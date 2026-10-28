"""HR Cost Analysis Chart Builder — 人力成本分析."""
import logging
from typing import Dict, List, Any, Optional, Tuple
import pandas as pd

from .base import (
    AbstractFinancialChartBuilder, _sanitize_for_json, _detect_value_scale,
)
from . import metrics as M
from .. import financial_subjects as S

logger = logging.getLogger(__name__)

# 人力成本科目 (按科目名关键词识别): 组 -> 关键词。顺序即匹配优先级。
HR_GROUPS: Tuple[Tuple[str, str, Tuple[str, ...]], ...] = (
    ("benefits", "福利社保", ("社保", "社会保险", "公积金", "福利", "保险费", "补贴")),
    ("training", "培训", ("培训", "教育经费")),
    ("salary", "薪资", ("工资", "薪酬", "薪资", "薪金", "奖金", "绩效", "提成", "计件")),
    ("other", "其他人力", ("人工", "劳务", "外包", "临时工", "工会经费", "职工")),
)

# Green/blue shades for HR categories
HR_COLORS = {
    "salary": "#2D8B57",      # green — 薪资
    "benefits": "#4C9AFF",    # medium blue — 福利
    "training": "#79B8FF",    # light blue — 培训
    "other": "#B3D4FC",       # pale blue — 其他
}

NO_HR_MESSAGE = ("上传的数据里没有人力成本科目（如工资、社保、福利、培训），无法做人力成本分析。")


def _hr_group(item: str) -> Optional[str]:
    for key, _, keywords in HR_GROUPS:
        if any(kw in item for kw in keywords):
            return key
    return None


class HRCostAnalysisBuilder(AbstractFinancialChartBuilder):
    """Stacked bars for HR cost breakdown, built only from real HR line items.

    ⛔ 不按「薪资 60% / 福利 25% …」的比例拆分, 不估算人数, 不填占位的人力成本占比。
    """

    chart_type = "hr_cost_analysis"
    display_name = "人力成本分析"
    gap_kind = 'statement'
    statement_hint = "需要人工成本明细：一列「项目」写工资、社保、福利、培训等，按月填金额。"
    description = "展示人力成本构成(薪资/福利/培训)的月度趋势及同比对比"
    required_columns = ['actual', 'period']
    display_order = 15

    def build(self, df: pd.DataFrame, column_mapping, period: Dict, year: int = 2026) -> Dict[str, Any]:
        try:
            return self._do_build(df, column_mapping, period, year)
        except Exception as e:
            logger.error(f"HRCostAnalysisBuilder failed: {e}", exc_info=True)
            return {
                "chartType": self.chart_type,
                "title": f"{year}年{self.display_name}",
                "kpis": [],
                "echartsOption": {},
                "tableData": None,
                "analysisContext": f"人力成本分析构建失败: {str(e)}",
                "metadata": {"period": period, "dataQuality": "error"},
            }

    def _do_build(self, df: pd.DataFrame, column_mapping, period: Dict, year: int) -> Dict[str, Any]:
        start_month = period.get('start_month', 1)
        end_month = period.get('end_month', 12)
        months = list(range(start_month, end_month + 1))
        labels = self._month_labels(start_month, end_month)

        if 'item' not in df.columns:
            return self._empty_result(period, year, NO_HR_MESSAGE)
        groups = df['item'].fillna('').astype(str).map(_hr_group)
        hr_rows = df.assign(hr_group=groups)[groups.notna()]
        if hr_rows.empty or hr_rows['actual'].notna().sum() == 0:
            return self._empty_result(period, year, NO_HR_MESSAGE)

        series_by_group: Dict[str, List[Optional[float]]] = {}
        ly_by_group: Dict[str, List[Optional[float]]] = {}
        for key, _, _ in HR_GROUPS:
            part = hr_rows[hr_rows['hr_group'] == key]
            if part.empty:
                continue
            series_by_group[key] = M.monthly(part, 'actual', months)
            ly_by_group[key] = M.monthly(part, 'last_year', months)

        actual_vals = M.monthly(hr_rows, 'actual', months)
        ly_vals = M.monthly(hr_rows, 'last_year', months)
        total_hr_cost = M.total(actual_vals)
        total_ly = M.total(ly_vals)
        yoy_change = M.growth(total_hr_cost, total_ly).value
        has_last_year = total_ly is not None

        # 人力成本占收入比: 只有表里同时有收入科目才算
        revenue_total = M.sum_class(df, [S.REVENUE])
        hr_ratio = M.ratio(total_hr_cost, revenue_total).value

        scale = _detect_value_scale([v for v in actual_vals + ly_vals if v is not None])
        divisor = scale['divisor']

        def scaled(series: List[Optional[float]]) -> List[Optional[float]]:
            return [None if v is None else round(v / divisor, 2) for v in series]

        kpis = [
            {
                "label": "人力总成本",
                "value": self._format_value(total_hr_cost, scale),
                "unit": "元",
                "trend": "flat",
                "sparkline": scaled(actual_vals),
            },
            {
                "label": "人力成本占收入比",
                "value": f"{hr_ratio:.1f}" if hr_ratio is not None else "-",
                "unit": "%",
                "trend": "flat",
            },
            {
                "label": "同比变化",
                "value": f"{yoy_change:+.1f}" if yoy_change is not None else "-",
                "unit": "%",
                "trend": self._trend_from_value(yoy_change) if yoy_change is not None else "flat",
            },
        ]

        # --- ECharts option ---
        option = self._base_echarts_option()
        names = {key: name for key, name, _ in HR_GROUPS}
        legend_data = [f"{names[k]}{scale['name_suffix']}" for k in series_by_group]
        if has_last_year:
            legend_data.append("去年人力总成本")

        series: List[Dict[str, Any]] = []
        for idx, (key, values) in enumerate(series_by_group.items()):
            series.append({
                "name": f"{names[key]}{scale['name_suffix']}",
                "type": "bar",
                "stack": "hr_cost",
                "data": scaled(values),
                "itemStyle": {"color": self._gradient_color(HR_COLORS[key]),
                              "borderRadius": [2, 2, 0, 0] if idx == len(series_by_group) - 1 else [0, 0, 0, 0]},
                "barMaxWidth": 32,
                "emphasis": {"itemStyle": {"shadowBlur": 10, "shadowColor": "rgba(0,0,0,0.2)"}},
            })
        if has_last_year:
            series.append({
                "name": "去年人力总成本",
                "type": "line",
                "data": scaled(ly_vals),
                "itemStyle": {"color": "rgba(45,139,87,0.5)"},
                "lineStyle": {"width": 1.5, "type": "dashed"},
                "symbol": "none",
            })

        option.update({
            "legend": {
                "data": legend_data,
                "top": "2%",
                "textStyle": {"fontSize": 10},
                "type": "scroll",
            },
            "xAxis": {
                "type": "category",
                "data": labels,
                "axisLabel": {"fontSize": 11},
            },
            "yAxis": {
                "type": "value",
                "name": f"成本{scale['name_suffix']}",
                "nameTextStyle": {"fontSize": 11},
                "axisLabel": {"fontSize": 10},
                "splitLine": {"lineStyle": {"type": "dashed", "color": "#e8e8e8"}},
            },
            "series": series,
        })
        self._apply_datazoom(option)

        # Quarter markArea
        mark_areas = self._quarter_mark_areas(start_month, end_month)
        if mark_areas and option["series"]:
            option["series"][0]["markArea"] = {"silent": True, "data": mark_areas}

        # --- Table data ---
        rows = [{"label": "人力总成本", "values": scaled(actual_vals)}]
        rows += [{"label": names[k], "values": scaled(v)} for k, v in series_by_group.items()]
        if has_last_year:
            rows.append({"label": "去年人力总成本", "values": scaled(ly_vals)})
        table_data = {"headers": ["月份"] + labels, "rows": rows}

        result = {
            "chartType": self.chart_type,
            "title": f"{year}年{self.display_name}",
            "kpis": kpis,
            "echartsOption": option,
            "tableData": table_data,
            "analysisContext": self.get_analysis_context({"title": f"{year}年{self.display_name}", "kpis": kpis}),
            "metadata": {
                "period": period,
                "scale": scale,
                "dataQuality": "good",
                "hrItems": sorted({str(v) for v in hr_rows['item']}),
            },
        }
        return _sanitize_for_json(result)

    def _empty_result(self, period: Dict, year: int, reason: str) -> Dict:
        return {
            "chartType": self.chart_type,
            "title": f"{year}年{self.display_name}",
            "kpis": [],
            "echartsOption": {},
            "tableData": None,
            "analysisContext": reason,
            "metadata": {"period": period, "dataQuality": "insufficient", "reason": reason},
        }
