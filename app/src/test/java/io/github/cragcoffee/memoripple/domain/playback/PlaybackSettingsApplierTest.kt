package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.domain.WorkCommentParser
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.settings.CommentSize
import io.github.cragcoffee.memoripple.domain.settings.PlaybackSpeed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSettingsApplierTest {
    private val applier = PlaybackSettingsApplier()

    @Test
    fun speedMultiplierMakesFastShorterAndSlowLongerThanStandard() {
        val source = timeline(item(0, duration = 8_500))
        val standard = applier.apply(source, settings(speed = PlaybackSpeed.STANDARD))
        val fast = applier.apply(source, settings(speed = PlaybackSpeed.FAST))
        val slow = applier.apply(source, settings(speed = PlaybackSpeed.SLOW))

        assertTrue(fast.items.single().travelDurationMillis < standard.items.single().travelDurationMillis)
        assertTrue(slow.items.single().travelDurationMillis > standard.items.single().travelDurationMillis)
    }

    @Test
    fun globalSizeComposesWithExistingHeadingAndDepthScale() {
        val source = CommentPlaybackPlanner().plan(
            WorkCommentParser().parse("# 見出し\n- 親\n  - 子"),
        )
        val standard = applier.apply(source, settings(size = CommentSize.STANDARD))
        val large = applier.apply(source, settings(size = CommentSize.LARGE))

        assertTrue(large.items[0].fontScale > standard.items[0].fontScale)
        assertTrue(large.items[0].fontScale > large.items[1].fontScale)
        assertTrue(large.items[2].fontScale < large.items[1].fontScale)
    }

    @Test
    fun fastLargeAndSlowSmallMixedTimelinesRemainCollisionFree() {
        val work = CommentPlaybackPlanner().plan(
            WorkCommentParser().parse(
                "# とても長い見出しコメントです\n- 長いWork Comment\n  - 子項目も長文です",
            ),
        )
        val mixed = CommentPlaybackComposer().compose(
            workTimeline = work,
            userComments = listOf(
                UserCommentPlaybackSource(1, "通常User Comment", 100, 0),
                UserCommentPlaybackSource(2, "ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!", 200, 1),
            ),
            contentMode = PlaybackContentMode.BOTH,
        )

        listOf(
            settings(PlaybackSpeed.FAST, CommentSize.LARGE),
            settings(PlaybackSpeed.SLOW, CommentSize.SMALL),
        ).forEach { appSettings ->
            val configured = applier.apply(mixed, appSettings)
            val widths = configured.items.associate { item ->
                item.id to (item.text.length * 28f * item.fontScale + 28f)
            }
            val allocator = CommentLaneAllocator(safetyGapPx = 12f)
            val allocated = allocator.allocate(configured, widths, containerWidthPx = 420f)

            allocated.items.groupBy(PlaybackItem::laneIndex).values.forEach { lane ->
                lane.forEachIndexed { index, first ->
                    lane.drop(index + 1).forEach { second ->
                        assertFalse(
                            allocator.collides(
                                first,
                                widths.getValue(first.id),
                                second,
                                widths.getValue(second.id),
                                420f,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun settings(
        speed: PlaybackSpeed = PlaybackSpeed.STANDARD,
        size: CommentSize = CommentSize.STANDARD,
    ) = AppSettings(playbackSpeed = speed, commentSize = size)

    private fun timeline(vararg items: PlaybackItem) = PlaybackTimeline(
        items = items.toList(),
        laneCount = 1,
        totalDurationMillis = items.maxOf { it.startTimeMillis + it.travelDurationMillis },
    )

    private fun item(id: Int, duration: Long) = PlaybackItem(
        id = id,
        text = "comment",
        startTimeMillis = 0,
        travelDurationMillis = duration,
        laneIndex = 0,
        fontScale = 1f,
        opacity = 1f,
        emphasis = PlaybackEmphasis.NORMAL,
    )
}
