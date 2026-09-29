package io.github.cragcoffee.memoripple

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentKind
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.DiaryPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.memos.MemoBlock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The diary written in blocks (docs/MEMO_CONTENT_BLOCKS.md §11): an entry with photos shows its
 * words and photos in their order, the photos as content (nothing counts them), each with its ⋮;
 * the picture itself takes no touch; 未来の自分へ follows the last block; a LOCKED entry offers no
 * delete; words typed in a text land in that text; an entry without photos is its one field.
 */
class DiaryBlockEditorInstrumentationTest {
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

    private suspend fun photoRow(entryId: Long, index: Int): Long {
        val temp = java.io.File(app.cacheDir, "diary-photo-$index-${System.nanoTime()}.png")
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[index % 3])
            temp.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        val size = temp.length()
        val sha = AttachmentBlobStore.hash(temp)
        return app.attachmentRepository.installForRestore(listOf(Triple(temp, sha, size))) {
            app.database.withTransaction {
                app.database.attachmentDao().insertBlob(AttachmentBlobEntity(sha, AttachmentKind.IMAGE, "image/png", size, 1600, 900, 2L + index))
                app.database.attachmentDao().insertDiaryRelation(DiaryPhotoAttachmentEntity(diaryEntryId = entryId, blobSha256 = sha, sortOrder = index, createdAt = 10L + index))
            }
        }
    }

    /** texts[0], photo, texts[1], photo, … — written the way the editor writes them. */
    private fun seed(texts: List<String>, state: DiaryState = DiaryState.DRAFT): Long = runBlocking {
        val today = app.timeProvider.currentLocalDate().toEpochDay()
        val id = app.database.diaryDao().insert(
            DiaryEntryEntity(diaryDateEpochDay = today, body = texts.first(), state = DiaryState.DRAFT, createdAt = 1, updatedAt = 1),
        )
        app.diaryContentStore.createFor(id, texts.first())
        var writing = app.diaryContentStore.materialize(id).single().id
        texts.drop(1).forEachIndexed { index, text ->
            val relation = photoRow(id, index)
            writing = app.diaryContentStore.placePhoto(id, relation, writing)!!
            app.diaryContentStore.saveTexts(id, mapOf(writing to text))
        }
        if (state != DiaryState.DRAFT) {
            val entry = app.database.diaryDao().findById(id)!!
            app.database.diaryDao().update(entry.copy(state = state, lockedAt = if (state == DiaryState.LOCKED) 5 else null))
        }
        id
    }

    private fun blocks(id: Long) = runBlocking { app.diaryContentStore.materialize(id) }
    private fun body(id: Long) = runBlocking { app.database.diaryDao().findById(id)!!.body }

    private fun open(id: Long) {
        composeRule.onNodeWithTag("nav_calendar").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("timeline_item_journal_$id").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("timeline_item_journal_$id").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("diary_body").fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
    }

    @Test
    fun anEntryWithPhotosShowsItsWordsAndPhotosInOrderAsContent() {
        val id = seed(listOf("朝は晴れ", "昼は散歩", "夜は雨"))
        open(id)
        composeRule.onNodeWithTag("memo_block_column").assertIsDisplayed()
        val list = blocks(id)
        assertEquals(listOf("T", "P", "T", "P", "T"), list.map { if (it is MemoBlock.Text) "T" else "P" })
        val photo = list.filterIsInstance<MemoBlock.Photo>().first()
        composeRule.onNodeWithTag("memo_photo_block_${photo.id}").performScrollTo().assertIsDisplayed()
        assertTrue("nothing counts the photos", composeRule.onAllNodesWithText("枚", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue("no strip", composeRule.onAllNodesWithTag("open_photo_reorder").fetchSemanticsNodes().isEmpty())
        // The picture takes no touch; its ⋮ offers the three.
        composeRule.onNodeWithTag("memo_photo_block_${photo.id}").performClick()
        composeRule.onNodeWithTag("memo_photo_block_${photo.id}").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("photo_viewer_pager").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("memo_photo_menu_${photo.id}").performClick()
        composeRule.onNodeWithTag("memo_photo_fullscreen").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_photo_overview").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_photo_delete").assertIsDisplayed()
        assertEquals("朝は晴れ\n昼は散歩\n夜は雨", body(id))
    }

    @Test
    fun futureCommentsFollowTheLastBlock() {
        val id = seed(listOf("今日", "続き"))
        open(id)
        composeRule.onNodeWithTag("future_comment_empty_state").performScrollTo().assertIsDisplayed()
        val lastText = blocks(id).last()
        val lastTop = composeRule.onNodeWithTag("diary_body").fetchSemanticsNode().positionInRoot.y
        val futureTop = composeRule.onNodeWithTag("future_comment_empty_state").fetchSemanticsNode().positionInRoot.y
        assertTrue("未来の自分へ comes after the words (${lastText.id})", futureTop > lastTop)
    }

    @Test
    fun wordsTypedInATextLandInThatText() {
        val id = seed(listOf("一", "二"))
        open(id)
        composeRule.onNodeWithTag("diary_body").performTextInput("追加")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.waitUntil(5_000) { body(id).contains("追加") }
        assertEquals("one text per place, the photo between", listOf("T", "P", "T"), blocks(id).map { if (it is MemoBlock.Text) "T" else "P" })
        assertTrue(body(id).startsWith("一\n") && body(id).contains("二"))
    }

    @Test
    fun aLockedEntryOffersNoDeleteAndTakesNoWords() {
        val id = seed(listOf("昔の日", "写真の後"), state = DiaryState.LOCKED)
        open(id)
        composeRule.onNodeWithTag("diary_state_label").assertIsDisplayed()
        val photo = blocks(id).filterIsInstance<MemoBlock.Photo>().single()
        composeRule.onNodeWithTag("memo_photo_menu_${photo.id}").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_photo_fullscreen").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("memo_photo_delete").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("diary_add_photo").fetchSemanticsNodes().isEmpty())
        assertEquals("昔の日\n写真の後", body(id))
    }

    @Test
    fun anEntryWithoutPhotosIsItsOneField() {
        val id = seed(listOf("写真なし"))
        open(id)
        assertTrue(composeRule.onAllNodesWithTag("memo_block_column").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("diary_body").assertIsDisplayed()
    }
}
