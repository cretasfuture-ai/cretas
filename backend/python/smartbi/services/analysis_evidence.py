"""Build model-safe statistics and separately bind readable, authorised evidence."""
from collections import Counter,defaultdict
import math
import hashlib
import json

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


def build(report,context=None,*,include_all=False):
    context=context or {}
    has_cost=bool(context) or (report.get('datasetKind')=='production_cost' and
                              report.get('capabilities',{}).get('cost',{}).get('status') in ('summary_only','insufficient'))
    section_ids=list(SECTIONS) if has_cost else ['coverage']
    facts=[];display={};required={s:set() for s in section_ids};trace_required=[]
    by_project={p.get('projectId'):p for p in context.get('eligibleCostReview',{}).get('projects',[]) if p.get('projectId') is not None}

    def add(section,kind,text,*,values=None,hazards=(),entity_refs=(),group_key=None):
        if section not in required:section='coverage'
        ref=f'F{len(facts)+1:03d}'
        clean={k:n for k,v in (values or {}).items() if (n:=_number(v)) is not None}
        facts.append({'id':ref,'sectionId':section,'kind':kind,'statistics':clean,'considerations':list(hazards)})
        if group_key is None and entity_refs:
            keys=[]
            for entity in entity_refs:
                row=by_project.get(entity.get('projectId')) or entity
                keys.append((row.get('productCode'),row.get('unit')))
            if keys and all(k[0] and k[1] for k in keys) and len(set(keys))==1:group_key=keys[0]
        if group_key and all(group_key):
            facts[-1]['groupId']='G'+hashlib.sha256(json.dumps([report.get('scope'),group_key],sort_keys=True,default=str).encode()).hexdigest()[:12]
        if kind in TYPE_SECTION or kind in ('CURRENCY_UNVERIFIED',):
            facts[-1]['statisticScope']='alert_count_and_representative_ratio'
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
                    values={'sameUnitAmountRank':rank,'sampleSize':product['projects']},hazards=['SMALL_SAMPLE'] if product['projects']<3 else [],group_key=(product.get('productCode'),product.get('unit')))
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
                    values={'sameUnitAmountRank':rank,'sampleSize':product['projects']},hazards=['SMALL_SAMPLE'] if product['projects']<3 else [],group_key=(product.get('productCode'),product.get('unit')))
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
        # All orders and units remain visible for coverage; no cost is imputed.
        coverage_groups=defaultdict(list)
        for project in context.get('cost',{}).get('projects',[]):
            coverage_groups[(project.get('productCode'),project.get('unit'))].append(project)
        for key,rows in coverage_groups.items():
            if not key[0] or not key[1]:continue
            eligible_rows=[p for p in rows if p.get('unitCostAvailable') is True]
            quantities=[_number(p.get('finishedGood')) for p in rows]
            eligible_quantities=[_number(p.get('finishedGood')) for p in eligible_rows]
            quantity_available=all(q is not None and q>=0 for q in quantities)
            total_qty=sum(quantities) if quantity_available else None
            eligible_qty=sum(q for q in eligible_quantities if q is not None) if quantity_available else None
            quantity_ratio=eligible_qty/total_qty if total_qty and eligible_qty is not None else None
            add('coverage','product_coverage',f"{rows[0].get('product') or key[0]}：{len(rows)} 张工单中 {len(eligible_rows)} 张具备计算字段；按张数覆盖 {_fmt(len(eligible_rows)/len(rows)*100)}%，按已提供且同单位良品量覆盖 {_fmt(quantity_ratio*100 if quantity_ratio is not None else None)}%；缺少良品量时不猜产量覆盖。",
                values={'sampleSize':len(rows),'eligibleCount':len(eligible_rows),
                        'eligibleRatio':len(eligible_rows)/len(rows),'eligibleQuantityRatio':quantity_ratio},group_key=key)
        trace=context.get('costTraceability')
        if trace:
            if (trace.get('schema')!='smartbi-cost-traceability/1' or trace.get('analysisScope')!=report.get('scope')
                    or trace.get('scope',{}).get('snapshotId')!=context.get('runId')):
                raise ValueError('TRACE_EVIDENCE_SCOPE_MISMATCH')
            summary=trace['summary']
            ref=add('material','lineage_business_links',
                f"当前工单核实 {summary['materialOriginLinks']} 条领退料到用料清单的来源关联，其中 {summary['materialProcessLinks']} 条能定位投料工序。没有实际入库批次和领用分配证明，不能称原料到成品批次成本已贯通。",
                values={'materialOriginLinks':summary['materialOriginLinks'],'materialProcessLinks':summary['materialProcessLinks'],
                        'physicalBatchChainsVerified':summary['physicalBatchChainsVerified']})
            trace_required.append(('material',ref))
            clues=trace.get('fieldClueSummary',{})
            if clues:
                clue_keys={'原材价格':'rawPriceFieldOrderCount','领料重量':'issuedWeightFieldOrderCount',
                    '单片重量':'pieceWeightFieldOrderCount','最小单位重量':'minimumUnitWeightFieldOrderCount','计算初成数量':'initialQuantityFieldOrderCount'}
                ref=add('material','custom_field_basis',
                    '已有自定义业务线索：'+'；'.join(f'{name} {count} 张工单' for name,count in clues.items() if name!='产品类型')+
                    '。字段的单位、实际或计划属性、适用批次仍需确认；不重复索取已有值，不直接替换库存领料实际成本。',
                    values={clue_keys[name]:count for name,count in clues.items() if name in clue_keys})
                facts[-1]['statisticMeaning']='每个数字是填有该字段的工单数，不是价格、重量或金额；字段有值不代表单位及业务含义已验证。'
                trace_required.append(('material',ref))
            from .cost_traceability import ACTION_SECTIONS
            work_orders={str(r['workOrderId']):r for r in trace['workOrders']}
            chosen=set()
            for action in sorted(trace['actions'],key=lambda a:(a['priority']!='high',a['impactType']!='unit_cost',a['workOrderCode'])):
                section=ACTION_SECTIONS.get(action['code'],'coverage')
                if section in chosen:continue
                chosen.add(section);order=work_orders[str(action['workOrderId'])]
                ref=add(section,'work_order_data_request',
                    f"{action['workOrderCode']}（{order.get('product') or '产品待核'}）：缺什么——{action['missingData']}；找谁——{action['responsibleRole']}；补齐后——{action['afterProvided']}。",
                    values={'affectedRecords':len(action['affectedRecordIds'])},
                    entity_refs=[{'projectId':order['workOrderId'],'projectCode':order['workOrderCode']}],
                    group_key=(order.get('productCode'),order.get('unit')))
                facts[-1]['dataRequest']={k:action[k] for k in ('code','missingData','responsibleRole','afterProvided','sourceStatus','impactType')}
                facts[-1]['dataRequest']['alreadyProvidedClues']=[c['label'] for c in order.get('providedClues',[])]
                trace_required.append((section,ref))
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
    selected=[];required_refs={sid:[] for sid in section_ids}
    for sid,ref in trace_required:required_refs[sid].append(ref)
    narrative={sid:[] for sid in section_ids}
    bridges=[f for f in facts if f['kind']=='same_product_bridge']
    if bridges:
        high=max(bridges,key=lambda f:f['statistics']['spread'])
        required_refs['product_cost'].append(high['id'])
        quote=next((f for f in facts if high.get('groupId') and f['kind']=='common_input_price_check' and f.get('groupId')==high.get('groupId')),None)
        if quote:required_refs['product_cost'].append(quote['id'])
        different=[f for f in bridges if f.get('groupId')!=high.get('groupId')]
        if different:
            normal=min(different,key=lambda f:f['statistics']['spread'])
            required_refs['product_cost'].append(normal['id'])
            if normal['statistics']['spread']<.01 and normal['statistics']['sampleSize']>=3:
                narrative['product_cost'].append('normal_control')
    product_coverage=[f for f in facts if f['kind']=='product_coverage']
    if product_coverage:
        worst=min(product_coverage,key=lambda f:(f['statistics']['eligibleRatio'],-f['statistics']['sampleSize']))
        required_refs['coverage'].append(worst['id'])
        if any('eligibleQuantityRatio' in f['statistics'] for f in product_coverage):narrative['coverage'].append('order_and_quantity_coverage')
    if trace_required:narrative['material'].append('business_link_is_not_batch')
    for section in section_ids:
        group=[f for f in facts if f['sectionId']==section]
        critical=[]
        for code in sorted(required[section]):
            fact=next(f for f in group if code in f['considerations'])
            if fact not in critical:critical.append(fact)
        critical += [f for f in group if f['id'] in required_refs[section] and f not in critical]
        selected.extend(critical+[f for f in group if f not in critical][:max(0,18-len(critical))])
    if include_all:selected=facts
    retained={f['id'] for f in selected}
    return {'version':VERSION,'isCostDataset':has_cost,'sectionIds':section_ids,'facts':selected,
            'displayFacts':{ref:value for ref,value in display.items() if ref in retained},
            'requiredConsiderations':{s:sorted(required[s]) for s in section_ids},
            'requiredFactRefs':required_refs,
            'narrativeRequirements':narrative,
            'omittedFacts':len(facts)-len(selected)+report.get('analysisMetrics',{}).get('omittedGroups',0)}
