import copy
import json

import pytest

from common.llm_router import _apply_slot_params
from . import ranking


@pytest.mark.asyncio
@pytest.mark.parametrize('case', ['valid', 'extra_candidate', 'invalid_reason', 'extra_field', 'wrong_wrapper'])
async def test_chart_router_json_object_keeps_exact_candidates_and_computed_facts(monkeypatch, case):
    items = [{'id': 'production.output', 'metric': '产量', 'domain': '生产',
              'priority': 'required', 'chart': {'chartType': 'line'},
              'kpis': [{'label': '产量', 'value': 17}]}]
    original = copy.deepcopy(items)
    delivered = []

    async def provider(slot, payload):
        # Exercise the actual shared-router profile, not an array-only provider mock.
        outgoing = _apply_slot_params(slot, 'aliyun_c_vl', 'qwen3.8-flash', payload)
        assert outgoing['response_format'] == {'type': 'json_object'}
        assert 'rankings' in outgoing['messages'][0]['content']
        rows = [{'id': 'production.output', 'reason_code': 'observe'}]
        if case == 'extra_candidate': rows[0]['id'] = 'dish_sales'
        if case == 'invalid_reason': rows[0]['reason_code'] = 'trace'
        if case == 'extra_field': rows[0]['reason'] = '产量增长50%'
        envelope = {'rankings': rows} if case != 'wrong_wrapper' else {'arbitrary': rows}
        delivered.append(envelope)
        return {'choices': [{'message': {'content': json.dumps(envelope)}}]}

    monkeypatch.setattr('common.llm_router.call_chain', provider)
    result, mode = await ranking.rank(items, True)
    assert len(delivered) == 1
    assert mode['mode'] == ('ai' if case == 'valid' else 'rule')
    assert items == original
    assert [{k: v for k, v in c.items() if k != 'aiReason'} for c in result] == original
