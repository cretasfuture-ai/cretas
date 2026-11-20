#!/usr/bin/env python3
"""Clarify agreement before vs after the rubric, on the same rows (INTENT-DISTILL-20261111 F-pre).

Agreement uses the pipeline's own ``agreed()`` (same act AND same intent AND both valid) on both
sides — the rule is not loosened. ``--before`` is the pilot's labeled-raw.jsonl, ``--after`` the
relabel output; ``--selection`` names the two subsets (plan NEED_CLARIFY / clarify-related queue).
Teacher agreement is silver, not human truth.
"""
import argparse
import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from distill_common import read_jsonl  # noqa: E402
from distill_pipeline import agreed, conclude  # noqa: E402


def summary(rows):
    n = len(rows)
    ag = [r for r in rows if agreed(r)]
    labels = Counter(conclude(r['luna'], r['state'], r['candidates'])[0] for r in ag)
    pairs = Counter((r['luna']['dialogue_act'], r['terra']['dialogue_act']) for r in rows if not agreed(r))
    return dict(n=n, agreed=len(ag), rate=round(len(ag) / n, 4) if n else None,
                agreed_act_labels=dict(labels), disagreement_pairs_luna_terra={'%s|%s' % k: v for k, v in pairs.most_common()})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--before', required=True)
    ap.add_argument('--after', required=True)
    ap.add_argument('--selection', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    before = {r['id']: r for r in read_jsonl(a.before)}
    after = {r['id']: r for r in read_jsonl(a.after)}
    sel = json.loads(Path(a.selection).read_text(encoding='utf-8'))
    subsets = dict(sel, union=list(dict.fromkeys(sel['plan_need_clarify'] + sel['queue_clarify_related'])))
    out = {}
    for name, ids in subsets.items():
        missing = [i for i in ids if i not in after]
        if missing:
            raise SystemExit('after is missing %d rows of %s, e.g. %s' % (len(missing), name, missing[:3]))
        out[name] = dict(before=summary([before[i] for i in ids]), after=summary([after[i] for i in ids]))
    # where the remaining disagreements sit, by family (union)
    fam = Counter(after[i]['family'] for i in subsets['union'] if not agreed(after[i]))
    out['after_disagreements_by_family'] = dict(fam.most_common())
    out['note'] = 'agreed() unchanged on both sides; teacher agreement is silver, not human truth'
    Path(a.out).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(out, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
