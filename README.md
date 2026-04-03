# AutoExpandNotifications

Notification & System Tweaks + Screen Snapper + Keyboard Enhancer for OxygenOS

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

## Features

### Auto-Expand Notifications
Automatically expand all notifications in three contexts:
- **Notification Shade** — full text and action buttons visible the moment you pull down
- **Heads-Up Banners** — incoming banners arrive fully expanded; swipe down to collapse, swipe down again to expand
- **Lock Screen** — expanded notifications by default; can be disabled for privacy

### Heads-Up Max Lines — New in v2.0.0
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

### Master Toggle for Screen Snapper — New in v2.0.1
A single switch at the top of the Snapper tab to fully disable all Snapper activation methods (hardware chord and edge button) without changing individual settings.

---

### Screen Snapper — New in v2.0.0
A zero-delay screen capture and annotation tool built directly into the system.

**Activation**
Three modes selectable in the Snapper tab:
- **Software** — floating edge button on the left or right side of the screen
- **Hardware** — Power + Volume Down chord, intercepted at `system_server` level. The native OxygenOS screenshot is fully suppressed; only Snapper fires
- **Both** — edge button and chord active simultaneously

**Capture flow**
- Snapper pre-fetches the screencap in the background the moment activation fires (zero delay)
- A crop UI appears 100 ms later on a clean frame with no UI chrome visible
- Drag corner handles to select your region, then release to float it

**Floating overlay**
- The cropped region appears as a resizable floating window above all apps
- Pinch to zoom in/out; drag to reposition
- Corner bracket button triggers a standard OS screenshot of what's currently on screen (separate from Snapper)
- Double-tap to dismiss (configurable)

**Snap History**
- Every saved crop is stored locally in a gallery accessible from the Snapper tab
- Per-snap actions: Float / Pin as overlay · Open in Gallery · Save to Photos · Share · Delete
- Configurable history limit; pruning deletes the actual image files (no silent storage bloat)

---

### Keyboard Enhancer (Gboard Toolbar)
Injects a customizable toolbar below Gboard. Each button can be individually enabled or disabled. Toolbar height is adjustable via a slider.

- **Clipboard** — Tap to open a scrollable clipboard history popup. Pin entries with a long-press. History size is configurable.
- **Paste** — Quick one-tap paste without opening the clipboard popup.
- **Select All** — Tap to select the last word; long-press to select all text in the field.
- **Cursor Navigation** — Jump cursor to the start or end of the text field.
- **Text Shortcut** — Tap / long-press to insert preset text snippets.

---

### App UI Overhaul — New in v2.0.0
The settings app has been fully redesigned with a bottom navigation bar:

- **Notifications tab** — all notification toggles including the new heads-up max lines field
- **Keyboard tab** — Gboard toolbar settings and button toggles
- **Snapper tab** — activation method, edge button side, double-tap dismiss, history limit, and history viewer
- **Guide tab** — collapsible explanations for every feature; includes a privacy notice confirming everything runs locally

---

## Privacy

Everything this module does happens entirely on your device. No data is collected, no network requests are made, no analytics are included. All settings and Snap History are stored locally in the app's private storage.

---

## Requirements

- OnePlus device running **OxygenOS 16** (Android 16)
- **Root access** (Magisk / KernelSU / APatch)
- **LSPosed** framework (Irena or compatible fork)
- **Zygisk** enabled

## Installation

1. Download the latest APK from [Releases](https://github.com/kvmy666/-AutoExpandNotifications/releases)
2. Install the APK
3. Open **LSPosed Manager** → Modules → Enable **AutoExpandNotifications**
4. Ensure **System UI** and **Gboard** are checked in the module scope
5. **Reboot** your device
6. Open the app to configure features

## Tested On

| Device | OS Version | Status |
|---|---|---|
| OnePlus 15 | OxygenOS 16.0.3.501 (Android 16) | Fully Working |

> **Note:** This module was built and tested specifically for OxygenOS 16. It may work on other OxygenOS versions or OnePlus devices, but compatibility is not guaranteed. Contributions to support more devices are welcome!

## FAQ

**Q: Do I need to reboot after changing settings?**
A: Yes, a reboot is required for toggle changes to take effect since hooks are loaded at boot time. The app shows a reminder.

**Q: Will this cause a bootloop?**
A: Every hook is wrapped in a try-catch. If something fails, it fails silently and the system continues normally. Still recommended to have bootloop protection in place.

**Q: The keyboard toolbar doesn't appear after enabling it.**
A: Force-stop Gboard after enabling the toolbar in the settings app, then open any text field.

**Q: The hardware chord (Power + Vol Down) triggers Snapper but also takes a native screenshot.**
A: Make sure "Hardware" or "Both" is selected in the Snapper tab. The hook intercepts the chord at `system_server` level and suppresses the native screenshot. A full reboot is required after changing the activation method.

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
