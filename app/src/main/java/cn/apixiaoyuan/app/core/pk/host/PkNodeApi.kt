package cn.apixiaoyuan.app.core.pk.host

import cn.apixiaoyuan.app.core.log.AppLogger
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内置 pk-node 的 HTTP API 客户端（★ 2026-10-05，竞速页用）。
 *
 * ## 定位
 *
 * [PkNodeSync] 负责「把 App 登录态**推给** pk-node」；本类负责**反向** ——
 * App 作为客户端调用 pk-node 的**业务接口**（竞速 `/api/race/` 系列、任务
 * `/api/jobs/` 系列等），与 pk-node 自己的网页（`public/app.js`）打的是同一套 API。
 * 「比赛竞速」页的全部能力（获取知识点 / 榜单 / 起任务 / 日志流 / 停止）
 * 都经由本类，**不重写业务** —— 竞速的协议（WS v2 / 贴限 / 频控）只在
 * pk-node 里维护一份，App 是它的客户端。
 *
 * ## 鉴权
 *
 * 与网页同款：先 `POST /api/auth/login` 拿 `pk_sid` 会话 cookie（复用
 * [PkNodeSync.adminSessionCookie]），之后所有请求带 `Cookie: pk_sid=…`。
 * 会话缓存到 [cachedSid]；收到 401/403 时清缓存重登一次
 * （服务重启后旧会话失效的场景）。
 *
 * ## 为什么不用 OkHttp / Retrofit 栈
 *
 * 内置服务走 `127.0.0.1` 回环、JSON 极小 —— 与 [PkNodeSync] / [PkNodeLink]
 * 同一取舍：`HttpURLConnection` 够用且零新依赖。SSE 流（[openSse]）
 * 也用它，调用方逐行读 `data:` 行即可。
 */
object PkNodeApi {

    private const val TAG = "PkNodeApi"

    /**
     * 常规请求超时（毫秒）。
     *
     * ⚠️ 竞速的 `home` / `rank` 要**真打小猿接口**（拉知识点 / 榜单），
     * 慢的时候好几秒，所以给 60s；`run` / `stop` 是本地起任务，毫秒级。
     */
    private const val TIMEOUT_MS = 60_000

    /**
     * SSE 读超时（毫秒）—— 服务端每 15s 发一个心跳注释行（`:ping`），
     * 90s 无任何数据视为断线，调用方重连。
     */
    const val SSE_READ_TIMEOUT_MS = 90_000

    @Volatile
    private var cachedSid: String? = null

    /** 后端基址（内置 node 固定端口，见 [NodeRuntime.DEFAULT_PORT]）。 */
    fun base(path: String): String = "http://127.0.0.1:${NodeRuntime.DEFAULT_PORT}$path"

    /** 拿（并缓存）管理会话 cookie。 */
    @Synchronized
    private fun sid(adminUser: String?, adminPass: String?): String? {
        cachedSid?.let { return it }
        val s = PkNodeSync.adminSessionCookie(adminUser, adminPass)
        if (s != null) cachedSid = s
        return s
    }

    /** 服务重启 / 会话失效时清缓存（由 [getJson] / [postJson] 内部调用）。 */
    fun invalidateSession() {
        cachedSid = null
    }

    /**
     * GET 一个 JSON。
     *
     * @return null = 连不上（服务没起 / 网络异常）；
     *         否则返回响应体（HTTP 非 2xx 且有 body 时也返回 body，
     *         调用方看 `ok` 字段 —— pk-node 的错误都用 `{ok:false,message}` 表达）。
     */
    fun getJson(path: String, adminUser: String?, adminPass: String?): JSONObject? {
        var s = sid(adminUser, adminPass) ?: return null
        var (code, json) = requestOnce("GET", path, null, s)
        if (code == 401 || code == 403) {
            invalidateSession()
            s = sid(adminUser, adminPass) ?: return null
            val r2 = requestOnce("GET", path, null, s)
            code = r2.first
            json = r2.second
        }
        if (json == null) {
            AppLogger.w(TAG, "GET $path → HTTP $code（无 body）")
            return null
        }
        return json
    }

    /**
     * POST 一个 JSON。语义同 [getJson]。
     */
    fun postJson(
        path: String,
        body: JSONObject,
        adminUser: String?,
        adminPass: String?,
    ): JSONObject? {
        var s = sid(adminUser, adminPass) ?: return null
        var (code, json) = requestOnce("POST", path, body, s)
        if (code == 401 || code == 403) {
            invalidateSession()
            s = sid(adminUser, adminPass) ?: return null
            val r2 = requestOnce("POST", path, body, s)
            code = r2.first
            json = r2.second
        }
        if (json == null) {
            AppLogger.w(TAG, "POST $path → HTTP $code（无 body）")
            return null
        }
        return json
    }

    /**
     * 打开一条 SSE（`text/event-stream`）连接。
     *
     * 调用方负责逐行读 [HttpURLConnection.getInputStream] 并解析 `data:` 行
     * （参见 `RaceViewModel.attachStream`）；读完 / 出错时 `disconnect()`。
     *
     * @return null = 拿不到会话 / 连接失败
     */
    fun openSse(path: String, adminUser: String?, adminPass: String?): HttpURLConnection? {
        val s = sid(adminUser, adminPass) ?: return null
        return try {
            (URL(base(path)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = SSE_READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("X-PK-Link", PkNodeLink.LINK_TOKEN)
                setRequestProperty("Cookie", s)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "openSse 失败：${t.message}")
            null
        }
    }

    /** 单次请求（不重试）。返回 (HTTP code, body-json?)。 */
    private fun requestOnce(
        method: String,
        path: String,
        body: JSONObject?,
        cookie: String,
    ): Pair<Int, JSONObject?> {
        return try {
            val conn = (URL(base(path)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = TIMEOUT_MS
                requestMethod = method
                setRequestProperty("X-PK-Link", PkNodeLink.LINK_TOKEN)
                setRequestProperty("Cookie", cookie)
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                conn.doOutput = true
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { (it as OutputStream).write(bytes) }
            }
            val code = conn.responseCode
            val text = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            conn.disconnect()
            val json = text?.takeIf { it.isNotBlank() }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            code to json
        } catch (t: Throwable) {
            AppLogger.w(TAG, "$method $path 异常：${t.message}")
            0 to null
        }
    }
}
