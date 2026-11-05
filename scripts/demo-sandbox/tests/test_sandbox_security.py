import json, tempfile, unittest
from pathlib import Path
from unittest.mock import Mock
from orchestrator import SandboxOrchestrator, OrchestratorError, CONTAINERS
from vector_dependency import verify_receipt, SOURCE_DIGEST
from create_private_config import create
from baseline_export import validate_schema_material, ExportError, scrub, schema_without_comments

class SecurityTests(unittest.TestCase):
    def test_unmanaged_database_state_cannot_be_reported_clean(self):
        slot=object.__new__(SandboxOrchestrator)
        for state in ('1|0|0','0|1|0','0|0|1'):
            slot._pg=lambda sql:state
            with self.assertRaises(OrchestratorError):slot._verify_database_shape()
        slot._pg=lambda sql:'0|0|0';slot._verify_database_shape()
    def test_internal_http_uses_stdin_not_argv_and_keeps_payload_exact(self):
        with tempfile.TemporaryDirectory() as folder:
            proof=Path(folder)/'identity.json';proof.write_text(json.dumps({'nodes':{'java':{'id':'registered'}}}))
            slot=object.__new__(SandboxOrchestrator);slot.cfg={'identity_path':str(proof)}
            slot._inspect=lambda role:{'Id':'registered','State':{'Running':True}}
            slot._checked=Mock(return_value=Mock(stdout=b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{"ok":true}'))
            result=slot.request('/api/mobile/F006/orders','POST','{"name":"测试\\nvalue"}'.encode(),{'Authorization':'Bearer private-test-token','Content-Type':'application/json'})
            self.assertEqual(result[0],200);self.assertEqual(result[2],b'{"ok":true}')
            args,kwargs=slot._checked.call_args
            self.assertNotIn('private-test-token',' '.join(args[0]));self.assertIn(b'private-test-token',kwargs['input'])
            self.assertEqual(args[0][-4:],['curl','-q','--config','-'])
            self.assertIn(b'data-binary = "{\\"name\\":',kwargs['input'])
            with self.assertRaises(OrchestratorError):slot.request('/api/mobile/F006/orders\nurl=evil')
            for path in ('/api/mobile/F006/../auth','/api/mobile//F006/orders','/api/mobile/F006/%2e%2e/x','/api/mobile/F006/orders#fragment','https://evil.invalid/api/mobile/F006/orders'):
                with self.assertRaises(OrchestratorError):slot.request(path)
    def test_network_rejects_extra_container_but_accepts_stopped_node_omission(self):
        slot=object.__new__(SandboxOrchestrator);slot.cfg={'instance_id':'fixed'}
        slot._inspect=lambda role:{'Id':role+'-id'}
        net={'Internal':True,'Labels':slot._labels(),'Containers':{'pg-id':{},'redis-id':{}}}
        slot._json=lambda args:net
        self.assertIs(slot._network(),net)
        net['Containers']['sidecar-id']={}
        with self.assertRaises(OrchestratorError):slot._network()
    def test_missing_schema_proof_cannot_enroll_live_schema(self):
        with tempfile.TemporaryDirectory() as folder:
            proof=Path(folder)/'proof.json';proof.write_text(json.dumps({'baseline':{'x':'y'},'tables':{}}))
            slot=object.__new__(SandboxOrchestrator);slot.cfg={'baseline_proof_path':str(proof)}
            slot._verify_identity=lambda:None;slot._inspect_running=lambda role:False
            slot._validated_baseline=lambda:({'sha256':{'x':'y'}},{},{'tables':{}},'')
            slot._pg=lambda sql,**kwargs:'1';slot._schema_hash=lambda:'untrusted-schema'
            with self.assertRaisesRegex(OrchestratorError,'schema proof'):slot._restore()
            self.assertNotIn('schemaHash',json.loads(proof.read_text()))
    def test_private_configuration_cannot_be_generated_in_repository(self):
        with self.assertRaisesRegex(RuntimeError,'outside Git'):
            create(Path(__file__).resolve().parents[1]/'never-create-private',Path('missing.jar'),Path('missing-baseline'),'0'*64)
    def test_vector_version_sql_tamper_and_extra_file_are_rejected(self):
        slot=Mock();slot._checked.return_value.stdout='abc  /extension/vector--0.8.5.sql\ndef  /lib/vector.so\n'
        proof={'sourceDigest':SOURCE_DIGEST,'fileHashes':{'/extension/vector--0.8.5.sql':'abc','/lib/vector.so':'def'}}
        verify_receipt(slot,proof)
        slot._checked.return_value.stdout='changed  /extension/vector--0.8.5.sql\ndef  /lib/vector.so\n'
        with self.assertRaises(OrchestratorError):verify_receipt(slot,proof)
        proof['sourceDigest']='unreviewed'
        with self.assertRaises(OrchestratorError):verify_receipt(slot,proof)
    def test_sensitive_defaults_and_top_level_contacts_are_removed_or_rejected(self):
        with self.assertRaises(ExportError):validate_schema_material('',{'users':[{'column_name':'api_key','column_default':"'live-value'"}]})
        with self.assertRaises(ExportError):validate_schema_material("SELECT 'admin.cretaceousfuture.com';",{})
        self.assertEqual(scrub({'address':'private','legalRepresentative':'private','callbackUrl':'private'}),{'address':None,'legalRepresentative':None,'callbackUrl':None})
        self.assertEqual(schema_without_comments(b"CREATE TABLE x(id int);\nCOMMENT ON TABLE x IS 'private; comment';\n"),b'CREATE TABLE x(id int);\n')
if __name__=='__main__':unittest.main()
