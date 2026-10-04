package cn.apixiaoyuan.app.core.design.glass.low

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import cn.apixiaoyuan.app.core.log.AppLogger

/**
 * 低版本高斯模糊 —— 对齐 `miuix-blur` 的 `blur(radiusX, radiusY)`。
 *
 * # 背景（为什么低版本不能用 miuix-blur）
 *
 * `miuix-blur` 的 `minSdk = 33`，它的模糊走 **`RenderEffect.createBlurEffect`**
 * （API 31+）。低版本（API 24–30）既要能装，又要「1:1 复刻」——
 * 于是必须自己实现同一件事。
 *
 * # 为什么选 RenderScript
 *
 * Android 上跨 API 24–30 可用的模糊只有三条路：
 *
 *  1. **`RenderScript` + `ScriptIntrinsicBlur`** —— 官方内建（API 17+ 引入、
 *     API 31 标记废弃但**至今仍在 `android.jar` 里**，SDK 37 实测仍存在）。
 *     由系统提供、走硬件加速，是当年 Android 官方推荐的 blur 做法。
 *     ✅ 采用它。
 *  2. 纯 Java 反复卷积 —— 在 1280×2772 的屏上每帧算几千万次乘加，必然掉帧。
 *  3. GLSL 自己写两趟高斯 —— 需要自建 EGL 环境与纹理上传，复杂度极高且
 *     与 Skia 的合成路径难以对齐。
 *
 * 结论：**RenderScript 是唯一既能跑、又与 `RenderEffect` 行为最接近的选择**。
 *
 * # 与 GPU 版的差异（诚实说明）
 *
 * `RenderEffect.createBlurEffect(radius, radius)` 用的是 Skia 的 **双通道
 * 盒式近似高斯**；`ScriptIntrinsicBlur` 用的是 RenderScript 自己的
 * **多次盒式 + 加权** 近似（`setRadius` 上限 25）。
 * 两者都是「近似高斯」，观感一致，但**不是逐像素相同** —— 这是跨引擎
 * 无法消除的差别。为了尽量贴近，本实现：
 *
 *  - 对 > 25 px 的半径做**多趟分解**（每趟 ≤ 25，等价于加大近似迭代次数，
 *    正是 RenderScript 官方文档推荐的用法）；
 *  - 半径按「方差可加」合并（多趟半径 r 的等效总半径 = r·√n）。
 *
 * # 资源与线程
 *
 * `RenderScript` 的创建**很贵**（约 10–30 ms）且必须显式 `destroy()`，
 * 所以本类做成「一次性会话」：每次调用建一个、用完立即释放，
 * 不做全局单例（避免与其它 `RenderScript` 使用方抢资源，也避免忘记释放）。
 */
internal object LowBlur {

    /** ScriptIntrinsicBlur 的单趟半径上限（RenderScript 硬限制）。 */
    private const val MAX_RADIUS_PER_PASS = 25f

    /**
     * 对 [src] 做高斯模糊，返回**新位图**（`src` 不被修改）。
     *
     * @param radiusPx 模糊半径（px）。与 `miuix-blur` 的 `blur()` 参数同口径。
     * @return 模糊后的位图；[radiusPx] ≤ 0.5 或环境不可用时返回 [src] 本身。
     *
     * ⚠️ 必须在**后台线程**调用（RenderScript 会阻塞）。
     */
    fun blur(context: Context, src: Bitmap, radiusPx: Float): Bitmap {
        if (radiusPx <= 0.5f) return src
        return runCatching { blurInternal(context, src, radiusPx) }
            .getOrElse {
                AppLogger.w("LowBlur", "模糊失败，回退原图：${it.message}")
                src
            }
    }

    private fun blurInternal(context: Context, src: Bitmap, radiusPx: Float): Bitmap {
        // 输入统一转成 ARGB_8888（RenderScript 只认这一种）。
        val inBmp = if (src.config == Bitmap.Config.ARGB_8888) src
        else src.copy(Bitmap.Config.ARGB_8888, false)

        val w = inBmp.width
        val h = inBmp.height
        // 输出位图：与输入同尺寸（后续折射依赖「与目标区域等大」的假设）。
        val current = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val rs = RenderScript.create(context)
        try {
            val blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            var allocIn = Allocation.createFromBitmap(rs, inBmp)
            var allocOut = Allocation.createFromBitmap(rs, current)

            // ---- 大于单趟上限时分解成多趟 ----
            //
            // ScriptIntrinsicBlur 单趟最大半径 25；更大半径的官方做法是**连续多趟**。
            // 用「方差可加」把总半径 R 拆成 n 趟、每趟 r = R / √n：
            //
            //   n 趟半径 r 的等效半径 = r·√n = R   ✅
            //
            // 于是每趟都 ≤ 25，且总强度与单趟大半径一致。
            if (radiusPx <= MAX_RADIUS_PER_PASS) {
                blur.setRadius(radiusPx.coerceAtLeast(0.1f))
                blur.setInput(allocIn)
                blur.forEach(allocOut)
                allocOut.copyTo(current)
            } else {
                val passes = kotlin.math.ceil(radiusPx / MAX_RADIUS_PER_PASS).toInt()
                val perPass = (radiusPx / kotlin.math.sqrt(passes.toFloat()))
                    .coerceIn(0.1f, MAX_RADIUS_PER_PASS)
                repeat(passes) {
                    blur.setRadius(perPass)
                    blur.setInput(allocIn)
                    blur.forEach(allocOut)
                    // 交换缓冲：本趟输出成为下一趟输入
                    val tmp = allocIn
                    allocIn = allocOut
                    allocOut = tmp
                }
                // 交换后最后一趟结果在 allocIn 上
                allocIn.copyTo(current)
            }
            blur.destroy()
        } finally {
            // RenderScript 必须显式销毁，否则泄漏 native 资源（几个会话就 OOM）。
            runCatching { rs.destroy() }
        }

        if (inBmp !== src) inBmp.recycle()
        return current
    }
}