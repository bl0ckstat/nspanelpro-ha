package pro.nspanel.ha2.device

import android.os.Build

enum class PanelGeneration { GEN1, GEN2, UNKNOWN }

/**
 * Per-device tuning detected at runtime so Gen1/Gen2 quirks live in one place.
 *
 * Detection keys off SDK level: the original NSPanel Pro runs Android 8.1 (API 27),
 * the Gen2 runs Android 11 (API 30). [model] is recorded for diagnostics and as an
 * override hook once real Gen2 model strings are observed on hardware — don't branch
 * on guessed model names.
 */
data class DeviceProfile(
    val generation: PanelGeneration,
    val model: String,
    val sdkInt: Int,
    /** Floor for both window- and hardware-level brightness (0..1). */
    val minBrightness: Float,
    /** For distance-reporting sensors: near when value < maximumRange * this. */
    val proximityNearFraction: Float,
    /**
     * For reflectance-reporting sensors: near when the raw value exceeds this.
     *
     * Gen1 panels declare maximumRange = 9 but idle around 43–58 and spike to
     * ~22000 with a hand in front — the reading is reflected IR, not a distance,
     * and rises on approach rather than falling. This sits an order of magnitude
     * above the resting noise band and far below a real detection.
     */
    val proximityReflectanceNear: Float,
    /** Whether BOOT_COMPLETED startActivity is expected to work (pre-API-29). */
    val bootLaunchViaReceiver: Boolean,
) {
    companion object {
        fun detect(
            sdkInt: Int = Build.VERSION.SDK_INT,
            model: String = Build.MODEL ?: "unknown",
        ): DeviceProfile {
            val generation = when {
                sdkInt <= 28 -> PanelGeneration.GEN1
                sdkInt == 30 -> PanelGeneration.GEN2
                else -> PanelGeneration.UNKNOWN
            }
            // Both generations use identical values for now; diverge here as
            // hardware quirks (brightness floor, proximity range) are found.
            return DeviceProfile(
                generation = generation,
                model = model,
                sdkInt = sdkInt,
                minBrightness = 0.01f,
                proximityNearFraction = 0.5f,
                proximityReflectanceNear = 500f,
                bootLaunchViaReceiver = sdkInt < 29,
            )
        }
    }

    /**
     * Whether a raw proximity reading means something is in front of the panel.
     *
     * The two panel generations ship sensors that report on incompatible scales,
     * so the rule is chosen from the reading itself rather than the generation:
     * a value above the sensor's own declared maximum cannot be a distance, so
     * it is treated as reflectance, where near means a large value. Distance
     * sensors keep the original "close to zero" test.
     */
    fun isProximityNear(raw: Float, maxRange: Float, threshold: Float = 0f): Boolean =
        if (maxRange > 0f && raw > maxRange) {
            // Reflectance: near is a *large* reading, so the threshold is a floor.
            raw > if (threshold > 0f) threshold else proximityReflectanceNear
        } else {
            // Distance: near is a *small* reading, so the threshold is a ceiling.
            raw < if (threshold > 0f) threshold else maxRange * proximityNearFraction
        }
}
