"""One real sale, verified receipt, physical return and independently approved refund."""
import sys,secrets
from support import *
assert sys.argv[1:]==['NON_PRODUCTION_ONLY']
if not (OUT/'source-before.json').exists():save('source-before.json',snapshot(SOURCE))
if not (OUT/'clone-before.json').exists():save('clone-before.json',snapshot())
financefile=OUT/'finance-credentials.private.json'
if not financefile.exists():
 credentials={'username':'p30_factory_finance','password':secrets.token_urlsafe(24)}
 financefile.write_text(json.dumps(credentials),encoding='utf-8')
else:credentials=json.loads(financefile.read_text())
fin_user=step('finance-user',admin,'/users',{**credentials,'email':'p30-finance@example.invalid','fullName':'P30本地财务审核人','roleCode':'finance_manager','isActive':True})
finance,fin_identity=login(credentials['username'],credentials['password']);assert fin_identity['userId']!=2
save('actors.json',{'applicant':{k:identity[k] for k in ('userId','factoryId','username')},'finance':{k:fin_identity[k] for k in ('userId','factoryId','username')}})
period('reopen','prepare-reopen')
masters=json.loads((OUT.parent/'factory-data-p14/scenario/business-state.json').read_text(encoding='utf-8'))['entities'];sku=masters['sku_b']
with dbconnect() as d:
 candidates=d.execute("select id,batch_number from finished_goods_batches where factory_id=%s and product_type_id=%s and deleted_at is null and produced_quantity-shipped_quantity-reserved_quantity>=1 and status='AVAILABLE' order by batch_number desc",(FACTORY,sku['id'])).fetchall()
assert candidates,'No released finished-goods batch; do not fake stock'
batch_id,batch_number=candidates[0];today=date.today().isoformat();note='P30本地退款结账并发模拟，无真实收付款'
customer=step('customer',admin,'/customers',{'name':'P30独立退款并发验收客户','status':'ACTIVE','creditLimit':10000,'notes':note})
sale=step('sale',admin,'/sales/orders',{'customerId':customer['id'],'orderDate':today,'requiredDeliveryDate':today,'processingMode':'STANDARD_SALE','materialSupplyMode':'FACTORY_SUPPLIED','remark':note,'items':[{'productTypeId':sku['id'],'quantity':1,'unit':sku['unit'],'unitPrice':15,'taxRate':0,'sourceWarehouseCode':'WH-FG'}]})
assert step('sale-confirm',admin,'/sales/orders/'+sale['id']+'/confirm')['status']=='FINANCE_APPROVED'
sale=call(admin,'GET','/sales/orders/'+sale['id'])
delivery=step('delivery',admin,'/sales/deliveries',{'idempotencyKey':'p30-refund-master','salesOrderId':sale['id'],'customerId':customer['id'],'deliveryDate':today,'deliveryAddress':'P30本地虚拟地址','remark':note,'items':[{'salesOrderItemId':l['id'],'productTypeId':l['productTypeId'],'deliveredQuantity':l['quantity'],'unit':l['unit'],'unitPrice':l['unitPrice'],'sourceWarehouseCode':'WH-FG'} for l in sale['items']]})
delivery=call(admin,'GET','/sales/deliveries/'+delivery['id'])
shipment=step('shipment',admin,'/sales/deliveries/'+delivery['id']+'/shipments',{'idempotencyKey':'p30-refund-shipment','plannedShipmentDate':today,'deliveryMode':'FULL','deliveryMethod':'SELF_PICKUP','remark':note,'items':[{'parentDeliveryItemId':l['id'],'quantity':l['deliveredQuantity']} for l in delivery['items']]})
shipment=call(admin,'GET','/sales/deliveries/'+shipment['id'])
for l in shipment['items']:step('allocation-'+str(l['id']),admin,'/sales-deliveries/items/'+str(l['id'])+'/batch-allocations',{'allocations':[{'finishedGoodsBatchId':batch_id,'allocatedQty':1}]})
step('shipped',admin,'/warehouse/deliveries/'+shipment['id']+'/confirm',{'actualQuantities':{str(l['id']):l['deliveredQuantity'] for l in shipment['items']}})
payment=step('receipt',admin,'/finance/payments/record',{'salesOrderId':sale['id'],'amount':15,'paymentMethod':'BANK_TRANSFER','paymentDate':today,'paymentReference':'P30-RECEIPT-15','remark':note})
step('receipt-verified',finance,'/finance/payments/'+payment['id']+'/verify')
with dbconnect() as d:arid=d.execute("select id from ar_ap_transactions where factory_id=%s and sales_order_id=%s and transaction_type='AR_PAYMENT' and deleted_at is null",(FACTORY,sale['id'])).fetchone()[0]
post_source('CASH_RECEIPT',arid,'receipt-post');post_source('SALES_ORDER',sale['id'],'sale-post')
ret=step('return',admin,'/return-orders',{'returnType':'SALES_RETURN','counterpartyId':customer['id'],'sourceOrderId':sale['id'],'returnDate':today,'reason':note,'withGoods':True,'remark':note+' SHIPMENT_ID='+shipment['id']+';','items':[{'productTypeId':sku['id'],'itemName':sku['name'],'batchNumber':batch_number,'quantity':1,'unitPrice':15}]})
rbase='/return-orders/'+ret['id'];step('return-submit',admin,rbase+'/submit');step('return-approve',admin,rbase+'/approve');step('return-finance',finance,rbase+'/finance-approve');step('return-complete',admin,rbase+'/complete')
follow=call(admin,'GET',rbase+'/financial-follow-up')
for row in follow['adjustments']:
 if row['approvalStatus']=='PENDING':step('adjustment-'+row['id'],finance,'/finance/adjustment/'+row['id']+'/approve')
post_source('RETURN_ORDER',ret['id'],'return-post')
step('refund-first',admin,rbase+'/refund',{'amount':15,'remark':note});step('refund-first-submit',admin,rbase+'/refund/submit');approved=step('refund-first-approved',finance,rbase+'/refund/approve')
assert approved['request']['status']=='APPROVED'
read=financial_read(customer['id']);assert Decimal(read['customerBalance'])==-15
save('prepared.json',{'verdict':'PASS','customer':customer['id'],'salesOrder':sale['id'],'returnOrder':ret['id'],'refundRequest':approved['request']['id'],'batch':batch_id,'shipment':shipment['id'],'readback':read,'normalBusinessApisOnly':True})
assert snapshot(SOURCE)==json.loads((OUT/'source-before.json').read_text(encoding='utf-8'))
print('P30 normal API preparation PASS; independent finance approval and source preserved')
