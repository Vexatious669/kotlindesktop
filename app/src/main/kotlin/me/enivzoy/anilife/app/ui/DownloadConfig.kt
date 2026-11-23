package me.enivzoy.anilife.app.ui

import me.enivzoy.anilife.core.model.AnimeDetail

enum class AudioType { Sub, Dub }

enum class Resolution(val label: String, val factor: Float) {
    Auto("Auto", 1.0f),
    P360("360p", 0.4f),
    P480("480p", 0.55f),
    P720("720p", 0.7f),
    P1080("1080p", 1.0f),
}

data class DownloadConfig(
    val animeId: Long,
    val audio: AudioType,
    val resolution: Resolution,
    val episodeNumbers: List<Int>,
    val saveDirectory: String,
)