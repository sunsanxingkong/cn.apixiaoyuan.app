package cn.apixiaoyuan.app.feature.pk

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import cn.apixiaoyuan.app.core.design.component.isDarkColor
import cn.apixiaoyuan.app.core.design.component.probeH5PageColor
import cn.apixiaoyuan.app.core.design.component.rememberH5PageColor
import cn.apixiaoyuan.app.core.design.theme.PageTransitionPrefs
import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.navigation.transition.appNavTransition
import cn.apixiaoyuan.app.core.oldsimian.PkJsInjector
import cn.apixiaoyuan.app.core.pk.host.PkHostOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.nav.runtime.NavChange
import top.yukonga.miuix.kmp.nav.transition.NavGesture
import top.yukonga.miuix.kmp.nav.transition.NavRole
import top.yukonga.miuix.kmp.nav.transition.NavSettle
import top.yukonga.miuix.kmp.nav.transition.NavSettlePhase
import top.yukonga.miuix.kmp.nav.transition.NavSwipeEdge
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope

/**
 * 口算 PK 页 —— **纯 WebView 容器**（2026-10-04）。
 *
 * # 用户要求（逐字）
 *
 * > 「直接把 pk h5 重写，**页面逻辑全用 pk-node 的**，不过只要 webview 里的，
 * >   账号自动切换为 app 的。」
 * > 「翻页动画应该用 app 的…翻页应该是**再覆盖一层 h5 页面**而不是乱写」
 * > 「应该还有预测性返回，把 app 的逻辑读一遍 **1 比 1 使用**」
 *
 * # ① 页面逻辑全用 pk-node 的
 *
 * 桥（`getUserInfo` / `requestConfig` / `dataEncrypt` / `dataDecrypt` / `openWebView` …）、
 * 出站代理（补 sign / 风控头 / 用账号 jar）、页面跳转、`__PK_USER` 注入
 * —— 全在 pk-node 的 `H5_INJECT` 里，由 node 服务端完成。
 *
 * 宿主**刻意不注册任何** `addJavascriptInterface`：pk-node 的 JS 桥用
 * `if (!window[name]) window[name] = bridge` 挂载（**先到先得**），
 * 宿主抢注任何名字都会让更完整的实现被静默跳过。也不注册 `shouldInterceptRequest`。
 *
 * # ② 转场：**直接套用 App 的转场实现**（0 公式复制）
 *
 * miuix 把转场的「行为」与「驱动」解耦了：
 *  - `NavTransition.transformEntry(scope: NavTransitionScope): Modifier` 是 **public**；
 *  - `NavTransitionScope` 是 **public interface**，字段全是延迟读取源。
 *
 * 所以这里**不重写任何公式**：直接把 `appNavTransition(设置)` 返回的对象
 * （MIUIX = `NavTransitions.MiuixDefault`；AOSP = `AospNavTransition`，
 * 含 `CrossActivityPredictive` 预测性返回）拿来，喂两个自己造的 scope
 * （当前页 / 被覆盖层），用 `transformEntry(scope)` 产出 Modifier。
 * 与 App 二级页转场**共用同一份实现** —— 改 App 转场，这里跟着变。
 *
 * # ③ 被覆盖层 = **再覆盖一层 H5 页面**（旧页快照）
 *
 * 跳转前（`shouldOverrideUrlLoading`，此刻 WebView 还画着旧页）用
 * `View.draw(Canvas)` 抓快照，作为转场的被覆盖层 —— 底下是**真实的旧页面**，
 * 不是纯色板。
 *
 * # ④ 顶部下移 + 纯色填充
 *
 * **必须用系统状态栏真实高度**（`WindowInsets.statusBars`），**不能**用
 * `LocalTopBarInset` —— 那是 `AppScaffold` 下发的「顶栏高度」，而本页是
 * NavHost 里的全屏二级页，**不在 AppScaffold 里**，它恒为 `0.dp`。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PkH5Screen(
    viewModel: PkViewModel,
    onFinish: () -> Unit = {},
) {
    val context = LocalContext.current

    // 进页面把内置服务拉起来（幂等），并把 App 当前身份同步给 pk-node。
    DisposableEffect(Unit) {
        PkHostOrchestrator.startAsync(context)
        onDispose { }
    }
    LaunchedEffect(Unit) {
        // relinkAsync 会阻塞（要真打小猿接口探活），放 IO。
        withContext(Dispatchers.IO) { runCatching { PkHostOrchestrator.relinkAsync() } }
    }

    val pageColor = rememberH5PageColor()

    // 转场状态机。必须可跨作用域：WebView 是在 `remember { WebView(...).apply { } }`
    // 里创建的，那个 lambda **不是 composable 作用域**。
    val navState = remember { H5NavState() }

    val webView = remember {
        WebView(context).apply {
            // ---- 焦点策略：可聚焦 + 移出前先 clearFocus ----
            //
            // Chromium 直接在 WebView 自身上建立 `InputConnection`，所以
            // 「不可聚焦」的代价是**整个页面弹不出输入法**（用户报过）。
            // 而「移除时还持着焦点」正是那条 Compose 重入合成崩溃的触发条件。
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

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    viewModel.setProgress(5)
                    viewModel.webError = null
                    view?.let { PkJsInjector.markPageStarted(it) }
                    navState.onPageStarted(url)
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
                    // 取证：桥归谁 + 身份（PK_ID 应等于 URL 里的 leoAccountId）。
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
                    navState.onPageFinished()
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
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    // ★ 跳转前抓旧页快照 = 转场里「再覆盖一层 H5 页面」那一层。
                    view?.let { navState.captureCovered(it) }
                    return handleScheme(url, onFinish)
                }

                @Deprecated("Deprecated in API 24, but kept for older WebView")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    view?.let { navState.captureCovered(it) }
                    return url?.let { handleScheme(it, onFinish) } ?: false
                }
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

    // 原生**主动驱动加载**的目标（只认它，绝不用 `WebView.url` 判断 ——
    // H5 是 SPA，内部导航会改 `WebView.url`，用它判断会反复 loadUrl = 页面一直刷新）。
    var loadedTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { webView.clearFocus() }
            runCatching { webView.stopLoading() }
        }
    }

    // ---- 返回：预测性返回（跟手）+ 普通返回 ----
    //
    // 用 `PredictiveBackHandler` 拿真实手势进度，喂给**同一个** `NavTransition`
    // （与 App 二级页的预测性返回同源）。
    PredictiveBackHandler(enabled = webView.canGoBack()) { progressFlow ->
        navState.beginGesture()
        try {
            progressFlow.collect { e: BackEventCompat ->
                navState.updateGesture(
                    progress = e.progress,
                    touchY = e.touchY,
                    fromEdge = e.swipeEdge != BackEventCompat.EDGE_NONE,
                )
            }
            // 手势走完 = 提交返回：退一级 H5 历史。
            navState.commitGesture()
            webView.goBack()
        } catch (t: Throwable) {
            // 手势取消：回弹。
            navState.cancelGesture()
        } finally {
            navState.endGesture()
        }
    }

    BackHandler(enabled = true) { goBackOrFinish(webView, onFinish, navState) }

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

    // ★★ 2026-10-04 修正：**系统状态栏的真实高度**。
    //   不能用 `LocalTopBarInset` —— 那是 AppScaffold 下发的「顶栏高度」，
    //   而本页是 NavHost 里的全屏二级页（entry<RoutePk>），不在 AppScaffold 里，
    //   所以它恒为 `0.dp`（我连续两版都栽在这，等于没下移）。
    val statusBarTopDp = with(LocalDensity.current) {
        WindowInsets.statusBars.getTop(this).toDp()
    }

    // ---- 转场：直接把 App 的那个 NavTransition 套上来（0 公式复制）----
    val transition: NavTransition = appNavTransition(PageTransitionPrefs.animation)
    val density = LocalDensity.current
    var layerSize by remember { mutableStateOf(IntSize.Zero) }

    // ★ 两个 scope：当前页（Incoming/Outgoing）与被覆盖层（Covered）。
    //   注意它们的实例必须稳定（`remember`），否则每帧重建会丢延迟读取。
    val pageScope = remember(navState) { H5NavScope(navState, covered = false) }
    val coveredScope = remember(navState) { H5NavScope(navState, covered = true) }
    pageScope.update(layerSize, density)
    coveredScope.update(layerSize, density)

    // 程序化进度：用 LinearEasing —— 曲线形状由 App 的 NavTransition 决定
    // （它读 relativeDepth，内部自己 shapedTopProgress）。
    val animatedProgress by animateFloatAsState(
        targetValue = navState.progress,
        animationSpec = tween(durationMillis = 450, easing = LinearEasing),
        label = "pk-h5-nav",
    )
    navState.animatedProgress = animatedProgress

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg),
    ) {
        // 顶部进度条：只在**首次加载**显示（SPA 内部导航不显示，否则一闪一闪）。
        if (viewModel.webProgress in 1..99 && loadedTarget == null) {
            LinearProgressIndicator(
                progress = { viewModel.webProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .onSizeChanged { layerSize = it },
        ) {
            // ---- 被覆盖层：「再覆盖一层 H5 页面」（旧页快照）----
            //
            // 用户明确要求「翻页应该是再覆盖一层 h5 页面而不是乱写」——
            // 所以这一层是**真实的旧页画面**（跳转前抓的 Bitmap）。
            // 它由同一个 `NavTransition` 驱动（Covered 角色 → 拿视差）。
            navState.covered?.let { snapshot ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(0f)
                        // `transformEntry` 是 NavTransition 的**成员扩展函数**
                        // （`fun Modifier.transformEntry(scope)`），需要两个接收者：
                        // NavTransition + Modifier。所以用 `with(transition) { ... }`。
                        .then(with(transition) { Modifier.transformEntry(coveredScope) }),
                ) {
                    Image(
                        bitmap = snapshot.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                }
            }

            // ---- 当前页：WebView ----
            //
            // 顶部让出状态栏那一条（露出的就是根 Column 的 background(bg) = 纯色填充）。
            AndroidView(
                factory = { webView },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .padding(top = statusBarTopDp)
                    .then(with(transition) { Modifier.transformEntry(pageScope) }),
                update = { v ->
                    val url = PkHostOrchestrator.h5Url() ?: return@AndroidView
                    val target = url to viewModel.reloadToken
                    if (viewModel.webError == null && target != loadedTarget) {
                        loadedTarget = target
                        AppLogger.i("PkH5", "加载内置 pk-node H5：$url")
                        // 清掉本 host 的 cookie，让身份唯一真源 = URL 的 leoAccountId。
                        clearHostCookies()
                        v.loadUrl(url)
                    }
                },
                onRelease = { v -> releaseWebView(v) },
            )

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

// ============================================================================
// 转场：把 App 的 NavTransition 套到 H5 容器上
// ============================================================================

/**
 * H5 容器的导航状态机 —— 只产出**驱动信号**，不含任何转场公式。
 *
 * App 里 `relativeDepth` 由 `NavDisplay` 的返回栈驱动；H5 内部跳转是 pk-node
 * 在页面里 `location.href = …`，返回栈在 WebView 内部，拿不到。
 * 所以这里用两个信号合成：预测性返回手势 + 程序化进度。
 */
internal class H5NavState {
    /** 程序化进度：1 = 静止，0 = 刚开始进场。 */
    var progress by mutableFloatStateOf(1f)

    /** 由 composable 的动画回填（`animateFloatAsState` 的当前值）。 */
    var animatedProgress by mutableFloatStateOf(1f)

    /** 预测性返回手势进度；null = 无手势。 */
    var gestureProgress by mutableStateOf<Float?>(null)
        private set

    /** 手势纵向位置（跟手用）。 */
    var touchY by mutableFloatStateOf(0f)
        private set

    /** 是否从屏幕边缘发起（影响弹跳强度）。 */
    var fromEdge by mutableStateOf(false)
        private set

    /** 松手结算的相位；null = 不在结算。 */
    var settlePhase by mutableStateOf<NavSettlePhase?>(null)
        private set

    /** 松手结算的时钟（毫秒）。 */
    var settleElapsed by mutableFloatStateOf(0f)
        private set

    /** 是否在转场中（决定 role）。 */
    var running by mutableStateOf(false)
        private set

    /** 方向：true = 返回 / 退页。 */
    var backward by mutableStateOf(false)
        private set

    /** 被覆盖层 = 旧页快照（「再覆盖一层 H5 页面」）。 */
    var covered by mutableStateOf<Bitmap?>(null)
        private set

    private var lastBase: String? = null

    // ---- 程序化 ----

    /** 页面开始加载 = 进场开始（新页从右侧滑入）。同文档 hash 变化不播。 */
    fun onPageStarted(url: String?) {
        val u = url ?: return
        if (!u.contains("/pk-h5")) return
        val base = u.substringBefore('#')
        if (base == lastBase) return
        lastBase = base
        backward = false
        running = true
        progress = 0f
    }

    /** 页面加载完成 = 进场结束。 */
    fun onPageFinished() {
        progress = 1f
        running = false
        covered = null
    }

    /** 程序化返回（先铺动画，再 goBack）。 */
    fun beginBack() {
        backward = true
        running = true
        progress = 1f
    }

    // ---- 预测性返回手势 ----

    fun beginGesture() {
        backward = true
        running = true
        gestureProgress = 0f
        settlePhase = null
    }

    fun updateGesture(progress: Float, touchY: Float, fromEdge: Boolean) {
        gestureProgress = progress.coerceIn(0f, 1f)
        this.touchY = touchY
        this.fromEdge = fromEdge
    }

    /** 手势提交：进入 commit 结算（450ms 时钟做淡出 / 弹跳）。 */
    fun commitGesture() {
        settlePhase = NavSettlePhase.Commit
        settleElapsed = 0f
    }

    /** 手势取消：回弹。 */
    fun cancelGesture() {
        settlePhase = NavSettlePhase.Cancel
        settleElapsed = 0f
    }

    fun endGesture() {
        gestureProgress = null
        running = false
        settlePhase = null
        settleElapsed = 0f
    }

    /** 跳转前抓旧页快照（此刻 WebView 还画着旧页）。 */
    fun captureCovered(view: WebView) {
        covered = runCatching {
            if (view.width <= 0 || view.height <= 0) return@runCatching null
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            bmp
        }.getOrNull()
    }
}

/**
 * 把 [H5NavState] 适配成 miuix 的 [NavTransitionScope]。
 *
 * 同一份状态被构造**两次**：`covered = false` 给当前页（Incoming / Outgoing），
 * `covered = true` 给被覆盖层（Covered）。
 */
private class H5NavScope(
    private val st: H5NavState,
    private val covered: Boolean,
) : NavTransitionScope {
    override val relativeDepth: Float
        get() {
            val g = st.gestureProgress
            if (g != null) return -g
            return st.animatedProgress - 1f
        }

    override val role: NavRole
        get() = when {
            covered -> NavRole.Covered
            st.backward -> NavRole.Outgoing
            st.running -> NavRole.Incoming
            else -> NavRole.Top
        }

    override val change: NavChange
        get() = if (st.backward) NavChange.Pop else NavChange.Push

    override val gesture: NavGesture?
        get() = st.gestureProgress?.let {
            NavGesture(
                progress = it,
                swipeEdge = if (st.fromEdge) NavSwipeEdge.Left else NavSwipeEdge.None,
                touchY = st.touchY,
                initialTouchY = st.touchY,
            )
        }

    override val settle: NavSettle?
        get() {
            val phase = st.settlePhase ?: return null
            val elapsed = st.settleElapsed
            return object : NavSettle {
                override val phase: NavSettlePhase get() = phase
                override val releaseVelocity: Float get() = 0f
                override val elapsedMillis: Float get() = elapsed
            }
        }

    override val layoutSize: IntSize get() = size
    override val layoutDirection: LayoutDirection get() = LayoutDirection.Ltr
    override val density: Density get() = den

    private var size = IntSize.Zero
    private var den = Density(1f)

    fun update(size: IntSize, density: Density) {
        this.size = size
        this.den = density
    }
}

/**
 * 清空 `127.0.0.1` 下的全部 WebView cookie。
 *
 * PK 链路的身份**唯一真源**是 URL 上的 `leoAccountId`。H5 与代理都在
 * `127.0.0.1:8792`，任何写进这个 host 的 cookie 都会被 H5 请求带上；
 * 一旦来自「别的账号」（旧容器曾把 App 的小猿 cookie 灌进来），
 * 就会出现用户说的「cookie 传递错误」。
 *
 * ⚠️ 补充事实（本轮查证）：pk-node 的 `proxyApi()` **从不读 `req.headers.cookie`**
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

/** 「返回」：先退 H5 历史，退无可退才关容器回主页。 */
private fun goBackOrFinish(webView: WebView, onFinish: () -> Unit, navState: H5NavState?) {
    if (webView.canGoBack()) {
        // 先铺「返回方向」的转场，再退 —— 否则旧页恢复前会先白闪一下。
        // 刻意**不清焦点**：用户可能正在 `<input>` 里打字。
        runCatching { navState?.beginBack() }
        webView.goBack()
    } else {
        // 首页再返回 = 关容器。★ 必须先 clearFocus 再 onFinish：
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
 *  2. **清空回调与 JS 开关**：不清会让整棵 Activity 泄漏。
 *  3. **stopLoading 再 destroy**：否则网络线程回调已销毁的 WebView 会崩。
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
 * 内置 node 架构下绝大多数能力已被 pk-node 的 JS 桥接管，宿主只处理
 * 「页面自己冒出来」的 `close` / `back` / `finish`。
 */
private fun handleScheme(url: String, onFinish: () -> Unit): Boolean {
    if (!url.startsWith("leo://")) return false
    val host = Uri.parse(url).host ?: return false
    if (host == "close" || host == "back" || host == "finish") {
        onFinish()
        return true
    }
    // 其余自定义能力放行给 pk-node 的 JS 桥。
    return false
}