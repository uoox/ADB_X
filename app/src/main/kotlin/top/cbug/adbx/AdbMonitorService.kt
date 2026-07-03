package top.cbug.adbx

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.cbug.adbx.store.Settings
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.ShellUtils
import top.cbug.adbx.util.WifiHelper

class AdbMonitorService : Service() {

    companion object {
        private const val TAG = "ADB_X_MonitorSvc"
        private const val CHANNEL_ID = "adb_x_monitor"
        private const val NOTIF_ID = 1001
        private const val WATCHDOG_INTERVAL_MS = 15000L

        fun start(ctx: Context) {
            val intent = Intent(ctx, AdbMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AdbMonitorService::class.java))
        }
    }

    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchdogJob: Job? = null
    private var lastEnabledSsid: String = ""
    private var lastAdbEnabled: Boolean = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkAvailable(network)
        }

        override fun onLost(network: Network) {
            handleNetworkLost(network)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            handleNetworkCapabilitiesChanged(network, caps)
        }
    }

    private fun handleNetworkAvailable(network: Network) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val caps = cm.getNetworkCapabilities(network) ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        val wm = getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val ssid = getCurrentSSID(wm)
        Log.d(TAG, "onAvailable WiFi SSID: " + ssid)
        if (ssid.isNotBlank() && ssid != lastEnabledSsid) {
            lastEnabledSsid = ssid
            onWifiConnected(ssid)
        }
    }

    private fun handleNetworkLost(network: Network) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val caps = cm.getNetworkCapabilities(network) ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        Log.d(TAG, "onLost WiFi")
        if (lastEnabledSsid.isNotBlank()) {
            lastEnabledSsid = ""
            onWifiDisconnected()
        }
    }

    private fun handleNetworkCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        val wm = getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val ssid = getCurrentSSID(wm)
        if (ssid.isNotBlank() && ssid != lastEnabledSsid) {
            Log.d(TAG, "onCapabilitiesChanged WiFi SSID: " + ssid)
            lastEnabledSsid = ssid
            onWifiConnected(ssid)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentSSID(wm: WifiManager): String {
        return WifiHelper.cleanSsid(wm.connectionInfo?.ssid) ?: ""
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        registerNetworkCallback()
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        startForeground(NOTIF_ID, buildNotification("Monitoring...", "tap to open"))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        watchdogJob?.cancel()
        bgScope.cancel()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun onWifiConnected(ssid: String) {
        Log.d(TAG, "WiFi connected: " + ssid)
        Settings.load(this)
        if (!Settings.autoEnable) {
            Log.d(TAG, "Auto-enable disabled, skip")
            return
        }
        val trusted = Settings.trustedSet()
        if (trusted.isEmpty()) {
            Log.d(TAG, "No trusted networks")
            return
        }
        val clean = ssid.trim().removeSurrounding("\"")
        val isTrusted = trusted.contains(clean) || trusted.contains('"' + clean + '"')
        if (!isTrusted) {
            Log.d(TAG, "SSID not trusted: " + clean)
            return
        }
        Log.d(TAG, "Trusted WiFi matched, enabling wireless ADB")
        bgScope.launch {
            try {
                AdbHelper.enableWirelessAdb()
                if (Settings.fixedPortEnabled) {
                    delay(1500)
                    AdbHelper.setFixedPort(Settings.fixedPort)
                }
                updateNotification("ADB Enabled", "on " + clean)
            } catch (t: Throwable) {
                Log.e(TAG, "enable failed", t)
            }
        }
    }

    private fun onWifiDisconnected() {
        Log.d(TAG, "WiFi disconnected")
        Settings.load(this)
        if (!Settings.autoDisable) return
        bgScope.launch {
            AdbHelper.disableWirelessAdb()
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = bgScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                try {
                    runWatchdog()
                } catch (t: Throwable) {
                    Log.w(TAG, "Watchdog error: " + t.message)
                }
            }
        }
    }

    private suspend fun runWatchdog() {
        if (!ShellUtils.hasRoot()) return
        val enabled = AdbHelper.getCurrentState(this)
        val port = AdbHelper.getCurrentPortNonRoot()
        val currentSsid = try { WifiHelper.getCurrentSsid(this) } catch (_: Exception) { "" }
        Settings.load(this)
        val trustedSet = Settings.trustedSet()
        val cleanSsid = currentSsid.trim().removeSurrounding("\"")
        val onTrustedWifi = Settings.autoEnable && currentSsid.isNotBlank() &&
            (trustedSet.contains(cleanSsid) || trustedSet.contains('"' + cleanSsid + '"'))
        Log.d(TAG, "Watchdog: enabled=" + enabled + " port=" + port + " ssid=" + currentSsid + " trusted=" + onTrustedWifi)
        if (enabled && port.isNotEmpty()) {
            lastAdbEnabled = true
            // IMPORTANT: DO NOT try to fix port mismatch here.  On Android 14+
            // with Rust adbd, changing port + restarting adbd kills the wireless
            // connection we're talking over.  Just log and move on.
            if (Settings.fixedPortEnabled && port != Settings.fixedPort.toString()) {
                Log.w(TAG, "Port mismatch (got $port, want ${Settings.fixedPort}) — skipping fix because restarting adbd over wireless disconnects us")
            }
            return
        }
        if (!enabled && onTrustedWifi) {
            Log.d(TAG, "ADB disabled but on trusted WiFi, re-enabling")
            AdbHelper.enableWirelessAdb()
            lastAdbEnabled = true
            if (Settings.fixedPortEnabled) {
                delay(1500)
                AdbHelper.setFixedPort(Settings.fixedPort)
            }
        } else if (!enabled && lastAdbEnabled && !onTrustedWifi) {
            if (!Settings.autoDisable) {
                Log.d(TAG, "ADB went down unexpectedly, restoring")
                AdbHelper.enableWirelessAdb()
            }
            lastAdbEnabled = enabled
        } else {
            lastAdbEnabled = enabled
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "ADB Monitor", NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "ADB_X monitor service"
            channel.setShowBadge(false)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(title, text))
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        cm.registerNetworkCallback(request, networkCallback)
        Log.d(TAG, "NetworkCallback registered")
    }
}
