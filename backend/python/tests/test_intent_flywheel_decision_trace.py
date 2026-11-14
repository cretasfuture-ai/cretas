"""意图飞轮 (INTENT-DISTILL-20261111): Python 决策痕迹随识别结果带回 Java (decisionTrace)。

跑在真实入口 POST /api/ai/intent/match 上 (真 Orchestrator + 真 DecisionMatcher, 只换掉模型 IO 与库),
证明: 每次识别都带回 optionSetHash; 记录点 (Luna / 退路 / 旧出口) 记进同一份痕迹, 子任务里记的也看得见;
没有打开痕迹的调用方 (定时任务 / 评测) 记录函数什么都不做。
"""
import asyncio
import json
import re

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from ai import api, legacy_exit_metrics, registry
from ai.config import AIConfig
from ai.db import IntentSnapshot
from ai.matcher import candidates
from ai.matcher.candidates import BlendRetriever
from ai.matcher.decision import DecisionMatcher
from ai.orchestrator import Orchestrator

ROW = {'id': '1', 'intent_code': 'MATERIAL_BATCH_QUERY', 'intent_name': '原料库存查询',
       'description': '查询原料库存', 'keywords': '["库存"]', 'intent_category': 'MATERIAL',
       'business_type': 'FACTORY', 'factory_id': None, 'is_active': True, 'similarity': .9}


class _Connection:
    async def fetch(self, sql, *args):
        return [ROW] if 'FROM ai_intent_configs' in sql else []


class _Pool:
    def acquire(self):
        class Context:
            async def __aenter__(self):
                return _Connection()

            async def __aexit__(self, *args):
                pass
        return Context()


def _app(monkeypatch, outage=False):
    async def embedding(_query):
        return [0.0] * 768
    monkeypatch.setattr(candidates, 'get_embedding_cached', embedding)
    monkeypatch.setattr(api, 'get_current_snapshot', lambda: IntentSnapshot(rows=[ROW]))
    monkeypatch.setattr(registry, 'get_registry', lambda: registry.RegistrySnapshot())
    dec = DecisionMatcher()

    async def model(payload, **kwargs):
        if outage:
            raise OSError('local IO test outage')
        if payload['max_tokens'] == 32:
            return {'choices': [{'message': {'content': json.dumps({'purpose': 'CURRENT'})}}]}
        if payload['max_tokens'] != 1:
            return {'choices': [{'message': {'content': json.dumps({'candidates': ['MATERIAL_BATCH_QUERY']})}}]}
        options = payload['messages'][1]['content']
        letter = re.search(r'^([A-T])\. MATERIAL_BATCH_QUERY：', options, re.M).group(1)
        return {'choices': [{'logprobs': {'content': [{'top_logprobs': [{'token': letter, 'logprob': 0.0}]}]}}]}
    monkeypatch.setattr(dec, '_call', model)
    retriever = BlendRetriever(_Pool())
    app = FastAPI()

    @app.middleware('http')
    async def local_auth(request: Request, call_next):
        request.state.auth_method = 'internal'
        request.state.factory_id = 'F006'
        return await call_next(request)
    app.include_router(api.router)
    app.state.ai_orchestrator = Orchestrator(
        retriever, None, None, decision_matcher=dec, candidate_retriever=retriever,
        config=AIConfig(decision_business_types='FACTORY', decision_special_options=True, decision_fallback='legacy'))
    return app


def _post(app, query):
    response = TestClient(app).post('/api/ai/intent/match', json={
        'query': query, 'factoryId': 'F006', 'businessType': 'FACTORY', 'userId': '1686', 'role': 'operator',
        'history': []}, headers={'X-Factory-Id': 'F006'})
    assert response.status_code == 200 and response.json()['success'] is True
    return response.json()['data']


def test_real_match_entry_returns_decision_trace_with_option_set_hash(monkeypatch):
    data = _post(_app(monkeypatch), '请查当前原料的可用库存')
    assert data['bestMatch']['intentCode'] == 'MATERIAL_BATCH_QUERY'   # 阳性: 识别本身不受影响
    trace = data['decisionTrace']
    assert trace is not None
    assert trace['optionSetHash'] == api.option_set_hash([ROW])
    assert trace['optionSetHash'].startswith('os-sha256:')


def test_real_match_entry_trace_records_where_the_decision_fell_back(monkeypatch):
    data = _post(_app(monkeypatch, outage=True), '请查当前原料的可用库存')
    trace = data['decisionTrace']
    # 模型不可用: 说话意图判不了, 决策层停下来追问 (不猜)。这条路不经 Luna / 退路 / 旧出口, 痕迹只有候选摘要;
    # Java 侧据 timingMs.requestPurposeUnavailable 记来源 PY_UNAVAILABLE (FlywheelDecisionAnalysis)。
    assert data['bestMatch'] is None
    assert data['timingMs']['requestPurposeUnavailable'] == 1
    assert trace == {'optionSetHash': api.option_set_hash([ROW])}


def test_option_set_hash_changes_with_the_candidate_set_and_ignores_order():
    a = {'intent_code': 'A'}
    b = {'intent_code': 'B'}
    assert api.option_set_hash([a, b]) == api.option_set_hash([b, a])
    assert api.option_set_hash([a, b]) != api.option_set_hash([a])


def test_recorders_write_into_the_open_trace_including_child_tasks():
    async def run():
        trace = legacy_exit_metrics.start_decision_trace()
        legacy_exit_metrics.record_luna_request()

        async def child():
            legacy_exit_metrics.record_luna_outcome('timeout', 'answered')
            legacy_exit_metrics.record_fallback('combo', 'answered', {'luna': 'timeout'})
        await asyncio.create_task(child())
        legacy_exit_metrics.record_legacy_exit(None, 'F006', 'decision_not_enabled', observe_alarm=False)
        return trace
    trace = asyncio.run(run())
    assert trace['luna'] == 'fallback'
    assert trace['lunaFallbackReason'] == 'timeout'
    assert trace['fallback'] == {'hop': 'combo', 'outcome': 'answered', 'failures': {'luna': 'timeout'}}
    assert trace['legacyExit']['reason'] == 'decision_not_enabled'


def test_recorders_are_no_ops_without_an_open_trace():
    async def run():
        legacy_exit_metrics.record_luna_request()
        legacy_exit_metrics.note_trace('x', 1)
        return legacy_exit_metrics.get_decision_trace()
    assert asyncio.run(run()) is None


def test_attach_does_not_overwrite_and_survives_bad_results():
    class R:
        decisionTrace = {'kept': True}
    r = R()
    api.attach_decision_trace(r, {'new': 1})
    assert r.decisionTrace == {'kept': True}
    api.attach_decision_trace(None, {'new': 1})   # 不抛


@pytest.mark.parametrize('field', ['decisionTrace'])
def test_dto_carries_the_field(field):
    from ai.dto import IntentMatchResultDto
    assert field in IntentMatchResultDto.model_fields
