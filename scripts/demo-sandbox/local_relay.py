#!/usr/bin/env python3
"""Loopback-only relay for the F006 local demo; never exposes production credentials."""
from __future__ import annotations
import argparse, json, re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler
from urllib.error import HTTPError

MAX_BODY=16*1024*1024; ACTOR='1309'; PROD='https://admin.cretaceousfuture.com'
UUID=r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
class RelayError(RuntimeError): pass
class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self,*a): return None
class Relay:
    def __init__(self,cfg,opener=None):
        self.cfg=cfg; self.opener=opener or build_opener(ProxyHandler({}),NoRedirect()); self.manager='http://127.0.0.1:10041'
        if cfg.get('listen_host','127.0.0.1')!='127.0.0.1': raise RelayError('loopback only')
        if len(cfg.get('control_key',''))<32: raise RelayError('private control key required')
    def _json(self,url,headers=None,method='GET',body=None):
        req=Request(url,data=body,headers=headers or {},method=method)
        try:
            with self.opener.open(req,timeout=20) as r:
                data=r.read(MAX_BODY+1)
                if len(data)>MAX_BODY: raise RelayError('response too large')
                return r.status,dict(r.headers),data
        except HTTPError as e:
            data=e.read(MAX_BODY+1)
            if len(data)>MAX_BODY or 300<=e.code<400: raise RelayError('upstream response denied')
            return e.code,dict(e.headers),data
        except Exception as e: raise RelayError('upstream unavailable') from e
    def _caller(self,headers):
        h={k:v for k,v in headers.items() if k.lower() in {'authorization','cookie','accept'}}
        status,_,raw=self._json(PROD+'/api/mobile/auth/me',h)
        if status!=200: raise RelayError('caller authentication failed')
        try: value=json.loads(raw); value=value.get('data',value); user=value.get('factoryUser',value)
        except Exception: raise RelayError('caller authentication response invalid')
        if str(user.get('id',user.get('userId')))!='1309' or user.get('factoryId')!='F006': raise RelayError('caller does not own this sandbox')
        return h,user
    @staticmethod
    def _admin(user):
        if user.get('roleCode',user.get('role'))!='factory_super_admin' or user.get('username')!='f006_admin': raise RelayError('demo administrator required')
    @staticmethod
    def _data(response):
        status,_,raw=response
        try:
            value=json.loads(raw)
            if status!=200 or value.get('success') is not True or 'data' not in value: raise ValueError()
            return value['data']
        except (ValueError,TypeError,AttributeError): raise RelayError('upstream state unverified') from None
    def request(self,path,method='GET',headers=None,body=None):
        headers=headers or {}; parsed=urlsplit(path); route=parsed.path; suffix=parsed.query
        if parsed.scheme or parsed.netloc or parsed.fragment or re.search(r'%2f|%5c|%2e|\\|//',route,re.I) or any(p in {'.','..'} for p in route.split('/')): raise RelayError('ambiguous route denied')
        if route=='/api/mobile/auth/unified-login' and method=='POST':
            if suffix or len(body or b'')>8192: raise RelayError('invalid login request')
            try:
                obj=json.loads(body or b'{}')
                if obj.get('username')!='f006_admin' or set(obj)-{'username','password','clientType','rememberMe'}: raise RelayError('only f006_admin unified login allowed')
            except (ValueError,TypeError): raise RelayError('invalid login body')
            status,h,data=self._json(PROD+path,{'Content-Type':'application/json'},'POST',body)
            value=self._data((status,h,data)); token=value.get('token') or value.get('accessToken')
            if not isinstance(token,str) or not token: raise RelayError('login token missing')
            _,user=self._caller({'Authorization':'Bearer '+token});self._admin(user)
            return status,{k:v for k,v in h.items() if k.lower()!='set-cookie'},data
        caller,user=self._caller(headers)
        if route=='/api/mobile/auth/me' and method=='GET': return self._json(PROD+route+('?' + suffix if suffix else ''),caller)
        if method=='GET' and not suffix and route in {'/api/mobile/F006/permissions/current','/api/mobile/F006/canvas/user-module-access/1309'}: return self._json(PROD+route,caller)
        if route=='/api/mobile/F006/demo-sandbox/active-session' and method=='GET' and not suffix: return self._manager('/actors/1309/session',method,headers,body)
        prefix='/api/mobile/F006/demo-sandbox'
        if route==prefix+'/sessions' and method=='POST' and not suffix:
            self._admin(user)
            if len(body or b'')>8192: raise RelayError('control body too large')
            return self._manager('/sessions',method,headers,body)
        session=re.fullmatch(re.escape(prefix)+r'/sessions/('+UUID+r')',route)
        if session and method in {'GET','DELETE'} and not suffix: return self._manager('/sessions/'+session.group(1),method,headers,body)
        proxy=re.fullmatch(re.escape(prefix)+r'/sessions/('+UUID+r')/proxy(/api/mobile/F006/.+)',route)
        if proxy and method in {'GET','HEAD','POST','PUT','PATCH','DELETE'}:
            self._admin(user)
            return self._manager('/sessions/'+proxy.group(1)+'/proxy'+proxy.group(2)+('?' + suffix if suffix else ''),method,headers,body)
        if route.startswith(prefix+'/'): raise RelayError('unknown sandbox control route')
        self._admin(user)
        if route=='/api/mobile/F006/form-assistant/parse':
            if method!='POST' or suffix or len(body or b'')>65536: raise RelayError('parse requires bounded POST')
            return self._json(PROD+route,{'Content-Type':'application/json',**caller},method,body)
        if method not in {'GET','HEAD'}: raise RelayError('production writes are not supported')
        if not route.startswith('/api/mobile/F006/'): raise RelayError('production route denied')
        if self._data(self._manager('/actors/1309/session','GET',headers,None)) is not None: raise RelayError('business reads must use active sandbox')
        return self._json(PROD+route+('?' + suffix if suffix else ''),caller,method,None)
    def _manager(self,path,method,headers,body):
        target=self.manager+path
        actor=headers.get('X-Sandbox-Actor-Id','1309')
        if actor!='1309': raise RelayError('actor mismatch')
        control=self.cfg.get('control_key','')
        mh={'X-Sandbox-Control-Key':control,'X-Sandbox-Actor-Id':'1309'}
        mh.update({k:v for k,v in headers.items() if k.lower() in {'content-type','idempotency-key'}})
        if body is not None and not any(k.lower()=='content-type' for k in mh): mh['Content-Type']='application/json'
        return self._json(target,mh,method,body)
class Handler(BaseHTTPRequestHandler):
    relay=None
    def _handle(self):
        try:
            self.connection.settimeout(10)
            if self.client_address[0] not in {'127.0.0.1','::1'} or self.headers.get('Host','').split(':')[0] not in {'127.0.0.1','localhost'}: raise RelayError('local host required')
            origin=self.headers.get('Origin','')
            if origin:
                if origin not in {'http://127.0.0.1:5186','http://localhost:5186'}: raise RelayError('origin denied')
            if self.headers.get('Transfer-Encoding'): raise RelayError('chunked body denied')
            n=int(self.headers.get('Content-Length','0'))
            if n<0 or n>MAX_BODY: raise RelayError('body too large')
            body=self.rfile.read(n) if n else None
            if body is not None and len(body)!=n: raise RelayError('incomplete body')
            status,h,data=self.relay.request(self.path,self.command,self.headers,body)
            self.send_response(status); self.send_header('Cache-Control','no-store'); self.send_header('Content-Type',h.get('Content-Type','application/json')); self.send_header('Content-Length',str(len(data))); self.end_headers()
            if self.command!='HEAD': self.wfile.write(data)
        except Exception:
            data=json.dumps({'success':False,'message':'本机演示请求未完成，请核对账号与隔离状态'},ensure_ascii=False).encode(); self.send_response(403); self.send_header('Content-Type','application/json'); self.send_header('Cache-Control','no-store'); self.send_header('Content-Length',str(len(data))); self.end_headers()
            if self.command!='HEAD': self.wfile.write(data)
    do_GET=do_POST=do_PUT=do_PATCH=do_DELETE=do_HEAD=_handle
    def log_message(self,*a): pass
def main():
    p=argparse.ArgumentParser(); p.add_argument('--config',required=True); a=p.parse_args(); cfg=json.load(open(a.config,encoding='utf-8')); Handler.relay=Relay(cfg); ThreadingHTTPServer(('127.0.0.1',10050),Handler).serve_forever()
if __name__=='__main__': main()
