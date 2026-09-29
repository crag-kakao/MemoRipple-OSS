package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.NoteLink

/** The little a memo has to expose for links between memos to be worked out. */
data class LinkableMemo(val id: Long, val title: String, val body: String)

/** A `[[title]]` in a body, with the memo it points at when there is one. */
data class OutgoingLink(val title: String, val memoId: Long?) {
    val isResolved: Boolean get() = memoId != null
}

/** A memo pointing back at the one being read. */
data class Backlink(val id: Long, val title: String)

/** Both directions of a memo's references. */
data class MemoLinks(
    val outgoing: List<OutgoingLink> = emptyList(),
    val backlinks: List<Backlink> = emptyList(),
) {
    val isEmpty: Boolean get() = outgoing.isEmpty() && backlinks.isEmpty()
}

/**
 * Works out what a memo points at and what points back at it.
 *
 * Links are matched on the title, so a link may name a memo that does not exist yet. That is kept
 * rather than dropped: writing the reference before the memo is a normal way to work.
 */
object MemoLinkGraph {

    fun of(memo: LinkableMemo, others: List<LinkableMemo>): MemoLinks = MemoLinks(
        outgoing = outgoing(memo.body, others),
        backlinks = backlinks(memo, others),
    )

    fun outgoing(body: String, memos: List<LinkableMemo>): List<OutgoingLink> {
        if (body.isBlank()) return emptyList()
        val byTitle = memos.associateBy { NoteLink.key(it.title) }
        return NoteLink.titles(body).map { title ->
            OutgoingLink(title, byTitle[NoteLink.key(title)]?.id)
        }
    }

    fun backlinks(memo: LinkableMemo, others: List<LinkableMemo>): List<Backlink> {
        val key = NoteLink.key(memo.title)
        if (key.isBlank()) return emptyList()
        return others.asSequence()
            .filter { it.id != memo.id }
            .filter { other -> NoteLink.titles(other.body).any { NoteLink.key(it) == key } }
            .map { Backlink(it.id, it.title.ifBlank { "無題のメモ" }) }
            .toList()
    }
}
