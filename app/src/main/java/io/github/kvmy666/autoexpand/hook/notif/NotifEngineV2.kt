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
        /** `Notification.FLAG_GROUP_SUMMARY` — read off the posted notification, so race-free. */
        const val FLAG_GROUP_SUMMARY = 0x00000200

        const val GROUP_MANAGER_CLASS =
            "com.android.systemui.statusbar.notification.collection.render.GroupExpansionManagerImpl"

        /**
         * Row frames that mean *the user asked for this*. Everything else the row calls — most
         * importantly `setHideSensitive` on every keyguard transition — is bookkeeping.
         *
         * `onClick` (OnClickListener) and `onExpandClicked` (OnExpandClickListener) are interface
         * overrides and so survive R8 untouched; `performExpansion` / `setUserExpanded` are the
         * AOSP names for the same gesture on ROMs that route it through the row.
         */
        val USER_INTENT_FRAMES = setOf(
            "onClick", "onExpandClicked", "performExpansion", "setUserExpanded",
        )
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
                // Parents default ON: a closed bundle is the thing the module exists to open.
                // Children default OFF: one-line children are the stock grouped look, and
                // expanding them is a taste call, not a fix.
                groupParentsEnabled  = prefs.isFeatureEnabled("expand_group_parents_enabled") &&
                                       RowApi.groupCapable,
                groupChildrenEnabled = prefs.isOptInEnabled("expand_group_children_enabled") &&
                                       RowApi.groupCapable,
            )
            lsGateEnabled = prefsFacts.lockscreenEnabled && RowApi.lockscreenCapable
            NotifLog.enabled = prefs.isOptInEnabled("notif_debug_logging")
        } catch (_: Throwable) {}
    }

    // ── facts capture ────────────────────────────────────────────────────────

    private fun keyOf(row: Any): String? = try {
        RowApi.sbnOf(row)?.let { XposedHelpers.callMethod(it, "getKey") } as? String
    } catch (_: Throwable) { null }

    private fun capture(row: Any): RowFacts? {
        return try {
            val sbn = RowApi.sbnOf(row)
            val key = (sbn?.let { XposedHelpers.callMethod(it, "getKey") } as? String) ?: return null
            val pkg = sbn.let { XposedHelpers.callMethod(it, "getPackageName") } as? String
            val groupKey = try { XposedHelpers.callMethod(sbn, "getGroupKey") as? String } catch (_: Throwable) { null }
            val isGroupSummary = try {
                val notif = XposedHelpers.callMethod(sbn, "getNotification")
                (XposedHelpers.getIntField(notif, "flags") and FLAG_GROUP_SUMMARY) != 0
            } catch (_: Throwable) { false }
            RowFacts(
                key = key,
                pkg = pkg,
                groupKey = groupKey,
                onKeyguard = RowApi.bool(RowApi.fOnKeyguard, row),
                isHeadsUp = RowApi.bool(RowApi.fIsHeadsUp, row),
                isPinned = RowApi.callBool(RowApi.isPinned, row),
                isChildInGroup = RowApi.callBool(RowApi.isChildInGroup, row),
                isSummaryWithChildren = RowApi.callBool(RowApi.isSummaryWithChildren, row),
                isGroupSummary = isGroupSummary,
                expandedUngated = RowApi.isExpandedUngated(row),
                hasUserChangedExpansion = RowApi.bool(RowApi.fHasUserChangedExpansion, row),
                userExpanded = RowApi.bool(RowApi.fUserExpanded, row),
                groupExpanded = RowApi.callBool(RowApi.isGroupExpanded, row),
                groupUserCollapsed = RowStateStore.isGroupCollapsedByUser(key),
                childCollapseDone = RowStateStore.wasChildCollapsed(key),
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
                NotifLog.d { "v2 $trigger EXPAND key=${facts.key} kg=${facts.onKeyguard} grouped=${facts.isGroupChild} bundle=${facts.isSystemAggregate}" }
            }
            is Decision.ExpandGroup -> {
                applyGroupExpanded(row, facts)
                NotifLog.d { "v2 $trigger EXPAND_GROUP key=${facts.key}" }
            }
            is Decision.Collapse -> {
                setToken(row, false)
                applyCollapsed(row, facts)
                NotifLog.d { "v2 $trigger COLLAPSE_CHILD key=${facts.key}" }
            }
            is Decision.AlreadyExpanded -> {
                // Still needs the token: on keyguard the row only *stays* expanded while the
                // gate keeps rewriting the argument.
                setToken(row, true)
                // An already-open group still has to have its children reconciled: the group
                // may have been opened by the user, or on a later pass than the one that
                // opened it, and the children carry their own expansion state.
                if (facts.isSummaryWithChildren) reconcileChildren(row, trigger)
                NotifLog.d { "v2 $trigger already key=${facts.key} kg=${facts.onKeyguard}" }
            }
            is Decision.Skip -> {
                if (decision.why.clearsLockscreenToken) setToken(row, false)
                // The two group toggles are independent: with parents off but children on, a
                // group the *user* opens still gets its rows expanded.
                if (facts.isSummaryWithChildren && facts.groupExpanded) reconcileChildren(row, trigger)
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

    /**
     * Returns a group child to collapsed.
     *
     * `setUserExpanded(false, allowChildExpansion = false)` is the mirror of what expanded it:
     * the heads-up path uses `setUserExpanded(true, true)`, so clearing `mIsSystemExpanded` alone
     * would not collapse anything — `isExpanded()` reads `mUserExpanded` at its tail. Passing
     * false for the second argument keeps the call away from the group branch, so this touches
     * the child only. It is the same primitive the heads-up code already uses to collapse
     * children, so no new mechanism is introduced.
     *
     * This deliberately leaves `mHasUserChangedExpansion` set, which means the row now behaves
     * exactly as if the user had collapsed it — including expiring when SystemUI resets the row
     * on the next repost. That is the lifetime we want.
     */
    private fun applyCollapsed(row: Any, facts: RowFacts) {
        Attribution.ours {
            try {
                RowApi.setSystemExpanded?.invoke(row, false)
                RowApi.setUserExpanded2?.invoke(row, false, false)
                    ?: RowApi.setUserExpanded1?.invoke(row, false)
            } catch (t: Throwable) {
                NotifLog.d { "v2 collapse failed key=${facts.key}: $t" }
            }
        }
        RowStateStore.onChildCollapsed(facts.key)
    }

    /**
     * Opens a group summary.
     *
     * `setUserExpanded(true, allowChildExpansion = true)` is the *only* primitive used here.
     * Verified in this ROM's bytecode: for a summary it hands off to
     * `GroupExpansionManager.setGroupExpanded(entry, true)` and returns before touching
     * `mUserExpanded`, `mHasUserChangedExpansion` or any height — the same call the expand arrow
     * makes. `setChildrenExpanded` is deliberately not used: it drives the container directly and
     * visibly breaks the grouped layout.
     *
     * SystemUI's own `shouldShowPublic()` guard sits in front of that branch, so a redacted group
     * on the lock screen never opens.
     */
    private fun applyGroupExpanded(row: Any, facts: RowFacts) {
        Attribution.ours {
            try {
                RowApi.setUserExpanded2?.invoke(row, true, true)
            } catch (t: Throwable) {
                NotifLog.d { "v2 group apply failed key=${facts.key}: $t" }
            }
        }
        RowStateStore.onApplied(facts.key)
        reconcileChildren(row, "group")
    }

    /**
     * Runs the policy over the rows inside an open group.
     *
     * Children are not reachable from the engine's own triggers once the group opens — nothing
     * re-fires on them — so the summary drives them. With the children toggle off every one of
     * these ends in `skip=GroupChild` and nothing is written.
     */
    private fun reconcileChildren(summary: Any, trigger: String) {
        if (!prefsFacts.groupChildrenEnabled) return
        for (child in RowApi.attachedChildrenOf(summary)) {
            try { reconcile(child, "$trigger/child") } catch (_: Throwable) {}
        }
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
        NotifLog.i("v2 engine installing (lsGate=$lsGateEnabled groupCapable=${RowApi.groupCapable} " +
                   "parents=${prefsFacts.groupParentsEnabled} children=${prefsFacts.groupChildrenEnabled})")

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
                            when (decision) {
                                // Both are left to the cold triggers. Opening a group needs a
                                // different primitive, and rewriting this argument on a summary
                                // would set mIsSystemExpanded on it — expanding its own content
                                // rather than the group, which is what tears the grouped look
                                // apart. A collapse has nothing to rewrite: the argument is
                                // already false.
                                is Decision.ExpandGroup, is Decision.Collapse -> Unit
                                is Decision.Expand, is Decision.AlreadyExpanded -> {
                                    // The SBN flag, not the view's: a summary whose children have
                                    // not attached yet still must not have its own content
                                    // expanded, and the view flag is false on exactly that pass.
                                    if (facts.isGroupSummary) return
                                    setToken(row, true)
                                    param.args[0] = true
                                    RowStateStore.onApplied(facts.key)
                                    NotifLog.d { "v2 setSystemExpanded rewritten key=${facts.key} kg=${facts.onKeyguard}" }
                                }
                                is Decision.Skip -> setToken(row, false)
                            }
                        } catch (_: Throwable) {}
                    }

                    /**
                     * The group-parent trigger. Measured on the legacy engine and unchanged here:
                     * `setSystemExpanded(false)` fires on the group parent every time the shade or
                     * lock screen renders it, which makes it the one reliable per-open hook for
                     * summaries. Running in `after` keeps the group write clear of SystemUI's own
                     * in-flight expansion bookkeeping.
                     */
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (Attribution.isOurs()) return
                            if (!prefsFacts.groupParentsEnabled && !prefsFacts.groupChildrenEnabled) return
                            val row = param.thisObject
                            if (!RowApi.callBool(RowApi.isSummaryWithChildren, row)) return
                            reconcile(row, "setSystemExpanded")
                        } catch (_: Throwable) {}
                    }
                })
            } catch (t: Throwable) { NotifLog.e("v2 setSystemExpanded hook failed: $t") }
        }

        installGroupCollapseMemory(lpparam)

        // ── cold triggers ───────────────────────────────────────────────────
        // Deliberately no per-frame driver. These are the points where the row's situation
        // actually changes; `onVisibilityAggregated` is excluded because it binds 0 methods on
        // this build (inherited, not declared) and would fail silently.
        for (name in listOf("onAttachedToWindow", "setOnKeyguard", "onNotificationUpdated")) {
            try {
                val hooks = XposedBridge.hookAllMethods(cls, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (name == "onNotificationUpdated") {
                                refreshPrefs()
                                // New content ends a collapse, exactly as the checkpoint's own
                                // acceptance test says ("stays collapsed until the app updates
                                // it"). Groups need this spelled out because, unlike a single
                                // row, SystemUI keeps no expansion state of its own to reset.
                                keyOf(param.thisObject)?.let { RowStateStore.onNotificationUpdated(it) }
                            }
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
                        // Summaries are exempt from the wipe accounting. `resetUserExpansion`
                        // clears `mUserExpanded`, which is where a *single* row's expansion
                        // lives; a group's lives in GroupExpansionManager and is untouched here.
                        // Counting these as SystemUI fighting us made two quick shade cycles trip
                        // the back-off and stop the parent opening for the whole cooldown.
                        if (!RowApi.callBool(RowApi.isSummaryWithChildren, row)) {
                            RowStateStore.onExpansionWiped(key)
                        }
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

    /**
     * Remembers that the user closed a group, so the parent toggle stops reopening it.
     *
     * `GroupExpansionManagerImpl.setGroupExpanded` is the single chokepoint for group state —
     * every path in this build funnels through it. Its full caller census, read out of the
     * device's own dex:
     *
     * | caller | expanded | meaning |
     * |---|---|---|
     * | `ExpandableNotificationRow$1.onClick` | toggled | **the expand arrow — the user** |
     * | `GroupExpansionManagerImpl.collapseGroups` | false | bulk reset when the shade closes |
     * | `GroupExpansionManagerImpl$$…Lambda0.onBeforeRenderList` | false | summary left the list |
     * | `NotificationRemoteInputManager.activateRemoteInput` | true | inline reply opened it |
     * | `StatusBarRemoteInputCallback$$…Lambda0.run` | true | inline reply, deferred |
     *
     * Note what is *not* in that list: `ExpandableNotificationRow.setUserExpanded`. On stock
     * AOSP the arrow reaches the group through it, but this ROM's listener calls the manager
     * directly — so hooking the row's setter observes nothing, and this is the only place the
     * distinction can be made.
     *
     * The two collapse paths that are *not* the user are bulk resets originating inside the
     * manager, so a frame from the row class is what separates a tap from a shade close. That
     * test is used rather than the anonymous class name because `$1` and `$$ExternalSynthetic…`
     * are R8 output and change between builds, while the row's own class name is stable — it is
     * already the module's one hard dependency.
     */
    private fun installGroupCollapseMemory(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cls = try {
            lpparam.classLoader.loadClass(GROUP_MANAGER_CLASS)
        } catch (t: Throwable) {
            NotifLog.e("v2 group collapse memory: $GROUP_MANAGER_CLASS not found ($t)")
            return
        }
        try {
            val hooks = XposedBridge.hookAllMethods(cls, "setGroupExpanded", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (Attribution.isOurs()) return
                        val entry = param.args.getOrNull(0) ?: return
                        val expanded = param.args.getOrNull(1) == true
                        // A bulk collapse is SystemUI tidying up, not a decision — ignore it,
                        // or the toggle would switch itself off the first time the shade closes.
                        val via = if (expanded) "expand" else initiator() ?: run {
                            NotifLog.d { "v2 group system collapse ignored" }
                            return
                        }
                        val key = XposedHelpers.callMethod(entry, "getKey") as? String ?: return
                        RowStateStore.onGroupUserExpansion(key, expanded)
                        NotifLog.d { "v2 group user expanded=$expanded via=$via key=$key" }
                    } catch (_: Throwable) {}
                }
            })
            if (hooks.isEmpty()) NotifLog.e("v2 setGroupExpanded bound 0 methods")
        } catch (t: Throwable) { NotifLog.e("v2 group collapse memory failed: $t") }
    }

    /**
     * Names the click-path frame that asked for this collapse, or null when it was SystemUI's own
     * bookkeeping.
     *
     * Deliberately **not** "is there a row frame anywhere in the stack" — that reads as a
     * reasonable proxy for "the user" and is wrong in the direction that silently breaks the
     * feature. Measured on device: entering the keyguard calls
     * `ExpandableNotificationRow.setHideSensitive`, which collapses the group. That leaves a row
     * frame on the stack, so the loose test marked every bundle as user-collapsed the first time
     * the screen locked, and the group then stayed shut for good — which is exactly the bug this
     * whole mechanism exists to prevent.
     *
     * So the test is positive rather than negative: the stack must contain a frame that *is* the
     * expand gesture. Those names are stable in a way the surrounding classes are not — `onClick`
     * and `onExpandClicked` are interface overrides, which R8 cannot rename, whereas the classes
     * that hold them (`ExpandableNotificationRow$1`, `…$$ExternalSyntheticLambda0`) are R8 output
     * and change between builds. A frame inside the manager short-circuits first, since
     * `collapseGroups` / `onBeforeRenderList` are bulk resets by definition.
     *
     * Only walked on a collapse, so the stack capture costs nothing. Rejections are logged with
     * their frame, so a ROM that routes the arrow somewhere new is one log line away.
     */
    private fun initiator(): String? {
        for (f in Throwable().stackTrace) {
            val c = f.className
            if (c.startsWith(GROUP_MANAGER_CLASS)) {
                if (f.methodName == "setGroupExpanded") continue   // the hooked frame itself
                return null                                        // SystemUI's own bulk reset
            }
            if (c.startsWith(RowApi.ROW_CLASS) && f.methodName in USER_INTENT_FRAMES)
                return f.methodName
        }
        return null
    }
}
