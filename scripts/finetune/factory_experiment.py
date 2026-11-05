"""Offline factory-catalog experiment. No production model/service writes.

Evidence directories are explicit. Test labels are never supplied to retrieval
or teachers. Initialization transfers only identically named classifier rows.
"""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import random
import time
from difflib import SequenceMatcher
from flywheel_reviewed import normalized, HoldoutGuard
from experiment_split_contract import validate_splits

WORK = Path(__file__).resolve().parents[2]
OLD = Path.home() / 'Documents/Cretas意图微调-20261104'


def read(path):
    return [json.loads(s) for s in Path(path).read_text(encoding='utf-8-sig').splitlines() if s.strip()]


def save(path, obj):
    Path(path).write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding='utf-8')


def write(path, rows):
    Path(path).write_text(''.join(json.dumps(r, ensure_ascii=False)+'\n' for r in rows), encoding='utf-8')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def near(row, protected):
    text = normalized(row['text'])
    return any(SequenceMatcher(None, text, t).ratio() >= .85 for t in protected)


def transfer_head(model, old_mapping, labels, torch):
    old_weight = model.classifier.weight.detach().clone()
    old_bias = model.classifier.bias.detach().clone()
    head = torch.nn.Linear(model.classifier.in_features, len(labels))
    model._init_weights(head)
    with torch.no_grad():
        for i, label in enumerate(labels):
            if label in old_mapping:
                head.weight[i].copy_(old_weight[old_mapping[label]])
                head.bias[i].copy_(old_bias[old_mapping[label]])
    model.classifier = head
    model.num_labels = len(labels)
    model.config.num_labels = len(labels)
    model.config.label2id = {label:i for i,label in enumerate(labels)}
    model.config.id2label = dict(enumerate(labels))
    return model


def prepare(root):
    if (root/'manifest.json').exists():
        raise ValueError('Frozen experiment exists; use a new evidence directory')
    catalog_path = WORK/'backend/python/ai/data/factory-intent-catalog.json'
    catalog = json.loads(catalog_path.read_text(encoding='utf-8'))
    rows = catalog['canonical_intents']; bycode = {r['intent_code']:r for r in rows}
    labels = sorted(bycode); assert len(labels) == 266
    save(root/'catalog.json', catalog)
    mapping = json.loads((OLD/'baseline-model/label_mapping.json').read_text(encoding='utf-8'))['label_to_id']
    reviews = read(OLD/'reviewed-35.jsonl')
    overrides = {31:'QUALITY_CHECK_SUMMARY',35:'ATTENDANCE_STATS',27:'UNSUPPORTED_CAPABILITY',28:'UNSUPPORTED_CAPABILITY',29:'UNSUPPORTED_CAPABILITY'}
    for row in reviews:
        n = row['record_id'].split('-')[-1]
        n = int(n) if n.isdigit() else 0
        label = overrides.get(n, row.get('active_label'))
        if label not in bycode: label = '__CLARIFY__'
        row.update(label=label, active_label=None if label=='__CLARIFY__' else label,
                   status='clarify' if label=='__CLARIFY__' else 'mapped',
                   current_definition=bycode.get(label), training_eligible=False,
                   split='protected_diagnostic', group_id='original-review:'+row['record_id'],
                   reviewer='Codex source-based catalog review; not human business approval')
        if n in (27,28,29): row['reason']='当前工厂目录无通用设备实时状态/故障/运行报告入口，明确能力缺口；不能用维护或告警替代设备健康结论'
        if n==31: row['reason']='指定批次的已有质检结果走质检综合汇总，不是生成全厂质量统计报表'
        if n==35: row['reason']='询问人数是统计意图；今日打卡标签专指打卡记录'
        if label=='__CLARIFY__': row['clarification']='请补充业务对象、指标或查询范围。'
    write(root/'reviewed-35.jsonl', reviews)
    # All earlier observed evaluation and retention rows remain protected.
    previous = read(WORK/'scripts/finetune/data/evaluation_holdout.jsonl')
    for name in ['fresh-test','validation','historical-retention']:
        previous += read(OLD/(name+'.jsonl'))
    previous += reviews
    previous = [dict(r,group_id=r.get('group_id') or 'protected:'+hashlib.sha256(normalized(r['text']).encode()).hexdigest()) for r in previous if r.get('text','').strip()]
    authored = json.loads((root/'authored-families.json').read_text(encoding='utf-8'))
    splits = {'train':[], 'validation':[], 'test':[]}
    excluded = []
    for label, families in authored.items():
        assert label in bycode
        for split, texts in zip(splits, families):
            for i,text in enumerate(texts):
                splits[split].append(dict(text=text,label=label,group_id=f'authored-20261105:{split}:{label}:{i}',source='independently_authored_synthetic',split=split))
    for i,text in enumerate(['把那个给我处理一下','现在怎么样','我要看排名','这几天异常查一下','这些数据比较一下','看看这个单子','查个指标','给我库存数字']):
        splits['test'].append(dict(text=text,label='__CLARIFY__',group_id=f'new-ambiguous:{i}',source='independently_authored_synthetic',split='test'))
    for i,text in enumerate(['今晚有什么电影可以看','帮我写一首描写月亮的诗','解释一下火星的地形','一号机器现在是不是正常转动','检测全部设备的即时运行状态','给出机台运行时长分析报告']):
        splits['test'].append(dict(text=text,label='OUT_OF_DOMAIN' if i<3 else 'UNSUPPORTED_CAPABILITY',group_id=f'new-boundary:{i}',source='independently_authored_synthetic',split='test'))
    oldtexts = [normalized(r['text']) for r in previous]
    # Preserve tests, exclude train/development conflicts without looking at predictions.
    splits['test'] = [r for r in splits['test'] if not near(r,oldtexts)]
    testtexts = [normalized(r['text']) for r in splits['test']]
    splits['validation'] = [r for r in splits['validation'] if not near(r,oldtexts+testtexts)]
    protected = previous + splits['test'] + splits['validation']
    pt = [normalized(r['text']) for r in protected]
    training = list(splits['train'])
    # Definition-derived seeds are explicitly weak training data, never test data.
    for r in rows:
        for text in [r['intent_name'], '请帮我'+r['intent_name']]:
            training.append(dict(text=text,label=r['intent_code'],group_id='catalog-seed:'+r['intent_code'],source='catalog_definition_weak_seed',split='train'))
    training += [dict(r,source='previous_authored_training',split='train') for r in read(OLD/'new-train.jsonl') if r['label'] in bycode]
    legacy = collections.defaultdict(list)
    for r in read(WORK/'scripts/finetune/data/full_training_data.jsonl'):
        if r['label'] in bycode: legacy[r['label']].append(r)
    rng = random.Random(20261105)
    for label, data in sorted(legacy.items()):
        rng.shuffle(data)
        for r in data[:16]:
            training.append(dict(r,group_id='historical:'+hashlib.sha256(normalized(r['text']).encode()).hexdigest(),source='historical_replay_unknown_original_provenance',split='train'))
    seen = {}; conflict = set()
    for row in training:
        key=normalized(row['text'])
        if key in seen and seen[key]['label'] != row['label']: conflict.add(key)
        if not near(row,pt): seen[key]=row
        else: excluded.append(dict(row,reason='protected_exact_or_near_overlap'))
    splits['train'] = [r for k,r in seen.items() if k not in conflict]
    validate_splits(splits['train'], splits['validation'], splits['test'])
    for name,data in splits.items(): write(root/(name+'.jsonl'),data)
    write(root/'protected.jsonl',previous+splits['test'])
    write(root/'excluded.jsonl',excluded)
    all_labels = set(r['label'] for r in splits['train'])
    manifest = dict(seed=20261105,base_sha=os.popen('git rev-parse HEAD').read().strip(),catalog_sha256=sha(catalog_path),
                    labels=266,shared_old_head=len(set(labels)&set(mapping)),new_head_rows=len(set(labels)-set(mapping)),
                    train=len(splits['train']),validation=len(splits['validation']),test=len(splits['test']),review=35,
                    trained_classes=len(all_labels),classes_without_training=sorted(set(labels)-all_labels),
                    sources=dict(collections.Counter(r['source'] for r in splits['train'])),
                    hashes={name:sha(root/name) for name in ['train.jsonl','validation.jsonl','test.jsonl','protected.jsonl','reviewed-35.jsonl','authored-families.json','catalog.json']},
                    baseline_sha256=sha(OLD/'baseline-model/model.safetensors'),
                    protocol=dict(epochs=3,lr=2e-5,batch_size=2,freeze_layers=10,max_length=96,local_confidence=.8,local_margin=.2,temperature=1.5,cloud_confidence=.65,topk=24),
                    limitations=['synthetic pilot, not real business acceptance','catalog seeds are weak supervision','old model training provenance unknown','fixed thresholds; no test-score optimization'])
    save(root/'manifest.json',manifest)
    print(json.dumps(manifest,ensure_ascii=False),flush=True)


def verify(root):
    m=json.loads((root/'manifest.json').read_text(encoding='utf-8'))
    for name,digest in m['hashes'].items():
        if sha(root/name)!=digest: raise ValueError('Frozen evidence changed: '+name)
    if sha(OLD/'baseline-model/model.safetensors')!=m['baseline_sha256']: raise ValueError('Baseline changed')
    validate_splits(read(root/'train.jsonl'),read(root/'validation.jsonl'),read(root/'test.jsonl'))
    return m


def train(root):
    m=verify(root)
    os.environ['HF_HUB_OFFLINE']='1'; os.environ['TRANSFORMERS_OFFLINE']='1'; os.environ['TOKENIZERS_PARALLELISM']='false'
    import torch
    from transformers import AutoModelForSequenceClassification, AutoTokenizer
    from incremental_finetune import run_finetuning,FinetuneConfig
    torch.set_num_threads(4); torch.manual_seed(m['seed'])
    labels=sorted(r['intent_code'] for r in json.loads((root/'catalog.json').read_text(encoding='utf-8'))['canonical_intents'])
    oldmap=json.loads((OLD/'baseline-model/label_mapping.json').read_text(encoding='utf-8'))['label_to_id']
    initialized=root/'initialized-266'
    if initialized.exists(): raise ValueError('Initialization already exists; no silent rerun')
    model=AutoModelForSequenceClassification.from_pretrained(OLD/'baseline-model',local_files_only=True)
    model=transfer_head(model,oldmap,labels,torch)
    model.save_pretrained(initialized)
    AutoTokenizer.from_pretrained(OLD/'baseline-model',local_files_only=True).save_pretrained(initialized)
    save(initialized/'label_mapping.json',dict(label_to_id=model.config.label2id,id_to_label=model.config.id2label))
    del model
    started=time.perf_counter()
    interval=(m['train']+1)//2
    trainer,metrics=run_finetuning(FinetuneConfig(new_data_path=str(root/'train.jsonl'),model_path=str(initialized),output_path=str(root/'candidate'),epochs=3,batch_size=2,learning_rate=2e-5,freeze_layers=10,max_length=96,seed=m['seed'],validation_data_path=str(root/'validation.jsonl'),protected_test_path=str(root/'protected.jsonl'),eval_steps=interval,save_steps=interval))
    save(root/'training-receipt.json',dict(seconds=time.perf_counter()-started,metrics=metrics,device=str(trainer.args.device),candidate_sha256=sha(root/'candidate/final/model.safetensors'),baseline_sha256=sha(OLD/'baseline-model/model.safetensors'),online_writes=0))


def local(root):
    m=verify(root)
    os.environ['HF_HUB_OFFLINE']='1';os.environ['TRANSFORMERS_OFFLINE']='1'
    import torch
    from transformers import AutoTokenizer,AutoModelForSequenceClassification
    torch.set_num_threads(4)
    active={r['intent_code'] for r in json.loads((root/'catalog.json').read_text(encoding='utf-8'))['canonical_intents']}
    out=[]
    candidate=root/'candidate-complete/final' if (root/'completion-receipt.json').exists() else root/'candidate/final'
    for arm,path in [('baseline259',OLD/'baseline-model'),('candidate266',candidate)]:
        mapping=json.loads((path/'label_mapping.json').read_text(encoding='utf-8'))['label_to_id'];rev={v:k for k,v in mapping.items()}
        model=AutoModelForSequenceClassification.from_pretrained(path,local_files_only=True).eval().cpu()
        tok=AutoTokenizer.from_pretrained(path,local_files_only=True)
        with torch.inference_mode():
            for _ in range(3): model(**tok('生产批次信息',return_tensors='pt'))
            for split in ['validation','test','reviewed-35']:
                for i,row in enumerate(read(root/(split+'.jsonl'))):
                    start=time.perf_counter()
                    p,idx=(model(**tok(row['text'],return_tensors='pt',truncation=True,max_length=96)).logits/1.5).softmax(-1)[0].topk(5)
                    pred=rev[idx[0].item()]; conf=p[0].item();margin=conf-p[1].item()
                    out.append(dict(arm=arm,id=f'{split}:{i}',split=split,text=row['text'],label=row['label'],prediction=pred,confidence=conf,margin=margin,local_ms=(time.perf_counter()-start)*1000,accepted=pred in active and conf>=.8 and margin>=.2,top5=[dict(label=rev[j.item()],confidence=v.item()) for j,v in zip(idx,p)]))
        del model
    write(root/'local-results.jsonl',out)
    print('LOCAL_COMPLETE',len(out),flush=True)


def complete(root):
    """Complete the frozen three-epoch budget after over-frequent warmup early stop.

    This decision uses only validation logs; no test prediction has run yet.
    Preserve the preliminary run, initialize from the exact same saved weights.
    """
    m=verify(root)
    if (root/'local-results.jsonl').exists(): raise ValueError('Test predictions already observed')
    if (root/'candidate-complete').exists(): raise ValueError('Completion already attempted')
    os.environ['HF_HUB_OFFLINE']='1';os.environ['TRANSFORMERS_OFFLINE']='1'
    import torch
    from incremental_finetune import run_finetuning,FinetuneConfig
    torch.set_num_threads(4)
    interval=(m['train']+1)//2
    save(root/'completion-protocol.json',dict(reason='Initial 50-step validation stopped during warmup at epoch0.26; complete the original 3epoch budget with per-epoch checks, no test predictions observed',eval_steps=interval,save_steps=interval,early_stopping_patience=3,initialized_sha256=sha(root/'initialized-266/model.safetensors')))
    start=time.perf_counter()
    trainer,metrics=run_finetuning(FinetuneConfig(new_data_path=str(root/'train.jsonl'),model_path=str(root/'initialized-266'),output_path=str(root/'candidate-complete'),epochs=3,batch_size=2,learning_rate=2e-5,freeze_layers=10,max_length=96,seed=m['seed'],validation_data_path=str(root/'validation.jsonl'),protected_test_path=str(root/'protected.jsonl'),eval_steps=interval,save_steps=interval))
    save(root/'completion-receipt.json',dict(seconds=time.perf_counter()-start,metrics=metrics,device=str(trainer.args.device),candidate_sha256=sha(root/'candidate-complete/final/model.safetensors'),baseline_sha256=sha(OLD/'baseline-model/model.safetensors'),online_writes=0))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('phase',choices=['prepare','train','complete','local']);parser.add_argument('--evidence',type=Path,required=True)
    args=parser.parse_args(); globals()[args.phase](args.evidence)
