package io.github.cragcoffee.memoripple.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTtsSettingsLauncherTest {
    private val subject = AndroidTtsSettingsLauncher()

    @Test
    fun `launches resolved TTS settings first`() {
        val launched = mutableListOf<String>()

        val opened = subject.openFirstAvailable(
            canResolve = { true },
            launch = launched::add,
        )

        assertTrue(opened)
        assertEquals(listOf(AndroidTtsSettingsLauncher.TTS_SETTINGS_ACTION), launched)
    }

    @Test
    fun `falls back when TTS settings cannot resolve`() {
        val launched = mutableListOf<String>()

        val opened = subject.openFirstAvailable(
            canResolve = { it != AndroidTtsSettingsLauncher.TTS_SETTINGS_ACTION },
            launch = launched::add,
        )

        assertTrue(opened)
        assertEquals(listOf(AndroidTtsSettingsLauncher.actions[1]), launched)
    }

    @Test
    fun `launch failure continues to the next resolved action`() {
        val attempts = mutableListOf<String>()

        val opened = subject.openFirstAvailable(
            canResolve = { true },
            launch = { action ->
                attempts += action
                if (action == AndroidTtsSettingsLauncher.TTS_SETTINGS_ACTION) {
                    error("Activity disappeared")
                }
            },
        )

        assertTrue(opened)
        assertEquals(AndroidTtsSettingsLauncher.actions.take(2), attempts)
    }

    @Test
    fun `returns false without throwing when no settings action is available`() {
        val opened = subject.openFirstAvailable(
            canResolve = { false },
            launch = { error("must not launch") },
        )

        assertFalse(opened)
    }
}
