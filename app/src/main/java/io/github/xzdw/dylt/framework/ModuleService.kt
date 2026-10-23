package io.github.xzdw.dylt.framework

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * The framework service this app is currently connected to, if any.
 *
 * [ModuleActivationMonitor] owns the binder lifecycle and publishes it here so
 * the settings layer can mirror every committed configuration into the
 * framework's remote preferences — the channel a hooked process listens on
 * instead of polling for changes. Every accessor is null-safe: without the
 * service the module simply keeps its broadcast/provider path.
 */
object ModuleService {
    @Volatile
    private var service: XposedService? = null

    /** The remote-preference group both sides use. */
    const val CONFIG_GROUP = "config"

    fun install(value: XposedService?) {
        service = value
    }

    fun isBound(): Boolean = service != null

    fun remotePreferences(group: String = CONFIG_GROUP): SharedPreferences? =
        service?.let { runCatching { it.getRemotePreferences(group) }.getOrNull() }
}
