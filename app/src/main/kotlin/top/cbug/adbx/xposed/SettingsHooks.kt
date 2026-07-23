package top.cbug.adbx.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.io.File

/**
 * Hooks inside com.android.settings — capture the wireless-debugging
 * pairing code as it flows through the developer-options pairing UI.
 *
 * Migrated to libxposed API 102: methods are hooked via
 * [XposedInterface.hook] and the captured 6–8 digit code is read from the
 * interceptor [chain] arguments.
 */
object SettingsHooks {

    private const val TAG = "ADB_X_SettingsHooks"

    private lateinit var module: XposedInterface

    private fun log(msg: String) = module.log(Log.INFO, TAG, msg)

    fun hook(module: XposedInterface, classLoader: ClassLoader) {
        this.module = module
        log("loading into com.android.settings")
        hookPairingDialog(classLoader)
    }

    private fun hookPairingDialog(classLoader: ClassLoader) {
        val candidates = listOf(
            "com.android.settings.development.AdbWirelessDebuggingPreferenceController",
            "com.android.settings.development.AdbWirelessDebuggingFragment",
            "com.android.settings.development.AdbPairingDialogFragment"
        )
        for (className in candidates) {
            val clazz = try {
                Class.forName(className, false, classLoader)
            } catch (_: Throwable) {
                continue // class not present on this OEM / version
            }
            for (method in clazz.declaredMethods) {
                if (method.parameterCount != 1 || method.parameterTypes[0] != String::class.java) continue
                try {
                    module.hook(method).intercept { chain ->
                        val arg = chain.args.getOrNull(0) as? String
                        if (arg != null) {
                            log("[${method.name}] captured: $arg")
                            if (arg.matches(Regex("\\d{6,8}"))) writePairingCode(arg)
                        }
                        chain.proceed()
                    }
                    log("hooked ${method.name} in $className")
                } catch (_: Throwable) { }
            }
        }
    }

    private fun writePairingCode(code: String) {
        try {
            val dir = File("/data/local/tmp")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "adb_x_pairing_code")
            f.writeText(code)
            f.setReadable(true, false)
            log("pairing code saved: $code")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "writePairingCode failed", t)
        }
    }
}
