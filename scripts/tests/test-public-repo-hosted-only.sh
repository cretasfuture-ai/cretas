#!/usr/bin/env bash
# GUARDS=.github/workflows/rn-gate.yml,.github/workflows/web-admin-gate.yml,.github/workflows/python-gate.yml,.github/workflows/crosslang-contract-gate.yml,.github/workflows/source-hygiene.yml,.github/workflows/web-dist.yml,.github/workflows/java-gate-pr.yml,.github/workflows/java-full-nightly.yml,.github/workflows/jpa-gate-main.yml,.github/workflows/java-artifact.yml,.github/workflows/tool-inventory-gate.yml,.github/workflows/ops-script-tests.yml,.github/workflows/soft-delete-scan-surface.yml,.github/workflows/secret-regression.yml,.github/workflows/scheduler-canary.yml,.github/workflows/artifact-reaper.yml,.github/workflows/reap-orphan-testenvs.yml
# GUARDS=.github/workflows/fresh-db-progress.yml,scripts/ci/switch_ci_runner.py
# 2026-10-25: 仓库已转 public, CI 全部走 GitHub 托管 runner (owner 决定, 自建 runner 退役)。
# 公开仓库里任何人都能提 PR; 只要还有一个 job 指向自建 runner, 陌生人的 PR 代码就可能在
# 我们自己的机器上执行。所以这道闸钉三件事:
#   1. 每个 job 的 runs-on 都是 GitHub 托管标签 (ubuntu-* / windows-* / macos-*), 没有 self-hosted,
#      也没有表达式 (表达式里能藏 self-hosted);
#   2. 原来跑在自建 runner 上的工作流, 每个 job 仍带 timeout-minutes (托管 job 卡住同样占并发);
#   3. Java 重任务在托管 16 GB 机器上显式给堆 (不给就退回 3g, fast-contracts 在这个类序下 OOM)。
# 阳性对照: 扫到的 job 数 >= 25; 阴性对照: 一段带 [self-hosted, cretas] 的样本必须被判红。
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
PY=${PYTHON:-python3}
if ! "$PY" -c 'import sys' >/dev/null 2>&1; then PY=python; fi
"$PY" - "$ROOT_DIR" <<'PY'
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(sys.argv[1])
WF = ROOT / '.github/workflows'
HOSTED = re.compile(r'^(ubuntu|windows|macos)-[A-Za-z0-9.-]+$')
FORMERLY_SELF_HOSTED = {
    'rn-gate.yml', 'web-admin-gate.yml', 'python-gate.yml', 'crosslang-contract-gate.yml',
    'source-hygiene.yml', 'web-dist.yml', 'java-gate-pr.yml', 'java-full-nightly.yml',
    'jpa-gate-main.yml', 'java-artifact.yml', 'tool-inventory-gate.yml', 'ops-script-tests.yml',
    'soft-delete-scan-surface.yml', 'secret-regression.yml', 'scheduler-canary.yml',
    'artifact-reaper.yml', 'reap-orphan-testenvs.yml', 'fresh-db-progress.yml',
}
HEAP = {
    ('java-gate-pr.yml', 'fast-contracts'): 'CRETAS_JAVA_FORK_XMX',
    ('java-gate-pr.yml', 'postgres-reversal-concurrency'): 'CRETAS_JAVA_FORK_XMX',
    ('java-full-nightly.yml', 'full-suite'): 'CRETAS_JAVA_NIGHTLY_XMX',
}


def jobs(text):
    """(job, block) for each top-level job; a block runs until the next 2-space key."""
    body = text.replace('\r\n', '\n').split('\njobs:\n', 1)
    if len(body) != 2:
        return []
    out, name, buf = [], None, []
    for line in body[1].split('\n'):
        m = re.match(r'^  ([A-Za-z0-9_-]+):\s*$', line)
        if m or (line and not line.startswith(' ')):
            if name:
                out.append((name, '\n'.join(buf)))
            name, buf = (m.group(1) if m else None), []
            if not m:
                break
        elif name:
            buf.append(line)
    if name:
        out.append((name, '\n'.join(buf)))
    return out


def runs_on(block):
    """The literal runs-on value (comment stripped), or None when the job has none."""
    m = re.search(r'^    runs-on:[ \t]*(.*)$', block, re.M)
    if not m:
        return None
    return m.group(1).split('#', 1)[0].strip()


def violations(name, text):
    bad = []
    for job, block in jobs(text):
        value = runs_on(block)
        if value is None:
            continue  # reusable-workflow callers (`uses:`) have no runs-on
        if not HOSTED.match(value):
            bad.append('%s/%s runs-on %r is not a GitHub-hosted label' % (name, job, value))
        if name in FORMERLY_SELF_HOSTED and not re.search(r'^    timeout-minutes:\s*\d+', block, re.M):
            bad.append('%s/%s has no timeout-minutes' % (name, job))
        heap = HEAP.get((name, job))
        if heap and not re.search(r'^      %s:\s*\d+g\s*$' % heap, block, re.M):
            bad.append('%s/%s does not set %s in its job env' % (name, job, heap))
    return bad


problems, seen, routed = [], set(), 0
for path in sorted(WF.glob('*.yml')):
    text = path.read_text(encoding='utf-8')
    for job, block in jobs(text):
        seen.add((path.name, job))
        routed += runs_on(block) is not None
    problems += violations(path.name, text)

print('READING: workflows=%d jobs=%d jobs_with_runs_on=%d' % (len(list(WF.glob('*.yml'))), len(seen), routed))
ok = True
if routed < 25:
    print('FAIL: 阳性对照: 只扫到 %d 个带 runs-on 的 job (预期 >= 25) —— 扫描器瞎了' % routed)
    ok = False
missing = sorted(set(HEAP) - seen)
if missing:
    print('FAIL: 阳性对照: 这些 Java 重任务 job 没扫到: %s' % missing)
    ok = False

sample = 'on: push\njobs:\n  probe:\n    runs-on: [self-hosted, cretas]\n    steps: []\n'
if not violations('negative-control.yml', sample):
    print('FAIL: 阴性对照: [self-hosted, cretas] 没被判红')
    ok = False
else:
    print('  ok - 阴性对照: [self-hosted, cretas] 被判红')

check = subprocess.run([sys.executable, str(ROOT / 'scripts/ci/switch_ci_runner.py'), '--check'],
                       cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace')
last = (check.stdout.strip().splitlines() or [''])[-1]
print('READING: switch_ci_runner --check rc=%d: %s' % (check.returncode, last))
if check.returncode != 0 or '= hosted' not in last:
    print('FAIL: switch_ci_runner.py 管理的工作流不是全部 hosted')
    ok = False

for p in problems:
    print('  ✗ ' + p)
if problems:
    ok = False
print('PASS: 全部 job 走 GitHub 托管 runner' if ok else 'FAIL')
sys.exit(0 if ok else 1)
PY
