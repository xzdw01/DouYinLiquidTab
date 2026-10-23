package io.github.xzdw.dylt.targets

/** Stable display order; each app owns its own TargetSpec.kt. */
internal object TargetSpecRegistry {
    val all: List<TargetSpec> = listOf(
        io.github.xzdw.dylt.hook.adapters.douyin.targetSpec,
    )
    val byKey: Map<String, TargetSpec> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        all.associateBy(TargetSpec::key)
    }
}
