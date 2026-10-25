"""Factory facts with explicit units, denominators and additive semantics."""
import statistics
import re
import pandas as pd
from .metrics import Series, number, display, grouped
from smartbi.services.financial import metrics as M


def build(frame, cols, request, can_price, source):
    from .engine import series_candidate
    candidates, gaps = [], []

    def col(key, *names):
        mapped = cols.get(key)
        if mapped in frame.columns: return mapped
        found = [c for c in frame.columns if re.sub(r'[（(].*?[）)]','',str(c)).strip() in names]
        return found[0] if len(found) == 1 else None

    def gap(key, title, *missing):
        gaps.append({'id': key, 'title': title, 'missing': list(missing)})

    def numeric(fields):
        if any(c is None or c not in frame for c in fields): return None
        result = frame[list(dict.fromkeys(fields))].copy()
        for c in result: result[c] = result[c].map(number)
        return None if result.isna().any().any() else result

    def emit(series, kind='line', optional=False):
        c = series_candidate(series, kind, optional)
        candidates.append(c)
        return c

    date = col('date', '日期', '月份', '检验日期', '销售日期')
    product = col('product', '产品', 'SKU', '产品名称')
    unit = col('unit', '单位', '数量单位')
    output = col('output', '产量', '实际产量', '产出数量')
    good = col('good', '良品数', '合格数')
    inspected = col('inspected', '检验数', '检验数量')
    bad = col('defect', '不良数', '不合格数')
    cost = col('cost', '总成本')
    process = col('process', '工序', '工序名称')
    sales = col('sales', '销售金额', '销售额')
    customer = col('customer', '客户', '客户名称')
    order = col('order_id', '订单ID', '订单号')
    batch = col('batch', '批次', '批次ID')
    optional = bool(getattr(request, 'include_optional', False))

    if customer and sales and can_price:
        points = grouped(frame, customer, sales)
        if order and frame[order].duplicated().any():
            gap('sales.customer', '客户销售金额', '唯一订单金额事实；明细行重复订单金额不能重复累计')
        elif points:
            emit(Series('sales.customer', '客户销售金额', '销售', '元', points, source), 'bar_horizontal')
        else: gap('sales.customer', '客户销售金额', '完整客户与有效金额')

    subtotal = col('sku_subtotal', '订单行小计', 'SKU折后金额')
    total = col('order_total', '订单总额', '销售订单总额')
    line = col('line_id', '订单行ID')
    sku = col('sku', 'SKU', '产品ID')
    if sku or subtotal:
        values = numeric([subtotal, total])
        valid = can_price and all([order, line, sku]) and values is not None
        if valid:
            work = frame.copy(); work[[subtotal, total]] = values
            valid = not work[[order, line, sku]].isna().any().any() and not work[line].duplicated().any()
            if valid:
                valid = all(g[total].nunique() == 1 and abs(g[subtotal].sum()-g[total].iloc[0]) <= .01
                            for _, g in work.groupby(order, dropna=False))
            if valid:
                emit(Series('sales.sku', 'SKU销售金额（逐单对账）', '销售', '元', grouped(work, sku, subtotal), source), 'bar_horizontal')
        if not valid:
            gap('sales.sku', 'SKU销售金额', '真实SKU、唯一订单行ID、订单行小计（折后）与订单保存总额逐单对账；不能任意分摊折扣')

    material = col('material', '原料', '物料', '原料名称')
    price = col('purchase_price', '采购单价', '入库单价')
    price_unit = col('price_unit', '价格单位')
    received = col('inflow', '入库数量', '实收数量')
    if price or material and received:
        values = numeric([price, received])
        valid = can_price and all([material, unit, price_unit]) and values is not None
        if valid:
            valid = not frame[[material, unit, price_unit]].isna().any().any() and frame[unit].astype(str).str.strip().ne('').all()
            valid = valid and frame[unit].eq(frame[price_unit]).all() and values[price].ge(0).all() and values[received].gt(0).all()
        if not valid:
            gap('purchase.price', '原料采购加权单价', '原料、正实收数量、数量单位与匹配的价格单位、有效单价及金额权限')
        else:
            work = frame.copy(); work[[price, received]] = values
            work['_amount'] = work[price]*work[received]
            for u, group in work.groupby(unit, dropna=False):
                ag = group.groupby(material)[['_amount', received]].sum()
                points = [(str(k), M.ratio(r['_amount'], r[received], 1).value) for k, r in ag.iterrows()]
                c = emit(Series('purchase.price@'+str(u), '原料采购加权单价', '采购', '元/'+str(u), points, source, 'nonadditive'), 'bar_horizontal')
                c['findings']['attention'].append('各原料分别按实收数量加权：金额合计÷数量合计；不同原料均价不相加，不代表扣退货后的净采购价')

    components = [col(k, *names) for k, names in [
        ('material_cost', ('材料成本', '原料成本')), ('labor_cost', ('人工成本',)),
        ('equipment_cost', ('设备成本',)), ('other_cost', ('其他成本',))]]
    if process:
        values = numeric(components+[cost])
        valid = can_price and values is not None and not frame[process].isna().any()
        valid = valid and values.ge(0).all().all() and values[components].sum(axis=1).sub(values[cost]).abs().le(.01).all()
        if valid:
            work = frame.copy(); work[components+[cost]] = values
            c = emit(Series('process.full-cost', '工序完整成本', '成本', '元', grouped(work, process, cost), source), 'bar_horizontal')
            c['findings']['attention'].append('逐行材料+人工+设备+其他=总成本；仅覆盖来源已完成归集的工序')
        else: gap('process.full-cost', '工序完整成本', '完整材料/人工/设备/其他/总成本及逐行对账，不能把两项成本称为完整成本')

    for key, title, num, den in [
        ('quality.time-trend', '检验合格率时间趋势', good, inspected),
        ('production.quality-trend', '生产良品率时间趋势', good, output)]:
        if num and date and not den:
            gap(key, title, '检验数或实际产量分母，不能由合格数猜测总数')
        if num and den:
            fields = [num, den]+([bad] if key.startswith('quality') and bad else [])
            values = numeric(fields)
            valid = date is not None and values is not None and not frame[date].isna().any()
            valid = valid and (unit is not None or key.startswith('quality'))
            valid = valid and values.ge(0).all().all() and values[den].gt(0).all() and values[num].le(values[den]).all()
            if valid and key.startswith('quality') and bad:
                valid = values[num].add(values[bad]).sub(values[den]).abs().le(.00001).all()
            if not valid:
                gap(key, title, '真实日期、完整非负分子分母且分母大于零；通过数与不良数须和检验数一致'); continue
            work = frame.copy(); work[list(values.columns)] = values
            groups = work.groupby(unit, dropna=False) if unit else [('样本', work)]
            for u, group in groups:
                if pd.isna(u) or not str(u).strip(): gap(key, title, '同一数量单位'); continue
                points = grouped(group, date, num, ratio_denominator=den)
                if points and len(points) >= 2:
                    emit(Series(key+'@'+str(u), title, '质量', '%', points, source, 'ratio', M.total(group[num]), M.total(group[den])))
                else: gap(key, title, '至少两个有效日期；单点不画趋势')

    if date and product and output and good and cost:
        vals = numeric([output, good, cost])
        valid = can_price and batch and unit and vals is not None
        valid = valid and not frame[[batch, unit, date, product]].isna().any().any() and not frame[batch].duplicated().any()
        valid = valid and vals.ge(0).all().all() and vals[output].gt(0).all() and vals[good].le(vals[output]).all()
        if valid:
            work = frame.copy(); work[[output, good, cost]] = vals
            for (sku_name, u), group in work.groupby([product, unit], dropna=False):
                if group[date].nunique() < 2: continue
                sums = group.groupby(date)[[output, good, cost]].sum()
                points = [(str(d), float(r[output])) for d, r in sums.iterrows()]
                c = emit(Series('production.fused@'+str(sku_name)+'@'+str(u), str(sku_name)+'生产/质量/成本联合趋势', '生产', str(u), points, source))
                rates = [M.ratio(r[good], r[output]).value for _, r in sums.iterrows()]
                costs = [float(r[cost]) for _, r in sums.iterrows()]
                c['chart'] = {'chartType': 'combination', 'echartsOption': {
                    'animation': False, 'tooltip': {'trigger': 'axis'}, 'legend': {'data': ['产量', '良品率', '总成本']},
                    'xAxis': {'type': 'category', 'data': [d for d, _ in points]},
                    'yAxis': [{'type': 'value', 'name': str(u)}, {'type': 'value', 'name': '%', 'max': 100},
                              {'type': 'value', 'name': '元', 'position': 'right', 'offset': 55}],
                    'series': [{'name': '产量', 'type': 'bar', 'data': [v for _, v in points]},
                               {'name': '良品率', 'type': 'line', 'yAxisIndex': 1, 'data': rates},
                               {'name': '总成本', 'type': 'line', 'yAxisIndex': 2, 'data': costs}]}}
                c['tableData'] = {'headers': ['日期', '产量（'+str(u)+'）', '良品率（%）', '总成本（元）'],
                                  'rows': [[d, q, r, v] for (d, q), r, v in zip(points, rates, costs)]}
                c['kpis'] += [{'label': '累计良品率', 'value': display(M.ratio(M.total(group[good]), M.total(group[output])).value), 'unit': '%'},
                              {'label': '总成本', 'value': display(M.total(group[cost])), 'unit': '元'}]
                c['findings']['attention'].append('同批次对齐的数量、质量及已归集总成本；未推断因果或在制品流向')
        else: gap('production.fused', '生产/质量/成本联合趋势', '唯一批次、同产品同单位、真实日期与完整产量/良品/总成本')

    input_qty = col('input', '投入量', '投入数量')
    loss = col('loss', '损耗量')
    opening = col('opening_wip', '期初在制品')
    closing = col('closing_wip', '期末在制品')
    if process or input_qty or loss:
        vals = numeric([input_qty, output, loss, opening, closing])
        valid = unit and date and process and vals is not None
        valid = valid and not frame[[unit, date, process]].isna().any().any() and vals.ge(0).all().all()
        valid = valid and vals[opening].add(vals[input_qty]).sub(vals[output]).sub(vals[loss]).sub(vals[closing]).abs().le(.00001).all()
        if valid:
            work = frame.copy(); work[list(vals.columns)] = vals
            for u, group in work.groupby(unit):
                emit(Series('process.loss@'+str(u), '工序已核对损耗', '生产', str(u), grouped(group, date, loss), source))
        else: gap('process.loss', '工序损耗', '同单位期初在制品+投入=产出+已记录损耗+期末在制品；不能倒推未记录损耗')

    if optional:
        planned = col('planned_runtime', '计划运行分钟')
        runtime = col('runtime', '实际运行分钟')
        cycle = col('ideal_cycle', '理想周期秒')
        count = col('total_count', '总数')
        if any([planned, runtime, cycle, count]):
            vals = numeric([planned, runtime, cycle, count, good])
            valid = vals is not None and vals.ge(0).all().all() and vals[planned].gt(0).all() and vals[runtime].gt(0).all()
            valid = valid and vals[count].gt(0).all() and vals[cycle].gt(0).all() and vals[good].le(vals[count]).all()
            valid = valid and vals[runtime].le(vals[planned]).all() and vals[cycle].mul(vals[count]).le(vals[runtime]*60+.00001).all()
            valid = valid and vals[cycle].nunique() == 1
            if valid:
                a = M.total(vals[runtime])/M.total(vals[planned])
                p = M.total(vals[cycle]*vals[count])/(M.total(vals[runtime])*60)
                q = M.total(vals[good])/M.total(vals[count])
                c = emit(Series('production.oee', '设备OEE', '生产', '%', [('同节拍观察期', round(100*a*p*q, 6))], source, 'nonadditive'), 'gauge', True)
                c['kpis'] = [{'label': label, 'value': display(v*100), 'unit': '%'} for label, v in [('OEE', a*p*q), ('时间开动率', a), ('性能开动率', p), ('良品率', q)]]
                c['findings']['attention'].append(f'OEE {display(100*a*p*q)}%=时间开动率{display(a*100)}%×性能开动率{display(p*100)}%×良品率{display(q*100)}%；秒/分钟已换算')
            else: gap('production.oee', '设备OEE', '同一理想节拍的计划/实际运行分钟、理想周期秒、总数与良品数；逐行0≤A/P/Q≤100%')

        avg = col('average_inventory', '平均库存金额')
        period_cost = col('period_cost', '同期销货成本', '同期领料成本')
        period = col('accounting_period', '会计期间')
        if avg or period_cost:
            vals = numeric([avg, period_cost])
            valid = can_price and period and vals is not None and not frame[period].isna().any()
            valid = valid and vals[avg].gt(0).all() and vals[period_cost].ge(0).all()
            if valid:
                work = frame.copy(); work[[avg, period_cost]] = vals
                valid = all(g[avg].nunique() == 1 and g[period_cost].nunique() == 1 for _, g in work.groupby(period))
            if valid:
                points = [(str(k), M.ratio(g[period_cost].iloc[0], g[avg].iloc[0], 1).value) for k, g in work.groupby(period)]
                c = emit(Series('stock.turnover', '库存金额周转次数', '库存', '次', points, source, 'nonadditive'), 'bar', True)
                c['findings']['attention'].append('每会计期间使用一次同期销货/领料成本÷该期间提供的平均库存金额；重复快照不累加成本')
            else: gap('stock.turnover', '库存周转', '会计期间、同币种平均库存金额、唯一同期销货/领料成本事实及金额权限；采购金额不能代替消耗成本')

        if all([batch, date, product, unit, output]):
            vals = numeric([output])
            if vals is not None and not frame[[batch, date, product, unit]].isna().any().any() and not frame[batch].duplicated().any():
                work = frame.copy(); work[output] = vals[output]
                for (sku_name, u), group in work.groupby([product, unit]):
                    if len(group) < 10 or group[output].lt(0).any(): continue
                    observations = group[output].tolist()
                    mean, deviation = statistics.mean(observations), statistics.pstdev(observations)
                    if not deviation: continue
                    points = [(str(r[batch]), float(r[output])) for _, r in group.iterrows()]
                    outliers = [k for k, v in points if abs(v-mean) > 2*deviation]
                    if outliers:
                        c = emit(Series('production.anomaly@'+str(sku_name)+'@'+str(u), '同SKU批次产量偏离观察', '生产', str(u), points, source, 'nonadditive'), 'bar', True)
                        c['findings']['attention'].append(f'{len(group)}个同SKU/单位批次，{len(outliers)}个值偏离均值超过2个总体标准差；仅统计观察，不代表原因')
    return candidates, gaps
