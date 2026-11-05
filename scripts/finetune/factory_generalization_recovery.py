"""Recover a quota-interrupted confirmation without changing model or routing.

Unknown HTTP outcomes are never retried and are conservatively scored wrong.
This module cannot raise the daily allowance or authorize additional spending.
"""
import argparse
import asyncio
import json
from pathlib import Path
from factory_generalization import verify,mini_many,BASELINE,WORK
from factory_accuracy_eval import CandidatePool,decision
from factory_accuracy_train import predict,scores
from factory_experiment import read,write,save,sha


def validate(root):
    protocol,manifest=verify(root)
    policy=json.loads((root/'routing-policy.json').read_text(encoding='utf-8'))
    selected=json.loads((root/'selection.json').read_text(encoding='utf-8'))
    opened=json.loads((root/'confirmation-opened.json').read_text(encoding='utf-8'))
    checks=[(WORK/'scripts/finetune/factory_generalization.py',policy['runner_sha256']),
            (WORK/'scripts/finetune/factory_accuracy_eval.py',policy['decision_sha256']),
            (root/'selection.json',policy['selection_sha256']),
            (root/'model-mapping.json',policy['mapping_sha256']),
            (root/'test.jsonl',policy['test_hash']),
            (Path(selected['model_path'])/'model.safetensors',opened['model']),
            (root/'routing-policy.json',opened['policy'])]
    for path,digest in checks:
        if sha(path)!=digest:raise ValueError('Frozen confirmation dependency changed: '+path.name)
    if (root/'confirmation-summary.json').exists():raise ValueError('Confirmation already complete')
    return manifest,policy,selected


def freeze(root):
    validate(root)
    directory=root/'confirmation-shadow';ledger_path=directory/'api-ledger.json'
    before=ledger_path.read_bytes();ledger=json.loads(before)
    records={r['id']:r for r in read(directory/'api-results.jsonl')}
    failures=[r['id'] for r in ledger if r['state']=='pending']
    if any(records.get(id,{}).get('status') for id in failures):
        raise ValueError('Unknown-resolution procedure requires no complete HTTP response')
    snapshot=root/'confirmation-interruption.json'
    if snapshot.exists():
        incident=json.loads(snapshot.read_text(encoding='utf-8'))
        if sha(root/'confirmation-local.jsonl')!=incident['local_predictions_sha256']:raise ValueError('Interrupted local results changed')
        if failures:raise ValueError('New unknown requests require a separate explicit incident')
        return incident
    incident=dict(local_predictions_sha256=sha(root/'confirmation-local.jsonl'),unknown_never_replay=failures,
        original_ledger_sha256=sha(ledger_path),failure_score='Always incorrect, including expected clarification; no rows dropped',
        routing_changed=False,model_changed=False,retry_unknown=False)
    (root/'confirmation-ledger-before-resolution.json').write_bytes(before)
    for row in ledger:
        if row['id'] in failures:
            day=records.get(row['id'],{}).get('day')
            if not day:raise ValueError('Unknown request day unavailable')
            row.update(state='abandoned_unknown',day=day,resolution='never replay; full token upper-bound charged; score as error')
    if ledger_path.read_bytes()!=before:raise ValueError('Concurrent ledger writer')
    save(ledger_path,ledger);save(snapshot,incident)
    return incident


async def recover(root):
    manifest,policy,selected=validate(root)
    incident=freeze(root)
    mapping=json.loads((root/'model-mapping.json').read_text(encoding='utf-8'))
    rows=read(root/'test.jsonl');pred=read(root/'confirmation-local.jsonl')
    if [(r['record_id'],r['text'],r['label']) for r in rows]!=[(r['record_id'],r['text'],r['label']) for r in pred]:
        raise ValueError('Local prediction rows differ from frozen test')
    pool=CandidatePool(json.loads((root/'definitions.json').read_text(encoding='utf-8')),mapping,manifest['aliases'])
    uncertain=[r for r in pred if decision(r,mapping,policy)[1]=='abstain']
    unknown=set(incident['unknown_never_replay'])
    eligible=[r for r in uncertain if 'v2:confirmation:'+r['record_id'] not in unknown]
    # Completed responses must match the original request fingerprint; only unsent
    # requests hit the network. The original quota guard still applies.
    cloud=await mini_many(root,'confirmation',eligible,pool)
    for row in uncertain:
        if 'v2:confirmation:'+row['record_id'] in unknown:
            cloud[row['record_id']]=dict(prediction='__API_ERROR__',confidence=1.,candidates=[r['value'] for r in pool.choices(row['text'],row['logits'])],system_error='no_complete_response_never_replayed')
    paired=[dict(r,raw_prediction=r['prediction'],prediction=decision(r,mapping,policy,cloud.get(r['record_id']))[0],route=decision(r,mapping,policy,cloud.get(r['record_id']))[1],mini=cloud.get(r['record_id'])) for r in pred]
    write(root/'confirmation-paired.jsonl',paired)
    styles={r['record_id']:r['style'] for r in rows}
    raw=scores(pred);hybrid=scores(paired);positive=scores([r for r in pred if r['label']!='__CLARIFY__'])
    long=scores([r for r in pred if styles[r['record_id']]=='long']);short=scores([r for r in pred if styles[r['record_id']]=='short'])
    gap=selected['validation']['accuracy']-positive['accuracy']
    numeric=raw['accuracy']>=.80 and raw['macro_f1']>=.80 and hybrid['accuracy']>=.95
    generalization=selected['training_validation_gap']<=.10 and gap<=.10 and long['accuracy']>=.80 and short['accuracy']>=.80
    old_path=root/'confirmation-previous266.jsonl';old_receipt=root/'comparison-baseline-receipt.json'
    if old_path.exists() and old_receipt.exists():
        receipt=json.loads(old_receipt.read_text(encoding='utf-8'))
        if receipt!=dict(model_sha256=sha(BASELINE/'model.safetensors'),test_sha256=sha(root/'test.jsonl'),predictions_sha256=sha(old_path)):raise ValueError('Baseline comparison cache changed')
        old=read(old_path)
        if [(r['record_id'],r['text'],r['label']) for r in old]!=[(r['record_id'],r['text'],r['label']) for r in rows]:raise ValueError('Baseline comparison rows changed')
    else:
        old,_=predict(BASELINE,rows);write(old_path,old)
    summary=dict(n=len(rows),raw266=raw,hybrid=hybrid,positive_raw=positive,long_query_raw=long,short_query_raw=short,previous266=scores(old),mini_calls=len(uncertain),mini_review_calls=0,
        numeric_targets_met=numeric,generalization_checks=dict(passed=generalization,training_validation_gap=selected['training_validation_gap'],validation_to_positive_test_gap=gap,no_test_based_fit=True),targets_met=numeric and generalization,
        model_sha256=policy['model_sha256'],primary_confirmation_openings=1,candidate_misses=[r['record_id'] for r in paired if r['mini'] and r['label'] not in r['mini']['candidates']],
        clarification=dict(n=sum(r['label']=='__CLARIFY__' for r in paired),correct=sum(r['label']==r['prediction']=='__CLARIFY__' for r in paired)),
        transport_failures_scored_incorrect=incident['unknown_never_replay'],recovered_same_frozen_confirmation=True)
    save(root/'confirmation-summary.json',summary)
    print('RECOVERED_CONFIRMATION',json.dumps(summary),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('phase',choices=['freeze','recover']);parser.add_argument('--evidence',type=Path,required=True);args=parser.parse_args()
    if args.phase=='freeze':print(json.dumps(freeze(args.evidence)),flush=True)
    else:asyncio.run(recover(args.evidence))
