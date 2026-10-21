"""Cashflow Trend Area Chart Builder — 现金流趋势分析."""
import logging
from typing import Dict, List, Any, Optional
import pandas as pd

from .base import (
    AbstractFinancialChartBuilder, COLORS, MONTH_LABELS,
    _sanitize_for_json, _detect_value_scale,
)
from . import metrics as M

logger = logging.getLogger(__name__)

# 现金流量表的三类活动 (按科目名关键词识别)
CF_ACTIVITIES = (('operating', '经营活动'), ('investing', '投资活动'), ('financing', '筹资活动'))


class CashflowTrendBuilder(AbstractFinancialChartBuilder):
    """Stacked area chart for operating/investing/financing cashflow + cumulative net line."""

    chart_type = "cashflow_trend"
    display_name = "现金流趋势分析"
    description = "按月展示经营/投资/筹资现金流的趋势面积图，叠加累计净现金流折线"
    required_columns = ['actual', 'period']
    display_order = 14

    def build(self, df: pd.DataFrame, column_mapping, period: Dict, year: int = 2026) -> Dict[str, Any]:
        try:
            return self._do_build(df, column_mapping, period, year)
        except Exception as e:
            logger.error(f"CashflowTrendBuilder failed: {e}", exc_info=True)
            return {
                "chartType": self.chart_type,
                "title": f"{year}年{self.display_name}",
                "kpis": [],
                "echartsOption": {},
                "tableData": None,
                "analysisContext": f"现金流趋势构建失败: {str(e)}",
                "metadata": {"period": period, "dataQuality": "error"},
            }

    def _do_build(self, df: pd.DataFrame, column_mapping, period: Dict, year: int) -> Dict[str, Any]:
        start_month = period.get('start_month', 1)
        end_month = period.get('end_month', 12)
        months = list(range(start_month, end_month + 1))
        month_labels = [MONTH_LABELS[m - 1] for m in months]

        # 现金流只来自表内真实的现金流科目 (经营/投资/筹资活动); ⛔ 不按比例从收入拆
        rows = df
        reported = getattr(column_mapping, 'reported_rows', None)
        if reported is not None and not reported.empty:
            rows = pd.concat([df, reported], ignore_index=True)
        components = {kind: self._activity_series(rows, keyword, months, 'actual')
                      for kind, keyword in CF_ACTIVITIES}
        if all(series is None for series in components.values()):
            return self._empty_result(
                period, year,
                "上传的数据里没有现金流量科目（经营活动、投资活动、筹资活动现金流量），无法做现金流分析。")

        def filled(series: Optional[List[Optional[float]]]) -> List[float]:
            return [0.0 if v is None else round(v, 2) for v in (series or [None] * len(months))]

        operating_cf = filled(components['operating'])
        investing_cf = filled(components['investing'])
        financing_cf = filled(components['financing'])
        net_cf = [round(o + i + f, 2) for o, i, f in zip(operating_cf, investing_cf, financing_cf)]

        # Cumulative net CF
        cumulative_net = []
        running = 0.0
        for ncf in net_cf:
            running += ncf
            cumulative_net.append(round(running, 2))

        # 预算参考线: 只用经营活动现金流自己的预算
        budget_series = self._activity_series(rows, '经营活动', months, 'budget')
        has_budget = budget_series is not None
        budget_line = filled(budget_series) if has_budget else None

        # Scale detection
        all_values = operating_cf + investing_cf + financing_cf + cumulative_net
        scale = _detect_value_scale(all_values)

        scaled_operating = [round(v / scale['divisor'], 2) for v in operating_cf]
        scaled_investing = [round(v / scale['divisor'], 2) for v in investing_cf]
        scaled_financing = [round(v / scale['divisor'], 2) for v in financing_cf]
        scaled_net = [round(v / scale['divisor'], 2) for v in net_cf]
        scaled_cumulative = [round(v / scale['divisor'], 2) for v in cumulative_net]
        scaled_budget = None
        if budget_line:
            scaled_budget = [round(v / scale['divisor'], 2) for v in budget_line]

        # --- KPIs ---
        total_operating = sum(operating_cf)
        total_net = sum(net_cf)
        max_month_idx = net_cf.index(max(net_cf)) if net_cf else 0
        min_month_idx = net_cf.index(min(net_cf)) if net_cf else 0

        kpis = [
            {
                "label": "累计经营现金流",
                "value": self._format_value(total_operating, scale),
                "unit": "元",
                "trend": self._trend_from_value(total_operating),
                "sparkline": [round(v / scale['divisor'], 2) for v in operating_cf],
            },
            {
                "label": "累计净现金流",
                "value": self._format_value(total_net, scale),
                "unit": "元",
                "trend": self._trend_from_value(total_net),
                "sparkline": [round(v / scale['divisor'], 2) for v in cumulative_net],
            },
            {
                "label": "现金流最高月",
                "value": month_labels[max_month_idx] if month_labels else "-",
                "unit": "",
                "trend": "up",
            },
            {
                "label": "现金流最低月",
                "value": month_labels[min_month_idx] if month_labels else "-",
                "unit": "",
                "trend": "down",
            },
        ]

        # --- ECharts area chart ---
        option = self._base_echarts_option()

        legend_data = ["经营现金流", "投资现金流", "筹资现金流", "净现金流", "累计净现金流"]
        if scaled_budget:
            legend_data.append("预算参考线")

        series = [
            {
                "name": "经营现金流",
                "type": "line",
                "stack": "cashflow",
                "areaStyle": {"opacity": 0.5},
                "data": scaled_operating,
                "smooth": True,
                "lineStyle": {"width": 2, "color": COLORS['secondary']},
                "itemStyle": {"color": COLORS['secondary']},
                "emphasis": {"focus": "series"},
            },
            {
                "name": "投资现金流",
                "type": "line",
                "stack": "cashflow",
                "areaStyle": {"opacity": 0.4},
                "data": scaled_investing,
                "smooth": True,
                "lineStyle": {"width": 2, "color": COLORS['primary']},
                "itemStyle": {"color": COLORS['primary']},
                "emphasis": {"focus": "series"},
            },
            {
                "name": "筹资现金流",
                "type": "line",
                "stack": "cashflow",
                "areaStyle": {"opacity": 0.3},
                "data": scaled_financing,
                "smooth": True,
                "lineStyle": {"width": 2, "color": COLORS['accent']},
                "itemStyle": {"color": COLORS['accent']},
                "emphasis": {"focus": "series"},
            },
            {
                "name": "净现金流",
                "type": "bar",
                "data": self._build_net_cf_bar_data(scaled_net),
                "barWidth": "30%",
                "itemStyle": {"borderRadius": [3, 3, 0, 0]},
                "z": 1,
            },
            {
                "name": "累计净现金流",
                "type": "line",
                "yAxisIndex": 1,
                "data": scaled_cumulative,
                "smooth": True,
                "symbol": "diamond",
                "symbolSize": 8,
                "lineStyle": {
                    "width": 3,
                    "color": COLORS['danger'],
                    "type": "solid",
                },
                "itemStyle": {"color": COLORS['danger']},
                "label": {
                    "show": False,
                },
                "z": 10,
            },
        ]

        # Optional budget reference line
        if scaled_budget:
            series.append({
                "name": "预算参考线",
                "type": "line",
                "data": scaled_budget,
                "lineStyle": {
                    "width": 2,
                    "color": COLORS['muted'],
                    "type": "dashed",
                },
                "itemStyle": {"color": COLORS['muted']},
                "symbol": "none",
                "z": 5,
            })

        option.update({
            "title": {
                "text": f"{year}年现金流趋势分析",
                "left": "center",
                "textStyle": {"fontSize": 14, "fontWeight": "bold"},
            },
            "tooltip": {
                "trigger": "axis",
                "axisPointer": {"type": "cross"},
                "confine": True,
            },
            "legend": {
                "data": legend_data,
                "bottom": 0,
                "textStyle": {"fontSize": 10},
                "type": "scroll",
            },
            "grid": {
                "left": "3%",
                "right": "4%",
                "bottom": "15%",
                "top": "15%",
                "containLabel": True,
            },
            "xAxis": {
                "type": "category",
                "data": month_labels,
                "boundaryGap": True,
                "axisLabel": {"fontSize": 11},
            },
            "yAxis": [
                {
                    "type": "value",
                    "name": f"月度现金流{scale['name_suffix']}",
                    "position": "left",
                    "axisLabel": {"formatter": "{value}"},
                },
                {
                    "type": "value",
                    "name": f"累计净额{scale['name_suffix']}",
                    "position": "right",
                    "splitLine": {"show": False},
                    "axisLabel": {"formatter": "{value}"},
                },
            ],
            "series": series,
        })
        self._apply_datazoom(option)

        # Add zero reference line
        option["yAxis"][0]["axisLine"] = {"show": True}

        # Fix 69: CAGR annotation for cumulative net cashflow
        if len(net_cf) >= 2 and net_cf[0] > 0 and net_cf[-1] > 0:
            self._add_cagr_annotation(option, net_cf[0], net_cf[-1], len(net_cf) - 1)

        # Fix 70: Trend line + R² for operating cashflow
        if len(scaled_operating) >= 3:
            self._add_trend_series(option, scaled_operating, "经营CF趋势线")

        # --- Table data ---
        table_data = {
            "headers": [
                "月份",
                f"经营CF{scale['name_suffix']}",
                f"投资CF{scale['name_suffix']}",
                f"筹资CF{scale['name_suffix']}",
                f"净CF{scale['name_suffix']}",
                f"累计净CF{scale['name_suffix']}",
            ],
            "rows": [],
        }
        for i, label in enumerate(month_labels):
            table_data["rows"].append([
                label,
                f"{scaled_operating[i]:,.2f}",
                f"{scaled_investing[i]:,.2f}",
                f"{scaled_financing[i]:,.2f}",
                f"{scaled_net[i]:,.2f}",
                f"{scaled_cumulative[i]:,.2f}",
            ])

        # --- Analysis context ---
        analysis_context = (
            f"{year}年现金流趋势: "
            f"累计经营现金流{self._format_value(total_operating, scale)}元, "
            f"累计净现金流{self._format_value(total_net, scale)}元, "
            f"最高月{month_labels[max_month_idx]}, 最低月{month_labels[min_month_idx]}"
        )

        result = {
            "chartType": self.chart_type,
            "title": f"{year}年{self.display_name}",
            "kpis": kpis,
            "echartsOption": option,
            "tableData": table_data,
            "analysisContext": analysis_context,
            "metadata": {
                "period": period,
                "dataQuality": "good",
                "monthCount": len(months),
                "hasBudgetRef": budget_line is not None,
                "scale": scale['suffix'],
            },
        }
        return _sanitize_for_json(result)

    @staticmethod
    def _activity_series(rows: pd.DataFrame, keyword: str, months: List[int],
                         field_name: str) -> Optional[List[Optional[float]]]:
        """某类活动 (经营/投资/筹资) 的逐月现金流量净额; 表内没有这类科目时返回 None。

        优先用「…现金流量净额」行; 没有净额行时用 流入 - 流出 (流出按金额扣减, 不论原表符号)。
        """
        if rows is None or rows.empty or 'item' not in rows.columns or field_name not in rows.columns:
            return None
        items = rows['item'].fillna('').astype(str)
        part = rows[items.str.contains(keyword)]
        if part.empty or part[field_name].notna().sum() == 0:
            return None
        part_items = part['item'].fillna('').astype(str)
        net_rows = part[part_items.str.contains('净额')]
        if not net_rows.empty:
            return M.monthly(net_rows, field_name, months)
        signed = part.copy()
        outflow = part_items.str.contains('流出')
        signed.loc[outflow, field_name] = -signed.loc[outflow, field_name].abs()
        return M.monthly(signed, field_name, months)

    def _build_net_cf_bar_data(self, scaled_net: List[float]) -> List[Dict]:
        """Build bar data with positive=green, negative=red coloring."""
        data = []
        for val in scaled_net:
            color = COLORS['secondary'] if val >= 0 else COLORS['danger']
            data.append({
                "value": val,
                "itemStyle": {"color": self._gradient_color(color)},
            })
        return data

    def _empty_result(self, period: Dict, year: int, reason: str = "无现金流趋势数据") -> Dict:
        return {
            "chartType": self.chart_type,
            "title": f"{year}年{self.display_name}",
            "kpis": [],
            "echartsOption": {},
            "tableData": None,
            "analysisContext": reason,
            "metadata": {"period": period, "dataQuality": "insufficient", "reason": reason},
        }
