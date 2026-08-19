package pro.nspanel.ha2.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address

/**
 * Network facts for the diagnostics sheet. The panels are wall-mounted with no
 * status bar, so "what is my IP and am I actually on the network" has to be
 * answerable from the panel's own screen — it is the only way back in when
 * Wi-Fi drops and ADB is unreachable.
 *
 * Touches system services and may shell out for the SSID — call off the main thread.
 */
object NetworkInfo {

    data class State(
        val ipAddress: String? = null,
        val gateway: String? = null,
        val dns: List<String> = emptyList(),
        val ssid: String? = null,
        val rssiDbm: Int? = null,
        val linkSpeedMbps: Int? = null,
        val frequencyMhz: Int? = null,
        val wifiEnabled: Boolean = false,
        val connected: Boolean = false,
        /** Connectivity actually verified by Android (captive portals fail this). */
        val validated: Boolean = false,
        val transport: String? = null,
    ) {
        /** Coarse quality bucket; avoids the API-level split in calculateSignalLevel. */
        val signalQuality: String?
            get() = rssiDbm?.let {
                when {
                    it >= -55 -> "excellent"
                    it >= -67 -> "good"
                    it >= -75 -> "fair"
                    else -> "weak"
                }
            }
    }

    fun read(context: Context): State {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager

        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val link = network?.let { cm.getLinkProperties(it) }

        val transport = when {
            caps == null -> null
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }

        val ipv4 = link?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }
            ?.let { "${it.address.hostAddress}/${it.prefixLength}" }

        val gateway = link?.routes
            ?.firstOrNull { it.isDefaultRoute }
            ?.gateway
            ?.hostAddress

        val wifiInfo = runCatching { @Suppress("DEPRECATION") wifi?.connectionInfo }.getOrNull()

        return State(
            ipAddress = ipv4,
            gateway = gateway,
            dns = link?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList(),
            ssid = resolveSsid(wifiInfo?.ssid),
            rssiDbm = wifiInfo?.rssi?.takeIf { it != 0 && it > -127 },
            linkSpeedMbps = wifiInfo?.linkSpeed?.takeIf { it > 0 },
            frequencyMhz = wifiInfo?.frequency?.takeIf { it > 0 },
            wifiEnabled = runCatching { wifi?.isWifiEnabled }.getOrNull() ?: false,
            connected = caps != null,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false,
            transport = transport,
        )
    }

    /**
     * Android redacts the SSID to "<unknown ssid>" without location permission.
     * Rather than hold a location permission purely for a diagnostics label, fall
     * back to `cmd wifi status`, which these rooted userdebug images allow.
     */
    private fun resolveSsid(raw: String?): String? {
        val cleaned = raw?.trim()?.trim('"')?.takeIf {
            it.isNotEmpty() && !it.equals(UNKNOWN_SSID, ignoreCase = true)
        }
        if (cleaned != null) return cleaned

        val status = RootShell.run("cmd wifi status") ?: return null
        return QUOTED.find(status)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
    }

    private const val UNKNOWN_SSID = "<unknown ssid>"
    private val QUOTED = Regex("\"([^\"]+)\"")
}
