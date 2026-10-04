package cn.apixiaoyuan.app.feature.oldsimian

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.apixiaoyuan.app.core.navigation.AppNavController
import cn.apixiaoyuan.app.core.design.component.AppScrollScaffold
import cn.apixiaoyuan.app.core.oldsimian.OldSimianPrefs
import cn.apixiaoyuan.app.core.pk.host.PkAutoHostService
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
// `TextField` 仍被 [CostFieldRow]（「开下一局间隔」「提交次数」等数字输入）使用。
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * PK 相关开关的接入方式（★ 2026-10-04 更新）。
 *
 * PK 页换成内置 node 版后，原先三个注入 H5 的脚本（`pk_auto_next.js` /
 * `pk_no_anim.js` / `pk_auto_stroke.js`）是照**旧容器**写的。现在改为：
 *
 *  - `结束页自动化` / `自动提交画笔` —— 走内置 pk-node 的 `pkbot` 能力
 *    （URL 参数 `?pkbot=autoNext,autoStroke`，由 `PkHostOrchestrator` 拼）；
 *  - `去除排行榜展示动效` —— 仍由 [PkJsInjector] 注入 `pk_no_anim.js`
 *    （只动 CSS/音频，与容器实现无关）。
 */

/**
 * 「老挂戏老叟」功能页（**底部「功能」tab 的根页**，见 `MainActivity` 的 pager）。
 *
 * ## 页面纪律
 *
 * **页面完全用本项目自己的 UI 搭建**（[AppScrollScaffold] 顶栏 + miuix 组件），
 * 不复用 cn.nizou.sxd 的 `MainPagerScreen` / `CustomScoreScreen` 等任何页面 ——
 * 这是用户明确要求的：「页面就不要用老挂戏老叟的了」。
 *
 * 组件选型：miuix 的 [Card] / [Switch] / [Slider] / [TextField]。
 * 理由：本项目主题根已切到 [MiuixTheme]（见 `MiuixAppTheme`），
 * 用 miuix 组件能直接吃到同一套莫奈色板，与顶栏渐变模糊风格统一。
 *
 * ## 与 cn.nizou.sxd 的根本差异（**重要**）
 *
 * | | cn.nizou.sxd | 本项目 |
 * |---|---|---|
 * | 架构 | LSPosed 模块，hook 宿主进程 | **内置客户端**，自己发请求 |
 * | 练习改答案 | hook 宿主的 Presenter / 提交包 | 改自己组装的 [cn.apixiaoyuan.app.core.model.ExamData] |
 * | PK 自动化 | hook 宿主的 `WebView.loadUrl` 注入 JS | 自己的 WebView 直接 `evaluateJavascript` |
 * | 笔迹 | `libauto_oral.so`（Rust jni） | 纯 Kotlin 字形库 [cn.apixiaoyuan.app.core.oldsimian.OralStrokes] |
 *
 * 所以**没有任何一行 hook 代码**，功能靠内置链路原生实现。
 *
 * ## ★ 2026-10-03 本页的大幅删减（用户要求，全部只在 ui 层）
 *
 * 用户原话：「把功能页没有实际作用以及后台接口的功能删掉（只在 ui 层）」
 * → 「练习的功能可以直接把 ui 删了，注意都是功能 tab 页的元素不要删错了」
 * → 「还有其他关于 pk h5 页面的功能还没写先把开关设为不可动」。
 *
 * | 段 | 处理 | 依据 |
 * |---|---|---|
 * | 练习（5 项） | **整段删 UI** | 用户明确要求；底层 `ExamViewModel` / `OldSimianPrefs` 未动 |
 * | PK · 刷 PK 对局入口 | **删** | 与刷分区「PK 刷对局」是**同一个** `RoutePkGrind`，纯重复 |
 * | PK · 结束页自动化 | **可用** | pk-node `autoNext`（URL 的 `pkbot=autoNext`）|
 * | PK · 去除排行榜动效 | **可用** | App 侧注入 `pk_no_anim.js`（只动 CSS/音频）|
 * | PK · 自动提交画笔 | **可用** | pk-node `autoStroke`（URL 的 `pkbot=autoStroke`）|
 * | PK · 显示刷轮数悬浮入口 | **删** | 2026-10-04 用户要求；开关无渲染点，连 pref 一起清 |
 * | H5 调试 · Eruda | 保留 | 用户明确要求保留 JS 控制台 |
 * | 分数（2 项） | **整段删 UI** | 与刷分区同源（同一 pref、同一 `RouteScorePump`） |
 *
 * 保留的开关（真实接入、不可删）：
 *  - Eruda 调试台 → `PkJsInjector` 注入 `assets/js/eruda.js`。
 *
 * 已删但**仍在底层生效**的（供将来决定是否彻底移除）：
 *  - `autoCorrect` / `customAnswerEnabled` / `customAnswerText` / `strokeEnabled` /
 *    `customCostEnabled` / `customCostMs` → `ExamViewModel.buildSubmitBody()` 仍读；
 *  - `customScoreEnabled` → 刷分区仍可改，`ScorePumpViewModel.start()` 会判。
 *
 * ## 「无视名字限制」**已接入**（2026-09-28 更正）
 *
 * 本页此前有一行 **disabled 的占位开关**（写「待接入个人资料编辑」），
 * 那是**过时的死开关** —— 该功能实际已在**账号页**落地：
 *  - 开关 UI：`feature/account/AccountScreen.kt`（「无视名字限制」卡片）；
 *  - 生效逻辑：`AccountViewModel.rename()` 读
 *    `OldSimianPrefs.ignoreNicknameRestriction` —— 打开即跳过客户端全部昵称校验
 *    （长度/字符/敏感词），原样提交给服务端。
 *
 * 故本页**删除了那行死开关**（避免「显示未接入但实际已接入」的误导），
 * 需要开关请到账号页。
 */
@Composable
fun OldSimianScreen(
    navController: AppNavController,
    title: String = "功能",
    showBack: Boolean = false,
) {
    AppScrollScaffold(
        title = title,
        onBack = if (showBack) ({ navController.popBackStack() }) else null,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ==================== 练习 ====================
            //
            // ★ 2026-10-03 按用户要求**整段删除**（原 5 项：自动全部答对 / 自定义答案 +
            //   答案输入框 / 提交画笔 / 自定义结算时间 + 每题耗时输入框）。
            //
            // 用户原话：「练习的功能可以直接把 ui 删了，注意都是功能 tab 页的元素不要删错了」。
            //
            // ⚠️ **只删 UI**，底层一个都没动：
            //   · `OldSimianPrefs` 里那 5 个字段与 `persist()/init()` 照旧保留 ——
            //     它们还会被 `ConfigTransfer`（配置导入导出）读写，删了会让老配置文件导入失败；
            //   · 真正的消费方 `feature/exercise/ExamViewModel.buildSubmitBody()` **完全没改**
            //     —— 也就是说：**已经开着这些开关的人，行为不变**（继续生效）；
            //     只是新用户没有界面去打开它们了。
            //   这正是「只在 ui 层」的边界。若将来要彻底移除，需连 ExamViewModel 与
            //   ConfigTransfer 一起动，那是另一个决定。

            // ==================== 后台挂机 ====================
            //
            // ★ 2026-10-03：从**刷 PK 对局页**（`PkGrindScreen`）搬到这里。
            //
            // 用户原话：「后台挂机在练习应该也同样适用才对，把后台悬浮窗的开关做进
            // tab 栏功能里而不是放到刷分」。
            //
            // 为什么搬得动：`PkAutoHostService` 本身**与 PK 无关** —— 它只做三件事：
            //   ① 起内置 node（`PkHostOrchestrator.startAsync`，给 PK H5 用）；
            //   ② 前台服务常驻通知（系统不轻易回收本进程）；
            //   ③ 悬浮球（可见窗口 → 更难被回收 + 一键回 App）。
            // 也就是说它是**整个 App 的保活手段**，练习挂机同样受益
            // （练习刷局跑在 App 自己的进程里，进程活着才不会半路被回收）。
            //
            // 放在「功能」tab 而不是刷分页：保活是全局能力，不该藏在某一个刷分链路里。
            HostingSection()

            // ==================== PK ====================
            //
            // ★ 2026-10-03 两处调整：
            //   ① 删除「刷 PK 对局」入口 —— 它跳 `RoutePkGrind`，而**刷分区**
            //      （[cn.apixiaoyuan.app.feature.grind.GrindScreen]）的「PK 刷对局」
            //      是**同一个路由**，纯重复入口。
            //   ② 下面三项**已接入**（★ 2026-10-04，用户要求「把 tab 功能里的 PK
            //      功能全部实现」）。
            //
            //      ## 接入方式：全部改走内置 pk-node 的 `pkbot` 能力
            //
            //      PK 页换成内置 node 版后，原先那三个脚本（`assets/js/pk_auto_next.js`
            //      / `pk_no_anim.js` / `pk_auto_stroke.js`）是照**旧容器**（App 自己发
            //      请求 + 自己的桥）写的，在新架构上不成立。而内置 pk-node 自己就有
            //      一套等价能力，由入口 URL 的 `?pkbot=` 驱动：
            //
            //        | 本页开关 | pk-node 能力 | pk-node 里的实现 |
            //        |---|---|---|
            //        | 视为正确答案 | `answer`    | `recognize` 桥回预期答案 |
            //        | 自动提交画笔 | `autoStroke`| 对局页定时派发 touch 笔迹 |
            //        | 结束页自动化 | `autoNext`  | 结算页点「继续 PK」 |
            //
            //      URL 由 `PkHostOrchestrator.h5Url()` 里的 `pkbotParam()` 拼（读的
            //      就是本页这几个开关）。**所以本页只差「把开关放开」**。
            //
            //      ⚠️ 「去除排行榜展示动效」pk-node 没有对应能力（它只管答题链），
            //      仍由 App 侧的 [PkJsInjector] 注入 `assets/js/pk_no_anim.js` ——
            //      那个脚本只动 CSS/音频，与容器实现无关，继续有效。
            SectionCard(title = "PK") {
                SwitchRow(
                    title = "结束页自动化",
                    summary = "结算页自动开下一局（走内置 pk-node 的 autoNext 能力）",
                    checked = OldSimianPrefs.autoNextRound,
                    onCheckedChange = {
                        OldSimianPrefs.autoNextRound = it
                        OldSimianPrefs.persist()
                    },
                )
                if (OldSimianPrefs.autoNextRound) {
                    SliderRow(
                        title = "开下一局间隔",
                        valueText = "${OldSimianPrefs.nextRoundIntervalMs} ms",
                        value = OldSimianPrefs.nextRoundIntervalMs.toFloat(),
                        valueRange = OldSimianPrefs.NEXT_ROUND_INTERVAL_MIN.toFloat()..
                            OldSimianPrefs.NEXT_ROUND_INTERVAL_MAX.toFloat(),
                        onValueChange = { OldSimianPrefs.nextRoundIntervalMs = it.toInt() },
                        onValueChangeFinished = { OldSimianPrefs.persist() },
                    )
                }
                SwitchRow(
                    title = "去除排行榜展示动效",
                    summary = "CSS 动画归零 + 音效静音（只动样式，不碰答题节奏）",
                    checked = OldSimianPrefs.noRankingAnim,
                    onCheckedChange = {
                        OldSimianPrefs.noRankingAnim = it
                        OldSimianPrefs.persist()
                    },
                )
                SwitchRow(
                    title = "自动提交画笔",
                    summary = "题目页自动注入笔迹并触发提交（走内置 pk-node 的 autoStroke 能力）",
                    checked = OldSimianPrefs.pkStrokeEnabled,
                    onCheckedChange = {
                        OldSimianPrefs.pkStrokeEnabled = it
                        OldSimianPrefs.persist()
                    },
                )
                if (OldSimianPrefs.pkStrokeEnabled) {
                    CostFieldRow(
                        title = "提交次数",
                        value = OldSimianPrefs.pkStrokeCount,
                        range = OldSimianPrefs.PK_STROKE_COUNT_MIN..
                            OldSimianPrefs.PK_STROKE_COUNT_MAX,
                        onCommit = {
                            OldSimianPrefs.pkStrokeCount = it
                            OldSimianPrefs.persist()
                        },
                    )
                    CostFieldRow(
                        title = "两次提交间隔",
                        value = OldSimianPrefs.pkStrokeIntervalMs,
                        range = OldSimianPrefs.PK_STROKE_INTERVAL_MIN..
                            OldSimianPrefs.PK_STROKE_INTERVAL_MAX,
                        onCommit = {
                            OldSimianPrefs.pkStrokeIntervalMs = it
                            OldSimianPrefs.persist()
                        },
                    )
                }
            }

            // ==================== H5 调试 ====================
            SectionCard(title = "H5 调试") {
                SwitchRow(
                    title = "Eruda 调试台",
                    summary = "在 PK 的 H5 页面里注入 Eruda（移动端 DevTools）：" +
                        "可看 Console / Network / Elements / Storage。" +
                        "开启后下次打开 PK 页生效（会浮一个面板，排障用）",
                    checked = OldSimianPrefs.h5DebugConsole,
                    onCheckedChange = {
                        OldSimianPrefs.h5DebugConsole = it
                        OldSimianPrefs.persist()
                    },
                )
            }
            // ==================== 分数 ====================
            //
            // ★ 2026-10-03 按用户要求**整段删除**（原来有两项：开关 + 「打开刷分页」）。
            //
            // 两项都在**刷分区**里有同源副本（`GrindScreen` 的「开关」段）：
            //  - 开关 `customScoreEnabled` 读写的是**同一份** pref
            //    （`OldSimianPrefs.KEY_CUSTOM_SCORE_ENABLED`）；
            //  - 「打开刷分页」与刷分区的「直接刷分」跳**同一个** `RouteScorePump`。
            //
            // 即：这里删掉不会少任何能力，只是消除重复入口（诚实说明：同一份配置
            // 现在只能从「主页 → 刷分区」改，这是**行为变更**，但符合用户要求）。
            //
            // ⚠️ `OldSimianPrefs.customScoreEnabled` **本身没删** —— 它是**跨层**的
            // （`ScorePumpViewModel.start()` 里会判它），删了会导致刷分入口点不动。

            // ==================== 后台挂机（悬浮球保活） ====================
            //
            // ★ 2026-10-03 从「刷 PK 对局」页搬到这里（用户要求：
            //   「后台挂机在练习应该也同样适用才对，把后台悬浮窗的开关做进 tab 栏
            //     功能里而不是放到刷分」）。
            //
            // 它与「刷什么」无关 —— 只做「前台服务常驻通知 + 可见悬浮球」，
            // 目的是让 App 进程别被系统回收，于是 PK / 练习 / 刷分三条链路的
            // 协程都能在后台继续跑。放在 PK 页里会让人误以为只对 PK 生效。
            SectionCard(title = "后台挂机") {
                AutoHostSwitchCard()
            }

            // ==================== 说明 ====================
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
                        text = "关于",
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                    )
                    Text(
                        text = "本项目是内置客户端：功能直接作用于自己发出的请求与" +
                            "自己的 WebView，不 hook 任何进程。",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                    // ★ 2026-10-03 更新：原来这里写的是「开启『自动全部答对』『自定义答案』
                    //   会改变练习记录的真实性」—— 那两项的 UI 已按用户要求删除，
                    //   所以这段话不再适用于本页，换成对「置灰开关」的说明。
                    //   （注意：Compose 的 Text 不解析 markdown，别写 ** 强调符，
                    //     否则会原样显示星号。）
                    Text(
                        text = "标了「暂不可用」的开关是还没接上（PK H5 功能在新架构上尚未实现），" +
                            "不是坏了；实现后会开放。其余开关默认关闭。",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
            }
        }
    }
}

/**
 * 「后台挂机（悬浮球保活）」开关（★ 2026-10-03 从刷 PK 对局页搬来）。
 *
 * ## 为什么放在功能 tab 而不是刷分页
 *
 * 用户原话：「后台挂机在练习应该也同样适用才对，把后台悬浮窗的开关做进 tab 栏
 * 功能里而不是放到刷分」。
 *
 * 保活是**全局能力**：[cn.apixiaoyuan.app.core.pk.host.PkAutoHostService] 只做
 * ① 起内置 node ② 前台服务常驻通知 ③ 悬浮球 —— 三件事都跟「刷 PK」无关。
 * 练习刷局跑在 App 自己进程里，进程活着同样受益（否则挂到一半被回收）。
 *
 * ## 为什么要单独一段、还要写这么多说明
 *
 * 挂机依赖两个**用户手动授予**的权限。不给权限时表现是「开关打开了但球没出来」，
 * 不把原因写在界面上，用户只会觉得「坏了」。
 */
@Composable
private fun HostingSection() {
    val hostCtx = androidx.compose.ui.platform.LocalContext.current
    var hostOn by remember { mutableStateOf(PkAutoHostService.isRunning()) }
    var hostNote by remember { mutableStateOf<String?>(null) }

    // 从系统设置页返回时刷新一次状态（用户可能刚授完权限）。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hostOn = PkAutoHostService.isRunning()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    SectionCard(title = "后台挂机（悬浮球保活）") {
        Button(
            onClick = {
                val want = !PkAutoHostService.isRunning()
                if (want) {
                    // 悬浮窗是**特殊权限**，只能跳系统设置页让用户手动开。
                    // 没权限就给引导，别假装启动成功。
                    val canOverlay = android.provider.Settings.canDrawOverlays(hostCtx)
                    if (!canOverlay) {
                        hostNote = "先去系统设置里打开「显示在其他应用上层」，再回来点一次"
                        runCatching {
                            hostCtx.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + hostCtx.packageName),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        return@Button
                    }
                }
                PkAutoHostService.toggle(hostCtx, want)
                hostOn = want
                hostNote = if (want) {
                    "已开启：状态栏常驻通知 + 屏幕上多出一支笔（可拖动，点击回到 App）"
                } else {
                    "已关闭：悬浮球与通知都已撤下"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (hostOn) "关闭后台挂机" else "开启后台挂机")
        }
        Text(
            text = "「后台挂机」= 让 App 进程别被系统回收，**练习刷局与 PK 刷局都受益**。\n" +
                "开启后：① 前台服务常驻通知；② 屏幕上出现一支背景透明的笔形悬浮球" +
                "（可拖动，点击回到 App）。\n" +
                "两者叠加才算保活 —— 只有通知或只有悬浮球都压不住后台回收。",
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )
        hostNote?.let {
            Text(text = it, color = MiuixTheme.colorScheme.primary)
        }
    }
}

/** 分组卡片：标题 + 若干行。 */
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
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                content()
            }
        }
    }
}
/** 开关行：左标题+副标题，右 Switch。 */
@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
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
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

/**
 * 数字输入行（通用）。
 *
 * 用 miuix [TextField]（`value: String` 重载）+ `KeyboardType.Number` 而不是
 * [SliderRow]：调用方的取值范围跨度都很大（耗时 5..10000、提交次数 1..50），
 * 线性滑条在 360dp 宽下每像素几十个单位，**根本选不到精确值**。
 * 输入框能精确落值，也顺手把非法输入过滤掉。
 *
 * 写盘时机：每次文本变化就解析并写盘，但**只在解析成功时**才写 ——
 * 清空重输的过程中会短暂出现空串，那时不动 prefs，避免把配置写成 0。
 *
 * `remember(value)` 而不是 `remember`：写盘后 `value` 被 `coerceIn` 夹过
 * （如输入 99999 → 10000），这一步会重建文本，让输入框立刻显示真实生效值，
 * 不会出现「框里 99999、实际 10000」的错位。
 *
 * @param title  行标题
 * @param value  当前生效值
 * @param range  合法区间；越界输入会被夹到边界后写盘
 * @param unit   单位后缀，仅用于「当前生效」提示
 */
@Composable
private fun CostFieldRow(
    title: String,
    value: Int,
    range: IntRange,
    unit: String = "",
    onCommit: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            color = MiuixTheme.colorScheme.onSurfaceContainer,
        )
        TextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter(Char::isDigit)
                text = digits
                digits.toIntOrNull()?.let { parsed ->
                    val clamped = parsed.coerceIn(range.first, range.last)
                    if (clamped != value) onCommit(clamped)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "${range.first}~${range.last}",
            useLabelAsPlaceholder = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        Text(
            text = "当前生效：$value$unit",
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )
    }
}

/** 滑条行：标题 + 当前值 + Slider。 */
@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                modifier = Modifier.weight(1f),
            )
            Text(text = valueText, color = MiuixTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}