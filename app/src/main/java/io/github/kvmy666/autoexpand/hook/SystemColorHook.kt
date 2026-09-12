package io.github.kvmy666.autoexpand.hook

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * OxygenOS / ColorOS "Custom color" fix — turns the white accents that system apps show
 * back into the colour the user actually picked. Opt-in (`system_color_fix_enabled`, OFF).
 *
 * THE BUG (OxygenOS 16, CPH2747 / OOS 16.x)
 * Settings → Wallpapers & style → Colors has two paths:
 *   • Featured / preset colours → the OEM writes `Settings.Secure.sysui_type_accent_color`
 *     to a real colour (e.g. `#ff247cff`) and every surface tints correctly.
 *   • Custom (from wallpaper)  → the framework Monet palette IS written correctly (that is
 *     why third-party apps tint fine), but `sysui_type_accent_color` is left at `#ffffffff`.
 * The "type accent" is what system surfaces tint from — Settings' ON toggles and the
 * connected-Wi-Fi icon, My Files, Calculator, and SystemUI's own accent — so they all render
 * white. Featured writes it, Custom never does: that is the whole difference.
 *
 * THE FIX
 * While enabled: if a wallpaper-derived ("Custom") palette is active and the stored
 * `sysui_type_accent_color` is missing/white, mirror the palette colour
 * (`theme_customization_overlay_packages.accent_color`) into it. Preset/Featured colours are
 * never touched — we only fire on the exact broken signature.
 *
 * Runs inside the already-scoped `com.android.systemui` process: no new LSPosed scope, no
 * `su`. Everything is wrapped — a failure is silent and stock behaviour is preserved.
 */
class SystemColorHook(private val prefs: PrefsBridge) {

    private companion object {
        const val TAG = "Snapper"
        const val PREF_KEY = "system_color_fix_enabled"
        const val KEY_TYPE_ACCENT = "sysui_type_accent_color"
        const val KEY_THEME_OVERLAY = "theme_customization_overlay_packages"
        const val KEY_COLOR_THEME_SETTING = "color_theme_setting"

        /** `color_source` values meaning "derived from the user's own wallpaper" = the Custom path. */
        val CUSTOM_SOURCES = setOf("home_wallpaper", "lock_wallpaper", "photo", "custom_image")

        /** Values that mean "the OEM never wrote a real accent" (the bug signature). */
        val BLANK_ACCENTS = setOf("#ffffffff", "#ffffff", "ffffffff", "ffffff", "#00000000")
    }

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var observer: ContentObserver? = null

    /** Called once from MainHook once prefs are readable (SystemUI Application.onCreate). */
    fun install(app: android.app.Application) {
        try {
            registerPrefReceiver(app)
            observeColorChanges(app)
            apply()
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: SystemColor install failed: $t")
        }
    }

    /** Mirror the active custom palette colour into `sysui_type_accent_color`. Never throws. */
    fun apply() {
        try {
            val cr = prefs.appContext?.contentResolver ?: return
            val current = Settings.Secure.getString(cr, KEY_TYPE_ACCENT)
            if (!prefs.isOptInEnabled(PREF_KEY)) {
                Log.d(TAG, "DIAG: SystemColor disabled (current=$current)")
                return
            }
            val desired = customAccent(cr)
            Log.d(TAG, "DIAG: SystemColor current=$current desired=$desired")
            // No wallpaper-derived palette → Featured/preset is active: never touch it.
            if (desired == null) return
            // The OEM already wrote a real colour — nothing to repair.
            if (!isBlankAccent(current)) return
            Settings.Secure.putString(cr, KEY_TYPE_ACCENT, desired)
            Log.d(TAG, "DIAG: SystemColor FIXED $KEY_TYPE_ACCENT -> $desired (was $current)")
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: SystemColor apply failed: $t")
        }
    }

    // ── Palette reading ───────────────────────────────────────────────────────────

    /**
     * The Custom-path accent as `#aarrggbb`, or null when the active colour is not a
     * wallpaper-derived custom colour. Never throws.
     */
    private fun customAccent(cr: android.content.ContentResolver): String? {
        return try {
            val raw = Settings.Secure.getString(cr, KEY_THEME_OVERLAY) ?: return null
            val json = JSONObject(raw)
            val source = json.optString("android.theme.customization.color_source", "")
            if (source !in CUSTOM_SOURCES) {
                Log.d(TAG, "DIAG: SystemColor not a custom source (color_source=$source)")
                return null
            }
            val hex = json.optString("android.theme.customization.accent_color", "")
                .ifEmpty { json.optString("android.theme.customization.system_palette", "") }
            val rgb = hex.removePrefix("#")
            // Only accept a plain 6- or 8-digit hex colour.
            if (rgb.length != 6 && rgb.length != 8) return null
            if (!rgb.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            if (isBlankAccent("#$rgb")) return null
            "#" + rgb.lowercase()
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: SystemColor palette read failed: $t")
            null
        }
    }

    private fun isBlankAccent(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        return value.trim().lowercase() in BLANK_ACCENTS
    }

    // ── Live updates ──────────────────────────────────────────────────────────────

    /** Re-apply whenever the user picks a different colour / wallpaper theme. */
    private fun observeColorChanges(app: android.app.Application) {
        try {
            val obs = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    apply()
                }
            }
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(KEY_THEME_OVERLAY), false, obs
            )
            app.contentResolver.registerContentObserver(
                Settings.System.getUriFor(KEY_COLOR_THEME_SETTING), false, obs
            )
            observer = obs
            Log.d(TAG, "DIAG: SystemColor observer registered")
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: SystemColor observer registration failed: $t")
        }
    }

    /** Live toggle from the app's settings screen (no reboot). */
    private fun registerPrefReceiver(app: android.app.Application) {
        try {
            val filter = android.content.IntentFilter("io.github.kvmy666.autoexpand.PREF_CHANGED")
            app.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: android.content.Intent) {
                    try {
                        prefs.loadFilePrefs()
                        if (intent.getStringExtra("key") == PREF_KEY) apply()
                    } catch (t: Throwable) {
                        Log.d(TAG, "DIAG: SystemColor PREF_CHANGED failed: $t")
                    }
                }
            }, filter, Context.RECEIVER_EXPORTED)
            Log.d(TAG, "DIAG: SystemColor PREF_CHANGED receiver registered")
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: SystemColor receiver registration failed: $t")
        }
    }
}
