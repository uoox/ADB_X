package top.cbug.adbx.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class XposedInit : IXposedHookLoadPackage {
    companion object {
        const val MODULE_PACKAGE = "top.cbug.adbx"
        const val TAG = "ADB_X"

        fun log(msg: String) {
            XposedBridge.log("[$TAG] $msg")
        }

        fun log(t: Throwable) {
            XposedBridge.log(t)
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> AdbSystemHooks.hook(lpparam)
            "com.android.settings" -> SettingsHooks.hook(lpparam)
        }
    }
}
