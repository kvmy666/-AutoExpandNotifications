package io.github.kvmy666.autoexpand

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import org.json.JSONObject

/**
 * OxygenOS / ColorOS **"Custom colour" fix** — the real mechanism, proven on CPH2747 (OOS 16).
 *
 * ## The bug
 * System apps on ColorOS/OxygenOS do **not** tint from the Material-You `android` palette.
 * They tint from the Oplus *Coui* accent channel, i.e. resources whose value is a theme
 * attribute such as `?attr/couiColorPrimary`. On the **Featured/preset** path the OEM paints
 * those attributes; on the **Custom (from wallpaper)** path it never does, so they fall back
 * to the white placeholder (`#ffffffff`) baked into the app.
 *
 * That is why the wallpaper-derived Material-You palette is correct (third-party apps tint
 * fine) while Calculator's `=` key, Settings' ON toggles, the connected-Wi-Fi icon and
 * My Files all render **white**.
 *
 * ## The fix
 * Each broken surface is a *colour resource* whose value is `?attr/couiColorPrimary` — an
 * "accent slot". A runtime resource overlay can replace such a resource with a literal ARGB,
 * which is exactly what is needed and does **not** require touching the theme (attrs live in
 * style bags, which `cmd overlay fabricate` cannot rewrite).
 *
 * Verified end-to-end: fabricating `com.oneplus.calculator:color/fold_button_equals_bg_color`
 * turns the `=` key pink, and `…settings:color/switch_outer_circle_color` turns the Settings
 * and Wi-Fi ON toggles pink.
 *
 * ## Gotchas baked into this implementation
 *  * `cmd overlay fabricate` must **not** have its stdout redirected to a file — the command
 *    ships the overlay through a file descriptor and a redirected stdout breaks the binder
 *    call ("Failed transaction"). [RootShell] captures via pipes, which is safe.
 *  * Fabricated overlays are registered under the **caller's** package, so the real name is
 *    `com.android.shell:<name>`, never `<target>:<name>`.
 *  * `OverlayManagerService.commit()` enforces root-or-shell (`SecurityException: commit
 *    failed` otherwise), so this must run from the app process, which has `su`.
 */
object CouiAccentFix {

    private const val TAG = "Snapper"

    /** Opt-out kill switch, shared with the settings screen. Absent = enabled (this is a fix). */
    const val PREF_KEY = "system_color_fix_enabled"

    private const val KEY_THEME_OVERLAY = "theme_customization_overlay_packages"
    private const val OVERLAY_PREFIX = "aeCoui_"
    private const val CALLER = "com.android.shell"
    private const val PREF_GLOBAL_KEY = "ae_prefs_json"

    /** Fabricating against a 240 MB Settings.apk is not instant. */
    private const val FABRICATE_TIMEOUT_S = 60L
    private const val QUICK_TIMEOUT_S = 10L

    /** `color_source` values meaning "derived from the user's own wallpaper" = the Custom path. */
    private val CUSTOM_SOURCES = setOf("home_wallpaper", "lock_wallpaper", "photo", "custom_image")

    /** Values that mean "the OEM never painted this accent". */
    private val BLANK = setOf("ffffffff", "ffffff", "00000000")

    /**
     * Accent slots: colour resources whose value is `?attr/couiColorPrimary` (or an equivalent
     * Coui accent attribute) in the shipped APK. All are left white on the Custom path.
     *
     * Deliberately excluded: on-accent *foreground* colours (e.g.
     * `coui_btn_check_inner_color_on_normal`, `status_icon_chip_checked_text_color`) — those
     * must stay white because they are drawn on top of the accent.
     */
    private val SLOTS = listOf(
        // Calculator — the '=' key: its background, pressed state, and the dialog accent.
        "com.oneplus.calculator:color/fold_button_equals_bg_color",
        "com.oneplus.calculator:color/event_down_fold_button_img_equals",
        "com.oneplus.calculator:color/dialog_cancel",
        // Settings / Wireless Settings — the ON-toggle circle (thumb + track accent).
        "com.android.settings:color/switch_outer_circle_color",
        "com.oplus.wirelesssettings:color/switch_outer_circle_color",
        // My Files — same switch family; absent on some builds, failure is harmless.
        "com.oneplus.filemanager:color/switch_outer_circle_color"
    )

    /**
     * Entry point. Blocking (spawns several `su` shells) — call it off the main thread.
     *
     * Self-healing: when the active colour is no longer a wallpaper-derived custom colour
     * (user picked a Featured preset) every overlay this class created is disabled, restoring
     * stock OEM behaviour.
     *
     * @return a short status string, for logging/testing.
     */
    fun apply(ctx: Context): String {
        return try {
            if (!isEnabled(ctx)) {
                disableAll()
                return "disabled"
            }
            val accent = customAccent()
            if (accent == null) {
                disableAll()
                return "no custom accent (featured preset) — overlays cleared"
            }
            val hex = accent.removePrefix("#").lowercase()
            val argb = "0x" + (if (hex.length == 6) "ff$hex" else hex)

            val names = SLOTS.indices.map { overlayName(it, hex) }
            disableStale(names.toSet())

            var ok = 0
            SLOTS.forEachIndexed { index, slot ->
                val pkg = slot.substringBefore(':')
                val name = names[index]
                val qualified = "$CALLER:$name"
                // Idempotency: drop any previous copy of this exact slot first.
                RootShell.exec("cmd overlay disable --user 0 $qualified", QUICK_TIMEOUT_S)
                val fabricate = RootShell.exec(
                    "cmd overlay fabricate --target $pkg --name $name $slot 0x1c $argb",
                    FABRICATE_TIMEOUT_S
                )
                val enable = if (fabricate.ok) {
                    RootShell.exec("cmd overlay enable --user 0 $qualified", QUICK_TIMEOUT_S)
                } else {
                    RootShell.Result(false, "skipped")
                }
                if (fabricate.ok && enable.ok) {
                    ok++
                } else {
                    Log.d(
                        TAG,
                        "DIAG: CouiAccent $slot fabricate=${fabricate.ok}(${fabricate.output}) " +
                            "enable=${enable.ok}(${enable.output})"
                    )
                }
            }
            Log.d(TAG, "DIAG: CouiAccent applied $ok/${SLOTS.size} slots for $accent")
            "applied $ok/${SLOTS.size} slots ($accent)"
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: CouiAccent failed: $t")
            "error: $t"
        }
    }

    /** Disables every overlay this class ever fabricated (used to restore stock). */
    private fun disableAll() = disableStale(emptySet())

    /**
     * Disables any of our overlays that aren't in [keep] — leftovers from a previous accent
     * colour, or all of them when the user leaves the Custom path.
     */
    private fun disableStale(keep: Set<String>) {
        try {
            val listed = RootShell.exec("cmd overlay list --user 0", QUICK_TIMEOUT_S)
            if (!listed.ok) return
            val marker = "$CALLER:$OVERLAY_PREFIX"
            listed.output.lineSequence()
                .map { it.trim() }
                .filter { it.contains(marker) }
                .map { it.substringAfter("$CALLER:").trim() }
                .filter { it.isNotEmpty() && it !in keep }
                .forEach {
                    RootShell.exec("cmd overlay disable --user 0 $CALLER:$it", QUICK_TIMEOUT_S)
                    Log.d(TAG, "DIAG: CouiAccent disabled stale overlay $it")
                }
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: CouiAccent stale sweep failed: $t")
        }
    }

    /** Overlay name is unique per (slot, colour) so a colour change fabricates a new one. */
    private fun overlayName(index: Int, hex: String) = "$OVERLAY_PREFIX${index}_$hex"

    /**
     * The active accent as `#aarrggbb`, or null when the active colour is not a
     * wallpaper-derived custom colour. Read through `su` so it works regardless of whether an
     * app process is allowed to read this secure setting directly.
     */
    private fun customAccent(): String? {
        return try {
            val res = RootShell.exec("settings get secure $KEY_THEME_OVERLAY", QUICK_TIMEOUT_S)
            if (!res.ok) return null
            val raw = res.output.trim()
            if (raw.isEmpty() || raw == "null") return null
            val json = JSONObject(raw)
            val source = json.optString("android.theme.customization.color_source", "")
            if (source !in CUSTOM_SOURCES) {
                Log.d(TAG, "DIAG: CouiAccent not a custom source (color_source=$source)")
                return null
            }
            val hex = json.optString("android.theme.customization.accent_color", "")
                .ifEmpty { json.optString("android.theme.customization.system_palette", "") }
                .removePrefix("#")
                .lowercase()
            if (hex.length != 6 && hex.length != 8) return null
            if (!hex.all { it.isDigit() || it in 'a'..'f' }) return null
            if (hex in BLANK) return null
            "#$hex"
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: CouiAccent palette read failed: $t")
            null
        }
    }

    /**
     * Kill switch, read from the same prefs channel the hooks use (`Settings.Global`
     * `ae_prefs_json`, base64 of a flat String→String JSON). An absent key counts as enabled:
     * this is a fix, not an optional behaviour, and an overlay is trivially reversible.
     */
    private fun isEnabled(ctx: Context): Boolean {
        val value = readPref(ctx, PREF_KEY) ?: return true
        return value != "0" && !value.equals("false", ignoreCase = true)
    }

    private fun readPref(ctx: Context, key: String): String? {
        return try {
            val b64 = Settings.Global.getString(ctx.contentResolver, PREF_GLOBAL_KEY)
            if (b64.isNullOrEmpty()) return null
            val text = String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8)
            PrefsJson.parse(text)[key]
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: CouiAccent pref read failed: $t")
            null
        }
    }
}
