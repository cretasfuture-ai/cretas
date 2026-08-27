#!/usr/bin/env python3
"""源码里不许出现**字面 NUL 字节** —— 它会让整份文件对每一道 grep 闸隐形。

## 2026-08-27 实测（原 NoLiteralNulByteInSourceContractTest 的记录，原样保留）

三个文件里躺着 9 个真实的 ``0x00``（都是拼 key 用的分隔符 ``a + "<NUL>" + b``）。
后果不是编译失败 —— 它**编得过、跑得对**，但 ``grep`` 会把整份文件判成二进制
并**整份跳过**::

    $ grep -c "settleProduction" ProductionPlanServiceImpl.java
    Binary file ProductionPlanServiceImpl.java matches      ← 没有计数, 没有行号

两个中招的是核心服务：``ProductionPlanServiceImpl``（479KB）与
``BomRecipeServiceImpl``（188KB）。**本仓大量依赖源码级扫描闸**（黑话闸、
单位契约闸、UTC 截断闸、变异可达性闸…），它们对这 667KB 代码**全都是瞎的**，
而且不会报错 —— 只是安静地少扫两个文件。

## 为什么值得单独立一道闸

这一类缺陷**没有任何现有信号会提示它**：编译过、测试绿、IDE 正常显示、
git diff 也看不出（git 只探前 8000 字节判二进制，NUL 在更后面就侥幸不报）。
唯一的症状是「某道闸的读数偏小」，而偏小的读数长得和「没有问题」一模一样。

## 🔴 为什么从 JUnit 搬到这里（2026-08-27）

原来它是 ``backend/java/.../NoLiteralNulByteInSourceContractTest``。
它**扫全仓**（后缀含 ``.ts`` ``.vue`` ``.py``），但作为一个 JUnit 测试，
只有 ``backend/java/cretas-api/**`` 变化时才会跑。

⇒ **一个只改 ``.ts`` / ``.vue`` / ``.py`` 的 PR，它一次都不会执行。**

当天实测：一个新写的 RN 闸文件带着 2 个字面 ``0x00`` 进了 PR，
``git`` 报 ``Bin 0 -> 8343 bytes`` —— 是人看 ``git show --stat`` 发现的，
不是闸发现的，因为那道闸根本没被触发。

**判据：一道扫全仓的闸，触发面也必须是全仓。**
⛔ 反过来把 Java 闸的 ``paths:`` 撑成整个仓是错的 —— 那会让每个 PR 都跑 5 分钟 Java。
⇒ 所以搬成一个几秒钟的独立脚本，挂在**不带 ``paths:``** 的 workflow 上。

## 退出码是三态（本仓硬约束 4）

===  ============================================================
rc   含义
===  ============================================================
0    干净
1    找到了字面 NUL          ⇒ 🔴 有文件对 grep 闸隐形
2    **这次没量到**          ⇒ ⚠️ 扫到的文件数低于下界，读数作废
===  ============================================================

``rc=2`` 必须与 ``rc=1`` 用**不同措辞**告警 —— 否则「没量到」会被折叠进
「没问题」，一个跑在空目录里的检查会安静地天天绿。

用法::

    python scripts/ci/check_no_nul_bytes.py [--repo <路径>] [--min-files N]
"""

from __future__ import annotations

import argparse
import os
import sys

RC_OK, RC_FOUND, RC_NOT_MEASURED = 0, 1, 2

SCAN_SUFFIXES = (".java", ".ts", ".tsx", ".vue", ".py", ".sql")
SKIP_DIRS = {"node_modules", ".git", "target", "dist", ".codex-build", "build", ".venv"}

# ⛔ 不写 b"\x00" —— 本文件正是那道「不许有裸 NUL」的闸，
#    用显式构造而不是转义序列，从物理上不可能因为某个工具把转义写成真字节而自伤。
#    （这正是它要防的那件事，仓里已经发生过一次。）
NUL = bytes([0])


def source_files(repo: str):
    for dirpath, dirnames, filenames in os.walk(repo):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if name.endswith(SCAN_SUFFIXES):
                yield os.path.join(dirpath, name)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=".")
    ap.add_argument(
        "--min-files",
        type=int,
        default=2000,
        help="阳性对照下界：扫到的文件少于它，判定为「没量到」(rc=2) 而不是「没问题」",
    )
    args = ap.parse_args()

    scanned = 0
    offenders = []
    for path in source_files(args.repo):
        scanned += 1
        try:
            with open(path, "rb") as fh:
                data = fh.read()
        except OSError as exc:                      # 读不到就说出来，⛔ 不静默跳过
            offenders.append((path, "READ_ERROR: %s" % exc))
            continue
        count = data.count(NUL)
        if count:
            rel = os.path.relpath(path, args.repo).replace(os.sep, "/")
            offenders.append((rel, "NUL x%d" % count))

    print("NUL_SCAN scanned_files=%d offenders=%d" % (scanned, len(offenders)))

    # 🔴 阳性对照先判 —— 扫了 0 个文件时「offenders 为空」是假读数，
    #    它和「真的干净」长得一模一样。
    if scanned < args.min_files:
        print(
            "NUL_SCAN INSTRUMENT_DEAD —— 只扫到 %d 个源文件（下界 %d）。"
            "本次读数作废，⛔ 不代表没有 NUL。" % (scanned, args.min_files)
        )
        return RC_NOT_MEASURED

    if offenders:
        print("NUL_SCAN FOUND —— 下列文件对每一道 grep 闸都是隐形的（grep 会整份跳过，且不报错）：")
        for rel, note in sorted(offenders):
            print("   %s (%s)" % (rel, note))
        print("")
        print("修法：字符串字面量里用转义代替裸字节 —— Java 用 \\0，")
        print("      TS/JS 用 \\u0000，Python 用 chr(0) / bytes([0])。")
        print("⚠️ 更好的做法是**根本不用哨兵字符**：需要两段式替换时，")
        print("   改成一次 replace 带回调，物理上就没有哨兵这个东西。")
        return RC_FOUND

    return RC_OK


if __name__ == "__main__":
    sys.exit(main())
