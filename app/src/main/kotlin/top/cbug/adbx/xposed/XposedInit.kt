package top.cbug.adbx.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import top.cbug.adbx.util.XposedStatus

class XposedInit : IXposedHookLoadPackage {
    companion object {
        const val MODULE_PACKAGE = "top.cbug.adbx"
        const val TAG = "ADB_X"

        /**
         * TODO: document log
         * @param String
         */
        fun log(msg: String) {
            XposedBridge.log("[$TAG] $msg")
        }

        /**
         * TODO: document log
         * @param Throwable
         */
        fun log(t: Throwable) {
            XposedBridge.log(t)
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // Flip the in-process activation flag whenever the framework injects into us.
        // This is what the UI reads to render the "Xposed active" badge.
        if (lpparam.packageName == MODULE_PACKAGE) {
            XposedStatus.markActive()
        }
        when (lpparam.packageName) {
            "android" -> AdbSystemHooks.hook(lpparam)
            "com.android.settings" -> SettingsHooks.hook(lpparam)
        }
    }
}
