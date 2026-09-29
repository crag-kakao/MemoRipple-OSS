package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat Home Launcher (human brief 2026-09-23, with the user's review of the same day): the chat's
 * empty state offers a small grid — four fixed entrances and up to four templates **the user put
 * there**. The home is its own short list, added to from 「＋ 追加」 and changed by a long press; it
 * is not the ＋ picker's ピン留め. The grid is a **projection**: it stores nothing beyond ids and
 * the names the user gave them, and it can execute nothing.
 */
class HomeShortcutsTest {

    private fun custom(id: String, name: String, flow: TemplateFlow = TemplateFlow.RECORD, action: TemplateAction = TemplateAction.CREATE) =
        MemoTemplate(id = id, name = name, body = "本文", action = action, documentKind = DocumentKind.MEMO, flow = flow)

    private val all = StarterTemplates.all + listOf(custom("u1", "朝の振り返り"), custom("u2", "企画メモ"))

    private fun entries(vararg ids: String) = ids.map { HomeShortcutEntry(it) }

    // --- the fixed four, always, in their order ---

    @Test
    fun theFourFixedShortcutsComeFirstInTheirFixedOrder() {
        val cells = HomeShortcuts.of(all, emptyList())
        assertEquals(
            listOf(HomeShortcut.Memo, HomeShortcut.Search, HomeShortcut.Journal, HomeShortcut.Organize),
            cells.take(HomeShortcuts.FIXED),
        )
        assertEquals(listOf("メモ", "探す", "日記", "整理"), cells.take(4).map { it.label })
        assertEquals(listOf("memo", "search", "journal", "organize"), cells.take(4).map { it.key })
    }

    @Test
    fun withNothingOnTheHomeTheGridIsTheFourAndTheAddEntrance() {
        val cells = HomeShortcuts.of(all, emptyList())
        assertEquals(5, cells.size)
        assertEquals(HomeShortcut.Add, cells.last())
        assertTrue("no empty slot is invented", cells.none { it is HomeShortcut.Template })
    }

    // --- the user's slots ---

    @Test
    fun theChosenTemplatesFollowTheFixedFourInTheOrderTheyWereAdded() {
        val cells = HomeShortcuts.of(all, entries("u2", "starter-think-idea", "u1"))
        assertEquals(listOf("u2", "starter-think-idea", "u1"), cells.filterIsInstance<HomeShortcut.Template>().map { it.template.id })
        assertEquals("the template's own name by default", listOf("企画メモ", "アイデア壁打ち", "朝の振り返り"), cells.filterIsInstance<HomeShortcut.Template>().map { it.label })
        assertEquals("four fixed + three chosen + 追加", 8, cells.size)
        assertEquals(HomeShortcut.Add, cells.last())
    }

    @Test
    fun atMostFourChosenReachTheGridAndItNeverGrowsPastEight() {
        val cells = HomeShortcuts.of(all, entries("u1", "u2", "starter-think-idea", "starter-daily-review", "starter-meeting-memo"))
        assertEquals(HomeShortcuts.MAX_CELLS, cells.size)
        assertEquals(HomeShortcuts.MAX_SHORTCUTS, cells.count { it is HomeShortcut.Template })
        assertTrue("a full grid leaves no room for 追加", cells.none { it == HomeShortcut.Add })
    }

    @Test
    fun anOrphanSlotIsSkippedAndNeverLeavesAHole() {
        val cells = HomeShortcuts.of(all, entries("gone-1", "u1", "gone-2"))
        assertEquals(listOf("u1"), cells.filterIsInstance<HomeShortcut.Template>().map { it.template.id })
        assertEquals("four fixed + the one that still exists + 追加", 6, cells.size)
    }

    @Test
    fun aTemplateMovedToAnotherFolderIsStillTheSameShortcut() {
        val moved = all.map { if (it.id == "u1") it.copy(folderId = "f-work") else it }
        val cells = HomeShortcuts.of(moved, entries("u1"))
        assertEquals("f-work", cells.filterIsInstance<HomeShortcut.Template>().single().template.folderId)
    }

    // --- adding, removing, renaming ---

    @Test
    fun addingPutsATemplateAtTheEndOnceAndOnlyWhileThereIsRoom() {
        var list = HomeShortcuts.added(emptyList(), "u1")
        list = HomeShortcuts.added(list, "u2")
        assertEquals(listOf("u1", "u2"), list.map { it.templateId })
        assertEquals("the same template twice is still one slot", list, HomeShortcuts.added(list, "u1"))
        list = HomeShortcuts.added(HomeShortcuts.added(list, "a"), "b")
        assertEquals(HomeShortcuts.MAX_SHORTCUTS, list.size)
        assertEquals("a full home takes no more", list, HomeShortcuts.added(list, "c"))
    }

    @Test
    fun removingTakesExactlyOneSlotAway() {
        val list = HomeShortcuts.removed(entries("u1", "u2", "u3"), "u2")
        assertEquals(listOf("u1", "u3"), list.map { it.templateId })
        assertEquals("removing what is not there changes nothing", list, HomeShortcuts.removed(list, "zz"))
    }

    @Test
    fun renamingChangesOnlyWhatTheHomeShowsAndABlankNameGivesTheTemplateBack() {
        val named = HomeShortcuts.renamed(entries("u1", "u2"), "u1", "  朝  ")
        assertEquals("朝", named.first { it.templateId == "u1" }.label)
        assertEquals("the other slot is untouched", "", named.first { it.templateId == "u2" }.label)
        val cells = HomeShortcuts.of(all, named)
        val renamed = cells.filterIsInstance<HomeShortcut.Template>().first { it.template.id == "u1" }
        assertEquals("朝", renamed.label)
        assertTrue(renamed.renamed)
        assertEquals("the template itself keeps its name", "朝の振り返り", renamed.template.name)

        val cleared = HomeShortcuts.renamed(named, "u1", "   ")
        assertEquals("", cleared.first { it.templateId == "u1" }.label)
        assertEquals("朝の振り返り", HomeShortcuts.of(all, cleared).filterIsInstance<HomeShortcut.Template>().first().label)
    }

    @Test
    fun aNameTooLongForOneLineIsCutAndNeverWraps() {
        val long = "あ".repeat(40)
        assertEquals(HomeShortcuts.MAX_LABEL_CHARS, HomeShortcuts.cleanLabel(long).length)
        assertEquals("a name is one line", "朝 の 支度", HomeShortcuts.cleanLabel("朝\nの\n支度"))
    }

    // --- identity ---

    @Test
    fun everyCellHasAStableKeyAndANonEmptyLabel() {
        val cells = HomeShortcuts.of(all, entries("u1", "starter-think-idea"))
        assertEquals("keys are unique", cells.size, cells.map { it.key }.toSet().size)
        cells.forEach { assertTrue("a cell without a label: ${it.key}", it.label.isNotBlank()) }
        assertEquals("template:u1", cells.filterIsInstance<HomeShortcut.Template>().first().key)
    }

    @Test
    fun aFixedEntranceIsNeverDuplicatedByAChosenTemplate() {
        val cells = HomeShortcuts.of(all, entries("starter-daily-review"))
        assertEquals(1, cells.count { it == HomeShortcut.Journal })
        assertEquals(1, cells.count { it is HomeShortcut.Template })
    }

    @Test
    fun theGridNeverCarriesRecentsOrFolders() {
        val cells = HomeShortcuts.of(all, emptyList())
        assertTrue(cells.all { it == HomeShortcut.Memo || it == HomeShortcut.Search || it == HomeShortcut.Journal || it == HomeShortcut.Organize || it == HomeShortcut.Add })
    }

    // --- the slots on the wire ---

    @Test
    fun theSlotsSurviveARoundTripAndNothingElseGetsIn() {
        val list = listOf(HomeShortcutEntry("u1", "朝"), HomeShortcutEntry("starter-think-idea"))
        assertEquals(list, HomeShortcutCodec.decode(HomeShortcutCodec.encode(list)))
        val text = HomeShortcutCodec.encode(list)
        assertTrue("ids and names only", text.contains("u1") && text.contains("朝"))
        assertFalse("never a body", text.contains("本文"))
    }

    @Test
    fun anUnreadableOrOverlongPreferenceLeavesTheHomeWithItsFixedFour() {
        assertEquals(emptyList<HomeShortcutEntry>(), HomeShortcutCodec.decode(null))
        assertEquals(emptyList<HomeShortcutEntry>(), HomeShortcutCodec.decode(""))
        assertEquals(emptyList<HomeShortcutEntry>(), HomeShortcutCodec.decode("{not json"))
        val many = (1..9).map { HomeShortcutEntry("u$it") }
        assertEquals(HomeShortcuts.MAX_SHORTCUTS, HomeShortcutCodec.decode(HomeShortcutCodec.encode(many)).size)
        assertEquals("a blank id is no slot", 1, HomeShortcutCodec.decode("""[{"templateId":""},{"templateId":"u1"}]""").size)
        assertEquals("the same template twice is one slot", 1, HomeShortcutCodec.decode("""[{"templateId":"u1"},{"templateId":"u1"}]""").size)
    }
}
