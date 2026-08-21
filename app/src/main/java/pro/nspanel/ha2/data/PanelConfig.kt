package pro.nspanel.ha2.data

/**
 * Effective panel behavior settings. [DEFAULT] is used when no YAML is loaded;
 * loaded YAML overrides only keys that appear in the file.
 */
data class PanelConfig(
    val screenBrightness: Int,
    val screenTimeoutSeconds: Int,
    val proximityWake: Boolean,
    /**
     * Reading at which the proximity sensor counts as "someone is there".
     * 0 keeps the device profile's own figure, which is the right answer on
     * hardware the profile knows; panels vary enough that a fixed number
     * cannot serve all of them, and the raw reading is on screen next to this
     * setting so it can be dialled in by hand.
     */
    val proximityThreshold: Float,
    /**
     * Which direction means "someone is there".
     *
     * A reflectance sensor reads LOW with nothing in front of it and climbs as
     * a hand approaches; a distance sensor does the opposite. This cannot be
     * inferred from a single reading — panels in the same family, on the same
     * board, differ — so it is stated rather than guessed. True is reflectance,
     * which is what the NSPanel Pro hardware reports.
     */
    val proximityNearHigh: Boolean?,
    val defaultDashboard: String,
    val idleDimPercent: Int,
    val showStatusBar: Boolean,
    val sensorReportIntervalSeconds: Int,
    val autoBrightness: Boolean,
    /**
     * The lux readings the auto-brightness curve is anchored to: at or below
     * [luxDark] the screen sits at its dimmest awake level, at or above
     * [luxBright] it runs full. The old fixed curve pinned full at 500 lux;
     * rooms here read 27000 in daylight and 150 at night lighting, so the
     * anchors are per-panel and the calibration wizard measures them.
     */
    val luxDark: Float,
    val luxBright: Float,
    /** TCP port for the diagnostics HTTP endpoint; 0 disables the server. */
    val diagPort: Int,
    /** Broker the panel subscribes to for sound commands, e.g. the doorbell
     *  chime. Blank leaves the client stopped. */
    val mqttBroker: String,
    /** Comma-separated topics; a panel normally listens on its own and on a
     *  fleet-wide topic. */
    val mqttTopic: String,
    val mqttUsername: String,
    val mqttPassword: String,
) {
    companion object {
        val DEFAULT = PanelConfig(
            screenBrightness = 180,
            screenTimeoutSeconds = 120,
            proximityWake = true,
            proximityThreshold = 0f,
            proximityNearHigh = null,
            defaultDashboard = "/lovelace/default_view",
            idleDimPercent = 40,
            showStatusBar = false,
            sensorReportIntervalSeconds = 30,
            autoBrightness = true,
            // Wizard-measured in the house these panels serve (panel .46,
            // 2026-08-21) and adopted as the fleet default: a lived-in room's
            // night lighting, not a laboratory dark. Individual rooms still
            // calibrate over the top with the wizard.
            luxDark = 401f,
            luxBright = 18341f,
            diagPort = 8377,
            mqttBroker = "",
            mqttTopic = "",
            mqttUsername = "",
            mqttPassword = "",
        )
    }
}
