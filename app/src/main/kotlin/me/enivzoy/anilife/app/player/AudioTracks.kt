package me.enivzoy.anilife.app.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.enivzoy.anilife.app.data.AnimeVideoSource
import me.enivzoy.anilife.app.data.AudioLanguage
import me.enivzoy.anilife.app.data.AudioLanguages

data class PlayerAudioTrack(
    val id: Int,
    val label: String,
    val language: AudioLanguage,
    val codec: String?,
    val channels: Int?,
    val selected: Boolean,
)

data class PlayerAudioChoice(
    val language: AudioLanguage,
    val embeddedTrackId: Int? = null,
    val source: AnimeVideoSource? = null,
    val selected: Boolean = false,
) {
    val embedded: Boolean get() = embeddedTrackId != null
}

internal fun parseAudioTracks(trackListJson: String): List<PlayerAudioTrack> =
    trackListElements(trackListJson).mapNotNull { track ->
        if (track.string("type") != "audio") return@mapNotNull null
        val id = track.string("id")?.toIntOrNull() ?: return@mapNotNull null
        val title = track.string("title")?.takeIf(String::isNotBlank)
        val language = AudioLanguages.fromMpv(track.string("lang"), title)
        val label = title ?: language.takeIf(AudioLanguage::known)?.let(AudioLanguages::shortName) ?: "Audio $id"
        PlayerAudioTrack(
            id = id,
            label = label,
            language = language,
            codec = track.string("codec")?.takeIf(String::isNotBlank),
            channels = track.string("demux-channel-count")?.toIntOrNull()
                ?: track.string("audio-channels")?.toIntOrNull(),
            selected = track.string("selected") == "true",
        )
    }

internal fun parseSubtitleTracks(trackListJson: String): List<PlayerSubtitleTrack> =
    trackListElements(trackListJson).mapNotNull { track ->
        if (track.string("type") != "sub") return@mapNotNull null
        val id = track.string("id")?.toIntOrNull() ?: return@mapNotNull null
        val code = track.string("lang")?.takeIf(String::isNotBlank)
        val parsed = AudioLanguages.parse(code)
        val title = track.string("title")?.takeIf(String::isNotBlank)
        val external = track.string("external") == "true"
        val label = title
            ?: parsed?.let(AudioLanguages::shortName)
            ?: code?.uppercase()
            ?: if (external) "External subtitle" else "Subtitle $id"
        PlayerSubtitleTrack(id, label, code, external, track.string("selected") == "true")
    }

internal fun audioLanguageMatches(actual: AudioLanguage, wanted: AudioLanguage): Boolean {
    if (actual.code == null || wanted.code == null) {
        return actual.label != null && actual.label.equals(wanted.label, ignoreCase = true)
    }
    if (actual.code != wanted.code) return false
    return actual.region == null || wanted.region == null || actual.region == wanted.region
}

internal fun chooseAudioTrack(tracks: List<PlayerAudioTrack>, wanted: AudioLanguage): PlayerAudioTrack? =
    tracks.firstOrNull { it.selected && exactLanguageMatch(it.language, wanted) }
        ?: tracks.firstOrNull { exactLanguageMatch(it.language, wanted) }
        ?: tracks.firstOrNull { it.selected && audioLanguageMatches(it.language, wanted) }
        ?: tracks.firstOrNull { audioLanguageMatches(it.language, wanted) }

private fun exactLanguageMatch(actual: AudioLanguage, wanted: AudioLanguage): Boolean =
    actual.code != null && actual.code == wanted.code && actual.region == wanted.region

// embedded wins because it switches without a reload; sources fill the languages it lacks
internal fun buildAudioChoices(
    tracks: List<PlayerAudioTrack>,
    sources: List<AnimeVideoSource>,
    currentSourceUrl: String?,
): List<PlayerAudioChoice> {
    val choices = mutableListOf<PlayerAudioChoice>()
    val currentEmbedded = tracks.firstOrNull { it.selected }?.language
    val currentSourceKnown = sources.firstOrNull { it.url == currentSourceUrl }
        ?.audioLanguage?.known == true
    // an unlabelled default track and its source describe the same audio; mark only the named one
    val foldUnknownIntoSource = currentSourceKnown && currentEmbedded?.known == false
    tracks.forEach { track ->
        choices += PlayerAudioChoice(
            language = track.language,
            embeddedTrackId = track.id,
            selected = track.selected && !(foldUnknownIntoSource && !track.language.known),
        )
    }
    val embeddedLanguages = tracks.map { it.language.key }.toSet()
    sources.groupBy { it.audioLanguage.key }.forEach { (key, group) ->
        if (key in embeddedLanguages) return@forEach
        val picked = group.firstOrNull { it.url == currentSourceUrl } ?: group.first()
        val selected = picked.url == currentSourceUrl &&
            (currentEmbedded == null || !currentEmbedded.known || audioLanguageMatches(currentEmbedded, picked.audioLanguage))
        choices += PlayerAudioChoice(language = picked.audioLanguage, source = picked, selected = selected)
    }
    return choices.sortedWith(
        compareByDescending<PlayerAudioChoice> { it.selected }
            .thenByDescending { it.embedded }
            .thenBy { it.language.key == "unknown" },
    )
}

// what a replacement stream must reproduce before its first frame is shown
internal data class PlaybackRestore(
    val positionSeconds: Double,
    val paused: Boolean,
    val subtitlePreference: String?,
    val audioLanguage: AudioLanguage?,
)

internal fun restorePosition(position: Double, duration: Double): Double =
    if (duration > 0.0) position.coerceIn(0.0, duration) else position.coerceAtLeast(0.0)

private fun trackListElements(trackListJson: String): List<JsonObject> = runCatching {
    (Json.parseToJsonElement(trackListJson) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
}.getOrDefault(emptyList())

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull