package io.github.cragcoffee.memoripple.backup

import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentLimits
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object BackupV10Limits {
    const val MAX_MANIFEST_BYTES = 8L * 1024L * 1024L
    const val MAX_ARCHIVE_ENTRIES = 10_001
    const val MAX_TOTAL_BLOB_BYTES = 2L * 1024L * 1024L * 1024L
    const val MAX_CONTAINER_BYTES = MAX_TOTAL_BLOB_BYTES + MAX_MANIFEST_BYTES + 64L * 1024L * 1024L

    fun acceptsManifestBytes(sizeBytes: Long): Boolean = sizeBytes in 0..MAX_MANIFEST_BYTES

    fun acceptsBlobBytes(sizeBytes: Long): Boolean =
        sizeBytes in 1..AttachmentLimits.MAX_IMAGE_BYTES

    fun acceptsEntryCount(entryCount: Int): Boolean = entryCount in 1..MAX_ARCHIVE_ENTRIES

    fun canAddBlobBytes(currentTotal: Long, nextSize: Long): Boolean =
        currentTotal in 0..MAX_TOTAL_BLOB_BYTES &&
            nextSize in 0..MAX_TOTAL_BLOB_BYTES - currentTotal
}
enum class BackupContainerError {
    EMPTY,
    FILE_TOO_LARGE,
    INVALID_CONTAINER,
    MISSING_MANIFEST,
    INVALID_MANIFEST,
    UNSUPPORTED_VERSION,
    UNSAFE_ENTRY,
    DUPLICATE_ENTRY,
    TOO_MANY_ENTRIES,
    ENTRY_TOO_LARGE,
    UNEXPECTED_ENTRY,
    MISSING_BLOB,
    BLOB_MISMATCH,
}

sealed interface BackupContainerReadResult {
    data class Success(
        val document: MemoRippleBackupDto,
        val stagedBlobs: Map<String, File>,
        val stagingDirectory: File?,
    ) : BackupContainerReadResult

    data class Failure(val error: BackupContainerError) : BackupContainerReadResult
}

class BackupContainer(
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    },
    private val legacyCodec: BackupCodec = BackupCodec(),
    private val validator: BackupValidator = BackupValidator(),
) {
    /**
     * Writes a container backup. The container shape arrived at version 10 and every version since
     * uses it; anything older is a plain compressed document and goes through the legacy codec.
     */
    suspend fun write(
        document: MemoRippleBackupDto,
        blobStore: AttachmentBlobStore,
        destination: File,
    ) {
        require(document.formatVersion in CONTAINER_MIN_FORMAT_VERSION..BACKUP_FORMAT_VERSION)
        require(validator.validate(document) == BackupValidationResult.Valid)
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            writeEntry(zip, MANIFEST_NAME) { output ->
                output.write(json.encodeToString(document).toByteArray(Charsets.UTF_8))
            }
            document.payload.attachmentBlobs.sortedBy { it.sha256 }.forEach { metadata ->
                require(blobStore.isValid(metadata.sha256, metadata.sizeBytes)) {
                    "Attachment blob is missing or corrupt"
                }
                writeEntry(zip, "$BLOB_PREFIX${metadata.sha256}") { output ->
                    blobStore.blobFile(metadata.sha256).inputStream().buffered().use { input ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    fun read(source: File, stagingRoot: File): BackupContainerReadResult {
        if (!source.isFile || source.length() == 0L) {
            return BackupContainerReadResult.Failure(BackupContainerError.EMPTY)
        }
        if (source.length() > BackupV10Limits.MAX_CONTAINER_BYTES) {
            return BackupContainerReadResult.Failure(BackupContainerError.FILE_TOO_LARGE)
        }
        val magic = source.inputStream().use { input -> byteArrayOf(input.read().toByte(), input.read().toByte()) }
        return when {
            magic[0] == GZIP_FIRST && magic[1] == GZIP_SECOND -> readLegacy(source)
            magic[0] == ZIP_FIRST && magic[1] == ZIP_SECOND -> readV10(source, stagingRoot)
            else -> BackupContainerReadResult.Failure(BackupContainerError.INVALID_CONTAINER)
        }
    }

    private fun readLegacy(source: File): BackupContainerReadResult {
        if (source.length() > BackupCodec.MAX_COMPRESSED_BYTES) {
            return BackupContainerReadResult.Failure(BackupContainerError.FILE_TOO_LARGE)
        }
        return when (val decoded = legacyCodec.decode(source.readBytes())) {
            is BackupDecodeResult.Failure -> BackupContainerReadResult.Failure(
                when (decoded.error) {
                    BackupDecodeError.COMPRESSED_FILE_TOO_LARGE,
                    BackupDecodeError.UNCOMPRESSED_DATA_TOO_LARGE,
                    -> BackupContainerError.FILE_TOO_LARGE
                    else -> BackupContainerError.INVALID_CONTAINER
                },
            )
            is BackupDecodeResult.Success -> {
                if (decoded.document.formatVersion >= CONTAINER_MIN_FORMAT_VERSION) {
                    BackupContainerReadResult.Failure(BackupContainerError.INVALID_CONTAINER)
                } else {
                    BackupContainerReadResult.Success(decoded.document, emptyMap(), null)
                }
            }
        }
    }

    private fun readV10(source: File, stagingRoot: File): BackupContainerReadResult {
        val staging = File(stagingRoot, "restore-${UUID.randomUUID()}")
        staging.mkdirs()
        val names = hashSetOf<String>()
        val staged = linkedMapOf<String, File>()
        var manifestBytes: ByteArray? = null
        var entryCount = 0
        var totalBlobBytes = 0L
        try {
            // ZipInputStream can read local entries from an archive whose central directory was
            // truncated. Opening ZipFile first makes structural completeness part of the restore
            // contract while the streaming pass below still enforces content and SHA limits.
            ZipFile(source).use { zipFile ->
                if (!zipFile.entries().hasMoreElements()) {
                    return failureAndDelete(staging, BackupContainerError.INVALID_CONTAINER)
                }
            }
            ZipInputStream(source.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    if (!BackupV10Limits.acceptsEntryCount(entryCount)) {
                        return failureAndDelete(staging, BackupContainerError.TOO_MANY_ENTRIES)
                    }
                    val name = entry.name
                    if (!isSafeEntryName(name)) {
                        return failureAndDelete(staging, BackupContainerError.UNSAFE_ENTRY)
                    }
                    if (entry.isDirectory || !names.add(name)) {
                        return failureAndDelete(staging, BackupContainerError.DUPLICATE_ENTRY)
                    }
                    when {
                        name == MANIFEST_NAME -> {
                            manifestBytes = readLimited(zip, BackupV10Limits.MAX_MANIFEST_BYTES)
                                ?: return failureAndDelete(
                                    staging,
                                    BackupContainerError.ENTRY_TOO_LARGE,
                                )
                        }
                        name.startsWith(BLOB_PREFIX) -> {
                            val sha = name.removePrefix(BLOB_PREFIX)
                            if (!AttachmentBlobStore.SHA256_PATTERN.matches(sha)) {
                                return failureAndDelete(staging, BackupContainerError.UNSAFE_ENTRY)
                            }
                            val file = File(staging, sha)
                            val size = copyLimited(zip, file, AttachmentLimits.MAX_IMAGE_BYTES)
                                ?: return failureAndDelete(
                                    staging,
                                    BackupContainerError.ENTRY_TOO_LARGE,
                                )
                            if (!BackupV10Limits.canAddBlobBytes(totalBlobBytes, size)) {
                                return failureAndDelete(
                                    staging,
                                    BackupContainerError.ENTRY_TOO_LARGE,
                                )
                            }
                            totalBlobBytes += size
                            staged[sha] = file
                        }
                        else -> return failureAndDelete(
                            staging,
                            BackupContainerError.UNEXPECTED_ENTRY,
                        )
                    }
                    zip.closeEntry()
                }
            }
        } catch (_: IOException) {
            return failureAndDelete(staging, BackupContainerError.INVALID_CONTAINER)
        }
        val manifest = manifestBytes
            ?: return failureAndDelete(staging, BackupContainerError.MISSING_MANIFEST)
        val document = try {
            json.decodeFromString<MemoRippleBackupDto>(manifest.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return failureAndDelete(staging, BackupContainerError.INVALID_MANIFEST)
        } catch (_: IllegalArgumentException) {
            return failureAndDelete(staging, BackupContainerError.INVALID_MANIFEST)
        }
        if (document.formatVersion !in CONTAINER_MIN_FORMAT_VERSION..BACKUP_FORMAT_VERSION) {
            return failureAndDelete(staging, BackupContainerError.UNSUPPORTED_VERSION)
        }
        if (validator.validate(document) != BackupValidationResult.Valid) {
            return failureAndDelete(staging, BackupContainerError.INVALID_MANIFEST)
        }
        val expected = document.payload.attachmentBlobs.associateBy { it.sha256 }
        if (expected.keys != staged.keys) {
            return failureAndDelete(staging, BackupContainerError.MISSING_BLOB)
        }
        expected.forEach { (sha, metadata) ->
            val file = staged.getValue(sha)
            if (file.length() != metadata.sizeBytes || AttachmentBlobStore.hash(file) != sha) {
                return failureAndDelete(staging, BackupContainerError.BLOB_MISMATCH)
            }
        }
        return BackupContainerReadResult.Success(document, staged, staging)
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, block: (ZipOutputStream) -> Unit) {
        val entry = ZipEntry(name).apply { time = 0L }
        zip.putNextEntry(entry)
        block(zip)
        zip.closeEntry()
    }

    private fun isSafeEntryName(name: String): Boolean =
        name.isNotBlank() && !name.startsWith('/') && !name.startsWith('\\') &&
            '\\' !in name && name.split('/').none { it == ".." || it == "." || it.isBlank() }

    private fun readLimited(input: ZipInputStream, limit: Long): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun copyLimited(input: ZipInputStream, destination: File, limit: Long): Long? {
        var total = 0L
        destination.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) return null
                output.write(buffer, 0, count)
            }
        }
        return total
    }

    private fun failureAndDelete(
        staging: File,
        error: BackupContainerError,
    ): BackupContainerReadResult.Failure {
        staging.deleteRecursively()
        return BackupContainerReadResult.Failure(error)
    }

    private companion object {
        const val MANIFEST_NAME = "manifest.json"
        const val BLOB_PREFIX = "blobs/"
        const val GZIP_FIRST: Byte = 0x1f
        const val GZIP_SECOND: Byte = 0x8b.toByte()
        const val ZIP_FIRST: Byte = 0x50
        const val ZIP_SECOND: Byte = 0x4b
    }
}
