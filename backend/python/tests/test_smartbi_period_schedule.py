from datetime import datetime, timezone

from smartbi.services.period_schedule import due_periods


def utc(year, month, day, hour=15, minute=55):
    return datetime(year, month, day, hour, minute, tzinfo=timezone.utc)


def test_nightly_sunday_has_current_preview_and_previous_final_without_morning_due():
    due = due_periods(utc(2026, 11, 8), lookback_days=0)
    keys = {item['key'] for item in due}
    assert 'day:2026-11-08:preview' in keys
    assert 'day:2026-11-08:final' in keys
    assert 'week:2026-11-02:preview' in keys
    assert all('08:00' not in item['dueAt'] for item in due)
    assert all(item['phase'] in {'preview', 'final'} for item in due)


def test_final_only_excludes_preview_and_monday_final_is_previous_week():
    due = due_periods(utc(2026, 11, 9), lookback_days=0, preview=False)
    assert due and all(item['phase'] == 'final' for item in due)
    weekly = next(item for item in due if item['key'].startswith('week:'))
    assert weekly['period']['startDate'] == '2026-11-02'
    assert weekly['period']['endDate'] == '2026-11-08'
    assert weekly['period']['canonicalRange'] is True


def test_month_quarter_year_boundaries_are_full_and_half_open():
    due = due_periods(utc(2026, 12, 31), lookback_days=0)
    by_key = {item['key']: item for item in due}
    assert by_key['month:2026-12-01:preview']['period']['endDate'] == '2026-12-31'
    assert by_key['quarter:2026-10-01:preview']['period']['endDate'] == '2026-12-31'
    assert by_key['year:2026:preview']['period']['endDate'] == '2026-12-31'
    assert by_key['month:2026-12-01:preview']['period']['endAt'] == '2026-12-31T16:00:00Z'
