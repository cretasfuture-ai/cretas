"""Expense YoY & Budget Achievement Chart Builder — 费用同比及预算达成."""
import logging
from typing import Dict, Any, List, Optional
import pandas as pd

from .base import (
    AbstractFinancialChartBuilder, COLORS, _sanitize_for_json, _detect_value_scale,
)
from . import metrics as M
from .. import financial_subjects as S

logger = logging.getLogger(__name__)


def _scaled(v: Optional[float], divisor: float) -> Optional[float]:
    return None if v is None else round(v / divisor, 2)


def _r1(v: Optional[float]) -> Optional[float]:
    return None if v is None else round(v, 1)


class ExpenseYoyBudgetBuilder(AbstractFinancialChartBuilder):
    """Dual axis: bars (last year vs current) + achievement rate line."""

    chart_type = "expense_yoy_budget"
    display_name = "费用同比及预算达成"
    description = "展示费用的同比变化趋势及预算达成率，双轴对比分析"
    required_columns = ['budget', 'actual', 'last_year', 'period']
    display_order = 4

    def build(self, df: pd.DataFrame, column_mapping, period: Dict, year: int = 2026) -> Dict[str, Any]:
        start_month = period.get('start_month', 1)
        end_month = period.get('end_month', 12)
        months = list(range(start_month, end_month + 1))
        labels = self._month_labels(start_month, end_month)

        # 有科目时只算费用科目 (销售/管理/研发/财务/其他费用); 没有科目列的表默认整表就是费用
        if M.has_subjects(df):
            source = M.select(df, S.EXPENSE_CLASSES)
            if source.empty:
                reason = "表内没有费用科目（销售费用、管理费用、研发费用、财务费用等），无法做费用分析"
                return {
                    "chartType": self.chart_type,
                    "title": f"{year}年{self.display_name}",
                    "kpis": [],
                    "echartsOption": {},
                    "tableData": None,
                    "analysisContext": reason,
                    "metadata": {"period": period, "dataQuality": "insufficient", "reason": reason},
                }
        else:
            source = df

        budget_vals = M.monthly(source, 'budget', months)
        actual_vals = M.monthly(source, 'actual', months)
        last_year_vals = M.monthly(source, 'last_year', months)

        # 缺失月份保持为空: 达成率 / 同比都算不出, 不显示成 0
        achievement_rates = [M.ratio(a, b).value for a, b in zip(actual_vals, budget_vals)]
        yoy_rates = [M.growth(a, ly).value for a, ly in zip(actual_vals, last_year_vals)]

        # KPIs
        total_actual = M.total(actual_vals)
        total_last_year = M.total(last_year_vals)
        total_budget = M.total(budget_vals)
        total_yoy_change = (None if total_actual is None or total_last_year is None
                            else total_actual - total_last_year)
        total_yoy_rate = M.growth(total_actual, total_last_year).value
        total_achievement = M.ratio(total_actual, total_budget).value
        scale = _detect_value_scale([v for v in actual_vals + last_year_vals + budget_vals if v is not None])
        divisor = scale['divisor']

        kpis = [
            {"label": "本年费用合计", "value": self._format_value(total_actual, scale), "unit": "元",
             "trend": self._trend_from_value(total_yoy_change),
             "sparkline": [_scaled(v, divisor) for v in actual_vals]},
            {"label": "上年费用合计", "value": self._format_value(total_last_year, scale), "unit": "元", "trend": "flat",
             "sparkline": [_scaled(v, divisor) for v in last_year_vals]},
            {"label": "同比变化", "value": f"{total_yoy_rate:.1f}" if total_yoy_rate is not None else '-',
             "unit": "%", "trend": self._trend_from_value(total_yoy_rate),
             "sparkline": [_r1(r) for r in yoy_rates]},
            {"label": "预算达成率", "value": f"{total_achievement:.1f}" if total_achievement is not None else '-',
             "unit": "%", "trend": 'up' if total_achievement and total_achievement <= 100 else 'down',
             "sparkline": [_r1(r) for r in achievement_rates]},
        ]

        # Scale values
        actual_scaled = [_scaled(v, divisor) for v in actual_vals]
        ly_scaled = [_scaled(v, divisor) for v in last_year_vals]
        budget_scaled = [_scaled(v, divisor) for v in budget_vals]

        def rate_point(r: Optional[float]) -> Any:
            if r is None:
                return None
            within = 0 < r <= 100
            return {
                "value": round(r, 1),
                "symbol": "roundRect" if within else "circle",
                "symbolSize": 8 if within else 7,
                "itemStyle": {"color": COLORS['success'] if within else COLORS['achievement']},
                "label": {
                    "show": True,
                    "position": "top",
                    "formatter": f"✓{round(r, 1)}%" if within else f"✓*{round(r, 1)}%",
                    "fontSize": 11,
                    "color": COLORS['success'] if within else COLORS['achievement'],
                },
            }

        def actual_bar(i: int, v: Optional[float]) -> Any:
            if v is None:
                return None
            ly = ly_scaled[i]
            over = ly is not None and v > ly
            return {
                "value": v,
                "itemStyle": {
                    "color": self._gradient_color(COLORS['accent'] if over else COLORS['success']),
                    "borderRadius": [2, 2, 0, 0],
                },
            }

        # ECharts option
        option = self._base_echarts_option()
        option.update({
            "legend": {
                "data": [
                    f"上年费用{scale['name_suffix']}",
                    f"本年费用{scale['name_suffix']}",
                    f"预算{scale['name_suffix']}",
                    "预算达成率",
                ],
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
                    "name": f"费用{scale['name_suffix']}",
                    "nameTextStyle": {"fontSize": 11},
                    "axisLabel": {"fontSize": 10},
                    "splitLine": {"lineStyle": {"type": "dashed", "color": "#e8e8e8"}},
                },
                {
                    # 不设上限: 超预算 200%+ 的月份必须画得出来 (客户样图里有 277.73%)
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
                    "name": f"上年费用{scale['name_suffix']}",
                    "type": "bar",
                    "data": ly_scaled,
                    "itemStyle": {"color": self._gradient_color(COLORS['last_year']), "borderRadius": [2, 2, 0, 0]},
                    "barGap": "5%",
                    "barMaxWidth": 24,
                    "emphasis": {"itemStyle": {"shadowBlur": 10, "shadowColor": "rgba(0,0,0,0.2)"}},
                },
                {
                    "name": f"本年费用{scale['name_suffix']}",
                    "type": "bar",
                    "data": [actual_bar(i, v) for i, v in enumerate(actual_scaled)],
                    "barMaxWidth": 24,
                    "emphasis": {"itemStyle": {"shadowBlur": 10, "shadowColor": "rgba(0,0,0,0.2)"}},
                },
                {
                    "name": f"预算{scale['name_suffix']}",
                    "type": "line",
                    "data": budget_scaled,
                    "itemStyle": {"color": COLORS['budget']},
                    "lineStyle": {"width": 1.5, "type": "dashed"},
                    "symbol": "rect",
                    "symbolSize": 5,
                    "label": {"show": False},
                },
                {
                    "name": "预算达成率",
                    "type": "line",
                    "yAxisIndex": 1,
                    "data": [rate_point(r) for r in achievement_rates],
                    "itemStyle": {"color": COLORS['achievement']},
                    "lineStyle": {"width": 2.5},
                    "markLine": {
                        "silent": True,
                        "data": [{"yAxis": 100, "label": {"formatter": "100%", "fontSize": 9}}],
                        "lineStyle": {"type": "dashed", "color": COLORS['target_line'], "width": 1.5},
                    },
                },
            ],
        })
        self._apply_datazoom(option)

        # Quarter markArea
        mark_areas = self._quarter_mark_areas(start_month, end_month)
        if mark_areas:
            option["series"][0]["markArea"] = {"silent": True, "data": mark_areas}

        # YoY change arrows as graphic elements (费用增长为不利: 红色; 下降: 绿色)
        graphic_elements: List[Dict[str, Any]] = []
        for i, (a, ly) in enumerate(zip(actual_scaled, ly_scaled)):
            pct = yoy_rates[i]
            if a is None or ly is None or pct is None:
                continue
            diff = a - ly
            if abs(diff) > 0.01:
                if diff > 0:
                    color = COLORS['yoy_up']
                    text = f"✓*{abs(pct):.0f}%"
                else:
                    color = COLORS['yoy_down']
                    text = f"-{abs(pct):.0f}%"
                graphic_elements.append({
                    "type": "text",
                    "left": f"{7 + i * (84 / max(len(labels), 1))}%",
                    "bottom": "6%",
                    "style": {
                        "text": text,
                        "fontSize": 11,
                        "fill": color,
                        "textAlign": "center",
                        "fontWeight": "bold",
                    },
                    "silent": True,
                })
        if graphic_elements:
            option["graphic"] = graphic_elements

        # Table data
        table_data = {
            "headers": ["月份"] + labels,
            "rows": [
                {"label": "上年费用", "values": ly_scaled},
                {"label": "本年费用", "values": actual_scaled},
                {"label": "预算", "values": budget_scaled},
                {"label": "同比(%)", "values": [_r1(r) for r in yoy_rates]},
                {"label": "达成率(%)", "values": [_r1(r) for r in achievement_rates]},
            ],
        }

        result = {
            "chartType": self.chart_type,
            "title": f"{year}年{self.display_name}",
            "kpis": kpis,
            "echartsOption": option,
            "tableData": table_data,
            "analysisContext": self.get_analysis_context({"title": f"{year}年{self.display_name}", "kpis": kpis}),
            "metadata": {"period": period, "scale": scale, "dataQuality": "good"},
        }
        return _sanitize_for_json(result)
