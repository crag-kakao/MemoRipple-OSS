package io.github.cragcoffee.memoripple.portableexport

import android.net.Uri
import io.github.cragcoffee.memoripple.backup.BackupFileReadResult
import io.github.cragcoffee.memoripple.backup.SafBackupFileStore
import io.github.cragcoffee.memoripple.data.AttachmentContentSource
import io.github.cragcoffee.memoripple.data.AttachmentLimits
import io.github.cragcoffee.memoripple.data.AttachmentRepository
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.data.TagMutationResult
import io.github.cragcoffee.memoripple.data.TagRepository
import io.github.cragcoffee.memoripple.domain.export.PortableMemoParser
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.tags.TagNameNormalizer
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 書き出したZIPを取り込む — Format 1 memos come back as NEW memos, nothing else. Diaries,
 * notes, comments, favorites, dates and ids stay with the backup, whose job restoring is;
 * this only reads the memos folders (never the trash), creates fresh ACTIVE memos, reattaches
 * tags by name, and runs every photo through the same validation pipeline the photo picker
 * uses — limits, decode check, hashing, atomic install and all. Existing data is only ever
 * added to; nothing is replaced or deleted.
 */
class PortableImportEngine(
    private val memoRepository: MemoRepository,
    private val tagRepository: TagRepository,
    private val attachmentRepositoryFactory: (AttachmentContentSource) -> AttachmentRepository,
    private val fileStore: SafBackupFileStore,
    private val cacheDirectory: File,
    private val nowMillis: () -> Long,
    /** An outline's rows: its photos go on top as photo rows once they are in (docs/OUTLINE_EXPORT_IMPORT.md). */
    private val outlineStore: io.github.cragcoffee.memoripple.data.OutlineStore? = null,
) {

    data class ImportPreview(val memos: Int, val photos: Int)

    sealed interface ImportResult {
        data class Done(val memos: Int, val photos: Int, val skippedPhotos: Int) : ImportResult
        data object NotAPortableExport : ImportResult
        data object TooLarge : ImportResult
        data object Failed : ImportResult
    }

    private val memoEntry = Regex("""(?:.*/)?memos/(?:active|archived)/([^/]+)/memo\.md""")
    private fun photoEntryOf(memoFolderEntry: String, fileName: String): String =
        memoFolderEntry.removeSuffix("memo.md") + "photos/" + fileName

    /** What the confirmation dialog shows. Null when the file is not a Format 1 export. */
    suspend fun preview(source: Uri): ImportPreview? = withContext(Dispatchers.IO) {
        when (val read = fileStore.read(source)) {
            is BackupFileReadResult.Success -> try {
                withZip(read.file) { zip ->
                    var memos = 0
                    var photos = 0
                    for (entry in zip.entries()) {
                        val name = entry.name
                        val match = memoEntry.matchEntire(name) ?: continue
                        memos += 1
                        photos += parsedPhotoCount(zip, name)
                    }
                    if (memos == 0) null else ImportPreview(memos, photos)
                }
            } finally {
                read.discard()
            }

            else -> null
        }
    }

    suspend fun import(
        source: Uri,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportResult = withContext(Dispatchers.IO) {
        val read = fileStore.read(source)
        val staged = when (read) {
            is BackupFileReadResult.Success -> read.file
            BackupFileReadResult.TooLarge -> return@withContext ImportResult.TooLarge
            else -> return@withContext ImportResult.Failed
        }
        try {
            withZip(staged) { zip ->
                val memoEntries = zip.entries().toList()
                    .filter { memoEntry.matchEntire(it.name) != null }
                    .sortedBy { it.name }
                if (memoEntries.isEmpty()) return@withZip ImportResult.NotAPortableExport
                var importedMemos = 0
                var importedPhotos = 0
                var skippedPhotos = 0
                val tagIds = mutableMapOf<String, Long>()
                tagRepository.observeAllTags().first().forEach { tag ->
                    tagIds[tag.normalizedName] = tag.id
                }
                for ((index, entry) in memoEntries.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val text = zip.getInputStream(entry).use { input ->
                        input.readLimited(MAX_MARKDOWN_BYTES)
                    }?.toString(Charsets.UTF_8) ?: continue
                    val parsed = PortableMemoParser.parse(text) ?: continue
                    // The file's kind: an outline comes back as an outline, anything else as a memo.
                    val memo = memoRepository.save(
                        existing = null,
                        title = parsed.title,
                        body = parsed.body,
                        now = nowMillis(),
                        kind = if (parsed.outline) MemoKind.OUTLINE else MemoKind.MEMO,
                    ) ?: continue
                    importedMemos += 1
                    for (tagName in parsed.tags) {
                        attachTag(memo.id, tagName, tagIds)
                    }
                    if (parsed.photoFileNames.isNotEmpty()) {
                        val outcome = importPhotos(zip, entry.name, parsed.photoFileNames, memo.id)
                        importedPhotos += outcome.first
                        skippedPhotos += outcome.second
                        // The export does not carry where an outline's photos stood: they become
                        // photo rows at the top, in their order, above the lines.
                        if (parsed.outline) outlineStore?.materialize(memo.id)
                    }
                    onProgress(index + 1, memoEntries.size)
                }
                ImportResult.Done(importedMemos, importedPhotos, skippedPhotos)
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            ImportResult.Failed
        } finally {
            read.discard()
        }
    }

    /**
     * Extracts each referenced photo to a bounded temp file and hands the batch to the same
     * import pipeline the photo picker uses; a photo the pipeline refuses is skipped, never
     * fatal. MIME comes from the exported extension — the pipeline's decode check then has
     * the final word on whether the bytes really are that image.
     */
    private suspend fun importPhotos(
        zip: ZipFile,
        memoEntryName: String,
        fileNames: List<String>,
        memoId: Long,
    ): Pair<Int, Int> {
        val temps = mutableListOf<Pair<File, String>>()
        try {
            for (fileName in fileNames) {
                val photoEntry = zip.getEntry(photoEntryOf(memoEntryName, fileName)) ?: continue
                val temp = File.createTempFile("portable-import", null, cacheDirectory)
                val bytes = zip.getInputStream(photoEntry).use { input ->
                    input.copyLimited(temp, AttachmentLimits.MAX_IMAGE_BYTES)
                }
                if (bytes < 0) {
                    temp.delete()
                    continue
                }
                temps.add(temp to mimeForExtension(fileName))
            }
            if (temps.isEmpty()) return 0 to fileNames.size
            val source = object : AttachmentContentSource {
                override fun getType(uri: Uri): String? =
                    temps.firstOrNull { it.first.path == uri.path }?.second

                override fun openInputStream(uri: Uri): InputStream? =
                    temps.firstOrNull { it.first.path == uri.path }?.first?.inputStream()
            }
            val result = attachmentRepositoryFactory(source)
                .importMemoPhotos(memoId, temps.map { Uri.fromFile(it.first) })
            val imported = result.added + result.duplicates
            return imported to (fileNames.size - imported)
        } finally {
            temps.forEach { it.first.delete() }
        }
    }

    private suspend fun attachTag(memoId: Long, name: String, tagIds: MutableMap<String, Long>) {
        val key = TagNameNormalizer.normalizeKey(name)
        val existing = tagIds[key]
        val tagId = existing ?: when (val created = tagRepository.create(name, nowMillis())) {
            is TagMutationResult.Success -> created.tag?.id?.also { tagIds[key] = it }
            else -> null
        } ?: return
        tagRepository.attach(memoId, tagId)
    }

    private fun parsedPhotoCount(zip: ZipFile, memoEntryName: String): Int {
        val text = zip.getEntry(memoEntryName)?.let { entry ->
            zip.getInputStream(entry).use { it.readLimited(MAX_MARKDOWN_BYTES) }
        }?.toString(Charsets.UTF_8) ?: return 0
        return PortableMemoParser.parse(text)?.photoFileNames?.size ?: 0
    }

    private fun mimeForExtension(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }

    private inline fun <T> withZip(file: File, block: (ZipFile) -> T): T =
        ZipFile(file).use(block)

    /** Reads at most [limit] bytes; null when the entry is larger than a memo could be. */
    private fun InputStream.readLimited(limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (out.size() + count > limit) return null
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    /** Streams into [target], stopping at [limit]; returns bytes copied or -1 when too big. */
    private fun InputStream.copyLimited(target: File, limit: Long): Long {
        var total = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) return -1
                output.write(buffer, 0, count)
            }
        }
        return total
    }

    private companion object {
        const val MAX_MARKDOWN_BYTES = 1 shl 20
    }
}
