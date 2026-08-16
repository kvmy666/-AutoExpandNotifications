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
     * Android 16 bundles notifications into system sections (`g:Aggregate_AlertingSection`,
     * `g:Aggregate_SilentSection`). Those children look grouped but render standalone and have
     * no parent that will ever expand them — so they must be treated as singles.
     *
     * An app-declared group child is different: its summary row genuinely drives it, and
     * expanding children individually there is what breaks the grouped look.
     */
    val isSystemAggregateChild: Boolean
        get() = !isGroupSummary && (groupKey?.contains(SYSTEM_AGGREGATE_MARKER) == true)

    /** A child of a real, app-declared group — the parent drives it, so leave it alone. */
    val isAppGroupChild: Boolean
        get() = !isGroupSummary && belongsToGroup && !isSystemAggregateChild

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
    /** Eligible, but already in the desired state — do nothing. */
    data object AlreadyExpanded : Decision
    /** Not our business. [why] is logged so a wrong skip is diagnosable. */
    data class Skip(val why: Reason) : Decision

    enum class Reason {
        FeatureOff, PkgExcluded, HeadsUp, Pinned, AppGroupChild,
        UserCollapsed, GroupSummary, BackedOff,
        /** Group parents left alone because the toggle is off. */
        GroupParentsOff,
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
        if (facts.isGroupSummary) {
            // The group primitive only works once the row has adopted its children: until then
            // `setUserExpanded(true, true)` misses its group branch and falls through to the
            // single path, which expands the summary's *own* content and tears the group apart
            // in the shade. A later trigger catches it — summaries are reconciled on every
            // render, so there is nothing to schedule.
            if (!facts.isSummaryWithChildren) return Decision.Skip(Decision.Reason.GroupNotReady)
            if (!prefs.groupParentsEnabled)  return Decision.Skip(Decision.Reason.GroupParentsOff)
            if (facts.groupUserCollapsed)    return Decision.Skip(Decision.Reason.UserCollapsed)
            if (backedOff)                   return Decision.Skip(Decision.Reason.BackedOff)
            return if (facts.groupExpanded) Decision.AlreadyExpanded else Decision.ExpandGroup
        }

        // ── Group child ──────────────────────────────────────────────────────────────────
        // A child of a real app group is driven by its summary. A child of a *system* bundle
        // is not — it is standalone in everything but name, so it falls through and expands.
        if (facts.isAppGroupChild) {
            if (!prefs.groupChildrenEnabled) return Decision.Skip(Decision.Reason.AppGroupChild)
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
