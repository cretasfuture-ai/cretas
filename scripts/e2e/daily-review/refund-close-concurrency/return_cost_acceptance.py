"""Physical return cost recovery through ordinary business endpoints in the P30 copy."""
import sys,time
from concurrent.futures import ThreadPoolExecutor
from support import *
assert len(sys.argv)==3 and sys.argv[1]=='NON_PRODUCTION_ONLY' and sys.argv[2] in ('correct','partial','multi')
stage=sys.argv[2];prefix='cost-'+stage;file=OUT/(prefix+'.json');assert not file.exists(),'Inspect existing stage; do not replay'
credentials=json.loads((OUT/'finance-credentials.private.json').read_text());finance,actor=login(credentials['username'],credentials['password'])
def opening():
 p=call(admin,'GET','/finance/accounting-periods/2026/10')
 if p['status']=='CLOSED':period('reopen',prefix+'-reopen')
 else:assert p['status']=='OPEN'
def close(expected):
 p=call(admin,'GET','/finance/accounting-periods/2026/10')
 if p['status']=='OPEN':step(prefix+'-month-close',admin,'/finance/month-close/execute',{'year':2026,'month':10})
 period('force-lock-close',prefix+'-lock')
 income=call(admin,'GET','/finance/report/income-statement?startYear=2026&startMonth=10&endYear=2026&endMonth=10')
 assert Decimal(str(income['totalCost']))==Decimal(expected),(expected,income)
 assert Decimal(str(income['netProfit']))==Decimal(str(income['totalRevenue']))-Decimal(expected)
 snap=snapshot();active=[v for v in snap['vouchers'] if v['factory_id']==FACTORY and v['source_business_type']=='COST_CARRYOVER' and v['status']=='POSTED' and not v.get('original_voucher_id')];assert len(active)==1
 assert Decimal(str(active[0]['total_debit']))==Decimal(expected)
 frozen=snapshot();again=call(admin,'POST','/finance/accounting-periods/force-lock-close',{'year':2026,'month':10});assert snapshot()==frozen
 return {'income':income,'activeCogs':active[0],'idempotentReclose':True}
def finish_return(key,order):
 base='/return-orders/'+order['id'];step(key+'-submit',admin,base+'/submit');step(key+'-approve',admin,base+'/approve');step(key+'-finance',finance,base+'/finance-approve')
 return step(key+'-complete',admin,base+'/complete')
def settle_return(key,order):
 f=call(admin,'GET','/return-orders/'+order['id']+'/financial-follow-up')
 for a in f['adjustments']:
  if a['approvalStatus']=='PENDING':step(key+'-adjustment-'+a['id'],finance,'/finance/adjustment/'+a['id']+'/approve')
 with dbconnect() as d:row=d.execute("select id,status from vouchers where factory_id=%s and source_business_type='RETURN_ORDER' and source_business_id=%s and deleted_at is null",(FACTORY,order['id'])).fetchall()
 if not row:step(key+'-generate',admin,'/finance/vouchers/generate',{'businessType':'RETURN_ORDER','businessId':order['id']})
 return post_source('RETURN_ORDER',order['id'],key+'-post')
def async_waiters(count):
 deadline=time.monotonic()+25
 while time.monotonic()<deadline:
  with dbconnect() as d:rows=d.execute("select pid,wait_event,query,pg_blocking_pids(pid) from pg_stat_activity where datname=current_database() and wait_event='advisory' and query like '%pg_advisory_xact_lock%'").fetchall()
  if len(rows)>=count:return rows
  time.sleep(.1)
 raise AssertionError('Async generation/completion did not queue on the shared factory lock')

# Resume only the final readback of an already completed partial scenario. Never
# replay its listener race or the deliberate failed closing transaction.
if stage=='partial' and prefix+'-lock' in steps:
 listener=json.loads((OUT/(prefix+'-listener-race.json')).read_text(encoding='utf-8'))
 blocked=json.loads((OUT/(prefix+'-unposted-blocked.json')).read_text(encoding='utf-8'))
 assert len(listener['both'])==2 and listener['completion']['status']==200
 assert blocked['response']['status']==409 and blocked['before']==blocked['after']
 master=json.loads((OUT.parent/'factory-data-p14/scenario/business-state.json').read_text(encoding='utf-8'))['entities']
 shipment=steps[prefix+'-shipment'];sale=steps[prefix+'-sale'];ret=steps[prefix+'-return']
 assert call(admin,'GET','/return-orders/'+ret['id'])['status']=='COMPLETED'
 with dbconnect() as d:
  rows=d.execute("select b.id,b.batch_number,b.unit_cost,e.quantity_used from batch_lineage_edges e join finished_goods_batches b on b.id=e.source_id and b.factory_id=e.factory_id where e.factory_id=%s and e.target_id=%s and e.edge_type='FINISHED_TO_SHIPMENT' and e.deleted_at is null order by b.unit_cost",(FACTORY,shipment['id'])).fetchall()
 assert len(rows)==2 and all(r[3]==2 for r in rows) and [r[2] for r in rows]==[Decimal('6.4165'),Decimal('7.4165')]
 # Original exact net cost is 346.1167, rather than the rounded 346.12
 # or an assumed 346.115. Add and subtract native costs before one rounding.
 expected=(Decimal('346.1167')+2*rows[0][2]+2*rows[1][2]-rows[0][2]).quantize(Decimal('.01'))
 assert expected==Decimal('367.37')
 result=close(str(expected));assert result['income']['totalRevenue']==765
 save(file.name,{'verdict':'PASS','sale':sale,'shipment':shipment,'sku':master['sku_b'],'customer':steps[prefix+'-customer']['id'],'batchA':rows[0][:3],'batchB':rows[1][:3],'firstReturn':ret,'result':result,'unpostedReturnBlocksAtomically':True,'resumedFinalReadbackOnly':True})
 assert snapshot(SOURCE)==json.loads((OUT/'source-before.json').read_text(encoding='utf-8'))
 print(prefix+' PASS');sys.exit(0)
if stage=='correct':
 opening();before=call(admin,'GET','/finance/report/income-statement?startYear=2026&startMonth=10&endYear=2026&endMonth=10')
 result=close('346.12');assert result['income']['totalRevenue']==720 and result['income']['netProfit']==373.88
 save(file.name,{'verdict':'PASS','beforeReclose':before,'result':result,'fullPhysicalReturnRestoredCost':True})
elif stage=='partial':
 opening();master=json.loads((OUT.parent/'factory-data-p14/scenario/business-state.json').read_text(encoding='utf-8'))['entities'];sku=master['sku_b'];today=date.today().isoformat();note='P30独立副本部分及多批次实物退回成本验收'
 with dbconnect() as d:rows=d.execute("select id,batch_number,unit_cost from finished_goods_batches where factory_id=%s and product_type_id=%s and status='AVAILABLE' and deleted_at is null and produced_quantity-shipped_quantity-reserved_quantity>=2 order by unit_cost",(FACTORY,sku['id'])).fetchall()
 assert len(rows)==2,rows
 batch_a,batch_b=rows;customer=step(prefix+'-customer',admin,'/customers',{'name':'P30多批次部分退货客户','status':'ACTIVE','notes':note})
 sale=step(prefix+'-sale',admin,'/sales/orders',{'customerId':customer['id'],'orderDate':today,'requiredDeliveryDate':today,'processingMode':'STANDARD_SALE','materialSupplyMode':'FACTORY_SUPPLIED','remark':note,'items':[{'productTypeId':sku['id'],'quantity':4,'unit':sku['unit'],'unitPrice':15,'taxRate':0,'sourceWarehouseCode':'WH-FG'}]})
 step(prefix+'-confirm',admin,'/sales/orders/'+sale['id']+'/confirm');sale=call(admin,'GET','/sales/orders/'+sale['id'])
 delivery=step(prefix+'-delivery',admin,'/sales/deliveries',{'idempotencyKey':prefix+'-master','salesOrderId':sale['id'],'customerId':customer['id'],'deliveryDate':today,'deliveryAddress':'P30本地虚拟地址','remark':note,'items':[{'salesOrderItemId':l['id'],'productTypeId':l['productTypeId'],'deliveredQuantity':l['quantity'],'unit':l['unit'],'unitPrice':l['unitPrice'],'sourceWarehouseCode':'WH-FG'} for l in sale['items']]})
 delivery=call(admin,'GET','/sales/deliveries/'+delivery['id']);shipment=step(prefix+'-shipment',admin,'/sales/deliveries/'+delivery['id']+'/shipments',{'idempotencyKey':prefix+'-shipment','plannedShipmentDate':today,'deliveryMode':'FULL','deliveryMethod':'SELF_PICKUP','remark':note,'items':[{'parentDeliveryItemId':l['id'],'quantity':l['deliveredQuantity']} for l in delivery['items']]})
 shipment=call(admin,'GET','/sales/deliveries/'+shipment['id'])
 for l in shipment['items']:step(prefix+'-allocation',admin,'/sales-deliveries/items/'+str(l['id'])+'/batch-allocations',{'allocations':[{'finishedGoodsBatchId':b[0],'allocatedQty':2} for b in rows]})
 step(prefix+'-shipped',admin,'/warehouse/deliveries/'+shipment['id']+'/confirm',{'actualQuantities':{str(l['id']):4 for l in shipment['items']}})
 payment=step(prefix+'-receipt',admin,'/finance/payments/record',{'salesOrderId':sale['id'],'amount':60,'paymentMethod':'BANK_TRANSFER','paymentDate':today,'paymentReference':prefix+'-receipt','remark':note});step(prefix+'-verify-receipt',finance,'/finance/payments/'+payment['id']+'/verify')
 with dbconnect() as d:arid=d.execute("select id from ar_ap_transactions where factory_id=%s and sales_order_id=%s and transaction_type='AR_PAYMENT' and deleted_at is null",(FACTORY,sale['id'])).fetchone()[0]
 post_source('SALES_ORDER',sale['id'],prefix+'-sale-post');post_source('CASH_RECEIPT',arid,prefix+'-cash-post')
 with ThreadPoolExecutor(max_workers=1) as pool:
  with dbconnect(readonly=False) as blocker:
   blocker.execute('select pg_advisory_xact_lock(hashtextextended(%s,0))',('production-cost-finance:'+FACTORY,))
   ret=step(prefix+'-return',admin,'/return-orders',{'returnType':'SALES_RETURN','counterpartyId':customer['id'],'sourceOrderId':sale['id'],'returnDate':today,'reason':note,'withGoods':True,'remark':'SHIPMENT_ID='+shipment['id']+'; '+note,'items':[{'productTypeId':sku['id'],'itemName':sku['name'],'batchNumber':batch_a[1],'quantity':1,'unitPrice':15}]})
   first=async_waiters(1);base='/return-orders/'+ret['id'];step(prefix+'-submit',admin,base+'/submit');step(prefix+'-approve',admin,base+'/approve');step(prefix+'-finance',finance,base+'/finance-approve')
   complete=pool.submit(send,admin,'POST',base+'/complete',{});both=async_waiters(2);assert not complete.done()
  completed=complete.result();save(prefix+'-listener-race.json',{'first':first,'both':both,'completion':completed});assert completed['status']==200 and completed['body']['success'],completed
  steps[prefix+'-complete']=completed['body']['data'];persist()
 # Missing/DRAFT income voucher must roll back the entire force-close transaction.
 step(prefix+'-window',admin,'/finance/month-close/execute',{'year':2026,'month':10});before=snapshot();negative=send(admin,'POST','/finance/accounting-periods/force-lock-close',{'year':2026,'month':10})
 save(prefix+'-unposted-blocked.json',{'response':negative,'before':before,'after':snapshot()});assert negative['status']==409 and snapshot()==before,negative
 settle_return(prefix,ret);result=close('367.37');assert result['income']['totalRevenue']==765
 save(file.name,{'verdict':'PASS','sale':sale,'shipment':shipment,'sku':sku,'customer':customer['id'],'batchA':batch_a,'batchB':batch_b,'firstReturn':ret,'result':result,'unpostedReturnBlocksAtomically':True})
elif stage=='multi':
 prior=json.loads((OUT/'cost-partial.json').read_text(encoding='utf-8'));sku=prior['sku'];note='P30两个原生盒装批次同时退回，收入与成本分别核对'
 ret=step(prefix+'-return',admin,'/return-orders',{'returnType':'SALES_RETURN','counterpartyId':prior['customer'],'sourceOrderId':prior['sale']['id'],'returnDate':date.today().isoformat(),'reason':note,'withGoods':True,'remark':'SHIPMENT_ID='+prior['shipment']['id']+'; '+note,'items':[{'productTypeId':sku['id'],'itemName':sku['name'],'batchNumber':b[1],'quantity':q,'unitPrice':15} for b,q in [(prior['batchA'],1),(prior['batchB'],2)]]})
 base='/return-orders/'+ret['id'];step(prefix+'-submit',admin,base+'/submit');step(prefix+'-approve',admin,base+'/approve');step(prefix+'-finance',finance,base+'/finance-approve')
 before=snapshot();blocked=send(admin,'POST',base+'/complete',{});save(prefix+'-locked-complete.json',{'response':blocked,'before':before,'after':snapshot()});assert blocked['status']==400 and '期间已结账' in blocked['body']['message'] and snapshot()==before,blocked
 opening();step(prefix+'-complete',admin,base+'/complete');settle_return(prefix,ret)
 unchanged=snapshot();repeat=send(admin,'POST',base+'/complete',{});assert repeat['status']==409 and snapshot()==unchanged
 for label,order,amount in [('partial',prior['firstReturn'],15),('remaining',ret,45)]:
  refund_base='/return-orders/'+order['id']+'/refund';step(prefix+'-'+label+'-refund',admin,refund_base,{'amount':amount,'remark':'P30多批次退货正常退款结清'})
  step(prefix+'-'+label+'-submit-refund',admin,refund_base+'/submit');step(prefix+'-'+label+'-approve-refund',finance,refund_base+'/approve')
  paid=step(prefix+'-'+label+'-paid',finance,refund_base+'/mark-paid',{'paymentMethod':'BANK_TRANSFER','paymentReference':prefix+'-'+label+'-refund','remark':'P30本地正常退款，不真实转账'})
  post_source('RETURN_REFUND',paid['request']['id'],prefix+'-'+label+'-refund-post')
 result=close('346.12');assert result['income']['totalRevenue']==720 and result['income']['netProfit']==373.88
 save(file.name,{'verdict':'PASS','return':ret,'result':result,'lockedPhysicalReturnRollback':True,'duplicateCompletionNoop':True})
assert snapshot(SOURCE)==json.loads((OUT/'source-before.json').read_text(encoding='utf-8'))
print(prefix+' PASS')
