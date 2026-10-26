"""YoY/MoM Comparison Chart Builder — 同比环比情况.

Steven's Power BI design:
- YoY growth shown as SCATTER circle bubbles (red/green) on top of bars
- Star markers for quarterly totals above bar groups
- Prominent red quarterly summary boxes at bottom
- Monthly data rows (本年/上年) as graphic elements inside chart
- 环比增长率是一条带图例的折线 (原来默认「综合」模式下永远不画, 只剩图顶部一排没标签的小字)
"""
import logging
from typing import Dict, Any
import pandas as pd

from .base import (
    AbstractFinancialChartBuilder, COLORS, _sanitize_for_json, _detect_value_scale,
)
from . import metrics as M
from .. import financial_subjects as S

logger = logging.getLogger(__name__)


class YoyMomComparisonBuilder(AbstractFinancialChartBuilder):
    """Grouped bar (current vs last year) + YoY% scatter bubbles."""

    chart_type = "yoy_mom_comparison"
    display_name = "同比环比情况"
    description = "展示本年与上年同期对比，含同比增长率及环比变化趋势"
    required_columns = ['actual', 'last_year', 'period']
    display_order = 2

    def build(self, df: pd.DataFrame, column_mapping, period: Dict, year: int = 2026) -> Dict[str, Any]:
        start_month = period.get('start_month', 1)
        end_month = period.get('end_month', 12)
        labels = self._month_labels(start_month, end_month)

        months = list(range(start_month, end_month + 1))
        # 业绩口径 = 收入: 有科目时只取收入行; 没有科目列的表整表就是业绩
        if M.has_subjects(df):
            source = M.select(df, [S.REVENUE])
            if source.empty:
                reason = "表内没有收入科目，无法做业绩同比环比"
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

        # 按月汇总; 没上传的月份为空 (不是 0), 同比/环比随之为空
        actual_vals = M.monthly(source, 'actual', months)
        last_year_vals = M.monthly(source, 'last_year', months)
        yoy_rates = [M.growth(a, ly).value for a, ly in zip(actual_vals, last_year_vals)]
        mom_rates = M.month_over_month(actual_vals)

        # 最近一个算得出环比的月份 (本月和上月都有数、上月不为 0)
        latest_mom = next(((labels[i], r) for i, r in reversed(list(enumerate(mom_rates))) if r is not None), None)
        latest_mom_kpi = {
            "label": "最近月环比",
            "value": f"{latest_mom[0]} {latest_mom[1]:+.1f}" if latest_mom else '-',
            "unit": "%",
            "trend": self._trend_from_value(latest_mom[1]) if latest_mom else "flat",
        }

        # KPIs
        total_actual = M.total(actual_vals)
        total_last_year = M.total(last_year_vals)
        total_yoy_amount = (None if total_actual is None or total_last_year is None
                            else total_actual - total_last_year)
        total_yoy_rate = M.growth(total_actual, total_last_year).value
        scale = _detect_value_scale([v for v in actual_vals + last_year_vals if v is not None])
        divisor = scale['divisor']

        def scaled(v):
            return None if v is None else round(v / divisor, 2)

        def r1(v):
            return None if v is None else round(v, 1)

        kpis = [
            {"label": "上年累计", "value": self._format_value(total_last_year, scale), "unit": "元", "trend": "flat",
             "sparkline": [scaled(v) for v in last_year_vals]},
            {"label": "本年累计", "value": self._format_value(total_actual, scale), "unit": "元",
             "trend": self._trend_from_value(total_yoy_amount),
             "sparkline": [scaled(v) for v in actual_vals]},
            {"label": "同比增长额", "value": self._format_value(total_yoy_amount, scale), "unit": "元",
             "trend": self._trend_from_value(total_yoy_amount)},
            {"label": "同比增长率", "value": f"{total_yoy_rate:.1f}" if total_yoy_rate is not None else '-',
             "unit": "%", "trend": self._trend_from_value(total_yoy_rate),
             "sparkline": [r1(r) for r in yoy_rates]},
            latest_mom_kpi,
        ]

        # Scale values
        actual_scaled = [scaled(v) for v in actual_vals]
        ly_scaled = [scaled(v) for v in last_year_vals]

        # --- Build YoY scatter bubble data (colored circles with rate labels) ---
        yoy_scatter_data = []
        for i, rate in enumerate(yoy_rates):
            if rate is None:
                continue
            yoy_scatter_data.append({
                "value": [i, round(rate, 1)],
                "itemStyle": {
                    "color": COLORS['yoy_up'] if rate >= 0 else COLORS['yoy_down'],
                },
                "label": {
                    "show": True,
                    "position": "inside",
                    "formatter": f"{round(rate, 0):.0f}%" if abs(rate) < 1000 else f"{round(rate, 0):.0f}%",
                    "fontSize": 9,
                    "fontWeight": "bold",
                    "color": "#fff",
                },
            })

        # --- Build quarterly star markers as graphic elements ---
        quarter_star_graphics = []
        month_range = list(range(start_month, end_month + 1))
        for q in range(4):
            q_start_m = q * 3 + 1
            q_end = q * 3 + 3
            if q_start_m < start_month or q_end > end_month:
                continue
            q_actual = M.total(actual_vals[m - start_month] for m in range(q_start_m, q_end + 1)
                               if start_month <= m <= end_month)
            if q_actual is None:
                continue
            q_formatted = f"{round(q_actual / divisor):,}" if divisor > 1 else f"{round(q_actual):,}"
            # Position above the middle month of the quarter
            q_mid_idx = month_range.index(q_start_m + 1) if (q_start_m + 1) in month_range else month_range.index(q_end)
            x_pct = 8 + q_mid_idx * (84 / max(len(labels) - 1, 1)) if len(labels) > 1 else 50
            quarter_star_graphics.append({
                "type": "text",
                "style": {
                    "text": f"\u2605{q_formatted}",
                    "fontSize": 12,
                    "fill": COLORS['danger'],
                    "fontWeight": "bold",
                    "textAlign": "center",
                },
                "left": f"{x_pct}%",
                "top": "8%",
                "silent": True,
                "z": 100,
            })

        # --- ECharts option ---
        legend_data = [f"本年{scale['name_suffix']}", f"上年{scale['name_suffix']}", "同比增长率", "环比增长率"]

        option = self._base_echarts_option()
        option.update({
            "grid": {"left": "3%", "right": "5%", "bottom": "18%", "top": "16%", "containLabel": True},
            "legend": {
                "data": legend_data,
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
                    "name": "增长率 (%)",
                    "nameTextStyle": {"fontSize": 11},
                    "axisLabel": {"formatter": "{value}%", "fontSize": 10},
                    "splitLine": {"show": False},
                },
            ],
            "series": [
                {
                    "name": f"本年{scale['name_suffix']}",
                    "type": "bar",
                    "data": actual_scaled,
                    "itemStyle": {"color": self._gradient_color(COLORS['current_year']), "borderRadius": [2, 2, 0, 0]},
                    "barGap": "10%",
                    "barMaxWidth": 28,
                    "emphasis": {"itemStyle": {"shadowBlur": 10, "shadowColor": "rgba(0,0,0,0.2)"}},
                },
                {
                    "name": f"上年{scale['name_suffix']}",
                    "type": "bar",
                    "data": ly_scaled,
                    "itemStyle": {"color": self._gradient_color(COLORS['last_year']), "borderRadius": [2, 2, 0, 0]},
                    "barMaxWidth": 28,
                    "emphasis": {"itemStyle": {"shadowBlur": 10, "shadowColor": "rgba(0,0,0,0.2)"}},
                },
                # Change 1: YoY as SCATTER bubbles instead of LINE
                {
                    "name": "同比增长率",
                    "type": "scatter",
                    "yAxisIndex": 1,
                    "data": yoy_scatter_data,
                    "symbolSize": 30,
                    "itemStyle": {"color": COLORS['danger']},
                    "label": {
                        "show": True,
                        "position": "inside",
                        "fontSize": 9,
                        "fontWeight": "bold",
                        "color": "#fff",
                    },
                    "emphasis": {
                        "itemStyle": {"shadowBlur": 10, "shadowColor": "rgba(0,0,0,0.3)"},
                        "scale": 1.2,
                    },
                    "z": 10,
                },
            ],
        })

        option["series"].append({
            "name": "环比增长率",
            "type": "line",
            "yAxisIndex": 1,
            "data": [r1(r) for r in mom_rates],
            "connectNulls": False,
            "itemStyle": {"color": COLORS['accent']},
            "lineStyle": {"width": 1.5, "type": "dashed"},
            "symbol": "triangle",
            "symbolSize": 6,
            "label": {"show": True, "formatter": "{c}%", "fontSize": 11, "position": "top"},
        })

        self._apply_datazoom(option)

        # Quarter markArea
        mark_areas = self._quarter_mark_areas(start_month, end_month)
        if mark_areas:
            option["series"][0]["markArea"] = {"silent": True, "data": mark_areas}

        # Difference arrows as graphic elements
        graphic_elements = []
        for i, (a, ly) in enumerate(zip(actual_scaled, ly_scaled)):
            if a is None or ly is None:
                continue
            diff = a - ly
            if abs(diff) > 0:
                color = COLORS['yoy_up'] if diff > 0 else COLORS['yoy_down']
                arrow = "\u25b2" if diff > 0 else "\u25bc"
                graphic_elements.append({
                    "type": "text",
                    "left": f"{8 + i * (84 / max(len(labels) - 1, 1)) if len(labels) > 1 else 50}%",
                    "top": "82%",
                    "style": {
                        "text": f"{arrow}{abs(diff):.1f}",
                        "fontSize": 8,
                        "fill": color,
                        "textAlign": "center",
                    },
                    "silent": True,
                })

        # === Change 5: Monthly data rows (本年/上年) as graphic elements inside chart ===
        data_row_graphics = []
        # Row label "本年"
        data_row_graphics.append({
            "type": "text",
            "style": {
                "text": "本年",
                "fontSize": 9,
                "fill": "#333",
                "fontWeight": "bold",
                "textAlign": "right",
            },
            "left": "4%",
            "bottom": "10%",
            "silent": True,
        })
        # Row label "上年"
        data_row_graphics.append({
            "type": "text",
            "style": {
                "text": "上年",
                "fontSize": 9,
                "fill": "#999",
                "fontWeight": "bold",
                "textAlign": "right",
            },
            "left": "4%",
            "bottom": "7%",
            "silent": True,
        })
        # Monthly values for 本年 and 上年
        for i in range(len(labels)):
            x_pct = 8 + i * (84 / max(len(labels) - 1, 1)) if len(labels) > 1 else 50
            # Current year value
            data_row_graphics.append({
                "type": "text",
                "style": {
                    "text": f"{actual_scaled[i]:,.1f}" if actual_scaled[i] is not None else "—",
                    "fontSize": 9,
                    "fill": "#333",
                    "textAlign": "center",
                },
                "left": f"{x_pct}%",
                "bottom": "10%",
                "silent": True,
            })
            # Last year value
            data_row_graphics.append({
                "type": "text",
                "style": {
                    "text": f"{ly_scaled[i]:,.1f}" if ly_scaled[i] is not None else "—",
                    "fontSize": 9,
                    "fill": "#999",
                    "textAlign": "center",
                },
                "left": f"{x_pct}%",
                "bottom": "7%",
                "silent": True,
            })

        # === Change 3: Enhanced quarterly summary red boxes ===
        quarter_summary_graphics = []
        for q in range(4):
            q_end = q * 3 + 3
            q_start_m = q * 3 + 1
            if q_end not in month_range or q_start_m not in month_range:
                continue
            q_idx = month_range.index(q_end)
            q_actual_total = M.total(
                actual_vals[m - start_month]
                for m in range(q_start_m, q_end + 1)
                if start_month <= m <= end_month
            )
            q_ly_total = M.total(
                last_year_vals[m - start_month]
                for m in range(q_start_m, q_end + 1)
                if start_month <= m <= end_month
            )
            if q_actual_total is None:
                continue
            q_yoy_rate = M.growth(q_actual_total, q_ly_total).value
            q_formatted = f"{round(q_actual_total / divisor):,}" if divisor > 1 else f"{round(q_actual_total):,}"
            q_rate_str = f"{q_yoy_rate:.0f}%" if q_yoy_rate is not None else "—"
            x_pct = 8 + q_idx * (84 / max(len(labels) - 1, 1)) if len(labels) > 1 else 50
            # Red filled background box (larger, more prominent)
            quarter_summary_graphics.append({
                "type": "rect",
                "shape": {"x": -36, "y": -14, "width": 72, "height": 28, "r": 4},
                "style": {
                    "fill": "rgba(255,86,48,0.15)",
                    "stroke": COLORS['danger'],
                    "lineWidth": 1.5,
                },
                "left": f"{x_pct}%",
                "bottom": "1%",
                "silent": True,
            })
            # Quarterly total text — two lines: amount + rate
            quarter_summary_graphics.append({
                "type": "text",
                "style": {
                    "text": f"Q{q + 1}: {q_formatted}\n{q_rate_str}",
                    "fontSize": 10,
                    "fill": COLORS['danger'],
                    "fontWeight": "bold",
                    "textAlign": "center",
                    "lineHeight": 13,
                },
                "left": f"{x_pct}%",
                "bottom": "1.5%",
                "silent": True,
            })

        # Merge all graphic elements
        all_graphics = (graphic_elements + quarter_star_graphics
                        + data_row_graphics + quarter_summary_graphics)
        if all_graphics:
            option["graphic"] = all_graphics

        # Table data
        table_data = {
            "headers": ["月份"] + labels,
            "rows": [
                {"label": "本年", "values": actual_scaled},
                {"label": "上年", "values": ly_scaled},
                {"label": "同比(%)", "values": [r1(r) for r in yoy_rates]},
                {"label": "环比(%)", "values": [r1(r) for r in mom_rates]},
            ],
        }

        # Fix 69: CAGR annotation (Think-Cell / McKinsey style)
        if (len(actual_vals) >= 2 and actual_vals[0] is not None and actual_vals[-1] is not None
                and actual_vals[0] > 0 and actual_vals[-1] > 0):
            self._add_cagr_annotation(option, actual_vals[0], actual_vals[-1], len(actual_vals) - 1)

        result = {
            "chartType": self.chart_type,
            "title": f"{year}年{self.display_name}",
            "kpis": kpis,
            "echartsOption": option,
            "tableData": table_data,
            "analysisContext": self.get_analysis_context({"title": f"{year}年{self.display_name}", "kpis": kpis}),
            "monthlyDataRows": {
                "currentYear": actual_scaled,
                "lastYear": ly_scaled,
                "labels": labels,
            },
            "metadata": {"period": period, "scale": scale, "dataQuality": "good"},
        }
        return _sanitize_for_json(result)
