# 启动清单（relay.json）· 设计说明

> 本文件在 **App 仓库（private）**。
> ⚠️ **绝对不能进任何 public 仓库或静态站** —— 它解释了这个机制的目的与用法。
>
> 曾经踩过的坑（2026-10-04）：把这份说明放在 pk-node（public）仓库根目录，
> 而 relay.json 放在它的 docs/（会被 Pages 发布）。两者都泄露了。已全部移除。

## 它是什么

一个**纯静态 JSON**，App 在第一次组合 UI 之前读它，决定这次
「正常启动」还是「不继续启动」。它是 App 无法启动、用户又不方便连电脑时的
唯一外部救援通道。

## 线上地址

    https://pk-node.pages.dev/relay.json

内容：

    { "service": "pk-node", "manifest": 1, "relay": true }

## 字符语义

| relay | App 行为 |
|---|---|
| true（缺省 / 字段不存在 / 网络失败） | **正常运行** |
| false | **不继续启动**（静默 exitProcess(0)，无崩溃、无对话框、无日志痕迹） |

### 判据是白名单式（最要紧的一条）

**只有确凿读到 false 才停，其余一切情况（断网 / 超时 / 404 / 解析失败 / 字段缺失）一律放行。**

反例的后果：若「取不到就停」，那么一次断网、一次静态页被删，App 就**永远打不开** ——
兜底开关反而成了永久自杀开关。宁可漏一次，不可误杀。

（App 侧实现见 core/launch/LaunchManifest.kt，含 7 组用例已验证。）

## 隐蔽（硬要求）

1. **不出现敏感字眼**：产物 / 提交信息 / 静态站里不得出现
   kill / switch / remote / disable / fallback / gate 等词。
2. **命名能自圆其说**：文件名 relay.json（中继），类 LaunchManifest（启动清单），
   字段 service / manifest / relay，真值 true。名字本身就是第一层掩护。
3. **不留可检索的指纹**：public 仓库、README、用户可见 UI、错误信息里都不提；
   诊断日志只打公开 URL。

## 部署（Cloudflare Pages）

    export CLOUDFLARE_API_TOKEN=<令牌>
    export CLOUDFLARE_ACCOUNT_ID=13182625f8c268f652dca7e33d52ed52
    cd deploy/pages && wrangler pages deploy . --project-name=pk-node --branch=main

- **不新增任何 workflow / secret**（CI 日志里看不到任何相关行）；
- 同一个 Pages 项目里还有一条代理 function（全部路径转发到 Worker，绕过 workers.dev 封锁），
  所以 deploy/pages/ 是**整目录快照** —— functions/ 必须一起上传，否则会把代理冲掉；
- _routes.json 里把 /relay.json 排除在 function 之外，让它静态直出。

## 改开关

改 deploy/pages/relay.json 再跑一次部署命令即可（App 侧 5 分钟 TTL 内生效）。
长期不用时把 relay 字段删掉即可 —— 字段缺省 = 正常运行。
