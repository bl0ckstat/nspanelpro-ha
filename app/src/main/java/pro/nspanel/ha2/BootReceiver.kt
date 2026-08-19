package pro.nspanel.ha2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import pro.nspanel.ha2.device.DeviceProfile

/**
 * Launches the app at boot. On Android 10+ background activity starts are blocked,
 * so this only works there when the app holds the SYSTEM_ALERT_WINDOW appop
 * (granted by the deploy script). The primary boot path on Android 11 (Gen2) is
 * being the default HOME launcher, which the system starts itself.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val profile = DeviceProfile.detect()
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            Log.i(TAG, "Boot launch attempted (sdk=${profile.sdkInt}, viaReceiver=${profile.bootLaunchViaReceiver})")
        } catch (e: Exception) {
            Log.w(TAG, "Boot launch failed — relying on default-HOME launcher path", e)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
