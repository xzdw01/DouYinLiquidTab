package io.github.xzdw.dylt.hook.adapters.douyin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import io.github.xzdw.dylt.hook.adapters.AdapterNavigationFrame

/**
 * Douyin's top bar artwork, drawn the same way as the bottom bar:
 *
 *   ( ☰ )   [ 亳州  精选  关注  商城  推荐 ]   ( 🔍 )
 *
 * The capsule and its selected pill, the two circles, the glass, the blur and the
 * refraction all come from the shared host (see [GlassHostLayout]); this renderer only
 * paints the labels and the two glyphs, and it lays them out on the host's own slot
 * geometry so a label sits exactly on the selection capsule underneath it.
 *
 * Labels are whatever Douyin's own tab cells currently say — nothing is hardcoded.
 */
internal class DouyinTopBarRenderer(@Suppress("UNUSED_PARAMETER") context: Context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** Reused on the draw path: `Paint.fontMetrics` allocates a FontMetrics per read. */
    private val labelFontMetrics = Paint.FontMetrics()

    private var labelTextSize = -1f

    fun draw(
        canvas: Canvas,
        frame: AdapterNavigationFrame,
        labels: List<String>,
        selectedIndex: Int,
        leftAction: Boolean,
        rightAction: Boolean,
        inlineAction: Boolean,
    ) {
        val width = frame.width
        val height = frame.height
        if (width <= 0 || height <= 0) return
        val density = frame.density
        val button = height.toFloat()
        // The corner circles may be smaller than the bar; their glyphs scale with them.
        val circle = frame.actionButtonPx.takeIf { it > 0f } ?: button
        // The circles now fill the bar height (topBarActionButtonFraction = 1), but the
        // drawer/magnifier outlines were measured against the old 0.88 circle. Keep the
        // glyph at that measured size so only the glass key grows.
        val glyph = button * ACTION_GLYPH_FRACTION

        // Same palette as the labels: black on the light glass, white on the dark one, held at
        // the same 80% the selected label uses so the keys never read brighter than the text.
        val foreground = withAlpha(
            if (frame.darkGlass) GLYPH_COLOR else GLYPH_COLOR_LIGHT,
            GLYPH_ALPHA,
        )
        if (leftAction && frame.leftActionReservePx > 0f) {
            drawHamburger(canvas, circle / 2f, height / 2f, glyph, density, foreground)
        }
        if (rightAction && frame.rightActionReservePx > 0f) {
            drawMagnifier(canvas, width - circle / 2f, height / 2f, glyph, density, foreground)
        }
        if (inlineAction && frame.inlineActionWidthPx > 0f) {
            // The tab-menu key sits inside the capsule, ahead of the slots.
            val centre = 4f * density + frame.leftActionReservePx + frame.inlineActionWidthPx / 2f
            drawDots(canvas, centre, height / 2f, button, foreground)
        }
        if (frame.iconOnly || labels.isEmpty()) return

        val textSize = (frame.textSize * frame.scaledDensity).coerceAtLeast(1f)
        if (textSize != labelTextSize) {
            labelTextSize = textSize
            labelPaint.textSize = textSize
        }
        val base = if (frame.darkGlass) LABEL_SELECTED_COLOR else LABEL_SELECTED_COLOR_LIGHT
        val dim = if (frame.darkGlass) LABEL_UNSELECTED_COLOR else LABEL_UNSELECTED_COLOR_LIGHT
        // Two constant inks, resolved once per draw instead of per label per frame.
        val selectedLabelColor = withAlpha(base, LABEL_SELECTED_ALPHA)
        val unselectedLabelColor = withAlpha(dim, LABEL_UNSELECTED_ALPHA)
        labelPaint.getFontMetrics(labelFontMetrics)
        val baseline = height / 2f -
            (labelFontMetrics.ascent + labelFontMetrics.descent) / 2f
        val slotWidth = frame.slotWidth
        // Same slot band the host uses for the selection capsule: past the leading circle
        // and past the tab-menu key that sits inside the capsule.
        val bandLeft = 4f * density + frame.leftActionReservePx + frame.inlineActionWidthPx
        labels.forEachIndexed { index, label ->
            val centreX = bandLeft + (index + 0.5f) * slotWidth
            labelPaint.color = if (index == selectedIndex) {
                selectedLabelColor
            } else {
                unselectedLabelColor
            }
            canvas.drawText(label, centreX, baseline, labelPaint)
        }
    }

    /**
     * The leading corner action: the drawer key. It sits in its own glass circle to the left
     * of the capsule, exactly as the module's first top bar drew it.
     */
    /**
     * The leading corner action: Douyin's own drawer glyph — three bars sharing one left
     * edge, the longest on top and each one shorter than the one above it. The bars are
     * pitched a little over their own thickness apart, because equal-length lines packed at
     * one stroke's pitch read as a single solid block at this size.
     */
    private fun drawHamburger(
        canvas: Canvas,
        centreX: Float,
        centreY: Float,
        button: Float,
        density: Float,
        color: Int,
    ) {
        val longest = button * HAMBURGER_WIDTH_RATIO
        val pitch = button * HAMBURGER_GAP_RATIO
        // The longest bar defines the glyph's width, so centring it centres the whole glyph;
        // the other two are left-aligned to it.
        val left = centreX - longest / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = GLYPH_STROKE_DP * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color
        HAMBURGER_LENGTHS.forEachIndexed { index, factor ->
            val y = centreY + (index - 1) * pitch
            canvas.drawLine(left, y, left + longest * factor, y, paint)
        }
    }

    /** The in-pill tab-menu key: four solid dots in a 2x2 grid. */
    private fun drawDots(canvas: Canvas, centreX: Float, centreY: Float, button: Float, color: Int) {
        val radius = button * DOT_RADIUS_RATIO
        val offset = button * DOT_OFFSET_RATIO
        paint.style = Paint.Style.FILL
        paint.color = color
        for (column in -1..1 step 2) {
            for (row in -1..1 step 2) {
                canvas.drawCircle(
                    centreX + column * offset,
                    centreY + row * offset,
                    radius,
                    paint,
                )
            }
        }
    }

    /** The trailing corner action, drawn as a magnifier. */
    private fun drawMagnifier(
        canvas: Canvas,
        centreX: Float,
        centreY: Float,
        button: Float,
        density: Float,
        color: Int,
    ) {
        val radius = button * LENS_RADIUS_RATIO
        val stroke = GLYPH_STROKE_DP * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color
        val lensCentreX = centreX - button * LENS_OFFSET_RATIO
        val lensCentreY = centreY - button * LENS_OFFSET_RATIO
        canvas.drawCircle(lensCentreX, lensCentreY, radius, paint)
        // The handle starts *on* the lens's edge. Starting it inside (at 0.72 of the radius)
        // pushed its rounded cap into the glass and read as a blob growing out of the lens.
        val handleStart = radius
        val handleEnd = radius + button * HANDLE_LENGTH_RATIO
        val diagonal = 0.70710678f
        canvas.drawLine(
            lensCentreX + handleStart * diagonal,
            lensCentreY + handleStart * diagonal,
            lensCentreX + handleEnd * diagonal,
            lensCentreY + handleEnd * diagonal,
            paint,
        )
    }

    private fun withAlpha(color: Int, scale: Float): Int =
        Color.argb(
            (Color.alpha(color) * scale.coerceIn(0f, 1f)).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    internal companion object {
        /**
         * The colour the label inside the selection capsule is painted with. The dropdown
         * mirrors it for every one of its rows, so the two lists can never disagree about
         * what a tab label looks like.
         */
        internal fun selectedLabelColor(darkGlass: Boolean): Int =
            if (darkGlass) LABEL_SELECTED_COLOR else LABEL_SELECTED_COLOR_LIGHT

        /** Glyph proportions, all relative to the bar height (the circle's diameter). */
        const val HAMBURGER_WIDTH_RATIO = 0.32f
        const val HAMBURGER_GAP_RATIO = 0.14f
        /**
         * The drawer/magnifier glyphs keep the size they had when the corner circles were
         * 0.88 of the bar height. The glass circle is now full height, so the glyph sits in
         * a slightly larger key instead of growing with it.
         */
        const val ACTION_GLYPH_FRACTION = 0.88f

        /** Descending bar lengths, longest first — Douyin's own drawer glyph. */
        val HAMBURGER_LENGTHS = floatArrayOf(1f, 0.78f, 0.55f)
        const val DOT_RADIUS_RATIO = 0.075f
        const val DOT_OFFSET_RATIO = 0.105f
        const val GLYPH_STROKE_DP = 2.3f
        /**
         * The magnifier is sized so its body matches the drawer bars opposite it. Measured on the
         * device (white-pixel bounding boxes inside the circle, bloom excluded): the bars come out
         * 49x45 with [HAMBURGER_WIDTH_RATIO] 0.32, while 0.12 lens + 0.15 handle measured 53x53 —
         * 4px wider and 8px taller than the bars. 0.11/0.135 shrinks that footprint by the ~9% the
         * measurement calls for, so the two side keys read the same size.
         */
        const val LENS_RADIUS_RATIO = 0.11f
        const val LENS_OFFSET_RATIO = 0.04f
        /**
         * Handle length beyond the lens's edge: 0.135 is still 50% longer than the original 0.09
         * (17.4 against 11.6 px here) and puts the tip about halfway from the circle's centre to
         * its rim.
         */
        const val HANDLE_LENGTH_RATIO = 0.135f

        /** Reference label colours: pure black/white, held at 90% when selected, 60% when not. */
        const val LABEL_SELECTED_COLOR = 0xFFFFFFFF.toInt()
        const val LABEL_UNSELECTED_COLOR = 0xFFFFFFFF.toInt()
        const val LABEL_SELECTED_COLOR_LIGHT = 0xFF000000.toInt()
        const val LABEL_UNSELECTED_COLOR_LIGHT = 0xFF000000.toInt()
        /** Selected entries keep 90%: 0xFF × 0.90 = 229. */
        const val LABEL_SELECTED_ALPHA = 0.90f
        /** Unselected entries drop to 60%: 0xFF × 0.60 = 153. */
        const val LABEL_UNSELECTED_ALPHA = 0.60f

        const val GLYPH_COLOR = 0xFFFFFFFF.toInt()
        const val GLYPH_COLOR_LIGHT = 0xFF000000.toInt()
        /** The keys (drawer, magnifier, tab-menu dots) match the selected label: 0xFF × 0.90. */
        const val GLYPH_ALPHA = 0.90f
    }
}
