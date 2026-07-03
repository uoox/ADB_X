package top.cbug.adbx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import top.cbug.adbx.store.Settings
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.ShellUtils
import top.cbug.adbx.util.WifiHelper
import top.cbug.adbx.AdbMonitorService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ADB_X_BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed received, checking settings")

        // 启动监控服务（看门狗自动恢复断连）
        try {
            AdbMonitorService.start(context)
            Log.d(TAG, "Monitor service started")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start monitor service", t)
        }

        try {
            Settings.load(context)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load settings", t)
            return
        }

        if (!Settings.bootStart && !Settings.fixedPortEnabled) {
            Log.d(TAG, "Boot start and fixed port both disabled")
            return
        }

        if (!ShellUtils.hasRoot()) {
            Log.w(TAG, "No root access, cannot proceed")
            return
        }

        // Apply fixed port on boot if enabled
        if (Settings.fixedPortEnabled) {
            Log.d(TAG, "Applying fixed port on boot: " + Settings.fixedPort)
            AdbHelper.setFixedPort(Settings.fixedPort)
        }

        // Auto-enable ADB if connected to trusted WiFi
        if (!Settings.bootStart || !Settings.autoEnable) {
            Log.d(TAG, "Boot start or auto enable disabled")
            return
        }

        val trustedSet = Settings.trustedSet()
        if (trustedSet.isEmpty()) {
            Log.d(TAG, "No trusted networks configured")
            return
        }

        // Wait for WiFi to be ready
        try { Thread.sleep(5000) } catch (_: InterruptedException) { }

        val currentSsid = try {
            WifiHelper.getCurrentSsid(context)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to get current SSID", t)
            return
        }

        if (currentSsid.isBlank()) {
            Log.d(TAG, "Not connected to any WiFi")
            return
        }

        val cleanSsid = currentSsid.trim().removeSurrounding("\"")
        if (!trustedSet.contains(cleanSsid) && !trustedSet.contains("\"" + cleanSsid + "\"")) {
            Log.d(TAG, "Current WiFi '" + cleanSsid + "' not in trusted set")
            return
        }

        Log.d(TAG, "Connected to trusted WiFi '" + cleanSsid + "', enabling wireless ADB")
        val ok = AdbHelper.enableWirelessAdb()
        Log.d(TAG, "Wireless ADB enabled: " + ok)
    }
}
