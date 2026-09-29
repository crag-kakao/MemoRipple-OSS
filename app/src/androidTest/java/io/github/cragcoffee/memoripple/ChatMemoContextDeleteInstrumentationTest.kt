package io.github.cragcoffee.memoripple

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessageKind
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationStore
import io.github.cragcoffee.memoripple.domain.ai.conversation.LastConversationStore
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import io.github.cragcoffee.memoripple.ui.chat.AiPhase
import io.github.cragcoffee.memoripple.ui.chat.ChatViewModel
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「メモを選択」 (§16.105) and a conversation deleted mid-request (§16.106), together: a memo selected in
 * conversation A, an APPEND to it in flight or on screen as a preview, then A deleted. The request
 * stops, A stays gone, its selection goes with it, no preview is shown or confirmed, and the memo
 * is not written — write authority over the memo never outlives the conversation. The real
 * orchestrator, no model (the deterministic path); the race is made at the reply's first write.
 */
@RunWith(AndroidJUnit4::class)
class ChatMemoContextDeleteInstrumentationTest {
    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val repository get() = application.chatHistoryRepository
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun start() {
        runBlocking {
            application.database.clearAllTables()
            application.chatMemoSelectionStore.clear()
            application.aiOrchestrator.release()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0; TestGuards.reset()
        TestAiSelection.descriptor = null   // no model: the deterministic path only
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After
    fun end() {
        runBlocking { application.chatMemoSelectionStore.clear(); application.aiOrchestrator.release() }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestGuards.reset()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    /** The product store with a gate in front of the reply's first write; its end is signalled where it happens. */
    private class GatedStore(private val real: ConversationStore) : ConversationStore by real {
        @Volatile var armed = false
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val heldDone = CompletableDeferred<Unit>()
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        private suspend fun <T> held(write: suspend () -> T): T {
            if (!armed) return write()
            armed = false
            reached.complete(Unit)
            try {
                release.await()
                return write()
            } catch (e: Throwable) {
                if (e !is kotlinx.coroutines.CancellationException) failures += e
                throw e
            } finally {
                heldDone.complete(Unit)
            }
        }
        override suspend fun append(conversationId: Long, role: ChatRole, kind: ChatMessageKind, text: String) =
            if (role == ChatRole.ASSISTANT) held { real.append(conversationId, role, kind, text) } else real.append(conversationId, role, kind, text)
        override suspend fun setLatestResults(conversationId: Long, results: List<DocumentSummary>) = held { real.setLatestResults(conversationId, results) }
        override suspend fun setAnchor(conversationId: Long, ref: DocumentRef?) = held { real.setAnchor(conversationId, ref) }
    }

    private class MemoryLastConversation : LastConversationStore {
        val value = MutableStateFlow<Long?>(null)
        override val lastConversationId: Flow<Long?> get() = value
        override suspend fun setLastConversationId(id: Long?) { value.value = id }
    }

    private fun memo(title: String, body: String): Long = runBlocking {
        application.memoRepository.save(existing = null, title = title, body = body, now = application.timeProvider.nowMillis())!!.id
    }
    private fun body(id: Long) = runBlocking { application.database.memoDao().findById(id)!!.body }
    private fun conversations() = runBlocking { repository.conversations().first() }
    private fun selections() = runBlocking { application.chatMemoSelectionStore.selections.first() }
    private fun orphanRows(): Int = application.database.openHelper.readableDatabase.query(
        "SELECT (SELECT count(*) FROM chat_messages WHERE conversationId NOT IN (SELECT id FROM chat_conversations)) + (SELECT count(*) FROM chat_result_refs WHERE conversationId NOT IN (SELECT id FROM chat_conversations))",
    ).use { c -> c.moveToFirst(); c.getInt(0) }

    private fun viewModel(store: ConversationStore, last: LastConversationStore) = ChatViewModel(
        savedStateHandle = SavedStateHandle(),
        ai = application.aiOrchestrator,
        history = store,
        lastConversation = last,
        memoChoices = application.memoChoices,
        memoSelections = application.chatMemoSelectionStore,
    )

    /** A new chat with memo X chosen, then 「『牛乳』を追記して」 — held at the reply's first write; returns conversation A. */
    private fun appendToTheSelectedMemoHeld(vm: ChatViewModel, store: GatedStore, memoId: Long): Long {
        vm.selectMemo(memoId)
        runBlocking { withTimeout(5_000) { vm.uiState.first { it.selectedMemo?.ref?.id == memoId } } }
        store.armed = true
        vm.updateInput("『牛乳』を追記して")
        vm.ask()
        runBlocking { withTimeout(10_000) { store.reached.await() } }
        val asked = conversations().single().id
        assertEquals("the choice was bound to the new conversation", mapOf(asked to memoId), selections())
        return asked
    }

    private fun assertNothingSurvivesA(asked: Long, memoId: Long, vm: ChatViewModel, store: GatedStore) {
        assertTrue("nothing escaped: $uncaught", uncaught.isEmpty())
        assertTrue("the store refused by check, never by exception: ${store.failures}", store.failures.isEmpty())
        assertTrue("A stays deleted", conversations().none { it.id == asked })
        runBlocking { withTimeout(5_000) { while (asked in selections()) kotlinx.coroutines.delay(10) } }
        assertFalse("its selection went with it", asked in selections())
        assertFalse("no preview", vm.uiState.value.ai.result is AiInteractionResult.WritePreview)
        assertEquals("the memo is not written", "卵", body(memoId))
        assertEquals("no row without its conversation", 0, orphanRows())
        assertEquals("no model was loaded", 0, TestAiRuntime.loads)
    }

    @Test
    fun anAppendToTheSelectedMemoInFlightWhenTheConversationIsDeletedElsewhereWritesNothing() {
        val memoId = memo("買い物", "卵")
        val store = GatedStore(repository)
        val last = MemoryLastConversation()
        val vm = viewModel(store, last)
        val asked = appendToTheSelectedMemoHeld(vm, store, memoId)
        // 履歴を管理: forgotten, then deleted, in another screen
        runBlocking { last.setLastConversationId(null); repository.delete(asked) }
        store.release.complete(Unit)
        runBlocking { withTimeout(10_000) { store.heldDone.await() } }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertNothingSurvivesA(asked, memoId, vm, store)
        vm.confirmWrite()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("and no late confirm writes it either", "卵", body(memoId))
    }

    @Test
    fun anAppendToTheSelectedMemoInFlightWhenTheDrawerDeletesTheConversationIsCancelled() {
        val memoId = memo("買い物", "卵")
        val store = GatedStore(repository)
        val last = MemoryLastConversation()
        val vm = viewModel(store, last)
        val asked = appendToTheSelectedMemoHeld(vm, store, memoId)
        vm.deleteConversation(asked)
        // the request is cancelled at its held write — it ends without the release
        runBlocking { withTimeout(10_000) { store.heldDone.await() } }
        store.release.complete(Unit)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        runBlocking { withTimeout(5_000) { while (conversations().any { it.id == asked }) kotlinx.coroutines.delay(10) } }
        assertNothingSurvivesA(asked, memoId, vm, store)
    }

    @Test
    fun aPreviewOfAnAppendToTheSelectedMemoDiesWithItsConversation() {
        val memoId = memo("買い物", "卵")
        val last = MemoryLastConversation()
        val vm = viewModel(repository, last)
        vm.selectMemo(memoId)
        runBlocking { withTimeout(5_000) { vm.uiState.first { it.selectedMemo != null } } }
        vm.updateInput("『牛乳』を追記して")
        vm.ask()
        val shown = runBlocking { withTimeout(10_000) { vm.uiState.first { it.ai.result is AiInteractionResult.WritePreview && it.ai.phase == AiPhase.DONE } } }
        val preview = (shown.ai.result as AiInteractionResult.WritePreview).preview
        assertTrue("the preview is for the selected memo", preview.toString().contains("買い物"))
        val asked = conversations().single().id
        // deleted elsewhere; the confirm comes before the chat has heard of it
        runBlocking { repository.delete(asked) }
        vm.confirmWrite()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        runBlocking { withTimeout(5_000) { vm.uiState.first { !it.busy } } }
        assertEquals("the memo's write authority did not outlive the conversation", "卵", body(memoId))
        assertTrue(conversations().isEmpty())
        runBlocking { withTimeout(5_000) { while (asked in selections()) kotlinx.coroutines.delay(10) } }
        assertEquals(0, orphanRows())
        assertTrue(uncaught.isEmpty())
        assertEquals(0, TestAiRuntime.loads)
    }
}
