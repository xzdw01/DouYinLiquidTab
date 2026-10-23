package io.github.xzdw.dylt.config

import android.graphics.Color
import android.os.Bundle

/** Locally persisted appearance and behavior settings for the glass navigation bar. */
data class GlassConfig(
    val enabled: Boolean = true,
    val themeMode: Int = THEME_SYSTEM,
    val tabWidth: Float = 76f,
    val bottomPadding: Float = 12f,
    val blurRadius: Float = 2f,
    /** Corner radius as a percentage of the current bar/indicator height. */
    val cornerRadiusPercent: Float = 50f,
    val cornerSmoothing: Float = -1f,
    /**
     * 50dp is the reference bar the module mirrors (Douyin's floating bar). Keeping the
     * reference as the *default* — instead of forcing it inside an adapter — is what lets
     * the settings slider move the bar at all.
     */
    val barHeight: Float = 50f,
    /**
     * 底栏下边距: dp added to the bar's shipped bottom gap (a screen-height fraction, not a fixed
     * dp — which is why this is an offset and not an absolute margin). 0 is the shipped value;
     * negative lowers the bar, positive raises it, and the resulting margin never goes below 0.
     *
     * Global-only, exactly like the top-bar knobs: it lives on the settings page and is read by
     * every app from the global prefix.
     */
    val bottomMarginOffsetDp: Float = 0f,
    val lightAlpha: Float = 0.40f,
    val darkAlpha: Float = 0.40f,
    /** Multiplier for the outer bar bloom stroke in dark mode only. */
    val darkBarHighlightStrength: Float = 0.5f,
    /**
     * Multiplier on the themed glass fill alpha (1 = the appearance/app default, so the
     * shipped look is unchanged until the user moves the slider). Applied after the
     * per-app normalization so it also reaches an adapter's own glass alpha (Douyin).
     */
    val glassAlphaScale: Float = 1f,
    /** Saturation of the captured backdrop; 1 = untouched, 1.5 = the reference glass. */
    val glassSaturation: Float = 1.5f,
    /** Vertical extent of the bar's edge refraction, in dp. */
    val refractionHeightDp: Float = 15f,
    /** Magnitude of the edge displacement, in dp (set as a negative uniform). */
    val refractionStrengthDp: Float = 15f,
    /**
     * 描边（高光边线）向形状内侧扩散的光晕：强度 0~1，半径 dp。0 = 只有那条细线。
     * 用"同一路径叠几道逐层加宽变暗的描边"实现，所以对"胶囊 + 独立圆"这种两形状面板同样有效。
     */
    val bloomGlowStrength: Float = 0f,
    val bloomGlowDp: Float = 0f,

    val iconScale: Float = 1.0f,
    val textSize: Float = 13f,
    /**
     * 底栏显示样式: which of the two halves of an entry the bottom bar draws —
     * [BOTTOM_BAR_STYLE_ICON_TEXT] (the shipped icon-above-label layout),
     * [BOTTOM_BAR_STYLE_ICON_ONLY] or [BOTTOM_BAR_STYLE_TEXT_ONLY]. The single element a
     * single-element style draws is centred in the pill.
     *
     * This is the switch the settings page writes; [iconOnly] is derived from it in [normalized],
     * and [textOnly] reads it directly, so a renderer only ever has to look at the flag it already
     * knows.
     */
    val bottomBarStyle: Int = BOTTOM_BAR_STYLE_ICON_TEXT,
    val iconOnly: Boolean = false,
    val hiddenMask: Int = 0,
    /**
     * 底栏菜单文字: user-renamed bottom-bar entries, one name per line in the bar's own order
     * (首页 / 朋友 / 消息 / 我). A blank line keeps the app's own name for that entry, and an
     * empty string keeps every name — that is what the settings page's placeholder shows.
     */
    val customEntryLabels: String = "",
    /**
     * 隐藏底栏消息图标上的未读数量红点。默认关闭：红点按应用自身的未读状态显示。
     * 开启后底栏消息图标只画标签，不再叠加原生未读角标。
     */
    val hideMessageBadge: Boolean = false,
    val showPostButton: Boolean = true,
    val customAccentEnabled: Boolean = false,
    val customAccentColor: Int = DEFAULT_ACCENT,
    val showChannel: Boolean = false,
    val backdropCapture: Boolean = true,
    val nativeBlur: Boolean = false,
    val selectedAccent: Boolean = false,
    /** Global optical-effect gate. Navigation, blur and touch routing stay active. */
    val liquidGlassEnabled: Boolean = true,
    /**
     * Anti burn-in: periodically shift the bar's text/icon artwork by a couple of pixels while
     * leaving the glass panel, capsule and touch geometry in place. On by default; the shift is
     * slow enough to be imperceptible and only redraws once per interval.
     */
    val antiBurnIn: Boolean = true,
    /** Global gate for page-bottom progressive blur veils. */
    val bottomGradientBlurEnabled: Boolean = false,
    val outlineEnabled: Boolean = true,
    /**
     * "整页下移": cap the native control lift by the measured widget edge instead of the
     * control container's own edge.
     *
     * Douyin's caption/author/action column lives in a full-height overlay whose bottom sits
     * on the window edge while its widgets end ~178px higher. Lifting by the container edge
     * therefore parks the whole cluster far above the bar, leaving a visibly empty band.
     * Off reproduces that pre-fix behaviour; on keeps the lift at the widget edge.
     */
    val controlDrop: Boolean = false,
    /**
     * The top title bar as a second glass copy. Off leaves the native row alone: no glass host
     * is installed and nothing suppresses the app's own title bar. Off is the shipped default.
     */
    val topBarLiquidGlass: Boolean = false,
    /**
     * 顶栏参数: dp added to the top copy's distance from the screen's top edge. 0 is the shipped
     * value — the status bar's own height plus a third of the shared screen-relative gap.
     */
    val topBarMarginOffsetDp: Float = 0f,
    /**
     * 顶栏参数: multiplier for the whole top cluster — its height, slot width, action bands and
     * label size together, so the copy scales instead of deforming. 1 is the shipped size.
     *
     * The copy's height itself stays hard-coded: Douyin's own title bar times the adapter's height
     * fraction, clamped to [TOP_BAR_MIN_HEIGHT_DP]–[TOP_BAR_MAX_HEIGHT_DP]. It has no setting of its
     * own on purpose — the row it replaces is the only thing that knows how tall it should be.
     */
    val topBarScale: Float = 1f,
    /**
     * 顶栏两侧按钮: hide the leading drawer key and/or the trailing search key. A hidden key
     * keeps no action reserve, so the top copy's content-hugging width shrinks by exactly that
     * circle and the remaining cluster stays centred.
     */
    val topBarHideLeadingAction: Boolean = false,
    val topBarHideTrailingAction: Boolean = false,
    /**
     * 顶栏标签数量: how many tabs the top copy's capsule shows. The shipped pair is 2, which is
     * what makes the default reproduce the pre-setting top bar exactly.
     */
    val topBarTabCount: Int = 2,
    /**
     * 顶栏标签排序: the user's tab order for the top copy, one label per line. Only the first
     * [topBarTabCount] entries are drawn; an empty value keeps the shipped 关注 / 推荐 pair (and,
     * for counts above two, the native visible window).
     */
    val topBarTabOrder: String = "",
    /** Live device dark state, stamped by the module-side provider. */
    val systemDark: Boolean = false,
    /**
     * User-picked image drawn as the bottom bar's publish button, base64 PNG; empty keeps the
     * built-in "+" glyph. Downscaled in the settings UI so the stored string stays small.
     */
    val publishIcon: String = "",
) {
    /** 仅文字: the bar draws labels alone, vertically centred where the glyph used to sit. */
    val textOnly: Boolean get() = bottomBarStyle == BOTTOM_BAR_STYLE_TEXT_ONLY

    fun normalized(): GlassConfig = copy(
        themeMode = themeMode.coerceIn(THEME_SYSTEM, THEME_DARK),
        tabWidth = tabWidth.coerceIn(64f, 92f),
        bottomPadding = bottomPadding.coerceIn(4f, 28f),
        blurRadius = blurRadius.coerceIn(0f, 16f),
        cornerRadiusPercent = 50f,
        cornerSmoothing = -1f,
        // 50dp is Douyin's reference bar height (the floating glass bar it mirrors);
        // the adjustable range is 45–55dp.
        barHeight = barHeight.coerceIn(45f, 55f),
        // 底栏下边距 floats around the shipped gap: −15 dp (down to the gesture strip) to +35 dp
        // (well clear of it). Asymmetric on purpose — see BOTTOM_MARGIN_OFFSET_MIN_DP.
        bottomMarginOffsetDp = bottomMarginOffsetDp.coerceIn(
            BOTTOM_MARGIN_OFFSET_MIN_DP,
            BOTTOM_MARGIN_OFFSET_MAX_DP,
        ),
        lightAlpha = 0.40f,
        // Dark mirrors light so the 玻璃底色透明度 slider spans the same alpha range
        // (0.10 .. 0.80) in both themes.
        darkAlpha = 0.40f,
        darkBarHighlightStrength = darkBarHighlightStrength.coerceIn(0.5f, 1.7f),
        glassAlphaScale = glassAlphaScale.coerceIn(0.25f, 2f),
        glassSaturation = glassSaturation.coerceIn(0.5f, 2.5f),
        // 0 = no refraction at all (the lens shaders short-circuit), so pulling a slider to
        // the bottom really turns it off instead of leaving a 14 px warp.
        //
        // The two ceilings differ for a physical reason. `height` is the band measured
        // inward from each edge, so past roughly half the bar's height the two edges meet
        // and their displacement gradients start opposing each other; the bar is ~50 dp, so
        // 32 dp keeps a little headroom. `strength` is how far the sample is displaced and
        // has no such limit — a larger value only makes the bend more pronounced.
        refractionHeightDp = refractionHeightDp.coerceIn(0f, 32f),
        refractionStrengthDp = refractionStrengthDp.coerceIn(0f, 48f),
        bloomGlowStrength = bloomGlowStrength.coerceIn(0f, 1f),
        bloomGlowDp = bloomGlowDp.coerceIn(0f, 12f),

        // Per-app adapters such as QQ need a wider range because their source
        // navigation row is projected into a much narrower floating panel.
        iconScale = iconScale.coerceIn(0.70f, 1.40f),
        textSize = textSize.coerceIn(9f, 14f),
        // 底栏显示样式 is the source of truth for both shape flags: iconOnly stays a stored field
        // (every renderer and the native-text pass already read it) but is always re-derived here,
        // so a stale per-app value can never contradict the style the user picked.
        bottomBarStyle = bottomBarStyle.coerceIn(BOTTOM_BAR_STYLE_ICON_TEXT, BOTTOM_BAR_STYLE_TEXT_ONLY),
        iconOnly = bottomBarStyle.coerceIn(BOTTOM_BAR_STYLE_ICON_TEXT, BOTTOM_BAR_STYLE_TEXT_ONLY) ==
            BOTTOM_BAR_STYLE_ICON_ONLY,
        customAccentColor = customAccentColor or Color.BLACK,
        bottomGradientBlurEnabled = false,
        // 顶栏参数: a range floating around the shipped value of each knob — the margin around its
        // own default, the scale around the shipped 1.0.
        topBarMarginOffsetDp = topBarMarginOffsetDp.coerceIn(
            -TOP_BAR_MARGIN_OFFSET_MAX_DP,
            TOP_BAR_MARGIN_OFFSET_MAX_DP,
        ),
        topBarScale = topBarScale.coerceIn(TOP_BAR_MIN_SCALE, TOP_BAR_MAX_SCALE),
        topBarTabCount = topBarTabCount.coerceIn(TOP_BAR_MIN_TABS, TOP_BAR_MAX_TABS),
        topBarTabOrder = normalizeTopBarTabOrder(topBarTabOrder),
        // Safety net for a hand-edited store: the settings page caps typing at
        // ENTRY_LABEL_MAX_LENGTH and offers one field per entry, so this only ever trims a value
        // that did not come from the UI.
        customEntryLabels = customEntryLabels
            .split('\n')
            .take(ENTRY_LABEL_COUNT)
            .joinToString("\n") { it.trim().take(ENTRY_LABEL_MAX_LENGTH) },
    )

    fun toBundle(packageName: String): Bundle = Bundle().apply {
        val value = normalized()
        putString(ConfigContract.KEY_PACKAGE, packageName)
        putBoolean(ConfigContract.KEY_ENABLED, value.enabled)
        putInt(ConfigContract.KEY_THEME_MODE, value.themeMode)
        putFloat(ConfigContract.KEY_TAB_WIDTH, value.tabWidth)
        putFloat(ConfigContract.KEY_BOTTOM_PADDING, value.bottomPadding)
        putFloat(ConfigContract.KEY_BLUR_RADIUS, value.blurRadius)
        putFloat(ConfigContract.KEY_CORNER_RADIUS_PERCENT, value.cornerRadiusPercent)
        putFloat(ConfigContract.KEY_CORNER_SMOOTHING, value.cornerSmoothing)
        putFloat(ConfigContract.KEY_BAR_HEIGHT, value.barHeight)
        putFloat(ConfigContract.KEY_BOTTOM_MARGIN_OFFSET, value.bottomMarginOffsetDp)
        putFloat(ConfigContract.KEY_LIGHT_ALPHA, value.lightAlpha)
        putFloat(ConfigContract.KEY_DARK_ALPHA, value.darkAlpha)
        putFloat(ConfigContract.KEY_DARK_BAR_HIGHLIGHT_STRENGTH, value.darkBarHighlightStrength)
        putFloat(ConfigContract.KEY_GLASS_ALPHA_SCALE, value.glassAlphaScale)
        putFloat(ConfigContract.KEY_GLASS_SATURATION, value.glassSaturation)
        putFloat(ConfigContract.KEY_REFRACTION_HEIGHT, value.refractionHeightDp)
        putFloat(ConfigContract.KEY_REFRACTION_STRENGTH, value.refractionStrengthDp)
        putFloat(ConfigContract.KEY_BLOOM_GLOW_STRENGTH, value.bloomGlowStrength)
        putFloat(ConfigContract.KEY_BLOOM_GLOW_DP, value.bloomGlowDp)

        putFloat(ConfigContract.KEY_ICON_SCALE, value.iconScale)
        putFloat(ConfigContract.KEY_TEXT_SIZE, value.textSize)
        putBoolean(ConfigContract.KEY_ICON_ONLY, value.iconOnly)
        putInt(ConfigContract.KEY_BOTTOM_BAR_STYLE, value.bottomBarStyle)
        putInt(ConfigContract.KEY_HIDDEN_MASK, value.hiddenMask)
        putString(ConfigContract.KEY_CUSTOM_ENTRY_LABELS, value.customEntryLabels)
        putBoolean(ConfigContract.KEY_HIDE_MESSAGE_BADGE, value.hideMessageBadge)
        putBoolean(ConfigContract.KEY_SHOW_POST, value.showPostButton)
        putBoolean(ConfigContract.KEY_CUSTOM_ACCENT_ENABLED, value.customAccentEnabled)
        putInt(ConfigContract.KEY_CUSTOM_ACCENT_COLOR, value.customAccentColor)
        putBoolean(ConfigContract.KEY_SHOW_CHANNEL, value.showChannel)
        putBoolean(ConfigContract.KEY_BACKDROP_CAPTURE, value.backdropCapture)
        putBoolean(ConfigContract.KEY_NATIVE_BLUR, value.nativeBlur)
        putBoolean(ConfigContract.KEY_SELECTED_ACCENT, value.selectedAccent)
        putBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, value.liquidGlassEnabled)
        putBoolean(ConfigContract.KEY_ANTI_BURN_IN, value.antiBurnIn)
        putBoolean(ConfigContract.KEY_BOTTOM_GRADIENT_BLUR_ENABLED, value.bottomGradientBlurEnabled)
        putBoolean(ConfigContract.KEY_OUTLINE_ENABLED, value.outlineEnabled)
        putBoolean(ConfigContract.KEY_CONTROL_DROP, value.controlDrop)
        putBoolean(ConfigContract.KEY_TOP_BAR_LIQUID, value.topBarLiquidGlass)
        putFloat(ConfigContract.KEY_TOP_BAR_MARGIN_OFFSET, value.topBarMarginOffsetDp)
        putFloat(ConfigContract.KEY_TOP_BAR_SCALE, value.topBarScale)
        putBoolean(ConfigContract.KEY_TOP_BAR_HIDE_LEADING, value.topBarHideLeadingAction)
        putBoolean(ConfigContract.KEY_TOP_BAR_HIDE_TRAILING, value.topBarHideTrailingAction)
        putInt(ConfigContract.KEY_TOP_BAR_TAB_COUNT, value.topBarTabCount)
        putString(ConfigContract.KEY_TOP_BAR_TAB_ORDER, value.topBarTabOrder)
        putString(ConfigContract.KEY_PUBLISH_ICON, value.publishIcon)
    }

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val DEFAULT_ACCENT: Int = -30208

        /** Entries the bar can be renamed through: 首页 / 朋友 / 消息 / 我. */
        const val ENTRY_LABEL_COUNT = 4

        /**
         * Longest name one entry accepts. Four two-character names already fill their slots at the
         * reference bar's 13sp, so this is the point where a longer name stops fitting; the
         * renderer shrinks an over-long name to its slot as a second net.
         */
        const val ENTRY_LABEL_MAX_LENGTH = 6

        /**
         * 顶栏参数: the band the top copy's height clamp has always used. The clamp is not a
         * setting — see [topBarScale]'s note — but the numbers are named because they are the only
         * place the copy's height is bounded.
         */
        const val TOP_BAR_MIN_HEIGHT_DP = 32f
        const val TOP_BAR_MAX_HEIGHT_DP = 96f

        /**
         * 顶栏参数: the margin offset floats this far either side of the shipped value, in dp.
         *
         * ±32 dp is roughly the status bar plus a third of the shared gap on each side of the
         * default, which is what the owner asked for after testing ±16: enough to lift the copy
         * onto the status bar or drop it well below Douyin's title row.
         */
        const val TOP_BAR_MARGIN_OFFSET_MAX_DP = 32f

        /** 顶栏参数: the cluster scale floats this far either side of the shipped 1.0. */
        const val TOP_BAR_MIN_SCALE = 0.8f
        const val TOP_BAR_MAX_SCALE = 1.25f

        /** 顶栏标签数量: the capsule shows at least one tab and at most four. */
        const val TOP_BAR_MIN_TABS = 1
        const val TOP_BAR_MAX_TABS = 4

        /**
         * 顶栏标签排序: Douyin's location tab carries the current place name (亳州, 花戏楼,
         * …), which changes as the user moves. The module shows and stores it as this fixed
         * label so the candidate list has the same nine tabs everywhere and the stored order
         * does not accumulate one alias per location.
         */
        const val TOP_BAR_LOCATION_TAB = "同城"

        /**
         * The top title bar's non-location tabs. Any other label is treated as the location
         * tab: it is displayed/stored as [TOP_BAR_LOCATION_TAB], never as its raw place name.
         */
        val TOP_BAR_STABLE_TABS = setOf(
            "推荐", "关注", "商城", "精选", "团购", "直播", "经验", "热点",
        )

        /** 顶栏标签排序: a safety cap for a hand-edited store; the title bar carries ~9 tabs. */
        const val TOP_BAR_TAB_ORDER_MAX_ENTRIES = 12
        const val TOP_BAR_TAB_ORDER_MAX_LENGTH = 8

        /**
         * 顶栏标签排序: trims a stored order to the form the top copy and the reorder page
         * both expect — one non-blank label per line, no duplicates, bounded in count and
         * length. An empty string means "keep the shipped pair". A location alias is
         * canonicalised to [TOP_BAR_LOCATION_TAB], so 亳州 / 花戏楼 never becomes a second
         * candidate or a stale row.
         */
        fun normalizeTopBarTabOrder(raw: String): String =
            raw.split('\n')
                .map { normalizeTopBarTabLabel(it) }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(TOP_BAR_TAB_ORDER_MAX_ENTRIES)
                .joinToString("\n")

        /** Maps a raw title-bar label to the fixed store label; blanks stay blank. */
        fun normalizeTopBarTabLabel(raw: String): String {
            val label = raw.trim().take(TOP_BAR_TAB_ORDER_MAX_LENGTH)
            if (label.isEmpty()) return ""
            return if (label == TOP_BAR_LOCATION_TAB || label in TOP_BAR_STABLE_TABS) {
                label
            } else {
                TOP_BAR_LOCATION_TAB
            }
        }

        /**
         * 底栏下边距: the offset range around the shipped gap, in dp.
         *
         * Deliberately asymmetric: the bar may drop at most until it sits on the gesture strip
         * (−15 dp), but may be raised well clear of it (+45 dp). The host margin is still floored
         * at 0 in `glassHostBottomMarginPx` as a safety net for a hand-edited store, and the
         * Douyin lift applies the same floor (`liftOffsetPx`).
         */
        const val BOTTOM_MARGIN_OFFSET_MIN_DP = -15f
        const val BOTTOM_MARGIN_OFFSET_MAX_DP = 45f

        /**
         * 底栏显示样式: 图标 + 文字（默认，当前布局）、仅图标、仅文字. The constants are the store's
         * values, so they keep their numbers.
         */
        const val BOTTOM_BAR_STYLE_ICON_TEXT = 0
        const val BOTTOM_BAR_STYLE_ICON_ONLY = 1
        const val BOTTOM_BAR_STYLE_TEXT_ONLY = 2

        fun fromBundle(bundle: Bundle?): GlassConfig {
            if (bundle == null) return GlassConfig()
            return GlassConfig(
                enabled = bundle.getBoolean(ConfigContract.KEY_ENABLED, true),
                themeMode = bundle.getInt(ConfigContract.KEY_THEME_MODE, THEME_SYSTEM),
                tabWidth = bundle.getFloat(ConfigContract.KEY_TAB_WIDTH, 76f),
                bottomPadding = bundle.getFloat(ConfigContract.KEY_BOTTOM_PADDING, 12f),
                blurRadius = bundle.getFloat(ConfigContract.KEY_BLUR_RADIUS, 2f),
                cornerRadiusPercent = bundle.getFloat(ConfigContract.KEY_CORNER_RADIUS_PERCENT, 50f),
                cornerSmoothing = bundle.getFloat(ConfigContract.KEY_CORNER_SMOOTHING, -1f),
                barHeight = bundle.getFloat(ConfigContract.KEY_BAR_HEIGHT, 50f),
                bottomMarginOffsetDp = bundle.getFloat(ConfigContract.KEY_BOTTOM_MARGIN_OFFSET, 0f),
                lightAlpha = bundle.getFloat(ConfigContract.KEY_LIGHT_ALPHA, 0.40f),
                darkAlpha = bundle.getFloat(ConfigContract.KEY_DARK_ALPHA, 0.40f),
                darkBarHighlightStrength = bundle.getFloat(ConfigContract.KEY_DARK_BAR_HIGHLIGHT_STRENGTH, 0.5f),
                glassAlphaScale = bundle.getFloat(ConfigContract.KEY_GLASS_ALPHA_SCALE, 1f),
                glassSaturation = bundle.getFloat(ConfigContract.KEY_GLASS_SATURATION, 1.5f),
                refractionHeightDp = bundle.getFloat(ConfigContract.KEY_REFRACTION_HEIGHT, 15f),
                refractionStrengthDp = bundle.getFloat(ConfigContract.KEY_REFRACTION_STRENGTH, 15f),
                bloomGlowStrength = bundle.getFloat(ConfigContract.KEY_BLOOM_GLOW_STRENGTH, 0f),
                bloomGlowDp = bundle.getFloat(ConfigContract.KEY_BLOOM_GLOW_DP, 0f),

                iconScale = bundle.getFloat(ConfigContract.KEY_ICON_SCALE, 1f),
                textSize = bundle.getFloat(ConfigContract.KEY_TEXT_SIZE, 13f),
                iconOnly = bundle.getBoolean(ConfigContract.KEY_ICON_ONLY, false),
                bottomBarStyle = bundle.getInt(ConfigContract.KEY_BOTTOM_BAR_STYLE, BOTTOM_BAR_STYLE_ICON_TEXT),
                hiddenMask = bundle.getInt(ConfigContract.KEY_HIDDEN_MASK, 0),
                customEntryLabels = bundle.getString(ConfigContract.KEY_CUSTOM_ENTRY_LABELS) ?: "",
                hideMessageBadge = bundle.getBoolean(ConfigContract.KEY_HIDE_MESSAGE_BADGE, false),
                showPostButton = bundle.getBoolean(ConfigContract.KEY_SHOW_POST, true),
                customAccentEnabled = bundle.getBoolean(ConfigContract.KEY_CUSTOM_ACCENT_ENABLED, false),
                customAccentColor = bundle.getInt(ConfigContract.KEY_CUSTOM_ACCENT_COLOR, DEFAULT_ACCENT),
                showChannel = bundle.getBoolean(ConfigContract.KEY_SHOW_CHANNEL, false),
                backdropCapture = bundle.getBoolean(ConfigContract.KEY_BACKDROP_CAPTURE, true),
                nativeBlur = bundle.getBoolean(ConfigContract.KEY_NATIVE_BLUR, false),
                selectedAccent = bundle.getBoolean(ConfigContract.KEY_SELECTED_ACCENT, false),
                liquidGlassEnabled = bundle.getBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, true),
                antiBurnIn = bundle.getBoolean(ConfigContract.KEY_ANTI_BURN_IN, true),
                bottomGradientBlurEnabled = false,
                outlineEnabled = bundle.getBoolean(ConfigContract.KEY_OUTLINE_ENABLED, true),
                controlDrop = bundle.getBoolean(ConfigContract.KEY_CONTROL_DROP, false),
                topBarLiquidGlass = bundle.getBoolean(ConfigContract.KEY_TOP_BAR_LIQUID, false),
                topBarMarginOffsetDp = bundle.getFloat(ConfigContract.KEY_TOP_BAR_MARGIN_OFFSET, 0f),
                topBarScale = bundle.getFloat(ConfigContract.KEY_TOP_BAR_SCALE, 1f),
                topBarHideLeadingAction = bundle.getBoolean(ConfigContract.KEY_TOP_BAR_HIDE_LEADING, false),
                topBarHideTrailingAction = bundle.getBoolean(ConfigContract.KEY_TOP_BAR_HIDE_TRAILING, false),
                topBarTabCount = bundle.getInt(ConfigContract.KEY_TOP_BAR_TAB_COUNT, 2),
                topBarTabOrder = bundle.getString(ConfigContract.KEY_TOP_BAR_TAB_ORDER) ?: "",
                systemDark = bundle.getBoolean(ConfigContract.KEY_SYSTEM_DARK, false),
                publishIcon = bundle.getString(ConfigContract.KEY_PUBLISH_ICON) ?: "",
            ).normalized()
        }
    }
}
