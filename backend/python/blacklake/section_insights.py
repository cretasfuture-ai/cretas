"""Evidence-bound section explanations; no source queries or cross-stage sums."""
from collections import Counter

SECTIONS = {
    'coverage': ('核算覆盖与数据缺口', ('readiness', 'data_quality')),
    'material': ('原料投入与材料成本', ()),
    'labor': ('加工工时与人工成本', ()),
    'finished': ('成品产出与核算口径', ()),
    'product_cost': ('产品单位直接成本', ('business_signal',)),
    'production': ('生产进度与报工质量', ()),
    'inventory': ('库存余额与领退料关联', ()),
}


def fmt(value, digits=2):
    return '未提供' if value is None else f'{value:,.{digits}f}'.rstrip('0').rstrip('.')


def section_alerts(analysis, section, project_id=None):
    items = analysis['costAlerts']['items']
    if project_id is not None:
        items = [a for a in items if any(str(p['projectId']) == str(project_id) for p in a['entityRefs'])]
    if section == 'coverage':
        return [a for a in items if a['blocksUnitCost'] or a['family'] == 'data_quality']
    if section == 'product_cost':
        return [a for a in items if a['family'] == 'business_signal' or a['type'] == 'PRODUCT_NAME_CONFLICT']
    fields = {'material': ('material', 'costUnitPrice', 'changedCost', 'input'),
              'labor': ('labor', 'salary', 'outputDuration'),
              'finished': ('finished', 'good', 'unit', 'status'),
              'production': ('good', 'task', 'status', 'approval'),
              'inventory': ('material', 'stock', 'associated')}[section]
    return [a for a in items if any(k.lower() in ' '.join(a['fields']).lower() for k in fields)]


def build(analysis):
    cost, production = analysis['cost'], analysis['production']
    review = analysis['eligibleCostReview']
    eligible = review['eligibleProjects']; total = production['projectCount']
    common = ['黑湖未说明金额是什么币种，需要财务确认。这里仅包含已记录的材料和人工，未包含所有制造费用，不能用来算完整制造成本或毛利。',
              '还缺每批原料、半成品与成品的对应记录，以及期初尚未做完的货。半成品领料金额若已含上游成本，就不能再加一次上游工单成本。']
    result = {}
    def add(key, summary, observations, calculation, hypotheses, limitations, steps):
        alerts = section_alerts(analysis, key)
        sources = {
            'coverage': [('工单及成本检查结果', '逐张检查完工状态、成品数量、领退料金额和任务工资；一张工单可能缺多项资料。')],
            'material': [('已审批的生产领料、退料明细', '使用已关联到工单的材料数量、历史单价和领退料金额；未关联的单据单独列出。')],
            'labor': [('工序任务的累计工资与工时', '按任务所属工单汇总工资；报工中的估算工资不会再加一次。')],
            'finished': [('工单最终良品数量与完工状态', '成品量按工单最终良品计算，工序任务和各次报工的数量不重复累加。')],
            'product_cost': [('材料领退料、任务工资和工单最终良品', '只比较资料具备的工单；同产品名称、代码和成品单位分别计算。')],
            'production': [('工单状态、工序任务及报工记录', '工单用于看完工情况；任务用于看累计工时；报工用于核对良品、不良品及审批情况。')],
            'inventory': [('仓库库存余额及历史出入库单', '库存是采集时的余额，领退料是历史记录；两者不能直接当作同一期间的收发存对账。')],
        }
        by_id = {str(p['projectId']): p for p in cost['projects']}
        related = {}
        for alert in alerts:
            for reference in alert['entityRefs']:
                project = by_id.get(str(reference['projectId']))
                if project is None:
                    continue
                entry = related.setdefault(str(project['projectId']), {
                    'projectId': project['projectId'], 'projectCode': project['projectCode'],
                    'product': project['product'], 'reasons': []})
                if alert['title'] not in entry['reasons']:
                    entry['reasons'].append(alert['title'])
        scope = (f'本次采集的全部 {total} 张工单；其中 {eligible} 张资料具备，可展示单位材料加人工成本。'
                 if key != 'inventory' else '本次采集时的库存余额，以及账号可见的历史出入库记录。')
        result[key] = {'sectionId': key, 'title': SECTIONS[key][0], 'summary': summary,
            'observations': observations, 'calculation': calculation, 'hypotheses': hypotheses,
            'limitations': limitations, 'nextSteps': steps,
            'suggestedQuestions': ['这个数字怎么算的？', '目前缺什么，影响哪些判断？', '接下来优先查什么？'],
            'scope': {'runId': analysis['runId']}, 'evidenceRefs': [a['id'] for a in alerts],
            'scopeDescription': scope + ' 这是这组数据的整体分析，不是按固定天数切成的一段；不随图表单位、搜索或表格筛选改变。',
            'evidenceSources': [{'label': label, 'description': description} for label, description in sources[key]],
            'relatedOrders': list(related.values())}

    reasons = Counter(issue for p in cost['projects'] if not p['unitCostAvailable'] for issue in p['issues'])
    add('coverage', f'{eligible}/{total} 张工单资料具备，可以先算材料加人工的单位成本；另外 {total-eligible} 张还缺资料或尚未满足完工条件。',
        ['能算出数字，不代表所有食材和工资都已经记全。'] +
        [f'{reason}：影响 {count} 张（同一工单可有多个原因，不可相加）。' for reason, count in reasons.most_common(5)],
        ['可核算占比 = 资料具备的工单数 ÷ 本次可见工单总数；表示有多少工单能算，不表示已记录多少成本金额。'],
        ['缺失可能来自未报工、未归集或关联未填，当前数据不能区分真实原因。'], common,
        ['先核实领退料工单关联，再补历史估值、人工与成品量；保留原始缺失和源端零值。'])

    add('material', f'已关联 {cost["linkedMaterialLines"]} 行领退料；{cost["unlinkedMaterialLines"]} 行尚未确认工单，{cost["sourceZeroCostLines"]} 行金额为源端零值。',
        ['材料数量按领料减退料计算，同一种材料、同一种单位分别看；公斤和包不能直接相加。',
         f'来源金额有值 {cost["knownMaterialCostLines"]} 行，其中零值不等于材料免费。'],
        ['材料净额 = 已审批生产领料成本 − 已审批退料成本。',
         '单位材料成本 = 该工单已识别材料净额 ÷ 该工单最终良品量。'],
        ['材料成本不同，可能是单价、用量、换料或退料不同，需要回看原单确认。'], common +
        ['标准配方的单位用量还没有核实，不能直接说超耗。今天的库存均价不能当作历史领料的价格。'],
        ['仓库核对未关联的领退料单；财务复核零估值发生时点的成本单价。'])

    labor_rows = sorted((p for p in review['projects'] if p.get('laborShare') is not None), key=lambda p:p['laborShare'], reverse=True)
    labor_obs = [f'任务累计工资有值 {cost["knownLaborTasks"]}/{production["taskCount"]} 个任务；有值包括需要核实的零值。']
    labor_obs += [f'{p["projectCode"]}（{p["product"]}）：每 {p["unit"]} 的人工成本为 {fmt(p["laborPerFinished"])} / {p["unit"]}，占已记录材料加人工成本的 {p["laborShare"]:.2%}。' for p in labor_rows[:2]]
    add('labor', '人工成本只加总各工序任务已记录的工资。人工占比高时先查材料有没有记全，再查工资；不能直接认定工人效率低。', labor_obs,
        ['工单人工成本 = 这张工单各工序任务的累计工资之和。工资没提供时标为缺失，不当作零。',
         '人工占比 = 人工成本 ÷ 已记录的材料加人工总额。材料少记了，也会让人工占比显得很高。'],
        ['可能是计薪、报工归集或工艺劳动强度差异；不能仅凭占比认定效率低。'], common,
        ['生产复核累计工时与审批报工，财务核对工资口径；同时核实材料估值是否完整。'])

    add('finished', '一张工单可能经过多道工序、分多次报工。同一批货不能每报一次就多算一次，成品数量只按工单最终良品计算一次。',
        [f'可见工单 {total} 张，任务 {production["taskCount"]} 个，报工 {production["outputCount"]} 条；三者数量不互相求和。',
         f'目前只有 {eligible} 张工单同时具备单位成本所需字段。'],
        ['单位直接成本 =（已识别材料净额 + 工序任务累计工资）÷ 工单最终良品量。',
         '按产品和成品单位分别比较；不同工序的报工量不叠加为成品量。'],
        ['产出偏低可能源于在制、未审批或报工缺失，须查最终工序与成品入库。'], common,
        ['核对最终审批良品、完工状态与成品入库；单位换算需要明确基准。'])

    ranking = sorted(review['rankings'], key=lambda p:(p['unit'], -p['weightedUnitCost']))
    observations = [f'{p["product"]}（产品编码 {p["productCode"]}）：{p["projects"]} 张工单，按产量计算的平均单位成本 {fmt(p["weightedUnitCost"])} / {p["unit"]}；各工单最低 {fmt(p["minimum"])}、最高 {fmt(p["maximum"])} / {p["unit"]}。' for p in ranking]
    comparisons = []
    for candidate in analysis['costIntelligence']['comparisons']:
        rows = [p for p in review['projects'] if p.get('productCode') == candidate.get('productCode')
                and p.get('unit') == candidate.get('unit')]
        if (candidate.get('bridge') and rows and len(rows) == candidate.get('eligibleProjects')
                and all(p['product'] == candidate['product'] for p in rows)):
            comparisons.append(candidate)
    observations += [f'{c["product"]}同产品最高与最低工单差额 {fmt(c["bridge"]["delta"])} / {c["unit"]}，其中材料差额 {fmt(c["bridge"]["materialDelta"])}、人工差额 {fmt(c["bridge"]["laborDelta"])}；这不是时间趋势。' for c in comparisons]
    add('product_cost', f'先分析资料具备的 {eligible} 张工单：每做出一单位成品，花了多少材料和人工。按同产品、同单位分别比较；贵不一定代表有问题。', observations,
        ['同产品平均单位成本 = 这些工单的材料加人工总额 ÷ 最终良品总量。大单小单按产量算，不能把每张工单的单价直接取平均。',
         '两张工单的单位成本差多少，拆成材料差多少、人工差多少，再逐项查材料明细。'],
        ['价格、用料量、工资、产品规格或生产阶段都可能影响差额。目前能算出钱差在哪一项，还不能确定为什么会差。'], common +
        ['产品名称冲突、规格差异仍须核；最低观测值不是标准成本或目标成本。'],
        ['优先看同产品差额分解，再回查差异最大的物料与人工；不可跨产品据排名判断浪费。'])

    completion='未提供' if production['completionRate'] is None else fmt(production['completionRate']*100,2)+'%'
    add('production', f'已完工 {production["statuses"].get("20",0)}/{total} 张，状态口径完成率 {completion}。',
        ['计划与实际按产品和单位展示；累计工时高只表示任务工时汇总高，不证明瓶颈或人员效率低。'],
        ['状态完成率 = 完工状态工单数 ÷ 可见工单数；良品率按报工良品 ÷（良品 + 不良）计算，缺失不补零。'],
        ['工时偏高可能与批量、在制、停机或报工方式相关，当前数据不能区分。'],
        ['缺少已核实的标准工时、班次和计划交期，暂时不能确定哪里效率低，或为什么延期。'],
        ['检查审批状态、任务持续时长和最终工序；再补标准工时与排程基准。'])
    inventory = analysis['inventory']
    add('inventory', f'当前库存返回 {len(inventory["records"])} 行余额，历史出入库单 {inventory["movementOrders"]} 张。',
        [f'{inventory["pairsNotReturned"]} 个产品/仓库查询组合未返回余额；这不等于零库存。',
         '库存是采集时点快照；领退料历史与快照不在同一时间粒度。'],
        ['库存按仓库、产品、来源单位分别看；材料成本只使用具备关联且符合状态的领退料单。'],
        ['未返回可能是无记录、查询可见范围或仓库产品组合不适用，不能直接归因。'],
        ['缺少期初余额和完整收发存时间边界，不能由当前快照证明历史收发存已平衡。'],
        ['仓库核对未返回组合和未关联领退料，财务对照期初与期间审批收发记录。'])
    trace=analysis.get('costTraceability')
    if trace:
        if trace['scope']['snapshotId']!=analysis['runId']:raise ValueError('TRACE_SECTION_SNAPSHOT_MISMATCH')
        from smartbi.services.cost_traceability import ACTION_SECTIONS
        for sid,section in result.items():
            actions=[a for a in trace['actions'] if ACTION_SECTIONS.get(a['code'],'coverage')==sid]
            section['dataRequests']=actions
            selected=sorted(actions,key=lambda a:(a['priority']!='high',a['impactType']!='unit_cost',a['workOrderCode']))[:2]
            section['nextSteps']=[f"{a['workOrderCode']}：{a['responsibleRole']}核对{a['missingData']} 补齐后：{a['afterProvided']}" for a in selected]+section['nextSteps']
    return list(result.values())


def answer(report, question, history=()):
    """Choose disclosed evidence, never guess missing facts or execute actions."""
    q = question.strip()
    if q in ('继续', '详细一点', '展开说说'):
        q = next((h['content'] for h in reversed(history) if h['role']=='user' and h['content'] not in ('继续','详细一点','展开说说')), q)
    if any(k in q for k in ('怎么算','计算','公式','口径')):
        groups = [('计算口径', report['calculation']), ('不能据此推断', report['limitations'])]
    elif any(k in q for k in ('缺','补','可信','完整')):
        groups = [('当前限制', report['limitations']), ('补证据的顺序', report['nextSteps'])]
    elif any(k in q for k in ('为什么','原因','异常','高','低','比较','差')):
        groups = [('已证实的观察', report['observations']), ('可能原因，尚未证实', report['hypotheses']), ('下一步', report['nextSteps'])]
    elif any(k in q for k in ('查','下一步','跟进','负责','建议','处理')):
        groups = [('建议核对', report['nextSteps']), ('当前限制', report['limitations'])]
    else:
        groups = [('当前可回答的证据', report['observations']), ('可继续问', report['suggestedQuestions'])]
    text = [report['title']+'：'+report['summary']]
    if report.get('scopeDescription'):
        text.append('分析范围：'+report['scopeDescription'])
    for title, lines in groups:
        text.append(title+'：\n'+'\n'.join('• '+line for line in lines))
    return '\n\n'.join(text)
