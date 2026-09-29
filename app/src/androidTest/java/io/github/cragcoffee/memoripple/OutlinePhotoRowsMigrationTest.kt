package io.github.cragcoffee.memoripple

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Room 28 → 29 (docs/OUTLINE_PHOTO_ROWS.md, human-approved 2026-09-25): `outline_rows` gains `kind`
 * and `photoAttachmentId`. Every line keeps its id and its text byte for byte and becomes a `text`
 * row; an outline's photos (the strip above the lines) become `photo` rows at the top, depth 0, in
 * their order, with ids past the outline's highest; an outline without photos is unchanged; a memo's
 * photos get no row; no memo or photo row changes. The migration alone, on a real file.
 */
class OutlinePhotoRowsMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "outline-photo-rows-migration-test.db"

    @Before fun deleteDatabase() { context.deleteDatabase(databaseName) }
    @After fun cleanUp() { context.deleteDatabase(databaseName) }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<List<String?>> = db.query(sql).use { c ->
        generateSequence { if (c.moveToNext()) (0 until c.columnCount).map { i -> if (c.isNull(i)) null else c.getString(i) } else null }.toList()
    }

    @Test
    fun migrationTo29PutsEachOutlinesPhotosOnTopAndChangesNoLine() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName).callback(
                object : SupportSQLiteOpenHelper.Callback(28) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE `memos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, " +
                                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL, " +
                                "`archivedAt` INTEGER, `trashedAt` INTEGER, `noteId` INTEGER, `chapterId` INTEGER, `episodeOrder` INTEGER NOT NULL, " +
                                "`kind` TEXT NOT NULL, `folderId` INTEGER, `sortIndex` INTEGER NOT NULL)",
                        )
                        db.execSQL(
                            "CREATE TABLE `memo_photo_attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `memoId` INTEGER NOT NULL, " +
                                "`blobSha256` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                        )
                        db.execSQL(
                            "CREATE TABLE `outline_rows` (`memoId` INTEGER NOT NULL, `rowId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                                "`text` TEXT NOT NULL, PRIMARY KEY(`memoId`, `rowId`), " +
                                "FOREIGN KEY(`memoId`) REFERENCES `memos`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                        )
                        db.execSQL("CREATE INDEX `index_outline_rows_memoId` ON `outline_rows` (`memoId`)")
                        // 1: an outline with two photos and lines whose ids are not 1..n (a life of edits).
                        db.execSQL("INSERT INTO memos VALUES (1, '旅行', '- 旅行計画\n  - [ ] 寺院を回る\n\n- 帰る', 1, 2, 0, 0, NULL, NULL, NULL, NULL, 0, 'outline', NULL, 0)")
                        listOf(21 to "- 旅行計画", 4 to "  - [ ] 寺院を回る", 30 to "", 9 to "- 帰る").forEachIndexed { position, (rowId, text) ->
                            db.execSQL("INSERT INTO outline_rows VALUES (1, ?, ?, ?)", arrayOf<Any>(rowId, position, text))
                        }
                        // Photo 12 was picked first (sortOrder 0), photo 11 second.
                        db.execSQL("INSERT INTO memo_photo_attachments VALUES (11, 1, '${"a".repeat(64)}', 1, 1)")
                        db.execSQL("INSERT INTO memo_photo_attachments VALUES (12, 1, '${"b".repeat(64)}', 0, 1)")
                        // 2: an outline without photos.
                        db.execSQL("INSERT INTO memos VALUES (2, '無写真', '- 一\n- 二', 1, 2, 0, 0, NULL, NULL, NULL, NULL, 0, 'outline', NULL, 0)")
                        db.execSQL("INSERT INTO outline_rows VALUES (2, 1, 0, '- 一')")
                        db.execSQL("INSERT INTO outline_rows VALUES (2, 2, 1, '- 二')")
                        // 3: a memo with a photo — its photos live in its content blocks, never in outline rows.
                        db.execSQL("INSERT INTO memos VALUES (3, 'メモ', '本文', 1, 2, 0, 0, NULL, NULL, NULL, NULL, 0, 'memo', NULL, 0)")
                        db.execSQL("INSERT INTO memo_photo_attachments VALUES (13, 3, '${"c".repeat(64)}', 0, 1)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            ).build(),
        )
        val before = helper.writableDatabase
        val memosBefore = rows(before, "SELECT * FROM memos ORDER BY id")
        val photosBefore = rows(before, "SELECT * FROM memo_photo_attachments ORDER BY id")
        helper.close()
        val migrated = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName).callback(
                object : SupportSQLiteOpenHelper.Callback(29) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = AppDatabase.MIGRATION_28_29.migrate(db)
                },
            ).build(),
        )
        val db = migrated.writableDatabase
        assertEquals(29, db.version)
        assertEquals("no memo changed", memosBefore, rows(db, "SELECT * FROM memos ORDER BY id"))
        assertEquals("no photo changed", photosBefore, rows(db, "SELECT * FROM memo_photo_attachments ORDER BY id"))
        assertEquals(
            listOf(
                listOf("31", "0", "photo", "", "12"),
                listOf("32", "1", "photo", "", "11"),
                listOf("21", "2", "text", "- 旅行計画", null),
                listOf("4", "3", "text", "  - [ ] 寺院を回る", null),
                listOf("30", "4", "text", "", null),
                listOf("9", "5", "text", "- 帰る", null),
            ),
            rows(db, "SELECT rowId, position, kind, text, photoAttachmentId FROM outline_rows WHERE memoId = 1 ORDER BY position"),
        )
        assertEquals(
            "an outline without photos is unchanged",
            listOf(listOf("1", "0", "text", "- 一", null), listOf("2", "1", "text", "- 二", null)),
            rows(db, "SELECT rowId, position, kind, text, photoAttachmentId FROM outline_rows WHERE memoId = 2 ORDER BY position"),
        )
        assertEquals("a memo's photo gets no row", emptyList<List<String?>>(), rows(db, "SELECT * FROM outline_rows WHERE memoId = 3 OR photoAttachmentId = 13"))
        assertEquals("no duplicate id", emptyList<List<String?>>(), rows(db, "SELECT memoId, rowId FROM outline_rows GROUP BY memoId, rowId HAVING COUNT(*) > 1"))
        assertEquals(
            "both indexes are there",
            listOf(listOf("index_outline_rows_memoId"), listOf("index_outline_rows_photoAttachmentId")),
            rows(db, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'outline_rows' AND name LIKE 'index_%' ORDER BY name"),
        )
        migrated.close()
    }
}
