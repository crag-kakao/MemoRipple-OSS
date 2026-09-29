package io.github.cragcoffee.memoripple.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface BackupFileReadResult {
    class Success(val file: File) : BackupFileReadResult {
        val bytes: ByteArray get() = file.readBytes()
        fun discard() { file.delete() }
    }
    data object Unavailable : BackupFileReadResult
    data object TooLarge : BackupFileReadResult
    data object IoFailure : BackupFileReadResult
}

class SafBackupFileStore(
    private val contentResolver: ContentResolver,
    private val cacheDirectory: File = File(System.getProperty("java.io.tmpdir"), "memorripple"),
) {
    suspend fun write(uri: Uri, file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val output = contentResolver.openOutputStream(uri, "w") ?: return@withContext false
            output.use { destination ->
                file.inputStream().buffered().use { source -> source.copyTo(destination) }
                destination.flush()
            }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    suspend fun write(uri: Uri, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val output = contentResolver.openOutputStream(uri, "w") ?: return@withContext false
            output.use {
                it.write(bytes)
                it.flush()
            }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Reads a document the user picked as text, refusing anything longer than [maxCharacters] so a
     * mistaken pick cannot pull an arbitrarily large file into memory.
     */
    suspend fun readText(uri: Uri, maxCharacters: Int): String? = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(uri)?.use { source ->
                val reader = source.reader()
                val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                val text = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > maxCharacters) return@withContext null
                    text.appendRange(buffer, 0, count)
                }
                text.toString()
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    suspend fun read(uri: Uri): BackupFileReadResult = withContext(Dispatchers.IO) {
        val directory = File(cacheDirectory, "backup/import").apply { mkdirs() }
        val temp = File(directory, "saf-${UUID.randomUUID()}.mrbackup")
        try {
            val input = contentResolver.openInputStream(uri)
                ?: return@withContext BackupFileReadResult.Unavailable
            input.use { source ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > BackupV10Limits.MAX_CONTAINER_BYTES) {
                            temp.delete()
                            return@withContext BackupFileReadResult.TooLarge
                        }
                        output.write(buffer, 0, count)
                    }
                }
                BackupFileReadResult.Success(temp)
            }
        } catch (_: IOException) {
            temp.delete()
            BackupFileReadResult.IoFailure
        } catch (_: SecurityException) {
            temp.delete()
            BackupFileReadResult.IoFailure
        }
    }
}
