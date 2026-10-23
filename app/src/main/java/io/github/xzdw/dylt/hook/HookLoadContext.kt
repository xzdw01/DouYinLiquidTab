package io.github.xzdw.dylt.hook

/**
 * The per-process facts [HookInstaller] needs, supplied by the module entry.
 *
 * The module has exactly one entry now: the libxposed `XposedModule` class in
 * `META-INF/xposed/java_init.list`. Keeping the entry's facts in a plain value
 * object means the installer never touches framework types.
 */
class HookLoadContext(
    val classLoader: ClassLoader,
    val packageName: String,
    val processName: String,
)
