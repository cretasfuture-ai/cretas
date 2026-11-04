"""Compact teacher examples and second-pass indicators, without factory answers."""
from collections import defaultdict
from decimal import Decimal,InvalidOperation

EXAMPLES=[
    {'case':'apparent_low_cost','firstLook':'单位成本很低，人工占比较高。',
     'secondLook':'先查是否只做包装，再查主料或半成品价值是否另记；投入与成品单位不同时追查包装换算和批次。',
     'indicators':['材料覆盖','人工占比','包装工序边界','上游到成品单位换算是否有依据'],
     'direction':'低价先查漏项，不急着称为成本优势；候选上游不等于已贯通批次，也不替换实际领料估值。'},
    {'case':'peer_cost_gap','firstLook':'同产品工单单位成本差异明显。',
     'secondLook':'先拆材料差与人工差，再查低价对照是否零工资；材料贵还要拆每件耗用量与账面单位领料折算值。',
     'indicators':['材料差额贡献','人工差额贡献','单位成品耗用量变化','领料折算值变化','材料组合是否变化'],
     'direction':'成本高但耗量反而少时，优先查投入组合、半成品阶段和估值；折算值不是采购价，不据此认定涨价或超耗。'},
    {'case':'too_perfect_quantity','firstLook':'多张工单投入数量与成品数量完全一致。',
     'secondLook':'核实数量是否实称实耗，还是按产量、配方或默认规则生成；再查退料、损耗和期初在制品。',
     'indicators':['账面投入产出比','相同投入产出比的重复程度','计量来源是否可追溯','损耗记录是否具备'],
     'direction':'账面相等不证明实际没有损耗；先确认计量来源，再讨论出成率，不指控数据造假。'},
]


def _decimal(value):
    if value is None or isinstance(value,bool):return None
    try:result=Decimal(str(value))
    except (InvalidOperation,ValueError,TypeError):return None
    return result if result.is_finite() else None


def _mass(project):
    lines=[row for row in project.get('components',[]) if str(row.get('unit','')).casefold()=='kg']
    if not lines:return None
    quantities=[_decimal(row.get('netConsumption')) for row in lines]
    values=[_decimal(row.get('recognizedCost')) for row in lines]
    if any(v is None or v<0 for v in quantities+values):return None
    quantity=sum(quantities,Decimal(0));value=sum(values,Decimal(0))
    good=_decimal(project.get('finishedGood'))
    if quantity<=0 or good is None or good<=0:return None
    return quantity/good,value/quantity,{row.get('materialCode') for row in lines}


def peer_basis(high,low):
    if (not high.get('productCode') or any(high.get(k)!=low.get(k) for k in ('productCode','product','unit'))):return None
    upper=_mass(high);lower=_mass(low)
    if not upper or not lower or lower[0]<=0 or lower[1]<=0:return None
    return {'inputQuantityChange':float(upper[0]/lower[0]-1),
            'inputValuationChange':float(upper[1]/lower[1]-1),
            'kgMaterialCostPerFinishedChange':float((upper[0]*upper[1])/(lower[0]*lower[1])-1),
            'inputKindsChanged':int(upper[2]!=lower[2]),
            'highQuantityPerFinished':upper[0],'lowQuantityPerFinished':lower[0],
            'highValuationPerKg':upper[1],'lowValuationPerKg':lower[1]}


def second_pass_facts(projects):
    groups=defaultdict(list);names=defaultdict(set);facts=[]
    known_products={p.get('productCode') for p in projects if p.get('productCode')}
    for p in projects:
        groups[(p.get('productCode'),p.get('unit'),p.get('product'))].append(p)
        names[(p.get('productCode'),p.get('unit'))].add(p.get('product'))
        for line in p.get('components',[]):
            amount=_decimal(line.get('netConsumption'));good=_decimal(p.get('finishedGood'))
            if (line.get('materialCode') not in known_products or not amount or amount<=0 or not good or good<=0
                    or str(line.get('unit','')).casefold()==str(p.get('unit','')).casefold()):continue
            facts.append({'section':'material','kind':'upstream_unit_conversion',
                'text':f"{p['projectCode']}：账面投入 {amount:,.2f}{line['unit']} 的{line['material']}，最终良品 {good:,.2f}{p['unit']}；需核对包装规格与批次对应，不能直接将候选上游单价当本单实际成本。",
                'values':{'recordedOutputPerInput':float(good/amount),'unitConversionNeedsVerification':1},
                'hazards':['UNIT_CONVERSION'],'refs':[{'projectId':p['projectId'],'projectCode':p['projectCode']}]})
    for (code,unit,name),group in groups.items():
        if not code or len(names[(code,unit)])>1:continue
        if len(group)>=2:
            high=max(group,key=lambda p:p['unitIdentifiedDirectCost']);low=min(group,key=lambda p:p['unitIdentifiedDirectCost'])
            basis=peer_basis(high,low)
            if basis:
                facts.append({'section':'product_cost','kind':'material_quantity_valuation_split',
                    'text':f"{name}：{high['projectCode']} 对比 {low['projectCode']}，每{unit}成品的账面公斤投入变化 {basis['inputQuantityChange']*100:,.2f}%，领料折算值变化 {basis['inputValuationChange']*100:,.2f}%；按公斤计量材料的每{unit}账面净额变化 {basis['kgMaterialCostPerFinishedChange']*100:,.2f}%，不含非公斤材料。材料组合{'不同' if basis['inputKindsChanged'] else '相同'}。折算值不是采购价，不能直接认定涨价或超耗。",
                    'values':{k:basis[k] for k in ('inputQuantityChange','inputValuationChange','inputKindsChanged','kgMaterialCostPerFinishedChange')},
                    'hazards':['MATERIAL_MIX'] if basis['inputKindsChanged'] else [],
                    'refs':[{'projectId':p['projectId'],'projectCode':p['projectCode']} for p in (high,low)]})
        masses=[_mass(p) for p in group]
        if len(group)>=3 and str(unit).casefold()=='kg' and all(m and abs(m[0]-1)<Decimal('0.000000001') for m in masses):
            facts.append({'section':'production','kind':'recorded_quantity_equality',
                'text':f'{name}有 {len(group)} 张工单的账面投入/最终良品均为 1.00；需确认数量来自实称，还是按产量或默认配方生成；不能据此断言真实无损耗。',
                'values':{'sampleSize':len(group),'equalRecordedInputOutputRatio':1},'hazards':['MEASUREMENT_BASIS'],
                'refs':[{'projectId':p['projectId'],'projectCode':p['projectCode']} for p in group]})
    return facts
