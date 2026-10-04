package cn.apixiaoyuan.app.core.pk.host

import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * `java.lang.Process` 的 API 24 兼容层。
 *
 * # 为什么要它
 *
 * 把 minSdk 从 33 降到 24（Android 7）后，lint 的 `NewApi` 检查揪出了 9 处
 * **API 26 才有的 `Process` 方法** —— 它们在本项目里全是「起子进程 → 等 → 杀」
 * 的关键路径（内置 node、终端会话）：
 *
 * | 方法 | 起始 API | 本文件的等价实现 |
 * |---|---|---|
 * | `Process.isAlive()` | 26 | `exitValue()` 抛不抛 `IllegalThreadStateException` |
 * | `Process.waitFor(timeout, unit)` | 26 | 20ms 轮询 + 截止时间 |
 * | `Process.destroyForcibly()` | 26 | `destroy()`（低版本没有更强的杀法） |
 *
 * # 语义差异（如实说明）
 *
 *  - `isAlive`：**完全等价**。`Process` 的「活着」定义就是「`exitValue()` 会抛异常」，
 *    这是 Java 原生语义，不是模拟。
 *  - `waitFor(timeout)`：**行为等价，精度略低**（最多多等一个轮询间隔 20ms）。
 *    且它不会真正 interrupt 子进程 —— 与 API 26 的原生实现一致。
 *  - `destroyForcibly`：低版本**没有** SIGKILL 等价物，`destroy()` 发的是 SIGTERM。
 *    对 node / shell 这类会正常响应 SIGTERM 的进程没差别；极少数卡死不响应信号的
 *    进程在 Android 7 上可能杀不掉（这是平台能力缺失，不是实现问题）。
 */
internal object ProcessCompat {

    /** 轮询间隔（毫秒）—— 20ms 在「响应速度」与「CPU 占用」之间取平衡。 */
    private const val POLL_INTERVAL_MS = 20L

    /** 等价 `Process.isAlive()`（API 26+）。null 一律视为「不活着」。 */
    fun isAlive(p: Process?): Boolean {
        if (p == null) return false
        return if (Build.VERSION.SDK_INT >= 26) {
            p.isAlive
        } else {
            try {
                p.exitValue()
                false
            } catch (e: IllegalThreadStateException) {
                true
            }
        }
    }

    /** 等价 `Process.waitFor(timeout, unit)`（API 26+）。返回 true = 超时前已结束。 */
    fun waitFor(p: Process, timeout: Long, unit: TimeUnit): Boolean {
        if (Build.VERSION.SDK_INT >= 26) {
            return p.waitFor(timeout, unit)
        }
        val deadlineNanos = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadlineNanos) {
            if (!isAlive(p)) return true
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                // 调用方被中断：恢复中断标记并按「没等到」返回（与原生 waitFor 一致）。
                Thread.currentThread().interrupt()
                return !isAlive(p)
            }
        }
        return !isAlive(p)
    }

    /** 等价 `Process.destroyForcibly()`（API 26+）。低版本退化为 `destroy()`。 */
    fun destroyForcibly(p: Process): Process {
        return if (Build.VERSION.SDK_INT >= 26) {
            p.destroyForcibly()
        } else {
            p.destroy()
            p
        }
    }
}