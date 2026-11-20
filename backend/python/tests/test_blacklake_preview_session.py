import json
import httpx
import jwt
import pytest
from blacklake import preview_session

KEY='synthetic-local-preview-signing-key-only'


def setup(monkeypatch,*,full=True,claim_factory='BLACKLAKE_POC_TEST',issued_at=None):
    config={'tenant_id':'BLACKLAKE_POC_TEST','username':'synthetic','password':'synthetic-only','jwt_secret':KEY}
    monkeypatch.setenv('LOCALAPPDATA','synthetic')
    monkeypatch.setattr(preview_session,'load_local_secret',lambda p:config)
    claims={'factoryId':claim_factory,'userId':33,'role':'factory_super_admin'}
    if issued_at is not None:claims['iat']=issued_at
    token=jwt.encode(claims,KEY,algorithm='HS256')
    calls=[];client_type=httpx.Client
    def handle(request):
        calls.append(request.url)
        if request.url.path.endswith('/unified-login'):
            return httpx.Response(200,json={'success':True,'data':{'factoryId':config['tenant_id'],'userId':33,
                'role':'factory_super_admin','accessToken':token}})
        return httpx.Response(200,json={'success':True,'data':{'factoryId':config['tenant_id'],'userId':33,'unscopedCostAllowed':full}})
    monkeypatch.setattr(preview_session.httpx,'Client',lambda **kwargs:client_type(transport=httpx.MockTransport(handle),**kwargs))
    return calls


def test_preview_returns_real_verified_test_identity_without_credentials(monkeypatch):
    calls=setup(monkeypatch)
    value=preview_session.session()
    assert value['success'] is True and value['data']['userId']==33
    assert 'password' not in json.dumps(value) and 'jwt_secret' not in json.dumps(value)
    assert len(calls)==2 and all(url.host=='127.0.0.1' and url.port==11210 for url in calls)


def test_preview_does_not_return_session_if_cost_authority_denies(monkeypatch):
    setup(monkeypatch,full=False)
    with pytest.raises(RuntimeError,match='TEST_COST_AUTHORITY_REQUIRED'):preview_session.session()


def test_preview_rejects_signed_token_for_another_factory(monkeypatch):
    calls=setup(monkeypatch,claim_factory='FOREIGN')
    with pytest.raises(RuntimeError,match='TEST_SESSION_TOKEN_MISMATCH'):preview_session.session()
    assert len(calls)==1


def test_preview_waits_for_verified_recent_issued_at_without_relaxing_receiver(monkeypatch):
    setup(monkeypatch,issued_at=101)
    monkeypatch.setattr(preview_session.time,'time',lambda:100.5)
    waited=[];monkeypatch.setattr(preview_session.time,'sleep',waited.append)
    assert preview_session.session()['success'] is True
    assert waited==[0.55]


def test_preview_rejects_large_clock_skew_before_authority_request(monkeypatch):
    calls=setup(monkeypatch,issued_at=110)
    monkeypatch.setattr(preview_session.time,'time',lambda:100)
    with pytest.raises(RuntimeError,match='TEST_SESSION_CLOCK_SKEW'):preview_session.session()
    assert len(calls)==1


def test_preview_can_read_explicit_existing_encrypted_config_without_copying_it(monkeypatch):
    from pathlib import Path
    setup(monkeypatch)
    seen=[]
    original=preview_session.load_local_secret
    monkeypatch.setattr(preview_session,'load_local_secret',lambda p:(seen.append(p),original(p))[1])
    path=Path('synthetic-resolved-package-config.dpapi')
    assert preview_session.session(path)['success'] is True
    assert seen==[path]
