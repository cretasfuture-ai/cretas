import json

import httpx
import pytest
from fastapi.testclient import TestClient

from blacklake.public_preview import create_app

ORIGIN = 'https://test.cretaceousfuture.com'


class Broker:
    async def get(self, invalidate=False):
        return 'synthetic-server-only-authority'


@pytest.fixture
def preview(tmp_path):
    (tmp_path / 'blacklake-smartbi.html').write_text('standalone preview', encoding='utf8')
    (tmp_path / 'assets').mkdir()
    (tmp_path / 'assets/app.js').write_text('preview code', encoding='utf8')
    calls = []

    def upstream(request):
        calls.append(request)
        return httpx.Response(200, json={'success': True, 'data': {'items': [1], 'accessToken': 'must-never-leak',
            'nested': {'jwt_secret': 'must-never-leak'}, 'totalTokens': 55}})

    app = create_app(tmp_path, ORIGIN, broker=Broker(), transport=httpx.MockTransport(upstream))
    with TestClient(app) as client:
        yield client, calls


def test_anonymous_entry_and_session_return_no_credential(preview):
    client, calls = preview
    assert client.get('/').text == 'standalone preview'
    response = client.post('/__blacklake/preview-session', headers={'Origin': ORIGIN})
    assert response.json()['data'] == {'factoryId': 'BLACKLAKE_POC_TEST', 'previewOnly': True}
    assert response.headers['x-robots-tag'].startswith('noindex')
    assert not calls


def test_history_summary_parameter_is_narrow_and_other_filters_stay_blocked(preview):
    client,calls=preview
    path='/smartbi-api/api/smartbi/blacklake/'
    response=client.get(path+'period-reports?summaries_only=true')
    assert response.status_code==200 and calls[-1].url.params['summaries_only']=='true'
    for resource in ('analysis?summaries_only=true','period-reports?summaries_only=false','period-reports?summaries_only=true&summaries_only=false','period-reports?tenant_id=OTHER'):
        assert client.get(path+resource).status_code==400
    assert len(calls)==1


def test_browser_auth_is_discarded_and_only_fixed_local_analytical_upstream_is_used(preview):
    client, calls = preview
    response = client.get('/smartbi-api/api/smartbi/blacklake/complete-cost-analysis',
                          headers={'Authorization': 'Bearer visitor-value', 'Cookie': 'other=secret'})
    assert response.status_code == 200
    assert 'must-never-leak' not in response.text
    assert response.json()['data']['totalTokens'] == 55
    assert len(calls) == 1
    assert str(calls[0].url) == 'http://127.0.0.1:8095/api/smartbi/blacklake/complete-cost-analysis'
    assert calls[0].headers['authorization'] == 'Bearer synthetic-server-only-authority'
    assert 'cookie' not in calls[0].headers


@pytest.mark.parametrize('path', ['/api/mobile/auth/unified-login', '/__blacklake/local-session', '/.env',
    '/index.html', '/assets/private.js.map', '/smartbi-api/api/mobile/F006/batches',
    '/smartbi-api/api/smartbi/blacklake/alerts/local-review'])
def test_unrelated_routes_sources_and_auth_material_are_inaccessible(preview, path):
    client, calls = preview
    assert client.get(path).status_code in {403, 404, 405}
    assert not calls


def test_mutation_and_cross_origin_requests_never_reach_upstream(preview):
    client, calls = preview
    path = '/smartbi-api/api/smartbi/blacklake/'
    assert client.post(path + 'alerts/one/followup', json={}, headers={'Origin': ORIGIN}).status_code == 403
    assert client.post(path + 'sections/followup', json={}, headers={'Origin': 'https://other.example'}).status_code == 403
    assert client.delete(path + 'analysis').status_code == 405
    assert not calls


def test_tenant_injection_and_large_payloads_are_blocked_and_queue_force_is_removed(preview):
    client, calls = preview
    path = '/smartbi-api/api/smartbi/blacklake/'
    assert client.get(path + 'analysis?factoryId=FOREIGN').status_code == 400
    assert client.post(path + 'period-reports', json={'factory_id': 'FOREIGN'}, headers={'Origin': ORIGIN}).status_code == 400
    assert client.post(path + 'sections/followup', content=b'x' * 32769, headers={'Origin': ORIGIN}).status_code == 413
    assert not calls
    response = client.post(path + 'period-reports', json={'force': True, 'start_date': '2026-11-01', 'end_date': '2026-11-02'}, headers={'Origin': ORIGIN})
    assert response.status_code == 200
    assert json.loads(calls[0].content)['force'] is False


def test_only_versioned_assets_are_served(preview):
    client, calls = preview
    assert client.get('/assets/app.js').text == 'preview code'
    assert client.get('/assets/%2e%2e/blacklake-smartbi.html').status_code == 404
    assert not calls


@pytest.mark.parametrize('extra', [
    {'period': {'start_date': '2026-11-01', 'end_date': '2026-11-02', 'factory_id': 'FOREIGN'}},
    {'history': [{'role': 'user', 'content': 'hello', 'url': 'http://foreign'}]},
    {'tenantId': 'FOREIGN'}, {'token': 'foreign'}, {'unknown': {'source_factory_id': 'FOREIGN'}},
])
def test_strict_route_and_nested_dtos_reject_unknown_scope_fields(preview, extra):
    client, calls = preview
    payload = {'section_id': 'material', 'run_id': 'run1', 'question': '材料成本为什么高？', **extra}
    assert client.post('/smartbi-api/api/smartbi/blacklake/sections/followup', json=payload, headers={'Origin': ORIGIN}).status_code == 400
    assert not calls


def test_strict_complete_followup_and_contact_redaction(preview):
    from blacklake.public_preview import clean_response
    client, calls = preview
    path = '/smartbi-api/api/smartbi/blacklake/complete-cost-analysis/followup'
    assert client.post(path, json={'fact_id': 'one', 'run_id': 'run', 'question': '为什么高？'}, headers={'Origin': ORIGIN}).status_code == 200
    assert len(calls) == 1
    assert clean_response({'username': 'person', 'workers': [{'wage': 999}], 'laborCost': 123,
                           'note': '联系13812345678或者test@example.com'}) == {'laborCost': 123, 'note': '联系[联系方式已隐藏]或者[联系方式已隐藏]'}
    assert clean_response({'employeeList': [{'name': '张三', 'idCard': '110101199001011234', 'address': '住所'}],
                           'contactName': '张三', 'note': '+86 138-1234-5678', 'laborCost': 123}) == {
        'note': '[联系方式已隐藏]', 'laborCost': 123}
    assert clean_response({'ownerName': '张三', 'reviewerName': '李四', 'createdByName': '王五',
                           'note': '座机021-12345678，微信wx_123', 'laborCost': 123}) == {
        'note': '座机[联系方式已隐藏]，[联系方式已隐藏]', 'laborCost': 123}
    assert clean_response({'echartsOption': {'series': [{'name': '材料费', 'data': [123]}]},
                           'name': '张三'}) == {'echartsOption': {'series': [{'name': '材料费', 'data': [123]}]}}


def test_pending_backpressure_applies_under_share_mount_without_calling_upstream(tmp_path):
    import asyncio
    from fastapi import FastAPI

    async def check():
        release = asyncio.Event()
        entered = asyncio.Event()
        calls = []
        async def upstream(request):
            calls.append(request)
            if len(calls) == 8:
                entered.set()
            await release.wait()
            return httpx.Response(200, json={'success': True})
        app = FastAPI()
        app.mount('/preview/synthetic', create_app(tmp_path, ORIGIN, broker=Broker(), transport=httpx.MockTransport(upstream)))
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://preview') as client:
            path = '/preview/synthetic/smartbi-api/api/smartbi/blacklake/analysis'
            tasks = [asyncio.create_task(client.get(path)) for _ in range(8)]
            await asyncio.wait_for(entered.wait(), 5)
            response = await client.get(path)
            assert response.status_code == 429 and response.headers['retry-after'] == '3'
            assert len(calls) == 8
            release.set()
            assert all(r.status_code == 200 for r in await asyncio.gather(*tasks))
            assert (await client.get(path)).status_code == 200
            assert (await client.get('/smartbi-api/api/smartbi/blacklake/analysis')).status_code == 404
    asyncio.run(check())
