package io.github.cragcoffee.memoripple.domain.export

/**
 * What the portable export reads, already filtered and ordered by the snapshot layer. These
 * types carry no Room ids beyond what naming needs, no lanes, no velocities — only what a
 * person opening the ZIP years later should find.
 */

/** One photo to copy: the blob it comes from and the format a viewer expects. */
data class PortablePhoto(
    val sha256: String,
    val mimeType: String,
    val sizeBytes: Long,
)

/** One user comment, with only the expression choices that differ from the defaults. */
data class PortableComment(
    val text: String,
    val expressionNotes: List<String>,
)

enum class PortableShelf { ACTIVE, ARCHIVED, TRASHED }

data class PortableMemo(
    val id: Long,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val favorite: Boolean,
    val pinned: Boolean,
    val shelf: PortableShelf,
    val tags: List<String>,
    val comments: List<PortableComment>,
    val photos: List<PortablePhoto>,
    /** An outline (kind `outline`), not a memo: its file says so (docs/OUTLINE_EXPORT_IMPORT.md). */
    val outline: Boolean = false,
)

/** A future comment the user has already been shown; anything unopened never reaches here. */
data class PortableFutureComment(
    val text: String,
    val expressionNotes: List<String>,
)

data class PortableDiary(
    val id: Long,
    val epochDay: Long,
    val body: String,
    val stateLabel: String,
    val photos: List<PortablePhoto>,
    val futureComments: List<PortableFutureComment>,
    /** When the entry was begun; with the id it names the entry's folder inside its day. */
    val createdAt: Long = 0L,
)

data class PortableEpisode(
    val id: Long,
    val number: Int,
    val title: String,
    val body: String,
    val photos: List<PortablePhoto>,
)

/** A heading over a run of episodes; null title means the run before any heading. */
data class PortableNoteSection(
    val title: String?,
    val episodes: List<PortableEpisode>,
)

data class PortableNote(
    val id: Long,
    val title: String,
    val subtitle: String,
    val createdAt: Long,
    val updatedAt: Long,
    val coverPhoto: PortablePhoto?,
    val sections: List<PortableNoteSection>,
)

data class PortableSnapshot(
    val memos: List<PortableMemo>,
    val diaries: List<PortableDiary>,
    val notes: List<PortableNote>,
)
