"""Channel Analysis Horizontal Stacked Bar Chart Builder — 渠道分析."""
import logging
from typing import Dict, Any
import pandas as pd

from .base import (
    AbstractFinancialChartBuilder, COLORS, _sanitize_for_json, _detect_value_scale,
)
from . import metrics as M
from .. import financial_subjects as S

logger = logging.getLogger(__name__)

NO_CHANNEL_MESSAGE = "上传的数据里没有「渠道」列，无法做渠道分析。请在表格中加一列「渠道」（如商超、经销、电商）后重新上传。"


class ChannelAnalysisBuilder(AbstractFinancialChartBuilder):
    """Horizontal stacked bar chart showing revenue/profit by channel."""

    chart_type = "channel_analysis"
    display_name = "渠道分析"
    description = "展示各销售渠道的收入构成，水平堆叠柱状图呈现渠道贡献与占比"
    # 没有渠道列也要出这张卡片, 用来明确告诉用户缺什么 (⛔ 不按比例编造渠道)
    required_columns = ['actual']
    display_order = 11

    def build(self, df: pd.DataFrame, column_mapping, period: Dict, year: int = 2026) -> Dict[str, Any]:
        try:
            return self._do_build(df, column_mapping, period, year)
        except Exception as e:
            logger.error(f"ChannelAnalysisBuilder failed: {e}", exc_info=True)
            return {
                "chartType": self.chart_type,
                "title": f"{year}年{self.display_name}",
                "kpis": [],
                "echartsOption": {},
                "tableData": None,
                "analysisContext": f"渠道分析构建失败: {str(e)}",
                "metadata": {"period": period, "dataQuality": "error"},
            }

    def _do_build(self, df: pd.DataFrame, column_mapping, period: Dict, year: int) -> Dict[str, Any]:
        if not getattr(column_mapping, 'channel_col', None) or 'channel' not in df.columns:
            return self._empty_result(period, year, NO_CHANNEL_MESSAGE)

        # 有科目时只取收入行; 普通销售表 (没有科目) 每一行就是收入
        source = M.select(df, [S.REVENUE]) if M.has_subjects(df) else df
        source = source[source['channel'].fillna('').astype(str).str.strip() != '']
        if source.empty or source['actual'].notna().sum() == 0:
            return self._empty_result(period, year, "所选期间没有带渠道的收入数据")

        channel_data = source.groupby('channel').agg(revenue=('actual', 'sum')).reset_index()
        channel_data = channel_data[channel_data['revenue'] != 0]

        if channel_data.empty:
            return self._empty_result(period, year, "所选期间各渠道收入均为 0")

        # Sort descending by revenue
        channel_data = channel_data.sort_values('revenue', ascending=False).reset_index(drop=True)

        channels = channel_data['channel'].tolist()
        revenues = channel_data['revenue'].fillna(0).tolist()
        total_revenue = sum(revenues)

        # Calculate percentages
        percentages = [round(r / total_revenue * 100, 1) if total_revenue else 0 for r in revenues]

        # Detect value scale
        scale = _detect_value_scale(revenues)
        scaled_revenues = [round(v / scale['divisor'], 2) for v in revenues]

        # --- KPIs ---
        max_idx = 0
        max_channel = channels[max_idx] if channels else "-"
        max_pct = percentages[max_idx] if percentages else 0

        kpis = [
            {
                "label": "总收入",
                "value": self._format_value(total_revenue, scale),
                "unit": "元",
                "trend": "flat",
            },
            {
                "label": "最大渠道",
                "value": max_channel,
                "unit": "",
                "trend": "flat",
            },
            {
                "label": "最大渠道占比",
                "value": f"{max_pct}",
                "unit": "%",
                "trend": "up" if max_pct > 40 else "flat",
            },
            {
                "label": "渠道数量",
                "value": str(len(channels)),
                "unit": "个",
                "trend": "flat",
            },
        ]

        # --- ECharts option: horizontal stacked bar ---
        colors = COLORS['charts'][:len(channels)]

        # Build series — one bar series per metric (revenue + profit proxy)
        # For simplicity, show revenue bar with label showing value + pct
        series_data = []
        for i, (ch, val, pct) in enumerate(zip(channels, scaled_revenues, percentages)):
            series_data.append({
                "value": val,
                "itemStyle": {"color": self._gradient_color(colors[i % len(colors)])},
            })

        option = self._base_echarts_option()
        option.pop("dataZoom", None)
        option.update({
            "title": {
                "text": f"{year}年渠道收入分析",
                "left": "center",
                "textStyle": {"fontSize": 14, "fontWeight": "bold"},
            },
            "tooltip": {
                "trigger": "axis",
                "axisPointer": {"type": "shadow"},
                "formatter": None,  # use default
                "confine": True,
            },
            "legend": {
                "show": False,
            },
            "grid": {
                "left": "3%",
                "right": "15%",
                "bottom": "3%",
                "top": "12%",
                "containLabel": True,
            },
            "xAxis": {
                "type": "value",
                "name": f"金额{scale['name_suffix']}",
                "axisLabel": {"formatter": "{value}"},
            },
            "yAxis": {
                "type": "category",
                "data": channels,
                "inverse": True,
                "axisLabel": {"fontSize": 12},
            },
            "series": [
                {
                    "name": "收入",
                    "type": "bar",
                    "data": series_data,
                    "barWidth": "50%",
                    "label": {
                        "show": True,
                        "position": "right",
                        "formatter": lambda_unavailable_use_rich_label(),
                        "fontSize": 11,
                    },
                    "itemStyle": {
                        "borderRadius": [0, 4, 4, 0],
                    },
                }
            ],
        })

        # Replace lambda formatter with rich text config (ECharts JSON safe)
        # Use custom label with value + percentage
        label_data = []
        for val, pct in zip(scaled_revenues, percentages):
            label_data.append(f"{val:,.2f}{scale['suffix']}  ({pct}%)")

        option['series'][0]['label'] = {
            "show": True,
            "position": "right",
            "fontSize": 11,
            "color": "#333",
        }

        # Encode label text into data items
        for i, lbl in enumerate(label_data):
            if isinstance(option['series'][0]['data'][i], dict):
                option['series'][0]['data'][i]['label'] = {"formatter": lbl}
            else:
                option['series'][0]['data'][i] = {
                    "value": option['series'][0]['data'][i],
                    "label": {"formatter": lbl},
                }

        # --- Table data ---
        table_data = {
            "headers": ["渠道", f"收入{scale['name_suffix']}", "占比"],
            "rows": [],
        }
        for ch, val, pct in zip(channels, scaled_revenues, percentages):
            table_data["rows"].append([ch, f"{val:,.2f}", f"{pct}%"])

        # --- Analysis context ---
        ctx_parts = [f"{year}年渠道分析:"]
        for ch, pct in zip(channels, percentages):
            ctx_parts.append(f"{ch}占比{pct}%")
        analysis_context = " ".join(ctx_parts)

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
                "channelCount": len(channels),
                "scale": scale['suffix'],
            },
        }
        return _sanitize_for_json(result)

    def _empty_result(self, period: Dict, year: int, reason: str = "无渠道分析数据") -> Dict:
        return {
            "chartType": self.chart_type,
            "title": f"{year}年{self.display_name}",
            "kpis": [],
            "echartsOption": {},
            "tableData": None,
            "analysisContext": reason,
            "metadata": {"period": period, "dataQuality": "insufficient", "reason": reason},
        }


def lambda_unavailable_use_rich_label():
    """Placeholder — ECharts JSON doesn't support lambdas; labels are set per-item."""
    return "{c}"
