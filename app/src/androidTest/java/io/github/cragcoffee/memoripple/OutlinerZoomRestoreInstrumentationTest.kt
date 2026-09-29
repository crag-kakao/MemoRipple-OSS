package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.ui.outline.OutlinerSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A zoom outlives the screen's recreation (rotation, process-lean restarts of the activity)
 * without ever being written anywhere durable: it is carried as a content key, like a fold,
 * never as a session id, and re-found in the freshly parsed document. When the line it named
 * is no longer there the outliner is back at the root — never zoomed on a stranger.
 */
@OptIn(ExperimentalTestApi::class)
class OutlinerZoomRestoreInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MemoRippleApplication

    private val body = "- 一\n  - 二\n    - 三\n- 四"

    @Before
    fun startFromAnEmptyWall() {
        runBlocking {
            application.database.clearAllTables()
            application.settingsRepository.resetToDefaults()
            application.settingsRepository.resetPlaybackStyle()
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.settingsRepository.clearOutlinerFolds()
        }
    }

    @After
    fun releaseTheClock() {
        composeRule.mainClock.autoAdvance = true
        runBlocking { application.settingsRepository.clearOutlinerFolds() }
    }

    @Test
    fun aZoomIntoAChildIsStillThereAfterRecreation() {
        openOutliner()
        zoomInto(2)

        composeRule.activityRule.scenario.recreate()

        awaitTag("outliner_breadcrumb")
        composeRule.onNodeWithTag("outliner_crumb_current").assertTextEquals("二")
        composeRule.onNodeWithTag("outliner_crumb_1").assertIsDisplayed()
        composeRule.onNodeWithTag("outliner_node_2").assertIsDisplayed()
        composeRule.onNodeWithTag("outliner_node_3").assertIsDisplayed()
        composeRule.onAllNodesWithTag("outliner_node_1").assertCountEquals(0)
        composeRule.onAllNodesWithTag("outliner_node_4").assertCountEquals(0)
    }

    @Test
    fun aDeepZoomKeepsItsWholeBreadcrumb() {
        openOutliner()
        zoomInto(3)

        composeRule.activityRule.scenario.recreate()

        awaitTag("outliner_breadcrumb")
        composeRule.onNodeWithTag("outliner_crumb_current").assertTextEquals("三")
        composeRule.onNodeWithTag("outliner_crumb_1").assertIsDisplayed()
        composeRule.onNodeWithTag("outliner_crumb_2").assertIsDisplayed()
        composeRule.onNodeWithTag("outliner_node_3").assertIsDisplayed()
        composeRule.onAllNodesWithTag("outliner_node_2").assertCountEquals(0)
    }

    @Test
    fun aZoomIntoATopLevelLineComesBackWithItsSubtree() {
        openOutliner()
        zoomInto(1)

        composeRule.activityRule.scenario.recreate()

        awaitTag("outliner_breadcrumb")
        composeRule.onNodeWithTag("outliner_crumb_current").assertTextEquals("一")
        composeRule.onNodeWithTag("outliner_node_1").assertIsDisplayed()
        composeRule.onNodeWithTag("outliner_node_2").assertIsDisplayed()
        composeRule.onNodeWithTag("outliner_node_3").assertIsDisplayed()
        composeRule.onAllNodesWithTag("outliner_node_4").assertCountEquals(0)
    }

    @Test
    fun backStillUnwindsOneLevelAtATimeAfterRecreation() {
        openOutliner()
        zoomInto(3)

        composeRule.activityRule.scenario.recreate()
        awaitTag("outliner_breadcrumb")

        // A fresh screen holds no live field, so no keyboard stands in Back's way.
        Espresso.pressBack()
        composeRule.onNodeWithTag("outliner_crumb_current").assertTextEquals("二")
        Espresso.pressBack()
        composeRule.onNodeWithTag("outliner_crumb_current").assertTextEquals("一")
        Espresso.pressBack()
        composeRule.onAllNodesWithTag("outliner_breadcrumb").assertCountEquals(0)
        composeRule.onNodeWithTag("outliner_node_4").assertIsDisplayed()
        Espresso.pressBack()
        awaitTag("memo_view_outliner")
        composeRule.onAllNodesWithTag("outliner_screen").assertCountEquals(0)
    }

    @Test
    fun noZoomIsRememberedAsNoZoom() {
        openOutliner()

        composeRule.activityRule.scenario.recreate()

        awaitTag("outliner_node_1")
        composeRule.onAllNodesWithTag("outliner_breadcrumb").assertCountEquals(0)
        composeRule.onNodeWithTag("outliner_node_4").assertIsDisplayed()
    }

    @Test
    fun theKeyNamesTheLineNotItsIdAndAVanishedLineFallsBackToTheRoot() {
        val session = OutlinerSession()
        session.acceptBody("- 一\n  - 二\n    - 三")
        session.zoomInto(2)
        val key = session.zoomKey()
        assertNotNull(key)

        // The same words parsed again: the zoom is found again, whatever the ids.
        val again = OutlinerSession()
        again.acceptBody("- 一\n  - 二\n    - 三")
        again.restoreZoom(key)
        assertEquals(2, again.zoomId)

        // A line added above shifts every id; the key still names 二.
        val shifted = OutlinerSession()
        shifted.acceptBody("- 零\n- 一\n  - 二\n    - 三")
        shifted.restoreZoom(key)
        assertEquals(3, shifted.zoomId)

        // The line rewritten: nothing matches, and the outliner is at the root — not on 参.
        val rewritten = OutlinerSession()
        rewritten.acceptBody("- 一\n  - 参\n    - 三")
        rewritten.restoreZoom(key)
        assertNull(rewritten.zoomId)

        rewritten.zoomInto(2)
        rewritten.restoreZoom(null)
        assertNull(rewritten.zoomId)
        assertNull(OutlinerSession().apply { acceptBody("") }.zoomKey())
    }

    private fun openOutliner(): Long {
        val memoId = runBlocking {
            application.database.memoDao().insert(
                MemoEntity(
                    title = "計画",
                    body = body,
                    createdAt = 1_783_000_000_000L,
                    updatedAt = 1_783_000_000_000L,
                    kind = MemoKind.OUTLINE.storageId,
                ),
            )
        }
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$memoId")
        composeRule.onNodeWithTag("outline_card_$memoId").performClick()
        awaitTag("outliner_node_4")
        return memoId
    }

    private fun zoomInto(nodeId: Int) {
        composeRule.onNodeWithTag("outliner_node_$nodeId").performClick()
        composeRule.onNodeWithTag("outliner_zoom").performClick()
        awaitTag("outliner_breadcrumb")
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
