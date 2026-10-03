package cn.apixiaoyuan.app.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.apixiaoyuan.app.core.design.component.AppScaffold
import cn.apixiaoyuan.app.core.design.component.LocalScrollBottomLimit
import cn.apixiaoyuan.app.core.design.component.LocalTopBarInset
import cn.apixiaoyuan.app.core.navigation.AppNavController
import cn.apixiaoyuan.app.core.pk.host.TerminalSession
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 内置 Linux 终端（★ 2026-10-03，用户要求「主页加入 linux 终端入口」）。
 *
 * # 它跑的是什么
 *
 * `/system/bin/sh -i` —— **Android 自带的 toybox/mksh**，不是塞进去的发行版。
 * 详见 [TerminalSession] 的 KDoc（为什么不需要 Ubuntu rootfs）。
 *
 * 环境变量与 [cn.apixiaoyuan.app.core.pk.host.NodeRuntime] 对齐，
 * 所以这里能直接手搓服务来排障：
 *
 * ```sh
 * cd $PK_NODE_DIR
 * PK_HOST=127.0.0.1 PK_PORT=9999 PK_SKIP_NATIVE=1 \
 *   $PK_NODE_BIN server.js
 * ```
 *
 * # 交互设计（刻意做得最小）
 *
 * - 输出区：等宽字体 + 深色底（像终端），自动跟随滚动；
 * - 输入框：**回车即执行**（`ImeAction.Send` / `Done`），执行后清空；
 * - 顶部：状态（运行中 / 已停止）、清空、重启。
 *
 * # ⚠️ 已知取舍（如实说明）
 *
 * - **不是完整 PTY**：用的是管道而非伪终端，所以 `top` 这类需要 `ioctl(TIOCGWINSZ)`
 *   的全屏交互程序跑不了；`ls` / `cat` / `ps` / `netstat` / `node` 这些**都没问题**。
 * - **不支持 `su`**：App 没有 root，`su` 会失败（这本来就是预期行为）。
 * - 输出按**行**追加：`printf` 不带换行的进度条会等到换行才显示。
 */
@Composable
fun TerminalScreen(navController: AppNavController) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // 进页面就确保终端起来（幂等）。startAsync 内部在 IO 线程，不会卡 UI。
    DisposableEffect(Unit) {
        TerminalSession.startAsync(context)
        onDispose { /* 刻意不停终端：退出页面后仍保留会话状态（cd、环境变量）*/ }
    }

    var input by remember { mutableStateOf("") }

    // ★ 轮询输出：TerminalSession 是普通对象（不是 Flow），
    //   用 revision 当版本戳，变了才重新取快照 —— 避免每帧都复制 2000 行。
    var lines by remember { mutableStateOf(TerminalSession.snapshot()) }
    var lastRevision by remember { mutableStateOf(TerminalSession.revision) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(300)
            val rev = TerminalSession.revision
            if (rev != lastRevision) {
                lastRevision = rev
                lines = TerminalSession.snapshot()
            }
        }
    }

    // 自动跟随：只在**贴底**时跟随（用户上滑查看历史时不打断）。
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && listState.firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size >= lines.size - 2) {
            listState.animateScrollToItem(lines.lastIndex)
        }
    }

    AppScaffold(title = "终端", onBack = { navController.popBackStack() }) { pad ->
        val topInset = LocalTopBarInset.current
        val scrollLimit = LocalScrollBottomLimit.current
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏占位（与日志页同款：只留一条 Spacer）
            Spacer(Modifier.height(topInset))

            // ---- 工具条 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (TerminalSession.isRunning) "● 运行中" else "○ 已停止",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (TerminalSession.isRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { TerminalSession.clear() }) { Text("清空") }
                TextButton(onClick = {
                    TerminalSession.stop()
                    TerminalSession.startAsync(context)
                }) { Text("重启") }
            }

            // ---- 输出 ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101014)),
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = pad.calculateBottomPadding() + scrollLimit + 8.dp,
                        start = 10.dp,
                        end = 10.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            ),
                            color = colorFor(line),
                        )
                    }
                }
            }

            // ---- 输入 ----
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .heightIn(min = 48.dp),
                label = "输入命令后回车执行（如 ls -l \$PK_NODE_DIR）",
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val cmd = input.trim()
                    if (cmd.isNotEmpty()) {
                        if (!TerminalSession.run(cmd)) {
                            // 终端没起来 —— 提示而不是静默失败
                            TerminalSession.startAsync(context)
                        }
                        input = ""
                    }
                }),
            )
        }
    }
}

/** 按行内容着色：命令回显亮一点、错误红、提示青、普通灰白。 */
private fun colorFor(line: String): Color = when {
    line.startsWith("$ ") -> Color(0xFF7FD1FF)          // 自己敲的命令
    line.startsWith("[") -> Color(0xFF8FE388)           // 本类的提示行
    line.contains("error", true) ||
        line.contains("failed", true) ||
        line.contains("denied", true) -> Color(0xFFFF8A80)
    else -> Color(0xFFD6D6DC)
}