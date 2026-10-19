#!/usr/bin/env python3
"""F006-only loopback protocol adapter and native model supervisor; never stops a VM or another model service."""
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
LOCAL_GUIDANCE = ('补充要求：仅回答与问题有关的要点，最多4条，正文不超过200个字符（包括标点）。'
    '温控或冷链异常应先隔离受影响产品，由质检依据时间温度记录和SOP评估，不能仅凭外观、气味或口感判断安全。'
    '日期模糊须先隔离追溯，核实前不得擅改日期。生熟共台应优先改为物理分区；分时操作须先熟后生并彻底清洗消毒。')
EFFECTIVE_SYSTEM = SYSTEM + '\n' + LOCAL_GUIDANCE
HOST = '127.0.0.1'
MODEL_PORT = 11437
GATEWAY_PORT = 11438
GIB = 2**30
REQUEST_SECONDS = 50
MODEL_SHA256 = 'b0d5696b72a61363c5fd3495c0c7034e8adc42020f65436175b6b57425c2be6d'
MODEL_BYTES = 9779561824
NATIVE_PROFILE = dict(load_mode='mmap',gpu_layers=48,parallel=1,batch=64,ubatch=64,
    ctx=10240,cache_type='q8_0',lazy='on',reasoning_budget=64)


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
    probe=("import subprocess,sys;p=subprocess.run(['pgrep','-x','Runner.Worker'],stdout=subprocess.DEVNULL);"
           "print('F006_CI_BUSY_v1' if p.returncode==0 else 'F006_CI_IDLE_v1' if p.returncode==1 else 'F006_CI_UNKNOWN_v1');"
           "sys.exit(0 if p.returncode in (0,1) else 2)")
    result=subprocess.run(['/opt/homebrew/bin/colima','ssh','-p','ci','--','python3','-c',probe],
                          capture_output=True,text=True,timeout=3)
    return result.returncode!=0 or result.stdout.strip()!='F006_CI_IDLE_v1'


def admitted(s, native_build=False):
    return (s['pressure']==1 and s['memory_free_percent']>=60 and
            s['free_gib']>=110 and not native_build)


def protected(s, baseline):
    return (s['pressure']==1 and s['memory_free_percent']>=15 and
            s['free_gib']>=105 and s['swap_mib']<=baseline['swap_mib']+64)


def signal_owned_group(pgid, sig):
    try:
        os.killpg(pgid, sig)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        # Darwin can report EPERM for a group whose final member just exited.
        # Ignore it only after proving that no live member remains; query failures
        # and genuinely live inaccessible processes remain cleanup errors.
        rows=subprocess.check_output(['ps','-axo','pgid=,stat='],text=True,timeout=2)
        if any(len(v:=line.split())>=2 and v[0]==str(pgid) and not v[1].startswith('Z')
               for line in rows.splitlines()):
            raise
        return False


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
    # The caller cannot choose an engine, model path, context, tools or egress.
    return dict(model=MODEL,messages=[dict(role='system',content=EFFECTIVE_SYSTEM),m[1]],
        stream=True,stream_options={'include_usage':True},temperature=.4,top_k=20,
        top_p=.95,min_p=0,repeat_penalty=1,presence_penalty=0,
        max_tokens=768 if d['think'] else 384,cache_prompt=False,
        chat_template_kwargs={'enable_thinking':d['think']})


def translated_stream(response, guard, thinking):
    """Accept bounded native SSE and publish a terminal only after real usage + DONE."""
    content='';reasoning='';finish=None;usage=None
    while True:
        line=response.readline(131073)
        guard()
        if not line:raise RuntimeError('missing_native_terminal')
        if len(line)>131072:raise RuntimeError('native_line_size')
        if not line.strip():continue
        if not line.startswith(b'data:'):raise RuntimeError('native_sse_format')
        raw=line[5:].strip()
        if raw==b'[DONE]':
            if finish!='stop' or not content.strip() or usage is None:
                raise RuntimeError('native_incomplete')
            if thinking!=bool(reasoning):raise RuntimeError('native_thinking_contract')
            yield dict(model=MODEL,message=dict(role='assistant',content=''),done=True,
                done_reason='stop',prompt_eval_count=usage['prompt_tokens'],eval_count=usage['completion_tokens'])
            return
        frame=json.loads(raw)
        if not isinstance(frame,dict) or frame.get('model')!=MODEL or frame.get('error'):
            raise RuntimeError('native_frame_guard')
        choices=frame.get('choices')
        if not isinstance(choices,list) or len(choices)>1:raise RuntimeError('native_choices')
        if frame.get('usage') is not None:
            if usage is not None:raise RuntimeError('duplicate_native_usage')
            usage=frame['usage']
            if (not isinstance(usage,dict) or any(type(usage.get(k)) is not int or usage[k]<0
                    for k in ('prompt_tokens','completion_tokens','total_tokens'))
                    or usage['total_tokens']!=usage['prompt_tokens']+usage['completion_tokens']):
                raise RuntimeError('native_usage')
        for choice in choices:
            if not isinstance(choice,dict) or choice.get('index')!=0:raise RuntimeError('native_choice')
            delta=choice.get('delta')
            if (not isinstance(delta,dict) or set(delta)-{'role','content','reasoning_content'}
                    or delta.get('role','assistant')!='assistant'):
                raise RuntimeError('native_text_only')
            text=delta.get('content','');reason=delta.get('reasoning_content','')
            if text is None:text=''
            if reason is None:reason=''
            if not isinstance(text,str) or not isinstance(reason,str):raise RuntimeError('native_text_type')
            if finish is not None:raise RuntimeError('native_after_finish')
            content+=text;reasoning+=reason
            if len(content)>12000 or len(reasoning)>12000:raise RuntimeError('native_text_size')
            end=choice.get('finish_reason')
            if end is not None:
                if end!='stop':raise RuntimeError('native_truncated')
                finish=end
            if text or reason:
                yield dict(model=MODEL,message=dict(role='assistant',content=text,thinking=reason),done=False)



class Runtime:
    def __init__(self,root):
        self.root=Path(root);self.state=self.root/'state';self.state.mkdir(parents=True,exist_ok=True,mode=0o700)
        self.child=None;self.child_pgid=None;self.child_lock=threading.Lock();self.request_lock=threading.Lock();self.active=False;self.closing=threading.Event()
        self.logger=logging.getLogger('f006.runtime');self.logger.setLevel(logging.INFO)
        h=RotatingFileHandler(self.state/'runtime.log',maxBytes=524288,backupCount=2)
        h.setFormatter(logging.Formatter('%(asctime)s %(message)s'));self.logger.addHandler(h)
        self.report('ready')
    def report(self,status,**fields):
        data=dict(status=status,model=MODEL,native_profile=NATIVE_PROFILE,request_active=self.active,updated_at=int(time.time()),**fields)
        fd,name=tempfile.mkstemp(prefix='.latest-',dir=self.state)
        try:
            with os.fdopen(fd,'w') as f:json.dump(data,f);f.flush();os.fsync(f.fileno())
            os.replace(name,self.state/'latest.json')
        finally:
            with contextlib.suppress(FileNotFoundError):os.unlink(name)
    def start_child(self):
        with self.child_lock:
            if self.child and self.child.poll() is None:return
            with socket.socket() as sock:
                sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
                sock.bind((HOST,MODEL_PORT))
            blob=self.root/'models/blobs'/('sha256-'+MODEL_SHA256)
            if blob.is_symlink() or not blob.is_file() or blob.stat().st_size!=MODEL_BYTES:
                raise RuntimeError('fixed_model_missing')
            command=[str(self.root/'runtime-0.40.6/llama-server'),'-m',str(blob),
                '--alias',MODEL,'--host',HOST,'--port',str(MODEL_PORT),'--parallel',str(NATIVE_PROFILE['parallel']),
                '-c',str(NATIVE_PROFILE['ctx']),'-t','4','-b',str(NATIVE_PROFILE['batch']),
                '-ub',str(NATIVE_PROFILE['ubatch']),'--load-mode',NATIVE_PROFILE['load_mode'],
                '-ngl',str(NATIVE_PROFILE['gpu_layers']),'-fa','on','-ctk',NATIVE_PROFILE['cache_type'],
                '-ctv',NATIVE_PROFILE['cache_type'],'--lazy-mode',NATIVE_PROFILE['lazy'],
                '--cache-ram','0','--ctx-checkpoints','0',
                '--reasoning-budget',str(NATIVE_PROFILE['reasoning_budget']),'--reasoning-format','deepseek','--no-webui','--jinja']
            self.child=subprocess.Popen(command,stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
            proc=self.child;self.child_pgid=proc.pid
        # Do not hold the cleanup mutex while the model loads: the independent
        # resource and wall watchdogs must be able to terminate this initial peak.
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            if proc.poll() is not None:raise RuntimeError('model_server_start_failed')
            try:
                c=http.client.HTTPConnection(HOST,MODEL_PORT,timeout=.25)
                try:
                    c.request('GET','/health');r=c.getresponse();r.read()
                    if r.status==200:return
                finally:c.close()
            except OSError:time.sleep(.1)
        self.stop_child();raise RuntimeError('model_server_start_timeout')
    def stop_child_locked(self):
        pgid=self.child_pgid
        if pgid:
            signal_owned_group(pgid,signal.SIGTERM)
            deadline=time.monotonic()+3
            while time.monotonic()<deadline:
                if not signal_owned_group(pgid,0):break
                time.sleep(.05)
            signal_owned_group(pgid,signal.SIGKILL)
        if self.child:
            with contextlib.suppress(subprocess.TimeoutExpired):self.child.wait(timeout=2)
        self.child=None;self.child_pgid=None
    def stop_child(self):
        with self.child_lock:self.stop_child_locked()
    def watch(self,baseline,done,breach,deadline=None):
        while not done.wait(.5):
            try:ok=(deadline is None or time.monotonic()<deadline) and protected(sample(),baseline)
            except Exception:ok=False
            if not ok:
                breach.set();self.logger.warning('resource_guard_stop');self.stop_child();return

    def watch_jobs(self,done,breach):
        # Guest/CLI latency must never delay the independent memory watchdog.
        while not done.wait(5):
            try:ok=not active_build()
            except (OSError,subprocess.SubprocessError):ok=False
            if not ok:
                breach.set();self.logger.warning('build_guard_stop');self.stop_child();return


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
        started=time.monotonic();content='';baseline=None;done=threading.Event();breach=threading.Event();watcher=None;job_watcher=None;timer=None;conn=None;sent=False;complete=False
        try:
            if rt.closing.is_set():self.reply(503,'shutting_down');return
            baseline=sample()
            native=active_build()
            if not admitted(baseline,native):self.reply(503,'resources_busy');return
            rt.active=True;rt.report('running',baseline=baseline)
            def expire():
                breach.set();rt.stop_child()
            timer=threading.Timer(max(0,started+REQUEST_SECONDS-time.monotonic()),expire);timer.daemon=True;timer.start()
            watcher=threading.Thread(target=rt.watch,args=(baseline,done,breach,started+REQUEST_SECONDS),daemon=True);watcher.start()
            job_watcher=threading.Thread(target=rt.watch_jobs,args=(done,breach),daemon=True);job_watcher.start()
            rt.start_child()
            if breach.is_set():raise RuntimeError('startup_guard')
            conn=http.client.HTTPConnection(HOST,MODEL_PORT,timeout=25)
            conn.request('POST','/v1/chat/completions',json.dumps(payload).encode(),{'Content-Type':'application/json'})
            response=conn.getresponse()
            if response.status!=200:raise RuntimeError('upstream_status')
            self.send_response(200);self.send_header('Content-Type','application/x-ndjson');self.send_header('Connection','close');self.end_headers();sent=True
            deadline=started+REQUEST_SECONDS
            def guard():
                if time.monotonic()>deadline or breach.is_set():raise RuntimeError('stream_guard')
            for frame in translated_stream(response,guard,payload['chat_template_kwargs']['enable_thinking']):
                guard()
                self.wfile.write((json.dumps(frame,ensure_ascii=False)+'\n').encode());self.wfile.flush()
                if frame.get('done') is True:complete=True
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
            if job_watcher:job_watcher.join(timeout=6)
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
