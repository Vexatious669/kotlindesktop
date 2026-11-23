package me.enivzoy.anilife.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.enivzoy.anilife.app.ui.AudioType
import me.enivzoy.anilife.app.ui.DownloadConfig
import me.enivzoy.anilife.app.ui.RemoteImage
import me.enivzoy.anilife.app.ui.Resolution
import me.enivzoy.anilife.core.model.AnimeDetail
import java.io.File

private val ModalBg = Color(0xFF141416)
private val ModalSurface = Color(0xFF1E1E21)
private val ModalSurfaceAlt = Color(0xFF2A2A2E)
private val ModalBorder = Color(0xFF2E2E33)
private val ModalText = Color(0xFFF5F5F7)
private val ModalMuted = Color(0xFF9A9AA2)
private val ModalInk = Color(0xFF0B0B0C)
private val ModalShape = RoundedCornerShape(24.dp)

@Composable
fun DownloadConfigDialog(
    detail: AnimeDetail,
    downloadedEpisodeNumbers: Set<Int>,
    defaultStartEpisode: Int,
    defaultSaveDirectory: String,
    onDismiss: () -> Unit,
    onConfirm: (DownloadConfig) -> Unit,
    serverOptions: List<String> = listOf("Auto"),
) {
    val totalEpisodes = detail.episodes ?: 0
    val episodes = remember(detail.id, totalEpisodes) { (1..totalEpisodes).toList() }
    val ranges = remember(episodes) { episodes.chunked(100) }
    val startEpisode = defaultStartEpisode.coerceIn(1, totalEpisodes.coerceAtLeast(1))

    var selectedAudio by remember { mutableStateOf(AudioType.Sub) }
    var selectedResolution by remember { mutableStateOf(Resolution.Auto) }
    var firstChoiceServer by remember { mutableStateOf(serverOptions.firstOrNull() ?: "Auto") }
    var switchServers by remember { mutableStateOf(false) }
    var skipFiller by remember { mutableStateOf(false) }
    var activeRange by remember { mutableIntStateOf(0) }
    val selectedEpisodes = remember { mutableStateListOf<Int>() }

    remember(detail.id) {
        selectedEpisodes.addAll(episodes.filter { it >= startEpisode && it !in downloadedEpisodeNumbers })
        true
    }

    val selectedCount by remember { derivedStateOf { selectedEpisodes.size } }
    val selectedPerRange by remember {
        derivedStateOf { ranges.map { range -> range.count { it in selectedEpisodes } } }
    }
    val perEpisodeMb = (330 * selectedResolution.factor).toInt()
    val estimatedMb = selectedCount * perEpisodeMb
    val selectedSizeText = if (estimatedMb >= 1024) "%.1f GB".format(estimatedMb / 1024f) else "$estimatedMb MB"
    val freeBytes = remember(defaultSaveDirectory) { usableBytes(defaultSaveDirectory) }
    val totalBytes = remember(defaultSaveDirectory) { totalBytes(defaultSaveDirectory) }

    fun availableIn(range: List<Int>) = range.filter { it !in downloadedEpisodeNumbers }

    fun selectRange(range: List<Int>) {
        selectedEpisodes.clear()
        selectedEpisodes.addAll(availableIn(range))
    }

    fun selectFrom(start: Int) {
        selectedEpisodes.clear()
        selectedEpisodes.addAll(episodes.filter { it >= start && it !in downloadedEpisodeNumbers })
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth(0.9f)
                    .fillMaxHeight(0.9f)
                    .clip(ModalShape)
                    .background(ModalBg)
                    .border(1.dp, ModalBorder, ModalShape),
            ) {
                Header(detail = detail, totalEpisodes = episodes.size, onClose = onDismiss)

                Column(
                    Modifier.weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("Audio")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillChip("Sub", selectedAudio == AudioType.Sub) { selectedAudio = AudioType.Sub }
                            PillChip("Dub", selectedAudio == AudioType.Dub) { selectedAudio = AudioType.Dub }
                        }
                    }

                    ServerRow(
                        selected = firstChoiceServer,
                        options = serverOptions,
                        onSelect = { firstChoiceServer = it },
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("Quality")
                        Text(
                            "The best rendition at or below the resolution chosen.",
                            color = ModalMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Resolution.values().forEach { resolution ->
                                PillChip(
                                    label = if (resolution == Resolution.Auto) "Best" else resolution.label,
                                    selected = selectedResolution == resolution,
                                    onClick = { selectedResolution = resolution },
                                )
                            }
                        }
                    }

                    ToggleRow(
                        title = "Switch servers per episode",
                        subtitle = "When an episode's server has nothing at 360p or below, try others for that episode. Slower, and the subtitle style may differ.",
                        checked = switchServers,
                        onCheckedChange = { switchServers = it },
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionLabel("Episodes")
                        ToggleRow(
                            title = "Skip filler episodes",
                            subtitle = if (skipFiller) "Filler episodes are skipped when a provider marks them." else "None of these are marked as filler.",
                            checked = skipFiller,
                            onCheckedChange = { skipFiller = it },
                        )

                        if (episodes.isEmpty()) {
                            Text("No episodes available.", color = ModalMuted, fontSize = 13.sp)
                        } else {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ranges.forEachIndexed { index, range ->
                                    val count = selectedPerRange.getOrElse(index) { 0 }
                                    val label = "${range.first()}-${range.last()}" + if (count > 0) " ($count)" else ""
                                    OutlineChip(label, active = activeRange == index) { activeRange = index }
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val active = ranges.getOrElse(activeRange) { emptyList() }
                                OutlineChip("Select these ${active.size}") { selectRange(active) }
                                OutlineChip("Unwatched") { selectFrom(startEpisode) }
                                OutlineChip("From episode $startEpisode") { selectFrom(startEpisode) }
                                OutlineChip("Clear all") { selectedEpisodes.clear() }
                            }

                            EpisodeGrid(
                                episodes = episodes,
                                downloaded = downloadedEpisodeNumbers,
                                selected = selectedEpisodes,
                                onToggle = { episode ->
                                    if (episode in selectedEpisodes) selectedEpisodes.remove(episode)
                                    else selectedEpisodes.add(episode)
                                },
                            )

                            Text(
                                "$selectedCount of ${episodes.size} selected · fetched one at a time; you can keep using the app while they run.",
                                color = ModalMuted,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("Storage")
                        StorageCard(
                            sizeText = selectedSizeText,
                            selectedCount = selectedCount,
                            freeBytes = freeBytes,
                            totalBytes = totalBytes,
                            estimatedMb = estimatedMb,
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = ModalMuted,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Saved as files in $defaultSaveDirectory",
                                color = ModalMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (episodes.isNotEmpty() && episodes.all { it in downloadedEpisodeNumbers }) {
                            Text("All episodes are already downloaded.", color = ModalMuted, fontSize = 12.sp)
                        }
                    }
                }

                Footer(
                    selectedCount = selectedCount,
                    enabled = selectedCount > 0,
                    onCancel = onDismiss,
                    onConfirm = {
                        onConfirm(
                            DownloadConfig(
                                animeId = detail.id,
                                audio = selectedAudio,
                                resolution = selectedResolution,
                                episodeNumbers = selectedEpisodes.sorted(),
                                saveDirectory = defaultSaveDirectory,
                                firstChoiceServer = firstChoiceServer,
                                switchServers = switchServers,
                                skipFiller = skipFiller,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun Header(detail: AnimeDetail, totalEpisodes: Int, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(172.dp)) {
        val banner = detail.banner
        if (!banner.isNullOrBlank()) {
            RemoteImage(
                url = banner,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(0.35f),
                placeholderColor = ModalSurface,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color(0xFF26262B).copy(alpha = 0.85f), ModalBg),
                ),
            ),
        )
        Row(
            Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteImage(
                url = detail.cover,
                contentDescription = detail.title,
                modifier = Modifier.width(80.dp).height(112.dp).clip(RoundedCornerShape(10.dp)),
                placeholderColor = ModalSurfaceAlt,
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "DOWNLOAD EPISODES",
                    color = ModalMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                )
                Text(
                    detail.title,
                    color = ModalText,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$totalEpisodes episodes",
                    color = ModalMuted,
                    fontSize = 14.sp,
                )
            }
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(32.dp),
        ) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(ModalSurfaceAlt.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = ModalText, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = ModalMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
    )
}

@Composable
private fun PillChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(if (selected) Color.White else ModalSurfaceAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (selected) ModalInk else ModalText,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun OutlineChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(if (active) ModalSurfaceAlt else Color.Transparent)
            .border(1.dp, if (active) Color.White.copy(alpha = 0.35f) else ModalBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (active) ModalText else ModalMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = ModalText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = ModalMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF4A4A50),
                uncheckedThumbColor = Color(0xFFB0B0B8),
                uncheckedTrackColor = ModalSurfaceAlt,
            ),
        )
    }
}

@Composable
private fun ServerRow(selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("First-choice server", color = ModalText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Tried first for each episode. If it fails, another may be used for the same audio.",
                color = ModalMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        Spacer(Modifier.width(16.dp))
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(ModalSurfaceAlt)
                    .border(1.dp, ModalBorder, RoundedCornerShape(10.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selected, color = ModalText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = ModalMuted, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(ModalSurface),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, color = ModalText, fontSize = 12.sp) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeGrid(
    episodes: List<Int>,
    downloaded: Set<Int>,
    selected: List<Int>,
    onToggle: (Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(12),
        modifier = Modifier.fillMaxWidth().height(260.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(episodes, key = { it }) { episode ->
            val isDownloaded = episode in downloaded
            val isSelected = episode in selected
            val bg = when {
                isDownloaded -> Color(0xFF1A1A1D)
                isSelected -> Color.White
                else -> ModalSurfaceAlt
            }
            Box(
                Modifier.aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .then(if (isDownloaded) Modifier else Modifier.clickable { onToggle(episode) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    episode.toString(),
                    color = when {
                        isDownloaded -> ModalMuted.copy(alpha = 0.5f)
                        isSelected -> ModalInk
                        else -> ModalText
                    },
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun StorageCard(
    sizeText: String,
    selectedCount: Int,
    freeBytes: Long,
    totalBytes: Long,
    estimatedMb: Int,
) {
    val afterBytes = (freeBytes - estimatedMb.toLong() * 1024 * 1024).coerceAtLeast(0L)
    val usedFraction = if (totalBytes > 0) ((totalBytes - freeBytes).toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ModalSurface)
            .border(1.dp, ModalBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "≈ $sizeText · $selectedCount ${if (selectedCount == 1) "episode" else "episodes"}",
                color = ModalText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${formatBytes(freeBytes)} free · ${formatBytes(afterBytes)} after",
                color = ModalMuted,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(ModalSurfaceAlt),
        ) {
            Box(
                Modifier.fillMaxWidth(usedFraction).fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.7f)),
            )
        }
    }
}

@Composable
private fun Footer(
    selectedCount: Int,
    enabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(ModalBorder))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onCancel,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(40.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ModalSurfaceAlt, contentColor = ModalText),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            ) {
                Text("Cancel", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Button(
                onClick = onConfirm,
                enabled = enabled,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = ModalInk,
                    disabledContainerColor = ModalSurfaceAlt,
                    disabledContentColor = ModalMuted,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            ) {
                Text(
                    "Download $selectedCount ${if (selectedCount == 1) "episode" else "episodes"}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun existingAncestor(path: String): File? {
    var file = File(path)
    while (!file.exists()) file = file.parentFile ?: return null
    return file
}

private fun usableBytes(path: String): Long =
    existingAncestor(path)?.usableSpace ?: 0L

private fun totalBytes(path: String): Long =
    existingAncestor(path)?.totalSpace ?: 0L

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "unknown"
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024f * 1024 * 1024))
    else -> "${bytes / (1024 * 1024)} MB"
}
