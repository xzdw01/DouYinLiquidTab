package io.github.xzdw.dylt.targets

enum class AdapterSource {
    DEDICATED,
    GENERIC_FALLBACK,
}

data class HideOption(val label: String, val index: Int)

data class HookSignal(val className: String, val methods: List<String>)

data class TargetSpec(
    val key: String,
    val packageName: String,
    val displayName: String,
    val source: AdapterSource,
    val activityHints: Set<String> = emptySet(),
    val idHints: Set<String> = emptySet(),
    val classHints: Set<String> = emptySet(),
    val textHints: Set<String> = emptySet(),
    val preferredSlots: IntRange = 2..6,
    val hideOptions: List<HideOption> = emptyList(),
    val supportsPostButton: Boolean = false,
    val supportsChannel: Boolean = false,
    val defaultIconOnly: Boolean = false,
    val defaultAccentColor: Int = -30208,
    val entryLabels: List<String> = emptyList(),
    val hookSignals: List<HookSignal> = emptyList(),
    val drawsNavigationInOptics: Boolean = false,
    val glassAccentColor: Int? = null,
    // Extra processes that host this app's UI but keep their own package name
    // (e.g. Theme Store delegates its home UI to the system theme manager
    // process). They share the primary package's config and appear in the
    // module UI only under the primary entry.
    val aliasPackages: Set<String> = emptySet(),
    /** Exact package-relative UI process suffixes; empty preserves main-process-only injection. */
    val uiProcessSuffixes: Set<String> = emptySet(),
) {
    fun covers(packageName: String): Boolean =
        packageName == this.packageName || packageName in aliasPackages
}

object AppCatalog {
    /**
     * Keys whose adapter directories are kept out of the release scope. Douyin is the
     * only adapter carried by this build, so nothing is archived; the declaration stays
     * because tools/verify-adapter-workspaces.ps1 reads it to derive the active keys.
     */
    private val archivedKeys: Set<String> = setOf()
    // Stable display order for supported targets. Dedicated adapters take precedence.
    private val allTargets: List<TargetSpec> = TargetSpecRegistry.all
    val targets: List<TargetSpec> = allTargets.filterNot { it.key in archivedKeys }
    private val byPackage = targets.associateBy(TargetSpec::packageName)
    private val byAlias = targets.flatMap { spec ->
        spec.aliasPackages.map { pkg -> pkg to spec }
    }.toMap()
    fun forPackage(packageName: String): TargetSpec? =
        byPackage[packageName] ?: byAlias[packageName]
}
