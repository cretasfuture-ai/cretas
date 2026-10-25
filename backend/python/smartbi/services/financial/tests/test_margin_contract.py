"""Margin facts must agree in the headline, findings, KPIs and chart table."""
import pandas as pd
import pytest

from smartbi.services.financial_dashboard import FinancialDashboardService


def dashboard(rows, start=1, end=2):
    result = FinancialDashboardService().generate_dashboard(pd.DataFrame(rows), 2026, 'month_range', start, end)
    chart = next(c for c in result['charts'] if c['chartType'] == 'gross_margin_trend')
    return result, chart, {k['label']: k['value'] for k in chart['kpis']}


def cost_rows():
    return [
        {'月份': '1月', '项目': '营业收入', '实际': 100, '上年': 100},
        {'月份': '1月', '项目': '营业成本', '实际': 50, '上年': 60},
        {'月份': '2月', '项目': '营业收入', '实际': 900, '上年': 100},
        {'月份': '2月', '项目': '营业成本', '实际': 810, '上年': 60},
    ]


@pytest.mark.parametrize('shape', ['cost-rows', 'profit-rows', 'cost-columns', 'profit-columns'])
def test_unequal_revenues_use_weighted_margin_everywhere(shape):
    rows = cost_rows()
    if shape == 'profit-rows':
        rows = [{**r, '项目': '毛利', '实际': 50 if r['月份'] == '1月' else 90, '上年': 40}
                if r['项目'] == '营业成本' else r for r in rows]
    if shape.endswith('columns'):
        rows = [{'月份': '1月', '营业收入': 100, '上年营业收入': 100,
                 '本年毛利率': '50%', '上年毛利率': '40%'},
                {'月份': '2月', '营业收入': 900, '上年营业收入': 100,
                 '本年毛利率': '10%', '上年毛利率': '40%'}]
        key, last = ('营业成本', '上年营业成本') if shape == 'cost-columns' else ('毛利', '上年毛利')
        rows[0].update({key: 50, last: 60 if shape == 'cost-columns' else 40})
        rows[1].update({key: 810 if shape == 'cost-columns' else 90,
                        last: 60 if shape == 'cost-columns' else 40})
    result, chart, kpis = dashboard(rows)
    assert chart['success'] is True
    assert kpis['本年累计毛利率'] == '14.0'
    assert kpis['上年累计毛利率'] == '40.0'
    assert kpis['累计差异'] == '-26.0'
    assert '毛利率 14.0%' in result['headline']['text']
    assert any('毛利率 14.0%' in line for line in result['findings']['good'])
    assert chart['tableData']['rows'][1]['values'] == [50.0, 10.0, 14.0]
    assert chart['metadata']['marginBasis'] == 'weighted'
    assert '简单平均' not in result['headline']['text']


def test_ratio_only_displayed_difference_uses_the_same_rounding_everywhere():
    result, chart, kpis = dashboard([
        {'月份': f'{m}月', '本年毛利率': '26.34%', '上年毛利率': '26.26%'} for m in (1, 2)])
    assert kpis['本年月均毛利率'] == '26.3'
    assert kpis['上年月均毛利率'] == '26.3'
    assert kpis['平均差异'] == '+0.0'
    assert '与上年持平（各月简单平均）' in result['headline']['text']
    assert not any('平均毛利率' in x for values in result['findings'].values() for x in values)
    assert '各月简单平均' in chart['metadata']['note']


def test_a_missing_cost_month_is_not_silently_treated_as_zero_or_an_average():
    rows = cost_rows()[:-1]
    result, chart, kpis = dashboard(rows)
    assert kpis['本年累计毛利率'] == '-'
    assert '毛利率 95.0%' not in (result['headline'] or {}).get('text', '')
    assert not any('毛利率 95.0%' in x for values in (result['findings'] or {}).values() for x in values)
    assert chart['tableData']['rows'][1]['values'] == [50.0, None, None]
    assert '缺少' in chart['metadata']['note']


def test_month_selection_recomputes_the_revenue_weight():
    result, chart, kpis = dashboard(cost_rows(), 2, 2)
    assert kpis['本年累计毛利率'] == '10.0'
    assert '毛利率 10.0%' in result['headline']['text']
    assert chart['tableData']['rows'][1]['values'] == [10.0, 10.0]


def test_zero_revenue_does_not_claim_a_zero_margin():
    rows = [{**r, '实际': 0} if r['项目'] == '营业收入' else r for r in cost_rows()]
    _, chart, kpis = dashboard(rows)
    assert kpis['本年累计毛利率'] == '-'
    assert '分母为 0' in chart['metadata']['note']


@pytest.mark.parametrize('profit', [40, 45])
def test_reported_gross_profit_aliases_are_one_fact_or_an_explicit_conflict(profit):
    rows = [{'月份': '1月', '项目': subject, '实际': amount, '上年': amount}
            for subject, amount in [('营业收入', 100), ('毛利', 40), ('毛利润', profit)]]
    result = FinancialDashboardService().generate_dashboard(pd.DataFrame(rows), 2026, 'month_range', 1, 1)
    text = (result['headline'] or {}).get('text', '')
    if profit == 40:
        chart = next(c for c in result['charts'] if c['chartType'] == 'gross_margin_trend')
        assert {k['label']: k['value'] for k in chart['kpis']}['本年累计毛利率'] == '40.0'
        assert '毛利率 40.0%' in text
    else:
        chart = next(c for c in result['charts'] if c['chartType'] == 'gross_margin_trend')
        assert chart['success'] is False
        assert '冲突' in chart['metadata']['reason']
        assert '毛利率' not in text


def test_incomplete_last_year_cannot_be_compared_with_a_full_current_period():
    rows = [{**r, '上年': None} if r['月份'] == '2月' else r for r in cost_rows()]
    _, chart, kpis = dashboard(rows)
    assert kpis['本年累计毛利率'] == '14.0'
    assert kpis['上年累计毛利率'] == '-'
    assert kpis['累计差异'] == '-'
    assert '上年与本年收入期间不一致' in chart['metadata']['note']


@pytest.mark.parametrize('column', ['实际', '实际（万元）'])
def test_explicit_subject_row_units_take_priority_without_double_scaling(column):
    rows = [{'月份': '1月', '项目': '营业收入（万元）', column: 1, '上年': 1},
            {'月份': '1月', '项目': '营业成本（元）', column: 5000, '上年': 5000}]
    result, _, kpis = dashboard(rows, 1, 1)
    assert kpis['本年累计毛利率'] == '50.0'
    assert '毛利率 50.0%' in result['headline']['text']
    assert any('毛利 5,000.00 / 营业收入 1.00万' in text for text in result['findings']['good'])


def test_explicit_year_columns_follow_requested_year_and_not_column_order():
    # The older year is deliberately first, and 2024 must never overwrite 2026.
    rows = [{'月份': '1月', '2025年营业收入': 100, '2025年营业成本': 60,
             '2026年营业收入': 100, '2026年营业成本': 50,
             '2024年营业收入': 9999, '2024年营业成本': 1},
            {'月份': '2月', '2025年营业收入': 100, '2025年营业成本': 60,
             '2026年营业收入': 900, '2026年营业成本': 810,
             '2024年营业收入': 9999, '2024年营业成本': 1}]
    result, chart, kpis = dashboard(rows)
    assert kpis['本年累计毛利率'] == '14.0'
    assert kpis['上年累计毛利率'] == '40.0'
    assert '毛利率 14.0%' in result['headline']['text']
    assert chart['tableData']['rows'][1]['values'] == [50.0, 10.0, 14.0]
