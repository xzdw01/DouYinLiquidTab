package io.github.xzdw.dylt.hook.adapters.douyin

import io.github.xzdw.dylt.targets.AdapterSource
import io.github.xzdw.dylt.targets.HideOption
import io.github.xzdw.dylt.targets.TargetSpec

internal val targetSpec: TargetSpec = TargetSpec("douyin", "com.ss.android.ugc.aweme", "抖音", AdapterSource.DEDICATED,
            activityHints = setOf(
                "com.ss.android.ugc.aweme.splash.SplashActivity",
                "com.ss.android.ugc.aweme.main.MainActivity",
            ),
            // 40.3.0: cta > a9+ > ctu > root_view is the authoritative
            // five-cell row. The centre cell is the native publish action.
            idHints = setOf("root_view", "ctu", "cta"),
            classHints = setOf("SplashActivity", "MainActivity", "LinearLayout"),
            textHints = setOf("首页", "朋友", "消息", "我"),
            preferredSlots = 5..5,
            supportsPostButton = true,
            // 底栏菜单项隐藏 (the settings page's 底栏菜单项隐藏 switches): the four navigation
            // entries, named by their native slot index, which is the bit `GlassConfig.hiddenMask`
            // stores. Slot 2 is the publish action (`DouyinAdapter.actionOnlyIndices`), so it is
            // not offered here — it has its own 去除发布按钮 switch.
            hideOptions = listOf(
                HideOption("首页", 0),
                HideOption("朋友", 1),
                HideOption("消息", 3),
                HideOption("我", 4),
            ),
            entryLabels = listOf("首页", "朋友", "发布", "消息", "我"),
            defaultAccentColor = 0xFFFE2C55.toInt(), drawsNavigationInOptics = true, glassAccentColor = 0xFFFE2C55.toInt())
