package cn.apixiaoyuan.app.core.design.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * H5 容器的**加载动画**（★ 2026-10-04，用户要求「顺便给页面来个加载动画」）。
 *
 * # 为什么用「脉冲圆点」而不是转圈
 *
 * 已有的 CircularProgressIndicator 是 Material 默认样式（细线条），而这两个 H5 容器
 * 背景是**网页自己的底色**（H5PageColor 探测出来，可能是白也可能是黑）——
 * 细线条在深色页面上对比度很差。圆点是**实心块**，按底色取对比色画，两种底色下都清楚。
 *
 * # 交互细节
 *
 * 每颗点按相位依次「亮起 / 放大」，相位用 0→3 的无限循环驱动，
 * 与当前相位距离越近越亮越大。
 *
 * 注意：只在**首屏加载**期间显示（由调用方控制），不要常驻。
 */
@Composable
fun H5LoadingDots(
    /** 页面底色 —— 用来决定点的颜色（深底用白点，浅底用深点）。 */
    pageColor: Color,
    modifier: Modifier = Modifier,
) {
    val dot = h5LoadingDotColor(pageColor)
    // 无限循环：0f→3f 驱动「依次亮起」的相位。
    val transition = rememberInfiniteTransition(label = "h5-loading")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "h5-loading-phase",
    )

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { i ->
                // 每颗点与当前相位的距离 → 0(最亮) … 1(最暗)
                val d = abs(phase - i).coerceAtMost(1f)
                val scale = 1f - 0.35f * d
                val alpha = 1f - 0.7f * d
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .scale(scale)
                        .alpha(alpha)
                        .background(dot, CircleShape),
                )
            }
        }
    }
}

/**
 * 加载动效的颜色：取页面底色的**对比色**。
 *
 * 判据用感知亮度 Y = 0.299R + 0.587G + 0.114B：
 * 深底 → 半透明白；浅底 → 半透明深灰。
 * 用半透明而不是纯黑/纯白，是为了不喧宾夺主（它只是个过渡态）。
 */
private fun h5LoadingDotColor(pageColor: Color): Color {
    val y = 0.299f * pageColor.red + 0.587f * pageColor.green + 0.114f * pageColor.blue
    return if (y < 0.5f) Color.White.copy(alpha = 0.75f) else Color(0xFF444444).copy(alpha = 0.6f)
}
