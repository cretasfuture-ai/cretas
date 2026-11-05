"""Offline evidence inventory; detailed source values stay in CurrentUser DPAPI."""
import json
from collections import Counter,defaultdict
from .full_verify import offline_context
from .full_archive import Archive
from .secrets import load_local_secret,save_local_secret
from .analytics import number


def main():
    directory,store=offline_context()
    try:
        run=load_local_secret(directory/'full-run.dpapi')
        archive=Archive(None,store,{},run['run_id'])
        data={r:archive.raw_rows(r) for r in ('project','task','output','stock_order','materials','bom','product','routing')}
        projects={str(p['id']):p for p in data['project']}
        products={str(p['id']):p for p in data['product']}
        all_lines=[];unlinked=[];zero=[]
        for order in data['stock_order']:
            for detail in order.get('orderDetails') or []:
                record={'order':order,'detail':detail}
                all_lines.append(record)
                if str(order.get('bizType')) not in ('30','32'): continue
                parent=projects.get(str(detail.get('associatedBizOrderId')))
                if parent is None or detail.get('associatedBizOrderCode')!=parent['projectCode'] or str(detail.get('associatedBizOrderType'))!='10':
                    unlinked.append(record)
                elif number(detail.get('changedCostAmountSigned'))==0:
                    zero.append(record)
        zero_products={str(r['detail'].get('productId')) for r in zero}
        incoming=defaultdict(list)
        for record in all_lines:
            if record['order'].get('orderType')==10:
                incoming[str(record['detail'].get('productId'))].append(record)
        positive_reference={p for p in zero_products if any(any((number(r['detail'].get(f)) or 0)>0 for f in ('costUnitPrice','realCostUnitPrice','taxExclusiveAmount','taxInclusiveAmount')) for r in incoming[p])}
        report={'run_id':run['run_id'],'tenant_id':store.scope[0],'source_factory_id':store.scope[1],
                'unlinked':unlinked,'zero_material_lines':zero,
                'zero_product_definitions':[products[p] for p in zero_products if p in products],
                'incoming_candidates':{p:incoming[p] for p in zero_products},
                'materials':data['materials'],'bom':data['bom']}
        save_local_secret(directory/'gap-offline.dpapi',report)
        detail_keys=sorted({k for r in unlinked for k in r['detail']})
        def nested_shapes(value):
            if isinstance(value,dict): return {k:type(v).__name__ for k,v in value.items()}
            if isinstance(value,list): return [nested_shapes(v) for v in value[:1]]
            return type(value).__name__
        print(json.dumps({'unlinked_lines':len(unlinked),'zero_lines':len(zero),'zero_products':len(zero_products),
                          'products_with_positive_incoming_reference':len(positive_reference),
                          'stock_business_types':sorted({(o.get('bizType'),o.get('bizTypeDisplay')) for o in data['stock_order']}),
                          'unlinked_shapes':[{'order':{k:nested_shapes(r['order'].get(k)) for k in ('originIdList','originCodeList','originType','bizType','warehouse')},
                                             'detail':{k:nested_shapes(r['detail'].get(k)) for k in ('associatedBizOrderId','associatedBizOrderType','originDetailId','originType','originDetailInfo','originDetailDisplayInfo','detailWarehouseId')}} for r in unlinked],
                          'materials_fields':sorted(data['materials'][0]) if data['materials'] else [],
                          'bom_fields':sorted(data['bom'][0]) if data['bom'] else [],
                          'price_source_codes':dict(Counter(str(r['detail'].get('costUnitPriceSource')) for r in zero)),
                          'source_requests':0},ensure_ascii=False))
    finally:store.connection.close()


if __name__=='__main__':main()
