package io.github.kvmy666.autoexpand.hook.notif

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The one place that reflects into `ExpandableNotificationRow`.
 *
 * Every handle is resolved **once per process** at install and cached. A missing handle means
 * "this ROM doesn't have that capability" — it never turns into a throw at call time, and never
 * into a silent per-call `try/catch` that hides a systematic problem behind 800 swallowed
 * exceptions. [caps] is logged once so an unsupported ROM is visible immediately; it is the
 * single line to ask a Xiaomi user for.
 *
 * Names come from [HookTargets] and [HookTargetResolver], not from string literals here: exact
 * AOSP/OEM names first, then a superclass walk, then a conservative shape match. Fields are
 * never shape-matched — a wrong boolean field is worse than a missing capability.
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
    /** vivo OriginOS 6 declares only `isExpanded$1()Z` (verified); read-only fallback. */
    var isExpandedNoArg: Method? = null; private set
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
    /** Field fallback: HyperOS 3 declares only `GroupEntry.mSummary` (verified). */
    private var fGroupSummary: Field? = null
    /** The static top-level `GroupEntry` — anything parented to it is not inside a group. */
    private var rootEntry: Any? = null

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
    private var fExpandedChild: Field? = null
    private var fExpandClickListener: Field? = null

    // NotificationContentView — the row's content, and the thing that decides which of the
    // inflated layouts (contracted / expanded / heads-up / single-line) is on screen.
    private var getVisibleType: Method? = null
    private var selectLayout: Method? = null

    @Volatile private var bound = false

    /** True when enough of the surface resolved that the v2 engine can do its job at all. */
    val usable: Boolean
        get() = rowClass != null && (isExpandedArg != null || isExpandedNoArg != null) &&
                (setSystemExpanded != null || setUserExpanded2 != null || setUserExpanded1 != null)

    /**
     * True when the row class itself resolved.
     *
     * The install gate uses this instead of [usable]: a ROM without `isExpanded(boolean)` can
     * still run the heads-up and group paths, so it must not lose the whole engine over one
     * missing capability.
     */
    val coreCapable: Boolean get() = rowClass != null

    /** True when the lock-screen gate can be installed. */
    val lockscreenCapable: Boolean get() = isExpandedArg != null

    fun bind(classLoader: ClassLoader): Boolean {
        if (bound) return coreCapable
        bound = true
        val cls = HookTargets.row(classLoader) ?: run {
            Log.e(TAG, "RowApi: row class not found on ${OemDetector.current.name} — v2 engine disabled")
            return false
        }
        rowClass = cls

        isExpandedArg        = m(cls, listOf("isExpanded"), listOf(Boolean::class.javaPrimitiveType!!),
                                 Boolean::class.javaPrimitiveType!!, listOf("isexpanded"))
        if (isExpandedArg == null) {
            isExpandedNoArg = m(cls, listOf("isExpanded\$1"), emptyList(),
                                Boolean::class.javaPrimitiveType!!, listOf("isexpanded"))
        }
        setUserExpanded2     = m(cls, listOf("setUserExpanded"),
                                 listOf(Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
                                 Void.TYPE, listOf("setuserexpanded"))
        setUserExpanded1     = m(cls, listOf("setUserExpanded"),
                                 listOf(Boolean::class.javaPrimitiveType!!), Void.TYPE, listOf("setuserexpanded"))
        setSystemExpanded    = m(cls, listOf("setSystemExpanded"),
                                 listOf(Boolean::class.javaPrimitiveType!!), Void.TYPE, listOf("setsystemexpanded"))
        isChildInGroup       = m(cls, listOf("isChildInGroup"), emptyList(), Boolean::class.javaPrimitiveType!!,
                                 listOf("ischildingroup"))
        isPinned             = m(cls, listOf("isPinned"), emptyList(), Boolean::class.javaPrimitiveType!!,
                                 listOf("ispinned"))
        isSummaryWithChildren = m(cls, listOf("isSummaryWithChildren"), emptyList(),
                                  Boolean::class.javaPrimitiveType!!, listOf("issummarywithchildren"))
        // R8 renames the group-expansion read on some builds — measured as `isGroupExpanded$1`
        // on OxygenOS 16 because a synthetic accessor already owns the plain name.
        isGroupExpanded      = m(cls, listOf("isGroupExpanded", "isGroupExpanded\$1"), emptyList(),
                                 Boolean::class.javaPrimitiveType!!, listOf("isgroupexpanded"))
        shouldShowPublic     = m(cls, listOf("shouldShowPublic"), emptyList(),
                                 Boolean::class.javaPrimitiveType!!, listOf("shouldshowpublic"))
        getEntry             = m(cls, listOf("getEntry"), emptyList(), null, listOf("getentry"))
        getEntryLegacy       = m(cls, listOf("getEntryLegacy"), emptyList(), null, listOf("getentrylegacy"))

        fIsHeadsUp               = f(cls, listOf("mIsHeadsUp"))
        fOnKeyguard              = f(cls, listOf("mOnKeyguard"))
        fUserExpanded            = f(cls, listOf("mUserExpanded"))
        fHasUserChangedExpansion = f(cls, listOf("mHasUserChangedExpansion"))
        fIsSystemExpanded        = f(cls, listOf("mIsSystemExpanded"))
        fChildrenExpanded        = f(cls, listOf("mChildrenExpanded"))
        fChildrenContainer       = f(cls, listOf("mChildrenContainer"))
        fAttachedChildren        = fChildrenContainer?.type?.let { f(it, listOf("mAttachedChildren")) }
        fPrivateLayout           = f(cls, listOf("mPrivateLayout"))
        fExpandClickListener     = f(cls, listOf("mExpandClickListener"))

        // Layout repair is optional, but a renamed content field should still resolve: the
        // content view type is unique on the row, so a type + "private" match is safe.
        if (fPrivateLayout == null) {
            HookTargets.contentView(classLoader)?.let { content ->
                fPrivateLayout = HookTargetResolver.findUniqueFieldOfType(cls, content, "private")
            }
        }
        fPrivateLayout?.type?.let { contentView ->
            fExpandedChild = HookTargetResolver.findField(contentView, listOf("mExpandedChild"))
            getVisibleType = m(contentView, listOf("getVisibleType"), emptyList(), Int::class.javaPrimitiveType!!,
                               listOf("getvisibletype"))
            selectLayout   = m(contentView, listOf("selectLayout"),
                               listOf(Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
                               Void.TYPE, listOf("selectlayout"))
        }

        bindPipeline(classLoader)

        Log.d(TAG, "RowApi caps: $caps")
        if (!usable) Log.e(TAG, "RowApi: core capabilities missing — v2 will install with the " +
                              "capabilities that did resolve (see caps line)")
        return coreCapable
    }

    /** Exact-name / hierarchy / shape resolution through [HookTargetResolver]. */
    private fun m(
        cls: Class<*>,
        names: List<String>,
        params: List<Class<*>>,
        ret: Class<*>?,
        hints: List<String>,
    ): Method? = HookTargetResolver.findMethod(cls, names, params, ret, hints)

    /** Field resolution stays exact-name only — see [HookTargetResolver]. */
    private fun f(cls: Class<*>, names: List<String>): Field? =
        HookTargetResolver.findField(cls, names)

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
        getParentEntry = HookTargetResolver.findMethod(
            parentOwner, listOf("getParent"), emptyList(), null, listOf("getparent")
        )
        groupEntryClass = loadClass(classLoader, GROUP_ENTRY_CLASS)
        groupEntryClass?.let { cls ->
            rootEntry = resolveRootEntry(cls)
            getGroupSummary = HookTargetResolver.findMethod(
                cls, listOf("getSummary"), emptyList(), null, listOf("getsummary")
            )
            // HyperOS 3 has no getSummary at all; the pipeline still stores the summary in
            // `mSummary`. Verified from the pulled Xiaomi SystemUI dex.
            if (getGroupSummary == null) {
                fGroupSummary = HookTargetResolver.findField(cls, listOf("mSummary"))
            }
        }
    }

    /**
     * Reads the static root `GroupEntry` by type rather than by field name.
     *
     * `ROOT_ENTRY` is the AOSP name but R8 is free to rename it, so the type is the reliable
     * discriminator: a static field of the entry's own type. The name is only a preference.
     */
    private fun resolveRootEntry(cls: Class<*>): Any? = try {
        val candidates = cls.declaredFields.filter {
            Modifier.isStatic(it.modifiers) && cls.isAssignableFrom(it.type)
        }
        val f = candidates.firstOrNull { it.name == "ROOT_ENTRY" } ?: candidates.firstOrNull()
        f?.apply { isAccessible = true }?.get(null)
    } catch (_: Throwable) { null }

    private fun loadClass(classLoader: ClassLoader, name: String): Class<*>? =
        HookTargetResolver.load(classLoader, name)

    val caps: String
        get() = "oem=${OemDetector.current.name} " +
                "row=${rowClass?.name?.substringAfterLast('.') ?: "none"} " +
                "isExpandedArg=${b(isExpandedArg)} isExpNoArg=${b(isExpandedNoArg)} " +
                "setSysExp=${b(setSystemExpanded)} " +
                "setUserExp2=${b(setUserExpanded2)} setUserExp1=${b(setUserExpanded1)} " +
                "childInGroup=${b(isChildInGroup)} pinned=${b(isPinned)} " +
                "summaryKids=${b(isSummaryWithChildren)} entry=${b(getEntry)}/${b(getEntryLegacy)} " +
                "fHU=${b(fIsHeadsUp)} fKG=${b(fOnKeyguard)} fUserExp=${b(fUserExpanded)} " +
                "fUserChanged=${b(fHasUserChangedExpansion)} fSysExp=${b(fIsSystemExpanded)} " +
                "fKidsExp=${b(fChildrenExpanded)} groupExp=${b(isGroupExpanded)} " +
                "showPublic=${b(shouldShowPublic)} kids=${b(fChildrenContainer)}/${b(fAttachedChildren)} " +
                "content=${b(fPrivateLayout)} visType=${b(getVisibleType)} selectLayout=${b(selectLayout)} " +
                "entryParent=${b(getParentEntry)} groupSummary=${b(getGroupSummary)}/${b(fGroupSummary)} " +
                "rootEntry=${b(rootEntry)} arrow=${b(fExpandClickListener)}"

    /** True when the group parent/children toggles have everything they need on this ROM. */
    val groupCapable: Boolean
        get() = isSummaryWithChildren != null && isGroupExpanded != null && setUserExpanded2 != null

    /** True when a stale child layout can be detected and re-selected. */
    val layoutRepairCapable: Boolean
        get() = fPrivateLayout != null && getVisibleType != null && selectLayout != null

    private fun b(o: Any?) = if (o != null) 1 else 0

    // ── typed accessors ──────────────────────────────────────────────────────

    fun bool(f: Field?, row: Any, default: Boolean = false): Boolean =
        try { if (f == null) default else f.getBoolean(row) } catch (_: Throwable) { default }

    fun callBool(m: Method?, row: Any, vararg args: Any?, default: Boolean = false): Boolean =
        try { if (m == null) default else (m.invoke(row, *args) as? Boolean) ?: default }
        catch (_: Throwable) { default }

    /**
     * `isExpanded(allowOnKeyguard = true)` — the honest read.
     *
     * vivo OriginOS 6 has no boolean overload at all, only the synthetic `isExpanded$1()`; the
     * read path uses that when present. The lock-screen *hook* still requires the boolean form,
     * because that is the method SystemUI calls.
     */
    fun isExpandedUngated(row: Any): Boolean =
        if (isExpandedArg != null) callBool(isExpandedArg, row, true)
        else callBool(isExpandedNoArg, row)

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

    /** The row's private (user-facing) content view, or null when the capability is missing. */
    fun privateLayoutOf(row: Any): Any? =
        try { fPrivateLayout?.get(row) } catch (_: Throwable) { null }

    /** The content view's expanded child (the view whose measured height is the HUD height). */
    fun expandedChildOf(content: Any): android.view.View? =
        try { fExpandedChild?.get(content) as? android.view.View } catch (_: Throwable) { null }

    /**
     * Drives the row's own expand arrow — SystemUI's real listener, not a state rewrite.
     *
     * [fExpandClickListener] is the view's own `OnExpandClickListener`; its `onClick` is what a
     * tap on the arrow runs, so it keeps every decision (summary vs single, grouped vs not) and
     * every side effect (`StatusBarNotificationPresenter.onExpandClicked`) inside SystemUI.
     * Returns false when the field or the view is missing, never throws.
     */
    fun clickExpand(row: Any): Boolean {
        val listener = try { fExpandClickListener?.get(row) } catch (t: Throwable) { null } ?: return false
        val view = row as? android.view.View ?: return false
        return try {
            (listener as android.view.View.OnClickListener).onClick(view)
            true
        } catch (t: Throwable) {
            NotifLog.d { "arrow click failed: ${t.javaClass.simpleName}" }
            false
        }
    }

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
     * `GroupEntry.ROOT_ENTRY` — the top level — is never a group, so a row parented to it is
     * unambiguously a single.
     *
     * This is the *model*, set by the pipeline before the views are bound, which is why it is
     * trustworthy on the pass where the view-level `isChildInGroup()` is still false.
     *
     * Returns `null` for "cannot tell" — the handles are missing, the entry is not attached to
     * the pipeline yet, the parent is some other shape (an Android 16 `BundleEntry`), or the
     * parent is a non-root `GroupEntry` whose summary has not attached yet. That last case is
     * why this must not answer `false`: forming-group rows would look like singles and be
     * expanded before SystemUI pins them. The caller falls back to the group key there.
     */
    fun pipelineGroupedOf(row: Any): Boolean? = try {
        val getParent = getParentEntry
        val groupEntry = groupEntryClass
        if (getParent == null || groupEntry == null) null
        else {
            val parent = entryOf(row)?.let { getParent.invoke(it) }
            when {
                parent == null -> null
                !groupEntry.isInstance(parent) -> null
                rootEntry != null && parent === rootEntry -> false
                summaryPresent(parent) -> true
                // Non-root group whose summary has not attached yet: unknown, not "single".
                else -> null
            }
        }
    } catch (_: Throwable) { null }

    /**
     * Whether a group entry currently carries a summary.
     *
     * AOSP/OxygenOS expose `getSummary()`; HyperOS 3 only has the `mSummary` field. Either one
     * counts — the caller only needs "is a summary attached", not which accessor answered.
     */
    private fun summaryPresent(parent: Any): Boolean = try {
        (getGroupSummary?.invoke(parent) != null) || (fGroupSummary?.get(parent) != null)
    } catch (_: Throwable) { false }

    fun entryOf(row: Any): Any? =
        try { getEntry?.invoke(row) } catch (_: Throwable) { null }
            ?: try { getEntryLegacy?.invoke(row) } catch (_: Throwable) { null }

    fun sbnOf(row: Any): Any? = try {
        val e = entryOf(row) ?: return null
        e.javaClass.getMethod("getSbn").invoke(e)
    } catch (_: Throwable) { null }
}
