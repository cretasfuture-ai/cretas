"""Evidence-only warehouse reconciliation; visible ledger is not physical stock."""
from decimal import Decimal
from .analytics import number, serial
from .period_projection import instant


def reconcile(stock, orders, checked_at, opening=None, tracked=None,units=None):
    selected={(str(r['productId']),str(r['warehouseId'])):r for r in stock
              if number(r.get('qtyInWarehouse')) is not None and number(r['qtyInWarehouse'])<0}
    for r in tracked or []:
        key=(str(r['productId']),str(r['warehouseId']))
        found=next((s for s in stock if (str(s['productId']),str(s['warehouseId']))==key),None)
        selected[key]=found or {**r,'qtyInWarehouse':None}
    result=[]
    for (pid,wid),row in selected.items():
        unit=row.get('unitName');quantity=number(row.get('qtyInWarehouse'))
        movements=[];issues=[];inbound=Decimal(0);outbound=Decimal(0);seen=set();ambiguous=False
        for order in orders:
            for d in order.get('orderDetails') or []:
                if str(d.get('productId'))!=pid:continue
                detail_wid=d.get('detailWarehouseId')
                header=(order.get('warehouse') or {}).get('id')
                effective=detail_wid if detail_wid is not None else header
                if effective is not None and str(effective)!=wid:continue
                identity=(str(order['id']),str(d.get('id')))
                if identity in seen:raise ValueError('STOCK_RECONCILIATION_DUPLICATE_DETAIL')
                seen.add(identity)
                q=number(d.get('changeQty'));direction='in' if order.get('orderType')==10 else 'out'
                reasons=[]
                if effective is None:reasons.append('未返回明细或单头仓库，不能归入本仓');ambiguous=True
                if str(order.get('orderStatus'))!='1':reasons.append('未审批入账')
                if not unit or d.get('unitName')!=unit:
                    issue=f"{order.get('orderNo')}：单据单位 {d.get('unitName') or '未提供'}，余额单位 {unit or '未提供'}"
                    issues.append(issue);reasons.append('计量单位不一致，缺少已验证换算关系')
                if q is None:reasons.append('未返回库存变动量')
                elif (direction=='in' and q<0) or (direction=='out' and q>0):reasons.append('出入方向与库存变动符号不一致')
                if instant(order.get('bizAt')) is None:reasons.append('业务日期缺失')
                elif instant(order['bizAt'])>instant(checked_at):reasons.append('业务时间晚于余额采集')
                counted=not reasons
                if counted:
                    if q>=0:inbound+=q
                    else:outbound-=q
                header_conflict=detail_wid is not None and header is not None and str(detail_wid)!=str(header)
                note='；'.join(reasons) if reasons else '已审批；按明细仓库计入'
                if header_conflict:note+='；单头仓库与明细不同，请核对实际发货仓'
                movements.append({'id':f'{order["id"]}:{d.get("id")}','orderNo':order.get('orderNo') or str(order['id']),
                    'direction':direction,'bizAt':order.get('bizAt'),'approval':'已审批' if str(order.get('orderStatus'))=='1' else '未审批',
                    'quantity':abs(q) if q is not None else None,'unit':d.get('unitName'),'counted':counted,'reason':note,
                    'warehouseConflict':header_conflict,'associatedOrder':d.get('associatedBizOrderCode'),
                    'originalQuantity':number(d.get('qty'))})
        comparable=not issues and not ambiguous and all(m['counted'] or m['approval']=='未审批' for m in movements)
        net=inbound-outbound if comparable else None
        # No zero opening assumption: all-history net is supporting evidence only.
        conclusion=f'可见已审批流水入库 {inbound:,.2f}、出库 {outbound:,.2f} {unit or "（单位待补）"}。'
        if net is not None and net==quantity and quantity is not None and quantity<0:
            conclusion+='净出库超过入库的数量与负余额一致，已定位到账面来源；是否实物短缺仍需盘点。'
        elif quantity is not None and quantity>=0:
            conclusion+='当前余额已恢复非负；仍保留原负库存核对记录。'
        elif quantity is None:conclusion+='本次未返回这组物料与仓库的余额，当前数量未知；不能按零库存或问题已解决处理。'
        else:conclusion+='现有流水还不能完整解释余额，需补期初或核实调整、漏单及单据范围。'
        reasons=[]
        if any(m['warehouseConflict'] for m in movements):reasons.append('已发现单头仓库与明细仓库不同；余额应按明细仓库核对，实际发货仓仍待确认。')
        other_in=[]
        for order in orders:
            if order.get('orderType')!=10 or str(order.get('orderStatus'))!='1':continue
            for d in order.get('orderDetails') or []:
                dw=d.get('detailWarehouseId') if d.get('detailWarehouseId') is not None else (order.get('warehouse') or {}).get('id')
                if str(d.get('productId'))==pid and dw is not None and str(dw)!=wid:
                    other_in.append(order.get('orderNo') or str(order['id']))
        if other_in and inbound==0:reasons.append('该物料在其他仓库有入库，本仓未查到已计入的入库；可能漏调拨或选错领料仓，尚未证实。涉及 '+ '、'.join(dict.fromkeys(other_in))+'。')
        if issues:reasons.append('单位换算条件不足，不能把不同单位直接相加。')
        elif unit=='个' and quantity is not None and quantity!=quantity.to_integral_value():
            definitions=[u for u in units or [] if u.get('name')==unit]
            decimals=definitions[0].get('decimalLength') if len(definitions)==1 else None
            if type(decimals) is int and 0<=decimals<=12:
                conclusion+=f'黑湖“个”的单位定义允许保留 {decimals} 位小数；当前未发现单据与余额混用单位的证据。'
                reasons.append('是否应该领用小数个，仍需领料经办人核对业务数量和配方依据；不能据此认定换算错误。')
            else:reasons.append('按“个”计量却出现小数余额，需核对允许小数的设置及历史换算；仅凭小数不能认定换算错误。')
        fractional=[m for m in movements if m['quantity'] is not None and m['quantity']!=m['quantity'].to_integral_value()]
        if unit=='个' and fractional:
            reasons.append('已定位小数来源：'+'、'.join(f'{m["orderNo"]} 记录 {m["quantity"]:,.2f} 个' for m in fractional)+'。请领料经办人与主数据管理员核实原单及是否允许小数。')
        if not reasons:reasons.append('尚无证据认定漏单或换算错误；需核对期初、盘点与单据生效范围。')
        result.append({'key':pid+':'+wid,'productId':pid,'productCode':row.get('productCode') or '',
            'product':row.get('productName') or row.get('productCode') or pid,'warehouseKey':wid,
            'warehouse':row.get('warehouseName') or wid,'unit':unit,'quantity':quantity,'checkedAt':checked_at,
            'status':'resolved' if quantity is not None and quantity>=0 else 'needs_review','conclusion':conclusion,
            'possibleReasons':reasons,'owner':'仓库负责人 / 领料或发货经办人；换算问题由物料主数据管理员核对',
            'action':'按下列单号核实实际收发仓、盘点实物，补提供期初库存及调拨/调整凭证；有单位差异时补物料换算表。',
            'afterProvided':'确认是账面漏记、选错仓、换算错误还是实物短缺，并明确需要纠正的原单；本页不修改黑湖。',
            'openingQuantity':None,'openingAt':None,'closingQuantity':quantity,'closingAt':checked_at,
            'inbound':inbound if comparable else None,'outbound':outbound if comparable else None,
            'expectedClosing':None,'difference':None,'unitIssues':issues,
            'movementCoverage':'当前账号可见全部分页出入库；期初、盘点调整及删除/不可见记录完整性未证明，不能假定期初为零。',
            'movements':sorted(movements,key=lambda m:m['bizAt'] or '')})
    return serial(result)
