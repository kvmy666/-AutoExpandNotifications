package io.github.kvmy666.autoexpand

import java.util.Locale

/**
 * Pure palette maths for the OxygenOS / ColorOS **"Custom colour"** fix.
 *
 * ## Why this exists
 * System apps on ColorOS/OxygenOS do **not** tint from the Material-You `android` palette.
 * They tint from the Oplus **uxres** colour store, a device-global file pair:
 *
 * ```
 * /data/oplus/uxres/uxcolor/ux_custom_color.xml        (day)
 * /data/oplus/uxres/uxcolor/ux_custom_color_night.xml  (night)
 * ```
 *
 * On the **Featured/preset** path the OEM fills that store with a real palette. On the
 * **Custom (from wallpaper)** path it ships a stub whose accent slot `couiSingleFirstNormal`
 * is literally `#FFFFFFFF` — so every `?attr/couiColorPrimary` consumer (Calculator's `=`,
 * Settings' ON toggles, the connected-Wi-Fi icon, My Files, …) renders **white**.
 *
 * Rewriting the accent family of that store *is* the whole fix. Proven on a CPH2747
 * (OOS 16): with cyan in the slot and **no** resource overlay anywhere, Calculator's `=`,
 * the Wi-Fi ON toggle, its links and the connected-network icon all turned cyan — see
 * `docs/system-color-fix.md` for the evidence trail.
 *
 * ## The slot family
 * The Custom store defines one 6-slot family, each slot duplicated with an `NXcolor` prefix
 * (the OEM writes both, so both are written here):
 *
 * | slot                           | role                                    |
 * |--------------------------------|-----------------------------------------|
 * | `…SingleFirstNormal`           | the accent itself                        |
 * | `…SingleFirstPressed`          | the accent, pressed/darker               |
 * | `…SingleFirstLightNormal`      | the accent at 30 % (day) / 40 % (night)  |
 * | `…SingleFirstLightPressed`     | the pressed accent at 30 %               |
 * | `…SingleFirstTextHighLight`    | the accent at 15 % (text highlight)      |
 * | `…SingleFirstBarDisabledColor` | the accent at 15 % (disabled track)      |
 *
 * The alphas mirror the OEM's own stub; the pressed shade mirrors the Featured palette's
 * `couiBlueTintControlNormal #FF848DC8` → `…Pressed #FF5662B3` step (≈ ×0.80).
 *
 * Deliberately Android-free and stateless so it can be unit-tested on the JVM
 * (`CouiPaletteTest`) — the root plumbing lives in [CouiAccentFix].
 */
object CouiPalette {

    /** Alpha of `…LightNormal` in the day store (the OEM's own stub uses `#4CFFFFFF`). */
    const val DAY_LIGHT_NORMAL_ALPHA = 0x4C

    /** Alpha of `…LightNormal` in the night store (`#66FFFFFF`). */
    const val NIGHT_LIGHT_NORMAL_ALPHA = 0x66

    /** Alpha of `…LightPressed` (`#4C…` in both stores). */
    const val LIGHT_PRESSED_ALPHA = 0x4C

    /** Alpha of `…TextHighLight` and `…BarDisabledColor` (`#26…` in both stores). */
    const val HIGHLIGHT_ALPHA = 0x26

    /** Day pressed shade: the Featured palette's Normal → Pressed step. */
    const val DAY_PRESSED_FACTOR = 0.78

    /** Night pressed shade: still darker, gentler (night surfaces are already dark). */
    const val NIGHT_PRESSED_FACTOR = 0.88

    private const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>"
    private const val OPEN = "<resources>"
    private const val CLOSE = "</resources>"

    /** Base slot names, in the exact order the OEM writes them. */
    private val BASE_SLOTS = listOf(
        "couiSingleFirstNormal",
        "couiSingleFirstPressed",
        "couiSingleFirstLightNormal",
        "couiSingleFirstLightPressed",
        "couiSingleFirstTextHighLight",
        "couiSingleFirstBarDisabledColor"
    )

    /**
     * Parses `#rrggbb`, `rrggbb`, `#aarrggbb` or `aarrggbb` (any case) into ARGB, or returns
     * `null` when the string is not a colour. A 6-digit colour is taken as opaque, which is
     * how the theme JSON stores it (`"accent_color":"FFF13871"` / `"#f13871"`).
     */
    fun parseArgb(hex: String): Int? {
        val body = hex.trim().removePrefix("#")
        if (body.length != 6 && body.length != 8) return null
        if (!body.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val value = body.toLongOrNull(16) ?: return null
        return if (body.length == 6) (0xFF000000L or value).toInt() else value.toInt()
    }

    /**
     * Slot name → ARGB for one of the two stores, in write order. The accent is forced
     * opaque: every `Normal`/`Pressed` slot in the stock store is opaque and the
     * `Light`/`HighLight` slots get their translucency from the alpha applied here.
     */
    fun slots(night: Boolean, accent: Int): LinkedHashMap<String, Int> {
        val normal = accent or 0xFF000000.toInt()
        val pressed = shade(normal, if (night) NIGHT_PRESSED_FACTOR else DAY_PRESSED_FACTOR)
        val lightAlpha = if (night) NIGHT_LIGHT_NORMAL_ALPHA else DAY_LIGHT_NORMAL_ALPHA

        val values = LinkedHashMap<String, Int>()
        for (name in BASE_SLOTS) {
            values[name] = when (name) {
                "couiSingleFirstNormal" -> normal
                "couiSingleFirstPressed" -> pressed
                "couiSingleFirstLightNormal" -> withAlpha(normal, lightAlpha)
                "couiSingleFirstLightPressed" -> withAlpha(pressed, LIGHT_PRESSED_ALPHA)
                // TextHighLight + BarDisabledColor share the 15 % highlight alpha.
                else -> withAlpha(normal, HIGHLIGHT_ALPHA)
            }
        }
        // The OEM duplicates every slot with an `NXcolor` prefix, value for value.
        for ((name, value) in values.toList()) values[nx(name)] = value
        return values
    }

    /**
     * The XML the framework expects, identical in shape to the OEM's stub: declaration,
     * `<resources>`, one `<color>` per slot, closing tag, **no trailing newline** (the
     * stock file has none either).
     */
    fun xml(night: Boolean, accent: Int): String {
        val body = slots(night, accent).entries.joinToString("\n") { (name, value) ->
            "<color name=\"$name\">${format(value)}</color>"
        }
        return listOf(HEADER, OPEN, body, CLOSE).joinToString("\n")
    }

    /**
     * `#AARRGGBB`, uppercase — the OEM's own spelling. `Locale.US` is not cosmetic here: on
     * an Arabic-locale device the default locale would render hex digits as Arabic-Indic
     * numerals and the framework would parse garbage.
     */
    fun format(argb: Int): String = String.format(Locale.US, "#%08X", argb)

    /** Replaces the alpha channel, keeping RGB. */
    fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    /** Scales RGB toward black by [factor] (clamped to 0..255), keeping alpha. */
    fun shade(argb: Int, factor: Double): Int {
        val a = (argb ushr 24) and 0xFF
        fun channel(shift: Int) =
            (((argb ushr shift) and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** `couiSingleFirstNormal` → `NXcolorSingleFirstNormal`, as the OEM spells it. */
    fun nx(name: String): String = "NXcolor" + name.removePrefix("coui")

}
