package `in`.jphe.storyvox.playback.sleep

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
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

    fun start(): Boolean {
        if (sensorManager != null) return true
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        sensorManager = sm
        accelerometer = sensor
        recentPeaks.clear()
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
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
