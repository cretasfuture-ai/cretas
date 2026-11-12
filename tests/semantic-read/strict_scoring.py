"""Strict re-scoring of recorded intent-evaluation traces (Fable independent review §6 / suggestion 4).

Offline only: no model call, no network, no database. It re-reads traces that already exist and
scores them under rules that cannot drift upwards:

1. Three-state verdicts. Every turn is PASS, FAIL or UNMEASURED. Anything that could not be judged
   (decision call errored / 503, gold intent unspecified, a gold parameter the binder does not
   report, no binder output, a parameter-changing turn whose gold carries no parameters) is
   UNMEASURED. UNMEASURED is never added to PASS; it is its own column.
2. Whole-session success. A dialogue passes only when every turn passes. One failing turn fails it;
   otherwise one unmeasured turn makes it unmeasured. Ambiguous turns have no reliable gold and are
   UNMEASURED inside a session.
3. Reads and writes are reported apart. A write item that the write gate turned into a confirmation
   is "write gate correct", never a pass of the headline read rate. Whether the confirmation targets
   the right write intent is a separate column (UNMEASURED when the gold intent is null).
4. Parameters are checked key by key against the gold: missing or different = FAIL; a gold key the
   binder does not report (time_range, material, orderId, basis ... for ``ReadParamProbe``) =
   UNMEASURED. A key the gold lists as null but the binder filled is a FAIL (a filter nobody asked for).
5. Intent equivalence: both readings are reported. ``exact`` (the headline) accepts only the gold
   intent; ``equivalent`` also accepts ``EQUIVALENT`` (ORDER_LIST -> ORDER_STATUS), the convention
   the earlier scorers used silently.
6. Repeated runs: per-turn result vectors are compared. Identical runs are declared as ONE effective
   sample ("runs identical; one effective sample"), never as stability evidence. Rates carry a 95%
   Wilson interval over items (it reflects item sampling only, not run-to-run variance).
7. The real result (rows returned vs an oracle) was not measured by any of these traces; every turn
   reports it as UNMEASURED and no headline includes it. Every trace was run as the factory-director
   role only; other roles are not measured.

Two levels are reported side by side, both strict:
  decision  outcome + target intent (what decision-level sets such as holdout B / E-line C test)
  turn      decision + parameters (what end-to-end sets such as holdout A / E-line M test)
"""
from __future__ import annotations

import argparse
import collections
import json
import math
import pathlib
import subprocess
from typing import Any

PASS, FAIL, UNMEASURED = 'pass', 'fail', 'unmeasured'
VERDICTS = (PASS, FAIL, UNMEASURED)
EQUIVALENT = {'ORDER_LIST': {'ORDER_STATUS'}}
PAGINATION = {'PAGINATION_NEXT', 'PAGINATION_PREVIOUS'}
REFUSE = {'OUT_OF_DOMAIN', 'UNSUPPORTED_CAPABILITY'}
ERROR_OUTCOMES = {'ERROR', 'FALLBACK'}
# Keys ReadParamProbe (main's Java binders) reports. Any other gold key cannot be checked from these traces.
PROBE_KEYS = frozenset({'page', 'size', 'status'})


# ---------------------------------------------------------------- primitives

def combine(*verdicts):
    """Any FAIL -> FAIL; else any UNMEASURED -> UNMEASURED; else PASS. None entries are ignored."""
    vs = [v for v in verdicts if v is not None]
    for v in vs:
        if v not in VERDICTS:
            raise ValueError('not a verdict: %r' % (v,))
    if FAIL in vs:
        return FAIL
    if UNMEASURED in vs:
        return UNMEASURED
    return PASS


def wilson(k, n, z=1.96):
    """95% Wilson score interval for k successes out of n, as percentages; None when n == 0."""
    if not n:
        return None
    p = k / n
    den = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / den
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / den
    return [round(100 * max(0.0, centre - half), 1), round(100 * min(1.0, centre + half), 1)]


def tally(verdicts):
    """Counts per verdict. ``pass_rate`` divides by ALL items (unmeasured is not a pass); the
    ``pass_rate_of_measured`` reading is given for comparison only."""
    c = collections.Counter(v for v in verdicts if v is not None)
    n = sum(c.values())
    k, f, u = c[PASS], c[FAIL], c[UNMEASURED]
    return dict(n=n, pass_=k, fail=f, unmeasured=u,
                pass_rate=round(100 * k / n, 1) if n else None, ci95=wilson(k, n),
                pass_rate_of_measured=round(100 * k / (k + f), 1) if (k + f) else None)


def allowed_intents(gold_intent, mode):
    if gold_intent is None:
        return None
    if mode == 'exact':
        return {gold_intent}
    return {gold_intent} | EQUIVALENT.get(gold_intent, set())


def check_params(gold, bound, checkable=PROBE_KEYS):
    """Key-by-key parameter verdict.

    gold      {key: value} expected effective parameters (value None = must be unset); None = no gold.
    bound     {key: value} the binder produced, or None when no binder output exists.
    checkable keys the binder is able to report.
    Returns (verdict, {key: verdict}).
    """
    if not gold:
        return UNMEASURED, {'*': UNMEASURED}
    detail = {}
    for key, want in gold.items():
        if key not in checkable:
            # an unset gold key the binder cannot see cannot be wrong; a set one cannot be checked
            detail[key] = None if want is None else UNMEASURED
        elif bound is None:
            detail[key] = UNMEASURED
        else:
            got = bound.get(key)
            detail[key] = PASS if got == want else FAIL
    detail = {k: v for k, v in detail.items() if v is not None}
    return combine(*detail.values()), detail


# ---------------------------------------------------------------- per-turn scoring

def decide(expected, outcome, intent, gold_intent, mode, prev_exec=None, gold_act=None, error=False):
    """Strict decision verdict for one read-side turn (expected in EXECUTE/CLARIFY/NONE/REFUSE)."""
    if error or outcome in ERROR_OUTCOMES:
        return UNMEASURED
    if expected == 'CLARIFY':
        return PASS if outcome == 'CLARIFY' else FAIL
    if expected == 'NONE':
        return PASS if outcome == 'NONE' else FAIL
    if expected == 'REFUSE':
        return PASS if outcome == 'NONE' or (outcome == 'EXECUTE' and intent in REFUSE) else FAIL
    if expected != 'EXECUTE':
        raise ValueError(expected)
    if outcome != 'EXECUTE':
        return FAIL
    allowed = allowed_intents(gold_intent, mode)
    if allowed is None:
        return UNMEASURED                      # gold says "any / undetermined": no target to check
    if intent in allowed:
        return PASS
    if intent in PAGINATION and gold_act == 'PAGE' and prev_exec in allowed:
        return PASS                            # server paging re-runs the previous (right) read
    return FAIL


def write_gate(outcome, error=False):
    """A write item: anything but direct execution is a correct gate. Errors are unmeasured."""
    if error or outcome in ERROR_OUTCOMES:
        return UNMEASURED
    return FAIL if outcome == 'EXECUTE' else PASS


def write_target(outcome, intent, top, gold_intent, mode, error=False):
    """Did the confirmation point at the right write intent? Only judged for CONFIRM_WRITE."""
    if error or outcome in ERROR_OUTCOMES or outcome != 'CONFIRM_WRITE':
        return None
    allowed = allowed_intents(gold_intent, mode)
    if allowed is None:
        return UNMEASURED
    chosen = intent or (top[0] if top else None)
    return PASS if chosen in allowed else FAIL


def score_turn(t, mode):
    """t: normalised turn (see ``normalise_*``). Returns dict with decision / params / turn verdicts."""
    err = bool(t.get('error'))
    out: dict[str, Any] = dict(set=t['set'], dialogue=t['dialogue'], turn=t['turn'], expected=t['expected'],
               ambiguous=bool(t.get('ambiguous')), read_only=t.get('read_only'), act=t.get('gold_act'),
               outcome=t['outcome'], error=err, real_result=UNMEASURED)
    if t['expected'] == 'WRITE':
        out.update(kind='write', write_gate=write_gate(t['outcome'], err),
                   write_target=write_target(t['outcome'], t.get('intent'), t.get('top'), t.get('gold_intent'), mode, err),
                   decision=None, params=None, turn_verdict=None)
        out['session_verdict'] = combine(out['write_gate'], out['write_target'])
        return out
    decision = decide(t['expected'], t['outcome'], t.get('intent'), t.get('gold_intent'), mode,
                      t.get('prev_exec'), t.get('gold_act'), err)
    params, detail = None, {}
    if t['expected'] == 'EXECUTE' and t['outcome'] == 'EXECUTE' and decision != FAIL:
        if t.get('param_scope') == 'gold':
            params, detail = check_params(t.get('gold_params'), t.get('bound'), t.get('checkable', PROBE_KEYS))
        elif t.get('param_scope') == 'unknown':
            params, detail = UNMEASURED, {'*': UNMEASURED}
        # param_scope 'none': the gold asserts no parameters for this turn
    turn = combine(decision, params)
    out.update(kind='read', decision=decision, params=params, params_detail=detail, turn_verdict=turn,
               write_gate=None, write_target=None,
               session_verdict=UNMEASURED if out['ambiguous'] else turn)
    return out


def session_verdicts(scored, drop_ambiguous=False):
    """{(set, dialogue): verdict} for multi-turn sets (every turn must pass).

    ``drop_ambiguous`` is a secondary reading only: ambiguous turns are left out instead of making
    the dialogue unmeasured (dialogues made only of ambiguous turns disappear)."""
    by = collections.defaultdict(list)
    for s in scored:
        if s['set'] in MULTI_TURN_SETS and not (drop_ambiguous and s['ambiguous']):
            by[(s['set'], s['dialogue'])].append(s['session_verdict'])
    return {k: combine(*v) for k, v in by.items()}


MULTI_TURN_SETS = {'A', 'M'}


# ---------------------------------------------------------------- run comparison

def run_signature(turns):
    return {(t['set'], t['dialogue'], t['turn']): json.dumps(
        [t['outcome'], t.get('intent'), (t.get('top') or [None])[0], bool(t.get('error'))]) for t in turns}


def run_identity(runs):
    """Compare per-turn decisions across runs. ``effective_samples`` = 1 when all runs agree."""
    sigs = [run_signature(r) for r in runs]
    keys = set().union(*sigs) if sigs else set()
    differing = sorted(k for k in keys if len({s.get(k) for s in sigs}) > 1)
    only_errors = all(any(json.loads(s[k])[3] for s in sigs if k in s) for k in differing)
    if len(runs) <= 1:
        note = 'single run'
    elif not differing:
        note = '%d runs identical: one effective sample, not stability evidence' % len(runs)
    elif only_errors:
        note = ('%d runs identical except %d turn(s) where a decision call errored: one effective sample, '
                'not stability evidence' % (len(runs), len(differing)))
    else:
        note = '%d runs differ on %d turn(s)' % (len(runs), len(differing))
    effective = 1 if (len(runs) <= 1 or not differing or only_errors) else len(runs)
    return dict(runs=len(runs), differing_turns=len(differing), differing_only_by_error=bool(differing) and only_errors,
                effective_samples=effective, note=note)


# ---------------------------------------------------------------- summaries

def summarize(scored, mode_label):
    out = {}
    for s in sorted({x['set'] for x in scored}):
        for amb in (False, True):
            sub = [x for x in scored if x['set'] == s and x['ambiguous'] == amb]
            if not sub:
                continue
            reads = [x for x in sub if x['kind'] == 'read']
            ro_reads = [x for x in reads if x['read_only'] is not False]
            writes = [x for x in sub if x['kind'] == 'write']
            execs = [x for x in reads if x['expected'] == 'EXECUTE']
            unmeasured_reasons = collections.Counter()
            for x in reads:
                if x['turn_verdict'] == UNMEASURED:
                    if x['error']:
                        unmeasured_reasons['decision_error'] += 1
                    elif x['decision'] == UNMEASURED:
                        unmeasured_reasons['gold_intent_unspecified'] += 1
                    else:
                        for k, v in x['params_detail'].items():
                            if v == UNMEASURED:
                                unmeasured_reasons['param:' + k] += 1
            entry = dict(
                items=len(sub), intent_mode=mode_label,
                read_decision=tally(x['decision'] for x in reads),
                read_turn=tally(x['turn_verdict'] for x in reads),
                read_only_turn=tally(x['turn_verdict'] for x in ro_reads),
                read_execute_turn=tally(x['turn_verdict'] for x in execs),
                read_params=tally(x['params'] for x in execs),
                read_by_expected={k: tally(x['turn_verdict'] for x in reads if x['expected'] == k)
                                  for k in sorted({x['expected'] for x in reads})},
                read_by_act={k: tally(x['turn_verdict'] for x in reads if x['act'] == k)
                             for k in sorted({str(x['act']) for x in reads}) if k != 'None'},
                write_gate=tally(x['write_gate'] for x in writes),
                write_target=tally(x['write_target'] for x in writes),
                write_not_confirmed=sum(1 for x in writes if x['write_gate'] == PASS and x['write_target'] is None),
                real_result=dict(unmeasured=len(sub), note='not measured by these traces'),
                turn_unmeasured_reasons=dict(unmeasured_reasons.most_common()),
                errors=sum(1 for x in sub if x['error']),
            )
            out[s + ('-ambiguous' if amb else '')] = entry
        if s in MULTI_TURN_SETS:
            sess = {k: v for k, v in session_verdicts(scored).items() if k[0] == s}
            out[s + '-sessions'] = tally(sess.values())
            sess2 = {k: v for k, v in session_verdicts(scored, drop_ambiguous=True).items() if k[0] == s}
            out[s + '-sessions-ambiguous-turns-dropped'] = tally(sess2.values())
    out['roles'] = 'factory director only; other roles not measured'
    return out


def score_runs(runs_turns):
    """runs_turns: list (one per run) of normalised turn lists -> both intent modes for run 1 + identity."""
    first = runs_turns[0]
    result: dict[str, Any] = dict(identity=run_identity(runs_turns))
    for mode in ('exact', 'equivalent'):
        result[mode] = summarize([score_turn(t, mode) for t in first], mode)
    if result['identity']['effective_samples'] > 1:
        result['per_run_exact'] = [summarize([score_turn(t, 'exact') for t in r], 'exact') for r in runs_turns]
    return result


# ---------------------------------------------------------------- adapters: holdout (independent acceptance)

def holdout_expected(g):
    if g.get('read_only') is False or g.get('must_not_direct_execute'):
        return 'WRITE'
    if g.get('should_clarify'):
        return 'CLARIFY'
    if g.get('dialogue_act') == 'CANCEL':
        return 'NONE'
    if g.get('expect_out_of_domain') or g.get('expect_unsupported_refusal') or g.get('intent') in REFUSE:
        return 'REFUSE'
    return 'EXECUTE'


def load_holdout_gold(sealed):
    sealed = pathlib.Path(sealed)
    A = json.loads((sealed / 'A_multiturn.json').read_text('utf-8'))['dialogues']
    B = [json.loads(x) for x in (sealed / 'B_decision.jsonl').read_text('utf-8').splitlines() if x.strip()]
    out = {}
    for d in A:
        for t in d['turns']:
            out[('A', d['id'], t['turn'])] = dict(t)
    for r in B:
        out[('B', r['id'], 1)] = dict(r, input=r['utterance'], parameters=r.get('expected_parameters'))
    return out


def normalise_holdout(rows, gold, bound):
    """rows: one holdout_eval.py run; bound: {(id, turn): probe output} for set A (may be empty)."""
    prev_exec = {}
    out = []
    for r in sorted(rows, key=lambda r: (r['set'], r['id'], r['turn'])):
        g = gold[(r['set'], r['id'], r['turn'])]
        o = r['outcome']
        gp = g.get('parameters') if isinstance(g.get('parameters'), dict) else None
        if r['set'] == 'A':
            b = bound.get((r['id'], r['turn']))
            bparams = None if b is None or 'params' not in b else b['params']
            scope = 'gold' if gp is not None else ('unknown' if g.get('dialogue_act') in ('CHANGE_PARAM', 'PAGE') else 'none')
        else:
            bparams = None                         # set B was never bound
            scope = 'gold' if gp else ('unknown' if g.get('dialogue_act') in ('CHANGE_PARAM', 'PAGE') else 'none')
        out.append(dict(set=r['set'], dialogue=r['id'], turn=r['turn'], expected=holdout_expected(g),
                        gold_intent=g.get('intent'), gold_act=g.get('dialogue_act'), read_only=g.get('read_only'),
                        ambiguous=bool(g.get('ambiguous')), outcome=o, intent=r.get('intent'), top=r.get('top'),
                        error=r.get('error'), prev_exec=prev_exec.get(r['id']), gold_params=gp,
                        bound=bparams, param_scope=scope))
        if r['set'] == 'A':
            prev_exec[r['id']] = r['intent'] if o == 'EXECUTE' else (prev_exec.get(r['id']) if o == 'CLARIFY' else None)
    return out


def bind_holdout(rows, gold, classpath):
    """Run ReadParamProbe over set A (same feed as holdout_score.py)."""
    feed, keys = [], []
    for r in sorted((r for r in rows if r['set'] == 'A'), key=lambda r: (r['id'], r['turn'])):
        feed.append(json.dumps(dict(case=r['id'], intent=r.get('intent'), sentence=gold[('A', r['id'], r['turn'])]['input'],
                                    act=r.get('act'), outcome=r['outcome']), ensure_ascii=False))
        keys.append((r['id'], r['turn']))
    proc = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', classpath,
                           'com.cretas.aims.service.execution.ReadParamProbe'], input='\n'.join(feed) + '\n',
                          capture_output=True, text=True, encoding='utf-8')
    if proc.returncode != 0:
        raise SystemExit('probe failed (rc %d): %s' % (proc.returncode, proc.stderr[-2000:]))
    outs = [json.loads(x) for x in proc.stdout.splitlines() if x.strip().startswith('{')]
    if len(outs) != len(keys):
        raise SystemExit('probe row count mismatch: %d vs %d' % (len(outs), len(keys)))
    return dict(zip(keys, outs))


# ---------------------------------------------------------------- adapters: E-line development set

def eline_expected(r):
    if r.get('read_only') is False:
        return 'WRITE'
    return {'NEED_CLARIFY': 'CLARIFY', 'CANCEL': 'NONE'}.get(r['gold_act'], 'EXECUTE')


def m_gold_act(turns, i):
    t = turns[i]
    if i == 0 or (turns[i - 1]['gold_intent'], turns[i - 1].get('gold_basis')) != (t['gold_intent'], t.get('gold_basis')):
        return 'NEW_TASK'
    p, q = turns[i - 1]['gold_params'], t['gold_params']
    if q.get('page') == p.get('page', 0) + 1 and q.get('status') == p.get('status') and q.get('size') == p.get('size'):
        return 'PAGE'
    return 'CHANGE_PARAM'


def normalise_eline(rows):
    """rows: one run of ``turns.params.jsonl`` (decision_layer_eval / nogen_eval + apply_param_probe)."""
    by_case = collections.defaultdict(list)
    for r in rows:
        by_case[(r['part'], r['case_id'])].append(r)
    out = []
    for (part, case), turns in sorted(by_case.items()):
        turns.sort(key=lambda r: r['turn'])
        prev_exec = None
        for i, r in enumerate(turns):
            act = m_gold_act(turns, i) if part == 'M' else r['gold_act']
            kind = r['outcome']
            b = r.get('bound')
            bparams = b.get('params') if isinstance(b, dict) and 'params' in b else None
            gp = r.get('gold_params') if part == 'M' else None
            scope = 'gold' if gp else ('unknown' if act in ('CHANGE_PARAM', 'PAGE') else 'none')
            out.append(dict(set=part, dialogue=case, turn=r['turn'], expected=eline_expected(dict(r, gold_act=act)),
                            gold_intent=r['gold_intent'], gold_act=act, read_only=r.get('read_only', True),
                            ambiguous=False, outcome=kind, intent=r.get('intent'), top=r.get('top'),
                            error=r.get('error'), prev_exec=prev_exec if part == 'M' else r.get('prev_intent'),
                            gold_params=gp, bound=bparams, param_scope=scope))
            if kind == 'EXECUTE':
                prev_exec = r['intent'] if r['intent'] not in PAGINATION else prev_exec
            elif part == 'M':
                prev_exec = prev_exec if kind == 'CLARIFY' else None
    return out


# ---------------------------------------------------------------- CLI

def load_jsonl(path):
    return [json.loads(x) for x in pathlib.Path(path).read_text('utf-8').splitlines() if x.strip()]


def main():
    p = argparse.ArgumentParser(description='Strict offline re-scoring of recorded traces.')
    sub = p.add_subparsers(dest='cmd', required=True)
    h = sub.add_parser('holdout', help='holdout_eval.py runs')
    h.add_argument('--sealed', required=True, type=pathlib.Path)
    h.add_argument('--run', action='append', required=True, type=pathlib.Path, help='run-N.jsonl (repeatable)')
    h.add_argument('--classpath', help='ReadParamProbe classpath; bound params cached next to --output')
    h.add_argument('--bound', action='append', type=pathlib.Path, help='cached bound-N.jsonl per run (same order)')
    h.add_argument('--output', required=True, type=pathlib.Path)
    e = sub.add_parser('eline', help='E-line turns.params.jsonl')
    e.add_argument('--run', action='append', required=True, help='version=path/to/turns.params.jsonl')
    e.add_argument('--output', required=True, type=pathlib.Path)
    a = p.parse_args()
    result = {}
    if a.cmd == 'holdout':
        gold = load_holdout_gold(a.sealed)
        runs = []
        for i, path in enumerate(a.run):
            rows = load_jsonl(path)
            if a.bound and i < len(a.bound) and a.bound[i].exists():
                bound = {(x['id'], x['turn']): x['out'] for x in load_jsonl(a.bound[i])}
            elif a.classpath:
                bound = bind_holdout(rows, gold, a.classpath)
                cache = a.output.with_name('bound-%d.jsonl' % (i + 1))
                cache.write_text('\n'.join(json.dumps(dict(id=k[0], turn=k[1], out=v), ensure_ascii=False)
                                           for k, v in sorted(bound.items())) + '\n', 'utf-8')
            else:
                bound = {}
            runs.append(normalise_holdout(rows, gold, bound))
        result['holdout'] = score_runs(runs)
    else:
        for spec in a.run:
            version, path = spec.split('=', 1)
            per = collections.defaultdict(list)
            for r in load_jsonl(path):
                per[r['run']].append(r)
            result[version] = score_runs([normalise_eline(v) for _, v in sorted(per.items())])
    a.output.write_text(json.dumps(result, ensure_ascii=False, indent=1), 'utf-8')
    print('wrote', a.output)


if __name__ == '__main__':
    main()
