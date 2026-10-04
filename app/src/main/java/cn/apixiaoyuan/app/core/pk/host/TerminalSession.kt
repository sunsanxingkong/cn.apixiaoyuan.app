package cn.apixiaoyuan.app.core.pk.host

import android.content.Context
import android.util.Log
import cn.apixiaoyuan.app.core.log.AppLogger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * 内置 Linux **终端会话**（★ 2026-10-03 新增；同日改为「一个实例 = 一个终端」）。
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
 * # ★ 多终端（2026-10-03 追加，用户要求「创建新终端页面」）
 *
 * 本类从 `object` 改成 **class**，一个实例 = 一个独立终端（独立 sh 进程、独立
 * 输出缓冲、独立 pid）。会话的持有与切换由 [TerminalManager] 负责。
 *
 * 这是**必须**的：单例 object 意味着「全 App 只有一块终端缓冲」，
 * 想「开一个新终端去跑 node，同时保留原来那个 tail 日志」根本做不到。
 *
 * # ★ Ctrl+C 是怎么实现的（2026-10-03 追加，含真实踩坑）
 *
 * 直觉做法是「拿到前台子进程的 pid，给它发 SIGINT」。这里有三层坑，
 * 全部是**在真机上实测**出来的（不是推测）：
 *
 *  ## 坑 1：`Process.pid()` 在 Android 上不存在
 *
 *  `java.lang.Process.pid()` 是 **Java 9+** API，Android 只有 Java 8 那套
 *  （本项目的 `NodeRuntime` 也栽过同一个坑）。所以**无法从 `Process` 对象
 *  拿到 sh 的 pid**。
 *
 *  **解法**：启动时让 shell 自己把 pid **写进文件** ——
 *  ```sh
 *  /system/bin/sh -c 'echo $$ > <pidfile>; exec /system/bin/sh -i'
 *  ```
 *  关键在 `exec`：它**替换进程映像而不是 fork**，所以 pid 不变，
 *  pidfile 里记的就是那个交互 sh 的 pid。
 *
 *  ## 坑 2：`/proc/<pid>/task/<pid>/children` 在 Android 上读不到
 *
 *  这是 Linux 上「列出子进程」的标准做法，但 Android 实测
 *  `children_file_UNREADABLE`（内核就把它做成不可读，`ps`/`/proc` 两条路都堵）。
 *  **解法**：改用 `ps -o PID,PPID`。
 *  实测在 Android 上**同 uid 能列出自己的子进程**（`ps -o PID,PPID` 会输出全系统
 *  进程列表，其中包含 PPID == 当前 sh 的那几条），用 `awk '$2==p'` 过滤即可。
 *
 *  ## 坑 3：`sh -i` 走的是**管道**，没有 tty → 没有 job control
 *
 *  所以 sh 自己不知道「谁是前台任务」，也没有 `^C` 按键事件可转发。
 *  只能由 App 侧「枚举 PPID==shPid 的子进程 → 逐个 `kill -INT`」。
 *
 *  ## 为什么不干脆给 sh 自己发 SIGINT
 *
 *  管道模式下 sh 收到 SIGINT 可能直接**退出**（而不是「中断当前行、回到提示符」），
 *  那样用户按一次 Ctrl+C 就把整个终端弄死了。所以**只发给子进程**。
 *
 *  ## 安全边界（如实说明）
 *
 *  这是「**近似** Ctrl+C」：会中断 sh 的**所有**子进程，包括用户自己 `&` 放后台的。
 *  真正的 job control 需要 PTY，那是另一项工程（当时明确列为不做）。
 *
 * # 环境变量（与 [NodeRuntime] 对齐，这样在终端里能直接手动调试同一个服务）
 *
 * ```
 * PATH            = /system/bin:/system/xbin          ← toybox + sh
 * LD_LIBRARY_PATH = nativeLibraryDir:/system/lib64    ← node 的库 + 系统库
 * HOME/TMPDIR     = filesDir                          ← Android 私有目录可写
 * PK_NODE_DIR     = filesDir/pk-node                  ← pk-node 工作区（方便 cd 过去）
 * PK_TERM_ID      = 本会话编号（多终端时方便在日志里区分）
 * ```
 *
 * ⚠️ **刻意不设 `PK_DB` / `PK_PORT`**：那几个是给 [NodeRuntime] 启动服务用的；
 * 终端里若继承它们，用户手动 `node server.js` 会和 App 启动的那个**抢同一个 SQLite
 * 与端口**。让终端保持「干净」，要调服务时自己带参数（更明确，也不会互相踩）。
 *
 * # 线程与阻塞
 *
 * stdout 用一个守护线程读。**绝不能在主线程读** —— `read()` 在无输出时会一直阻塞。
 * [interrupt] 内部会 fork 一个 `ps`，同样不能在主线程做。
 */
class TerminalSession internal constructor(
    /** 会话编号（从 1 开始）。用作 chip 标题与 [AppLogger] 的 tag 后缀。 */
    val id: Int,
    ctx: Context,
) {

    private val appContext: Context = ctx.applicationContext

    /** 外壳 `sh -i` 的 pid（从 pidfile 读出；-1 = 还不知道）。 */
    @Volatile
    private var shellPid: Int = -1

    private val pidFile = File(appContext.filesDir, "terminal/term-$id.pid")

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: OutputStreamWriter? = null

    /** 输出（**行**为单位，最新在末尾）。 */
    private val lines = ArrayDeque<String>()

    private val lock = Any()

    /** 输出变化计数 —— 供 Compose 观察（不用 Flow，直接读快照）。 */
    @Volatile
    var revision: Int = 0
        private set

    val isRunning: Boolean
        get() = ProcessCompat.isAlive(process)

    /** UI 上的标题。 */
    val title: String get() = "终端 $id"

    /** 给 [AppLogger] 用的 tag（与旧版 `Terminal` 兼容，多会话时带 id）。 */
    private val logTag: String get() = if (id == 1) TAG else "$TAG#$id"

    /**
     * 启动终端（幂等）。已启动直接返回 true。
     *
     * ⚠️ 内部会做 IO，**不要在主线程调**（用 [startAsync] 或自己在 IO 线程调）。
     */
    fun start(): Boolean {
        if (isRunning) return true
        return runCatching {
            val nativeDir = appContext.applicationInfo.nativeLibraryDir
            val home = appContext.filesDir
            pidFile.parentFile?.mkdirs()
            // 先把旧 pidfile 清掉，避免读到上一次的 stale pid（那会误杀别人的进程）。
            runCatching { pidFile.delete() }

            // ★ `echo $$ > pidfile; exec sh -i`
            //   - `$$` 是**外层** sh 的 pid；
            //   - `exec` 替换映像、pid 不变 → pidfile 里就是最终那个交互 sh 的 pid。
            //   这是绕开「Android 没有 Process.pid()」的唯一干净办法（见类 KDoc 坑 1）。
            val inner = "echo \$\$ > ${pidFile.absolutePath}; cd ${home.absolutePath}; exec $SHELL -i"
            val pb = ProcessBuilder(SHELL, "-c", inner)
            pb.directory(home)
            pb.environment().apply {
                put("PATH", "$home/bin:/system/bin:/system/xbin")
                // ⚠️ 刻意**不把 nativeLibraryDir 放进 LD_LIBRARY_PATH**（2026-10-04 修）。
                //
                // # 症状
                // 终端里敲 `curl www.baidu.com` 报：
                //   CANNOT LINK EXECUTABLE "curl": cannot locate symbol
                //   "EVP_MD_CTX_create" referenced by "/system/bin/curl"
                //
                // # 真因
                // 原来写的是 `LD_LIBRARY_PATH=$nativeDir:/system/lib64` —— nativeDir 里
                // 有 node 自己的 `libcrypto.so`（OpenSSL 3，已去掉 EVP_MD_CTX_create 这类旧符号）。
                // 动态链接器**按目录顺序找**，于是 `/system/bin/curl` 被强行塞了 node 的
                // libcrypto → 找不到旧符号 → 直接崩。
                // 这是「用全局 LD_LIBRARY_PATH 图省事」的经典副作用：它会影响**所有**子进程。
                //
                // # 正确做法
                // 什么都不设 —— `/system/lib64` 本来就在动态链接器的默认搜索路径里，
                // 系统命令（curl / toybox / sh）自然用系统库。
                //
                // 想跑内置 node 时，**按次指定**即可（不污染其他命令）：
                //   LD_LIBRARY_PATH=$NODE_LIB $PK_NODE_BIN server.js
                // 为此下面仍导出 `PK_NODE_LIB`。
                put("LD_LIBRARY_PATH", "")
                put("HOME", home.absolutePath)
                put("TMPDIR", home.absolutePath)
                // 方便用户 `cd $PK_NODE_DIR`
                put("PK_NODE_DIR", File(home, "pk-node").absolutePath)
                put("PK_NODE_BIN", File(nativeDir, "libnode.so").absolutePath)
                /** node 的库目录 —— 跑 node 时临时用：`LD_LIBRARY_PATH=$PK_NODE_LIB $PK_NODE_BIN x.js` */
                put("PK_NODE_LIB", nativeDir)
                put("PK_TERM_ID", id.toString())
                // ⚠️ 刻意**不设 PS1**：真机实测（Android 15 / mksh）**完全忽略**
                //   `PS1=...` 环境变量 —— 设了反而让启动时的 `sh -i` 打一堆
                //   「can't find tty fd」告警。提示符仍用系统默认 `$`。
            }
            // ★ 合并 stderr：只影响我们读到的顺序，不影响上面那些环境变量。
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
                        // ★ 2026-10-04：过滤 `sh -i` 在**非 tty** 下的两条固有噪声。
                        //
                        // 我们的 sh 是挂在管道上的（没有 pty），mksh 一启动必然打：
                        //   /system/bin/sh: can't find tty fd: No such device or address
                        //   /system/bin/sh: warning: won't have full job control
                        // 这两条**不代表出错**（Ctrl+C 我们是用 SIGINT 扫子进程实现的，
                        // 见 interrupt()，本来就不依赖 job control），但会糊在用户第一眼
                        // 看到的位置。只屏蔽这两条精确匹配，其他 stderr 照常显示。
                        if (line.contains("can't find tty fd") ||
                            line.contains("won't have full job control")
                        ) continue
                        append(line)
                    }
                }
                // 进程退出（sh 被 kill / 崩了）：留一行痕迹，UI 才知道。
                append("[终端已退出]")
            }.apply { isDaemon = true; name = "pk-terminal-${this@TerminalSession.id}" }.start()

            append("[终端 $id 已启动] $SHELL -i    cwd=${home.absolutePath}")
            append("[提示] Ctrl+C 中断当前命令；内置 node：执行 node（已替你设好库路径）；工作区：\$PK_NODE_DIR")
            // ★ 2026-10-04（用户反馈 `curl` 报 symbol 错 + 提示符错行后补）：
            //   给内置 node 一个**包装脚本**，避免「库路径全局污染」和「每次都要手打
            //   LD_LIBRARY_PATH」。它是可执行的 shell 脚本，放在 filesDir（可执行、非 W^X 目录）。
            installNodeWrapper(nativeDir, home)
            // ★ 2026-10-04（用户要求）：每个**新终端会话**开头打一个 `sxd` 字符画。
            //   放在「已启动 / 提示」之后，这样启动信息仍是最先出现的，
            //   字符画作为「新会话」的视觉分隔（多开终端时一眼能看出哪块是哪次）。
            SXD_ART.forEach { append(it.padEnd(ART_WIDTH)) }
            append("")
            Log.i(logTag, "终端已启动 pidAlive=${ProcessCompat.isAlive(p)}")
            true
        }.getOrElse {
            Log.e(logTag, "启动终端失败：${it.message}", it)
            append("[启动失败] ${it.message}")
            false
        }
    }
/** 后台起（UI 用）。 */
    fun startAsync() {
        if (isRunning) return
        Thread { start() }.apply { isDaemon = true; name = "pk-terminal-start-${this@TerminalSession.id}" }.start()
    }

    /**
     * 安装内置 node 的**包装脚本** `files/bin/node`（★ 2026-10-04）。
     *
     * # 为什么需要
     * 内置 node（`libnode.so`）依赖同目录下一堆库（`libcxx_node.so` 等），必须设
     * `LD_LIBRARY_PATH=$nativeDir` 才能起来。但**不能**把这个变量放进全局环境 ——
     * 那会让系统命令（`curl` / `toybox`）被强行挂上 node 的 `libcrypto`，
     * 报 `cannot locate symbol "EVP_MD_CTX_create"`（本轮用户实测就是这个坑）。
     *
     * 折中且标准的做法：写一个只给 node 自己用的包装脚本，
     *   - 在**脚本内部**设 `LD_LIBRARY_PATH`，作用域仅限该次 node 进程；
     *   - 放到 `files/bin` 并加进 `PATH` → 终端里直接敲 `node` 即可。
     *
     * 脚本落在 `filesDir`（App 私有、可执行、不受 W^X 限制），每次启动覆盖写（幂等）。
     */
    private fun installNodeWrapper(nativeDir: String, home: File) {
        runCatching {
            val binDir = File(home, "bin").apply { mkdirs() }
            val f = File(binDir, "node")
            val script = buildString {
                append("#!/system/bin/sh\n")
                append("# 内置 Node 包装器（App 自动生成，勿手改）\n")
                append("export LD_LIBRARY_PATH=\"$nativeDir\"\n")
                append("exec \"$nativeDir/libnode.so\" \"\$@\"\n")
            }
            f.writeText(script)
            f.setExecutable(true, false)
        }.onFailure { Log.w(logTag, "写 node 包装脚本失败：${it.message}") }
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

    /**
     * 近似 Ctrl+C：给本终端 `sh` 的**所有子进程**发 `SIGINT`。
     *
     * 实现细节与三层踩坑见类 KDoc 的「Ctrl+C 是怎么实现的」。
     * 这里只强调两点：
     *  - **不给 sh 自己发**（管道模式下它会整个退出，而不是回到提示符）；
     *  - 内部要 fork `ps`，所以**自己开线程**（按钮点击在主线程）。
     *
     * @return false = 终端没起来 / 拿不到 pid（调用方给个提示）
     */
    fun interrupt(): Boolean {
        val p = process ?: return false
        if (!ProcessCompat.isAlive(p)) return false
        val hostPid = readShellPid()
        if (hostPid <= 0) {
            append("[Ctrl+C] 还拿不到 shell 的 pid（稍后再试一次）")
            return false
        }
        append("[Ctrl+C] 中断 shell($hostPid) 的子进程…")
        Thread {
            runCatching {
                val kids = childPids(hostPid)
                if (kids.isEmpty()) {
                    append("[Ctrl+C] 没有正在运行的子进程")
                    return@runCatching
                }
                kids.forEach { killPid(it, "-INT") }
                append("[Ctrl+C] 已向 ${kids.size} 个进程发 SIGINT：${kids.joinToString(" ")}")
            }.onFailure {
                append("[Ctrl+C] 失败：${it.message}")
            }
        }.apply { isDaemon = true; name = "pk-terminal-int-${this@TerminalSession.id}" }.start()
        return true
    }

    /**
     * 列出 [hostPid] 的**直接**子进程 pid。
     *
     * ⚠️ 不能用 `/proc/<pid>/task/<pid>/children` —— Android 上那个文件读不到
     * （实测 `children_file_UNREADABLE`），只能靠 `ps -o PID,PPID` 过滤。
     */
    private fun childPids(hostPid: Int): List<Int> {
        // awk 的程序体必须用**单引号**（交给 sh，不被 Kotlin 展开）；
        // Kotlin 侧把 `$` 写成 `\$`，否则会被当成模板插值。
        val script = "ps -o PID,PPID 2>/dev/null | awk -v p=$hostPid '\$2==p {print \$1}'"
        val probe = ProcessBuilder(SHELL, "-c", script).redirectErrorStream(true).start()
        val out = probe.inputStream.bufferedReader().use { it.readText() }
        probe.waitFor()
        return out.split(Regex("\\s+"))
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 && it != hostPid }
    }

    /** 给单个 pid 发信号（`signal` 形如 `-INT` / `-9`）。 */
    private fun killPid(pid: Int, signal: String) {
        runCatching { ProcessBuilder(SHELL, "-c", "kill $signal $pid").start().waitFor() }
    }

    /** 从 pidfile 读 shell 的 pid（读不到返回 -1）。 */
    private fun readShellPid(): Int {
        shellPid.takeIf { it > 0 }?.let { return it }
        val v = runCatching {
            if (!pidFile.canRead()) return@runCatching -1
            pidFile.readText().trim().toIntOrNull() ?: -1
        }.getOrDefault(-1)
        if (v > 0) shellPid = v
        return v
    }

    /**
     * 停止终端（异步）。
     *
     * ⚠️ **不要在 UI 线程做完整停止**：里面有 `p.waitFor(2s)` 与 `Thread.sleep`，
     * 会直接掉帧。所以对外只暴露异步版本，UI / 管理器都用它。
     *
     * ⚠️ 顺序是踩出来的（每一条都对应一个真 bug）：
     *  1. **先 SIGINT 子进程** —— 正常中断，让它们有机会自己收拾；
     *  2. 等一小会儿；
     *  3. **还活着的补 SIGKILL** —— 必须**在 sh 死之前**做！父进程一死，
     *     子进程会被 reparent 到 init（PPID=1），`ps --ppid` 就再也找不到它们了，
     *     于是变成孤儿继续占着端口（下次启动 node 就报「端口被占用」）；
     *  4. 最后才 `destroy()` sh 自己。
     */
    fun stop() {
        val p = process ?: return
        val hostPid = readShellPid()
        process = null
        writer = null
        shellPid = -1
        append("[终端 $id 已停止]")
        Thread { stopBlocking(p, hostPid) }
            .apply { isDaemon = true; name = "pk-terminal-stop-${this@TerminalSession.id}" }
            .start()
    }

    /** 异步「重启」：停止 + 启动**串在同一个线程**，避免 stop 还在清理时 start 已经在写 pidfile。 */
    fun restartAsync() {
        val p = process
        val hostPid = readShellPid()
        process = null
        writer = null
        shellPid = -1
        append("[终端 $id 重启中…]")
        Thread {
            if (p != null) stopBlocking(p, hostPid)
            start()
        }.apply { isDaemon = true; name = "pk-terminal-restart-${this@TerminalSession.id}" }.start()
    }

    /** 真正的停止流程（阻塞，只能在后台线程调）。 */
    private fun stopBlocking(p: Process, hostPid: Int) {
        runCatching {
            if (hostPid > 0) {
                val kids = childPids(hostPid)
                if (kids.isNotEmpty()) {
                    append("[终端 $id] 中断子进程：${kids.joinToString(" ")}")
                    kids.forEach { killPid(it, "-INT") }
                }
                // 给它们一点时间自己退（SIGINT 是可以被忽略/捕获的）。
                Thread.sleep(300)
                val left = childPids(hostPid)
                if (left.isNotEmpty()) {
                    append("[终端 $id] SIGINT 未奏效，补 SIGKILL：${left.joinToString(" ")}")
                    left.forEach { killPid(it, "-9") }
                }
            }
        }
        runCatching {
            p.destroy()
            if (!ProcessCompat.waitFor(p, 2, java.util.concurrent.TimeUnit.SECONDS)) ProcessCompat.destroyForcibly(p)
        }
        runCatching { pidFile.delete() }
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
        AppLogger.d(logTag, safe)
    }

    private companion object {
        const val TAG = "Terminal"

        /** 输出行数上限（超出丢最旧的）。滚动缓冲，避免长时间挂机把内存吃爆。 */
        const val MAX_LINES = 2000

        /** 单行最大长度 —— 防止某条命令输出一坨二进制把 UI 卡死。 */
        const val MAX_LINE_LEN = 4000

        /** 系统 shell。toybox 提供的 mksh；`-i` 交互模式才有提示符。 */
        const val SHELL = "/system/bin/sh"

        /**
         * 新终端会话开头的 **`SXD` 字符画**（★ 2026-10-04，用户要求）。
         *
         * > 「把终端每个新界面开头用字符输出一个「sxd」的字符画。」
         * > 「用 `/ _` 画」　「大写的 D」
         *
         * 用 `/` `_` `|` `\` `<` `>` 这套「斜杠体」拼（比 `#` 点阵更像终端 banner），
         * 5 行高、三个字母各 8 宽 + 1 空格 = **每行 26 字符**。
         *
         * ⚠️ 行尾空格是点阵的一部分，**不要被格式化吃掉**；
         * 打印时统一 `padEnd(ART_WIDTH)` 兜底。
         *
         * ```text
         *   _____  __   __   _____
         *  / ____| \ \ / /  |  __ \
         * | (___    \ V /   | |  \ \
         *  \___ \    > <    | |__/ /
         * |_____/   /_/\_\   |_____/
         * ```
         */
        val SXD_ART: List<String> = listOf(
            "  _____  __   __   _____  ",
            " / ____| \\ \\ / /  |  __ \\ ",
            "| (___    \\ V /   | |  \\ \\",
            " \\___ \\    > <    | |__/ /",
            "|_____/   /_/\\_\\  |_____/ ",
        )

        /** [SXD_ART] 每行的标准宽度（见上面的说明）。 */
        const val ART_WIDTH = 26
    }
}

/**
 * 终端会话管理器（★ 2026-10-03）。
 *
 * 负责：持有所有 [TerminalSession]、切换当前会话、增删会话。
 *
 * # 为什么需要它（而不是继续用单例 object）
 *
 * 用户要求「创建新终端」—— 即**同时**开多个终端（典型用法：
 * 一个终端 `tail` 日志、另一个跑 node、再一个敲零散命令）。
 * 单例 object 只有一块缓冲，物理上做不到。
 *
 * # 会话是**进程内**的（不落盘、不跨 App 重启）
 *
 * 有意如此：终端进程本来就跟 App 同生共死（App 被系统杀掉，sh 子进程也活不了），
 * 把会话列表持久化只会得到一堆「指向死进程」的空壳，反而更让人困惑。
 *
 * # 变更通知
 *
 * Compose 那边靠 [listRevision] 轮询感知（与 [TerminalSession.revision] 同一套路）。
 */
object TerminalManager {

    private const val TAG = "TerminalMgr"

    /** 最多允许多少个终端。防止用户手滑连点开出一屏 chip 把内存吃光。 */
    const val MAX_SESSIONS = 6

    private val sessions = mutableListOf<TerminalSession>()

    /** 会话列表变化计数（新增/关闭时 ++）。 */
    @Volatile
    var listRevision: Int = 0
        private set

    /** 当前选中的会话编号（0 = 还没有）。 */
    @Volatile
    var activeId: Int = 0
        private set

    /** 会话列表快照（按 id 升序）。 */
    fun list(): List<TerminalSession> = synchronized(sessions) { sessions.toList() }

    /** 当前会话（没有则 null）。 */
    fun active(): TerminalSession? {
        val snapshot = list()
        return snapshot.firstOrNull { it.id == activeId } ?: snapshot.firstOrNull()
    }

    /** 切换到指定会话。 */
    fun select(id: Int) {
        if (list().any { it.id == id }) activeId = id
    }

    /**
     * 确保至少有一个终端（进页面时调用，幂等）。
     *
     * @return 当前会话
     */
    fun ensureStarted(ctx: Context): TerminalSession {
        active()?.let {
            it.startAsync()
            return it
        }
        return createNew(ctx) ?: error("ensureStarted 失败：会话已满（不该发生）")
    }

    /**
     * 新建一个终端并切过去。
     *
     * @return 新会话；已到 [MAX_SESSIONS] 上限时返回 null（调用方给提示）。
     */
    fun createNew(ctx: Context): TerminalSession? {
        val s = synchronized(sessions) {
            if (sessions.size >= MAX_SESSIONS) return null
            // id 用「最小未占用」，这样关掉中间的会话后编号不会一直涨。
            var id = 1
            while (sessions.any { it.id == id }) id++
            TerminalSession(id, ctx).also {
                sessions += it
                sessions.sortBy { s2 -> s2.id }
            }
        }
        activeId = s.id
        listRevision++
        s.startAsync()
        Log.i(TAG, "新建终端 #${s.id}（当前 ${list().size} 个）")
        return s
    }

    /**
     * 关闭一个终端。若关的是当前会话，自动切到最近的一个。
     *
     * 最后一个**不允许关**（页面总得有东西可显示）；要清就让用户按「重启」。
     */
    fun close(id: Int) {
        synchronized(sessions) {
            val idx = sessions.indexOfFirst { it.id == id }
            if (idx < 0 || sessions.size <= 1) return
            val removed = sessions.removeAt(idx).also { runCatching { it.stop() } }
            if (activeId == id) {
                activeId = sessions.minByOrNull { kotlin.math.abs(it.id - id) }?.id ?: 0
            }
            listRevision++
            Log.i(TAG, "关闭终端 #${removed.id}（剩 ${sessions.size} 个）")
        }
    }

    /** 关停所有终端。 */
    fun stopAll() {
        val all = list()
        all.forEach { runCatching { it.stop() } }
        synchronized(sessions) { sessions.clear() }
        activeId = 0
        listRevision++
    }
}