package cn.apixiaoyuan.app.core.pk.host

import android.content.Context
import android.util.Log
import cn.apixiaoyuan.app.core.log.AppLogger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * 内置 Linux **终端**（★ 2026-10-03 新增，用户要求「主页加入 linux 终端入口」）。
 *
 * # 它是什么
 *
 * 一个**长驻的 `/system/bin/sh` 子进程** + 一块滚动缓冲的文本输出。
 * 用户既能敲命令，也能看到 node 服务、pk-node 的运行痕迹。
 *
 * # 为什么不需要塞一个 Linux 发行版（重要）
 *
 * 用户最初的想法是「内置一个 linux 系统去 termux 看」。但实测发现：
 *
 * | 需要的东西 | Android 原生有没有 |
 * |---|---|
 * | shell 解释器 | ✅ `/system/bin/sh`（318KB，toybox 提供的 mksh） |
 * | 常用命令 | ✅ `/system/bin/toybox`（611KB，含 ls/cat/grep/ps/netstat/… 上百个 applet） |
 * | 跑 node | ✅ 已经有了（内置 node 就在 `nativeLibraryDir/libnode.so`） |
 *
 * 也就是说 **App 已经「内置 Linux」了** —— bionic libc + toybox + sh 就是 Android
 * 自带的用户态。再塞一个 Ubuntu rootfs 只是把同样的东西换成 200MB 的另一份副本，
 * 而且还要 proot 才能跑（国产 ROM 上常被限制）。所以这里直接复用系统的 sh。
 *
 * # 为什么用「常驻一个 sh」而不是「每条命令起一个进程」
 *
 *  - 常驻 sh 才能保留 `cd`、环境变量、以及 `PK_HOST=... node ...` 这类**会话状态**；
 *  - 每条命令都 exec 一次的话，用户没法「先 cd 再 ls」，也看不到交互式提示符。
 *
 * # 环境变量（与 [NodeRuntime] 对齐，这样在终端里能直接手动调试同一个服务）
 *
 * ```
 * PATH          = /system/bin:/system/xbin            ← toybox + sh
 * LD_LIBRARY_PATH = nativeLibraryDir:/system/lib64    ← node 的库 + 系统库
 * HOME/TMPDIR   = filesDir                            ← Android 私有目录可写
 * PK_NODE_DIR   = filesDir/pk-node                    ← pk-node 工作区（方便 cd 过去）
 * ```
 *
 * ⚠️ **刻意不设 `PK_DB` / `PK_PORT`**：那几个是给 [NodeRuntime] 启动服务用的；
 * 终端里若继承它们，用户手动 `node server.js` 会和 App 启动的那个**抢同一个 SQLite
 * 与端口**。让终端保持「干净」，要调服务时自己带参数（更明确，也不会互相踩）。
 *
 * # 线程与阻塞
 *
 * stdout 用一个守护线程读。**绝不能在主线程读** —— `read()` 在无输出时会一直阻塞。
 */
object TerminalSession {

    private const val TAG = "Terminal"

    /** 输出行数上限（超出丢最旧的）。滚动缓冲，避免长时间挂机把内存吃爆。 */
    private const val MAX_LINES = 2000

    /** 单行最大长度 —— 防止某条命令输出一坨二进制把 UI 卡死。 */
    private const val MAX_LINE_LEN = 4000

    /** 系统 shell。toybox 提供的 mksh；`-i` 交互模式才有提示符。 */
    private const val SHELL = "/system/bin/sh"

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: OutputStreamWriter? = null

    /** 输出（**行**为单位，最新在末尾）。 */
    private val lines = ArrayDeque<String>()

    private val lock = Any()

    /** 输出变化计数 —— 供 Compose 观察（`snapshotFlow` 不用，直接用快照读）。 */
    @Volatile
    var revision: Int = 0
        private set

    val isRunning: Boolean
        get() = process?.isAlive == true

    /**
     * 启动终端（幂等）。已启动直接返回 true。
     *
     * ⚠️ 内部会做 IO，**不要在主线程调**（用 [startAsync] 或自己在 IO 线程调）。
     */
    fun start(ctx: Context): Boolean {
        if (isRunning) return true
        val app = ctx.applicationContext
        return runCatching {
            val nativeDir = app.applicationInfo.nativeLibraryDir
            val home = app.filesDir
            val pb = ProcessBuilder(SHELL, "-i")
            pb.directory(home)
            pb.environment().apply {
                put("PATH", "/system/bin:/system/xbin")
                // ★ 与 NodeRuntime 完全一致：node 的库在前、系统库在后。
                //   这样终端里敲 `<nativeDir>/libnode.so server.js` 能直接跑起来。
                put("LD_LIBRARY_PATH", "$nativeDir:/system/lib64")
                put("HOME", home.absolutePath)
                put("TMPDIR", home.absolutePath)
                // 方便用户 `cd $PK_NODE_DIR`
                put("PK_NODE_DIR", File(home, "pk-node").absolutePath)
                put("PK_NODE_BIN", File(nativeDir, "libnode.so").absolutePath)
            }
            pb.redirectErrorStream(true)
            val p = pb.start()
            process = p
            writer = OutputStreamWriter(p.outputStream)

            // stdout / stderr（已合并）→ 滚动缓冲 + 文件日志。
            Thread {
                runCatching {
                    val r = BufferedReader(InputStreamReader(p.inputStream))
                    while (true) {
                        val line = r.readLine() ?: break
                        append(line)
                    }
                }
                // 进程退出（sh 被 kill / 崩了）：留一行痕迹，UI 才知道。
                append("[终端已退出]")
            }.apply { isDaemon = true; name = "pk-terminal" }.start()

            append("[终端已启动] $SHELL -i    cwd=${home.absolutePath}")
            append("[提示] 内置 node：\$PK_NODE_BIN；pk-node 工作区：\$PK_NODE_DIR")
            Log.i(TAG, "终端已启动 pidAlive=${p.isAlive}")
            true
        }.getOrElse {
            Log.e(TAG, "启动终端失败：${it.message}", it)
            append("[启动失败] ${it.message}")
            false
        }
    }

    /** 后台起（UI 用）。 */
    fun startAsync(ctx: Context) {
        if (isRunning) return
        Thread { start(ctx) }.apply { isDaemon = true; name = "pk-terminal-start" }.start()
    }

    /**
     * 执行一条命令。
     *
     * 实现就是**往常驻 sh 的 stdin 写一行**（等价于用户在终端里敲回车），
     * 所以 `cd` / 环境变量 / 前后依赖都自然保留。
     *
     * @return false = 终端没起来（调用方应提示）
     */
    fun run(command: String): Boolean {
        val w = writer ?: return false
        if (!isRunning) return false
        // 先把命令回显进缓冲 —— 否则用户看不出自己敲了什么。
        append("$ $command")
        return runCatching {
            w.write(command)
            w.write("\n")
            w.flush()
            true
        }.getOrElse {
            append("[写入失败] ${it.message}")
            false
        }
    }

    /** 停止终端。 */
    fun stop() {
        val p = process ?: return
        process = null
        writer = null
        runCatching {
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        append("[终端已停止]")
    }

    /** 清空输出缓冲。 */
    fun clear() {
        synchronized(lock) {
            lines.clear()
            revision++
        }
    }

    /** 输出快照（最新在末尾）。 */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    /** 追加一行并可观察。 */
    private fun append(line: String) {
        val safe = if (line.length > MAX_LINE_LEN) {
            line.substring(0, MAX_LINE_LEN) + "…（已截断）"
        } else {
            line
        }
        synchronized(lock) {
            lines.addLast(safe)
            // ⚠️ 不用 `removeFirst()`（Java 21 API / 需 API 35）；用 Kotlin 扩展。
            while (lines.size > MAX_LINES) lines.removeFirstOrNull()
            revision++
        }
        // 也落文件日志：挂机时终端输出是排障第一手材料。
        AppLogger.d(TAG, safe)
    }
}