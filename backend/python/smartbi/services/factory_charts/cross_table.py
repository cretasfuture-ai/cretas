"""Fixed batch-key relationship; no arbitrary join or model-authored arithmetic."""
from __future__ import annotations
from collections import Counter
from typing import Any
import pandas as pd
from .engine import Dataset
from .metrics import display, number

FAMILY = 'production.inspection_fused'
SOURCE = 'production-inspection-fused'
SOURCE_LABEL = SOURCE + ' · 生产批次关联真实质检及已归集成本'


def _gap(message):
    return {'id': FAMILY, 'title': '生产-检验-成本联合分析', 'missing': [message]}


def _key(value):
    if value is None or pd.isna(value):
        return None
    return str(value).strip() or None


def _production(row):
    key, product, unit = (_key(row.get(c)) for c in ('来源主键', '产品ID', '单位'))
    output, good, cost = (number(row.get(c)) for c in ('产量', '良品数', '总成本'))
    day = pd.to_datetime(row.get('日期'), errors='coerce')
    if (not all((key, product, unit)) or pd.isna(day) or output is None or output <= 0
            or good is None or not 0 <= good <= output or cost is None or cost < 0):
        return None
    return {**row, '来源主键': key, '产品ID': product, '单位': unit,
            '日期': day.strftime('%Y-%m-%d'), '产量': output, '良品数': good, '总成本': cost}


def prepare_joined(production: Dataset, quality: Dataset):
    """Validate raw rows before aggregation; one output per production batch.

    Both reads are factory scoped. Quality is bounded to production source_pk.
    One incomplete inspection invalidates its batch denominator, never becoming
    zero through pandas sum. Inspection samples are not production quantities.
    """
    p_rows, q_rows = production.frame.to_dict('records'), quality.frame.to_dict('records')
    duplicates = {k for k, n in Counter(_key(r.get('来源主键')) for r in p_rows).items() if k and n > 1}
    totals = Counter((_key(r.get('产品ID')), _key(r.get('单位'))) for r in p_rows)
    aggregate, invalid_quality, rejected = {}, set(), Counter()
    for row in q_rows:
        pair = (_key(row.get('生产批次ID')), _key(row.get('产品ID')))
        sample, passed, failed = (number(row.get(c)) for c in ('检验数', '合格数', '不良数'))
        if (not all(pair) or sample is None or sample <= 0 or passed is None or not 0 <= passed <= sample
                or failed is None or failed < 0 or abs(sample - passed - failed) > 1e-6):
            invalid_quality.add(pair)
            continue
        bucket = aggregate.setdefault(pair, {'检验数': 0.0, '合格数': 0.0, '质检不良数': 0.0, '质检记录数': 0, 'dates': []})
        for column, value in (('检验数', sample), ('合格数', passed), ('质检不良数', failed)):
            bucket[column] += value
        bucket['质检记录数'] += 1
        qdate = pd.to_datetime(row.get('日期'), errors='coerce')
        if pd.notna(qdate):
            bucket['dates'].append(qdate.strftime('%Y-%m-%d'))
    joined = []
    for raw in p_rows:
        row = _production(raw)
        if row is None:
            rejected['生产批次缺少完整日期、产品ID、数量单位、产量/良品或非负成本'] += 1
            continue
        if row['来源主键'] in duplicates:
            rejected['生产稳定批次键重复，未重复累计产量或成本'] += 1
            continue
        pair = (row['来源主键'], row['产品ID'])
        if pair in invalid_quality:
            rejected['关联质检存在不完整或不一致的样本分子/分母'] += 1
            continue
        quality_sum = aggregate.get(pair)
        if quality_sum is None:
            rejected['缺少同厂同批次同产品ID的可关联质检'] += 1
            continue
        dates = quality_sum['dates']
        joined.append({**row, **{k: v for k, v in quality_sum.items() if k != 'dates'},
                       '生产批次ID': row['来源主键'], '首次检验日期': min(dates) if dates else None,
                       '末次检验日期': max(dates) if dates else None})
    gaps = [_gap(f'{count}个生产批次：{reason}；未补零') for reason, count in rejected.items()]
    if not joined:
        return None, gaps or [_gap('没有可关联的完整已完成生产批次与质检记录')]
    frame = pd.DataFrame(joined)
    covered = Counter((r['产品ID'], r['单位']) for r in joined)
    frame.attrs['joint_coverage'] = {(p, u): {'totalBatches': n, 'coveredBatches': covered[(p, u)]}
                                     for (p, u), n in totals.items() if p and u}
    return frame, gaps


def _candidate(frame, product, unit, source, coverage):
    sums = frame.groupby('日期', sort=True)[['产量', '良品数', '检验数', '合格数', '总成本']].sum()
    rows = [[str(day), float(r['产量']), float(r['合格数'] / r['检验数'] * 100),
             float(r['良品数'] / r['产量'] * 100), float(r['总成本'])] for day, r in sums.iterrows()]
    label = next((str(v) for v in frame.get('产品', []) if _key(v)), product)
    total, covered = coverage.get('totalBatches', len(frame)), len(frame)
    limitations = [f'当前产品/单位覆盖{covered}/{total}个生产批次；未关联或不完整批次不进入汇总',
        '检验合格率=质检合格数÷检验样本数；生产良品率=生产良品数÷生产产量，分母不同',
        '多次检验汇总的是检验样本观测次数，不等于唯一生产实物数量；未推断因果']
    return {
        'id': f'{FAMILY}@{product}@{unit}', 'analysis': {'familyId': FAMILY, 'familyLabel': '生产-检验-成本联合'},
        'title': f'{label}生产-检验-成本联合分析', 'metric': '生产-检验-成本联合分析',
        'domain': '生产', 'priority': 'optional', 'reason': '按稳定生产批次键关联、先聚合多次质检',
        'source': source, 'unit': unit,
        'chart': {'chartType': 'combination', 'echartsOption': {
            'animation': False, 'tooltip': {'trigger': 'axis', 'renderMode': 'richText'},
            'legend': {'data': ['产量', '检验合格率', '生产良品率', '总成本'], 'bottom': 0},
            'grid': {'left': 12, 'right': 90, 'top': 40, 'bottom': 64, 'containLabel': True},
            'xAxis': {'type': 'category', 'data': [r[0] for r in rows], 'axisLabel': {'rotate': 35}},
            'yAxis': [{'type': 'value', 'name': unit}, {'type': 'value', 'name': '%', 'min': 0, 'max': 100, 'position': 'right'},
                      {'type': 'value', 'name': '元', 'position': 'right', 'offset': 55}],
            'series': [{'name': '产量', 'type': 'bar', 'data': [r[1] for r in rows]},
                       {'name': '检验合格率', 'type': 'line', 'yAxisIndex': 1, 'data': [r[2] for r in rows]},
                       {'name': '生产良品率', 'type': 'line', 'yAxisIndex': 1, 'data': [r[3] for r in rows]},
                       {'name': '总成本', 'type': 'line', 'yAxisIndex': 2, 'data': [r[4] for r in rows]}]}},
        'kpis': [{'label': '已覆盖批次产量', 'value': display(frame['产量'].sum()), 'unit': unit},
                 {'label': '累计生产良品率', 'value': display(frame['良品数'].sum() / frame['产量'].sum() * 100), 'unit': '%'},
                 {'label': '累计检验合格率', 'value': display(frame['合格数'].sum() / frame['检验数'].sum() * 100), 'unit': '%'},
                 {'label': '已覆盖批次总成本', 'value': display(frame['总成本'].sum()), 'unit': '元'}],
        'tableData': {'headers': ['日期', f'产量（{unit}）', '检验合格率（%）', '生产良品率（%）', '总成本（元）'], 'rows': rows},
        'detailColumns': ['日期', '来源主键', '生产批次ID', '批次', '产品ID', '产品', '单位', '产量', '良品数',
                          '检验数', '合格数', '质检不良数', '质检记录数', '首次检验日期', '末次检验日期', '总成本'],
        'sourceMetadata': {'sourceKey': SOURCE, 'sources': ['production', 'quality'],
                           'joinKey': 'factory_id + source_pk/production_batch_id + product_type_id', 'costAuthorized': True,
                           'coverage': {'totalBatches': total, 'coveredBatches': covered}},
        'findings': {'good': [], 'attention': list(limitations), 'limitations': limitations,
            'suggestions': ['按稳定批次键回查联合原始明细，核对缺失质检及成本归集']}}


def build(datasets: list[Dataset], request: Any, can_price: bool):
    if not getattr(request, 'guided', False) or not getattr(request, 'analysis_subject', None):
        return [], []
    if not can_price:
        return [], [_gap('当前身份没有成本查看权限，未返回联合成本或其明细')]
    dataset = next((d for d in datasets if str(d.source).split(' · ', 1)[0] == SOURCE), None)
    if dataset is None or dataset.frame.empty:
        return [], [_gap('缺少同厂同产品且完整对齐的生产、真实质检与成本来源')]
    required = {'日期', '来源主键', '产品ID', '单位', '产量', '良品数', '总成本', '检验数', '合格数', '质检不良数'}
    if not required.issubset(dataset.frame.columns):
        return [], [_gap('联合明细缺少稳定批次、产品ID、日期、单位或完整数值列')]
    records = dataset.frame.to_dict('records')
    if len({_key(r['来源主键']) for r in records}) != len(records):
        return [], [_gap('联合明细生产批次键重复，拒绝重复累计')]
    for row in records:
        inspected, passed, failed = (number(row.get(c)) for c in ('检验数', '合格数', '质检不良数'))
        if (_production(row) is None or inspected is None or inspected <= 0 or passed is None
                or not 0 <= passed <= inspected or failed is None or failed < 0 or abs(inspected - passed - failed) > 1e-6):
            return [], [_gap('联合明细校验失败，不使用缺失值或跨单位比较')]
    coverage = dataset.frame.attrs.get('joint_coverage', {})
    return [_candidate(g, str(p), str(u), dataset.source, coverage.get((p, u), {}))
            for (p, u), g in dataset.frame.groupby(['产品ID', '单位'], dropna=False)], []
