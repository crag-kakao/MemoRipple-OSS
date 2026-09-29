package io.github.cragcoffee.memoripple.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Conversation history (docs/AI_CONVERSATION_HISTORY.md), Room 25. Three tables, device-local,
 * never in any backup: the conversations, their messages, and the latest shown results per
 * conversation. Only what the user saw is stored — a role, a display kind, the visible text.
 * The document anchor lives on the conversation as a kind + id pair. No foreign key points at a
 * document table: a deleted memo leaves the transcript alone and the Resolver says NotFound.
 */
@Entity(tableName = "chat_conversations")
data class ChatConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val anchorKind: String? = null,
    val anchorId: Long? = null,
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [ForeignKey(entity = ChatConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId")],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** USER or ASSISTANT — never a system prompt, never a raw answer. */
    val role: String,
    val kind: String,
    val content: String,
    val createdAt: Long,
)

/** The latest shown results of a conversation, by position — the app rebuilds the numbered refs from these for one ask. */
@Entity(
    tableName = "chat_result_refs",
    primaryKeys = ["conversationId", "ordinal"],
    foreignKeys = [ForeignKey(entity = ChatConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
)
data class ChatResultRefEntity(
    val conversationId: Long,
    val ordinal: Int,
    val documentKind: String,
    val documentId: Long,
    val title: String,
)
