#!/usr/bin/env python3
"""在「托管 runner」与「自建 runner」之间切换 CI。

🔴 2026-10-25: 仓库已转 public, 自建 runner 全部退役, `self-hosted` 目标被拒绝 ——
公开仓库里任何人都能提 PR, 自建路由会让陌生人的代码在我们的机器上执行。
现在只用 `--check` (scripts/tests/test-public-repo-hosted-only.sh 要求结果是 hosted)。

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
    # 2026-10-24: 小检查专用槽 (cretas-quick) 接入时纳入。六关逐条过: 4 空格缩进; 两个都不用
    # setup-python (直接找 python3, 只用标准库); 无 Linux 专有命令依赖; 只用 git/python3/bash;
    # 无平台 wheel; 无 services: / 容器。托管侧就是 ubuntu-latest。
    "source-hygiene.yml",
    "artifact-reaper.yml",
]

HOSTED = "    runs-on: ubuntu-latest"
SELF = "    runs-on: [self-hosted, cretas]"
# 2026-09-15: 跑 Maven 测试 fork 的 job 必须落在带 `java` 标签的 8G runner 上 ——
# 同一台 WSL 上另有两个 4G 的 extra runner(给 jest/web 用), 也带 [self-hosted, cretas],
# JVM 按 cgroup 算默认堆只有 1G, Java Gate 在上面第 61 个类就 `Java heap space`。
# 托管侧用行尾注释标记, 切回自托管时才知道该还原成哪一种形态(两种形态往返必须逐字还原,
# 原 test-java-heavy-jobs-need-8g-runner.sh 钉这条, 2026-10-25 随自建 runner 退役)。
SELF_JAVA = "    runs-on: [self-hosted, cretas, java]"
HOSTED_JAVA = "    runs-on: ubuntu-latest  # java-heavy: self-hosted 时要 8G runner"
# 2026-10-03: 可溢出的 Java job —— 只要 `java` 标签, 不要 `cretas`。本机三个 8G 的
# windows-wsl-ci 带 [cretas, java] 照样匹配; 加拿大 Mac mini 上 colima Linux arm64 VM 里的
# canada-mini-cretas 只带 [java], 也匹配 ⇒ 本机满了 GitHub 会把这些 job 派过去。
# 4G 的 extra runner 没有 java 标签, 仍然接不到(这正是 java 标签存在的理由)。
# ⛔ 带 services: 的 job(会撞同 VM 里 assembly runner 的 job 映射的 5432) 与要 runner 本机常驻 PG 的
#    java-full-nightly 不许用这一形态 (原由 8G runner 闸的 A2 钉着, 2026-10-25 已退役)。
#    java-artifact 没有 services:, 但 09-15 起只手动派发, 不抢 runner, 故保留三标签形态。
SELF_JAVA_ANY = "    runs-on: [self-hosted, java]"
HOSTED_JAVA_ANY = "    runs-on: ubuntu-latest  # java-heavy-any: self-hosted 时要 8G runner, 可溢到 canada-mini"
PAIRS = ((SELF, HOSTED), (SELF_JAVA, HOSTED_JAVA), (SELF_JAVA_ANY, HOSTED_JAVA_ANY))
# Generic cretas also matches Aurora. Keep Web on the bounded Windows worker,
# without changing any existing Java-heavy return markers or other workflows.
WEB_WORKFLOWS = frozenset({"web-admin-gate.yml", "web-dist.yml"})
SELF_WEB = "    runs-on: [self-hosted, Linux, cretas, X64, windows-light]"
# 2026-10-24: 不到 1 分钟的小检查原来和 vitest/pytest 挤在本机那一个轻任务槽里排队 (实测
# 中位数 12–17 分钟)。现在走专用的 `cretas-quick` 标签: 本机 1 核 / 1G 的小检查槽只带这一个
# 自定义标签 (不带 cretas, 重任务落不过去); aurora 与 windows-light 也加了 cretas-quick,
# 小检查槽忙或离线时照样有人接。槽的任务白名单 (tools/ci/windows-quick/job-started.sh)
# 必须等于 QUICK_JOBS (原由小检查路由闸钉着, 2026-10-25 随自建 runner 退役)。
QUICK_WORKFLOWS = frozenset({
    "secret-regression.yml",
    "soft-delete-scan-surface.yml",
    "source-hygiene.yml",
    "artifact-reaper.yml",
})
QUICK_JOBS = ("tracked-secret-scan", "scan-surface-in-sync", "no-literal-nul-bytes", "reap")
SELF_QUICK = "    runs-on: [self-hosted, cretas-quick]"
# 有专用自托管路由的工作流: 自托管时用它, 托管时是 ubuntu-latest; 从通用 SELF 迁移要显式 --adopt。
DEDICATED = {name: SELF_WEB for name in WEB_WORKFLOWS}
DEDICATED.update({name: SELF_QUICK for name in QUICK_WORKFLOWS})


def read(p):
    return io.open(p, "r", encoding="utf-8", newline="").read()


def _count_lines(text, literal):
    """整行相等才算 —— HOSTED 是 HOSTED_JAVA 的前缀, `str.count` 会把后者也数进去。"""
    return sum(1 for ln in text.split("\n") if ln.rstrip("\r") == literal)


def _swap_lines(text, frm, to):
    """整行替换, 保留每行原有的行尾(\\r\\n / \\n)。"""
    out = []
    for ln in text.split("\n"):
        body = ln.rstrip("\r")
        out.append(to + ln[len(body):] if body == frm else ln)
    return "\n".join(out)


def current(text):
    """一个文件当前落在哪一侧。

    ⚠️ 2026-08-27: 原来是 `if SELF in text: return "self-hosted"` 先判 SELF ——
    一个**半转换**的文件(两种都有)会被报成 self-hosted, 正好把
    「共享结构只改一处」那种坏法**藏起来**。而多 job 的文件正是最容易半转换的
    (ci.yml 有 4 个 job)。⇒ 两种都数, 同时存在就是显式的坏状态 `mixed`。
    """
    n_self = (sum(_count_lines(text, s) for s, _ in PAIRS)
              + _count_lines(text, SELF_WEB) + _count_lines(text, SELF_QUICK))
    n_hosted = sum(_count_lines(text, h) for _, h in PAIRS)
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
    if target == "self-hosted" and not check_only:
        print("ci-runner: REFUSED — 仓库是 public, 自建 runner 已退役 (2026-10-25)。")
        print("  自建路由会让外部 PR 的代码在我们的机器上执行; 要恢复必须先把仓库改回 private。")
        return 2

    states = {}
    contents = {}
    issues = []
    for name in WORKFLOWS:
        p = REPO / ".github" / "workflows" / name
        if not p.exists():
            print("  ✗ 找不到 %s" % name)
            return 1
        contents[name] = read(p)
        states[name] = current(contents[name])
        allowed = {HOSTED, DEDICATED[name]} if name in DEDICATED else {
            literal for pair in PAIRS for literal in pair
        }
        # Only explicit adoption may migrate a generic route to its dedicated label.
        if adopt and not check_only and name in DEDICATED:
            allowed.add(SELF)
        routes = [line.rstrip("\r") for line in contents[name].split("\n")
                  if line.startswith("    runs-on:")]
        if (not routes or any(line not in allowed for line in routes)
                or states[name] in ("mixed", "unknown")):
            issues.append(name)

    # Validate the whole batch before writing any file, including --adopt.
    if issues:
        print("ci-runner: FAIL — invalid routing literal or mixed/unknown state: %s" % ", ".join(issues))
        print("  Legacy generic Web / quick-check routes require explicit self-hosted --adopt migration.")
        return 1

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

    needs_migration = any(_count_lines(contents[name], SELF) for name in DEDICATED)
    if len(distinct) == 1 and distinct.copy().pop() == target and not needs_migration:
        print("ci-runner: 已经是 %s, 无需改动" % target)
        return 0

    # 两对形态各自往返(普通 job / java-heavy job), 方向由 target 决定。
    changed = 0
    for name in WORKFLOWS:
        p = REPO / ".github" / "workflows" / name
        s = contents[name]
        route_pairs = ((DEDICATED[name], HOSTED),) if name in DEDICATED else PAIRS
        pairs = [(h, own) for own, h in route_pairs] if target == "self-hosted" else list(route_pairs)
        if adopt and name in DEDICATED:
            pairs.append((SELF, DEDICATED[name] if target == "self-hosted" else HOSTED))
        if states[name] == target and not (name in DEDICATED and _count_lines(s, SELF)):
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
        n = sum(_count_lines(s, frm) for frm, _ in pairs)
        if n < 1:
            print("ci-runner: FAIL — %s 里一处 `%s` 都没有(当前状态 %s)"
                  % (name, " / ".join(frm.strip() for frm, _ in pairs), states[name]))
            print("  (已改动 %d 个文件, 工作树现在是**半途状态**, 用 git 看一眼再决定)" % changed)
            return 1
        for frm, to in pairs:
            s = _swap_lines(s, frm, to)
        io.open(p, "w", encoding="utf-8", newline="").write(s)
        changed += 1
        print("  %-26s -> %s  (%d 处)" % (name, target, n))

    print("")
    print("ci-runner: 改动 %d 个文件, 跳过 %d 个。" % (changed, len(WORKFLOWS) - changed))
    print("ci-runner: 已切到 %s。⚠️ 要 commit + push 才生效。" % target)
    if target == "self-hosted":
        print("  Web: Windows light literal; quick checks: cretas-quick; existing Java and other mappings preserved.")
        print("  在线检查: gh api orgs/cretasfuture-ai/actions/runners --jq '.runners[]|.name+\" \"+.status'")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
