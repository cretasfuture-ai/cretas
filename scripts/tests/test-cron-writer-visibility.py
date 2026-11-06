import importlib.util
from pathlib import Path
import tempfile
import unittest

path = Path(__file__).resolve().parents[1] / 'ops/audit-cron-writer-visibility.py'
spec = importlib.util.spec_from_file_location('visibility', path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class VisibilityContract(unittest.TestCase):
    def test_explicit_crontab_reads_are_not_mutators(self):
        for args in (['crontab', '-l'], ['/usr/bin/crontab', '-u', 'root', '-l'],
                     ['crontab', '--help'], ['crontab', '-V']):
            self.assertIsNone(module.writer_kind(args))

    def test_install_edit_remove_and_stdin_crontab_are_candidates(self):
        for args in (['crontab'], ['crontab', '-'], ['crontab', '-e'], ['crontab', '-r'],
                     ['crontab', '/tmp/fixture'], ['crontab', '-u', 'root']):
            self.assertEqual(module.writer_kind(args), 'crontab-mutator')

    def test_only_target_unit_mutation_is_flagged(self):
        for verb in ('start', 'stop', 'enable', 'disable', 'edit', 'mask'):
            self.assertEqual(module.writer_kind(['systemctl', verb, module.UNITS[0]]),
                             'target-unit-mutator')
        self.assertIsNone(module.writer_kind(['systemctl', 'show', module.UNITS[0]]))
        self.assertIsNone(module.writer_kind(['systemctl', 'restart', 'unrelated.service']))

    def test_path_access_is_ambiguous_and_never_called_definite_writer(self):
        for exe in ('cat', 'tee', 'cp', 'python'):
            self.assertEqual(module.writer_kind([exe, '/var/spool/cron/root']),
                             'target-path-process-review-required')

    def test_inline_code_without_visible_target_remains_unobservable(self):
        self.assertIsNone(module.writer_kind(['python', '-']))
        self.assertIsNone(module.writer_kind(['bash', '-s']))

    def test_fingerprint_returns_hash_not_content(self):
        with tempfile.TemporaryDirectory() as folder:
            p = Path(folder) / 'fixture'
            marker = 'private-canary-must-never-be-returned'
            p.write_text(marker)
            value = module.fingerprint(p)
            self.assertEqual(value['sha256'], module.hashlib.sha256(marker.encode()).hexdigest())
            self.assertNotIn(marker, str(value))


if __name__ == '__main__':
    unittest.main()
