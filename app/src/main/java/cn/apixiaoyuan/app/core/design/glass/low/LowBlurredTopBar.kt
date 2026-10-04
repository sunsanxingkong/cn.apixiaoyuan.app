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
import cn.apixiaoyuan.app.core.design.glass.low.gl.GlassShaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

    Box(modifier.fillMaxWidth()) {
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

    /** 裁出「顶栏 + overhang」那块，跑 GPU 渐变模糊 + 混色。 */
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

        val passes = ArrayList<GlGlassRenderer.Pass>()
        val texel = floatArrayOf(1f / fw, 1f / fh)
        // 半径/曲线在降采样图上按同一比例缩放（与 Lens.kt 的 /sf 同口径）。
        val radius = floatArrayOf(blurRadiusPx / d)
        val curveArr = floatArrayOf(curve)
        val heightArr = floatArrayOf(fh)

        passes += GlGlassRenderer.Pass(
            GlassShaders.PROGRESSIVE_H,
            mapOf("uTexelSize" to texel, "uRadius" to radius, "uCurve" to curveArr, "uHeight" to heightArr),
        )
        passes += GlGlassRenderer.Pass(
            GlassShaders.PROGRESSIVE_V,
            mapOf("uTexelSize" to texel, "uRadius" to radius, "uCurve" to curveArr, "uHeight" to heightArr),
        )
        // 混色（对应高版本 blendColors = [surface α0.30]，避免深色内容把顶栏压灰）。
        passes += GlGlassRenderer.Pass(
            GlassShaders.BLEND,
            mapOf(
                "uBlendColor" to floatArrayOf(
                    blendColor.red, blendColor.green, blendColor.blue, blendAlpha,
                ),
            ),
        )

        return runCatching { GlGlassRenderer.render(cropped, passes) }.getOrNull()
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