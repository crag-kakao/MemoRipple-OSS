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
import io.github.cragcoffee.memoripple.portableexport.PortableExportEngine
import io.github.cragcoffee.memoripple.portableexport.PortableImportEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * No photo is lost on the way back in (docs/OUTLINE_EXPORT_IMPORT.md §5). The portable import
 * installs photos through its own `AttachmentRepository`; the photo store's one lock (on
 * `AttachmentBlobStore`, shared by every repository over the same folder) keeps the app's own
 * sweep from deleting a file the import is still writing. Every photo comes back, none twice,
 * and nothing is left behind — no row without its file, no file without its row, no temp file —
 * whether the import succeeds, meets a broken photo, runs again, or races the sweep.
 */
class PortableImportPhotoIntegrityInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private val blobDir: File get() = app.attachmentBlobStore.blobFile("0".repeat(64)).parentFile!!
    private val tempDir: File get() = File(app.filesDir, "attachments/tmp")

    @Before
    fun startEmpty() {
        runBlocking {
            app.database.clearAllTables()
            app.settingsRepository.setAutoPlayOnLaunch(false)
            // Files left by earlier tests have no rows now; the sweep takes them, so every file
            // counted below is one this test made.
            app.attachmentRepository.garbageCollect()
        }
    }

    private fun png(index: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb((index * 37) % 256, (index * 91) % 256, (index * 53 + 17) % 256))
        return ByteArrayOutputStream().also { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            bitmap.recycle()
        }.toByteArray()
    }

    private fun memoWithPhotos(title: String, count: Int): Long = runBlocking {
        val id = app.database.memoDao().insert(MemoEntity(title = title, body = "写真の話", createdAt = 1, updatedAt = 1))
        app.memoContentStore.createFor(id, "写真の話")
        repeat(count) { index ->
            val temp = File(app.cacheDir, "integrity-$index-${System.nanoTime()}.png").apply { writeBytes(png(index)) }
            val sha = AttachmentBlobStore.hash(temp)
            val size = temp.length()
            app.attachmentRepository.installForRestore(listOf(Triple(temp, sha, size))) {
                app.database.withTransaction {
                    app.database.attachmentDao().insertBlob(AttachmentBlobEntity(sha, AttachmentKind.IMAGE, "image/png", size, 8, 8, 2L))
                    app.database.attachmentDao().insertMemoRelation(MemoPhotoAttachmentEntity(memoId = id, blobSha256 = sha, sortOrder = index, createdAt = 10L))
                }
            }
        }
        id
    }

    private fun exported(): File {
        val target = File(app.cacheDir, "integrity-${System.nanoTime()}.zip")
        val result = runBlocking { app.portableExportEngine.export(includeTrash = false, destination = Uri.fromFile(target), nowMillis = 1_756_600_800_000) }
        assertTrue("$result", result is PortableExportEngine.ExportResult.Done)
        return target
    }

    private fun import(zip: File): PortableImportEngine.ImportResult.Done {
        val result = runBlocking { app.portableImportEngine.import(Uri.fromFile(zip)) }
        assertTrue("$result", result is PortableImportEngine.ImportResult.Done)
        return result as PortableImportEngine.ImportResult.Done
    }

    /** Every row has its file, every file its row, every blob a relation, and no temp file is left. */
    private fun assertTheStoreIsWhole() = runBlocking {
        val dao = app.database.attachmentDao()
        val rows = dao.allBlobShas().toSet()
        val files = blobDir.listFiles().orEmpty().filter { AttachmentBlobStore.SHA256_PATTERN.matches(it.name) }.map { it.name }.toSet()
        assertEquals("a file for every row, a row for every file", rows, files)
        assertEquals("no blob without a relation", emptyList<String>(), dao.unreferencedBlobShas())
        val blobs = app.database.backupDao().readAttachmentBlobs().associateBy { it.sha256 }
        rows.forEach { sha -> assertEquals("the file is whole: $sha", blobs.getValue(sha).sizeBytes, File(blobDir, sha).length()) }
        assertEquals("no temp file is left", emptyList<String>(), tempDir.listFiles().orEmpty().map { it.name })
        assertEquals("no staging file is left", emptyList<String>(), blobDir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }.map { it.name })
    }

    private fun relationsOf(title: String): Int = runBlocking {
        val ids = app.database.backupDao().readMemos().filter { it.title == title }.map { it.id }.toSet()
        app.database.backupDao().readMemoPhotoAttachments().count { it.memoId in ids }
    }

    @Test
    fun twoPhotosComeBackAsTwo() {
        memoWithPhotos("二枚", 2)
        val zip = exported()
        runBlocking { app.database.clearAllTables() }
        val result = import(zip)
        zip.delete()
        assertEquals(2, result.photos)
        assertEquals(0, result.skippedPhotos)
        assertEquals(2, relationsOf("二枚"))
        assertTheStoreIsWhole()
    }

    @Test
    fun fivePhotosComeBackAsFive() {
        memoWithPhotos("五枚", 5)
        val zip = exported()
        runBlocking { app.database.clearAllTables() }
        val result = import(zip)
        zip.delete()
        assertEquals(5, result.photos)
        assertEquals(0, result.skippedPhotos)
        assertEquals(5, relationsOf("五枚"))
        assertTheStoreIsWhole()
    }

    @Test
    fun importingAgainAndAgainLosesNothingAndStoresEachPhotoOnce() {
        memoWithPhotos("何度も", 5)
        val zip = exported()
        repeat(3) { round ->
            val result = import(zip)
            assertEquals("round $round", 5, result.photos)
            assertEquals("round $round", 0, result.skippedPhotos)
        }
        zip.delete()
        // The original and three imports, each with its five photos; the bytes are stored once.
        assertEquals(4, runBlocking { app.database.backupDao().readMemos().count { it.title == "何度も" } })
        assertEquals(20, relationsOf("何度も"))
        assertEquals(5, runBlocking { app.database.attachmentDao().allBlobShas().size })
        assertTheStoreIsWhole()
    }

    @Test
    fun aBrokenPhotoIsCountedAndLeavesNothingBehind() {
        val memo = listOf(
            "# 一枚だけ壊れている", "", "- 作成: 2026-09-01 10:00", "", "---", "", "本文", "", "## 写真", "",
            "![写真 1](photos/photo-01.png)", "", "![写真 2](photos/photo-02.png)", "", "![写真 3](photos/photo-03.png)", "",
        ).joinToString("\n")
        val zip = File(app.cacheDir, "broken-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            fun entry(name: String, bytes: ByteArray) {
                out.putNextEntry(ZipEntry("MemoRipple-Export/memos/active/001_壊れ/$name"))
                out.write(bytes)
                out.closeEntry()
            }
            entry("memo.md", memo.toByteArray())
            entry("photos/photo-01.png", png(1))
            entry("photos/photo-02.png", "これは写真ではない".toByteArray())
            entry("photos/photo-03.png", png(3))
        }
        val result = import(zip)
        zip.delete()
        assertEquals(2, result.photos)
        assertEquals("the broken one is said, never silently dropped", 1, result.skippedPhotos)
        assertEquals(2, relationsOf("一枚だけ壊れている"))
        assertTheStoreIsWhole()
    }

    @Test
    fun theAppsOwnSweepRunningThroughTheImportTakesNoPhotoOfIt() {
        memoWithPhotos("掃除と同時", 5)
        val zip = exported()
        runBlocking {
            // The app's own repository sweeping without pause, as its attachment observer does after
            // each row the import writes — the race that used to delete a photo mid-install.
            val sweeping = launch(Dispatchers.IO) {
                while (isActive) {
                    app.attachmentRepository.garbageCollect()
                    yield()
                }
            }
            try {
                repeat(3) { round ->
                    val result = app.portableImportEngine.import(Uri.fromFile(zip))
                    assertTrue("$result", result is PortableImportEngine.ImportResult.Done)
                    result as PortableImportEngine.ImportResult.Done
                    assertEquals("round $round: $result", 5, result.photos)
                    assertEquals("round $round: $result", 0, result.skippedPhotos)
                }
            } finally {
                sweeping.cancelAndJoin()
            }
        }
        zip.delete()
        assertEquals(20, relationsOf("掃除と同時"))
        assertTheStoreIsWhole()
    }
}
