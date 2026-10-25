package io.github.kvmy666.autoexpand.hook.notif

import android.content.Context
import android.os.Build
import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.io.File

/**
 * Bytecode fallback for ROMs that rename the classes the module hooks.
 *
 * Everything about DexKit here is **lazy and one-way**:
 *  - the native library is loaded on first use, never in a static initializer, so a missing or
 *    unstripped-ABI build cannot fail module class loading;
 *  - the bridge is created only when exact-name resolution already failed, so the measured
 *    OxygenOS path never pays the parse cost;
 *  - any failure flips [disabled] and every later call returns empty — the engine keeps running
 *    with the capabilities that reflection did resolve.
 *
 * The APK scanned is **SystemUI's**, not the module's: [prepare] receives
 * `LoadPackageParam.appInfo.sourceDir`, because the classes we want live there.
 */
internal object DexKitBackend {

    private const val TAG = "AENotif"
    private const val MODULE_PKG = "io.github.kvmy666.autoexpand"
    private const val NATIVE_LIB = "libdexkit.so"

    @Volatile private var nativeLoaded = false
    @Volatile private var disabled = false
    @Volatile private var apkPath: String? = null
    @Volatile private var context: Context? = null
    @Volatile private var bridge: DexKitBridge? = null
    @Volatile private var reportedUnavailable = false

    /** Records where to scan and, when available, a context able to locate the module's libs. */
    fun prepare(sourceDir: String?, ctx: Context?) {
        if (sourceDir != null) apkPath = sourceDir
        if (ctx != null) context = ctx
    }

    /** Null when DexKit cannot be used in this process; callers then stay on reflection. */
    fun bridge(): DexKitBridge? {
        if (disabled) return null
        val path = apkPath
        if (path == null) {
            reportOnce("no SystemUI APK path")
            return null
        }
        bridge?.let { return it }
        return synchronized(this) {
            bridge ?: run {
                // Engine 1 installs before the Application.onCreate hook has captured a context.
                // Without one the module's native lib cannot be located, so stay undecided and
                // let Engine 2's later call retry instead of disabling the fallback for the
                // whole process.
                if (context == null && !nativeLoaded) return null
                if (!loadNative()) {
                    disabled = true
                    reportOnce("native library unavailable")
                    return null
                }
                try {
                    DexKitBridge.create(path).also { bridge = it }
                } catch (t: Throwable) {
                    disabled = true
                    Log.e(TAG, "resolver dexkit: create failed ($t)")
                    null
                }
            }
        }
    }

    /**
     * Class names in SystemUI whose simple name contains [simpleName].
     *
     * Returns an empty list on any failure — never throws to the resolver.
     */
    fun findClassNames(simpleName: String, limit: Int = 8): List<String> {
        val b = bridge() ?: return emptyList()
        return try {
            b.findClass {
                searchPackages("com.android.systemui")
                matcher {
                    className(simpleName, StringMatchType.Contains)
                }
            }.map { it.name }.take(limit)
        } catch (t: Throwable) {
            Log.e(TAG, "resolver dexkit: class search '$simpleName' failed ($t)")
            emptyList()
        }
    }

    /**
     * Loads `libdexkit.so` in the ways an LSPosed module can actually find it.
     *
     * `System.loadLibrary` only searches the host process's own library paths (SystemUI's), which
     * do not contain a module's libs. Next is the module's `nativeLibraryDir` — valid when the
     * manifest extracts native libs at install. Last is the APK itself:
     * `nativeloader` accepts `base.apk!/lib/<abi>/lib.so`, which is how other modules on this
     * device load their own native code. Every attempt is caught; failure only disables DexKit.
     */
    private fun loadNative(): Boolean {
        if (nativeLoaded) return true

        try {
            System.loadLibrary("dexkit")
            nativeLoaded = true
            Log.i(TAG, "resolver dexkit: native library loaded from process path")
            return true
        } catch (_: Throwable) { /* fall through */ }

        val ctx = context ?: return false
        val info = try {
            ctx.packageManager.getApplicationInfo(MODULE_PKG, 0)
        } catch (t: Throwable) {
            Log.e(TAG, "resolver dexkit: module package lookup failed ($t)")
            return false
        }

        val extracted = File(info.nativeLibraryDir, NATIVE_LIB)
        if (extracted.exists()) {
            try {
                System.load(extracted.absolutePath)
                nativeLoaded = true
                Log.i(TAG, "resolver dexkit: native library loaded from ${info.nativeLibraryDir}")
                return true
            } catch (t: Throwable) {
                Log.e(TAG, "resolver dexkit: extracted lib load failed ($t)")
            }
        }

        for (abi in Build.SUPPORTED_ABIS) {
            try {
                System.load("${info.sourceDir}!/lib/$abi/$NATIVE_LIB")
                nativeLoaded = true
                Log.i(TAG, "resolver dexkit: native library loaded from APK ($abi)")
                return true
            } catch (_: Throwable) { /* try next ABI */ }
        }

        Log.e(TAG, "resolver dexkit: $NATIVE_LIB not found in ${info.nativeLibraryDir} or APK")
        return false
    }

    private fun reportOnce(reason: String) {
        if (reportedUnavailable) return
        reportedUnavailable = true
        Log.i(TAG, "resolver dexkit unavailable: $reason — staying on reflection")
    }
}
