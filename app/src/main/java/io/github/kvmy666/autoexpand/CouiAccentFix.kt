package io.github.kvmy666.autoexpand

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * OxygenOS / ColorOS **"Custom colour" fix** — the real mechanism, proven on CPH2747 (OOS 16).
 *
 * ## The bug
 * System apps on ColorOS/OxygenOS do **not** tint from the Material-You `android` palette.
 * They tint from the Oplus *Coui* channel — resources whose value is a theme attribute such
 * as `?attr/couiColorPrimary`. On the **Featured/preset** path the OEM paints that channel;
 * on the **Custom (from wallpaper)** path it never does, so every such surface falls back to
 * the white placeholder baked into the APK. Calculator's `=` key, Settings' ON toggles, the
 * connected-Wi-Fi icon and My Files all render **white** while third-party apps tint fine.
 *
 * ## The channel (what the previous implementation got wrong)
 * The channel is not an RRO: this device has **no** OEM overlays for those apps at all, and
 * a fabricated overlay on the palette resource behind `couiColorPrimary` is ignored
 * (refuted on device). The channel is a plain, device-global **uxres colour store**:
 *
 * ```
 * /data/oplus/uxres/uxcolor/ux_custom_color.xml        day   — written by com.oplus.uxdesign
 * /data/oplus/uxres/uxcolor/ux_custom_color_night.xml  night
 * ```
 *
 * `<color name="couiSingleFirstNormal">#FFFFFFFF</color>` is the white signature: the Custom
 * path leaves the whole `Single` family white. Rewriting that family with the user's accent
 * makes **every** app that consumes `couiColorPrimary` correct — no per-slot whack-a-mole,
 * nothing to enumerate, no overlay, and it covers apps we never heard of.
 *
 * ## The fix
 * On a Custom-path palette: derive the 6-slot `Single` family (see [CouiPalette]) and write
 * both stores as root, keeping the stock shape and permissions. Then restart the app
 * processes that are already running, because a **running** process keeps the palette it
 * read at start (verified on device: a live UI never re-tints; force-stop + relaunch always
 * does).
 *
 * ## Safety
 *  * The stock stubs are backed up once to [STOCK_DIR] and restored verbatim whenever the
 *    user is on a Featured preset or turns the fix off — the device can never get stuck with
 *    our values.
 *  * Overlays fabricated by the pre-uxres implementation (`com.android.shell:aeCoui_*`) are
 *    disabled on sight, so upgrading does not leave stale ones behind.
 *  * Everything is wrapped: a failure is logged and stock behaviour is preserved.
 */
object CouiAccentFix {

    private const val TAG = "Snapper"

    /** Opt-out kill switch, shared with the settings screen. Absent = enabled (this is a fix). */
    const val PREF_KEY = "system_color_fix_enabled"

    private const val ACTION_PREF_CHANGED = "io.github.kvmy666.autoexpand.PREF_CHANGED"

    private const val KEY_THEME_OVERLAY = "theme_customization_overlay_packages"
    private const val KEY_COLOR_THEME_SETTING = "color_theme_setting"
    private const val PREF_GLOBAL_KEY = "ae_prefs_json"
    private const val PREF_LAST_ACCENT = "coui_accent_applied"

    /** The OEM's device-global Coui colour store. */
    private const val STORE_DIR = "/data/oplus/uxres/uxcolor"
    private const val DAY_FILE = "ux_custom_color.xml"
    private const val NIGHT_FILE = "ux_custom_color_night.xml"

    /** Where the stock stubs are kept so the Featured path can be restored byte-for-byte. */
    private const val STOCK_DIR = "/data/local/tmp/ae_uxcolor_stock"
    private const val STOCK_MARK = "$STOCK_DIR/.ae_stock_v1"

    /** Overlays created by the pre-uxres implementation; disabled on sight. */
    private const val LEGACY_OVERLAY_MARK = "com.android.shell:aeCoui_"

    private const val ROOT_TIMEOUT_S = 25L
    private const val QUICK_TIMEOUT_S = 10L

    /** `color_source` values meaning "derived from the user's own wallpaper" = the Custom path. */
    private val CUSTOM_SOURCES = setOf("home_wallpaper", "lock_wallpaper", "photo", "custom_image")

    /** Values that mean "the OEM never painted this accent". */
    private val BLANK = setOf("ffffffff", "ffffff", "00000000")

    /**
     * Apps restarted after a palette change. Only the ones **actually running** are touched
     * (a `pidof` guard), so on a cold device this is a no-op and the change simply applies
     * when each app next starts. SystemUI is restarted rather than force-stopped, so init
     * brings it straight back (same pattern as [RootShell.restartSystemUi]).
     */
    private val RESTART_PACKAGES = listOf(
        "com.android.settings",
        "com.oplus.wirelesssettings",
        "com.oplus.phonemanager",
        "com.oneplus.filemanager",
        "com.oneplus.calculator",
        "com.oneplus.deskclock",
        "com.oneplus.gallery",
        "com.oneplus.note",
        "com.oneplus.soundrecorder",
        "com.oneplus.backuprestore",
        "com.oneplus.brickmode",
        "com.coloros.compass2",
        "com.coloros.video",
        "com.coloros.translate",
        "com.oplus.camera",
        "com.oplus.games",
        "com.oplus.aimemory",
        "com.heytap.browser",
        "net.oneplus.weather",
        "com.oplus.consumerIRApp",
        "com.android.launcher",
        "com.android.phone",
        "com.android.bluetooth"
    )

    /** Debounce: one user action can fire several settings callbacks. */
    @Volatile private var lastRunAt = 0L

    /** Set by [install] so the live triggers are wired only once per process. */
    @Volatile private var installed = false

    // ── Entry points ──────────────────────────────────────────────────────────────

    /**
     * Wires the live triggers, from [Application.onCreate]:
     *  * a [ContentObserver] on the theme settings, so picking a colour in
     *    Settings → Wallpapers & style → Colors re-applies within a second instead of at the
     *    next launch, and
     *  * the app's own `PREF_CHANGED` broadcast, so flipping the in-app toggle applies
     *    immediately.
     *
     * Once per process. Never throws.
     */
    fun install(app: Application) {
        if (installed) return
        installed = true
        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = trigger(app, "theme setting")
            }
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(KEY_THEME_OVERLAY), false, observer
            )
            app.contentResolver.registerContentObserver(
                Settings.System.getUriFor(KEY_COLOR_THEME_SETTING), false, observer
            )
            Log.d(TAG, "DIAG: CouiAccent observer registered")
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: CouiAccent observer registration failed: $t")
        }
        try {
            app.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.getStringExtra("key") == PREF_KEY) trigger(app, "toggle")
                }
            }, IntentFilter(ACTION_PREF_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            Log.d(TAG, "DIAG: CouiAccent pref receiver registered")
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: CouiAccent pref receiver registration failed: $t")
        }
    }

    /**
     * Runs the whole pipeline. **Blocking** (spawns `su` shells) — call it off the main
     * thread. Never throws.
     *
     * @param force re-publish and restart consumers even when the accent is unchanged (used
     *              when the user flips the toggle on).
     * @return a short status string, for the log and the debug audit script.
     */
    fun apply(ctx: Context, force: Boolean = false): String =
        try {
            applyInternal(ctx, force)
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: CouiAccent failed: $t")
            "error: $t"
        }

    private fun applyInternal(ctx: Context, force: Boolean): String {
        clearLegacyOverlays()
        if (!isEnabled(ctx)) {
            restoreStock(ctx)
            return "disabled — stock restored"
        }
        val accent = customAccent() ?: run {
            restoreStock(ctx)
            return "featured preset — stock restored"
        }
        val argb = CouiPalette.parseArgb(accent) ?: return "bad accent '$accent' (ignored)"
        backupStockOnce()
        if (!writePalette(ctx, argb)) return "palette write failed ($accent)"
        val changed = force || lastAccent(ctx) != accent.lowercase(Locale.US)
        rememberAccent(ctx, accent)
        val restarted = if (changed) restartConsumers() else emptyList()
        val result = if (changed) {
            "applied $accent; restarted ${restarted.size} app(s) $restarted"
        } else {
            "re-applied $accent (palette already current)"
        }
        Log.d(TAG, "DIAG: CouiAccent $result")
        return result
    }

    /** Debounced, off-main-thread re-apply for the observer / broadcast paths. */
    private fun trigger(ctx: Context, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRunAt < 1500L) return
        lastRunAt = now
        Log.d(TAG, "DIAG: CouiAccent re-apply ($reason)")
        Thread {
            try {
                apply(ctx, force = reason == "toggle")
            } catch (t: Throwable) {
                Log.d(TAG, "DIAG: CouiAccent trigger failed: $t")
            }
        }.apply {
            isDaemon = true
            name = "coui-accent"
        }.start()
    }

    // ── Root work ─────────────────────────────────────────────────────────────────

    /** Flipped once the OEM stubs are safely in [STOCK_DIR]. */
    @Volatile private var stockBackedUp = false

    /**
     * Disables any `com.android.shell:aeCoui_*` overlay left behind by the pre-uxres
     * implementation, so upgrading to this build removes the old per-slot patches instead of
     * leaving them enabled next to the palette. Idempotent and cheap.
     */
    private fun clearLegacyOverlays() {
        try {
            val listed = RootShell.exec("cmd overlay list --user 0", QUICK_TIMEOUT_S)
            if (!listed.ok) return
            listed.output.lineSequence()
                .map { it.trim() }
                .filter { it.contains(LEGACY_OVERLAY_MARK) }
                .map { it.substringAfter("com.android.shell:").trim() }
                .filter { it.isNotEmpty() }
                .forEach {
                    RootShell.exec(
                        "cmd overlay disable --user 0 com.android.shell:$it", QUICK_TIMEOUT_S
                    )
                    Log.d(TAG, "DIAG: CouiAccent disabled legacy overlay $it")
                }
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: CouiAccent legacy overlay sweep failed: $t")
        }
    }

    /**
     * Copies the OEM's stubs aside **once**, before the first write of ours — keyed by a
     * marker file so a later run can never overwrite the backup with our own output.
     */
    private fun backupStockOnce() {
        if (stockBackedUp) return
        val res = RootShell.exec(
            "[ -f $STOCK_MARK ] || { mkdir -p $STOCK_DIR; " +
                "cp -f $STORE_DIR/$DAY_FILE $STOCK_DIR/; " +
                "cp -f $STORE_DIR/$NIGHT_FILE $STOCK_DIR/; " +
                "chmod 644 $STOCK_DIR/$DAY_FILE $STOCK_DIR/$NIGHT_FILE; " +
                "touch $STOCK_MARK; }; echo ok",
            QUICK_TIMEOUT_S
        )
        if (res.ok) stockBackedUp = true
        Log.d(TAG, "DIAG: CouiAccent stock backup ok=${res.ok} (${res.output})")
    }

    /**
     * Puts the OEM's stubs back — the Featured path and "fix off" end up byte-identical to
     * stock, and the remembered accent is cleared so re-enabling always restarts consumers.
     */
    private fun restoreStock(ctx: Context): Boolean {
        val res = RootShell.exec(
            "if [ -f $STOCK_DIR/$DAY_FILE ]; then cp -f $STOCK_DIR/$DAY_FILE $STORE_DIR/$DAY_FILE; fi; " +
                "if [ -f $STOCK_DIR/$NIGHT_FILE ]; then cp -f $STOCK_DIR/$NIGHT_FILE $STORE_DIR/$NIGHT_FILE; fi; " +
                "chmod 644 $STORE_DIR/$DAY_FILE $STORE_DIR/$NIGHT_FILE 2>/dev/null; echo ok",
            ROOT_TIMEOUT_S
        )
        forgetAccent(ctx)
        Log.d(TAG, "DIAG: CouiAccent stock restore ok=${res.ok} (${res.output})")
        return res.ok
    }

    /**
     * Writes both stores. The XML is generated into the app's cache dir (no shell-quoting
     * minefield around `<`, `"` and `#`), then copied into place by a root shell and verified
     * against a locally computed MD5 — a silent half-write would be worse than a failure.
     */
    private fun writePalette(ctx: Context, argb: Int): Boolean {
        return try {
            val dayXml = CouiPalette.xml(night = false, accent = argb)
            val nightXml = CouiPalette.xml(night = true, accent = argb)
            val day = File(ctx.cacheDir, DAY_FILE).apply { writeText(dayXml) }
            val night = File(ctx.cacheDir, NIGHT_FILE).apply { writeText(nightXml) }
            val res = RootShell.exec(
                "cp -f ${day.absolutePath} $STORE_DIR/$DAY_FILE; " +
                    "cp -f ${night.absolutePath} $STORE_DIR/$NIGHT_FILE; " +
                    "chmod 644 $STORE_DIR/$DAY_FILE $STORE_DIR/$NIGHT_FILE; " +
                    "chown root:root $STORE_DIR/$DAY_FILE $STORE_DIR/$NIGHT_FILE; " +
                    "md5sum $STORE_DIR/$DAY_FILE $STORE_DIR/$NIGHT_FILE",
                ROOT_TIMEOUT_S
            )
            val out = res.output.lowercase(Locale.US)
            val dayOk = out.contains(md5(dayXml))
            val nightOk = out.contains(md5(nightXml))
            Log.d(TAG, "DIAG: CouiAccent write day=$dayOk night=$nightOk (${res.output})")
            res.ok && dayOk && nightOk
        } catch (t: Throwable) {
            Log.d(TAG, "DIAG: CouiAccent palette write failed: $t")
            false
        }
    }

    /**
     * Restarts the apps that read the palette at process start, so the change is visible
     * without a reboot. Only processes that are **running** are touched, and SystemUI is
     * bounced with SIGTERM rather than a force-stop so init brings it straight back.
     *
     * @return the packages that were actually restarted (for the log / audit output).
     */
    private fun restartConsumers(): List<String> {
        val list = RESTART_PACKAGES.joinToString(" ")
        val cmd = "for p in $list; do " +
            "if pidof \$p >/dev/null 2>&1; then am force-stop \$p && echo \$p; fi; done"
        val res = RootShell.exec(cmd, ROOT_TIMEOUT_S)
        val killed = res.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        RootShell.restartSystemUi()
        Log.d(TAG, "DIAG: CouiAccent restarted ${killed.size} app(s): $killed (ok=${res.ok})")
        return killed
    }

    // ── State ─────────────────────────────────────────────────────────────────────

    /** Separate, unpublished pref file holding the last accent we published. */
    private const val ACCENT_STORE = "coui_accent"

    /**
     * The Custom-path accent as `#aarrggbb`, or `null` when the active colour is not a
     * wallpaper-derived custom colour (i.e. the user picked a Featured preset). Read through
     * `su` so it works regardless of whether an app process may read this secure setting
     * directly. Never throws.
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
                .lowercase(Locale.US)
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
     * `ae_prefs_json`, base64 of a flat String→String JSON). An absent key counts as
     * **enabled**: this is a fix, not an optional behaviour, and a palette is trivially
     * reversible — the stock stubs are restored the moment it is switched off.
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

    private fun lastAccent(ctx: Context): String? =
        ctx.getSharedPreferences(ACCENT_STORE, Context.MODE_PRIVATE)
            .getString(PREF_LAST_ACCENT, null)

    private fun rememberAccent(ctx: Context, accent: String) {
        ctx.getSharedPreferences(ACCENT_STORE, Context.MODE_PRIVATE).edit()
            .putString(PREF_LAST_ACCENT, accent.lowercase(Locale.US))
            .apply()
    }

    private fun forgetAccent(ctx: Context) {
        ctx.getSharedPreferences(ACCENT_STORE, Context.MODE_PRIVATE).edit()
            .remove(PREF_LAST_ACCENT)
            .apply()
    }

    /** `%02x` per byte — `Locale.US` so an Arabic locale cannot alter the digits. */
    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.US, "%02x", it) }




}
