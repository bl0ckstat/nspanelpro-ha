package pro.nspanel.ha2.diag

import pro.nspanel.ha2.data.AppSettings
import pro.nspanel.ha2.data.PanelConfig
import pro.nspanel.ha2.screen.ScreenStats

/**
 * Process-wide holder for the latest live values so both diagnostics transports
 * (HTTP server and DIAG broadcast) can build a snapshot, even when the broadcast
 * fires while the Activity is not running (values are then the last known ones,
 * with [activityAlive] = false).
 */
object DiagState {
    @Volatile var stats: ScreenStats = ScreenStats()
    @Volatile var panelConfig: PanelConfig? = null
    @Volatile var appSettings: AppSettings? = null
    @Volatile var activityAlive: Boolean = false
}
