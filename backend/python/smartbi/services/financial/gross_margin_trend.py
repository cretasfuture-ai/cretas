"""Gross Margin Trend Chart Builder — 毛利率趋势."""
import logging
from typing import Dict, Any, List, Optional
import pandas as pd

from .base import (
    AbstractFinancialChartBuilder, _sanitize_for_json,
)
from . import metrics as M
from .. import financial_subjects as S

logger = logging.getLogger(__name__)

# 客户样图口径: 毛利率提升红色↑, 下降绿色↓
_UP_COLOR = "#FF5630"
_DOWN_COLOR = "#36B37E"


def _round(v: Optional[float], n: int = 2) -> Optional[float]:
    return None if v is None else round(v, n)


class GrossMarginTrendBuilder(AbstractFinancialChartBuilder):
    """Line chart showing margin difference with annotations and data table."""

    chart_type = "gross_margin_trend"
    display_name = "毛利率趋势"
    description = "展示毛利率月度走势，对比本年与上年差异，含趋势标注"
    required_columns = ['actual', 'last_year', 'period']
    display_order = 6
    accepts_ratio_values = True  # 表里直接给各月毛利率时走百分比口径

    def build(self, df: pd.DataFrame, column_mapping, period: Dict, year: int = 2026) -> Dict[str, Any]:
        start_month = period.get('start_month', 1)
        end_month = period.get('end_month', 12)
        months = list(range(start_month, end_month + 1))
        labels = self._month_labels(start_month, end_month)

        # 口径 1 (首选): 有收入、成本科目 → 毛利率 = (收入-成本)/收入, 合计按期间总额加权
        # 口径 2: 表里直接给的是毛利率百分比 → 各月照用, 合计只能是月均 (没有收入做权重)
        rev = M.select(df, [S.REVENUE])
        cost = M.select(df, [S.COST])
        weighted = not rev.empty and not cost.empty

        if weighted:
            rev_cur, cost_cur = M.monthly(rev, 'actual', months), M.monthly(cost, 'actual', months)
            rev_ly, cost_ly = M.monthly(rev, 'last_year', months), M.monthly(cost, 'last_year', months)
            current_margins = [self._margin(r, c) for r, c in zip(rev_cur, cost_cur)]
            ly_margins = [self._margin(r, c) for r, c in zip(rev_ly, cost_ly)]
            total_cur = self._margin(M.total(rev_cur), M.total(cost_cur))
            total_ly = self._margin(M.total(rev_ly), M.total(cost_ly))
        else:
            cur_raw = M.monthly(df, 'actual', months)
            ly_raw = M.monthly(df, 'last_year', months)
            present = [abs(v) for v in cur_raw + ly_raw if v is not None]
            if not present or max(present) > 100:
                reason = ("数据为绝对金额而非百分比，无法计算毛利率。需要毛利率百分比数据或独立的收入/成本数据。"
                          if present else "所选期间没有毛利率数据")
                return {
                    "chartType": self.chart_type,
                    "title": f"{year}年{self.display_name}",
                    "kpis": [],
                    "echartsOption": {},
                    "tableData": None,
                    "analysisContext": reason,
                    "metadata": {"period": period, "dataQuality": "insufficient", "reason": reason},
                }
            current_margins, ly_margins = cur_raw, ly_raw
            total_cur = self._mean(current_margins)
            total_ly = self._mean(ly_margins)

        # Margin difference (百分点); 任一侧缺失则该月差异为空
        margin_diff = [None if c is None or ly is None else round(c - ly, 2)
                       for c, ly in zip(current_margins, ly_margins)]
        total_diff = None if total_cur is None or total_ly is None else round(total_cur - total_ly, 2)

        prefix = "累计" if weighted else "月均"
        valid_diffs = [(i, d) for i, d in enumerate(margin_diff) if d is not None]
        if valid_diffs:
            best_idx, best_val = max(valid_diffs, key=lambda x: x[1])
            best_diff_value = f"{labels[best_idx]} ({best_val:+.1f}个百分点)"
        else:
            best_diff_value = '-'

        def fmt(v: Optional[float], spec: str = ".1f") -> str:
            return '-' if v is None else format(v, spec)

        kpis = [
            {"label": f"本年{prefix}毛利率", "value": fmt(total_cur), "unit": "%",
             "trend": self._trend_from_value(total_diff) if total_diff is not None else "flat",
             "sparkline": [_round(v, 1) for v in current_margins]},
            {"label": f"上年{prefix}毛利率", "value": fmt(total_ly), "unit": "%", "trend": "flat",
             "sparkline": [_round(v, 1) for v in ly_margins]},
            {"label": "累计差异" if weighted else "平均差异", "value": fmt(total_diff, "+.1f"), "unit": "个百分点",
             "trend": self._trend_from_value(total_diff) if total_diff is not None else "flat",
             "sparkline": [d for d in margin_diff]},
            {"label": "最佳改善月", "value": best_diff_value,
             "unit": "", "trend": "up"},
        ]

        # ECharts option — single difference line (red line + yellow dots)
        option = self._base_echarts_option()
        option["grid"] = {"left": "3%", "right": "4%", "bottom": "15%", "top": "15%", "containLabel": True}

        # Build label data with positional formatting
        diff_series_data: List[Any] = []
        for d in margin_diff:
            if d is None:
                diff_series_data.append(None)
                continue
            arrow = "↑" if d >= 0 else "↓"
            color = _UP_COLOR if d >= 0 else _DOWN_COLOR
            diff_series_data.append({
                "value": round(d, 2),
                "label": {
                    "show": True,
                    "position": "top" if d >= 0 else "bottom",
                    "formatter": f"{arrow}{abs(d):.2f}%",
                    "fontSize": 10,
                    "color": color,
                    "fontWeight": "bold",
                },
            })

        option.update({
            "legend": {
                "data": ["毛利率差异"],
                "top": "2%",
                "textStyle": {"fontSize": 11},
            },
            "xAxis": {
                "type": "category",
                "data": labels,
                "axisLabel": {"fontSize": 11},
            },
            "yAxis": {
                "type": "value",
                "name": "差异 (个百分点)",
                "nameTextStyle": {"fontSize": 11},
                "axisLabel": {"formatter": "{value}", "fontSize": 10},
                "splitLine": {"lineStyle": {"type": "dashed", "color": "#e8e8e8"}},
            },
            "series": [
                {
                    "name": "毛利率差异",
                    "type": "line",
                    "data": diff_series_data,
                    "connectNulls": False,
                    "lineStyle": {"color": "#FF0000", "width": 2.5},
                    "itemStyle": {"color": "#E8B931", "borderColor": "#FF0000", "borderWidth": 2},
                    "symbol": "circle",
                    "symbolSize": 10,
                    "smooth": 0.3,
                },
            ],
        })
        self._apply_datazoom(option)

        # Data table below chart — 合计列: 有收入成本时按期间总额加权, 否则为月均
        total_header = "合计" if weighted else "合计(月均)"
        table_data = {
            "headers": ["指标"] + labels + [total_header],
            "rows": [
                {"label": "上年毛利率(%)", "values": [_round(v) for v in ly_margins] + [_round(total_ly)]},
                {"label": "本年毛利率(%)", "values": [_round(v) for v in current_margins] + [_round(total_cur)]},
                {"label": "差异(个百分点)", "values": margin_diff + [total_diff]},
            ],
        }

        metadata: Dict[str, Any] = {"period": period, "dataQuality": "good",
                                    "marginBasis": "weighted" if weighted else "monthly_average"}
        if not weighted:
            metadata["note"] = "表内只有各月毛利率、没有收入和成本，合计为各月简单平均，不是加权毛利率"

        result = {
            "chartType": self.chart_type,
            "title": f"{year}年{self.display_name}",
            "kpis": kpis,
            "echartsOption": option,
            "tableData": table_data,
            "analysisContext": self.get_analysis_context({"title": f"{year}年{self.display_name}", "kpis": kpis}),
            "metadata": metadata,
        }
        return _sanitize_for_json(result)

    @staticmethod
    def _margin(revenue: Optional[float], cost: Optional[float]) -> Optional[float]:
        m = M.ratio(None if revenue is None or cost is None else revenue - cost, revenue)
        return m.value

    @staticmethod
    def _mean(values: List[Optional[float]]) -> Optional[float]:
        vals = [v for v in values if v is not None]
        return round(sum(vals) / len(vals), 2) if vals else None
