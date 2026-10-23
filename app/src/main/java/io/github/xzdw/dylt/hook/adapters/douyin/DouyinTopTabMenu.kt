package io.github.xzdw.dylt.hook.adapters.douyin

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import io.github.xzdw.dylt.hookx.Xposed
import io.github.xzdw.dylt.config.GlassConfig
import io.github.xzdw.dylt.hook.BloomTiltTracker
import io.github.xzdw.dylt.hook.GlassHostLayout
import io.github.xzdw.dylt.hook.setBottomBarSquircle
import io.github.xzdw.dylt.hook.adapters.AdapterOwnedOverlay
import io.github.xzdw.dylt.hook.adapters.AppVideoBackdrop
import io.github.xzdw.dylt.rendering.NativeBackdropBlur
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything the dropdown takes from the module's own settings, resolved by the bar it hangs
 * from: the panel reads the same tint, blur, corner rule and effect gates, so moving a slider
 * (or an adapter's own palette) moves both together. Nothing here is a panel-local constant
 * except the layout metrics, which have no setting of their own.
 */
internal data class DouyinMenuStyle(
    val dark: Boolean,
    val config: GlassConfig,
    /** The bar's resolved fill, alpha included. */
    val fillColor: Int,
    /** The bar's outer corner radius; the panel stays concentric with it. */
    val barCornerRadiusPx: Float,
    /** The bar's resolved backdrop blur radius. */
    val blurRadiusPx: Float,
    /** `backdropCapture`: without it the panel is a flat tint. */
    val materialEnabled: Boolean,
)

/**
 * The dropdown Douyin's leading key opens: every tab its title bar carries except the two
 * the glass capsule already shows.
 *
 * It has to be its own view. A menu hangs *below* the bar, outside the glass host's bounds,
 * and a View only receives touches inside its own bounds 鈥?the host could draw there (its
 * parent does not clip) but could never be tapped there.
 *
 * The panel is real glass, rendered live with the same mechanism the bars use, and none of
 * it software drawn:
 *
 * - The **live scene** is the primary source: the display lists of the views drawn below
 *   this overlay are replayed into a `RenderNode` every frame (a `TextureView` feed keeps
 *   submitting its own texture into that tree), which is what makes the bars live without
 *   any readback.
 * - A one-shot `PixelCopy` of the window surface behind the panel is the base layer, taken
 *   when the panel opens. It also covers the case where the scene has no display list yet.
 * - [DouyinVideoSampler] supplies the media layer where a real `SurfaceView` owns it. A
 *   `SurfaceView` records a CLEAR hole, so the scene is drawn inside its own layer: there a
 *   clear can only reveal the media frame underneath, never erase it.
 *
 * Software drawing (`View.draw` into a bitmap) would miss the media layer entirely and
 * produce the black/white mask this was written to avoid.
 */
internal class DouyinTopTabMenu(context: Context) : View(context), AdapterOwnedOverlay {

    private companion object {
        /**
         * Grid metrics, all deliberately tight: the panel is a list of short labels, and
         * generous list-row spacing read as empty space between them.
         */
        const val ROW_HEIGHT_MIN_DP = 44f
        const val ROW_LINE_FACTOR = 1.45f
        const val PANEL_PADDING_DP = 6f
        const val ITEM_HORIZONTAL_PADDING_DP = 12f
        const val ITEM_COLUMN_GAP_DP = 10f
        const val ANCHOR_GAP_DP = 6f
        /**
         * The list is a grid: Douyin's title bar carries seven or eight tabs, and one row
         * each made a panel taller than half the screen. Two columns halve that and still
         * read in order, left to right.
         */
        const val COLUMNS = 2

        /**
         * Floor for one column. The width itself follows the labels (see [open]), because
         * the font and the user's display size both move it.
         */
        const val MIN_COLUMN_DP = 64f

        /** Backdrop padding floor, matching the host's own capture padding. */
        const val CAPTURE_PADDING_DP = 24f

        /** Downscale of the one-shot window copy, matching the host's capture. */
        const val CAPTURE_DOWNSCALE = 2f

        const val VIDEO_REFRESH_MS = 220L

        /** Grace after the unfold before the window copy is taken; see [open]. */
        const val CAPTURE_SETTLE_MS = 40L

        /**
         * Unfold and fold-back timings. The panel grows out of the key it was opened from,
         * so the expand is the slower, decelerating half and the collapse leaves faster 鈥?         * the same asymmetry the bars use for their own show/hide.
         */
        const val EXPAND_MS = 210L
        const val COLLAPSE_MS = 130L

        /** Panel opacity at progress 0; the material fades the rest of the way in. */
        const val MIN_APPEAR_ALPHA = 0.55f

        /**
         * Safety re-record of the replayed scene while the panel stays open.
         *
         * The scene node keeps live references to the app's child RenderNodes, so it only needs
         * re-recording when the sibling set/layout changes. One second covers that without
         * paying the old per-frame record; the base layer is keyed on its actual inputs instead.
         */
        const val SCENE_REFRESH_MS = 1_000L
    }

    private val expandInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val collapseInterpolator = PathInterpolator(0.4f, 0f, 1f, 1f)

    /** Final panel geometry; the animated [panel] unfolds inside it. */
    private val fullPanel = RectF()
    private val panel = RectF()
    private val panelPath = Path()
    private var expandProgress = 0f
    private var animator: ValueAnimator? = null
    private var closing = false
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val itemBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val itemBorderRect = RectF()

    /**
     * The two glass-editor effects the bars draw, on the panel: the edge-refraction lens and
     * the dual-light bloom stroke. The shaders live in `GlassHostLayout`'s companion, so the
     * panel runs the same GLSL rather than a copy of it.
     */
    private val lensShader = runCatching {
        RuntimeShader(GlassHostLayout.ROUNDED_RECT_REFRACTION_SHADER)
    }.getOrNull()
    private val bloomShader = runCatching {
        RuntimeShader(GlassHostLayout.BLOOM_STROKE_DUAL_SHADER)
    }.getOrNull()
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Blur + lens chain for this open; rebuilt per open, since its uniforms are per-panel. */
    private var materialEffect: RenderEffect? = null
    private var refractionHeightPx = 0f
    private var refractionAmountPx = 0f
    private var saturation = 1.5f
    private var highlightAlpha = 0f
    private var cornerRadiusPx = 0f
    private var cornerSmoothing = -1f
    private var materialEnabled = true

    /** The plain blur for the base layer; constant for as long as the panel is open. */
    private var baseBlurEffect: RenderEffect? = null

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private var items: List<String> = emptyList()
    private var selected = -1
    private var rowHeight = 0f

    /** Grid metrics of the open panel; see [open] and [itemAt]. */
    private var columnWidth = 0f
    private var rows = 1
    private var panelColor = Color.argb(90, 245, 242, 236)
    private var borderColor = Color.argb(70, 0, 0, 0)
    private var labelColor = Color.BLACK
    private var blurRadiusPx = 0f

    /** One-shot window copy behind the panel; the base layer of the material. */
    private var backdrop: Bitmap? = null
    private val captureRect = Rect()
    private var captureInFlight = false
    private var captureGeneration = 0

    /** The blurred base layer (media frame + window copy) and the live scene above it. */
    private val baseNode = RenderNode("LiquidTabTopMenuBase")
    private val sceneNode = RenderNode("LiquidTabTopMenuScene")
    private val sceneRect = Rect()
    private val sceneOrigin = IntArray(2)
    private val locationScratch = IntArray(2)
    private val viewLocationScratch = IntArray(2)

    /** What the recorded layers were built from; see [drawGlassBackdrop]. */
    private var baseSignature = 0L
    private var sceneRecordedAt = 0L
    private var sceneRecordedWidth = -1
    private var sceneRecordedHeight = -1

    private val retainedRenderNodeField by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }
        }.getOrNull()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureRetry = Runnable { captureBackdrop() }
    private val videoSampler = DouyinVideoSampler(context)
    private val videoRefresh = Runnable { refreshVideoFrame() }

    /** Called with the row the user picked; the menu closes itself first. */
    var onPick: ((Int) -> Unit)? = null

    fun isOpen(): Boolean = visibility == VISIBLE && !closing

    /**
     * Shows the panel anchored under [anchor] (the glass bar's rect in this view's
     * coordinates) and unfolds it downwards.
     */
    fun open(
        labels: List<String>,
        selectedIndex: Int,
        anchor: RectF,
        density: Float,
        style: DouyinMenuStyle,
        scaledDensity: Float,
    ) {
        items = labels
        selected = selectedIndex
        val config = style.config
        val dark = style.dark
        // The panel stays concentric with the bar it hangs from: the bar's own outer radius
        // (which follows `cornerRadiusPercent` and the bar height) plus the gap between them.
        cornerRadiusPx = style.barCornerRadiusPx + ANCHOR_GAP_DP * density
        cornerSmoothing = config.cornerSmoothing
        labelPaint.textSize = (config.textSize * scaledDensity).coerceAtLeast(1f)
        // Rows keep the grid tight but never clip the font, at whatever text size runs.
        val lineHeight = labelPaint.fontMetrics.descent - labelPaint.fontMetrics.ascent
        rowHeight = max(ROW_HEIGHT_MIN_DP * density, lineHeight * ROW_LINE_FACTOR)
        val padding = PANEL_PADDING_DP * density
        val gap = ANCHOR_GAP_DP * density
        // Column width follows the longest label: the grid must fit what the app actually
        // draws, at whatever text size the user runs.
        val longest = items.maxOfOrNull { labelPaint.measureText(it) } ?: 0f
        // Leave room for the item outline on both sides plus a gap between the two columns.
        // Without the gap the two item borders clamp against each other and read as one block.
        columnWidth = (longest + (ITEM_HORIZONTAL_PADDING_DP * 2f + ITEM_COLUMN_GAP_DP) * density)
            .coerceAtLeast(MIN_COLUMN_DP * density)
        rows = ((items.size + COLUMNS - 1) / COLUMNS).coerceAtLeast(1)
        val width = COLUMNS * columnWidth + padding * 2
        val height = rows * rowHeight + padding * 2
        // Anchor under the bar's left edge, but keep the panel on screen. On the first open
        // this view has not been laid out yet, so its own width is still zero 鈥?fall back to
        // the display width instead of clamping the panel to x=0.
        val screenWidth = if (this.width > 0) this.width else resources.displayMetrics.widthPixels
        val left = anchor.left.coerceIn(0f, (screenWidth - width).coerceAtLeast(0f))
        fullPanel.set(
            left,
            anchor.bottom + gap,
            left + width,
            anchor.bottom + gap + height,
        )
        // The panel paints the bar's own resolved fill 鈥?adapter tint, themed surface colour
        // and the configured alpha, all already in it 鈥?so the two can never drift apart.
        panelColor = style.fillColor
        // Same rim pair the bars fall back to when the bloom is off.
        borderColor = if (dark) {
            Color.argb((0.32f * 255f).toInt(), 255, 255, 255)
        } else {
            Color.argb((0.24f * 255f).toInt(), 0, 0, 0)
        }
        // Every row takes the colour the bar paints the selected capsule's label with 鈥?the
        // dropdown is the same list of tabs, so it uses the same ink.
        labelColor = DouyinTopBarRenderer.selectedLabelColor(dark)
        saturation = config.glassSaturation.coerceIn(0.5f, 2.5f)
        // The bar's own radius, so the slider moves both by the same amount.
        blurRadiusPx = style.blurRadiusPx
        materialEnabled = style.materialEnabled
        refractionHeightPx = config.refractionHeightDp * density
        refractionAmountPx = -config.refractionStrengthDp * density
        // Same curve the panel's own bloom uses on the bars: lighter in light mode, and the
        // user's dark-mode strength applies only there.
        highlightAlpha = if (!config.outlineEnabled) {
            0f
        } else {
            ((if (dark) 0.58f else 0.75f) * if (dark) config.darkBarHighlightStrength else 1f)
                .coerceIn(0f, 1f)
        }
        materialEffect = if (materialEnabled) {
            buildMaterialEffect(density)
        } else {
            null
        }
        baseBlurEffect = NativeBackdropBlur.effect(blurRadiusPx, saturation)
        closing = false
        if (visibility != VISIBLE) visibility = VISIBLE
        if (highlightAlpha > 0.001f) BloomTiltTracker.register(this)
        animateExpand(expandProgress, 1f, EXPAND_MS, expandInterpolator)
        // Capture *after* the unfold: PixelCopy is a GPU->CPU readback that stalls the
        // pipeline, and running it in the animation's first frames is what made the panel
        // stutter as it opened. The material is live without it 鈥?the replayed scene carries
        // the feed 鈥?so the copy only has to be there once the panel has stopped moving.
        if (materialEnabled) {
            mainHandler.removeCallbacks(captureRetry)
            mainHandler.postDelayed(captureRetry, EXPAND_MS + CAPTURE_SETTLE_MS)
        }
        invalidate()
    }

    /**
     * The material's GPU chain: the blurred, saturation-boosted backdrop feeds the
     * edge-refraction lens 鈥?the same pair the bars build
     * (`createChainEffect(lens, blurred)` applies the blur first, then the lens over it).
     *
     * The lens measures itself against the *full* panel even while it unfolds, so its
     * uniforms are set once per open instead of per frame; the visible part of a half-open
     * panel is just the top of that lens.
     */
    private fun buildMaterialEffect(density: Float): RenderEffect? {
        val blurred = NativeBackdropBlur.effect(blurRadiusPx, saturation)
        val shader = lensShader ?: return blurred
        val padding = NativeBackdropBlur.padding(
            blurRadiusPx,
            CAPTURE_PADDING_DP * density,
        ).roundToInt().toFloat()
        runCatching {
            shader.setFloatUniform("size", fullPanel.width(), fullPanel.height())
            shader.setFloatUniform("offset", -padding, -padding)
            shader.setFloatUniform(
                "cornerRadii",
                cornerRadiusPx,
                cornerRadiusPx,
                cornerRadiusPx,
                cornerRadiusPx,
            )
            shader.setFloatUniform("refractionHeight", refractionHeightPx)
            shader.setFloatUniform("refractionAmount", refractionAmountPx)
            shader.setFloatUniform("depthEffect", 0f)
        }
        return RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(shader, "content"),
            blurred,
        )
    }

    /**
     * The dual-light bloom rim, stroked along the panel path so it follows the rounded shape
     * (and the unfolding edge). Its primary light follows the device tilt through the same
     * tracker the bars register with, rotated -45 degrees the way the panel does it.
     */
    private fun drawBloom(canvas: Canvas, appear: Float) {
        val shader = bloomShader ?: return
        if (highlightAlpha <= 0.001f) return
        val density = resources.displayMetrics.density
        runCatching {
            shader.setFloatUniform("halfView", panel.width() / 2f, panel.height() / 2f)
            shader.setFloatUniform("highlightAlpha", highlightAlpha * appear)
            shader.setColorUniform("strokeColor", Color.WHITE)
            shader.setFloatUniform("strokeAlphaMul", 0.12f)
            setBloomLight(shader, "1", primary = true)
            setBloomLight(shader, "2", primary = false)
            bloomPaint.shader = shader
            bloomPaint.style = Paint.Style.STROKE
            bloomPaint.strokeWidth = density
            canvas.drawPath(panelPath, bloomPaint)
        }
        bloomPaint.shader = null
        bloomPaint.style = Paint.Style.FILL
    }

    /**
     * Primary light: the screen-plane gravity projection, rotated -45 degrees. Secondary:
     * the fixed fill light the shader expects. Mirrors the host's own setup, without the
     * luminance scaling it applies while a bar is appearing.
     */
    private fun setBloomLight(shader: RuntimeShader, suffix: String, primary: Boolean) {
        var dx = 0f
        var dy = 0.1f
        var dz = -0.5f
        var intensity = 0.4f
        if (primary) {
            var gx = BloomTiltTracker.gravityX
            var gy = BloomTiltTracker.gravityY
            when (displayRotation()) {
                Surface.ROTATION_90 -> {
                    val remap = gx
                    gx = -gy
                    gy = remap
                }
                Surface.ROTATION_180 -> {
                    gx = -gx
                    gy = -gy
                }
                Surface.ROTATION_270 -> {
                    val remap = gx
                    gx = gy
                    gy = -remap
                }
            }
            val magnitude = gx * gx + gy * gy
            var lx = 0f
            var ly = -1f
            if (magnitude > BloomTiltTracker.GRAVITY_DIR_THRESHOLD_SQ) {
                val inverse = 1f / sqrt(magnitude)
                lx = gx * inverse
                ly = gy * inverse
            }
            val radians = Math.toRadians(-45.0)
            val c = cos(radians).toFloat()
            val s = sin(radians).toFloat()
            dx = c * lx - s * ly
            dy = s * lx + c * ly
            dz = -0.05f
            intensity = 1f
        }
        val length = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.000001f)
        shader.setFloatUniform("lightDir$suffix", dx / length, dy / length, dz / length)
        shader.setColorUniform("lightColor$suffix", Color.WHITE)
        shader.setFloatUniform("lightIntensity$suffix", intensity)
    }

    private fun displayRotation(): Int = runCatching {
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.rotation
    }.getOrDefault(Surface.ROTATION_0)

    /** Folds the panel back up, then takes it off screen. */
    fun close() {
        if (visibility != VISIBLE || closing) return
        closing = true
        mainHandler.removeCallbacks(videoRefresh)
        mainHandler.removeCallbacks(captureRetry)
        animateExpand(expandProgress, 0f, COLLAPSE_MS, collapseInterpolator)
    }

    /** Immediate teardown, for a detach where no frame will run. */
    private fun closeNow() {
        animator?.cancel()
        animator = null
        closing = false
        expandProgress = 0f
        BloomTiltTracker.unregister(this)
        materialEffect = null
        if (visibility == VISIBLE) visibility = GONE
        items = emptyList()
        selected = -1
        mainHandler.removeCallbacks(videoRefresh)
        mainHandler.removeCallbacks(captureRetry)
        captureGeneration++
        backdrop?.takeUnless(Bitmap::isRecycled)?.recycle()
        backdrop = null
        captureInFlight = false
        baseNode.discardDisplayList()
        sceneNode.discardDisplayList()
        baseSignature = 0L
        sceneRecordedAt = 0L
        sceneRecordedWidth = -1
        sceneRecordedHeight = -1
    }

    /**
     * Drives the unfold. The geometry 鈥?and with it the panel path, the tint and the row
     * positions 鈥?follows [expandProgress]; the backdrop keeps the full panel's rect so the
     * material itself never shifts while the panel grows over it.
     */
    private fun animateExpand(from: Float, to: Float, duration: Long, interpolator: PathInterpolator) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { animation ->
                expandProgress = animation.animatedValue as Float
                applyExpand()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (to <= 0f) closeNow()
                }
            })
            start()
        }
    }

    /** Recomputes the visible panel rect and the path that clips the material to it. */
    private fun applyExpand() {
        if (fullPanel.width() <= 0f || fullPanel.height() <= 0f) return
        val visibleHeight = fullPanel.height() * expandProgress.coerceIn(0f, 1f)
        panel.set(fullPanel.left, fullPanel.top, fullPanel.right, fullPanel.top + visibleHeight)
        // The module's own squircle, with the corner rule the bar uses 鈥?so the panel and the
        // bar it hangs from share a corner language instead of two different round rects. The
        // radius collapses towards a stadium while the panel is short.
        panelPath.setBottomBarSquircle(
            panel,
            cornerRadiusPx.coerceAtMost(panel.height() / 2f),
            cornerSmoothing,
        )
        // A dirty rectangle, not the whole view: this overlay is MATCH_PARENT, so a bare
        // invalidate() marks the entire screen dirty and the app's parent display list is
        // re-recorded over all of it. The padding covers the blur and the rim, and the whole
        // of fullPanel covers both the old and the new visible part.
        val pad = NativeBackdropBlur.padding(
            blurRadiusPx,
            CAPTURE_PADDING_DP * resources.displayMetrics.density,
        ).roundToInt()
        if (width > 0 && height > 0) {
            invalidate(
                (fullPanel.left.roundToInt() - pad).coerceAtLeast(0),
                (fullPanel.top.roundToInt() - pad).coerceAtLeast(0),
                (fullPanel.right.roundToInt() + pad).coerceAtMost(width),
                (fullPanel.bottom.roundToInt() + pad).coerceAtMost(height),
            )
        } else {
            invalidate()
        }
    }

    /** One readback of the window region behind the panel, as the base of the material. */
    private fun captureBackdrop() {
        if (captureInFlight || width <= 0 || height <= 0) return
        val root = rootView
        val viewRoot = runCatching { Xposed.callMethod(root, "getViewRootImpl") }.getOrNull()
            ?: return
        val surface = readPrivateField(viewRoot, "mSurface") as? Surface ?: return
        if (!surface.isValid) return

        val viewLocation = IntArray(2).also(::getLocationInWindow)
        val rootLocation = IntArray(2).also(root::getLocationInWindow)
        val left = viewLocation[0] - rootLocation[0] + fullPanel.left.roundToInt()
        val top = viewLocation[1] - rootLocation[1] + fullPanel.top.roundToInt()
        val panelWidth = fullPanel.width().roundToInt()
        val panelHeight = fullPanel.height().roundToInt()
        if (panelWidth <= 0 || panelHeight <= 0) return

        // Blur needs real neighbouring pixels, so the copy reaches past the panel 鈥?as far
        // as the window allows.
        val wanted = NativeBackdropBlur.padding(
            blurRadiusPx,
            CAPTURE_PADDING_DP * resources.displayMetrics.density,
        )
        val padding = minOf(
            wanted,
            left.toFloat(),
            top.toFloat(),
            (root.width - left - panelWidth).toFloat(),
            (root.height - top - panelHeight).toFloat(),
        ).coerceAtLeast(0f).roundToInt()
        captureRect.set(
            left - padding,
            top - padding,
            left + panelWidth + padding,
            top + panelHeight + padding,
        )
        if (captureRect.width() <= 0 || captureRect.height() <= 0) return
        val targetWidth = max(1, (captureRect.width() / CAPTURE_DOWNSCALE).roundToInt())
        val targetHeight = max(1, (captureRect.height() / CAPTURE_DOWNSCALE).roundToInt())
        val target = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val generation = ++captureGeneration
        captureInFlight = true
        runCatching {
            PixelCopy.request(surface, captureRect, target, { result ->
                captureInFlight = false
                if (generation != captureGeneration || result != PixelCopy.SUCCESS) {
                    target.recycle()
                    return@request
                }
                backdrop?.takeUnless(Bitmap::isRecycled)?.recycle()
                backdrop = target
                postInvalidateOnAnimation()
            }, mainHandler)
        }.onFailure {
            captureInFlight = false
            target.recycle()
            log("window copy threw $it")
        }

        // The video layer is sampled separately: it is the one part of the scene a window
        // copy can miss.
        videoSampler.request(this, true, false)
        mainHandler.removeCallbacks(videoRefresh)
        mainHandler.postDelayed(videoRefresh, VIDEO_REFRESH_MS)
    }

    private fun refreshVideoFrame() {
        if (items.isEmpty()) return
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty() || panel.height() <= 0f) return
        val appear = MIN_APPEAR_ALPHA + (1f - MIN_APPEAR_ALPHA) * expandProgress.coerceIn(0f, 1f)
        // Everything is clipped to the unfolded panel, so rows appear as it grows instead of
        // being squashed or drawn outside it.
        val save = canvas.save()
        canvas.clipPath(panelPath)
        drawGlassBackdrop(canvas)
        panelPaint.color = withAlpha(panelColor, appear)
        canvas.drawPath(panelPath, panelPaint)
        borderPaint.strokeWidth = resources.displayMetrics.density
        borderPaint.color = withAlpha(borderColor, appear)
        canvas.drawPath(panelPath, borderPaint)
        drawBloom(canvas, appear)

        val padding = PANEL_PADDING_DP * resources.displayMetrics.density
        val itemHorizontalPadding = ITEM_HORIZONTAL_PADDING_DP * resources.displayMetrics.density
        val itemVerticalInset = 6f * resources.displayMetrics.density
        val itemRadius = 15f * resources.displayMetrics.density
        items.forEachIndexed { index, label ->
            val column = index % COLUMNS
            val row = index / COLUMNS
            val rowTop = panel.top + padding + row * rowHeight
            val baseline = rowTop + rowHeight / 2f -
                (labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f
            val textWidth = labelPaint.measureText(label)
            val cellLeft = panel.left + padding + column * columnWidth
            val cellRight = cellLeft + columnWidth
            val textX = cellLeft + (columnWidth - textWidth) / 2f
            itemBorderRect.set(
                (textX - itemHorizontalPadding).coerceAtLeast(cellLeft + 2f),
                rowTop + itemVerticalInset,
                (textX + textWidth + itemHorizontalPadding).coerceAtMost(cellRight - 2f),
                rowTop + rowHeight - itemVerticalInset,
            )
            itemBorderPaint.strokeWidth = (if (index == selected) 1.15f else 0.9f) *
                resources.displayMetrics.density
            itemBorderPaint.color = if (index == selected) {
                withAlpha(labelColor, appear * 0.55f)
            } else {
                withAlpha(borderColor, appear * 0.75f)
            }
            canvas.drawRoundRect(
                itemBorderRect,
                itemRadius,
                itemRadius,
                itemBorderPaint,
            )
            // One ink for every row, the same the capsule's own label uses; the unfold's
            // appear ramp still fades the whole panel in together.
            labelPaint.color = withAlpha(labelColor, appear)
            canvas.drawText(
                label,
                textX,
                baseline,
                labelPaint,
            )
        }
        canvas.restoreToCount(save)
    }

    /**
     * The glass material, live: the replayed scene on top of a blurred base layer, both
     * clipped to the panel. While the panel is open this keeps asking for frames, exactly
     * like the bars' own pump.
     */
    private fun drawGlassBackdrop(canvas: Canvas) {
        if (!materialEnabled || !canvas.isHardwareAccelerated || width <= 0 || height <= 0) return
        val viewLocation = viewLocationScratch.also(::getLocationInWindow)
        val padding = NativeBackdropBlur.padding(
            blurRadiusPx,
            CAPTURE_PADDING_DP * resources.displayMetrics.density,
        ).roundToInt()
        sceneRect.set(
            viewLocation[0] + fullPanel.left.roundToInt() - padding,
            viewLocation[1] + fullPanel.top.roundToInt() - padding,
            viewLocation[0] + fullPanel.right.roundToInt() + padding,
            viewLocation[1] + fullPanel.bottom.roundToInt() + padding,
        )
        sceneOrigin[0] = sceneRect.left
        sceneOrigin[1] = sceneRect.top
        val sceneWidth = sceneRect.width()
        val sceneHeight = sceneRect.height()
        if (sceneWidth <= 0 || sceneHeight <= 0) return
        val translateX = (sceneOrigin[0] - viewLocation[0]).toFloat()
        val translateY = (sceneOrigin[1] - viewLocation[1]).toFloat()

        val windowCopy = backdrop?.takeUnless(Bitmap::isRecycled)
        val video = videoSampler.snapshot()
        // The scene node keeps live references to the app's child RenderNodes, so its pixels
        // keep updating with the app's own traversal. It only needs re-recording when the
        // sibling set/layout changes; the old code re-recorded it on every settled frame.
        val animating = closing || expandProgress < 0.999f
        val now = android.os.SystemClock.uptimeMillis()
        val sceneSizeChanged = sceneRecordedWidth != sceneWidth || sceneRecordedHeight != sceneHeight
        val hasScene = if (animating) {
            sceneNode.hasDisplayList()
        } else if (!sceneNode.hasDisplayList() || sceneSizeChanged ||
            now - sceneRecordedAt >= SCENE_REFRESH_MS
        ) {
            val recorded = recordLiveScene(sceneWidth, sceneHeight)
            sceneRecordedAt = now
            sceneRecordedWidth = sceneWidth
            sceneRecordedHeight = sceneHeight
            recorded
        } else {
            true
        }
        // Blur + refraction, or just the blur when the shader could not be built. The base
        // layer takes the plain blur: the scene covers it whenever it has content, so
        // running the lens over both would pay for the same pixels twice per frame.
        val effect = materialEffect ?: baseBlurEffect
        val baseEffect = baseBlurEffect

        // The base only changes when its inputs do: a new video epoch, a new window copy, or a
        // scene geometry/origin change. Re-recording it on every settled frame was the menu's
        // largest main-thread cost.
        var baseSig = 17L
        baseSig = baseSig * 31 + (video?.epoch ?: -1)
        baseSig = baseSig * 31 + System.identityHashCode(windowCopy)
        baseSig = baseSig * 31 + sceneWidth
        baseSig = baseSig * 31 + sceneHeight
        baseSig = baseSig * 31 + sceneOrigin[0]
        baseSig = baseSig * 31 + sceneOrigin[1]
        baseSig = baseSig * 31 + captureRect.left
        baseSig = baseSig * 31 + captureRect.top
        baseSig = baseSig * 31 + captureRect.right
        baseSig = baseSig * 31 + captureRect.bottom
        val needBase = (windowCopy != null || video != null) &&
            (baseSig != baseSignature || !baseNode.hasDisplayList())
        if (needBase) {
            val recording = baseNode.beginRecording(sceneWidth, sceneHeight)
            recording.clipRect(0f, 0f, sceneWidth.toFloat(), sceneHeight.toFloat())
            video?.let { drawVideo(recording, it) }
            windowCopy?.let { bitmap ->
                recording.drawBitmap(
                    bitmap,
                    null,
                    RectF(
                        (captureRect.left - sceneOrigin[0]).toFloat(),
                        (captureRect.top - sceneOrigin[1]).toFloat(),
                        (captureRect.right - sceneOrigin[0]).toFloat(),
                        (captureRect.bottom - sceneOrigin[1]).toFloat(),
                    ),
                    null,
                )
            }
            baseNode.endRecording()
            baseNode.setPosition(0, 0, sceneWidth, sceneHeight)
            baseNode.setRenderEffect(baseEffect)
            baseSignature = baseSig
        }

        if (baseNode.hasDisplayList()) {
            val save = canvas.save()
            canvas.clipPath(panelPath)
            canvas.translate(translateX, translateY)
            canvas.drawRenderNode(baseNode)
            canvas.restoreToCount(save)
        }

        if (hasScene) {
            sceneNode.setPosition(0, 0, sceneWidth, sceneHeight)
            sceneNode.setRenderEffect(effect)
            val save = canvas.save()
            canvas.clipPath(panelPath)
            canvas.translate(translateX, translateY)
            // The scene is isolated in its own layer: a SurfaceView records a CLEAR for its
            // separate media layer, and inside this layer that clear can only reveal the
            // blurred media frame below it, never erase it.
            val layer = canvas.saveLayer(0f, 0f, sceneWidth.toFloat(), sceneHeight.toFloat(), null)
            canvas.drawRenderNode(sceneNode)
            canvas.restoreToCount(layer)
            canvas.restoreToCount(save)
        }

        // Keep the material live while the panel is on screen, like the bars' frame pump.
        if (isShown) postInvalidateOnAnimation()
    }

    /**
     * Re-records the display lists of the views drawn below this overlay. Their pixels stay
     * current 鈥?a TextureView feed keeps submitting into that tree 鈥?so replaying them every
     * frame is what makes the panel live without a single readback.
     */
    private fun recordLiveScene(sceneWidth: Int, sceneHeight: Int): Boolean {
        val parent = this.parent as? ViewGroup ?: return false
        val index = parent.indexOfChild(this)
        if (index <= 0) return false
        val recording = sceneNode.beginRecording(sceneWidth, sceneHeight)
        recording.clipRect(0f, 0f, sceneWidth.toFloat(), sceneHeight.toFloat())
        var drawn = 0
        for (childIndex in 0 until index) {
            val sibling = parent.getChildAt(childIndex) ?: continue
            // Our own overlays are above the app content but below this panel; neither
            // belongs in the material.
            if (sibling is GlassHostLayout || sibling is DouyinTopTabMenu) continue
            if (sibling.visibility != View.VISIBLE || sibling.alpha <= 0.001f) continue
            if (sibling.width <= 0 || sibling.height <= 0) continue
            val location = locationScratch.also(sibling::getLocationInWindow)
            if (location[0] + sibling.width <= sceneRect.left || location[0] >= sceneRect.right) continue
            if (location[1] + sibling.height <= sceneRect.top || location[1] >= sceneRect.bottom) continue
            val node = viewRenderNode(sibling) ?: continue
            if (!node.hasDisplayList()) continue
            recording.save()
            recording.translate(
                (location[0] - sceneOrigin[0]).toFloat(),
                (location[1] - sceneOrigin[1]).toFloat(),
            )
            recording.drawRenderNode(node)
            recording.restore()
            drawn++
        }
        sceneNode.endRecording()
        return drawn > 0
    }

    /** The part of the sampled video surface that sits behind the panel. */
    private fun drawVideo(canvas: Canvas, video: AppVideoBackdrop) {
        val bitmap = video.bitmap.takeUnless(Bitmap::isRecycled) ?: return
        if (video.surfaceWidth <= 0 || video.surfaceHeight <= 0) return
        val surfaceLeft = video.surfaceLeft
        val surfaceTop = video.surfaceTop
        val scaleX = bitmap.width.toFloat() / video.surfaceWidth
        val scaleY = bitmap.height.toFloat() / video.surfaceHeight
        val cropLeft = max(sceneRect.left, surfaceLeft)
        val cropTop = max(sceneRect.top, surfaceTop)
        val cropRight = minOf(sceneRect.right, surfaceLeft + video.surfaceWidth)
        val cropBottom = minOf(sceneRect.bottom, surfaceTop + video.surfaceHeight)
        if (cropRight <= cropLeft || cropBottom <= cropTop) return
        canvas.drawBitmap(
            bitmap,
            Rect(
                ((cropLeft - surfaceLeft) * scaleX).roundToInt(),
                ((cropTop - surfaceTop) * scaleY).roundToInt(),
                ((cropRight - surfaceLeft) * scaleX).roundToInt(),
                ((cropBottom - surfaceTop) * scaleY).roundToInt(),
            ),
            RectF(
                (cropLeft - sceneOrigin[0]).toFloat(),
                (cropTop - sceneOrigin[1]).toFloat(),
                (cropRight - sceneOrigin[0]).toFloat(),
                (cropBottom - sceneOrigin[1]).toFloat(),
            ),
            null,
        )
    }

    /**
     * The Retained RenderNode of Android's own traversal, refreshed only when it has no
     * display list yet.
     */
    private fun viewRenderNode(view: View): RenderNode? {
        val retained = runCatching { retainedRenderNodeField?.get(view) as? RenderNode }.getOrNull()
        if (retained?.hasDisplayList() == true) return retained
        return runCatching {
            Xposed.callMethod(view, "updateDisplayListIfDirty") as? RenderNode
        }.getOrNull() ?: retained
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                // A pick is only accepted once the panel has unfolded; a tap during the
                // fold-back just dismisses it.
                val row = if (closing) -1 else itemAt(event.x, event.y)
                close()
                if (row >= 0) onPick?.invoke(row)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                close()
                return true
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closeNow()
        videoSampler.dispose()
    }

    /** Grid hit-test: which item the tap landed on, or -1 outside the panel's rows. */
    private fun itemAt(x: Float, y: Float): Int {
        if (items.isEmpty()) return -1
        val padding = PANEL_PADDING_DP * resources.displayMetrics.density
        if (y < panel.top + padding || y > panel.bottom - padding) return -1
        if (x < panel.left + padding || x > panel.right - padding) return -1
        val row = ((y - panel.top - padding) / rowHeight).toInt()
        val column = ((x - panel.left - padding) / columnWidth).toInt()
        if (row < 0 || row >= rows || column < 0 || column >= COLUMNS) return -1
        return (row * COLUMNS + column).takeIf { it in items.indices } ?: -1
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

    /**
     * Menu tracing.
     *
     * All of these go through one helper so they can be switched off together. They are
     * genuinely useful when the panel misbehaves (the "draw" line reports which backdrop
     * sources resolved, the "expand done" line reports frame pacing), but one of them fires
     * on every draw and another on every backdrop copy, which floods the LSPosed module log
     * while the menu is open. Off by default; flip it while investigating the panel.
     */
    private val MENU_DIAG_ENABLED = false

    private fun log(message: String) {
        if (!MENU_DIAG_ENABLED) return
        Xposed.log("[OfflineGlass][TopMenu] $message")
    }

    private fun withAlpha(color: Int, scale: Float): Int = Color.argb(
        (Color.alpha(color) * scale.coerceIn(0f, 1f)).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )
}
