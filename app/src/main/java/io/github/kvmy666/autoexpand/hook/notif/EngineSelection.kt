package io.github.kvmy666.autoexpand.hook.notif

import android.os.Build
import android.util.Log
import io.github.kvmy666.autoexpand.hook.PrefsBridge

/**
 * The single decision point for "which expand engine drives this SystemUI process".
 *
 * Two independent call sites used to read the `notif_engine_v2` boolean:
 * [io.github.kvmy666.autoexpand.MainHook] decides whether to *install* [NotifEngineV2], and
 * [io.github.kvmy666.autoexpand.hook.NotificationExpander.useV2] decides whether Engine 1's
 * heads-up hooks stand down. They read it at different points in the SystemUI startup sequence,
 * so a change that landed between them produced a half-switched process. Both now resolve
 * through here, and the answer is cached once per process — the same lifetime the old
 * `useV2()` cache had, without the split.
 *
 * `notif_engine_mode` is the user-facing pref (`auto` / `v1` / `v2`). `notif_engine_v2` stays
 * as the legacy boolean: installs that never wrote a mode keep their old behaviour instead of
 * being silently moved onto Auto.
 */
object EngineSelection {

    const val MODE_KEY = "notif_engine_mode"
    const val LEGACY_V2_KEY = "notif_engine_v2"

    private const val TAG = "AENotif"

    enum class Mode(val pref: String, val label: String) {
        AUTO("auto", "Auto"),
        V1("v1", "Engine 1"),
        V2("v2", "Engine 2"),
    }

    /** Cached per SystemUI process — deliberately never re-read while hooks are live. */
    @Volatile
    private var resolvedV2: Boolean? = null

    /** Pure mapping used by both the hook side and the settings UI. */
    fun resolveMode(rawMode: String?, legacyV2: Boolean?): Mode {
        val raw = rawMode?.trim()?.lowercase().orEmpty()
        if (raw.isNotEmpty()) {
            Mode.entries.firstOrNull { it.pref == raw }?.let { return it }
            Log.w(TAG, "unknown $MODE_KEY=$raw — falling back to auto")
        }
        // A legacy value only counts when the new key has never been written.
        if (legacyV2 != null) return if (legacyV2) Mode.V2 else Mode.V1
        return Mode.AUTO
    }

    fun mode(prefs: PrefsBridge): Mode {
        val legacy = if (prefs.contains(LEGACY_V2_KEY)) prefs.isOptInEnabled(LEGACY_V2_KEY) else null
        return resolveMode(prefs.getStringPref(MODE_KEY, ""), legacy)
    }

    /**
     * True when Engine 2 should run in this process.
     *
     * Auto is conservative on purpose: Engine 2 is verified on OxygenOS 16 only, so an
     * unrecognised ROM stays on Engine 1 until a resolver proves its capabilities. The
     * capability gate inside [NotifEngineV2.install] can still decline v2 at runtime.
     */
    fun isV2(prefs: PrefsBridge): Boolean {
        resolvedV2?.let { return it }
        val selected = mode(prefs)
        val effective = when (selected) {
            Mode.V2 -> true
            Mode.V1 -> false
            Mode.AUTO -> autoWantsV2()
        }
        resolvedV2 = effective
        Log.i(
            TAG,
            "engine selected mode=${selected.pref} effective=${if (effective) "v2" else "v1"} " +
                "sdk=${Build.VERSION.SDK_INT} brand=${Build.BRAND} " +
                "manuf=${Build.MANUFACTURER} oplusRom=${prop("ro.build.version.oplusrom")}"
        )
        return effective
    }

    /**
     * Auto rule: Android 16 on the OxygenOS/ColorOS/Realme family, which is the only ROM
     * family where Engine 2's HUD path has been measured. Everything else keeps Engine 1.
     */
    private fun autoWantsV2(): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val brand = (Build.BRAND + " " + Build.MANUFACTURER).lowercase()
        if (OPLUS_BRANDS.any { brand.contains(it) }) return true
        return OPLUS_PROPS.any { prop(it).isNotEmpty() }
    }

    /** Reads a system property without touching hidden-API-restricted fields directly. */
    private fun prop(key: String): String = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String).orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private val OPLUS_BRANDS = listOf("oneplus", "oplus", "oppo", "realme")

    private val OPLUS_PROPS = listOf(
        "ro.build.version.oplusrom",
        "ro.build.version.oplusrom.display",
        "ro.oplus.version",
        "ro.build.version.opporom",
    )
}
