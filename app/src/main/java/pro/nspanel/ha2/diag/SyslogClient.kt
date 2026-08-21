package pro.nspanel.ha2.diag

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Fire-and-forget syslog over UDP (RFC 3164 framing, local0 facility), so a
 * fleet's worth of screen-state transitions and wake decisions can be watched
 * from one place instead of reconstructed from screenshots the morning after.
 *
 * Deliberately an object with volatile config rather than a wired dependency:
 * the interesting events happen deep in ScreenManager and MqttManager, and
 * threading a logger through every constructor for an optional feature buys
 * nothing. Blank host means disabled, which is the default; failures are
 * swallowed after one local log line — telemetry must never become the fault.
 */
object SyslogClient {

    @Volatile private var host: String = ""
    @Volatile private var port: Int = 514
    @Volatile private var tag: String = "nspanel"
    @Volatile private var warned = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // RFC 3164 timestamps carry no zone, so the receiver assumes its own —
    // and the receiver here runs UTC while the panels run local. Stamping
    // local time indexed every event eight hours in the future, where no
    // "last 24 hours" search would ever look. The wire speaks UTC.
    private val stamp = SimpleDateFormat("MMM d HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun configure(newHost: String, newPort: Int, panelIp: String?) {
        val changed = newHost.trim() != host || newPort != port
        host = newHost.trim()
        port = newPort
        tag = "nspanel-" + (panelIp?.substringBefore('/') ?: "unknown")
        warned = false
        if (changed && host.isNotEmpty()) info("syslog: reporting to $host:$port")
    }

    fun info(message: String) = send(6, message)
    fun warn(message: String) = send(4, message)

    private fun send(severity: Int, message: String) {
        val h = host
        if (h.isEmpty()) return
        val pri = 16 * 8 + severity // local0
        val line = "<$pri>${stamp.format(Date())} $tag nspanelha: $message"
        scope.launch {
            try {
                DatagramSocket().use { socket ->
                    val bytes = line.toByteArray(Charsets.UTF_8)
                    socket.send(
                        DatagramPacket(bytes, bytes.size, InetAddress.getByName(h), port),
                    )
                }
            } catch (e: Exception) {
                if (!warned) {
                    warned = true
                    Log.w("SyslogClient", "send failed (${e.message}) — suppressing further warnings")
                }
            }
        }
    }
}
