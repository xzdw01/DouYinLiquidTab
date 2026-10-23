package io.github.xzdw.dylt.ui

import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.RoundedCorner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplayTransitionEffects
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.highlight.Highlight
import io.github.xzdw.dylt.R
import io.github.xzdw.dylt.config.GlassConfig
import io.github.xzdw.dylt.config.ManagerSettings
import io.github.xzdw.dylt.framework.HookedProcess
import io.github.xzdw.dylt.framework.ModuleActivation
import io.github.xzdw.dylt.targets.AppCatalog
import io.github.xzdw.dylt.ui.liquid.components.LiquidBottomTab
import io.github.xzdw.dylt.ui.liquid.components.LocalLiquidBottomTabAccentCopy
import io.github.xzdw.dylt.ui.liquid.components.LiquidBottomTabs
import io.github.xzdw.dylt.ui.liquid.components.LiquidButton
import io.github.xzdw.dylt.ui.liquid.components.LiquidSlider
import io.github.xzdw.dylt.ui.liquid.components.LiquidToggle
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.squircle.squircleClip

/**
 * Liquid-glass manager UI.
 *
 * The layout hosts three root pages (主页 / 设置 / 关于) via
 * [ManagerPagePager], a pinned top bar and a bottom navigation bar, all inside
 * the [ManagerRoute.Main] scene of a [NavDisplay]. Sub-pages are pushed on top
 * of that scene and popped by the predictive-back gesture NavDisplay provides.
 * Where the Material build used themed cards, this build paints real
 * backdrop-sampled glass surfaces using the rendering core from the
 * AndroidLiquidGlass example project (`io.github.kyant0:backdrop`), together
 * with its ported LiquidButton / LiquidToggle / LiquidSlider / LiquidBottomTabs
 * components.
 *
 * Two kinds of recorded layers are used so nothing samples itself:
 *  - `controlsBackdrop` records only the static background gradient, and is what
 *    in-content glass widgets (panels, toggles, sliders, buttons) sample;
 *  - each scene records its own background plus page content, and is what that
 *    scene's top bar and bottom navigation bar sample so they blur the content
 *    scrolling underneath them.
 */
@Composable
fun GlassManager(
    settings: ManagerSettings,
    activation: ModuleActivation,
    selectedPage: ManagerPage,
    onSelectedPage: (ManagerPage) -> Unit,
    actions: UiActions,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.colorMode) {
        io.github.xzdw.dylt.config.ColorMode.SYSTEM -> systemDark
        io.github.xzdw.dylt.config.ColorMode.LIGHT -> false
        io.github.xzdw.dylt.config.ColorMode.DARK -> true
    }
    val palette = remember(dark) { glassPalette(dark) }

    val backgroundTransition = rememberInfiniteTransition(label = "background")
    val backgroundPhase = backgroundTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 30_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "backgroundPhase",
    )
    // The background is painted from this lambda (rather than a Brush) so the
    // animated value is read while drawing, never while composing.
    val backgroundOnDraw: DrawScope.() -> Unit = remember(palette, backgroundPhase) {
        { drawAnimatedBackground(palette, backgroundPhase.value) }
    }
    val controlsOnDraw: ContentDrawScope.() -> Unit = remember(backgroundOnDraw) { { backgroundOnDraw() } }
    // Records the background, so the in-content glass widgets sample the
    // gradient rather than their own content.
    val controlsBackdrop = rememberLayerBackdrop(onDraw = controlsOnDraw)

    // One back stack for the whole manager: [ManagerRoute.Main] hosts the pager
    // with the three tabs, and a sub-page is pushed on top of it. NavDisplay owns
    // the predictive-back gesture, so dragging in from the screen edge previews
    // the pop instead of only reacting once the gesture commits.
    val backStack = remember { NavBackStack<NavKey>(ManagerRoute.Main) }
    val pop: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() }
    val push: (ManagerRoute) -> Unit = { route -> if (route !in backStack) backStack.add(route) }

    Box(Modifier.fillMaxSize()) {
        // Background, visible and also recorded for the in-content glass.
        Box(Modifier.matchParentSize().drawBehind { backgroundOnDraw() })
        Box(Modifier.matchParentSize().layerBackdrop(controlsBackdrop))

        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
            onBack = pop,
            transitionEffects = NavDisplayTransitionEffects(
                // The library only keeps its corner clip on while the scene is
                // strictly mid-transition, so a quick (velocity-committed) pop
                // settles before the clip is ever visible and the corners look
                // square. The sub-page carries its own permanent card clip
                // instead, which is identical for slow drags and fast pops.
                enableCornerClip = false,
                dimAmount = 0.18f,
                blockInputDuringTransition = true,
                // Without this the pop direction is taken from whichever screen
                // edge started the gesture (left edge -> exits right, right edge
                // -> exits left). Keeping it false pins every pop to the default
                // transform, so the sub-page always leaves to the right.
                popDirectionFollowsSwipeEdge = false,
            ),
            entryProvider = entryProvider<NavKey> {
                entry<ManagerRoute.Main> {
                    GlassMainScene(
                        settings = settings,
                        activation = activation,
                        actions = actions,
                        selectedPage = selectedPage,
                        onSelectedPage = onSelectedPage,
                        onOpenSettings = { push(ManagerRoute.SettingsCategory(it)) },
                        onOpenHomeInfo = { push(ManagerRoute.HomeInfo(it)) },
                        backgroundOnDraw = backgroundOnDraw,
                        controlsBackdrop = controlsBackdrop,
                        palette = palette,
                    )
                }
                entry<ManagerRoute.SettingsCategory> { route ->
                    GlassSubpageScene(
                        title = route.page.title,
                        onBack = pop,
                        backgroundOnDraw = backgroundOnDraw,
                        palette = palette,
                    ) { listState ->
                        GlassSettingsSubpage(
                            settings,
                            actions,
                            controlsBackdrop,
                            palette,
                            route.page,
                            listState,
                            onOpenSubpage = { push(ManagerRoute.SettingsCategory(it)) },
                        )
                    }
                }
                entry<ManagerRoute.HomeInfo> { route ->
                    GlassSubpageScene(
                        title = route.page.title,
                        onBack = pop,
                        backgroundOnDraw = backgroundOnDraw,
                        palette = palette,
                    ) { listState ->
                        GlassHomeSubpage(route.page, controlsBackdrop, palette, listState)
                    }
                }
            },
        )
    }
}

/**
 * One navigation scene: an opaque page background, a recorded layer holding the
 * page content, and the scene's glass bars drawn over it.
 *
 * The bars stay outside the recorded layer so they blur the page rather than
 * themselves, and every scene keeps its own layer so a scene's bar never samples
 * the page sliding past it. Because the bars live inside the scene they travel
 * with it during a push or pop — the top bar and its back pill move together
 * with the page — while the opaque background stops the outgoing page from
 * showing through the incoming one.
 */
@Composable
private fun GlassScene(
    backgroundOnDraw: DrawScope.() -> Unit,
    modifier: Modifier = Modifier,
    pageContent: @Composable BoxScope.() -> Unit,
    bars: @Composable BoxScope.(Backdrop) -> Unit,
) {
    val sceneOnDraw: ContentDrawScope.() -> Unit = remember(backgroundOnDraw) {
        {
            backgroundOnDraw()
            drawContent()
        }
    }
    val backdrop = rememberLayerBackdrop(onDraw = sceneOnDraw)
    Box(modifier.fillMaxSize().drawBehind { backgroundOnDraw() }) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { pageContent() }
        bars(backdrop)
    }
}

/** [ManagerRoute.Main]: the root pager plus its pinned top bar and bottom tabs. */
@Composable
private fun GlassMainScene(
    settings: ManagerSettings,
    activation: ModuleActivation,
    actions: UiActions,
    selectedPage: ManagerPage,
    onSelectedPage: (ManagerPage) -> Unit,
    onOpenSettings: (ManagerSettingsSubpage) -> Unit,
    onOpenHomeInfo: (ManagerHomeSubpage) -> Unit,
    backgroundOnDraw: DrawScope.() -> Unit,
    controlsBackdrop: Backdrop,
    palette: GlassPalette,
) {
    // Each root page owns its own scroll position. Hoisting them here (rather
    // than inside the page composables) lets the shared pinned top bar read the
    // active page's scroll offset and grow its frosted plate as that page moves.
    val homeListState = rememberLazyListState()
    val settingsListState = rememberLazyListState()
    val aboutListState = rememberLazyListState()
    val homeProgress = rememberTopBarProgress(homeListState)
    val settingsProgress = rememberTopBarProgress(settingsListState)
    val aboutProgress = rememberTopBarProgress(aboutListState)
    GlassScene(
        backgroundOnDraw = backgroundOnDraw,
        pageContent = {
            ManagerPagePager(
                selectedPage = selectedPage,
                onSelectedPage = onSelectedPage,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    ManagerPage.HOME ->
                        GlassHome(settings, activation, actions, controlsBackdrop, palette, homeListState)
                    ManagerPage.SETTINGS ->
                        GlassSettings(settings, controlsBackdrop, palette, onOpenSettings, settingsListState)
                    ManagerPage.ABOUT ->
                        GlassAbout(controlsBackdrop, palette, onOpenHomeInfo, aboutListState)
                }
            }
        },
        bars = { backdrop ->
            val pageProgress = when (selectedPage) {
                ManagerPage.HOME -> homeProgress
                ManagerPage.SETTINGS -> settingsProgress
                ManagerPage.ABOUT -> aboutProgress
            }
            GlassTopBar(
                title = selectedPage.title,
                onBack = null,
                backdrop = backdrop,
                palette = palette,
                progress = pageProgress,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            LiquidBottomTabs(
                selectedTabIndex = { ManagerPage.entries.indexOf(selectedPage).coerceAtLeast(0) },
                onTabSelected = { index -> onSelectedPage(ManagerPage.entries[index]) },
                backdrop = backdrop,
                tabsCount = ManagerPage.entries.size,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                ManagerPage.entries.forEach { page ->
                    // Only the sampled copy carries the selected artwork. The visible layer always
                    // shows the resting (outline) style, so the accent fill appears exactly where the
                    // capsule is and reverts as the capsule leaves — using `page == selectedPage`
                    // here kept the old entry filled and accent-coloured for the whole drag, because
                    // the selection only commits on release.
                    val accentCopy = LocalLiquidBottomTabAccentCopy.current
                    LiquidBottomTab({ onSelectedPage(page) }) {
                        Icon(
                            imageVector = if (accentCopy) page.selectedIcon else page.unselectedIcon,
                            contentDescription = page.title,
                            tint = if (accentCopy) palette.accent else palette.secondary,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = page.title,
                            color = if (accentCopy) palette.accent else palette.secondary,
                            fontSize = 12.sp,
                            fontWeight = if (accentCopy) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
    )
}

/** A pushed sub-page: its content with a back-pill top bar of its own. */
@Composable
private fun GlassSubpageScene(
    title: String,
    onBack: () -> Unit,
    backgroundOnDraw: DrawScope.() -> Unit,
    palette: GlassPalette,
    content: @Composable BoxScope.(LazyListState) -> Unit,
) {
    val listState = rememberLazyListState()
    val topBarProgress = rememberTopBarProgress(listState)
    GlassScene(
        backgroundOnDraw = backgroundOnDraw,
        // The sub-page is a card clipped with the display's own corner radius.
        // Using the physical radius keeps the curve identical on every
        // transition (push, drag-pop and quick pop) and makes it coincide with
        // the display mask once settled, so no radius jump is ever visible.
        // miuix's squircle clip reproduces the continuous-corner curvature the
        // system uses, and degrades to a plain rounded clip if the runtime
        // shader is unavailable.
        modifier = Modifier.squircleClip(systemCornerRadius()),
        pageContent = { content(listState) },
        bars = { backdrop ->
            GlassTopBar(
                title = title,
                onBack = onBack,
                backdrop = backdrop,
                palette = palette,
                progress = topBarProgress,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
    )
}

/**
 * Physical corner radius of the device display, read from the window insets so a
 * scene can clip itself with exactly the screen's own curve. Falls back to the
 * radius the shared glass panels use when the platform reports no rounded
 * corner.
 */
@Composable
private fun systemCornerRadius(): Dp {
    val density = LocalDensity.current
    val radiusPx = LocalView.current.rootWindowInsets
        ?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
        ?.radius
    return if (radiusPx != null && radiusPx > 0) with(density) { radiusPx.toDp() } else 28.dp
}

// ---------------------------------------------------------------------------
// Palette + shared glass primitives
// ---------------------------------------------------------------------------

internal class GlassPalette(
    val backgroundPalettes: List<List<Color>>,
    val content: Color,
    val secondary: Color,
    val accent: Color,
    val panelTint: Color,
)

/**
 * The animated background walks these palettes in order. Adjacent palettes hold
 * near hues on the colour wheel, so every step stays saturated instead of fading
 * through grey, and the last repeats the first so the loop has no jump.
 */
private val darkBackgroundPalettes: List<List<Color>> = listOf(
    listOf(Color(0xFF0A1A45), Color(0xFF102A6E), Color(0xFF16389B), Color(0xFF122E7A), Color(0xFF0C2052)),
    listOf(Color(0xFF07262B), Color(0xFF0B3B44), Color(0xFF0F5058), Color(0xFF0C4149), Color(0xFF092E35)),
    listOf(Color(0xFF1B0F45), Color(0xFF2E1A73), Color(0xFF45269E), Color(0xFF34208A), Color(0xFF241453)),
    listOf(Color(0xFF2E0A2E), Color(0xFF4E1550), Color(0xFF6E2270), Color(0xFF541C5C), Color(0xFF3A1038)),
    listOf(Color(0xFF0A1A45), Color(0xFF102A6E), Color(0xFF16389B), Color(0xFF122E7A), Color(0xFF0C2052)),
)

private val lightBackgroundPalettes: List<List<Color>> = listOf(
    listOf(Color(0xFF7FA9EE), Color(0xFF9CC1F2), Color(0xFFB9D6F6), Color(0xFF9CC4F0), Color(0xFF7FB0EA)),
    listOf(Color(0xFF7FD8C4), Color(0xFFA2E1D4), Color(0xFFC4EBE2), Color(0xFFA2E0DC), Color(0xFF7FCDD8)),
    listOf(Color(0xFFA98BEC), Color(0xFFC0A6F1), Color(0xFFD6C3F6), Color(0xFFBDA9EE), Color(0xFFA18FE6)),
    listOf(Color(0xFFEE8FC0), Color(0xFFF2ACD2), Color(0xFFF6C8E1), Color(0xFFF2B8C0), Color(0xFFEE9FA8)),
    listOf(Color(0xFF7FA9EE), Color(0xFF9CC1F2), Color(0xFFB9D6F6), Color(0xFF9CC4F0), Color(0xFF7FB0EA)),
)

private fun glassPalette(dark: Boolean): GlassPalette = if (dark) {
    GlassPalette(
        backgroundPalettes = darkBackgroundPalettes,
        content = Color.White,
        secondary = Color.White.copy(alpha = 0.62f),
        accent = Color(0xFF0091FF),
        panelTint = Color.White.copy(alpha = 0.10f),
    )
} else {
    GlassPalette(
        backgroundPalettes = lightBackgroundPalettes,
        content = Color.Black,
        secondary = Color.Black.copy(alpha = 0.55f),
        accent = Color(0xFF0088FF),
        panelTint = Color.White.copy(alpha = 0.45f),
    )
}

private val PanelShape: Shape = RoundedCornerShape(28.dp)
private val BarShape: Shape = RoundedCornerShape(percent = 50)

/**
 * Paints the module's background: a gradient that sweeps through a cycle of
 * colour palettes and drifts diagonally. Callers read the animated [phase]
 * inside their own draw lambda, so the background repaints every frame without
 * ever recomposing the UI tree.
 */
private fun DrawScope.drawAnimatedBackground(palette: GlassPalette, phase: Float) {
    val palettes = palette.backgroundPalettes
    // Walk through the palettes in order: phase 0..1 spans them all, so the
    // background crosses the whole colour range instead of swinging between two.
    val scaled = phase * (palettes.size - 1)
    val index = scaled.toInt().coerceIn(0, palettes.size - 2)
    val local = scaled - index
    val from = palettes[index]
    val to = palettes[index + 1]
    val colors = from.indices.map { lerp(from[it], to[it], local) }
    // Sinusoidal drift: it returns to zero at both ends of the cycle, so the
    // gradient axis never jumps when the loop restarts.
    val shift = (sin(phase * 2.0 * PI) * size.height * 0.2).toFloat()
    drawRect(
        brush = Brush.linearGradient(
            colors = colors,
            start = Offset(0f, shift),
            end = Offset(size.width, size.height + shift),
        ),
    )
}

/**
 * A backdrop-sampled glass panel. [backdrop] must be the background-only layer
 * so the panel blurs the gradient rather than its own content.
 */
@Composable
private fun GlassPanel(
    backdrop: Backdrop,
    palette: GlassPalette,
    modifier: Modifier = Modifier,
    shape: Shape = PanelShape,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = { blur(12.dp.toPx()) },
                highlight = { Highlight.Default },
                shadow = null,
                onDrawSurface = { drawRect(palette.panelTint) },
            )
            .padding(contentPadding),
        content = content,
    )
}

@Composable
private fun GlassTopBar(
    title: String,
    onBack: (() -> Unit)?,
    backdrop: Backdrop,
    palette: GlassPalette,
    progress: State<Float>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var barHeightPx by remember { mutableStateOf(0) }
    val feather = 56.dp
    val veilHeightPx = barHeightPx + with(density) { feather.toPx() }
    val blurEffect = rememberManagerGradientTopBlurEffect(
        enabled = barHeightPx > 0,
        veilHeightPx = veilHeightPx,
        density = density.density,
    )
    val solidFraction = if (veilHeightPx > 0f) {
        (barHeightPx / veilHeightPx).coerceIn(0f, 0.95f)
    } else {
        0.6f
    }
    Box(modifier = modifier.fillMaxWidth()) {
        if (barHeightPx > 0) {
            // One plate, one progressive blur. The AGSL chain fades from a real
            // 28dp Gaussian at the status bar to no blur at the feather's end, so
            // the bar melts into the list instead of drawing a translucent band.
            // Progress is read in the draw/layer phase: scrolling invalidates
            // drawing only, never the composition of the bars slot.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { veilHeightPx.toDp() })
                    .graphicsLayer { alpha = progress.value }
                    .drawWithContent {
                        if (progress.value > 0.001f) drawContent()
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = { renderEffect = blurEffect },
                        highlight = null,
                        shadow = null,
                        onDrawSurface = {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0f to palette.panelTint.copy(
                                            alpha = palette.panelTint.alpha * 0.55f,
                                        ),
                                        solidFraction to palette.panelTint.copy(
                                            alpha = palette.panelTint.alpha * 0.30f,
                                        ),
                                        1f to Color.Transparent,
                                    ),
                                ),
                            )
                        },
                    ),
            )
        }
        Box(
            modifier = Modifier
                .onSizeChanged { barHeightPx = it.height }
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // The title stays plain; the back affordance is a single soft circle
            // rather than a second backdrop blur, so the bar reads as one piece.
            Text(
                text = title,
                color = palette.content,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 48.dp),
            )
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(palette.panelTint.copy(alpha = 0.35f))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回上一页",
                        tint = palette.content,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberTopBarProgress(listState: LazyListState): State<Float> {
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    return remember(listState, threshold) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / threshold).coerceIn(0f, 1f)
            }
        }
    }
}

@Composable
private fun GlassSectionTitle(text: String, palette: GlassPalette) {
    Text(
        text = text,
        color = palette.accent,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun GlassSwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    backdrop: Backdrop,
    palette: GlassPalette,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) palette.content else palette.secondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!summary.isNullOrEmpty()) {
                Text(summary, color = palette.secondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (enabled) {
            LiquidToggle(selected = { checked }, onSelect = onChecked, backdrop = backdrop)
        } else {
            // A locked row greys its switch out rather than only ignoring the tap, so the rule
            // behind the lock (底栏菜单项隐藏 always keeps one entry) is visible on the page.
            LiquidToggle(
                selected = { checked },
                onSelect = {},
                backdrop = backdrop,
                modifier = Modifier.alpha(0.4f),
            )
        }
    }
}

/**
 * [LiquidSlider] reports a value on every drag frame. The label is driven by a
 * local copy so it tracks the thumb in real time, while the commit to the
 * setting store is debounced: persisting each frame would rewrite
 * SharedPreferences and reload the whole config on every pixel of movement.
 */
@Composable
private fun GlassSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    backdrop: Backdrop,
    palette: GlassPalette,
    /** Optional snap step; 0 keeps the slider continuous. */
    step: Float = 0f,
    onChange: (Float) -> Unit,
) {
    fun snap(candidate: Float): Float =
        if (step > 0f) (candidate / step).roundToInt() * step else candidate

    var localValue by remember { mutableFloatStateOf(snap(value)) }
    var committed by remember { mutableFloatStateOf(snap(value)) }
    var pending by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(value) { localValue = snap(value) }
    LaunchedEffect(pending) {
        val candidate = pending ?: return@LaunchedEffect
        delay(250)
        if (pending == candidate && candidate != committed) {
            committed = candidate
            pending = null
            onChange(candidate)
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = palette.content, fontSize = 15.sp)
            Text(format(localValue), color = palette.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        LiquidSlider(
            value = { localValue },
            onValueChange = { v ->
                val snapped = snap(v)
                localValue = snapped
                pending = snapped
            },
            valueRange = range,
            visibilityThreshold = if (step > 0f) step / 2f else (range.endInclusive - range.start) / 1000f,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------

@Composable
private fun GlassHome(
    settings: ManagerSettings,
    activation: ModuleActivation,
    actions: UiActions,
    backdrop: Backdrop,
    palette: GlassPalette,
    listState: LazyListState,
) {
    val config = settings.glass

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 96.dp,
            bottom = 128.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { GlassActivationPanel(activation, backdrop, palette) }
        item { GlassVersionRow(backdrop, palette) }
        item { SectionLabel("快捷控制", palette) }
        item {
            GlassPanel(backdrop, palette) {
                GlassSwitchRow(
                    "防止烧屏",
                    if (config.antiBurnIn) "文字图标随时间微移"
                    else "文字图标保持固定位置",
                    config.antiBurnIn,
                    backdrop,
                    palette,
                ) { enabled -> actions.setGlassConfig(config.copy(antiBurnIn = enabled)) }
                GlassSwitchRow(
                    "底栏液态玻璃",
                    if (settings.liquidGlassEnabled) "底栏使用液态玻璃材质"
                    else "底栏保持原生样式",
                    settings.liquidGlassEnabled,
                    backdrop,
                    palette,
                ) { enabled -> actions.setLiquidGlassEnabled(enabled) }
                GlassSwitchRow(
                    "顶栏液态玻璃",
                    if (config.topBarLiquidGlass) "开启液态玻璃材质并精简顶栏布局"
                    else "顶栏保持原生样式",
                    config.topBarLiquidGlass,
                    backdrop,
                    palette,
                ) { actions.setGlassConfig(config.copy(topBarLiquidGlass = it)) }
            }
        }
        item { SectionLabel("使用指南", palette) }
        item {
            GlassPanel(backdrop, palette) {
                GlassGuideStep(1, "在 LSPosed 中启用本模块，并勾选抖音作用域。", palette)
                Spacer(Modifier.height(10.dp))
                GlassGuideStep(2, "强制停止抖音再重新打开，使功能注入生效。", palette)
                Spacer(Modifier.height(10.dp))
                GlassGuideStep(3, "之后在模块设置中调整外观参数，部分参数改动无需重启抖音。", palette)
            }
        }
    }
}

/**
 * Activation exactly as the Xposed framework reports it through the libxposed
 * module service: the framework connects to this app's `XposedProvider` only
 * for an enabled module, and its service answers with the activated scope and,
 * on service API 102 and above, the processes currently running the module.
 *
 * Nothing here is self-reported by a hooked target, so the card can only claim
 * "working" while the framework has the module loaded in a live process.
 */
@Composable
private fun GlassActivationPanel(
    activation: ModuleActivation,
    backdrop: Backdrop,
    palette: GlassPalette,
) {
    // Scope granted is enough to call the module activated: the user should not
    // see a separate "authorized, open the app" state just because the process
    // is not running yet.
    val activated = activation.isWorking ||
        (activation.isEnabled && activation.scope.isNotEmpty())
    val title = if (activated) "模块已激活" else "模块未激活"
    val detail = when {
        activated -> "我焯，太LiquidGlass了"
        activation.failure != null -> "服务不可用：${activation.failure}"
        else -> "未在 LSPosed 中勾选相关作用域"
    }

    GlassPanel(backdrop, palette) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (activated) {
                    Icons.Rounded.CheckCircle
                } else {
                    Icons.Rounded.Cancel
                },
                contentDescription = null,
                tint = if (activated) palette.accent else Color(0xFFFF6B6B),
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    title,
                    color = palette.content,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(detail, color = palette.secondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun GlassGuideStep(index: Int, text: String, palette: GlassPalette) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(palette.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$index", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            color = palette.content,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The 关于 tab: the module's support, version and privacy notes that used to sit
 * at the bottom of the home page.
 */
@Composable
private fun GlassAbout(
    backdrop: Backdrop,
    palette: GlassPalette,
    onOpenSubpage: (ManagerHomeSubpage) -> Unit,
    listState: LazyListState,
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                .versionName
        }.getOrNull()
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 96.dp, 16.dp, 128.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            GlassAboutHeader(palette, versionName)
        }
        item {
            GlassProvenanceNote(palette)
        }
        item {
            AuthorRow(
                title = "bigdictator",
                tag = "原作者",
                project = "LiquidTab",
                avatar = R.drawable.author_bigdictator,
                url = AUTHOR_COOLAPK_BIGDICTATOR,
                backdrop = backdrop,
                palette = palette,
            )
        }
        item {
            AuthorRow(
                title = "小郑大王",
                tag = "二次开发",
                project = "DouYinLiquidTab",
                avatar = R.drawable.author_xiaozheng,
                url = AUTHOR_COOLAPK_XIAOZHENG,
                backdrop = backdrop,
                palette = palette,
            )
        }
        item {
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                GlassInfoCard(
                    title = "支持环境",
                    lines = listOf("Android 15+", "LSPosed API 101+"),
                    backdrop = backdrop,
                    palette = palette,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    leading = { GlassLeadingIcon(Icons.Outlined.PhoneAndroid, palette) },
                )
                GlassInfoCard(
                    title = "离线与隐私",
                    lines = listOf("本模块完全离线运行，所有数据均在本地处理。"),
                    backdrop = backdrop,
                    palette = palette,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    leading = { GlassLeadingIcon(Icons.Filled.CloudOff, palette) },
                )
            }
        }
        item {
            GlassRow(
                title = "致谢",
                summary = "查看本模块使用的开源项目与依赖",
                backdrop = backdrop,
                palette = palette,
                leading = { GlassLeadingIcon(Icons.Filled.LocalFlorist, palette) },
                onClick = { onOpenSubpage(ManagerHomeSubpage.ACKNOWLEDGEMENTS) },
            )
        }
    }
}

/**
 * The provenance notice above the author cards: the sentence is centred between
 * two hairline rules, so it reads as a caption on the background rather than as
 * yet another card.
 */
@Composable
private fun GlassProvenanceNote(palette: GlassPalette) {
    val rule = palette.secondary.copy(alpha = 0.22f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(rule))
        Text(
            text = "本项目基于LiquidTab改进开发",
            color = palette.secondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
        )
        Box(Modifier.weight(1f).height(1.dp).background(rule))
    }
}

/**
 * The About header: the module's own icon, centred above its nickname and a
 * one-line description.
 */
@Composable
private fun GlassAboutHeader(palette: GlassPalette, versionName: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The 768px export keeps the header crisp at 84dp; the launcher icon
        // maxes out at 192px and would be upscaled here.
        Image(
            painter = painterResource(R.drawable.module_icon),
            contentDescription = "DouYinLiquidTab",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(22.dp)),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "DouYinLiquidTab",
            color = palette.content,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "v${versionName ?: "?"}",
            color = palette.secondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "抖音液态玻璃材质模块",
            color = palette.secondary,
            fontSize = 13.sp,
        )
    }
}

/**
 * Whole-dp offset label: positive values carry no sign, negatives keep `-`, and zero stays plain.
 *
 * `%+.0f` used to print `+0` / `-0` because the sign survived the rounding. Rounding to whole
 * dp with an explicit zero branch keeps the label stable while the slider stays continuous.
 */
private fun formatSignedDp(value: Float): String {
    val rounded = value.roundToInt()
    return if (rounded == 0) "0 dp" else "%d dp".format(rounded)
}

/**
 * The two status readouts on the home page: the Douyin build installed on the
 * device, and the version of this module itself.
 */
@Composable
private fun GlassVersionRow(
    backdrop: Backdrop,
    palette: GlassPalette,
) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val douyinVersion = remember {
        AppCatalog.targets.firstOrNull { it.key == "douyin" }?.packageName?.let { name ->
            runCatching {
                packageManager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0)).versionName
            }.getOrNull()
        }
    }
    val deviceModel = remember {
        android.os.Build.MODEL.orEmpty().trim().ifBlank {
            android.os.Build.MANUFACTURER.orEmpty().trim().ifBlank { "未知机型" }
        }
    }
    val deviceBrand = remember {
        android.os.Build.MANUFACTURER.orEmpty().trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
    val androidLabel = remember {
        "安卓${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）"
    }

    /**
     * Douyin's own build number, read from `assets/ss.properties` inside its APK.
     *
     * versionName is not enough to tell builds apart — a silent APK swap can keep `40.7.0`.
     * That file carries `update_version_code` (and `release_build` with the git sha and
     * build date), which changes on every build. The module's own process can read it the
     * same way the hook does: resolve the APK path from `ApplicationInfo.sourceDir`.
     */
    val douyinBuildCode = remember {
        AppCatalog.targets.firstOrNull { it.key == "douyin" }?.packageName?.let { name ->
            runCatching {
                val sourceDir = packageManager
                    .getApplicationInfo(name, PackageManager.ApplicationInfoFlags.of(0))
                    .sourceDir
                java.util.zip.ZipFile(sourceDir).use { zip ->
                    zip.getEntry("assets/ss.properties")?.let { entry ->
                        zip.getInputStream(entry).bufferedReader().use { reader ->
                            reader.lineSequence()
                                .firstOrNull { it.startsWith("update_version_code") }
                                ?.substringAfter('=')
                                ?.trim()
                        }
                    }
                }
            }.getOrNull()
        }
    }
    val adapted = when {
        douyinBuildCode != null -> douyinBuildCode == "40709900"
        douyinVersion != null -> douyinVersion == "40.7.0"
        else -> false
    }
    val darkTheme = palette.content == Color.White
    val adaptedColor = if (darkTheme) Color(0xB39FE9B4) else Color(0xCC5FD07F)
    val unmatchedColor = if (darkTheme) Color(0xB3FFC978) else Color(0xCCFFB84D)

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassMetric(
            title = "当前版本",
            // The build number when it can be read, otherwise the versionName, so the cell
            // never goes blank on an unfamiliar APK layout.
            value = douyinBuildCode ?: douyinVersion ?: "未安装",
            subtitle = "适配版本：40709900",
            // The old value pill moved onto the status tag: the version itself is plain text,
            // and the green/orange rectangle now marks whether this build is adapted.
            titleBadgeText = when {
                adapted -> "已适配"
                douyinVersion == null -> "未安装"
                else -> "未适配"
            },
            titleBadgeColor = if (adapted) adaptedColor else unmatchedColor,
            backdrop = backdrop,
            palette = palette,
            modifier = Modifier.weight(1f),
            valueFontSize = 18.sp,
            // Both cells use the same 18sp value and 8dp gap, so their title and
            // subtitle lines share a baseline instead of compensating per cell.
            valueGap = 8.dp,
        )
        GlassMetric(
            title = "机型：$deviceModel",
            value = deviceBrand.ifBlank { deviceModel },
            subtitle = androidLabel,
            backdrop = backdrop,
            palette = palette,
            modifier = Modifier.weight(1f),
            valueFontSize = 18.sp,
            valueGap = 8.dp,
        )
    }
}

@Composable
private fun GlassMetric(
    title: String,
    value: String,
    subtitle: String,
    backdrop: Backdrop,
    palette: GlassPalette,
    modifier: Modifier = Modifier,
    /**
     * Kept as a parameter because the two cells hold very different strings: the module
     * version is short ("1.6") while Douyin's build number is eight digits, and at 26sp the
     * latter wraps inside a half-width card.
     */
    valueFontSize: androidx.compose.ui.unit.TextUnit = 26.sp,
    /**
     * Extra gap between the value and the title.
     *
     * The two cells sit side by side, so their title and subtitle lines have to share a
     * baseline. A smaller value font shortens its line box and drags those lines up; this
     * pads the difference back rather than shrinking the type in both cells.
     */
    valueGap: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    valueBackground: Color? = null,
    valueTextColor: Color = Color.White,
    /** Optional status tag drawn after the title, e.g. 「已适配」. */
    titleBadgeText: String? = null,
    titleBadgeColor: Color? = null,
) {
    GlassPanel(backdrop, palette, modifier) {
        Text(
            value,
            color = if (valueBackground != null) valueTextColor else palette.content,
            fontSize = valueFontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (valueBackground != null) {
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(valueBackground)
                    .padding(horizontal = 8.dp, vertical = 1.dp)
            } else {
                Modifier
            },
        )
        if (valueGap > androidx.compose.ui.unit.Dp(0f)) {
            Spacer(Modifier.height(valueGap))
        }
        if (titleBadgeText != null && titleBadgeColor != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, color = palette.secondary, fontSize = 13.sp)
                Text(
                    titleBadgeText,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(titleBadgeColor)
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
        } else {
            Text(title, color = palette.secondary, fontSize = 13.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = palette.secondary, fontSize = 12.sp)
    }
}

/**
 * 设置 ▸ 顶栏 的入口图标：把抖音顶栏的整体轮廓精简成线条图——左边圆形菜单键、
 * 中间胶囊式导航、右边圆形搜索键。24dp 视口、圆角线端，随 accent 着色。
 */
private val TopBarOutline: ImageVector by lazy {
    val stroke = SolidColor(Color.Black)
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 圆形菜单键（cx 3.5, cy 12, r 2.75）
        path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(0.75f, 12f)
            arcTo(2.75f, 2.75f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 6.25f, y1 = 12f)
            arcTo(2.75f, 2.75f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 0.75f, y1 = 12f)
            close()
        }
        // 胶囊式导航（x 8.85..15.15，y 9.45..14.55，两端全圆角 r 2.55）
        path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(11.4f, 9.45f)
            lineTo(12.6f, 9.45f)
            arcTo(2.55f, 2.55f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12.6f, y1 = 14.55f)
            lineTo(11.4f, 14.55f)
            arcTo(2.55f, 2.55f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 11.4f, y1 = 9.45f)
            close()
        }
        // 圆形搜索键（cx 20.5, cy 12, r 2.75）
        path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(17.75f, 12f)
            arcTo(2.75f, 2.75f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 23.25f, y1 = 12f)
            arcTo(2.75f, 2.75f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 17.75f, y1 = 12f)
            close()
        }
    }.build()
}

/**
 * 设置 ▸ 底栏布局 的入口图标：把抖音底栏的整体轮廓精简成线条图——左边全圆角胶囊式
 * 底栏主体、右边独立的圆形加号按钮。24dp 视口、圆角线端，随 accent 着色。
 */
private val BottomBarOutline: ImageVector by lazy {
    val stroke = SolidColor(Color.Black)
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 底栏主体（x 0.75..14.65，y 8.3..15.7，两端全圆角 r 3.7）
        path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4.45f, 8.3f)
            lineTo(10.95f, 8.3f)
            arcTo(3.7f, 3.7f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 10.95f, y1 = 15.7f)
            lineTo(4.45f, 15.7f)
            arcTo(3.7f, 3.7f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 4.45f, y1 = 8.3f)
            close()
        }
        // 圆形加号按钮（cx 20.25, cy 12, r 3.0）
        path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(17.25f, 12f)
            arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 23.25f, y1 = 12f)
            arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 17.25f, y1 = 12f)
            close()
        }
    }.build()
}

@Composable
private fun GlassLeadingIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    palette: GlassPalette,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = palette.accent,
        modifier = Modifier.size(26.dp),
    )
}

/** A compact card showing an optional leading icon, a title and description lines. */
@Composable
private fun GlassInfoCard(
    title: String,
    lines: List<String>,
    backdrop: Backdrop,
    palette: GlassPalette,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
) {
    GlassPanel(backdrop, palette, modifier, contentPadding = PaddingValues(16.dp)) {
        if (leading != null) {
            leading()
            Spacer(Modifier.height(10.dp))
        }
        Text(title, color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        lines.forEach { line ->
            Text(line, color = palette.secondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun GlassRow(
    title: String,
    summary: String,
    backdrop: Backdrop,
    palette: GlassPalette,
    titleTag: String? = null,
    extra: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    GlassPanel(
        backdrop,
        palette,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    if (titleTag != null) {
                        Spacer(Modifier.width(8.dp))
                        AuthorTag(titleTag, palette)
                    }
                }
                if (extra != null) {
                    Text(extra, color = palette.accent, fontSize = 13.sp, lineHeight = 18.sp)
                }
                Text(summary, color = palette.secondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            } else if (onClick != null) {
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Filled.ChevronRight, null, tint = palette.secondary)
            }
        }
    }
}

/**
 * An author card: a circular avatar, the author's nickname and role, the project
 * they are known for here, and a link into their Coolapk profile.
 */
@Composable
private fun AuthorRow(
    title: String,
    tag: String,
    project: String,
    avatar: Int,
    url: String,
    backdrop: Backdrop,
    palette: GlassPalette,
) {
    val uriHandler = LocalUriHandler.current
    GlassRow(
        title = title,
        titleTag = tag,
        extra = project,
        summary = "进入作者酷安主页",
        backdrop = backdrop,
        palette = palette,
        leading = { AuthorAvatar(avatar) },
        onClick = { uriHandler.openUri(url) },
    )
}

/** The small blue role badge shown next to an author's nickname. */
@Composable
private fun AuthorTag(text: String, palette: GlassPalette) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(palette.accent)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * A caption that sits above the card it names.
 */
@Composable
private fun SectionLabel(text: String, palette: GlassPalette) {
    Text(
        text,
        color = palette.secondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

@Composable
private fun AuthorAvatar(avatar: Int) {
    Image(
        painter = painterResource(avatar),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape),
    )
}

@Composable
private fun GlassHomeSubpage(
    page: ManagerHomeSubpage,
    backdrop: Backdrop,
    palette: GlassPalette,
    listState: LazyListState,
) {
    when (page) {
        ManagerHomeSubpage.ACKNOWLEDGEMENTS -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(16.dp, 100.dp, 16.dp, 128.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "感谢以下开源项目为 DYLT 提供基础能力。",
                    color = palette.secondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            item { GlassAckCard("AndroidLiquidGlass (Backdrop)", "液态玻璃渲染核心与示例组件（backdrop 2.0.1 / shapes 1.2.1，Apache-2.0）", backdrop, palette) }
            item { GlassAckCard("AndroidX Jetpack Compose", "BOM 2026.06.01；Activity Compose 1.13.0、Animation、Foundation、Material Icons Extended、Material 3 1.5.0-alpha24、UI 与 Tooling Preview", backdrop, palette) }
            item { GlassAckCard("AndroidX Navigation", "Navigation 3 Runtime 1.1.4 与 NavigationEvent Compose 1.1.2", backdrop, palette) }
            item { GlassAckCard("AndroidX Graphics Shapes", "连续曲线与几何形状支持（1.1.0）", backdrop, palette) }
            item { GlassAckCard("Miuix KMP", "UI、图标、Preference、Blur、Squircle 与 Navigation 3 UI（均为 0.9.3）", backdrop, palette) }
            item { GlassAckCard("LSPosed HiddenApiBypass", "隐藏 API 访问兼容层（6.1）", backdrop, palette) }
            item { GlassAckCard("LSPosed API", "模块接口由项目内的编译期桩提供，运行时由 LSPosed 环境承载", backdrop, palette) }
        }

        ManagerHomeSubpage.NONE -> Unit
    }
}

@Composable
private fun GlassAckCard(title: String, summary: String, backdrop: Backdrop, palette: GlassPalette) {
    GlassPanel(backdrop, palette, contentPadding = PaddingValues(16.dp)) {
        Text(title, color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(summary, color = palette.secondary, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

@Composable
private fun GlassSettings(
    settings: ManagerSettings,
    backdrop: Backdrop,
    palette: GlassPalette,
    onOpenSubpage: (ManagerSettingsSubpage) -> Unit,
    listState: LazyListState,
) {
    val config = settings.glass
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 88.dp, 16.dp, 128.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GlassRow(
                title = ManagerSettingsSubpage.GLASS.title,
                summary = listOfNotNull(
                    "模糊 %.1f".format(config.blurRadius),
                    "折射 %d/%d dp".format(config.refractionHeightDp.toInt(), config.refractionStrengthDp.toInt()),
                    "饱和度 %.2f".format(config.glassSaturation),
                    if (settings.outlineEnabled) "描边开" else "描边关",
                    "光晕 %.2f / %d dp".format(config.bloomGlowStrength, config.bloomGlowDp.toInt()),
                ).joinToString(" · "),
                backdrop = backdrop,
                palette = palette,
                leading = { GlassLeadingIcon(Icons.Filled.AutoAwesome, palette) },
                onClick = { onOpenSubpage(ManagerSettingsSubpage.GLASS) },
            )
        }
        item {
            GlassRow(
                title = ManagerSettingsSubpage.MENU_AND_TEXT.title,
                summary = listOf(
                    hiddenBottomBarLabels(config.hiddenMask).let { hidden ->
                        if (hidden.isEmpty()) "标签全部显示" else "已隐藏 " + hidden.joinToString("、")
                    },
                    customEntryLabelSummary(config.customEntryLabels),
                ).joinToString(" · "),
                backdrop = backdrop,
                palette = palette,
                leading = { GlassLeadingIcon(Icons.Filled.Apps, palette) },
                onClick = { onOpenSubpage(ManagerSettingsSubpage.MENU_AND_TEXT) },
            )
        }
        item {
            GlassRow(
                title = ManagerSettingsSubpage.TOP_BAR.title,
                summary = listOfNotNull(
                    "液态玻璃".takeIf { config.topBarLiquidGlass },
                    topBarTabsSummary(config),
                    "隐藏左侧按钮".takeIf { config.topBarHideLeadingAction },
                    "隐藏右侧按钮".takeIf { config.topBarHideTrailingAction },
                    "缩放 %.2f×".format(config.topBarScale),
                    "距顶 ${formatSignedDp(config.topBarMarginOffsetDp)}",
                ).joinToString(" · "),
                backdrop = backdrop,
                palette = palette,
                leading = { GlassLeadingIcon(TopBarOutline, palette) },
                onClick = { onOpenSubpage(ManagerSettingsSubpage.TOP_BAR) },
            )
        }
        item {
            GlassRow(
                title = ManagerSettingsSubpage.BOTTOM_BAR.title,
                summary = listOfNotNull(
                    bottomBarStyleLabel(config.bottomBarStyle),
                    "高度 ${config.barHeight.toInt()} dp",
                    // Formatted only when set: this row recomposes with every slider frame, and
                    // the 0 case would otherwise allocate a string just to drop it.
                    if (config.bottomMarginOffsetDp.roundToInt() != 0) {
                        "下边距 ${formatSignedDp(config.bottomMarginOffsetDp)}"
                    } else {
                        null
                    },
                    "整页下移".takeIf { config.controlDrop },
                ).joinToString(" · "),
                backdrop = backdrop,
                palette = palette,
                leading = { GlassLeadingIcon(BottomBarOutline, palette) },
                onClick = { onOpenSubpage(ManagerSettingsSubpage.BOTTOM_BAR) },
            )
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "说明",
                    color = palette.secondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                )
                val notes = listOf(
                    "部分参数调整无需重启抖音即生效，如未生效可重启抖音。",
                    "设置调整后需保持模块与抖音运行（配置连接同步），功能生效之后可关闭模块后台。",
                )
                notes.forEach { note ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .padding(top = 9.dp)
                                .size(width = 6.dp, height = 2.dp)
                                .background(palette.secondary, RoundedCornerShape(1.dp)),
                        )
                        Text(note, color = palette.secondary, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
            }
        }
    }
}

/**
 * One settings page. A first-level entry page either carries controls itself
 * ([ManagerSettingsSubpage.BOTTOM_BAR], [ManagerSettingsSubpage.TOP_BAR]) or lists the
 * second-level entries that carry the controls ([ManagerSettingsSubpage.MENU_AND_TEXT],
 * [ManagerSettingsSubpage.GLASS]); every other page is a second-level entry. Every control is
 * carried over from its former category unchanged; only the container it lives in changed.
 */
@Composable
private fun GlassSettingsSubpage(
    settings: ManagerSettings,
    actions: UiActions,
    backdrop: Backdrop,
    palette: GlassPalette,
    page: ManagerSettingsSubpage,
    listState: LazyListState,
    onOpenSubpage: (ManagerSettingsSubpage) -> Unit,
) {
    val config = settings.glass
    LazyColumn(
        // imePadding keeps 文字修改's rename fields and its 保存 button above the keyboard; every
        // other sub-page has no text field, so the inset is zero there.
        modifier = Modifier.fillMaxSize().imePadding(),
        state = listState,
        contentPadding = PaddingValues(16.dp, 100.dp, 16.dp, 128.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (page) {
            // ---- 一级入口：底栏布局，单卡片直接承载全部底栏设置 ----
            ManagerSettingsSubpage.BOTTOM_BAR -> item {
                GlassPanel(backdrop, palette) {
                    GlassSwitchRow(
                        "底栏液态玻璃",
                        if (settings.liquidGlassEnabled) "底栏使用液态玻璃材质"
                        else "底栏保持原生样式",
                        settings.liquidGlassEnabled,
                        backdrop,
                        palette,
                    ) { enabled -> actions.setLiquidGlassEnabled(enabled) }
                    GlassSwitchRow(
                        "整页下移",
                        "一般不用开，开启后将抖音下半屏控件（文案、昵称、头像、点赞等）下沉",
                        config.controlDrop,
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(controlDrop = it)) }
                    GlassStyleChoice(
                        selected = config.bottomBarStyle,
                        backdrop = backdrop,
                        palette = palette,
                    ) { actions.setGlassConfig(config.copy(bottomBarStyle = it)) }
                    GlassPublishIconPreference(config.publishIcon, backdrop, palette) {
                        actions.setGlassConfig(config.copy(publishIcon = it))
                    }
                    GlassSlider(
                        "底栏高度\n45–55 dp",
                        config.barHeight,
                        45f..55f,
                        { "${it.toInt()} dp" },
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(barHeight = it)) }
                    // 底栏下边距 is its own row: the value is an offset around the bar's shipped
                    // screen-relative gap (0 = exactly today's spacing), so it needs no absolute
                    // dp to stay correct across screen sizes.
                    GlassSlider(
                        "底栏下边距\n−15 ~ 45 dp",
                        config.bottomMarginOffsetDp,
                        GlassConfig.BOTTOM_MARGIN_OFFSET_MIN_DP..GlassConfig.BOTTOM_MARGIN_OFFSET_MAX_DP,
                        { formatSignedDp(it) },
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(bottomMarginOffsetDp = it)) }
                }
            }

            // ---- 一级入口：菜单与文字 → 二级入口列表 ----
            ManagerSettingsSubpage.MENU_AND_TEXT -> {
                item {
                    GlassRow(
                        title = ManagerSettingsSubpage.ENTRY_HIDE.title,
                        summary = listOf(
                            hiddenBottomBarLabels(config.hiddenMask).let { hidden ->
                                if (hidden.isEmpty()) "标签全部显示" else "已隐藏 " + hidden.joinToString("、")
                            },
                            customEntryLabelSummary(config.customEntryLabels),
                        ).joinToString(" · "),
                        backdrop = backdrop,
                        palette = palette,
                        leading = { GlassLeadingIcon(Icons.Filled.VisibilityOff, palette) },
                        onClick = { onOpenSubpage(ManagerSettingsSubpage.ENTRY_HIDE) },
                    )
                }
                item {
                    GlassRow(
                        title = ManagerSettingsSubpage.TEXT_EDIT.title,
                        summary = customEntryLabelSummary(config.customEntryLabels),
                        backdrop = backdrop,
                        palette = palette,
                        leading = { GlassLeadingIcon(Icons.Filled.Edit, palette) },
                        onClick = { onOpenSubpage(ManagerSettingsSubpage.TEXT_EDIT) },
                    )
                }
            }

            // ---- 一级入口：玻璃效果 → 二级入口列表 ----
            ManagerSettingsSubpage.GLASS -> {
                item {
                    GlassRow(
                        title = ManagerSettingsSubpage.GLASS_MATERIAL.title,
                        summary = listOfNotNull(
                            "模糊 %.1f".format(config.blurRadius),
                            "折射 %d/%d dp".format(config.refractionHeightDp.toInt(), config.refractionStrengthDp.toInt()),
                            "饱和度 %.2f".format(config.glassSaturation),
                        ).joinToString(" · "),
                        backdrop = backdrop,
                        palette = palette,
                        leading = { GlassLeadingIcon(Icons.Filled.BlurOn, palette) },
                        onClick = { onOpenSubpage(ManagerSettingsSubpage.GLASS_MATERIAL) },
                    )
                }
                item {
                    GlassRow(
                        title = ManagerSettingsSubpage.GLASS_OUTLINE.title,
                        summary = listOfNotNull(
                            if (settings.outlineEnabled) "描边开" else "描边关",
                            "光晕 %.2f / %d dp".format(config.bloomGlowStrength, config.bloomGlowDp.toInt()),
                        ).joinToString(" · "),
                        backdrop = backdrop,
                        palette = palette,
                        leading = { GlassLeadingIcon(Icons.Filled.Flare, palette) },
                        onClick = { onOpenSubpage(ManagerSettingsSubpage.GLASS_OUTLINE) },
                    )
                }
            }

            ManagerSettingsSubpage.TOP_BAR -> item {
                GlassPanel(backdrop, palette) {
                    GlassSwitchRow(
                        "顶栏液态玻璃",
                        if (config.topBarLiquidGlass) "开启液态玻璃材质并精简顶栏布局"
                        else "顶栏保持原生样式",
                        config.topBarLiquidGlass,
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(topBarLiquidGlass = it)) }
                    // 顶栏两侧按钮: two independent switches. A hidden key keeps no glass circle,
                    // no action reserve and no tap target, so the capsule and whatever key is
                    // left are re-centred by the copy's content-hugging width.
                    GlassSwitchRow(
                        "隐藏左侧抽屉按钮",
                        if (config.topBarHideLeadingAction) "左侧按钮已隐藏，剩余内容自动居中"
                        else "左侧显示抖音的抽屉按钮",
                        config.topBarHideLeadingAction,
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(topBarHideLeadingAction = it)) }
                    GlassSwitchRow(
                        "隐藏右侧搜索按钮",
                        if (config.topBarHideTrailingAction) "右侧按钮已隐藏，剩余内容自动居中"
                        else "右侧显示抖音的搜索按钮",
                        config.topBarHideTrailingAction,
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(topBarHideTrailingAction = it)) }
                    // 顶栏标签数量: replaces the hard-coded pair.
                    GlassCountChoice(
                        selected = config.topBarTabCount,
                        backdrop = backdrop,
                        palette = palette,
                    ) { actions.setGlassConfig(config.copy(topBarTabCount = it)) }
                    // 顶栏标签排序: its own page, because dragging nine rows does not fit (or
                    // scroll well) inside this panel.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSubpage(ManagerSettingsSubpage.TOP_BAR_ORDER) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "标签页排序",
                                color = palette.content,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Icon(Icons.Filled.ChevronRight, null, tint = palette.secondary)
                    }
                    GlassSlider(
                        "距屏幕顶部边距",
                        config.topBarMarginOffsetDp,
                        -GlassConfig.TOP_BAR_MARGIN_OFFSET_MAX_DP..GlassConfig.TOP_BAR_MARGIN_OFFSET_MAX_DP,
                        { formatSignedDp(it) },
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(topBarMarginOffsetDp = it)) }
                    GlassSlider(
                        "整体缩放",
                        config.topBarScale,
                        GlassConfig.TOP_BAR_MIN_SCALE..GlassConfig.TOP_BAR_MAX_SCALE,
                        { "%.2f×".format(it) },
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(topBarScale = it)) }
                }
            }

            ManagerSettingsSubpage.TOP_BAR_ORDER -> item {
                // The candidate labels come from the running target's own title bar when it has
                // reported them; the fallback list keeps the page usable before the first report.
                val candidates = remember(settings.appTopTabLabels) { topBarTabCandidates(settings) }
                val labels = remember(config.topBarTabOrder, candidates) {
                    topBarOrderDrafts(config.topBarTabOrder, candidates)
                }
                GlassPanel(backdrop, palette) {
                    Text(
                        "按住拖动调整频道顺序",
                        color = palette.content,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "推荐固定在第一个（最右侧）。前 ${config.topBarTabCount} 个标签按此顺序从右到左显示，其余标签拖到前面即可显示。",
                        color = palette.secondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    GlassTopBarOrderList(
                        labels = labels,
                        shownCount = config.topBarTabCount,
                        fixedFirstLabel = TOP_BAR_FIXED_TAB,
                        palette = palette,
                    ) { updated ->
                        actions.setGlassConfig(
                            config.copy(topBarTabOrder = encodeTopBarTabOrder(updated)),
                        )
                    }
                }
            }

            ManagerSettingsSubpage.GLASS_MATERIAL -> item {
                GlassPanel(backdrop, palette) {
                    GlassSlider(
                        "底栏模糊半径\n默认 2.0",
                        config.blurRadius,
                        0f..16f,
                        { "%.1f".format(it) },
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(blurRadius = it)) }
                    GlassSlider(
                        "折射范围\n默认 15 dp",
                        config.refractionHeightDp,
                        0f..32f,
                        { "${it.toInt()} dp" },
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(refractionHeightDp = it)) }
                    GlassSlider(
                        "折射强度\n默认 15 dp",
                        config.refractionStrengthDp,
                        0f..48f,
                        { "${it.toInt()} dp" },
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(refractionStrengthDp = it)) }
                    GlassSlider(
                        "玻璃底色透明度\n默认 40%",
                        config.glassAlphaScale * 40f,
                        10f..80f,
                        { "${it.toInt()}%" },
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(glassAlphaScale = it / 40f)) }
                    GlassSlider(
                        "整体玻璃饱和度\n1.0 为原样 · 默认 1.5",
                        config.glassSaturation,
                        0.5f..2.5f,
                        { "%.2f".format(it) },
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(glassSaturation = it)) }
                }
            }

            ManagerSettingsSubpage.GLASS_OUTLINE -> item {
                GlassPanel(backdrop, palette) {
                    GlassSwitchRow("描边", "液态玻璃的高光描边", settings.outlineEnabled, backdrop, palette, onChecked = actions.setOutlineEnabled)
                    GlassSlider(
                        "深色模式高光描边强度\n默认 50%",
                        config.darkBarHighlightStrength * 100f,
                        50f..170f,
                        { "${it.toInt()}%" },
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(darkBarHighlightStrength = it / 100f)) }
                    GlassSlider(
                        "描边光晕强度\n0 = 只有细线 · 默认 0",
                        config.bloomGlowStrength,
                        0f..1f,
                        { "%.2f".format(it) },
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(bloomGlowStrength = it)) }
                    GlassSlider(
                        "描边光晕扩散\n0~12 dp · 默认 0",
                        config.bloomGlowDp,
                        0f..12f,
                        { "${it.toInt()} dp" },
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(bloomGlowDp = it)) }
                }
            }

            ManagerSettingsSubpage.TEXT_EDIT -> item {
                // Read once: the option list is the adapted app's own slot table (see
                // bottomBarHideOptions), not something the mask can change.
                val options = remember { bottomBarHideOptions() }
                val stored = config.customEntryLabels
                // The drafts are keyed on the stored value: typing never writes the setting, and a
                // save (or any other settings change that leaves the names alone) reseeds them from
                // the store instead of clobbering what is being typed.
                var drafts by remember(stored) { mutableStateOf(customEntryLabelDrafts(stored)) }
                var saved by remember { mutableStateOf(false) }
                val focus = LocalFocusManager.current
                GlassPanel(backdrop, palette) {
                    options.forEachIndexed { index, option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                option.label,
                                color = palette.content,
                                fontSize = 15.sp,
                                modifier = Modifier.width(48.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            GlassTextField(
                                value = drafts.getOrElse(index) { "" },
                                // The grey text inside the box is the entry's own name, so an empty
                                // box reads as "this entry still shows 首页/朋友/消息/我".
                                placeholder = option.label,
                                palette = palette,
                                modifier = Modifier.weight(1f),
                            ) { typed ->
                                val next = drafts.toMutableList()
                                next[index] = typed.take(GlassConfig.ENTRY_LABEL_MAX_LENGTH)
                                drafts = next
                                saved = false
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    LiquidButton(
                        onClick = {
                            focus.clearFocus()
                            actions.setGlassConfig(
                                config.copy(customEntryLabels = encodeCustomEntryLabels(drafts)),
                            )
                            saved = true
                        },
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("保存", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        if (saved) "已保存，抖音底栏立即生效。"
                        else "留空即恢复原昵称。",
                        color = palette.secondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            ManagerSettingsSubpage.ENTRY_HIDE -> item {
                // Read once: the option list is the adapted app's own slot table (see
                // bottomBarHideOptions), not something the mask can change.
                val options = remember { bottomBarHideOptions() }
                // The bar keeps at least one entry, so at most options.size - 1 switches may be
                // off: once that many are off, the last one still on is locked (greyed) instead
                // of being switchable and then silently refusing the change.
                val hiddenCount = options.count { config.hiddenMask and (1 shl it.index) != 0 }
                val lastOneLocked = hiddenCount >= options.size - 1
                GlassPanel(backdrop, palette) {
                    options.forEach { option ->
                        val bit = 1 shl option.index
                        val shown = config.hiddenMask and bit == 0
                        val locked = lastOneLocked && shown
                        GlassSwitchRow(
                            option.label,
                            when {
                                locked -> "至少要保留一个"
                                shown -> "底栏显示「${option.label}」"
                                else -> "底栏隐藏「${option.label}」"
                            },
                            shown,
                            backdrop,
                            palette,
                            enabled = !locked,
                        ) { next ->
                            actions.setGlassConfig(
                                config.copy(
                                    hiddenMask = if (next) {
                                        config.hiddenMask and bit.inv()
                                    } else {
                                        config.hiddenMask or bit
                                    },
                                ),
                            )
                        }
                    }
                    // 加号按钮 / 消息红点 不是底栏入口：不占 hiddenMask 位，也不参与上面
                    // “至少保留一个入口”的数量锁定，两个可以同时关闭。
                    GlassSwitchRow(
                        "加号按钮",
                        if (config.showPostButton) "显示底栏圆形发布按钮"
                        else "隐藏圆形发布按钮",
                        config.showPostButton,
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(showPostButton = it)) }
                    GlassSwitchRow(
                        "消息红点",
                        if (config.hideMessageBadge) "底栏隐藏消息未读数量红点"
                        else "底栏显示消息未读数量红点",
                        !config.hideMessageBadge,
                        backdrop,
                        palette,
                    ) { actions.setGlassConfig(config.copy(hideMessageBadge = !it)) }
                    Text(
                        if (lastOneLocked) "最多可关闭 ${options.size - 1} 个入口：至少要保留一个入口。"
                        else "隐藏后底栏尺寸保持不变，其余内容自动均匀排列。",
                        color = palette.secondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            ManagerSettingsSubpage.NONE -> Unit
        }
    }
}

/**
 * 底栏显示样式: one capsule per choice, the current one tinted with the accent. A three-state
 * choice does not fit the shared toggle (which is on/off), and a slider would read as a magnitude
 * rather than as three named layouts.
 */
@Composable
private fun GlassStyleChoice(
    selected: Int,
    backdrop: Backdrop,
    palette: GlassPalette,
    onSelected: (Int) -> Unit,
) {
    val chosen = bottomBarStyleOptions.firstOrNull { it.style == selected } ?: bottomBarStyleOptions.first()
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text("图标显示样式", color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            bottomBarStyleOptions.forEach { option ->
                val isSelected = option.style == chosen.style
                LiquidButton(
                    onClick = { if (!isSelected) onSelected(option.style) },
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f),
                    surfaceColor = if (isSelected) palette.accent.copy(alpha = 0.30f) else Color.Unspecified,
                ) {
                    Text(
                        option.label,
                        color = if (isSelected) palette.content else palette.secondary,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 顶栏标签数量: one button per allowed count. Four short choices fit on one row, and the
 * current one is tinted with the accent like 底栏显示样式.
 */
@Composable
private fun GlassCountChoice(
    selected: Int,
    backdrop: Backdrop,
    palette: GlassPalette,
    onSelected: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text("显示标签数量", color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (count in GlassConfig.TOP_BAR_MIN_TABS..GlassConfig.TOP_BAR_MAX_TABS) {
                val isSelected = count == selected
                LiquidButton(
                    onClick = { if (!isSelected) onSelected(count) },
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f),
                    surfaceColor = if (isSelected) palette.accent.copy(alpha = 0.30f) else Color.Unspecified,
                ) {
                    Text(
                        "$count 个",
                        color = if (isSelected) palette.content else palette.secondary,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 顶栏标签排序: the draggable list from the reference sheet.
 *
 * 推荐 is the fixed first row (no handle, never reordered). The other rows drag from their
 * right-hand handle immediately — no long press — and the rows between the original and the
 * preview slot slide out of the way with a spring. Releasing animates the dragged row into
 * its slot and only then commits the order, so the drop has no visual jump.
 */
@Composable
private fun GlassTopBarOrderList(
    labels: List<String>,
    shownCount: Int,
    fixedFirstLabel: String,
    palette: GlassPalette,
    onCommit: (List<String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Keep the dragged order local until the store confirms it. `remember(labels)` would
    // reset this synchronously on every labels change, which could momentarily redraw the
    // pre-commit order right after a drop; the LaunchedEffect below skips that confirmation.
    var order by remember { mutableStateOf(labels) }
    var committedOrder by remember { mutableStateOf<List<String>?>(null) }
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    // True while the released row is animating into its slot; a new grab waits it out.
    var settling by remember { mutableStateOf(false) }
    // One shift animator per label. Keyed by label rather than by row index so a committed
    // reorder can move the base positions and then hand each row its *residual* offset,
    // keeping the visual position continuous (no flash/jump on release).
    val shiftAnims = remember { mutableMapOf<String, Animatable<Float, AnimationVector1D>>() }
    LaunchedEffect(labels) {
        val committed = committedOrder
        if (committed != null && committed == labels) {
            committedOrder = null
            return@LaunchedEffect
        }
        if (draggingIndex < 0 && !settling) {
            order = labels
            committedOrder = null
        }
    }

    // Every row is the same height, including the fixed one: the drop target is computed by
    // dividing the drag distance by this pitch, so a shorter fixed row would make the first
    // move jump two slots (the release/landing mismatch). The divider is part of the item
    // height measured below, which keeps the pitch exact for every row but the last.
    val rowHeight = 56.dp

    val dragTarget = if (draggingIndex >= 1 && rowHeightPx > 0f) {
        (draggingIndex + (dragOffset / rowHeightPx).roundToInt())
            .coerceIn(1, order.lastIndex)
    } else {
        -1
    }

    Column(Modifier.fillMaxWidth()) {
        order.forEachIndexed { index, label ->
            val isFixed = index == 0 && label == fixedFirstLabel
            val isDragging = index == draggingIndex
            // Rows between the dragged row and the preview slot move by one row height.
            val shift = when {
                draggingIndex < 0 || isDragging || rowHeightPx <= 0f -> 0f
                dragTarget > draggingIndex && index in (draggingIndex + 1)..dragTarget -> -rowHeightPx
                dragTarget in 1 until draggingIndex && index in dragTarget until draggingIndex -> rowHeightPx
                else -> 0f
            }
            val rowShift = remember(label) {
                shiftAnims.getOrPut(label) { Animatable(0f) }
            }
            LaunchedEffect(rowShift, shift) {
                rowShift.animateTo(
                    targetValue = shift,
                    // Quick and critically damped: the rows must have reached their preview
                    // slots before the released row finishes its snap, so the commit's
                    // residual hand-off is only a few pixels.
                    animationSpec = spring(stiffness = 1200f, dampingRatio = 0.9f),
                )
            }
            val offsetY = if (isDragging) dragOffset else rowShift.value
            Column(
                Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .offset { IntOffset(0, offsetY.roundToInt()) }
                    .onSizeChanged {
                        if (rowHeightPx <= 0f) rowHeightPx = it.height.toFloat()
                    },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        color = if (index < shownCount || isFixed) palette.content else palette.secondary,
                        fontSize = 15.sp,
                        fontWeight = if (index < shownCount || isFixed) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (index < shownCount) "显示" else "折叠",
                        color = if (index < shownCount) palette.accent
                        else palette.secondary.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(10.dp))
                    if (isFixed) {
                        Box(
                            modifier = Modifier.width(44.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PushPin,
                                contentDescription = "固定",
                                tint = palette.secondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .pointerInput(index, order.size) {
                                    detectDragGestures(
                                        onDragStart = {
                                            if (!settling) {
                                                draggingIndex = index
                                                dragOffset = 0f
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            if (!settling && draggingIndex == index) {
                                                change.consume()
                                                dragOffset += dragAmount.y
                                            }
                                        },
                                        onDragEnd = {
                                            if (!settling && draggingIndex == index) {
                                                val target = if (rowHeightPx > 0f) {
                                                    (index + (dragOffset / rowHeightPx).roundToInt())
                                                        .coerceIn(1, order.lastIndex)
                                                } else {
                                                    index
                                                }
                                                val endOffset = (target - index) * rowHeightPx
                                                scope.launch {
                                                    settling = true
                                                    animate(
                                                        initialValue = dragOffset,
                                                        targetValue = endOffset,
                                                        animationSpec = tween(180),
                                                    ) { value, _ -> dragOffset = value }
                                                    val oldOrder = order
                                                    val movedLabel = oldOrder.getOrNull(index)
                                                    var committed: List<String>? = null
                                                    if (target != index && movedLabel != null) {
                                                        val next = oldOrder.toMutableList()
                                                        val moved = next.removeAt(index)
                                                        next.add(target, moved)
                                                        // Rebase each row's visual offset into the
                                                        // new order before it recomposes. A row
                                                        // whose slot moved by delta keeps its
                                                        // current visual position with
                                                        // `offset - delta`; animating that
                                                        // residual to zero is then the only
                                                        // motion after release, so the other
                                                        // rows cannot flash/jump when the
                                                        // list order changes.
                                                        for (newIndex in next.indices) {
                                                            val label = next[newIndex]
                                                            val oldIndex = oldOrder.indexOf(label)
                                                            if (oldIndex < 0) continue
                                                            val anim = shiftAnims[label] ?: continue
                                                            val delta = (newIndex - oldIndex) * rowHeightPx
                                                            val residual = if (label == movedLabel) {
                                                                0f
                                                            } else {
                                                                anim.value - delta
                                                            }
                                                            anim.snapTo(residual)
                                                        }
                                                        order = next
                                                        committed = next
                                                        committedOrder = next
                                                    }
                                                    draggingIndex = -1
                                                    dragOffset = 0f
                                                    settling = false
                                                    committed?.let(onCommit)
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            if (!settling && draggingIndex == index) {
                                                scope.launch {
                                                    settling = true
                                                    animate(
                                                        initialValue = dragOffset,
                                                        targetValue = 0f,
                                                        animationSpec = tween(140),
                                                    ) { value, _ -> dragOffset = value }
                                                    draggingIndex = -1
                                                    dragOffset = 0f
                                                    settling = false
                                                }
                                            }
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "拖动排序",
                                tint = palette.secondary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
                if (index != order.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(palette.secondary.copy(alpha = 0.12f)),
                    )
                }
            }
        }
    }
}

/**
 * A one-line field for the manager's glass panels.
 *
 * [placeholder] is the entry's own name and is drawn in the panel's secondary (grey) ink, so an
 * empty box shows what the bar would call that entry. The box is a plain tint rather than another
 * backdrop sample: a nested `drawBackdrop` would sample the background *behind* the panel it sits
 * on and read as a hole in the glass.
 */
@Composable
private fun GlassTextField(
    value: String,
    placeholder: String,
    palette: GlassPalette,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = TextStyle(color = palette.content, fontSize = 15.sp),
        cursorBrush = SolidColor(palette.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        decorationBox = { field ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(palette.panelTint)
                    .border(1.dp, palette.secondary.copy(alpha = 0.25f), shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        color = palette.content.copy(alpha = 0.30f),
                        fontSize = 15.sp,
                    )
                }
                field()
            }
        },
    )
}

@Composable
private fun GlassPublishIconPreference(
    icon: String,
    backdrop: Backdrop,
    palette: GlassPalette,
    onPicked: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val encoded = withContext(Dispatchers.IO) { encodePublishIcon(context, uri) }
            busy = false
            if (encoded != null) onPicked(encoded)
        }
    }
    val hasIcon = icon.isNotEmpty()
    val preview = remember(icon) { if (hasIcon) decodePublishIcon(icon) else null }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Circular preview of the icon currently configured.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(palette.panelTint),
                contentAlignment = Alignment.Center,
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = "当前自定义加号图标",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = palette.secondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text("自定义加号图标", color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        busy -> "正在处理图片…"
                        hasIcon -> "已选择自定义图片"
                        else -> "使用默认加号"
                    },
                    color = palette.secondary,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiquidButton(
                onClick = { launcher.launch("image/*") },
                backdrop = backdrop,
                modifier = Modifier.weight(1f),
            ) {
                Text("选择图片", color = palette.content, fontSize = 14.sp)
            }
            if (hasIcon) {
                LiquidButton(
                    onClick = { onPicked("") },
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f),
                    surfaceColor = palette.panelTint,
                ) {
                    Text("恢复默认", color = palette.content, fontSize = 14.sp)
                }
            }
        }
    }
}

/**
 * Decodes the stored icon data-URI (the base64 payload after the first comma)
 * into a bitmap for the preview, or returns null when it is missing or
 * unreadable.
 */
private fun decodePublishIcon(encoded: String): ImageBitmap? {
    val comma = encoded.indexOf(',')
    val payload = if (comma >= 0) encoded.substring(comma + 1) else encoded
    return runCatching {
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}
