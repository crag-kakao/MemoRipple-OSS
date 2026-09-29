package io.github.cragcoffee.memoripple.domain.playback

/** Creates an ephemeral mixed timeline; timing and lanes are never persisted with comments. */
class CommentPlaybackComposer(
    private val userCommentMapper: UserCommentPlaybackMapper = UserCommentPlaybackMapper(),
) {
    fun compose(
        workTimeline: PlaybackTimeline,
        userComments: List<UserCommentPlaybackSource>,
        contentMode: PlaybackContentMode,
    ): PlaybackTimeline {
        val laneCount = maxOf(workTimeline.laneCount, USER_COMMENT_LANE_COUNT)
        val orderedUserComments = userCommentMapper.orderForPlayback(userComments)
        val firstUserPlaybackId = (workTimeline.items.maxOfOrNull(PlaybackItem::id) ?: -1) + 1
        val userItems = orderedUserComments.mapIndexed { index, comment ->
            userCommentMapper.map(
                source = comment,
                playbackId = firstUserPlaybackId + index,
                startTimeMillis = userStartTime(
                    index = index,
                    count = orderedUserComments.size,
                    workTimeline = workTimeline,
                ),
                laneIndex = (index * USER_LANE_STEP + USER_LANE_OFFSET).mod(laneCount),
            )
        }

        // Every branch leaves here chronological (start, then id for a stable tie): the
        // lane allocator requires it, and the planner's 溜め and ×N staggers can push a
        // line's start past the line after it, so the raw planner order is not enough.
        val selectedItems = when (contentMode) {
            PlaybackContentMode.WORK_ONLY -> workTimeline.items
            PlaybackContentMode.USER_ONLY -> userItems
            PlaybackContentMode.BOTH -> workTimeline.items + userItems
        }.sortedWith(compareBy(PlaybackItem::startTimeMillis, PlaybackItem::id))
        if (selectedItems.isEmpty()) return PlaybackTimeline.Empty

        return PlaybackTimeline(
            items = selectedItems,
            laneCount = laneCount,
            totalDurationMillis = selectedItems.maxOf {
                it.startTimeMillis + it.travelDurationMillis
            },
        )
    }

    private fun userStartTime(
        index: Int,
        count: Int,
        workTimeline: PlaybackTimeline,
    ): Long = if (workTimeline.items.isEmpty()) {
        index * USER_ONLY_INTERVAL_MILLIS
    } else {
        workTimeline.totalDurationMillis * (index + 1) / (count + 1)
    }

    private companion object {
        const val USER_COMMENT_LANE_COUNT = 12
        const val USER_ONLY_INTERVAL_MILLIS = 700L
        const val USER_LANE_STEP = 5
        const val USER_LANE_OFFSET = 1
    }
}
