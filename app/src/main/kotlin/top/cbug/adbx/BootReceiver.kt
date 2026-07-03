package top.cbug.adbx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import top.cbug.adbx.store.Settings

/**
 * Boot receiver — starts UI notification only.
 *
 * ADB management is handled by the system_server hook (AdbSystemHooks),
 * which loads automatically on boot with Xposed. No need to enable
 * ADB or apply settings here.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ADB_X_BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed — system_server hook handles ADB logic")
        try {
            Settings.load(context)
        } catch (_: Exception) { }

        // Start foreground service for notification only (no functional logic)
        try {
            AdbMonitorService.start(context)
        } catch (_: Exception) { }
    }
}