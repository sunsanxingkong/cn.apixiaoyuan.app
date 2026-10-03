package cn.apixiaoyuan.app.feature.oldsimian

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.apixiaoyuan.app.core.pk.host.PkAutoHostService
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「后台挂机（悬浮球保活）」开关 —— 抽成独立 Composable，因为它现在挂在
 * **「功能」tab**（原来是刷 PK 对局页里的一个卡片）。
 *
 * # 为什么搬到功能 tab（用户 2026-10-03 要求）
 *
 * > 「还有后台挂机在练习应该也同样适用才对，把后台悬浮窗的开关做进 tab 栏功能里
 * >   而不是放到刷分」
 *
 * 理由很实在：这个开关**与「刷什么」无关** ——
 * 它只做两件事（前台服务常驻通知 + 可见悬浮球），目的是**让 App 进程别被系统回收**，
 * 于是 PK 刷局、练习刷对局、刷分三条链路的协程都能在后台继续跑。
 * 放在「刷 PK 对局」页里会让人误以为只对 PK 生效。
 *
 * # 为什么要单独一个组件（而不是内联进 OldSimianScreen）
 *
 * 这里面有 4 个状态 + 一个 lifecycle observer + 一次跳系统设置的交互，
 * 内联会把页面文件撑得很难读。抽出来后 [OldSimianScreen] 里只有一行调用。
 *
 * # 权限引导（关键）
 *
 * 悬浮窗是**特殊权限**（`SYSTEM_ALERT_WINDOW`），只能跳系统设置页让用户手动开。
 * 不给权限时表现是「开关打开了但球没出来」—— 必须在这里把原因说清楚，
 * 否则用户只会觉得「坏了」。
 */
@Composable
fun AutoHostSwitchCard() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(PkAutoHostService.isRunning()) }
    var note by remember { mutableStateOf<String?>(null) }

    // 从系统设置页返回时刷新一次状态（用户可能刚授完悬浮窗权限）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) on = PkAutoHostService.isRunning()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "后台挂机（悬浮球保活）",
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
                Text(
                    // 注意：Compose 的 Text 不解析 markdown，别写 ** 强调符。
                    text = "开启后：① 前台服务常驻通知（系统不轻易回收）；" +
                        "② 屏幕上多出一支背景透明的笔（可拖动，点击回到 App）。" +
                        "两者叠加才算保活 —— 只有通知或只有悬浮球都压不住后台回收。" +
                        "对 PK 刷局 / 练习刷对局 / 刷分都有效。",
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
            }
        }
        Button(
            onClick = {
                val want = !PkAutoHostService.isRunning()
                if (want && !Settings.canDrawOverlays(ctx)) {
                    // 没权限就引导，别假装启动成功。
                    note = "先去系统设置里打开「显示在其他应用上层」，再回来点一次"
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + ctx.packageName),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    return@Button
                }
                PkAutoHostService.toggle(ctx, want)
                on = want
                note = if (want) {
                    "已开启：状态栏常驻通知 + 屏幕上多出一支笔" +
                        "（对 PK 刷局 / 练习刷对局 / 刷分**都**有效）"
                } else {
                    "已关闭：悬浮球与通知都已撤下"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (on) "关闭后台挂机" else "开启后台挂机")
        }
        note?.let {
            Text(text = it, color = MiuixTheme.colorScheme.primary)
        }
    }
}
