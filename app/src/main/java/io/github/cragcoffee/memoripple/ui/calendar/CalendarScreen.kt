package io.github.cragcoffee.memoripple.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.rememberLazyListState
import io.github.cragcoffee.memoripple.ui.OnTabReselect
import io.github.cragcoffee.memoripple.ui.TopLevelTab
import io.github.cragcoffee.memoripple.ui.scrollToTopOnReselect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.DiaryRepository
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentRepository
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.domain.calendar.DaySummary
import io.github.cragcoffee.memoripple.domain.calendar.label
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.calendar.TimelineBuckets
import io.github.cragcoffee.memoripple.domain.calendar.TimelineDay
import io.github.cragcoffee.memoripple.domain.calendar.TimelineFilter
import io.github.cragcoffee.memoripple.domain.calendar.TimelineItem
import io.github.cragcoffee.memoripple.domain.diary.DiaryCalendarGridFactory
import io.github.cragcoffee.memoripple.domain.diary.DiaryCalendarMonth
import io.github.cragcoffee.memoripple.domain.diary.PastTodayFactory
import io.github.cragcoffee.memoripple.domain.diary.PastTodayItem
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.ui.components.ProductEmptyState
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductCompactTopBar
import io.github.cragcoffee.memoripple.ui.components.SectionHeader
import io.github.cragcoffee.memoripple.ui.diary.FutureDeliveryBanner
import io.github.cragcoffee.memoripple.ui.diary.PastTodayCard
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * カレンダー: one time axis over memos, outlines and journal entries (HANDOFF §16.18). A view
 * built from the rows the month's range queries return — never a copy of a document. A day is
 * midnight to midnight in the device zone; 作成 / 更新 / すべて are `TimelineBuckets`' rules.
 */
data class CalendarUiState(
    val month: YearMonth,
    val today: LocalDate,
    val selectedDate: LocalDate,
    val filter: TimelineFilter,
    val grid: DiaryCalendarMonth,
    val days: Map<LocalDate, DaySummary>,
    val items: List<TimelineItem>,
    /**
     * 「すべて」 (2026-09-23): the whole displayed month, day by day, newest first. 作成 / 更新 stay
     * the selected day's, so the two uses of the calendar both keep a home.
     */
    val monthItems: List<TimelineDay> = emptyList(),
    /** Future comments delivered and not yet received — the banner's count, as on the diary page. */
    val deliveredCount: Int = 0,
    /** 過去の今日: the same month-day in earlier years, over every journal. */
    val pastToday: List<PastTodayItem<DiaryEntryEntity>> = emptyList(),
)

class CalendarViewModel(
    private val memoRepository: MemoRepository,
    private val diaryRepository: DiaryRepository,
    private val futureRepository: FutureDiaryCommentRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {
    private val today = MutableStateFlow(timeProvider.currentLocalDate())
    private val month = MutableStateFlow(YearMonth.from(today.value))
    private val selectedDate = MutableStateFlow(today.value)
    private val filter = MutableStateFlow(TimelineFilter.ALL)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val monthRows = month.flatMapLatest { shown ->
        val window = TimelineBuckets.monthWindow(shown, timeProvider.currentZoneId())
        combine(
            memoRepository.observeTouchedBetween(window.startInclusiveMillis, window.endExclusiveMillis),
            diaryRepository.observeEntriesBetween(shown.atDay(1), shown.atEndOfMonth()),
        ) { memos, journals -> memos to journals }
    }

    // The two diary-page jobs that moved here keep their own logic: the delivery count is the
    // repository's, 過去の今日 is PastTodayFactory over every journal. Nothing is copied.
    private val diaryExtras = combine(
        futureRepository.observeDeliveredCount(),
        diaryRepository.observeEntries(),
        today,
    ) { delivered, entries, todayValue ->
        delivered to PastTodayFactory.create(
            values = entries,
            today = todayValue,
            dateOf = { LocalDate.ofEpochDay(it.diaryDateEpochDay) },
        )
    }

    val uiState: StateFlow<CalendarUiState> = combine(month, selectedDate, filter, today, monthRows) { shown, selected, chosen, todayValue, (memos, journals) ->
        val zone = timeProvider.currentZoneId()
        val days = TimelineBuckets.daySummaries(memos, journals, zone)
        CalendarUiState(
            month = shown,
            today = todayValue,
            selectedDate = selected,
            filter = chosen,
            grid = DiaryCalendarGridFactory.create(
                displayedMonth = shown,
                today = todayValue,
                firstDayOfWeek = DayOfWeek.MONDAY,
                diaryDates = days.keys,
                selectedDate = selected,
            ),
            days = days,
            items = TimelineBuckets.itemsFor(selected, memos, journals, zone, chosen),
            monthItems = if (chosen == TimelineFilter.ALL) TimelineBuckets.monthItems(shown, memos, journals, zone, chosen) else emptyList(),
        )
    }.combine(diaryExtras) { state, (delivered, pastToday) ->
        state.copy(deliveredCount = delivered, pastToday = pastToday)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CalendarUiState(
            month = YearMonth.from(today.value),
            today = today.value,
            selectedDate = today.value,
            filter = TimelineFilter.ALL,
            grid = DiaryCalendarGridFactory.create(YearMonth.from(today.value), today.value, DayOfWeek.MONDAY, emptySet(), today.value),
            days = emptyMap(),
            items = emptyList(),
        ),
    )

    /**
     * On resume, as the diary page did: due future comments become deliveries, and when the
     * date has changed while the tab was away, a selection that was following today moves on.
     */
    fun refresh() {
        viewModelScope.launch {
            futureRepository.markDueDelivered()
            val now = timeProvider.currentLocalDate()
            val previous = today.value
            if (now != previous) {
                val followedToday = selectedDate.value == previous && month.value == YearMonth.from(previous)
                today.value = now
                if (followedToday) {
                    month.value = YearMonth.from(now)
                    selectedDate.value = now
                }
            }
        }
    }

    /** A new entry on [epochDay] — the explicit 日記を書く; navigation never creates one. */
    fun createEntry(epochDay: Long, onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(diaryRepository.createEntry(epochDay).id) }
    }

    fun showPreviousMonth() {
        month.value = month.value.minusMonths(1)
        selectedDate.value = month.value.atDay(1)
    }

    fun showNextMonth() {
        val now = today.value
        if (month.value >= YearMonth.from(now)) return
        month.value = month.value.plusMonths(1)
        selectedDate.value = if (month.value == YearMonth.from(now)) now else month.value.atDay(1)
    }

    fun showToday() {
        month.value = YearMonth.from(today.value)
        selectedDate.value = today.value
    }

    fun selectDate(date: LocalDate) {
        if (date.isAfter(today.value)) return
        month.value = YearMonth.from(date)
        selectedDate.value = date
    }

    fun setFilter(value: TimelineFilter) {
        filter.value = value
    }

    companion object {
        fun factory(
            memoRepository: MemoRepository,
            diaryRepository: DiaryRepository,
            futureRepository: FutureDiaryCommentRepository,
            timeProvider: TimeProvider,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CalendarViewModel(memoRepository, diaryRepository, futureRepository, timeProvider) as T
        }
    }
}

/**
 * The カレンダー tab (docs/CALENDAR_TAB_MIGRATION.md): Calendar v1's month, chips and rows, plus
 * the three jobs that came over from the 日記 tab — the delivery banner, the explicit 日記を書く
 * for the selected day, and 過去の今日 — and a top bar that reaches 日記一覧 and 設定.
 */
@Composable
fun CalendarRoute(
    onOpen: (DocumentRef) -> Unit,
    onOpenJournalList: () -> Unit,
    onOpenSettings: () -> Unit,
    onReceiveFutureComment: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val viewModel: CalendarViewModel = viewModel(
        factory = CalendarViewModel.factory(
            application.memoRepository,
            application.diaryRepository,
            application.futureDiaryCommentRepository,
            application.timeProvider,
        ),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    CalendarScreen(
        state = state,
        onPreviousMonth = viewModel::showPreviousMonth,
        onNextMonth = viewModel::showNextMonth,
        onToday = viewModel::showToday,
        onSelectDate = viewModel::selectDate,
        onFilter = viewModel::setFilter,
        onOpen = { item -> onOpen(item.sourceRef) },
        onCreateJournal = { viewModel.createEntry(state.selectedDate.toEpochDay()) { onOpen(DocumentRef(DocumentKind.JOURNAL, it)) } },
        onOpenJournal = { onOpen(DocumentRef(DocumentKind.JOURNAL, it)) },
        onOpenJournalList = onOpenJournalList,
        onOpenSettings = onOpenSettings,
        onReceiveFutureComment = onReceiveFutureComment,
    )
}

@Composable
private fun CalendarScreen(
    state: CalendarUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onFilter: (TimelineFilter) -> Unit,
    onOpen: (TimelineItem) -> Unit,
    onCreateJournal: () -> Unit,
    onOpenJournal: (Long) -> Unit,
    onOpenJournalList: () -> Unit,
    onOpenSettings: () -> Unit,
    onReceiveFutureComment: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Where the day grid ends, measured while the page is at its top. The grid is the one heavy
    // item on the page (~100 ms to build on a debug build): a reselect lands with the grid's last
    // row at the top of the view, so the grid is built in the same frame as the instant jump —
    // before anything moves — and the glide after it passes only rows already built
    // (docs/BOTTOM_NAV_RESELECT.md).
    var gridBottom by remember { mutableIntStateOf(0) }
    LaunchedEffect(listState) {
        snapshotFlow {
            if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == MONTH_GRID_KEY }?.let { it.offset + it.size }
            } else {
                null
            }
        }.collect { bottom -> if (bottom != null && bottom > 0) gridBottom = bottom }
    }
    // カレンダー tapped again in the bottom bar (docs/BOTTOM_NAV_RESELECT.md): back to the top of
    // the page, where the month is — the month, the day, the filter and the rows stay.
    OnTabReselect(TopLevelTab.CALENDAR) { listState.scrollToTopOnReselect(landing = gridBottom - 1) }
    Scaffold(
        topBar = {
            ProductCompactTopBar(
                modifier = Modifier.testTag("calendar_compact_top_bar"),
                centerContent = {
                    Text(
                        "カレンダー",
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                action = {
                    Row {
                        IconButton(
                            onClick = onOpenJournalList,
                            modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("open_journal_list"),
                        ) {
                            Icon(Icons.Outlined.Book, contentDescription = "日記一覧")
                        }
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("calendar_open_settings"),
                        ) {
                            Icon(Icons.Outlined.Settings, contentDescription = "設定")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding)
                .padding(horizontal = ProductSize.screenHorizontalPadding)
                .testTag("timeline_list"),
            verticalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
        ) {
            // A delivery is the first thing on the tab, above the month — never on another tab.
            if (state.deliveredCount > 0) {
                item { FutureDeliveryBanner(state.deliveredCount, onReceiveFutureComment) }
            }
            item {
                MonthHeader(
                    month = state.month,
                    canGoForward = state.month < YearMonth.from(state.today),
                    onPreviousMonth = onPreviousMonth,
                    onNextMonth = onNextMonth,
                    onToday = onToday,
                )
            }
            item(key = MONTH_GRID_KEY) { MonthGrid(grid = state.grid, days = state.days, onSelectDate = onSelectDate) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
                ) {
                    TimelineFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = filter == state.filter,
                            onClick = { onFilter(filter) },
                            label = { Text(filter.label) },
                            modifier = Modifier.testTag("timeline_filter_${filter.name}"),
                        )
                    }
                }
            }
            item {
                // The selected day and, beside it, the one thing that makes a journal: a tap.
                // Today and any past day may get one, several a day; a future day cannot be
                // selected at all.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.md, bottom = ProductSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.filter == TimelineFilter.ALL) formatTimelineMonth(state.month) else formatTimelineDate(state.selectedDate),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).testTag("timeline_scope_label"),
                    )
                    Button(
                        onClick = onCreateJournal,
                        modifier = Modifier.semantics { contentDescription = "${formatTimelineDate(state.selectedDate)}の日記を書く" }.testTag("timeline_create_journal"),
                    ) { Text("日記を書く") }
                }
            }
            // 「すべて」 is the month (2026-09-23): the days of the displayed month, newest first, each
            // with its own rows. 作成 / 更新 stay the selected day's, so choosing a date still shows a day.
            if (state.filter == TimelineFilter.ALL) {
                if (state.monthItems.isEmpty()) {
                    item {
                        ProductEmptyState(
                            title = "この月の記録はありません",
                            description = "作った・書いたメモ、アウトライン、日記がここに並びます。",
                            modifier = Modifier.fillMaxWidth().testTag("timeline_month_empty"),
                        )
                    }
                }
                state.monthItems.forEach { day ->
                    item(key = "month_day_${day.date}") {
                        Text(
                            formatTimelineDate(day.date),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                                .padding(top = ProductSpacing.md, bottom = ProductSpacing.xs)
                                .testTag("timeline_month_day_${day.date}"),
                        )
                    }
                    items(day.items, key = { "month-${day.date}-${it.kind.name}-${it.id}" }) { item ->
                        // one row per document, so a row of the month is the same row the day view draws
                        TimelineRow(item = item, onClick = { onOpen(item) })
                    }
                }
            } else {
                if (state.items.isEmpty()) {
                    item {
                        ProductEmptyState(
                            title = "この日の記録はありません",
                            description = "作った・書いたメモ、アウトライン、日記がここに並びます。",
                            modifier = Modifier.fillMaxWidth().testTag("timeline_empty"),
                        )
                    }
                }
                items(state.items, key = { "${it.kind.name}-${it.id}" }) { item ->
                    TimelineRow(item = item, onClick = { onOpen(item) })
                }
            }
            if (state.pastToday.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(ProductSpacing.lg))
                    SectionHeader(
                        title = "過去の今日",
                        supportingText = "同じ日に書いた日記",
                        divider = true,
                    )
                }
                items(state.pastToday, key = { "past_today_${it.value.id}" }) { item ->
                    PastTodayCard(item = item, onOpen = onOpenJournal)
                }
            }
            item { Spacer(Modifier.height(ProductSpacing.xl)) }
        }
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    canGoForward: Boolean,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPreviousMonth,
            modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("timeline_previous_month"),
        ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "前の月") }
        Text(
            month.format(DateTimeFormatter.ofPattern("yyyy年M月", Locale.JAPAN)),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).testTag("timeline_month_title"),
        )
        IconButton(
            onClick = onNextMonth,
            enabled = canGoForward,
            modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("timeline_next_month"),
        ) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "次の月") }
        TextButton(onClick = onToday, modifier = Modifier.testTag("timeline_today")) { Text("今日") }
    }
}

@Composable
private fun MonthGrid(
    grid: DiaryCalendarMonth,
    days: Map<LocalDate, DaySummary>,
    onSelectDate: (LocalDate) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            grid.weekDays.forEach { weekday ->
                Text(
                    weekday.getDisplayName(TextStyle.SHORT, Locale.JAPAN),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        grid.days.chunked(DiaryCalendarMonth.DAYS_PER_WEEK).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    val summary = days[day.date]
                    val description = buildString {
                        append(day.date.monthValue).append("月").append(day.date.dayOfMonth).append("日")
                        if (day.isToday) append(" 今日")
                        if (day.isFuture) append(" 未来")
                        if (summary != null) append(" ").append(summary.count).append("件 ").append(summary.kinds.joinToString("・") { it.label })
                        if (day.isSelected) append(" 選択中")
                    }
                    Box(
                        modifier = Modifier.weight(1f)
                            .heightIn(min = 44.dp)
                            .padding(2.dp)
                            .clip(MaterialTheme.shapes.small)
                            .then(
                                if (day.isSelected) Modifier.padding(0.dp) else Modifier,
                            )
                            .clickable(enabled = !day.isFuture) { onSelectDate(day.date) }
                            .semantics {
                                contentDescription = description
                                selected = day.isSelected
                            }
                            .testTag("timeline_day_${day.date.toEpochDay()}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                day.date.dayOfMonth.toString(),
                                style = if (day.isSelected) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                                color = when {
                                    day.isFuture -> MaterialTheme.colorScheme.outline
                                    !day.belongsToDisplayedMonth -> MaterialTheme.colorScheme.onSurfaceVariant
                                    day.isSelected || day.isToday -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                summary?.kinds?.sortedBy { it.ordinal }?.forEach { kind ->
                                    // A plain circle: one node, where a Surface was several (the grid is 42 cells).
                                    Box(Modifier.size(5.dp).background(kindColor(kind), CircleShape))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun kindColor(kind: DocumentKind) = when (kind) {
    DocumentKind.MEMO -> MaterialTheme.colorScheme.primary
    DocumentKind.OUTLINE -> MaterialTheme.colorScheme.secondary
    DocumentKind.JOURNAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun TimelineRow(item: TimelineItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().testTag("timeline_item_${item.kind.name.lowercase()}_${item.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(ProductSpacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                item.timeLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(52.dp),
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.kind.label, style = MaterialTheme.typography.labelMedium, color = kindColor(item.kind))
                    Spacer(Modifier.width(ProductSpacing.sm))
                    Text(
                        item.activity.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = ProductSpacing.xs),
                )
            }
        }
    }
}

private fun formatTimelineMonth(month: YearMonth): String =
    month.format(DateTimeFormatter.ofPattern("yyyy年M月の記録", Locale.JAPAN))

private fun formatTimelineDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPAN))

/** The day grid's key in the page's list (its bottom edge is where a reselect lands). */
private const val MONTH_GRID_KEY = "month_grid"
