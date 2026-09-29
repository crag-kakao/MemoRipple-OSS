package io.github.cragcoffee.memoripple

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.domain.memos.ExportableMemo
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.memos.MemoMarkdownExport
import io.github.cragcoffee.memoripple.domain.memos.MemoMarkdownImport
import io.github.cragcoffee.memoripple.ui.memos.MarkdownImportConfirmDialog
import io.github.cragcoffee.memoripple.ui.settings.PortableImportConfirmDialog
import io.github.cragcoffee.memoripple.ui.settings.PortableImportViewModel
import io.github.cragcoffee.memoripple.ui.theme.MemoRippleTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The imports' words (docs/OUTLINE_EXPORT_IMPORT.md §9): what they bring may be memos, outlines or
 * both, so the Markdown import's question and the portable import's preview and result count
 * ドキュメント — on the dialogs themselves, from real files — while what comes in keeps its kind.
 */
class ImportWordingInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    @Before
    fun startEmpty() {
        runBlocking { app.database.clearAllTables() }
    }

    private fun assertNothingSaysMemo() {
        assertEquals("no sentence calls it a メモ", 0, composeRule.onAllNodes(hasText("メモ", substring = true)).fetchSemanticsNodes().size)
    }

    private fun showMarkdownQuestion(markdown: String) {
        val parsed = MemoMarkdownImport.parse(markdown)
        composeRule.setContent {
            MemoRippleTheme { MarkdownImportConfirmDialog(parsed = parsed, onConfirm = {}, onDismiss = {}) }
        }
    }

    @Test
    fun aMemoFileIsAskedAboutAsADocument() {
        val markdown = MemoMarkdownExport.render(ExportableMemo("買い物", "牛乳\n卵"))
        assertFalse(MemoMarkdownImport.parse(markdown).single().outline)
        showMarkdownQuestion(markdown)
        composeRule.onNodeWithText("1件のドキュメントを読み込みますか？").assertIsDisplayed()
        assertNothingSaysMemo()
    }

    @Test
    fun anOutlineFileIsAskedAboutAsADocument() {
        val markdown = MemoMarkdownExport.render(ExportableMemo("旅行の計画", "- 旅行\n  - 京都", outline = true))
        assertTrue("still read as an outline", MemoMarkdownImport.parse(markdown).single().outline)
        showMarkdownQuestion(markdown)
        composeRule.onNodeWithText("1件のドキュメントを読み込みますか？").assertIsDisplayed()
        assertNothingSaysMemo()
    }

    @Test
    fun severalAreCountedTheSameWay() {
        showMarkdownQuestion(MemoMarkdownExport.renderAll(listOf(ExportableMemo("a", "x"), ExportableMemo("b", "y"), ExportableMemo("c", "z"))))
        composeRule.onNodeWithText("3件のドキュメントを読み込みますか？").assertIsDisplayed()
        assertNothingSaysMemo()
    }

    private fun png(index: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(listOf(Color.RED, Color.BLUE, Color.GREEN)[index % 3])
        return ByteArrayOutputStream().also { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            bitmap.recycle()
        }.toByteArray()
    }

    /** An outline with one photo, a memo with a photo and a broken one — the mix the words must fit. */
    private fun mixedZip(): File {
        fun memoMd(title: String, kindLine: String, body: String, photos: Int) =
            "# $title\n\n- 作成: 2026-09-01 10:00\n$kindLine\n---\n\n$body\n\n## 写真\n\n" +
                (1..photos).joinToString("") { "![写真 $it](photos/photo-0$it.png)\n\n" }
        val zip = File(app.cacheDir, "mixed-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            fun entry(name: String, bytes: ByteArray) {
                out.putNextEntry(ZipEntry("MemoRipple-Export/memos/active/$name"))
                out.write(bytes)
                out.closeEntry()
            }
            entry("001_計画/memo.md", memoMd("旅行の計画", "- 種類: outline\n", "- 旅行\n  - 京都", 1).toByteArray())
            entry("001_計画/photos/photo-01.png", png(0))
            entry("002_買い物/memo.md", memoMd("買い物", "", "牛乳", 2).toByteArray())
            entry("002_買い物/photos/photo-01.png", png(1))
            entry("002_買い物/photos/photo-02.png", "写真ではない".toByteArray())
        }
        return zip
    }

    @Test
    fun aMixedPortableImportIsCountedAsDocumentsAndEachKeepsItsKind() {
        val zip = mixedZip()
        val viewModel = PortableImportViewModel(app)
        composeRule.runOnIdle { viewModel.onSourceSelected(Uri.fromFile(zip)) }
        composeRule.waitUntil(10_000) { viewModel.state.value is PortableImportViewModel.UiState.Confirm }
        val preview = (viewModel.state.value as PortableImportViewModel.UiState.Confirm).preview
        composeRule.setContent {
            MemoRippleTheme { PortableImportConfirmDialog(memoCount = preview.memos, photoCount = preview.photos, onConfirm = {}, onDismiss = {}) }
        }
        composeRule.onNodeWithText("ドキュメント2件と写真3枚を新しく取り込みます。", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("日記・ノート・コメントはこの取り込みの対象外です。", substring = true).assertIsDisplayed()
        assertNothingSaysMemo()

        composeRule.runOnIdle { viewModel.confirmImport() }
        composeRule.waitUntil(20_000) { viewModel.state.value is PortableImportViewModel.UiState.Finished }
        zip.delete()
        val message = (viewModel.state.value as PortableImportViewModel.UiState.Finished).message
        assertEquals("ドキュメント2件・写真2枚を取り込みました（取り込めなかった写真が1枚あります）", message)

        // The words changed; what came in did not: the outline is an outline, the memo a memo.
        val kinds = runBlocking { app.database.backupDao().readMemos() }.associate { it.title to it.kind }
        assertEquals(MemoKind.OUTLINE.storageId, kinds["旅行の計画"])
        assertEquals(MemoKind.MEMO.storageId, kinds["買い物"])
    }
}
