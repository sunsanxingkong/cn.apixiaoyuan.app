package cn.apixiaoyuan.app.core.design.glass.low

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import cn.apixiaoyuan.app.core.log.AppLogger
import kotlin.math.max

/**
 * 低版本玻璃采样源 —— 对齐 `miuix-blur` 的 `LayerBackdrop`。
 *
 * # 它替代了什么
 *
 * 高版本用 `rememberLayerBackdrop()`（miuix-blur，minSdk 33）把「内容层」录进
 * GPU 纹理，玻璃控件再从纹理采样。低版本没有 miuix-blur，所以这里用
 * **Compose 自带的 [GraphicsLayer]**（API 21+ 就有分版本实现）做同一件事：
 *
 * ```
 * ① .lowLayerBackdrop(backdrop)   →  layer.record { drawContent() } 录制内容
 * ② backdrop.capture()            →  layer.toImageBitmap() 抓成位图
 * ③ 玻璃层渲染                     →  LowGlassPipeline.render() 模糊 + 折射
 * ```
 *
 * # 图层从哪来
 *
 * 不依赖 `rememberGraphicsLayer()`（该 API 在依赖的 Compose 版本里不存在），
 * 改用**项目内已验证的写法**（见 `liquid/InnerShadow.kt` 的 `InnerShadowNode`）：
 *
 * ```kotlin
 * requireGraphicsContext().createGraphicsLayer()   // 挂载时创建
 * requireGraphicsContext().releaseGraphicsLayer()  // 卸载时释放
 * ```
 *
 * 这是 Compose 官方的图层生命周期管理，低版本（V23 实现）会落到
 * `android.view.RenderNode`（API 21+）。
 *
 * # 抓取机制在低版本为什么可行
 *
 * `GraphicsLayer.toImageBitmap()` 在低版本会走到 Compose 的 `LayerSnapshotV22`
 * —— 它用 `ImageReader` + `PixelCopy`（**API 24 起可用**）把图层拷成位图。
 * 我们的 minSdk 正好是 24，所以这条路成立。
 *
 * # 性能取舍（诚实说明）
 *
 * `toImageBitmap()` 要等一帧合成完成，且拷贝是全尺寸的（1280×2772 ≈ 14 MB）。
 * 所以本类做三件事控制开销：
 *
 *  - **降采样**：抓到后立刻缩到 `1/downscale`，后续模糊/折射都在小图上做；
 *  - **节流**：`captureIntervalMs` 内不重复抓（背景多数时间静止）；
 *  - **单飞**：抓取中不重入。
 *
 * 代价是内容快速滚动时折射会**滞后一拍**。这是 CPU 管线无法避免的；
 * 静态阅读时完全无感。
 */
internal class LowGlassBackdrop {

    /** 录制内容的图层。由挂载的 [LowLayerBackdropNode] 负责创建与释放。 */
    internal var layer: GraphicsLayer? = null
        set(value) {
            field = value
            // 图层换新（旋转 / 重组）时旧快照作废，避免用错尺寸的图做折射。
            if (value === null) snapshot = null
        }

    /** 已抓取好的背景位图（已降采样）。null = 还没抓到过。 */
    var snapshot by mutableStateOf<Bitmap?>(null)
        private set

    /** 快照版本号 —— 每次抓到新图自增，用来驱动玻璃层重绘。 */
    var snapshotId by mutableIntStateOf(0)
        private set

    /** 抓取节流间隔（毫秒）。96ms ≈ 每秒 10 张，足够跟上滚动又不至于压垮 CPU。 */
    var captureIntervalMs: Long = 96L

    /** 降采样因子（≥1）。抓到的全尺寸位图会缩到 `1/downscale` 再交给管线。 */
    var downscale: Int = 3

    private var lastCaptureAt = 0L
    private var capturing = false

    /**
     * 抓取一帧背景。**必须在主线程协程里调用**（`toImageBitmap` 要求主线程）。
     *
     * @param force 跳过节流（首帧、或明确知道内容变了时用）
     */
    suspend fun capture(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastCaptureAt < captureIntervalMs) return
        if (capturing) return
        val l = layer ?: return
        capturing = true
        lastCaptureAt = now
        try {
            val image = l.toImageBitmap()
            val full = image.asAndroidBitmap()
            if (full.width <= 0 || full.height <= 0) return

            val ds = downscale.coerceAtLeast(1)
            val bw = max(1, full.width / ds)
            val bh = max(1, full.height / ds)
            val scaled = if (bw != full.width || bh != full.height) {
                Bitmap.createScaledBitmap(full, bw, bh, true)
            } else {
                full
            }
            snapshot = scaled
            snapshotId++
        } catch (t: Throwable) {
            // 抓取失败不是致命错误：玻璃层会用上一次的快照 / 纯色回退。
            AppLogger.w("LowGlass", "背景快照失败：${t.message}")
        } finally {
            capturing = false
        }
    }
}

/** 建一个低版本玻璃采样源。图层由随后挂上的 [Modifier.lowLayerBackdrop] 创建。 */
@Composable
internal fun rememberLowGlassBackdrop(): LowGlassBackdrop = remember { LowGlassBackdrop() }

/**
 * 把内容录进 [LowGlassBackdrop] 的图层 —— 对齐 miuix-blur 的 `.layerBackdrop(backdrop)`。
 *
 * 用法与高版本一致：挂在**内容层**（如 `HorizontalPager`）上，玻璃控件浮在它之上。
 */
internal fun Modifier.lowLayerBackdrop(backdrop: LowGlassBackdrop): Modifier =
    this then LowLayerBackdropElement(backdrop)

private class LowLayerBackdropElement(
    val backdrop: LowGlassBackdrop,
) : ModifierNodeElement<LowLayerBackdropNode>() {

    override fun create(): LowLayerBackdropNode = LowLayerBackdropNode(backdrop)

    override fun update(node: LowLayerBackdropNode) {
        node.backdrop = backdrop
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "lowLayerBackdrop"
    }

    override fun equals(other: Any?): Boolean =
        other is LowLayerBackdropElement && other.backdrop === backdrop

    override fun hashCode(): Int = System.identityHashCode(backdrop)
}

private class LowLayerBackdropNode(
    var backdrop: LowGlassBackdrop,
) : Modifier.Node(), DrawModifierNode {

    private var ownLayer: GraphicsLayer? = null

    override fun onAttach() {
        // 生命周期照 InnerShadowNode：挂载建图层、卸载释放，交给 GraphicsContext 管。
        val layer = requireGraphicsContext().createGraphicsLayer()
        ownLayer = layer
        backdrop.layer = layer
    }

    override fun onDetach() {
        ownLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        ownLayer = null
        backdrop.layer = null
    }

    override fun ContentDrawScope.draw() {
        val layer = ownLayer
        if (layer == null) {
            // 还没挂载好图层：直接画内容，绝不让内容消失。
            drawContent()
            return
        }
        // 与 miuix-blur 的 LayerBackdrop 相同：先把内容录进图层，再把图层画到屏幕。
        // 录制是 GPU 侧引用，不产生额外像素拷贝开销。
        layer.record {
            this@draw.drawContent()
        }
        this.drawLayer(layer)
    }
}