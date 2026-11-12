"""Bounded, read-only reporting-unit and balance supplement; no source writes."""
import json
from datetime import datetime, timezone
from pathlib import Path
import uuid

from .contracts import BlacklakeError
from .full_archive import Archive, digest
from .full_probe import context
from .lineage_probe import OUTPUT_FIELDS, save
from .secrets import save_local_secret


def probe(parent_path, destination, max_requests=200):
    parent=json.loads(Path(parent_path).read_text(encoding='utf8'))
    destination=Path(destination)
    if not destination.is_absolute() or destination.exists():raise ValueError('NEW_ABSOLUTE_PROBE_DIRECTORY_REQUIRED')
    destination.mkdir(parents=True)
    directory,client,store,cache=context()
    report={'schema':'blacklake-operations-supplement/1','id':uuid.uuid4().hex,
            'tenant_id':parent['tenant_id'],'source_factory_id':str(parent['source_factory_id']),
            'baselineRunId':parent['run_id'],'parentObservationId':parent['probeId'],
            'parentOutputFingerprint':digest(parent['data']['output']),
            'startedAt':datetime.now(timezone.utc).isoformat(),'requests':0,'sourceBusinessWrites':0,
            'outputs':[],'stock':[],'products':[],'warehouses':[],'failures':[]}
    original=client.query
    def counted(resource,body):
        if report['requests']>=max_requests:raise BlacklakeError('OPERATIONS_QUERY_BOUND_REACHED')
        report['requests']+=1
        return original(resource,body)
    client.query=counted
    try:
        if tuple(store.scope)!=(report['tenant_id'],report['source_factory_id']):raise BlacklakeError('OPERATIONS_SOURCE_SCOPE_MISMATCH')
        archive=Archive(client,store,json.loads((Path(__file__).parent/'full_contract.json').read_text()),parent['run_id'])
        with client.coordination.lease():
            ids=[p['id'] for p in parent['data']['project']]
            for offset in range(0,len(ids),50):
                selected=ids[offset:offset+50]
                rows,_=archive.fetch('output',{'projectIds':selected})
                if any(str(r.get('projectId')) not in {str(i) for i in selected} for r in rows):raise BlacklakeError('OPERATIONS_OUTPUT_SCOPE_MISMATCH')
                # The wire unit and name are safe business facts, not personnel.
                report['outputs'].extend({k:v for k,v in r.items() if k in OUTPUT_FIELDS} for r in rows)
            for resource,key in [('product','products'),('warehouse','warehouses')]:
                rows,_=archive.fetch(resource,{})
                if resource=='product':report[key]=[{k:r.get(k) for k in ('id','productCode','productName')} for r in rows]
                else:report[key]=[{k:r['simpleVO'].get(k) for k in ('id','code','name','status')} for r in rows]
            pairs=[{'productId':p['id'],'warehouseId':w['id']} for p in report['products'] for w in report['warehouses']]
            if any(p['productId'] is None or p['warehouseId'] is None for p in pairs):raise BlacklakeError('OPERATIONS_DEFINITION_ID_MISSING')
            if len({(str(p['productId']),str(p['warehouseId'])) for p in pairs})!=len(pairs):raise BlacklakeError('OPERATIONS_DEFINITION_DUPLICATE')
            seen=set()
            for offset in range(0,len(pairs),100):
                batch=pairs[offset:offset+100];allowed={(str(p['productId']),str(p['warehouseId'])) for p in batch}
                rows,_=archive.fetch('stock',{'stockInfoQryOpenApiCOList':batch})
                for row in rows:
                    key=(str(row.get('productId')),str(row.get('warehouseId')))
                    if key not in allowed or key in seen:raise BlacklakeError('OPERATIONS_STOCK_SCOPE_OR_DUPLICATE')
                    seen.add(key)
                    report['stock'].append({k:v for k,v in row.items() if k in ('productId','productCode','productName','warehouseId','warehouseCode','warehouseName','unitName','qtyInWarehouse')})
                save(destination/'checkpoint-private.json',report)
            report['stockPairsQueried']=len(pairs)
            report['stockPairsNotReturned']=len(pairs)-len(seen)
            report['factoryVerified']=client.factory_verified
        report['finishedAt']=datetime.now(timezone.utc).isoformat()
        report['complete']=True
        report['fingerprint']=digest({k:v for k,v in report.items() if k!='fingerprint'})
        save(destination/'supplement-private.json',report)
        receipt={k:report[k] for k in ('id','startedAt','finishedAt','requests','sourceBusinessWrites','factoryVerified','complete','stockPairsQueried','stockPairsNotReturned','fingerprint')}
        receipt.update(outputRecords=len(report['outputs']),outputsWithUnit=sum(bool(r.get('stockUnit')) for r in report['outputs']),stockRecords=len(report['stock']))
        save(destination/'receipt.json',receipt)
        print(json.dumps(receipt),flush=True)
        return 0
    except Exception as error:
        report['complete']=False;report['failures'].append(str(error) if isinstance(error,BlacklakeError) else type(error).__name__)
        save(destination/'failed-private.json',report)
        print(json.dumps({'complete':False,'errors':report['failures'],'requests':report['requests']}),flush=True)
        return 1
    finally:client.close();store.connection.close();cache.close()


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('--parent',required=True);parser.add_argument('--out',required=True)
    args=parser.parse_args();raise SystemExit(probe(args.parent,args.out))
