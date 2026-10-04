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
 *
 * ⚠️ `leoAccountId` 是 pk-node **自己的账号主键 id**（`leo_accounts.id`），
 *    **不是**小猿 userid —— 它内部走 `db.getLeoAccount(id)`（`WHERE id = ?`）。
 *    这里 2026-10-03 曾误传 `yfdU`，导致 H5 每个业务请求都 404「账号不存在」。
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
         * @param leoAccountId  ★ pk-node **库里那个账号的主键 id**（**不是**小猿 userid！）；
         *                      null = 它库里还没有账号。
         *                      pk-node 的 `/api/pk/h5/api` 用 `db.getLeoAccount(id)`
         *                      （`WHERE id = ?`）选账号，所以这里必须是主键。
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

    /**
     * handshake 拿到的管理员凭据（username to password）。
     *
     * 用途：`PkNodeScreen`（管理后台网页）要**自动登录**，得知道账号密码。
     * 不是敏感信息 —— 就是 pk-node 首次启动写入的 `admin/admin`，
     * 且服务只监听 `127.0.0.1`。
     */
    var adminCredentials: Pair<String, String>? by mutableStateOf(null)
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
     * 首次调用时留下的 Application Context（供 [relinkAsync] 不带参数使用）。
     *
     * ⚠️ Application Context，不是 Activity —— 这里只用来跑后台 IO，不碰 UI。
     */
    @Volatile
    private var appCtx: android.content.Context? = null

    /**
     * 最近一次**推给 pk-node** 的 App 身份（`SessionStore.yfdU`）。
     *
     * 用途（2026-10-03，修「切换子账号后 pk-node 没有跟着切」）：
     * 用它判断「App 当前是谁」与「pk-node 里那个是谁」是否还一致。
     * 不一致才重新推 + 重算 [State.Ready.leoAccountId]，避免每次进页都白推一遍
     * （推账号要真打小猿接口，一次好几秒）。
     */
    @Volatile
    private var syncedYfdU: Long = 0

    /** App 当前身份（没有则 0）。 */
    private fun currentYfdU(): Long =
        runCatching { SessionStore.yfdU }.getOrNull() ?: 0L

    /**
     * 起一遍（幂等，异步）。
     *
     * 由 `PkScreen` 在进入 PK 页时调用；也由 `App.onCreate` 提前预热
     * （内置换机/悬浮球后台挂机时不进 PK 页也要服务在跑）。
     */
    fun startAsync(ctx: Context) {
        appCtx = ctx.applicationContext
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = ctx.applicationContext
        scope.launch { startBlocking(app) }
    }

    /**
     * ★ 身份变了（切子账号 / 重新登录）→ 重跑一遍联动，把新身份推给 pk-node。
     *
     * ## 为什么要单独一个入口
     *
     * [startAsync] / [startBlocking] 都有「只跑一次」的门禁（[started] / [startedBlocking]），
     * 而切号之后**必须**重算 H5 的 `leoAccountId`（它决定 H5 用 pk-node 库里哪条账号，
     * 那条账号里存的是 cookie）。所以这里把两个门禁复位再走一遍 start。
     *
     * ## 为什么服务没跑就跳过
     *
     * 用户可能从不逛 PK 页（那时内置 node 根本没起，120MB 的 node 不该为切号而拉起）。
     * 等他真进 PK 页时，[startBlocking] 里的「身份不一致就推」逻辑会自然补上。
     *
     * @return true = 已安排重联动；false = 内置 node 没在跑，跳过
     */
    fun relinkAsync(): Boolean {
        val ctx = appCtx ?: return false
        if (!NodeRuntime.isRunning) {
            AppLogger.i(TAG, "切号后未联动：内置 node 没在跑，等进 PK 页时再补")
            return false
        }
        synchronized(this) {
            started = false
            startedBlocking = false
        }
        // ★ 强制重推：把「上次推过去的身份」清掉，让 startBlocking 里的
        //   「身份不一致才推」判定必然为真。
        //   不加这句会出现死角：切号后 `want` 与 `syncedYfdU` 的比较依赖时序，
        //   万一这中间又被别处写过，就会「切了但没推」。
        syncedYfdU = 0
        AppLogger.i(TAG, "切号后重跑联动（App 身份 → pk-node）")
        startAsync(ctx)
        return true
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
        appCtx = app
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
        //
        // ⚠️ `hs` 后面会被重新赋值（推送登录态之后要重新 handshake），
        //    所以**先做一次非空绑定** `val hs0`，再用可变的 `var hs = hs0`。
        //    否则「`if (hs == null) return` + 重新赋值」会让 Kotlin 的
        //    智能转换失效，后面全是 `Only safe (?.) ... on a nullable receiver`。
        val hs0 = PkNodeLink.handshake()
        if (hs0 == null) {
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
        var hs: PkNodeLink.Handshake = hs0

        // ---- ④.5 ★ 把 App 的登录态推给 pk-node（2026-10-03）----
        //
        // 真机症状：PK H5 出来了但**没有登录态**。
        // 根因：内置 node 的 SQLite 是全新的，**一个小猿账号都没有**
        // （日志：`联动完成：账号 0 个，主账号 yfdU=null linked=false`），
        // 而 H5 的业务请求是 pk-node 用「它库里那个账号」的 cookie 发的。
        //
        // 所以这里主动推一份过去。**只在「它库里没账号」时才推**，避免每次
        // 进 PK 页都重跑一遍（那会真打小猿接口，慢且没必要）。
        // 顺便记住 admin 凭据（管理后台网页要自动登录）。
        adminCredentials = hs.adminUser?.let { u -> hs.adminPass?.let { p -> u to p } }

        // ★★ 2026-10-03 修「切换子账号后 pk-node 没有跟着切」
        //
        // 原来的门禁是「**它库里一个账号都没有**才推」。于是：
        //   - 用户切了子账号 → App 的 cookie / userid 变了，但 pk-node 里那条还是**旧 cookie**
        //     → H5 仍以切换前那个身份发请求 → 看起来就是「没跟着切」；
        //   - 而且 `startBlocking` 只跑一次（[started] 门禁），此后进页根本不会重算
        //     [State.Ready.leoAccountId]。
        //
        // 现在改成「**身份变了就推**」：App 当前 yfdU ≠ 上次推过去的 yfdU 时为真。
        // 没变就跳过 —— 否则每次进 PK 页都要真打小猿接口（probe + profile + 子账号），好几秒。
        val want = currentYfdU()
        val needPush = hs.accounts.isEmpty() || (want > 0L && want != syncedYfdU)
        if (needPush) {
            val res = PkNodeSync.syncNow(hs.adminUser, hs.adminPass, force = hs.accounts.isNotEmpty())
            AppLogger.i(TAG, "自动推送登录态（want=$want last=$syncedYfdU）：${res.message}")
            // 推完重新 handshake 一次，才能拿到刚导入账号的**主键 id**
            // （决定 H5 的 leoAccountId）与 yfdU（灌 cookie 用）
            PkNodeLink.handshake()?.let { hs = it }
            if (res.loggedIn) syncedYfdU = want
        }

        // 把 pk-node 的账号**导入 App 登录态**（用户要求：「管理员的小猿口算账号
        // 自动导入 app 的」）。只认第一个有 yfdU 的账号当主账号。
        //
        // ⚠️ `primary` 既用来**取主键**（拼 leoAccountId，见下）也用来**灌 cookie**，
        //    两者用的字段不同：前者要 `id`，后者要 `cookieHeader`。别混。
        // ★ 2026-10-03：按 **App 当前身份**优先挑主账号（修「切号不成功」）。
        //   不能只取「第一个有 yfdU 的」—— 那在 pk-node 库里存在多条账号时，
        //   会拿到**切换前那条**，然后被 applyAccountToSession 灌回 App，
        //   把用户刚切好的身份改回去。详见 Handshake.primaryFor 与
        //   applyAccountToSession 的 KDoc。
        val appYfdU = currentYfdU().takeIf { it > 0L }
        val primary = hs.primaryFor(appYfdU)
        val linked = applyAccountToSession(primary)

        AppLogger.i(
            TAG,
            "联动完成：账号 ${hs.accounts.size} 个，主账号 yfdU=${primary?.yfdU} name=${primary?.name} " +
                "linked=$linked port=${hs.port}",
        )
        return State.Ready(
            h5Base = hs.h5Base ?: defaultH5Base(),
            accountCount = hs.accounts.size,
            // ★★ 2026-10-03 真 bug（用户报「PK 一直无登录态」）：
            //
            // 这里曾经写的是 `primary?.yfdU`。但 `yfdU` 是**小猿 userid**，
            // 而 pk-node 的 `/api/pk/h5/api` 是这么选账号的：
            //
            //   const leoId = Number(u.searchParams.get('leoAccountId') || 0);
            //   const acc = db.getLeoAccount(leoId);   // SELECT * FROM leo_accounts WHERE id = ?
            //   if (!acc) return sendJson(res, 404, { ok: false, message: '账号不存在' });
            //
            // 于是 App 发 `leoAccountId=<小猿 userid>` → WHERE id = <小猿 userid> → 查不到
            // → **每个请求都 404「账号不存在」**。
            //
            // 真机日志里那一串就是铁证（H5 所有业务请求全军覆没）：
            //   api-result target=xyks.yuanfudao.com/leo-game-pk/api/math/pk/home?...
            //              status=404 body={"ok":false,"message":"账号不存在"}
            //
            // 后果很迷惑人：页面框架能出来（年级还有，因为那来自桥），
            // 但胜场/胜率/背包/活动全是空的 —— 看起来就像「没登录态」。
            //
            // 正确值：pk-node 自己的账号主键 `id`。它自己的网页也是用 id 拼的：
            //   public/app.js: `frame.src = '/pk-h5/pk.html?leoAccountId=' + encodeURIComponent(id)`
            //   而 id 来自 `<option value=String(a.id)>`（a.id = 库主键）。
            leoAccountId = primary?.id,
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
     *    （这里的『一致』指**同一个账号**，不是同一个数值：H5 用 pk-node 的主键，
     *      App 侧用小猿 userid）
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

        // ★★ 2026-10-03 修「账号切换不成功」（我自己引入的回归）：
        //
        // ## 症状与链路
        //
        // 切号 → [relinkAsync] → 把新身份推给 pk-node。但 pk-node 的导入是
        // **按 yfd_u upsert**，切到**另一个**子账号时 yfd_u 变了 → 库里**新增**一条，
        // 旧的**不会消失**。而 `Handshake.primary` 取的是「**第一个**有 yfdU 的账号」
        // —— 也就是最早那条 = **切换前的那个**。
        //
        // 于是这里会把**旧 cookie 灌回 App**，把用户刚切好的身份**改回去**
        // （[SessionStore.saveYfdU] 一写，身份就回到旧的）→ 表现就是「切号不成功」。
        //
        // ## 原则：App 是「谁是当前身份」的权威
        //
        // pk-node 应该**跟随** App，不是反过来。所以两者不一致时**不导入**。
        // （`[relinkAsync]` 已经把 App 的新身份推过去了，下一轮 handshake 就会一致。）
        val appYfdU = runCatching { SessionStore.yfdU }.getOrNull() ?: 0L
        if (appYfdU > 0L && appYfdU != target) {
            AppLogger.i(
                TAG,
                "跳过导入 pk-node 主账号 $target：与 App 当前身份 $appYfdU 不一致（以 App 为准）",
            )
            return false
        }
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
        // ★ 拼进 URL 的是 pk-node 的账号主键（见 [State.Ready.leoAccountId] 的说明）
        val id = r.leoAccountId
        val base = r.h5Base

        // ★★ 2026-10-04：**完全照搬 pk-node 的 openPkPage 拼 URL**。
        //
        // pk-node（`public/app.js:288`）拼的是：
        //
        //     frame.src = '/pk-h5/pk.html?leoAccountId=' + id +
        //       '&pkbot=' + pkbot + '&t=' + Date.now();
        //
        // 而 App 此前**只传了 leoAccountId** —— 少了 `pkbot` 与 `t`。
        // 两者都不是可有可无：
        //
        //  - `pkbot`：三个自动能力（视为正确答案 / 自动提交画笔 / 自动下一局）。
        //    H5 侧 `pkBotCfg()` 会「**没带 pkbot 时读 localStorage 旧值**」，
        //    所以不传就会继承上一次的残留配置 —— 行为不确定。必须像 pk-node
        //    一样**显式传**（没勾就传 `off`，全关）。
        //  - `t`：**防缓存**。这个页面里内联了 H5 hook，吃缓存就会加载到
        //    「没有 hook 的旧页面」（H5_INJECT 的响应头也写了 no-store，
        //    这里再兜一层，与 pk-node 一致）。
        val sb = StringBuilder(base)
        // base 可能已经带了 query（handshake 返回的 h5Base 带 leoAccountId 时）——
        // 那就用 `&` 续接，**不能直接 return**（否则会丢掉 pkbot / t）。
        if (!base.contains("leoAccountId=")) {
            sb.append(if (base.contains('?')) "&" else "?").append("leoAccountId=").append(id ?: 0)
        }
        if (!base.contains("pkbot=")) {
            sb.append("&pkbot=").append(pkbotParam())
        }
        sb.append("&t=").append(System.currentTimeMillis())
        return sb.toString()
    }

    /**
     * 三个自动能力 → `pkbot` 参数（与 pk-node 的 `openPkPage` 逐字一致）。
     *
     * 取自 App 自己的三个开关（语义一一对应）：
     *
     * | App 开关 | pk-node 的 cap |
     * |---|---|
     * | [cn.apixiaoyuan.app.core.oldsimian.OldSimianPrefs.autoCorrect] | `answer`（视为正确答案）|
     * | [cn.apixiaoyuan.app.core.oldsimian.OldSimianPrefs.pkStrokeEnabled] | `autoStroke`（自动提交画笔）|
     * | [cn.apixiaoyuan.app.core.oldsimian.OldSimianPrefs.autoNextRound] | `autoNext`（自动下一局）|
     *
     * 三个都关 → `off`（**显式全关**，而不是省略 —— 省略会让 H5 去读 localStorage 旧值）。
     */
    private fun pkbotParam(): String {
        val p = cn.apixiaoyuan.app.core.oldsimian.OldSimianPrefs
        val caps = buildList {
            if (p.autoCorrect) add("answer")
            if (p.pkStrokeEnabled) add("autoStroke")
            if (p.autoNextRound) add("autoNext")
        }
        return if (caps.isEmpty()) "off" else caps.joinToString(",")
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