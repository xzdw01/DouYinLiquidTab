package io.github.xzdw.dylt.hook

import android.content.Context
import android.content.SharedPreferences
import io.github.xzdw.dylt.config.ConfigContract
import io.github.xzdw.dylt.config.GlassConfig
import io.github.xzdw.dylt.framework.ModuleService
import io.github.xzdw.dylt.targets.AppCatalog
import io.github.xzdw.dylt.hook.adapters.TargetAdapterRegistry
import io.github.xzdw.dylt.hookx.Xposed

object HookConfigReader {
    // Separate from the compositor worker: slow provider IPC must not hold up frames.
    private val refreshExecutor = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 10L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(),
        java.util.concurrent.ThreadFactory { task ->
            Thread(task, "LiquidTab-Config").apply { isDaemon = true }
        },
    )
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun readFreshAsync(context: Context, packageName: String, complete: (GlassConfig?) -> Unit) {
        val appContext = context.applicationContext ?: context
        refreshExecutor.execute {
            val value = runCatching { readFresh(appContext, packageName) }.getOrNull()
            mainHandler.post { complete(value) }
        }
    }

    private data class Cached(val packageName: String, val config: GlassConfig, val time: Long)
    @Volatile private var cached: Cached? = null
    @Volatile private var qqProcessConfig: GlassConfig? = null

    /**
     * When the module's SettingsProvider is not running (the usual case for a
     * hooked target app), every poll first pays a failing resolver IPC and
     * then falls back to the framework's remote preferences on
     * the UI thread. GlassHostLayout polls once per second from onPreDraw,
     * so back off after a provider failure and serve the last fallback value
     * from memory until the backoff window expires.
     */
    @Volatile private var providerFailedAt = 0L
    @Volatile private var fallbackCache: Cached? = null

    /** Application context of the hooked process, so a pushed config can be cached on disk. */
    @Volatile private var appContext: Context? = null

    /** Last configuration written to the target's own cache, to skip redundant writes. */
    @Volatile private var persistedCache: GlassConfig? = null

    /**
     * Last `[CfgDiag]` line emitted and when, so a poll that keeps producing the same reading
     * does not keep printing it. The 5 s provider backoff means the provider is retried once
     * per window; without this gate every window re-prints the identical failure text.
     */
    @Volatile private var lastCfgDiagMessage = ""
    @Volatile private var lastCfgDiagAt = 0L

    /** Why the last provider attempt failed, folded into the coalesced fallback line. */
    @Volatile private var lastProviderFailure = ""

    /**
     * Whether the last failure means this process cannot reach the provider *at all*
     * ("Unknown authority" / authority not found), as opposed to a transient miss.
     *
     * A target process usually cannot resolve the module's exported provider at any point in
     * its life — that is why the remote-preferences + disk-cache fallback exists. Retrying it
     * every [PROVIDER_FAILURE_BACKOFF_MS] paid a failing resolver IPC, an exception and a
     * framework ERROR line every 5 s, forever, from the UI thread. While this flag is set the
     * retry window is [PROVIDER_ABSENT_BACKOFF_MS] instead.
     */
    @Volatile private var providerPermanentlyAbsent = false

    /**
     * Logs [message] only when it differs from the previous line, or after
     * [CFG_DIAG_REPEAT_MS]. Returns true when the line was actually written.
     */
    private fun cfgDiag(message: String): Boolean {
        val now = System.currentTimeMillis()
        if (message == lastCfgDiagMessage && now - lastCfgDiagAt < CFG_DIAG_REPEAT_MS) return false
        lastCfgDiagMessage = message
        lastCfgDiagAt = now
        Xposed.log("[OfflineGlass][CfgDiag] $message")
        return true
    }

    /**
     * Process-load gate for the per-app master switch. This is intentionally
     * evaluated before any target-specific method hook is installed: a
     * disabled app must run with its completely native navigation and layout,
     * not merely with the GlassHost hidden after mutations have begun.
     */
    fun isAppEnabledAtProcessLoad(packageName: String): Boolean =
        readThroughLsposed(packageName).enabled

    fun read(context: Context, packageName: String): GlassConfig {
        appContext = context.applicationContext ?: context
        TargetAdapterRegistry.forPackage(packageName)?.pushedConfig()?.let { return it }
        if (packageName == QQ_PACKAGE) {
            qqProcessConfig?.let { return it }
            return synchronized(this) {
                qqProcessConfig ?: readCurrent(context, packageName).also { qqProcessConfig = it }
            }
        }
        val now = System.currentTimeMillis()
        cached?.takeIf { it.packageName == packageName && now - it.time < 2_000L }?.let { return it.config }

        val value = readCurrent(context, packageName)
        cached = Cached(packageName, value, now)
        return value
    }

    /** Bypasses the short process-local cache for an already attached glass host. */
    fun readFresh(context: Context, packageName: String): GlassConfig {
        appContext = context.applicationContext ?: context
        TargetAdapterRegistry.forPackage(packageName)?.pushedConfig()?.let { return it }
        if (packageName == QQ_PACKAGE) return read(context, packageName)
        val value = readCurrent(context, packageName)
        cached = Cached(packageName, value, System.currentTimeMillis())
        return value
    }

    fun acceptPushedConfig(packageName: String, config: GlassConfig) {
        if (AppCatalog.forPackage(packageName) == null) return
        val value = config.normalized()
        // A pushed snapshot is the freshest configuration we ever see, so keep it in the
        // target's own cache: that copy is what survives the module being closed.
        persist(packageName, value)
        // Adapter-owned storage is optional, not a prerequisite for accepting
        // a committed module snapshot. Otherwise most adapters discarded the
        // push here and the next poll/rebind restored an older fallback value.
        TargetAdapterRegistry.forPackage(packageName)?.acceptPushedConfig(value)
        val snapshot = Cached(packageName, value, System.currentTimeMillis())
        cached = snapshot
        fallbackCache = snapshot
        providerFailedAt = 0L
    }

    // ---------------------------------------------------------------------------
    // Framework preference push
    //
    // The settings UI mirrors every commit into the framework's remote
    // preferences. A hooked process registers one listener there and re-reads
    // only when something changed, which is what lets GlassHostLayout drop its
    // once-per-second configuration read down to a rare safety net.
    // ---------------------------------------------------------------------------

    private val pushListeners =
        java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<(GlassConfig) -> Unit>>()

    private val pushHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val pushRunnable = Runnable { reloadFromPushedPreferences() }

    @Volatile private var pushStarted = false

    @Volatile private var pushChannelLive = false

    @Volatile private var pushPrefs: SharedPreferences? = null

    @Volatile private var pushPackageName: String? = null

    /** True once the framework delivers settings changes without polling. */
    fun hasPushChannel(): Boolean = pushChannelLive

    /**
     * Registers the framework preference listener for this process. Safe to call
     * once per process from the installer; a no-op when the active backend has no
     * remote preferences (legacy frameworks keep their broadcast + poll path).
     */
    fun startPreferencePush(packageName: String) {
        if (pushStarted) return
        val prefs = Xposed.remotePreferences(ModuleService.CONFIG_GROUP)
        if (prefs == null) {
            Xposed.log("[OfflineGlass] no framework remote preferences; configuration keeps polling")
            return
        }
        pushStarted = true
        pushPrefs = prefs
        pushPackageName = packageName
        pushChannelLive = runCatching {
            prefs.registerOnSharedPreferenceChangeListener { _, _ -> schedulePushReload() }
        }.onFailure {
            Xposed.log("[OfflineGlass] cannot listen for framework preference changes: $it")
        }.isSuccess
        if (pushChannelLive) {
            Xposed.log("[OfflineGlass] framework preference push active for $packageName")
        }
    }

    /**
     * Registers interest in pushed configurations. Held weakly: a listener is
     * normally a view that must not be kept alive by the reader.
     */
    fun addPushListener(listener: (GlassConfig) -> Unit) {
        pushListeners.add(java.lang.ref.WeakReference(listener))
    }

    /** One commit mirrors every key, so the re-read is coalesced. */
    private fun schedulePushReload() {
        pushHandler.removeCallbacks(pushRunnable)
        pushHandler.postDelayed(pushRunnable, PUSH_COALESCE_MS)
    }

    private fun reloadFromPushedPreferences() {
        val prefs = pushPrefs ?: return
        val packageName = pushPackageName ?: return
        val value = runCatching { readFromPrefs(prefs, packageName) }.getOrNull() ?: return
        cfgDiag(
            "framework push for $packageName: blur=${value.blurRadius} " +
                "liquid=${value.liquidGlassEnabled} enabled=${value.enabled}",
        )
        acceptPushedConfig(packageName, value)
        var stale = false
        for (reference in pushListeners) {
            val listener = reference.get()
            if (listener == null) {
                stale = true
                continue
            }
            runCatching { listener(value) }
        }
        if (stale) {
            // A CopyOnWriteArrayList iterator refuses remove(), and calling it killed the
            // target process; rebuild the list instead. Both this reload and registration
            // run on the main thread, so the rebuild cannot race.
            val alive = pushListeners.mapNotNull { it.get() }
            pushListeners.clear()
            alive.forEach { pushListeners.add(java.lang.ref.WeakReference(it)) }
        }
    }

    /**
     * Reads current config — prefers the module's exported SettingsProvider
     * and falls back to the framework's remote preferences.
     *
     * The `xposedsharedprefs` meta-data has been removed from the manifest
     * so the module's SharedPreferences writes to the standard CE path
     * (/data/user/0/io.github.xzdw.dylt/shared_prefs/offline_glass.xml),
     * where the provider reads from and the UI writes to.  Both the provider
     * call and the remote-preferences fallback return the same value, but
     * the provider is tried first for speed (in-process IPC).
     */
    private fun readCurrent(context: Context, packageName: String): GlassConfig {
        val now = System.currentTimeMillis()
        // A provider this process cannot resolve is not retried on the 5 s window.
        val providerBackoff = if (providerPermanentlyAbsent) {
            PROVIDER_ABSENT_BACKOFF_MS
        } else {
            PROVIDER_FAILURE_BACKOFF_MS
        }
        if (AppCatalog.forPackage(packageName) != null &&
            now - providerFailedAt >= providerBackoff
        ) {
            val viaProvider = readThroughProvider(context, packageName)
            if (viaProvider != null) {
                providerFailedAt = 0L
                providerPermanentlyAbsent = false
                persist(packageName, viaProvider)
                return viaProvider
            }
            providerFailedAt = now
            // Folded into one line: the provider reason plus the fallback that follows it.
            // Previously this was two lines ("call threw" from readThroughProvider, then
            // "provider unavailable" here), both reprinted on every backoff window.
            cfgDiag(
                "provider unavailable for $packageName, falling back to remote preferences" +
                    lastProviderFailure.let { if (it.isEmpty()) "" else " ($it)" },
            )
        }
        fallbackCache?.takeIf {
            it.packageName == packageName && now - it.time < PROVIDER_FAILURE_BACKOFF_MS
        }?.let { return it.config }
        // The module's preference file is private to its uid, so this read only yields real
        // values when LSPosed mirrors it (the `xposedsharedprefs` meta-data). Whenever it does
        // not, use the copy this process cached while the module was still reachable — that is
        // what keeps the adjusted settings instead of reverting to the defaults.
        if (!modulePrefsReadable()) {
            ConfigDiskCache.read(context, packageName)?.let { cached ->
                cfgDiag(
                    "module unreachable for $packageName, serving cached " +
                        "config (blur=${cached.blurRadius} controlDrop=${cached.controlDrop})",
                )
                fallbackCache = Cached(packageName, cached, now)
                return cached
            }
            cfgDiag(
                "module unreachable and no cached config for " +
                    "$packageName — defaults will be used",
            )
        }
        val value = readThroughLsposed(packageName)
        cfgDiag(
            "lsposed read for $packageName: blur=${value.blurRadius} " +
                "liquid=${value.liquidGlassEnabled} enabled=${value.enabled}",
        )
        fallbackCache = Cached(packageName, value, now)
        return value
    }

    /**
     * Whether the module's settings are readable from this (hooked) process.
     *
     * A framework that has not handed the module its service yet exposes no
     * preferences at all, which is indistinguishable from a module that never
     * saved — in both cases the local cache is the better source.
     */
    private fun modulePrefsReadable(): Boolean {
        val prefs = modulePrefs() ?: return false
        return runCatching {
            prefs.contains(GLOBAL + ConfigContract.KEY_BAR_HEIGHT) ||
                prefs.contains(GLOBAL + ConfigContract.KEY_BLUR_RADIUS) ||
                prefs.contains("global_defaults_v2")
        }.getOrDefault(false)
    }

    /**
     * The module's settings as this process can see them.
     *
     * Read from the framework's remote preferences, which the settings UI mirrors
     * into on every commit; a process whose framework service is not up yet has
     * no source and falls back to the local cache.
     */
    private fun modulePrefs(): SharedPreferences? =
        Xposed.remotePreferences(ModuleService.CONFIG_GROUP)


    /** Mirrors a freshly read configuration into the target's own cache. */
    private fun persist(packageName: String, config: GlassConfig) {
        val context = appContext ?: return
        if (persistedCache == config) return
        ConfigDiskCache.write(context, packageName, config)
        persistedCache = config
    }

    private fun readThroughProvider(context: Context, packageName: String): GlassConfig? =
        runCatching {
            val bundle = context.contentResolver.call(
                ConfigContract.URI,
                ConfigContract.METHOD_GET,
                packageName,
                null,
            )
            if (bundle == null) {
                lastProviderFailure = "null bundle"
                return@runCatching null
            }
            if (bundle.getString(ConfigContract.KEY_PACKAGE) != packageName) {
                lastProviderFailure = "package mismatch: " +
                    bundle.getString(ConfigContract.KEY_PACKAGE)
                return@runCatching null
            }
            val parsed = GlassConfig.fromBundle(bundle)
            lastProviderFailure = ""
            cfgDiag(
                "provider read for $packageName: " +
                    "blur=${parsed.blurRadius} enabled=${parsed.enabled}",
            )
            parsed
        }.onFailure {
            // Kept only as the reason folded into the coalesced fallback line; the failure
            // itself is not an error the module can act on. LSPosed appends a per-call
            // callback id to every message it throws through, which made the text unique on
            // every attempt and defeated the repeat gate — keep only the stable sentence.
            val detail = it.message.orEmpty()
                .substringBefore(": [")
                .substringBefore(" [")
                .trim()
                .take(96)
            lastProviderFailure = it.javaClass.simpleName + ": " + detail
            // A missing authority is not a transient miss: nothing about this process will
            // resolve it later, so stop paying the resolver IPC on the short window.
            val absent = it is IllegalArgumentException &&
                (detail.contains("Unknown authority") || detail.contains("not found"))
            providerPermanentlyAbsent = absent
        }.getOrNull()

    private fun readThroughLsposed(packageName: String): GlassConfig = runCatching {
        val p = modulePrefs() ?: return@runCatching GlassConfig()
        readFromPrefs(p, packageName)
    }.getOrElse { GlassConfig() }

    private fun readFromPrefs(p: SharedPreferences, packageName: String): GlassConfig {
        val global = readConfig(p, GLOBAL, GlassConfig())
            .copy(
                liquidGlassEnabled = p.getBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, true),
                bottomGradientBlurEnabled = false,
                    outlineEnabled = p.getBoolean(ConfigContract.KEY_OUTLINE_ENABLED, true),
            )
        val spec = AppCatalog.forPackage(packageName)
        val canonicalPackage = spec?.packageName ?: packageName
        val defaults = global.copy(
            // 底栏显示样式 owns the icon-only flag now (see readConfig); the spec's own default
            // would be overwritten by normalized() anyway.
            customAccentColor = spec?.defaultAccentColor ?: global.customAccentColor,
        )
        val config = if (p.getBoolean("appcfg_${canonicalPackage}_override", false)) {
            readConfig(p, "appcfg_${canonicalPackage}_", defaults)
        } else {
            defaults
        }
        return config.copy(
            enabled = p.getBoolean("app_$canonicalPackage", true),
            blurRadius = global.blurRadius,
            cornerRadiusPercent = 50f,
            cornerSmoothing = global.cornerSmoothing,
            barHeight = global.barHeight,
            lightAlpha = global.lightAlpha,
            darkAlpha = global.darkAlpha,
            darkBarHighlightStrength = global.darkBarHighlightStrength,
            glassAlphaScale = global.glassAlphaScale,
            glassSaturation = global.glassSaturation,
            refractionHeightDp = global.refractionHeightDp,
            refractionStrengthDp = global.refractionStrengthDp,
            // Kept global like the other visual switches: the 控件下移 toggle lives on the
            // settings page, and a stale per-app override must not silently ignore it.
            controlDrop = global.controlDrop,
            // Same contract as 控件下移: 去除发布按钮 is a settings-page switch.
            showPostButton = global.showPostButton,
            // Same contract again: 隐藏消息红点 is a settings-page switch, and a stale per-app
            // override written before it existed must not shadow it.
            hideMessageBadge = global.hideMessageBadge,
            // And again for 底栏菜单项隐藏: the switches live on the settings page, so the mask
            // is read from the global prefix only.
            hiddenMask = global.hiddenMask,
            // 底栏菜单文字 is the same kind of settings-page value.
            customEntryLabels = global.customEntryLabels,
            // 顶栏参数 is the settings page's top-copy geometry, shared by every app.
            topBarMarginOffsetDp = global.topBarMarginOffsetDp,
            topBarScale = global.topBarScale,
            // The two side keys, label count and label order are settings-page values too.
            topBarHideLeadingAction = global.topBarHideLeadingAction,
            topBarHideTrailingAction = global.topBarHideTrailingAction,
            topBarTabCount = global.topBarTabCount,
            topBarTabOrder = global.topBarTabOrder,
            antiBurnIn = global.antiBurnIn,
            // 底栏显示样式 is a settings-page choice as well, and it owns the derived icon-only flag.
            bottomBarStyle = global.bottomBarStyle,
            publishIcon = global.publishIcon,
            bottomGradientBlurEnabled = false,
        ).normalized()
    }

    private fun readConfig(p: SharedPreferences, prefix: String, defaults: GlassConfig): GlassConfig = GlassConfig(
        // Global-only, exactly like the manager writes it: 底栏下边距 is a settings-page slider and
        // an app prefix must not carry a stale, shadowing copy.
        bottomMarginOffsetDp = if (prefix == GLOBAL) {
            p.getFloat(GLOBAL + ConfigContract.KEY_BOTTOM_MARGIN_OFFSET, defaults.bottomMarginOffsetDp)
        } else {
            defaults.bottomMarginOffsetDp
        },
        // Global-only, exactly like the manager writes it. Missing this key made every pushed
        // config (the remote-preferences channel) report the top copy as switched off, which
        // removed the top host and restored Douyin's native title row on any live change.
        topBarLiquidGlass = if (prefix == GLOBAL) {
            p.getBoolean(GLOBAL + ConfigContract.KEY_TOP_BAR_LIQUID, defaults.topBarLiquidGlass)
        } else {
            defaults.topBarLiquidGlass
        },
        // 顶栏参数: the knobs are written by the settings page, exactly like the switch above.
        topBarMarginOffsetDp = if (prefix == GLOBAL) {
            p.getFloat(GLOBAL + ConfigContract.KEY_TOP_BAR_MARGIN_OFFSET, defaults.topBarMarginOffsetDp)
        } else {
            defaults.topBarMarginOffsetDp
        },
        topBarScale = if (prefix == GLOBAL) {
            p.getFloat(GLOBAL + ConfigContract.KEY_TOP_BAR_SCALE, defaults.topBarScale)
        } else {
            defaults.topBarScale
        },
        // 顶栏两侧按钮 / 顶栏标签数量 / 顶栏标签排序: settings-page values like the geometry above.
        topBarHideLeadingAction = if (prefix == GLOBAL) {
            p.getBoolean(GLOBAL + ConfigContract.KEY_TOP_BAR_HIDE_LEADING, defaults.topBarHideLeadingAction)
        } else {
            defaults.topBarHideLeadingAction
        },
        topBarHideTrailingAction = if (prefix == GLOBAL) {
            p.getBoolean(GLOBAL + ConfigContract.KEY_TOP_BAR_HIDE_TRAILING, defaults.topBarHideTrailingAction)
        } else {
            defaults.topBarHideTrailingAction
        },
        topBarTabCount = if (prefix == GLOBAL) {
            p.getInt(GLOBAL + ConfigContract.KEY_TOP_BAR_TAB_COUNT, defaults.topBarTabCount)
        } else {
            defaults.topBarTabCount
        },
        topBarTabOrder = if (prefix == GLOBAL) {
            p.getString(GLOBAL + ConfigContract.KEY_TOP_BAR_TAB_ORDER, defaults.topBarTabOrder)
                ?: defaults.topBarTabOrder
        } else {
            defaults.topBarTabOrder
        },
        controlDrop = p.getBoolean(prefix + ConfigContract.KEY_CONTROL_DROP, defaults.controlDrop),
        antiBurnIn = if (prefix == GLOBAL) {
            p.getBoolean(GLOBAL + ConfigContract.KEY_ANTI_BURN_IN, defaults.antiBurnIn)
        } else {
            defaults.antiBurnIn
        },
        enabled = defaults.enabled,
        themeMode = p.getInt(prefix + ConfigContract.KEY_THEME_MODE, defaults.themeMode),
        tabWidth = p.getFloat(prefix + ConfigContract.KEY_TAB_WIDTH, defaults.tabWidth),
        bottomPadding = p.getFloat(prefix + ConfigContract.KEY_BOTTOM_PADDING, defaults.bottomPadding),
        blurRadius = p.getFloat(prefix + ConfigContract.KEY_BLUR_RADIUS, defaults.blurRadius),
        cornerRadiusPercent = p.getFloat(prefix + ConfigContract.KEY_CORNER_RADIUS_PERCENT, defaults.cornerRadiusPercent),
        cornerSmoothing = p.getFloat(prefix + ConfigContract.KEY_CORNER_SMOOTHING, defaults.cornerSmoothing),
        barHeight = p.getFloat(prefix + ConfigContract.KEY_BAR_HEIGHT, defaults.barHeight),
        lightAlpha = p.getFloat(prefix + ConfigContract.KEY_LIGHT_ALPHA, defaults.lightAlpha),
        darkAlpha = p.getFloat(prefix + ConfigContract.KEY_DARK_ALPHA, defaults.darkAlpha),
        darkBarHighlightStrength = p.getFloat(prefix + ConfigContract.KEY_DARK_BAR_HIGHLIGHT_STRENGTH, defaults.darkBarHighlightStrength),
        glassAlphaScale = p.getFloat(prefix + ConfigContract.KEY_GLASS_ALPHA_SCALE, defaults.glassAlphaScale),
        glassSaturation = p.getFloat(prefix + ConfigContract.KEY_GLASS_SATURATION, defaults.glassSaturation),
        refractionHeightDp = p.getFloat(prefix + ConfigContract.KEY_REFRACTION_HEIGHT, defaults.refractionHeightDp),
        refractionStrengthDp = p.getFloat(prefix + ConfigContract.KEY_REFRACTION_STRENGTH, defaults.refractionStrengthDp),
        bloomGlowStrength = p.getFloat(prefix + ConfigContract.KEY_BLOOM_GLOW_STRENGTH, defaults.bloomGlowStrength),
        bloomGlowDp = p.getFloat(prefix + ConfigContract.KEY_BLOOM_GLOW_DP, defaults.bloomGlowDp),

        iconScale = p.getFloat(prefix + ConfigContract.KEY_ICON_SCALE, defaults.iconScale),
        textSize = p.getFloat(prefix + ConfigContract.KEY_TEXT_SIZE, defaults.textSize),
        iconOnly = p.getBoolean(prefix + ConfigContract.KEY_ICON_ONLY, defaults.iconOnly),
        // 底栏显示样式 is the switch the settings page writes; `iconOnly` is re-derived from it by
        // normalized(), so the per-prefix read above only ever carries an obsolete copy.
        bottomBarStyle = if (prefix == GLOBAL) {
            p.getInt(GLOBAL + ConfigContract.KEY_BOTTOM_BAR_STYLE, defaults.bottomBarStyle)
        } else {
            defaults.bottomBarStyle
        },
        // Global-only, exactly like the manager writes it. The 底栏菜单项隐藏 switches write the
        // GLOBAL prefix, so reading the mask from an app prefix would only ever return a stale
        // pre-existing copy and make the switches look dead in the hooked app.
        hiddenMask = if (prefix == GLOBAL) {
            p.getInt(GLOBAL + ConfigContract.KEY_HIDDEN_MASK, defaults.hiddenMask)
        } else {
            defaults.hiddenMask
        },
        // Global-only as well: 底栏菜单文字 is written by the settings page.
        customEntryLabels = if (prefix == GLOBAL) {
            p.getString(GLOBAL + ConfigContract.KEY_CUSTOM_ENTRY_LABELS, defaults.customEntryLabels)
                ?: defaults.customEntryLabels
        } else {
            defaults.customEntryLabels
        },
        hideMessageBadge = p.getBoolean(prefix + ConfigContract.KEY_HIDE_MESSAGE_BADGE, defaults.hideMessageBadge),
        showPostButton = p.getBoolean(prefix + ConfigContract.KEY_SHOW_POST, defaults.showPostButton),
        customAccentEnabled = p.getBoolean(prefix + ConfigContract.KEY_CUSTOM_ACCENT_ENABLED, defaults.customAccentEnabled),
        customAccentColor = p.getInt(prefix + ConfigContract.KEY_CUSTOM_ACCENT_COLOR, defaults.customAccentColor),
        showChannel = p.getBoolean(prefix + ConfigContract.KEY_SHOW_CHANNEL, defaults.showChannel),
        backdropCapture = p.getBoolean(prefix + ConfigContract.KEY_BACKDROP_CAPTURE, defaults.backdropCapture),
        nativeBlur = p.getBoolean(prefix + ConfigContract.KEY_NATIVE_BLUR, defaults.nativeBlur),
        selectedAccent = p.getBoolean(prefix + ConfigContract.KEY_SELECTED_ACCENT, defaults.selectedAccent),
        liquidGlassEnabled = defaults.liquidGlassEnabled,
        bottomGradientBlurEnabled = defaults.bottomGradientBlurEnabled,
        outlineEnabled = defaults.outlineEnabled,
        // Global-only: the picked publish icon applies to every app that shows such a button.
        publishIcon = if (prefix == GLOBAL) {
            p.getString(GLOBAL + ConfigContract.KEY_PUBLISH_ICON, "") ?: ""
        } else {
            defaults.publishIcon
        },
    ).normalized()

    private const val QQ_PACKAGE = "com.tencent.mobileqq"
    private const val GLOBAL = "glass_"
    private const val PROVIDER_FAILURE_BACKOFF_MS = 5_000L

    /** Coalescing window for the framework preference listener (one commit mirrors many keys). */
    private const val PUSH_COALESCE_MS = 64L

    /**
     * Retry window once the provider is known to be unresolvable in this process. Long enough
     * to stop the failing IPC + framework ERROR line costing anything, short enough that a
     * module reinstall or an LSPosed re-scope is picked up without killing the target app.
     */
    private const val PROVIDER_ABSENT_BACKOFF_MS = 300_000L

    /**
     * How long an identical `[CfgDiag]` line stays suppressed. The provider is retried once per
     * [PROVIDER_FAILURE_BACKOFF_MS] window while it stays unreachable, so without this the same
     * failure text was reprinted every 5 s for the life of the target process.
     */
    private const val CFG_DIAG_REPEAT_MS = 60_000L
}
