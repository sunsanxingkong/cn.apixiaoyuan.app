package cn.apixiaoyuan.app.core.pk.host

import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.apixiaoyuan.app.core.log.AppLogger

/**
 * WebView 预热池 —— 解决「每次进 H5 页面黑屏约 1 秒」。
 *
 * # 为什么需要预热
 *
 * 用户原话：「搞个页面预加载吧，现在每次打开 h5 都要黑屏一秒」。
 *
 * 每次进 PK 页都会 `WebView(context)` **新建实例 + 首次 loadUrl**，这两步加起来
 * 在主线程要几百毫秒（WebView 首次初始化尤其重：加载 so、起渲染进程），
 * 期间页面是空的 —— 用户看到的就是那一秒黑屏。
 *
 * 预热的做法：在**用户点进 PK 页之前**（pk-node 就绪后）就用
 * `MutableContextWrapper(applicationContext)` 建好实例并把入口页先加载掉。
 * 等真正进页面时，把 wrapper 的 baseContext 换成 Activity 再复用 ——
 * 这是给 WebView 预热的标准套路（`MutableContextWrapper` 就是为这种
 * 「创建用 App Context、使用换 Activity」的场景设计的；用 Application
 * Context 创建也能避免 Activity 泄漏）。
 *
 * # 生命周期与正确性
 *
 * - 预热实例有 TTL（[TTL_MS]）：太久没用就丢弃重建，避免拿到过期页面。
 * - [take] 只会成功一次；拿不到（没预热 / 超时 / 已被取走 / 已销毁）时
 *   调用方自己新建即可 —— **不影响正确性**，只是少了加速。
 * - [isReady] 会兜底检查实例是否仍可用（渲染进程可能被系统回收），
 *   失效时宁可丢弃重建，也不交出必然白屏的实例。
 *
 * # 为什么并发安全靠「只在主线程调用」
 *
 * WebView 只能在创建它的线程（这里 = 主线程）使用，所以本对象
 * **所有公开方法都必须在主线程调用**，不做额外加锁（加锁也解决不了跨线程误用）。
 */
internal object PkWebViewPool {

    /** 预热实例的有效期：超过就丢弃（页面数据可能已过期）。 */
    private const val TTL_MS = 90_000L

    private var warm: WebView? = null
    private var wrap: MutableContextWrapper? = null
    private var warmUrl: String? = null
    private var warmAt = 0L

    /**
     * 预热时加载的 URL。
     *
     * 容器拿它判断「预热页与要进的页是不是同一个」—— 是的话就**不用再 loadUrl**
     * （这正是省掉黑屏的关键：页面已经渲染好了，直接显示即可）。
     */
    var preloadedUrl: String? = null
        private set

    /** 是否有可用（未超时、未失效）的预热实例。 */
    fun isReady(): Boolean {
        val w = warm ?: return false
        if (System.currentTimeMillis() - warmAt > TTL_MS) return false
        return isAlive(w)
    }

    /**
     * 预热一个 WebView 实例并预加载 [url]。
     *
     * 幂等：已有可用实例时直接返回（不会重复创建）。
     * 必须在主线程调用。
     */
    fun warmUp(appCtx: Context, url: String) {
        // 已有可用实例 → 什么都不做（避免用户反复进出页面时反复重建）。
        if (isReady()) return
        // 有残留但已不可用 → 先清掉，避免泄漏。
        if (warm != null) clear()
        runCatching {
            val w = MutableContextWrapper(appCtx)
            val wv = WebView(w)
            applySettings(wv)
            wv.loadUrl(url)
            warm = wv
            wrap = w
            warmUrl = url
            warmAt = System.currentTimeMillis()
            preloadedUrl = url
            AppLogger.i("PkWebViewPool", "预热完成：$url")
        }.onFailure { AppLogger.w("PkWebViewPool", "预热失败：${it.message}") }
    }

    /**
     * 取出预热实例，并**把它的 Context 换成 [activity]**。
     *
     * 返回的 [Warm] 带上「已经加载的 URL」，调用方据此决定要不要再 loadUrl
     * （同 URL 就不需要，直接显示已渲染好的页面 = 无黑屏）。
     *
     * 返回 null 表示没有可用的预热实例（调用方新建即可）。
     */
    fun take(activity: Context): Warm? {
        val wv = warm ?: return null
        if (System.currentTimeMillis() - warmAt > TTL_MS) {
            AppLogger.i("PkWebViewPool", "预热实例已超时，丢弃")
            clear()
            return null
        }
        if (!isAlive(wv)) {
            AppLogger.i("PkWebViewPool", "预热实例已失效，丢弃")
            clear()
            return null
        }
        return runCatching {
            // 关键一步：把创建时的 App Context 换成 Activity，
            // 之后 WebView 才能正常弹出输入法 / 对话框（需要 Window）。
            wrap?.baseContext = activity
            val w = Warm(view = wv, url = warmUrl)
            // 交出所有权：一个实例只能被取一次。
            warm = null
            wrap = null
            preloadedUrl = null
            warmAt = 0L
            AppLogger.i("PkWebViewPool", "复用预热实例（已加载：${w.url}）")
            w
        }.getOrElse {
            clear()
            null
        }
    }

    /** 丢弃预热实例（退出 / 异常 / 已被系统回收时调用）。主线程。 */
    fun clear() {
        runCatching {
            val w = warm ?: return@runCatching
            w.stopLoading()
            w.webViewClient = WebViewClient()
            (w.parent as? ViewGroup)?.removeView(w)
            w.removeAllViews()
            w.destroy()
        }
        warm = null
        wrap = null
        warmUrl = null
        preloadedUrl = null
        warmAt = 0L
    }

    /**
     * 容器设置 —— **必须与 `PkH5Screen` 的容器完全一致**。
     *
     * 不一致会让预热页与真实页行为漂移（例如视口宽度、图片加载策略不同），
     * 用户会看到「进页面后闪一下重排」。改这里时同步改 PkH5Screen。
     */
    private fun applySettings(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 与 PkH5Screen 一致：开这两个才能让 H5 的 meta viewport 生效。
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    }

    /** WebView 是否还可用（渲染进程未被杀）。 */
    private fun isAlive(wv: WebView): Boolean = runCatching {
        wv.url != null
    }.getOrDefault(false)

    /** 取出的预热实例。 */
    class Warm internal constructor(
        val view: WebView,
        /** 预热时已经加载的 URL（null = 尚未加载）。 */
        val url: String?,
    )
}