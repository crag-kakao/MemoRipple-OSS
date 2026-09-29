package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.BodyText
import io.github.cragcoffee.memoripple.domain.WorkCommentSyntax
import io.github.cragcoffee.memoripple.domain.tags.TagNameNormalizer

/** One word of a search. */
data class SearchTerm(
    val text: String,
    val negated: Boolean = false,
    val tagOnly: Boolean = false,
)

/** A parsed search. Every term has to hold, so adding a word narrows the result. */
data class MemoSearchQuery(val terms: List<SearchTerm>) {
    val isEmpty: Boolean get() = terms.isEmpty()

    /** The words a result actually contains, which are the ones worth marking in it. */
    val highlights: List<String>
        get() = terms.filterNot { it.negated || it.tagOnly }.map(SearchTerm::text)

    companion object {
        val Empty = MemoSearchQuery(emptyList())
    }
}

/**
 * Full text search over memos.
 *
 * Words are separated by spaces and all of them have to match. `"..."` keeps a phrase together,
 * `#name` looks only at tags, and a leading `-` excludes. Matching runs on NFKC-folded text, so
 * half width kana and full width letters find their counterparts.
 */
object MemoSearch {

    private const val TAG_PREFIX = '#'
    private const val NEGATION_PREFIX = '-'
    private const val QUOTE = '"'
    private const val SNIPPET_MAX_CHARS = 80
    private const val SNIPPET_LEAD_CHARS = 24
    private const val ELLIPSIS = "…"

    fun parse(raw: String): MemoSearchQuery {
        val terms = tokenize(raw).mapNotNull { token ->
            var rest = token
            val negated = rest.length > 1 && rest.first() == NEGATION_PREFIX
            if (negated) rest = rest.drop(1)
            val tagOnly = rest.length > 1 && rest.first() == TAG_PREFIX
            if (tagOnly) rest = rest.drop(1)
            rest.takeIf(String::isNotBlank)?.let { SearchTerm(it, negated, tagOnly) }
        }
        return MemoSearchQuery(terms)
    }

    fun matches(
        query: MemoSearchQuery,
        title: String,
        body: String,
        tagNames: List<String> = emptyList(),
    ): Boolean {
        if (query.isEmpty) return true
        // Folded once per memo and shared by every term, rather than once per term.
        val haystack by lazy(LazyThreadSafetyMode.NONE) { fold(title + "\n" + readable(body)) }
        val tags = tagNames.map(TagNameNormalizer::normalizeKey)
        return query.terms.all { term ->
            val needle = fold(term.text)
            if (needle.isBlank()) return@all true
            val hit = if (term.tagOnly) {
                tags.any { it.contains(needle) }
            } else {
                haystack.contains(needle) || tags.any { it.contains(needle) }
            }
            hit != term.negated
        }
    }

    /**
     * The line a search actually hit, cleaned of its markers and trimmed to a readable width, so a
     * result shows why it is a result instead of always showing the top of the memo.
     */
    fun snippet(body: String, terms: List<String>): String? {
        if (terms.isEmpty() || body.isBlank()) return null
        val hit = terms
            .mapNotNull { term -> body.indexOf(term, ignoreCase = true).takeIf { it >= 0 }?.to(term) }
            .minByOrNull { it.first } ?: return null

        val lineStart = body.lastIndexOf('\n', hit.first).let { if (it < 0) 0 else it + 1 }
        val lineEnd = body.indexOf('\n', hit.first).let { if (it < 0) body.length else it }
        val line = body.substring(lineStart, lineEnd)
        val cleaned = BodyText.readable(
            WorkCommentSyntax.recognize(line)?.text ?: line.trimStart(),
        ).trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.length <= SNIPPET_MAX_CHARS) return cleaned

        val at = cleaned.indexOf(hit.second, ignoreCase = true)
        if (at <= SNIPPET_LEAD_CHARS) return cleaned.take(SNIPPET_MAX_CHARS).trimEnd() + ELLIPSIS
        val from = at - SNIPPET_LEAD_CHARS
        val to = (from + SNIPPET_MAX_CHARS).coerceAtMost(cleaned.length)
        val tail = if (to < cleaned.length) ELLIPSIS else ""
        return ELLIPSIS + cleaned.substring(from, to).trim() + tail
    }

    private fun tokenize(raw: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        raw.forEach { character ->
            when {
                character == QUOTE -> quoted = !quoted
                !quoted && character.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(character)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /** Decoration is the writer's tool, so a search reads the body the way a reader would. */
    private fun readable(body: String): String =
        if (body.any { it == '*' || it == '=' || it == '[' }) BodyText.readable(body) else body

    private fun fold(value: String): String = TagNameNormalizer.normalizeKey(value)
}
