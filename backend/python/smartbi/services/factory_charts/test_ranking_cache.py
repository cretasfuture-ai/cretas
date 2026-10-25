import json
import pytest
from . import ranking


@pytest.mark.asyncio
async def test_ai_cache_is_bound_to_factory_role_and_exact_computed_snapshot(monkeypatch):
    ranking._CACHE.clear(); calls=[]
    async def provider(slot,payload):
        calls.append(payload)
        return {'choices':[{'message':{'content':json.dumps([{'id':'a','reason_code':'observe'}])}}]}
    monkeypatch.setattr('common.llm_router.call_chain',provider)
    items=[{'id':'a','metric':'产量','domain':'生产','priority':'required','chart':{'chartType':'line'},'kpis':[{'value':100}]}]
    _,first=await ranking.rank(items,True,'F006:admin')
    _,second=await ranking.rank(items,True,'F006:admin')
    assert first['mode']==second['mode']=='ai' and second['cacheHit'] is True and len(calls)==1
    await ranking.rank(items,True,'F001:admin')
    await ranking.rank(items,True,'F006:finance_manager')
    items[0]['kpis'][0]['value']=200
    await ranking.rank(items,True,'F006:admin')
    assert len(calls)==4
    ranking._CACHE.clear()


@pytest.mark.asyncio
async def test_ai_cannot_assign_flow_tracing_reason_to_simple_trend(monkeypatch):
    async def provider(*args):
        return {'choices':[{'message':{'content':'[{"id":"a","reason_code":"trace"}]'}}]}
    monkeypatch.setattr('common.llm_router.call_chain',provider)
    _,mode=await ranking.rank([{'id':'a','metric':'产量','domain':'生产','priority':'required','chart':{'chartType':'line'}}],True)
    assert mode['mode']=='rule' and '未经AI排序' in mode['reason']
