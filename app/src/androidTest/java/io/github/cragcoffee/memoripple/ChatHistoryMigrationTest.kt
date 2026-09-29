package io.github.cragcoffee.memoripple

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 8 RED 53, 54: Room 24 → 25 adds the three chat tables and nothing else — every existing
 * row (memos of both kinds, journal entries, folders) is byte-equivalent afterwards and the chat
 * tables start empty. Same shape as the 23 → 24 test: the migration alone, on a real file.
 */
class ChatHistoryMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "chat-history-migration-test.db"

    @Before fun deleteDatabase() { context.deleteDatabase(databaseName) }
    @After fun cleanUp() { context.deleteDatabase(databaseName) }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<List<String?>> = db.query(sql).use { c ->
        generateSequence { if (c.moveToNext()) (0 until c.columnCount).map { i -> if (c.isNull(i)) null else c.getString(i) } else null }.toList()
    }

    private fun tables(db: SupportSQLiteDatabase): Set<String> = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
    }

    @Test
    fun migrationTo25AddsTheChatTablesAndKeepsEveryRow() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName).callback(
                object : SupportSQLiteOpenHelper.Callback(24) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `memos` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `title` TEXT NOT NULL, `body` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                                `isFavorite` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL,
                                `archivedAt` INTEGER, `trashedAt` INTEGER, `noteId` INTEGER, `chapterId` INTEGER,
                                `episodeOrder` INTEGER NOT NULL, `kind` TEXT NOT NULL, `folderId` INTEGER,
                                `sortIndex` INTEGER NOT NULL
                            )
                            """.trimIndent(),
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memos_createdAt` ON `memos` (`createdAt`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memos_updatedAt` ON `memos` (`updatedAt`)")
                        db.execSQL("INSERT INTO memos VALUES (1, 'MemoRipple開発', '## 進捗', 100, 200, 0, 0, NULL, NULL, NULL, NULL, 0, 'memo', 3, 0)")
                        db.execSQL("INSERT INTO memos VALUES (2, '週次', '- a', 300, 300, 1, 0, NULL, NULL, NULL, NULL, 0, 'outline', NULL, 1)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `diary_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `diaryDateEpochDay` INTEGER NOT NULL, `body` TEXT NOT NULL, `state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `finalizedAt` INTEGER)")
                        db.execSQL("INSERT INTO diary_entries VALUES (7, 20700, '散歩の記録', 'DRAFT', 5, 6, NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `parentFolderId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO folders VALUES (3, '仕事', NULL, 1, 2)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            ).build(),
        )
        helper.writableDatabase; helper.close()
        val migrated = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName).callback(
                object : SupportSQLiteOpenHelper.Callback(25) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = AppDatabase.MIGRATION_24_25.migrate(db)
                },
            ).build(),
        )
        val db = migrated.writableDatabase
        assertEquals(25, db.version)
        val t = tables(db)
        assertTrue("chat tables created: $t", t.containsAll(listOf("chat_conversations", "chat_messages", "chat_result_refs")))
        assertEquals(0, rows(db, "SELECT COUNT(*) FROM chat_conversations").single().single()!!.toInt())
        assertEquals(0, rows(db, "SELECT COUNT(*) FROM chat_messages").single().single()!!.toInt())
        assertEquals(0, rows(db, "SELECT COUNT(*) FROM chat_result_refs").single().single()!!.toInt())
        assertEquals(
            listOf(listOf("1", "MemoRipple開発", "## 進捗", "100", "200", "memo", "3", "0"), listOf("2", "週次", "- a", "300", "300", "outline", null, "1")),
            rows(db, "SELECT id, title, body, createdAt, updatedAt, kind, folderId, sortIndex FROM memos ORDER BY id"),
        )
        assertEquals(listOf(listOf("7", "20700", "散歩の記録", "DRAFT", "5", "6", null)), rows(db, "SELECT * FROM diary_entries"))
        assertEquals(listOf(listOf("3", "仕事", null, "1", "2")), rows(db, "SELECT * FROM folders"))
        // the chat tables reference each other, never a document table
        val fks = rows(db, "PRAGMA foreign_key_list(chat_messages)").map { it[2] } + rows(db, "PRAGMA foreign_key_list(chat_result_refs)").map { it[2] }
        assertEquals(setOf("chat_conversations"), fks.toSet())
        assertTrue(rows(db, "PRAGMA foreign_key_list(chat_conversations)").isEmpty())
        migrated.close()
    }

    @Test
    fun theSharedChainHoldsTheChatStepAndTheAppOpensAtTheLatestVersion() {
        // 29 since the outline photo rows (docs/OUTLINE_PHOTO_ROWS.md, 2026-09-25).
        assertEquals(29, AppDatabase.MIGRATIONS.last().endVersion)
        assertTrue(AppDatabase.MIGRATIONS.any { it.startVersion == 24 && it.endVersion == 25 })
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
        assertEquals(29, app.database.openHelper.readableDatabase.version)
    }
}
