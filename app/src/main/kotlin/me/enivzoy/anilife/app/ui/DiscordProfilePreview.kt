package me.enivzoy.anilife.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private object DiscordCardTokens {
    val CardBg = Color(0xFF2A2C31)
    val CardBorder = Color.White.copy(alpha = 0.06f)
    val BannerSageTeal = Color(0xFF7BBAB5)
    val BubbleBg = Color(0xFF383A40)
    val BubbleBorder = Color.White.copy(alpha = 0.04f)
    val ActivityBg = Color(0xFF35373C)
    val ActivityBorder = Color.White.copy(alpha = 0.04f)
    val TextPrimary = Color(0xFFF2F3F5)
    val TextSecondary = Color(0xFFDBDEE1)
    val IconMuted = Color(0xFFB5BAC1)
    val DndRed = Color(0xFFF23F42)
    val ElapsedGreen = Color(0xFF45C476)
    val CopiedGreen = Color(0xFF57F287)
    val TileBg = Color(0xFFF8F9FA)
    val TileInk = Color(0xFF2B2D31)
    val PopupBg = Color(0xFF111214)

    const val DefaultAvatarUrl =
        "https://avatar-cyan.vercel.app/api/pfp/1176527774597521448/smallimage"
}

@Composable
fun DiscordProfilePreview(
    displayName: String = "FleurdelysLover",
    username: String = "witherbl_de",
    avatarUrl: String? = DiscordCardTokens.DefaultAvatarUrl,
    statusThought: String = "it means nothing even if your the most beautiful...",
    subtitle: String = "enivzoy | king orchestra | l's cumbucket",
    bio: String = "my rods pretty long",
    activityDetails: String = "On S01E11",
    activityArtworkUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var copied by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(325) } // 5:25
    var isPaused by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(isPaused) {
        if (isPaused) return@LaunchedEffect
        while (true) {
            delay(1_000L)
            elapsedSeconds += 1
        }
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_800L)
            copied = false
        }
    }

    val resolvedAvatarUrl = avatarUrl?.takeIf(String::isNotBlank) ?: DiscordCardTokens.DefaultAvatarUrl
    var avatarBitmap by remember(resolvedAvatarUrl) {
        mutableStateOf<ImageBitmap?>(RemoteImages.cached(resolvedAvatarUrl))
    }
    LaunchedEffect(resolvedAvatarUrl) {
        avatarBitmap = RemoteImages.load(resolvedAvatarUrl)
    }

    val cardShape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .widthIn(max = 356.dp)
            .fillMaxWidth()
            .shadow(24.dp, cardShape, ambientColor = Color.Black.copy(0.6f), spotColor = Color.Black.copy(0.6f))
            .clip(cardShape)
            .background(DiscordCardTokens.CardBg)
            .border(1.dp, DiscordCardTokens.CardBorder, cardShape),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 1. Header Overlap Zone (104.dp banner + overlapping 88.dp avatar & thought bubble)
            Box(Modifier.fillMaxWidth()) {
                // Solid Sage-Teal Banner
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(104.dp)
                        .background(DiscordCardTokens.BannerSageTeal),
                )

                // Avatar + DND Cutout Badge (starts at y = 58.dp -> 46.dp inside banner, 42.dp below)
                // Thought Bubble (starts at y = 82.dp -> 22.dp inside banner)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 58.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    AvatarWithDndBadge(
                        avatarBitmap = avatarBitmap,
                        displayName = displayName,
                    )

                    Spacer(Modifier.width(8.dp))

                    // Shift bubble top to y = 82.dp (58.dp + 24.dp top padding inside Row)
                    ThoughtStatusBubble(
                        text = statusThought,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 24.dp),
                    )
                }
            }

            // 2. Identity, Bio & Activity Content
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            ) {
                Text(
                    text = displayName,
                    color = DiscordCardTokens.TextPrimary,
                    fontSize = 20.sp,
                    lineHeight = 25.sp,
                    letterSpacing = (-0.2).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(2.dp))

                // Username copy row
                val usernameInteraction = remember { MutableInteractionSource() }
                val usernameHovered by usernameInteraction.collectIsHoveredAsState()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = username,
                        color = DiscordCardTokens.TextSecondary,
                        fontSize = 13.5.sp,
                        lineHeight = 18.sp,
                        textDecoration = if (usernameHovered) TextDecoration.Underline else TextDecoration.None,
                        modifier = Modifier
                            .hoverable(usernameInteraction)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable {
                                clipboard.setText(AnnotatedString(username))
                                copied = true
                            },
                    )
                    Text(
                        text = "•",
                        color = DiscordCardTokens.TextSecondary.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                    )
                    if (copied) {
                        Text(
                            text = "Copied",
                            color = DiscordCardTokens.CopiedGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = DiscordCardTokens.TextSecondary,
                        fontSize = 13.5.sp,
                        lineHeight = 18.5.sp,
                    )
                }

                if (bio.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = bio,
                        color = DiscordCardTokens.TextPrimary,
                        fontSize = 13.5.sp,
                        lineHeight = 18.5.sp,
                    )
                }

                Spacer(Modifier.height(14.dp))

                // 3. Watching AniLife Activity Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DiscordCardTokens.ActivityBg)
                        .border(1.dp, DiscordCardTokens.ActivityBorder, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Watching AniLife",
                            color = DiscordCardTokens.TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )

                        Box {
                            val menuInteraction = remember { MutableInteractionSource() }
                            val menuHovered by menuInteraction.collectIsHoveredAsState()
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (menuHovered) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                                    .hoverable(menuInteraction)
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable { menuExpanded = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "•••",
                                    color = if (menuHovered) DiscordCardTokens.TextPrimary else DiscordCardTokens.IconMuted,
                                    fontSize = 12.sp,
                                    letterSpacing = 1.2.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                                modifier = Modifier.background(DiscordCardTokens.PopupBg).appEnter(),
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isPaused) "Resume timer" else "Pause timer",
                                            color = DiscordCardTokens.TextSecondary,
                                            fontSize = 12.sp,
                                        )
                                    },
                                    onClick = {
                                        isPaused = !isPaused
                                        menuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Reset to 5:25",
                                            color = DiscordCardTokens.TextSecondary,
                                            fontSize = 12.sp,
                                        )
                                    },
                                    onClick = {
                                        elapsedSeconds = 325
                                        isPaused = false
                                        menuExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (activityArtworkUrl.isNullOrBlank()) {
                            UnknownPresenceArtTile()
                        } else {
                            RemoteImage(
                                url = activityArtworkUrl,
                                contentDescription = "AniLife artwork",
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                        }

                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "AniLife",
                                color = DiscordCardTokens.TextPrimary,
                                fontSize = 14.sp,
                                lineHeight = 17.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = activityDetails,
                                color = DiscordCardTokens.TextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 16.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                MonitorElapsedIcon(Modifier.size(14.dp))
                                Text(
                                    text = formatElapsedSeconds(elapsedSeconds),
                                    style = TextStyle(
                                        color = DiscordCardTokens.ElapsedGreen,
                                        fontSize = 12.5.sp,
                                        lineHeight = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFeatureSettings = "tnum",
                                    ),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Discord RPC buttons (opens AniLife / GitHub in the browser)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DiscordCardTokens.ActivityBg)
                            .border(1.dp, DiscordCardTokens.ActivityBorder, RoundedCornerShape(8.dp))
                            .clickable { uriHandler.openUri("https://anilife.cc") }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Visit AniLife",
                            color = DiscordCardTokens.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DiscordCardTokens.ActivityBg)
                            .border(1.dp, DiscordCardTokens.ActivityBorder, RoundedCornerShape(8.dp))
                            .clickable { uriHandler.openUri("https://github.com/anilifecommunity/kotlindesktop") }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Star on GitHub",
                            color = DiscordCardTokens.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AvatarWithDndBadge(
    avatarBitmap: ImageBitmap?,
    displayName: String,
) {
    Box(Modifier.size(88.dp)) {
        // 5.dp #2A2C31 cutout ring around 78.dp circular avatar
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(DiscordCardTokens.CardBg)
                .padding(5.dp),
        ) {
            if (avatarBitmap != null) {
                Image(
                    bitmap = avatarBitmap,
                    contentDescription = "$displayName avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(AniLifeColors.SurfaceVariant),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFFDA4AF), Color(0xFF5EEAD4)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = displayName.take(2).uppercase().ifBlank { "FL" },
                        color = Color(0xFF1E1F22),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                }
            }
        }

        // Do Not Disturb badge bottom-right (inset 1.dp)
        TooltipArea(
            tooltip = {
                Box(
                    modifier = Modifier
                        .appEnter()
                        .shadow(8.dp, RoundedCornerShape(6.dp))
                        .clip(RoundedCornerShape(6.dp))
                        .background(DiscordCardTokens.PopupBg)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "Do Not Disturb",
                        color = DiscordCardTokens.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
            tooltipPlacement = TooltipPlacement.ComponentRect(
                anchor = Alignment.TopCenter,
                alignment = Alignment.TopCenter,
                offset = DpOffset(0.dp, (-6).dp),
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-1).dp, y = (-1).dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(DiscordCardTokens.CardBg),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(DiscordCardTokens.DndRed),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 9.dp, height = 3.dp)
                            .clip(CircleShape)
                            .background(DiscordCardTokens.CardBg),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThoughtStatusBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // Trailing dot 1 (small, overlaps right side of avatar cutout area)
        Box(
            modifier = Modifier
                .offset(x = (-6).dp, y = 5.dp)
                .size(10.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(DiscordCardTokens.BubbleBg),
        )

        // Trailing dot 2 (medium)
        Box(
            modifier = Modifier
                .offset(x = 10.dp, y = 9.dp)
                .size(16.dp)
                .shadow(2.dp, CircleShape)
                .background(DiscordCardTokens.BubbleBg)
                .clip(CircleShape),
        )

        // Main speech bubble
        Box(
            modifier = Modifier
                .padding(start = 6.dp, top = 15.dp)
                .fillMaxWidth()
                .shadow(6.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(DiscordCardTokens.BubbleBg)
                .border(1.dp, DiscordCardTokens.BubbleBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = text,
                color = DiscordCardTokens.TextPrimary,
                fontSize = 13.sp,
                lineHeight = 17.5.sp,
            )
        }
    }
}

@Composable
private fun UnknownPresenceArtTile() {
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DiscordCardTokens.TileBg),
    ) {
        val dotModifier = Modifier.size(5.dp).clip(CircleShape).background(DiscordCardTokens.TileInk)
        Box(dotModifier.align(Alignment.TopStart).offset(x = 7.dp, y = 7.dp))
        Box(dotModifier.align(Alignment.TopEnd).offset(x = (-7).dp, y = 7.dp))
        Box(dotModifier.align(Alignment.BottomStart).offset(x = 7.dp, y = (-7).dp))
        Box(dotModifier.align(Alignment.BottomEnd).offset(x = (-7).dp, y = (-7).dp))

        Text(
            text = "?",
            color = DiscordCardTokens.TileInk,
            fontSize = 28.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun MonitorElapsedIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val sx = size.width / 16f
        val sy = size.height / 16f
        val color = DiscordCardTokens.ElapsedGreen

        drawRoundRect(
            color = color,
            topLeft = Offset(1.5f * sx, 2.5f * sy),
            size = Size(13f * sx, 9f * sy),
            cornerRadius = CornerRadius(1.5f * sx, 1.5f * sy),
        )
        drawLine(
            color = color,
            start = Offset(8f * sx, 11.5f * sy),
            end = Offset(8f * sx, 13.5f * sy),
            strokeWidth = 1.4f * sx,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(6f * sx, 13.5f * sy),
            end = Offset(10f * sx, 13.5f * sy),
            strokeWidth = 1.4f * sx,
            cap = StrokeCap.Round,
        )
    }
}

private fun formatElapsedSeconds(totalSeconds: Int): String {
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "$mins:${secs.toString().padStart(2, '0')}"
}