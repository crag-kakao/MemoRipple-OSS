package io.github.cragcoffee.memoripple.backup

import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentEntity
import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoTagCrossRef
import io.github.cragcoffee.memoripple.data.RoomBackupSnapshot
import io.github.cragcoffee.memoripple.data.TagEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.settings.CommentSize
import io.github.cragcoffee.memoripple.domain.settings.PlaybackSpeed
import io.github.cragcoffee.memoripple.domain.settings.StageBackground
import io.github.cragcoffee.memoripple.domain.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMapperTest {
    @Test
    fun fullRoomAndSettingsRoundTripPreservesEveryFieldAndState() {
        val snapshot = RoomBackupSnapshot(
            memos = listOf(
                MemoEntity(10, "一つ目", "本文A", 100, 110, isFavorite = true),
                MemoEntity(20, "二つ目", "本文B", 200, 220, isPinned = true),
                MemoEntity(
                    21,
                    "両方",
                    "本文C",
                    230,
                    240,
                    isFavorite = true,
                    isPinned = true,
                ),
                MemoEntity(22, "アーカイブ", "本文D", 250, 260, archivedAt = 270),
                MemoEntity(23, "ゴミ箱", "本文E", 280, 290, archivedAt = 295, trashedAt = 300),
            ),
            memoComments = listOf(
                MemoCommentEntity(
                    102,
                    10,
                    "先に再生",
                    102,
                    0,
                    appearanceColor = "pink",
                    appearanceSize = "large",
                    appearanceEmphasis = "strong",
                    motionSpeed = "fast",
                    motionPlacement = "top",
                    motionMode = "fixed_top",
                    flowDirection = "ltr",
                    flowEffect = "wave",
                ),
                MemoCommentEntity(101, 10, "後で再生", 101, 1),
                MemoCommentEntity(103, 20, "別のメモ", 103, 0),
            ),
            diaryEntries = listOf(
                DiaryEntryEntity(30, 20_000, "下書き日記", DiaryState.DRAFT, 300, 310),
                DiaryEntryEntity(
                    31,
                    20_001,
                    "確定日記",
                    DiaryState.LOCKED,
                    320,
                    330,
                    321,
                    325,
                    330,
                ),
            ),
            futureDiaryComments = listOf(
                FutureDiaryCommentEntity(
                    40,
                    30,
                    "封印中",
                    400,
                    900,
                    appearanceColor = "pink",
                    appearanceSize = "large",
                    appearanceEmphasis = "strong",
                    motionMode = "fixed_top",
                ),
                FutureDiaryCommentEntity(41, 30, "配達済み", 401, 800, 810),
                FutureDiaryCommentEntity(42, 31, "初回表示前", 402, 700, 710, 720, null),
                FutureDiaryCommentEntity(43, 31, "表示済み", 403, 600, 610, 620, 630),
            ),
            tags = listOf(
                TagEntity(50, "仕事", "仕事", 500),
                TagEntity(51, "Coffee", "coffee", 510),
            ),
            memoTagRelations = listOf(
                MemoTagCrossRef(10, 50),
                MemoTagCrossRef(10, 51),
                MemoTagCrossRef(20, 51),
            ),
        )
        val settings = AppSettings(
            ThemeMode.DARK,
            PlaybackSpeed.FAST,
            CommentSize.LARGE,
            StageBackground.LIGHT,
        )

        val encoded = BackupCodec().encode(
            BackupMapper.toDocument(snapshot, settings, 1_000, "test", 10),
        )
        val document = (BackupCodec().decode(encoded) as BackupDecodeResult.Success).document

        val restored = BackupMapper.toRoomSnapshot(document)
        assertEquals(snapshot, restored.copy(memoContentBlocks = emptyList(), diaryContentBlocks = emptyList()))
        // Format 21 carries each journal entry's content: an entry written before blocks is one text, its body.
        assertEquals(
            snapshot.diaryEntries.map { it.id to it.body },
            restored.diaryContentBlocks.map { it.diaryEntryId to it.text },
        )
        // Format 20 carries each memo's content: a memo written before blocks is one text, its body.
        assertEquals(
            snapshot.memos.filter { it.kind == "memo" }.map { it.id to it.body },
            restored.memoContentBlocks.map { it.memoId to it.text },
        )
        assertEquals(settings, BackupMapper.toAppSettings(document))
        assertEquals(1_000, document.exportedAt)
        assertEquals("test", document.appVersionName)
        assertEquals(10, document.appVersionCode)
        assertEquals(BACKUP_FORMAT_VERSION, document.formatVersion)
    }

    @Test
    fun legacyVersionOneMemoMapsMissingOrganizationFieldsToFalse() {
        val legacy = fullBackupFixture().copy(
            formatVersion = 1,
            payload = fullBackupFixture().payload.copy(
                memos = listOf(MemoBackupDto(1, "旧メモ", "本文", 10, 20)),
                memoComments = emptyList(),
            ),
        )

        val restored = BackupMapper.toRoomSnapshot(legacy).memos.single()

        assertEquals(false, restored.isFavorite)
        assertEquals(false, restored.isPinned)
        assertNull(restored.archivedAt)
        assertNull(restored.trashedAt)
    }

    @Test
    fun legacyVersionTwoBackupMapsMissingTagsToEmptyCollections() {
        val legacy = fullBackupFixture().copy(
            formatVersion = 2,
            payload = fullBackupFixture().payload.copy(
                tags = emptyList(),
                memoTagRelations = emptyList(),
            ),
        )

        val restored = BackupMapper.toRoomSnapshot(legacy)

        assertEquals(emptyList<TagEntity>(), restored.tags)
        assertEquals(emptyList<MemoTagCrossRef>(), restored.memoTagRelations)
    }

    @Test
    fun legacyVersionsOneThroughSixMapMissingModeToFlow() {
        (1..6).forEach { version ->
            val legacy = fullBackupFixture().copy(
                formatVersion = version,
                payload = fullBackupFixture().payload.copy(
                    memoComments = listOf(MemoCommentBackupDto(101, 10, "旧コメント", 100, 0)),
                ),
            )

            val restored = BackupMapper.toRoomSnapshot(legacy).memoComments.single()
            assertEquals("default", restored.appearanceColor)
            assertEquals("standard", restored.appearanceSize)
            assertEquals("normal", restored.appearanceEmphasis)
            assertEquals("standard", restored.motionSpeed)
            assertEquals("auto", restored.motionPlacement)
            assertEquals("flow", restored.motionMode)
        }
    }

    @Test
    fun legacyVersionsOneThroughSevenMapFutureExpressionToDefaults() {
        (1..7).forEach { version ->
            val legacy = fullBackupFixture().copy(
                formatVersion = version,
                payload = fullBackupFixture().payload.copy(
                    futureDiaryComments = listOf(
                        FutureDiaryCommentBackupDto(
                            40,
                            30,
                            "旧Future",
                            400,
                            900,
                            null,
                            null,
                            null,
                        ),
                    ),
                ),
            )

            val restored = BackupMapper.toRoomSnapshot(legacy).futureDiaryComments.single()
            assertEquals("default", restored.appearanceColor)
            assertEquals("standard", restored.appearanceSize)
            assertEquals("normal", restored.appearanceEmphasis)
            assertEquals("flow", restored.motionMode)
        }
    }

    @Test
    fun legacyVersionsOneThroughEightForceUserFlowPathDefaults() {
        (1..8).forEach { version ->
            val legacy = fullBackupFixture().copy(
                formatVersion = version,
                payload = fullBackupFixture().payload.copy(
                    memoComments = listOf(
                        MemoCommentBackupDto(
                            101,
                            10,
                            "旧コメント",
                            100,
                            0,
                            flowDirection = "ltr",
                            flowEffect = "wave",
                        ),
                    ),
                ),
            )

            val restored = BackupMapper.toRoomSnapshot(legacy).memoComments.single()
            assertEquals("rtl", restored.flowDirection)
            assertEquals("straight", restored.flowEffect)
        }
    }

    @org.junit.Test
    fun memoKindsRoundTripInFormatFifteenAndOlderFormatsRestoreEveryMemoAsAMemo() {
        val snapshot = RoomBackupSnapshot(
            memos = listOf(
                MemoEntity(10, "メモ", "- 項目\n  - 子", 100, 110),
                MemoEntity(11, "計画", "- 一\n  - 二", 120, 130, kind = "outline"),
            ),
            memoComments = emptyList(),
            diaryEntries = emptyList(),
            futureDiaryComments = emptyList(),
        )
        val document = BackupMapper.toDocument(
            snapshot,
            AppSettings.Default,
            exportedAt = 1,
            appVersionName = "test",
            appVersionCode = 1,
        )
        // A fresh export says what each memo is…
        assertEquals(BACKUP_FORMAT_VERSION, document.formatVersion)
        assertEquals(listOf("memo", "outline"), document.payload.memos.map { it.kind })
        // …and a restore of it keeps that, body and all, whichever way the body reads.
        val restored = BackupMapper.toRoomSnapshot(document).memos
        assertEquals(listOf("memo", "outline"), restored.map { it.kind })
        assertEquals(listOf("- 項目\n  - 子", "- 一\n  - 二"), restored.map { it.body })

        // A file written before format 15 never carried a kind: whatever a field claims,
        // every memo in it is a memo. The bodies are still not read to decide.
        val older = document.copy(formatVersion = 14)
        assertEquals(listOf("memo", "memo"), BackupMapper.toRoomSnapshot(older).memos.map { it.kind })
    }

    @org.junit.Test
    fun linkNumbersRoundTripAndOlderFormatsCarryNone() {
        val withLinks = with(fullBackupFixture()) {
            copy(
                payload = payload.copy(
                    memoComments = payload.memoComments.mapIndexed { index, comment ->
                        comment.copy(linkNo = if (index == 0) 3 else null)
                    },
                ),
            )
        }
        // Restore carries the number onto the entity…
        val snapshot = BackupMapper.toRoomSnapshot(withLinks)
        assertEquals(listOf(3, null, null), snapshot.memoComments.map { it.linkNo })
        // …and a fresh export writes it back out unchanged.
        val document = BackupMapper.toDocument(
            snapshot,
            BackupMapper.toAppSettings(withLinks),
            exportedAt = 1,
            appVersionName = "test",
            appVersionCode = 1,
        )
        assertEquals(listOf(3, null, null), document.payload.memoComments.map { it.linkNo })

        // A pre-14 document never assigns links, even if a field somehow claims one.
        val old = withLinks.copy(formatVersion = 13)
        org.junit.Assert.assertTrue(
            BackupMapper.toRoomSnapshot(old).memoComments.all { it.linkNo == null },
        )
    }
}

class BackupFolderMapperTest {
    private val folders = listOf(
        io.github.cragcoffee.memoripple.data.FolderEntity(1, "開発", null, 10, 11),
        io.github.cragcoffee.memoripple.data.FolderEntity(2, "Android", 1, 12, 13),
        io.github.cragcoffee.memoripple.data.FolderEntity(3, "日常", null, 14, 15),
    )
    private val snapshot = RoomBackupSnapshot(
        memos = listOf(
            MemoEntity(10, "仕様", "本文", 100, 110, folderId = 2),
            MemoEntity(11, "計画", "- 一", 120, 130, kind = "outline", folderId = 2),
            MemoEntity(12, "棚上げ", "本文", 140, 150, archivedAt = 160, folderId = 1),
            MemoEntity(13, "捨てた", "本文", 170, 180, trashedAt = 190, folderId = 3),
            MemoEntity(14, "未分類", "本文", 200, 210),
        ),
        memoComments = emptyList(),
        diaryEntries = emptyList(),
        futureDiaryComments = emptyList(),
        folders = folders,
    )

    @org.junit.Test
    fun foldersAndFolderMembershipRoundTripInFormatSixteen() {
        val document = BackupMapper.toDocument(
            snapshot, AppSettings.Default, exportedAt = 1, appVersionName = "test", appVersionCode = 1,
        )
        assertEquals(BACKUP_FORMAT_VERSION, document.formatVersion)
        assertEquals(listOf(1L to null, 2L to 1L, 3L to null), document.payload.folders.map { it.id to it.parentFolderId })
        assertEquals(listOf(2L, 2L, 1L, 3L, null), document.payload.memos.map { it.folderId })

        val restored = BackupMapper.toRoomSnapshot(document)
        assertEquals(folders, restored.folders)
        assertEquals(listOf(2L, 2L, 1L, 3L, null), restored.memos.map { it.folderId })
        assertEquals(listOf("memo", "outline", "memo", "memo", "memo"), restored.memos.map { it.kind })
        assertEquals(160L, restored.memos[2].archivedAt)
        assertEquals(190L, restored.memos[3].trashedAt)
    }

    @org.junit.Test
    fun aFileFromBeforeSixteenHasNoFoldersAndEveryMemoAtTheRoot() {
        val document = BackupMapper.toDocument(
            snapshot, AppSettings.Default, exportedAt = 1, appVersionName = "test", appVersionCode = 1,
        ).copy(formatVersion = 15)

        val restored = BackupMapper.toRoomSnapshot(document)

        assertEquals(emptyList<io.github.cragcoffee.memoripple.data.FolderEntity>(), restored.folders)
        assertTrue(restored.memos.all { it.folderId == null })
        assertEquals("- 一", restored.memos[1].body)
    }

    @org.junit.Test
    fun aMemoPointingAtAFolderTheFileDoesNotHaveComesBackAtTheRootNotLost() {
        val document = BackupMapper.toDocument(
            snapshot, AppSettings.Default, exportedAt = 1, appVersionName = "test", appVersionCode = 1,
        )
        val dangling = document.copy(
            payload = document.payload.copy(
                memos = document.payload.memos.map { if (it.id == 10L) it.copy(folderId = 999) else it },
            ),
        )

        val restored = BackupMapper.toRoomSnapshot(dangling)

        assertEquals(5, restored.memos.size)
        assertNull(restored.memos.first { it.id == 10L }.folderId)
        assertEquals(2L, restored.memos.first { it.id == 11L }.folderId)
    }
}

/** Format 17: the hand-given order of the cards travels with them; older files have none. */
class BackupOrderMapperTest {
    private val snapshot = RoomBackupSnapshot(
        memos = listOf(
            MemoEntity(10, "二番目", "本文", 100, 110, sortIndex = 1),
            MemoEntity(11, "一番目", "- 一", 120, 130, kind = "outline", sortIndex = 0),
        ),
        memoComments = emptyList(),
        diaryEntries = emptyList(),
        futureDiaryComments = emptyList(),
        notes = listOf(io.github.cragcoffee.memoripple.data.NoteEntity(id = 5, title = "夜明け", coverColor = "sky", createdAt = 1, updatedAt = 2, sortIndex = 4)),
    )

    @org.junit.Test
    fun theBackupFormatIsTwentyThreeNow() {
        assertEquals(23, BACKUP_FORMAT_VERSION)   // 23: an outline's photo rows (docs/OUTLINE_PHOTO_ROWS.md, 2026-09-25)
    }

    @org.junit.Test
    fun theOrderRoundTripsInFormatSeventeen() {
        val document = BackupMapper.toDocument(snapshot, AppSettings.Default, exportedAt = 1, appVersionName = "test", appVersionCode = 1)
        assertEquals(listOf(1, 0), document.payload.memos.map { it.sortIndex })
        val restored = BackupMapper.toRoomSnapshot(document)
        assertEquals(listOf(1, 0), restored.memos.map { it.sortIndex })
        assertEquals(4, document.payload.notes.single().sortIndex)
        assertEquals(4, restored.notes.single().sortIndex)
    }

    @org.junit.Test
    fun aFileFromBeforeSeventeenRestoresEveryCardAtIndexZero() {
        val document = BackupMapper.toDocument(snapshot, AppSettings.Default, exportedAt = 1, appVersionName = "test", appVersionCode = 1)
            .copy(formatVersion = 16)
        assertEquals(listOf(0, 0), BackupMapper.toRoomSnapshot(document).memos.map { it.sortIndex })
        assertEquals(0, BackupMapper.toRoomSnapshot(document).notes.single().sortIndex)
    }
}

/** Templates ride along from format 18; an older file simply has none. */
class BackupTemplateMappingTest {
    @Test
    fun templatesRoundTripAndAnOlderFileYieldsNone() {
        val templates = listOf(
            io.github.cragcoffee.memoripple.domain.memos.MemoTemplate("t1", "朝の記録", "# 朝\n- 体調\n"),
            io.github.cragcoffee.memoripple.domain.memos.MemoTemplate("t2", "Project Log", "## 今日\n"),
        )
        val document = BackupMapper.toDocument(
            snapshot = RoomBackupSnapshot(emptyList(), emptyList(), emptyList(), emptyList()),
            settings = AppSettings.Default,
            exportedAt = 1,
            appVersionName = "test",
            appVersionCode = 1,
            templates = templates,
        )
        assertEquals(23, document.formatVersion)
        assertEquals(templates, BackupMapper.toTemplates(document))
        assertEquals(emptyList<io.github.cragcoffee.memoripple.domain.memos.MemoTemplate>(), BackupMapper.toTemplates(document.copy(formatVersion = 17, payload = document.payload.copy(templates = emptyList()))))
    }

    /** Format 19 (Template first-class, 2026-09-21): a v2 template — action, kind, fields, search, target — comes back whole. */
    @Test
    fun aV2TemplateRoundTripsWhole() {
        val meeting = io.github.cragcoffee.memoripple.domain.memos.StarterTemplates.meetingMemo.copy(id = "u1", createdAt = 5, updatedAt = 6)
        val log = io.github.cragcoffee.memoripple.domain.memos.StarterTemplates.projectLog.copy(id = "u2", targetSpec = io.github.cragcoffee.memoripple.domain.memos.TemplateTargetSpec.Named("MemoRipple開発"))
        val week = io.github.cragcoffee.memoripple.domain.memos.StarterTemplates.thisWeek.copy(id = "u3")
        val choice = io.github.cragcoffee.memoripple.domain.memos.MemoTemplate(
            "u4", "気分", "{{mood}}", fields = listOf(io.github.cragcoffee.memoripple.domain.memos.TemplateField("mood", "気分", io.github.cragcoffee.memoripple.domain.memos.TemplateFieldType.CHOICE, default = "普通", choices = listOf("良い", "普通"))),
        )
        val all = listOf(meeting, log, week, choice)
        val document = BackupMapper.toDocument(RoomBackupSnapshot(emptyList(), emptyList(), emptyList(), emptyList()), AppSettings.Default, 1, "test", 1, templates = all)
        val dtos = document.payload.templates
        assertEquals(listOf("create", "append", "search", "create"), dtos.map { it.action })
        assertEquals("memo", dtos[0].documentKind)
        assertEquals(listOf("text", "text", "multiline", "multiline", "multiline"), dtos[0].fields.map { it.type })
        assertEquals(TemplateTargetBackupDto("named", "MemoRipple開発"), dtos[1].target)
        assertEquals(TemplateSearchBackupDto("", "this_week", listOf("memo", "outline", "journal")), dtos[2].search)
        assertEquals(all, BackupMapper.toTemplates(document))
        // the same JSON, decoded and encoded again, is the same document
        val codec = BackupCodec()
        val back = (codec.decode(codec.encode(document)) as BackupDecodeResult.Success).document
        assertEquals(all, BackupMapper.toTemplates(back))
    }

    /** Think templates (2026-09-22): the flow travels as a defaulted word in the same format 19 — a Think template comes back whole, a file without the word is the record flow (what an older 19 reader also sees), an unknown word is refused. */
    @Test
    fun aThinkTemplateRoundTripsInFormat19AndAnOlderReaderSeesACreateTemplate() {
        val think = io.github.cragcoffee.memoripple.domain.memos.StarterTemplates.thinkIdea.copy(id = "u5", createdAt = 7, updatedAt = 8)
        val legacy = io.github.cragcoffee.memoripple.domain.memos.MemoTemplate("u6", "朝の記録", "# 朝\n")
        val document = BackupMapper.toDocument(RoomBackupSnapshot(emptyList(), emptyList(), emptyList(), emptyList()), AppSettings.Default, 1, "test", 1, templates = listOf(think, legacy))
        assertEquals(23, document.formatVersion)
        val dtos = document.payload.templates
        assertEquals(listOf("think", "record"), dtos.map { it.flow })
        assertEquals(listOf("create", "create"), dtos.map { it.action })
        assertEquals(listOf(think, legacy), BackupMapper.toTemplates(document))
        val codec = BackupCodec()
        val back = (codec.decode(codec.encode(document)) as BackupDecodeResult.Success).document
        assertEquals(listOf(think, legacy), BackupMapper.toTemplates(back))
        assertTrue(BackupMapper.toTemplates(back)[1].isLegacyShape)
        // what the current main build (no flow word) reads from the same bytes: the same template as a CREATE memo template, still valid
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val olderReader = json.decodeFromString(OlderTemplateDto.serializer(), json.encodeToString(TemplateBackupDto.serializer(), dtos[0]))
        assertEquals("create", olderReader.action)
        assertEquals(6, olderReader.fields.size)
        assertEquals(null, TemplateBackupMapping.toDomain(dtos[0].copy(flow = "dream")))
        assertEquals(io.github.cragcoffee.memoripple.domain.memos.TemplateFlow.RECORD, TemplateBackupMapping.toDomain(dtos[0].copy(flow = "record"))!!.flow)
    }

    /** Template folders (2026-09-22): a template's folder and the folders travel as defaulted format-19 fields; a reader before them ignores both and sees an unclassified template. */
    @Test
    fun templateFoldersRoundTripInFormat19AndAnOlderReaderIgnoresThem() {
        val folder = io.github.cragcoffee.memoripple.domain.memos.TemplateFolder("f1", "仕事", 0)
        val inFolder = io.github.cragcoffee.memoripple.domain.memos.MemoTemplate("u7", "週報", "x", folderId = "f1")
        val document = BackupMapper.toDocument(RoomBackupSnapshot(emptyList(), emptyList(), emptyList(), emptyList()), AppSettings.Default, 1, "test", 1, templates = listOf(inFolder), templateFolders = listOf(folder))
        assertEquals(23, document.formatVersion)
        assertEquals("f1", document.payload.templates.single().folderId)
        assertEquals(listOf(TemplateFolderBackupDto("f1", "仕事", 0)), document.payload.templateFolders)
        val codec = BackupCodec()
        val back = (codec.decode(codec.encode(document)) as BackupDecodeResult.Success).document
        assertEquals(listOf(inFolder), BackupMapper.toTemplates(back))
        assertEquals(listOf(folder), BackupMapper.toTemplateFolders(back))
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val older = json.decodeFromString(OlderTemplateDto.serializer(), json.encodeToString(TemplateBackupDto.serializer(), document.payload.templates.single()))
        assertEquals("週報", older.name)
        assertEquals("an 18 / early-19 file has no folders", emptyList<io.github.cragcoffee.memoripple.domain.memos.TemplateFolder>(), BackupMapper.toTemplateFolders(document.copy(payload = document.payload.copy(templateFolders = emptyList()))))
    }

    /** The format-19 template shape as the build before Think templates declared it — no flow word. */
    @kotlinx.serialization.Serializable
    private data class OlderTemplateDto(val id: String, val name: String, val body: String, val description: String = "", val action: String = "create", val documentKind: String = "memo", val fields: List<TemplateFieldBackupDto> = emptyList())

    /** An 18 file carries name + body only; it maps to the legacy shape, as before. A word this build does not know is no template. */
    @Test
    fun aFormat18TemplateIsTheLegacyShapeAndAnUnknownWordIsRefused() {
        val legacy = TemplateBackupDto("t1", "朝の記録", "# 朝\n")
        assertEquals(io.github.cragcoffee.memoripple.domain.memos.MemoTemplate("t1", "朝の記録", "# 朝\n"), TemplateBackupMapping.toDomain(legacy))
        assertEquals(true, TemplateBackupMapping.toDomain(legacy)!!.isLegacyShape)
        assertEquals(null, TemplateBackupMapping.toDomain(legacy.copy(action = "delete")))
        assertEquals(null, TemplateBackupMapping.toDomain(legacy.copy(documentKind = "note")))
        assertEquals(null, TemplateBackupMapping.toDomain(legacy.copy(fields = listOf(TemplateFieldBackupDto("a", "A", "script")))))
        assertEquals(null, TemplateBackupMapping.toDomain(legacy.copy(target = TemplateTargetBackupDto("id"))))
        assertEquals(null, TemplateBackupMapping.toDomain(legacy.copy(search = TemplateSearchBackupDto("x", "next_week", listOf("memo")))))
    }
}
