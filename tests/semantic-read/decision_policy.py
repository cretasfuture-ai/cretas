"""Offline policy simulation over calibration signals (line E). No model calls.

``outcome()`` mirrors ``Orchestrator._match_merged`` + ``_result_from_probs`` for one turn
given the recorded merged understanding and Choice distribution. The mirror is pinned to
the real orchestrator by ``test_decision_policy.py`` (same inputs through both must give
the same outcome), so a threshold chosen here is the threshold the live code applies.
"""
from __future__ import annotations

import argparse
import json
import pathlib

NONE_KEY, CLARIFY_KEY = '__NONE__', '__CLARIFY__'
EQUIVALENT = {'ORDER_LIST': {'ORDER_STATUS'}}
AUTO = 0.80  # AIConfig.decision_auto_threshold


def choice_outcome(probs, force_clarify=False):
    probs = dict(probs or {})
    p_none = probs.pop(NONE_KEY, 0.0)
    p_clar = 1.0 if force_clarify else probs.pop(CLARIFY_KEY, 0.0)
    probs.pop(CLARIFY_KEY, None)
    ranked = sorted(probs.items(), key=lambda kv: -kv[1])
    top = ranked[0][1] if ranked else 0.0
    if not force_clarify and p_none >= 0.5 and p_none > top and p_none >= p_clar:
        return 'NONE', None
    if not ranked:
        return 'NONE', None
    if (p_clar > top and p_clar >= p_none) or top < AUTO:
        return 'CLARIFY', ranked[0][0]
    return 'EXECUTE', ranked[0][0]


def outcome(u, choice, clarify_t, choice_below):
    """-> (kind, intent, choice_called). kind in EXECUTE / CLARIFY / NONE / FALLBACK."""
    if u is None:
        return 'FALLBACK', None, False
    if u['act'] == 'CANCEL':
        return 'NONE', None, False
    if u['purpose'] != 'CURRENT':
        return 'CLARIFY', None, False
    if not (u['candidates'] or u['alternatives']):
        return 'FALLBACK', None, False
    clarify = u['clarify_probability'] >= clarify_t
    need = clarify or u['target'] is None or u['confidence'] < choice_below
    if not need:
        return 'EXECUTE', u['target'], False
    if choice is None:
        return 'FALLBACK', None, True
    if not clarify:
        real = {k: v for k, v in choice.items() if k not in (NONE_KEY, CLARIFY_KEY)}
        if u['target'] and real and max(real, key=real.get) == u['target']:
            return 'EXECUTE', u['target'], True
    kind, intent = choice_outcome(choice, force_clarify=clarify)
    return kind, intent, True


def expected(r):
    act = r.get('gold_act')
    if act == 'NEED_CLARIFY':
        return 'CLARIFY'
    if act == 'CANCEL':
        return 'NONE'
    return 'EXECUTE'


def allowed(gold):
    return {gold} | EQUIVALENT.get(gold, set())


def score(rows, clarify_t, choice_below):
    n = len(rows)
    should = [r for r in rows if expected(r) == 'CLARIFY']
    should_not = [r for r in rows if expected(r) != 'CLARIFY']
    res = dict(n=n, missed=0, false=0, goal_ok=0, goal_n=0, exec_ok=0, choice_calls=0, cancel_ok=0, cancel_n=0)
    for r in rows:
        kind, intent, called = outcome(r.get('understand'), r.get('choice'), clarify_t, choice_below)
        res['choice_calls'] += called
        exp = expected(r)
        if exp == 'CLARIFY' and kind != 'CLARIFY':
            res['missed'] += 1
        if exp != 'CLARIFY' and kind == 'CLARIFY':
            res['false'] += 1
        if exp == 'EXECUTE':
            res['goal_n'] += 1
            res['goal_ok'] += kind in ('EXECUTE', 'CLARIFY') and intent in allowed(r['gold_intent'])
            res['exec_ok'] += kind == 'EXECUTE' and intent in allowed(r['gold_intent'])
        if exp == 'NONE':
            res['cancel_n'] += 1
            res['cancel_ok'] += kind == 'NONE'
    res['should'], res['should_not'] = len(should), len(should_not)
    return res


def main():
    p = argparse.ArgumentParser()
    p.add_argument('signals', type=pathlib.Path)
    a = p.parse_args()
    rows = [json.loads(x) for x in a.signals.read_text('utf-8').splitlines() if x.strip()]
    for part in ('ALL', 'M', 'C'):
        sub = [r for r in rows if part == 'ALL' or r['part'] == part]
        print('==', part, len(sub))
        for ct in (0.3, 0.4, 0.5, 0.6, 0.7):
            for cb in (0.6, 0.7, 0.8, 0.9, 1.01):
                s = score(sub, ct, cb)
                print(f"clarify>={ct} choice<{cb}: missed {s['missed']}/{s['should']} false {s['false']}/{s['should_not']} "
                      f"goal {s['goal_ok']}/{s['goal_n']} exec {s['exec_ok']}/{s['goal_n']} cancel {s['cancel_ok']}/{s['cancel_n']} "
                      f"choice {s['choice_calls']}/{s['n']}")


if __name__ == '__main__':
    main()
