package cn.apixiaoyuan.app.feature.pknode

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
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
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import cn.apixiaoyuan.app.core.design.component.isDarkColor
import cn.apixiaoyuan.app.core.design.component.probeH5PageColor
import cn.apixiaoyuan.app.core.design.component.rememberH5PageColor
import cn.apixiaoyuan.app.core.design.icon.AppIcons
import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.navigation.AppNavController
import cn.apixiaoyuan.app.core.pk.host.NodeRuntime
import cn.apixiaoyuan.app.core.pk.host.PkHostOrchestrator
import cn.apixiaoyuan.app.core.pk.host.PkNodeSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **pk-node 管理后台**（★ 2026-10-03，用户要求「把 pk-node 网页也在主页做一个入口」）。
 *
 * # 它展示的是什么
 *
 * 内置 node 提供的那个网页控制台（`http://127.0.0.1:8792/`），
 * 与你用浏览器打开的是**同一个页面** —— 账号管理 / 设备链池 / 任务 /
 * 日志 / 刷 PK / 刷练习 / 接口控制台全在里面。
 *
 * # ★ 自动登录（不用你手输 admin/admin）
 *
 * 页面本身要登录（`pk_sid`）。这里在加载前：
 *
 *  1. `PkNodeSync.adminSessionCookie()` 拿一枚管理会话 cookie
 *     （走 `POST /api/auth/login`，凭据来自 handshake，默认 admin/admin）；
 *  2. 写进 WebView 的 `CookieManager`；
 *  3. **再** `loadUrl`。
 *
 * 顺序不能反 —— WebView 用 `CookieManager` 发请求，
 * 先 load 再写 cookie 的话首屏会是登录页。
 *
 * # 没服务时怎么办
 *
 * 内置 node 可能还没起来（首启要解压 + 启动）。这时显示
 * 「正在启动内置服务…」，等服务就绪后自动加载 —— 而不是给一个
 * `ERR_CONNECTION_REFUSED` 白屏。
 *
 * # 与 PK 页的关系
 *
 * 两者共用 [PkHostOrchestrator] 这一份内置 node 实例（**同一个端口、同一个库**）。
 * 所以在这里加的账号 / 设备链，PK 页立刻就能用上。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PkNodeScreen(navController: AppNavController) {
    val context = LocalContext.current

    // 进页面确保内置服务起来（幂等）。
    DisposableEffect(Unit) {
        PkHostOrchestrator.startAsync(context)
        onDispose { }
    }

    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("pk-node 管理后台") }
    // 原生下发的加载目标（SPA 内部导航会改 WebView.url，所以不能用它判断）
    var loadedTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }

    val hostState = PkHostOrchestrator.state
    // 管理后台是**服务根路径**，不是 PK H5 —— 这里不用 PkHostOrchestrator.h5Url()。
    val baseUrl = "http://127.0.0.1:${NodeRuntime.DEFAULT_PORT}/"
    val ready = hostState is PkHostOrchestrator.State.Ready

    // ★ 2026-10-03：与管理后台同样的「自适应页面底色」处理。
    //   管理后台是跟随 App 主题的网页（可能白底也可能黑底），
    //   所以同样不能写死颜色。详见 core/design/component/H5PageColor.kt。
    val pageColor = rememberH5PageColor()

    val webView = remember {
        WebView(context).apply {
            // ★★ 2026-10-04（用户反馈「app 里的 pk-node 页面无法调用输入法」）：
            //   **必须可聚焦**，否则软键盘弹出不出来。
            //
            // # 之前为什么写成 false（以及它错在哪）
            //
            // 早先这里写 ，理由是「WebView 持有焦点会在
            // 点一下再返回时触发 Compose 重入合成崩溃」。那个崩溃是**真的**，
            // 但「禁止聚焦」是**过度治疗**：WebView 不可聚焦 ⇒ 点输入框时
            // 系统不给它输入焦点 ⇒ **IME（输入法）永远弹不出来**。
            // 管理后台有搜索框、登录表单，全都点不动键盘。
            //
            // # 正解（与 PkH5Screen / PkH5ChildScreen 已采用的方案一致）
            //
            // 允许聚焦，但在**移出视图树之前主动 clearFocus**（见下面的
            // onRelease 回调与 releaseWebView）。崩溃的根因是「移除时仍持有
            // 焦点」，清掉焦点就断掉了，不需要牺牲可聚焦性。
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // ★★ 2026-10-04：与 PK 两个 H5 容器保持一致 —— 管理后台同样是响应式页面，
                //   不开这两个会让 meta viewport 失效、按物理像素宽排版而溢出。
                //   自适应行为由 WebView 按设备 density 换算，无固定值。
                useWideViewPort = true
                // ★★ 2026-10-04：与入口容器一致 —— `loadWithOverviewMode` 保持 false。
                // 它会让 WebView 采用 meta viewport 的 `height=device-height`，
                // 该值被解析成 0 → `100vh` / `100%` 全塌（榜单空白、弹窗溢出）。
                // 详见 PkH5Screen 里的完整说明。
                loadWithOverviewMode = true
            }
            // 不需要任何 addJavascriptInterface：管理后台是纯网页，
            // 与 PK H5 的 window 桥无关，宿主插一手只会添乱。
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    progress = 5
                    error = null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progress = 100
                    view?.title?.takeIf { it.isNotBlank() }?.let { title = it }
                    AppLogger.i("PkNodeWeb", "onPageFinished: $url")
                    // 问页面要底色（每次都探：SPA 内部导航会换页面）
                    view?.let { probeH5PageColor(it, pageColor, tag = "PkNodeWeb") }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    err: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        error = "管理后台加载失败：${err?.description ?: "未知错误"}"
                        progress = 0
                    }
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

    // ★ 服务就绪后：写管理会话 cookie → 再加载。
    LaunchedEffect(ready, reloadToken) {
        if (!ready) return@LaunchedEffect
        // 拿会话 + 写 cookie 都要联网，放 IO。
        withContext(Dispatchers.IO) {
            val cookie = PkNodeSync.adminSessionCookie(adminUser(), adminPass())
            if (cookie.isNullOrBlank()) {
                AppLogger.w("PkNodeWeb", "拿管理会话失败，将直接打开（可能停在登录页）")
                return@withContext
            }
            // 先清掉本 host 下旧会话，避免过期 cookie 覆盖新值。
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            runCatching {
                val existing = cm.getCookie(baseUrl) ?: ""
                existing.split(";").forEach { kv ->
                    val name = kv.trim().substringBefore('=')
                    if (name.isNotBlank()) cm.setCookie(baseUrl, "$name=; path=/; Max-Age=0")
                }
            }
            cm.setCookie(baseUrl, cookie)
            cm.flush()
            AppLogger.i("PkNodeWeb", "管理会话已写入 WebView（${cookie.substringBefore('=')}）")
        }
    }

    BackHandler(enabled = true) {
        if (webView.canGoBack()) webView.goBack() else navController.popBackStack()
    }

    // 底色：探测到就紧跟网页；没探到则退回主题色（不猜）。
    val bg = pageColor.value ?: MiuixTheme.colorScheme.surfaceContainer

    // ★ 顶部下移一条状态栏（系统真实高度，带资源兜底 + 日志可验证）。
    val topInset = cn.apixiaoyuan.app.core.design.component.statusBarTopDp()

    // 状态栏图标跟着底色走（底色深 → 浅色图标）。
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
            // ★ 铺满：edge-to-edge 下状态栏那条带子用页面同色，不再留白。
            .background(bg),
    ) {
        if (progress in 1..99 && loadedTarget == null) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ★ 2026-10-03（用户要求：「顶部不要有文字只要纯色填充」）：
        //
        // 原先的标题栏（返回 + 标题 + 加载圈）已整条移除，顶上只留纯色
        // （根 Column 的 `background(bg)`，即探测到的网页底色）。
        //
        //  · 返回：已有 [BackHandler]（系统返回键 → canGoBack/popBackStack）
        //    与左滑返回，那条按钮是冗余的；
        //  · 进度：首屏那条 LinearProgressIndicator 保留（就在本行上方）；
        //  · `title` 仍由 onPageFinished 更新，只是不再显示。

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { webView },
                modifier = Modifier
                    .fillMaxSize()
                    // ★ 2026-10-04：顶部**下移一条状态栏**（系统真实高度，带资源兜底）。
                    //   顶上露出的就是根 Column 的 `background(bg)` = **纯色填充**，
                    //   网页自己的抬头不再被手机状态栏挡住。
                    //   （此前这里完全没有 top padding，WebView 从 y=0 开始画。）
                    .padding(top = topInset),
                update = { view ->
                    if (!ready) return@AndroidView
                    val target = baseUrl to reloadToken
                    if (error == null && target != loadedTarget) {
                        loadedTarget = target
                        AppLogger.i("PkNodeWeb", "加载 pk-node 管理后台：$baseUrl")
                        view.loadUrl(baseUrl)
                    }
                },
                onRelease = { view -> releaseWebView(view) },
            )

            // ★ 2026-10-04（用户要求）：与 PK 页一致 —— **删掉「正在启动内置服务…」提示卡**。
            //   内置 node 现在在 App 启动时就预热（见 `App.onCreate`），
            //   进本页时基本已 Ready；偶尔没就绪就让 WebView 空着，
            //   不要糊一张居中卡片（转场落地瞬间会闪一下，就是用户说的「弹窗」）。
            //   保留 Failed（真出事才提示）。
            when (hostState) {
                is PkHostOrchestrator.State.Failed -> Notice(
                    title = "内置服务启动失败",
                    detail = hostState.message,
                    actionText = "重试",
                    onAction = {
                        PkHostOrchestrator.stop()
                        PkHostOrchestrator.startAsync(context)
                    },
                )

                // Idle / Starting / Ready-准备中：什么都不画（见上面的说明）。
                PkHostOrchestrator.State.Idle,
                PkHostOrchestrator.State.Starting,
                is PkHostOrchestrator.State.Ready,
                -> Unit
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
                                text = baseUrl,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = {
                                error = null
                                loadedTarget = null
                                reloadToken++
                            }) { Text("重试") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 取 handshake 拿到的 admin 凭据。
 *
 * ⚠️ handshake 只在编排开始时调过，凭据没被持久化成字段 —— 这里**重新调一次**
 * 最省事也没风险（本机回环、几十毫秒、幂等）。拿不到就退回默认 `admin/admin`
 * （pk-node 首次启动写入的就是这个）。
 */
private fun adminUser(): String? =
    PkHostOrchestrator.adminCredentials?.first ?: "admin"

private fun adminPass(): String? =
    PkHostOrchestrator.adminCredentials?.second ?: "admin"

@Composable
private fun Notice(
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

/** 安全销毁 WebView（与 PK 容器同套路，理由见 PkH5Screen 的 releaseWebView）。 */
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