"""All turns remain in denominator; carrier errors, routing and actual results separate."""
import argparse,collections,hashlib,json,math,pathlib,statistics
p=argparse.ArgumentParser();p.add_argument('--previous-evidence',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--corpus',type=pathlib.Path);a=p.parse_args()
def load(path):return json.loads(path.read_text('utf-8-sig'))
plans=load(a.corpus or a.previous_evidence/'exploratory-cases-locked.json')['dialogues'];turn_count=sum(len(x['turns']) for x in plans)
trace=load(a.output/'complete-read-trace.private.json')['trace'];assert len(trace)==2*turn_count
before=load(a.output/'oracle-before.private.json');after=load(a.output/'oracle-after.private.json')
catalog={r['intentCode']:r for r in load(a.output/'actual-catalog.private.json')}
wired=set(load(a.output/'complete-read-trace.private.json.wiring.json'))
rows=[]
for channel in ['normal','sse']:
 for plan in plans:
  for i,gold in enumerate(plan['turns'],1):
   row=next(x for x in trace if x['case_id']==plan['id'] and x['turn']==i and x['channel']==channel)
   response=row.get('result') or {};data=response.get('resultData') or {};expected=gold['parameters'];domain=gold.get('domain',plan['domain'])
   expiry_scope=gold.get('basis')=='EXPIRY_DATE'
   actual_expiry=(data.get('queryConditions') or {}).get('basis')=='EXPIRY_DATE'
   values=data.get('batches' if actual_expiry else 'orders' if domain=='orders' else 'content');values=values if isinstance(values,list) else None
   ids=[r.get('id') for r in values] if values is not None else []
   total=data.get('total' if domain=='orders' or actual_expiry else 'totalElements');page=data.get('page' if domain=='orders' or actual_expiry else 'currentPage');size=data.get('size')
   status=(data.get('appliedStatusFilter') or {}).get('code') or (data.get('queryConditions') or {}).get('status')
   requested=expected.get('status')
   if expiry_scope:
    boundary=before['observed'][0]['at'][:10]
    predicate=lambda r:r.get('expireDate') is not None and r['expireDate']<boundary and r['status']!='EXPIRED'
    pre=sorted(filter(predicate,before[domain]),key=lambda r:(r['expireDate'],str(r['id'])))
    post=sorted(filter(predicate,after[domain]),key=lambda r:(r['expireDate'],str(r['id'])))
   else:
    pre=[r for r in before[domain] if r['status']==requested];post=[r for r in after[domain] if r['status']==requested]
   drift={(r['id'],r['status'],r['createdAt']) for r in pre}!={(r['id'],r['status'],r['createdAt']) for r in post}
   lookup={r['id']:r for r in pre};target=pre[(expected['page']-1)*expected['size']:expected['page']*expected['size']]
   allowed={r['id'] for r in target} if expiry_scope else {r['id'] for r in pre if target[-1]['createdAt']<=r['createdAt']<=target[0]['createdAt']} if target else set()
   exp_count=max(0,min(expected['size'],len(pre)-(expected['page']-1)*expected['size']))
   data_ok=actual_expiry==expiry_scope and values is not None and len(ids)==len(set(ids)) and all(v in lookup for v in ids) and all(predicate(r) if expiry_scope else r.get('status')==requested for r in values) and set(ids)<=allowed and len(ids)==exp_count and total==len(pre)
   goal_ok=response.get('intentCode') in ({'MATERIAL_EXPIRED_QUERY'} if expiry_scope else {'ORDER_LIST','ORDER_STATUS'} if domain=='orders' else {'MATERIAL_BATCH_QUERY'})
   params_ok=(dict(page=page,size=size)==expected and (data.get('queryConditions') or {}).get('basis')=='EXPIRY_DATE') if expiry_scope else dict(page=page,size=size,status=status)==expected
   selected=response.get('intentCode');tool=(catalog.get(selected) or {}).get('toolName')
   carrier_unknown=response.get('status')=='FAILED' and tool is not None and tool not in wired
   success=bool(response.get('status')=='SUCCESS' and goal_ok and params_ok and data_ok and not drift)
   category='PASS' if success else 'UNKNOWN_DATA_DRIFT' if drift else 'UNKNOWN_CARRIER_OUTSIDE_REGISTERED_SCOPE' if carrier_unknown else 'CLARIFICATION' if response.get('status') in ['NEED_MORE_INFO','NEED_CLARIFICATION'] else 'PERMISSION_DENIED' if response.get('status') in ['DENIED','NO_PERMISSION'] else 'GOAL_OR_CAPABILITY' if not goal_ok else 'PARAMETERS' if not params_ok else 'BUSINESS_RESULT_OR_EXECUTION'
   rows.append(dict(case_id=plan['id'],turn=i,channel=channel,domain=domain,basis=gold.get('basis','STATUS'),input=gold['input'],expected=expected,
       status=response.get('status'),intent=selected,tool=tool,goal_correct=goal_ok,parameters_correct=params_ok,
       real_result_correct=data_ok,task_success=success,carrier_unknown=carrier_unknown,category=category,
       observed=dict(status=status,page=page,size=size,total=total,rows=len(ids)),oracle_count=len(pre),data_drift=drift,
       parameter_sources=row.get('sources'),recognition=row.get('recognition'),latency_ms=row.get('latency_ms'),
       returned_id_hashes=[hashlib.sha256(str(v).encode()).hexdigest()[:16] for v in ids]))
for r in rows:
 previous=next((v for v in rows if v['case_id']==r['case_id'] and v['channel']==r['channel'] and v['turn']==r['turn']-1),None)
 if (previous and r['domain']==previous['domain'] and r['basis']==previous['basis'] and r['expected'].get('status')==previous['expected'].get('status')
     and r['expected']['size']==previous['expected']['size'] and r['expected']['page']==previous['expected']['page']+1):
  overlap=set(r['returned_id_hashes'])&set(previous['returned_id_hashes'])
  r['cross_page_duplicate_count']=len(overlap)
  if overlap:r.update(real_result_correct=False,task_success=False,category='BUSINESS_PAGE_OVERLAP')
summaries={}
for ch in ['normal','sse']:
 selected=[r for r in rows if r['channel']==ch];lat=sorted(r['latency_ms'] for r in selected)
 tasks=[dict(id=plan['id'],passed=all(r['task_success'] for r in selected if r['case_id']==plan['id'])) for plan in plans]
 summaries[ch]=dict(tasks_passed=sum(t['passed'] for t in tasks),tasks_total=len(plans),turns_passed=sum(r['task_success'] for r in selected),turns_total=turn_count,
    goal_correct=sum(r['goal_correct'] for r in selected),parameters_correct=sum(r['parameters_correct'] for r in selected),real_result_correct=sum(r['real_result_correct'] for r in selected),
    carrier_unknown=sum(r['carrier_unknown'] for r in selected),categories=collections.Counter(r['category'] for r in selected),p50_ms=statistics.median(lat),p95_ms=lat[math.ceil(.95*len(lat))-1],tasks=tasks)
parity=[]
for plan in plans:
 for i in range(1,len(plan['turns'])+1):
  left=next(r for r in rows if r['case_id']==plan['id'] and r['turn']==i and r['channel']=='normal')
  right=next(r for r in rows if r['case_id']==plan['id'] and r['turn']==i and r['channel']=='sse')
  parity.append(dict(case_id=plan['id'],turn=i,same_semantic_outcome=all(left[k]==right[k] for k in ['status','intent','observed']),both_succeeded=left['task_success'] and right['task_success']))
result=dict(kind='ALREADY_OPENED_DEVELOPMENT_REGRESSION_ACTUAL_BUSINESS_READS_NOT_INDEPENDENT',channels=summaries,
    same_dialogue_channel_pairs=len(parity),same_semantic_outcomes=sum(r['same_semantic_outcome'] for r in parity),both_channels_successful=sum(r['both_succeeded'] for r in parity),
    denominator_policy='All turns and channel-conversations retained. Unknown carrier and clarification are not success. Model inference is stochastic; channel differences alone do not prove an entry implementation defect.',
    model_only_failure_claim=False,independent_gold=False,local_classifier_live=False,training=False,deploy=False,turns=rows,parity=parity)
(a.output/'complete-read-score.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:({ch:{m:v for m,v in s.items() if m!='tasks'} for ch,s in value.items()} if k=='channels' else value) for k,value in result.items() if k not in ['turns','parity']},ensure_ascii=False))
