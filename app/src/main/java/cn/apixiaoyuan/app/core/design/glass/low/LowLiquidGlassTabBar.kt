package cn.apixiaoyuan.app.core.design.glass.low

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import cn.apixiaoyuan.app.core.design.glass.TabItem
import cn.apixiaoyuan.app.core.design.glass.animation.DampedDragAnimation
import cn.apixiaoyuan.app.core.design.glass.animation.InteractiveHighlight
import cn.apixiaoyuan.app.core.design.icon.AppIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sign

/**
 * **低版本液态玻璃悬浮 Tab 栏** —— `LiquidGlassTabBar` 的 API 24–32 等价实现。
 *
 * # 为什么需要它
 *
 * 高版本的 `LiquidGlassTabBar` 完全依赖 `miuix-blur`（`minSdk = 33`）的
 * `drawBackdrop / blur / lens / highlight`。而 `miuix-blur` 的模糊走
 * `RenderEffect`（API 31+）、折射走 AGSL（API 33+）——
 * 在 Android 7（API 24）上那些效果会**整个消失**（源码里是 `return`，不是降级）。
 *
 * 所以本文件把同一条效果链**逐段搬到 CPU 上**：
 *
 * | 高版本（miuix-blur / AGSL） | 低版本（本文件） |
 * |---|---|
 * | `.layerBackdrop(backdrop)` 录纹理 | [LowGlassBackdrop] + `GraphicsLayer.record` |
 * | `blur(4dp)` → `RenderEffect` | [LowBlur] → `RenderScript.ScriptIntrinsicBlur` |
 * | `lens(24dp, 24dp)` → AGSL | [LensKt] → **AGSL 逐行 CPU 翻译** |
 * | `highlight = BloomStroke(...)` | 本文件 [drawRimHighlight] 的渐变描边 |
 * | `innerShadow` → `BlurEffect` | 本文件 [drawInnerShadow] 的软件阴影 |
 * | `dropShadow` | 本文件 `BlurMaskFilter` 阴影 |
 *
 * # 参数与高版本**同源**
 *
 * 几何、动画、颜色、动画曲线全部照抄（64dp 高、4dp 内边距、56dp 指示器、
 * `CircleShape`、`TabItem` 尺寸、`DampedDragAnimation` 的弹簧参数）——
 * 只有「怎么把像素画出来」这一段换了后端。
 *
 * # 性能（如实说明）
 *
 * CPU 渲染有硬上限，本实现用三招控住：
 *  - **降采样**：背景先缩到 `1/3`（[LowGlassBackdrop.downscale]）再处理；
 *  - **只在需要时算**：背景快照版本号 / 按压进度变化才重算，静止时不烧 CPU；
 *  - **小区域**：每块玻璃只处理自己那块矩形（百微米级像素），不是全屏。
 *
 * 已知代价：内容快速滚动时折射**滞后一拍**（最多 [LowGlassBackdrop.captureIntervalMs]）。
 */
@Composable
internal fun LowLiquidGlassTabBar(
    items: List<TabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: LowGlassBackdrop,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    indicatorColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    activeContentColor: Color = MaterialTheme.colorScheme.primary,
    liquidGlassBlurRadius: Dp = 4.dp,
) {
    if (items.isEmpty()) return

    val pillShape = remember { CircleShape }
    val glassTint = containerColor.copy(alpha = 0.4f)

    val density = LocalDensity.current
    val context = LocalContext.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    val tabsCount = items.size

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) 0f
            else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    val selectedIndexUpdated by rememberUpdatedState(selectedIndex)
    val onSelectUpdated by rememberUpdatedState(onSelect)
    val gestureIndices = remember { IntArray(2) }

    fun indexAt(positionX: Float): Int {
        if (tabWidthPx == 0f) return currentIndex
        val horizontalPaddingPx = with(density) { 4.dp.toPx() }
        val logicalX = if (isLtr) positionX else totalWidthPx - positionX
        return ((logicalX - horizontalPaddingPx) / tabWidthPx)
            .toInt()
            .fastCoerceIn(0, tabsCount - 1)
    }

    // ★ 手势/动画与高版本**逐字一致**（这是观感的关键，不能改）。
    val dampedDragAnimation = remember(animationScope, tabsCount, density, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = currentIndex.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            canDrag = { position -> position.x in 0f..totalWidthPx },
            onDragStarted = { position ->
                gestureIndices[0] = currentIndex
                gestureIndices[1] = indexAt(position.x)
                updateValue(gestureIndices[1].toFloat())
            },
            onDragStopped = {
                val target = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                if (currentIndex != target) {
                    currentIndex = target
                    onSelectUpdated(target)
                }
                updateValue(target.toFloat())
                animationScope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
            },
            onDragCancelled = {
                currentIndex = gestureIndices[0]
                updateValue(gestureIndices[0].toFloat())
                animationScope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0f && dragAmount.x != 0f) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            },
            onTap = {
                if (gestureIndices[1] == gestureIndices[0]) {
                    onSelectUpdated(gestureIndices[1])
                }
            },
        )
    }

    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { selectedIndexUpdated }.collectLatest { index ->
            if (currentIndex != index) {
                currentIndex = index
                dampedDragAnimation.animateToValue(index.toFloat())
            }
        }
    }

    val activateTab = remember(dampedDragAnimation) {
        { index: Int ->
            if (currentIndex != index) {
                currentIndex = index
                onSelectUpdated(index)
            }
            dampedDragAnimation.animateToValue(index.toFloat())
        }
    }

    val tabsContent: @Composable RowScope.() -> Unit = {
        val contentColorLocal = LocalContentColor.current
        items.forEachIndexed { index, item ->
            val isActive = index == currentIndex
            Column(
                modifier = Modifier
                    .defaultMinSize(minWidth = 64.dp)
                    .semantics(mergeDescendants = true) {
                        selected = isActive
                        role = Role.Tab
                        onClick {
                            activateTab(index)
                            true
                        }
                    }
                    .fillMaxHeight()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CompositionLocalProvider(LocalContentColor provides contentColorLocal) {
                    Icon(
                        imageVector = if (isActive) AppIcons.forKeySelected(item.iconKey)
                        else AppIcons.forKey(item.iconKey),
                        contentDescription = item.label,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }

    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { size, _ ->
                Offset(
                    if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                    else size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset,
                    size.height / 2f,
                )
            },
        )
    }

    // ---- 玻璃参数（全部分辨率 px，与高版本的 dp 字面量一一对应） ----
    val blurPx = with(density) { liquidGlassBlurRadius.toPx() }
    val lensHeightPx = with(density) { 24.dp.toPx() }
    val lensAmountPx = with(density) { 24.dp.toPx() }
    val barCornerPx = with(density) { 32.dp.toPx() }        // CircleShape = 高度一半
    val indicatorCornerPx = with(density) { 28.dp.toPx() }  // 56dp / 2

    // 底栏整体：模糊 + 折射（折射带 24dp，与高版本 lens(24.dp, 24.dp) 同参）
    val barBarSpec = LowGlassPipeline.GlassSpec(
        blurRadiusPx = blurPx,
        refractionHeightPx = lensHeightPx,
        refractionAmountPx = lensAmountPx,
        cornerRadii = FloatArray(4) { barCornerPx },
    )

    Box(
        modifier = modifier.width(IntrinsicSize.Min),
        contentAlignment = Alignment.CenterStart,
    ) {
        // ================= 底栏主体（玻璃） =================
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(
                Modifier
                    .onGloballyPositioned { coords ->
                        totalWidthPx = coords.size.width.toFloat()
                        val contentWidthPx = totalWidthPx - with(density) { 8.dp.toPx() }
                        tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                    }
                    .graphicsLayer { translationX = panelOffset }
                    .lowDropShadow(pillShape, isDark = true)
                    .lowGlassSurface(
                        context = context,
                        backdrop = backdrop,
                        spec = { barBarSpec },
                        shape = pillShape,
                        tint = glassTint,
                        highlight = true,
                        progress = { 1f },
                    )
                    .then(interactiveHighlight.modifier)
                    .then(interactiveHighlight.gestureModifier)
                    .then(dampedDragAnimation.modifier)
                    .height(64.dp)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = tabsContent,
            )
        }

        // ================= 选中指示器（深度折射 + 色散） =================
        if (tabWidthPx > 0f) {
            val tabWidthDp = with(density) { tabWidthPx.toDp() }
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .graphicsLayer {
                        val progressOffset = dampedDragAnimation.value * tabWidthPx
                        translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                    }
                    .lowGlassSurface(
                        context = context,
                        backdrop = backdrop,
                        spec = {
                            val progress = dampedDragAnimation.pressProgress
                            LowGlassPipeline.GlassSpec(
                                // 指示器本身不模糊（高版本只挂 lens），保持锐利的镜面感。
                                blurRadiusPx = 0f,
                                refractionHeightPx = with(density) { 10.dp.toPx() } * progress,
                                refractionAmountPx = with(density) { 14.dp.toPx() } * progress,
                                depthEffect = true,
                                chromaticAberration = 0.5f,
                                cornerRadii = FloatArray(4) { indicatorCornerPx },
                            )
                        },
                        shape = pillShape,
                        tint = Color.Transparent,
                        highlight = false,
                        progress = { dampedDragAnimation.pressProgress },
                        indicatorLayer = true,
                    )
                    .height(56.dp)
                    .width(tabWidthDp),
            )
        }
    }
}

// ============================================================================
// 以下为低版本玻璃绘制原语（高版本由 miuix-blur 负责，这里全部自写）
// ============================================================================

/**
 * 玻璃表面 —— 等价于高版本的 `Modifier.drawBackdrop { effects { ... } }`。
 *
 * 流程：[LowGlassBackdrop] 的快照 → 裁出本元素矩形 → [LowGlassPipeline]（模糊 + 折射）
 * → 画进位图 → 叠容器色 → 描高光边。
 */
private fun Modifier.lowGlassSurface(
    context: Context,
    backdrop: LowGlassBackdrop,
    spec: () -> LowGlassPipeline.GlassSpec,
    shape: androidx.compose.ui.graphics.Shape,
    tint: Color,
    highlight: Boolean,
    progress: () -> Float,
    indicatorLayer: Boolean = false,
): Modifier = this then LowGlassSurfaceElement(
    context, backdrop, spec, shape, tint, highlight, progress, indicatorLayer,
)

private class LowGlassSurfaceElement(
    val context: Context,
    val backdrop: LowGlassBackdrop,
    val spec: () -> LowGlassPipeline.GlassSpec,
    val shape: androidx.compose.ui.graphics.Shape,
    val tint: Color,
    val highlight: Boolean,
    val progress: () -> Float,
    val indicatorLayer: Boolean,
) : androidx.compose.ui.node.ModifierNodeElement<LowGlassSurfaceNode>() {

    override fun create(): LowGlassSurfaceNode =
        LowGlassSurfaceNode(context, backdrop, spec, shape, tint, highlight, progress, indicatorLayer)

    override fun update(node: LowGlassSurfaceNode) {
        node.context = context
        node.backdrop = backdrop
        node.spec = spec
        node.shape = shape
        node.tint = tint
        node.highlight = highlight
        node.progress = progress
        node.indicatorLayer = indicatorLayer
        node.invalidateDraw()
    }

    override fun androidx.compose.ui.platform.InspectorInfo.inspectableProperties() {
        name = "lowGlassSurface"
    }

    override fun equals(other: Any?): Boolean =
        other is LowGlassSurfaceElement && other.backdrop === backdrop && other.indicatorLayer == indicatorLayer

    override fun hashCode(): Int = System.identityHashCode(backdrop) * 31 + indicatorLayer.hashCode()
}

/**
 * 玻璃表面的绘制节点。
 *
 * 它做了三件事：
 *  1. 记录自己的**窗口坐标矩形**（`boundsInWindow`）—— 用于从全屏快照里裁出本块；
 *  2. 监听 `backdrop.snapshotId` 与 `progress()`，**变化时才重算**（CPU 保护）；
 *  3. 绘制：玻璃位图 → tint → 高光描边。
 *
 * ⚠️ 计算放 `Dispatchers.Default`（RenderScript 不能在主线程跑）；
 *    算完回主线程只做一次 `drawBitmap`。
 */
private class LowGlassSurfaceNode(
    var context: Context,
    var backdrop: LowGlassBackdrop,
    var spec: () -> LowGlassPipeline.GlassSpec,
    var shape: androidx.compose.ui.graphics.Shape,
    var tint: Color,
    var highlight: Boolean,
    var progress: () -> Float,
    var indicatorLayer: Boolean,
) : androidx.compose.ui.Modifier.Node(),
    androidx.compose.ui.node.DrawModifierNode,
    androidx.compose.ui.node.GlobalPositionAwareModifierNode,
    androidx.compose.ui.node.ObserverModifierNode {

    private var windowBounds: Rect? = null
    private var processed: ImageBitmap? = null

    /** 计算用的协程作用域。后台跑模糊/折射，结果回主线程。 */
    private val computeScope = CoroutineScope(Dispatchers.Default)

    // 触发重算的指纹：快照版本 + 进度（量化到 2% 一档，避免每帧都算）
    private var lastSnapshotId = -1
    private var lastProgressKey = -1

    override fun onGloballyPositioned(coordinates: androidx.compose.ui.layout.LayoutCoordinates) {
        val r = coordinates.boundsInWindow()
        val newRect = Rect(
            r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt(),
        )
        if (newRect != windowBounds) {
            windowBounds = newRect
            lastSnapshotId = -1 // 位置变了 → 必须重算
            invalidateDraw()
        }
    }

    override fun onObservedReadsChanged() {
        // 依赖的状态（snapshotId / progress）在 draw 阶段被读取，变化后进来触发重算。
        invalidateDraw()
    }

    private fun maybeRecompute() {
        val snapshot = backdrop.snapshot ?: return
        val bounds = windowBounds ?: return
        val snapshotId = backdrop.snapshotId

        val p = progress().fastCoerceIn(0f, 1f)
        // 进度量化：2% 一档。指示器按压缩放这种连续动画不需要每帧重算折射。
        val progressKey = (p * 50f).toInt()

        if (snapshotId == lastSnapshotId && progressKey == lastProgressKey) return
        lastSnapshotId = snapshotId
        lastProgressKey = progressKey

        // 指示器在按下前没有折射（progress=0）→ 直接清空，省一次重算。
        if (indicatorLayer && p <= 0.01f) {
            processed = null
            return
        }

        val s = spec()
        if (s.refractionHeightPx <= 0f && s.blurRadiusPx <= 0f) {
            processed = null
            return
        }

        val ds = backdrop.downscale
        // 在后台线程跑：模糊是 RenderScript、折射是逐像素 CPU。
        // 算完切回主线程（withContext 恢复原调度器 = Compose 主线程）。
        computeScope.launch {
            val out = LowGlassPipeline.renderForElement(context, snapshot, bounds, ds, s)
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

        val img = processed
        val shapeOutline = shape.createOutline(size, layoutDirection, this)

        // ★★ 关键修复（2026-10-05）：**先画玻璃，再画子内容**。
        //
        // 此前这里完全没有 drawContent() → 玻璃层画完就结束了，
        // 子内容（Tab 的图标 + 文字、以及所有 AndroidView 之外的内容）
        // **从未被绘制** —— 用户看到的就是「除了那个栏什么都没有」。
        // 正确顺序与高版本一致：玻璃在后（先画），内容在前（后画）。

        if (img != null) {
            // 玻璃层：把处理后的位图铺满元素，按形状裁剪。
            clipPath(clipPathFor(shapeOutline)) {
                drawImage(
                    image = img,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                )
            }
        }

        // 容器色叠加（对应高版本 onDrawSurface 的 drawRect(containerColor)）。
        if (tint != Color.Transparent && tint.alpha > 0f) {
            drawPath(pathFor(shapeOutline), tint)
        }

        // 指示器的明暗自适应层（对应高版本 onDrawSurface 的两段 drawRect）。
        if (indicatorLayer) {
            val p = progress().fastCoerceIn(0f, 1f)
            // 未按下时整体压暗一点；按下时收薄（与高版本的两段 drawRect 同语义）。
            drawPath(pathFor(shapeOutline), Color.Black.copy(alpha = 0.10f * (1f - p)))
            drawPath(pathFor(shapeOutline), Color.Black.copy(alpha = 0.03f * p))
        }

        // 边缘高光（对应高版本 highlight = BloomStroke 双光源）。
        if (highlight) {
            drawRimHighlight(pathFor(shapeOutline))
        }

        // 指示器的内阴影在**内容之后画**（它是压在玻璃边缘内侧的暗圈）。
        if (indicatorLayer) {
            drawInnerShadow(pathFor(shapeOutline), progress().fastCoerceIn(0f, 1f))
        }

        // ★ 子内容最后画（图标 / 文字在玻璃之上）—— 之前漏掉的这一行
        //   导致底栏「什么都没有」。
        drawContent()
    }

    private fun pathFor(outline: Outline): androidx.compose.ui.graphics.Path {
        val path = Path()
        when (outline) {
            is Outline.Rectangle -> path.addRect(outline.rect)
            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is Outline.Generic -> path.addPath(outline.path)
        }
        return path
    }

    private fun clipPathFor(outline: Outline): androidx.compose.ui.graphics.Path =
        pathFor(outline)

    /** 边缘高光：上亮下暗的双光源渐变描边（对齐 BloomStroke 的观感）。 */
    private fun DrawScope.drawRimHighlight(
        path: androidx.compose.ui.graphics.Path,
    ) {
        val strokeWidth = 1.dp.toPx() * density / density // 1dp
        val brush = Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.42f),
            0.35f to Color.White.copy(alpha = 0.12f),
            0.6f to Color.White.copy(alpha = 0.0f),
            0.85f to Color.White.copy(alpha = 0.10f),
            1.0f to Color.White.copy(alpha = 0.26f),
        )
        drawPath(path, brush, style = Stroke(width = strokeWidth))
    }

    /** 内阴影：按下越深、内侧暗圈越明显（对齐 innerShadow(radius = 8dp * progress)）。 */
    private fun DrawScope.drawInnerShadow(
        path: androidx.compose.ui.graphics.Path,
        progress: Float,
    ) {
        if (progress <= 0.01f) return
        val radius = 8.dp.toPx() * progress
        // 内侧柔和暗圈：用「描边 + 弱化」近似 BlurEffect 的内阴影。
        drawPath(
            path = path,
            color = Color.Black.copy(alpha = 0.15f * progress),
            style = Stroke(width = radius),
        )
        drawPath(
            path = path,
            color = Color.Black.copy(alpha = 0.10f * progress),
            style = Stroke(width = radius * 0.5f),
        )
    }
}

/**
 * 投影 —— 对齐高版本的 `Modifier.dropShadow(radius = 10.dp, alpha = 0.1/0.2)`。
 *
 * 低版本用 `BlurMaskFilter`（软件阴影，API 1 就有）绘制：效果与 `dropShadow` 接近，
 * 且不依赖 `RenderNode`。
 */
private fun Modifier.lowDropShadow(
    shape: androidx.compose.ui.graphics.Shape,
    isDark: Boolean,
): Modifier = this.drawBehind {
    val path = Path()
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rectangle -> path.addRect(outline.rect)
        is Outline.Rounded -> path.addRoundRect(outline.roundRect)
        is Outline.Generic -> path.addPath(outline.path)
    }
    val radius = 10.dp.toPx()
    val alpha = if (isDark) 0.2f else 0.1f
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb((alpha * 255).toInt(), 0, 0, 0)
            maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        val native = canvas.nativeCanvas
        native.save()
        native.translate(0f, radius * 0.25f)
        native.drawPath(
            path.asAndroidPath(),
            paint,
        )
        native.restore()
    }
}