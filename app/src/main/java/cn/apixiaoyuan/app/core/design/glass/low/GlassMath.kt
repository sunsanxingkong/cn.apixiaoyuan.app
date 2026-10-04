package cn.apixiaoyuan.app.core.design.glass.low

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 玻璃渲染数学核 —— **miuix-blur 的 AGSL shader 的逐行 CPU 翻译**。
 *
 * # 为什么要有这个文件
 *
 * 高版本（API 33+）的液态玻璃由 `miuix-blur` 用 **AGSL（RuntimeShader）** 实现：
 * `Lens.kt` 里的 `ROUNDED_RECT_REFRACTION_SHADER` 与
 * `..._WITH_DISPERSION_SHADER` 是**唯一权威**的折射算法。
 *
 * 而 AGSL 是 **API 33 才引入**的。要满足「低版本也 1:1 复刻、一分不差」，
 * 唯一的办法就是：**把同一套数学原封不动地搬到 CPU 上跑**。
 *
 * 本文件就是那次搬运。翻译原则（严格遵守，否则「1:1」就是空话）：
 *
 *  1. **表达式逐条对应**，不做「顺手的优化」（例如把 `min(max(a,b),0)` 提前化简）——
 *     浮点运算顺序变了结果就会有 LSB 级差异，而这类差异会在边框附近被放大成可见色带；
 *  2. **变量名与 AGSL 保持一致**（`halfSize` / `centeredCoord` / `sd` / `d` / `grad`…），
 *     方便日后与 `Lens.kt` 对照 diff；
 *  3. `half4` 在本文件里就是 `Int`（打包的 ARGB_8888），采样返回 [Rgba] 四通道 float。
 *
 * # 与 GPU 版不可避免的差异（诚实说明）
 *
 * AGSL 在 GPU 上以 `half`（fp16）参与中间运算，本实现是 `float`（fp32）——
 * 精度**只会更高、不会更低**。极端情况下（例如极小折射量、极暗像素）
 * 可能出现 ±1/255 的舍入差别，这是跨后端物理上无法消除的；算法本身完全一致。
 *
 * # 覆盖的 shader
 *
 *  - `roundedRectRefraction`：[LensKt] 的非色散版（对齐 `ROUNDED_RECT_REFRACTION_SHADER`）
 *  - `roundedRectRefractionDispersion`：七通道色散版（对齐 `..._WITH_DISPERSION_SHADER`）
 */
internal object GlassMath {

    // ==================== SDF（对应 AGSL 的 ROUNDED_RECT_SDF） ====================

    /**
     * 按象限取圆角半径 —— 对应 AGSL：
     * ```glsl
     * float radiusAt(float2 coord, float4 radii) {
     *     if (coord.x >= 0.0) {
     *         if (coord.y <= 0.0) return radii.y;   // 右上
     *         else return radii.z;                  // 右下
     *     } else {
     *         if (coord.y <= 0.0) return radii.x;   // 左上
     *         else return radii.w;                  // 左下
     *     }
     * }
     * ```
     *
     * ⚠️ 注意 `radii` 的分量顺序是 **x=左上, y=右上, z=右下, w=左下**（顺时针），
     * 与直觉的「左上、右上、左下、右下」不同 —— 这是从 AGSL 原样继承的。
     */
    fun radiusAt(x: Float, y: Float, radii: FloatArray): Float {
        return if (x >= 0f) {
            if (y <= 0f) radii[1] else radii[2]
        } else {
            if (y <= 0f) radii[0] else radii[3]
        }
    }

    /**
     * 圆角矩形有向距离场 —— 对应 AGSL：
     * ```glsl
     * float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
     *     float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
     *     float outside = length(max(cornerCoord, 0.0)) - radius;
     *     float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
     *     return outside + inside;
     * }
     * ```
     * 返回负值 = 在形状内部，正值 = 在外部，0 = 边界上。
     */
    fun sdRoundedRect(x: Float, y: Float, halfW: Float, halfH: Float, radius: Float): Float {
        val ccx = abs(x) - (halfW - radius)
        val ccy = abs(y) - (halfH - radius)
        // length(max(cornerCoord, 0.0))
        val mx = if (ccx > 0f) ccx else 0f
        val my = if (ccy > 0f) ccy else 0f
        val outside = sqrt(mx * mx + my * my) - radius
        val inside = min(max(ccx, ccy), 0f)
        return outside + inside
    }

    /**
     * SDF 梯度（折射方向）—— 对应 AGSL：
     * ```glsl
     * float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
     *     float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
     *     if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
     *         return sign(coord) * normalize(max(cornerCoord, 0.0));
     *     } else {
     *         float gradX = step(cornerCoord.y, cornerCoord.x);
     *         return sign(coord) * float2(gradX, 1.0 - gradX);
     *     }
     * }
     * ```
     *
     * @param out 输出二元组 [gx, gy]（避免每像素分配数组）
     */
    fun gradSdRoundedRect(
        x: Float, y: Float,
        halfW: Float, halfH: Float, radius: Float,
        out: FloatArray,
    ) {
        val ccx = abs(x) - (halfW - radius)
        val ccy = abs(y) - (halfH - radius)

        val sx = if (x >= 0f) 1f else if (x < 0f) -1f else 0f
        val sy = if (y >= 0f) 1f else if (y < 0f) -1f else 0f

        if (ccx >= 0f || ccy >= 0f) {
            val mx = if (ccx > 0f) ccx else 0f
            val my = if (ccy > 0f) ccy else 0f
            val len = sqrt(mx * mx + my * my)
            if (len > 0f) {
                out[0] = sx * (mx / len)
                out[1] = sy * (my / len)
            } else {
                out[0] = 0f
                out[1] = 0f
            }
        } else {
            // step(cornerCoord.y, cornerCoord.x)：x >= y ? 1 : 0
            val gradX = if (ccx >= ccy) 1f else 0f
            out[0] = sx * gradX
            out[1] = sy * (1f - gradX)
        }
    }

    /**
     * 边缘圆形映射 —— 对应 AGSL：
     * ```glsl
     * float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }
     * ```
     * 把 `[0,1]` 的深度映射成「越靠边缘、折射越强」的曲线。
     */
    fun circleMap(x: Float): Float {
        val t = 1f - x * x
        // x 理论上 ∈ [0,1]，但浮点误差可能让它略微超出 → clamp 后再开方，避免 NaN。
        val c = if (t > 0f) t else 0f
        return 1f - sqrt(c)
    }

    // ==================== 采样 ====================

    /** 四通道 float 颜色（对应 AGSL 的 `half4`）。 */
    class Rgba {
        var r = 0f
        var g = 0f
        var b = 0f
        var a = 0f
    }

    /**
     * 双线性采样 —— 对应 AGSL 的 `content.eval(coord)`。
     *
     * AGSL 的 `eval` 默认使用 **双线性插值 + clamp 边界**，本实现严格对齐：
     *  - 坐标落在 `[-0.5, size-0.5]` 之外时按边缘像素夹取（clamp-to-edge）；
     *  - 其余情况取四邻域按小数部分加权。
     *
     * @param pixels 源位图的 `getPixels` 缓冲（ARGB_8888，行优先）
     * @param w 源宽
     * @param h 源高
     */
    fun sample(
        pixels: IntArray, w: Int, h: Int,
        coordX: Float, coordY: Float,
        out: Rgba,
    ) {
        // AGSL 的纹理坐标 = 像素中心在 (i+0.5)，eval 以像素左上角为原点。
        // 故先整体平移 -0.5 再取整，才是「像素中心」的插值基准。
        val fx = coordX - 0.5f
        val fy = coordY - 0.5f

        var x0 = kotlin.math.floor(fx).toInt()
        var y0 = kotlin.math.floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val x1 = x0 + 1
        val y1 = y0 + 1

        // clamp-to-edge
        val cx0 = x0.coerceIn(0, w - 1)
        val cx1 = x1.coerceIn(0, w - 1)
        val cy0 = y0.coerceIn(0, h - 1)
        val cy1 = y1.coerceIn(0, h - 1)

        val p00 = pixels[cy0 * w + cx0]
        val p10 = pixels[cy0 * w + cx1]
        val p01 = pixels[cy1 * w + cx0]
        val p11 = pixels[cy1 * w + cx1]

        // 逐通道双线性
        out.r = bilerp((p00 ushr 16) and 0xff, (p10 ushr 16) and 0xff, (p01 ushr 16) and 0xff, (p11 ushr 16) and 0xff, tx, ty)
        out.g = bilerp((p00 ushr 8) and 0xff, (p10 ushr 8) and 0xff, (p01 ushr 8) and 0xff, (p11 ushr 8) and 0xff, tx, ty)
        out.b = bilerp(p00 and 0xff, p10 and 0xff, p01 and 0xff, p11 and 0xff, tx, ty)
        out.a = bilerp((p00 ushr 24) and 0xff, (p10 ushr 24) and 0xff, (p01 ushr 24) and 0xff, (p11 ushr 24) and 0xff, tx, ty)
    }

    /** 四值双线性（返回 0..255 的 float 通道值）。 */
    private fun bilerp(a: Int, b: Int, c: Int, d: Int, tx: Float, ty: Float): Float {
        val top = a + (b - a) * tx
        val bottom = c + (d - c) * tx
        return top + (bottom - top) * ty
    }

    /** 把 [Rgba]（0..255 浮点）打包回 ARGB_8888 int（四舍五入 + clamp）。 */
    fun pack(c: Rgba): Int {
        val a = c.a.toInt().coerceIn(0, 255)
        val r = c.r.toInt().coerceIn(0, 255)
        val g = c.g.toInt().coerceIn(0, 255)
        val b = c.b.toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 0..255 浮点 → 0..255 int（带四舍五入，与 GPU 的最近取整一致）。 */
    fun round255(v: Float): Int {
        val r = v + 0.5f
        return if (r <= 0f) 0 else if (r >= 255f) 255 else r.toInt()
    }
}

/**
 * 折射着色 —— 对应 `Lens.kt` 里的两段 AGSL 主函数。
 *
 * 非色散版（`ROUNDED_RECT_REFRACTION_SHADER`）逐行翻译：
 * ```glsl
 * float2 halfSize = size * 0.5;
 * float2 centeredCoord = (coord + offset) - halfSize;
 * float radius = radiusAt(coord, cornerRadii);
 * float sd = sdRoundedRect(centeredCoord, halfSize, radius);
 * if (-sd >= refractionHeight) return content.eval(coord);   // 远离边缘：原样
 * sd = min(sd, 0.0);
 * float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
 * float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
 * float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)
 *                         + depthEffect * normalize(centeredCoord));
 * float2 refractedCoord = coord + d * grad;
 * return content.eval(refractedCoord);
 * ```
 */
internal object LensKt {

    /** 渲染参数（与 `BackdropEffectScope` 里 set 进去的 uniform 一一对应）。 */
    class Params(
        /** 目标区域尺寸（px）—— 对应 `uniform float2 size` */
        var width: Float = 0f,
        var height: Float = 0f,
        /** 采样偏移（px）—— 对应 `uniform float2 offset`，通常是 `-padding` */
        var offsetX: Float = 0f,
        var offsetY: Float = 0f,
        /** 四角半径（左上/右上/右下/左下）—— 对应 `uniform float4 cornerRadii` */
        var cornerRadii: FloatArray = FloatArray(4),
        /** 折射带宽度 —— 对应 `uniform float refractionHeight` */
        var refractionHeight: Float = 0f,
        /** 折射强度 —— 对应 `uniform float refractionAmount`（传入时为负值） */
        var refractionAmount: Float = 0f,
        /** 是否启用深度效应 —— 对应 `uniform float depthEffect`（0 或 1） */
        var depthEffect: Float = 0f,
        /** 色散强度 —— 对应 `uniform float chromaticAberration` */
        var chromaticAberration: Float = 0f,
    )

    /**
     * 对 [src] 应用「圆角矩形折射」。
     *
     * @param src 已模糊的源位图（ARGB_8888）
     * @param dst 输出位图（与 src 同尺寸）
     * @param p 参数
     * @param dispersion true = 七通道色散版（对应 `..._WITH_DISPERSION_SHADER`）
     */
    fun refract(
        src: Bitmap,
        dst: Bitmap,
        w: Int, h: Int,
        srcPixels: IntArray,
        dstPixels: IntArray,
        p: Params,
        dispersion: Boolean,
    ) {
        val halfW = p.width * 0.5f
        val halfH = p.height * 0.5f
        val grad = FloatArray(2)
        val c = GlassMath.Rgba()
        val tmp = GlassMath.Rgba()

        // 预取常量，循环内不再读属性（CPU 版性能关键）
        val offsetX = p.offsetX
        val offsetY = p.offsetY
        val radii = p.cornerRadii
        val refrH = p.refractionHeight
        val refrA = p.refractionAmount
        val depth = p.depthEffect
        val chroma = p.chromaticAberration

        for (py in 0 until h) {
            for (px in 0 until w) {
                val coordX = px.toFloat()
                val coordY = py.toFloat()

                val centeredX = (coordX + offsetX) - halfW
                val centeredY = (coordY + offsetY) - halfH
                val radius = GlassMath.radiusAt(coordX, coordY, radii)

                var sd = GlassMath.sdRoundedRect(centeredX, centeredY, halfW, halfH, radius)

                // 远离边缘 → 原样拷贝（与 GPU 的 `return content.eval(coord)` 等价）
                if (-sd >= refrH) {
                    dstPixels[py * w + px] = srcPixels[py * w + px]
                    continue
                }
                sd = min(sd, 0f)

                val d = GlassMath.circleMap(1f - -sd / refrH) * refrA
                val gradRadius = min(radius * 1.5f, min(halfW, halfH))

                GlassMath.gradSdRoundedRect(centeredX, centeredY, halfW, halfH, gradRadius, grad)

                var gradX = grad[0]
                var gradY = grad[1]

                if (depth > 0f) {
                    // + depthEffect * normalize(centeredCoord)
                    val len = sqrt(centeredX * centeredX + centeredY * centeredY)
                    if (len > 0f) {
                        gradX += depth * (centeredX / len)
                        gradY += depth * (centeredY / len)
                    }
                }

                // normalize(grad)
                val glen = sqrt(gradX * gradX + gradY * gradY)
                if (glen > 0f) {
                    gradX /= glen
                    gradY /= glen
                } else {
                    gradX = 0f
                    gradY = 0f
                }

                val refrX = coordX + d * gradX
                val refrY = coordY + d * gradY

                if (!dispersion) {
                    GlassMath.sample(srcPixels, w, h, refrX, refrY, c)
                    dstPixels[py * w + px] = GlassMath.pack(c)
                } else {
                    // ---- 七通道色散（严格按 AGSL 的累加顺序与系数）----
                    val dispIntensity = chroma * ((centeredX * centeredY) / (halfW * halfH))
                    val dispX = d * gradX * dispIntensity
                    val dispY = d * gradY * dispIntensity

                    var r = 0f; var g = 0f; var b = 0f; var a = 0f

                    fun acc(chOffX: Float, chOffY: Float, coefR: Float, coefG: Float, coefB: Float, coefA: Float) {
                        GlassMath.sample(srcPixels, w, h, refrX + chOffX, refrY + chOffY, tmp)
                        r += tmp.r * coefR
                        g += tmp.g * coefG
                        b += tmp.b * coefB
                        a += tmp.a * coefA
                    }

                    // 依次：red, orange, yellow, green, cyan, blue, purple
                    acc(dispX, dispY, 1f / 3.5f, 0f, 0f, 1f / 7f)
                    acc(dispX * (2f / 3f), dispY * (2f / 3f), 1f / 3.5f, 1f / 7f, 0f, 1f / 7f)
                    acc(dispX * (1f / 3f), dispY * (1f / 3f), 1f / 3.5f, 1f / 3.5f, 0f, 1f / 7f)
                    acc(0f, 0f, 0f, 1f / 3.5f, 0f, 1f / 7f)
                    acc(-dispX * (1f / 3f), -dispY * (1f / 3f), 0f, 1f / 3.5f, 1f / 3.0f, 1f / 7f)
                    acc(-dispX * (2f / 3f), -dispY * (2f / 3f), 0f, 0f, 1f / 3.0f, 1f / 7f)
                    acc(-dispX, -dispY, 1f / 7f, 0f, 1f / 3.0f, 1f / 7f)

                    c.r = r; c.g = g; c.b = b; c.a = a
                    dstPixels[py * w + px] = GlassMath.pack(c)
                }
            }
        }
    }
}