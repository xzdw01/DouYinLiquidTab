package io.github.xzdw.dylt.hookx

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * The module's single framework entry point: a thin facade over the libxposed
 * API (`io.github.libxposed.api`) plus the before/after callback style this
 * codebase's hook bodies are written in.
 *
 * The module ships only the modern entry (`META-INF/xposed/java_init.list`), so
 * the facade no longer abstracts over a second framework generation. It stays a
 * facade for two reasons: hook bodies keep the familiar
 * `MethodHook.beforeHookedMethod` / `afterHookedMethod` shape instead of
 * touching `Hooker`/`Chain`, and every framework call stays in one place.
 */
object Xposed {
    private const val TAG = "OfflineGlass"

    @Volatile
    private var framework: XposedInterface? = null

    private val methodCache = ConcurrentHashMap<String, Method>()

    private val remotePrefs = ConcurrentHashMap<String, SharedPreferences>()

    /** Called once per process by [io.github.xzdw.dylt.hook.LiquidTabModule]. */
    fun install(value: XposedInterface) {
        framework = value
    }

    private fun bound(): XposedInterface =
        framework ?: error("Xposed interface not installed; the module entry must call Xposed.install")

    /**
     * Framework-log-only sink for the module's own lines (framework log + logcat mirror).
     *
     * Reserved for state changes and failures: it costs a Binder call, so per-hook or per-pass
     * detail belongs in [note].
     */
    fun log(message: String) {
        // The framework log keeps module lines in the LSPosed module log; the
        // logcat mirror is what a device-side check can read. The framework call
        // is best-effort: it must never be the reason a hook body fails.
        runCatching { bound().log(Log.INFO, TAG, message) }
        Log.i(TAG, message)
    }

    /** Logcat-only mirror, for detail that would otherwise cost a Binder call. */
    private fun note(message: String) {
        Log.i(TAG, "[OfflineGlass] $message")
    }

    fun findClass(name: String, classLoader: ClassLoader): Class<*> =
        Class.forName(name, false, classLoader)

    fun callMethod(instance: Any, methodName: String, vararg args: Any?): Any? {
        val type = instance.javaClass
        val key = type.name + '#' + methodName + '/' + args.size
        val method = methodCache[key] ?: findMethod(type, methodName, args).also {
            runCatching { it.isAccessible = true }
            methodCache[key] = it
        }
        return try {
            method.invoke(instance, *args)
        } catch (e: InvocationTargetException) {
            // Surface the target's own failure as a runtime error; every call
            // site already wraps these in runCatching.
            throw RuntimeException(e.cause)
        } catch (e: IllegalAccessException) {
            // Hidden-API enforcement: fall back to the bypass the module already
            // ships for exactly these target-internal methods.
            runCatching { HiddenApiBypass.invoke(type, instance, methodName, *args) }
                .getOrElse { throw RuntimeException(e) }
        }
    }

    /** Hooks every method with this name, walking up the class hierarchy. */
    fun hookAllMethods(target: Class<*>, methodName: String, hook: MethodHook) {
        var hooked = 0
        var deoptimized = 0
        hierarchy(target).forEach { type ->
            type.declaredMethods.forEach { method ->
                if (method.name == methodName && !Modifier.isAbstract(method.modifiers)) {
                    when (hookExecutable(method, hook)) {
                        HookResult.DEOPTIMIZED -> {
                            hooked++
                            deoptimized++
                        }
                        HookResult.HOOKED -> hooked++
                        HookResult.FAILED -> Unit
                    }
                }
            }
        }
        hookInstallations += hooked
        hookDeoptimizations += deoptimized
        note("hookAllMethods ${target.name}#$methodName -> $hooked (deopt $deoptimized)")
    }

    fun hookAllConstructors(target: Class<*>, hook: MethodHook) {
        var hooked = 0
        var deoptimized = 0
        target.declaredConstructors.forEach { constructor ->
            when (hookExecutable(constructor, hook)) {
                HookResult.DEOPTIMIZED -> {
                    hooked++
                    deoptimized++
                }
                HookResult.HOOKED -> hooked++
                HookResult.FAILED -> Unit
            }
        }
        hookInstallations += hooked
        hookDeoptimizations += deoptimized
        note("hookAllConstructors ${target.name} -> $hooked (deopt $deoptimized)")
    }

    /**
     * One framework-log line per install.
     *
     * The per-class detail above stays on logcat: `log` is a Binder round trip to the daemon, and
     * this module installs twenty-odd hooks inside `Application.attach`, i.e. before the first
     * frame. A failure still goes through [log] the moment it happens.
     */
    fun reportHookInstall() {
        if (hookInstallations == 0) return
        log(
            "[OfflineGlass] hooks installed: $hookInstallations member(s), " +
                "$hookDeoptimizations deoptimized",
        )
    }

    /**
     * Framework-managed preferences, served by the daemon over Binder. Requests
     * are cached per group so a change listener can be registered on a stable
     * instance.
     */
    fun remotePreferences(group: String): SharedPreferences? =
        runCatching {
            remotePrefs.computeIfAbsent(group) { bound().getRemotePreferences(it) }
        }.onFailure {
            Log.w(TAG, "[OfflineGlass] framework remote preferences unavailable", it)
        }.getOrNull()

    private fun hookExecutable(executable: Executable, hook: MethodHook): HookResult =
        runCatching {
            // A hook on a method ART already inlined never fires until the
            // executable is deoptimized (measured on Application#attach).
            val deoptimized = runCatching { bound().deoptimize(executable) }.getOrDefault(false)
            bound().hook(executable).intercept { chain -> dispatch(hook, chain) }
            if (deoptimized) HookResult.DEOPTIMIZED else HookResult.HOOKED
        }.onFailure {
            // An unsupported target is skipped, exactly like the framework's own
            // loader does for the entry classes it cannot hook.
            log("[OfflineGlass] cannot hook ${describe(executable)}: $it")
        }.getOrDefault(HookResult.FAILED)

    /** What installing one hook cost, so the install can report deoptimizations in one line. */
    private enum class HookResult { FAILED, HOOKED, DEOPTIMIZED }

    private var hookInstallations = 0

    private var hookDeoptimizations = 0

    private fun describe(executable: Executable): String =
        executable.declaringClass.name + "." + executable.name +
            executable.parameterTypes.joinToString(", ", "(", ")") { it.simpleName }

    /** The before -> proceed -> after lifecycle the hook bodies expect. */
    private fun dispatch(hook: MethodHook, chain: XposedInterface.Chain): Any? {
        val param = HookParam(chain.thisObject, chain.args.toMutableList())
        runCatching { hook.beforeHookedMethod(param) }.onFailure { report("before", chain, it) }
        if (param.isResultSet) return param.result

        var failure: Throwable? = null
        val produced: Any? = try {
            chain.proceed(param.args.toTypedArray())
        } catch (t: Throwable) {
            failure = t
            null
        }
        param.provideResult(produced)
        runCatching { hook.afterHookedMethod(param) }.onFailure { report("after", chain, it) }
        failure?.let { throw it }
        return param.result
    }

    private fun report(stage: String, chain: XposedInterface.Chain, error: Throwable) {
        val where = "${chain.executable.declaringClass.name}.${chain.executable.name}"
        log("[OfflineGlass] $stage hook failed on $where: $error")
    }

    private fun hierarchy(target: Class<*>): List<Class<*>> {
        val types = ArrayList<Class<*>>(4)
        var current: Class<*>? = target
        while (current != null && current != Any::class.java) {
            types.add(current)
            current = current.superclass
        }
        return types
    }

    /**
     * Resolves a zero-argument method once, for callers that invoke it on a hot path.
     *
     * [callMethod] rebuilds its cache key — three string concatenations — and re-queries the
     * map on *every* call. That is fine occasionally, but the backdrop sweep invokes
     * `updateDisplayListIfDirty` once per sibling per display frame, where building the key
     * cost more than the reflective invoke it was guarding.
     *
     * Returns null when the method cannot be resolved; callers should then simply skip.
     */
    fun resolveMethod(type: Class<*>, methodName: String): Method? {
        val key = type.name + '#' + methodName + "/0"
        methodCache[key]?.let { return it }
        return runCatching {
            findMethod(type, methodName, emptyArray()).also {
                runCatching { it.isAccessible = true }
                methodCache[key] = it
            }
        }.getOrNull()
    }

    /**
     * [callMethod] for a method already obtained from [resolveMethod].
     *
     * Keeps the same error contract: the target's own failure surfaces as a RuntimeException,
     * and a hidden-API rejection falls back to the bypass.
     */
    fun callResolved(instance: Any, method: Method): Any? =
        try {
            method.invoke(instance)
        } catch (e: InvocationTargetException) {
            throw RuntimeException(e.cause)
        } catch (e: IllegalAccessException) {
            runCatching { HiddenApiBypass.invoke(instance.javaClass, instance, method.name) }
                .getOrElse { throw RuntimeException(e) }
        }

    private fun findMethod(type: Class<*>, methodName: String, args: Array<out Any?>): Method {
        var current: Class<*>? = type
        while (current != null) {
            val candidates = current.declaredMethods.filter { it.name == methodName && it.parameterCount == args.size }
            candidates.firstOrNull { matches(it.parameterTypes, args, exact = true) }?.let { return it }
            candidates.firstOrNull { matches(it.parameterTypes, args, exact = false) }?.let { return it }
            current = current.superclass
        }
        throw NoSuchMethodException("$type.$methodName/${args.size}")
    }

    private fun matches(parameters: Array<Class<*>>, args: Array<out Any?>, exact: Boolean): Boolean =
        parameters.indices.all { index ->
            val arg = args[index] ?: return@all !parameters[index].isPrimitive
            if (exact) parameters[index] == arg.javaClass else parameters[index].isInstance(arg)
        }

    private class HookParam(
        override val thisObject: Any?,
        override val args: MutableList<Any?>,
    ) : MethodHook.MethodHookParam {
        private var current: Any? = null
        private var resultSet = false

        override val isResultSet: Boolean get() = resultSet

        override var result: Any?
            get() = current
            set(value) {
                current = value
                resultSet = true
            }

        /** Records the value the original returned without claiming it was set. */
        fun provideResult(value: Any?) {
            current = value
        }
    }
}

/**
 * A hook with the before/after lifecycle the module's hook bodies are written
 * against:
 *
 * - [MethodHookParam.result] assigned from [beforeHookedMethod] skips the
 *   original method and returns that value;
 * - [MethodHookParam.args] is writable and the change reaches the original;
 * - [afterHookedMethod] runs after the original and may replace its result.
 */
abstract class MethodHook {
    open fun beforeHookedMethod(param: MethodHookParam) {}

    open fun afterHookedMethod(param: MethodHookParam) {}

    interface MethodHookParam {
        /** The receiver, or null for static methods and constructors. */
        val thisObject: Any?

        /** The live argument list: writes reach the original method. */
        val args: MutableList<Any?>

        /** True once [result] has been assigned for this invocation. */
        val isResultSet: Boolean

        /**
         * The value the original returned. Assigning it means "use this value":
         * from [beforeHookedMethod] the original method is skipped, from
         * [afterHookedMethod] its result is replaced.
         */
        var result: Any?
    }
}
