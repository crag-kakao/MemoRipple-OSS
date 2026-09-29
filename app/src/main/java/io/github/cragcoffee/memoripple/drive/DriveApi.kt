package io.github.cragcoffee.memoripple.drive

import io.github.cragcoffee.memoripple.backup.BACKUP_MIME_TYPE
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.util.UUID
import io.github.cragcoffee.memoripple.backup.BackupV10Limits
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val DRIVE_BACKUP_FILE_NAME = "MemoRipple-auto-backup.mrbackup"

@Serializable
data class DriveRemoteFile(
    val id: String,
    val name: String = DRIVE_BACKUP_FILE_NAME,
    val modifiedTime: String? = null,
)

@Serializable
private data class DriveFileList(val files: List<DriveRemoteFile> = emptyList())

sealed interface DriveApiResult<out T> {
    data class Success<T>(val value: T) : DriveApiResult<T>
    data object Unauthorized : DriveApiResult<Nothing>
    data object Forbidden : DriveApiResult<Nothing>
    data object NotFound : DriveApiResult<Nothing>
    data object NetworkFailure : DriveApiResult<Nothing>
    data object ServerFailure : DriveApiResult<Nothing>
    data object InvalidResponse : DriveApiResult<Nothing>
}

interface DriveApi {
    suspend fun listBackupFiles(accessToken: String): DriveApiResult<List<DriveRemoteFile>>
    suspend fun createBackup(
        accessToken: String,
        bytes: ByteArray,
    ): DriveApiResult<DriveRemoteFile>
    suspend fun updateBackup(
        accessToken: String,
        fileId: String,
        bytes: ByteArray,
    ): DriveApiResult<DriveRemoteFile>
    suspend fun downloadBackup(accessToken: String, fileId: String): DriveApiResult<ByteArray>

    suspend fun createBackupFile(accessToken: String, file: File): DriveApiResult<DriveRemoteFile> =
        createBackup(accessToken, file.readBytes())

    suspend fun updateBackupFile(
        accessToken: String,
        fileId: String,
        file: File,
    ): DriveApiResult<DriveRemoteFile> = updateBackup(accessToken, fileId, file.readBytes())

    suspend fun downloadBackupFile(
        accessToken: String,
        fileId: String,
        destination: File,
    ): DriveApiResult<File> = when (val result = downloadBackup(accessToken, fileId)) {
        is DriveApiResult.Success -> {
            destination.parentFile?.mkdirs()
            destination.writeBytes(result.value)
            DriveApiResult.Success(destination)
        }
        DriveApiResult.Unauthorized -> DriveApiResult.Unauthorized
        DriveApiResult.Forbidden -> DriveApiResult.Forbidden
        DriveApiResult.NotFound -> DriveApiResult.NotFound
        DriveApiResult.NetworkFailure -> DriveApiResult.NetworkFailure
        DriveApiResult.ServerFailure -> DriveApiResult.ServerFailure
        DriveApiResult.InvalidResponse -> DriveApiResult.InvalidResponse
    }
}

class DriveRestApi(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val filesUrl: String = DEFAULT_DRIVE_FILES_URL,
    private val uploadUrl: String = DEFAULT_DRIVE_UPLOAD_URL,
) : DriveApi {
    override suspend fun createBackupFile(
        accessToken: String,
        file: File,
    ): DriveApiResult<DriveRemoteFile> = resumableUploadFile(
        accessToken = accessToken,
        file = file,
        initiationMethod = "POST",
        initiationUrl = "$uploadUrl?uploadType=resumable&fields=id,name,modifiedTime",
        metadata = "{\"name\":\"$DRIVE_BACKUP_FILE_NAME\",\"mimeType\":\"$BACKUP_MIME_TYPE\"," +
            "\"parents\":[\"appDataFolder\"]}",
    )

    override suspend fun updateBackupFile(
        accessToken: String,
        fileId: String,
        file: File,
    ): DriveApiResult<DriveRemoteFile> = resumableUploadFile(
        accessToken = accessToken,
        file = file,
        initiationMethod = "PATCH",
        initiationUrl = "$uploadUrl/${encodePath(fileId)}" +
            "?uploadType=resumable&fields=id,name,modifiedTime",
        metadata = "{\"name\":\"$DRIVE_BACKUP_FILE_NAME\",\"mimeType\":\"$BACKUP_MIME_TYPE\"}",
    )

    override suspend fun downloadBackupFile(
        accessToken: String,
        fileId: String,
        destination: File,
    ): DriveApiResult<File> = withContext(Dispatchers.IO) {
        request(
            url = "$filesUrl/${encodePath(fileId)}?alt=media",
            method = "GET",
            accessToken = accessToken,
        ) { connection ->
            try {
                destination.parentFile?.mkdirs()
                var total = 0L
                connection.inputStream.use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > BackupV10Limits.MAX_CONTAINER_BYTES) {
                                destination.delete()
                                return@request DriveApiResult.InvalidResponse
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                DriveApiResult.Success(destination)
            } catch (_: IOException) {
                destination.delete()
                DriveApiResult.NetworkFailure
            }
        }
    }
    override suspend fun listBackupFiles(
        accessToken: String,
    ): DriveApiResult<List<DriveRemoteFile>> = withContext(Dispatchers.IO) {
        val escapedName = DRIVE_BACKUP_FILE_NAME.replace("'", "\\'")
        val query = URLEncoder.encode("name = '$escapedName' and trashed = false", "UTF-8")
        val fields = URLEncoder.encode("files(id,name,modifiedTime)", "UTF-8")
        request(
            url = "$filesUrl?spaces=appDataFolder&q=$query&orderBy=modifiedTime%20desc" +
                "&pageSize=100&fields=$fields",
            method = "GET",
            accessToken = accessToken,
        ) { connection ->
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            runCatching { json.decodeFromString<DriveFileList>(body).files }
                .fold(
                    onSuccess = { DriveApiResult.Success(it) },
                    onFailure = { DriveApiResult.InvalidResponse },
                )
        }
    }

    override suspend fun createBackup(
        accessToken: String,
        bytes: ByteArray,
    ): DriveApiResult<DriveRemoteFile> = resumableUpload(
        accessToken = accessToken,
        bytes = bytes,
        initiationMethod = "POST",
        initiationUrl = "$uploadUrl?uploadType=resumable&fields=id,name,modifiedTime",
        metadata = "{\"name\":\"$DRIVE_BACKUP_FILE_NAME\",\"mimeType\":\"$BACKUP_MIME_TYPE\"," +
            "\"parents\":[\"appDataFolder\"]}",
    )

    override suspend fun updateBackup(
        accessToken: String,
        fileId: String,
        bytes: ByteArray,
    ): DriveApiResult<DriveRemoteFile> = resumableUpload(
        accessToken = accessToken,
        bytes = bytes,
        initiationMethod = "PATCH",
        initiationUrl = "$uploadUrl/${encodePath(fileId)}" +
            "?uploadType=resumable&fields=id,name,modifiedTime",
        metadata = "{\"name\":\"$DRIVE_BACKUP_FILE_NAME\",\"mimeType\":\"$BACKUP_MIME_TYPE\"}",
    )

    override suspend fun downloadBackup(
        accessToken: String,
        fileId: String,
    ): DriveApiResult<ByteArray> = withContext(Dispatchers.IO) {
        request(
            url = "$filesUrl/${encodePath(fileId)}?alt=media",
            method = "GET",
            accessToken = accessToken,
        ) { connection ->
            try {
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_DOWNLOAD_BYTES) return@request DriveApiResult.InvalidResponse
                        output.write(buffer, 0, count)
                    }
                }
                DriveApiResult.Success(output.toByteArray())
            } catch (_: IOException) {
                DriveApiResult.NetworkFailure
            }
        }
    }

    private suspend fun resumableUpload(
        accessToken: String,
        bytes: ByteArray,
        initiationMethod: String,
        initiationUrl: String,
        metadata: String,
    ): DriveApiResult<DriveRemoteFile> = withContext(Dispatchers.IO) {
        val metadataBytes = metadata.toByteArray(Charsets.UTF_8)
        val sessionResult = request(
            url = initiationUrl,
            method = initiationMethod,
            accessToken = accessToken,
            configure = { connection ->
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("X-Upload-Content-Type", BACKUP_MIME_TYPE)
                connection.setRequestProperty("X-Upload-Content-Length", bytes.size.toString())
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(metadataBytes.size)
                connection.outputStream.use { it.write(metadataBytes) }
            },
            onSuccess = { connection ->
                connection.getHeaderField("Location")?.let { DriveApiResult.Success(it) }
                    ?: DriveApiResult.InvalidResponse
            },
        )
        val sessionUri = when (sessionResult) {
            is DriveApiResult.Success -> sessionResult.value
            DriveApiResult.Unauthorized -> return@withContext DriveApiResult.Unauthorized
            DriveApiResult.Forbidden -> return@withContext DriveApiResult.Forbidden
            DriveApiResult.NotFound -> return@withContext DriveApiResult.NotFound
            DriveApiResult.NetworkFailure -> return@withContext DriveApiResult.NetworkFailure
            DriveApiResult.ServerFailure -> return@withContext DriveApiResult.ServerFailure
            DriveApiResult.InvalidResponse -> return@withContext DriveApiResult.InvalidResponse
        }

        request(
            url = sessionUri,
            method = "PUT",
            accessToken = accessToken,
            configure = { connection ->
                connection.setRequestProperty("Content-Type", BACKUP_MIME_TYPE)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            },
            onSuccess = { connection ->
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                runCatching { json.decodeFromString<DriveRemoteFile>(body) }
                    .fold(
                        onSuccess = { DriveApiResult.Success(it) },
                        onFailure = { DriveApiResult.InvalidResponse },
                    )
            },
        )
    }

    private suspend fun resumableUploadFile(
        accessToken: String,
        file: File,
        initiationMethod: String,
        initiationUrl: String,
        metadata: String,
    ): DriveApiResult<DriveRemoteFile> = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() <= 0L || file.length() > BackupV10Limits.MAX_CONTAINER_BYTES) {
            return@withContext DriveApiResult.InvalidResponse
        }
        val metadataBytes = metadata.toByteArray(Charsets.UTF_8)
        val sessionResult = request(
            url = initiationUrl,
            method = initiationMethod,
            accessToken = accessToken,
            configure = { connection ->
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("X-Upload-Content-Type", BACKUP_MIME_TYPE)
                connection.setRequestProperty("X-Upload-Content-Length", file.length().toString())
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(metadataBytes.size)
                connection.outputStream.use { it.write(metadataBytes) }
            },
            onSuccess = { connection ->
                connection.getHeaderField("Location")?.let { DriveApiResult.Success(it) }
                    ?: DriveApiResult.InvalidResponse
            },
        )
        val sessionUri = when (sessionResult) {
            is DriveApiResult.Success -> sessionResult.value
            DriveApiResult.Unauthorized -> return@withContext DriveApiResult.Unauthorized
            DriveApiResult.Forbidden -> return@withContext DriveApiResult.Forbidden
            DriveApiResult.NotFound -> return@withContext DriveApiResult.NotFound
            DriveApiResult.NetworkFailure -> return@withContext DriveApiResult.NetworkFailure
            DriveApiResult.ServerFailure -> return@withContext DriveApiResult.ServerFailure
            DriveApiResult.InvalidResponse -> return@withContext DriveApiResult.InvalidResponse
        }
        request(
            url = sessionUri,
            method = "PUT",
            accessToken = accessToken,
            configure = { connection ->
                connection.setRequestProperty("Content-Type", BACKUP_MIME_TYPE)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(file.length())
                connection.outputStream.use { output ->
                    file.inputStream().buffered().use { input -> input.copyTo(output) }
                }
            },
            onSuccess = { connection ->
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                runCatching { json.decodeFromString<DriveRemoteFile>(body) }
                    .fold(
                        onSuccess = { DriveApiResult.Success(it) },
                        onFailure = { DriveApiResult.InvalidResponse },
                    )
            },
        )
    }

    private fun <T> request(
        url: String,
        method: String,
        accessToken: String,
        configure: (HttpURLConnection) -> Unit = {},
        onSuccess: (HttpURLConnection) -> DriveApiResult<T>,
    ): DriveApiResult<T> {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                useCaches = false
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
                configure(this)
            }
        } catch (_: IOException) {
            return DriveApiResult.NetworkFailure
        }
        return try {
            when (connection.responseCode) {
                in 200..299 -> onSuccess(connection)
                HttpURLConnection.HTTP_UNAUTHORIZED -> DriveApiResult.Unauthorized
                HttpURLConnection.HTTP_FORBIDDEN -> DriveApiResult.Forbidden
                HttpURLConnection.HTTP_NOT_FOUND -> DriveApiResult.NotFound
                in 500..599 -> DriveApiResult.ServerFailure
                else -> DriveApiResult.InvalidResponse
            }
        } catch (_: IOException) {
            DriveApiResult.NetworkFailure
        } finally {
            connection.disconnect()
        }
    }

    private fun encodePath(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val DEFAULT_DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
        const val DEFAULT_DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        const val MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 60_000
    }
}

sealed interface DriveTransportResult<out T> {
    data class Success<T>(val value: T) : DriveTransportResult<T>
    data class Failure(
        val apiResult: DriveApiResult<Nothing>,
        val cachedFileMissing: Boolean = false,
    ) : DriveTransportResult<Nothing>
}

class DriveBackupTransport(
    private val api: DriveApi,
    private val cacheDirectory: File = File(System.getProperty("java.io.tmpdir"), "memorripple"),
) {
    suspend fun upload(
        accessToken: String,
        cachedFileId: String?,
        file: File,
    ): DriveTransportResult<DriveRemoteFile> {
        var cachedMissing = false
        if (cachedFileId != null) {
            when (val updated = api.updateBackupFile(accessToken, cachedFileId, file)) {
                is DriveApiResult.Success -> return DriveTransportResult.Success(updated.value)
                DriveApiResult.NotFound -> cachedMissing = true
                else -> return DriveTransportResult.Failure(updated.asFailure(), false)
            }
        }
        return when (val lookup = api.listBackupFiles(accessToken)) {
            is DriveApiResult.Success -> {
                val latest = latestFile(lookup.value)
                val result = if (latest == null) api.createBackupFile(accessToken, file)
                else api.updateBackupFile(accessToken, latest.id, file)
                when (result) {
                    is DriveApiResult.Success -> DriveTransportResult.Success(result.value)
                    else -> DriveTransportResult.Failure(result.asFailure(), cachedMissing)
                }
            }
            else -> DriveTransportResult.Failure(lookup.asFailure(), cachedMissing)
        }
    }

    suspend fun upload(
        accessToken: String,
        cachedFileId: String?,
        bytes: ByteArray,
    ): DriveTransportResult<DriveRemoteFile> {
        var cachedMissing = false
        if (cachedFileId != null) {
            when (val updated = api.updateBackup(accessToken, cachedFileId, bytes)) {
                is DriveApiResult.Success -> return DriveTransportResult.Success(updated.value)
                DriveApiResult.NotFound -> cachedMissing = true
                else -> return DriveTransportResult.Failure(updated.asFailure(), false)
            }
        }
        return when (val lookup = api.listBackupFiles(accessToken)) {
            is DriveApiResult.Success -> {
                val latest = latestFile(lookup.value)
                val result = if (latest == null) api.createBackup(accessToken, bytes)
                else api.updateBackup(accessToken, latest.id, bytes)
                when (result) {
                    is DriveApiResult.Success -> DriveTransportResult.Success(result.value)
                    else -> DriveTransportResult.Failure(result.asFailure(), cachedMissing)
                }
            }
            else -> DriveTransportResult.Failure(lookup.asFailure(), cachedMissing)
        }
    }

    suspend fun download(
        accessToken: String,
        cachedFileId: String?,
    ): DriveTransportResult<DriveDownloadedBackup> {
        val directory = File(cacheDirectory, "backup/drive").apply { mkdirs() }
        fun destination() = File(directory, "download-${UUID.randomUUID()}.mrbackup")
        var cachedMissing = false
        if (cachedFileId != null) {
            when (val downloaded = api.downloadBackupFile(accessToken, cachedFileId, destination())) {
                is DriveApiResult.Success -> return DriveTransportResult.Success(
                    DriveDownloadedBackup(cachedFileId, downloaded.value),
                )
                DriveApiResult.NotFound -> cachedMissing = true
                else -> return DriveTransportResult.Failure(downloaded.asFailure(), false)
            }
        }
        return when (val lookup = api.listBackupFiles(accessToken)) {
            is DriveApiResult.Success -> {
                val latest = latestFile(lookup.value)
                    ?: return DriveTransportResult.Failure(
                        DriveApiResult.NotFound,
                        cachedMissing,
                    )
                when (val downloaded = api.downloadBackupFile(accessToken, latest.id, destination())) {
                    is DriveApiResult.Success -> DriveTransportResult.Success(
                        DriveDownloadedBackup(latest.id, downloaded.value),
                    )
                    else -> DriveTransportResult.Failure(downloaded.asFailure(), cachedMissing)
                }
            }
            else -> DriveTransportResult.Failure(lookup.asFailure(), cachedMissing)
        }
    }

    internal fun latestFile(files: List<DriveRemoteFile>): DriveRemoteFile? =
        files.filter { it.name == DRIVE_BACKUP_FILE_NAME }
            .maxWithOrNull(compareBy<DriveRemoteFile> { it.modifiedTime ?: "" }.thenBy { it.id })

    @Suppress("UNCHECKED_CAST")
    private fun DriveApiResult<*>.asFailure(): DriveApiResult<Nothing> =
        this as DriveApiResult<Nothing>
}

class DriveDownloadedBackup(val fileId: String, val file: File) {
    val bytes: ByteArray get() = file.readBytes()
    fun discard() { file.delete() }
}
