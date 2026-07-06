package `in`.jphe.storyvox.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import dagger.hilt.android.AndroidEntryPoint
import `in`.jphe.storyvox.data.intent.ReaderIntentContract
import `in`.jphe.storyvox.data.repository.playback.BedtimeSleepConfig
import `in`.jphe.storyvox.data.repository.playback.SleepTimerExtendConfig
import `in`.jphe.storyvox.playback.diagnostics.AudioOutputMonitor
import `in`.jphe.storyvox.playback.sleep.ShakeDetector
import `in`.jphe.storyvox.playback.tts.EnginePlayer
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The MediaSessionService that hosts our [EnginePlayer] and surfaces the audiobook to
 * every Android-side controller: the notification shade, the lock screen, Bluetooth
 * media buttons, Wear, and Auto.
 *
 * Notification + lock-screen rendering is fully delegated to Media3:
 *  - [DefaultMediaNotificationProvider] turns the bound [MediaSession] into a
 *    `MediaStyleNotificationHelper.MediaStyle` notification, derives transport
 *    actions from `Player.Commands`, and re-issues on every state change.
 *  - [CacheBitmapLoader] over [DataSourceBitmapLoader] resolves the `artworkUri` set
 *    by [EnginePlayer] in its [androidx.media3.common.MediaMetadata] and caches the
 *    decoded bitmap — that bitmap is used as the notification's large icon and as
 *    the lock-screen background.
 *
 * We don't drive `startForeground` ourselves; the framework promotes the service
 * automatically once a controller binds and a notification is posted.
 */
@UnstableApi
@AndroidEntryPoint
class StoryvoxPlaybackService : MediaSessionService() {

    @Inject lateinit var controller: DefaultPlaybackController
    @Inject lateinit var enginePlayerFactory: EnginePlayer.Factory
    @Inject lateinit var wearBridge: PhoneWearBridge
    @Inject lateinit var mediaSessionLocator: MediaSessionLocator

    /** Issue #1232 — resolves Android Auto play-from-media-id / play-from-search
     *  requests (routed into the session callback) to a playable chapter. */
    @Inject lateinit var autoPlaybackResolver: `in`.jphe.storyvox.playback.auto.AutoPlaybackResolver

    /** Issue #560 — process-wide audio-focus singleton. Held here so the
     *  service can wire the loss callback to controller.pause() once at
     *  onCreate(); the engine acquires/abandons through the same
     *  Singleton instance via Hilt. */
    @Inject lateinit var audioFocus: AudioFocusController

    /** "Why are we waiting?" — process-wide audio-output diagnostic
     *  singleton. Started at onCreate so it's listening before the user
     *  taps play; stopped at onDestroy. The monitor reads truthful
     *  signals (head-position advance, focus state, route, volume) and
     *  exposes a [WaitReason] flow that the reader UI surfaces in the
     *  brass diagnostic panel above the cover. */
    @Inject lateinit var audioOutputMonitor: AudioOutputMonitor

    /** Issue #595 — user-tunable shake-to-extend duration (replaces
     *  the legacy hardcoded [SHAKE_EXTEND_MINUTES] constant). Read at
     *  shake-detect time via [SleepTimerExtendConfig.currentShakeExtendMinutes]
     *  so a Settings flip takes effect on the very next shake without
     *  restarting the service. */
    @Inject lateinit var sleepExtendConfig: SleepTimerExtendConfig
    @Inject lateinit var bedtimeSleepConfig: BedtimeSleepConfig

    private lateinit var session: MediaSession
    private lateinit var player: EnginePlayer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Reflects the current session-activity intent so it can be refreshed
     *  whenever fictionId/chapterId changes — notification tap then opens the
     *  correct reader, not whatever was playing the moment the service started. */
    private var sessionActivityJob: Job? = null

    /** Issue #150 — sleep timer shake-to-extend.
     *  - [shakeDetector] is created once with a fixed callback that
     *    re-arms the timer at 15min if currently in the fade tail.
     *  - [shakeJob] watches `(remainingMs, shakeToExtendEnabled)` and
     *    starts/stops the detector so the accelerometer is only on
     *    during the 10s fade tail of an armed timer (not full
     *    playback sessions). */
    private var shakeDetector: ShakeDetector? = null
    private var shakeJob: Job? = null
    /** Tracks whether the detector is currently registered to avoid
     *  redundant register/unregister churn on every state emission. */
    private var shakeListening: Boolean = false

    /** DND / Bedtime mode auto-sleep receiver, registered when the
     *  bedtime-auto-sleep setting is on and playback is active.
     *  Unregistered on destroy or when the setting is flipped off. */
    private var dndReceiver: BroadcastReceiver? = null
    private var dndReceiverRegistered: Boolean = false
    private var bedtimeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()

        player = enginePlayerFactory.create(applicationContext)
        controller.bindPlayer(player)

        // Issue #560 — when the system or another app takes audio focus
        // from us (phone call, video playback, alarm), pause the
        // pipeline gracefully. Spotify/Pocket Casts behavior: speech
        // doesn't auto-resume on focus regain — the user explicitly
        // taps play. The controller's pause() routes through Media3's
        // pauseRouted() which handles both TTS and audio-stream backends.
        audioFocus.setOnFocusLost {
            scope.launch { controller.pause() }
        }

        // "Why are we waiting?" — start the diagnostic monitor here so
        // it's live before the user taps play. The monitor's own start()
        // is idempotent + re-anchors on every call, so a chapter advance
        // mid-session doesn't fight itself.
        audioOutputMonitor.start()

        session = MediaSession.Builder(this, player)
            .setCallback(StoryvoxSessionCallback(controller, scope, autoPlaybackResolver))
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader.Builder(this).build()))
            .setSessionActivity(buildSessionActivity(null, null))
            .build()
        mediaSessionLocator.token = session.token
        // Issue #1232 — publish the legacy MediaSessionCompat.Token so the Auto
        // browser service can setSessionToken() and bind transport controls.
        // Media3's framework platformToken bridges to the compat token.
        mediaSessionLocator.setCompatToken(
            android.support.v4.media.session.MediaSessionCompat.Token.fromToken(session.platformToken),
        )

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_PLAYBACK)
                .setNotificationId(NOTIFICATION_ID)
                .build()
                .apply { setSmallIcon(R.drawable.ic_storyvox_notif) },
        )

        wearBridge.start()

        // Refresh the session activity intent whenever the playing chapter
        // changes so notification taps open the right reader.
        sessionActivityJob = scope.launch {
            controller.state
                .map { it.currentFictionId to it.currentChapterId }
                .distinctUntilChanged()
                .collect { (fid, cid) ->
                    session.setSessionActivity(buildSessionActivity(fid, cid))
                }
        }

        // Issue #150 — register the shake-to-extend detector only
        // during the sleep timer's fade tail. The 10s window is when
        // shaking is meaningful: before fade, the user wouldn't shake
        // proactively; after pause, audio is gone. Outside the window
        // the accelerometer stays off so we don't drain battery on
        // long playback sessions.
        shakeDetector = ShakeDetector(
            context = applicationContext,
            onShake = {
                val remaining = controller.state.value.sleepTimerRemainingMs
                // Issue #1595 — gate the re-arm to the fade tail via the
                // pure [shouldExtendOnShake] decision so a jolt landing a
                // beat after the timer paused is ignored.
                if (shouldExtendOnShake(remaining, SHAKE_FADE_WINDOW_MS)) {
                    // Issue #595 — read the user-tunable extend duration
                    // from the config contract instead of the legacy
                    // hardcoded constant. Off-main launch so the suspend
                    // read doesn't block the accelerometer callback.
                    // `currentShakeExtendMinutes` falls back to 15 (the
                    // legacy value) when the store hasn't emitted yet.
                    Log.w(TAG, "Shake-to-extend: shake detected in fade tail (remaining=${remaining}ms) — extending")
                    scope.launch {
                        val minutes = sleepExtendConfig.currentShakeExtendMinutes()
                        controller.startSleepTimer(SleepTimerMode.Duration(minutes))
                    }
                } else {
                    // #1595 instrumentation — a shake fired but outside the
                    // fade tail. If an on-device repro shows this line but
                    // no "extending", the detector is armed too early/late;
                    // if it shows NO shake lines at all while the fade-tail
                    // "accelerometer registered" line is present, the
                    // gesture isn't crossing the detector threshold.
                    Log.w(TAG, "Shake-to-extend: shake ignored — not in fade tail (remaining=${remaining}ms)")
                }
            },
        )
        shakeJob = scope.launch {
            controller.state
                .map { it.sleepTimerRemainingMs to it.shakeToExtendEnabled }
                .distinctUntilChanged()
                .collect { (remaining, enabled) ->
                    // Issue #1595 — pure [shouldListenForShake] gate: listen
                    // only while the user opted in AND an armed timer is in
                    // its 10s fade tail.
                    val inFadeWindow = shouldListenForShake(remaining, enabled, SHAKE_FADE_WINDOW_MS)
                    if (inFadeWindow && !shakeListening) {
                        val started = shakeDetector?.start() ?: false
                        shakeListening = true
                        Log.w(
                            TAG,
                            "Shake-to-extend: fade tail entered (remaining=${remaining}ms) — " +
                                "accelerometer ${if (started) "registered" else "FAILED to register"}",
                        )
                    } else if (!inFadeWindow && shakeListening) {
                        shakeDetector?.stop()
                        shakeListening = false
                        Log.w(TAG, "Shake-to-extend: fade tail exited — accelerometer released")
                    }
                }
        }

        // Bedtime / DND auto-sleep: register a BroadcastReceiver for
        // ACTION_INTERRUPTION_FILTER_CHANGED. When the phone enters
        // DND (Sleep mode, Bedtime mode, manual toggle) while playback
        // is active and the user has the setting on, auto-arm the
        // sleep timer at the shake-extend duration. The receiver is
        // only registered while the setting is enabled.
        dndReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action != NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) return
                val nm = context.getSystemService(NotificationManager::class.java)
                // Null nm → treat as ALL so we don't spuriously arm.
                val filter = nm?.currentInterruptionFilter
                    ?: NotificationManager.INTERRUPTION_FILTER_ALL
                val dndActive = filter != NotificationManager.INTERRUPTION_FILTER_ALL
                val playing = controller.state.value.isPlaying
                val timerRunning = controller.state.value.sleepTimerRemainingMs != null
                // #1574 ② — entry-level breadcrumb. The prior code only
                // logged INSIDE the arm branch, so an on-device repro could
                // not distinguish "onReceive never fired" (delivery problem:
                // NO line at all) from "fired but a gate failed" (this line
                // present with armed=false). Logs the raw filter Int + all
                // three gate inputs so the failing condition is unambiguous.
                // (The #557 invariant means `isPlaying` is true whenever the
                // MediaSession shows PLAYING — so if this logs isPlaying=false
                // during audible playback, that's a *new* finding.)
                val arm = shouldArmBedtimeSleep(dndActive, playing, timerRunning)
                Log.w(
                    TAG,
                    "Bedtime auto-sleep: filter changed (filter=$filter dndActive=$dndActive " +
                        "isPlaying=$playing timerRunning=$timerRunning) → arm=$arm",
                )
                if (arm) {
                    scope.launch {
                        val minutes = sleepExtendConfig.currentShakeExtendMinutes()
                        controller.startSleepTimer(SleepTimerMode.Duration(minutes))
                        Log.w(TAG, "Bedtime auto-sleep: armed ${minutes}min (DND filter=$filter)")
                    }
                }
            }
        }
        bedtimeJob = scope.launch {
            bedtimeSleepConfig.bedtimeAutoSleepEnabled
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled && !dndReceiverRegistered) {
                        // #1218 / #1574 hygiene — target SDK 36 requires an
                        // explicit export flag; ContextCompat picks
                        // RECEIVER_NOT_EXPORTED on T+ and no-ops below it.
                        // Wrapped in runCatching because Samsung One UI has
                        // thrown SecurityException on notification-policy
                        // APIs (#1218); a throw here previously killed the
                        // collector coroutine so the receiver silently never
                        // registered even with the setting ON.
                        val ok = runCatching {
                            ContextCompat.registerReceiver(
                                this@StoryvoxPlaybackService,
                                dndReceiver,
                                IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
                                ContextCompat.RECEIVER_NOT_EXPORTED,
                            )
                        }.onFailure {
                            Log.w(TAG, "Bedtime auto-sleep: registerReceiver failed: ${it.message}")
                        }.isSuccess
                        dndReceiverRegistered = ok
                        Log.w(TAG, "Bedtime auto-sleep: receiver ${if (ok) "registered" else "registration FAILED"}")
                    } else if (!enabled && dndReceiverRegistered) {
                        runCatching { unregisterReceiver(dndReceiver) }
                        dndReceiverRegistered = false
                        Log.w(TAG, "Bedtime auto-sleep: receiver unregistered (setting off)")
                    }
                }
        }
    }

    /**
     * Builds the PendingIntent that fires when the user taps the playback
     * notification (or the lock-screen tile). We launch [MAIN_ACTIVITY] with
     * extras that [in.jphe.storyvox.navigation.DeepLinkResolver] reads to
     * navigate straight to the reader for the playing chapter. With a null
     * fiction/chapter id (e.g. before playback starts) we still launch
     * MainActivity — the user lands on Library which is the right default.
     *
     * `TaskStackBuilder.getPendingIntent(...)` is documented as nullable on
     * older platforms / under platform PendingIntent broker pressure. The
     * previous `!!` would crash the foreground service on that path; we
     * fall back to a flat `PendingIntent.getActivity` that exercises the
     * simpler broker path before giving up.
     */
    private fun buildSessionActivity(fictionId: String?, chapterId: String?): PendingIntent {
        val launchIntent = Intent().apply {
            component = ComponentName(packageName, MAIN_ACTIVITY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!fictionId.isNullOrBlank() && !chapterId.isNullOrBlank()) {
                putExtra(ReaderIntentContract.EXTRA_FICTION_ID, fictionId)
                putExtra(ReaderIntentContract.EXTRA_CHAPTER_ID, chapterId)
            }
        }
        // FLAG_UPDATE_CURRENT so the new fictionId/chapterId actually overwrite
        // any previous intent extras (PendingIntent equality ignores extras by
        // default — without UPDATE_CURRENT we'd keep firing the original chapter).
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return TaskStackBuilder.create(this)
            .addNextIntent(launchIntent)
            .getPendingIntent(/* requestCode = */ 0, flags)
            ?: PendingIntent.getActivity(this, 0, launchIntent, flags)
    }

    /** Placeholder posted to satisfy Android's 5-sec foreground deadline.
     * It re-renders dynamically as PlaybackState changes so the user never
     * sees a stale "Loading…" — the title tracks the chapter, the content
     * text tracks book/state, and tap routes to the reader for whatever's
     * currently playing. Once Media3's [DefaultMediaNotificationProvider]
     * has its first state to work with, it takes over the same
     * NOTIFICATION_ID with a richer MediaStyle layout. */
    private var placeholderPosted = false
    private var placeholderUpdaterJob: Job? = null

    /**
     * Android requires a foreground service started via `startForegroundService()` to
     * call `startForeground()` within 5 seconds, or the OS kills the app with
     * `ForegroundServiceDidNotStartInTimeException`. Media3's
     * [DefaultMediaNotificationProvider] only posts the real MediaStyle notification
     * once the player has a media item — but storyvox loads a sherpa-onnx model
     * and queues a chapter download before that, which can easily exceed 5s on a
     * cold first listen.
     *
     * We post a placeholder once, then keep updating it from controller.state
     * until Media3 takes over (Media3's calls share NOTIFICATION_ID and replace
     * ours in-place). The placeholder always carries the right
     * [PendingIntent] for tap-to-reader so the notification is functional even
     * during the seconds before Media3 has a chance to render its UI.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!placeholderPosted) {
            postPlaceholder("storyvox", "Starting…", null, null)
            placeholderPosted = true
            // Keep the placeholder fresh until Media3 takes over. Once a
            // chapter is loaded, the placeholder shows the real title +
            // content intent. Media3 will replace this entirely with its
            // MediaStyle layout once it has state to render.
            placeholderUpdaterJob = scope.launch {
                controller.state
                    .map { Triple(it.bookTitle, it.chapterTitle, it.currentFictionId to it.currentChapterId) }
                    .distinctUntilChanged()
                    .collect { (book, chapter, ids) ->
                        val title = chapter?.takeIf { it.isNotBlank() } ?: "storyvox"
                        val content = book?.takeIf { it.isNotBlank() } ?: "Loading…"
                        postPlaceholder(title, content, ids.first, ids.second)
                    }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Build + (re-)post the placeholder notification. Always carries a
     * MediaStyle session binding so transport buttons render, plus a
     * tap-intent into MainActivity with the extras [DeepLinkResolver] uses
     * to land the user on the reader for the playing chapter.
     */
    private fun postPlaceholder(
        title: String,
        content: String,
        fictionId: String?,
        chapterId: String?,
    ) {
        val notif = NotificationCompat.Builder(this, CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_storyvox_notif)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(buildSessionActivity(fictionId, chapterId))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setStyle(MediaStyleNotificationHelper.MediaStyle(session))
            .build()
        // Issue #37: on Android 12+ (API 31), startForeground can throw
        // ForegroundServiceStartNotAllowedException when the OS denies
        // the foreground promotion (battery-saver-killed media-button
        // exemption, OEM customizations, long-paused-then-resume after
        // FG attribution lapsed). The previous code crashed the service
        // on that path. Catch it, log, post a regular (non-foreground)
        // notification so the user has *some* signal, and stopSelf so
        // the service doesn't linger waiting for a promotion that
        // won't arrive.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            // Guard the catch with a runtime version check rather than a
            // typed catch — ForegroundServiceStartNotAllowedException is
            // API 31+, and a typed catch on pre-31 devices crashes the
            // service at class-load time, not just when thrown.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                Log.w(TAG, "FG-start denied; posting regular notification + stopSelf", e)
                runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notif) }
                stopSelf()
            } else {
                throw e
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    /**
     * If the user swipes the app off recents while audio is still playing, keep the
     * service alive — playback continues from the foreground notification. Only stop
     * if we were idle.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!controller.state.value.isPlaying) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        wearBridge.stop()
        // "Why are we waiting?" — stop the diagnostic monitor before
        // the controller unbinds; otherwise the tick loop would briefly
        // see a null player + emit a spurious AudioOutputStuck reason.
        audioOutputMonitor.stop()
        controller.unbindPlayer()
        session.release()
        player.releaseEngine()
        if (shakeListening) {
            shakeDetector?.stop()
            shakeListening = false
        }
        shakeDetector = null
        shakeJob = null
        if (dndReceiverRegistered) {
            // runCatching: unregister can throw if the receiver never
            // fully registered (e.g. a SecurityException swallowed above).
            runCatching { unregisterReceiver(dndReceiver) }
            dndReceiverRegistered = false
        }
        dndReceiver = null
        bedtimeJob = null
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_PLAYBACK) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_PLAYBACK,
                        "Playback",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "Audiobook playback controls"
                        setShowBadge(false)
                    },
                )
            }
        }
    }

    companion object {
        private const val TAG = "StoryvoxPlaybackService"
        const val CHANNEL_PLAYBACK = "playback"
        const val NOTIFICATION_ID = 1042
        /** FQN of the launcher Activity. Hardcoded so :core-playback doesn't
         *  need a dependency on :app. Mirrors the manifest entry. */
        private const val MAIN_ACTIVITY = "in.jphe.storyvox.MainActivity"
        /** Issue #150 — duration of the sleep timer's fade tail. The
         *  shake-to-extend listener only runs when remainingMs is in
         *  this window, matching SleepTimer.FADE_TAIL_MS. Fixed
         *  default for now; if the timer's fade duration ever moves
         *  to settings, plumb that through controller.state too. */
        private const val SHAKE_FADE_WINDOW_MS = 10_000L
        /** Legacy default extension on shake-detect. Pre-#595 this
         *  was the hardcoded source of truth; v1 reads the
         *  user-tunable value via [SleepTimerExtendConfig] at
         *  shake-detect time. Kept as the doc'd fallback value that
         *  matches the contract default when the store hasn't emitted
         *  yet. */
        const val LEGACY_SHAKE_EXTEND_MINUTES = 15
    }
}
