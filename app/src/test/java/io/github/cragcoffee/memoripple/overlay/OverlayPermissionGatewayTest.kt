package io.github.cragcoffee.memoripple.overlay

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPermissionGatewayTest {
    @Test
    fun grantedStartsAndDeniedRequiresDisclosure() {
        assertEquals(
            OverlayPermissionDecision.START,
            overlayInitialPermissionDecision(granted = true),
        )
        assertEquals(
            OverlayPermissionDecision.SHOW_DISCLOSURE,
            overlayInitialPermissionDecision(granted = false),
        )
    }

    @Test
    fun settingsReturnContinuesOnlyWhenGranted() {
        assertEquals(
            OverlayPermissionDecision.START,
            overlaySettingsReturnDecision(granted = true),
        )
        assertEquals(
            OverlayPermissionDecision.DENIED,
            overlaySettingsReturnDecision(granted = false),
        )
    }

    @Test
    fun unavailableIntentIsRejectedWithoutCrash() {
        assertEquals(
            OverlayPermissionDecision.UNAVAILABLE,
            overlaySettingsLaunchDecision(intentAvailable = false),
        )
    }

    @Test
    fun android10PrefersPackageSpecificTargetThenFallsBack() {
        val targets = OverlaySettingsTargetPlan.targetsFor(29)

        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, targets.first().action)
        assertTrue(targets.first().includePackageUri)
        assertEquals(Settings.ACTION_SETTINGS, targets.last().action)
    }

    @Test
    fun android11DoesNotAssumePackageSpecificSettingsPage() {
        val targets = OverlaySettingsTargetPlan.targetsFor(30)

        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, targets.first().action)
        assertFalse(targets.first().includePackageUri)
        assertTrue(targets.none { it.includePackageUri })
    }

    @Test
    fun unavailableManageActionHasSafeGeneralSettingsFallback() {
        val targets = OverlaySettingsTargetPlan.targetsFor(36)
        val available = setOf(Settings.ACTION_SETTINGS)

        val selected = targets.firstOrNull { it.action in available }

        assertEquals(Settings.ACTION_SETTINGS, selected?.action)
    }
}
