package io.github.cragcoffee.memoripple.ui.memos

import io.github.cragcoffee.memoripple.domain.playback.PlaybackContentMode
import io.github.cragcoffee.memoripple.overlay.OverlayDensity
import io.github.cragcoffee.memoripple.overlay.OverlayDisplayRegion
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackOptions
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackRequest
import io.github.cragcoffee.memoripple.overlay.OverlayStartDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLaunchFlowTest {

    private val request = OverlayPlaybackRequest(
        memoId = 7L,
        options = OverlayPlaybackOptions(
            PlaybackContentMode.BOTH,
            OverlayDisplayRegion.CENTER,
            OverlayDensity.DENSE,
        ),
        requestId = "request-1",
    )

    private class Harness(
        var granted: Boolean = true,
        var needsNotificationChoice: Boolean = false,
        var seenSetupIntro: Boolean = true,
    ) {
        val started = mutableListOf<OverlayPlaybackRequest>()
        val waiting = mutableListOf<OverlayPlaybackRequest>()
        var cancelled = 0
        var introMarkedSeen = 0
        val flow = OverlayLaunchFlow(
            isPermissionGranted = { granted },
            needsNotificationDecision = { needsNotificationChoice },
            onStart = { started += it },
            onWaitingForPermission = { waiting += it },
            onCancelled = { cancelled += 1 },
            hasSeenSetupIntro = { seenSetupIntro },
            onSetupIntroSeen = { introMarkedSeen += 1; seenSetupIntro = true },
        )
    }

    @Test
    fun `with permission and no notification question the overlay just starts`() {
        val h = Harness()

        h.flow.submit(request, OverlayStartDecision.START)

        assertEquals(listOf(request), h.started)
        assertNull(h.flow.pendingRequest)
        assertFalse(h.flow.showPermissionDisclosure)
        assertFalse(h.flow.showNotificationDisclosure)
    }

    @Test
    fun `the notification question interposes once and every exit starts the overlay`() {
        val h = Harness(needsNotificationChoice = true)

        h.flow.submit(request, OverlayStartDecision.START)
        assertTrue(h.flow.showNotificationDisclosure)
        assertTrue(h.started.isEmpty())

        h.flow.startPendingNow()
        assertEquals(listOf(request), h.started)
        assertNull(h.flow.pendingRequest)
        assertFalse(h.flow.showNotificationDisclosure)
    }

    @Test
    fun `without permission the disclosure opens and the request is reported waiting`() {
        val h = Harness(granted = false)

        h.flow.submit(request, OverlayStartDecision.START)

        assertTrue(h.flow.showPermissionDisclosure)
        assertEquals(listOf(request), h.waiting)
        assertEquals(request, h.flow.pendingRequest)
        assertTrue(h.started.isEmpty())
    }

    @Test
    fun `coming back from settings with the grant continues to a start`() {
        val h = Harness(granted = false)
        h.flow.submit(request, OverlayStartDecision.START)
        h.flow.leaveForPermissionSettings()

        h.granted = true
        assertTrue(h.flow.permissionSettingsReturned())

        assertEquals(listOf(request), h.started)
        assertNull(h.flow.pendingRequest)
    }

    @Test
    fun `coming back from settings without the grant cancels the request`() {
        val h = Harness(granted = false)
        h.flow.submit(request, OverlayStartDecision.START)
        h.flow.leaveForPermissionSettings()

        assertFalse(h.flow.permissionSettingsReturned())

        assertNull(h.flow.pendingRequest)
        assertEquals(1, h.cancelled)
        assertTrue(h.started.isEmpty())
    }

    @Test
    fun `declining the disclosure cancels and clears everything`() {
        val h = Harness(granted = false)
        h.flow.submit(request, OverlayStartDecision.START)

        h.flow.cancelPermissionDisclosure()

        assertFalse(h.flow.showPermissionDisclosure)
        assertNull(h.flow.pendingRequest)
        assertEquals(1, h.cancelled)
    }

    @Test
    fun `an unopenable settings screen cancels the request where it stood`() {
        val h = Harness(granted = false)
        h.flow.submit(request, OverlayStartDecision.START)
        h.flow.leaveForPermissionSettings()

        h.flow.permissionSettingsUnavailable()

        assertNull(h.flow.pendingRequest)
        assertEquals(1, h.cancelled)
    }

    @Test
    fun `replacing a running overlay waits for idle then walks the same gates`() {
        val h = Harness()

        h.flow.submit(request, OverlayStartDecision.CONFIRM_REPLACEMENT)
        assertEquals(request, h.flow.replacementRequest)
        assertTrue(h.started.isEmpty())

        h.flow.confirmReplacement()
        assertNull(h.flow.replacementRequest)
        assertEquals(request, h.flow.requestAfterStop)

        h.flow.onOverlayIdle()
        assertNull(h.flow.requestAfterStop)
        assertEquals(listOf(request), h.started)
    }

    @Test
    fun `dismissing the replacement leaves the running overlay alone`() {
        val h = Harness()
        h.flow.submit(request, OverlayStartDecision.CONFIRM_REPLACEMENT)

        h.flow.dismissReplacement()
        h.flow.onOverlayIdle()

        assertNull(h.flow.replacementRequest)
        assertNull(h.flow.requestAfterStop)
        assertTrue(h.started.isEmpty())
    }

    @Test
    fun `idle with nothing confirmed starts nothing`() {
        val h = Harness()

        h.flow.onOverlayIdle()

        assertTrue(h.started.isEmpty())
        assertNull(h.flow.pendingRequest)
    }
}

class OverlayFirstUseIntroTest {

    private val request = OverlayPlaybackRequest(
        memoId = 7L,
        options = OverlayPlaybackOptions(
            PlaybackContentMode.BOTH,
            OverlayDisplayRegion.CENTER,
            OverlayDensity.DENSE,
        ),
        requestId = "request-intro",
    )

    private class Harness(
        var granted: Boolean = false,
        var needsNotificationChoice: Boolean = true,
        var seenSetupIntro: Boolean = false,
    ) {
        val started = mutableListOf<OverlayPlaybackRequest>()
        val waiting = mutableListOf<OverlayPlaybackRequest>()
        var cancelled = 0
        var introMarkedSeen = 0
        val flow = OverlayLaunchFlow(
            isPermissionGranted = { granted },
            needsNotificationDecision = { needsNotificationChoice },
            onStart = { started += it },
            onWaitingForPermission = { waiting += it },
            onCancelled = { cancelled += 1 },
            hasSeenSetupIntro = { seenSetupIntro },
            onSetupIntroSeen = { introMarkedSeen += 1; seenSetupIntro = true },
        )
    }

    @Test
    fun `the very first overlay shows the introduction before any gate`() {
        val h = Harness()

        h.flow.submit(request, OverlayStartDecision.START)

        assertTrue(h.flow.showSetupIntro)
        assertFalse(h.flow.showPermissionDisclosure)
        assertFalse(h.flow.showNotificationDisclosure)
        assertTrue(h.started.isEmpty())
    }

    @Test
    fun `beginning setup from the introduction remembers it and walks the real gates`() {
        val h = Harness(granted = false)

        h.flow.submit(request, OverlayStartDecision.START)
        h.flow.beginSetupFromIntro()

        assertEquals(1, h.introMarkedSeen)
        assertFalse(h.flow.showSetupIntro)
        assertTrue(h.flow.showPermissionDisclosure)
        assertEquals(listOf(request), h.waiting)
    }

    @Test
    fun `with permission already held the introduction leads to the notification question`() {
        val h = Harness(granted = true, needsNotificationChoice = true)

        h.flow.submit(request, OverlayStartDecision.START)
        h.flow.beginSetupFromIntro()

        assertTrue(h.flow.showNotificationDisclosure)
        assertFalse(h.flow.showPermissionDisclosure)
    }

    @Test
    fun `dismissing the introduction counts as seen and cancels the request`() {
        val h = Harness()

        h.flow.submit(request, OverlayStartDecision.START)
        h.flow.cancelSetupIntro()

        assertEquals(1, h.introMarkedSeen)
        assertEquals(1, h.cancelled)
        assertNull(h.flow.pendingRequest)
        assertTrue(h.started.isEmpty())

        h.flow.submit(request, OverlayStartDecision.START)
        assertFalse(h.flow.showSetupIntro)
    }

    @Test
    fun `once seen the introduction never returns even when permission was later revoked`() {
        val h = Harness(granted = true, needsNotificationChoice = false, seenSetupIntro = true)

        h.flow.submit(request, OverlayStartDecision.START)
        assertEquals(listOf(request), h.started)

        // The OS stays the source of truth: a revoke re-raises the permission gate,
        // not the introduction.
        h.granted = false
        h.flow.submit(request, OverlayStartDecision.START)
        assertFalse(h.flow.showSetupIntro)
        assertTrue(h.flow.showPermissionDisclosure)
    }
}
