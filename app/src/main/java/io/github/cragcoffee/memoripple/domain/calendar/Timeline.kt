package io.github.cragcoffee.memoripple.domain.calendar

import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentTitles
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.memos.memoKind
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The calendar speaks the shared Document vocabulary (`domain/documents`): a memo and an outline
 * are both `memos` rows, a journal entry a `diary_entries` row, and a row opens through its
 * [DocumentRef]. Only the label — how the kind reads on this screen — is the calendar's own.
 */
val DocumentKind.label: String
    get() = when (this) {
        DocumentKind.MEMO -> "メモ"
        DocumentKind.OUTLINE -> "アウトライン"
        DocumentKind.JOURNAL -> "日記"
    }

/** What the document did on the day shown. */
enum class TimelineActivity(val label: String) {
    CREATED("作成"),
    UPDATED("更新"),
    CREATED_AND_UPDATED("作成・更新"),
}

enum class TimelineFilter(val label: String) {
    ALL("すべて"),
    CREATED("作成"),
    UPDATED("更新"),
}

/**
 * One row of a day. A projection for display — built from the stored rows every time and never
 * written anywhere; the document itself stays in its own table.
 */
data class TimelineItem(
    val id: Long,
    val kind: DocumentKind,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val displayDate: LocalDate,
    val sourceRef: DocumentRef,
    val activity: TimelineActivity,
    /** The moment that put the row on this day (creation, or the update), as `HH:mm`. */
    val timeLabel: String,
    /** The same moment as millis, for ordering. */
    val atMillis: Long,
)

/**
 * One day of the month list (2026-09-23): the date and the rows that day carries, in the same
 * order the day view uses. A projection like [TimelineItem] — nothing is stored.
 */
data class TimelineDay(val date: LocalDate, val items: List<TimelineItem>)

data class DaySummary(val count: Int, val kinds: Set<DocumentKind>)

/** A month as the range queries need it: millis for memos, epoch days for journals. */
data class MonthWindow(
    val startInclusiveMillis: Long,
    val endExclusiveMillis: Long,
    val startEpochDay: Long,
    val endEpochDayInclusive: Long,
)

/**
 * The calendar's rules. A day is 00:00–23:59:59 in [zone], nothing cleverer. 作成 = createdAt on
 * the day; 更新 = updatedAt on the day when it is not the creation itself; すべて = either, and a
 * document that did both on one day is one row saying so. Journal entries sit on their diary day
 * (that is what the day means for them), and count as updated on the day they were written to.
 */
object TimelineBuckets {
    private val minute = DateTimeFormatter.ofPattern("HH:mm")

    fun dayOf(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    fun monthWindow(month: YearMonth, zone: ZoneId): MonthWindow = MonthWindow(
        startInclusiveMillis = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        endExclusiveMillis = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        startEpochDay = month.atDay(1).toEpochDay(),
        endEpochDayInclusive = month.atEndOfMonth().toEpochDay(),
    )

    fun itemsFor(
        day: LocalDate,
        memos: List<MemoEntity>,
        journals: List<DiaryEntryEntity>,
        zone: ZoneId,
        filter: TimelineFilter,
    ): List<TimelineItem> {
        val items = ArrayList<TimelineItem>()
        memos.forEach { memo ->
            val createdDay = dayOf(memo.createdAt, zone)
            val updatedDay = if (memo.updatedAt != memo.createdAt) dayOf(memo.updatedAt, zone) else null
            row(
                created = createdDay == day,
                updated = updatedDay == day,
                filter = filter,
                createdAt = memo.createdAt,
                updatedAt = memo.updatedAt,
                zone = zone,
            ) { activity, at ->
                TimelineItem(
                    id = memo.id,
                    kind = DocumentKind.of(memo.memoKind),
                    // The shared title rule; the calendar keeps 無題のメモ for an empty outline too, as it always has.
                    title = DocumentTitles.resolve(memo.title, memo.body, DocumentTitles.UNTITLED_MEMO),
                    createdAt = memo.createdAt,
                    updatedAt = memo.updatedAt,
                    displayDate = day,
                    sourceRef = DocumentRef(DocumentKind.of(memo.memoKind), memo.id),
                    activity = activity,
                    timeLabel = Instant.ofEpochMilli(at).atZone(zone).format(minute),
                    atMillis = at,
                )
            }?.let(items::add)
        }
        journals.forEach { entry ->
            val diaryDay = LocalDate.ofEpochDay(entry.diaryDateEpochDay)
            val updatedDay = if (entry.updatedAt != entry.createdAt) dayOf(entry.updatedAt, zone) else null
            row(
                created = diaryDay == day,
                updated = updatedDay == day,
                filter = filter,
                createdAt = entry.createdAt,
                updatedAt = entry.updatedAt,
                zone = zone,
            ) { activity, at ->
                TimelineItem(
                    id = entry.id,
                    kind = DocumentKind.JOURNAL,
                    title = DocumentTitles.resolve("", entry.body, DocumentTitles.EMPTY_JOURNAL),
                    createdAt = entry.createdAt,
                    updatedAt = entry.updatedAt,
                    displayDate = day,
                    sourceRef = DocumentRef(DocumentKind.JOURNAL, entry.id),
                    activity = activity,
                    timeLabel = Instant.ofEpochMilli(at).atZone(zone).format(minute),
                    atMillis = at,
                )
            }?.let(items::add)
        }
        return items.sortedWith(compareBy({ it.atMillis }, { it.kind.ordinal }, { it.id }))
    }

    /**
     * The whole month as days, newest first (2026-09-23): 「すべて」 is no longer a day of one kind
     * but the month's own list. It is the **same rule** applied to every day the rows touch —
     * [itemsFor] per day, the same 作成 / 更新 / すべて meanings, the same order inside a day — so a
     * month reads exactly as the sum of its days. Days outside [month] are left out even when a
     * row touched them, and a day with nothing to show is not a day.
     */
    fun monthItems(
        month: YearMonth,
        memos: List<MemoEntity>,
        journals: List<DiaryEntryEntity>,
        zone: ZoneId,
        filter: TimelineFilter,
    ): List<TimelineDay> {
        val days = daySummaries(memos, journals, zone).keys.filter { YearMonth.from(it) == month }
        // A month is read once through: a document created on one day and updated on another is one
        // row, on the later of the two, and its own 作成 / 更新 label still says what it did. The day
        // view is unchanged — there each day answers for itself.
        val latestPerDocument = days
            .flatMap { day -> itemsFor(day, memos, journals, zone, filter) }
            .groupBy { it.sourceRef }
            .mapNotNull { (_, rows) -> rows.maxByOrNull { it.atMillis } }
        return latestPerDocument
            .groupBy { it.displayDate }
            .toSortedMap(reverseOrder())
            .map { (day, rows) -> TimelineDay(day, rows.sortedWith(compareBy({ it.atMillis }, { it.kind.ordinal }, { it.id }))) }
    }

    /** Which days of the given rows carry anything, and what — for the month grid's marks. */
    fun daySummaries(
        memos: List<MemoEntity>,
        journals: List<DiaryEntryEntity>,
        zone: ZoneId,
    ): Map<LocalDate, DaySummary> {
        val touched = HashMap<LocalDate, MutableList<DocumentKind>>()
        fun mark(day: LocalDate, kind: DocumentKind) { touched.getOrPut(day) { ArrayList() }.add(kind) }
        memos.forEach { memo ->
            val kind = DocumentKind.of(memo.memoKind)
            val createdDay = dayOf(memo.createdAt, zone)
            mark(createdDay, kind)
            if (memo.updatedAt != memo.createdAt) {
                val updatedDay = dayOf(memo.updatedAt, zone)
                if (updatedDay != createdDay) mark(updatedDay, kind)
            }
        }
        journals.forEach { entry ->
            val diaryDay = LocalDate.ofEpochDay(entry.diaryDateEpochDay)
            mark(diaryDay, DocumentKind.JOURNAL)
            if (entry.updatedAt != entry.createdAt) {
                val updatedDay = dayOf(entry.updatedAt, zone)
                if (updatedDay != diaryDay) mark(updatedDay, DocumentKind.JOURNAL)
            }
        }
        return touched.mapValues { (_, kinds) -> DaySummary(kinds.size, kinds.toSet()) }
    }

    private inline fun row(
        created: Boolean,
        updated: Boolean,
        filter: TimelineFilter,
        createdAt: Long,
        updatedAt: Long,
        zone: ZoneId,
        build: (TimelineActivity, Long) -> TimelineItem,
    ): TimelineItem? {
        val activity = when {
            created && updated -> TimelineActivity.CREATED_AND_UPDATED
            created -> TimelineActivity.CREATED
            updated -> TimelineActivity.UPDATED
            else -> return null
        }
        val shown = when (filter) {
            TimelineFilter.ALL -> true
            TimelineFilter.CREATED -> created
            TimelineFilter.UPDATED -> updated
        }
        if (!shown) return null
        val at = if (created) createdAt else updatedAt
        return build(activity, at)
    }
}
