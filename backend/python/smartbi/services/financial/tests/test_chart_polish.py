import pandas as pd
import pytest

from smartbi.api.financial_dashboard import _extract_dimensions
from smartbi.services.financial_dashboard import FinancialDashboardService


def test_percentage_measures_do_not_appear_as_slicer_choices():
    dimensions = _extract_dimensions(pd.DataFrame([
        {'月份': '1月', '本年毛利率': '25%', '上年毛利率': '20%'},
        {'月份': '2月', '本年毛利率': '26%', '上年毛利率': '21%'},
    ]))
    assert dimensions == [{'name': '月份', 'values': ['1月', '2月']}]


def test_margin_difference_labels_say_percentage_points():
    chart = FinancialDashboardService().generate_chart('gross_margin_trend', pd.DataFrame([
        {'月份': '1月', '本年毛利率': '25%', '上年毛利率': '20%'},
        {'月份': '2月', '本年毛利率': '26%', '上年毛利率': '21%'},
    ]), 2026, 'month_range', 1, 2)
    assert chart['success'] is True
    labels = chart['echartsOption']['series'][0]['data']
    assert [x['value'] for x in labels] == [5.0, 5.0]
    assert [x['label']['formatter'] for x in labels] == ['↑5.00个百分点', '↑5.00个百分点']


@pytest.mark.parametrize('current,previous', [('本年毛利率', '上年毛利率'), ('本年占比', '上年比重'), ('本年%', '上年％')])
def test_mixed_amount_and_ratio_measures_are_not_slicers(current, previous):
    dimensions = _extract_dimensions(pd.DataFrame([
        {'月份': '1月', current: '50%', previous: '40%',
         '营业收入': 100, '营业成本': 50, '上年营业收入': 100, '上年营业成本': 60},
        {'月份': '2月', current: '10%', previous: '40%',
         '营业收入': 900, '营业成本': 810, '上年营业收入': 100, '上年营业成本': 60},
    ]))
    assert dimensions == [{'name': '月份', 'values': ['1月', '2月']}]
