package io.github.xzdw.dylt.hook

import android.app.Activity
import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import io.github.xzdw.dylt.hookx.MethodHook
import io.github.xzdw.dylt.hookx.Xposed
import io.github.xzdw.dylt.hook.adapters.TargetAdapterRegistry
import io.github.xzdw.dylt.hook.adapters.TargetHookSignalsRegistry
import io.github.xzdw.dylt.hook.adapters.TargetHookSignal
import io.github.xzdw.dylt.targets.TargetSpec

private typealias Signal = TargetHookSignal

/** Navigation signals used by target-specific adapters. */
object TargetHooks {


    fun install(context: HookLoadContext, spec: TargetSpec) {
        val adapter = TargetAdapterRegistry.forSpec(spec)
        adapter?.installHooks(context, spec)
        // Do not force DeviceTabV4ViewModel.loadDevice(false) on return. That
        // rebuild resets the outer device list scroll position and moves the
        // still-present "More settings" row back behind the floating bar. The
        // actual return failure is handled at the navigation binding layer.
        (signalsFor(spec.key) + adapter?.hookSignals().orEmpty()).forEach { signal ->
            val targetClass = runCatching {
                Xposed.findClass(signal.className, context.classLoader)
            }.getOrNull() ?: return@forEach
            signal.methods.forEach { method ->
                runCatching {
                    Xposed.hookAllMethods(targetClass, method, object : MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            dispatch(param.thisObject, spec)
                        }
                    })
                }
            }
        }
    }



    private fun dispatch(instance: Any?, spec: TargetSpec) {
        when (instance) {
            is View -> HookCoordinator.onNavigationView(instance, spec)
            is Activity -> HookCoordinator.onActivityResumed(instance, spec)
            null -> Unit
            else -> runCatching {
                (Xposed.callMethod(instance, "getActivity") as? Activity)
                    ?.let { HookCoordinator.onActivityResumed(it, spec) }
            }
        }
    }

    private fun signalsFor(key: String): List<Signal> = TargetHookSignalsRegistry.forKey(key)

}
