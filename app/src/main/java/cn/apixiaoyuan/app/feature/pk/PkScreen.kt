package cn.apixiaoyuan.app.feature.pk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.apixiaoyuan.app.core.navigation.AppNavController
import cn.apixiaoyuan.app.core.navigation.RouteHome
import cn.apixiaoyuan.app.core.navigation.RoutePkH5

/**
 * 口算 PK 入口页。
 *
 * 口算 PK 是 H5 应用，原生侧只提供**容器**与**登录态同步**，实际交互全在
 * [PkH5Screen] 里的 WebView 中。本页只做两件事：
 *
 *  1. 触发 [PkViewModel.loadEntry] 拉 PK 入口数据（可选，失败不阻塞）；
 *  2. 渲染 [PkH5Screen]，把 H5 容器全屏铺开。
 *
 * ## ★ 2026-10-03：删掉了「刷轮数」悬浮入口（遗留屎山）
 *
 * 用户原话：
 * > 「还有什么屎山之前 h5 页面让删掉的刷轮数怎么还在」
 *
 * 这里原先挂着一个 `TextButton("刷轮数")`（浮在右下角），点击弹 [PkBattleDialog]
 * 做「PK 秒结算 / 循环 / 多玩法并发」。它**三个层面都该删**：
 *
 *  1. **功能重复**：PK 现在整页就是内置 pk-node 的 H5，而刷局能力早就落在
 *     pk-node 侧（管理后台 / `PkBattleEngine`）。原生再挂一份是两套实现打架。
 *  2. **交互有害**：H5 自己有右下角的按钮，这个浮层会**压住**它 ——
 *     当初就是因为「挡事」才被改成「默认隐藏」。**需要默认隐藏的 UI 本身就是设计错了**。
 *  3. **是残留**：用户之前就要求 H5 页面别再有它。
 *
 * 删掉后本页只剩「一个全屏 H5 容器」，也就没有可挡的东西了。
 *
 * ⚠️ 注意：[PkBattleDialog] / `OldSimianPrefs.pkGrindFloatingEntry` 的**类本身没删**
 * （那是跨层的东西，见 `OldSimianScreen` 的说明），只删了本页的入口渲染。
 */
@Composable
fun PkScreen(
    navController: AppNavController,
    viewModel: PkViewModel = viewModel(),
) {
    // 进页面拉一次入口数据；H5 不依赖它，拉失败也照常加载。
    LaunchedEffect(Unit) {
        viewModel.loadEntry()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PkH5Screen(
            viewModel = viewModel,
            // 「返回」回主页，而不是简单 pop 一层 —— 用户要求「像小猿 AI 原版一样
            // 返回主页」。原版 PK 是独立 WebApp Activity，返回即 finish 回主页；
            // 这里 PK 是 Home tab 的下一级，所以显式回退到 RouteHome。
            //
            // 兜底：若返回栈里此刻没有 RouteHome（理论上不会 —— PK 只从首页快捷
            // 入口进入），退化为普通 popBackStack，避免按键变成「无响应」。
            onFinish = {
                if (!navController.popBackStack<RouteHome>(inclusive = false)) {
                    navController.popBackStack()
                }
            },
            // ★ 2026-10-04：H5 里「点按钮开下一个页面」→ **压一个新的 H5 容器**。
            //
            // 用户要求：「点击按钮 → miuix/aosp 原生转场 → 进入新 h5 容器
            //          → 预测性返回 → 退回主页」。
            // 之前 H5 的 openWebView 被 pk-node 实现成同窗口 `location.href`，
            // 宿主在这里接住它、改走 App 导航 —— 转场与预测性返回才会生效。
            onOpenChild = { childUrl -> navController.navigate(RoutePkH5(childUrl)) },
        )
    }
}