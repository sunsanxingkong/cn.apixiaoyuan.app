package cn.apixiaoyuan.app.feature.pknode

import android.annotation.SuppressLint
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
import androidx.compose.ui.viewinterop.AndroidView
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

    val webView = remember {
        WebView(context).apply {
            // 与 PK 容器同样的焦点纪律：WebView 天生可聚焦，持有焦点会在
            // 「点一下再返回」时触发 Compose 重入合成崩溃（详见 PkH5Screen 的注释）。
            isFocusable = false
            isFocusableInTouchMode = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
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

    Column(modifier = Modifier.fillMaxSize()) {
        if (progress in 1..99 && loadedTarget == null) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (webView.canGoBack()) webView.goBack() else navController.popBackStack()
            }) {
                Icon(imageVector = AppIcons.Back, contentDescription = "返回")
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (progress in 1..99) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
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

            when (hostState) {
                PkHostOrchestrator.State.Idle,
                PkHostOrchestrator.State.Starting,
                -> Notice("正在启动内置服务…", "首次启动需要解压并拉起 node（约 2 秒）", busy = true)

                is PkHostOrchestrator.State.Failed -> Notice(
                    title = "内置服务启动失败",
                    detail = hostState.message,
                    actionText = "重试",
                    onAction = {
                        PkHostOrchestrator.stop()
                        PkHostOrchestrator.startAsync(context)
                    },
                )

                is PkHostOrchestrator.State.Ready -> if (error == null && loadedTarget == null) {
                    // 就绪但还没触发加载（LaunchedEffect 写 cookie 中）——短暂提示。
                    Notice("正在准备管理后台…", baseUrl, busy = true)
                }
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