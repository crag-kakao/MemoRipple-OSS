package io.github.cragcoffee.memoripple.ui.diary

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryCalendarDay
import io.github.cragcoffee.memoripple.domain.diary.DiaryCalendarGridFactory
import io.github.cragcoffee.memoripple.domain.diary.DiaryCalendarMonth
import io.github.cragcoffee.memoripple.domain.diary.DiaryCalendarNavigation
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.diary.PastTodayItem
import io.github.cragcoffee.memoripple.ui.components.ProductEmptyState
import io.github.cragcoffee.memoripple.ui.components.ProductCompactTopBar
import io.github.cragcoffee.memoripple.ui.components.ProductInfoBanner
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductStatusChip
import io.github.cragcoffee.memoripple.ui.components.SectionHeader
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

@Composable
fun DiaryRoute(
    onOpenEntry: (Long) -> Unit,
    onOpenDay: (Long) -> Unit,
    onBack: () -> Unit,
    onReceiveFutureComment: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val viewModel: DiaryViewModel = viewModel(
        factory = DiaryViewModel.factory(
            application.diaryRepository,
            application.futureDiaryCommentRepository,
            application.settingsRepository,
        ),
    )
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val calendarUiState by viewModel.calendarUiState.collectAsStateWithLifecycle()
    val pastTodayEntries by viewModel.pastTodayEntries.collectAsStateWithLifecycle()
    val deliveredCount by viewModel.deliveredCount.collectAsStateWithLifecycle()
    val calendarCompact by viewModel.calendarCompact.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DiaryScreen(
        entries = entries,
        calendarUiState = calendarUiState,
        pastTodayEntries = pastTodayEntries,
        deliveredCount = deliveredCount,
        onReceiveFutureComment = onReceiveFutureComment,
        onOpenEntry = onOpenEntry,
        onOpenDay = onOpenDay,
        onCreateEntry = { epochDay -> viewModel.createEntry(epochDay, onOpenEntry) },
        onOpenSettings = onOpenSettings,
        onBack = onBack,
        onPreviousMonth = viewModel::showPreviousMonth,
        onNextMonth = viewModel::showNextMonth,
        onToday = viewModel::showToday,
        onSelectDate = viewModel::selectDate,
        calendarCompact = calendarCompact,
        onToggleCompact = { viewModel.setCalendarCompact(!calendarCompact) },
        onJumpToDate = viewModel::jumpToDate,
    )
}

/**
 * The diary as one page.
 *
 * There used to be a 一覧/カレンダー pair of tabs, which meant the first thing the screen asked was
 * a navigation question. Now it answers instead: the month is at the top, today is directly under
 * it already selected, and reading further down is reading further back — the same days on the
 * same page, not a second place. Selecting a date changes the card, not the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiaryScreen(
    entries: List<DiaryEntryEntity>,
    calendarUiState: DiaryCalendarUiState,
    pastTodayEntries: List<PastTodayItem<DiaryEntryEntity>>,
    deliveredCount: Int,
    onReceiveFutureComment: () -> Unit,
    onOpenEntry: (Long) -> Unit,
    onOpenDay: (Long) -> Unit,
    onCreateEntry: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    calendarCompact: Boolean,
    onToggleCompact: () -> Unit,
    onJumpToDate: (LocalDate) -> Unit,
) {
    val currentEpochDay = calendarUiState.today.toEpochDay()
    val pastEntries = entries.filter { it.diaryDateEpochDay != currentEpochDay }

    Scaffold(
        topBar = {
            ProductCompactTopBar(
                modifier = Modifier.testTag("diary_compact_top_bar"),
                navigationIcon = {
                    // 日記一覧 now: a secondary screen under the カレンダー tab, so it goes back,
                    // not sideways (docs/CALENDAR_TAB_MIGRATION.md).
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(ProductSize.minimumTouchTarget),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
                centerContent = {
                    Text(
                        "日記一覧",
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                action = {
                    Row {
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.size(ProductSize.minimumTouchTarget),
                        ) {
                            Icon(Icons.Outlined.Settings, contentDescription = "設定")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding)
                .padding(horizontal = ProductSize.screenHorizontalPadding)
                .testTag("diary_list"),
            verticalArrangement = Arrangement.spacedBy(ProductSpacing.md),
        ) {
            item { Spacer(Modifier.height(ProductSpacing.sm)) }
            if (deliveredCount > 0) {
                item { FutureDeliveryBanner(deliveredCount, onReceiveFutureComment) }
            }
            item {
                DiaryCalendar(
                    state = calendarUiState,
                    compact = calendarCompact,
                    onToggleCompact = onToggleCompact,
                    onJumpToDate = onJumpToDate,
                    onPreviousMonth = onPreviousMonth,
                    onNextMonth = onNextMonth,
                    onToday = onToday,
                    onSelectDate = onSelectDate,
                )
            }
            item {
                DiarySelectedDateSummary(
                    selectedDate = calendarUiState.selectedDate,
                    today = calendarUiState.today,
                    entries = calendarUiState.selectedDate?.let { selected ->
                        calendarUiState.monthEntries
                            .filter { it.diaryDateEpochDay == selected.toEpochDay() }
                            .sortedWith(compareBy({ it.createdAt }, { it.id }))
                    }.orEmpty(),
                    onOpenEntry = onOpenEntry,
                    onOpenDay = onOpenDay,
                    onCreateEntry = onCreateEntry,
                )
            }
            if (pastTodayEntries.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(ProductSpacing.lg))
                    SectionHeader(
                        title = "過去の今日",
                        supportingText = "同じ日に書いた日記",
                        divider = true,
                    )
                }
                items(pastTodayEntries, key = { "past_today_${it.value.id}" }) { item ->
                    PastTodayCard(item = item, onOpen = onOpenEntry)
                }
            }
            item {
                Spacer(Modifier.height(ProductSpacing.lg))
                SectionHeader("過去の日記", divider = true)
            }
            if (pastEntries.isEmpty()) {
                item {
                    ProductEmptyState(
                        title = "過去の日記はまだありません",
                        description = "書いた日記はここから読み返せます。",
                        modifier = Modifier.fillMaxWidth().testTag("diary_past_empty_state"),
                    )
                }
            } else {
                items(pastEntries, key = DiaryEntryEntity::id) { entry ->
                    DiaryCard(entry) { onOpenEntry(entry.id) }
                }
            }
            item { Spacer(Modifier.height(ProductSpacing.xl)) }
        }
    }
}

/**
 * One week, unless asked for the month.
 *
 * The calendar's daily job is to say where today stands and take one tap to it, and a single
 * week answers that in one line. The whole month is still here — it unfolds from the title,
 * which carries a chevron so the hinge can be seen — for the day the reader goes looking
 * further back. In the week view the arrows step by week, and stepping moves the selection,
 * so the card below always describes the day the window is standing on.
 */
@Composable
private fun DiaryCalendar(
    state: DiaryCalendarUiState,
    compact: Boolean,
    onToggleCompact: () -> Unit,
    onJumpToDate: (LocalDate) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
) {
    val locale = Locale.getDefault()
    val firstDayOfWeek = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val diaryDates = remember(state.monthEntries) {
        state.monthEntries.mapTo(mutableSetOf()) {
            LocalDate.ofEpochDay(it.diaryDateEpochDay)
        }
    }
    val calendar = remember(
        state.displayedMonth,
        state.today,
        firstDayOfWeek,
        diaryDates,
        state.selectedDate,
    ) {
        DiaryCalendarGridFactory.create(
            displayedMonth = state.displayedMonth,
            today = state.today,
            firstDayOfWeek = firstDayOfWeek,
            diaryDates = diaryDates,
            selectedDate = state.selectedDate,
        )
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = CALENDAR_MAX_WIDTH).fillMaxWidth()
                .testTag("diary_calendar"),
        ) {
            val anchor = state.selectedDate ?: state.today
            DiaryCalendarHeader(
                displayedMonth = state.displayedMonth,
                today = state.today,
                selectedDate = state.selectedDate,
                locale = locale,
                compact = compact,
                onToggleCompact = onToggleCompact,
                weekBackEnabled = true,
                weekForwardEnabled = weekStart(anchor, firstDayOfWeek)
                    .isBefore(weekStart(state.today, firstDayOfWeek)),
                onPreviousWeek = { onJumpToDate(anchor.minusDays(7)) },
                onNextWeek = {
                    val step = anchor.plusDays(7)
                    onJumpToDate(if (step.isAfter(state.today)) state.today else step)
                },
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
                onToday = onToday,
            )
            DiaryCalendarWeekHeader(calendar.weekDays, locale)
            val weeks = calendar.days.chunked(DiaryCalendarMonth.DAYS_PER_WEEK)
            if (compact) {
                // The one row that holds the day the window stands on. Days of the
                // neighbouring month stay visible here: a week is a week.
                val week = weeks.firstOrNull { row -> row.any { it.date == anchor } }
                    ?: weeks.first { row -> row.any { it.belongsToDisplayedMonth } }
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        DiaryCalendarDayCell(
                            day = day,
                            locale = locale,
                            showOutsideMonth = true,
                            onSelect = onSelectDate,
                        )
                    }
                }
            } else {
                // Only the weeks this month actually has. The grid is padded to six for layout
                // arithmetic, but an empty row of another month's days is 48dp of nothing.
                weeks.filter { week -> week.any { it.belongsToDisplayedMonth } }
                    .forEach { week ->
                        Row(Modifier.fillMaxWidth()) {
                            week.forEach { day ->
                                DiaryCalendarDayCell(
                                    day = day,
                                    locale = locale,
                                    onSelect = onSelectDate,
                                )
                            }
                        }
                    }
            }
        }
    }
}

private fun weekStart(date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate {
    val offset = (date.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    return date.minusDays(offset.toLong())
}

@Composable
private fun DiaryCalendarHeader(
    displayedMonth: YearMonth,
    today: LocalDate,
    selectedDate: LocalDate?,
    locale: Locale,
    compact: Boolean,
    onToggleCompact: () -> Unit,
    weekBackEnabled: Boolean,
    weekForwardEnabled: Boolean,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
) {
    val currentMonth = YearMonth.from(today)
    // The month reads first, from the left rail; everything that moves it stands together on
    // the right. The name itself is the hinge between one week and the whole month, and the
    // chevron beside it says so before it is pressed.
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = ProductSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = ProductSize.minimumTouchTarget)
                .clickable(onClick = onToggleCompact)
                .semantics(mergeDescendants = true) {
                    contentDescription = if (compact) "月全体を表示" else "一週間だけ表示"
                    role = Role.Button
                }
                .testTag("calendar_mode_toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                displayedMonth.format(DateTimeFormatter.ofPattern("yyyy年M月", locale)),
                style = MaterialTheme.typography.titleMedium,
            )
            Icon(
                if (compact) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                contentDescription = null,
                modifier = Modifier.padding(start = ProductSpacing.xs).size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onToday,
            enabled = displayedMonth != currentMonth || selectedDate != today,
            modifier = Modifier.testTag("calendar_today"),
        ) { Text("今日") }
        if (compact) {
            IconButton(
                onClick = onPreviousWeek,
                enabled = weekBackEnabled,
                modifier = Modifier.testTag("calendar_previous_week"),
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "前の週") }
            IconButton(
                onClick = onNextWeek,
                enabled = weekForwardEnabled,
                modifier = Modifier.testTag("calendar_next_week"),
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "次の週") }
        } else {
            IconButton(
                onClick = onPreviousMonth,
                modifier = Modifier.testTag("calendar_previous_month"),
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "前月") }
            IconButton(
                onClick = onNextMonth,
                enabled = DiaryCalendarNavigation.canGoToNextMonth(displayedMonth, currentMonth),
                modifier = Modifier.testTag("calendar_next_month"),
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "翌月") }
        }
    }
}

@Composable
private fun DiaryCalendarWeekHeader(weekDays: List<DayOfWeek>, locale: Locale) {
    Row(Modifier.fillMaxWidth()) {
        weekDays.forEach { dayOfWeek ->
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    dayOfWeek.getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RowScope.DiaryCalendarDayCell(
    day: DiaryCalendarDay,
    locale: Locale,
    showOutsideMonth: Boolean = false,
    onSelect: (LocalDate) -> Unit,
) {
    if (!day.belongsToDisplayedMonth && !showOutsideMonth) {
        Spacer(Modifier.weight(1f).height(CALENDAR_CELL_HEIGHT))
        return
    }
    // A number, not a coin: the 40dp circle drew every day as a button and made seven of them
    // a row of hardware. The number stands plain; today is the one filled pill, a chosen day
    // fills softer, and a day with a diary underlines itself. The 48dp touch height stays.
    Box(
        modifier = Modifier.weight(1f).heightIn(min = CALENDAR_CELL_HEIGHT)
            .clickable(enabled = !day.isFuture) { onSelect(day.date) }
            .testTag("calendar_day_${day.date.toEpochDay()}")
            .semantics(mergeDescendants = true) {
                contentDescription = calendarDayContentDescription(day, locale)
                role = Role.Button
                if (day.isSelected) selected = true
                if (day.isFuture) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = when {
                    day.isToday -> MaterialTheme.colorScheme.primary
                    day.isSelected -> MaterialTheme.colorScheme.primaryContainer
                    else -> Color.Transparent
                },
                contentColor = when {
                    day.isToday -> MaterialTheme.colorScheme.onPrimary
                    day.isFuture -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    day.isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
            ) {
                Text(
                    day.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(width = 18.dp, height = 3.dp)
                    .background(
                        color = if (day.hasDiary) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

internal fun calendarDayContentDescription(day: DiaryCalendarDay, locale: Locale): String =
    buildList {
        add(
            day.date.format(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale),
            ),
        )
        if (day.isToday) add("今日")
        if (day.hasDiary) add("日記あり")
        if (day.isSelected) add("選択中")
        if (day.isFuture) add("未来、選択できません")
    }.joinToString("、")

@Composable
private fun DiarySelectedDateSummary(
    selectedDate: LocalDate?,
    today: LocalDate,
    entries: List<DiaryEntryEntity>,
    onOpenEntry: (Long) -> Unit,
    onOpenDay: (Long) -> Unit,
    onCreateEntry: (Long) -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Card(
            modifier = Modifier.widthIn(max = CALENDAR_MAX_WIDTH).fillMaxWidth()
                .testTag("calendar_selected_summary"),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(Modifier.fillMaxWidth().padding(ProductSpacing.lg)) {
                if (selectedDate == null) {
                    Text(
                        "日付を選ぶと、その日の記録を確認できます。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                Text(formatDiaryDate(selectedDate), style = MaterialTheme.typography.titleMedium)
                val epochDay = selectedDate.toEpochDay()
                when {
                    // A day may hold several entries: one is opened as itself, more go to the day's
                    // list. Writing is always an explicit tap, never a side effect of looking.
                    entries.size == 1 -> {
                        val entry = entries.single()
                        // only a state that still means something (2026-09-24): 編集中 said nothing once the
                        // fixed one-a-day lifecycle was retired; ロック済み still says the entry cannot be written
                        if (entry.state == DiaryState.LOCKED) {
                            ProductStatusChip(
                                entry.state.displayName(),
                                modifier = Modifier.padding(top = ProductSpacing.sm),
                            )
                        }
                        Text(
                            entry.body,
                            modifier = Modifier.padding(top = ProductSpacing.md),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Button(
                            onClick = { onOpenEntry(entry.id) },
                            modifier = Modifier.align(Alignment.End)
                                .padding(top = ProductSpacing.lg)
                                .testTag("calendar_open_diary"),
                        ) { Text("日記を開く") }
                    }

                    entries.size > 1 -> {
                        Text(
                            "${entries.size}件の日記",
                            modifier = Modifier.padding(top = ProductSpacing.sm),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            entries.first().body,
                            modifier = Modifier.padding(top = ProductSpacing.md),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Button(
                            onClick = { onOpenDay(epochDay) },
                            modifier = Modifier.align(Alignment.End)
                                .padding(top = ProductSpacing.lg)
                                .testTag("calendar_open_day"),
                        ) { Text("この日の一覧") }
                    }

                    selectedDate == today -> Text(
                        "今日の日記はまだありません",
                        modifier = Modifier.padding(top = ProductSpacing.md),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Text(
                        "この日の日記はありません",
                        modifier = Modifier.padding(top = ProductSpacing.md),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selectedDate == today) {
                    Button(
                        onClick = { onCreateEntry(epochDay) },
                        modifier = Modifier.align(Alignment.End)
                            .padding(top = ProductSpacing.sm)
                            .testTag("calendar_create_today"),
                    ) { Text(if (entries.isEmpty()) "今日の日記を書く" else "今日の日記をもう一つ書く") }
                }
            }
        }
    }
}

@Composable
internal fun PastTodayCard(
    item: PastTodayItem<DiaryEntryEntity>,
    onOpen: (Long) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().testTag("past_today_${item.date.toEpochDay()}"),
    ) {
        Column(Modifier.fillMaxWidth().padding(ProductSpacing.lg)) {
            Text("${item.yearsAgo}年前", style = MaterialTheme.typography.labelLarge)
            Text(
                formatDiaryDate(item.date),
                modifier = Modifier.padding(top = ProductSpacing.xs),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                item.value.body,
                modifier = Modifier.padding(top = ProductSpacing.md),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                onClick = { onOpen(item.value.id) },
                modifier = Modifier.align(Alignment.End)
                    .testTag("past_today_open_${item.date.toEpochDay()}"),
            ) { Text("日記を読む") }
        }
    }
}

@Composable
internal fun FutureDeliveryBanner(count: Int, onReceive: () -> Unit) {
    ProductInfoBanner(
        title = "未来からコメントが届いています",
        supportingText = "${count}件のコメントを受け取れます。",
        actionLabel = "受け取る",
        actionTestTag = "receive_future_comment",
        onAction = onReceive,
        modifier = Modifier.testTag("future_delivery_banner"),
    )
}

@Composable
private fun DiaryCard(entry: DiaryEntryEntity, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().testTag("diary_card_${entry.id}"),
    ) {
        Column(Modifier.padding(ProductSize.screenHorizontalPadding)) {
            Text(
                formatDiaryDate(LocalDate.ofEpochDay(entry.diaryDateEpochDay)) + " " + formatDiaryTime(entry.createdAt),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                entry.body,
                modifier = Modifier.padding(top = ProductSpacing.md),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.state == DiaryState.LOCKED) {
                ProductStatusChip(
                    entry.state.displayName(),
                    modifier = Modifier.padding(top = ProductSpacing.md),
                )
            }
        }
    }
}

/**
 * The retired lifecycle's words. Only ロック済み is drawn anywhere now (2026-09-24): a journal is no
 * longer written once a day and confirmed, so 編集中 / 確定済み / 修正中 describe nothing the reader can
 * act on. The states themselves, their storage, the backup and the export wording are unchanged.
 */
internal fun DiaryState.displayName(): String = when (this) {
    DiaryState.DRAFT -> "編集中"
    DiaryState.FINALIZED -> "確定済み"
    DiaryState.CORRECTING -> "修正中"
    DiaryState.LOCKED -> "ロック済み"
}

internal fun formatDiaryDate(date: LocalDate): String = date.format(
    DateTimeFormatter.ofPattern("yyyy年M月d日（E）", Locale.JAPAN),
)

/** The minute an entry was begun, in the device zone — how entries of one day tell apart. */
internal fun formatDiaryTime(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))

private val CALENDAR_CELL_HEIGHT = 48.dp
private val CALENDAR_MAX_WIDTH = 560.dp
