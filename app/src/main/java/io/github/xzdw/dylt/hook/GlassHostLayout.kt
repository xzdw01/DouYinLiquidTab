package io.github.xzdw.dylt.hook






import android.app.Activity

import android.content.Context

import android.content.ContextWrapper

import android.content.res.Configuration

import android.graphics.Bitmap

import android.graphics.BitmapFactory

import android.graphics.BlendMode

import android.graphics.BlendModeColorFilter

import android.graphics.Canvas

import android.graphics.Color

import android.graphics.ColorMatrix

import android.graphics.ColorMatrixColorFilter

import android.graphics.LinearGradient

import android.graphics.Paint

import android.graphics.Path

import android.graphics.PixelFormat

import android.graphics.PorterDuff

import android.graphics.PorterDuffXfermode

import android.graphics.RadialGradient

import android.graphics.Rect

import android.graphics.RectF

import android.graphics.RenderEffect

import android.graphics.RenderNode

import android.graphics.RuntimeShader

import android.graphics.Shader

import android.graphics.Typeface

import android.graphics.drawable.ColorDrawable

import android.graphics.drawable.GradientDrawable

import android.graphics.drawable.Drawable

import android.hardware.Sensor

import android.hardware.SensorEvent

import android.hardware.SensorEventListener

import android.hardware.SensorManager

import java.lang.ref.WeakReference

import android.os.Handler

import android.os.HandlerThread

import android.os.Build

import android.os.Looper

import android.os.SystemClock

import android.util.Log

import android.util.TypedValue

import android.view.Choreographer

import android.view.MotionEvent

import android.view.PixelCopy

import android.view.Surface
import android.view.SurfaceControl

import android.view.SurfaceHolder

import android.view.SurfaceView

import android.view.TextureView

import android.view.View

import android.view.ViewGroup
import io.github.xzdw.dylt.hook.adapters.AdapterNavigationFrame
import io.github.xzdw.dylt.hook.adapters.AppNavigationState
import io.github.xzdw.dylt.hook.adapters.NavigationBadge
import io.github.xzdw.dylt.hook.adapters.TargetAdapterRegistry
import io.github.xzdw.dylt.hook.adapters.AdapterOwnedOverlay

import android.view.ViewConfiguration

import android.view.ViewOutlineProvider

import android.view.ViewTreeObserver

import android.view.WindowInsets

import android.view.animation.PathInterpolator

import android.widget.FrameLayout

import android.widget.ImageView

import android.widget.RelativeLayout

import android.widget.SeekBar

import android.widget.TextView


import androidx.graphics.shapes.CornerRounding

import androidx.graphics.shapes.RoundedPolygon

import androidx.graphics.shapes.toPath

import io.github.xzdw.dylt.hookx.Xposed


import io.github.xzdw.dylt.hookx.MethodHook

import io.github.xzdw.dylt.config.GlassConfig

import kotlin.math.abs

import kotlin.math.ceil

import kotlin.math.cos

import kotlin.math.floor

import kotlin.math.max

import kotlin.math.min

import kotlin.math.roundToInt

import kotlin.math.sin

import kotlin.math.sqrt



/**

 * Rebuilds an Android canvas path with MIUIX's continuous squircle curve.

 *

 * The injected renderer is View/Canvas based, while miuix-squircle exposes a

 * Compose Path extension. This is the allocation-free Android Path equivalent

 * of miuix-squircle 0.9.3's addSquircleRect (extension 1.1, control 0.357), so

 * the moving indicator does not allocate a Compose wrapper on every frame.

 */

internal fun Path.setBottomBarSquircle(rect: RectF, radius: Float, smoothing: Float = 0.5f) {

    reset()

    if (rect.isEmpty) return

    val strength = smoothing.coerceIn(-1f, 1f)

    val cornerRadius = max(0f, radius).coerceAtMost(min(rect.width(), rect.height()) * 0.5f)

    if (cornerRadius <= 0f) {

        addRect(rect, Path.Direction.CW)

        return

    }

    if (strength < 0f) {

        // AndroidX graphics-shapes keeps the rectangle bounds fixed and builds

        // the smoothing transition inside them.  This is the canonical inward

        // continuous-corner construction; do not emulate it by mirroring a

        // single cubic control point (that creates pinched, non-G2 corners).

        RoundedPolygon(

            vertices = floatArrayOf(

                rect.left, rect.top,

                rect.right, rect.top,

                rect.right, rect.bottom,

                rect.left, rect.bottom,

            ),

            rounding = CornerRounding(cornerRadius, -strength),

            centerX = rect.centerX(),

            centerY = rect.centerY(),

        ).toPath(this)

        return

    }

    val extent = max(0f, radius * (1f + 0.2f * strength))

        .coerceAtMost(min(rect.width(), rect.height()) * 0.5f)

    if (extent <= 0f) {

        addRect(rect, Path.Direction.CW)

        return

    }

    // 0 = conventional circular corner. Positive values retain the approved

    // MIUIX-style outward/full curve.

    val controlRatio = 0.448f - 0.182f * strength

    val control = extent * controlRatio

    moveTo(rect.left + extent, rect.top)

    lineTo(rect.right - extent, rect.top)

    cubicTo(rect.right - control, rect.top, rect.right, rect.top + control, rect.right, rect.top + extent)

    lineTo(rect.right, rect.bottom - extent)

    cubicTo(rect.right, rect.bottom - control, rect.right - control, rect.bottom, rect.right - extent, rect.bottom)

    lineTo(rect.left + extent, rect.bottom)

    cubicTo(rect.left + control, rect.bottom, rect.left, rect.bottom - control, rect.left, rect.bottom - extent)

    lineTo(rect.left, rect.top + extent)

    cubicTo(rect.left, rect.top + control, rect.left + control, rect.top, rect.left + extent, rect.top)

    close()

}



internal fun android.graphics.drawable.Drawable.toBitmapSafely(w: Int, h: Int): Bitmap? {

    if (w <= 0 || h <= 0) return null

    return runCatching {

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(bmp)

        val oldBounds = bounds

        setBounds(0, 0, w, h)

        try {

            draw(canvas)

        } finally {

            bounds = oldBounds

        }

        bmp

    }.getOrNull()

}



/**

 * Shared device-tilt tracker driving the bloom-stroke primary light, ported

 * from miuix-blur's rememberDeviceTilt (game rotation vector at GAME rate,

 * 0.15 low-pass) plus KernelSU's gravity-rotated highlight fallback: a

 * near-flat screen keeps the upright (0, -1) reference direction. One listener

 * serves every host in this process; hosts register only while attached,

 * window-visible, and configured to draw the bloom stroke, so backgrounded

 * apps stop the sensor entirely and rest state triggers zero repaints.

 */

internal object BloomTiltTracker {

    /** Screen-plane gravity projection in device space (+X right, +Y top); (0, 0) before the first sample. */

    @Volatile

    var gravityX = 0f

        private set



    @Volatile

    var gravityY = 0f

        private set



    const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f // |g_xy| > 0.1, 锟?掳 tilt

    private const val SMOOTHING = 0.15f

    private const val INVALIDATE_DELTA_SQ = 0.0007f // 锟?.5掳 direction change before a repaint



    private val clients = java.util.concurrent.CopyOnWriteArrayList<WeakReference<View>>()

    private val rotationMatrix = FloatArray(9)

    private var sensorManager: SensorManager? = null

    private var listener: SensorEventListener? = null

    private var smoothX = 0f

    private var smoothY = 0f

    private var initialized = false

    private var lastDirX = 0f

    private var lastDirY = -1f



    fun register(view: View) {

        clients.removeAll { it.get() === view || it.get() == null }

        clients.add(WeakReference(view))

        ensureListening(view)

    }



    fun unregister(view: View) {

        clients.removeAll { it.get() === view || it.get() == null }

        if (clients.isEmpty()) stopListening()

    }



    private fun ensureListening(view: View) {

        if (listener != null) return

        val manager = runCatching {

            view.context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        }.getOrNull() ?: return

        val rotationSensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

            ?: return

        val sensorListener = object : SensorEventListener {

            override fun onSensorChanged(event: SensorEvent) {

                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                // Gravity in device space = R^T * (0, 0, -1)_world = -(R third row).

                val gx = -rotationMatrix[6]

                val gy = -rotationMatrix[7]

                if (!initialized) {

                    smoothX = gx

                    smoothY = gy

                    initialized = true

                } else {

                    smoothX += (gx - smoothX) * SMOOTHING

                    smoothY += (gy - smoothY) * SMOOTHING

                }

                gravityX = smoothX

                gravityY = smoothY

                invalidateTiltedClients()

            }



            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        }

        val registered = runCatching {

            manager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)

        }.getOrDefault(false)

        if (!registered) return

        sensorManager = manager

        listener = sensorListener

    }



    private fun stopListening() {

        listener?.let { sensorManager?.unregisterListener(it) }

        sensorManager = null

        listener = null

        initialized = false

    }



    private fun invalidateTiltedClients() {

        val magSq = smoothX * smoothX + smoothY * smoothY

        val dirX: Float

        val dirY: Float

        if (magSq > GRAVITY_DIR_THRESHOLD_SQ) {

            val invMag = 1f / sqrt(magSq)

            dirX = smoothX * invMag

            dirY = smoothY * invMag

        } else {

            dirX = 0f

            dirY = -1f

        }

        val deltaSq = (dirX - lastDirX) * (dirX - lastDirX) + (dirY - lastDirY) * (dirY - lastDirY)

        if (deltaSq < INVALIDATE_DELTA_SQ) return

        lastDirX = dirX

        lastDirY = dirY

        for (client in clients) {

            val view = client.get() ?: continue

            if (view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE) {

                view.postInvalidateOnAnimation()

            }

        }

    }

}

/**

 * Native custom renderer for the floating glass navigation bar.

 *

 * Rendering order mirrors the reference: vibrancy -> 4 dp blur -> outer 24/24

 * lens -> translucent surface, then a 56 dp selected pill whose pressed lens is

 * 10/14 with depth and 0.5 chromatic dispersion.

 */

class GlassHostLayout(

    context: Context,

    rawConfig: GlassConfig,

    private var slotCount: Int,

    nonSelectableIndices: Set<Int> = emptySet(),

    private val nativeSelectionReliable: Boolean = true,

    navigationSource: ViewGroup? = null,

    suppressedOriginalChrome: List<View> = emptyList(),


    sliderEnabled: Boolean = true,

    /**
     * This instance is the second, top-anchored copy of the bar. The host itself
     * behaves identically (same capture, blur, refraction and panel geometry); the
     * flag only tells the adapter which state/drawing it should create for this copy.
     */
    internal val topBar: Boolean = false,

    /**
     * Per-host override of the detached action-button reserves, in dp, as (left, right)
     * gaps. Null keeps the adapter's right-hand [TargetAdapter.navigationActionReserveGapDp]
     * and no left reserve, which is the bottom bar's geometry. The top copy asks for a
     * circle on both sides so its capsule is symmetric.
     */
    private val actionReserveDp: Pair<Float, Float>? = null,

) : FrameLayout(context) {
    private var burnInOffsetX = 0f
    private var burnInOffsetY = 0f
    private var burnInStep = if (topBar) 4 else 0
    private var burnInScheduled = false
    private val burnInRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) {
                burnInScheduled = false
                return
            }
            if (config.antiBurnIn) {
                burnInStep = (burnInStep + 1) % BURN_IN_OFFSETS.size
                burnInOffsetX = BURN_IN_OFFSETS[burnInStep].first
                burnInOffsetY = BURN_IN_OFFSETS[burnInStep].second
                invalidate()
            } else {
                burnInOffsetX = 0f
                burnInOffsetY = 0f
                invalidate()
            }
            burnInScheduled = true
            postDelayed(this, BURN_IN_INTERVAL_MS)
        }
    }
    private val targetAdapter by lazy(LazyThreadSafetyMode.NONE) {
        TargetAdapterRegistry.forPackage(context.packageName)
    }
    internal val appNavigationState: AppNavigationState? by lazy(LazyThreadSafetyMode.NONE) {
        targetAdapter?.createNavigationState(context, this)
    }
    private val appNativeChromeController by lazy(LazyThreadSafetyMode.NONE) {
        targetAdapter?.createNativeChromeController()
    }

    // The file manager owns two very different bars (main tab row vs

    // multi-select action bar); the host is re-parameterized when it

    // switches between them, so these two stay mutable.

    // The adapter's action-only slots describe the bottom bar (Douyin's publish cell
    // sits at index 2 there). The top copy draws its own tab set, where every index is
    // a real tab, so it must not inherit that mask —doing so dropped one slot and left
    // the capsule and the labels one position apart.

    private var nonSelectableIndices: Set<Int> = nonSelectableIndices +
        if (topBar) emptySet() else (targetAdapter?.actionOnlyIndices ?: emptySet())

    private var sliderEnabled: Boolean = sliderEnabled

    /** Keep Weibo on the live window backdrop instead of a retained row bitmap. */

    private fun packageConfig(raw: GlassConfig): GlassConfig {

        // Always use the cross-device Android backdrop pipeline. Do not rewrite
        // persisted legacy flags: a rollback must restore the old preference.
        val normalized = raw.normalized().copy(
            backdropCapture = if (targetAdapter?.honorsBackdropCaptureSetting == true) raw.backdropCapture else true,
            nativeBlur = false,
        )

        val adapted = targetAdapter?.normalizeConfig(normalized) ?: normalized
        // The user's glass-alpha multiplier is applied *after* the adapter, so it also
        // reaches an adapter-owned glass alpha (Douyin's dark 0.20) instead of being
        // silently overwritten by it.
        val alphaScaled = adapted.copy(
            lightAlpha = (adapted.lightAlpha * adapted.glassAlphaScale).coerceIn(0f, 0.95f),
            darkAlpha = (adapted.darkAlpha * adapted.glassAlphaScale).coerceIn(0f, 0.95f),
        )
        // Rendering switches are enforced after app-specific normalization so
        // no adapter can accidentally re-enable a retired or disabled path.
        return alphaScaled.copy(
            backdropCapture = alphaScaled.backdropCapture &&
                (alphaScaled.liquidGlassEnabled || alphaScaled.blurRadius > 0f),
            nativeBlur = false,
            bottomGradientBlurEnabled = false,
        )

    }



internal var config = packageConfig(rawConfig)

    /**
     * Douyin: the glass host is attached to its own translucent panel window
     * carrying blurBehindRadius, so SurfaceFlinger blurs every layer below it
     * (the feed video SurfaceView included) in real time, like the system
     * control center. While active, the in-tree RenderNode/PixelCopy backdrop
     * pipelines are disabled: they cannot capture the video surface anyway,
     * and their stale frames would cover the compositor blur.
     */
    internal var compositorBlurBehind: Boolean = false






    init {

        if (targetAdapter?.disablePlatformForceDark == true && android.os.Build.VERSION.SDK_INT >= 29) {

            // HyperOS force-dark inverts the module's own drawing: a dark glass

            // fill would come back light and white icons dark. The module

            // renders both palettes itself, so opt the whole glass subtree out.

            runCatching { setForceDarkAllowed(false) }

        }

    }

    internal var navigationSource: ViewGroup? = navigationSource

    /**
     * The app row's own alpha and background, captured the first time the host took the row
     * over. [restoreNavigationSourceChrome] puts them back when the host goes away.
     */
    private var capturedNavigationAlpha = false
    private var navigationAlphaBeforeHost = 1f
    private var capturedNavigationBackground = false
    private var navigationBackgroundBeforeHost: Drawable? = null

    internal fun invalidateNavigationSnapshot() {
        navigationSnapshotSignature = Long.MIN_VALUE
        pendingNavigationSignature = Long.MIN_VALUE
        pendingNavigationStableFrames = 0
        postInvalidateOnAnimation()
    }

    private var suppressedOriginalChrome: List<View> = suppressedOriginalChrome

    private val qqRecreatedBottomChrome = ArrayList<View>(4)

    private var lastQqBottomChromeProbe = 0L

    /**
     * Height the top copy was given by its installer, measured from the app's own title
     * bar. Zero keeps the shared bar height.
     */
    internal var topBarHeightPx: Int = 0

    /**
     * Width the top copy was given by its installer. Zero lets the shared width rules
     * apply; a positive value pins the bar, which is what keeps a reduced tab count from
     * re-flowing (and moving) the top bar.
     */
    internal var topBarWidthPx: Int = 0

    private val meituanTakeoutScrollBasePaddings = java.util.WeakHashMap<View, IntArray>()

    private var lastMeituanTakeoutScrollProbe = 0L

    internal var adapterMsgHideTime = 0L

    internal var adapterMsgPeekUntil = 0L

        fun snapToNavigationIndex(index: Int) {

        selectedIndex = index

        appNavigationState?.onNavigationTabSwitched(index, slotCount, SystemClock.uptimeMillis())

        dragTarget = visualPositionForNavigationIndex(index)

        positionSpring.snapTo(dragTarget)

        postInvalidateOnAnimation()

    }



    /**

     * Whether this host currently sits on XHS's message tab. Back interception

     * (finish/moveTaskToBack/predictive-back) asks this before consuming the

     * event so sub-pages and other tabs keep the default back behaviour.

     */

    internal fun isAdapterBackPeekTab(): Boolean =

        targetAdapter?.isBackPeekTab(selectedIndex) == true





    /**

     * JD rebuilds its NavigationGroup on page switches. Until the host

     * rebinds, the old source is detached and the freshly created bar draws

     * unsuppressed for several frames 锟?a native-bar flicker at the bottom.

     * Detect the stale source in the same preDraw pass and re-bind by class

     * name, then immediately suppress the new bar and let the content

     * adjuster re-run on the next frame (throttle reset), so the wrong

     * artwork is never drawn.

     */

    /**

     * The file manager owns two bars that replace each other across UI modes:

     * the multi-select action bar (split_action_bar, created/destroyed with

     * each selection session) and the main tab row (bottom_navigation_container,

     * 鏈€杩?娴忚 plus the standalone circular search key). ScannerSession locks

     * `installed` after the first install and never scans again, so every

     * subsequent switch must re-bind here: prefer the live action bar, fall

     * back to the main tab row, and re-parameterize the host (slider, slot

     * count, non-selectable search key, geometry) for the bound bar.

     */

    fun refreshFileManagerNavigationSource(): Boolean {

        if (context.packageName != FILE_MANAGER_PACKAGE) return false

        val current = navigationSource

        val currentId = current?.takeIf {

            it.isAttachedToWindow && it.width > 0 && it.height > 0

        }?.let { viewResourceEntryName(it) }

        val currentIsAuthoritative = currentId == FILE_MANAGER_ACTION_BAR_ID ||

            currentId == FILE_MANAGER_BOTTOM_NAV_ID

        if (currentIsAuthoritative) {

            val content = parent as? ViewGroup ?: return false

            val rect = if (currentId == FILE_MANAGER_BOTTOM_NAV_ID) {

                GlassInstaller.fileManagerMainPillRect(current as ViewGroup, content)

            } else {

                GlassInstaller.floatingPillRect(current as ViewGroup, content)

            } ?: return false

            val params = layoutParams as? FrameLayout.LayoutParams ?: return false

            val targetRight = (content.width - rect.right).coerceAtLeast(0)

            val targetBottom = (content.height - rect.bottom).coerceAtLeast(0)

            if (params.width == rect.width() &&

                params.height == rect.height() &&

                params.leftMargin == rect.left &&

                params.rightMargin == targetRight &&

                params.bottomMargin == targetBottom

            ) return false

            params.width = rect.width()

            params.height = rect.height()

            params.leftMargin = rect.left

            params.rightMargin = targetRight

            params.bottomMargin = targetBottom

            layoutParams = params

            requestLayout()

            navigationSnapshotSignature = Long.MIN_VALUE

            pendingNavigationSignature = Long.MIN_VALUE

            pendingNavigationStableFrames = 0

            postInvalidateOnAnimation()

            return true

        }

        val root = rootView as? ViewGroup ?: return false

        var fresh: ViewGroup? = null

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += root to 0

        var visited = 0

        while (stack.isNotEmpty() && visited++ < 800) {

            val (view, depth) = stack.removeLast()

            if (view is ViewGroup && view !== current && view.isAttachedToWindow &&

                view.width > 0 && view.height > 0 && view.visibility == View.VISIBLE &&

                viewResourceEntryName(view) == FILE_MANAGER_ACTION_BAR_ID

            ) {

                fresh = view

                break

            }

            if (view is ViewGroup && depth < 12) {

                for (index in 0 until view.childCount) {

                    stack += view.getChildAt(index) to (depth + 1)

                }

            }

        }

        if (fresh == null) {

            val fallback = ArrayDeque<Pair<View, Int>>()

            fallback += root to 0

            visited = 0

            while (fallback.isNotEmpty() && visited++ < 800) {

                val (view, depth) = fallback.removeLast()

                if (view is ViewGroup && view !== current && view.isAttachedToWindow &&

                    view.width > 0 && view.height > 0 && view.visibility == View.VISIBLE &&

                    viewResourceEntryName(view) == FILE_MANAGER_BOTTOM_NAV_ID

                ) {

                    fresh = view

                    break

                }

                if (view is ViewGroup && depth < 12) {

                    for (index in 0 until view.childCount) {

                        fallback += view.getChildAt(index) to (depth + 1)

                    }

                }

            }

        }

        val navigation = fresh ?: return false

        val content = parent as? ViewGroup

        val isActionBar = viewResourceEntryName(navigation) == FILE_MANAGER_ACTION_BAR_ID

        navigationSource = navigation

        // Capture before clearing: these are what the row looked like without the module.
        if (!capturedNavigationAlpha) {
            capturedNavigationAlpha = true
            navigationAlphaBeforeHost = navigation.alpha
        }
        if (!capturedNavigationBackground) {
            capturedNavigationBackground = true
            navigationBackgroundBeforeHost = navigation.background
        }

        if (navigation.alpha != 0f) navigation.alpha = 0f

        if (navigation.background != null) navigation.background = null

        sliderEnabled = !isActionBar

        nonSelectableIndices = if (isActionBar) emptySet() else setOf(2)

        if (isActionBar) {

            // Multi-select bar: bind the pill itself (not the full-width

            // container) as the projection source and derive the slot count

            // from its action buttons.

            var pill: ViewGroup? = null

            if (content != null) {

                val rect = GlassInstaller.floatingPillRect(navigation, content)

                if (rect != null) {

                    pill = GlassInstaller.floatingPillSource(navigation, content, rect)

                    val params = layoutParams as? FrameLayout.LayoutParams

                    if (params != null) {

                        params.width = rect.width()

                        params.height = rect.height()

                        params.leftMargin = rect.left

                        params.rightMargin = (content.width - rect.right).coerceAtLeast(0)

                        params.bottomMargin = (content.height - rect.bottom).coerceAtLeast(0)

                        layoutParams = params

                        requestLayout()

                    }

                }

            }

            if (pill == null) {

                pill = (0 until navigation.childCount)

                    .mapNotNull { navigation.getChildAt(it) as? ViewGroup }

                    .firstOrNull { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }

            }

            if (pill != null) {

                navigationSource = pill

                slotCount = pill.childCount.coerceIn(2, 7)

            }

        } else {

            // Main tab row: artwork is drawn from the extracted static cache,

            // so the container itself stays the extraction source. Re-pin the

            // host over the whole native pill (tabs + search circle).

            slotCount = 3

            if (content != null) {

                val rect = GlassInstaller.fileManagerMainPillRect(navigation, content)

                if (rect != null) {

                    val params = layoutParams as? FrameLayout.LayoutParams

                    if (params != null) {

                        params.width = rect.width()

                        params.height = rect.height()

                        params.leftMargin = rect.left

                        params.rightMargin = (content.width - rect.right).coerceAtLeast(0)

                        params.bottomMargin = (content.height - rect.bottom).coerceAtLeast(0)

                        layoutParams = params

                        requestLayout()

                    }

                }

            }

        }

        // Re-extract main-bar artwork after any re-bind; the multi-select bar

        // stays on the generic snapshot path.

        fmStaticIconsPopulated = false

        fmLastCaptureSignature = Long.MIN_VALUE

        fmStableFrames = 0

        navigationSnapshotSignature = Long.MIN_VALUE

        pendingNavigationSignature = Long.MIN_VALUE

        pendingNavigationStableFrames = 0

        postInvalidateOnAnimation()

        Xposed.log(

            "[OfflineGlass][FileManager] re-bound " +

                (if (isActionBar) "action-bar" else "main-tab-row") +

                " slots=$slotCount slider=$sliderEnabled",

        )

        return true

    }



    private var configSyncBroadcastRegistered = false

    // Shared scroll-stop-hide timer; adapter policy supplies the delay.

    private val msgScrollHideRunnable: Runnable = Runnable {

        if (msgUserTouching) {

            postDelayed(msgScrollHideRunnable, 200L)

            return@Runnable

        }

        msgScrolledToStop = true

        postInvalidateOnAnimation()

    }

    internal var msgScrolledToStop = false

    private var msgScrollListenerAttached = false

    private var msgUserTouching = false

    internal val navigationIndex: Int get() = selectedIndex


internal val density = resources.displayMetrics.density

internal val clipPath = Path()

    private val outlinePath = Path()

internal val outerRect = RectF()

    private val expandedBackdropRect = RectF()

    private val selectionRect = RectF()

    private val selectionPath = Path()

    /**
     * The capsule as it is actually drawn, for the adapter's selection-ink pass.
     *
     * The indicator is painted with the base [selectionRect] scaled about its centre (see the
     * `canvas.scale(scaleX, scaleY, centerX, centerY)` in drawSelection), so a clip taken from the
     * unscaled rect reveals visibly less than the pill covers —the fill looked inset from the
     * capsule's edge while a drag was pressed.
     */
    private val selectionClipPath = Path()
    private val selectionClipRect = RectF()

    /** Whether [selectionClipPath] was built for the frame currently being drawn. */
    private var selectionPathValid = false

    /** Whether the last recorded lens scene carried the selection ink; see selectinInkInLensScene(). */
    private var lastSelectionInkInScene = false

    private val lightingPath = Path()

    private val lightingRect = RectF()

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val navigationPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val snapshotCopyPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val outerRenderNode = RenderNode("OfflineGlassOuter")

    private val tabsBackdropNode = RenderNode("OfflineGlassTabsBackdrop")

    private val indicatorCombinedNode = RenderNode("OfflineGlassIndicatorCombined")

    private val indicatorRenderNode = RenderNode("OfflineGlassIndicator")

    private val indicatorShadowNode = RenderNode("OfflineGlassIndicatorShadow")

    private val indicatorCausticsNode = RenderNode("OfflineGlassIndicatorCaustics")

    private val liveBackdropNode = RenderNode("OfflineGlassLiveBackdrop")
    internal val liveBackdropNodeForAdapter: RenderNode get() = liveBackdropNode
    internal val liveBackdropActiveForAdapter: Boolean get() = liveBackdropActive

    // UI-thread scratch only. Clear references after each sweep so replaced pages

    // are not retained; keep capacity instead of allocating containers each frame.

    private val sceneNodesScratch = ArrayList<RenderNode>()

    /**
     * `View.updateDisplayListIfDirty`, resolved once.
     *
     * The backdrop sweep asks each sibling for its RenderNode on every sweep, and a sweep
     * runs on every displayed frame —so this is one of the hottest reflective calls in the
     * module. Through `Xposed.callMethod` each of those calls concatenated a three-part cache
     * key and re-queried the map; resolving it here removes both, leaving just the invoke.
     */
    private val updateDisplayListMethod by lazy {
        Xposed.resolveMethod(android.view.View::class.java, "updateDisplayListIfDirty")
    }
    private val sceneViewsScratch = ArrayList<View>()

    private val sceneContentLocation = IntArray(2)

    private val sceneHostLocation = IntArray(2)

    private var sceneSweepInProgress = false

    /** Last time the live scene was swept; see SCENE_SWEEP_MIN_INTERVAL_MS. */
    private var lastSceneSweepAt = 0L

    private var innerShadowBlurRadius = Float.NaN

    private var innerShadowBlurEffect: RenderEffect? = null

    private val navigationContentNode = RenderNode("OfflineGlassNavigationContent")

internal val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val plainBlurOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

        style = Paint.Style.STROKE

    }

    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }

    private val lightingPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    internal val panelShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val hiddenTabsTintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var drawingHiddenNavigationForOptics = false
    private var lastAppVideoBackdropEpoch = -1

    private val qqStaticIconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val qqStaticLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

        textAlign = Paint.Align.CENTER

        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)

    }

    private val qqNormalIconBitmaps by lazy(LazyThreadSafetyMode.NONE) {

        QQ_NORMAL_ICON_FILES.map(::loadQqTabBitmap)

    }

    private val qqSelectedIconBitmaps by lazy(LazyThreadSafetyMode.NONE) {

        QQ_SELECTED_ICON_FILES.map(::loadQqTabBitmap)

    }




    private var outerLensShader = createRuntimeShader(ROUNDED_RECT_REFRACTION_SHADER, "outer")

    private var combinedOuterLensShader = createRuntimeShader(ROUNDED_RECT_REFRACTION_SHADER, "tabs")

    private var opticalSurfaceOuterLensShader =

        createRuntimeShader(ROUNDED_RECT_REFRACTION_SHADER, "optical-surface-outer")

    private var indicatorLensShader = createRuntimeShader(ROUNDED_RECT_DISPERSION_SHADER, "indicator")

    private val outerBloomShader = runCatching { RuntimeShader(BLOOM_STROKE_DUAL_SHADER) }.getOrNull()

    private val indicatorBloomShader = runCatching { RuntimeShader(BLOOM_STROKE_DUAL_SHADER) }.getOrNull()

    private var bloomTiltRegistered = false



    /** Keep the shared tilt sensor alive only while this host actually draws bloom strokes. */

    private fun updateBloomTiltRegistration() {

        val want = isAttachedToWindow &&

            windowVisibility == View.VISIBLE &&

            config.outlineEnabled

        if (want == bloomTiltRegistered) return

        bloomTiltRegistered = want

        if (want) BloomTiltTracker.register(this) else BloomTiltTracker.unregister(this)

    }

    /**
     * Last-chance registration for the tilt-driven outline.
     *
     * `onAttachedToWindow` and `onWindowVisibilityChanged` normally cover this, but a host
     * added while the window is already visible can miss both transitions (the top copy is
     * added to the content frame after the activity has settled). A host that draws its bloom
     * but never joined [BloomTiltTracker] paints one fixed light direction, which reads as
     * "描边不会动". Drawing is the one moment we know the view is actually on screen.
     */
    private fun ensureBloomTiltRegistration() {
        if (bloomTiltRegistered) return
        if (!config.outlineEnabled) return
        if (!isAttachedToWindow || !isShown || windowVisibility != View.VISIBLE) return
        bloomTiltRegistered = true
        BloomTiltTracker.register(this)
    }

    private var outerGlassEffect: RenderEffect? = null

    private var opticalSurfaceOuterGlassEffect: RenderEffect? = null

    private var tabsBackdropEffect: RenderEffect? = null



internal var selectedIndex = 0
    internal val adapterSlotCount: Int get() = slotCount
    internal val adapterSelectedIndex: Int get() = selectedIndex
    internal fun adapterShowDuringScroll() {
        removeCallbacks(msgScrollHideRunnable)
        if (msgScrolledToStop) {
            msgScrolledToStop = false
            postInvalidateOnAnimation()
        }
    }
    internal fun adapterHideAfter(delayMs: Long) {
        removeCallbacks(msgScrollHideRunnable)
        postDelayed(msgScrollHideRunnable, delayMs)
    }
    internal fun updateAdapterSlotCount(count: Int, selected: Int) {
        if (count !in 1..7 || count == slotCount) return
        slotCount = count
        selectedIndex = selected.coerceIn(0, count - 1)
        dragTarget = selectedIndex.toFloat()
        indicatorPosition = dragTarget
        positionSpring.snapTo(dragTarget)
        updateHostLayoutForConfig()
        invalidate()
    }
    internal fun adapterResolvedSelection(): Int? = selectionForFrame()

    /**
     * Selection resolved once per frame.
     *
     * [resolveSelectedFromViewState] walks the slot container and each slot's subtree to
     * work out which entry the app has selected. It is reached up to five times in a single
     * frame —three times from the pre-draw listener (one of them inside a lambda), once
     * from dispatchDraw, once from the snapshot path —and every call repeats the whole
     * walk for an answer that cannot change between them. Measured at ~21 us per call, that
     * is ~3 ms/second of duplicated work at 165 Hz.
     *
     * The stamp is a millisecond clock rather than an explicit begin/clear pair: the
     * pre-draw listener and dispatchDraw land in the same millisecond within a frame, and a
     * timestamp invalidates itself. An explicit reset would have a hole —if the pre-draw
     * listener ever skipped a frame, dispatchDraw would reuse a stale selection forever.
     */
    private var frameSelection: Int? = null

    private var frameSelectionAt = -1L

    private fun selectionForFrame(): Int? {
        val now = SystemClock.uptimeMillis()
        if (now == frameSelectionAt) return frameSelection
        frameSelectionAt = now
        return resolveSelectedFromViewState().also { frameSelection = it }
    }

    private var selectionInitialized = false

    private var dragTarget = 0f

    private var indicatorPosition = 0f

    private var indicatorVelocity = 0f

    private var pressProgress = 0f

    private var pressSlotScales = FloatArray(0)

    /** Last target handed to each slot spring; lets an unchanged frame skip the re-arm. */
    private var pressSlotTargets = FloatArray(0)

    /** Stable [FloatArray.asList] view of [pressSlotScales], rebuilt only when the slot count changes. */
    private var pressSlotScalesList: List<Float> = emptyList()

    private val pressSlotSprings = ArrayList<SpringFloat>()

    private var outerPanelScale = 1f

    private var indicatorScaleX = 1f

    private var indicatorScaleY = 1f

    private var interactiveHighlightProgress = 0f

    private var touchX = 0f

    private var touchY = 0f

    private var lastTouchX = 0f

    private var gestureStartX = 0f

    private var gestureStartY = 0f

    private var touching = false

    /**
     * Safety heartbeat for the frame pump while the video source is static.
     *
     * The pump can stop only when the adapter says the video stopped *and* no bar animation is
     * running, but the adapter cannot see everything (a page swap that reuses the same surface,
     * an app-side change we do not hook). One repaint per second costs nothing and guarantees
     * the screen cannot stay stale.
     */
    private var lastStaticPumpAt = 0L

    private val staticPumpHeartbeatMs = 1000L

    /** Scratch for [isOnScreen]; reused because this runs from every frame pump tick. */
    private val screenVisibilityScratch = Rect()

    /**
     * True when this host actually occupies part of the window —not merely "visible" in the
     * View sense.
     *
     * `isShown` only checks `visibility` up the ancestor chain; it says nothing about
     * position. Douyin pushes the whole tab host (and both glass copies with it) off-screen
     * on a profile or detail page while leaving every `visibility` at `VISIBLE`, so the
     * pumps kept running: measured `fps=30`, `panel~115us`, `disp~350us` on a page where no
     * glass was on screen at all. Those are pixels nobody can see.
     *
     * `getGlobalVisibleRect` climbs the ancestor chain and is what actually answers "is any
     * of this on screen". Against ~450us of drawing per frame it is free — but one frame
     * asks the question up to three times (onDraw guard, the pump, dispatchDraw guard), and
     * each call is two ancestor walks. The millisecond stamp collapses those to one:
     * geometry cannot change mid-frame and both draw passes share a millisecond.
     */
    /** Memo for [isOnScreen]; the answer cannot change within one frame. */
    private var visibilityStampAt = -1L

    private var visibilityStampResult = false

    private fun isOnScreen(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now == visibilityStampAt) return visibilityStampResult
        visibilityStampAt = now
        visibilityStampResult = isShown && alpha > 0.001f &&
            getGlobalVisibleRect(screenVisibilityScratch)
        return visibilityStampResult
    }

    /**
     * Transform signature of the last recorded indicator lens scene.
     *
     * That scene only references [indicatorCombinedNode] under a translate + inverse-scale, so
     * its recording stays valid until the transform moves. See drawSelection.
     */
    private var lastIndicatorTransformKey = Long.MIN_VALUE

    /** Last time the drag applied a motion event; see DRAG_UPDATE_MIN_INTERVAL_MS. */
    private var lastDragUpdateAt = 0L

    private var indicatorGestureActive = false

    private var pendingTapIndex = -1

    /** Detached action button the current gesture started on: 0 leading, 1 trailing, -1 none. */
    private var pendingActionBand = -1

    /** Whether the current gesture started on the action band inside the pill. */
    private var pendingInlineAction = false

    private var pendingTapMoved = false

internal val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var panelDragOffset = 0f

    private var releaseGeneration = 0

    private val easeOut = PathInterpolator(0f, 0f, 0.58f, 1f)



    private var backdrop: Bitmap? = null

    private var liveBackdropActive = false

    private var liveBackdropSignature = Long.MIN_VALUE

    private var pendingBackdropSignature = Long.MIN_VALUE

    private var pendingBackdropStableFrames = 0

    private var liveBackdropStableOffsetX = Int.MIN_VALUE

    private var liveBackdropStableOffsetY = Int.MIN_VALUE

    private var opticalRefreshGeneration = 0

    private var liveBackdropLogged = false

    private var captureListener: ViewTreeObserver.OnPreDrawListener? = null

    private var sourceVisibilityListener: ViewTreeObserver.OnPreDrawListener? = null

    private var lastCapture = 0L

    private var capturing = false

    /**
     * Last touchable flag pushed onto the native row, and the row it was pushed to.
     *
     * The pre-draw listener re-applies bar visibility every frame, and the default
     * `setNativeBarTouchable` walks the whole native row (each Douyin cell plus its icon/label
     * children) to write the same two flags. A flag write only ever needs to happen when the
     * answer or the row changed.
     */
    private var lastTouchableApplied: Boolean? = null

    private var lastTouchableSource: WeakReference<View>? = null

    /** Last time the native row's touch flags were (re)applied; the 500 ms safety net. */
    private var lastTouchableAt = 0L

    /** Last badge drawn, so a new unread count can request the one repaint it needs. */
    private var lastNavigationBadge: NavigationBadge? = null

    private var qqStaticBackdropRefreshPending = context.packageName == QQ_PACKAGE

    private var qqStaticBackdropRefreshGeneration = 0

    private var detectedContentDark: Boolean? = null

    private var lastThemeProbe = 0L

    private var lastConfigPoll = 0L

    private var configPollPending = false

    private var configPollGeneration = 0L

    /**
     * Last diagnostic line this host emitted, plus when. Repeating polls (the 1 s config
     * poll, per-frame reads) produce the same sentence hundreds of times; keeping the text
     * means only a *change* is worth a new line. [DIAG_REPEAT_MS] alone would still let a
     * once-a-minute heartbeat through, so this is the change gate and nothing else.
     */
    private var lastDiagMessage = ""
    private var lastDiagAt = 0L

    /**
     * Emits [message] only when it differs from the previous one, or when
     * [DIAG_REPEAT_MS] has passed. Returns true when the line was actually logged.
     */
    private fun diagLogGate(message: String): Boolean {
        if (!GLASS_HOST_DIAG_ENABLED) return false
        val now = SystemClock.uptimeMillis()
        if (message == lastDiagMessage && now - lastDiagAt < DIAG_REPEAT_MS) return false
        lastDiagMessage = message
        lastDiagAt = now
        return true
    }

    private var nativeBlurActive = false

    private var lastPageVisibilityCheck = 0L

    /** Last time the host's ancestor clip flags were walked; see releaseAncestorClipping. */
    private var lastAncestorClipReleaseAt = 0L

    private var pageAllowsNavigation = true

    private var adapterLastReadySourceAt = 0L

    private val originalChromeVisibility = LinkedHashMap<View, Int>().apply {

        suppressedOriginalChrome.forEach { put(it, it.visibility) }

    }

    private var lastQqLayerSignature = ""

    private var opticalSurfaceSnapshotReady = false

    private var opticalSurfaceTextureView: TextureView? = null

    private var opticalSurfaceLastTextureProbe = 0L

    private var opticalSurfacePixelCopyInFlight = false

    private var opticalSurfacePixelCopyScratch: Bitmap? = null

    private var opticalSurfaceBackdropPadding = 0f

    private var opticalSurfaceNativeBlurView: View? = null

    private var opticalSurfaceNativeBlurActive = false

    private var opticalSurfaceNativeIndicatorView: View? = null

    private var opticalSurfaceNativeIndicatorActive = false

    private var opticalSurfaceNativeIndicatorShader =

        createRuntimeShader(ROUNDED_RECT_DISPERSION_SHADER, "optical-surface-native-indicator")

    private var opticalSurfaceNativeIndicatorEffectSignature = Long.MIN_VALUE

    private var opticalSurfaceSurfaceView: SurfaceView? = null
    internal val opticalSurfaceViewForAdapter: SurfaceView? get() = opticalSurfaceSurfaceView

    private var opticalSurfaceSurfaceReady = false

    private var opticalSurfaceSurfacePipelineActive = false

    private var lastAppVideoDiagLog = 0L

    // Written from the PixelCopy callback thread, read on the UI thread to

    // gate the copy cadence; must be visible across threads promptly.

    @Volatile private var opticalSurfaceRootCopyInFlight = false

    private var opticalSurfaceRootCopyScratch: Bitmap? = null


    private val opticalSurfacePixelCopyHandler = Handler(Looper.getMainLooper())

    private var compositorPixelCopyThread: HandlerThread? = null

    private var compositorPixelCopyHandler: Handler? = null

    /** Serializes surface renders between the UI thread and the copy callback thread. */

    private val surfaceRenderLock = Any()

    private val fallbackOpticalSurfaceRenderQueue by lazy(LazyThreadSafetyMode.NONE) {
        io.github.xzdw.dylt.hook.adapters.OpticalSurfaceRenderQueue()
    }

    private val sharedOpticalBlurNode = RenderNode("LiquidTab-SharedOpticalBlur").apply {

        setClipToBounds(false)

    }

    private var sharedOpticalBlurEffect: RenderEffect? = null

    private var sharedOpticalBlurPrepared = false

    private val reuseOpticalBlur = targetAdapter?.reuseOpticalBlur == true

    private val opticalLightingBlurRadii = floatArrayOf(Float.NaN, Float.NaN)

    private val opticalLightingBlurEffects = arrayOfNulls<RenderEffect>(2)


    // JD's feed owns a large dynamic hierarchy.  Its cleanup pass must never

    // run as a timer from pre-draw: clearing backgrounds while cards are being

    // rebound makes only the information flow flash.  A single cleanup after a

    // newly bound native bar is enough to remove the old bar artwork.

    // A JD NavigationGroup stays attached while the user changes home channels.

    // The structural correction must run once per actual bar instance, not as a

    // periodic probe, otherwise ordinary channels continuously re-layout.

    // JD page switches re-apply the bar stack's LayoutParams (or re-parent the

    // navigation row into a fresh uncollapsed container). The once-per-source

    // guard then kept a restored native bar on screen, covering the feed.

    // Track the collapsed bar and the extended spines so every preDraw can

    // cheaply verify the structural correction is still in effect.

    // JD's React Native renderer lays out the flash-page controls repeatedly.

    // Moving them with offsetTopAndBottom is undone by each layout pass, which

    // makes the controls visibly oscillate between the native and safe slots.

    // Keep the original property value for detach and anchor with translation

    // instead: translations survive ordinary RN layout passes and move hit tests

    // together with the rendered control.

    private var themeTransitionUntil = 0L

    private var sourceSizingGeneration = 0

    private var navigationRefreshGeneration = 0

    private var navigationSnapshot: Bitmap? = null

    private var navigationCaptureScratch: Bitmap? = null

    private var navigationSnapshotSignature = Long.MIN_VALUE

    private var pendingNavigationSignature = Long.MIN_VALUE

    private var pendingNavigationStableFrames = 0

    private var navigationSnapshotFrozenUntil = 0L

    // Keep completed bar inputs during native page construction so the
    // indicator does not share its frame budget with backdrop/icon discovery.
    private var barAnimationPriorityUntil = 0L
    private var lastPriorityNavigationProbeAt = 0L

    // File manager static icon cache —the main tab row (鏈€杩?娴忚/鎼滅储) is

    // extracted once from the settled native bar and re-drawn independently,

    // mirroring the JD static-icon path; the search key is the standalone

    // circular slot. The multi-select action bar never uses this cache.

    private val fmStaticSlots = arrayOfNulls<Bitmap>(3)

    private var fmStaticIconsPopulated = false

    private var fmLastCaptureSignature = Long.MIN_VALUE

    private var fmStableFrames = 0

    private val fmIconPaint = Paint(Paint.ANTI_ALIAS_FLAG)


    // The navigation snapshot bitmap is static between tab switches, yet

    // drawNavigationContent used to re-record navigationContentNode on every

    // glass render. Keyed by (bitmap, size); the recording paints with the

    // reset navigationPaint (colorFilter=null, alpha=255), so the cached

    // display list is bit-identical to a fresh recording.

    private var navigationContentRecordedBitmap: Bitmap? = null

    private var navigationContentRecordedWidth = 0

    private var navigationContentRecordedHeight = 0

    private var lastNavigationNormalization = "none"

    private var lastGeometryLogSignature = ""

    // Last (shouldShow, inputs) signature reported by the bar-visibility pre-draw pass.
    // The page gate reads live view state, so this only throttles the log line.
    private var lastShowDiagKey: String? = null






    private var perfFrames = 0

    private var perfPrepareNs = 0L

    private var perfPrepareMaxNs = 0L

    private var perfPanelNs = 0L

    private var perfPanelMaxNs = 0L

    private var perfDispatchNs = 0L

    private var perfDispatchMaxNs = 0L

    private var perfSceneSweeps = 0

    private var perfSceneSiblingVisits = 0

    private var perfSceneReRecords = 0

    private var perfEffectRebuilds = 0

    private var perfNodeRecords = 0

    private var perfSurfaceRenders = 0

    private var perfLastLogUptime = 0L



    // Stiffness raised from 1000: at the old value the settle after a release (and any
    // programmatic move) took long enough to read as lag. Still critically damped
    // (ratio 1), so it is quicker without overshooting.
    private val positionSpring = SpringFloat(0f, 2_500f, 1f, 0.001f) { value, velocity ->

        indicatorPosition = value

        if (touching) {

            velocitySpring.animateTo(velocity / (visualSlotCount() - 1).coerceAtLeast(1))

        }

        invalidate()

    }

    // Snappier than 300/0.5: the tilt should track the gesture rather than trail it.
    private val velocitySpring = SpringFloat(0f, 600f, 0.7f, 0.01f) { value, _ ->

        indicatorVelocity = value

        invalidate()

    }

    private val pressSpring = SpringFloat(0f, 1_000f, 1f, 0.001f) { value, _ ->

        pressProgress = value.coerceIn(0f, 1f)

        updateOuterScale()

        invalidate()

    }

    private val scaleXSpring = SpringFloat(1f, 250f, 0.6f, 0.001f) { value, _ ->

        indicatorScaleX = value

        invalidate()

    }

    private val scaleYSpring = SpringFloat(1f, 250f, 0.7f, 0.001f) { value, _ ->

        indicatorScaleY = value

        invalidate()

    }

    private val interactiveSpring = SpringFloat(0f, 300f, 0.5f, 0.001f) { value, _ ->

        interactiveHighlightProgress = value.coerceIn(0f, 1f)

        invalidate()

    }

    // The rubber-band return was 300/0.5, an under-damped spring that took several
    // hundred ms to stop wobbling after a release —the most visible source of the
    // "laggy" feel. Nearly critically damped now, so it returns at once.
    private val panelOffsetSpring = SpringFloat(0f, 900f, 0.9f, 0.01f) { value, _ ->

        panelDragOffset = value

        updatePanelTranslation()

        invalidate()

    }



    init {

        setWillNotDraw(false)

        // Mi Health inserts/re-attaches its main fragment after returning from

        // CommonBaseActivity. Its transparent native TabLayout then stops

        // being a reliable physical hit target. Make the retained glass host

        // itself the explicit target; dispatchTouchEvent still routes the

        // committed tab through TabLayout.Tab.select().

        isClickable = targetAdapter?.ownsNavigationTap == true

        isFocusable = false

        clipChildren = false

        clipToPadding = false

        outlineProvider = BottomBarSquircleOutlineProvider(

            radius = { outerCornerRadiusPx() },

            smoothing = { config.cornerSmoothing },

            silhouette = { clipPath },

        )

        clipToOutline = false

    }



    /** Carries selection across QQ's in-place navigation rebuild on theme changes. */

    fun adoptRuntimeStateFrom(previous: GlassHostLayout) {

        selectedIndex = previous.selectedIndex.coerceIn(0, (slotCount - 1).coerceAtLeast(0))

        selectionInitialized = previous.selectionInitialized

        dragTarget = if (targetAdapter?.snapIndicatorToSelectionOnRebind == true) {

            visualPositionForNavigationIndex(selectedIndex)

        } else {

            previous.indicatorPosition.coerceIn(0f, (slotCount - 1).coerceAtLeast(0).toFloat())

        }

        indicatorPosition = dragTarget

        positionSpring.snapTo(dragTarget)

        velocitySpring.snapTo(0f)

        pressSpring.snapTo(0f)

        scaleXSpring.snapTo(1f)

        scaleYSpring.snapTo(1f)

        interactiveSpring.snapTo(0f)

        panelOffsetSpring.snapTo(0f)

    }



    internal fun onAdapterBlockingOverlayVisibilityChanged(visible: Boolean) {

        post {

            if (!isAttachedToWindow) return@post

            applyBarVisibility(!visible)

        }

    }



    /**
     * Applies the decided bar visibility as a hard cut.
     *
     * The bar is shown and hidden outright —no expand/settle animation —with the transform
     * parked on its rest values so nothing a previous transition left behind can render it off
     * its anchor. Everything that must accompany the switch lives here rather than in a
     * caller: the native row is touchable only while the bar is on screen (a hidden bar must
     * not intercept touches meant for the content underneath), and a bar that leaves forces
     * the next appearance to capture a fresh live scene instead of the frame frozen at exit.
     */
    private fun applyBarVisibility(shouldShow: Boolean) {

        val wanted = if (shouldShow) View.VISIBLE else View.GONE

        if (visibility != wanted) visibility = wanted

        if (alpha != if (shouldShow) 1f else 0f) alpha = if (shouldShow) 1f else 0f

        if (scaleX != 1f) scaleX = 1f

        if (scaleY != 1f) scaleY = 1f

        if (translationY != 0f) translationY = 0f

        val touchSource = navigationSource

        val touchNow = SystemClock.uptimeMillis()

        // The flag write only needs a change, but Douyin can rebuild the row's children in
        // place (they arrive clickable), so a slow re-apply keeps the "no invisible tap
        // target" guarantee without walking the row on every pre-draw.
        if (lastTouchableApplied != shouldShow || lastTouchableSource?.get() !== touchSource ||

            touchNow - lastTouchableAt >= TOUCHABLE_REAPPLY_INTERVAL_MS

        ) {

            lastTouchableApplied = shouldShow

            lastTouchableSource = touchSource?.let(::WeakReference)

            lastTouchableAt = touchNow

            setNativeBarTouchable(touchSource, shouldShow)

        }

        if (shouldShow) {

            if (targetAdapter?.refreshOpticsOnBarShow == true) {

                scheduleOpticalPipelineRefresh(longArrayOf(0L, 16L, 48L, 96L), "bar-show")

                scheduleNavigationSnapshotRefresh(longArrayOf(0L, 48L, 120L))

            }

        } else {

            liveBackdropSignature = Long.MIN_VALUE

            pendingBackdropSignature = Long.MIN_VALUE

            pendingBackdropStableFrames = 0

        }

    }



    /**

     * QQ recreates its tab row in-place while switching colour modes. Keep this

     * renderer (and therefore the Backdrop, springs and touch state) alive and

     * atomically point it at the completed replacement row instead of replacing

     * the whole glass View for one configuration frame.

     */

    fun rebindNavigationSource(

        source: ViewGroup,

        chrome: List<View>,

        rawConfig: GlassConfig,

    ) {

        if (targetAdapter?.usesOpticalSurfacePipeline == true && navigationSource !== source) {

            opticalSurfaceTextureView = null

            opticalSurfaceLastTextureProbe = 0L

            opticalSurfaceSnapshotReady = false

        }

        navigationSource = source

        lastTouchableApplied = null

        lastTouchableSource = null

        lastTouchableAt = 0L

        suppressNativeQqBottomChrome(source, forceProbe = true)

        if (targetAdapter?.ownsHostNativeChrome == true) {
            appNavigationState?.suppressHostNativeChrome(source, rootView, selectionForFrame() ?: -1)
        }

        if (source != null && targetAdapter?.suppressSourceOnHostRebind == true) {
            if (suppressesNativeChrome()) targetAdapter?.suppressNativeBottomChromeOnRebind(source)
        }

        appNavigationState?.suppressNativeChromeEarly(source)

        suppressNativeMeituanTakeoutBottomBar(source)

        source?.let { if (suppressesNativeChrome()) targetAdapter?.suppressNativeBottomChrome(this, it) }

        appNavigationState?.suppressNativeChrome(source)

        source?.let { if (suppressesNativeChrome()) targetAdapter?.suppressNativeBottomChrome(this, it) }


        suppressNativeRedrawnBottomBar(source)

        chrome.forEach { view ->

            originalChromeVisibility.putIfAbsent(view, view.visibility)

            view.visibility = View.INVISIBLE

        }

        suppressedOriginalChrome = chrome

        val updated = packageConfig(rawConfig)

        if (updated != config) applyLiveConfig(updated)

        // A Bilibili page replacement can rebind this host while its temporary

        // content root still carries the previous page's inset geometry. Refresh

        // the screen-anchored layout even when configuration itself is unchanged.

        if (targetAdapter?.refreshHostGeometryOnRebind == true) updateHostLayoutForConfig()

        beginThemeTransition(resizeSourceAfterSettle = false)

        scheduleNavigationSnapshotRefresh(longArrayOf(240L, 720L, 1_500L))

        requestQqStaticBackdropRefresh(160L)

        visibility = View.VISIBLE

        bringToFront()

        post { logGeometryIfChanged("rebind") }

        invalidate()

    }



    /**

     * Release clipping only inside the application's content hierarchy.

     *

     * The liquid slider and the show/hide overshoot need room outside the host

     * bounds, but the freeform-window corner mask belongs to the DecorView (and

     * other window-level parents above android.R.id.content). Clearing their

     * outline provider makes the whole small window rectangular. Keep every

     * ancestor outline intact and stop before the application content root;

     * GlassInstaller already releases clipping on that content root itself.

     */

    /**
     * The Activity content root, resolved once.
     *
     * `findViewById(android.R.id.content)` is a recursive search of the whole window
     * hierarchy, and [releaseAncestorClipping] runs it from the pre-draw listener —165
     * times a second on this panel. The view it looks for is the Activity's content
     * container, which does not change while the host is attached, so the search is done
     * once and re-validated with an attached check.
     */
    private var contentRootRef: WeakReference<View>? = null

    private fun contentRootView(): View? {
        contentRootRef?.get()?.let { if (it.isAttachedToWindow) return it }
        val found = rootView.findViewById<View>(android.R.id.content)
        contentRootRef = found?.let { WeakReference(it) }
        return found
    }

    private fun releaseAncestorClipping() {

        if (targetAdapter?.releaseOutlineClipping == true) {

            clipToOutline = false

            outlineProvider = null

        }

        // The ancestor chain (host parent up to android.R.id.content) only changes when the
        // host is re-parented. Walking and writing the same two clip flags on every pre-draw
        // was pure repeated work; enforceContentClipping stays per-frame because an adapter
        // may need it.
        val now = SystemClock.uptimeMillis()

        if (now - lastAncestorClipReleaseAt >= ANCESTOR_CLIP_RELEASE_INTERVAL_MS) {

            lastAncestorClipReleaseAt = now

            releaseAncestorClippingNow()

        }

        appNavigationState?.enforceContentClipping()

    }


    private fun releaseAncestorClippingNow() {

        val contentRoot = contentRootView()

        val maxDepth = targetAdapter?.contentAncestorMaxDepth ?: 12

        var ancestor = parent as? ViewGroup ?: return

        var guard = 0

        while (contentRoot != null && guard < maxDepth && ancestor !== contentRoot) {

            ancestor.clipChildren = false

            ancestor.clipToPadding = false

            val next = ancestor.parent as? ViewGroup ?: break

            if (next === ancestor) break

            ancestor = next

            guard++

        }

    }



    private fun saveBurnInOffset(canvas: Canvas): Int {
        if (burnInOffsetX == 0f && burnInOffsetY == 0f) return -1
        val save = canvas.save()
        canvas.translate(burnInOffsetX, burnInOffsetY)
        return save
    }

    private fun restoreBurnInOffset(canvas: Canvas, save: Int) {
        if (save >= 0) canvas.restoreToCount(save)
    }

    private fun syncBurnInSchedule() {
        if (!isAttachedToWindow) {
            burnInScheduled = false
            removeCallbacks(burnInRunnable)
            return
        }
        if (!config.antiBurnIn) {
            removeCallbacks(burnInRunnable)
            burnInScheduled = false
            if (burnInOffsetX != 0f || burnInOffsetY != 0f) {
                burnInOffsetX = 0f
                burnInOffsetY = 0f
                invalidate()
            }
            return
        }
        if (!burnInScheduled) {
            burnInScheduled = true
            postDelayed(burnInRunnable, BURN_IN_INTERVAL_MS)
        }
    }

    override fun onAttachedToWindow() {

        super.onAttachedToWindow()

        targetAdapter?.requestedFrameRateMax?.takeIf { Build.VERSION.SDK_INT >= 35 }?.let { maximum ->
            val requestedRate = display?.supportedModes
                ?.maxOfOrNull { it.refreshRate }
                ?.coerceAtMost(maximum)
                ?: maximum
            setRequestedFrameRate(requestedRate)
        }

        context.findActivity()?.let { activity ->

            if (targetAdapter?.navigationImmersionOverride != null) {

                GlassInstaller.updateAdapterNavigationBarForPage(activity)

            } else {

                GlassInstaller.ensureNavigationBarImmersion(activity)

            }

        }

        lastTouchableApplied = null

        lastTouchableSource = null

        lastTouchableAt = 0L

        releaseAncestorClipping()

        syncBurnInSchedule()

        navigationSource?.let {

            val root = rootView

            sourceVisibilityListener = ViewTreeObserver.OnPreDrawListener {

                maybeReloadConfig()

                // The host can remain attached while an app temporarily hides

                // its liquid bar and replaces page content. Keep the Activity's

                // navigation-edge layout state independent from bar visibility,

                // and repair only if the app changed the window flags or colour.

                val forceNavigationBarImmersed = appNavigationState?.forceNavigationBarImmersed(this)

                context.findActivity()?.let { activity ->

                    if (forceNavigationBarImmersed == false) {

                        GlassInstaller.setNavigationBarImmersion(activity, false)

                    } else if (targetAdapter?.navigationImmersionOverride != null) {

                        GlassInstaller.updateAdapterNavigationBarForPage(activity)

                    } else {

                        GlassInstaller.ensureNavigationBarImmersion(activity)

                    }

                }

                for (view in suppressedOriginalChrome) {

                    if (view.visibility != View.INVISIBLE) view.visibility = View.INVISIBLE

                }

                // Mi Health rebuilds its TabLayout when returning from device

                // sub-pages; re-bind a detached source BEFORE this frame's

                // suppression, snapshot and readiness checks consume it.

                if (targetAdapter?.ownsNavigationTap == true) {

                    appNavigationState?.refreshNavigationSource(this)

                }

                // The file manager's multi-select action bar is destroyed when

                // selection mode ends and re-inflated on the next session;

                // re-bind the fresh split_action_bar before suppression and

                // snapshot checks consume the stale source. Its main tab row

                // artwork is extracted here once the native bar has settled.


                val source = navigationSource

                suppressNativeQqBottomChrome(source)

                if (targetAdapter?.ownsHostNativeChrome == true) {
                    appNavigationState?.suppressHostNativeChrome(source, rootView, selectionForFrame() ?: -1)
                }

                if (source != null && targetAdapter?.suppressSourceOnHostRebind == true) {
                    if (suppressesNativeChrome()) targetAdapter?.suppressNativeBottomChromeOnRebind(source)
                }

                val prioritizeTapTransition = SystemClock.uptimeMillis() < barAnimationPriorityUntil

                if (!prioritizeTapTransition && targetAdapter?.hasPerFrameScene == true) {
                    stabilizeLiftedHostPosition()
                    appNavigationState?.onHostPreDraw(
                        this, navigationSource,
                        { (selectionForFrame() ?: selectedIndex).coerceIn(0, (slotCount - 1).coerceAtLeast(0)) },
                        config.enabled, density,
                        glassHostHeightPx(density, config, context.packageName),
                        { adapterNavigationSurfaceColor() },
                    )
                }

                appNavigationState?.suppressNativeChromeEarly(source)

                appNavigationState?.refreshNavigationSource(this)
                appNavigationState?.suppressNativeChrome(navigationSource ?: source)

                appNavigationState?.adjustFloatingActions(this, navigationSource)

                // Resolve the selected Surface-backed homepage channel before

                // positioning its independent bottom overlays.

                if (!prioritizeTapTransition) appNavigationState?.onHostFrame(this)

                suppressNativeMeituanTakeoutBottomBar(source)

                adjustMeituanTakeoutScrollSafety()

                appNavigationState?.refreshNavigationSource(this)
                source?.let { if (suppressesNativeChrome()) targetAdapter?.suppressNativeBottomChrome(this, it) }

                appNavigationState?.suppressNativeChrome(source)

                appNavigationState?.adjustFloatingActions(this, navigationSource)

                source?.let { if (suppressesNativeChrome()) targetAdapter?.suppressNativeBottomChrome(this, it) }



                appNativeChromeController?.update(this, source)

                // JD rebuilds its NavigationGroup when switching pages; until

                // the host rebinds, the stale source left the freshly created

                // bar unsuppressed for visible frames (native-bar flicker).

                // Re-bind by class name before suppressing, like Weibo does.

                suppressNativeRedrawnBottomBar(source)

                val now = SystemClock.uptimeMillis()

                releaseAncestorClipping()


                val sourceReady = source != null && source.isAttachedToWindow &&

                    source.width > 0 && source.height > 0 &&

                    if (targetAdapter?.sourceParentVisibilityOnly == true) {

                        (source.parent as? View)?.ancestorsAreVisible() != false

                    } else {

                        source.ancestorsAreVisible()

                    }

                if (targetAdapter?.sourceSwapGraceMs != 0L && sourceReady) {

                    adapterLastReadySourceAt = now

                }

                // WeChat swaps LauncherUIBottomTabView after returning from a

                // conversation or another app. Preserve the already-rendered

                // host during that short detach instead of playing a false exit

                // animation. The exact replacement is dispatched by the native

                // tab's onLayout hook and rebinds within this bounded window.

                val adapterSourceSwapGrace = (targetAdapter?.sourceSwapGraceMs ?: 0L) > 0L &&

                    adapterLastReadySourceAt > 0L &&

                    now - adapterLastReadySourceAt <= (targetAdapter?.sourceSwapGraceMs ?: 0L)

                val sourceBeingRebuilt = source != null && now < themeTransitionUntil

                if (targetAdapter?.ownsNavigationTap == true) {

                    // A returned settings page can append/re-attach content

                    // after this host. Reassert the already-visible glass as

                    // the physical touch target without changing its geometry.

                    if (!isClickable) isClickable = true

                    if (!isEnabled) isEnabled = true

                    // bringToFront() unconditionally requests a layout; calling

                    // it every preDraw frame kept the UI thread in a permanent

                    // relayout loop (starved page switches and view dumps).

                    val hostParent = parent as? ViewGroup

                    if (hostParent != null &&

                        hostParent.getChildAt(hostParent.childCount - 1) !== this

                    ) bringToFront()

                }

                // While Shorts' back-peek window is open the bar must reappear

                // even though the Shorts pager may have detached the native row

                // underneath it; the retained navigation snapshot still supplies

                // the projected content.

                val retainsHiddenNavigation = appNavigationState?.retainNavigationWhenNativeRowHidden(
                    selectedIndex,
                ) == true

                val pageAllows = currentPageAllowsNavigation()

                val shouldShow = config.enabled &&

                    (sourceReady || sourceBeingRebuilt || adapterSourceSwapGrace ||
                        appNavigationState?.retainNavigationWithoutNativeSource(selectedIndex) == true ||
                        retainsHiddenNavigation) &&

                    pageAllows

                // Diagnostic: the bar hides on the profile page but was not coming back on
                // the feed. Report every input to shouldShow so the blocking term is visible.
                // Compiled out for release: this string is built on every pre-draw pass.
                if (GLASS_HOST_DIAG_ENABLED) runCatching {
                    val showKey = "show=$shouldShow enabled=${config.enabled} " +
                        "srcReady=$sourceReady rebuild=$sourceBeingRebuilt grace=$adapterSourceSwapGrace " +
                        "keepNoSrc=${appNavigationState?.retainNavigationWithoutNativeSource(selectedIndex)} " +
                        "keepHidden=$retainsHiddenNavigation pageAllows=$pageAllows " +
                        "src=${source?.javaClass?.simpleName} vis=$visibility"
                    if (showKey != lastShowDiagKey) {
                        lastShowDiagKey = showKey
                        Xposed.log("[OfflineGlass][Show] $showKey")
                    }
                }

                applyBarVisibility(shouldShow)

                syncOpticalVisibilityCompanions()

                true

            }.also { root.viewTreeObserver.addOnPreDrawListener(it) }

        }

        // JD uses a separate compositor-backed optical sheet below this host.

        // Applying native blur to the host itself would cover that sheet and

        // reduce the result to a flat blur without edge/pill refraction.

        nativeBlurActive = targetAdapter?.allowsSystemBackgroundBlur != false && shouldUseNativeBackgroundBlur() &&

            enableMiuiBackgroundBlur()

        if (targetAdapter?.usesDirectOpticalBackdropMode == true) {

            post {

                if (!isAttachedToWindow) return@post

                syncBackdropRendererMode()

            }

        }

        if (config.backdropCapture) installCaptureLoop()


        registerConfigSyncBroadcast()

        // A theme switch gives the replacement Activity its measured size

        // before the new hardware render tree is fully attached. Rebind both

        // the retained source scene and its effects after the first traversals.

        scheduleOpticalPipelineRefresh(longArrayOf(0L, 32L, 96L), "attached")

        updateBloomTiltRegistration()

    }



    override fun onWindowVisibilityChanged(visibility: Int) {

        super.onWindowVisibilityChanged(visibility)

        updateBloomTiltRegistration()

    }



    override fun onDetachedFromWindow() {

        // The watcher re-arms itself every frame, so it must be stopped here or it would
        // keep running (and keep the view reachable) after the host is gone.
        stopVideoFrameWatch()

        removeCallbacks(burnInRunnable)
        burnInScheduled = false

        if (targetAdapter?.requestedFrameRateMax != null && Build.VERSION.SDK_INT >= 35) {
            setRequestedFrameRate(0f)
        }

        configPollGeneration++

        configPollPending = false

        bloomTiltRegistered = false

        BloomTiltTracker.unregister(this)

        opticalRefreshGeneration++

        navigationRefreshGeneration++

        qqStaticBackdropRefreshGeneration++

        qqStaticBackdropRefreshPending = false

        val root = rootView

        captureListener?.let { listener ->

            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(listener)

        }

        captureListener = null

        sourceVisibilityListener?.let { listener ->

            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(listener)

        }

        sourceVisibilityListener = null

        if (configSyncBroadcastRegistered) unregisterConfigSyncBroadcast()

        restoreNavigationSourceChrome()

        appNavigationState?.onHostDetached()





        originalChromeVisibility.forEach { (view, visibility) -> view.visibility = visibility }

        listOf(

            positionSpring,

            velocitySpring,

            pressSpring,

            scaleXSpring,

            scaleYSpring,

            interactiveSpring,

            panelOffsetSpring,

        )

            .forEach(SpringFloat::cancel)

        translationX = 0f

        scaleX = 1f

        scaleY = 1f

        backdrop?.recycle()

        backdrop = null

        opticalSurfacePixelCopyInFlight = false

        opticalSurfaceTextureView = null

        opticalSurfaceLastTextureProbe = 0L

        opticalSurfacePixelCopyScratch?.recycle()

        opticalSurfacePixelCopyScratch = null

        opticalSurfaceBackdropPadding = 0f

        opticalSurfaceNativeBlurView?.let { blur ->

            disableOpticalSurfaceNativeBlur(blur)

            (blur.parent as? ViewGroup)?.removeView(blur)

        }

        opticalSurfaceNativeBlurView = null

        opticalSurfaceNativeBlurActive = false

        opticalSurfaceNativeIndicatorView?.let { indicator ->

            disableOpticalSurfaceNativeBlur(indicator)

            indicator.setRenderEffect(null)

            (indicator.parent as? ViewGroup)?.removeView(indicator)

        }

        opticalSurfaceNativeIndicatorView = null

        opticalSurfaceNativeIndicatorActive = false

        opticalSurfaceNativeIndicatorEffectSignature = Long.MIN_VALUE

        opticalSurfaceSurfaceView?.let { surface ->

            surface.visibility = View.GONE

            (surface.parent as? ViewGroup)?.removeView(surface)

        }

        opticalSurfaceSurfaceView = null

        opticalSurfaceSurfaceReady = false

        opticalSurfaceSurfacePipelineActive = false

        opticalSurfaceRootCopyInFlight = false

        opticalSurfaceRootCopyScratch?.recycle()

        opticalSurfaceRootCopyScratch = null


        compositorPixelCopyThread?.quitSafely()

        compositorPixelCopyThread = null

        compositorPixelCopyHandler = null

        compositorSurfaceProbeScheduled = false

        compositorSurfaceProbeRunning = false

        compositorSurfaceVsyncTick = 0

        compositorSurfaceStaticStreak = 0

        lastSurfaceRenderDark = null

        navigationSnapshot?.recycle()

        navigationSnapshot = null

        navigationContentRecordedBitmap = null

        navigationCaptureScratch?.recycle()

        navigationCaptureScratch = null

        appNavigationState?.dispose()

        fmStaticSlots.forEach { it?.takeUnless(Bitmap::isRecycled)?.recycle() }

        fmStaticSlots.fill(null)

        fmStaticIconsPopulated = false

        fmLastCaptureSignature = Long.MIN_VALUE


        outerRenderNode.setRenderEffect(null)

        tabsBackdropNode.setRenderEffect(null)

        indicatorCombinedNode.setRenderEffect(null)

        indicatorRenderNode.setRenderEffect(null)

        indicatorShadowNode.setRenderEffect(null)

        indicatorCausticsNode.setRenderEffect(null)

        liveBackdropNode.setRenderEffect(null)

        navigationContentNode.setRenderEffect(null)

        outerRenderNode.discardDisplayList()

        tabsBackdropNode.discardDisplayList()

        indicatorCombinedNode.discardDisplayList()

        indicatorRenderNode.discardDisplayList()

        indicatorShadowNode.discardDisplayList()

        indicatorCausticsNode.discardDisplayList()

        liveBackdropNode.discardDisplayList()

        navigationContentNode.discardDisplayList()

        liveBackdropActive = false

        liveBackdropSignature = Long.MIN_VALUE

        pendingBackdropSignature = Long.MIN_VALUE

        pendingBackdropStableFrames = 0

        liveBackdropStableOffsetX = Int.MIN_VALUE

        liveBackdropStableOffsetY = Int.MIN_VALUE

        outerGlassEffect = null

        opticalSurfaceOuterGlassEffect = null

        tabsBackdropEffect = null

        disableMiuiBackgroundBlur()

        super.onDetachedFromWindow()

    }



    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {

        super.onWindowFocusChanged(hasWindowFocus)
        appNavigationState?.onHostWindowFocusChanged(hasWindowFocus)

        if (hasWindowFocus) {

            context.findActivity()?.let { activity ->

                if (appNavigationState?.forceNavigationBarImmersed(this) == false) {

                    GlassInstaller.setNavigationBarImmersion(activity, false)

                } else if (targetAdapter?.navigationImmersionOverride != null) {

                    GlassInstaller.updateAdapterNavigationBarForPage(activity)

                } else {

                    GlassInstaller.ensureNavigationBarImmersion(activity)

                }

            }

        }

        if (!hasWindowFocus && targetAdapter?.hasPerFrameScene == true) {

            // Window lost focus (e.g. user swiped up to home). The Activity

            // is only stopped, not destroyed, so onDetachedFromWindow won't

            // fire. Reset the fold bar translation now to prevent the View

            // from being recycled with a stale translationY when the user

            // returns 锟?that residue is what creates the empty chat block.

            appNavigationState?.onHostFocusLost()

        }

        if (targetAdapter?.liveBackdropFramePump != true) return

        if (hasWindowFocus) {

            postInvalidateOnAnimation()

        }

    }
    private fun restoreQqSteadyStateZ() {

        if (context.packageName != QQ_PACKAGE) return

        translationZ = 0f

    }



    /** Keep compositor-backed optical siblings on the exact host transform. */

internal fun syncOpticalVisibilityCompanions() {
        if (appNavigationState?.syncVisibilityCompanion(this, selectedIndex) == true) return

        if (targetAdapter?.usesOpticalSurfacePipeline != true &&
            targetAdapter?.retainsOpticalSurfaceWhenBarHidden != true &&

            context.packageName != MEITUAN_TAKEOUT_PACKAGE

        ) return

        val visibleDuringTransition = appNavigationState?.opticalSurfaceVisibleDuringTransition(
            visibility, alpha,
        ) ?: (visibility == View.VISIBLE)

        opticalSurfaceSurfaceView?.let { surface ->

            val overflow = opticalSurfaceSurfaceOverflowPx().toFloat()

            surface.pivotX = surface.width * 0.5f

            surface.pivotY = (surface.height - overflow).coerceAtLeast(0f)

            // JD's compositor Surface spans from above the floating bar to

            // the physical screen bottom so it can also carry the gradient

            // veil. Scaling that whole Surface during bar exit pulls its

            // bottom edge away from the window and exposes a white block for

            // one composition frame. Keep the Surface geometry fixed; the

            // bar content itself receives the same transform when rendered.

            if (targetAdapter?.opticalSurfaceFollowsBarTransform == false) {

                surface.scaleX = 1f

                surface.scaleY = 1f

                surface.translationY = 0f

            } else {

                surface.scaleX = scaleX

                surface.scaleY = scaleY

                surface.translationY = translationY

            }

            surface.alpha = alpha

            if (appNavigationState?.directOpticalBackdropActive() == true) {

                // Keep JD's transparent Surface attached while normal tabs use

                // the direct RenderNode scene. GONE destroys its BLAST queue;

                // recreating that queue on every tab switch races JD's own

                // video/RN surfaces and previously caused native crashes.

                if (surface.visibility != View.VISIBLE) surface.visibility = View.VISIBLE

                return@let

            }

            // JD video auto-hide must not destroy/recreate the compositor

            // Surface. Keep it attached with alpha=0 while the bar is hidden;

            // recreating it against the active player caused a flash followed

            // by a native process crash.

            val wantsOpticalSurface = wantsRootSurfaceOpticalPipeline() &&

                (visibleDuringTransition || targetAdapter?.retainsOpticalSurfaceWhenBarHidden == true)

            if (wantsOpticalSurface && !opticalSurfaceSurfacePipelineActive &&

                !opticalSurfaceSurfaceReady

            ) {

                // The compositor surface must stay VISIBLE across a full

                // layout pass to be created at all. Flipping it back to GONE

                // within the same preDraw in which the renderer requested

                // VISIBLE starves the pipeline in a deadlock: no

                // surfaceCreated -> pipeline never activates -> GONE again.

                // The unrendered TRANSLUCENT surface is fully transparent, so

                // this pending window costs nothing visually.

                if (surface.visibility != View.VISIBLE) surface.visibility = View.VISIBLE

            } else {

                surface.visibility = if (

                    wantsOpticalSurface && opticalSurfaceSurfacePipelineActive

                ) View.VISIBLE else View.GONE

            }

        }

        opticalSurfaceNativeBlurView?.let { blur ->

            blur.pivotX = blur.width * 0.5f

            blur.pivotY = blur.height.toFloat()

            blur.scaleX = scaleX

            blur.scaleY = scaleY

            blur.translationY = translationY

            blur.alpha = alpha

            if (!visibleDuringTransition) blur.visibility = View.INVISIBLE

        }

    }



    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {

        super.onSizeChanged(w, h, oldw, oldh)

        liveBackdropActive = false

        liveBackdropSignature = Long.MIN_VALUE

        outerRect.set(0f, 0f, w.toFloat(), h.toFloat())

        rebuildPanelPath()

        invalidateOutline()

        configureGlassEffects()

        if (targetAdapter?.usesDirectOpticalBackdropMode == true) {

            post {

                if (!isAttachedToWindow) return@post

                syncBackdropRendererMode()

            }

        }

        requestQqStaticBackdropRefresh(64L)

        logGeometryIfChanged("size")

    }



    override fun onConfigurationChanged(newConfig: Configuration) {

        super.onConfigurationChanged(newConfig)

        context.findActivity()?.let(GlassInstaller::ensureNavigationBarImmersion)

        // Keep geometry, gesture state and springs alive while the theme-owned

        // content hierarchy is being replaced, then reconnect the optical

        // source after the new hierarchy has settled.

        detectedContentDark = null

        lastThemeProbe = 0L

        beginThemeTransition(resizeSourceAfterSettle = false)

        scheduleOpticalPipelineRefresh(longArrayOf(0L, 48L, 160L, 360L), "config")

        scheduleNavigationSnapshotRefresh(longArrayOf(240L, 720L, 1_500L))

        requestQqStaticBackdropRefresh(220L)

        logGeometryIfChanged("config-now", force = true)

        listOf(16L, 80L, 180L, 360L, 720L).forEach { delay ->

            postDelayed({ logGeometryIfChanged("config+$delay", force = true) }, delay)

        }

        invalidate()

    }



    private fun scheduleOpticalPipelineRefresh(delays: LongArray, reason: String) {

        val generation = ++opticalRefreshGeneration

        for (delay in delays) {

            postDelayed({

                if (!isAttachedToWindow || generation != opticalRefreshGeneration) return@postDelayed



                // Do not clear liveBackdropActive or discard its display list.

                // Marking only the signature stale lets prepareLiveBackdropScene

                // swap in the new QQ scene atomically after BACKDROP_STABLE_FRAMES.

                liveBackdropSignature = Long.MIN_VALUE

                pendingBackdropSignature = Long.MIN_VALUE

                pendingBackdropStableFrames = 0

                configureGlassEffects()

                invalidate()

                logGeometryIfChanged("$reason+$delay", force = true)

            }, delay)

        }

    }



    internal fun scheduleNavigationSnapshotRefresh(delays: LongArray) {

        // JD rebinds and config/theme transitions in place without tearing

        // the row down, and its icon/label colours can settle later than the

        // first stable layout frame. The staged signature resets force

        // re-captures so the freshly themed native pixels reach the caches.

        if (context.packageName != QQ_PACKAGE && targetAdapter?.supportsScheduledNavigationSnapshotRefresh != true) return

        val generation = ++navigationRefreshGeneration

        for (delay in delays) {

            postDelayed({

                if (!isAttachedToWindow || generation != navigationRefreshGeneration) return@postDelayed

                navigationSnapshotSignature = Long.MIN_VALUE

                pendingNavigationSignature = Long.MIN_VALUE

                pendingNavigationStableFrames = 0

                invalidate()

            }, delay)

        }

    }



    private fun requestQqStaticBackdropRefresh(delayMs: Long = 0L) {

        if (context.packageName != QQ_PACKAGE) return

        val generation = ++qqStaticBackdropRefreshGeneration

        liveBackdropSignature = Long.MIN_VALUE

        pendingBackdropSignature = Long.MIN_VALUE

        pendingBackdropStableFrames = 0

        if (delayMs <= 0L) {

            qqStaticBackdropRefreshPending = false

            postInvalidateOnAnimation()

            return

        }

        postDelayed({

            if (!isAttachedToWindow || generation != qqStaticBackdropRefreshGeneration) return@postDelayed

            qqStaticBackdropRefreshPending = false

            liveBackdropSignature = Long.MIN_VALUE

            pendingBackdropSignature = Long.MIN_VALUE

            pendingBackdropStableFrames = 0

            postInvalidateOnAnimation()

        }, delayMs)

    }



    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {

        super.onLayout(changed, left, top, right, bottom)

        if (changed) logGeometryIfChanged("layout")

    }



    override fun onDraw(canvas: Canvas) {

        // The backdrop capture uses a software bitmap canvas. Never recursively

        // draw RuntimeShader/RenderEffect content into that capture.

        if (capturing) return

        // Nothing here is visible when the host sits off-screen, but layout, touch and
        // visibility churn still invalidate it. See isOnScreen.
        if (!isOnScreen()) return

        super.onDraw(canvas)

        if (opticalSurfaceSurfacePipelineActive) return

        sharedOpticalBlurPrepared = false

        val appWantsHostPanel = appNavigationState?.beforeHostDraw(
            this, navigationSource as? ViewGroup,
        ) ?: true

        val perfT0 = System.nanoTime()

        if (canvas.isHardwareAccelerated &&
            SystemClock.uptimeMillis() >= barAnimationPriorityUntil &&
            !compositorBlurBehind && appNavigationState?.maintainsCompositorSamplingWithoutSurface() != true &&

            // Pre-draw already prepared the live scene for this traversal. Its
            // child RenderNodes stay live as Android draws the content below us.
            // A second sweep advances stability counters twice per screen frame.
            !(captureListener != null && liveBackdropActive)

        ) {

            prepareLiveBackdropScene()

        }

        val perfT1 = System.nanoTime()


        // MyRecentPlayActivity has no navigation row. Its tiny host exists

        // only to keep probing/skinning the native mini player; painting the

        // normal bottom panel here would either cover the player or leave an

        // empty liquid capsule. The player itself is drawn by its own glass

        // drawable in the adapter-owned mini-player maintenance path.

        if (!appWantsHostPanel) {

            glassPerfRecord(perfT1 - perfT0, 0L)

            return

        }

        drawGlassPanel(canvas, isDarkGlass())

        val perfT2 = System.nanoTime()

        glassPerfRecord(perfT1 - perfT0, perfT2 - perfT1)

        // Weibo renders feed video through a compositor-backed texture. Those

        // texture frames do not necessarily invalidate the surrounding Android

        // View tree, so the retained RenderNode glass otherwise redraws only on

        // unrelated layout/touch events and appears delayed or low-FPS. Keep a

        // display-rate frame pump only while Weibo's glass is actually visible;

        // the retained GPU scene then samples the current video texture without

        // falling back to expensive whole-page software captures.

        if (targetAdapter?.liveBackdropFramePump == true &&

            liveBackdropActive && isOnScreen()

        ) {

            postInvalidateOnAnimation()

        }

        // Adapter-owned live scenes may require a continuous frame pump.

        if (appNavigationState?.needsContinuousHomeFrames(selectedIndex) == true &&

            liveBackdropActive && isOnScreen()

        ) {

            postInvalidateOnAnimation()

        }

        // Douyin feed video frames are compositor-backed and do not invalidate

        // this overlay, so keep a display-rate pump while the live scene is up

        // while the live scene is up.

        if (targetAdapter?.continuousFramePump == true &&

            liveBackdropActive && isOnScreen() &&

            // A proven-static backyard (letterbox band or static fill) means no video frame
            // can change these pixels. Stop the Choreographer watcher entirely; the strip
            // watcher invalidates this host when the backyard starts changing again.
            appNavigationState?.barStripStatic(this) != true

        ) {

            // Repaint only when the video actually produced a new frame.
            //
            // This used to call postInvalidateOnAnimation() on every display frame, so the
            // glass redrew 165 times a second to follow a video producing 30-60. The surplus
            // passes cost display and GPU power and painted identical pixels —measurable as
            // ~82 redraws/second on an idle feed.
            //
            // videoFrameWatcher runs per frame instead and does a timestamp compare. It is
            // cheap (no drawing, no allocation), and only an advanced source earns a repaint.
            // Bar animations still get every frame, because then the glass's own state moves.
            startVideoFrameWatch()

        } else {

            stopVideoFrameWatch()

        }

    }

    /**
     * Per-frame watcher that decides whether the glass owes a repaint.
     *
     * Checking whether the video advanced is nearly free; drawing is not. So the display
     * rate is spent on the check and the actual repaint happens at the video's own rate.
     *
     * A 60 Hz paced Runnable was tried here to cut the wakeups further, on the reasoning
     * that the video cannot exceed 60 fps. Measured, it was worse: redraws went 55/s -> 83/s
     * and janky frames 0% -> 14.4%, because a timer that is not vsync-aligned competes with
     * the frame pipeline instead of riding it. Reverted.
     */
    private val videoFrameWatcher = object : android.view.Choreographer.FrameCallback {

        override fun doFrame(frameTimeNanos: Long) {

            if (!videoWatchActive) return

            if (videoWantsRepaint()) invalidate()

            android.view.Choreographer.getInstance().postFrameCallback(this)

        }

    }

    private var videoWatchActive = false

    /** Video timestamp of the last repaint we authorised; see [videoWantsRepaint]. */
    private var lastPumpedVideoStamp = Long.MIN_VALUE

    private fun startVideoFrameWatch() {

        if (videoWatchActive) return

        videoWatchActive = true

        android.view.Choreographer.getInstance().postFrameCallback(videoFrameWatcher)

    }

    private fun stopVideoFrameWatch() {

        if (!videoWatchActive) return

        videoWatchActive = false

    }

    /**
     * True when the glass should repaint.
     *
     * Bar animation wins outright: the glass's own geometry is moving, so every frame is
     * real. Otherwise the video's timestamp decides —a changed value owes a repaint, an
     * unchanged one means the current pixels are still correct. An adapter that cannot
     * report a timestamp (null) falls back to the slow heartbeat rather than to per-frame
     * pumping, because that is the behaviour this replaces.
     */
    private fun videoWantsRepaint(): Boolean {

        // A/B TEST: repaint on every display frame, i.e. the pre-optimisation behaviour.
        // Used to check whether the video-rate pump changed how the refraction looks.
        // Set back to false when the comparison is done.
        if (FORCE_FULL_RATE_PUMP) return true

        if (isBarVisualStateAnimating()) return true

        // The bar's own backyard can be static even while the player keeps producing
        // frames: letterboxed videos, blurred-fill pages, a paused clip, or a bar that sits
        // outside the video view. Skip the video-driven repaint then; the strip watcher
        // invalidates the host as soon as the backyard starts changing again.
        if (appNavigationState?.videoSourceIsStatic(this) == true) return false

        val stamp = appNavigationState?.videoFrameStamp(this)

        if (stamp == null) {

            val now = SystemClock.uptimeMillis()

            if (now - lastStaticPumpAt < staticPumpHeartbeatMs) return false

            lastStaticPumpAt = now

            return true

        }

        if (stamp == lastPumpedVideoStamp) return false

        lastPumpedVideoStamp = stamp

        return true

    }



    /** One geometry rule for every app: the bar radius follows its real height. */
    internal fun outerCornerRadiusPx(): Float =
        min(width.toFloat(), height.toFloat()) * (config.cornerRadiusPercent / 100f)

    /**
     * The blur the bars apply: the configured radius, in pixels, scaled by the adapter when
     * it asks for a different one. Single source for the capture radius, its padding and the
     * effects, so a host and anything mirroring it cannot drift apart.
     */
    internal fun glassBlurRadiusPx(): Float =
        config.blurRadius * density * (targetAdapter?.secondaryBlurScale ?: 1f)

    /**
     * The fill the bars paint: the adapter's own tint where it has one (Douyin's warm cream),
     * otherwise the themed surface colour, with the configured alpha scale already baked in.
     * The top copy's dropdown reads this, so the panel can never drift from its bar.
     */
    internal fun resolvedGlassFillColor(dark: Boolean): Int =
        targetAdapter?.glassBarTint(dark)?.let { pair ->
            val tint = pair.first
            val strength = pair.second
            Color.argb(
                (Color.alpha(surfaceContainerColor(dark)) * strength.coerceIn(0f, 1f))
                    .toInt().coerceIn(0, 255),
                Color.red(tint),
                Color.green(tint),
                Color.blue(tint),
            )
        } ?: surfaceContainerColor(dark)



    /**

     * 1.0 = chrome fully visible, 0.0 = fully hidden on adapter-managed pages;

     * returns 1.0 everywhere else. After the adapter-owned idle period without

     * a touch, the bar chrome (glass surface, slider, ambient shadow, bloom

     * stroke) fades out; the text layer

     * drawn in dispatchDraw is untouched. First draw initializes the timestamp

     * so the chrome starts visible.

     */




    private fun drawGlassPanel(canvas: Canvas, dark: Boolean) {

        val appVideoBackdrop = appNavigationState?.videoBackdrop()
        if (appVideoBackdrop != null) {

            val now = SystemClock.uptimeMillis()

            if (now - lastAppVideoDiagLog >= 1000L) {

                lastAppVideoDiagLog = now


            }

        }

        val appChromeFade = appNavigationState?.hostChromeAlpha(this, selectedIndex) ?: 1f

        // NetEase home: the whole bar chrome (backdrop refraction, fill,

        // interactive highlight, bloom stroke, ambient shadow) fades together

        // with the idle timer. The text layer in dispatchDraw stays untouched.

        val appChromeLayer = if (appChromeFade < 1f) {

            canvas.saveLayerAlpha(

                0f,

                0f,

                width.toFloat(),

                height.toFloat(),

                (appChromeFade * 255).roundToInt().coerceIn(0, 255),

            )

        } else {

            -1

        }

        canvas.save()

        canvas.scale(outerPanelScale, outerPanelScale, width / 2f, height / 2f)

        drawPanelAmbientShadow(canvas, dark, appChromeFade)
        appNavigationState?.drawAdditionalAmbientShadow(canvas, dark, appChromeFade)

        canvas.save()

        canvas.clipPath(clipPath)

        // Extra clipRect ensures the backdrop RenderNode (which extends

        // beyond the view bounds for blur sampling) is hard-clipped on

        // hardware-accelerated canvases where clipPath alone is unreliable

        // for RenderNodes carrying RenderEffects.

        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())

        // Always render the glass effect when a backdrop is available.

        // drawOuterGlass already handles hybrid mode internally with

        // opticalSurfaceOuterGlassEffect and reduced alpha.

        if (hasUsableBackdrop()) drawOuterGlass(canvas)




        // Douyin's cream glass: an adapter-owned warm tint replaces the neutral
        // white/black wash so the bar reads like the reference mock-up. The
        // configured light/dark alpha still controls how much shows through.
        val configuredFillColor = resolvedGlassFillColor(dark)

        val fillColor = configuredFillColor

        val effectiveFillAlpha = Color.alpha(fillColor)

        fillPaint.color = Color.argb(

            effectiveFillAlpha,

            Color.red(fillColor),

            Color.green(fillColor),

            Color.blue(fillColor),

        )

        canvas.drawPath(clipPath, fillPaint)

        drawInteractiveHighlight(canvas)

            if (config.outlineEnabled) {

                drawBloomStrokePath(

                    canvas,

                    clipPath,

                    ((if (usesOpticalSurfaceHybridBackdrop()) {

                        0.10f

                    } else if (dark) {

                        0.58f

                    } else {

                        0.75f

                    }) * if (dark) config.darkBarHighlightStrength else 1f)

                        .coerceAtMost(1f),

                    -45f,

                    outerBloomShader,

                )

            }

        canvas.restore()

        canvas.restore()

        if (appChromeLayer >= 0) canvas.restoreToCount(appChromeLayer)

    }



    /**

     * Offset-free ambient shadow ringing the panel on all sides. Drawn before

     * the panel fill with the panel path clipped out, so only the outer half

     * of the blur shows and the top edge stays separable from content without

     * dimming the glass fill. Matches the manager UI bar's dropShadow params.

     */

    private fun drawPanelAmbientShadow(canvas: Canvas, dark: Boolean, alphaScale: Float = 1f) {

        if (outerRect.isEmpty || !canvas.isHardwareAccelerated) return

        panelShadowPaint.color = Color.TRANSPARENT

        // WeChat renders a much tighter ambient ring; every other app keeps the

        // standard radius.

        val blurDp = targetAdapter?.panelShadowBlurDp ?: PANEL_SHADOW_BLUR_DP

        val pressBoost = if (topBar) 1f else 1f + 0.8f * pressProgress.coerceIn(0f, 1f)

        panelShadowPaint.setShadowLayer(

            blurDp * density,

            0f,

            0f,

            Color.argb(

                ((if (dark) PANEL_SHADOW_ALPHA else PANEL_LIGHT_SHADOW_ALPHA) *

                    255f * (alphaScale * pressBoost).coerceAtLeast(0f)).toInt().coerceIn(0, 255),

                0,

                0,

                0,

            ),

        )

        canvas.save()

        canvas.clipOutPath(clipPath)

        // The ambient ring deliberately reaches into the gaps of a panel made of several
        // shapes (pill plus detached action circles). Each shape's shadow crosses the 12 dp
        // gap and the two overlap there, which is what ties the pieces together: with the
        // ring clipped out of those gaps the backdrop shows through them at full strength,
        // and a bright backdrop then reads as a light strip bridging the pieces. Keep the
        // shadow inside the cluster.
        canvas.drawPath(clipPath, panelShadowPaint)

        canvas.restore()

    }



    /**

     * Ambient shadow ringing the mini-player capsule, drawn on the host surface

     * (whose bounds are larger than the capsule) so the outer halo isn't clipped

     * away the way it is inside the player's own bounded canvas. Mirrors

     * drawPanelAmbientShadow so the play bar reads clearly on light feeds.

     */




    private fun glassPerfRecord(prepareNs: Long, panelNs: Long) {

        // The per-second GlassPerf line is developer telemetry, not a failure report. It used to
        // run in every build for every host, which is two lines a second on Douyin (bottom bar
        // plus top copy) whether or not anyone is reading them. Gate the *accumulation*, not just
        // the print, so a disabled build does no bookkeeping at all.
        if (!GLASS_HOST_DIAG_ENABLED) return

        perfFrames++

        perfPrepareNs += prepareNs

        if (prepareNs > perfPrepareMaxNs) perfPrepareMaxNs = prepareNs

        perfPanelNs += panelNs

        if (panelNs > perfPanelMaxNs) perfPanelMaxNs = panelNs

        glassPerfLogTick()

    }



    private fun glassPerfLogTick() {

        // The counters stay for a future profiling build; the once-a-second log line does not
        // belong in a release. Two hosts used to print ~200 characters every second each.
        if (!GLASS_HOST_DIAG_ENABLED) return

        val now = SystemClock.uptimeMillis()

        if (now - perfLastLogUptime < 1000L) return

        perfLastLogUptime = now

        val frames = perfFrames

        if (frames > 0) {

            android.util.Log.i(

                "GlassPerf",

                "pkg=${context.packageName} fps=$frames " +

                    "prep=${perfPrepareNs / frames / 1000}us max=${perfPrepareMaxNs / 1000}us " +

                    "panel=${perfPanelNs / frames / 1000}us max=${perfPanelMaxNs / 1000}us " +

                    "disp=${perfDispatchNs / frames / 1000}us max=${perfDispatchMaxNs / 1000}us " +

                    "sweeps=$perfSceneSweeps sibs=$perfSceneSiblingVisits " +

                    "sceneRec=$perfSceneReRecords eff=$perfEffectRebuilds nodeRec=$perfNodeRecords " +

                    "surfR=$perfSurfaceRenders live=$liveBackdropActive",

            )

        }

        perfFrames = 0

        perfPrepareNs = 0L

        perfPrepareMaxNs = 0L

        perfPanelNs = 0L

        perfPanelMaxNs = 0L

        perfDispatchNs = 0L

        perfDispatchMaxNs = 0L

        perfSceneSweeps = 0

        perfSceneSiblingVisits = 0

        perfSceneReRecords = 0

        perfEffectRebuilds = 0

        perfNodeRecords = 0

        perfSurfaceRenders = 0

    }



    private fun glassDispatchPerfRecord(dispatchNs: Long) {
        if (!GLASS_HOST_DIAG_ENABLED) return

        perfDispatchNs += dispatchNs

        if (dispatchNs > perfDispatchMaxNs) perfDispatchMaxNs = dispatchNs

    }



    override fun draw(canvas: Canvas) {
        // Exclude the glass from software background capture without mutating
        // alpha: property changes invalidate the retained screen display list.
        if (capturing) return
        super.draw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {

        if (capturing) return

        // The selection resolution and navigation painting below are invisible off-screen.
        // See isOnScreen.
        if (!isOnScreen()) return

        // A host that draws the bloom must also be fed the tilt that moves its light; a
        // missed attach/window-visibility transition left the top copy's outline fixed.
        ensureBloomTiltRegistration()

        (if (nativeSelectionReliable) selectionForFrame() else null)?.let { resolved ->

            if (appNavigationState?.acceptNativeNavigationSelection(
                    resolved, SystemClock.uptimeMillis(),
                ) == false
            ) return@let

            val effectiveResolved = if (isNavigationEntryDisabled(resolved)) {

                nearestSelectableIndex(resolved)

            } else {

                resolved

            }

            if (!selectionInitialized) {

                selectionInitialized = true

                selectedIndex = effectiveResolved

                dragTarget = visualPositionForNavigationIndex(effectiveResolved)

                positionSpring.snapTo(dragTarget)

            } else if (effectiveResolved != selectedIndex) {

                selectedIndex = effectiveResolved

                animateToIndex(
                    effectiveResolved,
                    includePress = !touching,
                    prioritizePageTransition = false,
                )

                requestQqStaticBackdropRefresh(120L)

            }

        }

        if (opticalSurfaceSurfacePipelineActive) {

            val holdForOpticalFrame = appNavigationState?.holdDirectBackdropUntilOpticalFrame(
                liveBackdropActive, liveBackdropNode.hasDisplayList(),
            ) == true

            if (holdForOpticalFrame) {

                // Keep the previous direct GPU scene visible until the first

                // compositor-backed frame has actually been posted.

            } else {

            // JD queues animation state to the same worker as PixelCopy.

            // Surface buffer waits must not block the application's UI thread.

            val animating = isBarVisualStateAnimating()
            when (appNavigationState?.shouldQueueOpticalRenderFromUi(animating)) {
                true -> queueOpticalSurfaceRender()
                false -> Unit
                null -> renderOpticalSurfaceSurface()
            }

            if (targetAdapter?.needsContinuousOpticalSurfaceFrames(selectedIndex) == true) {
                postInvalidateOnAnimation()
            }

            return

            }

        }

        val dispatchPerfT0 = System.nanoTime()

        super.dispatchDraw(canvas)

        // The active optical surface uses a compositor-blurred base plus an

        // edge-only snapshot lens. Paint the lens first and the extracted

        // navigation artwork once on top; otherwise the moving translucent

        // pill washes/repeats the icon that was already painted underneath.

        // NetEase sub-pages without a native tab bar draw no navigation row;

        // the host there only drives the mini-player glass probe.

        if (appNavigationState?.drawsNavigationContent() != false) {

            // drawGlassPanel grows the panel by outerPanelScale while pressed. This artwork is
            // painted outside that transform, so at full press the detached publish circle
            // travelled ~24 px away from its own glyph (and the panel from the labels).
            // Mirror the panel's transform so the row, the pill and the circle stay locked.
            val panelScaleLayer = canvas.save()

            canvas.scale(outerPanelScale, outerPanelScale, width / 2f, height / 2f)

            val burnInLayer = saveBurnInOffset(canvas)

            if (usesOpticalSurfaceHybridBackdrop() ||
                targetAdapter?.foregroundNavigationOverSelection == true) {

                // Douyin's selected icon must remain above the refracting lens.
                // Swiping the feed can otherwise cover it with the slider layer.
                drawSelectionWithFade(canvas, isDarkGlass())

                val customForeground = appNavigationState?.drawForeground(
                    canvas,
                    adapterNavigationFrame(1f),
                    foregroundNavigationClipPath(),
                ) { drawNavigationSource(canvas) } == true
                if (!customForeground) {
                    drawNavigationSource(canvas)
                }

            } else {

                drawNavigationSource(canvas)

                drawSelectionWithFade(canvas, isDarkGlass())

            }

            // The resting selection ink, over the glass. While the finger is down the same call is a
            // no-op here and the ink comes from the lens scene instead (see drawSelectionInk).
            appNavigationState?.drawSelectionInk(canvas, adapterNavigationFrame(1f), inLensScene = false)

            restoreBurnInOffset(canvas, burnInLayer)

            canvas.restoreToCount(panelScaleLayer)

        }

        if (context.packageName == QQ_PACKAGE) drawQqBadgeOverlay(canvas)

        glassDispatchPerfRecord(System.nanoTime() - dispatchPerfT0)

    }



    fun invalidateProjectionCache() {

        navigationSnapshotSignature = Long.MIN_VALUE

        liveBackdropSignature = Long.MIN_VALUE

        pendingNavigationSignature = Long.MIN_VALUE

        postInvalidateOnAnimation()

    }






    override fun dispatchTouchEvent(event: MotionEvent): Boolean {

        if (slotCount <= 0 || width <= 0) return super.dispatchTouchEvent(event)

        if (appNavigationState?.allowsHostTouch() == false) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            appNavigationState?.onHostTouchDown(this, selectedIndex)
        }

        val slotWidth = tabWidthPx()

        val visualSlots = visualSlotCount()

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                if (appNavigationState?.beginOverlayTouch(this, event) == true) {

                    return true

                }

                appNavigationState?.onBarGesture(0, this)

                // User interacted with the bar on Weibo message tab: cancel

                // auto-hide so the bar stays visible while they use it

                appNavigationState?.onHostTouchDown(this, selectedIndex)

                // Same contract for XHS's message tab: touching the bar during

                // its back-peek window keeps it on screen.

                if (targetAdapter?.resetScrollStopHideOnOtherTabs() == true) {

                    adapterMsgPeekUntil = 0L

                    adapterMsgHideTime = 0L

                }

                indicatorGestureActive = sliderEnabled && selectionRect.contains(event.x, event.y)
                touching = indicatorGestureActive

                lastTouchX = event.x

                gestureStartX = event.x

                gestureStartY = event.y

                touchX = event.x

                touchY = event.y

                dragTarget = indicatorPosition

                pendingTapMoved = false

                pendingActionBand = actionBandAt(event.x)

                pendingInlineAction = inlineActionBandAt(event.x)

                pendingTapIndex = if (indicatorGestureActive || pendingActionBand >= 0 ||
                    pendingInlineAction
                ) {

                    -1

                } else {

                    navigationIndexForVisualIndex(

                        ((event.x - navigationSlotBandLeftPx()) / slotWidth).toInt()

                            .coerceIn(0, visualSlots - 1),

                    ).takeUnless(::isNavigationEntryDisabled) ?: -1

                }

                if (indicatorGestureActive) {

                    releaseGeneration++

                    press()

                }

            }



            MotionEvent.ACTION_MOVE -> {

                if (appNavigationState?.hasActiveOverlayTouch() == true) {
                    appNavigationState?.forwardOverlayTouch(this, event)

                    return true

                }

                appNavigationState?.onBarGesture(1, this)

                if (!indicatorGestureActive) {

                    if (abs(event.x - gestureStartX) > touchSlop ||

                        abs(event.y - gestureStartY) > touchSlop

                    ) {

                        pendingTapMoved = true

                    }

                } else {

                    // One update per ~120 Hz. Applying every motion event repainted the glass at
                    // the panel's full rate, and each repaint sends the live video through the
                    // glass effect chain; the pill does not need 165 updates a second to stay on
                    // the finger, and the video does need the frame budget.
                    val dragNow = SystemClock.uptimeMillis()

                    if (dragNow - lastDragUpdateAt < DRAG_UPDATE_MIN_INTERVAL_MS) return true

                    lastDragUpdateAt = dragNow

                    touchX = event.x

                    touchY = event.y

                    // Track the gesture everywhere, including the side margins outside the
                    // host view. Ignoring motion whose endpoints fell outside 0..width made
                    // the capsule stop following the finger near the edges. A single step
                    // is clamped so a stale reading cannot jump the indicator.
                    val delta = (event.x - lastTouchX)
                        .coerceIn(-slotWidth, slotWidth)

                    val previousTarget = dragTarget

                    dragTarget = (dragTarget + delta / slotWidth)

                        .coerceIn(0f, (visualSlots - 1).toFloat())

                    // Follow the finger directly. A critically damped spring always trails
                    // a moving target, which is exactly what made the capsule feel
                    // disconnected while dragging; the spring is still used for the settle
                    // animation after release.
                    positionSpring.snapTo(dragTarget)

                    // The position no longer comes from the spring, so feed the tilt spring
                    // from the gesture itself instead of a spring velocity.
                    val stepVelocity = (dragTarget - previousTarget) * 60f

                    velocitySpring.animateTo(
                        stepVelocity / (visualSlotCount() - 1).coerceAtLeast(1),
                    )

                    panelOffsetSpring.snapTo(panelDragOffset + delta)

                    lastTouchX = event.x

                }

            }



            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {

                // Tapping Home while already on Home flips Douyin's single/double-column
                // feed. The switch is an arrow inside the native bar, which this host covers,
                // so the tap is forwarded there instead.
                //
                // There is no ambiguity to resolve: Douyin only shows that arrow while Home
                // is the selected tab, so the gesture has no other meaning. Tapping Home from
                // any other tab still navigates, because selectedIndex is not 0 then.

                if (appNavigationState?.hasActiveOverlayTouch() == true) {
                    appNavigationState?.forwardOverlayTouch(this, event)
                    appNavigationState?.clearOverlayTouch()

                    return true

                }

                appNavigationState?.onBarGesture(2, this)

                if (indicatorGestureActive) {

                    touching = false

                    val generation = ++releaseGeneration

                    val rawTarget = navigationIndexForVisualIndex(

                        dragTarget.roundToInt().coerceIn(0, visualSlots - 1),

                    )

                    val actionTarget = event.actionMasked == MotionEvent.ACTION_UP &&

                        rawTarget in nonSelectableIndices && !isNavigationEntryDisabled(rawTarget)

                    val target = if (actionTarget) {

                        selectedIndex

                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {

                        nearestSelectableIndex(rawTarget)

                    } else {

                        selectedIndex

                    }

                    selectedIndex = target

                    dragTarget = visualPositionForNavigationIndex(target)

                    positionSpring.animateTo(dragTarget)

                    velocitySpring.animateTo(0f)

                    panelOffsetSpring.animateTo(0f)

                    if (event.actionMasked == MotionEvent.ACTION_UP) {

                        performNavigationSelection(if (actionTarget) rawTarget else target)

                    }

                    releaseWhenSettled(generation)

                } else if (event.actionMasked == MotionEvent.ACTION_UP && !pendingTapMoved) {

                    if (pendingActionBand >= 0 && actionBandAt(event.x) == pendingActionBand) {

                        // A detached action circle: the app's own key handles it.
                        appNavigationState?.performActionTap(
                            this,
                            navigationSource,
                            pendingActionBand == 0,
                        )

                    } else if (pendingInlineAction && inlineActionBandAt(event.x)) {

                        // The in-pill action band: the app's own key handles it, and the
                        // shared slot path is skipped so the selection never moves to it.
                        appNavigationState?.performInlineActionTap(this, navigationSource)

                    } else {

                    val upIndex = navigationIndexForVisualIndex(

                        ((event.x - navigationSlotBandLeftPx()) / slotWidth).toInt()

                            .coerceIn(0, visualSlots - 1),

                    )

                    val tapped = pendingTapIndex.takeIf { it == upIndex } ?: -1

                    if (tapped in nonSelectableIndices && !isNavigationEntryDisabled(tapped)) {

                        performNavigationSelection(tapped)

                    } else if (tapped >= 0) {

                        // The source QQTabLayout is transparent and scaled into this

                        // host. Re-dispatching raw MotionEvents to that detached hit

                        // geometry occasionally loses a quick tap. Invoke the exact

                        // original TabView click instead so QQ's own controller still

                        // performs the page change, while this host owns the gesture.

                        selectedIndex = tapped

                        if (sliderEnabled) animateToIndex(tapped, includePress = true)

                        performNavigationSelection(tapped)

                    }

                    }

                }

                indicatorGestureActive = false

                pendingTapIndex = -1

                pendingActionBand = -1

                pendingInlineAction = false

                pendingTapMoved = false

            }

        }

        // This overlay is the sole physical hit target. Navigation is routed via

        // the original tab views above, never by synthesising a second gesture.

        return true

    }



    /**

     * Starts forwarding a mini player touch. The player capsule sits above the

     * navigation capsule inside this extended host; touches there must reach

     * the native (hidden) player controls instead of becoming nav taps. The

     * event is re-dispatched to the original child view so its own click

     * listeners (play/pause, playlist, open player page) still run.

     */




    private fun press() {
        pressSpring.animateTo(1f)
        val pressedScale = if (topBar) PRESSED_SCALE else BOTTOM_PRESSED_SCALE
        scaleXSpring.animateTo(pressedScale)
        scaleYSpring.animateTo(pressedScale)
        interactiveSpring.animateTo(1f)
    }



    private fun release() {

        pressSpring.animateTo(0f)

        scaleXSpring.animateTo(1f)

        scaleYSpring.animateTo(1f)

        interactiveSpring.animateTo(0f)

        velocitySpring.animateTo(0f)

    }



    private fun releaseWhenSettled(generation: Int) {

        if (generation != releaseGeneration || touching) return

        if (abs(indicatorPosition - dragTarget) <= visualSlotCount().coerceAtLeast(1) * 0.025f) {

            release()

        } else {

            postOnAnimationDelayed({ releaseWhenSettled(generation) }, 16L)

        }

    }



    private fun animateToIndex(
        index: Int,
        includePress: Boolean,
        prioritizePageTransition: Boolean = true,
    ) {

        if (prioritizePageTransition) {
            barAnimationPriorityUntil = SystemClock.uptimeMillis() + 240L
        }

        val target = visualPositionForNavigationIndex(index)

        dragTarget = target

        if (includePress) press()

        positionSpring.animateTo(target)

        velocitySpring.animateTo(0f)

        if (includePress) releaseWhenSettled(++releaseGeneration)

    }



    private fun updateOuterScale() {

        val grow = if (width > 0) 16f * density / width else 0f

        outerPanelScale = 1f + grow * pressProgress

        invalidate()

    }

    /**
     * The settings bar gives each tab its own `graphicsLayer` scale animated by Compose. Mirror
     * that here with one spring per visual slot: the outgoing tab eases back to 1 while the tab
     * under the capsule eases up to 1.2. No discrete slot switch is visible as a one-frame jump.
     */
    private fun refreshPressSlotScales(): List<Float> {
        val count = visualSlotCount().coerceAtLeast(1)
        if (pressSlotScales.size != count) {
            pressSlotSprings.forEach(SpringFloat::cancel)
            pressSlotScales = FloatArray(count) { 1f }
            pressSlotTargets = FloatArray(count) { 1f }
            pressSlotScalesList = pressSlotScales.asList()
            pressSlotSprings.clear()
            repeat(count) { index ->
                pressSlotSprings += SpringFloat(1f, 900f, 0.9f, 0.001f) { value, _ ->
                    // A settled spring re-reports its final value; repainting on that
                    // identical value was what kept an idle bar at display rate.
                    if (index < pressSlotScales.size && value != pressSlotScales[index]) {
                        pressSlotScales[index] = value
                        invalidate()
                    }
                }
            }
        }
        val pressed = !topBar && (touching || pressProgress > 0.001f)
        for (index in 0 until count) {
            // Cover both icons when the wide capsule straddles them. A single nearest-slot
            // target made one of the two shrink even though it was still under the capsule.
            val covered = pressed &&
                kotlin.math.abs(index - indicatorPosition) <= 0.95f
            val target = if (covered) 1.2f else 1f
            // This method runs once per AdapterNavigationFrame, and an idle draw builds three
            // or four. Only a target change is allowed to wake a spring.
            if (pressSlotTargets[index] != target) {
                pressSlotTargets[index] = target
                pressSlotSprings[index].animateTo(target)
            }
        }
        return pressSlotScalesList
    }



    private fun updatePanelTranslation() {

        if (width <= 0) {

            translationX = 0f

            return

        }

        val fraction = (panelDragOffset / width).coerceIn(-1f, 1f)

        val eased = easeOut.getInterpolation(abs(fraction))

        translationX = 4f * density * if (fraction < 0f) -eased else eased

    }



    private data class SourceTransform(val scale: Float, val dx: Float, val dy: Float)



    private fun sourceTransform(source: View): SourceTransform {

        return sourceTransform(source.width, source.height)

    }



    private fun sourceTransform(sourceWidthPx: Int, sourceHeightPx: Int): SourceTransform {

        val sourceWidth = sourceWidthPx.coerceAtLeast(1).toFloat()

        val sourceHeight = sourceHeightPx.coerceAtLeast(1).toFloat()

        val horizontalPadding = 4f * density

        val contentWidth = (width - horizontalPadding * 2f).coerceAtLeast(1f)

        val scale = min(contentWidth / sourceWidth, height / sourceHeight).coerceAtLeast(0.0001f)

        return SourceTransform(

            scale = scale,

            dx = horizontalPadding + (contentWidth - sourceWidth * scale) / 2f,

            dy = (height - sourceHeight * scale) / 2f,

        )

    }



    /** Maps one native slot into one compact visual slot without squeezing its siblings. */

    private fun sourceTransformForSlot(

        sourceWidthPx: Int,

        sourceHeightPx: Int,

        nativeIndex: Int,

        visualIndex: Int,

    ): SourceTransform {

        val sourceWidth = sourceWidthPx.coerceAtLeast(1).toFloat()

        val sourceHeight = sourceHeightPx.coerceAtLeast(1).toFloat()

        val nativeSlotWidth = sourceWidth / slotCount.coerceAtLeast(1)

        val visualSlotWidth = tabWidthPx()

        val scale = min(visualSlotWidth / nativeSlotWidth, height / sourceHeight)

            .coerceAtLeast(0.0001f)

        val sourceCenterX = (nativeIndex + 0.5f) * nativeSlotWidth

        val targetCenterX = navigationSlotBandLeftPx() + (visualIndex + 0.5f) * visualSlotWidth

        return SourceTransform(

            scale = scale,

            dx = targetCenterX - sourceCenterX * scale,

            dy = (height - sourceHeight * scale) / 2f,

        )

    }

    /** A small generic coordinate bridge for adapter-owned tab adornments. */
    internal fun adapterTabTransform(index: Int): FloatArray? {
        val source = navigationSource ?: return null
        if (isNavigationEntryDisabled(index)) return null
        val visualIndex = enabledNavigationIndices().indexOf(index)
        if (visualIndex < 0) return null
        val transform = sourceTransformForSlot(source.width, source.height, index, visualIndex)
        return floatArrayOf(transform.scale, transform.dx, transform.dy, sourceTransform(source).scale, density)
    }

    internal val adapterNavigationSource: ViewGroup? get() = navigationSource

    internal fun adapterScheduleBackdropRefresh(delayMs: Long) {
        postDelayed({
            if (isAttachedToWindow && appNavigationState?.deferThemeDetection(SystemClock.uptimeMillis()) != true) {
                liveBackdropSignature = Long.MIN_VALUE
                navigationSnapshotSignature = Long.MIN_VALUE
                pendingNavigationSignature = Long.MIN_VALUE
                pendingNavigationStableFrames = 0
                postInvalidateOnAnimation()
            }
        }, delayMs)
    }

    internal fun adapterOnScrollStopHidePageEntered(delayMs: Long) {
        msgScrolledToStop = false
        removeCallbacks(msgScrollHideRunnable)
        ensureMsgScrollStopHideListener()
        postDelayed(msgScrollHideRunnable, delayMs)
        if (visibility != View.VISIBLE) visibility = View.VISIBLE
        alpha = 1f
    }

    internal fun adapterOnScrollStopHidePageExited() {
        postInvalidateOnAnimation()
    }

    internal fun adapterCancelScrollStopHide() {
        removeCallbacks(msgScrollHideRunnable)
        msgScrolledToStop = false
    }



    /**
     * Gap between an adapter's detached action button and the main pill, in px.
     *
     * "鍘婚櫎鍙戝竷鎸夐挳" collapses this reserve for the bottom bar. With no gap there is no detached
     * button either ([navigationActionButtonPx] returns 0), so [rebuildPanelPath] falls back to
     * the single capsule spanning the host, [tabWidthPx] hands the four slots the whole width and
     * [actionBandAt] stops reporting a trailing action —which is what makes the capsule fill the
     * bottom instead of ending short of the publish circle. The top copy is deliberately excluded:
     * its two reserves are its own tab-menu keys, not a publish button.
     */
    private fun navigationActionGapPx(): Float =
        if (topBar) {
            // 顶栏两侧按钮: a hidden trailing key (Douyin's search) keeps no reserve, so
            // [rebuildPanelPath] draws a capsule ending at the host edge and the content-hugging
            // width shrinks by exactly that circle plus its gap.
            if (config.topBarHideTrailingAction) {
                0f
            } else {
                (actionReserveDp?.second ?: (targetAdapter?.navigationActionReserveGapDp ?: 0f)) *
                    density * topBarScale()
            }
        } else if (!config.showPostButton) {
            0f
        } else {
            (actionReserveDp?.second ?: (targetAdapter?.navigationActionReserveGapDp ?: 0f)) *
                density * topBarScale()
        }

    /** Left-hand companion of [navigationActionGapPx]; zero keeps a plain capsule edge. */
    private fun navigationActionLeftGapPx(): Float =
        if (topBar && config.topBarHideLeadingAction) {
            // 顶栏两侧按钮: the leading key is gone, so the capsule starts at the host edge and
            // the remaining cluster is centred by the host's own TOP|CENTER_HORIZONTAL gravity.
            0f
        } else {
            (actionReserveDp?.first ?: 0f) * density * topBarScale()
        }

    /**
     * 椤舵爮鍙傛暟: the top cluster's own multiplier.
     *
     * Everything that makes up the copy's size goes through here and nowhere else —the height it
     * is laid out at (see `updateHostLayoutForConfig`), the two action gaps that frame it, the
     * in-pill action band and the label size —so the cluster scales as one piece instead of
     * deforming. The bottom bar never reads the setting, and neither does any shared path that
     * does not check [topBar] first.
     */
    private fun topBarScale(): Float = if (topBar) config.topBarScale else 1f

    /**
     * Diameter factor for this host's detached action circles. Only the top copy can ask for
     * smaller ones; every other bar keeps circles as tall as the bar itself.
     */
    private fun actionButtonScale(): Float =
        if (topBar) (targetAdapter?.topBarActionButtonFraction() ?: 1f).coerceIn(0.5f, 1f) else 1f

    /**
     * Whether this host may remove the app's own chrome for its row. The top copy has its own
     * switch: turned off, Douyin's title row is the app's business again —nothing hides it and
     * nothing clears its click flags, so the native bar comes back exactly as it was.
     */
    private fun suppressesNativeChrome(): Boolean = !(topBar && !config.topBarLiquidGlass)

    /** Diameter of the detached action button; a circle as tall as the bar by default. */
    private fun navigationActionButtonPx(): Float =
        if (navigationActionGapPx() > 0f) height * actionButtonScale() else 0f

    /** Diameter of the detached left action button. */
    private fun navigationActionLeftButtonPx(): Float =
        if (navigationActionLeftGapPx() > 0f) height * actionButtonScale() else 0f

    /**
     * Width held back at the right edge for an adapter's detached action button
     * (Douyin's circular publish button). The button is as tall as the bar, so the
     * reserve is height-derived and matches the renderer's geometry exactly. Zero for
     * every adapter that keeps its actions inside the pill.
     */
    private fun navigationActionReservePx(): Float {
        val gap = navigationActionGapPx()
        if (gap <= 0f) return 0f
        return (navigationActionButtonPx() + gap).coerceAtMost(width * 0.5f)
    }

    /** Mirror of [navigationActionReservePx] for a leading action button. */
    private fun navigationActionLeftReservePx(): Float {
        val gap = navigationActionLeftGapPx()
        if (gap <= 0f) return 0f
        return (navigationActionLeftButtonPx() + gap).coerceAtMost(width * 0.5f)
    }

    /** Left edge of the slot band: the shared 4 dp inset, after a leading action button. */
    private fun navigationSlotBandLeftPx(): Float =
        4f * density + navigationActionLeftReservePx() + inlineActionWidthPx()

    /**
     * Width of the action band the top copy keeps *inside* its capsule, ahead of the tab
     * slots (Douyin's tab-menu key). It is not a navigation slot: it never takes the
     * selection capsule and it is not part of [visualSlotCount], which is what keeps the
     * tab geometry and the capsule where they belong.
     */
    private fun inlineActionWidthPx(): Float =
        if (topBar) (targetAdapter?.topBarInlineActionWidthDp() ?: 0f) * density * topBarScale() else 0f

    /** Whether [x] falls in the in-pill action band. */
    private fun inlineActionBandAt(x: Float): Boolean {
        val width = inlineActionWidthPx()
        if (width <= 0f) return false
        val left = 4f * density + navigationActionLeftReservePx()
        return x >= left && x < left + width
    }

    /**
     * Which detached action button sits under [x]: 0 leading, 1 trailing, -1 none.
     *
     * Both reserves are zero for every bar that keeps its actions inside the pill, so
     * the shared slot hit-testing is untouched there; the top copy, which reserves a
     * circle on each side, gets those taps reported through
     * [AppNavigationState.performActionTap] instead of being folded onto the nearest
     * slot (a tap on the leading circle used to select the first tab).
     */
    private fun actionBandAt(x: Float): Int {
        val left = navigationActionLeftReservePx()
        if (left > 0f && x < left) return 0
        val right = navigationActionReservePx()
        if (right > 0f && x >= width - right) return 1
        return -1
    }

    /**
     * Panel outline. Normally one capsule spanning the host, but an adapter with a
     * detached action button gets two separate shapes —the pill holding the
     * navigation entries and a circle for the button —so the gap between them stays
     * transparent instead of being bridged by glass. Both shapes live in one Path, so
     * every existing consumer (clip, fill, shadow, outline) keeps working unchanged.
     */
    private fun rebuildPanelPath() {
        if (width <= 0 || height <= 0) return
        val gap = navigationActionGapPx()
        val button = navigationActionButtonPx()
        val leftGap = navigationActionLeftGapPx()
        val leftButton = navigationActionLeftButtonPx()
        if (gap <= 0f || button <= 0f) {
            if (leftGap <= 0f || leftButton <= 0f) {
                clipPath.setBottomBarSquircle(outerRect, outerCornerRadiusPx(), config.cornerSmoothing)
                return
            }
            // Leading button only: the capsule starts after its gap.
            val pillLeft = (leftButton + leftGap).coerceAtLeast(1f)
            val pillRect = RectF(pillLeft, 0f, width.toFloat(), height.toFloat())
            clipPath.setBottomBarSquircle(
                pillRect,
                min(pillRect.width(), pillRect.height()) * (config.cornerRadiusPercent / 100f),
                config.cornerSmoothing,
            )
            clipPath.addOval(
                RectF(0f, (height - leftButton) / 2f, leftButton, (height + leftButton) / 2f),
                Path.Direction.CW,
            )
            return
        }
        val pillLeft = if (leftGap > 0f && leftButton > 0f) {
            (leftButton + leftGap).coerceAtLeast(1f)
        } else {
            0f
        }
        val pillRight = (width - button - gap).coerceAtLeast(1f)
        val pillRect = RectF(pillLeft, 0f, pillRight, height.toFloat())
        // setBottomBarSquircle resets the path, so the circles must be appended after it.
        clipPath.setBottomBarSquircle(
            pillRect,
            min(pillRect.width(), pillRect.height()) * (config.cornerRadiusPercent / 100f),
            config.cornerSmoothing,
        )
        // The circles are vertically centred: an adapter may ask for actions smaller than
        // the bar, and an oval spanning the full height would come out as an ellipse.
        if (leftGap > 0f && leftButton > 0f) {
            clipPath.addOval(
                RectF(0f, (height - leftButton) / 2f, leftButton, (height + leftButton) / 2f),
                Path.Direction.CW,
            )
        }
        clipPath.addOval(
            RectF(
                width - button,
                (height - button) / 2f,
                width.toFloat(),
                (height + button) / 2f,
            ),
            Path.Direction.CW,
        )
    }

    private fun tabWidthPx(): Float =

        ((width - 8f * density - navigationActionReservePx() - navigationActionLeftReservePx() -
            inlineActionWidthPx()).coerceAtLeast(1f) /
            visualSlotCount().coerceAtLeast(1))



    private fun logGeometryIfChanged(reason: String, force: Boolean = false) {

        if (context.packageName != QQ_PACKAGE) return

        val hostLocation = IntArray(2).also { runCatching { getLocationInWindow(it) } }

        val source = navigationSource

        val sourceLocation = IntArray(2).also { location ->

            source?.let { runCatching { it.getLocationInWindow(location) } }

        }

        val params = layoutParams as? FrameLayout.LayoutParams

        val transform = source?.takeIf { it.width > 0 && it.height > 0 }?.let(::sourceTransform)

        val snapshot = navigationSnapshot?.takeUnless(Bitmap::isRecycled)

        val uiNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        val signature = buildString {

            append("reason=").append(reason)

            append(" ui=").append(uiNight)

            append(" host=").append(hostLocation[0]).append(',').append(hostLocation[1])

                .append(' ').append(width).append('x').append(height)

            append(" frame=").append(left).append(',').append(top).append(',').append(right).append(',').append(bottom)

            append(" lp=").append(params?.width).append('x').append(params?.height)

                .append(" bm=").append(params?.bottomMargin)

            append(" source=").append(source?.javaClass?.name)

                .append('@').append(sourceLocation[0]).append(',').append(sourceLocation[1])

                .append(' ').append(source?.width).append('x').append(source?.height)

                .append(" attached=").append(source?.isAttachedToWindow)

            append(" snapshot=").append(snapshot?.width).append('x').append(snapshot?.height)

            append(" navNorm=").append(lastNavigationNormalization)

            append(" transform=").append(transform?.scale).append(',').append(transform?.dx).append(',').append(transform?.dy)

            append(" indicator=").append(indicatorPosition).append('/').append(selectedIndex)

            append(" live=").append(liveBackdropActive).append('/').append(liveBackdropNode.hasDisplayList())

            append(" effects=").append(outerGlassEffect != null).append('/').append(tabsBackdropEffect != null)

            append(" shaders=").append(outerLensShader != null).append('/')

                .append(combinedOuterLensShader != null).append('/').append(indicatorLensShader != null)

        }

        val stableSignature = signature.substringAfter(' ')

        if (!force && stableSignature == lastGeometryLogSignature) return

        lastGeometryLogSignature = stableSignature

        Log.i(DIAGNOSTIC_TAG, signature)

        Xposed.log("[$DIAGNOSTIC_TAG] $signature")

    }



    /**
     * How often the host re-reads the configuration from scratch.
     *
     * While the framework pushes settings changes into this process (modern
     * entry + remote preferences) the read only exists as a safety net for a
     * push that never arrived, so it drops from once a second to once a minute.
     * Frameworks without that channel keep the original cadence.
     */
    private fun configPollIntervalMs(): Long =
        if (HookConfigReader.hasPushChannel()) CONFIG_PUSH_SAFETY_INTERVAL_MS else CONFIG_POLL_INTERVAL_MS

    private fun maybeReloadConfig() {

        val now = SystemClock.uptimeMillis()

        if (configPollPending || now - lastConfigPoll < configPollIntervalMs()) return

        lastConfigPoll = now

        // WeChat is being edited independently; preserve its existing refresh path.

        if (targetAdapter?.synchronousConfigPolling == true) {

            val updated = packageConfig(HookConfigReader.readFresh(context, context.packageName))

            // This poll runs every second, so the reading is identical on almost every pass.
            // The repeat gate keeps the first occurrence and any change, and drops the rest.
            val pollLine = "cfgPoll pkg=${context.packageName} read.blur=${updated.blurRadius} " +
                "live.blur=${config.blurRadius} eq=${updated == config}"
            if (diagLogGate(pollLine)) Log.i(DIAGNOSTIC_TAG, pollLine)

            if (updated != config) applyLiveConfig(updated)

            return

        }

        configPollPending = true

        val generation = configPollGeneration

        val startingConfig = config

        val hostRef = WeakReference(this)

        HookConfigReader.readFreshAsync(context, context.packageName) { result ->

            val host = hostRef.get()

            if (host != null && host.configPollGeneration == generation) {

                host.configPollPending = false

                // Do not overwrite a newer pushed configuration or a replacement host.

                if (result != null && host.isAttachedToWindow && host.config === startingConfig) {

                    val updated = host.packageConfig(result)

                    if (updated != host.config) host.applyLiveConfig(updated)

                }

            }

        }

    }



    private fun beginThemeTransition(resizeSourceAfterSettle: Boolean) {

        val transitionStart = SystemClock.uptimeMillis()

        themeTransitionUntil = transitionStart + THEME_TRANSITION_GRACE_MS

        navigationSnapshotFrozenUntil = transitionStart + SOURCE_RESIZE_DELAY_MS

        pendingNavigationSignature = Long.MIN_VALUE

        pendingNavigationStableFrames = 0

        val generation = ++sourceSizingGeneration

        if (!resizeSourceAfterSettle || context.packageName != QQ_PACKAGE) return



        // QQ applies night resources, rebuilds custom icon Views, and lays the

        // row out in separate passes. Reapplying our user scale in the middle of

        // those passes is what produced the permanently compressed/overlapping

        // row. Wait for three identical layout frames and then size it once.

        var previousSignature = Long.MIN_VALUE

        var stableFrames = 0

        fun awaitStableSource() {

            if (generation != sourceSizingGeneration || !isAttachedToWindow) return

            val signature = navigationLayoutSignature(navigationSource)

            if (signature != Long.MIN_VALUE && signature == previousSignature) {

                stableFrames++

            } else {

                previousSignature = signature

                stableFrames = if (signature == Long.MIN_VALUE) 0 else 1

            }

            if (stableFrames >= SOURCE_STABLE_FRAMES &&

                SystemClock.uptimeMillis() - transitionStart >= SOURCE_RESIZE_DELAY_MS

            ) {

                applyLiveNavigationSizing()

                // setIconSize/textSize may legitimately request one final QQ

                // layout. The retained host/backdrop stays visible throughout.

                postOnAnimation {

                    pendingBackdropSignature = Long.MIN_VALUE

                    pendingBackdropStableFrames = 0

                    navigationSnapshotFrozenUntil = SystemClock.uptimeMillis() + SOURCE_POST_RESIZE_SETTLE_MS

                    pendingNavigationSignature = Long.MIN_VALUE

                    pendingNavigationStableFrames = 0

                    invalidate()

                }

            } else {

                postOnAnimation { awaitStableSource() }

            }

        }

        postOnAnimation { awaitStableSource() }

    }



    private fun navigationLayoutSignature(source: ViewGroup?): Long {

        source ?: return Long.MIN_VALUE

        if (!source.isAttachedToWindow || source.width <= 0 || source.height <= 0) return Long.MIN_VALUE

        var signature = 17L

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += source to 0

        var laidOutViews = 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            signature = signature * 31L + System.identityHashCode(view)

            signature = signature * 31L + view.left

            signature = signature * 31L + view.top

            signature = signature * 31L + view.width

            signature = signature * 31L + view.height

            signature = signature * 31L + view.visibility

            // Fade-in animations (JD page switches) wash out or blank the

            // captured row when alpha is ignored: the mid-fade capture stays

            // frozen because nothing else changes once the fade completes.

            signature = signature * 31L + view.alpha.toRawBits()

            if (view.width > 0 && view.height > 0) laidOutViews++

            if (view is TextView) {

                signature = signature * 31L + view.currentTextColor

                signature = signature * 31L + view.textSize.toRawBits()

                // JD binds its tab labels asynchronously AFTER the row's

                // geometry has settled. Without the text in the signature the

                // blank capture stayed frozen and the glass bar lost its

                // labels until the next geometry change.

                signature = signature * 31L + (view.text?.hashCode() ?: 0)

            }

            // Mi Health re-binds its tab icons (ImageView drawables) after the

            // row geometry settles on return from device sub-pages. Without

            // the drawable identity in the signature a capture taken mid-rebind

            // (icons still empty) stayed frozen and the icons disappeared.

            if (view is ImageView && targetAdapter?.trackSourceDrawableIdentity == true) {

                val drawable = view.drawable

                signature = signature * 31L + (drawable?.let { System.identityHashCode(it) } ?: 0)

            }

            signature = signature * 31L + view.drawableState.contentHashCode()

            signature = signature * 31L + if (view.isSelected) 1 else 0

            signature = signature * 31L + if (view.isActivated) 1 else 0

            // JD's tab labels sit deeper inside its NavigationGroup; the deeper

            // walk makes their async text rebinds change the signature so a

            // blank capture is never left frozen on screen.

            if (view is ViewGroup && depth < (targetAdapter?.navigationSignatureMaxDepth ?: 5)) {

                signature = signature * 31L + view.childCount

                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)

            }

        }

        return signature.takeIf { laidOutViews >= slotCount * 2 } ?: Long.MIN_VALUE

    }



    /**

     * The Tencent adapter owns an independent navigation View

     * and only uses QQ's row as an adapter. Keep the same separation here by

     * flattening the completed QQ icon/text row into a tiny transparent bitmap.

     * QQ may freely tear down or half-layout its native row during a theme

     * switch without those intermediate frames entering our glass renderer.

     */

    private fun maybeRefreshNavigationSnapshot() {

        val probeTime = SystemClock.uptimeMillis()
        if (navigationSnapshot?.isRecycled == false &&
            probeTime < barAnimationPriorityUntil
        ) return
        val limitProbeCadence = probeTime < barAnimationPriorityUntil
        if (limitProbeCadence && lastPriorityNavigationProbeAt != 0L &&
            probeTime - lastPriorityNavigationProbeAt < 48L
        ) return
        if (limitProbeCadence) lastPriorityNavigationProbeAt = probeTime

        if (targetAdapter?.skipNavigationSnapshot == true) return


        if (appNavigationState?.retainBackdropDuringTransition(SystemClock.uptimeMillis()) == true &&

            navigationSnapshot?.let { !it.isRecycled } == true

        ) return

        val now = SystemClock.uptimeMillis()

        if (now < navigationSnapshotFrozenUntil) return

        val source = navigationSource ?: return

        if (targetAdapter?.ownsNavigationSnapshotCapturePolicy == true &&
            appNavigationState?.skipNavigationSnapshotCapture(
                source, navigationSnapshot, touching, isBarVisualStateAnimating(), slotCount, now,
            ) == true
        ) return

        val signature = navigationLayoutSignature(source)

        val followingNativeAnimation = appNavigationState?.followingNativeNavigationAnimation(now) == true

        val pendingNativeTarget = appNavigationState?.pendingNativeNavigationTarget(now)
        if (pendingNativeTarget != null) {

            // Never publish a frame belonging to an earlier rapid-switch

            // request. Continue probing, but keep the last complete snapshot

            // until JD's native controller reports the latest committed target.

            val nativeSelection = if (nativeSelectionReliable) {

                selectionForFrame()

            } else {

                pendingNativeTarget

            }

            if (nativeSelection != pendingNativeTarget) {

                pendingNavigationSignature = Long.MIN_VALUE

                pendingNavigationStableFrames = 0

                return

            }

        }

        if (signature == Long.MIN_VALUE ||

            (signature == navigationSnapshotSignature && !followingNativeAnimation)

        ) {

            pendingNavigationSignature = Long.MIN_VALUE

            pendingNavigationStableFrames = 0

            return

        }

        if (signature == pendingNavigationSignature) {

            pendingNavigationStableFrames++

        } else {

            pendingNavigationSignature = signature

            pendingNavigationStableFrames = 1

        }

        val requiredFrames = targetAdapter?.navigationSnapshotStableFrames
            ?: NAVIGATION_SNAPSHOT_STABLE_FRAMES

        if (pendingNavigationStableFrames < requiredFrames) return



        val captureLabels = appNavigationState?.inspectNavigationCaptureLabels(
            source, navigationSnapshot != null,
        )
        if ((captureLabels?.retryDelayMs ?: 0L) > 0L) {
            navigationSnapshotFrozenUntil = SystemClock.uptimeMillis() + captureLabels!!.retryDelayMs
            return
        }



        runCatching {

            val snapshotHeight = source.height

            val stagedCapture = targetAdapter?.stagedNavigationSnapshot == true

            var target = if (stagedCapture) {

                // Never draw JD's asynchronous icon directly into the bitmap

                // currently displayed by the Surface. Capture into a staging

                // bitmap and swap references only after visual validation.

                navigationCaptureScratch

                    ?.takeIf { !it.isRecycled && it.width == source.width && it.height == snapshotHeight }

                    ?: Bitmap.createBitmap(source.width, snapshotHeight, Bitmap.Config.ARGB_8888).also {

                        navigationCaptureScratch?.takeUnless(Bitmap::isRecycled)?.recycle()

                        navigationCaptureScratch = it

                    }

            } else {

                navigationSnapshot

                    ?.takeIf { !it.isRecycled && it.width == source.width && it.height == snapshotHeight }

                    ?: Bitmap.createBitmap(source.width, snapshotHeight, Bitmap.Config.ARGB_8888).also {

                        navigationSnapshot?.recycle()

                        navigationSnapshot = it

                    }

            }

            val captureAccepted = appNavigationState?.captureNavigation(source, target) == true
            if (!captureAccepted) return@runCatching

            val transformedSnapshot = appNavigationState?.transformNavigationSnapshot(source, target, slotCount)
            if (transformedSnapshot != null && transformedSnapshot !== target) {
                if (target === navigationSnapshot) navigationSnapshot = transformedSnapshot
                if (target === navigationCaptureScratch) navigationCaptureScratch = transformedSnapshot
                target.recycle()
                target = transformedSnapshot
            }

            appNavigationState?.replaceCapturedVideoSlot(target)

            val appAccepted = appNavigationState?.acceptAndCommitNavigationSnapshot(
                navigationSource, target, selectedIndex, slotCount, ::commitNavigationSnapshot,
            )
            if (appAccepted == false) return@runCatching
            if (appAccepted == null &&
                appNavigationState?.acceptCapturedNavigation(navigationSource, target, selectedIndex) == false
            ) return@runCatching

            if (targetAdapter?.stagedNavigationSnapshot == true) {

                // Publish a complete staged row; never draw into the bitmap on screen.

                val previous = navigationSnapshot

                navigationSnapshot = target

                navigationCaptureScratch = previous

            }

            navigationSnapshotSignature = signature

            pendingNavigationSignature = Long.MIN_VALUE

            pendingNavigationStableFrames = 0

            // The capture may have redrawn content in place into the reused

            // bitmap object; the recorded display list must be rebuilt.

            navigationContentRecordedBitmap = null

            navigationSnapshot?.takeUnless(Bitmap::isRecycled)?.let {
                appNavigationState?.onNavigationSnapshotReady(it, slotCount)
            }

            logGeometryIfChanged("snapshot", force = true)

            if (targetAdapter?.redrawSurfaceAfterNavigationCapture == true &&
                opticalSurfaceSurfacePipelineActive
            ) {

                // A snapshot change is independent from the sampled page strip;

                // submit it once even when the feed is already stationary.

                queueOpticalSurfaceRender()

            }

            invalidate()

        }.onFailure { error ->

            Xposed.log("[OfflineGlass][QQNavSnapshot] capture failed: $error")

        }

    }



    /**

     * JD's bottom bar labels are TextViews in the lower half of the native

     * NavigationGroup row. The deep scan is affordable because it only runs

     * when a snapshot capture is actually attempted and the row subtree is

     * small; a width cap keeps full-width text strips out of the result.
     */

    /** Keep JD's unread badges out of the icon/label colour-filter layer. */




    /**

     * Extract the file manager's main tab row artwork (鏈€杩?娴忚 tabs plus the

     * standalone circular search key) into the static icon cache. Unlike the

     * generic snapshot path, each element is drawn in isolation, so the pill

     * geometry never distorts the artwork. The multi-select action bar does

     * not use this cache (its source id is split_action_bar).

     */

    private fun populateFileManagerStaticIcons() {

        if (fmStaticIconsPopulated || context.packageName != FILE_MANAGER_PACKAGE) return

        val source = navigationSource as? ViewGroup ?: return

        if (!source.isAttachedToWindow || source.width <= 0 || source.height <= 0) return

        if (viewResourceEntryName(source) != FILE_MANAGER_BOTTOM_NAV_ID) {

            fmStableFrames = 0

            return

        }

        val tabs = (0 until source.childCount).mapNotNull { source.getChildAt(it) as? ViewGroup }

            .filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }

        val search = (0 until source.childCount).mapNotNull { source.getChildAt(it) as? ImageView }

            .filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }

            .maxByOrNull { it.width * it.height }

        if (tabs.size < 2 || search == null) {

            fmStableFrames = 0

            return

        }

        var signature = search.width * 31L + search.height

        for (tab in tabs) signature = signature * 31 + tab.width * 31L + tab.height

        if (signature != fmLastCaptureSignature) {

            fmLastCaptureSignature = signature

            fmStableFrames = 0

            return

        }

        if (fmStableFrames < 2) {

            fmStableFrames++

            return

        }

        runCatching {

            val captured = ArrayList<Bitmap>(3)

            for (view in tabs + search) {

                val savedAlpha = view.alpha

                if (savedAlpha < 0.01f) view.alpha = 1f

                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)

                view.draw(Canvas(bitmap))

                if (savedAlpha < 0.01f) view.alpha = savedAlpha

                captured += bitmap

            }

            for (index in 0 until minOf(captured.size, fmStaticSlots.size)) {

                fmStaticSlots[index]?.takeUnless(Bitmap::isRecycled)?.recycle()

                fmStaticSlots[index] = captured[index]

            }

            fmStaticIconsPopulated = true

            postInvalidateOnAnimation()

            Xposed.log(

                "[OfflineGlass][FileManager] populated main-bar icons " +

                    "tabs=${tabs.size} search=${search.width}x${search.height}",

            )

        }

    }



    /** Draw the file manager's extracted main-bar artwork into the glass host.

     *  The rightmost search key stays a standalone circle. */

    private fun drawFileManagerNavigation(canvas: Canvas, extraScale: Float) {

        if (!fmStaticIconsPopulated || slotCount <= 0 || width <= 0 || height <= 0) return

        val slotWidth = tabWidthPx()

        val horizontalPadding = 4f * density

        val contentCenterY = height / 2f

        for (visualIndex in 0 until minOf(slotCount, fmStaticSlots.size)) {

            val bitmap = fmStaticSlots[visualIndex]?.takeUnless(Bitmap::isRecycled) ?: continue

            val centerX = horizontalPadding + (visualIndex + 0.5f) * slotWidth

            val bmpWidth = bitmap.width.toFloat().coerceAtLeast(1f)

            val bmpHeight = bitmap.height.toFloat().coerceAtLeast(1f)

            val drawScale = min(slotWidth / bmpWidth, height / bmpHeight) * extraScale

            val drawWidth = bmpWidth * drawScale

            val drawHeight = bmpHeight * drawScale

            val iconRect = RectF(

                centerX - drawWidth / 2f,

                contentCenterY - drawHeight / 2f,

                centerX + drawWidth / 2f,

                contentCenterY + drawHeight / 2f,

            )

            canvas.save()

            if (visualIndex == slotCount - 1) {

                val radius = min(drawWidth, drawHeight) / 2f

                val circle = Path().apply {

                    addCircle(centerX, contentCenterY, radius, Path.Direction.CW)

                }

                canvas.clipPath(circle)

            }

            canvas.drawBitmap(bitmap, null, iconRect, fmIconPaint)

            canvas.restore()

        }

    }






    /** Commit the candidate bitmap as the navigation snapshot. */

    private fun commitNavigationSnapshot(candidate: Bitmap) {

        val previous = navigationSnapshot

        navigationSnapshot = candidate

        navigationCaptureScratch = previous?.takeIf {

            !it.isRecycled && it.width == candidate.width && it.height == candidate.height

        }

        if (previous !== navigationCaptureScratch) {

            previous?.takeUnless(Bitmap::isRecycled)?.recycle()

        }

    }



    private fun sampledBitmapSignature(bitmap: Bitmap): Long {

        var signature = 17L

        val step = 4

        var y = 0

        while (y < bitmap.height) {

            var x = 0

            while (x < bitmap.width) {

                val color = bitmap.getPixel(x, y)

                // Quantisation ignores tiny antialiasing noise while retaining

                // real icon-frame changes.

                val quantized = (Color.alpha(color) shr 5 shl 15) or

                    (Color.red(color) shr 5 shl 10) or

                    (Color.green(color) shr 5 shl 5) or

                    (Color.blue(color) shr 5)

                signature = signature * 31L + quantized

                x += step

            }

            y += step

        }

        return signature

    }






    /** Preserve a notification badge above the neutral tint applied to unselected items. */
    /** Exclude the badge from the item that receives the unselected colour tint. */
    /**

     * QQ uses both 165 px and 217 px tab shells. More importantly, its first

     * launch may initially center the icon row in the full 217 px shell and

     * later move it into the upper 165 px content band without changing the

     * shell geometry. Locate the actual transparent icon/text pixels and map

     * their vertical centre into our canonical row instead of cropping at y=0.

     */

    private fun captureNormalizedQqNavigation(source: ViewGroup, target: Bitmap) {

        val scratch = navigationCaptureScratch

            ?.takeIf { !it.isRecycled && it.width == source.width && it.height == source.height }

            ?: Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {

                navigationCaptureScratch?.recycle()

                navigationCaptureScratch = it

            }

        val rawCanvas = Canvas(scratch)

        rawCanvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)

        source.draw(rawCanvas)



        val recording = Canvas(target)

        recording.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)

        val splitY = (QQ_ICON_LABEL_SPLIT_DP * density).roundToInt()

            .coerceIn(1, scratch.height)

        val iconBand = findNavigationContentBand(scratch, 0, splitY)

        val labelBand = findNavigationContentBand(scratch, splitY, scratch.height)

        if (iconBand != null && labelBand != null) {

            val iconShift = QQ_CANONICAL_ICON_CENTER_DP * density -

                (iconBand.first + iconBand.second + 1) / 2f

            val labelShift = QQ_CANONICAL_LABEL_CENTER_DP * density -

                (labelBand.first + labelBand.second + 1) / 2f

            recording.drawBitmap(

                scratch,

                Rect(0, 0, scratch.width, splitY),

                RectF(0f, iconShift, scratch.width.toFloat(), splitY + iconShift),

                snapshotCopyPaint,

            )

            recording.drawBitmap(

                scratch,

                Rect(0, splitY, scratch.width, scratch.height),

                RectF(

                    0f,

                    splitY + labelShift,

                    scratch.width.toFloat(),

                    scratch.height + labelShift,

                ),

                snapshotCopyPaint,

            )

            lastNavigationNormalization =

                "i${iconBand.first}-${iconBand.second}:$iconShift," +

                    "l${labelBand.first}-${labelBand.second}:$labelShift/${source.height}"

        } else {

            val contentBand = findNavigationContentBand(scratch, 0, scratch.height, 0.8f)

            val shiftY = contentBand?.let { (top, bottom) ->

                target.height / 2f - (top + bottom + 1) / 2f

            }?.coerceIn(-source.height / 2f, source.height / 2f) ?: 0f

            recording.save()

            recording.translate(0f, shiftY)

            recording.drawBitmap(scratch, 0f, 0f, snapshotCopyPaint)

            recording.restore()

            lastNavigationNormalization = contentBand?.let { (top, bottom) ->

                "$top-$bottom/${source.height}:$shiftY"

            } ?: "raw/${source.height}:0"

        }

    }



    private fun findNavigationContentBand(

        bitmap: Bitmap,

        startY: Int,

        endY: Int,

        maximumRowFraction: Float = 0.22f,

    ): Pair<Int, Int>? {

        val width = bitmap.width

        val height = bitmap.height

        if (width <= 0 || height <= 0) return null

        val pixels = IntArray(width * height)

        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val maximumContentPixelsPerRow = (width * maximumRowFraction).toInt().coerceAtLeast(3)

        var top = height

        var bottom = -1

        for (y in startY.coerceAtLeast(0) until endY.coerceAtMost(height)) {

            var visiblePixels = 0

            val row = y * width

            for (x in 0 until width) {

                if ((pixels[row + x] ushr 24) >= 12) visiblePixels++

            }

            // Ignore empty rows and full-width shell/divider surfaces. The

            // remaining alpha belongs to the three icon/label slots.

            if (visiblePixels in 2 until maximumContentPixelsPerRow) {

                if (top == height) top = y

                bottom = y

            }

        }

        return if (bottom >= top) top to bottom else null

    }



    private fun applyLiveConfig(updated: GlassConfig) {

        val old = config

        config = packageConfig(updated)

        syncBurnInSchedule()

        Log.i(

            DIAGNOSTIC_TAG,

            "cfgApply pkg=${context.packageName} blur ${old.blurRadius} -> ${config.blurRadius} " +
                "bar ${config.barHeight}dp text ${config.textSize}sp " +
                "alphaScale ${old.glassAlphaScale} -> ${config.glassAlphaScale} " +
                "(light ${config.lightAlpha} dark ${config.darkAlpha}) " +
                "saturation ${old.glassSaturation} -> ${config.glassSaturation} " +
                "refraction ${config.refractionHeightDp}/${config.refractionStrengthDp}dp " +
                "top=${config.topBarLiquidGlass}",

        )

        // The top copy can be switched off while it is on screen. Take it away and let the
        // state put the app's own title row back (from onHostDetached), instead of leaving a
        // glass bar floating over a row it no longer owns.
        if (topBar && !config.topBarLiquidGlass && isAttachedToWindow) {
            post { (parent as? ViewGroup)?.removeView(this) }
            return
        }

        // The bottom copy is its own switch as well: off hands the row back to the app instead
        // of leaving a flat copy of our bar over a row whose alpha we cleared. The host gives
        // the row its alpha and background back as it detaches.
        //
        if (!topBar && !config.liquidGlassEnabled && isAttachedToWindow) {
            post { (parent as? ViewGroup)?.removeView(this) }
            return
        }

        detectedContentDark = null

        lastThemeProbe = 0L

        // Config changes keep the last valid GPU scene until the replacement

        // signature is stable. This prevents the panel flashing into its plain

        // translucent fallback while a source View is remeasured.

        pendingBackdropSignature = Long.MIN_VALUE

        pendingBackdropStableFrames = 0



        if (old.tabWidth != config.tabWidth || old.bottomPadding != config.bottomPadding ||

            // 底栏下边距 moves the host's own bottom box, so the live layout must re-place it —
            // including while the slider is being dragged (the change is debounced, not throttled
            // away).
            old.bottomMarginOffsetDp != config.bottomMarginOffsetDp ||

            old.hiddenMask != config.hiddenMask || old.blurRadius != config.blurRadius ||

            old.cornerRadiusPercent != config.cornerRadiusPercent ||

            old.cornerSmoothing != config.cornerSmoothing || old.barHeight != config.barHeight ||

            // 椤舵爮鍙傛暟 changes the top copy's box. On the bottom host these two are inert, so
            // the recompute is a no-op there —the same trade the other knobs above make.
            old.topBarMarginOffsetDp != config.topBarMarginOffsetDp ||

            old.topBarScale != config.topBarScale ||

            // 顶栏两侧按钮 / 顶栏标签数量 / 顶栏标签排序: the top copy's own geometry. The two hide
            // flags zero a reserve, the count and order change the slot band's width, so both
            // the box and (for the reserves) the panel path have to be recomputed.
            old.topBarHideLeadingAction != config.topBarHideLeadingAction ||

            old.topBarHideTrailingAction != config.topBarHideTrailingAction ||

            old.topBarTabCount != config.topBarTabCount ||

            old.topBarTabOrder != config.topBarTabOrder

        ) {

            updateHostLayoutForConfig()

        }

        // The lenses are RuntimeShaders and the vibrancy filter is a ColorMatrix, both
        // snapshotted when their RenderEffect is created —a cached effect keeps drawing
        // the old values. Nulling the effects is this host's rebuild flag (the draw path
        // calls configureGlassEffects() when outerGlassEffect is null).
        if (old.glassSaturation != config.glassSaturation ||
            old.refractionHeightDp != config.refractionHeightDp ||
            old.refractionStrengthDp != config.refractionStrengthDp
        ) {
            outerGlassEffect = null
            opticalSurfaceOuterGlassEffect = null
            sharedOpticalBlurPrepared = false
            postInvalidateOnAnimation()
        }

        // The glass fill colour is resolved during draw, so a repaint is enough.
        if (old.glassAlphaScale != config.glassAlphaScale) postInvalidateOnAnimation()

        if (old.cornerRadiusPercent != config.cornerRadiusPercent ||

            old.cornerSmoothing != config.cornerSmoothing || old.barHeight != config.barHeight ||

            // 鍘婚櫎鍙戝竷鎸夐挳 turns the panel from "pill plus detached circle" into the single
            // capsule spanning the host, so the path and its outline have to be rebuilt when
            // that switch moves. The host width is unchanged either way (glassHostWidthPx still
            // sizes the panel as the full cluster), which is what makes the capsule fill it.
            old.showPostButton != config.showPostButton ||

            // 顶栏两侧按钮: a hidden corner key removes a whole shape from the panel path (the
            // circle) and moves the capsule's edge, so the path must be rebuilt.
            old.topBarHideLeadingAction != config.topBarHideLeadingAction ||

            old.topBarHideTrailingAction != config.topBarHideTrailingAction

        ) {

            rebuildPanelPath()

            invalidateOutline()

            opticalSurfaceNativeBlurView?.invalidateOutline()

            opticalSurfaceNativeIndicatorView?.invalidateOutline()

        }

        // 搴曟爮鑿滃崟椤归殣钘?is a bottom-bar switch (see isNavigationEntryDisabled), so the top
        // copy never runs this: its row is the app's own title bar, where the same bit would
        // hide an unrelated tab.
        if (!topBar && old.hiddenMask != config.hiddenMask) {

            // Entry switches affect four independent layers: the retained

            // native controller row, our artwork, the pill's legal drag stops,

            // and direct tap routing.  Keep the native child indices stable,

            // but hide their pixels and move an invalid current selection to

            // the nearest remaining entry immediately.

            findSlotContainer()?.let { container ->

                for (index in 0 until min(container.childCount, slotCount)) {

                    val cell = container.getChildAt(index)

                    // Each cell is only ever touched for the mask bit that owns it: a cell the
                    // *previous* mask hid comes back, a newly hidden one goes away, and a cell
                    // another switch owns (鍘婚櫎鍙戝竷鎸夐挳's publish cell) keeps whatever it had.
                    when {

                        isNavigationEntryDisabled(index) -> if (cell.visibility != View.INVISIBLE) {
                            cell.visibility = View.INVISIBLE
                        }

                        old.hiddenMask and (1 shl index) != 0 -> if (cell.visibility != View.VISIBLE) {
                            cell.visibility = View.VISIBLE
                        }

                    }

                }

            }

            val target = selectedIndex.takeUnless(::isNavigationEntryDisabled)

                ?: enabledNavigationIndices().firstOrNull()

            if (target != null) {

                selectedIndex = target

                dragTarget = visualPositionForNavigationIndex(target)

                indicatorPosition = dragTarget

                positionSpring.snapTo(dragTarget)

                // Deliberately no performNavigationSelection() here. Hiding the entry whose
                // page happens to be on screen must not switch Douyin's page: 搴曟爮鑿滃崟椤归殣钘?
                // only takes a button away. The draw path re-reads the app's own selection and
                // maps a hidden one onto the nearest visible entry (nearestSelectableIndex in
                // dispatchDraw), so the capsule still lands on a real slot.

            }

            pendingNavigationSignature = Long.MIN_VALUE

            pendingNavigationStableFrames = 0

        }

        applyLiveNavigationSizing()

        configureGlassEffects()



        if (old.backdropCapture != config.backdropCapture) {

            if (config.backdropCapture) installCaptureLoop() else removeCaptureLoop()

        }

        if (old.nativeBlur != config.nativeBlur ||
            old.themeMode != config.themeMode ||

            old.blurRadius != config.blurRadius

        ) {

            if (nativeBlurActive) disableMiuiBackgroundBlur()

            nativeBlurActive = targetAdapter?.allowsSystemBackgroundBlur != false && shouldUseNativeBackgroundBlur() &&

                enableMiuiBackgroundBlur()

            if (targetAdapter?.usesDirectOpticalBackdropMode == true) {

                opticalSurfaceNativeBlurView?.let(::disableOpticalSurfaceNativeBlur)

                opticalSurfaceNativeIndicatorView?.let(::disableOpticalSurfaceNativeBlur)

                opticalSurfaceNativeBlurActive = false

                opticalSurfaceNativeIndicatorActive = false

                appNavigationState?.onOpticalRendererConfigurationChanged()

                opticalSurfaceNativeIndicatorEffectSignature = Long.MIN_VALUE

                syncBackdropRendererMode()

            }

        }

        invalidate()

        updateBloomTiltRegistration()

    }



    /**
     * Lets an adapter re-run the host's own layout sizing. The top copy needs it because
     * its slot count is already correct when it is constructed, so the "slot count
     * changed" path never fires and its content-hugging width would never be applied.
     */
    /**
     * Hands the app's own bar back: undoes the alpha and background this host cleared when it
     * took the row over.
     *
     * Runs on every detach, which is what makes switching the bottom copy off end with Douyin's
     * own bar visible instead of an invisible row where the glass used to be. The lifts the page
     * controller added are released by `dispose()` on the state's own detach path.
     */
    private fun restoreNavigationSourceChrome() {
        val source = navigationSource ?: return
        if (capturedNavigationAlpha) source.alpha = navigationAlphaBeforeHost
        if (capturedNavigationBackground) source.background = navigationBackgroundBeforeHost
    }

    internal fun refreshHostLayout() {
        if (topBar) updateHostLayoutForConfig()
    }

    private fun updateHostLayoutForConfig() {
        val content = parent as? ViewGroup ?: return

        val params = layoutParams as? FrameLayout.LayoutParams ?: return

        // The top copy keeps the height its installer measured from the app's own title
        // bar: the shared bar height would make it taller than the row it replaces, and
        // every config poll would otherwise push it back. 椤舵爮鍙傛暟 re-runs the same
        // arithmetic against the bound row, so 鏁翠綋缂╂斁 lands live, and at its default it
        // returns exactly what the installer set.
        val desiredHeight = if (topBar) {
            val measured = navigationSource?.height ?: 0
            if (measured <= 0) {
                // Nothing to follow this pass (a rebind or a page transition can report a row of
                // height 0). Keep the copy exactly as it is instead of dropping it to the shared
                // bar height for a frame.
                params.height
            } else {
                topBarHeightPx(
                    config = config,
                    sourceHeightPx = measured,
                    density = density,
                    fraction = targetAdapter?.topBarHeightFraction() ?: 1f,
                    fallbackHeightPx = glassHostHeightPx(density, config, context.packageName),
                )
            }
        } else {
            glassHostHeightPx(density, config, context.packageName)
        }

        // 椤舵爮鍙傛暟: the copy's distance from the screen's top edge, from the same function the
        // installer used so the two can never disagree.
        val desiredTopMargin = if (topBar) topBarTopMarginPx(content, config, density) else params.topMargin

        // Four-or-fewer bars share the five-tab height and bottom gap. Four

        // tabs also share its side gap; fewer tabs retain the resulting slot

        // width. WeChat remains on its existing app-specific +10dp geometry.

        //

        // Count the app's visible TABS, not its renderable navigation slots. An
        // adapter's action-only slot (Douyin's detached publish button) is not a tab:
        // counting it here would flip this branch, move the host onto the
        // symmetric-width path and re-lay-out the app's whole bottom area.

        //
        // 搴曟爮鑿滃崟椤归殣钘?stays out of this count on purpose. It used to reduce it, which moved
        // the host onto the symmetric-width path and shrank the pill as soon as one entry was
        // switched off; the device pass asked for the opposite —hiding a button must not
        // resize the capsule, the remaining entries just re-space inside it. The count is
        // therefore the row's own slot count, exactly as if no entry were hidden.

        val symmetricFloatingLayout = slotCount <= 4

        val desiredBottom = if (topBar) {

            // A top-anchored copy has no bottom edge to place.
            0

        } else if (symmetricFloatingLayout) {

            glassHostBottomMarginPx(content, config) + if (targetAdapter?.hostLiftDp != null) {

                // WeChat lift, shared with the initial layout and side insets.

                ((targetAdapter?.hostLiftDp ?: 0f) * density).toInt()

            } else {

                0

            }

        } else {

            glassHostBottomMarginPx(content, config)

        }

        val desiredWidth = if (topBar && (targetAdapter?.topBarSlotWidthDp() ?: 0f) > 0f) {

            // The top copy hugs its content: one slot per tab plus both action reserves,
            // so the cluster stays compact instead of spanning the content frame.
            // 椤舵爮鍙傛暟 scales the slot band and the in-pill action with the rest of the
            // cluster, which is what keeps the labels on the capsule at any scale.
            val slotPx = (targetAdapter?.topBarSlotWidthDp() ?: 0f) * density * topBarScale()
            val reserved = navigationActionLeftReservePx() + navigationActionReservePx()
            (8f * density + visualSlotCount() * slotPx + inlineActionWidthPx() + reserved)
                .roundToInt()
                .coerceAtMost(content.width)

        } else if (topBarWidthPx > 0 && topBar) {

            // The top copy keeps the width its installer measured: a reduced tab count
            // must not shrink the bar and pull its corner keys inwards.
            topBarWidthPx

        } else if (symmetricFloatingLayout) {

            symmetricGlassHostWidthPx(content, density, config, slotCount)

        } else {

            // The panel must be wide enough for every native cell: the navigation tabs
            // *and* an adapter's action-only slot, which the renderer turns into its
            // detached button. Using the navigation-only count here would shrink the
            // panel and squeeze the tabs (tabWidthPx already subtracts the reserve).
            glassHostWidthPx(content, slotCount, config, density)

        }

        if (params.width != desiredWidth || params.height != desiredHeight ||

            params.bottomMargin != desiredBottom || params.topMargin != desiredTopMargin

        ) {

            params.width = desiredWidth

            params.height = desiredHeight

            params.bottomMargin = desiredBottom

            params.topMargin = desiredTopMargin

            layoutParams = params

            requestLayout()

        }

    }



    private fun applyLiveNavigationSizing() {

        // QQ's row is an adapter only. Icon/text size is applied in our own

        // fixed slot coordinate space when drawing, never back into QQ Views.

        if (context.packageName == QQ_PACKAGE) invalidate()

    }



    private fun drawNavigationSource(canvas: Canvas) {

        val heightScale = (height / (64f * density)).coerceIn(0.875f, 1f)

        appNavigationState?.prepare(navigationSource) { delay, action -> postDelayed(action, delay) }

        if (context.packageName == QQ_PACKAGE && hasQqStaticIcons()) {

            drawQqStaticNavigation(canvas, extraScale = heightScale)

        } else if (targetAdapter?.ownsNavigationDrawing == true && appNavigationState?.readyForDrawing != false) {

            drawAdapterNavigation(canvas, extraScale = heightScale)

        } else if (context.packageName == FILE_MANAGER_PACKAGE && fmStaticIconsPopulated) {

            drawFileManagerNavigation(canvas, extraScale = heightScale)

        } else {

            drawNavigationContent(

                canvas,

                extraScale = heightScale,

                // Preserve native icon coloring (white outline icons

                // that turn blue when selected). The liquid pill indicator

                // alone marks the active tab; no additional icon tint is

                // applied on top.

                tintBySelection = context.packageName == QQ_PACKAGE,

            )

        }

        appNavigationState?.drawAppBadgeOverlay(canvas, this, heightScale)

    }



    /** Scale every item around its own centre in the hidden source row. */

    private fun drawScaledNavigationSource(canvas: Canvas, contentScale: Float) {

        val scaledContent = contentScale * (height / (64f * density)).coerceIn(0.875f, 1f)

        if (context.packageName == QQ_PACKAGE && hasQqStaticIcons()) {

            drawQqStaticNavigation(canvas, extraScale = scaledContent)

        } else if (targetAdapter?.ownsNavigationDrawing == true && appNavigationState?.readyForDrawing != false) {

            drawAdapterNavigation(
                canvas,
                extraScale = scaledContent,
                selectedSlot = navigationIndexForVisualIndex(
                    indicatorPosition.roundToInt().coerceIn(0, visualSlotCount() - 1),
                ),
            )

        } else if (context.packageName == FILE_MANAGER_PACKAGE && fmStaticIconsPopulated) {

            drawFileManagerNavigation(canvas, extraScale = scaledContent)

        } else {

            drawNavigationContent(

                canvas,

                extraScale = scaledContent,

                // No icon tint for any package on this path: Mi Health and the

                // generic adapters keep their native selected/unselected

                // icon states verbatim, the pill alone marks the active tab.

                tintBySelection = false,

            )

        }

        appNavigationState?.drawAppBadgeOverlay(canvas, this, scaledContent)

    }



    /** Draws the three default QQ tab icons without QQTabDragAnimationView. */

    private fun drawQqStaticNavigation(canvas: Canvas, extraScale: Float) {

        if (slotCount != 3 || width <= 0 || height <= 0) return

        val slotWidth = tabWidthPx()

        val horizontalPadding = 4f * density

        val iconSize = 28f * density

        val iconCenterY = 20f * density

        val labelBaseline = 53f * density

        val unselectedColor = if (isDarkGlass()) Color.WHITE else QQ_DEFAULT_DARK_COLOR

        val itemScale = config.iconScale * extraScale

        qqStaticLabelPaint.textSize = 12f * resources.displayMetrics.scaledDensity



        for ((visualIndex, index) in enabledNavigationIndices().withIndex()) {

            val selected = index == selectedIndex

            val bitmap = (if (selected) qqSelectedIconBitmaps else qqNormalIconBitmaps)[index] ?: continue

            val centerX = horizontalPadding + (visualIndex + 0.5f) * slotWidth

            val color = if (selected) QQ_DEFAULT_ACCENT_COLOR else unselectedColor

            canvas.save()

            canvas.scale(itemScale, itemScale, centerX, height / 2f)



            qqStaticIconPaint.colorFilter = BlendModeColorFilter(color, BlendMode.SRC_IN)

            qqStaticIconPaint.alpha = if (selected) 255 else 235

            val iconRect = RectF(

                centerX - iconSize / 2f,

                iconCenterY - iconSize / 2f,

                centerX + iconSize / 2f,

                iconCenterY + iconSize / 2f,

            )

            canvas.drawBitmap(bitmap, null, iconRect, qqStaticIconPaint)



            qqStaticLabelPaint.color = color

            qqStaticLabelPaint.alpha = if (selected) 255 else 235

            canvas.drawText(QQ_TAB_LABELS[index], centerX, labelBaseline, qqStaticLabelPaint)

            canvas.restore()

        }

        qqStaticIconPaint.colorFilter = null

        qqStaticIconPaint.alpha = 255

        qqStaticLabelPaint.alpha = 255

    }



    private fun hasQqStaticIcons(): Boolean =

        qqNormalIconBitmaps.size == 3 && qqSelectedIconBitmaps.size == 3 &&

            qqNormalIconBitmaps.all { it != null } && qqSelectedIconBitmaps.all { it != null }



    private fun loadQqTabBitmap(fileName: String): Bitmap? = runCatching {

        javaClass.classLoader

            ?.getResourceAsStream("qq_icons/$fileName")

            ?.use(BitmapFactory::decodeStream)

    }.onFailure { error ->

        Xposed.log("[OfflineGlass][QQStaticIcons] $fileName: $error")

    }.getOrNull()



    /** Draw JD's cached icons with theme-based color tinting, scaled by the

     *  slot transform factor to match the visual slot size. The entire cached

     *  slot (icon + label) is drawn with correct aspect ratio; no separate

     *  label text is drawn to avoid double-text. */


    /**

     * Draw Weibo's own light/dark, selected/unselected assets in LiquidTab's

     * Draw Weibo's own light/dark, selected/unselected assets in LiquidTab's

     * stable coordinate space. The native row stays attached only as the page

     * controller and badge source, so feed cards and transient tab animations

     * can never be captured into the liquid bar.

     */







    /**
     * Whether the selection ink belongs to the capsule's lens scene this frame.
     *
     * While the finger is down —or the press animation is still settling —the ink rides inside the
     * lens scene, so the glass refracts and disperses it. At rest it is painted over the glass
     * instead: the capsule's edge refraction band would otherwise smear the glyph's own extremes
     * (the icon's top bar, the label's baseline) into stray slivers, which is exactly the dot that
     * showed above the resting icon.
     */
    private fun selectionInkInLensScene(): Boolean =
        touching || pressProgress > 0.001f || isBarVisualStateAnimating()

    private fun drawAdapterNavigation(canvas: Canvas, extraScale: Float, selectedSlot: Int = selectedIndex) {        appNavigationState?.prepare(navigationSource) { delay, action -> postDelayed(action, delay) }
        val frame = adapterNavigationFrame(extraScale, selectedSlot)
        appNavigationState?.draw(canvas, frame) ?: targetAdapter?.drawNavigation(canvas, frame)
    }

    private fun adapterNavigationFrame(extraScale: Float, selectedSlot: Int = selectedIndex) = AdapterNavigationFrame(
            context = context,
            extraScale = extraScale,
            slotCount = slotCount,
            width = width,
            height = height,
            slotWidth = tabWidthPx(),
            leftActionReservePx = navigationActionLeftReservePx(),
            rightActionReservePx = navigationActionReservePx(),
            inlineActionWidthPx = inlineActionWidthPx(),
            actionButtonPx = if (navigationActionLeftButtonPx() > 0f) {
                navigationActionLeftButtonPx()
            } else {
                navigationActionButtonPx()
            },
            density = density,
            iconScale = config.iconScale,
            // 椤舵爮鍙傛暟 scales the top copy's labels with its height and slot band; the bottom
            // bar's own 鏂囧瓧澶у皬 setting is never multiplied by it.
            textSize = config.textSize * topBarScale(),
            scaledDensity = resources.displayMetrics.scaledDensity,
            selectedIndex = selectedSlot,
            accentClip = if (selectionPathValid) selectionClipPath else null,
            selectionInkInLensScene = selectionInkInLensScene(),
            enabledIndices = enabledNavigationIndices(),
            // 搴曟爮鏄剧ず鏍峰紡 is the bottom bar's own choice: the top copy is a labels-only design, so
            // handing it 浠呭浘鏍?would blank its tabs out and 浠呮枃瀛?would change nothing there.
            iconOnly = !topBar && config.iconOnly,
            textOnly = !topBar && config.textOnly,
            darkGlass = isDarkGlass(),
            accentColor = selectedNavigationAccent(),
            navigationOpticalMix = navigationOpticalMix(),
            hideSelectedForOptics = drawingHiddenNavigationForOptics,
            pressContentScale = if (topBar) 1f else 1f + 0.2f * pressProgress.coerceIn(0f, 1f),
            pressSlotScales = refreshPressSlotScales(),
            // 隐藏消息红点: skip the read entirely when the switch is on. The top copy has no
            // message entry at all, so it must not scan the title bar for a badge either.
            navigationBadge = readNavigationBadge(),
            publishIcon = config.publishIcon,
            customEntryLabels = customEntryLabels(),
        )

    /**
     * The adapter's badge, with a one-shot repaint when the value actually changed.
     *
     * Now that an idle bar no longer repaints itself every frame, an unread count that the app
     * updated without touching this host (Douyin refreshes it in its own row) would otherwise
     * stay stale until some unrelated invalidate. The value is sampled at 300 ms by the adapter,
     * so a change is rare and costs exactly one extra frame.
     */
    private fun readNavigationBadge(): NavigationBadge? {
        val badge = if (topBar || config.hideMessageBadge) {
            null
        } else {
            targetAdapter?.navigationBadge(navigationSource)
        }
        if (badge != lastNavigationBadge) {
            lastNavigationBadge = badge
            postInvalidateOnAnimation()
        }
        return badge
    }

    /**
     * 搴曟爮鑿滃崟鏂囧瓧: the user's per-entry names, parsed once per settings change rather than per
     * frame —the frame is rebuilt on every draw. Blanks are kept: the renderer substitutes its own
     * name for a blank entry, so a list that is short or half-filled never blanks a label out.
     */
    private var customEntryLabelsSource = ""
    private var customEntryLabelsParsed: List<String> = emptyList()

    private fun customEntryLabels(): List<String> {
        val raw = config.customEntryLabels
        if (raw != customEntryLabelsSource) {
            customEntryLabelsSource = raw
            customEntryLabelsParsed = if (raw.isEmpty()) emptyList() else raw.split('\n')
        }
        return customEntryLabelsParsed
    }

    private fun foregroundNavigationClipPath(): Path {
        val cutout = RectF(selectionRect).apply { inset(-density, -density) }
        return Path().apply {
            setBottomBarSquircle(
                cutout,
                cutout.height() * (config.cornerRadiusPercent / 100f),
                config.cornerSmoothing,
            )
        }
    }

    private fun navigationOpticalMix(): Float =
        if (targetAdapter?.usesNavigationOpticalMix != true || !sliderEnabled) 0f
        else if (touching) 1f
        else pressProgress.coerceIn(0f, 1f)



    /**

     * WeChat's native tab icons cannot be captured via View.draw() (produces

     * empty bitmap). Instead, walk the source's view tree and directly draw

     * each tab's icon Drawable, text label, and notification badge.

     *

     * Source structure: LauncherUIBottomTabView 锟?LinearLayout 锟?N 脳 RelativeLayout

     *   Each RelativeLayout: LinearLayout(label area) + RelativeLayout(icon area)

     *     Icon area: TabIconView(icon 85脳85) + ImageView(badge dot) + TextView(badge count)

     *

     * Text disappearing fix: draw text directly via canvas.drawText() instead of

     * labelView.draw(), avoiding parent alpha=0 interference.

     *

     * Badge fix: detect badge views (ImageView dot + TextView count) and draw

     * them when they are VISIBLE (WeChat sets them VISIBLE on unread messages).

     */




    private fun drawNavigationContent(canvas: Canvas, extraScale: Float, tintBySelection: Boolean) {

        val source = navigationSource?.takeIf { it.width > 0 && it.height > 0 }

        // For WeChat: source.draw() cannot capture native tab icons via snapshot

        // (produces empty bitmap). Always draw directly from source with alpha=1.

        val snapshot = when {

            targetAdapter?.nativeNavigationDrawsInSourceSpace == true -> null

            else -> navigationSnapshot?.takeUnless(Bitmap::isRecycled)

        }

        if (snapshot == null && source == null) return

        val sourceWidth = snapshot?.width ?: source!!.width

        val sourceHeight = snapshot?.height ?: source!!.height

        val firstEnabled = enabledNavigationIndices().firstOrNull() ?: return

        val transform = sourceTransformForSlot(sourceWidth, sourceHeight, firstEnabled, 0)

        val projectionCompensation = when (context.packageName) {

            QQ_PACKAGE -> (QQ_TARGET_ICON_DP / QQ_NATIVE_ICON_DP) * config.iconScale /

                transform.scale.coerceAtLeast(0.0001f)

            context.packageName.takeIf { targetAdapter?.nativeNavigationDrawsInSourceSpace == true } ->
                config.iconScale / transform.scale.coerceAtLeast(0.0001f)

            MEITUAN_TAKEOUT_PACKAGE -> config.iconScale / transform.scale.coerceAtLeast(0.0001f)

            context.packageName.takeIf { targetAdapter?.projectedNavigationScale != 1f } ->
                (config.iconScale * (targetAdapter?.projectedNavigationScale ?: 1f)) /
                    transform.scale.coerceAtLeast(0.0001f)

            else -> 1f

        }

        val finalScale = projectionCompensation * extraScale



        if (canvas.isHardwareAccelerated && targetAdapter?.nativeNavigationDrawsInSourceSpace != true) {

            if (snapshot != null) {

                if (snapshot !== navigationContentRecordedBitmap ||

                    sourceWidth != navigationContentRecordedWidth ||

                    sourceHeight != navigationContentRecordedHeight

                ) {

                    val navigationCanvas = navigationContentNode.beginRecording(sourceWidth, sourceHeight)

                    navigationCanvas.drawBitmap(snapshot, 0f, 0f, navigationPaint)

                    navigationContentNode.endRecording()

                    navigationContentNode.setPosition(0, 0, sourceWidth, sourceHeight)

                    navigationContentRecordedBitmap = snapshot

                    navigationContentRecordedWidth = sourceWidth

                    navigationContentRecordedHeight = sourceHeight

                }

            } else {

                val navigationCanvas = navigationContentNode.beginRecording(sourceWidth, sourceHeight)

                val src = source!!

                val saved = src.alpha; if (saved < 0.01f) src.alpha = 1f

                src.draw(navigationCanvas)

                if (saved < 0.01f) src.alpha = saved

                navigationContentNode.endRecording()

                navigationContentNode.setPosition(0, 0, sourceWidth, sourceHeight)

                navigationContentRecordedBitmap = null

            }

        }



        val slotWidth = tabWidthPx()

        val horizontalPadding = 4f * density

        val dark = isDarkGlass()

        for ((visualIndex, index) in enabledNavigationIndices().withIndex()) {

            val slotTransform = sourceTransformForSlot(

                sourceWidth,

                sourceHeight,

                index,

                visualIndex,

            )

            val left = horizontalPadding + visualIndex * slotWidth

            val right = left + slotWidth

            val centerX = (left + right) / 2f

            canvas.save()

            canvas.clipRect(left, 0f, right, height.toFloat())

            if (appNavigationState?.drawFixedNavigationSlot(

                    canvas = canvas,

                    index = index,

                    selectedIndex = selectedIndex,

                    dark = dark,

                    centerX = centerX,

                    centerY = height / 2f +

                        (slotTransform.dy + sourceHeight * slotTransform.scale / 2f - height / 2f) *

                        finalScale,

                    boxSize = min(sourceWidth / 5f, sourceHeight.toFloat()) *

                        slotTransform.scale * finalScale,

                ) == true

            ) {

                // Stable artwork lives in glass coordinates and is never

                // sourced from asynchronously recycled native tab Views.

                // Hidden native slots remain the click/selection targets.

                canvas.restore()

                continue

            }

            val tintLayer = if (tintBySelection) {

                val tint = if (index == selectedIndex) {

                    selectedNavigationAccent()

                } else if (dark) {

                    Color.WHITE

                } else {

                    Color.BLACK

                }

                navigationPaint.colorFilter = BlendModeColorFilter(tint, BlendMode.SRC_IN)

                navigationPaint.alpha = if (index == selectedIndex) 255 else 235

                canvas.saveLayer(left, 0f, right, height.toFloat(), navigationPaint)

            } else {

                -1

            }

            canvas.scale(finalScale, finalScale, centerX, height / 2f)

            val centerAction = targetAdapter?.sourceCenterActionIndex
            if (centerAction != null && index == centerAction) {
                canvas.translate(0f, height * 0.13f + (targetAdapter?.sourceCenterTranslationDp ?: 0f) * density)
                canvas.clipRect(left, 0f, right, height * (targetAdapter?.sourceCenterClipRatio ?: 1f))
            } else {
                canvas.translate(0f, (targetAdapter?.sourceDefaultTranslationDp ?: 0f) * density)
            }

            val contentOffsetY = appNavigationState?.sourceContentOffsetY(
                index, selectedIndex, height, slotTransform.dy, slotTransform.scale
            ) ?: (targetAdapter?.sourceContentOffsetDp ?: 0f) * density

            canvas.translate(slotTransform.dx, slotTransform.dy + contentOffsetY)

            canvas.scale(slotTransform.scale, slotTransform.scale)

            if (targetAdapter?.nativeNavigationDrawsInSourceSpace == true) {

                // WeChat: draw native tabs directly (source.draw() doesn't work)

                appNavigationState?.drawNativeNavigation(canvas, source!!, density)

            } else if (canvas.isHardwareAccelerated) {

                canvas.drawRenderNode(navigationContentNode)

            } else if (snapshot != null) {

                canvas.drawBitmap(snapshot, 0f, 0f, navigationPaint)

            } else {

                val src = source!!

                val saved = src.alpha; if (saved < 0.01f) src.alpha = 1f

                src.draw(canvas)

                if (saved < 0.01f) src.alpha = saved

            }

            // Douyin keeps its four navigation glyphs in the upper part of
            // the captured slot. Repaint only that glyph band at 1.5x around
            // the slot centre; the label band remains at its original size
            // and the slot geometry/touch positioning is unchanged.
            if (targetAdapter?.opticalIconBandScale != 1f) {
                val iconBandBottom = height * 0.48f
                canvas.save()
                canvas.clipRect(left, 0f, right, iconBandBottom)
                canvas.scale(targetAdapter?.opticalIconBandScale ?: 1f, targetAdapter?.opticalIconBandScale ?: 1f, centerX, 22f * density)
                if (canvas.isHardwareAccelerated) {
                    canvas.drawRenderNode(navigationContentNode)
                } else if (snapshot != null) {
                    canvas.drawBitmap(snapshot, 0f, 0f, navigationPaint)
                }
                canvas.restore()
            }

            if (tintLayer >= 0) canvas.restoreToCount(tintLayer)

            canvas.restore()

        }

        navigationPaint.colorFilter = null

        navigationPaint.alpha = 255

    }




    /**

     * QQ's unread badge belongs to the native tab child rather than the static

     * icon. Paint that child as the final layer so its original red artwork is

     * never included in the blue selected-icon mask.

     */

    private fun drawQqBadgeOverlay(canvas: Canvas) {

        if (slotCount != 3) return

        val source = navigationSource?.takeIf { it.width > 0 && it.height > 0 } ?: return

        val container = findSlotContainer() ?: return

        val sourceLocation = IntArray(2).also(source::getLocationInWindow)

        for (index in 0 until min(container.childCount, slotCount)) {

            if (isNavigationEntryDisabled(index)) continue

            val visualIndex = enabledNavigationIndices().indexOf(index)

            if (visualIndex < 0) continue

            val transform = sourceTransformForSlot(

                source.width,

                source.height,

                index,

                visualIndex,

            )

            val item = container.getChildAt(index)

            val stack = ArrayDeque<Pair<View, Int>>()

            stack += item to 0

            while (stack.isNotEmpty()) {

                val (candidate, depth) = stack.removeLast()

                if (candidate.javaClass.name == QQ_BADGE_CLASS &&

                    candidate.visibility == View.VISIBLE && candidate.width > 0 && candidate.height > 0

                ) {

                    val badgeLocation = IntArray(2).also(candidate::getLocationInWindow)

                    val centerX = transform.dx +

                        (badgeLocation[0] - sourceLocation[0] + candidate.width / 2f) * transform.scale

                    val centerY = transform.dy +

                        (badgeLocation[1] - sourceLocation[1] + candidate.height / 2f) * transform.scale

                    val badgeScale = max(

                        transform.scale,

                        QQ_BADGE_MIN_HEIGHT_DP * density / candidate.height.coerceAtLeast(1),

                    )

                    val save = canvas.save()

                    canvas.translate(

                        centerX - candidate.width * badgeScale / 2f,

                        centerY - candidate.height * badgeScale / 2f,

                    )

                    canvas.scale(badgeScale, badgeScale)

                    val oldAlpha = candidate.alpha

                    candidate.alpha = 1f

                    candidate.draw(canvas)

                    candidate.alpha = oldAlpha

                    canvas.restoreToCount(save)

                    break

                }

                if (candidate is ViewGroup && depth < 6) {

                    for (childIndex in candidate.childCount - 1 downTo 0) {

                        stack += candidate.getChildAt(childIndex) to (depth + 1)

                    }

                }

            }

        }

    }



    private fun readInstanceField(instance: Any, name: String): Any? {

        var type: Class<*>? = instance.javaClass

        while (type != null) {

            val field = runCatching { type.getDeclaredField(name) }.getOrNull()

            if (field != null) {

                field.isAccessible = true

                return runCatching { field.get(instance) }.getOrNull()

            }

            type = type.superclass

        }

        return null

    }



    private fun nearestSelectableIndex(rawIndex: Int): Int {

        val clamped = rawIndex.coerceIn(0, (slotCount - 1).coerceAtLeast(0))

        if (clamped !in nonSelectableIndices && !isNavigationEntryDisabled(clamped)) return clamped

        val direction = if (dragTarget >= selectedIndex) 1 else -1

        for (distance in 1 until slotCount) {

            val preferred = clamped + distance * direction

            if (preferred in 0 until slotCount && preferred !in nonSelectableIndices &&

                !isNavigationEntryDisabled(preferred)

            ) return preferred

            val alternate = clamped - distance * direction

            if (alternate in 0 until slotCount && alternate !in nonSelectableIndices &&

                !isNavigationEntryDisabled(alternate)

            ) return alternate

        }

        return selectedIndex.coerceIn(0, (slotCount - 1).coerceAtLeast(0))

    }



    private fun isNavigationEntryDisabled(index: Int): Boolean =

        // 搴曟爮鑿滃崟椤归殣钘?owns the bottom bar's entries only. A top copy must never read the
        // mask: its row is the app's own title bar, where the same bit would hide an unrelated
        // tab —and shrink the copy, whose width is derived from the visible slot count.
        !topBar && index in 0 until slotCount &&

            config.hiddenMask and (1 shl index) != 0



    private fun enabledNavigationIndices(): List<Int> =

        // Action-only slots (an adapter's detached publish button) must not be counted as
        // navigation slots: they have no label and no selection capsule, so including one
        // here would shrink the per-slot width and drag the capsule off its label.
        (0 until slotCount).filterNot { it in nonSelectableIndices || isNavigationEntryDisabled(it) }



    private fun visualSlotCount(): Int = enabledNavigationIndices().size.coerceAtLeast(1)



    /**

     * Enables or disables touch on the native bottom bar and all its

     * descendants. When the glass bar is hidden we must also disable

     * touch on the native bar underneath, otherwise its tab buttons

     * still intercept touches meant for the content behind it.

     */

    private fun setNativeBarTouchable(view: View?, touchable: Boolean) {

        if (view == null) return

        if (appNavigationState?.setNativeBarTouchable(view, touchable) == true) return

        view.isClickable = touchable
        view.isFocusable = touchable

        if (view is ViewGroup) {

            for (i in 0 until view.childCount) {

                setNativeBarTouchable(view.getChildAt(i), touchable)

            }

        }

    }



    private fun visualPositionForNavigationIndex(index: Int): Float {

        val enabled = enabledNavigationIndices().let {
            if (targetAdapter?.reverseVisualSlotOrder == true) it.asReversed() else it
        }

        val position = enabled.indexOf(index).takeIf { it >= 0 } ?: 0

        return position.toFloat()

    }



    private fun navigationIndexForVisualIndex(visualIndex: Int): Int {

        val enabled = enabledNavigationIndices().let {
            if (targetAdapter?.reverseVisualSlotOrder == true) it.asReversed() else it
        }

        return enabled.getOrElse(visualIndex.coerceIn(0, enabled.lastIndex.coerceAtLeast(0))) {

            selectedIndex.coerceIn(0, (slotCount - 1).coerceAtLeast(0))

        }

    }



    private fun performNavigationSelection(index: Int) {

        if (isNavigationEntryDisabled(index)) return

        if (!indicatorGestureActive) {
            barAnimationPriorityUntil = SystemClock.uptimeMillis() + 240L
        }

        if (targetAdapter?.ownsNavigationTap == true) {
            if (appNavigationState?.performTap(this, navigationSource, index, slotCount) == true) {
                if (targetAdapter?.commitSelectionAfterTap == true) {
                    selectedIndex = index.coerceIn(0, slotCount - 1)
                    selectionInitialized = true
                }
                afterNativeNavigationClick()
                appNavigationState?.onTapSucceeded { delay ->
                    if (delay <= 0L) postInvalidateOnAnimation()
                    else postDelayed({ postInvalidateOnAnimation() }, delay)
                }
            }
            return
        }

        if (appNavigationState?.onNavigationSelectionRequested(
                index, slotCount, SystemClock.uptimeMillis(),
            ) == true
        ) {
            compositorSurfaceStaticStreak = 0
            pendingNavigationSignature = Long.MIN_VALUE
            pendingNavigationStableFrames = 0
            navigationSnapshotFrozenUntil = 0L
            postOnAnimation { requestOpticalSurfaceRootSurfaceFrame() }
        }

        // Adapters with non-View-native tabs own dispatch; the host only commits
        // the resulting selection and schedules the shared visual transition.
        if (appNavigationState?.dispatchNativeNavigationTap(this, navigationSource, index, slotCount) == true ||
            targetAdapter?.dispatchNavigationTap(navigationSource, index, slotCount) == true
        ) {
                selectedIndex = index.coerceIn(0, slotCount - 1)

                selectionInitialized = true

                postInvalidateOnAnimation()

                postDelayed({ postInvalidateOnAnimation() }, 80L)

                return

        }

        // Theme Store's floating pill is a self-drawn surface: its tab cells

        // expose no View-level children to accessibility, so the shared

        // findSlotContainer + performClick path below cannot resolve a click

        // target and the page never switches (the pill just slides). Synthesize

        // a tap at the tab centre straight onto the pill: its own hit-testing

        // (Compose pointerInput, onTouchEvent or child click) performs the

        val container = findSlotContainer() ?: return

        val item = container.getChildAtOrNull(index) ?: return

        if (item.performClick()) {

            afterNativeNavigationClick()

            return

        }

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += item to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view !== item && view.isShown && view.isClickable && view.performClick()) {

                afterNativeNavigationClick()

                return

            }

            if (view is ViewGroup && depth < 5) {

                for (childIndex in 0 until view.childCount) {

                    stack += view.getChildAt(childIndex) to (depth + 1)

                }

            }

        }

    }



    /**

     * Mi Health's bottom bar is a stock material TabLayout. Prefer the bound

     * navigationSource; when it is stale or detached mid page transition, walk

     * the window for the TabLayout class so Tab.select() always has a live

     * receiver.

     */

    private fun afterNativeNavigationClick() {


        val settleMs = targetAdapter?.selectionSettleMs ?: 0L
        if (settleMs <= 0L) return

        navigationSnapshotFrozenUntil = SystemClock.uptimeMillis() + settleMs

        postDelayed({

            navigationSnapshotSignature = Long.MIN_VALUE

            pendingNavigationSignature = Long.MIN_VALUE

            pendingNavigationStableFrames = 0

            invalidate()

        }, settleMs)

        postDelayed({ invalidate() }, settleMs + 32L)

        postDelayed({ invalidate() }, settleMs + 64L)

    }



    private fun createRuntimeShader(source: String, name: String): RuntimeShader? =

        runCatching { RuntimeShader(source) }

            .onFailure { error ->

                Log.e(DIAGNOSTIC_TAG, "shader $name creation failed", error)

                Xposed.log("[$DIAGNOSTIC_TAG] shader $name creation failed: $error")

            }

            .getOrNull()



    private fun configureGlassEffects() {



        // Douyin's compositor input is already sampled through a downscaled
        // hardware buffer. Applying the global blur radius unchanged makes
        // the video appear softer than other apps even after increasing the
        // capture resolution. Keep the global setting intact elsewhere, but
        // reduce only Douyin's second blur pass so captured detail survives.
        val blurRadius = glassBlurRadiusPx()

        val blurred = io.github.xzdw.dylt.rendering.NativeBackdropBlur.effect(
            blurRadius,
            config.glassSaturation,
        )

        sharedOpticalBlurPrepared = false

        sharedOpticalBlurEffect = if (reuseOpticalBlur) blurred else null

        if (outerLensShader == null) {

            outerLensShader = createRuntimeShader(ROUNDED_RECT_REFRACTION_SHADER, "outer-retry")

        }

        if (combinedOuterLensShader == null) {

            combinedOuterLensShader = createRuntimeShader(ROUNDED_RECT_REFRACTION_SHADER, "tabs-retry")

        }

        if (opticalSurfaceOuterLensShader == null) {

            opticalSurfaceOuterLensShader =

                createRuntimeShader(ROUNDED_RECT_REFRACTION_SHADER, "optical-surface-outer-retry")

        }

        if (indicatorLensShader == null) {

            indicatorLensShader = createRuntimeShader(ROUNDED_RECT_DISPERSION_SHADER, "indicator-retry")

        }

        val outer = outerLensShader

        val combinedOuter = combinedOuterLensShader

        val opticalSurfaceOuter = opticalSurfaceOuterLensShader

        val indicator = indicatorLensShader

        if (outer == null || combinedOuter == null || indicator == null || width <= 0 || height <= 0) return



        setOuterLensUniforms(
            outer,
            refractionHeightDp = config.refractionHeightDp,
            refractionAmountDp = -config.refractionStrengthDp,
        )

        setTabsBackdropLensUniforms(combinedOuter)

        opticalSurfaceOuter?.let {

            setOuterLensUniforms(

                it,

                // The compositor sheet uses a shallower lens than the in-tree bar
                // (10 / -26 against 24 / -24). Track the user's refraction settings but
                // keep that ratio, so a 24dp / 24dp setting reproduces the shipped
                // surface exactly.
                refractionHeightDp = config.refractionHeightDp * (10f / 24f),

                refractionAmountDp = -config.refractionStrengthDp * (26f / 24f),

            )

        }

        perfEffectRebuilds++



        val outerLensEffect = RenderEffect.createRuntimeShaderEffect(outer, "content")

        outerGlassEffect = if (reuseOpticalBlur) outerLensEffect else

            RenderEffect.createChainEffect(outerLensEffect, blurred)

        // The compositor supplies the live base. Blur the retained optical

        // sheet before displacement so old WebView details cannot appear as a

        // second readable frame, while the stronger edge displacement remains.

        opticalSurfaceOuterGlassEffect = opticalSurfaceOuter?.let {

            RenderEffect.createChainEffect(

                RenderEffect.createRuntimeShaderEffect(it, "content"),

                blurred,

            )

        }



        val tabLensEffect = RenderEffect.createRuntimeShaderEffect(combinedOuter, "content")

        tabsBackdropEffect = if (reuseOpticalBlur) tabLensEffect else

            RenderEffect.createChainEffect(tabLensEffect, blurred)

        // The indicator effect is intentionally created in drawSelection.

        // Android RenderEffect snapshots RuntimeShader uniforms at creation;

        // Rebuild this effect whenever animated

        // press/geometry parameters change.

    }



    private fun setOuterLensUniforms(

        shader: RuntimeShader,

        refractionHeightDp: Float = 24f,

        refractionAmountDp: Float = -24f,

    ) {

        val corner = outerCornerRadiusPx()

        val padding = backdropPaddingPx()

        shader.setFloatUniform("size", width.toFloat(), height.toFloat())

        shader.setFloatUniform("offset", -padding, -padding)

        shader.setFloatUniform("cornerRadii", corner, corner, corner, corner)

        shader.setFloatUniform("refractionHeight", refractionHeightDp * density)

        shader.setFloatUniform("refractionAmount", refractionAmountDp * density)

        shader.setFloatUniform("depthEffect", 0f)

    }



    private fun setTabsBackdropLensUniforms(shader: RuntimeShader) {

        val rowHeight = (height - 8f * density).coerceAtLeast(1f)

        val rowTop = (height - rowHeight) / 2f

        val rowLeft = 4f * density + navigationActionLeftReservePx()

        val rowWidth = (width - 8f * density - navigationActionLeftReservePx()).coerceAtLeast(1f)

        val corner = rowHeight / 2f

        val padding = backdropPaddingPx()

        // The hidden source tab row is 56 dp high and inset by 4 dp. Its

        // effect is recorded in our padded host-sized RenderNode, so the lens

        // origin must include both that scene padding and the row's own inset.

        // Using the host origin here left the shader shape 4 dp above/left of

        // the clipped row, exposing its lower curve inside the selected pill.

        shader.setFloatUniform("size", rowWidth, rowHeight)

        shader.setFloatUniform("offset", -(padding + rowLeft), -(padding + rowTop))

        shader.setFloatUniform("cornerRadii", corner, corner, corner, corner)

        // Follow 鎶樺皠鑼冨洿 / 鎶樺皠寮哄害 exactly like setOuterLensUniforms does. The hard-coded
        // 24 dp here meant pulling those sliders down never reached the capsule's own optics
        // pass, so the picture behind the pill stayed warped.
        shader.setFloatUniform("refractionHeight", config.refractionHeightDp * density)

        shader.setFloatUniform("refractionAmount", -config.refractionStrengthDp * density)

        shader.setFloatUniform("depthEffect", 0f)

    }



internal fun hasUsableBackdrop(): Boolean =

        appNavigationState?.requiresSampledBackdrop() != false && !compositorBlurBehind && (

            (liveBackdropActive && liveBackdropNode.hasDisplayList()) ||

                appNavigationState?.videoBackdrop()?.bitmap?.let { !it.isRecycled } == true ||

                backdrop?.let { !it.isRecycled } == true

            )



    private fun drawBackdropContent(canvas: Canvas, destination: RectF) {

        val appVideoBackdrop = appNavigationState?.videoBackdrop()
        if (appVideoBackdrop != null) {

            // Douyin: the glass blurs the real video frame sampled from the
            // feed's SurfaceView (backdrop, painted first as the background
            // layer) plus the overlay controls (live RenderNode scene on top).
            // Without the Surface sample the blur input would only contain the
            // controls, which is the "plain transparency over video" artifact.
            appVideoBackdrop.bitmap.takeUnless(Bitmap::isRecycled).takeUnless { appNavigationState?.blocksBackdropSampling == true }?.let { bmp ->
                if (appVideoBackdrop.surfaceWidth > 0 && appVideoBackdrop.surfaceHeight > 0) {
                    // Map the captured surface pixels back onto this host's
                    // window rect using the surface geometry recorded at
                    // capture time, so the glass refracts exactly the video
                    // pixels behind it even when the surface is not full-bleed.
                    val hostLoc = IntArray(2).also(::getLocationInWindow)
                    val padX = ((destination.width() - width) / 2f).roundToInt()
                    val padY = ((destination.height() - height) / 2f).roundToInt()
                    hostLoc[0] -= padX
                    hostLoc[1] -= padY
                    val hostRight = hostLoc[0] + destination.width().roundToInt()
                    val hostBottom = hostLoc[1] + destination.height().roundToInt()
                    val sLeft = appVideoBackdrop.surfaceLeft
                    val sTop = appVideoBackdrop.surfaceTop
                    val sRight = sLeft + appVideoBackdrop.surfaceWidth
                    val sBottom = sTop + appVideoBackdrop.surfaceHeight
                    val cropLeft = maxOf(hostLoc[0], sLeft)
                    val cropTop = maxOf(hostLoc[1], sTop)
                    val cropRight = minOf(hostRight, sRight)
                    val cropBottom = minOf(hostBottom, sBottom)
                    if (cropRight > cropLeft && cropBottom > cropTop) {
                        val sx = bmp.width.toFloat() / appVideoBackdrop.surfaceWidth
                        val sy = bmp.height.toFloat() / appVideoBackdrop.surfaceHeight
                        val src = Rect(
                            ((cropLeft - sLeft) * sx).roundToInt(),
                            ((cropTop - sTop) * sy).roundToInt(),
                            ((cropRight - sLeft) * sx).roundToInt(),
                            ((cropBottom - sTop) * sy).roundToInt(),
                        )
                        val dstLeft = (cropLeft - hostLoc[0]) +
                            destination.left
                        val dstTop = (cropTop - hostLoc[1]) +
                            destination.top
                        val dstRight = (cropRight - hostLoc[0]) +
                            destination.left
                        val dstBottom = (cropBottom - hostLoc[1]) +
                            destination.top
                        if (src.width() > 0 && src.height() > 0) {
                            canvas.drawBitmap(
                                bmp, src,
                                RectF(dstLeft, dstTop, dstRight, dstBottom),
                                indicatorPaint,
                            )
                        }
                    } else {
                        // No overlap: leave the ordinary View backdrop visible.
                    }
                } else {
                    canvas.drawBitmap(bmp, null, destination, indicatorPaint)
                }
            }

            if (canvas.isHardwareAccelerated && liveBackdropActive &&

                liveBackdropNode.hasDisplayList()

            ) {

                // SurfaceView records a CLEAR hole for its separate video surface.
                // Isolate that operation so it cannot erase the sampled video below.
                val overlaySave = canvas.saveLayer(destination, null)
                canvas.drawRenderNode(liveBackdropNode)
                canvas.restoreToCount(overlaySave)

            }

            return

        }

        val compositorBackdropReady = targetAdapter?.usesDirectOpticalBackdropMode == true &&

            opticalSurfaceSurfacePipelineActive && backdrop?.let { !it.isRecycled } == true

        if (!compositorBackdropReady && canvas.isHardwareAccelerated &&

            liveBackdropActive && liveBackdropNode.hasDisplayList()

        ) {

            canvas.drawRenderNode(liveBackdropNode)

            return

        }

        backdrop?.takeUnless(Bitmap::isRecycled)?.let {
            if (appNavigationState?.drawSampledBackdrop(canvas, it, destination, indicatorPaint) != true) {
                canvas.drawBitmap(it, null, destination, indicatorPaint)
            }
        }

    }



    /**

     * Records only the content directly behind this 64 dp bar into a small GPU

     * scene. The scene references QQ's existing RenderNodes, so their pixels

     * stay current without synchronously redrawing QQ into a software bitmap.

     */

    private fun prepareLiveBackdropScene(): Boolean {
        if (!config.backdropCapture) {
            liveBackdropActive = false
            sharedOpticalBlurPrepared = false
            return false
        }
        if (appNavigationState?.requiresSampledBackdrop() == false) return false

        if (appNavigationState?.skipLiveBackdrop(selectedIndex) == true) {

            // Browse and full-screen video contain independently composed

            // surfaces which a parent RenderNode cannot sample reliably.

            liveBackdropActive = false

            return false

        }

        if (appNavigationState?.canReuseLiveBackdrop(selectedIndex) == true &&
            liveBackdropNode.hasDisplayList()
        ) {

            // The wrapper keeps live references to JD's existing child

            // RenderNodes. Their pixels update with the app's normal traversal;

            // rebuilding the wrapper or walking 1,000+ views every frame is

            // unnecessary and was the main-thread cost of earlier attempts.

            liveBackdropActive = true

            return true

        }

        // Adapter-owned independent Surfaces use the dedicated capture path.

        if (appNavigationState?.needsSurfaceBackdrop() == true) {

            liveBackdropActive = false

            return false

        }

        if (appNavigationState?.retainBackdropDuringTransition(SystemClock.uptimeMillis()) == true &&

            liveBackdropNode.hasDisplayList()

        ) {

            // The retained node already references the last complete page

            // scene. Do not walk siblings or request display-list updates while

            // an adapter is constructing the destination page.

            liveBackdropActive = true

            return true

        }

        // Some compositor-backed pages cannot safely use live RenderNode sampling.

        // In practice, capturing the parent MainFrameContainerView's RenderNode

        // (which includes the WebView) works fine and gives real-time glass.

        if (targetAdapter?.usesOpticalSurfacePipeline == true && !isOpticalSurfaceSelected()) {

            opticalSurfaceSnapshotReady = false

            opticalSurfaceTextureView = null

            if (opticalSurfaceBackdropPadding != 0f) {

                opticalSurfaceBackdropPadding = 0f

                configureGlassEffects()

            }

        }

        // Use the snapshot-based hybrid backdrop for the selected optical surface.

        if (isOpticalSurfaceSelected()) {

            liveBackdropActive = false

            return false

        }

        // Same class of problem as other compositor-backed pages: Takeout's promotion tabs

        // (绁炲埜/娲诲姩) are WebView-composed pages. Referencing the pager's

        // RenderNode from the glass blur chain makes the async WebView

        // texture submit twice per frame, so those pages flicker nonstop

        // while the native home tab is unaffected. Keep the live scene only

        // on the home tab; every other tab falls back to the periodic

        // software-snapshot backdrop, which never re-submits page layers.


        if (!isHardwareAccelerated || width <= 0 || height <= 0) return false

        // While the finger is down the gesture only moves the capsule: nothing in the scene's
        // structure changes, and the retained node holds live references to the app's own
        // RenderNodes, so the playing video is still sampled without walking and re-recording the
        // tree on every drag frame (which is what the sweep below would otherwise do at the
        // panel's full rate). The look is unchanged; the GPU work is not.
        if (touching && liveBackdropNode.hasDisplayList()) {

            liveBackdropActive = true

            return true

        }

        if (appNavigationState?.retainHomeBackdrop(selectedIndex) == true && liveBackdropNode.hasDisplayList()

        ) {

            // The wrapper retains references to the app's child RenderNodes; their

            // pixels keep updating with the feed. Re-walking and forcing every

            // dirty child display list on each 120 Hz frame is unnecessary and

            // was the source of homepage-only scrolling jank.

            liveBackdropActive = true

            return true

        }

        val adapterScene = appNavigationState?.resolveBackdropScene(this)
        val content = (adapterScene?.parent ?: parent) as? ViewGroup ?: return false
        val hostIndex = if (adapterScene != null) content.childCount else

            content.indexOfChild(this).takeIf { it >= 0 } ?: return false

        // The sweep below walks the scene and re-records its display list. While the capsule is
        // dragged the window is traversed far more often than the host is drawn -- GlassPerf
        // measured sweeps at 20-35x fps with a re-record on every one of them -- and that
        // repeated recording starved the video compositor until playback stuttered. A second
        // record inside one frame cannot show anything new, so the extra passes were pure cost.
        val sweepNow = SystemClock.uptimeMillis()
        if (sweepNow - lastSceneSweepAt < SCENE_SWEEP_MIN_INTERVAL_MS) return liveBackdropActive
        lastSceneSweepAt = sweepNow

        perfSceneSweeps++

        perfSceneSiblingVisits += hostIndex

        // A target app can re-enter drawing while updating its display list.

        // Such a nested sweep must not mutate the outer sweep's scratch storage.

        if (sceneSweepInProgress) return liveBackdropActive

        sceneSweepInProgress = true

        val nodes = sceneNodesScratch

        val views = sceneViewsScratch

        try {

        val safeSceneViews = appNavigationState?.backdropSceneViews(content, hostIndex)
        for (index in 0 until (safeSceneViews?.size ?: hostIndex)) {
            val child = safeSceneViews?.get(index) ?: content.getChildAt(index)


            if (adapterScene != null && child !== adapterScene) continue

            if (child === opticalSurfaceSurfaceView) continue

            if (child.visibility != View.VISIBLE || child.alpha <= 0.001f || child.width <= 0 || child.height <= 0) continue

            val node = viewRenderNode(child) ?: continue

            if (!node.hasDisplayList()) continue

            views += child

            nodes += node

        }

        if (nodes.isEmpty()) {

            if (safeSceneViews != null) {
                clearLiveBackdropForAdapter()
                return false
            }

            liveBackdropActive = liveBackdropNode.hasDisplayList()

            return liveBackdropActive

        }



        val padding = backdropPaddingPx().roundToInt()

        val expandedWidth = width + padding * 2

        val expandedHeight = height + padding * 2

        val contentLocation = sceneContentLocation.also(content::getLocationInWindow)

        val hostLocation = sceneHostLocation.also(::getLocationInWindow)

        val rawOffsetX = contentLocation[0] - hostLocation[0] + padding

        val rawOffsetY = contentLocation[1] - hostLocation[1] + padding

        // Window/layout rounding can alternate the relative origin by one

        // physical pixel while neither the bar nor its content actually moves.

        // Re-recording the blurred layer at both origins reads as shimmer.

        // Keep a one-pixel deadband; genuine layout changes still update at once.

        if (liveBackdropStableOffsetX == Int.MIN_VALUE ||

            abs(rawOffsetX - liveBackdropStableOffsetX) > 1

        ) liveBackdropStableOffsetX = rawOffsetX

        if (liveBackdropStableOffsetY == Int.MIN_VALUE ||

            abs(rawOffsetY - liveBackdropStableOffsetY) > 1

        ) liveBackdropStableOffsetY = rawOffsetY

        val offsetX = liveBackdropStableOffsetX

        val offsetY = liveBackdropStableOffsetY

        var signature = 17L

        val adapterSceneBase = adapterScene?.let {
            appNavigationState?.backdropSceneBaseColor(it, surfaceContainerColor(isDarkGlass()))
        } ?: Color.TRANSPARENT
        signature = signature * 31L + adapterSceneBase

        signature = signature * 31L + expandedWidth

        signature = signature * 31L + expandedHeight

        signature = signature * 31L + offsetX

        signature = signature * 31L + offsetY

        for (index in nodes.indices) {

            val view = views[index]

            signature = signature * 31L + System.identityHashCode(nodes[index])

            signature = signature * 31L + view.width

            signature = signature * 31L + view.height

            if (safeSceneViews != null) {
                val location = IntArray(2).also(view::getLocationInWindow)
                signature = signature * 31L + location[0]
                signature = signature * 31L + location[1]
            }

        }




        // Douyin's animated image/feed cards can legitimately change their
        // RenderNode content every frame while keeping the same node identity.
        // Waiting for consecutive identical scene signatures therefore never
        // commits a new live backdrop for those cards (video is handled by the
        // separate Surface capture path). Keep the stability guard for every
        // other app, but let Douyin's dynamic scene submit immediately.
        // During visibility animations, skip the stable-frame delay so the
        // backdrop updates every frame and the refraction stays live as
        // the panel scales/translates. Without this, the backdrop freezes
        // on the first animation frame and the exit looks like a static
        // image snapping away.
        val skipStableDelay = targetAdapter?.skipBackdropStableDelay == true



        val hasRetainedScene = liveBackdropNode.hasDisplayList()

        if (!skipStableDelay && signature != liveBackdropSignature && hasRetainedScene) {

            if (signature == pendingBackdropSignature) {

                pendingBackdropStableFrames++

            } else {

                pendingBackdropSignature = signature

                pendingBackdropStableFrames = 1

            }

            if (pendingBackdropStableFrames < BACKDROP_STABLE_FRAMES) {

                liveBackdropActive = true

                return true

            }

        }



        // Weibo video textures can submit new compositor frames without

        // changing any View/RenderNode identity or geometry. Merely invalidating

        // this host redraws the old effected input on some HyperOS builds. Mark

        // the lightweight wrapper display list fresh every visible frame so the

        // blur/refraction chain samples the current texture; keep the expensive

        // effect objects intact while the scene signature itself is unchanged.

        val forceCompositorFrame =

            (targetAdapter?.forceCompositorFrames == true ||
                appNavigationState?.forceLiveBackdropFrame(selectedIndex) == true) &&

                isShown && alpha > 0.001f

            val sceneChanged = signature != liveBackdropSignature || !hasRetainedScene

        if (sceneChanged || forceCompositorFrame) {

            perfSceneReRecords++

            val recording = liveBackdropNode.beginRecording(expandedWidth, expandedHeight)

            if (adapterScene != null) recording.drawColor(adapterSceneBase)

            recording.clipRect(0f, 0f, expandedWidth.toFloat(), expandedHeight.toFloat())

            recording.translate(offsetX.toFloat(), offsetY.toFloat())

            for (index in nodes.indices) {
                if (safeSceneViews == null) {
                    recording.drawRenderNode(nodes[index])
                } else {
                    // Descendant RenderNodes include their own View transform,
                    // but need the intervening ancestor transforms in this scene.
                    recording.save()
                    val ancestors = ArrayList<View>()
                    var ancestor = views[index].parent as? View
                    while (ancestor != null && ancestor !== content) {
                        ancestors += ancestor
                        ancestor = ancestor.parent as? View
                    }
                    recording.translate(-content.scrollX.toFloat(), -content.scrollY.toFloat())
                    for (parentView in ancestors.asReversed()) {
                        recording.translate(parentView.left.toFloat(), parentView.top.toFloat())
                        recording.concat(parentView.matrix)
                        if (parentView is ViewGroup && parentView.clipChildren) {
                            if (parentView.clipToPadding) {
                                recording.clipRect(parentView.paddingLeft, parentView.paddingTop,
                                    parentView.width - parentView.paddingRight,
                                    parentView.height - parentView.paddingBottom)
                            } else recording.clipRect(0, 0, parentView.width, parentView.height)
                        }
                        recording.translate(-parentView.scrollX.toFloat(), -parentView.scrollY.toFloat())
                    }
                    recording.drawRenderNode(nodes[index])
                    recording.restore()
                }
            }

            liveBackdropNode.endRecording()

            liveBackdropNode.setPosition(0, 0, expandedWidth, expandedHeight)

            liveBackdropSignature = signature

            appNavigationState?.onLiveBackdropRecorded(selectedIndex)

            // RenderEffect objects built before a replacement Activity joined

            // the hardware tree can remain valid Kotlin objects while drawing

            // only their transparent surface. Recreate the chain against the

            // newly committed Backdrop scene.

            // Effects depend on configuration and geometry, not on the current
            // feed's node identity. Keep the GPU chain across scene replacements;
            // configuration/layout updates already rebuild it explicitly.
            if (outerGlassEffect == null) configureGlassEffects()

        }

        pendingBackdropSignature = Long.MIN_VALUE

        pendingBackdropStableFrames = 0

        if (!liveBackdropLogged) {

            liveBackdropLogged = true

            val sourceDescription = views.joinToString("|") { view ->

                val location = IntArray(2).also(view::getLocationInWindow)

                "${view.javaClass.name}@${location[0]},${location[1]} frame=${view.left},${view.top},${view.width}x${view.height}"

            }

            Xposed.log(

                "[OfflineGlass][LiveBackdrop] host=${hostLocation[0]},${hostLocation[1]} ${width}x$height " +

                    "content=${contentLocation[0]},${contentLocation[1]} ${content.width}x${content.height} " +

                    "offset=$offsetX,$offsetY pad=$padding sources=$sourceDescription",

            )

        }

        liveBackdropActive = true

        // Douyin owns the video bitmap through its low-cadence PixelCopy

        // pipeline; it is composited under the live scene in drawBackdropContent,

        // so it must survive the scene commit.

        backdrop?.recycle()
        backdrop = null

        return true

        } finally {

            nodes.clear()

            views.clear()

            sceneSweepInProgress = false

        }

    }



    private val retainedRenderNodeField by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }
        }.getOrNull()
    }

    private fun viewRenderNode(view: View): RenderNode? {

        if (targetAdapter?.allowRenderNodeRefresh != true) {

            // JD's 鏂板搧 feed is drawn by an obfuscated custom renderer

            // (currently d6.core.c40). updateDisplayListIfDirty() is not a

            // passive getter for that renderer: invoking it again from our

            // pre-draw backdrop pass makes its video-preview/card display list

            // alternate between populated and empty. The actively playing

            // video remains stable because it is composed separately.

            //

            // Reuse the RenderNode already produced by Android's normal draw

            // traversal without recording a second display list for generic apps.

            val retained = runCatching {
                retainedRenderNodeField?.get(view) as? RenderNode
            }.getOrNull()
            if (retained?.hasDisplayList() == true || targetAdapter?.retainsExistingBackdropRenderNode == true) {
                return retained
            }

        }

        // Resolved once: the sweep below calls this per sibling per display frame, and
        // Xposed.callMethod rebuilt a string cache key on every one of those calls.
        val method = updateDisplayListMethod ?: return null

        return try {

            Xposed.callResolved(view, method) as? RenderNode

        } catch (t: Throwable) {

            null

        }
    }



    private var outerRecordedWidth = 0
    private var outerRecordedHeight = 0

internal fun drawOuterGlass(canvas: Canvas) {

        if (!canvas.isHardwareAccelerated || outerGlassEffect == null) {

            backdrop?.takeUnless(Bitmap::isRecycled)?.let {

                canvas.drawBitmap(it, null, outerRect, glassPaint)

            }

            return

        }

        val rendered = runCatching {

            val padding = backdropPaddingPx().roundToInt()

            val expandedWidth = width + padding * 2

            val expandedHeight = height + padding * 2

            expandedBackdropRect.set(0f, 0f, expandedWidth.toFloat(), expandedHeight.toFloat())

            outerRenderNode.setPosition(-padding, -padding, width + padding, height + padding)

            // A recorded drawRenderNode keeps a live reference to the source

            // RenderNode. Re-recording this wrapper every frame is redundant

            // and can alternate blur texture allocation/phase. Cache it for

            // both the live scene and JD's compositor-backed bitmap path.

            val reusable = liveBackdropActive ||
                appNavigationState?.reuseOuterBackdropForSurface(opticalSurfaceSurfacePipelineActive) == true
            val stateNeedsRecord = appNavigationState?.outerBackdropNeedsRecord(
                backdrop, expandedWidth, expandedHeight, outerRenderNode.hasDisplayList(),
            )
            val needsRecord = appNavigationState?.forceLiveBackdropFrame(selectedIndex) == true ||
                outerRecordedWidth != expandedWidth || outerRecordedHeight != expandedHeight ||
                (stateNeedsRecord ?: (!reusable || !outerRenderNode.hasDisplayList()))

            if (needsRecord) {

                perfNodeRecords++

                val recordingCanvas = outerRenderNode.beginRecording(expandedWidth, expandedHeight)

                drawSharedOpticalBackdrop(recordingCanvas, expandedBackdropRect)

                outerRenderNode.endRecording()

                outerRecordedWidth = expandedWidth
                outerRecordedHeight = expandedHeight

                if (reusable) appNavigationState?.onOuterBackdropRecorded(backdrop, expandedWidth, expandedHeight)

            }

            val hybridBackdrop = usesOpticalSurfaceHybridBackdrop()

            outerRenderNode.setRenderEffect(

                if (hybridBackdrop) opticalSurfaceOuterGlassEffect else outerGlassEffect,

            )

            outerRenderNode.alpha = if (hybridBackdrop) 0.34f else 1f

            canvas.drawRenderNode(outerRenderNode)

            val appVideoEpoch = appNavigationState?.videoBackdrop()?.epoch ?: -1
            if (appVideoEpoch >= 0 && appVideoEpoch != lastAppVideoBackdropEpoch) {
                lastAppVideoBackdropEpoch = appVideoEpoch
                if (appVideoEpoch % 15 == 0) {
                }
            }

        }.isSuccess

        if (!rendered) {

            backdrop?.takeUnless(Bitmap::isRecycled)?.let {

                canvas.drawBitmap(it, null, outerRect, glassPaint)

            }

        }

    }



    /** Slider variant that honors an adapter-owned chrome fade as a layer alpha. */

    /** Dark-mode companion for an adapter-owned indicator tint (same hue, lower value). */
    private fun darkenIndicatorTint(color: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * 0.45f).coerceIn(0f, 1f)
        hsv[1] = (hsv[1] * 0.75f).coerceIn(0f, 1f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun drawSelectionWithFade(canvas: Canvas, dark: Boolean) {

        val fade = appNavigationState?.hostChromeAlpha(this, selectedIndex) ?: 1f

        if (fade >= 1f) {

            drawSelection(canvas, dark)

            return

        }

        val saved = canvas.saveLayerAlpha(

            0f,

            0f,

            width.toFloat(),

            height.toFloat(),

            (fade * 255).roundToInt().coerceIn(0, 255),

        )

        drawSelection(canvas, dark)

        if (saved >= 0) canvas.restoreToCount(saved)

    }



    private fun drawSelection(canvas: Canvas, dark: Boolean) {

        selectionPathValid = false

        // Selection bars (file manager action mode) render the liquid glass

        // panel without any slider: every slot is a plain tap target.

        if (!sliderEnabled) return

        if (slotCount <= 0 || width <= 0) return

        val slotWidth = tabWidthPx()

        val horizontalPadding = navigationSlotBandLeftPx()

        val centerX = horizontalPadding + (indicatorPosition + 0.5f) * slotWidth

        val navCenterY: Float

        val navHeight: Float

        val adapterNavigationGeometry = appNavigationState?.navigationBarGeometry()
        if (adapterNavigationGeometry != null) {
            navCenterY = adapterNavigationGeometry.centerY()
            navHeight = adapterNavigationGeometry.height()

        } else {

            navCenterY = height / 2f

            navHeight = height.toFloat()

        }

        val centerY = navCenterY

        val baseHalfWidth = slotWidth.coerceAtLeast(1f) / 2f

        val baseHalfHeight = (navHeight - 8f * density).coerceAtLeast(1f) / 2f

        val velocity = indicatorVelocity / 10f

        val scaleX = indicatorScaleX / (1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f))

        val scaleY = indicatorScaleY * (1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f))

        val visualLeft = centerX - baseHalfWidth * scaleX

        val visualTop = centerY - baseHalfHeight * scaleY

        val visualRight = centerX + baseHalfWidth * scaleX

        val visualBottom = centerY + baseHalfHeight * scaleY



        // The backdrop is evaluated on the fixed 56 dp indicator and

        // DampedDragAnimation scales that completed layer afterwards. Growing

        // the sampling rect itself made the 78 dp pressed indicator read past

        // the 56 dp combined tab layer, exposing its lower edge as an internal

        // crescent. Keep all optical work at the base geometry, then transform

        // the finished result as one continuous layer.

        selectionRect.set(

            centerX - baseHalfWidth,

            centerY - baseHalfHeight,

            centerX + baseHalfWidth,

            centerY + baseHalfHeight,

        )

        val radius = selectionRect.height() * (config.cornerRadiusPercent / 100f)

        // The adapter's selection ink is revealed through the capsule's *drawn* shape, so grow the
        // base rect by the same press scale the canvas below applies (and scale the corner radius
        // with it). Clipping to the unscaled rect left the fill inset from the pill's edge.
        selectionClipRect.set(selectionRect)
        selectionClipRect.inset(
            -(selectionClipRect.width() * (scaleX - 1f)) / 2f,
            -(selectionClipRect.height() * (scaleY - 1f)) / 2f,
        )
        selectionClipPath.setBottomBarSquircle(
            selectionClipRect,
            radius * ((scaleX + scaleY) / 2f),
            config.cornerSmoothing,
        )
        selectionPathValid = true

        syncOpticalSurfaceNativeIndicator(

            centerX,

            centerY,

            baseHalfWidth,

            baseHalfHeight,

            scaleX,

            scaleY,

            radius,

        )

        val transformedLayer = canvas.save()

        canvas.scale(scaleX, scaleY, centerX, centerY)



        val runtime = indicatorLensShader

        if (hasUsableBackdrop() && canvas.isHardwareAccelerated &&

            (!usesOpticalSurfaceHybridBackdrop() || targetAdapter?.usesDirectOpticalBackdropMode == true)

        ) {

            runCatching {

                val scenePadding = backdropPaddingPx()

                val scenePaddingInt = scenePadding.roundToInt()

                val sceneWidth = width + scenePaddingInt * 2

                val sceneHeight = height + scenePaddingInt * 2

                expandedBackdropRect.set(0f, 0f, sceneWidth.toFloat(), sceneHeight.toFloat())



                // Build the combined backdrop in one stable, panel-local

                // coordinate space first (raw screen + hidden accent tab row).

                indicatorCombinedNode.setPosition(0, 0, sceneWidth, sceneHeight)

                val reusableScene = appNavigationState?.reuseCombinedIndicatorScene(
                    opticalSurfaceSurfacePipelineActive,
                ) == true
                val stateNeedsRecord = appNavigationState?.combinedIndicatorSceneNeedsRecord(
                    backdrop, navigationSnapshot, navigationSnapshotSignature, sceneWidth, sceneHeight,
                    dark, indicatorCombinedNode.hasDisplayList(),
                )
                val inkInScene = selectionInkInLensScene()
                val combinedNeedsRecord = if (!reusableScene) true
                    else (stateNeedsRecord ?: !indicatorCombinedNode.hasDisplayList()) ||
                        inkInScene != lastSelectionInkInScene

                if (combinedNeedsRecord) {

                    perfNodeRecords++

                    val combinedCanvas = indicatorCombinedNode.beginRecording(sceneWidth, sceneHeight)

                    drawBackdropContent(combinedCanvas, expandedBackdropRect)

        // The promotion page already keeps a live, native-blurred navigation row

        // behind this host. Repainting the hidden source tabs into the selection

        // lens duplicates the static foreground icons (especially while dragging).

        val showHiddenTabs = appNavigationState?.drawHiddenTabsInIndicatorScene(
            usesOpticalSurfaceHybridBackdrop(),
        ) ?: !usesOpticalSurfaceHybridBackdrop()
        if (showHiddenTabs) {

            drawHiddenTabsBackdrop(

                combinedCanvas,

                dark,

                scenePadding,

                sceneWidth,

                sceneHeight,

                // Douyin's bottom row is painted above the lens by the renderer. Recording it
                // into the lens as well put a second, pill-scaled copy under the foreground one:
                // while the content scale animated, the two copies diverged (ghosts) and the
                // scene swap made the zoom look stepped. The lens keeps the backdrop + mask;
                // the selected ink stays in the foreground only, like the Compose reference.
                includeNavigation = if (!topBar && context.packageName == "com.ss.android.ugc.aweme") {
                    false
                } else {
                    appNavigationState?.includeNavigationInIndicatorScene(
                        opticalSurfaceSurfacePipelineActive,
                    ) ?: !opticalSurfaceSurfacePipelineActive
                },

            )

        }

                    // The selection ink belongs to this lens scene, not to the foreground row: the
                    // capsule then bends it and splits it into its spectrum like the backdrop behind
                    // it (KernelSU's floating bar records its accent copy the same way). Painted over
                    // the glass instead it covered the refraction up and looked flat.
                    val inkSaved = combinedCanvas.save()
                    combinedCanvas.translate(scenePadding, scenePadding)
                    lastSelectionInkInScene = inkInScene
                    appNavigationState?.drawSelectionInk(
                        combinedCanvas, adapterNavigationFrame(1f), inLensScene = true,
                    )
                    combinedCanvas.restoreToCount(inkSaved)

                    indicatorCombinedNode.endRecording()

                    if (reusableScene) {
                        appNavigationState?.onCombinedIndicatorSceneRecorded(
                            backdrop, navigationSnapshot, navigationSnapshotSignature, sceneWidth, sceneHeight, dark,
                        )
                    }

                }



                // Apply the lens to a pill-sized layer so the transformed

                // drawBackdrop modifier. Applying it to the whole bar made the

                // RuntimeShader inherit the source RenderNode's global coords,

                // so the pill sampled distant avatars as an opaque flat colour.

                val nodeLeft = floor(selectionRect.left - scenePadding).toInt()

                val nodeTop = floor(selectionRect.top - scenePadding).toInt()

                val nodeRight = ceil(selectionRect.right + scenePadding).toInt()

                val nodeBottom = ceil(selectionRect.bottom + scenePadding).toInt()

                val nodeWidth = (nodeRight - nodeLeft).coerceAtLeast(1)

                val nodeHeight = (nodeBottom - nodeTop).coerceAtLeast(1)

                val localPaddingX = selectionRect.left - nodeLeft

                val localPaddingY = selectionRect.top - nodeTop



                indicatorRenderNode.setPosition(nodeLeft, nodeTop, nodeRight, nodeBottom)

                // This scene records nothing but a *reference* to indicatorCombinedNode under a
                // pure transform (translate + inverse press scale). Its content therefore only
                // needs re-recording when that transform moves —i.e. while the indicator is
                // being dragged or pressed —not once per display frame. At rest the signature
                // is constant and the recording is reused.
                var indicatorTransformKey = 17L

                indicatorTransformKey = indicatorTransformKey * 31L + nodeLeft

                indicatorTransformKey = indicatorTransformKey * 31L + nodeTop

                indicatorTransformKey = indicatorTransformKey * 31L + nodeWidth

                indicatorTransformKey = indicatorTransformKey * 31L + nodeHeight

                indicatorTransformKey = indicatorTransformKey * 31L + centerX.toRawBits()

                indicatorTransformKey = indicatorTransformKey * 31L + centerY.toRawBits()

                indicatorTransformKey = indicatorTransformKey * 31L + scaleX.toRawBits()

                indicatorTransformKey = indicatorTransformKey * 31L + scaleY.toRawBits()

                indicatorTransformKey = indicatorTransformKey * 31L + scenePaddingInt

                if (indicatorTransformKey != lastIndicatorTransformKey ||
                    !indicatorRenderNode.hasDisplayList()
                ) {

                lastIndicatorTransformKey = indicatorTransformKey

                perfNodeRecords++

                val recordingCanvas = indicatorRenderNode.beginRecording(nodeWidth, nodeHeight)

                // miuix-blur's LayerBackdrop applies the inverse of

                // drawBackdrop.layerBlock before capturing each backdrop.

                // This keeps the sampled scene registered to the screen while

                // the completed 56 dp indicator layer is scaled by the damped

                // drag animation. Without this inverse transform the captured

                // pixels scale with the pill and the refraction loses the

                // stable through-glass appearance used by the bar.

                val localCenterX = centerX - nodeLeft

                val localCenterY = centerY - nodeTop

                recordingCanvas.translate(localCenterX, localCenterY)

                recordingCanvas.scale(1f / scaleX, 1f / scaleY)

                recordingCanvas.translate(

                    -(centerX + scenePaddingInt),

                    -(centerY + scenePaddingInt),

                )

                recordingCanvas.drawRenderNode(indicatorCombinedNode)

                indicatorRenderNode.endRecording()

                }

                val hybridBackdrop = usesOpticalSurfaceHybridBackdrop()

                // Outside the hybrid path the reference lens grows only while

                // pressed. The promotion path no longer paints a hidden tab row

                // (that row caused the duplicated icons), so retain a modest

                // lens at rest to preserve liquid refraction and dispersion.

                val lensProgress = if (hybridBackdrop) {

                    pressProgress.coerceAtLeast(0.72f)

                } else if (targetAdapter?.restingOpticalMix != null) {

                    // Douyin's feed is an independent compositor surface. Keep
                    // the optical displacement active at rest; tying it only
                    // to pressProgress turns the bar into plain translucency
                    // between touches even when a fresh video frame is present.
                    targetAdapter?.restingOpticalMix ?: 0f

                } else {

                    pressProgress

                }

                if (runtime != null && lensProgress > 0.001f) {

                    runtime.setFloatUniform("size", selectionRect.width(), selectionRect.height())

                    runtime.setFloatUniform("offset", -localPaddingX, -localPaddingY)

                    runtime.setFloatUniform("cornerRadii", radius, radius, radius, radius)

                    // The manager's capsule lens (LiquidBottomTabs) is the same ASGS shader
                    // this host uses. Its long press runs lens(10dp, 14dp, dispersion = 1.0);
                    // the bar's resting optical mix is deliberately weaker and only had a
                    // fixed 0.5 aberration. Ramp both toward the manager's values with the
                    // press so the inner rainbow exists only while the finger is down and
                    // the resting bar keeps its current, quieter look.
                    val pressed = pressProgress.coerceIn(0f, 1f)

                    val refractionHeight = if (hybridBackdrop) {

                        config.refractionHeightDp *

                            lerp(
                                INDICATOR_LENS_HYBRID_HEIGHT_MIN_RATIO,
                                INDICATOR_LENS_HYBRID_HEIGHT_MAX_RATIO,
                                pressProgress,
                            ) * density

                    } else {

                        val restingHeight = config.refractionHeightDp *
                            INDICATOR_LENS_HEIGHT_RATIO * lensProgress
                        val pressedHeight = 8f * (config.refractionHeightDp / 15f)

                        lerp(restingHeight, pressedHeight, pressed) * density

                    }

                    val refractionAmount = if (hybridBackdrop) {

                        -config.refractionStrengthDp *

                            lerp(
                                INDICATOR_LENS_HYBRID_AMOUNT_MIN_RATIO,
                                INDICATOR_LENS_HYBRID_AMOUNT_MAX_RATIO,
                                pressProgress,
                            ) * density

                    } else {

                        val restingAmount = config.refractionStrengthDp *
                            INDICATOR_LENS_AMOUNT_RATIO * lensProgress
                        val pressedAmount = 11f * (config.refractionStrengthDp / 15f)

                        -lerp(restingAmount, pressedAmount, pressed) * density

                    }

                    runtime.setFloatUniform("refractionHeight", refractionHeight)

                    runtime.setFloatUniform("refractionAmount", refractionAmount)

                    runtime.setFloatUniform("depthEffect", 1f)

                    runtime.setFloatUniform("chromaticAberration", pressed * 1.3f)

                    val lensEffect = RenderEffect.createRuntimeShaderEffect(runtime, "content")

                    indicatorRenderNode.setRenderEffect(

                        if (hybridBackdrop) {

                            RenderEffect.createChainEffect(

                                lensEffect,

                                io.github.xzdw.dylt.rendering.NativeBackdropBlur.effect(
                                    glassBlurRadiusPx(),
                                    config.glassSaturation,
                                ),

                            )

                        } else {

                            lensEffect

                        },

                    )

                } else {

                    // Keep drawing the combined backdrop at rest; only the lens is

                    // omitted when its animated dimensions reach zero.

                    indicatorRenderNode.setRenderEffect(null)

                }

                indicatorRenderNode.alpha = if (hybridBackdrop) 0.34f else 1f

                selectionPath.setBottomBarSquircle(selectionRect, radius, config.cornerSmoothing)

                val composed = appNavigationState?.drawSelectionBackdrop(
                    canvas, selectionPath, selectedIndex,
                ) { canvas.drawRenderNode(indicatorRenderNode) } == true
                if (!composed) {
                    canvas.save()
                    canvas.clipPath(selectionPath)
                    canvas.drawRenderNode(indicatorRenderNode)
                    canvas.restore()
                }

            }

        }



        val idleAlpha = 0.10f * (1f - pressProgress)

        val pressedAlpha = 0.03f * pressProgress

        val accent = if (config.customAccentEnabled) config.customAccentColor else Color.WHITE

        // Douyin's flat capsule indicator: an adapter-owned tint replaces the shared
        // translucent white/black wash. Light mode uses the warm taupe measured from
        // the reference mock-up; an adapter may also name its dark-mode colour, because
        // deriving one by darkening the light tint usually reads as flat grey.
        val adapterTint = if (dark) {
            targetAdapter?.indicatorTintDark ?: targetAdapter?.indicatorTint?.let { pair ->
                darkenIndicatorTint(pair.first) to pair.second
            }
        } else {
            targetAdapter?.indicatorTint
        }
        fillPaint.color = if (adapterTint != null) {
            Color.argb(
                (adapterTint.second.coerceIn(0f, 1f) * 255f).roundToInt(),
                Color.red(adapterTint.first),
                Color.green(adapterTint.first),
                Color.blue(adapterTint.first),
            )
        } else when {

            config.selectedAccent -> Color.argb(

                (idleAlpha * 255f).toInt(),

                Color.red(accent),

                Color.green(accent),

                Color.blue(accent),

            )

            dark -> Color.argb((idleAlpha * 255f).toInt(), 255, 255, 255)

            else -> Color.argb((idleAlpha * 255f).toInt(), 0, 0, 0)

        }

        // The path built at ~8808 is still valid: nothing between then and now
        // writes to it (drawSelectionBackdrop is not overridden and only clips).
        // Rebuilding it here allocated a RoundedPolygon for identical geometry.

        canvas.drawPath(selectionPath, fillPaint)

        if (pressedAlpha > 0f) {

            fillPaint.color = Color.argb((pressedAlpha * 255f).toInt(), 0, 0, 0)

            canvas.drawPath(selectionPath, fillPaint)

        }



        if (config.outlineEnabled) {

            // A full-strength white bloom around the enlarged pill reads as

            // an almost closed luminous ring on dark content. Keep the same

            // moving dual-light shader, but lower only the pressed indicator

            // energy in dark mode. The outer panel and light mode are intact.

            val indicatorBloomAlpha = pressProgress * if (dark) 0.48f else 1f

            drawBloomStroke(

                canvas,

                selectionRect,

                radius,

                indicatorBloomAlpha,

                90f,

                indicatorBloomShader,

            )

        }

        if (targetAdapter?.indicatorInnerShadow == true) {

            drawIndicatorInnerShadow(canvas, radius)

        } else {

            drawLiquidIndicatorLighting(canvas, dark, radius)

            // The reference's pressed pill also carries a soft inner shadow. Douyin's adapter
            // keeps its liquid caustics instead of the full inner-shadow mode, so add just the
            // press-time inset here, in addition to (not instead of) those caustics.
            if (!topBar && pressProgress > 0.001f) {
                drawIndicatorInnerShadow(canvas, radius)
            }

        }

    

        canvas.restoreToCount(transformedLayer)



        // Touch hit-testing follows the visually transformed pill, while the

        // renderer above always consumes the stable 56 dp optical layer.

        selectionRect.set(visualLeft, visualTop, visualRight, visualBottom)

    }



    private fun drawHiddenTabsBackdrop(

        combinedCanvas: Canvas,

        dark: Boolean,

        padding: Float,

        expandedWidth: Int,

        expandedHeight: Int,

        includeNavigation: Boolean = true,

    ) {

        val effect = tabsBackdropEffect ?: return

        // Keep the captured coordinate space identical to the final indicator

        // pass. The previous row-slice node shifted the effect by one padding

        // radius, which made the lens sample the light edge pixels and turn

        // into an opaque white blob while being dragged.

        tabsBackdropNode.setPosition(0, 0, expandedWidth, expandedHeight)

        perfNodeRecords++

        val tabsCanvas = tabsBackdropNode.beginRecording(expandedWidth, expandedHeight)

        drawSharedOpticalBackdrop(tabsCanvas, expandedBackdropRect)

        tabsBackdropNode.endRecording()

        tabsBackdropNode.setRenderEffect(effect)



        val rowHeight = (height - 8f * density).coerceAtLeast(1f)

        val rowTop = (height - rowHeight) / 2f

        val rowRadius = rowHeight / 2f

        // The hidden 56 dp source row is padded by 4 dp on both sides. The reserve is
        // per side: using the leading reserve for both edges was wrong after
        // 隐藏右侧搜索按钮 — the trailing reserve is zero, the pill ends at the host edge,
        // and the mask stopped short of the rightmost slots, so the selected capsule lost
        // its 遮罩色.
        val leftInset = 4f * density + navigationActionLeftReservePx()

        val rightInset = 4f * density + navigationActionReservePx()

        // Keeping the backdrop row at the full panel width displaced its left

        // rounded corner from the first indicator by exactly that inset, which

        // exposed a second curved boundary inside the stationary pill.

        val rowRect = RectF(

            padding + leftInset,

            padding + rowTop,

            padding + width - rightInset,

            padding + rowTop + rowHeight,

        )

        // This is an internal sampling mask, not the visible pill outline.

        // If it exactly coincides with the final indicator path, the two

        // antialiased edges multiply their fractional alpha (especially on

        // AndroidX's three-segment inward corner) and expose a translucent

        // crescent. Give the sampled row a tiny bleed; drawSelection still

        // applies the exact configured squircle as the final clip.

        val samplingBleed = 1.5f * density

        rowRect.inset(-samplingBleed, -samplingBleed)

        selectionPath.setBottomBarSquircle(

            rowRect,

            rowRect.height() * (config.cornerRadiusPercent / 100f),

            config.cornerSmoothing,

        )

        combinedCanvas.save()

        combinedCanvas.clipPath(selectionPath)

        combinedCanvas.drawRenderNode(tabsBackdropNode)



        // The capsule keeps the bar's own container colour, so it follows the themed
        // mask; the adapter's ratio only makes the selected pill read a little stronger
        // than the surface beneath it (dark mode only).
        val maskBase = surfaceContainerColor(dark)
        val maskRatio = if (dark) targetAdapter?.indicatorMaskRatioDark ?: 1f else 1f
        fillPaint.color = Color.argb(
            (Color.alpha(maskBase) * maskRatio).toInt().coerceIn(0, 255),
            Color.red(maskBase),
            Color.green(maskBase),
            Color.blue(maskBase),
        )
        combinedCanvas.drawPath(selectionPath, fillPaint)



        // InteractiveHighlight is part of the captured hidden row as well.

        combinedCanvas.save()

        combinedCanvas.translate(padding, padding)

        drawInteractiveHighlight(combinedCanvas)



        if (includeNavigation) {

            drawingHiddenNavigationForOptics = targetAdapter?.usesNavigationOpticalMix == true

            if (targetAdapter?.drawsNavigationInOptics == true ||

                (context.packageName == QQ_PACKAGE && hasQqStaticIcons())

            ) {

                // WeChat's native TabIconView already supplies the correct selected

                // green while its separate badge Views remain red. Tinting the

                // whole row with SRC_IN also recolours those badges, so preserve

                // the native pixels as an indivisible icon/label/badge composition.

                if (targetAdapter?.usesNavigationOpticalMix == true) {
                    val opticalMix = navigationOpticalMix()
                    if (opticalMix > 0f) {
                        val layer = combinedCanvas.saveLayerAlpha(
                            0f, 0f, width.toFloat(), height.toFloat(),
                            (opticalMix * 255f).roundToInt().coerceIn(0, 255),
                        )
                        drawScaledNavigationSource(combinedCanvas, 1f + 0.2f * pressProgress)
                        combinedCanvas.restoreToCount(layer)
                    }
                } else {
                    drawScaledNavigationSource(combinedCanvas, 1f + 0.2f * pressProgress)
                }

            } else {

                hiddenTabsTintPaint.colorFilter = BlendModeColorFilter(

                    selectedNavigationAccent(),

                    BlendMode.SRC_IN,

                )

                val tintLayer = combinedCanvas.saveLayer(

                    0f,

                    0f,

                    width.toFloat(),

                    height.toFloat(),

                    hiddenTabsTintPaint,

                )

                drawScaledNavigationSource(combinedCanvas, 1f + 0.2f * pressProgress)

                combinedCanvas.restoreToCount(tintLayer)

                appNavigationState?.drawNavigationSnapshotOverlay(
                    combinedCanvas,
                    adapterNavigationFrame(1f + 0.2f * pressProgress),
                    navigationSnapshot,
                )

            }

            drawingHiddenNavigationForOptics = false

        }

        combinedCanvas.restore()

        combinedCanvas.restore()

    }



    private val transparentDrawable by lazy {

        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)

    }






    private fun stabilizeLiftedHostPosition() {

        if (targetAdapter?.hostLiftDp == null) return

        val content = parent as? View ?: return

        val params = layoutParams as? FrameLayout.LayoutParams ?: return

        // Mirror mi_health geometry exactly. The bottom offset is kept in the

        // current parent coordinate space while the side inset always remains

        // screen-relative, including the first frame of inset convergence.

        // WeChat lift, shared with the initial layout and side insets.

        val wanted = glassHostBottomMarginPx(content, config) + ((targetAdapter?.hostLiftDp ?: 0f) * density).toInt()

        // With fewer than four tabs the bar keeps the 4-slot per-slot width

        // (slider length unchanged) and simply shortens, staying centred.

        val wantedWidth = symmetricGlassHostWidthPx(

            content,

            density,

            config,

            visualSlotCount(),

        )

        if (params.bottomMargin != wanted || params.width != wantedWidth) {

            params.bottomMargin = wanted

            params.width = wantedWidth

            layoutParams = params

            requestLayout()

        }

    }








    /**

     * JD's reselect contract (Home -> scroll to top and icon press animation)

     * is implemented in its touch pipeline, not in the plain OnClickListener.

     * Deliver a complete local gesture to the original hidden tab view.

     */

    private fun adapterNavigationSurfaceColor(): Int {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return targetAdapter?.navigationSurfaceColor(context.findActivity(), navigationSource, dark)
            ?: Color.TRANSPARENT
    }





























    private fun drawSharedOpticalBackdrop(canvas: Canvas, destination: RectF) {

        val effect = sharedOpticalBlurEffect

        if (!reuseOpticalBlur || effect == null || !canvas.isHardwareAccelerated) {

            drawBackdropContent(canvas, destination)

            return

        }

        val w = destination.width().roundToInt().coerceAtLeast(1)

        val h = destination.height().roundToInt().coerceAtLeast(1)

        val currentBackdrop = backdrop

        val needsRecord = appNavigationState?.opticalBlurNodeNeedsRecord(
            sharedOpticalBlurPrepared, currentBackdrop, w, h, effect,
        ) ?: !sharedOpticalBlurPrepared

        if (needsRecord) {

            val recording = sharedOpticalBlurNode.beginRecording(w, h)

            drawBackdropContent(recording, destination)

            sharedOpticalBlurNode.endRecording()

            sharedOpticalBlurNode.setPosition(0, 0, w, h)

            sharedOpticalBlurNode.setRenderEffect(effect)

            sharedOpticalBlurPrepared = true

            appNavigationState?.onOpticalBlurNodeRecorded(currentBackdrop, w, h, effect)

        }

        canvas.drawRenderNode(sharedOpticalBlurNode)

    }



    /**

     * The checkout strip used to be lifted by the 400 ms probe only, so the

     * first frames after entering the cart page drew it at JD's native anchor

     * (behind the glass) before it jumped up. To kill the jump, detection and

     * correction now run BETWEEN layout and draw: a global-layout listener on

     * the window root fires right after RN lays the strip out, finds it and

     * offsets it in the same frame 锟?the wrong position is never drawn. A

     * cheap per-preDraw corrector (two getLocationInWindow calls) keeps the

     * offset alive across RN re-renders that reset it.

     */

    internal fun onAdapterContentScroll() {
        appNavigationState?.onContentScroll()
    }

    internal fun resetCompositorSamplingCadence() {
        compositorSurfaceStaticStreak = 0
        compositorSurfaceVsyncTick = 0
    }



    /**

     * New Products owns a custom full-page renderer which can restore its own

     * measured height after every feed update. Re-applying our content-spine

     * layout params from every pre-draw creates a layout tug-of-war, while

     * referencing the texture-backed scene from a second RenderNode can make

     * whole tiles disappear. Detection is cached in steady state; this page

     * keeps the extended outer viewport but uses compositor-native blur.

     */








    /**

     * The flash ("绉掞拷?) page's two floating controls, identified from a live

     * view-tree dump:

     *

     *  - Coupon banner: `HourlyGoScreenBottomBarView` [0,2586 1220x117] 锟?

     *    full-width strip pinned at the screen bottom (bottom edge runs 47 px

     *    offscreen), hosting the coupon copy and the 鍘讳娇锟?button. It sits as

     *    a direct child of HourlyGoMvpFragmentView, OUTSIDE the feed's

     *    RecyclerView. Matched by class name (JD-private, unambiguous) with a

     *    full-width bottom-strip shape fallback.

     *

     *  - Floating cart: a 130x130 SimpleDraweeView at (1064,2378) inside a

     *    130x143 ConstraintLayout 锟?the square right-edge FAB, half-covered

     *    by the glass. Matched as a near-square right-edge image button

     *    outside every scroll container; its direct parent container is what

     *    gets lifted (keeps the badge and touch area together).

     */

    /**

     * Weibo recreates its navigation hierarchy while a page is settling.  The

     * source row is therefore used only as a controller and badge provider;

     * copying any of its artwork allows feed cards, avatar strips and Weibo's

     * blue selected disk to leak into the retained bitmap after repeated drags.

     */
    private val configSyncReceiver = object : android.content.BroadcastReceiver() {

        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {

            if (intent?.action != io.github.xzdw.dylt.config.ConfigContract.ACTION_CONFIG_RESPONSE) return

            val extras = intent.extras ?: return

            val packageName = extras.getString(io.github.xzdw.dylt.config.ConfigContract.KEY_PACKAGE).orEmpty()

            if (packageName != context.packageName) return

            val pushed = GlassConfig.fromBundle(extras)

            HookConfigReader.acceptPushedConfig(packageName, pushed)

            val updated = packageConfig(pushed)

            if (updated != config) applyLiveConfig(updated)

        }

    }



    /**
     * Framework preference push target.
     *
     * Held by the host on purpose: [HookConfigReader] keeps only a weak reference,
     * so an inline lambda would be collected immediately and the host would stop
     * seeing pushed configuration.
     */
    private val pushedConfigListener: (io.github.xzdw.dylt.config.GlassConfig) -> Unit = { pushed ->
        post {
            val updated = packageConfig(pushed)
            if (updated != config) applyLiveConfig(updated)
        }
    }

    private fun registerConfigSyncBroadcast() {

        if (configSyncBroadcastRegistered) return

        // The framework preference push reaches the same place the broadcast
        // does: accept the snapshot, then re-apply it if anything changed.
        HookConfigReader.addPushListener(pushedConfigListener)

        runCatching {

            context.registerReceiver(

                configSyncReceiver,

                android.content.IntentFilter(

                    io.github.xzdw.dylt.config.ConfigContract.ACTION_CONFIG_RESPONSE,

                ),

                io.github.xzdw.dylt.config.ConfigContract.CONFIG_SYNC_PERMISSION,

                null,

                Context.RECEIVER_EXPORTED,

            )

            configSyncBroadcastRegistered = true

            context.sendBroadcast(

                android.content.Intent(

                    io.github.xzdw.dylt.config.ConfigContract.ACTION_CONFIG_REQUEST,

                )

                    .setComponent(

                        android.content.ComponentName(

                            MODULE_PACKAGE,

                            "$MODULE_PACKAGE.config.ConfigRequestReceiver",

                        ),

                    )

                    .putExtra(

                        io.github.xzdw.dylt.config.ConfigContract.EXTRA_REQUEST_PACKAGE,

                        context.packageName,

                    ),

            )

        }.onFailure {

            Xposed.log("[OfflineGlass][ConfigSync] register/request failed: $it")

        }

    }



    private fun unregisterConfigSyncBroadcast() {

        runCatching { context.unregisterReceiver(configSyncReceiver) }

        configSyncBroadcastRegistered = false

    }



    /**

     * Keep Weibo's native tab row alive as the page/selection controller, but

     * remove its artwork in one non-destructive operation.  Do not walk into

     * the row and disable click/focus state: Weibo rebuilds that state while

     * leaving/returning from Video and LiquidTab relies on it to restore the

     * floating bar immediately.

     */
    /** Keep Weibo's independent reward envelope above the floating bar. */
    private fun suppressNativeMeituanTakeoutBottomBar(source: ViewGroup?) {

        if (context.packageName != MEITUAN_TAKEOUT_PACKAGE || source == null) return

        // The app's native scroll-show can fade the TitleIndicator back in on

        // the order/profile pages; install-time alpha=0 is never re-asserted

        // elsewhere, so enforce it every pass like the other adapters.

        if (source.alpha != 0f) source.alpha = 0f

        // Hierarchy (from uiautomator dump):

        //   content

        //   鈹溾攢鈹€ pager_container                       [full width, stops above the bar]

        //   鈹溾攢鈹€ navigation_bar_margin_layout_container [opaque native strip]

        //   鈹溾攢鈹€ indicator_bg_container                [TitleIndicator + tab_line]

        //   鈹斺攢鈹€ GlassHostLayout (this host)

        // 1) Make every wrapper between TitleIndicator and the activity content

        //    transparent. These strips only span the bottom band, so clearing

        //    them never touches page content.

        var node: ViewGroup? = source.parent as? ViewGroup

        var guard = 0

        while (node != null && guard++ < 6 && node.id != android.R.id.content) {

            if (node.background != null) {

                meituanTakeoutChainRestoreCount++

                node.background = null

            }

            if (node.backgroundTintList != null) node.backgroundTintList = null

            if (node.foreground != null) node.foreground = null

            // Sibling decoration layers (tab_line, home indicator paints) that

            // live inside the same strip container.

            for (index in 0 until node.childCount) {

                val layer = node.getChildAt(index)

                if (layer === source || layer === this) continue

                if (layer.background != null) layer.background = null

                if (layer.foreground != null) layer.foreground = null

            }

            node = node.parent as? ViewGroup

        }

        // 2) Extend the page pager to the window bottom. Meituan Takeout

        //    reserves the native bar's height inside pager_container, so once

        //    the native strip is transparent the reserved band exposes the

        //    window background ("white residual bar" over the feed). Restore

        //    full-bleed like the Meituan main app's id="main" treatment.

        val content = node as? ViewGroup ?: (source.rootView.findViewById<ViewGroup>(android.R.id.content))

        if (content != null) {

            for (index in 0 until content.childCount) {

                val child = content.getChildAt(index)

                if (child === this || child === source) continue

                val idName = runCatching {

                    if (child.id == View.NO_ID) null else child.resources.getResourceEntryName(child.id)

                }.getOrNull() ?: continue

                if (idName != "pager_container") continue

                val params = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue

                var changed = false

                if (params.height != ViewGroup.LayoutParams.MATCH_PARENT) {

                    params.height = ViewGroup.LayoutParams.MATCH_PARENT

                    changed = true

                    meituanTakeoutPagerRestoreCount++

                }

                if (params.bottomMargin != 0) {

                    params.bottomMargin = 0

                    changed = true

                }

                if (changed) {

                    child.layoutParams = params

                    child.requestLayout()

                    content.requestLayout()

                }

                break

            }

        }

        // 3) The white residual band is painted by indicator_bg_container, a

        //    sibling branch under content (it hosts the tab_line hairline; the

        //    TitleIndicator itself lives under navigation_bar_margin_layout_

        //    container). The parent-chain walk above never reaches it.

        //    On the promotion tabs (绁炲埜/娲诲姩) the app re-applies the strip's

        //    background every few frames, so per-frame clearing raced with the

        //    re-apply and produced a flickering white band. Make the container

        //    INVISIBLE instead: an invisible view never paints, no matter how

        //    often the app swaps its background. Clicks are unaffected - the

        //    glass host sits above it and forwards synthesized touches to the

        //    TitleIndicator itself.

        if (content != null) {

            for (index in 0 until content.childCount) {

                val sibling = content.getChildAt(index)

                if (sibling === this || sibling === source) continue

                if (meituanTakeoutIdName(sibling) != "indicator_bg_container") continue

                meituanTakeoutIndicatorBg = sibling

                if (sibling.visibility == View.VISIBLE) {

                    meituanTakeoutBgRestoreCount++

                    sibling.visibility = View.INVISIBLE

                }

                if (sibling is ViewGroup) {

                    for (childIndex in 0 until sibling.childCount) {

                        val inner = sibling.getChildAt(childIndex)

                        if (inner !== source && meituanTakeoutIdName(inner) == "tab_line" &&

                            inner.visibility == View.VISIBLE

                        ) {

                            meituanTakeoutTabLineRestoreCount++

                            inner.visibility = View.INVISIBLE

                        }

                    }

                }

            }

        }

        val now = SystemClock.uptimeMillis()

        if (now - meituanTakeoutSuppressDiagAt > 10_000L) {

            meituanTakeoutSuppressDiagAt = now

            logMeituanTakeoutSuppressDiag(source)

        }

    }



    private var meituanTakeoutSuppressDiagAt = 0L



    private var meituanTakeoutBgRestoreCount = 0

    private var meituanTakeoutTabLineRestoreCount = 0

    private var meituanTakeoutChainRestoreCount = 0

    private var meituanTakeoutPagerRestoreCount = 0

    private var meituanTakeoutIndicatorBg: View? = null

    private var compositorSurfaceProbeScheduled = false

    private var compositorSurfaceProbeRunning = false

    private var compositorSurfaceVsyncTick = 0

    private var compositorSurfaceStaticStreak = 0

    private var compositorSurfaceCopyCount = 0

    private var compositorSurfaceRenderCount = 0

    private var compositorSurfacePerfLogAt = 0L

    private var lastSurfaceRenderDark: Boolean? = null



    /**

     * Single vsync-aligned driver for compositor-readback backdrops (Meituan

     * Takeout's tabs and other adapter-selected pages). The former pipeline

     * requeued an unconditional

     * PixelCopy on every animation frame, which taxed the compositor and

     * dropped frames. Here the copy itself is demand-scheduled: every vsync

     * while the strip keeps changing, and one cheap probe every 8th vsync

     * once the page has been still for a dozen samples. Motion therefore

     * samples at full display rate 锟?the glass refraction tracks the feed's

     * own frame rate instead of stepping at half rate during fast scrolls 锟?

     * while still pages fall back to the deep 8-vsync probe stride. The

     * in-flight copy guard self-throttles the cadence whenever the GPU

     * readback cannot keep up, so cost never exceeds what the pipeline can

     * actually sustain. Touch input and bar motion pin the fast cadence so

     * scrolling never resumes on a stale backdrop. Bar animations never wait

     * on copies 锟?dispatchDraw's surface-render branch repaints at full frame

     * rate from the cached backdrop bitmap.

     */

    private fun scheduleCompositorSurfaceProbe() {

        if (compositorSurfaceProbeScheduled) return

        compositorSurfaceProbeScheduled = true

        if (!compositorSurfaceProbeRunning) {

            compositorSurfaceProbeRunning = true

            if (GLASS_HOST_DIAG_ENABLED) {

                android.util.Log.i(

                    "WmGlassDiag",

                    "surfaceProbe: start pkg=" + context.packageName + " tab=" + selectedIndex,

                )

            }

        }

        postOnAnimation {

            compositorSurfaceProbeScheduled = false

            if (!opticalSurfaceSurfacePipelineActive &&
                appNavigationState?.maintainsCompositorSamplingWithoutSurface() != true
            ) {

                compositorSurfaceProbeRunning = false

                // An adapter may leave the pipeline active after a tab switch.
                // Without this reset the re-entered tab's first

                // copy would wait out the stale 8-vsync backoff stride.

                compositorSurfaceStaticStreak = 0

                compositorSurfaceVsyncTick = 0

                return@postOnAnimation

            }

            requestOpticalSurfaceRootSurfaceFrame()

            val now = SystemClock.uptimeMillis()

            if (GLASS_HOST_DIAG_ENABLED && now - compositorSurfacePerfLogAt > 5_000L) {

                compositorSurfacePerfLogAt = now

                android.util.Log.i(

                    "WmGlassDiag",

                    "surfacePerf pkg=" + context.packageName +

                        " copies=" + compositorSurfaceCopyCount +

                        " renders=" + compositorSurfaceRenderCount +

                        " streak=" + compositorSurfaceStaticStreak,

                )

            }

            scheduleCompositorSurfaceProbe()

        }

    }



    private fun compositorSurfaceCopyDue(): Boolean {
        compositorSurfaceVsyncTick++
        val backoff = compositorSurfaceStaticStreak >= 12 &&
            !touching && !isBarVisualStateAnimating() &&
            SystemClock.uptimeMillis() >= (appNavigationState?.contentMotionHoldUntil() ?: 0L)
        val stride = if (backoff) {
            // Step down gradually: the first backoff tier halves the wake-up
            // interval so a scroll that starts right after the page settles is
            // detected within ~2 vsyncs instead of averaging 4-8. Long-idle
            // pages still settle to the same deep stride as before, so the
            // steady-state cost is unchanged.
            if (compositorSurfaceStaticStreak >= 36) 8 else 4
        } else 1
        return compositorSurfaceVsyncTick % stride == 0
    }


    /** Throttled dump of the native bar chain: pinpoints which layer still

     *  paints the white residual strip when background clearing is not enough. */

    private fun logMeituanTakeoutSuppressDiag(source: ViewGroup) {

        val sb = StringBuilder("suppressDiag source=").append(source.javaClass.simpleName)

        var node: ViewGroup? = source.parent as? ViewGroup

        var guard = 0

        while (node != null && guard++ < 6 && node.id != android.R.id.content) {

            sb.append(" up").append(guard)

                .append("=").append(node.javaClass.simpleName)

                .append("/id=").append(meituanTakeoutIdName(node))

                .append("/").append(node.width).append("x").append(node.height)

                .append("@y").append(node.top)

                .append("/bg=").append(node.background?.javaClass?.simpleName ?: "null")

                .append("/fg=").append(node.foreground?.javaClass?.simpleName ?: "null")

                .append("/alpha=").append(node.alpha)

            node = node.parent as? ViewGroup

        }

        val content = source.rootView.findViewById<ViewGroup>(android.R.id.content)

        if (content != null) {

            sb.append(" contentChildren=").append(content.childCount)

            for (index in 0 until content.childCount) {

                val child = content.getChildAt(index)

                sb.append(" c").append(index)

                    .append("=").append(child.javaClass.simpleName)

                    .append("/id=").append(meituanTakeoutIdName(child))

                    .append("/").append(child.width).append("x").append(child.height)

                    .append("@y").append(child.top)

                    .append("/bg=").append(child.background?.javaClass?.simpleName ?: "null")

                    .append("/vis=").append(child.visibility)

                    .append("/alpha=").append(child.alpha)

            }

        }

        sb.append(" restoreCounters bg=").append(meituanTakeoutBgRestoreCount)

            .append(" tabLine=").append(meituanTakeoutTabLineRestoreCount)

            .append(" chain=").append(meituanTakeoutChainRestoreCount)

            .append(" pager=").append(meituanTakeoutPagerRestoreCount)

            .append(" indicatorBgVis=").append(meituanTakeoutIndicatorBg?.visibility ?: -1)

        android.util.Log.i("WmGlassDiag", sb.toString())

    }



    private fun meituanTakeoutIdName(view: View): String = runCatching {

        if (view.id == View.NO_ID) "none" else view.resources.getResourceEntryName(view.id)

    }.getOrDefault("err")

    /** Adapter entry point for the shared optical surface renderer. */
    internal fun updateOpticalSurfacePipeline(enabled: Boolean) {
        val renderBackdrop = enabled && config.backdropCapture
        updateOpticalSurfaceSurfaceRenderer(renderBackdrop)
        updateOpticalSurfaceNativeBlur(renderBackdrop && !opticalSurfaceSurfacePipelineActive)
    }



    private fun adjustMeituanTakeoutScrollSafety() {

        if (context.packageName != MEITUAN_TAKEOUT_PACKAGE || width <= 0 || height <= 0) return

        val onProfile = selectedIndex == MEITUAN_TAKEOUT_PROFILE_INDEX

        val now = SystemClock.uptimeMillis()

        if (onProfile && now - lastMeituanTakeoutScrollProbe < MEITUAN_TAKEOUT_SCROLL_PROBE_INTERVAL_MS) return

        lastMeituanTakeoutScrollProbe = now



        if (!onProfile) {

            for ((view, base) in meituanTakeoutScrollBasePaddings) {

                if (!view.isAttachedToWindow) continue

                if (view.paddingLeft != base[0] || view.paddingTop != base[1] ||

                    view.paddingRight != base[2] || view.paddingBottom != base[3]

                ) {

                    view.setPadding(base[0], base[1], base[2], base[3])

                    view.requestLayout()

                }

            }

            return

        }



        // While the bar is scroll-hidden on this page, keep the last applied

        // padding so the layout does not jump when the bar reappears.

        val hostRect = Rect()

        if (!getGlobalVisibleRect(hostRect)) return

        val gap = globalGlassBottomGapPx(this)

        val wantedItemBottom = hostRect.top - gap



        val candidates = ArrayList<ViewGroup>()

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += (rootView as? ViewGroup ?: return) to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view === this || view === navigationSource) continue

            if (view is ViewGroup) {

                val className = view.javaClass.name

                val scrollLike = className.contains("ScrollView") ||

                    className.contains("RecyclerView") ||

                    className.contains("NestedScroll")

                if (scrollLike && view.visibility == View.VISIBLE) {

                    val rect = Rect()

                    if (view.getGlobalVisibleRect(rect) && rect.top < hostRect.top &&

                        rect.bottom > hostRect.top

                    ) {

                        if (candidates.none { isDescendantOf(view, it) }) {

                            candidates += view

                        }

                    }

                }

                if (depth < 28) {

                    for (index in view.childCount - 1 downTo 0) {

                        stack += view.getChildAt(index) to depth + 1

                    }

                }

            }

        }



        val active = HashSet<View>(candidates)

        for ((view, base) in meituanTakeoutScrollBasePaddings) {

            if (!view.isAttachedToWindow || view in active) continue

            if (view.paddingLeft != base[0] || view.paddingTop != base[1] ||

                view.paddingRight != base[2] || view.paddingBottom != base[3]

            ) {

                view.setPadding(base[0], base[1], base[2], base[3])

                view.requestLayout()

            }

        }

        for (view in candidates) {

            val rect = Rect()

            if (!view.getGlobalVisibleRect(rect)) continue

            val base = meituanTakeoutScrollBasePaddings.getOrPut(view) {

                intArrayOf(

                    view.paddingLeft,

                    view.paddingTop,

                    view.paddingRight,

                    view.paddingBottom,

                )

            }

            val required = (rect.bottom - wantedItemBottom).coerceAtLeast(base[3])

            if (view.paddingLeft != base[0] || view.paddingTop != base[1] ||

                view.paddingRight != base[2] || view.paddingBottom != required

            ) {

                view.setPadding(base[0], base[1], base[2], required)

                view.clipToPadding = false

                view.requestLayout()

            }

        }

    }



    internal fun viewResourceEntryName(view: View): String? = runCatching {

        if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)

    }.getOrNull()

    internal fun findViewByClassName(root: View, className: String): View? {
        if (root.javaClass.name.endsWith(className)) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findViewByClassName(root.getChildAt(index), className)?.let { return it }
            }
        }
        return null
    }

    internal fun isDescendantOf(view: View, ancestor: View?): Boolean {
        if (ancestor == null) return false
        var parent = view.parent
        while (parent is View) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }



    internal fun findScrollableList(root: ViewGroup): View? {

        // Match by class name (handles subclasses like WBRecyclerView, etc.)

        val cn = root.javaClass.name

        val sn = root.javaClass.simpleName

        if (sn == "RecyclerView" || sn == "ListView" || sn == "AbsListView" ||

            cn.contains("RecyclerView") || cn.contains("AbsListView") ||

            cn.contains("ListView") || cn.contains("ScrollableList")

        ) return root

        for (i in 0 until root.childCount) {

            val child = root.getChildAt(i) ?: continue

            if (child is ViewGroup) {

                val found = findScrollableList(child)

                if (found != null) return found

            }

        }

        return null

    }



    /**

     * Render the liquid bar in an independent Surface. PixelCopy can then read

     * the application's root surface without recursively copying the glass or

     * asking a custom page renderer to produce a second display list.

     */

    private fun updateOpticalSurfaceSurfaceRenderer(enabled: Boolean) {

        if (targetAdapter?.usesOpticalSurfacePipeline != true && targetAdapter?.usesDirectOpticalBackdropMode != true &&

            context.packageName != MEITUAN_TAKEOUT_PACKAGE

        ) return

        val compositorEnabled = enabled && config.backdropCapture

        val content = parent as? FrameLayout ?: return

        var surface = opticalSurfaceSurfaceView

        if (!compositorEnabled && surface == null) return

        if (surface == null) {

            surface = SurfaceView(context).apply {

                isClickable = false

                isFocusable = false

                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

                setZOrderOnTop(true)

                holder.setFormat(PixelFormat.TRANSLUCENT)

                visibility = View.GONE

                holder.addCallback(object : SurfaceHolder.Callback {

                    override fun surfaceCreated(holder: SurfaceHolder) {

                        opticalSurfaceSurfaceReady = true

                        if (wantsRootSurfaceOpticalPipeline() &&
                            appNavigationState?.directOpticalBackdropActive() != true

                        ) {

                            appNavigationState?.onOpticalSurfaceFrameInvalidated()

                            opticalSurfaceSurfacePipelineActive = true

                            visibility = View.VISIBLE

                            updateOpticalSurfaceNativeBlur(false)

                            if (usesCompositorSurfaceProbe()) {

                                scheduleCompositorSurfaceProbe()

                            } else {

                                requestOpticalSurfaceRootSurfaceFrame()

                            }

                        }

                    }



                    override fun surfaceChanged(

                        holder: SurfaceHolder,

                        format: Int,

                        width: Int,

                        height: Int,

                    ) {

                        if (opticalSurfaceSurfacePipelineActive) {

                            requestOpticalSurfaceRootSurfaceFrame()

                        }

                    }



                    override fun surfaceDestroyed(holder: SurfaceHolder) {

                        opticalSurfaceSurfaceReady = false

                        opticalSurfaceSurfacePipelineActive = false

                        appNavigationState?.onOpticalSurfaceFrameInvalidated()

                        opticalSurfaceRootCopyInFlight = false

                    }

                })

            }

            val hostIndex = content.indexOfChild(this).coerceAtLeast(0)

            content.addView(surface, hostIndex, copyHostLayoutParams())

            opticalSurfaceSurfaceView = surface

        }

        syncOpticalSurfaceSurfaceLayout(surface)

        if (compositorEnabled) {

            appNavigationState?.onOpticalSurfacePipelineEnabled(opticalSurfaceSurfacePipelineActive)

            surface.visibility = View.VISIBLE

            if (opticalSurfaceSurfaceReady) {

                appNavigationState?.onOpticalSurfacePipelineEnabled(opticalSurfaceSurfacePipelineActive)

                opticalSurfaceSurfacePipelineActive = true

                updateOpticalSurfaceNativeBlur(false)

                if (usesCompositorSurfaceProbe()) {

                    // The probe loop is the single copy driver; requesting

                    // directly here as well would double-count the vsync tick

                    // and break the cadence stride.

                    scheduleCompositorSurfaceProbe()

                } else {

                    requestOpticalSurfaceRootSurfaceFrame()

                }

            }

        } else {

            if (targetAdapter?.usesDirectOpticalBackdropMode == true) {
                if (appNavigationState?.enterDirectOpticalBackdrop(
                        surface, opticalSurfaceSurfacePipelineActive,
                    ) != true) return
                opticalSurfaceSurfacePipelineActive = false
                opticalSurfaceRootCopyInFlight = false
                return

            }

            if (!opticalSurfaceSurfacePipelineActive && surface.visibility == View.GONE) return

            opticalSurfaceSurfacePipelineActive = false

            opticalSurfaceRootCopyInFlight = false

            surface.visibility = View.GONE

            surface.holder.surface.takeIf(Surface::isValid)?.let {

                runCatching {

                    val canvas = surface.holder.lockCanvas()

                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                    surface.holder.unlockCanvasAndPost(canvas)

                }

            }

        }

    }



    private fun syncOpticalSurfaceSurfaceLayout(surface: SurfaceView) {

        val host = layoutParams as? FrameLayout.LayoutParams ?: return

        val target = surface.layoutParams as? FrameLayout.LayoutParams ?: copyHostLayoutParams()

        val overflow = opticalSurfaceSurfaceOverflowPx()

        if (targetAdapter?.ownsOpticalSurfaceLayout == true) {
            val content = parent as? FrameLayout ?: return
            val layout = targetAdapter?.opticalSurfaceLayout(
                content.width, content.height, left, top, width, height, overflow,
            ) ?: return
            if (target.width == layout.width && target.height == layout.height &&
                target.leftMargin == layout.left && target.topMargin == layout.top &&
                target.rightMargin == layout.right && target.bottomMargin == layout.bottom &&
                target.gravity == layout.gravity
            ) return
            target.width = layout.width
            target.height = layout.height
            target.gravity = layout.gravity
            target.leftMargin = layout.left
            target.topMargin = layout.top
            target.rightMargin = layout.right
            target.bottomMargin = layout.bottom
            surface.layoutParams = target
            return
        }

        val hostWidth = if (host.width > 0) host.width else width.coerceAtLeast(1)

        val hostHeight = if (host.height > 0) host.height else height.coerceAtLeast(1)

        val targetWidth = hostWidth + overflow * 2

        val targetHeight = hostHeight + overflow * 2

        val targetBottomMargin = host.bottomMargin - overflow

        if (target.width == targetWidth && target.height == targetHeight &&

            target.gravity == host.gravity && target.leftMargin == host.leftMargin &&

            target.topMargin == host.topMargin && target.rightMargin == host.rightMargin &&

            target.bottomMargin == targetBottomMargin

        ) return

        target.width = targetWidth

        target.height = targetHeight

        target.gravity = host.gravity

        target.leftMargin = host.leftMargin

        target.topMargin = host.topMargin

        target.rightMargin = host.rightMargin

        // The bar is bottom/center anchored. Expanding around its centre and

        // relaxing the bottom margin leaves equal overflow on every edge.

        target.bottomMargin = targetBottomMargin

        surface.layoutParams = target

    }



    private fun opticalSurfaceSurfaceOverflowPx(): Int =

        (32f * density).roundToInt()



    private fun requestOpticalSurfaceRootSurfaceFrame(afterFrameCommit: Boolean = false) {
        if (!config.backdropCapture) return
        if (SystemClock.uptimeMillis() < barAnimationPriorityUntil) return
        if (appNavigationState?.requiresSampledBackdrop() == false) return
        // A ready adapter-owned Surface frame already supplies the glass pixels.
        // Root PixelCopy cannot include that layer and would compete for no gain.
        if (appNavigationState?.needsSurfaceBackdrop() == true &&
            appNavigationState?.videoBackdrop()?.bitmap?.let { !it.isRecycled } == true
        ) return
        if (!opticalSurfaceSurfacePipelineActive || !opticalSurfaceSurfaceReady ||
            opticalSurfaceRootCopyInFlight || width <= 0 || height <= 0
        ) return
        if (!afterFrameCommit && usesCompositorSurfaceProbe() && !compositorSurfaceCopyDue()) return
        val root = rootView
        if (!afterFrameCommit && appNavigationState?.scheduleOpticalSurfaceCopy(root) {
                requestOpticalSurfaceRootSurfaceFrame(afterFrameCommit = true)
            } == true) return
        val viewRoot = runCatching {
            Xposed.callMethod(root, "getViewRootImpl")
        }.getOrNull() ?: return
        val sourceSurface = readPrivateField(viewRoot, "mSurface")
            as? Surface ?: return
        if (!sourceSurface.isValid) return

        val rootLocation = IntArray(2).also(root::getLocationInWindow)
        val hostLocation = IntArray(2).also(::getLocationInWindow)
        val localLeft = hostLocation[0] - rootLocation[0]
        val localTop = hostLocation[1] - rootLocation[1]
        val desiredPadding = 24f * density
        val availablePadding = minOf(
            desiredPadding,
            localLeft.toFloat(),
            localTop.toFloat(),
            (root.width - localLeft - width).toFloat(),
            (root.height - localTop - height).toFloat(),
        ).coerceAtLeast(0f)
        if (abs(availablePadding - opticalSurfaceBackdropPadding) >= 1f) {
            opticalSurfaceBackdropPadding = availablePadding
            configureGlassEffects()
        }
        val padding = availablePadding.roundToInt()
        val captureGeometry = targetAdapter?.opticalCaptureGeometry(
            root.width, root.height, localLeft, localTop, width, height, padding,
        )
        if (targetAdapter?.usesDirectOpticalBackdropMode == true && captureGeometry == null) return
        val sourceRect = captureGeometry?.sourceRect ?: Rect(
            localLeft - padding,
            localTop - padding,
            localLeft + width + padding,
            localTop + height + padding,
        )
        if (sourceRect.width() <= 0 || sourceRect.height() <= 0) return
        val targetWidth = captureGeometry?.targetWidth
            ?: max(1, (sourceRect.width() / CAPTURE_DOWNSCALE).roundToInt())
        val targetHeight = captureGeometry?.targetHeight
            ?: max(1, (sourceRect.height() / CAPTURE_DOWNSCALE).roundToInt())
        val requestedBarSource = captureGeometry?.barSource
        val current = backdrop
        val reusable = opticalSurfaceRootCopyScratch?.takeIf {
            !it.isRecycled && it !== current &&
                it.width == targetWidth && it.height == targetHeight
        }
        val target = reusable ?: Bitmap.createBitmap(
            targetWidth,
            targetHeight,
            Bitmap.Config.ARGB_8888,
        )
        if (reusable != null) opticalSurfaceRootCopyScratch = null
        opticalSurfaceRootCopyInFlight = true
        // Adapter-selected pages deliver their copy
        // callbacks on a dedicated thread: the strip compare and the full
        // glass render used to run on the UI thread every vsync, competing
        // with the app's own feed rendering and reading as insufficient bar
        // frame rate during fast scrolls.
        val copyHandler = appNavigationState?.opticalSurfaceCopyHandler() ?: if (usesCompositorSurfaceProbe()) {
            compositorPixelCopyHandler ?: HandlerThread("LiquidTab-Compositor-PixelCopy").let { thread ->
                thread.start()
                compositorPixelCopyThread = thread
                Handler(thread.looper).also { compositorPixelCopyHandler = it }
            }
        } else {
            opticalSurfacePixelCopyHandler
        }
        runCatching {
            PixelCopy.request(
                sourceSurface,
                sourceRect,
                target,
                { result ->
                    var copySlotReleased = false
                    try {
                        glassPerfLogTick()
                        if (result == PixelCopy.SUCCESS && opticalSurfaceSurfacePipelineActive) {
                            val previous = backdrop
                            appNavigationState?.onOpticalBackdropCaptured(requestedBarSource)
                            backdrop = target
                            if (previous !== target) {
                                opticalSurfaceRootCopyScratch?.takeUnless(Bitmap::isRecycled)?.recycle()
                                opticalSurfaceRootCopyScratch = previous
                            }
                            val stripChanged = previous == null || previous.isRecycled ||
                                !previous.sameAs(target)
                            if (context.packageName == MEITUAN_TAKEOUT_PACKAGE ||
                                targetAdapter?.countsOpticalCompositorCopies == true ||
                                targetAdapter?.usesOpticalSurfacePipeline == true
                            ) {
                                compositorSurfaceCopyCount++
                                if (stripChanged) {
                                    compositorSurfaceStaticStreak = 0
                                } else {
                                    compositorSurfaceStaticStreak++
                                }
                            }
                            if (stripChanged) appNavigationState?.onOpticalBackdropChanged()
                            if (appNavigationState?.overlapsOpticalCopyAndRender() == true) {
                                // The published target remains read-only. The next copy
                                // writes only the retired scratch buffer; callbacks and
                                // rendering remain serialized on the adapter worker.
                                copySlotReleased = true
                                opticalSurfaceRootCopyInFlight = false
                                appNavigationState?.onOpticalSurfaceCopyCompleted(root) {
                                    requestOpticalSurfaceRootSurfaceFrame(afterFrameCommit = true)
                                }
                            }
                            val barAnimating = isBarVisualStateAnimating()
                            if (stripChanged || barAnimating || isDarkGlass() != lastSurfaceRenderDark) {
                                if (targetAdapter?.deferOpticalRenderDuringBarAnimation == true && barAnimating) {
                                    // During a bar gesture/transition the UI frame
                                    // pump owns Surface submission. The copy thread
                                    // only publishes the newest backdrop, avoiding
                                    // duplicate lock contention without dropping an
                                    // animation or optical update.
                                    postInvalidateOnAnimation()
                                } else {
                                    compositorSurfaceRenderCount++
                                    renderOpticalSurfaceSurface()
                                }
                            }
                        } else if (target !== backdrop) {
                            opticalSurfaceRootCopyScratch?.takeUnless(Bitmap::isRecycled)?.recycle()
                            opticalSurfaceRootCopyScratch = target
                        }
                    } finally {
                        // An overlapped request may already own the slot. Do not
                        // clear its guard when the preceding render completes.
                        if (!copySlotReleased) opticalSurfaceRootCopyInFlight = false
                        if (!copySlotReleased && result == PixelCopy.SUCCESS && opticalSurfaceSurfacePipelineActive) {
                            appNavigationState?.onOpticalSurfaceCopyCompleted(root) {
                                requestOpticalSurfaceRootSurfaceFrame(afterFrameCommit = true)
                            }
                        }
                    }
                },
                copyHandler,
            )
        }.onFailure {
            opticalSurfaceRootCopyInFlight = false
            if (target !== backdrop) {
                opticalSurfaceRootCopyScratch?.takeUnless(Bitmap::isRecycled)?.recycle()
                opticalSurfaceRootCopyScratch = target
            }
        }
    }



    private fun readPrivateField(instance: Any, name: String): Any? {

        var type: Class<*>? = instance.javaClass

        while (type != null) {

            val field = runCatching { type.getDeclaredField(name) }.getOrNull()

            if (field != null) {

                return runCatching {

                    field.isAccessible = true

                    field.get(instance)

                }.getOrNull()

            }

            type = type.superclass

        }

        return null

    }



    /** True while any spring/press/visibility state still drives bar visuals. */

    private fun isBarVisualStateAnimating(): Boolean =

        touching || pressProgress > 0.001f || interactiveHighlightProgress > 0.001f ||

            abs(panelDragOffset) > 0.001f ||

            abs(indicatorPosition - dragTarget) > 0.001f ||

            abs(indicatorScaleX - 1f) > 0.001f || abs(indicatorScaleY - 1f) > 0.001f


    internal fun queueOpticalSurfaceRender() {
        val renderHandler = appNavigationState?.opticalSurfaceCopyHandler() ?: compositorPixelCopyHandler
        if (appNavigationState?.queueOpticalSurfaceRender(
                renderHandler,
                active = { opticalSurfaceSurfacePipelineActive },
                render = ::renderOpticalSurfaceSurface,
            ) == true
        ) return
        fallbackOpticalSurfaceRenderQueue.request(
            renderHandler, active = { opticalSurfaceSurfacePipelineActive },
            render = ::renderOpticalSurfaceSurface,
        )
    }



    private fun renderOpticalSurfaceSurface() {

        if (!opticalSurfaceSurfacePipelineActive || !hasUsableBackdrop()) return

        val surface = opticalSurfaceSurfaceView ?: return

        if (!surface.holder.surface.isValid) return

        // Renders are invoked both from dispatchDraw (UI thread) and from the

        // PixelCopy callback (compositor thread for JD, Meituan Takeout, and

        // compositor-backed pages).

        // The shared RenderNodes must never be recorded concurrently.

        synchronized(surfaceRenderLock) {

            // JD's backdrop bitmap is immutable between PixelCopy deliveries.

            // Slider springs may render several frames against that same frame;

            // retain the already blurred optical node instead of re-running the

            // identical Gaussian pass for every animation frame.

            if (targetAdapter?.reuseSurfaceBlurDuringBarAnimation != true) sharedOpticalBlurPrepared = false

            perfSurfaceRenders++

            runCatching {

            val canvas = surface.holder.lockHardwareCanvas()

            try {

                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                val contentAlignedOpticalSurface = targetAdapter?.usesContentAlignedOpticalSurface == true

                val overflow = opticalSurfaceSurfaceOverflowPx().toFloat()

                val surfaceParams = surface.layoutParams as? FrameLayout.LayoutParams

                val barOffsetX = if (contentAlignedOpticalSurface) {

                    (left - (surfaceParams?.leftMargin ?: surface.left)).toFloat()

                } else {

                    overflow

                }

                val barOffsetY = if (contentAlignedOpticalSurface) {

                    (top - (surfaceParams?.topMargin ?: surface.top)).toFloat()

                } else {

                    overflow

                }

                canvas.save()

                if (contentAlignedOpticalSurface) {

                    // Apply the host transform in the content-aligned Surface's own

                    // coordinates, anchored on the bar's bottom edge.

                    val pivotX = barOffsetX + width * 0.5f

                    val pivotY = barOffsetY + height

                    canvas.translate(pivotX, pivotY + translationY)

                    canvas.scale(scaleX, scaleY)

                    canvas.translate(-pivotX, -pivotY)

                }

                canvas.translate(barOffsetX, barOffsetY)

                // JD follows system night mode directly. Walking its native

                // navigation hierarchy here cannot affect the result and used

                // to add periodic work to the compositor render thread.

                updateDetectedContentTheme()

                val dark = isDarkGlass()

                lastSurfaceRenderDark = dark

                drawGlassPanel(canvas, dark)

                // Same transform the panel itself was just drawn with (see drawGlassPanel).
                val panelScaleLayer = canvas.save()

                canvas.scale(outerPanelScale, outerPanelScale, width / 2f, height / 2f)

                val burnInLayer = saveBurnInOffset(canvas)

                if (targetAdapter?.navigationBehindIndicator == true) {

                    // The indicator's combined scene includes this same native

                    // artwork, so drawing the row first lets the liquid lens

                    // replace/refract it instead of leaving a sharp overlay.

                    drawNavigationSource(canvas)

                    drawSelection(canvas, dark)

                } else {

                    drawSelection(canvas, dark)

                    val customForeground = appNavigationState?.drawForeground(
                        canvas,
                        adapterNavigationFrame(1f),
                        foregroundNavigationClipPath(),
                    ) { drawNavigationSource(canvas) } == true
                    if (!customForeground) {
                        drawNavigationSource(canvas)
                    }

                }

                restoreBurnInOffset(canvas, burnInLayer)

                canvas.restoreToCount(panelScaleLayer)

                canvas.restore()

            } finally {

                surface.holder.unlockCanvasAndPost(canvas)

                if (appNavigationState?.onOpticalSurfaceFrameRendered() == true) postInvalidateOnAnimation()

            }

        }.onFailure { error ->

            Log.e(DIAGNOSTIC_TAG, "Optical surface render failed", error)

        }

        }

    }








    /**

     * Meco's regular WebView has no independently copyable Surface. HyperOS'

     * compositor blur is therefore kept as a rounded, non-interactive sibling

     * behind the glass only on the promotion tab. It samples the already

     * composed page in real time and never asks Meco to paint a second frame.

     */

    private fun updateOpticalSurfaceNativeBlur(enabled: Boolean) {

        if (targetAdapter?.usesNativeOpticalBlur != true) return

        val content = parent as? FrameLayout ?: return

        var blur = opticalSurfaceNativeBlurView

        if (blur == null) {

            blur = View(context).apply {

                isClickable = false

                isFocusable = false

                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

                background = GradientDrawable().apply {

                    shape = GradientDrawable.RECTANGLE

                    cornerRadius = height * (config.cornerRadiusPercent / 100f)

                    setColor(Color.TRANSPARENT)

                }

                outlineProvider = BottomBarSquircleOutlineProvider(

                    radius = { outerCornerRadiusPx() },

                    smoothing = { config.cornerSmoothing },

                )

                clipToOutline = true

                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                visibility = View.INVISIBLE

            }

            val hostIndex = content.indexOfChild(this).coerceAtLeast(0)

            content.addView(blur, hostIndex, copyHostLayoutParams())

            opticalSurfaceNativeBlurView = blur

        }

        var indicator = opticalSurfaceNativeIndicatorView

        if (indicator == null) {

            indicator = View(context).apply {

                isClickable = false

                isFocusable = false

                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

                background = GradientDrawable().apply {

                    shape = GradientDrawable.RECTANGLE

                    cornerRadius = height * (config.cornerRadiusPercent / 100f)

                    setColor(Color.TRANSPARENT)

                }

                outlineProvider = BottomBarSquircleOutlineProvider(

                    radius = { height * (config.cornerRadiusPercent / 100f) },

                    smoothing = { config.cornerSmoothing },

                )

                clipToOutline = true

                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                visibility = View.INVISIBLE

            }

            // The live indicator belongs above the full-bar compositor blur,

            // but below this host's static icons and lighting.

            val hostIndex = content.indexOfChild(this).coerceAtLeast(0)

            content.addView(

                indicator,

                hostIndex,

                FrameLayout.LayoutParams(1, 1).apply {

                    gravity = android.view.Gravity.NO_GRAVITY

                },

            )

            opticalSurfaceNativeIndicatorView = indicator

        }

        syncOpticalSurfaceBlurLayout(blur)

        appNavigationState?.syncNativeSurfaceOuterEffect(
            blur, width, height, config.blurRadius,
            config.cornerRadiusPercent, density,
        ) { createRuntimeShader(ROUNDED_RECT_REFRACTION_SHADER, "adapter-native-outer-retry") }

        if (enabled && !opticalSurfaceNativeBlurActive) {

            opticalSurfaceNativeBlurActive = enableOpticalSurfaceNativeBlur(blur)

            blur.visibility = if (opticalSurfaceNativeBlurActive) View.VISIBLE else View.INVISIBLE

        } else if (!enabled && opticalSurfaceNativeBlurActive) {

            disableOpticalSurfaceNativeBlur(blur)

            opticalSurfaceNativeBlurActive = false

            blur.visibility = View.INVISIBLE

        }

        if (enabled && opticalSurfaceNativeBlurActive && !opticalSurfaceNativeIndicatorActive) {

            opticalSurfaceNativeIndicatorActive = enableOpticalSurfaceNativeBlur(indicator, 0.62f)

            indicator.visibility =

                if (opticalSurfaceNativeIndicatorActive) View.VISIBLE else View.INVISIBLE

        } else if ((!enabled || !opticalSurfaceNativeBlurActive) && opticalSurfaceNativeIndicatorActive) {

            disableOpticalSurfaceNativeBlur(indicator)

            indicator.setRenderEffect(null)

            opticalSurfaceNativeIndicatorActive = false

            opticalSurfaceNativeIndicatorEffectSignature = Long.MIN_VALUE

            indicator.visibility = View.INVISIBLE

        }

    }



    /**

     * Positions a second compositor-backed blur layer under the animated pill.

     * Unlike the old WebView bitmap, this layer is sampled by SurfaceFlinger on

     * every frame, so its refraction can never reveal historical page content.

     */

    private fun syncOpticalSurfaceNativeIndicator(

        centerX: Float,

        centerY: Float,

        baseHalfWidth: Float,

        baseHalfHeight: Float,

        scaleX: Float,

        scaleY: Float,

        radius: Float,

    ) {

        val indicator = opticalSurfaceNativeIndicatorView ?: return

        if (!usesOpticalSurfaceHybridBackdrop() || !opticalSurfaceNativeIndicatorActive) {

            indicator.visibility = View.INVISIBLE

            return

        }

        val baseWidth = (baseHalfWidth * 2f).roundToInt().coerceAtLeast(1)

        val baseHeight = (baseHalfHeight * 2f).roundToInt().coerceAtLeast(1)

        val params = indicator.layoutParams as? FrameLayout.LayoutParams ?: return

        if (params.width != baseWidth || params.height != baseHeight) {

            params.width = baseWidth

            params.height = baseHeight

            indicator.layoutParams = params

        }

        indicator.pivotX = baseWidth / 2f

        indicator.pivotY = baseHeight / 2f

        val hostPivotX = width * 0.5f

        val hostPivotY = height.toFloat()

        val transformedCenterX = hostPivotX + (centerX - hostPivotX) * this.scaleX

        val transformedCenterY = hostPivotY + (centerY - hostPivotY) * this.scaleY + translationY

        indicator.x = x + transformedCenterX - baseHalfWidth

        indicator.y = y + transformedCenterY - baseHalfHeight

        indicator.scaleX = scaleX * this.scaleX

        indicator.scaleY = scaleY * this.scaleY

        indicator.alpha = alpha

        indicator.visibility = View.VISIBLE



        val shader = opticalSurfaceNativeIndicatorShader ?: return

        val effectSignature =

            (baseWidth.toLong() shl 32) xor

                (baseHeight.toLong() shl 16) xor

                (pressProgress * 100f).roundToInt().toLong() xor

                ((radius * 100f).roundToInt().toLong() shl 8)

        if (effectSignature == opticalSurfaceNativeIndicatorEffectSignature) return

        opticalSurfaceNativeIndicatorEffectSignature = effectSignature

        val lensProgress = pressProgress.coerceAtLeast(0.72f)

        shader.setFloatUniform("size", baseWidth.toFloat(), baseHeight.toFloat())

        shader.setFloatUniform("offset", 0f, 0f)

        shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)

        shader.setFloatUniform(

            "refractionHeight",

            config.refractionHeightDp *

                lerp(
                    INDICATOR_LENS_HYBRID_HEIGHT_MIN_RATIO,
                    INDICATOR_LENS_HYBRID_HEIGHT_MAX_RATIO,
                    pressProgress,
                ) * density,

        )

        shader.setFloatUniform(

            "refractionAmount",

            -config.refractionStrengthDp *

                lerp(
                    INDICATOR_LENS_COMPOSITOR_AMOUNT_MIN_RATIO,
                    INDICATOR_LENS_COMPOSITOR_AMOUNT_MAX_RATIO,
                    pressProgress,
                ) * density * lensProgress,

        )

        shader.setFloatUniform("depthEffect", 1f)

        shader.setFloatUniform("chromaticAberration", 0.85f)

        indicator.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))

        indicator.invalidate()

    }



    /**

     * JD cannot expose its custom feed RenderNode to a second scene without

     * making cards flicker. Distort the compositor-native blur sheet itself;

     * this restores the same rounded edge refraction without touching JD's

     * display list or invoking root.draw().

     */




    private fun copyHostLayoutParams(): FrameLayout.LayoutParams {

        val source = layoutParams as? FrameLayout.LayoutParams

        return FrameLayout.LayoutParams(

            source?.width ?: width.coerceAtLeast(1),

            source?.height ?: height.coerceAtLeast(1),

        ).apply {

            gravity = source?.gravity ?: android.view.Gravity.NO_GRAVITY

            leftMargin = source?.leftMargin ?: 0

            topMargin = source?.topMargin ?: 0

            rightMargin = source?.rightMargin ?: 0

            bottomMargin = source?.bottomMargin ?: 0

        }

    }



    private fun syncOpticalSurfaceBlurLayout(blur: View) {

        val host = layoutParams as? FrameLayout.LayoutParams ?: return

        val target = blur.layoutParams as? FrameLayout.LayoutParams ?: copyHostLayoutParams()

        if (target.width == host.width && target.height == host.height &&

            target.gravity == host.gravity && target.leftMargin == host.leftMargin &&

            target.topMargin == host.topMargin && target.rightMargin == host.rightMargin &&

            target.bottomMargin == host.bottomMargin

        ) return

        target.width = host.width

        target.height = host.height

        target.gravity = host.gravity

        target.leftMargin = host.leftMargin

        target.topMargin = host.topMargin

        target.rightMargin = host.rightMargin

        target.bottomMargin = host.bottomMargin

        blur.layoutParams = target

    }



    private fun enableOpticalSurfaceNativeBlur(view: View, radiusScale: Float = 1f): Boolean {

        fun call(name: String, vararg args: Any): Boolean = runCatching {

            Xposed.callMethod(view, name, *args)

            true

        }.getOrDefault(false)

        val baseRadius = if (isDarkGlass()) {

            (config.blurRadius * 4f).toInt().coerceIn(1, 36)

        } else {

            (config.blurRadius * 2.75f).toInt().coerceIn(1, 24)

        }

        val radius = (baseRadius * radiusScale).roundToInt().coerceAtLeast(1)

        val mode = call("setMiBackgroundBlurMode", 1)

        val blurRadius = call("setMiBackgroundBlurRadius", radius)

        val viewMode = call("setMiViewBlurMode", 1)

        runCatching { Xposed.callMethod(view, "clearMiBackgroundBlendColor") }

        view.invalidate()

        return mode && blurRadius && viewMode

    }



internal fun disableOpticalSurfaceNativeBlur(view: View) {

        runCatching { Xposed.callMethod(view, "clearMiBackgroundBlendColor") }

        runCatching { Xposed.callMethod(view, "setMiBackgroundBlurRadius", 0) }

        runCatching { Xposed.callMethod(view, "setMiViewBlurMode", 0) }

        runCatching { Xposed.callMethod(view, "setMiBackgroundBlurMode", 0) }

    }



    /**

     * QQ rebuilds QQBlurViewWrapper after a light/dark switch. The installer

     * can only hide the instance that existed at install time, so keep probing

     * for the replacement and suppress only this QQ-owned bottom chrome.

     */

    private fun suppressNativeQqBottomChrome(source: ViewGroup?, forceProbe: Boolean = false) {

        if (context.packageName != QQ_PACKAGE || source == null) return

        if (source.alpha != 0f) source.alpha = 0f

        source.background = null

        source.backgroundTintList = null



        val iterator = qqRecreatedBottomChrome.iterator()

        while (iterator.hasNext()) {

            val view = iterator.next()

            if (!view.isAttachedToWindow) {

                iterator.remove()

            } else {

                view.visibility = View.INVISIBLE

                view.alpha = 0f

                view.background = null

                view.backgroundTintList = null

            }

        }



        val now = SystemClock.uptimeMillis()

        if (!forceProbe && now - lastQqBottomChromeProbe < QQ_BOTTOM_CHROME_PROBE_INTERVAL_MS) return

        lastQqBottomChromeProbe = now

        val root = rootView

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += root to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view.javaClass.name == QQ_BOTTOM_BLUR_CLASS) {

                view.visibility = View.INVISIBLE

                view.alpha = 0f

                view.background = null

                view.backgroundTintList = null

                if (view !in qqRecreatedBottomChrome) qqRecreatedBottomChrome += view

            }

            if (view is ViewGroup && depth < 14) {

                for (index in view.childCount - 1 downTo 0) {

                    stack += view.getChildAt(index) to (depth + 1)

                }

            }

        }

    }



    /** Static QQ/Bilibili renderers never expose their native animated row. */

    private fun suppressNativeRedrawnBottomBar(source: ViewGroup?) {

        if (source == null) return

        if (!suppressesNativeChrome()) return

        val usesStaticRenderer =

            (context.packageName == QQ_PACKAGE && hasQqStaticIcons()) ||

                targetAdapter?.ownsNavigationDrawing == true ||

                targetAdapter?.hideRedrawnNavigationSource == true ||

                context.packageName == MEITUAN_TAKEOUT_PACKAGE ||

                targetAdapter?.usesOpticalSurfacePipeline == true ||

                targetAdapter?.ownsNavigationDrawing == true

        if (!usesStaticRenderer) return

        if (appNavigationState?.suppressRedrawnSource() == false) return

        if (source.alpha != 0f) source.alpha = 0f

        source.background = null

    }



    /**

     * NetEase leaves a translucent/gradient shell behind its hidden native

     * NavigationTabLayout. Clear only the bottom-chrome-sized branch: making

     * the whole Activity ancestor transparent would also erase page surfaces.

     */











    private fun drawIndicatorInnerShadow(canvas: Canvas, radius: Float) {

        val progress = pressProgress.coerceIn(0f, 1f)

        if (progress <= 0.001f || !canvas.isHardwareAccelerated) return

        val blurRadius = 8f * density * progress

        selectionPath.setBottomBarSquircle(selectionRect, radius, config.cornerSmoothing)

        runCatching {

            indicatorShadowNode.setPosition(0, 0, width, height)

            val recording = indicatorShadowNode.beginRecording(width, height)

            val layer = recording.saveLayer(selectionRect, null)

            lightingPaint.blendMode = BlendMode.SRC_OVER

            lightingPaint.color = Color.argb((0.15f * 255f).toInt(), 0, 0, 0)

            recording.drawPath(selectionPath, lightingPaint)

            recording.save()

            recording.translate(0f, blurRadius)

            lightingPaint.blendMode = BlendMode.CLEAR

            recording.drawPath(selectionPath, lightingPaint)

            recording.restore()

            recording.restoreToCount(layer)

            lightingPaint.blendMode = null

            indicatorShadowNode.endRecording()

            indicatorShadowNode.setAlpha(progress)

            if (innerShadowBlurRadius != blurRadius || innerShadowBlurEffect == null) {

                innerShadowBlurEffect = RenderEffect.createBlurEffect(

                    blurRadius, blurRadius, Shader.TileMode.DECAL,

                )

                innerShadowBlurRadius = blurRadius

            }

            indicatorShadowNode.setRenderEffect(

                innerShadowBlurEffect,

            )

            canvas.save()

            canvas.clipPath(selectionPath)

            canvas.drawRenderNode(indicatorShadowNode)

            canvas.restore()

        }

    }



    /**

     * Native selected-pill lighting pass.

     *

     * The reference draws four clipped rounded rectangles: a very faint white

     * lift, a soft black inset, then blue and pink additive caustics moving in

     * opposite directions as the pill is pressed.

     */

    private fun drawLiquidIndicatorLighting(canvas: Canvas, dark: Boolean, radius: Float) {

        val progress = pressProgress.coerceIn(0f, 1f)

        selectionPath.setBottomBarSquircle(selectionRect, radius, config.cornerSmoothing)

        canvas.save()

        canvas.clipPath(selectionPath)



        lightingPaint.shader = null

        lightingPaint.blendMode = BlendMode.PLUS

        val liftAlpha = if (dark) {

            0.012f + 0.024f * progress

        } else {

            0.008f + 0.022f * progress

        }

        lightingPaint.color = Color.argb((liftAlpha * 255f).toInt(), 255, 255, 255)

        canvas.drawPath(selectionPath, lightingPaint)



        // This is a property of the glass component, not an app adapter:

        // a light bar gets a light pill and a dark bar gets a dark pill.

        val blackAlpha = if (dark) {

            0.13f + 0.14f * progress

        } else {

            0.04f + 0.08f * progress

        }

        val blackBlur = lerp(0.55f, 0.9f, progress) * density

        lightingPaint.blendMode = BlendMode.SRC_OVER

        lightingPaint.color = Color.argb((blackAlpha * 255f).toInt(), 0, 0, 0)

        if (canvas.isHardwareAccelerated) {

            indicatorShadowNode.setPosition(0, 0, width, height)

            perfNodeRecords++

            val shadowCanvas = indicatorShadowNode.beginRecording(width, height)

            shadowCanvas.drawPath(selectionPath, lightingPaint)

            indicatorShadowNode.endRecording()

            indicatorShadowNode.setRenderEffect(

                opticalLightingBlur(0, blackBlur),

            )

            canvas.drawRenderNode(indicatorShadowNode)

        } else {

            canvas.drawPath(selectionPath, lightingPaint)

        }



        val colorBlur = lerp(0.3f, 0.6f, progress) * density

        val offset = lerp(0.25f, 1.1f, progress) * density

        val negativeOffset = -lerp(0.15f, 0.55f, progress) * density

        lightingPaint.blendMode = BlendMode.PLUS

        val causticsCanvas = if (canvas.isHardwareAccelerated) {

            indicatorCausticsNode.setPosition(0, 0, width, height)

            perfNodeRecords++

            indicatorCausticsNode.beginRecording(width, height)

        } else {

            canvas

        }

        val blueAlpha = 0.035f * progress + if (dark) 0.025f else 0.018f

        lightingPaint.color = Color.argb((blueAlpha * 255f).toInt(), 0x64, 0xC8, 0xFF)

        lightingRect.set(selectionRect)

        lightingRect.offset(-offset, negativeOffset)

        lightingPath.setBottomBarSquircle(lightingRect, radius, config.cornerSmoothing)

        causticsCanvas.drawPath(lightingPath, lightingPaint)



        val pinkAlpha = 0.03f * progress + if (dark) 0.020f else 0.016f

        lightingPaint.color = Color.argb((pinkAlpha * 255f).toInt(), 0xFF, 0xA0, 0xD8)

        lightingRect.set(selectionRect)

        lightingRect.offset(offset * 0.9f, negativeOffset * 0.5f)

        lightingPath.setBottomBarSquircle(lightingRect, radius, config.cornerSmoothing)

        causticsCanvas.drawPath(lightingPath, lightingPaint)

        if (canvas.isHardwareAccelerated) {

            indicatorCausticsNode.endRecording()

            indicatorCausticsNode.setRenderEffect(

                opticalLightingBlur(1, colorBlur),

            )

            canvas.drawRenderNode(indicatorCausticsNode)

        }



        lightingPaint.blendMode = null

        canvas.restore()

    }



    private fun opticalLightingBlur(slot: Int, radius: Float): RenderEffect {

        if (opticalLightingBlurRadii[slot] == radius) {

            opticalLightingBlurEffects[slot]?.let { return it }

        }

        val effect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)

        opticalLightingBlurRadii[slot] = radius

        opticalLightingBlurEffects[slot] = effect

        return effect

    }



    private fun drawInteractiveHighlight(canvas: Canvas) {

        val progress = interactiveHighlightProgress

        if (progress <= 0.001f) return

        fillPaint.shader = null

        fillPaint.blendMode = BlendMode.PLUS

        fillPaint.color = Color.argb((0.06f * progress * 255f).toInt(), 255, 255, 255)

        canvas.drawRect(outerRect, fillPaint)

        fillPaint.shader = RadialGradient(

            (navigationSlotBandLeftPx() + (indicatorPosition + 0.5f) * tabWidthPx() + translationX)

                .coerceIn(0f, width.toFloat()),

            height / 2f,

            height * 1.2f,

            Color.argb((0.12f * progress * 255f).toInt(), 255, 255, 255),

            Color.TRANSPARENT,

            Shader.TileMode.CLAMP,

        )

        canvas.drawRect(outerRect, fillPaint)

        fillPaint.shader = null

        fillPaint.blendMode = null

    }



    private fun drawBloomStroke(

        canvas: Canvas,

        target: RectF,

        radius: Float,

        alpha: Float,

        rotation: Float,

        shader: RuntimeShader?,

    ) {

        if (alpha <= 0f || target.isEmpty || shader == null || !canvas.isHardwareAccelerated) return

        runCatching {

            val width = target.width()

            val height = target.height()

            val halfWidth = width / 2f

            val halfHeight = height / 2f

            shader.setFloatUniform("halfView", halfWidth, halfHeight)

            shader.setFloatUniform("highlightAlpha", alpha)

            shader.setColorUniform("strokeColor", Color.WHITE)

            shader.setFloatUniform("strokeAlphaMul", 0.12f)

            setBloomLight(shader, "1", rotation, primary = true)

            setBloomLight(shader, "2", rotation, primary = false)



            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)

            setBloomGlow(shader)

            bloomPaint.shader = shader

            bloomPaint.style = Paint.Style.FILL

            lightingRect.set(0f, 0f, width, height)

            lightingPath.setBottomBarSquircle(lightingRect, radius, config.cornerSmoothing)

            canvas.save()

            canvas.translate(target.left, target.top)

            canvas.drawPath(lightingPath, bloomPaint)

            drawBloomGlow(canvas, lightingPath, shader, alpha)

            canvas.restore()

            bloomPaint.shader = null

        }

    }



    /**
     * Highlight stroke along an arbitrary panel path. A two-shape panel outline cannot
     * be rebuilt from a single rect, so the path is stroked directly in host coordinates
     * while the shader still measures itself against the whole host.
     */
    private fun drawBloomStrokePath(

        canvas: Canvas,

        path: Path,

        alpha: Float,

        rotation: Float,

        shader: RuntimeShader?,

    ) {

        if (alpha <= 0f || path.isEmpty || shader == null || !canvas.isHardwareAccelerated) return

        runCatching {

            shader.setFloatUniform("halfView", width / 2f, height / 2f)

            shader.setFloatUniform("highlightAlpha", alpha)

            shader.setColorUniform("strokeColor", Color.WHITE)

            shader.setFloatUniform("strokeAlphaMul", 0.12f)

            setBloomLight(shader, "1", rotation, primary = true)

            setBloomLight(shader, "2", rotation, primary = false)

            // The panel path is the single geometry source: it already describes the
            // squircle capsule, or the pill plus detached action circles. Stroking it keeps
            // the 1dp core identical whether the side keys are shown or hidden; the old SDF
            // fill measured its core inward, so hiding both keys doubled the visible line
            // width, and it also used a plain rounded rect against a squircle path.
            shader.setFloatUniform("edgeProfile", 0f)

            bloomPaint.shader = shader

            bloomPaint.style = Paint.Style.STROKE

            bloomPaint.strokeWidth = BLOOM_CORE_DP * density

            canvas.drawPath(path, bloomPaint)

            bloomPaint.shader = null

            drawBloomGlow(canvas, path, shader, alpha)

        }

    }

    /**
     * The edge profile the bloom shader draws: a [BLOOM_CORE_DP]-wide antialiased core on the
     * rim plus an inward glow of [BLOOM_GLOW_DP] dp at [BLOOM_GLOW_STRENGTH]. Both are baked into
     * the shader, so the highlight no longer depends on a stroked hairline.
     */
    private fun setBloomGlow(shader: RuntimeShader) {

        shader.setFloatUniform("strokeWidthPx", BLOOM_CORE_DP * density)

        shader.setFloatUniform("glowPx", BLOOM_GLOW_DP * density)

        shader.setFloatUniform("glowStrength", 0f)

        shader.setFloatUniform("edgeProfile", 1f)

    }

    /**
     * The inward glow: the same lit path redrawn a few times, each pass wider and dimmer, so light
     * bleeds from the rim toward the middle. Stacked strokes rather than a shader SDF keep it
     * correct for a panel made of a pill plus a detached circle.
     */
    private fun drawBloomGlow(canvas: Canvas, path: Path, shader: RuntimeShader, alpha: Float) {

        val strength = config.bloomGlowStrength

        val spread = config.bloomGlowDp * density

        if (strength <= 0.001f || spread <= 0.5f || !canvas.isHardwareAccelerated) return

        // Stacked strokes: the same lit rim redrawn [passes] times, each a little wider and
        // dimmer. With a dozen passes the width steps are under a pixel and the cumulative
        // profile reads as a continuous falloff, unlike the four-pass version that banded.
        // (A blurred RenderNode would be smoother still but failed silently in the field.)
        val passes = 12

        shader.setFloatUniform("edgeProfile", 0f)

        for (i in 1..passes) {

            val t = i / passes.toFloat()

            shader.setFloatUniform("highlightAlpha", alpha * strength * (1f - t) * 2f / passes)

            bloomPaint.shader = shader

            bloomPaint.style = Paint.Style.STROKE

            bloomPaint.strokeWidth = BLOOM_CORE_DP * density + spread * t

            canvas.drawPath(path, bloomPaint)

        }

        bloomPaint.shader = null

        bloomPaint.style = Paint.Style.FILL

        shader.setFloatUniform("highlightAlpha", alpha)

        shader.setFloatUniform("edgeProfile", 1f)

    }

    private fun setBloomLight(shader: RuntimeShader, suffix: String, rotation: Float, primary: Boolean) {

        val dx: Float

        val dy: Float

        val dz: Float

        val intensity: Float

        if (primary) {

            // The primary light follows the screen-plane gravity direction

            // (KernelSU liquid-bar / miuix-blur rememberDeviceTilt port); a

            // near-flat device keeps the upright (0, -1) reference. Each

            // highlight then rotates only its primary light (-45 degrees for

            // the panel, +90 for the pill).

            var gx = BloomTiltTracker.gravityX

            var gy = BloomTiltTracker.gravityY

            when (displayRotationCompat()) {

                Surface.ROTATION_90 -> { val remap = gx; gx = -gy; gy = remap }

                Surface.ROTATION_180 -> { gx = -gx; gy = -gy }

                Surface.ROTATION_270 -> { val remap = gx; gx = gy; gy = -remap }

            }

            val gMagSq = gx * gx + gy * gy

            val lx0: Float

            val ly0: Float

            if (gMagSq > BloomTiltTracker.GRAVITY_DIR_THRESHOLD_SQ) {

                val invMag = 1f / sqrt(gMagSq)

                lx0 = gx * invMag

                ly0 = gy * invMag

            } else {

                lx0 = 0f

                ly0 = -1f

            }

            val radians = Math.toRadians(rotation.toDouble())

            val c = cos(radians).toFloat()

            val s = sin(radians).toFloat()

            dx = c * lx0 - s * ly0

            dy = s * lx0 + c * ly0

            dz = -0.05f

            intensity = 1f

        } else {

            dx = 0f

            dy = 0.1f

            dz = -0.5f

            intensity = 0.4f

        }

        val length = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.000001f)

        shader.setFloatUniform("lightDir$suffix", dx / length, dy / length, dz / length)

        shader.setColorUniform("lightColor$suffix", Color.WHITE)

        shader.setFloatUniform("lightIntensity$suffix", intensity)

    }



    private fun displayRotationCompat(): Int =

        runCatching { display?.rotation ?: Surface.ROTATION_0 }.getOrDefault(Surface.ROTATION_0)



internal fun surfaceContainerColor(dark: Boolean): Int {

        val alpha = ((if (dark) config.darkAlpha else config.lightAlpha) * 255f)

            .toInt().coerceIn(0, 255)

        return if (dark) Color.argb(alpha, 33, 31, 38) else Color.argb(alpha, 255, 255, 255)

    }



    private fun resolveSelectedFromViewState(): Int? {

        if (slotCount <= 0) return null

        if (targetAdapter?.reverseVisualSlotOrder == true) {
            // LiteTabBar's selected View flags can lag behind the visible page
            // on cold start. Amap opens Home; subsequent tab changes originate
            // from this host and keep selectedIndex in native cell order.
            return if (selectionInitialized) selectedIndex.coerceIn(0, slotCount - 1)
                else (slotCount - 1).coerceAtLeast(0)
        }

        // XHS fragment changes and the global show/hide animation can overlap.

        // Its native selected flags are stale for several draw frames (and in

        // some builds Store remains selected while Home is already visible).

        // The glass host is the sole hit target, so its own committed index is

        // the stable source of truth.  This prevents animation invalidations

        // from changing navigation state and keeps cold start on Home.

        if (targetAdapter?.usesCommittedSelection == true) {

            return selectedIndex.coerceIn(0, slotCount - 1)

        }

        // JD's hidden row can report two selected descendants at once and its

        // asynchronous callbacks arrive out of order during rapid switching.

        // The glass host is the only physical navigation target, so the latest

        // committed host index is the deterministic source of truth.

        appNavigationState?.resolveSelectedIndex(navigationSource, slotCount)?.let { return it }


        findSlotContainer()?.let { container ->

            var bestIndex = -1

            var bestScore = 0

            for (index in 0 until minOf(container.childCount, slotCount)) {

                if (index in nonSelectableIndices) continue

                val score = selectionStateScore(container.getChildAt(index))

                if (score > bestScore) {

                    bestScore = score

                    bestIndex = index

                }

            }

            return bestIndex.takeIf { it >= 0 }

        }



        val candidates = ArrayList<View>()

        val stack = ArrayDeque<Pair<View, Int>>()

        if (childCount > 0) stack += getChildAt(0) to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view.isShown && (view.isSelected || view.isActivated)) candidates += view

            if (view is ViewGroup && depth < 6) {

                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)

            }

        }

        val hostLocation = IntArray(2).also(::getLocationInWindow)

        return candidates

            .asSequence()

            .map { selected ->

                val viewLocation = IntArray(2).also(selected::getLocationInWindow)

                val center = viewLocation[0] - hostLocation[0] + selected.width / 2f

                val index = ((center - navigationSlotBandLeftPx()) / tabWidthPx()).toInt().coerceIn(0, slotCount - 1)

                Triple(index, selectionStateScore(selected), selected.width * selected.height)

            }

            .filter { (index) -> index !in nonSelectableIndices }

            .maxWithOrNull(compareBy<Triple<Int, Int, Int>> { it.second }.thenBy { it.third })

            ?.first

    }



    private fun selectionStateScore(root: View): Int {

        var score = 0

        // Recursion instead of ArrayDeque<Pair<View, Int>>: this runs once per slot per
        // dispatchDraw, and the deque version allocated one Pair plus a boxed Int for
        // every visited node —several hundred short-lived objects per frame, all of it
        // GC pressure on the UI thread. The walk is depth-limited to 6, so the call
        // stack is bounded and this costs nothing.
        fun visit(view: View, depth: Int) {

            if (view.isShown) {

                if (view.isSelected) score += 100 - depth.coerceAtMost(20)

                if (view.isActivated) score += 80 - depth.coerceAtMost(20)

                if (view.isPressed) score += 20 - depth.coerceAtMost(10)

            }

            if (view is ViewGroup && depth < 6) {

                for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)

            }

        }

        visit(root, 0)

        return score

    }



    internal fun navigationSlotForAdapter(index: Int): View? = findSlotContainer()?.getChildAtOrNull(index)

    /**
     * Cached result of [findSlotContainer].
     *
     * The lookup is a full depth-6 sweep of the navigation source and it runs from every
     * dispatchDraw —once for the selection resolution and again from any fallback path.
     * What it returns is the *container view*, which only changes when the source is
     * re-bound or detached (its children changing is fine, the container identity holds),
     * so one identity check plus an attached check is enough to re-validate it.
     */
    private var slotContainerRef: WeakReference<ViewGroup>? = null

    private var slotContainerSourceRef: WeakReference<ViewGroup>? = null

    private fun findSlotContainer(): ViewGroup? {

        slotContainerRef?.get()?.let { cached ->

            if (cached.isAttachedToWindow && slotContainerSourceRef?.get() === navigationSource) {

                return cached

            }

        }

        val resolved = resolveSlotContainer() ?: return null

        slotContainerRef = WeakReference(resolved)

        slotContainerSourceRef = WeakReference(navigationSource)

        return resolved

    }

    private fun resolveSlotContainer(): ViewGroup? {

        val root = navigationSource?.takeIf { it.isAttachedToWindow }

            ?: (getChildAtOrNull(0) as? ViewGroup)

        if (root != null) {

            val found = findSlotContainerFrom(root)

            if (found != null) return found

        }

        if (appNavigationState?.refreshNavigationSource(this) == true) {
            navigationSource?.let { refreshed ->
                findSlotContainerFrom(refreshed)?.let { return it }
            }
        }

        return null

    }



    private fun findSlotContainerFrom(root: ViewGroup): ViewGroup? {

        var best: ViewGroup? = null

        var bestDepth = Int.MAX_VALUE

        // Same reasoning as selectionStateScore: recursion keeps this walk allocation-free.
        // It is a full depth-6 sweep and it runs from every dispatchDraw.
        fun visit(group: ViewGroup, depth: Int) {

            if (group.childCount in (slotCount - 1).coerceAtLeast(2)..(slotCount + 1) && depth < bestDepth) {

                val laidOut = (0 until group.childCount).count {

                    val child = group.getChildAt(it)

                    child.width > 0 && child.height > 0

                }

                if (laidOut >= 2) {

                    best = group

                    bestDepth = depth

                }

            }

            if (depth < 6) {

                for (index in 0 until group.childCount) {

                    (group.getChildAt(index) as? ViewGroup)?.let { visit(it, depth + 1) }

                }

            }

        }

        visit(root, 0)

        return best

    }



    private fun removeCaptureLoop() {

        val root = rootView

        captureListener?.let { listener ->

            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(listener)

        }

        captureListener = null

        backdrop?.recycle()

        backdrop = null

    }



    private fun installCaptureLoop() {

        if (!config.backdropCapture) return

        if (captureListener != null) return

        val root = rootView

        captureListener = ViewTreeObserver.OnPreDrawListener {

            val now = SystemClock.uptimeMillis()

            // During a tap-driven page switch, let the native page transition
            // own the frame budget. Keep the already-rendered glass untouched;
            // only defer per-frame probing/capture preparation briefly.
            if (now < barAnimationPriorityUntil) return@OnPreDrawListener true

            if (targetAdapter?.usesDirectOpticalBackdropMode == true) {

                // JD composes its feeds from native Views AND H5/WebViews

                // (the 鏂板搧 page is a JDWebView-backed H5 surface; browse and

                // video also host independent media surfaces). A live RenderNode

                // alone cannot reach inside a WebView, so live sampling flashes

                // wherever H5 pixels are expected. Every JD page therefore uses

                // the compositor host path (worker PixelCopy + stable bitmap),

                // which reads WebView and native content alike.

                syncBackdropRendererMode()

                return@OnPreDrawListener true

            }


            val animating = touching || pressProgress > 0.001f ||

                abs(indicatorPosition - dragTarget) > 0.001f

            if (context.packageName != QQ_PACKAGE &&

                appNavigationState?.deferThemeDetection(now) != true

            ) updateDetectedContentTheme()

            // The pre-draw listener also owns lightweight page/theme state.
            appNavigationState?.onHostFrame(this)

            if (!compositorBlurBehind) {
                appNavigationState?.requestVideoBackdrop(this, currentPageAllowsNavigation())
            }


            if (!compositorBlurBehind && prepareLiveBackdropScene()) return@OnPreDrawListener true

            val opticalSurface = isOpticalSurfaceSelected()

            if (!opticalSurface) opticalSurfaceSnapshotReady = false

            if (opticalSurface) {

                if (opticalSurfaceSurfacePipelineActive) {

                    // The probe loop owns the copy cadence for the promotion

                    // tab; re-requesting here as well would double-count the

                    // vsync tick and break the cadence stride.

                    return@OnPreDrawListener true

                }

                // The regular Meco WebView has no independently copyable

                // Surface. Its promotion path is compositor-only: retaining a

                // software frame here is the sole source of the visible ghost.

                if (usesOpticalSurfaceHybridBackdrop()) {

                    // Still need to capture the backdrop for the glass

                    // effect to have content to work with.

                    if (backdrop == null || backdrop?.isRecycled == true) {

                        val captureRoot = appNavigationState?.resolveBackdropScene(this)
                            ?: (navigationSource?.parent as? View) ?: root

                        captureBackdrop(captureRoot)

                    }

                    return@OnPreDrawListener true

                }

                if (!animating &&

                    !opticalSurfacePixelCopyInFlight && isShown &&

                    width > 0 && height > 0 && now - lastCapture >= OPTICAL_SURFACE_CAPTURE_INTERVAL_MS

                ) {

                    lastCapture = now

                    val textureCopyStarted = captureOpticalSurfaceTextureBackdrop()

                    if (!textureCopyStarted && !opticalSurfaceSnapshotReady && !capturing) {

                        captureBackdrop(appNavigationState?.resolveBackdropScene(this)
                            ?: (navigationSource?.parent as? View) ?: root)

                        opticalSurfaceSnapshotReady = true

                        invalidate()

                    }

                }

                return@OnPreDrawListener true

            }

            // JD: with the compositor-readback pipeline active the PixelCopy

            // result owns the backdrop. The generic 96ms software capture

            // would re-draw the whole content tree (feed + WebView) on the UI

            // thread and swap in a differently-sized bitmap that fights the

            // surface pipeline.

            if (opticalSurfaceSurfacePipelineActive) return@OnPreDrawListener true

            val interval = if (backdrop == null) 0L else CAPTURE_IDLE_INTERVAL_MS

            if (!animating && !capturing && isShown &&

                width > 0 && height > 0 && now - lastCapture >= interval

            ) {

                lastCapture = now

                val captureSource = parent as? View

                captureBackdrop(captureSource ?: root)

            }

            true

        }.also { root.viewTreeObserver.addOnPreDrawListener(it) }

    }



    /**

     * Some app pages are rendered into a TextureView. Sampling the

     * WebView's RenderNode makes Meco draw the asynchronous page twice and is

     * the source of the former full-page flashing. PixelCopy reads only the

     * small texture strip behind the glass from the already composed surface,

     * so the existing refraction shaders remain live without repainting the

     * page or copying the whole screen.

     */

    private fun captureOpticalSurfaceTextureBackdrop(): Boolean {

        if (!isOpticalSurfaceSelected() || opticalSurfacePixelCopyInFlight) return false

        val retainedTexture = opticalSurfaceTextureView

            ?.takeIf { it.isAttachedToWindow && it.isAvailable && it.width > 0 && it.height > 0 }

        val now = SystemClock.uptimeMillis()

        val texture = retainedTexture ?: run {

            if (now - opticalSurfaceLastTextureProbe < OPTICAL_SURFACE_TEXTURE_PROBE_INTERVAL_MS) return false

            opticalSurfaceLastTextureProbe = now

            findOpticalSurfaceTextureView()?.also { opticalSurfaceTextureView = it }

        } ?: return false



        val textureLocation = IntArray(2).also(texture::getLocationInWindow)

        val hostLocation = IntArray(2).also(::getLocationInWindow)

        val desiredPadding = 24f * density

        val availablePadding = minOf(

            desiredPadding,

            (hostLocation[0] - textureLocation[0]).toFloat(),

            (textureLocation[0] + texture.width - hostLocation[0] - width).toFloat(),

            (hostLocation[1] - textureLocation[1]).toFloat(),

            (textureLocation[1] + texture.height - hostLocation[1] - height).toFloat(),

        ).coerceAtLeast(0f)

        if (abs(availablePadding - opticalSurfaceBackdropPadding) >= 1f) {

            opticalSurfaceBackdropPadding = availablePadding

            configureGlassEffects()

        }

        val padding = opticalSurfaceBackdropPadding.roundToInt()

        val sourceRect = Rect(

            hostLocation[0] - textureLocation[0] - padding,

            hostLocation[1] - textureLocation[1] - padding,

            hostLocation[0] - textureLocation[0] + width + padding,

            hostLocation[1] - textureLocation[1] + height + padding,

        )

        if (sourceRect.left < 0 || sourceRect.top < 0 ||

            sourceRect.right > texture.width || sourceRect.bottom > texture.height ||

            sourceRect.width() <= 0 || sourceRect.height() <= 0

        ) return false



        val targetWidth = max(1, sourceRect.width() / CAPTURE_DOWNSCALE.toInt())

        val targetHeight = max(1, sourceRect.height() / CAPTURE_DOWNSCALE.toInt())

        val target = opticalSurfacePixelCopyScratch

            ?.takeIf { !it.isRecycled && it.width == targetWidth && it.height == targetHeight }

            ?: Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

        opticalSurfacePixelCopyScratch = null

        val surface = runCatching { Surface(texture.surfaceTexture) }.getOrNull() ?: return false

        opticalSurfacePixelCopyInFlight = true

        return runCatching {

            PixelCopy.request(

                surface,

                sourceRect,

                target,

                { result ->

                    surface.release()

                    opticalSurfacePixelCopyInFlight = false

                    if (result == PixelCopy.SUCCESS && isAttachedToWindow && isOpticalSurfaceSelected()) {

                        val previous = backdrop

                        backdrop = target

                        opticalSurfaceSnapshotReady = true

                        if (previous != null && !previous.isRecycled &&

                            previous.width == target.width && previous.height == target.height

                        ) {

                            opticalSurfacePixelCopyScratch = previous

                        } else {

                            previous?.takeUnless(Bitmap::isRecycled)?.recycle()

                        }

                        postInvalidateOnAnimation()

                    } else {

                        opticalSurfacePixelCopyScratch = target

                    }

                },

                opticalSurfacePixelCopyHandler,

            )

            true

        }.getOrElse {

            opticalSurfacePixelCopyInFlight = false

            opticalSurfacePixelCopyScratch = target

            surface.release()

            false

        }

    }



    private fun findOpticalSurfaceTextureView(): TextureView? {

        val root = navigationSource?.parent as? ViewGroup ?: return null

        val stack = ArrayDeque<View>()

        stack += root

        while (stack.isNotEmpty()) {

            val view = stack.removeLast()

            if (view.javaClass == TextureView::class.java && view is TextureView &&

                view.visibility == View.VISIBLE && view.isAvailable &&

                view.width >= root.width * 0.9f && view.height >= root.height * 0.8f &&

                view.ancestorsAreVisible()

            ) return view

            if (view is ViewGroup) {

                for (index in 0 until view.childCount) stack += view.getChildAt(index)

            }

        }

        return null

    }



    private fun captureBackdrop(root: View) {
        // Check every software-capture entry, including the idle fallback.
        // GPU-only app images may throw while an otherwise ordinary View draws.
        if (appNavigationState?.requiresSampledBackdrop() == false ||
            appNavigationState?.allowsSoftwareBackdropCapture == false) return

        val padding = backdropPaddingPx()

        val captureWidth = width + padding * 2f

        val captureHeight = height + padding * 2f

        val scaledWidth = max(1, (captureWidth / CAPTURE_DOWNSCALE).toInt())

        val scaledHeight = max(1, (captureHeight / CAPTURE_DOWNSCALE).toInt())

        val target = backdrop?.takeIf { it.width == scaledWidth && it.height == scaledHeight && !it.isRecycled }

            ?: Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888).also {

                backdrop?.recycle()

                backdrop = it

            }



        val rootLocation = IntArray(2)

        val hostLocation = IntArray(2)

        root.getLocationInWindow(rootLocation)

        getLocationInWindow(hostLocation)

        val x = hostLocation[0] - rootLocation[0]

        val y = hostLocation[1] - rootLocation[1]

        capturing = true

        try {

            val bitmapCanvas = Canvas(target)

            bitmapCanvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)

            bitmapCanvas.scale(1f / CAPTURE_DOWNSCALE, 1f / CAPTURE_DOWNSCALE)

            bitmapCanvas.translate(-x.toFloat() + padding, -y.toFloat() + padding)

            root.draw(bitmapCanvas)

        } finally {

            capturing = false

        }

        // App-owned optical surfaces may need host traversal to refresh their
        // compositor snapshot while the page itself is static.
        if (targetAdapter?.needsContinuousOpticalSurfaceFrames(selectedIndex) != true) invalidate()

    }



    private fun isOpticalSurfaceSelected(): Boolean =

        targetAdapter?.isOpticalSurfaceTab(selectedIndex) == true



    private fun wantsRootSurfaceOpticalPipeline(): Boolean =

        targetAdapter?.usesDirectOpticalBackdropMode == true || isOpticalSurfaceSelected() ||

            context.packageName == MEITUAN_TAKEOUT_PACKAGE





    /** New Products must not reference its texture-backed RenderNodes from a

     *  second scene. MIUI native background blur also makes those textures

     *  enter a second compositor blur pass and visibly flash, so this page

     *  uses the narrow, downscaled, demand-sampled Surface path instead. */

    internal fun disableOpticalSurfaceNativeBlurForAdapter() = updateOpticalSurfaceNativeBlur(false)
    internal fun enableOpticalSurfaceRendererForAdapter() =
        updateOpticalSurfaceSurfaceRenderer(config.backdropCapture)
    internal fun disableOpticalSurfaceRendererForAdapter() = updateOpticalSurfaceSurfaceRenderer(false)
    internal fun clearLiveBackdropForAdapter() {
        liveBackdropActive = false
        liveBackdropSignature = Long.MIN_VALUE
        sharedOpticalBlurPrepared = false
    }
    /** Discard an adapter's previous optical frame after its page changes. */
    internal fun invalidateOpticalBackdropForAdapter() {
        backdrop?.takeUnless(Bitmap::isRecycled)?.recycle()
        backdrop = null
        opticalSurfaceSnapshotReady = false
        opticalSurfaceTextureView = null
        clearLiveBackdropForAdapter()
        postInvalidateOnAnimation()
    }

    private fun syncBackdropRendererMode() {
        if (appNavigationState?.updateBackdropRendererMode(this) == true) return
        if (targetAdapter?.usesDirectOpticalBackdropMode == true) updateOpticalSurfaceSurfaceRenderer(true)
    }



    /** Tabs served by the demand-sampled probe loop: copies are driven solely

     *  by scheduleCompositorSurfaceProbe() and delivered on the dedicated

     *  compositor thread, never requeued from the copy callback. */

    private fun usesCompositorSurfaceProbe(): Boolean =

        context.packageName == MEITUAN_TAKEOUT_PACKAGE ||

            targetAdapter?.usesDirectOpticalBackdropMode == true ||

            isOpticalSurfaceSelected()



    private fun usesOpticalSurfaceHybridBackdrop(): Boolean =

        opticalSurfaceNativeBlurActive && opticalSurfaceTextureView == null &&

            (isOpticalSurfaceSelected() || targetAdapter?.usesDirectOpticalBackdropMode == true)



    private fun backdropPaddingPx(): Float {
        // Blur needs real neighbouring pixels, not repeated CLAMP edge pixels.
        // Reserve three radii so a moving high-contrast edge outside the bar
        // enters the filter gradually even at the maximum configured radius.
        val radius = glassBlurRadiusPx()
        val filterPadding = io.github.xzdw.dylt.rendering.NativeBackdropBlur.padding(radius, 24f * density)
        return if (isOpticalSurfaceSelected() && opticalSurfaceBackdropPadding > 0f) {
            // Surface samples publish their own rectangle and padding together;
            // do not change its geometry without requesting a matching sample.
            opticalSurfaceBackdropPadding
        } else {
            filterPadding
        }
    }



    private fun updateDetectedContentTheme() {

        if (config.themeMode != GlassConfig.THEME_SYSTEM) return

        val now = SystemClock.uptimeMillis()

        val probeInterval = targetAdapter?.themeProbeIntervalMs ?: THEME_PROBE_INTERVAL_MS

        // The interval alone gates this probe.
        //
        // It used to also require `detectedContentDark != null`, but that field is only
        // assigned when the source has readable text (see the `luminances.isNotEmpty()`
        // branch at the end). A source without any —an icon-only row, a page that has not
        // bound its labels yet —therefore left it null forever and this guard never
        // engaged, so the BFS below ran on every display frame instead of twice a second.
        if (now - lastThemeProbe < probeInterval) return

        lastThemeProbe = now

        val source = navigationSource ?: return

        val luminances = ArrayList<Float>(slotCount)

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += source to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view.visibility != View.VISIBLE) continue

            if (view is TextView) {

                val label = view.text?.toString().orEmpty()

                if (label.any(Char::isLetter)) {

                    val color = view.currentTextColor

                    if (Color.alpha(color) > 32) {

                        luminances += (

                            0.2126f * Color.red(color) +

                                0.7152f * Color.green(color) +

                                0.0722f * Color.blue(color)

                            )

                    }

                }

            }

            if (view is ViewGroup && depth < 6) {

                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)

            }

        }

        if (luminances.isNotEmpty()) {

            luminances.sort()

            detectedContentDark = luminances[luminances.size / 2] >= DARK_LABEL_LUMINANCE

        }

    }



internal fun isDarkGlass(): Boolean {

        appNavigationState?.forceDarkMode(selectedIndex)?.let { return it }

        if (targetAdapter?.forceLightMode == true || context.packageName == MEITUAN_TAKEOUT_PACKAGE) return false

        return when (config.themeMode) {

        GlassConfig.THEME_LIGHT -> false

        GlassConfig.THEME_DARK -> true

        else -> appNavigationState?.resolveAutoDarkMode(this) ?: if (

            targetAdapter?.usesContentDarkMode == true ||

            targetAdapter?.hideRedrawnNavigationSource == true

        ) {

            detectedContentDark ?: systemNightMode()

        } else {

            systemNightMode()

        }

    }

    }



    /**

     * One dark switch drives Weibo's glass fill, icon set and label colors 锟?

     * the same chain bilibili uses. In-process uiMode signals cover the normal

     * day/night flips; under HyperOS force-dark Weibo's process stays on the

     * day configuration, and the module-side provider's live device state

     * ([GlassConfig.systemDark]) is the signal that still reports dark.

     */
    internal fun adapterContentOrSystemDark(): Boolean =
        systemNightMode() || config.systemDark || detectedContentDark == true

    /** Memo for [systemNightMode]; see there. */
    private var lastNightModeAt = 0L

    private var lastNightModeResult = false

    private val nightModeTtlMs = 1000L

    /**
     * Day/night state, memoised for [nightModeTtlMs].
     *
     * Steps 1 and 2 below are cheap in-memory configuration reads, but step 4 is
     * `Settings.Global.getInt` —a ContentProvider query, i.e. a Binder round-trip. It is
     * reached on every call in light mode, because the earlier steps only *return early* on
     * dark.
     *
     * For this adapter the whole chain in [isDarkGlass] falls through: `forceDarkMode` and
     * `resolveAutoDarkMode` are not overridden, `usesContentDarkMode` and
     * `hideRedrawnNavigationSource` default to false, and the default `themeMode` is
     * THEME_SYSTEM. So every `isDarkGlass()` call ended in that Binder query —four per
     * repaint per host, which is ~250 queries a second across the two bars.
     *
     * A one-second TTL keeps the bar correct through a day/night flip (a one-frame lag at
     * worst) while removing essentially all of those calls. It is deliberately a TTL rather
     * than a `uiMode` key: MIUI's force-dark lives in `Settings.Global["dark_mode"]`
     * independently of `uiMode`, so `uiMode` alone would not invalidate it.
     */
    private fun systemNightMode(): Boolean {

        val now = SystemClock.uptimeMillis()

        if (now - lastNightModeAt < nightModeTtlMs) return lastNightModeResult

        lastNightModeAt = now

        lastNightModeResult = computeSystemNightMode()

        return lastNightModeResult

    }

    private fun computeSystemNightMode(): Boolean {

        // 1. Check app's Configuration

        if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==

            Configuration.UI_MODE_NIGHT_YES) return true

        // 2. Check system resources (not affected by per-app overrides)

        if (android.content.res.Resources.getSystem().configuration.uiMode and

            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) return true

        // 3. Check UiModeManager (standard Android API)

        val umm = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager

        if (umm?.nightMode == android.app.UiModeManager.MODE_NIGHT_YES) return true

        // 4. MIUI fallback: dark mode stored in Settings.Global

        return try {

            android.provider.Settings.Global.getInt(

                context.contentResolver, "dark_mode", 0

            ) == 1

        } catch (e: Exception) {

            false

        }

    }



    private fun selectedNavigationAccent(): Int = when {

        config.customAccentEnabled -> config.customAccentColor

        targetAdapter?.accentColor != null -> targetAdapter!!.accentColor!!

        else -> KERNEL_SU_ACCENT_COLOR

    }



    private fun enableMiuiBackgroundBlur(): Boolean {

        fun call(name: String, vararg args: Any): Boolean = runCatching {

            Xposed.callMethod(this, name, *args)

            true

        }.getOrDefault(false)



        val dark = isDarkGlass()

        val mappedRadius = if (dark) {

            (config.blurRadius * 4f).toInt().coerceIn(1, 36)

        } else {

            (config.blurRadius * 2.75f).toInt().coerceIn(1, 24)

        }

        val viewMode = call("setMiViewBlurMode", 1)

        val backgroundMode = call("setMiBackgroundBlurMode", 1)

        val radius = call("setMiBackgroundBlurRadius", mappedRadius)

        return viewMode && backgroundMode && radius

    }



    private fun disableMiuiBackgroundBlur() {

        if (!nativeBlurActive) return

        runCatching { Xposed.callMethod(this, "setMiBackgroundBlurMode", 0) }

        runCatching { Xposed.callMethod(this, "setMiViewBlurMode", 0) }

        nativeBlurActive = false

    }



    private fun ViewGroup.getChildAtOrNull(index: Int): View? =

        if (index in 0 until childCount) getChildAt(index) else null



    private fun View.ancestorsAreVisible(): Boolean {

        var current: View? = this

        repeat(16) {

            val view = current ?: return true

            if (view.visibility != View.VISIBLE) return false

            current = view.parent as? View

        }

        return true

    }



    /**

     * Never stack MIUI's asynchronous window blur underneath our captured

     * backdrop pipeline. Both are valid alone, but they sample adjacent

     * compositor frames while a feed scrolls and the translucent result reads

     * as a vibrating/doubled background. The module RenderEffect retains the

     * configured blur and liquid refraction; native blur remains the fallback

     * when backdrop capture is explicitly disabled.

     */

    private fun shouldUseNativeBackgroundBlur(): Boolean =

        config.nativeBlur && !config.backdropCapture



    /**

     * JD keeps its full-screen video channel inside MainFrameActivity, so an

     * Activity-name check cannot distinguish it from Home. Detect the stable

     * combination actually present on that page: a screen-sized video surface

     * plus the top video-channel strip. The probe is throttled and bounded.

     */

    internal fun onAdapterScrollHideTimerChanged(active: Boolean, delayMs: Long) {
        removeCallbacks(msgScrollHideRunnable)
        msgScrolledToStop = false
        if (active) postDelayed(msgScrollHideRunnable, delayMs)
        postInvalidateOnAnimation()
    }

    internal fun onSystemBackPressed() {
        appNavigationState?.handleSystemBack(this)
    }

    private fun currentPageAllowsNavigation(): Boolean {

        appNavigationState?.hostPageAllowsNavigation(this, rootView)?.let { return it }

        if (targetAdapter?.isBlockingOverlayVisible() == true) return false

        // XHS mirrors Weibo's message-tab contract: the bar leaves 1.5s after

        // entering the message tab and returns as a 1.5s peek when system back

        // is pressed, while the page itself stays put. XHS's own selected flags

        // are stale across fragment switches, so the host's committed index is

        // the source of truth (see resolveSelectedFromViewState).

        if (targetAdapter?.resetScrollStopHideOnOtherTabs() == true) {

            if (targetAdapter?.scrollStopHideTab(selectedIndex) == true) {

                ensureMsgScrollStopHideListener()

                return !msgScrolledToStop

            }

            if (msgScrolledToStop) {

                msgScrolledToStop = false

                removeCallbacks(msgScrollHideRunnable)

            }

            return true

        }

        // Meituan Takeout profile: scroll-stop-hide. The orders tab keeps the

        // bar permanently (user request): hiding it there left the compositor

        // surface's last frame floating over the feed, because the surface

        // visibility never followed the host's hide animation.



        // Flash Sale follows the same low-overhead contract as Weibo Message:

        // show immediately while the page moves, then leave one second after

        // scrolling becomes idle. Do not resize its compositor surface.

        if (appNavigationState?.pageRequiresScrollStopHide(selectedIndex) == true) {

            ensureMsgScrollStopHideListener()

            return !msgScrolledToStop

        }

        if (context.packageName != QQ_PACKAGE && targetAdapter?.hasTransientPageVisibility != true) return true

        val now = SystemClock.uptimeMillis()

        if (now - lastPageVisibilityCheck < PAGE_VISIBILITY_INTERVAL_MS) return pageAllowsNavigation

        lastPageVisibilityCheck = now

        val activity = context.findActivity()

        pageAllowsNavigation = when (context.packageName) {

            QQ_PACKAGE -> !containsActiveQqChatFragment(activity) && !hasQqForegroundPageLayer(activity)

            else -> appNavigationState?.transientPageAllowsNavigation(activity) ?: true

        }

        return pageAllowsNavigation

    }



    /**

     * Attach a ViewTreeObserver.OnScrollChangedListener once. When any view

     * in the window scrolls while on an adapter-owned scroll-hide page, cancel the

     * hide timer and restart it (1s). When the timer fires, hide the bar.

     */

    internal fun resetMessageScrollStopState() {
        if (!msgScrolledToStop) return
        msgScrolledToStop = false
        removeCallbacks(msgScrollHideRunnable)
    }

    internal fun ensureMsgScrollStopHideListener() {

        if (msgScrollListenerAttached) return

        val root = rootView ?: return

        root.viewTreeObserver.addOnScrollChangedListener {

            val isMessageTab = (targetAdapter?.scrollStopHideTab(selectedIndex) == true) ||
                (appNavigationState?.pageRequiresScrollStopHide(selectedIndex) == true) ||

                (context.packageName == MEITUAN_TAKEOUT_PACKAGE &&

                    (selectedIndex == MEITUAN_TAKEOUT_ORDER_INDEX || selectedIndex == MEITUAN_TAKEOUT_PROFILE_INDEX))

            if (isMessageTab) {

                removeCallbacks(msgScrollHideRunnable)

                if (msgScrolledToStop) {

                    msgScrolledToStop = false

                }

                postDelayed(msgScrollHideRunnable,

                    if (targetAdapter?.scrollStopHideTab(selectedIndex) == true)
                        targetAdapter?.scrollStopHideDelayMs(selectedIndex) ?: 1_000L
                    else if (context.packageName == MEITUAN_TAKEOUT_PACKAGE && selectedIndex == MEITUAN_TAKEOUT_ORDER_INDEX) 800L

                    else if (context.packageName == MEITUAN_TAKEOUT_PACKAGE && selectedIndex == MEITUAN_TAKEOUT_PROFILE_INDEX) 1500L

                    else 1000L)

            }

        }

        // Track touch state: don't hide while user is dragging

        root.setOnTouchListener { _, event ->

            msgUserTouching = event.actionMasked != android.view.MotionEvent.ACTION_UP &&

                event.actionMasked != android.view.MotionEvent.ACTION_CANCEL

            false

        }

        msgScrollListenerAttached = true

    }



    /**

     * WeChat keeps its main launcher and the pull-down mini-program surface in

     * the same Activity, so source visibility alone cannot distinguish them.

     * Identify the actual mini-program panel from its stable, user-visible

     * labels instead of relying on version-specific resource ids. The cached

     * state is cleared on the first frame those labels disappear, allowing the

     * reverse bar animation to begin immediately when the chat list returns.

     */




    /**

     * QQ keeps the home tab fragment alive underneath profile/settings pages.

     * Those pages are inserted as later full-screen siblings of the branch

     * containing QQTabLayout. Matching that actual draw order avoids both the

     * old chat-only special case and the unsafe parent-alpha workaround.

     */

    private fun hasQqForegroundPageLayer(activity: Activity?): Boolean {

        val source = navigationSource ?: return false

        val content = activity?.findViewById<ViewGroup>(android.R.id.content) ?: return false

        val contentRect = Rect()

        if (!content.getGlobalVisibleRect(contentRect) || contentRect.isEmpty) return false

        val descriptions = ArrayList<String>()

        var branch: View = source

        repeat(12) {

            val parent = branch.parent as? ViewGroup ?: return@repeat

            val branchIndex = parent.indexOfChild(branch)

            for (index in (branchIndex + 1).coerceAtLeast(0) until parent.childCount) {

                val sibling = parent.getChildAt(index)

                if (sibling === this || sibling in suppressedOriginalChrome) continue

                val group = sibling as? ViewGroup

                val visibleRect = Rect()

                val visiblyOnScreen = sibling.getGlobalVisibleRect(visibleRect) &&

                    !visibleRect.isEmpty && visibleRect.width() > 2f * density

                val fullScreenLayer = sibling.visibility == View.VISIBLE && sibling.alpha > 0.01f &&

                    visiblyOnScreen && visibleRect.height() >= contentRect.height() * 0.72f

                if (fullScreenLayer) {

                    descriptions += "${sibling.javaClass.name}#${sibling.id}:children=${group?.childCount ?: -1}"

                    if (sibling.containsViewClass(QQ_SETTING_ME_LAYOUT_CLASS, 5)) {

                        logQqLayerSignature(descriptions)

                        return true

                    }

                }

            }

            if (parent === content) {

                logQqLayerSignature(descriptions)

                return false

            }

            branch = parent

        }

        logQqLayerSignature(descriptions)

        return false

    }



    private fun View.containsViewClass(className: String, maxDepth: Int): Boolean {

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += this to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view.javaClass.name == className && view.visibility == View.VISIBLE && view.alpha > 0.01f) return true

            if (view is ViewGroup && depth < maxDepth) {

                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)

            }

        }

        return false

    }



    private fun logQqLayerSignature(descriptions: List<String>) {

        val signature = descriptions.joinToString("|").ifEmpty { "home" }

        if (signature == lastQqLayerSignature) return

        lastQqLayerSignature = signature

        Xposed.log("[OfflineGlass][QQLayers] $signature")

    }



    private fun containsActiveQqChatFragment(activity: Activity?): Boolean {

        if (activity == null) return false

        val manager = runCatching { Xposed.callMethod(activity, "getSupportFragmentManager") }

            .getOrElse {

                runCatching { Xposed.callMethod(activity, "getFragmentManager") }.getOrNull()

            } ?: return false

        return containsActiveQqChatFragment(manager, 0)

    }



    private fun containsActiveQqChatFragment(manager: Any, depth: Int): Boolean {

        if (depth > 5) return false

        val fragments = runCatching { Xposed.callMethod(manager, "getFragments") as? Iterable<*> }

            .getOrNull() ?: return false

        for (fragment in fragments) {

            fragment ?: continue

            val hidden = runCatching { Xposed.callMethod(fragment, "isHidden") as? Boolean }

                .getOrNull() == true

            if (hidden) continue

            val active = runCatching { Xposed.callMethod(fragment, "isResumed") as? Boolean }

                .getOrNull() == true ||

                runCatching { Xposed.callMethod(fragment, "isVisible") as? Boolean }

                    .getOrNull() == true ||

                ((runCatching { Xposed.callMethod(fragment, "getView") }.getOrNull() as? View)?.isShown == true)

            if (!active) continue

            val name = fragment.javaClass.name

            if (

                name == "com.tencent.mobileqq.activity.ChatFragment" ||

                name.endsWith("AIOFragment") ||

                (name.contains(".aio.") && name.endsWith("Fragment"))

            ) {

                return true

            }

            val childManager = runCatching {

                Xposed.callMethod(fragment, "getChildFragmentManager")

            }.getOrNull()

            if (childManager != null && containsActiveQqChatFragment(childManager, depth + 1)) return true

        }

        return false

    }



    internal fun Context.findActivity(): Activity? {

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

    internal fun activityForAdapter(): Activity? = context.findActivity()



    companion object {

        fun lerp(start: Float, stop: Float, progress: Float): Float =

            start + (stop - start) * progress



        const val CORNER_RADIUS_DP = 32f

        const val PANEL_SHADOW_BLUR_DP = 10f

        const val PANEL_SHADOW_ALPHA = 0.14f

        const val PANEL_LIGHT_SHADOW_ALPHA = 0.19f

        const val PRESSED_SCALE = 78f / 56f

        /** The bottom pill gets a slightly stronger press than the reference's 78/56. */
        const val BOTTOM_PRESSED_SCALE = 1.45f

        const val CAPTURE_DOWNSCALE = 2f

        /** Anti burn-in: one pixel-grid shift every three minutes, no continuous animation. */
        const val BURN_IN_INTERVAL_MS = 180_000L
        const val BURN_IN_SHIFT_PX = 2f
        val BURN_IN_OFFSETS = arrayOf(
            0f to 0f,
            BURN_IN_SHIFT_PX to 0f,
            BURN_IN_SHIFT_PX to BURN_IN_SHIFT_PX,
            0f to BURN_IN_SHIFT_PX,
            -BURN_IN_SHIFT_PX to BURN_IN_SHIFT_PX,
            -BURN_IN_SHIFT_PX to 0f,
            -BURN_IN_SHIFT_PX to -BURN_IN_SHIFT_PX,
            0f to -BURN_IN_SHIFT_PX,
        )

        /**
         * Host diagnostics are compiled out for release: the visibility line below is rebuilt on
         * every pre-draw pass. Flip this while a bar-visibility bug needs the reading.
         */
        const val GLASS_HOST_DIAG_ENABLED = false

        /**
         * A/B switch: true restores the pre-optimisation per-frame pump.
         *
         * Only for comparing how the refraction looks at 165 Hz versus the video rate.
         * Costs ~80% more repaints, so it must be false in normal builds.
         */
        const val FORCE_FULL_RATE_PUMP = false

    /**
     * Shortest gap between two live-scene sweeps.
     *
     * 5 ms is 200 Hz: above the 165 Hz panel, so a sweep is still available for every
     * displayed frame, while a drag that traverses the window many times per frame can no longer
     * re-record the whole scene each time.
     */
    const val SCENE_SWEEP_MIN_INTERVAL_MS = 5L

    /**
     * Shortest gap between two drag-driven repaints.
     *
     * 8 ms is ~120 Hz: still smooth for a gesture that follows the finger, while a 165 Hz panel
     * can no longer make the glass repaint on every single motion event.
     */
    const val DRAG_UPDATE_MIN_INTERVAL_MS = 8L

    /**
     * How often the host re-walks its ancestor chain to clear clipChildren/clipToPadding.
     *
     * The chain only changes when the host is re-parented, but the walk used to run on every
     * pre-draw for every host. Half a second is far below the time an app takes to rebuild the
     * content frame, and it keeps a hostile re-clip from surviving more than a few frames.
     */
    const val ANCESTOR_CLIP_RELEASE_INTERVAL_MS = 500L

    /**
     * Safety-net re-apply of the native row's touch flags.
     *
     * The row is cleared once when the bar appears, but an app that rebuilds the row's
     * children in place (Douyin reuses and re-adds cells) would otherwise reintroduce an
     * invisible clickable strip under the glass. Twice a second is cheap and bounded.
     */
    const val TOUCHABLE_REAPPLY_INTERVAL_MS = 500L

        /**
         * How long an identical diagnostic line stays suppressed. See [diagLogGate]: the
         * gate's primary job is dropping repeats of an unchanged reading, not rate limiting,
         * so this only bounds a steady heartbeat.
         */
        const val DIAG_REPEAT_MS = 60_000L


        // Fallback sampling runs from pre-draw, so it must track rendered frames.
        // A 96ms timer produced visible stepped updates even on slow scrolling.
        const val CAPTURE_IDLE_INTERVAL_MS = 0L

        const val OPTICAL_SURFACE_CAPTURE_INTERVAL_MS = 16L

        const val OPTICAL_SURFACE_TEXTURE_PROBE_INTERVAL_MS = 1_000L

        const val CONFIG_POLL_INTERVAL_MS = 1_000L

        /** Safety-net cadence once the framework pushes settings changes instead. */
        const val CONFIG_PUSH_SAFETY_INTERVAL_MS = 60_000L

        const val THEME_PROBE_INTERVAL_MS = 500L

        const val THEME_TRANSITION_GRACE_MS = 900L

        const val SOURCE_RESIZE_DELAY_MS = 180L

        const val SOURCE_POST_RESIZE_SETTLE_MS = 64L

        const val SOURCE_STABLE_FRAMES = 3

        const val BACKDROP_STABLE_FRAMES = 2

        const val NAVIGATION_SNAPSHOT_STABLE_FRAMES = 3

        const val DARK_LABEL_LUMINANCE = 145f

        // QQ's drawer/page layers animate independently while remaining

        // attached. Probe once per display frame so the home bar begins its

        // return animation on the first frame the foreground layer is gone.

        const val PAGE_VISIBILITY_INTERVAL_MS = 16L

        const val QQ_PACKAGE = "com.tencent.mobileqq"

        const val QQ_BOTTOM_BLUR_CLASS = "com.tencent.qui.quiblurview.QQBlurViewWrapper"

        const val QQ_BADGE_CLASS = "com.tencent.mobileqq.quibadge.QUIBadge"

        const val QQ_BADGE_MIN_HEIGHT_DP = 16f

        const val QQ_BOTTOM_CHROME_PROBE_INTERVAL_MS = 80L

        const val QQ_VISIBILITY_ANIMATION_Z_DP = 64f

        const val QQ_STEADY_TRANSLATION_Z_DP = 2f

        // Detection parameters for the pull-down "Recent" drawer we get when

        // the chat list is scrolled to top and pulled. Hides the liquid bar

        // while the drawer is open to avoid overlap.





        const val FILE_MANAGER_PACKAGE = "com.android.fileexplorer"

        const val FILE_MANAGER_ACTION_BAR_ID = "split_action_bar"

        const val FILE_MANAGER_BOTTOM_NAV_ID = "bottom_navigation_container"

        const val MODULE_PACKAGE = "io.github.xzdw.dylt"

        const val MEITUAN_TAKEOUT_PACKAGE = "com.sankuai.meituan.takeoutnew"



        /**
         * The selected capsule's lens is a shallower profile than the bar's, so it scales with
         * the 鎶樺皠鑼冨洿 / 鎶樺皠寮哄害 settings instead of using fixed values. The ratios are taken
         * against the bar's 24dp / 24dp baseline, so it reproduces the shipped capsule exactly at
         * that setting and keeps following the bar as the sliders move.
         */
        const val INDICATOR_LENS_HEIGHT_RATIO = 10f / 24f

        const val INDICATOR_LENS_AMOUNT_RATIO = 14f / 24f

        const val INDICATOR_LENS_HYBRID_HEIGHT_MIN_RATIO = 5.5f / 24f

        const val INDICATOR_LENS_HYBRID_HEIGHT_MAX_RATIO = 8f / 24f

        const val INDICATOR_LENS_HYBRID_AMOUNT_MIN_RATIO = 16f / 24f

        const val INDICATOR_LENS_HYBRID_AMOUNT_MAX_RATIO = 20f / 24f

        const val INDICATOR_LENS_COMPOSITOR_AMOUNT_MIN_RATIO = 14f / 24f

        const val INDICATOR_LENS_COMPOSITOR_AMOUNT_MAX_RATIO = 19f / 24f

                const val MEITUAN_SELECTION_SETTLE_MS = 220L

        const val MEITUAN_TAKEOUT_ORDER_INDEX = 3

        const val MEITUAN_TAKEOUT_PROFILE_INDEX = 4

        // The curve remains translucent through most of its height, while the

        // last pixel reaches the exact navigation-surface colour for a seam-free join.

        // Softened transition removed: blend now spans from gesture surface

        // top to screen bottom (no solid zone), driven by inset geometry.



    


        const val QQ_TARGET_ICON_DP = 28f

        const val QQ_NATIVE_ICON_DP = 27f

        const val QQ_SOURCE_ROW_HEIGHT_DP = 54f

        const val QQ_ICON_LABEL_SPLIT_DP = 31.5f

        const val QQ_CANONICAL_ICON_CENTER_DP = 19.25f

        const val QQ_CANONICAL_LABEL_CENTER_DP = 40f

        const val DIAGNOSTIC_TAG = "OfflineGlassGeom"

        const val QQ_SETTING_ME_LAYOUT_CLASS = "com.tencent.mobileqq.widget.QQSettingMeRelativeLayout"

        const val KERNEL_SU_ACCENT_COLOR = 0xFF0A84FF.toInt()

        // Match Bilibili's compact icon/label silhouette. Xiaomi Community's

        // live drawable uses a padded 2x source box, so scale both paths in

        // lockstep or the server-provided icon would remain visibly smaller.

        // Amap home bar: its own tab row lays every cell out as icon(left)+label
        // (right) plus a persistent selection halo behind the icon, so the generic
        // projection shows a left-shifted icon with leftover native highlights.
        // We extract the default icon artwork and label per cell at runtime and
        // re-draw them centred in each slot over the clean liquid glass.

        // 1.15 -> *1.1: icons enlarged again to 1.1x of the previous size.

        // Icon pulled down and label pulled up symmetrically to tighten the gap.

        const val MEITUAN_TAKEOUT_SCROLL_PROBE_INTERVAL_MS = 160L

        const val QQ_DEFAULT_ACCENT_COLOR = 0xFF0099FF.toInt()

        const val QQ_DEFAULT_DARK_COLOR = 0xFF191919.toInt()

        val QQ_TAB_LABELS = arrayOf("消息", "联系人", "动态")

        val QQ_NORMAL_ICON_FILES = arrayOf(

            "message_normal.png",

            "contact_normal.png",

            "dynamic_normal.png",

        )

        val QQ_SELECTED_ICON_FILES = arrayOf(

            "message_selected.png",

            "contact_selected.png",

            "dynamic_selected.png",

        )

        val MEITUAN_TAKEOUT_LABELS = arrayOf("棣栭〉", "绁炲埜", "娲诲姩", "璁㈠崟", "鎴戠殑")



        const val ROUNDED_RECT_SDF = """

            float radiusAt(float2 coord, float4 radii) {

                if (coord.x >= 0.0) {

                    if (coord.y <= 0.0) return radii.y;

                    else return radii.z;

                } else {

                    if (coord.y <= 0.0) return radii.x;

                    else return radii.w;

                }

            }



            float sdRoundedRect(float2 coord, float2 halfSize, float radius) {

                float2 cornerCoord = abs(coord) - (halfSize - float2(radius));

                float outside = length(max(cornerCoord, 0.0)) - radius;

                float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);

                return outside + inside;

            }



            float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {

                float2 cornerCoord = abs(coord) - (halfSize - float2(radius));

                if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {

                    return sign(coord) * normalize(max(cornerCoord, 0.0));

                } else {

                    float gradX = step(cornerCoord.y, cornerCoord.x);

                    return sign(coord) * float2(gradX, 1.0 - gradX);

                }

            }

        """



        const val ROUNDED_RECT_REFRACTION_SHADER = """

            uniform shader content;

            uniform float2 size;

            uniform float2 offset;

            uniform float4 cornerRadii;

            uniform float refractionHeight;

            uniform float refractionAmount;

            uniform float depthEffect;



            $ROUNDED_RECT_SDF



            float circleMap(float x) {

                return 1.0 - sqrt(1.0 - x * x);

            }



            half4 main(float2 coord) {

                float2 halfSize = size * 0.5;

                float2 centeredCoord = (coord + offset) - halfSize;

                float radius = radiusAt(coord, cornerRadii);

                float sd = sdRoundedRect(centeredCoord, halfSize, radius);

                if (refractionHeight < 0.5) return content.eval(coord);

                if (-sd >= refractionHeight) return content.eval(coord);

                sd = min(sd, 0.0);

                float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;

                float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));

                float2 grad = normalize(

                    gradSdRoundedRect(centeredCoord, halfSize, gradRadius) +

                    depthEffect * normalize(centeredCoord)

                );

                return content.eval(coord + d * grad);

            }

        """



        const val ROUNDED_RECT_DISPERSION_SHADER = """

            uniform shader content;

            uniform float2 size;

            uniform float2 offset;

            uniform float4 cornerRadii;

            uniform float refractionHeight;

            uniform float refractionAmount;

            uniform float depthEffect;

            uniform float chromaticAberration;



            $ROUNDED_RECT_SDF



            float circleMap(float x) {

                return 1.0 - sqrt(1.0 - x * x);

            }



            half4 main(float2 coord) {

                float2 halfSize = size * 0.5;

                float2 centeredCoord = (coord + offset) - halfSize;

                float radius = radiusAt(coord, cornerRadii);

                float sd = sdRoundedRect(centeredCoord, halfSize, radius);

                if (refractionHeight < 0.5) return content.eval(coord);

                if (-sd >= refractionHeight) return content.eval(coord);

                sd = min(sd, 0.0);



                float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;

                float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));

                float2 grad = normalize(

                    gradSdRoundedRect(centeredCoord, halfSize, gradRadius) +

                    depthEffect * normalize(centeredCoord)

                );

                float2 refractedCoord = coord + d * grad;

                float dispersionIntensity = chromaticAberration *

                    ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));

                float2 dispersedCoord = d * grad * dispersionIntensity;



                half4 color = half4(0.0);

                half4 red = content.eval(refractedCoord + dispersedCoord);

                color.r += red.r / 3.5;

                color.a += red.a / 7.0;

                half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));

                color.r += orange.r / 3.5;

                color.g += orange.g / 7.0;

                color.a += orange.a / 7.0;

                half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));

                color.r += yellow.r / 3.5;

                color.g += yellow.g / 3.5;

                color.a += yellow.a / 7.0;

                half4 green = content.eval(refractedCoord);

                color.g += green.g / 3.5;

                color.a += green.a / 7.0;

                half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));

                color.g += cyan.g / 3.5;

                color.b += cyan.b / 3.0;

                color.a += cyan.a / 7.0;

                half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));

                color.b += blue.b / 3.0;

                color.a += blue.a / 7.0;

                half4 purple = content.eval(refractedCoord - dispersedCoord);

                color.r += purple.r / 7.0;

                color.b += purple.b / 3.0;

                color.a += purple.a / 7.0;

                return color;

            }

        """



        /** Highlight edge profile: a whole-pixel core on the rim plus a soft inward glow. */
        const val BLOOM_CORE_DP = 1f
        const val BLOOM_GLOW_DP = 4f
        const val BLOOM_GLOW_STRENGTH = 0.35f

        const val BLOOM_STROKE_DUAL_SHADER = """

            uniform float2 halfView;

            uniform float highlightAlpha;



            layout(color) uniform half4 strokeColor;

            uniform float strokeAlphaMul;

            uniform float strokeWidthPx;

            uniform float glowPx;

            uniform float glowStrength;

            uniform float4 cornerRadii;

            uniform float edgeProfile;



            uniform float3 lightDir1;

            layout(color) uniform half4 lightColor1;

            uniform float lightIntensity1;



            uniform float3 lightDir2;

            layout(color) uniform half4 lightColor2;

            uniform float lightIntensity2;



            $ROUNDED_RECT_SDF

            half4 main(float2 fragCoord) {

                // The shape is filled now and the shader owns its edge profile: a signed distance
                // gives an antialiased core hugging the rim plus an exponential glow that decays
                // inward, so nothing can alias the way a one-pixel hairline stroke did.

                float2 centered = fragCoord - halfView;

                float radius = radiusAt(fragCoord, cornerRadii);

                float sd = sdRoundedRect(centered, halfView, radius);

                float aa = 0.75;

                float inside = 1.0 - smoothstep(-aa, aa, sd);

                float depth = max(-sd, 0.0);

                float core = 1.0 - smoothstep(strokeWidthPx - aa, strokeWidthPx + aa, depth);

                float coverage = 1.0;

                if (edgeProfile > 0.5) {

                    coverage = inside * (core + exp(-depth / max(glowPx, 0.5)) * glowStrength);

                }

                float2 delta = fragCoord - halfView;

                float2 n = delta / max(length(delta), 0.001);

                half3 rgb = strokeColor.rgb * half(strokeAlphaMul);

                float l1 = dot(n, normalize(lightDir1.xy));

                rgb += half(l1 * l1 * lightIntensity1) * lightColor1.rgb;

                float l2 = dot(n, normalize(lightDir2.xy));

                rgb += half(l2 * l2 * lightIntensity2) * lightColor2.rgb;

                half a = half(highlightAlpha * coverage);

                return half4(rgb * a, a);

            }

        """

    }

}






/** Main-thread spring integrator for navigation gestures and visibility transitions. */

private class SpringFloat(

    initialValue: Float,

    private val stiffness: Float,

    dampingRatio: Float,

    private val threshold: Float,

    private val update: (value: Float, velocity: Float) -> Unit,

) : Choreographer.FrameCallback {

    private val damping = 2f * dampingRatio * sqrt(stiffness)

    private val choreographer = Choreographer.getInstance()

    private var value = initialValue

    private var target = initialValue

    private var lastFrameNanos = 0L

    private var running = false

    var velocity = 0f

        private set



    fun animateTo(newTarget: Float) {

        // Idle calls must not re-arm the choreographer. The slot springs are asked for a
        // target from every AdapterNavigationFrame (three or four per displayed frame), and
        // each re-arm used to post a frame callback whose update() invalidated the host --
        // a self-sustaining redraw loop that painted identical pixels at display rate even
        // with the video paused. A spring that is already at rest on this target has
        // nothing to animate.
        if (newTarget == target && !running && value == target) return

        target = newTarget

        if (!running) {

            running = true

            lastFrameNanos = 0L

            choreographer.postFrameCallback(this)

        }

    }



    fun snapTo(newValue: Float) {

        cancel()

        value = newValue

        target = newValue

        velocity = 0f

        update(value, velocity)

    }



    fun cancel() {

        if (running) choreographer.removeFrameCallback(this)

        running = false

        lastFrameNanos = 0L

    }



    override fun doFrame(frameTimeNanos: Long) {

        if (!running) return

        if (lastFrameNanos == 0L) {

            lastFrameNanos = frameTimeNanos

            update(value, velocity)

            choreographer.postFrameCallback(this)

            return

        }

        val totalDt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 1f / 20f)

        lastFrameNanos = frameTimeNanos

        val steps = ceil(totalDt / (1f / 120f)).toInt().coerceAtLeast(1)

        val dt = totalDt / steps

        repeat(steps) {

            val acceleration = -stiffness * (value - target) - damping * velocity

            velocity += acceleration * dt

            value += velocity * dt

        }

        update(value, velocity)

        if (abs(value - target) <= threshold && abs(velocity) <= threshold * 10f) {

            value = target

            velocity = 0f

            running = false

            lastFrameNanos = 0L

            update(value, velocity)

        } else {

            choreographer.postFrameCallback(this)

        }

    }

}



/**

 * Full-width, non-interactive contacts-page veil. It reuses the accepted shared

 * opacity curve but measures WeChat's own taller native navigation edge.

 */







internal class BottomBarSquircleOutlineProvider(

    private val radius: () -> Float,

    private val smoothing: () -> Float = { 0.5f },

    /**
     * The view's real silhouette, when it is not one rounded rectangle. A bar whose action
     * button sits in a detached circle has a panel path of two or three shapes with a gap
     * between them; outlining the full rect instead makes the system cast the elevation
     * shadow across those gaps, which shows as a faint band joining the pieces. The lambda
     * returns the host's own clipping path, so the shadow follows the shapes exactly.
     */
    private val silhouette: (() -> Path)? = null,

) : android.view.ViewOutlineProvider() {

    private val rect = RectF()

    private val path = Path()



    override fun getOutline(view: View, outline: android.graphics.Outline) {

        val real = silhouette?.invoke()

        if (real != null && !real.isEmpty) {

            outline.setPath(real)

            return

        }

        rect.set(0f, 0f, view.width.toFloat(), view.height.toFloat())

        path.setBottomBarSquircle(rect, radius(), smoothing())

        if (!path.isEmpty) outline.setPath(path)

    }

}
