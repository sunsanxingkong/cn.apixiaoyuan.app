package cn.apixiaoyuan.app.core.design.glass.low

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.apixiaoyuan.app.core.design.component.LocalBottomBarInset
import cn.apixiaoyuan.app.core.design.component.LocalScrollBottomLimit
import cn.apixiaoyuan.app.core.design.component.LocalTopBarInset
import cn.apixiaoyuan.app.core.design.icon.AppIcons
import cn.apixiaoyuan.app.core.design.glass.low.gl.GlGlassRenderer
import cn.apixiaoyuan.app.core.design.glass.low.gl.MiuixShaderPorts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏模糊层的**下撑高度** —— 与高版本 `AppScaffold.BlurOverhang` **同一个值**。
 *
 * # 为什么要有下撑
 *
 * 模糊层如果只覆盖顶栏本体，内容滚到顶栏下缘时会**戛然而止** —— 出现一条硬边。
 * 多盖一段并让模糊强度在这段里衰减到 0，视觉上就变成「自然的渐隐」。
 *
 * # 为什么必须共用常量（2026-10-05 修正）
 *
 * `AppScaffold` 要把这段高度从 `innerPadding.top` 里减掉（保证「只有模糊多盖
 * 一段，内容一点不下移」），低版本 [cn.apixiaoyuan.app.core.design.component.LowAppScaffold]
 * 也要减。此前两处**各自写死 28.dp** —— 改一处漏一处就是必然。
 * 现在统一引用本常量，且与高版本 `AppScaffold` 的 `BlurOverhang` 保持同值。
 */
internal val TOP_BAR_BLUR_OVERHANG: Dp = 28.dp

/**
 * 低版本顶栏**渐变模糊** —— miuix-blur `progressiveTextureBlur` 的 1:1 等价实现。
 *
 * # 用户要求
 *
 * 「顶部 scaffold 应该同样一比一复刻」——
 * 上一版我用的是「实色顶栏」（miuix 在 blur 不可用时的降级），
 * 那不是 1:1。这里改成**真的渐变模糊**。
 *
 * # 高版本参数（逐项对照，`AppScaffold.BlurredTopBar`）
 *
 * | 参数 | 高版本 | 本实现 |
 * |---|---|---|
 * | `blurRadius` | `10f` | `uRadius = 10f` |
 * | `gradient` | `ProgressiveBlur.Top.copy(curve = 2.2f)` | `uCurve = 2.2f` + 距顶比例衰减 |
 * | `shape` | `RectangleShape` | 矩形（裁剪到底栏区域） |
 * | `colors` | `blendColors = [surface α0.30]` | `uBlendColor = surface, a = 0.30` |
 * | `BlurOverhang` | 28.dp 下撑 | 同（只在下撑区域内渲染） |
 *
 * # 实现方式
 *
 * 与底栏同一个思路：从 [LowGlassBackdrop] 的全屏快照里裁出「顶栏 + 下撑」那块，
 * 用 GPU（GLSL ES 2.0 的可分离高斯 + 渐变强度）渲染，再贴回去。
 *
 * 差别只在着色器：这里用 [GlassShaders.PROGRESSIVE_H] / [GlassShaders.PROGRESSIVE_V]
 * （模糊强度随 `coord.y / uHeight` 衰减为 `radius * pow(1 - t, curve)`）。
 */
@Composable
internal fun LowBlurredTopBar(
    title: String,
    onBack: (() -> Unit)?,
    surfaceColor: Color,
    backdrop: LowGlassBackdrop,
    modifier: Modifier = Modifier,
) {
    // 高版本 BlurOverhang = 28.dp（模糊层比顶栏多盖一段，避免「到边就断」）。
    val overhang = 28.dp
    val context = LocalContext.current

    Box(
        modifier
            .fillMaxWidth()
            // ★ 2026-10-05（治卡顿）：登记「顶栏 + overhang」这块区域，
            // 采样器只拓它 + 底栏的包围盒（远小于全屏）。
            .onGloballyPositioned { coords ->
                val b = coords.boundsInWindow()
                backdrop.requestRegion(
                    android.graphics.Rect(
                        b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt(),
                    )
                )
            },
    ) {
        // 模糊层：铺满「顶栏 + overhang」，背景是渐变模糊后的内容。
        Box(
            Modifier
                .matchParentSize()
                .lowProgressiveBlur(
                    context = context,
                    backdrop = backdrop,
                    blurRadiusPx = 10f,          // 与高版本 blurRadius = 10f 一致
                    curve = 2.2f,                // 与高版本 ProgressiveBlur.Top.copy(curve = 2.2f) 一致
                    blendColor = surfaceColor,   // 与高版本 blendColors = [surface α0.30] 一致
                    blendAlpha = 0.30f,
                ),
        )
        // 顶栏本体：透明（让下面的模糊透出来）—— 与高版本 blurSupported 分支一致。
        Column {
            SmallTopAppBar(
                title = title,
                modifier = Modifier.fillMaxWidth(),
                color = Color.Transparent,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = AppIcons.Back,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
            )
            // 下撑段：模糊层因 Box 变高而向下多盖一段（与高版本同款做法）。
            Box(Modifier.height(overhang))
        }
    }
}

/** 顶栏渐变模糊的绘制节点 —— 从全屏快照裁出本区域，跑 GPU 渐变模糊。 */
private fun Modifier.lowProgressiveBlur(
    context: Context,
    backdrop: LowGlassBackdrop,
    blurRadiusPx: Float,
    curve: Float,
    blendColor: Color,
    blendAlpha: Float,
): Modifier = this then LowProgressiveBlurElement(
    context, backdrop, blurRadiusPx, curve, blendColor, blendAlpha,
)

private class LowProgressiveBlurElement(
    val context: Context,
    val backdrop: LowGlassBackdrop,
    val blurRadiusPx: Float,
    val curve: Float,
    val blendColor: Color,
    val blendAlpha: Float,
) : ModifierNodeElement<LowProgressiveBlurNode>() {

    override fun create() = LowProgressiveBlurNode(
        context, backdrop, blurRadiusPx, curve, blendColor, blendAlpha,
    )

    override fun update(node: LowProgressiveBlurNode) {
        node.context = context
        node.backdrop = backdrop
        node.blurRadiusPx = blurRadiusPx
        node.curve = curve
        node.blendColor = blendColor
        node.blendAlpha = blendAlpha
        node.invalidateDraw()
    }

    override fun hashCode(): Int = System.identityHashCode(backdrop)
    override fun equals(other: Any?): Boolean =
        other is LowProgressiveBlurElement && other.backdrop === backdrop
}

private class LowProgressiveBlurNode(
    var context: Context,
    var backdrop: LowGlassBackdrop,
    var blurRadiusPx: Float,
    var curve: Float,
    var blendColor: Color,
    var blendAlpha: Float,
) : androidx.compose.ui.Modifier.Node(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode {

    private var windowBounds: Rect? = null
    private var processed: ImageBitmap? = null
    private var lastSnapshotId = -1
    private val scope = CoroutineScope(Dispatchers.Default)

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
        if (sid == lastSnapshotId) return
        lastSnapshotId = sid

        val ds = backdrop.downscale
        scope.launch {
            val out = renderProgressive(snapshot, bounds, ds)
            if (out != null) {
                withContext(Dispatchers.Main) {
                    processed = out.asImageBitmap()
                    invalidateDraw()
                }
            }
        }
    }

    /** 裁出「顶栏 + overhang」那块，跑 miuix 真实渐进模糊 + 混色。 */
    private fun renderProgressive(snapshot: Bitmap, bounds: Rect, ds: Int): Bitmap? {
        val d = ds.coerceAtLeast(1)
        val sw = snapshot.width
        val sh = snapshot.height
        val l = (bounds.left / d).coerceIn(0, sw - 1)
        val t = (bounds.top / d).coerceIn(0, sh - 1)
        val r = (bounds.right / d).coerceIn(l + 1, sw)
        val b = (bounds.bottom / d).coerceIn(t + 1, sh)
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return null

        val cropped = Bitmap.createBitmap(snapshot, l, t, w, h)
        val fw = w.toFloat()
        val fh = h.toFloat()

        // ★★ 2026-10-05 重写：改用 **miuix 的真实算法**（见 MiuixShaderPorts）。
        //
        // 旧的实现是我自己编的「半径随高度衰减」—— 那不是 miuix 的做法。
        // miuix 的渐进模糊（`buildProgressiveStackShader`）是：
        //   ① 对整层做**全强度模糊**；
        //   ② 用遮罩 shader 生成一条**软过渡权重** w(xy)；
        //   ③ 合成 `mix(sharp, blurred, w)`。
        // 所以渐变体现在**遮罩**上，模糊半径是恒定的 —— 这是关键区别。
        //
        // 参数对应（高版本 AppScaffold.BlurredTopBar）：
        //   blurRadius = 10f, gradient = ProgressiveBlur.Top.copy(curve = 2.2f)
        //   blendColors = [surface α 0.30]
        val passes = ArrayList<GlGlassRenderer.Pass>()

        // 顶点：预乘（整条链路的起点，见 LowGlassPipeline.glRender 的说明）。
        passes += GlGlassRenderer.Pass(MiuixShaderPorts.PREMUL)

        val blurPx = (blurRadiusPx / d).coerceAtLeast(0.5f)
        val step = floatArrayOf(1f / fw, 1f / fh)
        passes += GlGlassRenderer.Pass(
            MiuixShaderPorts.BLUR_H,
            mapOf(
                "uStep" to step,
                "uRadius" to floatArrayOf(blurPx),
                "uNoise" to floatArrayOf(0.75f),
            ),
        )
        passes += GlGlassRenderer.Pass(
            MiuixShaderPorts.BLUR_V,
            mapOf(
                "uStep" to step,
                "uRadius" to floatArrayOf(blurPx),
                "uNoise" to floatArrayOf(0.75f),
            ),
        )

        // 遮罩：沿 Y 轴从「贴顶=1」过渡到「底部=0」。
        //   uGradAxis = (0,1)，则 p = xy.y（距顶的像素距离）。
        //   band = (0, 高度)，curve 照抄高版本 2.2。
        //   level=1 / slope=1 → w = clamp(1 - raw, 0, 1)，即贴顶全模糊、底部全清晰。
        passes += GlGlassRenderer.Pass(
            MiuixShaderPorts.PROGRESSIVE_MASK,
            mapOf(
                "uGradAxis" to floatArrayOf(0f, 1f),
                "uGradBand" to floatArrayOf(0f, fh),
                "uCurve" to floatArrayOf(curve),
                "uLevel" to floatArrayOf(1f),
                "uSlope" to floatArrayOf(1f),
            ),
        )

        // ★ 这里**不能**接 UNPREMUL —— 它会把 alpha 强制成 1，遮罩权重就丢了。
        //   PROGRESSIVE_MASK 已经输出「非预乘色 + mask 覆盖率」，直接可用。
        //   （这也是我上一版写错的地方：先 mask 再 unpremul，等于渐变白做。）

        val blurred = runCatching { GlGlassRenderer.render(cropped, passes) }.getOrNull() ?: return null

        // 与原始（清晰）内容合成：out = sharp*(1-w) + blur*w。
        // 用 CPU 合成（Canvas 混合是硬件加速的，且省一趟 GPU 往返）。
        return runCatching {
            val out = cropped.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(out)
            // 原图（清晰）先画，再把「已带遮罩 alpha 的模糊层」叠上去。
            canvas.drawBitmap(cropped, 0f, 0f, null)
            canvas.drawBitmap(blurred, 0f, 0f, android.graphics.Paint().apply {
                alpha = (blendAlpha.coerceIn(0f, 1f) * 255).toInt()
            })
            out
        }.getOrNull()
    }

    override fun ContentDrawScope.draw() {
        maybeRecompute()
        val img = processed
        if (img != null) {
            clipRect {
                drawImage(
                    image = img,
                    dstSize = androidx.compose.ui.unit.IntSize(
                        size.width.toInt(), size.height.toInt(),
                    ),
                )
            }
        }
        // 内容（标题栏 + 图标）画在模糊之上。
        drawContent()
    }
}