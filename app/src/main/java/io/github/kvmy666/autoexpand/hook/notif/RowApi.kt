package io.github.kvmy666.autoexpand.hook.notif

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * The one place that reflects into `ExpandableNotificationRow`.
 *
 * Every handle is resolved **once per process** at install and cached. A missing handle means
 * "this ROM doesn't have that capability" — it never turns into a throw at call time, and never
 * into a silent per-call `try/catch` that hides a systematic problem behind 800 swallowed
 * exceptions. [caps] is logged once so an unsupported ROM is visible immediately; it is the
 * single line to ask a Xiaomi user for.
 *
 * Deliberately resolves by explicit name + parameter types rather than enumerating
 * `getDeclaredMethods()` / `getDeclaredFields()`, which can throw `NoClassDefFoundError` while
 * resolving OEM parameter types.
 */
object RowApi {

    private const val TAG = "AENotif"
    const val ROW_CLASS = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"

    @Volatile var rowClass: Class<*>? = null; private set

    // Methods
    var isExpandedArg: Method? = null; private set     // isExpanded(boolean allowOnKeyguard)
    var setUserExpanded2: Method? = null; private set  // setUserExpanded(boolean, boolean)
    var setUserExpanded1: Method? = null; private set  // setUserExpanded(boolean)
    var setSystemExpanded: Method? = null; private set
    var isChildInGroup: Method? = null; private set
    var isPinned: Method? = null; private set
    var isSummaryWithChildren: Method? = null; private set
    private var getEntry: Method? = null
    private var getEntryLegacy: Method? = null

    // Fields
    var fIsHeadsUp: Field? = null; private set
    var fOnKeyguard: Field? = null; private set
    var fUserExpanded: Field? = null; private set
    var fHasUserChangedExpansion: Field? = null; private set
    var fIsSystemExpanded: Field? = null; private set
    var fChildrenExpanded: Field? = null; private set

    @Volatile private var bound = false

    /** True when enough of the surface resolved that the v2 engine can do its job at all. */
    val usable: Boolean
        get() = rowClass != null && isExpandedArg != null &&
                (setSystemExpanded != null || setUserExpanded2 != null || setUserExpanded1 != null)

    /** True when the lock-screen gate can be installed. */
    val lockscreenCapable: Boolean get() = isExpandedArg != null

    fun bind(classLoader: ClassLoader): Boolean {
        if (bound) return usable
        bound = true
        val cls = try { classLoader.loadClass(ROW_CLASS) } catch (t: Throwable) {
            Log.e(TAG, "RowApi: row class not found — v2 engine disabled ($t)"); return false
        }
        rowClass = cls

        isExpandedArg        = method(cls, "isExpanded", Boolean::class.javaPrimitiveType!!)
        setUserExpanded2     = method(cls, "setUserExpanded", Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
        setUserExpanded1     = method(cls, "setUserExpanded", Boolean::class.javaPrimitiveType!!)
        setSystemExpanded    = method(cls, "setSystemExpanded", Boolean::class.javaPrimitiveType!!)
        isChildInGroup       = method(cls, "isChildInGroup")
        isPinned             = method(cls, "isPinned")
        isSummaryWithChildren = method(cls, "isSummaryWithChildren")
        getEntry             = method(cls, "getEntry")
        getEntryLegacy       = method(cls, "getEntryLegacy")

        fIsHeadsUp               = field(cls, "mIsHeadsUp")
        fOnKeyguard              = field(cls, "mOnKeyguard")
        fUserExpanded            = field(cls, "mUserExpanded")
        fHasUserChangedExpansion = field(cls, "mHasUserChangedExpansion")
        fIsSystemExpanded        = field(cls, "mIsSystemExpanded")
        fChildrenExpanded        = field(cls, "mChildrenExpanded")

        Log.d(TAG, "RowApi caps: $caps")
        if (!usable) Log.e(TAG, "RowApi: insufficient capabilities — v2 engine will stay off")
        return usable
    }

    val caps: String
        get() = "isExpandedArg=${b(isExpandedArg)} setSysExp=${b(setSystemExpanded)} " +
                "setUserExp2=${b(setUserExpanded2)} setUserExp1=${b(setUserExpanded1)} " +
                "childInGroup=${b(isChildInGroup)} pinned=${b(isPinned)} " +
                "summaryKids=${b(isSummaryWithChildren)} entry=${b(getEntry)}/${b(getEntryLegacy)} " +
                "fHU=${b(fIsHeadsUp)} fKG=${b(fOnKeyguard)} fUserExp=${b(fUserExpanded)} " +
                "fUserChanged=${b(fHasUserChangedExpansion)} fSysExp=${b(fIsSystemExpanded)} " +
                "fKidsExp=${b(fChildrenExpanded)}"

    private fun b(o: Any?) = if (o != null) 1 else 0

    private fun method(cls: Class<*>, name: String, vararg types: Class<*>): Method? = try {
        cls.getDeclaredMethod(name, *types).apply { isAccessible = true }
    } catch (_: Throwable) {
        // Walk up: some OEMs move members onto a superclass.
        var c: Class<*>? = cls.superclass
        var found: Method? = null
        while (c != null && found == null) {
            found = try { c.getDeclaredMethod(name, *types).apply { isAccessible = true } }
                    catch (_: Throwable) { null }
            c = c.superclass
        }
        found
    }

    private fun field(cls: Class<*>, name: String): Field? = try {
        cls.getDeclaredField(name).apply { isAccessible = true }
    } catch (_: Throwable) {
        var c: Class<*>? = cls.superclass
        var found: Field? = null
        while (c != null && found == null) {
            found = try { c.getDeclaredField(name).apply { isAccessible = true } }
                    catch (_: Throwable) { null }
            c = c.superclass
        }
        found
    }

    // ── typed accessors ──────────────────────────────────────────────────────

    fun bool(f: Field?, row: Any, default: Boolean = false): Boolean =
        try { if (f == null) default else f.getBoolean(row) } catch (_: Throwable) { default }

    fun callBool(m: Method?, row: Any, vararg args: Any?, default: Boolean = false): Boolean =
        try { if (m == null) default else (m.invoke(row, *args) as? Boolean) ?: default }
        catch (_: Throwable) { default }

    /** `isExpanded(allowOnKeyguard = true)` — the honest read; the no-arg form does not exist. */
    fun isExpandedUngated(row: Any): Boolean = callBool(isExpandedArg, row, true)

    fun entryOf(row: Any): Any? =
        try { getEntry?.invoke(row) } catch (_: Throwable) { null }
            ?: try { getEntryLegacy?.invoke(row) } catch (_: Throwable) { null }

    fun sbnOf(row: Any): Any? = try {
        val e = entryOf(row) ?: return null
        e.javaClass.getMethod("getSbn").invoke(e)
    } catch (_: Throwable) { null }
}
