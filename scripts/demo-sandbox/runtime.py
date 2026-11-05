#!/usr/bin/env python3
"""Authenticated loopback manager for one physically isolated F006 demo slot."""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import os
import re
import secrets
import shutil
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, ProxyHandler
from orchestrator import SandboxOrchestrator, NoRedirect, atomic_json

UUID_RE = re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
ACTOR_RE = re.compile(r'^[1-9][0-9]*$')
FILE_RE = re.compile(r'^/api/mobile/F006/demo-sandbox-local-files/([A-Za-z0-9_-]{24,64})$')
MAX_BODY = 16 * 1024 * 1024
STATUSES = {'PREPARING','READY','ENDING','ENDED','FAILED'}

class SandboxError(RuntimeError): pass

class Manager:
    def __init__(self, cfg, slot=None):
        self.cfg = cfg; self._validate_config()
        self.lock = threading.RLock(); self.condition = threading.Condition(self.lock)
        self.inflight = {}; self.workers = {}; self.ending = set(); self.files = {}
        self.path = Path(cfg['manifest_path']); self.file_root = Path(cfg['sandbox_file_root']).resolve()
        if not self.file_root.is_relative_to(self.path.parent.resolve()) or self.file_root == self.path.parent.resolve(): raise SandboxError('sandbox files must stay below the private session directory')
        if slot is None:
            config_path = Path(cfg.get('orchestrator_config_path',''))
            if not config_path.is_absolute() or not config_path.is_file(): raise SandboxError('private slot configuration required')
            slot = SandboxOrchestrator(json.loads(config_path.read_text(encoding='utf-8')))
        self.slot = slot
        self.opener = build_opener(ProxyHandler({}), NoRedirect())
        self.sessions = self._load()
        if not any(s['status'] != 'ENDED' for s in self.sessions.values()):
            self.slot.assert_clean()
            if self.file_root.exists() and any(self.file_root.iterdir()): raise SandboxError('unaccounted sandbox files require verified cleanup')
        recovered = False
        for session in self.sessions.values():
            if session['status'] in {'PREPARING','READY','ENDING'}:
                session['status'] = 'FAILED'; session['message'] = '服务重启后保持隔离，请点击结束演示重试清理'; recovered = True
        if recovered: self._save()

    def _validate_config(self):
        if self.cfg.get('strict_security', True) is not True: raise SandboxError('sandbox safety checks cannot be disabled')
        if self.cfg.get('allowed_actor_id') != 1309: raise SandboxError('this slot belongs to F006 actor 1309')
        if self.cfg.get('listen_host') != '127.0.0.1' or self.cfg.get('listen_port') != 10041: raise SandboxError('manager must bind loopback:10041')
        if self.cfg.get('java_base_url') != 'http://127.0.0.1:10040' or self.cfg.get('sandbox_base_url') != 'http://127.0.0.1:10040': raise SandboxError('only the fixed sandbox Java slot is allowed')
        if self.cfg.get('sandbox_db_name') != 'f006_demo_sandbox' or self.cfg.get('pg_access_mode') != 'DOCKER_EXEC_ONLY' or self.cfg.get('sandbox_redis_port') != 6387: raise SandboxError('sandbox dependency identity invalid')
        key = self.cfg.get('control_key',''); token = self.cfg.get('sandbox_bearer_token','')
        if len(key) < 32 or len(set(key)) < 12: raise SandboxError('a random private control key is required')
        if len(token) < 64 or token == self.cfg.get('production_bearer_token'): raise SandboxError('a private sandbox-only bearer is required')
        for name in ('manifest_path','sandbox_file_root'):
            if not os.path.isabs(self.cfg.get(name,'')): raise SandboxError('absolute private sandbox paths are required')

    def _load(self):
        if not self.path.is_file(): raise SandboxError('manifest missing; perform a verified local reset/bootstrap')
        try: value = json.loads(self.path.read_text(encoding='utf-8'))
        except (OSError, ValueError): raise SandboxError('manifest unreadable; manual verified recovery required') from None
        if not isinstance(value, dict): raise SandboxError('manifest schema invalid')
        for sid, session in value.items():
            if not UUID_RE.fullmatch(sid) or not isinstance(session,dict) or session.get('sessionId') != sid or session.get('actor') != 1309 or session.get('status') not in STATUSES:
                raise SandboxError('manifest session schema invalid')
        if sum(s['status'] != 'ENDED' for s in value.values()) > 1: raise SandboxError('multiple active sessions in one slot')
        return value

    def _save(self): atomic_json(self.path, self.sessions)

    @staticmethod
    def _actor(actor):
        if actor != 1309: raise SandboxError('actor does not own this sandbox')

    def _session(self, sid, actor):
        self._actor(actor)
        session = self.sessions.get(sid)
        if not session or session['actor'] != actor: raise SandboxError('session not found')
        return session

    def _state(self, sid, status, message=''):
        with self.lock:
            snapshot = {**self.sessions, sid:{**self.sessions[sid], 'status':status, 'message':message}}
            atomic_json(self.path,snapshot)
            self.sessions = snapshot

    def _launch(self, sid, target):
        thread = threading.Thread(target=self._work, args=(sid,target), daemon=True)
        self.workers[sid] = thread; thread.start()

    def _work(self, sid, target):
        try: target(sid)
        except Exception as error:
            atomic_json(self.path.with_name('last-worker-error.private.json'),{'type':type(error).__name__,'message':str(error)[:300]})
            self._state(sid, 'FAILED', '准备或清理未完成；仍保持隔离，请重试结束演示')
        finally:
            with self.condition:
                self.workers.pop(sid, None)
                # End can arrive after READY was persisted but before this worker exits.
                if self.sessions[sid]['status'] == 'ENDING': self._launch(sid,self._reset)
                self.condition.notify_all()

    def close(self, timeout=10):
        deadline = time.monotonic()+timeout
        with self.lock: workers = list(self.workers.values())
        for thread in workers: thread.join(max(0, deadline-time.monotonic()))

    def create(self, actor):
        self._actor(actor)
        with self.lock:
            active = [s for s in self.sessions.values() if s['status'] != 'ENDED']
            if active:
                if active[0]['actor'] != actor: raise SandboxError('slot belongs to another actor')
                if active[0]['status'] == 'FAILED': raise SandboxError('finish the failed session before starting again')
                return dict(active[0])
            sid = str(uuid.uuid4())
            session = {'sessionId':sid,'actor':actor,'generation':len(self.sessions)+1,'status':'PREPARING','createdAt':time.time()}
            self.sessions[sid] = session; self._save(); snapshot = dict(session)
            self._launch(sid, self._prepare)
            return snapshot

    def _prepare(self, sid):
        try:
            self.slot.prepare(); self._ready_auth_check()
        except Exception:
            with self.lock: end_requested = sid in self.ending
            if end_requested: self._reset(sid); return
            raise
        with self.lock:
            if sid not in self.ending:
                self._state(sid,'READY'); return
        self._reset(sid)

    def _ready_auth_check(self):
        identity = self._upstream_json('/api/mobile/F006/demo-sandbox-node/identity')
        expected = {'mode':'F006_DEMO_SANDBOX','databaseUrl':'jdbc:postgresql://postgres:5432/f006_demo_sandbox',
            'redisHost':'redis','redisPort':6379,'scheduling':'DISABLED'}
        if any(identity.get(k) != v for k,v in expected.items()): raise SandboxError('node identity is not the isolated F006 service')
        if identity.get('databaseFingerprint') != hashlib.sha256(expected['databaseUrl'].encode()).hexdigest(): raise SandboxError('node database fingerprint mismatch')
        user = self._upstream_json('/api/mobile/auth/me')
        user = user.get('factoryUser', user)
        if str(user.get('id',user.get('userId'))) != '1309' or user.get('factoryId') != 'F006' or user.get('roleCode',user.get('role')) != 'factory_super_admin' or user.get('isActive',True) is not True:
            raise SandboxError('sandbox token identity does not match the session actor')

    def get(self, sid, actor):
        with self.lock: return dict(self._session(sid,actor))

    def actor_session(self, actor):
        self._actor(actor)
        with self.lock:
            active = [s for s in self.sessions.values() if s['status'] != 'ENDED']
            return dict(active[0]) if active else None

    def end(self, sid, actor):
        with self.lock:
            session = self._session(sid,actor)
            if session['status'] in {'ENDING','ENDED'}: return dict(session)
            self.ending.add(sid); self._state(sid,'ENDING','正在清空本轮数据')
            if sid not in self.workers: self._launch(sid,self._reset)
            return dict(self.sessions[sid])

    def retry_reset(self, sid, actor): return self.end(sid,actor)

    def _reset(self, sid):
        with self.condition:
            deadline = time.monotonic()+90
            while self.inflight.get(sid,0):
                remaining = deadline-time.monotonic()
                if remaining <= 0: raise SandboxError('sandbox requests did not drain')
                self.condition.wait(min(remaining,1))
        self.slot.reset()
        root = self.file_root / sid
        if root.is_symlink(): raise SandboxError('unexpected sandbox file link')
        if root.exists():
            if not root.resolve().is_relative_to(self.file_root): raise SandboxError('sandbox file root escaped')
            shutil.rmtree(root)
        with self.lock:
            self.files = {key: value for key,value in self.files.items() if value[0] != sid}
            self._state(sid,'ENDED','本轮新增单据、库存变化和凭证已清空')

    def _request_upstream(self, path, method='GET', body=None, headers=None):
        safe = {k:v for k,v in (headers or {}).items() if k.lower() in {'content-type','accept','idempotency-key'}}
        safe['Authorization'] = 'Bearer ' + self.cfg['sandbox_bearer_token']
        if isinstance(self.slot,SandboxOrchestrator):
            return self.slot.request(path,method,body,safe)
        request = Request(self.cfg['java_base_url']+path, data=body, headers=safe, method=method)
        try: response = self.opener.open(request,timeout=60)
        except HTTPError as error: response = error
        with response:
            data = response.read(MAX_BODY+1)
            if len(data) > MAX_BODY: raise SandboxError('sandbox response exceeded the limit')
            if 300 <= response.status < 400: raise SandboxError('sandbox redirects are forbidden')
            return response.status, {'Content-Type':response.headers.get('Content-Type','application/json')}, data

    def _upstream_json(self, path):
        status, _, raw = self._request_upstream(path)
        if status != 200: raise SandboxError('sandbox identity request failed')
        envelope = json.loads(raw)
        if envelope.get('success') is not True or not isinstance(envelope.get('data'),dict): raise SandboxError('sandbox identity response invalid')
        return envelope['data']

    def _image_uri(self, sid, file_id):
        pair = self.files.get(file_id)
        if not pair or pair[0] != sid or not pair[1].is_file(): raise SandboxError('sandbox image not found')
        return 'data:image/png;base64,' + base64.b64encode(pair[1].read_bytes()).decode('ascii')

    def _rewrite_files(self, value, sid):
        if isinstance(value, list): return [self._rewrite_files(v,sid) for v in value]
        if isinstance(value, dict): return {k:self._rewrite_files(v,sid) for k,v in value.items()}
        if isinstance(value, str):
            match = re.fullmatch(r'/F006/demo-sandbox-local-files/([A-Za-z0-9_-]{24,64})', value)
            if match: return self._image_uri(sid,match.group(1))
        return value

    def proxy(self, sid, actor, path, method, body, headers):
        suffix = path.partition('/sessions/'+sid+'/proxy')[2]
        target = urlsplit(suffix)
        if not target.path.startswith('/api/mobile/F006/') or '%' in target.path or '\\' in target.path or any(s in {'.','..',''} for s in target.path[1:].split('/')):
            raise SandboxError('sandbox route denied')
        if target.path.startswith(('/api/mobile/F006/demo-sandbox/','/api/mobile/F006/demo-sandbox-node/')): raise SandboxError('nested control route denied')
        if body and len(body) > MAX_BODY: raise SandboxError('sandbox body exceeded the limit')
        with self.condition:
            session = self._session(sid,actor)
            if session['status'] != 'READY': raise SandboxError('sandbox is not ready')
            self.inflight[sid] = self.inflight.get(sid,0)+1
        try:
            if method == 'POST' and target.path == '/api/mobile/F006/attachments/upload-url':
                file_id = secrets.token_urlsafe(24); root = self.file_root/sid; root.mkdir(parents=True, exist_ok=True)
                self.files[file_id] = (sid,root/file_id)
                relative = '/F006/demo-sandbox-local-files/'+file_id
                return 200, {'Content-Type':'application/json'}, json.dumps({'success':True,'data':{'uploadUrl':relative,'fileUrl':relative,'fileId':file_id}}).encode()
            local = FILE_RE.fullmatch(target.path)
            if local:
                pair = self.files.get(local.group(1))
                if not pair or pair[0] != sid: raise SandboxError('sandbox file not found')
                if method == 'PUT' and body and len(body) <= 10*1024*1024:
                    pair[1].write_bytes(body)
                    return 200, {'Content-Type':'application/json'}, b'{"success":true,"data":{}}'
                if method == 'GET':
                    return 200, {'Content-Type':'image/png'}, pair[1].read_bytes()
                raise SandboxError('sandbox file method/size denied')
            download = re.fullmatch(r'/api/mobile/F006/attachments/([A-Za-z0-9_-]+)/download-url',target.path)
            if download and method == 'GET':
                attachment = self._upstream_json('/api/mobile/F006/attachments/'+download.group(1))
                match = re.fullmatch(r'/F006/demo-sandbox-local-files/([A-Za-z0-9_-]{24,64})',str(attachment.get('fileUrl','')))
                if not match: raise SandboxError('attachment does not belong to the local sandbox')
                return 200, {'Content-Type':'application/json'}, json.dumps({'success':True,'data':{'url':self._image_uri(sid,match.group(1))}}).encode()
            status, response_headers, data = self._request_upstream(suffix,method,body,headers)
            if 'application/json' in response_headers['Content-Type'] and '/attachments' in target.path and data:
                data = json.dumps(self._rewrite_files(json.loads(data),sid)).encode()
            return status,response_headers,data
        finally:
            with self.condition:
                self.inflight[sid] -= 1; self.condition.notify_all()

class Handler(BaseHTTPRequestHandler):
    manager = None
    def _auth(self):
        key = self.headers.get('X-Sandbox-Control-Key','')
        if not secrets.compare_digest(key,self.manager.cfg['control_key']): raise SandboxError('control authentication required')
        actor = self.headers.get('X-Sandbox-Actor-Id','')
        if not ACTOR_RE.fullmatch(actor): raise SandboxError('trusted actor required')
        actor = int(actor); self.manager._actor(actor); return actor
    def _reply(self, code, value):
        self._bytes(code,{'Content-Type':'application/json'},json.dumps(value,ensure_ascii=False).encode())
    def _bytes(self, code, headers, body):
        self.send_response(code); self.send_header('Content-Type',headers.get('Content-Type','application/json')); self.send_header('Content-Length',str(len(body))); self.end_headers()
        if self.command != 'HEAD': self.wfile.write(body)
    def _handle(self):
        try:
            self.connection.settimeout(10)
            actor = self._auth()
            if len(self.headers) > 64 or sum(len(k)+len(v) for k,v in self.headers.items()) > 16384: raise SandboxError('too many headers')
            if self.headers.get('Transfer-Encoding'): raise SandboxError('chunked control requests are not supported')
            length = int(self.headers.get('Content-Length','0'))
            if length < 0 or length > MAX_BODY: raise SandboxError('body size denied')
            body = self.rfile.read(length) if length else None
            if body is not None and len(body) != length: raise SandboxError('incomplete request body')
            path = urlsplit(self.path).path
            proxy = re.fullmatch(r'/sessions/('+UUID_RE.pattern[1:-1]+r')/proxy(/.*)',path)
            if proxy:
                return self._bytes(*self.manager.proxy(proxy.group(1),actor,self.path,self.command,body,self.headers))
            if self.command == 'POST' and self.path == '/sessions': data = self.manager.create(actor)
            elif self.command == 'GET' and self.path == '/actors/'+str(actor)+'/session': data = self.manager.actor_session(actor)
            else:
                session = re.fullmatch(r'/sessions/('+UUID_RE.pattern[1:-1]+')',self.path)
                if not session: raise SandboxError('control route not found')
                if self.command == 'GET': data = self.manager.get(session.group(1),actor)
                elif self.command == 'DELETE': data = self.manager.end(session.group(1),actor)
                else: raise SandboxError('control method denied')
            self._reply(200,{'success':True,'data':data})
        except Exception:
            self._reply(409,{'success':False,'message':'演示沙盒请求未完成；请核对状态或重试结束演示'})
    do_GET = _handle; do_HEAD = _handle; do_POST = _handle; do_PUT = _handle; do_PATCH = _handle; do_DELETE = _handle
    def log_message(self, *_): pass

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--config',required=True); args = parser.parse_args()
    cfg = json.loads(Path(args.config).read_text(encoding='utf-8')); manager = Manager(cfg); Handler.manager = manager
    try: ThreadingHTTPServer(('127.0.0.1',10041),Handler).serve_forever()
    finally: manager.close()

if __name__ == '__main__': main()
