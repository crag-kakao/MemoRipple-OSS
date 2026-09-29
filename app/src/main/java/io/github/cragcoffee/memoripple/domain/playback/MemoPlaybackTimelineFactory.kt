package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.domain.WorkCommentParser
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.comments.CommentAppearance
import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import io.github.cragcoffee.memoripple.domain.comments.CommentEmphasisRole
import io.github.cragcoffee.memoripple.domain.comments.CommentSizeRole
import io.github.cragcoffee.memoripple.domain.comments.CommentMotion
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.comments.CommentPlacementRole
import io.github.cragcoffee.memoripple.domain.comments.CommentSpeedRole
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowDirection
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowEffect

/** Builds one immutable playback snapshot for every Memo playback destination. */
class MemoPlaybackTimelineFactory(
    private val workCommentParser: WorkCommentParser = WorkCommentParser(),
    private val playbackPlanner: CommentPlaybackPlanner = CommentPlaybackPlanner(),
    private val playbackComposer: CommentPlaybackComposer = CommentPlaybackComposer(),
    private val playbackSettingsApplier: PlaybackSettingsApplier = PlaybackSettingsApplier(),
) {
    /**
     * One linked comment, alone on its own tiny timeline — what flows when reading passes
     * its marker. The link is what excludes it from the ordinary timeline, so this path
     * deliberately looks past linkNo.
     */
    fun createForSingleComment(
        comment: MemoCommentEntity,
        appSettings: AppSettings,
    ): PlaybackTimeline = create(
        memoBody = "",
        userComments = listOf(comment.copy(linkNo = null)),
        contentMode = PlaybackContentMode.USER_ONLY,
        appSettings = appSettings,
    )

    fun create(
        memoBody: String,
        userComments: List<MemoCommentEntity>,
        contentMode: PlaybackContentMode,
        appSettings: AppSettings,
    ): PlaybackTimeline {
        val workTimeline = playbackPlanner.plan(
            workCommentParser.parse(appSettings.commentScope, memoBody),
        )
        // コメントリンク: a linked comment leaves the ordinary timeline. It flows only when
        // reading passes (or taps) its marker — one comment, two doors would be noise.
        val userSources = userComments.filter { it.linkNo == null }.map { comment ->
            UserCommentPlaybackSource(
                id = comment.id,
                text = comment.text,
                createdAt = comment.createdAt,
                playbackOrder = comment.playbackOrder,
                appearance = CommentAppearance(
                    colorRole = CommentColorRole.fromStorageId(comment.appearanceColor),
                    sizeRole = CommentSizeRole.fromStorageId(comment.appearanceSize),
                    emphasisRole = CommentEmphasisRole.fromStorageId(comment.appearanceEmphasis),
                ),
                motion = CommentMotion(
                    mode = CommentMotionMode.fromStorageId(comment.motionMode),
                    speedRole = CommentSpeedRole.fromStorageId(comment.motionSpeed),
                    placementRole = CommentPlacementRole.fromStorageId(comment.motionPlacement),
                    direction = CommentFlowDirection.fromStorageId(comment.flowDirection),
                    flowEffect = CommentFlowEffect.fromStorageId(comment.flowEffect),
                ),
            )
        }
        return playbackSettingsApplier.apply(
            playbackComposer.compose(workTimeline, userSources, contentMode),
            appSettings,
        )
    }
}
