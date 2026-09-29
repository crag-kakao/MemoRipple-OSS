package io.github.cragcoffee.memoripple

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.AttachmentKind
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.DiaryPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentEntity
import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.portableexport.PortableExportEngine
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The portable export, end to end against real Room and real files: the sheet is reached from
 * 設定, and the archive the engine writes opens with an ordinary ZIP reader, keeps photo bytes
 * identical, keeps the trash home by default, and never carries an unopened future comment.
 */
@RunWith(AndroidJUnit4::class)
class PortableExportInstrumentationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun application(): MemoRippleApplication =
        composeRule.activity.application as MemoRippleApplication

    // A real JPEG carrying EXIF (camera model + GPS), so the export can prove both that
    // original bytes survive and that the strip option removes exactly this metadata.
    private val photoBytes: ByteArray by lazy {
        val temp = File.createTempFile("seed-photo", ".jpg")
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(
                16, 16, android.graphics.Bitmap.Config.ARGB_8888,
            )
            bitmap.eraseColor(android.graphics.Color.rgb(30, 111, 217))
            temp.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
            }
            android.media.ExifInterface(temp.absolutePath).apply {
                setAttribute(android.media.ExifInterface.TAG_MODEL, "TestCam")
                setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE, "35/1,0/1,0/1")
                setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE, "139/1,0/1,0/1")
                setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                saveAttributes()
            }
            temp.readBytes()
        } finally {
            temp.delete()
        }
    }
    private val photoSha = "a".repeat(64)
    private val diaryPhotoSha = "b".repeat(64)

    @Before
    fun seed() {
        runBlocking {
            val app = application()
            app.database.clearAllTables()
            app.settingsRepository.setAutoPlayOnLaunch(false)
            // One transaction for the whole seed: the app's own attachment GC runs on its
            // application scope and would otherwise race the blob rows away between inserts.
            app.database.withTransaction {
            val memoDao = app.database.memoDao()
            val activeId = memoDao.insert(
                MemoEntity(
                    title = "旅行の準備", body = "■ 持ち物\n- [ ] 充電器",
                    createdAt = 1, updatedAt = 9,
                ),
            )
            memoDao.insert(
                MemoEntity(
                    title = "眠るメモ", body = "アーカイブの本文。",
                    createdAt = 1, updatedAt = 5, archivedAt = 10,
                ),
            )
            memoDao.insert(
                MemoEntity(
                    title = "捨てたメモ", body = "ゴミ箱の本文。",
                    createdAt = 1, updatedAt = 2, trashedAt = 11,
                ),
            )
            val tagId = app.database.tagDao().insert(
                io.github.cragcoffee.memoripple.data.TagEntity(
                    name = "旅",
                    normalizedName = io.github.cragcoffee.memoripple.domain.tags
                        .TagNameNormalizer.normalizeKey("旅"),
                    createdAt = 1,
                ),
            )
            app.database.tagDao().attach(
                io.github.cragcoffee.memoripple.data.MemoTagCrossRef(activeId, tagId),
            )
            app.database.memoCommentDao().insert(
                MemoCommentEntity(
                    memoId = activeId, text = "もう一度確認する", createdAt = 3,
                    appearanceColor = "pink", appearanceSize = "large",
                ),
            )
            // One real photo blob on disk (written below, once this transaction has committed), one
            // relation pointing at it — and one relation whose blob is missing, to prove a single
            // bad photo cannot sink the export.
            app.database.attachmentDao().insertBlob(
                AttachmentBlobEntity(
                    sha256 = photoSha, kind = AttachmentKind.IMAGE, mimeType = "image/jpeg",
                    sizeBytes = photoBytes.size.toLong(), widthPx = 4, heightPx = 4, createdAt = 1,
                ),
            )
            app.database.attachmentDao().insertBlob(
                AttachmentBlobEntity(
                    sha256 = diaryPhotoSha, kind = AttachmentKind.IMAGE, mimeType = "image/png",
                    sizeBytes = 99, widthPx = 4, heightPx = 4, createdAt = 1,
                ),
            )
            app.database.attachmentDao().insertMemoRelation(
                MemoPhotoAttachmentEntity(
                    memoId = activeId, blobSha256 = photoSha, sortOrder = 0, createdAt = 1,
                ),
            )
            val diaryId = app.database.diaryDao().insertIgnoringConflict(
                DiaryEntryEntity(
                    diaryDateEpochDay = java.time.LocalDate.of(2026, 3, 5).toEpochDay(),
                    body = "今日の記録。", state = DiaryState.FINALIZED,
                    createdAt = 1, updatedAt = 1, finalizedAt = 2,
                ),
            )
            app.database.attachmentDao().insertDiaryRelation(
                DiaryPhotoAttachmentEntity(
                    diaryEntryId = diaryId, blobSha256 = diaryPhotoSha, sortOrder = 0, createdAt = 1,
                ),
            )
            val future = app.database.futureDiaryCommentDao()
            // Opened: the user has already been shown this one. It may leave.
            future.insert(
                FutureDiaryCommentEntity(
                    diaryEntryId = diaryId, text = "開封済みの言葉", sealedAt = 1, revealAt = 2,
                    deliveredAt = 3, revealedAt = 4, firstPresentedAt = 5,
                ),
            )
            // Sealed, delivered-only, and revealed-but-never-presented: none may leave.
            future.insert(
                FutureDiaryCommentEntity(
                    diaryEntryId = diaryId, text = "封印中の秘密", sealedAt = 1, revealAt = 999,
                ),
            )
            future.insert(
                FutureDiaryCommentEntity(
                    diaryEntryId = diaryId, text = "配達済みの秘密", sealedAt = 1, revealAt = 2,
                    deliveredAt = 3,
                ),
            )
            future.insert(
                FutureDiaryCommentEntity(
                    diaryEntryId = diaryId, text = "提示前の秘密", sealedAt = 1, revealAt = 2,
                    deliveredAt = 3, revealedAt = 4,
                ),
            )
            }
            // Phase 7 (HANDOFF §16.59): the file lands only once its rows are committed. The app's
            // attachment GC (application scope, fired by the relation tables' invalidation) lists the
            // blob directory against the *committed* rows on another connection; a file written inside
            // the transaction had no visible row yet and could be swept — the full-suite-only failure
            // of theArchiveAlsoReadsInABrowser. Rows first, then the file: the sweep then sees a
            // referenced blob whichever way it interleaves.
            val blobFile = app.attachmentBlobStore.blobFile(photoSha)
            blobFile.parentFile?.mkdirs()
            blobFile.writeBytes(photoBytes)
        }
    }

    private fun openSettings() {
        if (composeRule.onAllNodesWithTag("memo_top_menu").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("memo_top_menu").performClick()
            composeRule.onNodeWithTag("memo_open_settings").performClick()
        } else {
            composeRule.onNodeWithContentDescription("設定").performClick()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_list").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theSheetOpensFromSettingsAndTellsThePlainTruth() {
        openSettings()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_portable_export"))
        composeRule.onNodeWithTag("setting_portable_export").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("portable_export_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("portable_export_counts").assertIsDisplayed()
        composeRule.onNodeWithTag("portable_export_notes")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("portable_export_trash_toggle")
            .performScrollTo().performClick()
        composeRule.onNodeWithTag("portable_export_cancel").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("portable_export_sheet").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun exportToFile(includeTrash: Boolean): Map<String, ByteArray> {
        val target = File(application().cacheDir, "test-export-${System.nanoTime()}.zip")
        val result = runBlocking {
            application().portableExportEngine.export(
                includeTrash = includeTrash,
                destination = Uri.fromFile(target),
                nowMillis = 1_756_600_800_000,
            )
        }
        assertTrue(result is PortableExportEngine.ExportResult.Done)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(target.readBytes())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        target.delete()
        return entries
    }

    @Test
    fun theArchiveOpensReadsAndKeepsItsPromises() {
        val entries = exportToFile(includeTrash = false)
        val names = entries.keys

        // The root documents and the shelves.
        assertTrue(names.contains("MemoRipple-Export/README.md"))
        assertTrue(names.contains("MemoRipple-Export/INDEX.md"))
        val memoMd = names.single { it.matches(Regex("MemoRipple-Export/memos/active/.*/memo\\.md")) }
        assertTrue(names.any { it.startsWith("MemoRipple-Export/memos/archived/") })
        // Trash stays home by default.
        assertFalse(names.any { it.startsWith("MemoRipple-Export/trash/") })

        // The photo rode along byte for byte, in the memo's own photos folder.
        val photoEntry = memoMd.removeSuffix("memo.md") + "photos/photo-01.jpg"
        assertArrayEquals(photoBytes, entries[photoEntry])
        val memoText = entries.getValue(memoMd).toString(Charsets.UTF_8)
        assertTrue(memoText.contains("![写真 1](photos/photo-01.jpg)"))
        assertTrue(memoText.contains("# 持ち物"))
        assertTrue(memoText.contains("- [ ] 充電器"))
        assertTrue(memoText.contains("1. もう一度確認する"))
        assertTrue(memoText.contains("- 色: ピンク"))
        assertTrue(memoText.contains("- 大きさ: 大きめ"))

        // The diary is there; its broken photo became a sentence and a warning file.
        // Since Room 23 an entry's folder inside its day is `HHmm-<id>`, so several a day never collide.
        val diaryPath = names.single { Regex("MemoRipple-Export/diaries/2026/2026-03-05/\\d{4}-\\d+/diary\\.md").matches(it) }
        val diaryMd = entries.getValue(diaryPath).toString(Charsets.UTF_8)
        assertTrue(diaryMd.contains("今日の記録。"))
        assertTrue(diaryMd.contains("> 写真1は読み込めなかったため、書き出されませんでした。"))
        assertTrue(names.contains("MemoRipple-Export/EXPORT_WARNINGS.md"))
        val warnings = entries.getValue("MemoRipple-Export/EXPORT_WARNINGS.md")
            .toString(Charsets.UTF_8)
        assertFalse(warnings.contains("/data/"))
        assertFalse(warnings.contains("content://"))

        // Future privacy: the opened comment leaves; the three secrets never do, anywhere.
        assertTrue(diaryMd.contains("開封済みの言葉"))
        val everything = entries.values.joinToString("\n") { it.toString(Charsets.UTF_8) }
        assertFalse(everything.contains("封印中の秘密"))
        assertFalse(everything.contains("配達済みの秘密"))
        assertFalse(everything.contains("提示前の秘密"))

        // INDEX links resolve to entries that actually exist.
        val index = entries.getValue("MemoRipple-Export/INDEX.md").toString(Charsets.UTF_8)
        Regex("""\]\(([^)]+)\)""").findAll(index).forEach { match ->
            assertTrue(names.contains("MemoRipple-Export/" + match.groupValues[1]))
        }
    }

    @Test
    fun trashJoinsOnlyWhenAskedAndInItsOwnFolder() {
        val entries = exportToFile(includeTrash = true)
        val trashMd = entries.keys.single {
            it.matches(Regex("MemoRipple-Export/trash/.*/memo\\.md"))
        }
        assertTrue(entries.getValue(trashMd).toString(Charsets.UTF_8).contains("ゴミ箱の本文。"))
    }

    @Test
    fun cancellationLeavesNoStagingAndNoArchive() {
        val app = application()
        val target = File(app.cacheDir, "test-cancel-${System.nanoTime()}.zip")
        val threw = runBlocking {
            try {
                app.portableExportEngine.export(
                    includeTrash = false,
                    destination = Uri.fromFile(target),
                    nowMillis = 1_756_600_800_000,
                    onProgress = { _, _ -> throw CancellationException("stop") },
                )
                false
            } catch (expected: CancellationException) {
                true
            }
        }
        assertTrue(threw)
        // The staging file is gone, and nothing was copied to the destination.
        val leftovers = app.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("portable-export") }
        assertTrue(leftovers.isEmpty())
        assertTrue(!target.exists() || target.length() == 0L)
        target.delete()
    }

    @Test
    fun theStripOptionRemovesWhatACameraWrote() {
        fun exifOf(bytes: ByteArray): android.media.ExifInterface {
            val temp = File.createTempFile("exif-check", ".jpg", application().cacheDir)
            temp.writeBytes(bytes)
            return android.media.ExifInterface(temp.absolutePath).also { temp.delete() }
        }

        val kept = exportToFile(includeTrash = false)
        val photoEntry = kept.keys.single { it.endsWith("photos/photo-01.jpg") }
        val keptExif = exifOf(kept.getValue(photoEntry))
        assertEquals("TestCam", keptExif.getAttribute(android.media.ExifInterface.TAG_MODEL))

        val target = File(application().cacheDir, "test-strip-${System.nanoTime()}.zip")
        val result = runBlocking {
            application().portableExportEngine.export(
                includeTrash = false,
                stripPhotoMetadata = true,
                destination = Uri.fromFile(target),
                nowMillis = 1_756_600_800_000,
            )
        }
        assertTrue(result is PortableExportEngine.ExportResult.Done)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(target.readBytes())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        target.delete()
        val strippedExif = exifOf(entries.getValue(photoEntry))
        assertTrue(strippedExif.getAttribute(android.media.ExifInterface.TAG_MODEL) == null)
        assertTrue(strippedExif.getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE) == null)
        // The picture itself still decodes.
        val decoded = android.graphics.BitmapFactory.decodeByteArray(
            entries.getValue(photoEntry), 0, entries.getValue(photoEntry).size,
        )
        assertTrue(decoded != null && decoded.width == 16)
    }

    @Test
    fun theExportedZipComesBackAsNewMemos() {
        val app = application()
        val target = File(app.cacheDir, "test-import-${System.nanoTime()}.zip")
        val exported = runBlocking {
            app.portableExportEngine.export(
                includeTrash = false,
                destination = Uri.fromFile(target),
                nowMillis = 1_756_600_800_000,
            )
        }
        assertTrue(exported is PortableExportEngine.ExportResult.Done)

        runBlocking { app.database.clearAllTables() }
        val preview = runBlocking { app.portableImportEngine.preview(Uri.fromFile(target)) }
        assertEquals(2, preview?.memos)

        val result = runBlocking { app.portableImportEngine.import(Uri.fromFile(target)) }
        target.delete()
        assertTrue(result is io.github.cragcoffee.memoripple.portableexport.PortableImportEngine.ImportResult.Done)
        result as io.github.cragcoffee.memoripple.portableexport.PortableImportEngine.ImportResult.Done
        assertEquals(2, result.memos)
        assertEquals(1, result.photos)
        assertEquals(0, result.skippedPhotos)

        runBlocking {
            val memos = app.database.backupDao().readMemos()
            val titles = memos.map { it.title }
            assertTrue(titles.contains("旅行の準備"))
            assertTrue(titles.contains("眠るメモ"))
            assertFalse(titles.contains("捨てたメモ"))
            // Everything arrives as a fresh ACTIVE memo.
            assertTrue(memos.all { it.archivedAt == null && it.trashedAt == null })
            val travel = memos.first { it.title == "旅行の準備" }
            // The heading came back as the hash form the editor reads natively.
            assertEquals("# 持ち物\n- [ ] 充電器", travel.body)
            // The tag rode along by name, and the photo went through the real pipeline.
            val relations = app.database.backupDao().readMemoTagRelations()
            val tags = app.database.backupDao().readTags()
            val travelTagNames = relations.filter { it.memoId == travel.id }
                .mapNotNull { rel -> tags.firstOrNull { it.id == rel.tagId }?.name }
            assertEquals(listOf("旅"), travelTagNames)
            val photos = app.database.backupDao().readMemoPhotoAttachments()
                .filter { it.memoId == travel.id }
            assertEquals(1, photos.size)
            assertTrue(app.attachmentBlobStore.blobFile(photos.single().blobSha256).isFile)
        }
    }

    @Test
    fun aLargeWallExportsWhileTheUiThreadStaysFree() {
        val app = application()
        runBlocking {
            app.database.withTransaction {
                repeat(200) { index ->
                    app.database.memoDao().insert(
                        MemoEntity(
                            title = "量産メモ$index",
                            body = "本文$index。\n".repeat(20),
                            createdAt = 1,
                            updatedAt = 100L + index,
                        ),
                    )
                }
            }
        }
        val target = File(app.cacheDir, "test-large-${System.nanoTime()}.zip")
        var result: PortableExportEngine.ExportResult? = null
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            result = app.portableExportEngine.export(
                includeTrash = false,
                destination = Uri.fromFile(target),
                nowMillis = 1_756_600_800_000,
            )
        }
        // While the export streams, the UI thread keeps answering — these queries each
        // round-trip through the main thread and would time out if it were held.
        var probes = 0
        while (job.isActive && probes < 200) {
            composeRule.onAllNodesWithTag("settings_list").fetchSemanticsNodes()
            probes += 1
        }
        runBlocking { kotlinx.coroutines.withTimeout(120_000) { job.join() } }
        assertTrue(result is PortableExportEngine.ExportResult.Done)
        assertTrue(target.length() > 0)
        target.delete()
    }

    @Test
    fun theArchiveAlsoReadsInABrowser() {
        val entries = exportToFile(includeTrash = false)
        val index = entries.getValue("MemoRipple-Export/index.html").toString(Charsets.UTF_8)
        assertTrue(index.contains("<title>MemoRipple 書き出し</title>"))
        // Every HTML link resolves to an entry that exists.
        Regex("""href="([^"]+)"""").findAll(index).forEach { match ->
            assertTrue(entries.keys.contains("MemoRipple-Export/" + match.groupValues[1]))
        }
        val memoHtml = entries.keys.single {
            it.matches(Regex("MemoRipple-Export/memos/active/.*/memo\\.html"))
        }
        val html = entries.getValue(memoHtml).toString(Charsets.UTF_8)
        assertTrue(html.contains("<h2>持ち物</h2>"))
        assertTrue(html.contains("☐ 充電器"))
        assertTrue(html.contains("photos/photo-01.jpg"))
        assertTrue(html.contains("もう一度確認する"))
        // Escaping holds: nothing rendered raw.
        assertFalse(html.contains("<script"))
    }

    @Test
    fun thePdfDrawsEveryRecordIntoRealPages() {
        val app = application()
        val target = File(app.cacheDir, "test-pdf-${System.nanoTime()}.pdf")
        val result = runBlocking {
            app.portableExportEngine.exportPdf(
                includeTrash = false,
                destination = Uri.fromFile(target),
                nowMillis = 1_756_600_800_000,
            )
        }
        assertTrue(result is PortableExportEngine.ExportResult.Done)
        assertEquals("%PDF", target.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII))
        android.os.ParcelFileDescriptor.open(
            target,
            android.os.ParcelFileDescriptor.MODE_READ_ONLY,
        ).use { descriptor ->
            android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                assertTrue(renderer.pageCount >= 1)
                renderer.openPage(0).use { page ->
                    assertTrue(page.width > 0 && page.height > 0)
                }
            }
        }
        target.delete()
    }

    @Test
    fun theAutomaticExportRunsOnItsScheduleAndOnlyThen() {
        val app = application()
        val produced = mutableListOf<File>()
        val exporter = io.github.cragcoffee.memoripple.portableexport.PortableAutoExporter(
            engine = app.portableExportEngine,
            settingsRepository = app.settingsRepository,
            contentResolver = app.contentResolver,
            nowMillis = { 1_756_600_800_000 },
            createDocument = { _, name ->
                val file = File(app.cacheDir, "auto-$name")
                produced.add(file)
                Uri.fromFile(file)
            },
        )
        runBlocking {
            app.settingsRepository.setPortableAutoExportLastRun(0, "")
            app.settingsRepository.setPortableAutoExportEnabled(false)
            // Off: nothing runs.
            exporter.runIfDue()
            assertTrue(produced.isEmpty())
            // On, folder granted, never run: due at once.
            app.settingsRepository.setPortableAutoExportEnabled(true)
            app.settingsRepository.setPortableAutoExportTreeUri("content://fake/tree")
            app.settingsRepository.setPortableAutoExportIntervalDays(1)
            exporter.runIfDue()
            assertEquals(1, produced.size)
            assertTrue(produced.single().length() > 0)
            val status = app.settingsRepository.portableAutoExportLastResult.first()
            assertTrue(status.contains("書き出しました"))
            // The same launch again: not due, no second file.
            exporter.runIfDue()
            assertEquals(1, produced.size)
            // Clean the device-local switches so later tests start quiet.
            app.settingsRepository.setPortableAutoExportEnabled(false)
            app.settingsRepository.setPortableAutoExportTreeUri("")
            produced.forEach { it.delete() }
        }
    }

    @Test
    fun anEmptyDatabaseStillExportsAReadableShell() {
        runBlocking { application().database.clearAllTables() }
        val entries = exportToFile(includeTrash = false)
        assertEquals(
            setOf(
                "MemoRipple-Export/README.md",
                "MemoRipple-Export/INDEX.md",
                "MemoRipple-Export/index.html",
            ),
            entries.keys,
        )
    }
    @Test
    fun oneMemoBecomesItsOwnSmallPdf() {
        val memoId = runBlocking {
            application().database.memoDao().insert(
                MemoEntity(
                    title = "ひとつだけ", body = "この一枚のための本文。",
                    createdAt = 3, updatedAt = 4,
                ),
            )
        }
        val target = File(application().cacheDir, "test-memo-${System.nanoTime()}.pdf")
        val result = runBlocking {
            application().portableExportEngine.exportMemoPdf(
                memoId = memoId,
                destination = Uri.fromFile(target),
                nowMillis = 1_756_600_800_000,
            )
        }
        assertTrue(result is PortableExportEngine.ExportResult.Done)
        val bytes = target.readBytes()
        assertTrue(bytes.size > 4)
        assertEquals("%PDF", String(bytes, 0, 4, Charsets.US_ASCII))

        // A record nothing holds draws nothing.
        val missing = runBlocking {
            application().portableExportEngine.exportMemoPdf(
                memoId = 987_654L,
                destination = Uri.fromFile(File(application().cacheDir, "none.pdf")),
                nowMillis = 1_756_600_800_000,
            )
        }
        assertTrue(missing is PortableExportEngine.ExportResult.Failed)
    }
}
