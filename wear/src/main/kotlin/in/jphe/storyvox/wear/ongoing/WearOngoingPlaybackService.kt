package `in`.jphe.storyvox.wear.ongoing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.wearable.Wearable
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge
import `in`.jphe.storyvox.wear.WearMainActivity
import `in`.jphe.storyvox.wear.playback.NodeSelection
import `in`.jphe.storyvox.wear.playback.PhoneNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Wear companion — foreground service that surfaces active phone playback as an
 * **Ongoing Activity**: a media chip on the watch face + the system ongoing
 * strip, carrying the chapter title and a play/pause control. The phone owns
 * the MediaSession; this is the watch's reflection of it.
 *
 * Driven entirely by [PlaybackStateListenerService], which forwards every
 * `/playback/state` change as an [ACTION_UPDATE] (playing) or [ACTION_STOP]
 * (not playing) intent — so the chip appears even when the watch app was never
 * opened this session.
 *
 * The play/pause action and the chip touch both route back through here:
 * [ACTION_TOGGLE] sends [PhoneWearBridge.CMD_TOGGLE] to the phone over
 * MessageClient; the touch intent opens [WearMainActivity].
 *
 * VERIFICATION GAP: built compile-only (no watch in CI). The OngoingActivity
 * chip rendering, the foreground-start path from a background data-layer event
 * (Android 12+ FGS-start rules), and POST_NOTIFICATIONS on Wear 4 all need
 * on-watch verification. The platform-uncertain calls are wrapped in
 * runCatching so a restriction degrades to "no chip" rather than a crash.
 */
class WearOngoingPlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE)?.ifBlank { null } ?: FALLBACK_TITLE
                val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)?.ifBlank { null }
                startForegroundChip(title, subtitle)
            }
            ACTION_TOGGLE -> scope.launch { sendToggle() }
            // ACTION_STOP or a redelivered null intent → tear the chip down.
            else -> stopChip()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundChip(title: String, subtitle: String?) {
        ensureChannel()

        val touchIntent = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            Intent(this, WearMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PI_FLAGS,
        )
        val toggleIntent = PendingIntent.getService(
            this,
            REQ_TOGGLE,
            Intent(this, WearOngoingPlaybackService::class.java).setAction(ACTION_TOGGLE),
            PI_FLAGS,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(subtitle ?: "Playing on phone")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(touchIntent)
            .addAction(android.R.drawable.ic_media_pause, "Pause", toggleIntent)

        // Decorate as an Ongoing Activity so it surfaces as a watch-face chip.
        OngoingActivity.Builder(applicationContext, NOTIF_ID, builder)
            .setStaticIcon(android.R.drawable.ic_media_play)
            .setTouchIntent(touchIntent)
            .setStatus(Status.Builder().addTemplate(title).build())
            .build()
            .apply(applicationContext)

        val notification = builder.build()
        // Android 12+ can refuse a foreground start from a background data-layer
        // event (BAL). Degrade to "no chip" instead of crashing if so — see the
        // class VERIFICATION GAP note.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }.onFailure { Log.w(TAG, "startForeground refused (FGS background-start restriction?)", it) }
    }

    private fun stopChip() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Toggle phone playback from the chip's play/pause action. */
    private suspend fun sendToggle() {
        runCatching {
            val ctx = this@WearOngoingPlaybackService
            val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
                .map { PhoneNode(id = it.id, isNearby = it.isNearby) }
            val target = NodeSelection.preferredTarget(nodes) ?: return
            Wearable.getMessageClient(ctx)
                .sendMessage(target.id, PhoneWearBridge.CMD_TOGGLE, null)
                .await()
        }.onFailure { Log.w(TAG, "chip toggle send failed", it) }
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Playback",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WearOngoingPlayback"
        private const val NOTIF_ID = 4201
        private const val CHANNEL_ID = "wear_playback_ongoing"
        private const val REQ_OPEN = 1
        private const val REQ_TOGGLE = 2
        private const val FALLBACK_TITLE = "storyvox"
        private val PI_FLAGS =
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        const val ACTION_UPDATE = "in.jphe.storyvox.wear.ongoing.UPDATE"
        const val ACTION_TOGGLE = "in.jphe.storyvox.wear.ongoing.TOGGLE"
        const val ACTION_STOP = "in.jphe.storyvox.wear.ongoing.STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"

        fun updateIntent(ctx: Context, title: String, subtitle: String?): Intent =
            Intent(ctx, WearOngoingPlaybackService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUBTITLE, subtitle)

        fun stopIntent(ctx: Context): Intent =
            Intent(ctx, WearOngoingPlaybackService::class.java).setAction(ACTION_STOP)
    }
}
