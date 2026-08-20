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
    /**
     * Which way this generation's sensor reads, when the config says nothing.
     *
     * Higher-means-near for every panel we have met. Gen1 reports reflected
     * IR that climbs on approach; Gen2's "proximity" is a presence radar
     * mapped to 1 present / 0 clear. v0.5.5 assumed Gen2 followed the phone
     * convention (0 = near, against the ear) — measured on hardware, it is
     * the opposite, and the panel woke when the room emptied. What actually
     * differs between the generations is the scale, carried by
     * [proximityDefaultTrigger].
     */
    val proximityNearHigh: Boolean,
    /** The trigger used when the config sets none: between the resting and
     *  detected readings for this generation's sensor. */
    val proximityDefaultTrigger: Float,
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
            // The generations diverge on how their proximity sensors report.
            // Measured across a fleet of both: Gen1 idles in the tens and
            // spikes past 40000 with a hand in front; Gen2 reports only 0 or
            // its declared maximum of 1, Android's ordinary near/far.
            return DeviceProfile(
                generation = generation,
                model = model,
                sdkInt = sdkInt,
                minBrightness = 0.01f,
                proximityNearFraction = 0.5f,
                proximityReflectanceNear = 500f,
                proximityNearHigh = true,
                // Gen1 idles in the tens and spikes past 40000; the radar in
                // Gen2 only ever says 0 or 1.
                proximityDefaultTrigger =
                    if (generation == PanelGeneration.GEN2) 0.5f else 500f,
                bootLaunchViaReceiver = sdkInt < 29,
            )
        }
    }

    /**
     * Whether a raw proximity reading means something is in front of the panel.
     *
     * The direction is stated by [nearHigh], not inferred. It used to be
     * inferred — a reading above the sensor's declared maximum was taken as
     * reflectance — and that quietly inverted whole panels: the 120mm units
     * rest at 0 against a declared range of 9, so they failed the test, took
     * the distance rule, and reported "near" continuously with nothing in
     * front of them. Panels on the same board with the same model string
     * disagree about this, so there is nothing reliable to infer it from.
     */
    fun isProximityNear(
        raw: Float,
        maxRange: Float,
        threshold: Float = 0f,
        nearHigh: Boolean = true,
    ): Boolean {
        val trigger = when {
            threshold > 0f -> threshold
            nearHigh -> proximityDefaultTrigger
            else -> maxRange * proximityNearFraction
        }
        return if (nearHigh) raw > trigger else raw < trigger
    }

}
