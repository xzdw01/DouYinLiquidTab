package io.github.xzdw.dylt.hook.adapters.douyin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.github.xzdw.dylt.hook.adapters.AdapterNavigationFrame
import io.github.xzdw.dylt.hook.adapters.NavigationBadge
import kotlin.math.roundToInt

/**
 * Douyin's bar artwork: each of the four entries is a traced glyph with its label under it.
 *
 *   [ ^首页  ^朋友  ^消息  ^我 ]        ( + )
 *     ˉˉˉˉ   ˉˉˉˉ   ˉˉˉˉ   ˉˉˉˉ
 *
 * The selected entry takes the design's blue (glyph and label), the others the reference's
 * white — or black on light glass, where white would be invisible. The selected entry also
 * sits on the flat taupe capsule drawn by the shared host (see DouyinAdapter.indicatorTint). The
 * publish action is a separate circular glass button to the right of the main bar,
 * drawn here with an outlined rounded-square plus glyph.
 *
 * The host always reserves [PUBLISH_SLOT_WIDTH_RATIO] of the panel width for that
 * button, so the pill's four labels are laid out inside the remaining width.
 *
 * 底栏菜单项隐藏 (the settings page's switches) removes entries from the row: the host drops
 * them from `frame.enabledIndices` and shortens the pill to the remaining count, and this
 * renderer lays out only those entries, compacted, on the band the host's capsule uses.
 */
internal class DouyinNavigationRenderer(@Suppress("UNUSED_PARAMETER") context: Context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The reference label weight, shared by the text-only and icon-only styles. */
    private val labelTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /**
     * The icon+text style's label: one step heavier than the reference weight. Only that style uses
     * it — the two single-element styles keep the medium face they were designed with.
     */
    private val labelBoldTypeface = Typeface.create("sans-serif", Typeface.BOLD)

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = labelTypeface
    }

    /** Reused on the draw path: `Paint.fontMetrics` allocates a FontMetrics per read. */
    private val labelFontMetrics = Paint.FontMetrics()
    private val labelPath = Path()
    /** Decoded custom publish icon, kept per encoded value. */
    private var publishIconSource = ""
    private var publishIconBitmap: Bitmap? = null
    /** Scratch path/rect/paint for the circular crop of the picked publish icon. */
    private val publishClipPath = Path()
    private val publishIconDest = RectF()

    /**
     * Dedicated paint for the user-picked publish icon, so its 95% opacity cannot leak into the
     * tab glyphs (which share [iconPaint] and deliberately use 0.60 / 0.90 / 1.0 per entry).
     */
    private val publishIconPaint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (PUBLISH_ICON_ALPHA * 255f).roundToInt().coerceIn(0, 255)
        }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /**
     * Glyph bounds, measured once. The glyphs are height-normalised, so their widths differ
     * (19.3 / 34.4 / 28.6 / 22.8 units) and the widest one still has to fit its slot.
     */
    private val glyphBounds = Array(LABELS.size) { RectF() }

    init {
        for (slot in LABELS.indices) tabIcon(slot).computeBounds(glyphBounds[slot], true)
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        // The resting row only. The selection ink arrives through drawSelectionInk, which decides for
        // itself whether this frame's copy belongs to the lens scene or sits over the glass.
        drawEntries(
            canvas, frame, accentEveryEntry = false,
            discreteAccent = frame.accentClip == null, drawPublish = true,
        )
    }

    /**
     * The selection's ink, in whichever pass owns it for this frame.
     *
     * `inLensScene = true` is the host recording the layer its lens samples: the ink is then bent and
     * spectrally split like everything else behind the glass, and because the capsule follows the
     * finger the fill sweeps with the drag instead of switching when the selection commits on release.
     * `inLensScene = false` is the resting look: the same ink clipped to the same capsule, but painted
     * over the glass — where the capsule's edge refraction band cannot smear the glyph's own extremes
     * into stray slivers, and the entry keeps the exact accent colour it has when it is dragged.
     */
    fun drawSelectionInk(canvas: Canvas, frame: AdapterNavigationFrame, inLensScene: Boolean) {
        // Only the pass that matches this frame's state draws; the host calls both every frame.
        if (inLensScene != frame.selectionInkInLensScene) return
        val capsule = frame.accentClip ?: return
        val saved = canvas.save()
        canvas.clipPath(capsule)
        drawEntries(canvas, frame, accentEveryEntry = true, discreteAccent = false, drawPublish = false)
        canvas.restoreToCount(saved)
    }

    /** One pass of the entry row. */
    private fun drawEntries(
        canvas: Canvas,
        frame: AdapterNavigationFrame,
        accentEveryEntry: Boolean,
        discreteAccent: Boolean,
        drawPublish: Boolean,
    ) {
        val width = frame.width
        val height = frame.height
        if (width <= 0 || height <= 0) return

        val density = frame.density
        // 底栏显示样式: 图标 + 文字 (both true), 仅图标 (labels off) or 仅文字 (glyph off). The block
        // below is built from whichever halves are on, and it is centred in the pill either way —
        // so a single-element style comes out vertically centred without a special case.
        val showIcon = !frame.textOnly
        val showLabels = !frame.iconOnly

        // Design B: a glyph with a smaller label underneath. The label still takes the host's
        // own textSize setting as its base and is scaled down here, because two lines have to
        // fit the same pill the single-line reference labels used. 仅文字 has no second line above
        // it, so it takes the full text size instead of that two-line reduction.
        var labelSize = (frame.textSize * frame.scaledDensity *
            if (frame.textOnly) 1f else LABEL_SIZE_SCALE).coerceAtLeast(1f)

        // The publish button is a circle as tall as the bar, separated from the pill by
        // the same gap the host reserves (DouyinAdapter.navigationActionReserveGapDp).
        // Labels use the host's own slot geometry (4dp side padding, slotWidth =
        // (width - padding - reserve) / visible entries) so they sit exactly on the selection
        // capsule instead of drifting off it by a few dp.
        //
        // With "去除发布按钮" on, the host reports no trailing reserve at all: there is no circle
        // to draw and the entries take the whole width, which is exactly the slot band the
        // host itself lays out in tabWidthPx().
        val showPublish = frame.rightActionReservePx > 0f
        val buttonSize = if (showPublish) height.toFloat() else 0f
        val gap = if (showPublish) PUBLISH_GAP_DP * density else 0f
        val sidePadding = 4f * density

        // 底栏菜单项隐藏: draw only the entries the host still offers. The mask is a set of
        // native slot indices, the bar's four entries sit on 0,1,3,4 of that five-slot row
        // (2 = the publish cell), and the host already removed the hidden ones from
        // `enabledIndices` — so filter the label table through it and compact: a hidden entry
        // leaves no gap, and the remaining labels share the pill on exactly the band the
        // host's own capsule uses (tabWidthPx divides by the same count).
        val entries = NATIVE_SLOTS.indices.filter { NATIVE_SLOTS[it] in frame.enabledIndices }
        val entryCount = entries.size.coerceAtLeast(1)
        val slotWidth = ((width - 2f * sidePadding - buttonSize - gap).coerceAtLeast(1f)) / entryCount
        // 底栏菜单文字: the user's names replace the built-in ones entry by entry, so a blank or
        // missing line keeps the app's own name (see the settings page's placeholder).
        val labels = Array(LABELS.size) { entry ->
            frame.customEntryLabels.getOrNull(entry)?.takeIf { it.isNotEmpty() } ?: LABELS[entry]
        }

        // The selected entry is the one the host's capsule sits on, looked up in the compacted
        // order. -1 (its page has no button, or the app is mid-transition) leaves every entry
        // unselected; the capsule itself is still drawn by the host.
        val visualSelected = entries.indexOf(frame.selectedIndex)

        labelPaint.textSize = labelSize
        // 图标+文字 carries a bold label; 仅文字 and 仅图标 keep the reference medium face.
        labelPaint.typeface = if (showIcon && showLabels) labelBoldTypeface else labelTypeface
        labelPaint.getFontMetrics(labelFontMetrics)
        var textHeight = if (showLabels) labelFontMetrics.descent - labelFontMetrics.ascent else 0f
        var iconSize = (minOf(slotWidth * ICON_SLOT_RATIO, height * ICON_BAR_RATIO) *
            frame.iconScale * (if (frame.iconOnly) ICON_ONLY_SCALE else 1f))
            .coerceAtLeast(1f)
        // Only the two-line layout spends a gap between the glyph and its label.
        val iconTextGap = if (showIcon && showLabels) ICON_TEXT_GAP_DP * density else 0f

        // Keep the block clear of the pill's edge refraction band. The lens displaces whatever
        // sits within its refraction height of the pill edge, so the block's extremes have to stay
        // BLOCK_EDGE_CLEARANCE_DP inside the pill itself.
        //
        // The capsule's own vertical inset is deliberately NOT subtracted here: the capsule is the
        // selection indicator drawn on top of the block, not a wall the block has to clear. Folding
        // it in cost the glyph ~23% of its size (the block was capped at 98px instead of 126px),
        // which is why the icons ended up looking small next to the reference bar.
        val maxBlockHeight = (height - 2f * BLOCK_EDGE_CLEARANCE_DP * density).coerceAtLeast(1f)
        val wantedBlock = (if (showIcon) iconSize else 0f) +
            if (showLabels) iconTextGap + textHeight else 0f
        if (wantedBlock > maxBlockHeight) {
            val shrink = maxBlockHeight / wantedBlock
            if (showIcon) iconSize *= shrink
            if (showLabels) {
                labelSize *= shrink
                labelPaint.textSize = labelSize
                labelPaint.getFontMetrics(labelFontMetrics)
                textHeight = labelFontMetrics.descent - labelFontMetrics.ascent
            }
        }

        // Whatever the bar draws is centred in the pill: glyph + label as one block, or the single
        // element a 仅图标 / 仅文字 style leaves.
        val blockHeight = (if (showIcon) iconSize else 0f) +
            if (showLabels) iconTextGap + textHeight else 0f
        val blockTop = (height - blockHeight) / 2f
        val textBaseline = blockTop + (if (showIcon) iconSize + iconTextGap else 0f) - labelFontMetrics.ascent

        // Everything below is drawn in the host's own coordinates so it lands exactly on
        // the panel path the host builds (a pill plus a separate circle). The host's
        // content scale must NOT be applied here: it scales about the panel centre, which
        // pushed the right-hand publish button ~60px left of its circle and shifted the
        // outer labels off the selection capsule.
        // The publish glyph rides the glass exactly like the labels do: black on the light bar,
        // white on the dark one, at the selected label's own alpha (0xFF × 0.90 = 229).
        val glyphColor = withAlpha(
            if (frame.darkGlass) LABEL_SELECTED_COLOR else LABEL_SELECTED_COLOR_LIGHT,
            LABEL_SELECTED_ALPHA,
        )

        // Selected entries take the design's blue on dark glass. On light glass the glyph takes the
        // design's mint while the label keeps the TOP BAR's own selected style - black at the top
        // bar's own alpha - so the two bars read as one system. Unselected entries are the top
        // bar's unselected colour at its unselected alpha (white on dark, black on light).
        val unselectedColor =
            if (frame.darkGlass) LABEL_UNSELECTED_COLOR else TAB_UNSELECTED_LIGHT_COLOR

        // All four inks are constant for the whole draw; resolving them once removes two
        // Color.argb() calls per entry per pass (the row is painted twice: foreground + ink).
        val selectedIconColor =
            if (frame.darkGlass) withAlpha(TAB_SELECTED_ICON_DARK, LABEL_SELECTED_ALPHA)
            else TAB_SELECTED_ICON_LIGHT
        val selectedLabelColor =
            if (frame.darkGlass) withAlpha(LABEL_SELECTED_COLOR, LABEL_SELECTED_ALPHA)
            else withAlpha(LABEL_SELECTED_COLOR_LIGHT, 0.80f)
        val unselectedIconColor =
            if (frame.darkGlass) withAlpha(unselectedColor, LABEL_UNSELECTED_ALPHA)
            else TAB_UNSELECTED_LIGHT_COLOR
        val unselectedLabelColor =
            if (frame.darkGlass) unselectedIconColor else LABEL_UNSELECTED_COLOR_LIGHT

        for ((position, entry) in entries.withIndex()) {
            val centreX = sidePadding + (position + 0.5f) * slotWidth
            val selected = position == visualSelected
            // One animated scale per slot, mirroring the settings bar's per-tab graphicsLayer.
            // The outgoing tab eases down while the incoming one eases up; no slot boundary
            // snaps the content in a single frame.
            val contentScale = frame.pressSlotScales.getOrNull(position)
                ?: if (selected) frame.pressContentScale else 1f
            fun drawArt(scale: Float) {
                val save = if (scale != 1f) canvas.save() else -1
                if (save >= 0) canvas.scale(scale, scale, centreX, height / 2f)
                val accented = accentEveryEntry || (discreteAccent && selected)
                val iconColor = if (accented) selectedIconColor else unselectedIconColor
                val labelColor = if (accented) selectedLabelColor else unselectedLabelColor

                if (showIcon) {
                    // The glyphs share one keyline (body centred in the 24-unit box), so the box itself
                    // sizes them; only the width guard can still cap a wide one (朋友).
                    val maxScale = slotWidth * ICON_SLOT_FILL_RATIO / glyphBounds[entry].width()
                    val glyphScale = minOf(iconSize / TAB_ICON_VIEWPORT, maxScale)
                    val saved = canvas.save()
                    canvas.translate(centreX - iconSize / 2f, blockTop)
                    canvas.scale(glyphScale, glyphScale)
                    iconPaint.color = iconColor
                    canvas.drawPath(tabIcon(entry), iconPaint)
                    canvas.restoreToCount(saved)
                }

                if (showLabels) {
                    labelPaint.color = labelColor
                    drawEntryLabel(canvas, labels[entry], centreX, textBaseline, slotWidth, density)
                }
                if (entry == MESSAGE_LABEL_INDEX) {
                    frame.navigationBadge?.takeIf { it.visible }?.let { badge ->
                        // Anchored to whatever the entry actually draws, not to the label: with the
                        // label below the glyph the old anchor parked the badge on top of the bubble.
                        // 仅文字 anchors to the text's own box (and to the text's full height, not the
                        // two-line size), 仅图标 and 图标+文字 to the glyph box — which is also what
                        // keeps a wide glyph from pushing the badge into its neighbour's slot.
                        val anchorHalfWidth = if (showIcon) {
                            iconSize / 2f
                        } else {
                            // labelPaint is back at labelSize here: drawEntryLabel restores the size it
                            // borrowed before it returns.
                            labelPaint.measureText(labels[entry]) / 2f
                        }
                        val iconText = showIcon && showLabels
                        val iconOnly = showIcon && !showLabels
                        val badgeRight = centreX + anchorHalfWidth +
                            (when {
                                iconText -> 6f
                                iconOnly -> 4f
                                else -> 8f
                            }) * density
                        val badgeTop = when {
                            iconText -> blockTop - 3f * density
                            iconOnly -> blockTop - 2f * density
                            else -> blockTop - 13f * density
                        }
                        drawMessageBadge(
                            canvas,
                            frame,
                            badgeRight,
                            badgeTop,
                            badge,
                            when {
                                iconText -> 0.92f
                                iconOnly -> 0.88f
                                else -> 0.95f
                            },
                        )
                    }
                }
                if (save >= 0) canvas.restoreToCount(save)
            }

            val clip = frame.accentClip
            if (contentScale != 1f && clip != null) {
                // Only the part inside the selection capsule grows. Outside the capsule the
                // original pixels stay exactly where they were, so the unfilled area does not
                // scale with the filled one.
                canvas.save()
                canvas.clipOutPath(clip)
                drawArt(1f)
                canvas.restore()

                canvas.save()
                canvas.clipPath(clip)
                drawArt(contentScale)
                canvas.restore()
            } else {
                drawArt(contentScale)
            }
        }

        // The publish button belongs to the row itself, never to the selection ink.
        if (drawPublish && showPublish) {
            drawPublishButton(canvas, width - buttonSize / 2f, height / 2f, buttonSize, density, customPublishIcon(frame), glyphColor)
        }
    }

    /**
     * One entry's name, kept inside its own slot.
     *
     * The name is user-editable (底栏菜单文字), and a name wider than its slot would otherwise run
     * into its neighbour: measure it and shrink it for this draw only, then put the tuned size
     * back so the other entries and the next frame are unaffected. The settings page already caps
     * how long a name may be; this is the net under it.
     */
    private fun drawEntryLabel(
        canvas: Canvas,
        text: String,
        centreX: Float,
        baseline: Float,
        slotWidth: Float,
        density: Float,
    ) {
        if (text.isEmpty()) return
        val tuned = labelPaint.textSize
        val maxWidth = (slotWidth - LABEL_SLOT_MARGIN_DP * density).coerceAtLeast(1f)
        val measured = labelPaint.measureText(text)
        if (measured > maxWidth) labelPaint.textSize = tuned * (maxWidth / measured)
        canvas.drawText(text, centreX, baseline, labelPaint)
        if (labelPaint.textSize != tuned) labelPaint.textSize = tuned
    }

    /** The traced glyph for one navigation slot. */
    private fun tabIcon(slot: Int): Path = when (slot) {
        0 -> DouyinTabIcons.shouye
        1 -> DouyinTabIcons.pengyou
        2 -> DouyinTabIcons.xiaoxi
        else -> DouyinTabIcons.wo
    }

    /**
     * The user's picked publish icon, decoded once per encoded value. Empty means "draw the
     * built-in glyph", which is also the fallback when the stored image cannot be decoded.
     */
    private fun customPublishIcon(frame: AdapterNavigationFrame): Bitmap? {
        val encoded = frame.publishIcon
        if (encoded.isEmpty()) {
            publishIconBitmap = null
            publishIconSource = ""
            return null
        }
        if (encoded == publishIconSource) return publishIconBitmap
        publishIconSource = encoded
        publishIconBitmap = runCatching {
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            // Centre-cropped to a square here so the circle is filled without distortion,
            // whatever aspect the picked picture had.
            decoded?.let { source ->
                val side = minOf(source.width, source.height)
                if (side <= 0) {
                    null
                } else if (side == source.width && side == source.height) {
                    source
                } else {
                    val x = (source.width - side) / 2
                    val y = (source.height - side) / 2
                    android.graphics.Bitmap.createBitmap(source, x, y, side, side).also {
                        if (it !== source) source.recycle()
                    }
                }
            }
        }.getOrNull()
        return publishIconBitmap
    }

    /**
     * The unread-messages badge, redrawn from the reference bar: a `15dp` tall circle for one
     * digit that widens to a pill for two digits and for `99+`, filled `0xFFFF3B30` with a 1dp
     * white ring at 88% and a bold white 10sp label, anchored to the label's top-right corner
     * with the reference's `(+10dp, -13dp)` offset. A dot-only indicator (no count) is drawn as
     * a small circle instead. The whole badge is composited as one 90% layer so the fill, ring
     * and label fade together instead of stacking their own alphas.
     */
    private fun drawMessageBadge(
        canvas: Canvas,
        frame: AdapterNavigationFrame,
        anchorRight: Float,
        anchorTop: Float,
        badge: NavigationBadge,
        scale: Float = 1f,
    ) {
        val density = frame.density
        val height = BADGE_HEIGHT_DP * density * scale
        val count = badge.count
        val width = when {
            count <= 0 -> BADGE_DOT_DP * density * scale
            count > 99 -> BADGE_WIDE_DP * density * scale
            count > 9 -> BADGE_MEDIUM_DP * density * scale
            else -> BADGE_HEIGHT_DP * density * scale
        }
        val right = anchorRight
        val top = anchorTop
        val rect = RectF(right - width, top, right, top + height)
        val radius = height / 2f

        // One layer for the fill + ring + label: the group is 90% opaque, so the ring cannot
        // stack its own 88% on top of the fill and the count fades with the dot.
        val layerPad = 2f * density * scale
        val layer = canvas.saveLayerAlpha(
            rect.left - layerPad,
            rect.top - layerPad,
            rect.right + layerPad,
            rect.bottom + layerPad,
            BADGE_LAYER_ALPHA,
        )
        try {
            paint.style = Paint.Style.FILL
            paint.color = BADGE_COLOR
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density * scale
            paint.color = BADGE_RING_COLOR
            canvas.drawRoundRect(rect, radius, radius, paint)

            if (count > 0) {
                val text = if (count > 99) "99+" else count.toString()
                badgeTextPaint.textSize = BADGE_TEXT_SP * frame.scaledDensity * scale
                badgeTextPaint.color = BADGE_TEXT_COLOR
                val textBaseline = rect.centerY() -
                    (badgeTextPaint.fontMetrics.ascent + badgeTextPaint.fontMetrics.descent) / 2f
                canvas.drawText(text, rect.centerX(), textBaseline, badgeTextPaint)
            }
        } finally {
            canvas.restoreToCount(layer)
        }
    }

    /**
     * The publish action: a stroked rounded square holding a plus, redrawn from the
     * reference bar's code-built glyph.
     *
     * That glyph is authored in a 24x24 viewport, sized at 28dp, and placed on a 50dp
     * circular button — so it occupies only 28/50 of the button, not the whole of it.
     * Keeping that ratio is what stops the glyph from looking oversized inside the
     * circle when the bar is at its reference height.
     */
    private fun drawPublishButton(
        canvas: Canvas,
        centreX: Float,
        centreY: Float,
        size: Float,
        density: Float,
        icon: Bitmap?,
        glyphColor: Int,
    ) {
        // A user-picked image replaces the glyph: drawn centre-cropped inside the circle, the way
        // an avatar fills it, so any aspect ratio still looks intentional. Its opacity is pinned
        // at [PUBLISH_ICON_ALPHA] (95%) for both themes and every selected tab; before this the
        // shared [iconPaint] made it inherit the last tab glyph's alpha (0.60 / 0.90 on dark,
        // 1.0 on light, stale in 仅文字).
        if (icon != null && !icon.isRecycled && icon.width > 0 && icon.height > 0) {
            // Inset so the picture keeps a margin inside the button instead of hugging its edge.
            val radius = size / 2f * (1f - PUBLISH_ICON_INSET_RATIO)
            publishClipPath.reset()
            publishClipPath.addCircle(centreX, centreY, radius, Path.Direction.CW)
            publishIconDest.set(centreX - radius, centreY - radius, centreX + radius, centreY + radius)
            val saved = canvas.save()
            canvas.clipPath(publishClipPath)
            canvas.drawBitmap(icon, null, publishIconDest, publishIconPaint)
            canvas.restoreToCount(saved)
            return
        }

        val glyphSize = size * GLYPH_TO_BUTTON_RATIO
        val scale = glyphSize / GLYPH_VIEWPORT
        val originX = centreX - glyphSize / 2f
        val originY = centreY - glyphSize / 2f
        fun px(v: Float) = v * scale

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = px(GLYPH_STROKE)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = glyphColor

        // Rounded square: x in [1.5, 22.5], y in [3, 21], 6-unit corner radius.
        val box = RectF(
            originX + px(GLYPH_BOX_LEFT),
            originY + px(GLYPH_BOX_TOP),
            originX + px(GLYPH_BOX_RIGHT),
            originY + px(GLYPH_BOX_BOTTOM),
        )
        val radius = px(GLYPH_BOX_CORNER)
        labelPath.reset()
        labelPath.addRoundRect(box, radius, radius, Path.Direction.CW)
        canvas.drawPath(labelPath, paint)

        // Plus cross: horizontal y=12 x in [8.5, 15.5]; vertical x=12 y in [8.5, 15.5].
        canvas.drawLine(
            originX + px(GLYPH_CROSS_LEFT), originY + px(GLYPH_CROSS_Y),
            originX + px(GLYPH_CROSS_RIGHT), originY + px(GLYPH_CROSS_Y), paint,
        )
        canvas.drawLine(
            originX + px(GLYPH_CROSS_X), originY + px(GLYPH_CROSS_TOP),
            originX + px(GLYPH_CROSS_X), originY + px(GLYPH_CROSS_BOTTOM), paint,
        )
    }

    /**
     * The navigation row is drawn before the liquid indicator so the lens can
     * refract it; outside the optical mix the source is simply replayed in full.
     *
     * With a capsule on screen the row is drawn **outside** it only. The capsule's interior is
     * supplied by the lens scene (see [drawSelectionInk]), which is the whole point: the resting
     * ink and the ink revealed while dragging are then the same pixels from the same source, so
     * they cannot differ in colour. Drawing the row inside the capsule as well put crisp pixels on
     * top of the lens' own rendering, which is what made the resting entry look a different blue
     * from the fill that swept while dragging.
     */
    fun drawForeground(
        canvas: Canvas,
        frame: AdapterNavigationFrame,
        selectionPath: Path,
        drawSource: () -> Unit,
    ) {
        if (selectionPath.isEmpty) {
            drawSource()
            return
        }
        val saved = canvas.save()
        canvas.clipOutPath(selectionPath)
        drawSource()
        canvas.restoreToCount(saved)
    }

    private fun withAlpha(color: Int, scale: Float): Int =
        Color.argb(
            (Color.alpha(color) * scale.coerceIn(0f, 1f)).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    /** Retained for the module-asset lookup used by older snapshots of this adapter. */
    private companion object {
        val LABELS = listOf("首页", "朋友", "消息", "我")

        /**
         * The bar's four entries, by native slot index of Douyin's five-cell row: 2 is the
         * publish action ([DouyinAdapter.actionOnlyIndices]) and takes no navigation slot.
         *
         * The order is the bar's own: index 0 of this table is 首页, the last one 我. Every
         * entry lookup — the glyph, the label, the unread badge and the `隐藏` switches'
         * bitmask — goes through it, which is why the table is shared with the adapter spec
         * (`TargetSpec.hideOptions`).
         */
        val NATIVE_SLOTS = intArrayOf(0, 1, 3, 4)

        /** Gap between the capsule and the publish button, matching DouyinAdapter. */
        const val PUBLISH_GAP_DP = 12f

        /**
         * Publish glyph, in the reference bar's own 24x24 viewport units. That glyph is
         * authored at 24 units, stroked at 1.8 units, and rendered at 28dp on a 50dp
         * button, so these raw values are reproduced at whatever size the host draws.
         */
        const val GLYPH_VIEWPORT = 24f
        /** The reference's 28dp glyph on a 50dp button, i.e. 56% of the button diameter. */
        const val GLYPH_TO_BUTTON_RATIO = 28f / 50f

        /**
         * Inner margin kept around a user-picked publish icon, as a fraction of the button radius.
         * 0.14 leaves the picture comfortably inside the circle instead of filling it edge to edge.
         */
        const val PUBLISH_ICON_INSET_RATIO = 0.24f

        /**
         * Opacity of a user-picked publish icon: 95% on both glass themes and on every tab.
         *
         * The bitmap must not inherit the tab glyphs' alpha (0.60 unselected / 0.90 selected on
         * dark glass, 1.0 on light glass, stale in 仅文字); [publishIconPaint] pins this value.
         */
        const val PUBLISH_ICON_ALPHA = 0.95f
        const val GLYPH_STROKE = 1.8f
        const val GLYPH_BOX_LEFT = 1.5f
        const val GLYPH_BOX_TOP = 3f
        const val GLYPH_BOX_RIGHT = 22.5f
        const val GLYPH_BOX_BOTTOM = 21f
        const val GLYPH_BOX_CORNER = 6f
        const val GLYPH_CROSS_LEFT = 8.5f
        const val GLYPH_CROSS_RIGHT = 15.5f
        const val GLYPH_CROSS_TOP = 8.5f
        const val GLYPH_CROSS_BOTTOM = 15.5f
        const val GLYPH_CROSS_X = 12f
        const val GLYPH_CROSS_Y = 12f

        /**
         * Design B geometry: a 24-unit glyph above a label scaled to [LABEL_SIZE_SCALE] of the
         * host's textSize, with [ICON_TEXT_GAP_DP] between them. The glyph is capped by both the
         * slot width and the pill height so a wide or short bar cannot overflow it.
         */
        const val TAB_ICON_VIEWPORT = 24f

        /** How much of a slot a glyph may occupy before the width guard shrinks it. */
        const val ICON_SLOT_FILL_RATIO = 0.92f

        /**
         * The four glyphs share one keyline (bodies sized and centred in the artwork itself), so
         * there is no per-entry size factor any more: the box alone sizes them, and the only size
         * knobs are [ICON_SLOT_RATIO] / [ICON_BAR_RATIO] plus the user's `iconScale` setting.
         *
         * 朋友 is inherently wider than the others (two people side by side); the width guard above
         * is what keeps it inside its slot.
         */
        const val ICON_SLOT_RATIO = 0.34f
        const val ICON_BAR_RATIO = 0.36f

        /** 仅图标 has no label to balance the slot, so its glyph reads larger. */
        const val ICON_ONLY_SCALE = 1.28f
        /**
         * Gap between a glyph and its label. Kept tight on purpose: the block's vertical budget is
         * the pill minus the edge-refraction clearance, and every dp given to the gap is a dp the
         * glyph loses, so this is what lets the icons keep their full [ICON_BAR_RATIO] size.
         */
        const val ICON_TEXT_GAP_DP = 3f
        const val LABEL_SIZE_SCALE = 0.86f

        /**
         * Gap a label keeps from its slot's edge before [drawEntryLabel] starts shrinking it.
         *
         * The reference names are two characters and never reach this; a user-typed name does, and
         * without the gap an over-long one touches its neighbour's text.
         */
        const val LABEL_SLOT_MARGIN_DP = 6f

        /** The pill is inset from the host by this much (see GlassHostLayout.drawSelection). */
        const val CAPSULE_VERTICAL_INSET_DP = 8f

        /**
         * Vertical clearance the entry block keeps from the pill's edge.
         *
         * The lens refracts content within `refractionHeightDp` of that edge (15 dp by default, and
         * the user can raise it), so anything closer is bent and spectrally split out of place. The
         * block shrinks just enough to stay outside this margin.
         */
        const val BLOCK_EDGE_CLEARANCE_DP = 7f

        /** Selected navigation colour on dark glass: the blue the design asks for. */
        const val TAB_SELECTED_COLOR = 0xFF3B82F6.toInt()

        /** Selected glyph fill in dark mode. */
        const val TAB_SELECTED_ICON_DARK = 0xFFF71841.toInt()

        /**
         * Selected glyph fill on LIGHT glass: the design's mint (#71E2D1).
         *
         * Only the glyph takes it. The label keeps the TOP BAR's selected colour
         * ([LABEL_SELECTED_COLOR_LIGHT]) at the top bar's own alpha, and the unselected entries keep
         * [TAB_UNSELECTED_LIGHT_COLOR] at the top bar's unselected alpha, so the bottom bar reads as
         * the same type as the top one instead of inventing a second selection language.
         */
        const val TAB_SELECTED_ICON_LIGHT = 0xFF5796C3.toInt()

        /**
         * Unselected colour on light glass: the reference's black, dimmed to 60% exactly like the
         * dark glass's white.
         *
         * An earlier revision used the accent here, on the theory that white would be invisible on a
         * light bar; on the device that made every entry read as selected — four identical blue
         * icons with nothing left to tell the current tab apart. The accent belongs to the capsule
         * pass alone.
         */
        val TAB_UNSELECTED_LIGHT_COLOR = 0xFF3C4148.toInt()

        /** Reference label colours: pure black/white, held at 90% when selected, 60% when not. */
        const val LABEL_SELECTED_COLOR = 0xFFFFFFFF.toInt()
        const val LABEL_UNSELECTED_COLOR = 0xFFFFFFFF.toInt()
        const val LABEL_SELECTED_COLOR_LIGHT = 0xFF101114.toInt()
        const val LABEL_UNSELECTED_COLOR_LIGHT = 0xFF3C4148.toInt()
        /** Selected entries keep 90%: 0xFF × 0.90 = 229. */
        const val LABEL_SELECTED_ALPHA = 0.90f
        /** Unselected entries drop to 60%: 0xFF × 0.60 = 153. */
        const val LABEL_UNSELECTED_ALPHA = 0.60f



        /** Messages entry inside [LABELS]. */
        const val MESSAGE_LABEL_INDEX = 2

        /** Unread badge geometry and colours, taken from the reference bar. */
        const val BADGE_HEIGHT_DP = 15f
        const val BADGE_MEDIUM_DP = 20f
        const val BADGE_WIDE_DP = 27f
        const val BADGE_DOT_DP = 9f
        const val BADGE_OFFSET_X_DP = 10f
        const val BADGE_OFFSET_Y_DP = -13f
        const val BADGE_TEXT_SP = 10f
        /** Whole-badge layer alpha: 230/255 ≈ 90%. */
        const val BADGE_LAYER_ALPHA = 230
        const val BADGE_COLOR = 0xFFFF3B30.toInt()
        const val BADGE_RING_COLOR = 0xE0FFFFFF.toInt()
        const val BADGE_TEXT_COLOR = 0xFFFFFFFF.toInt()

        @Suppress("unused")
        const val MODULE_PACKAGE = "io.github.xzdw.dylt"
    }
}
