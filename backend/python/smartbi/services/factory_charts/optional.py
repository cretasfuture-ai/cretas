"""Specialized views are admitted only with their explicit factory evidence."""
import re
import statistics
import pandas as pd
from smartbi.services.charts.registry import get_strategy
from smartbi.services.financial import metrics as M
from .metrics import Series, number


def build(frame, cols, request, can_price, source):
    if not request.include_optional:
        return [], []
    from .engine import series_candidate
    charts, gaps = [], []

    def emit(series, kind, option, note=''):
        c = series_candidate(series, 'bar')
        c.update(id=series.key + ':' + kind, priority='optional')
        c['chart'] = {'chartType': kind, 'echartsOption': option}
        option['animation'] = False
        if note: c['findings']['attention'].append(note)
        charts.append(c)
        return c

    # Quantity / unit cost dual axes: one product and unit, aligned by period.
    if (not getattr(request, 'guided', False) or getattr(request, 'analysis_subject', None)) and can_price and all(cols[k] for k in ('product', 'date', 'unit', 'cost', 'output')):
        for (sku, unit), group in frame.groupby([cols['product'], cols['unit']], dropna=False):
            if pd.isna(sku) or pd.isna(unit) or group[cols['date']].nunique() < 2:
                continue
            if any(group[cols[k]].map(number).isna().any() for k in ('cost', 'output')) or (group[cols['output']].map(number) <= 0).any():
                continue
            work = group[[cols['date'], cols['cost'], cols['output']]].copy()
            for k in ('cost', 'output'): work[cols[k]] = work[cols[k]].map(number)
            work = work.groupby(cols['date'], as_index=False).sum()
            q = work[cols['output']].tolist()
            costs = [M.ratio(c, v, 1).value for c, v in zip(work[cols['cost']], q)]
            data = pd.DataFrame({'日期': work[cols['date']].astype(str), f'产量（{unit}）': q, f'单位成本（元/{unit}）': costs})
            s = Series(f'cost.dual@{sku}@{unit}', f'{sku}产量与单位成本', '成本', f'元/{unit}', list(zip(data['日期'], costs)), source,
                       'unit_cost', M.total(work[cols['cost']]), M.total(q))
            option = get_strategy('dual_axis').build(data, '日期', list(data.columns[1:]))
            emit(s, 'dual_axis', option, '左右轴单位不同；同一产品同一单位，不推断产量变化是成本变化的原因')

    # Explicit two-period, complete SKU panel; neither missing cells nor negative shares are filled.
    value = cols['output'] or cols['sales']
    if value and cols['date'] and cols['product'] and cols['unit'] and (value != cols['sales'] or can_price):
        for unit, group in frame.groupby(cols['unit'], dropna=False):
            if pd.isna(unit) or group[cols['date']].nunique() != 2 or group[cols['product']].nunique() < 2:
                continue
            if not group[cols['date']].astype(str).str.match(r'^\d{4}-\d{2}').all() or group[value].map(number).isna().any(): continue
            work = group.copy()
            work[value] = work[value].map(number)
            pivot = work.pivot_table(index=cols['product'], columns=cols['date'], values=value, aggfunc='sum')
            if pivot.isna().any().any() or len(pivot) > 8: continue
            data = pivot.reset_index()
            before, after = list(pivot.columns)
            data.columns = ['产品', '前期', '本期']
            points = list(zip(data['产品'].astype(str), data['本期'].astype(float)))
            s = Series(f'production.slope@{unit}', f'两期产品比较（{before} / {after}）', '生产', str(unit), points, source)
            emit(s, 'slope', get_strategy('slope').build(data, '产品', ['前期', '本期']))
            if (pivot.values >= 0).all() and all(pivot[c].sum() > 0 for c in pivot):
                option = get_strategy('nested_donut').build(work, cols['date'], [value], cols['product'])
                for i, period in enumerate((before, after)):
                    option['series'][i]['name'] = str(period)
                    option['series'][i]['data'] = [{'name': str(k), 'value': float(v)} for k, v in pivot[period].items()]
                emit(s, 'nested_donut', option, '内外环分别为两期同一产品集合，金额/数量单位一致')

    # Batch distributions need actual identified observations, not period averages.
    if (not getattr(request, 'guided', False) or getattr(request, 'analysis_subject', None)) and can_price and all(cols[k] for k in ('product', 'unit', 'cost', 'output')) and '批次' in frame:
        for (sku, unit), group in frame.groupby([cols['product'], cols['unit']], dropna=False):
            if pd.isna(sku) or pd.isna(unit) or len(group) < 5 or group['批次'].isna().any() or group['批次'].nunique() != len(group): continue
            costs, qty = group[cols['cost']].map(number), group[cols['output']].map(number)
            if costs.isna().any() or qty.isna().any() or (costs < 0).any() or (qty <= 0).any(): continue
            values = [M.ratio(c, q, 1).value for c, q in zip(costs, qty)]
            field = f'批次单位成本（元/{unit}）'
            s = Series(f'cost.distribution@{sku}@{unit}', f'{sku}批次单位成本分布', '成本', f'元/{unit}', list(zip(group['批次'].astype(str), values)), source,
                       'unit_cost', M.total(costs), M.total(qty))
            emit(s, 'boxplot', get_strategy('boxplot').build(pd.DataFrame({field: values}), y_fields=[field]), f'逐批次样本{len(group)}条；累计KPI按总成本÷总产量计算，箱线展示逐批分布')

    # Real task dates. Keep day granularity explicit and avoid unregistered formatter aliases.
    if all(c in frame for c in ('任务', '开始日期', '结束日期')):
        start, end = pd.to_datetime(frame['开始日期'], errors='coerce'), pd.to_datetime(frame['结束日期'], errors='coerce')
        if len(frame) <= 100 and not frame['任务'].isna().any() and frame['任务'].nunique() == len(frame) and start.notna().all() and end.notna().all() and (end >= start).all():
            option = get_strategy('gantt').build(frame, '任务', ['开始日期', '结束日期'])
            meta = option['_ganttMeta']
            option['xAxis']['axisLabel']['formatter'] = '{value}天'
            option['xAxis']['name'] = '距' + meta['refDate'] + '的天数'
            option['series'][0]['tooltip'] = {'show': False}
            option['series'][1]['label']['formatter'] = '{b}'
            option['tooltip'] = {'trigger': 'item', 'renderMode': 'richText', 'formatter': '{b}: {c}天'}
            s = Series('production.gantt', '任务计划时间', '生产', '天', [(r['name'], r['duration']) for r in meta['tasks']], source)
            emit(s, 'gantt', option, '日粒度任务时长；任务时长相加不是整个项目历时')

    # A funnel requires one cohort and nested object sets, not independent status counts.
    if all(c in frame for c in ('队列ID', '阶段', '阶段序号', '对象ID')):
        if frame['队列ID'].nunique() == 1 and frame[['阶段', '阶段序号', '对象ID']].notna().all().all():
            steps = []
            for _, group in frame.groupby('阶段序号', sort=True):
                if group['阶段'].nunique() != 1: steps = []; break
                steps.append((str(group['阶段'].iloc[0]), set(group['对象ID'].astype(str))))
            if len(steps) >= 2 and all(b <= a for (_, a), (_, b) in zip(steps, steps[1:])):
                points = [(name, len(ids)) for name, ids in steps]
                s = Series('sales.cohort', '同队列阶段转化', '销售', '个对象', points, source, 'cohort')
                option = get_strategy('funnel').build(pd.DataFrame(points, columns=['阶段', '数量']), '阶段', ['数量'])
                option['series'][0]['sort'] = 'none'
                emit(s, 'funnel', option, '仅统计同一队列去重对象；各阶段对象集合逐级包含')

    # A material flow needs traceable, nonnegative conserved, acyclic edges.
    if all(c in frame for c in ('来源节点', '去向节点', '流量', '单位', '流转记录ID')):
        if frame['单位'].nunique() == 1 and frame['流转记录ID'].nunique() == len(frame) and frame[['来源节点','去向节点','单位','流转记录ID']].notna().all().all():
            vals = frame['流量'].map(number)
            nodes = set(frame['来源节点']) | set(frame['去向节点'])
            balances = {n: 0.0 for n in nodes}
            adjacency = {n: [] for n in nodes}
            if vals.notna().all() and (vals > 0).all():
                for (_, row), v in zip(frame.iterrows(), vals):
                    balances[row['来源节点']] -= v; balances[row['去向节点']] += v
                    adjacency[row['来源节点']].append(row['去向节点'])
                middle = set(frame['来源节点']) & set(frame['去向节点'])
                # Kahn's traversal is linear and has no recursion/depth explosion.
                from collections import deque
                incoming = {n: 0 for n in nodes}
                for targets in adjacency.values():
                    for target in targets: incoming[target] += 1
                ready = deque(n for n, count in incoming.items() if count == 0)
                visited = 0
                while ready:
                    node = ready.popleft()
                    visited += 1
                    for target in adjacency[node]:
                        incoming[target] -= 1
                        if incoming[target] == 0: ready.append(target)
                if all(abs(balances[n]) < .01 for n in middle) and visited == len(nodes):
                    work = pd.DataFrame({'source': frame['来源节点'], 'target': frame['去向节点'], 'value': vals})
                    option = get_strategy('sankey').build(work, 'source', ['value'], 'target')
                    s = Series('production.flow', '已记录物料流转', '生产', str(frame['单位'].iloc[0]), [(str(r['来源节点'])+'→'+str(r['去向节点']), v) for (_, r), v in zip(frame.iterrows(), vals)], source, 'flow')
                    emit(s, 'sankey', option, '边流量不重复累加为产量；仅展示已记录且中间节点守恒的流转')

    # Explicit normalized process columns are the only source for multivariate views.
    params = [c for c in frame if '归一化' in str(c) and frame[c].map(number).notna().all()]
    if '批次' in frame and len(params) >= 3 and 5 <= len(frame) <= 100 and frame['批次'].nunique() == len(frame):
        work = frame[['批次'] + params].copy()
        for col in params: work[col] = work[col].map(number)
        if all(work[c].between(0, 1).all() for c in params):
            s = Series('production.parameters', '明确归一化的工艺参数', '生产', '归一化值', [(str(r['批次'])+' / '+str(c), r[c]) for _, r in work.iterrows() for c in params], source, 'nonadditive')
            option = get_strategy('parallel').build(work, '批次', params)
            option['series'][0]['data'] = [[round(float(r[c]), 2) for c in params] for _, r in work.iterrows()]
            for axis in option['parallelAxis']:
                axis.update(min=0, max=1)
            emit(s, 'parallel', option, '输入已声明归一化到0至1，不计算综合评分或原因')
            if len(frame) <= 8:
                option = get_strategy('radar').build(work, '批次', params, '批次')
                for indicator in option['radar']['indicator']: indicator['max'] = 1
                emit(s, 'radar', option, '各轴为来源已声明的0至1归一化值，不计算综合评分')
            if len(frame) >= 10 and all(work[c].nunique() > 1 for c in params):
                option = get_strategy('correlation_matrix').build(work, y_fields=params)
                emit(s, 'correlation_matrix', option, f'同批对齐{len(work)}个样本；相关性不代表因果')

    # Hierarchy uses two real level columns and explicit monetary units.
    if can_price and all(c in frame for c in ('一级类别', '二级类别', '金额（元）')):
        work = frame[['一级类别', '二级类别', '金额（元）']].copy()
        work['金额（元）'] = work['金额（元）'].map(number)
        if work.notna().all().all() and (work['金额（元）'] >= 0).all() and work['金额（元）'].sum() > 0:
            s = Series('cost.hierarchy', '真实层级金额结构', '成本', '元', [(f'{r["一级类别"]}/{r["二级类别"]}', r['金额（元）']) for _, r in work.iterrows()], source)
            option = get_strategy('sunburst').build(work, '一级类别', ['金额（元）'], options={'hierarchy':['一级类别','二级类别']})
            emit(s, 'sunburst', option)
            if len(work) >= 6:
                option = get_strategy('treemap').build(work, '二级类别', ['金额（元）'], options={})
                emit(s, 'treemap', option)

    if (not getattr(request, 'guided', False) or getattr(request, 'analysis_subject', None)) and all(cols[k] for k in ('product', 'unit', 'date', 'output')):
        for (sku, unit), group in frame.groupby([cols['product'], cols['unit']], dropna=False):
            if pd.isna(sku) or pd.isna(unit) or group[cols['date']].nunique() < 12 or group[cols['output']].map(number).isna().any(): continue
            work = group[[cols['date'], cols['output']]].copy()
            work[cols['output']] = work[cols['output']].map(number)
            work = work.groupby(cols['date'], as_index=False).sum().sort_values(cols['date'])
            values = work[cols['output']].tolist()
            mean, deviation = statistics.mean(values), statistics.pstdev(values)
            anomalies = [i for i, v in enumerate(values) if deviation > 0 and abs(v-mean) > 3*deviation]
            s = Series(f'production.anomaly@{sku}@{unit}', f'{sku}产量异常观察', '生产', str(unit), list(zip(work[cols['date']].astype(str), values)), source)
            option = get_strategy('line').build(work, cols['date'], [cols['output']])
            # The strategy can scale values; mark the actual rendered y-values.
            option['series'][0]['markPoint'] = {'data': [{'coord': [str(work.iloc[i][cols['date']]), option['series'][0]['data'][i]], 'name': '偏离本组均值'} for i in anomalies]}
            emit(s, 'line', option, f'历史{len(values)}期，{len(anomalies)}期偏离本组均值超过三个标准差；仅统计观察，不推断原因')

    requirements = {
        'dual_axis': ['同产品、同单位、至少两个对齐期间的产量与完整成本'],
        'nested_donut': ['恰好两个可比期间、完整一致的少量产品集合、同单位非负金额/数量'],
        'slope': ['两个有年份的期间、完整一致的产品集合、同一单位'],
        'scatter': ['同一产品同单位至少五个可追溯批次、完整产量和成本'],
        'boxplot': ['同一产品同单位至少五个逐批样本，不使用汇总均值'],
        'gantt': ['唯一任务、有效且先后正确的开始/结束日期'],
        'funnel': ['同队列、唯一对象、阶段顺序与逐级包含的对象集合'],
        'sankey': ['唯一流转记录、来源/去向节点、同单位正流量、中间守恒和无环关系'],
        'parallel': ['至少五个唯一批次、三个明确归一化到0至1的工艺参数'],
        'radar': ['五至八个批次、三个明确归一化到0至1的指标及范围'],
        'correlation_matrix': ['至少十个同批对齐样本、三个非恒定的归一化数值列'],
        'sunburst': ['真实一级/二级类别、明确人民币金额和金额权限'],
        'treemap': ['真实多类别层级、明确人民币非负金额和金额权限'],
    }
    titles = {'dual_axis':'产量与单位成本双轴','nested_donut':'两期结构双环','slope':'两期产品变化','scatter':'批次关联',
              'boxplot':'批次分布','gantt':'任务计划','funnel':'队列转化','sankey':'真实物料流向','parallel':'多工艺参数',
              'radar':'归一化指标比较','correlation_matrix':'参数相关性','sunburst':'层级结构','treemap':'多类别结构'}
    produced = {c['chart']['chartType'] for c in charts}
    for kind, missing in requirements.items():
        if kind not in produced:
            gaps.append({'id': 'optional.'+kind, 'title': titles[kind], 'missing': missing})

    return charts, gaps
