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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.enivzoy.anilife.app.ui.AniLifeColors
import me.enivzoy.anilife.app.ui.AudioType
import me.enivzoy.anilife.app.ui.adaptiveGlass
import me.enivzoy.anilife.app.ui.appEnter
import me.enivzoy.anilife.app.ui.DownloadConfig
import me.enivzoy.anilife.app.ui.PageScrollbar
import me.enivzoy.anilife.app.ui.RemoteImage
import me.enivzoy.anilife.app.ui.Resolution
import me.enivzoy.anilife.core.model.AnimeDetail
import java.io.File

private val DialogShape = RoundedCornerShape(16.dp)

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
    val selectedSizeText = remember(selectedCount, selectedResolution) {
        val megabytes = selectedCount * (330 * selectedResolution.factor).toInt()
        if (megabytes >= 1024) "%.1f GB".format(megabytes / 1024f) else "$megabytes MB"
    }
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
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(850.dp)
                    .heightIn(max = 900.dp)
                    .clip(DialogShape)
                    .background(AniLifeColors.Card)
                    .adaptiveGlass(shape = DialogShape)
                    .appEnter()
                    .border(1.dp, AniLifeColors.Border, DialogShape),
            ) {
                Header(detail = detail, totalEpisodes = episodes.size, onClose = onDismiss)
                HorizontalDivider(color = AniLifeColors.Border)

                val scrollState = rememberScrollState()
                Box(Modifier.weight(1f)) {
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("Audio")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectChip("Sub", selectedAudio == AudioType.Sub) { selectedAudio = AudioType.Sub }
                            SelectChip("Dub", selectedAudio == AudioType.Dub) { selectedAudio = AudioType.Dub }
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
                            color = AniLifeColors.MutedForeground,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Resolution.values().forEach { resolution ->
                                SelectChip(
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

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("Episodes")
                        ToggleRow(
                            title = "Skip filler episodes",
                            subtitle = if (skipFiller) "Filler episodes are skipped when a provider marks them." else "None of these are marked as filler.",
                            checked = skipFiller,
                            onCheckedChange = { skipFiller = it },
                        )

                        if (episodes.isEmpty()) {
                            Text("No episodes available.", color = AniLifeColors.MutedForeground, fontSize = 13.sp)
                        } else {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ranges.forEachIndexed { index, range ->
                                    val count = selectedPerRange.getOrElse(index) { 0 }
                                    val label = "${range.first()}-${range.last()}" + if (count > 0) " ($count)" else ""
                                    RangeChip(label, active = activeRange == index) { activeRange = index }
                                }
                            }

                            val active = ranges.getOrElse(activeRange) { emptyList() }
                            val activeReady = availableIn(active)
                            val allActiveSelected = activeReady.isNotEmpty() &&
                                activeReady.all { it in selectedEpisodes }
                            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                QuickAction(
                                    label = if (allActiveSelected) {
                                        "Clear these ${activeReady.size}"
                                    } else {
                                        "Select these ${activeReady.size}"
                                    },
                                    onClick = {
                                        if (allActiveSelected) {
                                            selectedEpisodes.removeAll(activeReady.toSet())
                                        } else {
                                            activeReady.forEach { if (it !in selectedEpisodes) selectedEpisodes.add(it) }
                                        }
                                    },
                                )
                                QuickAction("Unwatched") { selectFrom(startEpisode) }
                                QuickAction("From episode $startEpisode") { selectFrom(startEpisode) }
                                QuickAction("Clear all") { selectedEpisodes.clear() }
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
                                color = AniLifeColors.MutedForeground,
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
                            estimatedMb = selectedCount * (330 * selectedResolution.factor).toInt().toLong(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = AniLifeColors.MutedForeground,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Saved as files in $defaultSaveDirectory",
                                color = AniLifeColors.MutedForeground,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (episodes.isNotEmpty() && episodes.all { it in downloadedEpisodeNumbers }) {
                            Text("All episodes are already downloaded.", color = AniLifeColors.MutedForeground, fontSize = 12.sp)
                        }
                    }
                }
                PageScrollbar(scrollState, Modifier.align(Alignment.CenterEnd))
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
    Box(Modifier.fillMaxWidth()) {
        val banner = detail.banner
        if (!banner.isNullOrBlank()) {
            RemoteImage(
                url = banner,
                contentDescription = null,
                modifier = Modifier.matchParentSize().alpha(0.3f),
                placeholderColor = AniLifeColors.Card,
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(AniLifeColors.Card.copy(alpha = 0.35f), AniLifeColors.Card),
                ),
            ),
        )
        Row(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RemoteImage(
                url = detail.cover,
                contentDescription = detail.title,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                placeholderColor = AniLifeColors.CardHover,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "DOWNLOAD EPISODES",
                    color = AniLifeColors.MutedForeground,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Text(
                    detail.title,
                    color = AniLifeColors.Foreground,
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("$totalEpisodes episodes", color = AniLifeColors.MutedForeground, fontSize = 14.sp)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = AniLifeColors.MutedForeground, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = AniLifeColors.MutedForeground,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(if (selected) AniLifeColors.Primary else AniLifeColors.Secondary)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (selected) AniLifeColors.PrimaryForeground else AniLifeColors.Foreground,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun RangeChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(if (active) AniLifeColors.Secondary else AniLifeColors.Card)
            .border(1.dp, if (active) AniLifeColors.Primary else AniLifeColors.Border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, color = AniLifeColors.Foreground, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun QuickAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = AniLifeColors.Foreground,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = AniLifeColors.Foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = AniLifeColors.MutedForeground, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AniLifeColors.PrimaryForeground,
                checkedTrackColor = AniLifeColors.Primary,
                uncheckedThumbColor = AniLifeColors.MutedForeground,
                uncheckedTrackColor = AniLifeColors.Secondary,
                uncheckedBorderColor = AniLifeColors.Border,
            ),
        )
    }
}

@Composable
private fun ServerRow(selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("First-choice server", color = AniLifeColors.Foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "Tried first for each episode. If it fails, another may be used for the same audio.",
                color = AniLifeColors.MutedForeground,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        Spacer(Modifier.width(16.dp))
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(AniLifeColors.Secondary)
                    .border(1.dp, AniLifeColors.Border, RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selected, color = AniLifeColors.Foreground, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = AniLifeColors.MutedForeground, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(AniLifeColors.Secondary).appEnter(),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, color = AniLifeColors.Foreground, fontSize = 12.sp) },
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
    val gridState = rememberLazyGridState()
    Box(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 56.dp),
            modifier = Modifier.fillMaxSize(),
            state = gridState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(episodes, key = { it }) { episode ->
                val isDownloaded = episode in downloaded
                val isSelected = episode in selected
                Box(
                    Modifier.aspectRatio(1.6f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when {
                                isDownloaded -> AniLifeColors.CardHover.copy(alpha = 0.5f)
                                isSelected -> AniLifeColors.Primary
                                else -> AniLifeColors.Secondary
                            },
                        )
                        .then(if (isDownloaded) Modifier else Modifier.clickable { onToggle(episode) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        episode.toString(),
                        color = when {
                            isDownloaded -> AniLifeColors.MutedForeground.copy(alpha = 0.5f)
                            isSelected -> AniLifeColors.PrimaryForeground
                            else -> AniLifeColors.Foreground
                        },
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
        PageScrollbar(gridState, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun StorageCard(
    sizeText: String,
    selectedCount: Int,
    freeBytes: Long,
    totalBytes: Long,
    estimatedMb: Long,
) {
    val afterBytes = (freeBytes - estimatedMb * 1024 * 1024).coerceAtLeast(0L)
    val usedFraction = if (totalBytes > 0) ((totalBytes - freeBytes).toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AniLifeColors.Secondary)
            .border(1.dp, AniLifeColors.Border, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "≈ $sizeText · $selectedCount ${if (selectedCount == 1) "episode" else "episodes"}",
                    color = AniLifeColors.Foreground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${formatBytes(freeBytes)} free · ${formatBytes(afterBytes)} after",
                    color = AniLifeColors.MutedForeground,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { usedFraction },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = AniLifeColors.Primary,
                trackColor = AniLifeColors.Border,
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
        HorizontalDivider(color = AniLifeColors.Border)
        Row(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onCancel,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AniLifeColors.Secondary,
                    contentColor = AniLifeColors.Foreground,
                ),
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
                    containerColor = AniLifeColors.Primary,
                    contentColor = AniLifeColors.PrimaryForeground,
                    disabledContainerColor = AniLifeColors.Secondary,
                    disabledContentColor = AniLifeColors.MutedForeground,
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
