package cn.apixiaoyuan.app.core.pk.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import cn.apixiaoyuan.app.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮球（★ 2026-10-03 新增）。
 *
 * ## 需求（用户逐字）
 *
 * > 「加入后台挂机功能，需要悬浮窗保活，到时候就显示一个悬浮球，
 * >   悬浮球外观显示为一只 svg 画的笔，然后背景透明点击回到逆向系老挂界面。」
 *
 * 四点：
 *  1. 悬浮球 = 后台挂机的**可见标志**（也顺便让系统不那么容易杀进程）；
 *  2. 外观 = 一支笔（`res/drawable/ic_pk_pen.xml`，**描边**画法）；
 *  3. **背景透明** —— 球本身不画底色，只有笔的线条；
 *  4. **点击回到 App 界面**。
 *
 * ## 为什么用 `TYPE_APPLICATION_OVERLAY`
 *
 * Android 8.0（API 26）起，`TYPE_PHONE` / `TYPE_SYSTEM_ALERT` 等窗口类型
 * 被限制：非系统应用必须用 `TYPE_APPLICATION_OVERLAY`，否则直接抛
 * `BadTokenException`。本项目 minSdk 28，所以**只有这一个选择**。
 *
 * 该类型需要 `SYSTEM_ALERT_WINDOW` 权限 —— 它是**特殊权限**，
 * 只能用 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` 引导用户手动开，
 * 无法运行时申请（见 [PkAutoHostService] 的权限检查）。
 *
 * ## 背景透明怎么做到的
 *
 * 三层都要对：
 *  1. 窗口：`PixelFormat.TRANSLUCENT`；
 *  2. 根 View：**不设任何 background**（连 ripple 都不给，否则会有一圈水波纹底色）；
 *  3. 图标：矢量图里 `fillColor="#00000000"` + 只画 `strokeColor` 白线。
 *
 * 三处任缺其一，悬浮球都会变成一个「白方块 / 黑方块」。
 *
 * ## 拖拽与点击的区分
 *
 * 手指按下→移动→抬起，如果按位移判定为「拖」，就**不**触发点击。
 * 阈值 [TAP_SLOP_PX] —— 纯点击时手指会有几像素抖动，不能按「位移为 0」判。
 */
class PkPenOverlay(private val context: Context) {

    private companion object {
        const val TAG = "PkPenOverlay"

        /** 球直径（dp）。 */
        const val BALL_DP = 46

        /** 判定「点击」的最大位移（px）。超过即视为拖拽。 */
        const val TAP_SLOP_PX = 12

        /** 贴边吸附的动画时长。 */
        const val SNAP_MS = 180L
    }

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** 点击（非拖拽）时回调 —— 由 Service 负责「回到 App 界面」。 */
    var onClick: (() -> Unit)? = null

    val isShowing: Boolean get() = view != null

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).roundToInt()

    /** 屏幕可用区域（用来算初始位置与拖拽边界）。 */
    private val screenW: Int get() = context.resources.displayMetrics.widthPixels
    private val screenH: Int get() = context.resources.displayMetrics.heightPixels

    /**
     * 显示悬浮球。幂等 —— 已在显示时直接返回。
     *
     * @return true = 已显示（或本来就显示着，或权限不足导致没加上）
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (isShowing) return true
        if (!canDrawOverlays()) {
            Log.w(TAG, "没有悬浮窗权限，悬浮球不显示")
            return false
        }

        // ⚠️ 根 View 是**裸 ImageView**：不设 background、不设 foreground，
        //    这样除了笔的线条之外全是透明像素。
        val iv = ImageView(context).apply {
            setImageResource(R.drawable.ic_pk_pen)
            // 笔线是白色；给一层淡阴影，避免在浅色壁纸上「看不见」。
            // （阴影不占不透明像素，不影响「背景透明」的要求。）
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = 0x66000000
                outlineSpotShadowColor = 0x66000000
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }

        val size = dp(BALL_DP)
        val p = WindowManager.LayoutParams(
            size,
            size,
            // API 26+ 非系统应用**只能**用这个类型，见类 KDoc。
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 悬浮窗必须 FLAG_NOT_FOCUSABLE，否则会抢走输入焦点（键盘弹不出来、
            // 下层 App 收不到返回键）。NOT_TOUCH_MODAL 让球以外的触摸照常下传。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            // ★ 背景透明的关键之一：窗口格式必须是 TRANSLUCENT。
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 初始位置：右边靠上（不挡内容，又容易点）。
            x = screenW - size - dp(8)
            y = screenH / 4
        }

        attachDragAndTap(iv, p)

        return runCatching {
            wm.addView(iv, p)
            view = iv
            params = p
            Log.i(TAG, "悬浮球已显示 at (${p.x},${p.y}) size=$size")
            true
        }.getOrElse {
            // 常见原因：权限被撤 / 厂商 ROM 限制 / 已有同类型窗口冲突。
            Log.w(TAG, "悬浮球添加失败：${it.message}")
            false
        }
    }

    /** 隐藏并释放。幂等。 */
    fun hide() {
        val v = view ?: return
        view = null
        params = null
        runCatching { wm.removeView(v) }
            .onFailure { Log.w(TAG, "悬浮球移除失败：${it.message}") }
        Log.i(TAG, "悬浮球已隐藏")
    }

    /**
     * 拖拽 + 点击。
     *
     * 关键点：**用位移阈值区分点击与拖拽** —— 否则「手指微抖」会被当成拖拽，
     * 用户点了却回到不了 App（或反过来，拖动时会误触发跳转）。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTap(v: View, p: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > TAP_SLOP_PX || abs(dy) > TAP_SLOP_PX)) {
                        dragging = true
                    }
                    if (dragging) {
                        // 夹到屏幕内，避免球被拖出可视区（拖出去就再也点不到了）。
                        p.x = (startX + dx).toInt().coerceIn(0, (screenW - p.width).coerceAtLeast(0))
                        p.y = (startY + dy).toInt().coerceIn(0, (screenH - p.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        // ★ 点击（非拖拽）→ 回到 App。回调由 Service 注入。
                        runCatching { onClick?.invoke() }
                    }
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    /** 是否已授予「显示在其他应用上层」。 */
    fun canDrawOverlays(): Boolean =
        runCatching { android.provider.Settings.canDrawOverlays(context) }.getOrDefault(false)
}
