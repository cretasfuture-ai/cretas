"""说话意图被判 UNRESOLVED 时, 决策层已算好的按钮澄清不能被纯文字追问盖掉 (2026-11-18, 线上探针 clarify_then_pick_1)。

事故: #1824 把 SLOT.DECISION 池里的 qwen3.7-flash 换成 qwen3.8-flash 之后, 目的分类 (request_purpose, max_tokens=32)
对「这个客户的量给我弄一下」从 CURRENT 变成 UNRESOLVED (prod 本机同 prompt 各 6/6: 3.8 全 UNRESOLVED, 3.7 全 CURRENT)。
orchestrator 在 purpose != CURRENT 时直接返回「您是在讨论操作方法…」文字, Luna / 读概率已经算出的 >= 2 个按钮被丢掉,
Java 侧拿不到候选 ⇒ NEED_CLARIFICATION 变成纯文字, 用户没法点选。

桩的形状取自 prod 实测: 目的输出就是 qwen3.8-flash 的原样串 '{\\n  "purpose": "UNRESOLVED"\\n}' (带换行缩进)。
只桩模型 IO; 走真实的 /api/ai/intent/match 入口。
"""
import json
import re

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from ai import api, registry
from ai.config import AIConfig
from ai.db import IntentSnapshot
from ai.matcher import candidates
from ai.matcher.candidates import BlendRetriever
from ai.matcher.decision import DecisionMatcher
from ai.orchestrator import Orchestrator


def _row(code, name, desc):
    return {'id': code, 'intent_code': code, 'intent_name': name, 'description': desc, 'keywords': '[]',
            'intent_category': 'CUSTOMER', 'business_type': 'FACTORY', 'factory_id': None, 'is_active': True,
            'similarity': .9}


ROWS = [_row('CUSTOMER_QUERY', '客户查询', '查询客户信息'), _row('CUSTOMER_UPDATE', '客户修改', '修改客户资料')]

# qwen3.8-flash 在 prod 上对该句的原样输出 (含换行与缩进)
QWEN38_UNRESOLVED = '{\n  "purpose": "UNRESOLVED"\n}'


class _Conn:
    async def fetch(self, sql, *args):
        return ROWS if 'FROM ai_intent_configs' in sql else []


class _Pool:
    def acquire(self):
        class Ctx:
            async def __aenter__(self):
                return _Conn()

            async def __aexit__(self, *a):
                pass
        return Ctx()


def _run(monkeypatch, weights):
    """weights: {选项代码: 首 token 概率}; 返回 /intent/match 的 data。"""
    async def embedding(_q):
        return [0.0] * 768
    monkeypatch.setattr(candidates, 'get_embedding_cached', embedding)
    monkeypatch.setattr(api, 'get_current_snapshot', lambda: IntentSnapshot(rows=ROWS))
    monkeypatch.setattr(registry, 'get_registry', lambda: registry.RegistrySnapshot())
    dec = DecisionMatcher()

    async def model(payload, **kwargs):
        if payload['max_tokens'] == 32:  # 目的分类
            return {'choices': [{'message': {'content': QWEN38_UNRESOLVED}}]}
        if payload['max_tokens'] != 1:  # 粗选
            return {'choices': [{'message': {'content': json.dumps({'candidates': [r['intent_code'] for r in ROWS]})}}]}
        text = payload['messages'][1]['content']
        top = []
        for letter, code in re.findall(r'^([A-T])\. (\w+)：', text, re.M):
            if code in weights:
                top.append({'token': letter, 'logprob': __import__('math').log(weights[code])})
        return {'choices': [{'logprobs': {'content': [{'top_logprobs': top}]}}]}
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
    r = TestClient(app).post('/api/ai/intent/match', json={
        'query': '这个客户的量给我弄一下', 'factoryId': 'F006', 'businessType': 'FACTORY', 'userId': '1',
        'role': 'factory_super_admin', 'history': []}, headers={'X-Factory-Id': 'F006'})
    assert r.status_code == 200 and r.json()['success'] is True
    return r.json()['data']


def test_unresolved_purpose_keeps_decision_buttons(monkeypatch):
    # 决策层: 「需要先问清楚」0.7 压过两个真实候选 (0.15 / 0.1) ⇒ 先问, 出 >= 2 个按钮
    data = _run(monkeypatch, {'CLARIFY': 0.7, 'CUSTOMER_QUERY': 0.15, 'CUSTOMER_UPDATE': 0.1})
    assert data['requiresConfirmation'] is True
    codes = [c['intentCode'] for c in data['topCandidates']]
    assert len(codes) >= 2 and set(codes) == {'CUSTOMER_QUERY', 'CUSTOMER_UPDATE'}, data['topCandidates']
    assert not data.get('clarificationQuestion'), '不能是「您是在讨论操作方法」的纯文字追问'
    assert data['timingMs']['purposeUnresolvedButtons'] == 1 and data['timingMs']['decisionClarify'] == 1


def test_unresolved_purpose_still_text_when_decision_says_none(monkeypatch):
    # 阴性对照: 决策层也说「以上都不是」(真·说不清) ⇒ 仍是纯文字追问, 不凭空造按钮
    data = _run(monkeypatch, {'NONE': 0.8, 'CUSTOMER_QUERY': 0.1, 'CUSTOMER_UPDATE': 0.05})
    assert data['requiresConfirmation'] is True
    assert '您是在讨论操作方法' in (data.get('clarificationQuestion') or '')
    assert data['timingMs']['requestPurposeUnresolved'] == 1
    assert not data['timingMs'].get('purposeUnresolvedButtons')
