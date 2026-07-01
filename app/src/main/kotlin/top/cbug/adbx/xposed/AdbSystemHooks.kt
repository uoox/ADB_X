package top.cbug.adbx.xposed

import android.app.ActivityThread
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemProperties
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

object AdbSystemHooks {

    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val VAl_SERVICE_ADB_TLS_PORT = "service.adb.tls.port"
    private const val PROP_VARIANT_1 = "service.adb.tls.port"
    private const val PROP_VARIANT_2 = "service.adb.tcp.port"
    private const val PAIRING_CODE_DIR = "/data/local/tmp"
    private const val TRUSTED_SSIDS = "trusted_ssids"
    private const val FIXED_PORT_ENABLED = "fixed_port_enabled"
    private const val FIXED_PORT = "fixed_port"

    private var context: Context? = null
    private var wifiReceiverRegistered = false
    private var lastEnabledSsid: String = ""

    fun hook(lpparam: LoadPackageParam) {
        XposedInit.log("AdbSystemHooks: loading into android (system_server)")
        hookSystemContext(lpparam)
        hookAdbDebuggingManager(lpparam)
        hookSystemPropertiesPort(lpparam)
    }

    private fun hookSystemContext(lpparam: LoadPackageParam) {
        try {
            val atClass = XposedHelpers.findClass(
                "android.app.ActivityThread", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                atClass, "systemMain",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val at = param.thisObject
                        context = XposedHelpers.callMethod(at, "getSystemContext") as? Context
                        context?.let { registerWifiReceiver(it) }
                    }
                }
            )
            XposedInit.log("AdbSystemHooks: hooked ActivityThread.systemMain")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun hookAdbDebuggingManager(lpparam: LoadPackageParam) {
        val className = if (Build.VERSION.SDK_INT >= 31)
            "com.android.server.adb.AdbDebuggingManager"
        else null

        if (className == null) {
            XposedInit.log("AdbSystemHooks: AdbDebuggingManager hook skipped (SDK=${Build.VERSION.SDK_INT})")
            return
        }

        try {
            val mgrClass = XposedHelpers.findClass(className, lpparam.classLoader)

            XposedBridge.hookAllConstructors(mgrClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    XposedInit.log("AdbSystemHooks: AdbDebuggingManager constructed")
                }
            })

            dumpAllMethods(mgrClass).forEach { m ->
                val name = m.name
                val params = m.parameterTypes
                try {
                    val messageTypes = setOf("onPairingCode", "handlePairing", "showPairing")
                    if (messageTypes.any { name.contains(it, true) }) {
                        bridgeHook(mgrClass, name, params)
                    }
                } catch (_: Throwable) { /* ignore individual method failures */ }
            }

            for (declaredClass in mgrClass.declaredClasses) {
                dumpAllMethods(declaredClass).forEach { m ->
                    val name = m.name
                    val params = m.parameterTypes
                    try {
                        if (name.contains("pair", true) || name.contains("code", true)) {
                            bridgeHook(declaredClass, name, params, innerName = declaredClass.simpleName)
                        }
                    } catch (_: Throwable) { }
                }
            }

            XposedInit.log("AdbSystemHooks: hooked AdbDebuggingManager (cluster scan)")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun bridgeHook(
        clazz: Class<*>, name: String, params: Array<Class<*>>,
        innerName: String = ""
    ) {
        val label = if (innerName.isNotBlank()) "$innerName.$name" else name
        XposedHelpers.findAndHookMethod(clazz, name, *params, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args?.joinToString(", ") { it?.toString() ?: "null" }
                XposedInit.log("AdbDebuggingManager[$label] before: params=[$args]")
                val code = extractPairingCode(args)
                if (code.isNotEmpty()) writePairingCode(code)
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                XposedInit.log("AdbDebuggingManager[$label] after")
            }
        })
        XposedInit.log("AdbSystemHooks: hooked $label")
    }

    private fun dumpAllMethods(clazz: Class<*>): List<java.lang.reflect.Method> {
        return try { clazz.declaredMethods.toList() } catch (_: Throwable) { emptyList() }
    }

    private fun extractPairingCode(args: String?): String {
        if (args == null) return ""
        val groups = Regex("(\\d{6,8})").findAll(args).map { it.groupValues[1] }.toList()
        return groups.firstOrNull { it.length in 6..8 } ?: ""
    }

    private fun writePairingCode(code: String) {
        try {
            val dir = java.io.File(PAIRING_CODE_DIR)
            if (!dir.exists()) dir.mkdirs()
            java.io.File(dir, "adb_x_pairing_code").writeText(code)
            XposedInit.log("AdbSystemHooks: pairing code written: $code")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun hookSystemPropertiesPort(lpparam: LoadPackageParam) {
        try {
            val spClass = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                spClass, "set", String::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        if (key == PROP_VARIANT_1 || key == PROP_VARIANT_2) {
                            val prefs = readModulePrefs() ?: return
                            val fixedEnabled = prefs.getBoolean(FIXED_PORT_ENABLED, false)
                            if (!fixedEnabled) return
                            val fixedPort = prefs.getInt(FIXED_PORT, 5555)
                            val original = param.args[1]
                            XposedInit.log("AdbSystemHooks: overriding adb port $key: $original -> $fixedPort")
                            param.args[1] = fixedPort.toString()
                        }
                    }
                }
            )
            XposedInit.log("AdbSystemHooks: hooked SystemProperties.set for port override")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun registerWifiReceiver(ctx: Context) {
        if (wifiReceiverRegistered) return
        wifiReceiverRegistered = true
        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == WifiManager.NETWORK_STATE_CHANGED_ACTION) {
                    val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                    val wifiInfo = intent.getParcelableExtra<WifiInfo>(WifiManager.EXTRA_WIFI_INFO)
                    if (info != null && info.isConnected && wifiInfo != null) {
                        handleWifiConnected(ctx, wifiInfo)
                    } else if (info != null && NetworkInfo.State.DISCONNECTED == info.state) {
                        handleWifiDisconnected(ctx)
                    }
                }
            }
        }
        try {
            ctx.registerReceiver(receiver, filter, null, Handler(Looper.getMainLooper()))
            XposedInit.log("AdbSystemHooks: WifiReceiver registered")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun handleWifiConnected(ctx: Context, wifiInfo: WifiInfo) {
        val ssid = formatSsid(wifiInfo.ssid)
        XposedInit.log("AdbSystemHooks: WiFi connected: $ssid")
        if (ssid == lastEnabledSsid) return
        val prefs = readModulePrefs() ?: return
        val autoEnable = prefs.getBoolean("auto_enable", true)
        if (!autoEnable) return
        val trusted = prefs.getStringSet(TRUSTED_SSIDS, emptySet()) ?: emptySet()
        val isTrusted = isSsidTrusted(ssid, trusted)
        XposedInit.log("AdbSystemHooks: $ssid trusted=$isTrusted")
        if (isTrusted) {
            enableWirelessAdb(ctx)
            lastEnabledSsid = ssid
        }
    }

    private fun handleWifiDisconnected(ctx: Context) {
        if (lastEnabledSsid.isNotEmpty()) {
            val prefs = readModulePrefs() ?: return
            if (prefs.getBoolean("auto_disable", false)) {
                disableWirelessAdb(ctx)
            }
            lastEnabledSsid = ""
        }
    }

    private fun enableWirelessAdb(ctx: Context) {
        try {
            Settings.Global.putInt(ctx.contentResolver, ADB_WIFI_ENABLED, 1)
            XposedInit.log("AdbSystemHooks: wireless ADB enabled for trusted WiFi")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun disableWirelessAdb(ctx: Context) {
        try {
            Settings.Global.putInt(ctx.contentResolver, ADB_WIFI_ENABLED, 0)
            XposedInit.log("AdbSystemHooks: wireless ADB disabled (left trusted WiFi)")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun isSsidTrusted(ssid: String, trusted: Set<String>): Boolean {
        if (ssid.isBlank()) return false
        if (trusted.contains(ssid)) return true
        val quoted = "\"$ssid\""
        return trusted.contains(quoted)
    }

    private fun formatSsid(ssid: String?): String {
        if (ssid == null) return ""
        var s = ssid.trim()
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
            s = s.substring(1, s.length - 1)
        }
        return s
    }

    private fun readModulePrefs(): android.content.SharedPreferences? {
        return try {
            val xsp = de.robv.android.xposed.XSharedPreferences("top.cbug.adbx", "adb_x_settings")
            xsp.makeWorldReadableable()
            xsp.all
            xsp
        } catch (t: Throwable) {
            XposedBridge.log(t)
            null
        }
    }
}
