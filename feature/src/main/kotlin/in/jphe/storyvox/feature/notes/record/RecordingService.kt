package `in`.jphe.storyvox.feature.notes.record

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import `in`.jphe.storyvox.playback.R as PlaybackR

/**
 * Voice Notes (#1657, Phase 2a) — the microphone-typed foreground service that
 * keeps mic capture legal + visible while a recording is in progress.
 *
 * ## Deliberately thin
 * The recorder itself and the stop→persist→enqueue flow live in the VM +
 * [AudioRecorder] (spec §3.1: capture code in `feature/.../notes/record/`, the
 * VM as the "RecordingController"). This service exists ONLY to (a) satisfy the
 * Android-14 requirement that background mic access run under a
 * `microphone`-typed FGS with the `FOREGROUND_SERVICE_MICROPHONE` permission, and
 * (b) surface an ongoing notification. It is started/stopped by the VM around a
 * take and holds no recorder state — so there is no cross-process finalize path
 * to keep consistent.
 *
 * Modeled on [`StoryvoxPlaybackService`][`in`.jphe.storyvox.playback.StoryvoxPlaybackService]'s
 * channel + guarded-`startForeground` shape; it is a plain [Service] and does NOT
 * subclass the Media3 session service.
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
            }
            else -> startForegroundMic() // ACTION_START (or a redelivered start)
        }
        // Don't recreate a killed recording FGS with a null intent — the VM
        // re-establishes it on the next explicit start.
        return START_NOT_STICKY
    }

    private fun startForegroundMic() {
        ensureChannel()
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException is API 31+; runtime-guard
            // the type check so a typed catch can't crash at class-load on older
            // devices.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                Log.w(TAG, "mic FG-start denied by OS", e)
            } else {
                Log.w(TAG, "startForeground(microphone) failed: ${e.message}", e)
            }
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        // Tapping the notification reopens the app (launcher activity) — no
        // compile dependency on :app's MainActivity.
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_RECORDING)
            .setSmallIcon(PlaybackR.drawable.ic_storyvox_notif)
            .setContentTitle("Recording voice note")
            .setContentText("Recording in progress")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_RECORDING) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_RECORDING,
                        "Voice note recording",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_RECORDING = "notes_recording"
        private const val NOTIFICATION_ID = 1057
        const val ACTION_START = "in.jphe.storyvox.notes.action.START"
        const val ACTION_STOP = "in.jphe.storyvox.notes.action.STOP"
    }
}
