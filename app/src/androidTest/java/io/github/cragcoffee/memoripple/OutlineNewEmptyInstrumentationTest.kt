package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.AttachmentKind
import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.data.MemoPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.TagEntity
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A new outline left with nothing in it (docs/OUTLINE_PHOTO_ROWS.md §8): ＋ → the outliner → Back
 * with nothing written is as if it had never been made — like a memo never written, which is
 * never saved; anything at all in it (a word, a photo, a comment, a tag, a title) and it stays;
 * an outline that already existed is never removed by being opened and left; and an untitled
 * outline reads 「無題のアウトライナー」.
 */
class OutlineNewEmptyInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    @Before
    fun startEmpty() {
        runBlocking {
            app.database.clearAllTables()
            app.settingsRepository.setAutoPlayOnLaunch(false)
        }
    }

    private fun outlines() = runBlocking { app.database.backupDao().readMemos().filter { it.kind == MemoKind.OUTLINE.storageId } }
    private fun awaitTag(tag: String) = composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitFor(check: () -> Boolean) = composeRule.waitUntil(10_000, check)
    private fun outline(title: String = "", body: String = ""): Long = runBlocking {
        val id = app.database.memoDao().insert(MemoEntity(title = title, body = body, createdAt = 1, updatedAt = 1, kind = MemoKind.OUTLINE.storageId))
        app.outlineStore.createFor(id, body)
        id
    }

    private fun createFromPlus() {
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("create_outline")
        composeRule.onNodeWithTag("create_outline").performClick()
        waitFor { outlines().size == 1 }
        awaitTag("outliner_node_1")
    }

    private fun leave() {
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("create_outline")
    }

    @Test
    fun aNewOutlineLeftWithNothingInItIsAsIfItHadNeverBeenMade() {
        createFromPlus()
        leave()
        waitFor { outlines().isEmpty() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("無題のアウトライナー").fetchSemanticsNodes().size)
    }

    @Test
    fun aNewOutlineWithAWordStaysAndAnUntitledOneReadsMuDaiNoAutorainaa() {
        createFromPlus()
        composeRule.onNodeWithTag("outliner_node_1").performClick()
        composeRule.onNodeWithTag("outliner_node_1").performTextInput("あ")
        waitFor { outlines().singleOrNull()?.body?.contains("あ") == true }
        leave()
        composeRule.waitForIdle()
        assertEquals(1, outlines().size)
        // A saved outline with no words keeps its card, and an untitled card says 無題のアウトライナー.
        outline()
        waitFor { composeRule.onAllNodesWithText("無題のアウトライナー").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anOutlineThatAlreadyExistedIsNeverRemovedByBeingOpenedAndLeft() {
        val id = outline()
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$id")
        composeRule.onNodeWithTag("outline_card_$id").performClick()
        awaitTag("outliner_node_1")
        leave()
        composeRule.waitForIdle()
        assertEquals(listOf(id), outlines().map { it.id })
    }

    @Test
    fun theStoreRemovesOnlyAnOutlineWithNothingAtAllInIt() = runBlocking {
        val store = app.outlineStore
        val empty = outline()
        val blankLines = outline(body = "- \n\n  - ")
        val word = outline(body = "- あ")
        val titled = outline(title = "計画")
        val commented = outline()
        app.database.memoCommentDao().insert(MemoCommentEntity(memoId = commented, text = "いいね", createdAt = 1))
        val tagged = outline()
        val tag = app.database.tagDao().insert(TagEntity(name = "旅", normalizedName = "旅", createdAt = 1))
        app.database.tagDao().addTagsToMemos(listOf(tagged), listOf(tag))
        val photographed = outline()
        val sha = "c".repeat(64)
        app.database.attachmentDao().insertBlob(AttachmentBlobEntity(sha, AttachmentKind.IMAGE, "image/png", 10, 8, 8, 1))
        app.database.attachmentDao().insertMemoRelation(MemoPhotoAttachmentEntity(memoId = photographed, blobSha256 = sha, sortOrder = 0, createdAt = 1))
        val memo = app.database.memoDao().insert(MemoEntity(title = "", body = "", createdAt = 1, updatedAt = 1))

        assertTrue(store.discardIfEmpty(empty))
        assertTrue("bare markers and blank lines are not words", store.discardIfEmpty(blankLines))
        assertFalse(store.discardIfEmpty(word))
        assertFalse(store.discardIfEmpty(titled))
        assertFalse(store.discardIfEmpty(commented))
        assertFalse(store.discardIfEmpty(tagged))
        assertFalse(store.discardIfEmpty(photographed))
        assertFalse("a memo is never an outline to discard", store.discardIfEmpty(memo))
        assertEquals(setOf(word, titled, commented, tagged, photographed), outlines().map { it.id }.toSet())
        assertTrue("its rows went with it", app.database.outlineRowDao().rows(empty).isEmpty())
    }
}
