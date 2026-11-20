"""Bind parameters for every recorded turn with main's Java binders (``ReadParamProbe``).

Feeds part-M turns of a ``turns.jsonl`` to the probe in dialogue order (one probe state per
run x dialogue) and writes ``turns.params.jsonl``: the same rows plus ``bound`` (params the
program would execute with) and ``params_ok`` (page/size/status equal the gold, None when the
turn did not execute). The dialogue act passed to the probe is whatever the arm itself output
(none for main's path), so server-side paging is credited only to arms that produced a PAGE act.
"""
import argparse
import json
import pathlib
import subprocess


def main():
    p = argparse.ArgumentParser()
    p.add_argument('turns', type=pathlib.Path)
    p.add_argument('--classpath', required=True, help='probe classes;main classes;dependency jars')
    a = p.parse_args()
    rows = [json.loads(x) for x in a.turns.read_text('utf-8').splitlines() if x.strip()]
    m = sorted((r for r in rows if r['part'] == 'M'), key=lambda r: (r['run'], r['case_id'], r['turn']))
    feed = []
    for r in m:
        act = (r.get('understand') or {}).get('act') or r.get('act')
        feed.append(json.dumps(dict(case=r['run'] + '|' + r['case_id'], intent=r.get('intent'), sentence=r['input'],
                                    act=act, outcome=r['outcome']), ensure_ascii=False))
    proc = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', a.classpath,
                           'com.cretas.aims.service.execution.ReadParamProbe'],
                          input='\n'.join(feed) + '\n', capture_output=True, text=True, encoding='utf-8')
    if proc.returncode != 0:
        raise SystemExit('probe failed: ' + proc.stderr[-800:])
    outs = [json.loads(x) for x in proc.stdout.splitlines() if x.strip().startswith('{')]
    if len(outs) != len(m):
        raise SystemExit('probe returned %d rows for %d turns' % (len(outs), len(m)))
    by_key = {}
    for r, o in zip(m, outs):
        ok = None
        if r['outcome'] == 'EXECUTE':
            want = {k: v for k, v in r['gold_params'].items() if k in ('page', 'size', 'status')}
            got = o.get('params') or {}
            ok = all(got.get(k) == v for k, v in want.items())
        by_key[(r['run'], r['case_id'], r['turn'])] = dict(bound=o, params_ok=ok)
    out = a.turns.with_name('turns.params.jsonl')
    with out.open('w', encoding='utf-8') as fh:
        for r in rows:
            r.update(by_key.get((r['run'], r['case_id'], r['turn']), {}))
            fh.write(json.dumps(r, ensure_ascii=False) + '\n')
    n = [v['params_ok'] for v in by_key.values() if v['params_ok'] is not None]
    print('wrote %s; executed M turns %d, params ok %d' % (out, len(n), sum(n)))


if __name__ == '__main__':
    main()
