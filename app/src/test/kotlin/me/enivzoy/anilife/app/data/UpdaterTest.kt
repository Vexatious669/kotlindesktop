package me.enivzoy.anilife.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdaterTest {
    @Test
    fun parsesMatchingPlatformAssetFromManifest() {
        val manifest = """
            {
              "version": "1.0.3",
              "releaseNotes": "Bug fixes",
              "releasedAt": 1794697773,
              "assets": [
                {"platform": "linux", "url": "https://example.com/anilife.AppImage"},
                {"platform": "windows", "url": "https://example.com/anilife.exe", "sha256": "abc123", "size": 42}
              ]
            }
        """.trimIndent()

        val update = parseUpdateManifest(manifest, currentVersion = "1.0.1", platform = "windows")

        assertEquals("1.0.3", update?.version)
        assertEquals("https://example.com/anilife.exe", update?.url)
        assertEquals("abc123", update?.sha256)
        assertEquals(42L, update?.sizeBytes)
    }

    @Test
    fun ignoresNewerManifestWithoutAValidDownloadUrl() {
        val manifest = """
            {"version":"1.0.3","assets":[{"platform":"windows","url":""}]}
        """.trimIndent()

        assertNull(parseUpdateManifest(manifest, currentVersion = "1.0.1", platform = "windows"))
    }

    @Test
    fun repairsLegacyGitHubReleaseAssetUrl() {
        val manifest = """
            {
              "version": "1.0.3",
              "assets": [{
                "platform": "windows",
                "url": "https://github.com/anilifecommunity/kotlindesktop/releases/download/v1.0.3/dist/anilife-1.0.3#base.exe"
              }]
            }
        """.trimIndent()

        val update = parseUpdateManifest(manifest, currentVersion = "1.0.1", platform = "windows")

        assertEquals(
            "https://github.com/anilifecommunity/kotlindesktop/releases/download/v1.0.3/anilife-1.0.3.base.exe",
            update?.url,
        )
    }
}
