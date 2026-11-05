"""Calibration-only routing selection followed by a single locked blind evaluation."""
import argparse
import asyncio
import hashlib
import json
from pathlib import Path
import re
import numpy as np
from factory_experiment import read,save,write,sha,OLD
from factory_accuracy_train import verify,predict,scores,BASELINE
from factory_accuracy_api import SyntheticClient,MINI


def needs_clarification(text):
    """Small source-defined ambiguity checks, not question/answer memorization."""
    if re.search(r'库存|存量',text) and not re.search(r'原料|原材料|物料|成品|在制|报表|汇总|周转|库龄|预警|补货|低库存|批次|安全库存|冻结|盘盈|盘点|赠品|赠料|免费|预留|领用|消耗|释放|分配|调拨',text):return True
    if re.search(r'排名|排行|冠军|前十',text) and not re.search(r'产品|成品|商品|供应商|客户|员工|人员|部门|车间|品种|原料',text):return True
    if re.search(r'环比|同比|走势|趋势',text) and not re.search(r'销售|营收|收入|成本|产量|生产|质量|合格|库存|工时|利润|出货|良率|收款|经营|报表|报告',text):return True
    if re.search(r'异常情况|异常数据',text) and not re.search(r'考勤|打卡|设备|系统|质检|质量|生产|库存|冷链|温度|财务|订单|指标',text):return True
    concrete=r'采购|销售|发货|出货|原料|原材料|物料|成品|质检|质量|打卡|签到|请假|报销|加班|工资|成本|利润|资产|现金流|发票|开票|调拨|供应商|客户|员工|排产|工序|生产|添加剂|配方|冷链|留样|召回|清洁|HACCP|SSOP|BOM|密码|权限|电子秤|摄像头|帮助|指引|指南|使用说明|操作说明|上一页|下一页|返回|个人资料|切换工厂|系统设置|反馈|仪表盘|继续|恢复'
    if re.search(r'那个|上次|刚才|那张|这张|那份|这份|这批|那批',text) and not re.search(concrete,text,re.I):return True
    return False


def probabilities(logits,temperature):
    values=np.asarray(logits,dtype=float)/temperature;values-=values.max()
    p=np.exp(values);return p/p.sum()


class CandidatePool:
    def __init__(self,definitions,mapping,aliases):
        from sklearn.feature_extraction.text import TfidfVectorizer
        self.defs={r['code']:r for r in definitions};self.codes=list(self.defs)
        self.mapping=mapping;self.reverse={v:k for k,v in mapping.items()};self.aliases=aliases
        self.vec=TfidfVectorizer(analyzer='char',ngram_range=(1,3),sublinear_tf=True)
        self.mat=self.vec.fit_transform([r['name']*3+' '+r['meaning'] for r in definitions])

    def choices(self,text,logits):
        local=[self.aliases.get(self.reverse[int(i)],self.reverse[int(i)]) for i in np.argsort(logits)[::-1][:12]]
        lexical=(self.mat@self.vec.transform([text]).T).toarray().ravel()
        lex=[self.codes[int(i)] for i in np.argsort(lexical)[::-1][:18]]
        rank={}
        for weight,items in [(2.,local),(1.,lex)]:
            for i,code in enumerate(items):
                if code in self.defs:rank[code]=rank.get(code,0)+weight/(i+20)
        for code in local[:2]+lex[:2]:
            for neighbor in self.defs.get(code,{}).get('neighbors',[])[:3]:rank[neighbor]=rank.get(neighbor,0)+.02
        ordered=sorted(rank,key=rank.get,reverse=True)[:30]
        for code in ['OUT_OF_DOMAIN','UNSUPPORTED_CAPABILITY']:
            if code not in ordered:ordered.append(code)
        return [dict(value=code,description=self.defs[code]['name']+'：'+self.defs[code]['meaning'][:110]) for code in ordered]+[dict(value='__CLARIFY__',description='业务对象、指标或动作缺失，或没有语义吻合的候选，需要补充说明。')]


async def mini_many(root,phase,rows,pool):
    directory=root/(phase+'-shadow');directory.mkdir(exist_ok=True)
    (directory/'shared-budget-root').write_text('parent',encoding='utf-8')
    client=SyntheticClient(directory,concurrency=4)
    instructions=('你是食品工厂ERP的意图选择器，只识别，不执行。选择最具体匹配的候选。'
        '业务对象/指标不明确、语义可落入多类或无合适候选时选__CLARIFY__，不能强行选。缺具体实体ID不妨碍已经明确的意图。'
        '仔细区分原料/成品、记录/统计/综合报表、单项查询/综合工作台、查询/执行、历史发货/待发清单、打卡流水/出勤人数、基础追溯/召回客户/源头个体。'
        '只读组合不得被解释为自动执行。明确缺失能力选UNSUPPORTED_CAPABILITY，真正闲聊选OUT_OF_DOMAIN。仅使用候选的定义，不依据排列位置猜。')
    try:
        async def one(row):
            choices=pool.choices(row['text'],row['logits'])
            schema=dict(type='object',properties=dict(label=dict(type='string',enum=[c['value'] for c in choices]),confidence=dict(type='number',minimum=0,maximum=1)),required=['label','confidence'],additionalProperties=False)
            # No gold label, case ID or source-family ID is passed to the model.
            content=json.dumps(dict(text=row['text'],candidates=choices),ensure_ascii=False)
            id=phase+':'+row['record_id']
            body=await client.ask(id,MINI,instructions,content,schema,768)
            return dict(record_id=row['record_id'],prediction=body['label'],confidence=body['confidence'],candidates=[c['value'] for c in choices],request_id=id)
        first=await one(rows[0]) if rows else None
        rest=await asyncio.gather(*(one(r) for r in rows[1:]))
        output=([first] if first else [])+rest
        write(root/(phase+'-mini.jsonl'),output)
        return {r['record_id']:r for r in output}
    finally:await client.close()


def decision(row,mapping,policy,cloud=None):
    reverse={v:k for k,v in mapping.items()}
    def from_mini():
        review=cloud.get('review') if policy.get('critic_enabled') else None
        if review is not None:
            return (review['prediction'] if review['confidence']>=policy['critic_confidence'] else '__CLARIFY__'),'mini-review'
        return (cloud['prediction'] if cloud['confidence']>=policy['mini_confidence'] else '__CLARIFY__'),'mini'
    # A lexical signal is uncertainty, not proof that the request is unclear.
    # Send it to Mini for semantic verification rather than vetoing real intent.
    if needs_clarification(row['text']):
        if cloud is not None:return from_mini()
        return '__CLARIFY__','abstain'
    p=probabilities(row['logits'],policy['temperature']);idx=np.argsort(p)[::-1]
    if p[idx[0]]>=policy['confidence'] and p[idx[0]]-p[idx[1]]>=policy['margin']:
        return reverse[int(idx[0])],'local'
    if cloud is not None:
        return from_mini()
    return '__CLARIFY__','abstain'


def evaluate(rows,mapping,policy,cloud):
    local=[];hybrid=[]
    for row in rows:
        lp,_=decision(row,mapping,policy)
        hp,route=decision(row,mapping,policy,cloud[row['record_id']])
        local.append(dict(row,prediction=lp));hybrid.append(dict(row,prediction=hp,route=route))
    return scores(local),scores(hybrid),sum((r['route'] in ('mini','mini-review'))+(r['route']=='mini-review') for r in hybrid)


async def calibrate(root):
    if (root/'blind-opened.json').exists():raise ValueError('Blind evaluation started; calibration is locked')
    manifest=verify(root);selection=json.loads((root/'selection.json').read_text(encoding='utf-8'))
    rows=read(root/'calibration-local.jsonl');mapping=json.loads((root/'model-mapping.json').read_text(encoding='utf-8'))
    definitions=json.loads((root/'definitions.json').read_text(encoding='utf-8'))
    pool=CandidatePool(definitions,mapping,manifest['aliases'])
    cloud=await mini_many(root,'calibration',rows,pool)
    from factory_accuracy_critic import review_disagreements
    cloud=await review_disagreements(root,'calibration',rows,cloud,pool)
    positives=[r for r in rows if r['label'] in mapping]
    temperatures=[.75,1.,1.25,1.5,2.,2.5,3.]
    temperature=min(temperatures,key=lambda t:sum(-np.log(max(probabilities(r['logits'],t)[mapping[r['label']]],1e-12)) for r in positives))
    grid=[]
    for threshold in [.45,.55,.65,.75,.80,.85,.90,.95,.98]:
        for margin in [.05,.10,.20,.30]:
            for mini_confidence in [.5,.8]:
                for critic_enabled,critic_confidence in [(False,.65),(True,.65),(True,.8)]:
                    policy=dict(temperature=temperature,confidence=threshold,margin=margin,mini_confidence=mini_confidence,critic_enabled=critic_enabled,critic_confidence=critic_confidence)
                    local,hybrid,calls=evaluate(rows,mapping,policy,cloud)
                    grid.append(dict(policy=policy,local=local,hybrid=hybrid,mini_calls=calls))
    feasible=[r for r in grid if r['hybrid']['accuracy']>=.97]
    selected=min(feasible,key=lambda r:(r['mini_calls'],-r['hybrid']['accuracy'])) if feasible else max(grid,key=lambda r:(r['hybrid']['accuracy'],r['local']['accuracy'],-r['mini_calls']))
    policy=dict(selected['policy'],model_sha256=selection['model_sha256'],test_hash=manifest['hashes']['test.jsonl'],definitions_sha256=manifest['hashes']['definitions.json'],pool='12 classifier +18 lexical reciprocal-rank fusion, up to32 intents + clarify; no gold injection',aliases=manifest['aliases'],rule_sha256=hashlib.sha256(__import__('inspect').getsource(needs_clarification).encode()).hexdigest(),routing_code_sha256=sha(Path(__file__)),selected_on='calibration only',calibration_metrics=selected)
    path=root/'routing-policy.json'
    if path.exists() and json.loads(path.read_text(encoding='utf-8'))!=policy:raise ValueError('Routing policy changed')
    save(path,policy);save(root/'calibration-search.json',grid)
    print('CALIBRATED',json.dumps(selected,ensure_ascii=False),flush=True)


async def blind(root):
    manifest=verify(root);selection=json.loads((root/'selection.json').read_text(encoding='utf-8'));policy=json.loads((root/'routing-policy.json').read_text(encoding='utf-8'))
    selected=Path(selection['model_path'])
    if sha(Path(__file__))!=policy['routing_code_sha256']:raise ValueError('Routing implementation changed after calibration')
    if sha(selected/'model.safetensors')!=policy['model_sha256']:raise ValueError('Selected weights changed')
    stamp=dict(weights=policy['model_sha256'],test=sha(root/'test.jsonl'),routing=sha(root/'routing-policy.json'),selection=sha(root/'selection.json'))
    marker=root/'blind-opened.json'
    if marker.exists():
        if json.loads(marker.read_text(encoding='utf-8'))!=stamp:raise ValueError('Blind run changed; no refit or replay under new policy')
    else:save(marker,stamp)
    rows=read(root/'test.jsonl')
    local_path=root/'blind-local.jsonl'
    if local_path.exists():pred=read(local_path);mapping=json.loads((root/'model-mapping.json').read_text(encoding='utf-8'))
    else:
        pred,mapping=predict(selected,rows);write(local_path,pred)
    pool=CandidatePool(json.loads((root/'definitions.json').read_text(encoding='utf-8')),mapping,manifest['aliases'])
    uncertain=[r for r in pred if decision(r,mapping,policy)[1]=='abstain']
    cloud=await mini_many(root,'blind',uncertain,pool)
    if policy.get('critic_enabled'):
        from factory_accuracy_critic import review_disagreements
        cloud=await review_disagreements(root,'blind',uncertain,cloud,pool)
    paired=[]
    for row in pred:
        local,local_route=decision(row,mapping,policy)
        actual,route=decision(row,mapping,policy,cloud.get(row['record_id']))
        paired.append(dict(row,raw_prediction=row['prediction'],prediction=actual,local_pipeline=local,route=route,mini=cloud.get(row['record_id'])))
    write(root/'blind-paired.jsonl',paired)
    raw=scores(pred);local_pipeline=scores([dict(r,prediction=r['local_pipeline']) for r in paired]);hybrid=scores(paired)
    summary=dict(n=len(rows),raw266=raw,raw_route_equivalent=scores(pred,manifest['aliases']),local_pipeline=local_pipeline,hybrid=hybrid,mini_calls=len(uncertain),mini_review_calls=sum('review' in value for value in cloud.values()),targets_met=raw['accuracy']>=.80 and raw['macro_f1']>=.80 and hybrid['accuracy']>=.95,
        positive_raw=scores([r for r in pred if r['label']!='__CLARIFY__']),clarification=dict(n=sum(r['label']=='__CLARIFY__' for r in paired),hybrid_correct=sum(r['label']=='__CLARIFY__' and r['prediction']=='__CLARIFY__' for r in paired)),
        candidate_misses=[r['record_id'] for r in paired if r['mini'] and r['label'] not in r['mini']['candidates']],model_sha256=policy['model_sha256'],routing_sha256=sha(root/'routing-policy.json'),primary_test_openings=1)
    short_ids={r['record_id'] for r in rows if r['record_id'].startswith('short-test:')}
    short=[r for r in pred if r['record_id'] in short_ids]
    long=[r for r in pred if r['label']!='__CLARIFY__' and r['record_id'] not in short_ids]
    summary['short_query_raw']=scores(short) if short else None
    summary['long_query_raw']=scores(long) if long else None
    summary['generalization_checks']=dict(training_validation_gap=selection['training_validation_gap'],validation_to_positive_test_gap=selection['validation']['accuracy']-summary['positive_raw']['accuracy'],no_test_based_fit=True,short_query_accuracy=summary['short_query_raw']['accuracy'] if short else None)
    summary['generalization_checks']['passed']=selection['training_validation_gap']<=.10 and summary['generalization_checks']['validation_to_positive_test_gap']<=.10 and (not short or summary['short_query_raw']['accuracy']>=.80)
    summary['numeric_targets_met']=summary['targets_met']
    summary['targets_met']=summary['targets_met'] and summary['generalization_checks']['passed']
    save(root/'blind-summary.json',summary)
    for arm,path in [('old259',OLD/'baseline-model'),('previous266',BASELINE)]:
        old,_=predict(path,rows);write(root/('blind-'+arm+'.jsonl'),old);summary[arm]=scores(old)
    save(root/'blind-summary.json',summary)
    print('BLIND_RESULT',json.dumps(summary,ensure_ascii=False),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('phase',choices=['calibrate','blind']);parser.add_argument('--evidence',type=Path,required=True);args=parser.parse_args();asyncio.run(globals()[args.phase](args.evidence))
