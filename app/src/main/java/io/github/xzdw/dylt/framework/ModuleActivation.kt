package io.github.xzdw.dylt.framework

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.xzdw.dylt.targets.AppCatalog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One process the Xposed framework reports as currently hooked by this module.
 *
 * This is the framework's own view of the module — the only activation signal
 * the module does not have to invent for itself. It is filled from
 * [XposedService.getRunningTargets], which needs service API 102.
 */
data class HookedProcess(
    val processName: String,
    val pid: Int,
    val state: HookedTarget.State,
    val loadedVersionCode: Long,
) {
    /** The owning package; a process name carries an optional `:suffix`. */
    val packageName: String get() = processName.substringBefore(':')

    val displayName: String get() = AppCatalog.forPackage(packageName)?.displayName ?: processName
}

/**
 * The activation facts the framework reports for this module.
 *
 * The module never reports its own activation: the framework pushes its service
 * binder into the `XposedProvider` that `io.github.libxposed:service` declares
 * in this app's manifest, and only an enabled module receives one. Everything
 * below therefore comes from that service.
 *
 * Only what the UI acts on is kept here — the framework's name/version and its
 * API level are diagnostics and deliberately have no field.
 */
data class ModuleActivation(
    /** True once the framework has connected to this module's service provider. */
    val serviceBound: Boolean = false,
    /** Packages the user activated for this module in the framework manager. */
    val scope: List<String> = emptyList(),
    /** Processes running the module right now (service API 102 and above). */
    val hookedProcesses: List<HookedProcess> = emptyList(),
    /** Why the last service query failed, if it did. */
    val failure: String? = null,
) {
    /** Hooked processes the framework does not report as failed. */
    val workingProcesses: List<HookedProcess> =
        hookedProcesses.filter { it.state != HookedTarget.State.FAILED }

    /** The module is registered and enabled in the framework. */
    val isEnabled: Boolean get() = serviceBound

    /** The module is loaded in at least one live process at this moment. */
    val isWorking: Boolean get() = workingProcesses.isNotEmpty()
}

/**
 * Reads module activation from the Xposed framework's own service.
 *
 * The framework starts this app's process and calls
 * `XposedProvider.call("SendBinder", ...)` when the module is enabled, which
 * stores the binder inside [XposedServiceHelper] (cached until a listener is
 * registered, replayed afterwards). [start] installs that listener, [refresh]
 * re-queries the live state, and every result is delivered on the main thread.
 *
 * A module that is not enabled in the framework, or a framework that does not
 * implement the libxposed service API, never produces a binder: that absence is
 * the "not activated" answer rather than a cached timestamp of our own.
 */
object ModuleActivationMonitor {
    private const val TAG = "LiquidTabFramework"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LiquidTab-Activation").apply { isDaemon = true }
    }

    private val listenerInstalled = AtomicBoolean(false)
    private val queryInFlight = AtomicBoolean(false)

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var current = ModuleActivation()

    private var onChanged: ((ModuleActivation) -> Unit)? = null

    /** The most recent activation snapshot. */
    fun snapshot(): ModuleActivation = current

    /**
     * Installs the framework service listener (once per process) and starts
     * reporting activation through [onChanged] on the main thread.
     */
    fun start(onChanged: (ModuleActivation) -> Unit) {
        this.onChanged = onChanged
        if (listenerInstalled.compareAndSet(false, true)) {
            runCatching {
                XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(bound: XposedService) {
                        Log.i(TAG, "framework service bound")
                        service = bound
                        // The settings layer mirrors every committed change into
                        // the framework's remote preferences, which is the push
                        // channel a hooked process listens on.
                        ModuleService.install(bound)
                        refresh()
                    }

                    override fun onServiceDied(dead: XposedService) {
                        Log.i(TAG, "framework service died")
                        if (service === dead) {
                            service = null
                            ModuleService.install(null)
                            publish(ModuleActivation(failure = "框架服务已断开"))
                        } else {
                            refresh()
                        }
                    }
                })
            }.onFailure {
                Log.w(TAG, "cannot register service listener", it)
                publish(ModuleActivation(failure = describe(it)))
            }
        }
        refresh()
    }

    /**
     * Re-reads scope and running targets from the framework. The binder calls
     * run off the main thread; a slow query is dropped rather than queued.
     */
    fun refresh() {
        if (!queryInFlight.compareAndSet(false, true)) return
        worker.execute {
            val next = query()
            queryInFlight.set(false)
            mainHandler.post { publish(next) }
        }
    }

    private fun query(): ModuleActivation {
        val bound = service ?: return ModuleActivation()
        return try {
            val api = bound.apiVersion
            val processes = if (api >= XposedService.API_102) {
                bound.runningTargets.map {
                    HookedProcess(it.processName, it.pid, it.state, it.loadedVersionCode)
                }
            } else {
                emptyList()
            }
            ModuleActivation(
                serviceBound = true,
                scope = bound.scope.orEmpty().distinct(),
                hookedProcesses = processes,
            )
        } catch (t: Throwable) {
            // A dead service or an unimplemented call must not leave a stale
            // "working" state behind; drop the binder and report the failure.
            Log.w(TAG, "framework service query failed", t)
            service = null
            ModuleService.install(null)
            ModuleActivation(failure = describe(t))
        }
    }

    private fun publish(next: ModuleActivation) {
        current = next
        onChanged?.invoke(next)
    }

    private fun describe(t: Throwable): String {
        val detail = t.message.orEmpty().substringBefore(": [").substringBefore(" [").trim().take(96)
        return if (detail.isEmpty()) t.javaClass.simpleName else detail
    }
}
