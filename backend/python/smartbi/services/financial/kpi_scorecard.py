"""KPI Scorecard Chart Builder — 关键绩效指标记分卡."""
import logging
from typing import Dict, Any
import pandas as pd

from .base import (
    AbstractFinancialChartBuilder, COLORS,
    _sanitize_for_json, _detect_value_scale,
)
from . import metrics as M

logger = logging.getLogger(__name__)


class KpiScorecardBuilder(AbstractFinancialChartBuilder):
    """KPI Scorecard — 汇总关键财务指标卡片 (收入/毛利率/净利率/预算达成/同比增长).

    Produces a special chart type with no ECharts option — the frontend renders
    it as a row of KPI cards with trend indicators and sparklines.
    """

    chart_type = "kpi_scorecard"
    display_name = "关键指标记分卡"
    description = "汇总展示核心财务KPI：营收、毛利率、净利率、预算达成率、同比增长率等"
    required_columns = ['actual']  # only needs actual values
    display_order = 0  # always first

    def build(self, df: pd.DataFrame, column_mapping, period: Dict, year: int = 2026) -> Dict[str, Any]:
        start_month = period.get('start_month', 1)
        end_month = period.get('end_month', 12)

        months = list(range(start_month, end_month + 1))
        # 损益表 (有科目): 收入 / 成本 / 毛利 / 净利由指标库按科目计算;
        # 普通业绩表 (没有科目): 整表就是业绩, 不出毛利率 / 净利率 / 成本率 (算不出来, 不显示 0)
        has_subjects = M.has_subjects(df)
        perf, _ = M.performance_rows(df)
        pnl = M.pnl(df) if has_subjects else None
        revenue = pnl.revenue if pnl else None
        if has_subjects and revenue is None:
            return self._insufficient(period, year, M.NO_REVENUE_MESSAGE)
        cost = pnl.cost if pnl else None
        gross_profit = pnl.gross_profit if pnl else None
        net_profit = pnl.net_profit if pnl else None
        gross_margin = pnl.gross_margin().value if pnl else None
        net_margin = pnl.net_margin().value if pnl else None

        # 预算达成 / 同比只按业绩口径 (收入行) 计算, 不把成本费用一起加进去
        monthly_revenue = M.monthly(perf, 'actual', months)
        monthly_budget = M.monthly(perf, 'budget', months)
        monthly_ly = M.monthly(perf, 'last_year', months)
        # 合计直接按明细求和: 没有月份的年度数据 (月份为 0) 也要算进去
        total_actual = M.total(perf['actual'].tolist()) if not perf.empty else None
        total_budget = M.total(perf['budget'].tolist()) if not perf.empty else None
        total_ly = M.total(perf['last_year'].tolist()) if not perf.empty else None
        budget_achievement = M.ratio(total_actual, total_budget).value
        yoy_growth = M.growth(total_actual, total_ly).value
        has_budget = total_budget is not None
        has_ly = total_ly is not None

        scale = _detect_value_scale([v for v in (revenue, total_actual, total_ly) if v is not None])

        def scaled(series):
            return [None if v is None else round(v / scale['divisor'], 2) for v in series]

        monthly_actual_sparkline = scaled(monthly_revenue)
        monthly_budget_sparkline = scaled(monthly_budget)
        monthly_ly_sparkline = scaled(monthly_ly)

        # Build KPI cards
        cards = []

        # 1. Revenue
        cards.append({
            "label": "营业收入" if has_subjects else "实际总额",
            "value": self._format_value(revenue if has_subjects else total_actual, scale),
            "unit": "元",
            "trend": self._trend_from_value(yoy_growth),
            "subtitle": f"同比 {yoy_growth:+.1f}%" if yoy_growth is not None else None,
            "sparkline": monthly_actual_sparkline[:12] if monthly_actual_sparkline else None,
        })

        # 2. Gross Margin (only if we have P&L breakdown)
        if gross_margin is not None:
            cards.append({
                "label": "毛利率",
                "value": f"{gross_margin:.1f}",
                "unit": "%",
                "trend": "up" if gross_margin > 25 else ("down" if gross_margin < 15 else "flat"),
                "subtitle": f"毛利 {self._format_value(gross_profit, scale)}元",
            })

        # 3. Net Margin
        if net_margin is not None:
            cards.append({
                "label": "净利率",
                "value": f"{net_margin:.1f}",
                "unit": "%",
                "trend": self._trend_from_value(net_profit),
                "subtitle": f"净利润 {self._format_value(net_profit, scale)}元",
            })

        # 4. Budget Achievement
        if has_budget and budget_achievement is not None:
            # Monthly achievement rates for sparkline (算不出的月份为空)
            monthly_ach_sparkline = [M.ratio(a, b).value for a, b in zip(monthly_revenue, monthly_budget)]
            cards.append({
                "label": "预算达成率",
                "value": f"{budget_achievement:.1f}" if budget_achievement is not None else "-",
                "unit": "%",
                "trend": "up" if (budget_achievement or 0) >= 100 else "down",
                "subtitle": f"预算 {self._format_value(total_budget, scale)}元",
                "sparkline": monthly_ach_sparkline if monthly_ach_sparkline else None,
            })

        # 5. YoY Growth
        if has_ly and yoy_growth is not None:
            cards.append({
                "label": "同比增长",
                "value": f"{yoy_growth:+.1f}" if yoy_growth is not None else "-",
                "unit": "%",
                "trend": self._trend_from_value(yoy_growth),
                "subtitle": f"上年 {self._format_value(total_ly, scale)}元",
                "sparkline": monthly_ly_sparkline if monthly_ly_sparkline else None,
            })

        # 6. Cost ratio
        if has_subjects and revenue and cost is not None:
            cost_ratio = round(cost / revenue * 100, 1)
            cards.append({
                "label": "成本率",
                "value": f"{cost_ratio:.1f}",
                "unit": "%",
                "trend": "down" if cost_ratio < 70 else "up",  # lower is better
                "subtitle": f"成本 {self._format_value(cost, scale)}元",
            })

        # Build a simple gauge/indicator ECharts option for visual representation
        option = self._base_echarts_option()
        option.pop("dataZoom", None)
        option.pop("toolbox", None)
        option["tooltip"] = {"show": False}

        # Use a series of gauge charts arranged horizontally
        gauges = []
        gauge_count = min(len(cards), 6)
        for i, card in enumerate(cards[:6]):
            val = 0
            try:
                val = float(card['value'].replace(',', '').replace('+', '').rstrip('%').rstrip('万').rstrip('亿'))
            except (ValueError, AttributeError):
                pass

            # Determine gauge max based on unit
            gauge_max = 100 if card['unit'] == '%' else val * 1.5 if val > 0 else 100
            gauges.append({
                "type": "gauge",
                "center": [f"{(i + 0.5) * 100 / gauge_count:g}%", "55%"],
                "radius": f"{min(40, 75 / gauge_count):g}%",
                "min": 0,
                "max": gauge_max,
                "startAngle": 200,
                "endAngle": -20,
                "splitNumber": 4,
                "progress": {"show": True, "width": 8, "roundCap": True},
                "pointer": {"show": False},
                "axisLine": {"lineStyle": {"width": 8, "color": [[1, "#e8e8e8"]]}},
                "axisTick": {"show": False},
                "splitLine": {"show": False},
                "axisLabel": {"show": False},
                "title": {
                    "fontSize": 11,
                    "color": COLORS['muted'],
                    "offsetCenter": [0, "70%"],
                },
                "detail": {
                    "fontSize": 18,
                    "fontWeight": "bold",
                    "color": COLORS['primary'],
                    "offsetCenter": [0, "30%"],
                    "formatter": f"{card['value']}{card['unit']}",
                },
                "data": [{"value": round(val, 1), "name": card['label']}],
            })

        option["series"] = gauges

        # KPIs for the standard card display
        kpis = [
            {"label": c['label'], "value": c['value'], "unit": c['unit'],
             "trend": c['trend'], "subtitle": c.get('subtitle'),
             **({"sparkline": c['sparkline']} if c.get('sparkline') else {})}
            for c in cards
        ]

        result = {
            "chartType": self.chart_type,
            "title": f"{year}年{self.display_name}",
            "kpis": kpis,
            "echartsOption": option,
            "tableData": None,
            "cards": cards,  # Extended card data with sparklines
            "analysisContext": self.get_analysis_context({"title": f"{year}年{self.display_name}", "kpis": kpis}),
            "metadata": {
                "period": period,
                "scale": scale,
                "dataQuality": "good",
                "revenue": revenue,
                "grossMargin": gross_margin,
                "netMargin": net_margin,
            },
        }
        return _sanitize_for_json(result)
