package io.github.cragcoffee.memoripple.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.R
import io.github.cragcoffee.memoripple.data.SettingsRepository
import io.github.cragcoffee.memoripple.domain.playback.CommentAnimator
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import io.github.cragcoffee.memoripple.domain.settings.CommentFont
import io.github.cragcoffee.memoripple.ui.playback.LocalCommentBackdropEnabled
import io.github.cragcoffee.memoripple.ui.playback.LocalCommentFontFamily
import io.github.cragcoffee.memoripple.ui.playback.LocalCommentTransparency
import io.github.cragcoffee.memoripple.ui.playback.commentFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OverlayCommentService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanupGuard = OverlayCleanupGuard()
    private val animator = CommentAnimator()
    private val planFactory = OverlayPlaybackPlanFactory()

    private lateinit var applicationState: OverlayPlaybackStateStore
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var snapshotProvider: OverlayPlaybackSnapshotProvider
    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var watchdog: Job? = null
    private var currentRequest: OverlayPlaybackRequest? = null
    private var windowAdded = false
    private var completionMessage: String? = null

    override fun onCreate() {
        super.onCreate()
        val application = application as MemoRippleApplication
        applicationState = application.overlayPlaybackStateStore
        settingsRepository = application.settingsRepository
        snapshotProvider = OverlayPlaybackSnapshotProvider(
            memoRepository = application.memoRepository,
            commentRepository = application.memoCommentRepository,
            settingsRepository = application.settingsRepository,
        )
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (overlayServiceCommand(intent?.action)) {
            OverlayServiceCommand.STOP -> finishSession()
            OverlayServiceCommand.INVALID -> finishSession("オーバーレイ再生を開始できませんでした")
            OverlayServiceCommand.START -> intent?.let(::startSession)
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        val request = OverlayPlaybackRequest.from(intent)
        if (request == null) {
            finishSession("オーバーレイ再生を開始できませんでした")
            return
        }
        if (currentRequest != null || windowAdded) return
        currentRequest = request
        applicationState.starting(request)

        if (!startForegroundImmediately()) {
            finishSession("オーバーレイ再生を開始できませんでした")
            return
        }
        if (!OverlayPermissionGateway(this).isGranted()) {
            finishSession("「他のアプリの上に表示」が許可されていません")
            return
        }

        serviceScope.launch {
            val timeline = runCatching { snapshotProvider.load(request) }.getOrNull()
            val fontFamily = runCatching {
                io.github.cragcoffee.memoripple.ui.playback.resolveCommentFontFamily(
                    storageId = settingsRepository.commentFontId.first(),
                    userFonts = settingsRepository.userCommentFonts.first(),
                    store = (application as io.github.cragcoffee.memoripple.MemoRippleApplication)
                        .commentFontStore,
                )
            }.getOrNull()
            val backdrop = runCatching { settingsRepository.commentBackdrop.first() }
                .getOrDefault(false)
            val transparency = runCatching { settingsRepository.commentTransparency.first() }
                .getOrDefault(0f)
            val longCommentReadability = runCatching {
                settingsRepository.settings.first().longCommentReadability
            }.getOrDefault(false)
            when {
                timeline == null -> finishSession("対象のメモを読み込めませんでした")
                overlayPreflight(timeline) == OverlayPreflightResult.EMPTY ->
                    finishSession("再生できるコメントがありません")
                else -> attachOverlay(
                    request, timeline, fontFamily, backdrop, transparency,
                    longCommentReadability,
                )
            }
        }
    }

    private fun attachOverlay(
        request: OverlayPlaybackRequest,
        timeline: PlaybackTimeline,
        fontFamily: androidx.compose.ui.text.font.FontFamily?,
        backdrop: Boolean,
        transparency: Float,
        longCommentReadability: Boolean,
    ) {
        if (windowAdded) return
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        owner.attach(view)
        view.setContent {
            CompositionLocalProvider(
                LocalCommentFontFamily provides fontFamily,
                LocalCommentBackdropEnabled provides backdrop,
                LocalCommentTransparency provides transparency,
            ) {
                OverlayCommentContent(
                    sourceTimeline = timeline,
                    options = request.options,
                    animator = animator,
                    planFactory = planFactory,
                    longCommentReadability = longCommentReadability,
                    onPlaying = { plan ->
                        applicationState.playing(
                            request = request,
                            itemCount = plan.itemCount,
                            durationMillis = plan.estimatedDurationMillis,
                            startedElapsedRealtime = SystemClock.elapsedRealtime(),
                        )
                    },
                    onNaturalCompletion = { finishSession() },
                    onRejected = ::finishSession,
                )
            }
        }
        val added = runCatching {
            windowManager.addView(
                view,
                OverlayWindowPolicy.layoutParams(
                    this,
                    windowManager,
                    request.options.displayRegion,
                ),
            )
        }.isSuccess
        if (!added) {
            view.disposeComposition()
            owner.destroy()
            finishSession("オーバーレイを画面へ追加できませんでした")
            return
        }
        lifecycleOwner = owner
        composeView = view
        windowAdded = true
        watchdog = serviceScope.launch {
            delay(OVERLAY_WATCHDOG_MS)
            finishSession("オーバーレイ再生を安全のため終了しました")
        }
    }

    private fun startForegroundImmediately(): Boolean = runCatching {
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            foregroundType,
        )
    }.isSuccess

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, OverlayCommentService::class.java).apply {
            action = ACTION_STOP_OVERLAY
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_overlay)
            .setContentTitle("MemoRipple")
            .setContentText("コメントをオーバーレイ表示中")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "コメントオーバーレイ",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "一時的なコメントオーバーレイ再生"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun finishSession(message: String? = null) {
        if (message != null) completionMessage = message
        cleanupGuard.runOnce {
            applicationState.stopping(currentRequest?.requestId)
            watchdog?.cancel()
            watchdog = null
            animator.stop()
            composeView?.let { view ->
                if (windowAdded) runCatching { windowManager.removeViewImmediate(view) }
                view.disposeComposition()
            }
            windowAdded = false
            composeView = null
            lifecycleOwner?.destroy()
            lifecycleOwner = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (currentRequest != null) {
            finishSession("画面の向きが変わったためオーバーレイ再生を終了しました")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        finishSession()
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int) {
        finishSession("Androidの制限によりオーバーレイ再生を終了しました")
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        finishSession("Androidの制限によりオーバーレイ再生を終了しました")
    }

    override fun onDestroy() {
        finishSession()
        serviceScope.cancel()
        completionMessage?.let(applicationState::fail) ?: applicationState.idle()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "overlay_comment_playback"
        const val NOTIFICATION_ID = 5101
        private const val STOP_REQUEST_CODE = 5102
    }
}
