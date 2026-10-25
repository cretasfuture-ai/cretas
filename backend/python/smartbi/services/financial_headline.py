"""财务看板页首「一句话结论」— 按规则生成, 不经过 LLM。

老板打开看板先看到的那一句: 这期好不好、差在哪。数字全部来自指标库 (与图表同一口径),
措辞只用表里能算出来的事实, 不推测原因。

优先级: 预算达成 > 同比 > 损益; 费用表 (工作表名或科目判定为费用) 单独一套措辞 (超支为不利);
比率表 (各月直接给毛利率) 说各月平均与上年差几个百分点。
返回 {"text", "tone", "facts"}; tone 为 good / warn / bad / neutral。没有可说的事实时返回 None。
"""
from typing import Any, Dict, List, Optional, Sequence, Tuple

import pandas as pd

from . import financial_subjects as S
from .financial import metrics as M
from .financial.base import format_amount
from .financial_findings import ratio_months  # 比率表与结论区同一取数口径

MONTHS = list(range(1, 13))


def _pct(v: float) -> str:
    return f"{v:.1f}%"


def _signed_pct(v: float) -> str:
    return f"{v:+.1f}%"


def _achievement_word(rate: float, expense: bool) -> Tuple[str, str]:
    """(状态词, tone)。费用超预算为不利, 收入未达为不利。"""
    if expense:
        if rate > 100:
            return "超支", "bad"
        if rate >= 90:
            return "接近预算", "warn"
        return "在预算内", "good"
    if rate >= 100:
        return "达成", "good"
    if rate >= 80:
        return "接近", "warn"
    return "未达", "bad"


def _extremes(labels: Sequence[str], rates: Sequence[Optional[float]]) -> Optional[Tuple[str, float, str, float]]:
    rated = [(lbl, r) for lbl, r in zip(labels, rates) if r is not None]
    if len(rated) < 2:
        return None
    worst = min(rated, key=lambda x: x[1])
    best = max(rated, key=lambda x: x[1])
    return worst[0], worst[1], best[0], best[1]


def _budget_sentence(rows: pd.DataFrame, expense: bool) -> Optional[Dict[str, Any]]:
    actual = M.total(rows['actual'].tolist())
    budget = M.total(rows['budget'].tolist())
    rate = M.ratio(actual, budget).value
    if actual is None or budget is None or rate is None:
        return None
    word, tone = _achievement_word(rate, expense)
    noun = "费用" if expense else "实际"
    target_noun = "预算" if expense else "目标"
    parts = [f"{noun} {format_amount(actual)} / {target_noun} {format_amount(budget)}，达成 {_pct(rate)}（{word}）"]

    # 最弱 / 最好的季度与月份: 只在有逐月数据时给出
    monthly_actual = M.monthly(rows, 'actual', MONTHS)
    monthly_budget = M.monthly(rows, 'budget', MONTHS)
    q_labels, q_rates = [], []
    for q in range(4):
        q_months = range(q * 3, q * 3 + 3)
        q_a = M.total(monthly_actual[i] for i in q_months)
        q_b = M.total(monthly_budget[i] for i in q_months)
        if q_a is not None and q_b is not None:
            q_labels.append(f"Q{q + 1}")
            q_rates.append(M.ratio(q_a, q_b).value)
    m_labels = [f"{m}月" for m in MONTHS]
    m_rates = [M.ratio(a, b).value for a, b in zip(monthly_actual, monthly_budget)]

    if not expense:
        q_ext = _extremes(q_labels, q_rates)
        m_ext = _extremes(m_labels, m_rates)
        if q_ext:
            parts.append(f"最弱 {q_ext[0]} {_pct(q_ext[1])}")
        if m_ext:
            parts.append(f"最好 {m_ext[2]} {_pct(m_ext[3])}")
    else:
        m_ext = _extremes(m_labels, m_rates)
        if m_ext and m_ext[3] > 100:
            parts.append(f"超支最多 {m_ext[2]} {_pct(m_ext[3])}")
    return {"text": "；".join(parts), "tone": tone, "rate": rate}


def _yoy_sentence(rows: pd.DataFrame, expense: bool) -> Optional[Dict[str, Any]]:
    current = M.total(rows['actual'].tolist())
    last = M.total(rows['last_year'].tolist())
    growth = M.growth(current, last).value
    if current is None or last is None or growth is None:
        return None
    noun = "费用" if expense else "本年累计"
    if expense:
        tone = "bad" if growth > 0 else "good"
    else:
        tone = "good" if growth >= 0 else "bad"
    return {"text": f"{noun} {format_amount(current)}，上年 {format_amount(last)}，同比 {_signed_pct(growth)}",
            "tone": tone}


def _pnl_sentence(df: pd.DataFrame) -> Optional[Dict[str, Any]]:
    pnl = M.pnl(df)
    if pnl.revenue is None or pnl.cost is None or pnl.net_profit is None:
        return None
    margin = pnl.net_margin().value
    tone = "good" if pnl.net_profit > 0 else "bad"
    text = f"营业收入 {format_amount(pnl.revenue)}，净利润 {format_amount(pnl.net_profit)}"
    if margin is not None:
        text += f"，净利率 {_pct(margin)}"
    return {"text": text, "tone": tone}


def _ratio_sentence(df: pd.DataFrame) -> Optional[Dict[str, Any]]:
    """比率表 (各月毛利率): 各月简单平均与上年对比 —— 与毛利率趋势图「月均毛利率」同一口径。"""
    series = ratio_months(df)
    if series is None:
        return None
    cur = [v for v in series["current"] if v is not None]
    last = [v for v in series["last"] if v is not None]
    if not cur:
        return None
    mean_cur = sum(cur) / len(cur)
    if not last:
        return {"text": f"各月平均毛利率 {_pct(mean_cur)}（{len(cur)} 个月简单平均）", "tone": "neutral"}
    mean_last = sum(last) / len(last)
    diff = mean_cur - mean_last
    tone = "good" if diff >= 0 else "bad"
    return {"text": f"各月平均毛利率 {_pct(mean_cur)}，上年 {_pct(mean_last)}，{diff:+.1f} 个百分点（各月简单平均）",
            "tone": tone}


def build_headline(df: pd.DataFrame, value_kind: str, period_label: str) -> Optional[Dict[str, Any]]:
    """df 为已按期间过滤、只含明细行的标准明细。"""
    if df is None or df.empty:
        return None
    if value_kind == 'ratio':
        fact = _ratio_sentence(df)
        if fact is None:
            return None
        return {"text": f"{period_label}：{fact['text']}。", "tone": fact["tone"], "facts": [fact["text"]]}

    facts: List[Dict[str, Any]] = []
    expense_rows = M.select(df, S.EXPENSE_CLASSES) if M.has_subjects(df) else df.iloc[0:0]
    only_expense = not expense_rows.empty and M.select(df, [S.REVENUE]).empty

    if only_expense:
        for build in (_budget_sentence, _yoy_sentence):
            fact = build(expense_rows, True)
            if fact:
                facts.append(fact)
    else:
        perf = M.performance_view(df)
        if not perf.empty:
            fact = _budget_sentence(perf, False) or _yoy_sentence(perf, False)
            if fact:
                facts.append(fact)
        if M.has_subjects(df):
            fact = _pnl_sentence(df)
            if fact:
                facts.append(fact)

    if not facts:
        return None
    tone = facts[0]["tone"]
    text = f"{period_label}：" + "。".join(f["text"] for f in facts) + "。"
    return {"text": text, "tone": tone, "facts": [f["text"] for f in facts]}
