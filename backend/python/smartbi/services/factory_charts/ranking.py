"""AI sees eligible IDs/semantics only, never data values or source rows."""
import asyncio
import json
import logging
import re

logger = logging.getLogger(__name__)
REASONS = {
    'observe': '用于观察同口径指标的变化，并回到来源记录复核',
    'compare': '用于比较同口径项目，帮助安排复核顺序',
    'structure': '用于了解已记录项目的构成与集中程度',
    'plan': '用于核对实际与来源明确的计划目标',
    'trace': '用于核对已记录流程与批次，保留来源边界',
}


async def rank(candidates: list[dict], use_ai: bool):
    fallback = {'mode': 'rule', 'reason': '未经AI排序，按规则优先级显示'}
    if not use_ai or not candidates:
        return candidates, fallback
    if len(candidates) > 60:
        return candidates, {**fallback, 'reason': '未经AI排序：合格候选较多，按规则优先级显示'}
    from common.llm_router import call_chain, SLOT
    payload = {
        'messages': [
            {'role': 'system', 'content': '你只给合格工厂图表排序。返回JSON数组，每项仅id和reason_code。必须包含所有候选且各一次；不能增加候选或自由文本。reason_code仅选observe(观察变化)、compare(项目比较)、structure(构成集中度)、plan(目标核对)、trace(流程追溯)。不分析经营现状或原因。'},
            {'role': 'user', 'content': json.dumps([
                {'id': c['id'], 'metric': c.get('aiMetric', c['metric']), 'domain': c['domain']}
                for c in candidates], ensure_ascii=False)},
        ], 'temperature': 0, 'max_tokens': 3000, 'enable_thinking': False,
    }
    if len(json.dumps(payload, ensure_ascii=False).encode()) > 16000:
        return candidates, fallback
    try:
        response = await asyncio.wait_for(call_chain(SLOT.CHART, payload), timeout=12)
        text = response['choices'][0]['message']['content'].strip()
        if text.startswith('```'):
            text = re.sub(r'^```(?:json)?\s*|\s*```$', '', text)
        rows = json.loads(text)
        lookup = {c['id']: c for c in candidates}
        if not isinstance(rows, list) or len(rows) != len(lookup):
            raise ValueError('incomplete ranking')
        ids = [r['id'] for r in rows]
        if len(set(ids)) != len(ids) or set(ids) != set(lookup):
            raise ValueError('outside eligible set')
        ordered = []
        for r in rows:
            code = r.get('reason_code')
            if set(r) != {'id', 'reason_code'} or code not in REASONS:
                raise ValueError('invalid reason code')
            ordered.append({**lookup[r['id']], 'aiReason': REASONS[code]})
        # Required entries remain the first delivery group, even if AI put an optional first.
        ordered.sort(key=lambda c: c['priority'] != 'required')
        return ordered, {'mode': 'ai', 'reason': 'AI仅对合格图表排序；数值与结论均来自规则'}
    except Exception as exc:
        logger.info('Factory chart AI ordering unavailable (%s)', type(exc).__name__)
        return candidates, {**fallback, 'reason': '未经AI排序：AI暂不可用或回复未通过候选校验'}
