package pro.nspanel.ha2.screen

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pro.nspanel.ha2.data.PanelConfig
import pro.nspanel.ha2.device.DeviceProfile
import kotlin.math.log10

/**
 * Manages screen brightness, idle dimming, and sensor-driven wake for the main Activity.
 *
 * When WRITE_SETTINGS is granted the manager uses Settings.System.SCREEN_BRIGHTNESS
 * (hardware level) so dimming works regardless of any system auto-brightness fighting
 * the window-layer override. It also pins SCREEN_BRIGHTNESS_MODE to MANUAL and sets
 * SCREEN_OFF_TIMEOUT to max so the OS never pre-empts our own idle logic.
 *
 * Without WRITE_SETTINGS only window.attributes.screenBrightness is used; dimming may
 * be unreliable on some devices if system auto-brightness is active.
 *
 * Grant via ADB: adb shell appops set pro.nspanel.ha2 WRITE_SETTINGS allow
 *
 * State machine: AWAKE → (screenTimeoutSeconds) → DIM → (60 s) → OFF
 * Touch or proximity-near resets to AWAKE.
 */
class ScreenManager(
    private val activity: Activity,
    private val profile: DeviceProfile = DeviceProfile.detect(),
) : SensorEventListener {

    private enum class State { AWAKE, DIM, OFF }

    private val sensorManager =
        activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximitySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val lightSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var idleJob: Job? = null
    private var offJob: Job? = null

    private var state = State.AWAKE
    private var config = PanelConfig.DEFAULT
    private var running = false
    private var smoothedLux = 100f

    private val _stats = MutableStateFlow(
        ScreenStats(
            deviceModel = profile.model,
            generation = profile.generation,
            sdkInt = profile.sdkInt,
        ),
    )
    val stats: StateFlow<ScreenStats> = _stats.asStateFlow()

    // ── Public API ────────────────────────────────────────────────────────────

    fun applyConfig(newConfig: PanelConfig) {
        config = newConfig
        // Re-judge the last reading against the new trigger. These sensors only
        // report on change, so without this a threshold typed into the settings
        // sheet does nothing visible until someone walks past — which is exactly
        // the moment you are not watching the number you just typed.
        reevaluateProximity()
        if (!running) return
        when (state) {
            State.AWAKE -> { applyBrightness(awakeBrightness()); rescheduleIdle() }
            State.DIM -> applyBrightness(dimBrightness())
            State.OFF -> applyBrightness(profile.minBrightness)
        }
    }

    fun onUserInteraction() {
        if (!running) return
        if (state != State.AWAKE) wake() else rescheduleIdle()
    }

    fun resume() {
        running = true
        setState(State.AWAKE)
        _stats.update { it.copy(canWriteSettings = Settings.System.canWrite(activity)) }
        if (Settings.System.canWrite(activity)) {
            // Prevent system auto-brightness and OS idle timeout from fighting our logic.
            Settings.System.putInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                activity.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                Int.MAX_VALUE,
            )
        }
        applyBrightness(awakeBrightness())
        rescheduleIdle()
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun pause() {
        running = false
        sensorManager.unregisterListener(this)
        idleJob?.cancel(); idleJob = null
        offJob?.cancel(); offJob = null
    }

    fun destroy() {
        pause()
        scope.cancel()
    }

    /** Recompute "near" from the reading already in hand. */
    private fun reevaluateProximity() {
        val last = _stats.value
        if (last.proximityRaw < 0f) return
        val near = profile.isProximityNear(
            last.proximityRaw,
            last.proximityMaxRange,
            config.proximityThreshold,
            config.proximityNearHigh ?: profile.proximityNearHigh,
        )
        if (near != last.proximityNear) _stats.update { it.copy(proximityNear = near) }
    }

    // ── SensorEventListener ───────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val maxRange = proximitySensor?.maximumRange ?: 5f
                val raw = event.values[0]
                val near = profile.isProximityNear(raw, maxRange, config.proximityThreshold,
                    config.proximityNearHigh ?: profile.proximityNearHigh)
                _stats.update {
                    it.copy(
                        proximityNear = near,
                        proximityRaw = raw,
                        proximityMaxRange = maxRange,
                        proximityRawMin = if (it.proximityRawMin.isNaN()) raw
                            else minOf(it.proximityRawMin, raw),
                        proximityRawMax = if (it.proximityRawMax.isNaN()) raw
                            else maxOf(it.proximityRawMax, raw),
                    )
                }
                if (near && config.proximityWake && state != State.AWAKE) wake()
                // Presence ended: the room just emptied, so the idle clock
                // starts now, not back at the last touch.
                if (!near && config.proximityWake && state == State.AWAKE) rescheduleIdle()
            }
            Sensor.TYPE_LIGHT -> {
                smoothedLux = smoothedLux * (1f - LUX_EMA_ALPHA) + event.values[0] * LUX_EMA_ALPHA
                _stats.update { it.copy(lux = smoothedLux) }
                if (state == State.AWAKE) applyBrightness(awakeBrightness())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun wake() {
        setState(State.AWAKE)
        applyBrightness(awakeBrightness())
        rescheduleIdle()
    }

    private fun setState(next: State) {
        state = next
        _stats.update { it.copy(screenState = next.name) }
    }

    private fun rescheduleIdle() {
        idleJob?.cancel()
        offJob?.cancel()
        idleJob = scope.launch {
            delay(config.screenTimeoutSeconds * 1_000L)
            // Someone is still there — don't dim in their face. This matters
            // most on the Gen2 radar, which reports presence *changes* only:
            // once the panel dimmed with someone in the room, no further event
            // could ever arrive to wake it, because nothing changed. Waking on
            // arrival and dimming during presence were fighting each other.
            if (config.proximityWake && _stats.value.proximityNear) {
                rescheduleIdle()
                return@launch
            }
            setState(State.DIM)
            applyBrightness(dimBrightness())
            offJob = scope.launch {
                delay(OFF_DELAY_MS)
                setState(State.OFF)
                applyBrightness(profile.minBrightness)
            }
        }
    }

    private fun awakeBrightness(): Float {
        val maxFraction = config.screenBrightness / 255f
        return if (config.autoBrightness) {
            (maxFraction * luxToFactor(smoothedLux)).coerceIn(profile.minBrightness, maxFraction)
        } else {
            maxFraction.coerceAtLeast(profile.minBrightness)
        }
    }

    private fun dimBrightness(): Float =
        (config.screenBrightness / 255f * (config.idleDimPercent / 100f)).coerceAtLeast(profile.minBrightness)

    private fun applyBrightness(level: Float) {
        val clamped = level.coerceIn(profile.minBrightness, 1.0f)
        _stats.update { it.copy(appliedBrightness = clamped) }
        // Hardware-level brightness via system settings — works even when system
        // auto-brightness is active on some devices.
        if (Settings.System.canWrite(activity)) {
            val value255 = (clamped * 255f).toInt().coerceIn(1, 255)
            Settings.System.putInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value255,
            )
        }
        // Window-level override — immediate visual effect, no permission needed.
        activity.runOnUiThread {
            val lp = activity.window.attributes
            lp.screenBrightness = clamped
            activity.window.attributes = lp
        }
    }

    private companion object {
        const val OFF_DELAY_MS = 60_000L
        const val LUX_EMA_ALPHA = 0.08f
        const val LUX_FULL = 500f
        const val MIN_AUTO_FACTOR = 0.12f

        fun luxToFactor(lux: Float): Float {
            if (lux <= 0f) return MIN_AUTO_FACTOR
            return (log10(lux + 1.0) / log10(LUX_FULL + 1.0)).toFloat()
                .coerceIn(MIN_AUTO_FACTOR, 1.0f)
        }
    }
}
