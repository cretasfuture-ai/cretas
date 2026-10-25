"""Compute factory facts once; renderers and findings consume the same points."""
from dataclasses import dataclass
import math
import re
from typing import Optional

import pandas as pd
from smartbi.services.financial import metrics as M


def number(value) -> Optional[float]:
    try:
        v = float(value)
        return v if math.isfinite(v) else None
    except (TypeError, ValueError):
        return None


def display(value: float) -> str:
    return f'{value:,.2f}'.rstrip('0').rstrip('.')


@dataclass
class Series:
    key: str
    title: str
    domain: str
    unit: str
    points: list[tuple[str, float]]
    source: str
    aggregate: str = 'sum'
    numerator: Optional[float] = None
    denominator: Optional[float] = None

    @property
    def total(self):
        if self.aggregate in ('flow', 'nonadditive'):
            return None
        if self.aggregate == 'cohort':
            return self.points[0][1] if self.points else None
        if self.aggregate == 'balance':
            return self.points[-1][1] if self.points else None
        if self.aggregate == 'ratio':
            return M.ratio(self.numerator, self.denominator).value
        if self.aggregate == 'unit_cost':
            return M.ratio(self.numerator, self.denominator, 1).value
        return M.total(v for _, v in self.points)

    @property
    def cumulative(self):
        total = M.total(v for _, v in self.points)
        running = 0.0
        result = []
        for _, value in self.points:
            running += value
            r = M.ratio(running, total).value
            result.append(None if r is None else round(r, 1))
        return result

    def findings(self):
        out = {'good': [], 'attention': [], 'suggestions': []}
        if not self.points:
            return out
        high = max(self.points, key=lambda p: p[1])
        low = min(self.points, key=lambda p: p[1])
        if self.total is not None:
            label = '期末口径' if self.aggregate == 'balance' else '累计口径' if self.aggregate != 'sum' else '合计'
            out['good'].append(f'{self.title}{label} {display(self.total)}{self.unit}')
        if self.denominator is not None:
            out['good'].append(f'按分子 {display(self.numerator)} / 分母 {display(self.denominator)} 计算，未平均各组比率')
        out['attention'].append(f'最高为{high[0]} {display(high[1])}{self.unit}；最低为{low[0]} {display(low[1])}{self.unit}')
        out['suggestions'].append(f'复核{high[0]}对应的{self.title}记录（{display(high[1])}{self.unit}），与来源明细核对')
        return out


def grouped(frame: pd.DataFrame, dimension: str, value: str, *, ratio_denominator: str = '', bounded=True):
    """Missing/non-finite operands invalidate a metric, not turn into zero."""
    columns = [dimension, value] + ([ratio_denominator] if ratio_denominator else [])
    if frame.empty or any(c not in frame for c in columns):
        return None
    if frame[dimension].isna().any():
        return None
    work = frame[columns].copy()
    for col in columns[1:]:
        work[col] = work[col].map(number)
        if work[col].isna().any():
            return None
    groups = work.groupby(dimension, sort=True, dropna=False)[columns[1:]].sum()
    points = []
    for key, row in groups.iterrows():
        if ratio_denominator:
            if row[ratio_denominator] <= 0 or row[value] < 0 or (bounded and row[value] > row[ratio_denominator]):
                return None
            val = M.ratio(row[value], row[ratio_denominator]).value
        else:
            val = float(row[value])
        points.append((str(key), val))
    return sorted(points, key=lambda p: [int(s) if s.isdigit() else s for s in re.split(r'(\d+)', p[0])])
