package me.enivzoy.anilife.app.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DependencyManagerTest {
    @Test
    fun storesDependenciesInAniLifeRuntimeDirectory() {
        val root = Files.createTempDirectory("anilife-appdata-").toFile()
        try {
            val appData = File(root, "Roaming")
            val userHome = File(root, "Home")

            assertEquals(File(appData, "AniLife/runtime"), dependencyDirectory(appData, userHome))
            assertEquals(File(userHome, ".anilife/runtime"), dependencyDirectory(null, userHome))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun migratesOnlyKnownDependenciesFromLegacyBinIntoManagedRuntime() {
        val root = Files.createTempDirectory("anilife-runtime-migration-")
        try {
            val legacy = root.resolve("bin")
            val runtime = root.resolve("AniLife/runtime")
            Files.createDirectories(legacy.resolve("mpv"))
            Files.writeString(legacy.resolve("mpv/libmpv-2.dll"), "mpv")
            Files.writeString(legacy.resolve("mpv.installed"), "version|sha256")
            Files.writeString(legacy.resolve("unrelated.txt"), "leave in place")

            migrateLegacyDependencyDirectory(legacy.toFile(), runtime.toFile())

            assertTrue(Files.isRegularFile(runtime.resolve("mpv/libmpv-2.dll")))
            assertTrue(Files.isRegularFile(runtime.resolve("mpv.installed")))
            assertTrue(Files.isRegularFile(legacy.resolve("unrelated.txt")))
            assertTrue(!Files.exists(legacy.resolve("mpv")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun keepsExistingManagedRuntimeInsteadOfReplacingItDuringMigration() {
        val root = Files.createTempDirectory("anilife-runtime-migration-conflict-")
        try {
            val legacy = root.resolve("bin")
            val runtime = root.resolve("AniLife/runtime")
            Files.createDirectories(legacy.resolve("mpv"))
            Files.createDirectories(runtime.resolve("mpv"))
            Files.writeString(legacy.resolve("mpv/libmpv-2.dll"), "old")
            Files.writeString(runtime.resolve("mpv/libmpv-2.dll"), "managed")

            migrateLegacyDependencyDirectory(legacy.toFile(), runtime.toFile())

            assertEquals("managed", Files.readString(runtime.resolve("mpv/libmpv-2.dll")))
            assertEquals("old", Files.readString(legacy.resolve("mpv/libmpv-2.dll")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun usesWindowsUserAgent() {
        assertTrue(USER_AGENT.contains("Windows NT"))
        assertTrue(USER_AGENT.startsWith("Mozilla/"))
    }

    @Test
    fun librarySearchReturnsNullWhenDependencyDirectoryDoesNotExist() {
        val root = Files.createTempDirectory("anilife-missing-mpv-")
        try {
            assertEquals(null, findLibrary(root.resolve("runtime/mpv"), "libmpv-2.dll"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun librarySearchIsRestrictedToTheManagedRuntimeDirectory() {
        val root = Files.createTempDirectory("anilife-managed-mpv-")
        try {
            val managed = Files.createDirectories(root.resolve("runtime/mpv"))
            val external = Files.createDirectory(root.resolve("external"))
            Files.writeString(external.resolve("libmpv-2.dll"), "external")
            assertEquals(null, findLibrary(managed, "libmpv-2.dll"))

            Files.writeString(managed.resolve("libmpv-2.dll"), "managed")
            assertEquals(managed.resolve("libmpv-2.dll"), findLibrary(managed, "libmpv-2.dll"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun usesOfficialDependencyEndpoints() {
        assertEquals(
            "https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse",
            ADOPTIUM_API,
        )
        assertEquals("https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest", MPV_RELEASES)
        assertEquals("https://api.github.com/repos/BtbN/FFmpeg-Builds/releases/latest", FFMPEG_RELEASES)
    }

    @Test
    fun fetchTextUsesStandardJsonAcceptHeaderForNonGitHubMetadataApis() {
        var acceptHeader: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/metadata") { exchange ->
            acceptHeader = exchange.requestHeaders.getFirst("Accept")
            respond(exchange, 200, "{}")
        }
        server.start()
        try {
            fetchText("http://127.0.0.1:${server.address.port}/metadata")
            assertEquals("application/json", acceptHeader)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fetchTextFollowsRedirects() {
        val body = """{"tag_name":"latest","assets":[]}"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/start") { exchange ->
            exchange.responseHeaders.set("Location", "http://127.0.0.1:$port/target")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/target") { exchange ->
            respond(exchange, 200, body)
        }
        server.start()
        try {
            assertEquals(body, fetchText("http://127.0.0.1:$port/start"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun networkErrorIncludesOperationHostAndTimeoutReason() {
        val error = networkError(
            "Fetching dependency metadata",
            java.net.URI("https://api.example.test/releases"),
            java.net.SocketTimeoutException("Read timed out"),
        )

        assertTrue(error.message.orEmpty().contains("Fetching dependency metadata"))
        assertTrue(error.message.orEmpty().contains("api.example.test"))
        assertTrue(error.message.orEmpty().contains("Read timed out"))
    }

    @Test
    fun fetchTextRejectsNonSuccessStatus() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/missing") { exchange ->
            respond(exchange, 404, "not found")
        }
        server.start()
        try {
            val error = assertFailsWith<IllegalStateException> { fetchText("http://127.0.0.1:$port/missing") }
            assertTrue(error.message.orEmpty().contains("404"))
            assertTrue(error.message.orEmpty().contains("127.0.0.1"))
            assertTrue(error.message.orEmpty().contains("not found"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun parsesJreSourceFromAdoptiumResponse() {
        val body = """
            [{
              "version": {"semver": "25.0.5+100000002.0.LTS"},
              "binary": {"package": {
                "link": "https://example.com/jre.zip",
                "checksum": "abc123"
              }}
            }]
        """.trimIndent()

        val source = parseJreSource(body)

        assertEquals("https://example.com/jre.zip", source.url)
        assertEquals("abc123", source.sha256)
        assertEquals("25.0.5+100000002.0.LTS", source.version)
    }

    @Test
    fun parsesMpvSevenZipReleaseAsset() {
        val body = """
            {
              "tag_name": "20261111",
              "assets": [
                {"name": "mpv-i686-20261111-git-abcdef0.7z",
                 "browser_download_url": "https://example.com/mpv-i686.7z",
                 "digest": "sha256:aaa111"},
                {"name": "mpv-dev-x86_64-20261111-git-abcdef0.7z",
                 "browser_download_url": "https://example.com/mpv-dev-x86_64.7z",
                 "digest": "sha256:def456"}
              ]
            }
        """.trimIndent()

        val source = parseReleaseSource(body, releaseAssetPattern(Dependency.MPV))

        assertEquals("https://example.com/mpv-dev-x86_64.7z", source.url)
        assertEquals("def456", source.sha256)
        assertEquals("20261111", source.version)
    }

    @Test
    fun parsesFfmpegReleaseAsset() {
        val body = """
            {
              "tag_name": "latest",
              "assets": [
                {"name": "ffmpeg-master-latest-win64-gpl-shared.zip",
                 "browser_download_url": "https://example.com/shared.zip",
                 "digest": "sha256:aaa111"},
                {"name": "ffmpeg-master-latest-win64-gpl.zip",
                 "browser_download_url": "https://example.com/ffmpeg.zip",
                 "digest": "sha256:def456"}
              ]
            }
        """.trimIndent()

        val source = parseReleaseSource(body, releaseAssetPattern(Dependency.FFMPEG))

        assertEquals("https://example.com/ffmpeg.zip", source.url)
        assertEquals("def456", source.sha256)
        assertEquals("latest", source.version)
    }

    @Test
    fun recognizesInstalledUpscalerScaleModels() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val directory = Files.createTempDirectory("anilife-dependencies-")
        try {
            val files = listOf(
                "jre/bin/java.exe",
                "mpv/libmpv-2.dll",
                "ffmpeg/ffmpeg.exe",
                "ffmpeg/ffprobe.exe",
                "upscaler/realesrgan-ncnn-vulkan.exe",
                "upscaler/models/realesr-animevideov3-x4.param",
                "upscaler/models/realesr-animevideov3-x4.bin",
            )
            files.forEach { relative ->
                val file = directory.resolve(relative)
                Files.createDirectories(file.parent)
                Files.writeString(file, "installed")
            }
            Dependency.entries.forEach { dependency ->
                Files.writeString(directory.resolve("${dependency.name.lowercase()}.installed"), "version|sha256")
            }
            val manager = DependencyManager(CoroutineScope(Dispatchers.Unconfined), directory.toFile())

            assertTrue(manager.isSetupComplete())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun marksAlreadyInstalledDependenciesReady() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val directory = Files.createTempDirectory("anilife-dependencies-")
        try {
            val files = listOf(
                "jre/bin/java.exe",
                "mpv/libmpv-2.dll",
                "ffmpeg/ffmpeg.exe",
                "ffmpeg/ffprobe.exe",
                "upscaler/realesrgan-ncnn-vulkan.exe",
                "upscaler/models/realesr-animevideov3-x4.param",
                "upscaler/models/realesr-animevideov3-x4.bin",
            )
            files.forEach { relative ->
                val file = directory.resolve(relative)
                Files.createDirectories(file.parent)
                Files.writeString(file, "installed")
            }
            Dependency.entries.forEach { dependency ->
                Files.writeString(directory.resolve("${dependency.name.lowercase()}.installed"), "version|sha256")
            }
            val manager = DependencyManager(CoroutineScope(Dispatchers.Unconfined), directory.toFile())

            val result = runBlocking { manager.downloadAndInstall { _, _ -> } }

            assertTrue(result.isSuccess)
            Dependency.entries.forEach { dependency ->
                val state = requireNotNull(manager.states.value[dependency])
                assertEquals(DependencyStatus.Installed, state.status)
                assertEquals(1f, state.progress)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun usesOfficialUpscalerRelease() {
        val source = upscalerSource()

        assertEquals(
            "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesrgan-ncnn-vulkan-20220424-windows.zip",
            source.url,
        )
        assertEquals(UPSCALER_SHA256, source.sha256)
        assertEquals("v0.2.5.0", source.version)
    }
}

private fun respond(exchange: HttpExchange, status: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.set("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}
