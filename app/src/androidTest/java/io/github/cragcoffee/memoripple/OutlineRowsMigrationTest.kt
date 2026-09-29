package io.github.cragcoffee.memoripple

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.domain.outline.OutlineRows
import io.github.cragcoffee.memoripple.domain.outline.OutlineText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Room 27 → 28 (docs/OUTLINE_STABLE_ROWS.md, human-approved 2026-09-25): every outline's lines become
 * rows with lasting ids 1..n in order, each exactly as written — so the rows' projection is the body
 * byte for byte (indents, markers, task boxes, blank lines, a trailing line break, a CR, an emoji
 * all kept) — and no existing row changes. A memo gets no rows. The migration alone, on a real file.
 */
class OutlineRowsMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "outline-rows-migration-test.db"

    @Before fun deleteDatabase() { context.deleteDatabase(databaseName) }
    @After fun cleanUp() { context.deleteDatabase(databaseName) }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<List<String?>> = db.query(sql).use { c ->
        generateSequence { if (c.moveToNext()) (0 until c.columnCount).map { i -> if (c.isNull(i)) null else c.getString(i) } else null }.toList()
    }

    private val outlines = mapOf(
        1L to "# 旅行計画\n- 京都へ行く\n  - [ ] 寺院を回る\n  - [x] 宿を取る\n\n- 帰る",
        2L to "",
        3L to "- 末尾に改行\n",
        4L to "  \t- 字下げとタブ\r\n■ 見出し 📌\n    ★ 重要",
    )

    @Test
    fun migrationTo28GivesEveryOutlineLineALastingIdAndChangesNoText() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName).callback(
                object : SupportSQLiteOpenHelper.Callback(27) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE `memos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, " +
                                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL, " +
                                "`archivedAt` INTEGER, `trashedAt` INTEGER, `noteId` INTEGER, `chapterId` INTEGER, `episodeOrder` INTEGER NOT NULL, " +
                                "`kind` TEXT NOT NULL, `folderId` INTEGER, `sortIndex` INTEGER NOT NULL)",
                        )
                        outlines.forEach { (id, body) ->
                            db.execSQL(
                                "INSERT INTO memos VALUES (?, 'アウトライン', ?, 1, 2, 0, 0, NULL, NULL, NULL, NULL, 0, 'outline', NULL, 0)",
                                arrayOf<Any>(id, body),
                            )
                        }
                        db.execSQL("INSERT INTO memos VALUES (9, 'メモ', '- メモの行', 1, 2, 0, 0, NULL, NULL, NULL, NULL, 0, 'memo', NULL, 0)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            ).build(),
        )
        val memosBefore = rows(helper.writableDatabase, "SELECT * FROM memos ORDER BY id")
        helper.close()
        val migrated = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName).callback(
                object : SupportSQLiteOpenHelper.Callback(28) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = AppDatabase.MIGRATION_27_28.migrate(db)
                },
            ).build(),
        )
        val db = migrated.writableDatabase
        assertEquals(28, db.version)
        assertEquals("no memo row changed", memosBefore, rows(db, "SELECT * FROM memos ORDER BY id"))
        outlines.forEach { (id, body) ->
            val own = rows(db, "SELECT rowId, position, text FROM outline_rows WHERE memoId = $id ORDER BY position")
            val lines = own.map { OutlineRows.Row(it[0]!!.toInt(), it[2]!!) }
            assertEquals("$id: the projection is the body byte for byte", body, OutlineRows.projection(lines))
            assertEquals("$id: ids 1..n in order", (1..own.size).toList(), lines.map { it.id })
            assertEquals("$id: positions 0..n-1", (0 until own.size).map { it.toString() }, own.map { it[1] })
            assertEquals("$id: the outliner reads the same document", OutlineText.parse(body), OutlineRows.documentOf(lines))
        }
        assertEquals("a memo has no rows", emptyList<List<String?>>(), rows(db, "SELECT * FROM outline_rows WHERE memoId = 9"))
        assertEquals("no duplicate id", emptyList<List<String?>>(), rows(db, "SELECT memoId, rowId FROM outline_rows GROUP BY memoId, rowId HAVING COUNT(*) > 1"))
        migrated.close()
    }
}
