package cn.apixiaoyuan.app.core.pk.host

import android.content.Context
import android.util.Log
import cn.apixiaoyuan.app.core.log.AppLogger
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
        get() = ProcessCompat.isAlive(process)

    /** node 可执行文件路径（nativeLibraryDir/libnode.so）。 */
    private fun nodeExe(ctx: Context): File =
        File(ctx.applicationInfo.nativeLibraryDir, NODE_SO)

    /**
     * 运行时的库目录。
     *
     * ## ★★ 为什么是「nativeLibraryDir + /system/lib64」且**这个顺序**
     *
     * 两个目录各司其职，缺一不可：
     *
     * | 目录 | 提供什么 |
     * |---|---|
     * | `nativeLibraryDir` | node 自己的 14 个库（`libnode.so` / `libicudata.so` / `libcrypto.so` / `libcxx_node.so` / `libsqlite3.so` / `libz.so` / `libcares.so` / `libffi.so` / libicu* / libssl …）|
     * | `/system/lib64` | **Android 系统库**：`libc.so` / `libm.so` / `libdl.so` / `liblog.so`（还有 `libandroid.so` / `libmediandk.so` / `libjnigraphics.so` 等由 node 的运行时代码按需 dlopen）|
     *
     * ## ⚠️⚠️ 真机崩溃教训（2026-10-03，`exitCode=134` SIGABRT）
     *
     * 早先我们把 Termux 包里的 `libc.so` / `libm.so` / `libdl.so` / `liblog.so`
     * **也打包进了 APK**（因为当时是在 proot 沙箱里「凑齐依赖」跑通的）。
     * 真机上服务能起来、能监听，但**收到第一个 HTTP 请求就 abort**：
     *
     * ```
     * [http] GET /pk-h5/pk.html
     * dlopen failed: TLS symbol "(null)" in dlopened
     *   "/apex/com.android.runtime/lib64/bionic/libc.so"
     *   referenced from "/apex/com.android.runtime/lib64/bionic/libc.so"
     *   using IE access model
     * 内置 node 已退出：exitCode=134
     * ```
     *
     * 机理：node 处理请求时会 `dlopen` 系统的 bionic libc（APEX 路径），
     * 而进程里**已经**加载了一份来自 Termux 的 `libc.so` —— 两套 libc 的
     * TLS（线程局部存储）访问模型冲突 → 链接器直接 abort。
     *
     * 为什么「监听能起来、第一个请求才崩」：`listen()` 之后 HTTP 层才会
     * 触发那些 dlopen（DNS / TLS / 图形等），之前用不到。
     *
     * **修法**：把这 4 个系统库**从 APK 里删掉**（不是改名、不是换顺序 ——
     * 只要文件在 `nativeLibraryDir` 里就会被加载器优先解析，改名也没用），
     * 让它们统一走 `/system/lib64`。
     *
     * 反证：`libandroid.so` / `libmediandk.so` 当时**没**打包，靠系统解析 ——
     * 它们一直工作正常，正说明「系统库就该由系统提供」。
     *
     * ## 为什么还要显式写上 `/system/lib64`
     *
     * 用 `linker64` 直接执行非标准路径的 ELF 时，系统**不会**自动补
     * `/system/lib64`（那是 `LD_LIBRARY_PATH` 空时才有的默认行为，
     * 而这里 `LD_LIBRARY_PATH` 非空 → 默认搜索路径被整体替换）。
     * 所以必须显式列出，否则连 `libc.so` 都找不到。
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
            logE("找不到 node 可执行文件：${exe.absolutePath}（jniLibs 的 libnode.so 没打进包？）")
            return false
        }
        val server = File(workspaceDir, "server.js")
        if (!server.exists()) {
            logE("找不到 server.js：${server.absolutePath}")
            return false
        }

        val dataDir = File(workspaceDir, "data").apply { mkdirs() }

        return runCatching {
            val pb = ProcessBuilder(exe.absolutePath, server.absolutePath)
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

            // ★★ node 的 stdout/stderr **必须落盘**（2026-10-03 教训）。
            //
            // 初版只打 `android.util.Log`，而 logcat 是**环形缓冲**、很快被冲掉；
            // 真机排障时 `grep NodeRuntime` 在文件日志里**一个字都没有** ——
            // 只能看到上层那句「端口没响应」，完全不知道 node 侧发生了什么
            // （CANNOT LINK / 缺库 / 端口占用 / 崩溃…）。
            //
            // 现在：logcat + 文件日志（AppLogger）双写。
            // pk-node 自己会打印自检结果、监听地址、错误原因，这些是最关键的证据。
            Thread {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        Log.i(TAG, line)
                        // pk-node 的启动横幅很重要：自检结果 / 监听地址 / 联动令牌。
                        if (line.isNotBlank()) AppLogger.i(TAG, line)
                    }
                }
            }.apply { isDaemon = true; name = "pk-node-stdout" }.start()

            // 进程退出也要记 —— 「起来了又秒退」是最难查的一类。
            Thread {
                runCatching {
                    val code = p.waitFor()
                    AppLogger.w(TAG, "内置 node 已退出：exitCode=$code")
                }
            }.apply { isDaemon = true; name = "pk-node-wait" }.start()

            logI("内置 node 已启动：alive=${ProcessCompat.isAlive(p)} port=$DEFAULT_PORT dir=${workspaceDir.absolutePath}")
            true
        }.getOrElse {
            logE("启动内置 node 失败：${it.message}", it)
            false
        }
    }

    /** 停止内置服务。 */
    fun stop() {
        val p = process ?: return
        process = null
        runCatching {
            p.destroy()
            if (!ProcessCompat.waitFor(p, 3, TimeUnit.SECONDS)) ProcessCompat.destroyForcibly(p)
        }.onFailure { Log.w(TAG, "停止内置 node 失败：${it.message}") }
        logI("内置 node 已停止")
    }

    /**
     * 等内置服务就绪。
     *
     * ## ★ 探测哪一个路径（2026-10-03 真机 bug 的根因）
     *
     * 初版探的是 **根路径 `/`**，判定 `200..399` 算就绪 —— 而
     * **pk-node 根本没有 `/` 路由，返回 404**，于是：
     * 服务明明已经在正常监听（`netstat` 可见 `127.0.0.1:8792 LISTEN`），
     * 却被判为「没响应」，白等满超时后报「内置服务启动失败」。
     * 真机截图就是这个症状。
     *
     * 现在的判定：
     *  1. 先看**进程还活着**（死了立刻返回，不用等满超时）；
     *  2. 探 `/api/link/handshake`（**不需要登录**，只要带对令牌就 200，
     *     见 pk-node `server.js` 的 `needAuth` 豁免名单）。
     *
     * 返回码判定放宽为 **< 500 即算「HTTP 栈活着」**：
     * 哪怕令牌不对（403）、路径变了（404），也说明 listener 已经在服务请求了 ——
     * 「就绪」要回答的问题是「端口通不通」，不是「业务对不对」。
     *
     * @param timeoutMs 最长等待
     * @return true = 已就绪
     */
    fun awaitReady(timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastCode = -1
        while (System.currentTimeMillis() < deadline) {
            // 进程死了就别等了 —— 直接失败，把错误留给上层日志
            if (!isRunning) {
                logE("等待就绪期间 node 进程已退出（见上方 node 输出日志）")
                return false
            }
            lastCode = probe()
            if (lastCode in 1..499) return true
            runCatching { Thread.sleep(300) }
        }
        logE("等待就绪超时（${timeoutMs}ms），最后一次探测返回=$lastCode")
        return false
    }

    /** 探一次服务。返回 HTTP 状态码；连不上返回 -1。 */
    private fun probe(): Int = runCatching {
        val conn = (URL("http://127.0.0.1:$DEFAULT_PORT/api/link/handshake")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 800
            readTimeout = 800
            requestMethod = "GET"
            // 带上令牌，让 403 变成 200（更干净地证明整条链路通）
            setRequestProperty("X-PK-Link", PkNodeLink.LINK_TOKEN)
        }
        try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(-1)

    private fun logI(msg: String) {
        Log.i(TAG, msg)
        AppLogger.i(TAG, msg)
    }

    private fun logE(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        AppLogger.e(TAG, msg, t)
    }
}