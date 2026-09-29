package io.github.cragcoffee.memoripple.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentPlaybackComposerTest {
    private val composer = CommentPlaybackComposer()

    @Test
    fun workOnlyKeepsExistingWorkOrderAndStyles() {
        val work = workTimeline()

        val result = composer.compose(work, userComments(), PlaybackContentMode.WORK_ONLY)

        assertEquals(work.items, result.items)
    }

    @Test
    fun userOnlySortsByPlaybackOrderInsteadOfCreationTime() {
        val result = composer.compose(
            PlaybackTimeline.Empty,
            listOf(
                user(3, "created newest but first", 300, 0),
                user(1, "created oldest but last", 100, 2),
                user(2, "middle", 200, 1),
            ),
            PlaybackContentMode.USER_ONLY,
        )

        assertEquals(
            listOf("created newest but first", "middle", "created oldest but last"),
            result.items.map { it.text },
        )
    }

    @Test
    fun userOnlyWithoutWorkUsesSevenHundredMillisecondIntervals() {
        val result = composer.compose(
            PlaybackTimeline.Empty,
            userComments(),
            PlaybackContentMode.USER_ONLY,
        )

        assertEquals(listOf(0L, 700L, 1_400L), result.items.map { it.startTimeMillis })
    }

    @Test
    fun userOnlyKeepsNormalAndPresetCommentsInPlaybackOrder() {
        val users = listOf(
            user(1, "A", 400, 0),
            user(2, "wwwwwwww", 300, 1),
            user(3, "ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!", 200, 2),
            user(4, "B", 100, 3),
        )

        val result = composer.compose(PlaybackTimeline.Empty, users, PlaybackContentMode.USER_ONLY)

        assertEquals(
            listOf("A", "wwwwwwww", "ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!", "B"),
            result.items.map { it.text },
        )
    }

    @Test
    fun bothDistributesUsersAcrossWorkTimeline() {
        val work = workTimeline(totalDurationMillis = 12_000)

        val result = composer.compose(work, userComments(), PlaybackContentMode.BOTH)
        val userStarts = result.items.filter { it.text.startsWith("user") }.map { it.startTimeMillis }

        assertEquals(listOf(3_000L, 6_000L, 9_000L), userStarts)
    }

    @Test
    fun bothKeepsWorkRelativeOrder() {
        val result = composer.compose(workTimeline(), userComments(), PlaybackContentMode.BOTH)

        assertEquals(
            listOf("work first", "work second"),
            result.items.filter { it.text.startsWith("work") }.map { it.text },
        )
    }

    @Test
    fun bothKeepsCustomUserRelativeOrder() {
        val users = listOf(
            user(1, "A", 100, 0),
            user(2, "B", 200, 3),
            user(3, "wwwwwwww", 300, 1),
            user(4, "ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!", 400, 2),
        )

        val result = composer.compose(workTimeline(), users, PlaybackContentMode.BOTH)

        assertEquals(
            listOf("A", "wwwwwwww", "ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!", "B"),
            result.items.filterNot { it.text.startsWith("work") }.map { it.text },
        )
    }

    @Test
    fun noUsersLeavesWorkPlaybackNormalInBothMode() {
        val work = workTimeline()

        val result = composer.compose(work, emptyList(), PlaybackContentMode.BOTH)

        assertEquals(work.items, result.items)
    }

    @Test
    fun userOnlyWorksWhenWorkTimelineIsEmpty() {
        val result = composer.compose(
            PlaybackTimeline.Empty,
            listOf(user(1, "comment", 100)),
            PlaybackContentMode.USER_ONLY,
        )

        assertEquals("comment", result.items.single().text)
        assertTrue(result.totalDurationMillis > 0)
    }

    @Test
    fun mixedItemsReceiveUniquePlaybackIdsForWidthAndLaneAllocation() {
        val result = composer.compose(workTimeline(), userComments(), PlaybackContentMode.BOTH)

        assertEquals(result.items.size, result.items.map { it.id }.toSet().size)
    }

    @Test
    fun mixedWorkAndUserItemsUseTheExistingLaneCollisionAllocator() {
        val composed = composer.compose(workTimeline(), userComments(), PlaybackContentMode.BOTH)
        val widths = composed.items.associate { it.id to 180f }
        val allocator = CommentLaneAllocator(safetyGapPx = 8f)

        val allocated = allocator.allocate(composed, widths, containerWidthPx = 1_080f)

        allocated.items.groupBy { it.laneIndex }.values.forEach { laneItems ->
            val chronological = laneItems.sortedBy { it.startTimeMillis }
            chronological.forEachIndexed { index, first ->
                chronological.drop(index + 1).forEach { second ->
                    assertTrue(
                        !allocator.collides(first, 180f, second, 180f, 1_080f),
                    )
                }
            }
        }
    }

    @Test
    fun oneThousandMixedMotionCommentsComposeWithoutDroppingOrderOrIds() {
        val users = (1L..1_000L).map { id ->
            UserCommentPlaybackSource(
                id = id,
                text = "comment-$id",
                createdAt = id,
                playbackOrder = (id - 1).toInt(),
                motion = io.github.cragcoffee.memoripple.domain.comments.CommentMotion(
                    speedRole = io.github.cragcoffee.memoripple.domain.comments.CommentSpeedRole
                        .entries[(id % 3).toInt()],
                    placementRole = io.github.cragcoffee.memoripple.domain.comments.CommentPlacementRole
                        .entries[(id % 4).toInt()],
                ),
            )
        }

        val result = composer.compose(PlaybackTimeline.Empty, users, PlaybackContentMode.USER_ONLY)

        assertEquals(1_000, result.items.size)
        assertEquals(1_000, result.items.map(PlaybackItem::id).toSet().size)
        assertEquals("comment-1", result.items.first().text)
        assertEquals("comment-1000", result.items.last().text)
    }

    private fun workTimeline(totalDurationMillis: Long = 12_000) = PlaybackTimeline(
        items = listOf(
            playbackItem(0, "work first", 500),
            playbackItem(1, "work second", 2_000),
        ),
        laneCount = 6,
        totalDurationMillis = totalDurationMillis,
    )

    private fun userComments() = listOf(
        user(1, "user one", 100),
        user(2, "user two", 200),
        user(3, "user three", 300),
    )

    private fun user(
        id: Long,
        text: String,
        createdAt: Long,
        playbackOrder: Int = (id - 1).toInt(),
    ) = UserCommentPlaybackSource(id, text, createdAt, playbackOrder)

    private fun playbackItem(id: Int, text: String, start: Long) = PlaybackItem(
        id = id,
        text = text,
        startTimeMillis = start,
        travelDurationMillis = 8_500,
        laneIndex = id,
        fontScale = 1f,
        opacity = 1f,
        emphasis = PlaybackEmphasis.NORMAL,
    )

    // ---- Release audit B01: the allocator requires chronological input, and 溜め / ×N
    // can push a line's start past the line after it. Every branch must leave the
    // composer sorted, and the whole pipe must survive the allocator without throwing.

    @Test
    fun aDelayedLineNoLongerCrashesTheOutlineOnlyPipe() {
        // Case A: 「- 遅い ...」 then 「- 早い」 — the 溜め start (920ms) outruns the next
        // line's (540ms), which used to reach the allocator out of order and throw.
        val planned = CommentPlaybackPlanner().plan(
            io.github.cragcoffee.memoripple.domain.WorkCommentParser()
                .parse("- 遅い ...\n- 早い"),
        )
        val composed = composer.compose(planned, emptyList(), PlaybackContentMode.WORK_ONLY)

        assertEquals(
            composed.items.map(PlaybackItem::startTimeMillis),
            composed.items.map(PlaybackItem::startTimeMillis).sorted(),
        )
        val allocated = CommentLaneAllocator(safetyGapPx = 8f).allocate(
            composed,
            composed.items.associate { it.id to 200f },
            containerWidthPx = 1_000f,
        )
        assertEquals(composed.items.size, allocated.items.size)
    }

    @Test
    fun aVolleyWhoseCopiesOvertakeTheNextLineStaysChronological() {
        // Case B: ×3 staggers copies 300ms apart while the cursor moves only 300ms per
        // line, so the last copy starts after the following line.
        val planned = CommentPlaybackPlanner().plan(
            io.github.cragcoffee.memoripple.domain.WorkCommentParser()
                .parse("- 弾幕 ×3\n- 次の行"),
        )
        val composed = composer.compose(planned, emptyList(), PlaybackContentMode.WORK_ONLY)

        assertEquals(
            composed.items.map(PlaybackItem::startTimeMillis),
            composed.items.map(PlaybackItem::startTimeMillis).sorted(),
        )
        CommentLaneAllocator(safetyGapPx = 8f).allocate(
            composed,
            composed.items.associate { it.id to 200f },
            containerWidthPx = 1_000f,
        )
    }

    @Test
    fun equalStartsKeepADeterministicIdOrder() {
        // Case C: ties break on id, so two runs of the same body compose identically.
        val work = PlaybackTimeline(
            items = listOf(
                workItem(id = 2, text = "b", start = 500),
                workItem(id = 1, text = "a", start = 500),
                workItem(id = 3, text = "c", start = 100),
            ),
            laneCount = 12,
            totalDurationMillis = 5_000,
        )
        val once = composer.compose(work, emptyList(), PlaybackContentMode.WORK_ONLY)
        val twice = composer.compose(work, emptyList(), PlaybackContentMode.WORK_ONLY)

        assertEquals(listOf(3, 1, 2), once.items.map(PlaybackItem::id))
        assertEquals(once.items, twice.items)
    }

    @Test
    fun bothBranchStillInterleavesChronologicallyWithDelayedWorkLines() {
        // Case D: the BOTH branch was already sorted; it must stay sorted with the same
        // tie rule when delayed work lines are in the mix.
        val planned = CommentPlaybackPlanner().plan(
            io.github.cragcoffee.memoripple.domain.WorkCommentParser()
                .parse("- 遅い ...\n- 早い"),
        )
        val composed = composer.compose(planned, userComments(), PlaybackContentMode.BOTH)

        assertEquals(
            composed.items.map(PlaybackItem::startTimeMillis),
            composed.items.map(PlaybackItem::startTimeMillis).sorted(),
        )
    }

    private fun workItem(id: Int, text: String, start: Long) = PlaybackItem(
        id = id,
        text = text,
        startTimeMillis = start,
        travelDurationMillis = 4_000,
        laneIndex = 0,
        fontScale = 1f,
        opacity = 1f,
        emphasis = PlaybackEmphasis.NORMAL,
    )
}
