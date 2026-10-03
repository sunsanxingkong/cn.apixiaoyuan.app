#!/usr/bin/env python3
"""
把 node 运行时「规范化」成可以放进 Android nativeLibraryDir 的布局。

## 为什么需要这一步

Android 10+ 只允许从 `nativeLibraryDir` 执行代码，而那里：
  1. 只可靠地容纳 **`lib*.so`** 命名的文件（APK 里只有这些会被对齐/解压）；
  2. 文件是**平铺**的（没有子目录、没有符号链接）。

而 Termux 解出来的库是这样的：
```
libz.so.1.3.2                 ← 真实文件
libz.so.1 -> libz.so.1.3.2    ← 符号链接（soname）
libz.so   -> libz.so.1        ← 符号链接（dev）
libicudata.so.78.3            ← 真实文件
libicudata.so.78 -> ...       ← 符号链接
```
且 `libnode.so` 的 `DT_NEEDED` 写的是 **`libz.so.1`**、**`libicudata.so.78`** 这类
**带版本号**的名字 —— 而带版本号的名字没法安全地放进 nativeLibraryDir。

## 做法

对每个库：
  1. 文件名统一成 **无版本号**（`libz.so` / `libicudata.so` / `libcrypto.so`…）；
  2. 用 `patchelf --set-soname` 把它的 **soname 也改成无版本号**；
  3. 把**所有**引用了旧名字的 .so 里的 `DT_NEEDED` 用 `--replace-needed` 改过来。

`libc++_shared.so` 额外改名（否则会和 App 自带的同名库冲突，两者符号不兼容）：
`libc++_shared.so` → **`libcxx_node.so`**。

## ⚠️ 必须同时保留 `libz.so` 与它的旧名场景

OpenSSL 运行时会 `dlopen("libz.so")`（DSO support），所以「文件名 = libz.so」
正好命中；而 `libnode.so` 的 NEEDED 已经被改成 `libz.so`，也命中。**两全**。

用法：
  python3 tools/normalize-node-libs.py <源目录> <目标目录>
"""
import os
import re
import shutil
import subprocess
import sys

# 版本号形态：
#   1) 尾部：libz.so.1 / libicudata.so.78.3
#   2) 中间：libsqlite3.53.4.so（termux 的 libsqlite 就是这么命名的）
VER_RE = re.compile(r'^(lib[^/]*?)(?:\.\d+(?:\.\d+)*)?\.so(?:\.\d+(?:\.\d+)*)?$')

# 与 App 自带库冲突的那个：改名
RENAME = {
    'libc++_shared.so': 'libcxx_node.so',
}


def canonical(name):
    """把 `libz.so.1.3.2` / `libicudata.so.78` / `libsqlite3.53.4.so` 归一成 `libz.so` 等。"""
    if name in RENAME:
        return RENAME[name]
    m = VER_RE.match(name)
    # 归一后必须仍以 .so 结尾、且以 lib 开头，否则不认（避免误伤 libfoo.so 之类）
    if not m:
        return None
    return m.group(1) + '.so'


def readelf_needed(path):
    """读出 DT_NEEDED 列表。"""
    try:
        out = subprocess.run(['readelf', '-dW', path], capture_output=True, text=True, check=True).stdout
    except Exception:
        return []
    names = []
    for line in out.splitlines():
        if 'NEEDED' in line and '[' in line:
            names.append(line.split('[')[1].split(']')[0].strip())
    return names


def readelf_soname(path):
    try:
        out = subprocess.run(['readelf', '-dW', path], capture_output=True, text=True, check=True).stdout
    except Exception:
        return None
    for line in out.splitlines():
        if 'SONAME' in line and '[' in line:
            return line.split('[')[1].split(']')[0].strip()
    return None


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    src, dst = sys.argv[1], sys.argv[2]

    os.makedirs(dst, exist_ok=True)

    # ---------- 1) 收集「真实文件」（跳过所有符号链接） ----------
    real = {}
    for name in sorted(os.listdir(src)):
        if '.so' not in name:
            continue
        p = os.path.join(src, name)
        if os.path.islink(p) or not os.path.isfile(p):
            continue
        canon = canonical(name)
        if not canon:
            continue
        # 同名冲突时，优先保留「无版本号更少」的那个（即更原始的）
        if canon not in real or len(name) < len(real[canon][0]):
            real[canon] = (name, p)

    print('真实库文件 %d 个' % len(real))

    # ---------- 2) 建「旧名 → 新名」映射 ----------
    # 覆盖所有可能被 NEEDED 引用的写法：原名 / soname / 归一后的名字
    alias = {}
    for canon, (name, p) in real.items():
        alias[name] = canon
        sn = readelf_soname(p)
        if sn:
            alias[sn] = canon
        alias[canon] = canon

    # ---------- 3) 拷成「无版本号」文件名 + 设 soname ----------
    for canon, (name, p) in real.items():
        out = os.path.join(dst, canon)
        shutil.copy2(p, out)
        os.chmod(out, 0o755)
        subprocess.run(['patchelf', '--set-soname', canon, out], check=True)

    # ---------- 4) 修正所有 DT_NEEDED ----------
    #
    # ⚠️ 除了依赖库自己，**还要处理「主程序」**（node 二进制，改名 libnode.so）。
    #    它的 DT_NEEDED 同样写着 `libz.so.1` / `libicudata.so.78` 这类带版本号的名字，
    #    漏掉它就会在启动时报 `library "libz.so.1" not found`。
    #    主程序通过第三个参数传入（不传则跳过）。
    extra = sys.argv[3] if len(sys.argv) > 3 else None
    targets = [os.path.join(dst, c) for c in real]
    if extra:
        if not os.path.exists(extra):
            print('⚠️ 主程序不存在，跳过：%s' % extra)
        else:
            targets.append(extra)

    fixed = 0
    for out in targets:
        for need in readelf_needed(out):
            new = alias.get(need)
            if new and new != need:
                subprocess.run(['patchelf', '--replace-needed', need, new, out], check=True)
                fixed += 1
    print('修正 DT_NEEDED 引用 %d 处（含主程序）' % fixed)

    # ---------- 5) 报告 ----------
    total = 0
    for name in sorted(os.listdir(dst)):
        sz = os.path.getsize(os.path.join(dst, name))
        total += sz
        print('  %-24s %7.1f MB' % (name, sz / 1048576))
    print('合计 %.1f MB' % (total / 1048576))

    # ---------- 6) 残留检查：确认没有指向带版本名的 NEEDED ----------
    bad = []
    for name in sorted(os.listdir(dst)):
        for need in readelf_needed(os.path.join(dst, name)):
            if VER_RE.match(need) and re.search(r'\.so\.\d', need):
                bad.append('%s -> %s' % (name, need))
    if bad:
        print('\n⚠️ 仍有带版本号的 NEEDED（会放不进 nativeLibraryDir）：')
        for b in bad:
            print('   ' + b)
        sys.exit(1)
    print('\n✅ 全部依赖名已归一为无版本号形式')


if __name__ == '__main__':
    main()
