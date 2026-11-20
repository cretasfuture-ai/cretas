#!/usr/bin/env bash
# GUARDS=.github/workflows/rn-gate.yml,.github/workflows/web-admin-gate.yml,.github/workflows/python-gate.yml,.github/workflows/crosslang-contract-gate.yml,.github/workflows/source-hygiene.yml,.github/workflows/web-dist.yml,.github/workflows/java-gate-pr.yml,.github/workflows/java-full-nightly.yml,.github/workflows/jpa-gate-main.yml,.github/workflows/java-artifact.yml,.github/workflows/tool-inventory-gate.yml,.github/workflows/ops-script-tests.yml,.github/workflows/soft-delete-scan-surface.yml,.github/workflows/secret-regression.yml,.github/workflows/scheduler-canary.yml,.github/workflows/artifact-reaper.yml,.github/workflows/reap-orphan-testenvs.yml
# GUARDS=.github/workflows/fresh-db-progress.yml,scripts/ci/switch_ci_runner.py,scripts/ci/route-mini-or-hosted.sh
# 2026-10-25: 仓库已转 public, CI 全部走 GitHub 托管 runner (owner 决定, 自建 runner 退役)。
# 公开仓库里任何人都能提 PR; 只要还有一个 job 指向自建 runner, 陌生人的 PR 代码就可能在
# 我们自己的机器上执行。所以这道闸钉三件事:
#   1. 每个 job 的 runs-on 都是 GitHub 托管标签 (ubuntu-* / windows-* / macos-*), 没有 self-hosted,
#      也没有表达式 (表达式里能藏 self-hosted);
#   2. 原来跑在自建 runner 上的工作流, 每个 job 仍带 timeout-minutes (托管 job 卡住同样占并发);
#   3. Java 重任务在托管 16 GB 机器上显式给堆 (不给就退回 3g, fast-contracts 在这个类序下 OOM)。
# 阳性对照: 扫到的 job 数 >= 25; 阴性对照: 一段带 [self-hosted, cretas] 的样本必须被判红。
# 2026-11-18 例外 (Steve 撤销「公开仓库禁自建 runner」): 只放行 SELF_HOSTED_ALLOWED 里逐字登记的表达式,
# 且必须校验本仓库 PR 或经可信路由逐项验证的合并组。阴性对照只改合法 job 的 runs-on。
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
# 自建路由白名单: (工作流, job) -> runs-on 原文。只放行本仓库分支 PR / 合并队列; fork PR 必须落 ubuntu-latest。
LEGACY_GROUP_GUARD = "(github.event_name == 'merge_group' || github.event.pull_request.head.repo.full_name == github.repository)"
SAME_REPO_GUARD = ("((github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository)"
                   " || (github.event_name == 'merge_group' && needs.changes.outputs.mini_group_verified == 'true'))")
ROUTE_OUTPUT = "needs.changes.outputs.runner == 'cretas-mini' && "
FIRST_ATTEMPT = "github.run_attempt == '1' && "
POLICY_VERSION = "needs.changes.outputs.route_policy == 'v2' && "
# 2026-11-18 mini 优先、满了溢出: 先读 changes 作业 (托管机上) 的路由输出, 守卫照旧串在后面 ——
# 路由输出只能把作业从 mini 挪到托管, 不能让 fork PR 上 mini。
# 2026-11-19 21:5x Steve「不跑5个了……这4个槽不要只给pg用」+「其他检查也上 mini」: mini 改为最多 4 槽的共用槽池,
# 公共标签 cretas-mini, changes 作业只输出一个 runner (cretas-mini | ubuntu-latest) 与 mini_window。
GUARD_TAIL = " && !contains(github.event.pull_request.labels.*.name, 'ci-hosted')"
MINI_ROUTE_CORE = (FIRST_ATTEMPT + ROUTE_OUTPUT + POLICY_VERSION + SAME_REPO_GUARD + GUARD_TAIL
                   + " && 'cretas-mini' || 'ubuntu-latest' }}")
MINI_ROUTE = "${{ " + MINI_ROUTE_CORE
# 白天 PG 只派第 2、3 片, 夜间 (mini_window=full) 4 片都可; fast 白天只派第 3 片, 夜间第 2、3 片 (第 0 片在 mini 上 OOM)。
PG_SHARD_GATE = "(matrix.shard >= 2 || needs.changes.outputs.mini_window == 'full') && "
FAST_SHARD_GATE = "(matrix.shard == 3 || (matrix.shard == 2 && needs.changes.outputs.mini_window == 'full')) && "
MINI_PG_SHARD_ROUTE = "${{ " + PG_SHARD_GATE + MINI_ROUTE_CORE
MINI_FAST_SHARD_ROUTE = "${{ " + FAST_SHARD_GATE + MINI_ROUTE_CORE
# Java Gate: PG 4 片分片 + 汇总, fast-contracts 4 片分片 + 汇总; RN Gate jest、Web Admin Gate vitest (2026-11-19)。
# 判定/路由作业 changes 必须是托管字面量 (放 mini 上的话 mini 一满它自己也排队, 溢出失效), 所以不在白名单里。
SELF_HOSTED_ALLOWED = {
    ('java-gate-pr.yml', 'postgres-reversal-concurrency-shard'): MINI_PG_SHARD_ROUTE,
    ('java-gate-pr.yml', 'postgres-reversal-concurrency'): MINI_ROUTE,
    ('java-gate-pr.yml', 'fast-contracts-shard'): MINI_FAST_SHARD_ROUTE,
    ('java-gate-pr.yml', 'fast-contracts'): MINI_ROUTE,
    ('rn-gate.yml', 'jest'): MINI_ROUTE,
    ('web-admin-gate.yml', 'vitest'): MINI_ROUTE,
}
# 上 mini 的 workflow: changes 作业必须是托管字面量并调 route-mini-or-hosted.sh (输出 runner)。
MINI_WORKFLOWS = sorted({wf for wf, _ in SELF_HOSTED_ALLOWED})
HEAP = {
    ('java-gate-pr.yml', 'fast-contracts-shard'): 'CRETAS_JAVA_FORK_XMX',
    ('java-gate-pr.yml', 'postgres-reversal-concurrency-shard'): 'CRETAS_JAVA_FORK_XMX',
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
        allowed = SELF_HOSTED_ALLOWED.get((name, job))
        if allowed is not None and value == allowed and SAME_REPO_GUARD in value and value.endswith("|| 'ubuntu-latest' }}"):
            pass  # 登记过的自建路由: fork PR 落托管
        elif not HOSTED.match(value):
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

def rejects_runs_on(wf, job, baseline, target, replacement, name):
    original = violations(wf, baseline)
    changed = baseline.replace('    runs-on: ' + target + '\n', '    runs-on: ' + replacement + '\n', 1)
    expected = [f'{wf}/{job} runs-on {replacement!r} is not a GitHub-hosted label']
    result = violations(wf, changed)
    if original or changed == baseline or result != expected:
        print(f'FAIL: {name}: baseline={original}, result={result}, expected={expected}')
        return False
    print(f'  ok - {name}: {wf}/{job} only reports runs-on')
    return True


probe = 'on: push\njobs:\n  probe:\n    runs-on: ubuntu-latest\n    steps: []\n'
ok &= rejects_runs_on('negative-control.yml', 'probe', probe, 'ubuntu-latest', '[self-hosted, cretas]', 'plain self-hosted')
ok &= rejects_runs_on('negative-control.yml', 'probe', probe, 'ubuntu-latest', MINI_ROUTE, 'unregistered job')
for (wf, job), allowed in SELF_HOSTED_ALLOWED.items():
    text = (WF / wf).read_text(encoding='utf-8')
    block = dict(jobs(text)).get(job, '')
    baseline = f'on: push\njobs:\n  {job}:\n{block.rstrip()}\n'
    # Keep timeout and Java heap from a genuinely legal job. Change just runs-on.
    for label, old, new in (
        ('merge_group fallback restored', SAME_REPO_GUARD, LEGACY_GROUP_GUARD),
        ('repository guard removed', SAME_REPO_GUARD + ' && ', ''),
        ('runner decision removed', ROUTE_OUTPUT, ''),
        ('rerun guard removed (attempt 1 outputs reused)', FIRST_ATTEMPT, ''),
        ('rollout policy removed', POLICY_VERSION, ''),
    ):
        ok &= rejects_runs_on(wf, job, baseline, allowed, allowed.replace(old, new), label)
    shard_gate = {'fast-contracts-shard': FAST_SHARD_GATE, 'postgres-reversal-concurrency-shard': PG_SHARD_GATE}.get(job)
    if shard_gate:
        ok &= rejects_runs_on(wf, job, baseline, allowed, allowed.replace(shard_gate, ''), 'shard gate removed')

# Reproduce the old false-green control: a hosted job with only the unrelated
# timeout or heap error satisfies an "any violation" assertion.
for wf, job, remove, unrelated in (
    ('rn-gate.yml', 'jest', r'^    timeout-minutes:.*\n', 'has no timeout-minutes'),
    ('web-admin-gate.yml', 'vitest', r'^    timeout-minutes:.*\n', 'has no timeout-minutes'),
    ('java-gate-pr.yml', 'fast-contracts-shard', r'^      CRETAS_JAVA_FORK_XMX:.*\n', 'does not set CRETAS_JAVA_FORK_XMX in its job env'),
):
    block = dict(jobs((WF / wf).read_text(encoding='utf-8')))[job]
    hosted = f'on: push\njobs:\n  {job}:\n{block.rstrip()}\n'.replace(SELF_HOSTED_ALLOWED[wf, job], 'ubuntu-latest')
    incomplete = re.sub(remove, '', hosted, count=1, flags=re.M)
    result = violations(wf, incomplete)
    if violations(wf, hosted) or result != [f'{wf}/{job} {unrelated}']:
        print(f'FAIL: invalid baseline control: {wf}/{job}: {result}')
        ok = False
    else:
        print(f'  ok - {wf}/{job} old any-violation test would accept a hosted job: {unrelated}')

REQUIRED_LABEL_CHECKS = {
    'java-gate-pr.yml': ('fast-contracts', 'postgres-reversal-concurrency'),
    'rn-gate.yml': ('jest',),
    'web-admin-gate.yml': ('vitest',),
}


def label_wiring_errors(wf, text, group_guard, job_condition, ignored):
    blocks = dict(jobs(text))
    bad = []
    if not re.search(r'^  group: .*' + re.escape(group_guard), text, re.M):
        bad.append('label run shares active PR concurrency group')
    if job_condition not in blocks['changes']:
        bad.append('unrelated label can run changes')
    for job, block in blocks.items():
        if job != 'changes' and "needs.changes.result != 'skipped'" not in block:
            bad.append(f'unrelated label can run {job}')
    for job in REQUIRED_LABEL_CHECKS[wf]:
        name = f"    name: ${{{{ ({ignored}) && '{job} (ignored label)' || '{job}' }}}}"
        if name not in blocks[job]:
            bad.append(f'unrelated label can publish required check {job}')
    return bad


for wf in MINI_WORKFLOWS:
    text = (WF / wf).read_text(encoding='utf-8')
    changes = dict(jobs(text)).get('changes', '')
    print('READING: %s/changes runs-on=%r' % (wf, runs_on(changes)))
    required = ('route-mini-or-hosted.sh', 'git archive --output="$route_dir/router.tar" "$TRUSTED_BASE_SHA"',
                'git merge-base --is-ancestor "$TRUSTED_BASE_SHA" refs/remotes/origin/main',
                'MINI_POOL_CAPACITY: ${{ vars.MINI_POOL_CAPACITY }}',
                "github.event.label.name != 'ci-hosted'",  # A recovery label always bypasses mini routing.
                "vars.MINI_POOL_V2_ENABLED == '1'", "vars.MINI_POOL_V2_ENABLED == '0'", 'outputs.runner', 'outputs.route_policy', 'outputs.mini_group_verified',
                'continue-on-error: true', 'timeout 45s bash')
    if (runs_on(changes) != 'ubuntu-latest' or any(part not in changes for part in required)
            or not 0 <= changes.find('- id: route') < changes.find('- id: filter')):
        print(f'FAIL: {wf}/changes trusted hosted route wiring is incomplete')
        ok = False
    trigger = re.search(r'(?m)^  pull_request:\n    types: \[([^\n]+)\]', text)
    if not trigger or 'labeled' not in trigger.group(1).split(', '):
        print(f'FAIL: {wf} misses pull_request labeled recovery trigger')
        ok = False
    labels = ('ci-hosted', 'java-full-ready')
    ignored = "github.event.action == 'labeled'" + ''.join(f" && github.event.label.name != '{label}'" for label in labels)
    accepted = "github.event.action != 'labeled'" + ''.join(f" || github.event.label.name == '{label}'" for label in labels)
    group_guard = f"({ignored} && format('ignored-label-{{0}}', github.run_id)) || "
    job_condition = f"    if: ${{{{ {accepted} }}}}"
    actual = label_wiring_errors(wf, text, group_guard, job_condition, ignored)
    bad_group = label_wiring_errors(wf, text.replace(group_guard, '', 1), group_guard, job_condition, ignored)
    bad_filter = label_wiring_errors(wf, text.replace(job_condition, '', 1), group_guard, job_condition, ignored)
    job = REQUIRED_LABEL_CHECKS[wf][0]
    bad_name = label_wiring_errors(wf, text.replace(f"'{job} (ignored label)'", f"'{job}'", 1), group_guard, job_condition, ignored)
    if (actual or bad_group != ['label run shares active PR concurrency group']
            or bad_filter != ['unrelated label can run changes']
            or bad_name != [f'unrelated label can publish required check {job}']):
        print(f'FAIL: {wf} label wiring: actual={actual}, concurrency={bad_group}, filter={bad_filter}, check name={bad_name}')
        ok = False
    else:
        print(f'  ok - {wf} labels {labels}; concurrency, job filter, and required-check-name mutations are red')


def rollout_errors(steps):
    required = (
        ('关闭新路由', ('MINI_POOL_V2_ENABLED', '排队和运行', '分页')),
        ('先验证双标签消费者', ('`online`', '`cretas-mini`', '`cretas-mini-pg`', '`cretas-mini-fast`', 'runner_name')),
        ('排空槽 5', ('从 `enabled-slots` 移除 5', 'Runner.Listener', 'Runner.Worker', '`busy`', '作业结论')),
        ('停止并清理槽 5', ('完成排空后', 'launchctl bootout', '离线且空闲', '槽 1–4')),
        ('最后开放新路由', ('前四步完成', 'security backlog', '不阻塞开放', 'MINI_POOL_V2_ENABLED=0',
                       'GITHUB_TOKEN', 'headCommit', 'MINI_POOL_V2_ENABLED=1', 'runner_name')),
    )
    return [name for index, (name, evidence) in enumerate(required)
            if index >= len(steps) or steps[index][0] != name
            or any(reading not in steps[index][1] for reading in evidence)]


rollout = (ROOT / 'scripts/ci/canada-mini-gate/rollout.md').read_text(encoding='utf-8')
steps = re.findall(r'^\d+\. \*\*([^*]+)。\*\* ([^\n]+)', rollout, re.M)
wrong_order = rollout_errors(steps[:2] + steps[3:4] + steps[2:3] + steps[4:])
early_open = rollout_errors(steps[-1:] + steps[:-1])
if rollout_errors(steps) or '排空槽 5' not in wrong_order or '关闭新路由' not in early_open:
    print('FAIL: rollout must verify both generations, then drain and retire slot 5 before activation')
    ok = False
else:
    print('  ok - rollout drain-before-stop and enable-last; stop-before-drain and enable-first mutations are red')
if any(proof not in rollout for proof in ('**5 分钟**', 'gh run rerun <run-id>', 'run_attempt > 1', 'reason=retry-hosted')):
    print('FAIL: rollout is missing the timed hosted recovery and its readback')
    ok = False

present = [k for k in SELF_HOSTED_ALLOWED if k in seen]
print('READING: self_hosted_allowed_present=%d/%d' % (len(present), len(SELF_HOSTED_ALLOWED)))
# 登记了但 workflow 里找不到 = 登记表过期 (改名/删除后白名单还放行一个不存在的 job), 判红。
if len(present) != len(SELF_HOSTED_ALLOWED):
    print('FAIL: SELF_HOSTED_ALLOWED 里有 workflow 不存在的 job: %s' % sorted(set(SELF_HOSTED_ALLOWED) - set(present)))
    ok = False

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
print('PASS: 全部 job 走 GitHub 托管 runner (登记的自建路由除外, 且 fork PR 落托管)' if ok else 'FAIL')
sys.exit(0 if ok else 1)
PY
