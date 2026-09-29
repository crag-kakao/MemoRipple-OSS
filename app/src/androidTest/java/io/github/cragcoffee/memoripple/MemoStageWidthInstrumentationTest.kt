package io.github.cragcoffee.memoripple

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.settings.StageBackground
import io.github.cragcoffee.memoripple.domain.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Pins the actual screen edges, not just fillMaxWidth inside the editor's gutters. */
class MemoStageWidthInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as MemoRippleApplication

    @After
    fun restoreClockAndSettings() {
        composeRule.mainClock.autoAdvance = true
        runBlocking {
            app.settingsRepository.resetToDefaults()
            app.settingsRepository.resetPlaybackStyle()
        }
    }

    @Test
    fun lightStageReachesBothScreenEdgesAndKeepsTheTextGutters() = checkStage(ThemeMode.LIGHT)

    @Test
    fun darkStageReachesBothScreenEdgesAndKeepsTheTextGutters() = checkStage(ThemeMode.DARK)

    private fun checkStage(theme: ThemeMode) {
        val memoId = runBlocking {
            app.database.clearAllTables()
            app.settingsRepository.resetToDefaults()
            app.settingsRepository.resetPlaybackStyle()
            app.settingsRepository.setAutoPlayOnLaunch(false)
            app.settingsRepository.setTheme(theme)
            app.settingsRepository.setStageBackground(StageBackground.DARK_GRAY)
            app.database.memoDao().insert(
                MemoEntity(
                    title = "Memo Stage — full width",
                    body = "本文の左右位置はそのままです。\n\n- 右端から入り、左端へ抜ける\n- 二行目のコメント\n- 長いコメントも同じ再生ルールで流れます。画面を横断する動きと文字の大きさを確認します。",
                    createdAt = 1_783_000_000_000L,
                    updatedAt = 1_783_000_000_000L,
                ),
            )
        }
        awaitTag("memo_card_$memoId")
        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        awaitTag("memo_reading_view")
        val inlineBody = bounds("memo_reading_view")
        composeRule.onNodeWithTag("open_playback_settings").performClick()
        awaitTag("playback_mode_stage")
        composeRule.onNodeWithTag("playback_mode_stage").performClick()
        composeRule.onNodeWithTag("close_playback_settings").performClick()
        awaitTag("comment_stage")
        composeRule.waitForIdle()

        val root = composeRule.onRoot().fetchSemanticsNode().boundsInWindow
        val stage = bounds("comment_stage")
        val title = bounds("memo_title")
        val body = bounds("memo_reading_view")
        val controls = bounds("open_playback_settings")
        record(theme, "idle", mapOf("root" to root, "stage" to stage, "title" to title, "body" to body, "controls" to controls))

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("play_work_comments").performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithTag("pause_work_comments").assertIsDisplayed()
        record(theme, "flow-1000ms")
        composeRule.mainClock.advanceTimeBy(1_000)
        record(theme, "flow-2000ms")
        composeRule.mainClock.advanceTimeBy(3_000)
        composeRule.onNodeWithTag("play_work_comments").assertIsDisplayed()
        composeRule.mainClock.autoAdvance = true

        assertEquals("Stage left edge", root.left, stage.left, 1f)
        assertEquals("Stage right edge", root.right, stage.right, 1f)
        assertEquals("Stage retains 16:9 height", stage.width * 9f / 16f, stage.height, 1f)
        assertEquals("Reading gutter left", inlineBody.left, body.left, 1f)
        assertEquals("Reading gutter right", inlineBody.right, body.right, 1f)
        assertEquals("Title keeps reading gutter", title.left, body.left, 1f)
        assertTrue("Body stays below stage", body.top >= stage.bottom)
        assertTrue("Controls stay below body", controls.top >= body.bottom)
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow

    private fun record(theme: ThemeMode, moment: String, bounds: Map<String, Rect> = emptyMap()) {
        val prefix = InstrumentationRegistry.getArguments().getString("stageEvidencePrefix") ?: return
        val directory = File(app.getExternalFilesDir(null), "stage-width-evidence").apply { mkdirs() }
        val name = "$prefix-${theme.name.lowercase()}-$moment"
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        if (bounds.isNotEmpty()) {
            val json = JSONObject()
            bounds.forEach { (key, rect) ->
                json.put(key, JSONObject().put("left", rect.left).put("top", rect.top)
                    .put("right", rect.right).put("bottom", rect.bottom))
            }
            File(directory, "$name.json").writeText(json.toString(2))
        }
    }
}
