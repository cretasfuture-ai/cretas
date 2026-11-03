"""Source-scoped adapter to existing SmartBI candidates/facts, plus exact cost bridges."""
import hashlib
import sys
from pathlib import Path
from collections import defaultdict,Counter
from decimal import Decimal

from .analytics import number,serial,complete_amount
from .gap_evidence import EVIDENCE_SCHEMA


def _smartbi():
    # Same legacy service import root as backend/python/main.py.
    root=str(Path(__file__).parents[1]/'smartbi')
    if root not in sys.path:sys.path.insert(0,root)
    from smartbi.services.factory_charts.metrics import Series
    from smartbi.services.factory_charts.engine import series_candidate
    from smartbi.services.factory_charts.conversation import facts_for,render_answer
    return Series,series_candidate,facts_for,render_answer


def material_components(project):
    grouped=defaultdict(list)
    for row in project['materialLinesDetail']:
        if not row.get('materialCode') or not row.get('unit'):raise ValueError('COST_MATERIAL_STABLE_KEY_REQUIRED')
        grouped[(row['materialCode'],row['unit'])].append(row)
    out={}
    qty=number(project['finishedGood'])
    for key,rows in grouped.items():
        consumption=complete_amount([number(r['netConsumption']) for r in rows])
        cost=complete_amount([number(r['recognizedCost']) for r in rows])
        out[key]={'materialCode':key[0],'unit':key[1],'material':rows[0]['material'],
                  'consumptionPerFinished':consumption/qty if consumption is not None else None,
                  'costPerFinished':cost/qty if cost is not None else None,
                  'effectivePrice':cost/consumption if cost is not None and cost>0 and consumption is not None and consumption>0 else None}
    return out


def bridge(low,high):
    if (not low['unitCostAvailable'] or not high['unitCostAvailable']
            or (low['productCode'],low['unit'])!=(high['productCode'],high['unit'])):
        raise ValueError('COST_COMPARISON_SCOPE_INVALID')
    before,after=material_components(low),material_components(high)
    rows=[]
    for key in sorted(set(before)|set(after)):
        a,b=before.get(key),after.get(key)
        # Absent material is explicitly absent from this complete linked subset, not a missing value.
        ca=a['costPerFinished'] if a else Decimal(0)
        cb=b['costPerFinished'] if b else Decimal(0)
        if ca is None or cb is None:raise ValueError('COST_BRIDGE_COMPONENT_MISSING')
        price_effect,usage_effect=None,None
        if a and b and a['effectivePrice'] is not None and b['effectivePrice'] is not None:
            usage_effect=(b['consumptionPerFinished']-a['consumptionPerFinished'])*a['effectivePrice']
            price_effect=b['consumptionPerFinished']*(b['effectivePrice']-a['effectivePrice'])
        item=b or a
        rows.append({'material':item['material'],'materialCode':key[0],'unit':key[1],
                     'baselineUsage':a['consumptionPerFinished'] if a else None,
                     'comparisonUsage':b['consumptionPerFinished'] if b else None,
                     'baselineContribution':ca,'comparisonContribution':cb,'delta':cb-ca,
                     'usageEffect':usage_effect,'priceEffect':price_effect,
                     'basis':'净耗用与净额折算的代数分解，非采购涨价或损耗原因的证明' if usage_effect is not None else '新增/减少或无法拆分的材料项'})
    labor_low=number(low['laborCost'])/number(low['finishedGood'])
    labor_high=number(high['laborCost'])/number(high['finishedGood'])
    delta=number(high['identifiedDirectCost'])/number(high['finishedGood'])-number(low['identifiedDirectCost'])/number(low['finishedGood'])
    material_delta=sum((r['delta'] for r in rows),Decimal(0))
    reconciliation=delta-material_delta-(labor_high-labor_low)
    if abs(reconciliation)>Decimal('0.00000001'):raise ValueError('COST_BRIDGE_RECONCILIATION_FAILED')
    return serial({'baselineProject':low['projectCode'],'comparisonProject':high['projectCode'],
                   'unit':low['unit'],'delta':delta,'materialDelta':material_delta,'laborDelta':labor_high-labor_low,
                   'reconciliationDifference':reconciliation,'materials':rows,
                   'selection':'同产品、同单位、通过校验子集内的最高与最低工单；非时间趋势',
                   'limitations':['只分解已识别材料与人工；不等于完整成本差异','净额/净耗用折算价不等于采购单价','未证明差异的业务原因']})


def enrich(analysis,data,metadata,evidence=None):
    Series,candidate,facts_for,render_answer=_smartbi()
    identities={}
    for product in data.get('product',[]):
        code=product.get('productCode');product_id=str(product.get('id'))
        if code in identities and identities[code]!=product_id:raise ValueError('AMBIGUOUS_PRODUCT_CODE_IDENTITY')
        if code and product.get('id') is not None:identities[code]=product_id
    for order in data.get('stock_order',[]):
        for detail in order.get('orderDetails',[]):
            code=detail.get('productCode')
            if code in identities and detail.get('productId') is not None and str(detail['productId'])!=identities[code]:
                raise ValueError('MOVEMENT_PRODUCT_IDENTITY_MISMATCH')
    grouped=defaultdict(list)
    for project in analysis['cost']['projects']:grouped[(project['productCode'],project['unit'])].append(project)
    comparisons=[];recommendations=[]
    for (code,unit),projects in sorted(grouped.items(),key=lambda item:str(item[0])):
        eligible=[r for r in projects if r['unitCostAvailable'] and r.get('productCode') in identities and r.get('unit')]
        if not eligible:continue
        eligible=sorted(eligible,key=lambda r:r['unitIdentifiedDirectCost'])
        numerator=sum((number(r['identifiedDirectCost']) for r in eligible),Decimal(0))
        denominator=sum((number(r['finishedGood']) for r in eligible),Decimal(0))
        key=hashlib.sha256(str((code,unit)).encode()).hexdigest()[:12]
        series=Series(key='cost.unit.blacklake.'+key,title=projects[0]['product']+' · 单位可识别直接成本',domain='成本',
                      unit='来源金额/'+unit,points=[(p['projectCode'],p['unitIdentifiedDirectCost']) for p in eligible],
                      source='黑湖历史归档 · '+metadata['run_id'],aggregate='unit_cost',numerator=float(numerator),denominator=float(denominator))
        card=candidate(series,'bar_horizontal')
        card['kpis'][0]['label']='通过校验子集的加权单位直接成本'
        original=card['findings']['attention']
        card['findings']['attention']=[f'通过校验子集的产量加权单位直接成本 {numerator/denominator:,.4f}{series.unit}（{len(eligible)}/{len(projects)}张）',
                                       f'按已识别直接成本合计 {numerator:,.2f} / 成品量合计 {denominator:,.4f}{unit}计算',
                                       *[text for text in original if '累计口径' not in text and not text.startswith('按分子 ')]]
        card['findings']['limitations']=[f'仅{len(eligible)}/{len(projects)}张同产品同单位工单通过字段校验；不代表全部工单',
                                          '来源币种待核；不是完整制造成本或毛利']
        recommendations.append(card)
        comparisons.append({'productCode':code,'product':projects[0]['product'],'unit':unit,
                            'eligibleProjects':len(eligible),'visibleProjects':len(projects),
                            'weightedUnitCost':float(numerator/denominator),'minimum':eligible[0]['unitIdentifiedDirectCost'],
                            'maximum':eligible[-1]['unitIdentifiedDirectCost'],
                            'candidateId':card['id'],'bridge':bridge(eligible[0],eligible[-1]) if len(eligible)>1 else None})
    facts=facts_for(recommendations)
    limitations=['按同产品代码与成品单位比较，不跨SKU或单位合并单位成本',
                 '价格线索不替代实际成本；缺失数据不补零','未接独立质检记录，不把报工良品率冒充质检合格率']
    answer=render_answer('同产品工单的单位直接成本差异体现在哪里？',recommendations,facts,limitations)
    materials_by_project=defaultdict(list)
    # Requested parent context was verified by dual source queries; do not forge a source projectId.
    usable=evidence and evidence.get('schema')==EVIDENCE_SCHEMA and evidence.get('status')=='complete' and evidence.get('negativeControlVerified') is True
    proven={(str(r['projectId']),str(r['materialId'])) for r in evidence.get('verifiedMaterialParents',[])} if usable else set()
    material_rows={str(r['id']):r for r in data.get('materials',[])}
    project_codes={str(r['projectId']):r['projectCode'] for r in analysis['cost']['projects']}
    for project_id,material_id in proven:
        if material_id in material_rows and project_id in project_codes:
            row=material_rows[material_id]
            materials_by_project[project_codes[project_id]].append({'materialCode':row.get('productCode'),'sourceUnitQty':row.get('unitQty'),
                                                   'feedProcessCode':row.get('feedProcessCode'),
                                                   'basis':'按工单ID与编号双查询核实父级；仅源端单位用量，不推算实际消耗'})
    issue_counts=Counter(issue for p in analysis['cost']['projects'] for issue in p['issues'])
    return {'engine':'Cretas SmartBI Series / series_candidate / facts_for / render_answer',
            'recommendations':recommendations,'facts':facts,'comparisons':comparisons,'answer':answer,
            'limitations':limitations,'gapEvidence':evidence or {'status':'not_available'},
            'verifiedMaterialReferences':dict(materials_by_project),
            'worklist':[{'issue':issue,'projects':count} for issue,count in issue_counts.most_common()],
            'nextActions':['先核对领退料单的估值来源与历史入库价格','按单核对人工计价与未记录工时',
                           '按同产品、同单位查看材料与人工差异，再回查明细证明原因']}
