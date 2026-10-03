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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import cn.apixiaoyuan.app.core.design.component.LocalTopBarInset
import cn.apixiaoyuan.app.core.design.component.isDarkColor
import cn.apixiaoyuan.app.core.design.component.probeH5PageColor
import cn.apixiaoyuan.app.core.design.component.rememberH5PageColor
import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.navigation.transition.AospTransitionDurationMs
import cn.apixiaoyuan.app.core.navigation.transition.CrossActivityDrift
import cn.apixiaoyuan.app.core.navigation.transition.MiuixCoverAlpha
import cn.apixiaoyuan.app.core.navigation.transition.MiuixCoverParallax
import cn.apixiaoyuan.app.core.navigation.transition.MiuixTransitionDurationMs
import cn.apixiaoyuan.app.core.oldsimian.PkJsInjector
import cn.apixiaoyuan.app.core.pk.host.PkHostOrchestrator
import cn.apixiaoyuan.app.core.design.theme.PageTransitionPrefs
import cn.apixiaoyuan.app.core.design.theme.PageTransitionAnimation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 口算 PK 页 —— **纯 WebView 容器**（★ 2026-10-04 按用户要求推倒重写）。
 *
 * # 用户要求（逐字）
 *
 * > 「直接把 pk h5 重写，**页面逻辑全用 pk-node 的**，不过只要 webview 里的，
 * >   账号自动切换为 app 的。」
 *
 * 三句话对应三件事：
 *
 *  1. **页面逻辑全用 pk-node 的** —— 本容器**不再实现任何页面逻辑**。
 *     桥（`getUserInfo` / `requestConfig` / `dataEncrypt` / `dataDecrypt` /
 *     `openWebView` …）、出站代理（补 sign / 风控头 / 用账号 jar）、
 *     页面跳转、`__PK_USER` 注入 —— 全都在 pk-node 的 `H5_INJECT` 里，
 *     由 **node 服务端**完成。宿主不再 `addJavascriptInterface`（那会「先到先得」
 *     把 pk-node 更完整的 JS 桥顶掉，见 `git log` 里 aa58bbd 的教训）。
 *
 *  2. **只要 webview 里的** —— 删掉历代在 Kotlin 侧堆的容器补丁：
 *     cookie 灌入 / `leoAccountId` 拼接 / `dataEncrypt` 等宿主桥 / scheme 特判 /
 *     刷轮数悬浮入口 …… 这些要么是 pk-node 已经做好的（重复），
 *     要么是在跟 pk-node 打架（bug 的来源）。
 *     容器只负责：**加载一个 URL** + **加载失败时给提示**。
 *
 *  3. **账号自动切换为 app 的** —— [PkHostOrchestrator] 每次进入本页都会
 *     把 App 当前身份推给 pk-node（`relinkAsync`，身份没变则跳过），
 *     并把 pk-node 的账号主键 [PkNodeLink.Account.id] 拼进 URL 的
 *     `leoAccountId`（**不是**小猿 userid —— 那是 `85f5ce2` 修过的真 bug）。
 *     所以「App 切了号 → 进 PK 页 → pk-node 用新号」是自动的。
 *
 * # 与 pk-node 自带网页的等价性
 *
 * pk-node 的 `public/index.html` 里也有一个 PK 页容器，做法是：
 *
 * ```html
 * <iframe src="/pk-h5/pk.html?leoAccountId=<库主键>&pkbot=off&t=<now>"></iframe>
 * ```
 *
 * 本容器做的是**同一件事**，只是把 `<iframe>` 换成全屏 `WebView`：
 * 同一个 URL、同一份服务端逻辑。差别只有两处，都是宿主必须做的：
 *
 * | | pk-node 网页 | 本容器 |
 * |---|---|---|
 * | cookie | 浏览器自动带（同源 iframe 会话）| 宿主**主动清掉** App 域 cookie（见下）|
 * | 顶部安全区 | 浏览器给 iframe 独立布局视口 | 全屏 WebView 需让出状态栏那一条 |
 *
 * # 为什么必须清 App 域 cookie（★ 关键，别再删）
 *
 * H5 与代理**都在 `127.0.0.1:8792` 上**，而小猿主域（`*.yuanfudao.com`）的 cookie
 * 与它**不同域**，WebView 本来就不会带过去。但**必须防一手**：App 自己的
 * `CookieManager` 里可能被动地存过 `127.0.0.1` 的 cookie（例如以前把 App 的小猿
 * cookie 灌进来过），一旦混进去，pk-node 按 `leoAccountId` 选账号 jar 的逻辑就白做了
 * —— 页面会带上「不属于这个账号」的 cookie，正是用户说的「cookie 传递错误」。
 *
 * 所以每次加载前：**把 `127.0.0.1` 下的所有 cookie 清空**，让 PK 链路的身份
 * 100% 由 URL 的 `leoAccountId` 决定（唯一真源）。
 *
 * # 保留的东西（用户明确要过）
 *
 *  - **JS 控制台**（Eruda）：走 [PkJsInjector]，是 `evaluateJavascript` 注入，
 *    与 `addJavascriptInterface` 是两条独立的路 —— 不受「不注册宿主桥」影响。
 *  - **原生切页转场**：用户要求「切 H5 要用 miuix/aosp 的原生动画，且只在 App 侧」。
 *    数值**引用** `AppNavTransition` 的同一份定义（不自己写数字）。
 *  - **顶部下移 + 纯色填充**：让 H5 抬头不被状态栏挡住。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PkH5Screen(
    viewModel: PkViewModel,
    onFinish: () -> Unit = {},
) {
    val context = LocalContext.current

    // 进页面就把内置服务拉起来（幂等：已起过直接返回）。
    // 再跑一次「账号跟随」—— App 切了号的话，这里把新身份推给 pk-node 并重算
    // leoAccountId（内部有 yfdU 门禁，身份没变不会重复推）。
    DisposableEffect(Unit) {
        PkHostOrchestrator.startAsync(context)
        onDispose { }
    }
    LaunchedEffect(Unit) {
        // relinkAsync 会阻塞（要真打小猿接口探活），放 IO。
        withContext(Dispatchers.IO) { runCatching { PkHostOrchestrator.relinkAsync() } }
    }

    // H5 页面底色：状态栏那条带子（edge-to-edge 下露出的地方）与页面同色。
    val pageColor = rememberH5PageColor()

    // 切页转场状态。必须用 holder 对象：WebView 是在 `remember { WebView(...)... }`
    // 里创建的，那个 lambda **不是 composable 作用域**，引用不到 composable 局部状态。
    val navAnim = remember { NavAnimHolder() }

    val webView = remember {
        WebView(context).apply {
            // ---- 焦点策略：可聚焦 + 移出前先 clearFocus ----
            //
            // Chromium **不用子 View 承载输入框** —— 它直接在 WebView 自身建立
            // `InputConnection`。所以「不可聚焦」的代价是**整个页面弹不出输入法**
            // （用户报过「pk h5 输入文字时应该能调用输入法」）。
            //
            // 而「移除时还持着焦点」正是那条 Compose 重入合成崩溃
            // （ViewGroup.removeViewInLayout → rootViewRequestFocus →
            //  "pending composition has not been applied"）的触发条件。
            // 解法：允许聚焦，但在 [releaseWebView] / [goBackOrFinish] 里先清掉。
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // UA 由 pk-node 在页面里自己伪装（`YuanSouTiKouSuan/3.141.1`）。
                // 宿主**不再**追加 —— 追加会让 UA 里出现两段版本号。
                AppLogger.i("PkH5", "WebView UA = $userAgentString")
            }

            // ★★ 刻意**不注册任何** `addJavascriptInterface`（理由见文件头「②」）。
            //
            // pk-node 的 H5 自带完整 JS 桥，挂载方式是 `if (!window[name]) window[name] = bridge`
            // —— **先到先得**。宿主抢注任何名字，都会让 pk-node 更完整的实现被静默跳过。
            //
            // ★ 也**不注册** `shouldInterceptRequest`：H5 的业务请求会被它自己的
            //   XHR hook 改写到同源 `/api/pk/h5/api`，由 node 服务端转发
            //   （那是它能拿到账号 jar 与设备链的地方）。宿主再插一手只会画蛇添足。
            //
            // ★ 也**不再**把 App cookie 灌进 CookieManager（旧容器的做法）：
            //   PK 链路的身份唯一真源是 URL 的 `leoAccountId`，多灌一份只会打架。
            //   见下方 `clearHostCookies`。

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    viewModel.setProgress(5)
                    viewModel.webError = null
                    // 重置注入标记：一次加载 onPageFinished 可能回调多次，
                    // 不重置会让脚本（尤其带 setInterval 的）叠加注入多轮。
                    view?.let { PkJsInjector.markPageStarted(it) }
                    navAnim.onMainNav(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    viewModel.setProgress(100)
                    view?.title?.takeIf { it.isNotBlank() }?.let { viewModel.webTitle = it }
                    AppLogger.i("PkH5", "onPageFinished: $url")
                    // 取证：页面可见文本（一眼判断卡在哪一步）。
                    view?.evaluateJavascript(
                        "(function(){try{return (document.body&&document.body.innerText||'')" +
                            ".replace(/\\s+/g,' ').slice(0,300)}catch(e){return 'ERR:'+e}})()",
                    ) { v -> AppLogger.i("PkH5", "页面文本: $v") }
                    // 取证：桥归谁了 + 身份。pk-node 的桥挂上后 `window.LeoWebView` 存在；
                    // `PK_ID` 应当等于 URL 里的 leoAccountId（= pk-node 账号主键）。
                    view?.evaluateJavascript(
                        "(function(){try{var ks=['WebView','CommonWebView','LeoWebView','LeoSecureWebView'];" +
                            "return ks.map(function(k){return k+'='+(typeof window[k])}).join(',')" +
                            "+';LeoCallNative='+(window.LeoWebView&&typeof window.LeoWebView.callNative)" +
                            "+';PK_ID='+(window.__PK_LEO_ID||'-');" +
                            "}catch(e){return 'ERR:'+e}})()",
                    ) { v -> AppLogger.i("PkH5", "桥对象: $v") }
                    // 「老挂戏老叟」注入：去动画 / 自动下一局 / 自动画笔 / **JS 控制台**。
                    view?.let { PkJsInjector.injectIfEnabled(it) }
                    // 每次都探：SPA 内部导航会换「页面」，底色未必相同。
                    view?.let { probeH5PageColor(it, pageColor, tag = "PkH5") }
                    navAnim.finish()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    // 只对主文档报错 —— 子资源失败（图片、埋点）不该阻塞整页。
                    if (request?.isForMainFrame == true) {
                        viewModel.webError = "H5 加载失败：${error?.description ?: "未知错误"}"
                        viewModel.setProgress(0)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = handleScheme(request?.url?.toString() ?: return false, onFinish)

                @Deprecated("Deprecated in API 24, but kept for older WebView")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    url?.let { handleScheme(it, onFinish) } ?: false
            }

            // H5 的 console 落盘：排障的唯一可靠证据来源。
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
                        AppLogger.d(
                            "PkH5JS",
                            "[${m.messageLevel()}] ${m.sourceId()}:${m.lineNumber()} $text",
                        )
                    }
                    return true
                }
            }
        }
    }

    // 原生**主动驱动加载**的目标 URL（只认这个值，绝不用 `WebView.url` 做判断 ——
    // H5 是 SPA，内部导航会改 `WebView.url`，用它判断会导致反复 loadUrl = 页面一直刷新）。
    var loadedTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            // 不在这里 destroy（见 AndroidView.onRelease 的注释），只做非破坏性清理。
            // `clearFocus()` 是防崩溃的关键（见上面焦点策略）。
            runCatching { webView.clearFocus() }
            runCatching { webView.stopLoading() }
        }
    }

    BackHandler(enabled = true) { goBackOrFinish(webView, onFinish, navAnim) }

    val hostState = PkHostOrchestrator.state
    val targetUrl = PkHostOrchestrator.h5Url()

    val bg = pageColor.value ?: MaterialTheme.colorScheme.surfaceContainer

    // 状态栏图标：底色深 → 浅色图标（否则时间/电量看不见）。
    val view = LocalView.current
    LaunchedEffect(bg) {
        runCatching {
            val act = view.context as? android.app.Activity ?: return@runCatching
            WindowCompat.getInsetsController(act.window, view)
                .isAppearanceLightStatusBars = !isDarkColor(bg)
        }
    }

    // ---- 原生切页转场（数值取自 AppNavTransition 的同一份定义）----
    val animPref = PageTransitionPrefs.animation
    val screenW = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val driftPx = with(LocalDensity.current) { CrossActivityDrift.toPx() }
    val spec = remember(animPref, screenW, driftPx) { navAnimSpecOf(animPref, screenW, driftPx) }
    val t by animateFloatAsState(
        targetValue = navAnim.progress,
        animationSpec = tween(durationMillis = spec.durationMs),
        label = "pk-h5-nav",
    )
    val enterPx = screenW * spec.enterFraction
    val coverPx = screenW * spec.coverFraction

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 铺满整页：状态栏那条带子（edge-to-edge 下露出的窗口底色）与 H5 页面同色。
            .background(bg),
    ) {
        // 顶部进度条：只在**首次加载**显示（SPA 内部导航不显示，否则一闪一闪）。
        if (viewModel.webProgress in 1..99 && loadedTarget == null) {
            LinearProgressIndicator(
                progress = { viewModel.webProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- 内容区：就是 WebView（+ 就绪前的提示）----
        //
        // 顶部**下移一条状态栏**（[LocalTopBarInset]）：用户指出「h5 容器顶部应该下移，
        // 因为手机菜单挡住了」—— 这套 H5 不处理 `safe-area-inset-top`，
        // 必须由宿主让出那一条。露出来的正是根 Column 的 `background(bg)` = 纯色填充。
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { webView },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = LocalTopBarInset.current)
                    // 进入方向的原生转场：动的是 AndroidView 这个 View 的 graphicsLayer，
                    // **不动 WebView 内部、不动网页**（用户要求「只在 app 中使用网页没有动画」）。
                    .graphicsLayer {
                        if (navAnim.visible && navAnim.entering) {
                            translationX = (1f - t) * enterPx
                            alpha = t.coerceIn(0f, 1f)
                        }
                    },
                update = { v ->
                    val url = PkHostOrchestrator.h5Url() ?: return@AndroidView
                    val target = url to viewModel.reloadToken
                    if (viewModel.webError == null && target != loadedTarget) {
                        loadedTarget = target
                        AppLogger.i("PkH5", "加载内置 pk-node H5：$url")
                        // ★ 清掉本 host 的 cookie，让身份唯一真源 = URL 的 leoAccountId。
                        clearHostCookies()
                        v.loadUrl(url)
                    }
                },
                onRelease = { v -> releaseWebView(v) },
            )

            // ---- 被覆盖层（返回/退页方向）----
            //
            // 真机实测教训：这层曾经 `matchParentSize()` 铺满整屏 → **整屏白闪**
            // （H5 底色就是纯白，退页瞬间 WebView 也是白的，两层白叠一起）。
            // 现在只铺**顶部那条状态栏带子**：WebView 区域完全不动，不会白闪。
            //
            // 局限（如实说明）：拿不到旧 H5 的画面快照（`onPageStarted` 时旧内容已清空），
            // 所以被覆盖层只能用底色代替 —— 视差只出现在收尾那一次。
            if (navAnim.visible && !navAnim.entering) {
                val insetsPx = with(LocalDensity.current) { LocalTopBarInset.current.toPx() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(LocalDensity.current) { insetsPx.toDp() })
                        .align(Alignment.TopCenter)
                        .graphicsLayer {
                            translationX = (1f - t) * -coverPx
                            alpha = ((1f - t) * (1f - spec.coverAlpha) + spec.coverAlpha)
                                .coerceIn(0f, 1f)
                        }
                        .background(bg),
                )
            }

            // ---- 就绪前的覆盖层 / 失败提示 ----
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
                        AppLogger.w(
                            "PkH5",
                            "pk-node 里还没有小猿账号 —— PK 页会显示未登录。" +
                                "请在 pk-node 管理后台加一个账号（或从 App 的账号页导入）。",
                        )
                    }
                }
            }

            // ---- H5 主文档级错误覆盖层 ----
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
 * # 为什么（★ 别再删）
 *
 * PK 链路的身份**唯一真源**是 URL 上的 `leoAccountId`（pk-node 据此从它库里
 * 选账号 jar）。H5 页面与代理都在 `127.0.0.1:8792`，**任何**写进这个 host 的
 * cookie 都会被 H5 请求带上、并被 node 侧当作额外输入 —— 一旦它来自
 * 「别的账号」（旧容器曾把 App 的小猿 cookie 灌进来），就会出现
 * 「cookie 传递错误」。
 *
 * 所以每次加载前清干净：**让 pk-node 完全按 leoAccountId 决定身份**。
 *
 * 注意：这只动 `127.0.0.1` 这个 host，不碰小猿域、不碰 App 自己的会话。
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
        // 917 也要管：管理后台写过 pk_sid，PK 页不需要它。
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
 * 「返回」的统一实现：先退 H5 历史，退无可退才关容器回主页。
 *
 * H5 的 hash 路由（`#/xxx`）也会被 Chromium 记进 navigation controller，
 * 所以 `canGoBack()` 同样覆盖 SPA 内部的前进后退。
 */
private fun goBackOrFinish(webView: WebView, onFinish: () -> Unit, navAnim: NavAnimHolder?) {
    if (webView.canGoBack()) {
        // 先铺「返回方向」的转场，再退 —— 否则旧页恢复前会先白闪一下。
        // 刻意**不清焦点**：用户可能正在 `<input>` 里打字，退一级不等于收输入法。
        runCatching { navAnim?.onBackNav() }
        webView.goBack()
    } else {
        // 首页再返回 = 关容器。★ 必须**先 clearFocus 再 onFinish**：
        // onFinish 会弹出整个页面 → AndroidView 被移除，而「移除时还持着焦点」
        // 正是 Compose 重入合成崩溃的触发条件。
        webView.clearFocus()
        onFinish()
    }
}

/**
 * 安全销毁 WebView（由 AndroidView 的 onRelease 调用，此时 View 已移出视图树）。
 *
 * 顺序有讲究（真机崩溃倒逼出的三条）：
 *  1. **先 clearFocus**：否则 `removeViewInternal` 会走 `rootViewRequestFocus()`
 *     → 重入合成崩溃。
 *  2. **清空回调与 JS 开关**：`webViewClient` 持有 viewModel / onFinish 引用，
 *     不清会让整棵 Activity 泄漏。
 *  3. **stopLoading 再 destroy**：否则网络线程回调已销毁的 WebView
 *     会触发 chromium native 层崩溃。
 */
private fun releaseWebView(view: WebView) {
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
 * 拦截 `leo://` scheme（宿主侧兜底）。
 *
 * 内置 node 架构下绝大多数能力已被 pk-node 的 JS 桥接管（它自己实现
 * `openWebView` / `closeWebView`），宿主只处理「页面自己冒出来」的
 * `close` / `back` / `finish`。
 *
 * @return true 表示已消费该 URL
 */
private fun handleScheme(url: String, onFinish: () -> Unit): Boolean {
    if (!url.startsWith("leo://")) return false
    val host = Uri.parse(url).host ?: return false
    if (host == "close" || host == "back" || host == "finish") {
        onFinish()
        return true
    }
    // 其余自定义能力放行给 pk-node 的 JS 桥（拦了反而会让多 WebView 跳转失效）。
    return false
}

// ============================================================================
// H5 内部跳转的原生转场
// ============================================================================

/**
 * H5 内部跳转的**原生切页转场**。
 *
 * # 用户要求（逐字）
 *
 * > 「切换 h5 要用 miuix 或 aosp 的原生动画并且**只在 app 中使用网页没有动画**」
 * > 「切换页面的动画应该**联通 app 的切页动画**而不是自己乱写」
 *
 *  1. **只在 App 侧动**：网页（WebView）完全不动画。转场是 AndroidView 的
 *     `graphicsLayer` 参数，**绝不**改 WebView 内部或调用网页 JS。
 *  2. **对齐 App 已有的两套转场**（跟随 «设置 → 过渡动画»），数值**引用**
 *     [cn.apixiaoyuan.app.core.navigation.transition.AppNavTransition] 的同一份定义：
 *     - `MIUIX` → 整屏滑 + 被覆盖页 0.25 宽视差 + 轻微淡出（`NavTransitions.MiuixDefault`）
 *     - `AOSP` → **96dp 横向漂移** + 450ms（`AppNavTransition.ClassicActivityOpen/Close`）
 *
 * # 为什么需要它
 *
 * app 级二级页转场管不到 H5 内部跳转：始终是同一个 WebView、同一条路由，
 * 跳转是 pk-node 在页面里 `location.href = …`（浏览器式硬跳）。
 *
 * # 为什么状态要放在对象里
 *
 * WebView 是在 `remember { WebView(...).apply { ... } }` 里创建的，
 * 那个 lambda **不是 `@Composable` 作用域**，引用不到 composable 局部状态。
 * 所以状态封进这个类，两边都能读。
 *
 * # 什么时候播
 *
 * 只在**主文档导航**（`pk.html` → 荣誉榜页 / 对局页 …）时播。
 * `pk.html` 内部的换页走 hash 路由（`#/xxx`）—— 给它也播会变成「点什么都闪一下」。
 */
private class NavAnimHolder {
    /** 0 → 1 的进度，由 composable 侧的 `animateFloatAsState` 驱动。 */
    val progress: Float get() = _progress
    var visible by mutableStateOf(false)
        private set
    /** true = 进入（新页滑入）；false = 返回（被覆盖层滑回）。 */
    var entering by mutableStateOf(true)
        private set

    private var _progress by mutableStateOf(0f)

    fun onMainNav(url: String?) {
        if (!isMainNav(url)) return
        entering = true
        visible = true
        _progress = 0f
    }

    fun onBackNav() {
        entering = false
        visible = true
        _progress = 0f
    }

    fun finish() {
        _progress = 1f
        visible = false
    }

    /**
     * 是否是「进入另一个文档」（而不是同文档的 hash / query 变化）。
     *
     * 例：
     *  - `pk.html#/a` → `pk.html#/b`  → false（SPA 换页，不播）
     *  - `pk.html`     → `external.html` → true
     */
    private fun isMainNav(url: String?): Boolean {
        val u = url ?: return false
        if (!u.contains("/pk-h5") && !u.contains("/pk-h5-cdn")) return false
        // 同文档 hash 变化：只差 `#` 之后的部分。
        val last = lastUrl
        lastUrl = u
        if (last == null) return false
        val a = last.substringBefore('#')
        val b = u.substringBefore('#')
        return a != b
    }

    private var lastUrl: String? = null
}

/**
 * 一个「原生转场」的观感参数。
 *
 * ★ 数值**全部引用** App 转场的定义（不自己写）—— 用户要求
 * 「联通 app 的切页动画而不是自己乱写」。
 */
private class NavAnimSpec(
    /** 进场页滑入距离（占屏宽的比例）。 */
    val enterFraction: Float,
    /** 被覆盖页的视差距离（占屏宽比例）。 */
    val coverFraction: Float,
    /** 被覆盖页的最终不透明度。 */
    val coverAlpha: Float,
    /** 时长（毫秒）。 */
    val durationMs: Int,
)

/** 取当前设置对应的转场参数（数值取自 AppNavTransition）。 */
private fun navAnimSpecOf(
    anim: PageTransitionAnimation,
    widthPx: Float,
    /** AOSP 的 96dp 已换算好的像素值（CompositionLocal 只能在 composable 里读）。 */
    driftPx: Float,
): NavAnimSpec = when (anim) {
    // miuix：整屏滑 + 被覆盖页 0.25 宽视差、轻微淡出
    PageTransitionAnimation.MIUIX -> NavAnimSpec(
        enterFraction = 1f,
        coverFraction = MiuixCoverParallax,
        coverAlpha = MiuixCoverAlpha,
        durationMs = MiuixTransitionDurationMs,
    )
    // aosp：96dp 横向漂移（不是整屏滑），被覆盖页不视差
    PageTransitionAnimation.AOSP -> NavAnimSpec(
        enterFraction = driftPx / widthPx.coerceAtLeast(1f),
        coverFraction = 0f,
        coverAlpha = 1f,
        durationMs = AospTransitionDurationMs,
    )
}
