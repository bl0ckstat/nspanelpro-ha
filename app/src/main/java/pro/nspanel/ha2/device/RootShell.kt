package pro.nspanel.ha2.device

import android.util.Log
import java.io.File

/**
 * Runs commands as root. The panels ship userdebug builds with a setuid `su`, so
 * this works from the app's own uid without any special install step.
 *
 * Note the AOSP calling convention — `su [WHO [COMMAND...]]`. These images have no
 * `su -c`; passing it fails with "su: invalid uid/gid '-c'".
 *
 * Blocking: call from a background thread only.
 */
object RootShell {

    /** Path to a usable `su`, or null on a device without one. */
    val suPath: String? by lazy {
        SU_PATHS.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
    }

    val available: Boolean get() = suPath != null

    /** Runs [command] as root, returning its combined output, or null if it failed. */
    fun run(command: String): String? {
        val su = suPath ?: return null
        return try {
            val process = ProcessBuilder(su, "0", "sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            // Read to EOF first: waiting before draining can deadlock on a full pipe.
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.waitFor() == 0) output else null
        } catch (e: Exception) {
            Log.w(TAG, "root command failed: $command", e)
            null
        }
    }

    private val SU_PATHS = listOf("/system/xbin/su", "/system/bin/su", "/sbin/su")
    private const val TAG = "RootShell"
}
