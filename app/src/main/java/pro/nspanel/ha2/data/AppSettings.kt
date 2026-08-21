package pro.nspanel.ha2.data

data class AppSettings(
    val homeAssistantUrl: String = "",
    val panelYamlUrl: String = "",
    val lastPanelYamlRaw: String = "",
    // Manual panel config — used when panelYamlUrl is blank
    val manualBrightness: Int = PanelConfig.DEFAULT.screenBrightness,
    val manualTimeoutSeconds: Int = PanelConfig.DEFAULT.screenTimeoutSeconds,
    val manualProximityWake: Boolean = PanelConfig.DEFAULT.proximityWake,
    val manualProximityThreshold: Float = PanelConfig.DEFAULT.proximityThreshold,
    val manualProximityNearHigh: Boolean? = PanelConfig.DEFAULT.proximityNearHigh,
    val manualIdleDimPercent: Int = PanelConfig.DEFAULT.idleDimPercent,
    val manualShowStatusBar: Boolean = PanelConfig.DEFAULT.showStatusBar,
    val manualSensorIntervalSeconds: Int = PanelConfig.DEFAULT.sensorReportIntervalSeconds,
    val manualAutoBrightness: Boolean = PanelConfig.DEFAULT.autoBrightness,
    val manualLuxDark: Float = PanelConfig.DEFAULT.luxDark,
    val manualLuxBright: Float = PanelConfig.DEFAULT.luxBright,
    val manualDiagPort: Int = PanelConfig.DEFAULT.diagPort,
    val manualMqttBroker: String = "",
    val manualMqttTopic: String = "",
    val manualMqttUsername: String = "",
    val manualMqttPassword: String = "",
)

/** Effective panel config when no YAML is loaded (panelYamlUrl blank). */
fun AppSettings.toManualPanelConfig() = PanelConfig(
    screenBrightness = manualBrightness,
    screenTimeoutSeconds = manualTimeoutSeconds,
    proximityWake = manualProximityWake,
    proximityThreshold = manualProximityThreshold,
    proximityNearHigh = manualProximityNearHigh,
    defaultDashboard = PanelConfig.DEFAULT.defaultDashboard,
    idleDimPercent = manualIdleDimPercent,
    showStatusBar = manualShowStatusBar,
    sensorReportIntervalSeconds = manualSensorIntervalSeconds,
    autoBrightness = manualAutoBrightness,
    luxDark = manualLuxDark,
    luxBright = manualLuxBright,
    diagPort = manualDiagPort,
    mqttBroker = manualMqttBroker,
    mqttTopic = manualMqttTopic,
    mqttUsername = manualMqttUsername,
    mqttPassword = manualMqttPassword,
)
