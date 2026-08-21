package pro.nspanel.ha2.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nspanel_ha_settings",
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val haUrl = stringPreferencesKey("ha_url")
        val yamlUrl = stringPreferencesKey("panel_yaml_url")
        val yamlRaw = stringPreferencesKey("panel_yaml_raw")
        val brightness = intPreferencesKey("manual_brightness")
        val timeout = intPreferencesKey("manual_timeout_seconds")
        val proximityWake = booleanPreferencesKey("manual_proximity_wake")
        val proximityThreshold = floatPreferencesKey("manual_proximity_threshold")
        val proximityNearHigh = booleanPreferencesKey("manual_proximity_near_high")
        val idleDim = intPreferencesKey("manual_idle_dim_percent")
        val showStatusBar = booleanPreferencesKey("manual_show_status_bar")
        val sensorInterval = intPreferencesKey("manual_sensor_interval_seconds")
        val autoBrightness = booleanPreferencesKey("manual_auto_brightness")
        val luxDark = floatPreferencesKey("manual_lux_dark")
        val luxBright = floatPreferencesKey("manual_lux_bright")
        val diagPort = intPreferencesKey("manual_diag_port")
        val mqttBroker = stringPreferencesKey("manual_mqtt_broker")
        val mqttTopic = stringPreferencesKey("manual_mqtt_topic")
        val mqttUsername = stringPreferencesKey("manual_mqtt_username")
        val mqttPassword = stringPreferencesKey("manual_mqtt_password")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            homeAssistantUrl = prefs[Keys.haUrl].orEmpty(),
            panelYamlUrl = prefs[Keys.yamlUrl].orEmpty(),
            lastPanelYamlRaw = prefs[Keys.yamlRaw].orEmpty(),
            manualBrightness = prefs[Keys.brightness] ?: PanelConfig.DEFAULT.screenBrightness,
            manualTimeoutSeconds = prefs[Keys.timeout] ?: PanelConfig.DEFAULT.screenTimeoutSeconds,
            manualProximityWake = prefs[Keys.proximityWake] ?: PanelConfig.DEFAULT.proximityWake,
            manualProximityThreshold = prefs[Keys.proximityThreshold]
                ?: PanelConfig.DEFAULT.proximityThreshold,
            manualProximityNearHigh = prefs[Keys.proximityNearHigh],
            manualIdleDimPercent = prefs[Keys.idleDim] ?: PanelConfig.DEFAULT.idleDimPercent,
            manualShowStatusBar = prefs[Keys.showStatusBar] ?: PanelConfig.DEFAULT.showStatusBar,
            manualSensorIntervalSeconds = prefs[Keys.sensorInterval] ?: PanelConfig.DEFAULT.sensorReportIntervalSeconds,
            manualAutoBrightness = prefs[Keys.autoBrightness] ?: PanelConfig.DEFAULT.autoBrightness,
            manualLuxDark = prefs[Keys.luxDark] ?: PanelConfig.DEFAULT.luxDark,
            manualLuxBright = prefs[Keys.luxBright] ?: PanelConfig.DEFAULT.luxBright,
            manualDiagPort = prefs[Keys.diagPort] ?: PanelConfig.DEFAULT.diagPort,
            manualMqttBroker = prefs[Keys.mqttBroker].orEmpty(),
            manualMqttTopic = prefs[Keys.mqttTopic].orEmpty(),
            manualMqttUsername = prefs[Keys.mqttUsername].orEmpty(),
            manualMqttPassword = prefs[Keys.mqttPassword].orEmpty(),
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val current = AppSettings(
                homeAssistantUrl = prefs[Keys.haUrl].orEmpty(),
                panelYamlUrl = prefs[Keys.yamlUrl].orEmpty(),
                lastPanelYamlRaw = prefs[Keys.yamlRaw].orEmpty(),
                manualBrightness = prefs[Keys.brightness] ?: PanelConfig.DEFAULT.screenBrightness,
                manualTimeoutSeconds = prefs[Keys.timeout] ?: PanelConfig.DEFAULT.screenTimeoutSeconds,
                manualProximityWake = prefs[Keys.proximityWake] ?: PanelConfig.DEFAULT.proximityWake,
                manualProximityThreshold = prefs[Keys.proximityThreshold]
                    ?: PanelConfig.DEFAULT.proximityThreshold,
                manualProximityNearHigh = prefs[Keys.proximityNearHigh],
                manualIdleDimPercent = prefs[Keys.idleDim] ?: PanelConfig.DEFAULT.idleDimPercent,
                manualShowStatusBar = prefs[Keys.showStatusBar] ?: PanelConfig.DEFAULT.showStatusBar,
                manualSensorIntervalSeconds = prefs[Keys.sensorInterval] ?: PanelConfig.DEFAULT.sensorReportIntervalSeconds,
                manualAutoBrightness = prefs[Keys.autoBrightness] ?: PanelConfig.DEFAULT.autoBrightness,
                manualLuxDark = prefs[Keys.luxDark] ?: PanelConfig.DEFAULT.luxDark,
                manualLuxBright = prefs[Keys.luxBright] ?: PanelConfig.DEFAULT.luxBright,
                // Every stored key has to be read back here, not just the ones
                // a caller is likely to change: `current` is written out whole
                // below, so anything missed is silently reset to its default.
                // manualDiagPort was missed, which reset a customised
                // diagnostics port on any unrelated settings write.
                manualDiagPort = prefs[Keys.diagPort] ?: PanelConfig.DEFAULT.diagPort,
                manualMqttBroker = prefs[Keys.mqttBroker].orEmpty(),
                manualMqttTopic = prefs[Keys.mqttTopic].orEmpty(),
                manualMqttUsername = prefs[Keys.mqttUsername].orEmpty(),
                manualMqttPassword = prefs[Keys.mqttPassword].orEmpty(),
            )
            val next = transform(current)
            prefs[Keys.haUrl] = next.homeAssistantUrl
            prefs[Keys.yamlUrl] = next.panelYamlUrl
            prefs[Keys.yamlRaw] = next.lastPanelYamlRaw
            prefs[Keys.brightness] = next.manualBrightness
            prefs[Keys.timeout] = next.manualTimeoutSeconds
            prefs[Keys.proximityWake] = next.manualProximityWake
            prefs[Keys.proximityThreshold] = next.manualProximityThreshold
            next.manualProximityNearHigh?.let { prefs[Keys.proximityNearHigh] = it }
                ?: prefs.remove(Keys.proximityNearHigh)
            prefs[Keys.idleDim] = next.manualIdleDimPercent
            prefs[Keys.showStatusBar] = next.manualShowStatusBar
            prefs[Keys.sensorInterval] = next.manualSensorIntervalSeconds
            prefs[Keys.autoBrightness] = next.manualAutoBrightness
            prefs[Keys.luxDark] = next.manualLuxDark
            prefs[Keys.luxBright] = next.manualLuxBright
            prefs[Keys.diagPort] = next.manualDiagPort
            prefs[Keys.mqttBroker] = next.manualMqttBroker
            prefs[Keys.mqttTopic] = next.manualMqttTopic
            prefs[Keys.mqttUsername] = next.manualMqttUsername
            prefs[Keys.mqttPassword] = next.manualMqttPassword
        }
    }
}
