"""Deterministic production, inventory and raw-material-to-finished cost facts."""
from collections import Counter, defaultdict
from datetime import datetime
from decimal import Decimal, InvalidOperation


def number(value):
    if value is None or value == '' or isinstance(value, bool):
        return None
    try:
        result = Decimal(str(value))
        return result if result.is_finite() else None
    except InvalidOperation:
        return None


def amount(values):
    known = [v for v in values if v is not None]
    return sum(known, Decimal(0)) if known else None


def complete_amount(values):
    return amount(values) if values and all(v is not None for v in values) else None


def serial(value):
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, dict):
        return {k: serial(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [serial(v) for v in value]
    return value


def build(data, metadata):
    projects, tasks, outputs, orders = [data.get(k, []) for k in ('project','task','output','stock_order')]
    from .labor_evidence import evidence
    labor_evidence=evidence(data)
    project_by_id = {p['id']: p for p in projects}
    by_code = {p['projectCode']: p for p in projects}
    if len(by_code)!=len(projects):
        raise ValueError('AMBIGUOUS_PROJECT_CODE')
    process_names = {p['id']: p.get('name') or p.get('code') for p in data.get('process', [])}
    materials, labor = defaultdict(list), defaultdict(list)
    unlinked_candidates=Counter()
    project_by_text_id={str(key):value for key,value in project_by_id.items()}
    movements, unlinked, excluded_orders = [], 0, 0
    for task in tasks:
        if task.get('projectId') in project_by_id:
            labor[task['projectId']].append(task)
    for order in orders:
        if str(order.get('orderStatus')) != '1':
            excluded_orders += 1
            continue
        detail_ids=set()
        for detail in order.get('orderDetails') or []:
            detail_id=detail.get('id')
            if detail_id is None or detail_id in detail_ids:
                raise ValueError('DUPLICATE_OR_MISSING_MOVEMENT_DETAIL_ID')
            detail_ids.add(detail_id)
            # Only documented production picking / return types are costs of production.
            if str(order.get('bizType')) not in ('30', '32'):
                continue
            parent = by_code.get(detail.get('associatedBizOrderCode'))
            quantity = number(detail.get('changeQty'))
            signed = number(detail.get('changedCostAmountSigned'))
            line = {'material':detail.get('productName') or detail.get('productCode'),
                    'materialCode':detail.get('productCode'),'unit':detail.get('unitName'),
                    'netConsumption':-quantity if quantity is not None else None,
                    'recognizedCost':-signed if signed is not None else None,
                    'unitPrice':number(detail.get('costUnitPrice')),
                    'orderCode':order.get('orderNo'),'date':order.get('bizAt'),
                    'bizType':str(order.get('bizType'))}
            if (parent is None or str(detail.get('associatedBizOrderType')) != '10'
                    or str(detail.get('associatedBizOrderId'))!=str(parent['id'])):
                unlinked += 1
                candidates=set()
                if parent: candidates.add(parent['id'])
                id_parent=project_by_text_id.get(str(detail.get('associatedBizOrderId')))
                if id_parent: candidates.add(id_parent['id'])
                for candidate in candidates: unlinked_candidates[candidate]+=1
                continue
            materials[parent['id']].append(line)
            movements.append(line)
    cost_rows = []
    for project in projects:
        lines, steps = materials[project['id']], labor[project['id']]
        material_values = [line['recognizedCost'] for line in lines]
        wages = [number(task.get('sumOutputSalary')) for task in steps]
        material_known = sum(v is not None for v in material_values)
        wage_known = sum(v is not None for v in wages)
        zero_values = sum(v == 0 for v in material_values if v is not None)
        positive_usage = any(line['netConsumption'] is not None and line['netConsumption'] > 0 for line in lines)
        suspicious_zero = bool(lines) and zero_values == len(lines) and positive_usage
        material = amount(material_values)
        wage = amount(wages)
        # Independent project-level finished good quantity, never task/report sums.
        finished = number(project.get('numberofGoodProducts'))
        completed = str(project.get('status')) == '20'
        missing = []
        if unlinked_candidates[project['id']]: missing.append('存在可能属于本工单的领退料关联缺失或冲突')
        if not lines: missing.append('未关联生产领退料')
        elif material_known < len(lines): missing.append('材料金额缺失')
        if any(line['netConsumption'] is None or not line['unit'] for line in lines):
            missing.append('领退料数量或单位缺失')
        if lines and not positive_usage: missing.append('没有正向原料领用证据')
        if any((line['netConsumption']==0 and line['recognizedCost'] not in (None,Decimal(0)))
               or (line['netConsumption'] is not None and line['recognizedCost'] is not None and line['netConsumption']*line['recognizedCost']<0)
               or (line['unitPrice'] is not None and line['unitPrice']<0) for line in lines):
            missing.append('领退料数量、金额符号或单价异常')
        if suspicious_zero: missing.append('存在耗料但材料金额全部为源端零值')
        elif any(line['netConsumption'] is not None and line['netConsumption']!=0
                 and (line['recognizedCost'] in (None,Decimal(0)) or line['unitPrice'] in (None,Decimal(0))) for line in lines):
            missing.append('部分领料金额或单价为零/缺失，估值未核实')
        if material is not None and material<0: missing.append('净材料成本为负，需核对退料与归属')
        if material==0 and finished is not None and finished>0:
            missing.append('已有成品但材料净额为零，需核对领退料与估值')
        if not steps or wage_known < len(steps): missing.append('工序人工成本覆盖不完整')
        if any((number(t.get('outputDurationForSecond')) or 0)>0 and number(t.get('sumOutputSalary')) in (None,Decimal(0)) for t in steps):
            missing.append('部分工序有工时但工资为零或缺失，计价需核实')
        if any(value is not None and value<0 for value in wages): missing.append('工序累计工资存在负值，需核对冲销')
        if not completed: missing.append('工单尚未完工')
        if finished is None or finished <= 0: missing.append('缺少正数成品良品量')
        if not project.get('projectUnit'): missing.append('成品单位缺失')
        # Even complete recognized components are only partial direct costs.
        identified = material + wage if material is not None and wage is not None else None
        unit_cost = identified / finished if not missing and identified is not None else None
        cost_rows.append({'projectId':project['id'],'projectCode':project['projectCode'],
                          'product':project.get('productName') or project.get('productCode'),
                          'productCode':project.get('productCode'),'unit':project.get('projectUnit'),
                          'status':str(project.get('status')),'planned':number(project.get('planAmount')),
                          'actual':number(project.get('actualAmount')),'finishedGood':finished,
                          'materialCost':material,'laborCost':wage,'identifiedDirectCost':identified,
                          'unitIdentifiedDirectCost':unit_cost,'materialLines':len(lines),
                          'materialCostKnownLines':material_known,'sourceZeroCostLines':zero_values,
                          'taskCount':len(steps),'laborCostKnownTasks':wage_known,
                          'materialLinesDetail':lines,
                          'processes':[{'process':t.get('processName') or process_names.get(t.get('processId')),
                                        'status':str(t.get('processStatus')),'good':number(t.get('approvedFineAmount')),
                                        'hours':number(t.get('outputDurationForSecond'))/3600 if number(t.get('outputDurationForSecond')) is not None else None,
                                        'laborCost':number(t.get('sumOutputSalary')),
                                        **({'taskId':t['id'],'laborEvidence':labor_evidence[str(t['id'])]} if str(t['id']) in labor_evidence else {})} for t in steps],
                          'issues':missing,'unitCostAvailable':unit_cost is not None})
    status = Counter(str(p.get('status')) for p in projects)
    quantities = defaultdict(list)
    for row in cost_rows:
        quantities[(row['productCode'],row['product'],row['unit'])].append(row)
    plan_actual = [{'productCode':code,'product':p,'unit':u,'planned':complete_amount([r['planned'] for r in rows]),
                    'actual':complete_amount([r['actual'] for r in rows]),'projects':len(rows),
                    'plannedMissing':sum(r['planned'] is None for r in rows),
                    'actualMissing':sum(r['actual'] is None for r in rows)} for (code,p,u),rows in quantities.items()]
    quality = defaultdict(list)
    for row in outputs:
        if str(row.get('approvalStatus')) == '10':
            quality[(row.get('productCode'),row.get('productName') or row.get('productCode'),row.get('processId'),row.get('stockUnit'))].append(row)
    quality_rows = []
    for (code,product,process,unit), rows in quality.items():
        complete = [r for r in rows if number(r.get('numberofgoodProducts')) is not None and number(r.get('numberofDefectives')) is not None]
        good = amount([number(r['numberofgoodProducts']) for r in complete]) if len(complete)==len(rows) else None
        bad = amount([number(r['numberofDefectives']) for r in complete]) if len(complete)==len(rows) else None
        quality_rows.append({'productCode':code,'product':product,'processId':process,'process':process_names.get(process) or str(process),'unit':unit,
                             'good':good,'defective':bad,'goodRate':good/(good+bad) if good is not None and good+bad>0 else None,
                             'records':len(rows),'missingRecords':len(rows)-len(complete)})
    process_groups = defaultdict(list)
    for row in tasks:
        process_groups[(row.get('processId'),row.get('processName') or process_names.get(row.get('processId')) or '未关联工序')].append(row)
    process_rows = []
    for (process_id,name),rows in process_groups.items():
        seconds = complete_amount([number(r.get('outputDurationForSecond')) for r in rows])
        process_rows.append({'processId':process_id,'process':name,'tasks':len(rows),'hours':seconds/3600 if seconds is not None else None,
                             'projectIds':sorted({r['projectId'] for r in rows if r.get('projectId') in project_by_id},key=str),
                             'inProgress':sum(str(r.get('processStatus'))=='10' for r in rows),
                             'missingDurationTasks':sum(number(r.get('outputDurationForSecond')) is None for r in rows)})
    balances = [{'product':r.get('productName') or r.get('productCode'),'productCode':r.get('productCode'),'warehouse':r.get('warehouseName') or r.get('warehouseCode'),
                 'warehouseKey':r.get('warehouseId') or r.get('warehouseCode') or 'unknown:'+str(i),
                 'warehouseCode':r.get('warehouseCode'),'unit':r.get('unitName'),'quantity':number(r.get('qtyInWarehouse'))} for i,r in enumerate(data.get('stock',[]))]
    inventory_groups=defaultdict(list)
    for row in balances: inventory_groups[(row['warehouseKey'],row['warehouse'],row['unit'])].append(row)
    inventory_totals=[{'warehouseKey':key,'warehouse':w,'unit':u,'quantity':complete_amount([r['quantity'] for r in rows]),
                       'records':len(rows),'missing':sum(r['quantity'] is None for r in rows)} for (key,w,u),rows in inventory_groups.items()]
    material_totals=defaultdict(list)
    for row in movements: material_totals[(row['materialCode'],row['material'],row['unit'])].append(row)
    material_rows=[{'materialCode':code,'material':name,'unit':unit,
                   'netConsumption':complete_amount([r['netConsumption'] for r in rows]),
                   'recognizedCost':amount([r['recognizedCost'] for r in rows]),'lines':len(rows),
                   'missingCostLines':sum(r['recognizedCost'] is None for r in rows),
                   'missingQuantityLines':sum(r['netConsumption'] is None for r in rows),
                   'knownCostLines':sum(r['recognizedCost'] is not None for r in rows)}
                   for (code,name,unit),rows in material_totals.items()]
    unit_available=sum(r['unitCostAvailable'] for r in cost_rows)
    source_zero=sum(r['sourceZeroCostLines'] for r in cost_rows)
    known_material=sum(r['materialCostKnownLines'] for r in cost_rows)
    linked_lines=sum(r['materialLines'] for r in cost_rows)
    known_wages=sum(r['laborCostKnownTasks'] for r in cost_rows)
    snapshot=metadata['finished_at']
    limitations=['仅账号可见历史；删除、孤立及不可见记录完整性未证明。',
                 '工序工时与工资为累计快照，不代表某日或某周实际发生额。',
                 '成品量取工单层良品量；不叠加工单、任务和报工数量。',
                 '库存是采集时点余额；未返回组合保持未知，不补零。',
                 '材料与人工仅构成可识别直接成本；缺设备、能耗、制造费用等，不计算完整制造成本或毛利。',
                 'BOM与工单用料只作参考；225条用料的父归属缺源端ID独立验证，不据此估算实际耗料。',
                 '已完工、正数成品量、材料与人工均有值且无可疑零值，才展示单位可识别直接成本；仍非财务核算成本。']
    findings=[f'可见工单{len(projects)}张，已完工{status["20"]}张；数量比较保留产品与单位。',
              f'生产领退料已关联{linked_lines}行，未关联{unlinked}行；金额有值{known_material}行，其中源端零值{source_zero}行。',
              f'人工累计工资有值{known_wages}/{len(tasks)}个任务；不叠加报工预计工资。',
              f'可展示单位可识别直接成本{unit_available}/{len(projects)}张工单；其余逐单显示阻断原因。']
    return serial({'factoryName':'六膳门','snapshotAt':snapshot,'runId':metadata['run_id'],
                   'catalog':[{'resource':key,'count':len(rows)} for key,rows in sorted(data.items())],
                   'production':{'projectCount':len(projects),'taskCount':len(tasks),'outputCount':len(outputs),
                                 'approvedOutputCount':sum(str(r.get('approvalStatus'))=='10' for r in outputs),
                                 'unapprovedOutputCount':sum(str(r.get('approvalStatus'))!='10' for r in outputs),
                                 'statuses':dict(status),'completionRate':status['20']/len(projects) if projects else None,
                                 'planActual':plan_actual,'quality':quality_rows,'processes':process_rows},
                   'inventory':{'records':balances,'byWarehouseUnit':inventory_totals,
                                'reconciliations':[r for r in metadata.get('stock_reconciliations',[]) if r['checkedAt']==metadata.get('stock_finished_at')],
                                'balanceAsOf':metadata.get('stock_finished_at',metadata.get('finished_at')) if balances else None,
                                'lastAvailableBalanceAt':metadata.get('stock_latest_finished_at',metadata.get('stock_finished_at',metadata.get('finished_at'))),
                                'pairsNotReturned':metadata['stock_pairs_not_returned'],'movementOrders':len(orders)},
                   'cost':{'projects':cost_rows,'materials':material_rows,'linkedMaterialLines':linked_lines,
                           'unlinkedMaterialLines':unlinked,'excludedUnapprovedOrders':excluded_orders,
                           'knownMaterialCostLines':known_material,'sourceZeroCostLines':source_zero,
                           'knownLaborTasks':known_wages,'unitCostAvailableProjects':unit_available,
                           'fullManufacturingCost':None,'grossProfit':None},
                   'findings':findings,'limitations':limitations,'coverageComplete':False})
