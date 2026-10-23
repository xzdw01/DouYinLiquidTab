package io.github.xzdw.dylt.hook.adapters.douyin

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import io.github.xzdw.dylt.hook.HookLoadContext
import io.github.xzdw.dylt.hook.NavigationCandidate
import io.github.xzdw.dylt.hook.adapters.AdapterNavigationSearch
import io.github.xzdw.dylt.hook.adapters.AppNavigationState
import io.github.xzdw.dylt.hook.adapters.NavigationBadge
import io.github.xzdw.dylt.hook.adapters.TargetAdapter
import io.github.xzdw.dylt.config.GlassConfig
import io.github.xzdw.dylt.targets.TargetSpec

/** Douyin 40.3.0: only root_view is an authoritative five-cell navigation row. */
internal object DouyinAdapter : TargetAdapter {
    override fun installHooks(context: HookLoadContext, spec: TargetSpec) =
        DouyinPlayerTextureHooks.install(context)
    override val key = "douyin"
    override val allowRenderNodeRefresh = true
    override val ownsNavigationFinding = true
    override val ownsNavigationDrawing = true
    override val foregroundNavigationOverSelection = true
    override val usesNavigationOpticalMix = true
    /**
     * Cap the display hint at 60 Hz. Everything the module pays per frame scales with the panel
     * rate: the glass draw (blur + refraction + bloom shaders), the backdrop scene re-record,
     * the page-maintenance pass and the capture-driven invalidations. A feed player's own
     * output is 30/60 fps, so 120 Hz bought no extra detail inside the glass and paid for it
     * twice. Raise it back only if the glass visibly steps against the video behind it.
     */
    override val requestedFrameRateMax = 60f
    override val continuousFramePump = true
    override val opticalIconBandScale = 1.5f
    override val secondaryBlurScale = 0.72f
    override val skipBackdropStableDelay = true
    override val forceCompositorFrames = true
    override val restingOpticalMix = 0.86f
    override val hasPerFrameScene = true
    /**
     * Selection capsule: in light mode the capsule's mask is meant to read as one 40% wash,
     * which the shared container colour already supplies on its own (`lightAlpha = 0.40`), so
     * this tint stays transparent there instead of stacking another 15% white on top of it.
     */
    override val indicatorTint: Pair<Int, Float> = 0xFFFFFFFF.toInt() to 0f
    /**
     * Dark-theme capsule: the container colour already supplies the whole mask, so no
     * extra black is stacked on top of it.
     */
    override val indicatorTintDark: Pair<Int, Float> = 0xFF000000.toInt() to 0f
    /**
     * The capsule keeps the bar's own container colour, so it still tracks the glass in
     * dark mode; this multiplier only makes the selected pill read stronger than the
     * surface beneath it instead of blending into it.
     */
    override val indicatorMaskRatioDark: Float = 1.3f
    /**
     * Slot 2 of Douyin's native five-cell row is the publish action. The bar draws it as
     * the detached circular button, so it must not consume a navigation slot; declaring
     * it here is what keeps the four labels and the selection capsule on the same
     * geometry (otherwise the slot width is computed over five slots and the capsule
     * sits too narrow and too far left of "首页").
     */
    override val actionOnlyIndices = setOf(2)
    /**
     * The publish action sits outside the pill: a circular button beside it, with the
     * 12dp group gap the reference bottom bar uses between its capsule and its button.
     */
    override val navigationActionReserveGapDp = 12f
    /**
     * The module renders a second glass host at the top edge of the same content frame:
     * a literal copy of the bottom bar's host. Its content is Douyin's own title-bar tab
     * set, read at runtime by [DouyinTopBarState].
     */
    override val supportsTopBar = true
    /** Douyin's own title bar (`MainTitleBar`), which owns the tab strip and both corner keys. */
    override fun findTopNavigation(root: View): ViewGroup? = DouyinTopBarFinder.find(root)
    /**
     * Both corner keys of Douyin's title bar are circles as tall as the bar, separated
     * from the capsule by the same 12dp the reference bottom bar uses beside its
     * publish button. The bottom bar itself only ever reserves its right-hand one.
     */
    override fun topBarActionReserveGapDp(): Pair<Float, Float> = 12f to 12f
    /**
     * 顶栏标签数量: the glass capsule shows the configured number of tabs (1–4, default 2)
     * instead of whatever window Douyin happens to have on screen. The finder still caps
     * this at the cells the strip actually carries, so a page with fewer tabs shrinks the
     * copy rather than leaving empty slots.
     */
    override fun topBarSlotCount(root: View, config: GlassConfig): Int =
        DouyinTopBarFinder.find(root)?.let { row ->
            DouyinTopBarFinder.model(row, DouyinTopBarPlan.from(config))?.tabs?.size
        }?.takeIf { it in 1..GlassConfig.TOP_BAR_MAX_TABS } ?: config.topBarTabCount.coerceIn(
            GlassConfig.TOP_BAR_MIN_TABS,
            GlassConfig.TOP_BAR_MAX_TABS,
        )
    /**
     * The top bar hugs its two entries instead of spanning the screen: one slot is wide
     * enough for a two-character label plus the padding the reference cluster uses
     * (42dp ≈ 157 px here, against the 172 px Douyin gives its own tabs). It is wider than
     * the bottom bar's share because the capsule carries three keys, and a stadium only
     * reads as a capsule while its flat middle stays visible.
     */
    override fun topBarSlotWidthDp(): Float = 42f
    /**
     * The copy is a little slimmer than Douyin's own 44dp title bar (0.9 -> ~40dp). At the
     * native height the compact cluster read as three heavy circles; much below 0.9 and the
     * capsule's flat middle disappears into the corner radius.
     */
    override fun topBarHeightFraction(): Float = 0.9f
    /**
     * The capsule carries three keys: the tab-menu key ahead of the two tabs, each getting
     * the same 38dp band so the three read as one row.
     */
    override fun topBarInlineActionWidthDp(): Float = 38f
    /**
     * The two corner circles sit a little inside the capsule: full height (1.0) read too heavy
     * once the pill was stretched to 4 tabs, while 0.88 read as undersized discs. 0.92 keeps
     * them visually attached to the capsule without matching its full height. The glyphs keep
     * their measured 0.88 size (see DouyinTopBarRenderer), so only the glass circles move.
     */
    override fun topBarActionButtonFraction(): Float = 0.92f
    /**
     * Bar sizing taken from the reference bottom bar: a 50dp tall capsule and 13sp
     * medium labels. The 4dp inner padding is already the host default, so the
     * selection capsule comes out 42dp tall, again matching the reference.
     *
     * Dark mode mirrors the light container alpha so the 玻璃底色透明度 slider spans the
     * same alpha range in both themes. The user's multiplier is applied after this
     * (see `GlassHostLayout.packageConfig`), so it scales 0.40 as well.
     *
     * Bar height and label size are *not* touched here: 50dp / 13sp are the shared
     * defaults now. They used to be forced from this adapter, which silently discarded
     * every 底栏高度 / 文字大小 change on Douyin — the settings sliders looked live but
     * the bar never moved.
     */
    override fun normalizeConfig(config: GlassConfig): GlassConfig =
        config.copy(darkAlpha = 0.40f)

    /**
     * Unread-messages badge. Douyin's own cell carries the count (or a dot), but the module hides
     * that row and paints its own labels, so the value is read here and redrawn by the renderer —
     * otherwise the messages entry silently loses its unread indicator.
     */
    override fun navigationBadge(source: View?): NavigationBadge? = DouyinUnreadBadge.read(source)
    /**
     * Glass tint: the warm cream measured from the earlier mock-up in light mode, and a
     * dark warm neutral in dark mode so the bar does not glow over the feed. The host
     * composites these at the configured alpha, so the numbers above decide the result.
     */
    override fun glassBarTint(dark: Boolean): Pair<Int, Float> =
        if (dark) 0xFF2E2820.toInt() to 1.0f else 0xFFC0B6A8.toInt() to 1.0f
    override fun createNavigationState(
        context: android.content.Context,
        host: io.github.xzdw.dylt.hook.GlassHostLayout,
    ): AppNavigationState =
        if (host.topBar) DouyinTopBarState(context, host) else DouyinNavigationState(context)
    override fun createNativeChromeController() = object : io.github.xzdw.dylt.hook.adapters.AppNativeChromeController {
        override fun update(host: View, source: ViewGroup?) {
            source ?: return
            suppressNativeBottomChrome(source)
        }
    }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val row = AdapterNavigationSearch.findFirst(root) { view ->
            AdapterNavigationSearch.resourceEntryName(view) == "root_view" &&
                view.isAttachedToWindow && view.visibility == View.VISIBLE &&
                view.width >= root.width * 0.9f && view.height > 0 &&
                view.childCount == 5 &&
                (0 until view.childCount).all { view.getChildAt(it).isClickable }
        }
        return row?.let { NavigationCandidate(it, 5, 1_000) }
    }

    /** Frame stamp of the last chrome suppression; the same row is asked for 3x per pre-draw. */
    private var chromeSuppressedAt = -1L
    private var chromeSuppressedSource: ViewGroup? = null

    /** Retain the native click controller but remove its full-width chrome. */
    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        // The pre-draw listener asks for this three times per frame (adapter twice plus the
        // native-chrome controller), and every call walks the row's shell and chrome parents.
        // The writes are idempotent, so one pass per row per millisecond is enough.
        val now = SystemClock.uptimeMillis()
        if (chromeSuppressedAt == now && chromeSuppressedSource === navigation) return
        chromeSuppressedAt = now
        chromeSuppressedSource = navigation
        if (DouyinTopBarFinder.isTopRow(navigation)) {
            // The glass bar draws the whole title row now, including its own drawer key, so
            // the native row goes away — and with it any invisible tap target: an alpha-0
            // strip that stays clickable reaches past the glass and switches tabs the user
            // cannot see. The copy still forwards its ☰ with performClick, which needs no
            // clickable flag.
            DouyinTopBarFinder.hideNativeTitleBar(navigation)
            return
        }
        navigation.background = null
        val rowShell = navigation.parent as? ViewGroup ?: return
        rowShell.background = null
        for (index in 0 until rowShell.childCount) {
            val child = rowShell.getChildAt(index)
            if (child !== navigation && child.width >= navigation.width * 0.8f) child.alpha = 0f
        }
        val chrome = rowShell.parent as? ViewGroup ?: return
        chrome.background = null
        for (index in 0 until chrome.childCount) {
            val child = chrome.getChildAt(index)
            if (child !== rowShell && child.width >= navigation.width * 0.8f &&
                child.height >= navigation.height * 0.8f
            ) child.alpha = 0f
        }
        (chrome.parent as? ViewGroup)?.background = null

    }
}
