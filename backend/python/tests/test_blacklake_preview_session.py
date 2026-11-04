import json
import httpx
import jwt
import pytest
from blacklake import preview_session

KEY='synthetic-local-preview-signing-key-only'


def setup(monkeypatch,*,full=True,claim_factory='BLACKLAKE_POC_TEST'):
    config={'tenant_id':'BLACKLAKE_POC_TEST','username':'synthetic','password':'synthetic-only','jwt_secret':KEY}
    monkeypatch.setenv('LOCALAPPDATA','synthetic')
    monkeypatch.setattr(preview_session,'load_local_secret',lambda p:config)
    token=jwt.encode({'factoryId':claim_factory,'userId':33,'role':'factory_super_admin'},KEY,algorithm='HS256')
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
