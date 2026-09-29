package io.github.cragcoffee.memoripple

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The wall's top bar (search, tabs) keeps the page's colour whether the wall is at its top or
 * scrolled under it (the user's review, 2026-09-25): it used to lift a step to a darker surface
 * while the wall was scrolled.
 */
class WallTopBarColorInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    @Before
    fun startEmpty() {
        runBlocking {
            app.database.clearAllTables()
            app.settingsRepository.setAutoPlayOnLaunch(false)
            val now = System.currentTimeMillis()
            (1..40).forEach { i -> app.database.memoDao().insert(MemoEntity(title = "メモ$i", body = "本文 $i\n二行目", createdAt = now - i, updatedAt = now - i)) }
        }
    }

    /** The bar's colour where nothing is drawn on it: its right edge, just above the hairline. */
    private fun barColor(): Int {
        val pixels = composeRule.onNodeWithTag("memo_compact_top_bar").captureToImage().toPixelMap()
        return pixels[pixels.width - 2, pixels.height - 6].hashCode()
    }

    @Test
    fun theBarKeepsThePagesColourWhenTheWallIsScrolled() {
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("memo_cards").fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
        val atTop = barColor()
        composeRule.onNodeWithTag("memo_cards").performScrollToIndex(30)
        composeRule.waitForIdle()
        // Let any colour change finish before looking.
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        assertEquals("the bar's colour is the same as at the top", atTop, barColor())
    }
}
