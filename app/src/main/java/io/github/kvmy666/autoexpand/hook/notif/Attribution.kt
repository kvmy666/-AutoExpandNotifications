package io.github.kvmy666.autoexpand.hook.notif

/**
 * Marks the call stack as "this write came from us".
 *
 * Two jobs. First, re-entrancy: the engine hooks `setSystemExpanded` *and* calls it, so without
 * a token the hook would observe its own write and recurse. Second, attribution: any expansion
 * change that arrives **without** the token is by definition the user's or SystemUI's, which is
 * what lets a manual collapse win without having to find and hook the expand arrow.
 *
 * Thread-local because SystemUI touches rows from the main thread but the guarantee should not
 * depend on that remaining true.
 */
object Attribution {

    private val depth = ThreadLocal.withInitial { 0 }

    /** True while the current thread is inside an engine-initiated write. */
    fun isOurs(): Boolean = depth.get() > 0

    inline fun <T> ours(block: () -> T): T {
        enter()
        try { return block() } finally { exit() }
    }

    fun enter() { depth.set(depth.get() + 1) }
    fun exit()  { depth.set((depth.get() - 1).coerceAtLeast(0)) }
}
