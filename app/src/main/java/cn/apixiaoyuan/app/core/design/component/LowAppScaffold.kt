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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = surfaceColor,
        topBar = {
            LowTopBar(title = title, onBack = onBack, surfaceColor = surfaceColor)
        },
    ) { innerPadding ->
        val barInset = LocalBottomBarInset.current
        val barExtra = if (barInset != Dp.Unspecified) barInset else 0.dp
        // 低版本没有 BlurOverhang，顶栏 inset 就是 Scaffold 给的原值。
        val topBarInset = innerPadding.calculateTopPadding().coerceAtLeast(0.dp)
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

/** 低版本顶栏：实色（miuix 在 blur 不可用时给出的降级形态），无渐变模糊。 */
@Composable
private fun LowTopBar(
    title: String,
    onBack: (() -> Unit)?,
    surfaceColor: Color,
) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        SmallTopAppBar(
            title = title,
            modifier = Modifier.fillMaxWidth(),
            color = surfaceColor,
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = AppIcons.Back,
                            contentDescription = "返回",
                        )
                    }
                }
            },
        )
    }
}