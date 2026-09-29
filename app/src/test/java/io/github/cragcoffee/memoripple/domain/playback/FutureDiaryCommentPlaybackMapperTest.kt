package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.domain.comments.CommentAppearance
import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import io.github.cragcoffee.memoripple.domain.comments.CommentEmphasisRole
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.comments.CommentSizeRole
import io.github.cragcoffee.memoripple.domain.diary.FutureCommentExpression
import io.github.cragcoffee.memoripple.domain.diary.RevealedFutureDiaryComment
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.settings.CommentSize
import io.github.cragcoffee.memoripple.domain.settings.PlaybackSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FutureDiaryCommentPlaybackMapperTest {
    @Test
    fun revealedCommentMapsToOneNormalRightToLeftPlaybackItem() {
        val timeline = FutureDiaryCommentPlaybackMapper().map(
            RevealedFutureDiaryComment(42, 7, "未来からの本文", 1_000, 2_000),
        )

        assertEquals(1, timeline.items.size)
        assertEquals("未来からの本文", timeline.items.single().text)
        assertEquals(PlaybackEmphasis.NORMAL, timeline.items.single().emphasis)
        assertEquals(ResolvedPlaybackBehavior.FLOW, timeline.items.single().behavior)
        assertEquals(1, timeline.laneCount)
        assertTrue(timeline.totalDurationMillis > timeline.items.single().startTimeMillis)
    }

    @Test
    fun appearanceAndFixedTopMapToSharedPlaybackItem() {
        val expression = FutureCommentExpression(
            appearance = CommentAppearance(
                CommentColorRole.PINK,
                CommentSizeRole.LARGE,
                CommentEmphasisRole.STRONG,
            ),
            motionMode = CommentMotionMode.FIXED_TOP,
        )

        val item = FutureDiaryCommentPlaybackMapper().map(
            RevealedFutureDiaryComment(42, 7, "未来", 1_000, 2_000, expression = expression),
        ).items.single()

        assertEquals(CommentColorRole.PINK, item.colorRole)
        assertEquals(1.45f, item.fontScale, 0.0001f)
        assertEquals(PlaybackEmphasis.STRONG, item.emphasis)
        assertEquals(ResolvedPlaybackBehavior.FIXED, item.behavior)
        assertEquals(PlaybackLaneBand.TOP, item.laneBand)
        assertEquals(PlaybackLaneOrder.ASCENDING, item.laneOrder)
    }

    @Test
    fun fixedBottomMapsToHardBottomBand() {
        val expression = FutureCommentExpression(motionMode = CommentMotionMode.FIXED_BOTTOM)

        val timeline = FutureDiaryCommentPlaybackMapper().map(
            RevealedFutureDiaryComment(42, 7, "未来", 1_000, 2_000, expression = expression),
        )

        assertEquals(ResolvedPlaybackBehavior.FIXED, timeline.items.single().behavior)
        assertEquals(PlaybackLaneBand.BOTTOM, timeline.items.single().laneBand)
        assertEquals(PlaybackLaneOrder.DESCENDING, timeline.items.single().laneOrder)
        assertTrue(timeline.laneCount > 1)
    }

    @Test
    fun oversizedFixedFallbackUsesFlowWithoutMutatingPersistedMode() {
        val mapper = FutureDiaryCommentPlaybackMapper()
        val comment = RevealedFutureDiaryComment(
            42,
            7,
            "長い未来コメント",
            1_000,
            2_000,
            expression = FutureCommentExpression(motionMode = CommentMotionMode.FIXED_TOP),
        )
        val fixed = mapper.map(comment)

        val fallback = mapper.fallbackFixedToFlow(fixed)

        assertEquals(CommentMotionMode.FIXED_TOP, comment.expression.motionMode)
        assertEquals(ResolvedFlowDirection.RIGHT_TO_LEFT, fixed.items.single().flowDirection)
        assertEquals(ResolvedFlowEffect.STRAIGHT, fixed.items.single().flowEffect)
        assertEquals(ResolvedPlaybackBehavior.FIXED, fixed.items.single().behavior)
        assertEquals(ResolvedPlaybackBehavior.FLOW, fallback.items.single().behavior)
        assertEquals(null, fallback.items.single().laneBand)
        assertTrue(
            fallback.items.single().travelDurationMillis >
                fixed.items.single().travelDurationMillis,
        )
    }

    @Test
    fun initialPresentationUsesContextDelay() {
        val timeline = FutureDiaryCommentPlaybackMapper().map(
            RevealedFutureDiaryComment(42, 7, "未来からの本文", 1_000, 2_000),
            startDelayMillis = FutureDiaryCommentPlaybackMapper.INITIAL_CONTEXT_DELAY_MILLIS,
        )

        assertEquals(700L, timeline.items.single().startTimeMillis)
    }

    @Test
    fun futurePlaybackUsesGlobalSpeedAndSizeWithoutChangingContextDelay() {
        val mapper = FutureDiaryCommentPlaybackMapper()
        val source = mapper.map(
            RevealedFutureDiaryComment(42, 7, "未来からの本文", 1_000, 2_000),
            startDelayMillis = FutureDiaryCommentPlaybackMapper.INITIAL_CONTEXT_DELAY_MILLIS,
        )
        val configured = PlaybackSettingsApplier().apply(
            source,
            AppSettings(
                playbackSpeed = PlaybackSpeed.FAST,
                commentSize = CommentSize.LARGE,
            ),
        )

        assertEquals(700L, configured.items.single().startTimeMillis)
        assertTrue(
            configured.items.single().travelDurationMillis <
                source.items.single().travelDurationMillis,
        )
        assertEquals(1.15f, configured.items.single().fontScale, 0.0001f)
    }
}
