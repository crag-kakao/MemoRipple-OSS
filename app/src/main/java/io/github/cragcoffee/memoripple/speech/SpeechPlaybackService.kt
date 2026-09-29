package io.github.cragcoffee.memoripple.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 読み上げの背中を支える foreground service. It plays nothing itself — the voice belongs to
 * [SpeechController] — it only stands while a reading is in flight so the process survives
 * the screen going dark or the app leaving the foreground, which is the whole use: hearing a
 * memo with the phone asleep in a pocket.
 *
 * Started when a reading starts, gone the moment the voice goes quiet (it watches the
 * controller's own state rather than trusting whoever started it), and its one notification
 * is silent, ongoing, and carries 停止. START_NOT_STICKY: a service the system reclaimed has
 * no reading left to guard.
 */
class SpeechPlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = (application as MemoRippleApplication).speechController
        if (intent?.action == ACTION_STOP_SPEECH) {
            controller.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startForegroundImmediately()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watching == null) {
            watching = scope.launch {
                controller.state.collect { state ->
                    val reading = state.status == SpeechStatus.SPEAKING ||
                        state.status == SpeechStatus.INITIALIZING
                    if (!reading) stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundImmediately(): Boolean = runCatching {
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
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
        val stopIntent = Intent(this, SpeechPlaybackService::class.java).apply {
            action = ACTION_STOP_SPEECH
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
            .setContentText("読み上げ中")
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
            "読み上げ",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "メモの読み上げ中"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "speech_playback"
        private const val NOTIFICATION_ID = 5201
        private const val STOP_REQUEST_CODE = 5202
        const val ACTION_STOP_SPEECH = "io.github.cragcoffee.memoripple.action.STOP_SPEECH"
    }
}
