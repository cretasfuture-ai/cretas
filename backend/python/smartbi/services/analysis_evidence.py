"""Build model-safe statistics and separately bind readable, authorised evidence."""
from collections import Counter,defaultdict
import math

from .analysis_playbook import SECTIONS,VERSION

TYPE_SECTION={
    'MATERIAL_LINK_MISSING':'material','MATERIAL_VALUE_ZERO':'material','MATERIAL_VALUE_MISSING':'material',
    'MATERIAL_VALUE_PARTIAL_ZERO':'material','INPUT_ONLY_CONSUMABLES':'material',
    'LABOR_SHARE_HIGH':'labor','LABOR_ZERO_UNVERIFIED':'labor','LABOR_WITH_HOURS_ZERO':'labor','LABOR_COVERAGE_MISSING':'labor',
    'FINISHED_GOOD_INVALID':'finished','FINISHED_GOOD_NONPOSITIVE':'finished','FINISHED_UNIT_MISSING':'finished',
    'UNIT_COST_SPREAD':'product_cost','UNIT_COST_SPREAD_CANDIDATE':'product_cost','PRODUCT_NAME_CONFLICT':'product_cost','PEER_SAMPLE_SMALL':'product_cost',
    'MATERIAL_SHARE_LOW':'material','NOT_FINISHED':'production',
}
TYPE_HAZARD={
    'MATERIAL_VALUE_ZERO':'MISSING_COST','MATERIAL_VALUE_PARTIAL_ZERO':'MISSING_COST',
    'MATERIAL_VALUE_MISSING':'MISSING_COST','MATERIAL_LINK_MISSING':'MISSING_COST','MATERIAL_SHARE_LOW':'MISSING_COST',
    'INPUT_ONLY_CONSUMABLES':'PACKAGING_ONLY','LABOR_SHARE_HIGH':'LABOR_SHARE',
    'LABOR_ZERO_UNVERIFIED':'ZERO_LABOR_BASELINE','LABOR_WITH_HOURS_ZERO':'ZERO_LABOR_BASELINE',
    'PRODUCT_NAME_CONFLICT':'IDENTITY_CONFLICT','PEER_SAMPLE_SMALL':'SMALL_SAMPLE',
}


def _number(value):
    if value is None or isinstance(value,bool):return None
    try:value=float(value)
    except (TypeError,ValueError,OverflowError):return None
    return value if math.isfinite(value) and abs(value)<1e15 else None


def _fmt(value):
    number=_number(value)
    return '未提供' if number is None else f'{number:,.2f}'


def build(report,context=None):
    context=context or {}
    has_cost=bool(context) or (report.get('datasetKind')=='production_cost' and
                              report.get('capabilities',{}).get('cost',{}).get('status') in ('summary_only','insufficient'))
    section_ids=list(SECTIONS) if has_cost else ['coverage']
    facts=[];display={};required={s:set() for s in section_ids}

    def add(section,kind,text,*,values=None,hazards=(),entity_refs=()):
        if section not in required:section='coverage'
        ref=f'F{len(facts)+1:03d}'
        clean={k:n for k,v in (values or {}).items() if (n:=_number(v)) is not None}
        facts.append({'id':ref,'sectionId':section,'kind':kind,'statistics':clean,'considerations':list(hazards)})
        display[ref]={'id':ref,'text':text,'entityRefs':list(entity_refs)}
        required[section].update(hazards)
        return ref

    summary=report.get('summary',{})
    total=summary.get('projects',summary.get('scannedRows',0))
    eligible=summary.get('costEligible',report.get('capabilities',{}).get('cost',{}).get('eligibleRows'))
    eligible=_number(eligible)
    if not has_cost:eligible=None
    add('coverage','sample_coverage',f'本次范围 {total} 条工单或数据记录；可计算成本 {int(eligible)} 条。' if eligible is not None else f'本次检查 {total} 条记录，未提供工单成本核算条件。',
        values={'sampleSize':total,'eligibleCount':eligible,'eligibleRatio':eligible/total if total and eligible is not None else None},
        hazards=['MISSING_COST'] if has_cost and (eligible is None or eligible<total) else [])
    items=report.get('items',[])
    grouped=defaultdict(list)
    for item in items:grouped[item['type']].append(item)
    for kind,group in grouped.items():
        section=TYPE_SECTION.get(kind,'coverage')
        representative=group[0]
        evidence=representative.get('evidence') or {}
        values={'alertCount':len(group)}
        for key in ('spread','threshold','materialShare','laborShare','sampleSize'):
            values[key]=evidence.get(key)
        text=representative['title']+f'：本范围有 {len(group)} 条此类提醒；提醒可能重叠。'
        if kind in ('LABOR_SHARE_HIGH','MATERIAL_SHARE_LOW'):
            text=representative['message']
        refs=[ref for a in group for ref in (a.get('entityRefs') or [])][:12]
        hazards=[TYPE_HAZARD[kind]] if kind in TYPE_HAZARD else []
        add(section,kind,text,values=values,hazards=hazards,entity_refs=refs)

    if not context:
        metric_groups=defaultdict(list)
        for rank in report.get('analysisMetrics',{}).get('rankings',[]):
            if _number(rank['weightedUnitCost']) is not None:metric_groups[rank['unit']].append(rank)
        for group in metric_groups.values():
            for rank,product in enumerate(sorted(group,key=lambda p:_number(p['weightedUnitCost']),reverse=True),1):
                add('product_cost','weighted_unit_cost',f"{product['product']}：仅在字段通过校验子集内，{product['projects']} 张工单的加权材料加人工 {_fmt(product['weightedUnitCost'])}/{product['unit']}；工单范围 {_fmt(product['minimum'])}—{_fmt(product['maximum'])}/{product['unit']}，不代表完整制造成本。",
                    values={'sameUnitAmountRank':rank,'sampleSize':product['projects']},hazards=['SMALL_SAMPLE'] if product['projects']<3 else [])
    if context:
        period=context.get('periodMetadata',{})
        if period:
            add('coverage','period_boundary',period['scopeDescription'],hazards=['PARTIAL_PERIOD'] if period.get('coverageStatus')=='source_snapshot_before_period_end' else [])
        if context.get('analysisHistory',{}).get('usedReports'):
            add('coverage','history_context','已参考兼容的历史报告；重叠周期不能相加，未再命中不能认定问题已解决。',
                values={'historyReports':len(context['analysisHistory']['usedReports'])},hazards=['HISTORY_CONTEXT'])
        ranks=context.get('eligibleCostReview',{}).get('rankings',[])
        unit_groups=defaultdict(list)
        for rank in ranks:unit_groups[rank['unit']].append(rank)
        for group in unit_groups.values():
            for rank,product in enumerate(sorted(group,key=lambda p:p['weightedUnitCost'],reverse=True),1):
                add('product_cost','weighted_unit_cost',
                    f"{product['product']}：{product['projects']} 张资料具备工单，按产量加权的材料加人工 {_fmt(product['weightedUnitCost'])}/{product['unit']}，工单范围 {_fmt(product['minimum'])}—{_fmt(product['maximum'])}/{product['unit']}。不同产品不能仅据名次判断异常。",
                    values={'sameUnitAmountRank':rank,'sampleSize':product['projects']},hazards=['SMALL_SAMPLE'] if product['projects']<3 else [])
        projects=context.get('eligibleCostReview',{}).get('projects',[])
        from .analysis_distillation import second_pass_facts
        for insight in second_pass_facts(projects):
            add(insight['section'],insight['kind'],insight['text'],values=insight['values'],hazards=insight['hazards'],entity_refs=insight['refs'])
        peers=defaultdict(list);names=defaultdict(set)
        for project in projects:
            peers[(project.get('productCode'),project.get('unit'),project.get('product'))].append(project)
            names[(project.get('productCode'),project.get('unit'))].add(project.get('product'))
        for (code,unit,name),group in peers.items():
            if len(group)<2 or not code or len(names[(code,unit)])>1:continue
            low=min(group,key=lambda p:p['unitIdentifiedDirectCost']);high=max(group,key=lambda p:p['unitIdentifiedDirectCost'])
            minimum=low['unitIdentifiedDirectCost'];maximum=high['unitIdentifiedDirectCost']
            if minimum<=0:continue
            delta=maximum-minimum
            material=high['materialPerFinished']-low['materialPerFinished'];labor=high['laborPerFinished']-low['laborPerFinished']
            zero_labor=low.get('laborCost')==0
            add('product_cost','same_product_bridge',
                f"{name}：{high['projectCode']} {_fmt(maximum)}/{unit} 对比 {low['projectCode']} {_fmt(minimum)}/{unit}，差 {_fmt(delta)}/{unit}（{_fmt(delta/minimum*100)}%）；材料差 {_fmt(material)}，人工差 {_fmt(labor)}。"+
                ('低价对照单人工为零，应先核实人工是否另记，不能把差额认定为浪费。' if zero_labor else '这是账面差额，不是已证实的浪费或采购涨价。'),
                values={'spread':delta/minimum,'sampleSize':len(group),'lowLaborZero':int(zero_labor)},
                hazards=['ZERO_LABOR_BASELINE'] if zero_labor else [],entity_refs=[{k:p[k] for k in ('projectId','projectCode')} for p in (high,low)])
        cost=context.get('cost',{})
        add('material','material_link_coverage',f"所选工单关联 {cost.get('linkedMaterialLines',0)} 行领退料；未关联 {cost.get('unlinkedMaterialLines',0)} 行，源端零金额 {cost.get('sourceZeroCostLines',0)} 行。",
            values={'linkedLines':cost.get('linkedMaterialLines'),'unlinkedLines':cost.get('unlinkedMaterialLines'),'zeroValueLines':cost.get('sourceZeroCostLines')},hazards=['MISSING_COST'] if cost.get('sourceZeroCostLines') else [])
        production=context.get('production',{})
        add('production','project_scope',f"本范围工单 {production.get('projectCount',0)} 张、工序任务 {production.get('taskCount',0)} 个、报工 {production.get('outputCount',0)} 条；产量不能跨层相加。按完工日期选取的样本不能当成全厂完成率。",
            values={'projectCount':production.get('projectCount'),'taskCount':production.get('taskCount'),'outputCount':production.get('outputCount')})
        add('finished','final_good_basis','成品数量只按工单最终良品计算，工序产量与多次报工不重复相加；还需要审批、入库及规格换算核对。')
        if period.get('inventoryStatus')=='historical_balance_unavailable':
            inventory=context.get('inventory',{})
            add('inventory','period_movements',f"本范围按业务日期有 {inventory.get('movementOrders',0)} 张出入库单，可以核对收发，但没有对应历史期末余额。",
                values={'movementOrders':inventory.get('movementOrders')})
            add('inventory','missing_historical_balance','所选日期没有对应的历史库存余额，不能把无记录解释为零库存。',hazards=['HISTORICAL_BALANCE'])
        else:
            inventory=context.get('inventory',{})
            add('inventory','snapshot_only',f"本范围按业务日期有 {inventory.get('movementOrders',0)} 张出入库单；{len(inventory.get('records',[]))} 行库存余额只代表采集时点，不代表历史期末余额；期间收发仍需独立对账。",
                values={'movementOrders':inventory.get('movementOrders'),'balanceRows':len(inventory.get('records',[]))})

    for section in section_ids:
        if not any(f['sectionId']==section for f in facts):
            add(section,'not_supplied',f'{SECTIONS[section]}未提供可验证的统计证据，当前不能判断。',hazards=['NO_DATA'])
    # Keep a bounded, explicit per-section sample. Omitted facts are disclosed.
    selected=[]
    for section in section_ids:
        group=[f for f in facts if f['sectionId']==section]
        critical=[]
        for code in sorted(required[section]):
            fact=next(f for f in group if code in f['considerations'])
            if fact not in critical:critical.append(fact)
        selected.extend(critical+[f for f in group if f not in critical][:max(0,18-len(critical))])
    retained={f['id'] for f in selected}
    return {'version':VERSION,'isCostDataset':has_cost,'sectionIds':section_ids,'facts':selected,
            'displayFacts':{ref:value for ref,value in display.items() if ref in retained},
            'requiredConsiderations':{s:sorted(required[s]) for s in section_ids},
            'omittedFacts':len(facts)-len(selected)+report.get('analysisMetrics',{}).get('omittedGroups',0)}
