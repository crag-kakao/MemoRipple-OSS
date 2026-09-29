package io.github.cragcoffee.memoripple.domain.documents

/**
 * The one title rule: the stored title, else the first non-blank line of the body, trimmed,
 * else the kind's placeholder. The calendar and the boundary read titles through here so the
 * same document has the same name everywhere it is listed.
 */
object DocumentTitles {
    const val UNTITLED_MEMO = "無題のメモ"
    const val UNTITLED_OUTLINE = "無題のアウトライナー"
    const val EMPTY_JOURNAL = "（本文なし）"

    /** The first non-blank line of [body], trimmed, or null when there is none. */
    fun firstLine(body: String): String? =
        body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotEmpty() }

    /** [title] if it says anything, else [firstLine] of [body], else [fallback]. */
    fun resolve(title: String, body: String, fallback: String): String =
        title.ifBlank { firstLine(body) ?: fallback }

    fun placeholder(kind: DocumentKind): String = when (kind) {
        DocumentKind.MEMO -> UNTITLED_MEMO
        DocumentKind.OUTLINE -> UNTITLED_OUTLINE
        DocumentKind.JOURNAL -> EMPTY_JOURNAL
    }
}
