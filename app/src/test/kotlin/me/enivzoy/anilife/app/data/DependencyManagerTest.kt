package me.enivzoy.anilife.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class DependencyManagerTest {
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
    fun parsesReleaseAssetDigest() {
        val body = """
            {
              "tag_name": "v0.42.0",
              "assets": [
                {"name": "notes.txt", "browser_download_url": "https://example.com/notes.txt"},
                {"name": "libmpv-v0.42.0-x86_64-w64-mingw32-lgpl.zip",
                 "browser_download_url": "https://example.com/libmpv.zip",
                 "digest": "sha256:def456"}
              ]
            }
        """.trimIndent()

        val source = parseReleaseSource(body, Regex("libmpv-[^/]*x86_64-w64-mingw32-lgpl\\.zip$"))

        assertEquals("https://example.com/libmpv.zip", source.url)
        assertEquals("def456", source.sha256)
        assertEquals("v0.42.0", source.version)
    }
}
