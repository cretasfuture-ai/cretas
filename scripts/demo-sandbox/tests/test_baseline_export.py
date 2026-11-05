import copy
import sys
import unittest
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
from baseline_export import ExportError, ident, project, scrub

def column(table, name, nullable='NO'):
    return {'table_name': table, 'column_name': name, 'is_nullable': nullable,
            'is_generated': 'NEVER', 'column_default': None, 'data_type': 'text'}

class FixtureSnapshot:
    def __init__(self):
        self.columns = [column(t, c, n) for t, cols in {
            'factories': [('id', 'NO')], 'users': [('id','NO'), ('factory_id','NO'), ('password_hash','NO')],
            'product_types': [('id','NO'), ('code','NO'), ('factory_id','NO'), ('customer_id','YES')],
            'product_packaging_specs': [('id','NO'), ('product_type_id','NO')],
            'customers': [('id','NO'), ('code','NO'), ('factory_id','NO')],
            'web_login_sessions': [('id','NO'), ('factory_id','NO'), ('token','NO')],
        }.items() for c, n in cols]
        self.foreign = [
            {'child':'product_packaging_specs','parent':'product_types','child_cols':['product_type_id'],'parent_cols':['id']},
            {'child':'product_types','parent':'customers','child_cols':['customer_id'],'parent_cols':['id']},
        ]
        self.result = [
            {'table':'factories','rows':[{'id':'F006'}]},
            {'table':'users','rows':[{'id':1309,'factory_id':'F006'}]},
            {'table':'product_types','rows':[{'id':'so1','code':'DEMO-20261105-SKU0','factory_id':'F006','customer_id':'unavailable'}]},
            {'table':'product_packaging_specs','rows':[{'id':'si1','product_type_id':'so1'}]},
            {'table':'customers','rows':[]},
        ]
        self.queries = 0; self.script = ''; self.preamble = ''
    def query(self, query):
        self.queries += 1
        return self.columns if self.queries % 2 == 1 else self.foreign
    def run(self, script, preamble=''):
        self.script = script; self.preamble = preamble
        return copy.deepcopy(self.result)

class BaselineProjectionTests(unittest.TestCase):
    def setUp(self):
        self.schema_mock = patch('baseline_export.source_schema', return_value=b'-- reviewed schema')
        self.schema_mock.start(); self.addCleanup(self.schema_mock.stop)
    def test_source_selection_writes_only_session_temp_tables_and_excludes_sessions(self):
        source = FixtureSnapshot(); _, _, data = project(source)
        self.assertNotIn('web_login_sessions', data)
        self.assertNotIn('web_login_sessions', source.script)
        self.assertIn('CREATE TEMP TABLE', source.preamble)
        self.assertNotIn('CREATE TEMP TABLE', source.script)
        self.assertNotRegex(source.script, r'(?i)(INSERT\s+INTO|UPDATE|DELETE\s+FROM)\s+public\.')
        self.assertIn('t."factory_id"::text=\'F006\'', source.script)
        self.assertIn('pg_temp.f006_export_scrub(to_jsonb(t)', source.script)
        self.assertIn(" - 'password_hash'", source.script)
        self.assertEqual(data['users'][0]['password_hash'], 'SANDBOX_DISABLED')
        self.assertIsNone(data['product_types'][0]['customer_id'])
        self.assertEqual(data['product_packaging_specs'][0]['product_type_id'], 'so1')
    def test_cross_tenant_data_is_rejected_before_artifact_write(self):
        source = FixtureSnapshot(); source.result[1]['rows'][0]['factory_id'] = 'F007'
        with self.assertRaisesRegex(ExportError, 'cross-tenant'): project(source)
    def test_required_missing_parent_is_not_fabricated(self):
        source = FixtureSnapshot()
        source.result[3]['rows'][0]['product_type_id'] = 'missing'
        with self.assertRaisesRegex(ExportError, 'required dependency'): project(source)
    def test_secret_fields_are_recursively_removed_and_identifiers_validated(self):
        value = scrub({'config': {'apiKey': 'never-copy', 'nested': [{'access_token': 'never-copy'}]}, 'email': 'private@example.invalid'})
        self.assertIsNone(value['config']['apiKey'])
        self.assertIsNone(value['config']['nested'][0]['access_token'])
        self.assertIsNone(value['email'])
        with self.assertRaises(ExportError): ident('users; DROP SCHEMA public')

if __name__ == '__main__': unittest.main()
