"""Independent readonly SQL snapshots; never supplies model inputs or expected answers."""
import argparse,datetime,hashlib,json,pathlib,sys
p=argparse.ArgumentParser();p.add_argument('--previous-evidence',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--phase',choices=['before','after'],required=True);a=p.parse_args()
sys.path.insert(0,str(a.previous_evidence));from readonly_remote import run
target=a.output/('oracle-'+a.phase+'.private.json')
if target.exists():raise RuntimeError('Never overwrite oracle evidence')
data=run(r'''
print(json.dumps({'orders':query("SELECT json_build_object('id',id,'status',status,'createdAt',created_at) FROM sales_orders WHERE factory_id='F006' AND deleted_at IS NULL ORDER BY created_at DESC;"),
'stock':query("SELECT json_build_object('id',id,'status',status,'createdAt',created_at,'expireDate',expire_date) FROM material_batches WHERE factory_id='F006' AND deleted_at IS NULL ORDER BY created_at DESC;"),
'observed':query("SELECT json_build_object('at',CURRENT_TIMESTAMP,'read_only',current_setting('transaction_read_only'));" )},ensure_ascii=False))
''')
target.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(dict(snapshot=a.phase,orders=len(data['orders']),stock=len(data['stock']),read_only=data['observed'][0]['read_only']),ensure_ascii=False))
