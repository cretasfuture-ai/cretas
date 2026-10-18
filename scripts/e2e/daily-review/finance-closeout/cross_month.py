"""Full October/November refund API chain in the owner-bound request-clock copy."""
import os,sys,json,time
from pathlib import Path
from decimal import Decimal
os.environ['DAILY_REVIEW_PROFILE']='P31'
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'refund-close-concurrency'))
import support as flow
from support import *
assert sys.argv[1:]==['NON_PRODUCTION_ONLY'] and PROFILE=='P31'
assert not (OUT/'cross-month.json').exists(),'Completed scenario must not be replayed'
assert '-Dp10.profile=P31' in args and '-javaagent:'+str(OUT/'clock-agent.jar') in args
clock=OUT/'clock.txt';assert str(clock) in next(a for a in args if a.startswith('-Dp10.clock.file='))
original_send=flow.send;events=[]
def clock_send(s,method,path,payload=None):
 assert path.startswith('/') and '://' not in path and '..' not in path
 r=s.request(method,BASE+path,json=payload,timeout=100)
 assert r.headers.get('X-P10-Business-Date')==clock.read_text().strip() and r.headers.get('X-P10-Clock-Isolated')=='true',dict(r.headers)
 e={'method':method,'path':path,'status':r.status_code,'body':r.json(),'businessDate':r.headers['X-P10-Business-Date']};events.append(e);save('cross-month-http-events.json',events);return e
flow.send=clock_send
def phase(value):
 assert value in ('2026-10-18','2026-10-31','2026-11-01');clock.write_text(value+'\n',encoding='utf-8')
def reopen(key):
 p=call(admin,'GET','/finance/accounting-periods/2026/10')
 if p['status']=='CLOSED':period('reopen',key)
 else:assert p['status']=='OPEN'
def reverse(key,view):
 path='/return-orders/'+view['returnOrderId']+'/refund'
 pending=step(key+'-request',admin,path+'/reversal',{'requestId':view['request']['id'],'paymentReference':key+'-RECOVERY','reason':'P31本地模拟款项已收回，完整跨月API验收'})
 decision={'requestId':view['request']['id'],'reversalTransactionId':pending['request']['reversal']['ledger']['id']}
 return path,decision
def refund(key,rbase):
 step(key+'-new',admin,rbase,{'amount':15,'remark':'P31本地跨月退款，不真实转账'})
 step(key+'-submit',admin,rbase+'/submit');step(key+'-approve',finance,rbase+'/approve')
 paid=step(key+'-paid',finance,rbase+'/mark-paid',{'paymentMethod':'BANK_TRANSFER','paymentReference':key+'-PAYMENT','remark':'P31模拟，不真实转账'})
 if key+'-post' not in steps:post_source('RETURN_REFUND',paid['request']['id'],key+'-post')
 return paid
credentials=json.loads((OUT.parent/'factory-data-p30/finance-credentials.private.json').read_text());finance,fin=login(credentials['username'],credentials['password'])
facts=json.loads((OUT/'facts.json').read_text());prepared=json.loads((OUT.parent/'factory-data-p30/prepared.json').read_text());ret=prepared['returnOrder'];rbase='/return-orders/'+ret+'/refund'
try:
 phase('2026-10-18');reopen('cross-initial-reopen')
 prior=call(admin,'GET',rbase)
 if 'cross-original-paid' not in steps:
  if prior['request']['status']=='PAID':
   view=dict(prior);view['returnOrderId']=ret;path,decision=reverse('cross-prior',view);step('cross-prior-approved',finance,path+'/reversal/approve',decision)
 phase('2026-10-31');paid=refund('cross-original',rbase);rid=paid['request']['id'];vid=paid['request']['voucher']['id']
 assert paid['request']['paidAt'].startswith('2026-10-31') and paid['request']['ledger']['transactionDate']=='2026-10-31'
 step('cross-close-window',admin,'/finance/month-close/execute',{'year':2026,'month':10});period('force-lock-close','cross-original-lock')
 phase('2026-11-01');paid['returnOrderId']=ret;path,decision=reverse('cross-next',paid)
 if 'cross-next-approved' not in steps:
  before=snapshot();blocked=clock_send(finance,'POST',path+'/reversal/approve',decision);after=snapshot()
  save('cross-month-locked-rollback.json',{'before':before,'after':after,'response':blocked});assert blocked['status']==400 and before==after,blocked
 else:
  proof=json.loads((OUT/'cross-month-locked-rollback.json').read_text());assert proof['response']['status']==400 and proof['before']==proof['after']
 reopen('cross-reopen-original-month');cancelled=step('cross-next-approved',finance,path+'/reversal/approve',decision)
 v=call(admin,'GET','/finance/vouchers/'+vid);red=call(admin,'GET','/finance/vouchers/'+v['reversalVoucherId'])
 assert v['status']=='REVERSED' and red['status']=='POSTED' and red['originalVoucherId']==vid and red['voucherDate']=='2026-10-31'
 with dbconnect() as d:
  cancelled_at=d.execute('select cancelled_at from payment_requests where factory_id=%s and id=%s',(FACTORY,rid)).fetchone()[0]
  assert str(cancelled_at).startswith('2026-11-01')
  rows=d.execute('select subject_code,sum(debit-credit) from voucher_entries where voucher_id in (%s,%s) and deleted_at is null group by subject_code',(vid,red['id'])).fetchall();assert rows and all(r[1]==0 for r in rows)
  ledgers=d.execute("select transaction_date,amount,approval_status from ar_ap_transactions where factory_id=%s and source_id=%s and source_type in ('RETURN_REFUND','RETURN_REFUND_REVERSAL') order by amount",(FACTORY,rid)).fetchall()
 assert len(ledgers)==2 and all(str(r[0])=='2026-10-31' and r[2]=='APPROVED' for r in ledgers) and sum(r[1] for r in ledgers)==0
 unchanged=snapshot();call(finance,'POST',path+'/reversal/approve',decision);assert snapshot()==unchanged
 final=refund('cross-final-november',rbase);assert final['request']['ledger']['transactionDate']=='2026-11-01'
 step('cross-final-october-window',admin,'/finance/month-close/execute',{'year':2026,'month':10})
 period('force-lock-close','cross-final-october-lock')
 oct_cash=call(admin,'GET','/finance/report/cash-flow?startYear=2026&startMonth=10&endYear=2026&endMonth=10')
 nov_cash=call(admin,'GET','/finance/report/cash-flow?startYear=2026&startMonth=11&endYear=2026&endMonth=11')
 statement=call(admin,'GET','/finance/statement?counterpartyType=CUSTOMER&counterpartyId='+facts['customer']+'&startDate=2026-10-01&endDate=2026-11-30')
 assert Decimal(str(oct_cash['endingCash']))==15 and Decimal(str(nov_cash['endingCash']))==0 and Decimal(str(statement['closingBalance']))==0
 assert snapshot(SOURCE)==json.loads((OUT/'source-before.json').read_text(encoding='utf-8'))
 save('cross-month.json',{'verdict':'PASS','originalPaid':paid,'cancelled':cancelled,'originalVoucher':v,'redVoucher':red,'finalNovemberRefund':final,'octoberCash':oct_cash,'novemberCash':nov_cash,'customerStatement':statement,'lockedRollbackExact':True,'duplicateApprovalNoop':True,'sourceExactUnchanged':True,'sqlBusinessDml':0,'requestClockOnly':True})
 print('P31 cross-month posted refund, locked rollback, reopen reversal, November refund, balances/cash/vouchers PASS')
finally:
 phase('2026-10-18');flow.send=original_send
