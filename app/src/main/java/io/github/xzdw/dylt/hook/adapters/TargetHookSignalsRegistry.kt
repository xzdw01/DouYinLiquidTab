package io.github.xzdw.dylt.hook.adapters

internal object TargetHookSignalsRegistry {
    fun forKey(key: String): List<TargetHookSignal> {
        TargetAdapterRegistry.forKey(key)?.hookSignals()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        return io.github.xzdw.dylt.targets.TargetSpecRegistry.all
            .firstOrNull { it.key == key }?.hookSignals ?: emptyList()
    }
}
