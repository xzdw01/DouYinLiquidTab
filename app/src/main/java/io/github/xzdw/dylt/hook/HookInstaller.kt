package io.github.xzdw.dylt.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import io.github.xzdw.dylt.hookx.MethodHook
import io.github.xzdw.dylt.hookx.Xposed
import io.github.xzdw.dylt.targets.AppCatalog
import io.github.xzdw.dylt.targets.TargetSpec
import io.github.xzdw.dylt.hook.adapters.TargetAdapterRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Installs this module's hooks for one loaded package.
 *
 * [io.github.xzdw.dylt.hook.LiquidTabModule] is the only caller. The installer
 * arms at most once per target package and process, so a package that reports
 * readiness more than once cannot install the shared `Activity`/`Application`
 * hooks twice.
 */
object HookInstaller {
    private const val SYSTEM_PICKER_KEY = "system_picker"

    private val armed = ConcurrentHashMap<String, Boolean>()

    fun install(context: HookLoadContext) {
        val spec = AppCatalog.forPackage(context.packageName) ?: return
        if (context.processName != context.packageName &&
            spec.uiProcessSuffixes.none { context.processName == "${context.packageName}:$it" }
        ) return
        if (armed.putIfAbsent(context.packageName, true) != null) return

        android.util.Log.i(
            "LiquidTabInject",
            "HOOK-ENTRY pkg=${context.packageName} key=${spec.key}",
        )

        var initialized = false
        Xposed.hookAllMethods(Application::class.java, "attach", object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // Douyin attaches several Application instances per process, so
                // only the first one is reported; the rest return immediately.
                if (initialized) return
                android.util.Log.i(
                    "LiquidTabInject",
                    "ATTACH pkg=${context.packageName} args=${param.args.size} " +
                        "arg0=${param.args.firstOrNull()?.javaClass?.name}",
                )
                val appContext = param.args.firstOrNull() as? Context ?: return
                initialized = true
                val config = HookConfigReader.readFresh(appContext, spec.packageName)
                android.util.Log.i("LiquidTabInject", "CONFIG pkg=${spec.packageName} enabled=${config.enabled}")
                if (!config.enabled) {
                    Xposed.log(
                        "[OfflineGlass] disabled ${spec.packageName}; no target hooks installed",
                    )
                    return
                }
                installEnabledHooks(context, spec)
                HookConfigReader.startPreferencePush(spec.packageName)
                Xposed.reportHookInstall()
                android.util.Log.i("LiquidTabInject", "INSTALLED pkg=${spec.packageName}")
            }
        })
    }

    private fun installEnabledHooks(
        context: HookLoadContext,
        spec: TargetSpec,
    ) {
        if (spec.key == SYSTEM_PICKER_KEY) {
            installSystemPickerHooks(spec)
            return
        }
        runCatching {
            val adapter = TargetAdapterRegistry.forSpec(spec)
            TargetHooks.install(context, spec)
            Xposed.hookAllMethods(Activity::class.java, "onPostResume", object : MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (spec.covers(activity.packageName)) {
                        AppMasterSwitchReceiver.ensureRegistered(activity, spec.packageName)
                        if (adapter?.isBlockingOverlayActivity(activity) == true) {
                            GlassInstaller.setAdapterBlockingOverlayVisible(activity.packageName, true)
                        }
                        HookCoordinator.onActivityResumed(activity, spec)
                        // Overlay activities sit on top of the shell without touching the
                        // host window, so nothing would otherwise re-run its page gate.
                        GlassInstaller.notifyForegroundActivityChanged()
                    }
                }
            })
            Xposed.hookAllMethods(Activity::class.java, "onStop", object : MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (adapter?.isBlockingOverlayActivity(activity) == true) {
                        GlassInstaller.setAdapterBlockingOverlayVisible(activity.packageName, false)
                    }
                }
            })
            Xposed.hookAllMethods(Activity::class.java, "onDestroy", object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    (param.thisObject as? Activity)?.let { activity ->
                        if (adapter?.isBlockingOverlayActivity(activity) == true) {
                            GlassInstaller.setAdapterBlockingOverlayVisible(activity.packageName, false)
                        }
                        HookCoordinator.onActivityDestroyed(activity)
                    }
                }
            })
            // Adapter-owned window gesture observation without replacing target listeners.
            if (TargetAdapterRegistry.forSpec(spec)?.observeWindowTouches == true) {
                Xposed.hookAllMethods(Activity::class.java, "dispatchTouchEvent", object : MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        GlassInstaller.notifyWindowTouch(
                            param.thisObject as? Activity ?: return,
                            param.args.firstOrNull() as? MotionEvent ?: return,
                        )
                    }
                })
            }
            // Hook Activity.finish() — intercept back-from-video on the main
            // tab activity only, so sub-activities (post detail, etc.) can
            // finish normally.
            Xposed.hookAllMethods(Activity::class.java, "finish", object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.isNotEmpty()) return
                    val activity = param.thisObject as? Activity ?: return
                    if (TargetAdapterRegistry.forPackage(activity.packageName)?.isBackPeekActivity(activity) == true) {
                        if (GlassInstaller.isAdapterBackPeekTabActive(activity)) {
                            HookCoordinator.onActivityBackPressed(activity)
                            param.result = null
                        }
                    }
                }
            })
            Xposed.hookAllMethods(Activity::class.java, "moveTaskToBack", object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (TargetAdapterRegistry.forPackage(activity.packageName)?.isBackPeekActivity(activity) == true) {
                        if (GlassInstaller.isAdapterBackPeekTabActive(activity)) {
                            HookCoordinator.onActivityBackPressed(activity)
                            param.result = false
                        }
                    }
                }
            })
            Xposed.log("[OfflineGlass] loaded ${spec.packageName} via ${spec.source}")
        }.onFailure {
            Xposed.log("[OfflineGlass] hook install failed for ${spec.packageName}: $it")
        }
    }

    /**
     * Lightweight handling for the system "open with / share" chooser card.
     * This spec carries no liquid-bar adapter; it only re-seats the card's
     * bottom gesture bar (undoing any immersive/climbing effect) so the white
     * scrive sits flush against the screen. The module switch toggles whether
     * this process is hooked at all, so flipping it is enough to enable or
     * disable the behaviour during development.
     */
    private fun installSystemPickerHooks(spec: TargetSpec) {
        Xposed.hookAllMethods(Activity::class.java, "onPostResume", object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (spec.covers(activity.packageName)) {
                    AppMasterSwitchReceiver.ensureRegistered(activity, spec.packageName)
                    GlassInstaller.applySystemPickerBottomBar(activity)
                }
            }
        })
        Xposed.log("[OfflineGlass] loaded ${spec.packageName} (system picker lightweight)")
    }

    private fun findViewByClass(root: android.view.View, className: String): android.view.View? {
        if (root.javaClass.simpleName == className) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findViewByClass(root.getChildAt(i), className)
                if (found != null) return found
            }
        }
        return null
    }
}
