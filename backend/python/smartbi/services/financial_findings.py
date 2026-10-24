"""财务看板「分析结论与建议」的规则部分: 好的 / 需要关注 / 建议。

客户需求 8、9 (好的与不好的、建议) 原来只能靠 AI 生成。这里先用规则从同一套指标库算出来,
每条都带数字、只说表里能算出来的事实; AI 的段落放在它下面作补充。

覆盖: 预算达成 (全期 / 季度) · 同比 (全期 / 下滑月份) · 费用 (超预算 / 超支月份 / 同比) ·
业务单元 (达成 / 同比) · 品类占比变化 · 损益 (净利率)。比率表 (只有百分比) 不出金额结论。
"""
from typing import Dict, List, Optional

import pandas as pd

from . import financial_subjects as S
from .financial import metrics as M
from .financial.base import format_amount

MONTHS = list(range(1, 13))


class _Findings:
    def __init__(self) -> None:
        self.good: List[str] = []
        self.attention: List[str] = []
        self.suggestions: List[str] = []

    def any(self) -> bool:
        return bool(self.good or self.attention or self.suggestions)


def _pct(v: float) -> str:
    return f"{v:.1f}%"


def _budget(rows: pd.DataFrame, out: _Findings) -> None:
    actual = M.total(rows['actual'].tolist())
    budget = M.total(rows['budget'].tolist()) if 'budget' in rows else None
    rate = M.ratio(actual, budget).value
    if actual is None or budget is None or rate is None:
        return
    gap = budget - actual
    if rate >= 100:
        out.good.append(f"全期达成 {_pct(rate)}（实际 {format_amount(actual)} / 目标 {format_amount(budget)}）")
    else:
        out.attention.append(f"全期只达成 {_pct(rate)}，比目标少 {format_amount(gap)}")

    monthly_actual = M.monthly(rows, 'actual', MONTHS)
    monthly_budget = M.monthly(rows, 'budget', MONTHS)
    quarters = []
    for q in range(4):
        idx = range(q * 3, q * 3 + 3)
        q_a = M.total(monthly_actual[i] for i in idx)
        q_b = M.total(monthly_budget[i] for i in idx)
        q_rate = M.ratio(q_a, q_b).value
        if q_a is not None and q_b is not None and q_rate is not None:
            quarters.append((q + 1, q_rate, q_a, q_b))
    if len(quarters) < 2:
        return
    for n, q_rate, _, _ in quarters:
        if q_rate >= 100:
            out.good.append(f"Q{n} 达成 {_pct(q_rate)}")
    n, q_rate, q_a, q_b = min(quarters, key=lambda q: q[1])
    if q_rate < 80:
        out.attention.append(f"Q{n} 只达成 {_pct(q_rate)}（实际 {format_amount(q_a)} / 目标 {format_amount(q_b)}）")
        if gap > 0:
            share = (q_b - q_a) / gap * 100
            out.suggestions.append(
                f"Q{n} 缺口 {format_amount(q_b - q_a)}，占全期缺口的 {share:.0f}%：先复盘这一季度的目标拆分和出货进度")


def _yoy(rows: pd.DataFrame, out: _Findings, noun: str = '') -> None:
    if 'last_year' not in rows:
        return
    current = M.total(rows['actual'].tolist())
    last = M.total(rows['last_year'].tolist())
    growth = M.growth(current, last).value
    if current is None or last is None or growth is None:
        return
    if growth >= 0:
        out.good.append(f"{noun}同比增长 {_pct(growth)}（本期 {format_amount(current)}，上年 {format_amount(last)}）")
    else:
        out.attention.append(f"{noun}同比下降 {_pct(-growth)}（本期 {format_amount(current)}，上年 {format_amount(last)}）")

    monthly_actual = M.monthly(rows, 'actual', MONTHS)
    monthly_last = M.monthly(rows, 'last_year', MONTHS)
    declines = []
    for m, a, ly in zip(MONTHS, monthly_actual, monthly_last):
        g = M.growth(a, ly).value
        if a is not None and ly is not None and g is not None and a < ly:
            declines.append((g, m, ly - a))
    for g, m, diff in sorted(declines)[:2]:
        out.attention.append(f"{m}月比上年少 {format_amount(diff)}（{g:+.1f}%）")


def _expenses(rows: pd.DataFrame, out: _Findings) -> None:
    actual = M.total(rows['actual'].tolist())
    budget = M.total(rows['budget'].tolist()) if 'budget' in rows else None
    rate = M.ratio(actual, budget).value
    if actual is not None and budget is not None and rate is not None:
        if rate > 100:
            out.attention.append(
                f"费用超预算：实际 {format_amount(actual)} / 预算 {format_amount(budget)}，达成 {_pct(rate)}")
        else:
            out.good.append(f"费用在预算内：实际 {format_amount(actual)} / 预算 {format_amount(budget)}（{_pct(rate)}）")
        monthly_actual = M.monthly(rows, 'actual', MONTHS)
        monthly_budget = M.monthly(rows, 'budget', MONTHS)
        overruns = []
        for m, a, b in zip(MONTHS, monthly_actual, monthly_budget):
            r = M.ratio(a, b).value
            if r is not None and r > 100:
                overruns.append((r, m, a, b))
        for r, m, a, b in sorted(overruns, reverse=True)[:2]:
            out.suggestions.append(f"核查 {m}月费用：预算 {format_amount(b)}、实际 {format_amount(a)}（{_pct(r)}）")

    if 'last_year' in rows:
        last = M.total(rows['last_year'].tolist())
        growth = M.growth(actual, last).value
        if actual is not None and last is not None and growth is not None:
            if growth <= 0:
                out.good.append(f"费用比上年少 {format_amount(last - actual)}（{growth:+.1f}%）")
            else:
                out.attention.append(f"费用比上年多 {format_amount(actual - last)}（{growth:+.1f}%）")


def _units(rows: pd.DataFrame, out: _Findings) -> None:
    if 'business_unit' not in rows:
        return
    named = rows[~rows['business_unit'].fillna('').astype(str).str.strip().isin(['', 'nan'])]
    if named.empty:
        return
    units = []
    for name, g in named.groupby('business_unit', sort=False):
        a = M.total(g['actual'].tolist())
        b = M.total(g['budget'].tolist()) if 'budget' in g else None
        ly = M.total(g['last_year'].tolist()) if 'last_year' in g else None
        units.append((str(name), a, b, M.ratio(a, b).value, ly, M.growth(a, ly).value))
    if len(units) < 2:
        return
    rated = [u for u in units if u[3] is not None]
    if rated:
        best = max(rated, key=lambda u: u[3])
        worst = min(rated, key=lambda u: u[3])
        if best[3] >= 100:
            out.good.append(f"{best[0]}达成 {_pct(best[3])}，业务单元里最好")
        if worst[3] < 100:
            out.attention.append(
                f"{worst[0]}只达成 {_pct(worst[3])}（实际 {format_amount(worst[1])} / 预算 {format_amount(worst[2])}）")
            out.suggestions.append(f"{worst[0]}缺口 {format_amount(worst[2] - worst[1])}：单独复盘这个单元的目标和执行")
    declining = [u for u in units if u[5] is not None and u[5] < 0]
    for name, a, _, _, ly, g in sorted(declining, key=lambda u: u[5])[:1]:
        out.attention.append(f"{name}比上年少 {format_amount(ly - a)}（{g:+.1f}%）")


def _categories(rows: pd.DataFrame, out: _Findings) -> None:
    if 'category' not in rows or 'last_year' not in rows:
        return
    named = rows[~rows['category'].fillna('').astype(str).str.strip().isin(['', 'nan'])]
    if named.empty:
        return
    cur = {str(k): M.total(g['actual'].tolist()) for k, g in named.groupby('category', sort=False)}
    last = {str(k): M.total(g['last_year'].tolist()) for k, g in named.groupby('category', sort=False)}
    cur_total = M.total(cur.values())
    last_total = M.total(last.values())
    if len(cur) < 2 or not cur_total or not last_total:
        return
    shifts = []
    for k, c in cur.items():
        prev = last.get(k)
        if c is None or prev is None:
            continue
        shifts.append((c / cur_total * 100 - prev / last_total * 100, k, c / cur_total * 100))
    if not shifts:
        return
    up = max(shifts)
    down = min(shifts)
    if up[0] >= 1:
        out.good.append(f"{up[1]}占比 {_pct(up[2])}，比上年 +{up[0]:.1f} 个百分点")
    if down[0] <= -1:
        out.attention.append(f"{down[1]}占比 {_pct(down[2])}，比上年 {down[0]:.1f} 个百分点")


def _pnl(df: pd.DataFrame, out: _Findings) -> None:
    pnl = M.pnl(df)
    if pnl.revenue is None or pnl.cost is None or pnl.net_profit is None:
        return
    margin = pnl.net_margin().value
    if pnl.net_profit >= 0:
        tail = f"，净利率 {_pct(margin)}" if margin is not None else ''
        out.good.append(f"净利润 {format_amount(pnl.net_profit)}{tail}")
    else:
        out.attention.append(f"净亏损 {format_amount(-pnl.net_profit)}（营业收入 {format_amount(pnl.revenue)}）")


def build_findings(df: pd.DataFrame, value_kind: str) -> Optional[Dict[str, List[str]]]:
    """df 为已按期间过滤、只含明细行的标准明细。说不出任何一条时返回 None。"""
    if df is None or df.empty or value_kind == 'ratio':
        return None
    out = _Findings()
    expense_rows = M.select(df, S.EXPENSE_CLASSES) if M.has_subjects(df) else df.iloc[0:0]
    only_expense = not expense_rows.empty and M.select(df, [S.REVENUE]).empty
    if only_expense:
        _expenses(expense_rows, out)
    else:
        perf = M.performance_view(df)
        if not perf.empty:
            _budget(perf, out)
            _yoy(perf, out)
            _units(perf, out)
            _categories(perf, out)
        if M.has_subjects(df):
            _pnl(df, out)
    if not out.any():
        return None
    return {"good": out.good, "attention": out.attention, "suggestions": out.suggestions}
