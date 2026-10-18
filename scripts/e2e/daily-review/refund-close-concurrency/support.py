"""Reuse ordinary business APIs and strict, read-only PostgreSQL assertions."""
from pathlib import Path
from datetime import date
from decimal import Decimal
import json,requests,psycopg,psutil,os

ROOT=Path(__file__).resolve().parents[4]
PROFILE=os.environ.get('DAILY_REVIEW_PROFILE','P30');assert PROFILE in ('P30','P31','P34')
OUT=Path.home()/('Documents/cretas-connect-20261015/factory-data-'+PROFILE.lower())
FACTORY='FOOD_3101_001';DATABASE='p30_refund_close_20261018' if PROFILE=='P30' else 'p31_finance_closeout_20261018';SOURCE='p14_new_factory_20261016' if PROFILE=='P30' else 'p30_refund_close_20261018'
PORT=10066 if PROFILE=='P30' else 10068
if PROFILE=='P34':DATABASE,SOURCE,PORT='p34_disposal_guard_20261018','p31_finance_closeout_20261018',10070
BASE='http://127.0.0.1:'+str(PORT)+'/api/mobile/'+FACTORY
assert (date.today().year,date.today().month)==(2026,10),'Fixture period expired; do not run against another month'
runtime=json.loads((OUT/'runtime.json').read_text());assert runtime['owner']==({'P30':'P30-REFUND-CLOSE-RACE-20261018','P31':'P31-FINANCE-CLOSEOUT-20261018','P34':'P34-DISPOSAL-LIFECYCLE-GUARD-20261018'}[PROFILE]) and runtime['database']==DATABASE
proc=psutil.Process(runtime['javaPid']);assert proc.name().lower()=='java.exe' and '@'+runtime['javaArgFile'] in proc.cmdline()
argfile=Path(runtime['javaArgFile']);args=[json.loads(line) for line in argfile.read_text(encoding='utf-8').splitlines() if line.strip()]
assert argfile.stat().st_mtime<=proc.create_time(),'Runtime argument file changed after launch'
assert '--server.port='+str(PORT) in args and '--server.address=127.0.0.1' in args
dburl='jdbc:postgresql://127.0.0.1:55447/'+DATABASE+'?stringtype=unspecified'+('&options=-c%20lock_timeout%3D3s' if PROFILE=='P34' else '')
assert '--spring.datasource.url='+dburl in args
assert '--smartbi.postgres.url='+dburl in args
assert '--spring.flyway.enabled=false' in args and '--spring.jpa.hibernate.ddl-auto=none' in args
assert any(c.status=='LISTEN' and c.laddr.port==PORT for c in proc.net_connections(kind='tcp')),'Wrong live service owner'
statefile=OUT/'business-state.json'
state=json.loads(statefile.read_text(encoding='utf-8')) if statefile.exists() else {'factory':FACTORY,'steps':{},'events':[]}
assert state['factory']==FACTORY
steps=state['steps']
def save(name,obj):(OUT/name).write_text(json.dumps(obj,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
def persist():save('business-state.json',state)
def login(username,password):
 s=requests.Session();r=s.post('http://127.0.0.1:'+str(PORT)+'/api/mobile/auth/unified-login',json={'username':username,'password':password,'clientType':'web'},timeout=30);r.raise_for_status();a=r.json()['data'];assert a['factoryId']==FACTORY and a['username']==username
 s.headers['Authorization']='Bearer '+(a.get('token') or a['accessToken']);return s,a
cred=json.loads((OUT.parent/'factory-data-p14/factory-credentials.private.json').read_text())
admin,identity=login('food_3101_001_admin',cred['password']);assert identity['userId']==2
def send(s,method,path,payload=None):
 assert path.startswith('/') and '://' not in path and '..' not in path
 r=s.request(method,BASE+path,json=payload,timeout=100);return {'method':method,'path':path,'status':r.status_code,'body':r.json()}
def call(s,method,path,payload=None):
 e=send(s,method,path,payload);state['events'].append(e);persist();assert e['status']==200 and e['body']['success'],e;return e['body']['data']
def step(key,s,path,payload=None):
 if key not in steps:steps[key]=call(s,'POST',path,payload or {});persist()
 return steps[key]
def dbconnect(database=DATABASE,readonly=True):
 assert database in (DATABASE,SOURCE)
 d=psycopg.connect(host='127.0.0.1',port=55447,dbname=database,user='assembly_executor',options='-c default_transaction_read_only='+('on' if readonly else 'off'))
 assert d.execute('select current_database()').fetchone()[0]==database
 return d
TABLES=['customers','sales_orders','sales_order_items','return_orders','return_order_items','payment_requests','payment_records','ar_ap_transactions','vouchers','voucher_entries','accounting_periods','finished_goods_batches','material_batches','material_batch_adjustments','sales_delivery_records','sales_delivery_items','sales_delivery_item_batch_allocations','production_plans','production_batches','production_settlements','production_settlement_output_lines','indicators','indicator_versions']
if PROFILE in ('P31','P34'):TABLES+=['disposal_records','finished_goods_adjustment_log','batch_lineage_edges']
def snapshot(database=DATABASE):
 with dbconnect(database) as d:
  result={}
  for t in TABLES:
   assert d.execute('select to_regclass(%s)',(t,)).fetchone()[0]
   # Whole local copy read protects unrelated factories as well. No SQL business mutations.
   result[t]=[r[0] for r in d.execute('select row_to_json(t) from '+t+' t order by id')]
  return result
def period(action,key):return step(key,admin,'/finance/accounting-periods/'+action,{'year':2026,'month':10,**({'reason':'P30本地退款结账并发验收，保留完整红冲历史'} if action=='reopen' else {})})
def post_source(source,business_id,key):
 with dbconnect() as d:rows=d.execute('select id,status from vouchers where factory_id=%s and source_business_type=%s and source_business_id=%s and deleted_at is null',(FACTORY,source,business_id)).fetchall()
 assert len(rows)==1,(source,business_id,rows)
 if rows[0][1]=='DRAFT':step(key,admin,'/finance/vouchers/'+rows[0][0]+'/post')
 else:assert rows[0][1]=='POSTED',rows
 return rows[0][0]
def financial_read(customer):
 overview=call(admin,'GET','/finance/overview')
 statement=call(admin,'GET','/finance/statement?counterpartyType=CUSTOMER&counterpartyId='+customer+'&startDate=2026-10-01&endDate=2026-10-31')
 cash=call(admin,'GET','/finance/report/cash-flow?startYear=2026&startMonth=10&endYear=2026&endMonth=10')
 income=call(admin,'GET','/finance/report/income-statement?startYear=2026&startMonth=10&endYear=2026&endMonth=10')
 with dbconnect() as d:
  balance=d.execute('select current_balance from customers where factory_id=%s and id=%s',(FACTORY,customer)).fetchone()[0]
  total=d.execute('select sum(current_balance) from customers where factory_id=%s and deleted_at is null',(FACTORY,)).fetchone()[0]
  ar=d.execute("select coalesce(sum(case when transaction_type='AR_INVOICE' then amount when transaction_type in ('AR_ADJUSTMENT','AR_REFUND') and approval_status='APPROVED' then amount when transaction_type in ('AR_PAYMENT','AR_CREDIT_NOTE') then -abs(amount) else 0 end),0) from ar_ap_transactions where factory_id=%s and counterparty_id=%s and counterparty_type='CUSTOMER' and deleted_at is null",(FACTORY,customer)).fetchone()[0]
  book_cash=d.execute("select coalesce(sum(e.debit-e.credit),0) from voucher_entries e join vouchers v on v.id=e.voucher_id where v.factory_id=%s and v.status in ('POSTED','REVERSED') and v.deleted_at is null and e.deleted_at is null and e.subject_code in ('1001','1002','1012')",(FACTORY,)).fetchone()[0]
  book_ar=d.execute("select coalesce(sum(e.debit-e.credit),0) from voucher_entries e join vouchers v on v.id=e.voucher_id where v.factory_id=%s and v.status in ('POSTED','REVERSED') and v.deleted_at is null and e.deleted_at is null and e.subject_code='1122' and e.auxiliary_entity_id=%s",(FACTORY,customer)).fetchone()[0]
  pending_ar=d.execute("select coalesce(sum(e.debit-e.credit),0) from voucher_entries e join vouchers v on v.id=e.voucher_id where v.factory_id=%s and v.status='DRAFT' and v.deleted_at is null and e.deleted_at is null and e.subject_code='1122' and e.auxiliary_entity_id=%s",(FACTORY,customer)).fetchone()[0]
 assert Decimal(str(statement['closingBalance']))==balance==ar,(statement,balance,ar)
 assert Decimal(str(overview['totalReceivable']))==total
 assert Decimal(str(cash['endingCash']))==book_cash
 assert book_ar+pending_ar==balance,(book_ar,pending_ar,balance)
 return {'overview':overview,'statement':statement,'cashFlow':cash,'income':income,'customerBalance':str(balance),'signedAr':str(ar),'postedBookAr':str(book_ar),'pendingDraftAr':str(pending_ar),'postedBookCash':str(book_cash)}
