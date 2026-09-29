package io.github.cragcoffee.memoripple.data

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class AttachmentBlobStore(filesDir: File) {
    private val root = File(filesDir, "attachments")

    /**
     * The one lock of this physical photo store (docs/OUTLINE_EXPORT_IMPORT.md §5): everything that
     * writes, installs, cleans or sweeps its files — imports, restores, deletions, the garbage
     * collection — takes it, whichever `AttachmentRepository` it goes through. It belongs to the
     * directory, not to an object: every store over the same folder shares it, so no repository can
     * own a lock of its own and let another's cleanup delete a file it is still writing. Reading a
     * photo a row still refers to needs no lock (only unreferenced files are ever removed).
     */
    val lock: Mutex = locks.computeIfAbsent(root.canonicalPath) { Mutex() }
    private val blobDir = File(root, "blobs")
    private val tempDir = File(root, "tmp")

    init {
        blobDir.mkdirs()
        tempDir.mkdirs()
    }

    fun newTempFile(prefix: String = "import"): File {
        tempDir.mkdirs()
        return File(tempDir, "$prefix-${UUID.randomUUID()}.tmp")
    }

    fun blobFile(sha256: String): File {
        require(SHA256_PATTERN.matches(sha256)) { "Invalid attachment hash" }
        return File(blobDir, sha256)
    }

    suspend fun installValidated(source: File, sha256: String, sizeBytes: Long): File =
        withContext(Dispatchers.IO) {
            require(source.length() == sizeBytes) { "Attachment size mismatch" }
            require(hash(source) == sha256) { "Attachment hash mismatch" }
            blobDir.mkdirs()
            val destination = blobFile(sha256)
            if (destination.isFile && destination.length() == sizeBytes && hash(destination) == sha256) {
                source.delete()
                return@withContext destination
            }
            val staging = File(blobDir, ".$sha256-${UUID.randomUUID()}.tmp")
            source.inputStream().use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            if (hash(staging) != sha256 || staging.length() != sizeBytes) {
                staging.delete()
                throw IOException("Attachment changed during install")
            }
            if (destination.exists() && !destination.delete()) {
                staging.delete()
                throw IOException("Unable to replace corrupt attachment")
            }
            if (!staging.renameTo(destination)) {
                staging.delete()
                throw IOException("Unable to install attachment")
            }
            source.delete()
            destination
        }

    suspend fun isValid(sha256: String, sizeBytes: Long): Boolean = withContext(Dispatchers.IO) {
        val file = runCatching { blobFile(sha256) }.getOrNull() ?: return@withContext false
        file.isFile && file.length() == sizeBytes && runCatching { hash(file) }.getOrNull() == sha256
    }

    suspend fun remove(sha256: String) = withContext(Dispatchers.IO) {
        runCatching { blobFile(sha256) }.getOrNull()?.delete()
    }

    suspend fun cleanTemporaryFiles() = withContext(Dispatchers.IO) {
        tempDir.listFiles()?.forEach { it.delete() }
        blobDir.listFiles()?.filter { it.name.startsWith(".") }?.forEach { it.delete() }
    }

    companion object {
        /** One lock per physical store directory, for the life of the process. */
        private val locks = ConcurrentHashMap<String, Mutex>()

        val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
