package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** （ ）内をルビとして読み上げる: the switch persists, and it is off unless someone turns it on. */
class SpeechRubyInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun application(): MemoRippleApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as MemoRippleApplication

    @Before
    fun resetBeforeTest() {
        runBlocking {
            application().database.clearAllTables()
            application().settingsRepository.resetToDefaults()
            application().settingsRepository.setAutoPlayOnLaunch(false)
            application().settingsRepository.setSpeechReadRuby(false)
        }
    }

    @org.junit.After
    fun leaveTheDefaultBehind() {
        runBlocking { application().settingsRepository.setSpeechReadRuby(false) }
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theRubySwitchStartsOffAndTheChoicePersists() {
        assertFalse(runBlocking { application().settingsRepository.speechReadRuby.first() })

        if (composeRule.onAllNodesWithTag("memo_top_menu").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("memo_top_menu").performClick()
            composeRule.onNodeWithTag("memo_open_settings").performClick()
        } else {
            composeRule.onNodeWithContentDescription("設定").performClick()
        }
        awaitTag("settings_list")
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_speech_read_ruby"))
        composeRule.onNodeWithTag("speech_read_ruby_switch").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { application().settingsRepository.speechReadRuby.first() }
        }
        assertTrue(runBlocking { application().settingsRepository.speechReadRuby.first() })
    }
}
