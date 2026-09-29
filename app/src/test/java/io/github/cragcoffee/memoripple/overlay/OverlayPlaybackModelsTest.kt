package io.github.cragcoffee.memoripple.overlay

import io.github.cragcoffee.memoripple.domain.WorkCommentScope
import io.github.cragcoffee.memoripple.domain.playback.PlaybackContentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPlaybackModelsTest {
    @Test
    fun requestCarriesMemoOptionsAndRequestId() {
        val options = OverlayPlaybackOptions(
            PlaybackContentMode.BOTH,
            OverlayDisplayRegion.CENTER,
            OverlayDensity.DENSE,
        )
        val request = OverlayPlaybackRequest(42L, options, "request-1")

        assertEquals(42L, request.memoId)
        assertEquals(PlaybackContentMode.BOTH, request.contentMode)
        assertEquals(options, request.options)
        assertEquals("request-1", request.requestId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidMemoIdIsRejected() {
        OverlayPlaybackRequest(
            0L,
            OverlayPlaybackOptions(contentMode = PlaybackContentMode.WORK_ONLY),
            "request",
        )
    }

    @Test
    fun wallRequestCarriesItsMemosInOrderAndTheirScope() {
        val request = OverlayPlaybackRequest.createWall(
            memoIds = listOf(3L, 1L, 2L),
            scope = WorkCommentScope.OUTLINE,
            options = OverlayPlaybackOptions(contentMode = PlaybackContentMode.WORK_ONLY),
        )

        assertTrue(request.isWall)
        assertEquals(listOf(3L, 1L, 2L), request.wallMemoIds)
        assertEquals(WorkCommentScope.OUTLINE, request.wallScope)
        assertEquals(OverlayPlaybackRequest.WALL_MEMO_ID, request.memoId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyWallIsRejected() {
        OverlayPlaybackRequest.createWall(
            memoIds = emptyList(),
            scope = WorkCommentScope.BODY,
            options = OverlayPlaybackOptions(contentMode = PlaybackContentMode.WORK_ONLY),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun singleMemoRequestRejectsAStrayWallScope() {
        OverlayPlaybackRequest(
            memoId = 42L,
            options = OverlayPlaybackOptions(contentMode = PlaybackContentMode.BOTH),
            requestId = "request",
            wallScope = WorkCommentScope.BODY,
        )
    }

    @Test
    fun notificationStopActionMapsOnlyToStopCommand() {
        assertEquals(OverlayServiceCommand.STOP, overlayServiceCommand(ACTION_STOP_OVERLAY))
        assertEquals(OverlayServiceCommand.START, overlayServiceCommand(ACTION_START_OVERLAY))
        assertEquals(OverlayServiceCommand.INVALID, overlayServiceCommand(null))
    }

    @Test
    fun processStateIsNeverPersistedAndReturnsToIdle() {
        val store = OverlayPlaybackStateStore()
        val request = OverlayPlaybackRequest(
            42L,
            OverlayPlaybackOptions(),
            "request",
        )

        store.waiting(request)
        assertEquals(OverlayPlaybackStatus.WAITING_PERMISSION, store.state.value.status)
        assertEquals(request.options, store.state.value.options)
        store.starting(request)
        assertTrue(store.state.value.isActive)
        store.fail("failed")
        assertFalse(store.state.value.isActive)
        store.clearMessage()
        assertEquals(OverlayPlaybackState(), store.state.value)
    }

    @Test
    fun playingStateRetainsSnapshotMetadata() {
        val store = OverlayPlaybackStateStore()
        val request = OverlayPlaybackRequest(7L, OverlayPlaybackOptions(), "request")

        store.playing(request, itemCount = 4, durationMillis = 10_000L, startedElapsedRealtime = 500L)

        assertEquals(7L, store.state.value.memoId)
        assertEquals(4, store.state.value.itemCount)
    }

    @Test
    fun activeSessionRequiresExplicitReplacementConfirmation() {
        val store = OverlayPlaybackStateStore()
        val request = OverlayPlaybackRequest(7L, OverlayPlaybackOptions(), "request")

        assertEquals(OverlayStartDecision.START, overlayStartDecision(store.state.value))
        store.starting(request)
        assertEquals(
            OverlayStartDecision.CONFIRM_REPLACEMENT,
            overlayStartDecision(store.state.value),
        )
    }
}
