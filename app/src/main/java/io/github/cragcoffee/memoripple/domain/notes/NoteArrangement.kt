package io.github.cragcoffee.memoripple.domain.notes

/**
 * A note as one run of rows, with the headings standing among the episodes rather than over them.
 *
 * This is what `NoteStructure` has always said a chapter is: a heading placed over a single ordered
 * run, not a container holding it. Arranging is where the difference shows. A heading moves one row
 * at a time like anything else, and passing an episode is how an episode changes hands: whatever
 * follows a heading belongs to it, until the next heading.
 *
 * So membership is not dragged, it is **read from where things ended up**. `chapterId` is still
 * stored, but it is written from the order rather than moved alongside it, which is the same way
 * this app decides everything else it could have stored twice.
 */
object NoteArrangement {

    sealed interface Row {
        val key: String

        data class Chapter(val id: Long) : Row {
            override val key: String get() = "chapter-$id"
        }

        data class Episode(val memoId: Long) : Row {
            override val key: String get() = "episode-$memoId"
        }
    }

    /** Where each episode ended up, and in what order the headings stand. */
    data class Placement(
        val chapterIds: List<Long>,
        /** Episodes in reading order, each with the heading it now falls under. */
        val episodes: List<Pair<Long, Long?>>,
    )

    /** The note flattened into the rows a reader sees, headings included. */
    fun rows(sections: List<NoteSection>): List<Row> = buildList {
        sections.forEach { section ->
            section.chapterId?.let { add(Row.Chapter(it)) }
            section.episodes.forEach { add(Row.Episode(it.memoId)) }
        }
    }

    /**
     * [rows] with the row at [index] swapped with its neighbour, or null when there is none.
     *
     * One step at a time, which is what makes a heading able to cross an episode: the two simply
     * trade places, and what that means for membership falls out of [placement].
     */
    fun moved(rows: List<Row>, index: Int, delta: Int): List<Row>? {
        val to = index + delta
        if (index !in rows.indices || to !in rows.indices) return null
        val moved = rows.toMutableList()
        moved[index] = rows[to]
        moved[to] = rows[index]
        return moved
    }

    /** Reads the arrangement back out of [rows]: an episode belongs to the heading above it. */
    fun placement(rows: List<Row>): Placement {
        val chapterIds = mutableListOf<Long>()
        val episodes = mutableListOf<Pair<Long, Long?>>()
        var current: Long? = null
        rows.forEach { row ->
            when (row) {
                is Row.Chapter -> {
                    chapterIds += row.id
                    current = row.id
                }

                is Row.Episode -> episodes += row.memoId to current
            }
        }
        return Placement(chapterIds, episodes)
    }
}
