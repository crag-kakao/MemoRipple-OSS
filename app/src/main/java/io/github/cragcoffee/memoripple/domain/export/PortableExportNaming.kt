package io.github.cragcoffee.memoripple.domain.export

/**
 * Names inside the portable ZIP. Every rule here serves someone opening the archive in an
 * ordinary file manager years from now: names stay readable (Japanese included), never collide,
 * never traverse (`..`, leading `/`, backslashes), never use characters an OS reserves, and
 * never grow past what file systems accept. The short id keeps two records with the same title
 * apart without turning the name into a database dump.
 */
object PortableExportNaming {

    private const val MAX_TITLE_SEGMENT_CHARS = 40
    private val unsafeInSegment = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    private val collapseSpaces = Regex("""\s+""")

    /**
     * One readable path segment from a record's title: unsafe characters become `_`, whitespace
     * collapses, leading/trailing dots go (no hidden files, no `..`), and length is capped so a
     * deep path still fits the OS limit.
     */
    fun titleSegment(title: String, fallback: String): String {
        val cleaned = unsafeInSegment.replace(title, "_")
            .let { collapseSpaces.replace(it, " ") }
            .trim()
            .trim('.')
            .trim()
            .take(MAX_TITLE_SEGMENT_CHARS)
            .trim()
            .trim('.')
        return cleaned.ifBlank { fallback }
    }

    /** A short stable suffix from the record id, so equal titles never share a folder. */
    fun shortId(id: Long): String = java.lang.Long.toHexString(id).takeLast(6).padStart(4, '0')

    /** `YYYYMMDD-title-shortid` — the folder one memo lives in. */
    fun memoFolder(createdAtStamp: String, title: String, id: Long): String =
        "$createdAtStamp-${titleSegment(title, "無題のメモ")}-${shortId(id)}"

    /** `title-shortid` — the folder one note lives in. */
    fun noteFolder(title: String, id: Long): String =
        "${titleSegment(title, "無題のノート")}-${shortId(id)}"

    /** `photo-01.jpg` — order preserved, extension from the stored MIME. */
    fun photoFileName(index1Based: Int, mimeType: String?): String =
        "photo-" + index1Based.toString().padStart(2, '0') + photoExtension(mimeType)

    /** The extension a viewer expects for the stored MIME; unknown formats fall back safely. */
    fun photoExtension(mimeType: String?): String = when (mimeType?.lowercase()?.trim()) {
        "image/jpeg", "image/jpg" -> ".jpg"
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "image/heic" -> ".heic"
        "image/heif" -> ".heif"
        "image/bmp" -> ".bmp"
        else -> ".img"
    }

    /** `NN-title.md` for a chapter file, ordered the way the note orders it. */
    fun chapterFileName(index1Based: Int, title: String): String =
        index1Based.toString().padStart(2, '0') + "-" +
            titleSegment(title, "無題") + ".md"

    /** The ZIP's own file name, stamped so repeated exports never overwrite silently. */
    fun archiveFileName(stamp: String): String = "MemoRipple-Export-$stamp.zip"

    /**
     * Guards a full entry path before it is written: relative, forward slashes only, no `..`
     * segment, no empty segment. A violating path is a programming error, not user data —
     * the writer refuses it outright.
     */
    fun isSafeEntryPath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || path.contains('\\')) return false
        val segments = path.split('/')
        return segments.none { it.isBlank() || it == "." || it == ".." }
    }
}
