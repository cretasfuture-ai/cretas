from types import SimpleNamespace

import pandas as pd

from smartbi.services.financial.category_yoy import CategoryYoyComparisonBuilder


def test_category_yoy_retains_unknown_months_as_null():
    frame = pd.DataFrame([
        {'月份': '2026-01', '品类': '冻品', '实际': 100, '上年': 80},
        {'月份': '2026-03', '品类': '冻品', '实际': 130, '上年': 120},
    ])
    result = CategoryYoyComparisonBuilder().build(
        frame, None, {'start_month': 1, 'end_month': 3}, 2026)
    assert result['metadata']['dataQuality'] == 'good'
    assert result['echartsOption']['xAxis']['data'] == ['1月', '2月', '3月']
    current = next(s for s in result['echartsOption']['series'] if s['name'] == '冻品(本年)')
    assert [point['value'] if isinstance(point, dict) else point for point in current['data']] == [100, None, 130]


def test_category_yoy_missing_period_column_is_a_gap():
    result = CategoryYoyComparisonBuilder().build(
        pd.DataFrame([{'品类': '冻品', '实际': 100, '上年': 80}]),
        None, {'start_month': 1, 'end_month': 3}, 2026)
    assert result['metadata']['dataQuality'] == 'insufficient'
    assert '月份' in result['metadata']['reason']
