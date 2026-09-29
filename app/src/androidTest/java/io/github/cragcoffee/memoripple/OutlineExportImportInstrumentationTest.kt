package io.github.cragcoffee.memoripple

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentKind
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.data.OutlineStore
import io.github.cragcoffee.memoripple.domain.memos.ExportableMemo
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.memos.MemoMarkdownExport
import io.github.cragcoffee.memoripple.domain.memos.MemoMarkdownImport
import io.github.cragcoffee.memoripple.domain.outline.OutlineRows
import io.github.cragcoffee.memoripple.portableexport.PortableExportEngine
import io.github.cragcoffee.memoripple.portableexport.PortableImportEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * An outline taken out through the portable export and brought back through its import is an
 * outline again (the outline export → import issue, audit 2026-09-25): its kind survives, its lines
 * come back as outline rows, its photos come back with it; a memo stays a memo; and a file written
 * before the kind travelled (no kind in it) still comes back as a memo.
 */
class OutlineExportImportInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private val outlineBody = "- 旅行\n  - [ ] 宿を取る\n  - 京都\n- 帰る"

    @Before
    fun startEmpty() {
        runBlocking {
            app.database.clearAllTables()
            app.settingsRepository.setAutoPlayOnLaunch(false)
        }
    }

    private suspend fun photo(memoId: Long, index: Int): Long {
        val temp = File(app.cacheDir, "outline-export-$index-${System.nanoTime()}.png")
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(listOf(Color.RED, Color.BLUE)[index % 2])
            temp.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        val sha = AttachmentBlobStore.hash(temp)
        // Read before the install moves the file into the store.
        val size = temp.length()
        return app.attachmentRepository.installForRestore(listOf(Triple(temp, sha, size))) {
            app.database.withTransaction {
                app.database.attachmentDao().insertBlob(AttachmentBlobEntity(sha, AttachmentKind.IMAGE, "image/png", size, 8, 8, 2L))
                app.database.attachmentDao().insertMemoRelation(MemoPhotoAttachmentEntity(memoId = memoId, blobSha256 = sha, sortOrder = index, createdAt = 10L))
            }
        }
    }

    private fun outline(title: String, withPhotos: Boolean): Long = runBlocking {
        val id = app.database.memoDao().insert(MemoEntity(title = title, body = outlineBody, createdAt = 1, updatedAt = 1, kind = MemoKind.OUTLINE.storageId))
        app.outlineStore.createFor(id, outlineBody)
        if (withPhotos) {
            val (a, b) = photo(id, 0) to photo(id, 1)
            // 旅行 / [photo a] / 宿を取る / 京都 / [photo b] / 帰る
            val rows = listOf(
                OutlineRows.Row(1, "- 旅行"),
                OutlineRows.Row(10, "  ", photo = a),
                OutlineRows.Row(2, "  - [ ] 宿を取る"),
                OutlineRows.Row(3, "  - 京都"),
                OutlineRows.Row(11, "", photo = b),
                OutlineRows.Row(4, "- 帰る"),
            )
            val version = app.database.memoDao().findById(id)!!.updatedAt
            assertTrue(app.outlineStore.saveDocument(id, title, OutlineRows.documentOf(rows), 2, version) is OutlineStore.Save.Saved)
        }
        id
    }

    private fun memo(title: String, body: String): Long = runBlocking {
        val id = app.database.memoDao().insert(MemoEntity(title = title, body = body, createdAt = 1, updatedAt = 1))
        app.memoContentStore.createFor(id, body)
        id
    }

    private fun exportThenImportIntoAnEmptyLibrary(): PortableImportEngine.ImportResult.Done {
        val target = File(app.cacheDir, "outline-export-${System.nanoTime()}.zip")
        try {
            val exported = runBlocking { app.portableExportEngine.export(includeTrash = false, destination = Uri.fromFile(target), nowMillis = 1_756_600_800_000) }
            assertTrue(exported is PortableExportEngine.ExportResult.Done)
            // What the archive holds, for any failure below to say.
            zipListing = java.util.zip.ZipFile(target).use { zip ->
                zip.entries().toList().joinToString("\n") { entry ->
                    entry.name + if (entry.name.endsWith("memo.md")) ":\n" + zip.getInputStream(entry).bufferedReader().readText() else ""
                }
            }
            runBlocking { app.database.clearAllTables() }
            val result = runBlocking { app.portableImportEngine.import(Uri.fromFile(target)) }
            assertTrue("$result", result is PortableImportEngine.ImportResult.Done)
            return result as PortableImportEngine.ImportResult.Done
        } finally {
            target.delete()
        }
    }

    private var zipListing = ""

    private fun imported(title: String) = runBlocking { app.database.backupDao().readMemos().single { it.title == title } }

    @Test
    fun anOutlineComesBackAsAnOutlineWithItsLines() {
        outline("旅行の計画", withPhotos = false)
        memo("買い物", "牛乳\n卵")
        val result = exportThenImportIntoAnEmptyLibrary()
        assertEquals(2, result.memos)
        // The outline's file says what it is; the memo's does not.
        val outlineFile = zipListing.substringAfter("-旅行の計画-").substringAfter("memo.md:\n").substringBefore("\n---")
        assertTrue(zipListing, outlineFile.lines().contains("- 種類: outline"))
        val memoFile = zipListing.substringAfter("-買い物-").substringAfter("memo.md:\n").substringBefore("\n---")
        assertFalse(zipListing, memoFile.contains("種類"))
        val back = imported("旅行の計画")
        assertEquals("the kind travelled", MemoKind.OUTLINE.storageId, back.kind)
        assertEquals(outlineBody, back.body)
        val rows = runBlocking { app.outlineStore.materialize(back.id) }
        assertEquals("the lines are outline rows again", outlineBody, rows?.let { io.github.cragcoffee.memoripple.domain.outline.OutlineText.serialize(it) })
    }

    @Test
    fun aMemoStaysAMemo() {
        outline("旅行の計画", withPhotos = false)
        memo("買い物", "牛乳\n卵")
        exportThenImportIntoAnEmptyLibrary()
        val back = imported("買い物")
        assertEquals(MemoKind.MEMO.storageId, back.kind)
        assertEquals("牛乳\n卵", back.body)
    }

    @Test
    fun anOutlineWithPhotoRowsComesBackAsAnOutlineWithItsPhotos() {
        outline("写真のある計画", withPhotos = true)
        val result = exportThenImportIntoAnEmptyLibrary()
        val relations = runBlocking { app.database.backupDao().readMemoPhotoAttachments() }
        assertEquals("photos imported ($result, relations $relations); the archive:\n$zipListing", 2, result.photos)
        val back = imported("写真のある計画")
        assertEquals(MemoKind.OUTLINE.storageId, back.kind)
        assertEquals("the words are the lines, the photos are not in them", outlineBody, back.body)
        val document = runBlocking { app.outlineStore.materialize(back.id) }!!
        val photoRows = document.entries.count { (it as? io.github.cragcoffee.memoripple.domain.outline.OutlineNode)?.isPhoto == true }
        assertEquals("both photos are photo rows", 2, photoRows)
    }

    @Test
    fun aFileWrittenBeforeTheKindTravelledStillComesBackAsAMemo() {
        // What the portable export has always written: no word about the kind.
        val legacy = listOf(
            "# 旅行の計画", "", "- 作成: 2026-09-01 10:00", "- 更新: 2026-09-01 10:00", "- お気に入り: いいえ", "- ピン留め: いいえ", "", "---", "",
        ).joinToString("\n") + "\n" + outlineBody + "\n"
        val zip = File(app.cacheDir, "legacy-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("MemoRipple/memos/active/001_旅行の計画/memo.md"))
            out.write(legacy.toByteArray())
            out.closeEntry()
        }
        val result = runBlocking { app.portableImportEngine.import(Uri.fromFile(zip)) }
        zip.delete()
        assertTrue("$result", result is PortableImportEngine.ImportResult.Done)
        val back = imported("旅行の計画")
        assertEquals("no kind in the file: a memo, as before", MemoKind.MEMO.storageId, back.kind)
        assertEquals(outlineBody, back.body)
    }

    @Test
    fun aKindThisAppDoesNotKnowComesBackAsAMemo() {
        val file = listOf("# 旅行の計画", "", "- 作成: 2026-09-01 10:00", "- 種類: journal", "", "---", "").joinToString("\n") + "\n" + outlineBody + "\n"
        val zip = File(app.cacheDir, "unknown-kind-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("MemoRipple-Export/memos/active/001_旅行の計画/memo.md"))
            out.write(file.toByteArray())
            out.closeEntry()
        }
        val result = runBlocking { app.portableImportEngine.import(Uri.fromFile(zip)) }
        zip.delete()
        assertTrue("$result", result is PortableImportEngine.ImportResult.Done)
        assertEquals("an unknown kind is a memo, as every file was before", MemoKind.MEMO.storageId, imported("旅行の計画").kind)
    }

    // --- One document as Markdown (⋮ → 書き出し / the wall's Markdownを読み込む) ---

    private fun importMarkdown(markdown: String) = runBlocking { app.memoRepository.createFromImport(MemoMarkdownImport.parse(markdown)) }

    @Test
    fun anOutlineWrittenAsOneMarkdownFileComesBackAsAnOutline() {
        val markdown = MemoMarkdownExport.render(ExportableMemo("旅行の計画", outlineBody, outline = true))
        assertTrue(markdown.startsWith("---\nmemoripple: outline\n---\n"))
        importMarkdown(markdown)
        val back = imported("旅行の計画")
        assertEquals(MemoKind.OUTLINE.storageId, back.kind)
        assertEquals(outlineBody, back.body)
        val rows = runBlocking { app.outlineStore.materialize(back.id) }
        assertEquals("the lines are outline rows", outlineBody, rows?.let { io.github.cragcoffee.memoripple.domain.outline.OutlineText.serialize(it) })
    }

    @Test
    fun aMemoWrittenAsOneMarkdownFileStaysAMemo() {
        val markdown = MemoMarkdownExport.render(ExportableMemo("買い物", "牛乳\n卵"))
        assertEquals("# 買い物\n\n牛乳\n卵\n", markdown)
        importMarkdown(markdown)
        val back = imported("買い物")
        assertEquals(MemoKind.MEMO.storageId, back.kind)
        assertEquals("牛乳\n卵", back.body)
        assertTrue("a memo's blocks", runBlocking { app.memoContentStore.materialize(back.id) }.isNotEmpty())
    }

    @Test
    fun markdownWithNoFrontMatterOrSomeoneElsesIsAMemo() {
        importMarkdown("# 旅行の計画\n\n$outlineBody\n")
        assertEquals(MemoKind.MEMO.storageId, imported("旅行の計画").kind)
        importMarkdown("---\ntitle: 別の計画\ntype: outline\n---\n# 別の計画\n\n$outlineBody\n")
        assertEquals(MemoKind.MEMO.storageId, imported("別の計画").kind)
        importMarkdown("---\nmemoripple: diary\n---\n# 三つ目\n\n本文\n")
        assertEquals(MemoKind.MEMO.storageId, imported("三つ目").kind)
    }
}
