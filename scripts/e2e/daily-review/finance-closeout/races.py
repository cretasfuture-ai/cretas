"""Real PostgreSQL queues: final-lock guards, refunds and adjustment creation."""
import os,sys,time,json
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
os.environ['DAILY_REVIEW_PROFILE']='P31';sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'refund-close-concurrency'))
from support import *
assert sys.argv[1:]==['NON_PRODUCTION_ONLY'];assert not (OUT/'races.json').exists()
credentials=json.loads((OUT.parent/'factory-data-p30/finance-credentials.private.json').read_text());finance,fin=login(credentials['username'],credentials['password'])
f=json.loads((OUT/'facts.json').read_text());prepared=json.loads((OUT.parent/'factory-data-p30/prepared.json').read_text());ret=prepared['returnOrder'];rbase='/return-orders/'+ret+'/refund'
def opening(key):
 p=call(admin,'GET','/finance/accounting-periods/2026/10')
 if p['status']=='CLOSED':period('reopen',key+'-reopen')
 else:assert p['status']=='OPEN'
def apply(key):
 step(key+'-new',admin,rbase,{'amount':15,'remark':'P31新结账守卫下正常退款并发'})
 step(key+'-submit',admin,rbase+'/submit');return step(key+'-approve',finance,rbase+'/approve')
def pay(key):return step(key+'-pay',finance,rbase+'/mark-paid',{'paymentMethod':'BANK_TRANSFER','paymentReference':'P31-'+key,'remark':'本地模拟不实际转账'})
def reverse(key,view):
 pending=step(key+'-reverse',admin,rbase+'/reversal',{'requestId':view['request']['id'],'paymentReference':'P31-'+key+'-RECOVER','reason':'本地款项模拟收回'})
 return {'requestId':view['request']['id'],'reversalTransactionId':pending['request']['reversal']['ledger']['id']}
def recover(key):
 v=call(admin,'GET',rbase)
 if v['request']['status']=='PAID':step(key+'-approve-reversal',finance,rbase+'/reversal/approve',reverse(key,v))
def window(key):
 p=step(key+'-window',admin,'/finance/month-close/execute',{'year':2026,'month':10})['period']
 assert p['adjustWindowState']=='OPEN_WINDOW' and not p['closingPostedAt']
def waiters(count):
 end=time.monotonic()+30
 while time.monotonic()<end:
  with dbconnect() as d:rows=d.execute("select pid,pg_blocking_pids(pid) from pg_stat_activity where datname=current_database() and wait_event='advisory' and query like '%pg_advisory_xact_lock%'").fetchall()
  if len(rows)==count:return rows
  time.sleep(.1)
 raise AssertionError('Expected actual factory-lock waiters')
def race(key,op,close_first):
 assert not (OUT/(key+'.json')).exists(),'Inspect previous partial case; no replay'
 jobs=[('close',admin,'/finance/accounting-periods/force-lock-close',{'year':2026,'month':10}),('operation',op[0],op[1],op[2])]
 if not close_first:jobs.reverse()
 before=snapshot();save(key+'-before.json',before)
 with ThreadPoolExecutor(max_workers=2) as pool:
  with dbconnect(readonly=False) as blocker:
   blocker.execute('select pg_advisory_xact_lock(hashtextextended(%s,0))',('production-cost-finance:'+FACTORY,))
   first=pool.submit(send,jobs[0][1],'POST',jobs[0][2],jobs[0][3]);one=waiters(1)
   second=pool.submit(send,jobs[1][1],'POST',jobs[1][2],jobs[1][3]);both=waiters(2)
   assert not first.done() and not second.done()
   later=next(r for r in both if r[0]!=one[0][0]);assert one[0][0] in later[1]
  results={jobs[0][0]:first.result(),jobs[1][0]:second.result()}
 after=snapshot();save(key+'-after.json',after)
 receipt={'key':key,'before':before,'after':after,'first':one,'both':both,'results':results};save(key+'.json',receipt)
 return receipt
results=[]
for name,kind,close_first in [('pay-close','pay',True),('pay-first','pay',False),('reverse-posted-close','posted',True),('reverse-posted-first','posted',False),('reverse-draft-close','draft',True),('reverse-draft-first','draft',False)]:
 key='race-'+name;opening(key);recover(key+'-prepare');apply(key)
 if kind=='pay':op=(finance,rbase+'/mark-paid',{'paymentMethod':'BANK_TRANSFER','paymentReference':'P31-'+key,'remark':'本地竞态模拟'})
 else:
  paid=pay(key)
  if kind=='posted':post_source('RETURN_REFUND',paid['request']['id'],key+'-post')
  op=(finance,rbase+'/reversal/approve',reverse(key,paid))
 window(key);r=race(key,op,close_first);rs=r['results']
 if kind=='pay' and close_first:
  assert rs['close']['status']==200 and rs['operation']['status']==400
  # Only closing artifacts and period fields may differ after rejected payment.
  ids={v['id'] for v in r['after']['vouchers'] if v['voucher_type'] in ('PL_CLOSING','COST_CARRYOVER')}
  def projection(s):return {t:([v for v in rows if v['id'] not in ids] if t=='vouchers' else [e for e in rows if e['voucher_id'] not in ids] if t=='voucher_entries' else rows) for t,rows in s.items() if t!='accounting_periods'}
  assert projection(r['before'])==projection(r['after'])
  opening(key+'-retry');paid=step(key+'-retry-pay',finance,op[1],op[2]);post_source('RETURN_REFUND',paid['request']['id'],key+'-post-retry')
 elif kind=='pay':
  assert rs['operation']['status']==200 and rs['close']['status']==409
  p=call(admin,'GET','/finance/accounting-periods/2026/10');assert not p['closingPostedAt'] and p['adjustWindowState']=='OPEN_WINDOW'
  post_source('RETURN_REFUND',rs['operation']['body']['data']['request']['id'],key+'-post-after-race');period('force-lock-close',key+'-final-lock')
 else:
  assert rs['operation']['status']==200 and rs['close']['status']==(409 if close_first else 200)
  v=rs['operation']['body']['data']['request'];assert v['status']=='CANCELLED' and v['voucher']['status']==('REVERSED' if kind=='posted' else 'VOID')
  opening(key+'-settle');apply(key+'-settle');paid=pay(key+'-settle');post_source('RETURN_REFUND',paid['request']['id'],key+'-settle-post');window(key+'-settle');period('force-lock-close',key+'-settle-lock')
 read=financial_read(f['customer']);assert Decimal(read['customerBalance'])==0 and Decimal(read['pendingDraftAr'])==0 and Decimal(read['postedBookCash'])==0
 r.update(verdict='PASS',policy='Final lock blocks new DRAFT vouchers and pending reversals');save(key+'.json',r);results.append(key)
# Manual adjustment creation is a second actual HTTP writer on the same lock.
for close_first in (True,False):
 key='race-adjustment-'+('close' if close_first else 'writer');opening(key);window(key)
 op=(admin,'/finance/adjustment?counterpartyType=CUSTOMER',{'counterpartyId':f['customer'],'amount':1,'remark':'P31并发待审调整，将正常驳回不改变余额'})
 r=race(key,op,close_first);rs=r['results']
 if close_first:assert rs['close']['status']==200 and rs['operation']['status']==400
 else:
  assert rs['operation']['status']==200 and rs['close']['status']==409
  tx=rs['operation']['body']['data'];step(key+'-reject',finance,'/finance/adjustment/'+tx['id']+'/reject?reason=P31-local-acceptance-complete')
  period('force-lock-close',key+'-final-lock')
 r['verdict']='PASS';save(key+'.json',r);results.append(key)
assert snapshot(SOURCE)==json.loads((OUT/'source-before.json').read_text(encoding='utf-8'))
save('races.json',{'verdict':'PASS','cases':results,'sourceExactUnchanged':True,'sqlBusinessDml':0});print('P31 eight real PostgreSQL concurrency cases PASS')
