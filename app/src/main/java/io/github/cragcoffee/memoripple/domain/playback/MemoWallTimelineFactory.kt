package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.domain.WorkCommentParser
import io.github.cragcoffee.memoripple.domain.WorkCommentScope
import io.github.cragcoffee.memoripple.domain.WorkLine
import io.github.cragcoffee.memoripple.domain.settings.AppSettings

/**
 * One stream for a whole wall of memos.
 *
 * A single memo played through is that memo talking. A wall played through one memo at a time would
 * be a queue. So the memos are taken in turn, a line each, and what crosses the screen is the
 * library speaking rather than any one page of it.
 */
class MemoWallTimelineFactory(
    private val parser: WorkCommentParser = WorkCommentParser(),
    private val planner: CommentPlaybackPlanner = CommentPlaybackPlanner(),
    private val settingsApplier: PlaybackSettingsApplier = PlaybackSettingsApplier(),
) {
    fun create(
        bodies: List<String>,
        scope: WorkCommentScope,
        appSettings: AppSettings = AppSettings.Default,
    ): PlaybackTimeline {
        val lines = interleave(bodies.map { parser.parse(scope, it) })
        if (lines.isEmpty()) return PlaybackTimeline.Empty
        return settingsApplier.apply(planner.plan(lines), appSettings)
    }

    /**
     * A line from each memo in turn, until every memo has run out.
     *
     * The stream stops at [MAX_ITEMS]. A wall can hold more lines than anyone would watch go past,
     * and a stream that never ends is not a stream but a screensaver.
     */
    fun interleave(perMemo: List<List<WorkLine>>): List<WorkLine> {
        if (perMemo.isEmpty()) return emptyList()
        val result = mutableListOf<WorkLine>()
        val longest = perMemo.maxOf(List<WorkLine>::size)
        for (index in 0 until longest) {
            perMemo.forEach { lines ->
                lines.getOrNull(index)?.let(result::add)
                if (result.size >= MAX_ITEMS) return result
            }
        }
        return result
    }

    companion object {
        /** As many lines as a person would watch cross a screen, and no more. */
        const val MAX_ITEMS = 120
    }
}
