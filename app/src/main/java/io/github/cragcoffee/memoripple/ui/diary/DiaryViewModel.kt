package io.github.cragcoffee.memoripple.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.DiaryRepository
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentRepository
import io.github.cragcoffee.memoripple.data.SettingsRepository
import io.github.cragcoffee.memoripple.domain.diary.DiaryCalendarNavigation
import io.github.cragcoffee.memoripple.domain.diary.PastTodayFactory
import io.github.cragcoffee.memoripple.domain.diary.PastTodayItem
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DiaryCalendarUiState(
    val displayedMonth: YearMonth,
    val selectedDate: LocalDate?,
    val today: LocalDate,
    val monthEntries: List<DiaryEntryEntity>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(
    private val repository: DiaryRepository,
    private val futureRepository: FutureDiaryCommentRepository,
    private val settingsRepository: SettingsRepository? = null,
) : ViewModel() {
    /** One week or the whole month; the title is the hinge between them. */
    val calendarCompact: StateFlow<Boolean> =
        (settingsRepository?.diaryCalendarCompact ?: kotlinx.coroutines.flow.flowOf(true))
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setCalendarCompact(compact: Boolean) {
        viewModelScope.launch {
            settingsRepository?.setDiaryCalendarCompact(compact)
            // The week view is a window around a day; entering it with nothing chosen would
            // show no week at all, so it opens on today.
            if (compact && _selectedDate.value == null) showToday()
        }
    }

    /** Selects [date] wherever it is, moving the displayed month along with it. */
    fun jumpToDate(date: LocalDate) {
        val today = LocalDate.ofEpochDay(_currentEpochDay.value)
        if (date.isAfter(today)) return
        _displayedMonth.value = YearMonth.from(date)
        _selectedDate.value = date
    }

    val entries: StateFlow<List<DiaryEntryEntity>> = repository.observeEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _currentEpochDay = MutableStateFlow(repository.currentDate().toEpochDay())
    val currentEpochDay: StateFlow<Long> = _currentEpochDay.asStateFlow()
    private val _displayedMonth = MutableStateFlow(YearMonth.from(repository.currentDate()))
    private val _selectedDate = MutableStateFlow<LocalDate?>(repository.currentDate())
    private val calendarSelection = combine(
        _displayedMonth,
        _selectedDate,
        _currentEpochDay,
    ) { displayedMonth, selectedDate, currentEpochDay ->
        DiaryCalendarUiState(
            displayedMonth = displayedMonth,
            selectedDate = selectedDate,
            today = LocalDate.ofEpochDay(currentEpochDay),
            monthEntries = emptyList(),
        )
    }
    private val monthEntries = _displayedMonth.flatMapLatest { month ->
        // A week's row can carry days of the neighbouring months, so the window is a week
        // wider than the month on both sides — their diary marks must not go dark.
        repository.observeEntriesBetween(
            month.atDay(1).minusDays(7),
            month.atEndOfMonth().plusDays(7),
        )
    }
    val calendarUiState: StateFlow<DiaryCalendarUiState> = calendarSelection
        .combine(monthEntries) { state, entries -> state.copy(monthEntries = entries) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DiaryCalendarUiState(
                displayedMonth = YearMonth.from(repository.currentDate()),
                selectedDate = repository.currentDate(),
                today = repository.currentDate(),
                monthEntries = emptyList(),
            ),
        )
    val pastTodayEntries: StateFlow<List<PastTodayItem<DiaryEntryEntity>>> = entries
        .combine(_currentEpochDay) { allEntries, currentEpochDay ->
            PastTodayFactory.create(
                values = allEntries,
                today = LocalDate.ofEpochDay(currentEpochDay),
                dateOf = { LocalDate.ofEpochDay(it.diaryDateEpochDay) },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val deliveredCount: StateFlow<Int> = futureRepository.observeDeliveredCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            futureRepository.markDueDelivered()
            val previousToday = LocalDate.ofEpochDay(_currentEpochDay.value)
            val today = repository.currentDate()
            val followedCurrentMonth = _displayedMonth.value == YearMonth.from(previousToday)
            _currentEpochDay.value = today.toEpochDay()
            if (followedCurrentMonth && YearMonth.from(previousToday) != YearMonth.from(today)) {
                _displayedMonth.value = YearMonth.from(today)
                _selectedDate.value = today
            } else if (_selectedDate.value?.isAfter(today) == true) {
                _selectedDate.value = null
            }
        }
    }

    /** A new entry on [epochDay] — the shelf's explicit 「書く」; navigation never creates one. */
    fun createEntry(epochDay: Long, onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(repository.createEntry(epochDay).id) }
    }

    fun showPreviousMonth() {
        _displayedMonth.value = DiaryCalendarNavigation.previousMonth(_displayedMonth.value)
        _selectedDate.value = null
    }

    fun showNextMonth() {
        val today = LocalDate.ofEpochDay(_currentEpochDay.value)
        val nextMonth = DiaryCalendarNavigation.nextMonth(
            displayedMonth = _displayedMonth.value,
            currentMonth = YearMonth.from(today),
        )
        _displayedMonth.value = nextMonth
        _selectedDate.value = DiaryCalendarNavigation.selectionAfterMonthChange(nextMonth, today)
    }

    fun showToday() {
        val today = LocalDate.ofEpochDay(_currentEpochDay.value)
        _displayedMonth.value = YearMonth.from(today)
        _selectedDate.value = today
    }

    fun selectDate(date: LocalDate) {
        val today = LocalDate.ofEpochDay(_currentEpochDay.value)
        if (YearMonth.from(date) == _displayedMonth.value && !date.isAfter(today)) {
            _selectedDate.value = date
        }
    }

    companion object {
        fun factory(
            repository: DiaryRepository,
            futureRepository: FutureDiaryCommentRepository,
            settingsRepository: SettingsRepository? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DiaryViewModel(repository, futureRepository, settingsRepository) as T
            }
    }
}
