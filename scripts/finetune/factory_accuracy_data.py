"""Source-grounded, balanced synthetic data. No classifier sees sealed test data."""
import argparse
import asyncio
import collections
import hashlib
import json
from pathlib import Path
import re
import subprocess
from factory_accuracy_support import WORK,BOUNDARIES,semantics,OverlapIndex,verify_disjoint,source_inventory
from factory_experiment import read,write,save,sha,OLD
from factory_accuracy_api import SyntheticClient,MINI,BIG
from flywheel_reviewed import normalized

PREVIOUS=Path.home()/'Documents/Cretas意图微调-266-20261105'
PRIVATE=('OPENAI_API_KEY','Bearer ')


def clean(text):
    text=re.sub(r'客户原话.*','',text)
    text=re.sub(r'\bF\d{3}\b','示例工厂',text)
    text=re.sub(r'Sprint\s*\d+[^，。—]*[—:]?', '',text,flags=re.I)
    for token in PRIVATE:text=text.replace(token,'示例')
    return re.sub(r'\s+',' ',text).strip()


def initialize(root):
    from sklearn.feature_extraction.text import TfidfVectorizer
    if (root/'generation-protocol.json').exists():raise ValueError('Experiment already initialized')
    catalog=json.loads((WORK/'backend/python/ai/data/factory-intent-catalog.json').read_text(encoding='utf-8'))
    policy,aliases=semantics(catalog)
    if not (root/'inventory.json').exists():save(root/'inventory.json',source_inventory(catalog))
    inventory=json.loads((root/'inventory.json').read_text(encoding='utf-8'))
    bycode={r['intent_code']:r for r in inventory}
    definitions=[]
    for code,row in sorted(bycode.items()):
        if code in aliases:continue
        description=policy['label_notes'].get(code) or row['tool_contract'].get('description') or row.get('description') or row['intent_name']
        definitions.append(dict(code=code,name=row['intent_name'],meaning=clean(description)[:380]))
    vec=TfidfVectorizer(analyzer='char',ngram_range=(1,3),sublinear_tf=True)
    matrix=vec.fit_transform([r['name']*3+' '+r['meaning'] for r in definitions])
    similarity=(matrix@matrix.T).toarray()
    for i,row in enumerate(definitions):row['neighbors']=[definitions[j]['code'] for j in similarity[i].argsort()[::-1] if j!=i][:6]
    save(root/'definitions.json',definitions);save(root/'catalog.json',catalog)
    save(root/'boundary-policy.json',policy)
    protocol=dict(seed=20261105,source_base=subprocess.check_output(['git','rev-parse','HEAD'],cwd=WORK,text=True).strip(),physical_head=266,effective_intents=len(definitions),aliases=aliases,
        counts=dict(train=40,development=8,test=6),selection=dict(validation_per_class=4,calibration_per_class=2,test_per_class=4,test_minimum=2),
        generators=dict(train=MINI,development=MINI,test=BIG),semantic_policy_sha256=sha(BOUNDARIES),definitions_sha256=sha(root/'definitions.json'),
        locked_old_test_sha256=sha(PREVIOUS/'test.jsonl'),locked_old_review_sha256=sha(PREVIOUS/'reviewed-35.jsonl'),
        targets=dict(local_strict_accuracy=.80,local_macro_f1=.80,hybrid_accuracy=.95),
        test_policy='No local or evaluation-mini predictions on new tests until final model and calibration locked. Previous evaluated sets diagnostic-only. Never filter new tests by evaluator correctness.',
        model_selection='At most three preregistered local trials, validation only; sealed test once. Balanced classes, dropout, weight decay, label smoothing, early stop. No checkpoint chosen by test.',
        limitations=['Synthetic data, not real-business acceptance','True single-Tool aliases are declared before data generation; raw266 and canonical route scores both reported','Old baseline training provenance unknown'])
    save(root/'generation-protocol.json',protocol)
    print('INITIALIZED',len(definitions),'semantic intents,266 physical IDs; aliases',len(aliases),flush=True)


def context_for(code,defs):
    row=defs[code]
    return dict(target={k:row[k] for k in ['code','name','meaning']},neighbors=[dict(code=k,name=defs[k]['name'],meaning=defs[k]['meaning'][:120]) for k in row['neighbors']])


async def generate(root,phase):
    protocol=json.loads((root/'generation-protocol.json').read_text(encoding='utf-8'))
    assert sha(root/'definitions.json')==protocol['definitions_sha256']
    defs={r['code']:r for r in json.loads((root/'definitions.json').read_text(encoding='utf-8'))}
    short=phase.startswith('short-');base_phase=phase.removeprefix('short-')
    count={'train':10,'development':4,'test':2}[base_phase] if short else protocol['counts'][phase]
    model=protocol['generators'][base_phase]
    styles={
      'train':'编8个不同业务小场景，每场景5种自然说法，总共40句。覆盖操作员口述、手机短讯、交接班问话、经理日常指令；简短与较长、口语、常见错别字混合。每条18到65个汉字。不得只换日期/品名或用统一前缀当分类线索。',
      'development':'独立编8句未见过的需求，来自邮件片段、质控工单备注和部门沟通。不允许改写任何已见样本；你没有得到训练样本。每句22到70个汉字。表达需明确目标对象，但不靠照抄标签名字。',
      'test':'独立编6个有明确目标的挑战场景，分别用会议记录转述、语音转写、外部审计提问、跨部门客服转述等方式。每句25到75个汉字。至少两句避开标签名称的原词，允许自然省略礼貌词或语序变化，但保持意图唯一。不得用相同固定开头/业务编号编码答案。若存在真正等价入口，遵守已给的目标定义，不人为捏造执行区别。'}
    instructions=('为食品工厂ERP构建独立的意图分类语料，只生成合成用户原话，不执行业务。严格依据目标能力和排除边界，对照近邻意图区分。'
        '不得在原话中出现意图ID、工具函数名、代码变量、样本序号或“调用某工具/某接口”等伪线索。不要创造系统没有的动作、功能或输出。'
        '没有特别说明时，用某批次/某员工/某客户/某订单等合成对象，不用真实姓名、客户或工厂数据。'
        '只问查询/统计时不能改写成创建/执行。Skill只读时不要写成自动执行。需要对象类型或指标才明确时必须给足。'
        '目标是OUT_OF_DOMAIN时生成与工厂管理无关的闲聊；UNSUPPORTED_CAPABILITY只生成工厂相关的明确能力缺口，不混用闲聊。'
        '其余类别不涉及餐饮桌台、翻台率、菜品运营。不要解释答案。'+
        (f'独立编{count}句简短、自然的工厂用户问法，每句6到20汉字，必须包含足以区别近邻的业务对象或动作；不要机械复述标签名。不要用相同句式。你没有看到其他训练或测试样本，不得改写它们。' if short else styles[phase]))
    schema=dict(type='object',properties=dict(texts=dict(type='array',items=dict(type='string'),minItems=count,maxItems=count)),required=['texts'],additionalProperties=False)
    client=SyntheticClient(root,concurrency=4 if base_phase!='test' else 3)
    try:
        async def one(code):
            content=json.dumps(context_for(code,defs),ensure_ascii=False)
            if any(t in content for t in PRIVATE):raise ValueError('Private token in generation context')
            body=await client.ask('generate:'+phase+':'+code,model,instructions,content,schema,1536 if short else (4096 if phase=='train' else 1536))
            if len(body['texts'])!=count:raise ValueError('Wrong generated count')
            return [dict(text=text,label=code,split=phase,source='synthetic_'+model,group_id=f'20261105-accuracy:{phase}:{code}:{i//5 if phase=="train" else i}',record_id=f'{phase}:{code}:{i}') for i,text in enumerate(body['texts'])]
        codes=sorted(defs)
        first=await one(codes[0])
        rest=await asyncio.gather(*(one(code) for code in codes[1:]))
        write(root/('raw-'+phase+'.jsonl'),first+[r for group in rest for r in group])
        print('GENERATED',phase,count*len(codes),flush=True)
    finally:await client.close()


def structural(row,labels):
    text=row['text'].strip()
    if not 6<=len(text)<=180:return False
    if any(code in text for code in labels):return False
    if any(t in text for t in PRIVATE):return False
    if re.search(r'(?:intent|tool_name|sample_id|样本编号|意图代码)',text,re.I):return False
    return True


async def negatives(root):
    client=SyntheticClient(root,concurrency=2)
    schema=dict(type='object',properties=dict(texts=dict(type='array',items=dict(type='string'),minItems=80,maxItems=80)),required=['texts'],additionalProperties=False)
    base=('为食品工厂意图识别生成80句需要澄清的自然原话。它们是企业业务相关的请求，不能判闲聊。'
          '每句话在没有聊天上下文时至少可能指两种不同业务意图，缺的是业务对象、指标或动作目标，而不仅是实体ID。'
          '涵盖库存、批次、排名、指标环比、订单进度、设备报警、工序、考勤、财务等不同领域。'
          '不能把明确的报表请求或明确出货/采购/原料/成品需求故意标模糊。每条12到60汉字，口语自然，不含技术标签/答案，不重复固定前缀。')
    try:
        for phase,model,style in [('calibration',MINI,'风格为部门简短交流、工单备注。'),('test',BIG,'风格为语音转写和会议转述，语序多样，禁止故意提及“请你澄清”或解释有哪些候选答案。')]:
            body=await client.ask('negative:'+phase,model,base+style,'生成独立新场景；不要改写任何既有样本。',schema,8192)
            write(root/('raw-negative-'+phase+'.jsonl'),[dict(text=text,label='__CLARIFY__',split=phase,source='synthetic_'+model,group_id=f'new-negative:{phase}:{i}',record_id=f'negative:{phase}:{i}') for i,text in enumerate(body['texts'])])
    finally:await client.close()


async def audit_training(root):
    import random
    definitions={r['code']:r for r in json.loads((root/'definitions.json').read_text(encoding='utf-8'))}
    rows=read(root/'raw-train.jsonl');bylabel=collections.defaultdict(list)
    for row in rows:bylabel[row['label']].append(row)
    codes=sorted(definitions);batches=[];rng=random.Random(20261105)
    for start in range(0,len(codes),4):
        labels=codes[start:start+4]
        for offset in range(0,max(len(bylabel[label]) for label in labels),10):
            batch=[r for label in labels for r in bylabel[label][offset:offset+10]]
            rng.shuffle(batch);batches.append((labels,batch))
    client=SyntheticClient(root,concurrency=4)
    kept=[];rejected=[]
    try:
        async def one(i,labels,batch):
            candidates=sorted(set(labels+[n for label in labels for n in definitions[label]['neighbors']]))
            choices=[dict(code=c,name=definitions[c]['name'],meaning=definitions[c]['meaning']) for c in candidates]
            schema=dict(type='object',properties=dict(labels=dict(type='array',items=dict(type='string',enum=candidates+['__UNCERTAIN__']),minItems=len(batch),maxItems=len(batch))),required=['labels'],additionalProperties=False)
            content=json.dumps(dict(candidates=choices,texts=[r['text'] for r in batch]),ensure_ascii=False)
            body=await client.ask('audit:training:'+sha(root/'raw-train.jsonl')[:12]+':'+str(i),MINI,'独立审核合成训练文本。逐句按实际业务语义选择最准确意图，不得假设它们同类。只依候选定义判断，缺对象/跨多个意图/无合适候选选__UNCERTAIN__。区分查询/执行、原料/成品、记录/统计/报表、直接工具/真正综合工作台。顺序对应输入。',content,schema,3072)
            if len(body['labels'])!=len(batch):raise ValueError('Audit alignment mismatch')
            return [(dict(row,training_audit=actual),actual==row['label']) for row,actual in zip(batch,body['labels'])]
        results=await asyncio.gather(*(one(i,labels,batch) for i,(labels,batch) in enumerate(batches)))
        for batch in results:
            for row,accepted in batch:(kept if accepted else rejected).append(row)
        write(root/'audited-train.jsonl',kept);write(root/'audit-rejected.jsonl',rejected)
        print('AUDITED',len(kept),'kept',len(rejected),'rejected',flush=True)
    finally:await client.close()


def curate(root):
    if (root/'data-manifest.json').exists():raise ValueError('Frozen data exists')
    protocol=json.loads((root/'generation-protocol.json').read_text(encoding='utf-8'))
    labels=[r['code'] for r in json.loads((root/'definitions.json').read_text(encoding='utf-8'))]
    old_protected=read(WORK/'scripts/finetune/data/evaluation_holdout.jsonl')+read(OLD/'historical-retention.jsonl')
    old_protected=[dict(r,group_id=r.get('group_id') or 'old-protected:'+hashlib.sha256(r['text'].encode()).hexdigest()) for r in old_protected]
    old_training=read(WORK/'scripts/finetune/data/full_training_data.jsonl')+read(PREVIOUS/'train.jsonl')+read(OLD/'new-train.jsonl')
    oldindex=OverlapIndex(old_protected+old_training)
    excluded=[];test=[]
    for label in labels:
        rows=[r for r in read(root/'raw-test.jsonl') if r['label']==label]
        chosen=[]
        for row in rows:
            if not structural(row,labels) or oldindex.overlaps(row) or (chosen and OverlapIndex(chosen).overlaps(row)):
                excluded.append(dict(row,reason='structural_or_historical_overlap'));continue
            if len(chosen)<4:chosen.append(row)
        if len(chosen)<2:raise ValueError('Insufficient fresh tests: '+label)
        for row in [r for r in read(root/'raw-short-test.jsonl') if r['label']==label]:
            if structural(row,labels) and not oldindex.overlaps(row) and not OverlapIndex(chosen).overlaps(row):chosen.append(row)
            else:excluded.append(dict(row,reason='short_test_historical_or_overlap'))
        test+=chosen
    protectedindex=OverlapIndex(old_protected+old_training+test)
    validation=[];calibration=[]
    for label in labels:
        chosen=[]
        for row in [r for r in read(root/'raw-development.jsonl') if r['label']==label]:
            if not structural(row,labels) or protectedindex.overlaps(row) or (chosen and OverlapIndex(chosen).overlaps(row)):
                excluded.append(dict(row,reason='dev_structural_or_protected_overlap'));continue
            chosen.append(row)
        if len(chosen)<4:raise ValueError('Insufficient development samples: '+label)
        validation+=chosen[:-2][:4];calibration+=chosen[-2:]
        short_dev=[]
        for row in [r for r in read(root/'raw-short-development.jsonl') if r['label']==label]:
            if structural(row,labels) and not protectedindex.overlaps(row) and not OverlapIndex(chosen+short_dev).overlaps(row):short_dev.append(row)
            else:excluded.append(dict(row,reason='short_dev_historical_or_overlap'))
        validation+=short_dev[:2];calibration+=short_dev[2:]
    negative_root=root/'negative-generation'
    for name,target in [('test',test),('calibration',calibration)]:
        guard=OverlapIndex(old_protected+old_training+test+validation+calibration)
        negative_seen=set()
        for row in read(negative_root/('raw-negative-'+name+'.jsonl')):
            key=normalized(row['text'])
            if key not in negative_seen and structural(row,labels) and not guard.overlaps(row):
                target.append(row);negative_seen.add(key)
            else:excluded.append(dict(row,reason='negative_structural_or_overlap'))
    # Short cross-label expressions may collide although per-label checks pass.
    # Keep test untouched, then calibration, then validation in this priority.
    guard=OverlapIndex(test)
    retained=[]
    for row in calibration:
        if guard.overlaps(row):excluded.append(dict(row,reason='global_calibration_test_overlap'))
        else:retained.append(row)
    calibration=retained;guard=OverlapIndex(calibration+test);retained=[]
    for row in validation:
        if guard.overlaps(row):excluded.append(dict(row,reason='global_validation_protected_overlap'))
        else:retained.append(row)
    validation=retained
    valcounts=collections.Counter(r['label'] for r in validation)
    calcounts=collections.Counter(r['label'] for r in calibration)
    if any(not valcounts[label] or not calcounts[label] for label in labels):raise ValueError('A class lost development coverage after global overlap checks')
    trainindex=OverlapIndex(old_protected+test+validation+calibration)
    training=[];seen={}
    for row in read(root/'audited-train.jsonl'):
        if not structural(row,labels) or trainindex.overlaps(row):
            excluded.append(dict(row,reason='train_structural_or_protected_overlap'));continue
        key=normalized(row['text'])
        if key in seen:
            if seen[key]!=row['label']:raise ValueError('Conflicting labels in generated training')
            continue
        seen[key]=row['label'];training.append(row)
    counts=collections.Counter(r['label'] for r in training)
    if any(counts[label]<20 for label in labels):raise ValueError('Insufficient training support: '+str({l:counts[l] for l in labels if counts[l]<20}))
    for name,rows in [('train',training),('validation',validation),('calibration',calibration),('test',test),('protected',old_protected+calibration+test),('excluded',excluded)]:write(root/(name+'.jsonl'),rows)
    verify_disjoint(training,old_protected+validation+calibration+test)
    verify_disjoint(validation,calibration+test);verify_disjoint(calibration,test)
    manifest=dict(physical_head=266,effective_intents=len(labels),aliases=protocol['aliases'],counts=dict(train=len(training),validation=len(validation),calibration=len(calibration),test=len(test)),train_per_label=dict(counts),
        hashes={name:sha(root/name) for name in ['train.jsonl','validation.jsonl','calibration.jsonl','test.jsonl','protected.jsonl','definitions.json','boundary-policy.json','generation-protocol.json','coverage-augmentation-protocol.json']},
        primary_test_sealed=True,test_prediction_runs=0,exclusion_policy='Uniform source/schema/privacy/exact/near-overlap only; never evaluator correctness',old_tests_unchanged=(sha(PREVIOUS/'test.jsonl')==protocol['locked_old_test_sha256']))
    save(root/'data-manifest.json',manifest);print('FROZEN',manifest['counts'],flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('phase',choices=['initialize','train','development','test','short-train','short-development','short-test','negatives','audit','curate']);parser.add_argument('--evidence',type=Path,required=True)
    args=parser.parse_args()
    if args.phase=='initialize':initialize(args.evidence)
    elif args.phase=='curate':curate(args.evidence)
    elif args.phase=='negatives':asyncio.run(negatives(args.evidence))
    elif args.phase=='audit':asyncio.run(audit_training(args.evidence))
    else:asyncio.run(generate(args.evidence,args.phase))
