package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole

enum class PlaybackEmphasis { NORMAL, STRONG }
enum class ResolvedPlaybackBehavior { FLOW, FIXED }
enum class ResolvedFlowDirection { RIGHT_TO_LEFT, LEFT_TO_RIGHT }
enum class ResolvedFlowEffect { STRAIGHT, WAVE, BLINK }
enum class PlaybackLaneOrder { PREFERRED, ASCENDING, DESCENDING }
enum class PlaybackValidationIssue { FIXED_COMMENT_DOES_NOT_FIT }

/** Final, source-agnostic rendering information for one moving comment. */
data class PlaybackItem(
    val id: Int,
    val text: String,
    val startTimeMillis: Long,
    val travelDurationMillis: Long,
    val laneIndex: Int,
    val fontScale: Float,
    val opacity: Float,
    val emphasis: PlaybackEmphasis,
    val colorRole: CommentColorRole = CommentColorRole.DEFAULT,
    val renderWidthPx: Float = 0f,
    val renderHeightPx: Float = 0f,
    val laneBand: PlaybackLaneBand? = null,
    val allowedLaneIndices: List<Int> = emptyList(),
    val behavior: ResolvedPlaybackBehavior = ResolvedPlaybackBehavior.FLOW,
    val laneOrder: PlaybackLaneOrder = PlaybackLaneOrder.PREFERRED,
    val flowDirection: ResolvedFlowDirection = ResolvedFlowDirection.RIGHT_TO_LEFT,
    val flowEffect: ResolvedFlowEffect = ResolvedFlowEffect.STRAIGHT,
    val waveAmplitudePx: Float = 0f,
    val waveCycles: Float = DEFAULT_WAVE_CYCLES,
    /**
     * 終わらないコメント: a 完全固定 line stays where it is pinned, and a ループ line sets
     * off again the moment it has crossed. Either way the item has no last frame of its
     * own — it lives until playback is stopped, and [travelDurationMillis] describes one
     * crossing (or one holding) rather than the whole of its life.
     */
    val endless: Boolean = false,
    /**
     * 長いコメントを読みやすくする may stretch this item's crossing. False where the
     * writer said how fast the line flies (←← / ←←← 行末修飾子): an explicit speed always
     * outranks the automatic reading aid.
     */
    val readabilityAdjustable: Boolean = true,
)

const val DEFAULT_WAVE_CYCLES = 2f

data class PlaybackTimeline(
    val items: List<PlaybackItem>,
    val laneCount: Int,
    val totalDurationMillis: Long,
    val laneHeightPx: Float = 0f,
    val validationIssue: PlaybackValidationIssue? = null,
) {
    /** True when something on this timeline never ends by itself. */
    val hasEndless: Boolean get() = items.any(PlaybackItem::endless)

    companion object {
        val Empty = PlaybackTimeline(emptyList(), laneCount = 1, totalDurationMillis = 0)
    }
}
