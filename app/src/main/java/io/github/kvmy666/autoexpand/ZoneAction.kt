package io.github.kvmy666.autoexpand

sealed class ZoneAction {
    object NoAction           : ZoneAction()
    object ToggleFlashlight   : ZoneAction()
    object TogglePowerSaver   : ZoneAction()
    object ToggleWifi         : ZoneAction()
    object ToggleBluetooth    : ZoneAction()
    object ToggleMobileData   : ZoneAction()
    object ToggleDnd          : ZoneAction()
    object ToggleAutoRotate   : ZoneAction()
    object ToggleAirplaneMode : ZoneAction()
    data class OpenApp(val packageName: String) : ZoneAction()
    object OpenSnapperHistory : ZoneAction()
    object OpenRecents        : ZoneAction()
    object OpenCamera         : ZoneAction()
    object TakeScreenshot     : ZoneAction()
    object LockScreen         : ZoneAction()
    object VolumeUp           : ZoneAction()
    object VolumeDown         : ZoneAction()
    object VolumeMute         : ZoneAction()
    object ShowNotifications  : ZoneAction()
    object ShowQuickSettings  : ZoneAction()
    object RingerNormal       : ZoneAction()
    object RingerVibrate      : ZoneAction()
    object RingerSilent       : ZoneAction()
    object CycleRinger        : ZoneAction()
    object MediaPlayPause     : ZoneAction()
    object MediaNext          : ZoneAction()
    object MediaPrev          : ZoneAction()
    object BrightnessUp       : ZoneAction()
    object BrightnessDown     : ZoneAction()
    data class LaunchShortcut(val packageName: String, val shortcutId: String, val label: String = "") : ZoneAction()

    fun toKey(): String = when (this) {
        is NoAction           -> "no_action"
        is ToggleFlashlight   -> "toggle_flashlight"
        is TogglePowerSaver   -> "toggle_power_saver"
        is ToggleWifi         -> "toggle_wifi"
        is ToggleBluetooth    -> "toggle_bluetooth"
        is ToggleMobileData   -> "toggle_mobile_data"
        is ToggleDnd          -> "toggle_dnd"
        is ToggleAutoRotate   -> "toggle_auto_rotate"
        is ToggleAirplaneMode -> "toggle_airplane_mode"
        is OpenApp            -> "open_app"
        is OpenSnapperHistory -> "open_snapper_history"
        is OpenRecents        -> "open_recents"
        is OpenCamera         -> "open_camera"
        is TakeScreenshot     -> "take_screenshot"
        is LockScreen         -> "lock_screen"
        is VolumeUp           -> "volume_up"
        is VolumeDown         -> "volume_down"
        is VolumeMute         -> "volume_mute"
        is ShowNotifications  -> "show_notifications"
        is ShowQuickSettings  -> "show_quick_settings"
        is RingerNormal       -> "ringer_normal"
        is RingerVibrate      -> "ringer_vibrate"
        is RingerSilent       -> "ringer_silent"
        is CycleRinger        -> "cycle_ringer"
        is MediaPlayPause     -> "media_play_pause"
        is MediaNext          -> "media_next"
        is MediaPrev          -> "media_prev"
        is BrightnessUp       -> "brightness_up"
        is BrightnessDown     -> "brightness_down"
        is LaunchShortcut     -> "launch_shortcut"
    }

    companion object {
        fun fromKey(key: String, pkg: String = "", shortcutData: String = ""): ZoneAction = when (key) {
            "toggle_flashlight"    -> ToggleFlashlight
            "toggle_power_saver"   -> TogglePowerSaver
            "toggle_wifi"          -> ToggleWifi
            "toggle_bluetooth"     -> ToggleBluetooth
            "toggle_mobile_data"   -> ToggleMobileData
            "toggle_dnd"           -> ToggleDnd
            "toggle_auto_rotate"   -> ToggleAutoRotate
            "toggle_airplane_mode" -> ToggleAirplaneMode
            "open_app"             -> OpenApp(pkg)
            "open_snapper_history" -> OpenSnapperHistory
            "open_recents"         -> OpenRecents
            "open_camera"          -> OpenCamera
            "take_screenshot"      -> TakeScreenshot
            "lock_screen"          -> LockScreen
            "volume_up"            -> VolumeUp
            "volume_down"          -> VolumeDown
            "volume_mute"          -> VolumeMute
            "show_notifications"   -> ShowNotifications
            "show_quick_settings"  -> ShowQuickSettings
            "ringer_normal"        -> RingerNormal
            "ringer_vibrate"       -> RingerVibrate
            "ringer_silent"        -> RingerSilent
            "cycle_ringer"         -> CycleRinger
            "media_play_pause"     -> MediaPlayPause
            "media_next"           -> MediaNext
            "media_prev"           -> MediaPrev
            "brightness_up"        -> BrightnessUp
            "brightness_down"      -> BrightnessDown
            "launch_shortcut"      -> {
                // Format: "pkg::id" (legacy) or "pkg::id::label"
                val parts = shortcutData.split("::", limit = 3)
                if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank())
                    LaunchShortcut(parts[0], parts[1], parts.getOrElse(2) { "" })
                else NoAction
            }
            else                   -> NoAction
        }

        // Ordered list for UI display: key → display name.
        // "launch_shortcut" appears twice for discoverability: top of list and near other "open" actions.
        val ALL: List<Pair<String, String>> = listOf(
            "no_action"            to "No Action",
            "launch_shortcut"      to "Anywhere — Launch Shortcut...",
            "toggle_flashlight"    to "Toggle Flashlight",
            "toggle_wifi"          to "Toggle Wi-Fi",
            "toggle_bluetooth"     to "Toggle Bluetooth",
            "toggle_mobile_data"   to "Toggle Mobile Data",
            "toggle_airplane_mode" to "Toggle Airplane Mode",
            "toggle_dnd"           to "Toggle Do Not Disturb",
            "toggle_auto_rotate"   to "Toggle Auto Rotate",
            "toggle_power_saver"   to "Toggle Power Saver",
            "open_app"             to "Open App...",
            "open_snapper_history" to "Open Snapper History",
            "launch_shortcut"      to "Anywhere — Launch Shortcut...",
            "open_camera"          to "Open Camera",
            "open_recents"         to "Open Recents",
            "take_screenshot"      to "Take Screenshot",
            "lock_screen"          to "Lock Screen",
            "volume_up"            to "Volume Up",
            "volume_down"          to "Volume Down",
            "volume_mute"          to "Volume Mute/Unmute",
            "show_notifications"   to "Show Notifications",
            "show_quick_settings"  to "Show Quick Settings",
            "media_play_pause"     to "Media: Play / Pause",
            "media_next"           to "Media: Next Track",
            "media_prev"           to "Media: Previous Track",
            "brightness_up"        to "Brightness Up",
            "brightness_down"      to "Brightness Down",
            "ringer_normal"        to "Set Ringer: Normal",
            "ringer_vibrate"       to "Set Ringer: Vibrate Only",
            "ringer_silent"        to "Set Ringer: Silent",
            "cycle_ringer"         to "Cycle Ringer Mode",
        )
    }
}
