"""Independent follow-up cohort. Old evaluations stay protected; no production writes."""
import argparse
import asyncio
from collections import Counter,defaultdict
import json
import math
import os
from pathlib import Path
import random
import time

from factory_experiment import read,write,save,sha
from factory_accuracy_api import SyntheticClient,MINI,BIG
from factory_accuracy_data import structural,context_for
from factory_accuracy_support import WORK,OverlapIndex,verify_disjoint
from factory_accuracy_train import predict,scores
from factory_accuracy_eval import CandidatePool,decision,probabilities
from flywheel_reviewed import normalized

PREVIOUS=Path.home()/'Documents/Cretas意图准确率-20261105'
BASELINE=PREVIOUS/'models/balanced-a/final'
SEED=202611052


def unlocked(root):
    if (root/'confirmation-opened.json').exists():
        raise ValueError('Confirmation opened; further fitting is forbidden')


def locked(root):
    protocol=json.loads((root/'protocol.json').read_text(encoding='utf-8'))
    for name,digest in protocol['previous_hashes'].items():
        if sha(PREVIOUS/name)!=digest:raise ValueError('Prior evidence changed: '+name)
    if sha(BASELINE/'model.safetensors')!=protocol['baseline_sha256']:
        raise ValueError('Baseline weights changed')
    for name,digest in protocol['source_hashes'].items():
        if sha(WORK/name)!=digest:raise ValueError('Source contract changed: '+name)
    return protocol


def initialize(root):
    root.mkdir(parents=True,exist_ok=True)
    if (root/'protocol.json').exists():raise ValueError('Already initialized')
    names=['train.jsonl','validation.jsonl','calibration.jsonl','test.jsonl','definitions.json','boundary-policy.json','generation-protocol.json','blind-summary.json','selection.json','routing-policy.json']
    protocol=dict(seed=SEED,previous_hashes={n:sha(PREVIOUS/n) for n in names},baseline_sha256=sha(BASELINE/'model.safetensors'),
        source_hashes={n:sha(WORK/n) for n in ['backend/python/ai/data/factory-intent-catalog.json','scripts/finetune/data/factory_semantic_boundaries.json']},
        train_new_per_class=12,old_replay_per_class=20,development_per_class=3,confirmation_candidates_per_class=4,
        confirmation_selection='first two structurally valid independent long rows and first short row; reserve long only repairs structural/overlap exclusions before inference',
        confirmation_negatives=40,calibration_negatives=40,maximum_trials=2,
        configs=[dict(name='boundary-a',lr=1e-5,freeze=4,smoothing=.05),dict(name='boundary-b',lr=7e-6,freeze=2,smoothing=.10)],
        epochs=4,batch_size=8,maximum_train_validation_gap=.10,maximum_validation_confirmation_gap=.10,
        local_target=.80,macro_target=.80,hybrid_target=.95,short_target=.80,long_target=.80,
        stop_validation_accuracy=.90,stop_validation_macro=.88,model_selection='validation macroF1 only; no old or new test score',
        runtime='local + same GPT5.4 mini snapshot; no critic or stronger fallback',
        data_status='independent synthetic teacher data; not human-approved business gold',
        generation_inputs='source definitions and neighbors only; no old error text, logits, or evaluation examples')
    save(root/'protocol.json',protocol)
    save(root/'budget-previous-roots.json',[str(PREVIOUS)])
    save(root/'definitions.json',json.loads((PREVIOUS/'definitions.json').read_text(encoding='utf-8')))
    print('PREREGISTERED independent follow-up; prior failed result unchanged',flush=True)


async def generate(root,phase):
    unlocked(root);protocol=locked(root)
    definitions={r['code']:r for r in json.loads((root/'definitions.json').read_text(encoding='utf-8'))}
    count={'train':12,'development':3,'confirmation':4}[phase]
    model=BIG if phase=='confirmation' else MINI
    style={
        'train':'生成12句：前6句35到75汉字，像不同岗位随口提的业务诉求，避免直接使用标签名称，通过想要拿到的结果表达；接着4句30到70字，背景自然涉及相邻业务但最后目标清楚唯一，不要刻意写“不查X只查Y”等答案提示；末2句8到20字的简短自然问法。每句独立场景，不是同一句改写。',
        'development':'生成3句独立需求，每句35到75汉字。风格分别为现场沟通、邮件摘录和客户转述，背景可以有其他业务，但所需输出唯一；避免复制标签名称。不得共享同一个小场景。',
        'confirmation':'生成4句独立、未见过的业务原话：前2句为会议转述/故障复盘中的35到80字请求，包含自然的相邻业务背景，但最终要什么必须唯一明确，至少一句用生活化解释替代专业标签名；第3句8到20字短问；第4句另一个独立长场景，供结构去重时备用。不要刻意说“不是X而是Y”，不要问超过一种最终目标。'}[phase]
    instruction=('依据目标Tool能力与邻近边界生成食品工厂ERP意图数据，只返回合成用户原话，不执行。查询/执行、流水/统计/综合分析、原料/成品、直接工具/综合工作台必须区分。'
        '不能编造系统未支持的动作。不能出现标签ID、函数名、技术变量或答案编号，不能使用真实客户、姓名、工厂代码。缺实体ID不妨碍明确意图；缺业务对象不能当明确意图。'
        'OUT_OF_DOMAIN只生成真正无关闲聊；UNSUPPORTED_CAPABILITY生成明确但系统不支持的工厂需求。其余不要涉及餐饮桌台菜品运营。'+style)
    schema=dict(type='object',properties=dict(texts=dict(type='array',items=dict(type='string'),minItems=count,maxItems=count)),required=['texts'],additionalProperties=False)
    # Confirmation uses its own receipt/ledger files and a different model budget.
    # It may be generated alongside training text, with no shared writer files.
    directory=root if phase=='train' else root/(phase+'-generation')
    directory.mkdir(exist_ok=True)
    if directory!=root:(directory/'shared-budget-root').write_text('parent',encoding='utf-8')
    client=SyntheticClient(directory,concurrency=3)
    try:
        async def one(code):
            context=context_for(code,definitions)
            context['neighbors']=[dict(r,meaning=r['meaning'][:80]) for r in context['neighbors'][:3]]
            body=await client.ask('v2:generate:'+phase+':'+code,model,instruction,json.dumps(context,ensure_ascii=False),schema,2048 if phase=='train' else 1536)
            if len(body['texts'])!=count:raise ValueError('Generation count mismatch')
            return [dict(text=text,label=code,record_id=f'v2:{phase}:{code}:{i}',group_id=f'v2-family:{phase}:{code}:{i}',source='synthetic_'+model,split=phase,style=('short' if (phase=='confirmation' and i==2) or (phase=='train' and i>=10) else 'long')) for i,text in enumerate(body['texts'])]
        results=await asyncio.gather(*(one(code) for code in sorted(definitions)))
        write(root/('raw-'+phase+'.jsonl'),[r for batch in results for r in batch])
        print('GENERATED',phase,sum(map(len,results)),flush=True)
    finally:await client.close()


async def audit(root):
    unlocked(root);locked(root)
    definitions={r['code']:r for r in json.loads((root/'definitions.json').read_text(encoding='utf-8'))}
    bylabel=defaultdict(list)
    for row in read(root/'raw-train.jsonl'):bylabel[row['label']].append(row)
    codes=sorted(bylabel);rng=random.Random(SEED);batches=[]
    for start in range(0,len(codes),4):
        labels=codes[start:start+4];batch=[r for c in labels for r in bylabel[c]];rng.shuffle(batch)
        candidates=sorted(set(labels+[n for c in labels for n in definitions[c]['neighbors']]))
        batches.append((batch,candidates))
    client=SyntheticClient(root,concurrency=3)
    try:
        async def one(i,batch,candidates):
            request_id='v2:audit:'+str(i)
            incident_path=root/'audit-format-incident.json'
            incident=json.loads(incident_path.read_text(encoding='utf-8')) if incident_path.exists() else {}
            if request_id in incident.get('unknown_never_replay',[]):return []
            previous=client.done.get(request_id)
            if previous and previous.get('error'):
                if request_id not in incident.get('completed_format_repair',[]):raise ValueError('Explicit audit repair authorization missing')
                request_id='v2:audit-format-repair1:'+str(i)
            max_output=incident.get('original_output_limits',{}).get('v2:audit:'+str(i),4096)
            if previous and previous.get('error'):max_output=4096
            schema=dict(type='object',properties=dict(labels=dict(type='array',items=dict(type='string',enum=candidates+['__UNCERTAIN__']),minItems=len(batch),maxItems=len(batch))),required=['labels'],additionalProperties=False)
            payload=dict(candidates=[{k:definitions[c][k] for k in ['code','name','meaning']} for c in candidates],texts=[r['text'] for r in batch])
            result=await client.ask(request_id,MINI,'独立复核工厂用户原话。逐句按最终需要的输出和动作选最具体标签，不要被背景名词误导；查询不能当执行。无唯一明确目标、无合适候选或不确定则__UNCERTAIN__。顺序对应原话，不推测生成标签。',json.dumps(payload,ensure_ascii=False),schema,max_output)
            if len(result['labels'])!=len(batch):raise ValueError('Audit alignment mismatch')
            return [dict(r,training_audit=label) for r,label in zip(batch,result['labels'])]
        output=[r for batch in await asyncio.gather(*(one(i,*batch) for i,batch in enumerate(batches))) for r in batch]
        write(root/'audited-train.jsonl',[r for r in output if r['training_audit']==r['label']])
        write(root/'audit-rejected.jsonl',[r for r in output if r['training_audit']!=r['label']])
        print('AUDIT',len(output),'reviewed',sum(r['training_audit']==r['label'] for r in output),'accepted',flush=True)
    finally:await client.close()


async def negatives(root):
    unlocked(root);locked(root);client=SyntheticClient(root,concurrency=2)
    schema=dict(type='object',properties=dict(texts=dict(type='array',items=dict(type='string'),minItems=40,maxItems=40)),required=['texts'],additionalProperties=False)
    try:
        for phase,model in [('calibration',MINI),('confirmation',BIG)]:
            body=await client.ask('v2:negative:'+phase,model,'生成40句食品工厂业务交流，缺业务对象/指标/动作目标，至少可能对应两种不同意图。缺实体编号不能成为模糊理由；明确销售/原料/成品/设备/考勤请求不要误标。风格自然多样12到60汉字，不刻意要求澄清，不输出标签ID或解释。','独立新场景，仅返回原话。',schema,4096)
            write(root/('negative-'+phase+'.jsonl'),[dict(text=t,label='__CLARIFY__',record_id=f'v2:negative:{phase}:{i}',group_id=f'v2-negative:{phase}:{i}',style='ambiguous',split=phase,source='synthetic_'+model) for i,t in enumerate(body['texts'])])
    finally:await client.close()


def curate(root):
    unlocked(root);protocol=locked(root)
    if (root/'manifest.json').exists():raise ValueError('Frozen data exists')
    labels={r['code'] for r in json.loads((root/'definitions.json').read_text(encoding='utf-8'))}
    old_training=read(PREVIOUS/'train.jsonl')
    protected=read(WORK/'scripts/finetune/data/evaluation_holdout.jsonl')
    protected+=read(PREVIOUS/'protected.jsonl')
    old_index=OverlapIndex(protected+old_training)
    excluded=[];seen={};test=[];dev=[]
    def eligible(row,index):
        if not structural(row,labels) or index.overlaps(row):
            excluded.append(dict(row,reason='structural_or_protected_overlap'));return False
        key=normalized(row['text'])
        if key in seen:
            excluded.append(dict(row,reason='duplicate_generated_text'));return False
        seen[key]=row['label'];return True
    bylabel=defaultdict(list)
    for row in read(root/'raw-confirmation.jsonl'):bylabel[row['label']].append(row)
    for code,rows in sorted(bylabel.items()):
        long=0;short=0
        for row in rows:
            if (row['style']=='long' and long>=2) or (row['style']=='short' and short>=1):continue
            if eligible(row,old_index):
                test.append(row)
                if row['style']=='short':short+=1
                else:long+=1
    test_index=OverlapIndex(protected+old_training+test)
    for row in read(root/'negative-confirmation.jsonl'):
        if eligible(row,test_index):test.append(row)
    dev_index=OverlapIndex(protected+old_training+test)
    for row in read(root/'raw-development.jsonl'):
        if eligible(row,dev_index):dev.append(row)
    val=[];cal=[];bylabel=defaultdict(list)
    for row in dev:bylabel[row['label']].append(row)
    for code,rows in sorted(bylabel.items()):
        val+=rows[:2];cal+=rows[2:3]
    val_index=OverlapIndex(val+test)
    safe_cal=[]
    for row in cal:
        if val_index.overlaps(row):excluded.append(dict(row,reason='calibration_validation_overlap'))
        else:safe_cal.append(row)
    cal=safe_cal
    cal_index=OverlapIndex(protected+old_training+test+val+cal)
    for row in read(root/'negative-calibration.jsonl'):
        if eligible(row,cal_index):cal.append(row)
    # Old evaluation text is never eligible for replay; replay is solely old training.
    replay=[];bylabel=defaultdict(list);rng=random.Random(SEED)
    for row in old_training:bylabel[row['label']].append(row)
    for code,rows in sorted(bylabel.items()):
        longs=[r for r in rows if not r['record_id'].startswith('short-')]
        shorts=[r for r in rows if r['record_id'].startswith('short-')]
        rng.shuffle(longs);rng.shuffle(shorts);replay+=longs[:15]+shorts[:5]
    train_index=OverlapIndex(protected+read(root/'raw-confirmation.jsonl')+test+val+cal);train=[]
    for row in replay+read(root/'audited-train.jsonl'):
        if eligible(row,train_index):train.append(row)
    verify_disjoint(train,protected+test+val+cal)
    verify_disjoint(val,test+cal);verify_disjoint(cal,test)
    covered={r['label'] for r in test if r['label']!='__CLARIFY__'}
    if covered!=labels:raise ValueError('Confirmation misses labels: '+str(sorted(labels-covered)))
    if len(val)<400 or len(cal)<120:raise ValueError('Insufficient independent development data')
    if set(r['label'] for r in train)!=labels:raise ValueError('Training label coverage incomplete')
    for name,rows in [('train',train),('validation',val),('calibration',cal),('test',test),('protected',protected+test+cal),('excluded',excluded)]:write(root/(name+'.jsonl'),rows)
    save(root/'manifest.json',dict(counts={n:len(r) for n,r in [('train',train),('validation',val),('calibration',cal),('test',test)]},train_per_label=dict(Counter(r['label'] for r in train)),hashes={n:sha(root/n) for n in ['protocol.json','definitions.json','train.jsonl','validation.jsonl','calibration.jsonl','test.jsonl','protected.jsonl']},aliases=json.loads((PREVIOUS/'data-manifest.json').read_text(encoding='utf-8'))['aliases'],replay=len([r for r in train if not r['record_id'].startswith('v2:')]),test_used_for_fit=False))
    print('FROZEN',json.dumps(json.loads((root/'manifest.json').read_text(encoding='utf-8'))['counts']),flush=True)


def verify(root):
    protocol=locked(root);manifest=json.loads((root/'manifest.json').read_text(encoding='utf-8'))
    for name,digest in manifest['hashes'].items():
        if sha(root/name)!=digest:raise ValueError('Frozen cohort changed: '+name)
    return protocol,manifest


def train(root):
    unlocked(root);protocol,manifest=verify(root)
    os.environ['HF_HUB_OFFLINE']='1';os.environ['TRANSFORMERS_OFFLINE']='1';os.environ['TOKENIZERS_PARALLELISM']='false'
    import torch
    from incremental_finetune import FinetuneConfig,run_finetuning
    torch.set_num_threads(4)
    training=read(root/'train.jsonl');validation=read(root/'validation.jsonl')
    verify_disjoint(training,read(root/'protected.jsonl')+validation)
    rng=random.Random(SEED);groups=defaultdict(list)
    for row in training:groups[row['label']].append(row)
    sample=[r for label,rows in sorted(groups.items()) for r in rng.sample(rows,min(3,len(rows)))];receipts=[]
    for config in protocol['configs']:
        output=root/'models'/config['name'];receipt_path=root/(config['name']+'-receipt.json')
        if receipt_path.exists():receipt=json.loads(receipt_path.read_text(encoding='utf-8'))
        else:
            if output.exists():raise ValueError('Incomplete training directory; inspect before resume')
            interval=math.ceil(len(training)/8);started=time.perf_counter()
            trainer,metrics=run_finetuning(FinetuneConfig(new_data_path=str(root/'train.jsonl'),model_path=str(BASELINE),output_path=str(output),epochs=protocol['epochs'],batch_size=8,learning_rate=config['lr'],freeze_layers=config['freeze'],max_length=128,seed=SEED,validation_data_path=str(root/'validation.jsonl'),protected_test_path=str(root/'protected.jsonl'),eval_steps=interval,save_steps=interval,early_stopping_patience=2,label_smoothing_factor=config['smoothing'],metric_for_best_model='f1_macro'))
            elapsed=time.perf_counter()-started;history=trainer.state.log_history;del trainer
            if torch.cuda.is_available():torch.cuda.empty_cache()
            val,mapping=predict(output/'final',validation);sample_pred,_=predict(output/'final',sample)
            raw=scores(val);sample_score=scores(sample_pred)
            receipt=dict(config=config,training_seconds=elapsed,validation=raw,training_sample=sample_score,training_validation_gap=sample_score['accuracy']-raw['accuracy'],model_sha256=sha(output/'final/model.safetensors'),history=history)
            save(receipt_path,receipt);write(root/(config['name']+'-validation.jsonl'),val)
        receipts.append(receipt)
        if receipt['validation']['accuracy']>=.90 and receipt['validation']['macro_f1']>=.88 and receipt['training_validation_gap']<=.10:break
    best=max(receipts,key=lambda r:(r['validation']['macro_f1'],r['validation']['accuracy'],-r['training_validation_gap']))
    selected=root/'models'/best['config']['name']/'final'
    save(root/'selection.json',dict(name=best['config']['name'],model_path=str(selected),model_sha256=sha(selected/'model.safetensors'),validation=best['validation'],training_validation_gap=best['training_validation_gap'],trials=len(receipts),test_used=False))
    predictions,mapping=predict(selected,read(root/'calibration.jsonl'));write(root/'calibration-local.jsonl',predictions);save(root/'model-mapping.json',mapping)
    print('SELECTED',best['config']['name'],best['validation'],flush=True)


async def mini_many(root,phase,rows,pool):
    directory=root/(phase+'-shadow');directory.mkdir(exist_ok=True)
    (directory/'shared-budget-root').write_text('parent',encoding='utf-8');client=SyntheticClient(directory,concurrency=3)
    instruction=('识别工厂ERP最终业务目标，只返回最具体吻合的候选编号，不执行。背景提到的对象不是目标。区分查询/执行、原料/成品、流水/统计/综合工作台。缺业务对象/指标/动作或无唯一匹配选需要澄清的候选；缺具体编号不妨碍明确意图。无关闲聊和明确缺失能力按对应候选定义选择。')
    try:
        async def one(row):
            choices=pool.choices(row['text'],row['logits'])
            choices=[dict(value=r['value'],description=r['description'][:100]) for r in choices]
            values=[r['value'] for r in choices]
            schema=dict(type='object',properties=dict(choice=dict(type='integer',enum=list(range(len(values)))),confidence=dict(type='number',minimum=0,maximum=1)),required=['choice','confidence'],additionalProperties=False)
            compact=[dict(id=i,description=c['description']) for i,c in enumerate(choices)]
            body=await client.ask('v2:'+phase+':'+row['record_id'],MINI,instruction,json.dumps(dict(text=row['text'],candidates=compact),ensure_ascii=False),schema,768)
            choice=body['choice']
            if type(choice) is not int or not 0<=choice<len(values):raise ValueError('Invalid candidate index')
            return dict(record_id=row['record_id'],prediction=values[choice],confidence=body['confidence'],candidates=values)
        output=await asyncio.gather(*(one(row) for row in rows));write(root/(phase+'-mini.jsonl'),output)
        return {r['record_id']:r for r in output}
    finally:await client.close()


async def calibrate(root):
    unlocked(root);protocol,manifest=verify(root)
    import numpy as np
    selection=json.loads((root/'selection.json').read_text(encoding='utf-8'));mapping=json.loads((root/'model-mapping.json').read_text(encoding='utf-8'));rows=read(root/'calibration-local.jsonl')
    pool=CandidatePool(json.loads((root/'definitions.json').read_text(encoding='utf-8')),mapping,manifest['aliases'])
    cloud=await mini_many(root,'calibration',rows,pool)
    positives=[r for r in rows if r['label'] in mapping]
    temperature=min([.75,1.,1.25,1.5,2.,2.5,3.],key=lambda t:sum(-np.log(max(probabilities(r['logits'],t)[mapping[r['label']]],1e-12)) for r in positives))
    grid=[]
    for threshold in [.7,.8,.9,.95,.98]:
        for margin in [.05,.1]:
            for mini_confidence in [.65,.8]:
                policy=dict(temperature=temperature,confidence=threshold,margin=margin,mini_confidence=mini_confidence,critic_enabled=False)
                paired=[dict(r,prediction=decision(r,mapping,policy,cloud[r['record_id']])[0]) for r in rows]
                grid.append(dict(policy=policy,hybrid=scores(paired),mini_calls=sum(decision(r,mapping,policy)[1]=='abstain' for r in rows)))
    feasible=[r for r in grid if r['hybrid']['accuracy']>=.97]
    best=min(feasible,key=lambda r:(r['mini_calls'],-r['hybrid']['accuracy'])) if feasible else max(grid,key=lambda r:(r['hybrid']['accuracy'],-r['mini_calls']))
    policy=dict(best['policy'],model_sha256=selection['model_sha256'],test_hash=sha(root/'test.jsonl'),runner_sha256=sha(Path(__file__)),decision_sha256=sha(WORK/'scripts/finetune/factory_accuracy_eval.py'),selection_sha256=sha(root/'selection.json'),mapping_sha256=sha(root/'model-mapping.json'),selected_on='independent calibration only',metrics=best)
    path=root/'routing-policy.json'
    if path.exists() and json.loads(path.read_text(encoding='utf-8'))!=policy:raise ValueError('Routing policy changed')
    save(path,policy);save(root/'calibration-search.json',grid)
    print('CALIBRATED',json.dumps(best),flush=True)


async def confirm(root):
    protocol,manifest=verify(root);selection=json.loads((root/'selection.json').read_text(encoding='utf-8'));policy=json.loads((root/'routing-policy.json').read_text(encoding='utf-8'))
    if sha(Path(__file__))!=policy['runner_sha256'] or sha(WORK/'scripts/finetune/factory_accuracy_eval.py')!=policy['decision_sha256']:raise ValueError('Routing implementation changed')
    if sha(root/'selection.json')!=policy['selection_sha256'] or sha(root/'model-mapping.json')!=policy['mapping_sha256'] or sha(root/'test.jsonl')!=policy['test_hash']:raise ValueError('Calibrated selection, mapping or test changed')
    selected=Path(selection['model_path'])
    if sha(selected/'model.safetensors')!=policy['model_sha256']:raise ValueError('Selected model changed')
    stamp=dict(model=policy['model_sha256'],test=sha(root/'test.jsonl'),policy=sha(root/'routing-policy.json'),selection=sha(root/'selection.json'))
    marker=root/'confirmation-opened.json'
    if marker.exists():
        if json.loads(marker.read_text(encoding='utf-8'))!=stamp:raise ValueError('Confirmation lock mismatch')
        if (root/'confirmation-summary.json').exists():raise ValueError('Confirmation already complete')
    else:
        with marker.open('x',encoding='utf-8') as f:json.dump(stamp,f,indent=2)
    rows=read(root/'test.jsonl');path=root/'confirmation-local.jsonl'
    if path.exists():pred=read(path);mapping=json.loads((root/'model-mapping.json').read_text(encoding='utf-8'))
    else:pred,mapping=predict(selected,rows);write(path,pred)
    pool=CandidatePool(json.loads((root/'definitions.json').read_text(encoding='utf-8')),mapping,manifest['aliases'])
    uncertain=[r for r in pred if decision(r,mapping,policy)[1]=='abstain'];cloud=await mini_many(root,'confirmation',uncertain,pool)
    paired=[dict(r,raw_prediction=r['prediction'],prediction=decision(r,mapping,policy,cloud.get(r['record_id']))[0],route=decision(r,mapping,policy,cloud.get(r['record_id']))[1],mini=cloud.get(r['record_id'])) for r in pred]
    write(root/'confirmation-paired.jsonl',paired)
    styles={r['record_id']:r['style'] for r in rows}
    raw=scores(pred);hybrid=scores(paired);positive=scores([r for r in pred if r['label']!='__CLARIFY__'])
    long=scores([r for r in pred if styles[r['record_id']]=='long']);short=scores([r for r in pred if styles[r['record_id']]=='short'])
    gap=selection['validation']['accuracy']-positive['accuracy']
    numeric=raw['accuracy']>=.80 and raw['macro_f1']>=.80 and hybrid['accuracy']>=.95
    generalization=selection['training_validation_gap']<=.10 and gap<=.10 and long['accuracy']>=.80 and short['accuracy']>=.80
    previous,_=predict(BASELINE,rows);write(root/'confirmation-previous266.jsonl',previous)
    summary=dict(n=len(rows),raw266=raw,hybrid=hybrid,positive_raw=positive,long_query_raw=long,short_query_raw=short,previous266=scores(previous),mini_calls=len(uncertain),mini_review_calls=0,
        numeric_targets_met=numeric,generalization_checks=dict(passed=generalization,training_validation_gap=selection['training_validation_gap'],validation_to_positive_test_gap=gap,no_test_based_fit=True),targets_met=numeric and generalization,
        model_sha256=stamp['model'],primary_confirmation_openings=1,candidate_misses=[r['record_id'] for r in paired if r['mini'] and r['label'] not in r['mini']['candidates']],
        clarification=dict(n=sum(r['label']=='__CLARIFY__' for r in paired),correct=sum(r['label']==r['prediction']=='__CLARIFY__' for r in paired)))
    save(root/'confirmation-summary.json',summary);print('CONFIRMATION',json.dumps(summary),flush=True)


def regression(root):
    if not (root/'confirmation-summary.json').exists():raise ValueError('Complete independent confirmation first')
    verify(root);selection=json.loads((root/'selection.json').read_text(encoding='utf-8'));out={}
    for name,path in [('previous1637',PREVIOUS/'test.jsonl'),('original35',Path.home()/'Documents/Cretas意图微调-266-20261105/reviewed-35.jsonl')]:
        rows=[dict(r,record_id=r.get('record_id',name+':'+str(i))) for i,r in enumerate(read(path))]
        pred,_=predict(Path(selection['model_path']),rows);write(root/(name+'-new266.jsonl'),pred);out[name]=scores(pred)
        if name=='previous1637':
            source={r['record_id']:r for r in rows}
            out[name]['long']=scores([r for r in pred if r['label']!='__CLARIFY__' and not source[r['record_id']]['record_id'].startswith('short-test:')])
    save(root/'regression-summary.json',dict(results=out,used_for_fitting=False));print('REGRESSION',json.dumps(out),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('phase',choices=['initialize','generate-train','generate-development','generate-confirmation','audit','negatives','curate','train','calibrate','confirm','regression']);parser.add_argument('--evidence',type=Path,required=True);args=parser.parse_args()
    if args.phase.startswith('generate-'):asyncio.run(generate(args.evidence,args.phase.removeprefix('generate-')))
    else:
        result=globals()[args.phase](args.evidence)
        if asyncio.iscoroutine(result):asyncio.run(result)
