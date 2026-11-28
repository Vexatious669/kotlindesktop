package me.enivzoy.anilife.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.enivzoy.anilife.app.Screen
import me.enivzoy.anilife.app.ui.theme.GlassStyle
import me.enivzoy.anilife.app.ui.theme.LocalAppearance
import me.enivzoy.anilife.app.ui.theme.glassStyle

 



@Composable
fun AppSidebar(
    current: Screen,
    onNavigate: (Screen) -> Unit,
    onRandom: () -> Unit,
    onSearch: () -> Unit = { onNavigate(Screen.Browse) },
    downloadProgress: Float? = null,
    sillyMode: Boolean = false,
    onSillyMode: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .width(64.dp)
            .fillMaxHeight()
            .then(
                // Default keeps the solid sidebar of the original layout; glass themes sample
                // the backdrop through the glass renderer instead.
                if (LocalAppearance.current.themeMode.glassStyle == GlassStyle.LEGACY) {
                    Modifier.background(AniLifeColors.Sidebar)
                } else {
                    Modifier.liquidGlass(shape = RoundedCornerShape(0.dp))
                },
            )
            .drawBehind {
                val x = size.width - 0.5f
                drawLine(
                    color = AniLifeColors.Border,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        val compact = maxHeight < 560.dp
        val iconSize = 40.dp
        val gap = 2.dp
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier.fillMaxSize()
                .then(if (compact) Modifier.verticalScroll(scrollState) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
            BrandMark(size = 36.dp)
            Spacer(Modifier.height(18.dp))

            NavIcon(Icons.Rounded.Search, "Search (Ctrl+K)", selected = false, size = iconSize) { onSearch() }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.Home, "Home", selected = current is Screen.Home, size = iconSize) { onNavigate(Screen.Home) }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.Explore, "Browse", selected = current is Screen.Browse, size = iconSize) { onNavigate(Screen.Browse) }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.CalendarMonth, "Schedule", selected = current is Screen.Schedule, size = iconSize) { onNavigate(Screen.Schedule) }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.VideoLibrary, "Library", selected = current is Screen.Library, size = iconSize) { onNavigate(Screen.Library) }
            Spacer(Modifier.height(gap))
            NavIcon(
                Icons.Rounded.Download,
                if (downloadProgress == null) "Downloads" else "Downloads in progress",
                selected = current is Screen.Downloads,
                size = iconSize,
                progress = downloadProgress,
            ) { onNavigate(Screen.Downloads) }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.Extension, "Providers", selected = current is Screen.Providers, size = iconSize) { onNavigate(Screen.Providers) }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.Casino, "Random pick", selected = false, size = iconSize) { onRandom() }

            if (compact) Spacer(Modifier.height(6.dp)) else Spacer(Modifier.weight(1f))

            NavIcon(Icons.Rounded.Notifications, "Notifications", selected = current is Screen.Notifications, size = iconSize) {
                onNavigate(Screen.Notifications)
            }
            Spacer(Modifier.height(gap))
            NavIcon(
                Icons.Rounded.VisibilityOff,
                if (sillyMode) "Silly mode on" else "Silly mode",
                selected = sillyMode,
                size = iconSize,
            ) { onSillyMode() }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.Person, "Profile", selected = current is Screen.Profile, size = iconSize) { onNavigate(Screen.Profile) }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.Settings, "Settings", selected = current is Screen.Settings, size = iconSize) { onNavigate(Screen.Settings) }
            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        }
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    GlassIconButton(
        onClick = onClick,
        icon = icon,
        contentDescription = label,
        progress = progress,
        progressColor = AniLifeColors.DownloadProgress,
        size = size,
        iconSize = 20.dp,
        selected = selected,
        accent = AniLifeColors.SidebarAccent,
        shape = RoundedCornerShape(10.dp),
    )
}

