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
        if (progress in 1..99 && !loadedOnce) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { webView },
                modifier = Modifier
                    .fillMaxSize()
                    // 顶上露出 background(bg) = 纯色填充。
                    .padding(top = topInset),
                update = { v ->
                    if (error == null && !loadedOnce) {
                        loadedOnce = true
                        AppLogger.i("PkH5Child", "加载下级 H5：$url")
                        v.loadUrl(url)
                    }
                },
                onRelease = { v -> releaseWebView(v) },
            )

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
