#!/usr/bin/env python3
r"""闸: `docs/dispatch/ACTIVE.md` 只能是一张薄台账。

## 为什么需要

台账是 organizer 的「外存」, 设计上每轮派活前都要读它。2026-09-05 实测它长到 175 KB /
570 行: 在飞段里嵌着 27 段增量日志, 38 条任务里 19 条早已 merged/done, 单条最长 4,640 字。
读它一次 ≈ 60K token, 于是没人读, 于是它不再是真值 —— 「精简 ACTIVE 是真值」这句话就空了。

Owner 2026-09-05 裁定: 每条 ≤ 300 字, organizer 派活时才读。详情、判据读数、增量日志一律写
`docs/dispatch/archive/`。

## 规则(全部机械)

1. `## 在飞任务` 下每条(`- \`ID\`` 起, 含缩进续行) ≤ 300 字符, 状态必须是
   queued / claimed / in-progress / review / blocked —— merged / done 不许留在在飞。
2. `## Scope 锁地图` 下每条 ≤ 300 字符。
3. 全文不许有 `###` 小节(增量日志属于 archive, 不属于台账)。
4. 全文 ≤ 30,000 bytes。
5. 阳性对照: 至少解析到 1 条在飞条目, 否则 rc=2「没量到」——一个把标题改掉的台账
   不该把这道闸变绿。

退出码三态: 0 合规 / 1 有违例 / 2 没量到(文件缺失、结构认不出、0 条)。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ENTRY_LIMIT = 300
SIZE_LIMIT = 30_000
ALLOWED_STATUS = {"queued", "claimed", "in-progress", "review", "blocked"}
FORBIDDEN_STATUS = {"merged", "done"}
HEAD_RE = re.compile(r"^- `(?P<id>[^`]+)`(?:（[^）]*）)? — `(?P<status>[^`]+)`")


def split_sections(text: str) -> dict[str, list[str]]:
    sections: dict[str, list[str]] = {}
    current = "__preamble__"
    sections[current] = []
    for line in text.split("\n"):
        if line.startswith("## "):
            current = line[3:].strip()
            sections.setdefault(current, [])
            continue
        sections[current].append(line)
    return sections


def entries_of(lines: list[str]) -> list[list[str]]:
    entries: list[list[str]] = []
    cur: list[str] | None = None
    for line in lines:
        if line.startswith("- `"):
            if cur:
                entries.append(cur)
            cur = [line]
        elif cur is not None and line.strip():
            cur.append(line)
        elif cur is not None and not line.strip():
            entries.append(cur)
            cur = None
    if cur:
        entries.append(cur)
    return entries


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=".")
    parser.add_argument("--file", default="docs/dispatch/ACTIVE.md")
    args = parser.parse_args()

    path = Path(args.repo) / args.file
    if not path.is_file():
        print(f"✗ {args.file} 不存在 —— 本次没量到")
        return 2
    raw = path.read_bytes()
    text = raw.decode("utf-8")
    problems: list[str] = []

    if len(raw) > SIZE_LIMIT:
        problems.append(f"全文 {len(raw):,} bytes > {SIZE_LIMIT:,}: 台账不是日志, 增量写 archive/")

    for n, line in enumerate(text.split("\n"), 1):
        if line.startswith("### "):
            problems.append(f"L{n}: `###` 小节不属于台账(增量日志写 archive/): {line[:60]}")

    sections = split_sections(text)
    if "在飞任务" not in sections:
        print("✗ 找不到 `## 在飞任务` —— 结构认不出, 本次没量到")
        return 2

    in_flight = entries_of(sections["在飞任务"])
    if not in_flight:
        print("✗ `## 在飞任务` 下 0 条条目 —— 本次没量到(空台账也要写一条占位说明是空的)")
        return 2

    for e in in_flight:
        body = "\n".join(e)
        m = HEAD_RE.match(e[0])
        label = m.group("id") if m else e[0][:40]
        if not m:
            problems.append(f"在飞 {label!r}: 首行不是 `- \\`ID\\` — \\`status\\`` 形状")
        else:
            st = m.group("status")
            if st in FORBIDDEN_STATUS:
                problems.append(f"在飞 {label}: 状态 `{st}` 不许留在在飞, 移入 archive/ 并释放 scope 锁")
            elif st not in ALLOWED_STATUS:
                problems.append(f"在飞 {label}: 状态 `{st}` 不在 {sorted(ALLOWED_STATUS)}")
        if len(body) > ENTRY_LIMIT:
            problems.append(f"在飞 {label}: {len(body)} 字 > {ENTRY_LIMIT}, 详情移到 archive/ 只留一句话与阻塞点")

    scope = entries_of(sections.get("Scope 锁地图", []))
    for e in scope:
        body = "\n".join(e)
        m = re.match(r"^- `([^`]+)`", e[0])
        label = m.group(1) if m else e[0][:40]
        if len(body) > ENTRY_LIMIT:
            problems.append(f"scope {label}: {len(body)} 字 > {ENTRY_LIMIT}, 目录级 glob 或指向 archive")

    if problems:
        print(f"dispatch-active: FAIL ({len(problems)} 个问题; 在飞 {len(in_flight)} 条, scope {len(scope)} 条)")
        for p in problems:
            print("  " + p)
        return 1
    print(f"dispatch-active: PASS (在飞 {len(in_flight)} 条 / scope {len(scope)} 条, 全部 ≤ {ENTRY_LIMIT} 字; 全文 {len(raw):,} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
