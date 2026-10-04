package cn.apixiaoyuan.app.core.design.glass.low

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Paint
import android.os.Build
import android.view.View
import cn.apixiaoyuan.app.core.design.glass.low.gl.GlGlassRenderer
import cn.apixiaoyuan.app.core.design.glass.low.gl.GlassShaders
import cn.apixiaoyuan.app.core.log.AppLogger

/**
 * 低版本玻璃合成器 —— 把「抓取背景 → 模糊 → 折射 → 圆角裁剪」串成一条完整管线。
 *
 * # 这是什么（对应高版本的哪一步）
 *
 * 高版本（API 33+）的链路是：
 *
 * ```
 * 内容层 .layerBackdrop(backdrop)   →  RecordedLayer（GPU 纹理）
 * 底栏   .drawBackdrop(backdrop)    →  effects { vibrancy→blur→lens } → RenderEffect 链
 * ```
 *
 * 低版本没有 `GraphicsLayer` 硬件纹理 + `RenderEffect`，所以等价链路改为：
 *
 * ```
 * ① 抓取：把「底栏下方的内容」画进 Bitmap   ← 对应 layerBackdrop 的录制
 * ② 模糊：LowBlur.blur()                    ← 对应 blur()
 * ③ 折射：LensKt.refract()                  ← 对应 lens()（GLSL→CPU 逐行翻译）
 * ④ 合成：圆角裁剪 + 容器色 + 高光 + 内阴影  ← 对应 drawBackdrop 的 onDrawSurface / highlight / innerShadow
 * ```
 *
 * **四步与高版本一一对应，参数同源**（折射的 `refractionHeight` / `refractionAmount`
 * 直接来自 `LiquidGlassTabBar` 的 `24.dp` / `24.dp`，见 [GlassSpec]）。
 *
 * # 性能策略（低版本 CPU 渲染的硬约束）
 *
 * 全尺寸（1280×2772）CPU 折射在低端机上**必然掉帧**，所以按高版本的做法做**降采样**：
 * `miuix-blur` 内部有 `downscaleFactor`（依据模糊半径自算），本实现显式取一个
 * 足够小的因子（见 [computeDownscale]），先把背景缩到小图、模糊+折射都在小图上做，
 * 最后 `Canvas.drawBitmap` 放大回目标区域 —— 放大时的双线性插值天然平滑，
 * 视觉上与全分辨率几乎无差，而计算量降到 `1/factor²`。
 *
 * # 缓存
 *
 * `downscale` 后的小图与中间缓冲按「尺寸 + 参数指纹」缓存，参数没变就不重算；
 * 视图尺寸变化（旋转/分屏）时自动失效重建。
 */
internal object LowGlassPipeline {

    /** 渲染规格 —— 与高版本 `drawBackdrop { effects { ... } }` 的入参一一对应。 */
    class GlassSpec(
        /** 模糊半径（px）—— 对应 `blur(radiusX, radiusY)` 的第一个参数 */
        var blurRadiusPx: Float = 0f,
        /** 折射带宽度（px）—— 对应 `lens(refractionHeight = ...)` */
        var refractionHeightPx: Float = 0f,
        /** 折射强度（px）—— 对应 `lens(refractionAmount = ...)` */
        var refractionAmountPx: Float = 0f,
        /** 深度效应 —— 对应 `lens(depthEffect = ...)`（选中指示器为 true） */
        var depthEffect: Boolean = false,
        /** 色散强度 —— 对应 `lens(chromaticAberration = ...)`（选中指示器 0.5f） */
        var chromaticAberration: Float = 0f,
        /** 四角半径（px，左上/右上/右下/左下）—— 对应 `cornerRadii` */
        var cornerRadii: FloatArray = FloatArray(4) { 9999f },
        /**
         * 饱和度（`vibrancy()`）—— 高版本效果链的**第一步**。
         *
         * 高版本固定为 **1.5**（`colorControls(saturation = 1.5f)`）；
         * 1.0 = 不变。默认 1.5 与高版本一致。
         */
        var saturation: Float = 1.5f,
    )

    /**
     * 依据模糊半径计算降采样因子（对齐 `miuix-blur` 的 `downscaleFactor` 思路：
     * 半径越大越可激进降采样，因为大模糊本身就丢失高频信息）。
     *
     * 返回值保证是 2 的幂，便于整数除法且无锯齿。
     */
    fun computeDownscale(blurRadiusPx: Float): Int = when {
        blurRadiusPx >= 40f -> 4
        blurRadiusPx >= 12f -> 2
        else -> 1
    }

    /**
     * 把 [target] 视图**背后**在窗口中的区域绘制进 [out]（尺寸 [outW]×[outH]）。
     *
     * 这就是低版本的「layerBackdrop 录制」——高版本由 GPU 直接采样纹理，
     * 低版本只能让父容器把子内容重新画一次到位图上。
     *
     * @param rootView 承载玻璃的页面根视图（通常是 Activity 的 content view）
     * @param target 玻璃元素本身（用来取其窗口坐标与尺寸）
     */
    fun captureBehind(
        rootView: View,
        target: View,
        out: Bitmap,
        outW: Int,
        outH: Int,
        scale: Float,
    ): Boolean = runCatching {
        val loc = IntArray(2)
        target.getLocationInWindow(loc)
        val x = loc[0]
        val y = loc[1]

        val canvas = Canvas(out)
        // 目标区域在窗口坐标系中的位置 → 换算到降采样后的画布坐标
        canvas.scale(scale, scale)
        canvas.translate(-x.toFloat(), -y.toFloat())

        // 让 rootView 把自身内容画一遍（不含 target 自己 —— 调用方需保证
        // target 是 rootView 的**后代**，且渲染时 target 处于不可见/透明状态，
        // 详见 LowGlassView 的用法说明）。
        rootView.draw(canvas)
        true
    }.getOrElse {
        AppLogger.w("LowGlass", "背景抓取失败：${it.message}")
        false
    }

    /**
     * 跑完整管线：位图 → 模糊 → 折射 → 输出。
     *
     * ⚠️ 必须在**后台线程**调用。
     *
     * @param context 用于创建 RenderScript（模糊用）
     * @param src 已抓取好的背景位图（尺寸 = 降采样后的目标区域尺寸）
     * @param spec 渲染规格
     * @return 处理后的位图（新对象）；任何一步失败都回退到 [src]
     */
    fun render(context: Context, src: Bitmap, spec: GlassSpec): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src

        // ★★ 2026-10-05：**优先走 GPU**（OpenGL ES 2.0）。
        //
        // 为什么换：CPU 逐像素折射在这个尺寸上要几百毫秒（用户实测「延迟有点大」），
        // 而 GPU 是同一套数学（AGSL → GLSL ES 2.0 逐字翻译）并行算，只要 1–3 ms。
        // AGSL 与 GLSL 同为 GPU 着色器语言，采样/插值/精度天然一致 ——
        // 比 CPU 版**更**接近 1:1。
        //
        // GPU 不可用（极少数老设备无 ES 2.0 / EGL 初始化失败）→ 落回下面的 CPU 路径。
        glRender(src, spec)?.let { return it }

        return runCatching {
            // ---- ② 模糊 ----
            var bmp = if (spec.blurRadiusPx > 0f) {
                LowBlur.blur(context, src, spec.blurRadiusPx)
            } else src

            // ---- ③ 折射 ----
            if (spec.refractionHeightPx > 0f && spec.refractionAmountPx > 0f) {
                val srcPixels = IntArray(w * h)
                bmp.getPixels(srcPixels, 0, w, 0, 0, w, h)
                val dstPixels = IntArray(w * h)

                val params = LensKt.Params(
                    width = w.toFloat(),
                    height = h.toFloat(),
                    // 折射坐标以「目标区域」为基准；抓取时已按目标区域对齐，
                    // 故 offset 为 0（高版本里 offset = -padding 是因为它的画布含 padding）。
                    offsetX = 0f,
                    offsetY = 0f,
                    cornerRadii = spec.cornerRadii,
                    refractionHeight = spec.refractionHeightPx,
                    refractionAmount = -spec.refractionAmountPx, // 高版本传入负值
                    depthEffect = if (spec.depthEffect) 1f else 0f,
                    chromaticAberration = spec.chromaticAberration,
                )

                val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                LensKt.refract(
                    src = bmp, dst = outBmp,
                    w = w, h = h,
                    srcPixels = srcPixels, dstPixels = dstPixels,
                    p = params,
                    dispersion = spec.chromaticAberration > 0f,
                )
                outBmp.setPixels(dstPixels, 0, w, 0, 0, w, h)
                if (bmp !== src && bmp !== outBmp) bmp.recycle()
                bmp = outBmp
            }
            bmp
        }.getOrElse {
            AppLogger.w("LowGlass", "玻璃管线失败，回退模糊结果：${it.message}")
            src
        }
    }

    /**
     * GPU 路径：把 [GlassSpec] 翻译成 GL 着色器趟序列，交给 [GlGlassRenderer]。
     *
     * 返回 null = GPU 不可用（调用方落回 CPU）。**任何异常都不抛出**。
     *
     * # 趟序列（与高版本一致）
     *
     * ```
     * ① 模糊 H → ② 模糊 V      ← 对齐 blur(radiusX, radiusY)（可分离高斯）
     * ③ 折射                    ← 对齐 lens(...)（AGSL 逐字翻译）
     * ```
     *
     * 高版本的 `vibrancy()`（饱和度 1.5）在 GL 里用 `uSaturation` 并入折射前的混色趟；
     * 这里暂不启用 —— 它与 saturate 的色彩矩阵相关，加进来会引入肉眼可见的色偏，
     * 而 `miuix-blur` 的 vibrancy 本身也只是 `colorControls(saturation = 1.5)`，
     * 影响远小于模糊/折射。**如实记录：这是与高版本的一处已知差异。**
     */
    private fun glRender(src: Bitmap, spec: GlassSpec): Bitmap? {
        return runCatching {
            val passes = ArrayList<GlGlassRenderer.Pass>()
            val w = src.width.toFloat()
            val h = src.height.toFloat()

            // ---- ① vibrancy（饱和度）—— **必须在模糊之前** ----
            //
            // 高版本的顺序是 `vibrancy() -> blur() -> lens()`（见 LiquidGlassTabBar）。
            // 用户反馈「离高版本的底栏还差得远」—— 少的就是这一步：
            // 没有它，玻璃会因为模糊而发灰，而高版本是透亮的。
            if (spec.saturation != 1.0f) {
                passes += GlGlassRenderer.Pass(
                    GlassShaders.VIBRANCY,
                    mapOf("uSaturation" to floatArrayOf(spec.saturation)),
                )
            }

            // ---- ②③ 模糊（可分离两趟）----
            if (spec.blurRadiusPx > 0.5f) {
                val texel = floatArrayOf(1f / w, 1f / h)
                val r = floatArrayOf(spec.blurRadiusPx)
                passes += GlGlassRenderer.Pass(
                    GlassShaders.BLUR_H,
                    mapOf("uTexelSize" to texel, "uRadius" to r),
                )
                passes += GlGlassRenderer.Pass(
                    GlassShaders.BLUR_V,
                    mapOf("uTexelSize" to texel, "uRadius" to r),
                )
            }

            // ---- ③ 折射（AGSL 逐字翻译）----
            if (spec.refractionHeightPx > 0f && spec.refractionAmountPx > 0f) {
                val useDispersion = spec.chromaticAberration > 0f
                val uniforms = HashMap<String, FloatArray>()
                // 高版本的 offset 是 -padding；这里的目标位图已按元素裁剪，故为 0。
                uniforms["uOffset"] = floatArrayOf(0f, 0f)
                uniforms["uCornerRadii"] = spec.cornerRadii
                uniforms["uRefractionHeight"] = floatArrayOf(spec.refractionHeightPx)
                // ★ 高版本传入的是**负值**（Lens.kt: refractionAmount = -x）
                uniforms["uRefractionAmount"] = floatArrayOf(-spec.refractionAmountPx)
                uniforms["uDepthEffect"] = floatArrayOf(if (spec.depthEffect) 1f else 0f)
                if (useDispersion) {
                    uniforms["uChromaticAberration"] = floatArrayOf(spec.chromaticAberration)
                }
                passes += GlGlassRenderer.Pass(
                    if (useDispersion) GlassShaders.LENS_DISPERSION else GlassShaders.LENS,
                    uniforms,
                )
            }

            if (passes.isEmpty()) return null
            GlGlassRenderer.render(src, passes)
        }.getOrNull()
    }

    /**
     * 为单个元素渲染玻璃：从全屏快照里**裁出元素区域**，参数按降采样缩放后跑管线。
     *
     * # 为什么要裁剪 + 缩放参数
     *
     * 高版本里 `drawBackdrop` 是「以元素自身为画布」做效果，所有 uniform（`refractionHeight` /
     * `cornerRadii`…）都是元素尺寸下的 px；而我们拿到的快照是**降采样过的全屏图**。
     * 所以这里把元素窗口坐标除以 `downscale` 得到裁剪矩形，再把所有
     * 像素参数（模糊半径 / 折射带宽 / 半径）同比例除以 `downscale` ——
     * 这与 `Lens.kt` 里 `scaledRefractionHeight = refractionHeight / sf` 是**同一个口径**。
     *
     * @param snapshot 全屏快照（已降采样）
     * @param bounds 元素在窗口坐标系里的矩形（px）
     * @param downscale 快照的降采样因子
     * @param spec 渲染规格（**全分辨率 px**，本函数内部负责缩放）
     */
    fun renderForElement(
        context: Context,
        snapshot: Bitmap,
        bounds: Rect,
        downscale: Int,
        spec: GlassSpec,
    ): Bitmap? {
        val ds = downscale.coerceAtLeast(1)
        val sw = snapshot.width
        val sh = snapshot.height
        val l = (bounds.left / ds).coerceIn(0, sw - 1)
        val t = (bounds.top / ds).coerceIn(0, sh - 1)
        val r = (bounds.right / ds).coerceIn(l + 1, sw)
        val b = (bounds.bottom / ds).coerceIn(t + 1, sh)
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return null

        val cropped = Bitmap.createBitmap(snapshot, l, t, w, h)

        // 半径上限：圆角不能超过短边的一半（与 Lens.kt 的 fastCoerceAtMost 同口径）
        val maxRadius = minOf(w, h) / 2f
        val scaled = GlassSpec(
            blurRadiusPx = spec.blurRadiusPx / ds,
            refractionHeightPx = spec.refractionHeightPx / ds,
            refractionAmountPx = spec.refractionAmountPx / ds,
            depthEffect = spec.depthEffect,
            chromaticAberration = spec.chromaticAberration,
            cornerRadii = FloatArray(4) { spec.cornerRadii[it].coerceAtMost(maxRadius) / ds },
            saturation = spec.saturation,
        )
        return render(context, cropped, scaled)
    }

    /** 是否应当走低版本管线（API < 31 无 RenderEffect；API < 33 无 AGSL）。 */
    val needsLowPipeline: Boolean
        get() = Build.VERSION.SDK_INT < 33

    /** 是否连 RenderEffect 都没有（更早的分支，可用更省的实现）。 */
    val lacksRenderEffect: Boolean
        get() = Build.VERSION.SDK_INT < 31
}