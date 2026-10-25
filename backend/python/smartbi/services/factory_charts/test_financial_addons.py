from types import SimpleNamespace

import pandas as pd

from .financial_addons import build


def req(**kw):
    values = {'period_type': 'all', 'year': 2026, 'start_month': 1, 'end_month': 3}
    values.update(kw)
    return SimpleNamespace(**values)


def dataset(rows):
    return SimpleNamespace(frame=pd.DataFrame(rows), source='factory-finance', currency='CNY', sheet_name='现金流量表（人民币元）')


def test_cash_ar_budget_and_weighted_margin_candidates_have_proof():
    ds = dataset([
        {'日期': '2026-01', '现金流活动': '经营活动', '现金流金额': 100, '客户': '甲', '单据ID': 'I1', '未收金额': 120, '账龄天数': 10, '截止日': '2026-03-31', '实际': 90,
         '科目': '毛利润', '预算': 90, '收入': 300, '成本': 180, '品类': 'A'},
        {'日期': '2026-02', '现金流活动': '投资活动', '现金流金额': -20, '客户': '乙', '单据ID': 'I2', '未收金额': 80, '账龄天数': 100, '截止日': '2026-03-31', '实际': 50,
         '科目': '净利润', '预算': 50, '收入': 100, '成本': 80, '品类': 'B'},
    ])
    candidates, gaps = build(ds, req(period_type='year'), True)
    ids = {c['id'] for c in candidates}
    assert {'finance.cash_flow_waterfall', 'finance.cashflow_trend', 'finance.ar_aging',
            'finance.budget.毛利', 'finance.budget.净利', 'finance.margin.amount'} <= ids
    margin = next(c for c in candidates if c['id'] == 'finance.margin.amount')
    assert margin['metadata']['weightedMarginRate'] == 35.0
    assert all(c['unit'] in {'元', '%'} for c in candidates)
    assert not any(g['id'] == 'finance.currency' for g in gaps)


def test_missing_date_and_foreign_currency_are_gaps_without_guessing():
    no_date = dataset([{'现金流活动': '经营活动', '现金流金额': 1}])
    no_date.currency = 'CNY'
    candidates, gaps = build(no_date, req(period_type='year'), True)
    assert candidates == []
    assert any('日期' in m or '期间' in m for g in gaps for m in g['missing'])

    foreign = dataset([{'日期': '2026-01', '现金流活动': '经营活动', '现金流金额': 1}])
    foreign.currency = 'USD'
    candidates, gaps = build(foreign, req(), True)
    assert candidates == []
    assert any(g['id'] == 'finance.currency' for g in gaps)


def test_cash_and_ar_reject_nonfinite_reconciled_or_unidentified_rows():
    bad_cash = dataset([{'日期': '2026-01-20', '现金流活动': '经营活动', '现金流金额': float('nan')}])
    candidates, gaps = build(bad_cash, req(period_type='year'), True)
    assert not any(c['id'].startswith('finance.cash') for c in candidates)
    assert any('非数值' in m for g in gaps for m in g['missing'])

    bad_ar = dataset([{'日期': '2026-01', '客户': '甲', '单据ID': 'I1', '未收金额': 30,
                       '账龄天数': 10, '截止日': '2026-01-31', '核销状态': 'NEEDS_RECONCILIATION'}])
    candidates, gaps = build(bad_ar, req(period_type='year'), True)
    assert not any(c['id'] == 'finance.ar_aging' for c in candidates)
    assert any('未核销' in m for g in gaps for m in g['missing'])


def test_cash_waterfall_offsets_and_unknown_activity_are_provable():
    ds = dataset([{'日期': '2026-01-20', '现金流活动': '经营活动', '现金流金额': 100},
                  {'日期': '2026-02', '现金流活动': '投资活动', '现金流金额': -40}])
    candidates, _ = build(ds, req(), True)
    waterfall = next(c for c in candidates if c['id'] == 'finance.cash_flow_waterfall')
    option = waterfall['chart']['echartsOption']
    assert option['xAxis']['data'] == ['经营活动','投资活动']
    assert option['series'][0]['data'] == [0.0, 60.0]
    assert [row['value'] for row in waterfall['tableData']] == [100,-40]
    assert waterfall['kpis'][-1]['value'] == '60.00'

    false_cash = dataset([{'日期': '2026-01', '现金流活动': '营业收入', '现金流金额': 100}])
    candidates, gaps = build(false_cash, req(period_type='year'), True)
    assert not any(c['id'].startswith('finance.cash') for c in candidates)
    assert any('明确' in m for g in gaps for m in g['missing'])


def test_budget_overspend_and_margin_dimensions_keep_direction_and_units():
    ds = dataset([{'日期': '2026-01', '科目': '营业费用', '预算': 100, '实际': 120,
                   '收入': 100, '成本': 70, '品类': 'A', 'SKU': 'S1', '渠道': '线上'},
                  {'日期': '2026-02', '科目': '毛利率', '预算': 100, '实际': 999,
                   '收入': 900, '成本': 100, '品类': 'B', 'SKU': 'S2', '渠道': '线下'}])
    candidates, _ = build(ds, req(period_type='year'), True)
    expense = next(c for c in candidates if c['id'] == 'finance.budget.费用')
    assert any('超预算' in x for x in expense['findings']['attention'])
    assert not any('毛利率' in c['title'] and c['id'].startswith('finance.budget') for c in candidates)
    margin_ids = {c['id'] for c in candidates if c['id'].startswith('finance.margin.')}
    assert {'finance.margin.amount', 'finance.margin.category.rate', 'finance.margin.sku.amount',
            'finance.margin.sku.rate', 'finance.margin.channel.amount', 'finance.margin.channel.rate'} <= margin_ids


def test_ar_requires_consistent_asof_and_keeps_not_due_bucket():
    ds = dataset([{'客户': '甲', '单据ID': 'I1', '未收金额': 20, '到期日': '2026-04-01', '截止日': '2026-03-31'},
                  {'客户': '乙', '单据ID': 'I2', '未收金额': 30, '到期日': '2026-02-01', '截止日': '2026-03-31'}])
    candidates, gaps = build(ds, req(period_type='year'), True)
    ar = next(c for c in candidates if c['id'] == 'finance.ar_aging')
    assert any(row['label'] == '未到期' and row['value'] == 20 for row in ar['tableData'])
    mismatch = dataset([{'客户': '甲', '单据ID': 'I1', '未收金额': 20, '到期日': '2026-04-01', '截止日': '2026-03-31'},
                        {'客户': '乙', '单据ID': 'I2', '未收金额': 30, '到期日': '2026-02-01', '截止日': '2026-02-28'}])
    candidates, gaps = build(mismatch, req(period_type='year'), True)
    assert not any(c['id'] == 'finance.ar_aging' for c in candidates)
    assert any('一致' in m for g in gaps for m in g['missing'])

