"""No-generative decision pipeline (line E primary configuration, evaluation prototype).

Retrieval + decision model only; no generative call on the main path:

  candidates    previous executed intent (program-side continuation) + prod rerank top-10 +
                prod BlendRetriever top-20, de-duplicated, visible only, capped at 18 options.
  intent + act  decision-model Choice. Luna: one Decisions request with three questions
                (intent choice, dialogue-act choice, needs-clarification predicate). qwen (prod
                ``llm_router`` DECISION chain): two first-token logprob calls in parallel, the prod
                intent choice and a dialogue-act choice; P(NEED_CLARIFY) of the act is the clarify
                signal (qwen has no predicate endpoint).
  clarify       Predicate (Luna) / act probability (qwen) against a calibrated threshold.
  parameters    main's deterministic binders (``ReadParamProbe.java``), applied after the fact.
  question      clarify question text from ``clarify_rubric.json`` templates, never generated.

Candidate notes given to the decision model (must-ask fields, confusable terms) also come from
the rubric. Nothing here is wired into production; production wiring is line H.
"""
from __future__ import annotations

import asyncio
import json
import math
import pathlib
import random
from typing import Any, Dict, List, Optional

from ai.matcher.decision import (
    CLARIFY_KEY, LETTERS, NONE_KEY, SPECIAL_OPTIONS, STRICT_SYSTEM, history_block, intent_text, letter_probs,
    role_line,
)

ACTS = ('NEW_TASK', 'CHANGE_PARAM', 'PAGE', 'CANCEL', 'NEED_CLARIFY')
ACT_TEXT = {
    'NEW_TASK': '开始一个新任务或换了要办的事',
    'CHANGE_PARAM': '沿用上一轮任务，只改条件/对象/口径，或回答系统正在追问的字段',
    'PAGE': '对上一轮的结果翻页（下一页/上一页/第几页）',
    'CANCEL': '放弃当前任务（算了/不查了/先别弄）',
    'NEED_CLARIFY': '信息不足以判断要做什么、指代不明、口径含糊或缺了必须先问的字段，系统要先问用户',
}
CLARIFY_RULES = (
    '需要先问的情形：只有含糊动词（弄/处理/整/搞），分不出是查看还是新建、修改、审批，而这个对象既有查询又有改数据的功能；'
    '两个以上含义不同的功能都说得通且上文排除不了；用「这个/那批/这单」指代对象但上文对不上；'
    '系统正在追问某个字段，这句话却没有给出它；命中下面列出的易混口径且没有限定词能分开；'
    '功能已确定但它的必问字段在这句话和上文里都没有。'
    '不需要问：缺的只是有默认值的条件（时间范围、条数、排序、页码）；上文能对上的续句；明确取消。'
)
# Steve 2026-11-12 policy (clarify rubric v3, scripts/finetune/clarify_rubric_v3.json): ask only when the
# request may be a write or the target itself is two-way; a read whose missing conditions have defaults
# that do not mislead is executed and the defaults are stated (Java adds the default notices).
CLARIFY_RULES_V3 = (
    '只有下面几种情形才必须先问：'
    '①只有“弄一下/处理一下/整一下”这类含糊动词，说不出要查看还是要新建、修改、审批；'
    '②查询和录入/修改两种读法都说得通（如“录下……要多少”）；'
    '③接着上一轮只换了对象（如“那原料呢”），新对象有两种以上不同的查法，上文定不下；'
    '④只有“这个/那个”且上文对不上，或者缺了必填、没有合理默认的信息；'
    '⑤系统正在追问某个字段，这句话没回答也没换任务。'
    '下面这些不要问，直接办：只读查询缺的是有合理默认的条件（没说时间按最近7天、结果多先显示20条、过期=已经过期）；'
    '同名的人或物（都列出来）；系统做不到的口径（说明后给最接近的结果）；上文能对上的续句；明确的取消。'
)
MAX_OPTIONS = 18
ROLE = 'factory_super_admin'


def load_rubric(path: pathlib.Path) -> Dict[str, Any]:
    return json.loads(path.read_text(encoding='utf-8'))


def candidates(query: str, prev_intent: Optional[str], snap: Dict[str, Any], visible: Dict[str, Any],
               max_options: int = MAX_OPTIONS) -> List[str]:
    rec = snap.get(query) or {}
    out: List[str] = []
    for c in ([prev_intent] if prev_intent else []) + [x[0] for x in rec.get('rerank') or []] + \
            [x[0] for x in rec.get('blend') or []]:
        if c in visible and c not in out:
            out.append(c)
    return out[:max_options]


def rubric_notes(codes: List[str], rubric: Dict[str, Any], query: str) -> str:
    """Compact must-ask / confusable-term notes for the candidates (from the rubric, no generation)."""
    lines = []
    intents = rubric.get('intents', {})
    for c in codes:
        e = intents.get(c)
        if e and e.get('must_ask'):
            lines.append('%s 必问：%s' % (c, '、'.join(x['param'] for x in e['must_ask'])))
    seen = set()
    for cf in rubric.get('confusions', []):
        if cf['id'] in seen or not set(cf['intents']) & set(codes):
            continue
        seen.add(cf['id'])
        lines.append('易混口径「%s」：%s' % (cf['term'], '；'.join(cf['resolve_when'])))
    return ('\n候选须知：\n' + '\n'.join(lines)) if lines else ''


def clarify_question(top: List[str], rubric: Dict[str, Any], names: Dict[str, str], query: str) -> Dict[str, str]:
    """Template question from the rubric; returns {kind, text}. Never generated."""
    for cf in rubric.get('confusions', []):
        if set(cf['intents']) & set(top[:3]) and any(w and w in query for w in _terms(cf['term'])):
            return dict(kind='confusion', text=cf['question'])
    e = rubric.get('intents', {}).get(top[0]) if top else None
    if e and e.get('must_ask'):
        d = e['must_ask'][0]
        label = (d.get('description') or d['param']).split('，')[0].split('(')[0].strip()
        return dict(kind='must_ask', text='请告诉我%s。' % label)
    if len(top) >= 2:
        return dict(kind='choose_between', text='您是要「%s」还是「%s」？' % (names.get(top[0], top[0]), names.get(top[1], top[1])))
    return dict(kind='generic', text='请再说明一下您要办的事。')


def _terms(term: str) -> List[str]:
    core = term.split('（')[0]
    return [t for t in core.replace('/', '、').split('、') if t]


def _input_text(query, history, options, notes):
    opts = '\n'.join(f"{r['intent_code']}：{intent_text(r)[:60]}" for r in options)
    return f"{role_line(ROLE)}\n{history_block(history)}用户说：{query}\n\n可能相关的功能：\n{opts}{notes}"


class LunaBackend:
    """One Decisions request: intent choice + act choice + clarify predicate."""

    name = 'luna'

    def __init__(self, ledger, predicate: str = 'v1'):
        if predicate not in ('v1', 'v3', 'both'):
            raise ValueError('predicate must be v1, v3 or both')
        self.ledger = ledger
        self.predicate = predicate

    async def signals(self, query, history, options, notes) -> Dict[str, Any]:
        opts = list(options)
        random.Random(query).shuffle(opts)
        choices = [dict(value=r['intent_code'], description=intent_text(r)[:80]) for r in opts]
        choices += [dict(value=NONE_KEY, description=SPECIAL_OPTIONS[0]['intent_name'] + '：' + SPECIAL_OPTIONS[0]['description']),
                    dict(value=CLARIFY_KEY, description=SPECIAL_OPTIONS[1]['intent_name'] + '：' + SPECIAL_OPTIONS[1]['description'])]
        payload = dict(model='gpt-6-luna', input=_input_text(query, history, options, notes), questions=[
            dict(type='choice', name='intent', instructions='用户这句话应该交给哪个系统功能来处理？只依据对话与这句话判断。',
                 choices=choices),
            dict(type='choice', name='dialogue_act', instructions='判断这句话在对话中的动作。',
                 choices=[dict(value=a, description=ACT_TEXT[a]) for a in ACTS]),
        ])
        if self.predicate in ('v1', 'both'):
            payload['questions'].append(dict(type='predicate', name='needs_clarification',
                                             instructions='系统这一轮是否必须先问用户才能办理（true=必须先问）。' + CLARIFY_RULES))
        if self.predicate in ('v3', 'both'):
            payload['questions'].append(dict(type='predicate', name='needs_clarification_v3',
                                             instructions='系统这一轮是否必须先问用户才能办理（true=必须先问）。' + CLARIFY_RULES_V3))
        from decision_eval_transport import LABEL
        tok = LABEL.set('decision')
        try:
            body = await self.ledger.post('decisions', payload, 0)
        finally:
            LABEL.reset(tok)
        ans = {a.get('name'): a for a in body.get('answers', [])}
        intent = {p['value']: float(p['probability']) for p in ans['intent']['probabilities']}
        act = {p['value']: float(p['probability']) for p in ans['dialogue_act']['probabilities']}
        out = dict(intent=intent, act=act)
        for name, key in (('needs_clarification', 'clarify_p'), ('needs_clarification_v3', 'clarify_p_v3')):
            if name not in ans:
                continue
            p = ans[name].get('probability')
            if isinstance(p, bool) or not isinstance(p, (int, float)) or not math.isfinite(p):
                raise ValueError('predicate probability missing')
            out[key] = float(p)
        if 'clarify_p' not in out:
            out['clarify_p'] = out['clarify_p_v3']
        out['clarify_source'] = 'luna_predicate_' + self.predicate
        return out


class QwenBackend:
    """Prod DECISION chain: prod intent choice ‖ act choice (first-token logprobs)."""

    name = 'qwen'

    def __init__(self, matcher):
        self.matcher = matcher

    async def _act(self, query, history, notes):
        opts = '\n'.join(f'{L}. {a}：{ACT_TEXT[a]}' for L, a in zip(LETTERS, ACTS))
        user = (f"{role_line(ROLE)}\n{history_block(history)}用户说：{query}{notes}\n\n"
                f"问题：这句话在对话中是什么动作？{CLARIFY_RULES}\n选项：\n{opts}\n\n只输出一个选项字母。")
        payload = {'messages': [{'role': 'system', 'content': STRICT_SYSTEM}, {'role': 'user', 'content': user},
                                {'role': 'assistant', 'content': '答案：', 'partial': True}],
                   'max_tokens': 1, 'logprobs': True, 'top_logprobs': 5}

        def has(body):
            return None if letter_probs(body, len(ACTS)) else 'no_option_letter_in_top_logprobs'
        from decision_eval_transport import LABEL
        tok = LABEL.set('act')
        try:
            body = await self.matcher._call(payload, body_validator=has)
        finally:
            LABEL.reset(tok)
        probs = letter_probs(body, len(ACTS)) or {}
        return {a: probs.get(L, 0.0) for L, a in zip(LETTERS, ACTS)}

    async def signals(self, query, history, options, notes) -> Dict[str, Any]:
        intent, act = await asyncio.gather(
            self.matcher.choose(query, ROLE, list(options), history, special_options=True),
            self._act(query, history, notes))
        if not intent:
            raise ValueError('qwen choice returned no probabilities')
        return dict(intent=intent, act=act, clarify_p=act.get('NEED_CLARIFY', 0.0), clarify_source='qwen_act_need_clarify')


META_CONTINUE = {'PAGINATION_PREVIOUS', 'PAGINATION_NEXT', 'CONTEXT_CONTINUE'}


def decide(sig: Dict[str, Any], clarify_t: float, exec_t: float, prev_intent: Optional[str] = None) -> Dict[str, Any]:
    """Signals -> outcome. Same rule for both backends; thresholds come from calibration.

    Program rule (no model): a PAGE / CHANGE_PARAM act with a previous executed task continues
    that task; page and inherited conditions are then bound by main's ReadTurnParameters
    (server page +-1 for PAGE). Meta intents (pagination / context-continue) never become the
    target themselves when a previous task exists.
    """
    act = max(sig['act'], key=sig['act'].get)
    intent = dict(sig['intent'])
    p_none = intent.pop(NONE_KEY, 0.0)
    p_clar = intent.pop(CLARIFY_KEY, 0.0)
    ranked = sorted(intent.items(), key=lambda kv: -kv[1])
    top, p_top = (ranked[0] if ranked else (None, 0.0))
    if act == 'CANCEL':
        return dict(outcome='NONE', intent=None, top=[c for c, _ in ranked[:5]], act=act, reason='act_cancel')
    if sig['clarify_p'] >= clarify_t:
        return dict(outcome='CLARIFY', intent=top, top=[c for c, _ in ranked[:5]], act=act, reason='clarify_signal')
    if p_none >= 0.5 and p_none > p_top:
        return dict(outcome='NONE', intent=None, top=[c for c, _ in ranked[:5]], act=act, reason='choice_none')
    if prev_intent and act in ('PAGE', 'CHANGE_PARAM'):
        return dict(outcome='EXECUTE', intent=prev_intent, top=[c for c, _ in ranked[:5]], act=act, reason='continue_prev')
    if prev_intent and top in META_CONTINUE:
        return dict(outcome='EXECUTE', intent=prev_intent, top=[c for c, _ in ranked[:5]], act=act, reason='meta_to_prev')
    if top is None or p_top < exec_t or p_clar > p_top:
        return dict(outcome='CLARIFY', intent=top, top=[c for c, _ in ranked[:5]], act=act, reason='low_choice')
    return dict(outcome='EXECUTE', intent=top, top=[c for c, _ in ranked[:5]], act=act, reason='choice')


def decide_v3(sig: Dict[str, Any], clarify_t: float, read_t: float, prev_intent: Optional[str] = None,
              modes: Optional[Dict[str, str]] = None, margin_t: float = 0.3, write_t: float = 0.4) -> Dict[str, Any]:
    """Steve 2026-11-12 policy (rubric v3). Differs from ``decide`` in two places only:

    - the clarify signal is the v3 Predicate (asks only for write-or-read / two-way target / unknown referent /
      unanswered pending field / vague verb);
    - a READ target executes (Java states the defaults it used) when Choice's top >= ``read_t`` or leads the
      runner-up by >= ``margin_t``; the "需要先问清楚" option alone no longer forces a question on reads.
      A WRITE target (mode not READ / NO_TOOL) still goes to confirmation, or is asked when weak.
    """
    modes = modes or {}
    act = max(sig['act'], key=sig['act'].get)
    intent = dict(sig['intent'])
    p_none = intent.pop(NONE_KEY, 0.0)
    p_clar = intent.pop(CLARIFY_KEY, 0.0)
    ranked = sorted(intent.items(), key=lambda kv: -kv[1])
    top_codes = [c for c, _ in ranked[:5]]
    top, p_top = (ranked[0] if ranked else (None, 0.0))
    p_second = ranked[1][1] if len(ranked) > 1 else 0.0
    cp = sig.get('clarify_p_v3', sig['clarify_p'])
    if act == 'CANCEL':
        return dict(outcome='NONE', intent=None, top=top_codes, act=act, reason='act_cancel')
    if cp >= clarify_t:
        return dict(outcome='CLARIFY', intent=top, top=top_codes, act=act, reason='clarify_signal')
    if prev_intent and act in ('PAGE', 'CHANGE_PARAM'):
        return dict(outcome='EXECUTE', intent=prev_intent, top=top_codes, act=act, reason='continue_prev')
    if prev_intent and top in META_CONTINUE:
        return dict(outcome='EXECUTE', intent=prev_intent, top=top_codes, act=act, reason='meta_to_prev')
    if p_none >= 0.5 and p_none > p_top:
        return dict(outcome='NONE', intent=None, top=top_codes, act=act, reason='choice_none')
    if top is None:
        return dict(outcome='CLARIFY', intent=None, top=top_codes, act=act, reason='low_choice')
    if modes.get(top, 'READ') in ('READ', 'NO_TOOL'):
        if p_top >= read_t or p_top - p_second >= margin_t:
            return dict(outcome='EXECUTE', intent=top, top=top_codes, act=act, reason='read_with_defaults')
        return dict(outcome='CLARIFY', intent=top, top=top_codes, act=act, reason='two_way_target')
    if p_top >= write_t and p_top >= p_clar:
        return dict(outcome='EXECUTE', intent=top, top=top_codes, act=act, reason='write_to_confirm')
    return dict(outcome='CLARIFY', intent=top, top=top_codes, act=act, reason='low_choice')


__all__ = ['decide_v3', 'CLARIFY_RULES_V3', 'ACTS', 'LunaBackend', 'QwenBackend', 'candidates', 'rubric_notes', 'clarify_question', 'decide',
           'load_rubric', 'MAX_OPTIONS']
