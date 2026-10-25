"""Conditioned factory views using the existing chart strategies."""
import pandas as pd
from smartbi.services.charts.registry import get_strategy
from smartbi.services.financial import metrics as M
from .metrics import Series, number, grouped, display


def build(frame, cols, request, can_price, source):
    from .engine import series_candidate
    charts, gaps = [], []

    def candidate(series, kind, option):
        c = series_candidate(series, 'bar')
        c['id'] = series.key + ':' + kind
        c['chart'] = {'chartType': kind, 'echartsOption': option}
        option['animation'] = False
        return c

    if all(cols[k] for k in ('unit', 'output', 'planned')) and (cols['date'] or cols['product']):
        for unit, group in frame.groupby(cols['unit'], dropna=False):
            dim = cols['date'] or cols['product']
            actual = grouped(group, dim, cols['output'])
            planned = grouped(group, dim, cols['planned'])
            if pd.isna(unit) or not actual or not planned or any(v <= 0 for _, v in planned) or any(v < 0 for _, v in actual):
                gaps.append({'id': 'production.plan', 'title': '计划/实际产量', 'missing': ['完整产量及大于零的同单位计划分母']})
                continue
            data = pd.DataFrame({'项目': [k for k, _ in actual], '计划': [v for _, v in planned], '实际': [v for _, v in actual]})
            option = get_strategy('budget_comparison').build(data, '项目', ['计划', '实际'])
            option['xAxis']['data'] = [k for k, _ in actual]
            option['yAxis'][0]['name'] = option['yAxis'][0]['name'].replace('金额', '数量') + f'（{unit}）'
            for line in option['series']:
                if line['name'] == '预算':
                    line['name'] = '计划'
                    line['data'] = [v for _, v in planned]
                if line['name'] == '实际': line['data'] = [v for _, v in actual]
                if line['name'] == '达成率':
                    line['data'] = [M.ratio(a, p).value for (_, a), (_, p) in zip(actual, planned)]
                    line['itemStyle']['color'] = '#e6a23c'
                    option['yAxis'][1]['max'] = max(120, max(line['data']) * 1.1)
            option['legend']['data'] = ['计划' if n == '预算' else n for n in option['legend']['data']]
            s = Series(f'production.plan@{unit}', '计划与实际产量', '生产', str(unit), actual, source)
            c = candidate(s, 'budget_comparison', option)
            target, achieved = M.total(v for _, v in planned), M.total(v for _, v in actual)
            rate = M.ratio(achieved, target).value
            c['kpis'] += [{'label': '计划', 'value': display(target), 'unit': str(unit)}, {'label': '计划达成率', 'value': display(rate), 'unit': '%'}]
            c['findings']['attention' if rate < 100 else 'good'].append(f'实际 {display(achieved)} / 计划 {display(target)}{unit}，达成 {display(rate)}%')
            charts.append(c)
            if request.include_optional:
                import copy
                combo = copy.deepcopy(option)
                combo['series'] = [line for line in combo['series'] if line['name'] != '计划']
                combo['legend']['data'] = ['实际', '达成率']
                c = candidate(s, 'combination', combo)
                c['priority'] = 'optional'
                charts.append(c)
                bullet = get_strategy('bullet').build(data.copy(), '项目', ['实际', '计划'])
                c = candidate(s, 'bullet', bullet)
                c['priority'] = 'optional'
                charts.append(c)
                gauge = get_strategy('gauge').build(pd.DataFrame({'完成率': [rate]}), y_fields=['完成率'])
                gauge['series'][0]['max'] = max(100, rate * 1.1)
                gauge['series'][0]['detail']['formatter'] = '{value}%'
                rate_series = Series(f'production.plan-rate@{unit}', '累计计划达成率', '生产', '%', [('累计', rate)], source, 'ratio', achieved, target)
                c = candidate(rate_series, 'gauge', gauge)
                c['priority'] = 'optional'
                charts.append(c)
    else:
        gaps.append({'id': 'production.plan', 'title': '计划/实际产量', 'missing': ['期间或产品、计划产量、实际产量、同一数量单位']})

    if all(cols[k] for k in ('date', 'unit', 'output')) and (cols['process'] or cols['product']):
        dim = cols['process'] or cols['product']
        for unit, group in frame.groupby(cols['unit'], dropna=False):
            if pd.isna(unit) or group[[dim, cols['date']]].isna().any().any() or group[cols['output']].map(number).isna().any():
                continue
            if group[dim].nunique() < 2 or group[cols['date']].nunique() < 2:
                gaps.append({'id': 'production.heatmap', 'title': '日期与工序/产品产量热力图', 'missing': ['日期与工序/产品至少各有两个有效分组']})
                continue
            work = group[[cols['date'], dim, cols['output']]].copy()
            work[cols['output']] = work[cols['output']].map(number)
            work = work.groupby([cols['date'], dim], as_index=False)[cols['output']].sum()
            work[cols['date']] = work[cols['date']].astype(str)
            work[dim] = work[dim].astype(str)
            option = get_strategy('heatmap').build(work, cols['date'], [cols['output']], dim)
            points = [(f'{r[cols["date"]]} / {r[dim]}', r[cols['output']]) for _, r in work.iterrows()]
            s = Series(f'production.heatmap@{unit}', '日期与工序/产品产量', '生产', str(unit), points, source)
            charts.append(candidate(s, 'heatmap', option))
            if request.include_optional and work[dim].nunique() >= 3 and work[cols['date']].nunique() >= 3:
                import copy
                c = candidate(s, 'matrix_heatmap', copy.deepcopy(option))
                c['priority'] = 'optional'
                charts.append(c)
    else:
        gaps.append({'id': 'production.heatmap', 'title': '产量热力图', 'missing': ['日期、工序/产品、产量、单位']})

    if can_price and all(cols[k] for k in ('process', 'material_cost', 'labor_cost')):
        operands = [cols['material_cost'], cols['labor_cost']]
        if not frame[cols['process']].isna().any() and all(frame[c].map(number).notna().all() for c in operands):
            work = frame[[cols['process']] + operands].copy()
            for c in operands: work[c] = work[c].map(number)
            work = work.groupby(cols['process'], as_index=False)[operands].sum()
            option = get_strategy('bar').build(work, cols['process'], operands)
            for series in option['series']: series['stack'] = '已报告成本'
            s = Series('process.cost', '工序已报告材料/人工成本', '成本', '元',
                       [(str(r[cols['process']]), sum(r[c] for c in operands)) for _, r in work.iterrows()], source)
            c = candidate(s, 'bar', option)
            c['findings']['attention'].append('仅含已报告材料与人工成本，不代表完整工序总成本')
            charts.append(c)
        else:
            gaps.append({'id': 'process.cost', 'title': '工序已报告成本', 'missing': ['材料/人工成本数值完整；未归集成本不能补零']})
    else:
        gaps.append({'id': 'process.cost', 'title': '工序已报告成本', 'missing': ['工序、已报告材料成本/人工成本及金额权限']})

    ledger_cols = ['期初库存', '入库数量', '出库数量', '期末库存']
    if len(frame) == 1 and cols['unit'] and all(c in frame for c in ledger_cols):
        row = frame.iloc[0]
        vals = [number(row[c]) for c in ledger_cols]
        if all(v is not None and v >= 0 for v in vals) and pd.notna(row[cols['unit']]) and abs(vals[0]+vals[1]-vals[2]-vals[3]) < 0.01:
            points = [('期初', vals[0]), ('入库', vals[1]), ('出库', -vals[2])]
            option = get_strategy('waterfall').build(pd.DataFrame(points, columns=['项目', '数量']), '项目', ['数量'])
            # Ending is a balance column, not another delta.
            from smartbi.services.charts.common import detect_value_scale
            divisor = detect_value_scale([v for _, v in points])['divisor']
            option['xAxis']['data'].append('期末')
            for i, series in enumerate(option['series']): series['data'].append(vals[3]/divisor if i == 1 else 0)
            s = Series('stock.balance', '库存收发余额核对', '库存', str(row[cols['unit']]), points+[('期末', vals[3])], source, 'balance')
            charts.append(candidate(s, 'waterfall', option))
        else:
            gaps.append({'id': 'stock.balance', 'title': '库存收发余额', 'missing': ['期初+入库-出库=期末，且单位一致']})
    else:
        gaps.append({'id': 'stock.balance', 'title': '库存收发余额', 'missing': ['单一期间同单位的期初、入库、出库、期末余额；单张采购表不足']})

    # Statistical variants require identified, same-SKU unit samples, not aggregated means.
    if request.include_optional and can_price and all(cols[k] for k in ('product', 'unit', 'cost', 'output')) and '批次' in frame:
        for (sku, unit), group in frame.groupby([cols['product'], cols['unit']], dropna=False):
            if pd.isna(sku) or pd.isna(unit) or len(group) < 5 or group['批次'].isna().any() or group['批次'].nunique() != len(group):
                continue
            cost, qty = group[cols['cost']].map(number), group[cols['output']].map(number)
            if cost.isna().any() or qty.isna().any() or (qty <= 0).any() or (cost < 0).any(): continue
            values = [M.ratio(c, q, 1).value for c, q in zip(cost, qty)]
            data = pd.DataFrame({'批次': group['批次'].astype(str).tolist(), f'产量（{unit}）': qty.tolist(), f'单位成本（元/{unit}）': values})
            s = Series(f'cost.scatter@{sku}@{unit}', f'{sku}批次产量与单位成本', '成本', f'元/{unit}', list(zip(data['批次'], values)), source, 'unit_cost', M.total(cost), M.total(qty))
            option = get_strategy('scatter').build(data, data.columns[1], [data.columns[2]])
            c = candidate(s, 'scatter', option)
            c['priority'] = 'optional'
            c['findings']['attention'].append(f'同产品{len(group)}个批次，仅展示关联，不代表因果')
            charts.append(c)
    return charts, gaps
