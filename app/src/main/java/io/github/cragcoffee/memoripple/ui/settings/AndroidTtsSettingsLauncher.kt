package io.github.cragcoffee.memoripple.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings

class AndroidTtsSettingsLauncher {
    fun open(context: Context): Boolean = openFirstAvailable(
        canResolve = { action ->
            Intent(action).resolveActivity(context.packageManager) != null
        },
        launch = { action ->
            context.startActivity(Intent(action))
        },
    )

    internal fun openFirstAvailable(
        canResolve: (String) -> Boolean,
        launch: (String) -> Unit,
    ): Boolean {
        actions.forEach { action ->
            if (!runCatching { canResolve(action) }.getOrDefault(false)) return@forEach
            if (runCatching { launch(action) }.isSuccess) return true
        }
        return false
    }

    internal companion object {
        const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"
        val actions = listOf(
            TTS_SETTINGS_ACTION,
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
    }
}
