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
    val expandedUngated: Boolean,        // isExpanded(true) — the honest read
    val hasUserChangedExpansion: Boolean,
    val userExpanded: Boolean,
) {
    /**
     * Android 16 bundles notifications into system sections (`g:Aggregate_AlertingSection`,
     * `g:Aggregate_SilentSection`). Those children look grouped but render standalone and have
     * no parent that will ever expand them — so they must be treated as singles.
     *
     * An app-declared group child is different: its summary row genuinely drives it, and
     * expanding children individually there is what breaks the grouped look.
     */
    val isSystemAggregateChild: Boolean
        get() = isChildInGroup && (groupKey?.contains(SYSTEM_AGGREGATE_MARKER) == true)

    /** A child of a real, app-declared group — the parent drives it, so leave it alone. */
    val isAppGroupChild: Boolean
        get() = isChildInGroup && !isSystemAggregateChild

    /** The user deliberately collapsed this row; SystemUI records it for us. */
    val userCollapsed: Boolean
        get() = hasUserChangedExpansion && !userExpanded

    companion object {
        /** Matches `…|g:Aggregate_AlertingSection`, `…|g:Aggregate_SilentSection`, etc. */
        const val SYSTEM_AGGREGATE_MARKER = "g:Aggregate_"
    }
}

/** Prefs, snapshotted off the hot path. */
data class PrefsFacts(
    val shadeEnabled: Boolean,
    val lockscreenEnabled: Boolean,
    val excludedApps: Set<String>,
)

sealed interface Decision {
    /** Drive this row to expanded. */
    data object Expand : Decision
    /** Eligible, but already in the desired state — do nothing. */
    data object AlreadyExpanded : Decision
    /** Not our business. [why] is logged so a wrong skip is diagnosable. */
    data class Skip(val why: Reason) : Decision

    enum class Reason {
        FeatureOff, PkgExcluded, HeadsUp, Pinned, AppGroupChild,
        UserCollapsed, GroupSummary, BackedOff;

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

        // A child of a real app group is driven by its summary. A child of a *system* bundle
        // is not — it is standalone in everything but name, so it falls through and expands.
        if (facts.isAppGroupChild) return Decision.Skip(Decision.Reason.AppGroupChild)

        // Group summaries keep the legacy path; this driver only handles singles.
        if (facts.isSummaryWithChildren) return Decision.Skip(Decision.Reason.GroupSummary)

        // The user's own collapse always wins, and SystemUI maintains the flag for us.
        if (facts.userCollapsed) return Decision.Skip(Decision.Reason.UserCollapsed)

        if (backedOff) return Decision.Skip(Decision.Reason.BackedOff)

        // Idempotence: the honest read, not the keyguard-gated one.
        return if (facts.expandedUngated) Decision.AlreadyExpanded else Decision.Expand
    }
}
