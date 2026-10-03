package cn.apixiaoyuan.app.core.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 全应用路由表。
 *
 * ## 为什么从 androidx `@Serializable object` 改成 miuix-nav 的 `NavKey`
 *
 * 2026-09-26 用户指出：本项目的二级页转场**不是**「老挂戏老叟的过渡动画」。
 * 逐行核对参考项目 `cn.nizou.sxd/ui/MainPagerScreen.kt` 后确认属实 ——
 * 它用的是 **miuix-nav**：
 *
 * ```kotlin
 * val backStack = rememberNavBackStack<MainRoute>(MainRoute.Main)
 * val navigator = remember(backStack) { Navigator(backStack) }
 * NavDisplay(
 *     backStack = backStack,
 *     onBack = { if (navigator.backStackSize() <= 1) backAtRoot() else navigator.pop() },
 *     transition = weKitNavTransition(ThemeSettings.pageTransitionAnimation),
 *     effects = rememberM3NavEffects(),
 * ) { entry<MainRoute.General>(swipeDismiss = NavSwipeDirection.LeftToRight) { … } }
 * ```
 *
 * 而本项目此前用的是 androidx `NavHost` + compose `slideInHorizontally` 整屏滑 ——
 * **两套完全不同的转场模型**（miuix 是「单一 float 深度 → graphicsLayer」，
 * androidx 是 `AnimatedContent` 的四条 enter/exit）。miuix 的
 * `NavTransitions.MiuixDefault` 才是「老挂戏老叟同款」的真实观感：
 * 进场页整屏从右侧滑入、被覆盖页向左视差 **1/4 宽**且 `alpha = 1 - 0.1 * progress`，
 * 另有圆角裁切（`NavDisplayEffects`）与预测性返回手势。
 *
 * ## 结构
 *
 * 一个 `sealed interface` + 若干 `@Serializable` 子类型；`NavKey` 是纯标签，
 * `@Serializable` 是 `rememberNavBackStack` 持久化返回栈的硬要求
 * （见 `miuix-nav` 的 `NavKey` KDoc）。
 */
@Serializable
sealed interface Route : NavKey

/** 首页（Tab 根页）。 */
@Serializable
data object RouteHome : Route

/**
 * 「Linux 终端」二级页（★ 2026-10-03 新增）。
 *
 * 用户要求：「主页加入 liunx 终端入口」。
 * 它跑的是 **Android 自带的 `/system/bin/sh`（toybox/mksh）**，
 * 不是塞进去的发行版 —— 详见 `TerminalSession` 的 KDoc。
 */
@Serializable
data object RouteTerminal : Route

/**
 * 「接口控制台」路由（★ 2026-10-03 起**不再是 Tab 根页、也无任何入口**）。
 *
 * 历史：它原本是四 Tab 之一（首页/接口/请求台/设置）的根页；后来改为
 * 首页快捷入口 + 独立二级页（`ApiScreen`）。2026-10-03 按用户要求
 * 「把功能页没有实际作用以及后台接口的功能删掉（只在 ui 层）」
 * 摘掉了**首页快捷入口**与 `AppNavHost` 里的页面注册。
 *
 * ⚠️ 路由声明本身**保留**：删掉它就要连带删 `ApiScreen` 的签名，
 * 而用户的边界是「只在 ui 层」—— 底层与页面源码都不动，随时可恢复。
 */
@Serializable
data object RouteApi : Route

/** 请求台（Tab 根页）。 */
@Serializable
data object RouteRepl : Route

/** 设置（Tab 根页）。 */
@Serializable
data object RouteSettings : Route

/** 样本库（二级页）。 */
@Serializable
data object RouteSamples : Route

/** 口算 PK（二级页，H5 容器）。 */
@Serializable
data object RoutePk : Route

/** 练习（二级页）。 */
@Serializable
data object RouteExercise : Route

/**
 * 练习「刷对局」（二级页）—— 与网页端（pk-node）同做法的自动循环。
 *
 * 循环「出题 → 抄答案+笔迹 → 提交」，受**出题冷却 ≈62s/账号**配速。
 * 与 [RouteExercise]（手动点题练习）不同：这里是无人值守的批量刷。
 */
@Serializable
data object RouteExercisePump : Route

/** 登录（二级页）。 */
@Serializable
data object RouteLogin : Route

/** 「老挂戏老叟」功能页（二级页）。 */
@Serializable
data object RouteOldSimian : Route

/** 「自定义分数（刷分）」二级页。 */
@Serializable
data object RouteScorePump : Route

/** 「刷 PK 对局」二级页（纯 API 刷局：出题→弧线笔迹→提交）。 */
@Serializable
data object RoutePkGrind : Route

/**
 * 刷分区（二级页）—— 从主页进入的刷分总控。
 *
 * ## 为什么要有这个页（2026-09-28 用户要求）
 *
 * 刷分相关入口原先散在「功能」tab 里（PK 刷对局 / 自定义分数 / 练习页各自一个），
 * 用户希望**主页集中一个刷分区**，点进去就是「和 pk-node 差不多的功能」，
 * 三个选项一目了然：
 *  - PK 刷对局（[RoutePkGrind]）；
 *  - 练习刷对局（[RouteExercise]，练习闭环：知识点 → 出题 → 提交 → 经验）；
 *  - 直接刷分（[RouteScorePump]）。
 *
 * 功能页（[RouteOldSimian]）里对应的「分数」段已迁到本区，不再重复。
 */
@Serializable
data object RouteGrind : Route

/**
 * 「账号分数 / 任务」二级页（★ 2026-09-30 新增）。
 *
 * 从刷分区进入，只读观测台：刷新展示「本周经验 / 今日积分 / 连续打卡 / 排名 /
 * 今日任务」。对应 pk-node 刷练习页的「刷新分数/任务」。
 */
@Serializable
data object RouteAccountStats : Route

/** 账号页（宝贝学习账号切换 + 改密码）。 */
@Serializable
data object RouteAccount : Route

/**
 * 答题页路由（带参）。
 *
 * 三个参数全部来自练习页当前选择：
 *  - [keypointId] 知识点 ID
 *  - [limit]      题目数量，取自 `ExerciseType.chooseNumArray`
 *  - [title]      知识点名，仅用于顶栏展示
 */
@Serializable
data class RouteExam(
    val keypointId: Int,
    val limit: Int,
    val title: String,
) : Route