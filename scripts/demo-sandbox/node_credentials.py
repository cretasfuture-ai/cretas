"""Short-lived credentials for the single fixed isolated demo actor."""
import base64
import hashlib
import hmac
import json
import secrets
import time

def issue_node_token(signing_key, now=None):
    if not isinstance(signing_key,str) or len(signing_key)<64: raise ValueError('independent node signing key required')
    issued=int(time.time() if now is None else now)
    encode=lambda value:base64.urlsafe_b64encode(value).rstrip(b'=').decode('ascii')
    claims={'userId':1309,'factoryId':'F006','username':'f006_admin','role':'factory_super_admin','sub':'f006_admin',
        'jti':secrets.token_urlsafe(24),'iat':issued,'exp':issued+86400}
    signing=encode(b'{"alg":"HS256","typ":"JWT"}')+'.'+encode(json.dumps(claims,separators=(',',':')).encode())
    return signing+'.'+encode(hmac.new(signing_key.encode(),signing.encode(),hashlib.sha256).digest())
