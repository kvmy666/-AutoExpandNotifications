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

    private const val PKG = "com.android.systemui.statusbar.notification.collection"
    /** Declares `getParent()` on this build; older ones declare it on `ListEntry`. */
    private const val PIPELINE_ENTRY_CLASS = "$PKG.PipelineEntry"
    private const val LIST_ENTRY_CLASS = "$PKG.ListEntry"
    private const val GROUP_ENTRY_CLASS = "$PKG.GroupEntry"

    @Volatile var rowClass: Class<*>? = null; private set

    // Methods
    var isExpandedArg: Method? = null; private set     // isExpanded(boolean allowOnKeyguard)
    var setUserExpanded2: Method? = null; private set  // setUserExpanded(boolean, boolean)
    var setUserExpanded1: Method? = null; private set  // setUserExpanded(boolean)
    var setSystemExpanded: Method? = null; private set
    var isChildInGroup: Method? = null; private set
    var isPinned: Method? = null; private set
    var isSummaryWithChildren: Method? = null; private set
    var isGroupExpanded: Method? = null; private set   // may be `isGroupExpanded$1` after R8
    var shouldShowPublic: Method? = null; private set
    private var getEntry: Method? = null
    private var getEntryLegacy: Method? = null

    // The notification pipeline's own group model — see [pipelineGroupedOf].
    private var groupEntryClass: Class<*>? = null
    private var getParentEntry: Method? = null   // PipelineEntry.getParent()
    private var getGroupSummary: Method? = null  // GroupEntry.getSummary()

    // Fields
    var fIsHeadsUp: Field? = null; private set
    var fOnKeyguard: Field? = null; private set
    var fUserExpanded: Field? = null; private set
    var fHasUserChangedExpansion: Field? = null; private set
    var fIsSystemExpanded: Field? = null; private set
    var fChildrenExpanded: Field? = null; private set
    private var fChildrenContainer: Field? = null
    private var fAttachedChildren: Field? = null
    private var fPrivateLayout: Field? = null

    // NotificationContentView — the row's content, and the thing that decides which of the
    // inflated layouts (contracted / expanded / heads-up / single-line) is on screen.
    private var getVisibleType: Method? = null
    private var selectLayout: Method? = null

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
        // R8 renames the group-expansion read on some builds — measured as `isGroupExpanded$1`
        // on OxygenOS 16 because a synthetic accessor already owns the plain name.
        isGroupExpanded      = method(cls, "isGroupExpanded") ?: method(cls, "isGroupExpanded\$1")
        shouldShowPublic     = method(cls, "shouldShowPublic")
        getEntry             = method(cls, "getEntry")
        getEntryLegacy       = method(cls, "getEntryLegacy")

        fIsHeadsUp               = field(cls, "mIsHeadsUp")
        fOnKeyguard              = field(cls, "mOnKeyguard")
        fUserExpanded            = field(cls, "mUserExpanded")
        fHasUserChangedExpansion = field(cls, "mHasUserChangedExpansion")
        fIsSystemExpanded        = field(cls, "mIsSystemExpanded")
        fChildrenExpanded        = field(cls, "mChildrenExpanded")
        fChildrenContainer       = field(cls, "mChildrenContainer")
        fAttachedChildren        = fChildrenContainer?.type?.let { field(it, "mAttachedChildren") }
        fPrivateLayout           = field(cls, "mPrivateLayout")
        fPrivateLayout?.type?.let { contentView ->
            getVisibleType = method(contentView, "getVisibleType")
            selectLayout   = method(contentView, "selectLayout",
                Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
        }

        bindPipeline(classLoader)

        Log.d(TAG, "RowApi caps: $caps")
        if (!usable) Log.e(TAG, "RowApi: insufficient capabilities — v2 engine will stay off")
        return usable
    }

    /**
     * Resolves the notification pipeline's group model.
     *
     * Separate from the row surface because it is optional: without it the engine falls back to
     * reading the group key, which is what it always did. `getParent` moved from `ListEntry` up
     * to `PipelineEntry` in Android 16, so both are tried.
     */
    private fun bindPipeline(classLoader: ClassLoader) {
        val parentOwner = loadClass(classLoader, PIPELINE_ENTRY_CLASS)
            ?: loadClass(classLoader, LIST_ENTRY_CLASS) ?: return
        getParentEntry = method(parentOwner, "getParent")
        groupEntryClass = loadClass(classLoader, GROUP_ENTRY_CLASS)
        getGroupSummary = groupEntryClass?.let { method(it, "getSummary") }
    }

    private fun loadClass(classLoader: ClassLoader, name: String): Class<*>? =
        try { classLoader.loadClass(name) } catch (_: Throwable) { null }

    val caps: String
        get() = "isExpandedArg=${b(isExpandedArg)} setSysExp=${b(setSystemExpanded)} " +
                "setUserExp2=${b(setUserExpanded2)} setUserExp1=${b(setUserExpanded1)} " +
                "childInGroup=${b(isChildInGroup)} pinned=${b(isPinned)} " +
                "summaryKids=${b(isSummaryWithChildren)} entry=${b(getEntry)}/${b(getEntryLegacy)} " +
                "fHU=${b(fIsHeadsUp)} fKG=${b(fOnKeyguard)} fUserExp=${b(fUserExpanded)} " +
                "fUserChanged=${b(fHasUserChangedExpansion)} fSysExp=${b(fIsSystemExpanded)} " +
                "fKidsExp=${b(fChildrenExpanded)} groupExp=${b(isGroupExpanded)} " +
                "showPublic=${b(shouldShowPublic)} kids=${b(fChildrenContainer)}/${b(fAttachedChildren)} " +
                "content=${b(fPrivateLayout)} visType=${b(getVisibleType)} selectLayout=${b(selectLayout)} " +
                "entryParent=${b(getParentEntry)} groupSummary=${b(getGroupSummary)}"

    /** True when the group parent/children toggles have everything they need on this ROM. */
    val groupCapable: Boolean
        get() = isSummaryWithChildren != null && isGroupExpanded != null && setUserExpanded2 != null

    /** True when a stale child layout can be detected and re-selected. */
    val layoutRepairCapable: Boolean
        get() = fPrivateLayout != null && getVisibleType != null && selectLayout != null

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

    /**
     * The rows currently attached under a group summary, or empty when this is not a summary.
     *
     * Read straight off `mChildrenContainer.mAttachedChildren` rather than through
     * `getAttachedChildren()`, which several OEM builds do not declare. A defensive copy is
     * returned so the caller can expand rows without iterating SystemUI's live list.
     */
    @Suppress("UNCHECKED_CAST")
    fun attachedChildrenOf(row: Any): List<Any> = try {
        val container = fChildrenContainer?.get(row) ?: return emptyList()
        (fAttachedChildren?.get(container) as? List<Any>)?.toList() ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    /**
     * Which inflated layout the row is currently showing:
     * `0` contracted, `1` expanded, `2` heads-up, `3` single-line, `-1` unknown.
     *
     * Read off `mPrivateLayout` — the public content. A group child renders its single-line
     * layout while its group is closed, so this is how a child left on the wrong layout after
     * the group opened can be told apart from one that is legitimately one line.
     */
    fun visibleTypeOf(row: Any): Int = try {
        val content = fPrivateLayout?.get(row) ?: return -1
        (getVisibleType?.invoke(content) as? Int) ?: -1
    } catch (_: Throwable) { -1 }

    /**
     * Re-runs the row's own layout selection (`selectLayout(animate = false, force = true)`).
     *
     * This is SystemUI's own primitive for "the situation changed, work out what to show":
     * it calls `calculateVisibleType()` and applies the answer. It sets no height and no
     * expansion state, so it cannot fight the engine or the user — on a row that is already
     * correct it recomputes the same type and does nothing.
     */
    fun reselectLayout(row: Any): Boolean = try {
        val m = selectLayout ?: return false
        val content = fPrivateLayout?.get(row) ?: return false
        m.invoke(content, false, true)
        true
    } catch (_: Throwable) { false }

    /**
     * Whether the notification pipeline draws this row *inside a group* — SystemUI's own answer,
     * not the app's.
     *
     * The group key only says the app asked for grouping. It does not say a group was formed:
     * `ShadeListBuilder` keeps a group only while it has a summary, and promotes a lone child
     * back to the top level. Instagram gives every DM thread its own group key and posts no
     * summary, so each notification carries `|g:…` while rendering as an ordinary single card —
     * and the group-key test alone classified it as a group child and left it collapsed.
     *
     * Mirrors `GroupMembershipManagerImpl.getGroupSummary` as read out of this device's dex:
     * a row is a group child exactly when its parent is a `GroupEntry` holding a summary.
     * `GroupEntry.ROOT_ENTRY` — the top level — never has one, so it needs no special case.
     *
     * This is the *model*, set by the pipeline before the views are bound, which is why it is
     * trustworthy on the pass where the view-level `isChildInGroup()` is still false.
     *
     * Returns `null` for "cannot tell" — the handles are missing, the entry is not attached to
     * the pipeline yet, or the parent is some other shape (an Android 16 `BundleEntry`). The
     * caller falls back to the group key there, which is the previous behaviour.
     */
    fun pipelineGroupedOf(row: Any): Boolean? = try {
        val getParent = getParentEntry
        val groupEntry = groupEntryClass
        val getSummary = getGroupSummary
        if (getParent == null || groupEntry == null || getSummary == null) null
        else {
            val parent = entryOf(row)?.let { getParent.invoke(it) }
            when {
                parent == null -> null
                !groupEntry.isInstance(parent) -> null
                else -> getSummary.invoke(parent) != null
            }
        }
    } catch (_: Throwable) { null }

    fun entryOf(row: Any): Any? =
        try { getEntry?.invoke(row) } catch (_: Throwable) { null }
            ?: try { getEntryLegacy?.invoke(row) } catch (_: Throwable) { null }

    fun sbnOf(row: Any): Any? = try {
        val e = entryOf(row) ?: return null
        e.javaClass.getMethod("getSbn").invoke(e)
    } catch (_: Throwable) { null }
}
