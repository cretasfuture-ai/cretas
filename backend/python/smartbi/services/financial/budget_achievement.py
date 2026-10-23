"""Budget Achievement Chart Builder — 预算完成情况."""
import logging
from typing import Dict, Any
import pandas as pd

from .base import (
    AbstractFinancialChartBuilder, COLORS, _sanitize_for_json, _detect_value_scale,
)
from . import metrics as M

logger = logging.getLogger(__name__)

# Enhanced quarter background colors — much higher opacity for visible separation
# (Steven's Power BI uses 4 separate chart areas; we simulate with bold markArea)
QUARTER_BG_COLORS = [
    'rgba(45,139,87,0.09)',     # Q1 — green tint
    'rgba(54,179,126,0.08)',    # Q2 — teal tint
    'rgba(232,185,49,0.09)',    # Q3 — gold tint
    'rgba(169,209,142,0.08)',   # Q4 — light green tint
]

QUARTER_BORDER_COLORS = [
    'rgba(45,139,87,0.25)',     # Q1
    'rgba(54,179,126,0.22)',    # Q2
    'rgba(232,185,49,0.25)',    # Q3
    'rgba(169,209,142,0.22)',   # Q4
]

QUARTER_LABELS = ['1季度', '2季度', '3季度', '4季度']


class BudgetAchievementBuilder(AbstractFinancialChartBuilder):
    """Grouped bar (budget vs actual) + achievement rate line on secondary axis."""

    chart_type = "budget_achievement"
    display_name = "预算完成情况"
    description = "展示各月预算与实际完成情况对比，含达成率趋势线"
    required_columns = ['budget', 'actual', 'period']
    display_order = 1

    def build(self, df: pd.DataFrame, column_mapping, period: Dict, year: int = 2026) -> Dict[str, Any]:
        start_month = period.get('start_month', 1)
        end_month = period.get('end_month', 12)
        labels = self._month_labels(start_month, end_month)

        months = list(range(start_month, end_month + 1))
        # 业绩口径: 损益表只取收入科目, 不把成本费用一起加进「实际」
        source = M.performance_view(df)
        if source.empty:
            return {
                "chartType": self.chart_type,
                "title": f"{year}年{self.display_name}",
                "kpis": [],
                "echartsOption": {},
                "tableData": None,
                "analysisContext": M.NO_REVENUE_MESSAGE,
                "metadata": {"period": period, "dataQuality": "insufficient", "reason": M.NO_REVENUE_MESSAGE},
            }

        # 按月汇总; 没上传的月份为空, 达成率随之为空 (不显示成 0%)
        budget_vals = M.monthly(source, 'budget', months)
        actual_vals = M.monthly(source, 'actual', months)
        achievement_rates = [M.ratio(a, b).value for a, b in zip(actual_vals, budget_vals)]

        # Cumulative achievement
        cum_budget = M.cumulative(budget_vals)
        cum_actual = M.cumulative(actual_vals)
        cum_achievement = [M.ratio(a, b).value for a, b in zip(cum_actual, cum_budget)]

        # KPIs — first 3 are primary (larger in Vue), 4th is secondary
        total_budget = M.total(budget_vals)
        total_actual = M.total(actual_vals)
        total_rate = M.ratio(total_actual, total_budget).value
        scale = _detect_value_scale([v for v in budget_vals + actual_vals if v is not None])
        divisor = scale['divisor']

        def scaled(v):
            return None if v is None else round(v / divisor, 2)

        def r1(v):
            return None if v is None else round(v, 1)

        # Best month
        rated = [(i, r) for i, r in enumerate(achievement_rates) if r is not None]
        if rated:
            best_month_idx, best_month_rate = max(rated, key=lambda x: x[1])
            best_month_value = f"{labels[best_month_idx]} ({best_month_rate:.1f}%)"
        else:
            best_month_value = '-'

        kpis = [
            {"label": "年度目标", "value": self._format_value(total_budget, scale), "unit": "元", "trend": "flat",
             "primary": True,
             "sparkline": [scaled(v) for v in budget_vals]},
            {"label": "年度实际", "value": self._format_value(total_actual, scale), "unit": "元",
             "trend": self._trend_from_value((total_actual or 0) - (total_budget or 0)),
             "primary": True,
             "sparkline": [scaled(v) for v in actual_vals]},
            {"label": "年度达成率", "value": f"{total_rate:.1f}" if total_rate is not None else '-', "unit": "%",
             "trend": 'up' if total_rate and total_rate >= 100 else 'down',
             "primary": True,
             "sparkline": [r1(r) for r in achievement_rates]},
            {"label": "最佳月份", "value": best_month_value, "unit": "", "trend": "up",
             "primary": False},
        ]

        # Scale values for display
        budget_scaled = [scaled(v) for v in budget_vals]
        actual_scaled = [scaled(v) for v in actual_vals]
        cum_budget_scaled = [scaled(v) for v in cum_budget]
        cum_actual_scaled = [scaled(v) for v in cum_actual]

        def quarter_totals(q: int):
            q_months = [m for m in range(q * 3 + 1, q * 3 + 4) if start_month <= m <= end_month]
            q_actual = M.total(actual_vals[m - start_month] for m in q_months)
            q_budget = M.total(budget_vals[m - start_month] for m in q_months)
            return q_actual, q_budget

        # ECharts option
        option = self._base_echarts_option()
        option.update({
            "grid": {"left": "3%", "right": "5%", "bottom": "10%", "top": "18%", "containLabel": True},
            "legend": {
                "data": [f"预算{scale['name_suffix']}", f"实际{scale['name_suffix']}", "达成率"],
                "top": "2%",
                "textStyle": {"fontSize": 11},
            },
            "xAxis": {
                "type": "category",
                "data": labels,
                "axisLabel": {"fontSize": 11},
            },
            "yAxis": [
                {
                    "type": "value",
                    "name": f"金额{scale['name_suffix']}",
                    "nameTextStyle": {"fontSize": 11},
                    "axisLabel": {"fontSize": 10},
                    "splitLine": {"lineStyle": {"type": "dashed", "color": "#e8e8e8"}},
                },
                {
                    "type": "value",
                    "name": "达成率 (%)",
                    "nameTextStyle": {"fontSize": 11},
                    "axisLabel": {"formatter": "{value}%", "fontSize": 10},
                    "min": 0,
                    "splitLine": {"show": False},
                },
            ],
            "series": [
                {
                    "name": f"预算{scale['name_suffix']}",
                    "type": "bar",
                    "data": budget_scaled,
                    "itemStyle": {"color": COLORS['budget'], "borderRadius": [2, 2, 0, 0]},
                    "barGap": "10%",
                    "barMaxWidth": 28,
                    # 柱顶数值默认不显示 (与达成率标签、季度标注叠在一起读不清); 悬停与数据表里看数值
                    "label": {"show": False},
                    "emphasis": {"itemStyle": {"shadowBlur": 10, "shadowColor": "rgba(0,0,0,0.2)"}},
                },
                {
                    "name": f"实际{scale['name_suffix']}",
                    "type": "bar",
                    "data": actual_scaled,
                    "itemStyle": {"color": COLORS['actual'], "borderRadius": [2, 2, 0, 0]},
                    "barMaxWidth": 28,
                    "label": {"show": False},
                    "emphasis": {"itemStyle": {"shadowBlur": 10, "shadowColor": "rgba(0,0,0,0.2)"}},
                },
                {
                    "name": "达成率",
                    "type": "line",
                    "yAxisIndex": 1,
                    "data": [r1(r) for r in achievement_rates],
                    "itemStyle": {"color": COLORS['achievement']},
                    "lineStyle": {"width": 2, "type": "solid"},
                    "symbol": "circle",
                    "symbolSize": 6,
                    "label": {
                        "show": True,
                        "formatter": "{c}%",
                        "fontSize": 10,
                        "color": COLORS['achievement'],
                        "position": "top",
                    },
                    "markLine": {
                        "silent": True,
                        "data": [{"yAxis": 100, "label": {"formatter": "目标线", "fontSize": 10}}],
                        "lineStyle": {"type": "dashed", "color": COLORS['target_line'], "width": 2},
                    },
                },
            ],
        })
        # 一年最多 12 个月, 不需要缩放条; 累计达成率只留在数据表里 (KPI 已给出整体达成率)

        # 季度底色只标「几季度」: 季度达成率由图上方的季度条显示, 这里不再重复
        mark_areas = self._enhanced_quarter_mark_areas(start_month, end_month)
        if mark_areas:
            option["series"][0]["markArea"] = {"silent": True, "data": mark_areas}


        # === Quarterly progress data (for Vue rendering) ===
        quarterly_progress = []
        for q in range(4):
            q_start_m = q * 3 + 1
            q_end_m = q * 3 + 3
            if q_start_m < start_month or q_end_m > end_month:
                continue
            q_actual, q_budget = quarter_totals(q)
            q_rate = M.ratio(q_actual, q_budget).value
            if q_actual is None or q_budget is None:
                continue
            quarterly_progress.append({
                "quarter": f"Q{q + 1}",
                "budget": round(q_budget / divisor, 2),
                "actual": round(q_actual / divisor, 2),
                "rate": r1(q_rate),
            })

        # Table data
        table_data = {
            "headers": ["月份"] + labels,
            "rows": [
                {"label": "预算", "values": budget_scaled},
                {"label": "实际", "values": actual_scaled},
                {"label": "达成率(%)", "values": [r1(r) for r in achievement_rates]},
                {"label": "累计预算", "values": cum_budget_scaled},
                {"label": "累计实际", "values": cum_actual_scaled},
                {"label": "累计达成率(%)", "values": [r1(r) for r in cum_achievement]},
            ],
        }

        result = {
            "chartType": self.chart_type,
            "title": f"{year}年{self.display_name}",
            "kpis": kpis,
            "echartsOption": option,
            "tableData": table_data,
            "analysisContext": self.get_analysis_context({"title": f"{year}年{self.display_name}", "kpis": kpis}),
            "quarterlyProgress": quarterly_progress,
            "metadata": {"period": period, "scale": scale, "dataQuality": "good"},
        }
        return _sanitize_for_json(result)

    def _enhanced_quarter_mark_areas(self, start_month: int, end_month: int) -> list:
        """Build highly visible quarterly markArea backgrounds with labels.

        Compared to base _quarter_mark_areas():
        - Opacity 0.08-0.10 (was 0.03-0.04)
        - Visible border on each area
        - Quarter label at top: "1季度"
        """
        areas = []
        for q in range(4):
            q_start = q * 3       # 0-indexed month position
            q_end = q * 3 + 2
            if q_start > end_month - 1 or q_end < start_month - 1:
                continue
            actual_start = max(q_start, start_month - 1) - (start_month - 1)
            actual_end = min(q_end, end_month - 1) - (start_month - 1)
            areas.append([
                {
                    "xAxis": actual_start - 0.5,
                    "itemStyle": {
                        "color": QUARTER_BG_COLORS[q],
                        "borderWidth": 1,
                        "borderColor": QUARTER_BORDER_COLORS[q],
                        "borderType": "dashed",
                    },
                    "label": {
                        "show": True,
                        "position": "insideTop",
                        "formatter": QUARTER_LABELS[q],
                        "fontSize": 11,
                        "fontWeight": "bold",
                        "color": "rgba(0,0,0,0.45)",
                        "padding": [4, 0, 0, 0],
                    },
                },
                {"xAxis": actual_end + 0.5},
            ])
        return areas

    @staticmethod
    def _achievement_label_formatter():
        """Return JS-style formatter string for achievement rate labels on bars."""
        # ECharts rich text formatter — shows achievement rate below bar value
        return "{c}"
