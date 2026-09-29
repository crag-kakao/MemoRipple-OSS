package io.github.cragcoffee.memoripple.backup

import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentEntity
import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.RoomBackupSnapshot
import io.github.cragcoffee.memoripple.data.TagEntity
import io.github.cragcoffee.memoripple.data.MemoTagCrossRef
import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.MemoPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.data.DiaryPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.data.FolderEntity
import io.github.cragcoffee.memoripple.data.DiaryContentBlockEntity
import io.github.cragcoffee.memoripple.data.OutlineRowEntity
import io.github.cragcoffee.memoripple.domain.outline.OutlineRows
import io.github.cragcoffee.memoripple.data.MemoContentBlockEntity
import io.github.cragcoffee.memoripple.domain.memos.MemoBlock
import io.github.cragcoffee.memoripple.domain.memos.MemoContent
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.data.NoteChapterEntity
import io.github.cragcoffee.memoripple.data.NoteEntity
import io.github.cragcoffee.memoripple.domain.WorkCommentScope
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.settings.CommentSize
import io.github.cragcoffee.memoripple.domain.settings.PlaybackSpeed
import io.github.cragcoffee.memoripple.domain.settings.StageBackground
import io.github.cragcoffee.memoripple.domain.settings.ThemeMode
import io.github.cragcoffee.memoripple.domain.tags.TagNameNormalizer

object BackupMapper {
    fun toDocument(
        snapshot: RoomBackupSnapshot,
        settings: AppSettings,
        exportedAt: Long,
        appVersionName: String,
        appVersionCode: Long,
        templates: List<io.github.cragcoffee.memoripple.domain.memos.MemoTemplate> = emptyList(),
        templateFolders: List<io.github.cragcoffee.memoripple.domain.memos.TemplateFolder> = emptyList(),
    ): MemoRippleBackupDto = MemoRippleBackupDto(
        format = BACKUP_FORMAT_IDENTIFIER,
        formatVersion = BACKUP_FORMAT_VERSION,
        exportedAt = exportedAt,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode,
        payload = BackupPayloadDto(
            memos = snapshot.memos.map { memo ->
                MemoBackupDto(
                    memo.id,
                    memo.title,
                    memo.body,
                    memo.createdAt,
                    memo.updatedAt,
                    memo.isFavorite,
                    memo.isPinned,
                    memo.archivedAt,
                    memo.trashedAt,
                    memo.noteId,
                    memo.chapterId,
                    memo.episodeOrder,
                    kind = memo.kind,
                    folderId = memo.folderId,
                    sortIndex = memo.sortIndex,
                )
            },
            memoComments = snapshot.memoComments.map { comment ->
                MemoCommentBackupDto(
                    comment.id,
                    comment.memoId,
                    comment.text,
                    comment.createdAt,
                    comment.playbackOrder,
                    comment.appearanceColor,
                    comment.appearanceSize,
                    comment.appearanceEmphasis,
                    comment.motionSpeed,
                    comment.motionPlacement,
                    comment.motionMode,
                    comment.flowDirection,
                    comment.flowEffect,
                    linkNo = comment.linkNo,
                )
            },
            diaryEntries = snapshot.diaryEntries.map { diary ->
                DiaryEntryBackupDto(
                    diary.id,
                    diary.diaryDateEpochDay,
                    diary.body,
                    diary.state.storageId(),
                    diary.createdAt,
                    diary.updatedAt,
                    diary.finalizedAt,
                    diary.correctionStartedAt,
                    diary.lockedAt,
                )
            },
            futureDiaryComments = snapshot.futureDiaryComments.map { comment ->
                FutureDiaryCommentBackupDto(
                    comment.id,
                    comment.diaryEntryId,
                    comment.text,
                    comment.sealedAt,
                    comment.revealAt,
                    comment.deliveredAt,
                    comment.revealedAt,
                    comment.firstPresentedAt,
                    comment.appearanceColor,
                    comment.appearanceSize,
                    comment.appearanceEmphasis,
                    comment.motionMode,
                )
            },
            settings = SettingsBackupDto(
                settings.themeMode.storageId,
                settings.playbackSpeed.storageId,
                settings.commentSize.storageId,
                settings.stageBackground.storageId,
                settings.commentScope.storageId,
            ),
            notes = snapshot.notes.map { note ->
                NoteBackupDto(
                    id = note.id,
                    title = note.title,
                    subtitle = note.subtitle,
                    coverColor = note.coverColor,
                    createdAt = note.createdAt,
                    updatedAt = note.updatedAt,
                    coverBlobSha256 = note.coverBlobSha256,
                    sortIndex = note.sortIndex,
                )
            },
            noteChapters = snapshot.noteChapters.map { chapter ->
                NoteChapterBackupDto(chapter.id, chapter.noteId, chapter.title, chapter.sortOrder)
            },
            folders = snapshot.folders.map { folder ->
                FolderBackupDto(folder.id, folder.name, folder.parentFolderId, folder.createdAt, folder.updatedAt)
            },
            templates = templates.map { TemplateBackupMapping.toDto(it) },
            templateFolders = templateFolders.map { TemplateFolderBackupDto(it.id, it.name, it.order) },
            tags = snapshot.tags.map { tag ->
                TagBackupDto(tag.id, tag.name, tag.createdAt)
            },
            memoTagRelations = snapshot.memoTagRelations.map { relation ->
                MemoTagRelationBackupDto(relation.memoId, relation.tagId)
            },
            attachmentBlobs = snapshot.attachmentBlobs.map { blob ->
                AttachmentBlobBackupDto(
                    blob.sha256,
                    blob.kind,
                    blob.mimeType,
                    blob.sizeBytes,
                    blob.widthPx,
                    blob.heightPx,
                    blob.createdAt,
                )
            },
            memoPhotoAttachments = snapshot.memoPhotoAttachments.map { relation ->
                MemoPhotoAttachmentBackupDto(
                    relation.id,
                    relation.memoId,
                    relation.blobSha256,
                    relation.sortOrder,
                    relation.createdAt,
                )
            },
            memoContentBlocks = MemoContentBackup.forDocument(snapshot),
            diaryContentBlocks = DiaryContentBackup.forDocument(snapshot),
            outlineRows = OutlineRowsBackup.forDocument(snapshot),
            diaryPhotoAttachments = snapshot.diaryPhotoAttachments.map { relation ->
                DiaryPhotoAttachmentBackupDto(
                    relation.id,
                    relation.diaryEntryId,
                    relation.blobSha256,
                    relation.sortOrder,
                    relation.createdAt,
                )
            },
        ),
    )

    fun toRoomSnapshot(document: MemoRippleBackupDto): RoomBackupSnapshot =
        OutlineRowsBackup.withRows(
            document,
            DiaryContentBackup.withBlocks(document, MemoContentBackup.withBlocks(document, toBaseSnapshot(document))),
        )

    private fun toBaseSnapshot(document: MemoRippleBackupDto): RoomBackupSnapshot {
        // Before format 16 nothing wrote folders. A memo naming a folder the file does not
        // carry is read as unfiled: the words are kept, only the place is dropped.
        val folders = if (document.formatVersion >= 16) document.payload.folders else emptyList()
        val folderIds = folders.mapTo(hashSetOf(), FolderBackupDto::id)
        return RoomBackupSnapshot(
            memos = document.payload.memos.map { memo ->
                MemoEntity(
                    memo.id,
                    memo.title,
                    memo.body,
                    memo.createdAt,
                    memo.updatedAt,
                    memo.isFavorite,
                    memo.isPinned,
                    memo.archivedAt,
                    memo.trashedAt,
                    memo.noteId,
                    memo.chapterId,
                    memo.episodeOrder,
                    // Before format 15 nothing wrote a kind: every memo in such a file is a memo.
                    kind = if (document.formatVersion >= 15) memo.kind else MemoKind.MEMO.storageId,
                    folderId = memo.folderId?.takeIf { document.formatVersion >= 16 && it in folderIds },
                    // Before format 17 nothing wrote an order: every card is unplaced.
                    sortIndex = if (document.formatVersion >= 17) memo.sortIndex else 0,
                )
            },
            memoComments = document.payload.memoComments.map { comment ->
                MemoCommentEntity(
                    comment.id,
                    comment.memoId,
                    comment.text,
                    comment.createdAt,
                    comment.playbackOrder,
                    comment.colorRole,
                    comment.sizeRole,
                    comment.emphasisRole,
                    comment.speedRole,
                    comment.placementRole,
                    comment.motionMode,
                    if (document.formatVersion >= 9) comment.flowDirection else "rtl",
                    if (document.formatVersion >= 9) comment.flowEffect else "straight",
                    linkNo = if (document.formatVersion >= 14) comment.linkNo else null,
                )
            },
            diaryEntries = document.payload.diaryEntries.map { diary ->
                DiaryEntryEntity(
                    diary.id,
                    diary.diaryDateEpochDay,
                    diary.body,
                    diaryStateFromStorageId(diary.state),
                    diary.createdAt,
                    diary.updatedAt,
                    diary.finalizedAt,
                    diary.correctionStartedAt,
                    diary.lockedAt,
                )
            },
            futureDiaryComments = document.payload.futureDiaryComments.map { comment ->
                FutureDiaryCommentEntity(
                    comment.id,
                    comment.diaryEntryId,
                    comment.text,
                    comment.sealedAt,
                    comment.revealAt,
                    comment.deliveredAt,
                    comment.revealedAt,
                    comment.firstPresentedAt,
                    comment.appearanceColor,
                    comment.appearanceSize,
                    comment.appearanceEmphasis,
                    comment.motionMode,
                )
            },
            tags = document.payload.tags.map { tag ->
                TagEntity(
                    id = tag.id,
                    name = tag.name.trim(),
                    normalizedName = TagNameNormalizer.normalizeKey(tag.name),
                    createdAt = tag.createdAt,
                )
            },
            memoTagRelations = document.payload.memoTagRelations.map { relation ->
                MemoTagCrossRef(relation.memoId, relation.tagId)
            },
            attachmentBlobs = document.payload.attachmentBlobs.map { blob ->
                AttachmentBlobEntity(
                    blob.sha256,
                    blob.kind,
                    blob.mimeType,
                    blob.sizeBytes,
                    blob.widthPx,
                    blob.heightPx,
                    blob.createdAt,
                )
            },
            memoPhotoAttachments = document.payload.memoPhotoAttachments.map { relation ->
                MemoPhotoAttachmentEntity(
                    relation.id,
                    relation.memoId,
                    relation.blobSha256,
                    relation.sortOrder,
                    relation.createdAt,
                )
            },
            diaryPhotoAttachments = document.payload.diaryPhotoAttachments.map { relation ->
                DiaryPhotoAttachmentEntity(
                    relation.id,
                    relation.diaryEntryId,
                    relation.blobSha256,
                    relation.sortOrder,
                    relation.createdAt,
                )
            },
            notes = document.payload.notes.map { note ->
                NoteEntity(
                    id = note.id,
                    title = note.title,
                    subtitle = note.subtitle,
                    coverColor = note.coverColor,
                    createdAt = note.createdAt,
                    updatedAt = note.updatedAt,
                    coverBlobSha256 = note.coverBlobSha256,
                    sortIndex = if (document.formatVersion >= 17) note.sortIndex else 0,
                )
            },
            noteChapters = document.payload.noteChapters.map { chapter ->
                NoteChapterEntity(chapter.id, chapter.noteId, chapter.title, chapter.sortOrder)
            },
        ).let { snapshot ->
            snapshot.copy(
                folders = folders.map { folder ->
                    FolderEntity(folder.id, folder.name, folder.parentFolderId, folder.createdAt, folder.updatedAt)
                },
            )
        }
    }

    /** The templates a file carries — the format-18 name + body shape and the format-19 v2 shape alike; an older file simply has none. */
    fun toTemplates(document: MemoRippleBackupDto): List<io.github.cragcoffee.memoripple.domain.memos.MemoTemplate> =
        document.payload.templates.mapNotNull { TemplateBackupMapping.toDomain(it) }

    /** The template folders (2026-09-22): a file before them has none; the order is renumbered on the way in. */
    fun toTemplateFolders(document: MemoRippleBackupDto): List<io.github.cragcoffee.memoripple.domain.memos.TemplateFolder> =
        document.payload.templateFolders.sortedBy { it.order }.mapIndexed { i, f -> io.github.cragcoffee.memoripple.domain.memos.TemplateFolder(f.id, f.name, i) }

    fun toAppSettings(document: MemoRippleBackupDto): AppSettings =
        AppSettings(
            themeMode = requireNotNull(
                ThemeMode.entries.firstOrNull {
                    it.storageId == document.payload.settings.themeMode
                },
            ),
            playbackSpeed = requireNotNull(
                PlaybackSpeed.entries.firstOrNull {
                    it.storageId == document.payload.settings.playbackSpeed
                },
            ),
            commentSize = requireNotNull(
                CommentSize.entries.firstOrNull {
                    it.storageId == document.payload.settings.commentSize
                },
            ),
            stageBackground = requireNotNull(
                StageBackground.entries.firstOrNull {
                    it.storageId == document.payload.settings.stageBackground
                },
            ),
            // Added in version 11. An older backup has no value and falls back to the default,
            // which is what those versions behaved as.
            commentScope = WorkCommentScope.fromStorageId(
                document.payload.settings.commentScope,
            ),
        )

    private fun DiaryState.storageId(): String = name.lowercase()

    private fun diaryStateFromStorageId(value: String): DiaryState = when (value) {
        "draft" -> DiaryState.DRAFT
        "finalized" -> DiaryState.FINALIZED
        "correcting" -> DiaryState.CORRECTING
        "locked" -> DiaryState.LOCKED
        else -> error("Backup must be validated before mapping")
    }
}

/**
 * Template v2 ↔ the backup wire shape (format 19). Strings on the wire; an unknown word on the way
 * in yields no template here and an [BackupValidationIssue.INVALID_TEMPLATE] in the validator, so
 * nothing is guessed.
 */
object TemplateBackupMapping {
    private val actions = mapOf("create" to io.github.cragcoffee.memoripple.domain.memos.TemplateAction.CREATE, "search" to io.github.cragcoffee.memoripple.domain.memos.TemplateAction.SEARCH, "append" to io.github.cragcoffee.memoripple.domain.memos.TemplateAction.APPEND)
    private val kinds = mapOf("memo" to io.github.cragcoffee.memoripple.domain.documents.DocumentKind.MEMO, "outline" to io.github.cragcoffee.memoripple.domain.documents.DocumentKind.OUTLINE, "journal" to io.github.cragcoffee.memoripple.domain.documents.DocumentKind.JOURNAL)
    private val types = io.github.cragcoffee.memoripple.domain.memos.TemplateFieldType.entries.associateBy { it.name.lowercase() }
    private val dates = io.github.cragcoffee.memoripple.domain.memos.TemplateDateToken.entries.associateBy { it.name.lowercase() }
    private val flows = mapOf("record" to io.github.cragcoffee.memoripple.domain.memos.TemplateFlow.RECORD, "think" to io.github.cragcoffee.memoripple.domain.memos.TemplateFlow.THINK)

    fun toDto(t: io.github.cragcoffee.memoripple.domain.memos.MemoTemplate): TemplateBackupDto = TemplateBackupDto(
        id = t.id, name = t.name, body = t.body, description = t.description,
        action = t.action.name.lowercase(), documentKind = t.documentKind.name.lowercase(),
        fields = t.fields.map { TemplateFieldBackupDto(it.key, it.label, it.type.name.lowercase(), it.required, it.default, it.choices, it.question) },
        search = t.searchSpec?.let { TemplateSearchBackupDto(it.query, it.dateToken?.name?.lowercase(), it.kinds.map { k -> k.name.lowercase() }) },
        target = when (val ts = t.targetSpec) {
            null -> null
            is io.github.cragcoffee.memoripple.domain.memos.TemplateTargetSpec.Named -> TemplateTargetBackupDto("named", ts.name)
            io.github.cragcoffee.memoripple.domain.memos.TemplateTargetSpec.AskAtRun -> TemplateTargetBackupDto("ask")
        },
        targetQuestion = t.targetQuestion,
        flow = t.flow.name.lowercase(),
        folderId = t.folderId,
        createdAt = t.createdAt, updatedAt = t.updatedAt,
    )

    /** Null when a word on the wire is not one this build knows — the validator reports it; nothing is guessed. */
    fun toDomain(d: TemplateBackupDto): io.github.cragcoffee.memoripple.domain.memos.MemoTemplate? {
        val action = actions[d.action] ?: return null
        val flow = flows[d.flow] ?: return null
        val kind = kinds[d.documentKind] ?: return null
        val fields = d.fields.map { f ->
            io.github.cragcoffee.memoripple.domain.memos.TemplateField(f.key, f.label, types[f.type] ?: return null, f.required, f.default, f.choices, f.question)
        }
        val search = d.search?.let { s ->
            io.github.cragcoffee.memoripple.domain.memos.TemplateSearchSpec(
                query = s.query,
                dateToken = s.dateToken?.let { dates[it] ?: return null },
                kinds = s.kinds.map { kinds[it] ?: return null }.toSet(),
            )
        }
        val target = d.target?.let { t ->
            when (t.mode) {
                "named" -> io.github.cragcoffee.memoripple.domain.memos.TemplateTargetSpec.Named(t.name.orEmpty())
                "ask" -> io.github.cragcoffee.memoripple.domain.memos.TemplateTargetSpec.AskAtRun
                else -> return null
            }
        }
        return io.github.cragcoffee.memoripple.domain.memos.MemoTemplate(
            id = d.id, name = d.name, body = d.body, description = d.description, action = action, documentKind = kind,
            fields = fields, searchSpec = search, targetSpec = target, targetQuestion = d.targetQuestion, flow = flow, folderId = d.folderId?.takeIf { it.isNotBlank() }, createdAt = d.createdAt, updatedAt = d.updatedAt,
        )
    }
}

/**
 * A memo's blocks in the backup (format 20, docs/MEMO_CONTENT_BLOCKS.md). Written from what Room
 * holds — a memo whose blocks were never stored gets them as they are read (photos above text), so
 * a 20 file is always complete. Read back as stored for 20; for 1–19, derived the way the Room
 * migration did. The body of a memo is recomputed from its blocks, which are its source of truth.
 */
object MemoContentBackup {
    fun forDocument(snapshot: RoomBackupSnapshot): List<MemoContentBlockBackupDto> {
        val storedByMemo = snapshot.memoContentBlocks.groupBy(MemoContentBlockEntity::memoId)
        val photosByMemo = snapshot.memoPhotoAttachments.groupBy(MemoPhotoAttachmentEntity::memoId)
        var nextId = (snapshot.memoContentBlocks.maxOfOrNull(MemoContentBlockEntity::id) ?: 0L) + 1
        return snapshot.memos.filter { it.kind == MemoKind.MEMO.storageId }.flatMap { memo ->
            val stored = storedByMemo[memo.id].orEmpty().sortedWith(compareBy({ it.position }, { it.id })).map(::toBlock)
            val photos = photosByMemo[memo.id].orEmpty().sortedWith(compareBy({ it.sortOrder }, { it.id })).map { it.id }
            MemoContent.resolve(stored, memo.body, photos).mapIndexed { position, block ->
                val id = if (block.id == MemoContent.NEW) nextId++ else block.id
                when (block) {
                    is MemoBlock.Text -> MemoContentBlockBackupDto(id, memo.id, position, MemoContentBlockEntity.TYPE_TEXT, text = block.text)
                    is MemoBlock.Photo -> MemoContentBlockBackupDto(id, memo.id, position, MemoContentBlockEntity.TYPE_PHOTO, photoAttachmentId = block.attachmentId)
                }
            }
        }
    }

    fun withBlocks(document: MemoRippleBackupDto, base: RoomBackupSnapshot): RoomBackupSnapshot {
        val photosByMemo = base.memoPhotoAttachments.groupBy(MemoPhotoAttachmentEntity::memoId)
        val carried = if (document.formatVersion >= 20) {
            document.payload.memoContentBlocks.groupBy(MemoContentBlockBackupDto::memoId)
        } else {
            emptyMap()
        }
        val blocks = ArrayList<MemoContentBlockEntity>()
        val memos = base.memos.map { memo ->
            if (memo.kind != MemoKind.MEMO.storageId) return@map memo
            val own = carried[memo.id].orEmpty().sortedBy { it.position }
            val list: List<MemoBlock> = if (own.isNotEmpty()) {
                own.map { dto ->
                    if (dto.type == MemoContentBlockEntity.TYPE_PHOTO) MemoBlock.Photo(dto.id, dto.photoAttachmentId!!)
                    else MemoBlock.Text(dto.id, dto.text.orEmpty())
                }
            } else {
                // 1–19 (or a 20 memo written without blocks): the photos above the text.
                MemoContent.legacy(memo.body, photosByMemo[memo.id].orEmpty().sortedWith(compareBy({ it.sortOrder }, { it.id })).map { it.id })
            }
            list.forEachIndexed { position, block ->
                blocks += when (block) {
                    is MemoBlock.Text -> MemoContentBlockEntity(block.id, memo.id, position, MemoContentBlockEntity.TYPE_TEXT, block.text, null)
                    is MemoBlock.Photo -> MemoContentBlockEntity(block.id, memo.id, position, MemoContentBlockEntity.TYPE_PHOTO, null, block.attachmentId)
                }
            }
            if (own.isNotEmpty()) memo.copy(body = MemoContent.projection(list)) else memo
        }
        return base.copy(memos = memos, memoContentBlocks = blocks)
    }

    private fun toBlock(entity: MemoContentBlockEntity): MemoBlock =
        if (entity.type == MemoContentBlockEntity.TYPE_PHOTO && entity.photoAttachmentId != null) {
            MemoBlock.Photo(entity.id, entity.photoAttachmentId)
        } else {
            MemoBlock.Text(entity.id, entity.text.orEmpty())
        }
}

/**
 * A journal entry's blocks in the backup (format 21, docs/MEMO_CONTENT_BLOCKS.md §11) — the same
 * as [MemoContentBackup], with a journal's photos under its words. Written from what Room holds
 * (an entry whose blocks were never stored gets them as they are read), read back as stored for
 * 21, derived the way the Room migration did for 1–20. The body is recomputed from the blocks.
 */
object DiaryContentBackup {
    fun forDocument(snapshot: RoomBackupSnapshot): List<DiaryContentBlockBackupDto> {
        val storedByEntry = snapshot.diaryContentBlocks.groupBy(DiaryContentBlockEntity::diaryEntryId)
        val photosByEntry = snapshot.diaryPhotoAttachments.groupBy(DiaryPhotoAttachmentEntity::diaryEntryId)
        var nextId = (snapshot.diaryContentBlocks.maxOfOrNull(DiaryContentBlockEntity::id) ?: 0L) + 1
        return snapshot.diaryEntries.flatMap { entry ->
            val stored = storedByEntry[entry.id].orEmpty().sortedWith(compareBy({ it.position }, { it.id })).map(::toBlock)
            val photos = photosByEntry[entry.id].orEmpty().sortedWith(compareBy({ it.sortOrder }, { it.id })).map { it.id }
            MemoContent.resolve(stored, entry.body, photos, photosFirst = false).mapIndexed { position, block ->
                val id = if (block.id == MemoContent.NEW) nextId++ else block.id
                when (block) {
                    is MemoBlock.Text -> DiaryContentBlockBackupDto(id, entry.id, position, MemoContentBlockEntity.TYPE_TEXT, text = block.text)
                    is MemoBlock.Photo -> DiaryContentBlockBackupDto(id, entry.id, position, MemoContentBlockEntity.TYPE_PHOTO, photoAttachmentId = block.attachmentId)
                }
            }
        }
    }

    fun withBlocks(document: MemoRippleBackupDto, base: RoomBackupSnapshot): RoomBackupSnapshot {
        val photosByEntry = base.diaryPhotoAttachments.groupBy(DiaryPhotoAttachmentEntity::diaryEntryId)
        val carried = if (document.formatVersion >= 21) {
            document.payload.diaryContentBlocks.groupBy(DiaryContentBlockBackupDto::diaryEntryId)
        } else {
            emptyMap()
        }
        val blocks = ArrayList<DiaryContentBlockEntity>()
        var nextId = (carried.values.flatten().maxOfOrNull { it.id } ?: 0L) + 1
        val entries = base.diaryEntries.map { entry ->
            val own = carried[entry.id].orEmpty().sortedBy { it.position }
            val list: List<MemoBlock> = if (own.isNotEmpty()) {
                own.map { dto ->
                    if (dto.type == MemoContentBlockEntity.TYPE_PHOTO) MemoBlock.Photo(dto.id, dto.photoAttachmentId!!)
                    else MemoBlock.Text(dto.id, dto.text.orEmpty())
                }
            } else {
                // 1–20 (or a 21 entry written without blocks): the words, then the photos under them.
                MemoContent.legacy(
                    entry.body,
                    photosByEntry[entry.id].orEmpty().sortedWith(compareBy({ it.sortOrder }, { it.id })).map { it.id },
                    photosFirst = false,
                )
            }
            list.forEachIndexed { position, block ->
                val id = if (block.id == MemoContent.NEW) nextId++ else block.id
                blocks += when (block) {
                    is MemoBlock.Text -> DiaryContentBlockEntity(id, entry.id, position, MemoContentBlockEntity.TYPE_TEXT, block.text, null)
                    is MemoBlock.Photo -> DiaryContentBlockEntity(id, entry.id, position, MemoContentBlockEntity.TYPE_PHOTO, null, block.attachmentId)
                }
            }
            if (own.isNotEmpty()) entry.copy(body = MemoContent.projection(list)) else entry
        }
        return base.copy(diaryEntries = entries, diaryContentBlocks = blocks)
    }

    private fun toBlock(entity: DiaryContentBlockEntity): MemoBlock =
        if (entity.type == MemoContentBlockEntity.TYPE_PHOTO && entity.photoAttachmentId != null) {
            MemoBlock.Photo(entity.id, entity.photoAttachmentId)
        } else {
            MemoBlock.Text(entity.id, entity.text.orEmpty())
        }
}

/**
 * An outline's rows in the backup (format 22, docs/OUTLINE_STABLE_ROWS.md). Written from what Room
 * holds — an outline whose rows were never stored, or whose rows no longer say its body, gets them
 * as the store reads it, so a 22 file is always complete. Read back as stored for 22 (each line
 * keeps its lasting id, and the body is recomputed from the rows); for 1–21, derived line by line
 * from the body, as the Room migration did.
 */
object OutlineRowsBackup {
    fun forDocument(snapshot: RoomBackupSnapshot): List<OutlineRowBackupDto> {
        val storedByMemo = snapshot.outlineRows.groupBy(OutlineRowEntity::memoId)
        val photosByMemo = snapshot.memoPhotoAttachments.groupBy(MemoPhotoAttachmentEntity::memoId)
        return snapshot.memos.filter { it.kind == MemoKind.OUTLINE.storageId }.flatMap { memo ->
            val stored = storedByMemo[memo.id].orEmpty().sortedWith(compareBy({ it.position }, { it.rowId })).map(::toRow)
            val rows = when {
                // An outline never stored as rows: its lines, and its photos at the top.
                stored.isEmpty() -> OutlineRows.withPhotosOnTop(OutlineRows.fresh(memo.body), photosOf(photosByMemo[memo.id]))
                OutlineRows.projection(stored) != memo.body -> OutlineRows.reconcile(stored, memo.body, nextId = 1)
                else -> stored
            }
            rows.mapIndexed { position, row ->
                if (row.photo != null) {
                    OutlineRowBackupDto(memo.id, row.id, position, row.line, OutlineRowEntity.KIND_PHOTO, row.photo)
                } else {
                    OutlineRowBackupDto(memo.id, row.id, position, row.line)
                }
            }
        }
    }

    /**
     * 23: the rows as carried, photo rows included. 22: the lines as carried, and the outline's
     * photos as photo rows at the top (as the Room migration did). 1–21: the lines read from the
     * body, and the photos at the top.
     */
    fun withRows(document: MemoRippleBackupDto, base: RoomBackupSnapshot): RoomBackupSnapshot {
        val carried = if (document.formatVersion >= 22) {
            document.payload.outlineRows.groupBy(OutlineRowBackupDto::memoId)
        } else {
            emptyMap()
        }
        val photosByMemo = base.memoPhotoAttachments.groupBy(MemoPhotoAttachmentEntity::memoId)
        val rows = ArrayList<OutlineRowEntity>()
        val memos = base.memos.map { memo ->
            if (memo.kind != MemoKind.OUTLINE.storageId) return@map memo
            val own = carried[memo.id].orEmpty().sortedBy { it.position }
            val list = when {
                own.isEmpty() -> OutlineRows.withPhotosOnTop(OutlineRows.fresh(memo.body), photosOf(photosByMemo[memo.id]))
                document.formatVersion >= 23 -> own.map { dto ->
                    OutlineRows.Row(dto.rowId, dto.text, dto.photoAttachmentId.takeIf { dto.kind == OutlineRowEntity.KIND_PHOTO })
                }
                else -> OutlineRows.withPhotosOnTop(own.map { OutlineRows.Row(it.rowId, it.text) }, photosOf(photosByMemo[memo.id]))
            }
            list.forEachIndexed { position, row ->
                rows += if (row.photo != null) {
                    OutlineRowEntity(memo.id, row.id, position, OutlineRowEntity.KIND_PHOTO, row.line, row.photo)
                } else {
                    OutlineRowEntity(memo.id, row.id, position, OutlineRowEntity.KIND_TEXT, row.line, null)
                }
            }
            if (own.isNotEmpty()) memo.copy(body = OutlineRows.projection(list)) else memo
        }
        return base.copy(memos = memos, outlineRows = rows)
    }

    private fun photosOf(relations: List<MemoPhotoAttachmentEntity>?): List<Long> =
        relations.orEmpty().sortedWith(compareBy({ it.sortOrder }, { it.id })).map { it.id }

    private fun toRow(entity: OutlineRowEntity): OutlineRows.Row =
        OutlineRows.Row(entity.rowId, entity.text, entity.photoAttachmentId.takeIf { entity.kind == OutlineRowEntity.KIND_PHOTO })
}
