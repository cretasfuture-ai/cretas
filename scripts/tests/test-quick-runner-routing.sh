#!/usr/bin/env bash
# GUARDS=scripts/ci/switch_ci_runner.py,tools/ci/windows-quick/job-started.sh,tools/ci/windows-quick/register.sh,tools/ci/windows-quick/cretas-ci-quick.service
# GUARDS=.github/workflows/secret-regression.yml,.github/workflows/soft-delete-scan-surface.yml,.github/workflows/source-hygiene.yml,.github/workflows/artifact-reaper.yml
# 小检查专用槽 (cretas-quick) 的路由契约。Fixture-only: 不写真实 workflow、不派发、不联网。
#
# 钉住四件事:
#   1. 四个小检查工作流的 runs-on 正好是 [self-hosted, cretas-quick], 任务名 = QUICK_JOBS;
#   2. 槽的任务白名单 (job-started.sh) 与 QUICK_JOBS 两份一致 (形态 D: 两份必漂);
#   3. 重任务落不到这个 1 核 / 1G 的槽: 槽只带 cretas-quick 一个自定义标签, 且除小检查外
#      没有任何 runs-on 能被「self-hosted + Linux + X64 + cretas-quick」满足;
#   4. 切托管再切回来逐字还原; 通用 [self-hosted, cretas] 只能经 --adopt 显式迁移。
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
PY=${PYTHON:-python3}
if ! "$PY" -c 'import sys' >/dev/null 2>&1; then PY=python; fi
"$PY" - "$ROOT_DIR" <<'PY'
import importlib.util
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(sys.argv[1])
SOURCE = ROOT / 'scripts/ci/switch_ci_runner.py'
spec = importlib.util.spec_from_file_location('runner_switch', SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
QUICK = {'secret-regression.yml', 'soft-delete-scan-surface.yml', 'source-hygiene.yml', 'artifact-reaper.yml'}
EXPECTED = '    runs-on: [self-hosted, cretas-quick]'
SLOT_LABELS = {'self-hosted', 'Linux', 'X64', 'cretas-quick'}
HOOK = ROOT / 'tools/ci/windows-quick/job-started.sh'
REGISTER = ROOT / 'tools/ci/windows-quick/register.sh'
UNIT = ROOT / 'tools/ci/windows-quick/cretas-ci-quick.service'


def routes(text):
    return [line.rstrip('\r') for line in text.split('\n') if line.startswith('    runs-on:')]


def job_names(text):
    body = text.split('\njobs:\n', 1)[1]
    return [m.group(1) for m in re.finditer(r'^  ([A-Za-z0-9_-]+):\s*$', body, re.M)]


def labels(route):
    inner = route.split('runs-on:', 1)[1].strip()
    if not inner.startswith('['):
        return {inner}
    return {part.strip() for part in inner.strip('[]').split(',')}


class QuickRoutingContract(unittest.TestCase):
    def test_quick_workflows_and_jobs_are_exact(self):
        self.assertEqual(set(module.QUICK_WORKFLOWS), QUICK)
        self.assertEqual(module.SELF_QUICK, EXPECTED)
        self.assertTrue(QUICK <= set(module.WORKFLOWS))
        jobs = set()
        for name in QUICK:
            text = module.read(ROOT / '.github/workflows' / name)
            self.assertEqual(routes(text), [EXPECTED], name)
            jobs.update(job_names(text))
        self.assertEqual(jobs, set(module.QUICK_JOBS))

    def test_hook_allowlist_equals_quick_jobs(self):
        hook = HOOK.read_text(encoding='utf-8')
        case = re.search(r'^\s+([a-z|-]+)\)\s*;;\s*$', hook, re.M)
        self.assertIsNotNone(case, 'allowlist case line not found in job-started.sh')
        self.assertEqual(set(case.group(1).split('|')), set(module.QUICK_JOBS) | {'preflight'})
        self.assertIn('exit 75', hook)

    def test_slot_is_small_and_only_labelled_quick(self):
        register = REGISTER.read_text(encoding='utf-8')
        found = re.findall(r'--labels\s+(\S+)', register)
        self.assertEqual(found, ['cretas-quick'])
        unit = UNIT.read_text(encoding='utf-8')
        for setting in ('CPUQuota=100%', 'MemoryMax=1G', 'MemorySwapMax=0', 'User=cretas-ci-quick'):
            self.assertIn(setting, unit.split('\n'), setting)

    def test_no_other_route_can_land_on_the_slot(self):
        for path in sorted((ROOT / '.github/workflows').glob('*.yml')):
            for route in routes(module.read(path)):
                needed = labels(route)
                if needed <= SLOT_LABELS:
                    self.assertIn(path.name, QUICK, '%s routes %s onto the 1 GiB slot' % (path.name, route))
                if path.name not in QUICK:
                    self.assertNotIn('cretas-quick', needed, path.name)


class QuickSwitchRoundTrip(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='cretas-quick-routing-')
        self.addCleanup(self.tmp.cleanup)
        self.repo = pathlib.Path(self.tmp.name)
        (self.repo / 'scripts/ci').mkdir(parents=True)
        self.wf = self.repo / '.github/workflows'
        self.wf.mkdir(parents=True)
        shutil.copy2(SOURCE, self.repo / 'scripts/ci/switch_ci_runner.py')
        for name in module.WORKFLOWS:
            (self.wf / name).write_bytes(module.read(ROOT / '.github/workflows' / name).encode('utf-8'))

    def run_switch(self, *args, expected=0):
        result = subprocess.run([sys.executable, str(self.repo / 'scripts/ci/switch_ci_runner.py'), *args],
                                cwd=self.repo, text=True, encoding='utf-8', capture_output=True,
                                env={**os.environ, 'PYTHONIOENCODING': 'utf-8'})
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result.stdout

    def snapshot(self):
        return {p.name: p.read_bytes() for p in self.wf.iterdir()}

    def test_hosted_and_back_restores_every_file_byte_for_byte(self):
        before = self.snapshot()
        self.run_switch('hosted')
        for name in QUICK:
            self.assertEqual(routes((self.wf / name).read_text(encoding='utf-8')), [module.HOSTED], name)
        self.run_switch('self-hosted')
        self.assertEqual(self.snapshot(), before)

    def test_generic_route_needs_explicit_adoption_and_only_quick_files_change(self):
        for name in QUICK:
            path = self.wf / name
            path.write_text(module._swap_lines(module.read(path), EXPECTED, module.SELF), encoding='utf-8', newline='')
        before = self.snapshot()
        self.run_switch('--check', expected=1)
        self.run_switch('self-hosted', expected=1)
        self.assertEqual(self.snapshot(), before)
        self.run_switch('self-hosted', '--adopt')
        after = self.snapshot()
        self.assertEqual({k for k in before if before[k] != after[k]}, QUICK)
        for name in QUICK:
            self.assertEqual(routes((self.wf / name).read_text(encoding='utf-8')), [EXPECTED], name)


if __name__ == '__main__':
    unittest.main(argv=['quick-routing'], verbosity=2)
PY
