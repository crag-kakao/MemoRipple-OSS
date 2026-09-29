package io.github.cragcoffee.memoripple.domain.outline

/**
 * Which photos an outline counts (docs/OUTLINE_PHOTO_ROWS.md §7.3). Two things are kept apart:
 * a photo the outline **shows** — a photo row in the document now — and a photo record it only
 * **holds**: taken out and kept for 元に戻す, waiting to be let go, or back from a process death
 * before it was placed. The 20-photo cap counts only what is shown (plus what is about to be
 * placed); what is only held is never counted, and its file is kept by its own rules.
 */
object OutlinePhotos {
    /** The photos the document shows, one per photo row. */
    fun shown(document: OutlineDocument?): Set<Long> =
        document?.entries.orEmpty().mapNotNullTo(LinkedHashSet()) { (it as? OutlineNode)?.photoAttachmentId }

    /** How many photos count toward the cap: those shown and those about to be placed. */
    fun activeCount(document: OutlineDocument?, pending: List<Long>): Int = (shown(document) + pending).size

    /**
     * The photos an import places: the new ones, then — for a picture that was already there —
     * that same photo again, but only when it is held and not shown (taken out in this session
     * and kept for 元に戻す). A picture a row shows, or one already about to be placed, is not
     * placed twice: it stays "already there".
     */
    fun toPlace(addedIds: List<Long>, duplicateIds: List<Long>, document: OutlineDocument?, pending: List<Long>): List<Long> {
        val shown = shown(document)
        return addedIds + duplicateIds.filter { it !in shown && it !in pending }.distinct()
    }
}
