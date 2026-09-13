# System colour fix — WORKING NOTES / HANDOFF (read this first)

Branch: `fix/system-custom-color-system-apps`
Last commit: `b26b8ce` — per-slot RRO fix (WORKS but is the *wrong shape*; being replaced).
Device: OnePlus CPH2747, OxygenOS 16. Wireless adb: `adb connect 192.168.100.229:6666`.

## TL;DR

OxygenOS "Custom colour" (wallpaper-derived accent) leaves **system-app accents white**
(Calculator `=`, Settings/Wi-Fi ON toggles, Clock, Compass, My Files, …). The "Featured"/preset
path tints them; the Custom path does not. Goal: **one function** that fixes *all* Oplus apps at
once, with **semantic** colours (never force one flat accent colour on every surface).

## ❌ Dead ends (do NOT re-investigate)

| Idea | Verdict |
|---|---|
| One overlay on `android` (framework-res) | **No** — 0 coui colours in `framework-res.apk` (34 MB). |
| One overlay on the `oplus` package (`/system_ext/framework/oplus-framework-res.apk`, 12 MB) | **No** — only generic coui bits (`coui_round_corner_*`, `coui_scrollbar_handle_vertical`, `coui_popup_list_*`, `coui_tool_tips_*`). **No accent colours.** |
| A `coui` shared-library package | **Does not exist.** |
| `Settings.Secure.sysui_type_accent_color` | SystemUI only; wrong mechanism. |
| Hard-coding slot names (commit `b26b8ce`) | Works, but is symptom-patching: it only fixes the slots someone happened to look at. |

## ✅ PROVEN (evidence)

1. **155 Oplus/OnePlus packages** (~300 system APKs) — `pm list packages -s`.
2. Every app carries its **own** merged copy of the COUI library:
   `attr/couiColorPrimary = 0x7f04022d` (app namespace `0x7f`). An RRO **can only target one
   package**, so "one overlay for all apps" is impossible. What can be one thing =
   **one universal slot-name set + one loop**.
3. `style/Theme.COUI` (261 items = the app's base theme):
   * `colorAccent` / `colorPrimary` / `colorPrimaryDark` = `@color/coui_theme_primary_color` (**#ffffffff**)
   * `couiColorPrimary(0x7f04022d)` = `@color/coui_color_primary_blue` (**#ff0066ff** day / **#ff247cff** night)
   * parent = `style/ThemeOverrideBase` (size=2) ← the OEM's runtime theme-override injection point.
4. **The apps do NOT resolve the accent through those colours at runtime.**
   Live `cmd overlay lookup com.oneplus.calculator com.oneplus.calculator:color/coui_color_primary_blue`
   = **#ff247cff (blue)**, yet the `=` renders **white**, and
   `fold_button_equals_bg_color = ?attr/couiColorPrimary`.
   ⇒ the live theme value is injected (white) by the OEM and does **not** track the baked colour.
   A funnel-colour override may therefore be a no-op — must be tested.
5. **Some "slots" are hard-coded literals, not attrs.** WirelessSettings:
   `color/switch_outer_circle_color = #ffffffff`, used by `outerCircleColor` **and**
   `outerUnCheckedCircleColor` in the switch style. So the OEM tints it by **replacing a resource
   ID**, i.e. it ships a per-app resource list (theme package / Oplus dynamic-resource provider),
   NOT by painting an attribute.
   ⇒ **Finding that OEM resource list is the key to the "one function" fix.**
6. Calculator has only **10** colour resources whose value is a theme attr
   (3 × `?attr/couiColorPrimary`: `fold_button_equals_bg_color`, `dialog_cancel`,
   `event_down_fold_button_img_equals`). WirelessSettings has **2**, neither of them a switch colour.
   ⇒ "enumerate `?attr`-valued colours" is **not** a sufficient discovery rule.
7. AOSP's own theming shows the pattern: `com.android.systemui:accent` is a *fabricated* overlay
   targeted at `android`, ID-mapping `color/system_accent1_*`. `cmd overlay dump <name>` prints the
   full ID→value mapping — the best tool for reading live values.
8. The live semantic palette is available from the system (use it, never invent colours):
   `system_primary_light`, `system_primary_container_light`, `system_secondary_light`,
   `system_tertiary_light`, `system_on_primary_light`, neutral1/2 ladders.
   Read with `cmd overlay dump com.android.systemui:dynamic|accent|neutral`.

## ⛔ REFUTED ON DEVICE (2026-09-13 02:24) — do not retry

**T1/T2 (funnel / palette-colour override) is DEAD.**

Test: `cmd overlay fabricate --target com.oneplus.calculator --name funTest
com.oneplus.calculator:color/coui_color_primary_blue 0x1c 0xffff00ff` (through the app's root path),
enable, force-stop, relaunch.

* `cmd overlay lookup com.oneplus.calculator com.oneplus.calculator:color/coui_color_primary_blue`
  → **#ffff00ff** (override definitely live).
* Screenshot: `%TEMP%\shot_funnel2.png` → the `=` key is **still WHITE**.

⇒ The app does **not** resolve `?attr/couiColorPrimary` through `coui_color_primary_blue` at runtime.
Something (the OEM's runtime theme/resource injection) supplies **white** for `couiColorPrimary`.
⇒ **The only working mechanism is overriding the *final slot resource* itself** (what commit `b26b8ce`
does) — that is why the per-slot fix worked and the funnel does not.

Also refuted: `adb shell` is **not root** on this device, so `fabricate`/`enable` from adb fail with
"must be root"; they must run from the app's RUN_AUDIT root path.

## ✅ RESOLVED (2026-09-13 05:09) — shipped and verified on device

The mechanism is **not** an RRO. It is the OEM's device-global Coui colour store:

```
/data/oplus/uxres/uxcolor/ux_custom_color.xml        (day,   805 B, root:root, stock = all-white stub)
/data/oplus/uxres/uxcolor/ux_custom_color_night.xml  (night, 805 B)
```

`couiSingleFirstNormal = #FFFFFFFF` in the Custom-path stub **is** the white bug. Writing the
user's accent into the `Single` family fixes every `?attr/couiColorPrimary` consumer at once.

### Implementation (this branch)

* `CouiPalette.kt` — pure, JVM-tested (`CouiPaletteTest`, 8/8 green) palette maths: accent →
  6 slots (`Normal`, `Pressed` ×0.78 day / ×0.88 night, `LightNormal` @0x4C day / 0x66 night,
  `LightPressed` @0x4C, `TextHighLight` and `BarDisabledColor` @0x26) each with its `NXcolor`
  twin, in the OEM's exact XML shape (no trailing newline, `#AARRGGBB`, `Locale.US`).
* `CouiAccentFix.kt` — root plumbing: stock backup **once** to `/data/local/tmp/ae_uxcolor_stock`
  (+ `.ae_stock_v1` marker), write both stores, **verify by MD5**, restart the running consumers
  (`pidof`-guarded force-stops + `pkill -TERM` for SystemUI), restore stock verbatim on the
  Featured path / fix-off, and sweep the legacy `com.android.shell:aeCoui_*` overlays.
* `App.kt` — `CouiAccentFix.install(this)` before the first apply: `ContentObserver` on
  `theme_customization_overlay_packages` + `color_theme_setting`, and a `PREF_CHANGED`
  receiver (self-targeted broadcast added in `MainActivity.broadcastPref`).
* `SettingsScreens.kt` — the `system_color_fix_enabled` switch now defaults **on**, matching
  the code's "absent = enabled" semantics.

### Device evidence

| Check | Result |
|---|---|
| Store after apply | `#FFF13871` + `#FFBB2B58` / `#4CF13871` / `#26F13871` + 6 `NXcolor` twins (night: `#FFD43163`, `#66F13871`) |
| File shape | 805 B, `root:root`, `644` — byte-shape identical to stock |
| Calculator | `=` **pink**, `deg` label **pink** |
| Wi-Fi (WirelessSettings) | ON toggle, "Refresh", "Add network", SSID icon all **pink** |
| Overlays | every `aeCoui_*` / `funTest` = `[ ]` (the new code disables the legacy ones) |
| Logcat | `CouiAccent observer registered` / `pref receiver registered` / `disabled legacy overlay aeCoui_0..2` |
| Live trigger | a `settings put secure theme_customization_overlay_packages` write while the app ran logged `re-apply (theme setting)` → store write (pink md5) → `re-applied #fff13871 (palette already current)`, i.e. the observer fires and the unchanged-accent branch correctly skips consumer restarts |

### Facts worth keeping

* **A running app keeps the palette it read at start.** A live UI never re-tints (proven: the
  file changed under an open Wi-Fi screen, nothing moved); force-stop + relaunch always picks
  it up. Hence the restart step.
* The **OEM engine does not overwrite our writes**: MD5s were still ours hours later.
* `uxicons/<pkg>` (99 entries incl. Chrome/WhatsApp) is the *launcher icon* theming pipeline —
  a red herring for accents.
* `com.oplus.uxdesign`'s state lives in `shared_prefs/uxcolor_info_sp.xml`
  (`google_colors_key` = a 5×5 semantic palette matrix, `custom_color_version=16`).
* `adb shell` cannot even stat `/data/oplus/uxres/...`; **root** (the app's `RUN_AUDIT` path) can.
* The wrong process will fool you: the Wi-Fi screen is `com.oplus.wirelesssettings`, **not**
  `com.android.settings`.

### Dead ends, now disproven (do not re-try)

1. `coui_color_primary_blue` / palette-colour overrides — confirmed live in `cmd overlay lookup`
   yet the `=` key stayed white: apps do not resolve `?attr/couiColorPrimary` through it.
2. OEM per-app RROs / theme overlays — **none exist** (only display/navbar/fingerprint).
3. Per-slot fabricated overlays (previous build, `b26b8ce`) — worked, but whack-a-mole:
   it needed one slot per broken widget, missed `deg`, and is now replaced.
4. `sysui_type_accent_color` — SystemUI only (kept as its own small hook: `SystemColorHook`).

### Note for future testing

`App.onCreate` runs `CouiAccentFix.apply`, and `RUN_AUDIT` **starts the app** — so any colour
test is contaminated unless the fix is disabled first (`SET_PREF system_color_fix_enabled 0`)
and the overlays are checked. That mistake cost a full cycle: the "successful" cyan result first
looked like a discovery while it was really the old per-slot RRO being re-enabled by the app.


### `/data/oplus/uxres/uxcolor/` — the ONE global colour store for every app

```
-rwxrwxrwx u0_a309 coui_theme_color_wallpaper.xml       6093 B
-rwxrwxrwx u0_a309 coui_theme_color_wallpaper_night.xml 6093 B
-rw-r--r-- root    ux_custom_color.xml                   805 B
-rw-r--r-- root    ux_custom_color_night.xml             805 B
drwxrwxrwx u0_a309 temp/
```

* Owned/written by **`com.oplus.uxdesign`** (appId 10309) — NOT by an RRO. There are **no OEM
  overlays at all** for these apps (proven by the overlay dump), so this file store is the channel.
* `coui_theme_color_wallpaper.xml` = `<group><index>21</index>` with 5 `<child>` palettes
  (Green/Red/Yellow/Blue/Orange) × ~21 colours each, e.g.
  `couiBlueTintControlNormal #FF848DC8`, `NXcolorBlueTintControlNormal`,
  `couiBlueTintControlPressed`, `couiBlueTintLightNormal/Pressed`, `couiTextBlueHighlight`,
  `switchCheckedBlueBarDisabledColor`, `switchCheckedBlueInnerCircleDisabledColor` (+ `NX…` twins).
  ⇒ **This is the authoritative slot-name set** (hue families, exactly the "3-5 colours").
* `ux_custom_color.xml` = the Custom path = only 12 entries, family **`Single`**, and its primary
  `couiSingleFirstNormal` is **#FFFFFFFF** (white!) — the white-bug signature.

### `com.oplus.uxdesign` state (`shared_prefs/uxcolor_info_sp.xml`)

```xml
static_wallpaper_colors_key = [-14596929,-14596929,-14596929,-14596929,-14596929]
google_colors_key = [[-7237481,-7499866,-5470815,-8090168,-7303014],
                     [-7237231,-7237231,-7237231,-7237231,-7237231],
                     [-7171950,-7303014,-7499866,-7368801,-7171950],
                     [-7303012,-7107406,-6256966,-9271297,-7368801],
                     [-7106663,-5929047,-6846018,-11100301,-7106913]]
custom_color_version = 16
```

**`google_colors_key` is a 5×5 semantic palette (25 colours)** computed by the engine from the
wallpaper/custom colour — this is the source we should map roles from. Decoded samples:
`-7237231 = #FF919191` (neutral row), `-6256966 = #FFA086BA`, `-5929047 = #FFA587A9`.

### Empirically: the `=` key DID become the real accent (#F13871 pink)

After touching/editing those uxres files, the Calculator's `=` rendered **pink** with
`%TEMP%\shot_ux2.png` — while **all** `aeCoui_*` RROs were disabled and `funTest` only set
`coui_color_primary_blue` (magenta). Cause not yet pinned (content vs mtime-reload — the restore
test with `%TEMP%\shot_restored.png` answers it). Sub-lever candidates:
* writing the correct palette into `/data/oplus/uxres/uxcolor/*.xml` (files are world-writable), and/or
* touching them so `com.oplus.uxdesign` re-publishes the theme.

Backups of the original 4 files: `/data/local/tmp/uxbak/` on the device.


The switch circle (`color/switch_outer_circle_color = #ffffffff`) is a **hard literal**, so a theme
*style* cannot tint it — yet Featured does. The only mechanism that can rewrite a literal is a
**dynamic resource provider** that replaces resource values wholesale. Candidates found on device:

* package **`com.oplus.appplatform`** (Oplus AppFeature / ResourceProvider host)
* **`/data/oplus/uxres`** and **`/data/oplus/uxicon`** (per-app "UX resources" stores)
* package `com.heytap.colorfulengine`

If that store is the OEM's authoritative slot set, updating **it** = the true one-function fix.
Probe: `/data/local/tmp/ae_audit.sh` → `/data/local/tmp/ae_probe2.txt`.

### Palette source candidate

`Settings.Global wallpapers_inuse_theme` (JSON) holds
`"colorStyle":{"category":2,"customColor":-968591,"selectIndex":0,"selfColor":[5 values],"twoTone":0}`
and `"wallpaperColorInfo":{"wallpaperColorDark":…,"wallpaperColorLight":…,"wallpaperColorSeed":…}`.
`selfColor` is a **5-element array** — matches the user's "there are 3-5 colours" observation.


1. **Find the OEM's per-app resource list / theme package.**
   Root probe: `/data/local/tmp/ae_audit.sh` (source `%TEMP%\ae_probe.sh`, **must be LF, not CRLF**)
   is run by the app itself:
   ```
   adb shell am broadcast -a io.github.kvmy666.autoexpand.RUN_AUDIT \
       -n io.github.kvmy666.autoexpand/.DebugPrefReceiver
   ```
   It writes `/data/local/tmp/ae_probe.txt` (all overlays incl. **disabled**, `dumpsys overlay`
   states, `/data/resource-cache`, `/data` theme/overlay/coui search, `*.frro` search, theme
   processes/services, theme settings, oplus theme packages).
   **A DISABLED overlay targeting an app (e.g. WirelessSettings) on the Custom path is the smoking
   gun** — its mapping IS the authoritative slot list.
2. Confirm what the **Featured** path creates: switch to a preset, re-dump `cmd overlay list --user 0`
   (ALL entries, not just `[x]`), then diff.
3. Mechanism decision: `cmd overlay fabricate` per app (proven, OMS-allowlisted) vs a static RRO
   (**rejected**: needs `overlayable` + platform signing; a Magisk-placed RRO would fail).

## ⚙️ Operational gotchas (learned the hard way)

* **Scripts pushed to the device must use LF line endings** — Windows CRLF makes `sh` produce an
  empty/failed run (this cost a cycle: root script ran, output file stayed 0 bytes).
* `cmd overlay fabricate` / `enable` / `lookup` **must NOT have stdout redirected to a file** — the
  command ships the overlay over a file descriptor and a redirected stdout fails with
  `Failed transaction (2147483646)`. Pipes are fine (`list`/`dump` are safe to redirect too).
* Fabricated overlays register under the **caller** (`com.android.shell:<name>`), never `<target>:<name>`.
* `OverlayManagerService.commit()` requires root or shell ⇒ run from the **app process** (it has `su`).
* An RRO change needs the target app **restarted** (force-stop) before it becomes visible.
* `adb` from PowerShell: wrap every call as `cmd /c "... 2>&1"`, otherwise PowerShell raises
  `NativeCommandError` and **aborts loops**. Commands inside ONE tool call may run **in parallel** —
  put dependent steps in a single command string.
* Keep each tool command **under ~30 s** or it times out (`wait-for-device` hangs forever — avoid).
* The device sleeps/drops often; reconnect with `adb connect 192.168.100.229:6666`.
* `adb shell` has **no `su`**; root is reachable only through the app's RUN_AUDIT receiver.

## 🧹 Cleanup pass (after the fix, 2026-09-13)

The fix landed first; then the code that only made sense under the *old* theories was removed.

* **The legacy overlay sweep is now one-shot** (`PREF_LEGACY_SWEPT`, in the fix's own small prefs
  file). It used to run a root `cmd overlay list --user 0` on **every** apply — i.e. on every app
  start — looking for `aeCoui_*` overlays that only the unreleased `b26b8ce` build ever created.
* **One implementation of the theme rules.** `CouiPalette.customAccent(source, accentColor,
  systemPalette)` and `CouiPalette.isBlankAccent(value)` replaced the hand-copied parsing and the two
  separate "blank accent" sets in `CouiAccentFix` and `hook/SystemColorHook`. Behaviour is identical;
  the SystemUI copy quietly gains the locale-safe lowercase and the `null` guard the app side had.
* **Two stale comments corrected** — `App.kt` still claimed the fix "re-fabricates resource overlays",
  and `SystemColorHook`'s header still asserted the disproven "the type accent is the whole
  difference" theory.
* **Tests: 8 → 10**, all green (`customAccent` rules + `isBlankAccent`). One of the two new tests
  failed on its first run because the *expectation* was wrong, not the code: the accent keeps the 6- or
  8-digit shape it was given, and `parseArgb` reads either.
* **≈250 MB of device dumps deleted** from `.local/syscolor/` (the pulled `Settings.apk` and
  `Calculator2.apk` copies plus two screenshots). The text evidence — `calc-res.txt`,
  `overlays.txt` — is kept; nothing in the repo references any of it, and the dumps are re-pullable.
* **New doc:** `docs/how-the-color-fix-works.html`, the beginner-friendly waterfall write-up.

Deliberately **not** changed: `SystemColorHook`'s default. The app-side fix treats "pref absent" as
enabled, the SystemUI companion stays quiet until the pref says otherwise; the header now states that
difference instead of pretending there is none.

Not part of this pass (pre-existing, unrelated to colours): the heartbeat `EACCES` log spam.

## 📋 Remaining work

None for the colour fix. Everything this list used to hold is done and verified on device: the
force-stop strategy (`restartConsumers`, `pidof`-guarded), the in-app toggle
(`SettingsScreens` → `system_color_fix_enabled`, default on) and the rewritten docs.
