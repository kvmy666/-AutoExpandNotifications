# OxygenOS "Custom colour" — system apps render the accent as WHITE

Device of record: **OnePlus CPH2747, OxygenOS 16**. Status: **fixed & verified on screen** —
`#F13871` renders as pink on Calculator's `=` key *and* its `deg` label, the Wi-Fi ON toggle,
"Refresh", "Add network" and the connected-network icon, with **no resource overlays in play at
all** (`cmd overlay list` shows every `aeCoui_*` entry as `[ ]`).

**New to this bug?** Read `docs/how-the-color-fix-works.html` first — the same story told as a
waterfall (requirements → analysis → design → implementation → testing → deployment →
maintenance), written for someone who has never seen the code.

## Symptom

Settings → Wallpapers & style → Colors → **Custom** (colour picked from the wallpaper):

| Surface | Featured preset | **Custom (wallpaper)** |
|---|---|---|
| Calculator `=` key / `deg` label | tinted | **white** |
| Settings · Wi-Fi ON toggles | tinted | **white** |
| Connected-Wi-Fi icon, "Refresh", "Add network" | tinted | **white** |
| My Files accent | tinted | **white** |
| Third-party apps | correct | **correct** |

## Root cause

OxygenOS paints accents through **two independent channels**; only one of them is broken.

1. **Material You** — the `android` palette. Under Custom this *is* written correctly (the
   wallpaper-derived palettes carry the chosen colour), which is exactly why third-party apps
   tint correctly.
2. **Oplus *Coui*** — the channel every Oplus system app actually tints from: colour resources
   whose value is a theme attribute such as `?attr/couiColorPrimary`. The **Featured** path
   paints that channel; the **Custom** path never does, so those slots keep the white
   placeholder compiled into the APK.

### The Coui channel is a FILE, not an RRO

`/data/oplus/uxres/uxcolor/` — the OEM's device-global Coui colour store, written by the
**`com.oplus.uxdesign`** system app (that is the uid that owns it: `u0_a309`, appId `10309`):

```
-rwxrwxrwx u0_a309 coui_theme_color_wallpaper.xml        6093 B   Featured path, 5 hue families
-rwxrwxrwx u0_a309 coui_theme_color_wallpaper_night.xml  6093 B
-rw-r--r-- root    ux_custom_color.xml                    805 B   Custom path ← the white stub
-rw-r--r-- root    ux_custom_color_night.xml              805 B
```

`ux_custom_color.xml` **is** the white bug: the whole `Single` family is a placeholder that the
Custom path never fills in.

```xml
<color name="couiSingleFirstNormal">#FFFFFFFF</color>          <!-- the accent itself -->
<color name="couiSingleFirstPressed">#FF4D4D4D</color>
<color name="couiSingleFirstLightNormal">#4CFFFFFF</color>     <!-- 30 % alpha -->
<color name="couiSingleFirstLightPressed">#4C4D4D4D</color>
<color name="couiSingleFirstTextHighLight">#26FFFFFF</color>   <!-- 15 % alpha -->
<color name="couiSingleFirstBarDisabledColor">#26FFFFFF</color>
<!-- every slot duplicated verbatim with an NXcolor prefix: NXcolorSingleFirstNormal, … -->
```

Every `?attr/couiColorPrimary` consumer resolves through that family — one file, every app.
That is the whole reason this beats enumerating broken widgets one by one: Calculator, Settings,
WirelessSettings, My Files and apps nobody has inspected yet all read the same six slots.

The **Featured** path fills the sibling file instead: `coui_theme_color_wallpaper.xml` carries
five hue families (`Green/Red/Yellow/Blue/Orange`) × 14 slots
(`couiXxxTintControlNormal/Pressed`, `…TintLightNormal/Pressed`, `couiTextXxxHighlight`,
`switchCheckedXxxBarDisabledColor`, `switchCheckedXxxInnerCircleDisabledColor`, + `NX…` twins),
e.g. `couiBlueTintControlNormal #FF848DC8` → `…TintControlPressed #FF5662B3`. **That file is the
OEM's and is left untouched** — it is not broken.

## The fix

Rewrite the accent family of the Custom store with the user's accent. Nothing else — no RROs,
no per-slot enumeration, no APK-patching.

### 1. Derive the palette — `CouiPalette.kt`

| slot | value |
|---|---|
| `…SingleFirstNormal` | the accent, opaque |
| `…SingleFirstPressed` | accent ×0.78 (day) / ×0.88 (night) |
| `…SingleFirstLightNormal` | accent at alpha `0x4C` (day) / `0x66` (night) |
| `…SingleFirstLightPressed` | pressed at alpha `0x4C` |
| `…SingleFirstTextHighLight` | accent at alpha `0x26` |
| `…SingleFirstBarDisabledColor` | accent at alpha `0x26` |

…each duplicated with an `NXcolor` prefix, written in the OEM's exact XML shape
(`<?xml … standalone="yes" ?>`, `<resources>`, `#AARRGGBB` uppercase, **no trailing newline**).
The alphas are taken from the OEM's own stub; the pressed step mirrors the Featured palette's
`#FF848DC8 → #FF5662B3`. `Locale.US` in the formatter is load-bearing: under an Arabic locale the
default formatter emits Arabic-Indic digits and the framework would parse garbage.

Pure and Android-free, so it is unit-tested on the JVM (`CouiPaletteTest`, 8 tests).

### 2. Publish it — `CouiAccentFix.kt`

1. Read the accent from `Settings.Secure.theme_customization_overlay_packages`
   (`android.theme.customization.accent_color` / `system_palette`, read through `su`).
   Only fires when `color_source` ∈ `home_wallpaper | lock_wallpaper | photo | custom_image` —
   **Featured presets are never touched**.
2. Back the stock stubs up **once** to `/data/local/tmp/ae_uxcolor_stock` (marker file
   `.ae_stock_v1` guards against ever backing up our own output).
3. Write both stores: XML generated into the app cache dir, copied into place by a root shell,
   then **verified against a locally computed MD5** — a silent half-write would be worse than a
   failure. Permissions are restored to `root:root 644`.
4. **Restart the running consumers.** A process keeps the palette it read at start, so nothing
   re-tints in a live UI. Every package in `RESTART_PACKAGES` that is actually running
   (`pidof` guard) is force-stopped; SystemUI is bounced with `pkill -TERM` so init brings it
   back. On a cold device this step is a no-op — the palette simply applies as each app starts.
5. Sweep the legacy `com.android.shell:aeCoui_*` overlays from the previous implementation
   (disabled on sight), so upgrading leaves nothing behind.

Running from the **app process** (`App.onCreate`) is deliberate: it needs `su`, and
`OverlayManagerService.commit()` only accepts root/shell. A `ContentObserver` on the theme
settings plus the app's own `PREF_CHANGED` broadcast re-apply within a second when the user
picks a colour or flips the toggle — no reboot, no app relaunch.

**Live trigger, verified on device**: writing `theme_customization_overlay_packages` while the
app was running produced

```
DIAG: CouiAccent re-apply (theme setting)
DIAG: CouiAccent write day=true night=true (d43e87acdf997a8d44eee24719e580fc …)
DIAG: CouiAccent re-applied #fff13871 (palette already current)
```

Note the third line: the write carried the value that was current by the time it arrived (the
settings provider coalesces rapid writes, so a notification can be a few seconds late). That is
harmless by construction — the apply is idempotent and always publishes the *newest* accent, and
a value that has not actually changed skips the consumer restarts.

### 3. Reversibility

* Leaving the Custom path (or switching the fix off) restores the OEM stubs **verbatim** from
  the backup, so the device can never be stranded on our values.
* Opt-out: prefs key `system_color_fix_enabled` (`"0"` disables). Absent = enabled — this is a
  fix, and it is trivially reversible.
* `…_night.xml` is written too, so light/dark switching stays consistent.

## Evidence trail (on device)

1. **The channel is real.** With the app's fix *disabled* and every overlay off (`funTest` +
   all six `aeCoui_*` = `[ ]`), rewriting `couiSingleFirstNormal` to `#FF00FFFF` turned
   Calculator's `=` **cyan** — no overlay anywhere in the picture.
2. **It generalises.** Same store, still no overlays: the Wi-Fi screen (hosted by
   **`com.oplus.wirelesssettings`** — not `com.android.settings`) turned cyan on its ON toggle,
   "Refresh" and "Add network". With the *real* accent written by this implementation, all of
   those plus the connected-SSID icon and Calculator's `deg` label read **#F13871 pink**.
3. **It is read at process start.** Changing the file under an open Wi-Fi screen changed
   nothing until the process was force-stopped; after the restart it always picked the new
   value up. Hence the restart step in the fix.
4. **It survives.** Hours later the store still held our MD5 — the OEM's `com.oplus.uxdesign`
   does not fight the write. (It does own the file, so a future OEM theme change can overwrite
   it; the observer + the apply-on-start cover that.)
5. **The end state carries no overlays**: `aeCoui_*` all `[ ]` — the new code sweeps them.

## How to re-verify

```sh
# the store (root only: adb shell cannot even stat /data/oplus/uxres/…)
su -c 'cat /data/oplus/uxres/uxcolor/ux_custom_color.xml'
# the live log lines
adb logcat -d -s Snapper:D | findstr /i couiaccent
# overlays must all read [ ]
adb shell cmd overlay list | findstr /i aecoui
```

Then open Calculator and Settings → Wi-Fi: `=`, `deg`, the ON toggle, "Refresh" and
"Add network" should all match the picked colour.

## What was tried first and disproven (do not repeat)

| Lead | Result |
|---|---|
| Fabricated overlay on `com.oneplus.calculator:color/coui_color_primary_blue` | the overlay value **was** live per `cmd overlay lookup`, yet the `=` key stayed white — apps do not resolve `?attr/couiColorPrimary` through the palette colour |
| OEM per-app theme RROs | **none exist** — the overlay dump has only display/navbar/fingerprint entries |
| `Settings.Secure.sysui_type_accent_color` | SystemUI-only; it tints QS tiles and nothing else (kept as `SystemColorHook`) |
| `/data/oplus/uxicons/<pkg>` | launcher *icon* theming (day/mat/monochrome PNGs), not accents |
| Per-slot overlays (previous build `b26b8ce`) | worked, but whack-a-mole: one slot per broken widget (`fold_button_equals_bg_color`, `switch_outer_circle_color`, …) and it silently missed surfaces such as `deg` |

### Test-harness trap worth remembering

`RUN_AUDIT` **starts the app**, and `App.onCreate` applies the fix — so a root-script-driven
colour test re-fabricates and re-enables the old overlays *while you are disabling them*. Always
set `system_color_fix_enabled = 0` first and confirm every `aeCoui_*` reads `[ ]` before drawing
conclusions. This cost a full cycle: a cyan `=` briefly looked like a discovery while it was
really the previous RRO being switched back on by the app.

## Files

| File | Role |
|---|---|
| `CouiPalette.kt` | pure palette maths + the shared Custom-path rules (`customAccent`, `isBlankAccent`) and the OEM XML shape (unit-tested) |
| `CouiAccentFix.kt` | root publish: backup, write, MD5 verify, restart consumers, one-shot legacy sweep, live triggers |
| `App.kt` | `install()` (observer + toggle receiver) then apply on process start |
| `MainActivity.kt` | self-targeted `PREF_CHANGED` so the fix toggle applies live |
| `hook/SystemColorHook.kt` | SystemUI-only `sysui_type_accent_color` companion (shares `CouiPalette.customAccent`) |
| `app/src/test/…/CouiPaletteTest.kt` | 10 JVM tests locking the palette, the theme rules and the XML contract |
| `docs/how-the-color-fix-works.html` | beginner-friendly waterfall walkthrough of the whole investigation |


