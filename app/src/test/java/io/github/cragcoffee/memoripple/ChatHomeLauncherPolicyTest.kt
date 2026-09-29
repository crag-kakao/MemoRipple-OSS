package io.github.cragcoffee.memoripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chat Home Launcher policy (human brief 2026-09-23, with the user's review of the same day),
 * pinned at the source level.
 *
 * The launcher is an **entrance, not an action**: a tap opens a path that already exists — a
 * template's conversation script, the picker at a page, or a fixed question whose answer walks
 * the same validator → Resolver → policy → preview → Human Confirmation. It never writes a
 * document, never builds a ConfirmedCommand, never types a sentence for the Fast Path to read,
 * and never asks a model for anything.
 *
 * The home is **its own short list** — 「＋ 追加」 puts a template on it, a long press takes it off
 * or gives it another name — and it is **not** the ＋ picker's ピン留め. It lives in one light
 * preference of ids and names: no Room, no backup, no template ever copied into it.
 */
class ChatHomeLauncherPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    private val shortcuts by lazy { text("$main/domain/memos/HomeShortcuts.kt") }
    private val screen by lazy { text("$main/ui/chat/ChatScreen.kt") }
    private val vm by lazy { text("$main/ui/chat/ChatViewModel.kt") }
    private val settings by lazy { text("$main/data/SettingsRepository.kt") }

    // --- the model ---

    @Test
    fun theShortcutModelIsAPureProjectionWithTheFixedEntrancesSeparateFromTheChosenOnes() {
        assertTrue("a sealed model", shortcuts.contains("sealed interface HomeShortcut"))
        listOf("data object Memo", "data object Search", "data object Journal", "data object Organize", "data object Add", "data class Template(").forEach {
            assertTrue("the model is missing $it", shortcuts.contains(it))
        }
        assertTrue(shortcuts.contains("const val FIXED = 4"))
        assertTrue(shortcuts.contains("const val MAX_SHORTCUTS = 4"))
        assertTrue(shortcuts.contains("const val MAX_CELLS = 8"))
        assertTrue("a slot is an id and a name, nothing else", shortcuts.contains("data class HomeShortcutEntry(val templateId: String, val label: String = \"\")"))
        listOf("androidx.", "android.", "Dao", "Room", "DataStore", "Composable", "Icon(", "Modifier", "PinnedTemplates").forEach {
            assertFalse("the projection carries $it", shortcuts.contains(it))
        }
    }

    @Test
    fun theHomeIsItsOwnListAndNotThePickersPins() {
        assertTrue("its own preference", settings.contains("""stringPreferencesKey("chat_home_shortcuts")"""))
        assertTrue("the picker's pins are still their own thing", settings.contains("""stringPreferencesKey("chat_pinned_template_ids")"""))
        assertTrue("the grid is built from the home's own list", screen.contains("HomeShortcuts.of(state.templates, state.homeShortcuts)"))
        assertFalse("the grid is not built from the pins", screen.contains("HomeShortcuts.of(state.templates, state.pinnedTemplateIds)"))
        // the two are shown differently: the picker says ピン留め, the adding mode says ホームに追加
        assertTrue(screen.contains("\"ピン留め\""))
        assertTrue(screen.contains("\"ホームに追加\""))
        assertTrue(screen.contains("\"ホームに追加するテンプレート\""))
    }

    // --- one entrance each, all of them existing ---

    @Test
    fun everyShortcutOpensAPathThatAlreadyExists() {
        val body = vm.substringAfter("fun openShortcut(").substringBefore("\n    /**")
        assertTrue("a template of the home starts the ordinary conversation script", body.contains("is HomeShortcut.Template -> pickTemplate("))
        assertTrue("日記 starts the built-in 振り返り template", body.contains("journalTemplate()") && vm.contains("StarterTemplates.dailyReview.id"))
        assertTrue("整理 opens the picker at 整理・壁打ち", body.contains("openTemplatePicker(") && body.contains("Section.THINK"))
        assertTrue("＋追加 opens the same picker, to choose", body.contains("openShortcutChooser()"))
        // メモ / 探す ask one fixed question, built where the other fixed questions are built
        assertTrue(body.contains("HomeAsks.clarify(HomeAsk.MEMO)"))
        assertTrue(body.contains("HomeAsks.clarify(HomeAsk.SEARCH)"))
        val asks = text("$main/domain/ai/decision/HomeAsks.kt")
        assertTrue(asks.contains("ClarificationSlot.BODY") && asks.contains("AiIntent.CREATE"))
        assertTrue(asks.contains("ClarificationSlot.QUERY") && asks.contains("AiIntent.SEARCH"))
        assertTrue("the questions are constants", asks.contains("""const val ASK_MEMO = "何をメモしますか？"""") && asks.contains("""const val ASK_SEARCH = "何を探しますか？""""))
    }

    @Test
    fun theScreenNeverBuildsAProposalOfItsOwn() {
        // the standing boundary (AiBoundaryPolicyTest): ui/chat reaches the pipeline through the one door,
        // so the launcher's drafts are made in the domain and the chat only speaks the question
        listOf("IntentProposal(", "SemanticValidator", "Resolver(", "ExecutionPolicy", "CommandExecutor").forEach {
            assertFalse("the chat builds $it", vm.contains(it) || screen.contains(it))
        }
    }

    @Test
    fun theLauncherNeverWritesADocumentNeverConfirmsAndNeverTypesASentence() {
        val body = vm.substringAfter("fun openShortcut(").substringBefore("\n    /**")
        listOf("DocumentAccess", "documentAccess", "Dao", "insert(", "ConfirmedCommand", ".confirm(", "execute(", "PendingWrite(").forEach {
            assertFalse("openShortcut reaches for $it", body.contains(it))
        }
        listOf("interactFast(", "interact(", "askText(", "updateInput(").forEach {
            assertFalse("openShortcut fakes user input with $it", body.contains(it))
        }
        assertFalse("openShortcut never touches the runtime", body.contains("acquireForGeneration") || body.contains("availability()"))
        // the whole chat still has exactly one confirm and one execute, as every round before
        assertEquals("still exactly one confirm() in the orchestrator", 1, Regex("\\.confirm\\(").findAll(text("$main/domain/ai/AiOrchestrator.kt")).count())
        assertEquals("one execute in the view model", 1, Regex("\\.execute\\(").findAll(vm).count())
    }

    @Test
    fun theAnsweredQuestionWalksTheSameSettlePipeline() {
        assertTrue(vm.contains("ClarificationSlot.QUERY -> pending.draft.copy(query = text)"))
        assertTrue(vm.contains("orchestrator.completeDecision("))
        val decision = text("$main/domain/ai/decision/DecisionEngine.kt")
        assertTrue("the slot is part of the one clarification model", decision.contains("enum class ClarificationSlot { TARGET, BODY, QUERY }"))
        assertFalse("the deterministic engine itself never asks for a query", decision.contains("ClarificationSlot.QUERY"))
    }

    // --- adding, renaming, removing; deleting a template ---

    @Test
    fun theHomeIsChangedOnlyThroughItsOwnStoreAndOnlyByTheUser() {
        listOf("fun openShortcutChooser(", "fun addHomeShortcut(", "fun removeHomeShortcut(", "fun renameHomeShortcut(").forEach {
            assertTrue("the view model is missing $it", vm.contains(it))
        }
        listOf("store.add(templateId)", "store.remove(templateId)", "store.rename(templateId, label)").forEach {
            assertTrue("the change goes through the domain store: $it", vm.contains(it))
        }
        assertTrue("the store is a domain interface", shortcuts.contains("interface HomeShortcutStore"))
        // nothing adds itself: no recent, no run, no model ever puts a template on the home
        val add = vm.substringAfter("fun addHomeShortcut(").substringBefore("\n    /**")
        listOf("recent", "Recent", "interact", "generate").forEach { assertFalse("addHomeShortcut reaches for $it", add.contains(it)) }
    }

    @Test
    fun aTemplateIsDeletedOnlyByTheUserOnlyIfItIsTheirOwnAndTheHomeSlotGoesWithIt() {
        val body = vm.substringAfter("fun deleteTemplate(").substringBefore("\n    /**")
        assertTrue("a starter is never deleted", body.contains("StarterTemplates.isStarter(templateId)") && body.contains("return"))
        assertTrue("it goes through the domain remover", body.contains("remover.remove(templateId)"))
        assertTrue("and takes its home slot with it", body.contains("homeShortcuts?.remove(templateId)"))
        listOf("memoDao", "Dao", "DocumentAccess", "diary", "Memo(").forEach { assertFalse("deleteTemplate reaches for $it", body.contains(it)) }
        assertTrue("the domain names the remover", text("$main/domain/memos/MemoTemplate.kt").contains("interface TemplateRemover"))
        // it is a long press with a confirmation, in both places a template is listed
        assertTrue(screen.contains("chat_template_delete_") && screen.contains("chat_template_delete_confirm") && screen.contains("chat_template_delete_dialog"))
        assertTrue(screen.contains("onLongClick = { menu = true }"))
        val library = text("$main/ui/settings/PhraseLibraryScreens.kt")
        assertTrue(library.contains("settings_template_delete_confirm") && library.contains("onLongClick = { deleteAsk = template }"))
        assertTrue("and it says what it does not delete", screen.contains("メモや日記は削除されません") && library.contains("メモや日記は削除されません"))
    }

    // --- the screen ---

    @Test
    fun theLauncherIsPartOfTheEmptyStateAndOfNothingElse() {
        listOf("chat_home_launcher", "chat_home_shortcut_memo", "chat_home_shortcut_search", "chat_home_shortcut_journal", "chat_home_shortcut_organize", "chat_home_shortcut_add", "chat_home_shortcut_template_").forEach {
            assertTrue("the screen has no $it", screen.contains(it))
        }
        val empty = screen.substringAfter("private fun EmptyConversation(").substringBefore("\n/**")
        assertTrue("the grid lives in the empty state", empty.contains("HomeLauncher("))
        assertTrue("the examples stay, below the grid", empty.contains("chat_example"))
        assertEquals("still two or three examples", 3, Regex("\"(昨日の日記を探して|MemoRipple開発に追記して|振り返りのテンプレートを作りたい)\"").findAll(empty).count())
        // the lower ＋ keeps its meaning: the full library
        assertTrue(screen.contains("chat_plus"))
        assertTrue(screen.contains("chat_template_picker"))
    }

    @Test
    fun everyCellIsAButtonWithALabelATouchTargetAndItsOwnMenu() {
        val launcher = screen.substringAfter("private fun HomeLauncher(").substringBefore("\n/**")
        assertTrue("a cell is a button to a screen reader", launcher.contains("Role.Button"))
        assertTrue("a cell says what it is", launcher.contains("contentDescription"))
        assertTrue("the icon never carries the meaning alone", launcher.contains("shortcut.label"))
        assertTrue("a minimum touch target", launcher.contains("ProductSize.minimumTouchTarget"))
        assertTrue("a long name is cut, never wrapped into the grid", launcher.contains("TextOverflow.Ellipsis") && launcher.contains("maxLines = 1"))
        assertTrue("four columns", launcher.contains("COLUMNS"))
        assertFalse("no emoji icon", Regex("[\\x{1F300}-\\x{1FAFF}]").containsMatchIn(launcher))
        // the long press belongs to a template cell only: the fixed four are not the user's to change
        assertTrue(launcher.contains("(shortcut as? HomeShortcut.Template)"))
        listOf("chat_home_shortcut_menu", "chat_home_shortcut_rename_", "chat_home_shortcut_remove_", "chat_home_shortcut_rename_dialog", "chat_home_shortcut_rename_confirm").forEach {
            assertTrue("the cell menu is missing $it", screen.contains(it))
        }
        assertTrue("「ホームから削除」 says it is the home, not the template", screen.contains("\"ホームから削除\""))
    }

    // --- nothing about storage moves ---

    @Test
    fun theLauncherAddsNoSavedKeyAndNoSchemaChange() {
        assertTrue("Room 29", text("$main/data/AppDatabase.kt").contains("version = 29"))
        assertTrue("Backup 23", text("src/main/java/io/github/cragcoffee/memoripple/backup/BackupDtos.kt").contains("const val BACKUP_FORMAT_VERSION = 23"))
        assertEquals("only the draft is saved", setOf("KEY_INPUT"), Regex("const val (KEY_[A-Z_]+) = ").findAll(vm).map { it.groupValues[1] }.toSet())
        val backup = text("src/main/java/io/github/cragcoffee/memoripple/backup/BackupDtos.kt")
        assertFalse("a device preference stays out of the portable backup", backup.contains("chat_home_shortcuts") || backup.contains("HomeShortcut"))
    }
}
