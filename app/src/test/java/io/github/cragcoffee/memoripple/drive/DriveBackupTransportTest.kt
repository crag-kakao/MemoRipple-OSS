package io.github.cragcoffee.memoripple.drive

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DriveBackupTransportTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun missingCacheAndEmptyLookupCreatesAppDataBackup() = kotlinx.coroutines.runBlocking {
        val api = FakeDriveApi()
        val transport = DriveBackupTransport(api)
        val bytes = "exact gzip bytes".toByteArray()

        val result = transport.upload("short-lived-token", null, bytes)

        assertTrue(result is DriveTransportResult.Success)
        assertEquals(1, api.listCalls)
        assertEquals(1, api.createCalls)
        assertArrayEquals(bytes, api.lastUploadedBytes)
    }

    @Test
    fun cachedIdUpdatesWithoutLookup() = kotlinx.coroutines.runBlocking {
        val api = FakeDriveApi()
        val transport = DriveBackupTransport(api)

        transport.upload("token", "cached", byteArrayOf(1, 2, 3))

        assertEquals(listOf("cached"), api.updatedIds)
        assertEquals(0, api.listCalls)
    }

    @Test
    fun cached404ClearsPathThenFindsAndUpdatesExistingFile() = kotlinx.coroutines.runBlocking {
        val api = FakeDriveApi().apply {
            missingIds += "stale"
            files += DriveRemoteFile("found", modifiedTime = "2026-01-01T00:00:00Z")
        }
        val result = DriveBackupTransport(api).upload("token", "stale", byteArrayOf(4))

        assertTrue(result is DriveTransportResult.Success)
        assertEquals(listOf("stale", "found"), api.updatedIds)
        assertEquals(1, api.listCalls)
    }

    @Test
    fun duplicateLookupDeterministicallyUsesNewestModifiedTime() = kotlinx.coroutines.runBlocking {
        val api = FakeDriveApi().apply {
            files += DriveRemoteFile("older", modifiedTime = "2025-12-31T23:59:59Z")
            files += DriveRemoteFile("newest", modifiedTime = "2026-01-01T00:00:00Z")
            files += DriveRemoteFile("other-name", name = "other", modifiedTime = "2027-01-01T00:00:00Z")
        }

        DriveBackupTransport(api).upload("token", null, byteArrayOf(9))

        assertEquals(listOf("newest"), api.updatedIds)
        assertEquals(0, api.createCalls)
    }

    @Test
    fun cached404AndNoLookupMatchCreatesReplacement() = kotlinx.coroutines.runBlocking {
        val api = FakeDriveApi().apply { missingIds += "stale" }

        val result = DriveBackupTransport(api).upload("token", "stale", byteArrayOf(7))

        assertTrue(result is DriveTransportResult.Success)
        assertEquals(1, api.createCalls)
    }

    @Test
    fun payloadLargerThanFiveMiBIsPassedWithoutModification() = kotlinx.coroutines.runBlocking {
        val api = FakeDriveApi()
        val bytes = ByteArray(5 * 1024 * 1024 + 1) { (it % 251).toByte() }

        DriveBackupTransport(api).upload("token", null, bytes)

        assertArrayEquals(bytes, api.lastUploadedBytes)
    }

    @Test
    fun restoreFallsBackFromCached404AndReturnsSelectedFileId() = kotlinx.coroutines.runBlocking {
        val api = FakeDriveApi().apply {
            missingIds += "stale"
            files += DriveRemoteFile("remote", modifiedTime = "2026-01-01T00:00:00Z")
            downloadBytes = byteArrayOf(3, 2, 1)
        }

        val result = DriveBackupTransport(api).download("token", "stale")

        val value = (result as DriveTransportResult.Success).value
        assertEquals("remote", value.fileId)
        assertArrayEquals(byteArrayOf(3, 2, 1), value.bytes)
    }

    @Test
    fun v10FilePathCreatesUpdatesAndDownloadsWithoutByteArrayFallback() =
        kotlinx.coroutines.runBlocking {
            val api = FakeDriveApi()
            val transport = DriveBackupTransport(api, temporaryFolder.newFolder("drive-cache"))
            val source = temporaryFolder.newFile("backup.mrbackup").apply {
                writeBytes(ByteArray(256 * 1024) { (it % 251).toByte() })
            }

            val created = transport.upload("token", null, source)
            assertTrue(created is DriveTransportResult.Success)
            assertEquals(1, api.createFileCalls)
            assertEquals(0, api.createByteCalls)
            assertArrayEquals(source.readBytes(), api.lastUploadedFileBytes)

            val updated = transport.upload("token", "created", source)
            assertTrue(updated is DriveTransportResult.Success)
            assertEquals(listOf("created"), api.updatedFileIds)
            assertEquals(0, api.updateByteCalls)

            api.downloadBytes = source.readBytes()
            val downloaded = transport.download("token", "created")
            val value = (downloaded as DriveTransportResult.Success).value
            assertEquals("created", value.fileId)
            assertArrayEquals(source.readBytes(), value.file.readBytes())
            assertEquals(1, api.downloadFileCalls)
            assertEquals(0, api.downloadByteCalls)
            value.discard()
        }
}

internal class FakeDriveApi : DriveApi {
    val files = mutableListOf<DriveRemoteFile>()
    val missingIds = mutableSetOf<String>()
    val updatedIds = mutableListOf<String>()
    var listCalls = 0
    var createCalls = 0
    var createByteCalls = 0
    var updateByteCalls = 0
    var createFileCalls = 0
    var downloadFileCalls = 0
    var downloadByteCalls = 0
    var lastUploadedBytes: ByteArray? = null
    var lastUploadedFileBytes: ByteArray? = null
    val updatedFileIds = mutableListOf<String>()
    var downloadBytes: ByteArray = byteArrayOf(1)
    var nextFailure: DriveApiResult<Nothing>? = null
    var onUpload: (suspend () -> Unit)? = null

    override suspend fun listBackupFiles(accessToken: String): DriveApiResult<List<DriveRemoteFile>> {
        listCalls += 1
        nextFailure?.let { nextFailure = null; return it }
        return DriveApiResult.Success(files.toList())
    }

    override suspend fun createBackup(
        accessToken: String,
        bytes: ByteArray,
    ): DriveApiResult<DriveRemoteFile> {
        createCalls += 1
        createByteCalls += 1
        lastUploadedBytes = bytes.copyOf()
        onUpload?.invoke()
        nextFailure?.let { nextFailure = null; return it }
        return DriveApiResult.Success(DriveRemoteFile("created"))
    }

    override suspend fun updateBackup(
        accessToken: String,
        fileId: String,
        bytes: ByteArray,
    ): DriveApiResult<DriveRemoteFile> {
        updatedIds += fileId
        updateByteCalls += 1
        lastUploadedBytes = bytes.copyOf()
        onUpload?.invoke()
        if (fileId in missingIds) return DriveApiResult.NotFound
        nextFailure?.let { nextFailure = null; return it }
        return DriveApiResult.Success(DriveRemoteFile(fileId))
    }

    override suspend fun downloadBackup(
        accessToken: String,
        fileId: String,
    ): DriveApiResult<ByteArray> {
        downloadByteCalls += 1
        if (fileId in missingIds) return DriveApiResult.NotFound
        nextFailure?.let { nextFailure = null; return it }
        return DriveApiResult.Success(downloadBytes.copyOf())
    }

    override suspend fun createBackupFile(
        accessToken: String,
        file: File,
    ): DriveApiResult<DriveRemoteFile> {
        createFileCalls += 1
        createCalls += 1
        lastUploadedFileBytes = file.readBytes()
        lastUploadedBytes = lastUploadedFileBytes
        onUpload?.invoke()
        nextFailure?.let { nextFailure = null; return it }
        return DriveApiResult.Success(DriveRemoteFile("created"))
    }

    override suspend fun updateBackupFile(
        accessToken: String,
        fileId: String,
        file: File,
    ): DriveApiResult<DriveRemoteFile> {
        updatedFileIds += fileId
        updatedIds += fileId
        lastUploadedFileBytes = file.readBytes()
        lastUploadedBytes = lastUploadedFileBytes
        onUpload?.invoke()
        if (fileId in missingIds) return DriveApiResult.NotFound
        nextFailure?.let { nextFailure = null; return it }
        return DriveApiResult.Success(DriveRemoteFile(fileId))
    }

    override suspend fun downloadBackupFile(
        accessToken: String,
        fileId: String,
        destination: File,
    ): DriveApiResult<File> {
        downloadFileCalls += 1
        if (fileId in missingIds) return DriveApiResult.NotFound
        nextFailure?.let { nextFailure = null; return it }
        destination.parentFile?.mkdirs()
        destination.writeBytes(downloadBytes)
        return DriveApiResult.Success(destination)
    }
}
