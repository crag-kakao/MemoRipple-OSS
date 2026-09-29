package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.domain.comments.CommentMotion
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowDirection
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowEffect
import io.github.cragcoffee.memoripple.domain.comments.CommentPlacementRole
import io.github.cragcoffee.memoripple.domain.comments.CommentSpeedRole
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.settings.PlaybackSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class UserCommentMotionPlaybackTest {
    private val mapper = UserCommentPlaybackMapper()
    private val applier = PlaybackSettingsApplier()

    @Test
    fun perCommentSpeedChangesTravelNotScheduledStart() {
        val items = CommentSpeedRole.entries.map { speed ->
            mapper.map(source(speed = speed), speed.ordinal, 2_000, 1)
        }

        assertTrue(items[0].travelDurationMillis > items[1].travelDurationMillis)
        assertTrue(items[1].travelDurationMillis > items[2].travelDurationMillis)
        assertEquals(listOf(2_000L, 2_000L, 2_000L), items.map { it.startTimeMillis })
    }

    @Test
    fun everyGlobalAndPerCommentSpeedCombinationComposesVelocity() {
        PlaybackSpeed.entries.forEach { global ->
            CommentSpeedRole.entries.forEach { perComment ->
                val mapped = mapper.map(source(speed = perComment), 1, 400, 0)
                val resolved = applier.apply(
                    PlaybackTimeline(listOf(mapped), 1, 20_000),
                    AppSettings(playbackSpeed = global),
                ).items.single()
                val actualMultiplier = 4_000.0 / resolved.travelDurationMillis
                val expected = global.velocityMultiplier * perComment.velocityMultiplier
                assertEquals(expected.toDouble(), actualMultiplier, 0.002)
                assertEquals(400L, resolved.startTimeMillis)
            }
        }
    }

    @Test
    fun placementMapsToSourceIndependentLaneBand() {
        assertEquals(null, mapper.map(source(placement = CommentPlacementRole.AUTO), 0, 0, 0).laneBand)
        assertEquals(
            PlaybackLaneBand.TOP,
            mapper.map(source(placement = CommentPlacementRole.TOP), 0, 0, 0).laneBand,
        )
        assertEquals(
            PlaybackLaneBand.MIDDLE,
            mapper.map(source(placement = CommentPlacementRole.MIDDLE), 0, 0, 0).laneBand,
        )
        assertEquals(
            PlaybackLaneBand.BOTTOM,
            mapper.map(source(placement = CommentPlacementRole.BOTTOM), 0, 0, 0).laneBand,
        )
    }

    @Test
    fun fixedModeResolvesBehaviorBandOrderAndIgnoresPerCommentSpeed() {
        val slowTop = mapper.map(
            source(speed = CommentSpeedRole.SLOW, mode = CommentMotionMode.FIXED_TOP),
            0,
            200,
            4,
        )
        val fastBottom = mapper.map(
            source(speed = CommentSpeedRole.FAST, mode = CommentMotionMode.FIXED_BOTTOM),
            1,
            200,
            1,
        )

        assertEquals(ResolvedPlaybackBehavior.FIXED, slowTop.behavior)
        assertEquals(PlaybackLaneBand.TOP, slowTop.laneBand)
        assertEquals(PlaybackLaneOrder.ASCENDING, slowTop.laneOrder)
        assertEquals(3_000L, slowTop.travelDurationMillis)
        assertEquals(PlaybackLaneBand.BOTTOM, fastBottom.laneBand)
        assertEquals(PlaybackLaneOrder.DESCENDING, fastBottom.laneOrder)
        assertEquals(slowTop.travelDurationMillis, fastBottom.travelDurationMillis)
    }

    @Test
    fun fixedDwellComposesOnlyWithGlobalPlaybackSpeed() {
        val fixed = mapper.map(
            source(speed = CommentSpeedRole.FAST, mode = CommentMotionMode.FIXED_TOP),
            0,
            0,
            0,
        )
        PlaybackSpeed.entries.forEach { global ->
            val resolved = applier.apply(
                PlaybackTimeline(listOf(fixed), 1, fixed.travelDurationMillis),
                AppSettings(playbackSpeed = global),
            ).items.single()
            assertEquals(
                (3_000 / global.velocityMultiplier).roundToLong(),
                resolved.travelDurationMillis,
            )
        }
    }

    @Test
    fun directionAndEffectResolveWithoutChangingScheduleOrHorizontalDuration() {
        val rtl = mapper.map(source(), 0, 2_000, 0)
        val ltrWave = mapper.map(
            source(
                direction = CommentFlowDirection.LEFT_TO_RIGHT,
                effect = CommentFlowEffect.WAVE,
            ),
            1,
            2_000,
            0,
        )

        assertEquals(ResolvedFlowDirection.RIGHT_TO_LEFT, rtl.flowDirection)
        assertEquals(ResolvedFlowEffect.STRAIGHT, rtl.flowEffect)
        assertEquals(ResolvedFlowDirection.LEFT_TO_RIGHT, ltrWave.flowDirection)
        assertEquals(ResolvedFlowEffect.WAVE, ltrWave.flowEffect)
        assertEquals(rtl.startTimeMillis, ltrWave.startTimeMillis)
        assertEquals(rtl.travelDurationMillis, ltrWave.travelDurationMillis)
    }

    @Test
    fun fixedPreservesStoredFlowChoicesButResolvesAsStatic() {
        val source = source(
            mode = CommentMotionMode.FIXED_TOP,
            direction = CommentFlowDirection.LEFT_TO_RIGHT,
            effect = CommentFlowEffect.WAVE,
        )
        val fixed = mapper.map(source, 0, 0, 0)

        assertEquals(ResolvedPlaybackBehavior.FIXED, fixed.behavior)
        assertEquals(ResolvedFlowDirection.LEFT_TO_RIGHT, fixed.flowDirection)
        assertEquals(ResolvedFlowEffect.WAVE, fixed.flowEffect)
    }

    private fun source(
        speed: CommentSpeedRole = CommentSpeedRole.STANDARD,
        placement: CommentPlacementRole = CommentPlacementRole.AUTO,
        mode: CommentMotionMode = CommentMotionMode.FLOW,
        direction: CommentFlowDirection = CommentFlowDirection.RIGHT_TO_LEFT,
        effect: CommentFlowEffect = CommentFlowEffect.STRAIGHT,
    ) = UserCommentPlaybackSource(
        id = 1,
        text = "comment",
        createdAt = 100,
        playbackOrder = 0,
        motion = CommentMotion(speed, placement, mode, direction, effect),
    )
}
