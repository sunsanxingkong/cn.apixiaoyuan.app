package cn.apixiaoyuan.app.core.pk.host

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 内置 pk-node 宿主的**总编排**：解压工作区 → 起服务 → 联动拿账号 → 算出 H5 地址。
 *
 * ## 为什么要有这一层
 *
 * 上面四件事有严格的先后依赖，且各自都可能失败；散在 UI 里写会变成
 * 「解压没完就去探活」「服务没起就去 handshake」这类竞态。集中在这里，
 * UI 只需读 [state]。
 *
 * ```
 *  ① NodeWorkspace.ensureReady()    解压 pk-node 代码本体（首启 ~2.5MB）
 *        ↓
 *  ② NodeRuntime.start()            exec nativeLibraryDir/libnode.so server.js
 *        ↓
 *  ③ NodeRuntime.awaitReady()       轮询本机端口，等 listener 真的起来
 *        ↓
 *  ④ PkNodeLink.handshake()         拿管理员小猿账号 → 导入 App 登录态
 *        ↓
 *  Ready(h5Base)                    WebView 指向它
 * ```
 *
 * ## 与「App 自己的账号」的关系（串号防线）
 *
 * pk-node 的 H5 用**它自己库里的账号**发请求，靠 URL 上的 `leoAccountId` 选人。
 * 所以「PK 页用谁」这件事在**打开那一刻**就由 [h5Url] 定死，此后用户在
 * App 别处切号不会影响本页 —— 这正是 2026-10-03 修的串号问题的同类根因，
 * 换了容器之后这个性质仍然必须保住。
 *
 * [state] 里固化的 [Ready.leoAccountId] 就是那个「定死的身份」。
 */
object PkHostOrchestrator {

    private const val TAG = "PkHost"

    /** 编排状态。 */
    sealed interface State {
        /** 还没开始。 */
        data object Idle : State

        /** 正在解压 / 起服务 / 联动。 */
        data object Starting : State

        /**
         * 就绪，H5 可以指向 [h5Base]。
         *
         * @param h5Base        pk-node 报的 H5 入口（不带账号参数）
         * @param accountCount  它库里有几个小猿账号
         * @param leoAccountId  本次 PK 页固化的身份（小猿 userid）；null = 它库里还没有账号
         * @param linked        App 的登录态是否已按 pk-node 的账号对齐
         */
        data class Ready(
            val h5Base: String,
            val accountCount: Int,
            val leoAccountId: Long?,
            val linked: Boolean,
        ) : State

        /** 起不来。UI 据此提示，而不是给用户一个白屏。 */
        data class Failed(val message: String) : State
    }

    /** Compose 可观察状态。UI 直接读它。 */
    var state: State by mutableStateOf<State>(State.Idle)
        private set

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    /** 防止重复启动（PkScreen 每次进页都会调 [startAsync]）。 */
    @Volatile
    private var started = false

    /** 启动标记 —— 见 [startAsync] 与 [startBlocking] 的差异。 */
    @Volatile
    private var startedBlocking = false

    /**
     * 起一遍（幂等，异步）。
     *
     * 由 `PkScreen` 在进入 PK 页时调用；也由 `App.onCreate` 提前预热
     * （内置换机/悬浮球后台挂机时不进 PK 页也要服务在跑）。
     */
    fun startAsync(ctx: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = ctx.applicationContext
        scope.launch { startBlocking(app) }
    }

    /**
     * 起一遍（幂等，**阻塞**）。
     *
     * ⚠️ **禁止在主线程调用**：内部有 ① 解压工作区（首启 2.5MB 磁盘 IO）、
     * ② `awaitReady` 最多 20 秒的 HTTP 轮询。在主线程会 ANR。
     *
     * 保留它是因为「设置页的终端 / 自检」这类场景需要**同步**拿到结果
     * （用户提出过「在设置页可以打开终端」）；UI 与前台服务一律用 [startAsync]。
     *
     * @return 启动后的状态（已就绪则直接返回，不重复启动）
     */
    fun startBlocking(ctx: Context): State {
        synchronized(this) {
            if (startedBlocking && state is State.Ready) return state
            startedBlocking = true
        }
        val app = ctx.applicationContext
        state = State.Starting

        // ---- ① 工作区（解压 / 命中版本戳）----
        val ws = NodeWorkspace.ensureReady(app)
        if (!File(ws.dir, "server.js").isFile) {
            return fail("pk-node 工作区不可用（assets 里的 zip 没解出来？）")
        }

        // ---- ② 起服务 ----
        val ok = NodeRuntime.start(app, ws.dir, PkNodeLink.LINK_TOKEN)
        if (!ok) {
            // 已经在前台跑着也算成功（幂等），此时 isRunning 为 true
            if (!NodeRuntime.isRunning) return fail("内置 node 启动失败（见 NodeRuntime 日志）")
        }

        // ---- ③ 等就绪 ----
        if (!NodeRuntime.awaitReady()) {
            return fail("内置 node 起来了但端口没响应（${NodeRuntime.DEFAULT_PORT}）")
        }

        // ---- ④ 联动 ----
        val hs = PkNodeLink.handshake()
        if (hs == null) {
            // 服务活着但联动调不通：H5 仍可打开（内容靠 pk-node 自己的登录态），
            // 只是 App 侧不知道有哪些账号 —— 降级而不是失败。
            AppLogger.w(TAG, "服务已就绪，但 handshake 失败，降级为「未联动」")
            return State.Ready(
                h5Base = defaultH5Base(),
                accountCount = 0,
                leoAccountId = null,
                linked = false,
            ).also { state = it }
        }

        // 把 pk-node 的账号**导入 App 登录态**（用户要求：「管理员的小猿口算账号
        // 自动导入 app 的」）。只认第一个有 yfdU 的账号当主账号。
        val primary = hs.primary
        val linked = applyAccountToSession(primary)

        AppLogger.i(
            TAG,
            "联动完成：账号 ${hs.accounts.size} 个，主账号 yfdU=${primary?.yfdU} name=${primary?.name} " +
                "linked=$linked port=${hs.port}",
        )
        return State.Ready(
            h5Base = hs.h5Base ?: defaultH5Base(),
            accountCount = hs.accounts.size,
            leoAccountId = primary?.yfdU,
            linked = linked,
        ).also { state = it }
    }

    /**
     * 把 pk-node 主账号的 cookie 灌进 [SessionStore]。
     *
     * ## 为什么值得做
     *
     * 用户要求「pk-node 管理员的小猿口算账号自动导入 app」。导入后：
     *  - App 自己的功能（主页 / 练习 / 分数 / 任务）直接用这个账号，不用再手工粘 cookie；
     *  - PK H5 的 `leoAccountId` 与 App 当前身份天然一致，不再有「页面显示的号
     *    和 App 里的号不是一个」这种困惑。
     *
     * ## 幂等
     *
     * 身份没变就什么都不做 —— [SessionStore.importCookieHeader] 会走
     * [SessionStore.saveCookies]，而它的幂等守卫是「内容一致不写盘、不 bump」，
     * 加上这里的 `userid` 比较，重复调用是完全无副作用的。
     *
     * @return true = 已按 pk-node 的账号对齐（或本来就一致）
     */
    private fun applyAccountToSession(primary: PkNodeLink.Account?): Boolean {
        val target = primary?.yfdU ?: return false
        if (target <= 0L) return false
        if (primary.cookieHeader.isBlank()) {
            AppLogger.w(TAG, "pk-node 账号 ${primary.id} 没有 cookie，跳过导入")
            return false
        }

        // 已经是这个身份 → 不重复导入（避免无谓的写盘与 Keystore 加密开销）
        if (SessionStore.cookie("userid")?.toLongOrNull() == target &&
            SessionStore.yfdU == target
        ) {
            AppLogger.i(TAG, "App 登录态已是 pk-node 主账号 $target，无需导入")
            return true
        }

        val n = runCatching { SessionStore.importCookieHeader(primary.cookieHeader) }
            .getOrDefault(0)
        if (n == 0) {
            AppLogger.w(TAG, "导入 pk-node 账号 cookie 失败（cookieHeader 解析出 0 条）")
            return false
        }
        // 兜底写 yfdU：pk-node 的 cookieHeader 若没带 `userid`（有可能，
        // 它只回 jar 里的项），这里显式补上，否则 App 会判「未登录」。
        SessionStore.saveYfdU(target)
        primary.grade?.let { if (it > 0) SessionStore.saveGrade(it) }
        AppLogger.i(TAG, "已导入 pk-node 主账号 $target（$n 条 cookie）")
        return true
    }

    /**
     * 算 H5 入口地址（带本次固化的身份）。
     *
     * ## `leoAccountId` 是必须的
     *
     * pk-node 的 H5 靠它决定「用哪个账号的 cookie 发业务请求」，
     * 下级页（匹配 / 8 人 PK）也靠它 —— 缺了会 404（`leoAccountId` 丢在跳转里）
     * 或退回「用最后进入过的那个账号」（多账号时就是串号）。
     *
     * @return null = 还没就绪（UI 应显示加载中，而不是加载一个空地址）
     */
    fun h5Url(): String? {
        val r = state as? State.Ready ?: return null
        val id = r.leoAccountId
        return if (id != null && id > 0) {
            if (r.h5Base.contains("leoAccountId=")) r.h5Base
            else r.h5Base + "?leoAccountId=" + id
        } else {
            r.h5Base
        }
    }

    /** 兜底 H5 地址（handshake 挂了但服务活着时用）。 */
    private fun defaultH5Base(): String =
        "http://127.0.0.1:${NodeRuntime.DEFAULT_PORT}/pk-h5/pk.html"

    private fun fail(msg: String): State {
        AppLogger.e(TAG, msg)
        return State.Failed(msg).also { state = it }
    }

    /** 停止服务（调试/退出时用）。 */
    fun stop() {
        NodeRuntime.stop()
        state = State.Idle
        started = false
        startedBlocking = false
    }
}