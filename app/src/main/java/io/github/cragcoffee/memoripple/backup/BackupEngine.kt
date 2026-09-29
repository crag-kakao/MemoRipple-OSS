package io.github.cragcoffee.memoripple.backup

import androidx.room.withTransaction
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.BackupDao
import io.github.cragcoffee.memoripple.data.DiaryRepository
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentRepository
import io.github.cragcoffee.memoripple.data.SettingsRepository
import io.github.cragcoffee.memoripple.data.TemplateRepository
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentImageSampling
import io.github.cragcoffee.memoripple.data.AttachmentRepository
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import android.graphics.ImageDecoder

class PreparedBackup(
    val file: File,
    val exportedAt: Long,
    val suggestedFileName: String,
    private val deleteOnDiscard: Boolean = true,
) {
    constructor(bytes: ByteArray, exportedAt: Long, suggestedFileName: String) : this(
        file = kotlin.io.path.createTempFile("memorripple-test-", BACKUP_EXTENSION).toFile().apply {
            writeBytes(bytes)
            deleteOnExit()
        },
        exportedAt = exportedAt,
        suggestedFileName = suggestedFileName,
        deleteOnDiscard = false,
    )
    /** Compatibility helper for focused tests; production callers stream [file]. */
    val bytes: ByteArray get() = file.readBytes()
    fun discard() { if (deleteOnDiscard) file.delete() }
}

data class RestorePreview(
    val exportedAt: Long,
    val memoCount: Int,
    val memoCommentCount: Int,
    val diaryCount: Int,
    val futureCommentCount: Int,
    val photoCount: Int = 0,
    val photoBytes: Long = 0L,
    val hasSettings: Boolean = true,
)

class RestoreCandidate internal constructor(
    internal val document: MemoRippleBackupDto,
    val preview: RestorePreview,
    internal val stagedBlobs: Map<String, File> = emptyMap(),
    private val stagingDirectory: File? = null,
) {
    fun discard() { stagingDirectory?.deleteRecursively() }
}

enum class BackupInspectionError {
    INVALID_FILE,
    FILE_TOO_LARGE,
    WRONG_FORMAT,
    UNSUPPORTED_VERSION,
    INVALID_CONTENT,
}

sealed interface BackupInspectionResult {
    data class Ready(val candidate: RestoreCandidate) : BackupInspectionResult
    data class Rejected(val error: BackupInspectionError) : BackupInspectionResult
}

sealed interface BackupRestoreResult {
    data object Success : BackupRestoreResult
    data object InvalidCandidate : BackupRestoreResult
    data object RoomFailure : BackupRestoreResult
    data object SettingsFailure : BackupRestoreResult
}

class BackupEngine(
    private val database: AppDatabase,
    private val backupDao: BackupDao,
    private val settingsRepository: SettingsRepository,
    private val diaryRepository: DiaryRepository,
    private val futureDiaryCommentRepository: FutureDiaryCommentRepository,
    private val timeProvider: TimeProvider,
    private val appVersionName: String,
    private val appVersionCode: Long,
    private val blobStore: AttachmentBlobStore = AttachmentBlobStore(
        File(System.getProperty("java.io.tmpdir"), "memorripple-test-files"),
    ),
    private val attachmentRepository: AttachmentRepository? = null,
    private val cacheDirectory: File = File(
        System.getProperty("java.io.tmpdir"),
        "memorripple-test-cache",
    ),
    private val validator: BackupValidator = BackupValidator(),
    private val container: BackupContainer = BackupContainer(),
    private val templateRepository: TemplateRepository? = null,
    private val templateFolderRepository: io.github.cragcoffee.memoripple.data.TemplateFolderRepository? = null,
) {
    suspend fun prepareBackup(): PreparedBackup = withContext(Dispatchers.IO) {
        val exportedAt = timeProvider.nowMillis()
        val document = BackupMapper.toDocument(
            snapshot = backupDao.snapshot(),
            settings = settingsRepository.settings.first(),
            exportedAt = exportedAt,
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            templates = templateRepository?.current().orEmpty(),
            templateFolders = templateFolderRepository?.current().orEmpty(),
        )
        val directory = File(cacheDirectory, "backup").apply { mkdirs() }
        val output = File(directory, "backup-${UUID.randomUUID()}$BACKUP_EXTENSION")
        try {
            container.write(document, blobStore, output)
        } catch (error: Exception) {
            output.delete()
            throw error
        }
        PreparedBackup(
            file = output,
            exportedAt = exportedAt,
            suggestedFileName = suggestedFileName(exportedAt),
        )
    }

    suspend fun inspect(bytes: ByteArray): BackupInspectionResult =
        withContext(Dispatchers.IO) {
            val temp = File(cacheDirectory, "backup/inspect-${UUID.randomUUID()}.mrbackup")
            temp.parentFile?.mkdirs()
            try {
                temp.writeBytes(bytes)
                inspect(temp)
            } finally {
                temp.delete()
            }
        }

    suspend fun inspect(file: File): BackupInspectionResult = withContext(Dispatchers.IO) {
        when (val decoded = container.read(file, File(cacheDirectory, "backup/restore"))) {
            is BackupContainerReadResult.Failure -> BackupInspectionResult.Rejected(
                when (decoded.error) {
                    BackupContainerError.FILE_TOO_LARGE,
                    BackupContainerError.ENTRY_TOO_LARGE,
                    BackupContainerError.TOO_MANY_ENTRIES,
                    -> BackupInspectionError.FILE_TOO_LARGE
                    BackupContainerError.UNSUPPORTED_VERSION ->
                        BackupInspectionError.UNSUPPORTED_VERSION
                    else -> BackupInspectionError.INVALID_FILE
                },
            )
            is BackupContainerReadResult.Success -> inspectDocument(
                document = decoded.document,
                stagedBlobs = decoded.stagedBlobs,
                stagingDirectory = decoded.stagingDirectory,
            )
        }
    }

    suspend fun restore(candidate: RestoreCandidate): BackupRestoreResult =
        withContext(Dispatchers.IO) {
            if (validator.validate(candidate.document) != BackupValidationResult.Valid) {
                candidate.discard()
                return@withContext BackupRestoreResult.InvalidCandidate
            }
            val installs = mutableListOf<Triple<File, String, Long>>()
            try {
                candidate.document.payload.attachmentBlobs.forEach { metadata ->
                    val staged = candidate.stagedBlobs[metadata.sha256]
                        ?: return@withContext BackupRestoreResult.InvalidCandidate
                    validateImage(staged, metadata)
                    installs += Triple(staged, metadata.sha256, metadata.sizeBytes)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                candidate.discard()
                attachmentRepository?.garbageCollect()
                return@withContext BackupRestoreResult.InvalidCandidate
            }
            val previousSettings = settingsRepository.settings.first()
            val restoredSettings = BackupMapper.toAppSettings(candidate.document)
            val snapshot = BackupMapper.toRoomSnapshot(candidate.document)
            // Files and rows land as one held step. The repository locks come first and the
            // database transaction last — the same lock-then-write order every other caller
            // uses — and the attachment file lock spans both installation and the transaction,
            // so a garbage collection scheduled mid-restore can neither sweep the just-installed
            // files (no rows yet) nor deadlock against the expiry sweeps inside the transaction.
            val writeRoom: suspend () -> Unit = {
                diaryRepository.withOperationLock {
                    futureDiaryCommentRepository.withOperationLock {
                        database.withTransaction {
                            backupDao.replaceAll(snapshot)
                            futureDiaryCommentRepository.markDueDeliveredLockHeld()
                        }
                    }
                }
            }
            try {
                if (attachmentRepository != null) {
                    attachmentRepository.installForRestore(installs, writeRoom)
                } else {
                    // The photo store's one lock, as the repository takes it (M_file → … → T).
                    blobStore.lock.withLock {
                        installs.forEach { (file, sha, size) ->
                            blobStore.installValidated(file, sha, size)
                        }
                        writeRoom()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                candidate.discard()
                attachmentRepository?.garbageCollect()
                return@withContext BackupRestoreResult.RoomFailure
            }

            try {
                settingsRepository.replaceAll(restoredSettings)
                // Templates travel with the settings (format 18); a file without them restores none.
                templateRepository?.replaceAll(BackupMapper.toTemplates(candidate.document))
                // and their folders (2026-09-22): a file before them restores none, which leaves every template 未分類
                templateFolderRepository?.replaceAll(BackupMapper.toTemplateFolders(candidate.document))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                try {
                    settingsRepository.replaceAll(previousSettings)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The original Settings failure is reported without touching restored Room data.
                }
                candidate.discard()
                attachmentRepository?.garbageCollect()
                return@withContext BackupRestoreResult.SettingsFailure
            }
            candidate.discard()
            attachmentRepository?.garbageCollect()
            BackupRestoreResult.Success
        }

    private fun inspectDocument(
        document: MemoRippleBackupDto,
        stagedBlobs: Map<String, File> = emptyMap(),
        stagingDirectory: File? = null,
    ): BackupInspectionResult {
        return when (val validation = validator.validate(document)) {
            BackupValidationResult.Valid -> BackupInspectionResult.Ready(
                RestoreCandidate(
                    document = document,
                    preview = RestorePreview(
                        exportedAt = document.exportedAt,
                        memoCount = document.payload.memos.size,
                        memoCommentCount = document.payload.memoComments.size,
                        diaryCount = document.payload.diaryEntries.size,
                        futureCommentCount = document.payload.futureDiaryComments.size,
                        photoCount = document.payload.memoPhotoAttachments.size +
                            document.payload.diaryPhotoAttachments.size,
                        photoBytes = document.payload.attachmentBlobs.sumOf { it.sizeBytes },
                    ),
                    stagedBlobs = stagedBlobs,
                    stagingDirectory = stagingDirectory,
                ),
            )
            is BackupValidationResult.Invalid -> BackupInspectionResult.Rejected(
                when {
                    BackupValidationIssue.UNSUPPORTED_VERSION in validation.issues ->
                        BackupInspectionError.UNSUPPORTED_VERSION
                    BackupValidationIssue.WRONG_FORMAT in validation.issues ->
                        BackupInspectionError.WRONG_FORMAT
                    else -> BackupInspectionError.INVALID_CONTENT
                },
            )
        }.also { result ->
            if (result is BackupInspectionResult.Rejected) stagingDirectory?.deleteRecursively()
        }
    }

    private fun suggestedFileName(exportedAt: Long): String {
        val formatted = FILE_NAME_FORMATTER.format(
            Instant.ofEpochMilli(exportedAt).atZone(ZoneId.systemDefault()),
        )
        return "MemoRipple-backup-$formatted$BACKUP_EXTENSION"
    }

    private fun validateImage(file: File, metadata: AttachmentBlobBackupDto) {
        var width = 0
        var height = 0
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            width = info.size.width
            height = info.size.height
            decoder.setTargetSampleSize(
                AttachmentImageSampling.targetSampleSize(maxOf(width, height), 128),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        bitmap.recycle()
        require(width == metadata.widthPx && height == metadata.heightPx) {
            "Attachment dimensions do not match manifest"
        }
    }

    private companion object {
        val FILE_NAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
