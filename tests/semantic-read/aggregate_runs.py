"""Aggregate N evaluation runs: per-dimension mean/min/max and turns whose verdict differs between runs.

Input: N run directories, each holding the artefacts of one campaign
(``complete-read-score.json`` required; ``development-corpus-locked.json`` and
``run-manifest.json`` used when present). Offline only: no model request.

A single run is never a conclusion. Runs built from different source versions are
labelled with their version key and are NOT silently pooled: ``versions`` lists the
distinct keys, and a turn that differs between runs of different versions cannot be
attributed to sampling noise alone.
"""
import argparse
import hashlib
import json
import pathlib
import statistics
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import scoring_dimensions as sd  # noqa: E402


def _sha256_file(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def _digest(obj):
    return hashlib.sha256(json.dumps(obj, sort_keys=True, ensure_ascii=False).encode('utf-8')).hexdigest()


def run_identity(run_dir, manifest):
    """What source this run measured. Missing evidence is reported as None, never guessed."""
    run_dir = pathlib.Path(run_dir)
    manifest = manifest or {}
    parts = {k: manifest.get(k) for k in ('source_hashes', 'production_class_hashes', 'controlled_fixture_class_hashes')}
    base_head = manifest.get('base_head')
    return dict(
        base_head=base_head,
        source_digest=_digest(parts)[:16] if any(v is not None for v in parts.values()) else None,
        probe_sha256=(_sha256_file(run_dir / 'CompleteReadHttpProbe.java')[:16] if (run_dir / 'CompleteReadHttpProbe.java').exists() else None),
        corpus_sha256=(_sha256_file(run_dir / 'development-corpus-locked.json')[:16] if (run_dir / 'development-corpus-locked.json').exists() else None),
    )


def version_key(identity):
    head = (identity['base_head'] or 'UNKNOWN')[:10]
    return '%s/%s' % (head, identity['source_digest'] or 'nohash')


def load_run(run_dir, corpus_path=None):
    run_dir = pathlib.Path(run_dir)
    corpus = sd.load_json(corpus_path or run_dir / 'development-corpus-locked.json')
    score = sd.load_json(run_dir / 'complete-read-score.json')
    manifest_path = run_dir / 'run-manifest.json'
    manifest = sd.load_json(manifest_path) if manifest_path.exists() else None
    rows = sd.score_run(corpus, score)
    identity = run_identity(run_dir, manifest)
    return dict(name=run_dir.name, identity=identity, version=version_key(identity),
                rows=rows, summary=sd.summarize(rows, corpus), corpus=corpus)


def _stats(values):
    values = [v for v in values if v is not None]
    if not values:
        return dict(mean=None, min=None, max=None, n=0)
    return dict(mean=round(statistics.fmean(values), 4), min=round(min(values), 4), max=round(max(values), 4), n=len(values))


def _rate(part):
    return part['passed'] / part['evaluable'] if part['evaluable'] else None


def channel_table(runs, channel):
    """Per dimension: each run's passed/evaluable, then mean/min/max of the rate and of the count."""
    table = {}
    for d in sd.DIMENSIONS:
        parts = [r['summary'][channel]['dimensions'][d] for r in runs]
        table[d] = dict(
            per_run=[dict(run=r['name'], passed=p['passed'], evaluable=p['evaluable'], total=p['total']) for r, p in zip(runs, parts)],
            rate=_stats([_rate(p) for p in parts]),
            passed=_stats([p['passed'] for p in parts]),
        )
    for name, key in (('sessions_success', 'sessions_total'), ('turns_success', 'turns_total')):
        parts = [r['summary'][channel] for r in runs]
        table[name] = dict(
            per_run=[dict(run=r['name'], passed=p[name], total=p[key]) for r, p in zip(runs, parts)],
            rate=_stats([p[name] / p[key] for p in parts]),
            passed=_stats([p[name] for p in parts]),
        )
    fc = [r['summary'][channel]['clarify_directions'] for r in runs]
    table['false_clarify'] = dict(per_run=[dict(run=r['name'], **c['false_clarify']) for r, c in zip(runs, fc)],
                                  count=_stats([c['false_clarify']['count'] for c in fc]))
    table['missed_clarify'] = dict(per_run=[dict(run=r['name'], **c['missed_clarify']) for r, c in zip(runs, fc)],
                                   count=_stats([c['missed_clarify']['count'] for c in fc]))
    return table


def inconsistent_turns(runs):
    """Turns (per channel) where runs disagree on whole-turn success or on any dimension."""
    index = [{(r['channel'], r['case_id'], r['turn']): r for r in run['rows']} for run in runs]
    out = []
    for key in index[0]:
        rows = [i[key] for i in index if key in i]
        differing = [d for d in sd.DIMENSIONS + ('success',)
                     if len({r[d] for r in rows if r[d] is not None}) > 1]
        # a dimension that is evaluable in some runs and not in others is also a disagreement of evidence
        differing += [d for d in sd.DIMENSIONS if d not in differing and len({r[d] is None for r in rows}) > 1]
        if differing:
            out.append(dict(
                channel=key[0], case_id=key[1], turn=key[2], dimensions=differing,
                versions_differ=len({r['version'] for r in runs}) > 1,
                per_run=[dict(run=run['name'], version=run['version'], status=i[key]['status'],
                              selected_intent=i[key]['selected_intent'], category=i[key]['category'],
                              **{d: i[key][d] for d in sd.DIMENSIONS + ('success',)})
                         for run, i in zip(runs, index)],
            ))
    return out


def aggregate(runs):
    versions = {}
    for r in runs:
        versions.setdefault(r['version'], []).append(r['name'])
    return dict(
        runs=[dict(name=r['name'], version=r['version'], **r['identity']) for r in runs],
        versions=versions,
        mixed_versions=len(versions) > 1,
        channels={ch: channel_table(runs, ch) for ch in sd.CHANNELS},
        inconsistent=inconsistent_turns(runs),
    )


def render_markdown(result):
    lines = ['# Multi-run aggregate', '']
    lines.append('versions: ' + '; '.join('%s = %s' % (k, ', '.join(v)) for k, v in result['versions'].items()))
    if result['mixed_versions']:
        lines.append('WARNING: runs come from different source versions; spread mixes version change and sampling noise.')
    for ch, table in result['channels'].items():
        lines += ['', '## ' + ch, '', '| dimension | ' + ' | '.join(p['run'] for p in table['goal']['per_run']) + ' | mean | min | max |',
                  '|---|' + '---|' * (len(table['goal']['per_run']) + 3)]
        for d in sd.DIMENSIONS + ('sessions_success', 'turns_success'):
            cells = ['%d/%d' % (p['passed'], p.get('evaluable', p['total'])) for p in table[d]['per_run']]
            rate = table[d]['rate']
            fmt = lambda v: '-' if v is None else '%.1f%%' % (100 * v)
            lines.append('| %s | %s | %s | %s | %s |' % (d, ' | '.join(cells), fmt(rate['mean']), fmt(rate['min']), fmt(rate['max'])))
        lines.append('| false_clarify | %s | %s | %s | %s |' % (
            ' | '.join(str(p['count']) for p in table['false_clarify']['per_run']),
            table['false_clarify']['count']['mean'], table['false_clarify']['count']['min'], table['false_clarify']['count']['max']))
    lines += ['', '## Turns whose verdict differs between runs: %d' % len(result['inconsistent'])]
    return '\n'.join(lines) + '\n'


def main():
    p = argparse.ArgumentParser(description='Aggregate N run directories offline.')
    p.add_argument('runs', nargs='+', type=pathlib.Path)
    p.add_argument('--corpus', type=pathlib.Path, help='override the per-run development-corpus-locked.json')
    p.add_argument('--output', type=pathlib.Path)
    p.add_argument('--markdown', type=pathlib.Path)
    a = p.parse_args()
    runs = [load_run(d, a.corpus) for d in a.runs]
    result = aggregate(runs)
    if a.output:
        a.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    text = render_markdown(result)
    if a.markdown:
        a.markdown.write_text(text, encoding='utf-8')
    sys.stdout.buffer.write(text.encode('utf-8'))


if __name__ == '__main__':
    main()
