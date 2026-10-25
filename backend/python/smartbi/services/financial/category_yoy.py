"""Category YoY Comparison Chart Builder — 各品类同期对比."""
import logging
import math
from typing import Dict, Any
import pandas as pd

from .base import (
    AbstractFinancialChartBuilder, COLORS, _sanitize_for_json, _detect_value_scale,
)
from . import metrics as M

logger = logging.getLogger(__name__)


class CategoryYoyComparisonBuilder(AbstractFinancialChartBuilder):
    """Stacked bar with category colors, current vs last year side by side per month."""

    chart_type = "category_yoy_comparison"
    display_name = "各品类同期对比"
    description = "展示各品类本年与上年同期对比，含品类分布及同比增减"
    required_columns = ['actual', 'last_year', 'category', 'period']
    display_order = 5

    def build(self, df: pd.DataFrame, column_mapping, period: Dict, year: int = 2026) -> Dict[str, Any]:
        # 业绩口径: 损益表只取收入科目, 不把成本费用一起算进品类/目标
        df, reason = M.performance_rows(df)
        if reason:
            return self._insufficient(period, year, reason)
        start_month = period.get('start_month', 1)
        end_month = period.get('end_month', 12)
        labels = self._month_labels(start_month, end_month)

        # The normaliser usually adds ``month``.  Raw factory frames can reach
        # this builder with only a Chinese period column, so derive it here
        # without changing the source frame.  Reindex below is deliberate:
        # absent months remain unknown for both compared periods,
        # while malformed numeric operands remain unavailable.
        if 'month' not in df.columns:
            period_col = next((c for c in ('月份', '期间', '日期') if c in df.columns), None)
            if period_col is None:
                return self._insufficient(period, year, '缺少月份/日期列，无法补齐所选期间')
            raw = df[period_col].astype(str)
            def month_value(value):
                if str(value).endswith('月') and str(value)[:-1].isdigit():
                    return int(str(value)[:-1])
                parsed = pd.to_datetime(value, errors='coerce')
                return parsed.month if pd.notna(parsed) and parsed.year == year else None
            derived = raw.map(month_value)
            df = df.copy()
            df['month'] = pd.to_numeric(derived, errors='coerce')
        df = df.copy()
        # Accept the raw upload aliases when this builder is exercised without
        # FinancialDashboardService's column mapping.
        aliases = {'实际': 'actual', '本年': 'actual', '上年': 'last_year', '品类': 'category'}
        for raw, canonical in aliases.items():
            if canonical not in df.columns and raw in df.columns:
                df[canonical] = df[raw]
        if not {'actual', 'last_year', 'category'}.issubset(df.columns):
            return self._insufficient(period, year, '缺少实际/上年/品类列，无法进行同期对比')
        df['month'] = pd.to_numeric(df['month'], errors='coerce')
        if df['month'].isna().any() or not df['month'].between(1, 12).all():
            return self._insufficient(period, year, '月份缺失或非法，无法核对同期期间')
        df = df[df['month'].between(start_month, end_month)]
        for col in ('actual', 'last_year'):
            df[col] = pd.to_numeric(df[col], errors='coerce')
        if df.empty:
            return self._insufficient(period, year, '所选期间没有可用品类记录')

        # Get unique categories
        categories = df['category'].dropna().unique().tolist()
        categories = [c for c in categories if c and str(c).strip() and str(c).lower() != 'nan']
        if len(categories) > 8:
            return self._insufficient(period, year, '品类超过8个，请先明确筛选范围；不静默截断品类')
        if df[['actual', 'last_year']].isna().any().any() or not df[['actual', 'last_year']].apply(lambda c: c.map(math.isfinite)).all().all():
            return self._insufficient(period, year, '同期对比含缺失或非法金额，不能部分汇总')
        if df['category'].isna().any() or df['category'].astype(str).str.strip().eq('').any():
            return self._insufficient(period, year, '品类缺失，不能静默丢弃金额')

        if not categories:
            return {
                "chartType": self.chart_type,
                "title": f"{year}年{self.display_name}",
                "kpis": [],
                "echartsOption": {},
                "tableData": None,
                "analysisContext": "无品类数据",
                "metadata": {"period": period, "dataQuality": "insufficient"},
            }

        def known_sum(values):
            valid = [float(v) for v in values if pd.notna(v)]
            return sum(valid) if valid else None

        # Pivot: month x category for actual and last_year
        cat_monthly_actual = {}
        cat_monthly_ly = {}
        for cat in categories:
            cat_df = df[df['category'] == cat]
            monthly = cat_df.groupby('month').agg(
                actual=('actual', lambda s: s.sum(min_count=1)),
                last_year=('last_year', lambda s: s.sum(min_count=1)),
            ).reindex(range(start_month, end_month + 1))
            cat_monthly_actual[cat] = monthly['actual'].tolist()
            cat_monthly_ly[cat] = monthly['last_year'].tolist()

        # Category totals for KPIs
        cat_totals = {}
        for cat in categories:
            total_actual = known_sum(cat_monthly_actual[cat])
            total_ly = known_sum(cat_monthly_ly[cat])
            yoy_rate = self._calc_growth_rate(total_actual, total_ly)
            cat_totals[cat] = {
                "actual": total_actual,
                "last_year": total_ly,
                "yoy_rate": yoy_rate,
                "diff": total_actual - total_ly,
            }

        # Overall totals
        total_actual = known_sum([ct['actual'] for ct in cat_totals.values()])
        total_ly = known_sum([ct['last_year'] for ct in cat_totals.values()])
        total_yoy = self._calc_growth_rate(total_actual, total_ly)
        scale = _detect_value_scale([v for v in (total_actual, total_ly) if v is not None])

        # Find biggest growth category
        comparable = [(name, values) for name, values in cat_totals.items() if values.get('yoy_rate') is not None]
        max_growth_cat = max(comparable, key=lambda item: item[1]['yoy_rate']) if comparable else ('-', {'yoy_rate': None})

        # Scale values
        divisor = scale['divisor']

        # Monthly totals for sparkline
        monthly_total_actual_raw = [
            known_sum(cat_monthly_actual[cat][m] for cat in categories)
            for m in range(end_month - start_month + 1)
        ]
        monthly_total_ly_raw = [
            known_sum(cat_monthly_ly[cat][m] for cat in categories)
            for m in range(end_month - start_month + 1)
        ]

        kpis = [
            {"label": "本年总额", "value": self._format_value(total_actual, scale), "unit": "元",
             "trend": self._trend_from_value(total_actual - total_ly) if total_actual is not None and total_ly is not None else 'flat',
             "sparkline": [round(v / divisor, 2) if v is not None else None for v in monthly_total_actual_raw]},
            {"label": "上年总额", "value": self._format_value(total_ly, scale), "unit": "元", "trend": "flat",
             "sparkline": [round(v / divisor, 2) if v is not None else None for v in monthly_total_ly_raw]},
            {"label": "同比增长", "value": f"{total_yoy:.1f}" if total_yoy is not None else '-',
             "unit": "%", "trend": self._trend_from_value(total_yoy)},
            {"label": "同比表现最高品类", "value": f"{max_growth_cat[0]} ({max_growth_cat[1]['yoy_rate']:.1f}%)"
             if max_growth_cat[1].get('yoy_rate') is not None else max_growth_cat[0],
             "unit": "", "trend": self._trend_from_value(max_growth_cat[1].get('yoy_rate'))},
        ]
        chart_colors = COLORS['charts']

        # Build series: for each category, two bars (current year stacked, last year stacked)
        # Use grouped approach: x-axis has paired labels like "1月-本年", "1月-上年"
        # Simpler: use sub-categories per month
        series_list = []

        # Pre-calculate monthly totals for top-of-stack labels
        monthly_total_actual_scaled = [
            round(v / divisor, 1) if v is not None else None
            for v in monthly_total_actual_raw
        ]
        monthly_total_ly_scaled = [
            round(v / divisor, 1) if v is not None else None
            for v in monthly_total_ly_raw
        ]

        # Current year stacked bars (left bar in each pair)
        for ci, cat in enumerate(categories):
            color = chart_colors[ci % len(chart_colors)]
            actual_scaled = [round(v / divisor, 2) if pd.notna(v) else None for v in cat_monthly_actual[cat]]
            series_item = {
                "name": f"{cat}(本年)",
                "type": "bar",
                "stack": "current",
                "data": actual_scaled,
                "itemStyle": {"color": self._gradient_color(color), "borderRadius": [1, 1, 0, 0] if ci == len(categories) - 1 else [0, 0, 0, 0]},  # noqa: E501
                "barMaxWidth": 24,
                "barGap": "20%",
                "emphasis": {"itemStyle": {"shadowBlur": 10, "shadowColor": "rgba(0,0,0,0.2)"}},
            }
            # Last category gets monthly total label on top of current year stack
            if ci == len(categories) - 1:
                series_item["data"] = [
                    {
                        "value": actual_scaled[m],
                        "label": {
                            "show": True,
                            "position": "top",
                            "formatter": f"{monthly_total_actual_scaled[m]}",
                            "fontSize": 9,
                            "color": "#666",
                        },
                    }
                    for m in range(len(actual_scaled))
                ]
            series_list.append(series_item)

        # Last year stacked bars (right bar in each pair)
        for ci, cat in enumerate(categories):
            color = chart_colors[ci % len(chart_colors)]
            ly_scaled = [round(v / divisor, 2) if pd.notna(v) else None for v in cat_monthly_ly[cat]]
            series_item = {
                "name": f"{cat}(上年)",
                "type": "bar",
                "stack": "lastyear",
                "data": ly_scaled,
                "itemStyle": {
                    "color": self._gradient_color(color),
                    "opacity": 0.75,
                    "borderRadius": [1, 1, 0, 0] if ci == len(categories) - 1 else [0, 0, 0, 0],
                },
                "barMaxWidth": 24,
                "emphasis": {"itemStyle": {"shadowBlur": 8, "shadowColor": "rgba(0,0,0,0.15)"}},
            }
            # Last category gets monthly total label on top of last year stack
            if ci == len(categories) - 1:
                series_item["data"] = [
                    {
                        "value": ly_scaled[m],
                        "label": {
                            "show": True,
                            "position": "top",
                            "formatter": f"{monthly_total_ly_scaled[m]}",
                            "fontSize": 9,
                            "color": "#999",
                        },
                    }
                    for m in range(len(ly_scaled))
                ]
            series_list.append(series_item)

        # Monthly total YoY% labels
        monthly_total_actual = [
            known_sum(cat_monthly_actual[cat][m] for cat in categories)
            for m in range(end_month - start_month + 1)
        ]
        monthly_total_ly = [
            known_sum(cat_monthly_ly[cat][m] for cat in categories)
            for m in range(end_month - start_month + 1)
        ]
        monthly_yoy = [
            self._calc_growth_rate(a, ly)
            for a, ly in zip(monthly_total_actual, monthly_total_ly)
        ]

        # ECharts option
        option = self._base_echarts_option()
        option["grid"] = {"left": "3%", "right": "8%", "bottom": "10%", "top": "15%", "containLabel": True}
        option.update({
            "legend": {
                "data": [series['name'] for series in series_list],
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
                "name": f"金额{scale['name_suffix']}",
                "nameTextStyle": {"fontSize": 11},
                "axisLabel": {"fontSize": 10},
                "splitLine": {"lineStyle": {"type": "dashed", "color": "#e8e8e8"}},
            },
            "series": series_list,
        })
        self._apply_datazoom(option)

        # Quarter markArea on first series
        mark_areas = self._quarter_mark_areas(start_month, end_month)
        if mark_areas and series_list:
            series_list[0]["markArea"] = {"silent": True, "data": mark_areas}

        # --- Connecting dashed lines between paired bar totals (markLine) ---
        # Add markLine on the last current-year category series to connect
        # last-year stack top to current-year stack top for each month
        if len(categories) > 0:
            # Find the last current-year series (top of current stack)
            last_cur_series_idx = len(categories) - 1
            mark_line_data = []
            for m in range(end_month - start_month + 1):
                ly_top = monthly_total_ly_scaled[m]
                cur_top = monthly_total_actual_scaled[m]
                # markLine from (m, ly_top) to (m, cur_top) — vertical connector
                mark_line_data.append([
                    {"xAxis": m, "yAxis": ly_top, "symbol": "none"},
                    {"xAxis": m, "yAxis": cur_top, "symbol": "none"},
                ])
            series_list[last_cur_series_idx]["markLine"] = {
                "silent": True,
                "symbol": "none",
                "lineStyle": {"type": "dashed", "color": "#ccc", "width": 1},
                "data": mark_line_data,
                "label": {"show": False},
                "animation": False,
            }

        # --- Large YoY% annotations BETWEEN bar pairs + per-category change arrows ---
        num_months = len(labels)
        graphic_elements = []

        # Calculate the chart area proportions for positioning
        # Grid: left=3%, right=5%, so usable width is ~92% (from 3% to 95%)
        chart_left_pct = 5.0   # approximate left edge with containLabel
        chart_right_pct = 95.0
        chart_width_pct = chart_right_pct - chart_left_pct

        for i, yoy in enumerate(monthly_yoy):
            # X position: centered on this month's pair of bars
            x_pct = chart_left_pct + (i + 0.5) / max(num_months, 1) * chart_width_pct

            if yoy is not None:
                # Large YoY percentage — positioned between the two bars at ~50% height
                color = COLORS['yoy_up'] if yoy > 0 else COLORS['yoy_down']
                arrow = "\u2191" if yoy > 0 else "\u2193"
                yoy_text = f"{yoy:.2f}%{arrow}"

                # Position at roughly 50% of the chart height (between top and bottom)
                graphic_elements.append({
                    "type": "text",
                    "left": f"{x_pct:.1f}%",
                    "top": "48%",
                    "style": {
                        "text": yoy_text,
                        "fontSize": 13,
                        "fill": color,
                        "textAlign": "center",
                        "fontWeight": "bold",
                    },
                    "silent": True,
                    "z": 100,
                })

            # Per-category change annotations — small text to the right of each bar pair
            # Stack them vertically, offset slightly right of the bar pair center
            cat_x_pct = x_pct + chart_width_pct / num_months * 0.32
            for ci, cat in enumerate(categories):
                cat_actual_val = cat_monthly_actual[cat][i] if i < len(cat_monthly_actual[cat]) else None
                cat_ly_val = cat_monthly_ly[cat][i] if i < len(cat_monthly_ly[cat]) else None
                cat_yoy = self._calc_growth_rate(cat_actual_val, cat_ly_val)
                if cat_yoy is not None:
                    cat_color = COLORS['yoy_up'] if cat_yoy > 0 else COLORS['yoy_down']
                    cat_arrow = "\u2191" if cat_yoy > 0 else "\u2193"
                    cat_text = f"{cat_arrow}{cat_yoy:.1f}%"
                    # Vertical offset: stack categories from top to bottom
                    # Place in the upper portion of chart area, spaced by category index
                    cat_top_pct = 18 + ci * 8
                    if cat_top_pct < 65:  # Only show if within chart area
                        graphic_elements.append({
                            "type": "text",
                            "left": f"{cat_x_pct:.1f}%",
                            "top": f"{cat_top_pct}%",
                            "style": {
                                "text": cat_text,
                                "fontSize": 7,
                                "fill": cat_color,
                                "textAlign": "left",
                            },
                            "silent": True,
                            "z": 90,
                        })

        if graphic_elements:
            option["graphic"] = graphic_elements

        # Table data
        table_rows = []
        for cat in categories:
            ct = cat_totals[cat]
            table_rows.append({
                "label": cat,
                "values": [
                    round(ct['actual'] / divisor, 2),
                    round(ct['last_year'] / divisor, 2),
                    round(ct['diff'] / divisor, 2),
                    f"{ct['yoy_rate']:.1f}%" if ct['yoy_rate'] is not None else '-',
                ],
            })

        table_data = {
            "headers": ["品类", f"本年{scale['name_suffix']}", f"上年{scale['name_suffix']}", f"差异{scale['name_suffix']}", "同比"],  # noqa: E501
            "rows": table_rows,
        }

        result = {
            "chartType": self.chart_type,
            "title": f"{year}年{self.display_name}",
            "kpis": kpis,
            "echartsOption": option,
            "tableData": table_data,
            "analysisContext": self.get_analysis_context({"title": f"{year}年{self.display_name}", "kpis": kpis}),
            "metadata": {"period": period, "scale": scale, "categoryCount": len(categories), "dataQuality": "good"},
        }
        return _sanitize_for_json(result)
