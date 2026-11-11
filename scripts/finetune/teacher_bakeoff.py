#!/usr/bin/env python3
"""Teacher bake-off for INTENT-DISTILL (which models should label, at which effort).

Every contestant sees exactly what the current second teacher (Terra) sees: the same
instructions (act rules + clarify rubric + intent rules) and the same input (state, utterance,
candidates with rubric fields), with a strict JSON schema. No contestant sees another's answer,
the generator plan, or any silver label.

Stages (all outputs in --work, outside the repo):
  build    stratified question set from already-labelled rows (+ every three-way disagreement)
  run      --models a,b --effort low [--subset hard.json] ; resumable receipts, per-provider USD cap
  score    majority (leave-one-out) agreement overall / by act / by read-write, invalid rate,
           cost per item, p50 latency; split items -> review file for Steve; hard subset
  steve    after Steve fills the review file: accuracy of each contestant on those items

The bake-off never trains anything, never touches product code or prod data, and never opens
the sealed acceptance set (questions come from the leak-checked distillation pool).
"""
import argparse
import json
import random
import statistics
import sys
import threading
import time
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from distill_common import ACTS, NONE_KEY, build_intent_table, read_jsonl, write_jsonl, append_jsonl  # noqa: E402
import distill_pipeline as dp  # noqa: E402

DOCS = Path.home() / 'Documents' / 'Cretas意图蒸馏-20261111'
# list prices, USD per 1M tokens: (input, cached input, output). OpenAI: developers.openai.com pricing
# page read 2026-11-12; Claude: claude-api skill model table (cached 2026-11-03), cache write 1.25x input.
CONTESTANTS = {
    'gpt-6-astra': dict(provider='openai', price=(10.0, 1.0, 50.0), efforts=('low', 'medium', 'high', 'xhigh', 'max')),
    'gpt-6.1-sol': dict(provider='openai', price=(2.0, 0.10, 10.0), efforts=('low', 'medium', 'high', 'xhigh', 'max')),
    'gpt-6-sol': dict(provider='openai', price=(2.0, 0.20, 10.0), efforts=('none', 'low', 'medium', 'high', 'xhigh', 'max')),
    'gpt-6.1-terra': dict(provider='openai', price=(1.0, 0.05, 5.0), efforts=('none', 'low', 'medium', 'high', 'xhigh', 'max')),
    'claude-fable-5-2': dict(provider='anthropic', price=(8.0, 0.20, 40.0), efforts=('low', 'medium', 'high', 'xhigh', 'max')),
    'claude-opus-5-5': dict(provider='anthropic', price=(4.0, 0.20, 20.0), efforts=('low', 'medium', 'high', 'xhigh', 'max')),
    'claude-sonnet-5-5': dict(provider='anthropic', price=(2.0, 0.10, 10.0), efforts=('low', 'medium', 'high', 'xhigh', 'max')),
}
MAX_OUT = 4000


def schema(row):
    return dict(type='object', additionalProperties=False, required=['dialogue_act', 'intent'], properties=dict(
        dialogue_act=dict(type='string', enum=list(ACTS)), intent=dict(type='string', enum=row['candidates'] + [NONE_KEY])))


def openai_payload(row, table, model, effort):
    t = dp.terra_payload(row, table)
    return dict(model=model, store=False, reasoning=dict(effort=effort), instructions=t['instructions'], input=t['input'],
                max_output_tokens=MAX_OUT, text=dict(format=dict(type='json_schema', name='distill_label', strict=True, schema=schema(row))))


def anthropic_payload(row, table, model, effort):
    t = dp.terra_payload(row, table)
    # identical instruction text; the long shared prefix is cached (cost only, no behaviour change)
    return dict(model=model, max_tokens=MAX_OUT, system=[dict(type='text', text=t['instructions'], cache_control=dict(type='ephemeral'))],
                messages=[dict(role='user', content=t['input'])],
                output_config=dict(effort=effort, format=dict(type='json_schema', schema=schema(row))))


def load_env(path, key):
    for line in Path(path).read_text(encoding='utf-8-sig').splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            if k.strip() == key:
                return v.strip().strip('"').strip("'")
    return {}


class Runner:
    """Per-provider cap; receipts keyed by (model, effort, qid); a failed call is recorded, never fatal."""

    def __init__(self, work, caps, concurrency):
        self.work = Path(work)
        self.path = self.work / 'receipts.jsonl'
        self.done = {}
        for r in read_jsonl(self.path):
            self.done[(r['model'], r['effort'], r['qid'])] = r
        self.spent = Counter()
        for r in self.done.values():
            self.spent[CONTESTANTS[r['model']]['provider']] += r.get('cost', 0.0)
        self.caps, self.reserved = caps, Counter()
        self.lock = threading.Lock()
        self.sems = {p: threading.Semaphore(concurrency[p]) for p in concurrency}
        self.openai = self.anthropic = None

    def _openai(self):
        if self.openai is None:
            import httpx
            kf = Path.home() / '.codex/secrets/cretas-openai-eval.env'
            key = load_env(kf, 'OPENAI_API_KEY')
            if not key:
                raise SystemExit('OPENAI_API_KEY missing')
            h = {'Authorization': 'Bearer ' + key}
            for k, name in (('OPENAI_PROJECT_ID', 'OpenAI-Project'), ('OPENAI_ORGANIZATION_ID', 'OpenAI-Organization')):
                v = load_env(kf, k)
                if v:
                    h[name] = v
            self.openai = httpx.Client(headers=h, timeout=300)
        return self.openai

    def _anthropic(self):
        if self.anthropic is None:
            import anthropic
            from distill_claude_teacher import load_key
            self.anthropic = anthropic.Anthropic(api_key=load_key(), max_retries=2, timeout=300,
                                                 default_headers={'Accept-Encoding': 'gzip'})
        return self.anthropic

    def call(self, row, table, model, effort):
        k = (model, effort, row['id'])
        if k in self.done and self.done[k].get('ok'):
            return self.done[k]
        c = CONTESTANTS[model]
        prov, (pi, pc, po) = c['provider'], c['price']
        payload = (openai_payload if prov == 'openai' else anthropic_payload)(row, table, model, effort)
        reserve = (len(json.dumps(payload, ensure_ascii=False)) * pi + MAX_OUT * po) / 1e6
        with self.lock:
            if self.spent[prov] + self.reserved[prov] + reserve > self.caps[prov]:
                raise CapReached('%s cap %.2f (spent %.4f)' % (prov, self.caps[prov], self.spent[prov]))
            self.reserved[prov] += reserve
        rec = dict(qid=row['id'], model=model, effort=effort, ok=False)
        try:
            with self.sems[prov]:
                t0 = time.perf_counter()
                if prov == 'openai':
                    resp = self._openai().post('https://api.openai.com/v1/responses', json=payload)
                    rec['latency_ms'] = (time.perf_counter() - t0) * 1000
                    rec['status'] = resp.status_code
                    body = resp.json()
                    if resp.status_code != 200:
                        rec['error'] = str(body.get('error', body))[:300]
                    else:
                        u = body.get('usage') or {}
                        cached = (u.get('input_tokens_details') or {}).get('cached_tokens') or 0
                        rec.update(input_tokens=u.get('input_tokens', 0), cached_tokens=cached, output_tokens=u.get('output_tokens', 0),
                                   reasoning_tokens=(u.get('output_tokens_details') or {}).get('reasoning_tokens'),
                                   served_model=body.get('model'), status_text=body.get('status'))
                        rec['cost'] = ((rec['input_tokens'] - cached) * pi + cached * pc + rec['output_tokens'] * po) / 1e6
                        rec['text'] = ''.join(p.get('text', '') for it in body.get('output', []) for p in it.get('content', []) or []
                                              if p.get('type') == 'output_text')
                        rec['ok'] = True
                else:
                    an = self._anthropic()
                    import anthropic
                    try:
                        m = an.messages.create(**payload)
                        rec['latency_ms'] = (time.perf_counter() - t0) * 1000
                        u = m.usage
                        cw, cr = u.cache_creation_input_tokens or 0, u.cache_read_input_tokens or 0
                        rec.update(status=200, input_tokens=u.input_tokens, cache_write_tokens=cw, cached_tokens=cr,
                                   output_tokens=u.output_tokens, served_model=m.model, stop_reason=m.stop_reason)
                        rec['cost'] = (u.input_tokens * pi + cw * pi * 1.25 + cr * pc + u.output_tokens * po) / 1e6
                        rec['text'] = ''.join(b.text for b in m.content if b.type == 'text')
                        rec['ok'] = True
                    except anthropic.APIStatusError as e:
                        rec.update(status=e.status_code, error=str(e.message)[:300], latency_ms=(time.perf_counter() - t0) * 1000)
        except Exception as e:                       # connection errors etc.: recorded, never fatal
            rec.update(error='%s: %s' % (type(e).__name__, str(e)[:200]))
        finally:
            with self.lock:
                self.reserved[prov] -= reserve
                self.spent[prov] += rec.get('cost', 0.0)
                append_jsonl(self.path, rec)
                self.done[k] = rec
        return rec


class CapReached(RuntimeError):
    pass


def parse(rec, row):
    """-> (act, intent, issue). Invalid when the call failed, was truncated, served by another
    model, or broke the label rules (same normalisation as the pipeline's conclude())."""
    if not rec.get('ok'):
        return None, None, 'call_failed'
    if rec.get('stop_reason') not in (None, 'end_turn'):
        return None, None, 'stop:%s' % rec['stop_reason']
    if rec.get('status_text') not in (None, 'completed'):
        return None, None, 'status:%s' % rec['status_text']
    served = rec.get('served_model') or ''
    if not served.startswith(rec['model']):
        return None, None, 'served_by:%s' % served
    try:
        r = json.loads(rec['text'])
    except (ValueError, TypeError):
        return None, None, 'bad_json'
    return dp.conclude(dict(dialogue_act=r['dialogue_act'], intent=r['intent']), row['state'], row['candidates'])


# ---------------------------------------------------------------- build
def bucket_act(r):
    act = r.get('dialogue_act') or (r.get('generator_plan') or {}).get('dialogue_act')
    return 'NEED_CLARIFY' if act == 'NEED_CLARIFY' else 'OTHER'


def bucket_rw(r):
    return {True: 'read', False: 'write', None: 'none'}[r.get('read_only')]


def build(n, seed, sources, threeway_path):
    three = read_jsonl(threeway_path)
    ids3 = {r['id'] for r in three}
    pool, seen = [], set(ids3)
    for p in sources:
        for r in read_jsonl(p):
            if r['id'] not in seen:
                seen.add(r['id'])
                pool.append(r)
    rng = random.Random(seed)
    cells = defaultdict(lambda: defaultdict(list))
    for r in pool:
        cells[(bucket_act(r), bucket_rw(r))][r['family']].append(r)
    keys = sorted(cells)
    need = n - len(three)
    per = {k: need // len(keys) + (1 if i < need % len(keys) else 0) for i, k in enumerate(keys)}
    chosen = []
    for k in keys:
        fams = cells[k]
        for f in fams.values():
            rng.shuffle(f)
        order = sorted(fams)
        rng.shuffle(order)
        got, i = [], 0
        while len(got) < per[k] and any(fams[f] for f in order):      # round-robin over families
            f = order[i % len(order)]
            if fams[f]:
                got.append(fams[f].pop())
            i += 1
        chosen += got
    short = n - len(three) - len(chosen)
    if short > 0:                                                        # thin cell: top up from the rest
        rest = [r for k in keys for f in cells[k].values() for r in f]
        rng.shuffle(rest)
        chosen += rest[:short]
    qs = [dict(id=r['id'], utterance=r['utterance'], state=r['state'], candidates=r['candidates'], family=r['family'],
               read_only=r.get('read_only'), cell='%s|%s' % (bucket_act(r), bucket_rw(r)),
               threeway=r['id'] in ids3) for r in three + chosen]
    return qs


# ---------------------------------------------------------------- score
def answers(work, qs, table, effort_of):
    rec = {(r['model'], r['effort'], r['qid']): r for r in read_jsonl(Path(work) / 'receipts.jsonl')}
    out = defaultdict(dict)
    for q in qs:
        for m, eff in effort_of.items():
            r = rec.get((m, eff, q['id']))
            out[q['id']][m] = parse(r, q) if r else (None, None, 'not_run')
    return out, rec


def majority(votes, exclude=None):
    """Answer held by more than half of the valid voters (leave-one-out when exclude is given)."""
    valid = [(a, i) for m, (a, i, bad) in votes.items() if not bad and m != exclude]
    if not valid:
        return None, 0
    (ans, k), = Counter(valid).most_common(1)
    return (ans, len(valid)) if k * 2 > len(valid) else (None, len(valid))


def wilson(k, n, z=1.959964):
    if n == 0:
        return (None, None)
    p = k / n
    d = 1 + z * z / n
    c = p + z * z / (2 * n)
    h = z * (p * (1 - p) / n + z * z / (4 * n * n)) ** .5
    return ((c - h) / d, (c + h) / d)


def score(qs, ans, rec, effort_of):
    res = {}
    for m, eff in effort_of.items():
        agree = defaultdict(lambda: [0, 0])
        invalid = 0
        costs, lats = [], []
        for q in qs:
            a, i, bad = ans[q['id']][m]
            r = rec.get((m, eff, q['id']))
            if r:
                costs.append(r.get('cost', 0.0))
                if r.get('latency_ms'):
                    lats.append(r['latency_ms'])
            if bad:
                invalid += 1
            ref, _ = majority(ans[q['id']], exclude=m)
            if ref is None:
                continue
            ok = (not bad) and (a, i) == ref
            act_ref = ref[0]
            for key in ('all', 'act:' + ('NEED_CLARIFY' if act_ref == 'NEED_CLARIFY' else 'OTHER'), 'act5:' + act_ref,
                        'rw:' + bucket_rw(dict(read_only=dp.derive_read_only(ref[1], q['state'], TABLE)))):
                agree[key][0] += ok
                agree[key][1] += 1
        res[m] = dict(effort=eff, n=len(qs), invalid=invalid, invalid_rate=invalid / len(qs),
                      agreement={k: dict(agree=v[0], n=v[1], rate=v[0] / v[1], ci=wilson(*v)) for k, v in sorted(agree.items())},
                      cost_per_item=statistics.mean(costs) if costs else None, cost_total=sum(costs),
                      p50_latency_ms=statistics.median(lats) if lats else None)
    return res


TABLE = None


def top_votes(votes):
    valid = [(a, i) for a, i, bad in votes.values() if not bad]
    return Counter(valid).most_common(1)[0][1] if valid else 0


def review_md(qs, ans, table, models, limit=50, max_top=4):
    """Items with no majority, then bare majorities (top answer held by <= max_top voters),
    three-way-disagreement items first; at most ``limit``."""
    split = [q for q in qs if majority(ans[q['id']])[0] is None or top_votes(ans[q['id']]) <= max_top]
    split = sorted(split, key=lambda q: (majority(ans[q['id']])[0] is not None, top_votes(ans[q['id']]), not q['threeway'], q['id']))[:limit]
    lines = ['# 老师对比：多数意见分裂或勉强过半的题（请 Steve 复核）', '', '前 12 题没有任何答案过半；后面是 7 位选手里只有 4 位同意的题（多数是 OpenAI 四个模型对 Claude 三个模型）。', '',
             '每题列出当前句、上一轮状态、候选，以及各选手的答案（对话动作 + 意图）。请在「Steve 意见：」后写正确答案，'
             '格式：`动作 意图ID`（如 `NEW_TASK ORDER_LIST`，`NEED_CLARIFY` / `CANCEL` 不写意图），或写「都不对：…」。', '',
             '对话动作：NEW_TASK 新任务 / CHANGE_PARAM 改参数 / PAGE 翻页 / CANCEL 取消 / NEED_CLARIFY 需要澄清。', '']
    for n, q in enumerate(split, 1):
        st = q['state'] or {}
        lines.append('## %d. %s%s' % (n, q['id'], '（三方分歧题）' if q['threeway'] else ''))
        lines.append('')
        lines.append('- 当前句：**%s**' % q['utterance'])
        if st:
            prev = st.get('prev_intent')
            lines.append('- 上一轮：%s（%s）；已确认：%s；待答：%s；上次动作：%s' % (
                table[prev]['name'] if prev in table else prev, prev,
                '、'.join('%s=%s' % (p['name'], p['value']) for p in st.get('confirmed_params') or []) or '无',
                '、'.join(st.get('pending_fields') or []) or '无', st.get('last_action') or '无'))
        else:
            lines.append('- 上一轮：无（全新会话）')
        lines.append('- 候选：' + '；'.join('%s %s（%s）' % (c, table[c]['name'], '只读' if table[c]['read_only'] else '写') for c in q['candidates']))
        lines.append('- 各选手：')
        groups = defaultdict(list)
        for m in models:
            a, i, bad = ans[q['id']][m]
            groups['无效（%s）' % bad if bad else '%s %s' % (a, ('%s %s' % (i, table[i]['name'])) if i else '')].append(m)
        for g, ms in sorted(groups.items(), key=lambda kv: -len(kv[1])):
            lines.append('  - %s ← %s' % (g.strip(), '、'.join(ms)))
        lines.append('')
        lines.append('Steve 意见：')
        lines.append('')
    return '\n'.join(lines) + '\n', split


def parse_review(md):
    """-> {qid: (act, intent) or None for 都不对}. Reads the 'Steve 意见：' line after each '## n. qid'."""
    import re
    out, qid = {}, None
    for line in md.splitlines():
        m = re.match(r'^## \d+\. (\S+?)(（|$)', line)
        if m:
            qid = m.group(1)
            continue
        if qid and line.startswith('Steve 意见：'):
            v = line.split('：', 1)[1].strip().strip('`')
            if v:
                parts = v.split()
                if parts[0] in ACTS:
                    out[qid] = (parts[0], parts[1] if len(parts) > 1 and parts[0] not in ('CANCEL', 'NEED_CLARIFY') else None)
                else:
                    out[qid] = None           # 都不对 / free text: nobody is right
            qid = None
    return out


def score_steve(qs, ans_by_effort, gold):
    res = {}
    for key, ans in ans_by_effort.items():
        for m in next(iter(ans.values())):
            ok = n = 0
            for q in qs:
                if q['id'] not in gold or m not in ans[q['id']]:
                    continue
                a, i, bad = ans[q['id']][m]
                if bad == 'not_run':
                    continue
                n += 1
                ok += (not bad) and gold[q['id']] is not None and (a, i) == gold[q['id']]
            if n:
                res['%s@%s' % (m, key)] = dict(correct=ok, n=n, rate=ok / n, ci=wilson(ok, n))
    return res


def main():
    global TABLE
    ap = argparse.ArgumentParser()
    ap.add_argument('stage', choices=['build', 'run', 'score', 'steve'])
    ap.add_argument('--work', type=Path, default=DOCS / 'teacher-bakeoff')
    ap.add_argument('--n', type=int, default=300)
    ap.add_argument('--seed', default='teacher-bakeoff-20261112')
    ap.add_argument('--models', default=','.join(CONTESTANTS))
    ap.add_argument('--effort', default='low')
    ap.add_argument('--efforts', help='score: model=effort,... (default: --effort for all)')
    ap.add_argument('--subset', help='run/score only these question ids (json list)')
    ap.add_argument('--cap-openai', type=float, default=25.0)
    ap.add_argument('--cap-anthropic', type=float, default=25.0)
    ap.add_argument('--rubric', default=str(Path(__file__).parent / 'clarify_rubric.json'))
    ap.add_argument('--out', help='score: output json name')
    a = ap.parse_args()
    TABLE = table = build_intent_table()
    dp.RUBRIC = dp.load_rubric(a.rubric)
    a.work.mkdir(parents=True, exist_ok=True)
    qpath = a.work / 'questions.jsonl'
    if a.stage == 'build':
        v2 = DOCS / 'v2'
        qs = build(a.n, a.seed, [v2 / 'silver.jsonl', v2 / 'silver-adjudicated.jsonl', v2 / 'review-queue.jsonl'],
                   v2 / 'review-queue-after-claude.jsonl')
        write_jsonl(qpath, qs)
        print('BUILT', len(qs), dict(Counter(q['cell'] for q in qs)), 'threeway', sum(q['threeway'] for q in qs),
              'families', len({q['family'] for q in qs}))
        return
    qs = read_jsonl(qpath)
    if a.subset:
        keep = set(json.loads(Path(a.subset).read_text(encoding='utf-8')))
        qs = [q for q in qs if q['id'] in keep]
    models = a.models.split(',')
    if a.stage == 'run':
        runner = Runner(a.work, dict(openai=a.cap_openai, anthropic=a.cap_anthropic), dict(openai=6, anthropic=4))
        stop = threading.Event()

        def one(job):
            q, m = job
            if stop.is_set():
                return None
            try:
                return runner.call(q, table, m, a.effort)
            except CapReached as e:
                stop.set()
                print('CAP_REACHED', e, flush=True)
                return None
        jobs = [(q, m) for q in qs for m in models]
        with ThreadPoolExecutor(10) as ex:
            got = list(ex.map(one, jobs))
        bad = Counter((j[1], parse(r, j[0])[2]) for j, r in zip(jobs, got) if r and parse(r, j[0])[2])
        print('RUN done', len([g for g in got if g]), 'spent', dict(runner.spent), 'issues', dict(bad), flush=True)
        return
    if a.stage == 'steve':
        gold = parse_review((a.work / '给Steve复核.md').read_text(encoding='utf-8'))
        qs = [q for q in qs if q['id'] in gold]
        by = {}
        for eff in ('low', 'medium', 'high'):
            by[eff] = answers(a.work, qs, table, {m: eff for m in models})[0]
        res = score_steve(qs, by, gold)
        (a.work / 'steve-score.json').write_text(json.dumps(dict(answered=len(gold), scores=res), ensure_ascii=False, indent=2), encoding='utf-8')
        for k, v in sorted(res.items(), key=lambda kv: -kv[1]['rate']):
            print('STEVE %-28s %d/%d=%.3f' % (k, v['correct'], v['n'], v['rate']))
        return
    effort_of = dict(x.split('=') for x in a.efforts.split(',')) if a.efforts else {m: a.effort for m in models}
    ans, rec = answers(a.work, qs, table, effort_of)
    res = score(qs, ans, rec, effort_of)
    md, split = review_md(qs, ans, table, list(effort_of))
    out = dict(n=len(qs), efforts=effort_of, contestants=res, split_items=[q['id'] for q in split],
               no_majority=sum(majority(ans[q['id']])[0] is None for q in qs),
               hard_subset=[q['id'] for q in qs if majority(ans[q['id']])[0] is None
                            or (majority(ans[q['id']])[0] or ('',))[0] == 'NEED_CLARIFY'])
    name = a.out or 'score-%s.json' % a.effort
    (a.work / name).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding='utf-8')
    (a.work / name.replace('.json', '-split.md')).write_text(md, encoding='utf-8')
    for m, r in res.items():
        g = r['agreement'].get('all', {})
        print('SCORE %-18s %-6s agree %s/%s=%.3f invalid %d cost/item %.5f p50 %.0fms' % (
            m, r['effort'], g.get('agree'), g.get('n'), g.get('rate') or 0, r['invalid'], r['cost_per_item'] or 0, r['p50_latency_ms'] or 0))
    print('NO_MAJORITY', out['no_majority'], 'HARD', len(out['hard_subset']))


if __name__ == '__main__':
    main()
