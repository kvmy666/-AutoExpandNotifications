package io.github.kvmy666.autoexpand.hook

import android.content.Context
import android.os.FileObserver
import android.util.Log
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedHelpers
import io.github.kvmy666.autoexpand.PrefsJson
import java.io.File

/**
 * Shared preference/IPC reader for every hook collaborator. Owns the captured app context
 * and a short-lived snapshot of the prefs.
 *
 * Prefs arrive primarily via Settings.Global key `ae_prefs_json` (base64 of a flat
 * String→String JSON — see [PrefsJson]), which is readable by system_server and SystemUI
 * even when /data/local/tmp is SELinux-blocked under newer LSPosed/Android. A legacy
 * /data/local/tmp file and XSharedPreferences remain as fallbacks (the latter survives
 * app-process death on aggressive OEMs).
 */
class PrefsBridge {

    /**
     * Captured from the SystemUI process (or, for the global-search hook, from the search
     * field's context). Stays null in system_server, which reads via [systemCtx] instead.
     */
    @Volatile var appContext: Context? = null

    // Context used to read the Settings.Global IPC channel. SystemUI supplies appContext;
    // system_server (no appContext) falls back to the reflective system context. Both can
    // read Settings.Global even when /data/local/tmp is SELinux-blocked.
    private val ipcContext: Context?
        get() = appContext ?: systemCtx
    private val systemCtx: Context? by lazy {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = XposedHelpers.callStaticMethod(atClass, "currentActivityThread")
            XposedHelpers.callMethod(at, "getSystemContext") as? Context
        } catch (t: Throwable) {
            Log.d("Snapper", "DIAG: systemCtx fetch failed: $t"); null
        }
    }

    // XSharedPreferences — reads prefs XML directly; survives app-process death (Xiaomi SmartPower)
    private val xprefs = XSharedPreferences("io.github.kvmy666.autoexpand", "prefs")

    private var lastCacheTime = 0L
    private val CACHE_INTERVAL_MS = 2000L

    private val PREFS_FILE     = "/data/local/tmp/tweaks_prefs.json"
    private val HEARTBEAT_FILE = "/data/local/tmp/tweaks_heartbeat"
    @Volatile private var filePrefCache: Map<String, String>? = null
    @Volatile private var fileObserver: FileObserver? = null

    fun getStringPref(key: String, default: String): String {
        reloadIfStale()
        filePrefCache?.get(key)?.let { return it }
        return try { xprefs.getString(key, default) ?: default } catch (_: Throwable) { default }
    }

    fun reloadIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastCacheTime < CACHE_INTERVAL_MS) return
        lastCacheTime = now
        try { xprefs.reload() } catch (_: Throwable) {}
        loadFilePrefs()
    }

    fun loadFilePrefs() {
        // Primary: Settings.Global (readable by system_server + SystemUI even when
        // /data/local/tmp is SELinux-blocked under newer LSPosed/Android).
        try {
            val cr = ipcContext?.contentResolver
            if (cr != null) {
                val b64 = android.provider.Settings.Global.getString(cr, "ae_prefs_json")
                if (!b64.isNullOrEmpty()) {
                    val text = String(
                        android.util.Base64.decode(b64, android.util.Base64.NO_WRAP), Charsets.UTF_8
                    )
                    filePrefCache = PrefsJson.parse(text)
                    return
                }
            }
        } catch (t: Throwable) {
            Log.d("Snapper", "DIAG: Settings.Global prefs load failed: $t")
        }
        // Fallback: legacy /data/local/tmp file.
        try {
            filePrefCache = PrefsJson.parse(File(PREFS_FILE).readText())
        } catch (e: Throwable) {
            Log.d("Snapper", "DIAG: file prefs load failed: $e")
        }
    }

    fun startFileObserver() {
        try {
            val fileName = "tweaks_prefs.json"
            val observer = object : FileObserver(File("/data/local/tmp"), CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == fileName) loadFilePrefs()
                }
            }
            observer.startWatching()
            fileObserver = observer
            Log.d("Snapper", "DIAG: FileObserver started")
        } catch (e: Throwable) {
            Log.d("Snapper", "DIAG: FileObserver start failed: $e")
        }
    }

    fun startHeartbeatThread() {
        val t = Thread {
            val file = File(HEARTBEAT_FILE)
            while (true) {
                // Primary: monotonic heartbeat in Settings.Global. elapsedRealtime is
                // boot-relative, so the activity-side check is immune to wall-clock/NTP
                // skew, and Settings.Global is readable/writable even when /data/local/tmp
                // is SELinux-blocked (EACCES) on newer LSPosed/Android.
                try {
                    val cr = ipcContext?.contentResolver
                    if (cr != null) android.provider.Settings.Global.putString(
                        cr, "ae_heartbeat", android.os.SystemClock.elapsedRealtime().toString()
                    )
                } catch (e: Throwable) { Log.d("Snapper", "DIAG: heartbeat global write failed: $e") }
                // Fallback: legacy /data/local/tmp file (best effort).
                try { file.writeText(System.currentTimeMillis().toString()) }
                catch (e: Throwable) { Log.d("Snapper", "DIAG: heartbeat file write failed: $e") }
                Thread.sleep(60_000)
            }
        }
        t.isDaemon = true
        t.name = "tweaks-heartbeat"
        t.start()
    }

    fun isFeatureEnabled(key: String): Boolean {
        reloadIfStale()
        val fileVal = filePrefCache?.get(key)
        fileVal?.let { return it == "1" }
        return try { xprefs.getBoolean(key, true) } catch (_: Throwable) { true }
    }

    // Kill-switches default OFF (opt-in). Using isFeatureEnabled here would default ON,
    // disabling every HUD hook for any user whose prefs file hasn't been written with the key yet.
    fun isKillSwitchActive(key: String): Boolean {
        reloadIfStale()
        val fileVal = filePrefCache?.get(key)
        fileVal?.let { return it == "1" }
        return try { xprefs.getBoolean(key, false) } catch (_: Throwable) { false }
    }

    // Opt-in features (default OFF when the pref hasn't been written yet). Same
    // default-false semantics as isKillSwitchActive — aliased for readability.
    fun isOptInEnabled(key: String): Boolean = isKillSwitchActive(key)

    fun getExcludedApps(): Set<String> {
        reloadIfStale()
        filePrefCache?.get("excluded_apps")?.let { v ->
            return if (v.isEmpty()) emptySet() else v.split("\n").toSet()
        }
        return try { xprefs.getStringSet("excluded_apps", emptySet()) ?: emptySet() } catch (_: Throwable) { emptySet() }
    }

    fun getIntPref(key: String, default: Int): Int {
        reloadIfStale()
        filePrefCache?.get(key)?.toIntOrNull()?.let { return it }
        return try { xprefs.getString(key, null)?.toIntOrNull() ?: default } catch (_: Throwable) { default }
    }
}
