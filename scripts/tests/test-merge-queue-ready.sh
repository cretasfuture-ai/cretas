#!/usr/bin/env bash
# GUARDS=.github/workflows/*.yml,scripts/ci/changed-paths.py,scripts/ci/trigger-paths/*
#
# 合并队列准备闸 (2026-11-12, 三步计划第 1 步: 只改 workflow 与配套脚本, 不改仓库设置)。
#
# 要把检查设成「必需」并开合并队列, 每个检查必须:
#   (a) 在 pull_request 上【总是】报告结果 —— workflow 级 paths 过滤掉的 run 根本不出现,
#       必需检查会永远挂起; ⇒ pull_request 不带 paths, 改由 changes 作业判定, 原作业按 run 跳过
#       (被 if 跳过的作业对必需检查算通过);
#   (b) 在 merge_group 事件上也运行 (队列里跑全量, 不做路径过滤)。
#
# 本闸钉:
#   1. 11 个 PR workflow 都有 `merge_group: types: [checks_requested]`;
#   2. 9 个原带 paths 的 workflow: pull_request 不再有 paths / paths-ignore; 有 changes 作业
#      (托管 runner 字面量 + timeout + outputs.run + 调 changed-paths.py 读自己的清单);
#      每个非 changes 作业 needs changes, if 用 fail-safe 写法 `needs.changes.outputs.run != 'false'`
#      并带 `!cancelled()` 或 `always()` (changes 自己失败时下游照跑, ⛔ 不让仪器坏掉变成「跳过=通过」);
#      清单文件存在、含 changed-paths.py 与自身; 若 push 仍带 paths, 必须与清单 (去掉两条仪器行) 逐条相同 (形态 D);
#   3. concurrency group 在 merge_group 下仍唯一 (含 github.ref), 否则队列运行互相取消;
#   4. changed-paths.py 单测: `**` 跨目录 / `*` 不跨目录 / `**/` 含根 / `!` 取反 / 非 pull_request ⇒ true /
#      diff 失败 ⇒ true + ::warning:: / 无命中 ⇒ false / 清单为空 ⇒ rc=2;
#   5. 每份清单的样例路径命中与 GitHub paths 语义一致;
#   阴性对照: 去掉某 workflow 的 merge_group、给 pull_request 加回 paths、拿掉某作业的 needs、
#   把 if 改成 `== 'true'`, 本闸的判定函数都必须判红。
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
PY=${PYTHON:-python3}
if ! "$PY" -c 'import sys' >/dev/null 2>&1; then PY=python; fi
exec "$PY" -I - "$ROOT_DIR" <<'PY'
import copy
import importlib.util
import os
import re
import shutil
import subprocess
import sys
import tempfile

import yaml

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = sys.argv[1]
WF_DIR = os.path.join(ROOT, ".github", "workflows")
TP_DIR = os.path.join(ROOT, "scripts", "ci", "trigger-paths")
CP = os.path.join(ROOT, "scripts", "ci", "changed-paths.py")
FILTERED = ["crosslang-contract-gate", "factory-agentops-gate", "fresh-db-progress", "java-gate-pr",
            "ops-script-tests", "python-gate", "rn-gate", "soft-delete-scan-surface", "web-admin-gate"]
PLAIN = ["secret-regression", "source-hygiene"]
INFRA = ["scripts/ci/changed-paths.py"]  # + scripts/ci/trigger-paths/<name>.txt

problems = []
checks = 0


def ok(msg):
    global checks
    checks += 1
    print("  ok - " + msg)


spec = importlib.util.spec_from_file_location("changed_paths", CP)
cp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cp)


def load(name):
    with open(os.path.join(WF_DIR, name + ".yml"), encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def on_of(doc):
    # PyYAML (YAML 1.1) 把 `on` 读成 True
    return doc.get(True, doc.get("on")) or {}


def needs_of(job):
    n = (job or {}).get("needs")
    if n is None:
        return []
    return [n] if isinstance(n, str) else list(n)


def merge_group_problems(name, doc):
    mg = on_of(doc).get("merge_group", "<missing>")
    if mg == "<missing>":
        return ["%s: 没有 merge_group 触发" % name]
    if not isinstance(mg, dict) or mg.get("types") != ["checks_requested"]:
        return ["%s: merge_group 必须是 types: [checks_requested], 实得 %r" % (name, mg)]
    return []


def concurrency_problems(name, doc):
    g = str((doc.get("concurrency") or {}).get("group", ""))
    if not g:
        return []
    if "github.ref" not in g:
        return ["%s: concurrency.group=%r 不含 github.ref —— merge_group 下可能同组互相取消" % (name, g)]
    return []


def filtered_problems(name, doc, patterns_file_text=None):
    bad = []
    on = on_of(doc)
    pr = on.get("pull_request", "<missing>")
    if pr == "<missing>":
        return ["%s: 没有 pull_request 触发" % name]
    if isinstance(pr, dict) and ("paths" in pr or "paths-ignore" in pr):
        bad.append("%s: pull_request 仍带 paths/paths-ignore —— 被过滤的 PR 不会报告, 必需检查永远挂起" % name)
    jobs = doc.get("jobs") or {}
    ch = jobs.get("changes")
    want = "scripts/ci/changed-paths.py --patterns scripts/ci/trigger-paths/%s.txt" % name
    if not ch:
        bad.append("%s: 没有 changes 作业" % name)
    else:
        if ch.get("runs-on") != "ubuntu-latest":
            bad.append("%s/changes: runs-on 必须是字面量 ubuntu-latest, 实得 %r" % (name, ch.get("runs-on")))
        if not isinstance(ch.get("timeout-minutes"), int):
            bad.append("%s/changes: 缺 timeout-minutes" % name)
        if "steps.filter.outputs.run" not in str((ch.get("outputs") or {}).get("run", "")):
            bad.append("%s/changes: outputs.run 没接到 steps.filter.outputs.run" % name)
        runs = [s for s in ch.get("steps") or [] if s.get("id") == "filter" and want in str(s.get("run", ""))]
        if not runs:
            bad.append("%s/changes: 没有 id=filter 的步骤运行 `%s`" % (name, want))
        if needs_of(ch):
            bad.append("%s/changes: changes 自己不该 needs 别的作业" % name)
    for jn, job in jobs.items():
        if jn == "changes":
            continue
        cond = str((job or {}).get("if", ""))
        if "changes" not in needs_of(job):
            bad.append("%s/%s: 没有 needs changes" % (name, jn))
        if "needs.changes.outputs.run != 'false'" not in cond:
            bad.append("%s/%s: if 没用 fail-safe 判定 `needs.changes.outputs.run != 'false'` (实得 %r)" % (name, jn, cond))
        if "!cancelled()" not in cond and "always()" not in cond:
            bad.append("%s/%s: if 缺 !cancelled()/always() —— changes 失败时下游会被跳过 (=必需检查通过)" % (name, jn))
    return bad


# ── 1-3. workflow 结构 ─────────────────────────────────────────────────────────
docs = {}
for name in FILTERED + PLAIN:
    path = os.path.join(WF_DIR, name + ".yml")
    if not os.path.isfile(path):
        problems.append("缺 workflow %s.yml" % name)
        continue
    docs[name] = load(name)
print("READING: workflows_loaded=%d (预期 11)" % len(docs))
if len(docs) != 11:
    problems.append("只读到 %d 个 workflow —— 没量到" % len(docs))

for name, doc in docs.items():
    problems += merge_group_problems(name, doc)
    problems += concurrency_problems(name, doc)
ok("11 个 workflow 都有 merge_group: [checks_requested], concurrency.group 含 github.ref")

n_jobs = 0
for name in FILTERED:
    doc = docs.get(name)
    if doc is None:
        continue
    problems += filtered_problems(name, doc)
    n_jobs += len([j for j in (doc.get("jobs") or {}) if j != "changes"])
    tp = os.path.join(TP_DIR, name + ".txt")
    if not os.path.isfile(tp):
        problems.append("%s: 缺触发清单 %s" % (name, tp))
        continue
    pats = cp.read_patterns(tp)
    infra = INFRA + ["scripts/ci/trigger-paths/%s.txt" % name]
    for need in infra:
        if need not in pats:
            problems.append("%s: 触发清单里缺 %s (改仪器的 PR 触发不到本闸)" % (name, need))
    core = [p for p in pats if p not in infra]
    if len(core) < 3:
        problems.append("%s: 触发清单只有 %d 条正文模式 —— 不像原 paths, 没量到" % (name, len(core)))
    push = (on_of(doc).get("push") or {})
    if isinstance(push, dict) and "paths" in push:
        if push["paths"] != core:
            problems.append("%s: push.paths 与触发清单不一致 (两份会漂, 形态 D): push-only=%s txt-only=%s"
                            % (name, [p for p in push["paths"] if p not in core][:5],
                               [p for p in core if p not in push["paths"]][:5]))
    print("READING: %s trigger_patterns=%d push_paths=%s jobs=%s"
          % (name, len(pats), len(push["paths"]) if isinstance(push, dict) and "paths" in push else "none",
             list((doc.get("jobs") or {}).keys())))
print("READING: gated_jobs=%d" % n_jobs)
if n_jobs < 13:
    problems.append("9 个过滤 workflow 里只扫到 %d 个非 changes 作业 (预期 >= 13) —— 没量到" % n_jobs)
ok("9 个过滤 workflow: pull_request 无 paths, 有 changes 作业, 每个原作业 needs changes 且 fail-safe")

# 阴性对照: 判定函数必须能红
neg = 0
d = copy.deepcopy(docs["python-gate"])
on_of(d).pop("merge_group", None)
neg += bool(merge_group_problems("neg", d))
d = copy.deepcopy(docs["web-admin-gate"])
key = True if True in d else "on"
d[key]["pull_request"] = {"paths": ["web-admin/**"]}
neg += bool(filtered_problems("web-admin-gate", d))
d = copy.deepcopy(docs["java-gate-pr"])
d["jobs"]["fast-contracts"]["needs"] = "fast-contracts-shard"
neg += bool(filtered_problems("java-gate-pr", d))
d = copy.deepcopy(docs["rn-gate"])
d["jobs"]["jest"]["if"] = "${{ needs.changes.outputs.run == 'true' }}"
neg += bool(filtered_problems("rn-gate", d))
d = copy.deepcopy(docs["ops-script-tests"])
d["concurrency"]["group"] = "x-${{ github.event.pull_request.number }}"
neg += bool(concurrency_problems("neg", d))
d = copy.deepcopy(docs["fresh-db-progress"])
del d["jobs"]["changes"]
neg += bool(filtered_problems("fresh-db-progress", d))
print("READING: negative_controls_red=%d/6" % neg)
if neg != 6:
    problems.append("阴性对照只有 %d/6 判红 —— 判定函数恒真" % neg)
ok("阴性对照: 去 merge_group / 加回 paths / 拿掉 needs / if 用 == 'true' / group 只用 PR 号 / 删 changes 都会红")

# ── 4. changed-paths.py 单测 ────────────────────────────────────────────────────
def run_cp(args, cwd, env_extra=None):
    # 输出文件放在仓库外 (SCRATCH), 否则它会被 `git add -A` 带进提交, 下一次 checkout 被拒。
    out_file = os.path.join(SCRATCH, "gh_output.txt")
    if os.path.exists(out_file):
        os.remove(out_file)
    env = dict(os.environ)
    for k in ("EVENT_NAME", "GITHUB_EVENT_NAME", "BASE_SHA", "HEAD_SHA"):
        env.pop(k, None)
    env["GITHUB_OUTPUT"] = out_file
    env.update(env_extra or {})
    r = subprocess.run([sys.executable, CP] + args, cwd=cwd, env=env, capture_output=True,
                       text=True, encoding="utf-8", errors="replace")
    val = None
    if os.path.exists(out_file):
        lines = [l for l in open(out_file, encoding="utf-8").read().splitlines() if l.startswith("run=")]
        val = lines[-1][4:] if lines else None
    return r.returncode, val, r.stdout + r.stderr


def git(cwd, *args):
    subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", "-c", "commit.gpgsign=false"] + list(args),
                   cwd=cwd, check=True, capture_output=True)


SCRATCH = tempfile.mkdtemp(prefix="cp-scratch-")
tmp = tempfile.mkdtemp(prefix="cp-test-")
try:
    git(tmp, "init", "-q", "-b", "main")
    os.makedirs(os.path.join(tmp, "a", "b"))
    open(os.path.join(tmp, "README.md"), "w").write("x\n")
    git(tmp, "add", "-A")
    git(tmp, "commit", "-q", "-m", "base")
    base = subprocess.run(["git", "rev-parse", "HEAD"], cwd=tmp, capture_output=True, text=True).stdout.strip()

    def head_with(files):
        git(tmp, "checkout", "-q", "-B", "h", base)
        for f in files:
            full = os.path.join(tmp, f)
            os.makedirs(os.path.dirname(full) or tmp, exist_ok=True)
            open(full, "w").write("y\n")
        git(tmp, "add", "-A")
        git(tmp, "commit", "-q", "-m", "head")
        return subprocess.run(["git", "rev-parse", "HEAD"], cwd=tmp, capture_output=True, text=True).stdout.strip()

    pf = os.path.join(SCRATCH, "pats.txt")

    def case(label, pats, files, expect, event="pull_request", base_sha=None, head_sha=None, want_warn=False,
             want_rc=0):
        with open(pf, "w", encoding="utf-8", newline="") as fh:
            fh.write("# 注释行\n" + "\n".join(pats) + "\n")
        h = head_with(files) if files is not None else (head_sha or "")
        rc, val, out = run_cp(["--patterns", pf], tmp, {
            "EVENT_NAME": event, "BASE_SHA": base if base_sha is None else base_sha,
            "HEAD_SHA": h if head_sha is None else head_sha})
        good = rc == want_rc and (val == expect if want_rc == 0 else True)
        if want_warn and "::warning::" not in out:
            good = False
        if want_rc == 0 and "READING: event=" not in out:
            good = False
        print("READING: case=%s rc=%d run=%s expect=%s" % (label, rc, val, expect))
        if not good:
            problems.append("changed-paths 用例 %s: rc=%d run=%s (期望 rc=%d run=%s)\n%s"
                            % (label, rc, val, want_rc, expect, out))

    case("globstar-crosses-dirs", ["a/**"], ["a/b/c/d.txt"], "true")
    case("star-does-not-cross", ["a/*"], ["a/b/c.txt"], "false")
    case("star-same-dir", ["a/*"], ["a/c.txt"], "true")
    case("leading-globstar-root", ["**/c.txt"], ["c.txt"], "true")
    case("leading-globstar-deep", ["**/c.txt"], ["a/b/c.txt"], "true")
    case("negation-last-wins", ["a/**", "!a/b/**"], ["a/b/x.txt"], "false")
    case("negation-other-file-hits", ["a/**", "!a/b/**"], ["a/b/x.txt", "a/y.txt"], "true")
    case("no-match", ["web-admin/**", "backend/python/**"], ["docs/x.md"], "false")
    case("trailing-comment", ["docs/x.md   # 行尾注释"], ["docs/x.md"], "true")
    # 改名: 旧路径命中也要算 (--no-renames; 宁可多跑)
    git(tmp, "checkout", "-q", "-B", "r2", base)
    os.makedirs(os.path.join(tmp, "old"), exist_ok=True)
    open(os.path.join(tmp, "old", "f.txt"), "w").write("z\n" * 20)
    git(tmp, "add", "-A")
    git(tmp, "commit", "-q", "-m", "o")
    rbase = subprocess.run(["git", "rev-parse", "HEAD"], cwd=tmp, capture_output=True, text=True).stdout.strip()
    git(tmp, "mv", "old/f.txt", "new.txt")
    git(tmp, "commit", "-q", "-m", "mv")
    rhead = subprocess.run(["git", "rev-parse", "HEAD"], cwd=tmp, capture_output=True, text=True).stdout.strip()
    with open(pf, "w", encoding="utf-8", newline="") as fh:
        fh.write("old/**\n")
    rc, val, out = run_cp(["--patterns", pf], tmp, {"EVENT_NAME": "pull_request", "BASE_SHA": rbase, "HEAD_SHA": rhead})
    print("READING: case=rename-old-path rc=%d run=%s expect=true" % (rc, val))
    if rc != 0 or val != "true":
        problems.append("changed-paths: 改名时旧路径没算命中 (rc=%d run=%s)\n%s" % (rc, val, out))

    case("merge-group-always-true", ["web-admin/**"], ["docs/x.md"], "true", event="merge_group")
    case("push-always-true", ["web-admin/**"], ["docs/x.md"], "true", event="push")
    case("missing-sha-true", ["web-admin/**"], ["docs/x.md"], "true", base_sha="", want_warn=True)
    case("bogus-sha-true", ["web-admin/**"], ["docs/x.md"], "true",
         base_sha="0123456789abcdef0123456789abcdef01234567", want_warn=True)
    case("empty-patterns-rc2", [], ["docs/x.md"], None, want_rc=2)
    case("only-negations-rc2", ["!a/**"], ["docs/x.md"], None, want_rc=2)
    # git 本身不可用的目录 (不是仓库) ⇒ 取不到 diff ⇒ true + warning
    nogit = tempfile.mkdtemp(prefix="cp-nogit-")
    try:
        open(os.path.join(nogit, "p.txt"), "w").write("a/**\n")
        rc, val, out = run_cp(["--patterns", os.path.join(nogit, "p.txt")], nogit,
                              {"EVENT_NAME": "pull_request", "BASE_SHA": base, "HEAD_SHA": base,
                               "GIT_CEILING_DIRECTORIES": os.path.dirname(nogit)})
        print("READING: case=not-a-repo rc=%d run=%s expect=true" % (rc, val))
        if rc != 0 or val != "true" or "::warning::" not in out:
            problems.append("changed-paths: 非仓库目录 (git 出错) 没有兜底成 run=true + warning\n%s" % out)
    finally:
        shutil.rmtree(nogit, ignore_errors=True)
finally:
    shutil.rmtree(tmp, ignore_errors=True)
    shutil.rmtree(SCRATCH, ignore_errors=True)
ok("changed-paths.py 单测: ** / * / **/ / ! / 改名 / 非 PR / sha 缺失 / git 出错 / 无命中 / 空清单")

# ── 5. 每份真实清单的样例 (语义 = GitHub paths) ──────────────────────────────────
SAMPLES = {
    "crosslang-contract-gate": [("web-admin/src/views/a/b.vue", True),
                                ("backend/java/cretas-api/src/main/java/com/x/Y.java", True),
                                ("backend/python/smartbi/x.py", True),
                                ("backend/java/cretas-api/src/test/java/com/x/YTest.java", False),
                                ("web-admin/package.json", False)],
    "factory-agentops-gate": [("backend/python/smartbi/agent/eval/a/b.py", True),
                              ("scripts/ci/factory_agentops_gate.py", True),
                              ("backend/python/smartbi/agent/runtime/other.py", False)],
    "fresh-db-progress": [("backend/java/cretas-api/src/main/resources/db/migration/V1__a.sql", True),
                          ("ops/db-baseline/x/y.sql", True),
                          ("backend/java/cretas-api/src/main/resources/application.properties", False),
                          ("scripts/testing/other.sh", False)],
    "java-gate-pr": [("backend/java/cretas-api/src/main/java/com/x/Y.java", True),
                     ("scripts/ci/java-gate-pg-tests.txt", True),
                     ("scripts/ci/java-gate-pg-tests-floor.txt", False),
                     ("web-admin/src/x.ts", False)],
    "ops-script-tests": [("scripts/ota/push.sh", True),
                         ("backend/java/cretas-api/src/test/java/com/x/YTest.java", True),
                         ("backend/java/cretas-api/src/main/java/com/x/Y.java", False),
                         ("docs/x.md", False)],
    "python-gate": [("backend/python/smartbi/a/b/c.py", True),
                    ("scripts/cron/time-phrase-backlog-daily.sh", True),
                    ("scripts/cron/other.sh", False)],
    "rn-gate": [("frontend/CretasFoodTrace/src/screens/A.tsx", True),
                ("backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/X.java", True),
                ("backend/java/cretas-api/src/main/java/com/cretas/aims/entity/X.java", False)],
    "soft-delete-scan-surface": [("backend/java/cretas-api/src/main/java/com/cretas/aims/entity/a/B.java", True),
                                 ("scripts/cron/soft-delete-orphans.sql", True),
                                 ("backend/java/cretas-api/src/test/java/com/cretas/aims/X.java", False)],
    "web-admin-gate": [("web-admin/src/views/x/y.vue", True),
                       ("backend/java/cretas-api/src/main/java/com/cretas/aims/entity/x/Y.java", True),
                       ("backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/OtherServiceImpl.java", False)],
}
n_samples = 0
for name, rows in SAMPLES.items():
    compiled = cp.compile_patterns(cp.read_patterns(os.path.join(TP_DIR, name + ".txt")))
    for path, expect in rows:
        n_samples += 1
        got = cp.matches(path, compiled)
        if got != expect:
            problems.append("%s: 样例 %s 命中=%s, 期望 %s" % (name, path, got, expect))
        # 仪器自身也必须命中
    for infra in ("scripts/ci/changed-paths.py", "scripts/ci/trigger-paths/%s.txt" % name):
        n_samples += 1
        if not cp.matches(infra, compiled):
            problems.append("%s: 仪器文件 %s 不在触发面里" % (name, infra))
print("READING: trigger_samples=%d" % n_samples)
ok("9 份触发清单的样例命中与 GitHub paths 语义一致, 仪器文件都在各自触发面里")

if problems:
    for p in problems:
        print("FAIL: " + p)
    sys.exit(1)
print("PASS: %d 组断言全部通过" % checks)
PY
