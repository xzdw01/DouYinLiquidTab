package io.github.xzdw.dylt.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.xzdw.dylt.hookx.Xposed

/**
 * The module entry, loaded from `META-INF/xposed/java_init.list`.
 *
 * This is the only entry the module ships: the legacy `assets/xposed_init` path
 * and the `de.robv.android.xposed` API it needs were removed once the hook layer
 * had moved to libxposed. The framework hands the module its service and its
 * remote preferences through this entry, which is what the manager UI's
 * activation card and the configuration push rely on.
 */
class LiquidTabModule : XposedModule() {
    private var processName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // Only the first package of a process carries the Application the hooks
        // attach to; later packages are code-only loads.
        if (!param.isFirstPackage) return
        Log.i(TAG, "entry: ${param.packageName} in $processName")
        Xposed.install(this)
        HookInstaller.install(
            HookLoadContext(
                classLoader = param.classLoader,
                packageName = param.packageName,
                processName = processName,
            ),
        )
    }

    private companion object {
        const val TAG = "LiquidTabModule"
    }
}
