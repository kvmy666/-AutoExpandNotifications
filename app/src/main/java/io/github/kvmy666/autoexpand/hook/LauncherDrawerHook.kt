package io.github.kvmy666.autoexpand.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Insets
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowInsetsAnimationController
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * OnePlus Launcher drawer search tweaks.
 *
 * Hooks are intentionally reflection-heavy: the launcher classes are OEM-private and may be
 * absent on non-OnePlus ROMs. Every hook is best-effort and gated by opt-in prefs.
 */
class LauncherDrawerHook(private val prefs: PrefsBridge) {

    private val TAG = "TweaksLauncher"
    private val OPEN_KEY = "launcher_drawer_keyboard_enabled"
    private val SYNC_KEY = "launcher_drawer_ime_sync_enabled"
    private val ENTER_KEY = "launcher_drawer_enter_launch_enabled"
    private val AUTO_LAUNCH_SINGLE_KEY = "launcher_drawer_auto_launch_single_enabled"
    private val KEEP_DRAWER_LAYOUT_KEY = "launcher_drawer_keep_layout_until_query_enabled"
    private val REOPEN_AT_TOP_KEY = "launcher_drawer_reopen_search_at_top_enabled"

    @Volatile private var launcherActivity: Activity? = null
    @Volatile private var searchEditRef: java.lang.ref.WeakReference<View>? = null
    @Volatile private var imeSyncUntil = 0L
    @Volatile private var lastImeAnimationHeight = 0
    @Volatile private var lastAutoOpenTs = 0L
    @Volatile private var lastEnterLaunchTs = 0L
    @Volatile private var lastSingleAutoLaunchQuery: String? = null
    @Volatile private var lastSingleAutoLaunchTs = 0L
    @Volatile private var keepLayoutSearchLayout: java.lang.ref.WeakReference<View>? = null
    @Volatile private var keepLayoutTextWatcherInstalled = false
    @Volatile private var keepLayoutImeWasVisible = false

    private val hookedIcClasses = java.util.Collections.synchronizedSet(HashSet<String>())

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookApplicationContext(lpparam)
        hookLauncherLifecycle(lpparam)
        hookDrawerTransition(lpparam)
        hookDrawerOpenEnd(lpparam)
        hookBranchKeyboardPath(lpparam)
        hookSearchInputConnectionDiscovery()
        hookSearchResults(lpparam)
        hookBackForKeepLayout(lpparam)
        hookDrawerRecyclerScroll(lpparam)
        hookNativeSearchExitPaths(lpparam)
        hookImeProgress(lpparam)
        hookControlledImeProgress(lpparam)
        Log.d(TAG, "launcher drawer hook install attempted in ${lpparam.packageName}; ${prefSummary()}")
    }

    private fun hookApplicationContext(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val app = param.thisObject as? Application ?: return
                            if (prefs.appContext == null) {
                                prefs.appContext = app
                            }
                            prefs.loadFilePrefs()
                            Log.d(TAG, "launcher app context captured; ${prefSummary()}")
                        } catch (t: Throwable) {
                            Log.d(TAG, "launcher app context hook err: $t")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            Log.d(TAG, "launcher app context hook install failed: $t")
        }
    }

    private fun hookLauncherLifecycle(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (clsName in listOf("com.android.launcher.Launcher", "com.android.launcher3.Launcher")) {
            try {
                val cls = XposedHelpers.findClass(clsName, lpparam.classLoader)
                XposedBridge.hookAllMethods(cls, "onResume", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.thisObject as? Activity)?.let { activity ->
                            launcherActivity = activity
                            if (prefs.appContext == null) {
                                prefs.appContext = activity.applicationContext
                            }
                            prefs.loadFilePrefs()
                            Log.d(TAG, "launcher activity resumed (${activity.javaClass.name}); ${prefSummary()}")
                        }
                    }
                })
                XposedBridge.hookAllMethods(cls, "onPause", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (launcherActivity === param.thisObject) launcherActivity = null
                    }
                })
            } catch (t: Throwable) {
                Log.d(TAG, "launcher lifecycle hook install failed ($clsName): $t")
            }
        }
    }

    private fun hookDrawerTransition(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.launcher3.allapps.OplusAllAppsTransitionController",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(cls, "setStateWithAnimation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val state = param.args.getOrNull(0) ?: return
                    if (isAllAppsState(state, lpparam.classLoader)) {
                        Log.d(TAG, "setStateWithAnimation -> ALL_APPS; ${prefSummary()}")
                        if (prefs.isOptInEnabled(SYNC_KEY)) imeSyncUntil = SystemClock.uptimeMillis() + 2500L
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val state = param.args.getOrNull(0) ?: return
                        if (!isAllAppsState(state, lpparam.classLoader)) return
                        if (!prefs.isOptInEnabled(OPEN_KEY)) {
                            Log.d(TAG, "drawer keyboard disabled for setStateWithAnimation")
                            return
                        }
                        val launcher = XposedHelpers.getObjectField(param.thisObject, "mLauncher") as? Activity
                            ?: launcherActivity
                            ?: return
                        scheduleOpenDrawerSearch(launcher, 24L)
                    } catch (t: Throwable) {
                        Log.d(TAG, "drawer setStateWithAnimation after err: $t")
                    }
                }
            })
            Log.d(TAG, "hooked OplusAllAppsTransitionController.setStateWithAnimation")
            XposedBridge.hookAllMethods(cls, "setState", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val state = param.args.getOrNull(0) ?: return
                        if (!isAllAppsState(state, lpparam.classLoader)) return
                        Log.d(TAG, "setState -> ALL_APPS; ${prefSummary()}")
                        if (prefs.isOptInEnabled(SYNC_KEY)) imeSyncUntil = SystemClock.uptimeMillis() + 2500L
                        if (!prefs.isOptInEnabled(OPEN_KEY)) return
                        val launcher = XposedHelpers.getObjectField(param.thisObject, "mLauncher") as? Activity
                            ?: launcherActivity
                            ?: return
                        scheduleOpenDrawerSearch(launcher, 40L)
                    } catch (t: Throwable) {
                        Log.d(TAG, "drawer setState after err: $t")
                    }
                }
            })
            Log.d(TAG, "hooked OplusAllAppsTransitionController.setState")
        } catch (t: Throwable) {
            Log.d(TAG, "drawer transition hook failed: $t")
        }

        try {
            val cls = XposedHelpers.findClass(
                "com.android.launcher3.statemanager.StateManager",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(cls, "onStateTransitionEnd", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val state = param.args.getOrNull(0) ?: return
                        if (!isAllAppsState(state, lpparam.classLoader)) return
                        Log.d(TAG, "StateManager transition end -> ALL_APPS; ${prefSummary()}")
                        if (!prefs.isOptInEnabled(OPEN_KEY)) return
                        val launcher = XposedHelpers.getObjectField(param.thisObject, "mActivity") as? Activity
                            ?: launcherActivity
                            ?: return
                        scheduleOpenDrawerSearch(launcher, 0L)
                    } catch (t: Throwable) {
                        Log.d(TAG, "state transition end err: $t")
                    }
                }
            })
        } catch (t: Throwable) {
            Log.d(TAG, "state transition end hook failed: $t")
        }
    }

    private fun hookDrawerOpenEnd(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.launcher3.allapps.OplusLauncherAllAppsContainerView",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(cls, "onScrollUpEnd", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val launcher = (runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "mActivityContext") as? Activity
                        }.getOrNull()) ?: launcherActivity
                        Log.d(TAG, "all apps onScrollUpEnd; launcher=${launcher?.javaClass?.name}; ${prefSummary()}")
                        if (prefs.isOptInEnabled(SYNC_KEY)) imeSyncUntil = SystemClock.uptimeMillis() + 2500L
                        if (!prefs.isOptInEnabled(OPEN_KEY) || launcher == null) return
                        scheduleOpenDrawerSearch(launcher, 80L)
                    } catch (t: Throwable) {
                        Log.d(TAG, "drawer onScrollUpEnd hook err: $t")
                    }
                }
            })
            Log.d(TAG, "hooked OplusLauncherAllAppsContainerView.onScrollUpEnd")
        } catch (t: Throwable) {
            Log.d(TAG, "drawer onScrollUpEnd hook failed: $t")
        }
    }

    private fun hookBranchKeyboardPath(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.launcher3.allapps.branch.BranchManager",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(cls, "showKeyboardInDrawerPage", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        Log.d(TAG, "BranchManager.showKeyboardInDrawerPage; ${prefSummary()}")
                        if (prefs.isOptInEnabled(SYNC_KEY)) imeSyncUntil = SystemClock.uptimeMillis() + 2500L
                    } catch (t: Throwable) {
                        Log.d(TAG, "branch keyboard path hook err: $t")
                    }
                }
            })
            Log.d(TAG, "hooked BranchManager.showKeyboardInDrawerPage")
        } catch (t: Throwable) {
            Log.d(TAG, "branch keyboard path hook failed: $t")
        }
    }

    private fun scheduleOpenDrawerSearch(launcher: Activity, delayMs: Long) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAutoOpenTs < 700L) return
        lastAutoOpenTs = now
        launcher.window?.decorView?.postDelayed({
            try {
                openDrawerSearch(launcher)
            } catch (t: Throwable) {
                Log.d(TAG, "openDrawerSearch scheduled err: $t")
            }
        }, delayMs)
    }

    private fun openDrawerSearch(launcher: Activity): Boolean {
        val searchLayout = getSearchLayout(launcher) ?: run {
            Log.d(TAG, "openDrawerSearch: search layout not found")
            return false
        }
        if (prefs.isOptInEnabled(SYNC_KEY)) imeSyncUntil = SystemClock.uptimeMillis() + 2500L
        val keepLayout = prefs.isOptInEnabled(KEEP_DRAWER_LAYOUT_KEY)
        val opened = if (keepLayout) {
            prepareFocusedSearchField(searchLayout, enterSearchMode = false)
        } else {
            (tryCall(searchLayout, "onSearchBarClickInternal")
                || tryCall(searchLayout, "showKeyboard")
                || tryCall(searchLayout, "tryControlWindowInsetsAnimation", true, true, null, null)
                || tryCall(searchLayout, "onSearchBarClick"))
                .also { prepareFocusedSearchField(searchLayout, enterSearchMode = true) }
        }
        Log.d(TAG, "openDrawerSearch: layout=${searchLayout.javaClass.name}, opened=$opened")
        logDrawerDiag(searchLayout, "after-openDrawerSearch")
        return opened
    }

    private fun hookSearchInputConnectionDiscovery() {
        try {
            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "onCreateInputConnection",
                android.view.inputmethod.EditorInfo::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (!prefs.isOptInEnabled(ENTER_KEY)) return
                            val tv = param.thisObject as? TextView ?: return
                            if (!isLauncherDrawerSearchField(tv)) return
                            searchEditRef = java.lang.ref.WeakReference(tv)
                            launcherActivity = activityOf(tv) ?: launcherActivity
                            val ic = param.result ?: return
                            hookSearchInputConnection(ic.javaClass)
                        } catch (t: Throwable) {
                            Log.d(TAG, "launcher onCreateIC hook err: $t")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            Log.d(TAG, "launcher onCreateIC hook install failed: $t")
        }
        try {
            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "onEditorAction",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!prefs.isOptInEnabled(ENTER_KEY)) return
                            val tv = param.thisObject as? TextView ?: return
                            if (!isLauncherDrawerSearchField(tv)) return
                            val launcher = activityOf(tv) ?: launcherActivity ?: return
                            if (launchFirstSearchResultDebounced(launcher)) param.result = true
                        } catch (t: Throwable) {
                            Log.d(TAG, "launcher TextView.onEditorAction err: $t")
                        }
                    }
                }
            )
            Log.d(TAG, "hooked TextView.onEditorAction for launcher drawer search")
        } catch (t: Throwable) {
            Log.d(TAG, "launcher TextView.onEditorAction hook install failed: $t")
        }
    }

    private fun hookSearchResults(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classNames = listOf(
            "com.android.launcher3.allapps.search.LauncherAppsSearchContainerLayout",
            "com.android.launcher3.allapps.search.LauncherTaskbarAppsSearchContainerLayout"
        )
        for (className in classNames) {
            try {
                val cls = XposedHelpers.findClass(className, lpparam.classLoader)
                XposedBridge.hookAllMethods(cls, "onSearchResult", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!prefs.isOptInEnabled(KEEP_DRAWER_LAYOUT_KEY)) return
                            val query = param.args.getOrNull(0) as? String ?: return
                            if (query.isBlank()) return
                            val searchLayout = param.thisObject as? View ?: return
                            ensureSearchModeForQuery(searchLayout)
                        } catch (t: Throwable) {
                            Log.d(TAG, "search result pre-hook err: $t")
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val query = param.args.getOrNull(0) as? String ?: return
                            val searchLayout = param.thisObject as? View
                            if (query.isBlank()) {
                                if (searchLayout != null) restoreDrawerLayoutIfQueryEmpty(searchLayout)
                                return
                            }
                            if (searchLayout != null) showSearchResultsLayout(searchLayout)
                            if (!prefs.isOptInEnabled(AUTO_LAUNCH_SINGLE_KEY)) return
                            val launcher = searchLayout?.let { activityOf(it) } ?: launcherActivity ?: return
                            val results = param.args.getOrNull(1) as? List<*> ?: return
                            launchSingleSearchResultNow(launcher, query, results)
                        } catch (t: Throwable) {
                            Log.d(TAG, "search result post-hook err: $t")
                        }
                    }
                })
                Log.d(TAG, "hooked $className.onSearchResult")
            } catch (t: Throwable) {
                Log.d(TAG, "search result hook failed ($className): $t")
            }
        }
    }

    private fun hookDrawerRecyclerScroll(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.launcher3.allapps.OplusAllAppsRecyclerView",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(cls, "onScrolled", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (!prefs.isOptInEnabled(KEEP_DRAWER_LAYOUT_KEY)) return
                        val recycler = param.thisObject as? View ?: return
                        val dy = param.args.getOrNull(1) as? Int ?: return
                        val launcher = activityOf(recycler) ?: launcherActivity ?: return
                        val searchLayout = getSearchLayout(launcher) ?: return
                        val edit = getSearchEditText(searchLayout)
                        val hasSearchFocus = edit?.hasFocus() == true
                        val hasQuery = !edit?.text?.toString().isNullOrBlank()
                        if (dy > 0 && (hasSearchFocus || hasQuery || isImeVisible(searchLayout))) {
                            Log.d(TAG, "diag scroll-exit dy=$dy focus=$hasSearchFocus hasQuery=$hasQuery ime=${isImeVisible(searchLayout)}")
                            logDrawerDiag(searchLayout, "before-scroll-exit")
                            exitKeepLayoutSearch(searchLayout, clearQuery = false, clearFocus = true)
                            return
                        }
                        if (dy < 0 && prefs.isOptInEnabled(REOPEN_AT_TOP_KEY) && isAtTop(recycler) && !hasSearchFocus) {
                            prepareFocusedSearchField(searchLayout, enterSearchMode = false)
                        }
                    } catch (t: Throwable) {
                        Log.d(TAG, "drawer recycler scroll hook err: $t")
                    }
                }
            })
            Log.d(TAG, "hooked OplusAllAppsRecyclerView.onScrolled")
        } catch (t: Throwable) {
            Log.d(TAG, "drawer recycler scroll hook failed: $t")
        }

        for (className in listOf(
            "com.android.launcher3.allapps.search.LauncherAppsSearchContainerLayout",
            "com.android.launcher3.allapps.search.LauncherTaskbarAppsSearchContainerLayout"
        )) {
            try {
                val cls = XposedHelpers.findClass(className, lpparam.classLoader)
                XposedBridge.hookAllMethods(cls, "onSearchRecyclerViewScroll", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!prefs.isOptInEnabled(KEEP_DRAWER_LAYOUT_KEY)) return
                            val searchLayout = param.thisObject as? View ?: return
                            Log.d(TAG, "diag searchRecyclerScroll-exit")
                            logDrawerDiag(searchLayout, "before-searchRecyclerScroll-exit")
                            exitKeepLayoutSearch(searchLayout, clearQuery = false, clearFocus = true)
                            param.result = null
                        } catch (t: Throwable) {
                            Log.d(TAG, "search recycler scroll hook err: $t")
                        }
                    }
                })
                Log.d(TAG, "hooked $className.onSearchRecyclerViewScroll")
            } catch (t: Throwable) {
                Log.d(TAG, "search recycler scroll hook failed ($className): $t")
            }
        }
    }

    private fun hookBackForKeepLayout(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (clsName in listOf("com.android.launcher.Launcher", "com.android.launcher3.Launcher")) {
            try {
                val cls = XposedHelpers.findClass(clsName, lpparam.classLoader)
                XposedBridge.hookAllMethods(cls, "dispatchKeyEvent", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!prefs.isOptInEnabled(KEEP_DRAWER_LAYOUT_KEY)) return
                            val event = param.args.getOrNull(0) as? KeyEvent ?: return
                            if (event.keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_UP) return
                            val launcher = param.thisObject as? Activity ?: launcherActivity ?: return
                            val searchLayout = getSearchLayout(launcher) ?: return
                            val edit = getSearchEditText(searchLayout) ?: return
                            if (!edit.hasFocus()) return
                            Log.d(TAG, "diag dispatchBack-exit action=${event.action}")
                            logDrawerDiag(searchLayout, "before-dispatchBack-exit")
                            exitKeepLayoutSearch(searchLayout, clearQuery = false, clearFocus = true)
                            param.result = true
                        } catch (t: Throwable) {
                            Log.d(TAG, "keep-layout back hook err: $t")
                        }
                    }
                })
                Log.d(TAG, "hooked $clsName.dispatchKeyEvent for keep-layout drawer search")
                XposedBridge.hookAllMethods(cls, "onBackPressed", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!prefs.isOptInEnabled(KEEP_DRAWER_LAYOUT_KEY)) return
                            val launcher = param.thisObject as? Activity ?: launcherActivity ?: return
                            val searchLayout = getSearchLayout(launcher) ?: return
                            if (!isKeepLayoutSearchActive(searchLayout)) return
                            Log.d(TAG, "diag onBackPressed-exit")
                            logDrawerDiag(searchLayout, "before-onBackPressed-exit")
                            exitKeepLayoutSearch(searchLayout, clearQuery = false, clearFocus = true)
                            param.result = null
                        } catch (t: Throwable) {
                            Log.d(TAG, "keep-layout onBackPressed hook err: $t")
                        }
                    }
                })
                Log.d(TAG, "hooked $clsName.onBackPressed for keep-layout drawer search")
            } catch (t: Throwable) {
                Log.d(TAG, "keep-layout back hook install failed ($clsName): $t")
            }
        }
    }

    private fun hookNativeSearchExitPaths(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (className in listOf(
            "com.android.launcher3.allapps.search.LauncherAppsSearchContainerLayout",
            "com.android.launcher3.allapps.search.LauncherTaskbarAppsSearchContainerLayout"
        )) {
            try {
                val cls = XposedHelpers.findClass(className, lpparam.classLoader)
                XposedBridge.hookAllMethods(cls, "tryControlWindowInsetsAnimation", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!prefs.isOptInEnabled(KEEP_DRAWER_LAYOUT_KEY)) return
                            val showIme = param.args.getOrNull(0) as? Boolean ?: return
                            if (showIme) return
                            val searchLayout = param.thisObject as? View ?: return
                            if (!isKeepLayoutSearchActive(searchLayout)) return
                            Log.d(TAG, "diag nativeImeHide-exit showIme=$showIme needAnimate=${param.args.getOrNull(1)}")
                            logDrawerDiag(searchLayout, "before-nativeImeHide-exit")
                            exitKeepLayoutSearch(searchLayout, clearQuery = false, clearFocus = true)
                            param.result = null
                            Log.d(TAG, "intercepted native drawer search IME hide")
                        } catch (t: Throwable) {
                            Log.d(TAG, "native search IME hide hook err: $t")
                        }
                    }
                })
                Log.d(TAG, "hooked $className.tryControlWindowInsetsAnimation for keep-layout drawer search")
                XposedBridge.hookAllMethods(cls, "onSearchBarClick", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (!prefs.isOptInEnabled(KEEP_DRAWER_LAYOUT_KEY)) return
                            val searchLayout = param.thisObject as? View ?: return
                            reactivatePreservedQuery(searchLayout)
                        } catch (t: Throwable) {
                            Log.d(TAG, "search click reactivate hook err: $t")
                        }
                    }
                })
                Log.d(TAG, "hooked $className.onSearchBarClick for preserved drawer query")
            } catch (t: Throwable) {
                Log.d(TAG, "native search IME hide hook failed ($className): $t")
            }
        }

        try {
            val cls = XposedHelpers.findClass(
                "com.android.launcher3.allapps.search.AllAppsSearchBarController",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(cls, "onBackKey", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (!prefs.isOptInEnabled(KEEP_DRAWER_LAYOUT_KEY)) return
                        val edit = searchEditRef?.get() as? EditText ?: return
                        val searchLayout = findAncestorByEntryName(edit, "search_container_all_apps") ?: return
                        if (!isKeepLayoutSearchActive(searchLayout)) return
                        Log.d(TAG, "diag searchBackKey-exit")
                        logDrawerDiag(searchLayout, "before-searchBackKey-exit")
                        exitKeepLayoutSearch(searchLayout, clearQuery = false, clearFocus = true)
                        param.result = true
                        Log.d(TAG, "handled drawer search back key")
                    } catch (t: Throwable) {
                        Log.d(TAG, "search back-key hook err: $t")
                    }
                }
            })
            Log.d(TAG, "hooked AllAppsSearchBarController.onBackKey for keep-layout drawer search")
        } catch (t: Throwable) {
            Log.d(TAG, "search back-key hook install failed: $t")
        }
    }

    private fun hookSearchInputConnection(icClass: Class<*>) {
        if (!hookedIcClasses.add(icClass.name)) return
        try {
            XposedHelpers.findAndHookMethod(
                icClass,
                "performEditorAction",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!prefs.isOptInEnabled(ENTER_KEY)) return
                            val launcher = currentLauncherActivity() ?: return
                            if (launchFirstSearchResultDebounced(launcher)) param.result = true
                        } catch (t: Throwable) {
                            Log.d(TAG, "launcher performEditorAction err: $t")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            Log.d(TAG, "launcher performEditorAction hook failed: $t")
        }
        try {
            XposedHelpers.findAndHookMethod(
                icClass,
                "sendKeyEvent",
                KeyEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!prefs.isOptInEnabled(ENTER_KEY)) return
                            val ev = param.args.getOrNull(0) as? KeyEvent ?: return
                            if (ev.action != KeyEvent.ACTION_UP) return
                            if (ev.keyCode != KeyEvent.KEYCODE_ENTER && ev.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return
                            val launcher = currentLauncherActivity() ?: return
                            if (launchFirstSearchResultDebounced(launcher)) param.result = true
                        } catch (t: Throwable) {
                            Log.d(TAG, "launcher sendKeyEvent err: $t")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            Log.d(TAG, "launcher sendKeyEvent hook failed: $t")
        }
    }

    private fun hookImeProgress(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.launcher3.allapps.search.TranslateInsetsAnimationCallback",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(cls, "onStart", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val bounds = param.args.getOrNull(1) as? WindowInsetsAnimation.Bounds ?: return
                        lastImeAnimationHeight = kotlin.math.abs(bounds.upperBound.bottom - bounds.lowerBound.bottom)
                    } catch (_: Throwable) {
                    }
                }
            })
            XposedBridge.hookAllMethods(cls, "onProgress", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val insets = param.args.getOrNull(0) as? WindowInsets ?: return
                        if (prefs.isOptInEnabled(KEEP_DRAWER_LAYOUT_KEY)) {
                            applyKeepLayoutImeOffset(insets)
                            handleKeepLayoutImeVisibilityChange(param.thisObject, insets, "onProgress")
                        }
                        if (!shouldSyncIme()) return
                        val ime = insets.getInsets(WindowInsets.Type.ime())
                        val callback = param.thisObject
                        val launcher = XposedHelpers.getObjectField(callback, "activityContext") ?: return
                        val targetY = getSearchViewTargetY(callback)
                        val height = maxOf(lastImeAnimationHeight, kotlin.math.abs(targetY).toInt(), ime.bottom - ime.top, 1)
                        syncDrawerProgressFromIme(launcher, (ime.bottom - ime.top).toFloat() / height.toFloat())
                    } catch (t: Throwable) {
                        Log.d(TAG, "IME onProgress sync err: $t")
                    }
                }
            })
            XposedBridge.hookAllMethods(cls, "onEnd", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (!prefs.isOptInEnabled(KEEP_DRAWER_LAYOUT_KEY)) return
                        val searchLayout = runCatching { XposedHelpers.getObjectField(param.thisObject, "view") as? View }.getOrNull()
                            ?: keepLayoutSearchLayout?.get()
                            ?: return
                        val imeVisible = isImeVisible(searchLayout)
                        Log.d(TAG, "diag imeAnimationEnd ime=$imeVisible active=${isKeepLayoutSearchActive(searchLayout)}")
                        if (!imeVisible && isKeepLayoutSearchActive(searchLayout)) {
                            logDrawerDiag(searchLayout, "before-imeEnd-exit")
                            exitKeepLayoutSearch(searchLayout, clearQuery = false, clearFocus = true)
                            Log.d(TAG, "diag imeEnd-exit")
                        }
                        keepLayoutImeWasVisible = imeVisible
                    } catch (t: Throwable) {
                        Log.d(TAG, "IME onEnd keep-layout err: $t")
                    }
                }
            })
        } catch (t: Throwable) {
            Log.d(TAG, "IME callback hook failed: $t")
        }
    }

    private fun hookControlledImeProgress(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.launcher3.allapps.search.AllAppsWindowInsetsAnimationControlListener",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                cls,
                "runTransition\$lambda\$1",
                WindowInsetsAnimationController::class.java,
                cls,
                Insets::class.java,
                Insets::class.java,
                XposedHelpers.findClass("com.coui.appcompat.animation.dynamicanimation.COUIDynamicAnimation", lpparam.classLoader),
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (!shouldSyncIme()) return
                            val listener = param.args.getOrNull(1) ?: return
                            val showIme = XposedHelpers.getBooleanField(listener, "showIme")
                            if (!showIme) return
                            val progress = param.args.getOrNull(5) as? Float ?: return
                            val searchLayout = XposedHelpers.getObjectField(listener, "searchLayout") as? View ?: return
                            val launcher = activityOf(searchLayout) ?: launcherActivity ?: return
                            syncDrawerProgressFromIme(launcher, progress)
                        } catch (t: Throwable) {
                            Log.d(TAG, "controlled IME sync err: $t")
                        }
                    }
                }
            )
            Log.d(TAG, "hooked controlled IME transition lambda")
        } catch (t: Throwable) {
            Log.d(TAG, "controlled IME hook failed: $t")
        }
    }

    private fun shouldSyncIme(): Boolean =
        prefs.isOptInEnabled(SYNC_KEY) && SystemClock.uptimeMillis() <= imeSyncUntil

    private fun syncDrawerProgressFromIme(launcher: Any, rawFraction: Float) {
        val fraction = rawFraction.coerceIn(0f, 1f)
        val drawerProgress = (1f - fraction).coerceIn(0f, 1f)
        val controller = XposedHelpers.callMethod(launcher, "getAllAppsController") ?: return
        XposedHelpers.callMethod(controller, "setProgress", drawerProgress)
    }

    private fun launchFirstSearchResultDebounced(launcher: Activity): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastEnterLaunchTs < 800L) return true
        val ok = launchFirstSearchResult(launcher)
        if (ok) lastEnterLaunchTs = now
        return ok
    }

    private fun launchFirstSearchResult(launcher: Activity): Boolean {
        return try {
            val appsView = XposedHelpers.callMethod(launcher, "getAppsView") as? View ?: return false
            val appInfo = getFirstSearchAppInfo(appsView) ?: run {
                Log.d(TAG, "no drawer search app info found")
                return false
            }
            val appView = findVisibleViewForTag(appsView, appInfo)
            if (launchAppInfo(launcher, appView, appInfo)) return true
            val recycler = XposedHelpers.callMethod(appsView, "getActiveSearchRecyclerView") as? ViewGroup
                ?: findFirstVisibleByEntryName(appsView, "all_apps_search") as? ViewGroup
                ?: return false
            val fallbackView = findFirstAppResultView(recycler) ?: run {
                Log.d(TAG, "no drawer search result found")
                return false
            }
            tapViewInWindow(launcher, fallbackView)
            true
        } catch (t: Throwable) {
            Log.d(TAG, "launch first drawer result err: $t")
            false
        }
    }

    private fun launchSingleSearchResultNow(launcher: Activity, query: String, adapterItems: List<*>): Boolean {
        val now = SystemClock.uptimeMillis()
        if (query == lastSingleAutoLaunchQuery && now - lastSingleAutoLaunchTs < 3000L) return true
        if (adapterItems.size != 1) return false
        val appInfo = runCatching { XposedHelpers.getObjectField(adapterItems.first(), "itemInfo") }.getOrNull()
            ?: return false
        val appsView = runCatching { XposedHelpers.callMethod(launcher, "getAppsView") as? View }.getOrNull()
        val target = appsView?.let { findVisibleViewForTag(it, appInfo) }
        val launched = launchAppInfo(launcher, target, appInfo)
        if (launched) {
            lastSingleAutoLaunchQuery = query
            lastSingleAutoLaunchTs = SystemClock.uptimeMillis()
            Log.d(TAG, "auto launched single drawer result immediately for query=$query")
        }
        return launched
    }

    private fun findFirstAppResultView(v: View): View? {
        if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) return null
        val tag = runCatching { v.tag }.getOrNull()
        val tagName = tag?.javaClass?.name.orEmpty()
        if (tagName == "com.android.launcher3.model.data.AppInfo" ||
            tagName.endsWith(".AppInfo") ||
            tagName.contains("WorkspaceItemInfo")
        ) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findFirstAppResultView(v.getChildAt(i) ?: continue)?.let { return if (v.id != 0) v else it }
            }
        }
        return null
    }

    private fun getSearchLayout(launcher: Activity): View? {
        val appsView = runCatching { XposedHelpers.callMethod(launcher, "getAppsView") as? View }.getOrNull()
        if (appsView != null) {
            runCatching { XposedHelpers.callMethod(appsView, "getColorAppsSearchContainerLayout") as? View }
                .getOrNull()
                ?.let { return it }
            findFirstVisibleByEntryName(appsView, "search_container_all_apps")?.let { return it }
        }
        return findFirstVisibleByEntryName(launcher.window?.decorView ?: return null, "search_container_all_apps")
    }

    private fun prepareFocusedSearchField(searchLayout: View, enterSearchMode: Boolean): Boolean {
        val searchBar = runCatching { XposedHelpers.getObjectField(searchLayout, "mSearchViewAnimate") }.getOrNull()
        if (!enterSearchMode && searchBar != null) {
            tryCall(searchBar, "changeStateToEdit")
            tryCall(searchBar, "changeStateWithAnimation", 1)
        }
        val edit = getSearchEditText(searchLayout) ?: run {
            if (searchBar is View) searchBar.requestFocus()
            return searchBar != null
        }
        searchEditRef = java.lang.ref.WeakReference(edit)
        launcherActivity = activityOf(edit) ?: launcherActivity
        edit.isFocusable = true
        edit.isFocusableInTouchMode = true
        edit.requestFocus()
        edit.setSelection(edit.text?.length ?: 0)
        if (prefs.isOptInEnabled(KEEP_DRAWER_LAYOUT_KEY)) {
            keepLayoutSearchLayout = java.lang.ref.WeakReference(searchLayout)
            installKeepLayoutTextWatcher(searchLayout, edit)
            val query = edit.text?.toString()
            if (query.isNullOrBlank()) {
                restoreDrawerLayoutIfQueryEmpty(searchLayout)
            } else {
                reactivatePreservedQuery(searchLayout)
            }
            applyKeepLayoutImeOffset(searchLayout.rootWindowInsets)
            scheduleKeepLayoutImeOffsetUpdates(searchLayout)
        }
        showIme(edit)
        edit.postDelayed({ showIme(edit) }, 120L)
        Log.d(TAG, "focused drawer search edit text")
        return true
    }

    private fun ensureSearchModeForQuery(searchLayout: View) {
        val appsView = runCatching { XposedHelpers.getObjectField(searchLayout, "mAppsView") }.getOrNull() ?: return
        val hasEntered = runCatching { XposedHelpers.callMethod(appsView, "hasEnterSearchMode") as? Boolean }
            .getOrNull() == true
        if (hasEntered) return
        Log.d(TAG, "entering drawer search mode after non-empty query")
        tryCall(searchLayout, "onSearchBarClickInternal")
    }

    private fun getSearchEditText(searchLayout: View): EditText? {
        runCatching { XposedHelpers.getObjectField(searchLayout, "mSearchView") as? EditText }
            .getOrNull()
            ?.let { return it }
        val searchBar = runCatching { XposedHelpers.getObjectField(searchLayout, "mSearchViewAnimate") }.getOrNull()
        return runCatching { XposedHelpers.callMethod(searchBar, "getSearchEditText") as? EditText }.getOrNull()
    }

    private fun getSearchText(searchLayout: View): String? =
        getSearchEditText(searchLayout)?.text?.toString()

    private fun installKeepLayoutTextWatcher(searchLayout: View, edit: EditText) {
        if (keepLayoutTextWatcherInstalled) return
        keepLayoutTextWatcherInstalled = true
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                try {
                    if (!prefs.isOptInEnabled(KEEP_DRAWER_LAYOUT_KEY)) return
                    if (s.isNullOrBlank()) restoreDrawerLayoutIfQueryEmpty(searchLayout)
                } catch (t: Throwable) {
                    Log.d(TAG, "keep-layout text watcher err: $t")
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun restoreDrawerLayoutIfQueryEmpty(searchLayout: View) {
        val query = getSearchText(searchLayout)
        if (!query.isNullOrBlank()) return
        if (!isLauncherInAllApps(searchLayout)) return
        restoreNormalDrawerLayout(searchLayout, "empty search")
    }

    private fun restoreNormalDrawerLayout(searchLayout: View, reason: String) {
        if (!isLauncherInAllApps(searchLayout)) {
            Log.d(TAG, "skip drawer restore for $reason; launcher not in ALL_APPS")
            return
        }
        logDrawerDiag(searchLayout, "restore-start-$reason")
        val appsView = runCatching { XposedHelpers.getObjectField(searchLayout, "mAppsView") as? View }.getOrNull()
            ?: return
        restoreView(appsView)
        runCatching { XposedHelpers.callMethod(appsView, "animateForSearch", false, null, null, true) }
            .onFailure { Log.d(TAG, "diag restore animateForSearch failed: ${it.javaClass.simpleName}: ${it.message}") }
        runCatching { XposedHelpers.setBooleanField(appsView, "mIsSearching", false) }
            .onFailure { Log.d(TAG, "diag restore set mIsSearching failed: ${it.javaClass.simpleName}: ${it.message}") }
        runCatching { XposedHelpers.setBooleanField(appsView, "mHasEnterSearchMode", false) }
            .onFailure { Log.d(TAG, "diag restore set mHasEnterSearchMode failed: ${it.javaClass.simpleName}: ${it.message}") }
        runCatching { XposedHelpers.callMethod(appsView, "onClearSearchResult") }
            .onFailure { Log.d(TAG, "diag restore onClearSearchResult failed: ${it.javaClass.simpleName}: ${it.message}") }
        runCatching {
            val holder = XposedHelpers.callMethod(appsView, "getSearchAdapterHolder")
            val appsList = XposedHelpers.callMethod(holder, "getAppsListInHolder")
            XposedHelpers.callMethod(appsList, "setSearchResults", java.util.ArrayList<Any>())
        }.onFailure { Log.d(TAG, "diag restore setSearchResults empty failed: ${it.javaClass.simpleName}: ${it.message}") }
        runCatching {
            val holder = XposedHelpers.callMethod(appsView, "getSearchAdapterHolder")
            val holderForAnimate = XposedHelpers.callMethod(appsView, "getSearchAdapterHolderForAnimate")
            val utils = XposedHelpers.findClass("com.android.launcher3.allapps.search.SearchListUtils", searchLayout.javaClass.classLoader)
            XposedHelpers.callStaticMethod(utils, "resetSearchListData", holder, holderForAnimate)
        }.onFailure { Log.d(TAG, "diag restore resetSearchListData failed: ${it.javaClass.simpleName}: ${it.message}") }
        setViewFieldVisibility(appsView, "mSearchListContainer", View.GONE)
        restoreView(findFirstByEntryName(appsView, "all_apps_content"))
        restoreView(findFirstByEntryName(appsView, "apps_view_translate"))
        restoreView(findFirstByEntryName(appsView, "b_level_apps_view_translate"))
        restoreView(runCatching { XposedHelpers.getObjectField(appsView, "mViewPager") as? View }.getOrNull())
        restoreView(runCatching { XposedHelpers.getObjectField(appsView, "mAppViewForAnimation") as? View }.getOrNull())
        runCatching { XposedHelpers.callMethod(appsView, "getActiveRecyclerView") as? View }
            .getOrNull()
            ?.let { restoreView(it) }
        runCatching { XposedHelpers.callMethod(appsView, "getContentView") as? View }
            .getOrNull()
            ?.let { restoreView(it) }
        restoreAdapterHolderViews(appsView)
        restoreSearchBarForDrawer(searchLayout)
        appsView.requestLayout()
        appsView.invalidate()
        Log.d(TAG, "restored normal drawer layout for $reason")
        logDrawerDiag(searchLayout, "restore-end-$reason")
    }

    private fun showSearchResultsLayout(searchLayout: View) {
        val appsView = runCatching { XposedHelpers.getObjectField(searchLayout, "mAppsView") as? View }.getOrNull()
            ?: return
        runCatching { XposedHelpers.setBooleanField(appsView, "mIsSearching", true) }
        runCatching { XposedHelpers.setBooleanField(appsView, "mHasEnterSearchMode", true) }
        restoreView(runCatching { XposedHelpers.getObjectField(appsView, "mSearchListContainer") as? View }.getOrNull())
        runCatching { XposedHelpers.callMethod(appsView, "getActiveSearchRecyclerView") as? View }
            .getOrNull()
            ?.let { restoreView(it) }
        val holder = runCatching { XposedHelpers.callMethod(appsView, "getSearchAdapterHolder") }.getOrNull()
        restoreView(runCatching { XposedHelpers.getObjectField(holder, "mRecyclerView") as? View }.getOrNull())
    }

    private fun exitKeepLayoutSearch(searchLayout: View, clearQuery: Boolean, clearFocus: Boolean) {
        if (!isLauncherInAllApps(searchLayout)) {
            Log.d(TAG, "skip search exit; launcher not in ALL_APPS")
            return
        }
        val edit = getSearchEditText(searchLayout)
        if (clearQuery && edit != null && edit.text?.isNotEmpty() == true) {
            edit.text?.clear()
        }
        restoreNormalDrawerLayout(searchLayout, if (clearQuery) "cleared search" else "paused search")
        applyKeepLayoutImeOffset(null)
        if (clearFocus) {
            tryCall(searchLayout, "changeStateToNormal")
        }
        try {
            val imm = searchLayout.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow((edit ?: searchLayout).windowToken, 0)
        } catch (t: Throwable) {
            Log.d(TAG, "hide IME for drawer search failed: $t")
        }
        if (clearFocus) edit?.clearFocus()
        logDrawerDiag(searchLayout, "after-exitKeepLayoutSearch")
        schedulePostExitRestore(searchLayout)
    }

    private fun schedulePostExitRestore(searchLayout: View) {
        for (delay in longArrayOf(120L, 280L, 520L)) {
            searchLayout.postDelayed({
                try {
                    if (!isLauncherInAllApps(searchLayout)) {
                        Log.d(TAG, "skip post-exit-$delay restore; launcher not in ALL_APPS")
                        return@postDelayed
                    }
                    logDrawerDiag(searchLayout, "post-exit-$delay-before")
                    restoreNormalDrawerLayout(searchLayout, "post-exit-$delay")
                    applyKeepLayoutImeOffset(null)
                    getSearchEditText(searchLayout)?.clearFocus()
                    logDrawerDiag(searchLayout, "post-exit-$delay-after")
                } catch (t: Throwable) {
                    Log.d(TAG, "post-exit drawer restore err: $t")
                }
            }, delay)
        }
    }

    private fun reactivatePreservedQuery(searchLayout: View) {
        val query = getSearchText(searchLayout)
        if (query.isNullOrBlank()) return
        ensureSearchModeForQuery(searchLayout)
        showSearchResultsLayout(searchLayout)
        runCatching {
            val controller = XposedHelpers.getObjectField(searchLayout, "mSearchBarController")
            XposedHelpers.callMethod(controller, "refreshSearchResult")
        }.onFailure {
            Log.d(TAG, "refresh preserved drawer query failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        Log.d(TAG, "reactivated preserved drawer query")
    }

    private fun isKeepLayoutSearchActive(searchLayout: View): Boolean {
        val edit = getSearchEditText(searchLayout)
        return edit?.hasFocus() == true ||
            !edit?.text?.toString().isNullOrBlank() ||
            isImeVisible(searchLayout) ||
            runCatching {
                val appsView = XposedHelpers.getObjectField(searchLayout, "mAppsView")
                XposedHelpers.callMethod(appsView, "hasEnterSearchMode") as? Boolean
            }.getOrNull() == true
    }

    private fun handleKeepLayoutImeVisibilityChange(callback: Any, insets: WindowInsets, source: String) {
        val searchLayout = runCatching { XposedHelpers.getObjectField(callback, "view") as? View }.getOrNull()
            ?: keepLayoutSearchLayout?.get()
            ?: return
        if (!isLauncherInAllApps(searchLayout)) {
            keepLayoutImeWasVisible = false
            return
        }
        val ime = insets.getInsets(WindowInsets.Type.ime())
        val visible = insets.isVisible(WindowInsets.Type.ime()) && ime.bottom > 0
        if (visible) {
            if (!keepLayoutImeWasVisible) {
                Log.d(TAG, "diag ime-visible source=$source bottom=${ime.bottom}")
                logDrawerDiag(searchLayout, "ime-visible-$source")
            }
            keepLayoutImeWasVisible = true
            return
        }
        if (!keepLayoutImeWasVisible) return
        keepLayoutImeWasVisible = false
        Log.d(TAG, "diag ime-hidden-transition source=$source bottom=${ime.bottom} active=${isKeepLayoutSearchActive(searchLayout)}")
        logDrawerDiag(searchLayout, "before-ime-hidden-transition")
        if (isKeepLayoutSearchActive(searchLayout)) {
            exitKeepLayoutSearch(searchLayout, clearQuery = false, clearFocus = true)
            Log.d(TAG, "diag ime-hidden-transition-exit")
        }
    }

    private fun applyKeepLayoutImeOffset(insets: WindowInsets?) {
        val searchLayout = keepLayoutSearchLayout?.get() ?: return
        if (!isLauncherInAllApps(searchLayout)) {
            searchLayout.translationY = 0f
            return
        }
        if (!prefs.isOptInEnabled(KEEP_DRAWER_LAYOUT_KEY)) {
            searchLayout.translationY = 0f
            return
        }
        val ime = insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        if (ime <= 0) {
            searchLayout.translationY = 0f
            return
        }
        val safety = (searchLayout.resources.displayMetrics.density * 8f)
        searchLayout.translationY = -(ime.toFloat() + safety)
    }

    private fun scheduleKeepLayoutImeOffsetUpdates(searchLayout: View) {
        val delays = longArrayOf(40L, 100L, 180L, 280L, 420L)
        for (delay in delays) {
            searchLayout.postDelayed({
                try {
                    if (keepLayoutSearchLayout?.get() === searchLayout) {
                        applyKeepLayoutImeOffset(searchLayout.rootWindowInsets)
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "keep-layout IME offset update err: $t")
                }
            }, delay)
            }
    }

    private fun restoreView(view: View?) {
        if (view == null) return
        view.visibility = View.VISIBLE
        view.alpha = 1f
        view.translationY = 0f
        view.translationX = 0f
    }

    private fun logDrawerDiag(searchLayout: View, label: String) {
        try {
            val edit = getSearchEditText(searchLayout)
            val appsView = runCatching { XposedHelpers.getObjectField(searchLayout, "mAppsView") as? View }.getOrNull()
            val allAppsContent = appsView?.let { findFirstByEntryName(it, "all_apps_content") }
            val appsTranslate = appsView?.let { findFirstByEntryName(it, "apps_view_translate") }
            val bLevelTranslate = appsView?.let { findFirstByEntryName(it, "b_level_apps_view_translate") }
            val searchList = appsView?.let { runCatching { XposedHelpers.getObjectField(it, "mSearchListContainer") as? View }.getOrNull() }
            val viewPager = appsView?.let { runCatching { XposedHelpers.getObjectField(it, "mViewPager") as? View }.getOrNull() }
            val appAnim = appsView?.let { runCatching { XposedHelpers.getObjectField(it, "mAppViewForAnimation") as? View }.getOrNull() }
            val activeRv = appsView?.let { runCatching { XposedHelpers.callMethod(it, "getActiveRecyclerView") as? View }.getOrNull() }
            val activeSearchRv = appsView?.let { runCatching { XposedHelpers.callMethod(it, "getActiveSearchRecyclerView") as? View }.getOrNull() }
            val isSearching = appsView?.let { runCatching { XposedHelpers.getBooleanField(it, "mIsSearching") }.getOrNull() }
            val hasEnterSearch = appsView?.let { runCatching { XposedHelpers.getBooleanField(it, "mHasEnterSearchMode") }.getOrNull() }
            val hasEnterSearchMethod = appsView?.let {
                runCatching { XposedHelpers.callMethod(it, "hasEnterSearchMode") as? Boolean }.getOrNull()
            }
            val holderStates = appsView?.let { adapterHolderStates(it) }.orEmpty()
            Log.d(
                TAG,
                "diag[$label] queryLen=${edit?.text?.length ?: -1} focus=${edit?.hasFocus()} ime=${isImeVisible(searchLayout)} " +
                    "searchLayout=${viewState(searchLayout)} appsView=${viewState(appsView)} " +
                    "isSearching=$isSearching hasEnterField=$hasEnterSearch hasEnterMethod=$hasEnterSearchMethod " +
                    "content=${viewState(allAppsContent)} appsTranslate=${viewState(appsTranslate)} bLevel=${viewState(bLevelTranslate)} " +
                    "searchList=${viewState(searchList)} viewPager=${viewState(viewPager)} appAnim=${viewState(appAnim)} " +
                    "activeRv=${viewState(activeRv)} activeSearchRv=${viewState(activeSearchRv)} holders=$holderStates"
            )
        } catch (t: Throwable) {
            Log.d(TAG, "diag[$label] failed: $t")
        }
    }

    private fun adapterHolderStates(appsView: View): String {
        val holders = runCatching { XposedHelpers.getObjectField(appsView, "mAH") as? List<*> }.getOrNull()
            ?: return "holders=null"
        return holders.mapIndexed { index, holder ->
            val isSearch = runCatching { XposedHelpers.getBooleanField(holder, "mIsSearch") }.getOrNull()
            val recycler = runCatching { XposedHelpers.getObjectField(holder, "mRecyclerView") as? View }.getOrNull()
            "$index:search=$isSearch:${viewState(recycler)}"
        }.joinToString("|")
    }

    private fun viewState(view: View?): String {
        if (view == null) return "null"
        val name = resourceEntryName(view) ?: view.javaClass.simpleName
        return "$name{vis=${visibilityName(view.visibility)},a=${"%.2f".format(java.util.Locale.US, view.alpha)},tx=${view.translationX.toInt()},ty=${view.translationY.toInt()},w=${view.width},h=${view.height}}"
    }

    private fun visibilityName(visibility: Int): String = when (visibility) {
        View.VISIBLE -> "V"
        View.INVISIBLE -> "I"
        View.GONE -> "G"
        else -> visibility.toString()
    }

    private fun restoreAdapterHolderViews(appsView: View) {
        val holders = runCatching { XposedHelpers.getObjectField(appsView, "mAH") as? List<*> }.getOrNull()
            ?: return
        for (holder in holders) {
            val isSearch = runCatching { XposedHelpers.getBooleanField(holder, "mIsSearch") }.getOrDefault(false)
            val recycler = runCatching { XposedHelpers.getObjectField(holder, "mRecyclerView") as? View }.getOrNull()
            if (isSearch) {
                recycler?.visibility = View.GONE
                recycler?.alpha = 0f
            } else {
                restoreView(recycler)
            }
        }
    }

    private fun restoreSearchBarForDrawer(searchLayout: View) {
        restoreView(searchLayout)
        restoreView(runCatching { XposedHelpers.getObjectField(searchLayout, "mSearchParentAnimate") as? View }.getOrNull())
        restoreView(runCatching { XposedHelpers.getObjectField(searchLayout, "mSearchViewAnimate") as? View }.getOrNull())
        restoreView(runCatching { XposedHelpers.getObjectField(searchLayout, "mSearchBgViewAnimate") as? View }.getOrNull())
        runCatching { XposedHelpers.callMethod(searchLayout, "resetSearchBoxToExpandState", true) }
            .onFailure { Log.d(TAG, "restore search box expand failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun isLauncherInAllApps(searchLayout: View): Boolean {
        val launcher = activityOf(searchLayout) ?: launcherActivity ?: return true
        return runCatching {
            val stateCls = XposedHelpers.findClass("com.android.launcher3.LauncherState", launcher.classLoader)
            val allApps = XposedHelpers.getStaticObjectField(stateCls, "ALL_APPS")
            XposedHelpers.callMethod(launcher, "isInState", allApps) as? Boolean
        }.getOrNull()
            ?: runCatching {
                val stateManager = XposedHelpers.callMethod(launcher, "getStateManager")
                val state = XposedHelpers.callMethod(stateManager, "getState")
                isAllAppsState(state, launcher.classLoader)
            }.getOrDefault(true)
    }

    private fun isImeVisible(view: View): Boolean =
        runCatching { view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }.getOrDefault(false)

    private fun isAtTop(view: View): Boolean =
        runCatching { !(view.canScrollVertically(-1)) }.getOrDefault(false) ||
            runCatching { (XposedHelpers.callMethod(view, "getCurrentScrollY") as? Int ?: 1) <= 0 }.getOrDefault(false)

    private fun setViewFieldVisibility(target: Any, fieldName: String, visibility: Int) {
        runCatching { XposedHelpers.getObjectField(target, fieldName) as? View }
            .getOrNull()
            ?.let {
                it.visibility = visibility
                if (visibility == View.GONE || visibility == View.INVISIBLE) {
                    it.alpha = 0f
                }
            }
    }

    private fun showIme(view: View) {
        try {
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            view.windowInsetsController?.show(WindowInsets.Type.ime())
        } catch (t: Throwable) {
            Log.d(TAG, "show IME for drawer search failed: $t")
        }
    }

    private fun isLauncherDrawerSearchField(view: View): Boolean {
        val entryName = resourceEntryName(view)
        if (entryName == "search_box_input" || entryName == "search_src_text") return true
        var parent = view.parent
        while (parent is View) {
            val name = resourceEntryName(parent)
            if (name == "search_container_all_apps" || name == "search_box_input") return true
            parent = parent.parent
        }
        return false
    }

    private fun findFirstVisibleByEntryName(v: View, entryName: String): View? {
        if (v.visibility != View.VISIBLE) return null
        if (resourceEntryName(v) == entryName && v.width > 0 && v.height > 0) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findFirstVisibleByEntryName(v.getChildAt(i) ?: continue, entryName)?.let { return it }
            }
        }
        return null
    }

    private fun findFirstByEntryName(v: View, entryName: String): View? {
        if (resourceEntryName(v) == entryName) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findFirstByEntryName(v.getChildAt(i) ?: continue, entryName)?.let { return it }
            }
        }
        return null
    }

    private fun findAncestorByEntryName(v: View, entryName: String): View? {
        var current: View? = v
        while (current != null) {
            if (resourceEntryName(current) == entryName) return current
            current = current.parent as? View
        }
        return null
    }

    private fun getFirstSearchAppInfo(appsView: View): Any? =
        getSearchAppInfos(appsView).firstOrNull()

    private fun getSearchAppInfos(appsView: View): List<Any> {
        val list = getSearchAdapterItems(appsView)
        return list.mapNotNull { item ->
            runCatching { XposedHelpers.getObjectField(item, "itemInfo") }.getOrNull()
        }
    }

    private fun getSearchAdapterItems(appsView: View): List<*> {
        val holder = runCatching { XposedHelpers.callMethod(appsView, "getSearchAdapterHolder") }.getOrNull()
        val appsList = runCatching { XposedHelpers.callMethod(holder, "getAppsListInHolder") }.getOrNull()
        val results = runCatching { XposedHelpers.callMethod(appsList, "getSearchResults") as? List<*> }.getOrNull()
        if (!results.isNullOrEmpty()) return results
        val adapterItems = runCatching { XposedHelpers.callMethod(appsList, "getAdapterItems") as? List<*> }.getOrNull()
        return adapterItems ?: emptyList<Any>()
    }

    private fun launchAppInfo(launcher: Activity, sourceView: View?, appInfo: Any): Boolean {
        val intent = getItemIntent(appInfo) ?: return false
        return try {
            val result = XposedHelpers.callMethod(launcher, "startActivitySafely", sourceView, intent, appInfo)
            Log.d(TAG, "startActivitySafely for drawer result returned $result")
            result as? Boolean ?: true
        } catch (t: Throwable) {
            Log.d(TAG, "startActivitySafely drawer result failed: $t")
            try {
                launcher.startActivity(intent)
                true
            } catch (t2: Throwable) {
                Log.d(TAG, "fallback startActivity drawer result failed: $t2")
                false
            }
        }
    }

    private fun getItemIntent(itemInfo: Any): Intent? {
        runCatching { XposedHelpers.getObjectField(itemInfo, "intent") as? Intent }
            .getOrNull()
            ?.let { return it }
        return runCatching { XposedHelpers.callMethod(itemInfo, "getIntent") as? Intent }.getOrNull()
    }

    private fun findVisibleViewForTag(v: View, targetTag: Any): View? {
        if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) return null
        val tag = runCatching { v.tag }.getOrNull()
        if (tag === targetTag || sameLauncherItem(tag, targetTag)) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findVisibleViewForTag(v.getChildAt(i) ?: continue, targetTag)?.let { return it }
            }
        }
        return null
    }

    private fun sameLauncherItem(a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return false
        val aComponent = runCatching { XposedHelpers.getObjectField(a, "componentName") }.getOrNull()
        val bComponent = runCatching { XposedHelpers.getObjectField(b, "componentName") }.getOrNull()
        val aUser = runCatching { XposedHelpers.callMethod(a, "getUser") }.getOrNull()
            ?: runCatching { XposedHelpers.getObjectField(a, "user") }.getOrNull()
        val bUser = runCatching { XposedHelpers.callMethod(b, "getUser") }.getOrNull()
            ?: runCatching { XposedHelpers.getObjectField(b, "user") }.getOrNull()
        return aComponent != null && aComponent == bComponent && aUser == bUser
    }

    private fun tryCall(target: Any, method: String, vararg args: Any?): Boolean {
        return try {
            XposedHelpers.callMethod(target, method, *args)
            Log.d(TAG, "called ${target.javaClass.simpleName}.$method")
            true
        } catch (t: Throwable) {
            Log.d(TAG, "call ${target.javaClass.simpleName}.$method failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun isAllAppsState(state: Any?, classLoader: ClassLoader?): Boolean {
        if (state == null) return false
        try {
            val cls = XposedHelpers.findClass("com.android.launcher3.LauncherState", classLoader)
            val allApps = XposedHelpers.getStaticObjectField(cls, "ALL_APPS")
            if (state === allApps) return true
        } catch (_: Throwable) {
        }
        val text = state.toString()
        return text.contains("ALL_APPS", ignoreCase = true) ||
            text.contains("AllApps", ignoreCase = true) ||
            state.javaClass.simpleName.contains("AllApps", ignoreCase = true)
    }

    private fun getSearchViewTargetY(callback: Any): Float {
        for (method in listOf("getSearchViewTargetY", "getSearchTargetTranslationY")) {
            val value = runCatching { XposedHelpers.callMethod(callback, method) as? Float }.getOrNull()
            if (value != null) return value
        }
        return runCatching { XposedHelpers.getFloatField(callback, "searchViewTargetY") }.getOrDefault(0f)
    }

    private fun prefSummary(): String =
        "prefs(open=${prefs.isOptInEnabled(OPEN_KEY)}, sync=${prefs.isOptInEnabled(SYNC_KEY)}, enter=${prefs.isOptInEnabled(ENTER_KEY)}, single=${prefs.isOptInEnabled(AUTO_LAUNCH_SINGLE_KEY)}, keepLayout=${prefs.isOptInEnabled(KEEP_DRAWER_LAYOUT_KEY)}, reopenTop=${prefs.isOptInEnabled(REOPEN_AT_TOP_KEY)})"

    private fun currentLauncherActivity(): Activity? =
        searchEditRef?.get()?.let { activityOf(it) } ?: launcherActivity

    private fun activityOf(v: View): Activity? {
        var c: Context? = v.context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun resourceEntryName(view: View): String? = try {
        if (view.id == View.NO_ID) null else view.context.resources.getResourceEntryName(view.id)
    } catch (_: Throwable) {
        null
    }

    private fun tapViewInWindow(activity: Activity, v: View) {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val x = loc[0] + v.width / 2f
        val y = loc[1] + v.height / 2f
        val decor = activity.window?.decorView ?: v.rootView
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_UP, x, y, 0)
        try {
            decor.dispatchTouchEvent(down)
            decor.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }
}
