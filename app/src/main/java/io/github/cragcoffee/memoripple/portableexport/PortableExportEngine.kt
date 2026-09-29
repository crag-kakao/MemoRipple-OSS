package io.github.cragcoffee.memoripple.portableexport

import io.github.cragcoffee.memoripple.backup.SafBackupFileStore
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.export.PortableComment
import io.github.cragcoffee.memoripple.domain.export.PortableDiary
import io.github.cragcoffee.memoripple.domain.export.PortableEpisode
import io.github.cragcoffee.memoripple.domain.export.PortableExpressionNotes
import io.github.cragcoffee.memoripple.domain.export.PortableExportNaming
import io.github.cragcoffee.memoripple.domain.export.PortableExportPlanner
import io.github.cragcoffee.memoripple.domain.export.PortableExportPlanner.PlannedPhoto
import io.github.cragcoffee.memoripple.domain.export.PortableFutureComment
import io.github.cragcoffee.memoripple.domain.export.PortableMarkdownRenderer
import io.github.cragcoffee.memoripple.domain.export.PortableMarkdownRenderer.PhotoOutcome
import io.github.cragcoffee.memoripple.domain.export.PortableMemo
import io.github.cragcoffee.memoripple.domain.export.PortableNote
import io.github.cragcoffee.memoripple.domain.export.PortableNoteSection
import io.github.cragcoffee.memoripple.domain.export.PortablePhoto
import io.github.cragcoffee.memoripple.domain.export.PortableShelf
import io.github.cragcoffee.memoripple.domain.export.PortableSnapshot
import io.github.cragcoffee.memoripple.domain.export.PortableZipWriter
import io.github.cragcoffee.memoripple.domain.notes.NoteStructure
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 読める形式で書き出す — the whole run, from Room snapshot to a finished ZIP at the URI the
 * user chose. This is a reader: it never writes Room, never touches the backup format or its
 * Drive transport, and never advances the Drive dirty generation. The archive is staged
 * complete in the app's own cache first and only then copied to the chosen document, so a
 * failure part-way leaves no plausible-looking half ZIP behind; the staging file is deleted
 * on success, failure, and cancellation alike.
 *
 * Privacy holds at the snapshot: a future comment's words leave only when the user has
 * already been shown them (`firstPresentedAt != null`) — the same rule the reading UI
 * enforces — and nothing about the sealed ones, not even how many, is written anywhere.
 */
class PortableExportEngine(
    private val database: AppDatabase,
    private val blobStore: AttachmentBlobStore,
    private val fileStore: SafBackupFileStore,
    private val cacheDirectory: File,
) {

    data class ExportCounts(
        val activeMemos: Int,
        val archivedMemos: Int,
        val trashedMemos: Int,
        val diaries: Int,
        val notes: Int,
        val photos: Int,
        val estimatedPhotoBytes: Long,
    )

    sealed interface ExportResult {
        data class Done(val warnings: Int) : ExportResult
        data object Failed : ExportResult
    }

    /** What the confirmation sheet shows before anything is written. */
    suspend fun counts(): ExportCounts {
        val dao = database.backupDao()
        val memos = dao.readMemos()
        val standalone = memos.filter { it.noteId == null }
        val blobs = dao.readAttachmentBlobs().associateBy { it.sha256 }
        val memoPhotos = dao.readMemoPhotoAttachments()
        val diaryPhotos = dao.readDiaryPhotoAttachments()
        val photoBytes = (memoPhotos.mapNotNull { blobs[it.blobSha256]?.sizeBytes } +
            diaryPhotos.mapNotNull { blobs[it.blobSha256]?.sizeBytes }).sum()
        return ExportCounts(
            activeMemos = standalone.count { it.trashedAt == null && it.archivedAt == null },
            archivedMemos = standalone.count { it.trashedAt == null && it.archivedAt != null },
            trashedMemos = standalone.count { it.trashedAt != null },
            diaries = dao.readDiaryEntries().size,
            notes = dao.readNotes().size,
            photos = memoPhotos.size + diaryPhotos.size,
            estimatedPhotoBytes = photoBytes,
        )
    }

    /** The name offered to the file picker. */
    fun suggestedFileName(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        PortableExportNaming.archiveFileName(
            Instant.ofEpochMilli(nowMillis).atZone(zone)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")),
        )

    fun suggestedPdfFileName(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "MemoRipple-Export-" + Instant.ofEpochMilli(nowMillis).atZone(zone)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".pdf"

    /**
     * One memo (or one episode) as its own small PDF — the editor's PDFで保存. The same
     * snapshot, plan and writer as the full export, narrowed to the single record, so the
     * page layout and the metadata guarantees stay identical.
     */
    suspend fun exportMemoPdf(
        memoId: Long,
        destination: android.net.Uri,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ExportResult = withContext(Dispatchers.IO) {
        val snapshot = snapshot()
        val plan = PortableExportPlanner.plan(snapshot, includeTrash = true, zone = zone)
        val single = plan.allMemos.firstOrNull { it.memo.id == memoId }
            ?.let { planned ->
                PortableExportPlanner.PortableExportPlan(
                    activeMemos = listOf(planned),
                    archivedMemos = emptyList(),
                    trashedMemos = emptyList(),
                    diaries = emptyList(),
                    notes = emptyList(),
                )
            }
            ?: plan.notes.firstNotNullOfOrNull { note ->
                note.episodeFiles.firstOrNull { it.episode.id == memoId }?.let { file ->
                    PortableExportPlanner.PortableExportPlan(
                        activeMemos = emptyList(),
                        archivedMemos = emptyList(),
                        trashedMemos = emptyList(),
                        diaries = emptyList(),
                        notes = listOf(note.copy(coverPhoto = null, episodeFiles = listOf(file))),
                    )
                }
            }
            ?: return@withContext ExportResult.Failed
        val staging = File.createTempFile("portable-export", ".pdf", cacheDirectory)
        try {
            val active = currentCoroutineContext()
            try {
                PortablePdfWriter(blobStore, zone).write(
                    plan = single,
                    exportedAtMillis = nowMillis,
                    target = staging,
                    isActive = { active.isActive },
                ) {}
            } catch (interrupted: InterruptedException) {
                active.ensureActive()
                return@withContext ExportResult.Failed
            }
            currentCoroutineContext().ensureActive()
            if (fileStore.write(destination, staging)) {
                ExportResult.Done(warnings = 0)
            } else {
                ExportResult.Failed
            }
        } finally {
            staging.delete()
        }
    }

    /**
     * The same snapshot and plan, drawn as one PDF instead of packed as an archive. Staged
     * complete in cache first, exactly like the ZIP; images are redrawn, so no camera
     * metadata reaches the file regardless of the strip option.
     */
    suspend fun exportPdf(
        includeTrash: Boolean,
        destination: android.net.Uri,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ExportResult = withContext(Dispatchers.IO) {
        val snapshot = snapshot()
        val plan = PortableExportPlanner.plan(snapshot, includeTrash, zone)
        val staging = File.createTempFile("portable-export", ".pdf", cacheDirectory)
        try {
            var done = 0
            val documents = plan.allMemos.size + plan.diaries.size +
                plan.notes.sumOf { 1 + it.episodeFiles.size }
            val active = currentCoroutineContext()
            try {
                PortablePdfWriter(blobStore, zone).write(
                    plan = plan,
                    exportedAtMillis = nowMillis,
                    target = staging,
                    isActive = { active.isActive },
                ) {
                    done += 1
                    onProgress(done, documents)
                }
            } catch (interrupted: InterruptedException) {
                active.ensureActive()
                return@withContext ExportResult.Failed
            }
            currentCoroutineContext().ensureActive()
            if (fileStore.write(destination, staging)) {
                ExportResult.Done(warnings = 0)
            } else {
                ExportResult.Failed
            }
        } finally {
            staging.delete()
        }
    }

    suspend fun export(
        includeTrash: Boolean,
        stripPhotoMetadata: Boolean = false,
        destination: android.net.Uri,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ExportResult = withContext(Dispatchers.IO) {
        val snapshot = snapshot()
        val plan = PortableExportPlanner.plan(snapshot, includeTrash, zone)
        val warnings = mutableListOf<String>()
        val staging = File.createTempFile("portable-export", ".zip", cacheDirectory)
        try {
            var done = 0
            val total = plan.totalUnits
            fun step() {
                done += 1
                onProgress(done, total)
            }
            staging.outputStream().use { out ->
                PortableZipWriter(out).use { zip ->
                    zip.addText(
                        "${PortableExportPlanner.ROOT}/README.md",
                        PortableMarkdownRenderer.readme(
                            Instant.ofEpochMilli(nowMillis).atZone(zone)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                        ),
                    )
                    zip.addText(
                        "${PortableExportPlanner.ROOT}/INDEX.md",
                        PortableMarkdownRenderer.index(plan),
                    )
                    // The same archive reads in a browser too: index.html mirrors INDEX.md,
                    // and every record page gets an .html twin beside its .md.
                    zip.addText(
                        "${PortableExportPlanner.ROOT}/index.html",
                        io.github.cragcoffee.memoripple.domain.export.PortableHtmlRenderer
                            .indexHtml(plan),
                    )
                    for (planned in plan.allMemos) {
                        currentCoroutineContext().ensureActive()
                        val outcomes = copyPhotos(zip, planned.photos, stripPhotoMetadata, warnings) {
                            memoLabel(planned.memo)
                        }
                        outcomes.forEach { if (it.copied) step() }
                        zip.addText(
                            planned.markdownPath,
                            PortableMarkdownRenderer.memoMarkdown(planned, outcomes, zone),
                        )
                        zip.addText(
                            io.github.cragcoffee.memoripple.domain.export
                                .PortableHtmlRenderer.htmlPathFor(planned.markdownPath),
                            io.github.cragcoffee.memoripple.domain.export
                                .PortableHtmlRenderer.memoHtml(planned, outcomes, zone),
                        )
                        step()
                    }
                    for (planned in plan.diaries) {
                        currentCoroutineContext().ensureActive()
                        val outcomes = copyPhotos(zip, planned.photos, stripPhotoMetadata, warnings) {
                            "日記 ${planned.dateLabel}"
                        }
                        outcomes.forEach { if (it.copied) step() }
                        zip.addText(
                            planned.markdownPath,
                            PortableMarkdownRenderer.diaryMarkdown(planned, outcomes, zone),
                        )
                        zip.addText(
                            io.github.cragcoffee.memoripple.domain.export
                                .PortableHtmlRenderer.htmlPathFor(planned.markdownPath),
                            io.github.cragcoffee.memoripple.domain.export
                                .PortableHtmlRenderer.diaryHtml(planned, outcomes),
                        )
                        step()
                    }
                    for (planned in plan.notes) {
                        currentCoroutineContext().ensureActive()
                        val coverOutcome = planned.coverPhoto?.let { cover ->
                            copyPhotos(zip, listOf(cover), stripPhotoMetadata, warnings) {
                                "ノート「${planned.note.title.ifBlank { "無題のノート" }}」の表紙"
                            }.single().also { if (it.copied) step() }
                        }
                        zip.addText(
                            planned.readmePath,
                            PortableMarkdownRenderer.noteReadme(planned, coverOutcome, zone),
                        )
                        zip.addText(
                            io.github.cragcoffee.memoripple.domain.export
                                .PortableHtmlRenderer.htmlPathFor(planned.readmePath),
                            io.github.cragcoffee.memoripple.domain.export
                                .PortableHtmlRenderer.noteHtml(planned, coverOutcome, zone),
                        )
                        step()
                        for (file in planned.episodeFiles) {
                            currentCoroutineContext().ensureActive()
                            val outcomes = copyPhotos(zip, file.photos, stripPhotoMetadata, warnings) {
                                "第${file.episode.number}話 " +
                                    file.episode.title.ifBlank { "無題" }
                            }
                            outcomes.forEach { if (it.copied) step() }
                            zip.addText(
                                file.markdownPath,
                                PortableMarkdownRenderer.episodeMarkdown(file, outcomes),
                            )
                            zip.addText(
                                io.github.cragcoffee.memoripple.domain.export
                                    .PortableHtmlRenderer.htmlPathFor(file.markdownPath),
                                io.github.cragcoffee.memoripple.domain.export
                                    .PortableHtmlRenderer.episodeHtml(file, outcomes),
                            )
                            step()
                        }
                    }
                    if (warnings.isNotEmpty()) {
                        zip.addText(
                            "${PortableExportPlanner.ROOT}/EXPORT_WARNINGS.md",
                            PortableMarkdownRenderer.warnings(warnings),
                        )
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (fileStore.write(destination, staging)) {
                ExportResult.Done(warnings.size)
            } else {
                ExportResult.Failed
            }
        } finally {
            staging.delete()
        }
    }

    /**
     * Streams each photo's original managed bytes into the archive. A blob that is absent or
     * whose length disagrees with its record is skipped with a warning — the export goes on
     * without it — and nothing about why is said beyond a general reason.
     */
    private fun copyPhotos(
        zip: PortableZipWriter,
        photos: List<PlannedPhoto>,
        stripMetadata: Boolean,
        warnings: MutableList<String>,
        ownerLabel: () -> String,
    ): List<PhotoOutcome> = photos.map { planned ->
        val file = runCatching { blobStore.blobFile(planned.photo.sha256) }.getOrNull()
        val healthy = file != null && file.isFile && file.length() == planned.photo.sizeBytes
        if (!healthy) {
            warnings.add(
                "「${ownerLabel()}」の写真${planned.displayIndex}は" +
                    "読み込めなかったため、書き出されませんでした。",
            )
            PhotoOutcome(planned, copied = false)
        } else if (
            stripMetadata && PortablePhotoMetadataStripper.supports(planned.photo.mimeType)
        ) {
            // The strip works on a private copy; the managed blob itself is never touched. The copy
            // is the export's own file, never in the photo store's tmp — whose sweep (under the
            // store's lock, which an export does not hold) may empty it at any moment.
            val temp = File.createTempFile("portable-strip", null, cacheDirectory)
            try {
                file!!.copyTo(temp, overwrite = true)
                if (!PortablePhotoMetadataStripper.stripInPlace(temp)) {
                    warnings.add(
                        "「${ownerLabel()}」の写真${planned.displayIndex}は" +
                            "メタデータを取り除けなかったため、元のまま書き出しました。",
                    )
                }
                temp.inputStream().use { source -> zip.addStream(planned.entryPath, source) }
            } finally {
                temp.delete()
            }
            PhotoOutcome(planned, copied = true)
        } else {
            file!!.inputStream().use { source -> zip.addStream(planned.entryPath, source) }
            PhotoOutcome(planned, copied = true)
        }
    }

    private fun memoLabel(memo: PortableMemo): String = memo.title.ifBlank { "無題のメモ" }

    private suspend fun snapshot(): PortableSnapshot {
        val dao = database.backupDao()
        val memos = dao.readMemos()
        val comments = dao.readMemoComments().groupBy { it.memoId }
        val tags = dao.readTags().associateBy { it.id }
        val tagNames = dao.readMemoTagRelations().groupBy({ it.memoId }) { tags[it.tagId]?.name }
        val blobs = dao.readAttachmentBlobs().associateBy { it.sha256 }
        val memoPhotos = dao.readMemoPhotoAttachments()
            .groupBy { it.memoId }
            .mapValues { (_, relations) ->
                relations.sortedWith(compareBy({ it.sortOrder }, { it.id }))
                    .mapNotNull { relation -> blobs[relation.blobSha256]?.toPortable() }
            }
        val diaryPhotos = dao.readDiaryPhotoAttachments()
            .groupBy { it.diaryEntryId }
            .mapValues { (_, relations) ->
                relations.sortedWith(compareBy({ it.sortOrder }, { it.id }))
                    .mapNotNull { relation -> blobs[relation.blobSha256]?.toPortable() }
            }

        fun MemoEntity.toPortable(shelf: PortableShelf) = PortableMemo(
            outline = kind == io.github.cragcoffee.memoripple.domain.memos.MemoKind.OUTLINE.storageId,
            id = id,
            title = title,
            body = body,
            createdAt = createdAt,
            updatedAt = updatedAt,
            favorite = isFavorite,
            pinned = isPinned,
            shelf = shelf,
            tags = tagNames[id].orEmpty().filterNotNull(),
            comments = comments[id].orEmpty()
                .sortedWith(compareBy({ it.playbackOrder }, { it.id }))
                .map { entity ->
                    PortableComment(
                        text = entity.text,
                        expressionNotes = PortableExpressionNotes.forComment(
                            color = entity.appearanceColor,
                            size = entity.appearanceSize,
                            emphasis = entity.appearanceEmphasis,
                            motionMode = entity.motionMode,
                            speed = entity.motionSpeed,
                            placement = entity.motionPlacement,
                            flowDirection = entity.flowDirection,
                            flowEffect = entity.flowEffect,
                        ),
                    )
                },
            photos = memoPhotos[id].orEmpty(),
        )

        val standalone = memos.filter { it.noteId == null }.map { memo ->
            memo.toPortable(
                when {
                    memo.trashedAt != null -> PortableShelf.TRASHED
                    memo.archivedAt != null -> PortableShelf.ARCHIVED
                    else -> PortableShelf.ACTIVE
                },
            )
        }

        // The words of a future comment leave only after the user has been shown them; the
        // same guard the reading surfaces apply. Sealed and delivered-but-unopened comments
        // contribute nothing — not text, not styling, not a count.
        val futureByDiary = dao.readFutureDiaryComments()
            .filter { it.revealedAt != null && it.firstPresentedAt != null }
            .groupBy { it.diaryEntryId }
            .mapValues { (_, list) ->
                list.sortedWith(compareBy({ it.revealAt }, { it.id })).map { entity ->
                    PortableFutureComment(
                        text = entity.text,
                        expressionNotes = PortableExpressionNotes.forComment(
                            color = entity.appearanceColor,
                            size = entity.appearanceSize,
                            emphasis = entity.appearanceEmphasis,
                            motionMode = entity.motionMode,
                        ),
                    )
                }
            }

        val diaries = dao.readDiaryEntries().map { entry ->
            PortableDiary(
                id = entry.id,
                epochDay = entry.diaryDateEpochDay,
                body = entry.body,
                stateLabel = when (entry.state) {
                    DiaryState.DRAFT -> "下書き"
                    DiaryState.FINALIZED -> "確定済み"
                    DiaryState.CORRECTING -> "修正中"
                    DiaryState.LOCKED -> "確定済み（編集終了）"
                },
                photos = diaryPhotos[entry.id].orEmpty(),
                futureComments = futureByDiary[entry.id].orEmpty(),
                createdAt = entry.createdAt,
            )
        }

        val chaptersByNote = dao.readNoteChapters().groupBy { it.noteId }
        val episodesByNote = memos.filter { it.noteId != null && it.trashedAt == null }
            .groupBy { it.noteId!! }
        val notes = dao.readNotes().map { note ->
            val sections = NoteStructure.sections(
                chapters = chaptersByNote[note.id].orEmpty()
                    .map { NoteStructure.ChapterInput(it.id, it.title, it.sortOrder) },
                episodes = episodesByNote[note.id].orEmpty().map { memo ->
                    NoteStructure.EpisodeInput(
                        memoId = memo.id,
                        title = memo.title,
                        characterCount = 0,
                        updatedAt = memo.updatedAt,
                        chapterId = memo.chapterId,
                    )
                },
            )
            val episodeBodies = episodesByNote[note.id].orEmpty().associateBy { it.id }
            PortableNote(
                id = note.id,
                title = note.title,
                subtitle = note.subtitle,
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
                coverPhoto = note.coverBlobSha256?.let { blobs[it]?.toPortable() },
                sections = sections.map { section ->
                    PortableNoteSection(
                        title = section.title,
                        episodes = section.episodes.map { episode ->
                            val memo = episodeBodies.getValue(episode.memoId)
                            PortableEpisode(
                                id = memo.id,
                                number = episode.number,
                                title = memo.title,
                                body = memo.body,
                                photos = memoPhotos[memo.id].orEmpty(),
                            )
                        },
                    )
                },
            )
        }

        return PortableSnapshot(memos = standalone, diaries = diaries, notes = notes)
    }

    private fun AttachmentBlobEntity.toPortable() = PortablePhoto(
        sha256 = sha256,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
    )
}
