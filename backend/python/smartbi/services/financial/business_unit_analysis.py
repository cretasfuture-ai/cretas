"""Business Unit Analysis Chart Builder — 各业务单元 (事业部 / 部门 / 分公司 / 区域) 对比.

客户需求「从各业务单元」: 能识别事业部 / 部门列, 但原来没有对应的图。
按单元比实际金额与占比; 有预算列时比达成率, 有上年列时比同比增长。
损益表 (有科目) 只取收入科目, 不把成本、费用算进业绩。缺值是空, 不是 0。
"""
import logging
from typing import Any, Dict, List, Optional

import pandas as pd

from .base import AbstractFinancialChartBuilder, COLORS, _sanitize_for_json, _detect_value_scale
from . import metrics as M
from .. import financial_subjects as S

logger = logging.getLogger(__name__)


class BusinessUnitAnalysisBuilder(AbstractFinancialChartBuilder):
    """Horizontal bars of actual (and budget) per business unit, with share / achievement / YoY."""

    chart_type = "business_unit_analysis"
    display_name = "业务单元分析"
    description = "按事业部 / 部门 / 分公司对比实际金额、占比、预算达成和同比增长"
    required_columns = ['actual', 'business_unit']
    display_order = 13

    def build(self, df: pd.DataFrame, column_mapping, period: Dict, year: int = 2026) -> Dict[str, Any]:
        try:
            return self._do_build(df, period, year)
        except Exception as e:
            logger.error(f"BusinessUnitAnalysisBuilder failed: {e}", exc_info=True)
            return {
                "chartType": self.chart_type,
                "title": f"{year}年{self.display_name}",
                "kpis": [],
                "echartsOption": {},
                "tableData": None,
                "analysisContext": f"业务单元分析构建失败: {e}",
                "metadata": {"period": period, "dataQuality": "error"},
            }

    def _do_build(self, df: pd.DataFrame, period: Dict, year: int) -> Dict[str, Any]:
        if 'business_unit' not in df.columns:
            return self._insufficient(period, year, "上传的数据里没有「事业部 / 部门」列")
        source = M.select(df, [S.REVENUE]) if M.has_subjects(df) else df
        source = source[~source['business_unit'].fillna('').astype(str).str.strip().isin(['', 'nan'])]
        if source.empty:
            return self._insufficient(period, year, "所选期间没有业务单元的业绩数据")

        units: List[Dict[str, Any]] = []
        for name, rows in source.groupby('business_unit', sort=False):
            actual = M.total(rows['actual'].tolist())
            budget = M.total(rows['budget'].tolist()) if 'budget' in rows else None
            last_year = M.total(rows['last_year'].tolist()) if 'last_year' in rows else None
            units.append({
                "name": str(name),
                "actual": actual,
                "budget": budget,
                "lastYear": last_year,
                "rate": M.ratio(actual, budget).value,
                "growth": M.growth(actual, last_year).value,
            })
        units = [u for u in units if u["actual"] is not None]
        if not units:
            return self._insufficient(period, year, "业务单元都没有实际金额")

        total_actual = M.total(u["actual"] for u in units)
        for u in units:
            u["share"] = M.ratio(u["actual"], total_actual).value
        units.sort(key=lambda u: u["actual"], reverse=True)

        has_budget = any(u["budget"] is not None for u in units)
        has_last_year = any(u["lastYear"] is not None for u in units)
        scale = _detect_value_scale([v for u in units for v in (u["actual"], u["budget"], u["lastYear"]) if v is not None])
        divisor = scale['divisor']

        def scaled(v: Optional[float]) -> Optional[float]:
            return None if v is None else round(v / divisor, 2)

        def pct(v: Optional[float]) -> str:
            return '-' if v is None else f"{v:.1f}%"

        top = units[0]
        kpis = [
            {"label": "业务单元数", "value": f"{len(units)}个", "unit": "", "trend": "flat"},
            {"label": "实际最大", "value": f"{top['name']} ({pct(top['share'])})", "unit": "", "trend": "flat"},
        ]
        rated = [u for u in units if u["rate"] is not None]
        if rated:
            low = min(rated, key=lambda u: u["rate"])
            kpis.append({"label": "达成最低", "value": f"{low['name']} ({pct(low['rate'])})", "unit": "",
                         "trend": "down" if low["rate"] < 100 else "up"})
        grown = [u for u in units if u["growth"] is not None]
        if grown:
            fast = max(grown, key=lambda u: u["growth"])
            kpis.append({"label": "同比增长最快", "value": f"{fast['name']} ({fast['growth']:+.1f}%)", "unit": "",
                         "trend": "up" if fast["growth"] >= 0 else "down"})

        # 横向条形: 最大的在最上面 (ECharts 类目轴自下而上, 所以倒序)
        ordered = list(reversed(units))
        series: List[Dict[str, Any]] = [{
            "name": "实际",
            "type": "bar",
            "data": [scaled(u["actual"]) for u in ordered],
            "itemStyle": {"color": COLORS['actual'], "borderRadius": [0, 2, 2, 0]},
            "barMaxWidth": 22,
            "label": {"show": True, "position": "right", "fontSize": 11,
                      "formatter": "{c}"},
        }]
        legend = ["实际"]
        if has_budget:
            series.append({
                "name": "预算",
                "type": "bar",
                "data": [scaled(u["budget"]) for u in ordered],
                "itemStyle": {"color": COLORS['budget'], "borderRadius": [0, 2, 2, 0]},
                "barMaxWidth": 22,
            })
            legend.append("预算")
        if has_last_year:
            series.append({
                "name": "上年",
                "type": "bar",
                "data": [scaled(u["lastYear"]) for u in ordered],
                "itemStyle": {"color": COLORS.get('last_year', '#B0BEC5'), "borderRadius": [0, 2, 2, 0]},
                "barMaxWidth": 22,
            })
            legend.append("上年")

        option = self._base_echarts_option()
        option.update({
            "grid": {"left": "3%", "right": "10%", "bottom": "4%", "top": "12%", "containLabel": True},
            "legend": {"data": legend, "top": "2%", "textStyle": {"fontSize": 12}},
            "xAxis": {"type": "value", "name": f"金额{scale['name_suffix']}", "axisLabel": {"fontSize": 11}},
            "yAxis": {"type": "category", "data": [u["name"] for u in ordered], "axisLabel": {"fontSize": 12}},
            "series": series,
        })

        rows = [
            {"label": "实际", "values": [scaled(u["actual"]) for u in units]},
            {"label": "占比(%)", "values": [None if u["share"] is None else round(u["share"], 1) for u in units]},
        ]
        if has_budget:
            rows += [{"label": "预算", "values": [scaled(u["budget"]) for u in units]},
                     {"label": "达成率(%)", "values": [None if u["rate"] is None else round(u["rate"], 1) for u in units]}]
        if has_last_year:
            rows += [{"label": "上年", "values": [scaled(u["lastYear"]) for u in units]},
                     {"label": "同比(%)", "values": [None if u["growth"] is None else round(u["growth"], 1) for u in units]}]

        title = f"{year}年{self.display_name}"
        result = {
            "chartType": self.chart_type,
            "title": title,
            "kpis": kpis,
            "echartsOption": option,
            "tableData": {"headers": ["指标"] + [u["name"] for u in units], "rows": rows},
            "analysisContext": self.get_analysis_context({"title": title, "kpis": kpis}),
            "metadata": {"period": period, "scale": scale, "dataQuality": "good", "units": units},
        }
        return _sanitize_for_json(result)
