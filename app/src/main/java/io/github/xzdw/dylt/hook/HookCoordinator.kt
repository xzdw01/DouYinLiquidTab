package io.github.xzdw.dylt.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.xzdw.dylt.hook.adapters.TargetAdapterRegistry
import io.github.xzdw.dylt.hookx.MethodHook
import io.github.xzdw.dylt.hookx.Xposed
import io.github.xzdw.dylt.targets.TargetSpec
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object HookCoordinator {
    private val sessions = WeakHashMap<Activity, ScannerSession>()
    private var predictiveBackHookAttempted = false
    private var lastResumedActivity: WeakReference<Activity>? = null

    /**
     * The activity most recently resumed in this process.
     *
     * Overlay pages (Douyin's author profile, video detail, photo flow) are separate
     * activities drawn on top of the main shell. The shell's own view tree keeps
     * reporting itself as visible, and the host's own context always resolves back to
     * the shell activity it was created in, so the real foreground activity is the only
     * reliable way to tell that an overlay page is on screen.
     */
    fun resumedActivity(): Activity? = lastResumedActivity?.get()

    /**
     * System back comes through two channels: the `Activity.onBackPressed`
     * hook (installed with the other shared hooks) and, once the app opts into
     * the predictive back gesture on API 33+, androidx's OnBackPressedDispatcher,
     * which the framework calls straight from OnBackInvokedCallback. The androidx
     * class is only loadable from the host app's own class loader after it
     * bootstraps, so retry the dispatcher hook on every resume until it sticks.
     */
    fun onActivityBackPressed(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        return GlassInstaller.notifyBackPressed(activity)
    }

    @Synchronized
    private fun maybeHookPredictiveBackDispatcher(activity: Activity) {
        if (predictiveBackHookAttempted) return
        val dispatcherClass = runCatching {
            Xposed.findClass(
                "androidx.activity.OnBackPressedDispatcher",
                activity.classLoader,
            )
        }.getOrNull() ?: return
        predictiveBackHookAttempted = true
        runCatching {
            Xposed.hookAllMethods(
                dispatcherClass,
                "onBackPressed",
                object : MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val current = lastResumedActivity?.get() ?: return
                        if (current.isFinishing || current.isDestroyed) return
                        val dispatcher = runCatching {
                            Xposed.callMethod(
                                current,
                                "getOnBackPressedDispatcher",
                            )
                        }.getOrNull() ?: return
                        if (dispatcher !== param.thisObject) return
                        if (onActivityBackPressed(current)) {
                            param.result = null
                        }
                    }
                },
            )
        }.onFailure {
            predictiveBackHookAttempted = false
        }
    }

    @Synchronized
    fun onActivityResumed(activity: Activity, spec: TargetSpec) {
        lastResumedActivity = WeakReference(activity)
        maybeHookPredictiveBackDispatcher(activity)
        if (!matchesActivity(activity, spec)) return
        val config = HookConfigReader.read(activity, spec.packageName)
        if (!config.enabled) return

        val session = sessions.getOrPut(activity) { ScannerSession(activity, spec) }
        session.scheduleScans()
    }

    fun onNavigationView(view: View, spec: TargetSpec) {
        val navigation = view as? ViewGroup ?: return
        val activity = findActivity(view.context) ?: return
        if (!spec.covers(activity.packageName) || activity.isFinishing || activity.isDestroyed) return
        listOf(0L, 80L, 260L).forEach { delay ->
            navigation.postDelayed({
                if (!navigation.isAttachedToWindow || navigation.parent == null) return@postDelayed
                val config = HookConfigReader.read(activity, spec.packageName)
                if (!config.enabled) return@postDelayed
                val slots = NavigationFinder.estimateSlotCount(navigation, spec)
                // A mid-inflate row reports 0 (e.g. Meituan Takeout's
                // server-driven tabs); installing that partial count would
                // lock it in because install() is idempotent per source view.
                if (slots < 2) return@postDelayed
                GlassInstaller.install(activity, navigation, slots, config, spec)
            }, delay)
        }
    }

    private fun findActivity(context: Context?): Activity? {
        var current = context
        repeat(12) {
            when (current) {
                is Activity -> return current
                is ContextWrapper -> current = current.baseContext
                else -> return null
            }
        }
        return null
    }

    private fun matchesActivity(activity: Activity, spec: TargetSpec): Boolean {
        if (spec.activityHints.isEmpty()) return true
        val name = activity.javaClass.name
        return spec.activityHints.any { hint -> name == hint || name.endsWith(hint.substringAfterLast('.')) }
    }

    @Synchronized
    fun onActivityDestroyed(activity: Activity) {
        sessions.remove(activity)?.dispose()
    }
}

private class ScannerSession(activity: Activity, private val spec: TargetSpec) {
    private val activityRef = WeakReference(activity)
    private var installed = false
    private var lastScan = 0L
    private var listener: ViewTreeObserver.OnGlobalLayoutListener? = null

    fun scheduleScans() {
        val activity = activityRef.get() ?: return
        val root = activity.window?.decorView ?: return
        // Navigation roots can be recreated after drawer/back/configuration changes.
        installed = false
        listOf(0L, 180L, 650L, 1_500L, 3_000L).forEach { delay ->
            root.postDelayed({ scan() }, delay)
        }
        if (listener == null) {
            listener = ViewTreeObserver.OnGlobalLayoutListener {
                if (!installed && SystemClock.uptimeMillis() - lastScan > 300L) scan()
            }.also { root.viewTreeObserver.addOnGlobalLayoutListener(it) }
        }
    }

    private fun scan() {
        if (installed) return
        lastScan = SystemClock.uptimeMillis()
        val activity = activityRef.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val config = HookConfigReader.read(activity, spec.packageName)
        if (!config.enabled) return

        val root = activity.window?.decorView ?: return
        val candidate = NavigationFinder.find(root, spec)
        // App-specific side surfaces are owned by their adapter, not this scanner.
        TargetAdapterRegistry.forSpec(spec)?.beforeNavigationScan(activity)
        if (candidate == null) return
        installed = GlassInstaller.install(activity, candidate.view, candidate.slotCount, config, spec)
    }

    fun dispose() {
        val root = activityRef.get()?.window?.decorView ?: return
        listener?.let { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        listener = null
    }
}
