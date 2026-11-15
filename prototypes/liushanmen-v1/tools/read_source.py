"""Read the existing Blacklake collector's committed snapshot without changing it."""
import json,sys,sqlite3,os
from pathlib import Path
SOURCE=Path('C:/Users/Steve/cretas-blacklake-20261103')
sys.path.insert(0,str(SOURCE/'backend/python'))
from blacklake.live_monitor import config,binding
from blacklake.full_archive import digest
from blacklake.analytics import build

ROOT=Path(__file__).resolve().parents[1]

FIELDS={
 'project':'id projectCode status productCode productName productSpecification planAmount projectUnit planStartTime planEndTime actualStartTime actualEndTime createTime updateTime actualAmount numberofGoodProducts numberofDefectives',
 'task':'id orgId projectId processId processName productCode processStatus planAmount fineAmount defectsAmount approvedFineAmount outputDurationForSecond sumEffectiveTime sumOutputSalary updatedAt',
 'output':'id projectId processId productCode productName numberofgoodProducts stockUnit numberofDefectives approvalStatus starttimeofReport endtimeofReport updatedAt taskId effectiveTime',
 'product':'id productCode productName productSpecification unit originType prcessRoutingCode createdAt updatedAt',
 'process':'id code name outputRate processingType processingTypeDesc createdAt updatedAt',
 'routing':'processRoutingCode processRoutingName processRoutingSteps createdAt updatedAt',
 'bom':'id lastProductCode nextProductCode feedProcessCode unitQty parentBaseQty lossRate createdAt updatedAt',
 'materials':'id seq productCode unitQty feedProcessCode feedProcessId createdAt updatedAt',
 'unit':'id name decimalLength roundingMode',
}

def export():
    v,p=read()
    raw=p['data']
    if any(str(r.get('orgId'))!=str(v['source_factory_id']) for r in raw.get('task',[])):
        raise ValueError('TASK_SOURCE_FACTORY_MISMATCH')
    private=ROOT/'private';private.mkdir(exist_ok=True)
    binding_file=private/'binding.json'
    binding_value={'prototypeFactoryId':'LIUSHANMEN','sourceTenantId':v['tenant_id'],'sourceFactoryId':str(v['source_factory_id']),'baselineRunId':v['baselineRunId']}
    if binding_file.exists() and json.loads(binding_file.read_text('utf8'))!=binding_value:
        raise ValueError('FACTORY_BINDING_CHANGED')
    metadata={'run_id':'live:'+p['contentHash'][:32],'finished_at':p['checkedAt'],'stock_pairs_not_returned':p.get('pairsNotReturned',[])}
    analysis=build(raw,metadata)
    cost_fields='projectId projectCode product productCode unit status planned actual finishedGood materialCost laborCost identifiedDirectCost unitIdentifiedDirectCost materialLines materialCostKnownLines sourceZeroCostLines taskCount laborCostKnownTasks materialLinesDetail processes issues unitCostAvailable'.split()
    cost=[]
    for row in analysis['cost']['projects']:
        safe={k:row.get(k) for k in cost_fields}
        safe['processes']=[{k:x.get(k) for k in ['process','status','good','hours','laborCost','taskId']} for x in safe['processes']]
        cost.append(safe)
    resources={key:[{f:r.get(f) for f in fields.split()} for r in raw.get(key,[])] for key,fields in FIELDS.items()}
    def route_steps(items):
        return [{**{k:s.get(k) for k in ['id','seq','type','name','code','outputRate']},'processRoutingSteps':route_steps(s.get('processRoutingSteps') or [])} for s in (items or [])]
    for routing in resources['routing']:
        routing['processRoutingSteps']=route_steps(routing.get('processRoutingSteps'))
    # Current references are source product codes and IDs. No personnel, customer, login or credential payloads.
    result={'factoryId':'LIUSHANMEN','factoryName':'六膳门','sourceSystem':'Blacklake OpenAPI','sourceFactoryId':str(v['source_factory_id']),
      'revision':p['contentHash'],'runId':metadata['run_id'],'cycleId':p['cycleId'],'checkedAt':p['checkedAt'],'referenceAt':p.get('referenceAt'),'materialAt':p.get('materialAt'),
      'resources':resources,'costs':cost,'limitations':analysis['limitations'],'sourceGaps':analysis.get('sourceGaps',[]),
      'lineagePolicy':'只按来源产品编码、工单ID、任务ID、报工ID与工序ID关联。BOM为标准参考，不代替工单实际领退料。',
      'counts':{k:len(rs) for k,rs in resources.items()}}
    snapshots=private/'snapshots';snapshots.mkdir(exist_ok=True)
    target=snapshots/(p['contentHash']+'.json')
    if not target.exists():target.write_text(json.dumps(result,ensure_ascii=False),encoding='utf8')
    if not binding_file.exists():binding_file.write_text(json.dumps(binding_value),encoding='utf8')
    temp=private/'source-current.tmp';temp.write_text(json.dumps(result,ensure_ascii=False),encoding='utf8');os.replace(temp,private/'source-current.json')
    return {'success':True,'factoryId':'LIUSHANMEN','revision':p['contentHash'],'checkedAt':p['checkedAt'],'counts':result['counts'],'eligibleCost':sum(x['unitCostAvailable'] for x in cost)}

def read():
    v=config('D:/CretasSmartBILab/continuous-20261105/config.json')
    with sqlite3.connect(Path(v['db']).resolve().as_uri()+'?mode=ro',uri=True) as c:
        c.row_factory=sqlite3.Row
        c.execute('PRAGMA query_only=ON')
        m=c.execute('select * from monitor where id=1').fetchone()
        if not m or m['scope']!=binding(v):raise ValueError('SOURCE_FACTORY_BINDING_MISMATCH')
        row=c.execute("select * from cycle where status='success' order by finished desc limit 1").fetchone()
        if not row:raise ValueError('NO_SUCCESSFUL_SOURCE_SNAPSHOT')
        p=json.loads(c.execute('select payload from content where hash=?',(row['hash'],)).fetchone()[0])
        if digest(p)!=row['hash']:raise ValueError('SOURCE_SNAPSHOT_HASH_MISMATCH')
        return v,{**p,'checkedAt':row['finished'],'contentHash':row['hash'],'cycleId':row['id']}

if __name__=='__main__':
    if '--export' in sys.argv:
        print(json.dumps(export(),ensure_ascii=False));sys.exit(0)
    v,p=read()
    print(json.dumps({'verified':True,'checkedAt':p['checkedAt'],'keys':list(p),'resources':{k:{'count':len(x),'firstFields':list(x[0])[:45] if x and isinstance(x[0],dict) else []} for k,x in p['data'].items() if isinstance(x,list)}},ensure_ascii=False))
