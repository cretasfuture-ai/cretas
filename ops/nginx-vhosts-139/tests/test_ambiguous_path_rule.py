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
        self.assertEqual(snippet.strip(), 'if ($cretas_ambiguous_path) { return 400; }')

    def test_each_mirrored_vhost_includes_it_right_after_its_serving_server_name(self):
        for name in MIRRORED_VHOSTS:
            with self.subTest(vhost=name):
                lines = (ROOT / name).read_text(encoding='utf-8').splitlines()
                at = [i for i, line in enumerate(lines) if line.strip() == INCLUDE]
                self.assertEqual(len(at), 1)
                self.assertTrue(lines[at[0] - 1].strip().startswith('server_name'))


if __name__ == '__main__':
    unittest.main()
