package io.github.cragcoffee.memoripple.domain.export

import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import io.github.cragcoffee.memoripple.domain.comments.CommentEmphasisRole
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowDirection
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowEffect
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.comments.CommentPlacementRole
import io.github.cragcoffee.memoripple.domain.comments.CommentSizeRole
import io.github.cragcoffee.memoripple.domain.comments.CommentSpeedRole

/**
 * Turns a comment's stored expression into the few words a reader needs — and only where the
 * writer chose something other than the default. A comment left as it came prints nothing,
 * so the export stays a text to read, not a settings dump. Internal enum names, lanes and
 * timings never appear here.
 */
object PortableExpressionNotes {

    fun forComment(
        color: String,
        size: String,
        emphasis: String,
        motionMode: String,
        speed: String? = null,
        placement: String? = null,
        flowDirection: String? = null,
        flowEffect: String? = null,
    ): List<String> = buildList {
        colorLabel(CommentColorRole.fromStorageId(color))?.let { add("色: $it") }
        when (CommentSizeRole.fromStorageId(size)) {
            CommentSizeRole.SMALL -> add("大きさ: 小さめ")
            CommentSizeRole.LARGE -> add("大きさ: 大きめ")
            CommentSizeRole.STANDARD -> Unit
        }
        if (CommentEmphasisRole.fromStorageId(emphasis) == CommentEmphasisRole.STRONG) {
            add("強調: あり")
        }
        when (CommentMotionMode.fromStorageId(motionMode)) {
            CommentMotionMode.FIXED_TOP -> add("表示: 上に固定")
            CommentMotionMode.FIXED_BOTTOM -> add("表示: 下に固定")
            CommentMotionMode.FLOW -> Unit
        }
        speed?.let {
            when (CommentSpeedRole.fromStorageId(it)) {
                CommentSpeedRole.SLOW -> add("速さ: ゆっくり")
                CommentSpeedRole.FAST -> add("速さ: 速い")
                CommentSpeedRole.STANDARD -> Unit
            }
        }
        placement?.let {
            when (CommentPlacementRole.fromStorageId(it)) {
                CommentPlacementRole.TOP -> add("位置: 上")
                CommentPlacementRole.MIDDLE -> add("位置: 中央")
                CommentPlacementRole.BOTTOM -> add("位置: 下")
                CommentPlacementRole.AUTO -> Unit
            }
        }
        flowDirection?.let {
            if (CommentFlowDirection.fromStorageId(it) == CommentFlowDirection.LEFT_TO_RIGHT) {
                add("向き: 左から右")
            }
        }
        flowEffect?.let {
            if (CommentFlowEffect.fromStorageId(it) == CommentFlowEffect.WAVE) {
                add("動き: 波")
            }
        }
    }

    private fun colorLabel(role: CommentColorRole): String? = when (role) {
        CommentColorRole.DEFAULT -> null
        CommentColorRole.RED -> "赤"
        CommentColorRole.PINK -> "ピンク"
        CommentColorRole.ORANGE -> "オレンジ"
        CommentColorRole.YELLOW -> "黄"
        CommentColorRole.GREEN -> "緑"
        CommentColorRole.CYAN -> "シアン"
        CommentColorRole.GRAY -> "灰"
        CommentColorRole.BLUE -> "青"
        CommentColorRole.PURPLE -> "紫"
        CommentColorRole.BLACK -> "黒"
    }
}
