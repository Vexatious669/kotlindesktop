package me.enivzoy.anilife.app.ui

import me.enivzoy.anilife.app.data.AppSettings
import me.enivzoy.anilife.app.ui.theme.AppearanceConfig
import me.enivzoy.anilife.app.ui.theme.GlassStyle
import me.enivzoy.anilife.app.ui.theme.ScrollAnimationDuration
import me.enivzoy.anilife.app.ui.theme.ScrollbarVisibility
import me.enivzoy.anilife.app.ui.theme.ThemeMode
import me.enivzoy.anilife.app.ui.theme.glassStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class AppearanceConfigTest {
    @Test
    fun themeModesMapToGlassStylesAndFallBackToDefault() {
        assertEquals(ThemeMode.DEFAULT, ThemeMode.fromStorage(null))
        assertEquals(ThemeMode.DEFAULT, ThemeMode.fromStorage("unknown"))
        assertEquals(ThemeMode.FROSTED_GLASS, ThemeMode.fromStorage("frosted_glass"))
        assertEquals(GlassStyle.LEGACY, ThemeMode.DEFAULT.glassStyle)
        assertEquals(GlassStyle.FROSTED, ThemeMode.FROSTED_GLASS.glassStyle)
        assertEquals(GlassStyle.LIQUID, ThemeMode.LIQUID_GLASS.glassStyle)
    }

    @Test
    fun configReadsAllAppearanceSettingsFromTheExistingStore() {
        val config = AppearanceConfig.from(
            AppSettings(
                themeMode = "LIQUID_GLASS",
                smoothScrolling = false,
                scrollbarVisibility = "HIDDEN",
                scrollAnimationDuration = "SHORT",
                highRefreshRateUi = true,
                reducedMotion = true,
                glassBlurIntensity = 1.5f,
                glassTransparency = 0.7f,
                glassHighlightIntensity = 0.4f,
            ),
        )

        assertEquals(ThemeMode.LIQUID_GLASS, config.themeMode)
        assertEquals(false, config.smoothScrolling)
        assertEquals(ScrollbarVisibility.HIDDEN, config.scrollbarVisibility)
        assertEquals(ScrollAnimationDuration.SHORT, config.scrollDuration)
        assertEquals(true, config.highRefreshRateUi)
        assertEquals(true, config.reducedMotion)
        assertEquals(1.5f, config.glassBlurIntensity)
        assertEquals(0.7f, config.glassTransparency)
        assertEquals(0.4f, config.glassHighlightIntensity)
    }
}
