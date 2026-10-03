package cn.apixiaoyuan.app.core.pk.host

import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.session.DeviceChainPool
import cn.apixiaoyuan.app.core.session.SessionStore
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把 App 的登录态 / 设备链**推给**内置的 pk-node（★ 2026-10-03）。
 *
 * # 为什么需要它（真机症状：PK H5 出来了但没有登录态）
 *
 * 内置 node 的数据库是**全新**的（`filesDir/pk-node/data/pk-node.sqlite`），
 * 里面**一个小猿账号都没有** —— 真机日志写着：
 *
 * ```
 * 联动完成：账号 0 个，主账号 yfdU=null name=null linked=false
 * ```
 *
 * 而 PK H5 是**由 pk-node 用「它库里那个账号」的 cookie 发业务请求**的
 * （`/api/pk/h5/api?leoAccountId=N` → `jobs.jarOf(账号)`）。
 * 库里没账号 → 没有可用身份 → 页面显示未登录。
 *
 * 所以「App 已经登录了」跟「H5 登录了」是**两件事**：必须把 App 的 cookie
 * 交给 pk-node 一份，它才能拿去用。
 *
 * # 三步（顺序不能换）
 *
 * ```
 * ① POST /api/auth/login      {username, password}      → 拿管理后台会话（pk_sid）
 * ② POST /api/device-chains   {label, cookie}           → 把设备链池推过去
 * ③ POST /api/leo/accounts    {name, cookie}            → 把小猿账号推过去
 * ```
 *
 * ②③ 都需要 ① 的会话；③ 还必须带**有效**的 cookie ——
 * pk-node 收到后会 `probe()` 真打一次接口校验，无效会被拒（`cookie 无效或已过期`）。
 *
 * # 为什么先推设备链
 *
 * pk-node 的 `importAccount` → `applyChain()` 是「优先用**池里**的链」。
 * 先推链，账号一导入就能立刻套上 `ks_*`；否则账号只有 `sid`，
 * PK 出题会 400（`ks_*` 是 PK 的硬要求）。
 *
 * # 幂等性
 *
 * 三个接口都是**幂等**的：
 *  - 登录只是签发会话（无副作用）；
 *  - `upsertDeviceChain` 按 `ks_deviceid` 去重；
 *  - `importAccount` 按 `yfd_u` 去重（重复导入同一账号只更新）。
 *
 * 所以 [syncNow] 可以放心地在每次启动时调用。
 */
object PkNodeSync {

    private const val TAG = "PkNodeSync"

    /** 本机回环，正常 < 50ms；给足余量（导入账号要真打小猿接口，可能慢）。 */
    private const val TIMEOUT_MS = 20_000

    /** 一次同步的结果，供 UI / 日志展示。 */
    data class Result(
        val loggedIn: Boolean,
        val chainsPushed: Int,
        val accountPushed: Boolean,
        val message: String,
    )

    /**
     * 跑一遍完整同步。
     *
     * 失败**不抛异常** —— 这是启动路径，任何一步挂掉都只降级并记日志。
     *
     * @param adminUser pk-node 的默认管理员名（来自 handshake）
     * @param adminPass 同上
     * @param force     即使已经同步过也重来（用户点「重新同步」时用）
     */
    fun syncNow(
        adminUser: String?,
        adminPass: String?,
        force: Boolean = false,
    ): Result {
        // ① 登录拿会话
        val sid = login(adminUser, adminPass)
        if (sid == null) {
            return Result(false, 0, false, "登录内置服务失败（admin 密码被改过？）")
                .also { AppLogger.w(TAG, it.message) }
        }

        // ② 推设备链池
        var chains = 0
        runCatching {
            DeviceChainPool.listAll().forEach { item ->
                val text = cookieTextOf(item.decodeCookies())
                if (text.isNullOrBlank() || !text.contains("ks_deviceid")) return@forEach
                if (post("/api/device-chains", JSONObject().apply {
                        put("label", item.label)
                        put("cookie", text)
                    }, sid)) {
                    chains++
                }
            }
        }.onFailure { AppLogger.w(TAG, "推设备链失败：${it.message}") }

        // ③ 推小猿账号（用 App 当前的登录态）
        val sessionText = cookieTextOf(SessionStore.loadCookies())
        if (sessionText.isNullOrBlank()) {
            return Result(true, chains, false, "App 里没有登录态，无处可推（请先导入登录态）")
                .also { AppLogger.w(TAG, it.message) }
        }
        val acctName = SessionStore.currentNickname?.takeIf { it.isNotBlank() } ?: "App 导入"
        val acctOk = post("/api/leo/accounts", JSONObject().apply {
            put("name", acctName)
            put("cookie", sessionText)
        }, sid, timeoutMs = TIMEOUT_MS)

        val msg = buildString {
            append("会话 OK；设备链 +$chains；账号")
            append(if (acctOk) "已导入" else "导入失败（cookie 可能过期）")
        }
        AppLogger.i(TAG, msg)
        return Result(true, chains, acctOk, msg)
    }

    /**
     * `POST /api/auth/login` → 返回 `pk_sid` 的 `name=value`（供鉴权用）。
     *
     * ⚠️ 必须把 `Set-Cookie` 原样带回来（含 `Path` / `HttpOnly` 等属性），
     * 后续请求靠它鉴权。这里只取第一段 `name=value`。
     *
     * 公开给 [cn.apixiaoyuan.app.feature.pknode.PkNodeScreen] 用 ——
     * 它把这枚 cookie 写进 WebView 的 `CookieManager`，
     * 于是**打开管理后台就是已登录状态**，不用用户再手输 admin/admin。
     */
    fun adminSessionCookie(user: String?, pass: String?): String? = login(user, pass)

    private fun login(user: String?, pass: String?): String? = runCatching {
        val body = JSONObject().apply {
            put("username", user ?: "admin")
            put("password", pass ?: "admin")
        }
        val conn = open("/api/auth/login", body)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                AppLogger.w(TAG, "登录内置服务 HTTP $code")
                return@runCatching null
            }
            // 取 Set-Cookie（只取 name=value 段，属性由服务端重新下发）
            val raw = conn.headerFields["Set-Cookie"]?.firstOrNull()
            val pair = raw?.substringBefore(';')?.trim()
            if (pair.isNullOrBlank()) {
                AppLogger.w(TAG, "登录响应没有 Set-Cookie")
                null
            } else {
                pair
            }
        } finally {
            conn.disconnect()
        }
    }.getOrElse {
        AppLogger.w(TAG, "登录异常：${it.message}")
        null
    }

    /** 带会话 POST 一个 JSON。返回是否 2xx。 */
    private fun post(
        path: String,
        body: JSONObject,
        session: String,
        timeoutMs: Int = 6_000,
    ): Boolean = runCatching {
        val conn = open(path, body, session, timeoutMs)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                // 读一下 body 好知道原因（服务端会回 {ok:false,message}）
                val err = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                AppLogger.w(TAG, "POST $path HTTP $code ${err?.take(200) ?: ""}")
                false
            } else {
                true
            }
        } finally {
            conn.disconnect()
        }
    }.getOrElse {
        AppLogger.w(TAG, "POST $path 异常：${it.message}")
        false
    }

    /** 组装一个 JSON POST 连接（未发出）。 */
    private fun open(
        path: String,
        body: JSONObject,
        session: String? = null,
        timeoutMs: Int = 4_000,
    ): HttpURLConnection {
        val conn = (URL("http://127.0.0.1:${NodeRuntime.DEFAULT_PORT}$path")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // 联动令牌（部分接口用它自保护）
            setRequestProperty("X-PK-Link", PkNodeLink.LINK_TOKEN)
            if (!session.isNullOrBlank()) setRequestProperty("Cookie", session)
        }
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { (it as OutputStream).write(bytes) }
        return conn
    }

    /**
     * 把 cookie 列表拼成 `name=value; name2=value2` 串。
     *
     * ⚠️ 只挑**有值**的项 —— 原版/服务端曾用 `Set-Cookie: ks_deviceid=`
     * （空值）表示「设备链无效」，把空值带过去等于污染对方库。
     *
     * @return null = 一条都没有
     */
    private fun cookieTextOf(cookies: List<SessionStore.CookieEntry>): String? {
        val parts = cookies
            .filter { it.name.isNotBlank() && it.value.isNotBlank() }
            .map { "${it.name}=${it.value}" }
        return if (parts.isEmpty()) null else parts.joinToString("; ")
    }
}