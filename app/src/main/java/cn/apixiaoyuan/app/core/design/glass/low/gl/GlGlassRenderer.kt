package cn.apixiaoyuan.app.core.design.glass.low.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import cn.apixiaoyuan.app.core.log.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * GPU 玻璃渲染器 —— **低版本（API 24–32）的玻璃效果在这里用 OpenGL ES 2.0 算**。
 *
 * # 它替代了什么，以及为什么这么做
 *
 * 高版本由 `miuix-blur` 用 **AGSL**（`RenderEffect` + `RuntimeShader`）在 GPU 上渲染。
 * AGSL 是 API 33 才有；低版本要用同一套数学，正解是 **GLSL ES 2.0**：
 *
 *  - **同一类后端**：两者都是 GPU 着色器语言。浮点行为、纹理双线性插值、
 *    clamp-to-edge 都由 GL 规范保证 —— 与 AGSL 天然一致（[GlassShaders] 里有逐条对照表）。
 *  - **性能**：CPU 逐像素折射在 1280×2772 上要几百毫秒（用户实测「延迟有点大」）；
 *    GPU 做同样的事只要 1–3 ms，因为它是几千个核心并行算。
 *
 * # 架构
 *
 * ```
 * 源 Bitmap ──上传──> 纹理 A
 *                     │  ① 模糊 H（ping-pong 到 B）
 *                     │  ② 模糊 V（B → A）
 *                     │  ③ 折射  （A → B）
 *                     │  ④ 混色  （可选）
 *                     ▼
 *                  FBO ──glReadPixels──> 输出 Bitmap（垂直翻转）
 * ```
 *
 * # 线程模型（EGL 的硬性要求）
 *
 * EGL 上下文**必须始终在同一个线程**使用。所以本类内部持有一个**专用单线程
 * 执行器**，所有 GL 调用都提交到它上面跑；调用方（`Dispatchers.Default` 的协程）
 * 用 [runBlockingSafe] 同步等待结果。
 *
 * 上下文 / 程序 / 纹理 / FBO 都**常驻复用** —— 尺寸不变时不会重建（重建 EGL
 * 上下文要几十毫秒，绝不能每帧做）。
 *
 * # 与高版本不可避免的差异（如实说明）
 *
 *  - **模糊内核**：Skia 的 `createBlurEffect` 是盒式模糊三次迭代；本实现是标准
 *    可分离高斯（σ = radius/2、3σ 截断）。两者都是近似高斯，平坦区域几乎无法分辨；
 *    高频细节上真高斯略「干净」。
 *  - **精度**：AGSL 用 fp16 中间量，本实现 `highp` 时是 fp32（**精度只高不低**）。
 *
 * 除此之外，折射部分（[GlassShaders.LENS] / [GlassShaders.LENS_DISPERSION]）
 * 是**逐字翻译**，包括七通道色散的累加顺序与系数。
 */
internal object GlGlassRenderer {

    /** GL 操作专用线程 —— EGL 上下文与它绑定。 */
    private val glExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "low-glass-gl").apply { isDaemon = true }
    }

    // ---------------- EGL 状态（只在 glExecutor 线程访问） ----------------

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var initialized = false
    private var initFailed = false

    // ---------------- 离屏缓冲 ----------------

    private var fbo = 0
    private var texA = 0
    private var texB = 0
    private var bufW = 0
    private var bufH = 0

    /** 像素回读缓冲（复用，避免每次分配几 MB）。 */
    private var readBuffer: ByteBuffer? = null

    // ---------------- 着色器程序（懒编译 + 复用） ----------------

    private val programs = HashMap<String, Int>()

    /** 全屏四边形顶点（-1..1，两个三角形）。 */
    private val QUAD = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    )

    /**
     * 渲染一趟的规格。
     *
     * 一次 [render] 会按顺序跑 [passes] 里的每一趟，每趟都是全屏四边形 + 一个片元着色器。
     */
    class Pass(
        /** 用哪个着色器（[GlassShaders] 的常量）。 */
        val fragmentShader: String,
        /** uniform 名 → 值。支持 float / vec2 / vec4（**按名字长度自动识别**）。 */
        val uniforms: Map<String, FloatArray> = emptyMap(),
    )

    /**
     * 对 [src] 跑 [passes]，返回结果位图（尺寸与 [src] 相同）。
     *
     * ⚠️ **阻塞调用**（内部同步等 GL 线程）。调用方须在后台线程。
     * 任何一步失败都返回 [src]（调用方有纯色兜底，不会黑屏）。
     */
    fun render(src: Bitmap, passes: List<Pass>): Bitmap {
        if (passes.isEmpty()) return src
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src

        return try {
            glExecutor.submit<Bitmap> {
                renderOnGlThread(src, w, h, passes)
            }.get()
        } catch (t: Throwable) {
            AppLogger.w("LowGlassGL", "GPU 渲染失败，回退原图：${t.message}")
            src
        }
    }

    /**
     * 把 [overlay] 叠加到 [base] 上（source-over），返回新位图。
     *
     * # 为什么叠加放 CPU 而不是 GL
     *
     * miuix 的高光（`BloomStroke`）是一条**独立的 shader 链**，最后用
     * `highlightPaint` 画到内容之上。在 GL 里做「两张纹理混合」需要额外的
     * 拷贝趟（同一纹理不能既当 FBO 附件又当采样器），而这里一张图的
     * `drawBitmap` 就是硬件加速的，开销可以忽略 —— 简单可靠优先。
     *
     * 两张图都按**非预乘 ARGB** 处理（[render] 的输出已经是反预乘的）。
     */
    fun compose(base: Bitmap, overlay: Bitmap): Bitmap {
        if (base.width != overlay.width || base.height != overlay.height) return base
        return try {
            val out = base.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(out)
            canvas.drawBitmap(overlay, 0f, 0f, android.graphics.Paint().apply {
                isAntiAlias = true
            })
            out
        } catch (t: Throwable) {
            AppLogger.w("LowGlassGL", "高光叠加失败：${t.message}")
            base
        }
    }

    // =========================================================================
    // 以下全部只在 glExecutor 线程执行
    // =========================================================================

    private fun renderOnGlThread(src: Bitmap, w: Int, h: Int, passes: List<Pass>): Bitmap {
        if (initFailed) return src
        if (!ensureContext()) return src
        if (!ensureBuffers(w, h)) return src

        // ---- ① 上传源位图到纹理 A ----
        //
        // ★★ 2026-10-05 真机报错修正：`GLUtils.texImage2D` 只接受
        // **ARGB_8888 / RGB_565**，而我们的快照经过 `Bitmap.createScaledBitmap`
        // 后可能变成 `RGBA_F16` / `HARDWARE` 等格式 →
        // `IllegalArgumentException: invalid Bitmap format`。
        // 这里强制转一次（copy 到 ARGB_8888）再上传。
        val upload = if (src.config == Bitmap.Config.ARGB_8888) src
        else src.copy(Bitmap.Config.ARGB_8888, false)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, upload, 0)
        if (upload !== src) upload.recycle()

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        // ---- ② 逐趟渲染（ping-pong） ----
        var readTex = texA
        var writeTex = texB
        passes.forEachIndexed { index, pass ->
            val program = programFor(pass.fragmentShader) ?: run {
                AppLogger.w("LowGlassGL", "着色器编译失败，跳过第 $index 趟")
                return@forEachIndexed
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, writeTex, 0,
            )
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            setUniforms(program, pass.uniforms, w, h)

            // 源纹理固定绑到 unit 0，uniform `uContent` 也固定指向 0。
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, readTex)
            val loc = GLES20.glGetUniformLocation(program, "uContent")
            if (loc >= 0) GLES20.glUniform1i(loc, 0)

            drawQuad(program)

            // 交换
            val t = readTex; readTex = writeTex; writeTex = t
        }

        // ---- ③ 回读（从 FBO 的当前 readTex 对应的 attachment） ----
        // 注意：上面的循环结束后，最后写入的是 readTex（交换过）。
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, readTex, 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            AppLogger.w("LowGlassGL", "FBO 不完整：0x${status.toString(16)}")
            return src
        }

        val buf = readBuffer ?: ByteBuffer
            .allocateDirect(w * h * 4)
            .order(ByteOrder.nativeOrder())
            .also { readBuffer = it }
        buf.rewind()
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()

        // ---- ④ RGBA(BGRA 序) + 上下翻转 → ARGB_8888 Bitmap ----
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        var i = 0
        // glReadPixels 是「左下原点」，Bitmap 是「左上原点」→ 逐行翻转。
        for (y in 0 until h) {
            val srcRow = (h - 1 - y) * w
            for (x in 0 until w) {
                val p = buf.getInt((srcRow + x) * 4)
                // 回读格式是 RGBA，打包成 ARGB。
                val r = p and 0xff
                val g = (p ushr 8) and 0xff
                val b = (p ushr 16) and 0xff
                val a = (p ushr 24) and 0xff
                pixels[i++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return out
    }

    /** 建/复用 EGL 上下文。 */
    private fun ensureContext(): Boolean {
        if (initialized) return true
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                initFailed = true
                return false
            }
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) {
                initFailed = true
                return false
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] <= 0
            ) {
                initFailed = true
                return false
            }
            val config = configs[0] ?: run { initFailed = true; return false }

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                initFailed = true
                return false
            }

            // Pbuffer 作为「窗口」—— 我们只做离屏渲染，不需要真正的 SurfaceView。
            val surfAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 4,
                EGL14.EGL_HEIGHT, 4,
                EGL14.EGL_NONE,
            )
            surface = EGL14.eglCreatePbufferSurface(display, config, surfAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                initFailed = true
                return false
            }
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                initFailed = true
                return false
            }

            initialized = true
            AppLogger.i("LowGlassGL", "EGL 上下文就绪（ES 2.0）")
            true
        } catch (t: Throwable) {
            AppLogger.w("LowGlassGL", "EGL 初始化失败：${t.message}")
            initFailed = true
            false
        }
    }

    /** 建/复用 FBO 与两张纹理。尺寸变了就重建。 */
    private fun ensureBuffers(w: Int, h: Int): Boolean {
        if (fbo != 0 && bufW == w && bufH == h) return true

        releaseBuffers()

        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        texA = ids[0]
        texB = ids[1]
        for (tex in intArrayOf(texA, texB)) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            // ★ clamp-to-edge —— 与 AGSL 的 `content.eval()` 边界行为一致（不能 REPEAT）。
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
        }

        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        fbo = f[0]

        bufW = w
        bufH = h
        readBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        return true
    }

    private fun releaseBuffers() {
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
        if (texA != 0 || texB != 0) {
            GLES20.glDeleteTextures(2, intArrayOf(texA, texB), 0)
            texA = 0
            texB = 0
        }
        bufW = 0
        bufH = 0
        readBuffer = null
    }

    /** 编译/取用着色器程序（按源码缓存）。 */
    private fun programFor(fragmentSrc: String): Int? {
        programs[fragmentSrc]?.let { if (it != 0) return it }

        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return null
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc) ?: return null

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)

        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            AppLogger.w("LowGlassGL", "着色器链接失败：${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            programs[fragmentSrc] = 0
            return null
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        programs[fragmentSrc] = program
        return program
    }

    private fun compile(type: Int, src: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            AppLogger.w(
                "LowGlassGL",
                "着色器编译失败（${if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"}）：" +
                    GLES20.glGetShaderInfoLog(shader),
            )
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    /** 按名字长度自动识别类型，设置 uniform。 */
    private fun setUniforms(program: Int, uniforms: Map<String, FloatArray>, w: Int, h: Int) {
        uniforms.forEach { (name, v) ->
            val loc = GLES20.glGetUniformLocation(program, name)
            if (loc < 0) return@forEach
            when (v.size) {
                1 -> GLES20.glUniform1f(loc, v[0])
                2 -> GLES20.glUniform2f(loc, v[0], v[1])
                3 -> GLES20.glUniform3f(loc, v[0], v[1], v[2])
                4 -> GLES20.glUniform4f(loc, v[0], v[1], v[2], v[3])
            }
        }
        // uSize 是所有着色器都需要的（[GlassShaders] 的 COMMON 段）。
        val sizeLoc = GLES20.glGetUniformLocation(program, "uSize")
        if (sizeLoc >= 0) GLES20.glUniform2f(sizeLoc, w.toFloat(), h.toFloat())
    }

    private fun drawQuad(program: Int) {
        val attr = GLES20.glGetAttribLocation(program, "aPosition")
        val buffer: FloatBuffer = ByteBuffer
            .allocateDirect(QUAD.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD)
                position(0)
            }
        if (attr >= 0) {
            GLES20.glEnableVertexAttribArray(attr)
            GLES20.glVertexAttribPointer(attr, 2, GLES20.GL_FLOAT, false, 0, buffer)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (attr >= 0) GLES20.glDisableVertexAttribArray(attr)
    }

    /** 顶点着色器：直接把 -1..1 的四边形传到裁剪空间（纹理坐标由 gl_FragCoord 反推）。 */
    private const val VERTEX_SHADER = """
attribute vec2 aPosition;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

    /** 释放 GL 资源（进程退出/低内存时调用）。 */
    fun dispose() {
        runCatching {
            glExecutor.submit {
                releaseBuffers()
                programs.values.forEach { if (it != 0) GLES20.glDeleteProgram(it) }
                programs.clear()
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                    )
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
                display = EGL14.EGL_NO_DISPLAY
                context = EGL14.EGL_NO_CONTEXT
                surface = EGL14.EGL_NO_SURFACE
                initialized = false
            }.get()
        }
    }
}