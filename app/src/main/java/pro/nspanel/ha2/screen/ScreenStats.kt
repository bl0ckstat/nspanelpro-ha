package pro.nspanel.ha2.screen

import pro.nspanel.ha2.device.PanelGeneration

data class ScreenStats(
    val lux: Float = 100f,
    val proximityNear: Boolean = false,
    /** Raw sensor reading and the range it claims — Gen1 panels report a
     *  reflectance-style value that bears no relation to maximumRange, so both
     *  are surfaced for calibration. -1 until the first event arrives. */
    val proximityRaw: Float = -1f,
    val proximityMaxRange: Float = 0f,
    /** Extremes seen since launch. A hand passing the panel at any time leaves a
     *  trace here, so calibration doesn't depend on watching at the right moment. */
    val proximityRawMin: Float = Float.NaN,
    val proximityRawMax: Float = Float.NaN,
    /** The resting-level estimate the adaptive trigger is derived from, and
     *  the trigger itself — surfaced so calibration is observable. */
    val proximityBaseline: Float = Float.NaN,
    val proximityTrigger: Float = Float.NaN,
    val canWriteSettings: Boolean = false,
    val deviceModel: String = "",
    val generation: PanelGeneration = PanelGeneration.UNKNOWN,
    val sdkInt: Int = 0,
    val screenState: String = "AWAKE",
    val appliedBrightness: Float = 0f,
)
