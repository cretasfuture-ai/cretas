"""Provision missing private-review Gold inputs from existing canonical SQL.

This is not a production migration runner. It only accepts the owned local DB,
never rewrites a pre-existing Gold data set, and preserves all tenant policies.
"""
from pathlib import Path
import hashlib,json,re,ipaddress
import psycopg

ROOT=Path(__file__).resolve().parents[3]
OUT=Path('C:/Users/Steve/Documents/cretas-connect-20261015/daily-review/analysis')
SQL=ROOT/'backend/python/smartbi/database/migrations'
TABLES=['dim_store','dim_product','fact_pos_transaction','fact_pos_item','dim_restaurant_cost_product','agg_restaurant_product_cost']

def main():
 OUT.mkdir(parents=True,exist_ok=True)
 with psycopg.connect(host='127.0.0.1',port=55447,dbname='vision_entry_probe_20261015',user='assembly_executor') as c:
  with c.cursor() as q:
   q.execute('select current_database(),inet_server_addr()::text,inet_server_port()')
   database,address,port=q.fetchone()
   assert database=='vision_entry_probe_20261015' and ipaddress.ip_interface(address).ip==ipaddress.ip_address('127.0.0.1') and port==55447
   q.execute('select (select count(*) from production_batches),(select count(*) from material_batches),(select count(*) from finished_goods_batches)')
   before=q.fetchone()
   q.execute('select to_regclass(%s)',('public.dim_store',))
   if q.fetchone()[0] is not None:
    assert (OUT/'canonical-bootstrap.json').exists(),'Preserve pre-existing Gold schema'
    print('Previously bootstrapped private schema preserved');return
   inputs=[]
   for name in ['2026_04_28_silver_dimensions.sql','2026_04_29_silver_facts.sql','2026_04_24_gold_restaurant_ops.sql',
                '2026_04_24_recipe_product_source_pk.sql','V20261029_02__restaurant_cost_product_dimension.sql']:
    source=(SQL/name).read_text(encoding='utf-8-sig')
    statement=source
    if name=='2026_04_24_recipe_product_source_pk.sql':
     # The canonical aggregate-table portion, without the unrelated recipe-line table.
     statement=source[source.index('ALTER TABLE agg_restaurant_product_cost\n'):].split('-- Rollback:')[0]
    if name.startswith('V20261029_02'):
     # Do not import another tenant's historical demo backfill or create cluster roles.
     statement=source.split('-- Backfill')[0]
     statement=re.sub(r'^GRANT[^\n]+;\s*$', '', statement, flags=re.MULTILINE)
    q.execute(statement)
    (OUT/(name+'.applied.sql')).write_text(statement,encoding='utf-8')
    inputs.append({'file':str((SQL/name).relative_to(ROOT)),'sourceSha256':hashlib.sha256(source.encode()).hexdigest(),'appliedSha256':hashlib.sha256(statement.encode()).hexdigest()})
   q.execute('select relname,relrowsecurity,relforcerowsecurity from pg_class where relname=ANY(%s)',(TABLES,))
   flags=q.fetchall();assert len(flags)==6 and all(row[1] and row[2] for row in flags)
   q.execute('select (select count(*) from production_batches),(select count(*) from material_batches),(select count(*) from finished_goods_batches)')
   assert q.fetchone()==before
  c.commit()
 receipt={'database':'vision_entry_probe_20261015','productionTouched':False,'existingBusinessCountsUnchanged':list(before),'sources':inputs,'tenantPoliciesRetained':flags,'otherTenantBackfillExecuted':False,'productionMigrationClaimed':False}
 (OUT/'canonical-bootstrap.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
 print(json.dumps({'bootstrap':'PASS','tables':len(flags),'existingBusinessCounts':list(before)}))

if __name__=='__main__':main()
