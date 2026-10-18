"""P31 normal business API acceptance; only loopback copy permits mutations."""
import os,sys,time,json
from pathlib import Path
from decimal import Decimal,ROUND_HALF_UP
from concurrent.futures import ThreadPoolExecutor
os.environ['DAILY_REVIEW_PROFILE']='P31'
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'refund-close-concurrency'))
from support import *
assert len(sys.argv)==3 and sys.argv[1]=='NON_PRODUCTION_ONLY'
stage=sys.argv[2];assert stage in ('init','cost','rejection','closing','verify')
assert not (OUT/(stage+'.json')).exists(),'Inspect checkpoint, never replay a completed stage'
old=OUT.parent/'factory-data-p30'
cred=json.loads((old/'finance-credentials.private.json').read_text());finance,fin=login(cred['username'],cred['password']);assert fin['userId']!=identity['userId']
def mutation(key,s,method,path,payload=None):
 if key not in steps:steps[key]=call(s,method,path,payload or {});persist()
 return steps[key]
def negative(key,s,method,path,payload=None,status=409):
 before=snapshot();response=send(s,method,path,payload or {});after=snapshot()
 save(key+'.json',{'response':response,'before':before,'after':after})
 assert response['status']==status and before==after,(key,response)
 return response
def open_oct(key):
 p=call(admin,'GET','/finance/accounting-periods/2026/10')
 if p['status']=='CLOSED':period('reopen',key)
 else:assert p['status']=='OPEN'
def voucher(order,key):
 end=time.monotonic()+20
 while time.monotonic()<end:
  with dbconnect() as d:rows=d.execute("select id,status from vouchers where factory_id=%s and source_business_type='RETURN_ORDER' and source_business_id=%s and deleted_at is null",(FACTORY,order['id'])).fetchall()
  if rows:assert len(rows)==1;return rows[0]
  time.sleep(.1)
 raise AssertionError('Expected existing async return voucher')
def close_oct(key):
 p=call(admin,'GET','/finance/accounting-periods/2026/10')
 if p['status']=='OPEN':step(key+'-window',admin,'/finance/month-close/execute',{'year':2026,'month':10})
 period('force-lock-close',key+'-lock')
 return check_finance()
def check_finance():
 report=call(admin,'GET','/finance/report/income-statement?startYear=2026&startMonth=10&endYear=2026&endMonth=10')
 with dbconnect() as d:
  shipped=d.execute("select coalesce(sum(e.quantity_used*b.unit_cost),0) from batch_lineage_edges e join finished_goods_batches b on b.id=e.source_id and b.factory_id=e.factory_id join sales_delivery_records r on r.id=e.target_id and r.factory_id=e.factory_id where e.factory_id=%s and e.source_type='FINISHED_BATCH' and e.target_type='SHIPMENT_RECORD' and e.edge_type='FINISHED_TO_SHIPMENT' and e.deleted_at is null and r.deleted_at is null and r.status in ('SHIPPED','DELIVERED') and r.delivery_date between '2026-10-01' and '2026-10-31'",(FACTORY,)).fetchone()[0]
  returned=d.execute("select coalesce(sum(i.quantity*b.unit_cost),0) from return_order_items i join return_orders r on r.id=i.return_order_id join finished_goods_batches b on b.return_order_item_id=i.id and b.factory_id=r.factory_id where r.factory_id=%s and r.status='COMPLETED' and r.return_type='SALES_RETURN' and r.with_goods=true and r.return_date between '2026-10-01' and '2026-10-31' and i.deleted_at is null and r.deleted_at is null and b.deleted_at is null",(FACTORY,)).fetchone()[0]
  expense=d.execute("select coalesce(sum(actual_loss),0) from disposal_records where factory_id=%s and approval_status='APPROVED' and deleted_at is null and disposal_date between '2026-10-01' and '2026-10-31 23:59:59'",(FACTORY,)).fetchone()[0]
 cogs=(shipped-returned).quantize(Decimal('.01'),rounding=ROUND_HALF_UP)
 assert Decimal(str(report['totalRevenue']))==720 and Decimal(str(report['totalCost']))==cogs,(report,cogs)
 assert Decimal(str(report['totalExpense']))==expense and Decimal(str(report['netProfit']))==720-cogs-expense,(report,expense)
 if expense>0:
  assert any(row['accountCode']=='6711' and Decimal(str(row['amount']))==expense for row in report['expenses'])
  assert all(row['accountCode']!='6711' for row in report['costs'])
 p=call(admin,'GET','/finance/accounting-periods/2026/10');frozen=json.loads(p['incomeStatementSnapshot'])
 for field in ('totalRevenue','totalCost','totalExpense','netProfit'):assert Decimal(str(frozen[field]))==Decimal(str(report[field])),(field,frozen,report)
 assert p['closingPostedAt'] and p['reportReadyAt']
 return {'income':report,'period':p,'shippedRaw':str(shipped),'returnedRaw':str(returned),'expectedCogs':str(cogs),'expectedDisposalExpense':str(expense)}
if stage=='init':
 save('source-before.json',snapshot(SOURCE));save('clone-before.json',snapshot())
 prior=json.loads((old/'cost-partial.json').read_text());prepared=json.loads((old/'prepared.json').read_text())
 with dbconnect() as d:
  batch=d.execute('select id,production_plan_id,unit_cost from finished_goods_batches where factory_id=%s and id=%s',(FACTORY,prior['batchB'][0])).fetchone()
  plan=d.execute('select actual_other_cost from production_plans where factory_id=%s and id=%s',(FACTORY,batch[1])).fetchone()
  child=d.execute('select b.id from finished_goods_batches b join return_order_items i on i.id=b.return_order_item_id where b.factory_id=%s and i.return_order_id=%s',(FACTORY,prepared['returnOrder'])).fetchall()
 assert len(child)==1 and batch[2]>0
 metric=call(admin,'GET','/indicators/FACTORY_ACTUAL_TOTAL_COST/value?periodStart=2026-10-16&periodEnd=2026-10-16')
 save('facts.json',{'sourceBatch':batch[0],'plan':batch[1],'unitCostBefore':str(batch[2]),'otherCostBefore':str(plan[0] or 0),'returnedBatch':child[0][0],'customer':prepared['customer'],'sourceOrder':prepared['salesOrder'],'sku':prior['sku'],'productionCostMetricBefore':metric})
 result={'baselineProtected':True,'source':SOURCE}
elif stage=='cost':
 f=json.loads((OUT/'facts.json').read_text());cost=Decimal(f['otherCostBefore'])+20
 endpoint='/production-plans/'+f['plan']+'/costs?otherCost='+str(cost)
 if 'cost-first' not in steps:negative('cost-locked',admin,'PUT',endpoint)
 open_oct('cost-reopen');first=mutation('cost-first',admin,'PUT',endpoint)
 with dbconnect() as d:
  rows=d.execute('select b.id,b.unit_cost from return_order_items i join return_orders r on r.id=i.return_order_id join finished_goods_batches b on b.return_order_item_id=i.id and b.factory_id=r.factory_id where r.factory_id=%s and i.source_finished_goods_batch_id=%s and r.status=%s',(FACTORY,f['sourceBatch'],'COMPLETED')).fetchall()
  source=d.execute('select unit_cost from finished_goods_batches where factory_id=%s and id=%s',(FACTORY,f['sourceBatch'])).fetchone()[0]
 assert len(rows)>=2 and all(r[1]==source for r in rows) and source>Decimal(f['unitCostBefore'])
 record=step('cost-disposal',admin,'/disposal-records',{'finishedGoodsBatchId':f['returnedBatch'],'disposalType':'SCRAP','disposalQuantity':.5,'disposalReason':'P31退货隔离品报废与成本更正同步，本地演练'})
 # Provision only this local test account through the existing administrator API.
 mutation('cost-finance-quality-access',admin,'PUT','/canvas/user-module-access/'+str(fin['userId'])+'/quality_inspection?action=GRANT&remark=P31-local-acceptance')
 approved=mutation('cost-disposal-approve',finance,'PUT','/disposal-records/'+str(record['id'])+'/approve')
 assert str(approved['approvedBy'])==str(fin['userId'])
 negative('cost-disposal-duplicate',finance,'PUT','/disposal-records/'+str(record['id'])+'/approve',status=409)
 with dbconnect() as d:original=d.execute("select id,status,total_debit from vouchers where factory_id=%s and source_business_type='DISPOSAL_RECORD' and source_business_id=%s",(FACTORY,str(record['id']))).fetchone()
 assert original and original[1]=='POSTED' and original[2]==(source/2).quantize(Decimal('.01'),rounding=ROUND_HALF_UP)
 second=mutation('cost-second',admin,'PUT','/production-plans/'+f['plan']+'/costs?otherCost='+str(cost+20))
 with dbconnect() as d:
  original_after=d.execute('select status,reversal_voucher_id from vouchers where id=%s',(original[0],)).fetchone()
  revised=d.execute("select id,status,total_debit from vouchers where factory_id=%s and source_business_type='DISPOSAL_RECORD_REVISION' and source_business_id like %s and status='POSTED'",(FACTORY,str(record['id'])+'#%')).fetchall()
 assert original_after[0]=='REVERSED' and original_after[1] and len(revised)==1
 before=snapshot();mutation('cost-repeat',admin,'PUT','/production-plans/'+f['plan']+'/costs?otherCost='+str(cost+20));after=snapshot()
 assert before['vouchers']==after['vouchers'] and before['voucher_entries']==after['voucher_entries'],'Repeated cost correction duplicated accounting'
 metric=call(admin,'GET','/indicators/FACTORY_ACTUAL_TOTAL_COST/value?periodStart=2026-10-16&periodEnd=2026-10-16')
 assert Decimal(str(metric['value']))==Decimal(str(f['productionCostMetricBefore']['value']))+40,(metric,f['productionCostMetricBefore'])
 result={'first':first,'second':second,'disposal':approved,'originalVoucher':original,'reversal':original_after,'revision':revised,'productionCostMetricAfter':metric,'finance':close_oct('cost-final')}
elif stage=='rejection':
 open_oct('reject-reopen');f=json.loads((OUT/'facts.json').read_text());results=[]
 for label,posted in [('draft',False),('posted',True),('finance',False)]:
  order=step('reject-'+label+'-create',admin,'/return-orders',{'returnType':'SALES_RETURN','counterpartyId':f['customer'],'sourceOrderId':f['sourceOrder'],'returnDate':date.today().isoformat(),'reason':'P31驳回与凭证原子核对','withGoods':False,'items':[{'productTypeId':f['sku']['id'],'itemName':f['sku']['name'],'quantity':1,'unitPrice':15}]})
  vid,status=voucher(order,label);step('reject-'+label+'-submit',admin,'/return-orders/'+order['id']+'/submit')
  # A submitted return cannot normally be posted; retain that evidence guard.
  if posted:negative('reject-uncompleted-post-blocked',admin,'POST','/finance/vouchers/'+vid+'/post')
  if label=='finance':
   step('reject-'+label+'-approve',admin,'/return-orders/'+order['id']+'/approve')
   path='/return-orders/'+order['id']+'/finance-reject'
  else:path='/return-orders/'+order['id']+'/reject'
  rejected=step('reject-'+label,finance if label=='finance' else admin,path)
  v=call(admin,'GET','/finance/vouchers/'+vid);assert rejected['status']=='REJECTED' and v['status']=='VOID'
  results.append({'label':label,'order':rejected,'voucher':v})
 result={'cases':results,'finance':close_oct('reject-final')}
elif stage=='closing':
 open_oct('closing-reopen');f=json.loads((OUT/'facts.json').read_text())
 order=step('closing-return',admin,'/return-orders',{'returnType':'SALES_RETURN','counterpartyId':f['customer'],'sourceOrderId':f['sourceOrder'],'returnDate':date.today().isoformat(),'reason':'P31待过账及待审调整拦截','withGoods':False,'items':[{'productTypeId':f['sku']['id'],'quantity':1,'unitPrice':15}]})
 vid,status=voucher(order,'closing');step('closing-submit',admin,'/return-orders/'+order['id']+'/submit');step('closing-approve',admin,'/return-orders/'+order['id']+'/approve')
 step('closing-window',admin,'/finance/month-close/execute',{'year':2026,'month':10})
 negative('closing-draft-blocked',admin,'POST','/finance/accounting-periods/force-lock-close',{'year':2026,'month':10})
 step('closing-reject-finance',finance,'/return-orders/'+order['id']+'/finance-reject')
 adjustment=step('closing-manual-pending',admin,'/finance/adjustment?counterpartyType=CUSTOMER',{'counterpartyId':f['customer'],'amount':1,'remark':'P31最终锁账待审调整拦截，本地完成后驳回'})
 pending=[adjustment]
 negative('closing-self-approval',admin,'POST','/finance/adjustment/'+pending[0]['id']+'/approve',status=403)
 # Let the actual 20-day window expire in the existing request-scoped clock.
 # No direct period row/date mutation is used to manufacture the locked state.
 try:
  (OUT/'clock.txt').write_text('2026-11-30\n',encoding='utf-8')
  expired=call(admin,'GET','/finance/accounting-periods/2026/10');assert expired['adjustWindowState']=='LOCKED' and not expired['closingPostedAt']
  negative('closing-expired-window-approval',finance,'POST','/finance/adjustment/'+pending[0]['id']+'/approve',status=400)
 finally:(OUT/'clock.txt').write_text('2026-10-18\n',encoding='utf-8')
 negative('closing-pending-adjustment-blocked',admin,'POST','/finance/accounting-periods/force-lock-close',{'year':2026,'month':10})
 step('closing-manual-reject',finance,'/finance/adjustment/'+pending[0]['id']+'/reject?reason=P31-local-acceptance-complete')
 result={'finance':close_oct('closing-final')}
else:
 result=check_finance();f=json.loads((OUT/'facts.json').read_text());read=financial_read(f['customer'])
 assert Decimal(read['customerBalance'])==0 and Decimal(read['pendingDraftAr'])==0 and Decimal(read['postedBookCash'])==0
 result['financeReadback']=read
 tenant_results=[]
 for method,path in [('PUT','/production-plans/'+f['plan']+'/costs?otherCost=9999'),('POST','/finance/adjustment?counterpartyType=CUSTOMER')]:
  before=snapshot();response=admin.request(method,'http://127.0.0.1:10068/api/mobile/VISION-ENTRY-B'+path,json={'counterpartyId':f['customer'],'amount':1,'remark':'P31隔离反例'},timeout=30)
  assert response.status_code==403 and snapshot()==before,(response.status_code,response.text)
  tenant_results.append({'method':method,'path':path,'status':response.status_code,'exactRollback':True})
 result['crossTenantNegativeControls']=tenant_results
assert snapshot(SOURCE)==json.loads((OUT/'source-before.json').read_text(encoding='utf-8')),'Original P30 source mutated'
save(stage+'.json',{'verdict':'PASS','stage':stage,'factory':FACTORY,'result':result,'sourceExactUnchanged':True,'sqlBusinessDml':0})
print('P31 '+stage+' PASS')
