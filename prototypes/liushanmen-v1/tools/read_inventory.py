"""Project existing committed OpenAPI inventory into this prototype only."""
import json,os
from pathlib import Path
from read_source import read
ROOT=Path(__file__).resolve().parents[1]
v,p=read()
binding=json.loads((ROOT/'private/binding.json').read_text('utf8'))
if str(v['source_factory_id'])!=binding['sourceFactoryId']:raise ValueError('SOURCE_FACTORY_MISMATCH')
d=p['data']
if any(str(t.get('orgId'))!=binding['sourceFactoryId'] for t in d['task']):raise ValueError('TASK_FACTORY_MISMATCH')
def pick(x,fields):return {k:x.get(k) for k in fields.split()}
orders=[]
for x in d['stock_order']:
    item=pick(x,'id orderNo orderType bizTypeDisplay bizAt orderStatus orderStatusDisplay approvedAt createdAt updatedAt')
    item['lines']=[pick(l,'id productId productCode productName unitName qty realCostAmount associatedBizOrderId associatedBizOrderCode detailWarehouseId') for l in (x.get('orderDetails') or [])]
    orders.append(item)
out={'factoryId':'LIUSHANMEN','sourceFactoryId':binding['sourceFactoryId'],'revision':p['contentHash'],'checkedAt':p['checkedAt'],'materialAt':p.get('materialAt'),'stock':[pick(x,'productId productName productCode warehouseId warehouseName warehouseCode qtyInWarehouse unitName') for x in d['stock']],'orders':orders,'warehouses':[pick(x.get('simpleVO') or {},'id code name status') for x in d['warehouse']]}
target=ROOT/'private/inventory-current.json';temp=target.with_suffix('.tmp');temp.write_text(json.dumps(out,ensure_ascii=False),encoding='utf8');os.replace(temp,target)
print(json.dumps({'factoryId':out['factoryId'],'stock':len(out['stock']),'orders':len(orders),'warehouses':len(out['warehouses'])}))
