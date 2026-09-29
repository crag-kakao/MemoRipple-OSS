package io.github.cragcoffee.memoripple.domain.ai.conversation

import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import kotlinx.coroutines.flow.Flow

/**
 * 「メモを選択」 (docs/CHAT_MEMO_CONTEXT.md, human decisions 2026-09-26): one existing memo the chat
 * may be pointed at — what the picker shows and what the chip names. It is a **context**, never a
 * write authority: it only stands where the conversation's anchor would stand (OPEN, APPEND and a
 * target-less APPEND read it through the unchanged pipeline — preview, Human Confirmation, the
 * version check). Nothing here is ever sent to a model.
 */
data class MemoChoice(
    /** Always a memo (kind MEMO); the picker offers nothing else. */
    val ref: DocumentRef,
    val title: String,
    /** One line of the body, for the picker only. */
    val preview: String,
    val updatedAt: Long,
    val folderId: Long?,
)

/**
 * The read-only port the chat lists and re-checks memos through — so `ui/chat` never touches a
 * DAO, an entity or Room. Active memos only: no outline, journal, note episode, archive or trash.
 */
interface DocumentMemoChoices {
    /** Newest first, at most [LIMIT]; [query] narrows by title or body, [folderId] by the wall's folder (null = every folder). */
    fun choices(query: String, folderId: Long?): Flow<List<MemoChoice>>

    /** The memo as it is now — or null once it is no longer one the chat may point at (deleted, archived, trashed, not a memo). */
    fun observe(id: Long): Flow<MemoChoice?>

    companion object {
        const val LIMIT = 200
    }
}

/**
 * The selected memo of each conversation: conversation id → memo id, ids only, one device
 * preference (no Room, no backup). A new chat holds its choice on screen until its first message
 * creates the conversation, which then carries it.
 */
interface ChatMemoSelectionStore {
    val selections: Flow<Map<Long, Long>>

    /** Binds (or with null, clears) the conversation's selected memo. */
    suspend fun set(conversationId: Long, memoId: Long?)

    /** Every conversation is gone: every selection goes with them. */
    suspend fun clear()
}

/** The preference's wire form: `"<conversationId>:<memoId>"` per entry; anything else is skipped. */
object ChatMemoSelectionCodec {
    private val ENTRY = Regex("""^(\d{1,18}):(\d{1,18})$""")

    fun decode(entries: Set<String>): Map<Long, Long> = entries.mapNotNull { entry ->
        val m = ENTRY.matchEntire(entry) ?: return@mapNotNull null
        val conversation = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val memo = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        conversation to memo
    }.toMap()

    fun encode(selections: Map<Long, Long>): Set<String> = selections.map { (conversation, memo) -> "$conversation:$memo" }.toSet()
}

/** The picker's one line of a memo: the first line with words, trimmed and cut short. */
object MemoChoicePreview {
    const val MAX = 80

    fun of(body: String): String = body.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)?.take(MAX).orEmpty()
}
