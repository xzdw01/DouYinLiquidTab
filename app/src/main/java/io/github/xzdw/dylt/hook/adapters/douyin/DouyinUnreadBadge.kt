package io.github.xzdw.dylt.hook.adapters.douyin

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.xzdw.dylt.hook.adapters.NavigationBadge
import kotlin.math.min

/**
 * Reads the unread-messages badge out of Douyin's own (hidden) navigation row.
 *
 * The module hides the native row and paints its own labels, so the unread count has to be read
 * from the source views and redrawn on our bar — otherwise the messages entry silently loses it.
 * Ported from the reference project's `NativeMessageBadgeMonitor`: the row's messages cell is
 * scanned for a visible view whose text/description parses as an unread count, preferring text
 * over descriptions and the smallest view over larger ones (the badge is the smallest match), and
 * a small text-less view counts as a plain dot.
 */
internal object DouyinUnreadBadge {
    private const val SAMPLE_INTERVAL_MS = 300L
    private const val MAX_VIEWS = 240
    private const val DOT_MIN_DP = 3f
    private const val DOT_MAX_DP = 14f
    private const val MESSAGE_LABEL = "消息"

    /**
     * The app's messages cell alternates between being findable and not (measured: the badge read
     * flipped 3 / missing on every sample, which the bar then drew as a flicker). A failed read
     * therefore keeps the last known value for this long instead of clearing the badge, and a zero
     * reading only takes effect after this many consecutive successful samples.
     */
    private const val HOLD_MS = 1_500L
    private const val ZERO_STREAK_TO_CLEAR = 3

    /**
     * `12`, `99+`, `未读 12 条`, `12个` …
     *
     * Hoisted out of [parseCount]: it used to compile both patterns on every call, and the caller
     * runs it for every text/description in a 240-view scan — thousands of Regex compilations per
     * second on the UI thread for two constant patterns. The cadence is now 300 ms rather than
     * 100 ms, so the same scan costs a third of what it did.
     */
    private val DIRECT_COUNT = Regex("^(\\d{1,4})(\\+|\\s*(?:条|个|项))?$")

    private val LEADING_COUNT = Regex("^(?:未读[消息\\s]*)?(\\d{1,4})(?:\\+|\\s*条|\\s*个|\\s*项)")

    /**
     * Per-source sampling state.
     *
     * This used to live in the object itself, which made the two hosts share one `lastCell`,
     * one `cached` value and one sample clock. The bottom bar and the top copy pass *different*
     * rows, so `it.parent === row` in [messageCell] could only ever match for one of them: the
     * other re-scanned its whole cell subtree on every sample. Worse, the shared `SAMPLE_INTERVAL_MS`
     * meant whichever host sampled second was handed the *other* bar's badge value for up to 100 ms.
     */
    private class SampleState {
        var lastSampleAt = 0L

        /**
         * Weak, or the map can never release anything.
         *
         * `states` is a WeakHashMap keyed by the row, and its values are these states. A
         * strong reference to a child of that row keeps the row alive through
         * `View.mParent`, which is exactly the value→key path a WeakHashMap cannot break:
         * every row ever sampled would pin its title-bar subtree and Context for the life
         * of the process. A weak reference is enough because the cell is only a shortcut
         * past the scan in [messageCell]; if it is collected the scan just runs again.
         */
        var lastCell: java.lang.ref.WeakReference<View>? = null
        var cached: NavigationBadge? = null
        var lastKnown: NavigationBadge? = null
        var lastKnownAt = 0L
        var zeroStreak = 0
    }

    /** Keyed by row identity; WeakHashMap so a recycled row does not pin its state. */
    private val states = java.util.WeakHashMap<View, SampleState>()

    private val orphanState = SampleState()

    private fun stateFor(row: View?): SampleState =
        if (row == null) orphanState else states.getOrPut(row) { SampleState() }

    /** The value to keep drawing while the source is momentarily unreadable. */
    private fun held(state: SampleState, now: Long): NavigationBadge? {
        val known = state.lastKnown
        return if (known != null && now - state.lastKnownAt <= HOLD_MS) known else null
    }

    private fun commit(state: SampleState, now: Long, value: NavigationBadge?): NavigationBadge? {
        state.lastKnown = value
        state.lastKnownAt = now
        state.cached = value
        return value
    }

    fun read(row: View?): NavigationBadge? {
        val state = stateFor(row)
        val now = android.os.SystemClock.uptimeMillis()
        if (now - state.lastSampleAt < SAMPLE_INTERVAL_MS) return state.cached
        state.lastSampleAt = now
        if (row == null || !row.isAttachedToWindow) {
            state.cached = held(state, now)
            return state.cached
        }
        val cell = messageCell(state, row) ?: run {
            state.cached = held(state, now)
            return state.cached
        }
        val value = readCell(cell)
        if (value == null || !value.visible) {
            state.zeroStreak++
            if (state.zeroStreak < ZERO_STREAK_TO_CLEAR) {
                state.cached = held(state, now)
                return state.cached
            }
            return commit(state, now, value)
        }
        state.zeroStreak = 0
        return commit(state, now, value)
    }

    /** The row child that carries the messages entry, located by its own label. */
    private fun messageCell(state: SampleState, row: View): View? {
        state.lastCell?.get()
            ?.takeIf { it.isAttachedToWindow && it.parent === row }
            ?.let { return it }
        if (row !is ViewGroup) return null
        for (index in 0 until row.childCount) {
            val child = row.getChildAt(index) ?: continue
            if (!child.isShown || child.width <= 0 || child.height <= 0) continue
            if (collectText(child, MESSAGE_LABEL)) {
                state.lastCell = java.lang.ref.WeakReference(child)
                return child
            }
        }
        return null
    }

    private fun readCell(cell: View): NavigationBadge? {
        var bestCount: Int? = null
        var bestRank = Int.MAX_VALUE
        var dot = false
        val dotLimit = (DOT_MAX_DP * cell.resources.displayMetrics.density).toInt()
        val dotMin = (DOT_MIN_DP * cell.resources.displayMetrics.density).toInt()
        var visited = 0
        val stack = ArrayDeque<View>()
        stack += cell
        while (stack.isNotEmpty() && visited++ < MAX_VIEWS) {
            val node = stack.removeLast()
            if (node.visibility != View.VISIBLE || node.width <= 0 || node.height <= 0) continue
            val text = (node as? TextView)?.text?.toString()?.trim()
            val description = node.contentDescription?.toString()?.trim()
            val area = min(node.width * node.height, 1_000_000)
            // Two direct branches instead of `listOf(text to 0, description to 1)`: that built
            // an ArrayList plus two Pairs for every visited node — ~2,400 allocations/second
            // for a two-element decision. Text still outranks description, as before.
            if (!text.isNullOrEmpty()) {
                parseCount(text)?.let { count ->
                    if (area < bestRank) {
                        bestRank = area
                        bestCount = count
                    }
                }
            }
            if (description != null && description.isNotEmpty()) {
                parseCount(description)?.let { count ->
                    val rank = 1_000_000 + area
                    if (rank < bestRank) {
                        bestRank = rank
                        bestCount = count
                    }
                }
            }
            // A small, text-less view is the app's plain dot indicator.
            if (node !is TextView && text.isNullOrEmpty() && description.isNullOrEmpty() &&
                node.width in dotMin..dotLimit && node.height in dotMin..dotLimit
            ) {
                dot = true
            }
            if (node is ViewGroup) {
                for (index in 0 until node.childCount) {
                    stack += node.getChildAt(index)
                }
            }
        }
        val count = bestCount ?: 0
        return if (count <= 0 && !dot) NavigationBadge.None else NavigationBadge(count, dot)
    }

    private fun collectText(view: View, needle: String): Boolean {
        (view as? TextView)?.text?.toString()?.let { if (it.contains(needle)) return true }
        view.contentDescription?.toString()?.let { if (it.contains(needle)) return true }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (collectText(view.getChildAt(index), needle)) return true
            }
        }
        return false
    }

    /** `12`, `99+`, `未读 12 条`, `12个` … */
    private fun parseCount(raw: String): Int? {
        val match = DIRECT_COUNT.find(raw) ?: LEADING_COUNT.find(raw) ?: return null
        val parsed = match.groupValues[1].toIntOrNull() ?: return null
        val count = if (match.groupValues[2] == "+") parsed + 1 else parsed
        return if (count in 1..9_999) count else null
    }
}
