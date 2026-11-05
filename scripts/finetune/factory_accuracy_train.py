"""Bounded validation-only training search; sealed-test access locks further fitting."""
import argparse
import json
import math
import os
from pathlib import Path
import random
import time
from factory_experiment import read,save,write,sha,OLD
from factory_accuracy_support import verify_disjoint

PREVIOUS=Path.home()/'Documents/Cretas意图微调-266-20261105'
BASELINE=PREVIOUS/'candidate-complete/final'


def verify(root):
    manifest=json.loads((root/'data-manifest.json').read_text(encoding='utf-8'))
    for name,digest in manifest['hashes'].items():
        if sha(root/name)!=digest:raise ValueError('Frozen dataset changed: '+name)
    if not manifest['old_tests_unchanged']:raise ValueError('Old holdout changed')
    protocol=json.loads((root/'generation-protocol.json').read_text(encoding='utf-8'))
    if sha(PREVIOUS/'test.jsonl')!=protocol['locked_old_test_sha256'] or sha(PREVIOUS/'reviewed-35.jsonl')!=protocol['locked_old_review_sha256']:
        raise ValueError('Previously protected evaluation was modified')
    return manifest


def predict(path,rows,batch_size=24):
    os.environ['HF_HUB_OFFLINE']='1';os.environ['TRANSFORMERS_OFFLINE']='1';os.environ['TOKENIZERS_PARALLELISM']='false'
    import torch
    from transformers import AutoTokenizer,AutoModelForSequenceClassification
    torch.set_num_threads(4)
    model=AutoModelForSequenceClassification.from_pretrained(path,local_files_only=True).eval()
    device='cuda' if torch.cuda.is_available() else 'cpu';model=model.to(device)
    tok=AutoTokenizer.from_pretrained(path,local_files_only=True)
    mapping=json.loads((Path(path)/'label_mapping.json').read_text(encoding='utf-8'))['label_to_id']
    reverse={v:k for k,v in mapping.items()};out=[]
    with torch.inference_mode():
        for start in range(0,len(rows),batch_size):
            batch=rows[start:start+batch_size]
            logits=model(**tok([r['text'] for r in batch],return_tensors='pt',padding=True,truncation=True,max_length=128).to(device)).logits.cpu().float().tolist()
            for row,values in zip(batch,logits):
                order=sorted(range(len(values)),key=values.__getitem__,reverse=True)
                out.append(dict(record_id=row['record_id'],text=row['text'],label=row['label'],prediction=reverse[order[0]],logits=values,top20=[reverse[i] for i in order[:20]]))
    del model
    if torch.cuda.is_available():torch.cuda.empty_cache()
    return out,mapping


def scores(rows,aliases=None):
    from sklearn.metrics import f1_score
    aliases=aliases or {}
    gold=[aliases.get(r['label'],r['label']) for r in rows]
    actual=[aliases.get(r['prediction'],r['prediction']) for r in rows]
    return dict(n=len(rows),correct=sum(a==g for a,g in zip(actual,gold)),accuracy=sum(a==g for a,g in zip(actual,gold))/len(rows),macro_f1=float(f1_score(gold,actual,average='macro',zero_division=0)))


def train(root):
    if (root/'blind-opened.json').exists():raise ValueError('Sealed test already opened; no further fitting')
    manifest=verify(root)
    plan_path=root/'search-plan.json'
    configs=[dict(name='balanced-a',lr=2e-5,freeze=6,smoothing=.05),dict(name='regularized-b',lr=1e-5,freeze=4,smoothing=.10),dict(name='stable-c',lr=3e-5,freeze=8,smoothing=.05)]
    plan=dict(baseline_sha256=sha(BASELINE/'model.safetensors'),data_hashes=manifest['hashes'],configs=configs,epochs=6,batch_size=8,weight_decay=.01,maximum_trials=3,stop_validation_accuracy=.90,stop_validation_macro=.88,maximum_train_validation_gap=.10,test_access_allowed=False)
    if plan_path.exists():
        if json.loads(plan_path.read_text(encoding='utf-8'))!=plan:raise ValueError('Training search plan changed')
    else:save(plan_path,plan)
    import torch
    from incremental_finetune import FinetuneConfig,run_finetuning
    os.environ['HF_HUB_OFFLINE']='1';os.environ['TRANSFORMERS_OFFLINE']='1';os.environ['TOKENIZERS_PARALLELISM']='false'
    torch.set_num_threads(4)
    training=read(root/'train.jsonl');validation=read(root/'validation.jsonl');calibration=read(root/'calibration.jsonl')
    verify_disjoint(training,read(root/'protected.jsonl')+validation)
    rng=random.Random(20261105);bylabel={}
    for row in training:bylabel.setdefault(row['label'],[]).append(row)
    sample=[]
    for label,rows in sorted(bylabel.items()):sample+=rng.sample(rows,min(3,len(rows)))
    receipts=[]
    for config in configs:
        output=root/'models'/config['name'];receipt_path=root/(config['name']+'-receipt.json')
        if receipt_path.exists():receipt=json.loads(receipt_path.read_text(encoding='utf-8'))
        else:
            if output.exists():raise ValueError('Incomplete training directory; inspect before resume')
            interval=math.ceil(len(training)/8)
            start=time.perf_counter()
            trainer,metrics=run_finetuning(FinetuneConfig(new_data_path=str(root/'train.jsonl'),model_path=str(BASELINE),output_path=str(output),epochs=6,batch_size=8,learning_rate=config['lr'],freeze_layers=config['freeze'],max_length=128,seed=20261105,validation_data_path=str(root/'validation.jsonl'),protected_test_path=str(root/'protected.jsonl'),eval_steps=interval,save_steps=interval,early_stopping_patience=2,label_smoothing_factor=config['smoothing'],metric_for_best_model='f1_macro'))
            duration=time.perf_counter()-start
            history=trainer.state.log_history;best=trainer.state.best_model_checkpoint
            del trainer
            if torch.cuda.is_available():torch.cuda.empty_cache()
            val,mapping=predict(output/'final',validation);sample_pred,_=predict(output/'final',sample)
            raw=scores(val);train_score=scores(sample_pred)
            receipt=dict(config=config,training_seconds=duration,metrics=metrics,best_checkpoint=best,validation_raw=raw,validation_route=scores(val,manifest['aliases']),training_sample=train_score,gap=train_score['accuracy']-raw['accuracy'],model_sha256=sha(output/'final/model.safetensors'),history=history)
            save(receipt_path,receipt);write(root/(config['name']+'-validation.jsonl'),val)
        receipts.append(receipt)
        if receipt['validation_raw']['accuracy']>=.90 and receipt['validation_raw']['macro_f1']>=.88 and receipt['gap']<=.10:break
    best=max(receipts,key=lambda r:(r['validation_raw']['macro_f1'],r['validation_raw']['accuracy'],-r['gap']))
    selected=root/'models'/best['config']['name']/'final'
    selection=dict(name=best['config']['name'],model_path=str(selected),model_sha256=sha(selected/'model.safetensors'),validation=best['validation_raw'],training_validation_gap=best['gap'],trials=len(receipts),test_used=False)
    save(root/'selection.json',selection)
    predictions,mapping=predict(selected,calibration)
    write(root/'calibration-local.jsonl',predictions);save(root/'model-mapping.json',mapping)
    print('SELECTED',json.dumps(selection,ensure_ascii=False),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--evidence',type=Path,required=True);args=parser.parse_args();train(args.evidence)
