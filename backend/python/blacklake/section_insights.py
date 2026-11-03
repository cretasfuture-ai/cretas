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


def fmt(value, digits=4):
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
    common = ['金额币种未由来源返回，待核；材料和人工只是可识别直接成本，不是完整制造成本或毛利。',
              '原料/半成品到成品缺逐批对应及期初在制品；不能把上游工单成本再叠加到下游领料估值。']
    result = {}
    def add(key, summary, observations, calculation, hypotheses, limitations, steps):
        alerts = section_alerts(analysis, key)
        result[key] = {'sectionId': key, 'title': SECTIONS[key][0], 'summary': summary,
            'observations': observations, 'calculation': calculation, 'hypotheses': hypotheses,
            'limitations': limitations, 'nextSteps': steps,
            'suggestedQuestions': ['这个数字怎么算的？', '目前缺什么，影响哪些判断？', '接下来优先查什么？'],
            'scope': {'runId': analysis['runId']}, 'evidenceRefs': [a['id'] for a in alerts]}

    reasons = Counter(issue for p in cost['projects'] if not p['unitCostAvailable'] for issue in p['issues'])
    add('coverage', f'{eligible}/{total} 张工单通过单位直接成本字段校验，另外 {total-eligible} 张仍需补证据。',
        ['通过字段校验不代表实际材料和人工已经归集完整。'] +
        [f'{reason}：影响 {count} 张（同一工单可有多个原因，不可相加）。' for reason, count in reasons.most_common(5)],
        ['可核算占比 = 通过字段校验工单数 ÷ 可见工单总数；不是金额覆盖率。'],
        ['缺失可能来自未报工、未归集或关联未填，当前数据不能区分真实原因。'], common,
        ['先核实领退料工单关联，再补历史估值、人工与成品量；保留原始缺失和源端零值。'])

    add('material', f'已关联 {cost["linkedMaterialLines"]} 行领退料；{cost["unlinkedMaterialLines"]} 行尚未确认工单，{cost["sourceZeroCostLines"]} 行金额为源端零值。',
        ['下表按物料与单位保留净消耗；不同单位不相加。',
         f'来源金额有值 {cost["knownMaterialCostLines"]} 行，其中零值不等于材料免费。'],
        ['材料净额 = 已审批生产领料成本 − 已审批退料成本。',
         '单位材料成本 = 该工单已识别材料净额 ÷ 该工单最终良品量。'],
        ['材料差异可能受领料估值、投入量、替代料或退料影响，需原始单据确认。'], common +
        ['BOM 单位用量基准未核，不能据此断言超耗；当前移动平均价不能回填历史领料价。'],
        ['仓库核对未关联的领退料单；财务复核零估值发生时点的成本单价。'])

    labor_rows = sorted((p for p in review['projects'] if p.get('laborShare') is not None), key=lambda p:p['laborShare'], reverse=True)
    labor_obs = [f'任务累计工资有值 {cost["knownLaborTasks"]}/{production["taskCount"]} 个任务；有值包括需要核实的零值。']
    labor_obs += [f'{p["projectCode"]}（{p["product"]}）人工占可识别直接成本 {p["laborShare"]:.1%}，单位人工 {fmt(p["laborPerFinished"])} / {p["unit"]}。' for p in labor_rows[:2]]
    add('labor', '人工只使用工序任务累计工资，不叠加报工估算工资；比例偏高是核对信号。', labor_obs,
        ['工单人工成本 = 所属工序任务累计工资之和；任一必需工资缺失则不能默认为零。',
         '人工占比 = 人工成本 ÷ 已识别材料与人工合计；材料估值偏低也会使占比升高。'],
        ['可能是计薪、报工归集或工艺劳动强度差异；不能仅凭占比认定效率低。'], common,
        ['生产复核累计工时与审批报工，财务核对工资口径；同时核实材料估值是否完整。'])

    add('finished', '工单、工序任务和报工是不同粒度；成品数量只按工单最终良品口径计算一次。',
        [f'可见工单 {total} 张，任务 {production["taskCount"]} 个，报工 {production["outputCount"]} 条；三者数量不互相求和。',
         f'目前只有 {eligible} 张工单同时具备单位成本所需字段。'],
        ['单位直接成本 =（已识别材料净额 + 工序任务累计工资）÷ 工单最终良品量。',
         '按产品和成品单位分别比较；不同工序的报工量不叠加为成品量。'],
        ['产出偏低可能源于在制、未审批或报工缺失，须查最终工序与成品入库。'], common,
        ['核对最终审批良品、完工状态与成品入库；单位换算需要明确基准。'])

    ranking = sorted(review['rankings'], key=lambda p:(p['unit'], -p['weightedUnitCost']))
    observations = [f'{p["product"]}（{p["productCode"]}）：{p["projects"]} 张可比口径工单，加权单位成本 {fmt(p["weightedUnitCost"])} / {p["unit"]}，工单区间 {fmt(p["minimum"])}—{fmt(p["maximum"])}。' for p in ranking]
    comparisons = [c for c in analysis['costIntelligence']['comparisons'] if c.get('bridge')]
    observations += [f'{c["product"]}同产品最高与最低工单差额 {fmt(c["bridge"]["delta"])} / {c["unit"]}，其中材料差额 {fmt(c["bridge"]["materialDelta"])}、人工差额 {fmt(c["bridge"]["laborDelta"])}；这不是时间趋势。' for c in comparisons]
    add('product_cost', f'{eligible} 张工单可展示可识别直接成本，按产品与单位分组；成本水平高不直接等于异常。', observations,
        ['产品加权单位成本 = 该产品同单位合格子集直接成本合计 ÷ 该子集最终良品合计；不平均各工单单位成本。',
         '同产品差额 = 材料单位成本差额 + 人工单位成本差额；逐物料贡献与总差额进行对账。'],
        ['材料价格、耗用、人工、规格和工艺阶段都可能影响差额；目前只能证明代数贡献。'], common +
        ['产品名称冲突、规格差异仍须核；最低观测值不是标准成本或目标成本。'],
        ['优先看同产品差额分解，再回查差异最大的物料与人工；不可跨产品据排名判断浪费。'])

    completion='未提供' if production['completionRate'] is None else fmt(production['completionRate']*100,2)+'%'
    add('production', f'已完工 {production["statuses"].get("20",0)}/{total} 张，状态口径完成率 {completion}。',
        ['计划与实际按产品和单位展示；累计工时高只表示任务工时汇总高，不证明瓶颈或人员效率低。'],
        ['状态完成率 = 完工状态工单数 ÷ 可见工单数；良品率按报工良品 ÷（良品 + 不良）计算，缺失不补零。'],
        ['工时偏高可能与批量、在制、停机或报工方式相关，当前数据不能区分。'],
        ['缺少经过核验的标准工时、班次和延期基准，不输出已证实的效率或延期因果。'],
        ['检查审批状态、任务持续时长和最终工序；再补标准工时与排程基准。'])
    inventory = analysis['inventory']
    add('inventory', f'当前库存返回 {len(inventory["records"])} 行余额，历史出入库单 {inventory["movementOrders"]} 张。',
        [f'{inventory["pairsNotReturned"]} 个产品/仓库查询组合未返回余额；这不等于零库存。',
         '库存是采集时点快照；领退料历史与快照不在同一时间粒度。'],
        ['库存按仓库、产品、来源单位分别看；材料成本只使用具备关联且符合状态的领退料单。'],
        ['未返回可能是无记录、查询可见范围或仓库产品组合不适用，不能直接归因。'],
        ['缺少期初余额和完整收发存时间边界，不能由当前快照证明历史收发存已平衡。'],
        ['仓库核对未返回组合和未关联领退料，财务对照期初与期间审批收发记录。'])
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
    for title, lines in groups:
        text.append(title+'：\n'+'\n'.join('• '+line for line in lines))
    return '\n\n'.join(text)
