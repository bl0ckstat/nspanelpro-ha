package pro.nspanel.ha2.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        val idleDim = intPreferencesKey("manual_idle_dim_percent")
        val showStatusBar = booleanPreferencesKey("manual_show_status_bar")
        val sensorInterval = intPreferencesKey("manual_sensor_interval_seconds")
        val autoBrightness = booleanPreferencesKey("manual_auto_brightness")
        val diagPort = intPreferencesKey("manual_diag_port")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            homeAssistantUrl = prefs[Keys.haUrl].orEmpty(),
            panelYamlUrl = prefs[Keys.yamlUrl].orEmpty(),
            lastPanelYamlRaw = prefs[Keys.yamlRaw].orEmpty(),
            manualBrightness = prefs[Keys.brightness] ?: PanelConfig.DEFAULT.screenBrightness,
            manualTimeoutSeconds = prefs[Keys.timeout] ?: PanelConfig.DEFAULT.screenTimeoutSeconds,
            manualProximityWake = prefs[Keys.proximityWake] ?: PanelConfig.DEFAULT.proximityWake,
            manualIdleDimPercent = prefs[Keys.idleDim] ?: PanelConfig.DEFAULT.idleDimPercent,
            manualShowStatusBar = prefs[Keys.showStatusBar] ?: PanelConfig.DEFAULT.showStatusBar,
            manualSensorIntervalSeconds = prefs[Keys.sensorInterval] ?: PanelConfig.DEFAULT.sensorReportIntervalSeconds,
            manualAutoBrightness = prefs[Keys.autoBrightness] ?: PanelConfig.DEFAULT.autoBrightness,
            manualDiagPort = prefs[Keys.diagPort] ?: PanelConfig.DEFAULT.diagPort,
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
                manualIdleDimPercent = prefs[Keys.idleDim] ?: PanelConfig.DEFAULT.idleDimPercent,
                manualShowStatusBar = prefs[Keys.showStatusBar] ?: PanelConfig.DEFAULT.showStatusBar,
                manualSensorIntervalSeconds = prefs[Keys.sensorInterval] ?: PanelConfig.DEFAULT.sensorReportIntervalSeconds,
                manualAutoBrightness = prefs[Keys.autoBrightness] ?: PanelConfig.DEFAULT.autoBrightness,
            )
            val next = transform(current)
            prefs[Keys.haUrl] = next.homeAssistantUrl
            prefs[Keys.yamlUrl] = next.panelYamlUrl
            prefs[Keys.yamlRaw] = next.lastPanelYamlRaw
            prefs[Keys.brightness] = next.manualBrightness
            prefs[Keys.timeout] = next.manualTimeoutSeconds
            prefs[Keys.proximityWake] = next.manualProximityWake
            prefs[Keys.idleDim] = next.manualIdleDimPercent
            prefs[Keys.showStatusBar] = next.manualShowStatusBar
            prefs[Keys.sensorInterval] = next.manualSensorIntervalSeconds
            prefs[Keys.autoBrightness] = next.manualAutoBrightness
            prefs[Keys.diagPort] = next.manualDiagPort
        }
    }
}
