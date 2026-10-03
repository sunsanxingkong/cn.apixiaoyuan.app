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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.apixiaoyuan.app.core.design.component.AppScaffold
import cn.apixiaoyuan.app.core.design.component.AutoFollowScrollLazy
import cn.apixiaoyuan.app.core.design.component.LocalScrollBottomLimit
import cn.apixiaoyuan.app.core.design.component.LocalTopBarInset
import cn.apixiaoyuan.app.core.navigation.AppNavController
import cn.apixiaoyuan.app.core.pk.host.TerminalManager
import cn.apixiaoyuan.app.core.pk.host.TerminalSession
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 内置 Linux 终端（★ 2026-10-03；同日按用户反馈重做界面）。
 *
 * # 它跑的是什么
 *
 * `/system/bin/sh -i` —— **Android 自带的 toybox/mksh**，不是塞进去的发行版。
 * 详见 [TerminalSession] 的 KDoc（为什么不需要 Ubuntu rootfs）。
 *
 * 环境变量与 `NodeRuntime` 对齐，所以这里能直接手搓服务来排障：
 *
 * ```sh
 * cd $PK_NODE_DIR
 * PK_HOST=127.0.0.1 PK_PORT=9999 PK_SKIP_NATIVE=1 $PK_NODE_BIN server.js
 * ```
 *
 * # ★ 本轮界面重做（用户四条要求）
 *
 * > 「工具栏和 termux 看齐，但是 ui 用 miuix 的控件，
 * >   接着输入动画和日志动画一样，接着背景用莫奈取色」
 *
 * | 要求 | 落地 |
 * |---|---|
 * | 工具栏像 Termux | **会话 chip 条**（`+` 新建 / 点击切换 / `×` 关闭）+ 状态点 + `^C`（Ctrl+C） |
 * | UI 用 miuix 控件 | `Button` / `TextField` / `Text` 全部来自 `top.yukonga.miuix.kmp.basic` |
 * | 输入动画和日志一样 | **复用同一个 [AutoFollowScrollLazy]**（到底跟随 / 上滑暂停 / 回底恢复 / 弹簧丝滑） |
 * | 背景用莫奈取色 | 底色 = `MiuixTheme.colorScheme.surfaceContainer`，行/工具条用 `surfaceContainerHigh`；**不再硬编码** `0xFF101014` |
 *
 * # 关于「背景用莫奈取色」的一个取舍（如实说明）
 *
 * 终端输出区的传统做法是**恒深色底**（Termux 默认也是）。但如果直接铺
 * `MiuixTheme.colorScheme.surfaceContainer`，浅色主题下会变成浅底 + 深字，
 * 而下面 [colorFor] 的语义配色（命令青、错误红）是按深底调的，浅底上会糊。
 *
 * 所以这里**不硬编码颜色、而是全部取自莫奈色板**，并让「明暗」跟着主题走：
 * 深色主题 → `surfaceContainer` 偏深（观感接近 Termux）；
 * 浅色主题 → 偏浅，同时行文字统一用 `onSurfaceContainer` 保证对比度。
 * 语义色（命令/错误/提示）仍用**莫奈的 primary / error / tertiary**，
 * 这样不管明暗都能看清，也不会出现「一半硬编码、一半主题色」的割裂。
 *
 * # ⚠️ 已知取舍（如实说明）
 *
 * - **不是完整 PTY**：用的是管道而非伪终端，所以 `top` 这类需要
 *   `ioctl(TIOCGWINSZ)` 的全屏交互程序跑不了；`ls` / `cat` / `ps` / `netstat` /
 *   `node` 这些**都没问题**。
 * - **不支持 `su`**：App 没有 root，`su` 会失败（这本来就是预期行为）。
 * - 输出按**行**追加：`printf` 不带换行的进度条会等到换行才显示。
 * - **`^C` 是「近似 Ctrl+C」**：给 sh 的所有子进程发 SIGINT（Android 上读不到
 *   `/proc/.../children`，改用 `ps -o PID,PPID` 过滤，详见 [TerminalSession]）。
 */
@Composable
fun TerminalScreen(navController: AppNavController) {
    val context = LocalContext.current

    // 进页面就确保至少有一个终端（幂等）。
    //
    // ★ 放在 `remember` 而不是 `DisposableEffect`：DisposableEffect 是在**提交之后**
    //   才跑的，那样首帧会先渲染一次「没有可用的终端会话」、再被 200ms 轮询纠正 ——
    //   肉眼能看到闪一下。这里同步把会话对象建好（真正 fork 进程的 `startAsync`
    //   仍在 IO 线程），首帧就是对的。
    //
    // 退出页面**刻意不停终端**：cd、环境变量这些会话状态要保留。
    remember { TerminalManager.ensureStarted(context) }

    // ---- 会话列表（靠 listRevision 轮询感知，与日志页同一套路）----
    var listRev by remember { mutableIntStateOf(TerminalManager.listRevision) }
    var activeId by remember { mutableIntStateOf(TerminalManager.active()?.id ?: 0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(200)
            if (listRev != TerminalManager.listRevision) {
                listRev = TerminalManager.listRevision
            }
            val a = TerminalManager.active()?.id ?: 0
            if (a != activeId) activeId = a
        }
    }
    val sessions = TerminalManager.list()
    val session = TerminalManager.active()

    if (session == null) {
        // 极端情况（会话被全部关掉）：给个空态，别崩。
        AppScaffold(title = "终端", onBack = { navController.popBackStack() }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                Text("没有可用的终端会话", color = MiuixTheme.colorScheme.onSurfaceContainerVariant)
            }
        }
        return
    }

    // ---- 输出（按会话快照；切会话即换数据源）----
    var lines by remember(session.id) { mutableStateOf(session.snapshot()) }
    var revision by remember(session.id) { mutableIntStateOf(session.revision) }
    LaunchedEffect(session.id) {
        while (true) {
            kotlinx.coroutines.delay(300)
            if (revision != session.revision) {
                revision = session.revision
                lines = session.snapshot()
            }
        }
    }

    val listState = rememberLazyListState()
    // ★ 「输入动画和日志动画一样」= 复用日志页那套弹簧跟随引擎。
    AutoFollowScrollLazy(lazyState = listState, itemCount = lines.size)

    var input by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    AppScaffold(title = "终端", onBack = { navController.popBackStack() }) { pad ->
        val topInset = LocalTopBarInset.current
        val scrollLimit = LocalScrollBottomLimit.current
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏占位（与日志页同款：只留一条 Spacer，工具条才不会被透明顶栏盖住）
            Spacer(Modifier.height(topInset))

            // ==================== 工具栏（Termux 风格的会话与快捷键）====================
            TerminalToolbar(
                sessions = sessions,
                activeId = session.id,
                running = session.isRunning,
                onSelect = { TerminalManager.select(it) },
                onNew = {
                    val s = TerminalManager.createNew(context)
                    note = if (s == null) {
                        "最多只能同时开 ${TerminalManager.MAX_SESSIONS} 个终端"
                    } else {
                        "已新建 ${s.title}"
                    }
                },
                onClose = { TerminalManager.close(it) },
                onInterrupt = {
                    note = if (session.interrupt()) "已发送 Ctrl+C" else "终端没在跑，发不了 Ctrl+C"
                },
                onClear = { session.clear() },
                onRestart = {
                    // 用 restartAsync（而不 stop + startAsync）：停止流程里有
                    // waitFor / sleep，必须与启动串在同一个后台线程，
                    // 否则 start 会在 stop 还在清理时就去写 pidfile。
                    session.restartAsync()
                    note = "已重启 ${session.title}"
                },
            )
            note?.let {
                Text(
                    text = it,
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }

            // ==================== 输出区（莫奈底 + 弹簧跟随）====================
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.surfaceContainer),
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

            // ==================== 输入（回车即执行）====================
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                label = "输入命令后回车执行（如 ls -l \$PK_NODE_DIR）",
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val cmd = input.trim()
                    if (cmd.isNotEmpty()) {
                        if (!session.run(cmd)) session.startAsync()
                        input = ""
                    }
                }),
            )
        }
    }
}

/**
 * 工具栏 —— 对齐 Termux 的「多会话 + 快捷键」形态，但控件全用 miuix。
 *
 * 一行横向滚动（会话多了不至于把「新建」按钮挤出去）：
 *
 * ```
 * [终端 1 ✓] [终端 2] [终端 3 ×] [+ 新建]   ●运行中 ^C 清空 重启
 * ```
 */
@Composable
private fun TerminalToolbar(
    sessions: List<TerminalSession>,
    activeId: Int,
    running: Boolean,
    onSelect: (Int) -> Unit,
    onNew: () -> Unit,
    onClose: (Int) -> Unit,
    onInterrupt: () -> Unit,
    onClear: () -> Unit,
    onRestart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- 第一行：会话 chips + 新建 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sessions.forEach { s ->
                SessionChip(
                    label = s.title,
                    selected = s.id == activeId,
                    // 最后一个不允许关（页面总得有东西显示）。
                    closable = sessions.size > 1,
                    onClick = { onSelect(s.id) },
                    onClose = { onClose(s.id) },
                )
            }
            ActionButton("＋ 新建", onClick = onNew)
        }

        // ---- 第二行：状态 + 快捷键 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (running) "● 运行中" else "○ 已停止",
                fontSize = 12.sp,
                color = if (running) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceContainerVariant
                },
                modifier = Modifier.weight(1f),
            )
            // ★ 「ui 用 miuix 的控件」（用户原话）：动作按钮用真 miuix [Button]。
            //   `^C` 用 `buttonColorsPrimary()` 提亮：它是终端里最常用的一个键。
            ActionButton("^C", primary = true, onClick = onInterrupt)
            ActionButton("清空", onClick = onClear)
            ActionButton("重启", onClick = onRestart)
        }
    }
}

/** miuix 按钮版的动作键（新建 / ^C / 清空 / 重启）。 */
@Composable
private fun ActionButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        // 终端工具栏是「紧凑」语境：默认 MinHeight=40dp / MinWidth=58dp 偏大，
        // 这里压到 32dp / 48dp，同时保留 miuix 的 squircle 形状与内部 margin。
        minWidth = 48.dp,
        minHeight = 32.dp,
        cornerRadius = 10.dp,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        colors = if (primary) {
            ButtonDefaults.buttonColorsPrimary()
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(label, fontSize = 12.sp)
    }
}

/** 会话 chip：选中态高亮；右上角 `×` 关闭（只有一个会话时不显示）。 */
@Composable
private fun SessionChip(
    label: String,
    selected: Boolean,
    closable: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val bg = if (selected) MiuixTheme.colorScheme.primary
    else MiuixTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) MiuixTheme.colorScheme.onPrimary
    else MiuixTheme.colorScheme.onSurfaceContainerVariant
    Row(
        modifier = Modifier
            // ★ 固定宽度：会话名都是「终端 N」，等宽后切换时 chip 条不会左右跳。
            .widthIn(min = 78.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
        Spacer(Modifier.weight(1f))
        if (closable) {
            Text(
                text = "×",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = fg,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 3.dp),
            )
        }
    }
}

/**
 * 按行内容着色 —— **全取莫奈色板**，不硬编码 RGB（用户要求「背景用莫奈取色」）。
 *
 * | 行首 | 语义 | 颜色 |
 * |---|---|---|
 * | `$ ` | 自己敲的命令回显 | `primary` |
 * | `[` | 终端自身的提示行 | `secondaryVariant` |
 * | 含 error/failed/denied | 报错 | `error` |
 * | 其他 | 普通输出 | `onSurfaceContainer` |
 *
 * 这样在浅色主题下也是「主题里的红/蓝」，对比度由莫奈保证。
 *
 * ⚠️ miuix 的 [MiuixTheme.colorScheme] **没有 `tertiary`**
 * （只有 `tertiaryContainer` / `onTertiaryContainer` / `tertiaryContainerVariant`），
 * 所以提示行用 `secondaryVariant` —— 它是「当文字用也看得清」的次级强调色。
 */
@Composable
private fun colorFor(line: String): Color = when {
    line.startsWith("$ ") -> MiuixTheme.colorScheme.primary
    line.startsWith("[") -> MiuixTheme.colorScheme.secondaryVariant
    line.contains("error", true) ||
        line.contains("failed", true) ||
        line.contains("denied", true) -> MiuixTheme.colorScheme.error
    else -> MiuixTheme.colorScheme.onSurfaceContainer
}
