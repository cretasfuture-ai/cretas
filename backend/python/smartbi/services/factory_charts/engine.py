"""Approved factory catalogue: semantic eligibility before chart/AI selection.

No restaurant templates and no arbitrary numeric-column recommendations.
"""
import re
import hashlib
import json
from dataclasses import dataclass

import pandas as pd

import smartbi.services.charts  # register the existing strategies
from smartbi.services.charts.registry import get_strategy
from smartbi.services.financial import metrics as M
from smartbi.services.financial.base import _sanitize_for_json
from smartbi.services.financial_dashboard import FinancialDashboardService
from .metrics import Series, display, grouped, number
from .newstock_buckets import CONTRACT_KEY, contract as stock_count_contract, positive_balance, prepare_rows as prepare_stock_rows


ALIASES = {
    'date': ('日期', '月份', '生产日期', '入库日期', '销售日期', '会计期间'),
    'product': ('产品', '产品名称', 'SKU', '品类'),
    'process': ('工序', '工序名称', '产线'),
    'material': ('原料', '原料名称', '物料'),
    'supplier': ('供应商', '供应商名称'),
    'unit': ('单位', '数量单位'),
    'output': ('产量', '实际产量', '产出数量'),
    'planned': ('计划产量', '计划数量'),
    'input': ('投入量', '投入数量'),
    'good': ('良品数', '合格数'),
    'defect': ('不良数', '缺陷数', '不合格数'),
    'inspected': ('检验数', '检验数量'),
    'cost': ('总成本',),
    'material_cost': ('材料成本', '原料成本'),
    'labor_cost': ('人工成本',),
    'equipment_cost': ('设备成本',),
    'other_cost': ('其他成本',),
    'sales': ('销售金额', '销售额'),
    'inflow': ('入库数量', '实收数量'),
    'purchase': ('采购金额', '入库金额'),
    'stock': ('库存数量', '可用数量'),
    'age': ('库龄天数',),
    'expiry': ('距到期天数', '临期天数'),
}
OPTIONAL_FINANCIAL = {'cost_flow_sankey', 'hr_cost_analysis', 'bullet_chart', 'small_multiples'}
FINANCIAL_NAMES = {'budget_achievement', 'yoy_mom_comparison', 'pnl_waterfall',
    'expense_yoy_budget', 'category_yoy_comparison', 'gross_margin_trend',
    'category_structure_donut', 'variance_analysis', 'channel_analysis',
    'product_ranking', 'business_unit_analysis', 'kpi_scorecard'} | OPTIONAL_FINANCIAL


@dataclass
class Dataset:
    frame: pd.DataFrame
    source: str
    financial: bool = False
    sheet_name: str = ''
    currency: str = ''


_UNSCOPABLE_SUBJECT_SOURCES = ('cashflow', 'allfactorycash', 'oee', 'oee-sheet',
                               'receivables', 'historical-inventory')
_GUIDED_SPECIALIZED_PREFIXES = ('cost.dual', 'cost.distribution', 'cost.scatter',
                                 'production.fused', 'production.anomaly')
_FAMILY_LABELS = {
    'production.output': '产量趋势', 'production.products': '产品产量',
    'production.quality': '生产良品率', 'production.yield': '生产出成率',
    'quality.pass': '检验合格率', 'quality.defect': '检验不良率',
    'quality.counts': '不良数量排名', 'sales.trend': '销售金额趋势',
    'sales.products': '产品销售结构', 'purchase.trend': '采购入库金额趋势',
    'purchase.suppliers': '供应商入库金额', 'purchase.quantity': '采购入库数量',
    'stock.materials': '可用库存数量', 'cost.structure': '生产成本结构',
    'cost.unit': '产品单位成本', 'production.plan': '计划与实际产量',
    'production.heatmap': '日期与维度产量', 'production.oee': '设备OEE',
    'finance': '财务指标',
    'cost.dual': '产量与单位成本双轴', 'cost.distribution': '批次单位成本分布',
    'cost.scatter': '批次产量与单位成本关联', 'production.fused': '生产质量成本联合趋势',
    'production.anomaly': '批次产量偏离观察', 'production.slope': '两期产品变化',
    'production.plan-rate': '累计计划达成率', 'stock.turnover': '库存金额周转',
    'stock.history': '历史库存余额',
}
_PRODUCT_ID_NAMES = {
    '产品id', '产品编号', '产品编码', 'sku', 'sku编码', 'product_id',
    'productid', 'product_type_id', 'producttypeid', '产品类型id',
}


def _product_columns(frame):
    """Return product name and identity columns without treating names as IDs."""
    product_name = columns(frame).get('product')
    identity = None
    for column in frame.columns:
        normalized = re.sub(r'[（(].*?[）)]', '', str(column)).strip().lower()
        if normalized in _PRODUCT_ID_NAMES:
            identity = column
            break
    # A lone SKU is the only safe dual-purpose column: it is both the exact
    # displayed label and the source identity.
    if identity is not None and product_name == identity:
        return identity, identity
    return product_name, identity


def _subject_identity(identity, name):
    if identity not in (None, '') and pd.notna(identity):
        return 'id:' + str(identity).strip()
    return 'name:' + str(name)


def _subject_id(identity_kind, identity_value):
    return 'product-' + hashlib.sha256(
        ('smartbi-subject-v2:' + identity_kind + ':' + identity_value).encode('utf-8')).hexdigest()[:16]


def _catalog_entries(datasets, can_price):
    entries = {}
    names_to_ids = {}
    for dataset in datasets:
        source = str(dataset.source).split(' · ', 1)[0].lower()
        if source.startswith(_UNSCOPABLE_SUBJECT_SOURCES):
            continue
        if getattr(dataset, 'financial', False) and not can_price:
            continue
        name_col, id_col = _product_columns(dataset.frame)
        if not name_col or name_col not in dataset.frame:
            continue
        for _, row in dataset.frame.iterrows():
            name = row.get(name_col)
            if pd.isna(name) or not str(name).strip():
                continue
            name = str(name)
            identity = row.get(id_col) if id_col is not None else None
            if identity is not None and pd.notna(identity) and str(identity).strip():
                kind, value = 'id', str(identity).strip()
            else:
                kind, value = 'name', name
            sid = _subject_id(kind, value)
            entries.setdefault(sid, {'id': sid, 'label': name, 'name': name, 'kind': kind, 'value': value})
            names_to_ids.setdefault(name, set()).add(sid)
    # Name-only rows are aliases only when exactly one ID-backed identity owns
    # that exact name.  This prevents a dead name-only subject and prevents
    # silently combining two same-name SKUs.
    id_by_name = {}
    for sid, entry in entries.items():
        if entry['kind'] == 'id':
            id_by_name.setdefault(entry['name'], set()).add(sid)
    for sid, entry in list(entries.items()):
        if entry['kind'] != 'name':
            continue
        ids = id_by_name.get(entry['name'], set())
        if len(ids) == 1:
            names_to_ids[entry['name']] = set(ids)
            entries.pop(sid, None)
        elif len(ids) > 1:
            names_to_ids[entry['name']] = set(ids)
            entries.pop(sid, None)
    # Name-only records may join an ID-backed subject only when that name has
    # exactly one canonical identity in the authorized catalogue.
    return entries, names_to_ids


def _family_key(candidate_id):
    """Return a semantic family before the per-unit/product opaque suffix."""
    raw = str(candidate_id or '').split('@', 1)[0]
    raw = raw.split(':', 1)[0]
    # Specialized financial IDs have a stable chart type after finance.
    return raw


def _analysis_metadata(candidate):
    family_id = _family_key(candidate.get('id'))
    label = _FAMILY_LABELS.get(family_id)
    if not label:
        # Titles can contain product names, so they are intentionally not used
        # as the family label.  Keep an opaque, stable fallback for new views.
        label = candidate.get('metric') or family_id.rsplit('.', 1)[-1].replace('_', ' ')
    candidate['analysis'] = {'familyId': family_id, 'familyLabel': label}
    return candidate


def analysis_subjects(datasets, can_price):
    """Build the guided product catalogue from already-authorized data only."""
    found, names_to_ids = _catalog_entries(datasets, can_price)
    labels = {}
    for name, ids in names_to_ids.items():
        if len(ids) > 1:
            for sid in ids:
                labels[sid] = f'{name} · {sid[-6:]}'
    return sorted(({ 'id': sid, 'label': labels.get(sid, entry['label']) }
                   for sid, entry in found.items()), key=lambda item: (item['label'], item['id']))


def scope_datasets(datasets, request, subjects, can_price):
    """Apply an opaque subject to product-bearing frames before build()."""
    if not getattr(request, 'guided', False):
        return list(datasets), None, '全厂范围'
    if not getattr(request, 'analysis_subject', None):
        return list(datasets), None, '全厂范围'
    requested = str(request.analysis_subject)
    entries, names_to_ids = _catalog_entries(datasets, can_price)
    chosen = entries.get(requested)
    if not chosen:
        return [], {'id': 'analysis.subject', 'title': '分析主题',
                    'missing': ['所选分析主题不在当前授权数据目录中']}, '所选产品当前无数据'
    label = chosen['label']
    scoped = []
    product_data_found = False
    excluded_sources = []
    for dataset in datasets:
        source = str(dataset.source).split(' · ', 1)[0].lower()
        product_col, identity_col = _product_columns(dataset.frame)
        if source.startswith(_UNSCOPABLE_SUBJECT_SOURCES):
            # A product-scoped answer must not retain full-factory OEE/cash
            # frames: they cannot prove the selected product and would leak
            # unrelated factory scope into the response.
            excluded_sources.append(source)
            continue
        if not product_col:
            excluded_sources.append(source)
            continue
        def matches(row):
            name = row.get(product_col)
            if pd.isna(name):
                return False
            identity = row.get(identity_col) if identity_col is not None else None
            if identity is not None and pd.notna(identity) and str(identity).strip():
                return _subject_id('id', str(identity).strip()) == requested
            # A name-only source can join only when its exact name maps to one
            # canonical catalog identity; ambiguous names are excluded.
            name = str(name)
            return len(names_to_ids.get(name, set())) == 1 and requested in names_to_ids.get(name, set())
        mask = dataset.frame.apply(matches, axis=1)
        frame = dataset.frame.loc[mask].copy()
        if not frame.empty:
            product_data_found = True
            scoped.append(Dataset(frame, dataset.source, dataset.financial,
                                  dataset.sheet_name, dataset.currency))
    if not product_data_found:
        return [], {'id': 'analysis.subject.data', 'title': '分析主题',
                    'missing': ['所选产品当前没有可按产品限定的明细数据']}, '所选产品当前无数据'
    gap = None
    if excluded_sources:
        names = '、'.join(sorted(set(excluded_sources)))
        gap = {'id': 'analysis.subject.scope', 'title': '分析主题范围',
               'missing': [f'以下来源无法按产品限定，已从本次主题分析排除：{names}']}
    return scoped, gap, f'已选择分析主题：{label}'


def columns(frame):
    clean = [(re.sub(r'[（(].*?[）)]', '', str(c)).strip(), c) for c in frame.columns]
    return {key: matches[0] if len(matches := [c for name, c in clean if name in names]) == 1 else None
            for key, names in ALIASES.items()}


def money_allowed(dataset):
    frame = dataset.frame
    headers = ' '.join(map(str, frame.columns)) + ' ' + dataset.sheet_name
    normalized = [re.sub(r'[（(].*?[）)]', '', str(c)).strip() for c in frame.columns]
    if len(set(normalized)) != len(normalized):
        return False
    if re.search(r'美元|欧元|港元|日元|USD|EUR|HKD|JPY', headers, re.I):
        return False
    if '币种' in frame:
        return frame['币种'].notna().all() and set(frame['币种'].astype(str)).issubset({'CNY', '元', '人民币'})
    return dataset.currency == 'CNY' or bool(re.search(r'（(?:人民币|[千万元亿]*)元）|\((?:人民币|[千万元亿]*)元\)', headers))


def is_financial(frame):
    text = ' '.join(str(c) for c in frame.columns)
    explicit = re.search(r'营业收入|营业成本|毛利|净利|费用', text)
    return bool(explicit or (re.search(r'预算|目标|本年|上年', text)
                            and not re.search(r'产量|库存|入库|检验|工序', text)))


def _financial_findings(chart, detail, mapping):
    from smartbi.services import financial_findings as F
    out = F._Findings()
    t = chart['chartType']
    perf = M.performance_view(detail)
    if t in ('budget_achievement', 'variance_analysis', 'bullet_chart'):
        F._budget(perf, out)
    elif t == 'expense_yoy_budget':
        F._expenses(detail, out)
    elif t in ('yoy_mom_comparison', 'cashflow_trend'):
        F._yoy(perf, out)
        F._mom(perf, out)
    elif t in ('category_structure_donut', 'category_yoy_comparison', 'product_ranking'):
        F._categories(perf, out)
    elif t == 'business_unit_analysis':
        F._units(perf, out)
    elif t in ('pnl_waterfall', 'cost_flow_sankey'):
        F._pnl(detail, out)
    facts = {'good': list(out.good), 'attention': list(out.attention), 'suggestions': list(out.suggestions)}
    if not any(facts.values()):
        for k in chart.get('kpis', []):
            if k.get('value') not in (None, '-', ''):
                text = f"{k['label']} {k['value']}{k.get('unit', '')}"
                facts['attention' if k.get('trend') == 'down' else 'good'].append(text)
        if facts['good'] or facts['attention']:
            facts['suggestions'].append('与来源明细核对：' + (facts['attention'] or facts['good'])[0])
    note = chart.get('metadata', {}).get('note')
    if note:
        facts['attention'].append(note)
    return facts


def financial_candidates(dataset, request, can_price):
    service = FinancialDashboardService()
    if not can_price:
        return [], [{'id': 'finance', 'title': '财务指标', 'missing': ['当前身份无金额指标查看权限']}]
    detail, mapping, period = service._prepare(dataset.frame, request.year, request.period_type,
        request.start_month, request.end_month, dataset.sheet_name)
    if mapping.value_kind != 'ratio' and not money_allowed(dataset):
        return [], [{'id': 'finance.currency', 'title': '财务金额', 'missing': ['明确且唯一的人民币币种或金额单位；未知币种或单位同义列冲突不能标为元']}]
    results = service.registry.build_all_available(detail, mapping, period, request.year)
    eligible, gaps = [], []
    built = {c['chartType']: c for c in results}
    for info in service.registry.list_all():
        t = info['chartType']
        if t not in FINANCIAL_NAMES:
            continue
        c = built.get(t)
        key = f'finance.{t}'
        optional = t in OPTIONAL_FINANCIAL
        category_limit = {'category_yoy_comparison': 8, 'small_multiples': 9}.get(t)
        if category_limit and 'category' in detail and detail['category'].nunique() > category_limit:
            gaps.append({'id': key, 'title': info['displayName'], 'missing': [f'该图最多{category_limit}个完整品类，请先明确筛选范围；不静默截断品类']})
            continue
        if not c or not c.get('success'):
            if c and (c.get('error') or c.get('metadata', {}).get('dataQuality') == 'error'):
                gaps.append({'id': key, 'title': info['displayName'], 'missing': ['图表生成失败，请刷新重试或联系管理员'], 'error': True})
                continue
            gap = next((g for g in service.registry.list_unavailable(mapping) if g['chartType'] == t), {})
            reason = (c or {}).get('metadata', {}).get('reason') or gap.get('reason') or '缺少这张图所需的可计算指标'
            gaps.append({'id': key, 'title': info['displayName'], 'missing': [reason]})
            continue
        if optional and not request.include_optional:
            continue
        eligible.append({'id': key, 'title': info['displayName'], 'metric': info['displayName'],
            'domain': '财务', 'priority': 'optional' if optional else 'required',
            'reason': f"{period['label']}的来源表已满足{info['displayName']}计算条件",
            'source': dataset.source, 'unit': '',
            'findings': _financial_findings(c, detail, mapping),
            'kpis': c.get('kpis', []), 'tableData': c.get('tableData'),
            'chart': {'chartType': t, 'echartsOption': c['echartsOption']}})
        _analysis_metadata(eligible[-1])
    return eligible, gaps


def _option(series, kind):
    points = series.points
    if kind in ('pareto', 'bar_horizontal'):
        points = sorted(points, key=lambda p: p[1], reverse=True)
    frame = pd.DataFrame(points, columns=['项目', f'{series.title}（{series.unit}）'])
    field = frame.columns[1]
    option = get_strategy(kind).build(frame, x_field='项目', y_fields=[field])
    if kind in ('line', 'area') and all(re.fullmatch(r'\d{4}-\d{2}-\d{2}', label) for label, _ in points):
        observed = dict(points)
        axis = pd.date_range(min(observed), max(observed), freq='D')
        if len(axis) <= 1000:
            labels = axis.strftime('%Y-%m-%d').tolist()
            rendered = dict(zip(option['xAxis']['data'], option['series'][0]['data']))
            option['xAxis']['data'] = labels
            option['series'][0]['data'] = [rendered.get(label) for label in labels]
            option['series'][0]['connectNulls'] = False
        else:
            option['_factoryUnavailable'] = '日期跨度超过连续轴安全上限，请先按月汇总或缩小期间'
    elif kind in ('line', 'area') and all(re.fullmatch(r'\d{1,2}月', label) for label, _ in points):
        rendered = dict(zip(option['xAxis']['data'], option['series'][0]['data']))
        months = [int(label[:-1]) for label, _ in points]
        labels = [f'{m}月' for m in range(min(months), max(months) + 1)]
        option['xAxis']['data'] = labels
        option['series'][0]['data'] = [rendered.get(label) for label in labels]
        option['series'][0]['connectNulls'] = False
    if kind == 'pareto':
        # Both the graph and any cumulative facts use this one metrics object.
        ordered = Series(**{**series.__dict__, 'points': points})
        option['series'][1]['data'] = ordered.cumulative
    option['animation'] = False
    option.setdefault('tooltip', {'trigger': 'axis'})
    option.setdefault('grid', {'left': '8%', 'right': '8%', 'top': 45, 'bottom': 65, 'containLabel': True})
    return option


def series_candidate(series, kind, optional=False):
    total = series.total
    candidate = {'id': f'{series.key}:{kind}', 'title': series.title,
        'metric': series.title, 'domain': series.domain,
        'priority': 'optional' if optional else 'required',
        'reason': f'已有同口径{series.title}及{series.unit}单位，可按来源明细复核',
        'source': series.source, 'unit': series.unit, 'findings': series.findings(),
        'chart': {'chartType': kind, 'echartsOption': _option(series, kind)},
        'kpis': [] if total is None else [{'label': '累计口径' if series.aggregate != 'sum' else '合计', 'value': display(total), 'unit': series.unit}],
        'tableData': [{'label': label, 'value': value} for label, value in series.points]}
    if kind in ('line', 'area'):
        candidate['findings']['attention'].append('仅汇总有记录的日期；缺期不补零，空白不表示停产或没有业务')
    return _analysis_metadata(candidate)


def factory_candidates(dataset, request, can_price):
    frame = dataset.frame.copy()
    cols = columns(frame)
    eligible, gaps = [], []
    monetary = {'总成本','材料成本','原料成本','人工成本','设备成本','其他成本','销售金额','销售额','采购金额','入库金额',
                '平均库存金额','同期销货成本','同期领料成本','订单行小计','SKU折后金额','订单总额','销售订单总额','入库单价','采购单价'}
    sheet_factor = 1e8 if '亿元' in dataset.sheet_name else 1e4 if '万元' in dataset.sheet_name else 1e3 if '千元' in dataset.sheet_name else 1
    for col in frame.columns:
        if re.sub(r'[（(].*?[）)]','',str(col)).strip() in monetary:
            factor = (1e8 if '亿元' in str(col) else 1e4 if '万元' in str(col) else 1e3 if '千元' in str(col)
                      else 1 if re.search(r'[（(](?:人民币)?元[）)]',str(col)) else sheet_factor)
            frame[col] = frame[col].map(lambda v: None if number(v) is None else number(v)*factor)
    if not money_allowed(dataset):
        can_price = False
        gaps.append({'id': 'currency', 'title': '金额指标', 'missing': ['明确且一致的人民币币种列或人民币单位表头；不能把外币或未知币种标为元']})
    # Negative physical facts are invalid observations; keep monetary/stock
    # anomalies separate rather than letting cancellations hide bad rows.
    for key in ('output', 'planned', 'input', 'good', 'defect', 'inspected', 'inflow'):
        col = cols[key]
        if col and frame[col].map(number).dropna().lt(0).any():
            frame[col] = None
            gaps.append({'id': key, 'title': ALIASES[key][0], 'missing': ['非负物理量；来源含负数，不能作为正常产量或收货量展示']})
    if frame.empty:
        return [], [{'id': dataset.source, 'title': dataset.source, 'missing': ['该期间没有来源记录']}]
    date_col = cols['date']
    if request.period_type != 'all' and not date_col:
        return [], [{'id': dataset.source, 'title': dataset.source, 'missing': ['日期列；无法用无日期的数据声称已按期间筛选']}]
    if request.period_type != 'all' and date_col:
        texts = frame[date_col].astype(str)
        if not texts.str.match(r'^\d{4}[-/]').all():
            return [], [{'id': dataset.source, 'title': dataset.source, 'missing': ['日期缺少年份，无法核对所选期间']}]
        dates = pd.to_datetime(texts, errors='coerce')
        if dates.isna().any():
            return [], [{'id': dataset.source, 'title': dataset.source, 'missing': ['日期包含无效值']}]
        frame = frame[(dates.dt.year == request.year) & dates.dt.month.between(request.start_month, request.end_month)]
    if frame.empty:
        return [], [{'id': dataset.source, 'title': dataset.source, 'missing': ['所选期间没有记录']}]

    def add(key, title, domain, value_key, dimension_key, unit, kinds, denominator_key=''):
        value, dim = cols.get(value_key), cols.get(dimension_key)
        denominator = cols.get(denominator_key) if denominator_key else None
        missing = [ALIASES[k][0] for k in (value_key, dimension_key) if not cols.get(k)]
        if denominator_key and not denominator:
            missing.append(ALIASES[denominator_key][0])
        if unit == 'quantity' and not cols['unit']:
            missing.append('明确的数量单位')
        if unit == '元' and not can_price:
            missing.append('金额指标查看权限')
        if missing:
            gaps.append({'id': key, 'title': title, 'missing': missing})
            return
        if unit == 'quantity' and (frame[cols['unit']].isna().any() or frame[cols['unit']].astype(str).str.strip().eq('').any()):
            gaps.append({'id': key, 'title': title, 'missing': ['部分数量缺少单位，不能混加']})
            return
        units = frame.groupby(cols['unit'], dropna=False) if unit == 'quantity' else [(unit, frame)]
        for actual_unit, group in units:
            points = grouped(group, dim, value, ratio_denominator=denominator or '')
            if not points:
                gaps.append({'id': key, 'title': title, 'missing': ['数值/维度缺失，或分母无效；没有补零']})
                continue
            s = Series(f'{key}@{actual_unit}', title, domain, str(actual_unit), points, dataset.source,
                       'ratio' if denominator else 'sum',
                       M.total(group[value].map(number)) if denominator else None,
                       M.total(group[denominator].map(number)) if denominator else None)
            for kind, optional in kinds:
                if optional and not request.include_optional:
                    continue
                # Optional variants must add a specific, readable view, not just another shape.
                valid = len(points) >= 2
                if kind in ('donut', 'pie', 'pareto'):
                    valid = valid and all(v >= 0 for _, v in points) and s.total is not None and s.total > 0
                if kind in ('pie', 'donut'):
                    valid = valid and len(points) <= 6
                if kind == 'area':
                    valid = valid and s.aggregate == 'sum' and all(v >= 0 for _, v in points)
                if valid:
                    eligible.append(series_candidate(s, kind, optional))
                else:
                    gaps.append({'id': f'{s.key}:{kind}', 'title': title, 'missing': ['至少两个有效分组；结构图还需非负值和有效总额，饼图最多六类']})

    add('production.output', '产量趋势', '生产', 'output', 'date', 'quantity', [('line', False), ('area', True)])
    add('production.products', '产品产量', '生产', 'output', 'product', 'quantity', [('bar_horizontal', False)])
    # Unit grouping precedes rates, so kg and boxes never share a denominator.
    for value_key, denominator_key, key, title in [
        ('good', 'output', 'production.quality', '生产良品率'),
        ('good', 'inspected', 'quality.pass', '检验合格率'),
        ('output', 'input', 'production.yield', '生产出成率'),
        ('defect', 'inspected', 'quality.defect', '检验不良率'),
    ]:
        if cols.get(value_key) and cols.get(denominator_key) and cols['unit']:
            for u, group in frame.groupby(cols['unit'], dropna=False):
                if pd.isna(u) or not str(u).strip():
                    gaps.append({'id': key, 'title': title, 'missing': ['明确的分子分母单位']})
                    continue
                points = grouped(group, cols.get('product') or date_col or '', cols[value_key], ratio_denominator=cols[denominator_key], bounded=key != 'production.yield')
                if points:
                    s = Series(f'{key}@{u}', f'{title}（{u}口径）', '质量' if key.startswith('quality') else '生产', '%', points, dataset.source,
                               'ratio', M.total(group[cols[value_key]].map(number)), M.total(group[cols[denominator_key]].map(number)))
                    eligible.append(series_candidate(s, 'bar'))
                else:
                    gaps.append({'id': key, 'title': title, 'missing': ['完整的分子分母，分母需大于零且数量关系有效']})
        else:
            gaps.append({'id': key, 'title': title, 'missing': [ALIASES[value_key][0], ALIASES[denominator_key][0], '同一数量单位']})
    add('quality.counts', '不良数量排名', '质量', 'defect', 'product', 'quantity', [('bar_horizontal', False), ('pareto', False)])
    add('sales.trend', '销售金额趋势', '销售', 'sales', 'date', '元', [('line', False), ('area', True)])
    add('sales.products', '产品销售结构', '销售', 'sales', 'product', '元', [('bar_horizontal', False), ('donut', False), ('pie', True)])
    add('purchase.trend', '采购入库金额趋势', '采购', 'purchase', 'date', '元', [('line', False)])
    add('purchase.suppliers', '供应商入库金额', '采购', 'purchase', 'supplier', '元', [('bar_horizontal', False), ('pareto', False)])
    add('purchase.quantity', '采购入库数量', '采购', 'inflow', 'date', 'quantity', [('line', False)])
    add('stock.materials', '可用库存数量', '库存', 'stock', 'material', 'quantity', [('bar_horizontal', False)])

    components = [cols[k] for k in ('material_cost', 'labor_cost', 'equipment_cost', 'other_cost')]
    if can_price and all(components) and all(frame[c].map(number).notna().all() for c in components):
        values = [(ALIASES[k][0], M.total(frame[cols[k]].map(number))) for k in ('material_cost', 'labor_cost', 'equipment_cost', 'other_cost')]
        total = M.total(v for _, v in values)
        recorded = M.total(frame[cols['cost']].map(number)) if cols['cost'] and frame[cols['cost']].map(number).notna().all() else None
        if recorded is not None and abs(recorded - total) <= max(0.01, abs(total) * 1e-6) and all(v >= 0 for _, v in values) and total > 0:
            s = Series('cost.structure', '生产成本结构', '成本', '元', values, dataset.source)
            eligible += [series_candidate(s, 'donut'), series_candidate(s, 'pareto')]
        else:
            gaps.append({'id': 'cost.structure', 'title': '生产成本结构', 'missing': ['非负分项与总成本对账一致']})
    else:
        gaps.append({'id': 'cost.structure', 'title': '生产成本结构', 'missing': ['材料/人工/设备/其他/总成本完整分项及金额权限']})

    # Unit cost is a quotient of totals for each SKU/unit, never an average of row costs.
    if all(cols[k] for k in ('cost', 'output', 'product', 'unit')) and can_price:
        for unit, group in frame.groupby(cols['unit'], dropna=False):
            if pd.isna(unit) or not str(unit).strip() or any(group[cols[k]].map(number).isna().any() for k in ('cost', 'output')):
                gaps.append({'id': 'cost.unit', 'title': '产品单位成本', 'missing': ['完整成本/产量和单位快照']})
                continue
            points = []
            for sku, rows in group.groupby(cols['product'], dropna=False):
                numerator, denominator = M.total(rows[cols['cost']].map(number)), M.total(rows[cols['output']].map(number))
                if pd.isna(sku) or denominator <= 0 or numerator < 0:
                    points = []
                    break
                points.append((str(sku), M.ratio(numerator, denominator, 1).value))
            if points:
                s = Series(f'cost.unit@{unit}', '产品单位成本', '成本', f'元/{unit}', points, dataset.source, 'unit_cost',
                           M.total(group[cols['cost']].map(number)), M.total(group[cols['output']].map(number)))
                eligible.append(series_candidate(s, 'bar_horizontal'))
    else:
        gaps.append({'id': 'cost.unit', 'title': '产品单位成本', 'missing': ['产品、完整产量、成本、单位及金额权限']})

    for key, title in [('age', '库存账龄'), ('expiry', '库存效期')]:
        col = cols[key]
        if col and cols['stock']:
            all_rows = frame.to_dict('records')
            positive_rows = [row for row in all_rows if positive_balance(row.get(cols['stock']))]
            bucket_column = '库存库龄桶' if key == 'age' else '库存效期桶'
            metadata = stock_count_contract(key, cols['stock'], col, bucket_column)
            prepared_rows, _ = prepare_stock_rows(all_rows, metadata)
            # A positive balance with a malformed age/expiry is a source
            # quality failure. It must invalidate the whole candidate rather
            # than silently disappearing from the batch count.
            if not positive_rows or len(prepared_rows) != len(positive_rows):
                gaps.append({'id': key, 'title': title, 'missing': ['正可用余额与完整日期，不用零补未知日期']})
                continue
            labels = ['不超过30天', '31–90天', '91–180天', '超过180天'] if key == 'age' else ['已到期', '7天内', '8–30天', '超过30天']
            counts = {label: 0 for label in labels}
            for row in prepared_rows:
                counts[row[bucket_column]] += 1
            points = [(label, counts[label]) for label in labels]
            s = Series(f'stock.{key}', title + '（有余额批次）', '库存', '批', points, dataset.source)
            candidate = series_candidate(s, 'bar')
            candidate.setdefault('engine', {})[CONTRACT_KEY] = metadata
            eligible.append(candidate)
        else:
            gaps.append({'id': f'stock.{key}', 'title': title, 'missing': ['可用库存余额及库龄/到期日期']})
    from .advanced import build as advanced
    extra, extra_gaps = advanced(frame, cols, request, can_price, dataset.source)
    eligible.extend(extra)
    gaps.extend(extra_gaps)
    from .optional import build as specialized
    extra, extra_gaps = specialized(frame, cols, request, can_price, dataset.source)
    eligible.extend(extra)
    gaps.extend(extra_gaps)
    from .business_metrics import build as business_metrics
    extra, extra_gaps = business_metrics(frame, cols, request, can_price, dataset.source)
    eligible.extend(extra)
    gaps.extend(extra_gaps)
    allowed = {
        'production': ('production.', 'cost.', 'quality.counts'), 'quality': ('quality.',),
        'sales': ('sales.',), 'purchase': ('purchase.',), 'inventory': ('stock.',), 'process': ('process.',),
    }.get(dataset.source.split(' · ')[0])
    if allowed:
        eligible = [c for c in eligible if c['id'].startswith(allowed)]
        gaps = [g for g in gaps if g['id'].startswith(allowed)]
    return eligible, gaps


def _card_layout(option):
    """Reserve room for axis units and a separate legend in mounted/exported cards."""
    pies = [s for s in option.get('series', []) if s.get('type') == 'pie']
    for series in pies:
        label = series.setdefault('label', {})
        if label.get('position', 'outside') not in ('inside', 'inner', 'center'):
            # Keep outside category/percentage text within narrow card edges.
            label.update({'alignTo': 'edge', 'edgeDistance': 8, 'bleedMargin': 4})
    if len(pies) == 1 and isinstance(option.get('legend'), dict):
        pies[0].setdefault('center', ['50%', '42%'])
        legend = option['legend']
        legend.pop('top', None)
        legend.pop('right', None)
        legend.update({'orient': 'horizontal', 'left': 'center', 'bottom': 0})
    if 'xAxis' not in option or 'yAxis' not in option:
        return
    grid = option.setdefault('grid', {})
    if not isinstance(grid, dict):
        return  # Multi-panel charts own their individual grid layouts.

    def reserve(value, minimum):
        if isinstance(value, (int, float)):
            return max(value, minimum)
        # Cards are at least about 320px; retain larger intentional percentage margins.
        if isinstance(value, str) and value.endswith('%'):
            try:
                if float(value[:-1]) * 3.2 >= minimum:
                    return value
            except ValueError:
                pass
        return minimum

    for side, minimum in [('left', 12), ('right', 40), ('top', 40), ('bottom', 64)]:
        grid[side] = reserve(grid.get(side), minimum)
    grid['containLabel'] = True
    legend = option.get('legend')
    if isinstance(legend, dict) and legend.get('orient', 'horizontal') == 'horizontal':
        legend.pop('top', None)
        legend['bottom'] = 0
    axes = option['xAxis'] if isinstance(option['xAxis'], list) else [option['xAxis']]
    for axis in axes:
        if axis.get('type') == 'value' and axis.get('name'):
            # End-position names extend beyond the final tick and can be cropped.
            axis['nameLocation'] = 'middle'
            axis['nameGap'] = 28


def build(datasets, request, can_price):
    candidates, unavailable = [], []
    for dataset in datasets:
        if dataset.frame.empty:
            charts, gaps = [], [{'id': dataset.source, 'title': dataset.source, 'missing': ['所选期间没有来源记录']}]
        elif dataset.source.startswith(('cashflow ·', 'receivables ·')):
            charts, gaps = [], []
        elif dataset.source.startswith('historical-inventory ·'):
            charts, gaps = historical_inventory(dataset)
        elif dataset.financial:
            charts, gaps = financial_candidates(dataset, request, can_price)
        else:
            charts, gaps = factory_candidates(dataset, request, can_price)
        business_source = dataset.source.split(' · ')[0] in {'production','sales','purchase','quality','process','inventory','historical-inventory'}
        if not dataset.frame.empty and not business_source:
            from .financial_addons import build as financial_addons
            extra, extra_gaps = financial_addons(dataset, request, can_price)
            charts.extend(extra)
            gaps.extend(extra_gaps)
        if getattr(request, 'guided', False) and not getattr(request, 'analysis_subject', None):
            charts = [chart for chart in charts if not str(chart.get('id', '')).startswith(_GUIDED_SPECIALIZED_PREFIXES)]
        # Sources with the same metric stay distinct and auditable.
        prefix = re.sub(r'[^a-z0-9]+', '-', dataset.source.split(' · ')[0].lower()).strip('-')
        for chart in charts:
            _analysis_metadata(chart)
            if chart['chart']['echartsOption'].get('_factoryUnavailable'):
                gaps.append({'id': chart['id'], 'title': chart['title'], 'missing': [chart['chart']['echartsOption']['_factoryUnavailable']]})
                continue
            if chart_size(chart) > 100 or len(charts) > 100:
                gaps.append({'id': chart['id'], 'title': chart['title'], 'missing': ['图表超过100个分组或候选，请缩小期间或先按明确粒度汇总；没有截断或补数']})
                continue
            # Uploaded names are untrusted text. Keep tooltip rendering on canvas;
            # the legacy HTML formatters interpolate names without escaping.
            def safe_tooltips(node):
                if isinstance(node, dict):
                    tooltip = node.get('tooltip')
                    if isinstance(tooltip, dict):
                        tooltip['renderMode'] = 'richText'
                        if str(tooltip.get('formatter', '')).startswith('__FMT__'):
                            tooltip.pop('formatter')
                    for value in node.values(): safe_tooltips(value)
                elif isinstance(node, list):
                    for value in node: safe_tooltips(value)
            safe_tooltips(chart['chart']['echartsOption'])
            chart['chart']['echartsOption'].setdefault('tooltip', {})['renderMode'] = 'richText'
            _card_layout(chart['chart']['echartsOption'])
            if '@' in chart['id']:
                head, suffix = chart['id'].split('@', 1)
                chart['id'] = head + '@' + hashlib.sha256(suffix.encode()).hexdigest()[:12]
            chart['id'] = f'{prefix}.{chart["id"]}' if prefix else chart['id']
            # Keep source provenance in the family key while retaining one
            # family across chart variants and units from that source.
            family = chart.get('analysis', {}).get('familyId')
            if family and prefix and not str(family).startswith(prefix + '.'):
                chart['analysis']['familyId'] = f'{prefix}.{family}'
            # AI receives semantic metric IDs, never SKU names or source-row values.
            chart['aiMetric'] = chart['id'].split('@')[0]
        for gap in gaps:
            gap['id'] = f'{prefix}.{gap["id"]}' if prefix else gap['id']
        candidates.extend(c for c in charts if chart_size(c) <= 100 and len(charts) <= 100
                          and not c['chart']['echartsOption'].get('_factoryUnavailable'))
        unavailable.extend(gaps)
    candidates.sort(key=lambda c: (c['priority'] != 'required', c['domain'], c['id']))
    unique_gaps = {}
    for gap in unavailable:
        key = gap['id'] + ':' + hashlib.sha256(json.dumps(gap['missing'], ensure_ascii=False).encode()).hexdigest()[:8]
        unique_gaps[key] = {**gap, 'id': key}
    return _sanitize_for_json(candidates), _sanitize_for_json(list(unique_gaps.values()))


def historical_inventory(dataset):
    """Balances across months are observations, never summed as inventory."""
    frame = dataset.frame
    required = ['日期', '原料', '单位', '库存数量']
    if any(c not in frame for c in required) or frame[required].isna().any().any():
        return [], [{'id': 'stock.history', 'title': '历史库存', 'missing': ['完整关账期间、原料、单位和余额快照']}]
    work = frame.copy()
    work['库存数量'] = work['库存数量'].map(number)
    if work['库存数量'].isna().any() or work.duplicated(['日期', '原料', '单位']).any():
        return [], [{'id': 'stock.history', 'title': '历史库存', 'missing': ['每原料每单位每期间唯一且有效的余额快照']}]
    charts = []
    for (material, unit), group in work.groupby(['原料', '单位']):
        points = [(str(r['日期']), float(r['库存数量'])) for _, r in group.sort_values('日期').iterrows()]
        series = Series('stock.history@'+str(material)+'@'+str(unit), str(material)+'关账库存余额', '库存', str(unit), points, dataset.source, 'nonadditive')
        chart = series_candidate(series, 'line' if len(points) > 1 else 'bar')
        chart['kpis'] = [{'label': '最后关账余额', 'value': display(points[-1][1]), 'unit': str(unit)}]
        chart['findings']['attention'].append('关账期间余额快照；不同月份余额不累加，不代表当前可用库存')
        charts.append(chart)
    return charts, []


def chart_size(chart):
    table = chart.get('tableData') or []
    counts = [len(table.get('rows', [])) if isinstance(table, dict) else len(table)]
    option = chart['chart']['echartsOption']
    for series in option.get('series', []):
        counts.extend(len(series.get(k, [])) for k in ('data', 'links'))
    for key in ('xAxis', 'yAxis'):
        axes = option.get(key, [])
        if isinstance(axes, dict): axes = [axes]
        counts.extend(len(axis.get('data', [])) for axis in axes)
    return max(counts, default=0)
