package io.github.xzdw.dylt.hook.adapters

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.xzdw.dylt.hook.HookLoadContext
import io.github.xzdw.dylt.hook.NavigationCandidate
import io.github.xzdw.dylt.hook.adapters.douyin.DouyinAdapter
import io.github.xzdw.dylt.targets.TargetSpec
import io.github.xzdw.dylt.targets.TargetSpecRegistry
import io.github.xzdw.dylt.targets.HookSignal
import io.github.xzdw.dylt.config.GlassConfig

/** The app-owned part of discovery and lifecycle. Shared glass rendering stays in the host. */
internal interface TargetAdapter {
    val key: String
    val ownsNavigationFinding: Boolean get() = false
    /** Null keeps the legacy generic detector; an app may explicitly opt out. */
    val nativeSelectionReliable: Boolean? get() = null
    /** Keep host traversal alive when a static page feeds a compositor surface. */
    fun needsContinuousOpticalSurfaceFrames(selectedIndex: Int): Boolean = false
    /** Native source artwork adjustments, owned by each app's adapter. */
    val sourceIconScale: Float? get() = null
    val sourceTextTranslationDp: Float? get() = null
    val sourceIconTranslationDp: Float? get() = null
    val sourceContentOffsetDp: Float get() = 0f
    /** Optional native source label size applied before glass projection. */
    val sourceTextSizeSp: Float? get() = null
    /** Optional scale used when projecting native artwork into the glass row. */
    val projectedNavigationScale: Float get() = 1f
    val ownsNavigationDrawing: Boolean get() = false
    /** Draw projected native artwork before the liquid indicator so the lens refracts it. */
    val navigationBehindIndicator: Boolean get() = false
    val ownsNavigationTap: Boolean get() = false
    val commitSelectionAfterTap: Boolean get() = false
    val skipNavigationSnapshot: Boolean get() = false
    val stagedNavigationSnapshot: Boolean get() = false
    val ownsNavigationSnapshotCapturePolicy: Boolean get() = false
    val supportsScheduledNavigationSnapshotRefresh: Boolean get() = false
    val navigationSnapshotStableFrames: Int? get() = null
    val navigationSignatureMaxDepth: Int get() = 5
    val redrawSurfaceAfterNavigationCapture: Boolean get() = false
    val usesCommittedSelection: Boolean get() = false
    val usesContentDarkMode: Boolean get() = false
    val forceLightMode: Boolean get() = false
    val ownsHostNativeChrome: Boolean get() = false
    /** Some adapters use a native source as a visible content host (e.g. mini-player pages). */
    val keepNavigationSourceVisible: Boolean get() = false
    val hostLiftDp: Float? get() = null
    val hasPerFrameScene: Boolean get() = false
    /** Null keeps the normal immersive fitting; an app may pin its own navigation inset. */
    val navigationImmersionOverride: Boolean? get() = null
    /** Reapply the saved non-immersive state if an app rewrites window flags after launch. */
    val repeatImmersionRestoreWhileInactive: Boolean get() = false
    val suppressPlatformElevation: Boolean get() = false
    val contentAncestorMaxDepth: Int? get() = null
    val sourceParentVisibilityOnly: Boolean get() = false
    val sourceSwapGraceMs: Long get() = 0L
    val panelShadowBlurDp: Float? get() = null
    val synchronousConfigPolling: Boolean get() = false
    val nativeNavigationDrawsInSourceSpace: Boolean get() = false
    val indicatorInnerShadow: Boolean get() = false
    /**
     * Opaque selection-indicator tint owned by an adapter, in the form
     * (argb color, alpha 0..1). Null keeps the shared translucent white/black
     * indicator. Used by Douyin's flat capsule indicator.
     */
    val indicatorTint: Pair<Int, Float>? get() = null
    /**
     * Dark-mode companion for [indicatorTint]. Null derives one by darkening
     * [indicatorTint]'s hue, which is often too subtle; an adapter can name the
     * colour explicitly instead.
     */
    val indicatorTintDark: Pair<Int, Float>? get() = null
    /**
     * Multiplier applied to the dark-mode container mask when the selection capsule is
     * painted. The capsule keeps following the themed mask colour, so it still tracks
     * the bar; this only lets an adapter make the selected pill read a little stronger
     * than the surface it sits on. 1 keeps it identical to the container.
     */
    val indicatorMaskRatioDark: Float get() = 1f
    /**
     * Gap in dp between the main pill and a detached circular action button. A value
     * greater than zero reserves a button as tall as the bar at the right edge, and
     * the navigation slots are laid out inside the remaining width.
     */
    val navigationActionReserveGapDp: Float get() = 0f
    /**
     * Opt-in for a second glass host pinned to the top edge of the same content frame.
     * It is a literal copy of the bottom host — its own capture, blur, refraction and
     * panel path — so the installer only has to place it. Off by default.
     */
    val supportsTopBar: Boolean get() = false
    /**
     * Locates the app's own top bar row for the top-anchored glass copy. The returned
     * row is both the copy's visibility anchor and the window it forwards taps to.
     */
    fun findTopNavigation(root: View): ViewGroup? = null
    /**
     * Detached action-button reserves for the top copy as (left, right) dp gaps. Zero
     * keeps a plain capsule; a positive value reserves that side for a circular action
     * button as tall as the bar.
     */
    fun topBarActionReserveGapDp(): Pair<Float, Float> = 0f to 0f
    /**
     * Visible slot count for the top copy. Zero leaves the bottom bar's count in place
     * and lets the top state update it once it has read the app's own title bar.
     *
     * [config] carries 顶栏标签数量 / 顶栏标签排序 so the install-time width is already the
     * configured one instead of the app's own visible window.
     */
    fun topBarSlotCount(root: View, config: GlassConfig): Int = 0
    /**
     * Requested width of one top-copy slot in dp. A positive value makes the top bar hug
     * its content (slot band x tab count + both action reserves) instead of spanning the
     * content frame; zero keeps the shared width rules.
     */
    fun topBarSlotWidthDp(): Float = 0f
    /**
     * Height of the top copy as a fraction of the app's own title bar. 1 keeps the native
     * height; a smaller value makes the copy slimmer than the row it replaces.
     */
    fun topBarHeightFraction(): Float = 1f
    /**
     * Width of the action band the top copy keeps inside its capsule, ahead of the tabs
     * (Douyin's tab-menu key). Zero keeps a capsule that is nothing but navigation slots.
     */
    fun topBarInlineActionWidthDp(): Float = 0f
    /**
     * Diameter of the top copy's two corner circles as a fraction of the bar height. 1 keeps
     * them as tall as the bar, which is what the bottom bar's own action button does.
     */
    fun topBarActionButtonFraction(): Float = 1f
    /**
     * Adapter-owned bar tint as (argb rgb, strength 0..1), resolved per theme. The
     * strength scales the configured light/dark alpha, so the glass still shows the
     * content behind it. Null keeps the shared neutral white/black wash.
     */
    fun glassBarTint(dark: Boolean): Pair<Int, Float>? = null
    val themeProbeIntervalMs: Long? get() = null
    val allowRenderNodeRefresh: Boolean get() = false
    /** Reuse the cached optical blur node instead of rebuilding it during page transitions. */
    val reuseOpticalBlur: Boolean get() = false
    /** Keep capture invalidation active while a live backdrop listener is attached. */
    val captureDuringLiveBackdrop: Boolean get() = true
    /** Native source is retained for clicks only; the glass host owns visible rendering. */
    val hideRedrawnNavigationSource: Boolean get() = false
    /** Optional source projection rules for app-specific centre actions. */
    val sourceCenterActionIndex: Int? get() = null
    val sourceDefaultTranslationDp: Float get() = 0f
    val sourceCenterTranslationDp: Float get() = 0f
    val sourceCenterClipRatio: Float get() = 1f
    val preserveNativeColorIndices: Set<Int> get() = emptySet()
    /** Delay before a scroll-idle page hides the glass bar. */
    fun scrollStopHideDelayMs(selectedIndex: Int): Long = 1_000L
    /** Some native rows need a short settle window after dispatching a click. */
    val selectionSettleMs: Long get() = 0L
    /** App-owned scroll-stop-hide tab; the scroll listener itself is shared. */
    fun scrollStopHideTab(selectedIndex: Int): Boolean = false
    fun resetScrollStopHideOnOtherTabs(): Boolean = false
    fun isBackPeekTab(selectedIndex: Int): Boolean = false
    fun isBackPeekActivity(activity: Activity): Boolean = false
    val backPeekDurationMs: Long get() = 0L
    val drawsNavigationInOptics: Boolean
        get() = TargetSpecRegistry.byKey[key]?.drawsNavigationInOptics == true
    val suppressSourceOnHostRebind: Boolean get() = false
    val snapIndicatorToSelectionOnRebind: Boolean get() = false
    val refreshHostGeometryOnRebind: Boolean get() = false
    val accentColor: Int? get() = TargetSpecRegistry.byKey[key]?.glassAccentColor
    val reverseVisualSlotOrder: Boolean get() = false
    val actionOnlyIndices: Set<Int> get() = emptySet()
    val floatingPillUsesConfiguredHeight: Boolean get() = false
    val floatingPillUsesSharedInsets: Boolean get() = false
    val preserveNativeParentBackground: Boolean get() = false
    val preserveNativeSourceMetrics: Boolean get() = false
    val foregroundNavigationOverSelection: Boolean get() = false
    val usesNavigationOpticalMix: Boolean get() = false
    val requestedFrameRateMax: Float? get() = null
    val continuousFramePump: Boolean get() = false
    val observeWindowTouches: Boolean get() = false
    fun onWindowTouch(host: View, event: android.view.MotionEvent) = Unit
    val opticalIconBandScale: Float get() = 1f
    val secondaryBlurScale: Float get() = 1f
    val skipBackdropStableDelay: Boolean get() = false
    /** Refresh the optical pipeline when the bar appears. */
    val refreshOpticsOnBarShow: Boolean get() = false
    val forceCompositorFrames: Boolean get() = false
    val disablePlatformForceDark: Boolean get() = false
    val liveBackdropFramePump: Boolean get() = false
    val releaseOutlineClipping: Boolean get() = false
    /**
     * Distance the bar travels while hidden. Null uses the shared fraction of the host
     * height. An app whose native host is a short bottom container returns a smaller value
     * so the exit stays inside that container.
     */
    fun hiddenBarTranslationPx(hostHeight: Int, density: Float): Float? = null
    /** Shared low-power Surface/PixelCopy engine; page selection remains app-owned. */
    val usesOpticalSurfacePipeline: Boolean get() = false
    val retainsOpticalSurfaceWhenBarHidden: Boolean get() = false
    val opticalSurfaceFollowsBarTransform: Boolean get() = true
    val usesDirectOpticalBackdropMode: Boolean get() = false
    val usesNativeOpticalBlur: Boolean get() = false
    val ownsOpticalSurfaceLayout: Boolean get() = false
    fun opticalSurfaceLayout(
        contentWidth: Int, contentHeight: Int, hostLeft: Int, hostTop: Int,
        hostWidth: Int, hostHeight: Int, overflow: Int,
    ): OpticalSurfaceLayout? = null
    val allowsSystemBackgroundBlur: Boolean get() = true
    val countsOpticalCompositorCopies: Boolean get() = false
    val deferOpticalRenderDuringBarAnimation: Boolean get() = false
    val reuseSurfaceBlurDuringBarAnimation: Boolean get() = false
    val usesContentAlignedOpticalSurface: Boolean get() = false
    val retainsExistingBackdropRenderNode: Boolean get() = false
    fun isOpticalSurfaceTab(selectedIndex: Int): Boolean = false
    val restingOpticalMix: Float? get() = null
    val logNavigationHierarchy: Boolean get() = false
    val clearSourceSelectionSurfaces: Boolean get() = false
    val trackSourceDrawableIdentity: Boolean get() = false
    val managesNativeTabVisibility: Boolean get() = false
    val honorsBackdropCaptureSetting: Boolean get() = false
    fun normalizeConfig(config: GlassConfig): GlassConfig = config
    fun hostBottomMarginPx(view: View, defaultGap: Int): Int = defaultGap
    fun opticalCaptureGeometry(
        rootWidth: Int, rootHeight: Int, left: Int, top: Int, width: Int, height: Int, padding: Int,
    ): OpticalCaptureGeometry? = null
    fun createNavigationState(context: Context): AppNavigationState? = null
    fun createNavigationState(context: Context, host: io.github.xzdw.dylt.hook.GlassHostLayout): AppNavigationState? =
        createNavigationState(context)
    fun createNativeChromeController(): AppNativeChromeController? = null
    fun screenReferenceHeight(rootHeight: Int?, displayHeight: Int): Int? = null
    fun drawNavigation(canvas: Canvas, frame: AdapterNavigationFrame) = Unit
    /**
     * Unread badge for the messages entry, read from the app's own (hidden) navigation row.
     * Null or invisible draws nothing.
     */
    fun navigationBadge(source: View?): NavigationBadge? = null
    val hasTransientPageVisibility: Boolean get() = false
    fun navigationSurfaceColor(activity: Activity?, source: View?, dark: Boolean): Int? = null
    fun dispatchNavigationTap(source: ViewGroup?, index: Int, slotCount: Int): Boolean = false
    fun configureNavigationInsets(decor: View, enabled: Boolean) = Unit
    fun immersionWasReset(immersiveStub: Any?): Boolean = false
    fun pushedConfig(): GlassConfig? = null
    fun acceptPushedConfig(config: GlassConfig): Boolean = false
    fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? = null
    fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int? = null
    fun adjustNavigationScore(view: ViewGroup, idName: String?, score: Int): Int = score
    fun onNavigationCandidates(candidates: List<NavigationCandidate>) = Unit
    fun suppressNativeBottomChrome(navigation: ViewGroup) = Unit
    /** Host-aware variant for adapters that must exclude the glass host subtree from tree walks. */
    fun suppressNativeBottomChrome(host: View, navigation: ViewGroup) =
        suppressNativeBottomChrome(navigation)
    fun suppressNativeBottomChromeOnRebind(navigation: ViewGroup) = Unit
    fun prepareContentForInstall(content: ViewGroup) = Unit
    fun prepareNavigationSource(navigation: ViewGroup, density: Float) = Unit
    /** Return true when the adapter fully configured this source label. */
    fun configureSourceText(view: TextView, config: GlassConfig, density: Float): Boolean = false
    /** Return true when the adapter fully configured this source icon. */
    fun configureSourceIcon(view: ImageView, config: GlassConfig, density: Float): Boolean = false
    fun resolveNestedNavigation(navigation: ViewGroup): Pair<ViewGroup, Int>? = null
    fun acceptsNavigationSource(navigation: ViewGroup): Boolean = true
    fun hookSignals(): List<TargetHookSignal> = emptyList()
    fun installHooks(context: HookLoadContext, spec: TargetSpec) = Unit
    fun beforeNavigationScan(activity: Activity) = Unit
    fun isBlockingOverlayActivity(activity: Activity): Boolean = false
    fun setBlockingOverlayVisible(visible: Boolean) = Unit
    fun isBlockingOverlayVisible(): Boolean = false
}

internal typealias TargetHookSignal = HookSignal

/** Adapter-owned overlay excluded from generic sampling and traversal. */
internal interface AdapterOwnedOverlay

internal data class AdapterNavigationFrame(
    val context: Context,
    val extraScale: Float,
    val slotCount: Int,
    val width: Int,
    val height: Int,
    val slotWidth: Float,
    /**
     * Detached action-button reserves the host measured for this frame, as
     * (gap + button) in px on each side of the main pill. The bottom bar only ever
     * uses the right one; the top copy reserves a circle on both sides.
     */
    val leftActionReservePx: Float = 0f,
    val rightActionReservePx: Float = 0f,
    /**
     * Width of an action band *inside* the pill, ahead of the navigation slots. The top
     * copy uses it for the tab-menu key that sits in the capsule beside its two tabs.
     */
    val inlineActionWidthPx: Float = 0f,
    /**
     * Diameter of the detached action circles for this frame; zero means "as tall as the
     * bar", which is what every bar without an explicit fraction uses.
     */
    val actionButtonPx: Float = 0f,
    val density: Float,
    val iconScale: Float,
    val textSize: Float,
    val scaledDensity: Float,
    val selectedIndex: Int,
    val enabledIndices: List<Int>,
    val iconOnly: Boolean,
    /**
     * 底栏显示样式 仅文字: the bar draws labels alone, centred where the glyph used to sit. Both
     * shape flags are bottom-bar only — the top copy is a labels-only design of its own.
     */
    val textOnly: Boolean = false,
    val darkGlass: Boolean,
    /**
     * The selection capsule's shape for this frame, when the host is drawing one.
     *
     * The renderer paints its selected ink through this path instead of trusting a per-slot flag,
     * so the accent follows the capsule while it is dragged (see DouyinNavigationRenderer.drawRow).
     */
    val accentClip: android.graphics.Path? = null,

    /**
     * True while the selection ink belongs to the capsule's lens scene — the finger is down or the
     * press animation is still running. The lens then bends and disperses that ink along with the
     * backdrop. At rest the ink is painted over the glass instead, which keeps it crisp and stops the
     * capsule's edge refraction band from smearing the glyph's own extremes into stray slivers.
     */
    val selectionInkInLensScene: Boolean = false,
    val accentColor: Int,
    val navigationOpticalMix: Float = 0f,
    val hideSelectedForOptics: Boolean = false,
    /**
     * Press-time scale for the selected entry's icon + label + badge (1 = resting).
     * Foreground and indicator-lens copies share this value so their ink stays aligned.
     */
    val pressContentScale: Float = 1f,
    /**
     * Per-slot press scales. The settings bar gives every tab its own animated graphicsLayer
     * scale; these values mirror that so the outgoing tab shrinks and the incoming one grows
     * with a real spring instead of snapping at a slot boundary.
     */
    val pressSlotScales: List<Float> = emptyList(),
    /** Unread messages badge drawn above the messages label; null draws nothing. */
    val navigationBadge: NavigationBadge? = null,
    /**
     * User-picked image for the bar's publish button, base64 PNG; empty draws the built-in glyph.
     */
    val publishIcon: String = "",
    /**
     * 底栏菜单文字: per-entry names overriding the renderer's own, in the order the renderer draws
     * its entries (bar order). A blank or missing entry keeps the renderer's default name, so a
     * half-filled list never blanks a label out.
     */
    val customEntryLabels: List<String> = emptyList(),
)

/**
 * A navigation unread indicator: a count, a plain dot, or both absent.
 *
 * [count] 0 with [dot] true matches an app that only shows a dot for unread messages.
 */
internal data class NavigationBadge(val count: Int, val dot: Boolean) {
    val visible: Boolean get() = count > 0 || dot

    companion object {
        val None = NavigationBadge(0, false)
    }
}

internal data class AppVideoBackdrop(
    val bitmap: android.graphics.Bitmap,
    val surfaceLeft: Int,
    val surfaceTop: Int,
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val epoch: Int,
)

/** Per-host mutable navigation resources owned by one app, not the shared glass renderer. */
internal data class NavigationCaptureLabels(
    val labels: List<TextView>,
    val retryDelayMs: Long = 0L,
)

internal data class OpticalCaptureGeometry(
    val sourceRect: android.graphics.Rect,
    val targetWidth: Int,
    val targetHeight: Int,
    val barSource: android.graphics.Rect,
)

internal data class OpticalSurfaceLayout(
    val width: Int, val height: Int, val left: Int, val top: Int,
    val right: Int, val bottom: Int, val gravity: Int,
)

internal interface AppNavigationState {
    /** Some native scenes contain GPU-only assets and cannot be drawn to a Bitmap Canvas. */
    val allowsSoftwareBackdropCapture: Boolean get() = true
    /** Lightweight adapter-owned work that runs from the shared pre-draw cadence. */
    fun onHostFrame(host: View) = Unit
    fun onContentScroll() = Unit
    fun onNavigationTabSwitched(index: Int, slotCount: Int, now: Long) = Unit
    fun contentMotionHoldUntil(): Long = 0L
    fun onNavigationSelectionRequested(index: Int, slotCount: Int, now: Long): Boolean = false
    fun acceptNativeNavigationSelection(index: Int, now: Long): Boolean? = null
    fun followingNativeNavigationAnimation(now: Long): Boolean = false
    fun pendingNativeNavigationTarget(now: Long): Int? = null
    val readyForDrawing: Boolean get() = true
    val blocksBackdropSampling: Boolean get() = false
    fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) = Unit
    fun captureNavigation(source: ViewGroup, target: android.graphics.Bitmap): Boolean = false
    fun acceptCapturedNavigation(source: ViewGroup?, target: android.graphics.Bitmap, selectedIndex: Int): Boolean = true
    fun acceptAndCommitNavigationSnapshot(
        source: ViewGroup?, target: android.graphics.Bitmap, selectedIndex: Int, slotCount: Int,
        commit: (android.graphics.Bitmap) -> Unit,
    ): Boolean? = null
    fun inspectNavigationCaptureLabels(source: ViewGroup, hasSnapshot: Boolean): NavigationCaptureLabels? = null
    fun temporarilyHideNavigationLabels(labels: List<TextView>, selectedIndex: Int): List<Pair<TextView, Int>> = emptyList()
    /** Null uses the shared snapshot cadence; true skips this frame's capture. */
    fun skipNavigationSnapshotCapture(
        source: ViewGroup, snapshot: android.graphics.Bitmap?, touching: Boolean,
        visualAnimating: Boolean, slotCount: Int, now: Long,
    ): Boolean? = null
    fun replaceCapturedVideoSlot(target: android.graphics.Bitmap) = Unit
    fun drawFixedNavigationSlot(canvas: Canvas, index: Int, selectedIndex: Int, dark: Boolean,
                                centerX: Float, centerY: Float, boxSize: Float): Boolean = false
    fun sourceContentOffsetY(index: Int, selectedIndex: Int, hostHeight: Int,
                             transformDy: Float, transformScale: Float): Float? = null
    fun drawAppBadgeOverlay(canvas: Canvas, host: View, contentScale: Float) = Unit
    /** App-owned snapshot crop/repaint stages for native tab artwork. */
    fun transformNavigationSnapshot(source: ViewGroup, snapshot: android.graphics.Bitmap, slotCount: Int): android.graphics.Bitmap? = null
    fun onNavigationSnapshotReady(snapshot: android.graphics.Bitmap, slotCount: Int) = Unit
    fun drawNavigationSnapshotOverlay(canvas: Canvas, frame: AdapterNavigationFrame,
                                      snapshot: android.graphics.Bitmap?) = Unit
    /** Whether the current app mode needs a sampled copy of content behind the bar. */
    fun requiresSampledBackdrop(): Boolean = true
    fun needsSurfaceBackdrop(): Boolean = false
    fun maintainsCompositorSamplingWithoutSurface(): Boolean = false
    /** Optional app-owned worker for PixelCopy callbacks; null keeps the shared worker. */
    fun opticalSurfaceCopyHandler(): android.os.Handler? = null
    /** Optional adapter-owned ordering of readback relative to app frame submission. */
    fun scheduleOpticalSurfaceCopy(root: View, copy: () -> Unit): Boolean = false
    fun onOpticalSurfaceCopyCompleted(root: View, nextCopy: () -> Unit) = Unit
    /** Reuse the retired buffer for the next copy while the published frame renders. */
    fun overlapsOpticalCopyAndRender(): Boolean = false
    fun directOpticalBackdropActive(): Boolean = false
    fun opticalSurfaceVisibleDuringTransition(hostVisibility: Int, hostAlpha: Float): Boolean? = null
    fun enterDirectOpticalBackdrop(surface: android.view.SurfaceView, pipelineActive: Boolean): Boolean = false
    fun onOpticalSurfaceFrameInvalidated() = Unit
    fun onOpticalSurfacePipelineEnabled(wasActive: Boolean) = Unit
    fun onOpticalBackdropCaptured(barSource: android.graphics.Rect?) = Unit
    /** A changed sampled image can indicate movement without a View scroll event. */
    fun onOpticalBackdropChanged() = Unit
    fun onOpticalSurfaceFrameRendered(): Boolean = false
    fun onOpticalRendererConfigurationChanged() = Unit
    fun holdDirectBackdropUntilOpticalFrame(liveBackdropActive: Boolean, hasDisplayList: Boolean): Boolean = false
    fun shouldQueueOpticalRenderFromUi(animating: Boolean): Boolean? = null
    fun queueOpticalSurfaceRender(
        handler: android.os.Handler?, active: () -> Boolean, render: () -> Unit,
    ): Boolean = false
    fun syncNativeSurfaceOuterEffect(
        view: View, width: Int, height: Int, blurRadius: Float,
        cornerRadiusPercent: Float, density: Float, shaderFactory: () -> android.graphics.RuntimeShader?,
    ): Boolean = false
    fun retainBackdropDuringTransition(now: Long): Boolean = false
    fun retainHomeBackdrop(selectedIndex: Int): Boolean = false
    fun deferThemeDetection(now: Long): Boolean = false
    fun needsContinuousHomeFrames(selectedIndex: Int): Boolean = false

    /**
     * Whether the page's video source has stopped producing new frames.
     *
     * The glass has to repaint every display frame only because the video underneath it keeps
     * changing. When that stops — paused video, a photo post, a static page — continuing to
     * pump frames is pure waste, and at 165Hz it is the single largest cost this module adds.
     *
     * Return `true` to let the host stop pumping, `false` to keep pumping, or `null` (the
     * default) when the adapter cannot tell — in which case the host keeps its old behaviour.
     * Implementations must debounce: the ordinary gap between two frames is not "stopped".
     */
    fun videoSourceIsStatic(host: View): Boolean? = null

    /**
     * True when the adapter has proven that the bar's own backyard is static (a letterbox
     * band or a static fill). Unlike [videoSourceIsStatic], this must NOT include the
     * frame-stamp pause detector: the host stops its Choreographer watcher on this signal,
     * and a paused full-screen video must keep that watcher alive to notice playback
     * resuming.
     */
    fun barStripStatic(host: View): Boolean = false

    /**
     * Presentation timestamp of the newest frame the page's video source has produced, or
     * null when the adapter cannot tell.
     *
     * This is what lets the host stop repainting on every display frame. The video produces
     * 30-60 frames a second while the panel refreshes 165 times, so pumping on every vsync
     * redraws the glass ~3x more often than the picture behind it can possibly change — the
     * extra passes cost display and GPU power and produce identical pixels.
     *
     * Callers compare this across frames: a changed value means a repaint is owed, an
     * unchanged one means the previous frame is still current. It must be cheap enough to
     * read every display frame (no pixel readback).
     */
    fun videoFrameStamp(host: View): Long? = null

    fun pageRequiresScrollStopHide(selectedIndex: Int): Boolean = false
    fun retainNavigationWhenNativeRowHidden(selectedIndex: Int): Boolean = false
    fun retainNavigationWithoutNativeSource(selectedIndex: Int): Boolean =
        retainNavigationWhenNativeRowHidden(selectedIndex)
    fun onBarGesture(phase: Int, host: View) = Unit
    fun suppressNativeChrome(source: ViewGroup?) = Unit
    fun suppressNativeChromeEarly(source: ViewGroup?) = Unit
    fun suppressHostNativeChrome(source: ViewGroup?, root: View, selected: Int) = Unit
    fun drawNativeNavigation(canvas: Canvas, source: ViewGroup, density: Float): Boolean = false

    /**
     * The selection's own ink, painted into the indicator's lens scene rather than over the glass.
     *
     * The host calls this while recording the layer the capsule refracts, so the ink is bent and
     * split into its spectrum exactly like the backdrop behind it. Adapters whose entries track the
     * capsule positionally implement it; the default leaves the lens scene untouched.
     */
    fun drawSelectionInk(canvas: Canvas, frame: AdapterNavigationFrame, inLensScene: Boolean) = Unit
    fun onHostPreDraw(
        host: View, source: ViewGroup?, selected: () -> Int,
        enabled: Boolean, density: Float,
        barHeightPx: Int,
        surfaceColor: () -> Int,
    ) = Unit
    /** Return true when an app configured its own compositor/backdrop transition policy. */
    fun updateBackdropRendererMode(host: io.github.xzdw.dylt.hook.GlassHostLayout): Boolean = false
    fun onHostFocusLost() = Unit
    fun onHostWindowFocusChanged(hasFocus: Boolean) = Unit
    fun onHostDetached() = Unit
    fun syncVisibilityCompanion(host: View, selectedIndex: Int): Boolean = false
    fun beforeHostDraw(host: View, source: ViewGroup?): Boolean = true
    fun hostChromeAlpha(host: View, selectedIndex: Int): Float = 1f
    fun drawAdditionalAmbientShadow(canvas: Canvas, dark: Boolean, alphaScale: Float) = Unit
    fun drawSampledBackdrop(canvas: Canvas, bitmap: android.graphics.Bitmap,
                            destination: android.graphics.RectF, paint: android.graphics.Paint): Boolean = false
    fun reuseOuterBackdropForSurface(surfaceActive: Boolean): Boolean = false
    fun outerBackdropNeedsRecord(
        backdrop: android.graphics.Bitmap?, width: Int, height: Int, hasDisplayList: Boolean,
    ): Boolean? = null
    fun onOuterBackdropRecorded(backdrop: android.graphics.Bitmap?, width: Int, height: Int) = Unit
    fun reuseCombinedIndicatorScene(surfaceActive: Boolean): Boolean = false
    fun combinedIndicatorSceneNeedsRecord(
        backdrop: android.graphics.Bitmap?, navigation: android.graphics.Bitmap?, signature: Long,
        width: Int, height: Int, dark: Boolean, hasDisplayList: Boolean,
    ): Boolean? = null
    fun onCombinedIndicatorSceneRecorded(
        backdrop: android.graphics.Bitmap?, navigation: android.graphics.Bitmap?, signature: Long,
        width: Int, height: Int, dark: Boolean,
    ) = Unit
    fun drawHiddenTabsInIndicatorScene(hybridBackdrop: Boolean): Boolean? = null
    fun includeNavigationInIndicatorScene(surfaceActive: Boolean): Boolean? = null
    fun opticalBlurNodeNeedsRecord(
        prepared: Boolean, backdrop: android.graphics.Bitmap?, width: Int, height: Int,
        effect: android.graphics.RenderEffect?,
    ): Boolean? = null
    fun onOpticalBlurNodeRecorded(
        backdrop: android.graphics.Bitmap?, width: Int, height: Int,
        effect: android.graphics.RenderEffect?,
    ) = Unit
    fun skipLiveBackdrop(selectedIndex: Int): Boolean = false
    fun canReuseLiveBackdrop(selectedIndex: Int): Boolean = false
    /** Refresh retained GPU wrappers when a texture producer supplies a new frame. */
    fun forceLiveBackdropFrame(selectedIndex: Int): Boolean = false
    fun onLiveBackdropRecorded(selectedIndex: Int) = Unit
    fun beginOverlayTouch(host: View, event: android.view.MotionEvent): Boolean = false
    fun hasActiveOverlayTouch(): Boolean = false
    fun forwardOverlayTouch(host: View, event: android.view.MotionEvent) = Unit
    fun clearOverlayTouch() = Unit
    fun navigationBarGeometry(): android.graphics.RectF? = null
    fun allowsHostTouch(): Boolean = true
    fun setNativeBarTouchable(view: View?, touchable: Boolean): Boolean = false
    fun onHostTouchDown(host: View, selectedIndex: Int) = Unit
    fun drawsNavigationContent(): Boolean = true
    fun suppressRedrawnSource(): Boolean? = null
    fun forceDarkMode(selectedIndex: Int): Boolean? = null
    fun resolveAutoDarkMode(host: View): Boolean? = null
    fun resolveBackdropScene(host: View): View? = null
    /** Optional safe subtrees; ancestor nodes containing media surfaces must not be replayed. */
    fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View>? = null
    fun enforceContentClipping() = Unit
    fun refreshNavigationSource(host: View): Boolean = false
    fun backdropSceneBaseColor(scene: View, fallback: Int): Int = android.graphics.Color.TRANSPARENT
    fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? = null
    fun adjustFloatingActions(host: View, source: ViewGroup?) = Unit
    fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = Unit
    fun requestVideoBackdrop(host: View, pageAllowed: Boolean) = Unit
    fun videoBackdrop(): AppVideoBackdrop? = null
    fun drawForeground(canvas: Canvas, frame: AdapterNavigationFrame, selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean = false
    /** Optional app-owned edge composition; false retains the shared path clip. */
    fun drawSelectionBackdrop(canvas: Canvas, path: android.graphics.Path, selectedIndex: Int,
                              drawContent: () -> Unit): Boolean = false
    fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean = false
    /**
     * A tap landed on a detached action button beside the pill rather than on a slot.
     * The host only reports it when it actually reserved such a button, so a bar
     * without one never sees this call. Returning false leaves the tap unhandled.
     */
    fun performActionTap(host: View, source: ViewGroup?, leading: Boolean): Boolean = false
    /**
     * A tap landed on the action band *inside* the pill rather than on a navigation slot.
     * The host only reports it when it actually reserved such a band, so a bar without one
     * never sees this call; returning false leaves the tap unhandled.
     */
    fun performInlineActionTap(host: View, source: ViewGroup?): Boolean = false
    fun dispatchNativeNavigationTap(
        host: io.github.xzdw.dylt.hook.GlassHostLayout, source: ViewGroup?, index: Int, slotCount: Int,
    ): Boolean = false
    fun onTapSucceeded(requestRefresh: (Long) -> Unit) = Unit
    fun pageAllowsNavigation(root: View?): Boolean? = null
    fun hostPageAllowsNavigation(host: io.github.xzdw.dylt.hook.GlassHostLayout, root: View?): Boolean? =
        pageAllowsNavigation(root)
    /** Null leaves system navigation-bar immersion to the common page policy. */
    fun forceNavigationBarImmersed(host: io.github.xzdw.dylt.hook.GlassHostLayout): Boolean? = null
    fun handleSystemBack(host: View): Boolean = false
    fun transientPageAllowsNavigation(activity: Activity?): Boolean = true
    fun dispose() = Unit
}


/** Mutable, app-owned source-chrome cleanup invoked at the host's existing cadence. */
internal interface AppNativeChromeController {
    fun update(host: View, source: ViewGroup?)
}

/** Explicit registry: an app adapter cannot accidentally run in another package. */
internal object TargetAdapterRegistry {
    private val custom = listOf(
        DouyinAdapter,
    ).associateBy(TargetAdapter::key)

    private val byKey: Map<String, TargetAdapter> = TargetSpecRegistry.all.associate { spec ->
        spec.key to custom.getValue(spec.key)
    }
    private val byPackage: Map<String, TargetAdapter> = TargetSpecRegistry.all.flatMap { spec ->
        (listOf(spec.packageName) + spec.aliasPackages).map { pkg -> pkg to byKey.getValue(spec.key) }
    }.toMap()

    fun forSpec(spec: TargetSpec): TargetAdapter? = byKey[spec.key]

    fun forKey(key: String): TargetAdapter? = byKey[key]

    fun forPackage(packageName: String): TargetAdapter? = byPackage[packageName]
}

internal object AdapterNavigationSearch {
    fun findFirst(root: View, predicate: (ViewGroup) -> Boolean): ViewGroup? {
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 4_000) {
            val (view, depth) = stack.removeLast()
            if (view is ViewGroup && depth <= 14 && predicate(view)) return view
            if (view is ViewGroup && depth < 16) {
                for (index in view.childCount - 1 downTo 0) {
                    stack += view.getChildAt(index) to (depth + 1)
                }
            }
        }
        return null
    }

    fun resourceEntryName(view: View): String? = runCatching {
        if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)
    }.getOrNull()
}
