import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INCLUDE = 'include /www/server/nginx/conf/snippets/block-ambiguous-path.conf;'
MIRRORED_VHOSTS = ('admin.cretaceousfuture.com.conf', 'api.cretaceousfuture.com.conf', 'web-admin.conf')


def rule():
    text = (ROOT / '_cretas_ambiguous_path_map.conf').read_text(encoding='utf-8')
    pattern = re.search(r'"~\*(.+)" 1;', text).group(1)
    return re.compile(pattern, re.IGNORECASE)


def word_gate():
    """The $uri map in order, as nginx reads it: the first match wins."""
    text = (ROOT / '_cretas_word_gate_map.conf').read_text(encoding='utf-8')
    block = text[text.index('map $uri $cretas_word_path'):]
    block = block[:block.index('}\n')]
    return [(re.compile(p), int(v)) for p, v in re.findall(r'"~(\^[^"]+)" (\d);', block)]


def word_class(path):
    return next((value for pattern, value in word_gate() if pattern.search(path)), 0)


class WordGateTest(unittest.TestCase):
    def test_anonymous_routes_stay_open(self):
        for path in ('/api/mobile/health', '/api/mobile/auth/unified-login', '/api/mobile/auth/refresh',
                     '/api/mobile/public/share/abc', '/api/mobile/activation/activate',
                     '/api/mobile/system/release-notes', '/api/mobile/voice/recognize',
                     '/api/mobile/workflow/node-schemas', '/api/mobile/workflow/node-schemas/by-category/x',
                     '/api/mobile/scale-protocols/brands', '/api/mobile/F001/orders', '/api/public/trace/x'):
            with self.subTest(path=path):
                self.assertEqual(word_class(path), 0)

    def test_a_word_in_the_factory_position_needs_a_credential(self):
        for path in ('/api/mobile/dashboard/F002', '/api/mobile/system/shipments/1', '/api/mobile/voice/shipments/1',
                     '/api/mobile/auth/shipments/1', '/api/mobile/admin/x', '/api/mobile/smartbi-config/thresholds',
                     '/api/mobile/scheduled-tasks', '/api/mobile/edge/upload'):
            with self.subTest(path=path):
                self.assertEqual(word_class(path), 1)

    def test_public_is_closed_except_share(self):
        self.assertEqual(word_class('/api/mobile/public/shipments/1'), 2)
        self.assertEqual(word_class('/api/mobile/public/share/a/b'), 2)

    def test_password_recovery_is_closed_until_tokens_carry_a_purpose(self):
        for name in ('forgot-password', 'send-verification-code', 'verify-reset-code'):
            with self.subTest(name=name):
                self.assertEqual(word_class('/api/mobile/auth/' + name), 2)

    def test_the_gate_reads_web_cookies_as_well_as_the_bearer_header(self):
        text = (ROOT / '_cretas_word_gate_map.conf').read_text(encoding='utf-8')
        self.assertIn('$cookie_cretas_access_token', text)
        self.assertIn('$cookie_cretas_web_session', text)


class AmbiguousPathRuleTest(unittest.TestCase):
    def test_refuses_semicolons_and_needless_escapes_in_the_path(self):
        for uri in ('/api;x=1/mobile/F001/x', '/api/mobile;x=1/F001/x', '/api/%70latform/blueprints',
                    '/api/%6Dobile/F001/x', '/%61pi/internal/x', '/api/%2e%2e/x', '/x%2Fy', '/x%5Cy',
                    '/x%5fy', '/x%2dy', '/x%30y', '/x%7Ey', '/x%20/%70latform'):
            with self.subTest(uri=uri):
                self.assertTrue(rule().search(uri))

    def test_passes_real_paths_values_and_any_query_string(self):
        for uri in ('/api/mobile/F001/orders', '/api/mobile/F001/x/a%20b', '/x/%E4%B8%AD', '/x/a%25b', '/x/a%2Bb',
                    '/x/a%3Bb', '/x/a%40b', '/api/mobile/F001/x?q=%70;y', '/api/mobile/F001/x?a=1;b=2'):
            with self.subTest(uri=uri):
                self.assertIsNone(rule().search(uri))

    def test_snippet_refuses_on_the_map_variable(self):
        snippet = (ROOT / 'block-ambiguous-path.conf').read_text(encoding='utf-8')
        lines = snippet.strip().splitlines()
        self.assertEqual(lines[0], 'if ($cretas_ambiguous_path) { return 400; }')
        self.assertTrue(lines[1].startswith('if ($cretas_word_denied) { return 401 '))

    def test_each_mirrored_vhost_includes_it_right_after_its_serving_server_name(self):
        for name in MIRRORED_VHOSTS:
            with self.subTest(vhost=name):
                lines = (ROOT / name).read_text(encoding='utf-8').splitlines()
                at = [i for i, line in enumerate(lines) if line.strip() == INCLUDE]
                self.assertEqual(len(at), 1)
                self.assertTrue(lines[at[0] - 1].strip().startswith('server_name'))


if __name__ == '__main__':
    unittest.main()
