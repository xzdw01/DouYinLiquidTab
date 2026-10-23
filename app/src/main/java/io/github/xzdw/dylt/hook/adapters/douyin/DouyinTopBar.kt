package io.github.xzdw.dylt.hook.adapters.douyin

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import io.github.xzdw.dylt.config.GlassConfig
import io.github.xzdw.dylt.hook.GlassHostLayout

/** One tab cell of Douyin's own top title bar, as measured this frame. */
internal data class DouyinTopTab(
    val cell: View,
    /** Label read from the cell's own TextView; never a hardcoded name. */
    val label: String,
    val selected: Boolean,
    /** Horizontal overlap of the cell with the strip viewport, in px. */
    val visibleWidth: Int,
    /** Label alpha as Douyin leaves it; the current tab keeps a higher value. */
    val labelAlpha: Float,
    /** Screen X captured once when the model was built, so sorting does not re-query it. */
    val screenX: Int = 0,
)

/**
 * One frame of Douyin's top title bar: the tabs that are actually visible in the
 * strip right now, the selected one, and the two corner action cells.
 */
internal data class DouyinTopBarModel(
    val row: ViewGroup,
    /** Visible tabs, left to right in screen order. */
    val tabs: List<DouyinTopTab>,
    /** [tabs]' labels, stored once: the frame is rebuilt from this on every draw. */
    val labels: List<String>,
    /** How many tabs the strip showed in total, before the display filter was applied. */
    val visibleTotal: Int,
    /** Which rule picked the shown tabs: "preferred" (the feed pair) or "window". */
    val shownRule: String,
    /** Index into [tabs], or -1 when the native selection could not be read. */
    val selectedIndex: Int,
    val selectedSource: String,
    val leftAction: View?,
    val rightAction: View?,
)

/**
 * 顶栏标签数量 / 顶栏标签排序 / 顶栏两侧按钮, as the finder needs them.
 *
 * Kept as a small value type instead of passing [GlassConfig] through every call: the model
 * only cares about these four decisions, and the state can build one once per config change.
 */
internal data class DouyinTopBarPlan(
    val tabCount: Int = 2,
    /** Explicit order, one label per entry; empty means "the shipped pair / native window". */
    val order: List<String> = emptyList(),
    val hideLeading: Boolean = false,
    val hideTrailing: Boolean = false,
) {
    companion object {
        fun from(config: GlassConfig): DouyinTopBarPlan = DouyinTopBarPlan(
            tabCount = config.topBarTabCount.coerceIn(
                GlassConfig.TOP_BAR_MIN_TABS,
                GlassConfig.TOP_BAR_MAX_TABS,
            ),
            order = if (config.topBarTabOrder.isBlank()) {
                emptyList()
            } else {
                config.topBarTabOrder
                    .split('\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            },
            hideLeading = config.topBarHideLeadingAction,
            hideTrailing = config.topBarHideTrailingAction,
        )
    }
}

/**
 * Locates and reads Douyin's own top title bar (`MainTitleBar`).
 *
 * The module does not invent the top bar's content: the tab list, their order and
 * their text all come from the views Douyin built, so a changed tab set on another
 * page or another app version is picked up automatically. Only the cells whose
 * rendered rect still overlaps the strip viewport are reported, which is the
 * "draw just the tabs that are visible right now" rule.
 *
 * Matching is by class name: the title-bar classes are Douyin's own and are not
 * obfuscated, while the ids in that subtree (`fjr`, `ln5`, `x6r`, `5du`) are
 * regenerated between versions.
 */
internal object DouyinTopBarFinder {
    private const val ROW_CLASS = "MainTitleBar"
    private const val STRIP_CLASS = "MainTabStripNew"
    private const val SCROLL_CLASS = "MainTabStripScrollView"
    private const val ITEM_HOST_CLASS = "MainTabRelativeLayout"
    private const val TAB_CLASS = "HomeTopTabItemViewNew"
    private const val CORNER_CLASS = "TopCornerEntranceView"

    /** Share of a tab cell that has to sit inside the strip to be drawn. */
    private const val MIN_VISIBLE_FRACTION = 0.5f

    /** Minimum label-alpha lead that counts as "this tab is the current one". */
    private const val ALPHA_MARGIN = 0.01f

    /**
     * How often the clickable-suppression walk is allowed to run again on a row it has
     * already cleared.
     *
     * `hideNativeTitleBar` is called twice per display frame, but the walk only has real
     * work the first time per row. This interval is the safety net for a row Douyin reuses
     * and adds children to: those arrive clickable, so a periodic re-scan keeps the
     * "no invisible tap target" guarantee without paying for the walk every frame.
     */
    private const val SUPPRESS_RESCAN_MS = 500L

    /** Row whose subtree was last cleared, its child count, and when. See [SUPPRESS_RESCAN_MS]. */
    private var suppressedRow: View? = null
    private var suppressedChildCount = -1
    private var suppressedAt = 0L

    /**
     * Display filter: the feed's own two entries out of whatever the strip currently
     * offers, in the order they sit on screen. 推荐 is the fixed first tab of the reorder
     * list (see [FIXED_FIRST_TAB]) and therefore the rightmost label here. The labels
     * themselves are still read from Douyin's cells — this only decides *which* of them the
     * glass draws. 顶栏标签排序 replaces this pair when the user stored an order; an empty
     * order with a count other than two shows the native window ending at 推荐.
     */
    private val PREFERRED_DISPLAY_TABS = listOf("关注", "推荐")

    /**
     * 顶栏标签排序: 推荐 is always the first tab of the order list and cannot be moved, so it
     * is the rightmost label in the top bar. The order page locks its row; this constant is
     * the hook-side counterpart, so a hand-edited store cannot put another tab ahead of it.
     */
    const val FIXED_FIRST_TAB = "推荐"

    private const val MAX_VISITED = 3_000

    /** Re-scan interval while one of the two corner cells is missing. */
    private const val CORNER_RESCAN_MS = 1_000L

    /**
     * Per-cell label cache. `model()` runs on every display frame; walking each cell's subtree
     * for its TextView and allocating `text.toString()` per frame was pure churn for labels that
     * change only when Douyin rebuilds the strip. The TextView is weak so a replaced title bar
     * can be collected (a strong child would pin the row through its parent chain).
     */
    private class CellText(
        val textRef: java.lang.ref.WeakReference<TextView>?,
        val source: CharSequence?,
        val label: String,
    ) {
        val text: TextView? get() = textRef?.get()
    }

    private val cellTexts = java.util.WeakHashMap<View, CellText>()
    private val emptyCellText = CellText(null, null, "")

    /**
     * `Class.getSimpleName()` is reflective and the finder walks hundreds of nodes per scan
     * (`find`, `allTabs`, `corners`, the page controller's per-view `isTopRow`). The answer is
     * fixed for a Class, so resolve it once.
     */
    private val classNames = HashMap<Class<*>, String>(64)

    private fun cachedSimpleName(type: Class<*>): String = classNames.getOrPut(type) { type.simpleName }

    /** Cached corner cells; [corners] avoids two 600-view walks per frame. */
    private var cornersRowRef: java.lang.ref.WeakReference<ViewGroup>? = null
    private var cornersRowWidth = -1
    private var cornersRowChildCount = -1
    private var cornersResolved = false
    private var cornersAt = 0L
    private var cachedLeftCornerRef: java.lang.ref.WeakReference<View>? = null
    private var cachedRightCornerRef: java.lang.ref.WeakReference<View>? = null

    /** Whether [view] is Douyin's own top title bar row. */
    fun isTopRow(view: View): Boolean = cachedSimpleName(view.javaClass) == ROW_CLASS

    /**
     * Takes Douyin's own title bar out of the picture.
     *
     * The glass copy now draws the whole row — its own drawer key on the left, the capsule
     * with the tab-menu key and the tabs, and Douyin's search key on the right — so the
     * native row is faded out, and its whole subtree is made non-clickable. That second half
     * matters: an alpha-0 strip is still a tap target, and it reaches past the glass bar, so
     * a press beside the bar used to switch to a tab the user cannot see. Clicks the copy
     * forwards go through `performClick`, which no clickable flag is needed for.
     */
    fun hideNativeTitleBar(row: ViewGroup) {
        if (row.alpha != 0f) row.alpha = 0f

        // The recursive pass only has to run when the answer can have changed. It clears
        // isClickable across the whole row subtree, and once cleared nothing re-arms those
        // flags while the glass copy owns the row — yet this ran twice per display frame
        // (two suppressNativeBottomChrome calls inside the same pre-draw listener), i.e.
        // ~100k-200k pointless view visits per second for a walk that changed nothing.
        //
        // The alpha write three lines up was already guarded for exactly this reason; the
        // walk simply was not. A periodic re-scan is kept as the safety net, because Douyin
        // can add children to a row it reuses and those would arrive clickable.
        val now = SystemClock.uptimeMillis()
        if (suppressedRow !== row || row.childCount != suppressedChildCount ||
            now - suppressedAt >= SUPPRESS_RESCAN_MS
        ) {
            setSubtreeClickable(row, false)
            suppressedRow = row
            suppressedChildCount = row.childCount
            suppressedAt = now
        }
    }

    /**
     * Puts Douyin's own title bar back, for when the glass copy goes away (the top bar's
     * switch is turned off, or the host is detached). Only views that actually carry a click
     * listener are re-armed: that is what "clickable" meant before the copy cleared the flags,
     * and blindly setting the flag would arm views the app never made tappable.
     */
    fun showNativeTitleBar(row: ViewGroup) {
        if (row.alpha != 1f) row.alpha = 1f
        if (suppressedRow === row) suppressedRow = null
        restoreSubtree(row)
    }

    private fun restoreSubtree(view: View) {
        if (!view.isClickable && view.hasOnClickListeners()) view.isClickable = true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) restoreSubtree(view.getChildAt(index))
        }
    }

    private fun setSubtreeClickable(view: View, clickable: Boolean) {
        if (view.isClickable != clickable) view.isClickable = clickable
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) setSubtreeClickable(view.getChildAt(index), clickable)
        }
    }

    /** The title bar row, or the container that owns the tab strip as a fallback. */
    fun find(root: View): ViewGroup? {        val stack = ArrayDeque<View>()
        stack += root
        var visited = 0
        var stripOwner: ViewGroup? = null
        while (stack.isNotEmpty() && visited++ < MAX_VISITED) {
            val view = stack.removeLast()
            // Never descend into our own glass copies.
            if (view is GlassHostLayout) continue
            if (view is ViewGroup) {
                when (cachedSimpleName(view.javaClass)) {
                    ROW_CLASS -> return view
                    STRIP_CLASS -> if (stripOwner == null) stripOwner = view.parent as? ViewGroup
                }
                for (index in view.childCount - 1 downTo 0) stack += view.getChildAt(index)
            }
        }
        return stripOwner
    }

    /** Null when the row no longer carries a readable tab strip. */
    fun model(
        row: ViewGroup,
        plan: DouyinTopBarPlan = DouyinTopBarPlan(),
    ): DouyinTopBarModel? {
        val located = locate(row) ?: return null
        val strip = located.first
        val itemHost = located.second

        val viewport = modelViewport
        if (!strip.getGlobalVisibleRect(viewport)) return null

        // A display frame used to rebuild the whole model even when the row had not moved a
        // pixel: Rect, two lists, an IntArray, one MeasuredTab per cell, two sorts and one
        // DouyinTopTab per shown tab. Reuse the previous model while everything it reads is
        // unchanged; the signature is plain property reads plus one getLocationOnScreen per
        // cell, with no allocations.
        val signature = modelSignature(row, itemHost, viewport, plan)
        if (signature == cachedModelSignature &&
            cachedModelRow?.get() === row &&
            cachedModelPlan == plan
        ) {
            cachedModel?.get()?.let { return it }
        }

        val cells = ArrayList<View>(9)
        for (index in 0 until itemHost.childCount) {
            val child = itemHost.getChildAt(index)
            if (cachedSimpleName(child.javaClass) == TAB_CLASS ||
                (child is ViewGroup && cellText(child).text != null)
            ) {
                cells += child
            }
        }

        // `measured` is the subset that is actually on screen, and it stays the selection
        // source: Douyin's brightest-label signal only exists for rendered cells. `all` is
        // only needed for an explicit 标签页排序 order (including a tab the strip has scrolled
        // out of view); the shipped two-tab/default path never allocates or labels it.
        val needAll = plan.order.isNotEmpty()
        val rawAll = if (needAll) ArrayList<MeasuredTab>(cells.size) else null
        val rawMeasured = ArrayList<MeasuredTab>(cells.size)
        val location = IntArray(2)
        cells.forEach { cell ->
            if (cell.width <= 0 || cell.height <= 0) return@forEach
            cell.getLocationOnScreen(location)
            val left = location[0]
            val top = location[1]
            val right = left + cell.width
            val bottom = top + cell.height
            val overlapWidth = minOf(right, viewport.right) - maxOf(left, viewport.left)
            val overlapHeight = minOf(bottom, viewport.bottom) - maxOf(top, viewport.top)
            val overlap = if (overlapWidth > 0 && overlapHeight > 0) overlapWidth else 0
            val visible = overlap >= cell.width * MIN_VISIBLE_FRACTION
            if (!needAll && !visible) return@forEach
            val cached = cellText(cell)
            val text = cached.text
            val tab = MeasuredTab(
                cell = cell,
                label = cached.label,
                labelAlpha = text?.alpha ?: 1f,
                flagSelected = isSelected(cell, text),
                screenX = left,
                visibleWidth = overlap,
            )
            rawAll?.add(tab)
            if (visible) rawMeasured += tab
        }
        if (rawMeasured.isEmpty()) return null
        if (rawAll != null && rawAll.isEmpty()) return null

        // 顶栏标签排序: the location tab's label is the current place name (亳州, 花戏楼, …).
        // The top bar keeps showing that native text; only the settings report canonicalises
        // it to 同城. [locationCell] is the identity used to match a stored 同城 entry and to
        // canonicalise the report without touching the displayed label.
        val locationCell = rawAll?.firstOrNull {
            it.label.isNotBlank() && it.label !in GlassConfig.TOP_BAR_STABLE_TABS
        }?.cell
        val all = rawAll ?: emptyList()
        val measured = rawMeasured

        // The strip's children are laid out right to left in z-order, so the glass
        // capsule has to be laid out on screen order, not on child order.
        rawAll?.sortBy { it.screenX }
        measured.sortBy { it.screenX }

        val flagged = measured.indexOfFirst { it.flagSelected }
        val brightest = brightestIndex(measured)
        val selectedAmongVisible = when {
            flagged >= 0 -> flagged
            brightest >= 0 -> brightest
            else -> -1
        }
        val selectedSource = when {
            flagged >= 0 -> "flag"
            brightest >= 0 -> "alpha"
            else -> "none"
        }

        // 顶栏标签数量 / 顶栏标签排序: an explicit order picks its labels from every cell
        // (including a tab the strip has scrolled out, which the leading key's menu also
        // knows how to tap). An unset order keeps the shipped design: the feed pair for two
        // tabs, and the native visible window around the current tab for any other count.
        val tabCount = plan.tabCount.coerceIn(GlassConfig.TOP_BAR_MIN_TABS, GlassConfig.TOP_BAR_MAX_TABS)
        // [shown] is always the left-to-right display order. The reorder page's list is
        // right-to-left (推荐 first = rightmost), so an explicit order is reversed exactly once.
        val shown: List<MeasuredTab>
        val shownRule: String
        if (plan.order.isNotEmpty()) {
            val chosen = ArrayList<MeasuredTab>(tabCount)
            // 推荐 is fixed first in the order list; the stored order supplies everything after it.
            val orderedLabels = ArrayList<String>(plan.order.size + 1)
            orderedLabels += FIXED_FIRST_TAB
            plan.order.forEach { label ->
                if (label != FIXED_FIRST_TAB) orderedLabels += label
            }
            orderedLabels.forEach { label ->
                if (chosen.size < tabCount) {
                    all.firstOrNull { tab ->
                        tab !in chosen && matchesConfiguredLabel(tab, label, locationCell)
                    }?.let(chosen::add)
                }
            }
            if (chosen.size < tabCount) {
                // Fill the remainder from what Douyin is showing now, favouring a window
                // around the current tab, so a partially matching order never leaves the
                // capsule with a tooltip-sized gap.
                windowAround(measured, selectedAmongVisible, tabCount).forEach { tab ->
                    if (chosen.size < tabCount && tab !in chosen) chosen += tab
                }
                all.forEach { tab ->
                    if (chosen.size < tabCount && tab !in chosen) chosen += tab
                }
            }
            shown = chosen.asReversed()
            shownRule = "ordered"
        } else if (tabCount == PREFERRED_DISPLAY_TABS.size) {
            // 关注 then 推荐, in the fixed display order rather than the strip's order.
            val preferred = PREFERRED_DISPLAY_TABS.mapNotNull { label ->
                measured.firstOrNull { it.label == label }
            }
            if (preferred.size >= PREFERRED_DISPLAY_TABS.size) {
                shown = preferred.take(tabCount)
                shownRule = "preferred"
            } else {
                shown = windowEndingAtFixed(measured, selectedAmongVisible, tabCount)
                shownRule = "window"
            }
        } else {
            // No stored order: the native window ending at 推荐, so 推荐 stays rightmost.
            shown = windowEndingAtFixed(measured, selectedAmongVisible, tabCount)
            shownRule = "window"
        }

        // The capsule follows the native selection only while that tab is on screen.
        val selectedCell = measured.getOrNull(selectedAmongVisible)?.cell
        val selectedInShown = shown.indexOfFirst { it.cell === selectedCell }

        val tabs = shown.map { tab ->
            DouyinTopTab(
                cell = tab.cell,
                label = tab.label,
                selected = tab.cell === selectedCell,
                visibleWidth = tab.visibleWidth,
                labelAlpha = tab.labelAlpha,
                screenX = tab.screenX,
            )
        }

        val (leftCorner, rightCorner) = if (plan.hideLeading && plan.hideTrailing) {
            null to null
        } else {
            corners(row)
        }

        val built = DouyinTopBarModel(
            row = row,
            tabs = tabs,
            labels = tabs.map { it.label },
            visibleTotal = measured.size,
            shownRule = shownRule,
            selectedIndex = selectedInShown,
            selectedSource = selectedSource,
            leftAction = if (plan.hideLeading) null else leftCorner,
            rightAction = if (plan.hideTrailing) null else rightCorner,
        )
        cachedModel = java.lang.ref.WeakReference(built)
        cachedModelRow = java.lang.ref.WeakReference(row)
        cachedModelPlan = plan
        cachedModelSignature = signature
        return built
    }

    /**
     * The live state [model] reads, mixed into one Long so an unchanged row can reuse the last
     * model. Icon drawables are deliberately absent: the top copy paints its own glyphs, and the
     * corner cells are only tap targets, so their bitmap identity cannot change what is drawn.
     */
    private fun modelSignature(
        row: ViewGroup,
        itemHost: ViewGroup,
        viewport: Rect,
        plan: DouyinTopBarPlan,
    ): Long {
        var h = 17L
        h = mix(h, System.identityHashCode(row).toLong())
        h = mix(h, System.identityHashCode(itemHost).toLong())
        h = mix(h, row.width.toLong())
        h = mix(h, row.height.toLong())
        h = mix(h, row.childCount.toLong())
        h = mix(h, itemHost.childCount.toLong())
        h = mix(h, viewport.left.toLong())
        h = mix(h, viewport.top.toLong())
        h = mix(h, viewport.right.toLong())
        h = mix(h, viewport.bottom.toLong())
        h = mix(h, plan.tabCount.toLong())
        h = mix(h, if (plan.hideLeading) 1L else 0L)
        h = mix(h, if (plan.hideTrailing) 1L else 0L)
        h = mix(h, plan.order.hashCode().toLong())
        h = mix(h, System.identityHashCode(cachedLeftCornerRef?.get()).toLong())
        h = mix(h, System.identityHashCode(cachedRightCornerRef?.get()).toLong())
        for (index in 0 until itemHost.childCount) {
            val cell = itemHost.getChildAt(index)
            h = mix(h, System.identityHashCode(cell).toLong())
            h = mix(h, cell.width.toLong())
            h = mix(h, cell.height.toLong())
            h = mix(h, cell.visibility.toLong())
            h = mix(h, if (cell.isSelected) 1L else 0L)
            h = mix(h, if (cell.isActivated) 1L else 0L)
            cell.getLocationOnScreen(signatureLocation)
            h = mix(h, signatureLocation[0].toLong())
            h = mix(h, signatureLocation[1].toLong())
            val cachedText = cellText(cell)
            h = mix(h, cachedText.label.hashCode().toLong())
            val labelView = cachedText.text
            if (labelView != null) {
                h = mix(h, labelView.currentTextColor.toLong())
                h = mix(h, labelView.textSize.toBits().toLong())
                h = mix(h, java.lang.Float.floatToRawIntBits(labelView.alpha).toLong())
                h = mix(h, if (labelView.isSelected) 1L else 0L)
                h = mix(h, if (labelView.isActivated) 1L else 0L)
            }
        }
        return h
    }

    private fun mix(h: Long, value: Long): Long = h * 31 + value

    /**
     * 顶栏标签排序: a configured label matches the location cell when it is 同城 (or any
     * label outside the fixed eight, e.g. an old 亳州/花戏楼 store). The cell's own label is
     * left untouched, because the top bar must keep showing the native place name.
     */
    private fun matchesConfiguredLabel(
        tab: MeasuredTab,
        label: String,
        locationCell: View?,
    ): Boolean {
        if (tab.cell === locationCell) {
            return label == GlassConfig.TOP_BAR_LOCATION_TAB ||
                label !in GlassConfig.TOP_BAR_STABLE_TABS
        }
        return tab.label == label
    }

    /**
     * A contiguous window of at most [limit] tabs that contains [anchor], anchored to the
     * right when the selection sits near the end of the strip. This is the original
     * four-tab fallback generalised to the configured label count.
     */
    private fun windowAround(
        tabs: List<MeasuredTab>,
        anchor: Int,
        limit: Int,
    ): List<MeasuredTab> {
        if (tabs.size <= limit) return tabs
        val start = (anchor.coerceAtLeast(0) - (limit - 1)).coerceIn(0, tabs.size - limit)
        return tabs.subList(start, start + limit)
    }

    /**
     * 顶栏标签排序: the native window ending at 推荐, so 推荐 is the rightmost label. The
     * fixed tab is moved to the window's right edge if the strip happens to place it
     * elsewhere; when there is no 推荐 cell the selection anchors the window instead.
     */
    private fun windowEndingAtFixed(
        tabs: List<MeasuredTab>,
        anchor: Int,
        limit: Int,
    ): List<MeasuredTab> {
        val fixedIndex = tabs.indexOfLast { it.label == FIXED_FIRST_TAB }
        if (fixedIndex < 0) return windowAround(tabs, anchor.coerceAtLeast(0), limit)
        val fixed = tabs[fixedIndex]
        val window = windowAround(tabs, fixedIndex, limit).toMutableList()
        if (window.lastOrNull() !== fixed) {
            window.remove(fixed)
            window.add(fixed)
        }
        return window
    }


    /** One cell as measured, before the left-to-right ordering is applied. */
    private data class MeasuredTab(
        val cell: View,
        val label: String,
        val labelAlpha: Float,
        val flagSelected: Boolean,
        val screenX: Int,
        val visibleWidth: Int,
    )

    /**
     * The current tab carries the brightest label.
     *
     * Douyin's title-bar cells expose no selected/activated flag (measured on 40.7.0:
     * every cell reports `sel=false act=false`), but the label of the tab that is
     * actually current keeps a visibly higher alpha than its siblings — 0.749 against
     * 0.647 while the feed sat on 推荐. The rule is therefore relative: the selection
     * only counts when exactly one label is brighter than all the others, so a screen
     * where Douyin dims or brightens everything equally reports "unknown" and leaves
     * the capsule where it was instead of guessing.
     */
    private fun brightestIndex(tabs: List<MeasuredTab>): Int {
        if (tabs.size < 2) return if (tabs.size == 1) 0 else -1
        val best = tabs.maxByOrNull { it.labelAlpha } ?: return -1
        val second = tabs.filter { it !== best }.maxOfOrNull { it.labelAlpha } ?: return -1
        if (best.labelAlpha <= 0f) return -1
        return if (best.labelAlpha - second >= ALPHA_MARGIN) tabs.indexOf(best) else -1
    }

    /**
     * Every tab cell the title bar carries, in screen order — including the ones the
     * strip has scrolled out of view. The leading key's menu lists these, so the tabs the
     * capsule does not show are still reachable.
     */
    fun allTabs(row: ViewGroup, normalizeLocation: Boolean = false): List<DouyinTopTab> {
        val host = itemHost(row) ?: return emptyList()
        val cells = ArrayList<View>(9)
        for (index in 0 until host.childCount) {
            val child = host.getChildAt(index)
            if (child.javaClass.simpleName == TAB_CLASS ||
                (child is ViewGroup && cellText(child).text != null)
            ) {
                cells += child
            }
        }
        val location = IntArray(2)
        val raw = cells.map { cell ->
            val cached = cellText(cell)
            val text = cached.text
            cell.getLocationOnScreen(location)
            DouyinTopTab(
                cell = cell,
                label = cached.label,
                selected = isSelected(cell, text),
                visibleWidth = 0,
                labelAlpha = text?.alpha ?: 1f,
                screenX = location[0],
            )
        }
        val locationCell = raw.firstOrNull {
            it.label.isNotBlank() && it.label !in GlassConfig.TOP_BAR_STABLE_TABS
        }?.cell
        // The dropdown lists the same native labels as the bar; only the settings report
        // asks for the canonical 同城.
        val tabs = if (normalizeLocation && locationCell != null) {
            raw.map { tab ->
                if (tab.cell === locationCell) {
                    tab.copy(label = GlassConfig.TOP_BAR_LOCATION_TAB)
                } else {
                    tab
                }
            }
        } else {
            raw
        }
        return tabs.sortedBy { it.screenX }
    }

    /**
     * The strip and tab-cell host for [row], located once per row rather than per frame.
     *
     * `model()` runs on every display frame. Both of the walks below are bounded subtree
     * searches (up to 600 nodes, each comparing `javaClass.simpleName`), and they were being
     * paid twice per frame purely to answer "where is the strip" — an answer that does not
     * change until Douyin rebuilds the row.
     *
     * Only the two container references are cached. Everything that can change frame to
     * frame — the cells, their positions, labels, selection — is still read fresh by
     * `model()` on every call, so a tab switch or relayout is picked up immediately.
     *
     * `isAttachedToWindow` is the invalidation signal: when Douyin swaps the title bar the
     * old strip detaches from the window, and the next call re-walks.
     */
    private var locatedRow: java.lang.ref.WeakReference<ViewGroup>? = null

    private var locatedStrip: ViewGroup? = null

    private var locatedItemHost: ViewGroup? = null

    /**
     * Last built model, weakly: a model keeps its row (and through it the Activity) alive, and
     * the state already holds the live one. The cache must never outlive the top bar host.
     */
    private var cachedModel: java.lang.ref.WeakReference<DouyinTopBarModel>? = null
    private var cachedModelRow: java.lang.ref.WeakReference<ViewGroup>? = null
    private var cachedModelPlan: DouyinTopBarPlan? = null
    private var cachedModelSignature = 0L

    /** Scratch for [modelSignature] and for the viewport read; both run every frame. */
    private val signatureLocation = IntArray(2)
    private val modelViewport = Rect()

    private fun locate(row: ViewGroup): Pair<ViewGroup, ViewGroup>? {
        val strip = locatedStrip
        val host = locatedItemHost
        if (locatedRow?.get() === row &&
            strip != null && host != null &&
            strip.isAttachedToWindow && host.isAttachedToWindow
        ) {
            return strip to host
        }
        val foundStrip = firstDescendant(row, SCROLL_CLASS) as? ViewGroup
            ?: firstDescendantOfType(row, HorizontalScrollView::class.java)
            ?: return null
        val foundHost = itemHost(row, foundStrip) ?: return null
        locatedRow = java.lang.ref.WeakReference(row)
        locatedStrip = foundStrip
        locatedItemHost = foundHost
        return foundStrip to foundHost
    }

    /** The container whose children are the tab cells. */
    private fun itemHost(row: ViewGroup): ViewGroup? = itemHost(row, null)

    /**
     * [strip] is the strip the caller already located.
     *
     * The fallback below used to re-run `firstDescendant(row, SCROLL_CLASS)` — the exact
     * walk that produced `strip` one line earlier in `model()`. `model()` runs on every
     * display frame, so that duplicate was a full extra subtree search per frame for an
     * answer already in hand.
     */
    private fun itemHost(row: ViewGroup, strip: ViewGroup?): ViewGroup? =
        firstDescendant(row, ITEM_HOST_CLASS) as? ViewGroup
            ?: (strip ?: firstDescendant(row, SCROLL_CLASS) as? ViewGroup)
                ?.takeIf { it.childCount > 0 }
                ?.let { it.getChildAt(0) as? ViewGroup }

    /**
     * [text] is the cell's label the caller already located.
     *
     * Callers pass it in, because the walk to find it is otherwise repeated per cell on every
     * display frame.
     */
    private fun isSelected(cell: View, text: TextView?): Boolean {
        if (cell.isSelected || cell.isActivated) return true
        text ?: return false
        return text.isSelected || text.isActivated
    }

    /**
     * Corner action cells sit outside the strip; the leading one is left of centre.
     *
     * Both sides are resolved in one walk and cached per row. This used to run `collect` twice
     * per display frame — up to 1,200 view visits per frame for two cells that only change when
     * Douyin replaces the title bar.
     */
    private fun corners(row: ViewGroup): Pair<View?, View?> {
        val now = SystemClock.uptimeMillis()
        val cachedLeftCorner = cachedLeftCornerRef?.get()
        val cachedRightCorner = cachedRightCornerRef?.get()
        if (cornersRowRef?.get() === row && cornersResolved &&
            cornersRowWidth == row.width &&
            cornersRowChildCount == row.childCount &&
            cachedLeftCorner?.isAttachedToWindow != false &&
            cachedRightCorner?.isAttachedToWindow != false &&
            ((cachedLeftCorner != null && cachedRightCorner != null) ||
                now - cornersAt < CORNER_RESCAN_MS)
        ) {
            return cachedLeftCorner to cachedRightCorner
        }

        val collected = ArrayList<View>(2)
        collect(row, CORNER_CLASS, collected)
        val rowLocation = IntArray(2)
        row.getLocationOnScreen(rowLocation)
        val cornerLocation = IntArray(2)
        var left: View? = null
        var leftCenter = 0f
        var right: View? = null
        var rightCenter = 0f
        collected.forEach { corner ->
            corner.getLocationOnScreen(cornerLocation)
            val center = cornerLocation[0] - rowLocation[0] + corner.width / 2f
            if (center < row.width / 2f) {
                if (left == null || center < leftCenter) {
                    left = corner
                    leftCenter = center
                }
            } else {
                if (right == null || center > rightCenter) {
                    right = corner
                    rightCenter = center
                }
            }
        }
        cornersRowRef = java.lang.ref.WeakReference(row)
        cornersRowWidth = row.width
        cornersRowChildCount = row.childCount
        cornersResolved = true
        cornersAt = now
        cachedLeftCornerRef = left?.let { java.lang.ref.WeakReference(it) }
        cachedRightCornerRef = right?.let { java.lang.ref.WeakReference(it) }
        return left to right
    }

    /**
     * The cell's label TextView and its current label string, cached per cell.
     *
     * Only the TextView lookup and the `toString()` are cached: the alpha and selected flags
     * still come from the live view every frame.
     */
    private fun cellText(view: View): CellText {
        val cached = cellTexts[view]
        if (cached != null) {
            val text = cached.textRef?.get()
            if (text == null || !text.isAttachedToWindow) {
                cellTexts.remove(view)
            } else {
                val source = text.text
                if (source === cached.source) return cached
                return CellText(cached.textRef, source, source?.toString().orEmpty())
                    .also { cellTexts[view] = it }
            }
        }
        val text = firstText(view) ?: return emptyCellText
        val source = text.text
        return CellText(java.lang.ref.WeakReference(text), source, source?.toString().orEmpty())
            .also { cellTexts[view] = it }
    }

    private fun firstText(root: View, depth: Int = 0): TextView? {
        if (root is TextView && root.text?.isNotBlank() == true) return root
        if (root !is ViewGroup || depth >= 3) return null
        for (index in 0 until root.childCount) {
            firstText(root.getChildAt(index), depth + 1)?.let { return it }
        }
        return null
    }

    private fun firstDescendant(root: View, className: String): View? {
        val stack = ArrayDeque<View>()
        stack += root
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 600) {
            val view = stack.removeLast()
            if (view is GlassHostLayout) continue
            if (cachedSimpleName(view.javaClass) == className) return view
            if (view is ViewGroup) {
                for (index in view.childCount - 1 downTo 0) stack += view.getChildAt(index)
            }
        }
        return null
    }

    private fun <T : View> firstDescendantOfType(root: View, type: Class<T>): T? {
        val stack = ArrayDeque<View>()
        stack += root
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 600) {
            val view = stack.removeLast()
            if (view is GlassHostLayout) continue
            if (type.isInstance(view)) type.cast(view)?.let { return it }
            if (view is ViewGroup) {
                for (index in view.childCount - 1 downTo 0) stack += view.getChildAt(index)
            }
        }
        return null
    }

    private fun collect(root: View, className: String, out: MutableList<View>) {
        val stack = ArrayDeque<View>()
        stack += root
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 600) {
            val view = stack.removeLast()
            if (view is GlassHostLayout) continue
            if (cachedSimpleName(view.javaClass) == className) out += view
            if (view is ViewGroup) {
                for (index in view.childCount - 1 downTo 0) stack += view.getChildAt(index)
            }
        }
    }
}
