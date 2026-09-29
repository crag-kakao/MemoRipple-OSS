package io.github.cragcoffee.memoripple

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentKind
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.domain.memos.MemoBlock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The memo editor writing in blocks (docs/MEMO_CONTENT_BLOCKS.md): texts and photos in their order
 * in writing and in reading; another text is written by tapping it; a photo moves and is deleted
 * from its own menu; undo goes back in the text it was in; the page says nothing about the photos;
 * a memo without photos is the one field it always was.
 */
class MemoBlockEditorInstrumentationTest {
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

    private suspend fun photoRow(memoId: Long, index: Int, width: Int, height: Int): Long {
        val temp = java.io.File(app.cacheDir, "block-photo-$index-${System.nanoTime()}.png")
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[index % 3])
            temp.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        val size = temp.length()
        val sha = AttachmentBlobStore.hash(temp)
        return app.attachmentRepository.installForRestore(listOf(Triple(temp, sha, size))) {
            app.database.withTransaction {
                app.database.attachmentDao().insertBlob(AttachmentBlobEntity(sha, AttachmentKind.IMAGE, "image/png", size, width, height, 2L + index))
                app.database.attachmentDao().insertMemoRelation(MemoPhotoAttachmentEntity(memoId = memoId, blobSha256 = sha, sortOrder = index, createdAt = 10L + index))
            }
        }
    }

    /** texts[0], photo, texts[1], photo, … — written the way the editor writes them. */
    private fun seed(texts: List<String>): Long = runBlocking {
        val memoId = app.database.memoDao().insert(MemoEntity(title = "ノート", body = texts.first(), createdAt = 1, updatedAt = 1))
        app.memoContentStore.createFor(memoId, texts.first())
        var writing = app.memoContentStore.materialize(memoId).single().id
        texts.drop(1).forEachIndexed { index, text ->
            val relation = photoRow(memoId, index, 1600, 900)
            writing = app.memoContentStore.placePhoto(memoId, relation, writing)!!
            app.memoContentStore.saveTexts(memoId, mapOf(writing to text))
        }
        memoId
    }

    private fun blocks(memoId: Long) = runBlocking { app.memoContentStore.materialize(memoId) }
    private fun body(memoId: Long) = runBlocking { app.database.memoDao().findById(memoId)!!.body }
    private fun top(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    private fun tagOf(block: MemoBlock, activeId: Long) = when (block) {
        is MemoBlock.Photo -> "memo_photo_block_${block.id}"
        is MemoBlock.Text -> if (block.id == activeId) "memo_body" else "memo_text_block_${block.id}"
    }

    private fun open(memoId: Long, writing: Boolean) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("memo_card_$memoId").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        runCatching {
            composeRule.waitUntil(3_000) {
                composeRule.onAllNodesWithTag("reading_edit").fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isNotEmpty()
            }
        }
        if (writing && composeRule.onAllNodesWithTag("reading_edit").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("reading_edit").performClick()
        }
        if (writing) composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("memo_block_column").fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        runCatching { composeRule.waitUntil(5_000, check) }
        assertTrue(what, check())
    }

    @Test
    fun writingShowsTheTextsAndPhotosInTheirOrder() {
        val memoId = seed(listOf("今日は公園へ行った", "桜がきれいだった", "また行きたい"))
        open(memoId, writing = true)
        val list = blocks(memoId)
        assertEquals(listOf("T", "P", "T", "P", "T"), list.map { if (it is MemoBlock.Text) "T" else "P" })
        val active = list.last().id
        val tops = list.map { top(tagOf(it, active)) }
        assertEquals("one column, top to bottom, in the blocks' order", tops.sorted(), tops)
        assertEquals("今日は公園へ行った\n桜がきれいだった\nまた行きたい", body(memoId))
    }

    @Test
    fun readingShowsThePhotosWhereTheyAre() {
        val memoId = seed(listOf("一行目", "二行目", "三行目"))
        open(memoId, writing = false)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("reading_photos_1").fetchSemanticsNodes().isNotEmpty() }
        val first = composeRule.onNodeWithText("一行目").fetchSemanticsNode().positionInRoot.y
        val photoA = top("reading_photos_1")
        val second = composeRule.onNodeWithText("二行目").fetchSemanticsNode().positionInRoot.y
        assertTrue("text, photo, text — as written", first < photoA && photoA < second)
        composeRule.onNodeWithTag("memo_reading_view").performTouchInput { swipeUp() }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("reading_photos_2").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(top("reading_photos_2") < composeRule.onNodeWithText("三行目").fetchSemanticsNode().positionInRoot.y)
    }

    @Test
    fun aMigratedMemoOpensAtTheTopWithItsPhotosAboveItsText() {
        // S20, 2026-09-24: the page was drawn once without the blocks and the list kept the text in
        // place when the photos arrived above it — the photos opened scrolled out of sight.
        val memoId = runBlocking {
            val id = app.database.memoDao().insert(MemoEntity(title = "移行", body = "写真の下の文章", createdAt = 1, updatedAt = 1))
            app.memoContentStore.createFor(id, "写真の下の文章")
            listOf(0, 1).forEach { index -> app.memoContentStore.placePhoto(id, photoRow(id, index, 1600, 900), null) }
            id
        }
        assertEquals(listOf("P", "P", "T"), blocks(memoId).map { if (it is MemoBlock.Text) "T" else "P" })
        open(memoId, writing = false)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("reading_photos_0").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("reading_photos_0").assertIsDisplayed()
        assertTrue("the photos are at the top of the page", top("reading_photos_0") < composeRule.onNodeWithText("写真の下の文章").fetchSemanticsNode().positionInRoot.y)
    }

    @Test
    fun tappingAnotherTextWritesThere() {
        val memoId = seed(listOf("一行目", "二行目"))
        open(memoId, writing = true)
        val first = blocks(memoId).first().id
        composeRule.onNodeWithTag("memo_text_block_$first").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("memo_text_block_$first").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("memo_body").performTextInput("追加")
        waitFor("the words land in the first text") { body(memoId).startsWith("一行目") && body(memoId).contains("追加") && body(memoId).endsWith("二行目") }
        assertEquals(2, blocks(memoId).count { it is MemoBlock.Text })
    }

    @Test
    fun aPhotosRoundMenuOffersFullScreenTheOverviewAndDeleteAndThePictureTakesNoTouch() {
        val memoId = seed(listOf("一", "二", "三"))
        open(memoId, writing = true)
        val photo = blocks(memoId).filterIsInstance<MemoBlock.Photo>().first()
        // The picture itself: a tap and a long press open nothing.
        composeRule.onNodeWithTag("memo_photo_block_${photo.id}").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_photo_block_${photo.id}").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("photo_viewer_pager").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("memo_photo_delete").fetchSemanticsNodes().isEmpty())
        // Its ⋮: フルスクリーン, 写真一覧, 削除 — and no step moves.
        composeRule.onNodeWithTag("memo_photo_menu_${photo.id}").performClick()
        composeRule.onNodeWithText("フルスクリーン").assertIsDisplayed()
        composeRule.onNodeWithText("写真一覧").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_photo_delete").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("上へ").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("下へ").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("memo_photo_fullscreen").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("photo_viewer_pager").fetchSemanticsNodes().isNotEmpty() }
        // The viewer's own 閉じる (a system Back waits for a window focus a dialog may not hand back).
        composeRule.onNodeWithContentDescription("閉じる").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("photo_viewer_pager").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("memo_photo_menu_${photo.id}").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_photo_overview").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("memo_photo_overview_sheet").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("並べ替え").assertIsDisplayed()
        assertEquals("nothing was written", "一\n二\n三", body(memoId))
    }

    @Test
    fun deletingAPhotoFromItsMenuAsksAndKeepsTheText() {
        val memoId = seed(listOf("一", "二", "三"))
        open(memoId, writing = true)
        val firstPhoto = blocks(memoId).filterIsInstance<MemoBlock.Photo>().first()
        composeRule.onNodeWithTag("memo_photo_menu_${firstPhoto.id}").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_photo_delete").performClick()
        composeRule.onNodeWithText("写真を削除しますか？").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_photo_delete_confirm").performClick()
        waitFor("one photo left") { blocks(memoId).count { it is MemoBlock.Photo } == 1 }
        assertEquals("the words are all there", "一\n二\n三", body(memoId))
    }

    @Test
    fun undoGoesBackInTheTextItWasIn() {
        val memoId = seed(listOf("一", "二"))
        open(memoId, writing = true)
        val first = blocks(memoId).first().id
        composeRule.onNodeWithTag("memo_text_block_$first").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("memo_text_block_$first").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("memo_body").performTextInput("A")
        val last = blocks(memoId).last().id
        composeRule.onNodeWithTag("memo_text_block_$last").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("memo_text_block_$last").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("memo_body").performTextInput("B")
        waitFor("both typed") { body(memoId).contains("A") && body(memoId).contains("B") }
        composeRule.onNodeWithTag("toolbar_undo").performScrollTo().performClick()
        waitFor("the last text goes back first") { !body(memoId).contains("B") && body(memoId).contains("A") }
        composeRule.onNodeWithTag("toolbar_undo").performScrollTo().performClick()
        waitFor("then the first text, where it was typed") { body(memoId) == "一\n二" }
    }

    @Test
    fun thePageSaysNothingAboutThePhotosAndReorderIsInTheMenu() {
        val memoId = seed(listOf("一", "二", "三"))
        open(memoId, writing = true)
        composeRule.onAllNodesWithText("写真", substring = false).assertCountEquals(0)
        composeRule.onAllNodesWithText("枚", substring = true).assertCountEquals(0)
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        composeRule.onNodeWithTag("open_photo_reorder").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("写真を並べ替え").assertIsDisplayed()
        composeRule.onNodeWithText("キャンセル").performClick()
    }

    @Test
    fun aRecreationKeepsTheBlocksAndWhatWasTyped() {
        val memoId = seed(listOf("一", "二"))
        open(memoId, writing = true)
        // A tap puts the caret where the writer continues: after the short last line.
        composeRule.onNodeWithTag("memo_body").performClick()
        composeRule.onNodeWithTag("memo_body").performTextInput("続き")
        waitFor("saved") { body(memoId).endsWith("続き") }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        assertEquals(listOf("T", "P", "T"), blocks(memoId).map { if (it is MemoBlock.Text) "T" else "P" })
        assertTrue(body(memoId).startsWith("一\n二") && body(memoId).endsWith("続き"))
    }

    @Test
    fun aMemoWithoutPhotosIsTheOneFieldItWas() {
        val memoId = runBlocking {
            app.database.memoDao().insert(MemoEntity(title = "写真なし", body = "本文だけ", createdAt = 1, updatedAt = 1))
        }
        open(memoId, writing = false)
        if (composeRule.onAllNodesWithTag("reading_edit").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("reading_edit").performClick()
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodesWithTag("memo_block_column").assertCountEquals(0)
        composeRule.onAllNodesWithTag("memo_body").assertCountEquals(1)
        composeRule.onNodeWithTag("memo_body").performTextInput("と追記")
        waitFor("the body is saved as always") { body(memoId).contains("追記") }
        assertEquals("one text block holds it", listOf(body(memoId)), blocks(memoId).map { (it as MemoBlock.Text).text })
    }
}
