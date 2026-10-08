"""Match API -> actual chooser prompt wiring. Only model/embedding/DB IO is stubbed.

This contract verifies the prompt reaches the real entry. Live frozen-suite and
live prompt-removal mutation measurements establish classification performance.
"""
import json
import re

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient
import pytest

from ai import api, registry
from ai.config import AIConfig
from ai.db import IntentSnapshot
from ai.matcher import candidates
from ai.matcher.candidates import BlendRetriever
from ai.matcher.decision import DecisionMatcher, STRICT_SYSTEM, parse_request_purpose
from ai.orchestrator import Orchestrator

ROW={'id':'1','intent_code':'MATERIAL_BATCH_QUERY','intent_name':'原料库存查询',
     'description':'查询原料库存','keywords':'["库存"]','intent_category':'MATERIAL',
     'business_type':'FACTORY','factory_id':None,'is_active':True,'similarity':.9}

class Connection:
    async def fetch(self,sql,*args):
        return [ROW] if 'FROM ai_intent_configs' in sql else []

class Pool:
    def acquire(self):
        class Context:
            async def __aenter__(self):return Connection()
            async def __aexit__(self,*args):pass
        return Context()

@pytest.mark.parametrize('query,model_choice,matched',[
    ('请介绍仓库页面上的筛选控件怎么使用','NONE',False),
    ('等新品到货以后可能需要预留一些材料，现在先讨论方案','CLARIFY',False),
    ('请查当前原料的可用库存','MATERIAL_BATCH_QUERY',True),
    ('为安排下一班生产，需要了解目前还剩多少原料','MATERIAL_BATCH_QUERY',True),
    ('请求识别服务暂时不可用时应停止业务处理','unavailable',False),
    ('请求识别服务返回无效结构时应停止业务处理','malformed',False),
])
def test_request_purpose_in_real_match_entry(monkeypatch,query,model_choice,matched):
    observed=[]
    async def embedding(_query):return [0.0]*768
    monkeypatch.setattr(candidates,'get_embedding_cached',embedding)
    monkeypatch.setattr(api,'get_current_snapshot',lambda:IntentSnapshot(rows=[ROW]))
    monkeypatch.setattr(registry,'get_registry',lambda:registry.RegistrySnapshot())
    dec=DecisionMatcher()
    async def model(payload,**kwargs):
        observed.append(payload)
        if payload['max_tokens']==32:
            if model_choice=='unavailable':raise OSError('local IO test outage')
            if model_choice=='malformed':return {'choices':[None]}
            system=payload['messages'][0]['content']
            assert '提到某功能不等于要求现在调用它' in system
            assert '间接表达的真实查询也要理解' in system
            assert '界面位置' in system and '尚未成立的条件' in system
            purpose={'NONE':'INFORMATION_ONLY','CLARIFY':'UNRESOLVED'}.get(model_choice,'CURRENT')
            return {'choices':[{'message':{'content':json.dumps({'purpose':purpose})}}]}
        if payload['max_tokens']!=1:
            return {'choices':[{'message':{'content':json.dumps({'candidates':['MATERIAL_BATCH_QUERY']})}}]}
        system=payload['messages'][0]['content']
        # External model IO contract: distinguish present requests from instructions
        # and untriggered plans, while keeping indirect/contextual requests valid.
        assert system==STRICT_SYSTEM  # Current business requests keep the old chooser.
        options=payload['messages'][1]['content']
        # The legacy chooser selects a related business function for tutorials too.
        # Removing the purpose guard must expose that real wrong-bestMatch behavior.
        letter=re.search(r'^([A-T])\. MATERIAL_BATCH_QUERY：',options,re.M).group(1)
        return {'choices':[{'logprobs':{'content':[{'top_logprobs':[{'token':letter,'logprob':0.0}]}]}}]}
    monkeypatch.setattr(dec,'_call',model)
    retriever=BlendRetriever(Pool())
    app=FastAPI()
    @app.middleware('http')
    async def local_auth(request:Request,call_next):
        request.state.auth_method='internal';request.state.factory_id='F006'
        return await call_next(request)
    app.include_router(api.router)
    app.state.ai_orchestrator=Orchestrator(retriever,None,None,decision_matcher=dec,
        candidate_retriever=retriever,config=AIConfig(decision_business_types='FACTORY',decision_special_options=True))
    response=TestClient(app).post('/api/ai/intent/match',json={
        'query':query,'factoryId':'F006','businessType':'FACTORY','userId':'1686','role':'operator','history':[]},
        headers={'X-Factory-Id':'F006'})
    assert response.status_code==200 and response.json()['success'] is True
    data=response.json()['data']
    assert bool(data['bestMatch'])==matched
    if not matched:
        assert any(p['max_tokens']==32 for p in observed)
    if model_choice=='NONE':
        assert data['timingMs']['decisionNone']==1
        assert data['requiresConfirmation'] is True
        assert data['clarificationQuestion']
    elif model_choice=='CLARIFY':assert data['requiresConfirmation'] is True
    elif model_choice in ('unavailable','malformed'):
        assert data['requiresConfirmation'] is True
        assert data['timingMs']['requestPurposeUnavailable']==1
        assert '暂时无法' in data['clarificationQuestion']
        assert not any(p['max_tokens']==1 for p in observed)
    else:
        assert data['bestMatch']['intentCode']=='MATERIAL_BATCH_QUERY'
        assert data['requiresConfirmation'] is False


@pytest.mark.parametrize('raw',['{}','{"purpose":"CURRENT","confidence":1}','{"purpose":"unknown"}','null','garbled'])
def test_unknown_purpose_is_not_current(raw):
    assert parse_request_purpose(raw) is None
    assert parse_request_purpose('{"purpose":"CURRENT"}').value=='CURRENT'
