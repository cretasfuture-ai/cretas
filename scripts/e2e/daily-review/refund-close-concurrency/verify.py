"""Final independent readback of all six real races and protection of the source factory."""
import sys,hashlib,subprocess
from support import *
assert sys.argv[1:]==['NON_PRODUCTION_ONLY']
cases=['pay-close','reverse-posted-close','pay-refund','reverse-posted-refund','reverse-draft-close','reverse-draft-refund']
receipts={key:json.loads((OUT/(key+'.json')).read_text(encoding='utf-8')) for key in cases}
for key,r in receipts.items():
 assert r['verdict']=='PASS' and r['stage']=='accepted' and r['sqlBusinessDml']==0
 assert r['initialAdjustmentWindow']['adjustWindowState']=='OPEN_WINDOW' and not r['initialAdjustmentWindow']['closingPostedAt']
 assert len(r['waitersFirst'])==1 and len(r['waitersBoth'])==2
 first_pid=r['waitersFirst'][0][0];both=r['waitersBoth'];later=next(row for row in both if row[0]!=first_pid)
 assert first_pid in later[4],('Expected PostgreSQL ordering dependency',key,both)
 if key.endswith('-close'):
  assert r['results']['refund']['status']==r['lockedRetry']['status']==400 and r['exactFinancialStockRollback']
 else:assert r['results']['refund']['status']==200 and r['refundCommittedBeforeClosing']
 assert r['results']['close']['status']==200
final=json.loads((OUT/'finalize.json').read_text(encoding='utf-8'));request_id=final['finalRefund']['request']['id']
view=call(admin,'GET','/return-refunds/'+request_id);assert view['request']['status']=='PAID' and view['request']['voucher']['status']=='POSTED'
read=financial_read(json.loads((OUT/'prepared.json').read_text())['customer'])
for k in ('customerBalance','signedAr','postedBookAr','pendingDraftAr','postedBookCash'):assert Decimal(read[k])==0,(k,read[k])
physical={k:json.loads((OUT/('cost-'+k+'.json')).read_text(encoding='utf-8')) for k in ('correct','partial','multi')}
assert all(v['verdict']=='PASS' for v in physical.values())
assert Decimal(str(physical['partial']['result']['income']['totalCost']))==Decimal('367.37')
second_customer=physical['partial']['customer'];second_read=financial_read(second_customer)
for k in ('customerBalance','signedAr','postedBookAr','pendingDraftAr','postedBookCash'):assert Decimal(second_read[k])==0,(k,second_read[k])
assert read['income']['totalRevenue']==720 and read['income']['totalCost']==346.12 and read['income']['netProfit']==373.88
closing_snapshot=snapshot();closing_vouchers=[]
for kind,amount in [('COST_CARRYOVER',Decimal('346.12')),('PL_CLOSING',Decimal('720'))]:
 vs=[v for v in closing_snapshot['vouchers'] if v['factory_id']==FACTORY and v['source_business_type']==kind and v['status']=='POSTED' and not v.get('original_voucher_id')]
 assert len(vs)==1
 es=[e for e in closing_snapshot['voucher_entries'] if e['voucher_id']==vs[0]['id']]
 assert sum(Decimal(str(e.get('debit') or 0)) for e in es)==amount
 if kind=='PL_CLOSING':
  credits={e['subject_code']:Decimal(str(e.get('credit') or 0)) for e in es}
  assert credits['6401']==Decimal('346.12') and credits['4103']==Decimal('373.88')
 closing_vouchers.append({'voucher':vs[0],'entries':es})
assert snapshot(SOURCE)==json.loads((OUT/'source-before.json').read_text(encoding='utf-8'))
accepted=OUT.parent/'factory-data-p29'
fixed=json.loads((OUT/'product-final-freeze.json').read_text()) if (OUT/'product-final-freeze.json').exists() else {}
for f,digest in {**json.loads((accepted/'source-final-freeze.json').read_text(encoding='utf-8-sig')),**fixed}.items():assert hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==digest,'Product source drift '+f
original_runtime=json.loads((accepted/'runtime.json').read_text());original=psutil.Process(original_runtime['javaPid']);assert original.name().lower()=='java.exe' and '@'+original_runtime['javaArgFile'] in original.cmdline()
fresh=snapshot();before=json.loads((OUT/'clone-before.json').read_text(encoding='utf-8'))
for t in ('production_batches','production_settlements','production_settlement_output_lines','material_batches','material_batch_adjustments'):assert fresh[t]==before[t],t
# The dedicated Java restart refreshes these four existing metric caches.
# Protect values/configuration and every other factory exactly; only the two
# observed cache timestamps may advance for these already provisioned metrics.
metric_codes={'FACTORY_LU_UNIT_COST','FACTORY_LU_YIELD_RATE','FACTORY_GOOD_OUTPUT_RATE','FACTORY_ACTUAL_TOTAL_COST'}
old_metrics={r['id']:r for r in before['indicators']}
assert {r['id'] for r in fresh['indicators']}==set(old_metrics)
metric_refresh=[]
for row in fresh['indicators']:
 old=old_metrics[row['id']]
 if row==old:continue
 assert row['factory_id']==FACTORY and row['code'] in metric_codes
 assert {k:v for k,v in row.items() if k not in ('last_computed_at','updated_at')}=={k:v for k,v in old.items() if k not in ('last_computed_at','updated_at')}
 assert row['last_computed_at']>=old['last_computed_at'] and row['updated_at']>=old['updated_at']
 metric_refresh.append({'id':row['id'],'code':row['code'],'before':old['last_computed_at'],'after':row['last_computed_at'],'unchangedValue':row['last_value']})
old_versions={r['id']:r for r in before['indicator_versions']};versions={r['id']:r for r in fresh['indicator_versions']}
assert all(versions[k]==v for k,v in old_versions.items())
new_versions=[v for k,v in versions.items() if k not in old_versions]
assert len(new_versions)==len(metric_refresh)==4
assert {v['indicator_id'] for v in new_versions}=={m['id'] for m in metric_refresh}
current_metrics={r['id']:r for r in fresh['indicators']}
first_fixed_read=json.loads((OUT/'cost-correct.json').read_text(encoding='utf-8'))['result']['income']['generatedAt']
for v in new_versions:
 metric=current_metrics[v['indicator_id']]
 assert v['factory_id']==FACTORY and v['value']==metric['last_value'] and v['computed_at']==metric['last_computed_at']
 assert v['period_start']=='2026-10-01' and v['period_end']=='2026-10-18' and v['deleted_at'] is None
 assert v['compute_source']=='REAL_BUSINESS:'+metric['code']+':actual-flow-v2'
 assert v['computed_at']<first_fixed_read
old_plans={r['id']:r for r in before['production_plans']};plans={r['id']:r for r in fresh['production_plans']}
assert all(plans[k]==v for k,v in old_plans.items())
new_plans=[v for k,v in plans.items() if k not in old_plans];prepared=json.loads((OUT/'prepared.json').read_text())
allowed_orders={prepared['salesOrder']}
if (OUT/'cost-partial.json').exists():allowed_orders.add(json.loads((OUT/'cost-partial.json').read_text(encoding='utf-8'))['sale']['id'])
assert len(new_plans)==len(allowed_orders) and {v['source_order_id'] for v in new_plans}==allowed_orders and all(v['factory_id']==FACTORY and v['plan_type']=='FROM_INVENTORY' for v in new_plans),new_plans
for t,rows in before.items():
 if t in ('accounting_periods','vouchers','voucher_entries'):continue
 current={str(r['id']):r for r in fresh[t]}
 for old in rows:
  if old.get('factory_id')!=FACTORY or t=='customers':assert current[str(old['id'])]==old,('Unrelated tenant/customer mutated',t,old['id'])
with dbconnect() as d:
 refund_ledgers=d.execute("select source_id,count(*),sum(amount) from ar_ap_transactions where factory_id=%s and source_type='RETURN_REFUND' and deleted_at is null group by source_id",(FACTORY,)).fetchall()
 for rid,count,amount in refund_ledgers:
  request_amount=d.execute('select amount from payment_requests where factory_id=%s and id=%s',(FACTORY,rid)).fetchone()[0]
  assert count==1 and amount==request_amount,(rid,count,amount,request_amount)
 # Every cancelled local request must have exactly one approved recovery and correct voucher links.
 cancelled=d.execute("select id from payment_requests where factory_id=%s and return_order_id=%s and status='CANCELLED'",(FACTORY,json.loads((OUT/'prepared.json').read_text())['returnOrder'])).fetchall()
 for (rid,) in cancelled:
  recovery=d.execute("select amount,approval_status from ar_ap_transactions where factory_id=%s and source_type='RETURN_REFUND_REVERSAL' and source_id=%s and deleted_at is null",(FACTORY,rid)).fetchall();assert recovery==[(Decimal('-15.00'),'APPROVED')],(rid,recovery)
 # The 15 refund is genuinely an already posted debit AR / credit bank voucher.
 entries=d.execute('select subject_code,debit,credit from voucher_entries where voucher_id=%s and deleted_at is null order by line_no',(view['request']['voucher']['id'],)).fetchall()
 assert entries==[('1122',Decimal('15.00'),Decimal('0.00')),('1002',Decimal('0.00'),Decimal('15.00'))],entries
files=[p for p in Path(__file__).parent.iterdir() if p.suffix in ('.py','.md')]
freeze={str(p.relative_to(ROOT)).replace('\\','/'):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
save('source-freeze.json',freeze)
save('final-readback.json',{'verdict':'PASS','factory':FACTORY,'database':DATABASE,'caseCount':6,'sourceDatabaseExactUnchanged':True,'originalRuntimeUnchanged':True,'originalJavaPid':original.pid,'productSourceMatchesCombinedP29P30Freeze':True,'startupMetricTimestampRefresh':metric_refresh,'cancelledRefunds':len(cancelled),'refundLedgerOnePerRequest':True,'refundVoucherEntries':entries,'finalRefund':view,'finalFinance':read,'secondCustomerFinance':second_read,'activeClosingVouchers':closing_vouchers,'physicalReturnCostStages':list(physical),'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'sqlBusinessDml':0})
print('P30 six cases, posted 15 refund, ledger/statement/GL/cash consistency, original DB/runtime/source: PASS')
