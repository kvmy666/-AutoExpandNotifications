# AutoExpandNotifications

Notification Tweaks + Screen Snapper + Keyboard Enhancer for OxygenOS

![GitHub release](https://img.shields.io/github/v/release/kvmy666/-AutoExpandNotifications?style=flat-square)
![License](https://img.shields.io/github/license/kvmy666/-AutoExpandNotifications?style=flat-square)
![Android](https://img.shields.io/badge/Android-16%2B-green?style=flat-square)

---

## About

An LSPosed/Xposed module that brings powerful system-level enhancements to OnePlus devices running OxygenOS 16. Auto-expand notifications everywhere, cap heads-up banner height, block accidental popup launches, disable back gesture haptic, ungroup notifications, capture and annotate screen regions with a zero-delay live overlay, and get a powerful clipboard + shortcut toolbar injected into Gboard — all configurable from a clean tabbed settings UI, all 100% local with no network access.

## Screenshots

| Expanded Heads-Up | Notification Shade | Settings |
|---|---|---|
| ![](https://raw.githubusercontent.com/kvmy666/-AutoExpandNotifications/main/screenshots/headsup-expanded.jpg) | ![](https://raw.githubusercontent.com/kvmy666/-AutoExpandNotifications/main/screenshots/notification-shade.jpg) | ![](https://raw.githubusercontent.com/kvmy666/-AutoExpandNotifications/main/screenshots/settings.jpg) |

---

## Features

### Auto-Expand Notifications
Automatically expand all notifications in three contexts:
- **Notification Shade** — full text and action buttons visible the moment you pull down
- **Heads-Up Banners** — incoming banners arrive fully expanded; swipe down to collapse, swipe down again to expand
- **Lock Screen** — expanded notifications by default; can be disabled for privacy

### Heads-Up Max Lines
Caps the number of visible text lines in auto-expanded heads-up banners so long messages don't fill your entire screen. Set your preferred limit in the Notifications tab (default: 5 lines, 0 = unlimited). Action buttons always remain visible.

### Disable Heads-Up Popup
Prevents the mini-window / freeform app launch when swiping down on a heads-up notification. Swiping down instead toggles between expanded and collapsed views.

### Ungroup Notifications
Shows each notification as a separate card instead of grouping them by app. Never miss a message buried inside a collapsed group.

### Mute Back Gesture Haptic
Disables the vibration feedback when using the back swipe gesture, while keeping all other haptic feedback intact.

### App Exclusion List
Choose specific apps whose notifications should NOT be auto-expanded. Exclusions apply across all three notification contexts (shade, heads-up, and lock screen).

---

### Screen Snapper
A zero-delay screen capture and annotation tool built directly into the system. Requires the **Display over other apps** overlay permission.

**Activation**
Three modes selectable in the Snapper tab:
- **Software** — floating edge button on the left or right side of the screen
- **Hardware** — Power + Volume Down chord, intercepted at `system_server` level before `KeyCombinationManager` sees it. The native OxygenOS screenshot is fully suppressed; only Snapper fires
- **Both** — edge button and chord active simultaneously

A **master toggle** at the top of the Snapper tab lets you disable all activation methods instantly without changing individual settings.

**Capture flow**
- The screencap is pre-fetched in the background the moment activation fires — zero delay between press and crop UI
- The crop UI appears on a clean frame with no system chrome visible
- Drag corner handles to select your region; pinch inside the selection to check detail before confirming

**Floating overlay**
- The cropped region appears as a resizable, draggable floating window above all apps
- Pinch to zoom; drag to reposition anywhere on screen
- Zooming is bounds-clamped — you cannot zoom out beyond the original crop size or pan outside the image edges
- Corner bracket button triggers a standard OS screenshot of whatever is currently on screen (separate from Snapper)
- Double-tap to dismiss (configurable)

**Snap History**
- Every saved crop is stored locally in a gallery accessible from the Snapper tab
- Per-snap actions: Float / Pin as overlay · Open in Gallery · Save to Photos · Share · Delete
- Configurable history limit (0 = disabled); pruning removes the actual image files — no silent storage bloat

---

### Keyboard Enhancer (Gboard Toolbar)
Injects a customizable toolbar below Gboard. Each button can be individually enabled or disabled. Toolbar height is adjustable via a slider.

- **Clipboard** — Tap to open a scrollable clipboard history popup. Pin entries with a long-press. History size is configurable.
- **Paste** — Quick one-tap paste without opening the clipboard popup.
- **Select All** — Tap to select the last word; long-press to select all text in the field.
- **Cursor Navigation** — Jump cursor to the start or end of the text field.
- **Text Shortcut** — Tap / long-press to insert preset text snippets.

---

### Settings UI
The settings app uses a bottom navigation bar with four tabs:

- **Notifications** — all notification toggles, heads-up max lines field, and app exclusion list
- **Keyboard** — Gboard toolbar settings, button toggles, shortcut text, and clipboard limit
- **Snapper** — master toggle, activation method, edge button side, double-tap dismiss, history limit, and history viewer
- **Guide** — collapsible explanations for every feature, privacy notice

---

## Privacy

Everything this module does happens entirely on your device. No data is collected, no network requests are made, no analytics are included. No internet permission is declared. All settings and Snap History are stored in the app's local private storage.

---

## Requirements

- OnePlus device running **OxygenOS 16** (Android 16)
- **Root access** (Magisk / KernelSU / APatch)
- **LSPosed** framework (Irena or compatible fork)
- **Zygisk** enabled
- **Display over other apps** permission (required for Screen Snapper)

## Installation

1. Download the latest APK from [Releases](https://github.com/kvmy666/-AutoExpandNotifications/releases)
2. Install the APK
3. Open **LSPosed Manager** → Modules → Enable **AutoExpandNotifications**
4. Ensure **System UI** and **Gboard** are checked in the module scope
5. **Reboot** your device
6. Open the app to configure features
7. For Screen Snapper: grant **Display over other apps** when prompted in the Snapper tab

## Tested On

| Device | OS Version | Status |
|---|---|---|
| OnePlus 15 | OxygenOS 16.0.3.501 (Android 16) | Fully Working |

> **Note:** Built and tested specifically for OxygenOS 16. It may work on other OxygenOS versions or OnePlus devices, but compatibility is not guaranteed.

---

## Changelog

### v2.0.4
- Snapper tab now shows a visible **red warning card** when the overlay permission is missing — tap to open system settings directly
- Master toggle description updates to reflect missing permission; tapping it redirects to the permission screen instead of silently failing
- Permission state re-checks automatically when returning from system settings

### v2.0.3
- All `windowManager.addView()` calls wrapped in try-catch — if the overlay permission is revoked while Snapper is active, the service stops cleanly instead of crashing

### v2.0.2
- **Crop coordinate fix** — selection box now maps exactly to the cropped output; previously the status bar height caused a vertical upward shift
- **Gemini hardening** — `cancelPowerKeyLongPress()` now fires at chord detection time rather than on Power UP, preventing Gemini from activating while Power is still held

### v2.0.1
- Master toggle for Screen Snapper — disables all activation methods (hardware chord and edge button) without changing individual settings

### v2.0.0
- **Screen Snapper** — zero-delay capture, crop UI, floating overlay, pinch-to-zoom, Snap History
- **Heads-Up Max Lines** — configurable line cap for expanded heads-up banners
- **Bottom navigation UI** — full redesign with Notifications / Keyboard / Snapper / Guide tabs
- **Hardware chord interception** — Power + Volume Down intercepted at `system_server` level, native screenshot fully suppressed

### v1.2.x and earlier
- Keyboard Enhancer (Gboard toolbar), clipboard history, ungroup notifications, initial release

---

## FAQ

**Q: Do I need to reboot after changing settings?**
A: Yes, a reboot is required for toggle changes to take effect since hooks are loaded at boot time. The app shows a reminder.

**Q: Will this cause a bootloop?**
A: Every hook is wrapped in a try-catch. If something fails, it fails silently and the system continues normally. Still recommended to have bootloop protection in place.

**Q: The keyboard toolbar doesn't appear after enabling it.**
A: Force-stop Gboard after enabling the toolbar in the settings app, then open any text field.

**Q: The hardware chord triggers Snapper but also takes a native screenshot.**
A: Make sure **Hardware** or **Both** is selected in the Snapper tab and reboot. The hook intercepts the chord at `system_server` before the native handler sees it.

**Q: Screen Snapper doesn't appear when I press the chord / edge button.**
A: Check that the **Display over other apps** permission is granted. The Snapper tab shows a red warning card if it is missing.

**Q: Can I use this with Oxygen Customizer?**
A: Yes, they hook different parts of SystemUI and should not conflict.

## Contributing

Contributions are welcome! If you've tested this on a different OnePlus device or OxygenOS version, please open an issue to report compatibility.

## Contact

- Telegram: [@kvmy1](https://t.me/kvmy1)

## License

This project is licensed under the GPL-3.0 License — see the [LICENSE](LICENSE) file for details.

## Disclaimer

This module modifies SystemUI and Gboard behavior through Xposed hooks. While extensively tested, I am not responsible for any issues that may arise. Always maintain a backup and have bootloop protection in place.
