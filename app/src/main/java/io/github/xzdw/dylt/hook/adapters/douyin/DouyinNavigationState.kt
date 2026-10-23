package io.github.xzdw.dylt.hook.adapters.douyin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.xzdw.dylt.hook.adapters.AdapterNavigationFrame
import io.github.xzdw.dylt.hook.adapters.AppNavigationState
import io.github.xzdw.dylt.hook.adapters.AppVideoBackdrop
import io.github.xzdw.dylt.hookx.Xposed
import kotlin.math.roundToInt

// How the native row is judged, shared by the page gate in this file and the top-level
// `douyinNativeBarOffScreen` both gates read.
//
// These three live at file level rather than in the class companion on purpose: a
// `companion object` member that is not `internal`/`public` is invisible to a top-level
// function, so the shared judgement could not have used them.

/** Rows are shallow; the walk only has to reach the window root. */
private const val NATIVE_BAR_ANCESTOR_SCAN = 24

/**
 * Only the bar's own containers are tested for a fade. The reference monitor
 * tests exactly the row's parent (`alpha > 0.05`); the extra levels cover
 * Douyin's `root_view` -> shell -> chrome stack without letting an unrelated
 * deep container's alpha hide a bar that is still on screen.
 */
private const val NATIVE_BAR_FADE_SCAN = 4

/** Below this an ancestor counts as faded out. */
private const val NATIVE_BAR_MIN_ALPHA = 0.05f

/**
 * How long the video texture has to go without a new frame before the feed counts as
 * static.
 *
 * This is a debounce, not a detection threshold: while video plays normally the
 * timestamp advances every frame, and a gap of one or two frames is ordinary scheduling
 * noise. Without this the pump would stutter on and off mid-playback.
 */
private const val VIDEO_STATIC_HOLD_MS = 250L

/**
 * Reuse bookkeeping for the indicator's combined backdrop scene.
 *
 * That scene paints the hidden native tab row (from a captured snapshot) into a live backdrop
 * node, and it is a full panel-sized display list — `width + 2 * padding` square. Nothing in it
 * changes unless the row's own layout or state does, which is precisely what the host's
 * `navigationLayoutSignature` hashes (per-view identity, geometry, visibility, alpha, text
 * colour and size). Re-recording it on every frame was therefore pure waste; on the feed this
 * is the largest single node-recording cost, five records per frame.
 *
 * The backdrop/navigation bitmaps are compared by identity: the host hands over the same
 * instances until it actually captures a new one, and a new capture also moves the signature.
 */
internal class DouyinCombinedSceneState {
    private var signature = Long.MIN_VALUE
    private var width = -1
    private var height = -1
    private var dark = false
    private var backdrop: Bitmap? = null
    private var navigation: Bitmap? = null
    private var recordedAt = 0L

    fun needsRecord(
        backdrop: Bitmap?, navigation: Bitmap?, signature: Long,
        width: Int, height: Int, dark: Boolean, hasDisplayList: Boolean,
        maxAgeMs: Long = 0L,
    ): Boolean = !hasDisplayList ||
        this.signature != signature ||
        this.width != width ||
        this.height != height ||
        this.dark != dark ||
        this.backdrop !== backdrop ||
        this.navigation !== navigation ||
        (maxAgeMs > 0L && SystemClock.uptimeMillis() - recordedAt >= maxAgeMs)

    fun recorded(
        backdrop: Bitmap?, navigation: Bitmap?, signature: Long,
        width: Int, height: Int, dark: Boolean,
    ) {
        this.signature = signature
        this.width = width
        this.height = height
        this.dark = dark
        this.backdrop = backdrop
        this.navigation = navigation
        this.recordedAt = SystemClock.uptimeMillis()
    }

    /** Forces the next [needsRecord] call to report a change (e.g. after a tab switch). */
    fun invalidate() {
        signature = Long.MIN_VALUE
    }
}

/**
 * Per-host detector for "the feed's video has stopped producing frames".
 *
 * Shared by the bottom bar's [DouyinNavigationState] and the top copy's [DouyinTopBarState],
 * each holding its own instance: the two hosts pump independently and each must notice the
 * transition on its own.
 *
 * `SurfaceTexture.getTimestamp()` advances only when the producer calls `updateTexImage()`,
 * so comparing it across frames answers "is video still playing?" without reading a single
 * pixel back from the GPU — which is exactly what makes it affordable to skip repaints. The
 * glass only needs to redraw every display frame *because* the video underneath keeps
 * changing; when it stops, that cost buys nothing.
 */
internal class DouyinStaticSourceDetector {
    private var lastStamp = Long.MIN_VALUE
    private var changedAt = 0L

    /** True when no new frame has arrived for [VIDEO_STATIC_HOLD_MS]. */
    fun isStatic(now: Long = SystemClock.uptimeMillis()): Boolean {
        // No registered video texture at all (photo post, static page): nothing to wait for.
        val stamp = DouyinPlayerTextureHooks.currentFrameStamp() ?: return true
        if (stamp != lastStamp) {
            lastStamp = stamp
            changedAt = now
            return false
        }
        return now - changedAt >= VIDEO_STATIC_HOLD_MS
    }
}

/** All per-host Douyin navigation and page state lives with the Douyin adapter. */
internal class DouyinNavigationState(private val context: Context) : AppNavigationState {
    private companion object {
        /**
         * How long the native row has to stay off-screen before the glass bar follows
         * it. Matches the reference monitor's 100 ms sampling order of magnitude while
         * absorbing single-frame layout noise.
         */
        const val NATIVE_BAR_ABSENT_HOLD_MS = 150L

        /**
         * Slot 2 of Douyin's native five-cell row is the publish action
         * (see `DouyinAdapter.actionOnlyIndices`).
         */
        const val PUBLISH_CELL_INDEX = 2

        /** Press-to-release gap of the synthesized tap used when `performClick` is ignored. */
        const val PUBLISH_TAP_MS = 48L
    }

    /** Scratch rect for the per-frame native-row presence probe; never retained. */
    private val nativeBarVisibleRect = Rect()
    private var nativeBarAbsentSince = 0L
    private var nativeBarAbsentConfirmed = false

    /** Millisecond stamp of the last native-row probe, so one frame probes once. */
    private var nativeBarProbeAt = -1L
    private var nativeBarProbePresent = false

    private val renderer = DouyinNavigationRenderer(context)
    private val pageController = DouyinPageController(context)
    private val videoSampler = DouyinVideoSampler(context)
    private var outerRecordedVideoEpoch = Int.MIN_VALUE
    private var outerRecordedBackdrop: Bitmap? = null
    private var outerRecordedWidth = 0
    private var outerRecordedHeight = 0
    private var outerRecordedSamplingBlocked = false
    override val blocksBackdropSampling: Boolean get() = pageController.shopPageActive

    // The shared host retains its outer RenderNode while the live view scene is
    // active. Douyin's PixelCopy bitmap is painted into that node by value, so
    // a new video frame must invalidate the node even though the view tree and
    // the host's ordinary backdrop bitmap have not changed. The indicator
    // records every frame independently; without this policy only it moves.
    /**
     * Whether the feed's video has stopped producing frames.
     *
     * Delegates to [DouyinStaticSourceDetector]; see there for why a SurfaceTexture
     * timestamp is the right signal. This exists so the glass can stop repainting when the
     * video underneath is not changing — at 165Hz that pump is the largest cost the module
     * adds, and on a paused video or a photo post it buys nothing.
     */
    private val staticSourceDetector = DouyinStaticSourceDetector()

    /** Selection and ink state the retained combined scene was last recorded for. */
    private var sceneSelectedIndex = Int.MIN_VALUE
    private var sceneInkInLens = false

    override fun videoSourceIsStatic(host: View): Boolean? =
        if (videoSampler.isBarStripStatic()) true else staticSourceDetector.isStatic()

    override fun barStripStatic(host: View): Boolean = videoSampler.isBarStripStatic()

    override fun videoFrameStamp(host: View): Long? = DouyinPlayerTextureHooks.currentFrameStamp()

    // Deliberately NOT reusing the combined indicator scene.
    //
    // Reuse looked like free money — the scene is a full panel-sized display list and its
    // signature covers geometry, visibility, alpha, drawableState, isSelected, text colour
    // and size. But it does NOT cover everything the native row draws: an icon that changes
    // by swapping its drawable, or by colouring it through a ColorFilter, leaves the
    // signature untouched. Douyin's tab icons work exactly that way, so reuse froze them and
    // the bar's icons visibly lagged behind the app (reported on device: "底栏的图标刷新变迟钝").
    //
    // The saving was real but small (nodeRec 280 -> 235/s); correctness wins.
    private val combinedSceneState = DouyinCombinedSceneState()

    // The resting capsule's lens scene only changes when the video frame, the navigation
    // snapshot or an icon inside the row changes. While the finger is down the ink is drawn
    // into the lens and the capsule moves, so those frames keep recording; at rest the
    // retained scene is reused and only a selection change (or a new video frame / snapshot,
    // which the host's own signature already covers) forces a fresh record.
    override fun reuseCombinedIndicatorScene(surfaceActive: Boolean): Boolean = !sceneInkInLens

    override fun combinedIndicatorSceneNeedsRecord(
        backdrop: Bitmap?, navigation: Bitmap?, signature: Long,
        width: Int, height: Int, dark: Boolean, hasDisplayList: Boolean,
    ): Boolean = combinedSceneState.needsRecord(
        backdrop, navigation, signature, width, height, dark, hasDisplayList,
    )

    override fun onCombinedIndicatorSceneRecorded(
        backdrop: Bitmap?, navigation: Bitmap?, signature: Long,
        width: Int, height: Int, dark: Boolean,
    ) = combinedSceneState.recorded(backdrop, navigation, signature, width, height, dark)

    override fun outerBackdropNeedsRecord(
        backdrop: Bitmap?, width: Int, height: Int, hasDisplayList: Boolean,
    ): Boolean = !hasDisplayList ||
        outerRecordedVideoEpoch != (videoSampler.snapshot()?.epoch ?: -1) ||
        outerRecordedBackdrop !== backdrop ||
        outerRecordedWidth != width || outerRecordedHeight != height ||
        outerRecordedSamplingBlocked != blocksBackdropSampling

    override fun onOuterBackdropRecorded(backdrop: Bitmap?, width: Int, height: Int) {
        outerRecordedVideoEpoch = videoSampler.snapshot()?.epoch ?: -1
        outerRecordedBackdrop = backdrop
        outerRecordedWidth = width
        outerRecordedHeight = height
        outerRecordedSamplingBlocked = blocksBackdropSampling
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        // The scene was recorded earlier in this same dispatch pass, so a selection change
        // invalidates it for the next one (one frame at most). At rest nothing inside the
        // recorded scene depends on the selection -- the row itself is painted in the
        // foreground -- so this is the only extra signal the reuse contract needs.
        if (frame.selectedIndex != sceneSelectedIndex) {
            sceneSelectedIndex = frame.selectedIndex
            combinedSceneState.invalidate()
        }
        sceneInkInLens = frame.selectionInkInLensScene
        renderer.draw(canvas, frame)
    }
    override fun requestVideoBackdrop(host: View, pageAllowed: Boolean) {
        if (videoSampler.isBarStripStatic()) return
        videoSampler.request(host, pageAllowed, blocksBackdropSampling)
    }
    override fun videoBackdrop(): AppVideoBackdrop? = videoSampler.snapshot()
    override fun pageAllowsNavigation(root: View?): Boolean = true

    /**
     * A tap on the detached publish circle has to reach Douyin's own publish cell: the module
     * draws that action outside the pill and hides the native row, so the default no-op left the
     * + button dead. The row itself is the navigation source; slot 2 is the publish action.
     */
    override fun performActionTap(host: View, source: ViewGroup?, leading: Boolean): Boolean {
        if (leading) return false
        val row = source ?: return false
        val cell = publishCell(row) ?: return false
        if (runCatching { cell.performClick() }.getOrDefault(false)) return true
        if (!cell.isShown || cell.width <= 0 || cell.height <= 0) return false
        val now = SystemClock.uptimeMillis()
        val x = cell.width / 2f
        val y = cell.height / 2f
        listOf(
            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0),
            MotionEvent.obtain(now, now + PUBLISH_TAP_MS, MotionEvent.ACTION_UP, x, y, 0),
        ).forEach { event ->
            runCatching { cell.dispatchTouchEvent(event) }
            event.recycle()
        }
        return true
    }

    /** The native publish cell: slot 2 of the row, else the first cell labelled 发布/拍摄. */
    private fun publishCell(row: ViewGroup): View? {
        row.getChildAt(PUBLISH_CELL_INDEX)?.takeIf { it.isShown }?.let { return it }
        for (index in 0 until row.childCount) {
            val child = row.getChildAt(index) ?: continue
            if (child.isShown && (containsLabel(child, "发布") || containsLabel(child, "拍摄"))) {
                return child
            }
        }
        return null
    }

    private fun containsLabel(view: View, needle: String): Boolean {
        (view as? android.widget.TextView)?.text?.toString()?.let {
            if (it.contains(needle)) return true
        }
        view.contentDescription?.toString()?.let { if (it.contains(needle)) return true }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsLabel(view.getChildAt(index), needle)) return true
            }
        }
        return false
    }

    override fun drawForeground(
        canvas: Canvas,
        frame: AdapterNavigationFrame,
        selectionPath: Path,
        drawSource: () -> Unit,
    ): Boolean {
        renderer.drawForeground(canvas, frame, selectionPath, drawSource)
        return true
    }

    /**
     * The selection ink, drawn in whichever pass owns it: inside the capsule's lens scene while the
     * finger is down (refracted and dispersed like the backdrop), over the glass at rest (crisp, and
     * out of reach of the capsule's edge refraction band).
     */
    override fun drawSelectionInk(canvas: Canvas, frame: AdapterNavigationFrame, inLensScene: Boolean) {
        renderer.drawSelectionInk(canvas, frame, inLensScene)
    }

    override fun onHostPreDraw(
        host: View,
        source: ViewGroup?,
        selected: () -> Int,
        enabled: Boolean,
        
        density: Float,
        barHeightPx: Int,
        surfaceColor: () -> Int,
    ) {
        pageController.update(host, barHeightPx)
    }

    /**
     * The liquid bar belongs to the main shell only.
     *
     * Douyin's own tab row is the page signal, not its activity or any page id: on the
     * author profile (发布者主页) the shell activity stays resumed and the row keeps its
     * VISIBLE flag, its attachment, its `isShown`, its alpha and its 1272x181 bounds —
     * Douyin simply slides the whole row a screen width to the left
     * (`getLocationOnScreen` = -1273, `getGlobalVisibleRect` = none). The row is the
     * shared host's `sourceReady` anchor, and that test only reads visibility flags, so
     * it still reported "ready" and the bar stayed on screen.
     *
     * The gate therefore mirrors the native row's *rendered* presence
     * ([nativeBottomBarOnScreen]) — the mechanism the verified Douyin-Liquid-Glass
     * module implements in its `NativeBottomBarStateMonitor`, measured through
     * `getGlobalVisibleRect` and read inline from the pre-draw instead of from a
     * polling handler. Measured on the device (Android 17 / Douyin 40.7.x):
     * feed `loc=-1,2590 visible=1271x182` (bar shown); author profile
     * `loc=-1273,2582 visible=none` (bar hidden); back on the feed it returns to
     * `visible=1264x175`, so both directions recover from the same live measurement.
     *
     * Returning null (no activity yet) leaves the previous default in place.
     */
    override fun hostPageAllowsNavigation(
        host: io.github.xzdw.dylt.hook.GlassHostLayout,
        root: View?,
    ): Boolean? {
        // There used to be a gate here requiring the foreground activity to be named
        // MainActivity. That was wrong: Douyin's shell activity is SplashActivity
        // (MainActivity is just one of several), so the bar hid on the feed too and never
        // came back. A `cover`-id page probe replaced it and failed the same test in the
        // other direction: Douyin keeps the profile page attached and `isShown` after
        // returning to the feed, so the marker stayed true and the bar stayed hidden.
        // [douyinOverlayActivityOnTop] was measured and rejected: Douyin's shell/host
        // activity pairing is unstable (see the note further down the file), and the row
        // measurement plus the bottom input-surface test cover every page that was
        // reproduced on the device.
        if (root == null) return null
        // Chat / comment surfaces: Douyin keeps its own tab row fully on screen and opaque
        // underneath them (measured in the friend chat: `loc=-5,2591 visible=1267x173`,
        // every ancestor at alpha 1.0), so the rendered-presence test cannot see the page
        // and the glass bar sat on the chat's input row. The page controller spots the
        // visible bottom input surface during the walk it already performs.
        if (pageController.bottomInputVisible) return false
        host.navigationSource?.let { source ->
            if (!nativeBottomBarOnScreen(source)) return false
        }
        return true
    }

    /**
     * Rejected: an "a different activity is on top" gate (foreground activity compared
     * against the activity that owns this host). It was implemented and measured, and
     * Douyin's activity pairing is too unstable for it: while the friend chat was on screen
     * the host's own context resolved to `MainActivity`, the window and the resumed activity
     * were `SplashActivity`, and the foreground reading was briefly a *finishing*
     * `UserProfileActivity` — so the rule silently reported "no overlay" and, on other
     * pages, would have hidden the bar on the feed. The verified reference module uses a
     * bottom input-surface test for exactly these pages, which is what
     * [DouyinPageController.bottomInputVisible] ports.
     */

    /**
     * Whether Douyin still draws any part of the bound tab row where a bar belongs.
     *
     * The row is sampled every pre-draw, which is far finer than the reference
     * monitor's 100 ms sampling, so a single bad frame (a tab transition, a re-layout
     * of the bar container) must not blink the glass bar away: the absence has to
     * persist for [NATIVE_BAR_ABSENT_HOLD_MS] before it counts. Reappearance is
     * immediate, exactly like the reference.
     */
    private fun nativeBottomBarOnScreen(source: View): Boolean {
        val present = nativeBottomBarPresent(source)
        if (present) {
            nativeBarAbsentSince = 0L
            nativeBarAbsentConfirmed = false
            return true
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (nativeBarAbsentSince == 0L) nativeBarAbsentSince = now
        if (!nativeBarAbsentConfirmed && now - nativeBarAbsentSince >= NATIVE_BAR_ABSENT_HOLD_MS) {
            nativeBarAbsentConfirmed = true
        }
        return !nativeBarAbsentConfirmed
    }

    /**
     * Measured presence of the native bottom bar, ported from the verified
     * Douyin-Liquid-Glass `NativeBottomBarStateMonitor.sample()`:
     *
     * - the row itself must be attached, VISIBLE and non-empty;
     * - every ancestor up to the window must still be attached, VISIBLE and non-empty,
     *   and the bar's own containers ([NATIVE_BAR_FADE_SCAN] levels) must not be faded
     *   out (a container fade is invisible to every geometry test);
     * - a few pixels of the row must be inside the window. `getGlobalVisibleRect`
     *   folds in every ancestor's translation and clip, which is what catches the
     *   common Douyin hide — the bar container slides below the bottom edge while
     *   every visibility flag, `isShown` and bound stays intact.
     *
     * The row's own alpha is deliberately not tested: the module itself zeroes it to
     * suppress the native artwork, so only its ancestors can report a fade.
     */
    private fun nativeBottomBarPresent(source: View): Boolean {
        // One probe per millisecond: the two pre-draw listeners (the page gate and the capture
        // loop) plus the page controller can all ask inside the same frame. The shared helper
        // already performs every check this function used to repeat — attachment, visibility,
        // size, the ancestor chain and the rendered rect — so the old duplicate walk is gone.
        val now = android.os.SystemClock.uptimeMillis()
        if (now == nativeBarProbeAt) return nativeBarProbePresent
        nativeBarProbeAt = now
        nativeBarProbePresent = !douyinNativeBarOffScreen(source, nativeBarVisibleRect)
        return nativeBarProbePresent
    }

    override fun onHostDetached() {
        nativeBarAbsentSince = 0L
        nativeBarAbsentConfirmed = false
        nativeBarProbeAt = -1L
        nativeBarProbePresent = false
        pageController.dispose()
        videoSampler.dispose()
    }
}

/**
 * Share of the native tab row that has to stay inside the window for Douyin to count as
 * "still showing its bar".
 *
 * A few-pixel threshold is not enough: a page transition can park the whole tab host just
 * outside the window and leave a sliver of the 1272px row peeking in — measured at
 * `loc=-1251` while opening a friend chat, i.e. 22px / 1.7% — which a pixel test reads as
 * "bar present" and keeps the glass bar on a page that has none. The feed shows ~99% of
 * the row.
 */
internal const val DOUYIN_NATIVE_BAR_MIN_VISIBLE_FRACTION = 0.25f

/**
 * Whether Douyin has taken its own bottom bar off screen, judged by the rendered rect.
 *
 * Every flag on the row stays intact while Douyin pages the tab host away (see
 * [DouyinNavigationState.nativeBottomBarPresent]), so this is the only signal that follows
 * the bar. Shared by the page gate and the page controller's lift release so both agree on
 * the same measurement.
 */
internal fun douyinNativeBarOffScreen(source: View, scratch: Rect): Boolean {
    if (!source.isAttachedToWindow) return true
    if (source.visibility != View.VISIBLE) return true
    if (source.width <= 0 || source.height <= 0) return true
    // The geometry test alone is not enough, and sharing only that part with the page gate is
    // what let the two callers disagree about the same measurement. While the pager moves the
    // row's visible rect dips under the threshold for a moment even though the row, and every
    // container above it, is still visible and unfaded — that is the bar the module is showing.
    // The lift release read only the rect, so it dropped the caption / progress / action cluster
    // below a bar that was still on screen (measured on the device: `released` every ~1.1s with
    // `barState present=true` in between). Walk the ancestors exactly like the page gate does.
    var ancestor = source.parent as? View
    var depth = 0
    while (ancestor != null && depth < NATIVE_BAR_ANCESTOR_SCAN) {
        if (!ancestor.isAttachedToWindow) return true
        if (ancestor.visibility != View.VISIBLE) return true
        if (ancestor.width <= 0 || ancestor.height <= 0) return true
        if (depth < NATIVE_BAR_FADE_SCAN && ancestor.alpha < NATIVE_BAR_MIN_ALPHA) return true
        ancestor = ancestor.parent as? View
        depth++
    }
    // Douyin's hide is the container sliding below the window edge while every visibility flag,
    // isShown and bound stays intact, so the rendered rect stays the signal that follows it.
    if (!source.getGlobalVisibleRect(scratch)) return true
    val minWidth = (source.width * DOUYIN_NATIVE_BAR_MIN_VISIBLE_FRACTION).roundToInt()
    val minHeight = (source.height * DOUYIN_NATIVE_BAR_MIN_VISIBLE_FRACTION).roundToInt()
    return scratch.width() < minWidth || scratch.height() < minHeight
}
