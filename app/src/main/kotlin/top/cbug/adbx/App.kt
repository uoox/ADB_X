package top.cbug.adbx

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import top.cbug.adbx.store.Settings
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.LocaleHelper
import top.cbug.adbx.util.ShellUtils
import top.cbug.adbx.util.XposedStatus
import kotlin.concurrent.thread

class App : Application() {
    companion object {
        /** App-level context available from any thread once onCreate has run. */
        lateinit var appContext: Context
            private set

        private const val BOOT_PREFS = "adb_x_boot_module"
        private const val KEY_BOOT_MODULE_VER = "installed_version_code"
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Hand the application context to XposedStatus so its LSPosed-time
        // markActive() writes can land in our SharedPreferences without
        // needing the activity context (which doesn't exist yet when
        // XposedInit.onPackageReady runs in our own process).
        XposedStatus.init(this)
        // Load Settings first so LocaleHelper sees the saved choice.
        Settings.load(base)
    }

    override fun onCreate() {
        super.onCreate()
        appContext = this
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        // Settings was already loaded in attachBaseContext; reload is a no-op.
        Settings.load(this)
        // Trusted-SSID auto-toggle is driven by WifiStateReceiver (a manifest
        // broadcast receiver) plus BootReceiver, and — for the boot path that
        // survives OnePlus' background-broadcast restrictions — the KernelSU/
        // Magisk module installed below. No long-lived service is needed.
        maybeInstallBootModule()
    }

    /**
     * Install the boot-time KernelSU/Magisk module once per app version, off
     * the main thread. Its service.sh re-applies the trusted-Wi-Fi rule
     * before the lockscreen appears — the one path that reliably fires on
     * OnePlus, where background broadcasts are throttled at boot. The install
     * is idempotent and gated by a one-shot pref (keyed to the version code)
     * so it never blocks startup or repeats needlessly; a version bump
     * re-runs it so script changes ship with the update.
     */
    private fun maybeInstallBootModule() {
        thread(name = "adb-x-boot-module") {
            try {
                if (!ShellUtils.hasRoot()) return@thread
                val prefs = getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)
                val current = currentVersionCode()
                if (prefs.getInt(KEY_BOOT_MODULE_VER, -1) == current) return@thread
                if (AdbHelper.installAsKernelSuModule()) {
                    prefs.edit().putInt(KEY_BOOT_MODULE_VER, current).apply()
                }
            } catch (_: Throwable) { }
        }
    }

    private fun currentVersionCode(): Int = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    } catch (_: Throwable) { 0 }
}
