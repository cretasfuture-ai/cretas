"""指标库 — 财务看板的指标只在这里算一次。

约定:
- 输入是标准明细 (FinancialDataNormalizer.normalize 的输出, 只含 detail 行)。
- 「没有数据」返回 None, 「算不出来」(分母为 0 / 缺分母) 也返回 None 并给出原因;
  ⛔ 不用 0 代替: 0 只代表真实的 0。
- 比率一律按期间总额计算 (Σ分子 / Σ分母), 不对月度比率求平均。
"""
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Sequence

import pandas as pd

from .. import financial_subjects as S

OK = 'ok'
NO_DATA = 'no_data'
NOT_COMPUTABLE = 'not_computable'


@dataclass
class Metric:
    value: Optional[float]
    status: str = OK
    note: str = ''

    @property
    def ok(self) -> bool:
        return self.status == OK and self.value is not None


def total(values: Iterable[Optional[float]]) -> Optional[float]:
    """非空值求和; 全部为空时返回 None (没有数据), 不是 0。"""
    vals = [float(v) for v in values if v is not None and not pd.isna(v)]
    return sum(vals) if vals else None


def ratio(numerator: Optional[float], denominator: Optional[float], scale: float = 100.0) -> Metric:
    if numerator is None or denominator is None:
        return Metric(None, NO_DATA, '缺少数据')
    if denominator == 0:
        return Metric(None, NOT_COMPUTABLE, '分母为 0，无法计算')
    return Metric(round(numerator / denominator * scale, 2))


def growth(current: Optional[float], base: Optional[float]) -> Metric:
    """增长率 (%): 基数缺失 = 无数据; 基数为 0 = 无法计算 (不是 0%)。"""
    if current is None or base is None:
        return Metric(None, NO_DATA, '缺少对比期数据')
    if base == 0:
        return Metric(None, NOT_COMPUTABLE, '对比期为 0，增长率无法计算')
    return Metric(round((current - base) / abs(base) * 100, 2))


def monthly(df: pd.DataFrame, field_name: str, months: Sequence[int]) -> List[Optional[float]]:
    """按月求和; 该月没有任何非空值时为 None。"""
    if df is None or df.empty or field_name not in df.columns:
        return [None for _ in months]
    out: List[Optional[float]] = []
    for m in months:
        vals = df.loc[df['month'] == m, field_name]
        out.append(total(vals.tolist()))
    return out


def month_over_month(series: Sequence[Optional[float]]) -> List[Optional[float]]:
    """环比 (%): 首月、本月缺失、上月缺失或上月为 0 都是 None。"""
    out: List[Optional[float]] = [None]
    for prev, cur in zip(series, series[1:]):
        out.append(growth(cur, prev).value)
    return out


def cumulative(series: Sequence[Optional[float]]) -> List[Optional[float]]:
    """累计值: 缺失月份不打断累计, 但累计到该月仍无任何数据时为 None。"""
    out: List[Optional[float]] = []
    running: Optional[float] = None
    for v in series:
        if v is not None:
            running = (running or 0.0) + v
        out.append(running)
    return out


# ---------------------------------------------------------------------------
# 按科目取数
# ---------------------------------------------------------------------------
def has_subjects(df: pd.DataFrame) -> bool:
    return (df is not None and not df.empty and 'subject_class' in df.columns
            and (df['subject_class'] != S.UNKNOWN).any())


def select(df: pd.DataFrame, classes: Iterable[str]) -> pd.DataFrame:
    classes = tuple(classes)
    if df is None or df.empty or 'subject_class' not in df.columns:
        return df.iloc[0:0] if df is not None else pd.DataFrame()
    return df[df['subject_class'].isin(classes)]


def performance_view(df: pd.DataFrame) -> pd.DataFrame:
    """业绩类图表的取数口径: 表里有科目 (损益表) 时只取收入行; 普通销售表整表就是业绩。

    损益表里没有收入科目时返回空表, 调用方应提示「表内没有收入科目」, ⛔ 不要退回整表求和。
    """
    if has_subjects(df):
        return select(df, [S.REVENUE])
    return df


NO_REVENUE_MESSAGE = "表内有科目但没有收入科目（营业收入 / 主营业务收入），无法计算业绩指标"
NO_PERIOD_DATA_MESSAGE = "所选期间没有数据"


def performance_rows(df: pd.DataFrame):
    """(业绩口径的明细, 为空时给用户看的原因; 不为空时原因为 None)。"""
    view = performance_view(df)
    if view is not None and not view.empty:
        return view, None
    return view, (NO_REVENUE_MESSAGE if has_subjects(df) else NO_PERIOD_DATA_MESSAGE)


def sum_class(df: pd.DataFrame, classes: Iterable[str], field_name: str = 'actual') -> Optional[float]:
    part = select(df, classes)
    if part.empty or field_name not in part.columns:
        return None
    return total(part[field_name].tolist())


@dataclass
class PnL:
    revenue: Optional[float]
    cost: Optional[float]
    expenses: Dict[str, float] = field(default_factory=dict)  # 类别 -> 带符号金额
    surcharge: Optional[float] = None
    other_gain: Optional[float] = None
    non_op_income: Optional[float] = None
    non_op_expense: Optional[float] = None
    income_tax: Optional[float] = None

    @property
    def expense_total(self) -> Optional[float]:
        return total(self.expenses.values()) if self.expenses else None

    @property
    def gross_profit(self) -> Optional[float]:
        # 没有收入就没有毛利; 没有成本科目时按 0 计 (调用方用 missing_lines() 告诉用户)
        if self.revenue is None:
            return None
        return self.revenue - (self.cost or 0)

    def missing_lines(self) -> List[str]:
        return ['营业成本'] if self.revenue is not None and self.cost is None else []

    @property
    def operating_profit(self) -> Optional[float]:
        gp = self.gross_profit
        if gp is None:
            return None
        return (gp - (self.expense_total or 0) - (self.surcharge or 0) + (self.other_gain or 0))

    @property
    def total_profit(self) -> Optional[float]:
        op = self.operating_profit
        if op is None:
            return None
        return op + (self.non_op_income or 0) - (self.non_op_expense or 0)

    @property
    def net_profit(self) -> Optional[float]:
        tp = self.total_profit
        if tp is None:
            return None
        return tp - (self.income_tax or 0)

    def gross_margin(self) -> Metric:
        return ratio(self.gross_profit, self.revenue)

    def net_margin(self) -> Metric:
        return ratio(self.net_profit, self.revenue)

    def derived(self) -> Dict[str, Optional[float]]:
        return {
            S.PROFIT_GROSS: self.gross_profit,
            S.PROFIT_OPERATING: self.operating_profit,
            S.PROFIT_TOTAL: self.total_profit,
            S.PROFIT_NET: self.net_profit,
        }


def pnl(df: pd.DataFrame, field_name: str = 'actual') -> PnL:
    expenses: Dict[str, float] = {}
    for cls in S.EXPENSE_CLASSES:
        v = sum_class(df, [cls], field_name)
        if v is not None:
            expenses[cls] = v
    return PnL(
        revenue=sum_class(df, [S.REVENUE], field_name),
        cost=sum_class(df, [S.COST], field_name),
        expenses=expenses,
        surcharge=sum_class(df, [S.SURCHARGE], field_name),
        other_gain=sum_class(df, [S.OTHER_GAIN], field_name),
        non_op_income=sum_class(df, [S.NON_OP_INCOME], field_name),
        non_op_expense=sum_class(df, [S.NON_OP_EXPENSE], field_name),
        income_tax=sum_class(df, [S.INCOME_TAX], field_name),
    )


def _fmt(v: float) -> str:
    return f"{v:,.2f}".rstrip('0').rstrip('.')


def reconcile(detail_pnl: PnL, reported_rows: Optional[pd.DataFrame],
              field_name: str = 'actual', tolerance: float = 0.0005) -> List[str]:
    """表内已算好的合计 / 利润行与按明细计算的结果对账, 返回给用户看的差异说明。"""
    notes: List[str] = []
    if reported_rows is None or reported_rows.empty or field_name not in reported_rows.columns:
        return notes
    derived = detail_pnl.derived()
    class_totals = {
        S.REVENUE: detail_pnl.revenue, S.COST: detail_pnl.cost,
    }
    for (item, cls, level), grp in reported_rows.groupby(['item', 'subject_class', 'subject_level']):
        reported = total(grp[field_name].tolist())
        if reported is None:
            continue
        if level == S.REPORTED:
            computed = derived.get(cls)
        elif cls in class_totals:
            computed = class_totals[cls]
        else:
            continue
        if computed is None:
            continue
        diff = reported - computed
        if abs(diff) > max(abs(computed) * tolerance, 0.5):
            notes.append(f"{S.strip_subject(item)}：表内 {_fmt(reported)}，按明细计算 {_fmt(computed)}，"
                         f"差额 {_fmt(diff)}")
    return notes
