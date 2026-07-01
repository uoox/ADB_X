package top.cbug.adbx.util

import android.util.Log

object AdbHelper {

    private const val TAG = "ADB_X_AdbHelper"

    fun getCurrentPort(): String {
        val r = ShellUtils.execute("getprop service.adb.tls.port")
        if (!r.isSuccess()) r.output.trim().let { if (it.isNotEmpty()) return it }
        return ShellUtils.execute("getprop service.adb.tcp.port").output.trim()
    }

    fun getCurrentState(): Boolean {
        val r = ShellUtils.execute("getprop service.adb.tls.port")
        val port = r.output.trim()
        return port.isNotEmpty() && port != "0"
    }

    fun enableWirelessAdb(): Boolean {
        val r = ShellUtils.executeSu("setprop service.adb.tls.port 5555; setprop ctl.restart adbd")
        Log.d(TAG, "enableWirelessAdb result: ${r.exitCode} ${r.output}")
        return r.isSuccess()
    }

    fun disableWirelessAdb(): Boolean {
        val r = ShellUtils.executeSu("setprop service.adb.tls.port 0")
        Log.d(TAG, "disableWirelessAdb result: ${r.exitCode} ${r.output}")
        return r.isSuccess()
    }

    fun setFixedPort(port: Int): Boolean {
        val r = ShellUtils.executeSu("setprop service.adb.tls.port $port; setprop ctl.restart adbd")
        Log.d(TAG, "setFixedPort($port) result: ${r.exitCode} ${r.output}")
        return r.isSuccess()
    }

    fun readPairingCode(): String {
        return try {
            val file = java.io.File("/data/local/tmp/adb_x_pairing_code")
            if (file.exists()) file.readText().trim() else ""
        } catch (e: Exception) {
            val r = ShellUtils.executeSu("cat /data/local/tmp/adb_x_pairing_code")
            r.output.trim()
        }
    }

    fun clearPairingCode() {
        ShellUtils.executeSu("rm -f /data/local/tmp/adb_x_pairing_code")
    }
}
