package io.github.cragcoffee.memoripple.overlay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.cragcoffee.memoripple.domain.WorkCommentScope
import io.github.cragcoffee.memoripple.domain.playback.PlaybackContentMode
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OverlayPlaybackStatus { IDLE, WAITING_PERMISSION, STARTING, PLAYING, STOPPING, ERROR }

data class OverlayPlaybackState(
    val status: OverlayPlaybackStatus = OverlayPlaybackStatus.IDLE,
    val requestId: String? = null,
    val memoId: Long? = null,
    val options: OverlayPlaybackOptions? = null,
    val itemCount: Int = 0,
    val durationMillis: Long = 0L,
    val startedElapsedRealtime: Long? = null,
    val message: String? = null,
) {
    val isActive: Boolean get() = status == OverlayPlaybackStatus.STARTING ||
        status == OverlayPlaybackStatus.PLAYING || status == OverlayPlaybackStatus.STOPPING
}

class OverlayPlaybackStateStore {
    private val mutableState = MutableStateFlow(OverlayPlaybackState())
    val state: StateFlow<OverlayPlaybackState> = mutableState.asStateFlow()

    fun waiting(request: OverlayPlaybackRequest) {
        mutableState.value = OverlayPlaybackState(
            status = OverlayPlaybackStatus.WAITING_PERMISSION,
            requestId = request.requestId,
            memoId = request.memoId,
            options = request.options,
        )
    }

    fun starting(request: OverlayPlaybackRequest) {
        mutableState.value = OverlayPlaybackState(
            status = OverlayPlaybackStatus.STARTING,
            requestId = request.requestId,
            memoId = request.memoId,
            options = request.options,
        )
    }

    fun playing(
        request: OverlayPlaybackRequest,
        itemCount: Int,
        durationMillis: Long,
        startedElapsedRealtime: Long,
    ) {
        mutableState.value = OverlayPlaybackState(
            status = OverlayPlaybackStatus.PLAYING,
            requestId = request.requestId,
            memoId = request.memoId,
            options = request.options,
            itemCount = itemCount,
            durationMillis = durationMillis,
            startedElapsedRealtime = startedElapsedRealtime,
        )
    }

    fun stopping(requestId: String?) {
        mutableState.value = OverlayPlaybackState(
            status = OverlayPlaybackStatus.STOPPING,
            requestId = requestId,
        )
    }

    fun idle() {
        mutableState.value = OverlayPlaybackState()
    }

    fun fail(message: String) {
        mutableState.value = OverlayPlaybackState(
            status = OverlayPlaybackStatus.ERROR,
            message = message,
        )
    }

    fun clearMessage() {
        if (mutableState.value.message != null) idle()
    }
}

data class OverlayPlaybackRequest(
    val memoId: Long,
    val options: OverlayPlaybackOptions,
    val requestId: String,
    /** The wall at the moment ▶ was pressed; null for a single memo's overlay. */
    val wallMemoIds: List<Long>? = null,
    val wallScope: WorkCommentScope? = null,
) {
    val contentMode: PlaybackContentMode get() = options.contentMode
    val isWall: Boolean get() = wallMemoIds != null

    init {
        if (wallMemoIds == null) {
            require(memoId > 0)
            require(wallScope == null)
        } else {
            require(wallMemoIds.isNotEmpty())
            require(wallScope != null)
        }
        require(requestId.isNotBlank())
    }

    fun toIntent(context: Context): Intent = Intent(context, OverlayCommentService::class.java).apply {
        action = ACTION_START_OVERLAY
        putExtra(EXTRA_MEMO_ID, memoId)
        putExtra(EXTRA_CONTENT_MODE, contentMode.name)
        putExtra(EXTRA_DISPLAY_REGION, options.displayRegion.storageId)
        putExtra(EXTRA_DENSITY, options.density.storageId)
        putExtra(EXTRA_REQUEST_ID, requestId)
        if (wallMemoIds != null) {
            putExtra(EXTRA_WALL_MEMO_IDS, wallMemoIds.toLongArray())
            putExtra(EXTRA_WALL_SCOPE, wallScope?.storageId)
        }
    }

    companion object {
        fun create(memoId: Long, options: OverlayPlaybackOptions): OverlayPlaybackRequest =
            OverlayPlaybackRequest(memoId, options, UUID.randomUUID().toString())

        /** The wall's ▶: the memos it was showing, in the order it was showing them. */
        fun createWall(
            memoIds: List<Long>,
            scope: WorkCommentScope,
            options: OverlayPlaybackOptions,
        ): OverlayPlaybackRequest = OverlayPlaybackRequest(
            memoId = WALL_MEMO_ID,
            options = options,
            requestId = UUID.randomUUID().toString(),
            wallMemoIds = memoIds,
            wallScope = scope,
        )

        fun from(intent: Intent): OverlayPlaybackRequest? {
            if (intent.action != ACTION_START_OVERLAY) return null
            val memoId = intent.getLongExtra(EXTRA_MEMO_ID, -1L)
            val mode = intent.getStringExtra(EXTRA_CONTENT_MODE)?.let { stored ->
                PlaybackContentMode.entries.firstOrNull { it.name == stored }
            }
            val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
            val wallMemoIds = intent.getLongArrayExtra(EXTRA_WALL_MEMO_IDS)?.toList()
            val wallScope = intent.getStringExtra(EXTRA_WALL_SCOPE)?.let { stored ->
                WorkCommentScope.entries.firstOrNull { it.storageId == stored }
            }
            if (mode == null || requestId.isNullOrBlank()) return null
            if (wallMemoIds == null && memoId <= 0) return null
            if (wallMemoIds != null && (wallMemoIds.isEmpty() || wallScope == null)) return null
            return OverlayPlaybackRequest(
                memoId = memoId,
                options = OverlayPlaybackOptions(
                    contentMode = mode,
                    displayRegion = OverlayDisplayRegion.fromStorageId(
                        intent.getStringExtra(EXTRA_DISPLAY_REGION),
                    ),
                    density = OverlayDensity.fromStorageId(
                        intent.getStringExtra(EXTRA_DENSITY),
                    ),
                ),
                requestId = requestId,
                wallMemoIds = wallMemoIds,
                wallScope = if (wallMemoIds != null) wallScope else null,
            )
        }

        /** A wall request's memoId: never a real row, present only for the wire format. */
        const val WALL_MEMO_ID = 0L
    }
}

enum class OverlayServiceCommand { START, STOP, INVALID }

fun overlayServiceCommand(action: String?): OverlayServiceCommand = when (action) {
    ACTION_START_OVERLAY -> OverlayServiceCommand.START
    ACTION_STOP_OVERLAY -> OverlayServiceCommand.STOP
    else -> OverlayServiceCommand.INVALID
}

class OverlayServiceStarter(
    private val stateStore: OverlayPlaybackStateStore,
) {
    fun start(context: Context, request: OverlayPlaybackRequest) {
        stateStore.starting(request)
        ContextCompat.startForegroundService(context, request.toIntent(context))
    }

    fun stop(context: Context) {
        stateStore.stopping(stateStore.state.value.requestId)
        context.startService(Intent(context, OverlayCommentService::class.java).apply {
            action = ACTION_STOP_OVERLAY
        })
    }
}

const val ACTION_START_OVERLAY =
    "io.github.cragcoffee.memoripple.action.START_OVERLAY"
const val ACTION_STOP_OVERLAY =
    "io.github.cragcoffee.memoripple.action.STOP_OVERLAY"
internal const val EXTRA_MEMO_ID = "overlay.memo_id"
internal const val EXTRA_CONTENT_MODE = "overlay.content_mode"
internal const val EXTRA_DISPLAY_REGION = "overlay.display_region"
internal const val EXTRA_DENSITY = "overlay.density"
internal const val EXTRA_REQUEST_ID = "overlay.request_id"
internal const val EXTRA_WALL_MEMO_IDS = "overlay.wall_memo_ids"
internal const val EXTRA_WALL_SCOPE = "overlay.wall_scope"
