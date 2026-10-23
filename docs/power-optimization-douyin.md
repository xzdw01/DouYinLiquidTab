# 抖音作用域功耗优化分析（LiquidTab 1.7.3）

> 分析范围：`app/src/main/java/io/github/xzdw/dylt/` 全部源码，重点为抖音适配层
> （`hook/adapters/douyin/`，15 个文件约 8000 行非空代码，逐文件通读）与共享宿主
> `hook/GlassHostLayout.kt`（约 8000 行非空代码）中抖音实际会走到的每帧 / 每 pass 路径
> （逐热点函数阅读，并用检索确认没有漏掉定时器、监听器和树遍历）。
> 方法：静态阅读 + 全仓库检索所有 `Choreographer` / `Handler.postDelayed` /
> `OnPreDrawListener` / `Log` / `Xposed.log` / `foreach` / BFS-DFS 调用点，再按调用链推算频率。
> 本文所有行号均对应当前工作区源码；项目注释里已有的设备实测值会标注“项目实测”。
> 第一批改动已于 2026-10-19 实施并在设备上验证，见 0.1；其余条目仍是待办。

---

## 0.1 已实施与实测（2026-10-19）

已实施（第一批）：**A1、A2、B1、B2、B3、B5、B6**；第二批：**D1、D2、D3、D4、B4、B7、B8、C2、C6**；
第三批：**顶栏 lens 场景复用、顶栏菜单按变化重录、渲染器分配、B9**。
外加两项让空闲重绘闭环完整的配套改动：

- 选中红点值变化时补一次 `postInvalidateOnAnimation()`（`readNavigationBadge`）——空闲后没有别的东西会重绘红点，不补的话新消息到达时角标会停住；
- `setNativeBarTouchable` 保留 500 ms 安全网重放，防止 Douyin 原地重建行子节点后重新带回不可见可点区域。

设备：Android 17 / 抖音 40.7.0 / 1272x2772 / 模块 1.7.3（debug，LSPosed 2.x）。
测量：`adb shell dumpsys gfxinfo com.ss.android.ugc.aweme reset` 后等待固定窗口读取
`Total frames rendered`；玻璃区域用两次 `screencap` 的像素差确认是否还在重绘。

| 场景 | 改动前 | 改动后 |
|---|---|---|
| 消息页静止（底栏可见） | 560 帧 / 10 s（~56 fps） | 1–2 帧 / 10 s；30 s 窗口 9 帧（~0.3 fps） |
| 消息页静止 CPU（`top` 采样） | 未采（持续满帧重绘） | app ≈ 6% |
| feed 视频播放 | 564 帧 / 10 s，janky 0.35% | 566 帧 / 10 s，janky 0.53% |
| feed 播放时玻璃区域像素差（0.7 s 两帧） | — | 有变化（视频仍然实时反射） |
| 消息页静止玻璃区域像素差（0.7 s 两帧） | — | 0.0（完全不再重绘） |
| 滑动后玻璃区域 | — | 与内容一起大幅变化（不是冻结帧） |

功能回归：底栏四入口切换、选中胶囊/墨迹、按住缩放动画、顶栏 关注/推荐、顶栏标签菜单、
消息页红点、聊天页（底栏隐藏）、个人主页（底栏隐藏后返回）、feed 相关搜索 pill 抬升，
均正常；logcat 无 `AndroidRuntime`/`douyin adjust failed`/NPE。

**不要回退的三件事**（少任何一件空闲满帧会回来）：

1. `SpringFloat.animateTo` 的“目标未变且已静止”短路；
2. `refreshPressSlotScales` 只在目标变化时重臂 + 缓存 `asList()`；
3. spring 更新回调只在值真正变化时 `invalidate()`。

**装机后修的一个回归（2026-10-19 晚）**：B2 最初把整个导航栏沉浸检查节流到 250 ms，用户反馈
切视频时底部手势横条会闪一下黑色。临时插桩确认：抖音在部分 feed 切换时把
`window.navigationBarColor` 重置为 `0xFF1E1E1E`（5 次滑动里出现 3 次，flags 本来就对、contrast
为 false），旧节流让这块黑底停留最多 250 ms。现在改成：每次调用只做几个属性比较，发现被重置就
在下一次 preDraw 里立即 `setNavigationBarImmersion`；只有反射式的 HyperOS force-immersive
修复保留 500 ms 节流。修完再次实测空闲页 2 帧 / 10 s，没有回退。

**第二批（2026-10-20 凌晨）已实施：D1、D2、D3、D4、B4、B7、B8、C2、C6。**
同一台设备、同一测量方法（固定窗口读取 `/proc/<pid>/stat` 的 utime+stime，按 100 Hz 折算）：

| 场景 | 第一批后（改前） | 第二批后 |
|---|---|---|
| feed 播放 60 s | 117% | 101.7% / 108.3%（两次采样） |
| feed 滑动 ~55 s | 114.2% | 104.9% |
| 消息页静止 | 1–2 帧 / 10 s | 1 帧 / 10 s |

要点：

- `reportTopTabs` 的 2 s 计时器在“标签未变化”分支不前进，导致 2 s 后每帧跑一次全树
  `allTabs()`；现在计时器在去重判断之前前进。
- 顶栏 `model()` 增加廉价 `modelSignature` 复用：覆盖 row/strip/viewport 身份、每个 cell 的
  身份/尺寸/可见性/位置/选中标志、label 文本/颜色/字号/alpha、plan 和缓存的角标身份。模型用
  **弱引用**缓存（state 持有活模型，finder 不能钉住 Activity）；`labels` 改为存储字段。
- `Class.getSimpleName()` 按 Class 缓存；`hideNativeTitleBar` 在行/子节点数变化时立即重扫（保留
  500 ms 安全网）；`corners()` 增加行子节点数作为失效条件。
- 页面控制器：`isFeaturedFeedRoot` 只在 `idName == "dir"` 时调用；`isVideoControlsRoot` 复用
  walk 已算好的 `idName`；相关搜索检查只对带文本的 view 构造 String。
- 广告分类负 TTL 250 ms → 1 s；`looksLikeAdvertisementLabel` 直接比较 CharSequence，walk 不再
  每节点分配 Pair 和 String。
- `suppressNativeBottomChrome` 同帧 2–3 次调用合并为 1 次；纹理选择日志每进程只打一次；
  CfgDiag 的 `$packageName` 转义 bug 已修。

**新增不变量**：顶栏 model 现在是缓存复用的，任何 model 依赖但 `modelSignature` 没覆盖的状态
（例如原生图标 drawable）都会冻结到行变化为止。要加新的绘制依赖，必须同时把它加进签名。

功能回归（第二批后）：顶栏 关注/推荐 切换与文案颜色、页面切换后顶栏标签（直播/团购）、顶栏菜单、
底栏四入口、按住缩放、消息页红点、feed/团购页布局、滑动切换均正常；logcat 无
`AndroidRuntime`/`douyin adjust failed`。

**第三批（2026-10-20 凌晨）已实施：顶栏 lens 场景复用、顶栏菜单按变化重录、渲染器分配、B9。**

- 顶栏组合场景：`DouyinTopBarState.reuseCombinedIndicatorScene` 静止时复用，带 **1 s 年龄上限**
  （场景里含隐藏的原生标题行，图标换 drawable 不改几何）；`draw()` 在选中索引或标签集合变化时
  `invalidate()`。临时计数器实测：播放中从每帧 1 次（~60/s）降到 **5 次 / 5 s（~1/s）**。
- 顶栏菜单：场景节点只在首次 / 尺寸变化 / 每秒重录一次；base 层只在视频 epoch、窗口副本身份、
  场景尺寸/原点、captureRect 变化时重录。帧泵保留，因为半透明面板必须按帧合成后面的实时视频。
- 渲染器：`Paint.fontMetrics` 改为复用的 `getFontMetrics(fm)`；顶栏两个常量标签色、底栏四个
  常量 entry 色改成每帧只算一次。
- `DOUYIN_CONTAINER_PROBE_TTL_MS` 384 ms → 768 ms。
- 消息页静止复测：**0 帧 / 10 s**。
- 说明：feed 播放时的整机 CPU 由视频解码主导，样本波动大（本批前后落在 101.7–117% 区间），
  所以顶栏场景的收益用“每分钟重录次数”验证，而不是看整机 CPU。
- 回归：顶栏 关注/推荐 切换、菜单打开/选择团购、返回 feed、底栏、滑动、按住均正常；
  logcat 无 `AndroidRuntime`/`douyin adjust failed`。

---

## 0.2 黑边视频栏后景静止检测（2026-10-21）

问题：抖音视频比例不固定，部分视频上下留黑边；播放器仍在产帧，栏后景却是静态黑边，旧的视频泵仍按帧重绘。

实现：

- 新增 `SurfaceTexture.setDefaultBufferSize` hook，按 SurfaceTexture 记录视频真实解码尺寸（抖音会在第二个 surface 上预加载下一条，全局“最后一个尺寸”会每帧来回跳）。
- `DouyinVideoSampler` 每 600 ms 用视频尺寸与 TextureView 屏幕矩形算 fit-centre 内容矩形；栏矩形完全在内容外才进入候选。
- 候选后用 32×8 `PixelCopy` 采样栏靠内容一侧的**整条栏宽**窗口条（底栏取上方、顶栏取下方）。判定：
  - 纯黑且均匀 → 立即候选（快速路径）；
  - 非黑但和上一次采样平均通道差 ≤ 2.5，连续两次 → 候选（静态彩色/静态模糊填充）。
  - 动态模糊/裁切视频两次差 10–56，stable=false，不会停。
- 历史样本用布尔标记，而不是 `-1` 像素哨兵（不透明 ARGB 是负数，原判断永远 false，时间路径从未生效）。
- `over-content` / `fills-height` 分支只清候选，不清历史；栏在内容边界会隔帧在 outside/over 之间跳，清历史会让稳定路径永远走不到。
- 确认后 `barStripStatic = true`；`videoSourceIsStatic` 命中，`videoWantsRepaint` 返回 false，`requestVideoBackdrop` 停止采样；栏自身按压/拖动/回弹仍按帧绘制。每 2.5 s 复核但**不先置 false**；页面/视频/尺寸变化立即复位。
- 静止后 host 的 `videoFrameWatcher` 也停掉：新增 `barStripStatic` 单独钩子（不混入 paused 检测，避免暂停全屏视频恢复时无人唤醒），strip watcher 变化时 invalidate，`onDraw` 再启动。静止时从每帧 Choreographer 回调降到 2.5 s 复核。
- strip watcher 在栏位于内容内时退避到 1.5 s，后台/脱离 2 s；600 ms 只用于候选确认。

实测（调试版）：

- 16:9（1272×715）黑边视频：底栏区域 1 s 像素差 **0.00**，内容区 45.7；底栏泵计数 0；全屏视频保持 ~30/s。
- 暂停视频（边缘是彩色画面、非黑）：`diff=0 → stable=true → streak=2 → candidate=true → static=true`，验证彩色静态路径。
- 动态填充/裁切视频：`diff=10–56`，`stable=false`，不进入静态，不会冻结。

已知限制：fit-centre 假设；整条栏宽的采样带里如果有字幕/控件，会判不稳定而 miss（安全）；动态填充永不冻结；顶/底栏独立判断。功能探针与 `[BarStrip]` 日志已从源码移除。

## 0. 一页结论

| 优先级 | 问题 | 位置 | 频率 | 性质 |
|---|---|---|---|---|
| **P0** | 空闲时玻璃仍在按显示刷新率满帧重绘：`refreshPressSlotScales()` 每帧对每个槽位 `animateTo()`，spring 回调里无条件 `invalidate()` | `GlassHostLayout.kt:4423-4443`、`:14369-14383`、`:14415-14467`、`:6514-6558` | 每帧（窗口帧率请求上限 60 Hz），视频暂停/静态页也一样 | 常数功耗，且让 `videoWantsRepaint` 的“按视频帧率重绘”优化完全失效 |
| **P0** | 选中胶囊的 lens 场景每帧整块重录：`reuseCombinedIndicatorScene=false` ⇒ `combinedNeedsRecord` 恒为 true | `GlassHostLayout.kt:8488-8560`、`DouyinNavigationState.kt:193` | 每帧 1 次全屏尺寸 display list 录制 + 2 次 RenderEffect 绘制 | GPU/CPU 双高 |
| **P1** | 每帧重复的宿主前奏：`ensureNavigationBarImmersion()` 每帧 2 次（监听器 1 次 + `PageController.update()` 在 96 ms 节流之前 1 次）、`setNativeBarTouchable()` 全子树递归、`suppressNativeBottomChrome()` ×3、祖先链扫描 ×3 | `GlassHostLayout.kt:2286-2302`、`:2004`、`:7115-7134`、`DouyinPageController.kt:245` | 每帧，底栏宿主 | 纯浪费的 UI 线程时间（含同步块 + 反射） |
| **P1** | 页面控制器每 96 ms 全树遍历（上限 8192 view），逐 view 取 `TextView.text.toString()` 并做 4 次 `contains`，同一 view 重复查 2-3 次资源名，标题栏识别逐 view 反射 `simpleName` | `DouyinPageController.kt:430-698`、`:442`、`:486`、`:607`、`:646-654` | 10.4 次/秒 | UI 线程 CPU 与 GC 的主要来源之一 |
| **P1** | 视频采样：fallback 路径每 16 ms 一次 SurfaceControl 回读；原生纹理路径每帧持锁校验纹理 + 每帧 `Handler.removeCallbacks/postDelayed`；`videoSourceIsStatic` 写了但**从未被调用**，暂停视频不会降频 | `DouyinVideoSampler.kt:295-528`、`:312`、`:353-359`、`:827`；`DouyinPlayerTextureHooks.kt:318-349`；`DouyinNavigationState.kt:177` | 62 次/秒（回读）或每帧（校验） | GPU 回读 + 非 vsync 唤醒 |
| **P1** | 内存分配密集：每帧 3-4 个 `AdapterNavigationFrame`、每个都分配 `enabledNavigationIndices()` 列表和 `asList()` 包装；每帧 2-3 个 `AppVideoBackdrop`；draw 路径多次 `Paint.fontMetrics` | `GlassHostLayout.kt:6538/6548/8270`、`DouyinNavigationState.kt:210-216`、`DouyinNavigationRenderer.kt:148-155/165/449` | 每帧 | GC 压力 |
| **P1** | 日志：`press_log.txt` 里的 `GlassPress` 每帧 3 条已**不存在于当前源码/当前 APK**；发布版诊断总开关为 false。仍有无门控的 `Xposed.log`（= Binder 往返 + logcat），最频繁的一条是每创建一次 feed 播放器输出一次 | `DouyinPlayerTextureHooks.kt:124`、`DouyinTopBarState.kt:554-556`、`GlassHostLayout.kt:5847-5859` | 每条视频 / 每次状态变化 | 见第 8 节 |
| **P2** | 顶栏（需开启“顶栏液态玻璃”）：`refresh()` 每帧重建 model；`reportTopTabs()` 的 2 s 节流在“标签未变化”分支**不更新**计时器，2 s 后每帧全树 `allTabs()`；`corners()` 每秒重扫 600 节点；`hideNativeTitleBar()` 每 500 ms 递归整行 | `DouyinTopBarState.kt:442-514`、`:521-532`；`DouyinTopBar.kt:182-199`、`:630-641` | 每帧 / 1-2 次每秒 | 只在顶栏开启时出现，但一旦开启即为常数开销 |
| **P2** | 顶栏标签菜单打开期间 `postInvalidateOnAnimation()` 永不停，且每帧重录整个场景 | `DouyinTopTabMenu.kt:739`、`:678-681` | 菜单打开期间每帧 | 交互期功耗 |
| **P2** | 装饰性描边让 50 Hz 旋转矢量传感器常开 | `GlassHostLayout.kt:1320-1334`、`:401-471`（`SENSOR_DELAY_GAME`） | 50 次/秒 | 传感器功耗 |

**建议先做 P0 两项**：它们决定“不播放视频时玻璃还在不在耗电”，且改动小、风险低、可快速用
`GlassPerf` 验证。之后再做 P1 的“宿主前奏合并”和“页面控制器自适应降频”。

按你提出的三类问题对应：

- **大量频繁无用的遍历** → 第 3.4 节（每帧重复的祖先链/可见性探测）、第 5 节（96 ms 全树 + 子遍历）、
  第 7.2/7.3 节（顶栏 2 s 后每帧全树、每秒 600 节点重扫）。
- **大量频繁无用的日志** → 第 8 节。结论先说：`press_log.txt` 里的 `GlassPress` 在当前源码里
  **已经不存在**，那份日志来自旧版 APK；当前发布版诊断总开关是关的，剩下的是少量无门控的状态日志，
  最频繁的一条是“每创建一个 feed 播放器一条”。 
- **大量频繁重复运行的代码** → 第 2 节（spring 每帧重臂导致满帧重绘）、第 3 节（每帧重复的宿主前奏）、
  第 4 节（lens 场景每帧重录）、第 9.1 节（每帧 3-4 次 frame 构建与分配）。

---

## 1. 运行模型：抖音进程里到底每秒在跑什么

作用域只有 `com.ss.android.ugc.aweme`（`META-INF/xposed/scope.list`），适配目录也只有
`hook/adapters/douyin/`（`TargetSpecRegistry.kt`）。抖音进程内的宿主有两种：

- **底栏宿主**（默认开启，`liquidGlassEnabled=true`）：挂在抖音原生五格导航行所在的内容帧里。
- **顶栏宿主**（可选，`topBarLiquidGlass=false` 时不存在）：同一套 `GlassHostLayout` 的第二份实例。

一次屏幕刷新（`DouyinAdapter.requestedFrameRateMax=60f` 通过 `setRequestedFrameRate` 把窗口帧率请求上限设为 60 Hz）里，底栏宿主会做：

1. `sourceVisibilityListener`（`GlassHostLayout.kt:2272`，`addOnPreDrawListener` 于 `:2512`）
   - `maybeReloadConfig()`（有 push 通道时 60 s 一次）
   - `context.findActivity()` + `GlassInstaller.ensureNavigationBarImmersion()`（**每帧**，`:2286-2302`）
   - 遍历 `suppressedOriginalChrome`
   - `refreshNavigationSource()`（抖音未覆写，默认 false）
   - `suppressNativeQqBottomChrome()`（非 QQ 立即返回）
   - `stabilizeLiftedHostPosition()`（`hostLiftDp` 为 null，立即返回）
   - `appNavigationState.onHostPreDraw()` → `DouyinPageController.update()`（内部 96 ms 节流，
     但**节流点之前的代码每帧都跑**，见 3.1）
   - `suppressNativeBottomChrome()` ×2 + `appNativeChromeController.update()`（第 3 次）
   - `releaseAncestorClipping()`（向上 ≤12 层）
   - `currentPageAllowsNavigation()` → `hostPageAllowsNavigation()` → 10 层祖先链 + `getGlobalVisibleRect`
   - `applyBarVisibility()` → `setNativeBarTouchable()` 递归整个原生行
2. `captureListener`（`installCaptureLoop()`，`:12426-12588`）
   - `updateDetectedContentTheme()`（500 ms 一次）
   - `requestVideoBackdrop()` → `DouyinVideoSampler.request()`（每帧）
   - `prepareLiveBackdropScene()`（每帧，`forceCompositorFrames=true` 时每帧重录）
3. `onDraw` → `drawGlassPanel()`（模糊 + 折射 shader + 描边）
4. `dispatchDraw` → `drawSelectionWithFade()` + `DouyinNavigationRenderer` 两层绘制；
   期间构建 **3-4 个** `AdapterNavigationFrame`（`:3952`、`:3969`、`:6510`、`:8556`）
5. `videoFrameWatcher`（Choreographer，`:3359-3369`）每帧比较一次 `SurfaceTexture` 时间戳
6. 页面控制器每 96 ms 一次全树扫描（`DouyinPageController.kt:430`）
7. 传感器 `RotationVector` 50 Hz（`:461`）
8. 配置安全轮询 60 s 一次（`CONFIG_PUSH_SAFETY_INTERVAL_MS`，`:13804`）
9. 防烧屏位移 180 s 一次（`:13741`）

**关键观察**：第 3、4 步的绘制本应按“视频是否有新帧”来触发（`videoWantsRepaint()`，`:3403-3432`），
但第 4 步里的 spring 重臂（见第 2 节）让宿主自己每帧 `invalidate()`，于是“按视频帧率重绘”的
优化被完全绕过。

---

## 2. P0：空闲时仍在满帧重绘（spring 每帧重臂）

### 2.1 证据链

`dispatchDraw` 里抖音走 `foregroundNavigationOverSelection=true` 分支：

```kotlin
// GlassHostLayout.kt:3943-3957
drawSelectionWithFade(canvas, isDarkGlass())
val customForeground = appNavigationState?.drawForeground(
    canvas,
    adapterNavigationFrame(1f),          // ← 第 1 个 frame
    foregroundNavigationClipPath(),
) { drawNavigationSource(canvas) } == true   // ← 内部又构建第 2 个 frame (:6510)
...
appNavigationState?.drawSelectionInk(canvas, adapterNavigationFrame(1f), inLensScene = false) // ← 第 3 个
```

每个 `AdapterNavigationFrame` 都会调用：

```kotlin
// GlassHostLayout.kt:6514-6558
pressSlotScales = refreshPressSlotScales(),   // ← 每次构建都调用
```

而 `refreshPressSlotScales()` 对每个槽位无条件重臂 spring：

```kotlin
// GlassHostLayout.kt:4423-4443
val count = visualSlotCount().coerceAtLeast(1)
...
val pressed = !topBar && (touching || pressProgress > 0.001f)
for (index in 0 until count) {
    val covered = pressed && abs(index - indicatorPosition) <= 0.95f
    pressSlotSprings[index].animateTo(if (covered) 1.2f else 1f)   // 目标没变也调用
}
return pressSlotScales.asList()   // 每次一个新 List 包装
```

`SpringFloat.animateTo` 没有“目标未变且已静止就直接返回”的短路：

```kotlin
// GlassHostLayout.kt:14369-14383
fun animateTo(newTarget: Float) {
    target = newTarget
    if (!running) {
        running = true
        lastFrameNanos = 0L
        choreographer.postFrameCallback(this)   // ← 再排一帧
    }
}
```

spring 的更新回调里无条件重绘：

```kotlin
// GlassHostLayout.kt:4428-4432
pressSlotSprings += SpringFloat(1f, 900f, 0.9f, 0.001f) { value, _ ->
    if (index < pressSlotScales.size) pressSlotScales[index] = value
    invalidate()        // ← 每一帧都让宿主失效
}
```

于是形成闭环：**dispatchDraw → adapterNavigationFrame → animateTo → 下一帧 spring doFrame →
update() → invalidate() → dispatchDraw → …**。`SpringFloat.doFrame` 在第一次回调时也走
`update(...)`（`:14419-14428`），所以即使 spring 已经停在目标值，也至少会再画一帧。

### 2.2 影响

- 视频暂停、图片/图文页、评论区静止、用户不操作时，玻璃仍以 ~60 Hz 重绘。
  每次重绘都包含：`drawGlassPanel`（模糊 + 折射 + 描边）、`drawSelection`（lens 场景，
  见第 4 节）、导航文字/图标、以及 `prepareLiveBackdropScene()` 的场景重录
  （`forceCompositorFrames=true`，`:8011-8064`）。
- 项目注释里记录的 “idle feed 仍有 ~82 次/秒重绘”（`:3328-3336`）和
  “fps=30 / panel~115us / disp~350us”（`:1469`）与这条路径吻合；
  `FORCE_FULL_RATE_PUMP` 的 A/B 结果（55/s → 83/s，`:3354-3357`）也说明基线重绘数本来就不低。
- 它让 `videoFrameWatcher` 的省电逻辑失去意义：无论视频是否出新帧，宿主都在画。

### 2.3 修复（低风险，建议先做）

1. `SpringFloat.animateTo` 增加短路：

```kotlin
fun animateTo(newTarget: Float) {
    if (newTarget == target && !running && value == target) return
    target = newTarget
    if (!running) { ... }
}
```

   注意：`positionSpring` 等交互 spring 也走这里；`snapTo` 之后再 `animateTo` 同值是安全的。
2. spring 的 update 回调只在值真正变化时 invalidate：

```kotlin
pressSlotSprings += SpringFloat(1f, 900f, 0.9f, 0.001f) { value, _ ->
    if (value != pressSlotScales[index]) {
        pressSlotScales[index] = value
        invalidate()
    }
}
```

3. `refreshPressSlotScales()` 只在“按下状态 / indicatorPosition 跨槽 / 槽数变化”时重臂；
   静止时直接返回上一次的列表，并缓存 `asList()` 结果（见 9.1）。
4. `adapterNavigationFrame` 每帧构建 3-4 次本身也可以合并成 1 次（在 dispatchDraw 开头构建一次，
   三个使用点共用同一个 frame），这样 spring、列表分配、颜色计算都只做一次。

改完后，静止页面应由 `videoFrameWatcher` 的 1 s 心跳（`:3414-3423`）+ 视频新帧驱动，
空闲功耗应显著低于现在。

### 2.4 验证

- 临时把 `GLASS_HOST_DIAG_ENABLED`（`GlassHostLayout.kt:13758`）改为 `true`，`GlassPerf` 每秒会打印
  `fps=… sceneRec=… nodeRec=…`；改前静止页面 `fps` 应≈60，改后应≈0-2（暂停视频）。
- 或用 `adb shell dumpsys gfxinfo com.ss.android.ugc.aweme framestats` 观察空闲时的绘制帧数。

---

## 3. P1：每帧重复的宿主前奏（共享代码，抖音全量命中）

### 3.1 `ensureNavigationBarImmersion()` 每帧 2 次，且在节流之前

`DouyinPageController.update()` 的顺序有问题：

```kotlin
// DouyinPageController.kt:197-279
fun update(host: View, barHeightPx: Int) {
    currentBarHeightPx = barHeightPx
    val hostConfig = (host as? GlassHostLayout)?.config
    ...
    context.findActivity()?.let(GlassInstaller::ensureNavigationBarImmersion)   // :245 ← 每帧
    val now = SystemClock.uptimeMillis()
    val sheetUp = douyinCommentSheetUp()                                        // :255
    ...
    if (now - douyinLastLayoutAdjustAt < adjustInterval) return                 // :279 ← 节流在这里才发生
```

而 `onHostPreDraw()` 每帧都会调用 `update()`（`GlassHostLayout.kt:2349`），
同一帧里 `sourceVisibilityListener` 在 `:2286-2302` 又调用了一次
`context.findActivity()` + `ensureNavigationBarImmersion()`。**每帧 2 次 `findActivity`（最多 12 层
ContextWrapper 循环）+ 2 次 `@Synchronized` 的 `setNavigationBarImmersion()`**，后者内部还有一次
`Xposed.callMethod(decor, "getDecorViewImmersiveStub")` 反射调用
（`GlassInstaller.kt:277-388`，`:284-286`）。窗口标志几乎从不变化，这些工作 99.9% 是多余的。

**修复**：把 `:245` 移到 `:279` 的节流判断之后；`setNavigationBarImmersion` 在“本 Activity 已应用且
`decor.systemUiVisibility`/`navigationBarColor` 未变”时直接返回（缓存这三个值比较即可，不必每帧反射）。

### 3.2 `setNativeBarTouchable()` 每帧递归整棵原生行

```kotlin
// GlassHostLayout.kt:2004（applyBarVisibility 内，每帧）
setNativeBarTouchable(navigationSource, shouldShow)

// :7115-7133
private fun setNativeBarTouchable(view: View?, touchable: Boolean) {
    if (view == null) return
    if (appNavigationState?.setNativeBarTouchable(view, touchable) == true) return  // 顶栏 true，底栏 false
    view.isClickable = touchable
    view.isFocusable = touchable
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) setNativeBarTouchable(view.getChildAt(i), touchable)
    }
}
```

`DouyinNavigationState` 没有覆写 `setNativeBarTouchable`（只有顶栏 `DouyinTopBarState.kt:368` 覆写为
`true`），所以底栏每帧都递归遍历一次原生五格行（含每格的图标/文字子 view）。
`isClickable` 的 setter 在值未变时不会标脏，但递归本身仍在。

**修复**：宿主侧记录 `lastTouchable` 与 `lastTouchableSource`，只在值或 source 变化时遍历一次；
或在 `DouyinNavigationState` 覆写 `setNativeBarTouchable`，用「已应用」标志自行短路（顶栏已经这么做）。

### 3.3 `suppressNativeBottomChrome()` 每帧 3 次

`GlassHostLayout` 的 preDraw 监听器里：

- `:2361` `appNavigationState?.suppressNativeChrome(...)`（默认空实现）
- `:2376` / `:2382` `targetAdapter?.suppressNativeBottomChrome(this, it)`（抖音实现：清背景、遍历
  `rowShell` 子节点和 `chrome` 子节点、把 ≥80% 宽/高的子 view `alpha=0`）
- `:2386` `appNativeChromeController.update(this, source)` → 又调用一次
  `DouyinAdapter.suppressNativeBottomChrome`

三次调用都是幂等的属性写，但每次都做一次小规模树遍历、`parent` 访问和 `alpha/background` 写。
顶栏宿主还会走到 `DouyinTopBarFinder.hideNativeTitleBar()`（`:182-199`），它每 500 ms 递归整行
（`SUPPRESS_RESCAN_MS`，`:116`），注释里自己承认曾经“每帧两次、10 万-20 万次访问/秒”。

**修复**：三次合并为一次；`suppressNativeBottomChrome` 用“已抑制”标记（source + 版本号）短路；
`hideNativeTitleBar` 用 `row.setOnHierarchyChangeListener` 代替 500 ms 轮询（新增子节点时再清一次）。

### 3.4 祖先链 / 可见性探测每帧多次

- `releaseAncestorClipping()`（`:2165-2201`）每帧从宿主向上走到 `android.R.id.content`（≤12 层），
  设 `clipChildren/clipToPadding=false`。这些属性只有宿主换父容器时才需要重设。
  建议缓存 `parent` 与 `contentRoot`，父容器不变就跳过。
- `currentPageAllowsNavigation()`（`:13307-13389`）→ `DouyinNavigationState.hostPageAllowsNavigation()`
  （`:332-357`）→ `nativeBottomBarOnScreen()`（`:380-396`）→ `nativeBottomBarPresent()`（`:414-431`）
  **先走一遍 source+24 层祖先链，再调用 `douyinNativeBarOffScreen()` 把同样的检查再做一遍并
  `getGlobalVisibleRect()`**（`:465-491`）。每帧被调用 2 次（`:2481` 与 `:12480`），
  另外 `DouyinPageController` 每 pass 一次（`:2161`）。
- 修复：`nativeBottomBarPresent` 直接复用 `!douyinNativeBarOffScreen(source, nativeBarVisibleRect)`
  （前者是后者的严格子集）；再用 `uptimeMillis()` 时间戳缓存 1 帧，让两个监听器和控制器共享结果；
  控制器侧 96 ms 内复用即可（页面门限不需要 60 Hz）。

### 3.5 建议的合并顺序

把 `sourceVisibilityListener` 里“每帧必须做”的项压缩到：

1. `sourceReady` 判定（属性读）
2. `hostPageAllowsNavigation`（1 帧缓存）
3. `applyBarVisibility`（值未变不写）
4. 页面控制器（96 ms 节流，且节流前不做重活）

其余全部改成“变化时做”：配置、原 chrome 可见性、touchable、抑制、裁切、导航快照刷新。

---

## 4. P1：选中胶囊 lens 场景每帧整块重录

```kotlin
// DouyinNavigationState.kt:193
override fun reuseCombinedIndicatorScene(surfaceActive: Boolean): Boolean = false

// GlassHostLayout.kt:8488-8500
val reusableScene = appNavigationState?.reuseCombinedIndicatorScene(...) == true   // false
val stateNeedsRecord = appNavigationState?.combinedIndicatorSceneNeedsRecord(...)
val inkInScene = selectionInkInLensScene()
val combinedNeedsRecord = if (!reusableScene) true else ...                        // ← 恒 true

if (combinedNeedsRecord) {
    val combinedCanvas = indicatorCombinedNode.beginRecording(sceneWidth, sceneHeight)  // 面板大小
    drawBackdropContent(combinedCanvas, expandedBackdropRect)
    drawHiddenTabsBackdrop(...)        // 内部再录一次 tabsBackdropNode
    appNavigationState?.drawSelectionInk(combinedCanvas, adapterNavigationFrame(1f), inLensScene = true)
    indicatorCombinedNode.endRecording()
}
```

代码注释（`:181-190`）说明关闭复用是为了图标换 drawable 时不会“冻结”。这个理由成立，但代价是
**静止时也每帧重录一块面板大小的 display list**，外加 `drawHiddenTabsBackdrop` 里的第二块。
再叠加 `forceCompositorFrames=true` 让 `liveBackdropNode` 也每帧重录（`:8011-8064`），
一帧里至少有 2-3 块全尺寸场景录制。

**可选折中**（按风险从低到高）：

1. 静止（`!touching && pressProgress≈0 && indicatorPosition==dragTarget`）时复用上一次录制结果，
   只在按下/拖拽/指示器移动/导航快照签名变化时重录。图标换 drawable 通常伴随 `tabs` 变化，
   可以由 `navigationSnapshotSignature` + “当前选中 cell 的 `isSelected`”作为额外信号。
2. 把“背景层”和“行内容层”拆成两个节点：背景层签名不变时复用，只重录行内容（便宜得多）。
3. 只与视频帧率同步（`videoFrameStamp` 变化才重录），暂停视频时停止重录。

---

## 5. P1：页面控制器 10.4 Hz 全树遍历

### 5.1 主 walk

```kotlin
// DouyinPageController.kt:430-698
while (stackSize > 0 && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {   // 8192
    ...
    val idName = viewResourceEntryName(view)      // :436 HashMap 查找
    if (nativeTitleRowAlpha < 0f && DouyinTopBarFinder.isTopRow(view)) { ... }  // :442 每 view 反射 simpleName
    when (idName) { ... }                          // 6 个字符串比较
    ...
    if (douyinIsFeaturedFeedRoot(view)) { ... }    // :486 每 view 一次 id+父 id+尺寸
    ...
    val overlayRoot = douyinIsVideoControlsRoot(view)   // :607 每 view 再查一次 id
    ...
    val label = when (view) {
        is TextView -> view.text?.toString()
        else -> view.contentDescription?.toString()
    }.orEmpty()
    val relatedHit = DOUYIN_RELATED_SEARCH_TEXT.any { label.contains(it) }   // :646-654 每 view 4 次 contains
    ...
}
```

频率：`DOUYIN_LAYOUT_ADJUST_INTERVAL_MS=96`（`:2989`）⇒ ~10.4 次/秒；`DOUYIN_LAYOUT_MAX_VIEWS=8192`
（`:3049`）。抖音 feed 页的可见子树通常几百到上千个 view，按 800 估算就是 **8000+ view 访问/秒**，
其中每个 view 至少做：一次 `view.id`、一次 HashMap、一次 `isTopRow`（反射 `getSimpleName`）直到
标题栏被找到、一次 `isFeaturedFeedRoot`（再查两次资源名）、一次 `isVideoControlsRoot`（再查一次资源名）、
一次 `text.toString()` 或 `contentDescription.toString()`、4 次 `String.contains`。

项目注释里旧版本的实测是“5 ms 中位、25-33 ms 最差每 pass”（`:389-392`），零分配改造后仍不是零成本。

### 5.2 可以立刻消掉的重复

1. **`isTopRow` 每 view 反射**：`DouyinTopBarFinder.isTopRow()` 是
   `view.javaClass.simpleName == "MainTitleBar"`（`DouyinTopBar.kt:170`）。walk 每 pass 把
   `nativeTitleRowAlpha` 重置为 -1（`:402`），所以每个 pass 都会在找到标题栏之前对每个 view 调用一次。
   建议在 `DouyinTopBarFinder` 里加 `HashMap<Class<*>, Boolean>` 缓存，或直接比较
   `Class.forName` 一次解析出的 Class 对象。
2. **`isFeaturedFeedRoot` 无谓调用**：它只在 `idName == "dir"` 时可能为 true，先判断 id 再调用，
   可省掉每个 view 的一次资源名 + 父节点访问（`:486`）。
3. **`isVideoControlsRoot` 重复查资源名**：把已经算好的 `idName` 作为参数传入，省掉每个 view 一次
   HashMap 查找（`:607`）。
4. **related-search 文本扫描**：抖音 feed 页几乎不可能出现“相关搜索/大家都在搜”这类 pill；
   它只出现在搜索结果页。建议：
   - 用页面标记（搜索页的容器 id / class）门控，不在 feed 页做这段；
   - 或把这段降频到 1-2 s 一次；
   - 至少先判断 `view is TextView` 且 `text.isNotEmpty()` 再 `toString()`，避免对每个非文本 view
     做 `contentDescription` 读取和 4 次空串 `contains`。
5. **每 view 的 `IntArray(2)`**：标题栏定位那行（`:445`）每 pass 只执行一次，问题不大；
   但 `countDouyinBottomCompactChildren`（`:2893`）、`douyinShopBackToTop`（`:1776`）等仍在循环内分配，
   可复用 scratch 数组。
6. **`simpleNameOf` 已缓存**（`:1142-1146`），这部分没问题；`DouyinFeedCornerMasks.kt:17/24` 和
   `DouyinPlayerTextureHooks.hasLivePhotoVideoHost` 没有用这个缓存，可顺手统一。

### 5.3 子遍历：广告 / 全宽图 / 团购 / 视频容器

这些函数各自是 320-1200 view 的 BFS，并且**每次分配 `ArrayDeque<Pair<View,Int>>`（每个节点一个 Pair）**：

| 函数 | 位置 | 上限 | TTL | 实际频率 |
|---|---|---|---|---|
| `douyinPageLooksLikeAdvertisement` | `:1148-1191` | 1200 | 250 ms（负）/750 ms（正） | `pageAdjustDue("pagerchildren")` 200 ms 调用 × 每页（~3 页）⇒ 每 400 ms 每页一次全扫 |
| `douyinContainsFullWidthPhoto` | `:1613-1635` | 1200 | 1 s | 每个 lift target 每 pass，约 2-3 次/秒 |
| `douyinContainsGroupBuyContent` | `:1790-1812` | 1200 | 1 s | 同上 |
| `douyinIsGroupBuyPageContainer` / `douyinIsC2FeedPageContainer` | `:1222-1298` | 400 | 1 s | 页面级 |
| `findVideoContentInContainer` | `:2801-2831` | 8192 | 无 TTL，`videoContainerProbe` 每 384 ms 整表清空（`:331-335`） | 每个 target 每 384 ms 一次全子树 |
| `findPageControlsContainer` | `:2691-2769` | 8192 | 结构签名变化或 700 ms | 低频 |
| `releaseDouyinPageTopReserve` 的 marker 查找 | 已改为 walk 收集（`:1386-1436`） | — | — | 好 |

**建议**：

- 广告识别 TTL 与调用节奏对齐：负结果 250 ms 意味着“每两次调用就有一次要重扫”。广告属性在
  一个视频的生命周期内不会变，负 TTL 提到 2-5 s、正 TTL 保持 750 ms 即可，节省 80%+ 的扫描。
- 把这些 BFS 统一换成复用数组栈（页面控制器主 walk 已经这么做，`:389-399`），消除每节点一个 Pair。
- `videoContainerProbe` 的整表清空改成“条目带时间戳、按需过期”，或把 TTL 拉到 1-2 s。
- `douyinPageLooksLikeAdvertisement` 可以在主 walk 里顺便记录“是否见到过广告标记 id”，
  没见到就直接 false，省掉单独子树。

### 5.4 自适应降频

页面控制器的职责是“把控件 margin 抬到玻璃栏上方”。一旦某个页面的 lift 已应用且页面结构没变，
96 ms 的复扫只是在确认“还是对的”。建议：

- 结构/页面签名变化、滚动、配置变化后的 0.5-1 s 内保持 96 ms；
- 稳定后降到 250-400 ms（`DOUYIN_LAYOUT_ADJUST_INTERVAL_MS` 做成动态值）；
- 视频暂停/页面静止且无 lift 目标变化时，进一步降到 1 s（仍保留 1 s 心跳，注释 `:1447-1454` 的思路）。

按 96→300 ms 估算，主 walk 的 CPU 占用可降到约 1/3。

---

## 6. P1：视频采样

### 6.1 fallback 路径：62 次/秒 GPU→CPU 回读

```kotlin
// DouyinVideoSampler.kt:819-827
const val DOUYIN_VIDEO_COPY_INTERVAL_MS = 16L    // ~62 Hz
const val DOUYIN_VIDEO_CAPTURE_SCALE = 0.5f
```

`requestCurrent()` 每 16 ms 走一次 `captureLayers` + `wrapHardwareBuffer`（`:587-609`、`:639-709`），
只抓玻璃后方的窄条，已经做了 0.5 缩放和 scratch bitmap 复用（这是好的）。
但如果原生纹理钩子生效（`activeTexture(host) != null`，`:312`），整条回读路径会被跳过，
所以**设备上真正跑哪条路径取决于 `DouyinPlayerTextureHooks` 是否成功**；两条路径的代价差一个数量级，
值得在设备上确认（日志 `[OfflineGlass] native feed texture selected` 出现即走原生纹理）。

可继续省的点：

- **静止视频仍 62 Hz 回读**：`videoSourceIsStatic` 已经实现（`DouyinNavigationState.kt:107-122`、
  `:177`），但**全仓库没有任何调用点**。把它接到 `requestCurrent()` 开头（静止时降到 2-5 Hz），
  是一行级的收益。
- 30 fps 的视频只需要 ~30 次/秒回读，可把 cadence 与 `SurfaceTexture.getTimestamp()` 对齐，
  帧戳没变就跳过本次回读。
- `requestDouyinSurfaceControlFrame` 里 `getHardwareBuffer`/`getColorSpace` 用 `getMethod` 每帧反射
  （`:639-654`），把 `Method` 缓存进已经存在的 `CaptureApi` 即可。

### 6.2 原生纹理路径：每帧持锁 + 几何校验

```kotlin
// DouyinVideoSampler.kt:312
if (DouyinPlayerTextureHooks.activeTexture(host) != null) { ... }

// DouyinPlayerTextureHooks.kt:329-341
fun activeTexture(host: View): TextureView? {
    val scratch = geometryScratch.get()
    val h = scratch.hostLocation.also(host::getLocationOnScreen)
    val visible = scratch.visible
    return synchronized(textures) {
        ...
        val next = textures.keys.firstOrNull { usable(it, host, h, visible) }
        ...
    }
}

// :343-349
private fun usable(...) = texture.isAttachedToWindow && texture.isShown && texture.isAvailable &&
    ... texture.getGlobalVisibleRect(visible) && ...
```

每帧一次 monitor + WeakHashMap 查找 + `getLocationOnScreen` + `getGlobalVisibleRect`。
建议：

- 用 `@Volatile` 缓存 `host` 的屏幕位置，只在 layout 变化时更新；
- `usable()` 里的 `isShown`/`getGlobalVisibleRect` 可以 100-200 ms 校验一次，其余帧只比较
  `width/height` 和 `isAvailable`；
- `currentFrameStamp()`（`:318-326`，每帧调用）把时间戳改成由 `SurfaceTexture.onFrameAvailable`
  写入的 `@Volatile long`，避免每帧锁 + 迭代器。

### 6.3 每帧 Handler remove/post

```kotlin
// DouyinVideoSampler.kt:353-359
val remaining = DOUYIN_VIDEO_COPY_INTERVAL_MS - (now - lastDouyinVideoCopy)
if (remaining > 0L) {
    mainHandler.removeCallbacks(captureWakeup)
    mainHandler.postDelayed(captureWakeup, remaining)
    return
}
```

`OnPreDrawListener` 已经每帧驱动一次 `requestCurrent()`，这里的 16 ms 定时重臂是冗余的：
`removeCallbacks` 要拿 MessageQueue 锁并遍历队列，`postDelayed` 会产生非 vsync 对齐的唤醒。
建议去掉这段（`requestCurrent` 本身有 in-flight 和超时保护），或者只在 `remaining > 8 ms` 时排一次。

### 6.4 TextureView fallback 实际是死代码

`sampleDouyinVideoTextureViewFrame()`（`:717-789`）用 `compositorPixelCopyHandler`，但该 Handler
只在 SurfaceControl 路径里创建（`:600-605`）。没有 SurfaceView 时 `worker == null`，而
`if (worker == null || !worker.post { ... }) { }` 的块是空的 ⇒ 什么也不做。这解释了为什么
“没有 SurfaceView 的页面”实际上没有回读。要么删掉，要么把 Handler 初始化提前。

---

## 7. P2：顶栏（仅在开启“顶栏液态玻璃”时）

### 7.1 每帧重建 model

```kotlin
// DouyinTopBarState.kt:442-514 refresh()
if (!host.isShown || !host.isAttachedToWindow) return
...
val read = DouyinTopBarFinder.model(current, planFor(host.config)) ?: return   // :465
model = read
...
reportTopTabs(current)                                                         // :497
```

`model()`（`DouyinTopBar.kt:249-419`）每次分配：`Rect`、`ArrayList<View>`、`IntArray(2)`、
1-2 个 `ArrayList<MeasuredTab>`、每个 cell 一个 `MeasuredTab`、`sortBy` 的 lambda、每个 tab 一个
`DouyinTopTab`、以及 `labels` getter（`:44`）每次 `tabs.map { it.label }`。
`refresh()` 里还会用 `read.labels` 3 次（`:473/482/486`）。

建议：

- `DouyinTopBarModel.labels` 改为存储字段，只算一次；
- `model()` 增加廉价签名（row 身份 + `itemHost.childCount` + 选中 cell 的 id + 各 cell 的 label）
  未变则复用上一次的 model；
- `refresh()` 在 `labels`/`selectedIndex`/`leftAction`/`rightAction` 都没变时直接返回，不写回字段。

### 7.2 `reportTopTabs()` 的节流 bug：2 s 后变成每帧全树

```kotlin
// DouyinTopBarState.kt:521-532
val now = SystemClock.uptimeMillis()
if (now - lastReportAt < REPORT_INTERVAL_MS) return          // 2 s
val labels = DouyinTopBarFinder.allTabs(row, normalizeLocation = true)  // 全树 + 每 cell getLocationOnScreen
    .map { it.label.trim() }.filter { it.isNotEmpty() }.distinct()
if (labels.isEmpty()) return
val key = labels.joinToString("\u0001")
if (key == lastReportedTabs) return        // ← 这里 return 时 lastReportAt 没有更新
lastReportAt = now
```

标签没变化时 `lastReportAt` 不前进，于是**过了 2 s 之后每一帧都会执行一次 `allTabs()`**（全树 walk，
`firstDescendant` 上限 600 节点，每 cell 一次 `getLocationOnScreen` + `sortedBy`），直到标签变化。
这在浏览时几乎是常态（位置标签不变）。修复：把 `lastReportAt = now` 移到 `key` 比较之前，
或让 `allTabs` 使用 `locate()` 已缓存的 `locatedItemHost`。

### 7.3 `corners()` 每秒重扫、`hideNativeTitleBar()` 每 500 ms 递归

- `corners()`（`DouyinTopBar.kt:630-641`）的缓存条件要求左右两个角都存在，否则
  `now - cornersAt < CORNER_RESCAN_MS`（1 s）。只要用户隐藏了左侧按钮、或当前页面本来没有左角，
  它就会每秒重跑一次 `collect(row, CORNER_CLASS, ...)`（≤600 节点，每节点一次反射 `getSimpleName`）。
  建议缓存“负结果”（记录 `resolvedAbsent`），并用 `row.childCount` 或 `setOnHierarchyChangeListener`
  失效。
- `hideNativeTitleBar()`（`:182-199`）每 500 ms 递归整行清 `isClickable`。注释说是为了处理
  Douyin 复用行并新增可点 child。用 `setOnHierarchyChangeListener` 在新增时清一次即可，
  然后删除 `SUPPRESS_RESCAN_MS` 轮询。

### 7.4 菜单打开期间永不停帧 + 每帧重录场景

```kotlin
// DouyinTopTabMenu.kt:739（drawGlassBackdrop 末尾）
if (isShown) postInvalidateOnAnimation()

// :678-681
val animating = closing || expandProgress < 0.999f
val hasScene = if (animating) sceneNode.hasDisplayList() else {
    recordLiveScene(sceneWidth, sceneHeight)     // 稳定后仍然每帧重录
}
```

展开动画结束后 `expandProgress≈1`，`recordLiveScene()` 仍然每帧执行（遍历 `parent` 的每个兄弟节点、
`indexOfChild`、`getLocationInWindow`、反射 `mRenderNode`）。同时 `postInvalidateOnAnimation()`
让整个 MATCH_PARENT 覆盖层按显示率重绘，还把 `applyExpand()` 精心限制的脏矩形
（`:502-511`）覆盖成全屏脏区。
建议：

- 只在 `expandProgress` 变化、`videoFrameStamp` 变化、或兄弟节点布局变化时 invalidate；
- `recordLiveScene()` 只在首次打开/签名变化时执行一次（RenderNode 持有子节点引用，子节点自己会更新）；
- 稳态用 `invalidate(panelRect)` 而不是全屏。

### 7.5 渲染器每帧分配

`DouyinNavigationRenderer.drawEntries()`（`:148-155`、`:221`）：

```kotlin
val entries = NATIVE_SLOTS.indices.filter { NATIVE_SLOTS[it] in frame.enabledIndices } // 分配 List
val labels = Array(LABELS.size) { ... }                                               // 分配数组
for ((position, entry) in entries.withIndex()) { ... }                                // 迭代器 + IndexedValue
```

每次 draw 都会执行，且 `draw()` 与 `drawSelectionInk()` 两次进入；`labelPaint.fontMetrics`
（`:165`、`:190`、`:449-450`）每次都新建 `FontMetrics`；`measureText`（`:358`）每标签每帧一次；
徽标 `count.toString()`（`:446`）每帧一次；`RectF/Path` 重建（`:477-509`）。
建议：复用 `IntArray(4)+count`；`labels` 只在实际显示文字时填；`FontMetrics` 用
`getFontMetrics(fm)` 复用；`measureText` 按 `(text,textSize)` 缓存；徽标文本按 count 缓存；
矩形/路径按 `(size, scale)` 缓存。

---

## 8. 日志现状与建议

### 8.1 `press_log.txt` 的日志在当前代码里已经不存在

`press_log.txt` 是 2026-10-19 18:51 抓的，31.3 秒 1884 行，约 **60 条/秒**，全部是：

```
D GlassPress: host pos=... target=-1 selected=... touch=... drag=... press=... scales=[...]
D GlassPress: render pos=... sel=... scale=... slotScales=[...]
```

- 全仓库检索 `GlassPress`：`*.kt`、`*.java`、`backup-*` 目录，以及 `app/build` 下 271 个 `*.dex`
  的 ASCII 字符串检索，**均无命中**；
- 当前 debug APK 构建于 2026-10-19 20:52，比日志晚；同一次构建产出的 dex 里也没有该字符串
  （APK 内 dex 是压缩的，不能直接做字符串检索，这里以 `build/intermediates` 的原始 dex 为准）；
- 当前源码里最接近的是 `GlassPerf`，且被 `GLASS_HOST_DIAG_ENABLED=false`（`:13758`）编译期关掉。

结论：这份日志来自**旧版 APK**（设备上装的不是当前源码构建），当前代码已经不会这样刷日志。
如果设备上还在刷，先确认安装的是最新构建（`adb shell dumpsys package io.github.xzdw.dylt | findstr versionName`）。

### 8.2 现在的日志总量（发布版）

- `GLASS_HOST_DIAG_ENABLED = false`（`:13758`）⇒ `GlassPerf`、`[Show]`、`WmGlassDiag`、
  `cfgPoll`、几何日志、compositor 性能日志全部不执行（常量折叠，连字符串拼接都不会发生）。
- `Log.*` 全仓库 31 处、`Xposed.log` 约 20 处，绝大多数在安装/启动/失败路径。
- 配置通道有重复抑制：`HookConfigReader.cfgDiag()`（`:79-86`）只在内容变化或 60 s 后打印；
  `GlassHostLayout.diagLogGate()`（`:1578-1586`）同理。

### 8.3 仍值得处理的日志

| 位置 | 触发频率 | 问题 |
|---|---|---|
| `DouyinPlayerTextureHooks.kt:124` | **每创建一个 feed 播放器**（滑动浏览时每个视频一次） | 无门控的 `Xposed.log`（Binder + logcat），且 session 内不会只打一次 |
| `DouyinPlayerTextureHooks.kt:111-118` | 前 6 次 | 无门控，但有上限 |
| `DouyinTopBarState.kt:554-556` + `:434/:455/:458/:549` | 找到标题栏一次、合成点击、上报失败 | 无门控；建议照 `DouyinTopTabMenu.kt:883` 的 `MENU_DIAG_ENABLED=false` 加开关 |
| `GlassHostLayout.kt:5847-5859` | 每次配置应用 | `Log.i` 无条件，字符串很长；配置推送/回绑时各一次，量不大但可以移到 `GLASS_HOST_DIAG_ENABLED` 后面 |
| `GlassHostLayout.kt:8102` | 每个宿主首次 live backdrop（含 `joinToString` + 每源一次 `getLocationInWindow`） | 一次性，可接受；字符串构造可移到门控之后 |
| `GlassInstaller.kt:611` | 每次 rebind | 一次性 |
| `DouyinPageController.kt:1414/1425` | 每次释放 top reserve | 正常情况下一次；若 Douyin 反复重新加 padding，会变成每 pass 一条 |
| `DouyinPageController.kt:1116` | 每次 `update()` 抛异常 | 若错误每 pass 复现，就是 10 条/秒带堆栈；建议加 60 s 重复抑制 |
| `HookConfigReader.kt:267-270` | 每次 provider 回退 | 字符串里 `\$packageName` 被转义，日志会打印字面量 `$packageName`，是个小 bug |

### 8.4 建议

1. 统一一个 `object Debug { const val ENABLED = false }`（或复用 `BuildConfig.DEBUG` 之外的显式常量），
   所有“状态叙述型”日志都走它；`Xposed.log` 只保留真正的失败与一次性安装结果。
2. `POWER`/`Perf` 类日志只有在显式调试构建里才允许每秒输出；发布版不要保留任何“按秒/按帧”的日志。
3. 对确实需要保留的日志加“内容 + 时间”双门控（项目里 `diagLogGate` 已是这个模式）。
4. 日志字符串的拼接要放在门控**里面**（Kotlin 的内联字符串参数在调用点求值；门控在外层才省得掉）。

---

## 9. 其它可优化点

### 9.1 每帧分配

| 位置 | 内容 | 建议 |
|---|---|---|
| `GlassHostLayout.kt:6538` | `enabledNavigationIndices()` 每次 `filterNot` 分配 List | 用 `(slotCount, hiddenMask, topBar)` 做签名缓存 |
| `GlassHostLayout.kt:6577-6586` | `foregroundNavigationClipPath()` 每帧 new `Path` + `setBottomBarSquircle` | 按 `(selectionRect, cornerRadius)` 缓存 Path |
| `GlassHostLayout.kt:4443` | `pressSlotScales.asList()` 每次分配包装 | 缓存 `asList` 结果 |
| `GlassHostLayout.kt:8270` | `videoBackdrop()?.epoch` 每帧分配 `AppVideoBackdrop`，且整段代码是空的（`if (appVideoEpoch % 15 == 0) {}`）——死代码 | 删除，或改成读取 sampler 的 `val currentEpoch: Int` |
| `DouyinNavigationState.kt:210-216` | `outerBackdropNeedsRecord`/`onOuterBackdropRecorded` 每次 `snapshot()` 分配 | 同上，暴露无分配的 `currentEpoch` |
| `DouyinVideoSampler.kt:80-83` | `snapshot()` 每次 new `AppVideoBackdrop` | 同上 |
| `DouyinNavigationRenderer.kt:148/221` | List/Array/迭代器 | 见 7.5 |
| `DouyinPageController.kt` 各 BFS | `ArrayDeque<Pair<View,Int>>` | 改成复用数组栈 |
| `GlassHostLayout.kt:4428` | 4 个 slot spring 对象（一次性） | 可接受 |

### 9.2 传感器 50 Hz

`BloomTiltTracker` 在 `outlineEnabled=true`（默认）时注册
`TYPE_GAME_ROTATION_VECTOR`，`SENSOR_DELAY_GAME`（`:461`），每 20 ms 回调一次，做矩阵运算并在
方向变化超过阈值时 `postInvalidateOnAnimation()` 两个宿主（`:489-528`）。这是为一个装饰性描边
常开的传感器流。建议：

- 降到 `SENSOR_DELAY_UI`（~60 ms），视觉差异很小；
- 或只在“最近有触摸/正在交互/宿主可见且未暂停”时注册，静止 5-10 s 后注销；
- 屏幕关闭/宿主不可见时确保注销（目前 detach 会注销，但后台不可见时仍可能保持注册）。

### 9.3 `View.requestLayout` 全局 hook

`DouyinControlMarginGuard.kt:23-47` 在 `ownedCount > 0`（控件被抬起时是常态）时，每次
`View.requestLayout` 都要 `synchronized(owned) { owned[view] }`（WeakHashMap 查找）。
`requestLayout` 在抖音里调用极频繁。建议：

- 用 `view.setTag(id, margin)` 标记，hook 里直接读 tag（无锁、无 map）；
- 或维护一个小的 `ArrayList<View>` 做身份扫描（owned 通常 <5 个）。

### 9.4 `javaClass.simpleName` 的散落使用

`DouyinTopBar.kt:718/748`、`DouyinFeedCornerMasks.kt:17/24`、
`DouyinPlayerTextureHooks.kt:289-293` 都在热路径（每帧或每次 factory 调用）用反射 `getSimpleName()`。
`DouyinPageController` 已经有 `classSimpleNames` 缓存（`:1138-1143`），建议复用或各自加 `HashMap<Class<*>,…>`。

### 9.5 死代码 / 未使用状态（不影响功耗，但会误导后续优化）

- `DouyinNavigationState.videoSourceIsStatic`（`:177`）与 `DouyinStaticSourceDetector`（`:107-122`）
  **没有任何调用点**；要么接到 `videoWantsRepaint()` / `requestCurrent()`，要么删掉。
- `DouyinNavigationState` 的 `nativeBarObservedPresent`（`:150`、`:382-384`）、`lastBarAlphaDiagAt`
  （`:434`）只写不读。
- `combinedSceneState`（`:191-205`）因为 `reuseCombinedIndicatorScene=false` 永远不参与决策。
- `DouyinVideoSampler`：`captureRetryDelayMs`、`douyinCopyFailEpoch`、`douyinVideoChurn`、
  `:676` 的 `completedAt`、`:493` 的 `failEpoch`、以及 `:405-528` 的 PixelCopy 直连块
  （API≥29 永远走 `requestDouyinSurfaceControlFrame` 先返回）；
  `DouyinPlayerTextureHooks.kt:302` 的 `isNativeTexture` 也没有调用点。
- `DouyinNavigationRenderer` 的若干未用常量（`:642/654/700-701`）、`DouyinTabIcons.all`（`:405`）。
- `readCurrent()` 里 `"provider unavailable for \$packageName..."` 的 `\$` 转义 bug
  （`HookConfigReader.kt:267-270`）。

### 9.6 单作用域构建下的共享代码

本构建 `AppCatalog.targets` 只有抖音（`TargetSpecRegistry.kt:5-7`），但 `GlassHostLayout` 里仍保留
QQ、美团、JD、微博、XHS、B 站、文件管理器等分支。它们在抖音进程里只是包名比较（很便宜），
但**每帧路径上仍有 4-6 次无意义的函数调用/包名比较**（`:2335`、`:2371`、`:2373`、`:2396`、`:3977`）。
如果确定不再恢复多适配，可把共享宿主里非抖音的分支裁剪成默认实现，热路径会更短、更不容易在
未来改动中混入回归。若保留多适配，至少把这些检查做成 `targetAdapter` 的布尔字段（构造时求值一次），
而不是每帧比较字符串。

---

## 10. 建议实施顺序与预期收益

> 下面的百分比是**基于调用频率与工作量的估算**，不是设备实测；请按第 11 节验证。

| 步骤 | 改动 | 预期收益 | 风险 |
|---|---|---|---|
| 1 | 修 spring 每帧重臂（第 2 节） | 静止/暂停时玻璃重绘从 ~60 fps 降到 ~0-2 fps；空闲 GPU 占用大幅下降 | 低：只影响按压缩放动画的启动条件 |
| 2 | 修 `PageController.update` 节流顺序 + 门限缓存（3.1、3.4） | 每帧少 2 次同步/反射/祖先扫描，UI 线程 ~0.3-1 ms/帧 | 低 |
| 3 | 页面控制器自适应降频（5.4）+ 去掉 per-view 冗余（5.2） | 主 walk CPU 降到约 1/3，GC 压力下降 | 中：需验证 lift 在所有页面仍及时 |
| 4 | lens 场景静止复用（第 4 节） | 每帧少 1-2 块全尺寸 display list 录制 | 中：图标即时性需回归 |
| 5 | 视频采样接上 static 检测、去掉每帧 post（6.1、6.3） | fallback 路径回读降到 1/20；非 vsync 唤醒消失 | 低-中 |
| 6 | 顶栏 model/report/corners/menu（第 7 节） | 开启顶栏时 UI 线程与 GPU 明显下降 | 中 |
| 7 | 日志门控与清理（第 8 节） | 消除 Binder 往返与 logcat 抖动 | 低 |
| 8 | 传感器降频、requestLayout hook、分配优化（第 9 节） | 长期小幅 | 低 |

---

## 11. 验证方法

1. **帧数与每帧成本**：临时打开 `GLASS_HOST_DIAG_ENABLED`，读 `GlassPerf` 的
   `fps / prep / panel / disp / sweeps / sceneRec / nodeRec`。
   重点对比：feed 播放、feed 暂停、图文页、个人主页、评论区打开。
2. **UI 线程**：Perfetto / `adb shell atrace gfx view`，看
   `Choreographer#doFrame`、`ViewRootImpl#performTraversals`、`View#draw` 的时长分布。
3. **GPU**：`adb shell dumpsys gfxinfo com.ss.android.ugc.aweme` 的 `Janky frames` 与
   `50th/90th/95th/99th percentile`；对比改动前后的 `RenderThread` 时间。
4. **功耗**：`adb shell dumpsys batterystats --reset` 后固定场景 10 分钟，对比
   `batterystats` 的 mA 与 `PowerProfile` 估算；或 Perfetto 的 `power` 计数器。
5. **功能回归**：底栏 icon/文字/隐藏项/自定义名字/发布按钮、顶栏标签数量与排序、菜单、评论/聊天页
   的栏隐藏、个人主页回归、滑动 lift、深色/浅色切换。

---

## 12. 不要回退的既有优化（代码注释里已有实测）

- 视频帧率泵不要改回固定 60 Hz 定时器（`GlassHostLayout.kt:3354-3357` 实测更差）。
- `DOUYIN_VIDEO_COPY_INTERVAL_MS` 不要降回 8 ms（`DouyinVideoSampler.kt:819-826`）。
- 页面控制器的主 walk 已经是零分配数组栈（`DouyinPageController.kt:389-399`），不要改回 `ArrayDeque<Pair>`。
- 广告/全宽图/团购/controls 的 TTL 缓存是修过卡顿的
  （`DouyinPageController.kt:169-187`、`:325-335`、`:877-896`），只能延长 TTL，不能删除。
- `DouyinStaticSourceDetector` 的 250 ms 去抖（`DouyinNavigationState.kt:47`）、
  `DOUYIN_BAR_AWAY_RELEASE_HOLD_MS` 的 404-446 ms 窗口、`DOUYIN_SHEET_CLOSE_SETTLE_MS=1200`
  都是设备实测出来的，不要为了省电缩短。
- 配置 push 通道 + 60 s 安全轮询（`CONFIG_PUSH_SAFETY_INTERVAL_MS`）不要退回到 1 s 轮询。
- 顶栏 `reuseCombinedIndicatorScene=false` 的图标即时性取舍（`:181-190`）如果要改回复用，
  必须先有“图标变化也能被签名捕获”的方案。
- `DouyinPlayerTextureHooks` 的原生纹理选择是去掉 PixelCopy 回读的关键，不要因为“简化”而删掉。

---

## 附录 A：关键文件与职责

| 文件 | 作用 | 本次关注点 |
|---|---|---|
| `hook/GlassHostLayout.kt` | 玻璃宿主：绘制、帧泵、preDraw 生命周期、捕获、配置 | 第 2-4、9 节 |
| `hook/GlassInstaller.kt` | 安装宿主、导航栏沉浸 | 3.1 |
| `hook/HookConfigReader.kt` | 目标进程读配置、push 通道 | 8.3 |
| `hook/adapters/douyin/DouyinAdapter.kt` | 适配器契约与参数 | 频率来源（60 Hz 请求上限、连续帧泵等） |
| `hook/adapters/douyin/DouyinNavigationState.kt` | 底栏状态：页面门限、视频采样委托 | 3.4、4、6 |
| `hook/adapters/douyin/DouyinNavigationRenderer.kt` | 底栏绘制 | 7.5 |
| `hook/adapters/douyin/DouyinPageController.kt` | 96 ms 页面维护 pass | 第 5 节 |
| `hook/adapters/douyin/DouyinVideoSampler.kt` | 视频回读 | 第 6 节 |
| `hook/adapters/douyin/DouyinPlayerTextureHooks.kt` | 原生纹理选择 | 6.2、8.3 |
| `hook/adapters/douyin/DouyinTopBar*.kt` | 顶栏（可选） | 第 7 节 |
| `hook/adapters/douyin/DouyinTopTabMenu.kt` | 顶栏标签菜单 | 7.4 |
| `hook/adapters/douyin/DouyinUnreadBadge.kt` | 未读红点（300 ms） | 9.1 |
| `hook/adapters/douyin/DouyinControlMarginGuard.kt` | margin 保活 hook | 9.3 |
| `hook/adapters/douyin/DouyinFeedCornerMasks.kt` | 角标隐藏 | 9.4 |

## 附录 B：结论的置信度

- 高（代码路径确定、频率确定）：第 2、3、5、7.2 节。
- 中（路径确定，但设备上是否走该分支取决于钩子是否成功）：第 6 节视频回读路径；
  建议在设备日志里确认 `native feed texture selected` 是否出现。
- 中（影响大小只能估算）：第 4 节 lens 录制的 GPU 成本、第 9.2 节传感器功耗。
- 已排除：`press_log.txt` 里的 `GlassPress` 日志在当前源码/当前 APK 中不存在，
  它不是本次代码的问题；如果设备仍在刷，是安装了旧版构建。
