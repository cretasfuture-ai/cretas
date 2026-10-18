"""Real HTTP races queued on the production advisory lock, with no business SQL DML."""
import argparse,time,sys
from concurrent.futures import ThreadPoolExecutor
from support import *

p=argparse.ArgumentParser();p.add_argument('ack',choices=['NON_PRODUCTION_ONLY']);p.add_argument('--case',choices=['pay-close','reverse-posted-close','pay-refund','reverse-posted-refund','reverse-draft-close','reverse-draft-refund','finalize'],required=True);args=p.parse_args()
key=args.case;path=OUT/(key+'.json');assert not path.exists(),'Completed or partial race already exists; inspect checkpoint instead of replay'
prepared=json.loads((OUT/'prepared.json').read_text(encoding='utf-8'));ret=prepared['returnOrder'];customer=prepared['customer'];rbase='/return-orders/'+ret+'/refund'
credentials=json.loads((OUT/'finance-credentials.private.json').read_text());finance,fin_actor=login(credentials['username'],credentials['password']);assert fin_actor['userId']!=identity['userId']
def original():return call(admin,'GET',rbase)
def open_period():
 current=call(admin,'GET','/finance/accounting-periods/2026/10')
 if current['status']=='CLOSED':period('reopen',key+'-reopen')
 else:assert current['status']=='OPEN',current
def apply_refund():
 view=original()
 if view.get('request') and view['request']['status']=='APPROVED':return view
 assert not view.get('request') or view['request']['status']=='CANCELLED',view
 step(key+'-new',admin,rbase,{'amount':15,'remark':'P30独立副本退款并发模拟，不实际转账'})
 step(key+'-submit',admin,rbase+'/submit')
 return step(key+'-approve',finance,rbase+'/approve')
def paid_command():return {'paymentMethod':'BANK_TRANSFER','paymentReference':'P30-REFUND-'+key.upper()+'-'+original()['request']['id'][:8],'remark':'P30本地退款登记，不实际转账'}
def pay():return step(key+'-paid',finance,rbase+'/mark-paid',paid_command())
def pending_reversal():
 v=original();rid=v['request']['id']
 result=step(key+'-reversal',admin,rbase+'/reversal',{'requestId':rid,'paymentReference':'P30-RECOVERY-'+key.upper()+'-'+rid[:8],'reason':'P30本地模拟原款已收回，申请退款撤销'})
 return {'requestId':rid,'reversalTransactionId':result['request']['reversal']['ledger']['id']}
def refund_projection(snap):
 closing={v['id'] for v in snap['vouchers'] if v['voucher_type'] in ('COST_CARRYOVER','PL_CLOSING')}
 return {t:([v for v in rows if v['id'] not in closing] if t=='vouchers' else [e for e in rows if e['voucher_id'] not in closing] if t=='voucher_entries' else rows) for t,rows in snap.items() if t!='accounting_periods'}
def waiters(count,future):
 end=time.monotonic()+30
 while time.monotonic()<end:
  with dbconnect() as d:rows=d.execute("select pid,wait_event_type,wait_event,query,pg_blocking_pids(pid) as blockers from pg_stat_activity where datname=current_database() and wait_event='advisory' and query like '%pg_advisory_xact_lock%'").fetchall()
  if len(rows)>=count:return rows
  if future.done():
   record.update(stage='failed-before-advisory-queue',earlyResponse=future.result());save(key+'.json',record)
   raise AssertionError(record['earlyResponse'])
  time.sleep(.1)
 raise AssertionError('Expected real PG waiters: '+str(count))
def closing_readback():
 read=financial_read(customer);snap=snapshot()
 for kind,amount in [('COST_CARRYOVER',read['income']['totalCost']),('PL_CLOSING',read['income']['totalRevenue'])]:
  vs=[v for v in snap['vouchers'] if v['factory_id']==FACTORY and v['source_business_type']==kind and v['status']=='POSTED' and not v.get('original_voucher_id')];assert len(vs)==1,(kind,vs)
  es=[e for e in snap['voucher_entries'] if e['voucher_id']==vs[0]['id']];assert sum(Decimal(str(e.get('debit') or 0)) for e in es)==Decimal(str(amount)),(kind,es,amount)
  if kind=='PL_CLOSING':
   credits={e['subject_code']:Decimal(str(e.get('credit') or 0)) for e in es};assert credits['6401']==Decimal(str(read['income']['totalCost'])) and credits['4103']==Decimal(str(read['income']['netProfit'])),es
 read['activeClosingVouchers']=[v for v in snap['vouchers'] if v['source_business_type'] in ('PL_CLOSING','COST_CARRYOVER') and v['status']=='POSTED' and not v.get('original_voucher_id')]
 return read
open_period()
if key=='finalize':
 view=apply_refund();paid=pay();post_source('RETURN_REFUND',paid['request']['id'],key+'-post')
 period('request-close',key+'-request-close');period('confirm-close',key+'-confirm-close');period('force-lock-close',key+'-lock')
 read=closing_readback();assert Decimal(read['customerBalance'])==0 and Decimal(read['pendingDraftAr'])==0
 assert snapshot(SOURCE)==json.loads((OUT/'source-before.json').read_text(encoding='utf-8'))
 save(key+'.json',{'verdict':'PASS','finalRefund':original(),'readback':read,'sourceOriginalExactUnchanged':True,'sqlBusinessDml':0});print('finalize PASS');sys.exit(0)
if key.startswith('pay-'):
 view=apply_refund();refund_request=view['request']['id'];endpoint=rbase+'/mark-paid';payload=paid_command()
else:
 view=original()
 if not view.get('request') or view['request']['status']=='CANCELLED':apply_refund();view=pay()
 assert view['request']['status']=='PAID'
 if 'posted' in key:post_source('RETURN_REFUND',view['request']['id'],key+'-post')
 else:assert view['request']['voucher']['status']=='DRAFT',view
 payload=pending_reversal();refund_request=payload['requestId'];endpoint=rbase+'/reversal/approve'
window=step(key+'-month-close',admin,'/finance/month-close/execute',{'year':2026,'month':10})
assert window['period']['adjustWindowState']=='OPEN_WINDOW' and not window['period'].get('closingPostedAt'),window
guard=call(admin,'GET','/finance/accounting-periods/2026/10');assert guard['adjustWindowState']=='OPEN_WINDOW'
before=snapshot();before_projection=refund_projection(before);save(key+'-before.json',before)
close_first=key.endswith('-close');jobs=[('close','/finance/accounting-periods/force-lock-close',{'year':2026,'month':10},admin),('refund',endpoint,payload,finance)]
if not close_first:jobs.reverse()
record={'case':key,'stage':'prepared','requestId':refund_request,'operation':endpoint,'closeFirst':close_first,'payload':payload,'initialAdjustmentWindow':guard};save(key+'.json',record)
with ThreadPoolExecutor(max_workers=2) as pool:
 with dbconnect(readonly=False) as blocker:
  blocker.execute('select pg_advisory_xact_lock(hashtextextended(%s,0))',('production-cost-finance:'+FACTORY,))
  first=pool.submit(send,jobs[0][3],'POST',jobs[0][1],jobs[0][2]);q1=waiters(1,first);assert not first.done()
  second=pool.submit(send,jobs[1][3],'POST',jobs[1][1],jobs[1][2]);q2=waiters(2,second);assert not first.done() and not second.done()
  record.update(stage='both-http-blocked',waitersFirst=q1,waitersBoth=q2);save(key+'.json',record)
 results={jobs[0][0]:first.result(),jobs[1][0]:second.result()}
record.update(stage='completed-http',results=results);save(key+'.json',record)
assert results['close']['status']==200 and results['close']['body']['success'],results
after=snapshot();save(key+'-after.json',after)
if close_first:
 response=results['refund'];assert response['status']==400 and '期间已结账' in response['body']['message'],response
 assert before_projection==refund_projection(after),'Rejected refund leaked business state'
 record['exactFinancialStockRollback']=True
 # Repeat while hard locked: no new rows, balance changes, or duplicate cancellation.
 retry_before=snapshot();again=send(finance,'POST',endpoint,payload);record['lockedRetry']=again;save(key+'.json',record)
 assert again['status']==400 and '期间已结账' in again['body']['message'] and snapshot()==retry_before
 period('reopen',key+'-retry-reopen');completed=step(key+'-retry-after-reopen',finance,endpoint,payload)
else:
 response=results['refund'];assert response['status']==200 and response['body']['success'],response
 record['refundCommittedBeforeClosing']=True
 completed=call(admin,'GET','/return-refunds/'+refund_request)
record['completedRefund']=completed
if key.startswith('pay-'):
 request=completed['request'];assert request['status']=='PAID' and request['voucher']['status']=='DRAFT'
 read=financial_read(customer);assert Decimal(read['customerBalance'])==0 and Decimal(read['pendingDraftAr'])==15
 record['pendingVoucherExplainsUnpostedAr']=True
 # Posting remains a separate normal approval step; never claim DRAFT as posted money.
 if not close_first:period('reopen',key+'-post-reopen')
 post_source('RETURN_REFUND',refund_request,key+'-post-after-pay')
 record['afterNormalPosting']=financial_read(customer);assert Decimal(record['afterNormalPosting']['pendingDraftAr'])==0
else:
 request=completed['request'];assert request['status']=='CANCELLED' and request['reversal']['status']=='APPROVED'
 if 'posted' in key:
  assert request['voucher']['status']=='REVERSED' and request['reversal']['voucher']['status']=='POSTED'
  orig=call(admin,'GET','/finance/vouchers/'+request['voucher']['id']);mirror=call(admin,'GET','/finance/vouchers/'+request['reversal']['voucher']['id']);assert mirror['originalVoucherId']==orig['id'] and orig['reversalVoucherId']==mirror['id']
  with dbconnect() as d:
   nets=d.execute('select subject_code,sum(debit-credit) from voucher_entries where voucher_id in (%s,%s) and deleted_at is null group by subject_code',(orig['id'],mirror['id'])).fetchall()
  assert all(n[1]==0 for n in nets),nets
 else:assert request['voucher']['status']=='VOID' and request['reversal']['voucher'] is None
 read=financial_read(customer);assert Decimal(read['customerBalance'])==-15 and Decimal(read['pendingDraftAr'])==0
 record['afterReversal']=read
 # Approved reversal retries must be exact no-ops, including after locked close.
 repeat_before=snapshot();again=call(finance,'POST',endpoint,payload);assert again['request']['status']=='CANCELLED' and snapshot()==repeat_before
 record['approvedReversalRetryExactNoop']=True
if close_first or key.startswith('pay-'):
 period('request-close',key+'-final-request-close');period('confirm-close',key+'-final-confirm-close');period('force-lock-close',key+'-final-lock')
record['closingReadback']=closing_readback()
assert snapshot(SOURCE)==json.loads((OUT/'source-before.json').read_text(encoding='utf-8')),'Source experience DB modified'
record.update(verdict='PASS',stage='accepted',sourceOriginalExactUnchanged=True,sqlBusinessDml=0)
save(key+'.json',record);print(key+' PASS')
