package top.cbug.adbx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import top.cbug.adbx.store.Settings

/**
 * Boot receiver — wakes the app process and starts the trusted-WiFi
 * foreground service. Android 14+ refuses
 * [Context.startForegroundService] from a background broadcast receiver
 * with `ForegroundServiceStartNotAllowedException`, so the receiver
 * cannot directly elevate the process. The workaround used here:
 *
 *   1. launch the MainActivity with FLAG_ACTIVITY_NEW_TASK from the
 *      receiver — that briefly takes the process to the foreground
 *      long enough for the activity's onCreate → TrustedWifiService.start()
 *      to be considered a foreground start.
 *   2. the activity itself owns the service-start path so we keep
 *      the receiver logic minimal.
 *
 * If you replace this with a WorkManager task or a [JobScheduler]
 * job, both of those count as the foreground-process start already
 * when [Settings.bootStart] is on.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ADB_X_BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        try {
            Settings.load(context)
        } catch (_: Exception) { }

        if (!Settings.bootStart) {
            Log.d(TAG, "Boot completed — bootStart disabled, skipping")
            return
        }

        Log.d(TAG, "Boot completed — launching MainActivity to arm TrustedWifiService")
        val activity = Intent(context, top.cbug.adbx.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(activity)
        } catch (t: Throwable) {
            Log.w(TAG, "BootReceiver failed to launch MainActivity", t)
        }
    }
}