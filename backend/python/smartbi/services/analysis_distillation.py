"""Compact teacher examples and second-pass indicators, without factory answers."""
from collections import defaultdict
from decimal import Decimal,InvalidOperation

EXAMPLES=[
    {'case':'material_line_is_not_batch','firstLook':'领料来源明细已匹配工单用料行与投料工序。',
     'secondLook':'先核对ID所属对象；用料行、库存入库明细和批号是不同对象。继续找实际入库批次及领用分配。',
     'indicators':['业务父单关联','投料工序关联','实际批次与分配数量'],
     'direction':'仓库补入库明细、批号和拆混批领用量，生产核实工序；前者解决来源追溯，后者解决工序归属。'},
    {'case':'per_order_request','firstLook':'一张工单缺少最终良品，材料为源端零值，人工也为零。',
     'secondLook':'逐项写缺什么、找谁、补后能判断什么；区分已查询未提供、零值待核、查询失败和未完工。',
     'indicators':['具体单据与字段','责任角色','补齐后的计算或判断','查询证据状态'],
     'direction':'生产核最终审批良品，财务和仓库核历史计价，生产财务核计薪是否集中或另记；不笼统要求补齐全部资料。'},
    {'case':'carrying_value_boundary','firstLook':'半成品领料已有账面价值，上游还有原料和人工。',
     'secondLook':'先确认半成品价值是否承接上游；若已承接，只在下游增加本阶段新增成本，不把上游再加一次。',
     'indicators':['各阶段成本边界','承接价值','本阶段新增成本','其他制造费用是否具备'],
     'direction':'同编码或时间接近的上游工单仅为候选，不能按猜测回填；识别直接成本不是完整制造成本。'},
    {'case':'split_batch_and_joint_outputs','firstLook':'一批原料供多张工单，或一个工序产出多种产品。',
     'secondLook':'核对拆混批实际分配及各来源可用量；联产品还需要获准的成本分摊依据。',
     'indicators':['逐笔领用分配量','来源数量上限','同单位换算','联产品分摊规则'],
     'direction':'仓库核数量及退料，生产核规格与产出，财务核分摊；不跨单位直接按数量均摊，不超配库存，不重复成本。'},
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
    {'case':'normal_control','firstLook':'高差异组与另一产品的稳定组同时存在。',
     'secondLook':'分别解释两组，稳定组保留为正常对照；样本少，不能推广为标准成本，也不能把高差异组的问题套给稳定组。',
     'indicators':['同产品同单位组内差异','稳定对照','样本量','数据完整性'],
     'direction':'必须说清稳定组当前未见明显账面波动，不证明实际完整或无异常；同码异名另行核实。'},
    {'case':'coverage_selection','firstLook':'可算工单只是部分样本，缺失集中于特定批次。',
     'secondLook':'同时看工单覆盖和同单位产量覆盖；先补缺失批次，再讨论加权成本。',
     'indicators':['工单覆盖','产量覆盖','缺失集中程度','未知数量'],
     'direction':'未知不能填零；加权不能消除选择偏差，也不能猜测偏差方向。'},
    {'case':'contradictory_impact','firstLook':'错误表述：材料成本缺失会导致产品成本严重低估，影响定价。后文又说不能确认低估。',
     'secondLook':'改为：材料成本可能未记全，先核零值原因、计价状态和是否另记；尚不能确定成本偏差的方向和程度。',
     'indicators':['证据是否支持影响程度','正文与限制是否一致','必需风险是否写进白话解释'],
     'direction':'不照抄错误表述。风险代号写进列表不等于解释风险；有样本少就明确说样本少、不能推广。'},
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


def common_quotes(high,low):
    """Compare only observed positive-quantity book quotes, not purchase prices."""
    def index(project):
        values=defaultdict(list)
        for row in project.get('components',[]):
            qty=_decimal(row.get('netConsumption'));price=_decimal(row.get('unitPrice'))
            if not row.get('materialCode') or not row.get('unit') or qty is None or qty<=0 or price is None or price<0:continue
            values[(row['materialCode'],str(row['unit']).casefold())].append((price,row.get('material')))
        return values
    a,b=index(high),index(low);same=changed=ambiguous=conflicts=0;details=[]
    for key in sorted(set(a)&set(b)):
        pa={v[0] for v in a[key]};pb={v[0] for v in b[key]}
        names_a={v[1] for v in a[key]};names_b={v[1] for v in b[key]}
        conflicts+=int(names_a!=names_b or len(names_a|names_b)>1)
        if len(pa)!=1 or len(pb)!=1:ambiguous+=1;continue
        upper,lower=next(iter(pa)),next(iter(pb))
        same+=int(upper==lower);changed+=int(upper!=lower)
        if len(details)<4:details.append(f"编码 {key[0]}：两单领料账面单价 {upper:,.2f}/{key[1]} 与 {lower:,.2f}/{key[1]}")
    if not (same+changed+ambiguous):return None
    return {'sameBookQuoteCount':same,'changedBookQuoteCount':changed,
            'ambiguousBookQuoteCount':ambiguous,'inputNameConflictCount':conflicts},details


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
            quotes=common_quotes(high,low)
            if quotes:
                values,details=quotes
                facts.append({'section':'product_cost','kind':'common_input_price_check',
                    'text':f"{name}：{high['projectCode']} 对比 {low['projectCode']}，"+'；'.join(details)+
                           f"。共同编码中 {values['sameBookQuoteCount']} 组账面单价相同、{values['changedBookQuoteCount']} 组不同、{values['ambiguousBookQuoteCount']} 组多价无法直接比较；{values['inputNameConflictCount']} 组名称不一致。先核实材料组合和阶段，不能从加权折算值变化直接认定采购涨价。",
                    'values':values,'hazards':[],
                    'refs':[{'projectId':p['projectId'],'projectCode':p['projectCode']} for p in (high,low)]})
        masses=[_mass(p) for p in group]
        if len(group)>=3 and str(unit).casefold()=='kg' and all(m and abs(m[0]-1)<Decimal('0.000000001') for m in masses):
            facts.append({'section':'production','kind':'recorded_quantity_equality',
                'text':f'{name}有 {len(group)} 张工单的账面投入/最终良品均为 1.00；需确认数量来自实称，还是按产量或默认配方生成；不能据此断言真实无损耗。',
                'values':{'sampleSize':len(group),'equalRecordedInputOutputRatio':1},'hazards':['MEASUREMENT_BASIS'],
                'refs':[{'projectId':p['projectId'],'projectCode':p['projectCode']} for p in group]})
    return facts
