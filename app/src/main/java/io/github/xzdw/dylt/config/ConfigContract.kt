package io.github.xzdw.dylt.config

import android.net.Uri

object ConfigContract {
    const val AUTHORITY = "io.github.xzdw.dylt.settings"
    val URI: Uri = Uri.parse("content://$AUTHORITY/config")

    const val METHOD_GET = "get"
    const val METHOD_REPORT_NAVIGATION = "report_navigation"
    const val KEY_NAVIGATION_LABELS = "navigation_labels"
    const val METHOD_MI_MARKET_TAB_GET = "mi_market_tab_get"
    const val METHOD_MI_MARKET_TAB_SET = "mi_market_tab_set"

    const val ACTION_CONFIG_REQUEST = "io.github.xzdw.dylt.action.CONFIG_REQUEST"
    const val ACTION_CONFIG_RESPONSE = "io.github.xzdw.dylt.action.CONFIG_RESPONSE"
    const val EXTRA_REQUEST_PACKAGE = "request_package"
    const val CONFIG_SYNC_PERMISSION = "io.github.xzdw.dylt.permission.CONFIG_SYNC"

    const val KEY_PACKAGE = "package"
    const val KEY_ENABLED = "enabled"
    const val KEY_NATIVE_BLUR = "native_blur"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_TAB_WIDTH = "tab_width"
    const val KEY_BOTTOM_PADDING = "bottom_padding"
    const val KEY_BLUR_RADIUS = "blur_radius"
    const val KEY_CORNER_RADIUS = "corner_radius"
    const val KEY_CORNER_RADIUS_PERCENT = "corner_radius_percent"
    const val KEY_CORNER_SMOOTHING = "corner_smoothing"
    const val KEY_BAR_HEIGHT = "bar_height"

    /**
     * 底栏下边距: dp offset added to the bar's shipped screen-relative bottom gap. 0 is the
     * shipped value, so an untouched slider changes nothing. Global-only, like the top-bar knobs.
     */
    const val KEY_BOTTOM_MARGIN_OFFSET = "bottom_margin_offset"
    const val KEY_LIGHT_ALPHA = "light_alpha"
    const val KEY_DARK_ALPHA = "dark_alpha"
    const val KEY_DARK_BAR_HIGHLIGHT_STRENGTH = "dark_bar_highlight_strength"
    const val KEY_GLASS_ALPHA_SCALE = "glass_alpha_scale"
    const val KEY_GLASS_SATURATION = "glass_saturation"
    const val KEY_REFRACTION_HEIGHT = "refraction_height"
    const val KEY_REFRACTION_STRENGTH = "refraction_strength"

    /** 描边光晕：强度 0~1，扩散半径 dp。 */
    const val KEY_BLOOM_GLOW_STRENGTH = "bloom_glow_strength"
    const val KEY_BLOOM_GLOW_DP = "bloom_glow_dp"

    const val KEY_ICON_SCALE = "icon_scale"
    const val KEY_TEXT_SIZE = "text_size"
    /**
     * 底栏显示样式: 0 = 图标 + 文字（默认，当前布局）, 1 = 仅图标, 2 = 仅文字.
     *
     * [KEY_ICON_ONLY] is kept as the derived form of style 1 — renderers and the native-text pass
     * already read that flag — but this key is what the settings page writes.
     */
    const val KEY_BOTTOM_BAR_STYLE = "bottom_bar_style"
    const val KEY_ICON_ONLY = "icon_only"
    const val KEY_HIDDEN_MASK = "hidden_mask"

    /** 隐藏底栏消息图标上的未读数量红点；默认关闭（即显示红点）。 */
    const val KEY_HIDE_MESSAGE_BADGE = "hide_message_badge"

    /**
     * 底栏菜单文字: the user's names for the bottom bar's entries, one per line in the bar's own
     * order (首页 / 朋友 / 消息 / 我). A blank line keeps the app's own name for that entry, and an
     * empty string keeps every name. Drawn by `DouyinNavigationRenderer`.
     */
    const val KEY_CUSTOM_ENTRY_LABELS = "custom_entry_labels"
    const val KEY_SHOW_POST = "show_post"
    const val KEY_CUSTOM_ACCENT_ENABLED = "custom_accent_enabled"
    const val KEY_CUSTOM_ACCENT_COLOR = "custom_accent_color"
    const val KEY_SHOW_CHANNEL = "show_channel"
    const val KEY_BACKDROP_CAPTURE = "backdrop_capture"
    const val KEY_SELECTED_ACCENT = "selected_accent"
    const val KEY_LIQUID_GLASS_ENABLED = "liquid_glass_enabled"
    const val KEY_ANTI_BURN_IN = "anti_burn_in"
    const val KEY_BOTTOM_GRADIENT_BLUR_ENABLED = "bottom_gradient_blur_enabled"
    const val KEY_OUTLINE_ENABLED = "outline_enabled"
    const val KEY_CONTROL_DROP = "control_drop"

    /** Douyin's top title bar as a second glass copy; off restores the native row. */
    const val KEY_TOP_BAR_LIQUID = "top_bar_liquid_glass"

    /**
     * 顶栏参数: the top copy's own adjustments. Each one's default is exactly the value the copy
     * shipped with (its hard-coded margin and an unscaled cluster), so an untouched slider changes
     * nothing. Its height has no key on purpose: it follows Douyin's own title row.
     */
    const val KEY_TOP_BAR_MARGIN_OFFSET = "top_bar_margin_offset"
    const val KEY_TOP_BAR_SCALE = "top_bar_scale"

    /**
     * 顶栏两侧按钮: turn either corner key off. Hidden keys are not drawn, keep no reserve and
     * stop answering taps; the remaining cluster is centred because the top copy hugs its own
     * content width.
     */
    const val KEY_TOP_BAR_HIDE_LEADING = "top_bar_hide_leading"
    const val KEY_TOP_BAR_HIDE_TRAILING = "top_bar_hide_trailing"

    /** 顶栏标签数量: how many tabs the top copy's capsule shows (1–4; default 2). */
    const val KEY_TOP_BAR_TAB_COUNT = "top_bar_tab_count"

    /**
     * 顶栏标签排序: the user's tab order, one label per line. The first
     * [KEY_TOP_BAR_TAB_COUNT] entries are shown; an empty value keeps the shipped pair.
     */
    const val KEY_TOP_BAR_TAB_ORDER = "top_bar_tab_order"

    /**
     * Hooked process → manager: the top title bar's tab labels, so the reorder page can list
     * the tabs Douyin actually carries instead of a hardcoded set.
     */
    const val METHOD_REPORT_TOP_TABS = "report_top_tabs"
    const val KEY_TOP_TAB_LABELS = "top_tab_labels"

    /**
     * User-picked image that replaces the bottom bar's publish ("+") icon, as base64 PNG.
     * Empty keeps the built-in glyph.
     */
    const val KEY_PUBLISH_ICON = "publish_icon"
    const val KEY_SYSTEM_DARK = "system_dark"
    const val KEY_MI_MARKET_TAB_ENABLED = "mi_market_tab_enabled"
}
