package `in`.jphe.storyvox.playback.sleep

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.sqrt

/**
 * Issue #150 — detects a deliberate shake gesture during the sleep
 * timer's fade tail. Wakes the user up enough to extend the timer
 * without making them fish out the phone.
 *
 * Detection recipe (the standard one from Smart AudioBook Player /
 * ListenAudioBook): low-pass-filter the linear-acceleration magnitude
 * (g-subtracted) and trigger when at least [minPeaks] samples cross
 * [thresholdMps2] inside [windowMs]. A single jolt — bumping the bed,
 * a pothole — produces one peak; a deliberate shake produces several
 * in quick succession.
 *
 * Lifecycle: [start] registers the accelerometer with
 * [SensorManager.SENSOR_DELAY_UI] (~60ms cadence — fast enough to
 * sample a wrist flick, slow enough that 10s of listening is a
 * negligible battery hit). [stop] unregisters. The service is
 * responsible for matching these calls to the timer's fade-tail
 * window so the sensor isn't pinned for full playback sessions.
 */
class ShakeDetector(
    private val context: Context,
    private val onShake: () -> Unit,
    private val thresholdMps2: Float = 12f,
    private val windowMs: Long = 500L,
    private val minPeaks: Int = 3,
) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    /** Timestamps (uptime-ms) of recent above-threshold samples. */
    private val recentPeaks: ArrayDeque<Long> = ArrayDeque()

    /** Drops a re-fire window after [onShake] so a single sustained
     *  shake doesn't fire repeatedly while the user is mid-gesture. */
    private var lastFireUptime: Long = 0L
    private val refireGuardMs: Long = 1_500L

    /** #1595 diagnostic — peak linear-accel magnitude (m/s²) seen since the
     *  detector armed or last fired. Logged so an on-device repro shows how
     *  close a shake got to [thresholdMps2]. */
    private var maxMagMps2: Float = 0f

    fun start(): Boolean {
        if (sensorManager != null) return true
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        sensorManager = sm
        accelerometer = sensor
        recentPeaks.clear()
        maxMagMps2 = 0f
        return sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        accelerometer = null
        recentPeaks.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        // Subtract gravity baseline — TYPE_ACCELEROMETER reports
        // gravity-included acceleration. Subtracting g (9.81) from
        // the magnitude gives a linear-acceleration proxy that's
        // ~0 at rest and spikes on motion. (The dedicated
        // TYPE_LINEAR_ACCELERATION sensor isn't on every device, so
        // we derive it ourselves and stay portable.)
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH

        // #1595 diagnostic — log above-baseline samples (skip at-rest jitter
        // so it isn't a firehose) so an on-device repro shows how close a
        // shake got to firing: current magnitude, running max, the fire
        // threshold, and the peak count in the window. Log.w to survive the
        // release Log.i/d strip (#1276). Content-free — magnitudes + counts
        // only. Revert with the rest of the sleep diagnostics once #1595 is
        // root-caused.
        if (magnitude >= LOG_BASELINE_MPS2) {
            if (magnitude > maxMagMps2) maxMagMps2 = magnitude
            Log.w(
                TAG,
                "Shake sample: mag=%.1f maxMag=%.1f thr=%.0f peaks=%d/%d".format(
                    magnitude, maxMagMps2, thresholdMps2, recentPeaks.size, minPeaks,
                ),
            )
        }

        if (magnitude < thresholdMps2) return
        val now = SystemClock.uptimeMillis()
        recentPeaks.addLast(now)
        // Drop peaks that fell out of the window so [size] reflects
        // peaks-per-window without growing unbounded.
        while (recentPeaks.isNotEmpty() && now - recentPeaks.first() > windowMs) {
            recentPeaks.removeFirst()
        }
        if (recentPeaks.size >= minPeaks && now - lastFireUptime > refireGuardMs) {
            lastFireUptime = now
            recentPeaks.clear()
            maxMagMps2 = 0f
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        // Same tag as StoryvoxPlaybackService on purpose: the #1595 repro
        // filters `logcat -s StoryvoxPlaybackService:*`, so co-locating keeps
        // the whole shake narrative (fade-tail arm → samples → fire) in one
        // stream instead of hiding these lines under a separate tag.
        private const val TAG = "StoryvoxPlaybackService"

        /** #1595 diagnostic — only log samples above this linear-accel floor
         *  (m/s²). At-rest magnitude is ~0 after gravity subtraction, so a
         *  low floor captures real motion (including near-misses below the
         *  fire threshold) without logging idle jitter. */
        private const val LOG_BASELINE_MPS2 = 4f
    }
}
