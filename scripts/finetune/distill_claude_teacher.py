#!/usr/bin/env python3
"""Third, independent teacher on Claude (INTENT-DISTILL-20261111 F-pre).

Claude sees exactly what Terra sees (state, utterance, candidates with the clarify rubric, the
same act/intent rules) and nothing else: not Luna's or Terra's answer, not the generator plan.
Its answer is silver too, never human truth.

Stages:
  label        Claude labels the rows named by --ids-from (review queue, a control sample, ...)
               -> claude-labels.jsonl; receipts + ledger in --work, own USD cap (--cap)
  adjudicate   majority of three on review-queue rows: Claude matching exactly one of Luna/Terra
               (act AND intent, all valid) -> silver-adjudicated.jsonl, split by the same family
               assignment as the unanimous silver; everything else stays in the review queue.
               Also reports Claude vs unanimous silver on the control sample.

Model ``claude-fable-5-2`` with the server-side refusal fallback enabled; an answer served by a
fallback model is recorded but treated as invalid, so the third teacher is always the same model.
Never prints the key. Nothing here trains a model or touches product code or prod data.
"""
import argparse
import json
import random
import sys
import threading
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from distill_common import (ACTS, NONE_KEY, ApiStop, SchemaError, append_jsonl, build_intent_table, check_label, read_jsonl,
                            validate_sample, write_jsonl)  # noqa: E402
import distill_pipeline as dp  # noqa: E402

CLAUDE = 'claude-fable-5-2'
PRICE = (8.0, 40.0)          # USD per 1M tokens (input, output incl. thinking); list price, upper bound
MAX_TOKENS = 4000
KEY_FILE = Path.home() / '.codex/secrets/cretas-anthropic-eval.env'


def load_key(path=KEY_FILE):
    if not Path(path).exists():
        raise ApiStop('Anthropic key file not found')
    for line in Path(path).read_text(encoding='utf-8-sig').splitlines():
        if line.strip().startswith('ANTHROPIC_API_KEY') and '=' in line:
            v = line.split('=', 1)[1].strip().strip('"').strip("'")
            if v:
                return v
    raise ApiStop('ANTHROPIC_API_KEY empty in key file')


def claude_request(row, table):
    """Same instructions and input as terra_payload, Claude-shaped."""
    t = dp.terra_payload(row, table)
    schema = dict(type='object', additionalProperties=False, required=['dialogue_act', 'intent'], properties=dict(
        dialogue_act=dict(type='string', enum=list(ACTS)), intent=dict(type='string', enum=row['candidates'] + [NONE_KEY])))
    return dict(model=CLAUDE, max_tokens=MAX_TOKENS, system=t['instructions'],
                messages=[dict(role='user', content=t['input'])],
                output_config=dict(effort='high', format=dict(type='json_schema', schema=schema)),
                betas=['server-side-fallback-2026-07-01'], fallbacks='default')


class ClaudeClient:
    """Reserve an upper bound before each call; stop before the cap; receipts are resumable by id."""

    def __init__(self, work, cap, concurrency=4):
        import anthropic
        self.anthropic = anthropic
        # gzip only: this machine's brotli/zstd builds reject httpx2's decoder call (TypeError surfaced as
        # APIConnectionError); asking for gzip avoids touching the shared Python env
        self.client = anthropic.Anthropic(api_key=load_key(), max_retries=3, timeout=300,
                                          default_headers={'Accept-Encoding': 'gzip'})
        self.work = Path(work)
        self.work.mkdir(parents=True, exist_ok=True)
        self.results = self.work / 'claude-results.jsonl'
        self.done = {r['id']: r for r in read_jsonl(self.results) if r.get('ok')}
        self.spent = sum(r.get('price_upper_usd', 0) for r in read_jsonl(self.results))
        self.cap, self.reserved = cap, 0.0
        self.lock, self.sem = threading.Lock(), threading.Semaphore(concurrency)

    def call(self, rid, req):
        if rid in self.done:
            return self.done[rid]
        bound = (len(req['system']) + len(req['messages'][0]['content'])) * 2 * PRICE[0] / 1e6 + MAX_TOKENS * PRICE[1] / 1e6
        with self.lock:
            if self.spent + self.reserved + bound > self.cap:
                raise ApiStop('claude cap %.2f would be exceeded (spent %.4f)' % (self.cap, self.spent))
            self.reserved += bound
        try:
            with self.sem:
                kw = dict(req)
                resp = self.client.beta.messages.create(**kw)
            u = resp.usage
            cost = (u.input_tokens + (u.cache_creation_input_tokens or 0) * 1.25 + (u.cache_read_input_tokens or 0)) * PRICE[0] / 1e6 \
                + u.output_tokens * PRICE[1] / 1e6
            fallback = any(getattr(e, 'type', '') == 'fallback_message' for e in (getattr(u, 'iterations', None) or [])) \
                or resp.model != CLAUDE
            text = ''.join(b.text for b in resp.content if b.type == 'text')
            rec = dict(id=rid, ok=True, model=resp.model, stop_reason=resp.stop_reason, fallback=fallback,
                       input_tokens=u.input_tokens, output_tokens=u.output_tokens, price_upper_usd=round(cost, 6),
                       request_id=resp._request_id, text=text)
        except self.anthropic.APIStatusError as e:
            rec = dict(id=rid, ok=False, status=e.status_code, error=str(e.message)[:300], price_upper_usd=0.0)
        except self.anthropic.APIConnectionError as e:
            rec = dict(id=rid, ok=False, status=None, error='connection: ' + str(e)[:200], price_upper_usd=0.0)
        finally:
            with self.lock:
                self.reserved -= bound
        with self.lock:
            self.spent += rec['price_upper_usd']
            append_jsonl(self.results, rec)
            if rec['ok']:
                self.done[rid] = rec
        return rec


def parse(rec, row):
    """-> (act, intent, issue|None) normalised like the pipeline's conclude()."""
    if not rec.get('ok'):
        return None, None, 'call_failed'
    if rec['fallback']:
        return None, None, 'served_by_fallback:%s' % rec['model']
    if rec['stop_reason'] != 'end_turn':
        return None, None, 'stop:%s' % rec['stop_reason']
    try:
        r = json.loads(rec['text'])
    except ValueError:
        return None, None, 'bad_json'
    return dp.conclude(dict(dialogue_act=r['dialogue_act'], intent=r['intent']), row['state'], row['candidates'])


def cmd_label(a, table):
    rows = {r['id']: r for r in read_jsonl(a.rows)}
    ids = json.loads(Path(a.ids_from).read_text(encoding='utf-8'))
    ids = [i for k in a.ids_key for i in ids[k]] if a.ids_key else ids
    ids = list(dict.fromkeys(ids))[:a.limit]
    missing = [i for i in ids if i not in rows]
    if missing:
        raise SystemExit('ids not in --rows: %s' % missing[:3])
    cl = ClaudeClient(a.work, a.cap, a.concurrency)
    out = []
    try:
        with ThreadPoolExecutor(a.concurrency) as ex:
            for row, rec in zip((rows[i] for i in ids), ex.map(lambda i: cl.call('claude:' + i, claude_request(rows[i], table)), ids)):
                act, intent, issue = parse(rec, row)
                out.append(dict(id=row['id'], claude=dict(model=rec.get('model', CLAUDE), dialogue_act=act, intent=intent, issue=issue)))
    except ApiStop as e:
        print('CLAUDE_STOP', e)
    finally:
        write_jsonl(Path(a.work) / 'claude-labels.jsonl', out)
        print('CLAUDE_LABELED %d spent_upper %.4f cap %.2f issues %s' % (
            len(out), cl.spent, a.cap, dict(Counter(o['claude']['issue'] for o in out if o['claude']['issue']))))


def cmd_adjudicate(a, table):
    work = Path(a.work)
    claude = {r['id']: r['claude'] for r in read_jsonl(work / 'claude-labels.jsonl')}
    queue = read_jsonl(a.queue)
    silver = read_jsonl(a.silver)
    assign = {r['family']: r['split'] for f in a.silver_split_from for r in read_jsonl(f)}
    adjudicated, still, why = [], [], Counter()
    for r in queue:
        c = claude.get(r['id'])
        if not c:
            why['no_claude'] += 1
            still.append(r)
            continue
        if c['issue']:
            why['claude_' + c['issue'].split(':')[0]] += 1
            still.append(r)
            continue
        votes = {}
        for name in ('luna', 'terra'):
            t = r['teachers'][name]
            if not t['issue']:
                votes[name] = (t['dialogue_act'], t['intent'])
        match = [n for n, v in votes.items() if v == (c['dialogue_act'], c['intent'])]
        if len(match) == 1:
            act, intent = c['dialogue_act'], c['intent']
            try:
                check_label(act, intent, r['state'], r['candidates'])
            except SchemaError:
                why['label_rule'] += 1
                still.append(r)
                continue
            row = dict(r, dialogue_act=act, intent=intent, read_only=dp.derive_read_only(intent, r['state'], table),
                       agreement=False, adjudication=dict(rule='2_of_3', claude=c, sided_with=match[0]))
            row.pop('review_reason', None)
            if assign:
                row['split'] = assign.get(r['family'], 'train')
            validate_sample(row, table, 'adjudicated')
            adjudicated.append(row)
            why['adjudicated_with_' + match[0]] += 1
        else:
            why['claude_matches_neither' if not match else 'claude_matches_both'] += 1
            still.append(dict(r, claude=c))
    write_jsonl(work / 'silver-adjudicated.jsonl', adjudicated)
    write_jsonl(work / 'review-queue-after-claude.jsonl', still)
    # control: Claude vs unanimous silver
    ctrl = [r for r in silver if r['id'] in claude]
    ok = [r for r in ctrl if not claude[r['id']]['issue']]
    same = sum((claude[r['id']]['dialogue_act'], claude[r['id']]['intent']) == (r['dialogue_act'], r['intent']) for r in ok)
    by_act = defaultdict(lambda: [0, 0])
    for r in ok:
        by_act[r['dialogue_act']][1] += 1
        by_act[r['dialogue_act']][0] += (claude[r['id']]['dialogue_act'], claude[r['id']]['intent']) == (r['dialogue_act'], r['intent'])
    adj_by_act = Counter(r['dialogue_act'] for r in adjudicated)
    rep = dict(queue=len(queue), adjudicated=len(adjudicated), still_in_review=len(still), reasons=dict(why),
               adjudicated_by_act=dict(adj_by_act),
               adjudicated_by_read_only=dict(Counter(dp.ro_bucket(r) for r in adjudicated)),
               control_vs_unanimous_silver=dict(n=len(ctrl), valid=len(ok), same=same,
                                                rate=round(same / len(ok), 4) if ok else None,
                                                by_act={k: dict(n=v[1], same=v[0], rate=round(v[0] / v[1], 4)) for k, v in sorted(by_act.items())}),
               note='2-of-3 teacher majority is silver, kept apart from unanimous silver; not human truth')
    (work / 'adjudication-report.json').write_text(json.dumps(rep, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(rep, ensure_ascii=False, indent=2))


def cmd_select(a, table):
    """ids file: every review-queue row + a seeded control sample of unanimous silver."""
    queue = [r['id'] for r in read_jsonl(a.queue)]
    silver = read_jsonl(a.silver)
    rng = random.Random(a.seed)
    by_act = defaultdict(list)
    for r in silver:
        by_act[r['dialogue_act']].append(r['id'])
    control = []
    per = max(1, a.control // len(by_act))
    for act in sorted(by_act):
        pool = sorted(by_act[act])
        control += rng.sample(pool, min(per, len(pool)))
    Path(a.out).write_text(json.dumps(dict(review_queue=queue, control_silver=control), ensure_ascii=False), encoding='utf-8')
    print('SELECTED review_queue=%d control=%d' % (len(queue), len(control)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('stage', choices=['select', 'label', 'adjudicate'])
    ap.add_argument('--work', required=True)
    ap.add_argument('--rubric', required=True, help='clarify_rubric.json, same as the other two teachers')
    ap.add_argument('--rows', help='label: jsonl holding the rows (labeled-raw or clean)')
    ap.add_argument('--ids-from')
    ap.add_argument('--ids-key', action='append', default=[])
    ap.add_argument('--queue')
    ap.add_argument('--silver')
    ap.add_argument('--silver-split-from', action='append', default=[], help='adjudicate: silver split files to copy family->split from')
    ap.add_argument('--out')
    ap.add_argument('--control', type=int, default=150)
    ap.add_argument('--seed', default='claude-control-20261111')
    ap.add_argument('--cap', type=float, default=80.0, help='Claude USD upper-bound cap for this lane')
    ap.add_argument('--limit', type=int, default=100000)
    ap.add_argument('--concurrency', type=int, default=4)
    a = ap.parse_args()
    table = build_intent_table()
    dp.RUBRIC = dp.load_rubric(a.rubric)
    dp.RUBRIC_MODE = 'full'
    globals()['cmd_' + a.stage](a, table)


if __name__ == '__main__':
    main()
