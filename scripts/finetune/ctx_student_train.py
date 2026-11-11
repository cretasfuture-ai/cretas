#!/usr/bin/env python3
"""Train / compare / calibrate / evaluate the context-aware local decision model (task F).

Subcommands (all outputs outside the repo, under --out, default Documents/.../models/student-v1):
  fit       --variant V [--seed S]   train one variant on train, write logits for every split
  compare                            fit the backbone/head variants, rank on CALIBRATION only
  calibrate --variant V              temperatures + direct-output thresholds on calibration only
  evaluate  --variant V              one locked read of validation with the frozen policy
  old                                old single-sentence 266 student on the same validation rows
  latency   --variant V              CPU single-request latency

Weights never enter the repo. Validation is never used for fitting, model choice or thresholds;
``evaluate`` refuses to run before ``calibrate`` wrote a policy and records a hash-stamped marker.
"""
import argparse
import json
import math
import os
import random
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import ctx_student as cs

os.environ.setdefault('HF_HUB_OFFLINE', '1')
os.environ.setdefault('TRANSFORMERS_OFFLINE', '1')
os.environ.setdefault('TOKENIZERS_PARALLELISM', 'false')

VARIANTS = {
    # name: backbone, head, candidate description chars
    'old-266head': dict(backbone=str(cs.OLD_STUDENT), head='266', desc=0),
    'old-list': dict(backbone=str(cs.OLD_STUDENT), head='list', desc=0),
    'old-list-desc': dict(backbone=str(cs.OLD_STUDENT), head='list', desc=20),
    'bge-list': dict(backbone=str(cs.MODELS / 'backbones' / 'bge-base-zh-v1.5'), head='list', desc=0),
}
HP = dict(epochs=6, batch_size=16, lr=3e-5, head_lr=1e-3, warmup=.1, weight_decay=.01, max_length=384,
          kl_alpha=.5, target_weight=1.0, shuffle_candidates=True)
SPLITS = ('train', 'calibration', 'validation', 'adjudicated_calibration', 'adjudicated_validation')


def save_json(path, obj):
    body = json.dumps(obj, ensure_ascii=False, indent=2)
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(body, encoding='utf-8')


def write_jsonl(path, rows):
    body = ''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in rows)
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(body)


# ---------------------------------------------------------------- model
def build(variant, table):
    import torch
    from torch import nn
    from transformers import AutoModel, AutoTokenizer
    cfg = VARIANTS[variant]
    tok = AutoTokenizer.from_pretrained(cfg['backbone'], local_files_only=True)
    tok.add_special_tokens({'additional_special_tokens': [cs.MARK]})
    if tok.convert_tokens_to_ids(cs.MARK) in (tok.unk_token_id, None) or len(tok) != tok.vocab_size:
        raise ValueError('marker token must already exist in the backbone vocabulary (no embedding resize)')

    class CtxStudent(nn.Module):
        def __init__(self):
            super().__init__()
            self.enc = AutoModel.from_pretrained(cfg['backbone'], local_files_only=True, add_pooling_layer=False)
            h = self.enc.config.hidden_size
            self.drop = nn.Dropout(.1)
            self.act = nn.Linear(h, len(cs.ACTS))
            self.none = nn.Linear(h, 1)
            self.head = cfg['head']
            if self.head == 'list':
                self.cand = nn.Sequential(nn.Linear(h, h), nn.Tanh(), nn.Linear(h, 1))
            else:
                from safetensors.torch import load_file
                sd = load_file(str(Path(cfg['backbone']) / 'model.safetensors'))
                mapping = json.loads((Path(cfg['backbone']) / 'label_mapping.json').read_text(encoding='utf-8'))['label_to_id']
                self.label_to_id = mapping
                self.cls266 = nn.Linear(h, len(mapping))
                self.cls266.weight.data.copy_(sd['classifier.weight'])
                self.cls266.bias.data.copy_(sd['classifier.bias'])

        def forward(self, input_ids, attention_mask, token_type_ids, marker_pos, cand_ids, cand_mask):
            hs = self.enc(input_ids=input_ids, attention_mask=attention_mask, token_type_ids=token_type_ids).last_hidden_state
            cls = self.drop(hs[:, 0])
            act = self.act(cls)
            if self.head == 'list':
                idx = marker_pos.clamp(min=0).unsqueeze(-1).expand(-1, -1, hs.size(-1))
                s = self.cand(self.drop(hs.gather(1, idx))).squeeze(-1)
            else:
                s = self.cls266(cls).gather(1, cand_ids.clamp(min=0))
            s = s.float().masked_fill(~cand_mask, -1e4)
            return act.float(), torch.cat([s, self.none(cls).float()], 1)    # NONE at column K

    return tok, CtxStudent(), cfg


def encode(tok, rows, table, cfg, perm=None, label_to_id=None):
    import torch
    ctx = [cs.serialize_context(r, table) for r in rows]
    cand_rows = []
    for i, r in enumerate(rows):
        order = perm[i] if perm else list(range(len(r['candidates'])))
        cand_rows.append(dict(r, candidates=[r['candidates'][j] for j in order]))
    cands = [cs.serialize_candidates(r, table, cfg['desc']) for r in cand_rows]
    enc = tok(ctx, cands, truncation='only_first', max_length=HP['max_length'], padding=True, return_tensors='pt')
    mid = tok.convert_tokens_to_ids(cs.MARK)
    k = max(len(r['candidates']) for r in rows)
    pos = torch.full((len(rows), k), -1, dtype=torch.long)
    cid = torch.full((len(rows), k), -1, dtype=torch.long)
    for i, r in enumerate(cand_rows):
        p = (enc['input_ids'][i] == mid).nonzero().flatten()
        if len(p) != len(r['candidates']):
            raise ValueError('candidate markers truncated for %s (%d/%d)' % (r['id'], len(p), len(r['candidates'])))
        pos[i, :len(p)] = p
        if label_to_id:
            cid[i, :len(p)] = torch.tensor([label_to_id[c] for c in r['candidates']])
    if 'token_type_ids' not in enc:
        enc['token_type_ids'] = torch.zeros_like(enc['input_ids'])
    return enc, pos, cid, pos >= 0, cand_rows, k


def batch_targets(cand_rows, k):
    """Gold target index (NONE -> k), soft targets padded to k+1, soft-on flags."""
    gold, soft, use = [], [], []
    for r in cand_rows:
        n = len(r['candidates'])
        t = cs.target_index(r)
        gold.append(k if t == n else t)
        st = cs.soft_target(r)
        vec = [0.0] * (k + 1)
        if st:
            for j in range(n):
                vec[j] = st[j]
            vec[k] = st[n]
        soft.append(vec)
        use.append(bool(st) and cs.use_soft(r))
    return gold, soft, use


def fit(variant, table, splits, out, seed=20261111, epochs=None):
    import torch
    import torch.nn.functional as F
    from transformers import get_linear_schedule_with_warmup
    random.seed(seed)
    torch.manual_seed(seed)
    tok, model, cfg = build(variant, table)
    l2i = getattr(model, 'label_to_id', None)
    dev = 'cuda'
    model.to(dev)
    train = splits['train']
    epochs = epochs or HP['epochs']
    head_params = [p for n, p in model.named_parameters() if not n.startswith('enc.')]
    enc_params = [p for n, p in model.named_parameters() if n.startswith('enc.')]
    opt = torch.optim.AdamW([dict(params=enc_params, lr=HP['lr']), dict(params=head_params, lr=HP['head_lr'])], weight_decay=HP['weight_decay'])
    steps = epochs * math.ceil(len(train) / HP['batch_size'])
    sch = get_linear_schedule_with_warmup(opt, int(HP['warmup'] * steps), steps)
    scaler = torch.amp.GradScaler('cuda')
    history = []
    t0 = time.perf_counter()
    for ep in range(epochs):
        model.train()
        rng = random.Random(seed * 100 + ep)
        order = list(range(len(train)))
        rng.shuffle(order)
        tot = 0.0
        for b in range(0, len(order), HP['batch_size']):
            rows = [train[i] for i in order[b:b + HP['batch_size']]]
            perm = None
            if HP['shuffle_candidates']:
                perm = []
                for r in rows:
                    p = list(range(len(r['candidates'])))
                    rng.shuffle(p)
                    perm.append(p)
            enc, pos, cid, cm, cand_rows, k = encode(tok, rows, table, cfg, perm, l2i)
            gold, soft, use = batch_targets(cand_rows, k)
            w = torch.tensor([r['weight'] for r in rows], device=dev)
            with torch.autocast('cuda', dtype=torch.float16):
                act, tgt = model(enc['input_ids'].to(dev), enc['attention_mask'].to(dev), enc['token_type_ids'].to(dev),
                                 pos.to(dev), cid.to(dev), cm.to(dev))
            act_y = torch.tensor([cs.ACT_ID[r['dialogue_act']] for r in rows], device=dev)
            l_act = F.cross_entropy(act, act_y, reduction='none')
            logp = F.log_softmax(tgt, -1)
            l_ce = F.nll_loss(logp, torch.tensor(gold, device=dev), reduction='none')
            q = torch.tensor(soft, device=dev)
            l_kl = (q * (torch.log(q.clamp(min=1e-8)) - logp)).sum(-1)
            u = torch.tensor(use, device=dev, dtype=torch.float)
            a = HP['kl_alpha'] * u
            l_t = a * l_kl + (1 - a) * l_ce
            loss = (w * (l_act + HP['target_weight'] * l_t)).sum() / w.sum()
            opt.zero_grad()
            scaler.scale(loss).backward()
            scaler.unscale_(opt)
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            scaler.step(opt)
            scaler.update()
            sch.step()
            tot += loss.item() * len(rows)
        cal = cs.summarize_raw(predict(model, tok, cfg, splits['calibration'], table), splits['calibration'])
        history.append(dict(epoch=ep + 1, train_loss=tot / len(train), calibration_raw=cal))
        print('EPOCH', variant, ep + 1, round(tot / len(train), 4), json.dumps({k: round(v, 4) for k, v in cal.items() if isinstance(v, float)}), flush=True)
    secs = time.perf_counter() - t0
    vdir = Path(out) / variant
    vdir.mkdir(parents=True, exist_ok=True)
    for s in SPLITS:
        if s == 'validation' or s == 'adjudicated_validation':
            continue        # validation logits are produced only by the locked `evaluate`
        write_jsonl(vdir / ('pred-%s.jsonl' % s), predict(model, tok, cfg, splits[s], table))
    torch.save({k: v.half() for k, v in model.state_dict().items()}, vdir / 'weights.pt')
    tok.save_pretrained(vdir / 'tokenizer')
    receipt = dict(variant=variant, config=cfg, hp=HP, seed=seed, epochs=epochs, train_rows=len(train),
                   train_seconds=secs, history=history, weights_sha256=cs.sha256_file(vdir / 'weights.pt'),
                   data=cs.FROZEN, adjudicated_weight=cs.ADJUDICATED_WEIGHT)
    save_json(vdir / 'receipt.json', receipt)
    del model
    torch.cuda.empty_cache()
    return receipt


def predict(model, tok, cfg, rows, table, bs=32, device=None):
    import torch
    device = device or next(model.parameters()).device
    model.eval()
    out = []
    l2i = getattr(model, 'label_to_id', None)
    with torch.inference_mode():
        for b in range(0, len(rows), bs):
            chunk = rows[b:b + bs]
            enc, pos, cid, cm, _, k = encode(tok, chunk, table, cfg, None, l2i)
            with torch.autocast('cuda', dtype=torch.float16, enabled=str(device).startswith('cuda')):
                act, tgt = model(enc['input_ids'].to(device), enc['attention_mask'].to(device), enc['token_type_ids'].to(device),
                                 pos.to(device), cid.to(device), cm.to(device))
            for i, r in enumerate(chunk):
                n = len(r['candidates'])
                t = tgt[i].tolist()
                out.append(dict(id=r['id'], act_logits=act[i].tolist(), target_logits=t[:n] + [t[k]]))
    return out


def load_trained(variant, table, out, device='cuda'):
    import torch
    tok, model, cfg = build(variant, table)
    sd = torch.load(Path(out) / variant / 'weights.pt', map_location='cpu')
    model.load_state_dict({k: v.float() for k, v in sd.items()})
    model.to(device)
    return tok, model, cfg


def preds_for(out, variant, split, rows):
    p = {x['id']: x for x in cs.read_jsonl(Path(out) / variant / ('pred-%s.jsonl' % split))}
    return [p[r['id']] for r in rows]


def members(variant):
    return variant.split('+')


def weights_hash(out, variant):
    """Single model: sha256 of weights.pt. Ensemble 'a+b': sha256 over the members' hashes."""
    hs = [cs.sha256_file(Path(out) / m / 'weights.pt') for m in members(variant)]
    return hs[0] if len(hs) == 1 else cs.hashlib.sha256(','.join(hs).encode('utf-8')).hexdigest()


def materialize(out, variant, splits):
    """Write an ensemble's pre-validation predictions from its members' files."""
    if len(members(variant)) == 1:
        return
    for split in ('train', 'calibration', 'adjudicated_calibration'):
        rows = splits[split]
        write_jsonl(Path(out) / variant / ('pred-%s.jsonl' % split), cs.ensemble([preds_for(out, m, split, rows) for m in members(variant)]))
    save_json(Path(out) / variant / 'members.json', {m: cs.sha256_file(Path(out) / m / 'weights.pt') for m in members(variant)})


def predict_variant(variant, table, out, rows):
    import torch
    got = []
    for m in members(variant):
        tok, model, cfg = load_trained(m, table, out)
        got.append(predict(model, tok, cfg, rows, table))
        del model
        torch.cuda.empty_cache()
    return got[0] if len(got) == 1 else cs.ensemble(got)


# ---------------------------------------------------------------- subcommands
def calib_rows(splits):
    """Calibration = agreed calibration + adjudicated calibration (harder rows make the gate stricter)."""
    return splits['calibration'] + splits['adjudicated_calibration']


def calibrate(variant, table, splits, out):
    materialize(out, variant, splits)
    rows = calib_rows(splits)
    preds = preds_for(out, variant, 'calibration', splits['calibration']) + preds_for(out, variant, 'adjudicated_calibration', splits['adjudicated_calibration'])
    t_act, nll_a1, nll_a = cs.fit_temperature([p['act_logits'] for p in preds], [cs.ACT_ID[r['dialogue_act']] for r in rows])
    t_tgt, nll_t1, nll_t = cs.fit_temperature([p['target_logits'] for p in preds], [cs.target_index(r) for r in rows])
    policy, grid = cs.select_policy(preds, rows, table, t_act, t_tgt)
    strict, _ = cs.select_policy(preds, rows, table, t_act, t_tgt, hard='nonread_gold_direct')
    frontier = {}
    for g in grid:
        if g['write_gold_direct'] == 0 and g['accuracy'] is not None:
            key = round(g['accuracy'], 3)
            if key not in frontier or g['coverage'] > frontier[key]['coverage']:
                frontier[key] = g
    res = dict(variant=variant, calibration_rows=len(rows), t_act=t_act, t_target=t_tgt,
               act_nll=dict(t1=nll_a1, fitted=nll_a), target_nll=dict(t1=nll_t1, fitted=nll_t),
               act_ece=dict(t1=cs.ece([p['act_logits'] for p in preds], [cs.ACT_ID[r['dialogue_act']] for r in rows], 1.0),
                            fitted=cs.ece([p['act_logits'] for p in preds], [cs.ACT_ID[r['dialogue_act']] for r in rows], t_act)),
               target_ece=dict(t1=cs.ece([p['target_logits'] for p in preds], [cs.target_index(r) for r in rows], 1.0),
                               fitted=cs.ece([p['target_logits'] for p in preds], [cs.target_index(r) for r in rows], t_tgt)),
               policy=policy, selection_rule='max coverage s.t. accuracy>=0.98, 0 direct on write gold, 0 predicted write (calibration only)',
               strict_policy=strict, strict_rule='same, plus 0 direct on no-target (clarify/cancel without a task) gold',
               strict_calibration_score=cs.score_policy(preds, rows, table, strict) if strict else None,
               calibration_score=cs.score_policy(preds, rows, table, policy) if policy else None,
               frontier=sorted(frontier.values(), key=lambda g: -g['accuracy']),
               weights_sha256=weights_hash(out, variant),
               code_sha256=cs.sha256_file(Path(cs.__file__)))
    save_json(Path(out) / variant / 'policy.json', res)
    print('CALIBRATED', json.dumps({k: res[k] for k in ('t_act', 't_target', 'policy')}, ensure_ascii=False))
    print('CAL_SCORE', json.dumps({k: v for k, v in (res['calibration_score'] or {}).items() if not k.endswith('_ids')}, ensure_ascii=False))
    return res


def evaluate(variant, table, splits, out):
    vdir = Path(out) / variant
    pol = json.loads((vdir / 'policy.json').read_text(encoding='utf-8'))
    if pol['policy'] is None:
        raise SystemExit('no feasible policy on calibration; refusing to evaluate')
    if weights_hash(out, variant) != pol['weights_sha256']:
        raise SystemExit('weights changed after calibration')
    if cs.sha256_file(Path(cs.__file__)) != pol['code_sha256']:
        raise SystemExit('decision code changed after calibration')
    marker = vdir / 'validation-opened.json'
    held = sorted(r['id'] for n in ('validation', 'adjudicated_validation') for r in splits[n])
    stamp = dict(weights=pol['weights_sha256'], policy=cs.sha256_file(vdir / 'policy.json'),
                 validation_ids=cs.hashlib.sha256(','.join(held).encode('utf-8')).hexdigest())
    if marker.exists() and json.loads(marker.read_text(encoding='utf-8')) != stamp:
        raise SystemExit('validation already opened under a different model/policy; no re-selection')
    save_json(marker, stamp)
    policy = pol['policy']
    res = dict(variant=variant, policy=policy, strict_policy=pol.get('strict_policy'))
    for name in ('validation', 'adjudicated_validation'):
        write_jsonl(vdir / ('pred-%s.jsonl' % name), predict_variant(variant, table, out, splits[name]))
    for name, rows in (('validation', splits['validation']), ('adjudicated_validation', splits['adjudicated_validation']),
                       ('validation_all', splits['validation'] + splits['adjudicated_validation'])):
        preds = preds_for(out, variant, 'validation', splits['validation']) if name == 'validation' else (
            preds_for(out, variant, 'adjudicated_validation', rows) if name == 'adjudicated_validation' else
            preds_for(out, variant, 'validation', splits['validation']) + preds_for(out, variant, 'adjudicated_validation', splits['adjudicated_validation']))
        s = cs.score_policy(preds, rows, table, policy)
        strict = cs.score_policy(preds, rows, table, pol['strict_policy']) if pol.get('strict_policy') else None
        res[name] = dict(counts=cs.counts(rows), raw=cs.summarize_raw(preds, rows), direct=s, direct_strict=strict,
                         by_act=cs.breakdown(preds, rows, table, policy, lambda r: r['dialogue_act']),
                         by_read=cs.breakdown(preds, rows, table, policy, cs.read_bucket),
                         by_family=cs.breakdown(preds, rows, table, policy, lambda r: r['family']),
                         by_act_read=cs.breakdown(preds, rows, table, policy, lambda r: r['dialogue_act'] + '|' + cs.read_bucket(r)),
                         direct_errors=[dict(id=r['id'], utterance=r['utterance'], gold_act=r['dialogue_act'], gold=r['intent'], **cs.decide(p, r, table, policy))
                                        for p, r in zip(preds, rows) if cs.decide(p, r, table, policy)['direct'] and not cs.is_correct(cs.decide(p, r, table, policy), r)])
    agreed_train = [r for r in splits['train'] if not r['adjudicated']]
    tp = preds_for(out, variant, 'train', agreed_train)
    res['train_agreed'] = dict(raw=cs.summarize_raw(tp, agreed_train), direct=cs.score_policy(tp, agreed_train, table, policy))
    cal_preds = preds_for(out, variant, 'calibration', splits['calibration'])
    res['calibration_agreed'] = dict(raw=cs.summarize_raw(cal_preds, splits['calibration']))
    res['generalization'] = dict(
        train_minus_validation_joint=res['train_agreed']['raw']['joint_accuracy'] - res['validation']['raw']['joint_accuracy'],
        train_minus_validation_target=res['train_agreed']['raw']['target_accuracy'] - res['validation']['raw']['target_accuracy'],
        train_minus_validation_act=res['train_agreed']['raw']['act_accuracy'] - res['validation']['raw']['act_accuracy'],
        worst_families_validation=sorted(({'family': k, **v} for k, v in res['validation_all']['by_family'].items()), key=lambda x: x['raw_accuracy'])[:5])
    save_json(vdir / 'evaluation.json', res)
    v = res['validation']['direct']
    print('VALIDATION', json.dumps({k: v[k] for k in v if not k.endswith('_ids')}, ensure_ascii=False))
    return res


def old_student(table, splits, out):
    """Old 266 single-sentence classifier, its own input (utterance only) and its own local gate."""
    import numpy as np
    from factory_accuracy_eval import needs_clarification
    from factory_accuracy_train import predict as old_predict
    old_policy = json.loads((cs.OLD_STUDENT.parents[2] / 'routing-policy.json').read_text(encoding='utf-8'))
    res = {}
    for name in ('validation', 'adjudicated_validation'):
        rows = splits[name]
        pred, mapping = old_predict(cs.OLD_STUDENT, [dict(record_id=r['id'], text=r['utterance'], label=r['intent'] or '__CLARIFY__') for r in rows])
        rev = {v: k for k, v in mapping.items()}
        ro_n = ro_local = local = local_ok = write_local = nonread_local = 0
        raw_ok = raw_ok_in_cands = 0
        for p, r in zip(pred, rows):
            gold = r['intent'] or '__CLARIFY__'
            raw_ok += p['prediction'] == gold
            lg = np.asarray(p['logits'])
            ids = [mapping[c] for c in r['candidates']]
            raw_ok_in_cands += int(r['intent'] is not None and r['candidates'][int(np.argmax(lg[ids]))] == r['intent'])
            pr = np.exp((lg - lg.max()) / old_policy['temperature'])
            pr /= pr.sum()
            idx = np.argsort(pr)[::-1]
            is_local = bool(not needs_clarification(r['utterance'])) and pr[idx[0]] >= old_policy['confidence'] and pr[idx[0]] - pr[idx[1]] >= old_policy['margin']
            label = rev[int(idx[0])]
            if r['read_only'] is True:
                ro_n += 1
                ro_local += int(is_local)
            if is_local:
                local += 1
                local_ok += label == gold
                write_local += int(label in table and table[label]['read_only'] is not True)
                nonread_local += int(r['read_only'] is not True)
        res[name] = dict(n=len(rows), raw266_accuracy=raw_ok / len(rows), raw_restricted_to_candidates_correct=raw_ok_in_cands,
                         read_only_n=ro_n, read_only_local=ro_local, coverage=ro_local / ro_n, coverage_ci=cs.wilson(ro_local, ro_n),
                         local=local, local_target_correct=local_ok, local_target_accuracy=local_ok / local if local else None,
                         local_target_ci=cs.wilson(local_ok, local), local_predicted_write=write_local, local_on_nonread_gold=nonread_local,
                         policy={k: old_policy[k] for k in ('temperature', 'confidence', 'margin')},
                         model_sha256=cs.sha256_file(cs.OLD_STUDENT / 'model.safetensors'))
        print('OLD', name, json.dumps(res[name], ensure_ascii=False))
    save_json(Path(out) / 'old-student-validation.json', res)
    return res


def latency(variant, table, splits, out, n=60):
    import statistics
    import torch
    torch.set_num_threads(4)
    tok, model, cfg = load_trained(variant, table, out, device='cpu')
    rows = splits['calibration'][:n]
    ts = []
    for r in rows:
        t = time.perf_counter()
        predict(model, tok, cfg, [r], table, bs=1, device='cpu')
        ts.append(time.perf_counter() - t)
    ts = ts[5:]
    res = dict(variant=variant, device='cpu-4-threads', n=len(ts), p50_ms=statistics.median(ts) * 1000,
               p95_ms=sorted(ts)[int(.95 * len(ts)) - 1] * 1000)
    save_json(Path(out) / variant / 'latency.json', res)
    print('LATENCY', json.dumps(res))


def compare(table, splits, out, variants):
    rows = calib_rows(splits)
    ranking = []
    for v in variants:
        if not (Path(out) / v / 'receipt.json').exists():
            fit(v, table, splits, out)
        preds = preds_for(out, v, 'calibration', splits['calibration']) + preds_for(out, v, 'adjudicated_calibration', splits['adjudicated_calibration'])
        t_act, _, nll_a = cs.fit_temperature([p['act_logits'] for p in preds], [cs.ACT_ID[r['dialogue_act']] for r in rows])
        t_tgt, _, nll_t = cs.fit_temperature([p['target_logits'] for p in preds], [cs.target_index(r) for r in rows])
        pol, _ = cs.select_policy(preds, rows, table, t_act, t_tgt)
        sc = cs.score_policy(preds, rows, table, pol) if pol else None
        ranking.append(dict(variant=v, raw=cs.summarize_raw(preds, rows), nll_act=nll_a, nll_target=nll_t,
                            coverage_at_98=sc['coverage'] if sc else 0.0, direct_accuracy=sc['accuracy'] if sc else None, policy=pol))
        print('COMPARE', v, json.dumps(ranking[-1], ensure_ascii=False), flush=True)
    ranking.sort(key=lambda x: (-x['coverage_at_98'], -x['raw']['joint_accuracy']))
    save_json(Path(out) / 'compare.json', dict(selected_on='calibration (agreed + adjudicated) only', ranking=ranking))
    return ranking


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', choices=['fit', 'compare', 'calibrate', 'evaluate', 'old', 'latency'])
    ap.add_argument('--variant', default='old-list')
    ap.add_argument('--variants', default=','.join(VARIANTS))
    ap.add_argument('--seed', type=int, default=20261111)
    ap.add_argument('--out', type=Path, default=cs.MODELS / 'student-v1')
    ap.add_argument('--extra', action='append', default=[], help='two-teacher-agreed top-up jsonl (pooled in mode=known)')
    ap.add_argument('--mode', choices=['known', 'unseen'], default='known')
    a = ap.parse_args()
    import distill_common as dc
    table = dc.build_intent_table()
    splits = cs.load_splits(extra=a.extra, mode=a.mode)
    a.out = a.out / a.mode
    save_json(a.out / 'data-counts.json', {k: cs.counts(v) for k, v in splits.items()})
    write_jsonl(a.out / 'split-manifest.jsonl', [dict(id=r['id'], split=k, target=cs.target_key(r)) for k, v in splits.items() for r in v])
    save_json(a.out / 'inputs.json', dict(mode=a.mode, frozen=cs.FROZEN, extra={p: cs.sha256_file(p) for p in a.extra},
                                          split_salt=cs.SPLIT_SALT, near_jaccard=cs.NEAR_JACCARD))
    if a.cmd == 'fit':
        fit(a.variant, table, splits, a.out, a.seed)
    elif a.cmd == 'compare':
        compare(table, splits, a.out, a.variants.split(','))
    elif a.cmd == 'calibrate':
        calibrate(a.variant, table, splits, a.out)
    elif a.cmd == 'evaluate':
        evaluate(a.variant, table, splits, a.out)
    elif a.cmd == 'old':
        old_student(table, splits, a.out)
    elif a.cmd == 'latency':
        latency(a.variant, table, splits, a.out)


if __name__ == '__main__':
    main()
