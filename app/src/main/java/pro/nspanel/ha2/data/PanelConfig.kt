package pro.nspanel.ha2.data

/**
 * Effective panel behavior settings. [DEFAULT] is used when no YAML is loaded;
 * loaded YAML overrides only keys that appear in the file.
 */
data class PanelConfig(
    val screenBrightness: Int,
    val screenTimeoutSeconds: Int,
    val proximityWake: Boolean,
    val defaultDashboard: String,
    val idleDimPercent: Int,
    val showStatusBar: Boolean,
    val sensorReportIntervalSeconds: Int,
    val autoBrightness: Boolean,
    /** TCP port for the diagnostics HTTP endpoint; 0 disables the server. */
    val diagPort: Int,
) {
    companion object {
        val DEFAULT = PanelConfig(
            screenBrightness = 180,
            screenTimeoutSeconds = 120,
            proximityWake = true,
            defaultDashboard = "/lovelace/default_view",
            idleDimPercent = 40,
            showStatusBar = false,
            sensorReportIntervalSeconds = 30,
            autoBrightness = true,
            diagPort = 8377,
        )
    }
}
