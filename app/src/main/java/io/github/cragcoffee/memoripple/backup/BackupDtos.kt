package io.github.cragcoffee.memoripple.backup

import kotlinx.serialization.Serializable

@Serializable
data class MemoRippleBackupDto(
    val format: String,
    val formatVersion: Int,
    val exportedAt: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val payload: BackupPayloadDto,
)

@Serializable
data class BackupPayloadDto(
    val memos: List<MemoBackupDto>,
    val memoComments: List<MemoCommentBackupDto>,
    val diaryEntries: List<DiaryEntryBackupDto>,
    val futureDiaryComments: List<FutureDiaryCommentBackupDto>,
    val settings: SettingsBackupDto,
    val tags: List<TagBackupDto> = emptyList(),
    val memoTagRelations: List<MemoTagRelationBackupDto> = emptyList(),
    val attachmentBlobs: List<AttachmentBlobBackupDto> = emptyList(),
    val memoPhotoAttachments: List<MemoPhotoAttachmentBackupDto> = emptyList(),
    val diaryPhotoAttachments: List<DiaryPhotoAttachmentBackupDto> = emptyList(),
    val notes: List<NoteBackupDto> = emptyList(),
    val noteChapters: List<NoteChapterBackupDto> = emptyList(),
    /** フォルダ — carried since format 16; older files simply have none. */
    val folders: List<FolderBackupDto> = emptyList(),
    /** テンプレート — carried since format 18 (name + body); format 19 carries the whole v2 definition; older files simply have none. */
    val templates: List<TemplateBackupDto> = emptyList(),
    /** Template folders (2026-09-22, still format 19 — a defaulted list an older reader ignores): the user's organisation of templates. */
    val templateFolders: List<TemplateFolderBackupDto> = emptyList(),
    /**
     * 本文のブロック — format 20 (docs/MEMO_CONTENT_BLOCKS.md): a memo's text and photos in the
     * order the writer put them. An older file has none; its memos are read with their photos
     * above their text, as they looked.
     */
    val memoContentBlocks: List<MemoContentBlockBackupDto> = emptyList(),
    /**
     * 日記の本文のブロック — format 21 (docs/MEMO_CONTENT_BLOCKS.md §11): a journal entry's text and
     * photos in the order the writer put them. An older file has none; its entries are read with
     * their words first and their photos under them, as they looked.
     */
    val diaryContentBlocks: List<DiaryContentBlockBackupDto> = emptyList(),
    /**
     * アウトラインの行 — format 22 (docs/OUTLINE_STABLE_ROWS.md): each line of an outline with its
     * lasting id, in order. An older file has none; its outlines are read line by line from their
     * bodies (ids 1..n), exactly as the Room migration did.
     */
    val outlineRows: List<OutlineRowBackupDto> = emptyList(),
)

/**
 * One row of an outline: its lasting id within the outline, its place, and its text as written —
 * or, from format 23 (docs/OUTLINE_PHOTO_ROWS.md), a photo row: [kind] `photo`, [text] its indent,
 * [photoAttachmentId] the outline's photo it shows.
 */
@Serializable
data class OutlineRowBackupDto(
    val memoId: Long,
    val rowId: Int,
    val position: Int,
    val text: String,
    val kind: String = "text",
    val photoAttachmentId: Long? = null,
)

/** One block of a journal entry's content: `text` with its words, or `photo` with its photo row. */
@Serializable
data class DiaryContentBlockBackupDto(
    val id: Long,
    val diaryEntryId: Long,
    val position: Int,
    val type: String,
    val text: String? = null,
    val photoAttachmentId: Long? = null,
)

/** One block of a memo's content: `text` with its words, or `photo` with its photo row. */
@Serializable
data class MemoContentBlockBackupDto(
    val id: Long,
    val memoId: Long,
    val position: Int,
    val type: String,
    val text: String? = null,
    val photoAttachmentId: Long? = null,
)

@Serializable
data class AttachmentBlobBackupDto(
    val sha256: String,
    val kind: String,
    val mimeType: String,
    val sizeBytes: Long,
    val widthPx: Int,
    val heightPx: Int,
    val createdAt: Long,
)

@Serializable
data class MemoPhotoAttachmentBackupDto(
    val id: Long,
    val memoId: Long,
    val blobSha256: String,
    val sortOrder: Int,
    val createdAt: Long,
)

@Serializable
data class DiaryPhotoAttachmentBackupDto(
    val id: Long,
    val diaryEntryId: Long,
    val blobSha256: String,
    val sortOrder: Int,
    val createdAt: Long,
)

@Serializable
data class TagBackupDto(val id: Long, val name: String, val createdAt: Long)

@Serializable
data class MemoTagRelationBackupDto(val memoId: Long, val tagId: Long)

@Serializable
data class MemoBackupDto(
    val id: Long,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isFavorite: Boolean = false,
    val isPinned: Boolean = false,
    val archivedAt: Long? = null,
    val trashedAt: Long? = null,
    val noteId: Long? = null,
    val chapterId: Long? = null,
    val episodeOrder: Int = 0,
    /** 文書種別 (`memo` / `outline`) — carried since format 15; older files hold memos only. */
    val kind: String = "memo",
    /** The folder the memo is kept in — carried since format 16; null is the root. */
    val folderId: Long? = null,
    /** 並べた順 — carried since format 17; older files have every card at 0. */
    val sortIndex: Int = 0,
)

@Serializable
data class FolderBackupDto(
    val id: Long,
    val name: String,
    val parentFolderId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class NoteBackupDto(
    val id: Long,
    val title: String,
    /** Empty in every backup before format 13, which is what a note with no second line is. */
    val subtitle: String = "",
    val coverColor: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Null in every backup written before format 12, which is what a note with no picture is. */
    val coverBlobSha256: String? = null,
    /** 並べた順 — carried since format 17; older files have every note at 0. */
    val sortIndex: Int = 0,
)

@Serializable
data class NoteChapterBackupDto(
    val id: Long,
    val noteId: Long,
    val title: String,
    val sortOrder: Int,
)

@Serializable
data class MemoCommentBackupDto(
    val id: Long,
    val memoId: Long,
    val text: String,
    val createdAt: Long,
    val playbackOrder: Int,
    val colorRole: String = "default",
    val sizeRole: String = "standard",
    val emphasisRole: String = "normal",
    val speedRole: String = "standard",
    val placementRole: String = "auto",
    val motionMode: String = "flow",
    val flowDirection: String = "rtl",
    val flowEffect: String = "straight",
    /** コメントリンク番号 — carried since format 14; older files simply have none. */
    val linkNo: Int? = null,
)

@Serializable
data class DiaryEntryBackupDto(
    val id: Long,
    val diaryDateEpochDay: Long,
    val body: String,
    val state: String,
    val createdAt: Long,
    val updatedAt: Long,
    val finalizedAt: Long?,
    val correctionStartedAt: Long?,
    val lockedAt: Long?,
)

@Serializable
data class FutureDiaryCommentBackupDto(
    val id: Long,
    val diaryEntryId: Long,
    val text: String,
    val sealedAt: Long,
    val revealAt: Long,
    val deliveredAt: Long?,
    val revealedAt: Long?,
    val firstPresentedAt: Long?,
    val appearanceColor: String = "default",
    val appearanceSize: String = "standard",
    val appearanceEmphasis: String = "normal",
    val motionMode: String = "flow",
)

@Serializable
data class SettingsBackupDto(
    val themeMode: String,
    val playbackSpeed: String,
    val commentSize: String,
    val stageBackground: String,
    val commentScope: String = "outline",
)

const val BACKUP_FORMAT_IDENTIFIER = "memorripple_backup"
const val BACKUP_FORMAT_VERSION = 23

/** The version the container shape arrived at. Anything older is a plain compressed document. */
const val CONTAINER_MIN_FORMAT_VERSION = 10
const val MIN_SUPPORTED_BACKUP_FORMAT_VERSION = 1
const val BACKUP_EXTENSION = ".mrbackup"
const val BACKUP_MIME_TYPE = "application/octet-stream"

/**
 * A template as the writer keeps it. Format 18 wrote a name and a body (a memo starting point);
 * format 19 (Template v2, docs/CHAT_UI_TEMPLATE_V2.md §14) adds the action, the kind, the fields,
 * the search condition and the append target — every new part defaults, so an 18 file decodes
 * into the legacy shape and a 19 file restores a v2 template whole. Strings, not domain enums:
 * this is a wire format; the mapper turns it into the domain and the validator judges the result.
 */
@Serializable
data class TemplateBackupDto(
    val id: String,
    val name: String,
    val body: String,
    val description: String = "",
    /** `create` / `search` / `append`. */
    val action: String = "create",
    /** `memo` / `outline` / `journal`. */
    val documentKind: String = "memo",
    val fields: List<TemplateFieldBackupDto> = emptyList(),
    val search: TemplateSearchBackupDto? = null,
    val target: TemplateTargetBackupDto? = null,
    /** The target question of an APPEND that asks (§16); empty on an older file. */
    val targetQuestion: String = "",
    /**
     * `record` / `think` (docs/THINK_TEMPLATES.md, 2026-09-22) — still format 19: a defaulted word
     * a reader before Think templates ignores, and then reads the same template as a CREATE memo
     * template with its questions, which is what it is underneath.
     */
    val flow: String = "record",
    /** The template folder (2026-09-22; a defaulted field an older reader ignores → unclassified); a starter carries none. */
    val folderId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** A template folder as the writer keeps it (2026-09-22): id, name, order — template organisation, never the wall's folder. */
@Serializable
data class TemplateFolderBackupDto(
    val id: String,
    val name: String,
    val order: Int = 0,
)

@Serializable
data class TemplateFieldBackupDto(
    val key: String,
    val label: String,
    /** `text` / `multiline` / `date` / `choice` / `boolean`. */
    val type: String,
    val required: Boolean = false,
    val default: String = "",
    val choices: List<String> = emptyList(),
    /** The question the chat asks for this field (§16); empty on an older file. */
    val question: String = "",
)

@Serializable
data class TemplateSearchBackupDto(
    val query: String = "",
    /** `today` / `yesterday` / `this_week` / `last_week`, or none. */
    val dateToken: String? = null,
    /** `memo` / `outline` / `journal`. */
    val kinds: List<String> = emptyList(),
)

@Serializable
data class TemplateTargetBackupDto(
    /** `named` (with [name]) or `ask`. */
    val mode: String,
    val name: String? = null,
)
