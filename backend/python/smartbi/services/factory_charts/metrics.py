"""Compute factory facts once; renderers and findings consume the same points."""
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
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


def complete_manufacturing_cost(row) -> bool:
    """Require actual, finite, reconciled cost components; never infer zeros."""
    amounts = []
    for column in ('材料成本', '人工成本', '设备成本', '其他成本', '总成本'):
        value = row.get(column)
        if value is None or isinstance(value, bool):
            return False
        try:
            amount = Decimal(str(value))
        except (InvalidOperation, TypeError, ValueError):
            return False
        if not amount.is_finite() or amount < 0:
            return False
        amounts.append(amount)
    return sum(amounts[:4], Decimal('0')) == amounts[4]


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
            # A recorded total is neutral: larger cost, defects or expiry is not success.
            out['attention'].append(f'{self.title}{label} {display(self.total)}{self.unit}')
        if self.denominator is not None:
            out['attention'].append(f'按分子 {display(self.numerator)} / 分母 {display(self.denominator)} 计算，未平均各组比率')
        out['attention'].append(f'最高为{high[0]} {display(high[1])}{self.unit}；最低为{low[0]} {display(low[1])}{self.unit}')
        # Compare time observations only; comparing two products is not a time trend.
        dated = all(re.fullmatch(r'\d{4}-\d{2}(?:-\d{2})?', label) for label, _ in self.points)
        if dated and len(self.points) >= 2:
            first, last = sorted(self.points)[0], sorted(self.points)[-1]
            change = last[1] - first[1]
            direction = '上升' if change > 0 else '下降' if change < 0 else '持平'
            text = f'{last[0]}较{first[0]}{direction}'
            if change:
                text += f' {display(abs(change))}{"个百分点" if self.unit == "%" else self.unit}'
            lower_better = self.key.startswith(('cost.unit', 'quality.defect', 'process.loss'))
            higher_better = self.key.startswith(('production.quality', 'quality.pass'))
            proven_improvement = (change < 0 and lower_better) or (change > 0 and higher_better)
            out['good' if proven_improvement else 'attention'].append(text + '；仅比较有记录的两期')
        action = ('核查不良记录、检验分母和对应批次' if self.domain == '质量' else
                  '核查成本归集、对应产量和计价单位' if self.domain == '成本' else
                  '核对批次余额、到期日并安排库存处理' if self.domain == '库存' else
                  '对照确认收货明细复核供应商、数量和价格单位' if self.domain == '采购' else
                  '核对订单状态、退货与保存金额' if self.domain == '销售' else
                  '核对批次、报工记录和实际单位')
        out['suggestions'].append(f'{action}；优先回查{high[0]}（{display(high[1])}{self.unit}），不据此推测原因')
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
