package cn.apixiaoyuan.app.core.design.glass.low

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp as lerpFloat
import cn.apixiaoyuan.app.core.design.glass.animation.DampedDragAnimation
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.flow.collectLatest

/**
 * **低版本开关** —— `kyant/LiquidToggle` 的**降级实现**（API 24–32）。
 *
 * ★★ 2026-10-05（用户指令）：
 *
 * > 「算了低版本还是做降级处理吧」
 *
 * 此前低版本给开关做过整套液态玻璃（自建采样源 + 模糊 + 折射 + 高光），
 * 但开关只有 64×28dp、玻璃可见部分极小，采样开销（每个开关一个采样泵 + 全屏抓图）
 * 与收益完全不成比例。现在降级为**纯色滑块**（保留全部交互与动画）：
 *
 *  - 轨道：`lerp(trackColor, accentColor, fraction)` 纯色渐变（与上游同式）；
 *  - 滑块：`thumbSurface` 实色胶囊 + 柔和阴影（无采样、无模糊、无折射）；
 *  - 拖拽/弹簧/点击逻辑与上游**逐字一致**（`DampedDragAnimation`），只是不再渲染玻璃。
 *
 * 供 `kyant/LiquidToggle` 的低版本分支调用（签名与旧版相同，调用方无需改动）。
 */
@Composable
internal fun LowLiquidToggle(
    selected: () -> Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {

    // ★ 2026-10-04：`DampedDragAnimation` 的回调是**构造时捕获**的（`remember` 只跑一次），
    //   直接闭包引用会永久持有首帧的 lambda —— 开关数量多、每个都带自己的
    //   `onCheckedChange` 时，改成别的行就会调错回调。
    //   用 `rememberUpdatedState` 让它每次读到的都是最新的。
    val currentSelected = rememberUpdatedState(selected)
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentEnabled = rememberUpdatedState(enabled)

    // ★ 莫奈色源（替代上游硬编码苹果绿/灰）：
    val colors = MiuixTheme.colorScheme
    val accentColor = colors.primary
    val trackColor = colors.surfaceContainerHighest
    val thumbSurface = colors.surfaceContainerHigh

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20f.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (selected()) 1f else 0f) }

    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                if (didDrag) {
                    if (!currentEnabled.value) {
                        fraction = if (currentSelected.value()) 1f else 0f
                        didDrag = false
                        return@DampedDragAnimation
                    }
                    fraction = if (targetValue >= 0.5f) 1f else 0f
                    currentOnSelect.value(fraction == 1f)
                    didDrag = false
                } else {
                    if (!currentEnabled.value) return@DampedDragAnimation
                    fraction = if (currentSelected.value()) 0f else 1f
                    currentOnSelect.value(fraction == 1f)
                }
            },
            onDrag = { _, dragAmount ->
                if (!didDrag) didDrag = dragAmount.x != 0f
                val delta = dragAmount.x / dragWidth
                fraction =
                    if (isLtr) (fraction + delta).fastCoerceIn(0f, 1f)
                    else (fraction - delta).fastCoerceIn(0f, 1f)
            },
        )
    }

    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }.collectLatest { dampedDragAnimation.updateValue(it) }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { currentSelected.value() }.collectLatest { isSelected ->
            val target = if (isSelected) 1f else 0f
            if (target != fraction) {
                fraction = target
                dampedDragAnimation.animateToValue(target)
            }
        }
    }

    Box(
        modifier.then(if (enabled) Modifier else Modifier.alpha(0.38f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        // ---- 轨道：纯色，随 fraction 从灰渐变到主题色（与上游同式）----
        Box(
            Modifier
                .clip(RoundedCornerShape(percent = 50))
                .drawBehind {
                    val f = dampedDragAnimation.value
                    drawRect(lerp(trackColor, accentColor, f))
                }
                .size(64.dp, 28.dp),
        )
        // ---- 滑块：纯色胶囊 + 柔和阴影（降级：无玻璃采样） ----
        Box(
            Modifier
                .graphicsLayer {
                    val f = dampedDragAnimation.value
                    val padding = 2f.dp.toPx()
                    translationX =
                        if (isLtr) lerpFloat(padding, padding + dragWidth, f)
                        else lerpFloat(-padding, -(padding + dragWidth), f)
                }
                .then(dampedDragAnimation.modifier)
                .drawBehind {
                    // 柔和阴影（近似）：两层半透明黑圆，偏移在滑块下缘。
                    val r = size.minDimension / 2f
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.10f),
                        radius = r + 0.75.dp.toPx(),
                        center = Offset(cx, cy + 2.25.dp.toPx()),
                    )
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.16f),
                        radius = r,
                        center = Offset(cx, cy + 1.5.dp.toPx()),
                    )
                }
                .clip(RoundedCornerShape(percent = 50))
                .background(thumbSurface)
                .size(40.dp, 24.dp),
        )
    }
}