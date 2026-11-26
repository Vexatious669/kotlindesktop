package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoDetectMatcherTest {
    private fun card(
        id: Long,
        title: String,
        romaji: String? = null,
        english: String? = null,
        native: String? = null,
        year: Int? = null,
        aliases: List<String> = emptyList(),
    ) = AnimeCard(
        id = id,
        title = title,
        titleRomaji = romaji,
        titleEnglish = english,
        titleNative = native,
        year = year,
        aliases = aliases,
    )

    @Test
    fun parsesReleaseFolderWithGroupEpisodeAndHash() {
        val folder = AutoDetectMatcher.parseFolder("[SubsPlease] Sousou no Frieren - 01 (1080p) [ABCD1234]")
        assertEquals("Sousou no Frieren", folder.title)
        assertEquals("SubsPlease", folder.releaseGroup)
        assertNull(folder.year)
        assertEquals(1, folder.season)
    }

    @Test
    fun parsesGroupYearFolder() {
        val folder = AutoDetectMatcher.parseFolder("[Group] Jujutsu Kaisen (2020)")
        assertEquals("Jujutsu Kaisen", folder.title)
        assertEquals(2020, folder.year)
        assertEquals("Group", folder.releaseGroup)
    }

    @Test
    fun keepsHyphenatedWordAndReadsSeasonToken() {
        val folder = AutoDetectMatcher.parseFolder("Kaguya-sama wa Kokurasetai S2")
        assertTrue(folder.title.contains("Kaguya-sama wa Kokurasetai"))
        assertEquals(2, folder.season)
    }

    @Test
    fun readsTrailingRomanNumeralSequel() {
        val folder = AutoDetectMatcher.parseFolder("Mob Psycho 100 II")
        assertEquals("Mob Psycho 100", folder.title)
        assertEquals(2, folder.season)
    }

    @Test
    fun leavesNumberedTitleAlone() {
        val folder = AutoDetectMatcher.parseFolder("86 Eighty-Six")
        assertEquals("86 Eighty-Six", folder.title)
        assertEquals(1, folder.season)
    }

    @Test
    fun readsOrdinalSeasonWord() {
        val folder = AutoDetectMatcher.parseFolder("Bocchi the Rock! 2nd Season")
        assertEquals("Bocchi the Rock!", folder.title)
        assertEquals(2, folder.season)
    }

    @Test
    fun readsSeasonWordAndYearAndResolution() {
        val folder = AutoDetectMatcher.parseFolder("Spy x Family Season 3 (2025) [1080p]")
        assertEquals("Spy x Family", folder.title)
        assertEquals(3, folder.season)
        assertEquals(2025, folder.year)
    }

    @Test
    fun stripsVersionedTrailingEpisode() {
        val folder = AutoDetectMatcher.parseFolder("Frieren - Beyond Journey's End - 05v2")
        assertEquals("Frieren - Beyond Journey's End", folder.title)
    }

    @Test
    fun stripsPlainTrailingEpisode() {
        val folder = AutoDetectMatcher.parseFolder("One Piece - 1100")
        assertEquals("One Piece", folder.title)
    }

    @Test
    fun keepsSemicolonWord() {
        val folder = AutoDetectMatcher.parseFolder("Steins;Gate")
        assertEquals("Steins;Gate", folder.title)
    }

    @Test
    fun extractsYearFromTechnicalSuffixes() {
        val folder = AutoDetectMatcher.parseFolder("Anime Title (2025) [WEB-DL 1080p HEVC 10bit]")
        assertEquals("Anime Title", folder.title)
        assertEquals(2025, folder.year)
    }

    @Test
    fun stripsTrailingReleaseGroupAfterTheEpisode() {
        val folder = AutoDetectMatcher.parseFolder("Sousou no Frieren - 01 [SubsPlease]")
        assertEquals("Sousou no Frieren", folder.title)

        val anime = card(id = 3, title = "Sousou no Frieren")
        val match = AutoDetectMatcher.rank(folder, listOf(anime)).single()
        assertEquals(MatchConfidence.Exact, match.confidence)
        assertEquals(1.0, match.score, 0.0001)
    }

    @Test
    fun keepsABracketedTitleSuffixThatIsNotAGroupTag() {
        val folder = AutoDetectMatcher.parseFolder("Re:Zero [Director's Cut]")
        assertTrue(folder.title.contains("Director's Cut"))
    }

    @Test
    fun searchQueryOnlyAddsSeasonAfterFirstAndHidesJunk() {
        val frieren = AutoDetectMatcher.searchQuery(
            AutoDetectMatcher.parseFolder("[SubsPlease] Sousou no Frieren - 01 (1080p) [ABCD1234]"),
        )
        assertEquals("Sousou no Frieren", frieren)
        assertFalse(frieren.contains("SubsPlease"))
        assertFalse(frieren.contains("1080p"))

        val bocchi = AutoDetectMatcher.searchQuery(AutoDetectMatcher.parseFolder("Bocchi the Rock! 2nd Season"))
        assertEquals("Bocchi the Rock! Season 2", bocchi)

        val jujutsu = AutoDetectMatcher.searchQuery(AutoDetectMatcher.parseFolder("[Group] Jujutsu Kaisen (2020)"))
        assertEquals("Jujutsu Kaisen 2020", jujutsu)
        assertFalse(jujutsu.contains("Group"))

        val seasonless = AutoDetectMatcher.searchQuery(AutoDetectMatcher.parseFolder("Frieren - Beyond Journey's End - 05v2"))
        assertEquals("Frieren - Beyond Journey's End", seasonless)
    }

    @Test
    fun exactPrimaryTitleScoresFullWithExactConfidence() {
        val folder = AutoDetectMatcher.parseFolder("One Piece")
        val anime = card(id = 1, title = "One Piece", romaji = "One Piece")
        val match = AutoDetectMatcher.rank(folder, listOf(anime)).single()
        assertEquals(1.0, match.score, 0.0001)
        assertEquals(MatchConfidence.Exact, match.confidence)
        assertEquals(1L, match.anime.id)
    }

    @Test
    fun englishAlternateTitleIsAcceptedForRomajiPrimary() {
        val folder = AutoDetectMatcher.parseFolder("Frieren Beyond Journey's End")
        val anime = card(
            id = 2,
            title = "Sousou no Frieren",
            romaji = "Sousou no Frieren",
            english = "Frieren: Beyond Journey's End",
        )
        val match = AutoDetectMatcher.rank(folder, listOf(anime)).single()
        assertEquals(MatchConfidence.Exact, match.confidence)
        assertEquals(1.0, match.score, 0.0001)
    }

    @Test
    fun synonymAliasAndNativeTitleAreAccepted() {
        val folder = AutoDetectMatcher.parseFolder("Attack on Titan")
        val anime = card(
            id = 3,
            title = "Shingeki no Kyojin",
            native = "進撃の巨人",
            aliases = listOf("Attack on Titan", "AoT"),
        )
        val aliasMatch = AutoDetectMatcher.rank(folder, listOf(anime)).single()
        assertTrue(aliasMatch.score >= AutoDetectMatcher.AUTO_ACCEPT_SCORE)
        assertTrue(aliasMatch.confidence != MatchConfidence.Weak)

        val nativeFolder = AutoDetectMatcher.parseFolder("進撃の巨人")
        val nativeMatch = AutoDetectMatcher.rank(nativeFolder, listOf(anime)).single()
        assertTrue(nativeMatch.score >= AutoDetectMatcher.AUTO_ACCEPT_SCORE)
    }

    @Test
    fun sequelIsNotAutoAcceptedForSeasonlessFolder() {
        val original = card(id = 10, title = "Sousou no Frieren", romaji = "Sousou no Frieren")
        val sequel = card(id = 11, title = "Sousou no Frieren 2nd Season", romaji = "Sousou no Frieren 2nd Season")

        val bare = AutoDetectMatcher.parseFolder("Frieren")
        val sequelScore = AutoDetectMatcher.matchScore(bare, sequel)
        assertTrue(sequelScore < AutoDetectMatcher.AUTO_ACCEPT_SCORE)
        assertTrue(AutoDetectMatcher.matchScore(bare, original) > sequelScore)

        val ranked = AutoDetectMatcher.rank(AutoDetectMatcher.parseFolder("Sousou no Frieren"), listOf(sequel, original))
        assertEquals(10L, ranked.first().anime.id)
        assertEquals(MatchConfidence.Exact, ranked.first().confidence)
        assertTrue(ranked.none { it.anime.id == 11L })
    }

    @Test
    fun ambiguousCandidatesAreOrdered() {
        val folder = AutoDetectMatcher.parseFolder("Fullmetal Alchemist")
        val brotherhood = card(id = 20, title = "Fullmetal Alchemist: Brotherhood")
        val movie = card(id = 21, title = "Fullmetal Alchemist: The Movie")
        val ranked = AutoDetectMatcher.rank(folder, listOf(movie, brotherhood))
        assertEquals(2, ranked.size)
        assertTrue(ranked.all { it.confidence == MatchConfidence.Ambiguous })
        assertEquals(20L, ranked.first().anime.id)
        assertTrue(ranked[0].score >= ranked[1].score)
        assertTrue(ranked[0].score in AutoDetectMatcher.AMBIGUOUS_SCORE..AutoDetectMatcher.AUTO_ACCEPT_SCORE)
    }

    @Test
    fun garbageCandidateIsDroppedFromRank() {
        val folder = AutoDetectMatcher.parseFolder("One Piece")
        val real = card(id = 30, title = "One Piece")
        val garbage = card(id = 31, title = "Bleach")
        val ranked = AutoDetectMatcher.rank(folder, listOf(real, garbage))
        assertEquals(1, ranked.size)
        assertEquals(30L, ranked.single().anime.id)
        assertTrue(AutoDetectMatcher.matchScore(folder, garbage) < AutoDetectMatcher.AMBIGUOUS_SCORE)
    }

    @Test
    fun rankIsDistinctByIdAndKeepsBestEntry() {
        val folder = AutoDetectMatcher.parseFolder("One Piece")
        val exact = card(id = 40, title = "One Piece")
        val weaker = card(id = 40, title = "One Piece Film Red")
        val ranked = AutoDetectMatcher.rank(folder, listOf(weaker, exact))
        assertEquals(1, ranked.size)
        assertEquals(1.0, ranked.single().score, 0.0001)
    }

    @Test
    fun yearMismatchLowersScore() {
        val folder = AutoDetectMatcher.parseFolder("Jujutsu Kaisen (2020)")
        val matching = card(id = 50, title = "Jujutsu Kaisen", year = 2020)
        val mismatched = card(id = 51, title = "Jujutsu Kaisen", year = 2025)
        val matchingScore = AutoDetectMatcher.matchScore(folder, matching)
        val mismatchedScore = AutoDetectMatcher.matchScore(folder, mismatched)
        assertTrue(mismatchedScore < matchingScore)
        assertEquals(1.0, matchingScore, 0.0001)
        assertTrue(mismatchedScore <= 0.95 + 0.0001)
    }

    @Test
    fun readsSavedLibEntries() {
        val dir = Files.createTempDirectory("autodetect-savedlib")
        try {
            Files.writeString(
                dir.resolve("saved.lib"),
                """[{"id":1,"title":"X","cover":"","banner":"","episodes":3.0},{"id":2,"title":"Y","episodes":5.0}]""",
            )
            val hints = AutoDetectMatcher.readSavedLib(dir)
            assertEquals(2, hints.size)
            assertEquals(1L, hints[0].animeId)
            assertEquals("X", hints[0].title)
            assertEquals(3.0, hints[0].episode ?: Double.NaN, 0.0001)
            assertEquals(2L, hints[1].animeId)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun readSavedLibReturnsEmptyWhenMissing() {
        val dir = Files.createTempDirectory("autodetect-missing")
        try {
            assertTrue(AutoDetectMatcher.readSavedLib(dir).isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun readSavedLibSurvivesCorruptFile() {
        val dir = Files.createTempDirectory("autodetect-corrupt")
        try {
            Files.writeString(dir.resolve("saved.lib"), "this is not json {{{")
            assertTrue(AutoDetectMatcher.readSavedLib(dir).isEmpty())

            Files.writeString(dir.resolve("saved.lib"), """[{"id":7,"title":"Y","episodes":2.5""")
            val partial = AutoDetectMatcher.readSavedLib(dir).single()
            assertEquals(7L, partial.animeId)
            assertEquals("Y", partial.title)
            assertEquals(2.5, partial.episode ?: Double.NaN, 0.0001)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun readSavedLibFallsBackToTitleWithoutId() {
        val dir = Files.createTempDirectory("autodetect-title-only")
        try {
            Files.writeString(dir.resolve("saved.lib"), """[{"title":"Z","episodes":2.0}]""")
            val hint = AutoDetectMatcher.readSavedLib(dir).single()
            assertNull(hint.animeId)
            assertEquals("Z", hint.title)
            assertEquals(2.0, hint.episode ?: Double.NaN, 0.0001)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun hintIsPickedByTitleAndIgnoredWhenUnrelated() {
        val hints = listOf(
            SavedLibHint(1, "Sousou no Frieren", 28.0),
            SavedLibHint(2, "Bleach", 3.0),
        )
        val folder = AutoDetectMatcher.parseFolder("Sousou no Frieren [Erai-raws]")
        assertEquals(1L, AutoDetectMatcher.matchHint(folder, hints)?.animeId)

        val other = AutoDetectMatcher.parseFolder("One Piece")
        assertNull(AutoDetectMatcher.matchHint(other, hints))
    }
}
