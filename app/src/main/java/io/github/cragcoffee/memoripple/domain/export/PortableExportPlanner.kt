package io.github.cragcoffee.memoripple.domain.export

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Decides what the ZIP holds and where, before a single byte is written: which shelves are in
 * (trash only on request), what every folder and file is called, and one deterministic order —
 * newest first for memos and notes, newest date first for diaries — so the same data always
 * exports as the same archive. Naming goes through [PortableExportNaming]; nothing here can
 * emit an unsafe path.
 */
object PortableExportPlanner {

    const val ROOT = "MemoRipple-Export"

    data class PlannedPhoto(
        /** Full ZIP entry path of the copied photo. */
        val entryPath: String,
        /** How the record's own Markdown reaches it, relative to that Markdown file. */
        val markdownRef: String,
        /** 1-based place in the record's photo order — the number a reader sees. */
        val displayIndex: Int,
        val photo: PortablePhoto,
    )

    data class PlannedMemo(
        val memo: PortableMemo,
        val markdownPath: String,
        val photos: List<PlannedPhoto>,
    )

    data class PlannedDiary(
        val diary: PortableDiary,
        val year: Int,
        val dateLabel: String,
        /** `HH:mm` the entry was begun — with several entries a day the index needs it. */
        val timeLabel: String,
        val markdownPath: String,
        val photos: List<PlannedPhoto>,
    )

    data class PlannedEpisodeFile(
        val episode: PortableEpisode,
        val sectionTitle: String?,
        val markdownPath: String,
        val photos: List<PlannedPhoto>,
    )

    data class PlannedNote(
        val note: PortableNote,
        val readmePath: String,
        val coverPhoto: PlannedPhoto?,
        val episodeFiles: List<PlannedEpisodeFile>,
    )

    data class PortableExportPlan(
        val activeMemos: List<PlannedMemo>,
        val archivedMemos: List<PlannedMemo>,
        val trashedMemos: List<PlannedMemo>,
        val diaries: List<PlannedDiary>,
        val notes: List<PlannedNote>,
    ) {
        val allMemos: List<PlannedMemo> get() = activeMemos + archivedMemos + trashedMemos

        /** The count a progress bar walks: one unit per document, one per photo. */
        val totalUnits: Int
            get() = allMemos.sumOf { 1 + it.photos.size } +
                diaries.sumOf { 1 + it.photos.size } +
                notes.sumOf { note ->
                    1 + (if (note.coverPhoto != null) 1 else 0) +
                        note.episodeFiles.sumOf { 1 + it.photos.size }
                }
    }

    fun plan(
        snapshot: PortableSnapshot,
        includeTrash: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PortableExportPlan {
        val newestFirst = compareByDescending<PortableMemo> { it.updatedAt }
            .thenByDescending { it.id }
        val memos = snapshot.memos.sortedWith(newestFirst)
        val dayStamp = DateTimeFormatter.ofPattern("yyyyMMdd")

        fun planMemo(memo: PortableMemo, shelfDir: String): PlannedMemo {
            val created = Instant.ofEpochMilli(memo.createdAt).atZone(zone).toLocalDate()
            val folder = "$ROOT/$shelfDir/" +
                PortableExportNaming.memoFolder(created.format(dayStamp), memo.title, memo.id)
            return PlannedMemo(
                memo = memo,
                markdownPath = "$folder/memo.md",
                photos = planPhotos(memo.photos, "$folder/photos", "photos"),
            )
        }

        val active = memos.filter { it.shelf == PortableShelf.ACTIVE }
            .map { planMemo(it, "memos/active") }
        val archived = memos.filter { it.shelf == PortableShelf.ARCHIVED }
            .map { planMemo(it, "memos/archived") }
        val trashed = if (includeTrash) {
            memos.filter { it.shelf == PortableShelf.TRASHED }.map { planMemo(it, "trash") }
        } else {
            emptyList()
        }

        // Several entries may share a day (HANDOFF §16.18): each gets its own folder inside the day,
        // named by the minute it was begun plus its id, so even two entries of one minute never collide.
        val minuteStamp = DateTimeFormatter.ofPattern("HHmm")
        val diaries = snapshot.diaries
            .sortedWith(compareByDescending<PortableDiary> { it.epochDay }.thenByDescending { it.createdAt }.thenByDescending { it.id })
            .map { diary ->
                val date = LocalDate.ofEpochDay(diary.epochDay)
                val begun = Instant.ofEpochMilli(diary.createdAt).atZone(zone)
                val minute = begun.format(minuteStamp)
                val folder = "$ROOT/diaries/${date.year}/$date/$minute-${diary.id}"
                PlannedDiary(
                    diary = diary,
                    year = date.year,
                    dateLabel = date.toString(),
                    timeLabel = begun.format(DateTimeFormatter.ofPattern("HH:mm")),
                    markdownPath = "$folder/diary.md",
                    photos = planPhotos(diary.photos, "$folder/photos", "photos"),
                )
            }

        val notes = snapshot.notes
            .sortedWith(
                compareByDescending<PortableNote> { it.updatedAt }.thenByDescending { it.id },
            )
            .map { note ->
                val folder = "$ROOT/notes/" + PortableExportNaming.noteFolder(note.title, note.id)
                val cover = note.coverPhoto?.let { photo ->
                    PlannedPhoto(
                        entryPath = "$folder/photos/cover" +
                            PortableExportNaming.photoExtension(photo.mimeType),
                        markdownRef = "photos/cover" +
                            PortableExportNaming.photoExtension(photo.mimeType),
                        displayIndex = 1,
                        photo = photo,
                    )
                }
                val episodeFiles = note.sections.flatMap { section ->
                    section.episodes.map { episode ->
                        val number = episode.number.toString().padStart(2, '0')
                        PlannedEpisodeFile(
                            episode = episode,
                            sectionTitle = section.title,
                            markdownPath = "$folder/episodes/" +
                                PortableExportNaming.chapterFileName(episode.number, episode.title),
                            // Episode photos live in the note's one photos folder, prefixed by
                            // the episode number so orders never tangle across episodes.
                            photos = episode.photos.mapIndexed { index, photo ->
                                val name = "$number-" +
                                    PortableExportNaming.photoFileName(index + 1, photo.mimeType)
                                PlannedPhoto(
                                    entryPath = "$folder/photos/$name",
                                    markdownRef = "../photos/$name",
                                    displayIndex = index + 1,
                                    photo = photo,
                                )
                            },
                        )
                    }
                }
                PlannedNote(
                    note = note,
                    readmePath = "$folder/README.md",
                    coverPhoto = cover,
                    episodeFiles = episodeFiles,
                )
            }

        return PortableExportPlan(
            activeMemos = active,
            archivedMemos = archived,
            trashedMemos = trashed,
            diaries = diaries,
            notes = notes,
        )
    }

    private fun planPhotos(
        photos: List<PortablePhoto>,
        photoDirPath: String,
        markdownRefDir: String,
    ): List<PlannedPhoto> = photos.mapIndexed { index, photo ->
        val name = PortableExportNaming.photoFileName(index + 1, photo.mimeType)
        PlannedPhoto(
            entryPath = "$photoDirPath/$name",
            markdownRef = "$markdownRefDir/$name",
            displayIndex = index + 1,
            photo = photo,
        )
    }
}
