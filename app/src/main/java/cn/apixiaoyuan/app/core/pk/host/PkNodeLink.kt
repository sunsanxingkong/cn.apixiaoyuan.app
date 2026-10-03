package cn.apixiaoyuan.app.core.pk.host

import cn.apixiaoyuan.app.core.log.AppLogger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * App ↔ 内置 pk-node 的**联动客户端**。
 *
 * ## 为什么需要它
 *
 * pk-node 的 H5（`/pk-h5/pk.html`）要「用某个小猿账号」登录，靠的是
 * URL 上的 `leoAccountId=<小猿 userid>`：服务端据此从它的库里挑出该账号的
 * cookie jar，替 H5 发所有业务请求。所以 App 必须知道**有哪些账号、userid 是多少**。
 *
 * 而 pk-node 的普通接口**刻意不返回 cookie 值**（`publicLeoAccount` 只回名字），
 * 于是专门开了一组「联动接口」——见 pk-node `server.js` 的 `/api/link` 系列。
 *
 * ## 契约（pk-node `351e66c` 起）
 *
 * ```
 * GET /api/link/handshake        （需要 X-PK-Link 令牌，否则 403）
 * ```
 * ```json
 * {
 *   "ok": true, "version": 1,
 *   "admin": { "username": "admin", "password": "admin", "note": "…" },
 *   "port": 8792,
 *   "h5Base": "http://127.0.0.1:8792/pk-h5/pk.html",
 *   "accounts": [
 *     { "id": 1,                    // ← **pk-node 库里的账号 id**，不是小猿 userid
 *       "name": "…",
 *       "yfdU": 1155551346,         // ← 这个才是小猿 userid（= App 侧 SessionStore.yfdU）
 *       "grade": 2,
 *       "deviceChainId": 3,
 *       "cookies": [ {name, value, domain, path} ],
 *       "cookieHeader": "sid=…; ks_sess=…" }
 *   ]
 * }
 * ```
 *
 * ## ⚠️ 令牌为什么是**固定值**
 *
 * pk-node 侧 `config.linkToken` 默认是**每次启动随机**（`crypto.randomBytes(24)`）。
 * 若用随机值，App 就得去解析启动横幅才能拿到，既脆又绕。所以
 * [NodeRuntime.start] 用 `PK_LINK_TOKEN` 把它**固定**成 [LINK_TOKEN]，
 * 双方约定同一个常量即可。安全性靠「只监听 127.0.0.1」保证 ——
 * 服务只对**本机**开放，外网拿不到这个令牌（也连不上端口）。
 */
object PkNodeLink {

    private const val TAG = "PkNodeLink"

    /**
     * 联动令牌（固定值）。
     *
     * 必须与 [NodeRuntime.start] 传给子进程的 `PK_LINK_TOKEN` **完全一致**。
     * 改这里就要同步改那边 —— 两个常量故意放在同一个包下，便于对照。
     */
    const val LINK_TOKEN = "cn.apixiaoyuan.app-internal-link"

    /** 联动接口 HTTP 超时（毫秒）。本机回环，正常 < 20ms。 */
    private const val TIMEOUT_MS = 3_000

    /** 一个 pk-node 侧的小猿账号。 */
    data class Account(
        /** pk-node 库里的账号 id（**不是**小猿 userid）。 */
        val id: Long,
        val name: String?,
        /** 小猿 userid —— 拼 `leoAccountId` 用它，也对应 App 的 `SessionStore.yfdU`。 */
        val yfdU: Long?,
        val grade: Int?,
        val deviceChainId: Long?,
        /** 标准 `name=value; name2=value2` 形态，可直接喂 `SessionStore.importCookieHeader`。 */
        val cookieHeader: String,
    )

    /** 一次 handshake 的全部结果。 */
    data class Handshake(
        val adminUser: String?,
        val adminPass: String?,
        val port: Int?,
        /** H5 入口（pk-node 报的，一般就是 `http://127.0.0.1:8792/pk-h5/pk.html`）。 */
        val h5Base: String?,
        val accounts: List<Account>,
    ) {
        /** 第一个「有 yfdU」的账号 —— 用来拼 `leoAccountId`。 */
        val primary: Account?
            get() = accounts.firstOrNull { (it.yfdU ?: 0L) > 0L }
    }

    /**
     * 调 `GET /api/link/handshake`。
     *
     * 失败（服务没起 / 令牌不对 / 超时）一律返回 null —— 调用方据此降级，
     * 不抛异常（这是启动路径，不该因为联动失败就崩）。
     */
    fun handshake(port: Int = NodeRuntime.DEFAULT_PORT): Handshake? = runCatching {
        val body = httpGet("http://127.0.0.1:$port/api/link/handshake") ?: return@runCatching null
        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) {
            AppLogger.w(TAG, "handshake 返回 ok=false：${body.take(200)}")
            return@runCatching null
        }
        val admin = json.optJSONObject("admin")
        val arr = json.optJSONArray("accounts")
        val accounts = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        Account(
                            id = o.optLong("id", -1L),
                            name = o.optString("name").takeIf { it.isNotBlank() && it != "null" },
                            // ⚠️ 字段名是 `yfdU`：pk-node 直接用 `a.yfd_u`，
                            //    而 node:sqlite 返回的列名保持下划线形式，
                            //    这里两个都试，避免哪边改了就静默取到 null。
                            yfdU = o.optLongOrNull("yfdU") ?: o.optLongOrNull("yfd_u"),
                            grade = o.optIntOrNull("grade"),
                            deviceChainId = o.optLongOrNull("deviceChainId")
                                ?: o.optLongOrNull("device_chain_id"),
                            cookieHeader = o.optString("cookieHeader", ""),
                        ),
                    )
                }
            }
        }
        Handshake(
            adminUser = admin?.optString("username")?.takeIf { it.isNotBlank() },
            adminPass = admin?.optString("password")?.takeIf { it.isNotBlank() },
            port = json.optIntOrNull("port"),
            h5Base = json.optString("h5Base").takeIf { it.isNotBlank() && it != "null" },
            accounts = accounts,
        )
    }.onFailure {
        AppLogger.w(TAG, "handshake 失败：${it.message}")
    }.getOrNull()

    /** `JSONObject.optLong` 的「缺省真的返回 null」封装（原生 optLong 缺失时返回 0）。 */
    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    /** 极简 GET（本机回环，不值得动 OkHttp/Retrofit 栈）。 */
    private fun httpGet(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            // ★ 联动令牌：pk-node 的这两个接口靠它自保护（不带 = 403）
            setRequestProperty("X-PK-Link", LINK_TOKEN)
        }
        try {
            if (conn.responseCode !in 200..299) {
                AppLogger.w(TAG, "handshake HTTP ${conn.responseCode}")
                return@runCatching null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}