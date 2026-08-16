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
     */
    @Synchronized
    fun onExpansionWiped(key: String) {
        val s = state(key)
        val now = SystemClock.uptimeMillis()
        // Same logical reset arriving twice — not a second fight.
        if (now - s.lastWipeTs < WIPE_DEBOUNCE_MS) return
        s.wipes = if (now - s.lastWipeTs < WIPE_WINDOW_MS) s.wipes + 1 else 1
        s.lastWipeTs = now
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

    /** Called when a notification is genuinely reposted — a clean slate. */
    @Synchronized
    fun forget(key: String) { states.remove(key) }

    @Synchronized
    fun clear() = states.clear()
}
