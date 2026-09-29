package io.github.cragcoffee.memoripple.overlay

import io.github.cragcoffee.memoripple.data.MemoCommentRepository
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.data.SettingsRepository
import io.github.cragcoffee.memoripple.domain.WorkCommentScope
import io.github.cragcoffee.memoripple.domain.playback.MemoPlaybackTimelineFactory
import io.github.cragcoffee.memoripple.domain.playback.MemoWallTimelineFactory
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import kotlinx.coroutines.flow.first
import io.github.cragcoffee.memoripple.domain.memos.MemoLifecycleState
import io.github.cragcoffee.memoripple.domain.memos.lifecycleState

/** Reads a one-time Memo/Comment/Settings snapshot after the foreground service has started. */
class OverlayPlaybackSnapshotProvider(
    private val memoRepository: MemoRepository,
    private val commentRepository: MemoCommentRepository,
    private val settingsRepository: SettingsRepository,
    private val timelineFactory: MemoPlaybackTimelineFactory = MemoPlaybackTimelineFactory(),
    private val wallTimelineFactory: MemoWallTimelineFactory = MemoWallTimelineFactory(),
) {
    suspend fun load(request: OverlayPlaybackRequest): PlaybackTimeline? {
        val wallMemoIds = request.wallMemoIds
        if (wallMemoIds != null) {
            // The wall's stream, read fresh: the memos the wall was showing, in its order,
            // minus any that were trashed between the press and the load. What each memo says
            // follows the same 設定 the inline wall follows — titles by default, contents on
            // request — so the overlay is the same voice in a different sky.
            val playsContent = settingsRepository.wallPlaysContent.first()
            val scope = if (playsContent) request.wallScope ?: return null else WorkCommentScope.BODY
            val lines = wallMemoIds.mapNotNull { id ->
                val memo = memoRepository.findById(id)?.takeIf { it.isOverlayPlaybackEligible() }
                    ?: return@mapNotNull null
                if (playsContent) memo.body else memo.title.takeIf(String::isNotBlank)
            }
            if (lines.isEmpty()) return null
            val settings = settingsRepository.settings.first()
            return wallTimelineFactory.create(lines, scope, settings)
        }
        val memo = memoRepository.findById(request.memoId) ?: return null
        if (!memo.isOverlayPlaybackEligible()) return null
        val comments = commentRepository.findForMemo(request.memoId)
        val settings = settingsRepository.settings.first()
        return timelineFactory.create(
            memoBody = memo.body,
            userComments = comments,
            contentMode = request.contentMode,
            appSettings = settings,
        )
    }
}

fun io.github.cragcoffee.memoripple.data.MemoEntity.isOverlayPlaybackEligible(): Boolean =
    lifecycleState != MemoLifecycleState.TRASHED
