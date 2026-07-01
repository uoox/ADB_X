package top.cbug.adbx.util

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build

data class SavedWifi(val ssid: String, val bssid: String?, val security: String)

object WifiHelper {

    fun getSavedNetworks(context: Context): List<SavedWifi> {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        val result = mutableListOf<SavedWifi>()

        try {
            @Suppress("DEPRECATION")
            val configs: List<WifiConfiguration>? = wm.configuredNetworks
            if (configs != null) {
                for (cfg in configs) {
                    val ssid = cleanSsid(cfg.SSID)
                    val security = describeCapabilities(cfg)
                    result.add(SavedWifi(ssid, cfg.BSSID, security))
                }
            }
        } catch (_: SecurityException) {
            /* Android 13+ restricts getConfiguredNetworks */
        }

        if (result.isEmpty()) {
            try {
                @Suppress("DEPRECATION")
                val scanResults: List<ScanResult> = wm.scanResults
                for (sr in scanResults) {
                    val ssid = cleanSsid(sr.SSID)
                    if (ssid.isNotBlank()) {
                        val security = describeScanResultCapabilities(sr)
                        result.add(SavedWifi(ssid, sr.BSSID, security))
                    }
                }
            } catch (_: SecurityException) {
                /* no location permission */
            }
        }

        return result.distinctBy { it.ssid }
    }

    fun getCurrentSsid(context: Context): String {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ""
        return try {
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            cleanSsid(info.ssid)
        } catch (_: Throwable) { "" }
    }

    fun cleanSsid(ssid: String?): String {
        if (ssid == null) return ""
        var s = ssid.trim()
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
            s = s.substring(1, s.length - 1)
        }
        if (s == "<unknown ssid>") return ""
        return s
    }

    @Suppress("DEPRECATION")
    private fun describeCapabilities(cfg: WifiConfiguration): String {
        val cap = cfg.allowedKeyManagement
        return when {
            cap.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> "WPA/WPA2"
            cap.get(WifiConfiguration.KeyMgmt.SAE) -> "WPA3"
            cap.get(WifiConfiguration.KeyMgmt.IEEE8021X) -> "802.1X"
            cap.get(WifiConfiguration.KeyMgmt.WPA_EAP) -> "EAP"
            cap.get(WifiConfiguration.KeyMgmt.NONE) && cfg.allowedAuthAlgorithms.get(WifiConfiguration.AuthAlgorithm.OPEN) -> "Open"
            cap.get(WifiConfiguration.KeyMgmt.NONE) -> "WEP"
            else -> "Unknown"
        }
    }

    @Suppress("DEPRECATION")
    private fun describeScanResultCapabilities(sr: ScanResult): String {
        val cap = sr.capabilities ?: ""
        return when {
            cap.contains("WPA3") -> "WPA3"
            cap.contains("WPA2") || cap.contains("WPA") -> "WPA/WPA2"
            cap.contains("WEP") -> "WEP"
            cap.contains("ESS") -> "Open"
            else -> "Unknown"
        }
    }
}
