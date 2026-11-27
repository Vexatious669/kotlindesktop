package me.enivzoy.anilife.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {
    @Test
    fun parsesEveryReleaseSuffix() {
        val hotfix = assertNotNull(parseAppVersion("1.1.1-HOTFIX"))
        assertEquals(ReleaseSuffix.HOTFIX, hotfix.suffix)
        assertEquals(ReleaseChannel.STABLE, hotfix.channel)

        val release = assertNotNull(parseAppVersion("1.1.1-RELEASE"))
        assertEquals(ReleaseSuffix.RELEASE, release.suffix)
        assertEquals(ReleaseChannel.STABLE, release.channel)

        val pre = assertNotNull(parseAppVersion("1.1.1-PRE"))
        assertEquals(ReleaseSuffix.PRE, pre.suffix)
        assertEquals(ReleaseChannel.PRERELEASE, pre.channel)

        val testing = assertNotNull(parseAppVersion("1.1.1-TESTING"))
        assertEquals(ReleaseSuffix.TESTING, testing.suffix)
        assertEquals(ReleaseChannel.PRERELEASE, testing.channel)

        assertEquals(1, hotfix.major)
        assertEquals(1, hotfix.minor)
        assertEquals(1, hotfix.patch)
    }

    @Test
    fun parsesBareVersionAsStableRelease() {
        val version = assertNotNull(parseAppVersion("1.1.0"))

        assertNull(version.suffix)
        assertEquals(ReleaseChannel.STABLE, version.channel)
        assertEquals(ReleaseSuffix.RELEASE.rank, version.rank())
        assertEquals("1.1.0", version.normalized())
    }

    @Test
    fun stripsLeadingVWhenParsingAndNormalizing() {
        assertEquals("1.1.1-HOTFIX", normalizeAppVersion("v1.1.1-HOTFIX"))
        assertEquals("1.1.1-HOTFIX", normalizeAppVersion("V1.1.1-HOTFIX"))
        assertEquals("1.1.1", normalizeAppVersion(" v1.1.1 "))
        assertEquals("1.1.1-PRE", normalizeAppVersion("v1.1.1-PRE"))
        assertEquals(
            "1.1.1-HOTFIX",
            assertNotNull(parseAppVersion("v1.1.1-HOTFIX")).normalized(),
        )
    }

    @Test
    fun rejectsMalformedVersions() {
        val malformed = listOf(
            "1.1.1-beta",
            "1.1.1-hotfix",
            "1.1.1-Release",
            "1.1.1-UNKNOWN",
            "1.1.1-RELEASE-extra",
            "1.1.1-HOTFIX-extra",
            "1.1.1-HOTFIX-HOTFIX",
            "1.1",
            "1",
            "1.1.1.1",
            "1.1.1-",
            "vv1.1.1",
            "v1.1-HOTFIX",
            "abc",
            "",
            "   ",
        )

        for (raw in malformed) {
            assertNull(parseAppVersion(raw), "expected '$raw' to be rejected")
            assertNull(normalizeAppVersion(raw), "expected '$raw' to normalize to null")
        }
        assertNull(parseAppVersion(null))
        assertNull(normalizeAppVersion(null))
    }

    @Test
    fun patchWinsOverSuffix() {
        val lowerPatchHotfix = assertNotNull(parseAppVersion("1.1.1-HOTFIX"))
        val higherPatchTesting = assertNotNull(parseAppVersion("1.1.2-TESTING"))

        assertTrue(higherPatchTesting.isNewerThan(lowerPatchHotfix))
        assertFalse(lowerPatchHotfix.isNewerThan(higherPatchTesting))
        assertTrue(higherPatchTesting > lowerPatchHotfix)
    }

    @Test
    fun hotfixOutranksReleaseOfTheSameVersion() {
        val release = assertNotNull(parseAppVersion("1.1.1-RELEASE"))
        val hotfix = assertNotNull(parseAppVersion("1.1.1-HOTFIX"))

        assertTrue(hotfix.isNewerThan(release))
        assertFalse(release.isNewerThan(hotfix))
    }

    @Test
    fun preOutranksTestingOfTheSameVersion() {
        val testing = assertNotNull(parseAppVersion("1.1.1-TESTING"))
        val pre = assertNotNull(parseAppVersion("1.1.1-PRE"))

        assertTrue(pre.isNewerThan(testing))
        assertFalse(testing.isNewerThan(pre))
    }

    @Test
    fun testingRanksBelowRelease() {
        val testing = assertNotNull(parseAppVersion("1.1.1-TESTING"))
        val release = assertNotNull(parseAppVersion("1.1.1-RELEASE"))

        assertTrue(testing < release)
        assertFalse(release < testing)
    }

    @Test
    fun bareVersionOrdersLikeRelease() {
        val bare = assertNotNull(parseAppVersion("1.1.1"))
        val release = assertNotNull(parseAppVersion("1.1.1-RELEASE"))
        val hotfix = assertNotNull(parseAppVersion("1.1.1-HOTFIX"))
        val pre = assertNotNull(parseAppVersion("1.1.1-PRE"))

        assertEquals(0, bare.compareTo(release))
        assertEquals(0, release.compareTo(bare))
        assertTrue(bare < hotfix)
        assertTrue(bare > pre)
    }

    @Test
    fun equalVersionsAreNotNewer() {
        val left = assertNotNull(parseAppVersion("1.1.1-HOTFIX"))
        val right = assertNotNull(parseAppVersion("v1.1.1-HOTFIX"))

        assertFalse(left.isNewerThan(right))
        assertFalse(right.isNewerThan(left))
    }

    @Test
    fun stableInstallRejectsPrereleaseCandidate() {
        val stable = assertNotNull(parseAppVersion("1.1.1"))
        val testing = assertNotNull(parseAppVersion("1.1.2-TESTING"))
        val pre = assertNotNull(parseAppVersion("1.1.2-PRE"))

        assertFalse(isUpdateAllowed(stable, testing))
        assertFalse(isUpdateAllowed(stable, pre))
        assertFalse(isUpdateAllowed(assertNotNull(parseAppVersion("1.1.1-RELEASE")), testing))
        assertFalse(isUpdateAllowed(assertNotNull(parseAppVersion("1.1.1-HOTFIX")), pre))
    }

    @Test
    fun prereleaseInstallAcceptsReleaseAndPrereleaseCandidates() {
        val prerelease = assertNotNull(parseAppVersion("1.1.1-PRE"))
        val release = assertNotNull(parseAppVersion("1.1.2-RELEASE"))
        val pre = assertNotNull(parseAppVersion("1.1.2-PRE"))
        val newerTesting = assertNotNull(parseAppVersion("1.1.2-TESTING"))
        val sameVersionTesting = assertNotNull(parseAppVersion("1.1.1-TESTING"))

        assertTrue(isUpdateAllowed(prerelease, release))
        assertTrue(isUpdateAllowed(prerelease, pre))
        assertTrue(isUpdateAllowed(prerelease, newerTesting))
        // Within the same triple TESTING ranks below PRE, so it is not an upgrade.
        assertFalse(isUpdateAllowed(prerelease, sameVersionTesting))
    }

    @Test
    fun stableInstallAcceptsHotfix() {
        val stable = assertNotNull(parseAppVersion("1.1.1"))
        val hotfix = assertNotNull(parseAppVersion("1.1.2-HOTFIX"))

        assertTrue(isUpdateAllowed(stable, hotfix))
    }

    @Test
    fun updatePolicyStillRequiresAStrictlyNewerCandidate() {
        val current = assertNotNull(parseAppVersion("1.1.2-HOTFIX"))

        assertFalse(isUpdateAllowed(current, assertNotNull(parseAppVersion("1.1.2-HOTFIX"))))
        assertFalse(isUpdateAllowed(current, assertNotNull(parseAppVersion("1.1.2"))))
        assertFalse(isUpdateAllowed(current, assertNotNull(parseAppVersion("1.1.1-HOTFIX"))))
        assertFalse(isUpdateAllowed(current, assertNotNull(parseAppVersion("1.1.2-PRE"))))
    }
}