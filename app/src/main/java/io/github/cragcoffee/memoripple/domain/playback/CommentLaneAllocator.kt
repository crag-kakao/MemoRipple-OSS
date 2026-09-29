package io.github.cragcoffee.memoripple.domain.playback

import kotlin.math.min

/** Resolves preferred lanes/times using measured pixel widths without depending on Compose. */
class CommentLaneAllocator(
    private val safetyGapPx: Float = DEFAULT_SAFETY_GAP_PX,
    private val laneBandResolver: CommentLaneBandResolver = CommentLaneBandResolver(),
) {
    /**
     * ニコニコ式の配置: a comment keeps its own moment. Lanes are tried from the top (or in
     * the order its band asks), and the first lane where nothing collides — the catch-up
     * mathematics in [collides] — takes it. When every lane is taken the comment is NOT
     * delayed: it lands on a pseudo-random lane and overlaps, which is exactly the crowded
     * look the video site calls 弾幕. The randomness is a hash of the item's id, so the same
     * timeline always lays out the same way.
     */
    fun allocate(
        timeline: PlaybackTimeline,
        renderWidthsPx: Map<Int, Float>,
        containerWidthPx: Float,
    ): PlaybackTimeline {
        if (timeline.items.isEmpty() || containerWidthPx <= 0f) return timeline

        val laneItems = List(timeline.laneCount) { mutableListOf<PlaybackItem>() }
        val allocated = timeline.items.map { item ->
            val width = renderWidthsPx.widthFor(item)
            val requestedAllowedLanes = when {
                item.allowedLaneIndices.isNotEmpty() -> item.allowedLaneIndices
                item.laneBand != null -> laneBandResolver.candidates(item.laneBand, timeline.laneCount)
                else -> emptyList()
            }
            val allowed = requestedAllowedLanes
                .filter { it in 0 until timeline.laneCount }
                .toSet()
            val orderedLanes = when (item.laneOrder) {
                PlaybackLaneOrder.DESCENDING -> (0 until timeline.laneCount).toList().asReversed()
                else -> (0 until timeline.laneCount).toList()
            }
            val candidateLaneIndices = if (requestedAllowedLanes.isEmpty()) {
                orderedLanes
            } else {
                orderedLanes.filter { it in allowed }.ifEmpty {
                    listOf(requestedAllowedLanes.first().coerceIn(0, timeline.laneCount - 1))
                }
            }
            fun laneIsFree(lane: Int): Boolean {
                val candidate = item.copy(laneIndex = lane)
                return laneItems[lane].none { previous ->
                    collides(
                        first = previous,
                        firstWidthPx = renderWidthsPx.widthFor(previous),
                        second = candidate,
                        secondWidthPx = width,
                        containerWidthPx = containerWidthPx,
                    )
                }
            }
            val lane = candidateLaneIndices.firstOrNull(::laneIsFree)
                ?: candidateLaneIndices[hashLane(item.id, candidateLaneIndices.size)]
            item.copy(
                laneIndex = lane,
                renderWidthPx = width,
            ).also { resolved ->
                laneItems[lane] += resolved
            }
        }

        return timeline.copy(
            items = allocated,
            totalDurationMillis = allocated.maxOf { it.startTimeMillis + it.travelDurationMillis },
        )
    }

    fun collides(
        first: PlaybackItem,
        firstWidthPx: Float,
        second: PlaybackItem,
        secondWidthPx: Float,
        containerWidthPx: Float,
    ): Boolean {
        if (first.laneIndex != second.laneIndex) return false
        require(second.startTimeMillis >= first.startTimeMillis) {
            "Collision checks require chronological items"
        }
        // A 完全固定 or ループ comment never leaves, so nothing that comes after it can
        // wait for it: its lane is occupied for the rest of the reading.
        val firstEnd = endOf(first)
        if (second.startTimeMillis >= firstEnd) return false
        if (first.behavior == ResolvedPlaybackBehavior.FIXED ||
            second.behavior == ResolvedPlaybackBehavior.FIXED
        ) {
            return true
        }
        if (first.flowDirection != second.flowDirection) return true

        val firstSpeed = speedPxPerMillis(first, firstWidthPx, containerWidthPx)
        val secondSpeed = speedPxPerMillis(second, secondWidthPx, containerWidthPx)
        val gapAtSecondStart = firstSpeed *
            (second.startTimeMillis - first.startTimeMillis) - firstWidthPx
        if (gapAtSecondStart < safetyGapPx) return true
        if (secondSpeed <= firstSpeed) return false

        val overlapEnd = min(firstEnd, endOf(second))
        val gapAtOverlapEnd = gapAtSecondStart -
            (secondSpeed - firstSpeed) * (overlapEnd - second.startTimeMillis)
        return gapAtOverlapEnd < safetyGapPx
    }

    /** When an item's lane frees up — never, for one that has no end of its own. */
    private fun endOf(item: PlaybackItem): Long =
        if (item.endless) Long.MAX_VALUE / 4 else item.startTimeMillis + item.travelDurationMillis

    /** Deterministic "random" for the overflow lane: the same id always lands the same row. */
    private fun hashLane(id: Int, laneCount: Int): Int {
        val mixed = (id * -1640531527) ushr 16
        return mixed.mod(laneCount.coerceAtLeast(1))
    }

    private fun speedPxPerMillis(
        item: PlaybackItem,
        widthPx: Float,
        containerWidthPx: Float,
    ): Float = (containerWidthPx + widthPx) / item.travelDurationMillis

    private fun Map<Int, Float>.widthFor(item: PlaybackItem): Float =
        get(item.id)?.coerceAtLeast(MIN_COMMENT_WIDTH_PX) ?: MIN_COMMENT_WIDTH_PX

    private companion object {
        const val DEFAULT_SAFETY_GAP_PX = 8f
        const val MIN_COMMENT_WIDTH_PX = 1f
    }
}
