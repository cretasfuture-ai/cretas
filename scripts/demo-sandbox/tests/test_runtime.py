import json, os, tempfile, unittest, threading, time, secrets
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).parents[1]))
from runtime import Manager, SandboxError, Handler

class StubSlot:
    def __init__(self): self.calls=[]; self.raise_prepare=False; self.raise_reset=False
    def assert_clean(self): self.calls.append('clean')
    def prepare(self):
        self.calls.append('prepare')
        if self.raise_prepare: raise RuntimeError('prepare failed')
    def reset(self):
        self.calls.append('reset')
        if self.raise_reset: raise RuntimeError('reset failed')

class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.TemporaryDirectory(); root=Path(self.d.name)
        (root/'manifest.json').write_text('{}', encoding='utf-8')
        self.cfg={"listen_host":"127.0.0.1","listen_port":10041,"control_key":secrets.token_urlsafe(32),"strict_security":True,"allowed_actor_id":1309,"sandbox_base_url":"http://127.0.0.1:10040","java_base_url":"http://127.0.0.1:10040","sandbox_db_name":"f006_demo_sandbox","pg_access_mode":"DOCKER_EXEC_ONLY","sandbox_redis_port":6387,"sandbox_file_root":str(root/'data'),"sandbox_bearer_token":secrets.token_urlsafe(48),"manifest_path":str(root/'manifest.json')}
        self.slot=StubSlot(); self.m=Manager(self.cfg,slot=self.slot)
    def tearDown(self): self.m.close(); self.d.cleanup()
    def test_lifecycle_and_owner(self):
        self.m._ready_auth_check=lambda: None
        s=self.m.create(1309); self.assertEqual(s['status'],'PREPARING');
        deadline=time.time()+2
        while self.m.sessions[s['sessionId']]['status']=='PREPARING' and time.time()<deadline: time.sleep(.01)
        self.assertEqual(self.m.sessions[s['sessionId']]['status'],'READY'); self.assertEqual(self.m.create(1309)['sessionId'],s['sessionId'])
        with self.assertRaises(SandboxError): self.m.get(s['sessionId'],8)
        self.assertEqual(self.m.end(s['sessionId'],1309)['status'],'ENDING')
        deadline=time.time()+2
        while self.m.get(s['sessionId'],1309)['status'] not in {'ENDED','FAILED'} and time.time()<deadline: time.sleep(.01)
        self.assertEqual(self.m.get(s['sessionId'],1309)['status'],'ENDED')
    def test_failed_is_not_reused(self):
        self.slot.raise_prepare=True; self.m=Manager(self.cfg,slot=self.slot)
        first=self.m.create(1309); deadline=time.time()+2
        while self.m.sessions[first['sessionId']]['status']=='PREPARING' and time.time()<deadline: time.sleep(.01)
        with self.assertRaises(SandboxError): self.m.create(1309)
        with self.assertRaises(SandboxError): self.m.create(1309)
    def test_reject_production(self):
        self.cfg['sandbox_db_name']='cretas_prod_db'
        with self.assertRaises(SandboxError): Manager(self.cfg).create(1309)
    def test_manifest_is_json(self):
        self.m._ready_auth_check=lambda: None
        s=self.m.create(1309); deadline=time.time()+2
        while self.m.sessions[s['sessionId']]['status']=='PREPARING' and time.time()<deadline: time.sleep(.01)
        raw=json.loads(Path(self.cfg['manifest_path']).read_text()); self.assertEqual(raw[s['sessionId']]['actor'],1309)

    def test_real_http_lifecycle_proxy_attachment_and_path_guard(self):
        seen=[]
        class Upstream(BaseHTTPRequestHandler):
            def do_GET(self): self.reply()
            def do_POST(self): self.reply()
            def do_PUT(self): self.reply()
            def do_PATCH(self): self.reply()
            def do_DELETE(self): self.reply()
            def reply(self):
                seen.append((self.command, self.path, self.headers.get('Authorization'), self.headers.get('Cookie')))
                self.send_response(200); self.send_header('Content-Type','application/json'); self.end_headers(); self.wfile.write(b'{"success":true}')
            def log_message(self,*args): pass
        upstream=ThreadingHTTPServer(('127.0.0.1',0),Upstream); threading.Thread(target=upstream.serve_forever,daemon=True).start()
        self.m.cfg['java_base_url']=f'http://127.0.0.1:{upstream.server_port}'; self.m._ready_auth_check=lambda: None; self.m._validate_config=lambda: None
        Handler.manager=self.m; server=ThreadingHTTPServer(('127.0.0.1',0),Handler); threading.Thread(target=server.serve_forever,daemon=True).start()
        base=f'http://127.0.0.1:{server.server_port}'; h={'X-Sandbox-Control-Key':self.cfg['control_key'],'X-Sandbox-Actor-Id':'1309'}
        def call(method,path,data=None,headers=None):
            hh=dict(h); hh.update(headers or {}); req=Request(base+path,data=data,headers=hh,method=method); return urlopen(req,timeout=3)
        session=json.loads(call('POST','/sessions').read())['data']; sid=session['sessionId']; deadline=time.time()+2
        while self.m.sessions[sid]['status']=='PREPARING' and time.time()<deadline: time.sleep(.01)
        self.assertEqual(self.m.sessions[sid]['status'],'READY')
        for method in ('GET','POST','PUT','PATCH','DELETE'):
            response=call(method,f'/sessions/{sid}/proxy/api/mobile/F006/demo-{method.lower()}',b'{}',{'Authorization':'prod','Cookie':'secret','Content-Type':'application/json'})
            self.assertEqual(response.status,200)
        self.assertTrue(seen); self.assertTrue(all(row[2]=='Bearer '+self.cfg['sandbox_bearer_token'] and row[3] is None for row in seen))
        with self.assertRaises(Exception): call('GET',f'/sessions/{sid}/proxy/api/mobile/F006/%2e%2e/secret')
        upload=json.loads(call('POST',f'/sessions/{sid}/proxy/api/mobile/F006/attachments/upload-url',b'{}').read())
        upload=upload['data']; local=upload['uploadUrl']; self.assertTrue(local.startswith('/F006/demo-sandbox-local-files/'))
        file_response=call('PUT',f'/sessions/{sid}/proxy/api/mobile{local}',b'png',{'Content-Type':'image/png'}); self.assertEqual(file_response.status,200)
        file_id=upload['fileId']; file_path=self.m.files[file_id][1]; self.assertTrue(file_path.exists())
        end=json.loads(call('DELETE',f'/sessions/{sid}').read())['data']; self.assertEqual(end['status'],'ENDING')
        for _ in range(200):
            if self.m.sessions[sid]['status']=='ENDED': break
            time.sleep(.01)
        self.assertEqual(self.m.sessions[sid]['status'],'ENDED'); self.assertFalse(file_path.exists())
        server.shutdown(); upstream.shutdown()
        server.server_close(); upstream.server_close()

    def test_http_actor_and_control_are_required(self):
        Handler.manager=self.m; server=ThreadingHTTPServer(('127.0.0.1',0),Handler); threading.Thread(target=server.serve_forever,daemon=True).start()
        req=Request(f'http://127.0.0.1:{server.server_port}/sessions',method='POST')
        with self.assertRaises(Exception): urlopen(req,timeout=2)
        server.shutdown(); server.server_close()

    def test_different_actor_create_rejected(self):
        with self.assertRaises(SandboxError): self.m.create(1310)

    def test_reset_failure_blocks_new_session(self):
        self.m._ready_auth_check=lambda: None
        s=self.m.create(1309); deadline=time.time()+2
        while self.m.sessions[s['sessionId']]['status']=='PREPARING' and time.time()<deadline: time.sleep(.01)
        self.slot.raise_reset=True; self.m.end(s['sessionId'],1309); self.m.close()
        self.assertEqual(self.m.sessions[s['sessionId']]['status'],'FAILED')
        with self.assertRaises(SandboxError): self.m.create(1309)

    def test_empty_or_corrupt_manifest_rejected(self):
        self.m.close(); Path(self.cfg['manifest_path']).write_text('', encoding='utf-8')
        with self.assertRaises(SandboxError): Manager(self.cfg)

    def test_delete_waits_for_inflight_proxy(self):
        entered=threading.Event(); release=threading.Event()
        class Slow(BaseHTTPRequestHandler):
            def do_GET(self):
                entered.set(); release.wait(2); self.send_response(200); self.end_headers(); self.wfile.write(b'ok')
            def log_message(self,*args): pass
        upstream=ThreadingHTTPServer(('127.0.0.1',0),Slow); threading.Thread(target=upstream.serve_forever,daemon=True).start()
        self.m.cfg['java_base_url']=f'http://127.0.0.1:{upstream.server_port}'; self.m._ready_auth_check=lambda: None; self.m._validate_config=lambda: None; Handler.manager=self.m
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler); threading.Thread(target=server.serve_forever,daemon=True).start()
        base=f'http://127.0.0.1:{server.server_port}'; h={'X-Sandbox-Control-Key':self.cfg['control_key'],'X-Sandbox-Actor-Id':'1309'}; sid=json.loads(urlopen(Request(base+'/sessions',headers=h,method='POST')).read())['data']['sessionId']; deadline=time.time()+2
        while self.m.sessions[sid]['status']=='PREPARING' and time.time()<deadline: time.sleep(.01)
        worker=threading.Thread(target=lambda: urlopen(Request(base+f'/sessions/{sid}/proxy/api/mobile/F006/slow',headers=h,method='GET')).read()); worker.start(); self.assertTrue(entered.wait(1))
        ending=json.loads(urlopen(Request(base+f'/sessions/{sid}',headers=h,method='DELETE')).read())['data']; self.assertEqual(ending['status'],'ENDING'); self.assertEqual(self.m.sessions[sid]['status'],'ENDING')
        release.set(); worker.join(2)
        for _ in range(200):
            if self.m.sessions[sid]['status']=='ENDED': break
            time.sleep(.01)
        self.assertEqual(self.m.sessions[sid]['status'],'ENDED'); server.shutdown(); server.server_close(); upstream.shutdown(); upstream.server_close()

if __name__ == '__main__': unittest.main()
