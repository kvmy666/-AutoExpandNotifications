package io.github.kvmy666.autoexpand.hook.notif

/**
 * The expand decision, as pure data + pure logic.
 *
 * No Android, no Xposed, no reflection — so the rule that decides whether a notification should
 * be expanded can be read in one screen and unit-tested on the JVM. Everything that can fail
 * (reflection, prefs IO, view traversal) happens before this and arrives as plain values.
 */

/** Immutable capture of everything the decision depends on, read synchronously at one instant. */
data class RowFacts(
    val key: String,
    val pkg: String?,
    val groupKey: String?,
    val onKeyguard: Boolean,
    val isHeadsUp: Boolean,
    val isPinned: Boolean,
    val isChildInGroup: Boolean,
    val isSummaryWithChildren: Boolean,
    /**
     * `FLAG_GROUP_SUMMARY`, read off the posted notification.
     *
     * The view-level [isSummaryWithChildren] and [isChildInGroup] only become true once the row
     * has been attached under its summary, and rows are reconciled before that — so they are
     * both false on the pass that matters. This flag and [groupKey] come from the notification
     * itself and are correct from the first frame.
     */
    val isGroupSummary: Boolean = false,
    val expandedUngated: Boolean,        // isExpanded(true) — the honest read
    val hasUserChangedExpansion: Boolean,
    val userExpanded: Boolean,
    /**
     * `isGroupExpanded()` — whether this row's group is open. Meaningful on the summary *and* on
     * a child (it resolves through the summary), which is what lets a child ask "am I visible?".
     */
    val groupExpanded: Boolean = false,
    /** The user collapsed this group with the arrow; tracked by us because SystemUI does not. */
    val groupUserCollapsed: Boolean = false,
    /** We have already collapsed this row once as a group child — see [Decision.Collapse]. */
    val childCollapseDone: Boolean = false,
    /**
     * SystemUI's own answer to "is this row drawn inside a group", read from the notification
     * pipeline (`RowApi.pipelineGroupedOf`): its parent is a group that has a summary.
     *
     * `null` means the pipeline could not be read, and [isGroupChild] falls back to the group
     * key. Anything else would be a guess: the two disagree precisely for the notifications
     * this fact exists to get right.
     */
    val pipelineGrouped: Boolean? = null,
) {
    /**
     * Whether the notification belongs to *any* group.
     *
     * Measured on OxygenOS 16: a standalone notification's group key is simply its own key
     * (`0|pkg|2000|null|10441`), while anything grouped carries a `|g:` marker
     * (`0|pkg|g:…TEST_GROUP`, `0|pkg|g:Aggregate_AlertingSection`). A ROM that does neither
     * falls back to "standalone", which is the safe answer — it is what singles already do.
     */
    val belongsToGroup: Boolean
        get() = groupKey?.contains(GROUP_MARKER) == true

    /**
     * A child of *any* group — app-declared or one of Android 16's system bundles
     * (`g:Aggregate_AlertingSection`, `g:Aggregate_SilentSection`). Either way a summary row
     * drives it, so it answers to the children toggle rather than the single-row rules.
     *
     * System bundles used to be excluded here and expanded as singles, on the reasoning that
     * they "have no parent that will ever expand them". That was true only while nothing opened
     * summaries. The group-parent toggle opens them now, so the premise is gone — and keeping
     * the exception made the children toggle look broken: an app whose notifications the system
     * had bundled expanded them regardless of the setting, while an app that declares its own
     * groups obeyed it.
     *
     * A notification the system has *not* bundled has no group key marker at all and is still a
     * single, which is why one that arrives on its own expands as before.
     *
     * The group key is only the fallback, though. It records what the *app* asked for, and an
     * app can ask for a group it never gets: SystemUI keeps a group only while a summary row
     * exists for it and promotes a lone child back to the top level. Instagram gives every DM
     * thread its own group key and posts no summary, so its notifications carry `|g:…` while
     * rendering as ordinary single cards — and were skipped as group children, collapsed, for
     * as long as the children toggle was off. [pipelineGrouped] is SystemUI's own verdict and
     * wins wherever it can be read.
     */
    val isGroupChild: Boolean
        get() = !isGroupSummary && (pipelineGrouped ?: belongsToGroup)

    /** Diagnostic only — which flavour of group this is, for the log line. */
    val isSystemAggregate: Boolean
        get() = groupKey?.contains(SYSTEM_AGGREGATE_MARKER) == true

    /** The user deliberately collapsed this row; SystemUI records it for us. */
    val userCollapsed: Boolean
        get() = hasUserChangedExpansion && !userExpanded

    companion object {
        /** Matches `…|g:Aggregate_AlertingSection`, `…|g:Aggregate_SilentSection`, etc. */
        const val SYSTEM_AGGREGATE_MARKER = "g:Aggregate_"
        /** Present in the group key of every grouped notification, absent for singles. */
        const val GROUP_MARKER = "|g:"
    }
}

/** Prefs, snapshotted off the hot path. */
data class PrefsFacts(
    val shadeEnabled: Boolean,
    val lockscreenEnabled: Boolean,
    val excludedApps: Set<String>,
    /** Open every group summary, so a bundled app's rows are visible without a tap. */
    val groupParentsEnabled: Boolean = false,
    /** Inside an open group, expand each child too instead of leaving them one-line. */
    val groupChildrenEnabled: Boolean = false,
)

sealed interface Decision {
    /** Drive this row to expanded — the row's own content, via `setSystemExpanded`. */
    data object Expand : Decision
    /**
     * Open this group summary — a different primitive entirely: `setUserExpanded(true, true)`,
     * which routes to `GroupExpansionManager` instead of touching this row's content height.
     */
    data object ExpandGroup : Decision
    /**
     * Undo an expansion this row should not have. Only ever used for a group child while the
     * children toggle is off: the heads-up path expands a banner with `setUserExpanded(true,
     * true)` and that expansion survives into the shade, so "don't expand" is not enough to make
     * the toggle mean what it says. Applied once per notification, so a deliberate re-expand by
     * the user afterwards stands.
     */
    data object Collapse : Decision
    /** Eligible, but already in the desired state — do nothing. */
    data object AlreadyExpanded : Decision
    /** Not our business. [why] is logged so a wrong skip is diagnosable. */
    data class Skip(val why: Reason) : Decision

    enum class Reason {
        FeatureOff, PkgExcluded, HeadsUp, Pinned, GroupChild,
        UserCollapsed, GroupSummary, BackedOff,
        /** Group parents left alone because the toggle is off. */
        GroupParentsOff,
        /** A group summary on the lock screen — a state SystemUI's keyguard layout cannot draw. */
        GroupOnKeyguard,
        /** A summary whose children have not attached yet — writing anything now breaks it. */
        GroupNotReady,
        /** A child whose group is still closed — expanding it would inflate the collapsed preview. */
        GroupCollapsed;

        /**
         * Whether the lock-screen token should be dropped for this skip.
         *
         * [BackedOff] means "stop writing", not "collapse it". Dropping the token there makes
         * the keyguard gate stop rewriting `allowOnKeyguard`, which actively collapses a row
         * that was already open — the opposite of leaving it alone. Every other reason is a
         * genuine "this row is not ours", where the token must go.
         */
        val clearsLockscreenToken: Boolean get() = this != BackedOff
    }
}

object ExpandPolicy {

    /**
     * @param backedOff true when this key has already been re-applied and wiped again, so we
     *        stop rather than fight SystemUI (see [RowStateStore]).
     */
    fun decide(facts: RowFacts, prefs: PrefsFacts, backedOff: Boolean): Decision {
        // Heads-up owns its own engine and is frozen — never touched here.
        if (facts.isHeadsUp) return Decision.Skip(Decision.Reason.HeadsUp)
        if (facts.isPinned)  return Decision.Skip(Decision.Reason.Pinned)

        val featureOn = if (facts.onKeyguard) prefs.lockscreenEnabled else prefs.shadeEnabled
        if (!featureOn) return Decision.Skip(Decision.Reason.FeatureOff)
        if (facts.pkg != null && facts.pkg in prefs.excludedApps)
            return Decision.Skip(Decision.Reason.PkgExcluded)

        // ── Group summary ────────────────────────────────────────────────────────────────
        // Opening a group is not the same operation as expanding a row: it moves a flag in
        // GroupExpansionManager, and the summary's own content height is untouched. So it gets
        // its own decision and its own collapse memory — SystemUI never sets
        // mHasUserChangedExpansion on this path, so `userCollapsed` cannot see an arrow tap here.
        // NB: [backedOff] is deliberately not consulted below this point for a summary. It counts
        // `resetUserExpansion()` wipes, which is the right protection for a single row — that
        // call really does erase `mUserExpanded` underneath us. A group's expansion does not
        // live there at all; it lives in GroupExpansionManager, and SystemUI clears it on every
        // shade close by design. Treating that as SystemUI "fighting us" made two shade cycles
        // inside the 1.5 s window trip the back-off, and the parent then refused to open for the
        // 5 s cooldown — reported as "open and close twice quickly and it stops working".
        if (facts.isGroupSummary) {
            // An open group is a shade-only shape. SystemUI never draws one on the keyguard by
            // any path of its own: tapping the arrow on a collapsed group there runs
            // `goToLockedShade` instead of expanding in place. So the keyguard's own size
            // calculator (`NotificationStackSizeCalculator.getSpaceNeeded`, which reads
            // `getHeightWithoutLockscreenConstraints`) budgets for a *collapsed* summary, and a
            // group we opened behind its back draws taller than the slot it was given —
            // measured on device as rows painted straight over their neighbours once the lock
            // screen held enough notifications to be tight for space. The same flood with this
            // toggle off renders as a clean collapsed bundle.
            //
            // Nothing is lost: the group opens the moment the shade is pulled down, which is
            // where an expanded group belongs and where the space to draw it exists.
            if (facts.onKeyguard) return Decision.Skip(Decision.Reason.GroupOnKeyguard)
            // The group primitive only works once the row has adopted its children: until then
            // `setUserExpanded(true, true)` misses its group branch and falls through to the
            // single path, which expands the summary's *own* content and tears the group apart
            // in the shade. A later trigger catches it — summaries are reconciled on every
            // render, so there is nothing to schedule.
            if (!facts.isSummaryWithChildren) return Decision.Skip(Decision.Reason.GroupNotReady)
            if (!prefs.groupParentsEnabled)  return Decision.Skip(Decision.Reason.GroupParentsOff)
            if (facts.groupUserCollapsed)    return Decision.Skip(Decision.Reason.UserCollapsed)
            return if (facts.groupExpanded) Decision.AlreadyExpanded else Decision.ExpandGroup
        }

        // ── Group child ──────────────────────────────────────────────────────────────────
        // Driven by its summary, whether that summary was posted by the app or synthesised by
        // Android 16's bundling. Both are groups on screen, so both answer to the same toggle.
        if (facts.isGroupChild) {
            if (!prefs.groupChildrenEnabled) {
                // "Children stay collapsed" has to survive the row having been a heads-up
                // banner, which the (frozen) heads-up path expands and never undoes. Skipping
                // alone leaves the newest notification in every bundle sitting expanded.
                return if (facts.expandedUngated && !facts.childCollapseDone) Decision.Collapse
                       else Decision.Skip(Decision.Reason.GroupChild)
            }
            // While the group is closed the container sizes itself from each child's intrinsic
            // height, so expanding children here would inflate the collapsed preview.
            if (!facts.groupExpanded)        return Decision.Skip(Decision.Reason.GroupCollapsed)
            // else: fall through and expand like any other row.
        }

        // The user's own collapse always wins, and SystemUI maintains the flag for us.
        if (facts.userCollapsed) return Decision.Skip(Decision.Reason.UserCollapsed)

        if (backedOff) return Decision.Skip(Decision.Reason.BackedOff)

        // Idempotence: the honest read, not the keyguard-gated one.
        return if (facts.expandedUngated) Decision.AlreadyExpanded else Decision.Expand
    }
}
