import json, unittest, threading, http.client
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).parents[1]))
from local_relay import Relay, RelayError, Handler, ThreadingHTTPServer, MAX_BODY
class Resp:
    def __init__(self,status,data,headers=None): self.status=status; self._d=data; self.headers=headers or {'Content-Type':'application/json'}
    def read(self,n=-1): return self._d
    def __enter__(self): return self
    def __exit__(self,*a): pass
class Opener:
    def __init__(self): self.calls=[]
    def open(self,req,timeout=20):
        self.calls.append(req)
        if req.full_url.endswith('/auth/me'):
            return Resp(200,json.dumps({'success':True,'data':{'id':1309,'factoryId':'F006','username':'f006_admin','roleCode':'factory_super_admin'}}).encode())
        if req.full_url.endswith('/auth/unified-login'): return Resp(200,b'{"success":true,"data":{"token":"local-test-token"}}')
        return Resp(200,b'{"success":true,"data":null}')
class RelayTests(unittest.TestCase):
    def setUp(self): self.o=Opener(); self.r=Relay({'manager_base_url':'http://127.0.0.1:10041','control_key':'x'*40},self.o); self.h={'Authorization':'Bearer private'}
    def test_prod_mutation_denied_and_token_not_forwarded(self):
        with self.assertRaises(RelayError): self.r.request('/api/mobile/F006/orders', 'POST', self.h,b'{}')
        self.r.request('/api/mobile/F006/orders','GET',self.h)
        self.assertEqual(self.o.calls[-2].full_url,'http://127.0.0.1:10041/actors/1309/session')
    def test_manager_headers_only_and_cross_actor_rejected(self):
        self.r.request('/api/mobile/F006/demo-sandbox/active-session','GET',self.h)
        req=self.o.calls[-1]; self.assertEqual(req.get_header('X-sandbox-control-key'),'x'*40); self.assertEqual(req.get_header('X-sandbox-actor-id'),'1309'); self.assertIsNone(req.get_header('Authorization'))
        self.assertEqual(req.full_url,'http://127.0.0.1:10041/actors/1309/session')
        with self.assertRaises(RelayError): self.r.request('/api/mobile/F006/demo-sandbox/active-session','GET',{**self.h,'X-Sandbox-Actor-Id':'7'})
    def test_wrong_auth_and_route_fail_closed(self):
        self.o.open=lambda req,timeout=20: Resp(200,json.dumps({'data':{'id':31309,'factoryId':'F006','username':'f006_admin'}}).encode())
        with self.assertRaises(RelayError): self.r.request('/api/mobile/F006/x','GET',self.h)
        with self.assertRaises(RelayError): self.r.request('/api/mobile/other','GET',self.h)
    def test_login_before_auth_and_returned_token_verified(self):
        self.r.request('/api/mobile/auth/unified-login','POST',{},b'{"username":"f006_admin","password":"test-only","clientType":"web"}')
        self.assertTrue(self.o.calls[0].full_url.endswith('/unified-login'))
        self.assertEqual(self.o.calls[1].get_header('Authorization'),'Bearer local-test-token')
    def test_active_or_unverifiable_manager_blocks_prod_reads(self):
        original=self.o.open
        def active(req,timeout=20):
            if req.full_url.startswith('http://127.0.0.1:10041'): return Resp(200,b'{"success":true,"data":{"status":"READY"}}')
            return original(req,timeout)
        self.o.open=active
        with self.assertRaises(RelayError): self.r.request('/api/mobile/F006/orders','GET',self.h)
        self.assertFalse(any(r.full_url.endswith('/F006/orders') for r in self.o.calls))
        self.o.open=lambda req,timeout=20: original(req,timeout) if '/auth/me' in req.full_url else Resp(200,b'{}')
        with self.assertRaises(RelayError): self.r.request('/api/mobile/F006/orders','GET',self.h)
    def test_session_proxy_retains_query_content_type_and_drops_auth(self):
        sid='12345678-1234-1234-1234-123456789abc'
        self.r.request('/api/mobile/F006/demo-sandbox/sessions/'+sid+'/proxy/api/mobile/F006/orders?page=2','POST',{**self.h,'Content-Type':'image/png'},b'png')
        req=self.o.calls[-1]
        self.assertEqual(req.full_url,'http://127.0.0.1:10041/sessions/'+sid+'/proxy/api/mobile/F006/orders?page=2')
        self.assertEqual(req.get_header('Content-type'),'image/png'); self.assertIsNone(req.get_header('Authorization'))
    def test_control_unknown_uuid_and_traversal_denied(self):
        for path in ['/sessions/garbage','/anything','/sessions/12345678-1234-1234-1234-123456789abc/../x','/active-session?x=y']:
            with self.assertRaises(RelayError): self.r.request('/api/mobile/F006/demo-sandbox'+path,'GET',self.h)
    def test_role_drift_allows_cleanup_but_denies_proxy_create_and_prod(self):
        original=self.o.open
        def revoked(req,timeout=20):
            if req.full_url.endswith('/auth/me'): return Resp(200,b'{"success":true,"data":{"id":1309,"factoryId":"F006","username":"changed","roleCode":"viewer"}}')
            return original(req,timeout)
        self.o.open=revoked;sid='12345678-1234-1234-1234-123456789abc';prefix='/api/mobile/F006/demo-sandbox'
        self.r.request(prefix+'/active-session','GET',self.h)
        for method in ('GET','DELETE'):self.r.request(prefix+'/sessions/'+sid,method,self.h)
        for path,method in [(prefix+'/sessions','POST'),(prefix+'/sessions/'+sid+'/proxy/api/mobile/F006/orders','POST'),('/api/mobile/F006/orders','GET'),('/api/mobile/F006/form-assistant/parse','POST')]:
            with self.assertRaises(RelayError):self.r.request(path,method,self.h,b'{}')
    def test_real_handler_origin_body_and_proxy(self):
        class LocalHandler(Handler): pass
        LocalHandler.relay=self.r
        server=ThreadingHTTPServer(('127.0.0.1',0),LocalHandler)
        worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
        try:
            def call(headers,path='/api/mobile/F006/demo-sandbox/active-session',method='GET'):
                connection=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=3)
                try:
                    connection.request(method,path,headers={**self.h,**headers}); response=connection.getresponse(); data=response.read(); return response.status,data
                finally: connection.close()
            self.assertEqual(call({'Origin':'http://127.0.0.1:5186'})[0],200)
            self.assertEqual(self.o.calls[-1].full_url,'http://127.0.0.1:10041/actors/1309/session')
            self.assertEqual(call({'Origin':'http://localhost.evil:5186'})[0],403)
            self.assertEqual(call({'Content-Length':str(MAX_BODY+1)},method='POST')[0],403)
            self.assertEqual(call({'Transfer-Encoding':'chunked'},method='POST')[0],403)
        finally: server.shutdown();server.server_close();worker.join(2)
    def test_only_own_authorization_reads_survive_preparation(self):
        original=self.o.open
        def active(req,timeout=20):
            if req.full_url.startswith('http://127.0.0.1:10041'):return Resp(200,b'{"success":true,"data":{"status":"PREPARING"}}')
            return original(req,timeout)
        self.o.open=active
        for path in ['/api/mobile/F006/permissions/current','/api/mobile/F006/canvas/user-module-access/1309']:
            self.r.request(path,'GET',self.h)
            self.assertEqual(self.o.calls[-1].full_url,'https://admin.cretaceousfuture.com'+path)
            with self.assertRaises(RelayError):self.r.request(path,'POST',self.h,b'{}')
        with self.assertRaises(RelayError):self.r.request('/api/mobile/F006/canvas/user-module-access/1310','GET',self.h)
if __name__=='__main__': unittest.main()
