package me.enivzoy.anilife.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Development-only harness for the glass material.
 *
 * It is not part of the app's navigation. Launch with `-Danilife.glassShowcase=true`
 * (or `ANILIFE_GLASS_SHOWCASE=1`) to inspect the renderer over saturated artwork,
 * a photographic-style gradient and a flat dark field at the same time.
 */
@Composable
fun GlassShowcase() {
    var tab by remember { mutableIntStateOf(0) }
    var segment by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().showcaseWallpaper()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column {
                Text("Liquid Glass", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Every control below refracts the pixels behind it.",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 14.sp,
                )
            }

            // Real content sampling: the glass controls refract this artwork band, not the wallpaper.
            GlassBackdrop(
                modifier = Modifier.fillMaxWidth().height(240.dp).clipShape(),
                source = { ShowcaseArtwork(Modifier.fillMaxSize()) },
            ) {
                Row(
                    Modifier.align(Alignment.BottomStart).padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassButton(onClick = {}, accent = true) {
                        androidx.compose.material3.Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                        Text("Play", fontWeight = FontWeight.SemiBold)
                    }
                    GlassButton(onClick = {}) {
                        Text("Details")
                    }
                    GlassIconButton(
                        onClick = {},
                        icon = Icons.Rounded.Favorite,
                        contentDescription = "Favourite",
                        alwaysGlass = true,
                    )
                    GlassIconButton(
                        onClick = {},
                        icon = Icons.Rounded.Share,
                        contentDescription = "Share",
                        alwaysGlass = true,
                    )
                }
                Text(
                    "Sampling this artwork",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(20.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton(onClick = {}, accent = true) { Text("Primary", fontWeight = FontWeight.SemiBold) }
                GlassButton(onClick = {}) { Text("Secondary") }
                GlassButton(onClick = {}, danger = true) { Text("Delete") }
                GlassButton(onClick = {}, enabled = false) { Text("Disabled") }
                GlassIconButton(onClick = {}, icon = Icons.Rounded.Search, contentDescription = "Search", alwaysGlass = true)
                GlassIconButton(onClick = {}, icon = Icons.Rounded.Download, contentDescription = "Download", alwaysGlass = true)
                GlassIconButton(onClick = {}, icon = Icons.Rounded.Star, contentDescription = "Star", selected = true, alwaysGlass = true)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassSegmentedControl(
                    options = listOf("Sub", "Dub", "Both"),
                    selectedIndex = segment,
                    onSelect = { segment = it },
                    modifier = Modifier.width(260.dp),
                )
                GlassToolbar {
                    GlassIconButton(onClick = {}, icon = Icons.Rounded.Search, contentDescription = "Search", size = 32.dp, iconSize = 16.dp)
                    GlassIconButton(onClick = {}, icon = Icons.Rounded.Download, contentDescription = "Download", size = 32.dp, iconSize = 16.dp)
                    GlassIconButton(onClick = {}, icon = Icons.Rounded.Share, contentDescription = "Share", size = 32.dp, iconSize = 16.dp)
                }
            }

            GlassTabBar(
                labels = listOf("Overview", "Episodes", "Related", "Comments"),
                selectedIndex = tab,
                onSelect = { tab = it },
                modifier = Modifier.width(520.dp),
            )

            GlassSurface(Modifier.width(420.dp)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("GlassSurface", color = AniLifeColors.Foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "A floating panel for menus and contextual tools. Content pages stay on their own plane.",
                        color = AniLifeColors.MutedForeground,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ShowcaseArtwork(modifier: Modifier = Modifier) {
    Box(
        modifier.drawBehind {
            drawRect(
                Brush.linearGradient(
                    listOf(Color(0xFF4A1B6D), Color(0xFF0E4F5C), Color(0xFFB45309)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
            drawCircle(Color(0xFFFF4D8D).copy(alpha = 0.75f), size.minDimension * 0.42f, Offset(size.width * 0.18f, size.height * 0.30f))
            drawCircle(Color(0xFF2DD4BF).copy(alpha = 0.6f), size.minDimension * 0.5f, Offset(size.width * 0.72f, size.height * 0.22f))
            drawCircle(Color(0xFFF59E0B).copy(alpha = 0.55f), size.minDimension * 0.38f, Offset(size.width * 0.55f, size.height * 0.92f))
        },
    )
}

@Composable
private fun Modifier.showcaseWallpaper(): Modifier = drawBehind {
    drawRect(Color(0xFF070A09))
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFF2F6B4F).copy(alpha = 0.55f), Color.Transparent),
            center = Offset(size.width * 0.12f, size.height * 0.10f),
            radius = size.minDimension * 0.8f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFF7C3AED).copy(alpha = 0.45f), Color.Transparent),
            center = Offset(size.width * 0.85f, size.height * 0.85f),
            radius = size.minDimension * 0.75f,
        ),
    )
    // High-frequency detail so refraction and blur are obvious.
    var y = 0f
    while (y < size.height) {
        drawLine(Color.White.copy(alpha = 0.035f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += 6f
    }
}

private fun Modifier.clipShape(): Modifier = clip(RoundedCornerShape(18.dp))
