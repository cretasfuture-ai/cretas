#!/usr/bin/env python3
"""F006-only loopback Ollama supervisor; never stops a VM or another model service."""
from __future__ import annotations
import argparse
import contextlib
import fcntl
import http.client
import http.server
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import threading
import tempfile
import time

MODEL = 'qwen3.8:27b'
SYSTEM = ('你是白垩纪食品工厂助手。仅回答食品安全、生产和质量管理的通用咨询。'
          '用中文给出简洁实用的建议，不超过300字。不能声称读取了工厂实时数据，'
          '不能代用户执行业务操作。具体库存、订单或金额应引导使用系统查询。')
HOST = '127.0.0.1'
MODEL_PORT = 11437
GATEWAY_PORT = 11438
GIB = 2**30
REQUEST_SECONDS = 50


def identity():
    if (subprocess.check_output(['hostname'],text=True).strip(),
        subprocess.check_output(['whoami'],text=True).strip(),str(Path.home())) != (
            'Auroras-Mac-mini.local','aurora','/Users/aurora'):
        raise RuntimeError('Aurora identity required')


def sample():
    pressure = int(subprocess.check_output(['sysctl','-n','kern.memorystatus_vm_pressure_level'],text=True,timeout=2))
    memory = subprocess.check_output(['memory_pressure','-Q'],text=True,timeout=2)
    swap = subprocess.check_output(['sysctl','vm.swapusage'],text=True,timeout=2)
    return dict(pressure=pressure,
        memory_free_percent=int(re.search(r'free percentage: (\d+)%',memory).group(1)),
        swap_mib=float(re.search(r'used = ([\d.]+)M',swap).group(1)),
        free_gib=shutil.disk_usage(Path.home()).free/GIB)


def active_build():
    if subprocess.run(['pgrep','-x','xcodebuild'],stdout=subprocess.DEVNULL,timeout=2).returncode==0:
        return True
    # Read-only guest query. A missing/unreachable CI guest fails closed, never starts it.
    result=subprocess.run(['/opt/homebrew/bin/colima','ssh','-p','ci','--','pgrep','-x','Runner.Worker'],
                          stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=3)
    return result.returncode!=1


def admitted(s, native_build=False):
    return (s['pressure']==1 and s['memory_free_percent']>=60 and
            s['free_gib']>=110 and not native_build)


def protected(s, baseline):
    return (s['pressure']==1 and s['memory_free_percent']>=15 and
            s['free_gib']>=105 and s['swap_mib']<=baseline['swap_mib']+64)


def validate_request(raw):
    if len(raw)>32768: raise ValueError('request_size')
    d=json.loads(raw)
    if not isinstance(d,dict) or set(d)-{'model','messages','stream','think','options'}:
        raise ValueError('request_fields')
    if d.get('model')!=MODEL or d.get('stream') is not True or type(d.get('think')) is not bool:
        raise ValueError('request_model')
    m=d.get('messages');opts=d.get('options')
    if (not isinstance(m,list) or len(m)!=2 or [x.get('role') for x in m if isinstance(x,dict)]!=['system','user']
            or any(set(x)!={'role','content'} or not isinstance(x['content'],str) for x in m)
            or not 1<=len(m[1]['content'])<=2000 or m[0]['content']!=SYSTEM):
        raise ValueError('request_messages')
    m[1]['content'].encode('utf-8') # Reject lone surrogates rather than truncating a malformed question.
    if (not isinstance(opts,dict) or set(opts)!={'temperature','num_predict'}
            or opts['temperature']!=0.4 or opts['num_predict']!=(768 if d['think'] else 384)):
        raise ValueError('request_options')
    # The caller cannot choose model, context, GPU budget, cloud or persistence.
    d['keep_alive']=0
    d['options'].update(num_ctx=10240,num_thread=4)
    return d


class Runtime:
    def __init__(self,root):
        self.root=Path(root);self.state=self.root/'state';self.state.mkdir(parents=True,exist_ok=True,mode=0o700)
        self.child=None;self.child_pgid=None;self.child_lock=threading.Lock();self.request_lock=threading.Lock();self.active=False;self.closing=threading.Event()
        self.logger=logging.getLogger('f006.runtime');self.logger.setLevel(logging.INFO)
        h=RotatingFileHandler(self.state/'runtime.log',maxBytes=524288,backupCount=2)
        h.setFormatter(logging.Formatter('%(asctime)s %(message)s'));self.logger.addHandler(h)
        self.report('ready')
    def report(self,status,**fields):
        data=dict(status=status,model=MODEL,request_active=self.active,updated_at=int(time.time()),**fields)
        fd,name=tempfile.mkstemp(prefix='.latest-',dir=self.state)
        try:
            with os.fdopen(fd,'w') as f:json.dump(data,f);f.flush();os.fsync(f.fileno())
            os.replace(name,self.state/'latest.json')
        finally:
            with contextlib.suppress(FileNotFoundError):os.unlink(name)
    def start_child(self):
        with self.child_lock:
            if self.child and self.child.poll() is None:return
            # Unknown listeners are never killed or adopted.
            with socket.socket() as s:
                s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);s.bind((HOST,MODEL_PORT))
            runtime_home=self.root/'runtime-home';runtime_home.mkdir(mode=0o700,exist_ok=True)
            env=dict(os.environ,HOME=str(runtime_home),OLLAMA_AUTH='0',OLLAMA_HOST=f'{HOST}:{MODEL_PORT}',OLLAMA_MODELS=str(self.root/'models'),
                OLLAMA_MAX_LOADED_MODELS='1',OLLAMA_NUM_PARALLEL='1',OLLAMA_MAX_QUEUE='1',
                OLLAMA_KEEP_ALIVE='0',OLLAMA_CONTEXT_LENGTH='10240',OLLAMA_FLASH_ATTENTION='1',
                OLLAMA_KV_CACHE_TYPE='q8_0',OLLAMA_NO_CLOUD='1',OLLAMA_NOPRUNE='1',OLLAMA_DEBUG='0',LLAMA_ARG_CACHE_RAM='0',LLAMA_ARG_CTX_CHECKPOINTS='0')
            # A private session makes cleanup include only this server and its runners.
            self.child=subprocess.Popen([str(self.root/'runtime-0.40.6/ollama'),'serve'],env=env,
                stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
            self.child_pgid=self.child.pid
            deadline=time.monotonic()+5
            while time.monotonic()<deadline:
                if self.child.poll() is not None:raise RuntimeError('model_server_start_failed')
                try:
                    c=http.client.HTTPConnection(HOST,MODEL_PORT,timeout=.25);c.request('GET','/api/version')
                    r=c.getresponse();r.read();c.close()
                    if r.status==200:return
                except OSError:time.sleep(.1)
            self.stop_child_locked();raise RuntimeError('model_server_start_timeout')
    def stop_child_locked(self):
        pgid=self.child_pgid
        if pgid:
            with contextlib.suppress(ProcessLookupError):os.killpg(pgid,signal.SIGTERM)
            deadline=time.monotonic()+3
            while time.monotonic()<deadline:
                try:os.killpg(pgid,0)
                except ProcessLookupError:break
                time.sleep(.05)
            with contextlib.suppress(ProcessLookupError):os.killpg(pgid,signal.SIGKILL)
        if self.child:
            with contextlib.suppress(subprocess.TimeoutExpired):self.child.wait(timeout=2)
        self.child=None;self.child_pgid=None
    def stop_child(self):
        with self.child_lock:self.stop_child_locked()
    def watch(self,baseline,done,breach,deadline=None):
        next_job_check=time.monotonic()+5
        while not done.wait(.5):
            try:ok=(deadline is None or time.monotonic()<deadline) and protected(sample(),baseline)
            except Exception:ok=False
            if ok and time.monotonic()>=next_job_check:
                try:ok=not active_build()
                except (OSError,subprocess.SubprocessError):ok=False
                next_job_check=time.monotonic()+5
            if not ok:
                breach.set();self.logger.warning('resource_guard_stop');self.stop_child();return


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.0'
    def log_message(self,*args):pass # No user question, answer, headers or remote identifiers in logs.
    def reply(self,status,code):
        b=json.dumps({'error':code}).encode();self.send_response(status);self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
    def do_GET(self):
        if self.path!='/health':self.reply(404,'not_found');return
        b=b'{"status":"ready"}';self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
    def do_POST(self):
        rt=self.server.runtime
        if self.path!='/api/chat':self.reply(404,'not_found');return
        if self.headers.get('Origin') or self.headers.get_content_type()!='application/json':self.reply(400,'internal_json_required');return
        if self.headers.get('Transfer-Encoding'):self.reply(400,'length_required');return
        try:
            n=int(self.headers.get('Content-Length','0'))
            if not 0<n<=32768:raise ValueError('request_size')
            self.connection.settimeout(5);payload=validate_request(self.rfile.read(n))
        except (ValueError,TypeError,KeyError,OSError):self.reply(400,'invalid_request');return
        if rt.closing.is_set():self.reply(503,'shutting_down');return
        if not rt.request_lock.acquire(False):self.reply(429,'busy');return
        started=time.monotonic();content='';baseline=None;done=threading.Event();breach=threading.Event();watcher=None;timer=None;conn=None;sent=False;complete=False
        try:
            if rt.closing.is_set():self.reply(503,'shutting_down');return
            baseline=sample()
            native=active_build()
            if not admitted(baseline,native):self.reply(503,'resources_busy');return
            rt.active=True;rt.report('running',baseline=baseline);rt.start_child()
            def expire():
                breach.set();rt.stop_child()
            timer=threading.Timer(max(0,started+REQUEST_SECONDS-time.monotonic()),expire);timer.daemon=True;timer.start()
            watcher=threading.Thread(target=rt.watch,args=(baseline,done,breach,started+REQUEST_SECONDS),daemon=True);watcher.start()
            conn=http.client.HTTPConnection(HOST,MODEL_PORT,timeout=25)
            conn.request('POST','/api/chat',json.dumps(payload).encode(),{'Content-Type':'application/json'})
            response=conn.getresponse()
            if response.status!=200:raise RuntimeError('upstream_status')
            self.send_response(200);self.send_header('Content-Type','application/x-ndjson');self.send_header('Connection','close');self.end_headers();sent=True
            deadline=started+REQUEST_SECONDS
            while True:
                line=response.readline(131073)
                if not line:break
                if len(line)>131072 or time.monotonic()>deadline or breach.is_set():raise RuntimeError('stream_guard')
                frame=json.loads(line)
                if (not isinstance(frame,dict) or frame.get('model')!=MODEL or frame.get('error')):raise RuntimeError('frame_guard')
                msg=frame.get('message',{})
                if not isinstance(msg,dict) or msg.get('tool_calls') or msg.get('images') or not isinstance(msg.get('content',''),str) or not isinstance(msg.get('thinking',''),str):raise RuntimeError('text_guard')
                content+=msg.get('content','')
                if len(content)>12000:raise RuntimeError('answer_size')
                if frame.get('done') is True:
                    if frame.get('done_reason')!='stop' or not content.strip():raise RuntimeError('incomplete')
                    if any(type(frame.get(k)) is not int or frame[k]<0 for k in ['prompt_eval_count','eval_count']):raise RuntimeError('usage_guard')
                    complete=True
                self.wfile.write(line);self.wfile.flush()
                if complete:break
            if not complete:raise RuntimeError('incomplete')
            rt.logger.info('request_completed')
        except (OSError,ValueError,RuntimeError,http.client.HTTPException,subprocess.SubprocessError):
            rt.logger.warning('request_failed resource_breach=%s',breach.is_set())
            with contextlib.suppress(OSError):
                if not sent:self.reply(503,'local_model_unavailable')
                else:self.wfile.write(b'{"error":"local_model_unavailable"}\n');self.wfile.flush()
        finally:
            done.set()
            if timer:timer.cancel();timer.join(timeout=6)
            if conn:conn.close()
            if watcher:watcher.join(timeout=6)
            # Lock release must survive ENOSPC, failed telemetry or child cleanup.
            try:
                with contextlib.suppress(OSError,subprocess.SubprocessError):rt.stop_child()
                rt.active=False
                with contextlib.suppress(OSError):rt.report('ready' if complete else 'idle_after_refusal',completed=complete,resource_breach=breach.is_set())
            finally:
                rt.active=False;rt.request_lock.release();self.close_connection=True



def main():
    parser=argparse.ArgumentParser();parser.add_argument('--root',type=Path,default=Path.home()/'.local/share/cretas-f006-aurora');parser.add_argument('--check',action='store_true');args=parser.parse_args()
    identity()
    if args.check:
        print(json.dumps({'identity':True,'resources':sample(),'model_tag':MODEL,'model_port':MODEL_PORT,'gateway_port':GATEWAY_PORT,'run':False}));return
    os.umask(0o077);args.root.mkdir(mode=0o700,parents=True,exist_ok=True)
    lock=(args.root/'supervisor.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    rt=Runtime(args.root);server=http.server.ThreadingHTTPServer((HOST,GATEWAY_PORT),Handler);server.daemon_threads=False;server.runtime=rt
    def stop(*_):
        rt.closing.set()
        threading.Thread(target=server.shutdown,daemon=True).start()
    signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
    try:server.serve_forever(poll_interval=.25)
    finally:server.server_close();rt.stop_child();rt.report('stopped')
if __name__=='__main__':main()
