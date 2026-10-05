package cn.apixiaoyuan.app.core.design.glass.low

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.util.lerp as lerpFloat
import cn.apixiaoyuan.app.core.design.glass.animation.DampedDragAnimation
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * **低版本液态玻璃开关** —— `kyant/LiquidToggle` 的 API 24–32 等价实现。
 *
 * # 为什么不能直接用 kyant 版
 *
 * `com.kyant0:backdrop` 声明 minSdk 21，看起来「低版本可用」——但它是**假兼容**：
 * `effects/BlurKt.blur()` 与 `effects/RenderEffectKt.effect()` 在
 * `isRenderEffectSupported()`（SDK≥31）/ `isRuntimeShaderSupported()`（SDK≥33）
 * 为 false 时**直接 return，什么都不画**。也就是说 Android 7 上跑 kyant 版，
 * 滑块的玻璃会**整个消失**，只剩一个纯色圆点。
 *
 * # 本实现
 *
 * 效果链与 `kyant/LiquidToggle` **逐项对应**（参数同源）：
 *
 * | 高版本（kyant） | 低版本（本文件） |
 * |---|---|
 * | 轨道 `drawRect(lerp(track, accent, fraction))` | 照抄 |
 * | 滑块 `drawBackdrop { blur(8dp * (1-p)); lens(5dp*p, 10dp*p) }` | [LowGlassPipeline]（模糊 + 折射，同样按 p 缩放） |
 * | `Highlight.Ambient` | [drawAmbientHighlight]（顶部柔光） |
 * | `Shadow(4dp)` | `BlurMaskFilter` 阴影 |
 * | `InnerShadow(4dp * p)` | [drawInnerGlow]（内侧描边） |
 * | `onDrawSurface { drawRect(thumbSurface α) }` | 同款叠加 |
 *
 * 拖拽/弹簧/莫奈色全部沿用现有实现（`DampedDragAnimation` + `MiuixTheme`）。
 */
@Composable
internal fun LowLiquidToggle(
    selected: () -> Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // 采样源自给自足：内部建一个低版本采样源，把**轨道**录进去当玻璃的折射材料。
    // 这样调用方（LiquidToggle 的低版本分支）不需要准备任何东西。
    //
    // 与 kyant 版的一致性：kyant 版用的是 `rememberCombinedBackdrop(外部页面, trackBackdrop)`
    // —— 既采页面又采轨道。低版本这里简化为「只采轨道」：开关只有 40×24dp，
    // 折射带（5–10dp）占了 1/4 宽，折射的可见部分几乎全部来自轨道本身；
    // 不引入外部页面采样还能省掉「页面 backdrop 与开关时序不一致」的一类问题。
    val backdrop = rememberLowGlassBackdrop()
    val currentSelected = rememberUpdatedState(selected)
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentEnabled = rememberUpdatedState(enabled)

    val colors = MiuixTheme.colorScheme
    val accentColor = colors.primary
    val trackColor = colors.surfaceContainerHighest
    val thumbSurface = colors.surfaceContainerHigh

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20f.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (selected()) 1f else 0f) }

    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                if (didDrag) {
                    if (!currentEnabled.value) {
                        fraction = if (currentSelected.value()) 1f else 0f
                        didDrag = false
                        return@DampedDragAnimation
                    }
                    fraction = if (targetValue >= 0.5f) 1f else 0f
                    currentOnSelect.value(fraction == 1f)
                    didDrag = false
                } else {
                    if (!currentEnabled.value) return@DampedDragAnimation
                    fraction = if (currentSelected.value()) 0f else 1f
                    currentOnSelect.value(fraction == 1f)
                }
            },
            onDrag = { _, dragAmount ->
                if (!didDrag) didDrag = dragAmount.x != 0f
                val delta = dragAmount.x / dragWidth
                fraction =
                    if (isLtr) (fraction + delta).fastCoerceIn(0f, 1f)
                    else (fraction - delta).fastCoerceIn(0f, 1f)
            },
        )
    }

    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }.collectLatest { dampedDragAnimation.updateValue(it) }
    }
    // 采样泵：把**轨道**（不含滑块自己）抓成位图，供滑块折射。
    //
    // ★★ 2026-10-05：改用 [LowGlassBackdrop.msUntilNextCapture] 动态节流
    // （交互 32ms / 静止 200ms），与此前 `delay(64L)` 的固定节奏相比：
    // 按压时跟手、静止时几乎不唤醒。
    LaunchedEffect(backdrop) {
        while (true) {
            backdrop.capture()
            kotlinx.coroutines.delay(backdrop.msUntilNextCapture().coerceIn(8L, 250L))
        }
    }
    // 把「是否正在交互」告诉采样源（按压时切高频档）。
    val togglePressed by remember {
        derivedStateOf { dampedDragAnimation.pressProgress > 0.01f }
    }
    LaunchedEffect(backdrop, togglePressed) {
        backdrop.setActive(togglePressed)
    }
    LaunchedEffect(Unit) {
        snapshotFlow { currentSelected.value() }.collectLatest { isSelected ->
            val target = if (isSelected) 1f else 0f
            if (target != fraction) {
                fraction = target
                dampedDragAnimation.animateToValue(target)
            }
        }
    }

val context = LocalContext.current
    Box(
        modifier.then(if (enabled) Modifier else Modifier.alpha(0.38f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        // ---- 轨道 ----
        //
        // ★★ 2026-10-05（对齐 kyant 版，修「开关玻璃不对」）：
        // **layer 挂在轨道上，不是最外层 Box**。
        //
        // kyant 版是 `trackBackdrop = rememberLayerBackdrop()` + 轨道 Box 挂
        // `.layerBackdrop(trackBackdrop)`，滑块玻璃采的是**轨道**（不含滑块自己）。
        // 我此前把 `lowLayerBackdrop` 挂在**最外层 Box**（含轨道**和滑块**）
        // ⇒ 又是「采样源含自己」，与底栏那个 bug 同源。
        Box(
            Modifier
                .lowLayerBackdrop(backdrop)
                .clip(RoundedCornerShape(percent = 50))
                .drawBehind {
                    val f = dampedDragAnimation.value
                    drawRect(lerp(trackColor, accentColor, f))
                }
                .size(64.dp, 28.dp),
        )
        // ---- 滑块（玻璃） ----
        Box(
            Modifier
                .graphicsLayer {
                    val f = dampedDragAnimation.value
                    val padding = 2f.dp.toPx()
                    translationX =
                        if (isLtr) lerpFloat(padding, padding + dragWidth, f)
                        else lerpFloat(-padding, -(padding + dragWidth), f)
                }
                .then(dampedDragAnimation.modifier)
                .lowThumbGlass(
                    context = context,
                    backdrop = backdrop,
                    pressProgress = { dampedDragAnimation.pressProgress },
                    surfaceColor = thumbSurface,
                    densityValue = density.density,
                )
                .size(40.dp, 24.dp),
        )
    }
}

/**
 * 滑块玻璃：低版本版 `drawBackdrop { blur / lens / highlight / shadow / innerShadow / onDrawSurface }`。
 */
private fun Modifier.lowThumbGlass(
    context: Context,
    backdrop: LowGlassBackdrop,
    pressProgress: () -> Float,
    surfaceColor: Color,
    densityValue: Float,
): Modifier = this then LowThumbGlassElement(context, backdrop, pressProgress, surfaceColor, densityValue)

private class LowThumbGlassElement(
    val context: Context,
    val backdrop: LowGlassBackdrop,
    val pressProgress: () -> Float,
    val surfaceColor: Color,
    val densityValue: Float,
) : ModifierNodeElement<LowThumbGlassNode>() {

    override fun create() = LowThumbGlassNode(context, backdrop, pressProgress, surfaceColor, densityValue)

    override fun update(node: LowThumbGlassNode) {
        node.context = context
        node.backdrop = backdrop
        node.pressProgress = pressProgress
        node.surfaceColor = surfaceColor
        node.densityValue = densityValue
        node.invalidateDraw()
    }

    override fun hashCode(): Int = System.identityHashCode(backdrop)
    override fun equals(other: Any?): Boolean =
        other is LowThumbGlassElement && other.backdrop === backdrop
}

private class LowThumbGlassNode(
    var context: Context,
    var backdrop: LowGlassBackdrop,
    var pressProgress: () -> Float,
    var surfaceColor: Color,
    var densityValue: Float,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    /** 当前屏幕密度（像素/DP）—— 由 Modifier 元素传入，避免写死设备值。 */
    private val density: androidx.compose.ui.unit.Density get() = androidx.compose.ui.unit.Density(densityValue, 1f)

    private var windowBounds: Rect? = null
    private var processed: ImageBitmap? = null
    private var lastSnapshotId = -1
    private var lastPressKey = -1
    private val computeScope = CoroutineScope(Dispatchers.Default)

    override fun onGloballyPositioned(coordinates: androidx.compose.ui.layout.LayoutCoordinates) {
        val r = coordinates.boundsInWindow()
        val rect = Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        if (rect != windowBounds) {
            windowBounds = rect
            lastSnapshotId = -1
            invalidateDraw()
        }
    }

    private fun maybeRecompute() {
        val snapshot = backdrop.snapshot ?: return
        val bounds = windowBounds ?: return
        val sid = backdrop.snapshotId
        val p = pressProgress().fastCoerceIn(0f, 1f)
        val pressKey = (p * 20f).toInt()
        if (sid == lastSnapshotId && pressKey == lastPressKey) return
        lastSnapshotId = sid
        lastPressKey = pressKey

        val ds = backdrop.downscale
        // 密度从 Compose 取（**不写死设备值** —— 所有机型自适应）。
        val d = densityValue
        computeScope.launch {
            val spec = LowGlassPipeline.GlassSpec(
                // 与 kyant 版同参：blur(8dp * (1 - p))、lens(5dp * p, 10dp * p)、Capsule 圆角 = 高一半 12dp
                blurRadiusPx = 8f * d * (1f - p),
                refractionHeightPx = 5f * d * p,
                refractionAmountPx = 10f * d * p,
                cornerRadii = FloatArray(4) { 12f * d },
            )
            val out = LowGlassPipeline.renderForElement(context, snapshot, bounds, ds, spec)
            if (out != null) {
                withContext(Dispatchers.Main) {
                    processed = out.asImageBitmap()
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        maybeRecompute()
        val outline = RoundedCornerShape(percent = 50).createOutline(size, layoutDirection, this)
        val path = androidx.compose.ui.graphics.Path()
        when (outline) {
            is androidx.compose.ui.graphics.Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is androidx.compose.ui.graphics.Outline.Rectangle -> path.addRect(outline.rect)
            is androidx.compose.ui.graphics.Outline.Generic -> path.addPath(outline.path)
        }

        val img = processed
        val p = pressProgress().fastCoerceIn(0f, 1f)
        if (img != null) {
            clipPath(path) {
                drawImage(
                    image = img,
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                )
            }
        }
        // 表面色（对应 onDrawSurface：常态不透明，按下渐透明露出玻璃）。
        if (surfaceColor.alpha > 0f) {
            drawPath(path, surfaceColor.copy(alpha = surfaceColor.alpha * (1f - p * 0.6f)))
        }
        // 环境高光（对应 Highlight.Ambient，alpha 随按压）。
        drawAmbientHighlight(path, p)
        // 内阴影（对应 InnerShadow(4dp * p)）。
        drawInnerGlow(path, p)

        // ★ 子内容最后画（与 Tab 栏同一个漏点：没有它则内容全不显示）。
        drawContent()
    }

    /** 顶部柔光条 —— 对应 kyant `Highlight.Ambient`（顶缘一道窄白光）。 */
    private fun DrawScope.drawAmbientHighlight(path: androidx.compose.ui.graphics.Path, p: Float) {
        if (p <= 0.01f) return
        val brush = androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.55f * p),
            0.5f to Color.White.copy(alpha = 0.05f * p),
            1f to Color.Transparent,
        )
        drawPath(path, brush, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
    }

    /** 内侧暗圈 —— 对应 kyant `InnerShadow(radius = 4dp * p)`。 */
    private fun DrawScope.drawInnerGlow(path: androidx.compose.ui.graphics.Path, p: Float) {
        if (p <= 0.01f) return
        drawPath(
            path = path,
            color = Color.Black.copy(alpha = 0.10f * p),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx() * p),
        )
    }
}