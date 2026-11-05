import json, unittest, tempfile, secrets, uuid
from pathlib import Path
import sys
from unittest.mock import Mock, patch
sys.path.insert(0, str(Path(__file__).parents[1]))
from orchestrator import SandboxOrchestrator, Docker, CONTAINERS, VOLUMES, NETWORK, LABELS, OrchestratorError

class P:
    def __init__(self, rc=0, out=''): self.returncode=rc; self.stdout=out; self.stderr=''
class Recorder:
    def __init__(self): self.calls=[]
    def __call__(self,args,**kw):
        self.calls.append(args)
        if args[1]=='inspect':
            role=next((r for r,n in CONTAINERS.items() if n==args[-1]),'')
            labels=dict(LABELS); labels['cretas.sandbox.role']=role
            return P(0,json.dumps(labels))
        return P()

class OrchestratorTests(unittest.TestCase):
    def test_ready_wait_rejects_initdb_temporary_server_and_waits_for_final_postgres(self):
        slot = object.__new__(SandboxOrchestrator)
        slot.docker = Mock()
        slot.docker.run.side_effect = [P(0, 'docker-entrypoint.sh\n'), P(0, 'postgres\n'), P(1), P(0, 'postgres\n'), P(0)]
        with patch('orchestrator.time.sleep'):
            slot._wait_pg()
        commands = [call.args[0][3:] for call in slot.docker.run.call_args_list]
        self.assertEqual(commands, [
            ['cat', '/proc/1/comm'], ['cat', '/proc/1/comm'],
            ['pg_isready', '-U', 'cretas_sandbox', '-d', 'f006_demo_sandbox'],
            ['cat', '/proc/1/comm'],
            ['pg_isready', '-U', 'cretas_sandbox', '-d', 'f006_demo_sandbox'],
        ])
    def cfg(self):
        self.tmp=getattr(self,'tmp',tempfile.TemporaryDirectory()); root=Path(self.tmp.name)
        for n in ('schema.private.sql','rows.private.json','catalog.private.json'): (root/n).write_text('{}',encoding='utf-8')
        (root/'manifest.json').write_text(json.dumps({'tenant':'F006','counts':{},'sha256':{n:'x' for n in ('schema.private.sql','rows.private.json','catalog.private.json')}}),encoding='utf-8')
        jar=root/'app.jar'; props=root/'application.properties'; jar.write_bytes(b'jar'); props.write_text('x',encoding='utf-8')
        return {'db_name':'f006_demo_sandbox','network':NETWORK,'containers':CONTAINERS,'volumes':VOLUMES,'identity_path':str(root/'identity.json'),'baseline_dir':str(root),'java_jar':str(jar),'java_properties':str(props),'baseline_proof_path':str(root/'proof.json'),'instance_id':str(uuid.uuid4()),'pg_user':'cretas_sandbox','pg_password':secrets.token_urlsafe(32)}
    def test_fixed_argv_and_no_compose(self):
        rec=Recorder(); o=SandboxOrchestrator(self.cfg(),Docker(rec)); o._inspect=lambda role: {'State':{'Status':'running','Running':True}}; o._inspect_running=lambda role: False; o._stop_java();
        self.assertIn(['docker','stop','--time','30',CONTAINERS['java']],rec.calls)
        self.assertTrue(all('compose' not in c and 'down' not in c and '-v' not in c for c in rec.calls))
    def test_prod_db_and_topology_refused(self):
        c=self.cfg(); c['db_name']='cretas_prod_db'
        with self.assertRaises(OrchestratorError): SandboxOrchestrator(c)

    def test_restore_sql_transaction_generated_and_composite_fk(self):
        o=SandboxOrchestrator(self.cfg(),Docker(Recorder()))
        meta={'tenant':'F006','counts':{'parent':1,'child':1}}
        rows={'parent':[{'id':1}], 'child':[{'a':1,'b':2,'detail':'x'}]}
        catalog={'tables':{'parent':[{'column_name':'id','is_generated':'NEVER','column_default':'nextval(\'parent_id_seq\')'}], 'child':[{'column_name':'a','is_generated':'NEVER'},{'column_name':'b','is_generated':'NEVER'},{'column_name':'detail','is_generated':'ALWAYS'}]},'foreign':[{'child':'child','parent':'parent','child_cols':['a','b'],'parent_cols':['id','id']}]}
        sql=o._restore_sql(meta,rows,catalog,'CREATE SCHEMA public;')
        self.assertTrue(sql.startswith('BEGIN;')); self.assertTrue(sql.rstrip().endswith('COMMIT;'))
        self.assertIn('c."a" IS NOT NULL AND c."b" IS NOT NULL',sql); self.assertNotIn('("a","b","detail")',sql)

    def test_docker_dangerous_commands_refused(self):
        d=Docker(lambda *a,**k: None)
        for argv in (['docker','compose'],['docker','down'],['docker','volume','rm','x'],['docker','rm','-v','x']):
            with self.assertRaises(OrchestratorError): d.run(argv)
        c=self.cfg(); c['containers']=dict(CONTAINERS,java='other')
        with self.assertRaises(OrchestratorError): SandboxOrchestrator(c)

if __name__=='__main__': unittest.main()
