package cn.apixiaoyuan.app.feature.pk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.apixiaoyuan.app.core.account.AccountRepository
import cn.apixiaoyuan.app.core.account.SubAccountItem
import cn.apixiaoyuan.app.core.auth.DeviceRegistrar
import cn.apixiaoyuan.app.core.pk.PkBattleEngine
import cn.apixiaoyuan.app.core.pk.PkBattleRepository
import cn.apixiaoyuan.app.core.pk.PkMode
import cn.apixiaoyuan.app.core.pk.PkPointItem
import cn.apixiaoyuan.app.core.pk.PkStrokeMode
import cn.apixiaoyuan.app.core.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PkGrindViewModel : ViewModel() {
    var points by mutableStateOf<List<PkPointItem>?>(null)
        private set

    var loadError by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var totalWinCount by mutableStateOf<Int?>(null)
        private set

    var weekWinCount by mutableStateOf<Int?>(null)
        private set

    var currentUserId by mutableStateOf<Long?>(null)
        private set

    var subAccounts by mutableStateOf<List<SubAccountItem>?>(null)
        private set

    /** 子账号列表加载失败原因。null = 无错误（可能只是没有小号）。 */
    var subAccountsError by mutableStateOf<String?>(null)
        private set

    var running by mutableStateOf(false)
        private set
    var progress by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /**
     * 运行日志（★ 2026-10-03 新增，对齐刷练习页的日志区）。
     *
     * 用户要求：「pk 刷局加个日志显示就和刷练习一样」。
     *
     * 为什么需要：此前只有一个 `progress` 单行文本 + 最后一条 `message`，
     * 一轮里「出题重试了几次 / 提交被拒的 HTTP 码 / 结算是多少」全被覆盖掉了。
     * 出问题时用户只能看到「完成 0/10 局」，没有任何排查线索
     * （这正是 2026-10-02 排查「一秒钟就结束」时的痛点）。
     *
     * 事件来源 = [PkBattleEngine.runBattle] 的 `onProgress` 回调 —— 引擎里
     * `onEvent(...)` 已经写得足够细（出题/重试/提交/结算/频控退避），
     * 之前只是没地方展示。
     */
    val logs = mutableStateListOf<String>()

    private var job: Job? = null

    init {
        refreshAll()
    }

    fun refreshAll() {
        if (loading) return
        loading = true
        loadError = null
        subAccountsError = null
        currentUserId = SessionStore.yfdU
        viewModelScope.launch {
            try {
                // ★ 2026-10-03：进页面就先确保有设备链。
                //
                // PK 出题（`/leo-game-pk/.../match`）**缺 `ks_*` 恒 400**，
                // 而登录只下发 `sid`。内置的 3 条链已在 App 启动时入池，
                // 这里调一次 ensureRegistered 让它们**真正被套用**——
                // 否则新用户进来就是「一直 400」，且完全不知道缺的是设备链。
                runCatching { DeviceRegistrar.ensureRegistered() }
                    .onFailure { Log.w(TAG, "设备链准备失败：${it.message}") }
                val grade = SessionStore.grade() ?: DEFAULT_GRADE
                val home = PkBattleRepository.fetchMathHome(grade)
                points = home.pointList
                totalWinCount = home.totalWinCount
                weekWinCount = home.weekWinCount
                runCatching { AccountRepository.fetchSubAccounts() }
                    .onSuccess { subAccounts = it.getOrNull() }
                    .onFailure { t ->
                        subAccounts = null
                        subAccountsError = "子账号需设备链登录态：${t.message ?: t}"
                    }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    loadError = "拉取对局类型失败：${t.message ?: t}"
                }
            } finally {
                loading = false
            }
        }
    }

    fun switchSubAccount(item: SubAccountItem) {
        if (running) return
        viewModelScope.launch {
            try {
                message = null
                progress = "切换账号中…"
                AccountRepository.switchTo(item)
                progress = ""
                message = "已切换到 ${item.nickname}"
                // ★ 2026-10-03：切号后重推身份给内置 pk-node（见 PkHostOrchestrator.relinkAsync）
                runCatching { cn.apixiaoyuan.app.core.pk.host.PkHostOrchestrator.relinkAsync() }
                refreshAll()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                progress = ""
                message = "切换失败：${t.message ?: t}"
            }
        }
    }

    /**
     * 开始刷局。
     *
     * 默认值全部对齐 pk-node（2026-10-02 移植）：
     * **不再预防性等 60 秒出题冷却** —— 换到 `match/v2` + `611` 之后出题立刻就通，
     * 那个 400 是旧协议被服务端按频控处理的产物。撞到 400/403 才按
     * [matchRetryIntervalMs] 自动重试；[roundIntervalMs] 只是下限，默认 0。
     */
    fun start(
        rounds: Int,
        pointId: Int,
        costTimeMs: Long?,
        submitDelayMinMs: Long = PkBattleEngine.DEFAULT_SUBMIT_DELAY_MIN_MS,
        submitDelayMaxMs: Long = PkBattleEngine.DEFAULT_SUBMIT_DELAY_MAX_MS,
        roundIntervalMs: Long = PkBattleEngine.DEFAULT_ROUND_INTERVAL_MS,
        rateLimitWaitMs: Long = PkBattleEngine.RATE_LIMIT_BASE_MS,
        matchRetryIntervalMs: Long = PkBattleEngine.MATCH_RETRY_INTERVAL_MS,
        matchRetryMaxMs: Long = PkBattleEngine.MATCH_RETRY_MAX_MS,
        strokeMode: PkStrokeMode,
    ) {
        if (running) return
        if (rounds <= 0) {
            message = "对局数必须 ≥ 1"
            return
        }
        if (pointId <= 0) {
            message = "请先选择对局类型"
            return
        }

        running = true
        message = null
        progress = "开始：刷 $rounds 局（知识点 $pointId）"
        // ★ 2026-10-03：每次开跑清空日志，并记下起始参数（对齐刷练习页）。
        logs.clear()
        appendLog("开始：轮数=$rounds 知识点=$pointId 提交延迟=[$submitDelayMinMs,$submitDelayMaxMs]ms " +
            "轮间隔=${roundIntervalMs}ms 笔迹=$strokeMode")
        job = viewModelScope.launch {
            try {
                // 记下最后一条事件：失败时把它带进结果文案。
                // 否则「完成 0/10 局」这种结果完全没有信息量 —— 用户看不到
                // 是 417 还是频控还是编码器没装载（2026-10-02 的排查教训）。
                var lastEvent = ""
                val result = PkBattleEngine.runBattle(
                    rounds = rounds,
                    modes = setOf(PkMode.MATH),
                    pointId = pointId,
                    costTimeMs = costTimeMs,
                    submitDelayMinMs = submitDelayMinMs,
                    submitDelayMaxMs = submitDelayMaxMs,
                    roundIntervalMs = roundIntervalMs,
                    rateLimitBaseMs = rateLimitWaitMs,
                    matchRetryIntervalMs = matchRetryIntervalMs,
                    matchRetryMaxMs = matchRetryMaxMs,
                    strokeMode = strokeMode,
                    onProgress = { _, done, total, ev ->
                        lastEvent = ev
                        progress = "[数学] $ev（$done/$total）"
                        appendLog("[数学] $ev（$done/$total）")
                    },
                )
                running = false
                progress = ""
                val done = result[PkMode.MATH] ?: 0
                message = if (done >= rounds) {
                    "结束：完成 $done/$rounds 局"
                } else {
                    "结束：完成 $done/$rounds 局｜最后一条：$lastEvent"
                }
                appendLog("===== $message =====")
                refreshScoreOnly()
            } catch (c: CancellationException) {
                running = false
                progress = ""
                message = "已停止。"
                appendLog("===== 已手动停止 =====")
                throw c
            } catch (t: Throwable) {
                running = false
                progress = ""
                message = "异常：${t.message ?: t}"
                appendLog("异常：${t.message ?: t}")
            }
        }
    }

    private fun refreshScoreOnly() {
        viewModelScope.launch {
            runCatching {
                val grade = SessionStore.grade() ?: DEFAULT_GRADE
                PkBattleRepository.fetchMathHome(grade)
            }.onSuccess { home ->
                totalWinCount = home.totalWinCount
                weekWinCount = home.weekWinCount
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /**
     * 追加一行运行日志 + **批量**裁剪（★ 与刷练习页 `ExercisePumpViewModel.append` 同款）。
     *
     * 为什么批量而不是「每行删一行」：真机上曾出现「一旦开始删头，滚动就不丝滑」——
     * 每来一行删一行会让滚动条 `maxValue` 每帧变小，跟随滚动的目标跟着抖。
     * 这里到上限才一次性删 [TRIM_BATCH] 行，触发频率降到 1/100。
     */
    private fun appendLog(line: String) {
        logs.add(line)
        if (logs.size > MAX_LINES) {
            repeat(TRIM_BATCH) { if (logs.isNotEmpty()) logs.removeAt(0) }
        }
    }

    private companion object {
        /** 日志保留上限。 */
        const val MAX_LINES = 500

        /** 一次裁剪多少行（批量，避免滚动抖动）。 */
        const val TRIM_BATCH = 100

        /** 日志 TAG（设备链补链失败等诊断用）。 */
        private const val TAG = "PkGrindVM"

        private const val DEFAULT_GRADE = 2
    }
}
