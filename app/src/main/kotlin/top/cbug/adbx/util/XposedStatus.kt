package top.cbug.adbx.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Detects whether an Xposed framework (LSPosed, EdXposed, legacy Xposed) has loaded
 * this module. Three-state result:
 *
 *   ACTIVE   - framework injected into our process (definitive proof via /proc/self/maps).
 *   INACTIVE - framework installed but our hook never ran here (module not in scope, or
 *              scope not configured yet).
 *   UNKNOWN  - no framework evidence found at all.
 *
 * Detection priority:
 *   1. /proc/self/maps  — definitive if we find XposedBridge/library mappings.
 *   2. LSPosed config dir + module row — confirms LSPosed Zygisk is installed even
 *      without the manager APK present (common on KernelSU-only setups).
 *   3. Known manager packages — legacy fallback.
 */
object XposedStatus {

    private const val TAG = "ADB_X_XposedStatus"

    enum class State { ACTIVE, INACTIVE, UNKNOWN }

    data class Info(
        val state: State,
        val frameworkPackages: List<String>,
        val frameworkHint: String
    )

    /** Set by [top.cbug.adbx.xposed.XposedInit] when our hook fires in our own process. */
    @Volatile
    private var loadedIntoSelf: Boolean = false

    /**
     * TODO: document markActive
     */
    fun markActive() { loadedIntoSelf = true }
    /**
     * TODO: document reset
     */
    fun reset() { loadedIntoSelf = false }

    /**
     * TODO: document probe
     * @param Context
     */
    fun probe(context: Context): Info {
        // 1) Definitive: did the framework actually inject into us?
        if (loadedIntoSelf || hasInjectedIntoSelf()) {
            return Info(State.ACTIVE, emptyList(), "Hook loaded into this process")
        }

        // 2) Evidence the framework exists on this device
        val frameworks = mutableListOf<String>()
        val hint = StringBuilder()

        if (isLSPosedZygiskPresent()) {
            frameworks += "LSPosed (Zygisk)"
            hint.append("LSPosed Zygisk installed")
        }
        val mgrPkgs = detectManagerPackages()
        if (mgrPkgs.isNotEmpty()) {
            frameworks += mgrPkgs
            if (hint.isNotEmpty()) hint.append(" + ")
            hint.append(mgrPkgs.joinToString(", "))
        }

        return if (frameworks.isEmpty()) {
            Info(State.UNKNOWN, emptyList(), "No Xposed framework detected")
        } else {
            Info(
                State.INACTIVE,
                frameworks,
                "Framework installed ($hint) but hook not loaded into this process"
            )
        }
    }

    /**
     * Read /proc/self/maps looking for XposedBridge or related native libraries. This
     * is the most reliable signal: if a framework injected us, the bridge library is
     * mapped into our address space.
     */
    private fun hasInjectedIntoSelf(): Boolean {
        return try {
            val maps = File("/proc/self/maps")
            if (!maps.exists() || !maps.canRead()) return false
            maps.useLines { lines ->
                lines.any { line ->
                    val lower = line.lowercase()
                    lower.contains("xposed") ||
                    lower.contains("lsposed") ||
                    lower.contains("edxp") ||
                    lower.contains("riru") // Riru is the legacy loader
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hasInjectedIntoSelf failed: ${t.message}")
            false
        }
    }

    /**
     * On Zygisk-based setups the manager APK may not be installed but the LSPosed
     * Zygisk module is. Detect this by looking for:
     *   - /data/adb/lspd/config/modules_config.db (LSPosed state DB)
     *   - /data/adb/modules/zygisk_lsposed (Zygisk module install path)
     *   - /data/adb/modules/riru_lsposed (Riru-based, older setups)
     */
    private fun isLSPosedZygiskPresent(): Boolean {
        val probes = listOf(
            "/data/adb/lspd/config/modules_config.db",
            "/data/adb/modules/zygisk_lsposed",
            "/data/adb/modules/riru_lsposed",
            "/data/adb/zygisksu/lsposed"
        )
        return probes.any { File(it).exists() }
    }

    /** Known manager package names — fallback only. */
    private fun detectManagerPackages(): List<String> {
        val candidates = listOf(
            "org.lsposed.manager",
            "de.robv.android.xposed.installer",
            "org.meowcat.edxposed.manager",
            "org.lsposed.lspatch"
        )
        val su = ShellUtils.executeSu("pm list packages | cut -d: -f2", 2000)
        if (su.isSuccess() && su.output.isNotBlank()) {
            val installed = su.output.lineSequence().map { it.trim() }.toSet()
            return candidates.filter { it in installed }
        }
        return emptyList()
    }
}