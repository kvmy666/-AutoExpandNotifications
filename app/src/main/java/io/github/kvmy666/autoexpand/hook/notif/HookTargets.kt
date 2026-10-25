package io.github.kvmy666.autoexpand.hook.notif

/**
 * The SystemUI classes the module hooks, as candidate lists per OEM.
 *
 * The AOSP name is always first. OEM names after it are tried only when the AOSP one does not
 * load, which is what keeps the measured OxygenOS path byte-for-byte identical.
 *
 * **Every non-AOSP name in here must come from a decompiled APK.** There are currently none:
 * the earlier MiuiExpandableNotificationRow / SecExpandableNotificationRow entries were removed
 * because they were guesses from the design note, not evidence. A ROM whose row class is renamed
 * is handled by [DexKitBackend] searching for classes whose name still contains
 * `ExpandableNotificationRow`; a fully renamed class stays unsupported and reports the missing
 * capability in `RowApi caps` instead of being hooked blindly.
 */
object HookTargets {

    private const val ROW_PKG = "com.android.systemui.statusbar.notification.row"
    private const val PHONE_PKG = "com.android.systemui.statusbar.phone"
    private const val HU_PKG = "com.android.systemui.statusbar.notification.headsup"
    private const val COLL_PKG = "com.android.systemui.statusbar.notification.collection"

    // ── required core ────────────────────────────────────────────────────────

    /** The row view. Parent of everything the shade engine writes. */
    fun row(classLoader: ClassLoader): Class<*>? =
        HookTargetResolver.findClass(classLoader, "row", listOf("$ROW_PKG.ExpandableNotificationRow"))

    /** The row's contracted/expanded heads-up content; decides which layout is visible. */
    fun contentView(classLoader: ClassLoader): Class<*>? =
        HookTargetResolver.findClass(classLoader, "contentView",
            listOf("$ROW_PKG.NotificationContentView"))

    /**
     * The group's child container.
     *
     * Measured on `CPH2747_16.0.9.400` (`dbe67fd3…SystemUI.apk`): the class is at
     * `com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer` and
     * declares `addNotification(ExpandableNotificationRow, int)`. It is **not** under
     * `…notification.row`; that older guess never loaded, so Engine 1's `addNotification` hook
     * silently bound nothing on this build. The pre-stack AOSP location is kept for old ROMs.
     */
    fun childrenContainer(classLoader: ClassLoader): Class<*>? =
        HookTargetResolver.findClass(classLoader, "childrenContainer", listOf(
            "com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer",
            "com.android.systemui.statusbar.NotificationChildrenContainer",
        ))

    // ── heads-up ─────────────────────────────────────────────────────────────

    fun headsUpManager(classLoader: ClassLoader): Class<*>? =
        HookTargetResolver.findClass(classLoader, "headsUpManager", listOf("$HU_PKG.HeadsUpManagerImpl"))

    fun presenter(classLoader: ClassLoader): Class<*>? =
        HookTargetResolver.findClass(classLoader, "presenter",
            listOf("$PHONE_PKG.StatusBarNotificationPresenter"))

    fun activityStarter(classLoader: ClassLoader): Class<*>? =
        HookTargetResolver.findClass(classLoader, "activityStarter",
            listOf("$PHONE_PKG.StatusBarNotificationActivityStarter"))

    // ── optional ─────────────────────────────────────────────────────────────

    fun groupExpansionManager(classLoader: ClassLoader): Class<*>? =
        HookTargetResolver.findClass(classLoader, "groupExpansion",
            listOf("$COLL_PKG.render.GroupExpansionManagerImpl"))

    /** Framework class, not SystemUI — present on every ROM. */
    fun messagingText(classLoader: ClassLoader): Class<*>? =
        HookTargetResolver.findClass(classLoader, "messagingText",
            listOf("com.android.internal.widget.MessagingTextMessage"))

    /** OxygenOS-only swipe handler; absent elsewhere by design. */
    fun oplusTouchHelper(classLoader: ClassLoader): Class<*>? {
        if (OemDetector.current != Oem.OPLUS) return null
        return HookTargetResolver.findClass(classLoader, "oplusTouchHelper",
            listOf("com.oplus.systemui.notification.headsup.windowframe.OplusHeadsUpTouchHelper"))
    }
}
