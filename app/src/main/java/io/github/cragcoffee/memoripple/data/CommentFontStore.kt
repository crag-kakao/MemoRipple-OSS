package io.github.cragcoffee.memoripple.data

import android.graphics.Typeface
import io.github.cragcoffee.memoripple.domain.settings.UserCommentFont
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The files behind the user's own comment fonts: `files/comment_fonts/<id>.<ext>`, written
 * whole from the picked document and accepted only if the platform can actually build a
 * typeface from the bytes — a renamed PDF never reaches the list. Deleting removes the one
 * file; nothing else references it. Size is capped well above any real font.
 */
class CommentFontStore(filesDir: File) {

    private val root = File(filesDir, "comment_fonts")

    fun file(font: UserCommentFont): File = File(root, font.fileName)

    /**
     * Copies [source] in and validates it. Returns the catalog entry, or null when the bytes
     * are not a loadable font (or too large) — in which case nothing is left on disk.
     */
    suspend fun install(source: InputStream, displayName: String): UserCommentFont? =
        withContext(Dispatchers.IO) {
            root.mkdirs()
            val id = UUID.randomUUID().toString()
            val extension = displayName.substringAfterLast('.', "")
                .lowercase()
                .takeIf { it in ALLOWED_EXTENSIONS }
                ?: "ttf"
            val target = File(root, "$id.$extension")
            try {
                var total = 0L
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_FONT_BYTES) return@withContext null.also { target.delete() }
                        output.write(buffer, 0, count)
                    }
                }
                if (total == 0L) {
                    target.delete()
                    return@withContext null
                }
                // The platform is the judge of what counts as a font.
                val typeface = runCatching { Typeface.Builder(target).build() }.getOrNull()
                if (typeface == null) {
                    target.delete()
                    return@withContext null
                }
                UserCommentFont(
                    id = id,
                    name = displayName.substringBeforeLast('.').ifBlank { "フォント" },
                    fileName = target.name,
                )
            } catch (failure: Exception) {
                target.delete()
                null
            }
        }

    suspend fun delete(font: UserCommentFont): Unit = withContext(Dispatchers.IO) {
        file(font).delete()
    }

    /** Builds the typeface for drawing; null when the file is gone or unreadable. */
    suspend fun loadTypeface(font: UserCommentFont): Typeface? = withContext(Dispatchers.IO) {
        val file = file(font)
        if (!file.isFile) null
        else runCatching { Typeface.Builder(file).build() }.getOrNull()
    }

    /**
     * Deletes every file in the store the catalog does not name — an orphan from a death between
     * the copy and the catalog write. Returns how many went; a missing directory is nothing.
     */
    suspend fun removeUnlisted(listed: List<UserCommentFont>): Int = withContext(Dispatchers.IO) {
        val keep = listed.map(UserCommentFont::fileName).toHashSet()
        root.listFiles()?.count { it.isFile && it.name !in keep && it.delete() } ?: 0
    }

    private companion object {
        const val MAX_FONT_BYTES = 30L * 1024 * 1024
        val ALLOWED_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")
    }
}
