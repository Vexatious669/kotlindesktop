package me.enivzoy.anilife.app.ui.theme

enum class ThemeMode(val storageKey: String, val label: String) {
    DEFAULT("DEFAULT", "Default"),
    FROSTED_GLASS("FROSTED_GLASS", "Frosted Glass"),
    LIQUID_GLASS("LIQUID_GLASS", "Liquid Glass"),
    ;

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}

enum class GlassStyle {
    LEGACY,
    FROSTED,
    LIQUID,
    ;
}

val ThemeMode.glassStyle: GlassStyle
    get() = when (this) {
        ThemeMode.DEFAULT -> GlassStyle.LEGACY
        ThemeMode.FROSTED_GLASS -> GlassStyle.FROSTED
        ThemeMode.LIQUID_GLASS -> GlassStyle.LIQUID
    }

enum class ScrollbarVisibility(val storageKey: String, val label: String) {
    AUTOMATIC("AUTOMATIC", "Automatic"),
    ALWAYS("ALWAYS", "Always"),
    HIDDEN("HIDDEN", "Hidden"),
    ;

    companion object {
        fun fromStorage(value: String?): ScrollbarVisibility =
            entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) } ?: AUTOMATIC
    }
}

enum class ScrollAnimationDuration(val storageKey: String, val label: String, val millis: Int) {
    SHORT("SHORT", "Short", 180),
    NORMAL("NORMAL", "Normal", 320),
    LONG("LONG", "Long", 520),
    ;

    companion object {
        fun fromStorage(value: String?): ScrollAnimationDuration =
            entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) } ?: NORMAL
    }
}
