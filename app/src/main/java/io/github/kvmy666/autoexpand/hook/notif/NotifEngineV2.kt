package io.github.kvmy666.autoexpand.hook.notif

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.kvmy666.autoexpand.hook.PrefsBridge

/**
 * Shade + lock-screen expand engine, v2.
 *
 * Replaces the click-simulation approach with state. Two mechanisms, both evidence-driven:
 *
 *  - **Shade:** rewrite the argument of `setSystemExpanded(false)` to `true` for eligible rows.
 *    SystemUI then performs the write itself, at its own correct moment, with its own height
 *    plumbing — no `performClick` toggle to race, no `post{}` frame of collapsed paint.
 *
 *  - **Lock screen:** rewrite the `allowOnKeyguard` argument of `isExpanded(boolean)`.
 *    Measured on device: `isExpanded(false)` is false on keyguard even when `mUserExpanded` is
 *    true, while `isExpanded(true)` is honest and `getIntrinsicHeight()` tracks it exactly
 *    (169px collapsed ↔ 245px expanded). So flipping the argument is sufficient, and SystemUI
 *    still computes every dimension. Crucially we rewrite the *argument*, never the return
 *    value: `shouldShowPublic()` is evaluated before the keyguard gate, so redaction survives,
 *    and the `mUserExpanded` tail below the gate survives too, so a manual collapse still wins
 *    and the arrow's own `!isExpanded(...)` toggle computes correctly.
 *
 * Heads-up and the swipe-to-toggle behaviour are not touched by this class at all.
 */
class NotifEngineV2(private val prefs: PrefsBridge) {

    private companion object {
        /** Per-row token telling the hot `isExpanded` gate that this row may ignore the keyguard. */
        const val TOKEN = "aeV2LsAllow"
    }

    @Volatile private var prefsFacts = PrefsFacts(true, true, emptySet())
    @Volatile private var lsGateEnabled = false
    @Volatile private var installed = false

    // ── prefs, refreshed on cold events only ─────────────────────────────────

    private fun refreshPrefs() {
        try {
            prefsFacts = PrefsFacts(
                shadeEnabled      = prefs.isFeatureEnabled("expand_shade_enabled"),
                lockscreenEnabled = prefs.isFeatureEnabled("expand_lockscreen_enabled"),
                excludedApps      = prefs.getExcludedApps(),
            )
            lsGateEnabled = prefsFacts.lockscreenEnabled && RowApi.lockscreenCapable
            NotifLog.enabled = prefs.isOptInEnabled("notif_debug_logging")
        } catch (_: Throwable) {}
    }

    // ── facts capture ────────────────────────────────────────────────────────

    private fun capture(row: Any): RowFacts? {
        return try {
            val sbn = RowApi.sbnOf(row)
            val key = (sbn?.let { XposedHelpers.callMethod(it, "getKey") } as? String) ?: return null
            val pkg = sbn.let { XposedHelpers.callMethod(it, "getPackageName") } as? String
            val groupKey = try { XposedHelpers.callMethod(sbn, "getGroupKey") as? String } catch (_: Throwable) { null }
            RowFacts(
                key = key,
                pkg = pkg,
                groupKey = groupKey,
                onKeyguard = RowApi.bool(RowApi.fOnKeyguard, row),
                isHeadsUp = RowApi.bool(RowApi.fIsHeadsUp, row),
                isPinned = RowApi.callBool(RowApi.isPinned, row),
                isChildInGroup = RowApi.callBool(RowApi.isChildInGroup, row),
                isSummaryWithChildren = RowApi.callBool(RowApi.isSummaryWithChildren, row),
                expandedUngated = RowApi.isExpandedUngated(row),
                hasUserChangedExpansion = RowApi.bool(RowApi.fHasUserChangedExpansion, row),
                userExpanded = RowApi.bool(RowApi.fUserExpanded, row),
            )
        } catch (_: Throwable) { null }
    }

    private fun setToken(row: Any, allow: Boolean) =
        XposedHelpers.setAdditionalInstanceField(row, TOKEN, allow)

    // ── the single writer ────────────────────────────────────────────────────

    /**
     * Reconciles one row against the policy. Idempotent by construction: the state is read
     * synchronously here and the primitive is a setter, not a toggle, so calling this on every
     * cold trigger is indistinguishable from calling it once. That is what removes the need for
     * the old one-shot flag, its two timestamps and the 300 ms quiet-gap heuristic.
     */
    private fun reconcile(row: Any, trigger: String) {
        if (Attribution.isOurs()) return
        val facts = capture(row) ?: return
        val decision = ExpandPolicy.decide(facts, prefsFacts, RowStateStore.isBackedOff(facts.key))

        when (decision) {
            is Decision.Expand -> {
                setToken(row, true)
                applyExpanded(row, facts)
                NotifLog.d { "v2 $trigger EXPAND key=${facts.key} kg=${facts.onKeyguard} aggChild=${facts.isSystemAggregateChild}" }
            }
            is Decision.AlreadyExpanded -> {
                // Still needs the token: on keyguard the row only *stays* expanded while the
                // gate keeps rewriting the argument.
                setToken(row, true)
                NotifLog.d { "v2 $trigger already key=${facts.key} kg=${facts.onKeyguard}" }
            }
            is Decision.Skip -> {
                if (decision.why.clearsLockscreenToken) setToken(row, false)
                NotifLog.d { "v2 $trigger skip=${decision.why} key=${facts.key}" }
            }
        }
    }

    private fun applyExpanded(row: Any, facts: RowFacts) {
        Attribution.ours {
            try {
                RowApi.setSystemExpanded?.invoke(row, true)
                    ?: RowApi.setUserExpanded2?.invoke(row, true, true)
                    ?: RowApi.setUserExpanded1?.invoke(row, true)
            } catch (t: Throwable) {
                NotifLog.d { "v2 apply failed key=${facts.key}: $t" }
            }
        }
        RowStateStore.onApplied(facts.key)
    }

    // ── install ──────────────────────────────────────────────────────────────

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true
        if (!RowApi.bind(lpparam.classLoader)) {
            NotifLog.e("v2 engine NOT installed — required capabilities missing")
            return
        }
        refreshPrefs()
        val cls = RowApi.rowClass ?: return
        NotifLog.i("v2 engine installing (lsGate=$lsGateEnabled)")

        // ── the lock-screen gate ────────────────────────────────────────────
        // Hottest hook in the engine: `isExpanded` runs from measure and from the stack
        // scroll algorithm. Ordered so the common case (shade traffic) exits after two
        // cheap checks and never touches the per-row token map.
        try {
            XposedBridge.hookMethod(RowApi.isExpandedArg, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (!lsGateEnabled) return
                        if (param.args[0] == true) return
                        val row = param.thisObject
                        if (!RowApi.bool(RowApi.fOnKeyguard, row)) return
                        if (XposedHelpers.getAdditionalInstanceField(row, TOKEN) != true) return
                        param.args[0] = true
                    } catch (_: Throwable) {}
                }
            })
        } catch (t: Throwable) { NotifLog.e("v2 lockscreen gate failed: $t") }

        // ── the shade write ─────────────────────────────────────────────────
        // Rewriting the argument rather than calling the setter ourselves means SystemUI does
        // the write at its own moment with its own notifyHeightChanged plumbing, and there is
        // no write-after-write ping-pong with the caller that just passed false.
        RowApi.setSystemExpanded?.let { m ->
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (Attribution.isOurs()) return
                            if (param.args[0] == true) return
                            val row = param.thisObject
                            val facts = capture(row) ?: return
                            val decision = ExpandPolicy.decide(
                                facts, prefsFacts, RowStateStore.isBackedOff(facts.key)
                            )
                            if (decision is Decision.Expand || decision is Decision.AlreadyExpanded) {
                                setToken(row, true)
                                param.args[0] = true
                                RowStateStore.onApplied(facts.key)
                                NotifLog.d { "v2 setSystemExpanded rewritten key=${facts.key} kg=${facts.onKeyguard}" }
                            } else {
                                setToken(row, false)
                            }
                        } catch (_: Throwable) {}
                    }
                })
            } catch (t: Throwable) { NotifLog.e("v2 setSystemExpanded hook failed: $t") }
        }

        // ── cold triggers ───────────────────────────────────────────────────
        // Deliberately no per-frame driver. These are the points where the row's situation
        // actually changes; `onVisibilityAggregated` is excluded because it binds 0 methods on
        // this build (inherited, not declared) and would fail silently.
        for (name in listOf("onAttachedToWindow", "setOnKeyguard", "onNotificationUpdated")) {
            try {
                val hooks = XposedBridge.hookAllMethods(cls, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (name == "onNotificationUpdated") refreshPrefs()
                            reconcile(param.thisObject, name)
                        } catch (_: Throwable) {}
                    }
                })
                if (hooks.isEmpty()) NotifLog.e("v2 trigger $name bound 0 methods")
            } catch (t: Throwable) { NotifLog.e("v2 trigger $name failed: $t") }
        }

        // ── anti-thrash ─────────────────────────────────────────────────────
        // Measured: SystemUI calls resetUserExpansion() on the lock screen and wipes our
        // expansion. Re-apply once; if it is wiped again immediately, back off rather than
        // oscillate (the old engine produced five expands and a visible height flicker).
        try {
            XposedBridge.hookAllMethods(cls, "resetUserExpansion", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (Attribution.isOurs()) return
                        val row = param.thisObject
                        val key = (RowApi.sbnOf(row)?.let { XposedHelpers.callMethod(it, "getKey") } as? String)
                            ?: return
                        RowStateStore.onExpansionWiped(key)
                        if (RowStateStore.isBackedOff(key)) {
                            // Stop writing, but deliberately leave the token alone: dropping it
                            // would make the keyguard gate collapse a row that is already open.
                            NotifLog.d { "v2 backing off after repeated wipe key=$key" }
                            return
                        }
                        reconcile(row, "resetUserExpansion")
                    } catch (_: Throwable) {}
                }
            })
        } catch (t: Throwable) { NotifLog.e("v2 resetUserExpansion hook failed: $t") }

        NotifLog.i("v2 engine installed")
    }
}
