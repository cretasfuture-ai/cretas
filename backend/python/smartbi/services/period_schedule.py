"""Bounded Asia/Shanghai night-window generation for offline report catch-up."""
from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from .period_windows import Window, parse_window

SHANGHAI = ZoneInfo("Asia/Shanghai")
UTC = timezone.utc
NIGHTLY_DUE = time(23, 55)


def _month_add(value: date, months: int) -> date:
    index = value.year * 12 + value.month - 1 + months
    return date(index // 12, index % 12 + 1, 1)


def _month_end(value: date) -> date:
    return _month_add(value, 1) - timedelta(days=1)


def _quarter_start(value: date) -> date:
    return date(value.year, ((value.month - 1) // 3) * 3 + 1, 1)


def _year_start(value: date) -> date:
    return date(value.year, 1, 1)


def _due_at(local_date: date) -> datetime:
    return datetime.combine(local_date, NIGHTLY_DUE, tzinfo=SHANGHAI).astimezone(UTC)


def _candidate(key: str, due_at: datetime, phase: str, start: date, end: date, kind: str) -> dict[str, object]:
    period: Window = parse_window(start.isoformat(), end.isoformat(), kind=kind, basis="completed", timezone="Asia/Shanghai")
    return {"key": key, "dueAt": due_at.isoformat().replace("+00:00", "Z"), "phase": phase, "period": period.wire()}


def due_periods(now_utc: datetime | None = None, lookback_days: int = 2, preview: bool = True) -> list[dict[str, object]]:
    """Return nightly preview/final windows in a bounded UTC lookback.

    The current local day is intentionally previewed at 23:55, while the
    previous day is finalized at the same nightly tick.  Longer periods follow
    the same rule: preview on their last local day, final on the first day of
    the next period.  All windows remain full date ranges; no end timestamp is
    shortened to the 23:55 trigger.
    """
    if lookback_days < 0:
        raise ValueError("lookback_days must be non-negative")
    now = now_utc or datetime.now(UTC)
    if now.tzinfo is None:
        now = now.replace(tzinfo=UTC)
    now = now.astimezone(UTC)
    lower = now - timedelta(days=lookback_days)
    local_today = now.astimezone(SHANGHAI).date()
    local_start = local_today - timedelta(days=lookback_days + 10)
    local_end = local_today + timedelta(days=10)
    results: dict[str, dict[str, object]] = {}

    def add(key: str, due: datetime, phase: str, start: date, end: date, kind: str) -> None:
        if phase == "preview" and not preview:
            return
        if lower <= due <= now:
            results[key] = _candidate(key, due, phase, start, end, kind)

    day = local_start
    while day <= local_end:
        due = _due_at(day)
        add(f"day:{day.isoformat()}:preview", due, "preview", day, day, "day")
        add(f"day:{day.isoformat()}:final", due, "final", day - timedelta(days=1), day - timedelta(days=1), "day")
        day += timedelta(days=1)

    week = local_start - timedelta(days=local_start.weekday())
    while week <= local_end:
        week_end = week + timedelta(days=6)
        add(f"week:{week.isoformat()}:preview", _due_at(week_end), "preview", week, week_end, "week")
        add(f"week:{week.isoformat()}:final", _due_at(week), "final", week - timedelta(days=7), week - timedelta(days=1), "week")
        week += timedelta(days=7)

    month = _month_add(local_start, -2)
    month_limit = _month_add(local_end, 2)
    while month <= month_limit:
        end = _month_end(month)
        add(f"month:{month.isoformat()}:preview", _due_at(end), "preview", month, end, "month")
        previous = _month_add(month, -1)
        add(f"month:{month.isoformat()}:final", _due_at(month), "final", previous, _month_end(previous), "month")
        month = _month_add(month, 1)

    quarter = _quarter_start(_month_add(local_start, -3))
    quarter_limit = _quarter_start(_month_add(local_end, 3))
    while quarter <= quarter_limit:
        end = _month_end(_month_add(quarter, 2))
        add(f"quarter:{quarter.isoformat()}:preview", _due_at(end), "preview", quarter, end, "quarter")
        previous = _month_add(quarter, -3)
        add(f"quarter:{quarter.isoformat()}:final", _due_at(quarter), "final", previous, _month_end(_month_add(previous, 2)), "quarter")
        quarter = _month_add(quarter, 3)

    year = _year_start(local_start - timedelta(days=366))
    year_limit = _year_start(local_end + timedelta(days=366))
    while year <= year_limit:
        end = date(year.year, 12, 31)
        add(f"year:{year.year}:preview", _due_at(end), "preview", year, end, "year")
        previous = date(year.year - 1, 1, 1)
        add(f"year:{year.year}:final", _due_at(year), "final", previous, date(year.year - 1, 12, 31), "year")
        year = date(year.year + 1, 1, 1)

    return sorted(results.values(), key=lambda item: (str(item["dueAt"]), str(item["key"])))
