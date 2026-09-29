package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult
import io.github.cragcoffee.memoripple.domain.ai.AiOrchestrator
import io.github.cragcoffee.memoripple.domain.ai.AiProgress
import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import io.github.cragcoffee.memoripple.domain.ai.AiTiming
import io.github.cragcoffee.memoripple.domain.ai.CommandPreview
import io.github.cragcoffee.memoripple.domain.ai.DocumentVersion
import io.github.cragcoffee.memoripple.domain.ai.ExecutionDecision
import io.github.cragcoffee.memoripple.domain.ai.ExecutionPolicy
import io.github.cragcoffee.memoripple.domain.ai.ModelAvailability
import io.github.cragcoffee.memoripple.domain.ai.PendingWrite
import io.github.cragcoffee.memoripple.domain.ai.ResolutionResult
import io.github.cragcoffee.memoripple.domain.ai.ResolvedCommand
import io.github.cragcoffee.memoripple.domain.ai.WriteOutcome
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatConversation
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessage
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessageKind
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationReferents
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationStore
import io.github.cragcoffee.memoripple.domain.ai.conversation.LastConversationStore
import io.github.cragcoffee.memoripple.domain.ai.conversation.SafeConversationContext
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.documents.CreateDestination
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.ui.chat.AiPhase
import io.github.cragcoffee.memoripple.ui.chat.ChatViewModel
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A conversation deleted while a request of it is still running (the Human Review finding,
 * 2026-09-26): the late reply is never written into it, nothing crashes, the conversation is not
 * brought back, no row is left without its conversation, another conversation is untouched, and
 * a preview born in the deleted conversation can never be confirmed. The race is made, not
 * waited for: the store holds the reply's first write until the test has deleted the conversation.
 */
@RunWith(AndroidJUnit4::class)
class ChatDeleteDuringRequestInstrumentationTest {
    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val repository get() = application.chatHistoryRepository

    /** Whatever escapes a coroutine lands here instead of ending the process. */
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun start() {
        runBlocking { application.database.clearAllTables() }
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After
    fun end() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    // --- the pause point: the reply is ready, its first write has not happened ---

    /** The product store, with a gate in front of the reply's first write (results, anchor or the answer line). */
    private class GatedStore(private val real: ConversationStore) : ConversationStore by real {
        @Volatile var armed = false
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        /** The held write has ended — written, refused, failed or cancelled: the end of the critical section, observed where it happens. */
        val heldDone = CompletableDeferred<Unit>()
        /** What the store itself threw at a write after the gate (an FK violation before the fix). */
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
        val value = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
        override val lastConversationId: Flow<Long?> get() = value
        override suspend fun setLastConversationId(id: Long?) { value.value = id }
    }

    /** A scripted orchestrator: the Fast Path answers [fast] (null = not its sentence); the model answers [generated], behind [generationGate] when set. */
    private class ScriptedOrchestrator : AiOrchestrator {
        @Volatile var fast: AiInteractionResult? = null
        @Volatile var generated: AiInteractionResult = AiInteractionResult.Unknown
        @Volatile var generationGate: CompletableDeferred<Unit>? = null
        @Volatile var generationCancelled = false
        /** The generation is running (the model's lease is held). */
        val generationStarted = CompletableDeferred<Unit>()
        @Volatile var executions = 0
        override suspend fun interact(userText: String, context: AiResultContext, onProgress: (AiProgress) -> Unit, onNote: (String) -> Unit, referents: ConversationReferents, onTiming: (AiTiming) -> Unit, destination: CreateDestination?): AiInteractionResult {
            try {
                generationStarted.complete(Unit)
                generationGate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                generationCancelled = true
                throw e
            }
            return generated
        }
        override fun runtimeState() = RuntimeState.UNLOADED
        override suspend fun availability() = ModelAvailability.Available(TestAiSelection.default)
        override suspend fun runTemplate(template: MemoTemplate, values: Map<String, String>, destination: CreateDestination?): AiInteractionResult = error("not here")
        override suspend fun previewMemo(body: String, destination: CreateDestination?): AiInteractionResult = error("not here")
        override suspend fun interactFast(userText: String, context: AiResultContext, referents: ConversationReferents, destination: CreateDestination?): AiInteractionResult? = fast
        override suspend fun release() = Unit
        override suspend fun execute(pending: PendingWrite): WriteOutcome { executions++; return WriteOutcome.Success(DocumentRef(DocumentKind.MEMO, 1)) }
    }

    private fun summary(title: String) = DocumentSummary(DocumentRef(DocumentKind.MEMO, 1), title, 1, 1)

    private fun preview(): AiInteractionResult.WritePreview {
        val decision = ExecutionPolicy.decide(ResolutionResult.Resolved(ResolvedCommand.Create(DocumentKind.MEMO, io.github.cragcoffee.memoripple.domain.documents.DocumentCreate.Memo(), "x"))) as ExecutionDecision.RequiresConfirmation
        return AiInteractionResult.WritePreview(decision.preview, PendingWrite(decision))
    }

    private fun conversations(): List<ChatConversation> = runBlocking { repository.conversations().first() }
    private fun messages(id: Long): List<ChatMessage> = runBlocking { repository.messages(id).first() }
    /** Lines or shown results whose conversation no longer exists. */
    private fun orphanRows(): Int = application.database.openHelper.readableDatabase.query(
        "SELECT (SELECT count(*) FROM chat_messages WHERE conversationId NOT IN (SELECT id FROM chat_conversations)) + (SELECT count(*) FROM chat_result_refs WHERE conversationId NOT IN (SELECT id FROM chat_conversations))",
    ).use { c -> c.moveToFirst(); c.getInt(0) }

    /** Another conversation with a line of its own, which nothing here may touch. */
    private fun bystander(): Pair<Long, List<ChatMessage>> = runBlocking {
        val id = repository.create("別の会話")
        repository.append(id, ChatRole.USER, ChatMessageKind.TEXT, "そのまま")
        id to repository.messages(id).first()
    }

    private fun startAsking(viewModel: ChatViewModel, store: GatedStore, text: String = "買い物を探して"): Long {
        store.armed = true
        viewModel.updateInput(text)
        viewModel.ask()
        runBlocking { withTimeout(10_000) { store.reached.await() } }
        return conversations().single { it.title != "別の会話" }.id
    }

    private fun assertGoneQuietly(deleted: Long, other: Pair<Long, List<ChatMessage>>) {
        assertTrue("nothing escaped: $uncaught", uncaught.isEmpty())
        assertTrue("the deleted conversation is not brought back", conversations().none { it.id == deleted })
        assertTrue("its lines are gone", messages(deleted).isEmpty())
        assertEquals("no row without its conversation", 0, orphanRows())
        assertEquals("the other conversation is as it was", other.second, messages(other.first))
    }

    private fun settle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); runBlocking { kotlinx.coroutines.delay(0) } }

    // --- the Fast Path (deterministic): a reply ready, the conversation deleted, the reply let go ---

    @Test
    fun aFastReplyToAConversationDeletedFromTheHistoryScreenIsNotWrittenAndNothingCrashes() {
        val other = bystander()
        val store = GatedStore(repository)
        val last = MemoryLastConversation()
        val orchestrator = ScriptedOrchestrator().apply { fast = AiInteractionResult.SearchResults(io.github.cragcoffee.memoripple.domain.documents.DocumentQuery(text = "買い物"), listOf(summary("買い物"))) }
        val viewModel = ChatViewModel(SavedStateHandle(), orchestrator, store, last)
        val asked = startAsking(viewModel, store)
        // 履歴を管理: the current conversation is forgotten, then deleted — in another screen, not through the chat
        runBlocking { last.setLastConversationId(null); repository.delete(asked) }
        store.release.complete(Unit)
        runBlocking { withTimeout(10_000) { store.heldDone.await() } }
        settle()
        assertTrue("the store refused nothing by exception: ${store.failures}", store.failures.isEmpty())
        assertGoneQuietly(asked, other)
        assertNull("the late result is not shown on the next screen", viewModel.uiState.value.ai.result)
    }

    @Test
    fun aModelReplyReadyWhenEveryConversationIsDeletedIsNotWritten() {
        val other = bystander()
        val store = GatedStore(repository)
        val last = MemoryLastConversation()
        val orchestrator = ScriptedOrchestrator().apply { generated = AiInteractionResult.SearchResults(io.github.cragcoffee.memoripple.domain.documents.DocumentQuery(text = "買い物"), listOf(summary("買い物"))) }
        val viewModel = ChatViewModel(SavedStateHandle(), orchestrator, store, last)
        val asked = startAsking(viewModel, store)
        runBlocking { last.setLastConversationId(null); repository.deleteAll() }
        store.release.complete(Unit)
        runBlocking { withTimeout(10_000) { store.heldDone.await() } }
        settle()
        assertTrue("the store refused nothing by exception: ${store.failures}", store.failures.isEmpty())
        assertTrue("nothing escaped: $uncaught", uncaught.isEmpty())
        assertTrue("every conversation stays deleted", conversations().isEmpty())
        assertEquals(0, orphanRows())
        assertTrue(messages(asked).isEmpty() && messages(other.first).isEmpty())
    }

    @Test
    fun aReplyReadyWhenTheDrawerDeletesTheConversationIsNotWritten() {
        val other = bystander()
        val store = GatedStore(repository)
        val last = MemoryLastConversation()
        val orchestrator = ScriptedOrchestrator().apply { fast = AiInteractionResult.SearchResults(io.github.cragcoffee.memoripple.domain.documents.DocumentQuery(text = "買い物"), listOf(summary("買い物"))) }
        val viewModel = ChatViewModel(SavedStateHandle(), orchestrator, store, last)
        val asked = startAsking(viewModel, store)
        viewModel.deleteConversation(asked)
        runBlocking { withTimeout(5_000) { while (conversations().any { it.id == asked }) kotlinx.coroutines.delay(10) } }
        store.release.complete(Unit)
        runBlocking { withTimeout(10_000) { store.heldDone.await() } }
        settle()
        assertTrue("the store refused nothing by exception: ${store.failures}", store.failures.isEmpty())
        assertGoneQuietly(asked, other)
        assertNull(viewModel.uiState.value.ai.result)
    }

    @Test
    fun deletingTheConversationCancelsItsRunningGeneration() {
        val store = GatedStore(repository)
        val last = MemoryLastConversation()
        val orchestrator = ScriptedOrchestrator().apply { generationGate = CompletableDeferred(); generated = AiInteractionResult.Unknown }
        val viewModel = ChatViewModel(SavedStateHandle(), orchestrator, store, last)
        viewModel.updateInput("何かして")
        viewModel.ask()
        // the generation is running for the conversation — only then is it deleted
        runBlocking { withTimeout(10_000) { orchestrator.generationStarted.await() } }
        val asked = conversations().single().id
        viewModel.deleteConversation(asked)
        runBlocking { withTimeout(10_000) { while (!orchestrator.generationCancelled) kotlinx.coroutines.delay(10) } }
        runBlocking { withTimeout(10_000) { viewModel.uiState.first { !it.busy } } }
        orchestrator.generationGate?.complete(Unit)
        settle()
        assertTrue("the generation was cancelled, its resources released", orchestrator.generationCancelled)
        assertTrue(uncaught.isEmpty())
        assertTrue(conversations().isEmpty())
        assertEquals(0, orphanRows())
    }

    // --- write authority: a preview born in a deleted conversation is never confirmed ---

    @Test
    fun aLatePreviewOfADeletedConversationIsNeverShownOrConfirmed() {
        val store = GatedStore(repository)
        val last = MemoryLastConversation()
        val orchestrator = ScriptedOrchestrator().apply { fast = preview() }
        val viewModel = ChatViewModel(SavedStateHandle(), orchestrator, store, last)
        val asked = startAsking(viewModel, store, "メモを作って")
        runBlocking { last.setLastConversationId(null); repository.delete(asked) }
        store.release.complete(Unit)
        runBlocking { withTimeout(10_000) { store.heldDone.await() } }
        settle()
        assertTrue("the store refused nothing by exception: ${store.failures}", store.failures.isEmpty())
        assertFalse("no preview of the deleted conversation", viewModel.uiState.value.ai.result is AiInteractionResult.WritePreview)
        viewModel.confirmWrite()
        settle()
        assertEquals("nothing is written for it", 0, orchestrator.executions)
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun aPreviewOnScreenWhoseConversationIsDeletedCannotBeConfirmed() {
        val last = MemoryLastConversation()
        val orchestrator = ScriptedOrchestrator().apply { fast = preview() }
        val viewModel = ChatViewModel(SavedStateHandle(), orchestrator, repository, last)
        viewModel.updateInput("メモを作って")
        viewModel.ask()
        runBlocking { withTimeout(10_000) { viewModel.uiState.first { it.ai.result is AiInteractionResult.WritePreview && it.ai.phase == AiPhase.DONE } } }
        val asked = conversations().single().id
        // deleted elsewhere; the confirm comes before the chat has heard of it
        runBlocking { repository.delete(asked) }
        viewModel.confirmWrite()
        settle()
        runBlocking { withTimeout(5_000) { viewModel.uiState.first { !it.busy } } }
        assertEquals("the ticket of a deleted conversation writes nothing", 0, orchestrator.executions)
        assertTrue(conversations().isEmpty())
        assertEquals(0, orphanRows())
        assertTrue(uncaught.isEmpty())
    }

    // --- the ordinary order still holds ---

    @Test
    fun aReplyThatArrivesBeforeTheDeletionStaysAndTheDeletionThenRemovesEverything() {
        val last = MemoryLastConversation()
        val orchestrator = ScriptedOrchestrator().apply { fast = AiInteractionResult.SearchResults(io.github.cragcoffee.memoripple.domain.documents.DocumentQuery(text = "買い物"), listOf(summary("買い物"))) }
        val viewModel = ChatViewModel(SavedStateHandle(), orchestrator, repository, last)
        viewModel.updateInput("買い物を探して")
        viewModel.ask()
        runBlocking { withTimeout(10_000) { viewModel.uiState.first { it.ai.result is AiInteractionResult.SearchResults } } }
        val asked = conversations().single().id
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), messages(asked).map { it.role })
        viewModel.deleteConversation(asked)
        runBlocking { withTimeout(5_000) { while (conversations().isNotEmpty()) kotlinx.coroutines.delay(10) } }
        settle()
        assertEquals(0, orphanRows())
        assertTrue(uncaught.isEmpty())
    }
}

/**
 * The same, end to end: the real chat, the real orchestrator and a model generation held by the
 * test runtime, the conversation deleted from 履歴を管理 while it runs.
 */
class ChatDeleteDuringGenerationJourneyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun start() {
        runBlocking {
            application.database.clearAllTables()
            application.templateRepository.replaceAll(emptyList())
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.settingsRepository.setLastChatConversationId(null)
            application.aiOrchestrator.release()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0
        TestGuards.fastPathEnabled = false   // the model's route, on purpose
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After
    fun end() {
        TestAiRuntime.generateGate?.complete(Unit); TestAiRuntime.loadGate?.complete(Unit)
        runBlocking { application.aiOrchestrator.release(); application.settingsRepository.setLastChatConversationId(null) }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestGuards.fastPathEnabled = true
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun awaitTag(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun conversations() = runBlocking { application.chatHistoryRepository.conversations().first() }
    private fun orphanRows(): Int = application.database.openHelper.readableDatabase.query(
        "SELECT (SELECT count(*) FROM chat_messages WHERE conversationId NOT IN (SELECT id FROM chat_conversations)) + (SELECT count(*) FROM chat_result_refs WHERE conversationId NOT IN (SELECT id FROM chat_conversations))",
    ).use { c -> c.moveToFirst(); c.getInt(0) }
    private fun search(query: String) =
        "{\"intent\":\"SEARCH\",\"query\":\"$query\",\"targetRef\":null,\"targetName\":null,\"documentKind\":null,\"text\":null,\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}"

    private fun ask(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }

    /** A model generation held open for conversation A; returns A. */
    private fun holdAGeneration(): Long {
        composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input")
        TestAiRuntime.generateGate = CompletableDeferred()
        TestAiRuntime.answers.add(search("買い物"))
        ask("買い物のメモを探して")
        composeRule.waitUntil(15_000) { conversations().isNotEmpty() && TestAiRuntime.requests == 1 }
        return conversations().single().id
    }

    private fun openHistoryScreen() {
        composeRule.onNodeWithTag("chat_history").performClick()
        awaitTag("chat_drawer_manage")
        composeRule.onNodeWithTag("chat_drawer_manage").performClick()
        awaitTag("chat_history_screen")
    }

    @Test
    fun deletingItFromTheHistoryScreenMidGenerationEndsQuietly() {
        val asked = holdAGeneration()
        openHistoryScreen()
        awaitTag("chat_history_delete_$asked")
        composeRule.onNodeWithTag("chat_history_delete_$asked").performClick()
        composeRule.onNodeWithTag("chat_history_delete_confirm").performClick()
        composeRule.waitUntil(5_000) { conversations().isEmpty() }
        TestAiRuntime.generateGate?.complete(Unit)   // whatever was still running may finish now
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("nothing escaped: $uncaught", uncaught.isEmpty())
        assertTrue("not brought back", conversations().isEmpty())
        assertEquals(0, orphanRows())
        // the model is free again: the next ask, in a new conversation, is answered
        composeRule.onNodeWithTag("chat_history_back").performClick()
        awaitTag("chat_input")
        TestAiRuntime.generateGate = null
        TestAiRuntime.answers.add(search("卵"))
        ask("卵のメモを探して")
        composeRule.waitUntil(15_000) { conversations().size == 1 && runBlocking { application.chatHistoryRepository.messages(conversations().single().id).first() }.any { it.role == io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole.ASSISTANT } }
        assertTrue(uncaught.isEmpty())
        // and a recreation brings nothing back
        composeRule.activityRule.scenario.recreate()
        awaitTag("chat_input")
        assertTrue(conversations().none { it.id == asked })
        assertEquals(0, orphanRows())
    }

    @Test
    fun deletingEveryConversationMidGenerationEndsQuietly() {
        holdAGeneration()
        openHistoryScreen()
        awaitTag("chat_history_delete_all")
        composeRule.onNodeWithTag("chat_history_delete_all").performClick()
        composeRule.onNodeWithTag("chat_history_delete_all_confirm").performClick()
        composeRule.waitUntil(5_000) { conversations().isEmpty() }
        TestAiRuntime.generateGate?.complete(Unit)
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("nothing escaped: $uncaught", uncaught.isEmpty())
        assertTrue(conversations().isEmpty())
        assertEquals(0, orphanRows())
    }
}
