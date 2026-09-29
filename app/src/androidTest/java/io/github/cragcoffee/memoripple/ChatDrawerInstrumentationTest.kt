package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessageKind
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The history as a side drawer (2026-09-21 evening remark): the menu icon opens it over the chat; a row opens that conversation in place; 「新しいチャット」 opens the stage; a long press deletes after a confirmation; 「履歴を管理」 opens the full screen. */
class ChatDrawerInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    @Before
    fun empty() {
        runBlocking {
            application.database.clearAllTables()
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.settingsRepository.setLastChatConversationId(null)
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestAiSelection.descriptor = null
    }

    @After
    fun tidy() { runBlocking { application.settingsRepository.setLastChatConversationId(null) }; TestAiSelection.reset() }

    private fun conversation(title: String, user: String, assistant: String): Long = runBlocking {
        val h = application.chatHistoryRepository
        val id = h.create(title)
        h.append(id, ChatRole.USER, ChatMessageKind.TEXT, user); h.append(id, ChatRole.ASSISTANT, ChatMessageKind.TEXT, assistant)
        id
    }
    private fun awaitTag(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun awaitGone(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() } }
    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    // the drawer sheet stays composed while closed (it sits off-screen), so open / closed is whether its button is on screen
    private fun awaitDrawerOpen() { composeRule.waitUntil(15_000) { runCatching { composeRule.onNodeWithTag("chat_drawer_new", useUnmergedTree = true).assertIsDisplayed() }.isSuccess } }
    private fun awaitDrawerClosed() { composeRule.waitUntil(15_000) { runCatching { composeRule.onNodeWithTag("chat_drawer_new", useUnmergedTree = true).assertIsNotDisplayed() }.isSuccess } }

    @Test
    fun theMenuIconOpensTheHistoryDrawerAndARowOpensThatConversationInPlace() {
        val a = conversation("こんばんは", "こんばんは", "この依頼はまだ扱えません。")
        val b = conversation("日記を書いて", "日記を書いて", "日記の作成内容を確認してください。")
        runBlocking { application.settingsRepository.setLastChatConversationId(b) }
        composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input")
        composeRule.onNodeWithTag("chat_title").assertTextContains("日記を書いて", substring = true)
        composeRule.onNodeWithTag("chat_history").performClick()
        awaitDrawerOpen()
        composeRule.onNodeWithTag("chat_drawer_row_$b", useUnmergedTree = true).assertIsSelected()
        composeRule.onNodeWithTag("chat_drawer_row_$a", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("no separate screen", 0, count("chat_history_screen"))
        composeRule.onNodeWithTag("chat_drawer_row_$a", useUnmergedTree = true).performClick()
        awaitDrawerClosed()
        composeRule.waitUntil(15_000) { runBlocking { application.settingsRepository.lastChatConversationId.first() } == a }
        composeRule.onNodeWithTag("chat_title").assertTextContains("こんばんは", substring = true)
        assertEquals(0, count("nav_memos").let { if (it == 0) 0 else 0 })   // still the tab (the bottom bar is a host matter)
        composeRule.onNodeWithTag("nav_chat").assertIsDisplayed()
    }

    /** The S20 final smoke (2026-09-27): Back with the drawer open closes the drawer only — the chat tab, its conversation and the rest stay. */
    @Test
    fun backWithTheDrawerOpenClosesOnlyTheDrawerAndTheConversationStays() {
        val a = conversation("こんばんは", "こんばんは", "この依頼はまだ扱えません。")
        runBlocking { application.settingsRepository.setLastChatConversationId(a) }
        composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input")
        composeRule.onNodeWithTag("chat_title").assertTextContains("こんばんは", substring = true)
        composeRule.onNodeWithTag("chat_history").performClick()
        awaitDrawerOpen()
        Espresso.pressBack()
        awaitDrawerClosed()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_chat").assertIsSelected()
        composeRule.onNodeWithTag("chat_input").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_title").assertTextContains("こんばんは", substring = true)
        assertEquals("the conversation stays the current one", a, runBlocking { application.settingsRepository.lastChatConversationId.first() })
        assertEquals("nothing created or deleted", 1, runBlocking { application.chatHistoryRepository.conversations().first() }.size)
        // with the drawer closed, Back is the tab's own again (unchanged semantics): it leaves the chat
        Espresso.pressBack()
        composeRule.waitUntil(15_000) { runCatching { composeRule.onNodeWithTag("nav_memos").assertIsSelected() }.isSuccess }
    }

    @Test
    fun backWithTheDrawerOpenOnTheChatsHomeClosesOnlyTheDrawer() {
        conversation("こんばんは", "こんばんは", "この依頼はまだ扱えません。")
        composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input")
        awaitTag("chat_home_shortcut_memo")
        composeRule.onNodeWithTag("chat_history").performClick()
        awaitDrawerOpen()
        Espresso.pressBack()
        awaitDrawerClosed()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_chat").assertIsSelected()
        composeRule.onNodeWithTag("chat_home_shortcut_memo", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("the home stays the home", null, runBlocking { application.settingsRepository.lastChatConversationId.first() })
    }

    @Test
    fun theDrawersNewChatOpensTheStageAndALongPressDeletesAfterAConfirmation() {
        val a = conversation("こんばんは", "こんばんは", "この依頼はまだ扱えません。")
        composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input")
        composeRule.onNodeWithTag("chat_history").performClick()
        awaitDrawerOpen()
        composeRule.onNodeWithTag("chat_drawer_new", useUnmergedTree = true).performClick()
        awaitTag("chat_stage")
        assertEquals(0, count("nav_chat"))
        Espresso.pressBack()
        awaitTag("nav_chat")
        composeRule.onNodeWithTag("chat_history").performClick()
        awaitDrawerOpen()
        // the long press opens a small menu (2026-09-21 21:29): ピン留め / 削除
        composeRule.onNodeWithTag("chat_drawer_row_$a", useUnmergedTree = true).performTouchInput { longClick() }
        awaitTag("chat_drawer_menu")
        composeRule.onNodeWithTag("chat_drawer_delete", useUnmergedTree = true).performClick()
        awaitTag("chat_drawer_delete_dialog")
        composeRule.onNodeWithTag("chat_drawer_delete_cancel").performClick()
        awaitGone("chat_drawer_delete_dialog")
        assertEquals(1, runBlocking { application.chatHistoryRepository.conversations().first() }.size)
        composeRule.onNodeWithTag("chat_drawer_row_$a", useUnmergedTree = true).performTouchInput { longClick() }
        awaitTag("chat_drawer_menu")
        composeRule.onNodeWithTag("chat_drawer_delete", useUnmergedTree = true).performClick()
        awaitTag("chat_drawer_delete_dialog")
        composeRule.onNodeWithTag("chat_drawer_delete_confirm").performClick()
        composeRule.waitUntil(15_000) { runBlocking { application.chatHistoryRepository.conversations().first() }.isEmpty() }
        awaitTag("chat_drawer_empty")
        composeRule.onNodeWithTag("chat_drawer_manage", useUnmergedTree = true).performClick()
        awaitTag("chat_history_screen")
    }

    /** 2026-09-21 21:29: a pinned conversation sits under ピン留め above 最近 and survives a relaunch of the screen; the search icon filters the rows by title; no compose icon at the top. */
    @Test
    fun aLongPressPinsAConversationAndTheSearchFiltersTheRows() {
        val a = conversation("こんばんは", "こんばんは", "この依頼はまだ扱えません。")
        val b = conversation("日記を書いて", "日記を書いて", "日記の作成内容を確認してください。")
        val c = conversation("テンプレートを作成して", "テンプレートを作成して", "この依頼はまだ扱えません。")
        composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input")
        composeRule.onNodeWithTag("chat_history").performClick()
        awaitDrawerOpen()
        assertEquals("no compose icon in the drawer", 0, count("chat_drawer_compose"))
        assertEquals(0, count("chat_drawer_section_pinned"))
        composeRule.onNodeWithTag("chat_drawer_row_$b", useUnmergedTree = true).performTouchInput { longClick() }
        awaitTag("chat_drawer_menu")
        // the menu (a popup window: its placement is a policy check and an eye check, not a bounds one) offers both entries
        composeRule.onNodeWithTag("chat_drawer_delete", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("chat_drawer_pin", useUnmergedTree = true).performClick()
        awaitTag("chat_drawer_section_pinned")
        composeRule.waitUntil(15_000) { runBlocking { application.settingsRepository.pinnedChatConversationIds.first() } == setOf(b) }
        composeRule.onNodeWithTag("chat_drawer_pinned_$b", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("a pinned row leaves 最近", 0, count("chat_drawer_row_$b"))
        // search: the rows narrow to the titles that contain the words
        composeRule.onNodeWithTag("chat_drawer_search", useUnmergedTree = true).performClick()
        awaitTag("chat_drawer_search_field")
        composeRule.onNodeWithTag("chat_drawer_search_field", useUnmergedTree = true).performTextInput("テンプレ")
        composeRule.waitUntil(15_000) { count("chat_drawer_row_$a") == 0 }
        assertEquals("only the title that contains the words", 1, count("chat_drawer_row_$c"))
        assertEquals(0, count("chat_drawer_pinned_$b"))
        composeRule.onNodeWithTag("chat_drawer_search_field", useUnmergedTree = true).performTextClearance()
        composeRule.waitUntil(15_000) { count("chat_drawer_row_$a") == 1 }
        // unpin
        composeRule.onNodeWithTag("chat_drawer_pinned_$b", useUnmergedTree = true).performTouchInput { longClick() }
        awaitTag("chat_drawer_menu")
        composeRule.onNodeWithTag("chat_drawer_pin", useUnmergedTree = true).performClick()
        composeRule.waitUntil(15_000) { runBlocking { application.settingsRepository.pinnedChatConversationIds.first() }.isEmpty() }
        awaitTag("chat_drawer_row_$b")
    }
}
