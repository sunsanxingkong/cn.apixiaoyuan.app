package cn.apixiaoyuan.app.core.design.component

import android.graphics.Color as AndroidColor
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import cn.apixiaoyuan.app.core.log.AppLogger
import org.json.JSONObject

/**
 * H5 容器的「**自适应页面底色**」（★ 2026-10-03）。
 *
 * # 要解决的现象（用户反馈）
 *
 * > 「还有这个容器的顶部是空的应该采取自适应页面颜色填充，
 * >   并且所有 h5 页面均有这个问题」
 *
 * # 根因
 *
 * App 开了 `enableEdgeToEdge()`（见 `MainActivity`）—— 内容区一直画到屏幕最顶端，
 * **状态栏是浮在内容之上的**。而 H5 容器页（[cn.apixiaoyuan.app.feature.pk.PkH5Screen]、
 * [cn.apixiaoyuan.app.feature.pknode.PkNodeScreen]）的布局是：
 *
 * ```kotlin
 * Column(modifier = Modifier.fillMaxSize()) {   // ← 没有 background！
 *     ...标题栏...
 *     AndroidView(...)                          // ← WebView 从这里往下
 * }
 * ```
 *
 * 于是**状态栏那一条带子露出的是窗口底色**（主题的 `windowBackground`），
 * 而不是 H5 页面自己的颜色。表现就是「顶部空一条、颜色还跟页面不一样」——
 * 深色 H5 配浅色主题时尤其刺眼（一条白带子压在深色页面上）。
 *
 * # 解法：问页面「你是什么底色」，然后把它铺满整页
 *
 * 不用猜，**直接问 H5**：
 *
 * ```js
 * getComputedStyle(document.documentElement).backgroundColor   // 根元素（铺满视口）
 * getComputedStyle(document.body).backgroundColor              // body
 * document.querySelector('meta[name=theme-color]').content     // 页面自己声明的主题色
 * ```
 *
 * 优先级：**根元素 > body > `<meta theme-color>`**。
 * 理由：`html` 元素的背景会铺满整个视口（CSS 的背景传播规则），
 * 是「页面实际看起来的底色」；`body` 常常是 `transparent`（没设），
 * 这时才轮到它；`theme-color` 是页面给浏览器壳用的提示色，作最后兜底。
 *
 * 拿到值之后：把容器的背景设成它 —— 状态栏那条带子就与页面浑然一体了。
 *
 * # 为什么是「自适应」而不是写死深色
 *
 * 同一个 App 里有两个 H5：PK 页（深色主题的 H5）与管理后台（跟随 App 主题）。
 * 写死任何一边都会让另一边出现色带，所以必须**按页取色**。
 *
 * # 取不到怎么办
 *
 * 保持调用方给的默认色（通常是主题的 `surfaceContainer`），**不猜**。
 * 探测失败、色值解析不了、页面还没 `document.body` —— 都走默认色，
 * 只是没有「无缝」效果，不会出错。
 */
class H5PageColor internal constructor(
    /** 探测到的页面底色；null = 还没探测到 / 页面没给。 */
    initial: Color?,
) {
    /** 当前应使用的底色（null 时由调用方回落到主题色）。 */
    var value: Color? by mutableStateOf(initial)
        internal set

    internal fun update(c: Color?) {
        if (c != null && c != value) value = c
    }
}

/** 记住一个 [H5PageColor]（无初值，靠 [probeH5PageColor] 填）。 */
@Composable
fun rememberH5PageColor(): H5PageColor = remember { H5PageColor(null) }

/**
 * 在 `onPageFinished` 里调用：向 H5 询问它的底色并更新 [holder]。
 *
 * 必须**每次都探**（不能只探一次）：SPA 内部导航（hash 路由）会换「页面」，
 * 而 PK 页里主页/题页/结算页的底色未必相同。
 *
 * 探测是异步的（`evaluateJavascript` 回调），所以本函数立刻返回。
 */
fun probeH5PageColor(webView: WebView, holder: H5PageColor, tag: String = "H5Color") {
    webView.evaluateJavascript(PROBE_JS) { raw ->
        val parsed = parseH5PageColor(raw)
        if (parsed != null) {
            holder.update(parsed)
            AppLogger.d(tag, "页面底色: #${Integer.toHexString(parsed.toArgb())}")
        }
    }
}

/**
 * 探测脚本。返回一个**对象**（不用 JSON.stringify —— WebView 会替我们序列化成
 * JSON 对象字面量，调用方直接 `JSONObject(raw)` 即可，少一层转义）。
 */
private const val PROBE_JS = """
(function(){
  try{
    function bg(el){
      try{ return (el && getComputedStyle(el).backgroundColor) || ''; }catch(e){ return ''; }
    }
    function transparent(c){
      return !c || c === 'transparent' || c === 'rgba(0, 0, 0, 0)' || c === 'rgba(0,0,0,0)';
    }
    var h = bg(document.documentElement);
    var b = bg(document.body);
    if (transparent(h)) h = '';
    if (transparent(b)) b = '';
    var m = '';
    try{
      var meta = document.querySelector('meta[name="theme-color"]');
      if (meta) m = meta.getAttribute('content') || '';
    }catch(e){}
    return { html: h, body: b, theme: m };
  }catch(e){ return { html: '', body: '', theme: '' }; }
})()
"""

/**
 * 解析 [PROBE_JS] 的返回值。
 *
 * 优先级：`html` → `body` → `theme`（理由见 [H5PageColor] 的 KDoc）。
 * 解析不出返回 null（调用方保持默认色）。
 */
internal fun parseH5PageColor(raw: String?): Color? {
    if (raw.isNullOrBlank() || raw == "null") return null
    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    for (key in listOf("html", "body", "theme")) {
        val v = obj.optString(key, "")
        if (v.isNotBlank()) {
            parseCssColor(v)?.let { return it }
        }
    }
    return null
}

/**
 * 解析 CSS 颜色字面量。支持 `#rgb` / `#rrggbb` / `#aarrggbb` / `rgb(...)` / `rgba(...)`。
 *
 * ⚠️ **刻意不支持命名色与 hsl**：现代 Chromium 的 `getComputedStyle` 一律回
 * `rgb()/rgba()` 形式，命名色只在手写 `style` 属性时出现 —— 不是我们这条链路。
 * 不支持就返回 null，走默认色，不会出错。
 *
 * ⚠️ `rgba(..., 0)`（全透明）会被当作「没颜色」→ 返回 null。
 */
internal fun parseCssColor(s: String): Color? {
    val t = s.trim().lowercase()
    if (t.isEmpty()) return null
    if (t.startsWith("#")) {
        val hex = t.substring(1)
        val argb = when (hex.length) {
            3 -> { // #rgb → 每位重复
                val r = hex[0].digitToIntOrNull(16) ?: return null
                val g = hex[1].digitToIntOrNull(16) ?: return null
                val b = hex[2].digitToIntOrNull(16) ?: return null
                AndroidColor.rgb(r * 17, g * 17, b * 17)
            }
            6 -> (hex.toLongOrNull(16) ?: return null).let { AndroidColor.rgb(
                ((it shr 16) and 0xFF).toInt(),
                ((it shr 8) and 0xFF).toInt(),
                (it and 0xFF).toInt(),
            ) }
            8 -> (hex.toLongOrNull(16) ?: return null).let { AndroidColor.argb(
                ((it shr 24) and 0xFF).toInt(),
                ((it shr 16) and 0xFF).toInt(),
                ((it shr 8) and 0xFF).toInt(),
                (it and 0xFF).toInt(),
            ) }
            else -> return null
        }
        return Color(argb)
    }
    if (t.startsWith("rgb")) {
        // rgb(r, g, b) / rgba(r, g, b, a)
        val inner = t.substringAfter('(', "").substringBeforeLast(')', "")
        if (inner.isBlank()) return null
        val parts = inner.split(',', ' ', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val r = parts[0].toFloatOrNull()?.toInt() ?: return null
        val g = parts[1].toFloatOrNull()?.toInt() ?: return null
        val b = parts[2].toFloatOrNull()?.toInt() ?: return null
        val a = parts.getOrNull(3)?.toFloatOrNull() ?: 1f
        if (a <= 0f) return null        // 全透明 = 等价于没设
        val ai = (a.coerceIn(0f, 1f) * 255f).toInt()
        return Color(AndroidColor.argb(ai, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)))
    }
    return null
}

/**
 * 颜色够不够深 —— 用于决定状态栏图标该用浅色还是深色。
 *
 * 公式是 sRGB 相对亮度的常用近似（Rec.709 系数），与设计规范一致。
 * 底色深 → 状态栏图标要浅（白）；底色浅 → 要深（黑/灰）。
 */
internal fun isDarkColor(c: Color): Boolean {
    val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    return lum < 0.5f
}