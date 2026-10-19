import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

runtime_path=Path(__file__).with_name('f006_aurora_runtime.py')
if not runtime_path.exists():runtime_path=Path(__file__).parents[1]/'f006_aurora_runtime.py'
spec=importlib.util.spec_from_file_location('runtime',runtime_path)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class ResourceAdmission(unittest.TestCase):
    def setUp(self):self.good=dict(pressure=1,memory_free_percent=65,swap_mib=100,free_gib=130)
    def test_admission_and_active_build(self):
        self.assertTrue(m.admitted(self.good));self.assertFalse(m.admitted(self.good,True))
        for key,value in [('pressure',2),('pressure',4),('memory_free_percent',59),('free_gib',109)]:
            self.assertFalse(m.admitted(dict(self.good,**{key:value})))
    def test_protection_bounds(self):
        self.assertTrue(m.protected(self.good,self.good))
        for key,value in [('pressure',2),('pressure',4),('memory_free_percent',14),('free_gib',104),('swap_mib',165)]:
            self.assertFalse(m.protected(dict(self.good,**{key:value}),self.good))
    def test_identity_rejects_other_user_host_or_home(self):
        for host,user,home,ok in [('Auroras-Mac-mini.local','aurora','/Users/aurora',True),('Mac-mini-01.local','rex','/Users/rex',False),('Auroras-Mac-mini.local','rex','/Users/aurora',False),('Auroras-Mac-mini.local','aurora','/tmp',False)]:
            with mock.patch.object(m.subprocess,'check_output',side_effect=[host,user]),mock.patch.object(m.Path,'home',return_value=Path(home)):
                if ok:m.identity()
                else:
                    with self.assertRaises(RuntimeError):m.identity()

class ActiveBuildGate(unittest.TestCase):
    def test_native_or_ci_busy_and_guest_errors_refuse(self):
        for statuses,expected in [([0],True),([1,0],True),([1,1],False),([1,255],True)]:
            with mock.patch.object(m.subprocess,'run',side_effect=[mock.Mock(returncode=x) for x in statuses]):
                self.assertEqual(m.active_build(),expected)
    def test_timeout_is_not_idle(self):
        with mock.patch.object(m.subprocess,'run',side_effect=m.subprocess.TimeoutExpired('guest',3)):
            with self.assertRaises(m.subprocess.TimeoutExpired):m.active_build()

class RequestContract(unittest.TestCase):
    def payload(self,think=False):return dict(model=m.MODEL,messages=[dict(role='system',content=m.SYSTEM),dict(role='user',content='如何检查冷链？')],stream=True,think=think,options=dict(temperature=.4,num_predict=768 if think else 384))
    def test_original_caller_shape_gets_bounded_profile(self):
        for thinking in (False,True):
            d=m.validate_request(json.dumps(self.payload(thinking)).encode());self.assertEqual(d['keep_alive'],0);self.assertEqual(d['options']['num_ctx'],10240);self.assertEqual(d['options']['num_thread'],4)
    def test_cannot_select_egress_model_tools_or_parameters(self):
        for key,value in [('url','https://outside.invalid'),('model','qwen3:8b'),('tools',[]),('images',[]),('keep_alive',-1),('stream',False),('think','false')]:
            d=self.payload();d[key]=value
            with self.assertRaises(ValueError):m.validate_request(json.dumps(d).encode())
    def test_length_boundaries(self):
        for size,ok in [(0,False),(2000,True),(2001,False)]:
            d=self.payload();d['messages'][1]['content']='𐍈'*size
            if ok:m.validate_request(json.dumps(d).encode())
            else:
                with self.assertRaises(ValueError):m.validate_request(json.dumps(d).encode())
    def test_options_and_structure_rejected(self):
        variants=[None,{},[],dict(num_predict=384,temperature=.4,num_ctx=262144),dict(num_predict=769,temperature=.4),dict(num_predict=384,temperature=.5)]
        for options in variants:
            d=self.payload();d['options']=options
            with self.assertRaises(ValueError):m.validate_request(json.dumps(d).encode())
        for raw in [b'not json',b'x'*32769,b'[]',b'null']:
            with self.assertRaises((ValueError,TypeError)):m.validate_request(raw)
    def test_caller_payload_not_mutated_before_parse(self):
        d=self.payload();m.validate_request(json.dumps(d));self.assertNotIn('keep_alive',d)

class OwnershipAndReports(unittest.TestCase):
    def runtime(self,root):return m.Runtime(root)
    def tearDown(self):
        for h in list(m.logging.getLogger('f006.runtime').handlers):h.close();m.logging.getLogger('f006.runtime').removeHandler(h)
    def test_only_owned_child_stopped(self):
        with tempfile.TemporaryDirectory() as td:
            rt=self.runtime(td);rt.child=mock.Mock();child=rt.child;child.poll.return_value=None
            rt.child_pgid=12345
            with mock.patch.object(m.os,'killpg',side_effect=[None,ProcessLookupError(),ProcessLookupError()]) as kill:
                rt.stop_child();self.assertEqual(kill.call_args_list[0],mock.call(12345,m.signal.SIGTERM));child.wait.assert_called_once();self.assertIsNone(rt.child)
            with mock.patch.object(m.subprocess,'Popen') as popen:rt.stop_child();popen.assert_not_called()
    def test_guard_stops_own_child_on_warning(self):
        with tempfile.TemporaryDirectory() as td:
            rt=self.runtime(td);done=mock.Mock();done.wait.return_value=False;breach=mock.Mock()
            with mock.patch.object(m,'sample',return_value=dict(pressure=2)),mock.patch.object(rt,'stop_child') as stop:
                rt.watch(dict(swap_mib=0),done,breach);breach.set.assert_called_once();stop.assert_called_once()
    def test_atomic_bounded_report_and_log_rotation(self):
        with tempfile.TemporaryDirectory() as td:
            rt=self.runtime(td)
            for _ in range(30):rt.report('ready',message='x'*100)
            files=list((Path(td)/'state').glob('.latest-*'));self.assertEqual(files,[])
            self.assertLess((Path(td)/'state/latest.json').stat().st_size,4096)
            for _ in range(800):rt.logger.info('x'*2048)
            logs=list((Path(td)/'state').glob('runtime.log*'));self.assertLessEqual(len(logs),3);self.assertTrue(all(p.stat().st_size<=524288 for p in logs))

class ProxyIntegration(unittest.TestCase):
    def setUp(self):
        import http.server,threading
        self.tmp=tempfile.TemporaryDirectory();self.rt=m.Runtime(self.tmp.name)
        self.rt.start_child=mock.Mock();self.rt.stop_child=mock.Mock()
        self.frames=[dict(model=m.MODEL,message=dict(content='检查记录。'),done=False),dict(model=m.MODEL,message=dict(content=''),done=True,done_reason='stop',prompt_eval_count=12,eval_count=5)]
        owner=self;self.drip=False;self.drip_connection=None
        class Upstream(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def do_POST(self):
                owner.upstream_payload=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                if owner.drip:
                    import time
                    owner.drip_connection=self.connection
                    self.send_response(200);self.end_headers()
                    try:
                        for _ in range(100):self.wfile.write(b'{');self.wfile.flush();time.sleep(.02)
                    except OSError:pass
                    return
                body=b''.join((json.dumps(x)+'\n').encode() for x in owner.frames)
                self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        self.upstream=http.server.ThreadingHTTPServer(('127.0.0.1',0),Upstream)
        self.gateway=http.server.ThreadingHTTPServer(('127.0.0.1',0),m.Handler);self.gateway.runtime=self.rt
        self.threads=[]
        for server in [self.upstream,self.gateway]:
            t=threading.Thread(target=server.serve_forever,kwargs=dict(poll_interval=.02),daemon=True);t.start();self.threads.append(t)
        self.good=dict(pressure=1,memory_free_percent=65,swap_mib=100,free_gib=130)
        self.patches=[mock.patch.object(m,'MODEL_PORT',self.upstream.server_address[1]),mock.patch.object(m,'sample',return_value=self.good),mock.patch.object(m.subprocess,'run',return_value=mock.Mock(returncode=1))]
        for p in self.patches:p.start()
    def tearDown(self):
        for s in [self.gateway,self.upstream]:s.shutdown();s.server_close()
        for t in self.threads:t.join(timeout=1)
        for p in self.patches:p.stop()
        for h in list(m.logging.getLogger('f006.runtime').handlers):h.close();m.logging.getLogger('f006.runtime').removeHandler(h)
        self.tmp.cleanup()
    def call(self):
        import http.client
        c=http.client.HTTPConnection('127.0.0.1',self.gateway.server_address[1],timeout=3)
        d=RequestContract().payload();c.request('POST','/api/chat',json.dumps(d),{'Content-Type':'application/json'});r=c.getresponse();body=r.read();status=r.status;c.close();return status,body
    def test_valid_stream_is_not_relabelled_and_only_own_child_is_used(self):
        status,body=self.call();self.assertEqual(status,200);rows=[json.loads(x) for x in body.splitlines()]
        self.assertTrue(rows[-1]['done']);self.assertEqual(rows[-1]['model'],m.MODEL)
        self.assertEqual(self.upstream_payload['keep_alive'],0);self.assertEqual(self.upstream_payload['options']['num_ctx'],10240)
        self.rt.start_child.assert_called_once();self.rt.stop_child.assert_called_once()
    def test_resource_refusal_before_start(self):
        self.good['pressure']=2;status,_=self.call();self.assertEqual(status,503);self.rt.start_child.assert_not_called()
    def test_concurrent_request_rejected(self):
        self.rt.request_lock.acquire()
        try:status,_=self.call();self.assertEqual(status,429);self.rt.start_child.assert_not_called()
        finally:self.rt.request_lock.release()
    def test_bad_model_frame_does_not_complete(self):
        self.frames[0]['model']='unexpected';_,body=self.call();self.assertIn(b'"error"',body);self.assertNotIn(b'"done": true',body)
    def test_truncated_terminal_does_not_complete(self):
        self.frames[-1]['done_reason']='length';_,body=self.call();self.assertIn(b'"error"',body);self.assertNotIn(b'"done": true',body)
    def test_invalid_usage_does_not_complete(self):
        self.frames[-1]['eval_count']=True;_,body=self.call();self.assertIn(b'"error"',body);self.assertNotIn(b'"done": true',body)
    def test_missing_terminal_does_not_complete(self):
        self.frames=self.frames[:1];_,body=self.call();self.assertIn(b'"error"',body);self.assertNotIn(b'"done": true',body)

class CleanupFailures(ProxyIntegration):
    def test_wall_deadline_ends_no_newline_slow_stream(self):
        import socket,time
        self.drip=True
        def stop():
            if self.drip_connection:
                try:self.drip_connection.shutdown(socket.SHUT_RDWR)
                except OSError:pass
        self.rt.stop_child.side_effect=stop
        with mock.patch.object(m,'REQUEST_SECONDS',.15):
            start=time.monotonic();status,body=self.call();self.assertLess(time.monotonic()-start,1)
        self.assertEqual(status,200);self.assertIn(b'"error"',body);self.assertNotIn(b'"done": true',body);self.assertFalse(self.rt.request_lock.locked())
    def test_shutdown_refuses_new_child(self):
        self.rt.closing.set();self.assertEqual(self.call()[0],503);self.rt.start_child.assert_not_called()
    def test_report_failure_releases_lock(self):
        original=self.rt.report
        def report(status,**kw):
            if status!='running':raise OSError('simulated disk full')
            return original(status,**kw)
        self.rt.report=report
        self.assertEqual(self.call()[0],200);self.assertFalse(self.rt.request_lock.locked());self.assertFalse(self.rt.active)
    def test_cleanup_failure_releases_lock(self):
        self.rt.stop_child.side_effect=OSError('simulated cleanup failure')
        self.call();self.assertFalse(self.rt.request_lock.locked());self.assertFalse(self.rt.active)

class DeadlineGuard(unittest.TestCase):
    def test_expired_wall_deadline_stops_child_even_with_healthy_resources(self):
        with tempfile.TemporaryDirectory() as td:
            rt=m.Runtime(td);done=mock.Mock();done.wait.return_value=False;breach=mock.Mock()
            with mock.patch.object(rt,'stop_child') as stop:
                rt.watch({},done,breach,deadline=0);stop.assert_called_once();breach.set.assert_called_once()
            for h in list(rt.logger.handlers):h.close();rt.logger.removeHandler(h)

class RealOwnedProcessCleanup(unittest.TestCase):
    def test_exited_server_does_not_leave_ignoring_runner(self):
        import subprocess,sys,os,time,signal
        child_code='import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);print("ready",flush=True);time.sleep(30)'
        parent_code='import subprocess,sys;p=subprocess.Popen([sys.executable,"-u","-c",'+repr(child_code)+'],stdout=subprocess.PIPE,text=True);p.stdout.readline();print(p.pid,flush=True)'
        with tempfile.TemporaryDirectory() as td:
            proc=subprocess.Popen([sys.executable,'-u','-c',parent_code],stdout=subprocess.PIPE,text=True,start_new_session=True)
            pid=int(proc.stdout.readline());proc.wait(timeout=3)
            rt=m.Runtime(td);rt.child=proc;rt.child_pgid=proc.pid
            try:
                rt.stop_child()
                # A killed orphan may briefly remain as a zombie; it owns no model memory.
                status=subprocess.run(['ps','-o','stat=','-p',str(pid)],capture_output=True,text=True)
                self.assertTrue(status.returncode!=0 or not status.stdout.strip() or status.stdout.lstrip().startswith('Z'))
            finally:
                try:os.killpg(proc.pid,signal.SIGKILL)
                except ProcessLookupError:pass
                proc.stdout.close()
                for h in list(rt.logger.handlers):h.close();rt.logger.removeHandler(h)

if __name__=='__main__':unittest.main()
