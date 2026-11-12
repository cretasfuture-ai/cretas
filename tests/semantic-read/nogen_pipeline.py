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

# Act / clarify prompts and the v3 decision rule live in production (ai/matcher/fallback_combo.py, the
# post-Luna fallback wired in batch 2); this prototype imports them so the two can never drift.
from ai.matcher.fallback_combo import (  # noqa: E402
    ACT_TEXT, ACTS, CLARIFY_RULES, CLARIFY_RULES_V3, META_CONTINUE, decide_v3,
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


__all__ = ['decide_v3', 'CLARIFY_RULES_V3', 'ACTS', 'LunaBackend', 'QwenBackend', 'candidates', 'rubric_notes', 'clarify_question', 'decide',
           'load_rubric', 'MAX_OPTIONS']
