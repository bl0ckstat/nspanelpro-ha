package pro.nspanel.ha2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import pro.nspanel.ha2.data.SettingsRepository
import pro.nspanel.ha2.panel.YamlConfigFetcher

/**
 * Receives ADB-pushed config updates so the deploy script can configure devices remotely.
 *
 * Trigger via ADB (all extras optional; only present ones override stored values):
 *
 *   adb shell am broadcast -a pro.nspanel.ha2.PUSH_CONFIG \
 *     -n pro.nspanel.ha2/.ConfigPushReceiver \
 *     --es ha_url "http://homeassistant.local:8123" \
 *     --ei screen_brightness 180 \
 *     --ei screen_timeout_seconds 120 \
 *     --ez proximity_wake true \
 *     --ei idle_dim_percent 40 \
 *     --ez show_status_bar false \
 *     --ei report_interval_seconds 30 \
 *     --ez auto_brightness true \
 *     --ei diag_port 8377
 *
 * If yaml_url is supplied the app fetches and caches the panel YAML immediately,
 * which takes precedence over any individual panel config extras above.
 */
class ConfigPushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                val yamlUrl = intent.getStringExtra("yaml_url")?.takeIf { it.isNotBlank() }
                val yamlBody = yamlUrl?.let { YamlConfigFetcher().download(it).getOrNull() }

                SettingsRepository(context.applicationContext).update { s ->
                    var next = s
                    intent.getStringExtra("ha_url")?.takeIf { it.isNotBlank() }
                        ?.let { next = next.copy(homeAssistantUrl = it) }
                    if (intent.hasExtra("screen_brightness"))
                        next = next.copy(manualBrightness = intent.getIntExtra("screen_brightness", next.manualBrightness).coerceIn(0, 255))
                    if (intent.hasExtra("screen_timeout_seconds"))
                        next = next.copy(manualTimeoutSeconds = intent.getIntExtra("screen_timeout_seconds", next.manualTimeoutSeconds).coerceAtLeast(0))
                    if (intent.hasExtra("proximity_wake"))
                        next = next.copy(manualProximityWake = intent.getBooleanExtra("proximity_wake", next.manualProximityWake))
                    if (intent.hasExtra("idle_dim_percent"))
                        next = next.copy(manualIdleDimPercent = intent.getIntExtra("idle_dim_percent", next.manualIdleDimPercent).coerceIn(0, 100))
                    if (intent.hasExtra("show_status_bar"))
                        next = next.copy(manualShowStatusBar = intent.getBooleanExtra("show_status_bar", next.manualShowStatusBar))
                    if (intent.hasExtra("report_interval_seconds"))
                        next = next.copy(manualSensorIntervalSeconds = intent.getIntExtra("report_interval_seconds", next.manualSensorIntervalSeconds).coerceAtLeast(1))
                    if (intent.hasExtra("auto_brightness"))
                        next = next.copy(manualAutoBrightness = intent.getBooleanExtra("auto_brightness", next.manualAutoBrightness))
                    if (intent.hasExtra("diag_port"))
                        next = next.copy(manualDiagPort = intent.getIntExtra("diag_port", next.manualDiagPort).coerceIn(0, 65535))
                    intent.getStringExtra("mqtt_broker")?.let { next = next.copy(manualMqttBroker = it) }
                    intent.getStringExtra("mqtt_topic")?.let { next = next.copy(manualMqttTopic = it) }
                    intent.getStringExtra("mqtt_username")?.let { next = next.copy(manualMqttUsername = it) }
                    intent.getStringExtra("mqtt_password")?.let { next = next.copy(manualMqttPassword = it) }
                    yamlUrl?.let { next = next.copy(panelYamlUrl = it) }
                    yamlBody?.let { next = next.copy(lastPanelYamlRaw = it) }
                    next
                }
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        const val ACTION = "pro.nspanel.ha2.PUSH_CONFIG"
    }
}
