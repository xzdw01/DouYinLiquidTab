package io.github.xzdw.dylt.hook.adapters.douyin

import android.content.Context
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.xzdw.dylt.config.ConfigContract
import io.github.xzdw.dylt.hookx.Xposed
import io.github.xzdw.dylt.hook.GlassHostLayout
import io.github.xzdw.dylt.hook.adapters.AdapterNavigationFrame
import io.github.xzdw.dylt.hook.adapters.AppNavigationState
import io.github.xzdw.dylt.hook.adapters.AppVideoBackdrop
import java.util.concurrent.Executors

/** One worker for the low-priority top-tab label report; never blocks the UI thread. */
private val topTabReportExecutor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "LiquidTab-TopTabs").apply { isDaemon = true }
}

/**
 * State of the second, top-anchored glass host.
 *
 * The host copy brings its own panel, blur, refraction, capture loop and gesture
 * handling. This state supplies what it cannot know by itself:
 *
 * 1. **Content.** The tab set, their order and their text are read from Douyin's own
 *    title bar every pre-draw ([DouyinTopBarFinder]); the glass shows a window of the
 *    visible tabs that always contains Douyin's current one.
 * 2. **Selection.** Douyin's cells expose no selected flag, so the current tab is the
 *    one whose label Douyin leaves brightest ([DouyinTopBarFinder.model]).
 * 3. **Tap forwarding.** Taps are handed back to Douyin's own cells and corner keys,
 *    because the glass is the only hit target; a short commit window keeps the capsule
 *    on the tapped tab while Douyin runs its page transition.
 * 4. **Video pixels.** Douyin's feed frames live behind a TextureView/SurfaceView, so
 *    they are sampled by a second [DouyinVideoSampler]. That sampler captures around
 *    *its own* host rect (`request(host, …)` records `currentHost`), which is why the
 *    copy is fed the strip behind the top bar rather than the one behind the bottom bar.
 *
 * Deliberately independent of [DouyinPageController]: that controller owns Douyin's
 * bottom-margin lifts for the bottom bar, and a second instance would fight it over
 * the same containers. This state performs no page work.
 */
internal class DouyinTopBarState(
    private val context: Context,
    private val host: GlassHostLayout,
) : AppNavigationState {
    private companion object {
        /** How often a missing title bar is re-searched for while it stays missing. */
        const val FIND_RETRY_MS = 1_000L

        /**
         * How long a tapped tab stays the committed selection. Douyin's own title bar
         * needs a few hundred milliseconds to move its label emphasis to the new tab,
         * and until it does the native reading still describes the old one.
         */
        const val COMMIT_MS = 600L

        /** How far up a cell's parent chain a click listener may live. */
        const val CLICK_ANCESTOR_SCAN = 4

        /**
         * Frames a new tab count has to hold before the host is re-parameterized with it.
         * A page transition can report a single visible cell for a frame or two, and
         * resizing the bar to one slot collapses it to a small pill in the middle of the
         * screen before the next frame widens it again.
         */
        const val SLOT_COUNT_STABLE_FRAMES = 3

        /** Share of the title bar that has to stay on screen for the copy to be shown. */
        const val MIN_VISIBLE_FRACTION = 0.25f

        /** 顶栏标签排序: how often the title bar's own labels are reported to the manager. */
        const val REPORT_INTERVAL_MS = 2_000L

        /**
         * Safety age for the retained combined scene.
         *
         * The scene embeds the hidden native title row; a drawable swap that moves no geometry
         * can leave it stale. Re-recording at most once per second while the row is otherwise
         * static costs nothing next to the old every-frame record.
         */
        const val SCENE_MAX_AGE_MS = 1_000L
    }

    private val renderer = DouyinTopBarRenderer(context)
    private val videoSampler = DouyinVideoSampler(context)

    private var row: ViewGroup? = null
    private var model: DouyinTopBarModel? = null
    private var tabs: List<DouyinTopTab> = emptyList()
    private var labels: List<String> = emptyList()
    private var selectedIndex = -1
    private var leftAction = false
    private var rightAction = false

    /** The tab-menu key inside the capsule is part of this bar's own design. */
    private var inlineAction = true
    private var committedCell: View? = null
    private var committedUntil = 0L
    /**
     * A tab picked from the capsule's menu while it was not one of the displayed tabs.
     *
     * Douyin's strip can switch to that page without scrolling the hidden cell into view, so
     * neither its selected flag nor its label brightness describes the current tab; the model
     * would keep highlighting whichever displayed tab was current before. The picked cell is
     * therefore swapped into the bar until the user taps a displayed tab (or the row dies).
     */
    private var selectedOverride: DouyinTopTab? = null
    private var menu: DouyinTopTabMenu? = null
    private var appliedSlotCount = -1
    private var pendingSlotCount = -1
    private var pendingSlotFrames = 0
    private val rowVisibleRect = android.graphics.Rect()
    private var lastFind = 0L
    private var lastReportedTabs = ""
    private var lastReportAt = 0L

    /**
     * 顶栏标签数量 / 排序 / 两侧按钮, parsed once per settings change.
     *
     * [refresh] runs on every display frame; rebuilding the plan (which splits the stored
     * order) per frame would allocate on the UI thread for an answer that only changes when
     * the settings do.
     */
    private var planTabCount = -1
    private var planOrder = ""
    private var planHideLeading = false
    private var planHideTrailing = false
    private var plan = DouyinTopBarPlan()

    private fun planFor(config: io.github.xzdw.dylt.config.GlassConfig): DouyinTopBarPlan {
        if (config.topBarTabCount != planTabCount ||
            config.topBarTabOrder != planOrder ||
            config.topBarHideLeadingAction != planHideLeading ||
            config.topBarHideTrailingAction != planHideTrailing
        ) {
            planTabCount = config.topBarTabCount
            planOrder = config.topBarTabOrder
            planHideLeading = config.topBarHideLeadingAction
            planHideTrailing = config.topBarHideTrailingAction
            plan = DouyinTopBarPlan.from(config)
        }
        return plan
    }

    override fun drawsNavigationContent(): Boolean = true

    /**
     * Whether the feed's video has stopped producing frames.
     *
     * The top copy pumps its own frames independently of the bottom bar, so it needs its own
     * detector instance. See [DouyinStaticSourceDetector] for why a SurfaceTexture timestamp
     * is the right signal.
     */
    private val staticSourceDetector = DouyinStaticSourceDetector()

    override fun videoSourceIsStatic(host: View): Boolean? =
        if (videoSampler.isBarStripStatic()) true else staticSourceDetector.isStatic()

    override fun barStripStatic(host: View): Boolean = videoSampler.isBarStripStatic()

    override fun videoFrameStamp(host: View): Long? = DouyinPlayerTextureHooks.currentFrameStamp()

    /** See [DouyinNavigationState]: the resting top copy reuses its scene too, bounded by
     *  [SCENE_MAX_AGE_MS] because its scene contains the hidden native row. */
    private val combinedSceneState = DouyinCombinedSceneState()

    /** Selection/labels/ink the retained scene was recorded for; see [draw]. */
    private var sceneSelectedIndex = Int.MIN_VALUE
    private var sceneLabelsKey = 0
    private var sceneInkInLens = false

    override fun reuseCombinedIndicatorScene(surfaceActive: Boolean): Boolean = !sceneInkInLens

    override fun combinedIndicatorSceneNeedsRecord(
        backdrop: android.graphics.Bitmap?, navigation: android.graphics.Bitmap?, signature: Long,
        width: Int, height: Int, dark: Boolean, hasDisplayList: Boolean,
    ): Boolean = combinedSceneState.needsRecord(
        backdrop, navigation, signature, width, height, dark, hasDisplayList,
        maxAgeMs = SCENE_MAX_AGE_MS,
    )

    override fun onCombinedIndicatorSceneRecorded(
        backdrop: android.graphics.Bitmap?, navigation: android.graphics.Bitmap?, signature: Long,
        width: Int, height: Int, dark: Boolean,
    ) = combinedSceneState.recorded(backdrop, navigation, signature, width, height, dark)

    override fun onHostPreDraw(
        host: View,
        source: ViewGroup?,
        selected: () -> Int,
        enabled: Boolean,
        density: Float,
        barHeightPx: Int,
        surfaceColor: () -> Int,
    ) {
        refresh(source as? ViewGroup)
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        // The scene was recorded earlier in this dispatch pass. The foreground renderer paints
        // the labels and the ink, but the scene still embeds the hidden native row, so a new
        // selection or a new label set has to invalidate it for the next frame. The age cap in
        // [combinedIndicatorSceneNeedsRecord] bounds anything these two signals miss.
        var labelsKey = labels.size
        labels.forEach { labelsKey = labelsKey * 31 + it.hashCode() }
        if (frame.selectedIndex != sceneSelectedIndex || labelsKey != sceneLabelsKey) {
            sceneSelectedIndex = frame.selectedIndex
            sceneLabelsKey = labelsKey
            combinedSceneState.invalidate()
        }
        sceneInkInLens = frame.selectionInkInLensScene
        renderer.draw(canvas, frame, labels, selectedIndex, leftAction, rightAction, inlineAction)
    }

    /**
     * The capsule follows Douyin's own selected tab, except during the commit window
     * that starts when the user taps the glass: Douyin has not moved its emphasis yet,
     * so the old reading would pull the capsule straight back off the tapped tab.
     */
    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        val now = SystemClock.uptimeMillis()
        committedCell?.let { committed ->
            if (now < committedUntil) {
                val index = tabs.indexOfFirst { it.cell === committed }
                if (index >= 0) return index
            } else {
                committedCell = null
            }
        }
        return selectedIndex.takeIf { it >= 0 && labels.isNotEmpty() }
    }

    /**
     * The capsule uses the shared inset (`bar height - 8dp`), exactly like the bottom
     * bar, so both edges of the shell keep the same 15 px of glass above and below the
     * capsule. The top bar is slimmer than the bottom one, so the two capsules differ in
     * absolute height while sitting at the same inset — which is what makes them read as
     * one design.
     */

    /**
     * The top copy is shown exactly while Douyin is still drawing its own title bar.
     *
     * Visibility flags are not enough: opening the drawer or the search page leaves the
     * whole title bar attached, VISIBLE and fully sized while Douyin slides it out of the
     * window — the same trap the bottom bar documented. The rendered rect is the shared
     * signal, so the glass leaves with the row it replaces.
     */
    override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?): Boolean? {
        val current = row ?: return false
        if (!current.isAttachedToWindow || !current.isShown) {
            closeMenu()
            return false
        }
        if (current.width <= 0 || current.height <= 0) {
            closeMenu()
            return false
        }
        if (!current.getGlobalVisibleRect(rowVisibleRect)) {
            closeMenu()
            return false
        }
        val minWidth = (current.width * MIN_VISIBLE_FRACTION).toInt()
        if (rowVisibleRect.width() < minWidth || rowVisibleRect.height() <= 0) {
            closeMenu()
            return false
        }
        return true
    }

    /**
     * Hand the tap back to Douyin's own tab cell. The host reports the slot index of the
     * glass layout, which is a position inside the window this state drew, so the cell is
     * taken from the same list rather than from the native container's child order (that
     * order is right-to-left).
     */
    override fun dispatchNativeNavigationTap(
        host: GlassHostLayout,
        source: ViewGroup?,
        index: Int,
        slotCount: Int,
    ): Boolean {
        val tab = tabs.getOrNull(index)
        if (tab == null) return false
        if (!clickView(tab.cell, "tab[$index]")) return false
        // Tapping a displayed tab is the explicit "leave the hidden page" gesture. Tapping the
        // swapped-in hidden tab itself keeps the override.
        if (selectedOverride?.cell !== tab.cell) selectedOverride = null
        committedCell = tab.cell
        committedUntil = SystemClock.uptimeMillis() + COMMIT_MS
        return true
    }

    /** The two circles: the drawer key on the left, Douyin's own search key on the right. */
    override fun performActionTap(host: View, source: ViewGroup?, leading: Boolean): Boolean {
        if (leading) return clickView(model?.leftAction, "leading key")
        return clickView(model?.rightAction, "trailing key")
    }

    /** The key inside the capsule opens the tab menu. */
    override fun performInlineActionTap(host: View, source: ViewGroup?): Boolean {
        toggleMenu()
        return true
    }

    /**
     * The leading key lists the tabs the capsule does not show. The menu is a separate
     * overlay view (see [DouyinTopTabMenu]): it hangs below the bar, where this host can
     * neither receive touches nor be tapped.
     */
    private fun toggleMenu() {
        val open = menu?.isOpen() == true
        if (open) {
            menu?.close()
            return
        }
        val row = row ?: return
        val shownCells = tabs.map { it.cell }.toSet()
        val hidden = DouyinTopBarFinder.allTabs(row).filter { it.cell !in shownCells }
        if (hidden.isEmpty()) return
        // The state's selected tab, not the model's: the latter still describes the native
        // visible strip when [selectedOverride] is swapped into the bar.
        val selectedCell = tabs.getOrNull(selectedIndex)?.cell
        val view = ensureMenu() ?: return
        val hostParent = host.parent as? ViewGroup ?: return
        if (hostParent.indexOfChild(view) < 0) {
            // Attach before opening: the panel captures the pixels behind itself, and that
            // needs the view's own size and window position.
            hostParent.addView(view, menuParams(hostParent))
        }
        val anchor = android.graphics.RectF(
            host.left.toFloat(),
            host.top.toFloat(),
            host.right.toFloat(),
            host.bottom.toFloat(),
        )
        val density = host.resources.displayMetrics.density
        view.onPick = { index -> pickHiddenTab(hidden, index) }
        val dark = host.isDarkGlass()
        view.open(
            labels = hidden.map { it.label },
            selectedIndex = hidden.indexOfFirst { it.cell === selectedCell },
            anchor = anchor,
            density = density,
            style = DouyinMenuStyle(
                dark = dark,
                config = host.config,
                fillColor = host.resolvedGlassFillColor(dark),
                barCornerRadiusPx = host.outerCornerRadiusPx(),
                blurRadiusPx = host.glassBlurRadiusPx(),
                materialEnabled = host.config.backdropCapture,
            ),
            scaledDensity = host.resources.displayMetrics.scaledDensity,
        )
    }

    private fun menuParams(parent: ViewGroup): ViewGroup.LayoutParams =
        android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

    private fun pickHiddenTab(hidden: List<DouyinTopTab>, index: Int) {
        val tab = hidden.getOrNull(index) ?: return
        if (clickView(tab.cell, "menu[$index] ${tab.label}")) {
            selectedOverride = tab
            committedCell = tab.cell
            committedUntil = SystemClock.uptimeMillis() + COMMIT_MS
        }
        menu?.close()
    }

    private fun ensureMenu(): DouyinTopTabMenu? {
        menu?.let { return it }
        // The host already carries the shell's context; the menu only needs resources and
        // gets attached to the same parent the host lives in.
        return DouyinTopTabMenu(host.context).also { menu = it }
    }

    private fun closeMenu() {
        menu?.takeIf { it.isOpen() }?.close()
    }

    /**
     * The adapter owns the top row's touch flags.
     *
     * The shared helper marks the whole row *and every descendant* clickable, which is
     * what the bottom bar wants (the glass is the hit target and the native cells only
     * need to answer `performClick`). On the title bar it breaks Douyin's own leading
     * key: its inner `content_container` has no click listener, so a real finger press is
     * consumed there instead of reaching the ancestor that opens the drawer — the button
     * looked alive yet did nothing. Returning true leaves the row's flags untouched.
     */
    override fun setNativeBarTouchable(view: View?, touchable: Boolean): Boolean = true

    /**
     * The adapter owns this row's chrome: it must keep Douyin's leading key visible
     * while the strip and the trailing key stay hidden. The shared "hide the redrawn
     * source" step would fade the whole row, including that key.
     */
    override fun suppressRedrawnSource(): Boolean = false

    override fun requestVideoBackdrop(host: View, pageAllowed: Boolean) {
        if (videoSampler.isBarStripStatic()) return
        videoSampler.request(host, pageAllowed, false)
    }

    override fun videoBackdrop(): AppVideoBackdrop? = videoSampler.snapshot()

    override fun onHostDetached() {
        // The row may be hidden because this copy drew it. On the way out, hand it back: that
        // is what makes switching the top bar off (or reloading the module) restore Douyin's
        // own title bar instead of leaving an invisible strip behind.
        row?.let { DouyinTopBarFinder.showNativeTitleBar(it) }
        menu?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        menu = null
        videoSampler.dispose()
        row = null
        model = null
        tabs = emptyList()
        labels = emptyList()
        selectedIndex = -1
        committedCell = null
        selectedOverride = null
        appliedSlotCount = -1
    }

    /**
     * Runs the native click for [view]: its own listener when it has one, otherwise the
     * nearest clickable ancestor, and finally a synthesized tap on [view] itself.
     *
     * The tap is dispatched to the cell, never to the row: the tabs this menu lists are
     * usually scrolled out of view, and their row-relative position clamps onto whichever
     * tab happens to sit at the row's edge — which switched to the wrong page. A hidden
     * strip cell may also have been made non-clickable by the chrome suppression, so the
     * chain is re-enabled for the click; that suppression re-applies it every frame.
     */
    private fun clickView(view: View?, label: String): Boolean {
        if (view == null) return false
        var node: View? = view
        var depth = 0
        while (node != null && depth <= CLICK_ANCESTOR_SCAN) {
            val current = node
            if (!current.isClickable) current.isClickable = true
            if (runCatching { current.performClick() }.getOrDefault(false)) {
                return true
            }
            node = current.parent as? View
            depth++
        }
        if (view.width <= 0 || view.height <= 0) return false
        val x = (view.width / 2f).coerceAtLeast(1f)
        val y = (view.height / 2f).coerceAtLeast(1f)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 40L, MotionEvent.ACTION_UP, x, y, 0)
        val handled = runCatching {
            view.dispatchTouchEvent(down) or view.dispatchTouchEvent(up)
        }.getOrDefault(false)
        down.recycle()
        up.recycle()
        log("$label no click listener; synthesized tap on ${view.javaClass.simpleName} handled=$handled")
        return handled
    }

    /**
     * Binds the native title bar once and re-reads it every frame. The row is cached:
     * the full-tree search only re-runs while no row is bound.
     */
    private fun refresh(source: ViewGroup?) {
        // The host stays attached while the page gate/GONE hides the top copy. Its pre-draw
        // callback still runs every frame, so without this check the title-bar model and the
        // once-a-second full-tree find() would keep working for a bar that is not drawn.
        if (!host.isShown || !host.isAttachedToWindow) return
        var current = row
        if (current == null || !current.isAttachedToWindow) {
            val now = SystemClock.uptimeMillis()
            if (now - lastFind >= FIND_RETRY_MS) {
                lastFind = now
                current = DouyinTopBarFinder.find(host.rootView)
                if (current != null) {
                    row = current
                    log("row found ${current.javaClass.simpleName} ${current.width}x${current.height}")
                    if (host.navigationSource !== current) {
                        runCatching { host.rebindNavigationSource(current, emptyList(), host.config) }
                            .onFailure { log("rebind failed: $it") }
                    }
                }
            }
        }
        if (current == null) return

        val read = DouyinTopBarFinder.model(current, planFor(host.config)) ?: return
        model = read
        val override = selectedOverride?.takeIf { it.cell.isAttachedToWindow }
        if (selectedOverride != null && override == null) selectedOverride = null
        if (override != null) {
            val existing = read.tabs.indexOfFirst { it.cell === override.cell }
            if (existing >= 0) {
                tabs = read.tabs
                labels = read.labels
                selectedIndex = existing
            } else if (read.tabs.isNotEmpty()) {
                // Keep the bar's slot count and the fixed 推荐 at the right edge, but make the
                // current hidden page the leftmost (lowest-priority) slot so its selection is
                // visible. This is only a display swap: taps still go to the native cell.
                val display = read.tabs.toMutableList()
                display[0] = override.copy(selected = true)
                tabs = display
                labels = display.map { it.label }
                selectedIndex = 0
            } else {
                tabs = read.tabs
                labels = read.labels
                selectedIndex = read.selectedIndex
            }
        } else {
            tabs = read.tabs
            labels = read.labels
            selectedIndex = read.selectedIndex
        }
        leftAction = read.leftAction != null
        rightAction = read.rightAction != null
        inlineAction = true
        reportTopTabs(current)
        if (labels.size != appliedSlotCount) {
            if (labels.size == pendingSlotCount) {
                pendingSlotFrames++
            } else {
                pendingSlotCount = labels.size
                pendingSlotFrames = 1
            }
            if (pendingSlotFrames >= SLOT_COUNT_STABLE_FRAMES) {
                appliedSlotCount = labels.size
                host.updateAdapterSlotCount(labels.size, selectedIndex.coerceAtLeast(0))
                // The slot count is normally already right when the host is constructed,
                // so the count-changed path above is a no-op; this is what applies the
                // content-hugging width to the top copy.
                host.refreshHostLayout()
            }
        }
    }

    /**
     * 顶栏标签排序: hands the title bar's own labels to the manager, so the reorder page lists
     * the tabs Douyin actually carries. The manager keeps them per package; this process
     * never reads them back. Throttled and de-duplicated because [refresh] runs every frame.
     */
    private fun reportTopTabs(row: ViewGroup) {
        val now = SystemClock.uptimeMillis()
        if (now - lastReportAt < REPORT_INTERVAL_MS) return
        // The stamp has to move before the de-duplication return below. Leaving it behind on an
        // unchanged key made the gate expire once (2 s after the last change) and then run the
        // full `allTabs` walk on every single frame, forever, while the labels stayed the same.
        lastReportAt = now
        val labels = DouyinTopBarFinder.allTabs(row, normalizeLocation = true)
            .map { it.label.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (labels.isEmpty()) return
        val key = labels.joinToString("\u0001")
        if (key == lastReportedTabs) return
        lastReportedTabs = key
        val appContext = context.applicationContext ?: context
        val packageName = context.packageName
        val payload = ArrayList(labels)
        topTabReportExecutor.execute {
            runCatching {
                appContext.contentResolver.call(
                    ConfigContract.URI,
                    ConfigContract.METHOD_REPORT_TOP_TABS,
                    packageName,
                    Bundle().apply {
                        putStringArrayList(ConfigContract.KEY_TOP_TAB_LABELS, payload)
                    },
                )
            }.onFailure {
                // The manager may not be running; the labels are only a convenience for its
                // reorder page, so a failed report must never affect the bar itself.
                log("tab report failed: ${it.javaClass.simpleName}: ${it.message?.take(160)}")
            }
        }
    }

    private fun log(message: String) {
        Xposed.log("[OfflineGlass][TopBar] $message")
    }
}
