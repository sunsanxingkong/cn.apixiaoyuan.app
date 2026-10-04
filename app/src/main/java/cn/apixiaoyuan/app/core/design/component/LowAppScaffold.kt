package cn.apixiaoyuan.app.core.design.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.apixiaoyuan.app.core.design.glass.low.LowBlurredTopBar
import cn.apixiaoyuan.app.core.design.glass.low.lowLayerBackdrop
import cn.apixiaoyuan.app.core.design.glass.low.rememberLowGlassBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import cn.apixiaoyuan.app.core.design.icon.AppIcons
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 低版本（API 24–32）的页面骨架 —— [AppScaffold] 的无 miuix-blur 等价实现。
 *
 * # 与高版本的结构对应
 *
 * | 高版本（AppScaffold） | 低版本（本函数） |
 * |---|---|
 * | `rememberLayerBackdrop { drawRect(surface); drawContent() }` | [rememberLowGlassBackdrop] + `.lowLayerBackdrop()` |
 * | `progressiveTextureBlur(...)` 顶栏渐变模糊 | **实色顶栏**（miuix 官方在 blur 不可用时的降级路径） |
 * | `BlurOverhang` 多盖 28dp | 不盖（没有模糊层，加了只会白占一条） |
 *
 * # 为什么低版本顶栏不做模糊
 *
 * `progressiveTextureBlur` 依赖 AGSL（API 33+）。低版本要做「渐变模糊」只能靠
 * RenderScript 反复处理顶栏条带 —— 成本高、且顶栏内容每次滚动都要重算，
 * 在低端机上必然掉帧。而 miuix 自己给出的降级就是**实色顶栏**，
 * 所以这里照它的降级行为走（保证观感一致：内容滚到顶栏下方时被实色盖住）。
 *
 * # 内容层的 backdrop 录制保留
 *
 * 低版本底栏的玻璃折射**仍然需要**内容快照，所以内容层照挂 `.lowLayerBackdrop()`，
 * 与高版本一样有内容录制（只是顶栏不消费它，由底栏消费）。
 */
@Composable
internal fun LowAppScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier,
    bottomInset: Dp,
    surfaceColor: Color,
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = rememberLowGlassBackdrop()

    // ★★ 2026-10-05（用户：「顶部 scaffold 没有渲染效果啊（在低版本管线）」）：
    //
    // **根因**：[backdrop] 只是个「录制容器」，必须有人**定期把图层抓成位图**
    // （[LowGlassBackdrop.capture]）供顶栏模糊采样。上一版只在 MainActivity 的
    // `LowPagerWithGlassBar` 里挂了采样泵 —— 而 `AppScaffold` 是**二级页**用的，
    // 它的 backdrop **从来没有被 capture 过** ⇒ `snapshot` 恒为 null
    // ⇒ 顶栏模糊层永远走「没有位图就不画」分支 ⇒ **顶栏完全没有效果**。
    //
    // 现在这里自己挂一个泵（内部有节流，静止时几乎不耗电）。
    // ★ 2026-10-05：绑定宿主 View —— 采样器用 View.draw(Canvas) 只拓「需要的区域」。
    //   不绑定的话只能回落全屏 toImageBitmap()，卡顿依旧。
    val hostView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.LaunchedEffect(hostView, backdrop) {
        backdrop.bindHostView(hostView)
    }

    androidx.compose.runtime.LaunchedEffect(backdrop) {
        while (true) {
            backdrop.capture()
            kotlinx.coroutines.delay(80L)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = surfaceColor,
        topBar = {
            // ★★ 2026-10-05（用户要求「顶部 scaffold 应该同样一比一复刻」）：
            // 上一版用的是**实色顶栏**（miuix 在 blur 不可用时的降级路径），
            // 那不是 1:1。现在换成**真的渐变模糊**：GPU（GLSL ES 2.0）跑
            // 可分离高斯 + 沿高度衰减的强度，参数与高版本
            // `progressiveTextureBlur(blurRadius = 10f, gradient = ProgressiveBlur.Top.copy(curve = 2.2f))`
            // 逐项对应（见 LowBlurredTopBar 的对照表）。
            LowBlurredTopBar(
                title = title,
                onBack = onBack,
                surfaceColor = surfaceColor,
                backdrop = backdrop,
            )
        },
    ) { innerPadding ->
        val barInset = LocalBottomBarInset.current
        val barExtra = if (barInset != Dp.Unspecified) barInset else 0.dp
        // ★ 现在低版本也有渐变模糊层了（与高版本同款），顶栏 Box 多了 28dp 下撑；
        // Scaffold 的 innerPadding.top 也跟着变大 —— 这里减回去，
        // 保证「只有模糊多盖一段，内容一点不下移」（与高版本 AppScaffold 同逻辑）。
        val topBarInset = (innerPadding.calculateTopPadding() - 28.dp).coerceAtLeast(0.dp)
        CompositionLocalProvider(
            LocalScrollBottomLimit provides barExtra,
            LocalTopBarInset provides topBarInset,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    // 内容层录进低版本 backdrop，供底栏玻璃折射采样。
                    .lowLayerBackdrop(backdrop),
            ) {
                content(
                    PaddingValues(
                        top = 0.dp,
                        bottom = innerPadding.calculateBottomPadding() + bottomInset,
                    )
                )
            }
        }
    }
}
