package io.github.kvmy666.autoexpand.hook

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Global Search — Enter / soft "Go" launches the first result (OnePlus quicksearchbox).
 *
 * Pref `global_search_enter_launch_enabled` (opt-in, default OFF). The OEM search box opens
 * the global-search app; by default pressing Go/Enter there does nothing. When the pref is
 * ON we intercept the editor action at its true source — the search field's InputConnection —
 * and tap the first result row (a real touch, since the rows launch via a RecyclerView touch
 * listener rather than performClick).
 *
 * Two entry activities reuse the same field id `search_bar_search_input`: SearchActivity
 * (home swipe-down) and SearchDrawActivity (drawer). Result lists differ per activity
 * (recycler_view_shelf vs recycler_view_result), so we tree-walk for the first visible
 * `text_content` row instead of hard-coding a recycler id. The IC is the standard
 * com.android.internal.inputmethod.EditableInputConnection, whose performEditorAction /
 * sendKeyEvent are inherited from BaseInputConnection — hookAllMethods on the subclass would
 * hook nothing, so findAndHookMethod (which walks the superclass chain) is used.
 *
 * SAFETY: every hook is gated on the pref with full try/catch; OFF = the search app is
 * untouched.
 */
class GlobalSearchHook(private val prefs: PrefsBridge) {

    private val TAG = "TweaksLauncher"
    private val GS_KEY = "global_search_enter_launch_enabled"

    @Volatile private var lastSearchLaunchTs = 0L
    @Volatile private var gsActivity: android.app.Activity? = null
    // The live search EditText — captured in onCreateInputConnection so the launch path is
    // activity-agnostic across the two entry activities.
    @Volatile private var searchEditRef: java.lang.ref.WeakReference<View>? = null
    private val hookedIcClasses = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Resolve the current search activity from the focused field, falling back to gsActivity. */
    private fun currentSearchActivity(): android.app.Activity? =
        (searchEditRef?.get()?.let { activityOf(it) }) ?: gsActivity

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        // (1) onResume: supply an appContext from the activity (this third-party process has
        // none) so the Settings.Global pref channel is readable, and track the live activity.
        // The home swipe-down opens SearchActivity while the drawer uses SearchDrawActivity —
        // hook both so either entry point works.
        for (clsName in listOf(
            "com.oplus.globalsearch.ui.SearchActivity",
            "com.oplus.globalsearch.ui.SearchDrawActivity"
        )) {
            try {
                val act = XposedHelpers.findClass(clsName, cl)
                XposedBridge.hookAllMethods(act, "onResume", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val activity = param.thisObject as android.app.Activity
                            gsActivity = activity
                            if (prefs.appContext == null) {
                                prefs.appContext = activity.applicationContext
                                prefs.loadFilePrefs()
                            }
                        } catch (t: Throwable) { Log.d(TAG, "onResume hook err: $t") }
                    }
                })
                XposedBridge.hookAllMethods(act, "onPause", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (gsActivity === param.thisObject) gsActivity = null
                    }
                })
            } catch (t: Throwable) {
                Log.d(TAG, "activity hook install failed ($clsName): $t")
            }
        }
        // (2) The soft "Go" action is delivered through the search field's InputConnection
        // (it never reaches a view editor-action or key event). Discover that IC class at
        // runtime from onCreateInputConnection, then hook its performEditorAction/sendKeyEvent.
        try {
            XposedHelpers.findAndHookMethod(
                android.widget.TextView::class.java, "onCreateInputConnection",
                android.view.inputmethod.EditorInfo::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val tv = param.thisObject as? android.widget.TextView ?: return
                            if (resourceEntryName(tv) != "search_bar_search_input") return
                            // Capture the field + an appContext here so the launch path works
                            // even before either entry activity's onResume hook runs.
                            searchEditRef = java.lang.ref.WeakReference(tv)
                            if (prefs.appContext == null) {
                                prefs.appContext = tv.context?.applicationContext
                                prefs.loadFilePrefs()
                            }
                            if (!prefs.isOptInEnabled(GS_KEY)) return
                            val ic = param.result ?: return
                            val info = param.args.getOrNull(0) as? android.view.inputmethod.EditorInfo
                            Log.d(TAG, "search IC=${ic.javaClass.name} imeOptions=${info?.imeOptions}")
                            hookSearchInputConnection(ic.javaClass)
                        } catch (t: Throwable) { Log.d(TAG, "onCreateIC hook err: $t") }
                    }
                })
            Log.d(TAG, "global search hook installed")
        } catch (t: Throwable) {
            Log.d(TAG, "onCreateIC hook install failed: $t")
        }
    }

    /**
     * Hook performEditorAction (soft "Go") and sendKeyEvent (Enter) on the search field's
     * actual IC class (once). findAndHookMethod walks the superclass chain to the inherited
     * BaseInputConnection declaration; the hook still fires for calls on the subclass instance.
     */
    private fun hookSearchInputConnection(icClass: Class<*>) {
        if (!hookedIcClasses.add(icClass.name)) return
        try {
            XposedHelpers.findAndHookMethod(
                icClass, "performEditorAction", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!prefs.isOptInEnabled(GS_KEY)) return
                            val activity = currentSearchActivity() ?: return
                            if (launchFirstDebounced(activity)) param.setResult(true) // consume
                        } catch (t: Throwable) { Log.d(TAG, "IC.performEditorAction err: $t") }
                    }
                })
            Log.d(TAG, "hooked IC.performEditorAction via ${icClass.name}")
        } catch (t: Throwable) {
            Log.d(TAG, "hook performEditorAction err: $t")
        }
        try {
            XposedHelpers.findAndHookMethod(
                icClass, "sendKeyEvent", KeyEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!prefs.isOptInEnabled(GS_KEY)) return
                            val ev = param.args.getOrNull(0) as? KeyEvent ?: return
                            if (ev.action != KeyEvent.ACTION_UP) return
                            if (ev.keyCode != KeyEvent.KEYCODE_ENTER && ev.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return
                            val activity = currentSearchActivity() ?: return
                            if (launchFirstDebounced(activity)) param.setResult(true)
                        } catch (t: Throwable) { Log.d(TAG, "IC.sendKeyEvent err: $t") }
                    }
                })
            Log.d(TAG, "hooked IC.sendKeyEvent via ${icClass.name}")
        } catch (t: Throwable) {
            Log.d(TAG, "hook sendKeyEvent err: $t")
        }
    }

    /** De-duplicated launch — the performEditorAction and key paths can both fire for one press. */
    private fun launchFirstDebounced(activity: android.app.Activity): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSearchLaunchTs < 1000L) return true
        val ok = launchFirstSearchResult(activity)
        if (ok) lastSearchLaunchTs = now
        return ok
    }

    /**
     * Tap the first result row. Walk the decor tree for the first visible `text_content`
     * view — that id marks a real result row (section headers use text_title), and tapping
     * anywhere on the row launches it — regardless of which entry activity / recycler is shown.
     */
    private fun launchFirstSearchResult(activity: android.app.Activity): Boolean {
        return try {
            val textId = activity.resources.getIdentifier("text_content", "id", activity.packageName)
            if (textId == 0) { Log.d(TAG, "no text_content id"); return false }
            val root = activity.window?.decorView ?: return false
            val first = findFirstVisibleById(root, textId) ?: run {
                Log.d(TAG, "no result row found"); return false
            }
            val label = (first as? android.widget.TextView)?.text
            Log.d(TAG, "first result = \"$label\"")
            tapViewInWindow(activity, first)
            true
        } catch (t: Throwable) {
            Log.d(TAG, "launchFirstSearchResult err: $t"); false
        }
    }

    /** Depth-first (visual top-to-bottom) search for the first shown view with the given id. */
    private fun findFirstVisibleById(v: View, id: Int): View? {
        if (v.visibility != View.VISIBLE) return null
        if (v.id == id && v.width > 0 && v.height > 0) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                findFirstVisibleById(v.getChildAt(i) ?: continue, id)?.let { return it }
            }
        }
        return null
    }

    /**
     * Replay a real touch on a view by dispatching DOWN+UP at its on-screen centre to the
     * window's decor view (the rows launch on a genuine touch, not performClick).
     */
    private fun tapViewInWindow(activity: android.app.Activity, v: View) {
        val loc = IntArray(2); v.getLocationInWindow(loc)
        val x = loc[0] + v.width / 2f
        val y = loc[1] + v.height / 2f
        val decor = activity.window?.decorView ?: v.rootView
        val now = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_UP, x, y, 0)
        try {
            decor.dispatchTouchEvent(down)
            decor.dispatchTouchEvent(up)
        } finally { down.recycle(); up.recycle() }
    }

    /** Unwrap a view's context chain to the hosting Activity. */
    private fun activityOf(v: View): android.app.Activity? {
        var c: Context? = v.context
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** Resource-entry name of a view's id, or null. */
    private fun resourceEntryName(view: View): String? = try {
        view.context.resources.getResourceEntryName(view.id)
    } catch (_: Throwable) { null }
}
