package io.github.xzdw.dylt.hook.adapters.douyin

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import io.github.xzdw.dylt.hookx.MethodHook
import io.github.xzdw.dylt.hookx.Xposed
import io.github.xzdw.dylt.hook.HookLoadContext
import java.io.File
import java.util.WeakHashMap

/** Native 40.6.0 feed output selection, verified against the installed APK.
 * The player creates its own texture wrapper and retains its native lifecycle.
 */
internal object DouyinPlayerTextureHooks {
    private val feedScope = ThreadLocal.withInitial { 0 }
    private val textures = WeakHashMap<TextureView, Boolean>()
    private var lastActiveTexture = java.lang.ref.WeakReference<TextureView>(null)

/**
 * Dimensions the player last handed to [android.graphics.SurfaceTexture.setDefaultBufferSize],
 * keyed by the SurfaceTexture itself. Douyin preloads the next feed item on a second surface,
 * so a single global "last size" would alternate between two videos and reset the strip
 * watcher on every sample.
 */
private val videoSizes = java.util.WeakHashMap<android.graphics.SurfaceTexture, Long>()

fun videoSize(texture: TextureView): Pair<Int, Int>? {
    val surfaceTexture = texture.surfaceTexture ?: return null
    val packed = synchronized(videoSizes) { videoSizes[surfaceTexture] } ?: return null
    val width = (packed ushr 32).toInt()
    val height = packed.toInt()
    return if (width > 0 && height > 0) width to height else null
}
    private class GeometryScratch {
        val hostLocation = IntArray(2)
        val visible = Rect()
    }
    private val geometryScratch = ThreadLocal.withInitial { GeometryScratch() }
    private var installed = false
    private var factoryDiagCount = 0

    /** The selection line is useful once; it used to fire for every feed player created. */
    private var textureSelectionLogged = false
    /** Version-support answer per Context; see [versionSupported]. Concurrency-safe. */
    private val baseSupportCache =
        java.util.Collections.synchronizedMap(WeakHashMap<Context, Boolean>())
    private val scopedCalls = java.util.Collections.synchronizedMap(WeakHashMap<MethodHook.MethodHookParam, Boolean>())
    private val selectedCalls = java.util.Collections.synchronizedMap(WeakHashMap<MethodHook.MethodHookParam, Boolean>())

    fun install(context: HookLoadContext) {
        if (installed || context.processName != context.packageName) return
        Xposed.log("[OfflineGlass] DouyinTextureHooks.install process=${context.processName}")
        runCatching {
            Xposed.hookAllMethods(
                android.graphics.SurfaceTexture::class.java,
                "setDefaultBufferSize",
                object : MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val width = p.args.getOrNull(0) as? Int ?: return
                        val height = p.args.getOrNull(1) as? Int ?: return
                        val surfaceTexture =
                            p.thisObject as? android.graphics.SurfaceTexture ?: return
                        if (width > 0 && height > 0) {
                            synchronized(videoSizes) {
                                videoSizes[surfaceTexture] =
                                    (width.toLong() shl 32) or
                                        (height.toLong() and 0xFFFF_FFFFL)
                            }
                        }
                    }
                },
            )
        }
        runCatching {
            val feed = Xposed.findClass("com.ss.android.ugc.aweme.feed.ui.FeedVideoPlayerView", context.classLoader)
            // The feed output factory has been re-mapped more than once: X.0wMt (40.6.0),
            // X.1KBI (40.7.0), X.0GO1 (the 2026-10-17 build). Selecting by name alone is
            // not enough, because obfuscated names get recycled — the 2026-10-17 APK still
            // contains X.1KBI, but as an unrelated class, so a name-only probe picked it and
            // reported "factory not found". Resolve by *capability* instead: take the first
            // candidate that really declares LJIILIIL(ViewGroup, boolean) and returns its
            // own type, which is the feed-output builder.
            val factory = listOf("X.0GO1", "X.1KBI", "X.0wMt").firstNotNullOfOrNull { name ->
                runCatching { Xposed.findClass(name, context.classLoader) }
                    .getOrNull()
                    ?.takeIf { candidate ->
                        runCatching {
                            candidate.getDeclaredMethod(
                                "LJIILIIL", ViewGroup::class.java, Boolean::class.javaPrimitiveType,
                            )
                        }.isSuccess
                    }
            }
            val hasFactoryMethod = factory != null
            if (!hasFactoryMethod) {
                Xposed.log("[OfflineGlass] feed output factory not found; capture fallback stays")
            }
            feed.getDeclaredMethod("LJJIJIIJIL")
            Xposed.hookAllMethods(feed, "LJJIJIIJIL", object : MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val parent = runCatching { feed.getDeclaredField("a").apply { isAccessible = true }.get(p.thisObject) as? ViewGroup }.getOrNull() ?: return
                    if (!eligible(parent.context)) return
                    scopedCalls[p] = true
                    feedScope.set(feedScope.get() + 1)
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (scopedCalls.remove(p) == true) feedScope.set((feedScope.get() - 1).coerceAtLeast(0))
                }
            })
            if (hasFactoryMethod) Xposed.hookAllMethods(factory, "LJIILIIL", object : MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (feedScope.get() == 0) {
                        val parent = p.args.firstOrNull() as? ViewGroup ?: return
                        // X.0wei is the installed APK's Horizon LivePhotoPlayer,
                        // verified through LivePhotoPlayer$onCompleteObserver$1.
                        val livePhoto = hasLivePhotoVideoHost(parent) ||
                            hasHorizonCallerOnStack()
                        // Horizon prerender may use an application Context;
                        // its exact LivePhoto host, rather than Context type,
                        // identifies this path.
                        if (!livePhoto || !eligible(parent.context, requireHomepage = false)) return
                    }
                    selectedCalls[p] = true
                    p.args[1] = false // Verified native else branch: X.0DmT(parent).
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    val selected = selectedCalls.remove(p) == true
                    val created = p.result ?: return
                    // The 40.7.0 factory returns its builder (X.1KBI); the actual video
                    // view lives behind that builder's `wrapper` field, whose getView()
                    // yields the TextureView. Older builds returned the wrapper directly,
                    // so both shapes are accepted.
                    val output = runCatching {
                        val wrapper = runCatching {
                            created.javaClass.getDeclaredField("wrapper")
                                .apply { isAccessible = true }
                                .get(created)
                        }.getOrNull()
                        val fromWrapper = wrapper?.let {
                            Xposed.callMethod(it, "getView") as? View
                        }
                        fromWrapper ?: (Xposed.callMethod(created, "getView") as? View)
                    }.getOrNull()
                    // Report the first few factory calls: this shows whether the feed
                    // path is reached at all and what kind of view it produced.
                    if (factoryDiagCount < 6) {
                        factoryDiagCount++
                        Xposed.log(
                            "[OfflineGlass][Factory] selected=$selected " +
                                "result=${created.javaClass.simpleName} " +
                                "output=${output?.javaClass?.name} arg1=${p.args.getOrNull(1)}",
                        )
                    }
                    if (output == null) return
                    val parent = p.args.firstOrNull() as? ViewGroup
                    if (!selected) return
                    val texture = output as? TextureView ?: return
                    synchronized(textures) { textures[texture] = true }
                    if (!textureSelectionLogged) {
                        textureSelectionLogged = true
                        Xposed.log("[OfflineGlass] native feed texture selected: ${texture.javaClass.name}")
                    }
                }
            })
            // Douyin chooses between a SurfaceView and a TextureView through these
            // switches on the same factory class. A SurfaceView is its own hardware
            // layer, which the glass cannot sample — that is exactly why the bottle
            // fell back to PixelCopy readback and lagged. Forcing the TextureView side
            // removes the readback. All wrapped: if a name moves again, the existing
            // capture fallback simply stays in place.
            if (hasFactoryMethod) runCatching {
                listOf(
                    "sIsEnableSurfaceviewForGeneralDevice",
                    "sIsEnableSurfaceviewForGeneralDevicesFetched",
                    "sIsSurfaceViewEnabledWhenInOppoBlackList",
                    "sIsSurfaceViewEnabledWhenInOppoBlackListFetched",
                ).forEach { fieldName ->
                    runCatching {
                        factory!!.getDeclaredField(fieldName).apply { isAccessible = true }
                            .setBoolean(null, false)
                    }
                }
                listOf("LJIJJ", "LJJI", "LJJIIJZLJL").forEach { methodName ->
                    Xposed.hookAllMethods(factory, methodName, object : MethodHook() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            p.result = false
                        }
                    })
                }
                Xposed.log("[OfflineGlass] surfaceView switches forced off (texture output)")
            }.onFailure {
                Xposed.log("[OfflineGlass] surfaceView switch hook skipped: $it")
            }
            installed = true
            installLivePreview(context)
            Log.i("OfflineGlassDouyin", "native feed texture hooks installed")
            Xposed.log("[OfflineGlass] DouyinTextureHooks installed OK")
        }.onFailure {
            Log.w("OfflineGlassDouyin", "native texture unavailable; retain capture fallback", it)
            Xposed.log("[OfflineGlass] DouyinTextureHooks FAILED: $it")
        }
    }

    /** 40.6.0 LivePlayerView.LJII uses config.LJII to create its native renderer.
     * Keep the player's own renderer construction and surface lifecycle.
     * Scope to homepage contexts, leaving full live rooms and other apps alone.
     */
    private fun installLivePreview(context: HookLoadContext) {
        runCatching {
            val player = Xposed.findClass("com.bytedance.android.livesdkapi.roomplayer.LivePlayerView", context.classLoader)
            player.getDeclaredMethod("LJII", Context::class.java)
            val configField = player.getDeclaredField("config").apply { isAccessible = true }
            val configClass = configField.type
            val modeField = configClass.getDeclaredField("LJII").apply { isAccessible = true }
            val keepTexture = modeField.type.enumConstants.first { (it as Enum<*>).name == "KEEP_TEXTURE_RENDER_VIEW" }
            Xposed.hookAllMethods(player, "LJII", object : MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val context = p.args.firstOrNull() as? Context ?: return
                    if (!eligible(context)) return
                    val config = configField.get(p.thisObject) ?: return
                    val mode = modeField.get(config) as? Enum<*> ?: return
                    if (mode.name == "SURFACE_VIEW") modeField.set(config, keepTexture)
                    selectedCalls[p] = true
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (selectedCalls.remove(p) != true) return
                    val texture = p.result as? TextureView ?: return
                    synchronized(textures) { textures[texture] = true }
                }
            })
            Log.i("OfflineGlassDouyin", "native live preview texture hook installed")
        }.onFailure { Log.w("OfflineGlassDouyin", "live preview retains capture fallback", it) }
    }
    /**
     * Whether the calling frame chain contains the horizon LivePhoto player wrapper classes
     * (`X.0wei` / `X.0weh`).
     *
     * The walk is bounded to [STACK_SCAN_DEPTH] frames: the caller sits within a few frames of
     * the hooked factory method, and a full `stackTrace` copy on every factory call was pure
     * per-frame cost on the app's own thread.
     */
    private fun hasHorizonCallerOnStack(): Boolean {
        val frames = Thread.currentThread().stackTrace
        val limit = minOf(frames.size, STACK_SCAN_DEPTH)
        for (i in 0 until limit) {
            val name = frames[i].className
            if (name == "X.0wei" || name == "X.0weh") return true
        }
        return false
    }

    /**
     * Version support and the manual capture-fallback opt-out, resolved once per Context.
     *
     * `getPackageInfo` is a PackageManager query and `File.exists()` a disk stat; both used to
     * run on every hooked call. Only a *successfully read* version is cached, so a lookup that
     * throws during early startup is retried on the next call instead of latching "unsupported"
     * for the life of the process.
     *
     * The fallback file is deliberately part of the cached answer: its whole purpose is to be
     * checked before the app restarts, so "created it, saw no change" stays an honest report.
     */
    /**
     * The manual capture-fallback opt-out marker, resolved live but with its directory memoised.
     *
     * The `exists()` check stays live on purpose (see [versionSupported]): dropping
     * `dy_capture_fallback` into the target's external files dir has to take effect without
     * restarting the app, and a cached "absent" would turn "created it, saw no change" into a
     * false report. What *is* memoised is the directory: `Context.getExternalFilesDir` is a
     * synchronized `ContextImpl` lookup and [eligible] runs on every feed player construction,
     * while the `stat` that actually answers the question costs microseconds.
     */
    @Volatile
    private var captureFallbackMarker: File? = null

    private fun captureFallbackForced(context: Context): Boolean {
        val marker = captureFallbackMarker ?: runCatching {
            File(context.getExternalFilesDir(null), "dy_capture_fallback")
        }.getOrNull()?.also { captureFallbackMarker = it } ?: return false
        return runCatching { marker.exists() }.getOrDefault(false)
    }

    private fun versionSupported(context: Context): Boolean {
        baseSupportCache[context]?.let { return it }
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: return false
        // Do not pin an exact build string. Douyin ships APK updates that keep the same
        // versionName — the 2026-10-17 build still reports 40.7.0 — so an equality check
        // silently disables the hook, which is indistinguishable from "the feature
        // stopped working". Accept 40.6.0 and anything newer instead; every hook below is
        // guarded by runCatching and the factory is resolved by capability, so an
        // unexpected build degrades to the capture fallback rather than breaking playback.
        val major = version.substringBefore('.').toIntOrNull() ?: return false
        val minor = version.substringAfter('.', "0").substringBefore('.').toIntOrNull() ?: 0
        val supported = major > 40 || (major == 40 && minor >= 6)
        baseSupportCache[context] = supported
        return supported
    }

    private fun eligible(context: Context, requireHomepage: Boolean = true): Boolean {
        // The class names below were verified against 40.6.0, and the installed 40.7.0
        // keeps the same feed player surface classes. Gating on the exact version forced
        // every newer build onto the PixelCopy path, whose readback is what made the glass
        // lag behind the video. Every hook is wrapped in runCatching and falls back
        // cleanly, so a renamed hook degrades instead of breaking.
        if (!versionSupported(context)) return false
        if (captureFallbackForced(context)) return false
        if (!requireHomepage) return true
        var current = context
        repeat(12) {
            if (current.javaClass.name == "com.ss.android.ugc.aweme.main.MainActivity" ||
                current.javaClass.name == "com.ss.android.ugc.aweme.splash.SplashActivity") return true
            val base = (current as? ContextWrapper)?.baseContext ?: return false
            if (base === current) return false
            current = base
        }
        return false
    }

    private fun hasLivePhotoVideoHost(parent: View): Boolean {
        // Exact video FrameLayout inflated by X.0wei (Horizon LivePhotoPlayer).
        // Also covers native OPlayer prerender, whose call arrives before play().
        var view: View? = parent
        var horizon = false
        var photos = false
        val pinchVideo = parent.javaClass.simpleName == "VideoPinchViewContainer"
        repeat(16) {
            if (view?.id == 2131389877) return true
            horizon = horizon || view?.javaClass?.simpleName == "HorizonViewPager"
            photos = photos || view?.javaClass?.simpleName == "FeedPhotosGestureDispatchLayout"
            // Actual moving-image page uses Horizon's ordinary video holder,
            // not the separate qeu LivePhoto frame (confirmed by factory log).
            if (pinchVideo && horizon && photos) return true
            view = view?.parent as? View ?: return false
        }
        return false
    }

    fun isNativeTexture(view: View): Boolean = view is TextureView &&
        synchronized(textures) { textures.containsKey(view) }

    /**
     * Presentation timestamp of the newest frame handed to the registered video texture,
     * or null when no texture is known.
     *
     * Deliberately cheaper than [activeTexture]: it reuses the last known texture without
     * re-validating its geometry against the host. Callers use this only to ask "is the
     * producer still handing over frames?" — whether the texture is *sampleable* is still
     * decided by [activeTexture] on the paths that actually draw.
     *
     * `SurfaceTexture.getTimestamp()` advances only when the producer calls
     * `updateTexImage()`, so comparing it across frames answers that question with no
     * pixel readback at all.
     */
    fun currentFrameStamp(): Long? {
        val texture = synchronized(textures) {
            lastActiveTexture.get()?.takeIf { textures.containsKey(it) }
                ?: textures.keys.firstOrNull()
        } ?: return null
        // TextureView.surfaceTexture is a plain nullable getter; no runCatching here because
        // this runs once per frame and the closure would be an allocation in the hot path.
        return texture.surfaceTexture?.timestamp
    }

    /** Small registered-player set, avoiding per-frame traversal of the app tree. */
    fun activeTexture(host: View): TextureView? {
        val scratch = geometryScratch.get()
        val h = scratch.hostLocation.also(host::getLocationOnScreen)
        val visible = scratch.visible
        return synchronized(textures) {
            val retained = lastActiveTexture.get()
            if (retained != null && textures.containsKey(retained) &&
                usable(retained, host, h, visible)) return@synchronized retained
            val next = textures.keys.firstOrNull { usable(it, host, h, visible) }
            if (next !== retained) lastActiveTexture = java.lang.ref.WeakReference(next)
            next
        }
    }

    private fun usable(texture: TextureView, host: View, h: IntArray, visible: Rect): Boolean =
        texture.isAttachedToWindow && texture.isShown && texture.isAvailable &&
            texture.width >= host.width / 2 && texture.height >= host.height &&
            // Check actual clipping every frame; do not cache geometry across swipes.
            texture.getGlobalVisibleRect(visible) &&
            visible.left < h[0] + host.width && visible.right > h[0] &&
            visible.top < h[1] + host.height && visible.bottom > h[1]

    /**
     * Frames of the caller chain inspected by [hasHorizonCallerOnStack]. The horizon wrappers
     * sit a handful of frames above the hooked factory method; the cap exists so a deep stack
     * cannot make the scan unbounded.
     */
    private const val STACK_SCAN_DEPTH = 48
}
