package io.github.xzdw.dylt.hook.adapters.douyin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.PixelCopy
import io.github.xzdw.dylt.hook.adapters.AppVideoBackdrop
import io.github.xzdw.dylt.hook.GlassHostLayout
import io.github.xzdw.dylt.rendering.NativeBackdropBlur
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Owns Douyin compositor/video capture and all of its mutable probe state. */
internal class DouyinVideoSampler(
    private val context: Context,
) {
    private var douyinVideoSurfaceView: SurfaceView? = null
    private var douyinVideoTextureView: TextureView? = null
    private var douyinVideoCopyInFlight = false
    /**
     * Retry delay after a failed capture; 0 while captures succeed. A broken capture path used to
     * re-arm every 16 ms forever, paying a failed framework call, a probe reset and a diagnostics
     * string 60 times a second for no frames at all.
     */
    private var captureRetryDelayMs = 0L
    @Volatile private var douyinAsyncCaptureListener: Any? = null
    private var douyinVideoCopyStartedAt = 0L
    private var douyinVideoScratch: Bitmap? = null
    private var lastDouyinVideoCopy = 0L
    private var douyinVideoSurfaceLeft = 0
    private var douyinVideoSurfaceTop = 0
    private var douyinVideoSurfaceWidth = 0
    private var douyinVideoSurfaceHeight = 0
    private var douyinCopySuccessEpoch = 0
    private var douyinCopyFailEpoch = 0
    private var douyinVideoCandidates = ArrayList<SurfaceView>(8)
    private var douyinVideoProbeIndex = 0
    private var douyinVideoProbeFails = 0
    private var lastDouyinVideoScanAt = 0L
    private var douyinVideoChurn = 0
    private var douyinVideoHolderWatched: SurfaceView? = null
    private var backdrop: Bitmap? = null
    private var compositorPixelCopyThread: HandlerThread? = null
    private var compositorPixelCopyHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private var currentHost: View? = null
    private var pageAllowed = false
    private var blocked = false
    private val captureWakeup = Runnable { requestCurrent() }
    private var captureGeneration = 0L
    private var nativeTextureActive = false

    // Low-rate watch of the video geometry behind the glass bar. The player reports the real
    // video size through SurfaceTexture.setDefaultBufferSize, so a letterboxed video can be
    // recognised without reading pixels: the bar is static when it lies entirely in the
    // black top/bottom band of the fit-centred video. A full-screen video fills the height
    // and is never marked static here; a paused video/photo is covered by the existing frame
    // stamp detector in the navigation state.
    private var stripWatchScheduled = false
    private var stripTexture: TextureView? = null
    private var stripVideoW = 0
    private var stripVideoH = 0
    private var stripStatic = false
    private var stripEdgeStableStreak = 0
    private var stripEdgeCandidate = false
    private var stripEdgeCheckedAt = 0L
    private var stripEdgeCheckInFlight = false
    private var stripEdgeBitmap: Bitmap? = null
    private val stripEdgePixels = IntArray(32 * 8)
    private val stripEdgePrevious = IntArray(32 * 8)
    private var stripEdgeHasPrevious = false

    private val stripWatch = object : Runnable {
        override fun run() {
            stripWatchScheduled = false
            sampleBarStrip()
            scheduleStripWatch(nextStripDelayMs())
        }
    }

    fun isBarStripStatic(): Boolean = stripStatic

    private fun resetEdgeConfirmation() {
        stripEdgeStableStreak = 0
        stripEdgeCandidate = false
        stripEdgeHasPrevious = false
    }

    private fun scheduleStripWatch(delayMs: Long = BAR_STRIP_INTERVAL_MS) {
        if (stripWatchScheduled) return
        if (currentHost == null) return
        stripWatchScheduled = true
        mainHandler.postDelayed(stripWatch, delayMs)
    }

    /**
     * Over content there is no candidate to confirm, so the geometry check can run at a
     * slower cadence. While a candidate/static state exists keep the responsive 600 ms
     * cadence; background and detached windows back off to a maintenance tick instead of
     * stopping entirely (stopping left a static bar without a re-check path when the app
     * resumed).
     */
    private fun nextStripDelayMs(): Long {
        val host = currentHost ?: return BAR_STRIP_IDLE_INTERVAL_MS
        if (!host.isAttachedToWindow || !host.isShown ||
            host.windowVisibility != View.VISIBLE
        ) {
            return BAR_STRIP_IDLE_INTERVAL_MS
        }
        return if (stripStatic || stripEdgeCandidate) {
            BAR_STRIP_INTERVAL_MS
        } else {
            BAR_STRIP_SLOW_INTERVAL_MS
        }
    }

    /**
     * Samples a 64x64 bitmap of the video view and compares the 4x4 cell average behind the
     * host with the previous sample. A signature is only accepted as static after two
     * consecutive unchanged samples, so a dark scene or a paused frame gets the same
     * treatment as a letterbox (both really are static), while any moving video keeps the
     * bar repainting.
     */
    /**
     * Compares the video's fit-centred content rect with the bar's screen rect at a low rate.
     * A narrower-than-view video leaves static black bands above and below; when the bar lies
     * entirely in one of those bands, its backdrop is static even though the player keeps
     * producing frames. A black strip stops immediately; a non-black fill must stay stable
     * across two comparisons, so a transient size during a swipe cannot freeze the bar.
     */
    private fun sampleBarStrip() {
        val host = currentHost
        if (host == null || !host.isAttachedToWindow || !host.isShown ||
            host.windowVisibility != View.VISIBLE || !pageAllowed || blocked
        ) {
            setStripStatic(false)
            return
        }
        val texture = DouyinPlayerTextureHooks.activeTexture(host)
        if (texture == null) {
            setStripStatic(false)
            return
        }
        if (texture !== stripTexture) {
            stripTexture = texture
            resetEdgeConfirmation()
            setStripStatic(false)
        }
        val video = DouyinPlayerTextureHooks.videoSize(texture)
        if (video == null) {
            setStripStatic(false)
            return
        }
        val videoW = video.first
        val videoH = video.second
        if (videoW <= 0 || videoH <= 0) {
            setStripStatic(false)
            return
        }
        if (videoW != stripVideoW || videoH != stripVideoH) {
            stripVideoW = videoW
            stripVideoH = videoH
            resetEdgeConfirmation()
            setStripStatic(false)
        }
        val hostRect = Rect()
        if (!host.getGlobalVisibleRect(hostRect)) {
            setStripStatic(false)
            return
        }
        val texRect = Rect()
        if (!texture.getGlobalVisibleRect(texRect) || texRect.width() <= 0 || texRect.height() <= 0) {
            setStripStatic(false)
            return
        }
        if (!Rect.intersects(hostRect, texRect)) {
            considerStatic(host)
            return
        }
        val texW = texRect.width().toFloat()
        val texH = texRect.height().toFloat()
        // Fit-centre: scale until both axes fit, which leaves black bands on the short axis.
        val scale = minOf(texW / videoW, texH / videoH)
        val contentH = videoH * scale
        if (contentH >= texH - 1f) {
            stripEdgeCandidate = false
            setStripStatic(false)
            return
        }
        val contentTop = texRect.top + (texH - contentH) / 2f
        val contentBottom = contentTop + contentH
        val overlaps = hostRect.top < contentBottom && hostRect.bottom > contentTop
        if (overlaps) {
            stripEdgeCandidate = false
            setStripStatic(false)
        } else {
            considerStatic(host)
        }
    }

    /**
     * Geometry is necessary but not sufficient: Douyin sometimes fills a letterbox with a
     * blurred copy of the video, and a center-cropped video can leave no bands at all. The
     * strip beside the bar must therefore be black (fast path) or temporally stable (static
     * colored/blurred fill) before the bar is frozen.
     */
    private fun considerStatic(host: View) {
        val now = SystemClock.uptimeMillis()
        if (!stripEdgeCandidate) {
            requestEdgePixelCheck(host)
            setStripStatic(false)
            return
        }
        // Keep the bar frozen while a stale confirmation is refreshed. Dropping to false on
        // every TTL expiry made the pump restart for a second every few seconds.
        if (now - stripEdgeCheckedAt > BAR_STRIP_EDGE_TTL_MS) {
            requestEdgePixelCheck(host)
        }
        setStripStatic(true)
    }

    private fun requestEdgePixelCheck(host: View) {
        if (stripEdgeCheckInFlight) return
        val now = SystemClock.uptimeMillis()
        if (now - stripEdgeCheckedAt < BAR_STRIP_EDGE_RETRY_MS) return
        val activity = hostActivity(host.context) ?: return
        val root = host.rootView as? View ?: return
        val rootW = root.width
        val rootH = root.height
        if (rootW <= 0 || rootH <= 0) return
        val loc = IntArray(2)
        host.getLocationInWindow(loc)
        val margin = (4f * density).toInt().coerceAtLeast(1)
        val isTopCopy = loc[1] + host.height / 2 < rootH / 2
        val rect = Rect()
        val edgeHeight = (32f * density).toInt().coerceAtLeast(8)
        val left = (loc[0] + margin).coerceIn(0, rootW - 1)
        // Full bar width: a colored/animated fill anywhere along the bar must fail the
        // black-or-stable check, so a left-corner-only sample is not enough.
        val right = (loc[0] + host.width - margin).coerceIn(left + 1, rootW)
        // Sample the strip immediately beside the bar on its content side (below a top bar,
        // above a bottom bar). This is outside the glass, so it shows the raw video/black
        // letterbox, and it avoids the gesture area / screen edge.
        if (isTopCopy) {
            val top = (loc[1] + host.height + margin).coerceIn(0, rootH - 1)
            rect.set(left, top, right, (top + edgeHeight).coerceIn(top + 1, rootH))
        } else {
            val bottom = (loc[1] - margin).coerceIn(1, rootH)
            rect.set(left, (bottom - edgeHeight).coerceIn(0, bottom - 1), right, bottom)
        }
        if (rect.width() <= 1 || rect.height() <= 2) return
        val bmp = stripEdgeBitmap ?: runCatching {
            Bitmap.createBitmap(32, 8, Bitmap.Config.ARGB_8888)
        }.getOrNull()?.also { stripEdgeBitmap = it } ?: return
        if (bmp.isRecycled) {
            stripEdgeBitmap = null
            return
        }
        stripEdgeCheckInFlight = true
        val listener = object : PixelCopy.OnPixelCopyFinishedListener {
            override fun onPixelCopyFinished(copyResult: Int) {
                onEdgeCopied(copyResult, bmp)
            }
        }
        val request = runCatching {
            PixelCopy.request(activity.window, rect, bmp, listener, mainHandler)
        }
        if (request.isFailure) {
            stripEdgeCheckInFlight = false
            stripEdgeCheckedAt = now
            stripEdgeStableStreak = 0
            stripEdgeCandidate = false
        }
    }

    private fun onEdgeCopied(copyResult: Int, bmp: Bitmap) {
        stripEdgeCheckInFlight = false
        stripEdgeCheckedAt = SystemClock.uptimeMillis()
        if (copyResult != PixelCopy.SUCCESS || bmp.isRecycled) {
            stripEdgeStableStreak = 0
            stripEdgeCandidate = false
            return
        }
        bmp.getPixels(stripEdgePixels, 0, 32, 0, 0, 32, 8)
        var maxLuma = 0
        var sum = 0L
        var sumSq = 0L
        for (pixel in stripEdgePixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = (r * 77 + g * 150 + b * 29) shr 8
            if (luma > maxLuma) maxLuma = luma
            sum += luma
            sumSq += luma.toLong() * luma
        }
        val n = stripEdgePixels.size
        val mean = sum.toDouble() / n
        val variance = sumSq.toDouble() / n - mean * mean
        val black = maxLuma <= 24 && mean <= 12.0 && variance <= 8.0

        val hasPrevious = stripEdgeHasPrevious
        var diffSum = 0L
        if (hasPrevious) {
            for (i in stripEdgePixels.indices) {
                val p = stripEdgePixels[i]
                val q = stripEdgePrevious[i]
                diffSum += abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF))
                diffSum += abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF))
                diffSum += abs((p and 0xFF) - (q and 0xFF))
            }
        }
        val meanDiff = if (hasPrevious) diffSum.toFloat() / (n * 3) else Float.MAX_VALUE
        val stable = hasPrevious && meanDiff <= BAR_STRIP_EDGE_STABLE_DIFF

        if (black || stable) stripEdgeStableStreak++ else stripEdgeStableStreak = 0
        // Black stops immediately; a colored fill must prove stable over two comparisons.
        stripEdgeCandidate = black || (stable && stripEdgeStableStreak >= 2)
        System.arraycopy(stripEdgePixels, 0, stripEdgePrevious, 0, n)
        stripEdgeHasPrevious = true
    }

    private fun hostActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun setStripStatic(value: Boolean) {
        if (stripStatic == value) return
        stripStatic = value
        currentHost?.postInvalidateOnAnimation()
    }
    private data class CaptureApi(
        val capture: Class<*>, val builder: Class<*>,
        val baseBuilder: Class<*>, val listener: Class<*>,
    )
    // Accessed only on the capture worker.
    private var captureApi: CaptureApi? = null

    fun request(host: View, allowed: Boolean, samplingBlocked: Boolean) {
        // Two of the three samplers in this package are constructed with a no-op logger, so
        // draining into them only paid for the lock and the list copy.
        currentHost = host
        pageAllowed = allowed
        blocked = samplingBlocked
        scheduleStripWatch()
        requestCurrent()
    }

    fun snapshot(): AppVideoBackdrop? = backdrop?.takeUnless(Bitmap::isRecycled)?.let {
        AppVideoBackdrop(it, douyinVideoSurfaceLeft, douyinVideoSurfaceTop,
            douyinVideoSurfaceWidth, douyinVideoSurfaceHeight, douyinCopySuccessEpoch)
    }

    /**
     * Reused DFS stack for the surface scan, with the depth kept in a parallel array.
     *
     * The scan is throttled to 200 ms but walks up to [DOUYIN_VIDEO_FIND_MAX_VIEWS] views per
     * pass, so the previous `ArrayDeque<Pair<View, Int>>` allocated one `Pair` per pushed
     * child — hundreds of short-lived objects per pass on the UI thread. The page controller
     * already uses this parallel-array shape for the same reason.
     */
    private val scanViews = ArrayList<View>(256)
    private var scanDepths = IntArray(256)

    private fun pushScan(view: View, depth: Int) {
        if (scanDepths.size <= scanViews.size) {
            scanDepths = scanDepths.copyOf(scanDepths.size * 2)
        }
        scanViews.add(view)
        scanDepths[scanViews.size - 1] = depth
    }

    private fun findDouyinVideoSurfaceView(root: ViewGroup): SurfaceView? {
        val host = currentHost ?: return null
        val hostLeft = IntArray(2).also(host::getLocationInWindow)
        val hostRight = hostLeft[0] + host.width
        val hostBottom = hostLeft[1] + host.height
        val hostArea = host.width.toLong() * host.height.toLong()
        val now = SystemClock.uptimeMillis()

        // Between scans, reuse the ranked candidate list: pick the current
        // probe slot when its surface is still usable, otherwise walk forward.
        if (now - lastDouyinVideoScanAt < DOUYIN_VIDEO_FIND_INTERVAL_MS) {
            selectDouyinVideoProbe()?.let { return it }
            // A swipe can detach the old SurfaceView before the one for the next item is
            // attached, so force a rescan when a previously known candidate is gone. With no
            // candidates at all there is nothing to re-scan for: the feed's TextureView
            // fallback owns the frame. Resetting the clock there made every display frame
            // walk the whole tree (measured ~100 full scans/s, ~500k view visits/5s).
            if (douyinVideoCandidates.isEmpty()) return null
            lastDouyinVideoScanAt = 0L
        }

        lastDouyinVideoScanAt = now
        val minUsableArea = hostArea / 6
        scanViews.clear()
        pushScan(root, 0)
        var visited = 0
        val found = ArrayList<SurfaceView>(8)
        var bestAreaSurface: SurfaceView? = null
        var bestArea = 0L
        var bestTexture: TextureView? = null
        var bestTextureArea = 0L
        var surfaceCount = 0
        var invalidSurfaceCount = 0
        var smallSurfaceCount = 0
        var textureCount = 0
        while (scanViews.isNotEmpty() && visited++ < DOUYIN_VIDEO_FIND_MAX_VIEWS) {
            val last = scanViews.size - 1
            val view = scanViews.removeAt(last)
            val depth = scanDepths[last]
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) continue
            if (view is SurfaceView) {
                surfaceCount++
                if (!view.holder.surface.isValid) {
                    invalidSurfaceCount++
                } else {
                    val area = view.width.toLong() * view.height.toLong()
                    if (area < minUsableArea) {
                        smallSurfaceCount++
                    } else {
                        found.add(view)
                        if (area > bestArea) {
                            bestArea = area
                            bestAreaSurface = view
                        }
                    }
                }
            } else if (view is TextureView && view.surfaceTexture != null) {
                textureCount++
                val area = view.width.toLong() * view.height.toLong()
                if (area >= minUsableArea && area > bestTextureArea) {
                    bestTexture = view
                    bestTextureArea = area
                }
            }
            if (view is ViewGroup && depth < DOUYIN_VIDEO_FIND_MAX_DEPTH) {
                for (i in 0 until view.childCount) pushScan(view.getChildAt(i), depth + 1)
            }
        }

        // Rank by overlap with this host's window rect first (the glass must
        // blur pixels directly behind the bar), then by total area. A surface
        // that never covers the bar is useless even when it is the biggest in
        // the tree (e.g. a full-screen pool or preview surface elsewhere).
        if (found.isNotEmpty()) {
            val ranked = found.sortedWith(
                compareByDescending<SurfaceView> { surface ->
                    val loc = IntArray(2).also(surface::getLocationInWindow)
                    overlapsDouyinHost(
                        loc[0], loc[1], loc[0] + surface.width, loc[1] + surface.height,
                        hostLeft[0], hostLeft[1], hostRight, hostBottom,
                    )
                }.thenByDescending { it.width.toLong() * it.height.toLong() },
            )
            douyinVideoCandidates.clear()
            douyinVideoCandidates.addAll(ranked.take(DOUYIN_VIDEO_PROBE_MAX))
            if (douyinVideoProbeIndex >= douyinVideoCandidates.size) douyinVideoProbeIndex = 0
        } else if (bestAreaSurface != null) {
            // No surface overlapped the bar; keep the largest as a fallback so
            // the copy still runs (geometry mapping handles a mid-screen view).
            douyinVideoCandidates.clear()
            douyinVideoCandidates.add(bestAreaSurface)
            douyinVideoProbeIndex = 0
        } else {
            douyinVideoCandidates.clear()
            douyinVideoProbeIndex = 0
        }

        douyinVideoSurfaceView = selectDouyinVideoProbe()
        douyinVideoTextureView = if (douyinVideoSurfaceView == null) bestTexture else null
        return douyinVideoSurfaceView
    }

    /** Pick the current probe slot if usable, else walk to the next valid one. */
    private fun selectDouyinVideoProbe(): SurfaceView? {
        val list = douyinVideoCandidates
        if (list.isEmpty()) return null
        var i = douyinVideoProbeIndex
        if (i >= list.size) i = 0
        val n = list.size
        repeat(n) {
            val c = list[i]
            if (c.isAttachedToWindow && c.isShown && c.width > 0 &&
                c.height > 0 && c.holder.surface.isValid
            ) {
                if (i != douyinVideoProbeIndex) {
                    douyinVideoProbeIndex = i
                    douyinVideoProbeFails = 0
                }
                watchDouyinVideoSurface(c)
                return c
            }
            i = (i + 1) % n
        }
        return null
    }

    private val douyinVideoHolderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            resetDouyinVideoProbe("holder created")
            currentHost?.post { requestCurrent() }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            resetDouyinVideoProbe("holder changed")
            currentHost?.post { requestCurrent() }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            captureGeneration++
            douyinVideoChurn++
            // The old Surface can remain reported as attached for a short
            // interval during a feed-page transition. Invalidate it at the
            // lifecycle callback instead of waiting for PixelCopy to fail.
            douyinVideoCopyInFlight = false
            douyinVideoCopyStartedAt = 0L
            resetDouyinVideoProbe("holder destroyed")
            currentHost?.post { requestCurrent() }
        }
    }

    private fun watchDouyinVideoSurface(c: SurfaceView) {
        val prev = douyinVideoHolderWatched
        if (prev !== c) {
            prev?.holder?.removeCallback(douyinVideoHolderCallback)
            c.holder.addCallback(douyinVideoHolderCallback)
            douyinVideoHolderWatched = c
        }
    }

    /**
     * Move the PixelCopy probe to the next ranked candidate after the current
     * surface rejects readback, and zero the cadence clock so the retry runs
     * on the next tick instead of waiting out the interval.
     */
    private fun advanceDouyinVideoProbe(reason: String) {
        val n = douyinVideoCandidates.size
        douyinVideoProbeFails = 0
        if (n <= 1) return
        douyinVideoProbeIndex = (douyinVideoProbeIndex + 1) % n
        lastDouyinVideoCopy = 0L
    }

    private fun resetDouyinVideoProbe(reason: String) {
        lastDouyinVideoScanAt = 0L
        lastDouyinVideoCopy = 0L
        douyinVideoProbeIndex = 0
        douyinVideoProbeFails = 0
        douyinVideoCandidates.clear()
    }

    private fun overlapsDouyinHost(
        aLeft: Int, aTop: Int, aRight: Int, aBottom: Int,
        bLeft: Int, bTop: Int, bRight: Int, bBottom: Int,
    ): Long {
        val overlapW = minOf(aRight, bRight) - maxOf(aLeft, bLeft)
        val overlapH = minOf(aBottom, bBottom) - maxOf(aTop, bTop)
        if (overlapW <= 0 || overlapH <= 0) return 0L
        return overlapW.toLong() * overlapH.toLong()
    }

    /** Capture live video at display-pixel resolution around the glass only. */
    private fun requestCurrent() {

        val host = currentHost ?: return
        // windowVisibility, not just isShown: a backgrounded or screen-off activity keeps every
        // VISIBLE flag and still reports isShown, and the capture completion below re-arms this
        // loop on its own — so without this test the readback kept running at display rate while
        // Douyin played audio in the background. Leaving through this gate without re-arming is
        // what stops it; the per-frame capture loop calls request() again once the window is drawn.
        if (!pageAllowed || !host.isAttachedToWindow || !host.isShown || blocked ||
            host.width <= 0 || host.height <= 0 ||
            host.windowVisibility != View.VISIBLE
        ) return

        val now = SystemClock.uptimeMillis()

        // Registered native player textures are part of the ordinary live GPU
        // scene. Capturing them would reintroduce the readback latency.
        if (DouyinPlayerTextureHooks.activeTexture(host) != null) {
            if (!nativeTextureActive) {
                nativeTextureActive = true
                captureGeneration++
                douyinVideoCopyInFlight = false
                backdrop = null
            }
            mainHandler.removeCallbacks(captureWakeup)
            return
        }
        if (nativeTextureActive) {
            nativeTextureActive = false
            resetDouyinVideoProbe("native texture unavailable; capture fallback")
        }

        // During a fast feed swipe Douyin detaches the old SurfaceView before
        // attaching the next one. Do not let the old async capture occupy the
        // only slot until its callback/timeout; its late frame is discarded by
        // the existing attachment checks.
        val activeProbe = douyinVideoCandidates.getOrNull(douyinVideoProbeIndex)
        if (douyinVideoCopyInFlight && (activeProbe == null ||
                !activeProbe.isAttachedToWindow || !activeProbe.holder.surface.isValid)) {
            captureGeneration++
            douyinVideoCopyInFlight = false
            douyinVideoCopyStartedAt = 0L
            resetDouyinVideoProbe("probe detached")
        }

        // Stale-callback guard: if a PixelCopy listener never fires, the
        // in-flight flag would otherwise starve the sampling cadence forever.
        // Detect it here on the next tick and reset before re-arming.
        if (douyinVideoCopyInFlight && douyinVideoCopyStartedAt != 0L &&
            now - douyinVideoCopyStartedAt > DOUYIN_VIDEO_COPY_TIMEOUT_MS
        ) {
            captureGeneration++
            douyinVideoCopyInFlight = false
            douyinVideoCopyStartedAt = 0L
        }

        if (douyinVideoCopyInFlight) return

        val remaining = DOUYIN_VIDEO_COPY_INTERVAL_MS - (now - lastDouyinVideoCopy)
        if (remaining > 0L) {
            mainHandler.removeCallbacks(captureWakeup)
            mainHandler.postDelayed(captureWakeup, remaining)
            return
        }
        mainHandler.removeCallbacks(captureWakeup)

        lastDouyinVideoCopy = now

        val root = currentHost?.rootView as? ViewGroup ?: return

        val surfaceView = findDouyinVideoSurfaceView(root)

        if (surfaceView == null) {

            // Let the TextureView fallback own the cadence timestamp. The
            // caller has just updated it, which otherwise rejects every copy.
            lastDouyinVideoCopy = 0L

            sampleDouyinVideoTextureViewFrame()

            return

        }

        if (!surfaceView.holder.surface.isValid) {
            advanceDouyinVideoProbe("surface invalid")
            return
        }

        val surfaceLocation = IntArray(2).also(surfaceView::getLocationInWindow)


        // The feed player's buffers are a different size from the view rect (e.g. a 720x1280
        // buffer shown in a 1368x2432 view), so the glass strip is mapped into surface-frame
        // coordinates and the draw pass maps the result back through the view-space rect
        // published below. Only the strip behind the glass is ever read back.
        val frameRect = surfaceView.holder.surfaceFrame

        val bufferWidth = if (frameRect.width() > 0) frameRect.width() else surfaceView.width

        val bufferHeight = if (frameRect.height() > 0) frameRect.height() else surfaceView.height

        if (bufferWidth <= 0 || bufferHeight <= 0) return

        // PixelCopy cannot read this player's buffer on the current ROM
        // (ERROR_SOURCE_NO_DATA). Capture the actual SurfaceControl layer
        // instead; this includes the decoder's live compositor buffer without
        // creating another window or touching the control-lift layout.
        if (requestDouyinSurfaceControlFrame(surfaceView, now)) return

        val viewStrip = douyinVideoStrip(surfaceView, host) ?: run {
            releaseVideoBackdrop(host)
            return
        }
        val bufferScaleX = bufferWidth.toFloat() / surfaceView.width
        val bufferScaleY = bufferHeight.toFloat() / surfaceView.height
        val src = Rect(
            (viewStrip.left * bufferScaleX).roundToInt().coerceIn(0, bufferWidth - 1),
            (viewStrip.top * bufferScaleY).roundToInt().coerceIn(0, bufferHeight - 1),
            (viewStrip.right * bufferScaleX).roundToInt().coerceIn(1, bufferWidth),
            (viewStrip.bottom * bufferScaleY).roundToInt().coerceIn(1, bufferHeight),
        )
        if (src.width() <= 0 || src.height() <= 0) {
            releaseVideoBackdrop(host)
            return
        }
        val targetWidth = max(1, (src.width() / CAPTURE_DOWNSCALE).roundToInt())
        val targetHeight = max(1, (src.height() / CAPTURE_DOWNSCALE).roundToInt())
        val target = douyinVideoScratch
            ?.takeIf { !it.isRecycled && it.width == targetWidth && it.height == targetHeight }
            ?: Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        douyinVideoScratch = null

        douyinVideoCopyInFlight = true

        douyinVideoCopyStartedAt = now

        runCatching {

            PixelCopy.request(

                surfaceView,

                src,

                target,

                { result ->

                    douyinVideoCopyInFlight = false

                    douyinVideoCopyStartedAt = 0L

                    if (result == PixelCopy.SUCCESS && currentHost?.isAttachedToWindow == true &&

                        pageAllowed

                    ) {

                        val successEpoch = douyinCopySuccessEpoch++

                        val previous = backdrop

                        backdrop = target
                        captureRetryDelayMs = 0L
                        // Publish the strip, not the whole view: the renderer stretches the
                        // bitmap onto exactly this rect, which is what lets a cropped,
                        // half-resolution readback land on the right pixels.
                        douyinVideoSurfaceLeft = surfaceLocation[0] + viewStrip.left
                        douyinVideoSurfaceTop = surfaceLocation[1] + viewStrip.top
                        douyinVideoSurfaceWidth = viewStrip.width()
                        douyinVideoSurfaceHeight = viewStrip.height()

                        if (previous !== target) {

                            douyinVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()

                            douyinVideoScratch = previous

                        }

                        // The display-rate pump in onDraw repaints the glass with

                        // the freshest backdrop; postInvalidateOnAnimation keeps the

                        // transition immediate even while that pump is throttled.

                        currentHost?.postInvalidateOnAnimation()

                    } else {
                        if (result == PixelCopy.ERROR_SOURCE_INVALID ||
                            result == PixelCopy.ERROR_SOURCE_NO_DATA
                        ) {
                            douyinVideoProbeFails++
                            if (douyinVideoProbeFails >= 2 && douyinVideoCandidates.size > 1) {
                                advanceDouyinVideoProbe("copy code=$result")
                            }
                        }
                        val failEpoch = douyinCopyFailEpoch++

                        resetDouyinVideoProbe("copy code=$result")

                        if (target !== backdrop) {

                            douyinVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()

                            douyinVideoScratch = target

                        }

                    }

                },
                mainHandler,
            )

        }.onFailure { t ->


            douyinVideoCopyInFlight = false

            douyinVideoCopyStartedAt = 0L

            if (target !== backdrop) {

                douyinVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()

                douyinVideoScratch = target

            }

        }

    }

    /**
     * The part of [surfaceView] the glass actually samples: the host rect grown by exactly
     * the blur padding the renderer reserves, expressed in the surface view's own
     * coordinates.
     *
     * Both capture paths crop to this rect, so a full-screen decoder buffer is never read
     * back and the two paths cannot publish geometry that disagrees. Every host resolves its
     * own strip, so the bottom bar, the top copy and the top-tab panel each sample only what
     * sits behind them.
     */
    private fun douyinVideoStrip(surfaceView: SurfaceView, host: View): Rect? {
        if (surfaceView.width <= 0 || surfaceView.height <= 0) return null
        val location = IntArray(2).also(surfaceView::getLocationInWindow)
        val hostLocation = IntArray(2).also(host::getLocationInWindow)
        // Use exactly the static renderer's blur margin. Reading the host's existing
        // config needs no app-specific changes to the shared renderer.
        val radius = ((host as? GlassHostLayout)?.config?.blurRadius ?: 16f) *
            density * DouyinAdapter.secondaryBlurScale
        val padding = kotlin.math.ceil(NativeBackdropBlur.padding(radius, 24f * density)).toInt() + 2
        val strip = Rect(
            maxOf(0, hostLocation[0] - padding - location[0]),
            maxOf(0, hostLocation[1] - padding - location[1]),
            minOf(surfaceView.width, hostLocation[0] + host.width + padding - location[0]),
            minOf(surfaceView.height, hostLocation[1] + host.height + padding - location[1]),
        )
        return strip.takeUnless { it.isEmpty }
    }

    /**
     * A letterboxed video may not cover the bar at all. The ordinary View backdrop is
     * authoritative there; don't retry a copy or retain pixels from the preceding page.
     */
    private fun releaseVideoBackdrop(host: View?) {
        if (backdrop == null) return
        douyinVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()
        douyinVideoScratch = backdrop
        backdrop = null
        douyinCopySuccessEpoch++
        host?.postInvalidateOnAnimation()
    }

    /**
     * Re-arm the capture loop after a failure with an increasing delay. The loop is otherwise
     * self-sustaining (each completion calls requestCurrent again), so a path that can never
     * produce a frame — an unsupported ROM, a detached surface — would otherwise retry at the full
     * capture cadence and build a diagnostics string on every attempt.
     */
    private fun scheduleCaptureRetry() {
        captureRetryDelayMs = if (captureRetryDelayMs == 0L) {
            DOUYIN_VIDEO_RETRY_MIN_MS
        } else {
            (captureRetryDelayMs * 2).coerceAtMost(DOUYIN_VIDEO_RETRY_MAX_MS)
        }
        mainHandler.removeCallbacks(captureWakeup)
        mainHandler.postDelayed(captureWakeup, captureRetryDelayMs)
    }

    private fun requestDouyinSurfaceControlFrame(surfaceView: SurfaceView, startedAt: Long): Boolean {
        if (Build.VERSION.SDK_INT < 29 ||
            !surfaceView.isAttachedToWindow) return false
        val control = runCatching { surfaceView.surfaceControl }.getOrNull()
            ?.takeIf { it.isValid } ?: return false
        // Snapshot geometry on the UI thread, publish it atomically with its frame.
        val location = IntArray(2).also(surfaceView::getLocationInWindow)
        val host = currentHost ?: return false
        val crop = douyinVideoStrip(surfaceView, host) ?: run {
            releaseVideoBackdrop(host)
            return true
        }
        val requestGeneration = ++captureGeneration
        val worker = compositorPixelCopyHandler ?: run {
            val thread = HandlerThread("LiquidTab-Douyin-SurfaceCapture")
            thread.start()
            compositorPixelCopyThread = thread
            Handler(thread.looper).also { compositorPixelCopyHandler = it }
        }
        douyinVideoCopyInFlight = true
        douyinVideoCopyStartedAt = startedAt
        worker.post {
            runCatching {
                val api = captureApi ?: run {
                  val captureClass = if (Build.VERSION.SDK_INT >= 34) {
                    runCatching { Class.forName("android.window.ScreenCaptureInternal") }
                        .getOrElse { Class.forName("android.window.ScreenCapture") }
                } else SurfaceControl::class.java
                val builderClass = Class.forName(captureClass.name + "\$LayerCaptureArgs\$Builder")
                val baseBuilder = Class.forName(captureClass.name + "\$CaptureArgs\$Builder")
                  CaptureApi(captureClass, builderClass, baseBuilder,
                      Class.forName(captureClass.name + "\$ScreenCaptureListener"))
                      .also { captureApi = it }
                }
                val captureClass = api.capture
                val builderClass = api.builder
                val baseBuilder = api.baseBuilder
                val builder = org.lsposed.hiddenapibypass.HiddenApiBypass.newInstance(builderClass, control)
                org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(baseBuilder, builder, "setSourceCrop", crop)
                // Half-resolution output: the renderer stretches whatever size it receives
                // onto the published strip rect, so this changes sampling density only. A
                // ROM that refuses the scale must still deliver a frame, so a failure here
                // keeps the default 1.0 rather than failing the whole capture.
                runCatching {
                    org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                        baseBuilder, builder, "setFrameScale", DOUYIN_VIDEO_CAPTURE_SCALE,
                    )
                }
                // The default UID (-1) requests other apps' layers and is denied.
                org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(baseBuilder, builder, "setUid", android.os.Process.myUid().toLong())
                val args = org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(builderClass, builder, "build")
                val listenerClass = api.listener
                val consumer = java.util.function.ObjIntConsumer<Any> { capture, status ->
                    var failure: String? = null
                    val frame = if (status == 0 && capture != null) runCatching {
                        val hb = capture.javaClass.getMethod("getHardwareBuffer")
                            .invoke(capture) as android.hardware.HardwareBuffer
                        try {
                            val cs = capture.javaClass.getMethod("getColorSpace")
                                .invoke(capture) as? android.graphics.ColorSpace
                            Bitmap.wrapHardwareBuffer(hb, cs)
                                ?: error("wrapHardwareBuffer failed")
                        } finally {
                            hb.close()
                        }
                    }.onFailure {
                        failure = "${it.javaClass.simpleName}: ${it.message}; cause=${it.cause}"
                    }.getOrNull() else null
                    mainHandler.post completion@ {
                        if (requestGeneration != captureGeneration) {
                            frame?.recycle()
                            return@completion
                        }
                        douyinAsyncCaptureListener = null
                        douyinVideoCopyInFlight = false
                        douyinVideoCopyStartedAt = 0L
                        if (frame != null && currentHost?.isAttachedToWindow == true && surfaceView.isAttachedToWindow &&
                            surfaceView.isShown && pageAllowed
                        ) {
                            val previous = backdrop
                            backdrop = frame
                            captureRetryDelayMs = 0L
                            douyinVideoSurfaceLeft = location[0] + crop.left
                            douyinVideoSurfaceTop = location[1] + crop.top
                            douyinVideoSurfaceWidth = crop.width()
                            douyinVideoSurfaceHeight = crop.height()
                            douyinCopySuccessEpoch++
                            douyinVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()
                            douyinVideoScratch = previous
                            val completedAt = SystemClock.uptimeMillis()
                            // The capture-rate window exists only to produce the log line
                            // below, so it is accumulated only while diagnostics are on.
                            currentHost?.postInvalidateOnAnimation()
                            requestCurrent()
                        } else {
                            frame?.recycle()
                            resetDouyinVideoProbe("async status=$status")
                            scheduleCaptureRetry()
                        }
                    }
                }
                val listener = org.lsposed.hiddenapibypass.HiddenApiBypass.newInstance(listenerClass, consumer)
                douyinAsyncCaptureListener = listener
                val status = org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                    captureClass,
                    null,
                    "captureLayers",
                    args,
                    listener,
                ) as Int
                check(status == 0) { "async capture status=$status" }
            }.onFailure { error ->
                mainHandler.post failure@ {
                    if (requestGeneration != captureGeneration) return@failure
                    douyinAsyncCaptureListener = null
                    douyinVideoCopyInFlight = false
                    douyinVideoCopyStartedAt = 0L
                    resetDouyinVideoProbe("submit failure")
                    scheduleCaptureRetry()
                }
            }
        }
        return true
    }
    /**
     * TextureView fallback for the Douyin video readback path, used only when
     * no usable SurfaceView is found in the feed tree. TextureView.getBitmap
     * is a blocking GPU readback, so it runs on the compositor worker thread
     * and the finished frame is marshalled back to the UI thread.
     */
    private fun sampleDouyinVideoTextureViewFrame() {

        val textureView = douyinVideoTextureView ?: return

        if (!textureView.isAttachedToWindow || !textureView.isShown ||
            textureView.width <= 0 || textureView.height <= 0 ||
            textureView.surfaceTexture == null
        ) return

        val now = SystemClock.uptimeMillis()

        if (now - lastDouyinVideoCopy < DOUYIN_VIDEO_COPY_INTERVAL_MS) return

        lastDouyinVideoCopy = now

        val targetWidth = max(1, (textureView.width / CAPTURE_DOWNSCALE).roundToInt())

        val targetHeight = max(1, (textureView.height / CAPTURE_DOWNSCALE).roundToInt())

        val tv = textureView

        val worker = compositorPixelCopyHandler

        if (worker == null || !worker.post {

            val frame: Bitmap? = try {

                tv.getBitmap(targetWidth, targetHeight)

            } catch (t: Throwable) {

                null

            }

            mainHandler.post {

                if (frame != null && !frame.isRecycled && currentHost?.isAttachedToWindow == true &&

                    pageAllowed
                ) {

                    val loc = IntArray(2).also(tv::getLocationInWindow)

                    douyinVideoSurfaceLeft = loc[0]

                    douyinVideoSurfaceTop = loc[1]

                    douyinVideoSurfaceWidth = tv.width

                    douyinVideoSurfaceHeight = tv.height

                    val previous = backdrop

                    backdrop = frame

                    douyinVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()

                    douyinVideoScratch = previous

                    currentHost?.postInvalidateOnAnimation()

                } else {

                    frame?.takeUnless(Bitmap::isRecycled)?.recycle()

                }

            }

        }) { }

    }

    /**
     * PixelCopy the UnicornSurfaceView at the glass host's real screen position.

     * Because this Surface contains only Taobao's independently composed page,

     * it never includes our overlay and therefore cannot recursively sample the

     * glass. Sampling the strip above the host (the former Window workaround)

     * produced a visible vertical mismatch between refraction and the feed.

     */

    fun dispose() {
        captureGeneration++
        mainHandler.removeCallbacks(captureWakeup)
        mainHandler.removeCallbacks(stripWatch)
        stripWatchScheduled = false
        stripStatic = false
        stripTexture = null
        stripEdgeCheckInFlight = false
        stripEdgeBitmap = null
        currentHost = null
        pageAllowed = false
        douyinVideoHolderWatched?.holder?.removeCallback(douyinVideoHolderCallback)
        douyinVideoHolderWatched = null
        compositorPixelCopyThread?.quitSafely()
        compositorPixelCopyThread = null
        compositorPixelCopyHandler = null
        douyinVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()
        douyinVideoScratch = null
        backdrop?.takeUnless(Bitmap::isRecycled)?.recycle()
        backdrop = null
    }
    private companion object {
        /**
         * Feed-video capture cadence. Was 8 ms (~125 Hz), which is faster than any panel can
         * display and roughly double what the glass can show: every capture is a
         * SurfaceControl readback, so half of them were paid for and thrown away. 16 ms
         * (~62 Hz) matches a real frame interval. Lower it back toward 8 ms only if the
         * video behind the glass visibly steps during a fast swipe.
         */
        const val DOUYIN_VIDEO_COPY_INTERVAL_MS = 16L
        /**
         * Output scale of the SurfaceControl capture. The capture is already cropped to the
         * strip behind the glass, so halving both dimensions quarters the pixels and bytes
         * of every readback. The renderer stretches whatever size arrives onto the published
         * strip rect, so this changes sampling density only, never geometry. The glass blurs
         * this input, so the lost detail is invisible.
         */
        const val DOUYIN_VIDEO_CAPTURE_SCALE = 0.5f
        /** First retry delay after a failed capture; doubles up to [DOUYIN_VIDEO_RETRY_MAX_MS]. */
        const val DOUYIN_VIDEO_RETRY_MIN_MS = 32L
        const val DOUYIN_VIDEO_RETRY_MAX_MS = 500L
        const val DOUYIN_VIDEO_COPY_TIMEOUT_MS = 250L
        const val DOUYIN_VIDEO_FIND_INTERVAL_MS = 200L
        const val DOUYIN_VIDEO_PROBE_MAX = 4
        const val DOUYIN_VIDEO_FIND_MAX_VIEWS = 40_000
        const val DOUYIN_VIDEO_FIND_MAX_DEPTH = 48
        /** Downscale for the bitmap readback paths (strip capture and the TextureView fallback). */
        const val CAPTURE_DOWNSCALE = 2f

        /** How often the bar backyard is sampled; black or two stable samples mark it static. */
        const val BAR_STRIP_INTERVAL_MS = 600L
        /** Over content there is no candidate: geometry only needs a slow check. */
        const val BAR_STRIP_SLOW_INTERVAL_MS = 1_500L
        /** Background/detached maintenance tick; keeps a static bar recoverable. */
        const val BAR_STRIP_IDLE_INTERVAL_MS = 2_000L
        /** Mean per-channel difference at or below which two edge samples count as stable. */
        const val BAR_STRIP_EDGE_STABLE_DIFF = 2.5f
        /** How long a black-edge confirmation stays valid before it is re-checked. */
        const val BAR_STRIP_EDGE_TTL_MS = 2_500L
        /** Don't hammer PixelCopy while no usable confirmation exists. */
        const val BAR_STRIP_EDGE_RETRY_MS = 1_000L
    }
}
