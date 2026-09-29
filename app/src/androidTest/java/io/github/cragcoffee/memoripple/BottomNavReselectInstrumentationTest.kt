package io.github.cragcoffee.memoripple

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.FolderEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.NoteEntity
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessageKind
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.ui.TopLevelTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The bottom navigation's current tab tapped again (docs/BOTTOM_NAV_RESELECT.md): on メモ, the
 * home list being shown — the wall, the outliner list or the note list, inside a folder or not —
 * goes back to its top, and nothing else moves (the inner tab, the folder, the other lists); an
 * editor has no bar and ignores the event, and the event never comes back later; on カレンダー the
 * page goes back to the month with the month, the day and the filter kept; on チャット an open
 * conversation gives way to the chat's home without anything created or deleted, and the home
 * tapped again stays as it is. Switching tabs is unchanged, nothing is written.
 */
class BottomNavReselectInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private val now = System.currentTimeMillis()

    @Before
    fun startEmpty() {
        runBlocking {
            app.database.clearAllTables()
            app.settingsRepository.setAutoPlayOnLaunch(false)
            app.settingsRepository.setLastChatConversationId(null)
        }
    }

    @After
    fun tidy() {
        runBlocking { app.settingsRepository.setLastChatConversationId(null) }
    }

    private fun memos(count: Int, kind: MemoKind = MemoKind.MEMO, folderId: Long? = null, prefix: String = "メモ"): List<Long> = runBlocking {
        (1..count).map { i ->
            app.database.memoDao().insert(
                MemoEntity(
                    title = "$prefix$i",
                    body = if (kind == MemoKind.OUTLINE) "- 行$i\n- 次の行" else "本文 $i\n二行目\n三行目",
                    createdAt = now - 60_000L * (count - i),
                    updatedAt = now - 60_000L * (count - i),
                    kind = kind.storageId,
                    folderId = folderId,
                ),
            )
        }
    }

    private fun hasTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    /** The tag of the row drawn highest on screen among those whose tag starts with [prefix]. */
    private fun topMost(prefix: String): String = composeRule.onAllNodes(hasTagPrefix(prefix)).fetchSemanticsNodes()
        .minBy { it.positionInRoot.y }.config[SemanticsProperties.TestTag]

    private fun displayed(tag: String) = runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
    private fun awaitTag(tag: String) = composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitFor(check: () -> Boolean) = composeRule.waitUntil(10_000, check)
    private fun snapshotMemos() = runBlocking { app.database.backupDao().readMemos().map { Triple(it.id, it.body, it.updatedAt) } }

    /** Scroll [list] far down; [top] must be off screen afterwards. */
    private fun scrollAway(list: String, top: String, index: Int) {
        composeRule.onNodeWithTag(list).performScrollToIndex(index)
        composeRule.waitForIdle()
        assertTrue("$top is off screen after scrolling", !displayed(top))
    }

    private fun reselectMemos() = composeRule.onNodeWithTag("nav_memos").performClick()

    @Test
    fun theWallGoesBackToItsTopAndNothingElseMoves() {
        memos(40)
        val before = snapshotMemos()
        awaitTag("memo_cards")
        val top = topMost("memo_card_")
        scrollAway("memo_cards", top, 39)
        reselectMemos()
        waitFor { displayed(top) }
        composeRule.onNodeWithTag("memo_view_memo").assertIsSelected()
        assertEquals("nothing written", before, snapshotMemos())
        // A few taps in a row: no crash, still the top.
        repeat(5) { reselectMemos() }
        composeRule.waitForIdle()
        assertTrue(displayed(top))
    }

    @Test
    fun theOutlinerListGoesBackToItsTopAndStaysTheOutliner() {
        memos(30, MemoKind.OUTLINE, prefix = "アウトライン")
        memos(40)
        awaitTag("memo_cards")
        // The wall scrolled first: its place must survive a reselect on another page.
        val wallTop = topMost("memo_card_")
        scrollAway("memo_cards", wallTop, 39)
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outliner_list_page")
        val top = topMost("outline_card_")
        scrollAway("outliner_list_page", top, 29)
        reselectMemos()
        waitFor { displayed(top) }
        composeRule.onNodeWithTag("memo_view_outliner").assertIsSelected()
        composeRule.onNodeWithTag("memo_view_memo").performClick()
        awaitTag("memo_cards")
        composeRule.waitForIdle()
        assertTrue("the wall kept its own place", !displayed(wallTop))
    }

    @Test
    fun theNoteListGoesBackToItsTopAndStaysTheNotes() {
        runBlocking {
            (1..25).forEach { i ->
                app.database.noteDao().insert(NoteEntity(title = "ノート$i", coverColor = "teal", createdAt = now - i, updatedAt = now - i))
            }
        }
        awaitTag("memo_view_note")
        composeRule.onNodeWithTag("memo_view_note").performClick()
        awaitTag("note_list")
        val top = topMost("note_row_")
        scrollAway("note_list", top, 24)
        reselectMemos()
        waitFor { displayed(top) }
        composeRule.onNodeWithTag("memo_view_note").assertIsSelected()
    }

    @Test
    fun insideAFolderTheListGoesBackToItsTopAndTheFolderStays() {
        val folder = runBlocking { app.database.folderDao().insert(FolderEntity(name = "旅行", parentFolderId = null, createdAt = 1, updatedAt = 1)) }
        memos(40, folderId = folder, prefix = "旅")
        memos(3, prefix = "外")
        awaitTag("folder_row_$folder")
        composeRule.onNodeWithTag("folder_row_$folder").performClick()
        awaitTag("folder_crumb_current")
        val top = topMost("memo_card_")
        scrollAway("memo_cards", top, 40)
        reselectMemos()
        waitFor { displayed(top) }
        composeRule.onNodeWithTag("folder_crumb_current").assertTextContains("旅行")
        composeRule.onNodeWithTag("folder_breadcrumb").assertIsDisplayed()
    }

    @Test
    fun anEditorHasNoBarIgnoresTheEventAndTheEventNeverComesBackLater() {
        val ids = memos(40)
        awaitTag("memo_cards")
        val top = topMost("memo_card_")
        scrollAway("memo_cards", top, 39)
        val before = snapshotMemos()
        // Open a memo from where the wall stands now.
        val open = composeRule.onAllNodes(hasTagPrefix("memo_card_")).fetchSemanticsNodes()
            .first { node -> runCatching { composeRule.onNodeWithTag(node.config[SemanticsProperties.TestTag]).assertIsDisplayed() }.isSuccess }
            .config[SemanticsProperties.TestTag]
        composeRule.onNodeWithTag(open).performClick()
        waitFor { composeRule.onAllNodesWithTag("memo_cards").fetchSemanticsNodes().isEmpty() }
        assertEquals("an editor shows no bottom bar", 0, composeRule.onAllNodesWithTag("nav_memos").fetchSemanticsNodes().size)
        // Even fired by hand, the event does nothing in the editor…
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.tabReselectSignal.reselect(TopLevelTab.MEMOS) }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("memo_cards").fetchSemanticsNodes().size)
        assertEquals("nothing written", before, snapshotMemos())
        // …and does not wait for the wall: back on it, the wall is where it was.
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("memo_cards")
        composeRule.waitForIdle()
        assertTrue("no late reselect", !displayed(top))
        assertTrue(ids.isNotEmpty())
    }

    @Test
    fun theOutlinerEditorAndANoteHaveNoBarAndTheEventChangesNothing() {
        val outline = memos(1, MemoKind.OUTLINE, prefix = "計画").single()
        val note = runBlocking { app.database.noteDao().insert(NoteEntity(title = "連載", coverColor = "teal", createdAt = now, updatedAt = now)) }
        val before = snapshotMemos()
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$outline")
        composeRule.onNodeWithTag("outline_card_$outline").performClick()
        awaitTag("outliner_node_1")
        assertEquals(0, composeRule.onAllNodesWithTag("nav_memos").fetchSemanticsNodes().size)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.tabReselectSignal.reselect(TopLevelTab.MEMOS) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("outliner_node_1").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("memo_view_note")
        composeRule.onNodeWithTag("memo_view_note").performClick()
        awaitTag("note_row_$note")
        composeRule.onNodeWithTag("note_row_$note").performClick()
        waitFor { composeRule.onAllNodesWithTag("note_list").fetchSemanticsNodes().isEmpty() }
        assertEquals(0, composeRule.onAllNodesWithTag("nav_memos").fetchSemanticsNodes().size)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.tabReselectSignal.reselect(TopLevelTab.MEMOS) }
        composeRule.waitForIdle()
        assertEquals("still the note", 0, composeRule.onAllNodesWithTag("note_list").fetchSemanticsNodes().size)
        assertEquals("nothing written", before, snapshotMemos())
    }

    @Test
    fun theCalendarGoesBackToTheMonthWithTheMonthTheDayAndTheFilterKept() {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        runBlocking {
            (0 until 40).forEach { i ->
                val at = start + (i % 25) * 86_400_000L + i * 60_000L + 3_600_000L
                app.database.memoDao().insert(MemoEntity(title = "暦$i", body = "本文$i", createdAt = at, updatedAt = at))
            }
        }
        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitTag("timeline_month_title")
        val month = composeRule.onNodeWithTag("timeline_month_title").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        val scope = composeRule.onNodeWithTag("timeline_scope_label").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        composeRule.onNodeWithTag("timeline_list").performScrollToIndex(12)
        composeRule.waitForIdle()
        assertTrue(!displayed("timeline_month_title"))
        composeRule.onNodeWithTag("nav_calendar").performClick()
        waitFor { displayed("timeline_month_title") }
        composeRule.onNodeWithTag("timeline_month_title").assertTextContains(month)
        composeRule.onNodeWithTag("timeline_scope_label").assertTextContains(scope)
        composeRule.onNodeWithTag("timeline_filter_ALL").assertIsSelected()
    }

    @Test
    fun chatGoesHomeFromAConversationKeepingItAndTheHomeTappedAgainStays() {
        val id = runBlocking {
            val h = app.chatHistoryRepository
            val c = h.create("旅行の相談")
            h.append(c, ChatRole.USER, ChatMessageKind.TEXT, "旅行の相談")
            h.append(c, ChatRole.ASSISTANT, ChatMessageKind.TEXT, "どこへ行きますか？")
            app.settingsRepository.setLastChatConversationId(c)
            c
        }
        composeRule.onNodeWithTag("nav_chat").performClick()
        awaitTag("chat_title")
        waitFor { runCatching { composeRule.onNodeWithTag("chat_title").assertTextContains("旅行の相談", substring = true) }.isSuccess }
        val messages = runBlocking { app.chatHistoryRepository.messages(id).first() }
        composeRule.onNodeWithTag("nav_chat").performClick()
        awaitTag("chat_home_launcher")
        val conversations = runBlocking { app.chatHistoryRepository.conversations().first() }
        assertEquals("nothing created, nothing deleted", listOf(id), conversations.map { it.id })
        assertEquals("the transcript stays", messages, runBlocking { app.chatHistoryRepository.messages(id).first() })
        // The home tapped again stays the home.
        composeRule.onNodeWithTag("nav_chat").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_home_launcher").assertIsDisplayed()
        assertEquals(listOf(id), runBlocking { app.chatHistoryRepository.conversations().first() }.map { it.id })
        // The conversation opens again from the history, whole.
        composeRule.onNodeWithTag("chat_history").performClick()
        composeRule.waitUntil(10_000) { runCatching { composeRule.onNodeWithTag("chat_drawer_row_$id", useUnmergedTree = true).assertIsDisplayed() }.isSuccess }
        composeRule.onNodeWithTag("chat_drawer_row_$id", useUnmergedTree = true).performClick()
        waitFor { runCatching { composeRule.onNodeWithTag("chat_title").assertTextContains("旅行の相談", substring = true) }.isSuccess }
        assertEquals(messages, runBlocking { app.chatHistoryRepository.messages(id).first() })
    }

    @Test
    fun switchingTabsIsUnchangedAndNeverScrollsAList() {
        memos(40)
        awaitTag("memo_cards")
        val top = topMost("memo_card_")
        scrollAway("memo_cards", top, 39)
        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitTag("timeline_list")
        composeRule.onNodeWithTag("nav_chat").performClick()
        awaitTag("chat_input")
        composeRule.onNodeWithTag("nav_memos").performClick()
        awaitTag("memo_cards")
        composeRule.waitForIdle()
        assertTrue("switching back is not a reselect", !displayed(top))
        // A recreated activity does not replay any tap either.
        composeRule.activityRule.scenario.recreate()
        awaitTag("memo_cards")
        composeRule.waitForIdle()
        assertTrue(!displayed(top))
    }
}
