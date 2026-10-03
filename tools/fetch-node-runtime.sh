#!/usr/bin/env bash
# 把「Android 版 Node 运行时」拉下来并放进 App 的 assets。
#
# ## 为什么要内置 Node
#
# 老挂要把 pk-node 的**全部功能**（PK H5 页面 / 刷局 / 刷练习 / 设备链池…）
# 搬进 App，但那是 1200+ 行 Node 服务 + 一整套 H5 注入脚本 ——
# 用 Kotlin 重写不现实。正确做法是**在 App 内跑真的 Node**，
# 让 WebView 直接访问 127.0.0.1。
#
# ## ★ 为什么用 Termux 的包，而不是 nodejs-mobile
#
# `nodejs-mobile`（官方方案）最新只到 **Node 18.20.4**，而 **Node 18 没有
# `node:sqlite`**（22.5 才引入）—— pk-node 的数据库层全靠它，直接跑不起来。
#
# Termux 的 node **ELF 解释器是 `/system/bin/linker64`**（不是 glibc 的
# ld-linux），也就是说它是**真正的 Android 可执行文件**，可以在 App 进程里
# 用 Android 的 linker64 直接执行。
#
# ## 布局（决定了 App 里怎么用）
#
# ```
# app/src/main/assets/node-runtime/
#   manifest.json          # 版本 + 每个文件的 sha256（App 侧校验用）
#   bin/node               # 47 MB
#   bin/linker64           # 2.4 MB，Android linker（取自 pk-node/bin/native）
#   lib/*.so               # 36 个依赖库（libicu 占大头）
# ```
#
# ## ⚠️ 两个已踩过的坑（务必别改回去）
#
# 1. **不要**把 pk-node `bin/native/libc++_shared.so` 拷进 `lib/`！
#    它是**旧版** libc++（1.01 MB），会覆盖 Termux 的新版（1.42 MB），
#    导致 `cannot locate symbol "_ZTVNSt6__ndk119basic_ostringstream..."`。
#    只拷 `libc.so / libm.so / libdl.so / liblog.so` 这四个系统库。
# 2. Termux 包的版本号里可能带冒号（如 `openssl_1:3.6.5`），
#    拼 URL 时必须写成 `%3A`，否则 404。
#
# 用法：bash tools/fetch-node-runtime.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/node-runtime"
WORK="${NODE_RT_WORK:-/tmp/node-runtime-build}"
BASE="https://packages.termux.dev/apt/termux-main"

# Termux 版本（改这里就能升级；升级后记得重跑并提交 assets）
NODE_VER="26.4.0-1"
DEB="nodejs_${NODE_VER}_aarch64.deb"

# node 的直接依赖（闭包实测 10 个包；ca-certificates/resolv-conf 是元数据类，可不带）
LIBS=(libicu openssl libsqlite "libc++" c-ares zlib libffi)

log() { printf '\033[36m[node-rt]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[node-rt] %s\033[0m\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1"; }
need curl
need python3
need tar
need ar

mkdir -p "$WORK/debs" "$WORK/x" "$WORK/libs"
cd "$WORK"

# ---------------------------------------------------------------- 1) node 本体
log "下载 node $NODE_VER …"
if [ ! -s "$WORK/debs/node.deb" ]; then
  curl -fsSL -o "$WORK/debs/node.deb" \
    "$BASE/pool/main/n/nodejs/$DEB" \
    || die "node 包下载失败（检查版本号 $NODE_VER 是否还存在）"
fi

# ---------------------------------------------------------------- 2) 依赖库
#
# ⚠️ 不要手拼 pool 路径 —— termux 的目录前缀不规则
#    （libicu→libi/、libsqlite→libs/、libc++→libc/、c-ares→c/…），
#    而且版本号里可能带冒号。**直接从 Packages 索引里读 Filename** 最稳。
log "拉取包索引（用于取真实下载路径）…"
PKG_INDEX="$WORK/Packages"
if [ ! -s "$PKG_INDEX" ]; then
  curl -fsSL -o "$PKG_INDEX" "$BASE/dists/stable/main/binary-aarch64/Packages" \
    || die "包索引下载失败"
fi

url_of() {
  python3 - "$PKG_INDEX" "$1" <<'PY'
import sys
idx, want = sys.argv[1], sys.argv[2]
cur = {}
for line in open(idx, encoding='utf-8', errors='replace'):
    line = line.rstrip('\n')
    if not line:
        if cur.get('Package') == want:
            print(cur.get('Filename', ''))
            raise SystemExit(0)
        cur = {}
        continue
    if ':' in line and not line.startswith(' '):
        k, v = line.split(':', 1)
        cur[k.strip()] = v.strip()
if cur.get('Package') == want:
    print(cur.get('Filename', ''))
PY
}

for name in "${LIBS[@]}"; do
  [ -s "$WORK/debs/$name.deb" ] && { log "已有 $name.deb，跳过"; continue; }
  rel="$(url_of "$name")"
  [ -n "$rel" ] || die "索引里找不到包：$name"
  log "下载 $name …（$rel）"
  curl -fsSL -o "$WORK/debs/$name.deb" "$BASE/$rel" \
    || die "$name 下载失败：$rel"
done

# ---------------------------------------------------------------- 3) 解包
#
# 不依赖 `ar` / `tar` 的外部行为：deb 就是 `ar` 归档，
# 用 python 按 ar 格式切出 `data.tar.*` 再交给 tar 解。
# （实测 `ar x` + `tar -xf data.tar.xz` 在部分环境会失败。）
log "解包 …"
python3 - "$WORK" <<'PY'
import os, subprocess, sys

work = sys.argv[1]
debs = os.path.join(work, 'debs')
xdir = os.path.join(work, 'x')

def ar_members(path):
    """产出 (name, bytes)，只读 ar 归档里的普通成员。"""
    data = open(path, 'rb').read()
    if data[:8] != b'!<arch>\n':
        raise ValueError('%s 不是 ar 归档' % path)
    off = 8
    while off + 60 <= len(data):
        hdr = data[off:off + 60]
        name = hdr[0:16].decode('utf-8', 'replace').strip().rstrip('/')
        try:
            size = int(hdr[48:58].decode('ascii').strip())
        except ValueError:
            break
        body = data[off + 60: off + 60 + size]
        yield name, body
        off += 60 + size + (size % 2)

for fn in sorted(os.listdir(debs)):
    if not fn.endswith('.deb'):
        continue
    nm = fn[:-4]
    dest = os.path.join(xdir, nm)
    os.makedirs(dest, exist_ok=True)
    found = False
    for name, body in ar_members(os.path.join(debs, fn)):
        if not name.startswith('data.tar'):
            continue
        found = True
        comp = name.split('.', 2)[-1] if name.count('.') >= 2 else 'gz'
        ext = {'xz': '.xz', 'gz': '.gz', 'zst': '.zst', 'bz2': '.bz2'}.get(comp, '.' + comp)
        tmp = os.path.join(dest, 'data.tar' + ext)
        open(tmp, 'wb').write(body)
        subprocess.run(['tar', '-xf', tmp, '-C', dest], check=True)
        os.remove(tmp)
    if not found:
        raise SystemExit('包里没有 data.tar：' + fn)
    print('  解包 %s ok' % nm)
PY

# ---------------------------------------------------------------- 4) 组装
log "组装到 assets …"
rm -rf "$OUT"
mkdir -p "$OUT/bin" "$OUT/lib"

NODE_BIN="$WORK/x/node/data/data/com.termux/files/usr/bin/node"
[ -s "$NODE_BIN" ] || die "没找到 node 二进制：$NODE_BIN"
cp "$NODE_BIN" "$OUT/bin/node"
chmod 755 "$OUT/bin/node"

# linker64：优先用 pk-node 自带的（已在真机验证过能跑 node）
LINKER_SRC="$ROOT/../pk-node/bin/native/linker64"
[ -s "$LINKER_SRC" ] || LINKER_SRC="/root/pk-node/bin/native/linker64"
[ -s "$LINKER_SRC" ] || die "找不到 linker64（需要 pk-node 的 bin/native/linker64）"
cp "$LINKER_SRC" "$OUT/bin/linker64"
chmod 755 "$OUT/bin/linker64"

# 依赖库
#
# ⚠️ deb 里同时有「真实文件」和「symbolic link」（如
#    libz.so.1.3.2 ← libz.so.1 ← libz.so）。
#    `find -type f` 只会拷到真实文件，导致运行时报
#    `library "libz.so.1" not found` —— 必须把链接一起还原。
log "拷贝依赖库（含 soname 符号链接）…"
python3 - "$WORK" "$OUT" <<'PY'
import os, shutil, sys

work, out = sys.argv[1], sys.argv[2]
libdir = os.path.join(out, 'lib')
copied = 0
links = 0

def find_lib_roots(base):
    """找到每个包解出来的 usr/lib 目录。"""
    roots = []
    for dp, dns, _ in os.walk(base):
        if os.path.basename(dp) == 'lib' and os.path.basename(os.path.dirname(dp)) == 'usr':
            roots.append(dp)
    return roots

# 先用「真实文件」，再用「符号链接」覆盖 —— 顺序很重要：
# 若先建链接、后被真实文件写了同名，链接会被实体覆盖。
pending_links = []

for root in find_lib_roots(os.path.join(work, 'x')):
    for name in sorted(os.listdir(root)):
        if '.so' not in name:
            continue
        src = os.path.join(root, name)
        dst = os.path.join(libdir, name)
        if os.path.islink(src):
            pending_links.append((os.readlink(src), name))
            continue
        if os.path.isfile(src):
            shutil.copy2(src, dst)
            copied += 1

# 再建链接（用相对链接，避免绝对路径失效）
for target, name in pending_links:
    dst = os.path.join(libdir, name)
    if os.path.lexists(dst):
        os.remove(dst)
    os.symlink(target, dst)
    links += 1

print('  真实文件 %d 个，符号链接 %d 个' % (copied, links))
PY

# Android 系统库：只拷这四个！
# ⚠️ 绝不能拷 libc++_shared.so（见文件头「坑 1」）。
for f in libc.so libm.so libdl.so liblog.so; do
  s="$ROOT/../pk-node/bin/native/$f"
  [ -s "$s" ] || s="/root/pk-node/bin/native/$f"
  [ -s "$s" ] && cp -a "$s" "$OUT/lib/"
done

# ---------------------------------------------------------------- 5) 校验 + manifest
log "生成 manifest.json（含每个文件的 sha256）…"
python3 - "$OUT" "$NODE_VER" <<'PY'
import hashlib, json, os, sys
out, ver = sys.argv[1], sys.argv[2]
files = {}
total = 0
for dp, _, fns in os.walk(out):
    for fn in sorted(fns):
        if fn == 'manifest.json':
            continue
        p = os.path.join(dp, fn)
        rel = os.path.relpath(p, out)
        h = hashlib.sha256(open(p, 'rb').read()).hexdigest()
        sz = os.path.getsize(p)
        total += sz
        files[rel] = {'sha256': h, 'size': sz}
man = {
    'node': ver.split('-')[0],
    'termuxDeb': ver,
    'arch': 'arm64-v8a',
    'entry': 'bin/linker64',
    'nodeBin': 'bin/node',
    'totalBytes': total,
    'files': files,
}
open(os.path.join(out, 'manifest.json'), 'w').write(json.dumps(man, indent=2) + '\n')
print('  %d 个文件，合计 %.1f MB' % (len(files), total / 1048576))
PY

# ---------------------------------------------------------------- 6) 自检
log "自检：真跑一次 node --version …"
RT_LIB="$OUT/lib"
if LD_LIBRARY_PATH="$RT_LIB" "$OUT/bin/linker64" "$OUT/bin/node" --version 2>/dev/null | grep -q '^v'; then
  log "✅ node 可运行：$(LD_LIBRARY_PATH="$RT_LIB" "$OUT/bin/linker64" "$OUT/bin/node" --version 2>/dev/null)"
else
  die "node 跑不起来 —— 检查 lib/ 是否齐全（尤其别把 libc++_shared.so 混进去）"
fi

if LD_LIBRARY_PATH="$RT_LIB" "$OUT/bin/linker64" "$OUT/bin/node" \
     -e "require('node:sqlite');console.log('sqlite ok')" 2>/dev/null | grep -q 'sqlite ok'; then
  log "✅ node:sqlite 可用（pk-node 的硬依赖）"
else
  die "node:sqlite 不可用 —— pk-node 跑不起来"
fi

log "完成。体积：$(du -sh "$OUT" | cut -f1)  位置：$OUT"
