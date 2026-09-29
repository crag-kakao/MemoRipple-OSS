package io.github.cragcoffee.memoripple

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.cragcoffee.memoripple.domain.playback.PlaybackEmphasis
import io.github.cragcoffee.memoripple.domain.playback.PlaybackItem
import io.github.cragcoffee.memoripple.domain.playback.PlaybackSettingsApplier
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.settings.CommentSize
import io.github.cragcoffee.memoripple.ui.playback.CommentTextMetrics
import io.github.cragcoffee.memoripple.ui.playback.measureCommentTextMetricsPx
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CommentTextMeasurementTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun japaneseEndGlyphsAndStyledCommentsReceiveRenderSafetyMargin() {
        val items = listOf(
            item(0, "最終的にはカフェこそ自分の居場所だと気づく"),
            item(1, "これはひらがなでおわるながいぶんしょうになる"),
            item(2, "主人公が最後に選んだ本当の居場所は珈琲店"),
            item(3, "ここで本当に完成する！"),
            item(4, "この選択で本当にいいの？"),
            item(5, "重要事項", emphasis = PlaybackEmphasis.NORMAL),
            item(6, "重要事項", emphasis = PlaybackEmphasis.STRONG),
            item(7, "見出し倍率", fontScale = 1f),
            item(8, "見出し倍率", fontScale = 1.25f),
            item(9, "子項目の縮小", fontScale = 0.84f),
            item(10, "ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!"),
            item(11, "88888888"),
            item(12, "！？！？！？"),
            item(13, "長文の大きな強調コメントでも最後まで気づく", 1.15f, PlaybackEmphasis.STRONG),
        )
        val result = AtomicReference<Map<Int, CommentTextMetrics>>()

        composeRule.setContent {
            val textMeasurer = rememberTextMeasurer()
            val density = LocalDensity.current
            SideEffect {
                result.set(
                    measureCommentTextMetricsPx(
                        timeline = PlaybackTimeline(items, laneCount = 6, totalDurationMillis = 1),
                        textMeasurer = textMeasurer,
                        density = density,
                    ),
                )
            }
        }
        composeRule.waitForIdle()

        val metrics = result.get()
        assertEquals(items.size, metrics.size)
        metrics.values.forEach { measurement ->
            assertTrue(measurement.measuredTextWidthPx > 0f)
            assertTrue(measurement.renderWidthPx > ceil(measurement.measuredTextWidthPx))
            assertTrue(measurement.measuredTextHeightPx > 0f)
            assertTrue(measurement.renderHeightPx > measurement.measuredTextHeightPx)
        }
        assertTrue(metrics.getValue(6).measuredTextWidthPx >= metrics.getValue(5).measuredTextWidthPx)
        assertTrue(metrics.getValue(8).measuredTextWidthPx > metrics.getValue(7).measuredTextWidthPx)
        listOf(10, 11, 12).forEach { id ->
            val measurement = metrics.getValue(id)
            assertTrue(measurement.renderWidthPx > ceil(measurement.measuredTextWidthPx))
        }
    }

    @Test
    fun presetEndGlyphRemainsInsideRenderWidthAtEveryGlobalSize() {
        val baseTimeline = PlaybackTimeline(
            items = listOf(item(100, "ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!")),
            laneCount = 1,
            totalDurationMillis = 8_500,
        )
        val configured = CommentSize.entries.associateWith { size ->
            PlaybackSettingsApplier().apply(baseTimeline, AppSettings(commentSize = size))
        }
        val results = AtomicReference<Map<CommentSize, CommentTextMetrics>>()

        composeRule.setContent {
            val textMeasurer = rememberTextMeasurer()
            val density = LocalDensity.current
            SideEffect {
                results.set(
                    configured.mapValues { (_, timeline) ->
                        measureCommentTextMetricsPx(timeline, textMeasurer, density)
                            .getValue(100)
                    },
                )
            }
        }
        composeRule.waitForIdle()

        val metrics = results.get()
        CommentSize.entries.forEach { size ->
            val measurement = metrics.getValue(size)
            assertTrue(measurement.renderWidthPx > ceil(measurement.measuredTextWidthPx))
        }
        assertTrue(
            metrics.getValue(CommentSize.SMALL).measuredTextWidthPx <
                metrics.getValue(CommentSize.STANDARD).measuredTextWidthPx,
        )
        assertTrue(
            metrics.getValue(CommentSize.STANDARD).measuredTextWidthPx <
                metrics.getValue(CommentSize.LARGE).measuredTextWidthPx,
        )
    }

    private fun item(
        id: Int,
        text: String,
        fontScale: Float = 1f,
        emphasis: PlaybackEmphasis = PlaybackEmphasis.NORMAL,
    ) = PlaybackItem(
        id = id,
        text = text,
        startTimeMillis = id * 100L,
        travelDurationMillis = 8_500,
        laneIndex = id.mod(6),
        fontScale = fontScale,
        opacity = 1f,
        emphasis = emphasis,
    )
}
