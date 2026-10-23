package io.github.xzdw.dylt.hook.adapters.douyin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.SeekBar
import io.github.xzdw.dylt.hook.GlassInstaller
import io.github.xzdw.dylt.hook.GlassHostLayout
import io.github.xzdw.dylt.hook.globalGlassBottomGapPx
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Douyin-only page geometry and control avoidance. */
internal class DouyinPageController(private val context: Context) {
    private val feedCornerMasks = DouyinFeedCornerMasks()
    // Classification only: refreshed every layout-maintenance pass so newly
    // attached players and page changes never inherit an old negative result.
    private val videoContainerProbe = java.util.IdentityHashMap<View, Boolean>()
    /** container -> [maxCompact, count]; see [douyinBottomCompactChildCount]. */
    private val compactChildProbe = java.util.IdentityHashMap<View, IntArray>()
    /** Timestamp of the last probe-cache expiry. */
    private var containerProbeAt = 0L
    private var lastNativeRowKey: String? = null
    private val density = context.resources.displayMetrics.density
    private var currentRoot: ViewGroup? = null
    private var currentBarHeightPx = 0

    /**
     * 底栏下边距 in px, refreshed every layout pass from the host's config.
     *
     * The host itself is placed by `glassHostBottomMarginPx`, but the page's control lift is
     * computed here from the shared gap, so the same offset has to be folded into the caption /
     * seek-bar targets — otherwise raising the bar leaves the page's own controls behind it.
     * 0 is the shipped geometry.
     */
    private var bottomMarginOffsetPx = 0
    private var douyinLastLayoutAdjustAt = 0L
    /** Throttles the expensive comment-sheet geometry walk, independently of the rest. */
    private var lastCommentGapCheckAt = 0L

    private var douyinFeedPager: WeakReference<View>? = null

    private var douyinBottomSpace: WeakReference<View>? = null
    private var douyinShopBackToTopRef: WeakReference<View>? = null
    private var douyinShopBackToTopBaseTranslation = 0f
    private var douyinShopBackToTopLastSearchAt = 0L
    private var douyinShopPageActive = false
    val shopPageActive: Boolean get() = douyinShopPageActive

    /**
     * Last time a visible text input in the lower half of the window was seen (0 = none). Valid for
     * [DOUYIN_BOTTOM_INPUT_TTL_MS], so leaving the chat page releases the bar promptly even
     * though the check only runs on the layout pass.
     */
    private var douyinBottomInputSeenAt = 0L

    /**
     * 控件下移 toggle state, refreshed every layout pass. False (the shipped default)
     * reproduces the pre-fix lift rule: the whole cluster is lifted by its container edge,
     * which sits on the window bottom even though the widgets end ~178px higher.
     */
    private var controlDropEnabled = false

    /**
     * 顶栏液态玻璃 switch, refreshed every pass. Same condition `GlassInstaller` installs the top
     * copy under. Together with [nativeTitleRowAlpha] it gates the page top-reserve release; see
     * [topBarOwnsTitleRow].
     */
    private var topBarGlassActive = false
    private var bottomBarGlassActive = false

    /**
     * Alpha of Douyin's own title bar row as the last walk saw it, -1 when no row was visited in
     * this pass. [DouyinTopBarFinder.hideNativeTitleBar] blanks the row to alpha 0 exactly while
     * the top copy draws it, which is the other half of [topBarOwnsTitleRow].
     */
    private var nativeTitleRowAlpha = -1f

    /**
     * Height of that row and its own top edge in window coordinates, both refreshed with the alpha
     * above (0 when the pass saw no row). They are what [releaseDouyinPageTopReserve] measures the
     * page's reserve against, so the search never has to guess a dp value.
     */
    private var nativeTitleRowHeightPx = 0
    private var nativeTitleRowTopPx = 0

    /** Page-count signature of the last controls-root discovery, and when it last ran. */
    private var lastPageSignature = -1
    private var lastDiscoverAt = 0L

    
    val bottomInputVisible: Boolean
        get() = douyinBottomInputSeenAt != 0L &&
            SystemClock.uptimeMillis() - douyinBottomInputSeenAt <= DOUYIN_BOTTOM_INPUT_TTL_MS

    /**
     * Same sighting, but held much longer than the display latch.
     *
     * A bottom sheet (comments, chat) also makes Douyin slide its *own* bar away, which the
     * lift release would read as "pushed page, the lifts are pointless". Releasing there
     * drops the caption/action cluster to its native position and lifting it again once the
     * sheet closes is the visible drop-and-bounce-back. Keep the lifts while a sheet has
     * been on screen recently; only a genuine pushed page (profile, video detail, live
     * preview) has no bottom input at all.
     */
    val bottomInputRecentlyVisible: Boolean
        get() = douyinBottomInputSeenAt != 0L &&
            SystemClock.uptimeMillis() - douyinBottomInputSeenAt <= DOUYIN_BOTTOM_INPUT_RELEASE_GUARD_MS
    private var douyinSpecialPageLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var douyinCouponPageRef: WeakReference<View>? = null
    private var douyinLocationCommentRef: WeakReference<ViewGroup>? = null
    private var douyinGroupBuyBackToTopRef: WeakReference<View>? = null
    private var douyinGroupBuyBackToTopBaseTranslation = 0f
    private var douyinGroupBuyClipParentRef: WeakReference<ViewGroup>? = null
    private var douyinGroupBuyClipParentBottomPadding = 0
    private var douyinGroupBuyClipParentClipToPadding = true

    /**
     * Reused walk stack for the per-pass layout scan (see the zero-allocation note there). Each
     * entry is visited once, so a capacity equal to the walk's own visit cap can never overflow.
     */
    private val douyinWalkViews = arrayOfNulls<View>(DOUYIN_WALK_STACK_CAPACITY)
    private val douyinWalkDepths = IntArray(DOUYIN_WALK_STACK_CAPACITY)

    /**
     * What the current pass found for the two top-reserve releases, recorded where the walk already
     * stands on every view. [releaseDouyinPageTopReserve] used to re-walk the page subtree for its
     * marker on every call — a second and third copy of the same search `douyinIsC2FeedPageContainer`
     * and [releaseDouyinPageNativeReserve] each run — so the walk now collects the markers once and
     * the release only has to test containment. Both lists are cleared at the top of every pass; the
     * entries are page views Douyin owns, referenced no longer than the pass that recorded them.
     */
    private val douyinTopReserveMarkers = ArrayList<View>()
    private val douyinFeaturedFeedRoots = ArrayList<View>()

    private var douyinCachedProgressRef: WeakReference<View>? = null

    private var douyinCachedChipRef: WeakReference<View>? = null

    /**
     * Weak keys for both caches. They only describe live pages and the margins currently imposed
     * on them, so a page Douyin has recycled must not be kept alive by this bookkeeping — a missed
     * release path used to pin the page together with everything it references. View identity is
     * preserved (View does not override equals) and both maps are only touched from the UI-thread
     * pass, so nothing changes for a live page.
     */
    private val douyinMarginTargets = WeakHashMap<View, Int>()

    /**
     * Last window-relative top seen for each view of a page's bottom-fill chain.
     *
     * This is a *previous reading*, not a permanent baseline. The page's top really does move:
     * measured on 40.7.0, the message page's story row (`bjp`, 312px) is present right after the
     * page opens and gone once it settles, which moves the conversation list from 584 to 272. A
     * baseline recorded on first sight then rejected every later pass ("top=272 base=584") and
     * the page never filled. Comparing with the previous pass instead still suppresses the
     * pull-to-refresh drag (a downward move) while an upward settle is applied at once.
     */
    private val douyinPageChainTops = java.util.WeakHashMap<View, Int>()

    /**
     * The message page's header (`tv_title` = 消息) and conversation list, plus the full-width
     * pager that hosts a page's own content (the friends page's video pager), all published by
     * the update pass' walk. The adjusters consume them without traversing the tree again; the
     * posted retry also runs without a walk, which is what keeps it cheap on an idle page.
     */
    private var douyinMessageTitleRef: WeakReference<View>? = null
    private var douyinMessageListRef: WeakReference<View>? = null
    private var douyinBottomPagerRef: WeakReference<View>? = null

    /** Scratch for [douyinTopInWindow]; UI thread only, read immediately after the call. */
    private val douyinScratchLocation = IntArray(2)

    /**
     * Reused coverage of a page's bottom-fill chain (view + its window top). Rebuilt every pass
     * and capped by [DOUYIN_PAGE_CHAIN_MAX_DEPTH]; keeping it out of the heap is the same reason
     * the main walk reuses arrays instead of `Pair` objects.
     */
    private val douyinPageChainViews = ArrayList<View>(DOUYIN_PAGE_CHAIN_MAX_DEPTH)
    private val douyinPageChainTopValues = IntArray(DOUYIN_PAGE_CHAIN_MAX_DEPTH)

    /** One pending [scheduleDouyinPageRetry] at a time, and how many were used this attempt. */
    private var douyinPageRetryPending = false
    private var douyinPageRetryCount = 0

    private val douyinExpandedPagers = HashSet<Int>()
    private val douyinPageControlsCache = WeakHashMap<View, View>()

    /**
     * `page -> (candidate, validatedAt)` for a candidate that already passed the full structural
     * guard chain (not progress, no video surface, not the feed frame, no full-width photo, no
     * group-buy content).
     *
     * Every one of those guards is a full subtree walk, so re-running them on each pass cost
     * `discover` 2.3ms mean and 5ms peak on device even when [douyinPageControlsCache] hit — that
     * is what made the comment-sheet scroll stutter. The chain is re-run after the TTL instead,
     * which is the same trade the other page-level fixes use.
     */
    private val douyinValidatedControls = WeakHashMap<View, Pair<View, Long>>()
    private val douyinAdvertisementCache = java.util.WeakHashMap<View, Pair<Long, Boolean>>()
    // Page-level lift classifications are stable for at least a few frames but each one is a
    // 320–400-view subtree walk, and the lift target loop asks for all of them on every pass.
    // A short TTL removes those repeat walks without letting a recycled page keep a stale answer.
    private val douyinFullWidthPhotoCache = java.util.WeakHashMap<View, Pair<Long, Boolean>>()
    private val douyinGroupBuyContentCache = java.util.WeakHashMap<View, Pair<Long, Boolean>>()
    private val douyinGroupBuyPageCache = java.util.WeakHashMap<View, Pair<Long, Boolean>>()
    private val douyinC2FeedPageCache = java.util.WeakHashMap<View, Pair<Long, Boolean>>()
    private val douyinC2SearchHeaders =
        java.util.WeakHashMap<View, java.lang.ref.WeakReference<View>>()
    private val douyinC2HeaderHeights = java.util.WeakHashMap<View, Int>()
    private val douyinC2BottomReserve = java.util.WeakHashMap<View, Int>()
    private val targetClassifyTtlMs = 1_000L


    private var lastDouyinDiagFileAt = 0L

    fun update(host: View, barHeightPx: Int) {
        currentBarHeightPx = barHeightPx
        val hostConfig = (host as? GlassHostLayout)?.config
        // 底栏下边距: the offset the host reads, in px. It is folded into both control lifts below.
        val bottomMarginOffset = ((hostConfig?.bottomMarginOffsetDp ?: 0f) * density).roundToInt()
        // 控件下移: on = cap the lift by the measured widget edge (fixed), off = lift by the
        // control container's own edge (pre-fix, parks the cluster ~178px higher).
        val controlDrop = hostConfig?.controlDrop == true
        // 顶栏液态玻璃: while this is on the module draws Douyin's title row itself, so the row's
        // space in a page's own layout is a reserve nothing paints into (see
        // [releaseDouyinPageTopReserve]).
        topBarGlassActive = hostConfig?.topBarLiquidGlass == true
        bottomBarGlassActive = hostConfig?.liquidGlassEnabled == true
        if (controlDropEnabled != controlDrop) {
            controlDropEnabled = controlDrop
            // Drop the locked margins so the next pass recomputes with the other rule
            // instead of staying inside its 24dp dead zone, and drop the cached widget
            // insets so the cap is measured afresh for the new rule.
            douyinMarginTargets.keys.forEach(DouyinControlMarginGuard::release)
            douyinMarginTargets.clear()
            visibleInsetCache.clear()
        }
        if (bottomMarginOffsetPx != bottomMarginOffset) {
            bottomMarginOffsetPx = bottomMarginOffset
            // One slider step is below the lift's 24dp dead zone, so a changed offset would keep
            // the old margin and the controls would stay where they were. Drop the records so the
            // next pass recomputes against the bar's new edge.
            douyinMarginTargets.keys.forEach(DouyinControlMarginGuard::release)
            douyinMarginTargets.clear()
        }

        runCatching {

        // The host itself is permanently GONE on Douyin (non-animated hide
        // path), so it is never laid out and its own size stays 0. Gate on the
        // root view instead — the feed geometry must not depend on a view that
        // only ever exists off-screen.
        val root = host.rootView as? ViewGroup ?: return
        currentRoot = root

        if (root.width <= 0 || root.height <= 0) return

        val now = SystemClock.uptimeMillis()

        // A comment sheet (and its 500ms close guard) owns the page while Douyin animates the
        // video between the sheet-sized and full-screen layouts. Every pass here would walk the
        // tree and write margins/height during those exact animation frames; the page behind the
        // sheet is not visible, so skipping the pass entirely is both safe and the cheapest way
        // to keep the shrink/release animation on the display's cadence. The next pass after the
        // guard recomputes from the settled full-screen page.
        val sheetUp = douyinCommentSheetUp()
        if (sheetUp) {
            return
        }
        // The sheet's close animation releases the video back to full screen after the
        // visible-sheet guard has expired. Recomputing page margins in that window re-lays out
        // the very view Douyin is animating, so hold the pass for a short settle window too.
        if (douyinSheetSeenAt != 0L &&
            now - douyinSheetSeenAt < DOUYIN_SHEET_CLOSE_SETTLE_MS
        ) {
            return
        }

        // While a bottom sheet (comments, chat) is on screen Douyin animates the video and its
        // controls every frame, and this pass both walks the tree and writes margins/heights that
        // the animation then has to re-lay-out. Nothing here is visible during the sheet — the
        // module's bar is away and the page sits behind it — so the pass drops to a third of its
        // rate until the sheet closes, which is what the reported stutter in the comment
        // open/close animation is.
        val adjustInterval = if (bottomInputRecentlyVisible) {
            DOUYIN_SHEET_ADJUST_INTERVAL_MS
        } else {
            DOUYIN_LAYOUT_ADJUST_INTERVAL_MS
        }
        if (now - douyinLastLayoutAdjustAt < adjustInterval) return

        douyinLastLayoutAdjustAt = now

        // Douyin may restore its navigation-bar inset while changing feed tabs; keep every
        // liquid-bar page edge-to-edge. This runs once per adjusted pass. It used to sit above
        // the 96 ms gate, so every pre-draw paid a synchronized, reflective window check.
        context.findActivity()?.let(GlassInstaller::ensureNavigationBarImmersion)

        // Douyin's own bar is the gate for every bottom-margin lift below. While the
        // pager shows a pushed page (author profile 发布者主页, video detail, photo flow)
        // Douyin pages the whole tab host — bar included — off screen, so there is
        // nothing for a lifted control to clear. Keeping the lift there is what produced
        // the reported black band: the profile page slot itself was classified as a
        // controls root and carried a 269px lift, shrinking the page to 2503 of a 2772
        // window and leaving the strip showing the window background. Measured against
        // the same page with the module disabled, the page fills the window.
        // A bar that is away for a moment is Douyin *animating* it — while scrolling, and while
        // it slides back in after the comment sheet closes — not a pushed page. Releasing on that
        // transient state zeroed every lift, and because the lifts can only be re-applied once
        // the bar is back, the progress bar / caption / action cluster sat at the window bottom
        // until it returned.
        val nativeBarAway = douyinNativeBarOffScreen(host)
        // A hidden bar is not by itself "the page is gone": an overlay (comment panel, chat,
        // the comment input) hides the bar while the feed page stays behind, and releasing the
        // lifts there is what dropped the caption / action cluster / progress bar to their
        // native position on every panel open — and lifted them again, visibly late, when the
        // panel closed. Gate the release on the *page* being gone instead; the lift sites below
        // stay on `nativeBarAway`, so while an overlay covers the bar the applied margins are
        // simply frozen and nothing moves at all.
        // While the sheet is up — its close animation included — the lifts are left untouched:
        // the page behind it is still laid out for the sheet, and recomputing from that geometry
        // is what moved the cluster on close.
        val feedPageOnScreen = !nativeBarAway || douyinFeedPageOnScreen()

        val barAway = nativeBarAway && !feedPageOnScreen && !bottomInputRecentlyVisible
        // A sheet up means the bar is away because an overlay covers it, not because the page
        // was pushed: `douyinFeedPageOnScreen()` misjudges the page the sheet re-lays out, and
        // releasing there cleared every lift (measured: `release: bar-away cleared 3 lift(s)`
        // every few seconds while the panel was open). Fall through to the overlay branch, which
        // keeps the page lifts pinned and only gives back what the sheet owns.
        if (barAway && !sheetUp) {
            douyinReleaseBottomReservations()
        } else if (nativeBarAway) {
            // An overlay covers the bar while the feed page stays behind it. The page's own
            // lifts are pinned — that is what keeps the caption / action cluster in place — but
            // a lift that landed on the overlay's *own* content has to go back: the comment
            // sheet lays its card out from that margin, and a stale lift there is the empty
            // band above the comment tabs (measured: 334px on `fqc`).
            douyinReleaseOverlayLifts()
        }
        // These probe caches are expensive to fill — every miss is a full-tree walk of up
        // to DOUYIN_LAYOUT_MAX_VIEWS views — and their callers sit inside other walks, so
        // clearing them on every update made the pass O(n^2) on the UI thread. Expire
        // them on a short timer instead: far longer than one frame, still short enough
        // that a structural change (a surface appearing, a page swapping) is noticed
        // promptly.
        if (now - containerProbeAt >= DOUYIN_CONTAINER_PROBE_TTL_MS) {
            containerProbeAt = now
            videoContainerProbe.clear()
            compactChildProbe.clear()
        }
        // Diagnostic: follow the row the host actually bound to, up its ancestor chain.
        // The `root_view` id is reused by unrelated containers (the feed tab bar, the
        // live-preview pager), so findViewById can return the wrong one and report its
        // state — that is exactly how the previous reading was misleading. The bound row
        // is the reliable anchor, and its ancestors show where Douyin hides the bar.

        var bottomSpace = douyinBottomSpace?.get()?.takeIf { it.isAttachedToWindow }

        var pager = douyinFeedPager?.get()?.takeIf { it.isAttachedToWindow }

        // target -> visual anchor used to calculate its original bottom edge.

        // Some interactive children (notably the SeekBar) must not themselves

        // be translated because Douyin rewrites their properties while used.

        val controlsRoots = LinkedHashMap<View, View>()
        val relatedSearchChips = LinkedHashSet<View>()

        // Top edge of the hidden liquid bar area: controls are lifted until

        // their bottom clears this line, releasing the bar's space for the

        // video feed. The host itself is permanently GONE on Douyin, so the

        // clearance is derived from the configured bar height instead.

        val clearanceTop = (root.height - barHeightPx)

            .coerceAtLeast(0)

        // One screen-relative read per pass: it feeds both the cluster lift and the offset floor
        // below. It used to be re-read for every controls root inside the lift loop (this pass runs
        // ~10x/s and the loop owns the hot path, so the repeated adapter lookup is worth removing).
        val bottomGapPx = globalGlassBottomGapPx(root)

        // 底栏下边距, floored exactly like the bar's own margin (`glassHostBottomMarginPx`): past
        // −bottomGapPx that margin clamps at 0 and the bar stops at the screen bottom, so the
        // page's controls must stop with it instead of sinking underneath the bar. The default
        // (and every positive) offset needs no gap read at all.
        val liftOffsetPx = if (bottomMarginOffsetPx >= 0) {
            bottomMarginOffsetPx
        } else {
            bottomMarginOffsetPx.coerceAtLeast(-bottomGapPx)
        }

        // Moved below the walk (was here) so it can use the marker that walk collects.
        val douyinPagerCandidates = ArrayList<View>()

        var progressControl: View? = null

        var visited = 0

        // Zero-allocation walk. The old ArrayDeque<Pair<View, Int>> allocated one Pair for every
        // pushed child — several thousand per pass, twice a second per visible page — and that
        // garbage was what showed up as the intermittent stutter (measured: 5ms median, 25-33ms
        // worst per pass against an 8.3ms frame). Two reused arrays keep the same traversal.
        val stack = douyinWalkViews
        val stackDepth = douyinWalkDepths
        var stackSize = 0

        stack[stackSize] = root
        stackDepth[stackSize] = 0
        stackSize++

        douyinShopPageActive = false
        nativeTitleRowAlpha = -1f
        nativeTitleRowHeightPx = 0
        nativeTitleRowTopPx = 0
        douyinTopReserveMarkers.clear()
        douyinFeaturedFeedRoots.clear()

        // Page markers for the four full-tree adjusters below.
        //
        // adjustDouyinGroupBuyPage / MessagePage / LocationCommentGap / ShopBackToTop each
        // used to run their own whole-tree walk looking for a resource id that exists on
        // exactly one page. On the feed — where the app spends nearly all its time — all
        // four walks found nothing, every time, at 5/s, 5/s, 6.25/s and 2.5/s respectively:
        // roughly 1.9 full tree walks per pass on top of the walk below, each allocating a
        // Pair per pushed child.
        //
        // The walk already computes every view's resource name, so it records which markers
        // it saw; each adjuster is then skipped outright on pages that do not have its
        // marker. The id sets below are exactly the predicates the adjusters start with, so
        // a page that would have been handled still is.
        var sawGroupBuy = false
        var sawMessage = false
        var sawLocationComment = false
        var sawShopBackToTop = false

        // Bottom-fill anchors, picked up where the walk already stands on every view: the message
        // page's header + conversation list, and the pager that hosts a page's own content (the
        // friends tab's video pager). The adjusters below read the refs this pass publishes.
        var messageTitle: View? = null
        var messageList: View? = null
        var messageListHeight = 0
        var bottomPager: View? = null
        var bottomPagerHeight = 0

        val rootScreen = IntArray(2).also(root::getLocationOnScreen)
        var bottomInputSeen = false
        val bottomInputLocation = IntArray(2)

        while (stackSize > 0 && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {

            stackSize--
            val view = stack[stackSize]!!
            val depth = stackDepth[stackSize]

            val idName = viewResourceEntryName(view)

            // Douyin's own title row, read where the walk already stands on every view: it is the
            // top copy's signature (blanked to alpha 0 by [DouyinTopBarFinder.hideNativeTitleBar]
            // while the copy draws it) and it is the row every page below it makes room for, so it
            // also hands [releaseDouyinPageTopReserve] the strip to look for.
            if (nativeTitleRowAlpha < 0f && DouyinTopBarFinder.isTopRow(view)) {
                nativeTitleRowAlpha = view.alpha
                nativeTitleRowHeightPx = view.height
                nativeTitleRowTopPx = IntArray(2).also(view::getLocationOnScreen)[1] - rootScreen[1]
            }

            // Cheap string compares against a name already computed for this view.
            when (idName) {
                "ehh", "jwn" -> sawGroupBuy = true
                "tv_title" -> {
                    sawMessage = true
                    if (view is TextView && view.text?.toString() == "消息") messageTitle = view
                }
                // 40.3-era list id; still accepted, with the same geometry the class path uses.
                "zny" -> {
                    sawMessage = true
                    if (view.width >= root.width * 0.9f &&
                        view.height >= root.height * 0.4f && view.height > messageListHeight
                    ) {
                        messageList = view
                        messageListHeight = view.height
                    }
                }
                "q68" -> sawLocationComment = true
                "pmr" -> sawShopBackToTop = true
                // The markers the two top-reserve releases locate (see [releaseDouyinPageTopReserve]).
                // The geometry test is the one those releases apply to a candidate, so the pass
                // records exactly the views the search used to accept.
                "dit", "dg5", "dk9", "dir" ->
                    if (douyinTopReserveMarkerVisible(view, root)) {
                        douyinTopReserveMarkers.add(view)
                    }
            }

            // 40.7.0 names the conversation list `zna`, so there is no id left to match. It is a
            // `RecyclerView` subclass (`SessionListCheckDrawRV`, which does not contain
            // "RecyclerView" in its own name), so the class chain identifies it. Geometry is
            // tested first: only a handful of large containers per pass reach the class lookup.
            if (view.width >= root.width * 0.9f && view.height >= root.height * 0.4f &&
                view.height > messageListHeight && isRecyclerViewLike(view)
            ) {
                messageList = view
                messageListHeight = view.height
            }

            // Pages that page their own full-screen content (the friends tab's video pager)
            // carry the native bar's height on that pager's ancestors: measured `[0,0][1272,2513]`
            // for the friends `androidx.viewpager.widget.ViewPager` under a `[0,0][1272,2772]`
            // tab host. Remember the tallest window-anchored pager that is shorter than the
            // window; the main tab pager is already full height and is excluded.
            if (view is ViewGroup && view.width >= root.width * 0.9f &&
                view.height >= root.height * 0.4f && view.height < root.height &&
                view.height > bottomPagerHeight &&
                classNameOf(view.javaClass).contains("Pager") &&
                douyinTopInWindow(view) == rootScreen[1]
            ) {
                bottomPager = view
                bottomPagerHeight = view.height
            }

            // Remember the sheet while its id is already in hand: the lift freeze needs it
            // during the close animation, when the bar has already come back. Same id + class
            // pair the location-comment walk uses, so both agree on what the sheet is.
            if (idName == "root_layout" && view is ViewGroup &&
                simpleNameOf(view.javaClass) == "CommentNestedLayout"
            ) {
                douyinSheetRef = WeakReference(view)
            }

            // A visible text input in the lower half of the window is Douyin's own signal
            // for a chat / comment surface (ported from the reference monitor's
            // `findBottomInputSurface`). The native tab row stays fully on screen and fully
            // opaque underneath those pages — measured in the friend chat: the row at
            // `loc=-5,2591 visible=1267x173` with every ancestor at alpha 1.0 — so the
            // rendered-presence test cannot see the page, while the input surface can.
            if (!bottomInputSeen && view is android.widget.EditText && view.isShown &&
                view.width > 0 && view.height > 0
            ) {
                view.getLocationOnScreen(bottomInputLocation)
                val inputBottom = bottomInputLocation[1] + view.height
                if (inputBottom >= (rootScreen[1] + root.height * 0.5f)) bottomInputSeen = true
            }

            if (idName == "dir" && douyinIsFeaturedFeedRoot(view)) {
                douyinFeaturedFeedRoots.add(view)
                (view.parent as? ViewGroup)?.let { parent ->
                    if (parent.paddingBottom != 0) {
                        parent.setPadding(parent.paddingLeft, parent.paddingTop,
                            parent.paddingRight, 0)
                    }
                    if (parent.clipToPadding) parent.clipToPadding = false
                }
                val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null &&
                    (params.bottomMargin != 0 || params.height != ViewGroup.LayoutParams.MATCH_PARENT)
                ) {
                    params.bottomMargin = 0
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                    view.layoutParams = params
                    view.requestLayout()
                }
                douyinMarginTargets.remove(view)
                // While the top copy owns the title row, the strip this page reserves for that
                // row carries nothing but the page background: that is the blank band reported
                // at the top of the 精选 page. The feed runs up behind the glass instead, the
                // same way these pages are already extended down to the bottom edge. The release
                // itself runs below the walk, once both the row and the markers are settled.
                // The Featured feed keeps its own pre-tab-bar height after the outer
                // page has grown. Extend only its refresh/list chain; card children
                // and the seek bar must retain their original layout.
                val featuredIds = setOf("dly", "diw", "dk2", "dk1")
                var featuredParent: ViewGroup = view as ViewGroup
                for (featuredId in featuredIds) {
                    val child = (0 until featuredParent.childCount)
                        .map(featuredParent::getChildAt)
                        .firstOrNull { viewResourceEntryName(it) == featuredId }
                        ?: break
                    if (featuredParent.paddingBottom != 0) {
                        featuredParent.setPadding(featuredParent.paddingLeft,
                            featuredParent.paddingTop, featuredParent.paddingRight, 0)
                    }
                    if (featuredParent.clipToPadding) featuredParent.clipToPadding = false
                    val childParams = child.layoutParams as? ViewGroup.MarginLayoutParams
                    if (childParams != null && (childParams.bottomMargin != 0 ||
                            childParams.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
                        childParams.bottomMargin = 0
                        childParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                        child.layoutParams = childParams
                        child.requestLayout()
                    }
                    featuredParent = child as? ViewGroup ?: break
                }
            }

            if (idName == "s+i" &&
                simpleNameOf(view.javaClass) == "SkyLightTouchEventFrameLayout") {
                (view.parent as? ViewGroup)?.let { parent ->
                    if (parent.paddingBottom != 0) {
                        parent.setPadding(parent.paddingLeft, parent.paddingTop,
                            parent.paddingRight, 0)
                    }
                    if (parent.clipToPadding) parent.clipToPadding = false
                }
                val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null &&
                    (params.bottomMargin != 0 || params.height != ViewGroup.LayoutParams.MATCH_PARENT)
                ) {
                    params.bottomMargin = 0
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                    view.layoutParams = params
                    view.requestLayout()
                }
                douyinMarginTargets.remove(view)
            }

            if ((idName == "s3m" || idName == "i=-") &&
                !douyinIsUnderLocalPage(view)) {
                val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null &&
                    (params.bottomMargin != 0 || params.height != ViewGroup.LayoutParams.MATCH_PARENT)
                ) {
                    params.bottomMargin = 0
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                    view.layoutParams = params
                    view.requestLayout()
                }
                douyinMarginTargets.remove(view)
            }

            if (idName == "pmr" && view.isShown && view.width >= root.width * 0.85f) {
                val shopScreen = IntArray(2).also(view::getLocationOnScreen)
                if (kotlin.math.abs(shopScreen[0] - rootScreen[0]) < root.width * 0.5f) {
                    douyinShopPageActive = true
                }
            }

            if (simpleNameOf(view.javaClass) == "0wNK" && view.isShown &&
                view.width >= root.width * 0.85f) {
                douyinCouponPageRef = WeakReference(view)
            }

            if (idName == DOUYIN_BOTTOM_SPACE_ID) {
                bottomSpace = view
                feedCornerMasks.suppressAbove(view)
            }

            // Direct pager identification: if bottom_space is missing or
            // renamed in a Douyin build, expanding the feed pager directly
            // still removes the black gap at the screen bottom.
            if (view is ViewGroup) {
                val pagerClass = classNameOf(view.javaClass)
                if ((pagerClass.contains("ViewPager") || pagerClass.contains("Pager")) &&
                    view.isAttachedToWindow && view.width >= root.width * 0.9f
                ) {
                    douyinPagerCandidates.add(view)
                }
            }

            // ViewPager keeps the current and adjacent video pages alive, and

            // replaces/reuses these roots while swiping or changing tabs. Keep

            // every instance so both pages remain aligned during the gesture.

            val overlayRoot = douyinIsVideoControlsRoot(view, idName)

            val progressId = idName == DOUYIN_PROGRESS_ID ||

                idName == DOUYIN_PROGRESS_ID_ALT ||

                idName in DOUYIN_PROGRESS_NAME_SET

            if (overlayRoot && view.isAttachedToWindow) {

                controlsRoots[view] = view

            }

val progressLikeClass = !progressId && view.width > 0 &&
    view.height <= (40f * density).toInt() && view.width >= root.width * 0.25f &&
    classNameOf(view.javaClass).let { name ->
        name.contains("Progress") || name.contains("SeekBar") || name.contains("Scrubber")
    }
if ((progressId || view is SeekBar || progressLikeClass) && view.isAttachedToWindow) {

                // Douyin fades the progress line out shortly after playback

                // starts; force it back so it stays visible.

                if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE

                if (view.alpha != 1f) view.alpha = 1f

                val stableParent = view.parent as? View

                val progressTarget = stableParent ?: view

                controlsRoots[progressTarget] = view

                progressControl = progressTarget

            }

            val labelSource = when (view) {

                is TextView -> view.text

                else -> view.contentDescription

            }

            // Most of the tree has no label at all. Building a String and running four
            // contains() checks for every visited view was the walk's per-node constant cost;
            // only the labelled views can ever match the related-search pills.
            val relatedHit = if (labelSource != null && labelSource.isNotEmpty()) {

                val label = labelSource.toString()

                DOUYIN_RELATED_SEARCH_TEXT.any { label.contains(it) }

            } else {

                false

            }

            if (relatedHit && view.isAttachedToWindow) {

                // Include the pill/background rather than moving only its text.

                var chip: View = view

                repeat(4) {

                    val candidate = chip.parent as? View ?: return@repeat

                    if (candidate.width > 0 && candidate.height > 0 &&

                        candidate.width < root.width * 0.9f &&

                        candidate.height < root.height * 0.25f

                    ) chip = candidate

                }

                controlsRoots[chip] = chip
                relatedSearchChips += chip

            }

            if (view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH) {

                for (index in 0 until view.childCount) {
                    if (stackSize >= DOUYIN_WALK_STACK_CAPACITY) break
                    val child = view.getChildAt(index)
                    // Hidden subtrees cannot carry a control root, a bar row or the app's bottom
                    // input, and Douyin keeps whole page hierarchies GONE/INVISIBLE after a tab
                    // switch — visiting them was most of the walk's remaining cost. The module
                    // fades chrome with alpha, not visibility, so nothing it targets is skipped.
                    if (child.visibility != View.VISIBLE) continue
                    stack[stackSize] = child
                    stackDepth[stackSize] = depth + 1
                    stackSize++
                }

            }

        }

        // Re-publish only when the node changed: a fresh WeakReference every pass would be an
        // allocation per update for a page that is not going anywhere.
        if (douyinMessageTitleRef?.get() !== messageTitle) {
            douyinMessageTitleRef = messageTitle?.let(::WeakReference)
        }
        if (douyinMessageListRef?.get() !== messageList) {
            douyinMessageListRef = messageList?.let(::WeakReference)
        }
        if (douyinBottomPagerRef?.get() !== bottomPager) {
            douyinBottomPagerRef = bottomPager?.let(::WeakReference)
        }

        val bottomInputNow = SystemClock.uptimeMillis()
        if (bottomInputSeen) {
            douyinBottomInputSeenAt = bottomInputNow
        } else if (bottomInputNow - douyinBottomInputSeenAt > DOUYIN_BOTTOM_INPUT_TTL_MS) {
            douyinBottomInputSeenAt = 0L
        }
        bottomSpace?.let { spacer ->

            douyinBottomSpace = WeakReference(spacer)

            if (spacer.alpha != 0f) spacer.alpha = 0f

            val parent = spacer.parent as? ViewGroup

            if (pager == null && parent != null) {

                pager = (0 until parent.childCount)

                    .map(parent::getChildAt)

                    .firstOrNull { candidate ->

                        candidate !== spacer &&

                            viewResourceEntryName(candidate) == DOUYIN_FEED_PAGER_ID &&

                            candidate.width >= parent.width * 0.9f &&

                            candidate.bottom <= spacer.top + DOUYIN_EDGE_TOLERANCE_PX

                    }

            }

        }

        if (pager == null && douyinPagerCandidates.isNotEmpty()) {

            // bottom_space was not found (renamed id or different layout
            // branch): pick the widest pager-like container whose bottom
            // reaches the screen bottom, then expand it below.
            pager = douyinPagerCandidates
                .filter { it.isAttachedToWindow && it.width >= root.width * 0.9f }
                .minByOrNull { candidate ->
                    val p = IntArray(2).also { candidate.getLocationOnScreen(it) }
                    val r = IntArray(2).also { root.getLocationOnScreen(it) }
                    (root.height - (p[1] - r[1] + candidate.height)).coerceAtLeast(0)
                }
                ?.takeIf { candidate ->
                    val p = IntArray(2).also { candidate.getLocationOnScreen(it) }
                    val r = IntArray(2).also { root.getLocationOnScreen(it) }
                    p[1] - r[1] + candidate.height >= root.height - (40f * density).toInt()
                }

            if (pager != null) douyinFeedPager = WeakReference(pager!!)

        }

        val pagerSet = LinkedHashSet<View>()

        pager?.let { pagerSet.add(it) }

        douyinPagerCandidates

            .filter { it.isAttachedToWindow && it.width >= root.width * 0.9f }

            .forEach { pagerSet.add(it) }

        val aliveCodes = HashSet<Int>()

        for (feed in pagerSet) {

            val code = System.identityHashCode(feed)

            aliveCodes.add(code)

            douyinExpandedPagers.add(code)

            val params = feed.layoutParams

            if (params is RelativeLayout.LayoutParams) {

                if (params.getRule(RelativeLayout.ABOVE) != 0 ||
                    params.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) == 0 ||
                    params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    params.removeRule(RelativeLayout.ABOVE)
                    params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                    feed.layoutParams = params
                    feed.requestLayout()
                    (feed.parent as? View)?.requestLayout()
                }

            } else if (params is ViewGroup.MarginLayoutParams) {

                if (params.bottomMargin != 0 ||
                    params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    params.bottomMargin = 0
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                    feed.layoutParams = params
                    feed.requestLayout()
                }

            }

            // Photo/text posts keep an additional full-width page root at the
            // old native-tab height even after the outer feed pager is made
            // edge-to-edge. Video ignores that root because its Surface fills
            // the pager, which is why only video previously reached the
            // navigation-bar area. Expand the page root as well, but leave ad
            // cards untouched as requested.
            val pageHost = feed as? ViewGroup
            if (pageHost != null && pageAdjustDue("pagerchildren", now)) {
                for (index in 0 until pageHost.childCount) {
                    val page = pageHost.getChildAt(index)
                    if (!page.isAttachedToWindow || page.width < root.width * 0.85f ||
                        douyinPageLooksLikeAdvertisement(page) ||
                        douyinPageContainsCouponRoot(page)
                    ) continue
                    val pageParams = page.layoutParams
                    if (pageParams is ViewGroup.MarginLayoutParams) {
                        var changed = false
                        if (pageParams.bottomMargin != 0) {
                            pageParams.bottomMargin = 0
                            changed = true
                        }
                        if (pageParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                            pageParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                            changed = true
                        }
                        if (changed) {
                            page.layoutParams = pageParams
                            page.requestLayout()
                        }
                    }
                    extendDouyinPhotoContentToBottom(page, root)
                }
            }

        }

        douyinExpandedPagers.retainAll(aliveCodes)

        // 经验 / 精选 C2 pages keep the native title-row strip as a top padding on their
        // CustomSwipeRefreshLayout / DoubleBallSwipeRefreshLayout. The lift branch below releases
        // it only when the page also exposes a video-controls target; the 经验 grid has none, so
        // the strip stayed white above the cards. Release it for every visible C2 page instead,
        // independently of the lift targets. The release is idempotent: once the padding is zero
        // it does nothing until Douyin re-applies it.
        if (topBarOwnsTitleRow() || bottomBarGlassActive) {
            for (feed in pagerSet) {
                if (feed !is ViewGroup) continue
                for (index in 0 until feed.childCount) {
                    val page = feed.getChildAt(index)
                    if (page.visibility == View.VISIBLE && douyinIsC2FeedPageContainer(page)) {
                        if (topBarOwnsTitleRow()) {
                            releaseDouyinPageTopReserve(page, DOUYIN_C2_FEED_CONTAINER_IDS)
                            hideDouyinC2SearchHeader(page)
                        }
                        if (bottomBarGlassActive) {
                            releaseDouyinC2BottomReserve(page)
                        } else {
                            restoreDouyinC2BottomReserve(page)
                        }
                    }
                }
            }
        }


        adjustDouyinCouponPage(root, clearanceTop)
        if (sawGroupBuy) adjustDouyinGroupBuyPage(root)
        if (sawMessage || bottomPager != null) adjustDouyinPageBottom(root)
        if (sawLocationComment) adjustDouyinLocationCommentGap(root)
        if (sawShopBackToTop) adjustDouyinShopBackToTop(root, clearanceTop)


        // Resource names on the per-video controls root are obfuscated and
        // rotate between Douyin releases. When i7/ja no longer exists, locate
        // the controls layer inside every live pager page by geometry.
        //
        // Discovery is additive: a page already in `douyinPageControlsCache` keeps its entry and
        // is lifted from there, so this only ever has to notice *new* pages. Running it on every
        // pass cost 2.3-2.7ms mean and up to 10ms peak on device even with the validation cached,
        // because each page still pays an ancestor scan plus `controlsRoots.keys.any {
        // douyinContainsView(page, it) }`.
        //
        // A page can only introduce a new controls root when the pager's child set changes — a
        // swipe recycling a page, a tab switch, the first layout. So it runs when the page-count
        // signature changes, with a slow timer as the safety net for a page that swaps its own
        // controls layer in place (and for the first passes, while the structure is still
        // settling). Scrolling the comment sheet changes neither, which is where the stutter was.
        var pageSignature = pagerSet.size
        for (feed in pagerSet) {
            pageSignature = pageSignature * 31 + ((feed as? ViewGroup)?.childCount ?: 0)
        }
        val shapeChanged = pageSignature != lastPageSignature || pagerSet.isEmpty()
        if (shapeChanged) lastPageSignature = pageSignature
        if (shapeChanged || now - lastDiscoverAt >= DOUYIN_DISCOVER_INTERVAL_MS) {
            lastDiscoverAt = now
            run {
            for (feed in pagerSet) {
                val pageHost = feed as? ViewGroup ?: continue
                for (index in 0 until pageHost.childCount) {
                    val page = pageHost.getChildAt(index)
                    // Nested pagers inside a controls root do not own a
                    // second lift. In early layout they can otherwise cache
                    // only the footer (cne), leaving sibling controls behind.
                    var ancestor = page.parent as? View
                    var ownsAncestor = false
                    while (ancestor != null && ancestor !== root) {
                        if (douyinIsVideoControlsRoot(ancestor)) { ownsAncestor = true; break }
                        ancestor = ancestor.parent as? View
                    }
                    if (ownsAncestor) {
                        douyinPageControlsCache.remove(page)?.let { stale ->
                            if (!douyinIsVideoControlsRoot(stale)) {
                                DouyinControlMarginGuard.release(stale)
                                val previousLift = douyinMarginTargets.remove(stale)
                                val params = stale.layoutParams as? ViewGroup.MarginLayoutParams
                                if (previousLift != null && params?.bottomMargin == previousLift) {
                                    params.bottomMargin = 0
                                    stale.requestLayout()
                                }
                            }
                        }
                        continue
                    }
                    // A named root on an off-screen subpage must not disable
                    // structural recognition on this independently owned page.
                    if (controlsRoots.keys.any { known ->
                            douyinIsVideoControlsRoot(known) && douyinContainsView(page, known)
                        }) continue
                    val validated = douyinValidatedControls[page]
                    val candidate = validated
                        ?.takeIf { (view, at) ->
                            now - at <= DOUYIN_VALIDATED_CONTROLS_TTL_MS && view.isAttachedToWindow
                        }
                        ?.first
                        ?: (
                            douyinPageControlsCache[page]
                                ?.takeIf { it.isAttachedToWindow && !douyinIsProgressContainer(it) &&
                                    !douyinContainerHostsVideoSurface(it, root) &&
                                    !douyinIsFeedContentFrame(it) &&
                                    !douyinContainsFullWidthPhoto(it, root) &&
                                    !douyinContainsGroupBuyContent(it) }
                                ?: findPageControlsContainer(page, root)?.takeUnless {
                                    douyinIsFeedContentFrame(it) || douyinContainsFullWidthPhoto(it, root) ||
                                        douyinContainsGroupBuyContent(it)
                                }?.also {
                                    douyinPageControlsCache[page] = it
                                }
                            )?.also { douyinValidatedControls[page] = it to now }
                    if (candidate != null && candidate !== progressControl &&
                !douyinIsProgressContainer(candidate)
                    ) {                        controlsRoots[candidate] = candidate
                    }
                }
            }
            douyinPageControlsCache.entries.removeAll { (page, controls) ->
                !page.isAttachedToWindow || !controls.isAttachedToWindow
            }
            douyinValidatedControls.entries.removeAll { (page, validated) ->
                !page.isAttachedToWindow || !validated.first.isAttachedToWindow
            }
            }
        }

        // ---- Video controls: the per-page overlay root (i7) carries the
        // caption, action column and related-search pill inside each video
        // page. Raising it by margin lifts every control while keeping the
        // page-relative position fixed, so swiping slides the controls
        // together with the video exactly like the native feed.

        // Snapshot the cache lookups once per pass instead of re-deriving them inside the
        // per-target predicate below: `douyinPageControlsCache.values.any { it === x }` is a
        // linear scan of a WeakHashMap's values, and it used to run for every candidate root for
        // every target, together with a fresh `douyinIsVideoControlsRoot` per candidate (two
        // resource-name lookups each).
        val structuralRoots = douyinPageControlsCache.values.toHashSet()

        // The featured feed's own top band, released here rather than from the walk branch that
        // spots the page: the release both measures Douyin's title row ([nativeTitleRowAlpha] is
        // still -1f until the walk has stood on that row) and consumes the markers the walk
        // collects, so neither is settled while the walk is still running.
        if (topBarOwnsTitleRow()) {
            for (index in 0 until douyinFeaturedFeedRoots.size) {
                releaseDouyinPageTopReserve(douyinFeaturedFeedRoots[index], DOUYIN_TOP_RESERVE_MARKER_IDS)
            }
        }

        for (target in controlsRoots.keys) {
            val namedRoot = douyinIsVideoControlsRoot(target)
            // Some native video items give their controls an independent
            // MeasureOnce layer. Raise that layer, not both it and its wrapper.
            if (controlsRoots.keys.any { other ->
                    other !== target && (douyinIsVideoControlsRoot(other) ||
                        structuralRoots.contains(other)) &&
                        other.visibility == View.VISIBLE && douyinContainsView(target, other)
                }) continue
            val structuralRoot = structuralRoots.contains(target) &&
                !douyinIsProgressContainer(target) && target !== progressControl
            if (!nativeBarAway && !sheetUp && (namedRoot || structuralRoot) &&
                !douyinContainerHostsVideoSurface(target, root) &&
                !douyinIsFeedContentFrame(target) &&
                !douyinContainsFullWidthPhoto(target, root) &&
                !douyinContainsGroupBuyContent(target)
            ) {
                liftDouyinControlByMargin(
                    target,
                    root,
                    clearanceTop,
                    // clearanceTop is measured from the screen bottom, but the host is
                    // itself lifted bottomGapPx (plus 底栏下边距) above that edge. Adding the
                    // gap lands the controls on the bar's REAL top edge; the old fixed 40dp
                    // pushed them ~20dp higher and left a visibly empty band.
                    bottomGapPx + liftOffsetPx,
                )
            }
        }

        // ---- Progress bar: fixed overlay layer above the tab bar; margin
        // lift is self-stable because it is computed from live positions.

        var progress: View? = douyinCachedProgressRef?.get()?.takeIf { it.isAttachedToWindow }

        if (progress == null && progressControl != null) {
            progress = progressControl
            douyinCachedProgressRef = WeakReference(progress)
        }

        // The progress bar is lifted in every bar state, including while Douyin has paged its tab
        // host off screen. Two measurements drive this:
        //
        // - Opening the comment sheet (or paging the bar away) makes Douyin destroy the margined
        //   progress view and build a fresh one (measured: the lifted view is h=2669 bm=103 while
        //   the sheet is up, and h=2772 bm=0 while it is closed), so any margin we applied
        //   disappears with the old view. Skipping the lift there therefore did not "freeze"
        //   anything — it let the new view sit at Douyin's unlifted position, 103px lower, which
        //   is the sink the user sees, and lifting it again is the rise.
        // - Its target is derived from the bar height alone, not from the page geometry a sheet
        //   re-lays out, so the value computed while the bar is off screen is still valid.
        //
        // Hence no `nativeBarAway` gate: re-applying the lift to whatever view currently carries
        // the progress bar keeps it in one place across every toggle.
        if (progress != null) {
            val progressRoot = progress!!
            // wb9 is the visible track inside the full-height DProSeekBar.
            // Anchor the track, rather than the invisible wrapper's bottom.
            val track = (progressRoot as? ViewGroup)?.let { group ->
                (0 until group.childCount).asSequence().map(group::getChildAt)
                    .firstOrNull { simpleNameOf(it.javaClass) == "ProSeekBarView" && it.height > 0 }
            }
            val trackExtra = if (track != null) {
                // Existing floating bar top is clearanceTop - 21dp (also
                // used by coupon avoidance). Keep the visible track 10dp above.
                //
                // The caption/author/action cluster now ends ~11px above the bar (its
                // lift is capped by its own lowest widget), so the track has to be sunk
                // below that cluster instead of parking above it: measured on the feed
                // the caption ends at 2509 and the track sat at 2478 — a 31px overlap on
                // the caption's last line. DOUYIN_PROGRESS_TRACK_SINK_DP moves the track
                // under the caption while keeping it on the bar's top edge (2514 against
                // a bar top of 2520).
                //
                // This sink is deliberately NOT part of the 控件下移 switch: the track keeps
                // the fixed position in both switch states, so it can never end up above the
                // caption no matter what the cluster does. The switch only moves the cluster.
                ((21f + 10f - DOUYIN_CONTROL_SAFE_GAP_DP) * density).roundToInt() -
                    (progressRoot.height - track.bottom) -
                    (DOUYIN_PROGRESS_TRACK_SINK_DP * density).roundToInt()
            } else 0
            // 底栏下边距: the bar's own edge moves with the slider, so the track rides the same
            // offset (the cluster above already does).
            liftDouyinControlByMargin(progressRoot, root, clearanceTop, trackExtra + liftOffsetPx)
        }

        // ---- Related-search pill: independent container lifted by margin.

        var chip: View? = douyinCachedChipRef?.get()?.takeIf {
            it.isAttachedToWindow && it in relatedSearchChips && !douyinContainsGroupBuyContent(it)
        }

        if (chip == null) {
            for (target in relatedSearchChips) {
                if (target === progressControl) continue
                if (douyinContainsGroupBuyContent(target)) continue
                val name = viewResourceEntryName(target)
                if (name == DOUYIN_CONTROLS_ROOT_ID || name == DOUYIN_CONTROLS_ROOT_ID_ALT) continue
                chip = target
                douyinCachedChipRef = WeakReference(target)
                break
            }
        }

        // A structural controls root is already raised above with its extra
        // clearance. Never apply the independent-pill lift to that same View.
        if (!nativeBarAway && !sheetUp && chip != null && douyinPageControlsCache.values.none { it === chip }) {
            liftDouyinControlByMargin(chip!!, root, clearanceTop)
        }

        val diagNow = SystemClock.uptimeMillis()


        // Per-pass geometry trace: the string walk below is diagnostics only, so it stays
        // behind the shared switch. Failures still reach logcat through the catch at the
        // end of `update`, which is deliberately left ungated.

        // Video Surface read-back was removed as unstable; the glass now carries

        // only the overlay controls through the live RenderNode scene.

        }.onFailure { error ->

            // This catch covers the whole per-pass body: a failure here silently stops the
            // control lifts, so the class name and stack trace matter more than the message.
            android.util.Log.e(
                "LiquidTab",
                "douyin adjust failed: ${classNameOf(error.javaClass)}: ${error.message}",
                error,
            )

        }

    }

    /**
     * `Class` -> name caches for the main walk.
     *
     * The walk evaluates `javaClass.simpleName` / `javaClass.name` for every visited view
     * (up to DOUYIN_LAYOUT_MAX_VIEWS of them, ten times a second), and several ancestor
     * climbs read a name on every level. Both are reflective JNI calls — `getSimpleName()`
     * in particular re-derives the name from the binary name and can allocate — and the
     * answer is a pure function of the Class object, so it belongs in a map. Douyin's tree
     * has a few hundred distinct classes at most, so these stay tiny.
     *
     * Plain HashMap, not ConcurrentHashMap: every caller is on the UI thread.
     */
    private val classSimpleNames = HashMap<Class<*>, String>()

    private val classNames = HashMap<Class<*>, String>()

    private fun simpleNameOf(cls: Class<*>): String =
        classSimpleNames.getOrPut(cls) { cls.simpleName }

    private fun classNameOf(cls: Class<*>): String =
        classNames.getOrPut(cls) { cls.name }

    /** Cache for [isRecyclerViewLike]: the answer is a pure function of the concrete class. */
    private val recyclerViewLikeClasses = HashMap<Class<*>, Boolean>()

    /**
     * True for `RecyclerView` and its subclasses.
     *
     * The list on Douyin's message page is a custom subclass, and the concrete class name does
     * not have to contain "RecyclerView" (`TopOnlineRecyclerView` does, the conversation list's
     * `SessionListCheckDrawRV` does not). `AccessibilityNodeInfo` reports the canonical
     * `androidx.recyclerview.widget.RecyclerView` because the widget overrides
     * `getAccessibilityClassName`, so a hierarchy dump cannot be used to pick the match either;
     * the superclass chain can.
     */
    private fun isRecyclerViewLike(view: View): Boolean {
        val concrete = view.javaClass
        recyclerViewLikeClasses[concrete]?.let { return it }
        var cls: Class<*>? = concrete
        var result = false
        var guard = 0
        while (cls != null && guard++ < 10) {
            if (classNameOf(cls).contains("RecyclerView")) {
                result = true
                break
            }
            cls = cls.superclass
        }
        recyclerViewLikeClasses[concrete] = result
        return result
    }

    /**
     * [view]'s top in window coordinates, without allocating an `IntArray` per call.
     *
     * The page pass reads this for the root, the anchor and up to 16 chain ancestors — one
     * scratch array instead of one allocation each, several times a second.
     */
    private fun douyinTopInWindow(view: View): Int {
        view.getLocationOnScreen(douyinScratchLocation)
        return douyinScratchLocation[1]
    }

    private fun douyinPageLooksLikeAdvertisement(page: View): Boolean {
        val now = SystemClock.uptimeMillis()
        douyinAdvertisementCache[page]?.let { (checkedAt, advertisement) ->
            // A page's ad markers do not appear or disappear while it is being swiped, yet a
            // negative answer expired after 250 ms while the page was re-classified every
            // 200 ms — so nearly every sweep paid for the whole 1200-view walk again. One
            // second still re-checks a recycled pager page quickly, at a quarter of the scans.
            val ttl = if (advertisement) 750L else 1_000L
            if (now - checkedAt < ttl) return advertisement
        }
        // No Pair per node and no String per node: the walk only allocates for a genuine match.
        val stack = ArrayDeque<View>()
        stack += page
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 1200) {
            val view = stack.removeLast()
            val text = (view as? TextView)?.text
            if (looksLikeAdvertisementLabel(text) ||
                looksLikeAdvertisementLabel(view.contentDescription)
            ) {
                douyinAdvertisementCache[page] = now to true
                return true
            }
            // This Douyin build hosts the ad action row under u11/u10. The
            // marker is supplementary to the visible advertisement label.
            if (view is ViewGroup && viewResourceEntryName(view) == "u11" &&
                (0 until view.childCount).any { index ->
                    viewResourceEntryName(view.getChildAt(index)) == "u10"
                }
            ) {
                douyinAdvertisementCache[page] = now to true
                return true
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index)
            }
        }
        douyinAdvertisementCache[page] = now to false
        return false
    }

    /** Advertisement-label test on the CharSequence itself, so a miss allocates nothing. */
    private fun looksLikeAdvertisementLabel(raw: CharSequence?): Boolean {
        if (raw == null || raw.isEmpty() || raw.length > 32) return false
        val label = raw.trim()
        if (label.isEmpty()) return false
        return label.startsWith("广告") ||
            (label.length <= 24 && label.contains("广告")) ||
            (label.length == 2 && label[0].equals('A', ignoreCase = true) &&
                label[1].equals('d', ignoreCase = true)) ||
            label.contains("Sponsored", ignoreCase = true) ||
            label.contains("赞助")
    }

    private var lastLivePreviewSkipLogAt = 0L

    /**
     * Whether [view] belongs to Douyin's live preview card (the feed's "直播" card, before
     * the room is entered).
     *
     * The page root and its player view carry no id or class the shared recognition can
     * use, but the page container is `LivePreviewFrameLayout`; a few ancestor levels are
     * walked so the card's own overlay layers match too.
     */
    private fun douyinIsUnderLivePreview(view: View): Boolean {
        var node: View? = view
        repeat(4) {
            val current = node ?: return false
            if (simpleNameOf(current.javaClass).contains("LivePreview")) return true
            node = current.parent as? View
        }
        return false
    }

    /**
     * Whether [view] carries the group-buy (附近/团购) page's own content: the app's
     * `NearbyFragmentTouchEventLayout` with id `ehd`, full width and on screen.
     *
     * Deliberately scoped to the candidate's own subtree and to the visible part of the window:
     * the C2 feed and the live-preview pages share ids like `dit`/`dlx` with 精选/经验, but never
     * contain `ehd`, so checking inside one candidate cannot leak into a neighbouring page — that
     * leak is what made earlier id-based fixes move the live page's controls.
     */
    private fun douyinIsGroupBuyPageContainer(view: View): Boolean {
        val now = SystemClock.uptimeMillis()
        douyinGroupBuyPageCache[view]?.let { (at, result) ->
            if (now - at < targetClassifyTtlMs) return result
        }
        val root = currentRoot ?: return false
        val rootPos = IntArray(2).also(root::getLocationOnScreen)
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += view to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 400) {
            val (candidate, depth) = stack.removeLast()
            if (viewResourceEntryName(candidate) == "ehd") {
                val pos = IntArray(2).also(candidate::getLocationOnScreen)
                val top = pos[1] - rootPos[1]
                if (top >= 0 && top < root.height && candidate.width >= root.width * 0.9f) {
                    douyinGroupBuyPageCache[view] = now to true
                    return true
                }
            }
            if (candidate is ViewGroup && depth < 12) {
                for (index in 0 until candidate.childCount) {
                    stack += candidate.getChildAt(index) to (depth + 1)
                }
            }
        }
        douyinGroupBuyPageCache[view] = now to false
        return false
    }

    /**
     * Whether [view] is the 精选 / 经验 C2 feed page frame: its own subtree holds a `dit`-family
     * list container that is on screen and full width, and its page chain carries no
     * `LivePreview` container.
     *
     * Measured on the device: the C2 pages are `dit`/`dk0`/`dlx`/`diq` while the live-preview page
     * is a plain feed video page (`i6`/`w0f`/`6ow`, no `dit` at all), so this test cannot reach it.
     */
    private fun douyinIsC2FeedPageContainer(view: View): Boolean {
        val now = SystemClock.uptimeMillis()
        douyinC2FeedPageCache[view]?.let { (at, result) ->
            if (now - at < targetClassifyTtlMs) return result
        }
        var node: View? = view
        var levels = 0
        while (node != null && levels < 6) {
            if (simpleNameOf(node.javaClass).contains("LivePreview")) {
                douyinC2FeedPageCache[view] = now to false
                return false
            }
            node = node.parent as? View
            levels++
        }
        val root = currentRoot ?: return false
        val rootPos = IntArray(2).also(root::getLocationOnScreen)
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += view to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 400) {
            val (candidate, depth) = stack.removeLast()
            if (viewResourceEntryName(candidate) in DOUYIN_C2_FEED_CONTAINER_IDS) {
                val pos = IntArray(2).also(candidate::getLocationOnScreen)
                val top = pos[1] - rootPos[1]
                if (top >= 0 && top < root.height && candidate.width >= root.width * 0.9f) {
                    douyinC2FeedPageCache[view] = now to true
                    return true
                }
            }
            if (candidate is ViewGroup && depth < 12) {
                for (index in 0 until candidate.childCount) {
                    stack += candidate.getChildAt(index) to (depth + 1)
                }
            }
        }
        douyinC2FeedPageCache[view] = now to false
        return false
    }

    /**
     * Releases the app's own native-bar reserve inside the group-buy page (the 183px that is left
     * after the module's page-frame lift is handed back). Scoped the same way as the page test: the
     * search starts by locating `ehd` inside this page and then only walks that view's ancestors,
     * so nothing outside the group-buy page can be touched. The range is the reserve measured on
     * the device (183px, i.e. 40..60dp), which keeps unrelated paddings out of scope.
     */
    private fun releaseDouyinPageNativeReserve(page: View, markerIds: Set<String>) {
        val minPx = (40f * density).toInt()
        val maxPx = (60f * density).toInt()
        val root = currentRoot
        val rootPos = root?.let { IntArray(2).also(it::getLocationOnScreen) }
        var content: View? = null
        val stack = ArrayDeque<View>()
        stack += page
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 400) {
            val candidate = stack.removeLast()
            if (viewResourceEntryName(candidate) in markerIds) {
                val onScreen = root == null || rootPos == null || run {
                    val pos = IntArray(2).also(candidate::getLocationOnScreen)
                    val top = pos[1] - rootPos[1]
                    top >= 0 && top < root.height
                }
                if (onScreen && candidate.width >= (root?.width ?: candidate.width) * 0.9f) {
                    content = candidate
                    break
                }
            }
            if (candidate is ViewGroup) {
                for (index in 0 until candidate.childCount) stack += candidate.getChildAt(index)
            }
        }
        var node: View? = content ?: return
        var levels = 0
        while (node != null && levels < 4) {
            val params = node.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null && params.bottomMargin in minPx..maxPx) {
                params.bottomMargin = 0
                node.layoutParams = params
                node.requestLayout()
                return
            }
            if (node.paddingBottom in minPx..maxPx) {
                node.setPadding(node.paddingLeft, node.paddingTop, node.paddingRight, 0)
                node.requestLayout()
                return
            }
            node = node.parent as? View
            levels++
        }
    }

    /**
     * True while the module's top copy owns Douyin's title row.
     *
     * Both halves are needed. 顶栏液态玻璃 must be on — the same condition `GlassInstaller`
     * installs the top host under — because that is when the module owns the row. And the row must
     * have been blanked by [DouyinTopBarFinder.hideNativeTitleBar] as the current pass saw it: with
     * the copy off the native row keeps its own space and must keep the page's top reserve with it.
     */
    private fun topBarOwnsTitleRow(): Boolean = topBarGlassActive && nativeTitleRowAlpha == 0f

    /**
     * Releases the app's own *top* reserve in a page whose content sits below Douyin's title row.
     *
     * Douyin's title row is an overlay: the pager fills the window and each page insets its own
     * content by the row's height. The feed page has no such inset and its video fills the window;
     * the 精选 / 经验 (C2) pages do, and while the top copy hides the row that inset is a strip
     * nothing draws into — the page background, which the user reads as a blank band over the top
     * of the feed (the row measures 1272x164 = 44dp on the 1272x2772 / override density 598 device
     * the adapter is written against). Zeroing the reserve
     * lets the cards run up behind the glass, exactly as these same pages are already extended down
     * to the bottom edge.
     *
     * Scoped like [releaseDouyinPageNativeReserve]: only a marker that sits inside [page]'s own
     * subtree can be acted on, and only that marker's ancestors up to the page itself are walked. The
     * escape window is measured instead of guessed — the strip a page makes for the title row is at
     * least that row's height and at most down to the row's lower edge, so both conventions (row
     * only, or status bar plus row) are covered by one range. No row seen in the last pass means the
     * same 40..60dp band `releaseDouyinPageNativeReserve` uses.
     *
     * Unlike that function, this one does not search: the pass walk records the markers as it goes
     * ([douyinTopReserveMarkers]) and [douyinTopReserveContentOf] only tests containment, so the
     * per-call subtree walk the release used to pay for is gone.
     */
    private fun releaseDouyinPageTopReserve(page: View, markerIds: Set<String>) {
        val tolerancePx = (4f * density).toInt()
        val rowHeightPx = nativeTitleRowHeightPx
        val minPx: Int
        val maxPx: Int
        if (rowHeightPx > 0) {
            minPx = rowHeightPx - tolerancePx
            maxPx = nativeTitleRowTopPx + rowHeightPx + tolerancePx
        } else {
            minPx = (40f * density).toInt()
            maxPx = (60f * density).toInt()
        }
        var node: View? = douyinTopReserveContentOf(page, markerIds) ?: return
        var levels = 0
        // Walk up to the page itself, not a fixed four levels: the 经验 page carries the
        // 295px reserve on `CustomSwipeRefreshLayout` (`dlx`), five levels above the `dit`
        // marker, so the old limit stopped one ancestor short and never saw the padding.
        while (node != null && levels < 8) {
            val params = node.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null && params.topMargin in minPx..maxPx) {
                val released = params.topMargin
                params.topMargin = 0
                // A margin moved this view's own top edge; give the height back so the page does
                // not trade the top band for a new one at its bottom. Fixed-height views only:
                // MATCH_PARENT / WRAP_CONTENT already follow the parent's box.
                if (params.height > 0) params.height += released
                node.layoutParams = params
                node.requestLayout()
                Log.i(
                    "LiquidTab",
                    "douyin top reserve released (margin) " +
                        "${classNameOf(node.javaClass)} ${released}px",
                )
                return
            }
            if (node.paddingTop in minPx..maxPx) {
                val released = node.paddingTop
                node.setPadding(node.paddingLeft, 0, node.paddingRight, node.paddingBottom)
                node.requestLayout()
                Log.i(
                    "LiquidTab",
                    "douyin top reserve released (padding) " +
                        "${classNameOf(node.javaClass)} ${released}px",
                )
                return
            }
            if (node === page) break
            node = node.parent as? View
            levels++
        }
    }

    /**
     * The 经验 / 精选 page's own search header (`bjh`) is redundant while the module's top copy
     * already provides the search key. It sits above the card list and, with the top reserve
     * released, would otherwise be the first thing under the glass. Hide it once per page and
     * cache the header so the per-frame pass does not walk the page subtree.
     */
    private fun hideDouyinC2SearchHeader(page: View) {
        val cached = douyinC2SearchHeaders[page]?.get()
        val header = cached?.takeIf { it.isAttachedToWindow } ?: findDouyinC2SearchHeader(page) ?: return
        douyinC2SearchHeaders[page] = java.lang.ref.WeakReference(header)
        val headerHeight = douyinC2HeaderHeights.getOrPut(page) {
            header.height.coerceAtLeast(0)
        }
        if (header.visibility != View.GONE) header.visibility = View.GONE
        if (headerHeight <= 0) return
        // The AppBarLayout is GONE but its CoordinatorLayout behaviour still leaves the content
        // root at the header's old top. Shift that page-local content root up and let it fill the
        // page height; the shared bottom tab container is untouched.
        val parent = header.parent as? ViewGroup ?: return
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (viewResourceEntryName(child) != "i=-") continue
            if (child.translationY != -headerHeight.toFloat()) {
                child.translationY = -headerHeight.toFloat()
            }
            val targetHeight = page.height
            if (targetHeight > 0 && child.layoutParams.height != targetHeight) {
                child.layoutParams = child.layoutParams.apply { height = targetHeight }
                child.requestLayout()
            }
            return
        }
    }

    /**
     * The C2 page root (`dlx`, a CustomSwipeRefreshLayout) reserves the native bottom tab bar's
     * height as `paddingBottom` (measured 172px on this device). The module's own bottom bar is
     * already drawn over that strip, so the reserve is a white band under the cards. Remove only
     * this page's own padding, remember the original value, and restore it if the liquid bottom
     * bar is switched off. The shared native tab container (`cvc`) is never touched.
     */
    private fun releaseDouyinC2BottomReserve(page: View) {
        val padding = page.paddingBottom
        if (padding <= 0) return
        val minPx = (40f * density).toInt()
        val maxPx = (80f * density).toInt()
        if (padding !in minPx..maxPx) return
        if (!douyinC2BottomReserve.containsKey(page)) douyinC2BottomReserve[page] = padding
        page.setPadding(page.paddingLeft, page.paddingTop, page.paddingRight, 0)
        page.requestLayout()
    }

    private fun restoreDouyinC2BottomReserve(page: View) {
        val original = douyinC2BottomReserve.remove(page) ?: return
        if (page.paddingBottom == 0) {
            page.setPadding(page.paddingLeft, page.paddingTop, page.paddingRight, original)
            page.requestLayout()
        }
    }

    private fun findDouyinC2SearchHeader(page: View): View? {
        val stack = ArrayDeque<View>()
        stack += page
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 600) {
            val view = stack.removeLast()
            if (viewResourceEntryName(view) == "bjh") return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index)
            }
        }
        return null
    }

    /**
     * The marker the current pass recorded for [page], or [page] itself when it carries one.
     *
     * [page] is tested first because it can be the marker itself: the 精选 page root carries `dir`,
     * and the subtree search this replaces accepted the page as its own first candidate the same
     * way. The rest is a scan of what the walk collected ([douyinTopReserveMarkers]) filtered by the
     * caller's id set and [page]'s subtree, in the walk's own order — the same order the old search
     * produced, since both visit the last child first.
     */
    private fun douyinTopReserveContentOf(page: View, markerIds: Set<String>): View? {
        if (viewResourceEntryName(page) in markerIds && douyinTopReserveMarkerVisible(page, currentRoot)) {
            return page
        }
        for (index in 0 until douyinTopReserveMarkers.size) {
            val marker = douyinTopReserveMarkers[index]
            if (viewResourceEntryName(marker) !in markerIds) continue
            if (douyinContainsView(page, marker)) return marker
        }
        return null
    }

    /**
     * The one geometry test both top-reserve releases apply to a marker candidate: on screen under
     * the window root, and at least 0.9 of the window's width. Kept in a single place so what the
     * walk records ([douyinTopReserveMarkers]) is exactly what the release would have accepted.
     */
    private fun douyinTopReserveMarkerVisible(view: View, root: ViewGroup?): Boolean {
        if (root == null) return true
        val viewPos = IntArray(2).also(view::getLocationOnScreen)
        val rootPos = IntArray(2).also(root::getLocationOnScreen)
        val top = viewPos[1] - rootPos[1]
        return top >= 0 && top < root.height && view.width >= root.width * 0.9f
    }

    private fun extendDouyinPhotoContentToBottom(page: View, root: ViewGroup) {
        // A live preview card is not an image post. Measured on the device: the page is
        // `LivePreviewFrameLayout/root_view` (1272x2772) whose player view is an obfuscated
        // class (`X.1J5e`, 1272x715 — a letterboxed landscape live surface, only 26% of the
        // window height, so the >=50% height test in findVideoContentInContainer rejects it)
        // plus a `RemoteImageView/x22` cover (1272x8290). Treating that cover as image-post
        // artwork stretched it to the bottom edge and forced CENTER_CROP, which zoomed the
        // portrait live preview.
        if (douyinIsUnderLivePreview(page)) {
            val now = SystemClock.uptimeMillis()
            if (now - lastLivePreviewSkipLogAt >= 2_000L) {
                lastLivePreviewSkipLogAt = now
            }
            return
        }
        val hasVideoSurface = douyinContainerHostsVideoSurface(page, root)
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += page to 0
        var visited = 0
        var largestImage: ImageView? = null
        var imagePostContent: ImageView? = null
        var largestArea = 0L
        while (stack.isNotEmpty() && visited++ < 320) {
            val (view, depth) = stack.removeLast()
            if (view is ImageView && view.isAttachedToWindow && view.isShown &&
                view.width >= root.width * 0.72f && view.height >= root.height * 0.35f
            ) {
                if (viewResourceEntryName(view) == "mia") imagePostContent = view
                val area = view.width.toLong() * view.height.toLong()
                if (area > largestArea) {
                    largestArea = area
                    largestImage = view
                }
            }
            if (view is ViewGroup && depth < 9) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        // Some image posts also contain an inactive SurfaceView. The current
        // Douyin image-post artwork is `mia`; its presence takes precedence
        // over the Surface when choosing which content should reach the edge.
        val image = imagePostContent ?: (if (!hasVideoSurface) largestImage else null) ?: return
        val rootLocation = IntArray(2).also(root::getLocationOnScreen)
        var current: View? = image
        while (current != null && current !== page) {
            if (current.width >= root.width * 0.72f &&
                current.height >= root.height * 0.35f) {
                val location = IntArray(2).also(current::getLocationOnScreen)
                val top = location[1] - rootLocation[1]
                val desiredHeight = root.height - top
                val params = current.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null && desiredHeight > current.height + (12f * density).roundToInt() &&
                    desiredHeight > 0) {
                    params.bottomMargin = 0
                    params.height = desiredHeight
                    douyinMarginTargets.remove(current)
                    current.layoutParams = params
                    current.requestLayout()
                }
            }
            current = current.parent as? View
        }
        if (image.scaleType != ImageView.ScaleType.CENTER_CROP) {
            image.scaleType = ImageView.ScaleType.CENTER_CROP
        }
    }

    private fun douyinContainsFullWidthPhoto(container: View, root: View): Boolean {
        val now = SystemClock.uptimeMillis()
        douyinFullWidthPhotoCache[container]?.let { (at, result) ->
            if (now - at < targetClassifyTtlMs) return result
        }
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += container to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 320) {
            val (view, depth) = stack.removeLast()
            if (view is ImageView && view.isAttachedToWindow && view.isShown &&
                view.width >= root.width * 0.72f && view.height >= root.height * 0.35f
            ) {
                douyinFullWidthPhotoCache[container] = now to true
                return true
            }
            if (view is ViewGroup && depth < 9) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        douyinFullWidthPhotoCache[container] = now to false
        return false
    }

    private fun douyinIsFeedContentFrame(view: View): Boolean =
        simpleNameOf(view.javaClass) == "MonitorScrollFrameLayout"

    private fun douyinIsVideoControlsRoot(view: View, idName: String? = null): Boolean {
        val name = idName ?: viewResourceEntryName(view)
        if (name == DOUYIN_CONTROLS_ROOT_ID || name == DOUYIN_CONTROLS_ROOT_ID_ALT) return true
        // 40.6.0 runtime records identify i6 as the actual controls root.
        // Its loading children (e.g. i-6) must never become a second lift owner.
        val parent = view.parent as? View ?: return false
        if (name == "i-6" && simpleNameOf(view.javaClass) == "MeasureOnceCheckDrawChildRelativeLayout" &&
            viewResourceEntryName(parent) == "i6" &&
            simpleNameOf(parent.javaClass) == "PenetrateTouchRelativeLayout") {
            val frame = parent.parent as? View ?: return false
            return simpleNameOf(frame.javaClass) == "MonitorScrollFrameLayout" &&
                (frame.parent as? View)?.let { simpleNameOf(it.javaClass) } == "VideoViewHolderRootView"
        }
        return name == "i6" && simpleNameOf(view.javaClass) == "PenetrateTouchRelativeLayout" &&
            simpleNameOf(parent.javaClass) == "MonitorScrollFrameLayout" &&
            (parent.parent as? View)?.let { simpleNameOf(it.javaClass) } == "VideoViewHolderRootView"
    }

    private fun douyinIsFeaturedFeedRoot(view: View): Boolean {
        val parent = view.parent as? View ?: return false
        return viewResourceEntryName(view) == "dir" &&
            viewResourceEntryName(parent) == "i=+" &&
            view.width >= (currentRoot?.width ?: 0) * 0.8f &&
                view.height >= (currentRoot?.height ?: 0) * 0.5f
    }

    private fun douyinIsUnderFeaturedFeedRoot(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (douyinIsFeaturedFeedRoot(current)) return true
            current = current.parent as? View
        }
        return false
    }

    private fun douyinIsUnderMessagePage(view: View): Boolean {
        var current: View? = view
        var hasMessageHost = false
        while (current != null) {
            when (viewResourceEntryName(current)) {
                "p0+" -> hasMessageHost = true
                "wt4" -> if (hasMessageHost) return true
            }
            current = current.parent as? View
        }
        return false
    }

    private fun douyinIsUnderLocalPage(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (simpleNameOf(current.javaClass) == "NearbySkyLightTouchEventFrameLayout") return true
            current = current.parent as? View
        }
        return false
    }

    private fun douyinIsLocalEmptyContent(view: View): Boolean {
        val root = currentRoot ?: return false
        if (view.width < root.width * 0.8f || view.height < root.height * 0.5f) {
            return false
        }
        var current: View? = view.parent as? View
        while (current != null) {
            if (simpleNameOf(current.javaClass) == "NearbyEmptyView") return true
            current = current.parent as? View
        }
        return false
    }

    private fun adjustDouyinShopBackToTop(
        root: ViewGroup,
        clearanceTop: Int,
        forceSearch: Boolean = false,
    ) {
        fun inShopContainer(view: View): Boolean {
            var current: View? = view
            while (current != null && current !== root) {
                if (viewResourceEntryName(current) == "pmr") return true
                current = current.parent as? View
            }
            return false
        }

        val previous = douyinShopBackToTopRef?.get()
        var button = previous?.takeIf { it.isAttachedToWindow && it.isShown &&
            inShopContainer(it) }
        val now = SystemClock.uptimeMillis()
        if (button == null && !forceSearch && now - douyinShopBackToTopLastSearchAt < 400L) {
            if (previous != null && previous.isAttachedToWindow) {
                previous.translationY = douyinShopBackToTopBaseTranslation
            }
            douyinShopBackToTopRef = null
            return
        }
        if (button == null) {
            douyinShopBackToTopLastSearchAt = now
            val stack = ArrayDeque<Pair<View, Int>>()
            stack += root to 0
            var visited = 0
            while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {
                val (view, depth) = stack.removeLast()
                if (view.isShown && view.contentDescription?.toString()?.contains("返回顶部") == true &&
                    view.width < root.width * 0.2f && inShopContainer(view)
                ) {
                    button = view.parent as? View ?: view
                    break
                }
                if (view is ViewGroup && depth < 24) {
                    for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
                }
            }
        }

        if (previous != null && previous !== button && previous.isAttachedToWindow &&
            kotlin.math.abs(previous.translationY - douyinShopBackToTopBaseTranslation) > 1f
        ) {
            previous.translationY = douyinShopBackToTopBaseTranslation
        }
        if (button == null) {
            douyinShopBackToTopRef = null
            return
        }
        if (button !== previous) {
            douyinShopBackToTopRef = WeakReference(button)
            douyinShopBackToTopBaseTranslation = button.translationY
        }

        val rootPos = IntArray(2).also(root::getLocationOnScreen)
        val buttonPos = IntArray(2).also(button::getLocationOnScreen)
        val originalBottom = buttonPos[1] - rootPos[1] + button.height -
            (button.translationY - douyinShopBackToTopBaseTranslation)
        val targetBottom = clearanceTop - (36f * density).roundToInt()
        val targetTranslation = douyinShopBackToTopBaseTranslation +
            (targetBottom - originalBottom).coerceAtMost(0f)
        if (kotlin.math.abs(button.translationY - targetTranslation) > 1f) {
            button.translationY = targetTranslation
        }
    }

    private fun douyinPageContainsCouponRoot(page: View): Boolean {
        var current = douyinCouponPageRef?.get()
            ?.takeIf { it.isAttachedToWindow && it.isShown }
        while (current != null) {
            if (current === page) return true
            current = current.parent as? View
        }
        return false
    }

    private fun douyinContainsGroupBuyContent(container: View): Boolean {
        val now = SystemClock.uptimeMillis()
        douyinGroupBuyContentCache[container]?.let { (at, result) ->
            if (now - at < targetClassifyTtlMs) return result
        }
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += container to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 350) {
            val (view, depth) = stack.removeLast()
            if (viewResourceEntryName(view) == "ehh") {
                douyinGroupBuyContentCache[container] = now to true
                return true
            }
            if (view is ViewGroup && depth < 12) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        douyinGroupBuyContentCache[container] = now to false
        return false
    }

    private fun adjustDouyinGroupBuyPage(root: ViewGroup) {
        if (!pageAdjustDue("groupBuy", SystemClock.uptimeMillis())) return
        // Same reason as the C2 feed page: no layout writes while a sheet animates.
        if (bottomInputRecentlyVisible) return
        var selected = false
        var content: View? = null
        var backToTop: View? = null
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {
            val (view, depth) = stack.removeLast()
            if (view.contentDescription?.toString()?.contains("已选中，团购") == true) {
                selected = true
            }
            if (viewResourceEntryName(view) == "ehh" && view.isShown &&
                view.width >= root.width * 0.9f) content = view
            if (viewResourceEntryName(view) == "jwn" && view.isShown && view.isClickable &&
                view.width in 1..(root.width * 0.2f).toInt()) {
                backToTop = view
            }
            if (view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        if (!selected) {
            douyinGroupBuyClipParentRef?.get()?.takeIf { it.isAttachedToWindow }?.let { parent ->
                if (parent.paddingBottom != douyinGroupBuyClipParentBottomPadding) {
                    parent.setPadding(parent.paddingLeft, parent.paddingTop, parent.paddingRight,
                        douyinGroupBuyClipParentBottomPadding)
                }
                parent.clipToPadding = douyinGroupBuyClipParentClipToPadding
            }
            douyinGroupBuyClipParentRef = null
            douyinGroupBuyBackToTopRef?.get()?.takeIf { it.isAttachedToWindow }?.let {
                it.translationY = douyinGroupBuyBackToTopBaseTranslation
            }
            douyinGroupBuyBackToTopRef = null
            return
        }
        val previousButton = douyinGroupBuyBackToTopRef?.get()
        if (backToTop !== previousButton) {
            if (previousButton != null && previousButton.isAttachedToWindow) {
                previousButton.translationY = douyinGroupBuyBackToTopBaseTranslation
            }
            douyinGroupBuyBackToTopRef = backToTop?.let { WeakReference(it) }
            douyinGroupBuyBackToTopBaseTranslation = backToTop?.translationY ?: 0f
        }
        backToTop?.let { button ->
            val rootPos = IntArray(2).also(root::getLocationOnScreen)
            val buttonPos = IntArray(2).also(button::getLocationOnScreen)
            val originalBottom = buttonPos[1] - rootPos[1] + button.height -
                (button.translationY - douyinGroupBuyBackToTopBaseTranslation)
            val visibleBarTop = root.height -
                currentBarHeightPx -
                (21f * density).roundToInt()
            val targetBottom = visibleBarTop - (8f * density).roundToInt()
            val targetTranslation = douyinGroupBuyBackToTopBaseTranslation +
                (targetBottom - originalBottom).coerceAtMost(0f)
            if (kotlin.math.abs(button.translationY - targetTranslation) > 1f) {
                button.translationY = targetTranslation
            }
        }
        val groupBuy = content ?: return
        (groupBuy.parent as? ViewGroup)?.let { parent ->
            val previousParent = douyinGroupBuyClipParentRef?.get()
            if (parent !== previousParent) {
                douyinGroupBuyClipParentRef = WeakReference(parent)
                douyinGroupBuyClipParentBottomPadding = parent.paddingBottom
                douyinGroupBuyClipParentClipToPadding = parent.clipToPadding
            }
            if (parent.paddingBottom != 0) {
                parent.setPadding(parent.paddingLeft, parent.paddingTop, parent.paddingRight, 0)
            }
            if (parent.clipToPadding) parent.clipToPadding = false
        }
        val rootPos = IntArray(2).also(root::getLocationOnScreen)
        val contentPos = IntArray(2).also(groupBuy::getLocationOnScreen)
        val oldBottom = contentPos[1] - rootPos[1] + groupBuy.height
        if (oldBottom >= root.height - (8f * density).roundToInt()) return

        fun fillToBottom(view: View) {
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val pos = IntArray(2).also(view::getLocationOnScreen)
            val desired = root.height - (pos[1] - rootPos[1])
            if (desired > 0 && (params.height != desired || params.bottomMargin != 0)) {
                params.height = desired
                params.bottomMargin = 0
                douyinMarginTargets.remove(view)
                view.layoutParams = params
                view.requestLayout()
            }
        }

        // The current group-buy page has two reserved native-tab gaps: its
        // outer frame ends at 2334px and the actual list ends at 2175px.
        // Expand the page frame and every full-width list layer sharing the
        // latter edge, without touching video controls or the progress bar.
        val outer = groupBuy.parent as? View
        if (outer != null && outer.width >= root.width * 0.9f) fillToBottom(outer)
        val descendants = ArrayDeque<Pair<View, Int>>()
        descendants += groupBuy to 0
        var count = 0
        while (descendants.isNotEmpty() && count++ < 500) {
            val (view, depth) = descendants.removeLast()
            val pos = IntArray(2).also(view::getLocationOnScreen)
            val bottom = pos[1] - rootPos[1] + view.height
            if (view.width >= root.width * 0.9f && view.height >= root.height * 0.25f &&
                kotlin.math.abs(bottom - oldBottom) <= (3f * density).roundToInt()) {
                fillToBottom(view)
            }
            if (view is ViewGroup && depth < 12) {
                for (index in 0 until view.childCount) descendants += view.getChildAt(index) to (depth + 1)
            }
        }
    }

    /**
     * Paints the current page's own bottom reserve away.
     *
     * Both Douyin pages that own a scrollable/video body keep the native bar's height plus the
     * gesture inset inside their layout, and the module hides the native chrome, so without this
     * the glass bar's backdrop is an empty band:
     *
     * - 消息: the page host (`jvx` / `wtv` / `p03`) ends at 2513 and the conversation list
     *   (`zna`, a RecyclerView) ends at 2341 — measured on 40.7.0 / 1272x2772.
     * - 朋友: the `androidx.viewpager.widget.ViewPager` that hosts the video pages is
     *   `[0,0][1272,2513]` under a `[0,0][1272,2772]` tab host, so the last 259px of the video is
     *   never drawn and reads as a black band.
     *
     * The anchors are found by the update pass' own walk ([update]) and published as weak refs,
     * so this function does not traverse the tree; that is also what makes the posted retry
     * walk-free. The old message-only code matched `zny` / `p0+`, none of which exist any more.
     *
     * The page's top genuinely moves: right after opening, the message page's story row (`bjp`,
     * 312px) is present and the list starts at 584; once it settles the row is gone and the list
     * starts at 272. A first-sight baseline therefore rejected every later pass ("top=272
     * base=584") and the page never filled. The chain compares with the previous pass instead:
     * a downward move is a pull-to-refresh drag and is left to Douyin, an upward move is the
     * page settling and is applied immediately. A posted retry ([scheduleDouyinPageRetry])
     * covers the idle page, where the module's power work stops drawing and no pre-draw arrives
     * for seconds.
     */
    private fun adjustDouyinPageBottom(root: ViewGroup) {
        if (!pageAdjustDue("pagebottom", SystemClock.uptimeMillis())) return
        // Stale refs mean the page is gone or was rebuilt; the next update pass re-discovers it.
        val messageList = douyinMessageListRef?.get()
            ?.takeIf { it.isAttachedToWindow && it.isShown }
        if (messageList != null) {
            val title = douyinMessageTitleRef?.get()
                ?.takeIf { it.isAttachedToWindow && it.isShown }
            // The header has to sit above the list it titles; this rejects a `tv_title` from a
            // conversation row or an overlay. It replaces the old `p0+` host marker.
            if (title != null && douyinTopInWindow(title) < douyinTopInWindow(messageList)) {
                applyDouyinPageBottom(root, messageList)
                return
            }
        }
        val pager = douyinBottomPagerRef?.get()
            ?.takeIf { it.isAttachedToWindow && it.isShown && it.height < root.height }
        if (pager != null) applyDouyinPageBottom(root, pager)
    }

    /** One bottom-fill attempt; resets the retry burst when the chain is at its target. */
    private fun applyDouyinPageBottom(root: ViewGroup, anchor: View) {
        if (extendDouyinPageChain(root, anchor)) douyinPageRetryCount = 0
        else scheduleDouyinPageRetry(root, anchor)
    }

    /**
     * Sets [anchor] and every full-width ancestor up to — but not including — the first container
     * that already spans the window to `root.height - top`, clearing the bottom margin/padding
     * that carry the native bar reserve.
     *
     * The stop is measured instead of named, so the app-wide frames (tab host, main pager) are
     * never resized and a future id rename cannot break it. Returns true when the chain is at its
     * target (nothing left to write and it did not move this pass), false when the caller should
     * retry after the layout pass.
     */
    private fun extendDouyinPageChain(root: ViewGroup, anchor: View): Boolean {
        val rootTop = douyinTopInWindow(root)
        val chainViews = douyinPageChainViews
        val chainTops = douyinPageChainTopValues
        chainViews.clear()
        var chainSize = 0
        var current: View? = anchor
        var levels = 0
        var pulledDown = false
        while (current != null && current !== root && levels++ < DOUYIN_PAGE_CHAIN_MAX_DEPTH) {
            val parent = current.parent as? ViewGroup ?: break
            if (current.width >= root.width * 0.9f) {
                val currentTop = douyinTopInWindow(current) - rootTop
                if (current !== anchor && currentTop <= 0 && current.height >= root.height) break
                val previousTop = douyinPageChainTops.put(current, currentTop)
                // A *downward* move is the pull-to-refresh / overscroll drag (Douyin owns those
                // frames). An upward move is the page settling and is applied at once.
                if (previousTop != null &&
                    currentTop > previousTop + (2f * density).roundToInt()
                ) {
                    pulledDown = true
                }
                chainViews.add(current)
                chainTops[chainSize++] = currentTop
            }
            current = parent
        }
        if (pulledDown) {
            chainViews.clear()
            return false
        }
        var pending = false
        for (index in 0 until chainSize) {
            val view = chainViews[index]
            val top = chainTops[index]
            val parent = view.parent as? ViewGroup ?: continue
            val desiredHeight = root.height - top
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null && desiredHeight > 0 &&
                (params.height != desiredHeight || params.bottomMargin != 0)) {
                pending = true
                params.height = desiredHeight
                params.bottomMargin = 0
                view.layoutParams = params
                view.requestLayout()
            }
            // The page expresses part of the native bar reserve as a padding on the host
            // (`p03` on 40.7.0), so the height alone would leave a strip inside the page.
            if (parent.paddingBottom != 0) {
                pending = true
                parent.setPadding(parent.paddingLeft, parent.paddingTop, parent.paddingRight, 0)
            }
            if (parent.clipToPadding) {
                pending = true
                parent.clipToPadding = false
            }
        }
        // Do not keep a page the user has left alive through this scratch list.
        chainViews.clear()
        return !pending
    }

    /**
     * Re-runs the page-bottom pass without waiting for a display frame.
     *
     * The module deliberately stops drawing on an idle page (the 2026-10-19/20 power work), so
     * the pre-draw that calls the adjuster can be seconds away right after the page opens —
     * measured on 40.7.0: 5.6s and 6.0s gaps, which is exactly the "band shows for a few seconds
     * at launch, then disappears" report. `postDelayed` lands on the UI thread regardless of
     * draws. It stops as soon as the chain is at its target heights (the counter is reset by a
     * pass with nothing to write) and is capped so a page that keeps moving cannot keep waking
     * the process.
     */
    private fun scheduleDouyinPageRetry(root: ViewGroup, anchor: View) {
        if (douyinPageRetryPending || douyinPageRetryCount >= DOUYIN_PAGE_RETRY_MAX) return
        douyinPageRetryPending = true
        douyinPageRetryCount++
        anchor.postDelayed({
            douyinPageRetryPending = false
            // The page may have been replaced while the message was queued.
            if (anchor.isAttachedToWindow && anchor.isShown) adjustDouyinPageBottom(root)
        }, DOUYIN_PAGE_RETRY_MS)
    }

    private fun adjustDouyinLocationCommentGap(root: ViewGroup) {
        // This is the most expensive check in the per-frame path: when the cached
        // comment sheet is stale it walks up to DOUYIN_LAYOUT_MAX_VIEWS views, calling
        // getResourceEntryName (a resource lookup) on each, and then resolves global
        // screen coordinates for up to 120 descendants of the card. The comment sheet's
        // geometry only changes when it is (re)laid out, so check it far less often than
        // the general 32ms cadence — otherwise every scroll frame of the comment list
        // pays for a full-tree walk.
        val gapNow = SystemClock.uptimeMillis()
        if (gapNow - lastCommentGapCheckAt < DOUYIN_COMMENT_GAP_INTERVAL_MS) return
        lastCommentGapCheckAt = gapNow
        fun isLocationComment(content: ViewGroup): Boolean {
            if (viewResourceEntryName(content) != "q68") return false
            var parent: View? = content.parent as? View
            while (parent != null) {
                if (simpleNameOf(parent.javaClass) == "CommentNestedLayout" &&
                    viewResourceEntryName(parent) == "root_layout") return true
                parent = parent.parent as? View
            }
            return false
        }
        fun children(content: ViewGroup): Triple<View, View, View>? {
            var card: View? = null
            var tabs: View? = null
            var pager: View? = null
            for (i in 0 until content.childCount) {
                val child = content.getChildAt(i)
                when (viewResourceEntryName(child)) {
                    "fqc" -> card = child
                    "fr_" -> tabs = child
                    "x7f" -> pager = child
                }
            }
            return if (card != null && tabs != null && pager != null) Triple(card, tabs, pager)
                else null
        }
        val rootPos = IntArray(2).also(root::getLocationOnScreen)
        var content = douyinLocationCommentRef?.get()?.takeIf { candidate ->
            candidate.isAttachedToWindow && candidate.isShown &&
                isLocationComment(candidate) && children(candidate) != null &&
                IntArray(2).also(candidate::getLocationOnScreen)[1] - rootPos[1] < root.height
        }
        if (content == null) {
            val stack = ArrayDeque<Pair<View, Int>>()
            stack += root to 0
            var visited = 0
            while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {
                val (view, depth) = stack.removeLast()
                if (view is ViewGroup && view.isShown && isLocationComment(view) &&
                    children(view) != null &&
                    IntArray(2).also(view::getLocationOnScreen)[1] - rootPos[1] < root.height) {
                    content = view
                    douyinLocationCommentRef = WeakReference(view)
                    break
                }
                if (view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH) {
                    for (i in 0 until view.childCount) stack += view.getChildAt(i) to (depth + 1)
                }
            }
        }
        val (card, tabs, _) = children(content ?: return) ?: return
        val params = card.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val cardTop = IntArray(2).also(card::getLocationOnScreen)[1]
        var visibleContentBottom = 0
        val descendants = ArrayDeque<Pair<View, Int>>()
        descendants += card to 0
        var examined = 0
        while (descendants.isNotEmpty() && examined++ < 120) {
            val (child, depth) = descendants.removeLast()
            if (child !== card && child.isShown && child.width > 0 && child.height > 0) {
                val meaningful = when (child) {
                    is android.widget.TextView -> !child.text.isNullOrBlank()
                    is android.widget.ImageView -> child.drawable != null
                    else -> false
                }
                if (meaningful) {
                    val bottom = IntArray(2).also(child::getLocationOnScreen)[1] + child.height
                    visibleContentBottom = maxOf(visibleContentBottom, bottom)
                }
            }
            if (child is ViewGroup && depth < 8) {
                for (i in 0 until child.childCount) descendants += child.getChildAt(i) to (depth + 1)
            }
        }
        val normalGap = (24f * density).roundToInt()
        val excessiveGap = (96f * density).roundToInt()
        if (visibleContentBottom > cardTop &&
            cardTop + card.height - visibleContentBottom > excessiveGap) {
            val desiredHeight = visibleContentBottom - cardTop + normalGap
            params.height = desiredHeight
            card.layoutParams = params
            card.requestLayout()
            return
        }
        val cardBottom = IntArray(2).also(card::getLocationOnScreen)[1] + card.height
        val tabsTop = IntArray(2).also(tabs::getLocationOnScreen)[1]
        val gap = tabsTop - cardBottom
        if (gap > excessiveGap && params.bottomMargin > excessiveGap) {
            params.bottomMargin = normalGap
            card.layoutParams = params
            card.requestLayout()
        }
    }

    private fun adjustDouyinCouponPage(
        root: ViewGroup,
        clearanceTop: Int,
        forceSearch: Boolean = false,
    ) {
        val rootLocation = IntArray(2).also(root::getLocationOnScreen)
        var coupon = douyinCouponPageRef?.get()?.takeIf { view ->
            view.isAttachedToWindow && view.isShown && view.width >= root.width * 0.85f &&
                IntArray(2).also(view::getLocationOnScreen).let { location ->
                    kotlin.math.abs(location[0] - rootLocation[0]) < root.width * 0.5f
                }
        }
        if (coupon == null && forceSearch) {
            val stack = ArrayDeque<Pair<View, Int>>()
            stack += root to 0
            var visited = 0
            while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {
                val (view, depth) = stack.removeLast()
                if (simpleNameOf(view.javaClass) == "0wNK" && view.isShown &&
                    view.width >= root.width * 0.85f) {
                    coupon = view
                    douyinCouponPageRef = WeakReference(view)
                    break
                }
                if (view is ViewGroup && depth < 24) {
                    for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
                }
            }
        }
        val page = coupon ?: return
        val pageLocation = IntArray(2).also(page::getLocationOnScreen)
        val pageTop = pageLocation[1] - rootLocation[1]
        val barTop = clearanceTop - (21f * density).roundToInt()
        val desiredHeight = (barTop - pageTop).coerceAtLeast(1)
        val params = page.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (kotlin.math.abs(params.height - desiredHeight) > 1 || params.bottomMargin != 0) {
            params.height = desiredHeight
            params.bottomMargin = 0
            page.layoutParams = params
            page.requestLayout()
        }
    }

    /**
     * Whether Douyin has taken its own bottom bar off screen.
     *
     * Delegates to the shared judgement in `DouyinNavigationState.kt`, so the lift release
     * and the adapter's page gate can never disagree about the same measurement.
     */
    private fun douyinNativeBarOffScreen(host: View): Boolean {
        val source = (host as? GlassHostLayout)?.navigationSource ?: return false
        return io.github.xzdw.dylt.hook.adapters.douyin.douyinNativeBarOffScreen(
            source,
            nativeBarProbeRect,
        )
    }

    /** Scratch rect for the feed-page presence check; never retained. */
    private val feedPageProbeRect = Rect()

    /**
     * Whether the feed's own page is still rendered on screen.
     *
     * Douyin hides its bottom bar for two very different reasons, and only one of them means a
     * lifted control has nothing left to clear:
     *
     * - an overlay covers the bar — the comment panel, the chat page, the comment input — while
     *   the feed page stays right behind it;
     * - a pushed page (author profile, video detail, photo flow) pages the whole tab host, feed
     *   included, off screen.
     *
     * The lift release used to be gated on the bar alone, so every comment-panel open released
     * the lifts and the caption / action cluster / progress bar dropped to their native
     * position, then jumped back when the panel closed. Measured on the device: the comment
     * panel contains **no** `EditText` at all, so the bottom-input latch never covered it.
     *
     * Pages are the pager's own children, the same notion [douyinWriteDiagDump] prints, and the
     * rendered rect is the only signal that follows Douyin's paging (see
     * `DouyinNavigationState.douyinNativeBarOffScreen`).
     */
    private fun douyinFeedPageOnScreen(): Boolean {
        val pager = douyinFeedPager?.get() as? ViewGroup ?: return false
        if (!pager.isAttachedToWindow || pager.visibility != View.VISIBLE) return false
        if (pager.width <= 0 || pager.height <= 0) return false
        val rect = feedPageProbeRect
        for (index in 0 until pager.childCount) {
            val page = pager.getChildAt(index) ?: continue
            if (!page.isShown || page.width <= 0 || page.height <= 0) continue
            if (!page.getGlobalVisibleRect(rect)) continue
            val minWidth = (page.width * DOUYIN_FEED_PAGE_MIN_VISIBLE_FRACTION).roundToInt()
            val minHeight = (page.height * DOUYIN_FEED_PAGE_MIN_VISIBLE_FRACTION).roundToInt()
            if (rect.width() >= minWidth && rect.height() >= minHeight) return true
        }
        return false
    }


    /**
     * Views already checked for "lives inside a comment sheet".
     *
     * A view's ancestry does not change in practice, so the answer is memoised: the check walks
     * up to [DOUYIN_LIFT_ANCESTRY_MAX] ancestors and reads `javaClass.simpleName` on each, and
     * the lift candidates are re-evaluated every pass.
     */
    private val douyinSheetLiftMemo = WeakHashMap<View, Boolean>()

    /**
     * Whether this view belongs to a comment sheet rather than to the feed page.
     *
     * The sheet's card (`fqc`) sits in a video page's tree with real content, so the controls-root
     * heuristics lift it; the sheet then lays itself out with that bottom margin and leaves an
     * empty band above its comment tabs. Measured on the device: a 334px lift on `fqc`, visible
     * as the gap the user reported once the pin stopped releasing it.
     */
    private fun douyinIsInsideCommentSheet(view: View): Boolean =
        douyinSheetLiftMemo.getOrPut(view) {
            var node: View? = view.parent as? View
            var depth = 0
            while (node != null && depth < DOUYIN_LIFT_ANCESTRY_MAX) {
                if (simpleNameOf(node.javaClass) == "CommentNestedLayout") return@getOrPut true
                node = node.parent as? View
                depth++
            }
            false
        }

    /**
     * Gives back every lift that landed on an overlay's own content or on a page-level
     * container.
     *
     * Lifts that live *inside* a page stay pinned — that is what keeps the caption / action
     * cluster from dropping when the comment panel opens — while the overlay's own content (the
     * comment sheet lays itself out from that margin) and page-level containers go back. The
     * old bar-away release cleared everything and hid the page-level misclassification; pinning
     * had to keep clearing it or the author profile showed a 258px black band.
     *
     * Runs while an overlay covers the bar (see [update]): the page's lifts stay pinned, while
     * anything lifted inside a comment sheet is released so the sheet can lay out normally.
     */
    /**
     * Whether a lifted view sits at *page* level: one of the pager's page slots, or a direct
     * child of one.
     *
     * A page-level container is the page itself or its page-wide chrome, never the video cluster
     * inside it — measured on the device, the author-profile page's root
     * (`FpsControlFrameLayout/wl=`, the reported black band) is one hop below its slot, while
     * the feed's overlay root is more than eight. Lifting a page shortens it and leaves the
     * window background showing underneath, so those lifts are never pinned.
     */
    private fun douyinIsPageLevelLift(view: View): Boolean {
        val pager = douyinFeedPager?.get() as? ViewGroup ?: return false
        var node: View? = view
        var depth = 0
        while (node != null && depth <= DOUYIN_PAGE_LEVEL_MAX) {
            for (index in 0 until pager.childCount) {
                if (pager.getChildAt(index) === node) return true
            }
            node = node.parent as? View
            depth++
        }
        return false
    }

    private fun douyinReleaseOverlayLifts() {
        if (douyinMarginTargets.isEmpty()) return
        var released = 0
        val iterator = douyinMarginTargets.entries.iterator()
        while (iterator.hasNext()) {
            val (view, margin) = iterator.next()
            if (!douyinIsInsideCommentSheet(view) && !douyinIsPageLevelLift(view)) continue
            DouyinControlMarginGuard.release(view)
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null && params.bottomMargin == margin) {
                params.bottomMargin = 0
                view.layoutParams = params
                view.requestLayout()
            }
            iterator.remove()
            released++
        }
        if (released > 0) {
        }
    }

    /**
     * The comment sheet's container, remembered by the per-pass walk that already reads its id.
     *
     * Needed for the lift freeze: while a sheet is up, the page behind it is laid out *for the
     * sheet*, so a lift target computed from that geometry lands the cluster too low once the
     * sheet leaves. The bar-away state cannot cover the close animation (the bar is already
     * back) and neither can the bottom-input latch — the comment panel contains no `EditText`
     * at all (measured on the device).
     */
    private var douyinSheetRef: WeakReference<ViewGroup>? = null

    private var douyinSheetSeenAt = 0L

    /** Scratch rect for the sheet-presence check; never retained. */
    private val sheetProbeRect = Rect()

    /**
     * Whether a comment sheet is on screen, or was within the close-animation guard.
     *
     * The guard window is what keeps the lift frozen while the sheet animates out: it stops
     * reporting a visible rect partway through that animation while the page behind it is still
     * sized for it. Self-healing by construction — a sheet view the app left laid out but
     * invisible cannot freeze the lifts beyond the window.
     */
    private fun douyinCommentSheetUp(): Boolean {
        val now = SystemClock.uptimeMillis()
        val sheet = douyinSheetRef?.get()
        if (sheet != null && sheet.isAttachedToWindow && sheet.isShown &&
            sheet.width > 0 && sheet.height > 0 && sheet.alpha >= DOUYIN_SHEET_MIN_ALPHA &&
            sheet.getGlobalVisibleRect(sheetProbeRect) &&
            sheetProbeRect.height() >= (sheet.height * DOUYIN_SHEET_MIN_VISIBLE_FRACTION).toInt()
        ) {
            douyinSheetSeenAt = now
            return true
        }
        return douyinSheetSeenAt != 0L && now - douyinSheetSeenAt <= DOUYIN_SHEET_FREEZE_GUARD_MS
    }

    /** Container-to-lowest-widget inset per controls container, refreshed on a short TTL. */
    private val visibleInsetCache = java.util.WeakHashMap<View, LongArray>()

    /**
     * Distance between [container]'s bottom edge and the bottom of the lowest shown widget
     * inside it, i.e. the overlay's internal bottom space.
     *
     * Both edges are read in the same frame, so a pending lift margin and the pager's
     * animation cancel out and the result stays valid while a page is still settling — the
     * reason this is cached as an inset instead of as an absolute position. The subtree walk
     * visits hundreds of views, hence the TTL.
     */
    private fun douyinVisibleInset(
        container: View,
        rootPos: IntArray,
        currentBottom: Int,
    ): Int? {
        val now = SystemClock.uptimeMillis()
        val cached = visibleInsetCache[container]
        if (cached != null && now - cached[0] <= DOUYIN_VISIBLE_BOTTOM_TTL_MS) {
            return cached[1].toInt()
        }
        val leaf = douyinVisibleSubtreeBottom(container, rootPos, StringBuilder())
        val inset = if (leaf > 0) (currentBottom - leaf).coerceAtLeast(0) else -1
        // A page that is being attached or re-laid out reports nothing (no shown widget) or
        // nonsense (a widget spanning the whole overlay). Keep the last good measurement
        // instead of dropping the cap: with the pager mid-transition the measurement
        // alternates between valid and invalid, and falling back to the container edge on
        // every invalid frame made the lift oscillate 85 -> 263 -> 85 and visibly yank the
        // controls up and down.
        if (inset <= 0 || inset > container.height / 2) return cached?.get(1)?.toInt()
        visibleInsetCache[container] = longArrayOf(now, inset.toLong())
        return inset
    }

    /** Bottom edge of the lowest shown *leaf* (real widget) inside [view]; -1 if none. */
    private fun douyinVisibleSubtreeBottom(view: View, rootLoc: IntArray, label: StringBuilder): Int {
        val loc = IntArray(2)
        var bottom = Int.MIN_VALUE
        val stack = ArrayDeque<View>()
        stack += view
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 600) {
            val node = stack.removeLast()
            val leaf = node !is ViewGroup || node.childCount == 0
            if (leaf && node.isShown && node.width > 0 && node.height > 0) {
                node.getLocationOnScreen(loc)
                val nodeBottom = loc[1] - rootLoc[1] + node.height
                if (nodeBottom > bottom) {
                    bottom = nodeBottom
                    label.setLength(0)
                    label.append(simpleNameOf(node.javaClass)).append(':')
                        .append((node as? android.widget.TextView)?.text?.toString()?.take(8)
                            ?: node.contentDescription?.toString()?.take(8) ?: "-")
                        .append('@').append(node.width).append('x').append(node.height)
                }
            }
            if (node is ViewGroup) {
                for (index in 0 until node.childCount) stack += node.getChildAt(index)
            }
        }
        return if (bottom == Int.MIN_VALUE) -1 else bottom
    }

    /**
     * Gives back the bottom-margins this adapter reserved for the floating bar.
     *
     * Runs while Douyin has paged its own tab host (and therefore its bar) off screen, so
     * a lifted control has nothing to clear and the reserved strip would simply show the
     * window background. Each margin is only reset when it still holds the adapter's own
     * value, and [DouyinControlMarginGuard] ownership is dropped so the guard cannot
     * restore it on the next layout pass.
     *
     * The progress bar is the one record that survives: its lift is a function of the bar
     * height alone and Douyin rebuilds its view while the bar pages away, so releasing it here
     * was what sank it. See the loop below.
     */
    private fun douyinReleaseBottomReservations() {
        if (douyinMarginTargets.isEmpty()) return
        val iterator = douyinMarginTargets.entries.iterator()
        while (iterator.hasNext()) {
            val (view, margin) = iterator.next()
            // The progress bar keeps its reservation. It is the one control whose lift is a
            // function of the bar height alone, and Douyin rebuilds its view when the bar pages
            // away — releasing the record here is what let the fresh view sit at the window
            // bottom (the next pass had nothing to restore). Every other control still gives its
            // margin back while the bar is away.
            if (view === douyinCachedProgressRef?.get() || douyinIsProgressContainer(view)) {
                // Keep the live progress bar's reservation, but not a view Douyin has already
                // replaced. A stale record would leave DouyinControlMarginGuard armed
                // (`ownedCount > 0`) for a detached view, costing the requestLayout hook its
                // "nothing owned" fast path until the GC clears the weak entry.
                if (view.isAttachedToWindow) continue
                DouyinControlMarginGuard.release(view)
                iterator.remove()
                continue
            }
            DouyinControlMarginGuard.release(view)
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null && params.bottomMargin == margin) {
                params.bottomMargin = 0
                view.layoutParams = params
                view.requestLayout()
            }
            iterator.remove()
        }
    }

    /** Scratch rect for the per-pass native-bar presence check; never retained. */
    private val nativeBarProbeRect = Rect()

    /**
     * Raise a floating control by growing its bottom margin instead of `translationY`, so
     * Douyin's own per-frame animations cannot overwrite it. The lift derives from the layout
     * position without the current margin, which keeps the target self-stable frame to frame.
     */
    private fun liftDouyinControlByMargin(
        view: View,
        root: View,
        clearanceTop: Int,
        extraLiftPx: Int = 0,
        lockLift: Boolean = true,
    ) {

        // The group-buy (附近) page and the 精选 / 经验 C2 pages carry the module's own lift on
        // their content layer, which is what turns the app's own reserve into the reported big
        // band. Release it for those pages only: every check lives inside this candidate's own
        // subtree (plus the page chain for the C2 case), so no other pager page can be touched.
        if (douyinIsGroupBuyPageContainer(view) || douyinIsC2FeedPageContainer(view)) {
            // Both page families also drop the app's 183px native reserve, each located by its
            // own marker inside this page only.
            if (douyinIsGroupBuyPageContainer(view)) {
                releaseDouyinPageNativeReserve(view, setOf("ehd"))
            } else {
                releaseDouyinPageNativeReserve(view, DOUYIN_C2_FEED_CONTAINER_IDS)
                // The top edge's counterpart of that reserve: the strip the C2 pages keep for
                // Douyin's title row, released while the module's top copy draws that row.
                if (topBarOwnsTitleRow()) {
                    releaseDouyinPageTopReserve(view, DOUYIN_C2_FEED_CONTAINER_IDS)
                }
            }
            DouyinControlMarginGuard.release(view)
            val previousLift = douyinMarginTargets.remove(view)
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            if (previousLift != null && params?.bottomMargin == previousLift) {
                params.bottomMargin = 0
                view.requestLayout()
            }
            return
        }

        // Featured-feed and message-page chrome never lifts. The live-preview exclusion is
        // part of 控件下移: with the switch on, a preview page's own nested control layers
        // (author row, caption) stay where Douyin put them; with it off they are lifted like
        // any other page, which is what pushed the whole cluster up over the preview.
        if (douyinIsInsideCommentSheet(view)) {
            // Comment-sheet content is not a video control: lifting its card leaves the sheet
            // laid out with a stale margin, which shows as an empty band above its tabs.
            DouyinControlMarginGuard.release(view)
            val previousLift = douyinMarginTargets.remove(view)
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            if (previousLift != null && params?.bottomMargin == previousLift) {
                params.bottomMargin = 0
                view.layoutParams = params
                view.requestLayout()
            }
            return
        }
        if (            (douyinIsUnderFeaturedFeedRoot(view) || douyinIsUnderMessagePage(view) ||
                (controlDropEnabled && douyinIsUnderLivePreview(view)))) {
            DouyinControlMarginGuard.release(view)
            val previousLift = douyinMarginTargets.remove(view)
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            if (previousLift != null && params?.bottomMargin == previousLift) {
                params.bottomMargin = 0
                view.layoutParams = params
            }
            return
        }

        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0 ||
            (                (viewResourceEntryName(view) in setOf("s3m", "i=-") ||
                    douyinIsFeaturedFeedRoot(view) ||
                    douyinIsLocalEmptyContent(view)))
        ) return

        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return

        val gap = (DOUYIN_CONTROL_SAFE_GAP_DP * density).roundToInt()

        val desiredBottom = clearanceTop - gap - extraLiftPx

        val pos = IntArray(2).also { view.getLocationOnScreen(it) }

        val rootPos = IntArray(2).also { root.getLocationOnScreen(it) }
        // Vertical offset of the video page owning this control (0 when the
        // page is settled in the viewport). Measuring against the settled
        // page instead of the live position keeps the lift margin constant
        // while the page scrolls, so the controls ride the video smoothly
        // instead of stepping down at every lock release mid-swipe.
        val pageOffset = douyinVideoPageOffset(view, rootPos)
        // The progress bar is the one control the "视频控件" rules used to exclude on purpose:
        // its lift is measured from the track, never locked, and it carries no page geometry.
        // Keep those rules off it (`ownedVideoControls` below), but hand it to the guard so
        // Douyin's own relayouts cannot zero its margin between passes — that is what sank it
        // when a video switched or the comment panel toggled.
        val progressView = view === douyinCachedProgressRef?.get() || douyinIsProgressContainer(view)
        val ownedVideoControls = douyinIsVideoControlsRoot(view) ||
            douyinPageControlsCache.values.any { it === view }
        val guardOwns = ownedVideoControls || progressView

        val bottomNoMargin = if (lp.height == ViewGroup.LayoutParams.MATCH_PARENT) {
            // Full-height overlay: removing the margin restores the parent
            // bottom, so the lift target must be measured against the parent
            // edge instead of the current (already-margined) position.
            val parentBottom = (view.parent as? View)?.let { p ->
                val ploc = IntArray(2).also { p.getLocationOnScreen(it) }
                ploc[1] - rootPos[1] + p.height
            }
            (parentBottom ?: (pos[1] - rootPos[1] + view.height - lp.bottomMargin)) - pageOffset
        } else {
            // Bottom-anchored, fixed-height controls move up by their margin.
            // Add it back to recover the unlifted edge; subtracting it causes
            // a 0 -> lift -> 0 oscillation. Keep the progress path unchanged.
            val marginCorrection = if (ownedVideoControls) lp.bottomMargin else -lp.bottomMargin
            pos[1] - rootPos[1] + view.height + marginCorrection - pageOffset
        }

        val liftFromContainer = (bottomNoMargin - desiredBottom).coerceAtLeast(0)

        // A full-height controls overlay spans to the window edge while the widgets it
        // carries (caption, author row, action column) end well above that edge, and
        // Douyin lines those widgets up with the top of its own tab bar. Lifting by the
        // container's edge therefore overshoots by the overlay's internal bottom space:
        // measured on the feed the container bottom sat at 2772 while the lowest widget
        // ended at 2594 (against a native tab-bar top of 2589), so the container-based
        // 263px lift parked the whole cluster ~180px above the bar.
        //
        // Cap the lift by the widget edge. The cap uses the container-to-widget *inset*
        // rather than an absolute position: the inset is a property of the page template
        // (both edges move together while the pager animates and while a lift is applied),
        // so switching videos no longer computes a container-sized lift for the settling
        // page and then yanks the cluster down once the page has settled.
        val visibleBound = if (ownedVideoControls && controlDropEnabled) {
            val currentBottom = pos[1] - rootPos[1] + view.height
            douyinVisibleInset(view, rootPos, currentBottom)
                ?.let { (bottomNoMargin - it - desiredBottom).coerceAtLeast(0) }
        } else null
        val lift = when {
            // 控件下移 off: the pre-fix rule, and nothing else — the whole cluster rides the
            // container edge, which is what leaves the empty band under the widgets.
            !controlDropEnabled -> liftFromContainer
            visibleBound != null -> liftFromContainer.coerceAtMost(visibleBound)
            // No usable measurement yet. A page that is still being attached reports no
            // shown widget at all, and lifting by the container edge for those frames is
            // what yanked the controls up and then pulled them down (measured on a
            // 消息 -> 首页 switch: 0 -> 263 -> 85). Keep the lift that is already applied,
            // and otherwise leave Douyin's own layout untouched; the measurement arrives
            // within a frame or two and applies the real value.
            ownedVideoControls -> douyinMarginTargets[view] ?: 0
            else -> liftFromContainer
        }

        val target = if (lift <= 0) 0 else lift.coerceAtMost((220f * density).toInt())

        val locked = douyinMarginTargets[view]

        // Zero is never locked: a stale zero target measured before layout

        // settled must be recomputed every frame until the control lifts.
        //
        // The progress track is never locked either. Its 13dp 控件下移 sink is smaller than
        // this 24dp dead zone, so a lock taken before the track child existed (extra = 0)
        // used to survive: the bar hid, the reservations were released, and the next pass
        // recomputed the sunk value — the track jumped ~13dp up into the caption while the
        // cluster had already settled, which is the overlap users see after closing the
        // comment sheet or coming back from the profile page. Always recomputing keeps the
        // track identical at launch and after any hide/show cycle.
        val lockable = lockLift && !douyinIsProgressContainer(view) &&
            view !== douyinCachedProgressRef?.get()

        if (lockable && locked != null && locked > 0 &&

            kotlin.math.abs(target - locked) < (24f * density).toInt()

        ) {

            // Stay locked; re-apply only if the app reset the margin.
            if (ownedVideoControls) DouyinControlMarginGuard.own(view, locked)

            if (lp.bottomMargin != locked) {

                lp.bottomMargin = locked

                view.requestLayout()

            }

            return

        }

        if (guardOwns) DouyinControlMarginGuard.own(view, target)
        if (lp.bottomMargin != target) {
        }
        if (lp.bottomMargin != target) {

            lp.bottomMargin = target

            view.requestLayout()

        }

        if (target > 0) douyinMarginTargets[view] = target

        else douyinMarginTargets.remove(view)

    }

    /**
     * Vertical offset of the video page that owns [control], relative to
     * the root: 0 when the page is settled in the viewport, non-zero
     * mid-swipe. Measuring against the settled page keeps the lift margin
     * constant while the page scrolls, so the controls ride the video
     * smoothly instead of stepping down at every lock release.
     */
    private fun douyinVideoPageOffset(control: View, rootPos: IntArray): Int {
        var page: View? = control
        var cur = control.parent
        while (cur is View) {
            val cv = cur
            if (cv is ViewGroup && (classNameOf(cv.javaClass).contains("ViewPager") ||
                classNameOf(cv.javaClass).contains("Pager"))
            ) {
                val loc = IntArray(2).also { page!!.getLocationOnScreen(it) }
                return loc[1] - rootPos[1]
            }
            page = cv
            cur = cv.parent
        }
        return 0
    }

    /**

     * The ID-based control root sometimes also wraps the full-screen video

     * Surface. Padding it as a whole lifts the video with the controls, leaving

     * a void above the screen bottom. Drilling down to the innermost descendant

     * that still owns the bottom controls but excludes the video Surface keeps

     * the video pinned to the screen edge while only the controls rise.

     */

    private fun findPageControlsContainer(page: View, root: View): View? {

        if (page !is ViewGroup || root.width <= 0 || root.height <= 0) return null

        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }

        val maxCompact = (220f * density).toInt()

        val anchored = (40f * density).toInt()

        var best: View? = null

        var bestScore = Int.MIN_VALUE

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += page to 0

        var visited = 0

        while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {

            val (view, depth) = stack.removeLast()

            if (view !== page && view is ViewGroup &&

                depth < DOUYIN_LAYOUT_MAX_DEPTH && view.visibility == View.VISIBLE &&

                view.alpha > 0.001f && view.width >= root.width * 0.9f &&

                !douyinIsProgressContainer(view) &&

                !douyinIsFeedContentFrame(view) &&

                simpleNameOf(view.javaClass) != "MainBottomTabContainer"

            ) {

                val loc = IntArray(2).also { view.getLocationInWindow(it) }

                val bottom = loc[1] - rootLoc[1] + view.height

                if (bottom < root.height - anchored) continue

                if (!douyinContainerHostsVideoSurface(view, root)) {

                    val count = douyinBottomCompactChildCount(view, root, rootLoc, maxCompact)

                    if (count >= 1) {

                        val score = count * 100 - depth

                        if (score > bestScore) {

                            bestScore = score

                            best = view

                        }

                    }

                }

            }

            if (view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH) {

                for (k in 0 until view.childCount) stack += view.getChildAt(k) to (depth + 1)

            }

        }

        return best

    }

    private fun douyinIsProgressContainer(view: View): Boolean {
        val name = classNameOf(view.javaClass)
        if (view is SeekBar || name.contains("SeekBar", ignoreCase = true) ||
            name.contains("Progress", ignoreCase = true) ||
            name.contains("Scrubber", ignoreCase = true)
        ) return true

        val idName = viewResourceEntryName(view)
        return idName == DOUYIN_PROGRESS_ID || idName == DOUYIN_PROGRESS_ID_ALT ||
            idName in DOUYIN_PROGRESS_NAME_SET
    }

    /**

     * Geometry fallback for the overlay-control lift. Douyin obfuscates

     * resource ids between builds, so the id scan can miss entirely. A

     * full-size container that owns compact bottom controls and does not

     * host the video surface matches the feed overlay root signature.

     * Prefers a full-width sibling of the feed pager: page-agnostic, so it

     * is not rebuilt when the user swipes and the lift target stays fixed.

     */

    private fun douyinContainerHostsVideoSurface(container: View, root: View): Boolean {
        return videoContainerProbe.getOrPut(container) { findVideoContentInContainer(container, root) }
    }

    private fun findVideoContentInContainer(container: View, root: View): Boolean {

        val stack = ArrayDeque<View>()

        stack += container

        var visited = 0

        while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {

            val view = stack.removeLast()

            if ((view is SurfaceView || view is TextureView &&
                    classNameOf(view.javaClass) == "com.ss.android.ugc.playerkit.videoview.KeepSurfaceTextureView") &&
                view.width >= root.width * 0.6f &&

                view.height >= root.height * 0.5f

            ) return true

            if (view is ViewGroup) {

                for (i in 0 until view.childCount) stack += view.getChildAt(i)

            }

        }

        return false

    }

    private fun douyinBottomCompactChildCount(

        container: View,

        root: View,

        rootLoc: IntArray,

        maxCompact: Int,

    ): Int {

        // Same reasoning as [douyinContainerHostsVideoSurface]: this is a full-tree walk
        // that also resolves window coordinates for every candidate child, and its
        // callers sit inside other full-tree walks. Cache it per container, keyed by
        // maxCompact so a caller with a different bound cannot read a stale number.
        val cached = compactChildProbe[container]

        if (cached != null && cached[0] == maxCompact) return cached[1]

        val count = countDouyinBottomCompactChildren(container, root, rootLoc, maxCompact)

        compactChildProbe[container] = intArrayOf(maxCompact, count)

        return count

    }

    private fun countDouyinBottomCompactChildren(

        container: View,

        root: View,

        rootLoc: IntArray,

        maxCompact: Int,

    ): Int {

        var count = 0

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += container to 0

        var visited = 0

        while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {

            val (view, depth) = stack.removeLast()

            if (view !== container && view.visibility == View.VISIBLE &&

                view.alpha > 0.001f && view.width > 0 && view.height > 0

            ) {

                if (view.height <= maxCompact && view.width < root.width * 0.95f) {

                    val loc = IntArray(2).also { view.getLocationInWindow(it) }

                    val bottom = loc[1] - rootLoc[1] + view.height

                    if (bottom >= root.height * 0.55f && bottom <= root.height) count++

                }

            }

            if (view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH) {

                for (i in 0 until view.childCount) stack += view.getChildAt(i) to (depth + 1)

            }

        }

        return count

    }

    private fun douyinContainsView(container: View, descendant: View): Boolean {

        var current: View? = descendant

        while (current != null) {

            if (current === container) return true

            current = current.parent as? View

        }

        return false

    }

    fun dispose() {
        douyinMarginTargets.keys.forEach(DouyinControlMarginGuard::release)
        videoContainerProbe.clear()
        feedCornerMasks.dispose()
        douyinSpecialPageLayoutListener?.let { listener ->
            runCatching { (douyinFeedPager?.get()?.rootView as? View)?.viewTreeObserver?.removeOnGlobalLayoutListener(listener) }
        }
        douyinSpecialPageLayoutListener = null
    }

    /**
     * id -> resource entry name. getResourceEntryName is a resource lookup, and the
     * per-frame walks call it once per visited view (up to DOUYIN_LAYOUT_MAX_VIEWS per
     * walk, several walks per frame). The mapping is fixed for the process lifetime, so
     * cache it. Misses are cached as "" because they are just as common and just as
     * expensive to re-resolve.
     */
    private val resourceNameCache = HashMap<Int, String>(128)

    /** Per-key timestamps for [pageAdjustDue]. */
    private val pageAdjustStamps = HashMap<String, Long>(8)

    /**
     * Gate for the page-level geometry fixes that unconditionally walk the view tree.
     * Each such walk visits up to DOUYIN_LAYOUT_MAX_VIEWS views, and a page's layout only
     * changes when that page is (re)laid out — running them on the general 32ms cadence
     * added several pointless full-tree walks per frame on the UI thread.
     */
    private fun pageAdjustDue(key: String, now: Long): Boolean {
        val last = pageAdjustStamps[key] ?: 0L
        if (now - last < DOUYIN_PAGE_ADJUST_INTERVAL_MS) return false
        pageAdjustStamps[key] = now
        return true
    }

    private fun viewResourceEntryName(view: View): String? {
        val id = view.id
        if (id == View.NO_ID) return null
        val cached = resourceNameCache[id]
        if (cached != null) return cached.ifEmpty { null }
        val name = runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
        resourceNameCache[id] = name ?: ""
        return name
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        repeat(12) {
            when (current) {
                is Activity -> return current as Activity
                is ContextWrapper -> current = (current as ContextWrapper).baseContext
                else -> return null
            }
        }
        return null
    }

    private companion object {
        const val DOUYIN_LAYOUT_ADJUST_INTERVAL_MS = 96L

        /**
         * Slower cadence while a bottom sheet (comments, chat) is on screen: its open/close
         * animation rewrites the page geometry every frame, and the module's own height/margin
         * writes during that time are both invisible (the sheet covers the page) and a direct
         * source of dropped frames.
         */
        const val DOUYIN_SHEET_ADJUST_INTERVAL_MS = 100L

        /**
         * Content containers of the 精选 / 经验 (C2 feed) pages, one per pager page. Measured live
         * on the device; the ids are obfuscated and rotate, so older ones stay listed.
         */
        val DOUYIN_C2_FEED_CONTAINER_IDS = setOf("dit", "dg5", "dk9")

        /**
         * The same markers plus the 精选 page's own `dir` root: the C2 pages are located by their
         * content container and the featured feed by its page root, so the top-reserve release is
         * asked for both. Hoisted so that both callers, and the walk that collects them, name the
         * same set instead of each building its own.
         */
        val DOUYIN_TOP_RESERVE_MARKER_IDS = DOUYIN_C2_FEED_CONTAINER_IDS + "dir"

        /**
         * Lifetime of the per-container probe caches ([videoContainerProbe],
         * [compactChildProbe]). Each miss costs a full-tree walk, and the callers sit
         * inside other walks, so these must outlive a single update pass by a wide
         * margin. 768ms is ~8 passes: long enough to make the caches pay off, short
         * enough that a structural change is picked up without a visible delay.
         */
        const val DOUYIN_CONTAINER_PROBE_TTL_MS = DOUYIN_LAYOUT_ADJUST_INTERVAL_MS * 8
        /**
         * Cadence for the page-level geometry fixes that unconditionally walk the view
         * tree. A page's layout only changes when that page is (re)laid out, so these do
         * not need the general 32ms cadence.
         */
        const val DOUYIN_PAGE_ADJUST_INTERVAL_MS = 200L
        /**
         * The comment sheet's card/tab gap only changes on layout, so this walk runs at
         * roughly a fifth of the general cadence. At 32ms each comment-list scroll frame
         * paid for a full view-tree walk plus ~120 global coordinate lookups.
         */
        const val DOUYIN_COMMENT_GAP_INTERVAL_MS = 160L

        /**
         * How long a page's controls-root validation is trusted. The guards it covers are full
         * subtree walks, so re-running them every pass cost `discover` 2.3ms mean / 5ms peak on
         * device while the comment sheet scrolled. Long enough to make them worth caching, short
         * enough that a page really swapping its controls layer is picked up in a few frames.
         */
        const val DOUYIN_VALIDATED_CONTROLS_TTL_MS = 250L

        /**
         * Safety net for the controls-root discovery: even with an unchanged page-count
         * signature it runs this often, so a page that swaps its own controls layer in place
         * without changing the child count is still picked up. 700ms is ~85 frames of scrolling
         * and far below the time a user needs to notice a control that has not been lifted yet.
         */
        const val DOUYIN_DISCOVER_INTERVAL_MS = 700L
        const val DOUYIN_LAYOUT_MAX_VIEWS = 8_192

        /** Capacity of the reused per-pass walk stack; see [douyinWalkViews]. */
        const val DOUYIN_WALK_STACK_CAPACITY = DOUYIN_LAYOUT_MAX_VIEWS
        const val DOUYIN_LAYOUT_MAX_DEPTH = 24

        /**
         * How far the bottom-fill chain may climb from its anchor. Measured chains: the message
         * list 7 levels, the friends video pager 10; 16 leaves room for wrappers a future build
         * may add without letting a mis-detected anchor reach the window root.
         */
        const val DOUYIN_PAGE_CHAIN_MAX_DEPTH = 16

        /**
         * Delay between the page-bottom posted retries. Longer than
         * [DOUYIN_PAGE_ADJUST_INTERVAL_MS] so the retry never bounces off the page-adjust gate,
         * and short enough that the bottom band does not survive a page that has gone idle.
         */
        const val DOUYIN_PAGE_RETRY_MS = 250L

        /** Cap on posted retries per attempt: ~5s, then the normal pre-draw path takes over. */
        const val DOUYIN_PAGE_RETRY_MAX = 20
        const val DOUYIN_BOTTOM_SPACE_ID = "bottom_space"
        const val DOUYIN_FEED_PAGER_ID = "viewpager"
        const val DOUYIN_CONTROLS_ROOT_ID = "i7"
        const val DOUYIN_CONTROLS_ROOT_ID_ALT = "ja"
        const val DOUYIN_PROGRESS_ID = "wje"
        const val DOUYIN_PROGRESS_ID_ALT = "xw_"
        /**
         * Sinks the visible progress track below the caption/author/action cluster once
         * that cluster is capped to its own lowest widget. Measured against the feed:
         * without it the track (2478) crossed the caption's last line (2509); with 13dp
         * the track lands at 2525 — below the caption and just under the bar's top edge
         * (2520). Approved on device at this value; do not retune without a screenshot.
         */
        const val DOUYIN_PROGRESS_TRACK_SINK_DP = 13f
        const val DOUYIN_CONTROL_SAFE_GAP_DP = 3f
        const val DOUYIN_VIDEO_DIAG_FLUSH_MS = 2_000L
        const val DOUYIN_EDGE_TOLERANCE_PX = 4
        /**
         * How long a bottom-input sighting keeps the native bar treated as away.
         *
         * This is a *release* debounce, not a hold: the walk runs on every layout pass
         * (~32ms), so a value in this range only absorbs a single missing frame. It used to
         * be 600ms, which kept the bar hidden for about a second after the comment sheet
         * closed — the sheet's own exit animation plus the latch.
         */
        const val DOUYIN_BOTTOM_INPUT_TTL_MS = 120L

        /**
         * How long a bottom-input sighting suppresses the lift release. Covers the sheet's
         * own open/close animation plus the moment Douyin needs to bring its bar back, so
         * the lifted controls are never parked at their native position in between.
         */
        const val DOUYIN_BOTTOM_INPUT_RELEASE_GUARD_MS = 900L
        /**
         * How much of a pager page must still be rendered for the feed to count as on screen.
         *
         * Same fraction as the native-bar check, so "the bar is gone but the page is here"
         * (an overlay) and "both are gone" (a pushed page) are judged by one rule.
         */
        const val DOUYIN_FEED_PAGE_MIN_VISIBLE_FRACTION = 0.25f
        /** How far the comment-sheet probe walks up from a lift candidate. */
        const val DOUYIN_LIFT_ANCESTRY_MAX = 12
        /** How far above a lifted view the page-level probe looks for a pager page slot. */
        const val DOUYIN_PAGE_LEVEL_MAX = 2
        /** Refresh interval for the visible-content bottom used to cap a lift. */
        const val DOUYIN_VISIBLE_BOTTOM_TTL_MS = 400L
        /** How much of the sheet must still render for it to count as on screen. */
        const val DOUYIN_SHEET_MIN_VISIBLE_FRACTION = 0.25f
        const val DOUYIN_SHEET_MIN_ALPHA = 0.05f
        /**
         * How long a sheet sighting keeps the lift frozen, from its last visible frame. Covers
         * the close animation, during which the sheet stops rendering a visible rect while the
         * page behind it is still sized for it.
         */
        const val DOUYIN_SHEET_FREEZE_GUARD_MS = 500L
const val DOUYIN_SHEET_CLOSE_SETTLE_MS = 1_200L
        val DOUYIN_PROGRESS_NAME_SET = setOf("cez", "v5j", "progress_bar")
        val DOUYIN_RELATED_SEARCH_TEXT = setOf("相关搜索", "推荐搜索", "大家都在搜", "搜一搜")
    }
}
