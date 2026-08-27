#!/usr/bin/env python3
"""在「托管 runner」与「自建 runner」之间切换 CI。

## 为什么不是一个组织变量

第一版写的是 `runs-on: ${{ vars.CI_RUNNER && fromJSON(vars.CI_RUNNER) || 'ubuntu-latest' }}`。
**实测不生效**: job 被派成 `labels=ubuntu-latest`, 即 `vars` 在 `runs-on` 求值时读到空。
排查过并排除的: 变量 `visibility=all`、组织变量对本仓可见(API 查得到)、
创建时间早于 run、PR 非 fork。
⇒ `runs-on` 在 job 编排阶段求值, 那时的上下文比步骤里窄, `vars` 用不了。

所以改成**改文件**。代价是要提交一次, 好处是它**确定可用**, 且改了什么一目了然。

## 用法

    python scripts/ci/switch_ci_runner.py self-hosted   # 额度耗尽时
    python scripts/ci/switch_ci_runner.py hosted        # 额度恢复时(每月 1 号)
    python scripts/ci/switch_ci_runner.py --check       # 只报告当前状态

⚠️ 改完要 commit + push 才生效。
"""
import io
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
WORKFLOWS = ["secret-regression.yml", "web-admin-gate.yml", "rn-gate.yml"]

HOSTED = "    runs-on: ubuntu-latest"
SELF = "    runs-on: [self-hosted, cretas]"


def read(p):
    return io.open(p, "r", encoding="utf-8", newline="").read()


def current(text):
    if SELF in text:
        return "self-hosted"
    if HOSTED in text:
        return "hosted"
    return "unknown"


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    check_only = "--check" in sys.argv
    target = args[0] if args else None

    if not check_only and target not in ("hosted", "self-hosted"):
        print(__doc__)
        return 2

    states = {}
    for name in WORKFLOWS:
        p = REPO / ".github" / "workflows" / name
        if not p.exists():
            print("  ✗ 找不到 %s" % name)
            return 1
        states[name] = current(read(p))

    # 三处必须一致 —— 改共享结构最容易的坏法就是只改一处
    distinct = set(states.values())
    if len(distinct) != 1:
        print("ci-runner: FAIL — 三个工作流不一致, 先修好再切:")
        for k, v in states.items():
            print("  %-26s %s" % (k, v))
        return 1

    now = distinct.pop()
    if check_only:
        print("ci-runner: 当前 = %s (%d 个工作流一致)" % (now, len(WORKFLOWS)))
        return 0 if now != "unknown" else 1

    if now == target:
        print("ci-runner: 已经是 %s, 无需改动" % target)
        return 0

    frm, to = (HOSTED, SELF) if target == "self-hosted" else (SELF, HOSTED)
    for name in WORKFLOWS:
        p = REPO / ".github" / "workflows" / name
        s = read(p)
        nl = "\r\n" if "\r\n" in s else "\n"
        # 断言只有一处 —— 多了少了都是意外
        assert s.count(frm) == 1, "%s: 命中 %d 处, 预期 1" % (name, s.count(frm))
        io.open(p, "w", encoding="utf-8", newline="").write(s.replace(frm, to))
        print("  %s -> %s" % (name, target))

    print("")
    print("ci-runner: 已切到 %s。⚠️ 要 commit + push 才生效。" % target)
    if target == "self-hosted":
        print("  自建 runner: 加拿大 Mac-mini-01, 标签 [self-hosted, cretas]")
        print("  在线检查: gh api orgs/cretasfuture-ai/actions/runners --jq '.runners[]|.name+\" \"+.status'")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
