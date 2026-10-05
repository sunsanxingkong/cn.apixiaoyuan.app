package cn.apixiaoyuan.app.core.pk.host

import android.content.Context
import android.util.Log
import cn.apixiaoyuan.app.core.log.AppLogger
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 内置 node 的**工作区**（pk-node 的代码本体）。
 *
 * ## 它与 [NodeRuntime] 的分工
 *
 * | 组件 | 内容 | 存放位置 | 为什么 |
 * |---|---|---|---|
 * | [NodeRuntime] | **可执行文件**（`libnode.so` + 16 个依赖库，120 MB） | APK 的 `jniLibs` → 安装后的 `nativeLibraryDir` | Android 10+ 的 W^X 只允许从 `nativeLibraryDir` 执行代码（详见 `app/build.gradle.kts` 里 `useLegacyPackaging` 的注释） |
 * | 本对象 | **代码本体**（`server.js` / `src/` / `bin/`，2.5 MB） | `filesDir/pk-node/`（首启解压） | 这些是**纯数据**（JS + `.so` 结尾的签名数据），只需读，不需执行 —— 不受 W^X 约束 |
 *
 * 分开的原因很实际：`nativeLibraryDir` 是**平铺、只读、安装时由系统解压**的，
 * 不可能在里面写 SQLite；而 `filesDir` 可写但不可执行。
 *
 * ## 为什么是「首启解压到私有目录」而不是「直接从 assets 读」
 *
 * - `assets/` 里的文件在 APK 内是**压缩态**，只能通过 `AssetManager.open()` 流式读，
 *   拿不到真实路径。而 `server.js` 里全是 `require('./src/xxx')` 的相对路径解析，
 *   以及 `fs.readFileSync(path.join(__dirname, …))` —— 必须有真实文件系统路径。
 * - 解压一次后持久化，之后启动**零拷贝**（靠 [stampFile] 里的版本+ZIP 哈希戳判定是否需要重解）。
 *
 * ## 版本戳机制（避免每次升级都白解压）
 *
 * `filesDir/pk-node/.workspace` 里存一份「来自哪个版本、哪份 ZIP 的包」。
 * 与 assets 中 ZIP 的 `package.json` 版本和 SHA-256 比对：都一致则跳过，
 * 任一变化就清空重解。这样即使 pk-node 在不改版本号的情况下更新，
 * 已安装 App 也不会继续复用旧工作区。
 *
 * ⚠️ 解压前**不清空 data 目录**（SQLite 与 secret 在那里）——只重写代码文件。
 * 否则用户每升一次级就丢一次设备链池与账号。
 */
object NodeWorkspace {

    private const val TAG = "NodeWorkspace"

    /** APK assets 里的工作区 zip。由 pk-node 的 `tools/export-app-workspace.js` 产出。 */
    private const val ASSET_ZIP = "pk-node-workspace.zip"

    /** 版本戳文件名（存 pk-node 的 version 与内置 ZIP 的 SHA-256）。 */
    private const val STAMP_NAME = ".workspace"

    /** 工作区目录名（位于 `filesDir` 下）。 */
    private const val DIR_NAME = "pk-node"

    /** 运行时数据目录名（SQLite / secret）。**不随代码重解而清空**。 */
    private const val DATA_DIR_NAME = "data"

    /** 解压结果。 */
    data class Result(
        /** 工作区根目录（含 `server.js`）。 */
        val dir: File,
        /** 本次是否真的解压了（false = 命中版本戳，已是最新）。 */
        val extracted: Boolean,
        /** 解出的文件数。 */
        val fileCount: Int,
        /** 来自哪个 pk-node 版本与内置 ZIP 哈希。 */
        val version: String?,
    )

    /**
     * 确保工作区就绪（幂等）。
     *
     * 必须在 [NodeRuntime.start] **之前**调用 —— 后者要检查 `server.js` 是否存在。
     * 这是个**阻塞**操作（首启约 2.5 MB 解压，实测 < 300 ms），
     * 调用方应在 IO 线程上执行。
     *
     * @return 解压结果；失败时 `dir` 仍返回目标目录（调用方据 [Result.extracted] 判）
     */
    fun ensureReady(ctx: Context): Result {
        val root = File(ctx.filesDir, DIR_NAME)
        val stamp = File(root, STAMP_NAME)

        val assetStamp = runCatching { readAssetStamp(ctx) }.getOrNull()
            ?: return Result(root, false, 0, null).also {
                Log.e(TAG, "assets/$ASSET_ZIP 读不到版本/哈希 —— 工作区不可能就绪")
            }

        // 命中的条件：戳存在、版本一致、且 server.js 真的在（防止上次解压到一半被杀）
        if (stamp.isFile &&
            stamp.readText().trim() == assetStamp &&
            File(root, "server.js").isFile
        ) {
            Log.i(TAG, "工作区已是最新 $assetStamp，跳过解压")
            return Result(root, false, 0, assetStamp)
        }

        return runCatching {
            extract(ctx, root, assetStamp)
        }.getOrElse {
            Log.e(TAG, "解压工作区失败：${it.message}", it)
            AppLogger.e(TAG, "解压内置 node 工作区失败：${it.message}")
            Result(root, false, 0, assetStamp)
        }
    }

    /** 读取 assets zip 的版本和内容哈希，避免同版本更新时复用旧工作区。 */
    private fun readAssetStamp(ctx: Context): String? {
        val bytes = ctx.assets.open(ASSET_ZIP).use { it.readBytes() }
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        ByteArrayInputStream(bytes).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name == "package.json") {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        // 不引 JSON 库：`"version"` 的位置是固定的，正则足够且无依赖。
                        val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
                            .find(text)?.groupValues?.get(1)
                        if (version != null) return "$version:$sha256"
                    }
                    zip.closeEntry()
                }
            }
        }
        return null
    }

    /**
     * 解压 assets 里的 zip 到 [root]。
     *
     * ## 安全：必须防 Zip Slip
     *
     * 条目名来自 zip，理论上可以是 `../../xxx`。这里用
     * `canonicalPath.startsWith(rootCanonical)` 兜住 ——
     * 虽然这个 zip 是我们自己打的，但**读外部输入一律校验**是底线。
     *
     * ## 为什么先删旧代码文件而不是整个目录
     *
     * 整个目录里有 `data/`（SQLite + secret）。删了 = 用户升一次级丢一次账号。
     * 所以只删「本次 zip 里会覆盖的那些顶层条目」，`data/` 天然不在其中。
     */
    private fun extract(ctx: Context, root: File, stamp: String): Result {
        root.mkdirs()
        val rootCanonical = root.canonicalPath
        val dataDir = File(root, DATA_DIR_NAME).apply { mkdirs() }

        // 先清掉旧的**代码**条目（顶层清单固定，见 pk-node 的 export-app-workspace.js）。
        // 不清的话，旧版本里被删掉的模块会残留并被 require 到（缓存/误引用）。
        listOf("server.js", "src", "bin", "package.json").forEach { name ->
            val f = File(root, name)
            if (f.exists()) {
                val ok = f.deleteRecursively()
                if (!ok) Log.w(TAG, "旧条目删除失败（继续）：${f.absolutePath}")
            }
        }

        var count = 0
        ctx.assets.open(ASSET_ZIP).use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    // 目录条目（本 zip 不含，但防御性处理）
                    if (name.endsWith("/")) { zip.closeEntry(); continue }
                    // 防御：绝不覆盖 data/（SQLite 与 secret 在里面）
                    if (name == DATA_DIR_NAME || name.startsWith("$DATA_DIR_NAME/")) {
                        zip.closeEntry(); continue
                    }

                    val target = File(root, name)
                    val canonical = target.canonicalPath
                    if (!canonical.startsWith(rootCanonical + File.separator)) {
                        Log.w(TAG, "跳过越界条目（Zip Slip 防御）：$name")
                        zip.closeEntry(); continue
                    }

                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        while (true) {
                            val n = zip.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    count++
                    zip.closeEntry()
                }
            }
        }

        // 版本戳最后写：中途被杀 → 戳不存在 → 下次重解（幂等，不会半成品上线）。
        File(root, STAMP_NAME).writeText(stamp)

        Log.i(TAG, "工作区已解压 $stamp：$count 个文件 → ${root.absolutePath}")
        AppLogger.i(TAG, "内置 node 工作区已解压 $stamp（$count 个文件）")
        // data 目录显式建一下：NodeRuntime 会往这里写 SQLite，不能等 node 自己建
        // （万一权限/路径问题，早点暴露在日志里）。
        dataDir.mkdirs()
        return Result(root, true, count, version)
    }
}
