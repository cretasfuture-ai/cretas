from datetime import date, datetime, timezone

import pytest

from smartbi.services.period_windows import parse_window


def test_window_is_ui_inclusive_and_utc_half_open_for_leap_day():
    window = parse_window('2024-02-28', '2024-02-29', kind='custom')
    assert window.start_date == date(2024, 2, 28)
    assert window.end_date == date(2024, 2, 29)
    assert window.start_at == datetime(2024, 2, 27, 16, tzinfo=timezone.utc)
    assert window.end_at == datetime(2024, 2, 29, 16, tzinfo=timezone.utc)
    assert window.wire()['endDate'] == '2024-02-29'
    assert window.wire()['canonicalRange'] is False


def test_window_recognizes_iso_week_and_full_custom_month():
    cross_year = parse_window('2024-12-30', '2025-01-05', kind='week')
    assert cross_year.wire()['canonicalRange'] is True
    assert parse_window('2024-02-01', '2024-02-29').wire()['canonicalRange'] is True


def test_named_kinds_require_their_canonical_shape_but_custom_can_be_partial():
    with pytest.raises(ValueError):
        parse_window('2024-01-01', '2024-01-01', kind='year')
    with pytest.raises(ValueError):
        parse_window('2024-01-02', '2024-01-31', kind='month')
    assert parse_window('2024-12-30', '2025-01-05', kind='week').kind == 'week'
    assert parse_window('2024-01-02', '2024-01-31', kind='custom').wire()['canonicalRange'] is False


@pytest.mark.parametrize('start,end', [('2024-02-30', '2024-03-01'), ('2024-01-01', '2024-01-01T00:00:00')])
def test_window_requires_strict_iso_dates(start, end):
    with pytest.raises(ValueError):
        parse_window(start, end)


def test_window_rejects_reverse_and_oversized_ranges():
    with pytest.raises(ValueError):
        parse_window('2024-01-02', '2024-01-01')
    with pytest.raises(ValueError):
        parse_window('2010-01-01', '2030-01-01')
