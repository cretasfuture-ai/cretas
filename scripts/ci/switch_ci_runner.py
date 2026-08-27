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
# ⚠️ 往这里加一个工作流之前, 先过三关(2026-08-27 接 python-gate / jpa-gate 时逐条踩过):
#   1. `runs-on` 那一行必须**正好**是 4 个空格缩进 —— 下面是字面量匹配, 缩进不同会静默不命中。
#   2. 有没有 `actions/setup-python`? 自建 macOS runner 上它必失败
#      (安装脚本把 `/Users/runner` 写死), 要按 secret-regression.yml 的写法条件化。
#   3. 有没有 **Linux 专有命令**? `nproc` / `free` / `apt-get` 在 macOS 上直接不存在。
#      jpa-gate 就有一步 `nproc` + `free -h`, 只负责打印却能弄红整个 job。
# ⛔ 只把文件名加进来、不做这三关, 换来的是「从跑不起来」变成「跑起来就红」。
WORKFLOWS = [
    "secret-regression.yml",
    "web-admin-gate.yml",
    "rn-gate.yml",
    "python-gate.yml",
    "jpa-gate-main.yml",
]

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
    adopt = "--adopt" in sys.argv
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

    # 各处必须一致 —— 改共享结构最容易的坏法就是只改一处
    distinct = set(states.values())
    if len(distinct) != 1 and not adopt:
        print("ci-runner: FAIL — %d 个工作流不一致, 先修好再切:" % len(WORKFLOWS))
        for k, v in states.items():
            print("  %-26s %s" % (k, v))
        print("")
        # 🔴 2026-08-27: 这道一致性闸曾经把**它自己设计的接入路径**堵死了 ——
        #    往 WORKFLOWS 加一个新工作流的那一刻, 状态必然不一致(新的还是 hosted),
        #    于是拒绝切; 而唯一的出路「手改单个 runs-on」正是规则明令禁止的。
        #    ⇒ 给它一个显式的收敛口。⛔ 它不是 --force: 它只做「全部拉齐到 target」,
        #      并把**每一个被改动的文件逐行打出来**, 不允许静默拉齐。
        print("  新接入一个工作流时用: python %s %s --adopt"
              % (pathlib.Path(__file__).name, target or "<hosted|self-hosted>"))
        return 1

    if check_only:
        now = distinct.pop() if len(distinct) == 1 else "unknown"
        print("ci-runner: 当前 = %s (%d 个工作流一致)" % (now, len(WORKFLOWS)))
        return 0 if now != "unknown" else 1

    if len(distinct) == 1 and distinct.copy().pop() == target:
        print("ci-runner: 已经是 %s, 无需改动" % target)
        return 0

    frm, to = (HOSTED, SELF) if target == "self-hosted" else (SELF, HOSTED)
    changed = 0
    for name in WORKFLOWS:
        p = REPO / ".github" / "workflows" / name
        s = read(p)
        if states[name] == target:
            # --adopt 下会出现「一部分已经是目标态」, 这不是错误, 但要**说出来**,
            # ⛔ 不能让「没改」和「改了」在输出里长得一样。
            print("  %-26s 已经是 %s, 跳过" % (name, target))
            continue
        # 只许命中一处 —— 多了少了都是意外。
        # ⛔ 不用 assert: `python -O` 会把它整条剥掉, 那样这道防线在某些跑法下不存在。
        if s.count(frm) != 1:
            print("ci-runner: FAIL — %s 里 `%s` 命中 %d 处, 预期 1"
                  % (name, frm.strip(), s.count(frm)))
            print("  (已改动 %d 个文件, 工作树现在是**半途状态**, 用 git 看一眼再决定)" % changed)
            return 1
        io.open(p, "w", encoding="utf-8", newline="").write(s.replace(frm, to))
        changed += 1
        print("  %-26s -> %s" % (name, target))

    print("")
    print("ci-runner: 改动 %d 个文件, 跳过 %d 个。" % (changed, len(WORKFLOWS) - changed))
    print("ci-runner: 已切到 %s。⚠️ 要 commit + push 才生效。" % target)
    if target == "self-hosted":
        print("  自建 runner: 加拿大 Mac-mini-01, 标签 [self-hosted, cretas]")
        print("  在线检查: gh api orgs/cretasfuture-ai/actions/runners --jq '.runners[]|.name+\" \"+.status'")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
