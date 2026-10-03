package cn.apixiaoyuan.app.core.oldsimian

import android.webkit.WebView

/**
 * PK 页（H5）的 JS 注入器 —— **内置架构的天然优势**。
 *
 * ## 为什么这边不需要 hook
 *
 * 参考项目 cn.nizou.sxd 是 LSPosed 模块，PK 交互全在**宿主**的 WebView 里，
 * 所以它必须 hook 宿主的 `WebView.loadUrl` / 加载回调，才能在 H5 页面里
 * 塞进自己的脚本（见其 `WebViewHook`）。
 *
 * 本项目的 PK 容器是**自己的** [cn.apixiaoyuan.app.feature.pk.PkH5Screen]，
 * 直接调 [WebView.evaluateJavascript] 就能注入 —— 同一批脚本，省掉整层 hook。
 *
 * ## 注入时机
 *
 * 必须在 `onPageFinished` 之后（此时 `document` 就绪、Vue 可能已挂载）。
 * 但 `onPageFinished` 对同一次加载可能回调多次（含 iframe），因此调用方
 * 需配合 [markPageStarted] 在 `onPageStarted` 时重置标志，保证**每次页面
 * 加载只注入一轮** —— 否则 [PkJsInjector] 的脚本会叠加多个 `setInterval`。
 *
 * ## 脚本清单
 *
 *  | 脚本 | 对应开关 | 作用 |
 *  |---|---|---|
 *  | `js/pk_no_anim.js`    | [OldSimianPrefs.noRankingAnim]  | CSS 动画/过渡归零 + 音效静音 |
 *  | `js/pk_auto_next.js`  | [OldSimianPrefs.autoNextRound]  | 结算页自动开下一局 |
 *  | `js/pk_auto_stroke.js`| [OldSimianPrefs.pkStrokeEnabled]| 题目页自动注入笔迹并提交 |
 *  | `js/eruda.js`         | [OldSimianPrefs.h5DebugConsole] | 注入 Eruda 调试面板（移动端 DevTools）|
 *
 * 四者独立：只想去动画、不想自动连开、只想自动交笔迹、或只想开调试面板的人
 * 可以各开各的。
 *
 * ## Eruda 调试面板（2026-09-28，对齐 WeKit 的 `ErudaConsole`）
 *
 * 参考项目 WeKit（`Ujhhgtg/WeKit`）往微信小程序 WebView 注入 Eruda 并
 * `eruda.init()`。本项目同样处理，但**优势是容器是自己的**：
 * 不需要像 WeKit 那样 hook 宿主的 `onPageFinished`（见其
 * `WeWebViewApi.xwebOnPageFinished`）—— 我们的 [PkH5Screen] 直接调
 * [WebView.evaluateJavascript] 即可。
 *
 * 注入 `assets/js/eruda.js` 后必须**再调一次** `eruda.init()` 才会浮出面板。
 * Eruda 会改变页面外观，所以默认关、只在排障时打开。
 */
object PkJsInjector {

    /** 已注入过脚本的页面标记（按 WebView 实例区分，避免多 WebView 串味）。 */
    private val injected = java.util.WeakHashMap<WebView, Boolean>()

    /**
     * Eruda 脚本在 WebView `localStorage` 里的键（★ 2026-10-03）。
     *
     * ## 为什么要写进 localStorage（用户要求「js 控制台配置要持久化」）
     *
     * 之前每次加载都从 `assets/js/eruda.js` **重新读 474 KB 并注入** ——
     * 那是纯浪费：SPA 每次内部导航都会 `onPageFinished`，PC 端 DevTools 也是
     * 一次下载长期缓存。写进 `localStorage` 后：
     *
     *  - 站点已经有这个键 → 直接用缓存里的脚本，不再读 assets；
     *  - 没有 → 读一次 assets 并**顺手存进去**，下次就不用再读。
     *
     * ## 为什么必须用「两步注入」而不是直接把脚本内容拼进 JS
     *
     * 脚本本身有 474KB，直接 `evaluateJavascript(巨大的字符串)` 有两个问题：
     *  1. 超过 Binder 传输上限的风险（Chromium 走 IPC）；
     *  2. 里面的换行/引号要整体转义，极易出错（本项目已多次在
     *     「内嵌 JS 模板字符串」上翻车，见 `check-inject.js` 的由来）。
     *
     * 所以做法是：**把脚本内容当作普通字符串参数传进去**，由页面侧
     * `localStorage.setItem(KEY, <参数>)` 保存。
     * 参数用 `JSONObject.quote()` 转义（那是 JSON 的 `"..."` 字面量规则，
     * 与 JS 字符串字面量兼容），**不做手工转义**。
     */
    private const val ERUDA_LS_KEY = "__laogua_eruda_js"

    /** 在 `onPageStarted` 里调用，重置注入标记。 */
    fun markPageStarted(webView: WebView) {
        injected.remove(webView)
    }

    /**
     * 在 `onPageFinished` 里调用，按当前开关注入脚本。
     *
     * 无开关开启时不注入任何东西（连空脚本都不执行），
     * 保证「功能默认关 = 行为与没这个功能完全一致」。
     *
     * @param webView PK 容器
     * @return 本次实际注入的脚本数量，便于调用方打日志
     */
    fun injectIfEnabled(webView: WebView): Int {
        val prefs = OldSimianPrefs
        if (!prefs.noRankingAnim && !prefs.autoNextRound && !prefs.pkStrokeEnabled &&
            !prefs.h5DebugConsole
        ) {
            return 0
        }
        if (injected[webView] == true) return 0
        injected[webView] = true

        var count = 0
        if (prefs.noRankingAnim) {
            if (inject(webView, "js/pk_no_anim.js")) count++
        }
        if (prefs.autoNextRound) {
            // 先注入间隔配置，再注入主脚本（主脚本读 window.__pk_next_interval）。
            val interval = prefs.nextRoundIntervalMs.coerceAtLeast(0)
            webView.evaluateJavascript("window.__pk_next_interval=$interval;", null)
            if (inject(webView, "js/pk_auto_next.js")) count++
        }
        if (prefs.pkStrokeEnabled) {
            // 同套路：先把参数写进 window，再注入主脚本。
            // 脚本读 window.__pk_stroke_count / window.__pk_stroke_interval。
            val n = prefs.pkStrokeCount.coerceIn(
                OldSimianPrefs.PK_STROKE_COUNT_MIN,
                OldSimianPrefs.PK_STROKE_COUNT_MAX,
            )
            val iv = prefs.pkStrokeIntervalMs.coerceIn(
                OldSimianPrefs.PK_STROKE_INTERVAL_MIN,
                OldSimianPrefs.PK_STROKE_INTERVAL_MAX,
            )
            webView.evaluateJavascript(
                "window.__pk_stroke_count=$n;window.__pk_stroke_interval=$iv;",
                null,
            )
            if (inject(webView, "js/pk_auto_stroke.js")) count++
        }
        // Eruda 调试面板：脚本 + `eruda.init()` 两步（缺 init 不浮面板）。
        // 对齐 WeKit 的 `ErudaConsole`（它也是 evaluateJavascript 两次）。
        if (prefs.h5DebugConsole) {
            // 幂等 + **持久化**（2026-10-03，用户要求「js 控制台配置要持久化」）。
            //
            // 判定顺序（页面侧一段 JS 搞定）：
            //  1. `document.head` 不存在 → 还没就绪，什么都不做（onPageFinished 会再来）；
            //  2. `window.eruda._isInit` → 本次加载已经初始化过，避免 SPA 内部导航
            //     反复 init 叠出多个面板；
            //  3. `localStorage` 里**已有脚本** → 直接 eval 它（**不读 assets**），
            //     再 init —— 这就是「持久化」带来的省事；
            //  4. 都没有 → 回一个特殊串，由原生读一次 assets 并写进 localStorage。
            webView.evaluateJavascript(
                "(function(){" +
                    "if(!document.head) return 'NOTREADY';" +
                    "if(window.eruda&&window.eruda._isInit) return 'DONE';" +
                    "var src=null;try{src=localStorage.getItem('" + ERUDA_LS_KEY + "')}catch(e){}" +
                    "if(src){try{(new Function(src))();}catch(e){src=null;}}" +
                    "return src?'CACHED':'NEED';" +
                    "})()",
                android.webkit.ValueCallback { r ->
                    when (r?.trim('"')) {
                        "NOTREADY", "DONE" -> Unit
                        else -> {
                            // 'NEED' → 读 assets；'CACHED' → 也走这里（下面只负责 init）
                            if (r?.trim('"') == "NEED") {
                                val js = readAsset(webView, "js/eruda.js")
                                if (js.isNullOrBlank()) return@ValueCallback
                                // ★ 用 JSON 字符串字面量传参，**不手工转义**。
                                //   474KB 走 evaluateJavascript 是没问题的
                                //   （Chromium 的 devtools 通道按块传，不是 Binder 单包）。
                                webView.evaluateJavascript(
                                    "try{localStorage.setItem('" + ERUDA_LS_KEY + "', " +
                                        org.json.JSONObject.quote(js) + ");}catch(e){}",
                                    null,
                                )
                            }
                            // init（无论 CACHED 还是刚写入）
                            webView.evaluateJavascript(
                                "try{if(window.eruda&&!window.eruda._isInit){" +
                                    "eruda.init({useShadowDom:true,defaultPanel:'console'});" +
                                    "eruda.get('console').config.set('displayTimestamps',true);" +
                                    "console.log('[老挂] Eruda 已就绪（脚本已持久化到 localStorage）');" +
                                    "}}catch(e){console.error('eruda.init 失败',e);}",
                                null,
                            )
                        }
                    }
                },
            )
        }
        return count
    }

    /** 从 assets 读文本。读不到返回 null（不影响 H5 本身）。 */
    private fun readAsset(webView: WebView, assetPath: String): String? = runCatching {
        webView.context.assets.open(assetPath).bufferedReader().use { it.readText() }
    }.getOrNull()

    /** 从 assets 读脚本并执行。读不到 / 执行失败都静默返回 false，不影响 H5 本身。 */
    private fun inject(webView: WebView, assetPath: String): Boolean = runCatching {
        val js = webView.context.assets.open(assetPath)
            .bufferedReader()
            .use { it.readText() }
        if (js.isBlank()) return@runCatching false
        webView.evaluateJavascript(js, null)
        true
    }.getOrDefault(false)
}