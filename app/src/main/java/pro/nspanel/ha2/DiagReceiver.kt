package pro.nspanel.ha2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pro.nspanel.ha2.data.SettingsRepository
import pro.nspanel.ha2.data.toManualPanelConfig
import pro.nspanel.ha2.diag.DiagSnapshot
import pro.nspanel.ha2.diag.DiagState
import pro.nspanel.ha2.panel.PanelYamlParser

/**
 * Returns the diagnostics JSON through ADB, mirroring the HTTP /diag endpoint for
 * when only the adb path is available:
 *
 *   adb shell am broadcast -a pro.nspanel.ha2.DIAG -n pro.nspanel.ha2/.DiagReceiver
 *
 * The JSON appears in the broadcast result line ("data=...") and in logcat under
 * the DiagReceiver tag.
 */
class DiagReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                // If the Activity never ran in this process, DiagState is cold —
                // hydrate config/settings from persistent storage so the snapshot
                // still reports the effective configuration.
                if (DiagState.appSettings == null) {
                    val settings = SettingsRepository(context.applicationContext).settings.first()
                    DiagState.appSettings = settings
                    DiagState.panelConfig =
                        if (settings.panelYamlUrl.isBlank()) settings.toManualPanelConfig()
                        else PanelYamlParser.parse(settings.lastPanelYamlRaw)
                }
                val json = DiagSnapshot.build(context.applicationContext).toString()
                pending.setResultData(json)
                Log.i(TAG, json)
            } catch (e: Exception) {
                pending.setResultData("{\"error\":\"${e.message}\"}")
                Log.w(TAG, "diagnostics snapshot failed", e)
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        const val ACTION = "pro.nspanel.ha2.DIAG"
        private const val TAG = "DiagReceiver"
    }
}
