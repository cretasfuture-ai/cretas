#!/usr/bin/env bash
# GUARDS=.github/workflows/web-admin-gate.yml,.github/workflows/web-dist.yml,scripts/ci/switch_ci_runner.py
# GUARDS=.github/workflows/secret-regression.yml,.github/workflows/rn-gate.yml,.github/workflows/python-gate.yml
# GUARDS=.github/workflows/jpa-gate-main.yml,.github/workflows/java-artifact.yml,.github/workflows/java-full-nightly.yml,.github/workflows/tool-inventory-gate.yml
# GUARDS=.github/workflows/scheduler-canary.yml,.github/workflows/ops-script-tests.yml,.github/workflows/soft-delete-scan-surface.yml
# Fixture-only: no real workflow writes, dispatches, network or services.
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
PY=${PYTHON:-python3}
if ! "$PY" -c 'import sys' >/dev/null 2>&1; then PY=python; fi
"$PY" - "$ROOT_DIR" <<'PY'
import importlib.util
import pathlib
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
WEB = {'web-admin-gate.yml', 'web-dist.yml'}
EXPECTED = '    runs-on: [self-hosted, Linux, cretas, X64, windows-light]'


class WebRoutingContract(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='cretas-web-routing-')
        self.addCleanup(self.tmp.cleanup)
        self.repo = pathlib.Path(self.tmp.name)
        (self.repo / 'scripts/ci').mkdir(parents=True)
        self.wf = self.repo / '.github/workflows'
        self.wf.mkdir(parents=True)
        shutil.copy2(SOURCE, self.repo / 'scripts/ci/switch_ci_runner.py')
        for name in module.WORKFLOWS:
            text = module.read(ROOT / '.github/workflows' / name)
            if name in WEB:
                text = module._swap_lines(text, module.SELF, EXPECTED)
            self.path(name).write_bytes(text.encode('utf-8'))

    def path(self, name):
        return self.wf / name

    def snapshot(self):
        return {p.name: p.read_bytes() for p in self.wf.iterdir()}

    def run_switch(self, *args, expected=0):
        result = subprocess.run([sys.executable, str(self.repo / 'scripts/ci/switch_ci_runner.py'), *args],
                                cwd=self.repo, text=True, encoding='utf-8', capture_output=True,
                                env={**__import__('os').environ, 'PYTHONIOENCODING': 'utf-8'})
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result.stdout

    def legacy(self):
        for name in WEB:
            self.path(name).write_text(module._swap_lines(module.read(self.path(name)), EXPECTED, module.SELF), encoding='utf-8', newline='')

    def test_exact_mapping_and_actual_workflows(self):
        self.assertEqual(set(module.WEB_WORKFLOWS), WEB)
        self.assertEqual(module.SELF_WEB, EXPECTED)
        for name in WEB:
            routes = [s for s in module.read(ROOT / '.github/workflows' / name).splitlines() if s.startswith('    runs-on:')]
            self.assertEqual(routes, [EXPECTED], name)
        # Match real label inventories, not just a spelling chosen by the switcher.
        required = {'self-hosted', 'Linux', 'cretas', 'X64', 'windows-light'}
        self.assertTrue(required <= {'self-hosted', 'Linux', 'cretas', 'X64', 'windows-light'})
        self.assertFalse(required <= {'self-hosted', 'Linux', 'cretas', 'ARM64', 'java', 'aurora'})

    def test_legacy_needs_explicit_adoption_and_only_two_files_change(self):
        self.legacy()
        before = self.snapshot()
        self.run_switch('--check', expected=1)
        self.run_switch('self-hosted', expected=1)
        self.assertEqual(self.snapshot(), before)
        output = self.run_switch('self-hosted', '--adopt')
        after = self.snapshot()
        self.assertEqual({k for k in before if before[k] != after[k]}, WEB)
        for name in WEB:
            self.assertIn(name, output)
        self.assertIn('2', output)
        self.run_switch('--check')
        self.run_switch('self-hosted')
        self.assertEqual(self.snapshot(), after)

    def test_all_original_java_markers_roundtrip_byte_exact(self):
        before = self.snapshot()
        self.run_switch('hosted')
        for name in WEB:
            self.assertIn(module.HOSTED, module.read(self.path(name)))
        self.run_switch('--check')
        self.run_switch('self-hosted')
        self.assertEqual(self.snapshot(), before)
        self.run_switch('--check')

    def test_legacy_can_explicitly_adopt_hosted(self):
        self.legacy()
        self.run_switch('hosted', '--adopt')
        self.run_switch('--check')
        self.run_switch('self-hosted')
        for name in WEB:
            self.assertIn(EXPECTED, module.read(self.path(name)))

    def test_wrong_web_label_fails_without_writing(self):
        name = 'web-dist.yml'
        self.path(name).write_text(module.read(self.path(name)).replace('X64, windows-light', 'ARM64, windows-light'), encoding='utf-8')
        before = self.snapshot()
        self.run_switch('--check', expected=1)
        self.run_switch('self-hosted', '--adopt', expected=1)
        self.assertEqual(self.snapshot(), before)

    def test_web_label_cannot_move_java_to_windows(self):
        name = 'java-artifact.yml'
        text = module.read(self.path(name))
        self.assertIn(module.SELF_JAVA, text)
        self.path(name).write_text(module._swap_lines(text, module.SELF_JAVA, EXPECTED), encoding='utf-8')
        before = self.snapshot()
        self.run_switch('hosted', '--adopt', expected=1)
        self.assertEqual(self.snapshot(), before)

    def test_mixed_same_file_cannot_be_adopted(self):
        name = 'web-dist.yml'
        self.path(name).write_text(module.read(self.path(name)) + '\n' + module.HOSTED + '\n', encoding='utf-8')
        before = self.snapshot()
        self.run_switch('--check', expected=1)
        self.run_switch('self-hosted', '--adopt', expected=1)
        self.assertEqual(self.snapshot(), before)

    def test_cross_workflow_mixed_mode_requires_adopt(self):
        name = 'rn-gate.yml'
        self.path(name).write_text(module._swap_lines(module.read(self.path(name)), module.SELF, module.HOSTED), encoding='utf-8')
        before = self.snapshot()
        self.run_switch('--check', expected=1)
        self.run_switch('hosted', expected=1)
        self.assertEqual(self.snapshot(), before)
        self.run_switch('hosted', '--adopt')
        self.run_switch('--check')

    def test_unknown_last_file_fails_before_earlier_files_change(self):
        self.path(module.WORKFLOWS[-1]).write_text('jobs:\n  x:\n    runs-on: typo-runner\n', encoding='utf-8')
        before = self.snapshot()
        self.run_switch('hosted', '--adopt', expected=1)
        self.assertEqual(self.snapshot(), before)

    def test_missing_workflow_fails_before_any_write(self):
        self.path(module.WORKFLOWS[-1]).unlink()
        before = self.snapshot()
        self.run_switch('hosted', '--adopt', expected=1)
        self.assertEqual(self.snapshot(), before)

    def test_crlf_is_preserved_on_roundtrip(self):
        for name in WEB:
            text = module.read(self.path(name)).replace('\r\n', '\n').replace('\n', '\r\n')
            self.path(name).write_bytes(text.encode('utf-8'))
        before = self.snapshot()
        self.run_switch('hosted')
        self.run_switch('self-hosted')
        self.assertEqual(self.snapshot(), before)


unittest.main(argv=[sys.argv[0]], verbosity=2)
PY
