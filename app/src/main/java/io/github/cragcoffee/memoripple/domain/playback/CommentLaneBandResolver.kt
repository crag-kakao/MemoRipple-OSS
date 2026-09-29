package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.domain.comments.CommentPlacementRole
import kotlin.math.abs

enum class PlaybackLaneBand { TOP, MIDDLE, BOTTOM }

/** Maps a surface-relative placement to hard lane candidates after lane fitting. */
class CommentLaneBandResolver {
    fun candidates(placement: CommentPlacementRole, totalLaneCount: Int): List<Int> =
        when (placement) {
            CommentPlacementRole.AUTO -> (0 until totalLaneCount.coerceAtLeast(0)).toList()
            CommentPlacementRole.TOP -> candidates(PlaybackLaneBand.TOP, totalLaneCount)
            CommentPlacementRole.MIDDLE -> candidates(PlaybackLaneBand.MIDDLE, totalLaneCount)
            CommentPlacementRole.BOTTOM -> candidates(PlaybackLaneBand.BOTTOM, totalLaneCount)
        }

    fun candidates(band: PlaybackLaneBand, totalLaneCount: Int): List<Int> {
        if (totalLaneCount <= 0) return emptyList()
        if (totalLaneCount == 1) return listOf(0)
        val matches = (0 until totalLaneCount).filter { index ->
            val center = (index + 0.5) / totalLaneCount
            when (band) {
                PlaybackLaneBand.TOP -> center < ONE_THIRD
                PlaybackLaneBand.MIDDLE -> center >= ONE_THIRD && center < TWO_THIRDS
                PlaybackLaneBand.BOTTOM -> center >= TWO_THIRDS
            }
        }
        if (matches.isNotEmpty()) return matches
        val target = when (band) {
            PlaybackLaneBand.TOP -> ONE_SIXTH
            PlaybackLaneBand.MIDDLE -> ONE_HALF
            PlaybackLaneBand.BOTTOM -> FIVE_SIXTHS
        }
        return listOf(
            (0 until totalLaneCount).minBy { index ->
                abs((index + 0.5) / totalLaneCount - target)
            },
        )
    }

    companion object {
        fun bandFor(placement: CommentPlacementRole): PlaybackLaneBand? = when (placement) {
            CommentPlacementRole.AUTO -> null
            CommentPlacementRole.TOP -> PlaybackLaneBand.TOP
            CommentPlacementRole.MIDDLE -> PlaybackLaneBand.MIDDLE
            CommentPlacementRole.BOTTOM -> PlaybackLaneBand.BOTTOM
        }

        private const val ONE_SIXTH = 1.0 / 6.0
        private const val ONE_THIRD = 1.0 / 3.0
        private const val ONE_HALF = 1.0 / 2.0
        private const val TWO_THIRDS = 2.0 / 3.0
        private const val FIVE_SIXTHS = 5.0 / 6.0
    }
}
