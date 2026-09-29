package io.github.cragcoffee.memoripple.domain.ai.conversation

import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import kotlinx.coroutines.flow.Flow

/**
 * Conversation history (docs/AI_CONVERSATION_HISTORY.md) has three layers, kept apart on purpose:
 *
 * 1. the **persistent transcript** the user reads — [ChatConversation] and [ChatMessage], only
 *    what the user typed and what the screen showed, never a raw answer or an internal state;
 * 2. the **persistent safe context** the app resolves referents with — [SafeConversationContext]:
 *    the latest shown results by position and the last document anchor, as real references the
 *    model never sees and the Resolver always re-validates;
 * 3. the **bounded active context** the model sees for one ask — [ConversationWindow].
 *
 * Nothing in any layer can execute anything: the pipeline, the preview and the Human
 * Confirmation stand unchanged.
 */
enum class ChatRole { USER, ASSISTANT }

/** How a line is drawn, never what it may do. */
enum class ChatMessageKind { TEXT, RESULT, WRITE_EVENT, FAILURE }

data class ChatConversation(val id: Long, val title: String, val createdAt: Long, val updatedAt: Long)

data class ChatMessage(
    val id: Long,
    val conversationId: Long,
    val role: ChatRole,
    val kind: ChatMessageKind,
    val text: String,
    val createdAt: Long,
)

/**
 * The safe context of one conversation: the latest results as they were shown (position, kind,
 * id and the title the user saw) and the last document the user opened, chose or confirmed a
 * write on. Rebuilt into a request-scoped [AiResultContext] for every ask; never a `result_N`
 * label on disk, never shared across conversations.
 */
data class SafeConversationContext(
    val latestResults: List<DocumentSummary> = emptyList(),
    val lastDocumentAnchor: DocumentRef? = null,
) {
    fun resultContext(): AiResultContext = AiResultContext.of(latestResults)

    companion object {
        val EMPTY = SafeConversationContext()
    }
}

/** The history's door for the screen: transcripts and safe context, per conversation, on this device only. */
interface ConversationStore {
    /** Newest activity first. */
    fun conversations(): Flow<List<ChatConversation>>
    suspend fun conversation(id: Long): ChatConversation?
    suspend fun create(title: String): Long
    fun messages(conversationId: Long): Flow<List<ChatMessage>>
    /**
     * Appends one line the user saw (or typed) and marks the conversation as touched — only while
     * the conversation still exists, checked and written in one transaction (2026-09-26): a reply
     * that arrives after its conversation was deleted is written nowhere, brings nothing back and
     * throws nothing. Null when the conversation is gone.
     */
    suspend fun append(conversationId: Long, role: ChatRole, kind: ChatMessageKind, text: String): Long?
    /** Removes the conversation, its messages and its safe context — nothing else. */
    suspend fun delete(conversationId: Long)
    suspend fun deleteAll()
    suspend fun context(conversationId: Long): SafeConversationContext
    /** The latest shown results — kept only while the conversation exists (the same one-transaction check as [append]). */
    suspend fun setLatestResults(conversationId: Long, results: List<DocumentSummary>)
    suspend fun setAnchor(conversationId: Long, ref: DocumentRef?)
}

/** Which conversation the chat shows now — one light preference, restored after a restart; a deleted id means none. */
interface LastConversationStore {
    val lastConversationId: Flow<Long?>
    suspend fun setLastConversationId(id: Long?)
}

/**
 * The one-line hint above the input that says free text needs a Local AI model (UI/UX review
 * 2026-09-23). The user may close it with its ×, and a closed hint is **closed for good** — the
 * store can say so and can never be told otherwise, so the line cannot come back by itself.
 * Nothing is lost by closing it: the model name in the top bar still says 「AIモデルなし」 and leads
 * to the models, and a free-text send with no model still answers with the setup card.
 * A light device preference: a flag, never content, never in a backup.
 */
interface ChatHintStore {
    val aiHintDismissed: Flow<Boolean>
    suspend fun dismissAiHint()
}

/** The history drawer's pins (2026-09-21): ids only, a light preference; never content, never in a backup. */
interface PinnedConversationStore {
    val pinnedIds: Flow<Set<Long>>
    suspend fun setPinned(id: Long, pinned: Boolean)
}
