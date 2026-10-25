import json

import pytest

from .ranking import rank


@pytest.mark.asyncio
@pytest.mark.parametrize('kind,code,mode', [
    ('waterfall', 'structure', 'ai'),
    ('pnl_waterfall', 'structure', 'ai'),
    ('cash_flow_waterfall', 'structure', 'ai'),
    ('ar_aging', 'structure', 'ai'),
    ('hr_cost_analysis', 'structure', 'ai'),
    ('channel_analysis', 'structure', 'ai'),
    ('expense_yoy_budget', 'plan', 'ai'),
    ('line', 'trace', 'rule'),
])
async def test_known_financial_aliases_allow_only_applicable_reason_families(monkeypatch, kind, code, mode):
    async def provider(slot, payload):
        return {'choices': [{'message': {'content': json.dumps({'rankings': [{'id': 'eligible', 'reason_code': code}]})}}]}

    monkeypatch.setattr('common.llm_router.call_chain', provider)
    candidate = {'id': 'eligible', 'metric': '本厂合格指标', 'domain': '财务',
                 'priority': 'required', 'chart': {'chartType': kind}, 'kpis': [{'value': 17}]}
    result, ranking = await rank([candidate], True)
    assert ranking['mode'] == mode
    assert result[0]['id'] == candidate['id'] and result[0]['kpis'] == candidate['kpis']
