package pro.nspanel.ha2.diag

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONArray
import org.json.JSONObject
import pro.nspanel.ha2.data.AppSettings
import pro.nspanel.ha2.data.PanelConfig
import pro.nspanel.ha2.device.DeviceProfile
import pro.nspanel.ha2.device.NetworkInfo
import pro.nspanel.ha2.device.RelayController

/**
 * Builds the diagnostics JSON served by [DiagServer] and returned by DiagReceiver.
 * Read-only snapshot; never includes secrets (the cached YAML body is reported as
 * a character count only).
 */
object DiagSnapshot {

    fun build(context: Context): JSONObject {
        val stats = DiagState.stats
        val config = DiagState.panelConfig
        val settings = DiagState.appSettings
        val profile = DeviceProfile.detect()

        val pkgInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()

        return JSONObject()
            .put("app", JSONObject().apply {
                put("package", context.packageName)
                put("version_name", pkgInfo?.versionName ?: "unknown")
                put("version_code", pkgInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: -1)
                put("uptime_seconds", (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000)
                put("activity_alive", DiagState.activityAlive)
            })
            .put("device", JSONObject().apply {
                put("model", profile.model)
                put("generation", profile.generation.name)
                put("sdk_int", profile.sdkInt)
            })
            .put("screen", JSONObject().apply {
                put("state", stats.screenState)
                put("brightness_applied", stats.appliedBrightness.toDouble())
                put("lux", stats.lux.toDouble())
                put("proximity_near", stats.proximityNear)
                put("proximity_raw", stats.proximityRaw.toDouble())
                put("proximity_max_range", stats.proximityMaxRange.toDouble())
                put(
                    "proximity_raw_min",
                    if (stats.proximityRawMin.isNaN()) JSONObject.NULL
                    else stats.proximityRawMin.toDouble(),
                )
                put(
                    "proximity_baseline",
                    if (stats.proximityBaseline.isNaN()) JSONObject.NULL
                    else stats.proximityBaseline.toDouble(),
                )
                put(
                    "proximity_trigger",
                    if (stats.proximityTrigger.isNaN()) JSONObject.NULL
                    else stats.proximityTrigger.toDouble(),
                )
                put(
                    "proximity_raw_max",
                    if (stats.proximityRawMax.isNaN()) JSONObject.NULL
                    else stats.proximityRawMax.toDouble(),
                )
                put("can_write_settings", stats.canWriteSettings)
            })
            .put("network", networkJson(context))
            .put("relays", relayJson())
            .put("config", configJson(config, settings))
            .put("settings", JSONObject().apply {
                put("ha_url", settings?.homeAssistantUrl ?: JSONObject.NULL)
                put("yaml_url", settings?.panelYamlUrl ?: JSONObject.NULL)
                put("yaml_cached_chars", settings?.lastPanelYamlRaw?.length ?: 0)
            })
            .put("memory", JSONObject().apply {
                val rt = Runtime.getRuntime()
                put("used_mb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024))
                put("max_mb", rt.maxMemory() / (1024 * 1024))
            })
    }

    private fun networkJson(context: Context): JSONObject {
        val net = NetworkInfo.read(context)
        return JSONObject().apply {
            put("ip_address", net.ipAddress ?: JSONObject.NULL)
            put("gateway", net.gateway ?: JSONObject.NULL)
            put("dns", JSONArray(net.dns))
            put("ssid", net.ssid ?: JSONObject.NULL)
            put("rssi_dbm", net.rssiDbm ?: JSONObject.NULL)
            put("link_speed_mbps", net.linkSpeedMbps ?: JSONObject.NULL)
            put("frequency_mhz", net.frequencyMhz ?: JSONObject.NULL)
            put("wifi_enabled", net.wifiEnabled)
            put("connected", net.connected)
            put("validated", net.validated)
            put("transport", net.transport ?: JSONObject.NULL)
        }
    }

    private fun relayJson(): JSONObject {
        val state = RelayController.read()
        return JSONObject().apply {
            put("supported", state.supported)
            put("relay1", state.relay1 ?: JSONObject.NULL)
            put("relay2", state.relay2 ?: JSONObject.NULL)
            put("mode", state.mode ?: JSONObject.NULL)
        }
    }

    private fun configJson(config: PanelConfig?, settings: AppSettings?): JSONObject {
        val obj = JSONObject()
        if (config == null) return obj.put("source", JSONObject.NULL)
        return obj.apply {
            put("source", if (settings?.panelYamlUrl.isNullOrBlank()) "manual" else "yaml")
            put("screen_brightness", config.screenBrightness)
            put("screen_timeout_seconds", config.screenTimeoutSeconds)
            put("proximity_wake", config.proximityWake)
            // Both halves of the trigger, so a panel reporting the wrong side
            // of it can be diagnosed without opening its settings sheet.
            put("proximity_threshold", config.proximityThreshold.toDouble())
            put("proximity_near_high", config.proximityNearHigh)
            put("idle_dim_percent", config.idleDimPercent)
            put("show_status_bar", config.showStatusBar)
            put("report_interval_seconds", config.sensorReportIntervalSeconds)
            put("auto_brightness", config.autoBrightness)
            put("diag_port", config.diagPort)
        }
    }
}
