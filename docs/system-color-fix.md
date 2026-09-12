# OxygenOS "Custom colour" — system apps render the accent as WHITE

Device of record: **OnePlus CPH2747, OxygenOS 16**. Status: **fixed & verified on screen**.

## Symptom

Settings → Wallpapers & style → Colors → **Custom** (colour picked from the wallpaper):

| Surface | Featured preset | **Custom (wallpaper)** |
|---|---|---|
| Calculator `=` key | tinted | **white** |
| Settings ON toggles | tinted | **white** |
| Connected-Wi-Fi icon / toggle | tinted | **white** |
| My Files accent | tinted | **white** |
| Third-party apps | correct | **correct** |

## Root cause

There are **two independent accent channels** on OxygenOS, and only one of them is broken.

1. **Material You** — the `android` target palette. Under Custom this *is* written correctly
   (the wallpaper-derived `.frro` overlays really do carry the chosen pink). This is why
   **third-party apps tint correctly.**
2. **Oplus *Coui*** — the channel every Oplus system app actually tints from. Its resources
   resolve through theme attributes such as **`?attr/couiColorPrimary`**. The Featured-preset
   path paints those attributes; the **Custom path never writes them**, so they fall through to
   the white placeholder compiled into the app.

The apps are not "ignoring" the Custom colour — they read a channel the OEM forgot to paint.
Confirmed in the shipped APKs: all four system apps carry
`color/coui_theme_primary_color = #ffffffff`, and their accent slots resolve to white while the
Material palette is pink.

### How a broken surface actually renders white (traced, Calculator)

```
layout/pad_numeric_land_all
└─ COUIButton id/eq (the '=' key)
   └─ android:background = @drawable/fold_button_equals_img_gradient_bg   (res/lr.xml)
      └─ layer-list → shape → solid android:color = @color/fold_button_equals_bg_color
         ↑  color/fold_button_equals_bg_color  ==  ?attr/couiColorPrimary
                                                    └─ never painted on the Custom path → #ffffffff
```

`COUIButton` reads its paint colours from the `COUIButton` styleable — index 9 is
`COUIButton_drawableColor`, index 20 `strokeColor`. For the `=` key the visible circle comes
from the **background drawable** above, whose `solid` is an *accent slot*: a **colour resource
whose value is a theme attribute**.

### What is NOT the cause (each disproven on device)

* ✗ `Settings.Secure.sysui_type_accent_color` — read by **SystemUI only**; `Settings.apk` has
  zero references to it. Writing it turns the QS tiles pink and nothing else.
* ✗ Per-app resource overlays created by the OEM on the Featured path — `/data/resource-cache`
  diffs show the OEM creates none.
* ✗ `com.oplus.appplatform` AppFeature / `THEME_RES_ID_KEY`, `persist.sys.theme`.
* ✗ `color/coui_theme_primary_color` — overriding it alone changes nothing visible; it is not
  the resource the broken widgets use.

## The fix

For every broken surface, replace the **accent-slot colour resource** with the user's accent via
a *fabricated runtime resource overlay*. This works precisely because the slot is a colour
resource; the theme attribute itself lives in a style bag and cannot be rewritten this way.

Implemented in `app/src/main/java/io/github/kvmy666/autoexpand/CouiAccentFix.kt`, run from the
**app process** (`App.onCreate`), because:

* it needs `su`, which only the app process reliably has;
* `OverlayManagerService.commit()` enforces root-or-shell, otherwise it throws
  `SecurityException: commit failed`.

Slots currently covered:

```
com.oneplus.calculator:color/fold_button_equals_bg_color
com.oneplus.calculator:color/event_down_fold_button_img_equals
com.oneplus.calculator:color/dialog_cancel
com.android.settings:color/switch_outer_circle_color
com.oplus.wirelesssettings:color/switch_outer_circle_color
com.oneplus.filemanager:color/switch_outer_circle_color
```

The accent comes from `Settings.Secure.theme_customization_overlay_packages` →
`android.theme.customization.accent_color` (read through `su`), and the fix only fires when
`color_source` is a wallpaper-derived value — so **Featured presets are never touched**. When the
user leaves the Custom path, previously created overlays are disabled automatically
(self-healing), and overlays from an older accent are swept too.

Opt-out: prefs key `system_color_fix_enabled` (`"0"` disables). Absent = enabled — this is a
fix, and an overlay is trivially reversible.

### 😱 The gotcha that cost the most time

**`cmd overlay fabricate` must NOT have its stdout redirected to a file.**

```sh
cmd overlay fabricate --target pkg --name n pkg:color/x 0x1c 0xffeeeeee >> log 2>&1   # FAILS
cmd overlay fabricate --target pkg --name n pkg:color/x 0x1c 0xffeeeeee                # works
```

With a redirected stdout every call fails with
`cmd: Failure calling service overlay: Failed transaction (2147483646)` — while
`cmd overlay list`/`dump` keep working from the very same shell. The command ships the overlay
through a file descriptor, so a redirected stdout breaks the binder call. `enable`/`lookup` fail
the same way. Pipes are fine — hence `RootShell` capturing output via pipes works.

Other traps:

* Fabricated overlays are owned by the **caller** (`com.android.shell`), so the real name is
  `com.android.shell:<name>`, never `<target>:<name>`.
* `0x1c` is the resource type id for `ARGB8`; the value must be `0xAARRGGBB`.
* A non-root caller gets `SecurityException: commit failed` — check the shell's uid first.

## Verification (on device)

1. `cmd overlay lookup com.oneplus.calculator com.oneplus.calculator:color/fold_button_equals_bg_color`
   → `#ffffffff` before, `#fff33586` after.
2. Calculator `=` key: white → **pink**.
3. `…settings:color/switch_outer_circle_color` → `#fff33586`; Wi-Fi ON toggle: white → **pink**.
4. Logcat: `DIAG: CouiAccent applied 6/6 slots for #fff33586`, overlays
   `com.android.shell:aeCoui_*` enabled.

## Extending to another app

1. Pull the APK and find its accent slots — colour resources whose value is
   `?attr/couiColorPrimary`: `aapt2 dump resources app.apk`, then look for a `color/` resource
   whose first value line is `?attr/couiColorPrimary`.
2. If there are none, the app paints the attribute inline in a drawable/selector; trace from the
   widget's layout (`aapt2 dump xmltree --file res/XX.xml app.apk`) down to the
   `android:background` / `?attr` leaf and find the colour resource it lands on.
3. Add `"<pkg>:color/<name>"` to `CouiAccentFix.SLOTS`.

Do **not** override on-accent *foreground* colours (e.g. `coui_btn_check_inner_color_on_normal`,
`status_icon_chip_checked_text_color`) — those are drawn on top of the accent and must stay
white.
