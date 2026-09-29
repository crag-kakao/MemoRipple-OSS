package io.github.cragcoffee.memoripple.ui.memos

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackRequest
import io.github.cragcoffee.memoripple.overlay.OverlayStartDecision

/**
 * The state machine behind starting an overlay playback.
 *
 * Between pressing ▶ and comments appearing over another app stand up to four gates — an
 * already-running overlay to confirm replacing, the "display over other apps" permission with
 * its disclosure and its round-trip through Android settings, and the notification choice on
 * Android 13+. The editor used to coordinate them through five loose `remember` flags, which is
 * exactly the kind of ordering puzzle a bug hides in; here the flags live together and only the
 * named transitions can move them.
 *
 * The flow owns state and decisions. Side effects that need a composable — launching the
 * settings or permission activity, showing a snackbar — stay with the caller, which invokes the
 * matching transition at each UI moment.
 */
@Stable
internal class OverlayLaunchFlow(
    private val isPermissionGranted: () -> Boolean,
    private val needsNotificationDecision: () -> Boolean,
    private val onStart: (OverlayPlaybackRequest) -> Unit,
    private val onWaitingForPermission: (OverlayPlaybackRequest) -> Unit,
    private val onCancelled: () -> Unit,
    /**
     * First-use gate: false until the one-time どんな機能か introduction has been seen.
     * This is only "the explanation was shown" — the permissions themselves are asked of
     * the OS on every request, so a later revoke walks the permission gates again even
     * though the introduction never returns.
     */
    private val hasSeenSetupIntro: () -> Boolean = { true },
    private val onSetupIntroSeen: () -> Unit = {},
) {
    /** Set while the one-time first-use introduction is on screen. */
    var showSetupIntro: Boolean by mutableStateOf(false)
        private set

    /** The request travelling through the gates; consumed when it starts or is cancelled. */
    var pendingRequest: OverlayPlaybackRequest? by mutableStateOf(null)
        private set

    /** Set while the replace-running-overlay confirmation is on screen. */
    var replacementRequest: OverlayPlaybackRequest? by mutableStateOf(null)
        private set

    /** A confirmed replacement, waiting for the running overlay to reach IDLE. */
    var requestAfterStop: OverlayPlaybackRequest? by mutableStateOf(null)
        private set

    var showPermissionDisclosure: Boolean by mutableStateOf(false)
        private set

    var showNotificationDisclosure: Boolean by mutableStateOf(false)
        private set

    /** ▶ was pressed with overlay options chosen. */
    fun submit(request: OverlayPlaybackRequest, decision: OverlayStartDecision) {
        when (decision) {
            OverlayStartDecision.START ->
                if (hasSeenSetupIntro()) {
                    requestPermissionOrStart(request)
                } else {
                    // The very first overlay: say what this is before asking Android for
                    // anything. An overlay already running means this is not the first.
                    pendingRequest = request
                    showSetupIntro = true
                }
            OverlayStartDecision.CONFIRM_REPLACEMENT -> replacementRequest = request
        }
    }

    /** 設定を始める on the introduction: remembered, then straight into the real gates. */
    fun beginSetupFromIntro() {
        showSetupIntro = false
        onSetupIntroSeen()
        pendingRequest?.let(::requestPermissionOrStart)
    }

    /** The introduction was dismissed; it counts as seen, the request does not survive. */
    fun cancelSetupIntro() {
        showSetupIntro = false
        onSetupIntroSeen()
        pendingRequest = null
        onCancelled()
    }

    /** 停止して再生 on the replacement dialog. The caller stops the running overlay next. */
    fun confirmReplacement() {
        requestAfterStop = replacementRequest
        replacementRequest = null
    }

    fun dismissReplacement() {
        replacementRequest = null
    }

    /** The running overlay reached IDLE; a confirmed replacement may now take the stage. */
    fun onOverlayIdle() {
        requestAfterStop?.let { request ->
            requestAfterStop = null
            requestPermissionOrStart(request)
        }
    }

    /** Back from Android settings. Returns false when permission is still missing. */
    fun permissionSettingsReturned(): Boolean {
        val request = pendingRequest
        return if (request != null && isPermissionGranted()) {
            continueWithNotificationChoice(request)
            true
        } else {
            pendingRequest = null
            onCancelled()
            false
        }
    }

    /** 設定を開く was pressed; the dialog leaves while the caller launches settings. */
    fun leaveForPermissionSettings() {
        showPermissionDisclosure = false
    }

    /** The settings screen could not be opened; the request dies where it stood. */
    fun permissionSettingsUnavailable() {
        pendingRequest = null
        onCancelled()
    }

    /** The permission disclosure was declined or dismissed. */
    fun cancelPermissionDisclosure() {
        showPermissionDisclosure = false
        pendingRequest = null
        onCancelled()
    }

    /** 通知を許可 was pressed; the dialog leaves while the caller asks the system. */
    fun leaveForNotificationPermission() {
        showNotificationDisclosure = false
    }

    /**
     * Starts whatever is pending, with or without the notification permission: the overlay may
     * run with a silent notification, so every way out of the notification question — granted,
     * declined, dismissed, or the request screen failing to open — lands here.
     */
    fun startPendingNow() {
        showNotificationDisclosure = false
        pendingRequest?.let(onStart)
        pendingRequest = null
    }

    private fun requestPermissionOrStart(request: OverlayPlaybackRequest) {
        pendingRequest = request
        if (isPermissionGranted()) {
            continueWithNotificationChoice(request)
        } else {
            onWaitingForPermission(request)
            showPermissionDisclosure = true
        }
    }

    private fun continueWithNotificationChoice(request: OverlayPlaybackRequest) {
        pendingRequest = request
        if (needsNotificationDecision()) {
            showNotificationDisclosure = true
        } else {
            onStart(request)
            pendingRequest = null
        }
    }
}
