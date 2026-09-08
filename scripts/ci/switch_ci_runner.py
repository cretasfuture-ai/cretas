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
# ⚠️ 往这里加一个工作流之前, 先过六关。
#    2026-08-27 接 python-gate / jpa-gate / (试过 ci.yml) 时逐条**实测**踩出来的:
#
#   1. **缩进**: `runs-on` 那一行必须正好 4 个空格 —— 下面是字面量匹配, 不同会静默不命中。
#   2. **actions/setup-python**: 自建 macOS 上必失败(安装脚本把 `/Users/runner` 写死),
#      要按 secret-regression.yml 的写法条件化 + 建 venv(homebrew 的 3.11+ 带 PEP 668)。
#   3. **Linux 专有命令**: `nproc` / `free` / `apt-get` / `stat -c` 在 macOS 上不存在。
#      jpa-gate 就有一步 `nproc` + `free -h`, 只负责打印却能弄红整个 job。
#   4. **缺工具**: `setup-java` 只装 JDK 不装 Maven -> `mvn: command not found`;
#      homebrew 只给 `pip3` / `python3`, **没有裸 `pip` 也没有裸 `python`**。
#      ⇒ 探测 + 兜底(`./mvnw` / `python3` / venv 里的 `python`)。
#      🔴 2026-08-27 实测(run 33041333971): `java-full-nightly` 的棘轮那一步调裸 `python`,
#         rc=127; 而它的判定只列了 0/1/2 ⇒ **两条判定全被 skip, job 报 success,
#         artifact 只有 146 字节**。⇒ 扫的时候**不能只看 `uses:`**, run 步骤里的
#         裸解释器/裸工具同样要查。
#   5. **平台专用 wheel**: `torch==2.4.1+cpu` 的 `+cpu` 是 Linux 专用本地版本标识。
#   6. 🔴 **`services:` / 容器**: 这台自建 runner **没有 Docker** ——
#      实测 `ci.yml` 一 dispatch 就 `##[error]docker: command not found`,
#      job 在 **Set up job 阶段**就死, 一个 step 都执行不到(run 33037689686, 9 秒)。
#      ⇒ 带 `services:` 的工作流**在这台机器上接不进来**, 与 YAML 怎么写无关。
#
# ⛔ 只把文件名加进来、不逐条过这六关, 换来的是「从跑不起来」变成「跑起来就红」。
#
# 📌 `ci.yml` 试过并**撤回**了: 它有 4 个 job(所以下面改成「N 处全换」), 但它的
#    java-build-test 依赖 pgvector 服务容器 + psql ⇒ 卡在第 6 关。它仍写死
#    ubuntu-latest, 额度耗尽期间产不出制品 —— 那是**机器侧**的事(给 runner 装 Docker,
#    或换一台 Linux self-hosted), 不是这个脚本能解决的。
WORKFLOWS = [
    "secret-regression.yml",
    "web-admin-gate.yml",
    "rn-gate.yml",
    "python-gate.yml",
    "jpa-gate-main.yml",
    "java-artifact.yml",
    "java-full-nightly.yml",
    "tool-inventory-gate.yml",
    "web-dist.yml",
    # 调度器 canary —— 只有一步 echo, 但它带 runs-on, 所以必须在这张清单里,
    # 否则切回托管时它切不动(载体没登记, 与 java-gate-pr.yml 当初那次同形)。
    "scheduler-canary.yml",
    # Verified on the isolated Linux runner; neither uses service containers.
    "ops-script-tests.yml",
    "soft-delete-scan-surface.yml",
]

HOSTED = "    runs-on: ubuntu-latest"
SELF = "    runs-on: [self-hosted, cretas]"


def read(p):
    return io.open(p, "r", encoding="utf-8", newline="").read()


def current(text):
    """一个文件当前落在哪一侧。

    ⚠️ 2026-08-27: 原来是 `if SELF in text: return "self-hosted"` 先判 SELF ——
    一个**半转换**的文件(两种都有)会被报成 self-hosted, 正好把
    「共享结构只改一处」那种坏法**藏起来**。而多 job 的文件正是最容易半转换的
    (ci.yml 有 4 个 job)。⇒ 两种都数, 同时存在就是显式的坏状态 `mixed`。
    """
    n_self, n_hosted = text.count(SELF), text.count(HOSTED)
    if n_self and n_hosted:
        return "mixed"
    if n_self:
        return "self-hosted"
    if n_hosted:
        return "hosted"
    return "unknown"


def _remind_if_quota_reset(now: str) -> None:
    """托管额度每月 1 号重置。停在自建上不会报错, 只是白白丢掉
    「干净机器 + 第三方执行」的独立性 —— 而**没有任何东西会提醒你切回去**。

    ⚠️ 这里**只提醒不失败**: 一道会把 PR 弄红的提醒最终会被关掉(形态 E)。
    用 GitHub 的 `::warning::` 注解, 它出现在 PR 摘要页而不是只躺在日志里。
    """
    if now != "self-hosted":
        return
    import datetime
    day = datetime.date.today().day
    if day > 7:
        return
    msg = ("托管 Actions 额度每月 1 号重置, 而 CI 仍停在自建 runner 上。"
           "确认额度已恢复后执行: python scripts/ci/switch_ci_runner.py hosted")
    print("::warning title=CI 可以切回托管了::%s" % msg)
    print("  ⚠ %s" % msg)


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
        if any(v == "mixed" for v in states.values()):
            print("ci-runner: FAIL — 有文件处于 **mixed**(同一文件里两种 runs-on 并存),")
            print("  那是「多 job 文件只改了一半」的长相:")
            for k, v in states.items():
                if v == "mixed":
                    print("  %-26s %s" % (k, v))
            return 1
        print("ci-runner: 当前 = %s (%d 个工作流一致)" % (now, len(WORKFLOWS)))
        _remind_if_quota_reset(now)
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
        # ⚠️ 2026-08-27: 原来这里断言「恰好 1 处」—— 而 `ci.yml` 有 **4 个 job**,
        #    每个都有自己的 runs-on。那条断言把多 job 的工作流**从物理上排除**在
        #    这套路由之外, 而 ci.yml 恰恰是发布链 `--prefer-ci-artifact` 要用的
        #    那个制品的产出方, 额度耗尽时它死掉是有生产影响的。
        # ⇒ 改成「至少 1 处, 全部替换」, 并把**每个文件改了几处**打出来 ——
        #   数目是这里唯一能自证「没有只改一半」的东西。
        # ⛔ 不用 assert: `python -O` 会把它整条剥掉, 那样这道防线在某些跑法下不存在。
        n = s.count(frm)
        if n < 1:
            print("ci-runner: FAIL — %s 里一处 `%s` 都没有(当前状态 %s)"
                  % (name, frm.strip(), states[name]))
            print("  (已改动 %d 个文件, 工作树现在是**半途状态**, 用 git 看一眼再决定)" % changed)
            return 1
        io.open(p, "w", encoding="utf-8", newline="").write(s.replace(frm, to))
        changed += 1
        print("  %-26s -> %s  (%d 处)" % (name, target, n))

    print("")
    print("ci-runner: 改动 %d 个文件, 跳过 %d 个。" % (changed, len(WORKFLOWS) - changed))
    print("ci-runner: 已切到 %s。⚠️ 要 commit + push 才生效。" % target)
    if target == "self-hosted":
        print("  自建 runner: 组织级 runner，标签 [self-hosted, cretas]；当前使用隔离 Windows WSL Linux")
        print("  在线检查: gh api orgs/cretasfuture-ai/actions/runners --jq '.runners[]|.name+\" \"+.status'")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
