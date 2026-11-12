"""Classify the fixed original blocked cohort and recheck official read-only facts."""
import json
import uuid
from collections import Counter
from datetime import datetime,timezone
from pathlib import Path

from .analytics import build,number
from .full_archive import Archive,digest
from .full_verify import validate_retained_run
from .full_probe import context
from .contracts import BlacklakeError
from .secrets import load_local_secret,save_local_secret

CATEGORIES=('未完工，暂不核算成品单位成本','已完工但缺正数成品良品量','已完工有成品但无关联领退料',
            '已完工材料金额全部为零','已完工部分领料零价或缺价','其他字段或估值异常')


def validate_current(data):
    for rows in data.values():
        ids=[str(r.get('id')) for r in rows]
        if any(r.get('id') is None for r in rows) or len(ids)!=len(set(ids)):
            raise BlacklakeError('CURRENT_ID_MISSING_OR_DUPLICATE')
    codes=[p.get('projectCode') for p in data['project']]
    if any(not c for c in codes) or len(codes)!=len(set(codes)):
        raise BlacklakeError('CURRENT_PROJECT_CODE_AMBIGUOUS')


def category(row):
    if row['unitCostAvailable']:return '已具备可识别单位直接成本条件'
    if row['status']!='20':return CATEGORIES[0]
    if number(row['finishedGood']) is None or number(row['finishedGood'])<=0:return CATEGORIES[1]
    if not row['materialLines']:return CATEGORIES[2]
    if '存在耗料但材料金额全部为源端零值' in row['issues']:return CATEGORIES[3]
    if '部分领料金额或单价为零/缺失，估值未核实' in row['issues']:return CATEGORIES[4]
    return CATEGORIES[5]


def snapshot(store,directory,client):
    run=load_local_secret(directory/'full-run.dpapi')
    metadata=load_local_secret(directory/'full-report.dpapi')
    if run['source']!=digest(store.scope):raise BlacklakeError('BLOCKED_AUDIT_SOURCE_MISMATCH')
    archive=Archive(client,store,json.loads((Path(__file__).parent/'full_contract.json').read_text()),run['run_id'])
    validate_retained_run(run,metadata,archive.audit())
    data={r:archive.raw_rows(r) for r in ('project','task','output','stock_order','product','process','stock')}
    analysis=build(data,metadata)
    blocked=[row for row in analysis['cost']['projects'] if not row['unitCostAvailable']]
    if len(blocked)!=93:raise BlacklakeError('ORIGINAL_BLOCKED_COHORT_CHANGED')
    return run,archive,data,analysis,blocked


def probe():
    directory,client,store,cache=context()
    report={'probe_id':str(uuid.uuid4()),'started_at':datetime.now(timezone.utc).isoformat()}
    try:
        run,archive,baseline,analysis,blocked=snapshot(store,directory,client)
        original_ids={str(p['id']) for p in baseline['project']}
        report.update(tenant_id=store.scope[0],source_factory_id=store.scope[1],baseline_run_id=run['run_id'],
                      baseline_analysis=analysis,blocked_ids=[str(r['projectId']) for r in blocked])
        data={};requests=Counter()
        original_query=client.query
        def counted(resource,body):
            requests[resource]+=1
            return original_query(resource,body)
        client.query=counted
        with client.coordination.lease():
            projects=[]
            for status in ('0','10','20','30'):
                rows,_=archive.fetch('project',{'status':status})
                if any(str(r.get('status'))!=status for r in rows):raise BlacklakeError('PROJECT_STATUS_FILTER_NOT_APPLIED')
                projects.extend(rows)
            if len({str(p['id']) for p in projects})!=len(projects):raise BlacklakeError('CURRENT_PROJECT_DUPLICATE')
            report['current_project_identities']=[{'id':p['id'],'projectCode':p['projectCode']} for p in projects]
            validate_current({'project':report['current_project_identities']})
            data['project']=[r for r in projects if str(r['id']) in original_ids]
            report['new_projects_outside_original_cohort']=len(projects)-len(data['project'])
            report['original_projects_not_returned']=sorted(original_ids-{str(p['id']) for p in data['project']})
            data['task']=[];data['output']=[]
            ids=[p['id'] for p in data['project']]
            for offset in range(0,len(ids),50):
                batch=ids[offset:offset+50];allowed={str(i) for i in batch}
                for resource in ('task','output'):
                    rows,_=archive.fetch(resource,{'projectIds':batch})
                    if any(str(r.get('projectId')) not in allowed for r in rows):raise BlacklakeError('CURRENT_PARENT_FILTER_NOT_APPLIED')
                    if resource=='task' and any(str(r.get('orgId'))!=store.scope[1] for r in rows):raise BlacklakeError('SOURCE_FACTORY_MISMATCH')
                    data[resource].extend(rows)
            data['stock_order']=[]
            for direction in (10,20):
                rows,_=archive.fetch('stock_order',{'orderType':direction})
                if any(r.get('orderType')!=direction for r in rows):raise BlacklakeError('CURRENT_STOCK_FILTER_NOT_APPLIED')
                data['stock_order'].extend(rows)
            for resource in ('product','process'):
                data[resource],_=archive.fetch(resource,{})
        validate_current(data)
        report.update(finished_at=datetime.now(timezone.utc).isoformat(),data=data,source_query_counts=dict(requests),
                      source_business_mutations=0,baseline_mutated=False)
        current=build(data,{'run_id':report['probe_id'],'finished_at':report['finished_at'],'stock_pairs_not_returned':None})
        report['current_analysis']=current
        current_by_id={str(r['projectId']):r for r in current['cost']['projects']}
        now=[current_by_id[i] for i in report['blocked_ids'] if i in current_by_id]
        report['summary']={'original_blocked':len(blocked),'baseline_categories':dict(Counter(category(r) for r in blocked)),
                           'current_categories_for_original_93':dict(Counter(category(r) for r in now)),
                           'not_returned':sum(i not in current_by_id for i in report['blocked_ids']),
                           'newly_eligible_originally_blocked':sum(r['unitCostAvailable'] for r in now),
                           'source_queries':sum(requests.values()),'source_business_mutations':0,
                           'scope_counts':{r:len(rows) for r,rows in data.items()}}
        save_local_secret(directory/'blocked93-probe.dpapi',report)
        print(json.dumps(report['summary'],ensure_ascii=False))
        return 0
    except Exception as exc:
        save_local_secret(directory/'blocked93-error.dpapi',{'type':type(exc).__name__,'safe_code':str(exc) if isinstance(exc,BlacklakeError) else 'AUDIT_RUNTIME_ERROR','upstream':client.last_business_error})
        print(json.dumps({'success':False,'error':str(exc) if isinstance(exc,BlacklakeError) else 'AUDIT_RUNTIME_ERROR'}))
        return 1
    finally:
        client.close();store.connection.close();cache.close()


if __name__=='__main__':
    raise SystemExit(probe())
