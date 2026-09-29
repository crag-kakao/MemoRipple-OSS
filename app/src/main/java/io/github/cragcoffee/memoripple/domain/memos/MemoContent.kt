package io.github.cragcoffee.memoripple.domain.memos

/**
 * A memo's content as an ordered list of blocks (docs/MEMO_CONTENT_BLOCKS.md, 2026-09-24): text
 * and photos in the order the writer put them. The blocks are the memo's source of truth; the
 * stored `body` is only [projection] of them, written by the repository in the same transaction,
 * so everything that reads a plain body — search, AI, speech, previews, export — keeps reading it.
 *
 * A block whose id is [NEW] has not been stored yet; the repository gives it one.
 */
sealed interface MemoBlock {
    val id: Long

    data class Text(override val id: Long, val text: String) : MemoBlock

    /** One photo of the memo, by its `memo_photo_attachments` row. */
    data class Photo(override val id: Long, val attachmentId: Long) : MemoBlock
}

/** Pure rules over a memo's blocks. Nothing here reads or writes storage. */
object MemoContent {
    const val NEW: Long = 0L

    /** The plain body: the texts that say something, in order, one line break between them. */
    fun projection(blocks: List<MemoBlock>): String =
        blocks.filterIsInstance<MemoBlock.Text>().map { it.text }.filter { it.isNotEmpty() }.joinToString("\n")

    /**
     * A record written before blocks existed, exactly as it looked, with the projection the body
     * unchanged. A memo's photos sat above its body ([photosFirst], the default); a journal's sat
     * under it (docs/MEMO_CONTENT_BLOCKS.md §11), then a place to write after the last photo.
     */
    fun legacy(body: String, attachmentIds: List<Long>, photosFirst: Boolean = true): List<MemoBlock> {
        val photos = attachmentIds.map { MemoBlock.Photo(NEW, it) }
        return if (photosFirst) photos + MemoBlock.Text(NEW, body) else ensureWritingPlace(listOf(MemoBlock.Text(NEW, body)) + photos)
    }

    /**
     * Reads what storage holds as a valid list, whatever it holds: no blocks at all is a legacy
     * record; a photo no block mentions joins the leading photos ([photosFirst]) or the closing
     * ones, in front of the last place to write; a block whose photo is gone is dropped. Nothing
     * is written — the next write stores the result.
     */
    fun resolve(stored: List<MemoBlock>, body: String, attachmentIds: List<Long>, photosFirst: Boolean = true): List<MemoBlock> {
        if (stored.none { it is MemoBlock.Text }) return legacy(body, attachmentIds, photosFirst)
        val known = attachmentIds.toSet()
        val kept = stored.filter { it !is MemoBlock.Photo || it.attachmentId in known }
        val placed = kept.filterIsInstance<MemoBlock.Photo>().map { it.attachmentId }.toSet()
        val unplaced = attachmentIds.filter { it !in placed }.map { MemoBlock.Photo(NEW, it) }
        if (unplaced.isEmpty()) return ensureWritingPlace(kept)
        if (!photosFirst) {
            val closing = kept.lastOrNull()
            return if (closing is MemoBlock.Text && closing.text.isEmpty() && kept.size > 1) {
                kept.dropLast(1) + unplaced + closing
            } else {
                ensureWritingPlace(kept + unplaced)
            }
        }
        val lead = kept.takeWhile { it is MemoBlock.Photo }
        return ensureWritingPlace(lead + unplaced + kept.drop(lead.size))
    }

    /**
     * The shape every write leaves: a memo with no photo is one text block (its texts joined — the
     * projection does not move), and the last block is text, so there is always a place to write
     * after the last photo.
     */
    fun normalize(blocks: List<MemoBlock>): List<MemoBlock> {
        if (blocks.none { it is MemoBlock.Photo }) {
            val first = blocks.firstOrNull { it is MemoBlock.Text } as MemoBlock.Text?
            return listOf(MemoBlock.Text(first?.id ?: NEW, projection(blocks)))
        }
        return ensureWritingPlace(blocks)
    }

    private fun ensureWritingPlace(blocks: List<MemoBlock>): List<MemoBlock> =
        if (blocks.lastOrNull() is MemoBlock.Text) blocks else blocks + MemoBlock.Text(NEW, "")

    /**
     * Photos added while [activeTextId] is being written, where its [caret] is (null = its end):
     * - at the end: right after that text, then an empty text to go on writing in (unless an
     *   empty text already follows);
     * - at the start, or an empty text: in front of it, and it stays the writing place;
     * - inside the text: the text is cut there — the words before the caret stay above the
     *   photos, the words after it go below them and writing goes on at their start. A line
     *   break at the cut becomes the boundary itself, so a cut at a line's edge keeps the
     *   projection as it was.
     * Returns the blocks and the index of the text the caret should go to.
     */
    fun insertPhotos(blocks: List<MemoBlock>, activeTextId: Long?, attachmentIds: List<Long>, caret: Int? = null): Insertion {
        val photos = attachmentIds.map { MemoBlock.Photo(NEW, it) }
        if (photos.isEmpty()) return Insertion(blocks, blocks.indexOfLast { it is MemoBlock.Text })
        val activeIndex = blocks.indexOfFirst { it.id == activeTextId && it is MemoBlock.Text }
            .takeIf { it >= 0 } ?: blocks.indexOfLast { it is MemoBlock.Text }
        val active = blocks.getOrNull(activeIndex) as MemoBlock.Text?
        if (active == null) {
            val result = blocks + photos + MemoBlock.Text(NEW, "")
            return Insertion(result, result.lastIndex)
        }
        val at = caret ?: active.text.length
        if (active.text.isEmpty() || at <= 0) {
            val result = blocks.take(activeIndex) + photos + blocks.drop(activeIndex)
            return Insertion(result, activeIndex + photos.size)
        }
        if (at < active.text.length) {
            var above = active.text.substring(0, at)
            var below = active.text.substring(at)
            if (above.endsWith('\n')) above = above.dropLast(1) else if (below.startsWith('\n')) below = below.drop(1)
            val result = blocks.take(activeIndex) + MemoBlock.Text(active.id, above) + photos +
                MemoBlock.Text(NEW, below) + blocks.drop(activeIndex + 1)
            return Insertion(result, activeIndex + 1 + photos.size)
        }
        val before = blocks.take(activeIndex + 1) + photos
        val after = blocks.drop(activeIndex + 1)
        val next = after.firstOrNull()
        return if (next is MemoBlock.Text && next.text.isEmpty()) {
            Insertion(before + after, before.size)
        } else {
            Insertion(before + MemoBlock.Text(NEW, "") + after, before.size)
        }
    }

    data class Insertion(val blocks: List<MemoBlock>, val writingIndex: Int)

    /**
     * Backspace at the start of a text whose previous block is also text: the two become one,
     * exactly as deleting the line break between them — the projection loses that one break when
     * both said something. Returns null when the previous block is not text (a photo is never
     * removed from the keyboard). The caret goes to the join.
     */
    fun mergeWithPrevious(blocks: List<MemoBlock>, textBlockId: Long): Merge? {
        val index = blocks.indexOfFirst { it.id == textBlockId && it is MemoBlock.Text }
        if (index <= 0) return null
        val previous = blocks[index - 1] as? MemoBlock.Text ?: return null
        val current = blocks[index] as MemoBlock.Text
        val merged = MemoBlock.Text(previous.id, previous.text + current.text)
        val result = blocks.take(index - 1) + merged + blocks.drop(index + 1)
        return Merge(normalize(result), previous.id, previous.text.length)
    }

    data class Merge(val blocks: List<MemoBlock>, val textBlockId: Long, val caret: Int)

    /** Photos after a reorder: the slots keep their places; the pictures in them change. */
    fun reassignPhotos(blocks: List<MemoBlock>, orderedAttachmentIds: List<Long>): List<MemoBlock> {
        var next = 0
        return blocks.map { block ->
            if (block is MemoBlock.Photo && next < orderedAttachmentIds.size) {
                block.copy(attachmentId = orderedAttachmentIds[next++])
            } else {
                block
            }
        }
    }

    /**
     * For the reading view, which draws the projection line by line: the photos to draw before
     * each projection line (a key equal to the number of lines means after the last one).
     */
    fun photoBreaks(blocks: List<MemoBlock>): Map<Int, List<Long>> {
        val breaks = linkedMapOf<Int, MutableList<Long>>()
        var lines = 0
        blocks.forEach { block ->
            when (block) {
                is MemoBlock.Text -> if (block.text.isNotEmpty()) lines += block.text.split('\n').size
                is MemoBlock.Photo -> breaks.getOrPut(lines) { mutableListOf() } += block.attachmentId
            }
        }
        return breaks
    }

    /** A projection line as (text block, its own line) — for the doors that open writing on a line. */
    fun lineToBlock(blocks: List<MemoBlock>, projectionLine: Int): Pair<Long, Int>? {
        var start = 0
        var lastText: MemoBlock.Text? = null
        blocks.forEach { block ->
            if (block is MemoBlock.Text && block.text.isNotEmpty()) {
                val count = block.text.split('\n').size
                if (projectionLine < start + count) return block.id to (projectionLine - start)
                start += count
                lastText = block
            }
        }
        return lastText?.let { it.id to (it.text.split('\n').size - 1) }
    }

    /**
     * A whole new body from a writer that only knows plain text (AI append, the split pane, an
     * import) laid onto the blocks without losing a photo or a word. The change is found as the
     * part between the common head and tail of the old projection and the new body:
     * - inside one text (or at its end) → only that text changes;
     * - at the very end of the note when the note ends in a text after its last photo → that
     *   last text (the one line break the projection puts between texts is not doubled);
     * - across several texts → they become one, and the photos that sat between them follow it
     *   in their order.
     */
    fun reconcile(blocks: List<MemoBlock>, newBody: String): List<MemoBlock> {
        val old = projection(blocks)
        if (old == newBody) return blocks
        val texts = blocks.withIndex().filter { (_, b) -> b is MemoBlock.Text && b.text.isNotEmpty() }
        if (texts.isEmpty()) {
            val lastText = blocks.indexOfLast { it is MemoBlock.Text }
            if (lastText < 0) return normalize(blocks + MemoBlock.Text(NEW, newBody))
            return blocks.toMutableList().also { it[lastText] = MemoBlock.Text(blocks[lastText].id, newBody) }
        }
        var prefix = 0
        val maxPrefix = minOf(old.length, newBody.length)
        while (prefix < maxPrefix && old[prefix] == newBody[prefix]) prefix++
        var suffix = 0
        while (suffix < maxPrefix - prefix && old[old.length - 1 - suffix] == newBody[newBody.length - 1 - suffix]) suffix++
        val a = prefix
        val b = old.length - suffix
        val inserted = newBody.substring(prefix, newBody.length - suffix)

        // Where each saying text sits in the projection.
        val spans = ArrayList<Triple<Int, Int, Int>>() // (block index, start, end)
        var cursor = 0
        texts.forEach { (index, block) ->
            val text = (block as MemoBlock.Text).text
            spans += Triple(index, cursor, cursor + text.length)
            cursor += text.length + 1
        }
        // Appended at the very end, and the note closes on a text after its last photo.
        val closing = blocks.lastOrNull() as? MemoBlock.Text
        val lastSpanBlock = spans.last().first
        if (a == old.length && b == old.length && closing != null && blocks.lastIndex > lastSpanBlock) {
            val addition = if (closing.text.isEmpty()) inserted.removePrefix("\n") else inserted
            return blocks.toMutableList().also {
                it[it.lastIndex] = MemoBlock.Text(closing.id, closing.text + addition)
            }
        }
        val first = spans.indexOfFirst { a <= it.third }.let { if (it < 0) spans.lastIndex else it }
        val last = spans.indexOfFirst { b <= it.third }.let { if (it < 0) spans.lastIndex else it }.coerceAtLeast(first)
        val (firstIndex, firstStart, _) = spans[first]
        val (lastIndex, lastStart, _) = spans[last]
        val firstText = (blocks[firstIndex] as MemoBlock.Text).text
        val lastText = (blocks[lastIndex] as MemoBlock.Text).text
        val head = firstText.substring(0, (a - firstStart).coerceIn(0, firstText.length))
        val tail = lastText.substring((b - lastStart).coerceIn(0, lastText.length))
        val merged = MemoBlock.Text(blocks[firstIndex].id, head + inserted + tail)
        if (first == last) {
            return blocks.toMutableList().also { it[firstIndex] = merged }
        }
        val between = blocks.subList(firstIndex + 1, lastIndex + 1)
        val carriedPhotos = between.filterIsInstance<MemoBlock.Photo>()
        return normalize(blocks.take(firstIndex) + merged + carriedPhotos + blocks.drop(lastIndex + 1))
    }
}
