"""Joint intent must constrain the real provider catalog, not bypass the LLM."""
import json
import pytest
from . import conversation


@pytest.mark.asyncio
@pytest.mark.parametrize(('question', 'family'), [
    ('关联生产、检验和成本做多表分析', 'production.inspection_fused'),
    ('Compare production quality and cost across tables', 'production.inspection_fused'),
    ('把生产、良品率与成本放一起看', 'production.fused'),
])
async def test_joint_planner_excludes_standalone_charts_from_provider_catalog(monkeypatch, question, family):
    seen = []

    async def provider(_slot, payload, **kwargs):
        catalog = json.loads(payload['messages'][1]['content'])['catalog']
        seen.extend(catalog)
        content = json.dumps({'selected_ids': [catalog[0]['id']]})
        assert kwargs['content_validator'](content) is None
        return {'choices': [{'message': {'content': content}}]}

    monkeypatch.setattr('common.llm_router.call_chain', provider)
    catalog = [
        {'id': 'standalone-first', 'familyId': 'production.output', 'unit': '盒'},
        {'id': 'other-joint', 'familyId': 'production.fused' if family.endswith('inspection_fused') else 'production.inspection_fused', 'unit': '盒'},
        {'id': 'verified-joint', 'familyId': family, 'unit': '盒'},
    ]
    selected, mode = await conversation.plan(question, catalog, factory_id='F006')
    assert selected == ['verified-joint'] and mode['mode'] == 'ai'
    assert len(seen) == 1 and seen[0]['familyId'] == family
    assert len(catalog) == 3, 'Public catalog must remain intact'


@pytest.mark.asyncio
async def test_joint_planner_rejects_provider_selecting_excluded_standalone_alias(monkeypatch):
    import hashlib
    excluded = 'chart_' + hashlib.sha256(b'standalone-first').hexdigest()[:16]

    async def provider(_slot, _payload, **kwargs):
        content = json.dumps({'selected_ids': [excluded]})
        assert kwargs['content_validator'](content) is not None
        return {'choices': [{'message': {'content': content}}]}

    monkeypatch.setattr('common.llm_router.call_chain', provider)
    selected, mode = await conversation.plan('关联生产检验成本做多表分析', [
        {'id': 'standalone-first', 'familyId': 'production.output'},
        {'id': 'verified-joint', 'familyId': 'production.inspection_fused'},
    ])
    assert not selected and mode['mode'] == 'unavailable'
