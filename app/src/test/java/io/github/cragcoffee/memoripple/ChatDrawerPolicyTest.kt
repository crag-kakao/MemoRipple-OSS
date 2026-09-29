package io.github.cragcoffee.memoripple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The user's remark of 2026-09-21 evening: the chat's top-left button opens the history as a side
 * drawer, as ChatGPT / Claude do — the conversations as rows, the current one marked, 「新しいチャット」
 * at the bottom — instead of leaving for a separate screen. The history screen stays for managing.
 */
class ChatDrawerPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun theMenuIconOpensADrawerOfConversations() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        assertTrue("a modal drawer", screen.contains("ModalNavigationDrawer"))
        listOf("chat_drawer", "chat_drawer_row_", "chat_drawer_new", "chat_drawer_manage", "chat_drawer_delete_dialog", "chat_drawer_delete_confirm", "chat_drawer_empty").forEach {
            assertTrue("the drawer has $it", screen.contains(it))
        }
        listOf("最近", "新しいチャット", "履歴を管理").forEach { assertTrue(screen.contains(it)) }
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        assertTrue("the view model lists the conversations and opens one by id", vm.contains("val conversations: List<ChatConversation>") && vm.contains("fun openConversation(") && vm.contains("fun deleteConversation("))
        assertFalse("the chat still spells no route", screen.contains("Routes.") || screen.contains("navigate("))
        assertTrue("the history screen stays reachable for managing", text("$main/ui/MemoRippleApp.kt").contains("Routes.CHAT_HISTORY"))
    }

    /** The second remark (2026-09-21 21:29): the title at the top, a search instead of the compose icon, pins by long press, a narrower sheet. */
    @Test
    fun theDrawerIsTightAtTheTopSearchesAndPinsAndIsNarrower() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        val drawer = screen.substringAfter("private fun HistoryDrawer").substringBefore("A quiet empty state")
        assertTrue("no double inset at the top", drawer.contains("windowInsets = WindowInsets(0"))
        assertTrue("narrower than the screen, as the reference", drawer.contains("fillMaxWidth(0.8f)"))
        assertFalse("no compose icon at the top right — 新しいチャット is the button at the bottom", drawer.contains("chat_drawer_compose"))
        listOf("chat_drawer_search", "chat_drawer_search_field", "chat_drawer_pin", "chat_drawer_delete\"", "chat_drawer_section_pinned", "chat_drawer_menu").forEach { assertTrue("the drawer has $it", drawer.contains(it)) }
        listOf("ピン留め", "ピン留めを外す", "履歴を検索").forEach { assertTrue(drawer.contains(it)) }
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        assertTrue("pins are ids in a preference, never content", vm.contains("fun togglePin(") && vm.contains("pinnedConversationIds"))
        assertTrue(text("$main/data/SettingsRepository.kt").contains("chat_pinned_conversation_ids"))
        assertFalse("no Room change for pins", text("$main/data/AppDatabase.kt").contains("pinned"))
    }

    /** 2026-09-21 22:58: the long-press menu opens on the right of the row, as ChatGPT's, with an icon on each entry. */
    @Test
    fun theLongPressMenuOpensOnTheRightWithIcons() {
        val row = text("$main/ui/chat/ChatScreen.kt").substringAfter("private fun DrawerRow").substringBefore("\n}\n")
        assertTrue("anchored at the row's end", row.contains("Alignment.TopEnd") || row.contains("Alignment.CenterEnd"))
        assertTrue("icons on the entries", row.contains("leadingIcon") && row.contains("PushPin") && row.contains("Icons.Outlined.Delete"))
    }
}
