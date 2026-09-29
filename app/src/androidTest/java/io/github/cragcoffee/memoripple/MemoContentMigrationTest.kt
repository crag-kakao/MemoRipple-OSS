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
 * Room 25 → 26 (docs/MEMO_CONTENT_BLOCKS.md, human-approved 2026-09-24): every memo gets its photos
 * first, in their order, then its body as one text — how it looked — and no existing row changes.
 * An outline gets no blocks. The migration alone, on a real file.
 */
class MemoContentMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "memo-content-migration-test.db"

    @Before fun deleteDatabase() { context.deleteDatabase(databaseName) }
    @After fun cleanUp() { context.deleteDatabase(databaseName) }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<List<String?>> = db.query(sql).use { c ->
        generateSequence { if (c.moveToNext()) (0 until c.columnCount).map { i -> if (c.isNull(i)) null else c.getString(i) } else null }.toList()
    }

    @Test
    fun migrationTo26PutsEachMemosPhotosAboveItsBodyAndChangesNothingElse() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName).callback(
                object : SupportSQLiteOpenHelper.Callback(25) {
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
                        db.execSQL("INSERT INTO memos VALUES (1, '公園', 'ABC', 1, 2, 0, 0, NULL, NULL, NULL, NULL, 0, 'memo', NULL, 0)")
                        db.execSQL("INSERT INTO memos VALUES (2, '写真なし', '本文だけ', 1, 2, 0, 0, NULL, NULL, NULL, NULL, 0, 'memo', NULL, 0)")
                        db.execSQL("INSERT INTO memos VALUES (3, '週次', '- a', 1, 2, 0, 0, NULL, NULL, NULL, NULL, 0, 'outline', NULL, 0)")
                        db.execSQL("INSERT INTO memos VALUES (4, 'ゴミ箱', '捨てた', 1, 2, 0, 0, NULL, 9, NULL, NULL, 0, 'memo', NULL, 0)")
                        // Out of id order on purpose: the photos keep their sortOrder.
                        db.execSQL("INSERT INTO memo_photo_attachments VALUES (21, 1, 'p2', 1, 5)")
                        db.execSQL("INSERT INTO memo_photo_attachments VALUES (20, 1, 'p1', 0, 5)")
                        db.execSQL("INSERT INTO memo_photo_attachments VALUES (30, 3, 'p3', 0, 5)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            ).build(),
        )
        helper.writableDatabase
        val memosBefore = rows(helper.writableDatabase, "SELECT * FROM memos ORDER BY id")
        val photosBefore = rows(helper.writableDatabase, "SELECT * FROM memo_photo_attachments ORDER BY id")
        helper.close()
        val migrated = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName).callback(
                object : SupportSQLiteOpenHelper.Callback(26) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = AppDatabase.MIGRATION_25_26.migrate(db)
                },
            ).build(),
        )
        val db = migrated.writableDatabase
        assertEquals(26, db.version)
        assertEquals("no memo row changed", memosBefore, rows(db, "SELECT * FROM memos ORDER BY id"))
        assertEquals("no photo row changed", photosBefore, rows(db, "SELECT * FROM memo_photo_attachments ORDER BY id"))
        assertEquals(
            listOf(
                listOf("1", "0", "photo", null, "20"),
                listOf("1", "1", "photo", null, "21"),
                listOf("1", "2", "text", "ABC", null),
                listOf("2", "0", "text", "本文だけ", null),
                listOf("4", "0", "text", "捨てた", null),
            ),
            rows(db, "SELECT memoId, position, type, text, photoAttachmentId FROM memo_content_blocks ORDER BY memoId, position"),
        )
        migrated.close()
    }
}
