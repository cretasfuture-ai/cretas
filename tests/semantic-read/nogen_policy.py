"""Threshold sweep for the no-generative pipeline over recorded signals (no model calls).

Uses ``nogen_pipeline.decide`` itself, so the swept rule is the rule the live runs apply.
"""
import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / 'backend' / 'python'))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from nogen_pipeline import decide  # noqa: E402

EQUIV = {'ORDER_LIST': {'ORDER_STATUS'}}


def expected(r):
    act = r.get('gold_act')
    return {'NEED_CLARIFY': 'CLARIFY', 'CANCEL': 'NONE'}.get(act, 'EXECUTE')


def score(rows, ct, et):
    s = dict(missed=0, should=0, false=0, should_not=0, exec_ok=0, goal_n=0, cancel_ok=0, cancel_n=0, err=0)
    for r in rows:
        exp = expected(r)
        if not r.get('signals'):
            s['err'] += 1
            kind, intent = 'ERROR', None
        else:
            d = decide(r['signals'], ct, et, prev_intent=r.get('prev_intent_used'))
            kind, intent = d['outcome'], d['intent']
        if exp == 'CLARIFY':
            s['should'] += 1
            s['missed'] += kind != 'CLARIFY'
        else:
            s['should_not'] += 1
            s['false'] += kind == 'CLARIFY'
        if exp == 'EXECUTE':
            s['goal_n'] += 1
            s['exec_ok'] += kind == 'EXECUTE' and intent in ({r['gold_intent']} | EQUIV.get(r['gold_intent'], set()))
        if exp == 'NONE':
            s['cancel_n'] += 1
            s['cancel_ok'] += kind == 'NONE'
    return s


def main():
    p = argparse.ArgumentParser()
    p.add_argument('signals', type=pathlib.Path)
    a = p.parse_args()
    rows = [json.loads(x) for x in a.signals.read_text('utf-8').splitlines() if x.strip()]
    for part in ('M', 'C'):
        sub = [r for r in rows if r['part'] == part]
        print('==', part, len(sub))
        for ct in (0.3, 0.4, 0.5, 0.6, 0.7, 0.8):
            line = []
            for et in (0.3, 0.4, 0.5, 0.6, 0.7):
                s = score(sub, ct, et)
                line.append(f"e{et}: miss {s['missed']}/{s['should']} false {s['false']}/{s['should_not']} "
                            f"exec {s['exec_ok']}/{s['goal_n']} cancel {s['cancel_ok']}/{s['cancel_n']}")
            print(f'c{ct} | ' + ' | '.join(line))


if __name__ == '__main__':
    main()
