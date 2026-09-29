package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.settings.CommentSize
import io.github.cragcoffee.memoripple.domain.settings.PlaybackSpeed
import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoPlaybackTimelineFactoryTest {
    private val factory = MemoPlaybackTimelineFactory()
    private val comments = listOf(
        MemoCommentEntity(1, 10, "user first", 200, 0),
        MemoCommentEntity(2, 10, "user second", 100, 1),
    )

    @Test
    fun workUserAndBothReuseTheSameExistingPipelines() {
        val work = factory.create("# Heading\n- Item", comments, PlaybackContentMode.WORK_ONLY, AppSettings.Default)
        val user = factory.create("# Heading\n- Item", comments, PlaybackContentMode.USER_ONLY, AppSettings.Default)
        val both = factory.create("# Heading\n- Item", comments, PlaybackContentMode.BOTH, AppSettings.Default)

        assertEquals(listOf("Heading", "Item"), work.items.map { it.text })
        assertEquals(listOf("user first", "user second"), user.items.map { it.text })
        assertEquals(work.items.size + user.items.size, both.items.size)
    }

    @Test
    fun overlaySnapshotReceivesExistingSpeedAndSizeSettings() {
        val standard = factory.create(
            "- Item",
            emptyList(),
            PlaybackContentMode.WORK_ONLY,
            AppSettings.Default,
        ).items.single()
        val configured = factory.create(
            "- Item",
            emptyList(),
            PlaybackContentMode.WORK_ONLY,
            AppSettings(playbackSpeed = PlaybackSpeed.FAST, commentSize = CommentSize.LARGE),
        ).items.single()

        assertTrue(configured.travelDurationMillis < standard.travelDurationMillis)
        assertTrue(configured.fontScale > standard.fontScale)
    }

    @Test
    fun userAppearanceCombinesWithGlobalSizeWithoutChangingTimingOrWorkStyle() {
        val styled = MemoCommentEntity(
            id = 1,
            memoId = 10,
            text = "強い反応",
            createdAt = 100,
            playbackOrder = 0,
            appearanceColor = "red",
            appearanceSize = "large",
            appearanceEmphasis = "strong",
        )
        val standard = factory.create(
            "! work",
            listOf(styled),
            PlaybackContentMode.BOTH,
            AppSettings.Default,
        )
        val globallyLarge = factory.create(
            "! work",
            listOf(styled),
            PlaybackContentMode.BOTH,
            AppSettings(commentSize = CommentSize.LARGE),
        )
        val user = standard.items.single { it.text == "強い反応" }
        val work = standard.items.single { it.text == "work" }
        assertEquals(CommentColorRole.RED, user.colorRole)
        assertEquals(PlaybackEmphasis.STRONG, user.emphasis)
        assertEquals(1.45f, user.fontScale, 0.001f)
        assertEquals(CommentColorRole.YELLOW, work.colorRole)
        assertTrue(globallyLarge.items.single { it.text == "強い反応" }.fontScale > user.fontScale)
        assertEquals(user.startTimeMillis, globallyLarge.items.single { it.text == "強い反応" }.startTimeMillis)
    }

    @Test
    fun userAppearanceAndMotionCombineWhileWorkTimingAndLanesRemainUnchanged() {
        val styledMotion = MemoCommentEntity(
            id = 1,
            memoId = 10,
            text = "強く速い反応",
            createdAt = 100,
            playbackOrder = 0,
            appearanceColor = "pink",
            appearanceSize = "large",
            appearanceEmphasis = "strong",
            motionSpeed = "fast",
            motionPlacement = "top",
            flowDirection = "ltr",
            flowEffect = "wave",
        )
        val workOnly = factory.create(
            "# Heading\n- Item",
            emptyList(),
            PlaybackContentMode.WORK_ONLY,
            AppSettings.Default,
        )
        val both = factory.create(
            "# Heading\n- Item",
            listOf(styledMotion),
            PlaybackContentMode.BOTH,
            AppSettings.Default,
        )
        val user = both.items.single { it.text == "強く速い反応" }
        val bothWork = both.items.filter { it.text != "強く速い反応" }

        assertEquals(CommentColorRole.PINK, user.colorRole)
        assertEquals(PlaybackEmphasis.STRONG, user.emphasis)
        assertEquals(PlaybackLaneBand.TOP, user.laneBand)
        assertEquals(ResolvedFlowDirection.LEFT_TO_RIGHT, user.flowDirection)
        assertEquals(ResolvedFlowEffect.WAVE, user.flowEffect)
        assertTrue(user.travelDurationMillis < 4_000L)
        assertEquals(workOnly.items, bothWork)
    }

    @Test
    fun unknownLocalMotionValuesUseStandardAutoFallback() {
        val unknown = comments.first().copy(
            motionSpeed = "warp",
            motionPlacement = "ceiling",
            motionMode = "floating",
            flowDirection = "up",
            flowEffect = "zigzag",
        )
        val result = factory.create(
            "",
            listOf(unknown),
            PlaybackContentMode.USER_ONLY,
            AppSettings.Default,
        ).items.single()

        assertEquals(4_000L, result.travelDurationMillis)
        assertEquals(null, result.laneBand)
        assertEquals(ResolvedPlaybackBehavior.FLOW, result.behavior)
        assertEquals(ResolvedFlowDirection.RIGHT_TO_LEFT, result.flowDirection)
        assertEquals(ResolvedFlowEffect.STRAIGHT, result.flowEffect)
    }
}
