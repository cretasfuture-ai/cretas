import json
from types import SimpleNamespace

import pytest
import pandas as pd
from fastapi import FastAPI, Request
from httpx import ASGITransport, AsyncClient

from smartbi.api import financial_dashboard as api
from smartbi.services.factory_charts import conversation
from smartbi.services.factory_charts import conversation_store
from smartbi.services.factory_charts.business_metrics import build as build_business_metrics
from smartbi.services.factory_charts import cross_table as joined, engine
from common.llm_router import LLMChainExhausted


@pytest.fixture(autouse=True)
def isolated_conversation_store():
    old = conversation_store.set_store_for_tests(conversation_store.MemoryConversationStore())
    yield
    conversation_store.set_store_for_tests(old)


def _candidate(cid, family, chart_type='line', value='10'):
    return {
        'id': cid, 'analysis': {'familyId': family}, 'domain': '生产',
        'metric': '产量', 'unit': 'kg', 'priority': 'required',
        'source': 'production · trusted',
        'chart': {'chartType': chart_type, 'echartsOption': {'series': [{'data': [10]}]}},
        'kpis': [{'label': '合计', 'value': value, 'unit': 'kg'}],
        'findings': {'attention': ['仅统计已记录数据']},
        'detailContext': {'id': 'detail-token'},
        'tableData': {'rows': [{'产品': 'A', '产量': 10}]},
    }


def test_semantic_catalog_keeps_all_chart_kinds_without_values():
    candidates = [_candidate('production.output:line', 'production.output'),
                  _candidate('production.output:bar', 'production.output', 'bar')]
    catalog = conversation.semantic_catalog(candidates)
    assert [item['id'] for item in catalog] == ['production.output:line', 'production.output:bar']
    assert {item['chartType'] for item in catalog} == {'line', 'bar'}
    assert all('tableData' not in item and 'detailContext' not in item for item in catalog)
    assert all('A' not in json.dumps(item, ensure_ascii=False) for item in catalog)


@pytest.mark.asyncio
async def test_plan_accepts_only_existing_ids_and_fails_closed(monkeypatch):
    async def call(_slot, payload, **kwargs):
        import json as _json
        assert kwargs['timeout'] == 6 and kwargs['total_timeout'] == 10
        assert '10' not in payload['messages'][1]['content']
        alias = _json.loads(payload['messages'][1]['content'])['catalog'][0]['id']
        return {'choices': [{'message': {'content': '{"selected_ids":["' + alias + '"]}'}}]}

    monkeypatch.setattr('common.llm_router.call_chain', call)
    selected, mode = await conversation.plan('看产量', [
        {'id': 'one', 'familyId': 'production.output', 'chartType': 'line', 'domain': '生产',
         'metric': '产量', 'unit': 'kg'},
    ])
    assert selected == ['one'] and mode['mode'] == 'ai'

    async def outside(_slot, _payload, **kwargs):
        return {'choices': [{'message': {'content': '{"selected_ids":["not-eligible"]}'}}]}

    monkeypatch.setattr('common.llm_router.call_chain', outside)
    selected, mode = await conversation.plan('看产量', [
        {'id': 'one', 'familyId': 'production.output', 'chartType': 'line', 'domain': '生产',
         'metric': '产量', 'unit': 'kg'},
    ])
    assert selected == [] and mode['mode'] == 'unavailable'

    async def duplicate(_slot, _payload, **kwargs):
        return {'choices': [{'message': {'content': '{"selected_ids":["one","two"]}'}}]}

    monkeypatch.setattr('common.llm_router.call_chain', duplicate)
    selected, mode = await conversation.plan('看多维度', [
        {'id': 'one', 'familyId': 'production.output', 'chartType': 'line', 'domain': '生产', 'metric': '产量', 'unit': 'kg'},
        {'id': 'two', 'familyId': 'production.output', 'chartType': 'bar', 'domain': '生产', 'metric': '产量', 'unit': 'kg'},
    ])
    assert selected == [] and mode['mode'] == 'unavailable'


def _planner_catalog():
    return [
        {'id': 'one', 'familyId': 'production.output', 'chartType': 'line',
         'domain': '生产', 'metric': '产量', 'unit': 'kg'},
        {'id': 'two', 'familyId': 'quality.pass_rate', 'chartType': 'bar',
         'domain': '质量', 'metric': '合格率', 'unit': '%'},
    ]


@pytest.mark.asyncio
async def test_duplicate_family_chain_failure_gets_one_fixed_feedback_retry(monkeypatch):
    calls = []

    async def call(_slot, payload, **_kwargs):
        calls.append(payload)
        if len(calls) == 1:
            raise LLMChainExhausted(
                'chart', ['provider/model: invalid_planner selected duplicate chart family'])
        alias = json.loads(payload['messages'][1]['content'])['catalog'][0]['id']
        assert '固定校验反馈' in payload['messages'][-1]['content']
        assert 'invalid_planner' not in payload['messages'][-1]['content']
        return {'choices': [{'message': {'content': json.dumps(
            {'selected_ids': [alias]})}}]}

    monkeypatch.setattr('common.llm_router.call_chain', call)
    selected, mode = await conversation.plan('看生产和质量', _planner_catalog())

    assert selected == ['one'] and mode['mode'] == 'ai'
    assert len(calls) == 2


@pytest.mark.asyncio
async def test_mixed_duplicate_family_and_quota_chain_gets_one_retry(monkeypatch):
    calls = []

    async def call(_slot, payload, **_kwargs):
        calls.append(payload)
        if len(calls) == 1:
            raise LLMChainExhausted('chart', [
                'primary/model: invalid_planner selected duplicate chart family',
                'backup/model: quota 429',
            ])
        alias = json.loads(payload['messages'][1]['content'])['catalog'][0]['id']
        return {'choices': [{'message': {'content': json.dumps(
            {'selected_ids': [alias]})}}]}

    monkeypatch.setattr('common.llm_router.call_chain', call)
    selected, mode = await conversation.plan('看生产和质量', _planner_catalog())

    assert selected == ['one'] and mode['mode'] == 'ai'
    assert len(calls) == 2


@pytest.mark.asyncio
async def test_duplicate_family_second_failure_is_unavailable_without_third_call(monkeypatch):
    calls = 0

    async def call(_slot, _payload, **_kwargs):
        nonlocal calls
        calls += 1
        raise LLMChainExhausted(
            'chart', ['provider/model: invalid_planner selected duplicate chart family'])

    monkeypatch.setattr('common.llm_router.call_chain', call)
    selected, mode = await conversation.plan('看生产和质量', _planner_catalog())

    assert selected == [] and mode['mode'] == 'unavailable'
    assert calls == 2


@pytest.mark.asyncio
async def test_quota_timeout_and_other_invalid_do_not_trigger_replan(monkeypatch):
    cases = [
        ['provider/model: quota 429'],
        ['provider/model: timeout'],
        ['provider/model: invalid_planner envelope contains unknown fields'],
    ]
    for errors in cases:
        calls = 0

        async def call(_slot, _payload, _errors=errors, **_kwargs):
            nonlocal calls
            calls += 1
            raise LLMChainExhausted('chart', _errors)

        monkeypatch.setattr('common.llm_router.call_chain', call)
        selected, mode = await conversation.plan('看生产和质量', _planner_catalog())
        assert selected == [] and mode['mode'] == 'unavailable'
        assert calls == 1


@pytest.mark.asyncio
async def test_non_router_exception_with_errors_attribute_does_not_replan(monkeypatch):
    calls = 0

    class MisleadingError(RuntimeError):
        errors = ['provider/model: invalid_planner selected duplicate chart family']

    async def call(_slot, _payload, **_kwargs):
        nonlocal calls
        calls += 1
        raise MisleadingError('not an llm router chain failure')

    monkeypatch.setattr('common.llm_router.call_chain', call)
    selected, mode = await conversation.plan('看生产和质量', _planner_catalog())

    assert selected == [] and mode['mode'] == 'unavailable'
    assert calls == 1


@pytest.mark.asyncio
async def test_duplicate_family_does_not_replan_after_shared_deadline(monkeypatch):
    clock = iter([0.0, 0.0, 12.1])
    monkeypatch.setattr(conversation, '_planner_monotonic', lambda: next(clock))
    calls = 0

    async def call(_slot, _payload, **_kwargs):
        nonlocal calls
        calls += 1
        raise LLMChainExhausted(
            'chart', ['provider/model: invalid_planner selected duplicate chart family'])

    monkeypatch.setattr('common.llm_router.call_chain', call)
    selected, mode = await conversation.plan('看生产和质量', _planner_catalog())

    assert selected == [] and mode['mode'] == 'unavailable'
    assert calls == 1


@pytest.mark.asyncio
async def test_retry_payload_over_50kb_is_rejected_without_second_provider_call(monkeypatch):
    calls = []
    oversized_metric = 'x' * 49200

    async def call(_slot, payload, **_kwargs):
        calls.append(payload)
        if len(calls) > 1:
            raise AssertionError('retry payload must be rejected before provider call')
        payload_size = len(json.dumps(payload, ensure_ascii=False).encode('utf-8'))
        assert 49000 <= payload_size < 50000
        raise LLMChainExhausted(
            'chart', ['provider/model: invalid_planner selected duplicate chart family'])

    monkeypatch.setattr('common.llm_router.call_chain', call)
    selected, mode = await conversation.plan('看生产', [{
        'id': 'large', 'familyId': 'production.output', 'chartType': 'line',
        'domain': '生产', 'metric': oversized_metric, 'unit': 'kg',
    }])

    assert selected == [] and mode['mode'] == 'unavailable'
    assert '超过安全上限' in mode['reason']
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_planner_receives_sanitized_history_without_old_numeric_answers(monkeypatch):
    seen = {}

    async def call(_slot, payload, **kwargs):
        seen['payload'] = payload
        import json as _json
        alias = _json.loads(payload['messages'][1]['content'])['catalog'][0]['id']
        return {'choices': [{'message': {'content': '{"selected_ids":["' + alias + '"]}'}}]}

    monkeypatch.setattr('common.llm_router.call_chain', call)
    selected, mode = await conversation.plan('和刚才成本一起看', [
        {'id': 'one', 'familyId': 'cost.unit', 'chartType': 'line', 'domain': '成本', 'metric': '单位成本', 'unit': '元/kg'},
    ], history=[{'question': '刚才总成本是12345元，再看成本', 'selectedIds': ['old-cost']}], factory_id='F006')
    content = seen['payload']['messages'][1]['content']
    assert selected == ['one'] and mode['mode'] == 'ai'
    assert '12345' not in content and 'old-cost' not in content


def test_explicit_period_is_honored_and_conflicts_rejected():
    context = conversation._new_context(('F006', 1309, 'factory_super_admin'), 'business', None,
                                         {'year': 2026, 'period_type': 'all', 'start_month': 1,
                                          'end_month': 12, 'guided': True}, [], [], [], 'first')
    scope, error, present = conversation.apply_explicit_period(
        context.scope, year=2025, period_type='year', start_month=1, end_month=12)
    assert not error and present and scope['year'] == 2025 and scope['period_type'] == 'year'
    scope, _, error = conversation.scope_for_context(
        context, '看2024年', None, year=2025, period_type='year', start_month=1, end_month=12)
    assert error and scope['year'] == 2025


def test_factory_analysis_accepts_internal_snake_case_java_payload():
    parsed = api.FactoryAnalysisRequest.model_validate({
        'question': '看产品P1质量', 'context_id': 'ctx', 'analysis_subject': 'P1',
        'period_type': 'month_range', 'start_month': 2, 'end_month': 3,
        'upload_id': 7, 'factory_id': 'F006', 'year': 2026,
    })
    assert parsed.context_id == 'ctx' and parsed.analysis_subject == 'P1'
    assert parsed.period_type == 'month_range' and parsed.start_month == 2 and parsed.end_month == 3
    assert parsed.upload_id == 7 and parsed.factory_id == 'F006'


def test_generic_product_language_and_year_in_product_label_do_not_become_subject_or_period():
    subjects = [{'id': 'sku-2025', 'label': '产品2025'}]
    assert conversation.resolve_subject('按产品比较产量、良品率和成本', subjects) == (None, None)
    assert conversation.resolve_subject('哪个产品成本高', subjects) == (None, None)
    assert conversation.resolve_subject('看产品2025成本', subjects) == ('sku-2025', None)
    scope = {'year': 2026, 'period_type': 'all', 'start_month': 1, 'end_month': 12, 'guided': True}
    resolved, error = conversation.resolve_period('产品2025成本', scope)
    assert not error and resolved == scope


def test_relative_periods_are_supported_or_fail_closed():
    scope = {'year': 2026, 'period_type': 'all', 'start_month': 1, 'end_month': 12, 'guided': True}
    resolved, error = conversation.resolve_period('看今年', scope)
    assert not error and resolved['year'] == __import__('datetime').date.today().year
    current_year = __import__('datetime').date.today().year
    for phrase, expected_year, expected_month in (
        ('看去年3月产量', current_year - 1, 3),
        ('看今年3月产量', current_year, 3),
        ('看前年12月产量', current_year - 2, 12),
    ):
        resolved, error = conversation.resolve_period(phrase, scope)
        assert not error and resolved['year'] == expected_year
        assert resolved['period_type'] == 'month_range'
        assert resolved['start_month'] == resolved['end_month'] == expected_month
    resolved, error = conversation.resolve_period('看最近一季度', scope)
    assert error and resolved == scope


def test_relative_months_are_bounded_and_previous_month_is_not_current(monkeypatch):
    class FrozenDate:
        @classmethod
        def today(cls):
            return __import__('datetime').date(2026, 1, 3)
    monkeypatch.setattr(conversation, 'date', FrozenDate)
    scope = {'year': 2026, 'period_type': 'all', 'start_month': 1, 'end_month': 12, 'guided': True}
    previous, error = conversation.resolve_period('看上个月产量', scope)
    assert not error and previous['year'] == 2025 and previous['start_month'] == previous['end_month'] == 12
    rolling, error = conversation.resolve_period('看最近三个月产量', scope)
    assert error and rolling == scope  # cross-year rolling windows are not representable by the API
    unsupported, error = conversation.resolve_period('看近三年成本', scope)
    assert error and unsupported == scope


def test_generic_product_dimensions_do_not_become_unknown_product_and_partial_is_ambiguous():
    subjects = [{'id': 'a', 'label': '苹果A'}, {'id': 'b', 'label': '苹果B'}]
    assert conversation.resolve_subject('按产品类别比较成本', subjects) == (None, None)
    assert conversation.resolve_subject('哪个产品成本高', subjects) == (None, None)
    assert conversation.resolve_subject('看产品苹果', subjects)[1] is not None


@pytest.mark.asyncio
async def test_planner_registers_current_subjects_and_sensitive_table_values(monkeypatch):
    seen = {}

    async def call(_slot, _payload, **kwargs):
        from common.llm_redactor import current_redaction_scope
        from common.llm_redactor import redact_payload_for_egress
        seen.update(current_redaction_scope().known_values)
        import json as _json
        egress, _ = redact_payload_for_egress(_payload)
        provider_content = egress['messages'][1]['content']
        assert '秘密产品凤凰2025' not in provider_content
        assert '客户张三' not in provider_content
        assert '13800138000' not in provider_content
        alias = _json.loads(provider_content)['catalog'][0]['id']
        return {'choices': [{'message': {'content': '{"selected_ids":["' + alias + '"]}'}}]}

    monkeypatch.setattr('common.llm_router.call_chain', call)
    selected, mode = await conversation.plan(
        '看秘密产品凤凰2025和客户张三',
        [{'id': 'one', 'familyId': 'production.output', 'chartType': 'line'}],
        authorized_subjects=[{'id': 'p', 'label': '秘密产品凤凰2025'}],
        authorized_recommendations=[{'id': 'production.output@秘密产品凤凰2025@kg',
                                     'tableData': {'headers': ['客户', '联系电话'],
                                                   'rows': [['客户张三', '13800138000']]}}],
    )
    assert selected == ['one'] and mode['mode'] == 'ai'
    assert {'秘密产品凤凰2025', '客户张三', '13800138000'} <= set(seen)


def test_analysis_response_preserves_structured_period_for_export_and_detail():
    body = api._analysis_response(
        {'sourceLabel': '本厂业务系统',
         'period': {'label': '2025年', 'year': 2025, 'periodType': 'year', 'startMonth': 1, 'endMonth': 12}},
        factory_id='F006', source='business', question='看去年', status='answered', answer='ok',
        selected_ids=[], recommendations=[], catalog=[], facts=[], limitations=[], context_id='ctx',
        subjects=[], follow_up_questions=[])
    assert body['data']['period'] == {'label': '2025年', 'year': 2025, 'periodType': 'year', 'startMonth': 1, 'endMonth': 12}


@pytest.mark.asyncio
async def test_period_clarification_is_independent_from_product_and_valid_correction_continues(monkeypatch):
    candidate = _candidate('production.output:line', 'production.output')
    calls = []

    async def recommendations(req, _request):
        calls.append(req)
        return {'success': True, 'data': {
            'factoryId': 'F006', 'source': 'business', 'sourceLabel': '本厂业务系统',
            'period': {'label': f'{req.year}-{req.start_month}-{req.end_month}'},
            'analysisSubject': None, 'analysisScopeLabel': '全厂范围', 'subjects': [],
            'recommendations': [candidate], 'sourceErrors': [],
        }}

    async def planner(*_args, **_kwargs):
        return ['production.output:line'], {'mode': 'ai'}

    monkeypatch.setattr(api, 'factory_recommendations', recommendations)
    monkeypatch.setattr(conversation, 'plan', planner)
    app = FastAPI()

    @app.middleware('http')
    async def identity(request: Request, call_next):
        request.state.factory_id, request.state.user_id, request.state.role = 'F006', 1309, 'factory_super_admin'
        return await call_next(request)

    app.include_router(api.router)
    async with AsyncClient(transport=ASGITransport(app=app), base_url='http://test') as client:
        first = await client.post('/factory-analysis', json={'question': '看近三年产量'})
        first_data = first.json()['data']
        assert first_data['status'] == 'clarification' and first_data['subjects'] == []
        context_id = first_data['contextId']
        follow = await client.post('/factory-analysis', json={'question': '继续', 'contextId': context_id})
        assert follow.json()['data']['status'] == 'clarification'
        corrected = await client.post('/factory-analysis', json={'question': '看2025年产量', 'contextId': context_id})
        assert corrected.json()['data']['status'] == 'answered'
        assert calls[-1].year == 2025 and calls[-1].period_type == 'year'


@pytest.mark.asyncio
async def test_first_turn_explicit_year_conflicts_with_natural_year(monkeypatch):
    candidate = _candidate('production.output:line', 'production.output')

    async def recommendations(req, _request):
        return {'success': True, 'data': {
            'factoryId': 'F006', 'source': 'business', 'sourceLabel': '本厂业务系统',
            'period': {'label': 'year'}, 'analysisSubject': None, 'analysisScopeLabel': '全厂范围',
            'subjects': [], 'recommendations': [candidate], 'sourceErrors': [],
        }}

    async def planner(*_args, **_kwargs):
        return ['production.output:line'], {'mode': 'ai'}

    monkeypatch.setattr(api, 'factory_recommendations', recommendations)
    monkeypatch.setattr(conversation, 'plan', planner)
    app = FastAPI()

    @app.middleware('http')
    async def identity(request: Request, call_next):
        request.state.factory_id, request.state.user_id, request.state.role = 'F006', 1309, 'factory_super_admin'
        return await call_next(request)

    app.include_router(api.router)
    async with AsyncClient(transport=ASGITransport(app=app), base_url='http://test') as client:
        response = await client.post('/factory-analysis', json={
            'question': '看2024年产量', 'year': 2025, 'periodType': 'year',
            'startMonth': 1, 'endMonth': 12,
        })
        data = response.json()['data']
        assert data['status'] == 'clarification' and '不一致' in data['answer']


@pytest.mark.asyncio
async def test_first_turn_asgi_applies_relative_year_and_month(monkeypatch):
    candidate = _candidate('production.output:line', 'production.output')
    calls = []

    async def recommendations(req, _request):
        calls.append(req)
        return {'success': True, 'data': {
            'factoryId': 'F006', 'source': 'business', 'sourceLabel': '本厂业务系统',
            'period': {'label': 'period'}, 'analysisSubject': None, 'analysisScopeLabel': '全厂范围',
            'subjects': [], 'recommendations': [candidate], 'sourceErrors': [],
        }}

    async def planner(*_args, **_kwargs):
        return ['production.output:line'], {'mode': 'ai'}

    monkeypatch.setattr(api, 'factory_recommendations', recommendations)
    monkeypatch.setattr(conversation, 'plan', planner)
    app = FastAPI()

    @app.middleware('http')
    async def identity(request: Request, call_next):
        request.state.factory_id, request.state.user_id, request.state.role = 'F006', 1309, 'factory_super_admin'
        return await call_next(request)

    app.include_router(api.router)
    async with AsyncClient(transport=ASGITransport(app=app), base_url='http://test') as client:
        response = await client.post('/factory-analysis', json={
            'question': '看去年产量', 'periodType': 'all', 'year': 2026,
            'startMonth': 1, 'endMonth': 12,
        })
        assert response.json()['data']['status'] == 'answered'
        assert calls[-1].year == __import__('datetime').date.today().year - 1
        assert calls[-1].period_type == 'year'
        response = await client.post('/factory-analysis', json={
            'question': '看去年3月产量', 'periodType': 'all', 'year': 2026,
            'startMonth': 1, 'endMonth': 12,
        })
        assert response.json()['data']['status'] == 'answered'
    assert calls[-1].period_type == 'month_range'
    assert calls[-1].start_month == calls[-1].end_month == 3


def test_answer_contains_trusted_findings_and_stock_question_has_no_joint_warning():
    selected = [_candidate('inventory.stock:bar', 'inventory.stock', value='20')]
    selected[0]['findings'] = {'good': ['库存已记录'], 'attention': ['不代表原因'], 'suggestions': ['回查明细']}
    answer = conversation.render_answer('看库存', selected, conversation.facts_for(selected), [])
    assert '可信发现' in answer and '库存已记录' in answer and '来源：production · trusted' in answer
    catalog = [{'id': 'inventory.stock:bar', 'familyId': 'inventory.stock'}]
    assert not any('生产-质量-成本' in item for item in conversation.limitations_for(selected, catalog, '看库存'))


def test_limitations_use_only_explicit_constraints_not_normal_attention():
    selected = [_candidate('production.output:line', 'production.output')]
    selected[0]['findings'] = {
        'attention': ['合计 1,342kg', '最高为 800kg；最低为 542kg'],
        'limitations': ['部分SKU缺少产品名称，已回退产品ID'],
    }
    assert conversation.limitations_for(selected, [], '看产量') == ['部分SKU缺少产品名称，已回退产品ID']


def test_real_trend_keeps_missing_period_constraint_without_relabeling_totals():
    from .engine import series_candidate
    from .metrics import Series
    candidate = series_candidate(Series('production.output', '产量趋势', '生产', 'kg', [('2026-01-01', 10), ('2026-01-03', 20)], 'production'), 'line')
    limitations = conversation.limitations_for([candidate], [], '看产量')
    assert limitations == ['仅汇总有记录的日期；缺期不补零，空白不表示停产或没有业务']
    assert any('合计' in item for item in candidate['findings']['attention'])


def test_joint_intent_and_findings_use_chinese_labels():
    assert conversation.is_joint_intent('把生产、质量和成本放一起看')
    assert conversation.is_joint_intent('多维度分析产量和良品率')
    assert conversation.is_joint_intent('Compare production quality and cost')
    assert conversation.joint_family_requirement('跨表关联生产质量成本') == 'production.inspection_fused'
    assert conversation.joint_family_requirement('把生产质量成本放一起看') == 'production.fused'
    assert not conversation.is_joint_intent('看库存')
    answer = conversation.render_answer(
        '看质量', [_candidate('quality.time-trend', 'quality.time-trend')], [], [])
    assert '需关注：' in answer or '可信发现' not in answer


@pytest.mark.asyncio
async def test_joint_business_metrics_candidate_requires_product_then_uses_fused(monkeypatch):
    frame = pd.DataFrame({
        '日期': ['d1', 'd2'], '产品': ['P', 'P'], '单位': ['kg', 'kg'],
        '批次': ['b1', 'b2'], '产量': [100, 110], '良品数': [95, 105], '总成本': [10, 11],
    })
    from types import SimpleNamespace
    candidates, gaps = build_business_metrics(
        frame, {}, SimpleNamespace(include_optional=False, guided=True, analysis_subject='P'), True, 'production')
    fused = next(item for item in candidates if item['id'].startswith('production.fused@'))
    assert not any(g['id'] == 'production.fused' for g in gaps) and fused['chart']['chartType'] == 'combination'

    calls = []
    async def recommendations(req, _request):
        calls.append(req)
        return {'success': True, 'data': {
            'factoryId': 'F006', 'source': 'business', 'sourceLabel': '本厂业务系统',
            'period': {'label': 'period'}, 'analysisSubject': req.analysis_subject,
            'analysisScopeLabel': '产品P', 'subjects': [{'id': 'P', 'label': 'P'}],
            'recommendations': [fused], 'sourceErrors': [],
        }}

    async def planner(_question, catalog, *_args, **_kwargs):
        assert any(item['familyId'] == 'production.fused' for item in catalog)
        return [catalog[0]['id']], {'mode': 'ai'}

    monkeypatch.setattr(api, 'factory_recommendations', recommendations)
    monkeypatch.setattr(conversation, 'plan', planner)
    app = FastAPI()

    @app.middleware('http')
    async def identity(request: Request, call_next):
        request.state.factory_id, request.state.user_id, request.state.role = 'F006', 1309, 'factory_super_admin'
        return await call_next(request)

    app.include_router(api.router)
    async with AsyncClient(transport=ASGITransport(app=app), base_url='http://test') as client:
        first = await client.post('/factory-analysis', json={'question': '把生产、质量和成本放一起看'})
        assert first.json()['data']['status'] == 'clarification'
        assert first.json()['data']['subjects'] == [{'id': 'P', 'label': 'P'}]
        follow = await client.post('/factory-analysis', json={
            'question': '产品P的生产、质量和成本放一起看',
            'contextId': first.json()['data']['contextId'],
        })
        data = follow.json()['data']
        assert data['status'] == 'answered'
        assert data['selectedIds'] == [fused['id']]
        assert data['recommendations'][0]['tableData']
        assert data['facts']


@pytest.mark.asyncio
async def test_cross_table_joint_asgi_clarifies_then_uses_real_inspection_fused(monkeypatch):
    production, quality = __import__('smartbi.services.factory_charts.test_cross_table', fromlist=['raw_datasets']).raw_datasets()
    prepared, gaps = __import__('smartbi.services.factory_charts.test_cross_table', fromlist=['prepared_dataset']).prepared_dataset(production, quality)
    req = __import__('smartbi.services.factory_charts.test_cross_table', fromlist=['request']).request('P1')
    real_candidates, build_gaps = joined.build(
        __import__('smartbi.services.factory_charts.test_cross_table', fromlist=['scoped']).scoped(prepared, req), req, True)
    assert not gaps and not build_gaps and real_candidates[0]['analysis']['familyId'] == 'production.inspection_fused'
    real_candidate = real_candidates[0]
    planner_questions = []

    async def recommendations(request, _request):
        product_selected = bool(request.analysis_subject)
        return {'success': True, 'data': {
            'factoryId': 'F006', 'source': 'business', 'sourceLabel': '本厂业务系统',
            'period': {'label': 'period'}, 'analysisSubject': request.analysis_subject,
            'analysisScopeLabel': '产品P1' if product_selected else '全厂范围',
            'subjects': [{'id': 'P1', 'label': 'P1'}],
            'recommendations': [_candidate('standalone-first', 'production.output'), real_candidate] if product_selected else [], 'sourceErrors': [],
        }}

    async def provider(_slot, payload, **kwargs):
        body = json.loads(payload['messages'][1]['content'])
        planner_questions.append(body['question'])
        content = json.dumps({'selected_ids': [body['catalog'][0]['id']]})
        assert kwargs['content_validator'](content) is None
        return {'choices': [{'message': {'content': content}}]}

    monkeypatch.setattr(api, 'factory_recommendations', recommendations)
    monkeypatch.setattr('common.llm_router.call_chain', provider)
    app = FastAPI()

    @app.middleware('http')
    async def identity(request, call_next):
        request.state.factory_id, request.state.user_id, request.state.role = 'F006', 1309, 'factory_super_admin'
        return await call_next(request)

    app.include_router(api.router)
    async with AsyncClient(transport=ASGITransport(app=app), base_url='http://test') as client:
        first = await client.post('/factory-analysis', json={'question': 'Compare production quality and cost across tables'})
        assert first.json()['data']['status'] == 'clarification'
        assert first.json()['data']['subjects'] == [{'id': 'P1', 'label': 'P1'}]
        context_id = first.json()['data']['contextId']
        selected = await client.post('/factory-analysis', json={
            'question': 'Compare production quality and cost across tables for product P1', 'contextId': context_id,
        })
        data = selected.json()['data']
        assert data['status'] == 'answered'
        assert data['selectedIds'] == [real_candidate['id']]
        assert data['recommendations'][0]['analysis']['familyId'] == 'production.inspection_fused'
        assert data['recommendations'][0]['tableData']['rows'] == [['2026-01-01', 100, 90, 90, 200]]
        assert data['facts'] and any(fact['unit'] == 'kg' for fact in data['facts'])
        context_id = data['contextId']
        quality = await client.post('/factory-analysis', json={'question': '再看质量', 'contextId': context_id})
        assert quality.json()['data']['status'] == 'answered'
    assert planner_questions == ['Compare production quality and cost across tables for product P1', '再看质量']


@pytest.mark.asyncio
async def test_unresolved_product_cannot_continue_into_factory_scope(monkeypatch):
    calls = []
    candidate = _candidate('production.output:line', 'production.output')

    async def recommendations(_request, _http_request):
        calls.append(_request)
        return {'success': True, 'data': {
            'factoryId': 'F006', 'source': 'business', 'sourceLabel': '本厂业务系统',
            'period': {'label': '全部历史'}, 'analysisSubject': None,
            'analysisScopeLabel': '全厂范围', 'subjects': [{'id': 'product-a', 'label': '产品A'}],
            'recommendations': [candidate], 'sourceErrors': [],
        }}

    async def planner(*_args, **_kwargs):
        raise AssertionError('unresolved product must not reach planner')

    monkeypatch.setattr(api, 'factory_recommendations', recommendations)
    monkeypatch.setattr(conversation, 'plan', planner)
    app = FastAPI()

    @app.middleware('http')
    async def identity(request: Request, call_next):
        request.state.factory_id, request.state.user_id, request.state.role = 'F006', 1309, 'factory_super_admin'
        return await call_next(request)

    app.include_router(api.router)
    async with AsyncClient(transport=ASGITransport(app=app), base_url='http://test') as client:
        first = await client.post('/factory-analysis', json={'question': '分析产品不存在'})
        assert first.json()['data']['status'] == 'clarification'
        follow = await client.post('/factory-analysis', json={
            'question': '继续', 'contextId': first.json()['data']['contextId']})
        assert follow.json()['data']['status'] == 'clarification'
        assert len(calls) == 2


@pytest.mark.asyncio
async def test_each_followup_revalidates_current_data_finance_and_source_errors(monkeypatch):
    calls = []
    candidate = _candidate('production.output:line', 'production.output', value='10')

    async def recommendations(_request, _http_request):
        calls.append(_request)
        current = _candidate('production.output:line', 'production.output', value=str(10 + len(calls)))
        return {'success': True, 'data': {
            'factoryId': 'F006', 'source': 'business',
            'sourceLabel': '本厂业务系统', 'period': {'label': '全部历史'},
            'analysisSubject': None, 'analysisScopeLabel': '全厂范围', 'subjects': [],
            'recommendations': [current],
            'sourceErrors': [] if len(calls) == 1 else [{'source': 'cost', 'message': '权限已收回'}],
        }}

    async def planner(*_args, **_kwargs):
        return ['production.output:line'], {'mode': 'ai'}

    monkeypatch.setattr(api, 'factory_recommendations', recommendations)
    monkeypatch.setattr(conversation, 'plan', planner)
    app = FastAPI()

    @app.middleware('http')
    async def identity(request: Request, call_next):
        request.state.factory_id, request.state.user_id, request.state.role = 'F006', 1309, 'factory_super_admin'
        return await call_next(request)

    app.include_router(api.router)
    async with AsyncClient(transport=ASGITransport(app=app), base_url='http://test') as client:
        first = await client.post('/factory-analysis', json={'question': '看产量'})
        context_id = first.json()['data']['contextId']
        follow = await client.post('/factory-analysis', json={
            'question': '再看产量', 'contextId': context_id,
            'year': 2025, 'periodType': 'year', 'startMonth': 1, 'endMonth': 12,
        })
        body = follow.json()['data']
        # Each AI answer performs a second authorized read for the selected
        # chart so only its immutable detail context is allocated.
        assert len(calls) == 5
        assert calls[-1].year == 2025 and calls[-1].period_type == 'year'
        assert calls[-1].detail_candidate_ids == ['production.output:line']
        assert body['sourceErrors'] == [{'source': 'cost', 'message': '权限已收回'}]
        assert body['facts'][0]['value'] == '15'


@pytest.mark.asyncio
async def test_initial_generic_catalog_is_guided_current_year_and_explicit_join_is_not_executed(monkeypatch):
    captured = []
    candidate = _candidate('production.output:line', 'production.output')

    async def recommendations(req, _http_request):
        captured.append(req)
        return {'success': True, 'data': {
            'factoryId': 'F006', 'source': 'business', 'sourceLabel': '本厂业务系统',
            'period': {'label': '全部历史'}, 'analysisSubject': None,
            'analysisScopeLabel': '全厂范围', 'subjects': [], 'recommendations': [candidate],
        }}

    async def planner(*_args, **_kwargs):
        return ['production.output:line'], {'mode': 'ai'}

    monkeypatch.setattr(api, 'factory_recommendations', recommendations)
    monkeypatch.setattr(conversation, 'plan', planner)
    app = FastAPI()

    @app.middleware('http')
    async def identity(request: Request, call_next):
        request.state.factory_id, request.state.user_id, request.state.role = 'F006', 1309, 'factory_super_admin'
        return await call_next(request)

    app.include_router(api.router)
    async with AsyncClient(transport=ASGITransport(app=app), base_url='http://test') as client:
        response = await client.post('/factory-analysis', json={'question': '执行 SELECT * JOIN secret_table'})
        assert response.status_code == 200 and response.json()['data']['status'] == 'answered'
    assert captured and captured[0].guided is True
    assert captured[0].period_type == 'all'
    assert captured[0].year == __import__('datetime').date.today().year


@pytest.mark.asyncio
async def test_factory_analysis_reuses_trusted_candidates_and_context(monkeypatch):
    candidate = _candidate('production.output:line', 'production.output')
    async def recommendations(_request, _http_request):
        return {'success': True, 'data': {
            'factoryId': 'F006', 'source': 'business', 'sourceLabel': '本厂业务系统',
            'period': {'label': '全部历史'}, 'analysisSubject': None,
            'analysisScopeLabel': '全厂范围', 'subjects': [],
            'recommendations': [candidate],
        }}

    async def planner(_question, _catalog, _history=None, **_kwargs):
        return ['production.output:line'], {'mode': 'ai'}

    monkeypatch.setattr(api, 'factory_recommendations', recommendations)
    monkeypatch.setattr(conversation, 'plan', planner)
    releases = []
    original_release = conversation.release_shared_context

    async def tracked_release(context):
        releases.append(context.context_id)
        return await original_release(context)

    monkeypatch.setattr(conversation, 'release_shared_context', tracked_release)
    app = FastAPI()

    @app.middleware('http')
    async def trusted_identity(request: Request, call_next):
        request.state.factory_id = 'F006'
        request.state.user_id = 1309
        request.state.role = 'factory_super_admin'
        return await call_next(request)

    app.include_router(api.router)
    async with AsyncClient(transport=ASGITransport(app=app), base_url='http://test') as client:
        first = await client.post('/factory-analysis', json={'question': '看产量'})
        assert first.status_code == 200
        data = first.json()['data']
        assert data['status'] == 'answered'
        assert data['selectedIds'] == ['production.output:line']
        assert data['recommendations'][0]['detailContext']['id'] == 'detail-token'
        context_id = data['contextId']
        follow = await client.post('/factory-analysis', json={
            'question': '再看质量', 'contextId': context_id,
        })
        assert follow.status_code == 200
        assert follow.json()['data']['contextId'] == context_id
        switched = await client.post('/factory-analysis', json={
            'question': '再看质量', 'contextId': context_id, 'source': 'upload', 'uploadId': 99,
        })
        assert switched.status_code == 403
        assert releases == [context_id]
        reclaimed = await conversation.claim_shared_context(context_id, ('F006', 1309, 'factory_super_admin'))
        await conversation.release_shared_context(reclaimed)


@pytest.mark.asyncio
async def test_context_cannot_change_source_or_owner():
    conversation.clear_contexts()
    context = conversation._new_context(('F006', 1309, 'factory_super_admin'), 'business', None,
                                         {}, [], [], [], 'first')
    with pytest.raises(Exception) as exc:
        conversation._context_for(context.context_id, ('F007', 1309, 'factory_super_admin'))
    assert getattr(exc.value, 'status_code', None) == 403


def test_context_is_metadata_only_and_ttl_bounded():
    conversation.clear_contexts()
    context = conversation._new_context(
        ('F006', 1309, 'factory_super_admin'), 'upload', 42,
        {'year': 2026, 'period_type': 'all', 'start_month': 1, 'end_month': 12, 'guided': True},
        [{'id': 'product-a', 'label': '产品A'}],
        [_candidate('secret', 'cost.unit')], [{'id': 'secret'}], 'first')
    assert not hasattr(context, 'recommendations') and not hasattr(context, 'catalog')
    assert context.selected_ids == []
    context.expires_at = 0
    with pytest.raises(Exception) as exc:
        conversation._context_for(context.context_id, ('F006', 1309, 'factory_super_admin'))
    assert getattr(exc.value, 'status_code', None) == 410


def test_context_source_and_upload_are_immutable_metadata():
    context = conversation._new_context(
        ('F006', 1309, 'factory_super_admin'), 'upload', 42,
        {'year': 2026, 'period_type': 'all', 'start_month': 1, 'end_month': 12, 'guided': True},
        [], [], [], 'first')
    assert context.source == 'upload' and context.upload_id == 42
    with pytest.raises(Exception) as exc:
        if 'business' != context.source or 99 != context.upload_id:
            raise ValueError('context source/upload switch rejected')
    assert 'switch rejected' in str(exc.value)


@pytest.mark.asyncio
@pytest.mark.parametrize('failure', [None, 'provider_omits', 'fresh_source_missing', 'permission_denied'])
async def test_commerce_joint_asgi_uses_every_real_relationship_without_production_subject(monkeypatch, failure):
    from . import test_commerce_joint as fixture
    from .trusted_business_sources import COMMERCE_JOINT_SOURCE
    frame, gaps = fixture.prepared([fixture.sale('l1', 'o1')],
                                   [fixture.shipment('l1', 'o1', 'b1')],
                                   [fixture.inventory('b1', available='7')])
    assert frame is not None and not gaps
    candidates, _ = engine.build([engine.Dataset(frame, COMMERCE_JOINT_SOURCE, financial=True, currency='CNY')],
                                 fixture.request(guided=True), True)
    expected = {'commerce-joint.sales.stock', 'commerce-joint.customer.channel'}
    assert {item['analysis']['familyId'] for item in candidates} == expected

    original_recommendations = api.factory_recommendations
    if failure in {'fresh_source_missing', 'permission_denied'}:
        from . import sources, finance_sources, finance_auth, operational_sources, captured_sources, trusted_business_sources, ranking

        async def empty_source(*args, **kwargs):
            return [], [], []

        async def no_finance(*args, **kwargs):
            return False

        async def no_rank(rows, *args, **kwargs):
            return rows, {'mode': 'rules'}

        for source in (sources, finance_sources, operational_sources, captured_sources, trusted_business_sources):
            monkeypatch.setattr(source, 'load', empty_source)
        monkeypatch.setattr(finance_auth, 'full_factory_finance_access', no_finance)
        monkeypatch.setattr(ranking, 'rank', no_rank)
        monkeypatch.setattr(engine, 'build', lambda *_args, **_kwargs: (candidates[:1], []))

    async def recommendations(request, _http):
        if request.detail_candidate_ids and failure in {'fresh_source_missing', 'permission_denied'}:
            if failure == 'permission_denied':
                _http.state.role = 'unsupported_test_role'
            # Exercise the real route, including role and requested-detail-ID
            # validation. It raises 422 when the selected source disappeared.
            return await original_recommendations(request, _http)
        return {'success': True, 'data': {
            'factoryId': 'F006', 'source': 'business', 'sourceLabel': '本厂业务系统',
            'period': {'label': '全部历史'}, 'analysisSubject': None, 'subjects': [],
            'recommendations': candidates, 'sourceErrors': [],
        }}

    async def provider(_slot, payload, **kwargs):
        catalog = json.loads(payload['messages'][1]['content'])['catalog']
        assert {item['familyId'] for item in catalog} == expected
        ids = [item['id'] for item in catalog]
        content = json.dumps({'selected_ids': ids[:1] if failure == 'provider_omits' else ids})
        assert (kwargs['content_validator'](content) is not None) == (failure == 'provider_omits')
        return {'choices': [{'message': {'content': content}}]}

    monkeypatch.setattr(api, 'factory_recommendations', recommendations)
    monkeypatch.setattr('common.llm_router.call_chain', provider)
    app = FastAPI()

    @app.middleware('http')
    async def identity(request, call_next):
        request.state.factory_id, request.state.user_id, request.state.role = 'F006', 1309, 'factory_super_admin'
        return await call_next(request)

    app.include_router(api.router)
    async with AsyncClient(transport=ASGITransport(app=app), base_url='http://test') as client:
        result = await client.post('/factory-analysis', json={'question': '联合看客户渠道销售、发货和库存'})
    if failure == 'permission_denied':
        assert result.status_code == 403
        return
    assert result.status_code == 200
    data = result.json()['data']
    if failure:
        assert data['status'] == 'unavailable' and not data['facts'] and not data['recommendations']
    else:
        assert data['status'] == 'answered'
        assert {item['analysis']['familyId'] for item in data['recommendations']} == expected
        assert any(fact['value'] == 7 for fact in data['facts'])
        assert any('分别核验' in text for text in data['limitations'])
        assert not any('没有已验证的生产' in text for text in data['limitations'])
