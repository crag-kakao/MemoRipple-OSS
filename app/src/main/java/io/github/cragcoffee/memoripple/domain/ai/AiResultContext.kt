package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary

/**
 * The result list of one request, numbered as the model sees it: `result_1` is the first line.
 * Only the [Resolver] reads the [DocumentRef] behind a ref; the model sees [shownLines] — label and
 * title, never an id. Request-scoped: built from a search, used for the next proposal, dropped.
 * It is deliberately not serializable and never stored.
 */
class AiResultContext private constructor(private val entries: List<DocumentSummary>) {
    val size: Int get() = entries.size

    operator fun contains(ref: AiResultRef): Boolean = ref.index in 1..entries.size

    fun summary(ref: AiResultRef): DocumentSummary? = entries.getOrNull(ref.index - 1)

    fun ref(ref: AiResultRef): DocumentRef? = summary(ref)?.ref

    /** The lines a prompt would carry: `result_N: title`. */
    fun shownLines(): List<String> = entries.mapIndexed { i, s -> "${AiResultRef(i + 1).label}: ${s.title}" }

    companion object {
        val EMPTY = AiResultContext(emptyList())

        /** The first 99 results, in the order they were shown. */
        fun of(results: List<DocumentSummary>): AiResultContext = AiResultContext(results.take(99))
    }
}
