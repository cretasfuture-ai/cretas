"""Parameter accuracy through main's real read entry (``ReadTurnEntryProbe.java``) on the E-line
parameter development set (line E). Aggregates only.

Input: the dev set (gold intent / act / params per turn) and, optionally, a decision run
(``nogen_eval.py`` live ``turns.jsonl`` on the same set). Without a run the gold intents are fed
(``oracle``: what main's binders do when the decision is right). A PAGE act without an explicit page
is sent as PAGINATION_NEXT (D1 server paging), the no-generative pipeline's program rule.

Per executed turn the captured service call is normalised to {status, from, to, page, size, material,
batchNumber, orderId} and compared with the gold, key by key; ``session`` = every turn of the dialogue
right (outcome, intent and params). Gold time tokens are converted with the conventions in the dev file.
"""
import argparse
import collections
import datetime as dt
import json
import pathlib
import re
import subprocess

EQUIV = {'ORDER_LIST': {'ORDER_STATUS'}}
EXPLICIT_PAGE = re.compile(r'第\s*([0-9]{1,4}|[零一二两三四五六七八九十]{1,3})\s*页')


def window(token, today):
    if token is None:
        return (None, None)
    if token in ('TODAY',):
        return (today, today)
    if token == 'YESTERDAY':
        d = today - dt.timedelta(days=1)
        return (d, d)
    if token == 'DAY_BEFORE_YESTERDAY':
        d = today - dt.timedelta(days=2)
        return (d, d)
    if token in ('THIS_WEEK', 'LAST_WEEK'):
        mon = today - dt.timedelta(days=today.weekday()) - dt.timedelta(days=7 if token == 'LAST_WEEK' else 0)
        return (mon, mon + dt.timedelta(days=6))
    if token in ('THIS_MONTH', 'LAST_MONTH'):
        first = today.replace(day=1)
        if token == 'LAST_MONTH':
            first = (first - dt.timedelta(days=1)).replace(day=1)
        nxt = (first.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
        return (first, nxt - dt.timedelta(days=1))
    if token == 'DEFAULT_7D':
        return (today - dt.timedelta(days=6), today)
    if token.startswith('LAST_N_DAYS:'):
        n = int(token.split(':')[1])
        return (today - dt.timedelta(days=n - 1), today)
    raise ValueError('unknown time token ' + token)


def normalise(calls):
    """Last data-service call of the turn -> comparable params."""
    for c in reversed(calls):
        m, a = c['method'], c['args']
        if m == 'getSalesOrdersFiltered':
            return dict(status=a[1], from_=a[2], to=a[3], page=a[4], size=a[5])
        if m == 'getSalesOrders':
            return dict(status=None, from_=None, to=None, page=a[1], size=a[2])
        if m == 'getMaterialBatchList':
            pr, f = a[1], a[2]
            return dict(status=pr.get('status'), keyword=pr.get('keyword'), page=pr.get('page'), size=pr.get('size'),
                        materialTypeId=f.get('materialTypeId'), from_=f.get('receiptFrom'), to=f.get('receiptTo'))
        if m in ('getSalesOrderById', 'findByFactoryIdAndOrderNumber'):
            return dict(orderId=a[1])
    return None


def compare(gold, got, today):
    """{key: ok} over the gold keys."""
    res = {}
    p = gold['params']
    if 'orderId' in p:
        res['orderId'] = got is not None and got.get('orderId') == p['orderId']
        return res
    if got is None:
        return {k: False for k in p}
    for k in ('status', 'page', 'size'):
        if k in p:
            res[k] = got.get(k) == p[k]
    if 'time' in p:
        f, t = window(p['time'], today)
        res['time'] = (got.get('from_') == (f.isoformat() if f else None)) and (got.get('to') == (t.isoformat() if t else None))
    if 'material' in p:
        # main filters batches by materialTypeId; a bound material needs a resolved id (not checkable here) -> require
        # that *something* carries it: a materialTypeId or a keyword containing the name.
        res['material'] = (p['material'] is None and not got.get('materialTypeId')) or \
            (p['material'] is not None and (bool(got.get('materialTypeId')) or p['material'] in (got.get('keyword') or '')))
    if 'batchNumber' in p:
        res['batchNumber'] = p['batchNumber'] in (got.get('keyword') or '')
    return res


def feed_rows(dev, run_rows):
    """Turn list in dialogue order with the decision to replay."""
    by = {(r['case_id'], r['turn']): r for r in run_rows} if run_rows is not None else None
    out = []
    for d in dev['dialogues']:
        for i, t in enumerate(d['turns']):
            if by is None:
                outcome, intent, act = 'EXECUTE', t['intent'], t['act']
            else:
                r = by[(d['id'], i + 1)]
                outcome, intent, act = r.get('outcome'), r.get('intent'), r.get('act')
            sent_intent = intent
            if outcome == 'EXECUTE' and act == 'PAGE' and not EXPLICIT_PAGE.search(t['input']):
                sent_intent = 'PAGINATION_NEXT'
            out.append(dict(case=d['id'], turn=i + 1, sentence=t['input'], intent=sent_intent, outcome=outcome,
                            decided_intent=intent, gold=t))
    return out


def run_probe(rows, classpath, run_label):
    feed = '\n'.join(json.dumps(dict(case=run_label + '|' + r['case'], sentence=r['sentence'], intent=r['intent'],
                                     outcome=r['outcome']), ensure_ascii=False) for r in rows) + '\n'
    proc = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', classpath,
                           'com.cretas.aims.service.execution.ReadTurnEntryProbe'],
                          input=feed, capture_output=True, text=True, encoding='utf-8')
    outs = [json.loads(x) for x in proc.stdout.splitlines() if x.startswith('{"case"')]
    if proc.returncode != 0 or len(outs) != len(rows):
        raise SystemExit('probe failed rc=%s rows=%d/%d: %s' % (proc.returncode, len(outs), len(rows), proc.stderr[-600:]))
    return outs


def score(rows, outs, today):
    key_ok = collections.defaultdict(lambda: [0, 0])
    turns = dict(n=0, outcome_ok=0, intent_ok=0, params_ok=0, all_ok=0)
    sessions = collections.defaultdict(list)
    statuses = collections.Counter()
    for r, o in zip(rows, outs):
        g = r['gold']
        turns['n'] += 1
        executed = r['outcome'] == 'EXECUTE'
        turns['outcome_ok'] += executed
        intent_ok = executed and r['decided_intent'] in ({g['intent']} | EQUIV.get(g['intent'], set()))
        turns['intent_ok'] += intent_ok
        statuses[o['status']] += 1
        cmp = compare(g, normalise(o['calls']), today) if executed else {k: False for k in g['params']}
        for k, v in cmp.items():
            key_ok[k][0] += v
            key_ok[k][1] += 1
        p_ok = bool(cmp) and all(cmp.values())
        turns['params_ok'] += p_ok
        ok = executed and intent_ok and p_ok
        turns['all_ok'] += ok
        sessions[r['case']].append(ok)
    return dict(turns=turns, by_key={k: '%d/%d' % tuple(v) for k, v in sorted(key_ok.items())},
                sessions=dict(n=len(sessions), ok=sum(1 for v in sessions.values() if all(v))),
                response_status=dict(statuses))


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--dev', required=True)
    p.add_argument('--run', help='nogen_eval live turns.jsonl; omitted = oracle (gold intents)')
    p.add_argument('--classpath', required=True)
    p.add_argument('--output', required=True, type=pathlib.Path)
    a = p.parse_args()
    dev = json.loads(pathlib.Path(a.dev).read_text('utf-8'))
    today = dt.date.today()
    result = {}
    if a.run:
        allrows = [json.loads(x) for x in pathlib.Path(a.run).read_text('utf-8').splitlines() if x.strip()]
        for run in sorted({r['run'] for r in allrows}):
            rows = feed_rows(dev, [r for r in allrows if r['run'] == run])
            result[run] = score(rows, run_probe(rows, a.classpath, run), today)
    else:
        rows = feed_rows(dev, None)
        result['oracle'] = score(rows, run_probe(rows, a.classpath, 'oracle'), today)
    a.output.write_text(json.dumps(result, ensure_ascii=False, indent=1), 'utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
