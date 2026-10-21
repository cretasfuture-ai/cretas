"""Financial Dashboard Service — Orchestrates chart generation and AI analysis."""
import logging
from typing import Dict, List, Optional, Tuple
import pandas as pd
from .financial.registry import registry, chart_succeeded
from .financial_data_normalizer import FinancialDataNormalizer, ColumnMapping
from .financial_subjects import classify_sheet_name
from .financial_headline import build_headline
from .financial.base import _sanitize_for_json

logger = logging.getLogger(__name__)


class FinancialDashboardService:
    def __init__(self):
        self.normalizer = FinancialDataNormalizer()
        self.registry = registry

    def _prepare(self, raw_data: pd.DataFrame, year: int, period_type: str,
                 start_month: int, end_month: int,
                 sheet_name: Optional[str] = None) -> Tuple[pd.DataFrame, ColumnMapping, Dict]:
        """原始表格 → (参与汇总的标准明细, 列映射, 期间)。

        小计 / 合计 / 表内利润行不进明细, 挂在 column_mapping.reported_rows 上只用来对账,
        这样任何图表按明细求和都不会重复计算。
        """
        column_mapping = self.normalizer.detect_columns(
            raw_data.columns.tolist(), raw_data
        )
        column_mapping.year = year

        df = self.normalizer.normalize(raw_data, column_mapping, {
            "period_type": period_type,
            "year": year,
            "start_month": start_month,
            "end_month": end_month,
        }, subject_hint=classify_sheet_name(sheet_name))
        detail, reported = self.normalizer.split_detail(df)
        column_mapping.reported_rows = reported
        column_mapping.raw_frame = raw_data

        if period_type == 'all':
            label = "全部历史"
        else:
            label = self.normalizer.get_months_label(start_month, end_month, year)
            if period_type == 'year':
                label = f"{year}年全年"
        period = {
            "year": year,
            "period_type": period_type,
            "start_month": start_month,
            "end_month": end_month,
            "label": label,
        }
        return detail, column_mapping, period

    def generate_chart(self, chart_type: str, raw_data: pd.DataFrame,
                       year: int = 2026, period_type: str = "year",
                       start_month: int = 1, end_month: int = 12,
                       sheet_name: Optional[str] = None) -> Dict:
        """Generate a single chart."""
        if chart_type == "all":
            return self.generate_dashboard(raw_data, year, period_type, start_month, end_month, sheet_name)

        df, column_mapping, period = self._prepare(raw_data, year, period_type, start_month, end_month,
                                                   sheet_name)

        builder = self.registry.get(chart_type)
        if not builder:
            available = self.registry.list_all()
            return {
                "success": False,
                "error": f"Unknown chart type: {chart_type}",
                "availableTypes": available,
            }

        if not builder.can_build(column_mapping):
            return {
                "success": False,
                "error": f"Insufficient data columns for {chart_type}. Required: {builder.required_columns}",
                "detectedColumns": {
                    "budget": len(column_mapping.budget_cols) > 0,
                    "actual": len(column_mapping.actual_cols) > 0,
                    "last_year": len(column_mapping.last_year_cols) > 0,
                    "category": column_mapping.category_col is not None,
                    "item": column_mapping.item_col is not None or column_mapping.label_col is not None,
                },
            }

        result = builder.build(df, column_mapping, period, year)
        result['success'] = chart_succeeded(result)
        return _sanitize_for_json(result)

    def generate_dashboard(self, raw_data: pd.DataFrame,
                           year: int = 2026, period_type: str = "year",
                           start_month: int = 1, end_month: int = 12,
                           sheet_name: Optional[str] = None) -> Dict:
        """Generate all available charts."""
        df, column_mapping, period = self._prepare(raw_data, year, period_type, start_month, end_month,
                                                   sheet_name)

        charts = self.registry.build_all_available(df, column_mapping, period, year)
        available = self.registry.list_available(column_mapping)

        return _sanitize_for_json({
            "success": True,
            # 页首一句话结论 (规则生成, 与图表同一指标库; 没有可说的事实时为 None)
            "headline": build_headline(df, column_mapping.value_kind, period["label"]),
            "charts": charts,
            "availableTypes": available,
            "period": period,
            "totalCharts": len(charts),
            "successCount": sum(1 for c in charts if c.get('success', False)),
        })

    def list_templates(self) -> List[Dict]:
        """List all registered chart types."""
        return self.registry.list_all()
