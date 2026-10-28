"""Joint intent must constrain the real provider catalog, not bypass the LLM."""
import json
import pytest
from . import conversation


@pytest.mark.asyncio
@pytest.mark.parametrize(('question', 'family'), [
    ('关联生产、检验和成本做多表分析', 'production.inspection_fused'),
    ('Compare production quality and cost across tables', 'production.inspection_fused'),
    ('把生产、良品率与成本放一起看', 'production.fused'),
    ('关联销售发货与当前库存做多表分析', 'commerce-joint.sales.stock'),
    ('Compare sales and inventory across tables', 'commerce-joint.sales.stock'),
    ('Compare finished goods inventory and sales together', 'commerce-joint.sales.stock'),
    ('按客户和渠道做联合销售分析', 'commerce-joint.customer.channel'),
    ('Compare customer and channel sales together', 'commerce-joint.customer.channel'),
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


@pytest.mark.parametrize(('question', 'family', 'relationship'), [
    ('联合销售、发货与库存', 'commerce-joint.sales.stock', '销售-发货-库存'),
    ('按客户和渠道联合分析', 'commerce-joint.customer.channel', '客户-显式渠道-产品'),
])
def test_commerce_joint_limitations_name_the_actual_requested_relationship(question, family, relationship):
    joint = {'id': 'joint', 'analysis': {'familyId': family},
             'findings': {'limitations': ['仅统计校验通过的订单行，不代表全部销售']}}
    catalog = [{'id': 'joint', 'familyId': family}]
    limits = conversation.limitations_for([joint], catalog, question)
    assert limits == ['仅统计校验通过的订单行，不代表全部销售']
    unrelated = {'id': 'stock', 'analysis': {'familyId': 'finished_goods.inventory.current'}}
    missing = conversation.limitations_for([unrelated], [], question)
    assert any(relationship in text and '没有已验证' in text for text in missing)
    assert all('生产-质量-成本' not in text for text in missing)
    unselected = conversation.limitations_for([unrelated], catalog, question)
    assert any(relationship in text and '当前选择未包含' in text for text in unselected)


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


@pytest.mark.asyncio
@pytest.mark.parametrize('omit_one', [False, True])
async def test_multiple_commerce_relationships_cannot_be_silently_dropped(monkeypatch, omit_one):
    families = {'commerce-joint.customer.channel', 'commerce-joint.sales.stock'}
    question = '联合看客户渠道销售、发货和库存'
    assert conversation.joint_family_requirements(question) == families
    assert conversation.joint_family_requirements('Compare customer channel sales and finished goods inventory together') == families

    async def provider(_slot, payload, **kwargs):
        catalog = json.loads(payload['messages'][1]['content'])['catalog']
        assert {item['familyId'] for item in catalog} == families
        content = json.dumps({'selected_ids': [item['id'] for item in catalog[:1 if omit_one else 2]]})
        assert (kwargs['content_validator'](content) is not None) == omit_one
        return {'choices': [{'message': {'content': content}}]}

    monkeypatch.setattr('common.llm_router.call_chain', provider)
    selected, mode = await conversation.plan(question, [
        {'id': 'channel', 'familyId': 'commerce-joint.customer.channel'},
        {'id': 'stock', 'familyId': 'commerce-joint.sales.stock'},
        {'id': 'unrelated', 'familyId': 'production.fused'},
    ])
    assert (set(selected) == {'channel', 'stock'}) if not omit_one else not selected
    assert mode['mode'] == ('unavailable' if omit_one else 'ai')
    limits = conversation.limitations_for(
        [{'id': 'channel', 'analysis': {'familyId': 'commerce-joint.customer.channel'}}], [], question)
    assert any('销售-发货-库存' in text and '没有已验证' in text for text in limits)
    assert any('分别核验' in text for text in limits)


@pytest.mark.asyncio
@pytest.mark.parametrize('question', ['同时看销售渠道和库存', '同时看生产和库存', '综合生产与销售', '联合客户和库存'])
async def test_unproven_channel_stock_relation_is_not_replaced_by_sales_stock(monkeypatch, question):
    async def provider(*args, **kwargs):
        raise AssertionError('unproven relation must not be sent for selection')
    monkeypatch.setattr('common.llm_router.call_chain', provider)
    assert conversation.is_joint_intent(question)
    selected, mode = await conversation.plan(question, [
        {'id': 'stock', 'familyId': 'commerce-joint.sales.stock'},
    ])
    assert not selected and mode['mode'] == 'unavailable' and '尚未验证' in mode['reason']


@pytest.mark.parametrize('question', ['销售和库存分别看，不要联合', 'Show sales and inventory separately, do not combine', '和刚才成本一起看'])
def test_explicit_separate_views_do_not_force_a_commerce_join(question):
    assert not conversation.is_joint_intent(question)
