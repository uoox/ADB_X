package top.cbug.adbx.store

import android.content.Context
import android.content.SharedPreferences

object Settings {
    private const val PREFS_NAME = "adb_x_settings"

    private const val KEY_FIXED_PORT_ENABLED = "fixed_port_enabled"
    private const val KEY_FIXED_PORT = "fixed_port"
    private const val KEY_TRUSTED_SSIDS = "trusted_ssids"
    private const val KEY_AUTO_ENABLE = "auto_enable"
    private const val KEY_AUTO_DISABLE = "auto_disable"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile var fixedPortEnabled = false
    @Volatile var fixedPort = 5555
    @Volatile var autoEnable = true
    @Volatile var autoDisable = false

    private var trustedSsids: MutableSet<String> = mutableSetOf()

    fun load(context: Context) {
        val p = prefs(context)
        fixedPortEnabled = p.getBoolean(KEY_FIXED_PORT_ENABLED, false)
        fixedPort = p.getInt(KEY_FIXED_PORT, 5555)
        autoEnable = p.getBoolean(KEY_AUTO_ENABLE, true)
        autoDisable = p.getBoolean(KEY_AUTO_DISABLE, false)
        trustedSsids = p.getStringSet(KEY_TRUSTED_SSIDS, emptySet())!!.toMutableSet()
    }

    fun isTrusted(ssid: String): Boolean {
        val clean = sanitizeSsid(ssid)
        if (clean.isBlank()) return false
        return trustedSsids.contains(clean)
    }

    fun addTrusted(ssid: String) {
        val clean = sanitizeSsid(ssid)
        if (clean.isNotBlank()) trustedSsids.add(clean)
    }

    fun removeTrusted(ssid: String) {
        trustedSsids.remove(sanitizeSsid(ssid))
    }

    fun trustedSet(): Set<String> = trustedSsids.toSet()

    fun save(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_FIXED_PORT_ENABLED, fixedPortEnabled)
            .putInt(KEY_FIXED_PORT, fixedPort)
            .putBoolean(KEY_AUTO_ENABLE, autoEnable)
            .putBoolean(KEY_AUTO_DISABLE, autoDisable)
            .putStringSet(KEY_TRUSTED_SSIDS, trustedSsids)
            .apply()
    }

    private fun sanitizeSsid(ssid: String): String {
        var s = ssid.trim()
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) s = s.substring(1, s.length - 1)
        return s
    }
}
