package cn.apixiaoyuan.app.feature.grind

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.apixiaoyuan.app.core.design.component.AppScrollScaffold
import cn.apixiaoyuan.app.core.design.component.AutoFollowScroll
import cn.apixiaoyuan.app.core.design.glass.kyant.LiquidToggle
import cn.apixiaoyuan.app.core.design.glass.kyant.rememberPageBackdropOrFallback
import cn.apixiaoyuan.app.core.navigation.AppNavController
import cn.apixiaoyuan.app.core.pk.host.PkHostOrchestrator
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「比赛竞速」页（★ 2026-10-05，用户要求「移植进 app 的刷分→比赛竞速」）。
 *
 * ## 定位
 *
 * 刷分区第三条竞速链路：**开学季竞速**（`2026autumnRace`）。
 * 与 PK 刷局 / 练习刷对局不同，它跑的是**官方活动**的全流程
 * （活动主页 → 8 人匹配 → WebSocket 对战 → 逐题作答 → 结算 → 榜单）。
 *
 * 「内容对齐 pk-node」的实现方式：**App 是内置 pk-node 的客户端** ——
 * 本页不重写任何竞速协议，全部动作转发给内置服务的 `/api/race/` 系列接口
 * （与 pk-node 自己的网页打同一套接口），日志经 `/api/race/stream`（SSE）
 * 原样搬进来。协议（WS v2 / sign / 贴限 / 跨局自调）只在 pk-node 维护一份。
 *
 * ## 界面结构（对齐 pk-node 的「比赛竞速」tab）
 *
 * | 区域 | 对应 pk-node 控件 |
 * |---|---|
 * | 账号 / 子账号 | `race-leo` + 子账号切换（pk-node v1.5+ 的「切换子账号」） |
 * | 活动 / 知识点 | `race-home` + `race-point` 下拉 |
 * | 提交时间 / 贴限模式 | `race-delaymin/max` + `race-aim`/`race-aimsafety` |
 * | 排行榜 | `race-rank-load`（全国榜） |
 * | 运行 / 日志 | `race-run`/`race-stop` + `race-runlog`（SSE） |
 *
 * ## 日志区（用户要求「日志和练习一样」）
 *
 * 与 [cn.apixiaoyuan.app.feature.exercise.ExercisePumpScreen] **同款**：
 * [AutoFollowScroll]（到底跟随 / 上滑即停 / 回底恢复 / 全程动画）
 * + 等宽 11sp + min 160dp / max 360dp + 批量裁剪（ViewModel 侧）。
 */
@Composable
fun RaceScreen(
    navController: AppNavController,
    vm: RaceViewModel = viewModel(),
) {
    val context = LocalContext.current

    // 进页面即确保内置服务在跑（幂等；App 启动时已预热过）。
    LaunchedEffect(Unit) {
        PkHostOrchestrator.startAsync(context)
    }

    val hostState = PkHostOrchestrator.state
    // 服务就绪后：刷新账号列表（拿 adminCredentials 登录会话）+ 挂回在跑的任务。
    LaunchedEffect(hostState) {
        if (hostState is PkHostOrchestrator.State.Ready) {
            vm.onHostReady()
            vm.resumeIfRunning()
        }
    }

    AppScrollScaffold(title = "比赛竞速", onBack = { navController.popBackStack() }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

            Text(
                text = "比赛竞速（开学季）",
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = "官方活动全流程：活动主页 → 8 人匹配 → WebSocket 对战 → 逐题作答 → 结算 → 榜单。" +
                    "由内置 pk-node 驱动（与网页端同一套实现）。",
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )

            // ---- 内置服务未就绪时：显示提示（不白屏）----
            when (hostState) {
                PkHostOrchestrator.State.Idle,
                PkHostOrchestrator.State.Starting -> {
                    SectionCard(title = "内置服务") {
                        Text(
                            text = "正在启动内置服务（首次会解压工作区）…",
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                }
                is PkHostOrchestrator.State.Failed -> {
                    SectionCard(title = "内置服务") {
                        Text(
                            text = "启动失败：${hostState.message}",
                            color = MiuixTheme.colorScheme.error,
                        )
                        Button(onClick = {
                            PkHostOrchestrator.stop()
                            PkHostOrchestrator.startAsync(context)
                        }) { Text("重试") }
                    }
                }
                is PkHostOrchestrator.State.Ready -> Unit // 正常显示下面的内容
            }

            // ---- 账号 / 子账号（对齐 pk-node「小猿账号 + 子账号切换」）----
            SectionCard(title = "账号") {
                if (vm.accounts.isEmpty()) {
                    Text(
                        text = "没有可用小猿账号 —— 先在功能页导入登录态（会自动同步给内置服务）。",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                } else {
                    vm.accounts.forEach { a ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.selectAccount(a.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = (if (vm.selectedAccountId == a.id) "✓ " else "") +
                                    (a.name ?: "账号 ${a.id}") +
                                    (if (a.yfdU != null) "（uid ${a.yfdU}）" else ""),
                                color = if (vm.selectedAccountId == a.id) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceContainer
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // 子账号下拉：点击行即切换（走 pk-node 的 switch，与练习页同机制）。
                if (vm.subAccounts.isNotEmpty()) {
                    Text(
                        text = "子账号（点行切换）",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                    val curYfdU = vm.accounts.firstOrNull { it.id == vm.selectedAccountId }?.yfdU
                    vm.subAccounts.forEach { s ->
                        val isCur = s.isCurrent || (curYfdU != null && s.userId == curYfdU)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !vm.switching && !vm.running) {
                                    if (!isCur) vm.switchSub(s.userId)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = (s.nickname ?: "账号 ${s.userId}") +
                                    (if (s.isPrimary) "（主）" else "") +
                                    (if (isCur) " ← 当前" else ""),
                                color = if (isCur) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurfaceContainer,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = s.userId.toString(),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            )
                        }
                    }
                } else if (vm.subStatus.isNotBlank()) {
                    Text(
                        text = vm.subStatus,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
            }

            // ---- 活动 / 知识点（对齐 pk-node「获取知识点」）----
            SectionCard(title = "活动 / 知识点") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { vm.loadHome() },
                        enabled = !vm.running && vm.selectedAccountId > 0,
                    ) { Text("获取知识点") }
                    Button(
                        onClick = { vm.loadRank() },
                        enabled = !vm.running && vm.selectedPointId > 0,
                    ) { Text("拉取榜单") }
                }
                Text(
                    text = vm.activityStatus,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
                if (vm.points.isNotEmpty()) {
                    vm.points.forEach { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.selectPoint(p.pointId) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = (if (vm.selectedPointId == p.pointId) "✓ " else "") +
                                    (p.pointName ?: "知识点 ${p.pointId}") +
                                    "（${p.questionCnt} 题）",
                                color = if (vm.selectedPointId == p.pointId) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceContainer
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (vm.rankText.isNotBlank() && !vm.rankText.startsWith("（")) {
                    Text(
                        text = vm.rankText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                    )
                }
            }

            // ---- 贴限模式（核心：自动贴该榜下限抢名次）----
            SectionCard(title = "成绩模式") {
                SwitchRow(
                    title = "贴限模式（抢榜推荐）",
                    summary = "每个榜有「上榜下限」（低于它判异常不上榜、各榜不同）。" +
                        "自动读该榜榜一 → 目标 = 榜一 + 安全边距 → 反推每题延迟提交；" +
                        "未上榜自动加大边距、上榜后自动收紧逼近。",
                    checked = vm.aimCostMode,
                    onCheckedChange = { vm.aimCostMode = it },
                )
                NumberField(
                    label = "安全边距 ms（贴限模式用，默认 40）",
                    value = vm.aimSafetyMs,
                    enabled = !vm.running && vm.aimCostMode,
                    onCommit = { vm.aimSafetyMs = it.toString() },
                )
            }

            // ---- 参数（对齐 pk-node 高级参数区）----
            SectionCard(title = "参数") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NumberField(
                        label = "提交时间最小 ms" + if (vm.aimCostMode) "（贴限时自动）" else "",
                        value = vm.delayMinMs,
                        enabled = !vm.running && !vm.aimCostMode,
                        modifier = Modifier.weight(1f),
                        onCommit = { vm.delayMinMs = it.toString() },
                    )
                    NumberField(
                        label = "提交时间最大 ms" + if (vm.aimCostMode) "（贴限时自动）" else "",
                        value = vm.delayMaxMs,
                        enabled = !vm.running && !vm.aimCostMode,
                        modifier = Modifier.weight(1f),
                        onCommit = { vm.delayMaxMs = it.toString() },
                    )
                }
                Text(
                    text = "贴限模式下「提交时间」由服务端自动计算；手动模式下直接控制每题延迟。" +
                        "注意：秒答（0/0）因低于所有榜的下限会被判异常不上榜。",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NumberField(
                        label = "每局题数（0=用知识点默认）",
                        value = vm.questionCount,
                        enabled = !vm.running,
                        modifier = Modifier.weight(1f),
                        onCommit = { vm.questionCount = it.toString() },
                    )
                    NumberField(
                        label = "刷多少局",
                        value = vm.rounds,
                        enabled = !vm.running,
                        modifier = Modifier.weight(1f),
                        onCommit = { vm.rounds = it.coerceAtLeast(1).toString() },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NumberField(
                        label = "局间最小间隔 ms",
                        value = vm.gapMinMs,
                        enabled = !vm.running,
                        modifier = Modifier.weight(1f),
                        onCommit = { vm.gapMinMs = it.toString() },
                    )
                    NumberField(
                        label = "局间最大间隔 ms",
                        value = vm.gapMaxMs,
                        enabled = !vm.running,
                        modifier = Modifier.weight(1f),
                        onCommit = { vm.gapMaxMs = it.toString() },
                    )
                }
                NumberField(
                    label = "单局超时 ms（默认 300000）",
                    value = vm.battleMaxMs,
                    enabled = !vm.running,
                    onCommit = { vm.battleMaxMs = it.toString() },
                )
            }

            // ---- 运行 ----
            SectionCard(title = "运行") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { vm.start() },
                        enabled = !vm.running && vm.selectedAccountId > 0,
                    ) { Text("开始竞速") }
                    Button(
                        onClick = { vm.stop() },
                        enabled = vm.running && vm.currentJobId > 0,
                    ) { Text("停止") }
                    Text(
                        text = vm.status,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (vm.currentJobId > 0) {
                    Text(
                        text = "任务 #${vm.currentJobId}（切页/关浏览器后由服务端继续跑，可在 pk-node 网页任务页查看）",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
            }

            // ---- 运行日志（★ 与练习页同款：AutoFollowScroll + 等宽 11sp）----
            SectionCard(title = "运行日志") {
                val logScroll = rememberScrollState()
                AutoFollowScroll(state = logScroll, itemCount = vm.logs.size)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 360.dp)
                        .verticalScroll(logScroll)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (vm.logs.isEmpty()) {
                        Text(
                            text = "（暂无 —— 点「开始竞速」后这里会逐条显示匹配/答题/结算过程）",
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            fontSize = 11.sp,
                        )
                    } else {
                        vm.logs.forEach { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceContainer,
                            )
                        }
                    }
                }
            }

            // ---- 说明（把实测结论写进界面，避免用户误判）----
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "实测结论（来自 pk-node）",
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                    )
                    Text(
                        text = "· 上榜下限：每个榜不同（实测 2035=4900 / 2037·2038·2039=5600 / " +
                            "2036=7000ms），恰为该榜榜一值；低于它判异常不上榜（rank=999）。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                    Text(
                        text = "· 服务端按物理时间计时（帧时间戳无法干预，已实验证伪）；" +
                            "「抢榜」= 精确贴着下限提交，贴限 4900ms 可直接 rank=1。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                    Text(
                        text = "· 竞速任务是正经后台任务：切页/关浏览器都由服务端继续跑。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
            }
        }
    }
}

/** 分组卡片（与练习页 / 刷 PK 页同风格）。 */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.surfaceContainer,
                contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}

/** 开关行（液态玻璃开关，跟随莫奈语义色 —— 与刷分区其它开关一致）。 */
@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, color = MiuixTheme.colorScheme.onSurfaceContainer)
            Text(text = summary, color = MiuixTheme.colorScheme.onSurfaceContainerVariant)
        }
        val rowBackdrop = rememberPageBackdropOrFallback(MiuixTheme.colorScheme.surface)
        LiquidToggle(
            selected = { checked },
            onSelect = onCheckedChange,
            backdrop = rowBackdrop,
        )
    }
}

/** 数字输入行（提交即回写；只收数字）。 */
@Composable
private fun NumberField(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onCommit: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )
        TextField(
            value = text,
            onValueChange = { v ->
                text = v.filter { it.isDigit() }
                text.toIntOrNull()?.let(onCommit)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}