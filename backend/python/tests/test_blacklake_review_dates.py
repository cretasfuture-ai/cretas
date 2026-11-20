from blacklake.exhaustive_cost import project_dates


def test_source_dates_are_explicit_and_never_inferred_from_codes_or_cost_dates():
    result = project_dates([
        {'id': 1, 'actualEndTime': '2026-11-03T23:00:00+08:00', 'createTime': '2026-10-30T10:00:00+08:00'},
        {'id': 2, 'projectCode': 'GD202611040001', 'actualEndTime': 'invalid'},
    ])
    assert result['1']['completedAt'] == '2026-11-03T15:00:00+00:00'
    assert result['1']['createdAt'] == '2026-10-30T02:00:00+00:00'
    assert result['2'] == {'completedAt': None, 'createdAt': None}
