package io.github.cragcoffee.memoripple.backup

internal fun fullBackupFixture(): MemoRippleBackupDto = MemoRippleBackupDto(
    format = BACKUP_FORMAT_IDENTIFIER,
    formatVersion = BACKUP_FORMAT_VERSION,
    exportedAt = 1_777_777_777_000,
    appVersionName = "1.0-test",
    appVersionCode = 99,
    payload = BackupPayloadDto(
        memos = listOf(
            MemoBackupDto(10, "一つ目", "本文A", 100, 110, isFavorite = true, archivedAt = 120),
            MemoBackupDto(20, "二つ目", "本文B", 200, 220, isPinned = true, trashedAt = 230),
            MemoBackupDto(21, "第一話", "本文C", 240, 250, noteId = 60, chapterId = 70, episodeOrder = 0),
            MemoBackupDto(22, "第二話", "本文D", 260, 270, noteId = 60, chapterId = null, episodeOrder = 1),
        ),
        memoComments = listOf(
            MemoCommentBackupDto(101, 10, "後で再生", 101, 1),
            MemoCommentBackupDto(102, 10, "先に再生", 102, 0),
            MemoCommentBackupDto(103, 20, "別のメモ", 103, 0),
        ),
        diaryEntries = listOf(
            DiaryEntryBackupDto(30, 20_000, "下書き日記", "draft", 300, 310, null, null, null),
            DiaryEntryBackupDto(31, 20_001, "確定日記", "locked", 320, 330, 321, 325, 330),
        ),
        futureDiaryComments = listOf(
            FutureDiaryCommentBackupDto(
                40, 30, "封印中", 400, 900, null, null, null,
                "pink", "large", "strong", "fixed_top",
            ),
            FutureDiaryCommentBackupDto(
                41, 30, "配達済み", 401, 800, 810, null, null,
                "blue", "small", "normal", "flow",
            ),
            FutureDiaryCommentBackupDto(
                42, 31, "初回表示前", 402, 700, 710, 720, null,
                "green", "standard", "strong", "fixed_bottom",
            ),
            FutureDiaryCommentBackupDto(43, 31, "表示済み", 403, 600, 610, 620, 630),
        ),
        tags = listOf(
            TagBackupDto(50, "仕事", 500),
            TagBackupDto(51, "Coffee", 510),
            TagBackupDto(52, "長期 計画", 520),
        ),
        memoTagRelations = listOf(
            MemoTagRelationBackupDto(10, 50),
            MemoTagRelationBackupDto(10, 51),
            MemoTagRelationBackupDto(20, 52),
        ),
        notes = listOf(
            NoteBackupDto(
                id = 60,
                title = "夜明け前に君と",
                subtitle = "港の話",
                coverColor = "plum",
                createdAt = 240,
                updatedAt = 270,
            ),
        ),
        noteChapters = listOf(NoteChapterBackupDto(70, 60, "第一章", 0)),
        settings = SettingsBackupDto(
            themeMode = "dark",
            playbackSpeed = "fast",
            commentSize = "large",
            stageBackground = "light",
        ),
    ),
)
