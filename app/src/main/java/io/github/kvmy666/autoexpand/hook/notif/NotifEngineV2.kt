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

        /**
         * `NotificationContentView.VISIBLE_TYPE_SINGLELINE` — the one-line layout a group child
         * shows while its group is closed. Read out of this device's dex: `calculateVisibleType`
         * returns this constant for any child whose group is closed, *before* it looks at any
         * height at all.
         */
        const val VISIBLE_TYPE_SINGLE_LINE = 3
        const val VISIBLE_TYPE_EXPANDED = 1
        const val VISIBLE_TYPE_HEADSUP = 2

        const val GROUP_MANAGER_CLASS =
            "com.android.systemui.statusbar.notification.collection.render.GroupExpansionManagerImpl"

        /** How long a package keeps its "this app currently has a group summary" status. */
        const val SUMMARY_TTL_MS = 120_000L

        /** Only content this recent may open a grouped heads-up. */
        const val HUD_FRESH_MS = 60_000L

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
    /** Newest non-summary notification key seen on an update; Engine 2 only arrow-expands this HUD row. */
    @Volatile private var newestChildKey: String? = null
    /** The entry for [newestChildKey]; substitutes the fresh chat when the HUD picks a stale sibling. */
    @Volatile private var newestChildEntry: Any? = null
    /** Key of the row currently in the heads-up window; group expansion waits until it clears. */
    @Volatile private var hudKey: String? = null
    /** Key allowed into the HUD but not yet in setHeadsUp(true); collapse waits for it. */
    @Volatile private var hudPendingKey: String? = null
    @Volatile private var hudPendingAt = 0L
    /** HUD rows we expanded; they are collapsed again when the HUD ends. */
    private val hudExpandedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Message timestamp of [newestChildKey] — update order does not decide newest. */
    @Volatile private var newestChildWhen = 0L
    /** Resolved `GroupExpansionManagerImpl` name; also used to read the initiator stack. */
    @Volatile private var groupManagerName: String? = null
    /** Packages that currently have a live group summary, with the last time one was seen. */
    private val groupSummaryPkgs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** HUD freshness guard state is derived per entry from Notification.when; no package map. */

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

    private fun now() = android.os.SystemClock.uptimeMillis()

    /** Triggers that fire while a row is being re-attached/re-parented, not from new content. */
    private fun isColdTrigger(trigger: String) =
        trigger == "onAttachedToWindow" || trigger == "onNotificationUpdated" || trigger == "setOnKeyguard"

    private fun contentWhenOf(row: Any): Long = try {
        val sbn = RowApi.sbnOf(row) ?: return 0L
        val notification = XposedHelpers.callMethod(sbn, "getNotification")
        val whenMillis = XposedHelpers.getLongField(notification, "when")
        if (whenMillis > 0L) whenMillis
        else (XposedHelpers.callMethod(sbn, "getPostTime") as? Long ?: 0L)
    } catch (_: Throwable) { 0L }

    private fun capture(row: Any): RowFacts? {
        return try {
            val sbn = RowApi.sbnOf(row)
            val key = (sbn?.let { XposedHelpers.callMethod(it, "getKey") } as? String) ?: return null
            val pkg = sbn.let { XposedHelpers.callMethod(it, "getPackageName") } as? String
            val groupKey = try { XposedHelpers.callMethod(sbn, "getGroupKey") as? String } catch (_: Throwable) { null }
            val pkgHasLiveSummary = pkg?.let {
                val at = groupSummaryPkgs[it]
                at != null && now() - at < SUMMARY_TTL_MS
            } ?: false
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
                pipelineGrouped = RowApi.pipelineGroupedOf(row),
                pkgHasLiveSummary = pkgHasLiveSummary,
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
        if (facts.isGroupSummary && facts.pkg != null) {
            groupSummaryPkgs[facts.pkg] = now()
        }
        if (trigger == "onNotificationUpdated" && !facts.isGroupSummary &&
            !facts.key.startsWith(RowFacts.INTERNAL_KEY_PREFIX)) {
            val contentWhen = contentWhenOf(row)
            if (newestChildKey == null || contentWhen > newestChildWhen) {
                newestChildWhen = contentWhen
                newestChildKey = facts.key
                newestChildEntry = RowApi.entryOf(row)
            }
        }
        // Cold triggers must not re-expand an older row. SystemUI re-attaches/reposts rows
        // during a group refresh, and a row the system momentarily leaves ungrouped would
        // otherwise be treated as a fresh single and expanded behind the new notification.
        // Only the key that most recently received an update may expand on a cold trigger.
        if (isColdTrigger(trigger) &&
            !facts.isGroupSummary && facts.key != newestChildKey) {
            NotifLog.d { "v2 $trigger defer not-newest key=${facts.key} newest=$newestChildKey" }
            return
        }
        if (isColdTrigger(trigger) &&
            facts.belongsToGroup && !facts.isGroupChild && !facts.isGroupSummary) {
            NotifLog.d { "v2 $trigger defer group-forming key=${facts.key} kg=${facts.onKeyguard}" }
            return
        }
        val decision = ExpandPolicy.decide(facts, prefsFacts, RowStateStore.isBackedOff(facts.key))

        when (decision) {
            is Decision.Expand -> {
                setToken(row, true)
                applyExpanded(row, facts)
                NotifLog.d { "v2 $trigger EXPAND key=${facts.key} kg=${facts.onKeyguard} grouped=${facts.isGroupChild} bundle=${facts.isSystemAggregate}" }
            }
            is Decision.ExpandGroup -> {
                if (hudKey != null) {
                    NotifLog.d { "v2 $trigger defer EXPAND_GROUP hud key=${facts.key}" }
                    return
                }
                applyGroupExpanded(row, facts)
                NotifLog.d { "v2 $trigger EXPAND_GROUP key=${facts.key}" }
            }
            is Decision.Collapse -> {
                val pending = facts.key == hudPendingKey && now() - hudPendingAt < 5_000L
                if (facts.isHeadsUp || facts.isPinned || pending) {
                    NotifLog.d { "v2 $trigger defer COLLAPSE hud key=${facts.key}" }
                    return
                }
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
                // A skip is the answer to "why is this row not expanded", so it carries the
                // state the reason was derived from — otherwise diagnosing a wrong skip means
                // turning the (hot) probe on and reproducing it a second time.
                NotifLog.d {
                    "v2 $trigger skip=${decision.why} key=${facts.key} kg=${facts.onKeyguard} " +
                    "grpExp=${facts.groupExpanded} exp=${facts.expandedUngated} " +
                    "pipeGrp=${facts.pipelineGrouped} grpKey=${facts.belongsToGroup}"
                }
            }
        }

        // Read the group state again rather than from `facts`: the decision above may have just
        // opened this group, and the repair below is about the state the children are in *now*.
        if (facts.isSummaryWithChildren && RowApi.callBool(RowApi.isGroupExpanded, row))
            repairChildLayouts(row, trigger)
    }

    /**
     * Puts a child back on the right layout when its group is open but it is still drawing its
     * one-line self.
     *
     * The symptom: rows in an open group rendered as a full-size card containing a single line
     * of "sender: message" text at the top and blank space below — no icon, no timestamp, no
     * expand arrow. Both halves are consistent with their own inputs, which is why it looks so
     * strange: read out of this device's dex, `NotificationContentView.calculateVisibleType()`
     * returns SINGLELINE for any child whose group reads closed, while
     * `ExpandableNotificationRow.getIntrinsicHeight()` sizes a child of an *open* group from
     * its contracted layout. So a child that never re-ran its layout selection after the group
     * opened ends up one line of content inside a card sized for three.
     *
     * The engine opens groups from inside SystemUI's own callbacks, which run during a layout
     * traversal, and a `requestLayout()` issued from there is dropped for the current pass —
     * the most likely way for a child to miss the update. Rather than gamble on timing, this
     * tests the state directly and repairs only what is actually wrong: `selectLayout` on a
     * correct row recomputes the same type and does nothing, so the check runs on every render
     * trigger and the display heals itself on the next frame no matter what caused the stale
     * state.
     *
     * Gated by the children toggle. When children are meant to stay one-line, reselecting the
     * expanded layout is exactly the wrong repair: it turns a correct one-line child into a
     * full card. The repair only applies once the user asked for expanded children.
     */
    private fun repairChildLayouts(summary: Any, trigger: String) {
        if (!prefsFacts.groupChildrenEnabled) return
        if (!RowApi.layoutRepairCapable) return
        Attribution.ours {
            for (child in RowApi.attachedChildrenOf(summary)) {
                try {
                    if (RowApi.visibleTypeOf(child) != VISIBLE_TYPE_SINGLE_LINE) continue
                    // Ask the child itself, not the summary. If the child still reads its group
                    // as closed then one line is the right answer for it and re-selecting would
                    // return the same type on every render — a repair that never converges.
                    if (!RowApi.callBool(RowApi.isGroupExpanded, child)) continue
                    if (!RowApi.reselectLayout(child)) continue
                    NotifLog.d { "v2 $trigger repaired stale single-line child key=${keyOf(child)}" }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun applyExpanded(row: Any, facts: RowFacts) {
        val clicked = Attribution.ours { RowApi.clickExpand(row) }
        if (clicked) NotifLog.d { "v2 arrow EXPAND key=${facts.key}" }
        else NotifLog.d { "v2 arrow missing key=${facts.key}" }
        RowStateStore.onApplied(facts.key)
    }

    /**
     * Returns a group child to collapsed.
     *
     * The arrow is a toggle, and the policy only returns [Decision.Collapse] for a row that is
     * currently expanded, so invoking the row's own arrow listener flips it closed. That keeps
     * SystemUI's own bookkeeping (`mUserExpanded`, `mHasUserChangedExpansion`, heights) as the
     * single writer instead of clearing fields behind its back.
     *
     * This deliberately leaves `mHasUserChangedExpansion` set, which means the row now behaves
     * exactly as if the user had collapsed it — including expiring when SystemUI resets the row
     * on the next repost. That is the lifetime we want.
     */
    private fun applyCollapsed(row: Any, facts: RowFacts) {
        val clicked = Attribution.ours { RowApi.clickExpand(row) }
        if (clicked) NotifLog.d { "v2 arrow COLLAPSE key=${facts.key}" }
        else NotifLog.d { "v2 arrow missing key=${facts.key}" }
        RowStateStore.onChildCollapsed(facts.key)
    }

    /**
     * Opens a group summary.
     *
     * The summary's own arrow listener is `mExpandClickListener`: on this build it calls
     * `GroupExpansionManagerImpl.setGroupExpanded(entry, !isGroupExpanded)` and then
     * `StatusBarNotificationPresenter.onExpandClicked` — the real gesture, not a state rewrite.
     * `setChildrenExpanded` is deliberately not used: it drives the container directly and
     * visibly breaks the grouped layout.
     *
     * SystemUI's own `shouldShowPublic()` guard sits in front of that branch, so a redacted group
     * on the lock screen never opens.
     */
    private fun applyGroupExpanded(row: Any, facts: RowFacts) {
        val clicked = Attribution.ours { RowApi.clickExpand(row) }
        if (clicked) NotifLog.d { "v2 arrow GROUP key=${facts.key}" }
        else NotifLog.d { "v2 arrow missing key=${facts.key}" }
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
        // Tell the resolver where SystemUI's own bytecode lives; DexKit stays inert unless an
        // exact-name lookup fails.
        HookTargetResolver.prepare(lpparam.appInfo?.sourceDir, prefs.appContext)
        // Only the row class is a hard requirement. A ROM missing one optional capability
        // (say `isExpanded(boolean)`) keeps every other path instead of losing the engine.
        if (!RowApi.bind(lpparam.classLoader)) {
            NotifLog.e("v2 engine NOT installed — row class missing on ${OemDetector.current.name}")
            return
        }
        refreshPrefs()
        val cls = RowApi.rowClass ?: return
        NotifLog.i("v2 engine installing (oem=${OemDetector.current.name} lsGate=$lsGateEnabled " +
                   "groupCapable=${RowApi.groupCapable} " +
                   "parents=${prefsFacts.groupParentsEnabled} children=${prefsFacts.groupChildrenEnabled})")

        // ── the lock-screen gate ────────────────────────────────────────────
        // Hottest hook in the engine: `isExpanded` runs from measure and from the stack
        // scroll algorithm. Ordered so the common case (shade traffic) exits after two
        // cheap checks and never touches the per-row token map.
        RowApi.isExpandedArg?.let { isExpanded ->
        try {
            XposedBridge.hookMethod(isExpanded, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (!lsGateEnabled) return
                        if (param.args[0] == true) return
                        val row = param.thisObject
                        if (!RowApi.bool(RowApi.fOnKeyguard, row)) return
                        if (XposedHelpers.getAdditionalInstanceField(row, TOKEN) == true) {
                            param.args[0] = true
                            return
                        }
                        val facts = capture(row) ?: return
                        val decision = ExpandPolicy.decide(
                            facts, prefsFacts, RowStateStore.isBackedOff(facts.key)
                        )
                        if (decision is Decision.Expand || decision is Decision.AlreadyExpanded) {
                            setToken(row, true)
                            param.args[0] = true
                            NotifLog.d { "v2 LS gate flip key=${facts.key} exp=${facts.expandedUngated}" }
                        }
                    } catch (_: Throwable) {}
                }
            })
        } catch (t: Throwable) { NotifLog.e("v2 lockscreen gate failed: $t") }
        }

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
        installExpandClickSuppressor(lpparam.classLoader)
        installHudHook(lpparam.classLoader)
        installHudVeto(lpparam.classLoader)

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
     * Stops a programmatic arrow click from opening the shade.
     *
     * `ExpandableNotificationRow`'s arrow listener ends in
     * `StatusBarNotificationPresenter.onExpandClicked`, which opens the notification shade when
     * the row is on the lock screen. Our clicks are not the user's, so they must not have that
     * side effect — but a real tap must still work, which is why the suppression is gated on
     * [Attribution.isOurs] rather than on the method or the row.
     */
    private fun installExpandClickSuppressor(cl: ClassLoader) {
        try {
            val cls = HookTargets.presenter(cl) ?: run {
                NotifLog.d { "v2 expand-click suppressor unavailable: presenter class missing" }
                return
            }
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (Attribution.isOurs()) param.result = null
                }
            }
            val bound = hookAllWithFallback(cls, "onExpandClicked", "onexpandclicked", null, hook)
            if (bound == 0) NotifLog.d { "v2 expand-click suppressor: onExpandClicked missing" }
            else NotifLog.d { "v2 expand-click suppressor installed" }
        } catch (t: Throwable) {
            NotifLog.d { "v2 expand-click suppressor unavailable: ${t.javaClass.simpleName}" }
        }
    }

    /**
     * Hooks every overload of [name], falling back to a shape scan when the exact name binds
     * nothing (an OEM rename). Returns the number of methods hooked.
     */
    private fun hookAllWithFallback(
        cls: Class<*>?,
        name: String,
        hint: String,
        paramCount: Int?,
        hook: XC_MethodHook,
    ): Int {
        if (cls == null) return 0
        var bound = try { XposedBridge.hookAllMethods(cls, name, hook).size } catch (_: Throwable) { 0 }
        if (bound > 0) return bound
        val alts = HookTargetResolver.findByNameHint(cls, hint, paramCount)
        for (m in alts) {
            try { XposedBridge.hookMethod(m, hook); bound++ } catch (_: Throwable) {}
        }
        if (bound > 0) {
            NotifLog.i("v2 $name resolved by shape: ${alts.joinToString(",") { it.name }}")
        }
        return bound
    }

    private fun setSystemExpanded(row: Any, value: Boolean): Boolean = try {
        val m = RowApi.setSystemExpanded ?: return false
        m.invoke(row, value)
        true
    } catch (_: Throwable) { false }

    private fun expandUserExpanded(row: Any): Boolean = try {
        setSystemExpanded(row, true)
        val m2 = RowApi.setUserExpanded2
        if (m2 != null) {
            m2.invoke(row, true, true)
        } else {
            val m1 = RowApi.setUserExpanded1 ?: return false
            m1.invoke(row, true)
        }
        RowApi.isExpandedUngated(row)
    } catch (_: Throwable) { false }

    private fun collapseUserExpanded(row: Any): Boolean = try {
        setSystemExpanded(row, false)
        // Measured on CPH2747_16.0.9.400: `setExpandedWhenPinned(boolean)` does not exist on
        // ExpandableNotificationRow — only the `mExpandedWhenPinned` field. The call therefore
        // throws and is swallowed here; it is kept so a ROM that does declare the setter still
        // gets it, and the field is NOT written directly because the verified HUD path depends on
        // SystemUI's own height notification callbacks.
        try { XposedHelpers.callMethod(row, "setExpandedWhenPinned", false) } catch (_: Throwable) {}
        val m2 = RowApi.setUserExpanded2
        if (m2 != null) {
            m2.invoke(row, false, true)
        } else {
            val m1 = RowApi.setUserExpanded1 ?: return false
            m1.invoke(row, false)
        }
        !RowApi.isExpandedUngated(row)
    } catch (_: Throwable) { false }

    /**
     * v2 HUD layout/height hooks.
     *
     * Engine 1's heads-up hooks skip group rows, so a grouped HUD stays on the single-line layout
     * (194 px) while a single HUD expands (549 px). These hooks apply the same forced expanded
     * type and height only to the current HUD key; the shade row is collapsed again by
     * [hudExpandedKeys].
     */
    private fun installHudLayoutHooks(cl: ClassLoader) {
        try {
            val content = HookTargets.contentView(cl)
            if (content == null) {
                NotifLog.d { "v2 HUD visible-type hook unavailable: content view missing" }
            } else {
            val visibleHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.thisObject ?: return
                        if (!XposedHelpers.getBooleanField(view, "mIsHeadsUp")) return
                        val result = param.result as? Int ?: return
                        if (result != VISIBLE_TYPE_HEADSUP) return
                        val row = XposedHelpers.getObjectField(view, "mContainingNotification") ?: return
                        val key = keyOf(row) ?: return
                        if (key != hudKey && key != newestChildKey) return
                        if (RowApi.callBool(RowApi.isSummaryWithChildren, row)) return
                        if (XposedHelpers.getObjectField(view, "mExpandedChild") == null) return
                        param.result = VISIBLE_TYPE_EXPANDED
                        NotifLog.d { "v2 HUD visible-type key=$key 2->1" }
                    } catch (_: Throwable) {}
                }
            }
            val bound = hookAllWithFallback(
                content, "calculateVisibleType", "calculatevisibletype", 0, visibleHook
            )
            if (bound == 0) NotifLog.d { "v2 HUD visible-type hook: calculateVisibleType missing" }
            else NotifLog.d { "v2 HUD visible-type hook installed" }
            }
        } catch (t: Throwable) {
            NotifLog.d { "v2 HUD visible-type hook unavailable: ${t.javaClass.simpleName}" }
        }

        val rowClass = RowApi.rowClass ?: return
        try {
            val intrinsicHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val row = param.thisObject ?: return
                        if (!RowApi.bool(RowApi.fIsHeadsUp, row)) return
                        val key = keyOf(row) ?: return
                        if (key != hudKey && key != newestChildKey) return
                        if (RowApi.callBool(RowApi.isSummaryWithChildren, row)) return
                        val layout = RowApi.privateLayoutOf(row) ?: return
                        val child = RowApi.expandedChildOf(layout) ?: return
                        val h = child.measuredHeight
                        if (h <= 0) return
                        if (param.result != h) {
                            param.result = h
                            NotifLog.d { "v2 HUD intrinsic height key=$key h=$h" }
                        }
                    } catch (_: Throwable) {}
                }
            }
            val intrinsic = hookAllWithFallback(
                rowClass, "getIntrinsicHeight", "getintrinsicheight", 0, intrinsicHook
            )
            if (intrinsic == 0) NotifLog.d { "v2 HUD intrinsic hook: getIntrinsicHeight missing" }
            else NotifLog.d { "v2 HUD intrinsic hook installed" }
        } catch (t: Throwable) {
            NotifLog.d { "v2 HUD intrinsic hook unavailable: ${t.javaClass.simpleName}" }
        }

        try {
            val pinnedHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val row = param.thisObject ?: return
                        if (!RowApi.bool(RowApi.fIsHeadsUp, row)) return
                        val key = keyOf(row) ?: return
                        if (key != hudKey && key != newestChildKey) return
                        if (RowApi.callBool(RowApi.isSummaryWithChildren, row)) return
                        val layout = RowApi.privateLayoutOf(row) ?: return
                        val child = RowApi.expandedChildOf(layout) ?: return
                        val h = child.measuredHeight
                        if (h <= 0) return
                        if (param.result != h) {
                            param.result = h
                            NotifLog.d { "v2 HUD pinned height key=$key h=$h" }
                        }
                    } catch (_: Throwable) {}
                }
            }
            val pinned = hookAllWithFallback(
                rowClass, "getPinnedHeadsUpHeight", "getpinnedheadsupheight", 1, pinnedHook
            )
            if (pinned == 0) NotifLog.d { "v2 HUD pinned-height hook: getPinnedHeadsUpHeight missing" }
            else NotifLog.d { "v2 HUD pinned-height hook installed" }
        } catch (t: Throwable) {
            NotifLog.d { "v2 HUD pinned-height hook unavailable: ${t.javaClass.simpleName}" }
        }
    }

    /**
     * Engine 2's heads-up expansion. Engine 1's HU hooks are gated off when v2 is selected, so
     * this is the only thing that expands a pinned row. It clicks the row's real arrow listener
     * and only for the newest non-summary notification; an older row that SystemUI re-pins is
     * left alone instead of being re-expanded.
     */
    private fun installHudHook(cl: ClassLoader) {
        val rowClass = RowApi.rowClass ?: return
        try {
            XposedBridge.hookAllMethods(rowClass, "setHeadsUp", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val up = param.args.getOrNull(0) == true
                    val row = param.thisObject ?: return
                    val key = keyOf(row) ?: return
                    val view = row as? android.view.View
                    if (up) {
                        hudKey = key
                    } else {
                        if (hudKey == key) hudKey = null
                        if (hudPendingKey == key) hudPendingKey = null
                        if (hudExpandedKeys.remove(key)) {
                            view?.postDelayed({
                                try {
                                    if (RowApi.bool(RowApi.fIsHeadsUp, row)) return@postDelayed
                                    Attribution.ours {
                                        if (collapseUserExpanded(row)) {
                                            RowStateStore.onChildCollapsed(key)
                                            NotifLog.d { "v2 HUD auto-collapse key=$key" }
                                        }
                                    }
                                } catch (_: Throwable) {}
                            }, 250L)
                        }
                        return
                    }
                    if (key != newestChildKey) return
                    val grouped = try { capture(row)?.isGroupChild == true } catch (_: Throwable) { true }
                    if (grouped) {
                        val v = view ?: return
                        v.postDelayed({
                            try {
                                if (!v.isAttachedToWindow) return@postDelayed
                                if (RowApi.isExpandedUngated(row)) {
                                    hudExpandedKeys.add(key)
                                    return@postDelayed
                                }
                                Attribution.ours {
                                    if (expandUserExpanded(row)) {
                                        hudExpandedKeys.add(key)
                                        RowStateStore.onApplied(key)
                                        NotifLog.d { "v2 HUD auto-expand key=$key" }
                                    } else {
                                        NotifLog.d { "v2 HUD auto-expand missing key=$key" }
                                    }
                                }
                            } catch (_: Throwable) {}
                        }, 60L)
                        return
                    }
                    val v = view ?: return
                    v.postDelayed(object : Runnable {
                        override fun run() {
                            try {
                                if (!v.isAttachedToWindow) return
                                if (RowApi.isExpandedUngated(row)) {
                                    hudExpandedKeys.add(key)
                                    return
                                }
                                Attribution.ours {
                                    if (RowApi.clickExpand(row)) {
                                        hudExpandedKeys.add(key)
                                        NotifLog.d { "v2 HUD arrow EXPAND key=$key" }
                                    } else {
                                        NotifLog.d { "v2 HUD arrow missing key=$key" }
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                    }, 60L)
                }
            })
            XposedBridge.hookAllMethods(rowClass, "setPinnedStatus", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val row = param.thisObject ?: return
                        val key = keyOf(row) ?: return
                        if (key != newestChildKey) return
                        val status = param.args.getOrNull(0) ?: return
                        val pinned = XposedHelpers.callMethod(status, "isPinned") as? Boolean ?: false
                        if (!pinned) return
                        NotifLog.d { "v2 HUD pin seen key=$key" }
                        Attribution.ours {
                            XposedHelpers.callMethod(row, "setExpandedWhenPinned", true)
                            setSystemExpanded(row, true)
                        }
                        hudExpandedKeys.add(key)
                        NotifLog.d { "v2 HUD expand pinned key=$key" }
                    } catch (_: Throwable) {}
                }
            })
            NotifLog.d { "v2 HUD arrow hook installed" }
        } catch (t: Throwable) {
            NotifLog.d { "v2 HUD arrow hook unavailable: ${t.javaClass.simpleName}" }
        }
        installHudLayoutHooks(cl)
    }

    /**
     * Replaces a stale HUD entry with the newest fresh entry.
     *
     * Measured on OxygenOS 16: the group refresh posts the stale sibling to `showNotification`,
     * and the genuinely new child is never offered. The HUD manager only accepts the entry it is
     * given, so the stale entry is swapped for the stored newest entry before the original call
     * runs. The freshness check is repeated on the substitute.
     */
    private fun promoteNewest(param: XC_MethodHook.MethodHookParam, staleKey: String): Boolean {
        val entry = newestChildEntry ?: return false
        val freshKey = newestChildKey ?: return false
        if (freshKey == staleKey) return false
        val sbn = try { XposedHelpers.callMethod(entry, "getSbn") } catch (_: Throwable) { return false }
        val key = try { XposedHelpers.callMethod(sbn, "getKey") as? String } catch (_: Throwable) { null } ?: return false
        if (key != freshKey) return false
        val notif = try { XposedHelpers.callMethod(sbn, "getNotification") } catch (_: Throwable) { return false }
        val whenMillis = try { XposedHelpers.getLongField(notif, "when") } catch (_: Throwable) { 0L }
        val age = System.currentTimeMillis() - whenMillis
        if (whenMillis <= 0L || age < -5_000L || age > HUD_FRESH_MS) return false
        param.args[0] = entry
        hudPendingKey = freshKey
        hudPendingAt = now()
        NotifLog.d { "v2 HUD replace stale key=$staleKey fresh=$freshKey when=$whenMillis age=$age" }
        return true
    }

    /**
     * Rejects grouped heads-up content that is not current.
     *
     * Measured on OxygenOS 16: a group refresh promoted a sibling whose `Notification.when` was
     * 2.6 minutes old while the genuinely new message was current. The wrong row was the one
     * that reached `showNotification`. Only content newer than [HUD_FRESH_MS] may open a grouped
     * heads-up; the stale sibling is vetoed and the current chat stays eligible. Standalone
     * notifications and summaries are untouched.
     */
    private fun installHudVeto(cl: ClassLoader) {
        try {
            val cls = HookTargets.headsUpManager(cl)
            if (cls == null) {
                NotifLog.d { "v2 HUD veto unavailable: heads-up manager missing" }
                return
            }
            val vetoHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.args.size < 2 || param.args[1] !is Boolean) return
                        val entry = param.args[0] ?: return
                        val sbn = XposedHelpers.callMethod(entry, "getSbn") ?: return
                        val key = XposedHelpers.callMethod(sbn, "getKey") as? String ?: return
                        val groupKey = XposedHelpers.callMethod(sbn, "getGroupKey") as? String
                        if (groupKey == null) return
                        val notification = XposedHelpers.callMethod(sbn, "getNotification")
                        val contentWhen = try {
                            XposedHelpers.getLongField(notification, "when")
                        } catch (_: Throwable) { 0L }
                        val age = System.currentTimeMillis() - contentWhen
                        val fresh = contentWhen > 0L && age >= -5_000L && age <= HUD_FRESH_MS
                        if (fresh) {
                            hudPendingKey = key
                            hudPendingAt = now()
                            NotifLog.d { "v2 HUD allow fresh key=$key when=$contentWhen age=$age" }
                            return
                        }
                        if (promoteNewest(param, key)) return
                        param.result = null
                        NotifLog.d { "v2 HUD veto stale key=$key when=$contentWhen age=$age" }
                    } catch (_: Throwable) {}
                }
            }
            val bound = hookAllWithFallback(cls, "showNotification", "shownotification", 2, vetoHook)
            if (bound == 0) NotifLog.d { "v2 HUD veto: showNotification missing" }
            else NotifLog.d { "v2 HUD veto installed" }
        } catch (t: Throwable) {
            NotifLog.d { "v2 HUD veto unavailable: ${t.javaClass.simpleName}" }
        }
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
        val cls = HookTargets.groupExpansionManager(lpparam.classLoader)
        if (cls == null) {
            NotifLog.e("v2 group collapse memory: group manager class not found")
            return
        }
        groupManagerName = cls.name
        try {
            val groupHook = object : XC_MethodHook() {
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
            }
            val hooks = hookAllWithFallback(cls, "setGroupExpanded", "setgroupexpanded", 2, groupHook)
            if (hooks == 0) NotifLog.e("v2 setGroupExpanded bound 0 methods")
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
        // Both names come from the resolver: an OEM subclass moves the frames, and matching the
        // AOSP literal would then see neither the manager nor the row and drop a real tap.
        val groupName = groupManagerName ?: GROUP_MANAGER_CLASS
        val rowName = RowApi.rowClass?.name ?: RowApi.ROW_CLASS
        for (f in Throwable().stackTrace) {
            val c = f.className
            if (c.startsWith(groupName)) {
                if (f.methodName == "setGroupExpanded") continue   // the hooked frame itself
                return null                                        // SystemUI's own bulk reset
            }
            if (c.startsWith(rowName) && f.methodName in USER_INTENT_FRAMES)
                return f.methodName
        }
        return null
    }
}
