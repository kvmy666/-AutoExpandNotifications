package io.github.kvmy666.autoexpand.hook.notif

import android.os.SystemClock

/**
 * Per-notification state, keyed by notification key rather than row identity so it survives a
 * row being rebuilt, and so it can be reasoned about while debugging (the key is what shows up
 * in logs and in `dumpsys notification`).
 *
 * Its only real job is the **anti-thrash back-off**. Measured on device: on the lock screen
 * SystemUI calls `resetUserExpansion()` and wipes our expansion mid-flight; the old engine
 * re-applied unconditionally, producing five expand calls for one notification and a visible
 * 169↔245px height oscillation. The rule here is: re-apply once, and if it gets wiped again
 * straight away, stop fighting and leave the row alone.
 *
 * Bounded so it cannot grow without limit in a long-lived SystemUI process.
 */
object RowStateStore {

    /** A second wipe inside this window means SystemUI is actively fighting us. */
    private const val WIPE_WINDOW_MS = 1_500L
    /**
     * `resetUserExpansion` fires more than once for a single logical reset — measured at two
     * calls 4 ms apart. Without this debounce one reset counts as two wipes and trips the
     * back-off immediately, which is exactly what it must not do.
     */
    private const val WIPE_DEBOUNCE_MS = 250L
    /**
     * How soon after our own write a wipe has to land to count as SystemUI contesting it. The
     * measured lock-screen oscillation came back within milliseconds; a shade close is orders of
     * magnitude further away, which is what separates a fight from housekeeping.
     */
    private const val CONTESTED_MS = 300L
    /** How long a backed-off key stays backed off before we allow one more try. */
    private const val COOLDOWN_MS = 5_000L
    private const val MAX_KEYS = 256

    private class KeyState {
        var wipes = 0
        var lastWipeTs = 0L
        var lastApplyTs = 0L
        /**
         * The user closed this group with the arrow.
         *
         * Needed because the group path inside `setUserExpanded` returns before it writes
         * `mHasUserChangedExpansion`, so — unlike a single row — SystemUI keeps no record that
         * the collapse was deliberate. Without this the parent toggle would immediately reopen
         * every group the user just closed.
         */
        var groupCollapsedByUser = false
        /** We have collapsed this row once as a group child; we do not do it twice. */
        var childCollapsed = false
    }

    private val states = object : LinkedHashMap<String, KeyState>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KeyState>?) =
            size > MAX_KEYS
    }

    @Synchronized
    private fun state(key: String): KeyState = states.getOrPut(key) { KeyState() }

    @Synchronized
    fun onApplied(key: String) {
        state(key).lastApplyTs = SystemClock.uptimeMillis()
    }

    /**
     * Records that SystemUI wiped our expansion. Consecutive wipes inside [WIPE_WINDOW_MS]
     * accumulate; an isolated one resets the count, so a normal content update still re-expands.
     *
     * Only a wipe that lands right after *our own* write counts. `resetUserExpansion()` also runs
     * as routine housekeeping — every shade close calls it — and counting those made ordinary
     * interaction look like a fight: opening and closing the shade twice within [WIPE_WINDOW_MS]
     * tripped the back-off, and nothing expanded again until the cooldown elapsed. The oscillation
     * this guard exists for is nothing like that; there the wipe follows the write within
     * milliseconds, which is what [CONTESTED_MS] tests for.
     */
    @Synchronized
    fun onExpansionWiped(key: String) {
        val s = state(key)
        val now = SystemClock.uptimeMillis()
        // Same logical reset arriving twice — not a second fight.
        if (now - s.lastWipeTs < WIPE_DEBOUNCE_MS) return
        val sinceLastWipe = now - s.lastWipeTs
        s.lastWipeTs = now
        if (now - s.lastApplyTs > CONTESTED_MS) { s.wipes = 0; return }   // routine, not a fight
        s.wipes = if (sinceLastWipe < WIPE_WINDOW_MS) s.wipes + 1 else 1
    }

    /**
     * True once we have re-applied and been wiped again. Self-healing: after [COOLDOWN_MS] of
     * quiet the key is allowed one more attempt, so a transient fight never disables a
     * notification permanently.
     */
    @Synchronized
    fun isBackedOff(key: String): Boolean {
        val s = states[key] ?: return false
        if (s.wipes < 2) return false
        if (SystemClock.uptimeMillis() - s.lastWipeTs > COOLDOWN_MS) {
            s.wipes = 0
            return false
        }
        return true
    }

    /**
     * Records an arrow tap on a group summary. [expanded] false means the user closed it and we
     * must stop reopening it; true is the user reopening it themselves, which clears the memory.
     */
    @Synchronized
    fun onGroupUserExpansion(key: String, expanded: Boolean) {
        state(key).groupCollapsedByUser = !expanded
    }

    @Synchronized
    fun isGroupCollapsedByUser(key: String): Boolean =
        states[key]?.groupCollapsedByUser == true

    /**
     * A repost is new content, so the collapse memory expires — the same lifetime SystemUI gives
     * a single row, where it resets `mHasUserChangedExpansion` when the row is rebuilt.
     *
     * Without this the memory outlives every notification it was ever about. That is bad for an
     * app-declared group and *much* worse for a system bundle, whose key
     * (`…|g:Aggregate_AlertingSection`) covers everything that app puts in the section: closing
     * one bundle once would stop that app's notifications auto-opening again for the rest of the
     * SystemUI process, which reads exactly like the feature having quietly died.
     */
    @Synchronized
    fun onNotificationUpdated(key: String) {
        states[key]?.let { it.groupCollapsedByUser = false; it.childCollapsed = false }
    }

    /**
     * One collapse per notification. After that the row is left alone, so a user who expands a
     * child by hand keeps it expanded rather than watching it snap shut again.
     */
    @Synchronized
    fun onChildCollapsed(key: String) { state(key).childCollapsed = true }

    @Synchronized
    fun wasChildCollapsed(key: String): Boolean = states[key]?.childCollapsed == true

    /** Called when a notification is genuinely reposted — a clean slate. */
    @Synchronized
    fun forget(key: String) { states.remove(key) }

    @Synchronized
    fun clear() = states.clear()
}
