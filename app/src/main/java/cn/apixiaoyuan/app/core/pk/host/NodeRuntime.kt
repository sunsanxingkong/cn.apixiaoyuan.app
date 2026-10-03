package cn.apixiaoyuan.app.core.pk.host

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 内置 Node 运行时（★ 2026-10-03）。
 *
 * ## 它是什么
 *
 * 在 App 进程外跑一个**真的 Node**（`libnode.so`），用它提供 pk-node 的
 * 全部能力（PK H5 页面 / 刷局 / 刷练习 / 设备链池 …），
 * App 内的 WebView 直接访问 `http://127.0.0.1:<port>`。
 *
 * ## 为什么能跑（关键机制）
 *
 * `libnode.so` 其实是 **Termux 编译的 Android 版 node 二进制**，
 * ELF 解释器是 `/system/bin/linker64` —— 它本来就是 Android 可执行文件。
 * 之所以叫 `.so`，是因为 Android 10+ 的 **W^X** 只允许从
 * `nativeLibraryDir` 执行代码，而那里只接受 `lib*.so` 命名
 * （详见 `app/build.gradle.kts` 里 `useLegacyPackaging` 的注释）。
 *
 * ## 环境变量（缺一不可）
 *
 * - `LD_LIBRARY_PATH` = `nativeLibraryDir` + `/system/lib64`
 *   node 的 16 个依赖库都在前者；**必须带上后者**，否则 DNS 解析会
 *   `dlopen("/system/lib64/libc.so")` 失败并 SIGABRT。
 * - `PK_HOST` = `127.0.0.1` —— 只监听本机（内置服务不需要对外）。
 * - `PK_PORT` = 见 [DEFAULT_PORT] —— 固定端口，App 据此拼 H5 URL。
 * - `PK_DB` = App 私有目录 —— 数据落 App 里，卸载即清。
 * - `PK_LINK_TOKEN` = 固定值 —— 见 pk-node 的 `/api/link` 系列接口；**固定**它 App 才能调联动。
 * - `PK_SKIP_NATIVE` = `1` —— 跳过 arm64 native 自检（那些库在 App 里不可用；sign 已是纯 JS）。
 */
object NodeRuntime {

    private const val TAG = "NodeRuntime"

    /** 内置服务端口。固定值，App 用它拼 H5 URL。 */
    const val DEFAULT_PORT = 8792

    /** node 可执行文件在 nativeLibraryDir 里的名字（就是 node 二进制）。 */
    private const val NODE_SO = "libnode.so"

    @Volatile
    private var process: Process? = null

    /** 是否已启动（进程活着）。 */
    val isRunning: Boolean
        get() = process?.isAlive == true

    /** node 可执行文件路径（nativeLibraryDir/libnode.so）。 */
    private fun nodeExe(ctx: Context): File =
        File(ctx.applicationInfo.nativeLibraryDir, NODE_SO)

    /**
     * 运行时的库目录（nativeLibraryDir）。
     *
     * ⚠️ **必须额外带上 `/system/lib64`**：
     * Android 版 node 的 DNS resolver（c-ares）在首次解析时会
     * `dlopen("/system/lib64/libc.so")` 拿 bionic 的符号；
     * 只给 nativeLibraryDir 会失败并 **SIGABRT 整个进程**。
     */
    private fun libPath(ctx: Context): String =
        ctx.applicationInfo.nativeLibraryDir + ":/system/lib64"

    /**
     * 启动内置服务。幂等 —— 已在跑就直接返回 true。
     *
     * @param workspaceDir pk-node 代码本体的目录（含 server.js）
     * @param linkToken    联动令牌（与 App 侧调 handshake 用的必须一致）
     * @return true = 已启动（或本来就在跑）
     */
    fun start(ctx: Context, workspaceDir: File, linkToken: String): Boolean {
        if (isRunning) return true

        val exe = nodeExe(ctx)
        if (!exe.exists()) {
            Log.e(TAG, "找不到 node 可执行文件：${exe.absolutePath}（jniLibs 的 libnode.so 没打进包？）")
            return false
        }
        val server = File(workspaceDir, "server.js")
        if (!server.exists()) {
            Log.e(TAG, "找不到 server.js：${server.absolutePath}")
            return false
        }

        val dataDir = File(workspaceDir, "data").apply { mkdirs() }

        return runCatching {
            val pb = ProcessBuilder(
                exe.absolutePath,
                server.absolutePath,
            )
            pb.directory(workspaceDir)
            pb.environment().apply {
                // ★ 库搜索路径：nativeLibraryDir + /system/lib64（见 libPath 的注释）
                put("LD_LIBRARY_PATH", libPath(ctx))
                // 只监听本机 —— 内置服务不该被局域网访问到
                put("PK_HOST", "127.0.0.1")
                put("PK_PORT", DEFAULT_PORT.toString())
                put("PK_DB", File(dataDir, "pk-node.sqlite").absolutePath)
                put("PK_SECRET", File(dataDir, "secret.key").absolutePath)
                put("PK_LINK_TOKEN", linkToken)
                // 跳过 arm64 native 自检：那些 so 在 App 环境下加载不了；
                // sign 与内容编码都已是纯 JS（见 pk-node 的 src/native.js）。
                put("PK_SKIP_NATIVE", "1")
                // TZ：Android 没有 /usr/share/zoneinfo，留给 bionic 处理
                put("TZ", java.util.TimeZone.getDefault().id)
            }
            pb.redirectErrorStream(true)
            val p = pb.start()
            process = p

            // 后台把 stdout 转到日志（不读会阻塞子进程）
            Thread {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        Log.i(TAG, line)
                    }
                }
            }.apply { isDaemon = true; name = "pk-node-stdout" }.start()

            // 注意：Android 上没有 java.lang.Process.pid()（那是 Java 9+ API），
            // 所以这里不打 pid，只报存活状态与工作目录。
            Log.i(TAG, "内置 node 已启动：alive=${p.isAlive} port=$DEFAULT_PORT dir=${workspaceDir.absolutePath}")
            true
        }.getOrElse {
            Log.e(TAG, "启动内置 node 失败：${it.message}", it)
            false
        }
    }

    /** 停止内置服务。 */
    fun stop() {
        val p = process ?: return
        process = null
        runCatching {
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }.onFailure { Log.w(TAG, "停止内置 node 失败：${it.message}") }
        Log.i(TAG, "内置 node 已停止")
    }

    /**
     * 等内置服务就绪（轮询 `/api/system` 直到有响应）。
     *
     * 为什么不只等端口：node 起 listener 之后还要初始化 DB/各种桥，
     * 立刻访问 H5 会拿到 500。用一次真实 HTTP 探活更可靠。
     *
     * @param timeoutMs 最长等待
     * @return true = 已就绪
     */
    fun awaitReady(timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning) return false
            if (probeOnce()) return true
            runCatching { Thread.sleep(300) }
        }
        return false
    }

    /** 探一次端口是否可用。 */
    private fun probeOnce(): Boolean = runCatching {
        val conn = (URL("http://127.0.0.1:$DEFAULT_PORT/").openConnection() as HttpURLConnection).apply {
            connectTimeout = 800
            readTimeout = 800
            requestMethod = "GET"
        }
        try {
            // 200 或 302 都算「服务活着」
            conn.responseCode in 200..399
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)
}