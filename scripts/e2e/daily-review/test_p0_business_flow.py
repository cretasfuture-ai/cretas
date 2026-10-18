"""Guards for the write-capable local business acceptance harness."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('p0_business_flow', Path(__file__).with_name('p0_business_flow.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
BusinessFlow = module.BusinessFlow


class BusinessFlowGuardTest(unittest.TestCase):
    def test_every_non_owned_origin_is_refused_before_io(self):
        with patch.object(module.requests, 'Session') as session:
            for url in ['https://admin.cretaceousfuture.com', 'http://47.100.235.168:10010',
                        'http://localhost:10060', 'http://127.0.0.1:10010',
                        'http://someone@127.0.0.1:10060', 'http://127.0.0.1:10060?x=1',
                        'http://127.0.0.1:10060/#foreign']:
                with self.subTest(url=url), self.assertRaises(ValueError):
                    BusinessFlow(url, Path('absent'), Path('must-not-exist'))
            session.assert_not_called()

    def test_explicit_factory_identity_is_required_and_tokens_are_not_saved(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            credentials = root / 'accounts.private.json'
            credentials.write_text(json.dumps({'password': 'test-only-secret'}), encoding='utf-8')
            session = Mock()
            response = session.post.return_value
            actor = {'userId': 93001, 'username': 'daily_factory_a', 'factoryId': 'VISION-ENTRY-A',
                     'role': 'factory_super_admin', 'accessToken': 'test-only-token'}
            response.json.return_value = {'success': True, 'data': actor}
            session.headers = {}
            with patch.object(module.requests, 'Session', return_value=session):
                flow = BusinessFlow('http://127.0.0.1:10060', credentials, root / 'owned')
            saved = flow.state_path.read_text(encoding='utf-8')
            self.assertNotIn('test-only-secret', saved)
            self.assertNotIn('test-only-token', saved)
            self.assertEqual(flow.actor_id, 93001)
            actor['factoryId'] = 'VISION-ENTRY-B'
            with patch.object(module.requests, 'Session', return_value=session), self.assertRaises(ValueError):
                BusinessFlow('http://127.0.0.1:10060', credentials, root / 'foreign')
            session.request.assert_not_called()

    def test_paths_cannot_escape_factory_and_completed_creation_is_not_replayed(self):
        flow = BusinessFlow.__new__(BusinessFlow)
        flow.http = Mock()
        flow.state = {'entities': {'done': {'id': 'existing'}}}
        for path in ['https://foreign.invalid', '/../../auth', 'no-root']:
            with self.assertRaises(ValueError):
                flow.call('POST', path, {})
        self.assertEqual(flow.create('done', '/purchase/orders', {}), {'id': 'existing'})
        flow.http.request.assert_not_called()


if __name__ == '__main__':
    unittest.main()
