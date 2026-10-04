package cn.apixiaoyuan.app.core.launch

import android.app.Activity
import android.content.Context
import android.os.Process
import cn.apixiaoyuan.app.core.log.AppLogger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess

/**
 * 启动清单（launch manifest）—— 读一份**远端静态 JSON**，决定本次是否继续启动。
 *
 * # 它解决什么
 *
 * App 在某些极端情况下需要一个**外部开关**来让它「这一次别启动」。
 * 典型的两个场景：
 *
 *  1. 服务端已风控、再跑下去会连累账号 —— 先让 App 别跑；
 *  2. App 自身进入了坏状态（反复闹事、被盯上），需要在**不接触设备**的前提下
 *     先把它停下来（用户此时往往不方便连电脑，改不了本地配置）。
 *
 * 因此这份清单必须是**最不可能坏**的形式：一个静态文件，没有后端。
 * 详见 pk-node 仓库根目录的 `LAUNCH-MANIFEST.md`（设计说明 + 维护方法）。
 *
 * # 语义（就两个状态）
 *
 * 远端 JSON：
 * ```json
 * { "service": "pk-node", "manifest": 1, "relay": true }
 * ```
 *
 * | `relay` | 本次启动 |
 * |---|---|
 * | `true` / 字段不存在 / 网络失败 | **正常启动** |
 * | `false` | **不继续启动**（静默退出） |
 *
 * # ⚠️ 最要紧的一条：判据是**白名单式**的
 *
 * **只有「确凿读到 `false`」才退出，其余一切情况（断网、DNS 失败、超时、
 * 404、JSON 解析失败、字段缺失…）一律放行。**
 *
 * 为什么：如果反过来「取不到就退出」，那么一次断网、一次静态页被删、
 * 一次 CDN 抽风，都会让 App **永远打不开** —— 一个本来用于救急的开关
 * 反而变成了永久自杀开关。宁可漏一次，不可误杀。
 *
 * # 为什么用「本地缓存 + TTL」而不是每次都等网络
 *
 * 每次都等网络 → 每次冷启动都要加 0.3~1.5s（弱网下就是 1.5s 封顶），
 * 为了一个几乎不用的开关，代价太大。
 *
 * 所以把上一次的结果缓存起来，**TTL 内直接采信缓存、完全不联网**：
 *
 * | 情况 | 行为 | 加延迟 |
 * |---|---|---|
 * | 有缓存且在 TTL 内 | 直接用缓存值 | **0（不联网）** |
 * | 缓存过期 / 从没查过 | 联网查一次（最多等 [GATE_TIMEOUT_MS]） | <=1.5s |
 *
 * 代价：改了远端开关后，**最多晚 [CACHE_TTL_MS] 生效**（下次启动时命中）。
 * 对这个开关的用途来说完全可以接受 —— 它不是分秒必争的东西。
 *
 * # 隐蔽性
 *
 * 用户硬性要求「功能一定要隐蔽」。本类的做法是：
 *  · 命名全部取得能自圆其说（启动清单 / relay 中继），不出现敏感字眼；
 *  · 退出时**不留下任何痕迹**：无异常、无崩溃、无提示、无系统对话框；
 *  · 网络请求**不带任何自定义头**（不额外制造可识别指纹）；
 *  · 清单地址直接用上游仓库的公开静态页，不额外申请域名（少一个关联点）。
 */
object LaunchManifest {

    private const val TAG = "LaunchManifest"

    /**
     * 清单地址。
     *
     * 当前指向 pk-node 仓库的静态站（GitHub Pages）。
     * 要改到 Cloudflare Pages 时**只改这一行**，其余代码不用动 ——
     * 因为两份产物是完全相同的静态文件（见 `LAUNCH-MANIFEST.md`）。
     */
    private const val MANIFEST_URL = "https://sxd91.github.io/pk-node/relay.json"

    /** 字段名：控制本次是否继续启动。 */
    private const val KEY_RELAY = "relay"

    private const val PREF = "launch_manifest"
    private const val KEY_VALUE = "relay_value"
    private const val KEY_AT = "relay_checked_at"

    /** 联网查询的最长等待（毫秒）。超时即按「已知值 / 放行」处理。 */
    private const val GATE_TIMEOUT_MS = 1500L

    /** 本地缓存的有效期（毫秒）。TTL 内不联网。 */
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    /**
     * 启动闸门。**在 `MainActivity.onCreate` 里 `super.onCreate` 之后立刻调用**。
     *
     * 返回 = 继续启动；不返回 = 已退出进程。
     *
     * ⚠️ 会**阻塞当前线程**最多 [GATE_TIMEOUT_MS]，所以只能在主线程启动路径上调一次，
     *    且必须保证硬上限（否则会 ANR）。
     */
    fun gate(activity: Activity) {
        val sp = runCatching {
            activity.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        }.getOrNull() ?: return  // 拿不到 SP 就放行（绝不因为机制自身出错而拦住用户）

        val known: Boolean? = runCatching {
            if (sp.contains(KEY_VALUE)) sp.getBoolean(KEY_VALUE, true) else null
        }.getOrNull()

        val age = System.currentTimeMillis() -
            runCatching { sp.getLong(KEY_AT, 0L) }.getOrDefault(0L)

        val cached: Boolean? = known?.takeIf { age in 0 until CACHE_TTL_MS }

        val allow: Boolean = cached ?: run {
            // 缓存过期 / 从未查过：联网查一次。
            // 失败一律回落：有旧值用旧值，没旧值放行。
            query(fallback = known ?: true).also { v ->
                runCatching {
                    sp.edit().putBoolean(KEY_VALUE, v)
                        .putLong(KEY_AT, System.currentTimeMillis()).apply()
                }
            }
        }

        if (!allow) {
            // 静默退出：不抛异常、不弹窗、不留崩溃记录。
            AppLogger.i(TAG, "清单未放行，结束本次启动")
            runCatching { activity.finishAndRemoveTask() }
            runCatching { Process.killProcess(Process.myPid()) }
            exitProcess(0)
        }
    }

    /**
     * 联网取一次清单。
     *
     * @param fallback 取不到时的回落值（调用方保证「有旧值用旧值、没旧值放行」）
     * @return 是否放行
     */
    private fun query(fallback: Boolean): Boolean {
        var box: Array<Boolean?> = arrayOf(null)
        val t = Thread({
            box[0] = runCatching { fetch() }.getOrNull()
        }, "launch-manifest")
        t.isDaemon = true
        t.start()
        return runCatching {
            // 硬上限：join 超时也照旧往下走（网络线程是 daemon，会自己结束）
            t.join(GATE_TIMEOUT_MS)
            box[0] ?: fallback
        }.getOrDefault(fallback)
    }

    /**
     * 真正发请求并解析。
     *
     * @return true=放行 / false=不放行 / null=没拿到有效信息（调用方回落）
     */
    private fun fetch(): Boolean? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = GATE_TIMEOUT_MS.toInt()
                readTimeout = GATE_TIMEOUT_MS.toInt()
                instanceFollowRedirects = true
                // 不设 User-Agent / Referer 等自定义头：不给外部多制造一个可识别特征。
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) {
                AppLogger.w(TAG, "清单 HTTP ${conn.responseCode}，按放行处理")
                null
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parse(body)
            }
        } catch (e: Throwable) {
            // 断网 / DNS / 超时 / TLS 全走这里 —— 一律「没拿到」，由调用方回落放行。
            AppLogger.d(TAG, "清单获取失败（按放行处理）：${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 解析清单正文。
     *
     * 抽出成独立函数是为了**能被单独验证**（不依赖网络）。
     *
     * @return true=放行 / false=不放行 / null=没拿到有效信息
     */
    internal fun parse(body: String): Boolean? {
        return runCatching {
            val o = JSONObject(body)
            // 字段不存在 / 不是布尔 -> 都当「没说」-> 回落放行。
            if (!o.has(KEY_RELAY)) null else o.optBoolean(KEY_RELAY, true)
        }.getOrNull()
    }
}
