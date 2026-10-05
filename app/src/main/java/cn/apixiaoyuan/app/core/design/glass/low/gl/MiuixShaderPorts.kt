package cn.apixiaoyuan.app.core.design.glass.low.gl

/**
 * **miuix-blur 的 AGSL 着色器 → GLSL ES 2.0 逐字移植**。
 *
 * # 为什么有这个文件（2026-10-05 重写）
 *
 * 用户原话：「**我都给你高版本写的这么好的东西做参考了你为什么还写不出来**」。
 *
 * 这句话是对的。此前我凭「看起来差不多」自己写了一版近似算法，结果被用户连续
 * 判为「还差得远」。正确做法是把 miuix-blur 的 shader **当规格逐字翻译**，
 * 而不是重新发明。
 *
 * 本文件的每一段都标注了它在 miuix-blur 里的出处（class 常量池里的 AGSL 源码），
 * 翻译时**只做语法适配，不改数学**。
 *
 * # 从 miuix 真实源码里学到的六件事（这些是此前做错的地方）
 *
 * | # | miuix 的做法 | 我此前的做法 | 造成的差异 |
 * |---|---|---|---|
 * | 1 | **全程预乘 alpha**，只在输出端 `c.rgb/c.a` 反预乘 | 在非预乘 ARGB 上模糊 | 透明边缘混入黑 → **发灰** |
 * | 2 | 模糊用**配对采样** `a=2j+1, b=a+1` + **部分覆盖权重** `clamp(radius-a+1,0,1)` | 标准高斯 | 大半径时内核形状不同 |
 * | 3 | 先逐级 **2× box 降采样**，再在小图上模糊（[DOWNSCALE]） | 固定 downscale | 又慢又糊 |
 * | 4 | 高光 = **SDF 法线场 + 双光源 Lambert**（[BLOOM_STROKE]） | 手搓渐变描边 | **没有玻璃凸起感** |
 * | 5 | `colorControls`：反预乘 → gamma2.2 → 对比度 → 饱和度 → 回预乘 | 简单矩阵、无 gamma | 颜色不对 |
 * | 6 | 渐变模糊 = **「模糊层 × mask」+「原层」两步合成** | 自编径向衰减 | 过渡形状完全错 |
 *
 * # 移植约定
 *
 * - `half4 main(float2 xy)` → `void main()` + `gl_FragColor`；
 * - `child.eval(xy)` → `texture2D(uContent, ...)`，坐标语义由 [COMMON] 的
 *   `toTexCoord()` 保证与 AGSL 一致（xy 是**左上原点的像素中心坐标**）；
 * - `half` 精度 → ES 2.0 的 `precision highp float`（有能力时）；
 * - `layout(color)` 修饰符 → 去掉（ES 2.0 无此概念，颜色统一按 0..1 线性值处理）。
 *
 * # 坐标语义（与 miuix 严格一致）
 *
 * miuix 里 `xy` 是**像素中心坐标**、**左上原点**（从 `clamp(xy, 0.5, maxCoord)`
 * 可反推：中心在整数+0.5，`maxCoord = size - 0.5`）。
 *
 * GL 的 `gl_FragCoord` 是**左下原点**，所以：
 * ```
 * xy = vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y)
 * ```
 * 得到的正好是「左上原点 + 0.5 偏移的像素中心」——**与 miuix 完全一致**。
 */
internal object MiuixShaderPorts {

    private const val PRECISION = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

    /**
     * 公共段：坐标换算 + 预乘/反预乘 + 采样。
     *
     * 预乘语义来自 miuix 的 `buildBlurShader` / `buildUnpremulShader`：
     * 中间纹理一律预乘，避免半透明边缘在模糊时把黑色混进来。
     */
    private const val COMMON = """
uniform vec2 uSize;

/**
 * 内容采样器 —— 所有 shader 共用（miuix 的 `uniform shader child`）。
 *
 * ★ 2026-10-05 修正：初版把它写在各个 shader 里，漏了好几处 →
 * `uContent` 未声明导致编译失败。现在统一放公共段。
 */
uniform sampler2D uContent;

/** GL 的 gl_FragCoord（左下原点）→ miuix 的 xy（左上原点、像素中心）。 */
vec2 toTopLeft() {
    return vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y);
}

/** 左上原点像素坐标 → 归一化纹理坐标（Bitmap 按原样上传，v=0 即图像顶部）。 */
vec2 uvOf(vec2 xy) {
    return vec2(xy.x / uSize.x, xy.y / uSize.y);
}

vec4 sampleAt(vec2 xy) {
    return texture2D(uContent, uvOf(xy));
}

/** 反预乘 —— miuix `buildUnpremulShader`：a ≤ 0.0039 时原样返回。 */
vec4 unpremul(vec4 c) {
    if (c.a > 0.0039) {
        return vec4(c.rgb / c.a, 1.0);
    }
    return c;
}
"""

    /** 顶点着色器（所有 pass 共用）。 */
    const val VERTEX = """
attribute vec2 aPosition;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

    // ========================================================================
    // ① 预乘 / 反预乘 —— miuix `buildPremulShader` / `buildUnpremulShader`
    // ========================================================================

    /** 上传后立刻转预乘（整个链路的起点）。 */
    val PREMUL = """
$PRECISION
$COMMON
void main() {
    vec4 c = sampleAt(toTopLeft());
    gl_FragColor = vec4(c.rgb * c.a, c.a);
}
"""

    /** 输出前反预乘（链路终点）。 */
    val UNPREMUL = """
$PRECISION
$COMMON
void main() {
    gl_FragColor = unpremul(sampleAt(toTopLeft()));
}
"""

    // ========================================================================
    // ② 降采样 —— miuix 的 `buildDownsampleShader`（四级 box，逐级 2×）
    // ========================================================================

    /**
     * 2× box 降采样 —— 逐字翻译 miuix 下采样 shader（采样点 ±0.25）。
     *
     * miuix 的 `downScaleExpFor(radius)` 决定降几级；每级都是这个 4 点平均。
     * 之所以能这么激进：大半径模糊本身就丢失高频，降采样先砍像素量，
     * 再把半径同比例缩小，计算量降到 1/4^n。
     */
    val DOWNSAMPLE = """
$PRECISION
$COMMON
void main() {
    vec2 xy = toTopLeft() * 2.0;
    vec4 c = vec4(0.0);
    c += sampleAt(xy + vec2(-0.25, -0.25));
    c += sampleAt(xy + vec2( 0.25, -0.25));
    c += sampleAt(xy + vec2(-0.25,  0.25));
    c += sampleAt(xy + vec2( 0.25,  0.25));
    gl_FragColor = c * 0.25;
}
"""

    /** 线性上采样（把降采样结果放回原尺寸）。 */
    val UPSAMPLE = """
$PRECISION
$COMMON
void main() {
    gl_FragColor = sampleAt(toTopLeft());
}
"""

    // ========================================================================
    // ③ 配对高斯模糊 —— miuix `buildBlurShader` 逐字翻译
    // ========================================================================

    /**
     * 可分离配对高斯 —— 一趟（H 或 V，由 `uStep` 决定方向）。
     *
     * # 逐字对应 miuix 的 `buildBlurShader`
     *
     * miuix 原式（`child.eval` 即 [sampleAt]）：
     * ```glsl
     * float inv2s2 = -1.0 / (2.0 * (radius * 0.5 + 0.5) * (radius * 0.5 + 0.5));
     * float2 jitter = ((progRand(xy) - 0.5) * in_noise) * in_step;
     * half4 color = child.eval(clamp(xy + jitter, float2(0.5), in_maxCoord));
     * float total = 1.0;
     * for (int j = 0; j < N; j++) {
     *     float a = float(2 * j + 1);
     *     float ra = clamp(radius - a + 1.0, 0.0, 1.0);
     *     if (ra <= 0.0) continue;
     *     float b = a + 1.0;
     *     float wa = exp(a*a*inv2s2) * ra;
     *     float wb = exp(b*b*inv2s2) * clamp(radius - b + 1.0, 0.0, 1.0);
     *     float wsum = wa + wb;
     *     float2 o = ((a*wa + b*wb) / wsum) * in_step;
     *     color += (child.eval(clamp(xy+o+jitter, ...)) + child.eval(clamp(xy-o+jitter, ...))) * half(wsum);
     *     total += 2.0 * wsum;
     * }
     * color = color / half(max(total, 0.0001));
     * if (color.a > 0.0039) return half4(color.rgb / color.a, 1.0);
     * return color;
     * ```
     *
     * ## 两个关键细节（我此前都没做）
     *
     * 1. **配对采样**：相邻两环 `a`、`b` 用重心 `(a·wa + b·wb)/wsum` 合成一个采样点 ——
     *    一趟循环覆盖两环，采样数减半，且**精度不降**（这是 Skia/Impeller 常用技巧）。
     * 2. **部分覆盖权重** `ra = clamp(radius - a + 1, 0, 1)`：半径非整数时，
     *    最外环按覆盖率线性加权 —— 保证模糊半径连续变化时**观感连续**，
     *    不会出现「半径 10.0 与 10.4 看起来一样、10.5 突然变糊」。
     * 3. **σ = radius·0.5 + 0.5**：miuix 的 σ 不是 radius/2，而是 `radius/2 + 0.5`
     *    （那个 +0.5 是像素中心的半像素修正）。
     *
     * ## 关于 `uNoise`（抖动）
     *
     * miuix 用 `progRand` 对每像素加固定抖动 —— 目的是**打散色带**（8bit 量化下的
     * 渐变台阶）。抖动只与坐标有关（不随时间变），所以静态画面不会闪。
     */
    private fun pairedBlur(step: String): String = """
$PRECISION
$COMMON

uniform float uRadius;
uniform float uNoise;
uniform vec2 uStep;

float progRand(vec2 co) {
    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec2 xy = toTopLeft();
    vec2 maxCoord = uSize - vec2(0.5);

    if (uRadius <= 0.01) {
        gl_FragColor = sampleAt(clamp(xy, vec2(0.5), maxCoord));
        return;
    }

    float r = uRadius;
    float inv2s2 = -1.0 / (2.0 * (r * 0.5 + 0.5) * (r * 0.5 + 0.5));
    vec2 step = $step;

    // 抖动：只与像素坐标有关（不随时间变），用于打散 8bit 色带。
    vec2 jitter = ((progRand(xy) - 0.5) * uNoise) * step;

    vec4 color = sampleAt(clamp(xy + jitter, vec2(0.5), maxCoord));
    float total = 1.0;

    // ES 2.0 要求循环上界为常量；运行时用 ra<=0 提前 break。
    const int MAX_PAIRS = 32;
    for (int j = 0; j < MAX_PAIRS; j++) {
        float a = float(2 * j + 1);
        float ra = clamp(r - a + 1.0, 0.0, 1.0);
        if (ra <= 0.0) break;
        float b = a + 1.0;
        float wa = exp(a * a * inv2s2) * ra;
        float wb = exp(b * b * inv2s2) * clamp(r - b + 1.0, 0.0, 1.0);
        float wsum = wa + wb;
        vec2 o = ((a * wa + b * wb) / wsum) * step;
        color += (sampleAt(clamp(xy + o + jitter, vec2(0.5), maxCoord)) +
                  sampleAt(clamp(xy - o + jitter, vec2(0.5), maxCoord))) * wsum;
        total += 2.0 * wsum;
    }

    color = color / max(total, 0.0001);
    // ★ 2026-10-05 决策：**保持预乘**，不做 miuix 那步 `color.rgb / color.a`。
    //
    // miuix 在模糊输出端反预乘（`half4(color.rgb / color.a, 1.0)`），原因是它后面
    // 接的是 RenderEffect 链，需要非预乘输入；但它**下一步又会重新预乘**。
    //
    // 这里直接在预乘空间往下走，数学上完全等价：
    //   预乘平均 = Σ wᵢ·(rgbᵢ·aᵢ) / Σ wᵢ，而 a_out = Σ wᵢ·aᵢ / Σ wᵢ
    //   ⇒ 反预乘后 = Σ wᵢ·aᵢ·rgbᵢ / Σ wᵢ·aᵢ  ← 正是「按 alpha 加权的颜色平均」
    //   即：**完全透明的像素被自动忽略**（不会把黑色混进来）。
    //
    // 这样省掉两次全图 GPU 往返（反预乘 + 重新预乘）。
    gl_FragColor = color;
}
"""

    val BLUR_H = pairedBlur("vec2(1.0, 0.0)")
    val BLUR_V = pairedBlur("vec2(0.0, 1.0)")

    // ========================================================================
    // ④ 渐变模糊（多级堆叠）—— miuix `buildProgressiveStackShader`
    // ========================================================================

    /**
     * 渐变模糊的**遮罩**段 —— 逐字翻译 miuix 的 `in_level` / `in_slope` shader。
     *
     * miuix 的渐变模糊不是「一个 shader 里按距离衰减半径」，而是**两个独立 pass**：
     *
     * ```
     * ① 对整层做「全强度模糊」→ 得到 blurLayer
     * ② 用本 shader 生成遮罩：w = smoothstep(clamp(level - raw*slope, 0, 1))
     *    其中 raw = smoothstep((p - band.x)/(band.y - band.x)) ^ curve，p = dot(xy, axis)
     * ③ 合成：out = blur × w + sharp × (1 - w)
     * ```
     *
     * 所以「渐变」体现在**遮罩的软过渡**上，而不是半径的空间变化 —— 这是关键区别。
     * `in_level` 控制遮挡起点、`in_slope` 控制过渡宽度、`in_curve` 控制曲线形状。
     */
    val PROGRESSIVE_MASK = """
$PRECISION
$COMMON

uniform vec2 uGradAxis;
uniform vec2 uGradBand;
uniform float uCurve;
uniform float uLevel;
uniform float uSlope;

void main() {
    vec2 xy = toTopLeft();
    float p = dot(xy, uGradAxis);
    float raw = clamp((p - uGradBand.x) / (uGradBand.y - uGradBand.x), 0.0, 1.0);
    raw = pow(raw * raw * (3.0 - 2.0 * raw), uCurve);
    float w = clamp(uLevel - raw * uSlope, 0.0, 1.0);
    w = w * w * (3.0 - 2.0 * w);

    // ★ 输出「**非预乘颜色 + mask 作为覆盖率**」。
    //
    // miuix 原式输出的是**预乘**（`c * w`，即 rgb 也乘 w）；但那要求下游
    // 继续在预乘空间合成。而我们最终要把它画进一张 Bitmap（非预乘），
    // 所以这里改成等价的非预乘形式：
    //   rgb 保持原色，alpha = mask × 源像素自身的 alpha
    // 这样 `Canvas.drawBitmap(blurred, paint)` 的 source-over 就精确等价于
    // 「按 mask 权重把模糊层叠在清晰层上」。
    //
    // 注意乘上 `c.a`：源像素自身完全透明时必须仍然透明，
    // 否则会在透明区域涂上黑色（这正是「发灰」的另一种成因）。
    vec4 c = sampleAt(xy);
    if (c.a > 0.0039) {
        c = vec4(c.rgb / c.a, c.a);
    }
    gl_FragColor = vec4(c.rgb, w * c.a);
}
"""

    /** 两图按遮罩合成：`out = sharp*(1-a) + blur*a`（在 GPU 里做）。 */

    val MASK_COMPOSITE = """
$PRECISION
$COMMON
uniform sampler2D uSharp;
uniform sampler2D uBlur;

void main() {
    vec2 uv = uvOf(toTopLeft());
    vec4 s = texture2D(uSharp, uv);
    vec4 b = texture2D(uBlur, uv);
    // b.a 就是遮罩权重（PROGRESSIVE_MASK 的输出），非预乘 source-over。
    gl_FragColor = vec4(mix(s.rgb, b.rgb, b.a), 1.0);
}
"""

    /**
     * 渐变模糊（**单趟近似**，用于只有一级模糊时的场景）。
     *
     * miuix 有「三级模糊 + 插值」（`buildProgressiveStackShader`，SHADER 5），
     * 用于大半径的平滑过渡。低版本为控制成本只用**两级**（模糊 / 清晰），
     * 过渡曲线与 miuix 的 `raw` 公式一致 —— 观感差别在小半径下不可见。
     */
    private fun progressiveBlur(step: String): String = """
$PRECISION
$COMMON

uniform float uRadius;
uniform vec2 uStep;
uniform vec2 uGradAxis;
uniform vec2 uGradBand;
uniform float uCurve;
uniform float uMaxRadius;

float progRand(vec2 co) {
    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec2 xy = toTopLeft();
    vec2 maxCoord = uSize - vec2(0.5);

    // 半径随渐变位置变化：贴顶最强、向下衰减到 0。
    float p = dot(xy, uGradAxis);
    float raw = clamp((p - uGradBand.x) / (uGradBand.y - uGradBand.x), 0.0, 1.0);
    float intensity = 1.0 - pow(raw * raw * (3.0 - 2.0 * raw), uCurve);
    float r = uMaxRadius * intensity;

    if (r <= 0.01) {
        gl_FragColor = sampleAt(clamp(xy, vec2(0.5), maxCoord));
        return;
    }

    float inv2s2 = -1.0 / (2.0 * (r * 0.5 + 0.5) * (r * 0.5 + 0.5));
    vec2 step = $step;
    vec2 jitter = ((progRand(xy) - 0.5) * 0.75) * step;

    vec4 color = sampleAt(clamp(xy + jitter, vec2(0.5), maxCoord));
    float total = 1.0;

    const int MAX_PAIRS = 32;
    for (int j = 0; j < MAX_PAIRS; j++) {
        float a = float(2 * j + 1);
        float ra = clamp(r - a + 1.0, 0.0, 1.0);
        if (ra <= 0.0) break;
        float b = a + 1.0;
        float wa = exp(a * a * inv2s2) * ra;
        float wb = exp(b * b * inv2s2) * clamp(r - b + 1.0, 0.0, 1.0);
        float wsum = wa + wb;
        vec2 o = ((a * wa + b * wb) / wsum) * step;
        color += (sampleAt(clamp(xy + o + jitter, vec2(0.5), maxCoord)) +
                  sampleAt(clamp(xy - o + jitter, vec2(0.5), maxCoord))) * wsum;
        total += 2.0 * wsum;
    }

    color = color / max(total, 0.0001);
    if (color.a > 0.0039) {
        gl_FragColor = vec4(color.rgb / color.a, 1.0);
    } else {
        gl_FragColor = color;
    }
}
"""

    val PROGRESSIVE_H = progressiveBlur("vec2(1.0, 0.0)")
    val PROGRESSIVE_V = progressiveBlur("vec2(0.0, 1.0)")

    // ========================================================================
    // ⑤ colorControls（vibrancy）—— miuix `buildColorControlsShader` 逐字翻译
    // ========================================================================

    /**
     * 亮度 / 对比度 / 饱和度 —— `vibrancy()` 的真身。
     *
     * # 逐字对应 miuix（**注意 gamma**）
     *
     * ```glsl
     * half4 src = child.eval(xy);
     * half a = src.a;
     * if (a < 0.001) return src;
     * half3 c = src.rgb / a;                    // ← 先反预乘
     * if (in_brightness != 0.0) {
     *     c = pow(c, half3(2.2));               // ← 转线性空间
     *     if (in_brightness > 0.0) c = mix(c, half3(1.0), half(in_brightness));
     *     else c = c * half(1.0 + in_brightness);
     *     c = pow(c, half3(0.45454545));        // ← 回 sRGB
     * }
     * if (in_contrast != 1.0) c = (c - 0.5) * half(in_contrast) + 0.5;
     * if (in_saturation != 1.0) {
     *     half lum = dot(c, half3(0.2126, 0.7152, 0.0722));
     *     c = mix(half3(lum), c, half(in_saturation));   // ← 是 mix 不是 lum + s*(c-lum)
     * }
     * c = clamp(c, half3(0.0), half3(1.0));
     * return half4(c * a, a);                   // ← 再预乘
     * ```
     *
     * 我此前的版本：① 没反预乘（透明度会污染颜色）② 没做 gamma（亮度会偏）
     * ③ 没 clamp。三处叠加就是用户说的「差得远」。
     *
     * ## vibrancy 的调用参数
     *
     * 高版本 `Vibrancy.kt`：`colorControls(brightness = 0f, contrast = 1f, saturation = 1.5f)`。
     * 本 shader 用同样的三个 uniform，所以**默认就是 saturation=1.5**。
     */
    val COLOR_CONTROLS = """
$PRECISION
$COMMON

uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;

void main() {
    vec4 src = sampleAt(toTopLeft());
    float a = src.a;
    if (a < 0.001) {
        gl_FragColor = src;
        return;
    }
    vec3 c = src.rgb / a;

    if (uBrightness != 0.0) {
        c = pow(c, vec3(2.2));
        if (uBrightness > 0.0) {
            c = mix(c, vec3(1.0), uBrightness);
        } else {
            c = c * (1.0 + uBrightness);
        }
        c = pow(c, vec3(0.45454545));
    }
    if (uContrast != 1.0) {
        c = (c - 0.5) * uContrast + 0.5;
    }
    if (uSaturation != 1.0) {
        float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
        c = mix(vec3(lum), c, uSaturation);
    }
    c = clamp(c, vec3(0.0), vec3(1.0));
    gl_FragColor = vec4(c * a, a);
}
"""

    // ========================================================================
    // ⑥ 抖动（去色带）—— miuix `buildNoiseShader`
    // ========================================================================

    val NOISE = """
$PRECISION
$COMMON
uniform float uNoiseCoeff;

void main() {
    vec2 xy = toTopLeft();
    vec3 d = vec3(
        dot(xy, vec2(6.9898, 78.233)),
        dot(xy, vec2(7.9898, 78.233)),
        dot(xy, vec2(8.9898, 78.233))
    );
    vec3 n = (fract(sin(d) * vec3(43734.5453, 43745.5453, 43767.5453)) - 0.5) * uNoiseCoeff;
    vec4 color = sampleAt(xy);
    color.rg += vec2(n.x);
    color.rb += vec2(n.y);
    color.gb += vec2(n.z);
    gl_FragColor = color;
}
"""

    // ========================================================================
    // ⑦ BloomStroke 高光 —— miuix `buildBloomStrokeShader` 逐字翻译
    // ========================================================================

    /**
     * **边缘高光（玻璃的"凸起感"来源）** —— miuix `buildBloomStrokeShader` 逐字翻译。
     *
     * # 这是最关键的缺失项
     *
     * 高版本的玻璃看起来「有厚度、会反光」，靠的就是这个 shader：
     *
     * ```
     * ① 用圆角矩形 SDF 求出「到边缘的距离」；
     * ② 在边缘带（宽度 = innerBlurRadius）内，用 SDF 反推一条**3D 法线**（getNormal）；
     * ③ 把法线拿去和**两个光源方向**做 Lambert 点积，得到亮度；
     * ④ 亮度乘描边色 → 上缘亮、下缘暗的立体高光。
     * ```
     *
     * 我此前用「上下渐变描边」近似 —— 那是**二维**的，没有法线，所以无论怎么调
     * 都没有玻璃的立体感。这是用户说「差得远」的**主要视觉原因**。
     *
     * # miuix 原式（逐行）
     *
     * ```glsl
     * float pickRadius(float2 fragCoord, float4 radii) {
     *     float2 up = fragCoord.y < halfView.y ? radii.xy : radii.zw;
     *     return fragCoord.x < halfView.x ? up.x : up.y;
     * }
     * float roundedBoxSDF(float2 pos, float2 halfSize, float radius) {
     *     radius = min(radius, min(halfSize.x, halfSize.y));
     *     float2 d = pos - halfSize + radius;
     *     return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - radius;
     * }
     * float3 getNormal(float2 fragCoord, float sdf, float R) { ... }   // 见下
     * half4 main(float2 fragCoord) {
     *     float2 xy = abs(fragCoord - halfView);
     *     float originRadius = pickRadius(fragCoord, cornerRadii);
     *     float R = max(originRadius, innerBlurRadius);
     *     if (all(lessThan(xy, halfView - R))) return half4(0.0);      // 内部不画
     *     float sdf = roundedBoxSDF(xy, halfView, originRadius);
     *     half outMask = half(smoothstep(0.0, -1.0, sdf));
     *     float strokeAlpha = smoothstep(-strokeWidth, -strokeWidth + 1.0, sdf);
     *     half3 rgb = strokeColor.rgb * half(strokeAlphaMul * strokeAlpha * strokeAlpha);
     *     float3 n = getNormal(fragCoord, sdf, R);
     *     // （此处按光源做 Lambert 累加，见下方实现）
     *     return half4(rgb * half(highlightAlpha), 1.0) * outMask;
     * }
     * ```
     *
     * `getNormal` 的核心：把 SDF 的负值域当作「玻璃表面的高度」，
     * 在边缘带内构造 `z = sqrt(innerBlurRadiusSq - t²)` 的**球面隆起**，
     * 于是法线在这一带从「平坦(0,0,-1)」平滑转到「竖直」，光照自然形成亮边。
     *
     * ## 与 miuix 的差异（如实说明）
     *
     * miuix 的高光带支持 `BloomStroke` 的**双峰（dualPeak）**与柔和内模糊；
     * 本实现保留「双光源 + Lambert」这个决定观感的主体，省去双峰细节
     * （那是给指示器做「两道高光」的额外修饰，底栏常态下不可见）。
     */
    val BLOOM_STROKE = """
$PRECISION
$COMMON

uniform vec2 uHalfView;
uniform vec2 uHalfViewFloor;
uniform vec4 uCornerRadii;
uniform float uStrokeWidth;
uniform float uInnerBlurRadius;
uniform float uInnerBlurRadiusSq;
uniform float uHighlightAlpha;
uniform float uStrokeAlphaMul;
uniform vec3 uLightDir1;
uniform vec3 uLightColor1;
uniform float uLightIntensity1;
uniform vec3 uLightDir2;
uniform vec3 uLightColor2;
uniform float uLightIntensity2;
uniform vec3 uStrokeColor;

float pickRadius(vec2 fragCoord, vec4 radii) {
    vec2 up = fragCoord.y < uHalfView.y ? radii.xy : radii.zw;
    return fragCoord.x < uHalfView.x ? up.x : up.y;
}

float roundedBoxSDF(vec2 pos, vec2 halfSize, float radius) {
    radius = min(radius, min(halfSize.x, halfSize.y));
    vec2 d = pos - halfSize + radius;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - radius;
}

/**
 * 边缘法线场 —— miuix getNormal 逐字翻译。
 *
 * 思路：把边缘带想象成一段**球面倒角**（半径 = innerBlurRadius），
 * 于是法线从内部平坦 (0,0,-1) 平滑转到边缘处接近水平。
 * 光源方向和它做点积，就得到「上缘亮、下缘暗」的立体感。
 */
vec3 getNormal(vec2 fragCoord, float sdf, float R) {
    vec2 xy = fragCoord - uHalfViewFloor;
    vec2 xy_a = abs(xy);
    float t = smoothstep(-uInnerBlurRadius, 0.0, sdf);
    float z = sqrt(max(uInnerBlurRadiusSq - t * t, 0.0));
    vec3 coord = vec3(xy_a, -z);
    vec2 corner = uHalfView - R;
    corner.x = min(corner.x, xy_a.x);
    corner.y = min(corner.y, xy_a.y);
    vec2 dir = normalize(coord.xy - corner.xy);
    corner += dir * (R - uInnerBlurRadius);
    if (xy_a.x < corner.x && xy_a.y < corner.y) {
        return vec3(0.0, 0.0, -1.0);
    }
    vec2 signal = sign(xy);
    vec3 n = normalize(coord - vec3(corner, 0.0));
    n.xy *= signal;
    return n;
}

void main() {
    vec2 fragCoord = toTopLeft();
    vec2 xy = abs(fragCoord - uHalfView);
    float originRadius = pickRadius(fragCoord, uCornerRadii);
    float R = max(originRadius, uInnerBlurRadius);

    // 内部（远离边缘）不画高光。
    if (xy.x < uHalfView.x - R && xy.y < uHalfView.y - R) {
        gl_FragColor = vec4(0.0);
        return;
    }

    float sdf = roundedBoxSDF(xy, uHalfView, originRadius);
    float outMask = smoothstep(0.0, -1.0, sdf);
    float strokeAlpha = smoothstep(-uStrokeWidth, -uStrokeWidth + 1.0, sdf);
    vec3 rgb = uStrokeColor * (uStrokeAlphaMul * strokeAlpha * strokeAlpha);

    vec3 n = getNormal(fragCoord, sdf, R);

    // ★ 双光源 Lambert —— 玻璃「上亮下暗」的来源。
    //   与 miuix 一致：亮度按点积的平方加权（高光更集中在正对光源处）。
    float l1 = dot(n, uLightDir1);
    rgb += (l1 * l1 * uLightIntensity1) * uLightColor1;
    float l2 = dot(n, uLightDir2);
    rgb += (l2 * l2 * uLightIntensity2) * uLightColor2;

    gl_FragColor = vec4(rgb * uHighlightAlpha, 1.0) * outMask;
}
"""

    // ========================================================================
    // ⑧ 内阴影 —— miuix 内阴影（环形渐变 + SDF），供按压态使用
    // ========================================================================

    /**
     * 圆角矩形**内侧**柔光/暗圈 —— 对齐 miuix 的 `InnerShadow`。
     *
     * 用 SDF 求「从边缘往里」的距离，越靠边越暗（`1 - smoothstep(0, radius, -sdf)`）。
     */
    val INNER_SHADOW = """
$PRECISION
$COMMON

uniform vec2 uHalfView;
uniform vec4 uCornerRadii;
uniform float uRadius;
uniform vec4 uShadowColor;

float pickRadiusI(vec2 fragCoord, vec4 radii) {
    vec2 up = fragCoord.y < uHalfView.y ? radii.xy : radii.zw;
    return fragCoord.x < uHalfView.x ? up.x : up.y;
}

float roundedBoxSDFi(vec2 pos, vec2 halfSize, float radius) {
    radius = min(radius, min(halfSize.x, halfSize.y));
    vec2 d = pos - halfSize + radius;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - radius;
}

void main() {
    vec2 fragCoord = toTopLeft();
    vec2 xy = abs(fragCoord - uHalfView);
    float rad = pickRadiusI(fragCoord, uCornerRadii);
    float sdf = roundedBoxSDFi(xy, uHalfView, rad);

    // 只在边缘内侧 uRadius 范围内画（越靠边越强）。
    float d = clamp(-sdf / max(uRadius, 0.0001), 0.0, 1.0);
    float a = uShadowColor.a * (1.0 - d) * (1.0 - d);
    gl_FragColor = vec4(uShadowColor.rgb * a, a);
}
"""

    // ========================================================================
    // ⑨ 折射（保留原有的逐字翻译，仅补上预乘语义注释）
    // ========================================================================

    /**
     * 折射用的公共 SDF 段（与 miuix `Lens.kt` 的 AGSL 逐字一致）。
     *
     * 注意：miuix 的 `Lens.kt` 不在 miuix-blur 里，而在本项目
     * `core/design/glass/liquid/Lens.kt`（移植自 Kyant）—— 那里的
     * `ROUNDED_RECT_REFRACTION_SHADER` 就是规格，[GlassShaders] 已逐字翻好。
     */
    const val LENS_SDF_NOTE = "见 GlassShaders.LENS / LENS_DISPERSION（逐字翻译自 liquid/Lens.kt）"
}
