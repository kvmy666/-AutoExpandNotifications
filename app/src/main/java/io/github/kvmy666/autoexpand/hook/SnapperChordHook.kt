package io.github.kvmy666.autoexpand.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Snapper hardware chord (Power + Volume-Down) — launches the screenshot/snapper service
 * from system_server before the native KeyCombinationManager fires its screenshot rule.
 *
 * SAFETY CONTRACT: the entire hook body runs in try/catch(Throwable). On ANY exception we
 * log and let the original method proceed — system_server is never blocked by our code.
 *
 * PHASE 18 LOGCAT EVIDENCE:
 *   interceptKeyBeforeQueueing fires on tid=168 for POWER key=26 then VOLUME_DOWN key=25
 *   (same thread, sequential). handleKeyGestureEvent fires on tid=41 AFTER both keys are
 *   down. Its args[0] is a KeyGestureEvent object (NOT an int) — earlier attempts that cast
 *   args[0] as? Int silently skipped the block. Two calls: action=1 (start) then action=2
 *   (complete); both must be blocked. Both hooks confirmed on PhoneWindowManager.
 *
 * TWO-HOOK STRATEGY:
 *   Hook 1 (interceptKeyBeforeQueueing):
 *     - Power-first press order: VOLUME_DOWN arrives with Power held → consume VOLUME_DOWN
 *       (param.result=0) → launch Snapper → chordTriggered=true
 *     - POWER UP with chordTriggered=true → param.setResult(0) → no screen-off
 *   Hook 2 (handleKeyGestureEvent):
 *     - Volume-first press order: gesture fires before interceptKeyBQ catches it
 *     - Check args[0].toString() for "SCREENSHOT_CHORD" (confirmed string)
 *     - param.setResult(null) on BOTH action=1 and action=2
 *     - If !chordTriggered: launch Snapper here instead
 */
class SnapperChordHook(private val prefs: PrefsBridge) {

    @Volatile private var chordTriggered  = false
    @Volatile private var powerDownTime   = 0L
    @Volatile private var volumeDownTime  = 0L

    private companion object {
        const val CHORD_WINDOW_MS = 150L
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Phase 18 confirmed both hooks work on PhoneWindowManager.
        // Try it first; fall back to OplusPhoneWindowManager if not found.
        val candidates = listOf(
            "com.android.server.policy.PhoneWindowManager",
            "com.oplus.server.policy.OplusPhoneWindowManager"
        )

        // ── Hook 1: interceptKeyBeforeQueueing ─────────────────────────────────
        // Handles Power-first press order and POWER UP screen-off prevention.
        for (cls in candidates) {
            try {
                XposedHelpers.findAndHookMethod(
                    cls, lpparam.classLoader,
                    "interceptKeyBeforeQueueing",
                    KeyEvent::class.java, Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (!prefs.isFeatureEnabled("enable_snapper_entirely")) return
                                val ev = param.args[0] as? KeyEvent ?: return
                                when (ev.keyCode) {
                                    KeyEvent.KEYCODE_POWER -> when (ev.action) {
                                        KeyEvent.ACTION_DOWN -> {
                                            powerDownTime  = ev.downTime   // uptimeMillis domain
                                            chordTriggered = false
                                            // Pass through — do NOT consume
                                        }
                                        KeyEvent.ACTION_UP -> {
                                            if (chordTriggered) {
                                                chordTriggered = false
                                                try { XposedHelpers.callMethod(param.thisObject, "cancelPowerKeyLongPress") } catch (_: Throwable) {}
                                                try { XposedHelpers.setBooleanField(param.thisObject, "mPowerKeyDown", false) } catch (_: Throwable) {}
                                                param.setResult(0)
                                                Log.d("Snapper", "POWER_UP consumed — screen stays on")
                                            }
                                            // else: pass through — normal screen-off
                                        }
                                    }
                                    KeyEvent.KEYCODE_VOLUME_DOWN -> when (ev.action) {
                                        KeyEvent.ACTION_DOWN -> {
                                            volumeDownTime = ev.downTime   // always record
                                            if (!prefs.isFeatureEnabled("snapper_hardware_chord_enabled")) return
                                            val gap = ev.downTime - powerDownTime
                                            if (gap > 0L && gap < CHORD_WINDOW_MS) {
                                                chordTriggered = true
                                                // Cancel power long-press NOW — Gemini fires while
                                                // Power is still held, so POWER_UP is too late.
                                                try { XposedHelpers.callMethod(param.thisObject, "cancelPowerKeyLongPress") } catch (_: Throwable) {}
                                                try { XposedHelpers.setBooleanField(param.thisObject, "mPowerKeyHandled", true) } catch (_: Throwable) {}
                                                param.result = 0
                                                val ctx = getCtx(param)
                                                if (ctx != null) {
                                                    try {
                                                        launchSnapper(ctx)
                                                        Log.d("Snapper", "Snapper launched (interceptKeyBQ / Power-first)")
                                                    } catch (e: Throwable) {
                                                        Log.e("Snapper", "launch failed: ${e.message}")
                                                        chordTriggered = false
                                                        param.result = null
                                                    }
                                                } else {
                                                    Log.e("Snapper", "mContext null (interceptKeyBQ)")
                                                    chordTriggered = false
                                                    param.result = null
                                                }
                                            }
                                            // else: gap out of window — pass through normally
                                        }
                                        KeyEvent.ACTION_UP -> {
                                            if (chordTriggered) param.result = 0
                                            // else: pass through
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.e("Snapper", "interceptKeyBQ hook threw: $t")
                                param.result = null
                            }
                        }
                    }
                )
                Log.d("Snapper", "Hook1 (interceptKeyBQ) registered on $cls")
                break
            } catch (e: Throwable) {
                Log.e("Snapper", "Hook1 FAILED on $cls: $e")
            }
        }

        // ── Hook 2: handleKeyGestureEvent ──────────────────────────────────────
        // Handles Volume-first press order AND provides a second layer of defense.
        // args[0] is a KeyGestureEvent object — check toString() for SCREENSHOT_CHORD.
        // Block BOTH action=1 (start) and action=2 (complete) with setResult(null).
        for (cls in candidates) {
            try {
                val clazz = XposedHelpers.findClass(cls, lpparam.classLoader)
                val hooks = XposedBridge.hookAllMethods(clazz, "handleKeyGestureEvent",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (!prefs.isFeatureEnabled("enable_snapper_entirely")) return
                                val gestureEvent = param.args.getOrNull(0) ?: return
                                // args[0] is KeyGestureEvent — use toString() to identify
                                // SCREENSHOT_CHORD (confirmed "KEY_GESTURE_TYPE_SCREENSHOT_CHORD")
                                if (!gestureEvent.toString().contains("SCREENSHOT_CHORD")) return
                                if (!prefs.isFeatureEnabled("snapper_hardware_chord_enabled")) return

                                // Block the native screenshot (both action=1 and action=2)
                                param.setResult(null)
                                Log.d("Snapper", "handleKeyGestureEvent SCREENSHOT_CHORD blocked")

                                // Launch Snapper only on simultaneous press (Vol-first path)
                                // Both keys must have been pressed within CHORD_WINDOW_MS of each other.
                                // volumeDownTime == 0 means Volume was never pressed → single Power press → skip.
                                val bothSimultaneous = volumeDownTime > 0L &&
                                    kotlin.math.abs(powerDownTime - volumeDownTime) < CHORD_WINDOW_MS
                                if (!chordTriggered && bothSimultaneous) {
                                    chordTriggered = true
                                    val ctx = getCtx(param)
                                    if (ctx != null) {
                                        try {
                                            launchSnapper(ctx)
                                            Log.d("Snapper", "Snapper launched (handleKeyGesture / Vol-first)")
                                        } catch (e: Throwable) {
                                            Log.e("Snapper", "launch failed (handleKeyGesture): ${e.message}")
                                            chordTriggered = false
                                        }
                                    } else {
                                        Log.e("Snapper", "mContext null (handleKeyGesture)")
                                        chordTriggered = false
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.e("Snapper", "handleKeyGestureEvent hook threw: $t")
                            }
                        }
                    }
                )
                if (hooks.isNotEmpty()) {
                    Log.d("Snapper", "Hook2 (handleKeyGestureEvent) registered ${hooks.size} methods on $cls")
                    break
                }
            } catch (e: Throwable) {
                Log.e("Snapper", "Hook2 FAILED on $cls: $e")
            }
        }
    }

    private fun getCtx(param: XC_MethodHook.MethodHookParam): Context? = try {
        XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
    } catch (_: Throwable) { null }

    private fun launchSnapper(ctx: Context) {
        ctx.startForegroundService(
            Intent("io.github.kvmy666.autoexpand.ACTION_CAPTURE").apply {
                component = ComponentName(
                    "io.github.kvmy666.autoexpand",
                    "io.github.kvmy666.autoexpand.SnapperService"
                )
            }
        )
    }
}
