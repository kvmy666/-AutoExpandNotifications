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
    data class OpenApp(val packageName: String) : ZoneAction()
    object OpenSnapperHistory : ZoneAction()
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

    fun toKey(): String = when (this) {
        is NoAction           -> "no_action"
        is ToggleFlashlight   -> "toggle_flashlight"
        is TogglePowerSaver   -> "toggle_power_saver"
        is ToggleWifi         -> "toggle_wifi"
        is ToggleBluetooth    -> "toggle_bluetooth"
        is ToggleMobileData   -> "toggle_mobile_data"
        is ToggleDnd          -> "toggle_dnd"
        is ToggleAutoRotate   -> "toggle_auto_rotate"
        is OpenApp            -> "open_app"
        is OpenSnapperHistory -> "open_snapper_history"
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
    }

    companion object {
        fun fromKey(key: String, pkg: String = ""): ZoneAction = when (key) {
            "toggle_flashlight"    -> ToggleFlashlight
            "toggle_power_saver"   -> TogglePowerSaver
            "toggle_wifi"          -> ToggleWifi
            "toggle_bluetooth"     -> ToggleBluetooth
            "toggle_mobile_data"   -> ToggleMobileData
            "toggle_dnd"           -> ToggleDnd
            "toggle_auto_rotate"   -> ToggleAutoRotate
            "open_app"             -> OpenApp(pkg)
            "open_snapper_history" -> OpenSnapperHistory
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
            else                   -> NoAction
        }

        // Ordered list for UI display: key → display name
        val ALL: List<Pair<String, String>> = listOf(
            "no_action"            to "No Action",
            "toggle_flashlight"    to "Toggle Flashlight",
            "toggle_wifi"          to "Toggle Wi-Fi",
            "toggle_bluetooth"     to "Toggle Bluetooth",
            "toggle_mobile_data"   to "Toggle Mobile Data",
            "toggle_dnd"           to "Toggle Do Not Disturb",
            "toggle_auto_rotate"   to "Toggle Auto Rotate",
            "toggle_power_saver"   to "Toggle Power Saver",
            "open_app"             to "Open App...",
            "open_snapper_history" to "Open Snapper History",
            "take_screenshot"      to "Take Screenshot",
            "lock_screen"          to "Lock Screen",
            "volume_up"            to "Volume Up",
            "volume_down"          to "Volume Down",
            "volume_mute"          to "Volume Mute/Unmute",
            "show_notifications"   to "Show Notifications",
            "show_quick_settings"  to "Show Quick Settings",
            "ringer_normal"        to "Set Ringer: Normal",
            "ringer_vibrate"       to "Set Ringer: Vibrate Only",
            "ringer_silent"        to "Set Ringer: Silent",
            "cycle_ringer"         to "Cycle Ringer Mode",
        )
    }
}
