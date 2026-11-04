"""Pure date windows used by the SmartBI report scheduler.

The UI end date is inclusive.  Storage and report execution use a half-open
UTC interval so that a whole local day is never shortened by a timezone
conversion.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

MAX_WINDOW_DAYS = 3660
WINDOW_KINDS = frozenset({"day", "week", "month", "quarter", "year", "custom"})
UTC = timezone.utc


def _parse_date(value: str, name: str) -> date:
    if not isinstance(value, str) or len(value) != 10:
        raise ValueError(f"{name} must be an ISO date (YYYY-MM-DD)")
    try:
        parsed = date.fromisoformat(value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an ISO date (YYYY-MM-DD)") from exc
    if parsed.isoformat() != value:
        raise ValueError(f"{name} must be an ISO date (YYYY-MM-DD)")
    return parsed


def _zone(name: str) -> ZoneInfo:
    if not isinstance(name, str) or not name:
        raise ValueError("timezone is required")
    try:
        return ZoneInfo(name)
    except Exception as exc:  # ZoneInfoNotFoundError is platform-specific.
        raise ValueError(f"unknown timezone: {name}") from exc


def _next_month(value: date) -> date:
    return date(value.year + (value.month == 12), 1 if value.month == 12 else value.month + 1, 1)


def _is_canonical_range(start: date, end: date) -> bool:
    if start == end:
        return True
    if start.weekday() == 0 and end == start + timedelta(days=6):
        return True
    if start.day == 1 and end == _next_month(start) - timedelta(days=1):
        return True
    if start.month in (1, 4, 7, 10) and start.day == 1:
        quarter_end_month = start.month + 2
        quarter_end = date(start.year, quarter_end_month, 1)
        quarter_end = _next_month(quarter_end) - timedelta(days=1)
        if end == quarter_end:
            return True
    return start.month == 1 and start.day == 1 and end == date(start.year, 12, 31)


def _matches_kind(start: date, end: date, kind: str) -> bool:
    if kind == "day":
        return start == end
    if kind == "week":
        return start.weekday() == 0 and end == start + timedelta(days=6)
    if kind == "month":
        return start.day == 1 and end == _next_month(start) - timedelta(days=1)
    if kind == "quarter":
        return start.month in (1, 4, 7, 10) and start.day == 1 and end == _month_next_quarter_end(start)
    if kind == "year":
        return start.month == 1 and start.day == 1 and end == date(start.year, 12, 31)
    return True


def _month_next_quarter_end(start: date) -> date:
    return _next_month(date(start.year, start.month + 2, 1)) - timedelta(days=1)


@dataclass(frozen=True)
class Window:
    start_date: date
    end_date: date
    kind: str
    basis: str
    timezone: str
    start_at: datetime
    end_at: datetime
    canonical_range: bool

    def wire(self) -> dict[str, object]:
        return {
            "startDate": self.start_date.isoformat(),
            "endDate": self.end_date.isoformat(),
            "kind": self.kind,
            "basis": self.basis,
            "timezone": self.timezone,
            "startAt": self.start_at.isoformat().replace("+00:00", "Z"),
            "endAt": self.end_at.isoformat().replace("+00:00", "Z"),
            "canonicalRange": self.canonical_range,
        }


def parse_window(
    start_date: str,
    end_date: str,
    kind: str = "custom",
    basis: str = "completed",
    timezone: str = "Asia/Shanghai",
) -> Window:
    """Validate and build a UI-inclusive, UTC half-open report window."""
    if kind not in WINDOW_KINDS:
        raise ValueError(f"unsupported window kind: {kind}")
    if not isinstance(basis, str) or not basis.strip():
        raise ValueError("basis is required")
    start = _parse_date(start_date, "start_date")
    end = _parse_date(end_date, "end_date")
    if end < start:
        raise ValueError("end_date must not precede start_date")
    if (end - start).days + 1 > MAX_WINDOW_DAYS:
        raise ValueError(f"window must be no longer than {MAX_WINDOW_DAYS} days")
    if kind != "custom" and not _matches_kind(start, end, kind):
        raise ValueError(f"date range does not match kind: {kind}")
    zone = _zone(timezone)
    start_at = datetime.combine(start, time.min, tzinfo=zone).astimezone(UTC)
    end_at = datetime.combine(end + timedelta(days=1), time.min, tzinfo=zone).astimezone(UTC)
    return Window(start, end, kind, basis.strip(), timezone, start_at, end_at, _is_canonical_range(start, end))
