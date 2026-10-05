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
import androidx.compose.ui.layout.boundsInWindow
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
    /**
     * 采样节流间隔（毫秒）。
     *
     * ★★ 2026-10-05（用户「渲染依旧卡顿」）：改为**动态节流**。
     *
     * 固定 32ms（≈30fps）虽然让按压玻璃跟手，但**静止时也在 30fps 全屏抓图** ——
     * 那是纯粹的浪费（背景没变，抓到的图一模一样）。
     *
     * 现在按「是否需要即时反馈」分档：
     *  - [activeIntervalMs]（32ms）：有按压 / 拖拽 / 内容滚动等交互时用；
     *  - [idleIntervalMs]（200ms）：静止时用（人手感知不到 5fps 的背景更新）。
     *
     * 交互方通过 [requestImmediateCapture] 或 [setActive] 通知本采样源。
     */
    var captureIntervalMs: Long
        get() = if (active) activeIntervalMs else idleIntervalMs
        set(value) {
            idleIntervalMs = value
            activeIntervalMs = value
        }

    /** 交互中（按压/拖拽/动画）的间隔。 */
    var activeIntervalMs: Long = 32L

    /** 静止时的间隔（人手感知不到 5fps 的背景更新）。 */
    var idleIntervalMs: Long = 200L

    /** 是否处于交互中。 */
    private var active = false

    /**
     * 标记进入/退出交互态。
     *
     * 由按压 / 拖拽 / 滚动方在开始时调 `setActive(true)`、结束时 `setActive(false)`。
     * 进入时顺带请求一次立即抓帧（保证按压第一帧就有正确背景）。
     */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) requestImmediateCapture()
    }

    /**
     * 强制下一次采样（按压等交互事件用）。
     *
     * ★ 让控件在「希望立即反映」时不等节流。
     */
    fun requestImmediateCapture() {
        lastCaptureAt = 0L
    }

    /**
     * 距离下一次允许抓帧还有多少毫秒（已到时间返回 0）。
     *
     * 采样泵用它决定「睡多久」，避免无谓的定时唤醒 ——
     * 静止时一觉睡到 [idleIntervalMs] 之后，交互时立刻醒来。
     */
    fun msUntilNextCapture(): Long {
        val elapsed = SystemClock.uptimeMillis() - lastCaptureAt
        val interval = captureIntervalMs
        return (interval - elapsed).coerceAtLeast(0L)
    }

    /**
     * 需要采样的窗口矩形（顶栏 / 底栏各自登记）。
     *
     * ★ 2026-10-05：「按需采样」的关键 —— 没有它就只能拓全屏，而全屏拓图正是卡顿主因。
     */
    private val wantedRegions = java.util.concurrent.CopyOnWriteArrayList<android.graphics.Rect>()

    /**
     * ★ 2026-10-05：区域登记**已不再参与裁剪**（现在整屏降采样直绘，坐标天然对齐）。
     * 保留这些 API 是为了：
     *  1. 调用方不用改（三个组件还在登记，不会报错）；
     *  2. 将来真要做「只采必要区域」时，登记信息还在。
     */
    @Suppress("unused")
    private val regionRegistrationKept = Unit

    /** 宿主 View（Activity content view），仅作**兜底**采样用。 */
    private var hostViewRef: java.lang.ref.WeakReference<android.view.View>? = null

    /**
     * 内容层在**窗口坐标系**里的原点（px）。
     *
     * ★★ 2026-10-05（修「底栏变灰板」）：快照来自**内容层图层**，
     * 而玻璃元素的 `boundsInWindow()` 是**窗口坐标** —— 两者原点不同，
     * 裁剪前必须把这部分偏移减掉，否则会采到错位的区域。
     */
    var layerOriginX: Int = 0
        private set
    var layerOriginY: Int = 0
        private set

    /** 由 [LowLayerBackdropNode] 上报内容层在窗口中的位置。 */
    fun setLayerOrigin(x: Int, y: Int) {
        layerOriginX = x
        layerOriginY = y
    }

    /** 快照左上角在宿主坐标系里的位置（px）。裁剪时要减去它。 */
    var regionOrigin: Pair<Int, Int> = 0 to 0
        private set

    /** 登记一个需要采样的窗口矩形。 */
    fun requestRegion(rect: android.graphics.Rect?) {
        if (rect == null) return
        if (wantedRegions.none { it == rect }) wantedRegions.add(android.graphics.Rect(rect))
    }

    /** 清空登记。 */
    fun clearRegions() = wantedRegions.clear()

    /** 绑定宿主 View。 */
    fun bindHostView(v: android.view.View?) {
        hostViewRef = if (v == null) null else java.lang.ref.WeakReference(v)
    }

    /** 降采样因子（≥1）。抓到的全尺寸位图会缩到 `1/downscale` 再交给管线。 */
    var downscale: Int = 2

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
        capturing = true
        lastCaptureAt = now
        try {
            // ★★★ 2026-10-05（用户「你自己看着是一个东西吗」——修「底栏变灰板」）：
            //
            // **采样源必须是「内容层」，绝不能含玻璃自己**。
            //
            // 上一版为了省掉 `toImageBitmap()` 的全屏拷贝，改成 `hostView.draw(canvas)`。
            // 但 `hostView` 是整个 Activity content view，**玻璃（底栏/顶栏）就在里面** ——
            // 于是每一帧抓到的图里都含着「上一帧画好的玻璃」，
            // 玻璃再折射它，反复迭代 ⇒ **收敛成一片均匀色块（灰板）**。
            //
            // 高版本 miuix 的 `LayerBackdrop` 没有这个问题：它是**挂在内容层节点上**录制的，
            // 玻璃是它的兄弟节点、不在录制范围内。这就是「采样源」的语义。
            //
            // 所以这里改回 **layer 快照**（`toImageBitmap()`）——
            // 它录的正是 `.lowLayerBackdrop()` 挂的那个内容层，天然不含玻璃。
            val l = layer
            if (l != null) {
                captureByLayer(l)
                return
            }
            // ── 兜底：连图层都没有（尚未挂载）时，才退回 View 绘制。
            hostViewRef?.get()?.let { captureByViewDraw(it) }
        } catch (t: Throwable) {
            AppLogger.w("LowGlass", "背景快照失败：${t.message}")
            // 图层快照失败（极老设备不支持 PixelCopy 等）→ 退回 View 绘制。
            hostViewRef?.get()?.let {
                runCatching { captureByViewDraw(it) }
            }
        } finally {
            capturing = false
        }
    }

    /**
     * 从**内容层图层**抓一帧。
     *
     * 这是主路径 —— 与 miuix 的 `LayerBackdrop` 语义一致（只含内容，不含玻璃）。
     * 抓到的图会立刻缩到 `1/downscale`，大图随即释放。
     */
    private suspend fun captureByLayer(l: GraphicsLayer) {
        val image = l.toImageBitmap()
        val full = image.asAndroidBitmap()
        if (full.width <= 0 || full.height <= 0) return
        val ds = downscale.coerceAtLeast(1)
        val bw = max(1, full.width / ds)
        val bh = max(1, full.height / ds)
        val scaled = if (bw != full.width || bh != full.height) {
            Bitmap.createScaledBitmap(full, bw, bh, true)
                .let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
        } else {
            if (full.config == Bitmap.Config.ARGB_8888) full else full.copy(Bitmap.Config.ARGB_8888, false)
        }
        snapshot = scaled
        // ★ 快照原点 = 内容层左上角在**窗口坐标**里的位置。
        //   玻璃元素的 bounds 是窗口坐标，裁剪时要减掉它（见 LowGlassPipeline.renderForElement）。
        regionOrigin = layerOriginX to layerOriginY
        snapshotId++
    }

    /**
     * 只把「登记的区域」画到降采样位图上。
     *
     * 顶栏 + 底栏合计不到屏幕 20% 面积 —— 这是卡顿的根治。
     */
    private fun captureByViewDraw(hostView: android.view.View) {
        val ds = downscale.coerceAtLeast(1)
        val fullW = hostView.width
        val fullH = hostView.height
        if (fullW <= 0 || fullH <= 0) return

        val bw = max(1, fullW / ds)
        val bh = max(1, fullH / ds)

        // 尺寸不变就复用位图（避免每帧分配）。
        val cur = snapshot
        val bmp = if (cur != null && cur.width == bw && cur.height == bh) cur
        else Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)

        // ★★ 2026-10-05（修正）：**整屏降采样直绘**。
        //
        // 之前试过「只画登记区域」，但那套坐标换算（window → content view）
        // 很容易错位（状态栏偏移、regionOrigin 漏算），而且一旦错位就是「玻璃里看到错的内容」这种难查的现象。
        //
        // 现在改成：**直接把 hostView 画到已降采样的位图上**（不再经过
        // `toImageBitmap()` 的全屏 GPU 合成 + 14MB 拷贝）。
        // 这是卡顿的真正主因；只拓一次就位，而且坐标天然对齐（都是 view 自身坐标）。
        //
        // 代价：仍然要画整屏（但是画到 1/2 尺寸的位图上，像素量 1/4）。
        // 对比 `toImageBitmap()`：后者是「先 GPU 合成全屏→再全屏拷贝→再缩放」，这里只有「直接缩放着画」。
        val canvas = android.graphics.Canvas(bmp)
        canvas.scale(1f / ds, 1f / ds)
        runCatching { hostView.draw(canvas) }

        snapshot = bmp
        // 整屏快照 → 原点就是 (0,0)（与 View 坐标系一致）。
        regionOrigin = 0 to 0
        snapshotId++
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
) : Modifier.Node(), DrawModifierNode, androidx.compose.ui.node.GlobalPositionAwareModifierNode {

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

    /**
     * 上报内容层在**窗口坐标**里的位置。
     *
     * ★★ 2026-10-05（修「底栏变灰板」配套）：快照只含内容层（原点 = 内容层左上角），
     * 而玻璃元素用的是窗口坐标 —— 两者相差这个偏移，裁剪时必须减掉。
     */
    override fun onGloballyPositioned(coordinates: androidx.compose.ui.layout.LayoutCoordinates) {
        // ★ 用 `boundsInWindow`（已是本项目多处在用、确认可用的 API）。
        val b = coordinates.boundsInWindow()
        backdrop.setLayerOrigin(b.left.toInt(), b.top.toInt())
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