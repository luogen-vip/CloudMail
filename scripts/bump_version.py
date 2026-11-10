#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
自动升版脚本（CloudMail）
用法：
  python3 scripts/bump_version.py                  # versionName 的 patch +1，versionCode +1
  python3 scripts/bump_version.py --bump minor     # 1.3.28 -> 1.4.0
  python3 scripts/bump_version.py --bump major     # 1.3.28 -> 2.0.0
  python3 scripts/bump_version.py --version 1.5.0  # 指定版本号，versionCode 仍自动 +1

在 GitHub Actions 中会把 version / version_code 写入 $GITHUB_OUTPUT，供后续步骤引用。
"""

import argparse
import os
import re
import sys

DEFAULT_GRADLE = "app/build.gradle.kts"


def main() -> int:
    parser = argparse.ArgumentParser(description="升版本号并写入 build.gradle.kts")
    parser.add_argument("--bump", default="patch", choices=["patch", "minor", "major"])
    parser.add_argument("--version", default="", help="指定 versionName，留空则按 --bump 自动计算")
    parser.add_argument("--file", default=DEFAULT_GRADLE)
    args = parser.parse_args()

    with open(args.file, encoding="utf-8") as fh:
        src = fh.read()

    m_code = re.search(r"versionCode\s*=\s*(\d+)", src)
    m_name = re.search(r'versionName\s*=\s*"([^"]+)"', src)
    if not m_code or not m_name:
        print("错误：在 %s 中找不到 versionCode / versionName" % args.file, file=sys.stderr)
        return 2

    old_code = int(m_code.group(1))
    old_name = m_name.group(1)
    custom = args.version.strip().lstrip("vV")

    if custom:
        new_name = custom
    else:
        parts = [int(x) for x in re.split(r"[.\-]", old_name) if x.isdigit()][:3]
        parts += [0] * (3 - len(parts))
        if args.bump == "major":
            parts = [parts[0] + 1, 0, 0]
        elif args.bump == "minor":
            parts = [parts[0], parts[1] + 1, 0]
        else:
            parts[2] += 1
        new_name = ".".join(str(p) for p in parts)

    new_code = old_code + 1

    new_src = re.sub(r"versionCode\s*=\s*\d+", "versionCode = %d" % new_code, src, count=1)
    new_src = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "%s"' % new_name, new_src, count=1)
    if new_src == src:
        print("错误：版本号未发生变化，请检查 build.gradle.kts 格式", file=sys.stderr)
        return 3

    with open(args.file, "w", encoding="utf-8") as fh:
        fh.write(new_src)

    print("旧: versionCode=%d versionName=%s" % (old_code, old_name))
    print("新: versionCode=%d versionName=%s" % (new_code, new_name))

    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as fh:
            fh.write("version=%s\nversion_code=%d\n" % (new_name, new_code))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
