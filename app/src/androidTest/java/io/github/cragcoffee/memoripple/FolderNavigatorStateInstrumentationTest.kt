package io.github.cragcoffee.memoripple

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.FolderEntity
import io.github.cragcoffee.memoripple.ui.memos.MemoListViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The process-death case the UI test cannot stage: a MemoListViewModel built from a
 * SavedStateHandle that holds the open folder and the expanded folders comes back standing in
 * that folder, with those folders open, and saves what changes. No activity involved.
 */
@RunWith(AndroidJUnit4::class)
class FolderNavigatorStateInstrumentationTest {
    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MemoRippleApplication

    @Before
    fun startEmpty() {
        runBlocking { application.database.clearAllTables() }
    }

    private fun viewModel(handle: SavedStateHandle) = MemoListViewModel(
        application.memoRepository,
        application.tagRepository,
        application.attachmentRepository,
        application.settingsRepository,
        application.folderRepository,
        savedStateHandle = handle,
    )

    @Test
    fun theOpenFolderAndTheExpandedFoldersComeBackFromTheSavedStateAndAreSavedWhenTheyChange() {
        val (dev, memoRipple, ai) = runBlocking {
            val dao = application.database.folderDao()
            val dev = dao.insert(FolderEntity(name = "開発", parentFolderId = null, createdAt = 1, updatedAt = 1))
            val memoRipple = dao.insert(FolderEntity(name = "MemoRipple", parentFolderId = dev, createdAt = 1, updatedAt = 1))
            val ai = dao.insert(FolderEntity(name = "AI設計", parentFolderId = memoRipple, createdAt = 1, updatedAt = 1))
            Triple(dev, memoRipple, ai)
        }
        val handle = SavedStateHandle(mapOf("currentFolderId" to ai, "expandedFolderIds" to longArrayOf(dev)))
        val viewModel = viewModel(handle)

        val state = runBlocking {
            withTimeout(5_000) { viewModel.uiState.first { it.folders.size == 3 && it.currentFolderId == ai && it.navigatorRows.any { r -> r.folderId == ai } } }
        }
        // Standing in AI設計, with the path to it open even though only 開発 was saved as expanded.
        assertEquals(ai, state.currentFolderId)
        assertEquals(listOf("開発", "MemoRipple", "AI設計"), state.folderPath.map { it.name })
        assertEquals(listOf(dev, memoRipple, ai), state.navigatorRows.map { it.folderId })
        assertTrue(state.navigatorRows.single { it.folderId == ai }.selected)

        // Changes go back into the handle, so the next process can pick them up.
        viewModel.toggleFolderExpanded(memoRipple)
        viewModel.goToFolder(dev)
        runBlocking { withTimeout(5_000) { viewModel.uiState.first { it.currentFolderId == dev && it.navigatorRows.none { r -> r.folderId == ai } } } }
        assertEquals(dev, handle.get<Long>("currentFolderId"))
        assertEquals(setOf(dev), handle.get<LongArray>("expandedFolderIds")?.toSet())
    }
}
