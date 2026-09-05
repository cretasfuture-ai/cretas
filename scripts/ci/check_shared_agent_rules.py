#!/usr/bin/env python3
"""闸: `.claude/rules/` 与 `.codex/rules/` 里**登记为共享**的规则必须逐字相同。

## 为什么需要

Claude 读 `.claude/rules/`, Codex 读 `.codex/rules/`。同一条规则写在两处 =
形态 D「同一个东西有两份, 它一定会漂」。⛔ 而且这个漂**不报错**:
一边改了、另一边没改, 于是两个 agent 拿着不同的规则各干各的。

实测(2026-08-27): 两个目录下 9 个同名文件**全部已经漂了** ——
`worktree-and-main-only-deploy.md` 差 **147 行**(`.codex` 那份是旧快照)。

## 这道闸的范围: 只管 SHARED 清单里的, ⛔ 不管全部同名文件

一上来把 9 个存量漂移全钉红 = 一道当天就被关掉的闸(形态 E)。
⇒ 做成**棘轮**: 新写的共享规则登记进 SHARED, 立刻受保护;
   存量那 9 个另开任务收敛, 收一个登记一个。

用法:
    python scripts/ci/check_shared_agent_rules.py            # 校验
    python scripts/ci/check_shared_agent_rules.py --fix      # 以 .claude 为源同步过去
"""
import hashlib
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
CLAUDE_DIR = REPO / ".claude" / "rules"
CODEX_DIR = REPO / ".codex" / "rules"

# 登记为「两侧必须逐字相同」的规则。⛔ 加进来之前先确认两份真的该一样。
SHARED = [
    "ci-runner-routing.md",
]


def digest(p: pathlib.Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()[:12]


def main() -> int:
    fix = "--fix" in sys.argv
    problems = []
    checked = 0

    for name in SHARED:
        a = CLAUDE_DIR / name
        b = CODEX_DIR / name
        if not a.exists():
            problems.append("%s: .claude 侧不存在(源应在 .claude)" % name)
            continue
        if not b.exists():
            if fix:
                b.write_bytes(a.read_bytes())
                print("  已同步(新建) %s" % name)
            else:
                problems.append("%s: .codex 侧不存在" % name)
            continue
        checked += 1
        da, db = digest(a), digest(b)
        if da != db:
            if fix:
                b.write_bytes(a.read_bytes())
                print("  已同步(覆盖) %s" % name)
            else:
                problems.append(
                    "%s: 两侧不一致 (.claude=%s / .codex=%s)" % (name, da, db))

    # 指针: SHARED 之外的 .codex/rules/*.md 必须是指向 .claude 源的指针, 且目标存在
    # (2026-09-05 收敛: 9 个各自演化的旧快照全部改为指针, 这里守「指针别再长回正文、目标别失效」)
    pointer_checked = 0
    for b in sorted(CODEX_DIR.glob("*.md")):
        if b.name in SHARED or b.name.endswith(".example.md"):
            continue
        text = b.read_text(encoding="utf-8")
        targets = [ln.split(":", 1)[1].strip() for ln in text.splitlines() if ln.startswith("POINTER:")]
        if len(targets) != 1:
            problems.append("%s: 不是指针文件(需恰好一行 'POINTER: <path>'), 规则正文只能在 .claude 侧" % b.name)
            continue
        if not (REPO / targets[0]).exists():
            problems.append("%s: 指针目标不存在: %s" % (b.name, targets[0]))
            continue
        if len(text) > 1500:
            problems.append("%s: 指针文件长成正文了(%d 字节)" % (b.name, len(text)))
            continue
        pointer_checked += 1
    if pointer_checked == 0:
        problems.append(".codex/rules: 一个指针文件都没检查到(阳性对照失败)")

    # 阳性对照: 清单不能是空的, 否则这道闸永远绿而什么都没守
    if not SHARED:
        print("shared-rules: FAIL — SHARED 清单为空, 这道闸没有守任何东西")
        return 1

    if problems and not fix:
        print("shared-rules: FAIL (%d 个问题)" % len(problems))
        for p in problems:
            print("  " + p)
        print("")
        print("修法: python scripts/ci/check_shared_agent_rules.py --fix")
        print("  (以 .claude/rules/ 为源覆盖 .codex/rules/)")
        return 1

    print("shared-rules: PASS (%d 条共享规则两侧逐字相同)" % checked)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
