package io.github.cragcoffee.memoripple.overlay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri

class OverlayPermissionGateway(private val context: Context) {
    fun isGranted(): Boolean = Settings.canDrawOverlays(context)
}

enum class OverlayPermissionDecision { START, SHOW_DISCLOSURE, OPEN_SETTINGS, DENIED, UNAVAILABLE }

fun overlayInitialPermissionDecision(granted: Boolean): OverlayPermissionDecision =
    if (granted) OverlayPermissionDecision.START else OverlayPermissionDecision.SHOW_DISCLOSURE

fun overlaySettingsLaunchDecision(intentAvailable: Boolean): OverlayPermissionDecision =
    if (intentAvailable) OverlayPermissionDecision.OPEN_SETTINGS else OverlayPermissionDecision.UNAVAILABLE

fun overlaySettingsReturnDecision(granted: Boolean): OverlayPermissionDecision =
    if (granted) OverlayPermissionDecision.START else OverlayPermissionDecision.DENIED

internal data class OverlaySettingsTarget(
    val action: String,
    val includePackageUri: Boolean,
)

internal object OverlaySettingsTargetPlan {
    fun targetsFor(sdkInt: Int): List<OverlaySettingsTarget> = buildList {
        if (sdkInt <= Build.VERSION_CODES.Q) {
            add(OverlaySettingsTarget(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true))
        }
        add(OverlaySettingsTarget(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, false))
        add(OverlaySettingsTarget(Settings.ACTION_SETTINGS, false))
    }
}

/** Opens only a resolved Settings destination and never assumes a package page on Android 11+. */
class OverlaySettingsLauncher {
    fun createIntent(context: Context): Intent? {
        return OverlaySettingsTargetPlan.targetsFor(Build.VERSION.SDK_INT)
            .asSequence()
            .map { target -> target.toIntent(context.packageName) }
            .firstOrNull { intent ->
                runCatching { intent.resolveActivity(context.packageManager) != null }
                    .getOrDefault(false)
            }
    }

    private fun OverlaySettingsTarget.toIntent(packageName: String): Intent = Intent(action).apply {
        if (includePackageUri) data = "package:$packageName".toUri()
    }
}
