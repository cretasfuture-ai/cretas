import pandas as pd

from smartbi.api import financial_dashboard as api
from smartbi.services.factory_charts.advanced import build as build_advanced
from smartbi.services.factory_charts.engine import columns
from smartbi.services.factory_charts.optional import build as build_optional


def request(**kwargs):
    return api.FactoryRecommendationRequest(use_ai=False, **kwargs)


def chart(items, fragment):
    return next(item for item in items if fragment in item['id'])


def test_advanced_plan_variants_heatmap_and_findings_use_source_values():
    frame = pd.DataFrame([
        {'日期': '2026-01-01', '产品': '产品A', '工序': '切割', '单位': 'kg', '产量': 80, '计划产量': 100,
         '材料成本': 12, '人工成本': 6},
        {'日期': '2026-02-01', '产品': '产品A', '工序': '包装', '单位': 'kg', '产量': 100, '计划产量': 120,
         '材料成本': 20, '人工成本': 10},
    ])

    items, gaps = build_advanced(frame, columns(frame), request(), True, 'fixture')

    plan = [item for item in items if item['id'].startswith('production.plan@kg:')]
    assert {item['chart']['chartType'] for item in plan} == {
        'budget_comparison', 'combination', 'bullet'
    }
    gauge = chart(items, 'production.plan-rate@kg:gauge')
    required = chart(plan, ':budget_comparison')
    assert {k['label']: k['value'] for k in required['kpis']} == {
        '合计': '180', '计划': '220', '计划达成率': '81.82'
    }
    assert required['chart']['echartsOption']['legend']['data'] == ['计划', '实际', '达成率']
    actual_data = next(series['data'] for series in required['chart']['echartsOption']['series']
                       if series['name'] == '实际')
    assert sorted(actual_data) == [80.0, 100.0]
    option = required['chart']['echartsOption']
    assert option['xAxis']['data'] == ['2026-01-01', '2026-02-01']
    assert next(s['data'] for s in option['series'] if s['name'] == '达成率') == [80, 83.33]
    assert any('实际 180 / 计划 220kg，达成 81.82%' in finding
               for group in required['findings'].values() for finding in group)
    bullet = chart(plan, ':bullet')['chart']['echartsOption']
    assert sorted(bullet['series'][1]['data']) == [80.0, 100.0]
    assert gauge['chart']['echartsOption']['series'][0]['data'][0]['value'] == 81.82

    heatmap = chart(items, 'production.heatmap@kg')
    assert heatmap['chart']['chartType'] == 'heatmap'
    assert len(heatmap['chart']['echartsOption']['series'][0]['data']) == 2
    cost = chart(items, 'process.cost')
    assert cost['kpis'][0]['value'] == '48'
    assert any('仅含已报告材料与人工成本' in finding for finding in cost['findings']['attention'])
    assert not any(g['id'] == 'production.plan' for g in gaps)


def test_advanced_ledger_keeps_balance_and_rejects_imbalance():
    frame = pd.DataFrame([{
        '日期': '2026-01-01', '产品': '产品A', '工序': '切割', '单位': 'kg', '产量': 100, '计划产量': 100,
        '材料成本': 10, '人工成本': 5, '期初库存': 50, '入库数量': 30, '出库数量': 20, '期末库存': 60,
    }])
    items, _ = build_advanced(frame, columns(frame), request(), True, 'fixture')
    ledger = chart(items, 'stock.balance')
    assert ledger['kpis'][0]['value'] == '60'
    assert ledger['chart']['echartsOption']['xAxis']['data'][-1] == '期末'
    assert ledger['tableData'][-1] == {'label': '期末', 'value': 60}

    broken = frame.copy()
    broken.loc[0, '期末库存'] = 99
    broken_items, broken_gaps = build_advanced(broken, columns(broken), request(), True, 'fixture')
    assert not any('stock.balance' in item['id'] for item in broken_items)
    assert any(g['id'] == 'stock.balance' for g in broken_gaps)


def test_advanced_batch_scatter_requires_identified_positive_samples():
    frame = pd.DataFrame([
        {'产品': '产品A', '单位': 'kg', '批次': f'B{i}', '总成本': i * 10, '产量': 10}
        for i in range(1, 6)
    ])
    items, _ = build_advanced(frame, columns(frame), request(), True, 'fixture')
    scatter = chart(items, 'cost.scatter@产品A@kg')
    assert scatter['chart']['chartType'] == 'scatter'
    assert scatter['kpis'][0]['value'] == '3'
    assert any('同产品5个批次' in finding for finding in scatter['findings']['attention'])

    invalid = frame.copy()
    invalid.loc[0, '产量'] = 0
    invalid_items, invalid_gaps = build_advanced(invalid, columns(invalid), request(), True, 'fixture')
    assert not any('cost.scatter@产品A@kg' in item['id'] for item in invalid_items)
    assert not any(item['chart']['chartType'] == 'scatter' for item in invalid_items)
    assert invalid_gaps is not None


def test_optional_gantt_and_batch_distribution_render_real_values():
    batch = pd.DataFrame([
        {'日期': f'2026-0{i}-01', '产品': '产品A', '单位': 'kg', '批次': f'B{i}', '总成本': i * 10, '产量': 10}
        for i in range(1, 6)
    ])
    items, _ = build_optional(batch, columns(batch), request(), True, 'fixture')
    box = chart(items, 'cost.distribution@产品A@kg')
    assert box['chart']['chartType'] == 'boxplot'
    assert box['kpis'][0]['value'] == '3'
    assert any('逐批次样本5条' in finding for finding in box['findings']['attention'])
    assert box['chart']['echartsOption']['series'][0]['data'][0] == [1.0, 2.0, 3.0, 4.0, 5.0]

    tasks = pd.DataFrame([
        {'任务': '切割', '开始日期': '2026-01-01', '结束日期': '2026-01-03'},
        {'任务': '包装', '开始日期': '2026-01-04', '结束日期': '2026-01-05'},
    ])
    task_items, _ = build_optional(tasks, columns(tasks), request(), True, 'fixture')
    gantt = chart(task_items, 'production.gantt')
    option = gantt['chart']['echartsOption']
    assert option['xAxis']['axisLabel']['formatter'] == '{value}天'
    assert option['tooltip']['formatter'] == '{b}: {c}天'
    assert sorted(task['duration'] for task in option['_ganttMeta']['tasks']) == [1, 2]


def test_optional_cohort_funnel_accepts_nested_sets_and_rejects_invalid_sets():
    frame = pd.DataFrame([
        {'队列ID': 'Q1', '阶段': '报名', '阶段序号': 1, '对象ID': 'A'},
        {'队列ID': 'Q1', '阶段': '报名', '阶段序号': 1, '对象ID': 'B'},
        {'队列ID': 'Q1', '阶段': '报名', '阶段序号': 1, '对象ID': 'C'},
        {'队列ID': 'Q1', '阶段': '成交', '阶段序号': 2, '对象ID': 'A'},
        {'队列ID': 'Q1', '阶段': '成交', '阶段序号': 2, '对象ID': 'B'},
        {'队列ID': 'Q1', '阶段': '复购', '阶段序号': 3, '对象ID': 'A'},
    ])
    items, _ = build_optional(frame, columns(frame), request(), True, 'fixture')
    funnel = chart(items, 'sales.cohort')
    assert funnel['chart']['chartType'] == 'funnel'
    assert [row['value'] for row in funnel['tableData']] == [3, 2, 1]
    assert any('仅统计同一队列去重对象' in finding for finding in funnel['findings']['attention'])
    assert funnel['chart']['echartsOption']['series'][0]['sort'] == 'none'

    invalid = frame.copy()
    invalid.loc[5, '对象ID'] = 'Z'
    invalid_items, invalid_gaps = build_optional(invalid, columns(invalid), request(), True, 'fixture')
    assert not any(item['id'] == 'sales.cohort:funnel' for item in invalid_items)
    assert any(g['id'] == 'optional.funnel' for g in invalid_gaps)


def test_optional_flow_requires_conservation_and_acyclic_graph():
    valid = pd.DataFrame([
        {'来源节点': '原料', '去向节点': '工序', '流量': 10, '单位': 'kg', '流转记录ID': '1'},
        {'来源节点': '工序', '去向节点': '成品', '流量': 10, '单位': 'kg', '流转记录ID': '2'},
    ])
    items, _ = build_optional(valid, columns(valid), request(), True, 'fixture')
    flow = chart(items, 'production.flow')
    assert flow['chart']['chartType'] == 'sankey'
    assert flow['chart']['echartsOption']['series'][0]['data']
    assert flow['chart']['echartsOption']['series'][0]['links'][0]['value'] == 10.0
    assert any('中间节点守恒' in finding for finding in flow['findings']['attention'])

    cycle = valid.copy()
    cycle.loc[1, ['来源节点', '去向节点']] = ['工序', '原料']
    cycle_items, cycle_gaps = build_optional(cycle, columns(cycle), request(), True, 'fixture')
    assert not any('production.flow' in item['id'] for item in cycle_items)
    assert any(g['id'] == 'optional.sankey' for g in cycle_gaps)


def test_optional_normalized_multivar_has_explicit_range_and_negative_control():
    frame = pd.DataFrame([
        {'批次': f'B{i}', '归一化温度': i / 10, '归一化压力': (i + 1) / 10, '归一化速度': (i + 2) / 10}
        for i in range(1, 6)
    ])
    items, _ = build_optional(frame, columns(frame), request(), True, 'fixture')
    parallel = chart(items, 'production.parameters:parallel')
    radar = chart(items, 'production.parameters:radar')
    assert parallel['chart']['chartType'] == 'parallel'
    assert radar['chart']['chartType'] == 'radar'
    assert all(indicator['max'] == 1 for indicator in radar['chart']['echartsOption']['radar']['indicator'])
    assert any('不计算综合评分' in finding for finding in parallel['findings']['attention'])

    invalid = frame.copy()
    invalid.loc[0, '归一化温度'] = 1.5
    invalid_items, invalid_gaps = build_optional(invalid, columns(invalid), request(), True, 'fixture')
    assert not any(item['chart']['chartType'] in {'parallel', 'radar'} for item in invalid_items)
    assert any(g['id'] == 'optional.parallel' for g in invalid_gaps)
