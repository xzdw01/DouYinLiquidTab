package io.github.xzdw.dylt.config

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.github.xzdw.dylt.framework.ModuleService
import io.github.xzdw.dylt.targets.AppCatalog

enum class ColorMode(val value: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromValue(value: String?): ColorMode = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

data class ManagerSettings(
    val colorMode: ColorMode,
    val liquidGlassEnabled: Boolean,
    val bottomGradientBlurEnabled: Boolean,
    val outlineEnabled: Boolean,
    val glass: GlassConfig,
    val appEnabled: Map<String, Boolean>,
    val installedPackages: Set<String>,
    val appConfigs: Map<String, GlassConfig>,
    val appOverrides: Set<String>,
    val miMarketTabEnabled: Map<String, Boolean>,
    val appEntryLabels: Map<String, List<String>> = emptyMap(),
    /** 顶栏标签排序: tab labels the running target reported for its own title bar. */
    val appTopTabLabels: Map<String, List<String>> = emptyMap(),
)

object LocalSettings {
    private const val PREFS = "offline_glass"
    private const val GLOBAL = "glass_"
            private const val FORMAL_DEFAULTS_V1 = "formal_defaults_v1"
    private const val GLOBAL_DEFAULTS_V2 = "global_defaults_v2"
    private const val REFERENCE_BAR_DEFAULTS_V1 = "reference_bar_defaults_v1"
    private const val SETTINGS_DEFAULTS_V3 = "settings_defaults_v3"
    private const val REFERENCE_BAR_DEFAULTS_V2 = "reference_bar_defaults_v2"
    private const val REFRACTION_DEFAULTS_V1 = "refraction_defaults_v1"
    private const val MI_MARKET_TAB_PREFIX = "mi_market_tab_"
    private val MI_MARKET_TAB_KEYS = listOf("pref_key_short_play", "pref_key_mini_game")

    private fun prefs(context: Context): SharedPreferences {
        // directBootAware lets the provider start while the user is still
        // locked, in which case its context points at device-protected
        // storage (DE) — a separate offline_glass.xml from the one the
        // manager UI writes (CE). Once unlocked, always resolve to the
        // credential-protected context so provider reads and UI writes hit
        // the same file; before unlock, CE is unreadable so fall back to DE.
        val ctx = if (!context.isDeviceProtectedStorage) {
            context
        } else {
            val unlocked = runCatching {
                context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked
            }.getOrNull()
            if (unlocked == true) {
                runCatching {
                    context.createPackageContext(context.packageName, 0)
                }.getOrDefault(context)
            } else {
                context
            }
        }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Cold-start migration: copy existing settings from device-protected
        // storage (where earlier versions wrote) to the default credential-
        // protected storage this store and the framework mirror read from, so
        // the hook code in target processes always sees the latest values.
        if (!prefs.contains("_migrated_from_de")) {
            val de = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (de.all.isNotEmpty()) {
                prefs.edit().apply {
                    de.all.forEach { (k, v) ->
                        when (v) {
                            is String -> putString(k, v)
                            is Boolean -> putBoolean(k, v)
                            is Int -> putInt(k, v)
                            is Long -> putLong(k, v)
                            is Float -> putFloat(k, v)
                            is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, v as Set<String>)
                        }
                    }
                    putBoolean("_migrated_from_de", true)
                    apply()
                }
            } else {
                prefs.edit().putBoolean("_migrated_from_de", true).apply()
            }
        }
        // Undo the temporary blur-only migration once for affected installs.
        // New installs use the optical default; later manual choices still win.
        if (prefs.getBoolean("blur_only_debug_defaults_20261001", false) &&
            !prefs.getBoolean("liquid_glass_defaults_restored_20261001", false)) {
            prefs.edit()
                .putBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, true)
                .putBoolean("liquid_glass_defaults_restored_20261001", true)
                .commit()
        }
        return prefs
    }

    fun snapshot(context: Context): ManagerSettings {
        val p = prefs(context)
        val liquidGlassEnabled = p.getBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, true)
        val bottomGradientBlurEnabled = false
        val outlineEnabled = p.getBoolean(ConfigContract.KEY_OUTLINE_ENABLED, true)
        val global = readConfig(p, GLOBAL, GlassConfig()).normalized().copy(
            liquidGlassEnabled = liquidGlassEnabled,
            bottomGradientBlurEnabled = bottomGradientBlurEnabled,
            outlineEnabled = outlineEnabled,
        )
        val apps = AppCatalog.targets.associate { target ->
            target.packageName to p.getBoolean("app_${target.packageName}", true)
        }
        val installedPackages = AppCatalog.targets.mapNotNullTo(linkedSetOf()) { target ->
            target.packageName.takeIf {
                runCatching { context.packageManager.getApplicationInfo(it, 0) }.isSuccess
            }
        }
        val overrides = AppCatalog.targets.mapNotNullTo(linkedSetOf()) { target ->
            target.packageName.takeIf { p.getBoolean(overrideKey(it), false) }
        }
        val configs = AppCatalog.targets.associate { target ->
            val enabled = apps[target.packageName] != false
            val defaults = defaultsFor(target.packageName, global)
            val value = if (target.packageName in overrides) {
                readConfig(p, appPrefix(target.packageName), defaults)
            } else {
                defaults
            }
            target.packageName to value.copy(
                enabled = enabled,
                blurRadius = global.blurRadius,
                cornerRadiusPercent = 50f,
                cornerSmoothing = global.cornerSmoothing,
                barHeight = global.barHeight,
                lightAlpha = global.lightAlpha,
                darkAlpha = global.darkAlpha,
                darkBarHighlightStrength = global.darkBarHighlightStrength,
                bottomGradientBlurEnabled = false,
                publishIcon = global.publishIcon,
                // Settings-page switch: keep it global so the per-app rows agree with the master.
                hideMessageBadge = global.hideMessageBadge,
                // Same contract: 底栏菜单项隐藏 is a settings-page switch, so a stale per-app
                // override must not shadow the switches the user actually moved.
                hiddenMask = global.hiddenMask,
                // And 底栏菜单文字 is the same kind of settings-page value.
                customEntryLabels = global.customEntryLabels,
                // 顶栏参数 is the settings page's top-copy geometry, shared by every app.
                topBarMarginOffsetDp = global.topBarMarginOffsetDp,
                topBarScale = global.topBarScale,
                // 顶栏两侧按钮 / 顶栏标签数量 / 顶栏标签排序 are settings-page values as well.
                topBarHideLeadingAction = global.topBarHideLeadingAction,
                topBarHideTrailingAction = global.topBarHideTrailingAction,
                topBarTabCount = global.topBarTabCount,
                topBarTabOrder = global.topBarTabOrder,
                antiBurnIn = global.antiBurnIn,
                // 底栏显示样式 is a settings-page choice as well.
                bottomBarStyle = global.bottomBarStyle,
            )
        }
        return ManagerSettings(
            colorMode = ColorMode.fromValue(p.getString("color_mode", ColorMode.SYSTEM.value)),
            liquidGlassEnabled = liquidGlassEnabled,
            bottomGradientBlurEnabled = bottomGradientBlurEnabled,
            outlineEnabled = outlineEnabled,
            glass = global,
            appEnabled = apps,
            installedPackages = installedPackages,
            appConfigs = configs,
            appOverrides = overrides,
            miMarketTabEnabled = MI_MARKET_TAB_KEYS.associateWith { key ->
                p.getBoolean(miMarketTabKey(key), true)
            },
            appEntryLabels = AppCatalog.targets.associate { target ->
                target.packageName to runCatching {
                    val array = org.json.JSONArray(p.getString("navigation_labels_${target.packageName}", "[]"))
                    List(array.length()) { array.getString(it) }
                }.getOrDefault(emptyList())
            },
            appTopTabLabels = AppCatalog.targets.associate { target ->
                target.packageName to runCatching {
                    val array = org.json.JSONArray(p.getString(topTabLabelsKey(target.packageName), "[]"))
                    List(array.length()) { array.getString(it) }
                }.getOrDefault(emptyList())
            },
        )
    }

    fun configFor(context: Context, packageName: String): GlassConfig {
        val p = prefs(context)
        val canonicalPackage = AppCatalog.forPackage(packageName)?.packageName ?: packageName
        val liquidGlassEnabled = p.getBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, true)
        val bottomGradientBlurEnabled = false
        val outlineEnabled = p.getBoolean(ConfigContract.KEY_OUTLINE_ENABLED, true)
        val global = readConfig(p, GLOBAL, GlassConfig()).copy(
            liquidGlassEnabled = liquidGlassEnabled,
            bottomGradientBlurEnabled = bottomGradientBlurEnabled,
            outlineEnabled = outlineEnabled,
        )
        val defaults = defaultsFor(packageName, global)
        val config = if (p.getBoolean(overrideKey(canonicalPackage), false)) {
            readConfig(p, appPrefix(canonicalPackage), defaults)
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
            liquidGlassEnabled = liquidGlassEnabled,
            bottomGradientBlurEnabled = false,
            outlineEnabled = outlineEnabled,
            // Global-only like the other visual switches: the 去除发布按钮 toggle lives on the
            // settings page, and a stale per-app copy must not silently ignore it.
            showPostButton = global.showPostButton,
            // Same contract: 隐藏消息红点 is a settings-page switch.
            hideMessageBadge = global.hideMessageBadge,
            // Same contract again: 底栏菜单项隐藏 is a settings-page switch, and the mask says
            // nothing under an app prefix (see writeConfig).
            hiddenMask = global.hiddenMask,
            // 底栏菜单文字 follows the same rule.
            customEntryLabels = global.customEntryLabels,
            // 顶栏参数 too: the top-copy knobs live on the settings page only.
            topBarMarginOffsetDp = global.topBarMarginOffsetDp,
            topBarScale = global.topBarScale,
            // The top copy's two side keys, label count and label order share that rule.
            topBarHideLeadingAction = global.topBarHideLeadingAction,
            topBarHideTrailingAction = global.topBarHideTrailingAction,
            topBarTabCount = global.topBarTabCount,
            topBarTabOrder = global.topBarTabOrder,
            antiBurnIn = global.antiBurnIn,
            // 底栏显示样式 is the settings page's own choice, and it owns the derived icon-only flag.
            bottomBarStyle = global.bottomBarStyle,
            publishIcon = global.publishIcon,
        ).normalized()
    }

    fun setColorMode(context: Context, value: ColorMode) = edit(context) { putString("color_mode", value.value) }
    fun setLiquidGlassEnabled(context: Context, value: Boolean) =
        edit(context) { putBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, value) }

    /** Applies the public 1.0 defaults once, including when upgrading from a debug build. */
    fun applyFormalDefaultsOnce(context: Context) {
        val p = prefs(context)
        val edit = p.edit()
        var changed = false
        if (!p.getBoolean(FORMAL_DEFAULTS_V1, false)) {
            edit.putBoolean(FORMAL_DEFAULTS_V1, true)
                .putBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, false)
                .putBoolean(ConfigContract.KEY_OUTLINE_ENABLED, true)
            changed = true
        }
        if (!p.getBoolean(GLOBAL_DEFAULTS_V2, false)) {
            edit.putBoolean(GLOBAL_DEFAULTS_V2, true)
                .putBoolean(ConfigContract.KEY_BOTTOM_GRADIENT_BLUR_ENABLED, false)
                .putFloat(GLOBAL + ConfigContract.KEY_BLUR_RADIUS, 2f)
                .putFloat(GLOBAL + ConfigContract.KEY_CORNER_RADIUS_PERCENT, 50f)
                .putFloat(GLOBAL + ConfigContract.KEY_CORNER_SMOOTHING, -1f)
                .putFloat(GLOBAL + ConfigContract.KEY_BAR_HEIGHT, 50f)
            changed = true
        }
        if (!p.getBoolean("manager_defaults_20261002", false)) {
            edit.putBoolean("manager_defaults_20261002", true)
                .putFloat(GLOBAL + ConfigContract.KEY_DARK_BAR_HIGHLIGHT_STRENGTH, 0.5f)
            changed = true
        }
        // Bar height and label size used to carry 56dp / 11sp as the shared defaults while
        // the Douyin adapter forced 50dp / 13sp on top of them, so the settings sliders did
        // nothing. The reference values are the defaults now; a stored pair that still holds
        // the old defaults is promoted so the bar does not change appearance (and so the
        // slider's minimum is exactly the height in use).
        if (!p.getBoolean(REFERENCE_BAR_DEFAULTS_V1, false)) {
            edit.putBoolean(REFERENCE_BAR_DEFAULTS_V1, true)
            if (p.getFloat(GLOBAL + ConfigContract.KEY_BAR_HEIGHT, 50f) == 56f) {
                edit.putFloat(GLOBAL + ConfigContract.KEY_BAR_HEIGHT, 50f)
            }
            if (p.getFloat(GLOBAL + ConfigContract.KEY_TEXT_SIZE, 13f) == 11f) {
                edit.putFloat(GLOBAL + ConfigContract.KEY_TEXT_SIZE, 13f)
            }
            changed = true
        }
        // The 56dp write in GLOBAL_DEFAULTS_V2 lands in the same uncommitted editor
        // that the promotion above reads back, so on a fresh install (or after
        // clearing data) that check saw the read default (50f) instead of 56f and
        // skipped, leaving the legacy 56dp on disk. Promote installs still holding
        // it, which the flag above cannot catch because it already ran.
        if (!p.getBoolean(REFERENCE_BAR_DEFAULTS_V2, false)) {
            edit.putBoolean(REFERENCE_BAR_DEFAULTS_V2, true)
            if (p.getFloat(GLOBAL + ConfigContract.KEY_BAR_HEIGHT, 50f) == 56f) {
                edit.putFloat(GLOBAL + ConfigContract.KEY_BAR_HEIGHT, 50f)
            }
            changed = true
        }
        // 折射范围 / 折射强度 shipped with 24dp as the default while the sliders went up
        // to 40dp; the range is 0-20 with 15dp as the reference now. A stored pair still
        // holding the old default is promoted, so an install that ever touched another
        // slider does not keep a 24 that the new range would clamp to the 20dp ceiling.
        if (!p.getBoolean(REFRACTION_DEFAULTS_V1, false)) {
            edit.putBoolean(REFRACTION_DEFAULTS_V1, true)
            if (p.getFloat(GLOBAL + ConfigContract.KEY_REFRACTION_HEIGHT, 15f) == 24f) {
                edit.putFloat(GLOBAL + ConfigContract.KEY_REFRACTION_HEIGHT, 15f)
            }
            if (p.getFloat(GLOBAL + ConfigContract.KEY_REFRACTION_STRENGTH, 15f) == 24f) {
                edit.putFloat(GLOBAL + ConfigContract.KEY_REFRACTION_STRENGTH, 15f)
            }
            changed = true
        }
        // Revised page defaults: the bar ships with the liquid look on, the top-bar glass copy
        // off, and the outline as the bare thin line (no bloom glow). Written last so it also
        // overrides FORMAL_DEFAULTS_V1's earlier liquid_glass_enabled = false.
        if (!p.getBoolean(SETTINGS_DEFAULTS_V3, false)) {
            edit.putBoolean(SETTINGS_DEFAULTS_V3, true)
                .putBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, true)
                .putBoolean(GLOBAL + ConfigContract.KEY_TOP_BAR_LIQUID, false)
                .putFloat(GLOBAL + ConfigContract.KEY_BLOOM_GLOW_STRENGTH, 0f)
                .putFloat(GLOBAL + ConfigContract.KEY_BLOOM_GLOW_DP, 0f)
            changed = true
        }
        if (changed) edit.apply()
    }

    fun setOutlineEnabled(context: Context, value: Boolean) =
        edit(context) { putBoolean(ConfigContract.KEY_OUTLINE_ENABLED, value) }
    fun setAppEnabled(context: Context, packageName: String, value: Boolean) =
        edit(context) { putBoolean("app_$packageName", value) }

    fun miMarketTabEnabled(context: Context, key: String): Boolean =
        prefs(context).getBoolean(miMarketTabKey(key), true)

    fun setMiMarketTabEnabled(context: Context, key: String, value: Boolean) {
        if (key !in MI_MARKET_TAB_KEYS) return
        edit(context) { putBoolean(miMarketTabKey(key), value) }
    }

    fun setGlassConfig(context: Context, value: GlassConfig) {
        android.util.Log.i(
            "LiquidTabUI",
            "setGlassConfig pkg=${context.packageName} blur=${value.blurRadius}",
        )
        edit(context) {
            writeConfig(GLOBAL, value.normalized())
        }
    }

    fun setAppConfig(context: Context, packageName: String, value: GlassConfig) = edit(context) {
        putBoolean(overrideKey(packageName), true)
        writeConfig(appPrefix(packageName), value.normalized())
    }

    fun clearAppConfig(context: Context, packageName: String) = edit(context) {
        putBoolean(overrideKey(packageName), false)
    }

    /** Publishes the current complete configuration as one apply revision. */
    fun applyChanges(context: Context) = edit(context) { }

    private fun readConfig(p: SharedPreferences, prefix: String, defaults: GlassConfig): GlassConfig = GlassConfig(
        // Global-only knobs, exactly like controlDrop: they live on the settings page,
        // which writes the GLOBAL prefix. Reading them from an app prefix let a stale
        // per-app override (written when the per-app page was last saved) shadow the switch —
        // the toggle read back as false in the hooked app and looked dead.
        controlDrop = if (prefix == GLOBAL) p.getBoolean(GLOBAL + ConfigContract.KEY_CONTROL_DROP, false) else defaults.controlDrop,
        // 底栏下边距 is a settings-page slider as well: read it from the global prefix only, so no
        // stale per-app copy can shadow the value the user moved.
        bottomMarginOffsetDp = if (prefix == GLOBAL) {
            p.getFloat(GLOBAL + ConfigContract.KEY_BOTTOM_MARGIN_OFFSET, defaults.bottomMarginOffsetDp)
        } else {
            defaults.bottomMarginOffsetDp
        },
        topBarLiquidGlass = if (prefix == GLOBAL) {
            p.getBoolean(GLOBAL + ConfigContract.KEY_TOP_BAR_LIQUID, false)
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
        // 顶栏两侧按钮 / 顶栏标签数量 / 顶栏标签排序: same settings-page-only rule.
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
        // Global-only as well (settings-page knobs, no per-app UI): a stale app-prefix value
        // must not shadow the slider the user actually moved.
        glassAlphaScale = if (prefix == GLOBAL) p.getFloat(GLOBAL + ConfigContract.KEY_GLASS_ALPHA_SCALE, defaults.glassAlphaScale) else defaults.glassAlphaScale,
        glassSaturation = if (prefix == GLOBAL) p.getFloat(GLOBAL + ConfigContract.KEY_GLASS_SATURATION, defaults.glassSaturation) else defaults.glassSaturation,
        refractionHeightDp = if (prefix == GLOBAL) p.getFloat(GLOBAL + ConfigContract.KEY_REFRACTION_HEIGHT, defaults.refractionHeightDp) else defaults.refractionHeightDp,
        refractionStrengthDp = if (prefix == GLOBAL) p.getFloat(GLOBAL + ConfigContract.KEY_REFRACTION_STRENGTH, defaults.refractionStrengthDp) else defaults.refractionStrengthDp,
        bloomGlowStrength = if (prefix == GLOBAL) p.getFloat(GLOBAL + ConfigContract.KEY_BLOOM_GLOW_STRENGTH, defaults.bloomGlowStrength) else defaults.bloomGlowStrength,
        bloomGlowDp = if (prefix == GLOBAL) p.getFloat(GLOBAL + ConfigContract.KEY_BLOOM_GLOW_DP, defaults.bloomGlowDp) else defaults.bloomGlowDp,

        iconScale = p.getFloat(prefix + ConfigContract.KEY_ICON_SCALE, defaults.iconScale),
        textSize = p.getFloat(prefix + ConfigContract.KEY_TEXT_SIZE, defaults.textSize),
        iconOnly = p.getBoolean(prefix + ConfigContract.KEY_ICON_ONLY, defaults.iconOnly),
        // 底栏显示样式: a settings-page choice, and the source of truth for `iconOnly` — normalized()
        // re-derives that flag from it, so the per-prefix read above can only ever carry an obsolete
        // copy of an older build's value.
        bottomBarStyle = if (prefix == GLOBAL) {
            p.getInt(GLOBAL + ConfigContract.KEY_BOTTOM_BAR_STYLE, defaults.bottomBarStyle)
        } else {
            defaults.bottomBarStyle
        },
        // Global-only like the other settings-page switches: the 底栏菜单项隐藏 switches write
        // the GLOBAL prefix, and an app prefix must not carry a second, shadowing mask.
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
        // 防烧屏 is a settings-page switch, so an app prefix must not shadow it.
        antiBurnIn = if (prefix == GLOBAL) {
            p.getBoolean(GLOBAL + ConfigContract.KEY_ANTI_BURN_IN, defaults.antiBurnIn)
        } else {
            defaults.antiBurnIn
        },
    ).normalized()

    private fun SharedPreferences.Editor.writeConfig(prefix: String, value: GlassConfig) {
        if (prefix == GLOBAL) putBoolean(prefix + ConfigContract.KEY_TOP_BAR_LIQUID, value.topBarLiquidGlass)
        // 底栏下边距 shares the top bar's rule: settings-page only, so never written under an app
        // prefix (see readConfig).
        if (prefix == GLOBAL) putFloat(prefix + ConfigContract.KEY_BOTTOM_MARGIN_OFFSET, value.bottomMarginOffsetDp)
        putInt(prefix + ConfigContract.KEY_THEME_MODE, value.themeMode)
        putFloat(prefix + ConfigContract.KEY_TAB_WIDTH, value.tabWidth)
        putFloat(prefix + ConfigContract.KEY_BOTTOM_PADDING, value.bottomPadding)
        putFloat(prefix + ConfigContract.KEY_BLUR_RADIUS, value.blurRadius)
        putFloat(prefix + ConfigContract.KEY_CORNER_RADIUS_PERCENT, value.cornerRadiusPercent)
        putFloat(prefix + ConfigContract.KEY_CORNER_SMOOTHING, value.cornerSmoothing)
        putFloat(prefix + ConfigContract.KEY_BAR_HEIGHT, value.barHeight)
        putFloat(prefix + ConfigContract.KEY_LIGHT_ALPHA, value.lightAlpha)
        putFloat(prefix + ConfigContract.KEY_DARK_ALPHA, value.darkAlpha)
        putFloat(prefix + ConfigContract.KEY_DARK_BAR_HIGHLIGHT_STRENGTH, value.darkBarHighlightStrength)
        // Global-only knobs: never written under an app prefix, so no stale per-app copy can
        // exist to shadow them (see readConfig).
        if (prefix == GLOBAL) {
            putFloat(prefix + ConfigContract.KEY_GLASS_ALPHA_SCALE, value.glassAlphaScale)
            putFloat(prefix + ConfigContract.KEY_GLASS_SATURATION, value.glassSaturation)
            putFloat(prefix + ConfigContract.KEY_REFRACTION_HEIGHT, value.refractionHeightDp)
            putFloat(prefix + ConfigContract.KEY_REFRACTION_STRENGTH, value.refractionStrengthDp)
            putFloat(prefix + ConfigContract.KEY_BLOOM_GLOW_STRENGTH, value.bloomGlowStrength)
            putFloat(prefix + ConfigContract.KEY_BLOOM_GLOW_DP, value.bloomGlowDp)

            putBoolean(prefix + ConfigContract.KEY_CONTROL_DROP, value.controlDrop)
            putBoolean(prefix + ConfigContract.KEY_ANTI_BURN_IN, value.antiBurnIn)
            putString(prefix + ConfigContract.KEY_PUBLISH_ICON, value.publishIcon)
            // 底栏菜单项隐藏 is global-only too: writing the mask under an app prefix used to
            // leave a stale copy that shadowed the settings page (see readConfig).
            putInt(prefix + ConfigContract.KEY_HIDDEN_MASK, value.hiddenMask)
            // 底栏菜单文字 rides along with it.
            putString(prefix + ConfigContract.KEY_CUSTOM_ENTRY_LABELS, value.customEntryLabels)
            // 顶栏参数: the top-copy knobs share the top bar switch's rule just above. The copy's
            // height has no key — it follows Douyin's own title row.
            putFloat(prefix + ConfigContract.KEY_TOP_BAR_MARGIN_OFFSET, value.topBarMarginOffsetDp)
            putFloat(prefix + ConfigContract.KEY_TOP_BAR_SCALE, value.topBarScale)
            // 顶栏两侧按钮 / 顶栏标签数量 / 顶栏标签排序: the settings page owns them, so they are
            // never written under an app prefix either.
            putBoolean(prefix + ConfigContract.KEY_TOP_BAR_HIDE_LEADING, value.topBarHideLeadingAction)
            putBoolean(prefix + ConfigContract.KEY_TOP_BAR_HIDE_TRAILING, value.topBarHideTrailingAction)
            putInt(prefix + ConfigContract.KEY_TOP_BAR_TAB_COUNT, value.topBarTabCount)
            putString(prefix + ConfigContract.KEY_TOP_BAR_TAB_ORDER, value.topBarTabOrder)
            // 底栏显示样式，和它派生出的仅图标标记（iconOnly 由 normalized() 重新推导）。
            putInt(prefix + ConfigContract.KEY_BOTTOM_BAR_STYLE, value.bottomBarStyle)
        }
        putFloat(prefix + ConfigContract.KEY_ICON_SCALE, value.iconScale)
        putFloat(prefix + ConfigContract.KEY_TEXT_SIZE, value.textSize)
        putBoolean(prefix + ConfigContract.KEY_ICON_ONLY, value.iconOnly)
        putBoolean(prefix + ConfigContract.KEY_HIDE_MESSAGE_BADGE, value.hideMessageBadge)
        putBoolean(prefix + ConfigContract.KEY_SHOW_POST, value.showPostButton)
        putBoolean(prefix + ConfigContract.KEY_CUSTOM_ACCENT_ENABLED, value.customAccentEnabled)
        putInt(prefix + ConfigContract.KEY_CUSTOM_ACCENT_COLOR, value.customAccentColor)
        putBoolean(prefix + ConfigContract.KEY_SHOW_CHANNEL, value.showChannel)
        putBoolean(prefix + ConfigContract.KEY_BACKDROP_CAPTURE, value.backdropCapture)
        putBoolean(prefix + ConfigContract.KEY_NATIVE_BLUR, value.nativeBlur)
        putBoolean(prefix + ConfigContract.KEY_SELECTED_ACCENT, value.selectedAccent)
    }

    private inline fun edit(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        val now = System.currentTimeMillis()
        prefs(context).edit().apply(block).putLong("revision", now).commit()
        context.contentResolver.notifyChange(ConfigContract.URI, null)
        // The framework's remote preferences are what a hooked process can
        // *listen* on, so mirror the committed store there once per change
        // (see HookConfigReader.startPreferencePush).
        mirrorToFrameworkPreferences(context)
        // A provider notification does not wake an otherwise idle injected
        // View, so polling alone cannot make a released slider visible in all
        // running targets. Push one complete snapshot per target only after
        // the setting commit; slider drag frames remain local to the manager.
        AppCatalog.targets.forEach { publishConfig(context, it.packageName) }
    }

    /**
     * Copies the whole settings store into the framework's remote preferences.
     *
     * The group is per module and per user, served by the framework daemon, and
     * the hooked side reads the same flat key space from it, so this is a channel
     * swap rather than a format change. It is a no-op until the module app has
     * the framework service.
     */
    private fun mirrorToFrameworkPreferences(context: Context) {
        val remote = ModuleService.remotePreferences() ?: return
        val source = prefs(context).all
        runCatching {
            val editor = remote.edit()
            source.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
                }
            }
            editor.commit()
        }
    }

    fun publishConfig(context: Context, packageName: String) {
        val spec = AppCatalog.forPackage(packageName) ?: return
        val targets = buildList {
            add(spec.packageName)
            addAll(spec.aliasPackages)
        }
        targets.forEach { targetPackage ->
            val config = configFor(context, targetPackage).copy(systemDark = isSystemDarkNow())
            context.sendBroadcast(
                Intent(ConfigContract.ACTION_CONFIG_RESPONSE)
                    .setPackage(targetPackage)
                    .putExtras(config.toBundle(targetPackage)),
            )
        }
    }

    fun reportNavigationLabels(context: Context, packageName: String, labels: List<String>) {
        if (AppCatalog.forPackage(packageName) == null || labels.size !in 2..7) return
        val value = org.json.JSONArray(labels).toString()
        val p = prefs(context)
        val key = "navigation_labels_$packageName"
        if (p.getString(key, null) == value) return
        p.edit().putString(key, value).apply()
        context.contentResolver.notifyChange(ConfigContract.URI, null)
    }

    /**
     * 顶栏标签排序: records the labels of the target's own top title bar so the reorder page can
     * list the tabs that actually exist. The manager UI only reads this; the value is never
     * written under a preference prefix and never affects the hook side.
     */
    fun reportTopTabLabels(context: Context, packageName: String, labels: List<String>) {
        if (AppCatalog.forPackage(packageName) == null) return
        val cleaned = labels
            .map { it.trim().take(GlassConfig.TOP_BAR_TAB_ORDER_MAX_LENGTH) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(GlassConfig.TOP_BAR_TAB_ORDER_MAX_ENTRIES)
        if (cleaned.isEmpty()) return
        val value = org.json.JSONArray(cleaned).toString()
        val p = prefs(context)
        val key = topTabLabelsKey(packageName)
        if (p.getString(key, null) == value) return
        p.edit().putString(key, value).apply()
        context.contentResolver.notifyChange(ConfigContract.URI, null)
    }

    private fun topTabLabelsKey(packageName: String) = "top_tab_labels_$packageName"

    private fun isSystemDarkNow(): Boolean {
        val mask = android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val night = android.content.res.Configuration.UI_MODE_NIGHT_YES
        return android.content.res.Resources.getSystem().configuration.uiMode and mask == night
    }

    private fun appPrefix(packageName: String) = "appcfg_${packageName}_"
    private fun overrideKey(packageName: String) = "appcfg_${packageName}_override"
    private fun miMarketTabKey(key: String) = MI_MARKET_TAB_PREFIX + key

    private fun defaultsFor(packageName: String, global: GlassConfig): GlassConfig {
        val spec = AppCatalog.forPackage(packageName) ?: return global
        return global.copy(
            // The spec's own icon-only default is gone with 底栏显示样式: the style is a
            // settings-page choice now and normalized() derives `iconOnly` from it, so a per-app
            // default here could not survive anyway.
            customAccentColor = spec.defaultAccentColor,
        )
    }

}
