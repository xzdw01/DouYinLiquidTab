package io.github.xzdw.dylt.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.xzdw.dylt.config.ColorMode
import io.github.xzdw.dylt.config.GlassConfig
import io.github.xzdw.dylt.config.ManagerSettings
import io.github.xzdw.dylt.targets.AppCatalog
import io.github.xzdw.dylt.targets.HideOption
import io.github.xzdw.dylt.targets.TargetSpecRegistry

enum class ManagerPage(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME("主页", Icons.Filled.Home, Icons.Outlined.Home),
    SETTINGS("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
    ABOUT("关于", Icons.Filled.Info, Icons.Outlined.Info),
}

/** The pages the 关于 tab drills into. */
internal enum class ManagerHomeSubpage(val title: String) {
    NONE("关于"),
    ACKNOWLEDGEMENTS("致谢"),
}

/**
 * The settings pages the 设置 tab drills into, two entry levels deep.
 *
 * [BOTTOM_BAR], [MENU_AND_TEXT], [TOP_BAR] and [GLASS] are the first-level entries the
 * 设置 tab lists. [BOTTOM_BAR] and [TOP_BAR] carry their controls directly; the other two
 * are entry hubs that list second-level entries, and every remaining member is a
 * second-level entry carrying the controls themselves.
 */
internal enum class ManagerSettingsSubpage(val title: String) {
    NONE("设置"),
    // First-level entries (listed on the 设置 tab).
    BOTTOM_BAR("底栏布局"),
    MENU_AND_TEXT("菜单与文字"),
    TOP_BAR("顶栏布局"),
    GLASS("玻璃效果"),
    // Second-level entries (listed under 菜单与文字).
    ENTRY_HIDE("入口隐藏"),
    TEXT_EDIT("标签修改"),
    // Second-level entries (listed under 玻璃效果).
    GLASS_MATERIAL("材质"),
    GLASS_OUTLINE("描边与光晕"),
    // Second-level entry (listed under 顶栏): 顶栏标签排序.
    TOP_BAR_ORDER("标签页排序"),
}

data class UiActions(
    val setColorMode: (ColorMode) -> Unit,
    val setLiquidGlassEnabled: (Boolean) -> Unit,

    val setOutlineEnabled: (Boolean) -> Unit,
    val setAppEnabled: (String, Boolean) -> Unit,
    val setMiMarketTabEnabled: (String, Boolean) -> Unit,
    val setGlassConfig: (GlassConfig) -> Unit,
    val previewGlassConfig: (GlassConfig) -> Unit,
    val setAppConfig: (String, GlassConfig) -> Unit,
    val clearAppConfig: (String) -> Unit,
    val applyChanges: () -> Unit,
)

internal fun entryLabels(targetKey: String, settings: io.github.xzdw.dylt.config.ManagerSettings? = null): List<String> {
    val spec = TargetSpecRegistry.all.firstOrNull { it.key == targetKey }
    settings?.appEntryLabels?.get(spec?.packageName)?.takeIf { it.isNotEmpty() }?.let { return it }
    return when (targetKey) {
    "qq" -> listOf("消息", "联系人", "动态")
    else -> TargetSpecRegistry.all.firstOrNull { it.key == targetKey }?.entryLabels ?: emptyList()
}
}

/**
 * The bottom-bar entries the 设置 ▸ 底栏菜单项隐藏 page offers, in bar order.
 *
 * Each entry carries the adapted app's own navigation-slot index, which is the bit
 * `GlassConfig.hiddenMask` stores. The list comes from the active adapter specs rather than
 * from this UI, so the app that owns the slots is also the app that names them — and a slot
 * that is not a navigation entry (Douyin's publish action) is never offered as one.
 */
internal fun bottomBarHideOptions(): List<HideOption> =
    AppCatalog.targets.flatMap { it.hideOptions }.distinctBy { it.index }

/** Labels of the bottom-bar entries [mask] currently hides, in bar order. */
internal fun hiddenBottomBarLabels(mask: Int): List<String> =
    bottomBarHideOptions().filter { mask and (1 shl it.index) != 0 }.map { it.label }

/**
 * The 底栏菜单文字 page's drafts for [raw], in bar order: one name per entry, blank meaning "keep the
 * app's own name" — which is exactly what the page's placeholder shows. The list always has one
 * slot per entry, so a short or empty stored value still fills every row.
 */
internal fun customEntryLabelDrafts(raw: String): List<String> {
    val stored = if (raw.isEmpty()) emptyList() else raw.split('\n')
    return List(bottomBarHideOptions().size) { stored.getOrNull(it)?.trim().orEmpty() }
}

/**
 * Encodes the page's drafts back into the store's one-name-per-line form. Trailing blanks are
 * dropped so "reset the last entry" lands back on the empty default rather than on stored newlines.
 */
internal fun encodeCustomEntryLabels(drafts: List<String>): String =
    drafts.map { it.trim() }.dropLastWhile { it.isEmpty() }.joinToString("\n")

/** The 底栏菜单文字 row summary: the resulting bar labels, or that they are the app's own. */
internal fun customEntryLabelSummary(raw: String): String {
    if (raw.isBlank()) return "首页 / 朋友 / 消息 / 我"
    val drafts = customEntryLabelDrafts(raw)
    return bottomBarHideOptions().mapIndexed { index, option ->
        drafts.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: option.label
    }.joinToString(" / ")
}

/** One choice of 底栏显示样式. [style] is the stored value (see `GlassConfig.bottomBarStyle`). */
internal data class BottomBarStyleOption(
    val style: Int,
    val label: String,
    val summary: String,
)

/**
 * 底栏显示样式, in the order the page shows them. The first entry is the shipped layout, so the
 * store's default (0) and this list's first item are the same choice.
 */
internal val bottomBarStyleOptions = listOf(
    BottomBarStyleOption(
        GlassConfig.BOTTOM_BAR_STYLE_ICON_TEXT,
        "标+字",
        "当前布局：图标在上、文字在下",
    ),
    BottomBarStyleOption(
        GlassConfig.BOTTOM_BAR_STYLE_ICON_ONLY,
        "仅图标",
        "只画图标，上下居中",
    ),
    BottomBarStyleOption(
        GlassConfig.BOTTOM_BAR_STYLE_TEXT_ONLY,
        "仅文字",
        "只画文字，上下居中",
    ),
)

/** The 底栏显示样式 row summary: the chosen option's name. */
internal fun bottomBarStyleLabel(style: Int): String =
    bottomBarStyleOptions.firstOrNull { it.style == style }?.label
        ?: bottomBarStyleOptions.first().label

/**
 * 顶栏标签排序: the tabs Douyin's title bar carries, as a fallback list for the reorder page
 * before the hooked process has reported its own labels. The labels are the ones measured on
 * the feed (the strip hides 热点 / 经验 / 直播 / 团购 off screen); a report replaces them.
 *
 * 推荐 is the owner's fixed first tab: it cannot be dragged and always leads the list.
 */
internal const val TOP_BAR_FIXED_TAB = "推荐"

/** 同城: the location tab's fixed display name (the native label is the current place). */
internal const val TOP_BAR_LOCATION_TAB = GlassConfig.TOP_BAR_LOCATION_TAB

internal val fallbackTopBarTabLabels = listOf(
    TOP_BAR_FIXED_TAB, "关注", "商城", "精选", TOP_BAR_LOCATION_TAB, "团购", "直播", "经验", "热点",
)

/** The candidate labels the reorder page lists: reported by the target when available. */
internal fun topBarTabCandidates(settings: ManagerSettings?): List<String> {
    val spec = TargetSpecRegistry.all.firstOrNull()
    val reported = settings?.appTopTabLabels?.get(spec?.packageName).orEmpty()
    return fixedFirstTopBarTab(
        (reported + fallbackTopBarTabLabels)
            .map { GlassConfig.normalizeTopBarTabLabel(it) }
            .filter { it.isNotEmpty() }
            .distinct(),
    )
}

/**
 * The reorder page's working list: the stored order first, then any candidate it does not
 * mention. An empty store starts with the shipped pair so counts 1/2 reproduce the old bar.
 *
 * Location aliases from an older store (亳州 / 花戏楼 / …) are canonicalised to 同城, so the
 * list keeps nine rows instead of growing a new one whenever the user moves.
 */
internal fun topBarOrderDrafts(raw: String, candidates: List<String>): List<String> {
    val stored = if (raw.isBlank()) {
        emptyList()
    } else {
        raw.split('\n')
            .map { GlassConfig.normalizeTopBarTabLabel(it) }
            .filter { it.isNotEmpty() }
    }
    val shipped = if (stored.isEmpty()) listOf(TOP_BAR_FIXED_TAB, "关注") else emptyList()
    return fixedFirstTopBarTab(
        (stored + shipped + candidates)
            .map { GlassConfig.normalizeTopBarTabLabel(it) }
            .filter { it.isNotEmpty() }
            .distinct(),
    )
}

/** Encodes the reorder page's list in the store's one-label-per-line form. */
internal fun encodeTopBarTabOrder(order: List<String>): String =
    fixedFirstTopBarTab(
        order.map { GlassConfig.normalizeTopBarTabLabel(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(GlassConfig.TOP_BAR_TAB_ORDER_MAX_ENTRIES),
    ).joinToString("\n")

/**
 * 推荐 is the fixed first tab. Applied to every list the reorder page writes, so a row that
 * somehow arrived elsewhere (an old store, a hand edit) still ends up behind it.
 */
private fun fixedFirstTopBarTab(order: List<String>): List<String> {
    if (order.isEmpty()) return listOf(TOP_BAR_FIXED_TAB)
    val rest = order.filter { it != TOP_BAR_FIXED_TAB }
    return listOf(TOP_BAR_FIXED_TAB) + rest
}

/** The 顶栏 row summary for 顶栏标签数量 / 排序. */
internal fun topBarTabsSummary(config: GlassConfig): String {
    val shown = config.topBarTabOrder
        .split('\n')
        .map { GlassConfig.normalizeTopBarTabLabel(it) }
        .filter { it.isNotEmpty() }
        .take(config.topBarTabCount)
    return if (shown.isEmpty()) {
        "${config.topBarTabCount} 个（默认 推荐 / 关注）"
    } else {
        "${config.topBarTabCount} 个 · " + shown.joinToString(" / ")
    }
}

internal data class MiMarketTabToggle(
    val key: String,
    val label: String,
    val summary: String,
)internal val miMarketTabToggles = listOf(
    MiMarketTabToggle(
        key = "pref_key_short_play",
        label = "短剧",
        summary = "同步应用商店「底栏页面管理」中的短剧开关",
    ),
    MiMarketTabToggle(
        key = "pref_key_mini_game",
        label = "小游戏",
        summary = "同步应用商店「底栏页面管理」中的小游戏开关",
    ),
)
