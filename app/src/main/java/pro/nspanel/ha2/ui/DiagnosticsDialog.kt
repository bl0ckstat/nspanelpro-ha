package pro.nspanel.ha2.ui

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pro.nspanel.ha2.data.AppSettings
import pro.nspanel.ha2.data.PanelConfig
import pro.nspanel.ha2.device.NetworkInfo
import pro.nspanel.ha2.device.RelayController
import pro.nspanel.ha2.device.RootShell
import pro.nspanel.ha2.screen.ScreenStats

/**
 * Read-only diagnostics, plus the escape hatches out of the app.
 *
 * The escape buttons matter as much as the readouts: once this app is the default
 * launcher on a panel with no status bar, a dropped Wi-Fi connection leaves no way
 * back to Android's own settings short of USB. These buttons are that way back.
 */
@Composable
fun DiagnosticsDialog(
    stats: ScreenStats,
    panelConfig: PanelConfig,
    settings: AppSettings,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var network by remember { mutableStateOf(NetworkInfo.State()) }
    var relays by remember { mutableStateOf(RelayController.State()) }
    var rootAvailable by remember { mutableStateOf(false) }

    // Poll while open — these are the values most likely to be changing when
    // someone is standing at the panel trying to work out why it is offline.
    LaunchedEffect(Unit) {
        while (true) {
            val net = withContext(Dispatchers.IO) { NetworkInfo.read(context) }
            val relay = withContext(Dispatchers.IO) { RelayController.read() }
            val root = withContext(Dispatchers.IO) { RootShell.available }
            network = net
            relays = relay
            rootAvailable = root
            delay(3_000)
        }
    }

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()?.let { info ->
            "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        } ?: "unknown"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints {
            val compact = maxWidth < 520.dp && maxHeight < 520.dp
            val titleSize = if (compact) 15.sp else 19.sp
            val bodySize = if (compact) 12.sp else 14.sp
            val labelSize = if (compact) 11.sp else 13.sp

            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .heightIn(max = maxHeight * 0.94f)
                    .padding(4.dp),
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Diagnostics",
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = titleSize),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            modifier = Modifier.size(28.dp).clickable { onDismiss() },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // ── Escape hatches — first, because this is what a stranded panel needs ──
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Android settings",
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = labelSize),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EscapeButton("Wi-Fi", Modifier.weight(1f), bodySize) {
                            context.launchSettings(Settings.ACTION_WIFI_SETTINGS)
                        }
                        EscapeButton("Settings", Modifier.weight(1f), bodySize) {
                            context.launchSettings(Settings.ACTION_SETTINGS)
                        }
                        EscapeButton("Launcher", Modifier.weight(1f), bodySize) {
                            context.launchSettings(Settings.ACTION_HOME_SETTINGS)
                        }
                    }

                    Section("Network", labelSize)
                    val ipLabel = network.ipAddress ?: "— no address —"
                    DiagRow("IP address", ipLabel, bodySize, emphasise = network.ipAddress == null)
                    DiagRow("SSID", network.ssid ?: "unknown", bodySize)
                    DiagRow(
                        "Wi-Fi",
                        buildString {
                            append(if (network.wifiEnabled) "enabled" else "disabled")
                            network.rssiDbm?.let { append(" · $it dBm") }
                            network.signalQuality?.let { append(" ($it)") }
                        },
                        bodySize,
                        emphasise = !network.wifiEnabled,
                    )
                    network.linkSpeedMbps?.let { speed ->
                        val freq = network.frequencyMhz?.let { " · $it MHz" } ?: ""
                        DiagRow("Link", "$speed Mbps$freq", bodySize)
                    }
                    DiagRow(
                        "Connectivity",
                        when {
                            !network.connected -> "no active network"
                            network.validated -> "validated (${network.transport})"
                            else -> "connected, unvalidated (${network.transport})"
                        },
                        bodySize,
                        emphasise = !network.connected || !network.validated,
                    )
                    network.gateway?.let { DiagRow("Gateway", it, bodySize) }
                    if (network.dns.isNotEmpty()) {
                        DiagRow("DNS", network.dns.joinToString(", "), bodySize)
                    }

                    Section("Relays", labelSize)
                    if (!relays.supported) {
                        DiagRow("Driver", "st_relay not present", bodySize, emphasise = true)
                    } else {
                        DiagRow("Relay 1", relays.relay1.asRelayText(), bodySize)
                        DiagRow("Relay 2", relays.relay2.asRelayText(), bodySize)
                        relays.mode?.let { DiagRow("Mode", it, bodySize) }
                    }

                    Section("Home Assistant", labelSize)
                    DiagRow(
                        "URL",
                        settings.homeAssistantUrl.ifBlank { "— not set —" },
                        bodySize,
                        emphasise = settings.homeAssistantUrl.isBlank(),
                    )
                    if (settings.panelYamlUrl.isNotBlank()) {
                        DiagRow("YAML URL", settings.panelYamlUrl, bodySize)
                        DiagRow("YAML cached", "${settings.lastPanelYamlRaw.length} chars", bodySize)
                    }
                    DiagRow("Config source", if (settings.panelYamlUrl.isBlank()) "manual" else "yaml", bodySize)

                    Section("App", labelSize)
                    DiagRow("Version", versionName, bodySize)
                    DiagRow("App uptime", elapsedSince(Process.getStartElapsedRealtime()), bodySize)
                    val diagUrl = if (panelConfig.diagPort > 0) {
                        val host = network.ipAddress?.substringBefore('/') ?: "…"
                        "http://$host:${panelConfig.diagPort}/diag"
                    } else {
                        "disabled"
                    }
                    DiagRow("Diagnostics", diagUrl, bodySize)

                    Section("Device", labelSize)
                    DiagRow("Model", "${stats.deviceModel} · ${stats.generation} · API ${stats.sdkInt}", bodySize)
                    DiagRow("Device uptime", elapsedSince(0L), bodySize)
                    DiagRow("Root (su)", if (rootAvailable) "available" else "unavailable", bodySize)
                    DiagRow(
                        "WRITE_SETTINGS",
                        if (stats.canWriteSettings) "granted" else "not granted",
                        bodySize,
                        emphasise = !stats.canWriteSettings,
                    )
                    val rt = Runtime.getRuntime()
                    DiagRow(
                        "Memory",
                        "${(rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)} / " +
                            "${rt.maxMemory() / (1024 * 1024)} MB",
                        bodySize,
                    )

                    Section("Screen", labelSize)
                    DiagRow("State", stats.screenState, bodySize)
                    DiagRow("Brightness", "${(stats.appliedBrightness * 100).toInt()}%", bodySize)
                    DiagRow("Light sensor", "%.0f lx".format(stats.lux), bodySize)
                    DiagRow(
                        "Proximity",
                        buildString {
                            append(if (stats.proximityNear) "near" else "clear")
                            if (stats.proximityRaw >= 0f) {
                                append(" · raw ${"%.1f".format(stats.proximityRaw)}")
                                append(" / max ${"%.1f".format(stats.proximityMaxRange)}")
                            }
                            if (!stats.proximityRawMin.isNaN()) {
                                append(
                                    " · seen ${"%.0f".format(stats.proximityRawMin)}" +
                                        "–${"%.0f".format(stats.proximityRawMax)}",
                                )
                            }
                        },
                        bodySize,
                    )

                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Close", fontSize = bodySize)
                    }
                }
            }
        }
    }
}

@Composable
private fun EscapeButton(
    label: String,
    modifier: Modifier,
    fontSize: TextUnit,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = modifier) {
        Text(label, fontSize = fontSize, maxLines = 1)
    }
}

@Composable
private fun Section(title: String, fontSize: TextUnit) {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(6.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = fontSize),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun DiagRow(
    label: String,
    value: String,
    fontSize: TextUnit,
    emphasise: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            fontSize = fontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value,
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace,
            color = if (emphasise) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(0.58f),
        )
    }
}

private fun Boolean?.asRelayText(): String = when (this) {
    true -> "ON (closed)"
    false -> "OFF (open)"
    null -> "unreadable"
}

private fun elapsedSince(startElapsedRealtime: Long): String {
    val seconds = (SystemClock.elapsedRealtime() - startElapsedRealtime) / 1000
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

private fun android.content.Context.launchSettings(action: String) {
    runCatching {
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
