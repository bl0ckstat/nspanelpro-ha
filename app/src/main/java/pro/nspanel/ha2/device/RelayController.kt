package pro.nspanel.ha2.device

import java.io.File

/**
 * The panel's two mains relays, exposed by the vendor kernel driver as
 * /sys/class/st_relay/relay{1,2} — "1" is closed (on), "0" is open.
 *
 * The nodes are world-readable but root-owned, so status reads are plain file
 * reads and need no privilege; writing would need [RootShell]. Reading touches
 * the filesystem — call from a background thread.
 */
object RelayController {

    data class State(
        /** Null when the node is absent or unreadable. */
        val relay1: Boolean? = null,
        val relay2: Boolean? = null,
        /** Raw `mode` node — relay/button coupling, meaning not yet established. */
        val mode: String? = null,
        /** False on hardware without the st_relay driver. */
        val supported: Boolean = false,
    )

    fun read(): State {
        if (!runCatching { File(BASE).isDirectory }.getOrDefault(false)) return State()
        return State(
            relay1 = readRelay(1),
            relay2 = readRelay(2),
            mode = readNode("mode"),
            supported = true,
        )
    }

    private fun readRelay(index: Int): Boolean? = readNode("relay$index")?.let { it == "1" }

    private fun readNode(name: String): String? =
        runCatching { File(BASE, name).readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    private const val BASE = "/sys/class/st_relay"
}
