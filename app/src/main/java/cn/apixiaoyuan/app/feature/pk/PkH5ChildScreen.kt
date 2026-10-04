package cn.apixiaoyuan.app.feature.pk

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import cn.apixiaoyuan.app.core.design.component.isDarkColor
import cn.apixiaoyuan.app.core.design.component.probeH5PageColor
import cn.apixiaoyuan.app.core.design.component.rememberH5PageColor
import cn.apixiaoyuan.app.core.design.component.statusBarTopDp
import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.navigation.AppNavController
import cn.apixiaoyuan.app.core.oldsimian.PkJsInjector

/**
 * **口算 PK 的「下一个 H5 容器」**（★ 2026-10-04 新增，用户要求）。
 *
 * # 用户逐字
 *
 * > 「转场动画是**点击按钮 → miuix 或 aosp app 原生选择动画 → 进入新 h5 容器
 * >   → 预测性返回 → 退回主页**。」
 * > 「你根本没有使用**新 h5 容器**跳转页面」
 *
 * # 它是什么
 *
 * 一个「只加载指定 URL」的 H5 容器。与 [PkH5Screen] 的区别：
 *
 * | | [PkH5Screen]（入口页）| 本页（下级页）|
 * |---|---|---|
 * | URL | 由 `PkHostOrchestrator.h5Url()` 决定 | **由构造函数传入**（H5 桥要开的那个）|
 * | 位置 | `entry<RoutePk>` | `entry<RoutePkH5>` |
 * | 返回 | 交给 App 导航（pop 回主页）| 交给 App 导航（pop 回上一级容器）|
 *
 * 两者都**不注册 `BackHandler`** —— 返回手势交给 miuix `NavDisplay`，
 * 这样原生转场与**预测性返回**才会执行（我之前自己抢了手势，反而把它搞坏了）。
 *
 * # 为什么每个下级页都要新开一个容器
 *
 * H5 里的跳转是调桥 `openSchema('native://openWebView?url=…')`。
 * pk-node 的 `H5_INJECT` 原本把它实现成 **`location.href` 同窗口导航**
 * （见 `pk-h5-proxy.js:374` 的注释）—— 那样「进入下一个页面」只是同一个
 * WebView 换 URL，**App 导航毫不知情，转场自然没有**。
 *
 * 现在由**宿主**接住这个跳转 → `navController.navigate(RoutePkH5(url))`
 * → 压一个新的 H5 容器 → App 的原生转场与预测性返回全部生效。
 *
 * @param url 要加载的完整 URL（已是本机同源地址）
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PkH5ChildScreen(
    url: String,
    navController: AppNavController,
) {
    val context = LocalContext.current
    val pageColor = rememberH5PageColor()

    // 供 H5 桥在「要开新页面」时回调（宿主接住 → App 原生导航）。
    // 用 state 持有，保证 WebView 里那个非 composable 作用域也能读到最新的。
    val openChild = remember { PkChildNavigator() }
    openChild.navigate = { target -> navController.navigate(cn.apixiaoyuan.app.core.navigation.RoutePkH5(target)) }

    var progress by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadedOnce by remember { mutableStateOf(false) }

    /**
     * 把 H5 要开的页面压成**新的 H5 容器**（三级页也走这条）。
     *
     * ★ 2026-10-04（用户要求）：「三级页面跳转应该也要覆盖新容器」。
     *   之前只有入口容器接了打开逻辑，下级容器（荣誉榜 → 收到的赞这种）没接，
     *   于是在同一个 WebView 里同窗口跳、没有容器也没有转场。现在两级都接。
     *
     * ★ 同时处理「跳到结算页就折叠中间层」（用户要求）：
     *   跳到 `result.html` 系列时，把中间那几层（PK 入口 / 对局）折掉，
     *   只留 `[最底层, 结算页]` —— 这样从结算页返回直接回主页，
     *   不会再退回 PK / 对局页。
     *
     * ⚠️ 必须是**值捕获稳定**的 lambda：它被 `shouldOverrideUrlLoading` 持有，
     *   而 `remember` 让它在整个容器生命周期内只创建一次（navController 是不变的）。
     */
    val openChildInNewContainer: (String) -> Unit = remember {
        { child: String ->
            navController.navigate(cn.apixiaoyuan.app.core.navigation.RoutePkH5(child))
            if (isPkResultUrl(child)) {
                val dropped = navController.dropIntermediateLayers(1)
                AppLogger.i(
                    "PkH5Child",
                    "到达结算页 → 只保留「最底层 + 结算页」，折掉中间层=$dropped",
                )
            }
        }
    }

    val webView = remember {
        WebView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // ★★ 2026-10-04：与入口容器一致 —— 必须开，否则 H5 的 meta viewport 被忽略，
                //   页面按**物理像素宽**（而非 device-width）排版 → 排行榜等内容溢出屏幕外。
                //   自适应说明见 PkH5Screen；此处不设任何固定宽度/缩放。
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                    progress = 5
                    error = null
                    view?.let { PkJsInjector.markPageStarted(it) }
                }

                override fun onPageFinished(view: WebView?, u: String?) {
                    progress = 100
                    AppLogger.i("PkH5Child", "onPageFinished: $u")
                    // 取证：页面可见文本（判断卡在哪一步）。
                    view?.evaluateJavascript(
                        "(function(){try{return (document.body&&document.body.innerText||'')" +
                            ".replace(/\\s+/g,' ').slice(0,300)}catch(e){return 'ERR:'+e}})()",
                    ) { v -> AppLogger.i("PkH5Child", "页面文本: $v") }
                    // 「老挂戏老叟」注入（含 JS 控制台）。与入口页一致。
                    view?.let { PkJsInjector.injectIfEnabled(it) }
                    view?.let { probeH5PageColor(it, pageColor, tag = "PkH5Child") }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    err: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        error = "H5 加载失败：${err?.description ?: "未知错误"}"
                        progress = 0
                    }
                }
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = handleScheme(
                    request?.url?.toString() ?: return false,
                    onFinish = { navController.popBackStack() },
                    onOpenChild = openChildInNewContainer,
                    exceptUrl = url,
                    currentUrl = view?.url,
                    tag = "PkH5Child",
                )
                @Deprecated("Deprecated in API 24, but kept for older WebView")
                override fun shouldOverrideUrlLoading(view: WebView?, u: String?): Boolean =
                    u?.let {
                        handleScheme(
                            it,
                            onFinish = { navController.popBackStack() },
                            onOpenChild = openChildInNewContainer,
                            exceptUrl = url,
                            currentUrl = view?.url,
                            tag = "PkH5Child",
                        )
                    } ?: false
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    val m = msg ?: return false
                    AppLogger.d("PkH5ChildJS", "[${m.messageLevel()}] ${m.message()} ")
                    return true
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { webView.clearFocus() }
            runCatching { webView.stopLoading() }
        }
    }

    val bg = pageColor.value ?: MaterialTheme.colorScheme.surfaceContainer

    val view = LocalView.current
    LaunchedEffect(bg) {
        runCatching {
            val act = view.context as? android.app.Activity ?: return@runCatching
            WindowCompat.getInsetsController(act.window, view)
                .isAppearanceLightStatusBars = !isDarkColor(bg)
        }
    }

    // 顶部下移一条状态栏（与入口页同一取法，带资源兜底 + 日志）。
    val topInset = statusBarTopDp()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg),
    ) {
        val firstLoading = progress in 1..99 && !loadedOnce
        if (firstLoading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { webView },
                modifier = Modifier
                    .fillMaxSize(),
                // ★★ 2026-10-04：与入口容器一致 —— **去掉 `padding(top = topInset)`**。
                //   WebView 被 padding 会让 H5 的 `100vh` 跟着变小，而荣誉榜/收到的赞
                //   这些页面是 `height:100vh` + `overflow:hidden` + `calc(100vh - 82.67vw)`
                //   分区布局 → 视口一矮，榜单区高度被压成 0 → 整页空白。
                //   顶部留白改由 H5 自己用桥 `getImmerseStatusBarHeight` 处理
                //   （= pk-node 的做法；sbh 由 h5Url() 传进 URL）。
                update = { v ->
                    if (error == null && !loadedOnce) {
                        loadedOnce = true
                        AppLogger.i("PkH5Child", "加载下级 H5：$url")
                        v.loadUrl(url)
                    }
                },
                onRelease = { v -> releaseWebView(v) },
            )

            // ★ 2026-10-04（用户要求）：首屏加载动画（与入口容器一致）。
            //   叠在 WebView 之上，加载完（progress=100 或已 loadUrl）自动消失。
            if (firstLoading) {
                cn.apixiaoyuan.app.core.design.component.H5LoadingDots(pageColor = bg)
            }

            error?.let { msg ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Card(modifier = Modifier.padding(24.dp)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = msg,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = {
                                error = null
                                loadedOnce = false
                            }) { Text("重试") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「要开新 H5 页面」的回调载体。
 *
 * 为什么用对象：WebView 的 `webViewClient` 是在
 * `remember { WebView(...).apply { ... } }` 里创建的，那个 lambda
 * **不是 composable 作用域**，拿不到 `navController` 这类 composable 局部值；
 * 而 `navController` 本身可以跨作用域用 —— 所以把「navigate」这个动作
 * 放进一个用 `remember` 造的对象里，两边都能访问（与 NavAnimHolder 同一个套路）。
 */
internal class PkChildNavigator {
    var navigate: ((String) -> Unit)? = null
}
