#!/usr/bin/env python3
"""PR 门禁的测试选择棘轮: 每个 Java 测试类要么被某个 PR 作业选中, 要么在冻结的基线里 (只许变少)。

为什么 (2026-11-03 实测, 票 PR-TEST-SELECTION-RATCHET):
  java-gate-pr.yml 的 fast-contracts 按类名模式选测试 (*ContractTest, *SecurityTest …),
  postgres-reversal-concurrency 按显式清单选; 全量 (final-full-suite) 只在打了 java-full-ready
  标签的 PR 上跑, 平时靠 nightly。名字不命中的测试类 PR 上一次都不跑 —— 实测 2295 个测试类里
  1465 个如此。#1357 的 H2 入口测试 (MaterialBatchStatusFilterEntryTest) 就这样从合并到发现都没在 CI 跑过。
  `-Dsurefire.failIfNoSpecifiedTests=false` 让这件事完全无声。

判据:
  · 选中 = 被任一「不靠标签」的 PR 作业里的 -Dtest 选择器匹配 (含 !排除); 按类的简单名匹配;
  · 基线 (scripts/ci/pr-unselected-tests-baseline.txt) = 今天还没被选中的测试类, **由本脚本生成**;
  · 新出现的未选中测试类 ⇒ 红: 改名命中模式 / 加进选择器 / 在 allowlist 写理由;
  · 基线里的条目已被选中或文件已删 ⇒ 红, 提示 `--write` 收缩 (只许变少, 收缩永远允许);
  · `--write` 在会**新增**条目时拒绝写 (exit 1) —— 基线不靠手改、也不靠 --write 扩张。

用法:
  python3 scripts/ci/pr-test-selection.py            # 检查 (CI)
  python3 scripts/ci/pr-test-selection.py --write    # 重新生成基线, 打印删掉了哪些; 会增长时拒绝
  python3 scripts/ci/pr-test-selection.py --bootstrap  # 仅首次 (基线文件不存在时) 冻结
  可选: --repo DIR --workflow FILE --baseline FILE --allowlist FILE (测试用)

退出码: 0 通过 / 1 有问题 (读数有效) / 2 没量到 (选择器或测试树读不到)。
"""
import argparse
import fnmatch
import os
import re
import sys

TEST_ROOT = "backend/java/cretas-api/src/test/java"
WORKFLOW = ".github/workflows/java-gate-pr.yml"
BASELINE = "scripts/ci/pr-unselected-tests-baseline.txt"
ALLOWLIST = "scripts/ci/pr-test-selection-allowlist.txt"
HEADER = ("# 由 scripts/ci/pr-test-selection.py --write 生成, ⛔ 不要手改。\n"
          "# PR 门禁 (java-gate-pr.yml 不靠标签的作业) 没有选中的测试类 —— 它们只在 nightly / java-full-ready 跑。\n"
          "# 只许变少: 把类改名命中选择器模式、或加进选择器, 然后 --write 收缩。\n")
TEST_ANNOTATION = re.compile(r"@(?:[\w.]+\.)?(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|Nested)\b")


def jobs_of(workflow_text):
    """顶层 jobs: 下的每个作业 → 它的文本块。"""
    m = re.search(r"^jobs:\s*$", workflow_text, re.M)
    if not m:
        return {}
    body = workflow_text[m.end():]
    jobs = {}
    for jm in re.finditer(r"^  ([A-Za-z0-9_-]+):\s*$", body, re.M):
        jobs[jm.group(1)] = jm.start()
    names = sorted(jobs, key=jobs.get)
    out = {}
    for i, n in enumerate(names):
        end = jobs[names[i + 1]] if i + 1 < len(names) else len(body)
        out[n] = body[jobs[n]:end]
    return out


def label_only(job_text):
    """只在打了某个标签时才跑的作业 (if: contains(...labels...) 且不是取反)。"""
    m = re.search(r"^    if:\s*(.+)$", job_text, re.M)
    if not m:
        return False
    cond = m.group(1)
    return "labels" in cond and "contains(" in cond and "!contains(" not in cond.replace(" ", "")


def selectors(workflow_text, repo_root=None):
    """(作业名, 包含模式列表, 排除模式列表), 只取不靠标签的作业。"""
    found = []
    for name, text in jobs_of(workflow_text).items():
        if label_only(text):
            continue
        # 分片作业: 选择在清单文件里 (shard-tests.py --list <file>), 不在 -Dtest 字面量里。
        for lf in re.findall(r'shard-tests\.py"? --list "?(?:\$GITHUB_WORKSPACE/)?(scripts/ci/[\w.-]+)', text):
            lp = os.path.join(repo_root or os.getcwd(), lf)
            pats = [ln.strip() for ln in open(lp, encoding="utf-8")
                    if ln.strip() and not ln.lstrip().startswith("#")]
            found.append((name, pats, []))
        for raw in re.findall(r"-Dtest=['\"]?([^'\"\s\\]+)", text):
            parts = [p.split("#", 1)[0] for p in raw.split(",") if p]
            inc = [p for p in parts if not p.startswith("!")]
            exc = [p[1:] for p in parts if p.startswith("!")]
            found.append((name, inc, exc))
    return found


def test_classes(repo):
    root = os.path.join(repo, TEST_ROOT)
    out = {}
    for dp, _, files in os.walk(root):
        for f in files:
            if not f.endswith(".java"):
                continue
            path = os.path.join(dp, f)
            src = open(path, encoding="utf-8", errors="replace").read()
            name = f[:-5]
            if not TEST_ANNOTATION.search(src):
                continue
            if re.search(r"\babstract\s+class\s+" + re.escape(name) + r"\b", src):
                continue
            out[os.path.relpath(path, repo).replace("\\", "/")] = name
    return out


def is_selected(name, sels):
    for _, inc, exc in sels:
        if any(fnmatch.fnmatchcase(name, p) for p in exc):
            continue
        if any(fnmatch.fnmatchcase(name, p) for p in inc):
            return True
    return False


def read_lines(path):
    if not os.path.exists(path):
        return []
    return [ln.rstrip("\n") for ln in open(path, encoding="utf-8")
            if ln.strip() and not ln.lstrip().startswith("#")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--bootstrap", action="store_true")
    ap.add_argument("--repo", default=os.getcwd())
    ap.add_argument("--workflow")
    ap.add_argument("--baseline")
    ap.add_argument("--allowlist")
    a = ap.parse_args()
    repo = a.repo
    workflow = a.workflow or os.path.join(repo, WORKFLOW)
    baseline_path = a.baseline or os.path.join(repo, BASELINE)
    allowlist_path = a.allowlist or os.path.join(repo, ALLOWLIST)

    try:
        sels = selectors(open(workflow, encoding="utf-8").read(), repo)
    except OSError as e:
        print(f"NOT MEASURED: 读不到选择器 {workflow}: {e}", file=sys.stderr)
        return 2
    classes = test_classes(repo)
    patterns = sum(len(inc) for _, inc, _ in sels)
    if not sels or patterns == 0 or not classes:
        print(f"NOT MEASURED: 选择器 {len(sels)} 个 / 模式 {patterns} 个 / 测试类 {len(classes)} 个 —— 仪器没读到东西",
              file=sys.stderr)
        return 2

    allow = {}
    for ln in read_lines(allowlist_path):
        parts = ln.split(None, 1)
        if len(parts) < 2:
            print(f"FAIL: allowlist 条目必须写理由: {ln!r}", file=sys.stderr)
            return 1
        allow[parts[0]] = parts[1]

    unselected = sorted(p for p, n in classes.items() if not is_selected(n, sels) and n not in allow)
    baseline = set(read_lines(baseline_path))
    new = [p for p in unselected if p not in baseline]
    stale = sorted(baseline - set(unselected))

    print(f"READING: jobs={sorted({j for j, _, _ in sels})} patterns={patterns} test_classes={len(classes)} "
          f"selected={sum(1 for n in classes.values() if is_selected(n, sels))} allowlisted={len(allow)} "
          f"unselected={len(unselected)} baseline={len(baseline)}")

    if a.bootstrap:
        # 只在基线文件还不存在时可用: 首次冻结。之后只能 --write 收缩。
        if os.path.exists(baseline_path):
            print(f"REFUSED: 基线已存在 ({baseline_path}), --bootstrap 只用于首次生成; 收缩请用 --write", file=sys.stderr)
            return 1
        with open(baseline_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(HEADER)
            for p in unselected:
                f.write(p + "\n")
        print(f"BOOTSTRAPPED {baseline_path}: {len(unselected)} entries")
        return 0

    if a.write:
        if new:
            print("REFUSED: --write 不会让基线变大。这些新测试类 PR 上不跑, 先改名命中模式 / 加进选择器 / 写进 allowlist:",
                  file=sys.stderr)
            for p in new:
                print(f"  + {p}", file=sys.stderr)
            return 1
        with open(baseline_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(HEADER)
            for p in unselected:
                f.write(p + "\n")
        print(f"WROTE {baseline_path}: {len(baseline)} -> {len(unselected)} (removed {len(stale)})")
        for p in stale:
            print(f"  - {p}")
        return 0

    rc = 0
    if new:
        rc = 1
        print(f"FAIL: {len(new)} 个测试类不被任何 PR 作业选中, 也不在基线里 —— 它们在 PR 上一次都不会跑:",
              file=sys.stderr)
        for p in new:
            print(f"  + {p}", file=sys.stderr)
        print("  ⇒ 改名命中 java-gate-pr.yml 的模式 (如 *ContractTest), 或加进选择器, "
              "或在 scripts/ci/pr-test-selection-allowlist.txt 写上类名和理由", file=sys.stderr)
    if stale:
        rc = 1
        print(f"FAIL: 基线里 {len(stale)} 条已经被选中或文件已删 —— 运行 `python3 scripts/ci/pr-test-selection.py --write` 收缩:",
              file=sys.stderr)
        for p in stale:
            print(f"  - {p}", file=sys.stderr)
    for name in sorted(allow):
        if name not in classes.values():
            rc = 1
            print(f"FAIL: allowlist 里的 {name} 已不存在, 请删掉这条", file=sys.stderr)
        elif is_selected(name, sels):
            rc = 1
            print(f"FAIL: allowlist 里的 {name} 已被 PR 作业选中, 请删掉这条", file=sys.stderr)
    if rc == 0:
        print("PASS")
    return rc


if __name__ == "__main__":
    sys.exit(main())
