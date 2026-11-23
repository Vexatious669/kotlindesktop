package me.enivzoy.anilife.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyManagerTest {
    @Test
    fun usesWindowsUserAgent() {
        assertTrue(USER_AGENT.contains("Windows NT"))
        assertTrue(USER_AGENT.startsWith("Mozilla/"))
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
