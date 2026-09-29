package io.github.cragcoffee.memoripple

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import io.github.cragcoffee.memoripple.domain.settings.ThemeMode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.settings.CommentFont
import io.github.cragcoffee.memoripple.domain.settings.ThemePaletteStyle
import io.github.cragcoffee.memoripple.domain.settings.ThemeSeed
import io.github.cragcoffee.memoripple.ui.MemoRippleApp
import io.github.cragcoffee.memoripple.ui.playback.LocalCommentBackdropEnabled
import io.github.cragcoffee.memoripple.ui.playback.LocalCommentFontFamily
import io.github.cragcoffee.memoripple.ui.playback.LocalCommentTransparency
import io.github.cragcoffee.memoripple.ui.playback.commentFontFamily
import io.github.cragcoffee.memoripple.ui.theme.MemoRippleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MemoRippleApplication
        setContent {
            val settings = app.settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings.Default,
            ).value
            val commentFontId = app.settingsRepository.commentFontId.collectAsStateWithLifecycle(
                initialValue = CommentFont.DEFAULT.storageId,
            ).value
            val userCommentFonts = app.settingsRepository.userCommentFonts
                .collectAsStateWithLifecycle(initialValue = emptyList()).value
            val commentFontFamily = io.github.cragcoffee.memoripple.ui.playback
                .rememberCommentFontFamily(commentFontId, userCommentFonts, app.commentFontStore)
            val commentBackdrop = app.settingsRepository.commentBackdrop
                .collectAsStateWithLifecycle(initialValue = false).value
            val commentTransparency = app.settingsRepository.commentTransparency
                .collectAsStateWithLifecycle(initialValue = 0f).value
            // The system bars follow the app's own 外観, not the OS's. enableEdgeToEdge at
            // onCreate reads only the OS night mode, so an in-app Light choice on a dark OS
            // (or the reverse) left the status and navigation bar icons on the wrong side of
            // the contrast. Re-declaring the bar styles whenever the effective brightness
            // changes flips icon appearance immediately — no activity recreation, and the
            // bars stay transparent for edge-to-edge.
            //
            // The re-declaration is keyed on the brightness and *posted* to the main thread, never
            // run inside the composition: enableEdgeToEdge during the first traversal makes the
            // framework dispatch the attach pass a second time, and Compose statics that assume
            // one attach per detach then keep every finished activity alive — which is how the
            // instrumentation process leaked one MainActivity per test (docs/TEST_MEMORY_AUDIT.md).
            // A posted message runs after the frame, under the app's clock and the test host's alike.
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(darkTheme) {
                val style = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                window.decorView.post {
                    if (!isFinishing && !isDestroyed) {
                        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    }
                }
            }
            val themeSeed = app.settingsRepository.themeSeed
                .collectAsStateWithLifecycle(initialValue = ThemeSeed.DEFAULT).value
            val paletteStyle = app.settingsRepository.themePaletteStyle
                .collectAsStateWithLifecycle(initialValue = ThemePaletteStyle.TONAL_SPOT).value
            MemoRippleTheme(
                themeMode = settings.themeMode,
                themeSeed = themeSeed,
                paletteStyle = paletteStyle,
            ) {
                CompositionLocalProvider(
                    LocalCommentFontFamily provides commentFontFamily,
                    LocalCommentBackdropEnabled provides commentBackdrop,
                    LocalCommentTransparency provides commentTransparency,
                ) {
                    MemoRippleApp(appSettings = settings)
                }
            }
        }
    }
}
