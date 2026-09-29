package io.github.cragcoffee.memoripple.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupValidatorTest {
    private val validator = BackupValidator()

    @Test
    fun acceptsCompleteValidBackup() {
        assertEquals(BackupValidationResult.Valid, validator.validate(fullBackupFixture()))
    }

    @Test
    fun acceptsLegacyVersionOneBackup() {
        assertEquals(
            BackupValidationResult.Valid,
            validator.validate(fullBackupFixture().copy(formatVersion = 1)),
        )
    }

    @Test
    fun acceptsLegacyVersionTwoBackupWithoutTags() {
        val legacy = fullBackupFixture().copy(
            formatVersion = 2,
            payload = fullBackupFixture().payload.copy(
                tags = emptyList(),
                memoTagRelations = emptyList(),
            ),
        )
        assertEquals(BackupValidationResult.Valid, validator.validate(legacy))
    }

    @Test
    fun rejectsFutureBackupVersion() = checkRejected(
        fullBackupFixture().copy(formatVersion = BACKUP_FORMAT_VERSION + 1),
        BackupValidationIssue.UNSUPPORTED_VERSION,
    )

    @Test
    fun rejectsUnknownCommentMotionMode() = with(fullBackupFixture()) {
        val invalid = payload.memoComments.first().copy(motionMode = "floating")
        copy(payload = payload.copy(memoComments = listOf(invalid) + payload.memoComments.drop(1)))
            .assertRejected(BackupValidationIssue.INVALID_COMMENT_MOTION)
    }

    @Test
    fun rejectsUnknownVersionNineDirectionOrEffect() {
        mutateFirstComment { it.copy(flowDirection = "reverse", flowEffect = "zigzag") }
            .assertRejected(BackupValidationIssue.INVALID_COMMENT_MOTION)
    }

    @Test
    fun legacyVersionEightIgnoresFlowPathFieldsThatDidNotExistInItsContract() {
        val legacy = mutateFirstComment {
            it.copy(flowDirection = "reverse", flowEffect = "zigzag")
        }.copy(formatVersion = 8)

        assertEquals(BackupValidationResult.Valid, validator.validate(legacy))
    }

    @Test
    fun rejectsUnknownFutureExpressionBeforeRestore() = mutateFirstFuture {
        it.copy(
            appearanceColor = "orange",
            appearanceSize = "huge",
            appearanceEmphasis = "loud",
            motionMode = "wave",
        )
    }.assertRejected(BackupValidationIssue.INVALID_FUTURE_COMMENT_EXPRESSION)

    @Test
    fun rejectsNegativeMemoLifecycleTimestamp() = with(fullBackupFixture()) {
        val invalid = payload.memos.first().copy(archivedAt = -1)
        copy(payload = payload.copy(memos = listOf(invalid) + payload.memos.drop(1)))
            .assertRejected(BackupValidationIssue.INVALID_MEMO_LIFECYCLE_TIMESTAMP)
    }

    @Test
    fun rejectsUnknownVersionFiveAppearanceRole() {
        val invalid = fullBackupFixture().copy(
            payload = fullBackupFixture().payload.copy(
                memoComments = listOf(
                    // orange became a real colour when the palette adopted the video site's set,
                    // so the unknown example has to be something no version will ever know.
                    fullBackupFixture().payload.memoComments.first().copy(colorRole = "ultraviolet"),
                ),
            ),
        )

        checkRejected(invalid, BackupValidationIssue.INVALID_COMMENT_APPEARANCE)
    }

    @Test
    fun rejectsUnknownVersionSixMotionRole() {
        mutateFirstComment { it.copy(speedRole = "warp", placementRole = "ceiling") }
            .assertRejected(BackupValidationIssue.INVALID_COMMENT_MOTION)
    }

    @Test
    fun rejectsWrongFormat() = checkRejected(
        fullBackupFixture().copy(format = "different_app"),
        BackupValidationIssue.WRONG_FORMAT,
    )

    @Test
    fun rejectsDuplicateMemoId() = with(fullBackupFixture()) {
        checkRejected(
            copy(payload = payload.copy(memos = payload.memos + payload.memos.first())),
            BackupValidationIssue.DUPLICATE_MEMO_ID,
        )
    }

    @Test
    fun rejectsDuplicateMemoCommentId() = with(fullBackupFixture()) {
        checkRejected(
            copy(payload = payload.copy(memoComments = payload.memoComments + payload.memoComments.first())),
            BackupValidationIssue.DUPLICATE_MEMO_COMMENT_ID,
        )
    }

    @Test
    fun rejectsDuplicateTagId() = with(fullBackupFixture()) {
        checkRejected(
            copy(payload = payload.copy(tags = payload.tags + payload.tags.first())),
            BackupValidationIssue.DUPLICATE_TAG_ID,
        )
    }

    @Test
    fun rejectsNonPositiveTagId() = with(fullBackupFixture()) {
        copy(payload = payload.copy(tags = payload.tags.mapIndexed { index, tag ->
            if (index == 0) tag.copy(id = 0) else tag
        })).assertRejected(BackupValidationIssue.NON_POSITIVE_ID)
    }

    @Test
    fun rejectsBlankAndOverlongTagNames() = with(fullBackupFixture()) {
        copy(payload = payload.copy(tags = payload.tags.mapIndexed { index, tag ->
            if (index == 0) tag.copy(name = "   ") else tag
        })).assertRejected(BackupValidationIssue.INVALID_TAG_NAME)
        copy(payload = payload.copy(tags = payload.tags.mapIndexed { index, tag ->
            if (index == 0) tag.copy(name = "😀".repeat(41)) else tag
        })).assertRejected(BackupValidationIssue.INVALID_TAG_NAME)
    }

    @Test
    fun rejectsDuplicateNormalizedTagNames() = with(fullBackupFixture()) {
        val duplicate = TagBackupDto(99, " ＣＯＦＦＥＥ ", 999)
        copy(payload = payload.copy(tags = payload.tags + duplicate))
            .assertRejected(BackupValidationIssue.DUPLICATE_NORMALIZED_TAG_NAME)
    }

    @Test
    fun rejectsDuplicateAndOrphanMemoTagRelations() = with(fullBackupFixture()) {
        copy(payload = payload.copy(
            memoTagRelations = payload.memoTagRelations + payload.memoTagRelations.first(),
        )).assertRejected(BackupValidationIssue.DUPLICATE_MEMO_TAG_RELATION)
        copy(payload = payload.copy(
            memoTagRelations = payload.memoTagRelations + MemoTagRelationBackupDto(999, 50),
        )).assertRejected(BackupValidationIssue.ORPHAN_MEMO_TAG_RELATION)
        copy(payload = payload.copy(
            memoTagRelations = payload.memoTagRelations + MemoTagRelationBackupDto(10, 999),
        )).assertRejected(BackupValidationIssue.ORPHAN_MEMO_TAG_RELATION)
    }

    @Test
    fun rejectsOrphanMemoComment() = mutateFirstComment { it.copy(memoId = 999) }
        .assertRejected(BackupValidationIssue.ORPHAN_MEMO_COMMENT)

    /** Up to format 17 a day held one entry, and a file that says otherwise is not one of ours. */
    @Test
    fun rejectsDuplicateDiaryDateInFilesUpToFormat17() = with(fullBackupFixture()) {
        val duplicate = payload.diaryEntries[1].copy(
            diaryDateEpochDay = payload.diaryEntries[0].diaryDateEpochDay,
        )
        copy(formatVersion = 17, payload = payload.copy(diaryEntries = listOf(payload.diaryEntries[0], duplicate)))
            .assertRejected(BackupValidationIssue.DUPLICATE_DIARY_DATE)
    }

    /** From format 18 a day may hold several journal entries. */
    @Test
    fun acceptsSeveralEntriesOnOneDayFromFormat18() = with(fullBackupFixture()) {
        val second = payload.diaryEntries[1].copy(
            diaryDateEpochDay = payload.diaryEntries[0].diaryDateEpochDay,
        )
        assertEquals(
            BackupValidationResult.Valid,
            validator.validate(copy(formatVersion = 18, payload = payload.copy(diaryEntries = listOf(payload.diaryEntries[0], second)))),
        )
    }

    @Test
    fun rejectsAnUnusableOrDuplicatedTemplate() = with(fullBackupFixture()) {
        copy(payload = payload.copy(templates = listOf(TemplateBackupDto("t1", " ", "本文"))))
            .assertRejected(BackupValidationIssue.INVALID_TEMPLATE)
        copy(payload = payload.copy(templates = listOf(TemplateBackupDto("t1", "名前", ""))))
            .assertRejected(BackupValidationIssue.INVALID_TEMPLATE)
        copy(payload = payload.copy(templates = listOf(TemplateBackupDto("t1", "a", "b"), TemplateBackupDto("t1", "c", "d"))))
            .assertRejected(BackupValidationIssue.INVALID_TEMPLATE)
        assertEquals(
            BackupValidationResult.Valid,
            validator.validate(copy(payload = payload.copy(templates = listOf(TemplateBackupDto("t1", "朝の記録", "# 朝\n"))))),
        )
    }

    /** Format 19 (Template first-class, 2026-09-21): a v2 template is judged by its whole definition; an unknown word on the wire is invalid. */
    @Test
    fun judgesAV2TemplateByItsDefinition() = with(fullBackupFixture()) {
        val search = TemplateBackupMapping.toDto(io.github.cragcoffee.memoripple.domain.memos.StarterTemplates.yesterdayJournal)
        val append = TemplateBackupMapping.toDto(io.github.cragcoffee.memoripple.domain.memos.StarterTemplates.projectLog)
        val meeting = TemplateBackupMapping.toDto(io.github.cragcoffee.memoripple.domain.memos.StarterTemplates.meetingMemo)
        assertEquals(BackupValidationResult.Valid, validator.validate(copy(payload = payload.copy(templates = listOf(search, append, meeting)))))
        // a SEARCH template has no body — that is not a fault at 19 (it was at 18, where every template was a memo body)
        assertEquals("", search.body)
        copy(payload = payload.copy(templates = listOf(search.copy(search = null)))).assertRejected(BackupValidationIssue.INVALID_TEMPLATE)
        copy(payload = payload.copy(templates = listOf(meeting.copy(action = "delete")))).assertRejected(BackupValidationIssue.INVALID_TEMPLATE)
        copy(payload = payload.copy(templates = listOf(meeting.copy(body = "{{nowhere}}")))).assertRejected(BackupValidationIssue.INVALID_TEMPLATE)
        copy(payload = payload.copy(templates = listOf(meeting.copy(fields = meeting.fields + meeting.fields.first())))).assertRejected(BackupValidationIssue.INVALID_TEMPLATE)
        copy(payload = payload.copy(templates = listOf(append.copy(target = null)))).assertRejected(BackupValidationIssue.INVALID_TEMPLATE)
    }

    @Test
    fun rejectsOrphanFutureComment() = mutateFirstFuture { it.copy(diaryEntryId = 999) }
        .assertRejected(BackupValidationIssue.ORPHAN_FUTURE_COMMENT)

    @Test
    fun rejectsPlaybackOrderGapDuplicateAndNegative() {
        mutateFirstComment { it.copy(playbackOrder = 2) }
            .assertRejected(BackupValidationIssue.INVALID_PLAYBACK_ORDER)
        mutateFirstComment { it.copy(playbackOrder = 0) }
            .assertRejected(BackupValidationIssue.INVALID_PLAYBACK_ORDER)
        mutateFirstComment { it.copy(playbackOrder = -1) }
            .assertRejected(BackupValidationIssue.INVALID_PLAYBACK_ORDER)
    }

    @Test
    fun rejectsUnknownDiaryState() = with(fullBackupFixture()) {
        val invalid = payload.diaryEntries.first().copy(state = "archived")
        copy(payload = payload.copy(diaryEntries = listOf(invalid) + payload.diaryEntries.drop(1)))
            .assertRejected(BackupValidationIssue.INVALID_DIARY_STATE)
    }

    @Test
    fun rejectsRevealedWithoutDelivered() = mutateFirstFuture {
        it.copy(deliveredAt = null, revealedAt = 500)
    }.assertRejected(BackupValidationIssue.REVEALED_WITHOUT_DELIVERED)

    @Test
    fun rejectsPresentedWithoutRevealed() = mutateFirstFuture {
        it.copy(deliveredAt = 490, revealedAt = null, firstPresentedAt = 500)
    }.assertRejected(BackupValidationIssue.PRESENTED_WITHOUT_REVEALED)

    @Test
    fun rejectsBlankFutureComment() = mutateFirstFuture { it.copy(text = "  ") }
        .assertRejected(BackupValidationIssue.EMPTY_FUTURE_COMMENT)

    @Test
    fun rejectsInvalidSettingsValue() = with(fullBackupFixture()) {
        copy(payload = payload.copy(settings = payload.settings.copy(themeMode = "neon")))
            .assertRejected(BackupValidationIssue.INVALID_SETTINGS)
    }

    private fun mutateFirstComment(
        transform: (MemoCommentBackupDto) -> MemoCommentBackupDto,
    ): MemoRippleBackupDto = with(fullBackupFixture()) {
        copy(
            payload = payload.copy(
                memoComments = listOf(transform(payload.memoComments.first())) +
                    payload.memoComments.drop(1),
            ),
        )
    }

    @org.junit.Test
    fun linkNumbersMustBePositiveAndUniquePerMemo() {
        fun withComments(vararg linkNos: Int?): MemoRippleBackupDto = with(fullBackupFixture()) {
            copy(
                payload = payload.copy(
                    memoComments = payload.memoComments.mapIndexed { index, comment ->
                        comment.copy(linkNo = linkNos.getOrNull(index))
                    },
                ),
            )
        }
        // Gaps and nulls are the normal shape.
        assertTrue(validator.validate(withComments(2, null, 1)) is BackupValidationResult.Valid)
        // Zero or negative is not a number a memo ever assigns.
        withComments(0, null, null).assertRejected(BackupValidationIssue.INVALID_COMMENT_LINK)
        // Comments 101 and 102 share memo 10 — two R1 in one memo cannot both be true.
        withComments(1, 1, null).assertRejected(BackupValidationIssue.INVALID_COMMENT_LINK)
        // The same number on different memos is fine.
        assertTrue(validator.validate(withComments(1, 2, 1)) is BackupValidationResult.Valid)
    }

    private fun mutateFirstFuture(
        transform: (FutureDiaryCommentBackupDto) -> FutureDiaryCommentBackupDto,
    ): MemoRippleBackupDto = with(fullBackupFixture()) {
        copy(
            payload = payload.copy(
                futureDiaryComments = listOf(transform(payload.futureDiaryComments.first())) +
                    payload.futureDiaryComments.drop(1),
            ),
        )
    }

    private fun MemoRippleBackupDto.assertRejected(issue: BackupValidationIssue) =
        checkRejected(this, issue)

    @Test
    fun acceptsMemosAndOutlinesInFormatFifteen() {
        val document = fullBackupFixture().let { fixture ->
            fixture.copy(
                formatVersion = 15,
                payload = fixture.payload.copy(
                    memos = fixture.payload.memos.mapIndexed { index, memo ->
                        memo.copy(kind = if (index == 0) "outline" else "memo")
                    },
                ),
            )
        }
        assertEquals(BackupValidationResult.Valid, validator.validate(document))
    }

    @Test
    fun rejectsAKindItDoesNotKnow() {
        val document = fullBackupFixture().let { fixture ->
            fixture.copy(
                formatVersion = 15,
                payload = fixture.payload.copy(
                    memos = fixture.payload.memos.map { it.copy(kind = "canvas") },
                ),
            )
        }
        checkRejected(document, BackupValidationIssue.INVALID_MEMO_KIND)
    }

    @Test
    fun aFileFromBeforeFifteenIsNotJudgedOnAKindItCouldNotHaveWritten() {
        // Nothing before 15 wrote the field; a restore reads every such memo as a memo, so a
        // stray value in an old file is not a reason to refuse the whole backup.
        val document = fullBackupFixture().let { fixture ->
            fixture.copy(
                formatVersion = 14,
                payload = fixture.payload.copy(
                    memos = fixture.payload.memos.map { it.copy(kind = "canvas") },
                ),
            )
        }
        assertEquals(BackupValidationResult.Valid, validator.validate(document))
    }

    private fun checkRejected(document: MemoRippleBackupDto, issue: BackupValidationIssue) {
        val result = validator.validate(document)
        assertTrue(result is BackupValidationResult.Invalid && issue in result.issues)
    }
}

class BackupFolderValidatorTest {
    private val validator = BackupValidator()

    private fun sixteen(
        folders: List<FolderBackupDto>,
        memoFolder: Long? = null,
    ): MemoRippleBackupDto = fullBackupFixture().let { fixture ->
        fixture.copy(
            formatVersion = 16,
            payload = fixture.payload.copy(
                folders = folders,
                memos = fixture.payload.memos.mapIndexed { index, memo ->
                    if (index == 0) memo.copy(folderId = memoFolder) else memo
                },
            ),
        )
    }

    @Test
    fun acceptsNestedFoldersAndMembership() {
        val tree = listOf(
            FolderBackupDto(1, "開発", null, 1, 1),
            FolderBackupDto(2, "Android", 1, 1, 1),
            FolderBackupDto(3, "MemoRipple", 2, 1, 1),
        )
        assertEquals(BackupValidationResult.Valid, validator.validate(sixteen(tree, memoFolder = 3)))
        assertEquals(BackupValidationResult.Valid, validator.validate(sixteen(emptyList())))
    }

    @Test
    fun rejectsACycleAndAParentThatDoesNotExistAndADuplicateFolderId() {
        val cycle = listOf(FolderBackupDto(1, "A", 2, 1, 1), FolderBackupDto(2, "B", 1, 1, 1))
        val self = listOf(FolderBackupDto(1, "A", 1, 1, 1))
        val orphan = listOf(FolderBackupDto(1, "A", 9, 1, 1))
        val duplicate = listOf(FolderBackupDto(1, "A", null, 1, 1), FolderBackupDto(1, "B", null, 1, 1))

        listOf(cycle, self).forEach { tree ->
            val result = validator.validate(sixteen(tree))
            assertTrue(result is BackupValidationResult.Invalid && BackupValidationIssue.FOLDER_CYCLE in result.issues)
        }
        val orphanResult = validator.validate(sixteen(orphan))
        assertTrue(orphanResult is BackupValidationResult.Invalid && BackupValidationIssue.ORPHAN_FOLDER_PARENT in orphanResult.issues)
        val duplicateResult = validator.validate(sixteen(duplicate))
        assertTrue(duplicateResult is BackupValidationResult.Invalid && BackupValidationIssue.DUPLICATE_FOLDER_ID in duplicateResult.issues)
    }

    @Test
    fun aMemoPointingAtAMissingFolderIsNotAReasonToRefuseTheWholeBackup() {
        // The mapper reads such a memo as unfiled; refusing would lose every other document.
        assertEquals(BackupValidationResult.Valid, validator.validate(sixteen(emptyList(), memoFolder = 999)))
    }

    @Test
    fun foldersInAFileFromBeforeSixteenAreIgnoredEvenIfBroken() {
        val broken = sixteen(listOf(FolderBackupDto(1, "A", 1, 1, 1))).copy(formatVersion = 15)
        assertEquals(BackupValidationResult.Valid, validator.validate(broken))
    }
}
