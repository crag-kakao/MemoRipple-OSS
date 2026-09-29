package io.github.cragcoffee.memoripple.domain.diary

/** Where the old date route lands: one particular entry, or the day's list. */
sealed interface JournalDateTarget {
    data class Entry(val entryId: Long) : JournalDateTarget
    data object DayList : JournalDateTarget
}

/**
 * The compatibility rule for `diary-editor/{epochDay}`. A date is not an identity: exactly
 * one entry on the day opens that entry; none or several open the day's list. Creating an
 * entry is never a side effect of navigation — the list offers it as an action.
 */
object JournalDateRouting {
    fun resolve(entryIdsOfTheDay: List<Long>): JournalDateTarget =
        entryIdsOfTheDay.singleOrNull()?.let(JournalDateTarget::Entry) ?: JournalDateTarget.DayList
}
