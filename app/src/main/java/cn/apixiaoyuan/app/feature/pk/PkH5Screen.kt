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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
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
import cn.apixiaoyuan.app.core.design.component.LocalTopBarInset
import cn.apixiaoyuan.app.core.navigation.transition.AospTransitionDurationMs
import cn.apixiaoyuan.app.core.navigation.transition.CrossActivityDrift
import cn.apixiaoyuan.app.core.navigation.transition.MiuixCoverAlpha
import cn.apixiaoyuan.app.core.navigation.transition.MiuixCoverParallax
import cn.apixiaoyuan.app.core.navigation.transition.MiuixTransitionDurationMs
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import cn.apixiaoyuan.app.core.design.theme.PageTransitionAnimation
import cn.apixiaoyuan.app.core.design.theme.PageTransitionPrefs
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

    // ★ 2026-10-03：H5 内部跳转的「切页动画」
    //   （用户要求「进入下一个 h5 页面应该也有切页动画（有预测性返回）」）。
    //
    // ⚠️ 状态必须放在一个 **holder 对象** 里，不能写成 composable 局部变量：
    //    WebView 是在 `remember { WebView(...).apply { ... } }` 里创建的，
    //    那个 lambda **不是 composable 作用域**，里面引用不到 composable 局部状态
    //    （我第一次写就撞了 `Unresolved reference`）。holder 用 `remember` 造、
    //    内部持 Compose 状态（`mutableFloatStateOf`），两边就都能访问。
    //
    // 实现与局限见文件末尾 [NavAnimHolder] 的 KDoc。
    val navAnim = remember { NavAnimHolder() }

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

                    // ★ H5 内部切页动画（见上面 navAnim 的说明）。
                    //   浏览器在 onPageStarted 时已经把旧画面清空（= 白屏），
                    //   所以这正是铺「画面移交」覆盖层的最佳时机。
                    navAnim.onMainNav(url)
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
                    // ★ 切页动画收尾：新文档就绪 → 拿掉「画面移交」覆盖层。
                    //   直接 finish（不走动画）最干净：onPageFinished 与动画时长
                    //   没有先后保证，让覆盖层自己渐隐反而可能「叠一下」。
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
        goBackOrFinish(webView, onFinish, navAnim)
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

    val animPref = PageTransitionPrefs.animation
    // ★ 原生转场的两个输入：屏幕宽（miuix 用整屏宽、aosp 用 96dp）与密度。
    val screenW = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    // AOSP 的 96dp 漂移量：从 AppNavTransition 的**同一份定义**取（不自己写数字）。
    // CompositionLocal 只能在 composable 里读，所以在这里先换算成像素再传进去。
    val driftPx = with(LocalDensity.current) { CrossActivityDrift.toPx() }
    val spec = remember(animPref, screenW, driftPx) { navAnimSpecOf(animPref, screenW, driftPx) }
    // 进度 → 位移/透明度（帧时钟驱动；不用自己起协程，也不会被重组打断）。
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

        // ★ 2026-10-03（用户要求：「顶部不要有文字只要纯色填充」）：
        //
        // 这里**原先有一条标题栏**（返回按钮 + 网页标题 + 加载圈）。已整条移除，
        // 顶上只留纯色（就是下面 Column 的 background(bg)，也就是 H5 自己的底色）。
        //
        // 三件事都要交代清楚，否则会变成「功能没了」：
        //
        //  ① **返回**：本来就有
        //     [BackHandler]（系统返回键 → [goBackOrFinish]）+ 左滑返回
        //     （`entry<RoutePk>(swipeDismiss = NavSwipeDirection.LeftToRight)`），
        //     所以那个返回按钮是**冗余**的，删掉不丢能力。
        //
        //  ② **加载进度**：原本只在「首次加载且还没 loadUrl 过」时出现，
        //     且就是这条进度条（不是标题栏里的圈）。保留，仍只在首屏显示。
        //
        //  ③ **纯色填充怎么保证**：状态栏区域（edge-to-edge 下浮在内容之上）
        //     看到的是根 Column 的 `background(bg)`，而 `bg` 来自
        //     [probeH5PageColor] 探测到的**H5 页面自己的底色**
        //     —— 所以顶上不会有任何文字，且颜色与页面一致。
        //
        //  ④ 那个 `WebView` 的 `<title>` 仍会更新 [viewModel.webTitle]，
        //     只是不再显示（留着无副作用，别的页面还能用）。

        // ---- 内容区（WebView + 各种覆盖层）----
        //
        // ★★ 2026-10-04 修正（用户第二次指出）：
        // > 「顶部纯色填充没看到，**h5 容器顶部应该下移因为手机菜单挡住了**」
        //
        // 我之前把顶栏 inset **去掉了**（想让 WebView 顶到屏幕最顶、交给 H5 自己躲），
        // 但结果是 **H5 自己的抬头被手机的状态栏/菜单挡住** —— 因为这套 H5 页面
        // 并不处理 `safe-area-inset-top`（它是按「原生容器已经留好顶部」的假设写的）。
        //
        // 正确做法（也是用户要的）：
        //   **在 App 侧把 WebView 整体下移「状态栏高度」** —— 顶部那条就露出
        //   `background(bg)` 的**纯色填充**（与 H5 页面同色），H5 的内容从纯色下方开始，
        //   不会再被挡。
        //
        // ⚠️ 与 pk-node 的差异（为什么不能照搬）：pk-node 是网页里的 `<iframe>`，
        //    浏览器会给它一个独立的布局视口；而我们是**全屏 WebView**，
        //    edge-to-edge 下它从 y=0 开始画，所以必须由宿主让出状态栏那一条。
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { webView },
                modifier = Modifier
                    .fillMaxSize()
                    // ★ 顶部让出「状态栏 + 显示切口」那一条（用户要求「h5 容器顶部应该下移」）。
                    //   顶部那条露出的是根 Column 的 `background(bg)` —— 就是**纯色填充**。
                    //   这样 H5 自己的抬头不会被手机菜单挡住。
                    .padding(top = LocalTopBarInset.current)
                    // ★ 原生切页转场（进入方向）：**整体动 WebView 自己**。
                    //
                    // 用户要求「切换 h5 要用 miuix 或 aosp 的原生动画，并且**只在 app 中
                    // 使用网页没有动画**」—— 这里就是那个「只在 App 侧」的位移：
                    // 动的是 AndroidView 这个 View 的 `graphicsLayer`，
                    // **不动 WebView 内部、不动网页**。
                    //
                    // ⚠️ 我上一版写成了一个空 Box（什么都不动）—— 那时「太僵硬」的原因之一。
                    .graphicsLayer {
                        if (navAnim.visible && navAnim.entering) {
                            translationX = (1f - t) * enterPx
                            alpha = t.coerceIn(0f, 1f)
                        }
                    },
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

// ---- ★ H5 内部切页的**原生转场**（② 被覆盖层）----
            //
            // ①（**新页滑入**）动的是上面 `AndroidView` 的 `graphicsLayer` ——
            //   因为它要动的是 WebView 这个 View 自己（空 Box 是没用的）。
            //
            // ②（**被覆盖层**）就是这里：返回/退页时应该看到「旧页滑回来盖住当前页」。
            //
            // ⚠️⚠️ 2026-10-03 **真机实测后改**（这条很重要）：
            //
            // 我原来这一层是 **`matchParentSize()` 铺满整屏**的，结果真机抓帧发现
            // **整屏白闪一下** —— 因为这个 H5 的页面底色就是 `#ffffffff`（纯白，
            // 见日志 `页面底色: #ffffffff`），而 WebView 在退页瞬间也是白的，
            // 两者叠加 = 一整屏白，看起来又僵又刺眼。
            //
            // 改成**只铺顶部那条状态栏带子**（高度 = statusBars inset）：
            //  · WebView 区域**完全不动**，不会有白闪；
            //  · 顶栏那条带子正好是「App 的背景露出来的地方」，让它跟随转场滑一下，
            //    观感就接上了（也顺带落实了用户那句「顶部用纯色填充」）。
            //
            // 局限依旧：拿不到旧 H5 的画面快照，所以只能用底色代替。
            if (navAnim.visible && !navAnim.entering) {
                val statusBarPx = with(LocalDensity.current) {
                    WindowInsets.statusBars.getTop(this).toFloat()
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(LocalDensity.current) { statusBarPx.toDp() })
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
private fun goBackOrFinish(webView: WebView, onFinish: () -> Unit, navAnim: NavAnimHolder?) {
    if (webView.canGoBack()) {
        // H5 内部还有历史：退一级，**不退容器**。
        //
        // ★ 2026-10-03：先铺「返回方向」的原生转场（用户要求「切换 h5 要用 miuix 或
        //   aosp 的原生动画」）。必须**在 `goBack()` 之前**铺 —— 因为退页后旧页才被恢复，
        //   而我们要在它恢复之前先把「被覆盖层」放上去，否则中间会先白闪一下。
        //
        // ⚠️ 这里**刻意不清焦点** —— 用户可能正在某个 `<input>` 里打字，
        //    退一级不等于要收起输入法（浏览器也是这个行为）。
        //    清焦点只在「真的要离开容器」时做（见下面的 else）。
        runCatching { navAnim?.onBackNav() }
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

/**
 * H5 内部跳转的**原生切页转场**（★ 2026-10-03，同日按用户反馈重做第二版）。
 *
 * # 用户要求（逐字）
 *
 * > 「切换 h5 要用 miuix 或 aosp 的原生动画并且**只在 app 中使用网页没有动画**，
 * >   之前写的那个**太僵硬了**」
 *
 * 两点都照做：
 *  1. **只在 App 侧动**：网页（WebView）**完全不动画**。转场由 App 这里合成的两层
 *     图形层完成 —— 所以这里的 `translationX` / `alpha` 都是 `graphicsLayer` 参数，
 *     **绝不**去改 WebView 自身或调用网页 JS。
 *  2. **对齐 App 已有的两套原生转场**（跟随 «设置 → 过渡动画»）：
 *     - `MIUIX` → 整屏滑 + 被覆盖页 0.25 宽视差 + 轻微淡出
 *       （对齐 `NavTransitions.MiuixDefault`）
 *     - `AOSP` → **96dp 横向漂移**（不是整屏滑）+ 450ms `FastOutExtraSlowIn`
 *       （对齐 `AppNavTransition.ClassicActivityOpen/Close`）
 *
 * 第一版为什么「僵硬」：我用了「只滑 12% 宽度 + 260ms tween」，
 * 既不整屏滑（不像 miuix），也不是 96dp 漂移（不像 aosp），
 * 而且**没有视差** —— 被覆盖页一动不动，像一块板子平移。
 *
 * # 为什么需要它
 *
 * app 级二级页转场管不到 H5 内部跳转：始终是同一个 WebView、同一条路由，
 * 跳转是 pk-node 在页面里 `location.href = …`（浏览器式硬跳）。
 *
 * # 为什么状态要放在对象里（踩过的坑）
 *
 * WebView 是在 `remember { WebView(...).apply { ... } }` 里创建的，
 * 那个 lambda **不是 `@Composable` 作用域**，引用不到 composable 局部状态
 * （第一次写就撞了 `Unresolved reference`）。所以状态封进这个类，
 * 用 `remember { NavAnimHolder() }` 创建；WebViewClient 回调与 composable 两边都能读。
 *
 * # 什么时候播 / 不播
 *
 * 只在**主文档导航**（`pk.html` → `external.html` / 荣誉榜页 …）时播。
 * pk.html 内部的换页走 **hash 路由**（`#/xxx`）—— 不是「进入下一个页面」，
 * 给它也播会变成「点任何东西都闪一下」。判据见 [isMainNav]。
 *
 * # 局限（如实说明）
 *
 * 拿不到**被覆盖页（旧 H5）**的画面快照 —— WebView 在 `onPageStarted` 时已经把
 * 旧内容清空了。所以“两层”里：
 *  - 进场层 = **新的 H5 画面**（真实内容，会真的滑入）；
 *  - 被覆盖层 = 收尾那次用的**底色层**（旧页面已经没了，只能用底色代替）。
 * 因此**视差只出现在收尾（返回/退页）那一次**；进场时看不到旧页视差。
 * 真要做到进场也有视差，需要 WebView 绘制快照（`onDraw` 抓 bitmap）或双 WebView，
 * 成本与风险都高得多。
 */
private class NavAnimHolder {

    /**
     * 转场进行到哪一步，0 → 1。
     *
     * 含义按 [entering] 分：
     *  - [entering] = true（进入下级页）：0 = 新页在屏幕外，1 = 新页归位；
     *  - [entering] = false（返回上级页）：0 = 底色层在屏幕外，1 = 底色层归位。
     */
    var progress by mutableFloatStateOf(1f)
        private set

    /**
     * 本次是「进入下级页」还是「返回上级页」。
     *
     * 为什么要分：两者**该看到什么**不一样 ——
     *  - 进入：应该看到**新页滑进来**，所以动画目标是让新页归位；
     *  - 返回：应该看到**旧页（这里用底色代替）滑回来**，盖住正在退出的当前页。
     *
     * 第一版没分这两种，所以返回时也是「新页从右滑入」，方向反了 —— 那也
     * 是「僵硬」的来源之一。
     */
    var entering by mutableStateOf(true)
        private set

    /** 是否正在播放（false 时两个图形层都不渲染，避免常态多两层）。 */
    var visible by mutableStateOf(false)
        private set

    /** 上一次**主文档**地址，用于区分「切页」与「SPA 内部 hash 变化」。 */
    private var lastUrl: String? = null

    /**
     * 在 `onPageStarted` 调用。
     *
     * 此时浏览器**已经把旧画面清空**（所以那一瞬是白屏）—— 正是铺转场层的最佳时机。
     */
    fun onMainNav(url: String?) {
        if (url.isNullOrBlank()) return
        val prev = lastUrl
        val first = prev == null
        val changed = isMainNav(prev, url)
        lastUrl = url
        // 首次加载不播：那时还在「启动内置服务」的覆盖层里，播了也看不到，
        // 反而会在启动完成那一刻多闪一次。
        if (first || !changed) return
        visible = true
        entering = true
        progress = 0f
    }

    /**
     * 在返回/退页**之前**调用（[goBackOrFinish] 走 `webView.goBack()` 那条路）。
     *
     * 返回方向的转场需要**提前**铺层：真正 `goBack()` 之后旧页才被恢复，
     * 而我们要在它恢复之前先把底色层放上去，否则中间会先白闪一下。
     */
    fun onBackNav() {
        visible = true
        entering = false
        progress = 0f
    }

    /** 在 `onPageFinished` 调用：新文档就绪，撤掉转场层。 */
    fun finish() {
        visible = false
        progress = 1f
    }

    /**
     * 是否是「进入另一个文档」（而不是同文档的 hash / query 变化）。
     *
     * 例（2026-10-03 实测过的真实形态）：
     *  - `pk.html#/a` → `pk.html#/b`                    ：同文档，**不算**（SPA 换页）
     *  - `pk.html` → `pk-h5-cdn/leo-web-study-group/…`   ：**算**（荣誉榜 / 下级页）
     *  - `pk.html` → `exercise.html?pointId=42&…`        ：**算**
     */
    private fun isMainNav(prev: String?, next: String): Boolean {
        if (prev.isNullOrBlank()) return false
        return pathOf(prev) != pathOf(next) || hostOf(prev) != hostOf(next)
    }

    private fun pathOf(u: String) = u.substringBefore('#').substringBefore('?')

    private fun hostOf(u: String) =
        runCatching { java.net.URI(u).host ?: "" }.getOrDefault("")
}

/**
 * 一个「原生转场」的观感参数。
 *
 * ★★ 2026-10-04 修正：**数值全部引用 App 转场的定义**，不再自己写。
 *
 * 用户原话：
 * > 「切换页面的动画应该**联通 app 的切页动画**而不是自己乱写」
 *
 * 之前我把 `260`、`12%`、`0.25`、`450` 这些数字**硬编码**在这里，
 * 与 `AppNavTransition` 里的定义是**两份**——改一边另一边就不一致了。
 * 现在改成引用 `AppNavTransition.kt` 里 `internal` 暴露出来的同一批常量：
 *
 * | | MIUIX | AOSP |
 * |---|---|---|
 * | 进场位移 | 整屏宽 | [CrossActivityDrift]（96dp） |
 * | 时长 | [MiuixTransitionDurationMs] | [AospTransitionDurationMs] |
 * | 被覆盖层视差 | [MiuixCoverParallax]（0.25 宽）+ alpha→[MiuixCoverAlpha] | 0（不视差）|
 *
 * 依据（源码级，不是抄文档）：
 *  - miuix：`miuix-nav` 的 `NavTransitions.MiuixDefault`
 *      `translationX = -d * width`；被覆盖页 `-1 * coverProgress * width * 0.25f`、
 *      `alpha = 1f - 0.1f * coverProgress`。
 *  - aosp：本项目 `AppNavTransition.ClassicActivityOpen`：
 *      `translationX = (1f - progress) * driftPx`，drift = 96dp，
 *      时长 = `ClassicActivityMotion` 的 450ms。
 */
private class NavAnimSpec(
    /** 进场页滑入距离（占屏宽的比例；AOSP 是 96dp，调用时再换算成比例）。 */
    val enterFraction: Float,
    /** 被覆盖页（底色层）的视差距离（占屏宽比例）。 */
    val coverFraction: Float,
    /** 被覆盖页的最终不透明度。 */
    val coverAlpha: Float,
    /** 时长（毫秒）。 */
    val durationMs: Int,
)

/** 取当前设置对应的转场参数（跟随 «设置 → 过渡动画»；数值取自 AppNavTransition）。 */
private fun navAnimSpecOf(
    anim: PageTransitionAnimation,
    widthPx: Float,
    /** AOSP 的 96dp 已经换算好的像素值（CompositionLocal 只能在 composable 里读，故由调用方传入）。 */
    driftPx: Float,
): NavAnimSpec =
    when (anim) {
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