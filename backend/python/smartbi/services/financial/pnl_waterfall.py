"""P&L Waterfall Chart Builder — 损益表瀑布图."""
import logging
from typing import Dict, Any, List, Tuple
import pandas as pd

from .base import (
    AbstractFinancialChartBuilder, COLORS,
    _sanitize_for_json, _detect_value_scale,
)
from . import metrics as M
from .. import financial_subjects as S

logger = logging.getLogger(__name__)

# 瀑布图里各类科目的出场顺序与方向 (+1 抬高, -1 压低)。金额按原值带符号计算:
# 财务费用为 -20 (利息收入) 时柱子向上, 不取绝对值。
_STEPS: Tuple[Tuple[str, int], ...] = (
    (S.COST, -1),
    (S.EXPENSE_SALES, -1), (S.EXPENSE_ADMIN, -1), (S.EXPENSE_RD, -1), (S.EXPENSE_FINANCE, -1),
    (S.EXPENSE_OTHER, -1), (S.SURCHARGE, -1),
    (S.OTHER_GAIN, 1), (S.NON_OP_INCOME, 1), (S.NON_OP_EXPENSE, -1), (S.INCOME_TAX, -1),
)


class PnlWaterfallBuilder(AbstractFinancialChartBuilder):
    """Waterfall chart using stacked bars for P&L breakdown."""

    chart_type = "pnl_waterfall"
    display_name = "损益表瀑布图"
    description = "展示从营业收入到净利润的损益表层级分解，使用瀑布图展现各项增减"
    required_columns = ['item']
    display_order = 3

    def build(self, df: pd.DataFrame, column_mapping, period: Dict, year: int = 2026) -> Dict[str, Any]:
        pnl = M.pnl(df)
        revenue_total = pnl.revenue

        if revenue_total is None or revenue_total == 0:
            reason = "营业收入为零，无法生成损益表瀑布图" if revenue_total == 0 else "无可用的损益表数据"
            return {
                "chartType": self.chart_type,
                "title": f"{year}年{self.display_name}",
                "kpis": [],
                "echartsOption": {},
                "tableData": None,
                "analysisContext": reason,
                "metadata": {"period": period, "dataQuality": "insufficient", "reason": reason},
            }

        # 明细科目按表内出现顺序汇总 (同名科目跨月相加)
        item_totals: List[Tuple[str, str, float]] = []
        classified = df[df['subject_class'] != S.UNKNOWN] if 'subject_class' in df.columns else df.iloc[0:0]
        for (item, cls), grp in classified.groupby(['item', 'subject_class'], sort=False):
            v = M.total(grp['actual'].tolist())
            if v is not None and v != 0:
                item_totals.append((str(item), str(cls), v))

        # Build waterfall items
        waterfall_items: List[Dict[str, Any]] = [
            {"name": "营业收入", "value": revenue_total, "type": "revenue", "is_total": True},
        ]
        for cls, direction in _STEPS:
            for name, item_cls, v in item_totals:
                if item_cls == cls:
                    waterfall_items.append({"name": S.strip_subject(name), "value": direction * v,
                                            "type": cls, "is_total": False})
            if cls == S.COST and pnl.gross_profit is not None:
                waterfall_items.append({"name": "毛利润", "value": pnl.gross_profit, "type": "profit", "is_total": True})
        net_profit = pnl.net_profit
        waterfall_items.append({"name": "净利润", "value": net_profit, "type": "profit", "is_total": True})

        # Compute stacked bar positions
        categories = []
        helper_data = []      # Transparent base
        value_data = []       # Visible bar
        colors = []
        running_total = 0.0
        running_totals = []
        for item in waterfall_items:
            categories.append(item['name'])
            val = item['value']

            if item['is_total']:
                # Total bars start from 0
                helper_data.append(0 if val >= 0 else val)
                value_data.append(abs(val))
                running_total = val
            else:
                # Incremental bars stack on running total
                if val < 0:
                    # Deduction: bar goes down from running_total
                    helper_data.append(running_total + val)
                    value_data.append(abs(val))
                else:
                    # Addition: bar goes up from running_total
                    helper_data.append(running_total)
                    value_data.append(val)
                running_total += val

            running_totals.append(running_total)

            # 客户样图口径: 增加红 / 减少绿 / 汇总蓝; 营业收入是起点, 按「增加」着色
            if item['type'] == 'revenue':
                colors.append(COLORS['revenue'])
            elif item['is_total']:
                colors.append(COLORS['profit'])
            else:
                colors.append(COLORS['revenue'] if val >= 0 else COLORS['cost'])

        # Scale
        cost_total = pnl.cost or 0.0
        expense_total = pnl.expense_total
        all_values = [abs(v) for v in [revenue_total, cost_total, expense_total or 0, net_profit] if v]
        scale = _detect_value_scale(all_values)
        divisor = scale['divisor']

        # KPIs
        gross_margin = pnl.gross_margin().value
        net_margin = pnl.net_margin().value

        kpis = [
            {"label": "营业收入", "value": self._format_value(revenue_total, scale), "unit": "元", "trend": "flat"},
            {"label": "营业成本", "value": self._format_value(pnl.cost, scale), "unit": "元", "trend": "flat"},
            {"label": "费用合计", "value": self._format_value(expense_total, scale), "unit": "元", "trend": "flat"},
            {"label": "净利润", "value": self._format_value(net_profit, scale), "unit": "元",
             "trend": self._trend_from_value(net_profit)},
        ]

        # Scale bar data
        helper_scaled = [round(v / divisor, 2) for v in helper_data]
        value_scaled = [round(v / divisor, 2) for v in value_data]

        # ECharts option
        option = self._base_echarts_option()
        option.pop("dataZoom", None)
        option.pop("toolbox", None)
        option["legend"] = {
            "top": "2%", "left": "center",
            "data": [
                {"name": "增加", "icon": "rect", "itemStyle": {"color": COLORS['revenue']}},
                {"name": "减少", "icon": "rect", "itemStyle": {"color": COLORS['cost']}},
                {"name": "汇总", "icon": "rect", "itemStyle": {"color": COLORS['profit']}},
            ],
            "textStyle": {"fontSize": 11},
        }
        option["grid"] = {"left": "5%", "right": "5%", "bottom": "8%", "top": "20%", "containLabel": True}
        option.update({
            "xAxis": {
                "type": "category",
                "data": categories,
                "axisLabel": {"fontSize": 10, "rotate": 20, "interval": 0},
                "axisTick": {"alignWithLabel": True},
            },
            "yAxis": {
                "type": "value",
                "name": f"金额{scale['name_suffix']}",
                "nameTextStyle": {"fontSize": 11},
                "axisLabel": {"fontSize": 10},
                "splitLine": {"lineStyle": {"type": "dashed", "color": "#e8e8e8"}},
            },
            "series": [
                {
                    "name": "辅助",
                    "type": "bar",
                    "stack": "waterfall",
                    "data": helper_scaled,
                    "itemStyle": {"color": "transparent", "borderColor": "transparent"},
                    "emphasis": {"itemStyle": {"color": "transparent"}},
                    "tooltip": {"show": False},
                    "barMaxWidth": 40,
                },
                {
                    "name": "数值",
                    "type": "bar",
                    "stack": "waterfall",
                    "data": [
                        {
                            "value": value_scaled[i],
                            "itemStyle": {
                                "color": self._gradient_color(colors[i]),
                                "borderRadius": [3, 3, 0, 0] if waterfall_items[i]['value'] >= 0 else [0, 0, 3, 3],
                            },
                            # 标签显示带符号的金额 (减少项为负), 与客户原表一致
                            "label": {"formatter": f"{waterfall_items[i]['value'] / divisor:,.0f}"
                                      if abs(waterfall_items[i]['value'] / divisor) >= 100
                                      else f"{waterfall_items[i]['value'] / divisor:,.2f}"},
                        }
                        for i in range(len(value_scaled))
                    ],
                    "barMaxWidth": 40,
                    "label": {
                        "show": True,
                        "position": "top",
                        "fontSize": 10,
                        "color": "#333",
                        "fontWeight": "bold",
                    },
                    "emphasis": {"itemStyle": {"shadowBlur": 10, "shadowColor": "rgba(0,0,0,0.2)"}},
                },
            ],
        })

        # Connection lines between waterfall bars
        conn_data = []
        for i in range(len(running_totals) - 1):
            rt = round(running_totals[i] / divisor, 2)
            conn_data.append([
                {"xAxis": categories[i], "yAxis": rt, "symbol": "none"},
                {"xAxis": categories[i + 1], "yAxis": rt, "symbol": "none"},
            ])
        if conn_data:
            option["series"][1]["markLine"] = {
                "silent": True,
                "symbol": ["none", "none"],
                "lineStyle": {"type": "dotted", "color": "#999", "width": 1},
                "data": conn_data,
            }

        # Graphic annotations for margins
        def pct(v):
            return '-' if v is None else f"{v:.1f}%"

        option["graphic"] = [
            {
                "type": "text",
                "right": "6%",
                "top": "14%",
                "style": {
                    "text": f"毛利率: {pct(gross_margin)}  |  净利率: {pct(net_margin)}",
                    "fontSize": 12,
                    "fill": COLORS['primary'],
                    "fontWeight": "bold",
                },
                "silent": True,
            },
        ]

        # Table data
        table_data = {
            "headers": ["项目", f"金额{scale['name_suffix']}", "占收入比"],
            "rows": [
                {"label": item['name'], "values": [
                    round(item['value'] / divisor, 2),
                    f"{round(item['value'] / revenue_total * 100, 1)}%",
                ]}
                for item in waterfall_items
            ],
        }

        reconciliation = M.reconcile(pnl, getattr(column_mapping, 'reported_rows', None))
        reconciliation += [f"表内没有{line}科目，按 0 计算" for line in pnl.missing_lines()]
        unclassified = sorted({str(v) for v in df.loc[df['subject_class'] == S.UNKNOWN, 'item']
                               if str(v).strip()}) if 'subject_class' in df.columns else []

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
                "grossMargin": gross_margin,
                "netMargin": net_margin,
                "dataQuality": "good",
                "reconciliation": reconciliation,
                "unclassifiedItems": unclassified,
            },
        }
        return _sanitize_for_json(result)
