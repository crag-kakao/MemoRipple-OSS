package io.github.cragcoffee.memoripple.data

import androidx.room.withTransaction
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatConversation
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessage
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessageKind
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationStore
import io.github.cragcoffee.memoripple.domain.ai.conversation.LastConversationStore
import io.github.cragcoffee.memoripple.domain.ai.conversation.SafeConversationContext
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The conversation history on Room (docs/AI_CONVERSATION_HISTORY.md): transcripts and the safe
 * context, per conversation, on this device only. Nothing here logs, sends or backs anything up;
 * nothing here touches a document table.
 */
class ChatHistoryRepository(
    private val database: AppDatabase,
    private val dao: ChatDao,
    private val timeProvider: TimeProvider,
    /** 「メモを選択」 (docs/CHAT_MEMO_CONTEXT.md): a conversation's selected memo goes with the conversation, whichever screen deletes it. */
    private val memoSelections: io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMemoSelectionStore? = null,
) : ConversationStore {
    override fun conversations(): Flow<List<ChatConversation>> = dao.observeConversations().map { list -> list.map { it.toDomain() } }

    override suspend fun conversation(id: Long): ChatConversation? = dao.findConversation(id)?.toDomain()

    override suspend fun create(title: String): Long {
        val now = timeProvider.nowMillis()
        return dao.insertConversation(ChatConversationEntity(title = title, createdAt = now, updatedAt = now))
    }

    override fun messages(conversationId: Long): Flow<List<ChatMessage>> =
        dao.observeMessages(conversationId).map { list -> list.map { it.toDomain() } }

    override suspend fun append(conversationId: Long, role: ChatRole, kind: ChatMessageKind, text: String): Long? {
        val now = timeProvider.nowMillis()
        return database.withTransaction {
            // the conversation may have been deleted while this line was on its way (a late reply): then nothing is written —
            // Room runs one write transaction at a time, so a delete cannot land between this check and the insert
            if (dao.findConversation(conversationId) == null) return@withTransaction null
            val id = dao.insertMessage(ChatMessageEntity(conversationId = conversationId, role = role.name, kind = kind.name, content = text, createdAt = now))
            dao.touch(conversationId, now)
            id
        }
    }

    override suspend fun delete(conversationId: Long) {
        dao.deleteConversation(conversationId)
        memoSelections?.set(conversationId, null)
    }

    override suspend fun deleteAll() {
        dao.deleteAllConversations()
        memoSelections?.clear()
    }

    override suspend fun context(conversationId: Long): SafeConversationContext {
        val conversation = dao.findConversation(conversationId) ?: return SafeConversationContext.EMPTY
        val results = dao.resultRefs(conversationId).mapNotNull { row ->
            kindOf(row.documentKind)?.let { kind -> DocumentSummary(DocumentRef(kind, row.documentId), row.title, 0, 0) }
        }
        val anchor = conversation.anchorKind?.let(::kindOf)?.let { kind -> conversation.anchorId?.let { DocumentRef(kind, it) } }
        return SafeConversationContext(results, anchor)
    }

    override suspend fun setLatestResults(conversationId: Long, results: List<DocumentSummary>) {
        database.withTransaction {
            if (dao.findConversation(conversationId) == null) return@withTransaction   // gone: nothing to keep them for
            dao.clearResultRefs(conversationId)
            dao.insertResultRefs(results.take(99).mapIndexed { i, s -> ChatResultRefEntity(conversationId, i + 1, s.ref.kind.name, s.ref.id, s.title) })
        }
    }

    override suspend fun setAnchor(conversationId: Long, ref: DocumentRef?) = dao.setAnchor(conversationId, ref?.kind?.name, ref?.id)

    private fun kindOf(name: String): DocumentKind? = DocumentKind.entries.firstOrNull { it.name == name }

    private fun ChatConversationEntity.toDomain() = ChatConversation(id, title, createdAt, updatedAt)

    private fun ChatMessageEntity.toDomain() = ChatMessage(
        id, conversationId,
        ChatRole.entries.firstOrNull { it.name == role } ?: ChatRole.ASSISTANT,
        ChatMessageKind.entries.firstOrNull { it.name == kind } ?: ChatMessageKind.TEXT,
        content, createdAt,
    )
}

/** The current conversation id in the app's preferences — a light setting, never content. */
class DataStoreLastConversationStore(private val settings: SettingsRepository) : LastConversationStore {
    override val lastConversationId: Flow<Long?> get() = settings.lastChatConversationId
    override suspend fun setLastConversationId(id: Long?) = settings.setLastChatConversationId(id)
}

/**
 * The chat's closed hint in the app's preferences (UI/UX review 2026-09-23) — a flag, never
 * content. It has one write, and that write only closes the hint.
 */
class DataStoreChatHintStore(private val settings: SettingsRepository) : io.github.cragcoffee.memoripple.domain.ai.conversation.ChatHintStore {
    override val aiHintDismissed: Flow<Boolean> get() = settings.chatAiHintDismissed
    override suspend fun dismissAiHint() = settings.dismissChatAiHint()
}

/** The drawer's pinned conversation ids in the app's preferences — a light setting, never content. */
class DataStorePinnedConversationStore(private val settings: SettingsRepository) : io.github.cragcoffee.memoripple.domain.ai.conversation.PinnedConversationStore {
    override val pinnedIds: Flow<Set<Long>> get() = settings.pinnedChatConversationIds
    override suspend fun setPinned(id: Long, pinned: Boolean) = settings.setChatConversationPinned(id, pinned)
}

/** The ＋ picker's pinned template ids in the app's preferences (Review Batch 2, 2026-09-22) — ids in pin order, never a template. */
class DataStorePinnedTemplateStore(private val settings: SettingsRepository) : io.github.cragcoffee.memoripple.domain.memos.PinnedTemplateStore {
    override val pinnedIds: Flow<List<String>> get() = settings.pinnedTemplateIds
    override suspend fun setPinned(id: String, pinned: Boolean) = settings.setTemplatePinned(id, pinned)
}

/** The chat's chosen folder in the app's preferences (2026-09-22): the wall's folder id, or none. */
class DataStoreChatDestinationStore(private val settings: SettingsRepository) : io.github.cragcoffee.memoripple.domain.folders.ChatDestinationStore {
    override val folderId: Flow<Long?> get() = settings.chatCreateFolderId
    override suspend fun set(folderId: Long?) = settings.setChatCreateFolderId(folderId)
}

/** The wall's folders as the chat's choices (2026-09-22): the tree flattened by the one walk, over the folder repository. */
class RepositoryFolderChoices(private val folders: FolderRepository) : io.github.cragcoffee.memoripple.domain.folders.DocumentFolderChoices {
    override val choices: Flow<List<io.github.cragcoffee.memoripple.domain.folders.FolderChoice>> get() =
        folders.observeFolders().map { list -> io.github.cragcoffee.memoripple.domain.folders.FolderChoices.of(list.map { it.toNode() }) }

    /** 「＋ 新しいフォルダ」 from the chat's menu: the wall's own create, at the root (2026-09-22). */
    override suspend fun create(name: String): Long? = (folders.create(name, null) as? FolderResult.Done)?.id
}

/**
 * The chat home's slots in the app's preferences (2026-09-23) — template ids and the names the
 * user gave them, in their order; never a template, never in a backup.
 */
class DataStoreHomeShortcutStore(private val settings: SettingsRepository) : io.github.cragcoffee.memoripple.domain.memos.HomeShortcutStore {
    override val shortcuts: Flow<List<io.github.cragcoffee.memoripple.domain.memos.HomeShortcutEntry>> get() = settings.homeShortcuts
    override suspend fun add(templateId: String) = settings.addHomeShortcut(templateId)
    override suspend fun remove(templateId: String) = settings.removeHomeShortcut(templateId)
    override suspend fun rename(templateId: String, label: String) = settings.renameHomeShortcut(templateId, label)
}

/** Removing one of the user's own templates, over the template store (2026-09-23). */
class RepositoryTemplateRemover(private val repository: TemplateRepository) : io.github.cragcoffee.memoripple.domain.memos.TemplateRemover {
    override suspend fun remove(id: String) = repository.delete(id)
}

/**
 * 「メモを選択」's memos (docs/CHAT_MEMO_CONTEXT.md, 2026-09-26): the wall's own query of active
 * memos — kind memo, no archive, no trash, no note episode — newest first, at most
 * [io.github.cragcoffee.memoripple.domain.ai.conversation.DocumentMemoChoices.LIMIT]. Read-only;
 * the chat sees [io.github.cragcoffee.memoripple.domain.ai.conversation.MemoChoice]s, never an entity.
 */
class RepositoryMemoChoices(private val memos: MemoRepository) : io.github.cragcoffee.memoripple.domain.ai.conversation.DocumentMemoChoices {
    override fun choices(query: String, folderId: Long?): Flow<List<io.github.cragcoffee.memoripple.domain.ai.conversation.MemoChoice>> =
        memos.observeStandaloneMemos(query).map { list ->
            list.asSequence()
                .filter { folderId == null || it.folderId == folderId }
                .take(io.github.cragcoffee.memoripple.domain.ai.conversation.DocumentMemoChoices.LIMIT)
                .map { it.toMemoChoice() }
                .toList()
        }

    /** The same rule as the list, for one memo: a memo the chat may point at, or null (gone, archived, trashed, an outline, an episode). */
    override fun observe(id: Long): Flow<io.github.cragcoffee.memoripple.domain.ai.conversation.MemoChoice?> =
        memos.observeMemo(id).map { memo ->
            memo?.takeIf {
                it.kind == io.github.cragcoffee.memoripple.domain.memos.MemoKind.MEMO.storageId &&
                    it.archivedAt == null && it.trashedAt == null && it.noteId == null
            }?.toMemoChoice()
        }.distinctUntilChanged()

    private fun MemoEntity.toMemoChoice() = io.github.cragcoffee.memoripple.domain.ai.conversation.MemoChoice(
        ref = DocumentRef(DocumentKind.MEMO, id),
        title = title,
        preview = io.github.cragcoffee.memoripple.domain.ai.conversation.MemoChoicePreview.of(body),
        updatedAt = updatedAt,
        folderId = folderId,
    )

}

/** Each conversation's selected memo in the app's preferences (2026-09-26) — ids only, never in a backup. */
class DataStoreChatMemoSelectionStore(private val settings: SettingsRepository) : io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMemoSelectionStore {
    override val selections: Flow<Map<Long, Long>> get() =
        settings.chatSelectedMemoEntries.map { io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMemoSelectionCodec.decode(it) }

    override suspend fun set(conversationId: Long, memoId: Long?) = settings.editChatSelectedMemoEntries { entries ->
        val codec = io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMemoSelectionCodec
        val next = codec.decode(entries) - conversationId
        codec.encode(if (memoId == null) next else next + (conversationId to memoId))
    }

    override suspend fun clear() = settings.editChatSelectedMemoEntries { emptySet() }
}
