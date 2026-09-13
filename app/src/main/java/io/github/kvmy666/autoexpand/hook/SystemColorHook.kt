package io.github.kvmy666.autoexpand.hook

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.github.kvmy666.autoexpand.CouiPalette
import org.json.JSONObject

/**
 * OxygenOS / ColorOS "Custom color" fix — the **SystemUI half**.
 *
 * The bug and its real mechanism belong to [io.github.kvmy666.autoexpand.CouiAccentFix]: on the
 * Custom (from wallpaper) path the OEM never paints the device-global **uxres colour store**, so
 * every `?attr/couiColorPrimary` consumer renders white. That store is what makes system apps
 * tint. This hook is the SystemUI-side companion: it mirrors the same palette into SystemUI's
 * *type accent*, `Settings.Secure.sysui_type_accent_color`, which the Custom path also leaves at
 * `#ffffffff` (Featured presets write a real value there). It tints QS tiles and little else — a
 * companion to the fix, not the fix. See `docs/system-color-fix.md`.
 *
 * While enabled, if a wallpaper-derived palette is active and the stored value is still the
 * blank signature, the palette colour is mirrored into it. Featured presets are never touched —
 * we only fire on the exact broken signature.
 *
 * Runs inside the already-scoped `com.android.systemui` process: no new LSPosed scope, no `su`.
 * Everything is wrapped — a failure is silent and stock behaviour is preserved.
 *
 * The default differs from the app-side publisher on purpose: that one treats "pref absent" as
 * enabled (it repairs a broken OEM path), while this hook stays quiet until the pref says
 * otherwise, so a stock install never writes a system setting it did not have to.
 */
class SystemColorHook(private val prefs: PrefsBridge) {

    private companion object {
        const val TAG = "Snapper"
        const val PREF_KEY = "system_color_fix_enabled"
        const val KEY_TYPE_ACCENT = "sysui_type_accent_color"
        const val KEY_THEME_OVERLAY = "theme_customization_overlay_packages"
        const val KEY_COLOR_THEME_SETTING = "color_theme_setting"
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
            if (!CouiPalette.isBlankAccent(current)) return
            Settings.Secure.putString(cr, KEY_TYPE_ACCENT, desired)
            Log.d(TAG, "DIAG: SystemColor FIXED $KEY_TYPE_ACCENT -> $desired (was $current)")
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: SystemColor apply failed: $t")
        }
    }

    // ── Palette reading ───────────────────────────────────────────────────────────

    /**
     * The Custom-path accent as `#rrggbb`/`#aarrggbb`, or null when the active colour is not a
     * wallpaper-derived custom colour. Rules live in [CouiPalette.customAccent], shared with the
     * app-side publisher. Never throws.
     */
    private fun customAccent(cr: android.content.ContentResolver): String? {
        return try {
            val raw = Settings.Secure.getString(cr, KEY_THEME_OVERLAY) ?: return null
            val json = JSONObject(raw)
            CouiPalette.customAccent(
                source = json.optString("android.theme.customization.color_source", ""),
                accentColor = json.optString("android.theme.customization.accent_color", ""),
                systemPalette = json.optString("android.theme.customization.system_palette", "")
            )
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: SystemColor palette read failed: $t")
            null
        }
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
