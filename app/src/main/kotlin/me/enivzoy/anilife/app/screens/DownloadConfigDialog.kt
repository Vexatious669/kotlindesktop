package me.enivzoy.anilife.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.enivzoy.anilife.app.data.AppSettings
import me.enivzoy.anilife.app.ui.AniLifeColors
import me.enivzoy.anilife.app.ui.AudioType
import me.enivzoy.anilife.app.ui.DownloadConfig
import me.enivzoy.anilife.app.ui.Resolution
import me.enivzoy.anilife.core.model.AnimeDetail

@Composable
fun DownloadConfigDialog(
    detail: AnimeDetail,
    downloadedEpisodeNumbers: Set<Int>,
    defaultStartEpisode: Int,
    defaultSaveDirectory: String,
    onDismiss: () -> Unit,
    onConfirm: (DownloadConfig) -> Unit,
) {
    val episodes = remember(detail.id) {
        (1..(detail.episodes ?: 1)).toList()
    }
    var selectedAudio by remember { mutableStateOf(AudioType.Sub) }
    var selectedResolution by remember { mutableStateOf(Resolution.Auto) }
    val selectedEpisodes = remember { mutableStateListOf<Int>() }
    var presetStart by remember { mutableIntStateOf(defaultStartEpisode.coerceIn(1, episodes.size.coerceAtLeast(1))) }

    // Default to "Unwatched" preset on first composition
    remember {
        val unwatched = episodes.filter { it >= defaultStartEpisode && it !in downloadedEpisodeNumbers }
        selectedEpisodes.addAll(unwatched)
        true
    }

    val selectedCount by remember { derivedStateOf { selectedEpisodes.size } }
    val estimatedMb = (selectedCount * 330 * selectedResolution.factor).toInt()
    val estimatedText = if (estimatedMb >= 1024) "~%.1f GB".format(estimatedMb / 1024f) else "$estimatedMb MB"

    fun applyPresetUnwatched() {
        selectedEpisodes.clear()
        selectedEpisodes.addAll(episodes.filter { it >= defaultStartEpisode && it !in downloadedEpisodeNumbers })
    }
    fun applyPresetAll() {
        selectedEpisodes.clear()
        selectedEpisodes.addAll(episodes.filter { it !in downloadedEpisodeNumbers })
    }
    fun applyPresetFrom(start: Int) {
        val clamped = start.coerceIn(1, episodes.size.coerceAtLeast(1))
        presetStart = clamped
        selectedEpisodes.clear()
        selectedEpisodes.addAll(episodes.filter { it >= clamped && it !in downloadedEpisodeNumbers })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AniLifeColors.Card,
        titleContentColor = AniLifeColors.Foreground,
        textContentColor = AniLifeColors.MutedForeground,
        title = { Text("Download Episodes", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.widthIn(max = 720.dp).heightIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Section A: Audio
                Text("Audio", color = AniLifeColors.MutedForeground, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioChip("Sub", selectedAudio == AudioType.Sub) { selectedAudio = AudioType.Sub }
                    AudioChip("Dub", selectedAudio == AudioType.Dub) { selectedAudio = AudioType.Dub }
                }
                // Section B: Preset
                Text("Episodes", color = AniLifeColors.MutedForeground, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresetChip("Unwatched", onClick = { applyPresetUnwatched() })
                    PresetChip("All", onClick = { applyPresetAll() })
                    PresetChip("From $presetStart", onClick = { applyPresetFrom(presetStart) })
                }
                // Section C: Resolution
                Text("Resolution", color = AniLifeColors.MutedForeground, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Resolution.values().forEach { res ->
                        ResChip(res.label, selectedResolution == res) { selectedResolution = res }
                    }
                }
                // Section D: Episode grid
                if (episodes.isNotEmpty()) {
                    Box(Modifier.heightIn(max = 260.dp)) {
                        LazyVerticalGrid(columns = GridCells.Fixed(6), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(episodes) { ep ->
                                val downloaded = ep in downloadedEpisodeNumbers
                                val selected = ep in selectedEpisodes
                                val bg = when {
                                    downloaded -> AniLifeColors.Card.copy(alpha = 0.35f)
                                    selected -> AniLifeColors.Primary.copy(alpha = 0.25f)
                                    else -> AniLifeColors.Card
                                }
                                val border = if (selected && !downloaded) 2.dp else 0.dp
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(bg)
                                        .then(if (border > 0.dp) Modifier.border(border, AniLifeColors.Primary, RoundedCornerShape(8.dp)) else Modifier)
                                        .then(if (!downloaded) Modifier.clickable {
                                            if (ep in selectedEpisodes) selectedEpisodes.remove(ep) else selectedEpisodes.add(ep)
                                        } else Modifier),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        ep.toString(),
                                        color = if (downloaded) AniLifeColors.MutedForeground.copy(alpha = 0.35f) else AniLifeColors.Foreground,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text("No episodes available.", color = AniLifeColors.MutedForeground, fontSize = 12.sp)
                }
                // Section E: Save location
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Save to:", color = AniLifeColors.MutedForeground, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(defaultSaveDirectory, color = AniLifeColors.Foreground, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                // Section F: Storage estimate
                Text("Estimated size: ~$estimatedText", color = AniLifeColors.MutedForeground, fontSize = 12.sp)
                if (episodes.all { it in downloadedEpisodeNumbers }) {
                    Text("All episodes are already downloaded.", color = AniLifeColors.MutedForeground, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(DownloadConfig(
                        animeId = detail.id,
                        audio = selectedAudio,
                        resolution = selectedResolution,
                        episodeNumbers = selectedEpisodes.sorted(),
                        saveDirectory = defaultSaveDirectory,
                    ))
                },
                enabled = selectedEpisodes.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = AniLifeColors.Primary, contentColor = AniLifeColors.PrimaryForeground),
            ) {
                val n = selectedEpisodes.size
                Text(if (n == 1) "Download 1 Episode" else "Download $n Episodes", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White),
            ) { Text("Cancel", fontWeight = FontWeight.SemiBold) }
        },
    )
}

@Composable
private fun AudioChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (selected) AniLifeColors.Primary else AniLifeColors.Muted)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) { Text(label, color = if (selected) AniLifeColors.PrimaryForeground else AniLifeColors.MutedForeground, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(AniLifeColors.Muted)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) { Text(label, color = AniLifeColors.MutedForeground, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun ResChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (selected) AniLifeColors.Primary else AniLifeColors.Muted)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, color = if (selected) AniLifeColors.PrimaryForeground else AniLifeColors.MutedForeground, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
}