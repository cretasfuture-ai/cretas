#!/usr/bin/env python3
"""常驻规则闸（2026-11-13）：

1. 字节预算：CLAUDE.md + 所有不带 `paths:` frontmatter 的 `.claude/rules/*.md`（每个会话开场都加载、
   每轮重读）总和 <= 预算（默认 40000 字节）。超了不是「再压一压」就完事：要把内容挪到按路径加载 /
   skill / 案例库，或把规则加 `paths:`。
2. 登记：`.claude/rules/` 下每个规则文件的文件名必须出现在 CLAUDE.md（常驻清单或按路径加载清单）。
   CLAUDE.md 的清单是手工维护的，漏登记 = 这条规则不会被人想起（measurement-and-wiring 形态 B）。
3. 可执行位（--exec-bit-base <ref>）：比较 <ref> 与 HEAD 的树，任何文件 100755 -> 100644 报红。
   `git commit -- <paths>` 在 core.fileMode=false 下会静默丢掉暂存的 +x，本机绿、CI 才红。
   有意去掉可执行位的路径写进 scripts/ci/exec-bit-allowlist.txt（一行一个）。

退出码：0 通过；1 违规；2 没量到（仪器起不来：不在 git 仓库、ref 不存在、读不到文件）。
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

DEFAULT_BUDGET = 40000


def has_paths_frontmatter(text: str) -> bool:
    if not text.startswith("---"):
        return False
    end = text.find("\n---", 3)
    if end < 0:
        return False
    return re.search(r"^paths\s*:", text[3:end], re.M) is not None


def check_budget(root: Path, budget: int):
    claude = root / "CLAUDE.md"
    rules = sorted((root / ".claude" / "rules").glob("*.md"))
    if not claude.is_file() or not rules:
        raise RuntimeError("找不到 CLAUDE.md 或 .claude/rules/*.md（样本 0 条，没量到）")
    total = claude.stat().st_size
    rows = [("CLAUDE.md", total)]
    for r in rules:
        data = r.read_bytes()
        if has_paths_frontmatter(data.decode("utf-8", "replace")):
            continue
        rows.append((r.name, len(data)))
        total += len(data)
    problems = []
    if total > budget:
        top = sorted(rows, key=lambda x: -x[1])[:5]
        problems.append(
            "常驻规则 %d 字节 > 预算 %d：最大 5 项 %s。把背景挪进 docs/rules-casebook、命令挪进 skill、"
            "或给规则加 paths: 按路径加载" % (total, budget, ", ".join("%s=%d" % t for t in top))
        )
    return total, rows, problems


def check_registry(root: Path):
    claude_text = (root / "CLAUDE.md").read_text(encoding="utf-8")
    problems = []
    names = sorted(p.name for p in (root / ".claude" / "rules").glob("*.md"))
    for n in names:
        if n not in claude_text:
            problems.append("规则文件 %s 没有登记进 CLAUDE.md（常驻或按路径加载清单）" % n)
    return names, problems


def git(root: Path, *args):
    p = subprocess.run(["git", *args], cwd=root, capture_output=True)
    if p.returncode != 0:
        raise RuntimeError("git %s 失败 rc=%d: %s" % (" ".join(args), p.returncode, p.stderr.decode("utf-8", "replace").strip()))
    return p.stdout.decode("utf-8", "replace")


def check_exec_bit(root: Path, base: str):
    allow_file = root / "scripts" / "ci" / "exec-bit-allowlist.txt"
    allow = set()
    if allow_file.is_file():
        allow = {l.strip() for l in allow_file.read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")}
    out = git(root, "-c", "core.quotepath=false", "diff", "--raw", "--no-renames", base, "HEAD")
    dropped, seen = [], 0
    for line in out.splitlines():
        m = re.match(r"^:(\d{6}) (\d{6}) \S+ \S+ (\S+)\t(.+)$", line)
        if not m:
            continue
        seen += 1
        old, new, _status, path = m.group(1), m.group(2), m.group(3), m.group(4)
        if old == "100755" and new == "100644" and path not in allow:
            dropped.append(path)
    problems = ["%s 可执行位 100755 -> 100644（提交丢了 +x；`git update-index --chmod=+x <path>` 补回，有意去掉则登记 scripts/ci/exec-bit-allowlist.txt）" % p for p in dropped]
    return seen, problems


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--budget", type=int, default=DEFAULT_BUDGET)
    ap.add_argument("--exec-bit-base", default=None, help="比较该 ref 与 HEAD 的可执行位变化")
    ap.add_argument("--skip-rules", action="store_true", help="只做可执行位检查")
    a = ap.parse_args(argv)
    root = Path(a.root).resolve()
    problems = []
    try:
        if not a.skip_rules:
            total, rows, p1 = check_budget(root, a.budget)
            names, p2 = check_registry(root)
            problems += p1 + p2
            print("resident-rules: 常驻 %d 字节 / 预算 %d（%d 项）；规则文件 %d 个均检查登记" % (total, a.budget, len(rows), len(names)))
        if a.exec_bit_base:
            seen, p3 = check_exec_bit(root, a.exec_bit_base)
            problems += p3
            print("exec-bit: %s..HEAD 共 %d 个文件变更已逐个核对模式" % (a.exec_bit_base, seen))
    except (RuntimeError, OSError) as e:
        print("resident-rules: 没量到（rc=2）: %s" % e)
        return 2
    for p in problems:
        print("FAIL: " + p)
    if problems:
        return 1
    print("resident-rules: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
