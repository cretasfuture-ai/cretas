"""Exercise the publisher's actual transaction against temporary files."""
import base64
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-restaurant-removal.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)

class PublishTransactionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'site'
        self.root.mkdir()
        self.rows = []
        for name in ['index.html', 'demo.html']:
            (self.root / name).write_bytes(b'old ' + name.encode())
            self.rows.append({'path':name,'baseline':hashlib.sha256((self.root/name).read_bytes()).hexdigest(),
                             'content':base64.b64encode(b'new '+name.encode()).decode()})

    def run_transaction(self, check=False):
        data = {'head':'a'*40, 'check':check, 'files':self.rows}
        code = publisher.REMOTE.replace(publisher.ROOT, self.root.as_posix())
        code = code.replace('/tmp/cretas-showcase-publish.lock', (Path(self.temp.name)/'lock').as_posix())
        code = code.replace('/www/backup/cretas-showcase', (Path(self.temp.name)/'backup').as_posix())
        # Linux flock/chown are exercised by the live read-only check; file
        # replacement and rollback are real operations in this Windows test.
        code = code.replace('import base64, fcntl,', 'import base64,')
        code = code.replace('fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)', '')
        code = code.replace('os.chown(tmp,p.stat().st_uid,p.stat().st_gid)', '')
        output = io.StringIO()
        namespace = {}
        with patch('sys.stdin', io.StringIO(json.dumps(data))), contextlib.redirect_stdout(output):
            try:
                exec(code, namespace)
            except SystemExit as error:
                self.assertEqual(error.code, 0)
            finally:
                if 'lock' in namespace:
                    namespace['lock'].close()
        return json.loads(output.getvalue())

    def test_readonly_check_preserves_content(self):
        before = [(self.root/r['path']).read_bytes() for r in self.rows]
        self.assertEqual(self.run_transaction(True)['status'], 'baseline_verified')
        self.assertEqual(before, [(self.root/r['path']).read_bytes() for r in self.rows])

    def test_stale_baseline_rejects_entire_batch(self):
        (self.root/'demo.html').write_bytes(b'concurrent change')
        with self.assertRaisesRegex(RuntimeError, 'live baseline differs'):
            self.run_transaction()
        self.assertEqual((self.root/'index.html').read_bytes(), b'old index.html')
        self.assertEqual((self.root/'demo.html').read_bytes(), b'concurrent change')

    def test_publish_backup_readback_and_idempotency(self):
        self.assertEqual(self.run_transaction()['changed'], 2)
        self.assertEqual(self.run_transaction()['changed'], 0)
        backup=Path(self.temp.name)/'backup'/('a'*40)
        self.assertEqual((backup/'index.html').read_bytes(), b'old index.html')
        self.assertEqual((self.root/'index.html').read_bytes(), b'new index.html')

    def test_partial_replace_failure_restores_previous_files(self):
        original = os.replace
        calls = 0
        def fail_second(src, dst):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError('injected write failure')
            original(src, dst)
        with patch('os.replace', fail_second):
            with self.assertRaisesRegex(OSError, 'injected write failure'):
                self.run_transaction()
        for row in self.rows:
            self.assertEqual((self.root/row['path']).read_bytes(), b'old '+row['path'].encode())

    def test_path_escape_rejected_before_writes(self):
        self.rows[1]['path'] = '../outside.html'
        with self.assertRaisesRegex(RuntimeError, 'invalid path'):
            self.run_transaction()
        self.assertEqual((self.root/'index.html').read_bytes(), b'old index.html')

if __name__ == '__main__':
    unittest.main()
