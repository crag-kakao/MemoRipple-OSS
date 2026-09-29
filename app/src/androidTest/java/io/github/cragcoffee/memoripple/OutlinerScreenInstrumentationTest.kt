package io.github.cragcoffee.memoripple

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.down
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.click
import androidx.compose.ui.test.up
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.flow.first
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import kotlin.math.abs
import org.junit.Assert.assertTrue

/**
 * アウトライナー on a device: the body shows as lines; Enter adds a line and Backspace at a
 * line's start removes or joins it; the toolbar moves a line's depth and its place; folding
 * hides, writes nothing, and is remembered when the screen is opened again; zoom shows one
 * subtree with a breadcrumb and Back unwinds it before leaving. What is written back through
 * the memo editor's own autosave is the plain body text the rest of the app reads. The door
 * is the home アウトライナー page: an outline's card.
 */
@OptIn(ExperimentalTestApi::class)
class OutlinerScreenInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MemoRippleApplication

    private val body = "- 一\n  - 二\n- 三"

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

    // --- display / Enter (Phase 1) ---

    @Test
    fun theBodyShowsAsLinesWithBulletsAndAFoldOnTheParent() {
        openOutliner()

        composeRule.onNodeWithTag("outliner_node_1").assertTextEquals("一")
        composeRule.onNodeWithTag("outliner_node_2").assertTextEquals("二")
        composeRule.onNodeWithTag("outliner_node_3").assertTextEquals("三")
        composeRule.onNodeWithTag("outliner_fold_1").assertIsDisplayed()
        composeRule.onNodeWithTag("outliner_glyph_2", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("outliner_glyph_3", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun enterAddsTheNextLineAndTheBodyIsSavedAsPlainText() {
        val memoId = openOutliner()

        composeRule.onNodeWithTag("outliner_node_3").performClick()
        composeRule.onNodeWithTag("outliner_node_3").performTextInput("\n四")

        composeRule.onNodeWithTag("outliner_node_4").assertTextEquals("四")
        composeRule.onNodeWithTag("outliner_node_3").assertTextEquals("三")
        leaveAndExpectBody(memoId, "- 一\n  - 二\n- 三\n- 四")
    }

    @Test
    fun enterOnAParentAddsItsFirstChild() {
        val memoId = openOutliner()

        composeRule.onNodeWithTag("outliner_node_1").performClick()
        composeRule.onNodeWithTag("outliner_node_1").performTextInput("\n")

        leaveAndExpectBody(memoId, "- 一\n  - \n  - 二\n- 三")
    }

    // --- Backspace ---

    @Test
    fun backspaceOnAnEmptyNewLineRemovesItAndReturnsToTheLineAbove() {
        val memoId = openOutliner()
        composeRule.onNodeWithTag("outliner_node_3").performClick()
        composeRule.onNodeWithTag("outliner_node_3").performTextInput("\n")
        composeRule.onNodeWithTag("outliner_node_4").assertIsFocused()

        composeRule.onNodeWithTag("outliner_node_4").performKeyInput { pressKey(Key.Backspace) }

        composeRule.onAllNodesWithTag("outliner_node_4").assertCountEquals(0)
        composeRule.onNodeWithTag("outliner_node_3").assertIsFocused()
        leaveAndExpectBody(memoId, body)
    }

    @Test
    fun backspaceAtTheStartOfALineJoinsItOntoTheLineAbove() {
        val memoId = openOutliner()
        composeRule.onNodeWithTag("outliner_node_3").performClick()
        composeRule.onNodeWithTag("outliner_node_3").performTextInputSelection(TextRange(0))

        composeRule.onNodeWithTag("outliner_node_3").performKeyInput { pressKey(Key.Backspace) }

        composeRule.onAllNodesWithTag("outliner_node_3").assertCountEquals(0)
        composeRule.onNodeWithTag("outliner_node_2").assertTextEquals("二三")
        composeRule.onNodeWithTag("outliner_node_2").assertIsFocused()
        leaveAndExpectBody(memoId, "- 一\n  - 二三")
    }

    @Test
    fun backspaceInsideTheWordsStaysAnOrdinaryDeletion() {
        val memoId = openOutliner()
        composeRule.onNodeWithTag("outliner_node_3").performClick()
        composeRule.onNodeWithTag("outliner_node_3").performTextInputSelection(TextRange(1))

        composeRule.onNodeWithTag("outliner_node_3").performKeyInput { pressKey(Key.Backspace) }

        composeRule.onNodeWithTag("outliner_node_3").assertTextEquals("")
        leaveAndExpectBody(memoId, "- 一\n  - 二\n- ")
    }

    // --- indent / outdent / move ---

    @Test
    fun theToolbarIndentsAndOutdentsTheFocusedLine() {
        val memoId = openOutliner()

        composeRule.onNodeWithTag("outliner_node_3").performClick()
        composeRule.onNodeWithTag("outliner_indent").assertIsEnabled()
        composeRule.onNodeWithTag("outliner_indent").performClick()
        composeRule.onNodeWithTag("outliner_indent").assertIsEnabled()
        composeRule.onNodeWithTag("outliner_indent").performClick()
        composeRule.onNodeWithTag("outliner_indent").assertIsNotEnabled()
        composeRule.onNodeWithTag("outliner_outdent").performClick()

        leaveAndExpectBody(memoId, "- 一\n  - 二\n  - 三")
    }

    @Test
    fun theFirstLineCannotBeIndentedOutdentedOrMovedUp() {
        openOutliner()

        composeRule.onNodeWithTag("outliner_node_1").performClick()

        composeRule.onNodeWithTag("outliner_indent").assertIsNotEnabled()
        composeRule.onNodeWithTag("outliner_outdent").assertIsNotEnabled()
        composeRule.onNodeWithTag("outliner_move_up").assertIsNotEnabled()
        composeRule.onNodeWithTag("outliner_move_down").assertIsEnabled()
    }

    @Test
    fun moveUpCarriesTheSubtreeAndTheLastLineCannotMoveDown() {
        val memoId = openOutliner()

        composeRule.onNodeWithTag("outliner_node_3").performClick()
        composeRule.onNodeWithTag("outliner_move_down").assertIsNotEnabled()
        composeRule.onNodeWithTag("outliner_move_up").performClick()
        composeRule.onNodeWithTag("outliner_move_up").assertIsNotEnabled()

        leaveAndExpectBody(memoId, "- 三\n- 一\n  - 二")
    }

    // --- fold ---

    @Test
    fun foldingHidesTheChildrenAndWritesNothing() {
        val memoId = openOutliner()

        composeRule.onNodeWithTag("outliner_fold_1").performClick()

        composeRule.onAllNodesWithTag("outliner_node_2").assertCountEquals(0)
        composeRule.onNodeWithTag("outliner_node_3").assertIsDisplayed()
        composeRule.onNodeWithTag("outliner_fold_1").performClick()
        composeRule.onNodeWithTag("outliner_node_2").assertIsDisplayed()
        leaveAndExpectBody(memoId, body)
    }

    @Test
    fun aFoldIsStillThereWhenTheOutlinerIsOpenedAgain() {
        val memoId = openOutliner()
        composeRule.onNodeWithTag("outliner_fold_1").performClick()
        composeRule.onAllNodesWithTag("outliner_node_2").assertCountEquals(0)
        leaveAndExpectBody(memoId, body)

        reopenOutlinerFromTheHome(memoId)

        composeRule.onNodeWithTag("outliner_node_1").assertIsDisplayed()
        composeRule.onAllNodesWithTag("outliner_node_2").assertCountEquals(0)
        composeRule.onNodeWithTag("outliner_node_3").assertIsDisplayed()
        composeRule.onNodeWithTag("outliner_fold_1").performClick()
        composeRule.onNodeWithTag("outliner_node_2").assertIsDisplayed()
        leaveAndExpectBody(memoId, body)
    }


    // --- the look (S26 trial, 2026-09-15): no title, WorkFlowy rail, fold on the right ---

    @Test
    fun theScreenHasNoTitleAndTheFoldSitsAtTheRightEdgeOfItsLine() {
        openOutliner()

        composeRule.onAllNodesWithText("アウトライナー").assertCountEquals(0)
        val words = composeRule.onNodeWithTag("outliner_node_1").getBoundsInRoot()
        val fold = composeRule.onNodeWithTag("outliner_fold_1").getBoundsInRoot()
        val bullet = composeRule.onNodeWithTag("outliner_glyph_2", useUnmergedTree = true).getBoundsInRoot()
        val child = composeRule.onNodeWithTag("outliner_node_2").getBoundsInRoot()
        assertTrue("fold $fold must sit right of the words $words", fold.left >= words.right)
        assertTrue("bullet $bullet must sit left of the words $child", bullet.right <= child.left)
    }


    // --- the S26 trial's asks (2026-09-15): bullet zoom, rail fold, hidden count, Tab, task tick, drag, place ---

    @Test
    fun aTapOnTheBulletZoomsIntoTheLine() {
        openOutliner()

        composeRule.onNodeWithTag("outliner_bullet_1").performClick()

        awaitTag("outliner_crumb_current")
        composeRule.onNodeWithTag("outliner_crumb_current").assertTextEquals("一")
        composeRule.onNodeWithTag("outliner_node_2").assertIsDisplayed()
        composeRule.onAllNodesWithTag("outliner_node_3").assertCountEquals(0)
    }

    @Test
    fun aTapOnTheRailFoldsTheParentTheRailHangsFrom() {
        val memoId = openOutliner()

        // The rail of 一 runs beside 二 at 一's bullet column (40dp); a tap just left of it, in the margin, is on the rail.
        composeRule.onNodeWithTag("outliner_row_2").performTouchInput {
            click(Offset(with(density) { 32.dp.toPx() }, centerY))
        }

        composeRule.onAllNodesWithTag("outliner_node_2").assertCountEquals(0)
        composeRule.onNodeWithTag("outliner_node_1").assertIsDisplayed()
        leaveAndExpectBody(memoId, body)
    }

    @Test
    fun aFoldedLineSaysHowManyLinesItHides() {
        openOutliner("- 一\n  - 二\n    - 三\n- 四")

        composeRule.onAllNodesWithTag("outliner_hidden_1").assertCountEquals(0)
        composeRule.onNodeWithTag("outliner_fold_1").performClick()

        composeRule.onNodeWithTag("outliner_hidden_1").assertTextEquals("+2")
    }

    @Test
    fun tabIndentsAndShiftTabOutdentsTheLineWithTheCaret() {
        val memoId = openOutliner()

        composeRule.onNodeWithTag("outliner_node_3").performClick()
        composeRule.onNodeWithTag("outliner_node_3").assertIsFocused()
        composeRule.onNodeWithTag("outliner_node_3").performKeyInput { pressKey(Key.Tab) }
        composeRule.waitForIdle()
        val indented = composeRule.onNodeWithTag("outliner_node_3").getBoundsInRoot()
        val root = composeRule.onNodeWithTag("outliner_node_1").getBoundsInRoot()
        assertTrue("三 must step in after Tab: $indented vs $root", indented.left > root.left)

        composeRule.onNodeWithTag("outliner_node_3").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        composeRule.waitForIdle()
        leaveAndExpectBody(memoId, body)
    }

    @Test
    fun aTaskLineWearsTheDotAndABoxAndADoneLineIsMarkedDoneWhileEditing() {
        openOutliner("- [ ] 買い物\n- [x] 掃除")
        // 「・□」: the dot every line has, then the box — the line reads like its neighbours.
        composeRule.onNodeWithTag("outliner_glyph_1", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("outliner_task_box_1", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("outliner_task_box_2", useUnmergedTree = true).assertIsDisplayed()
        // A ticked line says so while editing too (its words are struck through).
        composeRule.onNodeWithTag("outliner_node_2").assert(hasStateDescription("完了"))
        composeRule.onNodeWithTag("outliner_node_1").assert(hasStateDescription("完了").not())
        // …and still while it is the live field.
        composeRule.onNodeWithTag("outliner_node_2").performClick()
        composeRule.onNodeWithTag("outliner_node_2").assert(hasStateDescription("完了"))
    }

    @Test
    fun aTapOnATaskBulletTicksItAndATapAgainUnticksIt() {
        val memoId = openOutliner("- [ ] 買い物\n- [x] 掃除")

        // A task line wears a box, not its notation: small, in the same quiet grey as the dots.
        val box = composeRule.onNodeWithTag("outliner_glyph_1", useUnmergedTree = true)
        box.assert(hasText("- [ ]").not())
        val boxWidth = box.getBoundsInRoot().width
        assertTrue("box $boxWidth should be a glyph, not a button", boxWidth <= 24.dp)
        composeRule.onNodeWithTag("outliner_bullet_1").performClick()
        composeRule.onNodeWithTag("outliner_bullet_2").performClick()

        leaveAndExpectBody(memoId, "- [x] 買い物\n- [ ] 掃除")
    }

    @Test
    fun aLongPressDragOnTheBulletMovesTheLineWithItsChildren() {
        val memoId = openOutliner()

        val rowHeight = composeRule.onNodeWithTag("outliner_row_3").getBoundsInRoot().height
        val dragPx = with(composeRule.density) { (rowHeight * 2.5f).toPx() }
        composeRule.onNodeWithTag("outliner_bullet_3").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, -dragPx), delayMillis = 200)
            up()
        }

        leaveAndExpectBody(memoId, "- 三\n- 一\n  - 二")
    }

    @Test
    fun theOutlineOpensWhereItWasLeft() {
        val long = (1..60).joinToString("\n") { "- 行$it" }
        val memoId = openOutliner(long)

        // Line 50 to the top of the list: the place to come back to is exactly that line.
        composeRule.onNodeWithTag("outliner_list").performScrollToIndex(49)
        composeRule.onNodeWithTag("outliner_node_50").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("memo_view_outliner")
        val anchor = runBlocking { application.settingsRepository.outlinerScrollAnchor(memoId).first() }
        assertTrue("a place must have been remembered", !anchor.isNullOrBlank())

        awaitTag("outline_card_$memoId")
        composeRule.onNodeWithTag("outline_card_$memoId").performClick()
        awaitTag("outliner_screen")
        runCatching { awaitTag("outliner_node_50") }.onFailure {
            val shown = composeRule.onAllNodesWithContentDescription("アウトラインの行").fetchSemanticsNodes()
                .map { it.config.getOrNull(SemanticsProperties.TestTag) }
            throw AssertionError("line 50 not on screen; shown: $shown; anchor: $anchor")
        }

        composeRule.onNodeWithTag("outliner_node_50").assertIsDisplayed()
        composeRule.onAllNodesWithTag("outliner_node_1").assertCountEquals(0)
    }

    @Test
    fun aLineStandsAtLeastTwentyEightDpTall() {
        openOutliner()

        // 28dp of words plus 8dp above and below: a row is 44dp, up from 40.
        val row = composeRule.onNodeWithTag("outliner_row_3").getBoundsInRoot()
        assertTrue("row height ${row.height}", row.height >= 44.dp)
    }


    @Test
    fun everyZoomCrumbSitsOnTheSameLineAsTheCurrentName() {
        openOutliner()
        composeRule.onNodeWithTag("outliner_bullet_2").performClick()
        awaitTag("outliner_crumb_current")

        val current = composeRule.onNodeWithTag("outliner_crumb_current").getBoundsInRoot()
        val currentCenter = (current.top + current.bottom) / 2
        listOf("outliner_crumb_root_label", "outliner_crumb_1_label").forEach { tag ->
            val label = composeRule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
            val labelCenter = (label.top + label.bottom) / 2
            assertTrue("$tag at $labelCenter, current at $currentCenter", abs((labelCenter - currentCenter).value) <= 1f)
        }
    }


    @Test
    fun thePlusUnderTheLastLineAddsALineThereAndTakesTheCaret() {
        val memoId = openOutliner()

        composeRule.onNodeWithTag("outliner_append").performClick()

        awaitTag("outliner_node_4")
        composeRule.onNodeWithTag("outliner_node_4").assertIsFocused()
        composeRule.onNodeWithTag("outliner_node_4").performTextInput("四")
        leaveAndExpectBody(memoId, "- 一\n  - 二\n- 三\n- 四")
    }

    @Test
    fun aDraggedLineFollowsTheFingerBeforeItChangesPlace() {
        openOutliner()

        val before = composeRule.onNodeWithTag("outliner_row_3").getBoundsInRoot()
        val halfRow = with(composeRule.density) { (before.height / 2).toPx() }
        composeRule.onNodeWithTag("outliner_bullet_3").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, -halfRow), delayMillis = 100)
        }
        composeRule.waitForIdle()

        val during = composeRule.onNodeWithTag("outliner_row_3").getBoundsInRoot()
        assertTrue("row must lift with the finger: $before -> $during", during.top < before.top - 4.dp)
        composeRule.onNodeWithTag("outliner_bullet_3").performTouchInput { up() }
    }


    @Test
    fun aDragCarriesALineIntoAnotherBranch() {
        val memoId = openOutliner()

        // 三 up one and a half rows: past 二, under 一 — which has children, so it joins them first.
        val rowHeight = composeRule.onNodeWithTag("outliner_row_3").getBoundsInRoot().height
        val dragPx = with(composeRule.density) { (rowHeight * 1.5f).toPx() }
        composeRule.onNodeWithTag("outliner_bullet_3").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, -dragPx), delayMillis = 200)
            up()
        }

        leaveAndExpectBody(memoId, "- 一\n  - 三\n  - 二")
    }

    @Test
    fun aDragToTheRightStepsTheLineIn() {
        val memoId = openOutliner()

        val stepPx = with(composeRule.density) { 24.dp.toPx() }
        composeRule.onNodeWithTag("outliner_bullet_3").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(stepPx, 0f), delayMillis = 200)
            up()
        }

        leaveAndExpectBody(memoId, "- 一\n  - 二\n  - 三")
    }


    @Test
    fun enterOnAnEmptyNestedLineStepsItOutInsteadOfAddingOne() {
        val memoId = openOutliner("- 一\n  - 二\n  - ")

        composeRule.onNodeWithTag("outliner_node_3").performClick()
        composeRule.onNodeWithTag("outliner_node_3").performTextInput("\n")

        composeRule.onAllNodesWithTag("outliner_node_4").assertCountEquals(0)
        composeRule.onNodeWithTag("outliner_node_3").assertIsFocused()
        leaveAndExpectBody(memoId, "- 一\n  - 二\n- ")
    }

    // --- zoom ---

    @Test
    fun zoomShowsOneSubtreeWithABreadcrumbAndBackUnwindsItBeforeLeaving() {
        openOutliner()
        composeRule.onNodeWithTag("outliner_node_1").performClick()

        composeRule.onNodeWithTag("outliner_zoom").performClick()

        composeRule.onNodeWithTag("outliner_breadcrumb").assertIsDisplayed()
        composeRule.onNodeWithTag("outliner_crumb_current").assertTextEquals("一")
        composeRule.onNodeWithTag("outliner_node_2").assertIsDisplayed()
        composeRule.onAllNodesWithTag("outliner_node_3").assertCountEquals(0)

        // With a line focused the keyboard is up, and the system's first Back closes it —
        // the same order a person meets on a phone. The zoom unwinds on the next one.
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        composeRule.onAllNodesWithTag("outliner_breadcrumb").assertCountEquals(0)
        composeRule.onNodeWithTag("outliner_node_3").assertIsDisplayed()
        composeRule.onNodeWithTag("outliner_screen").assertIsDisplayed()

        Espresso.pressBack()
        awaitTag("memo_view_outliner")
        composeRule.onAllNodesWithTag("outliner_screen").assertCountEquals(0)
    }

    @Test
    fun theRootCrumbLeavesTheZoomAndAFoldedRootStillShowsItsChildrenWhileZoomed() {
        openOutliner()
        composeRule.onNodeWithTag("outliner_fold_1").performClick()
        composeRule.onAllNodesWithTag("outliner_node_2").assertCountEquals(0)
        composeRule.onNodeWithTag("outliner_node_1").performClick()

        composeRule.onNodeWithTag("outliner_zoom").performClick()

        composeRule.onNodeWithTag("outliner_node_2").assertIsDisplayed()
        composeRule.onNodeWithTag("outliner_crumb_root").performClick()
        composeRule.onAllNodesWithTag("outliner_breadcrumb").assertCountEquals(0)
        composeRule.onAllNodesWithTag("outliner_node_2").assertCountEquals(0)
        composeRule.onNodeWithTag("outliner_node_3").assertIsDisplayed()
    }

    // --- back ---

    @Test
    fun aSecondTapOnBackWhileTheOutlinerFadesLeavesTheHomeStanding() {
        // The home is the root: a second pop would end the activity, so the outliner's 戻る
        // may pop only its own entry, however many times it is tapped while fading.
        openOutliner()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription("戻る").performClick()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (composeRule.onAllNodesWithTag("memo_view_outliner").fetchSemanticsNodes().isEmpty()) {
            check(System.nanoTime() < deadline) { "The home never came back after 戻る" }
            composeRule.mainClock.advanceTimeByFrame()
            Thread.sleep(10)
        }
        val fadingBack = composeRule.onAllNodesWithContentDescription("戻る").fetchSemanticsNodes()
        if (fadingBack.isNotEmpty()) {
            composeRule.onAllNodesWithContentDescription("戻る")[0].performClick()
        }

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("memo_view_outliner").assertIsDisplayed()
        composeRule.onAllNodesWithTag("outliner_screen").assertCountEquals(0)
        assertEquals(false, composeRule.activity.isFinishing)
    }

    private fun openOutliner(text: String = body): Long {
        val memoId = runBlocking {
            application.database.memoDao().insert(
                MemoEntity(
                    title = "アウトライナー",
                    body = text,
                    createdAt = 1_783_000_000_000L,
                    updatedAt = 1_783_000_000_000L,
                    kind = MemoKind.OUTLINE.storageId,
                ),
            )
        }
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        reopenOutlinerFromTheHome(memoId)
        return memoId
    }

    private fun reopenOutlinerFromTheHome(memoId: Long, firstVisible: Int = 1) {
        awaitTag("outline_card_$memoId")
        composeRule.onNodeWithTag("outline_card_$memoId").performClick()
        runCatching { awaitTag("outliner_node_$firstVisible") }.onFailure {
            val shown = composeRule.onAllNodesWithContentDescription("アウトラインの行").fetchSemanticsNodes()
                .map { it.config.getOrNull(SemanticsProperties.TestTag) }
            val screen = composeRule.onAllNodesWithTag("outliner_screen").fetchSemanticsNodes().size
            throw AssertionError("line $firstVisible not on screen; outliner_screen=$screen; shown: $shown")
        }
    }

    /** 戻る saves through the editor's own path; the body on disk is then plain text again. */
    private fun leaveAndExpectBody(memoId: Long, expected: String) {
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("memo_view_outliner")
        // Wait for the save to land, then compare, so a mismatch reports what was written.
        runCatching {
            composeRule.waitUntil(5_000) {
                runBlocking { application.database.memoDao().findById(memoId)?.body } == expected
            }
        }
        assertEquals(expected, runBlocking { application.database.memoDao().findById(memoId)?.body })
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
