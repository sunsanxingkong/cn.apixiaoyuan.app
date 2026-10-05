package cn.apixiaoyuan.app.feature.grind

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.apixiaoyuan.app.core.log.AppLogger
import cn.apixiaoyuan.app.core.pk.host.PkHostOrchestrator
import cn.apixiaoyuan.app.core.pk.host.PkNodeApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 「比赛竞速」页的状态机（★ 2026-10-05）。
 *
 * ## 定位：App 是 pk-node 的**客户端**
 *
 * 竞速的协议（WS v2 握手 / sign / 8 人匹配 / 贴限模式 / 跨局自调）全部由
 * **内置 pk-node** 实现（`src/school-season.js`），本类只做三件事：
 *
 *  1. 把用户的选择（账号 / 知识点 / 提交时间 / 贴限）转成 `/api/race/run` 的 body；
 *  2. 订阅 `/api/race/stream`（SSE）把逐条日志搬进 [logs]；
 *  3. 提供「获取知识点 / 拉榜单 / 停止」的动作入口。
 *
 * 这样「内容对齐 pk-node」不是靠照抄，而是**同一份实现** —— pk-node 网页改动
 * 后 App 自动跟随（只要接口形状稳定）。
 *
 * ## 与 pk-node 网页（public/app.js `runRace`）逐项对齐
 *
 * | 选项 | pk-node 字段 | 默认值 |
 * |---|---|---|
 * | 知识点 | `pointId` | 0 = 用活动主页第一个 |
 * | 提交时间 min/max | `answerDelayMinMs` / `answerDelayMaxMs` | 50 / 150 |
 * | 贴限模式 | `aimCostMode` + `aimSafetyMs` | 开 / 40 |
 * | 题数 | `questionCount` | 0 = 用知识点默认 |
 * | 刷多少局 | `rounds` | 1 |
 * | 局间间隔 | `gapMinMs` / `gapMaxMs` | 0 / 0 |
 * | 单局超时 | `battleMaxMs` | 300000 |
 *
 * ## 日志流的两段式
 *
 * `POST /api/race/run` 返回 `jobId` 后立刻连 `/api/race/stream`：
 * 先收快照（当前任务实况），再收实时事件（`raceMirror`）。
 * 事件按 `jobId` 过滤（服务端会把**所有用户的**竞速事件镜像到通道 0）。
 *
 * ## 与 App 编排器的关系
 *
 * 进页面先 [PkHostOrchestrator.startAsync]（幂等）—— 等 Ready 后
 * 才有 `adminCredentials` 可用于 [PkNodeApi] 的会话登录。
 */
class RaceViewModel : ViewModel() {

    private companion object {
        const val TAG = "RaceVm"

        /** 日志保留上限（与练习页同款：批量裁剪，避免滚动条每帧抖动）。 */
        const val MAX_LINES = 500
        const val TRIM_BATCH = 100
    }

    // ---------------- 服务状态 ----------------

    /** 内置 pk-node 就绪（来自 [PkHostOrchestrator]）。页面据此禁用按钮。 */
    var hostReady by mutableStateOf(false)
        private set

    /** pk-node 库里「当前身份」对应的账号主键 id（拼 API 的 leoAccountId 用）。 */
    var leoAccountId by mutableStateOf(0L)
        private set

    // ---------------- 小猿账号 / 子账号 ----------------

    var accounts by mutableStateOf<List<AccountBrief>>(emptyList())
        private set
    var selectedAccountId by mutableStateOf(0L)
        private set

    var subAccounts by mutableStateOf<List<SubBrief>>(emptyList())
        private set
    var subStatus by mutableStateOf("")
        private set
    var switching by mutableStateOf(false)
        private set

    data class AccountBrief(val id: Long, val name: String?, val yfdU: Long?)
    data class SubBrief(val userId: Long, val nickname: String?, val isPrimary: Boolean, val isCurrent: Boolean)

    // ---------------- 活动 / 知识点 / 榜单 ----------------

    var activityStatus by mutableStateOf("（点「获取知识点」）")
        private set
    var points by mutableStateOf<List<PointBrief>>(emptyList())
        private set
    var selectedPointId by mutableStateOf(0)
        private set

    data class PointBrief(val pointId: Int, val pointName: String?, val questionCnt: Int)

    var rankText by mutableStateOf("（点「拉取榜单」）")
        private set

    // ---------------- 参数 ----------------

    var delayMinMs by mutableStateOf("50")
    var delayMaxMs by mutableStateOf("150")
    var aimCostMode by mutableStateOf(false)
    var aimSafetyMs by mutableStateOf("40")
    var questionCount by mutableStateOf("0")
    var rounds by mutableStateOf("1")
    var gapMinMs by mutableStateOf("0")
    var gapMaxMs by mutableStateOf("0")
    var battleMaxMs by mutableStateOf("300000")

    /** 「提交时间」输入框在贴限模式下由服务端自动计算（界面置灰）。 */
    val delayInputsEnabled: Boolean get() = !aimCostMode

    // ---------------- 运行 ----------------

    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("待开始")
        private set
    var currentJobId by mutableStateOf(0L)
        private set

    val logs = mutableStateListOf<String>()

    private var streamJob: Job? = null

    /**
     * 当前打开的 SSE 连接。
     *
     * 用途：任务结束时（收到 `finished` 事件）**立即断开** —— 否则读取线程
     * 会阻塞在 `readLine()` 直到 90s readTimeout 才退出，日志流白挂一段。
     */
    @Volatile
    private var activeSseConn: java.net.HttpURLConnection? = null

    init {
        // 进页面即刷新一次账号列表（等 hostReady 后由 UI 调 refreshAccounts）。
        refreshAccounts()
    }

    // ================= 服务联动 =================

    /**
     * 等内置服务就绪后刷新账号列表 + 记下 leoAccountId。
     *
     * UI 在 [PkHostOrchestrator.state] 变为 Ready 后调用（见 RaceScreen 的
     * LaunchedEffect）—— 这里只读状态，不重复触发启动。
     */
    fun onHostReady() {
        hostReady = true
        val st = PkHostOrchestrator.state
        if (st is PkHostOrchestrator.State.Ready) {
            leoAccountId = st.leoAccountId ?: 0L
        }
        refreshAccounts()
    }

    /** 拉 pk-node 里的小猿账号列表（决定用哪个账号跑竞速 / 拉子账号）。 */
    fun refreshAccounts() {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                PkNodeApi.getJson("/api/leo/accounts", adminUser(), adminPass())
            } ?: return@launch
            val arr = r.optJSONArray("accounts") ?: return@launch
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        AccountBrief(
                            id = o.optLong("id", -1L),
                            name = o.optString("name").takeIf { it.isNotBlank() && it != "null" },
                            yfdU = if (o.has("yfdU") && !o.isNull("yfdU")) o.optLong("yfdU") else null,
                        ),
                    )
                }
            }
            accounts = list
            if (selectedAccountId <= 0L || list.none { it.id == selectedAccountId }) {
                // 优先选「当前身份」（与 leoAccountId 一致的那个），否则第一个。
                val cur = list.firstOrNull { it.id == leoAccountId }
                selectedAccountId = cur?.id ?: list.firstOrNull()?.id ?: 0L
            }
            if (selectedAccountId > 0L) loadSubAccounts()
        }
    }

    fun selectAccount(id: Long) {
        if (running) return
        selectedAccountId = id
        loadSubAccounts()
    }

    /** 拉选中账号的子账号（pk-node 的 batchGet 数据，可能为空）。 */
    fun loadSubAccounts() {
        val id = selectedAccountId
        subAccounts = emptyList()
        subStatus = "拉取中…"
        if (id <= 0L) {
            subStatus = ""
            return
        }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                PkNodeApi.getJson("/api/leo/accounts/$id/sub-accounts", adminUser(), adminPass())
            }
            if (r == null || !r.optBoolean("ok", false)) {
                subStatus = "子账号拉取失败"
                return@launch
            }
            val arr = r.optJSONArray("subs") ?: return@launch
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        SubBrief(
                            userId = o.optLong("userId", 0L),
                            nickname = o.optString("nickname").takeIf { it.isNotBlank() && it != "null" },
                            isPrimary = o.optBoolean("isPrimary", false),
                            isCurrent = o.optBoolean("isCurrent", false),
                        ),
                    )
                }
            }
            subAccounts = list
            subStatus = if (list.isEmpty()) "无子账号（先在该账号下登录过？）" else "共 ${list.size} 个"
        }
    }

    /**
     * 切换子账号（走 pk-node 已攻破的 switch）。
     *
     * ⚠️ switch 成功后**必须** [PkHostOrchestrator.relinkAsync]：
     * 让 App 登录态与 pk-node 库里的身份重新对齐（与 PkGrindViewModel 一致），
     * 否则竞速仍会跑在旧身份下。
     */
    fun switchSub(targetUserId: Long) {
        if (running || switching) return
        val id = selectedAccountId
        if (id <= 0L) return
        switching = true
        status = "切换账号中…"
        viewModelScope.launch {
            val body = JSONObject().put("userId", targetUserId)
            val r = withContext(Dispatchers.IO) {
                PkNodeApi.postJson("/api/leo/accounts/$id/switch", body, adminUser(), adminPass())
            }
            switching = false
            if (r != null && r.optBoolean("ok", false)) {
                val msg = r.optString("message", "已切换")
                status = msg
                append("切换成功：$msg")
                // 身份变了 → 重推给 pk-node + 重算主键 id
                runCatching { PkHostOrchestrator.relinkAsync() }
                refreshAccounts()
            } else {
                val msg = r?.optString("message") ?: "切换失败（服务未响应）"
                status = msg
                append("切换失败：$msg")
            }
        }
    }

    // ================= 活动 / 知识点 =================

    /** 「获取知识点」：`GET /api/race/home?leoAccountId=`。 */
    fun loadHome() {
        val id = selectedAccountId
        if (id <= 0L) {
            activityStatus = "先选小猿账号"
            return
        }
        activityStatus = "拉取中…"
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                PkNodeApi.getJson("/api/race/home?leoAccountId=$id", adminUser(), adminPass())
            }
            if (r == null) {
                activityStatus = "失败：内置服务无响应"
                return@launch
            }
            if (!r.optBoolean("ok", false)) {
                activityStatus = "失败：HTTP ${r.optInt("status", 0)} ${r.optString("text").take(120)}"
                return@launch
            }
            val home = r.optJSONObject("home") ?: return@launch
            val pts = home.optJSONArray("points") ?: return@launch
            val list = buildList {
                for (i in 0 until pts.length()) {
                    val o = pts.optJSONObject(i) ?: continue
                    add(
                        PointBrief(
                            pointId = o.optInt("pointId", 0),
                            pointName = o.optString("pointName").takeIf { it.isNotBlank() },
                            questionCnt = o.optInt("expectedQuestionCnt", 0),
                        ),
                    )
                }
            }
            points = list
            if (selectedPointId <= 0 || list.none { it.pointId == selectedPointId }) {
                selectedPointId = list.firstOrNull()?.pointId ?: 0
            }
            val finish = home.optJSONObject("user")?.optInt("finishCount", 0) ?: 0
            val endT = home.optLong("activityEndTime", 0L)
            activityStatus = "知识点 ${list.size} 个；已完成 $finish 局；活动截止 " +
                (if (endT > 0) java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(endT)) else "?")
            append("活动主页 OK：${list.size} 个知识点")
        }
    }

    fun selectPoint(pointId: Int) {
        selectedPointId = pointId
    }

    /** 「拉取榜单」：`GET /api/race/rank`（全国）—— 贴限模式下榜单即「该榜下限」的来源。 */
    fun loadRank() {
        val id = selectedAccountId
        val pointId = selectedPointId
        if (id <= 0L || pointId <= 0) {
            rankText = "先选账号并获取知识点"
            return
        }
        rankText = "拉取中…"
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                PkNodeApi.getJson(
                    "/api/race/rank?leoAccountId=$id&pointId=$pointId&scope=1",
                    adminUser(), adminPass(),
                )
            }
            if (r == null || !r.optBoolean("ok", false)) {
                rankText = "失败：${r?.optString("text")?.take(200) ?: "服务无响应"}"
                return@launch
            }
            val rank = r.optJSONObject("rank") ?: return@launch
            val sb = StringBuilder()
            sb.append("知识点：").append(rank.optString("curPointName", "?")).append('\n')
            val self = rank.optJSONObject("self")
            if (self != null) {
                val sr = self.optInt("rank", 0)
                sb.append("我的：").append(if (sr == 999) "未上榜（低于下限）" else "名次 $sr")
                sb.append("，costTime ").append(self.optInt("costTime", 0)).append("ms\n")
            }
            val ranks = rank.optJSONArray("ranks")
            if (ranks != null) {
                sb.append("—— 前 10 ——\n")
                for (i in 0 until minOf(10, ranks.length())) {
                    val it = ranks.optJSONObject(i) ?: continue
                    val p = it.optJSONObject("player")
                    sb.append('#').append(it.optInt("rank", 0)).append(' ')
                        .append(p?.optString("name", "?") ?: "?").append(' ')
                        .append(it.optInt("costTime", 0)).append("ms\n")
                }
            }
            rankText = sb.toString().trimEnd()
        }
    }

    // ================= 起任务 / 停止 =================

    /** 「开始竞速」：`POST /api/race/run` → 订阅日志流。 */
    fun start() {
        if (running) return
        val id = selectedAccountId
        if (id <= 0L) {
            status = "先选小猿账号"
            return
        }
        running = true
        status = "启动中…"
        logs.clear()
        viewModelScope.launch {
            val body = JSONObject().apply {
                put("leoAccountId", id)
                put("pointId", selectedPointId)
                put("questionCount", questionCount.toIntOrNull() ?: 0)
                put("rounds", (rounds.toIntOrNull() ?: 1).coerceAtLeast(1))
                put("answerDelayMinMs", delayMinMs.toIntOrNull() ?: 0)
                put("answerDelayMaxMs", delayMaxMs.toIntOrNull() ?: 0)
                put("gapMinMs", gapMinMs.toIntOrNull() ?: 0)
                put("gapMaxMs", gapMaxMs.toIntOrNull() ?: 0)
                put("battleMaxMs", battleMaxMs.toIntOrNull() ?: 300_000)
                put("aimCostMode", aimCostMode)
                put("aimSafetyMs", aimSafetyMs.toIntOrNull() ?: 40)
                put("useSample", true)
            }
            val r = withContext(Dispatchers.IO) {
                PkNodeApi.postJson("/api/race/run", body, adminUser(), adminPass())
            }
            if (r == null || !r.optBoolean("ok", false)) {
                running = false
                status = "启动失败：${r?.optString("message") ?: "服务无响应"}"
                append(status)
                return@launch
            }
            currentJobId = r.optLong("jobId", 0L)
            val msg = r.optString("message", "已开始")
            status = msg
            append(msg)
            attachStream(currentJobId)
        }
    }

    /** 「停止」：`POST /api/jobs/:id/stop {immediate:true}`。 */
    fun stop() {
        val id = currentJobId
        if (id <= 0L) return
        viewModelScope.launch {
            val body = JSONObject().put("immediate", true)
            val r = withContext(Dispatchers.IO) {
                PkNodeApi.postJson("/api/jobs/$id/stop", body, adminUser(), adminPass())
            }
            append(r?.optString("message") ?: "已停止")
        }
    }

    // ================= SSE 日志流 =================

    /**
     * 订阅 `/api/race/stream`，把 `raceMirror` 事件搬进 [logs]。
     *
     * 事件形状（pk-node 服务端镜像）：`{type, message, jobId, round, …}`；
     * 只显示 `jobId == currentJobId` 的（服务端会把所有用户的事件都镜像过来）。
     *
     * 线程模型：IO 线程逐行读；写 [logs] / [status] 时切回主线程（Compose 状态
     * 必须主线程写）。读超时（90s 无数据）自动重连一次。
     */
    private fun attachStream(jobId: Long) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            while (isActive && running) {
                val conn = withContext(Dispatchers.IO) {
                    PkNodeApi.openSse("/api/race/stream", adminUser(), adminPass())
                }
                if (conn == null) {
                    withContext(Dispatchers.Main) { append("[日志流] 连接失败，3s 后重试…") }
                    delay(3_000)
                    continue
                }
                activeSseConn = conn
                try {
                    withContext(Dispatchers.IO) {
                        conn.inputStream.bufferedReader().use { reader ->
                            var line: String?
                            while (true) {
                                line = reader.readLine() ?: break
                                if (!line.startsWith("data:")) continue
                                val payload = line.removePrefix("data:").trim()
                                if (payload.isEmpty()) continue
                                handleEvent(jobId, payload)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    AppLogger.w(TAG, "日志流断开：${t.message}")
                } finally {
                    runCatching { conn.disconnect() }
                    activeSseConn = null
                }
                if (!isActive) break
                // 断了还 running → 重连（等 2s 防打爆）
                withContext(Dispatchers.Main) { append("[日志流] 重连中…") }
                delay(2_000)
            }
        }
    }

    /** 解析一条 SSE data 行（JSON）→ 过滤 → 更新状态。 */
    private suspend fun handleEvent(jobId: Long, payload: String) {
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return
        // 只处理本任务的（服务端镜像通道是全局的）
        val evJob = o.optLong("jobId", -1L)
        if (evJob != jobId) return
        val msg = o.optString("message", "")
        val type = o.optString("type", "")
        val finished = o.optBoolean("finished", false)
        withContext(Dispatchers.Main) {
            if (msg.isNotBlank()) append(msg)
            // 状态行（进度）用 message 更新（pk-node 网页也是这么显示的）
            when {
                finished -> {
                    running = false
                    status = "已结束"
                    // ★ 立即断开 SSE：否则读取线程会阻塞到 90s readTimeout 才退出。
                    runCatching { activeSseConn?.disconnect() }
                }
                else -> {
                    val done = o.optInt("roundsDone", -1)
                    val total = o.optInt("roundsTotal", -1)
                    if (done >= 0 && total > 0) status = "进行中 $done/$total 局"
                }
            }
            // type 为 fail 且带 finished 的已是终态；其余滚动更新
            if (type == "ok" || type == "fail") {
                val round = o.optInt("round", 0)
                if (round > 0) status = "第 $round 局结束"
            }
        }
    }

    /** 回到页面时，如果之前的任务还在跑，重新挂上日志流。 */
    fun resumeIfRunning() {
        // /api/jobs 里找最近一条 race 且 running 的（仅本用户）
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                PkNodeApi.getJson("/api/jobs", adminUser(), adminPass())
            } ?: return@launch
            val arr = r.optJSONArray("jobs") ?: return@launch
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val kind = o.optJSONObject("config")?.optString("kind", "") ?: ""
                if (kind != "race") continue
                val st = o.optString("status", "")
                if (st == "running" || st == "queued") {
                    val id = o.optLong("id", 0L)
                    if (id > 0L && id != currentJobId) {
                        currentJobId = id
                        running = true
                        status = "进行中（任务 #$id）"
                        append("挂回任务 #$id 的日志流")
                        attachStream(id)
                    }
                    return@launch
                }
                // 只关心最近一条 race（按 id 倒序的第一条）
                break
            }
        }
    }

    // ================= 工具 =================

    private fun adminUser(): String? = PkHostOrchestrator.adminCredentials?.first
    private fun adminPass(): String? = PkHostOrchestrator.adminCredentials?.second

    /**
     * 日志追加 + **批量**裁剪（与练习页 ExercisePumpViewModel 同款）。
     *
     * 超过 [MAX_LINES] 才一次删 [TRIM_BATCH] 行 —— 避免滚动条 maxValue 每帧变小
     * 导致自动跟随滚动抖动（2026-09-30 用户反馈修复的同一问题）。
     */
    private fun append(line: String) {
        logs.add(line)
        if (logs.size > MAX_LINES) {
            repeat(TRIM_BATCH) { if (logs.isNotEmpty()) logs.removeAt(0) }
        }
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}