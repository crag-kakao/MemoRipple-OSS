package io.github.cragcoffee.memoripple.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class BackupDecodeError {
    EMPTY,
    COMPRESSED_FILE_TOO_LARGE,
    NOT_GZIP,
    INVALID_OR_TRUNCATED_GZIP,
    UNCOMPRESSED_DATA_TOO_LARGE,
    INVALID_JSON,
}

sealed interface BackupDecodeResult {
    data class Success(val document: MemoRippleBackupDto) : BackupDecodeResult
    data class Failure(val error: BackupDecodeError) : BackupDecodeResult
}

class BackupCodec(
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    },
) {
    fun encode(document: MemoRippleBackupDto): ByteArray {
        val jsonBytes = json.encodeToString(document).toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { gzip -> gzip.write(jsonBytes) }
            output.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): BackupDecodeResult {
        if (bytes.isEmpty()) return BackupDecodeResult.Failure(BackupDecodeError.EMPTY)
        if (bytes.size > MAX_COMPRESSED_BYTES) {
            return BackupDecodeResult.Failure(BackupDecodeError.COMPRESSED_FILE_TOO_LARGE)
        }
        if (bytes.size < 2 || bytes[0] != GZIP_MAGIC_FIRST || bytes[1] != GZIP_MAGIC_SECOND) {
            return BackupDecodeResult.Failure(BackupDecodeError.NOT_GZIP)
        }
        val jsonBytes = try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
                readLimited(gzip, MAX_UNCOMPRESSED_BYTES)
            }
        } catch (_: BackupSizeLimitException) {
            return BackupDecodeResult.Failure(BackupDecodeError.UNCOMPRESSED_DATA_TOO_LARGE)
        } catch (_: IOException) {
            return BackupDecodeResult.Failure(BackupDecodeError.INVALID_OR_TRUNCATED_GZIP)
        }
        return try {
            BackupDecodeResult.Success(
                json.decodeFromString<MemoRippleBackupDto>(jsonBytes.toString(Charsets.UTF_8)),
            )
        } catch (_: SerializationException) {
            BackupDecodeResult.Failure(BackupDecodeError.INVALID_JSON)
        } catch (_: IllegalArgumentException) {
            BackupDecodeResult.Failure(BackupDecodeError.INVALID_JSON)
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw BackupSizeLimitException()
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private class BackupSizeLimitException : IOException()

    companion object {
        const val MAX_COMPRESSED_BYTES = 32 * 1024 * 1024
        const val MAX_UNCOMPRESSED_BYTES = 128 * 1024 * 1024
        private const val GZIP_MAGIC_FIRST: Byte = 0x1f
        private const val GZIP_MAGIC_SECOND: Byte = 0x8b.toByte()
    }
}
