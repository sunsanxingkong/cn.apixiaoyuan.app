package cn.apixiaoyuan.app.core.pk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * PK 秒结算 + 循环 + 多玩法并发的引擎。
 *
 * ## 语义（用户拍板，2026-09-26）
 *
 * - 「秒结算」：出题 → 解码 → 每题答案填对 → 直接提交（不等真实作答）。
 * - 「循环 PK」：每个玩法连续刷 N 轮（N = 用户设的轮数）。
 * - 「多玩法并发」：勾选 math / multi / final / 语文 几个玩法，就**同时**起
 *   几个协程各自循环 —— **不是同一玩法并发多局**。
 * - 「失败重试」：每轮出题/提交失败按退避策略重试（上限可配）。
 *
 * ## ★★ 2026-10-02：出题不再「先等 60 秒」（移植自 pk-node `804c8ac`）
 *
 * 旧实现的节奏是「每轮之间先睡 ≥60s，再出题」，因为实测撞到过
 * `400 请求过于频繁`，被当成了「账号级 60 秒出题冷却」。
 *
 * pk-node 换了协议之后（[PkProtocol] 类 KDoc 那张表）实测结论是：
 * **那个 400 是「`631` + `_appId=6` + 明文 `match`」这套异构请求被服务端按频控
 * 处理的产物，不是真的账号级冷却。** 换到 `611` + `match/v2` 之后出题**立刻就通**，
 * 不需要先睡一分钟 —— 这是本次提速的根本原因。
 *
 * 所以现在的产品口径改成 pk-node 那一套「**不预防性等待，撞到再说**」：
 *
 * | 阶段 | 旧 | 新 |
 * |---|---|---|
 * | 轮间隔（[roundIntervalMs]） | 建议 ≥60000 | **默认 0**（不等；用户想加节奏自己填） |
 * | 出题撞 400/403 | 整轮直接失败 | **按 [matchRetryIntervalMs] 自动重试**，累计超 [matchRetryMaxMs] 才判失败 |
 * | 提交撞 403 | 退避 60s / 120s | 退避 **10s / 20s**（[RATE_LIMIT_BASE_MS]） |
 *
 * 这样配置里那个「循环间隔」就只是**下限**（保护服务器、也让节奏像真人），
 * 而不需要用户去猜一个刚好大于频控窗口的数 —— 猜小了会白跑一局，
 * 猜大了又白白拖慢。
 *
 * ## 为什么 Engine 与 ViewModel 分开
 *
 * 与练习线 [cn.apixiaoyuan.app.core.oldsimian.ScorePump] 的分层一致：算法
 * （循环/并发/退避）在 Engine，ViewModel 只做「起/停一个 Job + 把进度转文案」。
 * 这样 Engine 可单测、不依赖 Compose 状态。
 */
object PkBattleEngine {

    /** 默认失败重试上限（非频控类失败）。 */
    const val DEFAULT_MAX_RETRY = 3

    /** 默认重试基础退避（毫秒）。 */
    const val DEFAULT_RETRY_BASE_MS = 800L

    /** 默认每局间隔（毫秒），0 = **不等待**（见类 KDoc：不再预防性等冷却）。 */
    const val DEFAULT_ROUND_INTERVAL_MS = 0L

    /**
     * **出题冷却配速**（对齐 pk-node `PK.matchCooldownMs`）：默认 **0 = 不预防性等待**。
     *
     * pk-node 里这个值只是「引擎是否**自动**替你贴着冷却下沿发车」的开关：
     *  - `0`（默认）→ 完全按用户填的轮间隔走，撞到 400/403 才由出题重试兜底；
     *  - `> 0`  → 按「同账号上次成功出题时刻 + 该值」配速，不白撞窗口也不多等。
     *
     * 低版本（本 App）此前完全没有这个概念，用户想贴冷却只能自己把轮间隔填大 ——
     * 填小了白跑一局，填大了白白拖慢。这里把它作为**可调参数**补上，默认关闭
     * （保持既有行为不变）。
     */
    const val DEFAULT_MATCH_COOLDOWN_MS = 0L

    /**
     * 长等待期间的「滴答」间隔（毫秒）。
     *
     * 对齐 pk-node `sleepWithTicks` 的 5 秒：轮间隔 / 答题间隔 / 频控退避期间
     * **必须持续有事件**，否则界面上就是一片空白，用户会以为卡死。
     */
    private const val TICK_INTERVAL_MS = 5_000L

    /**
     * **提交**命中频控（403/429）时的退避基数（毫秒）。
     *
     * ## 为什么从 60s 降到 10s（2026-10-02，对齐 pk-node）
     *
     * 旧值 60s 是照着「提交接口频控窗口约十分钟级」拍的。pk-node 实测下来
     * 窗口没那么长，`10s × 2` 就够（基数 10000ms、最多 2 次）；硬等 60s/120s
     * 只会让一轮平白多花两分钟，而多等并不会提高放行概率。
     *
     * 真要窗口更长，用户在页面上把基数调大即可 —— 这是**可调参数**，不是常量。
     */
    const val RATE_LIMIT_BASE_MS = 10_000L

    /** 提交频控最多退避重试次数。 */
    const val RATE_LIMIT_MAX_WAIT = 2

    /**
     * **出题**撞频控（400/403）时的自动重试间隔（毫秒）。
     *
     * 真机点「继续PK」是立刻请求的；撞上频控时真人会等一会儿再点。
     * 本引擎把这件「等一会儿再点」自动化：报一次日志、睡 [MATCH_RETRY_INTERVAL_MS]、再试。
     */
    const val MATCH_RETRY_INTERVAL_MS = 10_000L

    /**
     * 出题重试的**累计上限**（毫秒）：超过就判该轮失败。
     *
     * 给足 2 分钟是为了兜住偶发的惩罚窗口；但正常情况下换到 `match/v2`
     * 后**一次就通**，根本走不到重试。
     */
    const val MATCH_RETRY_MAX_MS = 120_000L

    /**
     * 出题成功 → 提交答案 之间的默认间隔区间（毫秒）。
     *
     * 真人是「看一眼题、写答案、再交卷」，不是秒交。pk-node 默认 8~12s，
     * 这里同值 —— 也让提交节奏错开服务端的瞬时窗口。
     */
    const val DEFAULT_SUBMIT_DELAY_MIN_MS = 8_000L
    const val DEFAULT_SUBMIT_DELAY_MAX_MS = 12_000L

    /**
     * 跑一轮 PK 战斗（多玩法并发，各自循环）。
     *
     * @param rounds               每个玩法要刷的轮数（≥1）
     * @param modes                要并发的玩法集合（勾选哪些跑哪些）
     * @param pointId              知识点 ID（PK 首页 pointList 提供，默认 1）
     * @param maxRetry             每轮非频控失败的重试上限（≥1）
     * @param retryBaseMs          非频控失败的重试基础退避毫秒（按 2^n 倍退避 + 抖动）
     * @param rateLimitBaseMs      **提交**命中频控时的退避基数（默认 [RATE_LIMIT_BASE_MS]）
     * @param matchRetryIntervalMs **出题**命中频控时的重试间隔（默认 [MATCH_RETRY_INTERVAL_MS]）
     * @param matchRetryMaxMs      **出题**重试的累计时间上限（默认 [MATCH_RETRY_MAX_MS]）
     * @param roundIntervalMs      每轮之间的固定间隔（毫秒，默认 0）
     * @param matchCooldownMs      出题冷却配速（毫秒，默认 [DEFAULT_MATCH_COOLDOWN_MS] = 0 = 不预防性等待）
     * @param costTimeMs           每局提交的整卷耗时；null = 由题数 × 下限推导
     * @param submitDelayMinMs     出题成功 → 提交 之间的间隔下界
     * @param submitDelayMaxMs     出题成功 → 提交 之间的间隔上界
     * @param strokeMode           笔迹算法
     * @param onProgress           (玩法, 已完成轮数, 总轮数, 事件文本)
     * @return 各玩法最终完成轮数（含失败导致的不足 rounds 的情况）
     *
     * ## 笔迹 seed（★ 2026-10-04 修正）
     *
     * 每轮生成一个**新的** `seedBase` 下发给 [PkBattleRepository.buildSubmitBody]，
     * 与 pk-node `seedBase: (Date.now() % 1e9) + attempt` 同义。
     * 修正前把 `seed = 题号` 写死 —— 同一知识点连刷 N 轮，N 轮的 `script` 逐点相同，
     * 服务端按「多局笔迹雷同」判机器作答。
     */
    suspend fun runBattle(
        rounds: Int,
        modes: Set<PkMode>,
        pointId: Int,
        maxRetry: Int = DEFAULT_MAX_RETRY,
        retryBaseMs: Long = DEFAULT_RETRY_BASE_MS,
        rateLimitBaseMs: Long = RATE_LIMIT_BASE_MS,
        matchRetryIntervalMs: Long = MATCH_RETRY_INTERVAL_MS,
        matchRetryMaxMs: Long = MATCH_RETRY_MAX_MS,
        roundIntervalMs: Long = DEFAULT_ROUND_INTERVAL_MS,
        matchCooldownMs: Long = DEFAULT_MATCH_COOLDOWN_MS,
        costTimeMs: Long? = null,
        submitDelayMinMs: Long = DEFAULT_SUBMIT_DELAY_MIN_MS,
        submitDelayMaxMs: Long = DEFAULT_SUBMIT_DELAY_MAX_MS,
        strokeMode: PkStrokeMode = PkStrokeMode.ARC,
        onProgress: (PkMode, Int, Int, String) -> Unit = { _, _, _, _ -> },
    ): Map<PkMode, Int> = coroutineScope {
        require(rounds >= 1) { "轮数必须 ≥1" }
        require(modes.isNotEmpty()) { "至少勾选一个玩法" }

        // 对齐 pk-node 的 ctx.lastMatchOkAt：**按账号**共享「上次成功出题的时刻」，
        // 多玩法并发时也共用同一份 —— 否则每个玩法各算各的，冷却配速互相不认账。
        // 用 AtomicLong 而不是普通 var：多个 async 会并发读写它。
        val lastMatchOkAt = java.util.concurrent.atomic.AtomicLong(0L)

        modes.associateWith { mode ->
            async {
                var done = 0
                for (round in 1..rounds) {
                    val ok = runOneRound(
                        mode = mode,
                        pointId = pointId,
                        maxRetry = maxRetry,
                        retryBaseMs = retryBaseMs,
                        rateLimitBaseMs = rateLimitBaseMs,
                        matchRetryIntervalMs = matchRetryIntervalMs,
                        matchRetryMaxMs = matchRetryMaxMs,
                        matchCooldownMs = matchCooldownMs,
                        lastMatchOkAt = lastMatchOkAt,
                        costTimeMs = costTimeMs,
                        submitDelayMinMs = submitDelayMinMs,
                        submitDelayMaxMs = submitDelayMaxMs,
                        strokeMode = strokeMode,
                        onEvent = { ev -> onProgress(mode, done, rounds, ev) },
                    )
                    if (ok) {
                        done++
                        onProgress(mode, done, rounds, "第 $round/$rounds 轮完成")
                    } else {
                        onProgress(mode, done, rounds, "第 $round/$rounds 轮失败（已达重试上限）")
                        // 失败不中断整个玩法循环，继续下一轮 —— 用户要的是「失败重试」
                        // 不是「失败即停」。
                    }
                    if (round < rounds && roundIntervalMs > 0) {
                        onProgress(mode, done, rounds, "轮间隔：${roundIntervalMs / 1000}s 后开始下一轮")
                        sleepWithTicks(roundIntervalMs) { leftMs ->
                            onProgress(mode, done, rounds, "距下一轮还有 ${ceilSeconds(leftMs)}s")
                        }
                    }
                }
                done
            }
        }.mapValues { (_, d) -> d.await() }
    }

    /**
     * 跑一局：出题 → 组装全对 body → 提交 → 结算核对。
     *
     * ## 三段等待，各自独立（2026-10-02 对齐 pk-node `runOneRound`）
     *
     *  1. **出题**：不再预防性等待。撞到 400/403 才按 [matchRetryIntervalMs] 重试，
     *     累计超 [matchRetryMaxMs] 判该轮失败；非频控错误立刻失败，不浪费时间。
     *  2. **答题间隔**：出题成功 → 提交之间睡 [submitDelayMinMs]~[submitDelayMaxMs]，
     *     让节奏像真人。
     *  3. **提交**：命中频控按 `rateLimitBaseMs × 2^n` 退避，最多 [RATE_LIMIT_MAX_WAIT] 次。
     *
     * 计数分开：频控等待不计入 [maxRetry]，否则「1 次 403 + 2 次普通重试」
     * 会在窗口还没过时就宣告整局失败。
     *
     * @param matchCooldownMs 出题冷却配速（0 = 关闭，见 [DEFAULT_MATCH_COOLDOWN_MS]）
     * @param lastMatchOkAt   **同账号**上次成功出题的时刻（毫秒；0 = 本账号还没成功过）
     * @return true = 本局成功（出题+提交+结算都成功）；false = 重试耗尽仍失败。
     */
    private suspend fun runOneRound(
        mode: PkMode,
        pointId: Int,
        maxRetry: Int,
        retryBaseMs: Long,
        rateLimitBaseMs: Long,
        matchRetryIntervalMs: Long,
        matchRetryMaxMs: Long,
        matchCooldownMs: Long,
        lastMatchOkAt: java.util.concurrent.atomic.AtomicLong,
        costTimeMs: Long?,
        submitDelayMinMs: Long,
        submitDelayMaxMs: Long,
        strokeMode: PkStrokeMode,
        onEvent: (String) -> Unit,
    ): Boolean {
        // ---------- 0) 出题冷却配速（对齐 pk-node `runOneRound` 第 1 步）----------
        //
        // 默认关闭（matchCooldownMs = 0）：不做任何预防性等待，完全按用户填的轮间隔走。
        // 开启后按「上次成功出题 + 冷却」配速 —— 既不白撞窗口，也不多等。
        if (matchCooldownMs > 0) {
            val last = lastMatchOkAt.get()
            if (last > 0) {
                val target = last + matchCooldownMs
                val wait = target - System.currentTimeMillis()
                if (wait > 0) {
                    onEvent("按出题冷却（${matchCooldownMs / 1000}s/账号）等 ${wait / 1000}s 后出题")
                    sleepWithTicks(wait) { leftMs ->
                        onEvent("距下轮出题还有 ${ceilSeconds(leftMs)}s")
                    }
                }
            }
        }

        // ---------- 1) 出题（撞频控自动重试，不再先睡 60s）----------
        onEvent("出题中… pointId=$pointId")
        val m = fetchMatchWithRetry(
            mode = mode,
            pointId = pointId,
            maxRetry = maxRetry,
            retryBaseMs = retryBaseMs,
            matchRetryIntervalMs = matchRetryIntervalMs,
            matchRetryMaxMs = matchRetryMaxMs,
            onEvent = onEvent,
        ) ?: return false

        // 记下这次成功时刻（**按账号**共享）→ 下一轮据此贴冷却下沿发车
        lastMatchOkAt.set(System.currentTimeMillis())

        // ---------- 2) 答题间隔（像真人：看题 → 写答案 → 交卷）----------
        val lo = minOf(submitDelayMinMs, submitDelayMaxMs).coerceAtLeast(0L)
        val hi = maxOf(submitDelayMinMs, submitDelayMaxMs).coerceAtLeast(0L)
        if (hi > 0) {
            val d = lo + Random.nextLong(0, (hi - lo + 1).coerceAtLeast(1))
            if (d > 0) {
                onEvent("答题间隔：${d / 1000}s 后提交")
                sleepWithTicks(d) { leftMs -> onEvent("距提交还有 ${ceilSeconds(leftMs)}s") }
            }
        }

        // ---------- 3) 组装 + 提交（含频控退避）----------
        var submitAttempt = 0
        var submitRateLimitWaits = 0
        while (true) {
            val body = try {
                PkBattleRepository.buildSubmitBody(m, costTimeMs, strokeMode)
            } catch (t: Throwable) {
                onEvent("组装提交体失败：${t.message ?: t}")
                return false
            }
            try {
                onEvent("提交中… 全对 ${body.correctCnt}/${body.questionCnt} costTime=${body.costTime}ms")
                PkBattleRepository.submit(mode, body)

                // ★ 提交 200 ≠ 已结算：必须再查一次结算明细，以服务端结算为准
                //   （提交被 403 的局，服务端同样留 {correctCnt:0, questions:null} 的占位记录）。
                val pkIdStr = body.pkIdStr
                val settle = if (pkIdStr.isBlank()) {
                    null
                } else {
                    onEvent("提交成功，核对结算…")
                    PkBattleRepository.fetchHistoryDetail(pkIdStr)
                }
                return when {
                    settle == null -> {
                        // 查询失败（网络/超时）：不能断言失败 —— 真机上可能只是历史还没落库。
                        onEvent("提交成功（结算明细未取到，无法确认是否计入）")
                        true
                    }
                    settle.settled -> {
                        onEvent("已结算：答对 ${settle.correctCnt}/${settle.questionCnt} 题")
                        true
                    }
                    else -> {
                        onEvent(
                            "⚠ 服务端未结算（correctCnt=${settle.correctCnt}, " +
                                "questions=${if (settle.questions == null) "null" else settle.questions.size}）" +
                                "—— 这局没算上"
                        )
                        false
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (rl: PkHttpException) {
                if (!rl.isRateLimited) {
                    onEvent("提交被拒（HTTP ${rl.code}）：${rl.body.take(120)}")
                    return false
                }
                if (submitRateLimitWaits >= RATE_LIMIT_MAX_WAIT) {
                    onEvent(
                        "提交频控未解除（HTTP ${rl.code}，已等待 $submitRateLimitWaits 次）—— 停止本局"
                    )
                    return false
                }
                submitRateLimitWaits++
                val wait = rateLimitBaseMs * (1L shl (submitRateLimitWaits - 1))
                onEvent(
                    "提交命中频控（HTTP ${rl.code}），等待 ${wait / 1000}s 后重试" +
                        "（第 $submitRateLimitWaits/$RATE_LIMIT_MAX_WAIT 次）"
                )
                sleepWithTicks(wait) { leftMs -> onEvent("退避中，还剩 ${ceilSeconds(leftMs)}s") }
            } catch (t: Throwable) {
                submitAttempt++
                if (submitAttempt >= maxRetry) {
                    onEvent("提交失败：${t.message ?: t}（已重试 $submitAttempt 次）")
                    return false
                }
                val backoff = retryBaseMs * (1L shl (submitAttempt - 1))
                val jitter = Random.nextLong(0, backoff.coerceAtLeast(1) + 1)
                onEvent("提交失败：${t.message ?: t}，第 $submitAttempt 次重试（${backoff + jitter}ms 后）")
                sleepWithTicks(backoff + jitter) { leftMs ->
                    onEvent("提交重试倒计时 ${ceilSeconds(leftMs)}s")
                }
            }
        }
    }

    /**
     * 出题 + **撞频控自动重试**（对齐 pk-node `runOneRound` 第 2 步）。
     *
     * 真机点「继续PK」是立刻请求的；撞上频控时真人会等一会儿再点，本函数把
     * 「等一会儿再点」自动化：
     *
     *  - 拿到 200 → 直接返回；
     *  - 撞频控（400 请求过于频繁 / 403）→ 报一次事件、睡 [matchRetryIntervalMs]
     *    再试，累计超 [matchRetryMaxMs] 才放弃；
     *  - 撞非频控错误 → **立刻失败**，不浪费时间（重试也不会变好）。
     *
     * 换到 `match/v2` + `611` 之后正常情况下**一次就通**，走不到重试分支
     * —— 这段只是兜底，取代了旧实现「每轮先睡 ≥60s」的预防性等待。
     *
     * @return 出题响应；null = 本局放弃
     */
    private suspend fun fetchMatchWithRetry(
        mode: PkMode,
        pointId: Int,
        maxRetry: Int,
        retryBaseMs: Long,
        matchRetryIntervalMs: Long,
        matchRetryMaxMs: Long,
        onEvent: (String) -> Unit,
    ): PkMatchResponse? {
        var attempt = 0
        var rateLimitWaits = 0
        val matchStart = System.currentTimeMillis()
        while (true) {
            try {
                val m = PkBattleRepository.fetchMatch(mode, pointId)
                onEvent("出题成功（${m.examVO?.questions?.size ?: 0} 题，第 ${attempt + rateLimitWaits + 1} 次尝试）")
                return m
            } catch (c: CancellationException) {
                throw c
            } catch (rl: PkHttpException) {
                if (!rl.isRateLimited) {
                    onEvent("出题被拒（HTTP ${rl.code}）：${rl.body.take(120)}")
                    return null
                }
                val waited = System.currentTimeMillis() - matchStart
                if (waited >= matchRetryMaxMs) {
                    onEvent(
                        "出题持续频控（HTTP ${rl.code}，已重试 ${rateLimitWaits + 1} 次 / " +
                            "${waited / 1000}s 仍未放行）—— 本轮放弃"
                    )
                    return null
                }
                rateLimitWaits++
                onEvent(
                    "出题被频控（HTTP ${rl.code}），${matchRetryIntervalMs / 1000}s 后自动重试" +
                        "（已等 ${waited / 1000}s）"
                )
                sleepWithTicks(matchRetryIntervalMs) { leftMs ->
                    onEvent("出题重试倒计时 ${ceilSeconds(leftMs)}s")
                }
            } catch (t: Throwable) {
                attempt++
                if (attempt >= maxRetry) {
                    onEvent("出题失败：${t.message ?: t}（已重试 $attempt 次）")
                    return null
                }
                val backoff = retryBaseMs * (1L shl (attempt - 1))
                val jitter = Random.nextLong(0, backoff.coerceAtLeast(1) + 1)
                onEvent("出题失败：${t.message ?: t}，第 $attempt 次重试（${backoff + jitter}ms 后）")
                sleepWithTicks(backoff + jitter) { leftMs -> onEvent("出题重试倒计时 ${ceilSeconds(leftMs)}s") }
            }
        }
    }

    /**
     * 带「滴答」的可中断长睡眠（对齐 pk-node `sleepWithTicks`）。
     *
     * ## 为什么不能直接 `delay(ms)`
     *
     * 轮间隔、答题间隔、频控退避动辄几十秒。直接 `delay` 的话这段时间里
     * **界面上一个事件都没有** —— 用户看到的是卡死（这是 2026-10-02 排查
     * 「一秒就结束」与后续「页面像挂了」时反复踩到的观感问题）。
     *
     * 这里每 [TICK_INTERVAL_MS] 回调一次剩余时间，既给界面心跳，
     * 又保留 `delay` 的**可取消性**（协作式取消在每次 `delay` 处生效，
     * 所以「停止」依然能立刻打断这段等待）。
     *
     * @param totalMs 总时长（≤0 直接返回）
     * @param onTick  每 5 秒回调一次剩余毫秒；**最后一段不回调**（说「还剩 0s」没有意义）
     */
    private suspend fun sleepWithTicks(
        totalMs: Long,
        onTick: (Long) -> Unit = {},
    ) {
        var left = totalMs.coerceAtLeast(0L)
        while (left > 0) {
            val step = minOf(TICK_INTERVAL_MS, left)
            delay(step)
            left -= step
            if (left <= 0) break
            onTick(left)
        }
    }

    /** 剩余毫秒 → 向上取整的秒（日志文案用，避免出现「还剩 0s」）。 */
    private fun ceilSeconds(ms: Long): Long = (ms + 999L) / 1000L
}
