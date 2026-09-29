package io.github.cragcoffee.memoripple.data

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.room.withTransaction
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object AttachmentLimits {
    const val MAX_PHOTOS_PER_RECORD = 20
    const val MAX_IMAGE_BYTES = 50L * 1024L * 1024L
    const val MAX_IMAGE_DIMENSION = 40_000
    const val MAX_IMAGE_PIXELS = 250_000_000L

    fun acceptsByteCount(sizeBytes: Long): Boolean = sizeBytes in 1..MAX_IMAGE_BYTES

    fun acceptsDimensions(widthPx: Int, heightPx: Int): Boolean =
        widthPx in 1..MAX_IMAGE_DIMENSION &&
            heightPx in 1..MAX_IMAGE_DIMENSION &&
            widthPx.toLong() * heightPx.toLong() <= MAX_IMAGE_PIXELS

    fun availablePhotoSlots(existingCount: Int): Int =
        (MAX_PHOTOS_PER_RECORD - existingCount).coerceAtLeast(0)
}

object AttachmentImageSampling {
    fun targetSampleSize(longestEdgePx: Int, targetEdgePx: Int): Int {
        if (longestEdgePx <= 0 || targetEdgePx <= 0) return 1
        return ((longestEdgePx.toLong() + targetEdgePx - 1L) / targetEdgePx)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
            .coerceAtLeast(1)
    }
}

data class PhotoImportResult(
    val added: Int,
    val duplicates: Int,
    val rejected: Int,
    val limitReached: Boolean,
    /** For a memo's photos added while writing: the text to go on writing in. */
    val writingTextId: Long? = null,
    /** The photo rows made, in the order picked (an outline places them itself). */
    val addedIds: List<Long> = emptyList(),
    /**
     * For each picture already there, the photo it already is — an outline shows one again
     * whose row was taken out while it is still being edited (docs/OUTLINE_PHOTO_ROWS.md §3).
     */
    val duplicateIds: List<Long> = emptyList(),
)

enum class PhotoReorderResult {
    CHANGED,
    UNCHANGED,
    REJECTED,
}

interface AttachmentContentSource {
    fun getType(uri: Uri): String?
    fun openInputStream(uri: Uri): InputStream?
}

class ContentResolverAttachmentSource(
    private val contentResolver: ContentResolver,
) : AttachmentContentSource {
    override fun getType(uri: Uri): String? = contentResolver.getType(uri)
    override fun openInputStream(uri: Uri): InputStream? = contentResolver.openInputStream(uri)
}

class AttachmentRepository(
    private val database: AppDatabase,
    private val dao: AttachmentDao,
    private val noteDao: NoteDao,
    private val contentResolver: ContentResolver,
    val blobStore: AttachmentBlobStore,
    private val nowMillis: () -> Long,
    private val contentSource: AttachmentContentSource = ContentResolverAttachmentSource(contentResolver),
    /** A memo's blocks: a memo photo is also a block of its content (docs/MEMO_CONTENT_BLOCKS.md). */
    private val content: MemoContentStore? = null,
    /** A journal entry's blocks, the same way (Room 27, docs/MEMO_CONTENT_BLOCKS.md §11). */
    private val diaryContent: DiaryContentStore? = null,
) {
    /**
     * The photo store's own lock ([AttachmentBlobStore.lock]) — never one of this repository's:
     * another repository over the same store (the portable import's) goes through the same one.
     */
    private val fileMutex: Mutex get() = blobStore.lock

    fun observeMemoPhotos(memoId: Long): Flow<List<PhotoAttachment>> =
        dao.observeMemoPhotos(memoId)

    fun observeMemoPhotoCounts(): Flow<List<MemoPhotoCount>> = dao.observeMemoPhotoCounts()

    fun observeDiaryPhotos(entryId: Long): Flow<List<PhotoAttachment>> =
        dao.observeDiaryPhotos(entryId)

    suspend fun memoPhotoCount(memoId: Long): Int = dao.memoPhotoCount(memoId)
    suspend fun diaryPhotoCount(entryId: Long): Int = dao.diaryPhotoCount(entryId)

    suspend fun reorderMemoPhotos(
        memoId: Long,
        orderedAttachmentIds: List<Long>,
    ): PhotoReorderResult = database.withTransaction {
        if (!dao.memoExists(memoId)) return@withTransaction PhotoReorderResult.REJECTED
        val current = dao.memoRelations(memoId)
        if (!isExactReorder(current.map(MemoPhotoAttachmentEntity::id), orderedAttachmentIds)) {
            return@withTransaction PhotoReorderResult.REJECTED
        }
        if (current.map(MemoPhotoAttachmentEntity::id) == orderedAttachmentIds) {
            return@withTransaction PhotoReorderResult.UNCHANGED
        }
        orderedAttachmentIds.forEachIndexed { sortOrder, attachmentId ->
            check(dao.updateMemoSortOrder(memoId, attachmentId, sortOrder) == 1)
        }
        // The photo slots in the text stay where they are; the pictures in them change places.
        content?.reassignPhotos(memoId, orderedAttachmentIds)
        PhotoReorderResult.CHANGED
    }

    suspend fun reorderDiaryPhotos(
        entryId: Long,
        orderedAttachmentIds: List<Long>,
    ): PhotoReorderResult = database.withTransaction {
        if (!dao.diaryEntryExists(entryId)) return@withTransaction PhotoReorderResult.REJECTED
        val current = dao.diaryRelations(entryId)
        if (!isExactReorder(current.map(DiaryPhotoAttachmentEntity::id), orderedAttachmentIds)) {
            return@withTransaction PhotoReorderResult.REJECTED
        }
        if (current.map(DiaryPhotoAttachmentEntity::id) == orderedAttachmentIds) {
            return@withTransaction PhotoReorderResult.UNCHANGED
        }
        orderedAttachmentIds.forEachIndexed { sortOrder, attachmentId ->
            check(dao.updateDiarySortOrder(entryId, attachmentId, sortOrder) == 1)
        }
        // The photo slots in the words stay where they are; the pictures in them change places.
        diaryContent?.reassignPhotos(entryId, orderedAttachmentIds)
        PhotoReorderResult.CHANGED
    }

    suspend fun installValidatedFiles(files: List<Triple<File, String, Long>>) =
        fileMutex.withLock {
            files.forEach { (file, sha256, sizeBytes) ->
                blobStore.installValidated(file, sha256, sizeBytes)
            }
        }

    /**
     * Restore's installation window: the staged blob files land on disk and [andThen] — the
     * Room transaction that writes the rows referencing them — runs while the file lock is
     * still held, so a garbage collection scheduled in between cannot sweep the files away
     * before their rows exist.
     */
    suspend fun <T> installForRestore(
        files: List<Triple<File, String, Long>>,
        andThen: suspend () -> T,
    ): T = fileMutex.withLock {
        files.forEach { (file, sha256, sizeBytes) ->
            blobStore.installValidated(file, sha256, sizeBytes)
        }
        andThen()
    }

    /**
     * Photos for a memo. With [writingTextId] (the text being written) they go where its [caret]
     * is — its end when null — and writing goes on below them; [PhotoImportResult.writingTextId]
     * names where. Without it (an import) they join the photos at the top, as a memo's photos
     * always sat. [activeCount] is how many photos the record shows now, when that is not every
     * photo it holds: an outline counts only its photo rows — a photo kept for 元に戻す is not one
     * (docs/OUTLINE_PHOTO_ROWS.md §7.3).
     */
    suspend fun importMemoPhotos(
        memoId: Long,
        uris: List<Uri>,
        writingTextId: Long? = null,
        caret: Int? = null,
        activeCount: Int? = null,
    ): PhotoImportResult {
        var writing = writingTextId
        var at = caret
        val result = importPhotos(
            uris = uris,
            existingCount = { activeCount ?: dao.memoPhotoCount(memoId) },
            existing = { sha -> dao.memoRelations(memoId).firstOrNull { it.blobSha256 == sha }?.id },
            insert = { metadata ->
                val order = dao.nextMemoSortOrder(memoId)
                val relationId = dao.insertMemoRelation(
                    MemoPhotoAttachmentEntity(
                        memoId = memoId,
                        blobSha256 = metadata.sha256,
                        sortOrder = order,
                        createdAt = metadata.createdAt,
                    ),
                )
                if (relationId > 0) {
                    val next = content?.placePhoto(memoId, relationId, writing, at)
                    if (writing != null) writing = next ?: writing
                    // The next photo goes in front of the text writing went on in: after this one.
                    at = 0
                }
                relationId
            },
        )
        return result.copy(writingTextId = writing?.takeIf { writingTextId != null })
    }

    /**
     * Photos for a journal entry (Room 27): with [writingTextId] they go where its [caret] is — its
     * end when null — and writing goes on below them ([PhotoImportResult.writingTextId]); without
     * it they join the photos at the end, under the words. A LOCKED entry takes none.
     */
    suspend fun importDiaryPhotos(entryId: Long, uris: List<Uri>, writingTextId: Long? = null, caret: Int? = null): PhotoImportResult {
        if (diaryContent?.isLocked(entryId) == true) {
            return PhotoImportResult(added = 0, duplicates = 0, rejected = uris.size, limitReached = false)
        }
        var writing = writingTextId
        var at = caret
        val result = importPhotos(
            uris = uris,
            existingCount = { dao.diaryPhotoCount(entryId) },
            insert = { metadata ->
                val order = dao.nextDiarySortOrder(entryId)
                val relationId = dao.insertDiaryRelation(
                    DiaryPhotoAttachmentEntity(
                        diaryEntryId = entryId,
                        blobSha256 = metadata.sha256,
                        sortOrder = order,
                        createdAt = metadata.createdAt,
                    ),
                )
                if (relationId > 0) {
                    val next = diaryContent?.placePhoto(entryId, relationId, writing, at)
                    if (writing != null) writing = next ?: writing
                    // The next photo goes in front of the text writing went on in: after this one.
                    at = 0
                }
                relationId
            },
        )
        return result.copy(writingTextId = writing?.takeIf { writingTextId != null })
    }

    fun observeNoteCover(noteId: Long): Flow<PhotoAttachment?> = dao.observeNoteCover(noteId)

    fun observeAllNoteCovers(): Flow<List<PhotoAttachment>> = dao.observeAllNoteCovers()

    /**
     * Puts one picture on a note's cover, replacing whatever was there.
     *
     * A cover holds one picture, so this is a replacement rather than an addition: the blob is
     * written, the note is pointed at it, and whatever the note pointed at before is collected if
     * nothing else wants it.
     */
    suspend fun setNoteCover(noteId: Long, uri: Uri): Boolean {
        // File and row land under one hold of the file lock, the way importPhotos already
        // works: released between the two, a concurrently scheduled garbage collection would
        // see a file no row references yet and sweep it away — leaving the note pointing at
        // a picture that no longer exists.
        val stored = fileMutex.withLock {
            val metadata = runCatching { importOne(uri) }.getOrNull()
                ?: return@withLock false
            val committed = runCatching {
                database.withTransaction {
                    dao.insertBlob(metadata)
                    noteDao.setCoverPhoto(noteId, metadata.sha256, metadata.createdAt)
                }
            }.getOrNull()
            if (committed == null || committed == 0) {
                garbageCollectAfterFailure(metadata.sha256)
                false
            } else {
                true
            }
        }
        if (stored) garbageCollect()
        return stored
    }

    suspend fun clearNoteCover(noteId: Long, now: Long) {
        database.withTransaction { noteDao.setCoverPhoto(noteId, null, now) }
        garbageCollect()
    }

    suspend fun deleteMemoPhoto(memoId: Long, attachmentId: Long) {
        // Delete and gap-close are one transaction: no observer ever sees a hole in the
        // 0..n-1 order the backup validator (and the reorder sheet) rely on.
        database.withTransaction {
            dao.deleteMemoRelationAndNormalize(memoId, attachmentId)
            // Its block went with it; the text around it stays as it was.
            content?.afterPhotoDeleted(memoId)
        }
        garbageCollect()
    }

    /**
     * An outline's photos that no photo row shows any more, let go when the outliner's session
     * ends (docs/OUTLINE_PHOTO_ROWS.md §3): each is checked again inside the transaction and only
     * a photo still shown by no row is removed; the files follow through the usual collection.
     */
    suspend fun releaseUnshownOutlinePhotos(memoId: Long, unshown: suspend () -> List<Long>) {
        val released = database.withTransaction {
            unshown().onEach { dao.deleteMemoRelationAndNormalize(memoId, it) }
        }
        if (released.isNotEmpty()) garbageCollect()
    }

    suspend fun deleteDiaryPhoto(entryId: Long, attachmentId: Long) {
        database.withTransaction {
            dao.deleteDiaryRelationAndNormalize(entryId, attachmentId)
            // Its block went with it; the words around it stay as they were.
            diaryContent?.afterPhotoDeleted(entryId)
        }
        garbageCollect()
    }

    // On IO: callers include Main-dispatched view-model scopes, and the sweep below lists and
    // deletes real files — work that has no business on the UI thread.
    suspend fun garbageCollect() = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            blobStore.cleanTemporaryFiles()
            dao.unreferencedBlobShas().forEach { sha ->
                if (dao.deleteBlobIfUnreferenced(sha) > 0) blobStore.remove(sha)
            }
            val known = dao.allBlobShas().toSet()
            val blobRoot = blobStore.blobFile("0".repeat(64)).parentFile
            blobRoot?.listFiles()?.filter { file ->
                file.isFile &&
                    AttachmentBlobStore.SHA256_PATTERN.matches(file.name) &&
                    file.name !in known
            }?.forEach { it.delete() }
        }
    }

    private suspend fun importPhotos(
        uris: List<Uri>,
        existingCount: suspend () -> Int,
        existing: suspend (sha256: String) -> Long? = { null },
        insert: suspend (AttachmentBlobEntity) -> Long,
    ): PhotoImportResult = fileMutex.withLock {
        var added = 0
        val addedIds = ArrayList<Long>()
        val duplicateIds = ArrayList<Long>()
        var duplicates = 0
        var rejected = 0
        val available = AttachmentLimits.availablePhotoSlots(existingCount())
        val selected = uris.take(available)
        selected.forEach { uri ->
            val metadata = runCatching { importOne(uri) }.getOrNull()
            if (metadata == null) {
                rejected += 1
                return@forEach
            }
            val relationId = try {
                database.withTransaction {
                    dao.insertBlob(metadata)
                    insert(metadata)
                }
            } catch (_: Exception) {
                rejected += 1
                garbageCollectAfterFailure(metadata.sha256)
                return@forEach
            }
            if (relationId < 0) {
                duplicates += 1
                existing(metadata.sha256)?.let { duplicateIds += it }
            } else {
                added += 1
                addedIds += relationId
            }
        }
        rejected += (uris.size - selected.size)
        garbageCollectAfterFailure(null)
        PhotoImportResult(
            added = added,
            duplicates = duplicates,
            rejected = rejected,
            limitReached = uris.size > available,
            addedIds = addedIds,
            duplicateIds = duplicateIds,
        )
    }

    private suspend fun importOne(uri: Uri): AttachmentBlobEntity = withContext(Dispatchers.IO) {
        val mimeType = contentSource.getType(uri)
            ?.takeIf { it.startsWith("image/") }
            ?: throw PhotoImportException("Not an image")
        val temp = blobStore.newTempFile()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            contentSource.openInputStream(uri)?.use { input ->
                DigestOutputStream(temp.outputStream(), digest).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > AttachmentLimits.MAX_IMAGE_BYTES) {
                            throw PhotoImportException("Image is too large")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw PhotoImportException("Unreadable image")
            if (!AttachmentLimits.acceptsByteCount(total)) throw PhotoImportException("Empty image")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            var width = 0
            var height = 0
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(temp)) { decoder, info, _ ->
                width = info.size.width
                height = info.size.height
                if (!AttachmentLimits.acceptsDimensions(width, height)) {
                    throw PhotoImportException("Unsupported image dimensions")
                }
                val sample = AttachmentImageSampling.targetSampleSize(maxOf(width, height), 128)
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }.recycle()
            blobStore.installValidated(temp, sha, total)
            AttachmentBlobEntity(
                sha256 = sha,
                kind = AttachmentKind.IMAGE,
                mimeType = mimeType,
                sizeBytes = total,
                widthPx = width,
                heightPx = height,
                createdAt = nowMillis(),
            )
        } catch (error: Exception) {
            temp.delete()
            if (error is PhotoImportException) throw error
            if (error is IOException) throw PhotoImportException("Unreadable image", error)
            throw PhotoImportException("Invalid image", error)
        }
    }

    private suspend fun garbageCollectAfterFailure(candidateSha: String?) {
        if (candidateSha != null && dao.deleteBlobIfUnreferenced(candidateSha) > 0) {
            blobStore.remove(candidateSha)
        }
        blobStore.cleanTemporaryFiles()
    }
}

private fun isExactReorder(currentIds: List<Long>, requestedIds: List<Long>): Boolean =
    currentIds.size == requestedIds.size &&
        requestedIds.distinct().size == requestedIds.size &&
        currentIds.toSet() == requestedIds.toSet()

private class PhotoImportException(message: String, cause: Throwable? = null) :
    IOException(message, cause)
