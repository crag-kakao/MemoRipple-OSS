package io.github.cragcoffee.memoripple.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import io.github.cragcoffee.memoripple.domain.settings.ThemeMode
import io.github.cragcoffee.memoripple.domain.settings.ThemePaletteStyle
import io.github.cragcoffee.memoripple.domain.settings.ThemeSeed

private val ProductShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// Kana and kanji sit in full-width boxes, so a line height set for Latin reads as crammed.
// Everything that carries running Japanese is given roughly 1.6x its size.
private val ProductTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 23.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
)

@Composable
fun MemoRippleTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    themeSeed: ThemeSeed = ThemeSeed.DEFAULT,
    paletteStyle: ThemePaletteStyle = ThemePaletteStyle.TONAL_SPOT,
    content: @Composable () -> Unit,
) {
    val useDarkColors = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // 外観 says light or dark; テーマカラー says which colours; パレットスタイル says how the
    // seed spreads. Every scheme grows from its seed at runtime; the default seed is the
    // brand's drop blue, so the app wakes up wearing its own icon's colour.
    val colorScheme = rememberDynamicColorScheme(
        seedColor = Color(themeSeed.argb),
        isDark = useDarkColors,
        style = paletteStyle.toMaterialKolor(),
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ProductTypography,
        shapes = ProductShapes,
        content = content,
    )
}

private fun ThemePaletteStyle.toMaterialKolor(): PaletteStyle = when (this) {
    ThemePaletteStyle.TONAL_SPOT -> PaletteStyle.TonalSpot
    ThemePaletteStyle.NEUTRAL -> PaletteStyle.Neutral
    ThemePaletteStyle.VIBRANT -> PaletteStyle.Vibrant
    ThemePaletteStyle.EXPRESSIVE -> PaletteStyle.Expressive
    ThemePaletteStyle.RAINBOW -> PaletteStyle.Rainbow
    ThemePaletteStyle.FRUIT_SALAD -> PaletteStyle.FruitSalad
    ThemePaletteStyle.MONOCHROME -> PaletteStyle.Monochrome
    ThemePaletteStyle.FIDELITY -> PaletteStyle.Fidelity
    ThemePaletteStyle.CONTENT -> PaletteStyle.Content
}
