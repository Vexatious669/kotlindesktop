package me.enivzoy.anilife.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import me.enivzoy.anilife.core.model.AnimeCard
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import kotlin.math.abs

internal data class FolderTitle(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val releaseGroup: String? = null,
)

internal enum class MatchConfidence { Exact, Strong, Ambiguous, Weak }

internal data class TitleMatch(
    val anime: AnimeCard,
    val score: Double,
    val confidence: MatchConfidence,
)

internal data class SavedLibHint(val animeId: Long?, val title: String?, val episode: Double?)

internal object AutoDetectMatcher {
    const val AUTO_ACCEPT_SCORE = 0.82
    const val AMBIGUOUS_SCORE = 0.55

    private val junkTokens = listOf(
        "480p", "720p", "1080p", "1440p", "2160p", "4k",
        "web", "web-dl", "webdl", "webrip", "bluray", "blu-ray", "bdrip", "bd", "dvd", "dvdrip", "hdtv", "remux",
        "x264", "x265", "h264", "h265", "hevc", "avc", "10bit", "8bit", "hi10p",
        "aac", "ac3", "eac3", "flac", "opus", "ddp", "dd", "dts", "dual", "multi", "eng", "jap", "jpn", "sub", "dub", "subs",
        "repack", "proper", "uncensored", "batch", "complete", "amzn", "cr", "nf", "hulu", "atvp",
        "mkv", "mp4", "avi", "ts",
    )

    // longest first, otherwise "web" eats the "web" of "web-dl" and leaves "-dl" behind
    private val junkRegex = Regex(
        "(?i)(?<![a-z0-9])(?:" + junkTokens.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![a-z0-9])",
    )

    private val leadSquare = Regex("^\\[([^\\]]*)\\]")
    private val leadRound = Regex("^\\(([^)]*)\\)")
    private val blocks = Regex("\\[([^\\]]*)\\]|\\(([^)]*)\\)")
    private val sxxe = Regex("(?i)(?<![A-Za-z0-9])S(\\d{1,2})E\\d{1,3}(?:v\\d)?(?![A-Za-z0-9])")
    private val seasonS = Regex("(?i)(?<![A-Za-z0-9])S(\\d{1,2})(?![A-Za-z0-9])")
    private val seasonWord = Regex("(?i)\\b(?:(\\d{1,2})(?:st|nd|rd|th)\\s+season|season\\s+(\\d{1,2}))\\b")
    private val partWord = Regex("(?i)\\b(?:part|cour)\\s+(\\d{1,2})\\b")
    private val romanTail = Regex("(?i)(?:^|[\\s._-])(III|IV|II)\\s*$")
    private val episodePatterns = listOf(
        Regex("(?i)-+\\s*\\d{1,4}(?:\\.\\d+)?(?:v\\d+)?\\s*$"),
        Regex("(?i)(?:^|[\\s._-])(?:episode|ep)\\s*[._ ]?\\s*\\d{1,4}(?:\\.\\d+)?(?:v\\d+)?\\s*$"),
        // bare "e01" must be glued to its number, else a word ending in e would be eaten
        Regex("(?i)(?:^|[\\s._-])e[._]?\\d{1,4}(?:\\.\\d+)?(?:v\\d+)?\\s*$"),
    )
    private val whitespace = Regex("\\s+")
    // a short bracketed token at the very end is a release group, not part of the title
    private val trailingGroup = Regex("\\s*\\[[^\\[\\]\\s]{1,24}]\\s*$")
    private val savedLibJson = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parseFolder(name: String): FolderTitle {
        val raw = name.trim()
        if (raw.isEmpty()) return FolderTitle(raw)

        var text = raw
        var year: Int? = null
        var group: String? = null

        val lead = leadSquare.find(text) ?: leadRound.find(text)
        if (lead != null) {
            val content = lead.groupValues[1].trim()
            val leadYear = yearOf(content)
            when {
                leadYear != null -> year = leadYear
                content.isNotBlank() && !isJunkBlock(content) && !isHashBlock(content) -> group = content
            }
            text = text.removeRange(lead.range).trim()
        }

        val source = text
        text = blocks.replace(source) { match ->
            val content = if (match.value.startsWith("[")) match.groupValues[1].trim() else match.groupValues[2].trim()
            val blockYear = yearOf(content)
            when {
                blockYear != null -> {
                    if (year == null) year = blockYear
                    " "
                }
                isJunkBlock(content) || isHashBlock(content) -> " "
                else -> match.value
            }
        }
        text = junkRegex.replace(text, " ")
        text = trailingGroup.replace(text, " ")

        var season = 1
        sxxe.find(text)?.let {
            season = it.groupValues[1].toIntOrNull() ?: 1
            text = text.removeRange(it.range)
        }
        if (season == 1) {
            seasonS.find(text)?.let {
                val value = it.groupValues[1].toIntOrNull()
                if (value != null) {
                    season = value
                    text = text.removeRange(it.range)
                }
            }
        }
        if (season == 1) {
            seasonWord.find(text)?.let {
                val value = it.groupValues.drop(1).firstOrNull { part -> part.isNotBlank() }?.toIntOrNull()
                if (value != null) {
                    season = value
                    text = text.removeRange(it.range)
                }
            }
        }
        if (season == 1) {
            partWord.find(text)?.let {
                val value = it.groupValues[1].toIntOrNull()
                if (value != null) {
                    season = value
                    text = text.removeRange(it.range)
                }
            }
        }
        if (season == 1) {
            romanTail.find(text)?.let {
                val value = when (it.groupValues[1].uppercase()) {
                    "II" -> 2
                    "III" -> 3
                    "IV" -> 4
                    else -> 1
                }
                if (value > 1) {
                    season = value
                    text = text.removeRange(it.range)
                }
            }
        }

        var stripping = true
        while (stripping) {
            stripping = false
            for (pattern in episodePatterns) {
                val match = pattern.find(text) ?: continue
                text = text.removeRange(match.range)
                stripping = true
                break
            }
        }

        var title = whitespace.replace(text, " ").trim()
        title = title.trim('-', '_', '.', ':', ' ')
        title = whitespace.replace(title, " ").trim()
        if (title.isEmpty()) title = raw
        return FolderTitle(title = title, year = year, season = season, releaseGroup = group)
    }

    fun searchQuery(folder: FolderTitle): String {
        val base = whitespace.replace(folder.title.trim(), " ").trim()
        val year = folder.year?.takeIf { it > 0 }
        val season = folder.season ?: 1
        val withYear = if (year != null) "$base $year" else base
        val query = if (season > 1) "$withYear Season $season" else withYear
        return whitespace.replace(query, " ").trim()
    }

    fun matchScore(folder: FolderTitle, anime: AnimeCard): Double = evaluate(folder, anime).score

    fun rank(folder: FolderTitle, candidates: List<AnimeCard>): List<TitleMatch> = candidates
        .map { evaluate(folder, it) }
        .filter { it.confidence != MatchConfidence.Weak }
        .sortedByDescending { it.score }
        .distinctBy { it.anime.id }

    fun readSavedLib(directory: Path): List<SavedLibHint> = try {
        val file = directory.resolve("saved.lib")
        when {
            !Files.isRegularFile(file) -> emptyList()
            Files.size(file) > 1_048_576L -> emptyList()
            else -> {
                val text = String(Files.readAllBytes(file), Charsets.UTF_8).trim()
                if (text.isEmpty()) emptyList() else parseSavedLib(text)
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun matchHint(folder: FolderTitle, hints: List<SavedLibHint>): SavedLibHint? {
        val folderName = normalize(folder.title)
        if (folderName.isEmpty()) return null
        return hints.mapNotNull { hint ->
            val hintName = hint.title?.let(::normalize)?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val score = if (hintName == folderName) 1.0 else blended(folderName, hintName)
            hint to score
        }.filter { it.second >= 0.7 }.maxByOrNull { it.second }?.first
    }

    private fun evaluate(folder: FolderTitle, anime: AnimeCard): TitleMatch {
        val folderName = normalize(folder.title)
        if (folderName.isEmpty()) return TitleMatch(anime, 0.0, MatchConfidence.Weak)

        val names = mutableListOf<Pair<String, Boolean>>()
        names += normalize(anime.title) to true
        anime.titleRomaji?.let { names += normalize(it) to true }
        anime.titleEnglish?.let { names += normalize(it) to true }
        anime.titleNative?.let { names += normalize(it) to false }
        anime.aliases.forEach { names += normalize(it) to false }

        var best = 0.0
        var bestPrimary = true
        var exact = false
        for ((name, primary) in names) {
            if (name.isEmpty()) continue
            val isExact = name == folderName
            if (isExact) exact = true
            val score = if (isExact) 1.0 else blended(folderName, name)
            if (score > best || (score == best && primary && !bestPrimary)) {
                best = score
                bestPrimary = primary
            }
        }
        if (best <= 0.0) return TitleMatch(anime, 0.0, MatchConfidence.Weak)

        val folderSeason = folder.season ?: 1
        val candidateSeason = detectSeason(anime)
        val folderYear = folder.year
        val animeYear = anime.year
        if (folderYear != null && animeYear != null) {
            best = if (folderYear == animeYear) {
                best + 0.06
            } else if (abs(folderYear - animeYear) > 1) {
                best - 0.10
            } else {
                best
            }
        }
        best = when {
            candidateSeason == folderSeason -> best + 0.05
            candidateSeason > folderSeason -> best - 0.18
            else -> best - 0.10
        }
        if (!bestPrimary) best -= 0.05
        val score = best.coerceIn(0.0, 1.0)

        val confidence = when {
            exact -> MatchConfidence.Exact
            score >= AUTO_ACCEPT_SCORE -> MatchConfidence.Strong
            score >= AMBIGUOUS_SCORE -> MatchConfidence.Ambiguous
            else -> MatchConfidence.Weak
        }
        return TitleMatch(anime, score, confidence)
    }

    private fun blended(a: String, b: String): Double {
        val aTokens = a.split(' ').filter(String::isNotEmpty).toSet()
        val bTokens = b.split(' ').filter(String::isNotEmpty).toSet()
        val union = aTokens union bTokens
        val jaccard = if (union.isEmpty()) 0.0 else (aTokens intersect bTokens).size.toDouble() / union.size
        val maxLen = maxOf(a.length, b.length)
        val similarity = if (maxLen == 0) 0.0 else 1.0 - levenshtein(a, b).toDouble() / maxLen
        return 0.65 * jaccard + 0.35 * similarity.coerceIn(0.0, 1.0)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            val left = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (left == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            for (j in previous.indices) previous[j] = current[j]
        }
        return previous[b.length]
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            when {
                // apostrophes are dropped, not spaced: journey's must equal journeys
                ch == '\'' || ch == '\u2019' || ch == '`' -> Unit
                ch.isLetterOrDigit() -> out.append(ch.lowercaseChar())
                ch.category == CharCategory.NON_SPACING_MARK -> Unit
                else -> out.append(' ')
            }
        }
        return whitespace.replace(out, " ").trim()
    }

    private fun detectSeason(anime: AnimeCard): Int {
        val names = listOfNotNull(
            anime.title,
            anime.titleRomaji,
            anime.titleEnglish,
            anime.titleNative,
        ) + anime.aliases
        return names.mapNotNull(::seasonOf).maxOrNull() ?: 1
    }

    private fun seasonOf(value: String): Int? {
        sxxe.find(value)?.let { return it.groupValues[1].toIntOrNull() }
        seasonWord.find(value)?.let {
            it.groupValues.drop(1).firstOrNull { part -> part.isNotBlank() }?.toIntOrNull()?.let { value2 -> return value2 }
        }
        partWord.find(value)?.let { it.groupValues[1].toIntOrNull()?.let { value2 -> return value2 } }
        seasonS.find(value)?.let { it.groupValues[1].toIntOrNull()?.let { value2 -> return value2 } }
        romanTail.find(value)?.let {
            return when (it.groupValues[1].uppercase()) {
                "II" -> 2
                "III" -> 3
                "IV" -> 4
                else -> 1
            }
        }
        return null
    }

    private fun yearOf(content: String): Int? {
        if (content.length != 4) return null
        val value = content.toIntOrNull() ?: return null
        return value.takeIf { it in 1900..2099 }
    }

    private fun isJunkBlock(content: String): Boolean {
        val cleaned = content.trim()
        if (cleaned.isEmpty()) return true
        val leftovers = junkRegex.replace(cleaned, " ").replace(Regex("[^A-Za-z]"), "")
        return leftovers.isBlank()
    }

    private fun isHashBlock(content: String): Boolean = Regex("^[0-9A-Fa-f]{8}$").matches(content.trim())

    private fun parseSavedLib(text: String): List<SavedLibHint> {
        val parsed = runCatching {
            savedLibJson.parseToJsonElement(text).jsonArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 }
                val titlePrim = obj["title"] as? JsonPrimitive
                val title = titlePrim?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
                val episodes = (obj["episodes"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                if (id == null && title == null) null else SavedLibHint(id, title, episodes)
            }.distinctBy { it.animeId ?: it.title }
        }.getOrNull()
        return parsed ?: parseSavedLibFallback(text)
    }

    // lenient fallback for a file that was still being written: parse fields object by object
    private fun parseSavedLibFallback(text: String): List<SavedLibHint> = text.split('{')
        .drop(1)
        .mapNotNull { body ->
            val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
            val title = Regex("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(body)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
            val episodes = Regex("\"episodes\"\\s*:\\s*([0-9.]+)").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            if (id == null && title == null) null else SavedLibHint(id, title, episodes)
        }
        .distinctBy { it.animeId ?: it.title }
}
