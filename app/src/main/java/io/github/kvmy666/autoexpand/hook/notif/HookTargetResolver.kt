package io.github.kvmy666.autoexpand.hook.notif

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Resolves SystemUI hook targets without assuming one ROM's exact names.
 *
 * Order is deliberate and cheap-first:
 *  1. **Exact names** from [HookTargets] — the AOSP name plus the OEM names we know. This is the
 *     only path on a ROM the module has been measured against, and it never changes behaviour.
 *  2. **Hierarchy walk** — the same name on a superclass (OEMs move members up rather than
 *     rename them surprisingly often).
 *  3. **Shape match** — same parameter types and return type, with a name hint. This is what
 *     catches a renamed method without a bytecode scanner. It is deliberately conservative:
 *     more than one candidate is reported as ambiguous and resolves to nothing, because guessing
 *     between two look-alike methods is worse than leaving the capability off.
 *
 * Field renames are **not** shape-matched: a boolean field is indistinguishable from dozens of
 * others, and a wrong one would corrupt row state silently. Fields keep the exact-name walk, and
 * a ROM that renames them reports the capability as missing in [RowApi.caps].
 *
 * Every miss is logged once at install; nothing here throws at call time.
 */
object HookTargetResolver {

    private const val TAG = "AENotif"

    /** Records the SystemUI APK and (when available) a context for loading DexKit's native lib. */
    fun prepare(sourceDir: String?, context: android.content.Context?) =
        DexKitBackend.prepare(sourceDir, context)

    // ── classes ──────────────────────────────────────────────────────────────

    /**
     * Tries the candidate names in order, then DexKit on the SystemUI APK.
     *
     * [logical] is only used for the log line; callers pass the same string as [RowApi.caps].
     */
    fun findClass(classLoader: ClassLoader, logical: String, names: List<String>): Class<*>? {
        for (name in names) {
            val cls = try { classLoader.loadClass(name) } catch (_: Throwable) { null }
            if (cls != null) {
                if (name != names.first()) Log.i(TAG, "resolver $logical=$name (fallback candidate)")
                return cls
            }
        }
        dexKitClass(classLoader, logical, names.first().substringAfterLast('.'))?.let { return it }
        Log.w(TAG, "resolver $logical: none of ${names.size} candidates loaded")
        return null
    }

    /**
     * Last resort: ask DexKit for a SystemUI class whose simple name still contains the AOSP
     * name. Nothing here runs on a ROM where the exact names resolved.
     */
    private fun dexKitClass(classLoader: ClassLoader, logical: String, simpleName: String): Class<*>? {
        val found = DexKitBackend.findClassNames(simpleName)
        if (found.isEmpty()) return null
        // Prefer an exact simple-name suffix, then the shortest candidate, so a nested helper
        // (`Foo$ExpandableNotificationRowListener`) cannot win over the real class.
        val ordered = found.sortedWith(compareBy({ !it.endsWith(".$simpleName") }, { it.length }))
        for (name in ordered) {
            val cls = load(classLoader, name)
            if (cls != null) {
                Log.i(TAG, "resolver $logical=$name (dexkit, ${found.size} candidate(s))")
                return cls
            }
        }
        Log.w(TAG, "resolver $logical: dexkit found ${found.size} candidates but none loaded")
        return null
    }

    /** Nullable class load, for optional capabilities. */
    fun load(classLoader: ClassLoader, name: String): Class<*>? =
        try { classLoader.loadClass(name) } catch (_: Throwable) { null }

    // ── methods ──────────────────────────────────────────────────────────────

    /**
     * Resolves one method. Exact name + parameter types win; otherwise a shape match on the
     * hierarchy is attempted only when [hints] is non-empty.
     */
    fun findMethod(
        cls: Class<*>?,
        names: List<String>,
        paramTypes: List<Class<*>>,
        returnType: Class<*>? = null,
        hints: List<String> = emptyList(),
    ): Method? {
        if (cls == null) return null

        // 1 + 2: exact names, walking the hierarchy.
        var c: Class<*>? = cls
        while (c != null) {
            for (name in names) {
                try {
                    return c.getDeclaredMethod(name, *paramTypes.toTypedArray()).apply { isAccessible = true }
                } catch (_: Throwable) { /* try next */ }
            }
            c = c.superclass
        }
        if (hints.isEmpty()) return null

        // 3: shape match.
        val candidates = mutableListOf<Method>()
        c = cls
        while (c != null && c != Any::class.java) {
            for (m in try { c.declaredMethods } catch (_: Throwable) { emptyArray() }) {
                if (!shapeMatches(m, paramTypes, returnType)) continue
                val lowerName = m.name.lowercase()
                if (hints.none { lowerName.contains(it.lowercase()) }) continue
                candidates += m
            }
            c = c.superclass
        }
        val distinct = candidates.distinctBy { it.name + it.parameterTypes.joinToString(",") }
        return when {
            distinct.isEmpty() -> null
            distinct.size == 1 -> distinct.first().apply { isAccessible = true }.also {
                Log.i(TAG, "resolver ${cls.name}.${names.first()} matched by shape -> ${it.name}")
            }
            else -> {
                Log.w(TAG, "resolver ${cls.name}.${names.first()} ambiguous: " +
                    distinct.joinToString(",") { it.name })
                null
            }
        }
    }

    private fun shapeMatches(m: Method, paramTypes: List<Class<*>>, returnType: Class<*>?): Boolean {
        if (Modifier.isStatic(m.modifiers) || Modifier.isAbstract(m.modifiers)) return false
        val params = m.parameterTypes
        if (params.size != paramTypes.size) return false
        for (i in params.indices) {
            if (!accepts(params[i], paramTypes[i])) return false
        }
        if (returnType != null && !accepts(returnType, m.returnType)) return false
        return true
    }

    /** Primitive-aware assignability: `boolean` matches `Boolean`/`java.lang.Boolean`. */
    private fun accepts(expected: Class<*>, actual: Class<*>): Boolean {
        if (expected == actual) return true
        val boxed = box(expected)
        return boxed == actual || boxed.isAssignableFrom(actual) || expected.isAssignableFrom(actual)
    }

    private fun box(c: Class<*>): Class<*> = when (c) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> c
    }

    /**
     * Every non-static method on the hierarchy whose name contains [hint].
     *
     * Used by the `hookAllMethods` sites (which accept any parameter list) so a renamed method
     * can still be hooked: the caller keeps its name-based hook and only falls back to this when
     * that bound nothing.
     */
    fun findByNameHint(cls: Class<*>?, hint: String, paramCount: Int? = null): List<Method> {
        if (cls == null) return emptyList()
        val out = mutableListOf<Method>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (m in try { c.declaredMethods } catch (_: Throwable) { emptyArray() }) {
                if (Modifier.isStatic(m.modifiers) || Modifier.isAbstract(m.modifiers)) continue
                if (!m.name.contains(hint, ignoreCase = true)) continue
                if (paramCount != null && m.parameterTypes.size != paramCount) continue
                out += m
            }
            c = c.superclass
        }
        return out.distinctBy { it.name + it.parameterTypes.joinToString(",") }
    }

    // ── fields ───────────────────────────────────────────────────────────────    /** Exact field name, walking the hierarchy. Never guesses by type. */
    fun findField(cls: Class<*>?, names: List<String>): Field? {
        if (cls == null) return null
        var c: Class<*>? = cls
        while (c != null) {
            for (name in names) {
                try {
                    return c.getDeclaredField(name).apply { isAccessible = true }
                } catch (_: Throwable) { /* try next */ }
            }
            c = c.superclass
        }
        return null
    }

    /**
     * Unique field of exactly [type] whose name contains [hint].
     *
     * Only used where the type is a single-instance object (e.g. the row's content view), never
     * for primitives. Returns null when the hierarchy holds more than one match.
     */
    fun findUniqueFieldOfType(cls: Class<*>?, type: Class<*>, hint: String? = null): Field? {
        if (cls == null) return null
        val matches = mutableListOf<Field>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in try { c.declaredFields } catch (_: Throwable) { emptyArray() }) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.type != type) continue
                if (hint != null && !f.name.contains(hint, ignoreCase = true)) continue
                matches += f
            }
            c = c.superclass
        }
        val distinct = matches.distinctBy { it.declaringClass.name + "#" + it.name }
        return when {
            distinct.size == 1 -> distinct.first().apply { isAccessible = true }
            distinct.size > 1 -> {
                Log.w(TAG, "resolver field-of-type ${type.simpleName} ambiguous: " +
                    distinct.joinToString(",") { it.name })
                null
            }
            else -> null
        }
    }
}
