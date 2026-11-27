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

    @Test
    fun parsesMsiAssetFromLatestManifest() {
        val manifest = """
            {
              "version": "1.0.9",
              "assets": [{
                "platform": "windows",
                "url": "https://github.com/anilifecommunity/kotlindesktop/releases/download/v1.0.9/anilife-1.0.9.base.msi",
                "sha256": "abc123",
                "sizeBytes": 42
              }]
            }
        """.trimIndent()

        val update = parseUpdateManifest(manifest, currentVersion = "1.0.1", platform = "windows")

        assertEquals("https://github.com/anilifecommunity/kotlindesktop/releases/download/v1.0.9/anilife-1.0.9.base.msi", update?.url)
        assertEquals("abc123", update?.sha256)
        assertEquals(42L, update?.sizeBytes)
    }

    @Test
    fun prefersMsiAssetFromGithubRelease() {
        val release = """
            {
              "tag_name": "v1.0.6",
              "assets": [
                {"name": "anilife-1.0.6.base.exe", "browser_download_url": "https://example.com/anilife.exe"},
                {"name": "anilife-1.0.6.base.msi", "browser_download_url": "https://example.com/anilife.msi", "size": 42, "digest": "sha256:abc123"}
              ]
            }
        """.trimIndent()

        val update = parseUpdateManifest(release, currentVersion = "1.0.1", platform = "windows")

        assertEquals("https://example.com/anilife.msi", update?.url)
        assertEquals("abc123", update?.sha256)
    }

    @Test
    fun doesNotOfferTheInstalledVersionAgain() {
        val manifest = """
            {
              "version": "1.0.4",
              "assets": [{
                "platform": "windows",
                "url": "https://example.com/anilife-1.0.4.exe"
              }]
            }
        """.trimIndent()

        assertNull(parseUpdateManifest(manifest, currentVersion = "1.0.4", platform = "windows"))
    }

    @Test
    fun parsesGitHubReleaseApiResponseWithoutFollowingAssetRedirects() {
        val release = """
            {
              "tag_name": "v1.0.5",
              "body": "Release notes",
              "published_at": "2026-11-15T00:16:53Z",
              "assets": [
                {
                  "name": "version.json",
                  "browser_download_url": "https://github.com/example/version.json"
                },
                {
                  "name": "anilife-1.0.5.base.exe",
                  "url": "https://api.github.com/repos/example/releases/assets/123",
                  "browser_download_url": "https://github.com/example/anilife-1.0.5.base.exe",
                  "size": 42,
                  "digest": "sha256:abc123"
                }
              ]
            }
        """.trimIndent()

        val update = parseUpdateManifest(release, currentVersion = "1.0.4", platform = "windows")

        assertEquals("1.0.5", update?.version)
        assertEquals("Release notes", update?.changelog)
        assertEquals("https://github.com/example/anilife-1.0.5.base.exe", update?.url)
        assertEquals("abc123", update?.sha256)
        assertEquals(42L, update?.sizeBytes)
        assertEquals(1_794_701_813L, update?.publishedAt)
    }

    @Test
    fun suffixedManifestIsOfferedToTheMatchingInstall() {
        val release = """
            {
              "tag_name": "v1.1.1-HOTFIX",
              "assets": [{
                "name": "anilife-1.1.1-HOTFIX.base.msi",
                "browser_download_url": "https://github.com/anilifecommunity/kotlindesktop/releases/download/v1.1.1-HOTFIX/anilife-1.1.1-HOTFIX.base.msi",
                "size": 42
              }]
            }
        """.trimIndent()

        val update = parseUpdateManifest(release, currentVersion = "1.1.0", platform = "windows")

        assertEquals("1.1.1-HOTFIX", update?.version)
        assertEquals(
            "https://github.com/anilifecommunity/kotlindesktop/releases/download/v1.1.1-HOTFIX/anilife-1.1.1-HOTFIX.base.msi",
            update?.url,
        )
    }

    @Test
    fun sameSuffixedVersionIsNotOfferedAgain() {
        val release = """
            {
              "tag_name": "v1.1.1-HOTFIX",
              "assets": [{
                "name": "anilife-1.1.1-HOTFIX.base.msi",
                "browser_download_url": "https://example.com/anilife-1.1.1-HOTFIX.base.msi"
              }]
            }
        """.trimIndent()

        assertNull(parseUpdateManifest(release, currentVersion = "1.1.1-HOTFIX", platform = "windows"))
    }

    @Test
    fun hotfixIsOfferedOverReleaseOfTheSameVersion() {
        val release = """
            {
              "tag_name": "v1.1.1-HOTFIX",
              "assets": [{
                "name": "anilife-1.1.1-HOTFIX.base.exe",
                "browser_download_url": "https://example.com/anilife-1.1.1-HOTFIX.base.exe"
              }]
            }
        """.trimIndent()

        val update = parseUpdateManifest(release, currentVersion = "1.1.1", platform = "windows")

        assertEquals("1.1.1-HOTFIX", update?.version)
    }

    @Test
    fun releaseIsNotDowngradedToPrereleaseOfTheSameVersion() {
        val pre = """
            {
              "version": "1.1.1-PRE",
              "assets": [{"platform": "windows", "url": "https://example.com/anilife-1.1.1-PRE.exe"}]
            }
        """.trimIndent()
        val testing = """
            {
              "version": "1.1.1-TESTING",
              "assets": [{"platform": "windows", "url": "https://example.com/anilife-1.1.1-TESTING.exe"}]
            }
        """.trimIndent()

        assertNull(parseUpdateManifest(pre, currentVersion = "1.1.1-RELEASE", platform = "windows"))
        assertNull(parseUpdateManifest(testing, currentVersion = "1.1.1-RELEASE", platform = "windows"))
        assertNull(parseUpdateManifest(pre, currentVersion = "1.1.1", platform = "windows"))
    }

    @Test
    fun stableInstallIgnoresNewerPrereleaseManifest() {
        val testing = """
            {
              "version": "1.1.1-TESTING",
              "assets": [{"platform": "windows", "url": "https://example.com/anilife-1.1.1-TESTING.exe"}]
            }
        """.trimIndent()
        val pre = """
            {
              "version": "1.1.1-PRE",
              "assets": [{"platform": "windows", "url": "https://example.com/anilife-1.1.1-PRE.exe"}]
            }
        """.trimIndent()

        assertNull(parseUpdateManifest(testing, currentVersion = "1.1.0", platform = "windows"))
        assertNull(parseUpdateManifest(pre, currentVersion = "1.1.0", platform = "windows"))
        assertNull(parseUpdateManifest(pre, currentVersion = "1.1.0-RELEASE", platform = "windows"))

        assertEquals(
            "1.1.1-PRE",
            parseUpdateManifest(pre, currentVersion = "1.1.0-PRE", platform = "windows")?.version,
        )
    }

    @Test
    fun stableChannelFieldCannotUnlockPrereleaseSuffix() {
        val manifest = """
            {
              "version": "1.1.1-PRE",
              "channel": "stable",
              "assets": [{"platform": "windows", "url": "https://example.com/anilife-1.1.1-PRE.exe"}]
            }
        """.trimIndent()

        assertNull(parseUpdateManifest(manifest, currentVersion = "1.1.0", platform = "windows"))
    }

    @Test
    fun releaseApiUrlNeverWinsOverTheSelectedAsset() {
        // Shape of a real GET /repos/{owner}/{repo}/releases element: the release object carries
        // an API `url` that serves JSON, and each asset carries browser_download_url.
        val release = """
            {
              "tag_name": "v1.1.1-HOTFIX",
              "url": "https://api.github.com/repos/anilifecommunity/kotlindesktop/releases/459305308",
              "assets": [
                {"name": "anilife-1.1.1-HOTFIX.base.msi", "url": "https://api.github.com/repos/anilifecommunity/kotlindesktop/releases/assets/1", "browser_download_url": "https://github.com/anilifecommunity/kotlindesktop/releases/download/v1.1.1-HOTFIX/anilife-1.1.1-HOTFIX.base.msi", "size": 42, "digest": "sha256:abc123"}
              ]
            }
        """.trimIndent()

        val update = parseUpdateManifest(release, currentVersion = "1.1.0", platform = "windows")

        assertEquals(
            "https://github.com/anilifecommunity/kotlindesktop/releases/download/v1.1.1-HOTFIX/anilife-1.1.1-HOTFIX.base.msi",
            update?.url,
        )
        assertEquals("abc123", update?.sha256)
        assertEquals(42L, update?.sizeBytes)
    }

    @Test
    fun releaseWithOnlyMetadataAssetsIsNotOffered() {
        // A release that has no installer must not fall back to its own API `url`.
        val release = """
            {
              "tag_name": "v1.1.1-HOTFIX",
              "url": "https://api.github.com/repos/anilifecommunity/kotlindesktop/releases/459305308",
              "assets": [
                {"name": "version.json", "browser_download_url": "https://github.com/anilifecommunity/kotlindesktop/releases/download/v1.1.1-HOTFIX/version.json"}
              ]
            }
        """.trimIndent()

        assertNull(parseUpdateManifest(release, currentVersion = "1.1.0", platform = "windows"))
        assertNull(parseReleaseList("[$release]", currentVersion = "1.1.0", platform = "windows"))
    }

    @Test
    fun releaseAssetWithALongerVersionTokenIsRejected() {
        val release = """
            {
              "tag_name": "v1.1.1-HOTFIX",
              "assets": [{
                "name": "anilife-1.1.10.base.msi",
                "browser_download_url": "https://example.com/anilife-1.1.10.base.msi"
              }]
            }
        """.trimIndent()

        assertNull(parseUpdateManifest(release, currentVersion = "1.1.0", platform = "windows"))
        assertNull(parseReleaseList("[$release]", currentVersion = "1.1.0", platform = "windows"))
    }

    @Test
    fun releaseListSelectsTheCorrectReleaseForTheCurrentInstall() {
        val releases = """
            [
              {
                "tag_name": "v1.1.2-PRE",
                "prerelease": true,
                "assets": [{
                  "name": "anilife-1.1.2-PRE.base.msi",
                  "browser_download_url": "https://example.com/anilife-1.1.2-PRE.base.msi",
                  "size": 10
                }]
              },
              {
                "tag_name": "v1.1.1-RELEASE",
                "assets": [{
                  "name": "anilife-1.1.1-RELEASE.base.msi",
                  "browser_download_url": "https://example.com/anilife-1.1.1-RELEASE.base.msi",
                  "size": 20
                }]
              }
            ]
        """.trimIndent()

        assertEquals(
            "1.1.1-RELEASE",
            parseReleaseList(releases, currentVersion = "1.1.0", platform = "windows")?.version,
        )
        assertEquals(
            "1.1.2-PRE",
            parseReleaseList(releases, currentVersion = "1.1.0-PRE", platform = "windows")?.version,
        )
    }

    @Test
    fun releaseListPicksTheHighestCompatibleVersion() {
        val releases = """
            [
              {
                "tag_name": "v1.1.3-TESTING",
                "prerelease": true,
                "assets": [{"name": "anilife-1.1.3-TESTING.base.exe", "browser_download_url": "https://example.com/anilife-1.1.3-TESTING.base.exe"}]
              },
              {
                "tag_name": "v1.1.2-PRE",
                "prerelease": true,
                "assets": [{"name": "anilife-1.1.2-PRE.base.exe", "browser_download_url": "https://example.com/anilife-1.1.2-PRE.base.exe"}]
              },
              {
                "tag_name": "v1.1.1-HOTFIX",
                "assets": [{"name": "anilife-1.1.1-HOTFIX.base.msi", "browser_download_url": "https://example.com/anilife-1.1.1-HOTFIX.base.msi"}]
              },
              {
                "tag_name": "v1.1.1",
                "assets": [{"name": "anilife-1.1.1.base.msi", "browser_download_url": "https://example.com/anilife-1.1.1.base.msi"}]
              }
            ]
        """.trimIndent()

        assertEquals(
            "1.1.1-HOTFIX",
            parseReleaseList(releases, currentVersion = "1.1.0", platform = "windows")?.version,
        )
        assertEquals(
            "1.1.3-TESTING",
            parseReleaseList(releases, currentVersion = "1.1.0-PRE", platform = "windows")?.version,
        )
    }

    @Test
    fun releaseAssetWhoseFilenameDisagreesWithTagIsRejected() {
        val release = """
            {
              "tag_name": "v1.1.1-HOTFIX",
              "assets": [{
                "name": "anilife-1.1.0-HOTFIX.base.msi",
                "browser_download_url": "https://example.com/anilife-1.1.0-HOTFIX.base.msi"
              }]
            }
        """.trimIndent()

        assertNull(parseUpdateManifest(release, currentVersion = "1.1.0", platform = "windows"))
    }

    @Test
    fun releaseAssetWhoseUrlTagSegmentDisagreesWithTagIsRejected() {
        val release = """
            {
              "tag_name": "v1.1.1-HOTFIX",
              "assets": [{
                "name": "anilife-1.1.1-HOTFIX.base.msi",
                "browser_download_url": "https://github.com/anilifecommunity/kotlindesktop/releases/download/v1.1.0/anilife-1.1.1-HOTFIX.base.msi"
              }]
            }
        """.trimIndent()

        assertNull(parseUpdateManifest(release, currentVersion = "1.1.0", platform = "windows"))
    }

    @Test
    fun releaseWithoutAUsableAssetReturnsNull() {
        val release = """
            {
              "tag_name": "v1.1.1-HOTFIX",
              "assets": [
                {"name": "version.json", "browser_download_url": "https://example.com/version.json"},
                {"name": "anilife-1.1.1-HOTFIX.zip", "browser_download_url": "https://example.com/anilife-1.1.1-HOTFIX.zip"}
              ]
            }
        """.trimIndent()

        assertNull(parseUpdateManifest(release, currentVersion = "1.1.0", platform = "windows"))
        assertNull(parseReleaseList("[$release]", currentVersion = "1.1.0", platform = "windows"))
    }

    @Test
    fun malformedOrUnknownSuffixTagIsIgnored() {
        for (tag in listOf("v1.1.1-beta", "v1.1.1-hotfix", "v1.1.1-UNKNOWN", "v1.1")) {
            val release = """
                {
                  "tag_name": "$tag",
                  "assets": [{"name": "anilife-installer.base.msi", "browser_download_url": "https://example.com/anilife-installer.base.msi"}]
                }
            """.trimIndent()
            assertNull(parseUpdateManifest(release, currentVersion = "1.1.0", platform = "windows"))
            assertNull(parseReleaseList("[$release]", currentVersion = "1.1.0", platform = "windows"))
        }
    }

    @Test
    fun emptyReleaseListReturnsNull() {
        assertNull(parseReleaseList("[]", currentVersion = "1.1.0", platform = "windows"))
        assertNull(parseReleaseList("[]", currentVersion = "1.1.0-PRE", platform = "windows"))
    }
}
