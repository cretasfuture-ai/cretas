"""Shared request shape for date-scoped facts, reports and follow-up binding."""
from datetime import date
from typing import Literal

from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict


class PeriodSelection(BaseModel):
    model_config = ConfigDict(extra='forbid')
    start_date: date
    end_date: date
    basis: Literal['completed', 'created'] = 'completed'
    kind: Literal['day', 'week', 'month', 'quarter', 'year', 'custom'] = 'custom'

    def window(self):
        return window(self.start_date.isoformat(), self.end_date.isoformat(), self.basis, self.kind)


def window(start_date=None, end_date=None, basis='completed', kind='custom'):
    if start_date is None and end_date is None:
        return None
    if start_date is None or end_date is None:
        raise HTTPException(422, '请同时选择开始和结束日期')
    from smartbi.services.period_windows import parse_window
    try:
        return parse_window(start_date, end_date, basis=basis, kind=kind)
    except (ValueError, TypeError, OverflowError):
        raise HTTPException(422, '日期范围无效：请按北京时间选择有效起止日期，最长十年') from None
