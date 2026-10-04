package cn.apixiaoyuan.app.core.oldsimian

import cn.apixiaoyuan.app.core.session.SessionStore

/**
 * PK H5 专用的 **cookie 罐** —— 1:1 对齐 pk-node 的 `src/http.js` 里的 `CookieJar`。
 *
 * # 为什么要单独做一个（而不是直接用 SessionStore）
 *
 * 用户要求（2026-10-04）：
 * > 「app 侧只对 pkh5 改动 cookie 逻辑不就行了，其他不动即可，然后直接对齐 pk-node」
 *
 * [SessionStore] 是**全局会话**（登录 / 刷局 / 练习 / 拦截器都吃它），动它就是动
 * 全链路 —— 风险大且没必要。而 PK H5 的代理是独立入口（[PkH5Proxy.fetch]），
 * 只要把「发出去的那个 Cookie 头」换成对齐 pk-node 的算法即可。
 *
 * # 对齐了什么
 *
 * pk-node 的实现在 `src/http.js`，关键三点：
 *
 * ```js
 * // ① 导入时把 domain 统一为「. + baseHost」，且 baseHost 故意用根域
 * //    src/services/leo-accounts.js:40
 * const jar = leo.CookieJar.fromHeader(normalized, 'yuanfudao.com');
 * // 注释原文：「fromHeader 会把 domain 写成 `.yuanfudao.com`（覆盖所有子域），
 * //            符合真机 cookie 的域」
 *
 * // ② 匹配规则 domainMatches(cookieDomain, host)
 * //    有前导点（.yuanfudao.com）= 域 cookie：host 等于或为其子域
 *
 * // ③ 出站时按 host + path 过滤
 * jar.headerFor(u.hostname, u.pathname)
 * ```
 *
 * 对应到 App 侧：
 *
 * | 语义 | pk-node | 本类 |
 * |---|---|---|
 * | 统一根域 | baseHost = `yuanfudao.com` | 「有无前导点」归一为有 |
 * | 域 cookie 写法 | `.<root>` | 同 |
 * | root 自身的替换域名 | `local.yuanfudao.biz` | 一并归一（见 [LOOSE_ROOTS]） |
 * | 出站过滤 | `domainMatches` + path 前缀 | [headerFor] |
 * | 同 (name,domain) 去重 | `set` | [absorb] |
 *
 * ⚠️ **刻意保留的宽松之处（宁多带不漏带）**
 *
 * 若 cookie 的 domain **与目标 host 不沾边**（历史上存进 SessionStore 的
 * 脏数据），[headerFor] 仍会带上它。理由：对齐 pk-node 的同时不能制造
 * 「cookie 突然少了几条 → 登录态掉了」的新回归 —— cookie 少带是硬故障，
 * 多带是服务端自己会忽略的软瑕疵。
 */
internal object PkCookieJar {

    /** 根域。与 pk-node 的 `leo-accounts.js` 用的是同一个。 */
    private const val ROOT_DOMAIN = "yuanfudao.com"

    /**
     * 与根域「同族」的其它根 —— 归一 domain 时一并接受。
     *
     * `local.yuanfudao.biz` 是 H5 在**本地调试环境**下用的域（pk-node 的资产
     * 改写里也提到它），真机上偶有落到 cookie 里，归一时统一成 [ROOT_DOMAIN] 的写法，
     * 免得因为「不是一个根」而被过滤掉。
     */
    private val LOOSE_ROOTS = listOf(ROOT_DOMAIN, "yuanfudao.biz", "fbcontent.cn")

    /**
     * 按 pk-node 的口径计算**发给某个 host 的 Cookie 头**。
     *
     * @param host     目标主机名（如 `xyks.yuanfudao.com`）；空则不过滤（全带）
     * @param pathname 目标路径（如 `/leo-star/api/...`）；用于 path 前缀判定
     * @return 形如 `a=1; b=2`；没有可发的返回 null
     */
    fun headerFor(host: String?, pathname: String? = "/"): String? {
        val all = runCatching { SessionStore.loadCookies() }.getOrNull().orEmpty()
        if (all.isEmpty()) return null

        val h = host.orEmpty().trim().lowercase()
        val p = pathname.orEmpty().ifBlank { "/" }

        val out = ArrayList<String>(all.size)
        val seen = HashSet<String>(all.size)
        for (c in all) {
            if (c.value.isEmpty()) continue
            // 同名去重：与 pk-node 的 `set`（按 name+domain）等价 ——
            // 这里先到先得，后面的同 name 丢弃（避免同名两条一起发）。
            if (!seen.add(c.name)) continue

            val d = normalizeDomain(c.domain)
            if (h.isNotEmpty() && !domainMatches(d, h, loose = c.domain.isBlank())) continue
            if (!pathMatches(p, c.path)) continue
            out.add(c.name + "=" + c.value)
        }
        return if (out.isEmpty()) null else out.joinToString("; ")
    }

    /**
     * 归一 domain 写法，对齐 pk-node `importHeader` 的 `'.' + baseHost`。
     *
     * - 空 domain：按「无前导点」处理（[domainMatches] 会走「精确或子域」分支，
     *   而 [headerFor] 对空 domain 另有宽松处理）
     * - 有前导点：原样小写
     * - 无前导点但属于 [LOOSE_ROOTS] 之一：补上前导点（**这一步就是「覆盖所有子域」的来源**）
     * - 其余（具体子域如 `xyks.yuanfudao.com`）原样小写
     */
    private fun normalizeDomain(domain: String): String {
        val d = domain.trim().lowercase()
        if (d.isEmpty()) return ""
        if (d.startsWith(".")) return d
        return if (LOOSE_ROOTS.any { d == it || d.endsWith("." + it) && d.count { ch -> ch == '.' } == 1 }) {
            "." + d
        } else {
            d
        }
    }

    /**
     * 1:1 对齐 pk-node 的 `domainMatches(cookieDomain, host)`。
     *
     * ```js
     * if (d.startsWith('.')) return h === d.slice(1) || h.endsWith(d);
     * return h === d || h.endsWith('.' + d);
     * ```
     *
     * @param loose domain 为空时放宽 —— 空 domain 的 cookie 我们**不知道它属于谁**，
     *   丢掉的风险（掉登录态）远大于多带
     */
    private fun domainMatches(cookieDomain: String, host: String, loose: Boolean): Boolean {
        if (cookieDomain.isEmpty()) return loose
        return if (cookieDomain.startsWith(".")) {
            host == cookieDomain.substring(1) || host.endsWith(cookieDomain)
        } else {
            host == cookieDomain || host.endsWith("." + cookieDomain)
        }
    }

    /** path 前缀判定（pk-node `headerFor`：`p.startsWith(c.path)`）。 */
    private fun pathMatches(requestPath: String, cookiePath: String): Boolean {
        val cp = cookiePath.ifBlank { "/" }
        return requestPath.startsWith(cp)
    }
}
