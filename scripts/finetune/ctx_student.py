"""INTENT-DISTILL-20261111 task F: context-aware local decision model (student v1).

Pure pieces (no torch at import time, so the offline tests run anywhere):
  * data loading with fail-closed SHA256 / split / family-disjointness checks
  * fixed-format serialization of utterance + task state + candidate list
  * the direct-output policy (read-only target only, write-mass cap) and its metrics
  * Wilson confidence intervals

Torch pieces live in ``build_model`` / ``CtxStudent`` and are imported lazily.

Nothing here calls a product endpoint, writes prod data, or opens the sealed acceptance set.
"""
import hashlib
import json
import math
from collections import Counter, defaultdict
from pathlib import Path

ACTS = ('NEW_TASK', 'CHANGE_PARAM', 'PAGE', 'CANCEL', 'NEED_CLARIFY')
ACT_ID = {a: i for i, a in enumerate(ACTS)}
DIRECT_ACTS = ('NEW_TASK', 'CHANGE_PARAM', 'PAGE')      # acts that carry a target intent
NONE_KEY = '__NONE__'
MARK = '[unused1]'                                       # candidate slot marker (in BERT-zh vocab)
PREV = '★'                                               # candidate == previous task

DATA = Path.home() / 'Documents' / 'Cretas意图蒸馏-20261111' / 'v2'
MODELS = Path.home() / 'Documents' / 'Cretas意图蒸馏-20261111' / 'models'
OLD_STUDENT = Path.home() / 'Documents' / 'Cretas意图泛化-20261105' / 'models' / 'boundary-a' / 'final'

# Frozen inputs (v2/README.md). Any change => refuse to train / evaluate.
FROZEN = {
    'silver-train.jsonl': '832046677ade3dc7c3f24fe37bf15095956d1e657b29f70ae5024b86efe72d0e',
    'silver-validation.jsonl': '48fa304762d6a1e96e333c857fd9f00461195f4f33e8f350249476611d651729',
    'silver-calibration.jsonl': 'e2c6f0df21f490a7aeb4c7468702189126f0ed6da47a3ab2b36058cbfe1c72e1',
    'silver-adjudicated.jsonl': '7953f12c810b5e4435dbb8313d2a89edd33ab547028ad418aab9e44e39a1012c',
    'review-queue-after-claude.jsonl': 'a2ae4468b85bd2ffd9e8a3a10171a57c7895c8a5077874a58238395567a13d04',
}

# Adjudicated (2-of-3) rows are the hard/ambiguous cases by construction: half weight.
ADJUDICATED_WEIGHT = 0.5


# ---------------------------------------------------------------- io
def read_jsonl(path):
    return [json.loads(x) for x in Path(path).read_text(encoding='utf-8').splitlines() if x.strip()]


def sha256_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verify_frozen(root=DATA, frozen=FROZEN):
    for name, digest in frozen.items():
        got = sha256_file(Path(root) / name)
        if got != digest:
            raise ValueError('frozen input changed: %s %s != %s' % (name, got, digest))


SPLIT_NAMES = ('train', 'validation', 'calibration', 'adjudicated_validation', 'adjudicated_calibration')


def load_splits(root=DATA, extra=(), verify=True, mode='known'):
    """-> dict(train, validation, calibration, adjudicated_validation, adjudicated_calibration).

    mode='unseen': the frozen F-pre family split (validation intents never occur in train; a
      zero-shot generalization reference only).
    mode='known' (primary, production-like): every row is re-split by ``known_intent_split`` --
      paraphrase clusters inside each target never cross sets, every evaluated intent has train rows.

    ``extra``: additional two-teacher-agreed jsonl files (targeted top-up); they join the pool in
    mode='known' and only train in mode='unseen' (their family split is not the frozen one).
    Every row gets ``weight`` and ``adjudicated``. The 40 three-way disagreements never enter.
    """
    root = Path(root)
    if verify:
        verify_frozen(root)
    out = {s: [] for s in SPLIT_NAMES}
    for s in ('train', 'validation', 'calibration'):
        for r in read_jsonl(root / ('silver-%s.jsonl' % s)):
            out[s].append(dict(r, weight=1.0, adjudicated=False))
    for r in read_jsonl(root / 'silver-adjudicated.jsonl'):
        r = dict(r, weight=ADJUDICATED_WEIGHT, adjudicated=True)
        out['train' if r['split'] == 'train' else 'adjudicated_' + r['split']].append(r)
    extra_rows = [dict(r, weight=1.0, adjudicated=False) for path in extra for r in read_jsonl(path)]
    excluded = read_jsonl(root / 'review-queue-after-claude.jsonl')
    if mode == 'unseen':
        out['train'] += [r for r in extra_rows if r['family'] not in {x['family'] for n in SPLIT_NAMES[1:] for x in out[n]}]
        check_disjoint(out, excluded)
        return out
    pool = [r for rows in out.values() for r in rows] + extra_rows
    assign = known_intent_split(pool)
    out = {s: [] for s in SPLIT_NAMES}
    for r in pool:
        s = assign[r['id']]
        out[s if s == 'train' or not r['adjudicated'] else 'adjudicated_' + s].append(dict(r, split=s))
    check_disjoint(out, excluded, families=False)
    return out


# ---------------------------------------------------------------- known-intent split
SPLIT_SALT = 'student-v1-known-intent'
NEAR_JACCARD = .5          # char-bigram Jaccard at/above this = same paraphrase cluster


def target_key(r):
    """What the turn is about: the label intent, or (act, task acted on) when there is none."""
    return r['intent'] or '%s@%s' % (r['dialogue_act'], (r.get('state') or {}).get('prev_intent'))


def bigrams(text):
    t = norm(text)
    return {t[i:i + 2] for i in range(len(t) - 1)} or {t}


def paraphrase_clusters(rows):
    """Union-find over rows with identical normalized utterance or bigram Jaccard >= NEAR_JACCARD."""
    parent = list(range(len(rows)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    grams = [bigrams(r['utterance']) for r in rows]
    for i in range(len(rows)):
        for j in range(i + 1, len(rows)):
            a, b = grams[i], grams[j]
            if norm(rows[i]['utterance']) == norm(rows[j]['utterance']) or len(a & b) / len(a | b) >= NEAR_JACCARD:
                parent[find(i)] = find(j)
    groups = defaultdict(list)
    for i, r in enumerate(rows):
        groups[find(i)].append(r)
    return list(groups.values())


def _h(text):
    return int(hashlib.sha256((SPLIT_SALT + ':' + text).encode('utf-8')).hexdigest()[:12], 16)


def known_intent_split(rows, val=.15, cal=.15):
    """id -> train/validation/calibration.

    Within each target key, paraphrase clusters are ordered by a salted hash; the first cluster
    always goes to train (so no evaluated target is unseen), the rest go to validation/calibration
    by hash share. Keys with a single cluster stay in train. Deterministic and label-independent
    of model outputs.
    """
    by_key = defaultdict(list)
    for r in rows:
        by_key[target_key(r)].append(r)
    out = {}
    for key, items in by_key.items():
        clusters = sorted(paraphrase_clusters(items), key=lambda c: _h(key + '|' + min(x['id'] for x in c)))
        for k, c in enumerate(clusters):
            if k == 0:
                s = 'train'
            else:
                u = _h('share|' + key + '|' + min(x['id'] for x in c)) % 10000 / 10000
                s = 'validation' if u < val else 'calibration' if u < val + cal else 'train'
            for x in c:
                out[x['id']] = s
    return out


def check_disjoint(splits, excluded, families=True):
    bad = {r['id'] for r in excluded}
    seen = {}
    for name, rows in splits.items():
        for r in rows:
            if r['id'] in bad:
                raise ValueError('three-way disagreement in %s: %s' % (name, r['id']))
            if r['id'] in seen:
                raise ValueError('id in two splits: %s (%s, %s)' % (r['id'], seen[r['id']], name))
            seen[r['id']] = name
    if not families:
        check_known_split(splits)
        return
    fam = {n: {r['family'] for r in rows} for n, rows in splits.items()}
    held = fam['validation'] | fam['adjudicated_validation']
    cal = fam['calibration'] | fam['adjudicated_calibration']
    for a, b, label in ((fam['train'], held, 'train/validation'), (fam['train'], cal, 'train/calibration'), (held, cal, 'validation/calibration')):
        if a & b:
            raise ValueError('family crosses %s: %s' % (label, sorted(a & b)))
    tr = {norm(r['utterance']) + '|' + json.dumps(r['state'], ensure_ascii=False, sort_keys=True) for r in splits['train']}
    for n in ('validation', 'calibration', 'adjudicated_validation', 'adjudicated_calibration'):
        for r in splits[n]:
            if norm(r['utterance']) + '|' + json.dumps(r['state'], ensure_ascii=False, sort_keys=True) in tr:
                raise ValueError('identical sample in train and %s: %s' % (n, r['id']))


def check_known_split(splits):
    """No near-paraphrase of the same target crosses train and a held-out set; every held-out
    intent has train rows."""
    train_keys = defaultdict(list)
    for r in splits['train']:
        train_keys[target_key(r)].append(bigrams(r['utterance']))
    train_intents = {r['intent'] for r in splits['train'] if r['intent']}
    for n in SPLIT_NAMES[1:]:
        for r in splits[n]:
            if r['intent'] and r['intent'] not in train_intents:
                raise ValueError('held-out intent without train rows: %s (%s)' % (r['intent'], r['id']))
            g = bigrams(r['utterance'])
            for t in train_keys.get(target_key(r), ()):
                if len(g & t) / len(g | t) >= NEAR_JACCARD:
                    raise ValueError('paraphrase of the same target in train and %s: %s' % (n, r['id']))


def norm(text):
    return ''.join(c for c in text.lower() if c.isalnum())


# ---------------------------------------------------------------- serialization
def serialize_context(row, table):
    """Fixed-format task-state summary. Same field order every time; empty fields say 无."""
    st = row.get('state') or {}
    prev = st.get('prev_intent')
    prev_txt = '%s（%s）' % (table[prev]['name'], '只读' if table[prev]['read_only'] else '写') if prev in table else '无'
    params = '；'.join('%s=%s' % (p['name'], p['value']) for p in st.get('confirmed_params') or []) or '无'
    pending = '、'.join(st.get('pending_fields') or []) or '无'
    last = st.get('last_action') or '无'
    return '句：%s\n上轮目标：%s\n已确认：%s\n待答：%s\n上次动作：%s' % (row['utterance'], prev_txt, params, pending, last)


def serialize_candidates(row, table, desc_chars=0):
    prev = (row.get('state') or {}).get('prev_intent')
    parts = []
    for c in row['candidates']:
        t = table[c]
        d = (t['description'] or '')[:desc_chars] if desc_chars else ''
        parts.append(MARK + (PREV if c == prev else '') + t['name'] + ('：' + d if d else ''))
    return ''.join(parts)


def target_index(row):
    """Index into candidates + [NONE]."""
    if row['intent'] is None:
        return len(row['candidates'])
    return row['candidates'].index(row['intent'])


def soft_target(row):
    keys = list(row['candidates']) + [NONE_KEY]
    p = [max(float(row['teacher_probs'].get(k, 0.0)), 0.0) for k in keys]
    s = sum(p)
    return [x / s for x in p] if s > 0 else None


def use_soft(row):
    """Luna's distribution is a teacher only when Luna was not the outvoted teacher."""
    adj = row.get('adjudication')
    return not (adj and adj.get('sided_with') == 'terra')


# ---------------------------------------------------------------- math
def softmax(xs, t=1.0):
    m = max(x / t for x in xs)
    e = [math.exp(x / t - m) for x in xs]
    s = sum(e)
    return [x / s for x in e]


def log_softmax(xs):
    m = max(xs)
    s = math.log(sum(math.exp(x - m) for x in xs)) + m
    return [x - s for x in xs]


def ensemble(member_preds):
    """Geometric mean of member probabilities: mean of log-softmax, used as the ensemble's logits."""
    out = []
    for group in zip(*member_preds):
        if len({p['id'] for p in group}) != 1:
            raise ValueError('ensemble members are not aligned')
        merged = dict(id=group[0]['id'])
        for k in ('act_logits', 'target_logits'):
            ls = [log_softmax(p[k]) for p in group]
            merged[k] = [sum(v) / len(v) for v in zip(*ls)]
        out.append(merged)
    return out


def wilson(k, n, z=1.959964):
    if n == 0:
        return (None, None)
    p = k / n
    d = 1 + z * z / n
    c = p + z * z / (2 * n)
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return ((c - h) / d, (c + h) / d)


# ---------------------------------------------------------------- policy
def decide(pred, row, table, policy):
    """pred: dict(act_logits[5], target_logits[len(cands)+1]).

    Direct only if: act in DIRECT_ACTS with calibrated p >= act_min; target is a candidate that is a
    READ intent with calibrated p >= target_min; total calibrated mass on WRITE candidates <= write_max.
    Everything else escalates. CANCEL / NEED_CLARIFY always escalate (no read-only target).
    """
    pa = softmax(pred['act_logits'], policy['t_act'])
    pt = softmax(pred['target_logits'], policy['t_target'])
    a = max(range(len(ACTS)), key=pa.__getitem__)
    j = max(range(len(pt)), key=pt.__getitem__)
    cands = row['candidates']
    intent = cands[j] if j < len(cands) else None
    write_mass = sum(p for c, p in zip(cands, pt) if table[c]['read_only'] is not True)
    direct = (ACTS[a] in DIRECT_ACTS and pa[a] >= policy['act_min'] and intent is not None
              and table[intent]['read_only'] is True and pt[j] >= policy['target_min']
              and write_mass <= policy['write_max'])
    return dict(direct=direct, act=ACTS[a], intent=intent, p_act=pa[a], p_target=pt[j], write_mass=write_mass)


def is_correct(d, row):
    return d['act'] == row['dialogue_act'] and d['intent'] == row['intent']


def score_policy(preds, rows, table, policy):
    """Coverage is over read-only gold samples; accuracy is over every direct output (any gold)."""
    ro_n = ro_direct = direct = correct = act_ok = tgt_ok = 0
    write_gold_direct = []          # direct outputs on samples whose gold task is a WRITE task
    nonread_gold_direct = []        # direct outputs on samples whose gold read_only is not True
    predicted_write = 0             # direct outputs whose chosen intent is WRITE (must be structurally 0)
    for p, r in zip(preds, rows):
        d = decide(p, r, table, policy)
        if r['read_only'] is True:
            ro_n += 1
            ro_direct += d['direct']
        if not d['direct']:
            continue
        direct += 1
        correct += is_correct(d, r)
        act_ok += d['act'] == r['dialogue_act']
        tgt_ok += d['intent'] == r['intent']
        predicted_write += table[d['intent']]['read_only'] is not True
        if r['read_only'] is False:
            write_gold_direct.append(r['id'])
        if r['read_only'] is not True:
            nonread_gold_direct.append(r['id'])
    return dict(read_only_n=ro_n, read_only_direct=ro_direct, coverage=ro_direct / ro_n if ro_n else 0.0,
                coverage_ci=wilson(ro_direct, ro_n), direct=direct, correct=correct,
                accuracy=correct / direct if direct else None, accuracy_ci=wilson(correct, direct),
                target_accuracy=tgt_ok / direct if direct else None, target_ci=wilson(tgt_ok, direct),
                act_accuracy=act_ok / direct if direct else None, act_ci=wilson(act_ok, direct),
                predicted_write_direct=predicted_write, write_gold_direct=len(write_gold_direct),
                write_gold_direct_ids=write_gold_direct, nonread_gold_direct=len(nonread_gold_direct),
                nonread_gold_direct_ids=nonread_gold_direct)


POLICY_GRID = dict(act_min=(.5, .7, .8, .9, .95, .97, .98, .99, .995),
                   target_min=(.5, .7, .8, .9, .95, .97, .98, .99, .995),
                   write_max=(.005, .01, .02, .05, .1, .2, .3))


def select_policy(preds, rows, table, t_act, t_target, min_accuracy=.98, grid=POLICY_GRID, hard='write_gold_direct'):
    """Maximize coverage subject to accuracy >= min_accuracy and ``hard`` == 0 (calibration only).

    hard='write_gold_direct' (default): zero direct outputs on samples whose gold task is a WRITE
      task. A direct output is always a READ intent, so a no-target clarify row answered directly is
      a wrong read answer, not a write; it counts as an error against min_accuracy.
    hard='nonread_gold_direct' (strict reading): also zero on no-target gold rows.
    Ties -> higher accuracy, then stricter thresholds."""
    results = []
    for am in grid['act_min']:
        for tm in grid['target_min']:
            for wm in grid['write_max']:
                pol = dict(t_act=t_act, t_target=t_target, act_min=am, target_min=tm, write_max=wm)
                s = score_policy(preds, rows, table, pol)
                results.append(dict(policy=pol, **{k: v for k, v in s.items() if not k.endswith('_ids')}))
    ok = [r for r in results if r['direct'] and r['accuracy'] >= min_accuracy and r[hard] == 0 and r['predicted_write_direct'] == 0]
    if not ok:
        return None, results
    best = max(ok, key=lambda r: (r['coverage'], r['accuracy'], r['policy']['act_min'], r['policy']['target_min'], -r['policy']['write_max']))
    return best['policy'], results


def fit_temperature(logit_lists, gold, grid=None):
    grid = grid or [round(.5 + .05 * i, 2) for i in range(71)]     # 0.5 .. 4.0

    def nll(t):
        return -sum(math.log(max(softmax(l, t)[g], 1e-12)) for l, g in zip(logit_lists, gold)) / len(gold)
    best = min(grid, key=nll)
    return best, nll(1.0), nll(best)


def ece(logit_lists, gold, t, bins=10):
    buckets = defaultdict(lambda: [0, 0, 0.0])
    for l, g in zip(logit_lists, gold):
        p = softmax(l, t)
        j = max(range(len(p)), key=p.__getitem__)
        b = min(int(p[j] * bins), bins - 1)
        buckets[b][0] += 1
        buckets[b][1] += j == g
        buckets[b][2] += p[j]
    n = len(gold)
    return sum(abs(c / k - s / k) * k / n for k, c, s in buckets.values())


def breakdown(preds, rows, table, policy, key):
    groups = defaultdict(list)
    for p, r in zip(preds, rows):
        groups[key(r)].append((p, r))
    out = {}
    for g, items in sorted(groups.items(), key=lambda kv: str(kv[0])):
        ps, rs = zip(*items)
        s = score_policy(list(ps), list(rs), table, policy)
        raw = sum(raw_correct(p, r) for p, r in items)
        out[str(g)] = dict(n=len(rs), raw_accuracy=raw / len(rs), raw_ci=wilson(raw, len(rs)),
                           **{k: v for k, v in s.items() if not k.endswith('_ids')})
    return out


def raw_correct(p, r):
    a = max(range(len(ACTS)), key=lambda i: p['act_logits'][i])
    j = max(range(len(p['target_logits'])), key=lambda i: p['target_logits'][i])
    intent = r['candidates'][j] if j < len(r['candidates']) else None
    return ACTS[a] == r['dialogue_act'] and intent == r['intent']


def read_bucket(r):
    return {True: 'read', False: 'write', None: 'no_target'}[r['read_only']]


def summarize_raw(preds, rows):
    n = len(rows)
    act = sum(max(range(5), key=lambda i: p['act_logits'][i]) == ACT_ID[r['dialogue_act']] for p, r in zip(preds, rows))
    tgt = sum(max(range(len(p['target_logits'])), key=lambda i: p['target_logits'][i]) == target_index(r) for p, r in zip(preds, rows))
    joint = sum(raw_correct(p, r) for p, r in zip(preds, rows))
    return dict(n=n, act_accuracy=act / n, target_accuracy=tgt / n, joint_accuracy=joint / n,
                act_ci=wilson(act, n), target_ci=wilson(tgt, n), joint_ci=wilson(joint, n))


def counts(rows):
    return dict(n=len(rows), acts=dict(Counter(r['dialogue_act'] for r in rows)),
                read=dict(Counter(read_bucket(r) for r in rows)), families=len({r['family'] for r in rows}))
