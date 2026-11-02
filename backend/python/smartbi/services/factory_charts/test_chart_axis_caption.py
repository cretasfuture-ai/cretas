from copy import deepcopy

from smartbi.services.factory_charts.engine import _card_layout


def test_y_axis_captions_keep_units_and_explicit_alignment_inside_card_edges():
    names = ['生产成本结构（元）', '累计百分比', '自定义轴']
    option = {'xAxis': {'type': 'category', 'data': ['材料', '人工']},
              'yAxis': [{'type': 'value', 'name': names[0]},
                        {'type': 'value', 'name': names[1], 'max': 100},
                        {'type': 'value', 'name': names[2], 'nameTextStyle': {'align': 'center'}}],
              'series': [{'type': 'bar', 'data': [28, 26]}]}
    original = deepcopy(option)
    _card_layout(option)
    assert [axis['name'] for axis in option['yAxis']] == names
    assert option['series'] == original['series']
    assert option['yAxis'][1]['max'] == 100
    assert [a['nameTextStyle']['align'] for a in option['yAxis']] == ['left', 'right', 'center']


def test_middle_and_nonzero_rotated_captions_keep_their_full_axis_configuration():
    axes = [
        {'type': 'value', 'name': '中间标题', 'nameLocation': 'middle',
         'axisLabel': {'hideOverlap': True}, 'splitNumber': 3, 'nameTextStyle': {'color': '#333'}},
        {'type': 'value', 'name': '旋转标题', 'nameRotate': 45,
         'axisLabel': {'hideOverlap': True}, 'splitNumber': 3, 'nameTextStyle': {'color': '#555'}},
    ]
    original = deepcopy(axes)
    option = {'xAxis': {'type': 'category', 'data': ['A', 'B']}, 'yAxis': axes,
              'series': [{'type': 'bar', 'data': [28, 26]}]}
    _card_layout(option)
    assert option['yAxis'] == original
