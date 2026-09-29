package io.github.cragcoffee.memoripple.ui.diary

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.DiaryRepository
import io.github.cragcoffee.memoripple.ui.components.ProductEmptyState
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.ui.components.ProductStatusChip
import io.github.cragcoffee.memoripple.ui.components.ProductTopBar
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One day's journal entries (HANDOFF §16.18). Where the old date route lands when the day holds
 * none or several; each row opens its entry by id, and 「この日に書く」 is the only way a new one
 * comes to be — the list never creates one just by being opened.
 */
class JournalDayViewModel(
    private val repository: DiaryRepository,
    val epochDay: Long,
) : ViewModel() {
    val entries: StateFlow<List<DiaryEntryEntity>> = repository.observeForDate(epochDay)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun canWrite(): Boolean = epochDay <= repository.currentDate().toEpochDay()

    fun createEntry(onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(repository.createEntry(epochDay).id) }
    }

    companion object {
        fun factory(repository: DiaryRepository, epochDay: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    JournalDayViewModel(repository, epochDay) as T
            }
    }
}

@Composable
fun JournalDayRoute(
    epochDay: Long,
    onOpenEntry: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val viewModel: JournalDayViewModel = viewModel(
        key = "journal-day-$epochDay",
        factory = JournalDayViewModel.factory(application.diaryRepository, epochDay),
    )
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    JournalDayScreen(
        date = LocalDate.ofEpochDay(epochDay),
        entries = entries,
        canWrite = viewModel.canWrite(),
        onOpenEntry = onOpenEntry,
        onCreate = { viewModel.createEntry(onOpenEntry) },
        onBack = onBack,
    )
}

@Composable
private fun JournalDayScreen(
    date: LocalDate,
    entries: List<DiaryEntryEntity>,
    canWrite: Boolean,
    onOpenEntry: (Long) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = { ProductTopBar(title = formatDiaryDate(date), onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding)
                .padding(horizontal = ProductSize.screenHorizontalPadding)
                .testTag("journal_day_list"),
            verticalArrangement = Arrangement.spacedBy(ProductSpacing.md),
        ) {
            item { Spacer(Modifier.height(ProductSpacing.sm)) }
            if (entries.isEmpty()) {
                item {
                    ProductEmptyState(
                        title = "この日の日記はまだありません",
                        description = if (canWrite) "「この日に書く」で最初の一つを始められます。" else "未来の日はまだ書けません。",
                        modifier = Modifier.fillMaxWidth().testTag("journal_day_empty_state"),
                    )
                }
            }
            items(entries, key = DiaryEntryEntity::id) { entry ->
                Surface(
                    onClick = { onOpenEntry(entry.id) },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth().testTag("journal_entry_${entry.id}"),
                ) {
                    Column(Modifier.padding(ProductSize.screenHorizontalPadding)) {
                        Text(formatDiaryTime(entry.createdAt), style = MaterialTheme.typography.titleMedium)
                        Text(
                            entry.body.ifBlank { "（本文なし）" },
                            modifier = Modifier.padding(top = ProductSpacing.md),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // only a state that still means something (2026-09-24): see DiaryState.displayName
                        if (entry.state == DiaryState.LOCKED) {
                            ProductStatusChip(
                                entry.state.displayName(),
                                modifier = Modifier.padding(top = ProductSpacing.md),
                            )
                        }
                    }
                }
            }
            if (canWrite) {
                item {
                    Button(
                        onClick = onCreate,
                        modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.lg)
                            .testTag("journal_day_create"),
                    ) { Text("この日に書く") }
                }
            }
            item { Spacer(Modifier.height(ProductSpacing.xl)) }
        }
    }
}
