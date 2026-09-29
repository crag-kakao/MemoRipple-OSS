package io.github.cragcoffee.memoripple.domain.notes

/** The colour a note's cover is drawn in. Ids are persisted, so they do not change. */
enum class NoteCoverColor(val storageId: String) {
    PLUM("plum"),
    INDIGO("indigo"),
    AMBER("amber"),
    MOSS("moss"),
    SLATE("slate"),

    // The five above are all deep. A shelf of them reads as one colour from a distance, so there
    // are light ones too. These stay light in either theme: a cover chosen for being pale should
    // not turn dark because the app did.
    SAKURA("sakura"),
    SKY("sky"),
    SAND("sand"),
    MINT("mint"),
    ;

    companion object {
        val Default = MOSS

        fun fromStorageId(value: String?): NoteCoverColor =
            entries.firstOrNull { it.storageId == value } ?: Default

        /** A colour taken from the title, so a new note is not the same as the last one. */
        fun suggestedFor(title: String): NoteCoverColor = entries[title.hashCode().mod(entries.size)]
    }
}

/** One episode as the note shows it: the memo, its place, and the number that place gives it. */
data class NoteEpisode(
    val memoId: Long,
    val title: String,
    val characterCount: Int,
    val updatedAt: Long,
    val number: Int,
)

/** A heading with the episodes under it. The heading is null for the ones under none. */
data class NoteSection(
    val chapterId: Long?,
    val title: String?,
    val episodes: List<NoteEpisode>,
)

/**
 * Arranges a note's episodes for reading.
 *
 * Chapters are headings placed over a single ordered run of episodes, not containers that hold
 * them. So the numbering runs straight through the note, and an episode under no chapter is one
 * that has not been placed under a heading yet: it comes first, where writing starts.
 */
object NoteStructure {

    data class ChapterInput(val id: Long, val title: String, val sortOrder: Int)

    data class EpisodeInput(
        val memoId: Long,
        val title: String,
        val characterCount: Int,
        val updatedAt: Long,
        val chapterId: Long?,
    )

    fun sections(
        chapters: List<ChapterInput>,
        episodes: List<EpisodeInput>,
    ): List<NoteSection> {
        val numbered = episodes.mapIndexed { index, episode ->
            episode to NoteEpisode(
                memoId = episode.memoId,
                title = episode.title,
                characterCount = episode.characterCount,
                updatedAt = episode.updatedAt,
                number = index + 1,
            )
        }
        val byChapter = numbered.groupBy({ it.first.chapterId }, { it.second })
        val known = chapters.mapTo(hashSetOf(), ChapterInput::id)

        return buildList {
            // Episodes under no heading, plus any whose heading has gone, start the note.
            val loose = numbered
                .filter { it.first.chapterId == null || it.first.chapterId !in known }
                .map { it.second }
            if (loose.isNotEmpty()) add(NoteSection(null, null, loose))
            chapters.sortedWith(compareBy({ it.sortOrder }, { it.id })).forEach { chapter ->
                add(NoteSection(chapter.id, chapter.title, byChapter[chapter.id].orEmpty()))
            }
        }
    }

    /** Every episode in reading order, whichever heading it sits under. */
    fun flatten(sections: List<NoteSection>): List<NoteEpisode> =
        sections.flatMap(NoteSection::episodes).sortedBy(NoteEpisode::number)
}
