package cn.apixiaoyuan.app.feature.pk

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
import androidx.compose.runtime.DisposableEffect
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
// ★ 2026-10-03：`LocalLifecycleOwner` 已不再需要 —— 用它的是「后台挂机」卡片，
//   那张卡片已搬到「功能」tab（`OldSimianScreen.HostingSection`）。
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.apixiaoyuan.app.core.account.SubAccountItem
import cn.apixiaoyuan.app.core.design.component.AppScrollScaffold
import cn.apixiaoyuan.app.core.design.component.AutoFollowScroll
import cn.apixiaoyuan.app.core.navigation.AppNavController
import cn.apixiaoyuan.app.core.pk.PkStrokeMode
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PkGrindScreen(
    navController: AppNavController,
    viewModel: PkGrindViewModel = viewModel(),
) {
    var roundsText by remember { mutableStateOf("1") }
    var costTimeText by remember { mutableStateOf("") }
    // 出题成功 → 提交 的间隔区间。默认 8~12s（对齐 pk-node：真人要看题、写答案再交卷）。
    var submitDelayMinText by remember { mutableStateOf("8000") }
    var submitDelayMaxText by remember { mutableStateOf("12000") }
    // 循环间隔：默认 0 —— **不再预防性等 60 秒**（见页面底部「说明」）。
    var roundIntervalText by remember { mutableStateOf("0") }
    var selectedPointId by remember { mutableStateOf(0) }
    var strokeMode by remember { mutableStateOf(PkStrokeMode.ARC) }
    // 提交频控退避基数（毫秒）。默认 10s（pk-node 实测：60s 是拍脑袋，10s×2 就够）。
    var rateLimitWaitText by remember { mutableStateOf("10000") }
    // 出题撞 400/403 时的自动重试：间隔 / 累计上限。
    var matchRetryIntervalText by remember { mutableStateOf("10000") }
    var matchRetryMaxText by remember { mutableStateOf("120000") }

    val rounds = roundsText.toIntOrNull() ?: 0
    val costTimeMs = costTimeText.toLongOrNull()
    val submitDelayMinMs = submitDelayMinText.toLongOrNull() ?: 0L
    val submitDelayMaxMs = submitDelayMaxText.toLongOrNull() ?: 0L
    val roundIntervalMs = roundIntervalText.toLongOrNull() ?: 0L
    val rateLimitWaitMs = rateLimitWaitText.toLongOrNull() ?: 10_000L
    val matchRetryIntervalMs = matchRetryIntervalText.toLongOrNull() ?: 10_000L
    val matchRetryMaxMs = matchRetryMaxText.toLongOrNull() ?: 120_000L

    AppScrollScaffold(title = "刷 PK 对局", onBack = { navController.popBackStack() }) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "账号") {
                Text(
                    text = "当前 userid：${viewModel.currentUserId ?: "未登录"}",
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
                val subs = viewModel.subAccounts
                viewModel.subAccountsError?.let { err ->
                    Text(
                        text = err,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                if (subs.isNullOrEmpty() && viewModel.subAccountsError == null) {
                    Text(
                        text = "无子账号",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
                if (!subs.isNullOrEmpty()) {
                    subs.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.switchSubAccount(item) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.nickname +
                                    (if (item.isCurrent) "（当前）" else "") +
                                    (if (item.isPrimary) " · 主账号" else ""),
                                color = if (item.isCurrent) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurfaceContainer,
                                modifier = Modifier.weight(1f),
                            )
                            Text(text = item.userId.toString(), color = MiuixTheme.colorScheme.onSurfaceContainerVariant)
                        }
                    }
                }
            }

            SectionCard(title = "分数") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "总胜场 ${viewModel.totalWinCount ?: "—"} · 本周 ${viewModel.weekWinCount ?: "—"}",
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { viewModel.refreshAll() }) { Text("刷新") }
                }
            }

            SectionCard(title = "对局类型") {
                val points = viewModel.points
                when {
                    viewModel.loading -> Text("加载中…", color = MiuixTheme.colorScheme.onSurfaceContainerVariant)
                    viewModel.loadError != null -> Text(
                        text = viewModel.loadError ?: "",
                        color = MiuixTheme.colorScheme.error,
                    )
                    points.isNullOrEmpty() -> Text(
                        text = "无可用对局类型",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                    else -> points.forEach { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedPointId = p.pointId }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = (if (selectedPointId == p.pointId) "✓ " else "") +
                                    (p.pointName ?: "知识点 ${p.pointId}"),
                                color = if (selectedPointId == p.pointId) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurfaceContainer,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "胜 ${p.winCount}",
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            )
                        }
                    }
                }
            }

            SectionCard(title = "参数") {
                NumberField(
                    title = "对局数",
                    value = roundsText,
                    placeholder = "刷几局",
                    onValueChange = { roundsText = it.filter(Char::isDigit) },
                )
                NumberField(
                    title = "costTime（毫秒，留空=自动）",
                    value = costTimeText,
                    placeholder = "如 100，留空自动",
                    onValueChange = { costTimeText = it.filter(Char::isDigit) },
                )
                NumberField(
                    title = "出题后提交间隔 · 下界（毫秒）",
                    value = submitDelayMinText,
                    placeholder = "如 8000",
                    onValueChange = { submitDelayMinText = it.filter(Char::isDigit) },
                )
                NumberField(
                    title = "出题后提交间隔 · 上界（毫秒）",
                    value = submitDelayMaxText,
                    placeholder = "如 12000",
                    onValueChange = { submitDelayMaxText = it.filter(Char::isDigit) },
                )
                NumberField(
                    title = "循环间隔（毫秒，默认 0 = 不等）",
                    value = roundIntervalText,
                    placeholder = "每局之间，如 0",
                    onValueChange = { roundIntervalText = it.filter(Char::isDigit) },
                )
                NumberField(
                    title = "提交频控退避（毫秒，默认 10000）",
                    value = rateLimitWaitText,
                    placeholder = "提交命中 403/429 后等待多久再试",
                    onValueChange = { rateLimitWaitText = it.filter(Char::isDigit) },
                )
                NumberField(
                    title = "出题频控重试间隔（毫秒，默认 10000）",
                    value = matchRetryIntervalText,
                    placeholder = "出题撞 400/403 后隔多久再试",
                    onValueChange = { matchRetryIntervalText = it.filter(Char::isDigit) },
                )
                NumberField(
                    title = "出题最长等待（毫秒，默认 120000）",
                    value = matchRetryMaxText,
                    placeholder = "累计超此时长才判该轮失败",
                    onValueChange = { matchRetryMaxText = it.filter(Char::isDigit) },
                )
                Text(
                    text = "画笔算法",
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
                PkStrokeMode.entries.forEach { m ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { strokeMode = m }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = (if (strokeMode == m) "✓ " else "") + m.displayName,
                            color = if (strokeMode == m) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                    }
                }
            }

            SectionCard(title = "运行") {
                Button(
                    onClick = {
                        if (viewModel.running) {
                            viewModel.stop()
                        } else {
                            viewModel.start(
                                rounds = rounds,
                                pointId = selectedPointId,
                                costTimeMs = costTimeMs,
                                submitDelayMinMs = submitDelayMinMs,
                                submitDelayMaxMs = submitDelayMaxMs,
                                roundIntervalMs = roundIntervalMs,
                                rateLimitWaitMs = rateLimitWaitMs,
                                matchRetryIntervalMs = matchRetryIntervalMs,
                                matchRetryMaxMs = matchRetryMaxMs,
                                strokeMode = strokeMode,
                            )
                        }
                    },
                    enabled = viewModel.running || (rounds > 0 && selectedPointId > 0),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (viewModel.running) "停止" else "开始 API 刷局")
                }
                if (viewModel.progress.isNotEmpty()) {
                    Text(text = viewModel.progress, color = MiuixTheme.colorScheme.primary)
                }
                viewModel.message?.let {
                    Text(text = it, color = MiuixTheme.colorScheme.onSurfaceContainer)
                }
            }

            // ★ 2026-10-03：这里原来有「后台挂机（悬浮球保活）」卡片 —— 已**搬到
            // 「功能」tab**（`OldSimianScreen.HostingSection`）。
            //
            // 用户原话：「后台挂机在练习应该也同样适用才对，把后台悬浮窗的开关做进
            // tab 栏功能里而不是放到刷分」。保活是全局能力（与 PK 无关），
            // 放在刷 PK 对局页会让「练习挂机」的人找不到。
            //
            // 删掉这里而不是两处都留：同一个开关出现在两个页面会让人怀疑
            // 「是不是两份配置」。`PkAutoHostService` 本身没动，功能完全一样。

            // ★ 2026-10-03：运行日志（用户要求「pk 刷局加个日志显示就和刷练习一样」）。
            //
            // 与 `ExercisePumpScreen` 的日志区**同款**：
            //   - 同一个 [cn.apixiaoyuan.app.core.design.component.AutoFollowScroll]
            //     （到底跟随 / 上滑即停 / 回底恢复 / 全程动画）；
            //   - 等宽小字号、min 160dp / max 360dp、内容在 ViewModel 里**批量**裁剪。
            SectionCard(title = "运行日志") {
                val logScroll = rememberScrollState()
                AutoFollowScroll(state = logScroll, itemCount = viewModel.logs.size)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 360.dp)
                        .verticalScroll(logScroll)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (viewModel.logs.isEmpty()) {
                        Text(
                            text = "（暂无 —— 点「开始 API 刷局」后这里会逐条显示出题/提交/结算过程）",
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            fontSize = 11.sp,
                        )
                    } else {
                        viewModel.logs.forEach { line ->
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
                        text = "说明",
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                    )
                    Text(
                        text = "纯 API 刷局：出题（match/v2，加密响应，本地解包）→ 弧线笔迹组装 body → " +
                            "gzip+原生加密 → sign → 提交 → 结算核对。",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                    Text(
                        text = "★ 2026-10-02 起**不再预防性等 60 秒**：旧协议（631 + 明文 match）" +
                            "被服务端按频控处理，才表现为「每账号 60 秒一局」。换到 611 + match/v2 " +
                            "后出题立刻就通，循环间隔默认 0；真撞到 400/403 才按「出题频控重试间隔」" +
                            "自动重试，累计超「出题最长等待」才判该轮失败。",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun NumberField(
    title: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, color = MiuixTheme.colorScheme.onSurfaceContainer)
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = placeholder,
            useLabelAsPlaceholder = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
    }
}
