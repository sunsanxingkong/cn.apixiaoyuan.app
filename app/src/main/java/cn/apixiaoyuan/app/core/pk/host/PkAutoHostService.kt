package cn.apixiaoyuan.app.core.pk.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import cn.apixiaoyuan.app.MainActivity
import cn.apixiaoyuan.app.R

/**
 * 后台挂机前台服务（★ 2026-10-03 新增）。
 *
 * ## 需求
 *
 * > 「加入后台挂机功能，需要悬浮窗保活，到时候就显示一个悬浮球」
 *
 * ## 为什么必须是「前台服务」而不是普通 Service
 *
 * Android 8.0 起，后台进程会被**积极回收**；普通 Service 在 App 退到后台后
 * 几十秒内就可能被杀。要让挂机真正持续，只有两条路：
 *
 *  1. **前台服务**（本类）—— 必须挂一条常驻通知，系统会大幅降低回收优先级；
 *  2. 悬浮窗 —— 持有可见窗口的进程同样不容易被回收（这一点常被忽略）。
 *
 * 两者**一起用**才是「保活」：通知让系统别杀，悬浮球让用户看得见、
 * 也让进程有可见窗口。用户要求的正是后者（悬浮球 = 那只笔）。
 *
 * ## 权限（两处，缺一不可）
 *
 *  | 权限 | 类型 | 怎么拿 |
 *  |---|---|---|
 *  | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | 普通权限（Manifest 声明即可） | — |
 *  | `POST_NOTIFICATIONS`（API 33+） | 运行时权限 | 需用户同意，否则通知不显示 → 前台服务会被降级 |
 *  | `SYSTEM_ALERT_WINDOW` | **特殊权限** | 只能跳系统设置页让用户手动开 |
 *
 * ## 「点击回到 App」怎么实现
 *
 * 悬浮球点击 → 起 [MainActivity]，**必须**带 `FLAG_ACTIVITY_NEW_TASK`
 * （Service 里没有 Activity 栈）与 `FLAG_ACTIVITY_SINGLE_TOP`
 * （避免把已有的 MainActivity 又建一个实例）。
 *
 * ## 通知点击也要能回 App
 *
 * 挂机时用户多半已经离开 App，最常见的入口是**点通知**而不是找悬浮球，
 * 所以通知里也挂同一个 PendingIntent。
 */
class PkAutoHostService : Service() {

    private var overlay: PkPenOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startHosting()
        }
        // ⚠️ START_STICKY：被系统杀掉后**自动重建**（挂机场景必须）。
        //    重建时 intent 为 null → 走 else 分支 → 重新挂通知 + 悬浮球。
        return START_STICKY
    }

    private fun startHosting() {
        running = true

        // 0) ★ 2026-10-03：先把**内置 node** 拉起来。
        //
        // 后台挂机的实质是「让 PK H5 一直跑在 WebView 里」，而那个页面来自
        // 内置 node（`http://127.0.0.1:8792/pk-h5/pk.html`）。服务没起，
        // 用户从悬浮球回到 App 时 PK 页就是白屏 —— 挂机等于没挂。
        //
        // ⚠️ 必须用 **startAsync**（内部走 Dispatchers.IO）：
        //    `onStartCommand` 跑在**主线程**，而启动流程里有
        //    ① 解压 2.5MB 工作区、② 最多 20s 的 HTTP 探活轮询。
        //    在主线程序列化执行会直接 **ANR**（我初版写的 startBlocking
        //    就是这个错，已改）。这里只是「提前把服务热起来」，
        //    晚一两秒就绪完全无害 —— 用户此刻还在别的界面。
        PkHostOrchestrator.startAsync(this)

        // 1) 前台通知（保活的地基）。API 34+ 起 startForeground 必须声明 type。
        runCatching {
            createChannelIfNeeded()
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIF_ID, n)
            }
        }.onFailure {
            // 27% 的失败场景是「通知权限没给」——此时前台服务起不来，
            // 但**仍继续**建悬浮球（悬浮球本身也有保活作用，且用户明确要它）。
            Log.w(TAG, "startForeground 失败（多为通知权限未授予）：${it.message}")
        }

        // 2) 悬浮球（用户要求的可见标志）。
        val ov = overlay ?: PkPenOverlay(this).also { overlay = it }
        ov.onClick = { bringAppToFront() }
        if (!ov.canDrawOverlays()) {
            Log.w(TAG, "缺少「显示在其他应用上层」权限 —— 悬浮球不会显示；请到设置里授予")
        }
        ov.show()

        Log.i(TAG, "后台挂机已启动（通知=${notifEnabled()}，悬浮球=${ov.isShowing}）")
    }

    /** 点击悬浮球 / 通知 → 回到 App 界面。 */
    private fun bringAppToFront() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    // Service 起 Activity 必须带 NEW_TASK，否则抛
                    // AndroidRuntimeException: Calling startActivity() from outside
                    // of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                },
            )
            Log.i(TAG, "已请求回到 App 界面")
        }.onFailure { Log.w(TAG, "回到 App 失败：${it.message}") }
    }

    override fun onDestroy() {
        running = false
        overlay?.hide()
        overlay = null
        Log.i(TAG, "后台挂机已停止")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 通知

    private fun notifEnabled(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return runCatching { nm.areNotificationsEnabled() }.getOrDefault(true)
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "PK 后台挂机",
                // LOW：不出提示音、不弹横幅 —— 挂机是长时间后台状态，
                // 用 DEFAULT 会吵人（而且部分 ROM 会因为「频繁打扰」提醒用户关掉它）。
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "刷 PK 局时的常驻通知（用于保活与快速回到 App）"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            // API 31+ 必须显式指定可变性；这里不需要被系统改写内容 → IMMUTABLE。
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PkAutoHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("PK 挂机中")
            .setContentText("点这里回到 App；悬浮球是那支笔")
            .setSmallIcon(R.drawable.ic_pk_pen)
            .setContentIntent(tap)
            .addAction(
                Notification.Action.Builder(null, "停止挂机", stop).build(),
            )
            .setOngoing(true)          // 不可滑动清除 —— 否则「保活」名存实亡
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "PkAutoHost"
        private const val CHANNEL_ID = "pk_auto_host"
        private const val NOTIF_ID = 0x9E21

        /** 外部（UI）用来开关挂机的 action。 */
        const val ACTION_START = "cn.apixiaoyuan.app.PK_HOST_START"
        const val ACTION_STOP = "cn.apixiaoyuan.app.PK_HOST_STOP"

        /** 当前是否在挂机（供 UI 读；进程内单实例，够用）。 */
        @Volatile
        var running: Boolean = false
            private set

        /**
         * 由 UI 调用：开关挂机。
         *
         * @return true = 已请求启动；false = 已请求停止
         */
        fun toggle(context: Context, on: Boolean): Boolean {
            val ctx = context.applicationContext
            val intent = Intent(ctx, PkAutoHostService::class.java).setAction(
                if (on) ACTION_START else ACTION_STOP,
            )
            return runCatching {
                if (on) {
                    // API 26+ 起 startService 在后台受限，必须用 startForegroundService。
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ctx.startForegroundService(intent)
                    } else {
                        ctx.startService(intent)
                    }
                } else {
                    ctx.stopService(intent)
                }
                on
            }.getOrElse {
                Log.w(TAG, "切换挂机失败：${it.message}")
                !on
            }
        }

        /** 当前是否挂机中。 */
        fun isRunning(): Boolean = running
    }
}