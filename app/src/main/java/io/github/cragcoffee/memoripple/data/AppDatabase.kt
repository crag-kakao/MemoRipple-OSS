package io.github.cragcoffee.memoripple.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MemoEntity::class,
        MemoCommentEntity::class,
        DiaryEntryEntity::class,
        FutureDiaryCommentEntity::class,
        TagEntity::class,
        MemoTagCrossRef::class,
        AttachmentBlobEntity::class,
        MemoPhotoAttachmentEntity::class,
        DiaryPhotoAttachmentEntity::class,
        NoteEntity::class,
        NoteChapterEntity::class,
        FolderEntity::class,
        ChatConversationEntity::class,
        ChatMessageEntity::class,
        ChatResultRefEntity::class,
        MemoContentBlockEntity::class,
        DiaryContentBlockEntity::class,
        OutlineRowEntity::class,
    ],
    version = 29,
    exportSchema = false,
)
@TypeConverters(DiaryConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memoDao(): MemoDao
    abstract fun memoCommentDao(): MemoCommentDao
    abstract fun diaryDao(): DiaryDao
    abstract fun futureDiaryCommentDao(): FutureDiaryCommentDao
    abstract fun backupDao(): BackupDao
    abstract fun tagDao(): TagDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao
    abstract fun chatDao(): ChatDao
    abstract fun memoContentBlockDao(): MemoContentBlockDao
    abstract fun diaryContentBlockDao(): DiaryContentBlockDao
    abstract fun outlineRowDao(): OutlineRowDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memo_comments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `memoId` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`memoId`) REFERENCES `memos`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memo_comments_memoId` " +
                        "ON `memo_comments` (`memoId`)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `memo_comments` " +
                        "ADD COLUMN `playbackOrder` INTEGER NOT NULL DEFAULT 0",
                )
                db.query(
                    """
                    SELECT `id`, `memoId`
                    FROM `memo_comments`
                    ORDER BY `memoId` ASC, `createdAt` ASC, `id` ASC
                    """.trimIndent(),
                ).use { cursor ->
                    var currentMemoId: Long? = null
                    var nextOrder = 0
                    while (cursor.moveToNext()) {
                        val commentId = cursor.getLong(0)
                        val memoId = cursor.getLong(1)
                        if (memoId != currentMemoId) {
                            currentMemoId = memoId
                            nextOrder = 0
                        }
                        db.execSQL(
                            "UPDATE `memo_comments` SET `playbackOrder` = ? WHERE `id` = ?",
                            arrayOf<Any>(nextOrder, commentId),
                        )
                        nextOrder += 1
                    }
                }
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `diary_entries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `diaryDateEpochDay` INTEGER NOT NULL,
                        `body` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `finalizedAt` INTEGER,
                        `correctionStartedAt` INTEGER,
                        `lockedAt` INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_diary_entries_diaryDateEpochDay` " +
                        "ON `diary_entries` (`diaryDateEpochDay`)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `future_diary_comments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `diaryEntryId` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `sealedAt` INTEGER NOT NULL,
                        `revealAt` INTEGER NOT NULL,
                        `deliveredAt` INTEGER,
                        `revealedAt` INTEGER,
                        FOREIGN KEY(`diaryEntryId`) REFERENCES `diary_entries`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_future_diary_comments_diaryEntryId` " +
                        "ON `future_diary_comments` (`diaryEntryId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_future_diary_comments_revealAt` " +
                        "ON `future_diary_comments` (`revealAt`)",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `future_diary_comments` " +
                        "ADD COLUMN `firstPresentedAt` INTEGER",
                )
                db.execSQL(
                    """
                    UPDATE `future_diary_comments`
                    SET `firstPresentedAt` = `revealedAt`
                    WHERE `revealedAt` IS NOT NULL
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `memos` " +
                        "ADD COLUMN `isFavorite` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `memos` " +
                        "ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tags` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `normalizedName` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_normalizedName` " +
                        "ON `tags` (`normalizedName`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memo_tag_cross_refs` (
                        `memoId` INTEGER NOT NULL,
                        `tagId` INTEGER NOT NULL,
                        PRIMARY KEY(`memoId`, `tagId`),
                        FOREIGN KEY(`memoId`) REFERENCES `memos`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memo_tag_cross_refs_tagId` " +
                        "ON `memo_tag_cross_refs` (`tagId`)",
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memos` ADD COLUMN `archivedAt` INTEGER")
                db.execSQL("ALTER TABLE `memos` ADD COLUMN `trashedAt` INTEGER")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `memo_comments` " +
                        "ADD COLUMN `appearanceColor` TEXT NOT NULL DEFAULT 'default'",
                )
                db.execSQL(
                    "ALTER TABLE `memo_comments` " +
                        "ADD COLUMN `appearanceSize` TEXT NOT NULL DEFAULT 'standard'",
                )
                db.execSQL(
                    "ALTER TABLE `memo_comments` " +
                        "ADD COLUMN `appearanceEmphasis` TEXT NOT NULL DEFAULT 'normal'",
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `memo_comments` " +
                        "ADD COLUMN `motionSpeed` TEXT NOT NULL DEFAULT 'standard'",
                )
                db.execSQL(
                    "ALTER TABLE `memo_comments` " +
                        "ADD COLUMN `motionPlacement` TEXT NOT NULL DEFAULT 'auto'",
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `memo_comments` " +
                        "ADD COLUMN `motionMode` TEXT NOT NULL DEFAULT 'flow'",
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `future_diary_comments` " +
                        "ADD COLUMN `appearanceColor` TEXT NOT NULL DEFAULT 'default'",
                )
                db.execSQL(
                    "ALTER TABLE `future_diary_comments` " +
                        "ADD COLUMN `appearanceSize` TEXT NOT NULL DEFAULT 'standard'",
                )
                db.execSQL(
                    "ALTER TABLE `future_diary_comments` " +
                        "ADD COLUMN `appearanceEmphasis` TEXT NOT NULL DEFAULT 'normal'",
                )
                db.execSQL(
                    "ALTER TABLE `future_diary_comments` " +
                        "ADD COLUMN `motionMode` TEXT NOT NULL DEFAULT 'flow'",
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `memo_comments` " +
                        "ADD COLUMN `flowDirection` TEXT NOT NULL DEFAULT 'rtl'",
                )
                db.execSQL(
                    "ALTER TABLE `memo_comments` " +
                        "ADD COLUMN `flowEffect` TEXT NOT NULL DEFAULT 'straight'",
                )
            }
        }

        /**
         * フォルダ: a place documents are kept, nested or at the root. A new table and one
         * nullable column on `memos` (null = the root — every existing document); no foreign
         * key, as with `memos.noteId`, because `memos` is never recreated and the repository
         * keeps the references sound inside its transactions. Nothing about any row changes.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `folders` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `parentFolderId` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_folders_parentFolderId` " +
                        "ON `folders` (`parentFolderId`)",
                )
                db.execSQL("ALTER TABLE `memos` ADD COLUMN `folderId` INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memos_folderId` ON `memos` (`folderId`)")
            }
        }

        /**
         * 並べた順: one integer per memo and per note, the place the hand gave it on its page.
         * Every existing one is 0 — "not placed yet", shown newest first until it is moved.
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memos` ADD COLUMN `sortIndex` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `sortIndex` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 日記の複数化 (HANDOFF §16.18): a day may hold several journal entries, so the unique index on
         * the day goes and a plain index on (day, createdAt) takes its place for the day's list. No
         * column is added, dropped or rewritten; every row keeps its state and timestamps. An older
         * build opening this file would only see an index it does not expect.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_diary_entries_diaryDateEpochDay`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_diary_entries_diaryDateEpochDay_createdAt` " +
                        "ON `diary_entries` (`diaryDateEpochDay`, `createdAt`)",
                )
            }
        }

        /**
         * カレンダー (HANDOFF §16.18 / 16.22): the time axis asks memos for a month by createdAt and by
         * updatedAt, so both get an index. Nothing else changes; no row is touched.
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memos_createdAt` ON `memos` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memos_updatedAt` ON `memos` (`updatedAt`)")
            }
        }

        /**
         * Conversation history (docs/AI_CONVERSATION_HISTORY.md, Phase 8): three new tables for the
         * チャット AI mode's transcripts and safe context — created empty, cascading among themselves,
         * with no key into any document table. Nothing existing is touched; no row changes.
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_conversations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `anchorKind` TEXT, `anchorId` INTEGER)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversationId` INTEGER NOT NULL, " +
                        "`role` TEXT NOT NULL, `kind` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`conversationId`) REFERENCES `chat_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_conversationId` ON `chat_messages` (`conversationId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_result_refs` (`conversationId` INTEGER NOT NULL, `ordinal` INTEGER NOT NULL, " +
                        "`documentKind` TEXT NOT NULL, `documentId` INTEGER NOT NULL, `title` TEXT NOT NULL, PRIMARY KEY(`conversationId`, `ordinal`), " +
                        "FOREIGN KEY(`conversationId`) REFERENCES `chat_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }

        /**
         * 本文のブロック化 (docs/MEMO_CONTENT_BLOCKS.md, human-approved 2026-09-24): a memo's content
         * becomes an ordered list of text and photo blocks. One new table; every memo (kind
         * `memo`) gets its photos first, in their order, then its body as one text — exactly how
         * it looked, and the body's projection is the body unchanged. No existing row is changed.
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memo_content_blocks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`memoId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `type` TEXT NOT NULL, `text` TEXT, " +
                        "`photoAttachmentId` INTEGER, " +
                        "FOREIGN KEY(`memoId`) REFERENCES `memos`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`photoAttachmentId`) REFERENCES `memo_photo_attachments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memo_content_blocks_memoId` ON `memo_content_blocks` (`memoId`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_memo_content_blocks_photoAttachmentId` " +
                        "ON `memo_content_blocks` (`photoAttachmentId`)",
                )
                // Photos first, in the order they had (their sortOrder is 0..n-1 per memo).
                db.execSQL(
                    "INSERT INTO `memo_content_blocks` (`memoId`, `position`, `type`, `text`, `photoAttachmentId`) " +
                        "SELECT p.`memoId`, (SELECT COUNT(*) FROM `memo_photo_attachments` q WHERE q.`memoId` = p.`memoId` " +
                        "AND (q.`sortOrder` < p.`sortOrder` OR (q.`sortOrder` = p.`sortOrder` AND q.`id` < p.`id`))), " +
                        "'photo', NULL, p.`id` FROM `memo_photo_attachments` p JOIN `memos` m ON m.`id` = p.`memoId` " +
                        "WHERE m.`kind` = 'memo'",
                )
                // Then the body, whole, as one text.
                db.execSQL(
                    "INSERT INTO `memo_content_blocks` (`memoId`, `position`, `type`, `text`, `photoAttachmentId`) " +
                        "SELECT m.`id`, (SELECT COUNT(*) FROM `memo_photo_attachments` p WHERE p.`memoId` = m.`id`), " +
                        "'text', m.`body`, NULL FROM `memos` m WHERE m.`kind` = 'memo'",
                )
            }
        }

        /**
         * アウトラインの写真の行 (docs/OUTLINE_PHOTO_ROWS.md, human-approved 2026-09-25, Stage 2): a
         * row is a line of words or a photo row of its own. `outline_rows` is rebuilt with `kind`
         * and `photoAttachmentId` (a foreign key needs a new table in SQLite); every line is copied
         * with its id, text and order, and an outline's photos — shown above its lines until now —
         * become photo rows at the top, in their order, with ids past the highest. No text, id or
         * indent changes; memos and journals are not touched.
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `outline_rows_new` (`memoId` INTEGER NOT NULL, `rowId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, `text` TEXT NOT NULL, `photoAttachmentId` INTEGER, PRIMARY KEY(`memoId`, `rowId`), " +
                        "FOREIGN KEY(`memoId`) REFERENCES `memos`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`photoAttachmentId`) REFERENCES `memo_photo_attachments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                // The lines, each moved down by the number of its outline's photos.
                db.execSQL(
                    "INSERT INTO `outline_rows_new` (`memoId`, `rowId`, `position`, `kind`, `text`, `photoAttachmentId`) " +
                        "SELECT r.`memoId`, r.`rowId`, r.`position` + (SELECT COUNT(*) FROM `memo_photo_attachments` p WHERE p.`memoId` = r.`memoId`), " +
                        "'text', r.`text`, NULL FROM `outline_rows` r",
                )
                // The photos, at the top in their order, with ids past the outline's highest.
                db.execSQL(
                    "INSERT INTO `outline_rows_new` (`memoId`, `rowId`, `position`, `kind`, `text`, `photoAttachmentId`) " +
                        "SELECT p.`memoId`, " +
                        "COALESCE((SELECT MAX(r.`rowId`) FROM `outline_rows` r WHERE r.`memoId` = p.`memoId`), 0) + 1 + " +
                        "(SELECT COUNT(*) FROM `memo_photo_attachments` q WHERE q.`memoId` = p.`memoId` " +
                        "AND (q.`sortOrder` < p.`sortOrder` OR (q.`sortOrder` = p.`sortOrder` AND q.`id` < p.`id`))), " +
                        "(SELECT COUNT(*) FROM `memo_photo_attachments` q WHERE q.`memoId` = p.`memoId` " +
                        "AND (q.`sortOrder` < p.`sortOrder` OR (q.`sortOrder` = p.`sortOrder` AND q.`id` < p.`id`))), " +
                        "'photo', '', p.`id` FROM `memo_photo_attachments` p JOIN `memos` m ON m.`id` = p.`memoId` WHERE m.`kind` = 'outline'",
                )
                db.execSQL("DROP TABLE `outline_rows`")
                db.execSQL("ALTER TABLE `outline_rows_new` RENAME TO `outline_rows`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outline_rows_memoId` ON `outline_rows` (`memoId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_outline_rows_photoAttachmentId` ON `outline_rows` (`photoAttachmentId`)")
            }
        }

        /**
         * アウトラインの行 (docs/OUTLINE_STABLE_ROWS.md, human-approved 2026-09-25, Stage 1): every
         * line of every outline becomes a row with a lasting id — its lines in order, ids 1..n,
         * each line's text exactly as written (the body cut on `\n` alone, as the outliner reads
         * it), so the rows' projection is the body byte for byte. No existing row is changed; memos
         * and journals are not touched.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `outline_rows` (`memoId` INTEGER NOT NULL, `rowId` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, `text` TEXT NOT NULL, PRIMARY KEY(`memoId`, `rowId`), " +
                        "FOREIGN KEY(`memoId`) REFERENCES `memos`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outline_rows_memoId` ON `outline_rows` (`memoId`)")
                db.query("SELECT `id`, `body` FROM `memos` WHERE `kind` = 'outline' ORDER BY `id`").use { cursor ->
                    while (cursor.moveToNext()) {
                        val memoId = cursor.getLong(0)
                        cursor.getString(1).orEmpty().split('\n').forEachIndexed { index, line ->
                            db.execSQL(
                                "INSERT INTO `outline_rows` (`memoId`, `rowId`, `position`, `text`) VALUES (?, ?, ?, ?)",
                                arrayOf<Any>(memoId, index + 1, index, line),
                            )
                        }
                    }
                }
            }
        }

        /**
         * 日記の本文のブロック化 (docs/MEMO_CONTENT_BLOCKS.md §11, human-approved 2026-09-25): a
         * journal entry's content becomes an ordered list of text and photo blocks, as a memo's
         * did in 25→26. One new table; every entry gets its body as one text, then its photos in
         * their order (a journal's photos sat under its words), then an empty text to write on
         * after them — exactly how it looked, and the projection is the body unchanged. No
         * existing row is changed.
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `diary_content_blocks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`diaryEntryId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `type` TEXT NOT NULL, `text` TEXT, " +
                        "`photoAttachmentId` INTEGER, " +
                        "FOREIGN KEY(`diaryEntryId`) REFERENCES `diary_entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`photoAttachmentId`) REFERENCES `diary_photo_attachments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_diary_content_blocks_diaryEntryId` ON `diary_content_blocks` (`diaryEntryId`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_diary_content_blocks_photoAttachmentId` " +
                        "ON `diary_content_blocks` (`photoAttachmentId`)",
                )
                // The body, whole, as one text.
                db.execSQL(
                    "INSERT INTO `diary_content_blocks` (`diaryEntryId`, `position`, `type`, `text`, `photoAttachmentId`) " +
                        "SELECT e.`id`, 0, 'text', e.`body`, NULL FROM `diary_entries` e",
                )
                // Then the photos, in the order they had.
                db.execSQL(
                    "INSERT INTO `diary_content_blocks` (`diaryEntryId`, `position`, `type`, `text`, `photoAttachmentId`) " +
                        "SELECT p.`diaryEntryId`, 1 + (SELECT COUNT(*) FROM `diary_photo_attachments` q " +
                        "WHERE q.`diaryEntryId` = p.`diaryEntryId` " +
                        "AND (q.`sortOrder` < p.`sortOrder` OR (q.`sortOrder` = p.`sortOrder` AND q.`id` < p.`id`))), " +
                        "'photo', NULL, p.`id` FROM `diary_photo_attachments` p",
                )
                // A place to write after the last photo.
                db.execSQL(
                    "INSERT INTO `diary_content_blocks` (`diaryEntryId`, `position`, `type`, `text`, `photoAttachmentId`) " +
                        "SELECT e.`id`, 1 + (SELECT COUNT(*) FROM `diary_photo_attachments` p WHERE p.`diaryEntryId` = e.`id`), " +
                        "'text', '', NULL FROM `diary_entries` e " +
                        "WHERE EXISTS (SELECT 1 FROM `diary_photo_attachments` p WHERE p.`diaryEntryId` = e.`id`)",
                )
            }
        }

        /**
         * 文書種別: a memo is a memo or an outline, decided when it is made. Every memo written
         * before now is a memo — the body is never read to decide, so a memo that happens to
         * be written as an outline stays exactly where it was. One added column with a
         * default and its index; `memos` is not recreated (ARCHITECTURE: it is the parent of
         * comments, tags and photos).
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memos` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'memo'")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memos_kind` ON `memos` (`kind`)")
            }
        }

        /** コメントリンク: a comment may carry a number the body's [R1] markers point at.
         * Null for every comment written before now — nothing links until asked to. */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memo_comments` ADD COLUMN `linkNo` INTEGER")
            }
        }

        /** A note may say a second line about itself. Empty for every note written before now. */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `notes` ADD COLUMN `subtitle` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * A note may carry a picture on its cover.
         *
         * The column names a blob by its own hash, the way every other attachment in this app is
         * named, so a picture already stored for a memo costs nothing to reuse. No foreign key:
         * SQLite cannot add one to a table that already exists. What keeps the file alive is that
         * the collector in [AttachmentDao] counts this column as a reference.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `coverBlobSha256` TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notes_coverBlobSha256` " +
                        "ON `notes` (`coverBlobSha256`)",
                )
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `notes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `coverColor` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `note_chapters` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `noteId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_note_chapters_noteId` " +
                        "ON `note_chapters` (`noteId`)",
                )
                // Every existing memo keeps standing alone: the new columns are empty for all of
                // them, which is exactly what "not part of a note" means.
                db.execSQL("ALTER TABLE `memos` ADD COLUMN `noteId` INTEGER")
                db.execSQL("ALTER TABLE `memos` ADD COLUMN `chapterId` INTEGER")
                db.execSQL(
                    "ALTER TABLE `memos` ADD COLUMN `episodeOrder` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memos_noteId` ON `memos` (`noteId`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memos_chapterId` ON `memos` (`chapterId`)",
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `attachment_blobs` (
                        `sha256` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `widthPx` INTEGER NOT NULL,
                        `heightPx` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sha256`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memo_photo_attachments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `memoId` INTEGER NOT NULL,
                        `blobSha256` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`memoId`) REFERENCES `memos`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`blobSha256`) REFERENCES `attachment_blobs`(`sha256`)
                            ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memo_photo_attachments_memoId` " +
                        "ON `memo_photo_attachments` (`memoId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memo_photo_attachments_blobSha256` " +
                        "ON `memo_photo_attachments` (`blobSha256`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_memo_photo_attachments_memoId_blobSha256` " +
                        "ON `memo_photo_attachments` (`memoId`, `blobSha256`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `diary_photo_attachments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `diaryEntryId` INTEGER NOT NULL,
                        `blobSha256` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`diaryEntryId`) REFERENCES `diary_entries`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`blobSha256`) REFERENCES `attachment_blobs`(`sha256`)
                            ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_diary_photo_attachments_diaryEntryId` " +
                        "ON `diary_photo_attachments` (`diaryEntryId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_diary_photo_attachments_blobSha256` " +
                        "ON `diary_photo_attachments` (`blobSha256`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_diary_photo_attachments_diaryEntryId_blobSha256` " +
                        "ON `diary_photo_attachments` (`diaryEntryId`, `blobSha256`)",
                )
            }
        }

        /**
         * Every step, in one place.
         *
         * The tests migrate the same chain the app does, so a new step reaches both by being
         * added here once. Listing them twice meant a version bump broke thirteen tests that
         * had nothing to do with what changed.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28,
            MIGRATION_28_29,
        )

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "memo-ripple.db",
            )
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
