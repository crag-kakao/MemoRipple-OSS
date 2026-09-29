package io.github.cragcoffee.memoripple.data.ai.models

import io.github.cragcoffee.memoripple.domain.ai.models.CommitResult
import io.github.cragcoffee.memoripple.domain.ai.models.ModelFiles
import io.github.cragcoffee.memoripple.domain.ai.models.ModelStore
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelFileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * `<noBackupDir>/models/<id>/model.gguf`, with `model.gguf.part` during a download. The id is
 * validated by [ModelFileResolver] (path-safe) so a catalog id is the only thing that can name a
 * directory here. The final file appears only through [verifyAndCommit]'s atomic move.
 */
class FileModelStore(private val noBackupDir: File) : ModelStore {
    private val root: File get() = File(noBackupDir, ModelFileResolver.MODELS_DIR)

    override fun files(modelId: String): ModelFiles {
        val dir = File(root, ModelFileResolver.safeId(modelId))
        return ModelFiles(dir, File(dir, MODEL_FILE), File(dir, PART_FILE))
    }

    override fun installedBytes(modelId: String): Long? = files(modelId).file.takeIf { it.isFile }?.length()

    override fun partialBytes(modelId: String): Long = files(modelId).partFile.takeIf { it.isFile }?.length() ?: 0L

    override fun openPart(modelId: String, resume: Boolean): OutputStream {
        val f = files(modelId)
        f.directory.mkdirs()
        return FileOutputStream(f.partFile, resume)
    }

    override suspend fun verifyAndCommit(modelId: String, expectedSha256: String): CommitResult = withContext(Dispatchers.IO) {
        val f = files(modelId)
        if (!f.partFile.isFile) return@withContext CommitResult.NoPart
        val actual = sha256(f.partFile)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            f.partFile.delete()
            return@withContext CommitResult.Mismatch
        }
        Files.move(f.partFile.toPath(), f.file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        CommitResult.Committed(f.file.length())
    }

    override suspend fun delete(modelId: String): Boolean = withContext(Dispatchers.IO) {
        val dir = files(modelId).directory
        if (!dir.exists()) false else dir.deleteRecursively()
    }

    override fun usableBytes(): Long {
        root.mkdirs()
        return (if (root.exists()) root else noBackupDir).usableSpace
    }

    override fun installedModelIds(): Set<String> =
        root.listFiles()?.filter { it.isDirectory && File(it, MODEL_FILE).isFile }?.map { it.name }?.toSet() ?: emptySet()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MODEL_FILE = "model.gguf"
        const val PART_FILE = "model.gguf.part"
    }
}
