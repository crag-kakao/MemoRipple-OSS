package io.github.cragcoffee.memoripple.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_conversations ORDER BY updatedAt DESC, id DESC")
    fun observeConversations(): Flow<List<ChatConversationEntity>>

    @Query("SELECT * FROM chat_conversations WHERE id = :id")
    suspend fun findConversation(id: Long): ChatConversationEntity?

    @Insert
    suspend fun insertConversation(conversation: ChatConversationEntity): Long

    @Query("UPDATE chat_conversations SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("UPDATE chat_conversations SET anchorKind = :kind, anchorId = :documentId WHERE id = :id")
    suspend fun setAnchor(id: Long, kind: String?, documentId: Long?)

    @Query("DELETE FROM chat_conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("DELETE FROM chat_conversations")
    suspend fun deleteAllConversations()

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(conversationId: Long): Flow<List<ChatMessageEntity>>

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_result_refs WHERE conversationId = :conversationId ORDER BY ordinal ASC")
    suspend fun resultRefs(conversationId: Long): List<ChatResultRefEntity>

    @Query("DELETE FROM chat_result_refs WHERE conversationId = :conversationId")
    suspend fun clearResultRefs(conversationId: Long)

    @Insert
    suspend fun insertResultRefs(refs: List<ChatResultRefEntity>)
}
