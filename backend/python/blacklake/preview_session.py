"""Local dev broker: obtain a real Cretas test session without browser passwords.

Only Vite's guarded loopback development middleware invokes this command. Its
stdout is captured in memory and returned as a no-store session response.
"""
import json
import os
from pathlib import Path

import httpx
import jwt

from .secrets import load_local_secret


def session():
    config=load_local_secret(Path(os.environ['LOCALAPPDATA'])/'Cretas/Blacklake/smartbi-test.dpapi')
    if config.get('tenant_id')!='BLACKLAKE_POC_TEST':raise RuntimeError('TEST_FACTORY_REQUIRED')
    with httpx.Client(timeout=12,trust_env=False,follow_redirects=False) as client:
        result=client.post('http://127.0.0.1:11210/api/mobile/auth/unified-login',
                           json={k:config[k] for k in ('username','password')})
        result.raise_for_status();body=result.json();data=body.get('data') or {}
        if not body.get('success') or data.get('factoryId')!=config['tenant_id'] or data.get('role')!='factory_super_admin':
            raise RuntimeError('TEST_SESSION_IDENTITY_MISMATCH')
        token=data.get('accessToken') or data.get('token')
        claims=jwt.decode(token,config['jwt_secret'],algorithms=['HS256'],options={'verify_iat':False})
        if (claims.get('factoryId')!=config['tenant_id'] or claims.get('userId')!=data.get('userId')
                or claims.get('role')!=data['role'] or type(claims.get('userId')) is not int):
            raise RuntimeError('TEST_SESSION_TOKEN_MISMATCH')
        authority=client.post('http://127.0.0.1:11210/api/mobile/BLACKLAKE_POC_TEST/smart-bi/cost-product-access',
                              json={'productIds':[]},headers={'Authorization':'Bearer '+token})
        authority.raise_for_status();verified=authority.json();scope=verified.get('data') or {}
        if (verified.get('success') is not True or scope.get('factoryId')!=config['tenant_id']
                or scope.get('userId')!=claims.get('userId') or scope.get('unscopedCostAllowed') is not True):
            raise RuntimeError('TEST_COST_AUTHORITY_REQUIRED')
        return {'success':True,'data':{'accessToken':token,'factoryId':config['tenant_id'],
                'userId':data['userId'],'role':data['role'],'username':data.get('username','test-preview'),
                'permissions':data.get('permissions',[])}}


if __name__=='__main__':
    try:print(json.dumps(session(),ensure_ascii=False))
    except Exception:raise SystemExit(1) from None
