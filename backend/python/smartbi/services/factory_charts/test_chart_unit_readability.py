from smartbi.services.factory_charts.engine import Series, _card_layout, _option
from copy import deepcopy
import pytest


def test_dense_attainment_chart_keeps_all_points_thresholds_and_tooltip():
    option = {
        'xAxis': {'type': 'category', 'data': [f'2026-06-{i:02}' for i in range(1, 8)]},
        'yAxis': [{'type': 'value'}, {'type': 'value'}],
        'tooltip': {'trigger': 'axis'},
        'series': [{'name': '达成率', 'type': 'line', 'data': [100.63, 12, 513, 9.42, .8, 85, 100],
                    'label': {'show': True},
                    'markLine': {'data': [{'yAxis': 80, 'label': {'show': True}},
                                          {'yAxis': 100, 'label': {'show': True}}]}},
                   {'name': '普通走势', 'type': 'line', 'data': [1, 2, 3, 4, 5, 6, 7],
                    'label': {'show': True}}],
    }
    original = deepcopy(option)
    _card_layout(option, 'production.production.plan@test')
    assert option['xAxis']['data'] == original['xAxis']['data']
    assert option['tooltip'] == original['tooltip']
    for after, before in zip(option['series'], original['series']):
        assert after['data'] == before['data']
    assert [r['yAxis'] for r in option['series'][0]['markLine']['data']] == [80, 100]
    assert option['series'][0]['label']['show'] is False
    assert option['series'][1]['label']['show'] is True


def test_attainment_axis_and_gauge_round_limits_without_changing_rates():
    import pandas as pd
    from smartbi.api.financial_dashboard import FactoryRecommendationRequest
    from smartbi.services.factory_charts.advanced import build
    from smartbi.services.factory_charts.engine import columns
    frame = pd.DataFrame([
        {'日期': '2026-06-01', '产品': '产品A', '单位': 'kg', '产量': 513, '计划产量': 100},
        {'日期': '2026-06-02', '产品': '产品A', '单位': 'kg', '产量': 100, '计划产量': 100},
    ])
    candidates, _ = build(frame, columns(frame), FactoryRecommendationRequest(use_ai=False), True, 'fixture')
    plan = next(c for c in candidates if c['id'].endswith(':budget_comparison'))
    option = plan['chart']['echartsOption']
    assert next(s['data'] for s in option['series'] if s['name'] == '达成率') == [513, 100]
    assert option['yAxis'][1]['max'] == 570
    gauge = next(c for c in candidates if c['id'].endswith(':gauge'))
    assert gauge['chart']['echartsOption']['series'][0]['data'][0]['value'] == 306.5
    assert gauge['chart']['echartsOption']['series'][0]['max'] == 340


@pytest.mark.parametrize('unit', ['元', 'kg'])
def test_factory_line_and_area_keep_original_values_and_units_with_missing_dates(unit):
    points = [
        ('2026-01-01', 30),
        ('2026-01-02', 2902.66),
        ('2026-01-04', 20),
        ('2026-01-05', 22000),
    ]
    series = Series('sales.trend' if unit == '元' else 'production.output',
                    '销售金额趋势' if unit == '元' else '产量趋势',
                    '销售' if unit == '元' else '生产', unit, points, 'factory')

    for kind in ('line', 'area'):
        option = _option(series, kind)
        assert option['xAxis']['data'] == [
            '2026-01-01', '2026-01-02', '2026-01-03', '2026-01-04', '2026-01-05'
        ]
        assert option['series'][0]['data'] == [30, 2902.66, None, 20, 22000]
        assert option['yAxis']['name'] == unit


def test_short_category_axes_show_all_labels_and_wrap_long_batch_names():
    batch_ids = ['445ccb2d-1e66-48bd-8ff3-07e5d8d11f1b']
    option = {
        'xAxis': {'type': 'value'},
        'yAxis': {'type': 'category', 'data': batch_ids},
        'series': [{'type': 'bar', 'data': [3]}],
        'grid': {'left': '15%', 'right': '4%', 'bottom': '3%'},
    }

    _card_layout(option, 'finished_goods.shipment:bar_horizontal')

    labels = option['yAxis']['axisLabel']
    assert option['yAxis']['data'] == batch_ids
    assert labels == {
        'interval': 0,
        'hideOverlap': False,
        'overflow': 'breakAll',
        'lineHeight': 16,
        'width': 140,
    }
    assert option['grid']['left'] == '15%'


def test_dense_y_names_keep_full_data_and_tooltip_names_without_multiline_collision():
    names = [f'complete-long-product-identity-{index}' for index in range(5)]
    option = {'xAxis': {'type': 'value'}, 'yAxis': {'type': 'category', 'data': names},
              'tooltip': {'trigger': 'axis'}, 'series': [{'type': 'bar', 'data': [1, 2, 3, 4, 5]}]}
    _card_layout(option)
    assert option['yAxis']['data'] == names
    assert option['series'][0]['data'] == [1, 2, 3, 4, 5]
    assert option['tooltip'] == {'trigger': 'axis'}
    assert option['yAxis']['axisLabel']['overflow'] == 'truncate'


def test_dense_long_x_categories_only_change_painted_labels_not_source_data():
    names = [f'445ccb2d-1e66-48bd-8ff3-07e5d8d11f{index:02d}' for index in range(11)]
    values = list(range(11))
    option = {'xAxis': {'type': 'category', 'data': names},
              'yAxis': {'type': 'value'},
              'tooltip': {'trigger': 'axis'},
              'series': [{'type': 'bar', 'data': values}]}

    _card_layout(option, 'production.anomaly:bar')

    assert option['xAxis']['data'] == names
    assert option['series'][0]['data'] == values
    assert option['tooltip'] == {'trigger': 'axis'}
    assert option['xAxis']['axisLabel'] == {
        'interval': 'auto',
        'hideOverlap': True,
        'overflow': 'truncate',
        'lineHeight': 16,
        'width': 48,
    }


@pytest.mark.parametrize('count', [2, 3, 4, 6])
def test_date_x_axis_keeps_existing_label_configuration(count):
    dates = [f'2026-01-{index:02d}' for index in range(1, count+1)]
    option = {'xAxis': {'type': 'category', 'data': dates,
                        'axisLabel': {'rotate': 30}},
              'yAxis': {'type': 'value'},
              'series': [{'type': 'line', 'data': list(range(1, count+1))}]}

    _card_layout(option, 'production.output:line')

    assert option['xAxis']['data'] == dates
    assert option['xAxis']['axisLabel'] == {'rotate': 30}


def test_zoom_slider_keeps_all_data_and_range_while_separating_legend():
    option = {'xAxis': {'type': 'category', 'data': [str(i) for i in range(20)]},
              'yAxis': {'type': 'value'}, 'series': [{'type': 'bar', 'data': list(range(20))}],
              'legend': {'data': ['产量'], 'bottom': 0},
              'dataZoom': [{'type': 'inside', 'start': 20, 'end': 80},
                           {'type': 'slider', 'start': 20, 'end': 80, 'bottom': 0, 'height': 30}]}
    original = deepcopy(option)
    _card_layout(option)
    assert option['series'] == original['series']
    assert option['xAxis']['data'] == original['xAxis']['data']
    assert option['dataZoom'][0] == original['dataZoom'][0]
    assert [(z['start'], z['end']) for z in option['dataZoom']] == [(20, 80), (20, 80)]
    assert option['dataZoom'][1]['bottom'] >= 32
    assert option['grid']['bottom'] >= 88


def test_y_bound_gantt_slider_without_orient_keeps_its_vertical_geometry():
    option = {'xAxis': {'type': 'value'},
              'yAxis': {'type': 'category', 'data': [f'Task{i}' for i in range(12)]},
              'grid': {'left': 80, 'right': 40, 'top': 40, 'bottom': 64, 'containLabel': True},
              'series': [{'type': 'bar', 'data': list(range(12))}],
              'dataZoom': [{'type': 'slider', 'yAxisIndex': 0, 'right': 5, 'width': 15,
                            'start': 0, 'end': 100},
                           {'type': 'inside', 'yAxisIndex': 0, 'start': 0, 'end': 100}]}
    original = deepcopy(option)
    _card_layout(option)
    assert option['dataZoom'] == original['dataZoom']
    assert option['grid'] == original['grid']


def test_short_x_categories_show_all_labels_without_trimming_data():
    categories = ['供应商甲', '供应商乙', '供应商丙', '供应商丁']
    option = {
        'xAxis': {'type': 'category', 'data': categories},
        'yAxis': {'type': 'value'},
        'series': [{'type': 'bar', 'data': [10, 20, 30, 40]}],
        'grid': {'left': '8%', 'right': '8%', 'bottom': 64},
    }

    _card_layout(option, 'purchase.inventory:bar')

    assert option['xAxis']['data'] == categories
    assert option['xAxis']['axisLabel']['interval'] == 0
    assert option['xAxis']['axisLabel']['hideOverlap'] is False
    assert option['xAxis']['axisLabel']['overflow'] == 'breakAll'
    assert option['xAxis']['axisLabel']['width'] <= 224 // len(categories)
    assert option['grid']['bottom'] >= 64


def test_channel_analysis_reserves_right_margin_for_amount_and_share_labels():
    option = {
        'xAxis': {'type': 'category', 'data': ['渠道A', '渠道B']},
        'yAxis': {'type': 'value'},
        'series': [{'type': 'bar', 'data': [30, 100],
                    'label': {'show': True, 'position': 'top',
                              'formatter': '{c} ({d}%)'}}],
        'grid': {'left': '8%', 'right': '4%', 'bottom': '3%'},
    }

    _card_layout(option, 'finance.channel_analysis:bar')

    assert option['grid']['right'] == 140
    assert option['series'][0]['label']['show'] is True
    assert option['series'][0]['label']['formatter'] == '{c} ({d}%)'
