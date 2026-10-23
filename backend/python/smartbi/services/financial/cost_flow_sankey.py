"""Cost Flow Sankey Chart Builder — 成本流向桑基图."""
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


class CostFlowSankeyBuilder(AbstractFinancialChartBuilder):
    """Sankey chart showing P&L cost flow from revenue to net profit."""

    chart_type = "cost_flow_sankey"
    display_name = "成本流向桑基图"
    gap_kind = 'statement'
    statement_hint = "需要损益表：一列「项目」写科目（营业收入、营业成本、销售费用、管理费用、财务费用、所得税等），一列「实际」写金额。"
    description = "展示从收入到净利润的成本流转路径"
    required_columns = ['item']
    display_order = 8

    # Node colors by P&L type
    NODE_COLORS = {
        'revenue': '#FF5630',
        'cost': '#36B37E',
        'expense': '#57D9A3',
        'profit': '#4472C4',
        'tax': '#6B778C',
        'other': '#FFAB00',
    }

    def build(self, df: pd.DataFrame, column_mapping, period: Dict, year: int = 2026) -> Dict[str, Any]:
        # 收入、成本、各项费用、其他收益、营业外收支、所得税与净利润全部来自指标库, 与损益表瀑布图同一口径
        pnl = M.pnl(df)
        revenue_total = pnl.revenue
        if revenue_total is None or revenue_total <= 0:
            return self._insufficient(period, year, "营业收入为零，无法生成成本流向桑基图")

        def items_of(classes) -> List[Tuple[str, float]]:
            part = M.select(df, classes)
            out: List[Tuple[str, float]] = []
            for item, grp in part.groupby('item', sort=False):
                v = M.total(grp['actual'].tolist())
                if v:
                    out.append((S.strip_subject(item), v))
            return out

        cost_items = items_of([S.COST])
        expense_items = items_of(list(S.EXPENSE_CLASSES) + [S.SURCHARGE])
        gain_items = items_of([S.OTHER_GAIN])
        non_op_income = pnl.non_op_income or 0.0
        non_op_expense = pnl.non_op_expense or 0.0
        income_tax = pnl.income_tax or 0.0

        cost_total = pnl.cost or 0.0
        expense_total = sum(v for _, v in expense_items)
        gross_profit = pnl.gross_profit
        operating_profit = pnl.operating_profit
        net_profit = pnl.net_profit

        # --- Build nodes and links (桑基图只能画正向流量; 负数费用 = 净收益, 画成流入营业利润) ---
        nodes = []
        links = []
        node_set = set()

        def _add_node(name, pnl_type):
            if name not in node_set:
                node_set.add(name)
                nodes.append({
                    "name": name,
                    "itemStyle": {"color": self.NODE_COLORS.get(pnl_type, '#FFAB00')},
                })

        def _link(source, target, value):
            if value and value > 0:
                links.append({"source": source, "target": target, "value": round(value, 2)})

        # Level 0 → 1: 营业收入 → 成本项 + 毛利润
        _add_node("营业收入", 'revenue')
        for name, val in sorted(cost_items, key=lambda x: -x[1]):
            if val > 0:
                _add_node(name, 'cost')
                _link("营业收入", name, val)

        if gross_profit is not None and gross_profit > 0:
            _add_node("毛利润", 'profit')
            _link("营业收入", "毛利润", gross_profit)

            # Level 1 → 2: 毛利润 → 各项费用 + 营业利润; 其他收益 / 负数费用 流入营业利润
            positive_expenses = 0.0
            for name, val in sorted(expense_items, key=lambda x: -x[1]):
                if val > 0:
                    _add_node(name, 'expense')
                    _link("毛利润", name, val)
                    positive_expenses += val
            remaining = gross_profit - positive_expenses
            inflows = [(n, v) for n, v in gain_items if v > 0] + \
                [(f"{n}（净收益）", -v) for n, v in expense_items if v < 0]
            if operating_profit is not None and operating_profit > 0:
                _add_node("营业利润", 'profit')
                _link("毛利润", "营业利润", remaining)
                for name, val in inflows:
                    _add_node(name, 'other')
                    _link(name, "营业利润", val)

                # Level 2 → 3: 营业利润 → 营业外支出 / 所得税 / 净利润; 营业外收入 流入净利润
                if non_op_expense > 0:
                    _add_node("营业外支出", 'expense')
                    _link("营业利润", "营业外支出", non_op_expense)
                if income_tax > 0:
                    _add_node("所得税", 'tax')
                    _link("营业利润", "所得税", income_tax)
                to_net = operating_profit - non_op_expense - income_tax
                if net_profit is not None and net_profit > 0 and to_net > 0:
                    _add_node("净利润", 'profit')
                    _link("营业利润", "净利润", to_net)
                    if non_op_income > 0:
                        _add_node("营业外收入", 'other')
                        _link("营业外收入", "净利润", non_op_income)
                elif net_profit is not None and net_profit < 0:
                    _add_node("净亏损", 'revenue')
                    _link("营业利润", "净亏损", abs(net_profit))

        if not links:
            return self._insufficient(period, year, "数据不足以构建成本流向图")

        # Scale
        scale = _detect_value_scale([revenue_total, cost_total, expense_total, abs(net_profit or 0)])

        # Pre-scale link values so labels display consistently (e.g. "0.35万" not "3,515")
        divisor = scale['divisor']
        if divisor > 1:
            for lk in links:
                lk['value'] = round(lk['value'] / divisor, 2)

        # KPIs
        outgo = cost_total + expense_total + income_tax
        expense_ratio = round(outgo / revenue_total * 100, 1)
        net_margin = pnl.net_margin().value
        gross_margin = pnl.gross_margin().value

        kpis = [
            {"label": "营业收入", "value": self._format_value(revenue_total, scale), "unit": "元", "trend": "flat"},
            {"label": "费用占比", "value": f"{expense_ratio}%", "unit": "", "trend": self._trend_from_value(-expense_ratio + 70)},  # noqa: E501
            {"label": "净利率", "value": f"{net_margin}%" if net_margin is not None else "-", "unit": "",
             "trend": self._trend_from_value(net_margin)},
        ]

        # ECharts option
        option = self._base_echarts_option()
        option.pop("dataZoom", None)
        option.pop("toolbox", None)
        option["animationEasing"] = "elasticOut"
        option["animationDuration"] = 1000
        option.update({
            "series": [{
                "type": "sankey",
                "layout": "none",
                "orient": "horizontal",
                "nodeWidth": 22,
                "nodeGap": 14,
                "draggable": True,
                "emphasis": {
                    "focus": "adjacency",
                    "blurScope": "global",
                },
                "lineStyle": {
                    "color": "gradient",
                    "curveness": 0.5,
                    "opacity": 0.45,
                },
                "label": {
                    "show": True,
                    "fontSize": 11,
                    "color": "#333",
                    "fontWeight": "bold",
                    "formatter": f"__FMT__sankey_financial_label_{scale['suffix']}" if scale['suffix'] else "__FMT__sankey_financial_label",  # noqa: E501
                },
                "data": nodes,
                "links": links,
                "levels": [
                    {
                        "depth": 0,
                        "itemStyle": {"borderWidth": 2, "borderColor": "#fff"},
                        "lineStyle": {"opacity": 0.5},
                    },
                    {
                        "depth": 1,
                        "itemStyle": {"borderWidth": 1, "borderColor": "#fff"},
                        "lineStyle": {"opacity": 0.4},
                    },
                    {
                        "depth": 2,
                        "itemStyle": {"borderWidth": 1, "borderColor": "#fff"},
                        "lineStyle": {"opacity": 0.35},
                    },
                    {
                        "depth": 3,
                        "itemStyle": {"borderWidth": 1, "borderColor": "#fff"},
                        "lineStyle": {"opacity": 0.3},
                    },
                ],
            }],
            "tooltip": {
                "trigger": "item",
                "confine": True,
                "backgroundColor": "rgba(255,255,255,0.96)",
                "borderColor": "#e5e7eb",
                "borderWidth": 1,
                "borderRadius": 8,
                "padding": [12, 16],
                "textStyle": {"color": "#374151", "fontSize": 13},
                "extraCssText": "box-shadow: 0 4px 20px rgba(0,0,0,0.12);",
                "formatter": f"__FMT__sankey_financial_tooltip_{scale['suffix']}" if scale['suffix'] else "__FMT__sankey_financial_tooltip",  # noqa: E501
            },
        })

        # Margin annotation
        option["graphic"] = [{
            "type": "text",
            "right": "5%",
            "top": "3%",
            "style": {
                "text": f"毛利率: {gross_margin if gross_margin is not None else '-'}%  |  "
                        f"净利率: {net_margin if net_margin is not None else '-'}%",
                "fontSize": 12,
                "fill": COLORS['primary'],
                "fontWeight": "bold",
            },
            "silent": True,
        }]

        # Analysis context
        analysis_parts = [
            f"{year}年成本流向分析:",
            f"营业收入{self._format_value(revenue_total, scale)}元",
            f"营业成本占收入{round(cost_total / revenue_total * 100, 1)}%",
            f"费用合计占收入{round(expense_total / revenue_total * 100, 1)}%",
            f"净利率{net_margin if net_margin is not None else '-'}%",
        ]

        result = {
            "chartType": self.chart_type,
            "title": f"{year}年{self.display_name}",
            "kpis": kpis,
            "echartsOption": option,
            "tableData": {
                "headers": ["流向", "金额", "占收入比"],
                "rows": [{"label": lk["source"] + " → " + lk["target"], "values": [
                    round(lk["value"], 2),
                    f"{round(lk['value'] / (revenue_total / divisor) * 100, 1)}%"
                ]} for lk in links],
            },
            "analysisContext": " ".join(analysis_parts),
            "metadata": {
                "period": period,
                "scale": scale,
                "grossMargin": gross_margin,
                "netMargin": net_margin,
                "expenseRatio": expense_ratio,
                "dataQuality": "good",
            },
        }
        return _sanitize_for_json(result)
