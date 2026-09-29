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
 * Room 26 → 27 (docs/MEMO_CONTENT_BLOCKS.md §11, human-approved 2026-09-25): every journal entry
 * gets its body as one text, then its photos in their order (they sat under the words), then an
 * empty text to write on after them — how it looked — and no existing row changes. Every state
 * (DRAFT, FINALIZED, CORRECTING, LOCKED) migrates alike. The migration alone, on a real file.
 */
class DiaryContentMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "diary-content-migration-test.db"

    @Before fun deleteDatabase() { context.deleteDatabase(databaseName) }
    @After fun cleanUp() { context.deleteDatabase(databaseName) }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<List<String?>> = db.query(sql).use { c ->
        generateSequence { if (c.moveToNext()) (0 until c.columnCount).map { i -> if (c.isNull(i)) null else c.getString(i) } else null }.toList()
    }

    @Test
    fun migrationTo27PutsEachEntrysWordsAboveItsPhotosAndChangesNothingElse() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName).callback(
                object : SupportSQLiteOpenHelper.Callback(26) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE `diary_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `diaryDateEpochDay` INTEGER NOT NULL, " +
                                "`body` TEXT NOT NULL, `state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                                "`finalizedAt` INTEGER, `correctionStartedAt` INTEGER, `lockedAt` INTEGER)",
                        )
                        db.execSQL(
                            "CREATE TABLE `diary_photo_attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `diaryEntryId` INTEGER NOT NULL, " +
                                "`blobSha256` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                        )
                        db.execSQL("INSERT INTO diary_entries VALUES (1, 20000, '朝は晴れ', 'DRAFT', 1, 2, NULL, NULL, NULL)")
                        db.execSQL("INSERT INTO diary_entries VALUES (2, 20000, '同じ日の二つ目', 'FINALIZED', 3, 4, 4, NULL, NULL)")
                        db.execSQL("INSERT INTO diary_entries VALUES (3, 19000, '昔の日', 'LOCKED', 1, 2, 2, NULL, 2)")
                        db.execSQL("INSERT INTO diary_entries VALUES (4, 19500, '直している日', 'CORRECTING', 1, 2, 2, 2, NULL)")
                        db.execSQL("INSERT INTO diary_entries VALUES (5, 19600, '', 'DRAFT', 1, 2, NULL, NULL, NULL)")
                        // Out of id order on purpose: the photos keep their sortOrder.
                        db.execSQL("INSERT INTO diary_photo_attachments VALUES (21, 1, 'p2', 1, 5)")
                        db.execSQL("INSERT INTO diary_photo_attachments VALUES (20, 1, 'p1', 0, 5)")
                        db.execSQL("INSERT INTO diary_photo_attachments VALUES (30, 3, 'p3', 0, 5)")
                        db.execSQL("INSERT INTO diary_photo_attachments VALUES (40, 5, 'p4', 0, 5)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            ).build(),
        )
        helper.writableDatabase
        val entriesBefore = rows(helper.writableDatabase, "SELECT * FROM diary_entries ORDER BY id")
        val photosBefore = rows(helper.writableDatabase, "SELECT * FROM diary_photo_attachments ORDER BY id")
        helper.close()
        val migrated = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName).callback(
                object : SupportSQLiteOpenHelper.Callback(27) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = AppDatabase.MIGRATION_26_27.migrate(db)
                },
            ).build(),
        )
        val db = migrated.writableDatabase
        assertEquals(27, db.version)
        assertEquals("no entry row changed — state and times included", entriesBefore, rows(db, "SELECT * FROM diary_entries ORDER BY id"))
        assertEquals("no photo row changed", photosBefore, rows(db, "SELECT * FROM diary_photo_attachments ORDER BY id"))
        assertEquals(
            listOf(
                listOf("1", "0", "text", "朝は晴れ", null),
                listOf("1", "1", "photo", null, "20"),
                listOf("1", "2", "photo", null, "21"),
                listOf("1", "3", "text", "", null),
                listOf("2", "0", "text", "同じ日の二つ目", null),
                listOf("3", "0", "text", "昔の日", null),
                listOf("3", "1", "photo", null, "30"),
                listOf("3", "2", "text", "", null),
                listOf("4", "0", "text", "直している日", null),
                listOf("5", "0", "text", "", null),
                listOf("5", "1", "photo", null, "40"),
                listOf("5", "2", "text", "", null),
            ),
            rows(db, "SELECT diaryEntryId, position, type, text, photoAttachmentId FROM diary_content_blocks ORDER BY diaryEntryId, position"),
        )
        migrated.close()
    }
}
