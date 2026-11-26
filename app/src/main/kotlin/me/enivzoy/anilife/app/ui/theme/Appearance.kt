package me.enivzoy.anilife.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import me.enivzoy.anilife.app.data.AppSettings

data class AppearanceConfig(
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    val smoothScrolling: Boolean = true,
    val scrollbarVisibility: ScrollbarVisibility = ScrollbarVisibility.AUTOMATIC,
    val scrollDuration: ScrollAnimationDuration = ScrollAnimationDuration.NORMAL,
    val highRefreshRateUi: Boolean = false,
    val reducedMotion: Boolean = false,
    val glassBlurIntensity: Float = 1f,
    val glassTransparency: Float = 0.5f,
    val glassHighlightIntensity: Float = 1f,
) {
    companion object {
        fun from(settings: AppSettings): AppearanceConfig = AppearanceConfig(
            themeMode = ThemeMode.fromStorage(settings.themeMode),
            smoothScrolling = settings.smoothScrolling,
            scrollbarVisibility = ScrollbarVisibility.fromStorage(settings.scrollbarVisibility),
            scrollDuration = ScrollAnimationDuration.fromStorage(settings.scrollAnimationDuration),
            highRefreshRateUi = settings.highRefreshRateUi,
            reducedMotion = settings.reducedMotion,
            glassBlurIntensity = settings.glassBlurIntensity,
            glassTransparency = settings.glassTransparency,
            glassHighlightIntensity = settings.glassHighlightIntensity,
        )
    }
}

val LocalAppearance = compositionLocalOf { AppearanceConfig() }

@Composable
fun ProvideAppearance(settings: AppSettings, content: @Composable () -> Unit) {
    val config = remember(
        settings.themeMode,
        settings.smoothScrolling,
        settings.scrollbarVisibility,
        settings.scrollAnimationDuration,
        settings.highRefreshRateUi,
        settings.reducedMotion,
        settings.glassBlurIntensity,
        settings.glassTransparency,
        settings.glassHighlightIntensity,
    ) { AppearanceConfig.from(settings) }
    CompositionLocalProvider(LocalAppearance provides config, content = content)
}
