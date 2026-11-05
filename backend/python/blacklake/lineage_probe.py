"""Bounded read-only recheck; never merge fresh observations into an old snapshot."""
import argparse
from datetime import datetime,timezone
import json
from pathlib import Path
import uuid

from .contracts import BlacklakeError
from .full_archive import Archive,digest
from .full_probe import context
from .gap_probe import negative_control
from .gap_evidence import negative_verified
from .secrets import load_local_secret

TASK_FIELDS={'id','orgId','projectId','processId','processName','productCode','planAmount','fineAmount','defectsAmount',
             'approvedFineAmount','outputDurationForSecond','sumOutputSalary','updatedAt','processStatus'}
OUTPUT_FIELDS={'id','projectId','taskId','processId','productCode','numberofgoodProducts','numberofDefectives',
               'starttimeofReport','endtimeofReport','approvalStatus','updatedAt','stockUnit','productName'}
PERSON_FIELDS={'applierId','applierName','approverId','approverName','creatorId','creatorName','updatorId','updatorName',
               'operatorId','operatorName','userId','userName','phone','mobile','password','token'}


def minimized(value):
    if isinstance(value,dict):return {k:minimized(v) for k,v in value.items() if k not in PERSON_FIELDS}
    if isinstance(value,list):return [minimized(v) for v in value]
    return value


def save(path,value):
    temporary=path.with_suffix(path.suffix+'.tmp')
    temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2,default=str),encoding='utf8')
    temporary.replace(path)


def probe(destination,max_requests=600):
    destination=Path(destination)
    if not destination.is_absolute() or destination.exists():raise ValueError('NEW_ABSOLUTE_PROBE_DIRECTORY_REQUIRED')
    destination.mkdir(parents=True)
    directory,client,store,cache=context()
    report={'probeId':uuid.uuid4().hex,'started_at':datetime.now(timezone.utc).isoformat(),
            'sourceBusinessWrites':0,'baselineMutated':False,'requests':0,'failures':[],'parentControls':[],'data':{}}
    original=client.query
    def counted(resource,body):
        if report['requests']>=max_requests:raise BlacklakeError('LINEAGE_QUERY_BOUND_REACHED')
        report['requests']+=1
        return original(resource,body)
    client.query=counted
    try:
        baseline=load_local_secret(directory/'full-run.dpapi')
        if baseline['source']!=digest(store.scope):raise BlacklakeError('LINEAGE_SOURCE_BINDING_MISMATCH')
        report.update(tenant_id=store.scope[0],source_factory_id=store.scope[1],run_id=baseline['run_id'])
        archive=Archive(client,store,json.loads((Path(__file__).parent/'full_contract.json').read_text()),baseline['run_id'])
        old=archive.raw_rows('project');ids={str(p['id']) for p in old}
        report['originalProjects']=[{'id':p['id'],'projectCode':p['projectCode']} for p in old]
        report['snapshot_fingerprint']=digest(old)
        def fetch(resource,body):
            try:return archive.fetch(resource,body)[0]
            except BlacklakeError as error:
                report['failures'].append({'resource':resource,'request':body,'code':str(error)})
                return None
        with client.coordination.lease():
            report['negative_control']=negative_control(client,report)
            projects=[]
            for status in ('0','10','20','30'):
                rows=fetch('project',{'status':status})
                if rows is not None:
                    if any(str(p.get('status'))!=status for p in rows):raise BlacklakeError('LINEAGE_STATUS_FILTER_REJECTED')
                    projects.extend(rows)
            if len({str(p['id']) for p in projects})!=len(projects):raise BlacklakeError('LINEAGE_PROJECT_DUPLICATE')
            report['newProjectsOutsideOriginalCohort']=sum(str(p['id']) not in ids for p in projects)
            projects=[p for p in projects if str(p['id']) in ids]
            report['data']['project']=projects
            report['originalProjectsNotReturned']=sorted(ids-{str(p['id']) for p in projects})
            for resource in ('task','output'):
                combined=[]
                for offset in range(0,len(projects),50):
                    parents=[p['id'] for p in projects[offset:offset+50]]
                    rows=fetch(resource,{'projectIds':parents})
                    if rows is None:continue
                    if any(str(r.get('projectId')) not in {str(p) for p in parents} for r in rows):
                        raise BlacklakeError('LINEAGE_PARENT_FILTER_REJECTED')
                    if resource=='task' and any(str(r.get('orgId'))!=store.scope[1] for r in rows):
                        raise BlacklakeError('SOURCE_FACTORY_MISMATCH')
                    allowed=TASK_FIELDS if resource=='task' else OUTPUT_FIELDS
                    combined.extend({k:v for k,v in r.items() if k in allowed} for r in rows)
                report['data'][resource]=combined
            orders=[]
            for direction in (10,20):
                rows=fetch('stock_order',{'orderType':direction})
                if rows is not None:
                    if any(r.get('orderType')!=direction for r in rows):raise BlacklakeError('LINEAGE_STOCK_DIRECTION_REJECTED')
                    orders.extend(rows)
            report['data']['stock_order']=orders
            for resource in ('product','process','routing','unit'):
                rows=fetch(resource,{})
                if rows is not None:report['data'][resource]=rows
            report['data']['bom']=[]
            for product in report['data'].get('product',[]):
                code=product['productCode'];rows=fetch('bom',{'lastProductCode':code})
                if rows is not None:
                    if any(r.get('lastProductCode')!=code for r in rows):raise BlacklakeError('LINEAGE_BOM_PARENT_REJECTED')
                    report['data']['bom'].extend(rows)
            report['data']['materials']=[]
            report['data']['project_expand']=[]
            for index,project in enumerate(projects):
                by_id=fetch('materials',{'projectId':project['id']})
                by_code=fetch('materials',{'projectCode':project['projectCode']})
                verified=(by_id is not None and by_code is not None and len({digest(r) for r in by_id})==len(by_id)
                          and len(by_id)==len(by_code) and {digest(r) for r in by_id}=={digest(r) for r in by_code})
                report['parentControls'].append({'projectId':project['id'],'projectCode':project['projectCode'],
                    'dualQueryEqual':verified,'materialIds':[r.get('id') for r in by_id or []]})
                if by_id is not None:report['data']['materials'].extend(by_id)
                expanded=fetch('project_expand',{'projectIds':[project['id']]})
                if expanded is not None:report['data']['project_expand'].extend(expanded)
                if (index+1)%20==0:
                    save(destination/'checkpoint-private.json',minimized(report))
                    print(json.dumps({'checkedProjects':index+1,'queries':report['requests']}),flush=True)
        report['finished_at']=datetime.now(timezone.utc).isoformat()
        report['negativeControlVerified']=negative_verified(report)
        report['complete']=not report['failures'] and not report['originalProjectsNotReturned'] and report['negativeControlVerified'] and all(p['dualQueryEqual'] for p in report['parentControls'])
        report=minimized(report)
        report['observationFingerprint']=digest(report['data'])
        save(destination/'observations-private.json',report)
        receipt={k:report[k] for k in ('probeId','started_at','finished_at','requests','sourceBusinessWrites','baselineMutated','complete','negativeControlVerified','originalProjectsNotReturned','newProjectsOutsideOriginalCohort')}
        receipt['resourceCounts']={r:len(rows) for r,rows in report['data'].items()}
        receipt['failures']=report['failures']
        save(destination/'receipt.json',receipt)
        print(json.dumps(receipt,ensure_ascii=False),flush=True)
        return 0 if report['complete'] else 1
    finally:
        client.close();store.connection.close();cache.close()


def probe_bom(previous,destination):
    """Retain the failed request; supplement only with verified parent filters."""
    previous=json.loads(Path(previous).read_text(encoding='utf8'))
    destination=Path(destination)
    if not destination.is_absolute() or destination.exists():raise ValueError('NEW_ABSOLUTE_PROBE_DIRECTORY_REQUIRED')
    destination.mkdir(parents=True)
    directory,client,store,cache=context()
    result={'startedAt':datetime.now(timezone.utc).isoformat(),'tenantId':store.scope[0],
            'sourceFactoryId':store.scope[1],'baselineRunId':previous['run_id'],
            'parentProbeId':previous['probeId'],'rows':[],'failures':[],'queries':0,'sourceBusinessWrites':0}
    try:
        if (previous['tenant_id'],previous['source_factory_id'])!=store.scope:raise BlacklakeError('LINEAGE_SUPPLEMENT_SCOPE_MISMATCH')
        archive=Archive(client,store,json.loads((Path(__file__).parent/'full_contract.json').read_text()),previous['run_id'])
        products=previous['data']['product']
        if len(products)+previous['requests']>600:raise BlacklakeError('LINEAGE_QUERY_BOUND_REACHED')
        with client.coordination.lease():
            for product in products:
                code=product['productCode'];result['queries']+=1
                try:
                    rows,_=archive.fetch('bom',{'lastProductCode':code})
                    if any(r.get('lastProductCode')!=code for r in rows):raise BlacklakeError('LINEAGE_BOM_PARENT_REJECTED')
                    result['rows'].extend(rows)
                except BlacklakeError as error:
                    result['failures'].append({'productCode':code,'code':str(error),
                        'sourceMessage':(client.last_business_error or {}).get('msg')})
        result['finishedAt']=datetime.now(timezone.utc).isoformat()
        result['complete']=not result['failures']
        save(destination/'bom-supplement-private.json',result)
        print(json.dumps({'queries':result['queries'],'rows':len(result['rows']),'failures':len(result['failures']),
            'complete':result['complete'],'sourceBusinessWrites':0}))
        return 0 if result['complete'] else 1
    finally:client.close();store.connection.close();cache.close()


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True);parser.add_argument('--bom-only-from')
    args=parser.parse_args()
    raise SystemExit(probe_bom(args.bom_only_from,args.output) if args.bom_only_from else probe(args.output))
