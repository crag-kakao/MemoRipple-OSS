package io.github.cragcoffee.memoripple.backup

import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupContainerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun v10RoundTripKeepsRawDeduplicatedBlobAndRelations() = runBlocking {
        val files = temporaryFolder.newFolder("files")
        val stage = temporaryFolder.newFolder("stage")
        val store = AttachmentBlobStore(files)
        val bytes = "original-photo-binary".toByteArray()
        val source = temporaryFolder.newFile("source.jpg").apply { writeBytes(bytes) }
        val sha = AttachmentBlobStore.hash(source)
        store.installValidated(source, sha, bytes.size.toLong())
        val document = fullBackupFixture().copy(
            payload = fullBackupFixture().payload.copy(
                attachmentBlobs = listOf(
                    AttachmentBlobBackupDto(
                        sha, "image", "image/jpeg", bytes.size.toLong(), 100, 80, 700,
                    ),
                ),
                memoPhotoAttachments = listOf(
                    MemoPhotoAttachmentBackupDto(700, 10, sha, 0, 701),
                ),
                diaryPhotoAttachments = listOf(
                    DiaryPhotoAttachmentBackupDto(800, 30, sha, 0, 702),
                ),
            ),
        )
        val archive = temporaryFolder.newFile("backup.mrbackup")

        BackupContainer().write(document, store, archive)
        val result = BackupContainer().read(archive, stage) as BackupContainerReadResult.Success

        assertEquals(document, result.document)
        assertEquals(setOf(sha), result.stagedBlobs.keys)
        assertArrayEquals(bytes, result.stagedBlobs.getValue(sha).readBytes())
        result.stagingDirectory?.deleteRecursively()
        Unit
    }

    @Test
    fun v10RoundTripKeepsTwentyRelationsAndTwentyDeduplicatedEntries() = runBlocking {
        val files = temporaryFolder.newFolder("twenty-files")
        val stage = temporaryFolder.newFolder("twenty-stage")
        val store = AttachmentBlobStore(files)
        val blobs = (0 until 20).map { index ->
            val bytes = "photo-$index".toByteArray()
            val source = temporaryFolder.newFile("photo-$index.bin").apply { writeBytes(bytes) }
            val sha = AttachmentBlobStore.hash(source)
            store.installValidated(source, sha, bytes.size.toLong())
            AttachmentBlobBackupDto(sha, "image", "image/png", bytes.size.toLong(), 1, 1, index.toLong())
        }
        val base = fullBackupFixture()
        val document = base.copy(
            payload = base.payload.copy(
                attachmentBlobs = blobs,
                memoPhotoAttachments = blobs.mapIndexed { index, blob ->
                    MemoPhotoAttachmentBackupDto(
                        id = 1_000L + index,
                        memoId = base.payload.memos.first().id,
                        blobSha256 = blob.sha256,
                        sortOrder = index,
                        createdAt = index.toLong(),
                    )
                },
            ),
        )
        val archive = temporaryFolder.newFile("twenty.mrbackup")

        BackupContainer().write(document, store, archive)
        val result = BackupContainer().read(archive, stage) as BackupContainerReadResult.Success

        assertEquals(20, result.document.payload.memoPhotoAttachments.size)
        assertEquals(20, result.stagedBlobs.size)
        assertEquals(21, ZipFile(archive).use { it.size() })
        result.stagingDirectory?.deleteRecursively()
        Unit
    }

    @Test
    fun legacyV9GzipRemainsReadable() {
        val document = fullBackupFixture().copy(formatVersion = 9)
        val file = temporaryFolder.newFile("legacy.mrbackup").apply {
            writeBytes(BackupCodec().encode(document))
        }

        val result = BackupContainer().read(file, temporaryFolder.newFolder("legacy-stage"))

        assertEquals(document, (result as BackupContainerReadResult.Success).document)
        assertTrue(result.stagedBlobs.isEmpty())
    }

    @Test
    fun unsafeZipEntryIsRejectedBeforeManifestUse() {
        val file = temporaryFolder.newFile("unsafe.mrbackup")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../outside"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }

        val result = BackupContainer().read(file, temporaryFolder.newFolder("unsafe-stage"))

        assertEquals(
            BackupContainerReadResult.Failure(BackupContainerError.UNSAFE_ENTRY),
            result,
        )
    }

    @Test
    fun aManifestNewerThanThisAppKnowsIsRejected() {
        val file = temporaryFolder.newFile("future.mrbackup")
        val manifest = Json { encodeDefaults = true; explicitNulls = true }
            .encodeToString(fullBackupFixture().copy(formatVersion = BACKUP_FORMAT_VERSION + 1))
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
        }

        val result = BackupContainer().read(file, temporaryFolder.newFolder("future-stage"))

        assertEquals(
            BackupContainerReadResult.Failure(BackupContainerError.UNSUPPORTED_VERSION),
            result,
        )
    }

    @Test
    fun limitPolicyIsInclusiveAndOverflowSafeWithoutLargeFixtures() {
        assertTrue(BackupV10Limits.acceptsManifestBytes(BackupV10Limits.MAX_MANIFEST_BYTES))
        assertTrue(!BackupV10Limits.acceptsManifestBytes(BackupV10Limits.MAX_MANIFEST_BYTES + 1))
        assertTrue(BackupV10Limits.acceptsBlobBytes(50L * 1024L * 1024L))
        assertTrue(!BackupV10Limits.acceptsBlobBytes(50L * 1024L * 1024L + 1))
        assertTrue(BackupV10Limits.acceptsEntryCount(BackupV10Limits.MAX_ARCHIVE_ENTRIES))
        assertTrue(!BackupV10Limits.acceptsEntryCount(BackupV10Limits.MAX_ARCHIVE_ENTRIES + 1))
        assertTrue(
            BackupV10Limits.canAddBlobBytes(
                BackupV10Limits.MAX_TOTAL_BLOB_BYTES - 1,
                1,
            ),
        )
        assertTrue(
            !BackupV10Limits.canAddBlobBytes(
                BackupV10Limits.MAX_TOTAL_BLOB_BYTES,
                1,
            ),
        )
        assertTrue(!BackupV10Limits.canAddBlobBytes(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun oversizedManifestIsRejectedWithoutChangingAnyDatabase() {
        val file = temporaryFolder.newFile("large-manifest.mrbackup")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            val chunk = ByteArray(8 * 1024)
            repeat((BackupV10Limits.MAX_MANIFEST_BYTES / chunk.size).toInt() + 1) {
                zip.write(chunk)
            }
            zip.closeEntry()
        }

        val result = BackupContainer().read(file, temporaryFolder.newFolder("large-manifest-stage"))

        assertEquals(
            BackupContainerReadResult.Failure(BackupContainerError.ENTRY_TOO_LARGE),
            result,
        )
    }

    @Test
    fun truncatedArchiveIsRejectedEvenWhenLocalEntriesRemainReadable() = runBlocking {
        val files = temporaryFolder.newFolder("truncate-files")
        val store = AttachmentBlobStore(files)
        val archive = temporaryFolder.newFile("complete.mrbackup")
        BackupContainer().write(fullBackupFixture(), store, archive)
        val centralDirectoryStart = ZipFile(archive).use { zip ->
            // The manifest local entry is readable before the central directory. Removing the
            // trailing end record reproduces the case ZipInputStream alone would accept.
            require(zip.size() == 1)
            archive.length() - 22
        }
        val truncated = temporaryFolder.newFile("truncated.mrbackup").apply {
            outputStream().use { output ->
                archive.inputStream().use { input ->
                    var remaining = centralDirectoryStart
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
        }

        val result = BackupContainer().read(truncated, temporaryFolder.newFolder("truncated-stage"))

        assertEquals(
            BackupContainerReadResult.Failure(BackupContainerError.INVALID_CONTAINER),
            result,
        )
    }

    @Test
    fun unexpectedEntryAndTamperedBlobAreRejected() = runBlocking {
        val unexpected = temporaryFolder.newFile("unexpected.mrbackup")
        val manifest = Json { encodeDefaults = true; explicitNulls = true }
            .encodeToString(fullBackupFixture())
        ZipOutputStream(unexpected.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        assertEquals(
            BackupContainerReadResult.Failure(BackupContainerError.UNEXPECTED_ENTRY),
            BackupContainer().read(unexpected, temporaryFolder.newFolder("unexpected-stage")),
        )

        val bytes = "expected".toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val document = fullBackupFixture().copy(
            payload = fullBackupFixture().payload.copy(
                attachmentBlobs = listOf(
                    AttachmentBlobBackupDto(sha, "image", "image/png", bytes.size.toLong(), 1, 1, 1),
                ),
            ),
        )
        val tampered = temporaryFolder.newFile("tampered.mrbackup")
        ZipOutputStream(tampered.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                Json { encodeDefaults = true; explicitNulls = true }
                    .encodeToString(document).toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("blobs/$sha"))
            zip.write("tampered".toByteArray())
            zip.closeEntry()
        }
        assertEquals(
            BackupContainerReadResult.Failure(BackupContainerError.BLOB_MISMATCH),
            BackupContainer().read(tampered, temporaryFolder.newFolder("tampered-stage")),
        )
    }

    @Test
    fun duplicateManifestAndBlobEntriesAreRejected() {
        val manifest = Json { encodeDefaults = true; explicitNulls = true }
            .encodeToString(fullBackupFixture()).toByteArray()
        val duplicateManifest = temporaryFolder.newFile("duplicate-manifest.mrbackup")
        ZipOutputStream(duplicateManifest.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.js0n"))
            zip.write(manifest)
            zip.closeEntry()
        }
        replaceAscii(duplicateManifest, "manifest.js0n", "manifest.json")
        assertEquals(
            BackupContainerReadResult.Failure(BackupContainerError.DUPLICATE_ENTRY),
            BackupContainer().read(
                duplicateManifest,
                temporaryFolder.newFolder("duplicate-manifest-stage"),
            ),
        )

        val firstSha = "a".repeat(64)
        val secondSha = "b".repeat(64)
        val duplicateBlob = temporaryFolder.newFile("duplicate-blob.mrbackup")
        ZipOutputStream(duplicateBlob.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("blobs/$firstSha"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("blobs/$secondSha"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        replaceAscii(duplicateBlob, secondSha, firstSha)
        assertEquals(
            BackupContainerReadResult.Failure(BackupContainerError.DUPLICATE_ENTRY),
            BackupContainer().read(duplicateBlob, temporaryFolder.newFolder("duplicate-blob-stage")),
        )
    }

    private fun replaceAscii(file: File, from: String, to: String) {
        require(from.length == to.length)
        val bytes = file.readBytes()
        val source = from.toByteArray(Charsets.US_ASCII)
        val replacement = to.toByteArray(Charsets.US_ASCII)
        var replacements = 0
        for (index in 0..bytes.size - source.size) {
            if (source.indices.all { offset -> bytes[index + offset] == source[offset] }) {
                replacement.copyInto(bytes, index)
                replacements += 1
            }
        }
        assertTrue(replacements >= 2)
        file.writeBytes(bytes)
    }
}
