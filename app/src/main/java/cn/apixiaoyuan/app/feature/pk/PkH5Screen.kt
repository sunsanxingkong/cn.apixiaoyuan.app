package cn.apixiaoyuan.app.feature.pk

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.apixiaoyuan.app.core.design.icon.AppIcons
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import cn.apixiaoyuan.app.core.design.component.isDarkColor
import cn.apixiaoyuan.app.core.design.component.probeH5PageColor
import cn.apixiaoyuan.app.core.design.component.rememberH5PageColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.oldsimian.PkJsInjector
import cn.apixiaoyuan.app.core.pk.host.PkHostOrchestrator

/**
 * 口算 PK 的 H5 容器（★ 2026-10-03 换成**内置 node 版**）。
 *
 * # 架构变了：从「App 自己跑 H5」变成「App 只看一个本机页面」
 *
 * ## 旧架构（已废弃）
 *
 * H5 来自小猿 CDN，登录态靠 App 把 cookie 灌进 `CookieManager`，
 * 业务请求靠 `core/oldsimian/PkH5Proxy` 用 OkHttp 代发，
 * 原生桥靠 `addJavascriptInterface` 提供 —— 一套**与 pk-node 平行**的实现，
 * 每个坑都要各修一遍（417 / 登录态 / 串号 / 桥回调协议…）。
 *
 * ## 新架构
 *
 * ```
 *  ┌─ App 进程 ─────────────────────────────────────────────┐
 *  │  PkH5Screen（本文件）                                   │
 *  │    └─ WebView ──→ http://127.0.0.1:8792/pk-h5/pk.html  │
 *  │         ▲                        │                      │
 *  │         │ 只注入 Eruda（JS 控制台）│                      │
 *  │         │                        ▼                      │
 *  │  PkWebViewBridge            pk-node（内置 node 子进程）   │
 *  │   （只答 presetInfo）          ├ 服务端代理全部业务请求     │
 *  │                              ├ 自带 window 桥（JS 实现）  │
 *  │                              └ 账号 / 设备链 / 签名 全在它  │
 *  └────────────────────────────────────────────────────────┘
 * ```
 *
 * **原生侧只剩两件事**：
 *  1. 起服务（[PkHostOrchestrator]）并把 WebView 指向本机端口；
 *  2. 注入 JS 控制台（用户明确要求保留）。
 *
 * # 三个必须踩对的点
 *
 * ## ① **一个 `addJavascriptInterface` 都不能注册**
 *
 * pk-node 的 H5 **自带**整套 `window.WebView / CommonWebView / LeoWebView /
 * LeoSecureWebView`（见其 `H5_INJECT` 的 `HANDLERS`），而它的挂载是
 * `if (!window[name]) window[name] = bridge` —— **先到先得**。
 * 宿主若抢注同名对象，pk-node 那套更完整的实现（含 `dataEncrypt` /
 * `requestConfig`，宿主根本实现不了）会被**静默跳过**，H5 直接残废。
 *
 * 详见构造函数里那段注释（我初版写过一个「只让位部分方法」的宿主桥，
 * 查证 pk-node 的实现后**整段删除**了）。
 *
 * ## ② `loadUrl` 只在「原生下发的目标」变化时才发
 *
 * H5 是 SPA（hash 路由），内部导航会把 `WebView.url` 改成 `pk.html#/xxx`。
 * 若用 `view.url != h5Url` 判定，每次重组都会 `loadUrl` → 页面一直刷新。
 * 所以只认 `(目标 URL, reloadToken)` 这个「原生下发的目标」。
 *
 * ## ③ Cookie 同步：**不再需要**
 *
 * 旧容器要把 App 的 cookie 灌进 `CookieManager` 才能让 H5 登录；
 * 现在 H5 的所有业务请求都由 **pk-node 服务端**发出（用**它自己**的账号 jar），
 * WebView 只需要能打开 `127.0.0.1` 的页面。所以
 * `syncCookiesToWebView` / `PkH5Proxy` / `SessionStore.pkAccountId` 那一整套
 * 在本容器里**全部不再参与** —— 串号问题从架构上消失（身份由 URL 上的
 * `leoAccountId` 在打开那一刻定死，见 [PkHostOrchestrator.h5Url]）。
 *
 * ## JS 控制台（用户明确要求保留）
 *
 * 走 [PkJsInjector] 的 Eruda（`evaluateJavascript` 注入 `assets/js/eruda.js`
 * 再 `eruda.init()`），与上面被删掉的宿主桥是两条独立的路，不受影响。
 * 开关仍是 `OldSimianPrefs.h5DebugConsole`。
 *
 * @param viewModel PK 状态机（本容器只用它的进度/标题/重载令牌）
 * @param onFinish  H5 侧要关闭容器时的回调（如 `leo://close`），由调用方决定去哪
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PkH5Screen(
    viewModel: PkViewModel,
    onFinish: () -> Unit = {},
) {
    val context = LocalContext.current

    // 进页面就把内置服务拉起来（幂等：已起过直接返回）。
    // 放在这里而不是 App.onCreate：不逛 PK 的用户不该为 120MB 的 node 付启动时间。
    DisposableEffect(Unit) {
        PkHostOrchestrator.startAsync(context)
        onDispose { }
    }

    // ★ 2026-10-03：H5 页面底色（自适应填满状态栏那条带子）。
    //
    // App 开了 enableEdgeToEdge，状态栏是**浮在内容之上**的。本页的根 Column
    // 原先没有背景 → 状态栏那条带子露出窗口底色，与 H5 页面颜色不一致（用户报
    // 「顶部是空的、应该采取自适应页面颜色填充」）。
    //
    // 这里在 onPageFinished 里问页面「你的底色是什么」，拿到后铺满整页。
    // 详见 core/design/component/H5PageColor.kt。
    val pageColor = rememberH5PageColor()

    // WebView 实例在 composition 期间创建；销毁由 AndroidView 的 onRelease 负责。
    val webView = remember {
        WebView(context).apply {
            // ---- 焦点策略（★ 2026-10-03 修订：改为「可聚焦 + 移出前先 clearFocus」）----
            //
            // ## 历史：为什么一度设成不可聚焦
            //
            // 崩溃栈（data_app_crash@1790394023891）：Choreographer.doFrame →
            // Compose applyChanges → ViewGroup.removeViewInLayout →
            // View.rootViewRequestFocus → AndroidComposeView.requestFocus →
            // "pending composition has not been applied"。
            //
            // 机理：移除的 View 正是 `mFocused` 时，ViewGroup 会向上重新找焦点持有者，
            // 而此刻 Compose 正在 apply 阶段 → 重入合成 → 崩。
            // 当时为了「从源头断掉」把 WebView 设成 `isFocusable = false`。
            //
            // ## 为什么这个办法行不通（用户反馈：「pk h5 输入文字时应该能调用输入法」）
            //
            // Chromium **不用子 View 承载输入框** —— 它是直接在 **WebView 自身**上
            // 建立 `InputConnection` 并请求 IME。所以 `isFocusable = false` 的代价是
            // **整个页面永远弹不出输入法**（页面上所有 `<input>` 都敲不了字）。
            //
            // ## 现在的做法：允许聚焦，但在「移出视图树之前」主动清掉
            //
            // 崩溃的触发条件是「**移除时还持着焦点**」。所以只要保证下面三处
            // 都在移除前 `clearFocus()`，就可以安全地让 WebView 可聚焦：
            //   1. [releaseWebView]（AndroidView.onRelease，移除后回调，内部第一步就清）
            //   2. `DisposableEffect.onDispose`（与合成同帧，早于 onRelease）
            //   3. **导航离开前**（返回键 / 标题栏返回 / `onFinish`）—— 见 [goBackOrFinish]
            //
            // `descendantFocusability = FOCUS_BEFORE_DESCENDANTS`：WebView 自己拿焦点，
            // 同时保留其内部子 View（如下拉框）能接管的可能。
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // UA 由 pk-node 在页面里自己伪装（它 patch `navigator.userAgent` 加
                // `YuanSouTiKouSuan/3.141.1`）。宿主这边**不再**追加 ——
                // 追加会让 UA 里出现两段版本号，H5 的严格正则可能取到错误的那个。
                AppLogger.i("PkH5", "WebView UA = $userAgentString")
            }

            // ★★ 宿主**刻意不注册任何 `addJavascriptInterface`**。
            //
            // ## 为什么（2026-10-03 决定，验证过 pk-node 的实现后推翻了自己的初版设计）
            //
            // pk-node 的 H5 **自带完整的 `window` 桥**（见其 `H5_INJECT` 的
            // `HANDLERS`）：`getUserInfo` / `getWebViewInfo` / `getDeviceInfo` /
            // `requestConfig` / `dataEncrypt` / `dataDecrypt` / `openWebView` /
            // `closeWebView` … 全部在**页面里用 JS 实现**，且它的挂载方式是
            // `if (!window[name]) window[name] = bridge` —— **先到先得**。
            //
            // 也就是说：宿主只要用 `addJavascriptInterface` 抢注 `CommonWebView`
            // 之类的名字，pk-node 那套更完整的实现就会被**静默跳过**，
            // 而宿主的 Java 对象不可能实现得了 `dataEncrypt`（要拿 node 的密钥流）、
            // `requestConfig`（要拿账号 jar）—— 结果是 H5 直接残废。
            //
            // 初版我写过一个「只对少量方法名回 true、其余让位」的桥
            // （靠 `hasNativeMethod` 返回 false 让调用落到 JS 层）。
            // 但查证后发现：
            //   ① H5 实际会调的方法（`getWebViewInfo` / `getDeviceInfo` /
            //      `getBasicInfo` / `getUserInfo`）pk-node **全都实现了**；
            //   ② 它**从不调** `presetInfo` —— 我原本打算处理的正是这一个。
            // 于是那个桥 100% 只会「遮蔽好的、提供没用的」，且还押注在一个
            // 未经验证的 WebView 行为上（`hasNativeMethod=false` 是否真的
            // 让同一对象的 JS 属性在页面里可见）。**风险大于收益，整段删掉。**
            //
            // ## 那 JS 控制台怎么办（用户要求「那个 js 控制台还是要的」）
            //
            // 控制台走 `onPageFinished` 里的 `PkJsInjector`（Eruda），
            // 那是 **`evaluateJavascript` 注入**，与 `addJavascriptInterface`
            // 是两条完全独立的路 —— 不需要宿主桥。见下方 `onPageFinished`。

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    viewModel.setProgress(5)
                    viewModel.webError = null
                    // 重置注入标记：同一次加载 onPageFinished 可能回调多次，
                    // 不重置会导致脚本（尤其带 setInterval 的）被叠加注入多轮。
                    view?.let { PkJsInjector.markPageStarted(it) }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    viewModel.setProgress(100)
                    view?.title?.takeIf { it.isNotBlank() }?.let { viewModel.webTitle = it }
                    AppLogger.i("PkH5", "onPageFinished: $url")
                    // 取证：页面可见文本（一眼判断卡在哪一步）
                    view?.evaluateJavascript(
                        "(function(){try{return (document.body&&document.body.innerText||'')" +
                            ".replace(/\\s+/g,' ').slice(0,300)}catch(e){return 'ERR:'+e}})()",
                    ) { v -> AppLogger.i("PkH5", "页面文本: $v") }
                    // 取证：桥归谁了。pk-node 的桥挂上后 `window.LeoWebView` 存在，
                    // 且它下面应该有 `callNative`（宿主**没有**提供这个）。
                    view?.evaluateJavascript(
                        "(function(){try{var ks=['WebView','CommonWebView','LeoWebView','LeoSecureWebView'];" +
                            "return ks.map(function(k){return k+'='+(typeof window[k])}).join(',')" +
                            "+';LeoCallNative='+(window.LeoWebView&&typeof window.LeoWebView.callNative)" +
                            "+';PK_ID='+(window.__PK_LEO_ID||'-');" +
                            "}catch(e){return 'ERR:'+e}})()",
                    ) { v -> AppLogger.i("PkH5", "桥对象: $v") }
                    // 「老挂戏老叟」的注入（去动画 / 自动下一局 / 自动画笔 / **JS 控制台**）。
                    // 前三个依赖 Vue 树（与宿主实现无关），控制台是 Eruda。
                    view?.let { PkJsInjector.injectIfEnabled(it) }
                    // 每次都探：SPA 内部导航会换「页面」，底色未必相同。
                    view?.let { probeH5PageColor(it, pageColor, tag = "PkH5") }
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
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return handleScheme(url, onFinish)
                }

                @Deprecated("Deprecated in API 24, but kept for older WebView")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return url?.let { handleScheme(it, onFinish) } ?: false
                }

                // ★★ 注意：本容器**不再实现 `shouldInterceptRequest`**。
                //
                // 旧容器要拦自有域请求、改用 OkHttp 代发（补 sign / 风控头 / cookie）。
                // 现在这些请求根本不在 WebView 里发出 —— pk-node 的 H5 会把它们
                // XHR 到同源的 `/api/pk/h5/api`，由 **node 服务端**转发
                // （那是它能拿到账号 jar 与设备链的地方）。
                // 宿主再插一手只会画蛇添足（且拿不到 pk-node 的 cookie）。
            }

            // H5 的 console 落盘：这是排障的唯一可靠证据来源
            // （「H5 到底走到哪一步」只能看它自己的推理）。
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

    // 原生**主动驱动加载**的目标 URL。
    //
    // 只认这个值，绝不用 `WebView.url` 做判断 —— H5 是 SPA（hash 路由），
    // 内部导航会把 `WebView.url` 改成 `pk.html#/xxx`，此时
    // `view.url != h5Url` **恒为真** → update 每次重组都 loadUrl
    // → 表现就是「PK 页面一直刷新」。
    var loadedTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            // 不能在这里 destroy（见 AndroidView.onRelease 的注释），
            // 只做非破坏性清理。
            //
            // ★ 2026-10-03：`clearFocus()` 这一步现在**是防崩溃的关键**（不再是锦上添花）。
            //   因为焦点策略改成了「WebView 可聚焦」（否则弹不出输入法，
            //   见构造处那段注释），而「移除时还持着焦点」正是那条
            //   Compose 重入合成崩溃（ViewGroup.removeViewInLayout →
            //   rootViewRequestFocus → AndroidComposeView.requestFocus）的触发条件。
            //   本回调与合成同帧、**早于** AndroidView.onRelease，
            //   所以它是「View 被摘掉之前的最后一次清焦点机会」。
            runCatching { webView.clearFocus() }
            runCatching { webView.stopLoading() }
        }
    }

    // ---- 系统返回键：先退 H5 历史，退无可退才关容器 ----
    BackHandler(enabled = true) {
        goBackOrFinish(webView, onFinish)
    }

    val hostState = PkHostOrchestrator.state
    val targetUrl = PkHostOrchestrator.h5Url()

    // 底色：探测到就紧跟 H5；没探到则退回主题色（不猜）。
    val bg = pageColor.value ?: MiuixTheme.colorScheme.surfaceContainer

    // 状态栏图标：底色深 → 用浅色图标（否则时间/电量看不见）。
    // 必须在探测到颜色后跟着变，所以放在这里（而不是 remember 一次）。
    val view = LocalView.current
    LaunchedEffect(bg) {
        runCatching {
            val act = view.context as? android.app.Activity ?: return@runCatching
            WindowCompat.getInsetsController(act.window, view)
                .isAppearanceLightStatusBars = !isDarkColor(bg)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // ★ 铺满整页：状态栏那条带子（edge-to-edge 下露出的是窗口底色）
            //   会与 H5 页面完全同色。
            .background(bg),
    ) {

        // 顶部进度条：只在**首次加载**显示（SPA 内部导航不显示，否则一闪一闪）
        if (viewModel.webProgress in 1..99 && loadedTarget == null) {
            LinearProgressIndicator(
                progress = { viewModel.webProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 标题栏（返回按钮 + 标题 + 加载圈）
        //
        // ⚠️ 它必须在内容区**之外**：内置服务启动中 / 出错时下面会盖一层
        // 提示卡，若提示卡连标题栏一起盖住，用户就连「返回」都点不到了
        // （实测踩过：卡在启动失败时只能杀进程）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { goBackOrFinish(webView, onFinish) }) {
                Icon(imageVector = AppIcons.Back, contentDescription = "返回")
            }
            Text(
                text = viewModel.webTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (viewModel.loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        // ---- 内容区（WebView + 各种覆盖层）----
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    // 注意：这里**没有** cookie 同步 —— 见本文件 KDoc 的「③」。
                    // 也没有无条件 loadUrl（那会造成 SPA 反复重载）。
                    val url = PkHostOrchestrator.h5Url() ?: return@AndroidView
                    val target = url to viewModel.reloadToken
                    if (viewModel.webError == null && target != loadedTarget) {
                        loadedTarget = target
                        AppLogger.i("PkH5", "加载内置 pk-node H5：$url")
                        view.loadUrl(url)
                    }
                },
                // 销毁交给 onRelease：它在 View 被移出视图树**之后**才回调。
                // 放在 DisposableEffect.onDispose 里会与 requestFocus 重入竞争崩溃。
                onRelease = { view -> releaseWebView(view) },
            )

            // ---- 就绪前的覆盖层 / 失败提示 ----
            //
            // 内置 node 首次启动要解压工作区 + 起进程 + 等探活，真机约 1~3 秒。
            // 期间**没有 URL 可加载**（[PkHostOrchestrator.h5Url] 返回 null），
            // 必须给用户一个明确的「正在启动内置服务」，而不是白屏。
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
                        // 重置编排状态后重起 —— 否则 started 标记会让 startAsync 直接返回。
                        PkHostOrchestrator.stop()
                        PkHostOrchestrator.startAsync(context)
                    },
                )

                is PkHostOrchestrator.State.Ready -> {
                    if (targetUrl == null) {
                        // 就绪了却拿不到 URL —— 极少见（服务活着但没报 h5Base），兜个底。
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
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
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
                                fontFamily = FontFamily.Monospace,
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

/** 「内置服务启动中 / 失败」的提示卡。 */
@Composable
private fun HostNotice(
    title: String,
    detail: String,
    busy: Boolean = false,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
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
 * 标题栏返回按钮与系统返回键（[BackHandler]）**共用**这一个实现，
 * 保证两条入口行为一致。
 *
 * H5 的 hash 路由导航（SPA 的 `#/xxx`）会被 Chromium 记进 navigation
 * controller，所以 `canGoBack()` 同样覆盖 SPA 内部的前进后退。
 */
private fun goBackOrFinish(webView: WebView, onFinish: () -> Unit) {
    if (webView.canGoBack()) {
        // H5 内部还有历史：退一级，**不退容器**。
        //
        // ⚠️ 这里**刻意不清焦点** —— 用户可能正在某个 `<input>` 里打字，
        //    退一级不等于要收起输入法（浏览器也是这个行为）。
        //    清焦点只在「真的要离开容器」时做（见下面的 else）。
        webView.goBack()
    } else {
        // H5 首页再返回 = 关容器。★ 必须**先 clearFocus 再 onFinish**：
        //   onFinish 会把整个 PkH5Screen 从返回栈弹出 → AndroidView 被移除；
        //   而「移除时 WebView 还持着焦点」正是那条 Compose 重入合成崩溃的触发条件
        //   （ViewGroup.removeViewInLayout → rootViewRequestFocus →
        //    AndroidComposeView.requestFocus → "pending composition has not been applied"）。
        //   这是「移出视图树之前的最后一次清焦点机会」，比 onDispose / onRelease 都早。
        webView.clearFocus()
        onFinish()
    }
}

/**
 * 安全销毁 WebView（由 AndroidView 的 onRelease 调用，此时 View 已移出视图树）。
 *
 * 顺序有讲究（真机崩溃倒逼出的三条）：
 *  1. **先 clearFocus**：若 WebView 或其子 View 仍持焦点，
 *     `removeViewInternal` 会走 `rootViewRequestFocus()` → 重入合成崩溃。
 *  2. **清空回调与 JS 开关**：`webViewClient` 持有 viewModel 与 onFinish 引用，
 *     不清会让整棵 Activity 泄漏；关 JS 阻断页面里还在跑的定时器继续回调原生。
 *  3. **stopLoading 再 destroy**：否则网络线程回调已销毁的 WebView
 *     会触发 chromium 的 native 层崩溃。
 *
 * 全程 `runCatching`：销毁阶段的异常不该升级成用户可见的崩溃。
 */
private fun releaseWebView(view: WebView) {
    runCatching {
        view.clearFocus()
        view.stopLoading()
        view.webViewClient = WebViewClient()
        view.settings.javaScriptEnabled = false
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.removeAllViews()
        view.destroy()
    }
}

/**
 * 拦截 `leo://` scheme。
 *
 * 原版 H5 通过这个 scheme 调原生能力。在**内置 node 架构**下，
 * 绝大多数已被 pk-node 的 JS 桥接管（它自己实现 `openWebView` / `closeWebView`），
 * 宿主只需要处理「页面自己冒出来的 scheme」这一层兜底。
 *
 * 当前处理 `close` / `back` / `finish`（都回退）。其余放行给 WebView。
 *
 * @return true 表示已消费该 URL
 */
private fun handleScheme(url: String, onFinish: () -> Unit): Boolean {
    if (!url.startsWith("leo://")) return false

    val uri = Uri.parse(url)
    return when (uri.host) {
        "close", "back", "finish" -> {
            // 与 goBackOrFinish 同理：主动关容器时 WebView 很可能正持着焦点
            // （用户刚点过页面），必须先清 —— 否则移除时会触发
            // Compose 重入合成崩溃。
            // 注意：这里拿不到 webView 实例（handleScheme 只收 url），
            // 所以清焦点由调用方在 onFinish 之后由 onDispose/onRelease 兜底；
            // 而 BackHandler 那条路已由 goBackOrFinish 提前清掉（覆盖最常见的场景）。
            onFinish()
            true
        }
        // openWebView / 其余自定义能力：交给 pk-node 的 JS 桥，宿主不拦
        // （拦了反而会让 H5 的多 WebView 跳转失效）。
        else -> false
    }
}