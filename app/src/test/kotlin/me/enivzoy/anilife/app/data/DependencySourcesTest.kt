package me.enivzoy.anilife.app.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencySourcesTest {

    @Test
    fun btbNChecksumsUseOnlyTheExactWin64GplZip() {
        val body = """
            5aa6f9a6b8a11ac5872731b063e571f3eb8d6697dade8e40b932d188be29632d  ffmpeg-master-latest-win64-gpl-shared.zip
            6a76339ee6f1760a8bbf16853c281efcda25d080a87d062c74a087cecb6e0bf4  ffmpeg-master-latest-win64-gpl.zip
            503a177dff3ecc70ecf0615387edcd42c60fa9b4df88d713aaee9050b00541b2  ffmpeg-master-latest-win64-gpl-8.1.zip
            8495540268f6e8af80a0ba4d4916fa7dbb416c5e6755195759d9239467ca07a1  ffmpeg-master-latest-winarm64-gpl.zip
        """.trimIndent()

        val source = parseBtbNChecksums(body)
        assertEquals(
            "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip",
            source.url,
        )
        assertEquals("6a76339ee6f1760a8bbf16853c281efcda25d080a87d062c74a087cecb6e0bf4", source.sha256)
        assertEquals("latest", source.version)

        val withoutGpl = body.lineSequence().filterNot { it.endsWith(BTBN_ASSET) }.joinToString("\n")
        val failure = assertFailsWith<IllegalStateException> { parseBtbNChecksums(withoutGpl) }
        assertTrue(failure.message.orEmpty().contains(BTBN_ASSET))
    }

    @Test
    fun parsesTagFromReleaseRedirect() {
        assertEquals(
            "20261111",
            parseTagFromLocation("https://github.com/shinchiro/mpv-winbuild-cmake/releases/tag/20261111"),
        )
        assertFailsWith<IllegalStateException> {
            parseTagFromLocation("https://github.com/shinchiro/mpv-winbuild-cmake/releases")
        }
    }

    @Test
    fun expandedAssetsPickTheDevPackageAndItsDigest() {
        val html = """
            <div class="Box-row">
              <clipboard-copy id="c1" aria-label="Copy to clipboard digest for mpv-dev-x86_64-v3-20261111-git-bcde6b93cc.7z" type="button" value="sha256:19d9bb4c5f8249f48c2413a0b7c473116552b8cf55404a67a5a568b2d8b09bcb">
            </div>
            <div class="Box-row">
              <clipboard-copy id="c2" aria-label="Copy to clipboard digest for mpv-dev-x86_64-20261111-git-bcde6b93cc.7z" type="button" value="sha256:273c079b11ea5575822f972756f4c9a71149a5c10130353abeb8586738938452">
            </div>
            <div class="Box-row">
              <clipboard-copy id="c3" aria-label="Copy to clipboard digest for mpv-x86_64-20261111-git-bcde6b93cc.7z" type="button" value="sha256:8495540268f6e8af80a0ba4d4916fa7dbb416c5e6755195759d9239467ca07a1">
            </div>
        """.trimIndent()

        val source = parseExpandedAssets(html, "20261111", releaseAssetPattern(Dependency.MPV))
        assertEquals(
            "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20261111/" +
                "mpv-dev-x86_64-20261111-git-bcde6b93cc.7z",
            source.url,
        )
        assertEquals("273c079b11ea5575822f972756f4c9a71149a5c10130353abeb8586738938452", source.sha256)
        assertEquals("20261111", source.version)
    }

    @Test
    fun expandedAssetsFailWhenNoDigestIsShown() {
        val html = """<clipboard-copy aria-label="Copy to clipboard digest for mpv-dev-x86_64-20261111-git-bcde6b93cc.7z" value="">"""
        assertFailsWith<IllegalStateException> {
            parseExpandedAssets(html, "20261111", releaseAssetPattern(Dependency.MPV))
        }
    }

    @Test
    fun parsesAzulPackageListAndDetails() {
        val list = """
            [
              {"name":"zulu25.38.201-ca-fx-jre25.0.5.1-win_x64.zip","package_uuid":"fx-id"},
              {"name":"zulu25.38.201-ca-jre25.0.5.1-win_x64.zip","package_uuid":"d0303055-af0a-4bd3-ac80-6fd11478e25b"}
            ]
        """.trimIndent()
        assertEquals("d0303055-af0a-4bd3-ac80-6fd11478e25b", parseAzulPackageList(list))

        val details = """
            {"download_url":"https://cdn.azul.com/zulu/bin/zulu25.38.201-ca-jre25.0.5.1-win_x64.zip",
             "sha256_hash":"6c2c84c1541ea2db58bf15387454b21911505ede8d02cfcb7afba2de680ae379",
             "java_version":[25,0,5,1]}
        """.trimIndent()
        val source = parseAzulPackageDetails(details)
        assertEquals("https://cdn.azul.com/zulu/bin/zulu25.38.201-ca-jre25.0.5.1-win_x64.zip", source.url)
        assertEquals("6c2c84c1541ea2db58bf15387454b21911505ede8d02cfcb7afba2de680ae379", source.sha256)
        assertEquals("25.0.5.1", source.version)

        assertFailsWith<IllegalStateException> {
            parseAzulPackageList("""[{"name":"zulu25.38.201-ca-fx-jre25.0.5.1-win_x64.zip","package_uuid":"only-fx"}]""")
        }
    }

    @Test
    fun parsesGyanSha256File() {
        val source = parseGyanChecksum(
            "b9bbbb3acece000cbeec994f856649354e52c773807841e78908374b24d658fd\n",
        )
        assertEquals("https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip", source.url)
        assertEquals("b9bbbb3acece000cbeec994f856649354e52c773807841e78908374b24d658fd", source.sha256)
        assertFailsWith<IllegalStateException> { parseGyanChecksum("not a hash") }
    }

    @Test
    fun libmpvGuardRejectsPlayerAndV3Builds() {
        val pattern = releaseAssetPattern(Dependency.MPV)
        assertFalse(pattern.matches("mpv-x86_64-20251130-git-23f9381.7z"))
        assertFalse(pattern.matches("mpv-dev-x86_64-v3-20220918-git-ba81e4e.7z"))
        assertTrue(pattern.matches("mpv-dev-x86_64-20251130-git-23f9381.7z"))
    }

    @Test
    fun candidateChainFallsBackWhenTheDownloadFails() = runBlocking {
        val good = "second mirror payload".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/broken") { exchange -> respond(exchange, 500, "") }
        server.createContext("/good") { exchange -> respond(exchange, 200, good) }
        server.start()
        val dir = Files.createTempDirectory("anilife-chain-")
        val manager = DependencyManager(CoroutineScope(Dispatchers.Unconfined), dir.toFile())
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val tried = mutableListOf<String>()
            val installed = firstWorkingSource(
                Dependency.MPV,
                listOf(
                    "broken mirror" to {
                        tried += "broken"
                        DependencySource("$base/broken", sha256Of(good), "test")
                    },
                    "good mirror" to {
                        tried += "good"
                        DependencySource("$base/good", sha256Of(good), "test")
                    },
                ),
            ) { source ->
                val target = dir.resolve("mpv.7z")
                manager.download(source, target, Dependency.MPV) { _, _ -> }
                Files.readAllBytes(target)
            }
            assertEquals(listOf("broken", "good"), tried)
            assertEquals(String(good), String(installed))
        } finally {
            server.stop(0)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun candidateWithWrongChecksumIsRejectedAndTheNextIsUsed() = runBlocking {
        val wrong = "tampered payload".toByteArray()
        val right = "real payload".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/wrong") { exchange -> respond(exchange, 200, wrong) }
        server.createContext("/right") { exchange -> respond(exchange, 200, right) }
        server.start()
        val dir = Files.createTempDirectory("anilife-chain-bad-hash-")
        val manager = DependencyManager(CoroutineScope(Dispatchers.Unconfined), dir.toFile())
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val installed = firstWorkingSource(
                Dependency.MPV,
                listOf(
                    "tampered mirror" to { DependencySource("$base/wrong", sha256Of(right), "test") },
                    "honest mirror" to { DependencySource("$base/right", sha256Of(right), "test") },
                ),
            ) { source ->
                val target = dir.resolve("mpv.7z")
                manager.download(source, target, Dependency.MPV) { _, _ -> }
                Files.readAllBytes(target)
            }
            assertEquals(String(right), String(installed))
        } finally {
            server.stop(0)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun allCandidatesFailingGiveOneShortHumanError() = runBlocking {
        val failure = assertFailsWith<SourceResolutionException> {
            firstWorkingSource(
                Dependency.MPV,
                listOf(
                    "GitHub API" to { error("HTTP 403 from api.github.com: {\"message\":\"API rate limit exceeded\"}") },
                    "GitHub releases page" to { error("HTTP 404 from github.com: not found") },
                    "pinned GitHub asset" to { error("HTTP 503 from objects.githubusercontent.com: unavailable") },
                ),
            ) { }
        }
        val message = failure.message.orEmpty()
        assertTrue(message.startsWith("libmpv: "))
        assertTrue(message.contains("GitHub API - rate limited"))
        assertTrue(message.contains("GitHub releases page - HTTP 404"))
        assertTrue(message.contains("pinned GitHub asset - HTTP 503"))
        assertFalse(message.contains("{"))
        assertTrue(message.length <= 240)
        assertEquals(3, message.count { it == ';' } + 1)
    }

    @Test
    fun rateLimitIsReportedWithoutJson() {
        assertEquals(
            "rate limited",
            shortReason(IllegalStateException("HTTP 403 from api.github.com: {\"message\":\"API rate limit exceeded for 1.2.3.4\"}")),
        )
        assertEquals("HTTP 500", shortReason(IllegalStateException("HTTP 500 from example.com: oops")))
        assertEquals("timed out", shortReason(java.net.SocketTimeoutException("Read timed out")))
        assertEquals("unreachable", shortReason(java.net.UnknownHostException("api.github.com")))
    }

    @Test
    fun jreCandidatesTryAdoptiumThenAzulThenThePinnedBuild() {
        val labels = candidateSources(Dependency.JRE) { url ->
            when {
                url == ADOPTIUM_API -> error("HTTP 500 from api.adoptium.net: down")
                url == AZUL_PACKAGES -> error("HTTP 503 from api.azul.com: down")
                else -> error("unexpected $url")
            }
        }.map { it.first }
        assertEquals(listOf("Adoptium API", "Azul API", "pinned Temurin"), labels)
    }

    @Test
    fun mpvCandidatesAreAllGitHubAndUseTheDevAsset() {
        val labels = candidateSources(Dependency.MPV) { error("offline") }.map { it.first }
        assertEquals(listOf("GitHub API", "GitHub releases page", "pinned GitHub asset"), labels)
        val pattern = releaseAssetPattern(Dependency.MPV)
        assertTrue(pattern.matches(MPV_PINNED_URL.substringAfterLast('/')))
    }

    @Test
    fun ffmpegCandidatesPreferNonGitHubHostsBeforeTheApi() {
        val labels = candidateSources(Dependency.FFMPEG) { error("offline") }.map { it.first }
        assertEquals(listOf("BtbN checksums", "gyan.dev", "GitHub API"), labels)
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray) {
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
        exchange.close()
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) =
        respond(exchange, status, body.toByteArray())
}