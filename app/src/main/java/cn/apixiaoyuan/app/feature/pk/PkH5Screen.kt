package cn.apixiaoyuan.app.feature.pk

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
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
import androidx.compose.material3.CircularProgressIndicator
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
import cn.apixiaoyuan.app.core.oldsimian.PkJsInjector
import cn.apixiaoyuan.app.core.pk.host.PkHostOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 口算 PK 页 —— **纯 WebView 容器**（2026-10-04）。
 *
 * # 用户要求（逐字）
 *
 * > 「直接把 pk h5 重写，**页面逻辑全用 pk-node 的**，不过只要 webview 里的，
 * >   账号自动切换为 app 的。」
 * > 「顶部依旧没有下移，你看看很早之前的提交就下移了但是顶部没有纯色填充。」
 * > 「转场动画是**点击按钮 → miuix 或 aosp app 原生选择动画 → 进入新 h5 容器
 * >   → 预测性返回 → 退回主页**。」
 *
 * # ① 页面逻辑全用 pk-node 的
 *
 * 桥（`getUserInfo` / `requestConfig` / `dataEncrypt` / `dataDecrypt` / `openWebView` …）、
 * 出站代理、页面跳转、`__PK_USER` 注入 —— 全在 pk-node 的 `H5_INJECT` 里，由 node 服务端完成。
 *
 * 宿主**刻意不注册任何** `addJavascriptInterface`（pk-node 的 JS 桥「先到先得」），
 * 也不注册 `shouldInterceptRequest`。
 *
 * # ② 顶部下移 + 纯色填充（★ 本轮修正）
 *
 * 用 [statusBarTopDp]（系统状态栏真实高度，带资源兜底 + 日志可验证）
 * 让 WebView 整体下移；顶上那条露出 `background(bg)` = **纯色填充**。
 *
 * 为什么必须让出这一条：这套 H5 不处理 `safe-area-inset-top`，
 * 全屏 WebView 在 edge-to-edge 下从 y=0 开始画，H5 自己的抬头就被状态栏挡住了。
 *
 * # ③ 转场 / 预测性返回 —— **交回给 App 导航**（★ 本轮修正，之前被我自己搞坏）
 *
 * 用户描述的流程是**App 级导航**：
 *
 *     点按钮 → miuix/aosp 原生转场 → 进入新 h5 容器 → 预测性返回 → 退回主页
 *
 * 这条链路由 `AppNavHost` 的 `entry<RoutePk>(swipeDismiss = LeftToRight)`
 * 和 miuix 的 `NavDisplay` 提供 —— **它本来就带 miuix/aosp 转场与预测性返回**。
 *
 * ⚠️ 我之前在本页注册了 `BackHandler` + 自己实现的 `PredictiveBackHandler`，
 * 把返回手势**抢走了**，App 的原生转场/预测性返回根本不会执行
 * （表现就是用户说的「换页动画还是之前的」）。**本版全部删除。**
 *
 * ⚠️ 同理，本页**不再自己实现「H5 内部跳转」的转场**：那属于 WebView 内部行为，
 * App 导航管不到；用户要的是 App 级转场（上面那条流程）。
 *
 * # ④ 账号自动跟随 App
 *
 * 进页面即 `PkHostOrchestrator.relinkAsync()`（内部有 yfdU 门禁），
 * URL 的 `leoAccountId` 用 pk-node 账号主键（**不是**小猿 userid）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PkH5Screen(
    viewModel: PkViewModel,
    onFinish: () -> Unit = {},
    /**
     * 「H5 要开一个新页面」→ 宿主压一个**新的 H5 容器**（★ 2026-10-04）。
     *
     * 用户要求：「点击按钮 → miuix/aosp 原生转场 → 进入新 h5 容器 → 预测性返回」。
     *
     * H5 的 `openWebView` 桥在 pk-node 侧被实现为 `location.href`（同窗口导航），
     * 所以宿主在 [WebViewClient.shouldOverrideUrlLoading] 里把这个导航**接住** ——
     * 不让它在当前 WebView 里发生，而是交给 App 导航开新容器，
     * 这样原生转场与预测性返回才会执行。
     */
    onOpenChild: (String) -> Unit = {},
) {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        PkHostOrchestrator.startAsync(context)
        onDispose { }
    }
    LaunchedEffect(Unit) {
        // relinkAsync 会阻塞（要真打小猿接口探活），放 IO。
        withContext(Dispatchers.IO) { runCatching { PkHostOrchestrator.relinkAsync() } }
    }

    val pageColor = rememberH5PageColor()

    val hostState = PkHostOrchestrator.state

    // 入口 URL **只算一次**（★ 关键，2026-10-04）。
    //
    // 为什么不能每次重组都调 `h5Url()`：它按 pk-node 的 openPkPage 拼了
    // `&t=<now>` 防缓存参数 —— 每次调用结果都不同。而 `update` 的判据是
    // 「URL 变了才 loadUrl」，若 URL 每次都变 → **每次重组都 loadUrl → 页面无限刷新**。
    //
    // ⚠️ 必须定义在 `webView`（及其 WebViewClient）**之前** ——
    // `shouldOverrideUrlLoading` 要用它当 `exceptUrl`（否则本页会被当成「新页面」自跳）。
    val entryUrl = remember(hostState is PkHostOrchestrator.State.Ready, viewModel.reloadToken) {
        if (hostState is PkHostOrchestrator.State.Ready) PkHostOrchestrator.h5Url() else null
    }
    // 用于「未就绪/出错」提示里展示的地址（与真正加载的那个保持一致）。
    val targetUrl = entryUrl

    val webView = remember {
        WebView(context).apply {
            // 焦点策略：可聚焦（否则整页弹不出输入法）+ 移出前先 clearFocus
            //（「移除时还持着焦点」会触发 Compose 重入合成崩溃）。
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // UA 由 pk-node 自己伪装，宿主不追加（否则出现两段版本号）。
                AppLogger.i("PkH5", "WebView UA = $userAgentString")
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    viewModel.setProgress(5)
                    viewModel.webError = null
                    view?.let { PkJsInjector.markPageStarted(it) }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    viewModel.setProgress(100)
                    view?.title?.takeIf { it.isNotBlank() }?.let { viewModel.webTitle = it }
                    AppLogger.i("PkH5", "onPageFinished: $url")
                    // 取证：页面可见文本（判断卡在哪一步）。
                    view?.evaluateJavascript(
                        "(function(){try{return (document.body&&document.body.innerText||'')" +
                            ".replace(/\\s+/g,' ').slice(0,300)}catch(e){return 'ERR:'+e}})()",
                    ) { v -> AppLogger.i("PkH5", "页面文本: $v") }
                    // 取证：桥归谁 + 身份（PK_ID 应等于 URL 的 leoAccountId）。
                    view?.evaluateJavascript(
                        "(function(){try{var ks=['WebView','CommonWebView','LeoWebView','LeoSecureWebView'];" +
                            "return ks.map(function(k){return k+'='+(typeof window[k])}).join(',')" +
                            "+';LeoCallNative='+(window.LeoWebView&&typeof window.LeoWebView.callNative)" +
                            "+';PK_ID='+(window.__PK_LEO_ID||'-');" +
                            "}catch(e){return 'ERR:'+e}})()",
                    ) { v -> AppLogger.i("PkH5", "桥对象: $v") }
                    // 「老挂戏老叟」注入：去动画 / 自动下一局 / 自动画笔 / **JS 控制台**。
                    view?.let { PkJsInjector.injectIfEnabled(it) }
                    view?.let { probeH5PageColor(it, pageColor, tag = "PkH5") }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        viewModel.webError = "H5 加载失败：${error?.description ?: "未知错误"}"
                        viewModel.setProgress(0)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = handleScheme(
                    request?.url?.toString() ?: return false,
                    onFinish,
                    onOpenChild,
                    exceptUrl = entryUrl,
                )
                @Deprecated("Deprecated in API 24, but kept for older WebView")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    url?.let { handleScheme(it, onFinish, onOpenChild, exceptUrl = entryUrl) } ?: false
            }

            webChromeClient = object : WebChromeClient() {
                private var seen = 0
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    val m = msg ?: return false
                    val text = m.message() ?: return false
                    val keep = seen < 10 ||
                        text.contains("最终结果") ||
                        text.contains("webviewLogin") ||
                        text.contains("isLogin") ||
                        text.contains("homepage") ||
                        text.contains("userInfo") ||
                        text.contains("eruda") ||
                        m.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                    if (keep) {
                        seen++
                        AppLogger.d("PkH5JS", "[${m.messageLevel()}] ${m.sourceId()}:${m.lineNumber()} $text")
                    }
                    return true
                }
            }
        }
    }

    // 只认这个值驱动加载，绝不用 `WebView.url` 判断（H5 是 SPA，内部导航会改它 →
    // 用它判断会反复 loadUrl = 页面一直刷新）。
    var loadedTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { webView.clearFocus() }
            runCatching { webView.stopLoading() }
        }
    }

    val bg = pageColor.value ?: MaterialTheme.colorScheme.surfaceContainer

    // 状态栏图标：底色深 → 浅色图标。
    val view = LocalView.current
    LaunchedEffect(bg) {
        runCatching {
            val act = view.context as? android.app.Activity ?: return@runCatching
            WindowCompat.getInsetsController(act.window, view)
                .isAppearanceLightStatusBars = !isDarkColor(bg)
        }
    }

    // ★ 顶部下移一条状态栏（系统真实高度，带资源兜底 + 日志）。
    val topInset = statusBarTopDp()
    LaunchedEffect(topInset) { AppLogger.i("PkH5", "顶部下移 topInset=$topInset") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg),
    ) {
        // 顶部进度条：只在**首次加载**显示。
        if (viewModel.webProgress in 1..99 && loadedTarget == null) {
            LinearProgressIndicator(
                progress = { viewModel.webProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { webView },
                modifier = Modifier
                    .fillMaxSize()
                    // ★ 让出状态栏那一条：顶上露出根 Column 的 background(bg) = **纯色填充**。
                    .padding(top = topInset),
                update = { v ->
                    // ★ 用只算一次的 entryUrl（见上），不要每次重组都调 h5Url()
                    //   —— 那会因 `&t=<now>` 每次都变而导致页面无限刷新。
                    val url = entryUrl ?: return@AndroidView
                    val target = url to viewModel.reloadToken
                    if (viewModel.webError == null && target != loadedTarget) {
                        loadedTarget = target
                        AppLogger.i("PkH5", "加载内置 pk-node H5：$url")
                        clearHostCookies()
                        v.loadUrl(url)
                    }
                },
                onRelease = { v -> releaseWebView(v) },
            )

            when (hostState) {
                PkHostOrchestrator.State.Idle,
                PkHostOrchestrator.State.Starting,
                -> HostNotice(
                    title = "正在启动内置服务…",
                    detail = "首次启动需要解压并拉起 node（约 2 秒）",
                    busy = true,
                )

                is PkHostOrchestrator.State.Failed -> HostNotice(
                    title = "内置服务启动失败",
                    detail = hostState.message,
                    actionText = "重试",
                    onAction = {
                        PkHostOrchestrator.stop()
                        PkHostOrchestrator.startAsync(context)
                    },
                )

                is PkHostOrchestrator.State.Ready -> {
                    if (targetUrl == null) {
                        HostNotice(title = "内置服务已就绪，但没拿到 H5 地址", detail = "请重试")
                    }
                    if (hostState.accountCount == 0) {
                        AppLogger.w("PkH5", "pk-node 里还没有小猿账号 —— PK 页会显示未登录。")
                    }
                }
            }

            viewModel.webError?.let { err ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Card(modifier = Modifier.padding(24.dp)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = targetUrl ?: "(内置服务未就绪)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { viewModel.reload() }) { Text("重试") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 清空 `127.0.0.1` 下的全部 WebView cookie。
 *
 * PK 链路的身份**唯一真源**是 URL 上的 `leoAccountId`。H5 与代理都在
 * `127.0.0.1:8792`，任何写进这个 host 的 cookie 都会被 H5 请求带上；
 * 一旦来自「别的账号」（旧容器曾把 App 的小猿 cookie 灌进来），就会出错。
 *
 * ⚠️ 补充事实：pk-node 的 `proxyApi()` **从不读 `req.headers.cookie`**
 * （只读 `x-pk-path` / `x-pk-headers`），出站身份完全由 `ctx.jar` 决定 ——
 * 所以清 cookie 是「防污染」，不是「传身份」。
 */
private fun clearHostCookies() {
    runCatching {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        val base = "http://127.0.0.1:${cn.apixiaoyuan.app.core.pk.host.NodeRuntime.DEFAULT_PORT}/"
        (cm.getCookie(base) ?: "").split(";").forEach { kv ->
            val name = kv.trim().substringBefore('=')
            if (name.isNotBlank()) cm.setCookie(base, "$name=; path=/; Max-Age=0")
        }
        cm.flush()
    }
}

/** 「内置服务启动中 / 失败」的提示卡。 */
@Composable
private fun HostNotice(
    title: String,
    detail: String,
    busy: Boolean = false,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(modifier = Modifier.padding(24.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (busy) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(14.dp))
                }
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (actionText != null && onAction != null) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onAction) { Text(actionText) }
                }
            }
        }
    }
}

/**
 * 安全销毁 WebView（由 AndroidView 的 onRelease 调用，此时 View 已移出视图树）。
 *
 * 顺序：先 clearFocus（否则移除时会 Compose 重入合成崩溃）→ 清回调（防泄漏）
 * → stopLoading 再 destroy（否则网络线程回调已销毁的 WebView 会崩）。
 */
internal fun releaseWebView(view: WebView) {
    runCatching {
        view.clearFocus()
        view.stopLoading()
        view.webViewClient = WebViewClient()
        view.settings.javaScriptEnabled = false
        (view.parent as? ViewGroup)?.removeView(view)
        view.removeAllViews()
        view.destroy()
    }
}

/**
 * 处理「H5 自己发起的导航」（宿主侧兜底 + **新容器跳转**）。
 *
 * 两台事：
 *
 * 1. `leo://close|back|finish` → 关容器（回上一级）。
 * 2. **本机同源的「新页面」导航 → 交给 App 导航开一个「新的 H5 容器」**
 *    （★ 2026-10-04，用户要求「进入新 h5 容器」）。
 *
 * ## 为什么第 2 条是必须的
 *
 * H5 里的跳转走桥 `openSchema('native://openWebView?url=…')`，
 * 而 pk-node 的 `H5_INJECT` 把 `openWebView` 实现成 **`location.href` 同窗口导航**
 * （见 `pk-h5-proxy.js:374`）。同窗口导航 = 当前 WebView 直接换 URL，
 * **App 导航完全不知道** → 原生转场 / 预测性返回都不会播。
 *
 * 所以宿主在这里把它**接住**（返回 true = 不让 WebView 自己导航），
 * 改为 `onOpenChild(url)` → `navController.navigate(RoutePkH5(url))`
 * → 压一个新的 H5 容器 → 转场与预测性返回自然生效。
 *
 * @return true 表示已消费该 URL（WebView 不得自行导航）
 */
internal fun handleScheme(
    url: String,
    onFinish: () -> Unit,
    onOpenChild: (String) -> Unit,
    /**
     * 「当前容器自己的 URL」。命中即返回 false 放行 ——
     * 否则下级页的 `openWebView(self)` 会无限自跳（每跳一次压一个新容器）。
     */
    exceptUrl: String? = null,
): Boolean {
    // ★ 2026-10-04：pk-node 的 closeWebView 在 App 里发 `leo://close`
    //   （浏览器 iframe 里它才是 history.back()）。见 pk-h5-proxy.js 的 closeWebView。
    if (url.startsWith("leo://")) {
        val host = Uri.parse(url).host ?: return false
        if (host == "close" || host == "back" || host == "finish") {
            AppLogger.i("PkH5", "H5 请求返回 → 交给导航：$url")
            onFinish()
            return true
        }
        // 其余自定义能力放行给 pk-node 的 JS 桥。
        return false
    }

    // 自己：放行（同文档 hash 导航 / reload）。
    if (exceptUrl != null && url == exceptUrl) return false

    // ★ 「新的 H5 页面」→ 由 App 导航开新容器（原生转场 + 预测性返回）。
    //   判据：本机同源下的**另一个文档**（PK H5 页面都在 /pk-h5 或 /pk-h5-cdn）。
    //   同文档的 hash 路由（`#/xxx`）不算「新页面」，不能拦 —— 拦了会把 SPA 换页也变成新容器。
    if (url.contains("/pk-h5/") || url.contains("/pk-h5-cdn/")) {
        // 排除「同一个 URL 只差 hash」的情况（WebView 在纯 hash 变化时通常不会
        // 触发本回调，这里再兜一层，避免误把 SPA 换页当新页面）。
        AppLogger.i("PkH5", "H5 请求新页面 → 开新容器：$url")
        onOpenChild(url)
        return true
    }

    return false
}

/**
 * 是不是**结算页**（PK 打完那一屏）。
 *
 * ★ 2026-10-04 新增（用户要求）：
 * > 「PK 打完跳转结算后就可以关闭 PK 页面的容器，只保留主页和结算页面的容器了。
 * >   这样从结算页面返回就不会继续回到 PK 页面了」
 *
 * 判据来自 H5 源码（`useNavigation-legacy.C-iCgWHr.js`，它拼这些 URL 去 openWebView）：
 *
 * | 页面 | URL |
 * |---|---|
 * | 口算/诗词对局结算 | `/bh5/leo-web-oral-pk/result.html?pkIdStr=…` |
 * | 活动结算 | `/bh5/leo-web-oral-pk/pk-activity-result.html?…` |
 * | 道具赛结算 | `/bh5/leo-web-oral-pk/prop-result.html?pkIdStr=…` |
 *
 * 注意只认**路径**里的 `result.html` 系列，不认 query（`isFromHistory=true` 是
 * 「从历史进结算」，路径仍是 result.html）。
 */
internal fun isPkResultUrl(url: String): Boolean {
    val u = url.substringBefore('#')
    return u.contains("/result.html") ||
        u.contains("pk-activity-result.html") ||
        u.contains("/prop-result.html")
}