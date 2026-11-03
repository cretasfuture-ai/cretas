"""Bounded official read-only rechecks. Preserve snapshot; never repair source data."""
import json
import argparse
import uuid
from pathlib import Path
from datetime import datetime,timezone
from collections import Counter
from .full_probe import context
from .full_archive import Archive,digest
from .secrets import load_local_secret,save_local_secret
from .contracts import BlacklakeError,ENDPOINTS
from .gap_evidence import campaign_complete


def negative_control(client,report):
    body={'projectCode':'CRETAS-NONEXISTENT-'+uuid.uuid4().hex[:10]}
    control={'tenant_id':report['tenant_id'],'source_factory_id':report['source_factory_id'],'run_id':report['run_id'],
             'snapshot_fingerprint':report['snapshot_fingerprint'],'resource':'materials','endpoint':ENDPOINTS['materials'],'body':body,
             'checked_at':datetime.now(timezone.utc).isoformat()}
    control['query_digest']=digest(control)
    try:
        response=client.query('materials',body)
        control.update(mode='response',empty=response.get('data')==[])
    except BlacklakeError as exc:
        error=client.last_business_error or {}
        control.update(mode='source_rejected',code=error.get('code'),msg=error.get('msg'),safe_error=str(exc))
    return control


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--control-only',action='store_true',help='Recheck negative control on a matching retained live report')
    args=parser.parse_args()
    directory,client,store,cache=context()
    report={'started_at':datetime.now(timezone.utc).isoformat(),'source_business_mutations':0}
    try:
        run=load_local_secret(directory/'full-run.dpapi')
        profile=json.loads((Path(__file__).parent/'full_contract.json').read_text())
        archive=Archive(client,store,profile,run['run_id'])
        projects=archive.raw_rows('project')
        with store.transaction() as cur:
            cur.execute('SELECT resource,external_id,content_hash,normalized FROM blacklake.full_snapshot WHERE run_id=%s ORDER BY resource,external_id',(run['run_id'],))
            records=cur.fetchall()
        material_hashes={}
        for resource,external_id,content_hash,normalized in records:
            if resource=='materials':material_hashes.setdefault(str(normalized['request_context'][0]),set()).add(content_hash)
        report.update(run_id=run['run_id'],tenant_id=store.scope[0],source_factory_id=store.scope[1],
                      snapshot_fingerprint=digest([(r,i,h) for r,i,h,_ in records]),expected_projects=len(projects))
        if args.control_only:
            saved=load_local_secret(directory/'gap-live.dpapi')
            if any(saved.get(k)!=report[k] for k in ('run_id','tenant_id','source_factory_id','snapshot_fingerprint')):
                raise BlacklakeError('GAP_CONTROL_SCOPE_MISMATCH')
            saved.setdefault('fresh_stock_checked_at',saved['finished_at'])
            saved['expected_projects']=len(projects)
            with client.coordination.lease():saved['negative_control']=negative_control(client,saved)
            saved['finished_at']=datetime.now(timezone.utc).isoformat()
            save_local_secret(directory/'gap-live.dpapi',saved)
            print(json.dumps({'control_rechecked':True,'business_code':saved['negative_control'].get('code'),'source_business_mutations':0}))
            return 0 if campaign_complete(saved) else 1
        details=[];failures=[]
        with client.coordination.lease():
            report['negative_control']=negative_control(client,report)
            for index,parent in enumerate(projects):
                try:
                    by_id,_=archive.fetch('materials',{'projectId':parent['id']})
                    by_code,_=archive.fetch('materials',{'projectCode':parent['projectCode']})
                    ids={digest(r) for r in by_id};codes={digest(r) for r in by_code}
                    verified=ids==codes and len(ids)==len(by_id)==len(by_code)
                    details.append({'project_id':parent['id'],'project_code':parent['projectCode'],
                                    'dual_query_equal':verified,'snapshot_equal':ids==material_hashes.get(str(parent['id']),set()),
                                    'rows':by_id,'code_rows':by_code})
                except BlacklakeError as exc:
                    failures.append({'project_id':parent['id'],'error':str(exc)})
                if (index+1)%25==0:print(json.dumps({'phase':'materials_parent_controls','projects_checked':index+1}),flush=True)
            fresh_orders=[]
            for direction in (10,20):
                rows,_=archive.fetch('stock_order',{'orderType':direction})
                if any(row.get('orderType')!=direction for row in rows):raise BlacklakeError('FULL_PARENT_SCOPE_MISMATCH')
                fresh_orders.extend(rows)
            fresh_products,_=archive.fetch('product',{})
        report.update(finished_at=datetime.now(timezone.utc).isoformat(),materials=details,
                      failures=failures,fresh_stock_orders=fresh_orders,fresh_products=fresh_products)
        report['summary']={'projects_checked':len(details),'dual_query_verified_projects':sum(r['dual_query_equal'] for r in details),
                           'snapshot_equal_projects':sum(r['snapshot_equal'] for r in details),
                           'material_rows':sum(len(r['rows']) for r in details),'query_failures':len(failures),
                           'fresh_stock_orders':len(fresh_orders),'fresh_products':len(fresh_products),
                           'negative_control':report['negative_control']}
        save_local_secret(directory/'gap-live.dpapi',report)
        print(json.dumps(report['summary'],ensure_ascii=False),flush=True)
        return 0 if campaign_complete(report) else 1
    finally:
        client.close();store.connection.close();cache.close()


if __name__=='__main__':raise SystemExit(main())
