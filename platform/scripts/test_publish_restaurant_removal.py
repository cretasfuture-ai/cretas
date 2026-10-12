"""Exercise the publisher's actual transaction against temporary files."""
import base64
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import re
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

    def run_transaction(self, check=False, action=None):
        data = {'head':'a'*40, 'action':action or ('check' if check else 'publish'), 'files':self.rows}
        code = publisher.REMOTE.replace(publisher.ROOT, self.root.as_posix())
        code = code.replace('/tmp/cretas-showcase-publish.lock', (Path(self.temp.name)/'lock').as_posix())
        code = code.replace('/www/backup/cretas-showcase', (Path(self.temp.name)/'backup').as_posix())
        # Linux flock/chown are exercised by the live read-only check; file
        # replacement and rollback are real operations in this Windows test.
        if os.name == 'nt':
            code = code.replace('import base64, fcntl,', 'import base64,')
            code = code.replace('fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)', '')
            code = re.sub(r'^lock=.*$', "lock=open('"+(Path(self.temp.name)/'lock').as_posix()+"','w')", code, flags=re.M)
            code = code.replace("os.fchmod(fd,meta['mode'])", "os.chmod(tmp,meta['mode'])")
            code = code.replace("os.fchown(fd,meta['uid'],meta['gid'])", '')
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
        self.assertEqual(self.run_transaction()['status'], 'published')
        self.assertEqual(self.run_transaction()['status'], 'already_published')
        backup=Path(self.temp.name)/'backup'/('a'*40)
        self.assertEqual((backup/'index.html').read_bytes(), b'old index.html')
        self.assertEqual((self.root/'index.html').read_bytes(), b'new index.html')

    def test_partial_replace_failure_restores_previous_files(self):
        p=self.root/'index.html'
        if os.name != 'nt':
            os.chmod(p,0o640)
            os.chown(p,65534,65534)
        before=p.stat()
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
        after=p.stat()
        self.assertEqual((before.st_mode & 0o777,before.st_uid,before.st_gid),
                         (after.st_mode & 0o777,after.st_uid,after.st_gid))

    def test_path_escape_rejected_before_writes(self):
        self.rows[1]['path'] = '../outside.html'
        with self.assertRaisesRegex(RuntimeError, 'invalid path'):
            self.run_transaction()
        self.assertEqual((self.root/'index.html').read_bytes(), b'old index.html')

    def test_guarded_successful_release_rollback(self):
        self.run_transaction()
        self.assertEqual(self.run_transaction(action='rollback')['status'], 'rolled_back')
        self.assertEqual((self.root/'index.html').read_bytes(), b'old index.html')

    def test_https_failure_restores_actual_files(self):
        def transaction(payload):
            return self.run_transaction(action=payload['action'])
        with patch.object(publisher,'remote',side_effect=transaction), \
             patch.object(publisher,'verify_https',side_effect=RuntimeError('HTTPS failure')), \
             contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError,'prior website restored'):
                publisher.publish_and_verify({'head':'a'*40,'action':'publish','files':self.rows})
        self.assertEqual((self.root/'index.html').read_bytes(), b'old index.html')
        self.assertEqual((self.root/'demo.html').read_bytes(), b'old demo.html')

    def test_rollback_refuses_concurrent_edit(self):
        self.run_transaction()
        (self.root/'demo.html').write_bytes(b'another release')
        with self.assertRaisesRegex(RuntimeError, 'rollback current content differs'):
            self.run_transaction(action='rollback')
        self.assertEqual((self.root/'index.html').read_bytes(), b'new index.html')

    @unittest.skipIf(os.name == 'nt', 'Linux symlink/O_EXCL test executed on gateway /tmp')
    def test_preexisting_temp_symlink_cannot_modify_outside_file(self):
        self.rows=self.rows[:1]
        outside=Path(self.temp.name)/'outside'
        outside.write_bytes(b'protected')
        os.symlink(outside,self.root/'.cretas-publish-poison')
        with patch.object(tempfile,'_get_candidate_names',return_value=iter(['poison','safe'])):
            self.run_transaction()
        self.assertEqual(outside.read_bytes(),b'protected')
        self.assertEqual((self.root/'index.html').read_bytes(),b'new index.html')

    def test_concurrent_edit_blocks_entire_exception_rollback(self):
        name='third.html';(self.root/name).write_bytes(b'old third.html')
        self.rows.append({'path':name,'baseline':hashlib.sha256(b'old third.html').hexdigest(),
                          'content':base64.b64encode(b'new third.html').decode()})
        original=os.replace;calls=0
        def fail_third(src,dst):
            nonlocal calls
            calls+=1
            if calls==3:
                (self.root/'index.html').write_bytes(b'concurrent edit')
                raise OSError('injected write failure')
            original(src,dst)
        with patch('os.replace',fail_third):
            with self.assertRaisesRegex(RuntimeError,'ROLLBACK_BLOCKED'):
                self.run_transaction()
        self.assertEqual((self.root/'index.html').read_bytes(),b'concurrent edit')
        self.assertEqual((self.root/'demo.html').read_bytes(),b'new demo.html')

    @unittest.skipIf(os.name == 'nt', 'Linux ownership metadata tested on the gateway in /tmp')
    def test_linux_rollback_preserves_mode_uid_gid(self):
        p=self.root/'index.html'
        os.chmod(p, 0o640)
        os.chown(p, 65534, 65534)
        before=p.stat()
        self.run_transaction()
        self.run_transaction(action='rollback')
        after=p.stat()
        self.assertEqual((before.st_mode & 0o777, before.st_uid, before.st_gid),
                         (after.st_mode & 0o777, after.st_uid, after.st_gid))

class PublishEntryTest(unittest.TestCase):
    def test_already_published_https_failure_makes_no_rollback_write(self):
        with patch.object(publisher,'remote',return_value={'status':'already_published'}) as ssh, \
             patch.object(publisher,'verify_https',side_effect=RuntimeError('network failure')), \
             contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError,'made no changes'):
                publisher.publish_and_verify({'head':'a'*40,'action':'publish','files':[]})
            self.assertEqual(ssh.call_count,1)

    def test_origin_main_advance_rejects_before_ssh(self):
        def git(*args):
            if args == ('rev-parse','origin/main'): return b'b'*40
            if args[0]=='status': return b''
            return b'a'*40
        with patch.object(publisher,'git',side_effect=git),patch.object(publisher.subprocess,'run'), \
             patch.object(publisher,'remote') as ssh:
            with self.assertRaisesRegex(RuntimeError,'clean exact'):
                publisher.main(['--base','a'*40,'--expected-head','a'*40,'--confirm-prod','YES-PROD'])
            ssh.assert_not_called()

    def test_expected_head_mismatch_rejects_before_ssh(self):
        with patch.object(publisher, 'git', return_value=b'b'*40), patch.object(publisher, 'remote') as ssh:
            with self.assertRaisesRegex(RuntimeError, 'expected-head'):
                publisher.main(['--base','a'*40,'--expected-head','a'*40,'--confirm-prod','YES-PROD'])
            ssh.assert_not_called()

    def test_missing_confirmation_defaults_to_readonly(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'platform').mkdir();(root/'platform/index.html').write_bytes(b'new')
            with patch.object(publisher,'REPO',root),patch.object(publisher,'FILES',['index.html']), \
                 patch.object(publisher,'git',return_value=b'a'*40), \
                 patch.object(publisher.subprocess,'run'), \
                 patch.object(publisher.subprocess,'check_output',return_value=b'old'), \
                 patch.object(publisher,'remote',return_value={'status':'baseline_verified'}) as ssh, \
                 contextlib.redirect_stdout(io.StringIO()):
                publisher.main(['--base','a'*40,'--expected-head','a'*40])
                self.assertEqual(ssh.call_args[0][0]['action'],'check')

    def test_dirty_tree_rejects_before_ssh(self):
        def git(*args):
            return b'dirty' if args[0]=='status' else b'a'*40
        with patch.object(publisher,'git',side_effect=git),patch.object(publisher.subprocess,'run'), \
             patch.object(publisher,'remote') as ssh:
            with self.assertRaisesRegex(RuntimeError,'clean exact'):
                publisher.main(['--base','a'*40,'--expected-head','a'*40,'--confirm-prod','YES-PROD'])
            ssh.assert_not_called()

    def test_https_failure_calls_guarded_rollback(self):
        with patch.object(publisher,'remote',side_effect=[{'status':'published'},{'status':'rolled_back'}]) as ssh, \
             patch.object(publisher,'verify_https',side_effect=RuntimeError('network failure')), \
             contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError,'prior website restored'):
                publisher.publish_and_verify({'head':'a'*40,'action':'publish','files':[]})
            self.assertEqual(ssh.call_args_list[1][0][0]['action'],'rollback')

if __name__ == '__main__':
    unittest.main()
