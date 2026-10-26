"""Strict financial candidates which require the unnormalised upload frame.

The ordinary financial dashboard intentionally normalises away some statement
columns.  These candidates therefore run on the qualified raw frame and only
emit a chart when the source proves the required fact, period and currency.
"""
import re
import math
from typing import Any

import pandas as pd


def _num(v):
    try:
        x = float(v)
        return x if pd.notna(x) and pd.notnull(x) and math.isfinite(x) else None
    except (TypeError, ValueError):
        return None


def _col(frame, names):
    # A financial upload may legitimately carry both 项目 (cash-flow item)
    # and 科目 (profit subject).  Resolve each requested semantic field by the
    # declared priority instead of treating those independent columns as a
    # conflict.
    found = [c for c in frame.columns if re.sub(r'[（(].*?[）)]', '', str(c)).strip() in names]
    return found[0] if len(found) == 1 else None


def _scaled(frame, col):
    factor = frame.attrs.get('factory_amount_factor', 1)
    text = str(col)
    if '亿元' in text: factor = 1e8
    elif '万元' in text: factor = 1e4
    elif '千元' in text: factor = 1e3
    return frame[col].map(_num).map(lambda v: None if v is None else v * factor)


def _money_ok(dataset):
    frame = dataset.frame
    text = ' '.join(map(str, frame.columns)) + ' ' + str(getattr(dataset, 'sheet_name', ''))
    declared = str(getattr(dataset, 'currency', '') or '').strip().upper()
    if declared and declared not in {'CNY', 'RMB', '元', '人民币'}:
        return False
    if re.search(r'美元|欧元|港元|日元|USD|EUR|HKD|JPY', text, re.I):
        return False
    if '币种' in frame:
        kinds = set(frame['币种'].astype(str).str.strip().str.upper())
        if not frame['币种'].notna().all() or not kinds.issubset({'CNY', 'RMB', '元', '人民币'}):
            return False
    # Mixed scale columns are ambiguous even if both happen to say RMB.
    scales = set()
    for col in frame.columns:
        text_col = str(col)
        if re.search(r'万元', text_col): scales.add('wan')
        elif re.search(r'千元', text_col): scales.add('qian')
        elif re.search(r'亿元', text_col): scales.add('yi')
        elif re.search(r'元', text_col): scales.add('yuan')
    sheet = str(getattr(dataset, 'sheet_name', ''))
    if '万元' in sheet: scales.add('wan')
    elif '千元' in sheet: scales.add('qian')
    elif '亿元' in sheet: scales.add('yi')
    elif re.search(r'[（(](?:人民币)?元[）)]', sheet): scales.add('yuan')
    if len(scales) > 1:
        return False
    if '币种' in frame:
        return True
    return getattr(dataset, 'currency', '') == 'CNY' or bool(re.search(r'[（(](?:人民币|[千万元亿]*)元[）)]', text))


def _period(frame, request):
    date = _col(frame, ('日期', '月份', '期间', '记账日期', '发生日期', 'period', 'transaction_date'))
    if not date:
        return frame.iloc[0:0], '缺少日期/月份列，无法按所选期间筛选'
    raw = frame[date].astype(str).str.strip()
    valid_shape = raw.str.match(r'^\d{4}[-/]\d{1,2}(?:[-/]\d{1,2})?$')
    parsed = raw.where(valid_shape).map(lambda value: pd.to_datetime(value, errors='coerce'))
    if parsed.isna().any():
        return frame.iloc[0:0], '日期/月份缺少年份或月份，无法证明所选期间'
    mask = pd.Series(True, index=frame.index) if getattr(request, 'period_type', 'all') == 'all' else (
        (parsed.dt.year == int(request.year)) & parsed.dt.month.between(int(request.start_month), int(request.end_month)))
    out = frame.loc[mask].copy()
    out['_period_date'] = parsed.loc[mask].values
    return out, None


def _candidate(cid, title, chart_type, points, unit, source, *, reason, dimension='期间', total=None):
    labels = [str(k) for k, _ in points]
    vals = [round(float(v), 6) for _, v in points]
    total = sum(vals) if total is None else total
    result = {
        'id': cid, 'title': title, 'metric': title, 'domain': '财务', 'priority': 'required',
        'reason': reason, 'source': source, 'unit': unit,
        'findings': {'good': [], 'attention': [f'合计 {total:g}{unit}（事实汇总）'], 'suggestions': [f'可回查{dimension}明细']},
        'kpis': [{'label': '合计', 'value': f'{total:,.2f}', 'unit': unit}],
        'tableData': [{'label': label, 'value': value} for label, value in zip(labels, vals)],
        'chart': {'chartType': chart_type, 'echartsOption': {
            'animation': False, 'tooltip': {'trigger': 'axis', 'renderMode': 'richText'},
            'xAxis': {'type': 'category', 'data': labels}, 'yAxis': {'type': 'value', 'name': unit},
            'series': [{'name': title, 'type': 'bar' if chart_type != 'cashflow_trend' else 'line', 'data': vals}],
        }},
    }
    if chart_type == 'bar_horizontal':
        result['chart']['echartsOption']['xAxis'] = {'type': 'value', 'name': unit}
        result['chart']['echartsOption']['yAxis'] = {'type': 'category', 'data': labels}
    return result


def _gap(cid, title, *missing):
    return {'id': cid, 'title': title, 'missing': list(missing)}


def _cash(frame, source, request, gaps):
    item = _col(frame, ('现金流活动', '现金流项目', 'activity'))
    amount = _col(frame, ('现金流金额', 'posted_amount', 'amount'))
    if not item or not amount:
        return []
    work, reason = _period(frame, request)
    if reason:
        gaps.append(_gap('finance.cash_flow', '现金流', reason)); return []
    if work.empty:
        gaps.append(_gap('finance.cash_flow', '现金流', '所选期间没有记录')); return []
    vals = _scaled(work, amount)
    if vals.isna().any():
        gaps.append(_gap('finance.cash_flow', '现金流', '现金流金额含空值或非数值，不能补零')); return []
    labels = work[item].astype(str)
    allowed = {'经营活动', '投资活动', '筹资活动', '经营', '投资', '筹资', 'operating', 'investing', 'financing'}
    if not labels.map(lambda x: str(x).strip() in allowed).all():
        gaps.append(_gap('finance.cash_flow', '现金流', '缺少明确经营/投资/筹资现金流科目')); return []
    aliases = {'经营':'经营活动','投资':'投资活动','筹资':'筹资活动','operating':'经营活动','investing':'投资活动','financing':'筹资活动'}
    work[item] = labels.str.strip().map(lambda x: aliases.get(x,x))
    by_item = work.assign(_v=vals).groupby(item, sort=True)['_v'].sum()
    points = [(name,float(by_item[name])) for name in ('经营活动','投资活动','筹资活动') if name in by_item.index]
    candidate = _candidate('finance.cash_flow_waterfall', '现金流量瀑布图', 'cash_flow_waterfall', points, '元', source,
                       reason='现金流科目、金额、期间和人民币口径均由来源明细证明', dimension='现金流科目')
    running = 0.0
    offset, bars = [], []
    for _, value in points:
        offset.append(round(min(running, running + value), 6)); bars.append(round(abs(value), 6)); running += value
    candidate['chart']['echartsOption']['series'] = [
        {'name': '辅助', 'type': 'bar', 'stack': 'waterfall', 'stackStrategy':'all', 'data': offset, 'itemStyle': {'color': 'transparent'}, 'tooltip':{'show':False}},
        {'name': '现金流', 'type': 'bar', 'stack': 'waterfall', 'stackStrategy':'all',
         'data': [{'name':f'{name}：{value:g}元','value':height,'itemStyle':{'color':'#e6a23c' if value<0 else '#409eff'}} for (name,value),height in zip(points,bars)]},
    ]
    candidate['kpis'].append({'label': '现金净增加额', 'value': f'{running:,.2f}', 'unit': '元'})
    candidate['findings']['attention'].append(f'现金净增加额 {running:g}元')
    return [candidate]


def _cash_trend(frame, source, request, gaps):
    item = _col(frame, ('现金流活动', '现金流项目', 'activity'))
    amount = _col(frame, ('现金流金额', 'posted_amount', 'amount'))
    date = _col(frame, ('日期', '月份', '期间', '记账日期', '发生日期', 'period', 'transaction_date'))
    if not item or not amount or not date:
        return []
    work, reason = _period(frame, request)
    if reason:
        gaps.append(_gap('finance.cashflow_trend', '现金流趋势', reason)); return []
    vals = _scaled(work, amount)
    if work.empty or vals.isna().any():
        gaps.append(_gap('finance.cashflow_trend', '现金流趋势', '期间或现金流金额不完整，不能补零')); return []
    allowed = {'经营活动', '投资活动', '筹资活动', '经营', '投资', '筹资', 'operating', 'investing', 'financing'}
    if not work[item].astype(str).str.strip().map(lambda x: x in allowed).all():
        gaps.append(_gap('finance.cashflow_trend', '现金流趋势', '缺少明确现金流活动科目')); return []
    work['_v'] = vals
    aliases = {'经营':'经营活动','投资':'投资活动','筹资':'筹资活动','operating':'经营活动','investing':'投资活动','financing':'筹资活动'}
    work[item] = work[item].astype(str).str.strip().map(lambda x: aliases.get(x,x))
    work['_month'] = work['_period_date'].dt.to_period('M').astype(str)
    points = [(str(k),float(v)) for k,v in work.groupby('_month',sort=True)['_v'].sum().items()]
    candidate = _candidate('finance.cashflow_trend', '现金流趋势分析', 'cashflow_trend', points, '元', source,
                       reason='按日期汇总真实现金流金额；缺期不补零')
    labels = pd.period_range(points[0][0],points[-1][0],freq='M').astype(str).tolist()
    if len(labels)>120:
        gaps.append(_gap('finance.cashflow_trend','现金流趋势','最多120个月，请缩小期间')); return []
    by_activity = []
    for activity in ('经营活动', '投资活动', '筹资活动'):
        data = work[work[item] == activity].groupby('_month')['_v'].sum().to_dict()
        by_activity.append({'name':activity,'type':'line','connectNulls':False,'data':[float(data[m]) if m in data else None for m in labels]})
    candidate['chart']['echartsOption']['xAxis']['data'] = labels
    candidate['chart']['echartsOption']['series'] = by_activity
    candidate['chart']['echartsOption']['legend'] = {'data':[s['name'] for s in by_activity]}
    candidate['tableData'] = {'headers':['期间','经营活动（元）','投资活动（元）','筹资活动（元）'],
                              'rows':[[m]+[s['data'][i] for s in by_activity] for i,m in enumerate(labels)]}
    return [candidate]


def _ar(frame, source, request, gaps):
    amount = _col(frame, ('未收金额', '未核销应收', '应收余额', 'outstanding_amount'))
    age = _col(frame, ('账龄天数', '账龄', '逾期天数', 'age_days'))
    due = _col(frame, ('到期日', '应收截止日', 'due_date'))
    asof = _col(frame, ('账龄基准日', '截至日', '截止日', 'as_of_date'))
    customer = _col(frame, ('客户', '客户名称', 'customer'))
    invoice = _col(frame, ('单据ID', '单据号', '发票号', 'invoice_id'))
    status = _col(frame, ('核销状态', 'payment_status', 'reconciliation_status'))
    if not amount or not customer or not invoice or not asof or not (age or due):
        gaps.append(_gap('finance.ar_aging', '应收账款账龄', '需要客户、唯一单据ID、未收金额、截至日及账龄/到期日'))
        return []
    if status and not frame[status].isin(['UNPAID', 'PARTIALLY_PAID']).all():
        gaps.append(_gap('finance.ar_aging', '应收账款账龄', '存在未核销或未知余额，不能当作可信应收'))
        return []
    if frame[invoice].isna().any() or frame[invoice].astype(str).duplicated().any() or frame[customer].isna().any():
        gaps.append(_gap('finance.ar_aging', '应收账款账龄', '客户或单据ID缺失/重复，不能确认余额归属'))
        return []
    # As-of snapshots include invoices issued before the selected period.  Do
    # not discard those rows by transaction date; validate the explicit as-of.
    work, reason = frame.copy(), None
    if reason:
        gaps.append(_gap('finance.ar_aging', '应收账款账龄', reason)); return []
    snapshot = pd.to_datetime(work[asof], errors='coerce')
    if snapshot.isna().any() or snapshot.nunique() != 1:
        gaps.append(_gap('finance.ar_aging', '应收账款账龄', '截至日必须完整且全表一致')); return []
    from datetime import date as calendar_date
    if snapshot.iloc[0].date() > calendar_date.today():
        gaps.append(_gap('finance.ar_aging', '应收账款账龄', '未来截至日不是已发生的应收余额快照')); return []
    if getattr(request, 'period_type', 'all') != 'all' and not ((snapshot.dt.year == int(request.year)) & snapshot.dt.month.between(int(request.start_month), int(request.end_month))).all():
        gaps.append(_gap('finance.ar_aging', '应收账款账龄', '截至日不在所选期间，不能回溯当前余额')); return []
    vals = _scaled(work, amount)
    if age:
        ages = work[age].map(_num)
    else:
        due_dates = pd.to_datetime(work[due], errors='coerce')
        asof_dates = pd.to_datetime(work[asof], errors='coerce')
        ages = (asof_dates - due_dates).dt.days
    if work.empty or vals.isna().any() or ages.isna().any() or (vals < 0).any():
        gaps.append(_gap('finance.ar_aging', '应收账款账龄', '应收金额与账龄必须完整且非负，不能补零')); return []
    bins = pd.cut(ages, [-float('inf'), -1, 30, 90, 180, float('inf')], labels=['未到期', '0–30天', '31–90天', '91–180天', '超过180天'])
    points = [(str(k), float(vals[bins == k].sum())) for k in bins.cat.categories]
    if sum(v for _, v in points) <= 0:
        gaps.append(_gap('finance.ar_aging', '应收账款账龄', '应收余额合计为零，无法形成账龄分布')); return []
    asof_label = str(snapshot.iloc[0].date())
    chart = _candidate('finance.ar_aging', '应收账款账龄（截至'+asof_label+'）', 'ar_aging', points, '元', source,
                       reason='明确截至日的未核销余额，按到期日逾期天数分桶；未到期单独列示，不按发票日期丢弃历史未收')
    chart['findings']['attention'].append('截至日：'+asof_label+'；应收余额不等于订单金额或本期收入')
    return [chart]


def _budget(frame, source, request, can_price, gaps):
    subject = _col(frame, ('科目', '预算项目', '指标', '项目'))
    budget = _col(frame, ('预算', '预算数', '目标'))
    actual = _col(frame, ('实际', '实际数', '完成'))
    if not subject or not budget or not actual:
        return []
    if not can_price or not _money_ok(type('D', (), {'frame': frame, 'currency': 'CNY', 'sheet_name': ''})()):
        gaps.append(_gap('finance.budget', '利润预算', '缺少人民币币种/单位或金额查看权限')); return []
    work, reason = _period(frame, request)
    if reason:
        gaps.append(_gap('finance.budget', '利润预算', reason)); return []
    b, a = _scaled(work, budget), _scaled(work, actual)
    if work.empty or b.isna().any() or a.isna().any():
        gaps.append(_gap('finance.budget', '利润预算', '预算和实际必须完整，不能把缺失当作零')); return []
    out = []
    for key, title in [('毛利', '毛利润预算达成'), ('净利', '净利润预算达成'), ('费用', '费用预算达成'), ('营业收入', '营业收入预算达成')]:
        exact = {
            '毛利': {'毛利', '毛利润', '营业毛利'}, '净利': {'净利', '净利润'},
            '费用': {'费用', '营业费用', '销售费用'}, '营业收入': {'营业收入', '主营业务收入'},
        }[key]
        rows = work[work[subject].astype(str).str.strip().isin(exact)]
        if rows.empty: continue
        if rows[subject].astype(str).str.strip().nunique() != 1:
            gaps.append(_gap(f'finance.budget.{key}', title, '同一科目唯一口径，不能把汇总科目与明细科目相加'))
            continue
        rows = rows.copy()
        rows['_budget'] = b.loc[rows.index]
        rows['_actual'] = a.loc[rows.index]
        rows['_month'] = rows['_period_date'].dt.to_period('M').astype(str)
        if rows['_month'].duplicated().any():
            gaps.append(_gap(f'finance.budget.{key}', title, '每月同一科目唯一预算与实际，重复或多维明细须先明确汇总口径'))
            continue
        by_month = rows.set_index('_month')[['_actual','_budget']].sort_index()
        axis = (pd.period_range(f'{request.year}-{request.start_month:02d}', f'{request.year}-{request.end_month:02d}', freq='M').astype(str).tolist()
                if getattr(request, 'period_type', 'all') != 'all' else pd.period_range(by_month.index.min(), by_month.index.max(), freq='M').astype(str).tolist())
        actuals = [float(by_month.loc[m,'_actual']) if m in by_month.index else None for m in axis]
        targets = [float(by_month.loc[m,'_budget']) if m in by_month.index else None for m in axis]
        rates = [None if v is None or t is None or t <= 0 else v/t*100 for v,t in zip(actuals,targets)]
        points = [(m,v) for m,v in zip(axis,actuals) if v is not None]
        candidate = _candidate(f'finance.budget.{key}', title, 'budget_achievement', points, '元', source,
                              reason='上传表同时提供同一科目的预算与实际金额；单位为金额而非收入泛化', dimension='科目')
        target, achieved = float(b.loc[rows.index].sum()), float(a.loc[rows.index].sum())
        rate = achieved / target * 100 if target > 0 else None
        is_cost = key == '费用'
        if rate is None:
            candidate['findings']['attention'].append('预算合计非正，无法计算达成率')
        elif (achieved <= target if is_cost else achieved >= target):
            candidate['findings']['good'].append(f'达到预算 {rate:.1f}%')
        else:
            candidate['findings']['attention'].append(f'超预算 {rate:.1f}%' if is_cost else f'低于预算 {rate:.1f}%')
        candidate['kpis'].append({'label': '预算达成率', 'value': '-' if rate is None else f'{rate:.1f}', 'unit': '%'})
        candidate['chart']['echartsOption']['xAxis']['data'] = axis
        candidate['chart']['echartsOption']['legend'] = {'data':['实际','预算','达成率']}
        candidate['chart']['echartsOption']['series'] = [
            {'name': '实际', 'type': 'bar', 'data': actuals},
            {'name': '预算', 'type': 'bar', 'data': targets},
            {'name': '达成率', 'type': 'line', 'yAxisIndex': 1, 'connectNulls':False, 'data': rates},
        ]
        candidate['chart']['echartsOption']['yAxis'] = [
            {'type': 'value', 'name': '元'}, {'type': 'value', 'name': '%'}
        ]
        candidate['tableData'] = {'headers':['期间','实际（元）','预算（元）','达成率（%）'],
                                  'rows':[[m,v,t,r] for m,v,t,r in zip(axis,actuals,targets,rates)]}
        out.append(candidate)
    if not out:
        gaps.append(_gap('finance.budget', '利润预算', '未找到毛利、净利、费用或营业收入等明确科目'))
    return out


def _margins(frame, source, request, can_price, gaps):
    revenue = _col(frame, ('收入', '销售金额', '销售额', '营业收入'))
    cost = _col(frame, ('成本', '销售成本', '营业成本', '总成本'))
    dims = [c for c in ('品类', 'SKU', '渠道') if c in frame.columns]
    if not revenue or not cost or not dims:
        return []
    if not can_price or not _money_ok(type('D', (), {'frame': frame, 'currency': 'CNY', 'sheet_name': ''})()):
        gaps.append(_gap('finance.margin', '品类/SKU/渠道毛利', '缺少人民币币种/单位或金额查看权限')); return []
    work, reason = _period(frame, request)
    if reason:
        gaps.append(_gap('finance.margin', '品类/SKU/渠道毛利', reason)); return []
    r, c = _scaled(work, revenue), _scaled(work, cost)
    if work.empty or r.isna().any() or c.isna().any() or (r <= 0).any() or (c < 0).any():
        gaps.append(_gap('finance.margin', '品类/SKU/渠道毛利', '收入/成本需完整，收入大于零且成本非负')); return []
    work = work.assign(_r=r, _c=c, _m=r-c)
    output = []
    for dim in dims:
        grouped = work.groupby(dim, sort=True)[['_r', '_c', '_m']].sum()
        if work[dim].isna().any() or work[dim].astype(str).str.strip().eq('').any():
            gaps.append(_gap(f'finance.margin.{dim}', f'{dim}毛利', '维度存在缺失值，不能静默丢弃该维度'))
            continue
        if grouped.empty:
            continue
        points = [(str(k), float(v)) for k, v in grouped['_m'].items()]
        total_r, total_m = float(grouped['_r'].sum()), float(grouped['_m'].sum())
        rate = total_m / total_r * 100
        prefix = {'品类': 'category', '产品': 'sku', 'SKU': 'sku', '产品名称': 'sku', '渠道': 'channel'}[dim]
        amount_id = 'finance.margin.amount' if dim == '品类' else f'finance.margin.{prefix}.amount'
        cnd = _candidate(amount_id, f'{dim}毛利额', 'bar_horizontal', points, '元', source,
                         reason='毛利额=收入-成本；总额按金额加总，未对各行毛利率做简单平均', dimension=dim)
        cnd['findings']['attention'].append(f'加权毛利率 {rate:.2f}%（毛利额/收入）')
        cnd['metadata'] = {'weightedMarginRate': round(rate, 6), 'dimension': str(dim)}
        output.append(cnd)
        rates = [(str(k), float(v['_m'] / v['_r'] * 100)) for k, v in grouped.iterrows() if v['_r'] > 0]
        rate_candidate = _candidate(f'finance.margin.{prefix}.rate', f'{dim}毛利率', 'bar_horizontal', rates, '%', source, total=rate,
                                    reason='各组毛利率=该组毛利额/该组收入；总览另以加权率呈现', dimension=dim)
        rate_candidate['metadata'] = {'weightedMarginRate': round(rate, 6), 'dimension': str(dim)}
        rate_candidate['kpis'][0]['label'] = '加权毛利率'
        rate_candidate['findings']['attention'] = [f'加权毛利率 {rate:.2f}%=毛利额合计{total_m:g}元÷收入合计{total_r:g}元；不累加各组百分比']
        output.append(rate_candidate)
    return output


def build(dataset, request, can_price):
    """Return ``(candidates, gaps)`` for one raw qualified Dataset."""
    frame = dataset.frame.copy()
    source = dataset.source
    gaps = []
    if frame.empty:
        return [], [_gap('finance', '财务扩展', '该期间没有来源记录')]
    if not can_price:
        # Cash flow is still a financial amount and therefore also protected.
        return [], [_gap('finance', '财务扩展', '当前身份无金额指标查看权限')]
    if not _money_ok(dataset):
        return [], [_gap('finance.currency', '财务金额', '明确且一致的人民币币种或金额单位；不能把外币标为元')]
    sheet = str(getattr(dataset, 'sheet_name', ''))
    frame.attrs['factory_amount_factor'] = 1e8 if '亿元' in sheet else 1e4 if '万元' in sheet else 1e3 if '千元' in sheet else 1
    candidates = []
    candidates += _cash(frame, source, request, gaps)
    candidates += _cash_trend(frame, source, request, gaps)
    candidates += _ar(frame, source, request, gaps)
    candidates += _budget(frame, source, request, can_price, gaps)
    candidates += _margins(frame, source, request, can_price, gaps)
    return candidates, gaps
