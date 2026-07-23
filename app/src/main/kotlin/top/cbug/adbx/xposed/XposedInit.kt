package top.cbug.adbx.xposed

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import top.cbug.adbx.util.XposedStatus

/**
 * Modern libxposed (API 102) entry point.
 *
 * The framework instantiates this class once per hooked process (see the
 * scope declared in META-INF/xposed/scope.list) and attaches the
 * [io.github.libxposed.api.XposedInterface] before any lifecycle callback
 * runs, so [log] and [hook] are usable from every override below.
 *
 * Routing:
 *  - system_server        -> [AdbSystemHooks] (all ADB management logic)
 *  - com.android.settings -> [SettingsHooks]  (pairing-code capture)
 *  - our own app process  -> flip the "module active" marker the UI reads
 */
class XposedInit : XposedModule() {

    companion object {
        const val MODULE_PACKAGE = "top.cbug.adbx"
        const val TAG = "ADB_X"
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "loaded into ${param.processName} (systemServer=${param.isSystemServer})")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, TAG, "system_server starting — installing ADB hooks")
        AdbSystemHooks.hook(this, param.classLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            "com.android.settings" -> SettingsHooks.hook(this, param.classLoader)
            MODULE_PACKAGE -> markSelfActive(param.classLoader)
        }
    }

    /**
     * Flip the in-process activation flag the UI reads to render the
     * "Xposed active" badge. Runs inside our own app process, so the
     * application context is reachable via ActivityThread and the marker
     * lands directly in our own SharedPreferences.
     */
    private fun markSelfActive(classLoader: ClassLoader) {
        try {
            val atClass = Class.forName("android.app.ActivityThread", false, classLoader)
            val currentAT = atClass.getMethod("currentActivityThread").invoke(null)
            val appCtx = atClass.getMethod("getApplication").invoke(currentAT) as? Context
            if (appCtx != null) XposedStatus.init(appCtx)
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "markSelfActive: context lookup failed", t)
        }
        XposedStatus.markActive()
        log(Log.INFO, TAG, "module active in own process")
    }
}
