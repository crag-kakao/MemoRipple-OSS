package io.github.cragcoffee.memoripple.domain.settings

/**
 * テーマカラー: the seed the whole colour scheme grows from. [DEFAULT] is the brand's drop
 * blue — the launcher icon's own colour; everything else is a Material hue. Every scheme is
 * derived at runtime from its seed. A way of looking, stored device-locally — never in the
 * backup, never reset with the product settings.
 */
enum class ThemeSeed(val storageId: String, val argb: Long) {
    DEFAULT("default", 0xFF1E6FD9),
    RED("red", 0xFFF44336),
    PINK("pink", 0xFFE91E63),
    PURPLE("purple", 0xFF9C27B0),
    DEEP_PURPLE("deep_purple", 0xFF673AB7),
    INDIGO("indigo", 0xFF3F51B5),
    BLUE("blue", 0xFF2196F3),
    LIGHT_BLUE("light_blue", 0xFF03A9F4),
    CYAN("cyan", 0xFF00BCD4),
    TEAL("teal", 0xFF009688),
    GREEN("green", 0xFF4CAF50),
    LIGHT_GREEN("light_green", 0xFF8BC34A),
    LIME("lime", 0xFFCDDC39),
    YELLOW("yellow", 0xFFFFEB3B),
    AMBER("amber", 0xFFFFC107),
    ORANGE("orange", 0xFFFF9800),
    ;

    companion object {
        fun fromStorageId(value: String?): ThemeSeed =
            entries.firstOrNull { it.storageId == value } ?: DEFAULT
    }
}

/**
 * パレットスタイル: how the seed is spread across the scheme — Material Color Utilities'
 * variants, under their own names. Only meaningful when a seed colour is chosen.
 */
enum class ThemePaletteStyle(val storageId: String) {
    TONAL_SPOT("tonal_spot"),
    NEUTRAL("neutral"),
    VIBRANT("vibrant"),
    EXPRESSIVE("expressive"),
    RAINBOW("rainbow"),
    FRUIT_SALAD("fruit_salad"),
    MONOCHROME("monochrome"),
    FIDELITY("fidelity"),
    CONTENT("content"),
    ;

    companion object {
        fun fromStorageId(value: String?): ThemePaletteStyle =
            entries.firstOrNull { it.storageId == value } ?: TONAL_SPOT
    }
}
