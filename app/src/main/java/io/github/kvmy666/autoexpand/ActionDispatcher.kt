package io.github.kvmy666.autoexpand

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings
import android.util.Log

object ActionDispatcher {

    private const val TAG = "JeezZones"
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
                // Privileged actions handled by the SystemUI hook via broadcast
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
