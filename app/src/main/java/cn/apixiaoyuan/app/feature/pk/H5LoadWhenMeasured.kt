package cn.apixiaoyuan.app.feature.pk

import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import cn.apixiaoyuan.app.core.log.AppLogger

/**
 * 等 WebView **真正完成测量（尺寸 > 0）**之后再 loadUrl。
 *
 * # 为什么必须这样（真机实测根因）
 *
 * App 的 H5 页面里出现过一个极特定的现象：
 * ```
 * innerWidth/innerHeight        = 394x853   OK   （视口看起来正常）
 * documentElement.clientHeight  = 853       OK
 * visualViewport                = 394x853   OK
 * 但 height:100vw                = 394       OK
 *    height:100vh                = 0         BAD  <-- 塌了
 *    height:100%                 = 0         BAD
 * ```
 * 后果：
 *   - .honor-roll{height:100vh} = 0     -> 荣誉榜 / 收到的赞 **整片空白**；
 *   - .modal-container{height:100vh} = 0 -> 背包弹窗 **溢出屏幕外**。
 *
 * 同一份 HTML 在系统浏览器里完全正常，**静态创建的 WebView 也完全正常**
 * （用 VhProbe 对照实验验证：4 组设置组合 A/B/C/D 全部 vh=853）。
 *
 * 真正的差异是**加载时机** —— 真机日志给出的规律：
 * ```
 * 入口容器 pk.html        （转场结束后加载）: vh=853 OK
 * 下级容器 honor-roll.html（转场动画期间加载）: vh=0   BAD
 * 下级容器 like-list.html （转场动画期间加载）: vh=0   BAD
 * ```
 * 即：**在导航转场动画还没结束时 loadUrl，此时 View 还没被测量（尺寸 0x0），
 * Blink 会用「0 高视口」初始化视图，并把 100vh / 初始包含块固定下来。**
 * innerHeight 是实时读取的，所以看起来「视口正常」，可 vh 已经算死了。
 *
 * # 为什么不能用 post { loadUrl() }
 *
 * View.post 只是把任务排到**下一个消息循环**，此时转场动画往往还在进行、
 * View 尺寸仍然是 0 —— 实测这一招对入口容器部分有效、对下级容器**完全无效**。
 * 所以改为监听 OnLayoutChangeListener，直到宽高都 > 0 才真正加载。
 *
 * @param tag 日志标签（PkH5 / PkH5Child），用于核对「等待了多久 / 是否走了兜底」。
 */
internal fun loadUrlWhenMeasured(webView: WebView, url: String, tag: String) {
    if (webView.width > 0 && webView.height > 0) {
        AppLogger.i(tag, "容器已就绪 ${webView.width}x${webView.height} -> 立即加载")
        runCatching { webView.loadUrl(url) }
        return
    }
    AppLogger.i(tag, "容器未测量 ${webView.width}x${webView.height} -> 等布局完成再加载（否则 100vh 会被算成 0）")

    val handler = Handler(Looper.getMainLooper())
    var fallback: Runnable? = null
    var done = false

    val listener = object : View.OnLayoutChangeListener {
        override fun onLayoutChange(
            v: View,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int,
        ) {
            val w = right - left
            val h = bottom - top
            if (w <= 0 || h <= 0 || done) return
            done = true
            runCatching { v.removeOnLayoutChangeListener(this) }
            fallback?.let { runCatching { handler.removeCallbacks(it) } }
            AppLogger.i(tag, "容器测量完成 ${w}x$h -> 加载")
            runCatching { webView.loadUrl(url) }
        }
    }

    // 兜底：万一布局一直不来（极端情况），最多等 1.5s 就先加载，避免页面永远空白。
    fallback = Runnable {
        if (done) return@Runnable
        done = true
        runCatching { webView.removeOnLayoutChangeListener(listener) }
        AppLogger.w(tag, "等布局超时 1.5s，先加载（vh 可能不准）")
        runCatching { webView.loadUrl(url) }
    }

    webView.addOnLayoutChangeListener(listener)
    handler.postDelayed(fallback, 1500L)
}
