import json
import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import sys
sys.path.insert(0, str(Path(__file__).parents[1]))

import create_private_config as private_config
import linux_service


class LinuxProvisionTests(unittest.TestCase):
    def test_linux_root_is_one_new_child_and_rejects_escape_existing_and_repo(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder) / 'approved'
            base.mkdir()
            with patch.object(private_config, 'LINUX_PRIVATE_ROOT', base):
                self.assertEqual(private_config._approved_root(base / 'slot-a', 'linux'), base / 'slot-a')
                with self.assertRaisesRegex(RuntimeError, 'itself'):
                    private_config._approved_root(base, 'linux')
                with self.assertRaisesRegex(RuntimeError, 'direct child'):
                    private_config._approved_root(base / 'nested' / 'slot-a', 'linux')
                existing = base / 'existing'
                existing.mkdir()
                with self.assertRaisesRegex(RuntimeError, 'new absolute'):
                    private_config._approved_root(existing, 'linux')
                if os.name != 'nt':
                    outside = Path(folder) / 'outside'
                    outside.mkdir()
                    (base / 'escape').symlink_to(outside, target_is_directory=True)
                    with self.assertRaisesRegex(RuntimeError, 'new absolute|symlink'):
                        private_config._approved_root(base / 'escape', 'linux')
                repo_child = base / 'repo-slot'
                (repo_child.parent / '.git').mkdir()
                with self.assertRaisesRegex(RuntimeError, 'outside Git'):
                    private_config._approved_root(repo_child, 'linux')
                repo = Path(folder) / 'checkout'
                (repo / '.git').mkdir(parents=True)
                outside_repo = repo / 'new-slot'
                with self.assertRaisesRegex(RuntimeError, 'outside Git'):
                    private_config._approved_root(outside_repo, 'linux')
                self.assertFalse(outside_repo.exists())

    def test_linux_create_uses_private_permissions_and_only_fingerprint(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            base = folder / 'approved'
            base.mkdir()
            jar = folder / 'app.jar'
            jar.write_bytes(b'private jar')
            baseline = folder / 'baseline'
            baseline.mkdir()
            (baseline / 'manifest.json').write_text('{}', encoding='utf-8')
            root = base / 'slot-a'
            with patch.object(private_config, 'LINUX_PRIVATE_ROOT', base):
                result = private_config.create(root, jar, baseline, 'a' * 64, platform_name='linux')
            self.assertEqual(Path(result['root']), root)
            if os.name != 'nt':
                self.assertEqual(stat.S_IMODE(root.stat().st_mode), 0o700)
                for name in ('app.jar', 'application-sandbox.properties', 'orchestrator.private.json', 'runtime.private.json'):
                    self.assertEqual(stat.S_IMODE((root / name).stat().st_mode), 0o600, name)
            runtime = json.loads((root / 'runtime.private.json').read_text(encoding='utf-8'))
            self.assertEqual(runtime['listen_host'], '127.0.0.1')
            self.assertEqual(runtime['listen_port'], 10041)
            properties = (root / 'application-sandbox.properties').read_text(encoding='utf-8')
            self.assertIn('cretas.demo.sandbox.production-jwt-sha256=' + 'a' * 64, properties)
            self.assertNotIn('production-secret', properties)

    def test_other_platform_is_explicitly_rejected(self):
        with self.assertRaisesRegex(RuntimeError, 'Windows or Linux'):
            private_config._approved_root(Path('/tmp/slot'), 'darwin')

    def test_review_templates_are_loopback_only_and_secret_free(self):
        unit = linux_service.render_systemd_unit()
        env = linux_service.render_gateway_review_env()
        template = (Path(__file__).parents[1] / 'linux' / 'cretas-f006-demo-sandbox.service.in').read_text(encoding='utf-8')
        self.assertIn('/opt/cretas/f006-demo-sandbox/venv/bin/python', unit)
        self.assertIn('--config /var/lib/cretas/f006-demo-sandbox/runtime/runtime.private.json', unit)
        self.assertIn('UMask=0077', unit)
        self.assertEqual(template, unit)
        self.assertIn('127.0.0.1:10041', env)
        self.assertIn('blue and green', env.lower())
        self.assertIn('CRETAS_DEMO_SANDBOX_ENABLED=false', env)
        self.assertIn('CRETAS_DEMO_SANDBOX_MANAGER_BASE_URL=http://127.0.0.1:10041', env)
        self.assertIn('CRETAS_DEMO_SANDBOX_CONTROL_KEY=', env)
        self.assertRegex(env, r'(?m)^CRETAS_DEMO_SANDBOX_CONTROL_KEY=$')
        for text in (unit, env):
            self.assertNotIn('bearer_token', text.lower())
            self.assertNotIn('cretas_prod', text.lower())
            self.assertNotIn('systemctl', text.lower())
            self.assertNotIn('docker volume rm', text.lower())
        self.assertNotIn('0.0.0.0:10041', unit + env)

    def test_review_generation_writes_only_local_review_files(self):
        with tempfile.TemporaryDirectory() as folder:
            files = linux_service.write_review_files(folder)
            self.assertEqual(set(files), {'service', 'gateway_env_review'})
            self.assertTrue(files['service'].name.endswith('.service'))
            self.assertTrue(files['gateway_env_review'].name.endswith('.env.review'))
            if os.name != 'nt':
                self.assertEqual(stat.S_IMODE(files['gateway_env_review'].stat().st_mode), 0o600)


if __name__ == '__main__':
    unittest.main()
