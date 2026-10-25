"""AI sees eligible IDs/semantics only, never data values or source rows."""
import asyncio
import json
import logging
import re
import hashlib
import time

logger = logging.getLogger(__name__)
REASONS = {
    'observe': '用于观察同口径指标的变化，并回到来源记录复核',
    'compare': '用于比较同口径项目，帮助安排复核顺序',
    'structure': '用于了解已记录项目的构成与集中程度',
    'plan': '用于核对实际与来源明确的计划目标',
    'trace': '用于核对已记录流程与批次，保留来源边界',
}
_CACHE = {}


def allowed_codes(candidate):
    kind = candidate.get('chart', {}).get('chartType')
    if kind is None: return set(REASONS)  # compatibility for semantic-only callers
    if kind in ('sankey','gantt','funnel'): return {'trace','compare'}
    if kind in ('pie','donut','nested_donut','sunburst','treemap','pareto','category_structure_donut','cost_flow_sankey'): return {'structure','compare'}
    if kind in ('budget_comparison','budget_achievement','bullet','bullet_chart','gauge','variance_analysis'): return {'plan','compare'}
    if kind in ('line','area','dual_axis','combination','cashflow_trend','gross_margin_trend'): return {'observe','compare'}
    return {'compare','observe'}


async def rank(candidates: list[dict], use_ai: bool, cache_scope=None):
    fallback = {'mode': 'rule', 'reason': '未经AI排序，按规则优先级显示'}
    if not use_ai or not candidates:
        return candidates, fallback
    if len(candidates) > 60:
        return candidates, {**fallback, 'reason': '未经AI排序：合格候选较多，按规则优先级显示'}
    key = None
    if cache_scope:
        snapshot = json.dumps(candidates, ensure_ascii=False, sort_keys=True, default=str)
        key = (str(cache_scope), hashlib.sha256(snapshot.encode()).hexdigest())
        cached = _CACHE.get(key)
        if cached and time.monotonic() - cached[0] < 600:
            lookup = {c['id']:c for c in candidates}
            ordered = [{**lookup[cid], 'aiReason':REASONS[code]} for cid,code in cached[1]]
            return ordered, {'mode':'ai','reason':'AI仅对合格图表排序；复用本厂相同数据的已校验排序','cacheHit':True}
    from common.llm_router import call_chain, SLOT
    payload = {
        'messages': [
            {'role': 'system', 'content': '你只给合格工厂图表排序。返回JSON对象且只能含rankings字段，格式为{"rankings":[{"id":"候选ID","reason_code":"理由代码"}]}。rankings数组必须包含所有候选且各一次，每项仅id和reason_code；不能增加候选或自由文本。每项reason_code必须属于该候选的allowed_reason_codes，不能从其他候选借用。代码语义为observe(观察变化)、compare(项目比较)、structure(构成集中度)、plan(目标核对)、trace(流程追溯)。不分析经营现状或原因。'},
            {'role': 'user', 'content': json.dumps([
                {'id': c['id'], 'metric': c.get('aiMetric', c['metric']), 'domain': c['domain'],
                 'allowed_reason_codes':sorted(allowed_codes(c))}
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
        if isinstance(rows, dict):
            if set(rows) != {'rankings'}:
                raise ValueError('invalid ranking envelope')
            rows = rows['rankings']
        lookup = {c['id']: c for c in candidates}
        if not isinstance(rows, list) or len(rows) != len(lookup):
            raise ValueError('incomplete ranking')
        ids = [r['id'] for r in rows]
        if len(set(ids)) != len(ids) or set(ids) != set(lookup):
            raise ValueError('outside eligible set')
        ordered = []
        for r in rows:
            code = r.get('reason_code')
            if set(r) != {'id', 'reason_code'} or code not in allowed_codes(lookup[r['id']]):
                raise ValueError('invalid reason code')
            ordered.append({**lookup[r['id']], 'aiReason': REASONS[code]})
        # Required entries remain the first delivery group, even if AI put an optional first.
        ordered.sort(key=lambda c: c['priority'] != 'required')
        if key:
            if len(_CACHE) >= 32: _CACHE.pop(next(iter(_CACHE)))
            codes = {r['id']:r['reason_code'] for r in rows}
            _CACHE[key] = (time.monotonic(),[(c['id'],codes[c['id']]) for c in ordered])
        return ordered, {'mode': 'ai', 'reason': 'AI仅对合格图表排序；数值与结论均来自规则'}
    except Exception as exc:
        logger.info('Factory chart AI ordering unavailable (%s)', type(exc).__name__)
        return candidates, {**fallback, 'reason': '未经AI排序：AI暂不可用或回复未通过候选校验'}
