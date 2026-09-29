package io.github.cragcoffee.memoripple

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.ui.chat.AiPhase
import io.github.cragcoffee.memoripple.ui.chat.ChatViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The process-death case: a ChatViewModel built from a saved state stands where it stood and searches again. */
@RunWith(AndroidJUnit4::class)
class ChatViewModelStateInstrumentationTest {
    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MemoRippleApplication

    @Before
    fun startEmpty() {
        runBlocking { application.database.clearAllTables() }
    }

    // Chat UI redesign (2026-09-21): the chat no longer searches on its own — the query / kinds / preset test went with the search UI

    /** Phase 3: the mode and the AI input come back; a result, a preview or a result context never does (they are not even in the handle). */
    @Test
    fun theModeAndTheAiInputComeBackButNoResultPreviewOrContextIsEverSaved() {
        val memoId = runBlocking {
            application.database.memoDao().insert(io.github.cragcoffee.memoripple.data.MemoEntity(title = "MemoRipple開発", body = "本文", createdAt = 1, updatedAt = 1, kind = "memo"))
        }
        val handle = SavedStateHandle(mapOf(ChatViewModel.KEY_INPUT to "MemoRipple開発に追記して"))
        val preview = io.github.cragcoffee.memoripple.domain.ai.CommandPreview.Append(
            io.github.cragcoffee.memoripple.domain.documents.DocumentSummary(io.github.cragcoffee.memoripple.domain.documents.DocumentRef(DocumentKind.MEMO, memoId), "MemoRipple開発", 1, 1),
            "追記", io.github.cragcoffee.memoripple.domain.ai.DocumentVersion(1), "本文",
        )
        val ticket = io.github.cragcoffee.memoripple.domain.ai.PendingWrite(
            io.github.cragcoffee.memoripple.domain.ai.ExecutionPolicy.decide(
                io.github.cragcoffee.memoripple.domain.ai.ResolutionResult.Resolved(
                    io.github.cragcoffee.memoripple.domain.ai.ResolvedCommand.Append(preview.target, preview.text, preview.expectedVersion, preview.currentBody),
                ),
            ) as io.github.cragcoffee.memoripple.domain.ai.ExecutionDecision.RequiresConfirmation,
        )
        val orchestrator = object : io.github.cragcoffee.memoripple.domain.ai.AiOrchestrator {
            override suspend fun interact(userText: String, context: io.github.cragcoffee.memoripple.domain.ai.AiResultContext, onProgress: (io.github.cragcoffee.memoripple.domain.ai.AiProgress) -> Unit, onNote: (String) -> Unit, referents: io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationReferents, onTiming: (io.github.cragcoffee.memoripple.domain.ai.AiTiming) -> Unit, destination: io.github.cragcoffee.memoripple.domain.documents.CreateDestination?) =
                io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult.WritePreview(preview, ticket)
            override fun runtimeState() = io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState.UNLOADED
            override suspend fun availability() = io.github.cragcoffee.memoripple.domain.ai.ModelAvailability.Available(TestAiSelection.default)
                        override suspend fun runTemplate(template: io.github.cragcoffee.memoripple.domain.memos.MemoTemplate, values: Map<String, String>, destination: io.github.cragcoffee.memoripple.domain.documents.CreateDestination?): io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult = error("no template here")
                        override suspend fun previewMemo(body: String, destination: io.github.cragcoffee.memoripple.domain.documents.CreateDestination?): io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult = error("no export here")

                        override suspend fun interactFast(userText: String, context: io.github.cragcoffee.memoripple.domain.ai.AiResultContext, referents: io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationReferents, destination: io.github.cragcoffee.memoripple.domain.documents.CreateDestination?): io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult? = null
override suspend fun release() = Unit
            override suspend fun execute(pending: io.github.cragcoffee.memoripple.domain.ai.PendingWrite): io.github.cragcoffee.memoripple.domain.ai.WriteOutcome = error("never confirmed in this test")
        }
        val viewModel = ChatViewModel(handle, orchestrator)
        val restored = viewModel.uiState.value
        assertEquals("MemoRipple開発に追記して", restored.input)
        assertEquals(null, restored.ai.result)
        assertEquals(AiPhase.IDLE, restored.ai.phase)

        viewModel.ask()
        val shown = runBlocking { withTimeout(5_000) { viewModel.uiState.first { it.ai.result != null } } }
        assertEquals(preview, (shown.ai.result as io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult.WritePreview).preview)
        // the handle still holds the mode and the input, and nothing else
        assertEquals(setOf(ChatViewModel.KEY_INPUT), handle.keys().filter { handle.get<Any>(it) != null }.toSet())
        // a second view model from the same handle starts without the preview
        val again = ChatViewModel(handle, orchestrator)
        assertEquals(null, again.uiState.value.ai.result)
    }
}

/**
 * Phase 4 (docs/AI_CONFIRMED_WRITE.md): the confirmation is a view-model step — one execute per
 * ticket, an EXECUTING phase that refuses a second confirm, nothing resumed after process death.
 */
@RunWith(AndroidJUnit4::class)
class ChatViewModelConfirmedWriteInstrumentationTest {
    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    @Before
    fun startEmpty() {
        runBlocking { application.database.clearAllTables() }
    }

    private class GatedOrchestrator : io.github.cragcoffee.memoripple.domain.ai.AiOrchestrator {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var executions = 0
        var pending: io.github.cragcoffee.memoripple.domain.ai.PendingWrite? = null
        override suspend fun interact(userText: String, context: io.github.cragcoffee.memoripple.domain.ai.AiResultContext, onProgress: (io.github.cragcoffee.memoripple.domain.ai.AiProgress) -> Unit, onNote: (String) -> Unit, referents: io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationReferents, onTiming: (io.github.cragcoffee.memoripple.domain.ai.AiTiming) -> Unit, destination: io.github.cragcoffee.memoripple.domain.documents.CreateDestination?) =
            io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult.WritePreview(pending!!.preview, pending!!)
        override fun runtimeState() = io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState.UNLOADED
        override suspend fun availability() = io.github.cragcoffee.memoripple.domain.ai.ModelAvailability.Available(TestAiSelection.default)
                    override suspend fun runTemplate(template: io.github.cragcoffee.memoripple.domain.memos.MemoTemplate, values: Map<String, String>, destination: io.github.cragcoffee.memoripple.domain.documents.CreateDestination?): io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult = error("no template here")
                    override suspend fun previewMemo(body: String, destination: io.github.cragcoffee.memoripple.domain.documents.CreateDestination?): io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult = error("no export here")

                    override suspend fun interactFast(userText: String, context: io.github.cragcoffee.memoripple.domain.ai.AiResultContext, referents: io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationReferents, destination: io.github.cragcoffee.memoripple.domain.documents.CreateDestination?): io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult? = null
override suspend fun release() = Unit
        override suspend fun execute(pending: io.github.cragcoffee.memoripple.domain.ai.PendingWrite): io.github.cragcoffee.memoripple.domain.ai.WriteOutcome {
            executions++
            gate.await()
            return io.github.cragcoffee.memoripple.domain.ai.WriteOutcome.Success(io.github.cragcoffee.memoripple.domain.documents.DocumentRef(DocumentKind.MEMO, 1))
        }
    }

    private fun ticket(): io.github.cragcoffee.memoripple.domain.ai.PendingWrite {
        val decision = io.github.cragcoffee.memoripple.domain.ai.ExecutionPolicy.decide(
            io.github.cragcoffee.memoripple.domain.ai.ResolutionResult.Resolved(
                io.github.cragcoffee.memoripple.domain.ai.ResolvedCommand.Create(DocumentKind.MEMO, io.github.cragcoffee.memoripple.domain.documents.DocumentCreate.Memo(), "x"),
            ),
        ) as io.github.cragcoffee.memoripple.domain.ai.ExecutionDecision.RequiresConfirmation
        return io.github.cragcoffee.memoripple.domain.ai.PendingWrite(decision)
    }

    @Test
    fun confirmingOnceExecutesOnceTheExecutingPhaseRefusesASecondConfirmAndTheResultFollows() {
        val orchestrator = GatedOrchestrator().apply { pending = ticket() }
        val handle = SavedStateHandle(mapOf(ChatViewModel.KEY_INPUT to "新しいメモにxを作って"))
        val viewModel = ChatViewModel(handle, orchestrator)
        viewModel.ask()
        runBlocking { withTimeout(5_000) { viewModel.uiState.first { it.ai.result is io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult.WritePreview } } }
        assertEquals(0, orchestrator.executions)
        viewModel.confirmWrite()
        runBlocking { withTimeout(5_000) { viewModel.uiState.first { it.ai.phase == AiPhase.EXECUTING } } }
        viewModel.confirmWrite()
        viewModel.confirmWrite()
        // the execution runs on the main thread: drain it before counting, so the count is what happened, not when
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("one execution per ticket, however often confirm is pressed", 1, orchestrator.executions)
        // Back while executing closes nothing: the card is not dismissable mid-write
        assertEquals(false, viewModel.uiState.value.ai.isDismissable)
        orchestrator.gate.complete(Unit)
        val done = runBlocking { withTimeout(5_000) { viewModel.uiState.first { it.ai.result is io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult.Written } } }
        assertEquals(AiPhase.DONE, done.ai.phase)
        assertEquals(io.github.cragcoffee.memoripple.domain.documents.DocumentRef(DocumentKind.MEMO, 1), done.ai.pendingOpen)
        viewModel.confirmWrite()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(1, orchestrator.executions)
        // nothing about the ticket or the outcome reached the handle
        assertEquals(setOf(ChatViewModel.KEY_INPUT), handle.keys().filter { handle.get<Any>(it) != null }.toSet())
    }

    @Test
    fun aViewModelRebuiltFromTheHandleHasNoPreviewAndConfirmDoesNothing() {
        val orchestrator = GatedOrchestrator().apply { pending = ticket() }
        val handle = SavedStateHandle(mapOf(ChatViewModel.KEY_INPUT to "新しいメモにxを作って"))
        val first = ChatViewModel(handle, orchestrator)
        first.ask()
        runBlocking { withTimeout(5_000) { first.uiState.first { it.ai.result is io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult.WritePreview } } }
        // process death: a new view model from the same handle
        val again = ChatViewModel(handle, orchestrator)
        assertEquals(null, again.uiState.value.ai.result)
        again.confirmWrite()
        assertEquals(0, orchestrator.executions)
        assertEquals(0, runBlocking { application.database.memoDao().allIds().size })
    }
}
