package io.github.xzdw.dylt.hook.adapters.douyin

import android.view.View
import android.view.ViewGroup
import io.github.xzdw.dylt.hookx.MethodHook
import io.github.xzdw.dylt.hookx.Xposed
import java.util.WeakHashMap

/** Keep an adapter-owned controls margin intact before native remeasurement. */
internal object DouyinControlMarginGuard {
    private val owned = WeakHashMap<View, Int>()

    /**
     * Number of owned views, readable without the map's monitor.
     *
     * `View.requestLayout` is one of the hottest hooks in the process, so the
     * common case — nothing owned at all — must not pay a lock or a map lookup.
     */
    @Volatile private var ownedCount = 0

    private var installed = false

    fun own(view: View, margin: Int) {
        if (!installed) {
            Xposed.hookAllMethods(View::class.java, "requestLayout", object : MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (ownedCount == 0) return
                    val view = p.thisObject as? View ?: return
                    // Checked before the lock: most views carry params that cannot be adjusted.
                    val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                    val margin = synchronized(owned) { owned[view] } ?: return
                    // No recursive requestLayout and no additional traversal.
                    if (params.bottomMargin != margin) {
                        params.bottomMargin = margin
                    }
                }
            })
            installed = true
        }
        synchronized(owned) {
            if (margin > 0) {
                if (owned.put(view, margin) == null) ownedCount = owned.size
            } else if (owned.remove(view) != null) {
                ownedCount = owned.size
            }
        }
    }

    fun release(view: View) {
        synchronized(owned) { if (owned.remove(view) != null) ownedCount = owned.size }
    }
}
