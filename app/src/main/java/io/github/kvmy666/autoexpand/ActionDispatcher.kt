package io.github.kvmy666.autoexpand

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.LauncherApps
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent

object ActionDispatcher {

    private const val TAG = "Zones"
    const val ACTION_PRIVILEGED = "io.github.kvmy666.autoexpand.ZONE_PRIVILEGED_ACTION"
    const val EXTRA_ACTION_KEY  = "zone_action_key"

    // Tracks torch state since CameraManager doesn't expose a simple getter
    private var torchOn = false
    private var torchCameraId: String? = null

    fun dispatch(action: ZoneAction, context: Context) {
        try {
            when (action) {
                is ZoneAction.NoAction          -> return
                is ZoneAction.ToggleFlashlight  -> toggleFlashlight(context)
                is ZoneAction.ToggleDnd         -> toggleDnd(context)
                is ZoneAction.ToggleAutoRotate  -> toggleAutoRotate(context)
                is ZoneAction.OpenApp           -> openApp(context, action.packageName)
                is ZoneAction.OpenSnapperHistory -> openSnapperHistory(context)
                is ZoneAction.TakeScreenshot    -> takeScreenshot()
                is ZoneAction.LockScreen        -> lockScreen()
                is ZoneAction.VolumeUp          -> adjustVolume(context, AudioManager.ADJUST_RAISE)
                is ZoneAction.VolumeDown        -> adjustVolume(context, AudioManager.ADJUST_LOWER)
                is ZoneAction.VolumeMute        -> adjustVolume(context, AudioManager.ADJUST_TOGGLE_MUTE)
                is ZoneAction.ShowNotifications -> expandNotifications(context)
                is ZoneAction.ShowQuickSettings -> expandQuickSettings(context)
                is ZoneAction.RingerNormal      -> setRinger(context, AudioManager.RINGER_MODE_NORMAL)
                is ZoneAction.RingerVibrate     -> setRinger(context, AudioManager.RINGER_MODE_VIBRATE)
                is ZoneAction.RingerSilent      -> setRinger(context, AudioManager.RINGER_MODE_SILENT)
                is ZoneAction.CycleRinger       -> cycleRinger(context)
                is ZoneAction.MediaPlayPause    -> dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                is ZoneAction.MediaNext         -> dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                is ZoneAction.MediaPrev         -> dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                is ZoneAction.BrightnessUp      -> adjustBrightness(context, +1)
                is ZoneAction.BrightnessDown    -> adjustBrightness(context, -1)
                is ZoneAction.OpenRecents       -> openRecents()
                is ZoneAction.OpenCamera        -> openCamera(context)
                is ZoneAction.ToggleAirplaneMode -> toggleAirplaneMode()
                // Privileged actions handled by the SystemUI hook via broadcast
                is ZoneAction.LaunchShortcut    -> launchShortcut(action.packageName, action.shortcutId, context)
                is ZoneAction.ToggleWifi,
                is ZoneAction.ToggleBluetooth,
                is ZoneAction.ToggleMobileData,
                is ZoneAction.TogglePowerSaver  -> sendPrivileged(context, action.toKey())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "dispatch failed for ${action.toKey()}: $t")
        }
    }

    private fun sendPrivileged(context: Context, key: String) {
        context.sendBroadcast(
            Intent(ACTION_PRIVILEGED)
                .setPackage("com.android.systemui")
                .putExtra(EXTRA_ACTION_KEY, key)
        )
    }

    private fun toggleFlashlight(context: Context) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = torchCameraId ?: cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            torchCameraId = id
            val newState = !torchOn
            cm.setTorchMode(id, newState)
            torchOn = newState
        } catch (t: Throwable) {
            Log.e(TAG, "flashlight toggle failed: $t")
        }
    }

    private fun toggleDnd(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
            val current = nm.currentInterruptionFilter
            val next = if (current == android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                android.app.NotificationManager.INTERRUPTION_FILTER_NONE
            else
                android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            nm.setInterruptionFilter(next)
        } catch (t: Throwable) {
            Log.e(TAG, "DND toggle failed: $t")
        }
    }

    private fun toggleAutoRotate(context: Context) {
        try {
            if (!Settings.System.canWrite(context)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
            val current = Settings.System.getInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION, 1
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (current == 1) 0 else 1
            )
        } catch (t: Throwable) {
            Log.e(TAG, "auto rotate toggle failed: $t")
        }
    }

    private fun openApp(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "openApp failed for $packageName: $t")
        }
    }

    private fun openSnapperHistory(context: Context) {
        try {
            context.startActivity(
                Intent(context, SnapHistoryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "openSnapperHistory failed: $t")
        }
    }

    private fun takeScreenshot() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 120"))
        } catch (t: Throwable) {
            Log.e(TAG, "takeScreenshot failed: $t")
        }
    }

    private fun lockScreen() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 223"))
        } catch (t: Throwable) {
            Log.e(TAG, "lockScreen failed: $t")
        }
    }

    private fun adjustVolume(context: Context, direction: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        } catch (t: Throwable) {
            Log.e(TAG, "adjustVolume failed: $t")
        }
    }

    private fun expandNotifications(context: Context) {
        try {
            val sbm = context.getSystemService("statusbar") ?: return
            sbm.javaClass.getMethod("expandNotificationsPanel").invoke(sbm)
        } catch (t: Throwable) {
            Log.e(TAG, "expandNotifications failed: $t")
        }
    }

    private fun expandQuickSettings(context: Context) {
        try {
            val sbm = context.getSystemService("statusbar") ?: return
            sbm.javaClass.getMethod("expandSettingsPanel").invoke(sbm)
        } catch (t: Throwable) {
            Log.e(TAG, "expandQuickSettings failed: $t")
        }
    }

    private fun setRinger(context: Context, mode: Int) {
        try {
            // RINGER_MODE_SILENT requires notification policy access (DND access)
            if (mode == AudioManager.RINGER_MODE_SILENT) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
                if (!nm.isNotificationPolicyAccessGranted) {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    return
                }
            }
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = mode
        } catch (t: Throwable) {
            Log.e(TAG, "setRinger failed: $t")
        }
    }

    private fun launchShortcut(packageName: String, shortcutId: String, context: Context) {
        if (packageName.isBlank() || shortcutId.isBlank()) return
        try {
            if (packageName == "com.absinthe.anywhere_") {
                // Deep links don't need root — fire directly from SystemUI context.
                // Using su am start broke on KernelSU (su not on SystemUI's PATH).
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("anywhere://open?sid=$shortcutId"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                val la = context.getSystemService(LauncherApps::class.java)
                la.startShortcut(packageName, shortcutId, null, null, Process.myUserHandle())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "launchShortcut $packageName/$shortcutId failed: $t")
        }
    }

    private fun dispatchMediaKey(context: Context, keyCode: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP,   keyCode))
        } catch (t: Throwable) {
            Log.e(TAG, "mediaKey $keyCode failed: $t")
        }
    }

    private fun openRecents() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 187"))
        } catch (t: Throwable) {
            Log.e(TAG, "openRecents failed: $t")
        }
    }

    private fun openCamera(context: Context) {
        try {
            context.startActivity(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "openCamera failed: $t")
        }
    }

    private fun toggleAirplaneMode() {
        try {
            val get = Runtime.getRuntime().exec(arrayOf("su", "-c", "settings get global airplane_mode_on"))
            val current = get.inputStream.bufferedReader().readLine()?.trim() ?: "0"
            get.waitFor(); get.destroy()
            val next = if (current == "1") "0" else "1"
            val cmd = "settings put global airplane_mode_on $next && " +
                      "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state ${next == "1"}"
            val set = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            set.waitFor(); set.destroy()
        } catch (t: Throwable) {
            Log.e(TAG, "toggleAirplaneMode failed: $t")
        }
    }

    // Adjust screen brightness in 12 steps (0..255). Direction: +1 up, -1 down.
    // Switches off auto-brightness so the change actually sticks.
    private fun adjustBrightness(context: Context, direction: Int) {
        try {
            if (!Settings.System.canWrite(context)) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }
            val cr = context.contentResolver
            try {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            } catch (_: Throwable) {}
            val current = try {
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Throwable) { 128 }
            val step = 255 / 12
            val next = (current + direction * step).coerceIn(1, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, next)
        } catch (t: Throwable) {
            Log.e(TAG, "adjustBrightness failed: $t")
        }
    }

    private fun cycleRinger(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val next = when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL  -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else                             -> AudioManager.RINGER_MODE_NORMAL
            }
            setRinger(context, next)
        } catch (t: Throwable) {
            Log.e(TAG, "cycleRinger failed: $t")
        }
    }
}
