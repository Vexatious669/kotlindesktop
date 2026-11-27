package me.enivzoy.anilife.app.data

import java.text.Normalizer

data class AudioLanguage(
    val code: String? = null,
    val region: String? = null,
    val label: String? = null,
) {
    val known: Boolean get() = code != null

    val key: String
        get() {
            val base = code ?: label?.let(::foldLanguageText)?.takeIf(String::isNotBlank) ?: "unknown"
            return region?.let { "$base-${it.lowercase()}" } ?: base
        }
}

internal fun foldLanguageText(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .trim()

object AudioLanguages {
    private val isoPattern = Regex("^([a-zA-Z]{2,3})(?:[-_]([a-zA-Z0-9]{2,4}))?$")
    private val unknownWords = setOf("", "und", "unknown", "unk", "none", "n/a", "null", "multi", "dual", "dual audio", "multi-audio", "original")

    private val codeAliases = mapOf(
        "jpn" to "ja", "eng" to "en", "spa" to "es", "por" to "pt", "fre" to "fr", "fra" to "fr",
        "ger" to "de", "deu" to "de", "ita" to "it", "rus" to "ru", "ara" to "ar", "hin" to "hi",
        "zho" to "zh", "chi" to "zh", "cmn" to "zh", "yue" to "zh", "kor" to "ko", "tha" to "th",
        "ind" to "id", "msa" to "ms", "may" to "ms", "tur" to "tr", "vie" to "vi", "ukr" to "uk",
        "heb" to "he", "fas" to "fa", "per" to "fa", "tam" to "ta", "tel" to "te", "ben" to "bn",
        "urd" to "ur", "pol" to "pl", "nld" to "nl", "dut" to "nl", "swe" to "sv", "nor" to "no",
        "dan" to "da", "fin" to "fi", "ell" to "el", "gre" to "el", "ces" to "cs", "cze" to "cs",
        "hun" to "hu", "ron" to "ro", "rum" to "ro", "fil" to "tl", "swa" to "sw",
    )

    private val knownCodes = setOf(
        "ja", "en", "es", "pt", "fr", "de", "it", "ru", "ar", "hi", "zh", "ko", "th", "id", "ms",
        "tr", "vi", "uk", "he", "fa", "ta", "te", "bn", "ur", "pl", "nl", "sv", "no", "da", "fi",
        "el", "cs", "hu", "ro", "tl", "sw",
    )

    private val languageNames = mapOf(
        "ja" to "Japanese", "en" to "English", "es" to "Spanish", "pt" to "Portuguese",
        "fr" to "French", "de" to "German", "it" to "Italian", "ru" to "Russian", "ar" to "Arabic",
        "hi" to "Hindi", "zh" to "Chinese", "ko" to "Korean", "th" to "Thai",
        "id" to "Indonesian", "ms" to "Malay", "tr" to "Turkish", "vi" to "Vietnamese",
        "uk" to "Ukrainian", "he" to "Hebrew", "fa" to "Persian", "ta" to "Tamil", "te" to "Telugu",
        "bn" to "Bengali", "ur" to "Urdu", "pl" to "Polish", "nl" to "Dutch", "sv" to "Swedish",
        "no" to "Norwegian", "da" to "Danish", "fi" to "Finnish", "el" to "Greek", "cs" to "Czech",
        "hu" to "Hungarian", "ro" to "Romanian", "tl" to "Tagalog", "sw" to "Swahili",
    )

    private val regionNames = mapOf(
        "419" to "Latin America", "ES" to "Spain", "BR" to "Brazil", "US" to "United States",
        "GB" to "United Kingdom", "MX" to "Mexico", "AR" to "Argentina", "CO" to "Colombia",
        "CL" to "Chile", "PE" to "Peru", "VE" to "Venezuela", "PT" to "Portugal", "FR" to "France",
        "DE" to "Germany", "IT" to "Italy", "CA" to "Canada", "AU" to "Australia",
    )

    private val languageCues = listOf(
        "latino" to "es", "castellano" to "es", "latam" to "es", "latin america" to "es",
        "brasil" to "pt", "brazil" to "pt", "brasileiro" to "pt", "portugues" to "pt", "portuguese" to "pt",
        "spanish" to "es", "espanol" to "es",
        "japanese" to "ja", "japones" to "ja",
        "english" to "en", "ingles" to "en",
        "french" to "fr", "frances" to "fr", "francais" to "fr",
        "german" to "de", "aleman" to "de", "deutsch" to "de",
        "italian" to "it", "italiano" to "it",
        "russian" to "ru", "arabic" to "ar", "hindi" to "hi",
        "chinese" to "zh", "mandarin" to "zh", "cantonese" to "zh",
        "korean" to "ko", "tagalog" to "tl", "filipino" to "tl",
        "indonesian" to "id", "malay" to "ms", "thai" to "th", "turkish" to "tr",
        "vietnamese" to "vi", "ukrainian" to "uk", "hebrew" to "he",
        "persian" to "fa", "farsi" to "fa", "polish" to "pl", "dutch" to "nl",
        "swedish" to "sv", "norwegian" to "no", "danish" to "da", "finnish" to "fi",
        "greek" to "el", "czech" to "cs", "hungarian" to "hu", "romanian" to "ro",
    )

    fun parse(raw: String?): AudioLanguage? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || foldLanguageText(text) in unknownWords) return null
        isoLanguage(text)?.let { return it }
        return namedLanguage(foldLanguageText(text))
    }

    fun fromMpv(lang: String?, title: String?): AudioLanguage =
        parse(lang)
            ?: parse(title)
            ?: AudioLanguage(label = title?.takeIf(String::isNotBlank))

    fun forSource(provider: String, server: String, language: String, audio: String): AudioLanguage {
        parse(language)?.let { return it }
        parse("$provider $server")?.let { return it }
        return if (audio.equals("dub", true)) AudioLanguage() else AudioLanguage(code = "ja")
    }

    fun shortName(language: AudioLanguage): String =
        language.code?.let { languageNames[it] } ?: language.label?.takeIf(String::isNotBlank) ?: "Unknown"

    fun displayName(language: AudioLanguage): String {
        val base = shortName(language)
        val region = language.region?.let { regionNames[it] ?: it.uppercase() } ?: return base
        return "$base ($region)"
    }

    fun regionName(region: String): String = regionNames[region] ?: region.uppercase()

    private fun isoLanguage(raw: String): AudioLanguage? {
        val match = isoPattern.matchEntire(raw.trim()) ?: return null
        val base = match.groupValues[1].lowercase()
        val code = codeAliases[base] ?: base.takeIf { it in knownCodes } ?: return null
        return AudioLanguage(code, normalizeRegion(match.groupValues[2]))
    }

    private fun namedLanguage(folded: String): AudioLanguage? {
        val code = languageCues.firstOrNull { folded.contains(it.first) }?.second ?: return null
        return AudioLanguage(code, regionCue(folded))
    }

    private fun normalizeRegion(raw: String): String? = when (val value = raw.trim().lowercase()) {
        "" -> null
        "latam", "la", "latin america" -> "419"
        "br", "brazil" -> "BR"
        "es", "spain" -> "ES"
        "us", "usa", "united states" -> "US"
        "gb", "uk", "united kingdom" -> "GB"
        else -> value.uppercase()
    }

    private fun regionCue(folded: String): String? = when {
        folded.contains("latin america") || folded.contains("latin spanish") || folded.contains("latino") || folded.contains("latam") -> "419"
        folded.contains("espana") || folded.contains("spain") -> "ES"
        folded.contains("brazil") || folded.contains("brasil") || folded.contains("brasileiro") -> "BR"
        folded.contains("mexico") -> "MX"
        folded.contains("united states") || Regex("\\bus\\b").containsMatchIn(folded) -> "US"
        folded.contains("united kingdom") || Regex("\\buk\\b").containsMatchIn(folded) -> "GB"
        folded.contains("portugal") -> "PT"
        else -> null
    }
}