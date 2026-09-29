package io.github.cragcoffee.memoripple.backup

import io.github.cragcoffee.memoripple.domain.settings.CommentSize
import io.github.cragcoffee.memoripple.domain.settings.PlaybackSpeed
import io.github.cragcoffee.memoripple.domain.settings.StageBackground
import io.github.cragcoffee.memoripple.domain.settings.ThemeMode
import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import io.github.cragcoffee.memoripple.domain.comments.CommentEmphasisRole
import io.github.cragcoffee.memoripple.domain.comments.CommentSizeRole
import io.github.cragcoffee.memoripple.domain.comments.CommentPlacementRole
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.comments.CommentSpeedRole
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowDirection
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowEffect
import io.github.cragcoffee.memoripple.domain.folders.FolderNode
import io.github.cragcoffee.memoripple.domain.folders.FolderTree
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.tags.TagNameNormalizer
import io.github.cragcoffee.memoripple.domain.tags.TagNameValidation
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentKind
import io.github.cragcoffee.memoripple.data.AttachmentLimits

enum class BackupValidationIssue {
    WRONG_FORMAT,
    UNSUPPORTED_VERSION,
    NON_POSITIVE_ID,
    DUPLICATE_MEMO_ID,
    DUPLICATE_MEMO_COMMENT_ID,
    DUPLICATE_DIARY_ID,
    DUPLICATE_FUTURE_COMMENT_ID,
    ORPHAN_MEMO_COMMENT,
    INVALID_PLAYBACK_ORDER,
    INVALID_COMMENT_LINK,
    DUPLICATE_DIARY_DATE,
    INVALID_DIARY_STATE,
    INVALID_TEMPLATE,
    EMPTY_FUTURE_COMMENT,
    ORPHAN_FUTURE_COMMENT,
    REVEALED_WITHOUT_DELIVERED,
    PRESENTED_WITHOUT_REVEALED,
    INVALID_SETTINGS,
    DUPLICATE_TAG_ID,
    INVALID_TAG_NAME,
    DUPLICATE_NORMALIZED_TAG_NAME,
    DUPLICATE_MEMO_TAG_RELATION,
    DUPLICATE_NOTE_ID,
    DUPLICATE_NOTE_CHAPTER_ID,
    ORPHAN_NOTE_CHAPTER,
    ORPHAN_EPISODE,
    ORPHAN_MEMO_TAG_RELATION,
    INVALID_MEMO_LIFECYCLE_TIMESTAMP,
    INVALID_MEMO_KIND,
    DUPLICATE_FOLDER_ID,
    ORPHAN_FOLDER_PARENT,
    FOLDER_CYCLE,
    INVALID_COMMENT_APPEARANCE,
    INVALID_COMMENT_MOTION,
    INVALID_FUTURE_COMMENT_EXPRESSION,
    INVALID_ATTACHMENT_BLOB,
    DUPLICATE_ATTACHMENT_BLOB,
    DUPLICATE_PHOTO_ATTACHMENT_ID,
    DUPLICATE_PHOTO_ATTACHMENT,
    ORPHAN_PHOTO_ATTACHMENT,
    INVALID_PHOTO_ORDER,
    ATTACHMENT_IN_LEGACY_FORMAT,
    /** Format 20: a memo's text and photo blocks that do not describe it (docs/MEMO_CONTENT_BLOCKS.md). */
    INVALID_CONTENT_BLOCKS,
    INVALID_OUTLINE_ROWS,
}

sealed interface BackupValidationResult {
    data object Valid : BackupValidationResult
    data class Invalid(val issues: Set<BackupValidationIssue>) : BackupValidationResult
}

class BackupValidator {
    fun validate(document: MemoRippleBackupDto): BackupValidationResult {
        val issues = linkedSetOf<BackupValidationIssue>()
        if (document.format != BACKUP_FORMAT_IDENTIFIER) {
            issues += BackupValidationIssue.WRONG_FORMAT
        }
        if (document.formatVersion !in
            MIN_SUPPORTED_BACKUP_FORMAT_VERSION..BACKUP_FORMAT_VERSION
        ) {
            issues += BackupValidationIssue.UNSUPPORTED_VERSION
        }

        val payload = document.payload
        validateIds(payload, issues)
        if (payload.memos.any { memo ->
                memo.archivedAt?.let { it < 0L } == true ||
                    memo.trashedAt?.let { it < 0L } == true
            }
        ) {
            issues += BackupValidationIssue.INVALID_MEMO_LIFECYCLE_TIMESTAMP
        }
        // A kind is only judged where the file could have written one; the mapper reads every
        // older memo as a memo regardless, so an older file is not refused over the field.
        if (document.formatVersion >= 15 &&
            payload.memos.any { MemoKind.fromStorageId(it.kind) == null }
        ) {
            issues += BackupValidationIssue.INVALID_MEMO_KIND
        }
        validateMemoComments(document.formatVersion, payload, issues)
        validateDiaries(document.formatVersion, payload, issues)
        validateTemplates(payload, issues)
        validateFutureComments(payload, issues)
        validateTags(payload, issues)
        validateNotes(payload, issues)
        validateFolders(document.formatVersion, payload, issues)
        validateSettings(payload.settings, issues)
        validateAttachments(document.formatVersion, payload, issues)
        if (document.formatVersion >= 20 && !MemoContentBlockRules.valid(payload)) {
            issues += BackupValidationIssue.INVALID_CONTENT_BLOCKS
        }
        if (document.formatVersion >= 21 && !DiaryContentBlockRules.valid(payload)) {
            issues += BackupValidationIssue.INVALID_CONTENT_BLOCKS
        }
        if (document.formatVersion >= 22 && !OutlineRowRules.valid(payload)) {
            issues += BackupValidationIssue.INVALID_OUTLINE_ROWS
        }

        return if (issues.isEmpty()) BackupValidationResult.Valid
        else BackupValidationResult.Invalid(issues)
    }

    private fun validateIds(
        payload: BackupPayloadDto,
        issues: MutableSet<BackupValidationIssue>,
    ) {
        fun duplicate(values: List<Long>) = values.size != values.toSet().size
        val memoIds = payload.memos.map(MemoBackupDto::id)
        val commentIds = payload.memoComments.map(MemoCommentBackupDto::id)
        val diaryIds = payload.diaryEntries.map(DiaryEntryBackupDto::id)
        val futureIds = payload.futureDiaryComments.map(FutureDiaryCommentBackupDto::id)
        val tagIds = payload.tags.map(TagBackupDto::id)
        if (duplicate(memoIds)) issues += BackupValidationIssue.DUPLICATE_MEMO_ID
        if (duplicate(commentIds)) issues += BackupValidationIssue.DUPLICATE_MEMO_COMMENT_ID
        if (duplicate(diaryIds)) issues += BackupValidationIssue.DUPLICATE_DIARY_ID
        if (duplicate(futureIds)) issues += BackupValidationIssue.DUPLICATE_FUTURE_COMMENT_ID
        if (duplicate(tagIds)) issues += BackupValidationIssue.DUPLICATE_TAG_ID
        if ((memoIds + commentIds + diaryIds + futureIds + tagIds).any { it <= 0L }) {
            issues += BackupValidationIssue.NON_POSITIVE_ID
        }
    }

    private fun validateTags(
        payload: BackupPayloadDto,
        issues: MutableSet<BackupValidationIssue>,
    ) {
        val normalizedNames = mutableListOf<String>()
        payload.tags.forEach { tag ->
            when (val validation = TagNameNormalizer.validate(tag.name)) {
                is TagNameValidation.Valid -> normalizedNames += validation.value.normalizedName
                is TagNameValidation.Invalid -> issues += BackupValidationIssue.INVALID_TAG_NAME
            }
        }
        if (normalizedNames.size != normalizedNames.toSet().size) {
            issues += BackupValidationIssue.DUPLICATE_NORMALIZED_TAG_NAME
        }
        val pairs = payload.memoTagRelations.map { it.memoId to it.tagId }
        if (pairs.size != pairs.toSet().size) {
            issues += BackupValidationIssue.DUPLICATE_MEMO_TAG_RELATION
        }
        val memoIds = payload.memos.mapTo(hashSetOf(), MemoBackupDto::id)
        val tagIds = payload.tags.mapTo(hashSetOf(), TagBackupDto::id)
        if (payload.memoTagRelations.any { it.memoId !in memoIds || it.tagId !in tagIds }) {
            issues += BackupValidationIssue.ORPHAN_MEMO_TAG_RELATION
        }
    }

    /**
     * A note only says which memos belong together, so what has to hold is that every reference
     * points at something: a chapter at its note, and an episode at a note that is in the file.
     */
    private fun validateNotes(
        payload: BackupPayloadDto,
        issues: MutableSet<BackupValidationIssue>,
    ) {
        val noteIds = payload.notes.mapTo(hashSetOf(), NoteBackupDto::id)
        val chapterIds = payload.noteChapters.mapTo(hashSetOf(), NoteChapterBackupDto::id)
        if (noteIds.size != payload.notes.size) issues += BackupValidationIssue.DUPLICATE_NOTE_ID
        if (chapterIds.size != payload.noteChapters.size) {
            issues += BackupValidationIssue.DUPLICATE_NOTE_CHAPTER_ID
        }
        if ((payload.notes.map(NoteBackupDto::id) +
                payload.noteChapters.map(NoteChapterBackupDto::id)).any { it <= 0L }
        ) {
            issues += BackupValidationIssue.NON_POSITIVE_ID
        }
        if (payload.noteChapters.any { it.noteId !in noteIds }) {
            issues += BackupValidationIssue.ORPHAN_NOTE_CHAPTER
        }
        if (payload.memos.any { memo ->
                (memo.noteId != null && memo.noteId !in noteIds) ||
                    (memo.chapterId != null && memo.chapterId !in chapterIds)
            }
        ) {
            issues += BackupValidationIssue.ORPHAN_EPISODE
        }
    }

    /**
     * The folder tree must be one sound tree — unique ids, present parents, no cycle — or the
     * file is refused: a broken tree has no safe reading. A memo pointing at a folder the file
     * lacks is *not* an issue: the mapper reads it as unfiled, and the words are what matter.
     * Files from before 16 never wrote folders, so theirs are not judged.
     */
    private fun validateFolders(
        formatVersion: Int,
        payload: BackupPayloadDto,
        issues: MutableSet<BackupValidationIssue>,
    ) {
        if (formatVersion < 16 || payload.folders.isEmpty()) return
        val ids = payload.folders.map(FolderBackupDto::id)
        if (ids.any { it <= 0L }) issues += BackupValidationIssue.NON_POSITIVE_ID
        if (ids.toSet().size != ids.size) issues += BackupValidationIssue.DUPLICATE_FOLDER_ID
        val known = ids.toHashSet()
        val nodes = payload.folders.map { FolderNode(it.id, it.parentFolderId, it.name) }
        if (payload.folders.any { it.parentFolderId != null && it.parentFolderId !in known }) {
            issues += BackupValidationIssue.ORPHAN_FOLDER_PARENT
        } else if (ids.toSet().size == ids.size && !FolderTree.isWellFormed(nodes)) {
            issues += BackupValidationIssue.FOLDER_CYCLE
        }
    }

    private fun validateMemoComments(
        formatVersion: Int,
        payload: BackupPayloadDto,
        issues: MutableSet<BackupValidationIssue>,
    ) {
        val memoIds = payload.memos.mapTo(hashSetOf(), MemoBackupDto::id)
        if (payload.memoComments.any { it.memoId !in memoIds }) {
            issues += BackupValidationIssue.ORPHAN_MEMO_COMMENT
        }
        if (payload.memoComments.any { comment ->
                !CommentColorRole.isKnownStorageId(comment.colorRole) ||
                    !CommentSizeRole.isKnownStorageId(comment.sizeRole) ||
                    !CommentEmphasisRole.isKnownStorageId(comment.emphasisRole)
            }
        ) {
            issues += BackupValidationIssue.INVALID_COMMENT_APPEARANCE
        }
        if (payload.memoComments.any { comment ->
                !CommentMotionMode.isKnownStorageId(comment.motionMode) ||
                    !CommentSpeedRole.isKnownStorageId(comment.speedRole) ||
                    !CommentPlacementRole.isKnownStorageId(comment.placementRole) ||
                    (formatVersion >= 9 &&
                        (!CommentFlowDirection.isKnownStorageId(comment.flowDirection) ||
                            !CommentFlowEffect.isKnownStorageId(comment.flowEffect)))
            }
        ) {
            issues += BackupValidationIssue.INVALID_COMMENT_MOTION
        }
        payload.memoComments.groupBy(MemoCommentBackupDto::memoId).values.forEach { comments ->
            val actual = comments.map(MemoCommentBackupDto::playbackOrder).sorted()
            val expected = comments.indices.toList()
            if (actual != expected) issues += BackupValidationIssue.INVALID_PLAYBACK_ORDER
        }
        // A link number, when carried, is a positive memo-scoped number; gaps are fine,
        // duplicates within one memo are not — two comments cannot both be R1.
        if (payload.memoComments.any { (it.linkNo ?: 1) < 1 }) {
            issues += BackupValidationIssue.INVALID_COMMENT_LINK
        }
        payload.memoComments.groupBy(MemoCommentBackupDto::memoId).values.forEach { comments ->
            val numbers = comments.mapNotNull(MemoCommentBackupDto::linkNo)
            if (numbers.size != numbers.toSet().size) {
                issues += BackupValidationIssue.INVALID_COMMENT_LINK
            }
        }
    }

    private fun validateDiaries(
        formatVersion: Int,
        payload: BackupPayloadDto,
        issues: MutableSet<BackupValidationIssue>,
    ) {
        // Up to format 17 a day held one entry and a file saying otherwise was not one of ours;
        // from 18 (journal entries, several a day — HANDOFF §16.18) the date is only a grouping.
        val dates = payload.diaryEntries.map(DiaryEntryBackupDto::diaryDateEpochDay)
        if (formatVersion <= 17 && dates.size != dates.toSet().size) {
            issues += BackupValidationIssue.DUPLICATE_DIARY_DATE
        }
        if (payload.diaryEntries.any { it.state !in DIARY_STATES }) {
            issues += BackupValidationIssue.INVALID_DIARY_STATE
        }
    }

    /**
     * A template is judged by its definition (format 19: the same [TemplateValidation] the editor,
     * the import and the run use — a legacy name + body is a CREATE memo template and must still
     * have both), under an id no other template uses; an unknown word on the wire is invalid.
     */
    private fun validateTemplates(
        payload: BackupPayloadDto,
        issues: MutableSet<BackupValidationIssue>,
    ) {
        val ids = payload.templates.map(TemplateBackupDto::id)
        if (ids.size != ids.toSet().size ||
            payload.templates.size > io.github.cragcoffee.memoripple.domain.memos.MemoTemplatePolicy.MAX_TEMPLATES ||
            payload.templates.any { dto ->
                val template = TemplateBackupMapping.toDomain(dto)
                template == null || io.github.cragcoffee.memoripple.domain.memos.TemplateValidation.problems(template).isNotEmpty()
            }
        ) {
            issues += BackupValidationIssue.INVALID_TEMPLATE
        }
        // template folders (2026-09-22): unique ids and a name each; a template whose folder id names no
        // folder is not an error — it reads as 未分類
        val folderIds = payload.templateFolders.map(TemplateFolderBackupDto::id)
        if (folderIds.size != folderIds.toSet().size ||
            payload.templateFolders.size > io.github.cragcoffee.memoripple.domain.memos.TemplateFolderPolicy.MAX_FOLDERS ||
            payload.templateFolders.any { it.id.isBlank() || it.name.isBlank() }
        ) {
            issues += BackupValidationIssue.INVALID_TEMPLATE
        }
    }

    private fun validateFutureComments(
        payload: BackupPayloadDto,
        issues: MutableSet<BackupValidationIssue>,
    ) {
        val diaryIds = payload.diaryEntries.mapTo(hashSetOf(), DiaryEntryBackupDto::id)
        payload.futureDiaryComments.forEach { comment ->
            if (comment.text.isBlank()) issues += BackupValidationIssue.EMPTY_FUTURE_COMMENT
            if (comment.diaryEntryId !in diaryIds) {
                issues += BackupValidationIssue.ORPHAN_FUTURE_COMMENT
            }
            if (comment.revealedAt != null && comment.deliveredAt == null) {
                issues += BackupValidationIssue.REVEALED_WITHOUT_DELIVERED
            }
            if (comment.firstPresentedAt != null && comment.revealedAt == null) {
                issues += BackupValidationIssue.PRESENTED_WITHOUT_REVEALED
            }
            if (!CommentColorRole.isKnownStorageId(comment.appearanceColor) ||
                !CommentSizeRole.isKnownStorageId(comment.appearanceSize) ||
                !CommentEmphasisRole.isKnownStorageId(comment.appearanceEmphasis) ||
                !CommentMotionMode.isKnownStorageId(comment.motionMode)
            ) {
                issues += BackupValidationIssue.INVALID_FUTURE_COMMENT_EXPRESSION
            }
        }
    }

    private fun validateSettings(
        settings: SettingsBackupDto,
        issues: MutableSet<BackupValidationIssue>,
    ) {
        val valid = settings.themeMode in ThemeMode.entries.map(ThemeMode::storageId) &&
            settings.playbackSpeed in PlaybackSpeed.entries.map(PlaybackSpeed::storageId) &&
            settings.commentSize in CommentSize.entries.map(CommentSize::storageId) &&
            settings.stageBackground in StageBackground.entries.map(StageBackground::storageId)
        if (!valid) issues += BackupValidationIssue.INVALID_SETTINGS
    }

    private fun validateAttachments(
        formatVersion: Int,
        payload: BackupPayloadDto,
        issues: MutableSet<BackupValidationIssue>,
    ) {
        val blobs = payload.attachmentBlobs
        val memoRelations = payload.memoPhotoAttachments
        val diaryRelations = payload.diaryPhotoAttachments
        if (formatVersion < 10 &&
            (blobs.isNotEmpty() || memoRelations.isNotEmpty() || diaryRelations.isNotEmpty())
        ) {
            issues += BackupValidationIssue.ATTACHMENT_IN_LEGACY_FORMAT
        }
        val hashes = blobs.map(AttachmentBlobBackupDto::sha256)
        if (hashes.size != hashes.toSet().size) {
            issues += BackupValidationIssue.DUPLICATE_ATTACHMENT_BLOB
        }
        if (blobs.any { blob ->
                !AttachmentBlobStore.SHA256_PATTERN.matches(blob.sha256) ||
                    blob.kind != AttachmentKind.IMAGE ||
                    !blob.mimeType.startsWith("image/") ||
                    !AttachmentLimits.acceptsByteCount(blob.sizeBytes) ||
                    !AttachmentLimits.acceptsDimensions(blob.widthPx, blob.heightPx) ||
                    blob.createdAt < 0
            }
        ) {
            issues += BackupValidationIssue.INVALID_ATTACHMENT_BLOB
        }
        val memoRelationIds = memoRelations.map(MemoPhotoAttachmentBackupDto::id)
        val diaryRelationIds = diaryRelations.map(DiaryPhotoAttachmentBackupDto::id)
        if (memoRelationIds.any { it <= 0L } || diaryRelationIds.any { it <= 0L } ||
            memoRelationIds.size != memoRelationIds.toSet().size ||
            diaryRelationIds.size != diaryRelationIds.toSet().size
        ) {
            issues += BackupValidationIssue.DUPLICATE_PHOTO_ATTACHMENT_ID
        }
        val memoPairs = memoRelations.map { it.memoId to it.blobSha256 }
        val diaryPairs = diaryRelations.map { it.diaryEntryId to it.blobSha256 }
        if (memoPairs.size != memoPairs.toSet().size || diaryPairs.size != diaryPairs.toSet().size) {
            issues += BackupValidationIssue.DUPLICATE_PHOTO_ATTACHMENT
        }
        val memoIds = payload.memos.mapTo(hashSetOf(), MemoBackupDto::id)
        val diaryIds = payload.diaryEntries.mapTo(hashSetOf(), DiaryEntryBackupDto::id)
        val blobHashes = hashes.toHashSet()
        if (memoRelations.any { it.memoId !in memoIds || it.blobSha256 !in blobHashes } ||
            diaryRelations.any { it.diaryEntryId !in diaryIds || it.blobSha256 !in blobHashes }
        ) {
            issues += BackupValidationIssue.ORPHAN_PHOTO_ATTACHMENT
        }
        // A note's cover points at a blob the same way the photo relations do, and it was the
        // one reference nothing here checked: a malformed file could restore with a cover sha
        // that is no sha at all, or one whose blob the file does not carry — a silently missing
        // picture after a "successful" restore.
        if (payload.notes.any { note ->
                note.coverBlobSha256 != null &&
                    (!AttachmentBlobStore.SHA256_PATTERN.matches(note.coverBlobSha256) ||
                        note.coverBlobSha256 !in blobHashes)
            }
        ) {
            issues += BackupValidationIssue.ORPHAN_PHOTO_ATTACHMENT
        }
        val outlineIds = payload.memos.filter { it.kind == MemoKind.OUTLINE.storageId }.mapTo(hashSetOf(), MemoBackupDto::id)
        val activeOutlinePhotos = payload.outlineRows.filter { it.kind == "photo" }.groupingBy { it.memoId }.eachCount()
        fun invalidOrder(groups: Collection<List<Int>>): Boolean = groups.any { values ->
            values.sorted() != values.indices.toList()
        }
        if (invalidOrder(memoRelations.groupBy { it.memoId }.values.map { group ->
                group.map(MemoPhotoAttachmentBackupDto::sortOrder)
            }) || invalidOrder(diaryRelations.groupBy { it.diaryEntryId }.values.map { group ->
                group.map(DiaryPhotoAttachmentBackupDto::sortOrder)
            }) || memoRelations.groupBy { it.memoId }.any { (memoId, group) ->
                // From 23 an outline's cap is its photo rows — the photos it shows. A photo kept
                // for 元に戻す, or back from a process death, is a record no row shows and is not
                // counted (docs/OUTLINE_PHOTO_ROWS.md §7.3).
                if (formatVersion >= 23 && memoId in outlineIds) {
                    (activeOutlinePhotos[memoId] ?: 0) > AttachmentLimits.MAX_PHOTOS_PER_RECORD
                } else {
                    group.size > AttachmentLimits.MAX_PHOTOS_PER_RECORD
                }
            } || diaryRelations.groupBy { it.diaryEntryId }.values.any {
                it.size > AttachmentLimits.MAX_PHOTOS_PER_RECORD
            }
        ) {
            issues += BackupValidationIssue.INVALID_PHOTO_ORDER
        }
    }

    private companion object {
        val DIARY_STATES = setOf("draft", "finalized", "correcting", "locked")
    }
}

/**
 * Format 20's blocks (docs/MEMO_CONTENT_BLOCKS.md): every block belongs to a memo of kind `memo`;
 * a text carries words and no photo, a photo carries a photo row of the same memo and no words;
 * a memo's blocks sit at 0..n-1, hold at least one text, and name each of its photo rows exactly
 * once. A memo with no blocks at all is allowed — it is read with its photos above its text.
 */
object MemoContentBlockRules {
    fun valid(payload: BackupPayloadDto): Boolean {
        val blocks = payload.memoContentBlocks
        if (blocks.isEmpty()) return true
        if (blocks.any { it.id <= 0L } || blocks.map { it.id }.toSet().size != blocks.size) return false
        val memoKinds = payload.memos.associate { it.id to it.kind }
        val relationsByMemo = payload.memoPhotoAttachments.groupBy { it.memoId }
        return blocks.groupBy { it.memoId }.all { (memoId, own) ->
            if (memoKinds[memoId] != MemoKind.MEMO.storageId) return@all false
            if (own.map { it.position }.sorted() != own.indices.toList()) return@all false
            val shapes = own.all { block ->
                when (block.type) {
                    "text" -> block.text != null && block.photoAttachmentId == null
                    "photo" -> block.text == null && block.photoAttachmentId != null
                    else -> false
                }
            }
            if (!shapes || own.none { it.type == "text" }) return@all false
            val named = own.mapNotNull { it.photoAttachmentId }
            val rows = relationsByMemo[memoId].orEmpty().map { it.id }
            named.size == named.toSet().size && named.toSet() == rows.toSet()
        }
    }
}

/**
 * Format 21's diary blocks (docs/MEMO_CONTENT_BLOCKS.md §11): the same rules as
 * [MemoContentBlockRules] on journal entries — every block belongs to an entry in the file, the
 * shapes hold, 0..n-1, at least one text, each photo row of the entry named exactly once. An entry
 * with no blocks at all is allowed — it is read with its words first and its photos under them.
 */
object DiaryContentBlockRules {
    fun valid(payload: BackupPayloadDto): Boolean {
        val blocks = payload.diaryContentBlocks
        if (blocks.isEmpty()) return true
        if (blocks.any { it.id <= 0L } || blocks.map { it.id }.toSet().size != blocks.size) return false
        val entries = payload.diaryEntries.map { it.id }.toSet()
        val relationsByEntry = payload.diaryPhotoAttachments.groupBy { it.diaryEntryId }
        return blocks.groupBy { it.diaryEntryId }.all { (entryId, own) ->
            if (entryId !in entries) return@all false
            if (own.map { it.position }.sorted() != own.indices.toList()) return@all false
            val shapes = own.all { block ->
                when (block.type) {
                    "text" -> block.text != null && block.photoAttachmentId == null
                    "photo" -> block.text == null && block.photoAttachmentId != null
                    else -> false
                }
            }
            if (!shapes || own.none { it.type == "text" }) return@all false
            val named = own.mapNotNull { it.photoAttachmentId }
            val rows = relationsByEntry[entryId].orEmpty().map { it.id }
            named.size == named.toSet().size && named.toSet() == rows.toSet()
        }
    }
}

/**
 * Format 22's outline rows (docs/OUTLINE_STABLE_ROWS.md): every row belongs to a memo of kind
 * `outline`; within an outline the ids are positive and unique, the rows sit at 0..n-1, and a text
 * is one line (no line break). An outline with no rows at all is allowed — it is read line by
 * line from its body.
 */
object OutlineRowRules {
    fun valid(payload: BackupPayloadDto): Boolean {
        val rows = payload.outlineRows
        if (rows.isEmpty()) return true
        val memoKinds = payload.memos.associate { it.id to it.kind }
        val photosByMemo = payload.memoPhotoAttachments.groupBy({ it.memoId }, { it.id })
        return rows.groupBy { it.memoId }.all { (memoId, own) ->
            val shapes = own.all { row ->
                when (row.kind) {
                    "text" -> row.photoAttachmentId == null
                    // A photo row shows one of its outline's own photos and carries only its indent.
                    "photo" -> row.photoAttachmentId != null && row.photoAttachmentId in photosByMemo[memoId].orEmpty() && row.text.isBlank()
                    else -> false
                }
            }
            val shown = own.mapNotNull { it.photoAttachmentId }
            memoKinds[memoId] == MemoKind.OUTLINE.storageId && shapes &&
                shown.size == shown.toSet().size &&
                own.all { it.rowId > 0 && '\n' !in it.text } &&
                own.map { it.rowId }.toSet().size == own.size &&
                own.map { it.position }.sorted() == own.indices.toList()
        }
    }
}
