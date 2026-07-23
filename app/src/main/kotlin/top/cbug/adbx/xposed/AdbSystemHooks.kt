package top.cbug.adbx.xposed

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * system_server hooks — all ADB management logic lives here.
 *
 * App (UI only): writes config to /data/local/tmp/adb_x_config.txt
 * Hook: reads config on WiFi events + retry when the file appears late.
 *
 * Migrated to the modern libxposed API 102: hooks are installed through
 * [XposedInterface.hook] with interceptor-chain hookers, and every legacy
 * `XposedHelpers` call has been replaced with plain reflection.
 */
object AdbSystemHooks {

    private const val TAG = "ADB_X_SystemHooks"
    private const val CONFIG_PATH = "/data/local/tmp/adb_x_config.txt"
    private val registered = AtomicBoolean(false)

    /** The framework interface for the system_server process. */
    private lateinit var module: XposedInterface

    /** system_server classloader — used to resolve hidden framework classes. */
    private var systemClassLoader: ClassLoader? = null

    /** SSID we already enabled ADB for — avoid re-enabling on every event. */
    @Volatile private var lastEnabledSsid: String = ""

    /** Retry: when WiFi is up but no config yet, poll a few times. */
    private var retryCount = AtomicInteger(0)
    private val maxRetries = 8
    private val retryDelayMs = 10000L

    private fun log(msg: String) = module.log(Log.INFO, TAG, msg)

    /**
     * Entry point invoked from [XposedInit.onSystemServerStarting]. Wires up
     * the pairing detectors first (they only need class loading, which is
     * always available), then defers the WiFi/connectivity work until those
     * system services are bound.
     */
    fun hook(module: XposedInterface, classLoader: ClassLoader) {
        this.module = module
        this.systemClassLoader = classLoader
        if (!registered.compareAndSet(false, true)) return
        log("Loading system_server hooks")

        // Best-effort hooks first — these don't need system services and
        // should always run regardless of whether connectivity/wifi come
        // up in time. They only need class loading, which is stable.
        hookPairingDialog(classLoader)
        hookPairingFuzzy(classLoader)

        try {
            val context = systemContext(classLoader)
                ?: throw IllegalStateException("system context not ready")

            // SAFE cast — on Android 11 (and some early-boot ROMs even on
            // 13+) ConnectivityManager is not bound to the system context
            // yet when the module is loaded. Retry on the main looper until
            // both services come online.
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val handler = Handler(Looper.getMainLooper())

            if (cm == null || wm == null) {
                log("System services not ready yet (cm=${cm != null}, wm=${wm != null}); deferring init")
                scheduleDeferredInit(context, handler, attempt = 1)
                return
            }

            installCallbacks(context, cm, wm, handler)
        } catch (t: Throwable) {
            log("Init failed: ${t.message}")
            // DO NOT clear `registered` — the module is instantiated once per
            // process, so flipping the flag would let a retry double-register
            // NetworkCallbacks. Instead, defer with a fresh context.
            scheduleDeferredInitRetry(attempt = 1)
        }
    }

    // ---------------------------------------------------------------------
    // Reflection helpers (replacing legacy XposedHelpers)
    // ---------------------------------------------------------------------

    private fun loadClassOrNull(cl: ClassLoader?, name: String): Class<*>? = try {
        Class.forName(name, false, cl ?: systemClassLoader)
    } catch (_: Throwable) {
        null
    }

    /** ActivityThread.currentActivityThread().getSystemContext(). */
    private fun systemContext(cl: ClassLoader?): Context? = try {
        val at = Class.forName("android.app.ActivityThread", false, cl ?: systemClassLoader)
        val current = at.getMethod("currentActivityThread").invoke(null)
        at.getMethod("getSystemContext").invoke(current) as? Context
    } catch (_: Throwable) {
        null
    }

    private fun callNoArg(obj: Any, name: String): Any? = try {
        obj.javaClass.getMethod(name).invoke(obj)
    } catch (_: Throwable) {
        null
    }

    // ---------------------------------------------------------------------

    /**
     * Re-attempt to wire up system_server callbacks after the framework
     * has had a chance to bind connectivity/wifi services. Retries with
     * exponential backoff up to 60s, then gives up gracefully (the
     * user can still pair manually via the app).
     */
    private fun scheduleDeferredInit(context: Context, handler: Handler, attempt: Int) {
        val delayMs = (2000L * attempt).coerceAtMost(60_000L)
        handler.postDelayed({
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (cm == null || wm == null) {
                    if (attempt >= 30) {
                        log("System services never came up after $attempt retries — WiFi auto-enable will not work")
                        return@postDelayed
                    }
                    log("Defer retry #$attempt in ${delayMs}ms (cm=${cm != null}, wm=${wm != null})")
                    scheduleDeferredInit(context, handler, attempt + 1)
                    return@postDelayed
                }
                log("Deferred init succeeded on attempt $attempt")
                installCallbacks(context, cm, wm, handler)
            } catch (t: Throwable) {
                log("Deferred init failed: ${t.message}")
                if (attempt < 30) scheduleDeferredInit(context, handler, attempt + 1)
            }
        }, delayMs)
    }

    /**
     * Deferred retry when the very first try threw (e.g. ActivityThread
     * getSystemContext returned null because zygote hadn't bootstrapped
     * it yet). Re-acquires the context from scratch each time.
     */
    private fun scheduleDeferredInitRetry(attempt: Int) {
        val delayMs = (2000L * attempt).coerceAtMost(60_000L)
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            try {
                val context = systemContext(systemClassLoader)
                if (context == null) {
                    if (attempt < 30) scheduleDeferredInitRetry(attempt + 1)
                    return@postDelayed
                }
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (cm == null || wm == null) {
                    if (attempt >= 30) {
                        log("System services never came up after $attempt retries — WiFi auto-enable will not work")
                        return@postDelayed
                    }
                    scheduleDeferredInit(context, handler, attempt + 1)
                    return@postDelayed
                }
                log("Deferred retry succeeded on attempt $attempt")
                installCallbacks(context, cm, wm, handler)
            } catch (t: Throwable) {
                log("Deferred retry failed: ${t.message}")
                if (attempt < 30) scheduleDeferredInitRetry(attempt + 1)
            }
        }, delayMs)
    }

    /**
     * Actually wire up the WiFi NetworkCallback, the saved-network dump,
     * and the pair-request watcher. Safe to call multiple times — the
     * NetworkCallback is a fresh instance each call, so we never
     * double-register (Android would just dedupe by NetworkRequest).
     */
    private fun installCallbacks(
        context: Context,
        cm: ConnectivityManager,
        wm: WifiManager,
        handler: Handler
    ) {
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handleWifiEvent(cm, wm, network, context, handler)
                }
                override fun onLost(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
                    lastEnabledSsid = ""
                    retryCount.set(0)
                    val config = readConfig()
                    if (config.autoDisable) {
                        log("WiFi lost — disabling ADB")
                        disableAdb(context)
                    }
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                        handleWifiEvent(cm, wm, network, context, handler)
                }
            })

            log("WiFi callback registered — all ADB logic in system_server")

            // Dump saved WiFi networks to a world-readable file so the app
            // can read them. Android 11+ privacy hides the full list from
            // third-party apps, but system_server has full visibility.
            try {
                val getNetworksMethod = WifiManager::class.java.getDeclaredMethod("getConfiguredNetworks")
                val networks = getNetworksMethod.invoke(wm) as? List<*> ?: emptyList<Any>()
                val sb = StringBuilder()
                for (net in networks) {
                    if (net == null) continue
                    val ssid = (callNoArg(net, "getSSID") ?: "").toString()
                    val bssid = (callNoArg(net, "getBSSID") ?: "").toString()
                    sb.append(ssid.replace("\"", "")).append('|')
                      .append(bssid.replace("\"", "")).append('|')
                      .append("Secured").append('\n')
                }
                val tmp = File("/data/local/tmp/adb_x_wifi_list")
                tmp.writeText(sb.toString())
                tmp.setReadable(true, false)
                log("dumped ${networks.size} WiFi networks")
            } catch (t: Throwable) {
                log("WiFi dump failed: ${t.message}")
            }

            // Pair-request watcher: app writes /data/local/tmp/adb_x_request_pair
            // when the user taps "Start pairing"; we trigger startAdbPairing()
            // in system_server and capture the port via the detectors above.
            try {
                startPairRequestWatcher(context, handler)
            } catch (t: Throwable) {
                log("pair watcher start failed: ${t.message}")
            }
        } catch (t: Throwable) {
            log("installCallbacks failed: ${t.message}")
        }
    }

    /**
     * Poll /data/local/tmp/adb_x_request_pair every second. When the file
     * contains "1", trigger an AdbDebuggingManager startAdbPairing()
     * via reflection. The file is deleted after consuming so a single tap
     * does not fire twice.
     */
    private fun startPairRequestWatcher(context: Context, handler: Handler) {
        val requestFile = File("/data/local/tmp/adb_x_request_pair")
        val poll = object : Runnable {
            override fun run() {
                try {
                    if (requestFile.exists() && requestFile.length() > 0) {
                        val raw = requestFile.readText().trim()
                        log("pair-request detected: $raw")
                        try { requestFile.delete() } catch (_: Throwable) { }
                        if (raw == "1") triggerAdbPairing(context)
                    }
                } catch (t: Throwable) {
                    log("pair-request poll error: ${t.message}")
                }
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(poll)
    }

    /**
     * Reflectively invoke AdbDebuggingManager.startAdbPairing(). The method
     * exists across AOSP and OnePlus reshuffles of class names; we try a few
     * candidates. Whatever the class, the pairing port + code go through the
     * existing pairing detectors.
     */
    private fun triggerAdbPairing(context: Context) {
        try {
            val managerCls = loadClassOrNull(systemClassLoader, "com.android.server.adb.AdbDebuggingManager")
            if (managerCls == null) {
                log("triggerAdbPairing: AdbDebuggingManager not found")
                return
            }
            val inst = try {
                managerCls.getMethod("getInstance").invoke(null)
            } catch (_: Throwable) {
                try { managerCls.getConstructor(Context::class.java).newInstance(context) }
                catch (_: Throwable) { null }
            }
            if (inst == null) {
                log("triggerAdbPairing: could not obtain manager instance")
                return
            }
            val methodNames = arrayOf(
                "startAdbPairing",
                "startPairing",
                "startWirelessAdbPairing",
                "createAdbPairingService",
            )
            var invoked = false
            for (m in methodNames) {
                try {
                    inst.javaClass.getMethod(m).invoke(inst)
                    log("startAdbPairing invoked via $m")
                    invoked = true
                    break
                } catch (_: Throwable) { }
            }
            if (!invoked) log("startAdbPairing: no matching method")
        } catch (t: Throwable) {
            log("triggerAdbPairing failed: ${t.message}")
        }
    }

    /**
     * Hook the known ADB pairing dialog/service classes and read a named
     * port field off each fresh instance. Every declared constructor is
     * hooked (rather than guessing a single signature) so OEM builds that
     * added constructor parameters still surface the port.
     */
    private fun hookPairingDialog(classLoader: ClassLoader) {
        // (className, fieldNameHoldingPort) candidates across Android versions.
        val candidates = listOf(
            "com.android.systemui.adb.AdbPairingDialog" to "mPort",
            "com.android.systemui.adb.AdbPairingDialog" to "mServicePort",
            "com.android.systemui.adb.AdbPairingDialog" to "port",
            "com.android.settingslib.wifi.AccessPoint" to "mPairingPort",
            "com.android.settings.wifi.WifiPairingDialog" to "mPort",
        )
        for ((className, fieldName) in candidates) {
            val cls = loadClassOrNull(classLoader, className) ?: continue
            val field = try {
                cls.getDeclaredField(fieldName).apply { isAccessible = true }
            } catch (_: Throwable) {
                continue
            }
            var hooked = false
            for (ctor in cls.declaredConstructors) {
                try {
                    module.hook(ctor).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val port = field.get(chain.thisObject)?.toString()
                            if (!port.isNullOrBlank() && port != "0") writePairingPort(port)
                        } catch (_: Throwable) { }
                        result
                    }
                    hooked = true
                } catch (_: Throwable) { }
            }
            if (hooked) log("Hooked $className.$fieldName")
        }
    }

    /**
     * Beyond the fixed candidate list, walk every class the system_server
     * has loaded whose name hints at ADB pairing and, on each fresh
     * instance, scan every field for a port-shaped int (4–5 digits, in the
     * ephemeral range, not the legacy main ADB port 5555). This gives the
     * path a chance regardless of which inner class an OEM renamed.
     */
    private fun hookPairingFuzzy(classLoader: ClassLoader) {
        val candidates = listOf(
            // Stock AOSP
            "com.android.server.adb.AdbPairingDialog",
            "com.android.server.adb.AdbPairingService",
            "com.android.server.adb.WirelessDebuggingHandler",
            // OnePlus / OPlus internal names
            "com.oplus.adbd.AdbPairingDialog",
            "com.oplus.adbd.AdbPairingService",
            "com.oplus.adbd.WirelessDebuggingService",
            "com.android.adbd.AdbPairingDialog",
            "com.android.adbd.AdbPairingService",
            // Settings app side (also hosts the dialog on some OEMs)
            "com.android.systemui.adb.AdbPairingDialog\$Receiver",
        )
        for (className in candidates) {
            val cls = loadClassOrNull(classLoader, className) ?: continue
            for (ctor in cls.declaredConstructors) {
                try {
                    module.hook(ctor).intercept { chain ->
                        val result = chain.proceed()
                        try { scanForPort(chain.thisObject) } catch (_: Throwable) { }
                        result
                    }
                } catch (_: Throwable) { }
            }
            log("fuzzy-hooked $cls")
        }
    }

    private fun scanForPort(obj: Any?) {
        if (obj == null) return
        for (field in obj.javaClass.declaredFields) {
            try {
                field.isAccessible = true
                val raw = field.get(obj)?.toString() ?: continue
                if (raw.length in 4..5 && raw.all { it.isDigit() } &&
                    raw.toInt() in 1024..65535 && raw.toInt() != 5555) {
                    writePairingPort(raw)
                    return
                }
            } catch (_: Throwable) { }
        }
    }

    /**
     * Persist the captured pairing port to a world-readable marker the app
     * polls. system_server may be blocked by SELinux from writing
     * /data/local/tmp directly, so fall back to an elevated shell write.
     */
    private fun writePairingPort(port: String) {
        val path = "/data/local/tmp/adb_x_pairing_port"
        try {
            val f = File(path)
            f.writeText(port)
            f.setReadable(true, false)
            log("pairing port written: $port")
            return
        } catch (_: Throwable) { /* fall through to su */ }
        try {
            Runtime.getRuntime().exec(
                arrayOf("su", "-c", "sh -c 'echo $port > $path && chmod 666 $path'")
            )
            log("pairing port written via su: $port")
        } catch (t: Throwable) {
            log("writePairingPort failed: ${t.message}")
        }
    }

    private fun handleWifiEvent(
        cm: ConnectivityManager, wm: WifiManager,
        network: Network, context: Context, handler: Handler
    ) {
        val caps = cm.getNetworkCapabilities(network) ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return

        val ssid = currentSsid(wm)
        if (ssid.isBlank()) return

        // Already enabled ADB for this SSID?
        if (ssid == lastEnabledSsid) return

        val config = readConfig()
        if (!config.autoEnable) return
        if (ssid !in config.trustedSsids) {
            // Trusted list exists but doesn't include this SSID → don't retry.
            if (config.trustedSsids.isNotEmpty()) return
            // No trusted list yet → app might not have written config.
            retryConfig(handler, cm, wm, network, context)
            return
        }

        log("Trusted WiFi '$ssid' — enabling ADB")
        lastEnabledSsid = ssid
        retryCount.set(0)
        enableAdb(context, config)
    }

    @Suppress("DEPRECATION")
    private fun currentSsid(wm: WifiManager): String = try {
        wm.connectionInfo?.ssid?.trim()?.removeSurrounding("\"") ?: ""
    } catch (_: Exception) {
        ""
    }

    /** Retry a few times — catches the case where the config file appears after WiFi connects. */
    private fun retryConfig(
        handler: Handler, cm: ConnectivityManager,
        wm: WifiManager, network: Network, context: Context
    ) {
        val attempt = retryCount.incrementAndGet()
        if (attempt > maxRetries) return

        log("Scheduling config retry #$attempt in ${retryDelayMs}ms")
        handler.postDelayed({
            val ssid = currentSsid(wm)
            if (ssid.isBlank()) return@postDelayed
            if (ssid == lastEnabledSsid) return@postDelayed

            val config = readConfig()
            if (config.autoEnable && ssid in config.trustedSsids) {
                log("Config appeared on retry #$attempt — enabling ADB")
                lastEnabledSsid = ssid
                retryCount.set(0)
                enableAdb(context, config)
            } else {
                retryConfig(handler, cm, wm, network, context)
            }
        }, retryDelayMs)
    }

    private fun enableAdb(context: Context, config: AdbConfig) {
        try {
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
            log("adb_wifi_enabled=1 OK")
        } catch (t: Throwable) {
            log("Failed to enable ADB: ${t.message}")
        }

        if (config.fixedPortEnabled && config.fixedPort in 1024..65535) {
            try {
                val spClass = Class.forName("android.os.SystemProperties", false, systemClassLoader)
                val setMethod = spClass.getDeclaredMethod("set", String::class.java, String::class.java)
                setMethod.invoke(null, "service.adb.tls.port", config.fixedPort.toString())
                setMethod.invoke(null, "service.adb.tcp.port", config.fixedPort.toString())
                log("Fixed port set to ${config.fixedPort}")
            } catch (t: Throwable) {
                log("Fixed port failed (non-fatal): ${t.message}")
            }
        }
    }

    private fun disableAdb(context: Context) {
        try {
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
        } catch (t: Throwable) {
            log("Failed to disable ADB: ${t.message}")
        }
    }

    private data class AdbConfig(
        val autoEnable: Boolean = true,
        val autoDisable: Boolean = false,
        val bootStart: Boolean = true,
        val locale: String = "system",
        val fixedPortEnabled: Boolean = false,
        val fixedPort: Int = 5555,
        val trustedSsids: Set<String> = emptySet()
    )

    private fun readConfig(): AdbConfig {
        val file = File(CONFIG_PATH)
        if (!file.exists() || !file.canRead()) return AdbConfig()
        return try {
            val map = mutableMapOf<String, String>()
            for (line in file.readLines()) {
                val idx = line.trim().indexOf('=')
                if (idx > 0) map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
            AdbConfig(
                autoEnable = map.getOrDefault("auto_enable", "true").toBooleanStrictOrNull() ?: true,
                autoDisable = map.getOrDefault("auto_disable", "false").toBooleanStrictOrNull() ?: false,
                bootStart = map.getOrDefault("boot_start", "true").toBooleanStrictOrNull() ?: true,
                locale = map.getOrDefault("locale", "system"),
                fixedPortEnabled = map.getOrDefault("fixed_port_enabled", "false").toBooleanStrictOrNull() ?: false,
                fixedPort = map.getOrDefault("fixed_port", "5555").toIntOrNull() ?: 5555,
                trustedSsids = map.getOrDefault("trusted_ssids", "")
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            )
        } catch (t: Throwable) {
            log("readConfig: ${t.message}")
            AdbConfig()
        }
    }
}
