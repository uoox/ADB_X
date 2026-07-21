package top.cbug.adbx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import top.cbug.adbx.store.Settings as AppSettings
import top.cbug.adbx.util.TrustedWifiWatcher

/**
 * Sticky foreground service that owns the app-side
 * [TrustedWifiWatcher] so the auto-toggle survives:
 *
 *   - the user swiping the app away from recents
 *   - the app being backgrounded (Android 14+ stops NetworkCallbacks
 *     when the registering app is not visible)
 *   - reboots, as long as [BootReceiver] re-launches this service
 *     on ACTION_BOOT_COMPLETED
 *
 * We run as a foreground service with a low-priority "automation
 * running" notification. Foreground services are exempt from the
 * Android 14 background-restriction rules so the registered
 * NetworkCallback keeps firing.
 *
 * The service self-stops when both autoEnable and autoDisable are
 * off in [Settings] — no point keeping a sticky notification alive
 * if there is nothing for the watcher to do.
 */
class TrustedWifiService : Service() {

    companion object {
        private const val TAG = "ADB_X_TwService"
        const val CHANNEL_ID = "adb_x_trusted_wifi"
        const val NOTIFICATION_ID = 1001

        /**
         * Idempotent start helper — safe to call from BootReceiver or
         * any UI code without checking first. If the service is
         * already running this is a no-op.
         */
        fun start(context: Context) {
            try {
                AppSettings.load(context)
            } catch (_: Throwable) { }
            // Bail early if automation is fully off; otherwise we'd
            // be showing a notification that does nothing.
            if (!AppSettings.autoEnable && !AppSettings.autoDisable) {
                Log.d(TAG, "start: both auto-toggle rules off, nothing to do")
                return
            }
            val intent = Intent(context, TrustedWifiService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrustedWifiService::class.java))
        }
    }

    private var watcher: TrustedWifiWatcher? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        startInForeground()
        // Use the process-wide singleton so the watcher is the same
        // instance whether the service, MainActivity, or any other
        // component creates it. This is what lets us carry
        // lastTriggerSsid / lastTriggerMs across Activity recreation.
        watcher = TrustedWifiWatcher.get(this).also {
            if (!it.isRunning()) it.start()
            it.refreshCurrentSsid()
        }
        Log.d(TAG, "onCreate: TrustedWifiWatcher started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY — system may restart us after low-memory kill.
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.stop()
        watcher = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.trusted_wifi_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.trusted_wifi_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableLights(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun startInForeground() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle(getString(R.string.trusted_wifi_notif_title))
            .setContentText(getString(R.string.trusted_wifi_notif_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}