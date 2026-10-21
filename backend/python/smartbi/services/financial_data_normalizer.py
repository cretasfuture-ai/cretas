"""Financial Data Normalizer — 上传表格 → 标准明细。

财务看板所有图表只读这里产出的标准明细, 不再各自解析原始表格。每一行:

    year, month, quarter      期间 (表里没写年份时取请求年份)
    item                      科目或行标签原文
    subject_class, subject_level   科目类别与层级, 见 financial_subjects.py
    category, product, channel, business_unit   维度 (没有就是空串)
    budget, actual, last_year, last_year_budget 金额, 统一换算成「元」; 缺失为 None, 不补 0

解析时处理的格式: 表头单位「（万元）/(千元)/亿元」、日期「2026-01-15 / 2026/1/15 / 2026年1月」、
百分比文本「25%」、千分位「1,234」、会计负数「(100)」。
"""
import re
import logging
from datetime import date, datetime
from typing import Any, Dict, List, Optional, Tuple
from dataclasses import dataclass, field
import pandas as pd
import numpy as np

from . import financial_subjects as S

logger = logging.getLogger(__name__)

STANDARD_COLUMNS = [
    'year', 'month', 'quarter', 'category', 'item', 'product', 'channel', 'business_unit',
    'budget', 'actual', 'last_year', 'last_year_budget', 'subject_class', 'subject_level',
]

_UNIT_PATTERN = re.compile(r'[（(]\s*(亿元|万元|千元|元|亿|万|千)\s*[)）]|(亿元|万元|千元)$')
_UNIT_FACTORS = {'亿元': 1e8, '亿': 1e8, '万元': 1e4, '万': 1e4, '千元': 1e3, '千': 1e3, '元': 1.0}


def header_unit(header: str) -> Tuple[str, float]:
    """「实际（万元）」 -> ("实际", 10000)。没有单位时倍率为 1。"""
    text = str(header)
    m = _UNIT_PATTERN.search(text)
    if not m:
        return text.strip(), 1.0
    unit = m.group(1) or m.group(2)
    base = (text[:m.start()] + text[m.end():]).strip()
    return base or text.strip(), _UNIT_FACTORS.get(unit, 1.0)


@dataclass
class ColumnMapping:
    """Detected column role mapping."""
    period_cols: List[str] = field(default_factory=list)      # Month/Quarter columns
    budget_cols: List[str] = field(default_factory=list)       # Budget/Target
    actual_cols: List[str] = field(default_factory=list)       # Actual/Achieved
    last_year_cols: List[str] = field(default_factory=list)    # Previous year
    last_year_budget_cols: List[str] = field(default_factory=list)
    category_col: Optional[str] = None                         # Category/Product line
    item_col: Optional[str] = None                             # P&L item name
    label_col: Optional[str] = None                            # First text column (row label)
    product_col: Optional[str] = None                          # 具体产品 / SKU
    channel_col: Optional[str] = None                          # 销售渠道
    business_unit_col: Optional[str] = None                    # 事业部 / 部门 / 分公司
    year_col: Optional[str] = None                             # 单独的年份列
    year: int = 2026
    data_layout: str = "wide"  # "wide" (months as columns) or "long" (month as row values)
    unit_multipliers: Dict[str, float] = field(default_factory=dict)  # 列 -> 换算成元的倍率
    # 不参与汇总、只用来对账的行 (小计/合计/表内利润), 由 FinancialDashboardService 填入
    reported_rows: Optional[pd.DataFrame] = None
    # 原始表格: 只给需要读标准明细之外的原始列的图表用 (如账龄分段列)
    raw_frame: Optional[pd.DataFrame] = None
    # 解析中发现、需要告诉用户的问题
    issues: List[str] = field(default_factory=list)


class FinancialDataNormalizer:
    """Detect column roles and normalize raw Excel data for financial charts."""

    # Period detection patterns
    MONTH_PATTERNS = [
        re.compile(r'^(\d{1,2})月$'),           # 1月-12月
        re.compile(r'^(\d{4})年(\d{1,2})月$'),   # 2026年1月
        re.compile(r'^(\d{4})-(\d{1,2})$'),      # 2026-1
        re.compile(r'^(\d{4})/(\d{1,2})$'),      # 2026/1
        re.compile(r'^M(\d{1,2})$', re.I),       # M1-M12
        re.compile(r'^Month\s*(\d{1,2})$', re.I),
    ]
    # 带日的日期: 2026-01-15 / 2026/1/15 / 2026年1月15日 / 2026-01-15 00:00:00
    DATE_PATTERN = re.compile(r'^(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})日?(?:[ T].*)?$')
    QUARTER_PATTERNS = [
        re.compile(r'^Q([1-4])$', re.I),
        re.compile(r'^第([一二三四])季度$'),
        re.compile(r'^(\d{4})Q([1-4])$', re.I),
    ]

    # Column NAME keywords for period columns (not value patterns — these match the header itself)
    PERIOD_COL_KEYWORDS = ['月份', '月', 'month', '期间', '时间', 'period', '日期', 'date']
    YEAR_COL_KEYWORDS = ['年份', '年度', 'year']

    # Column role keywords
    BUDGET_KEYWORDS = ['预算', '目标', 'budget', 'target', 'plan', '计划', '指标']
    ACTUAL_KEYWORDS = ['实际', '完成', 'actual', 'achieved', '达成', '本年', '本期']
    LAST_YEAR_KEYWORDS = ['上年', '去年', '同期', '上期', 'last year', 'prior', 'ly', 'previous']
    CATEGORY_KEYWORDS = ['品类', '类别', '产品线', 'category', '分类', '品种', '系列']
    PRODUCT_KEYWORDS = ['产品名称', '产品', '品名', '商品名称', '商品', 'sku', '货品', 'product']
    CHANNEL_KEYWORDS = ['渠道', 'channel']
    BUSINESS_UNIT_KEYWORDS = ['事业部', '业务单元', '部门', '分公司', '子公司', '经营单元', '大区', '区域']
    ITEM_KEYWORDS = ['项目', '科目', 'item', '名称', '摘要', '费用项']

    QUARTER_CN = {1: 'Q1', 2: 'Q2', 3: 'Q3', 4: 'Q4'}

    # ------------------------------------------------------------------ detect
    def detect_columns(self, columns: List[str], sample_data: Optional[pd.DataFrame] = None) -> ColumnMapping:
        """Auto-detect column roles from column names and sample data."""
        mapping = ColumnMapping()

        for col in columns:
            base, factor = header_unit(col)
            if factor != 1.0:
                mapping.unit_multipliers[col] = factor
            cl = base.lower().strip()
            # Check period columns — first by value pattern (e.g. "1月", "2026-1")
            if self._is_month_col(cl):
                mapping.period_cols.append(col)
                continue
            if self._is_quarter_col(cl):
                mapping.period_cols.append(col)
                continue
            if mapping.year_col is None and cl in self.YEAR_COL_KEYWORDS:
                mapping.year_col = col
                continue
            # Then by column NAME keyword (e.g. "月份", "月", "month", "期间")
            # This column contains period values in its cells, not in the header
            if any(kw in cl for kw in self.PERIOD_COL_KEYWORDS):
                mapping.period_cols.append(col)
                continue

            # Check role keywords — check last_year FIRST to avoid "去年实际" matching actual
            is_last_year = any(kw in cl for kw in self.LAST_YEAR_KEYWORDS)
            is_budget = any(kw in cl for kw in self.BUDGET_KEYWORDS)
            is_actual = any(kw in cl for kw in self.ACTUAL_KEYWORDS)

            if is_last_year and is_budget:
                mapping.last_year_budget_cols.append(col)
            elif is_last_year:
                mapping.last_year_cols.append(col)
            elif is_budget:
                mapping.budget_cols.append(col)
            elif is_actual:
                mapping.actual_cols.append(col)
            elif mapping.category_col is None and any(kw in cl for kw in self.CATEGORY_KEYWORDS):
                mapping.category_col = col
            elif mapping.channel_col is None and any(kw in cl for kw in self.CHANNEL_KEYWORDS):
                mapping.channel_col = col
            elif mapping.business_unit_col is None and any(kw in cl for kw in self.BUSINESS_UNIT_KEYWORDS):
                mapping.business_unit_col = col
            elif mapping.product_col is None and any(kw in cl for kw in self.PRODUCT_KEYWORDS):
                mapping.product_col = col
            elif mapping.item_col is None and any(kw in cl for kw in self.ITEM_KEYWORDS):
                mapping.item_col = col

        # Auto-detect label column (first non-numeric, non-period text column)
        if sample_data is not None:
            dims = {mapping.category_col, mapping.item_col, mapping.product_col}
            for col in columns:
                if col in mapping.period_cols or col == mapping.year_col:
                    continue
                if col in (mapping.channel_col, mapping.business_unit_col):
                    continue
                if col in dims and col is not None:
                    mapping.label_col = col
                    break
                if col in sample_data.columns and sample_data[col].dtype == 'object' \
                        and not self._looks_numeric(sample_data[col]):
                    mapping.label_col = col
                    break

        # Detect data layout (>=2 period columns = wide, e.g. "1月", "2月")
        if len(mapping.period_cols) >= 2:
            mapping.data_layout = "wide"  # months as columns
        else:
            mapping.data_layout = "long"  # month values in rows

        # Wide-format row-role detection: if layout is wide and we have a label column,
        # scan row values to detect budget/actual/last_year rows — this makes can_build()
        # aware that these roles exist even though they're in rows, not columns.
        if mapping.data_layout == "wide" and sample_data is not None:
            label_col = mapping.label_col or mapping.item_col or mapping.category_col
            if label_col and label_col in sample_data.columns:
                for label_val in sample_data[label_col].dropna().astype(str):
                    role = self._row_role(label_val)
                    if role == 'budget' and not mapping.budget_cols:
                        mapping.budget_cols.append(f'__row_role__:{label_val}')
                    elif role == 'last_year' and not mapping.last_year_cols:
                        mapping.last_year_cols.append(f'__row_role__:{label_val}')
                    elif role == 'actual' and not mapping.actual_cols:
                        mapping.actual_cols.append(f'__row_role__:{label_val}')

        # Detect year from column names
        for col in columns:
            m = re.search(r'(20\d{2})', str(col))
            if m:
                mapping.year = int(m.group(1))
                break

        logger.info(f"Column mapping detected: periods={len(mapping.period_cols)}, "
                    f"budget={len(mapping.budget_cols)}, actual={len(mapping.actual_cols)}, "
                    f"lastYear={len(mapping.last_year_cols)}, layout={mapping.data_layout}, "
                    f"units={mapping.unit_multipliers}")
        return mapping

    # --------------------------------------------------------------- normalize
    def normalize(self, raw_data: pd.DataFrame, column_mapping: ColumnMapping,
                  period_filter: Optional[Dict] = None) -> pd.DataFrame:
        """Normalize raw data to the standard detail format (see module docstring)."""
        if column_mapping.data_layout == "wide":
            rows = self._normalize_wide(raw_data, column_mapping)
        else:
            rows = self._normalize_long(raw_data, column_mapping)

        df = pd.DataFrame(rows, columns=[c for c in STANDARD_COLUMNS if c not in ('subject_class', 'subject_level')])
        df = self._classify_subjects(df)

        # Apply period filter
        if period_filter:
            df = self._apply_period_filter(df, period_filter)

        return df.reset_index(drop=True)

    @staticmethod
    def split_detail(df: pd.DataFrame) -> Tuple[pd.DataFrame, pd.DataFrame]:
        """(参与汇总的明细, 只用来对账的小计/合计/表内利润行)。"""
        if df.empty or 'subject_level' not in df.columns:
            return df, df.iloc[0:0]
        detail_mask = df['subject_level'] == S.DETAIL
        return df[detail_mask].reset_index(drop=True), df[~detail_mask].reset_index(drop=True)

    def _classify_subjects(self, df: pd.DataFrame) -> pd.DataFrame:
        if df.empty:
            df['subject_class'] = pd.Series(dtype=object)
            df['subject_level'] = pd.Series(dtype=object)
            return df
        infos = S.classify_items(df['item'].fillna('').astype(str).tolist())
        df['subject_class'] = [infos[str(v)].subject_class if str(v) in infos else S.UNKNOWN
                               for v in df['item'].fillna('')]
        df['subject_level'] = [infos[str(v)].level if str(v) in infos else S.DETAIL
                               for v in df['item'].fillna('')]
        # 行标签同时出现在品类/产品列里的普通销售表: 「合计」行一样不参与汇总
        for dim in ('category', 'product'):
            if dim in df.columns:
                total_mask = df[dim].fillna('').astype(str).map(S.is_total_label)
                df.loc[total_mask, 'subject_level'] = S.SUBTOTAL
        return df

    def _row_dims(self, row: pd.Series, mapping: ColumnMapping, label: str) -> Dict[str, str]:
        def text(col: Optional[str]) -> str:
            if not col:
                return ''
            v = row.get(col)
            return '' if v is None or (isinstance(v, float) and np.isnan(v)) else str(v).strip()

        category = text(mapping.category_col) if mapping.category_col else ''
        product = text(mapping.product_col)
        item = text(mapping.item_col) if mapping.item_col else ''
        if not item:
            item = product or label
        if not category and not mapping.category_col and not product:
            # 旧行为: 没有品类列时, 行标签同时当品类用 (品类结构类图表依赖它)
            category = label
        return {
            'category': category,
            'item': item,
            'product': product,
            'channel': text(mapping.channel_col),
            'business_unit': text(mapping.business_unit_col),
        }

    def _normalize_wide(self, raw_data: pd.DataFrame, mapping: ColumnMapping) -> List[Dict[str, Any]]:
        """Normalize wide-format data (months as columns)."""
        rows: List[Dict[str, Any]] = []
        label_col = mapping.label_col or mapping.item_col or mapping.category_col

        # First pass: detect row roles from labels (budget/actual/last_year)
        row_roles = {}  # index -> 'budget'|'actual'|'last_year'|'item'
        for idx, row in raw_data.iterrows():
            label = str(row.get(label_col, '')) if label_col else ''
            row_roles[idx] = self._row_role(label)

        has_role_rows = any(r in ('budget', 'actual', 'last_year') for r in row_roles.values())

        for period_col in mapping.period_cols:
            year_num, month_num = self._extract_year_month(period_col)
            if month_num is None:
                continue
            year_num = year_num or mapping.year
            quarter = f"Q{(month_num - 1) // 3 + 1}"
            col_factor = mapping.unit_multipliers.get(period_col, 1.0)

            if has_role_rows:
                # 每一行是「预算 / 实际 / 上年」中的一种: 同一期间按角色合并成一行
                merged: Dict[str, Optional[float]] = {'budget': None, 'actual': None, 'last_year': None}
                for idx, row in raw_data.iterrows():
                    role = row_roles.get(idx, 'item')
                    if role not in merged:
                        continue
                    label = str(row.get(label_col, '')) if label_col else ''
                    _, label_factor = header_unit(label)
                    val = self._scaled(row.get(period_col), col_factor * label_factor)
                    if val is not None:
                        merged[role] = (merged[role] or 0.0) + val
                rows.append({
                    'year': year_num, 'month': month_num, 'quarter': quarter,
                    'category': '', 'item': '', 'product': '', 'channel': '', 'business_unit': '',
                    'budget': merged['budget'], 'actual': merged['actual'],
                    'last_year': merged['last_year'], 'last_year_budget': None,
                })
                continue

            # 每一行是一个科目/品类, 期间列是实际值
            for _, row in raw_data.iterrows():
                label = str(row.get(label_col, '')) if label_col else ''
                _, label_factor = header_unit(label)
                dims = self._row_dims(row, mapping, label)
                rows.append({
                    'year': year_num, 'month': month_num, 'quarter': quarter, **dims,
                    'budget': None,
                    'actual': self._scaled(row.get(period_col), col_factor * label_factor),
                    'last_year': None, 'last_year_budget': None,
                })

        return rows

    def _normalize_long(self, raw_data: pd.DataFrame, mapping: ColumnMapping) -> List[Dict[str, Any]]:
        """Normalize long-format data (separate budget/actual/last_year columns)."""
        rows: List[Dict[str, Any]] = []
        label_col = mapping.label_col or mapping.item_col or mapping.category_col

        # Fallback: if no role columns detected, use first numeric column as actual
        actual_col = mapping.actual_cols[0] if mapping.actual_cols else None
        budget_col = mapping.budget_cols[0] if mapping.budget_cols else None
        last_year_col = mapping.last_year_cols[0] if mapping.last_year_cols else None
        ly_budget_col = mapping.last_year_budget_cols[0] if mapping.last_year_budget_cols else None

        if not actual_col and not budget_col and not last_year_col:
            # No role columns found — pick first numeric column as actual
            known_cols = set(mapping.period_cols)
            for c in (mapping.category_col, mapping.item_col, mapping.label_col, mapping.product_col,
                      mapping.channel_col, mapping.business_unit_col, mapping.year_col):
                if c:
                    known_cols.add(c)
            for c in raw_data.columns:
                if c not in known_cols and (raw_data[c].dtype in ('int64', 'float64')
                                            or self._looks_numeric(raw_data[c])):
                    actual_col = c
                    break

        def value(row: pd.Series, col: Optional[str]) -> Optional[float]:
            if not col:
                return None
            return self._scaled(row.get(col), mapping.unit_multipliers.get(col, 1.0))

        for _, row in raw_data.iterrows():
            label = str(row.get(label_col, '')) if label_col else ''
            dims = self._row_dims(row, mapping, label)

            # Extract month (and year) from period column if exists
            year_num, month_num = None, None
            if mapping.period_cols:
                year_num, month_num = self._extract_year_month(row.get(mapping.period_cols[0]))
            if mapping.year_col:
                explicit = self._parse_year(row.get(mapping.year_col))
                if explicit:
                    year_num = explicit

            rows.append({
                'year': year_num or mapping.year,
                'month': month_num or 0,
                'quarter': f"Q{(month_num - 1) // 3 + 1}" if month_num else '',
                **dims,
                'budget': value(row, budget_col),
                'actual': value(row, actual_col),
                'last_year': value(row, last_year_col),
                'last_year_budget': value(row, ly_budget_col),
            })

        return rows

    def _apply_period_filter(self, df: pd.DataFrame, period_filter: Dict) -> pd.DataFrame:
        """Filter by period.

        period_type:
          all          全部数据, 不按年也不按月过滤 (「全部历史」)
          year         只保留 year 那一年
          month        year 年的 start_month 月
          quarter      year 年的 quarter (Q1..Q4) 或 start_month 所在季度
          month_range  year 年的 start_month..end_month
        """
        ptype = period_filter.get('period_type', 'year')
        if df.empty or ptype == 'all':
            return df
        year = period_filter.get('year')
        if year is not None and 'year' in df.columns:
            df = df[df['year'] == int(year)]
        if ptype == 'year':
            return df
        if ptype == 'month':
            m = period_filter.get('start_month', 1)
            return df[df['month'] == m]
        if ptype == 'quarter':
            q = period_filter.get('quarter') or f"Q{(int(period_filter.get('start_month', 1)) - 1) // 3 + 1}"
            return df[df['quarter'] == q]
        if ptype == 'month_range':
            s = period_filter.get('start_month', 1)
            e = period_filter.get('end_month', 12)
            return df[(df['month'] >= s) & (df['month'] <= e)]
        return df

    # ------------------------------------------------------------ primitives
    def _row_role(self, label: str) -> str:
        ll = str(label).lower()
        if any(kw in ll for kw in ['预算', '目标', 'budget', 'target', '计划']):
            return 'budget'
        if any(kw in ll for kw in ['上年', '去年', '同期', 'last year', '上期']):
            return 'last_year'
        if any(kw in ll for kw in ['实际', '完成', 'actual', '达成', '本年']):
            return 'actual'
        return 'item'

    def _is_month_col(self, col_lower: str) -> bool:
        return any(p.match(col_lower) for p in self.MONTH_PATTERNS) or bool(self.DATE_PATTERN.match(col_lower))

    def _is_quarter_col(self, col_lower: str) -> bool:
        return any(p.match(col_lower) for p in self.QUARTER_PATTERNS)

    def _extract_month(self, text: Any) -> Optional[int]:
        return self._extract_year_month(text)[1]

    def _extract_year_month(self, value: Any) -> Tuple[Optional[int], Optional[int]]:
        """「1月」→(None,1); 「2026年1月」「2026-01」「2026-01-15」→(2026,1); 日期对象同理。"""
        if value is None:
            return None, None
        if isinstance(value, (pd.Timestamp, datetime, date)):
            if isinstance(value, pd.Timestamp) and pd.isna(value):
                return None, None
            return value.year, value.month
        if isinstance(value, float) and np.isnan(value):
            return None, None
        text = str(value).strip()
        m = self.DATE_PATTERN.match(text)
        if m:
            month = int(m.group(2))
            return (int(m.group(1)), month) if 1 <= month <= 12 else (None, None)
        for p in self.MONTH_PATTERNS:
            m = p.match(text)
            if m:
                groups = m.groups()
                month = int(groups[-1])  # Last group is always month
                if not 1 <= month <= 12:
                    return None, None
                year = int(groups[0]) if len(groups) == 2 else None
                return year, month
        return None, None

    @staticmethod
    def _parse_year(value: Any) -> Optional[int]:
        if value is None:
            return None
        m = re.search(r'(19|20)\d{2}', str(value))
        return int(m.group(0)) if m else None

    @classmethod
    def _scaled(cls, val: Any, factor: float) -> Optional[float]:
        v = cls._safe_float(val)
        if v is None:
            return None
        return v * factor if factor != 1.0 else v

    @staticmethod
    def _safe_float(val) -> Optional[float]:
        """数字文本 -> float。「25%」→ 25.0 (百分点); 「(100)」→ -100; 「-」/空 → None。"""
        if val is None or (isinstance(val, float) and np.isnan(val)):
            return None
        if isinstance(val, (int, float, np.integer, np.floating)) and not isinstance(val, bool):
            v = float(val)
            return v if not np.isnan(v) else None
        text = str(val).strip().replace(',', '').replace('，', '').replace('¥', '').replace('￥', '')
        if text in ('', '-', '—', '--', 'N/A', 'n/a', 'nan', 'None'):
            return None
        negative = False
        if (text.startswith('(') and text.endswith(')')) or (text.startswith('（') and text.endswith('）')):
            negative, text = True, text[1:-1]
        if text.endswith('%'):
            text = text[:-1]
        try:
            v = float(text)
        except (ValueError, TypeError):
            return None
        if np.isnan(v):
            return None
        return -v if negative else v

    @classmethod
    def _looks_numeric(cls, series: pd.Series) -> bool:
        vals = [v for v in series.dropna().tolist() if str(v).strip() != '']
        if not vals:
            return False
        parsed = [cls._safe_float(v) for v in vals]
        return sum(p is not None for p in parsed) >= max(1, int(len(vals) * 0.8))

    def classify_pnl_item(self, item_name: str) -> str:
        """Classify a P&L item into: revenue, cost, expense, profit, tax, other.

        兼容旧调用方的粗分类; 判定本身只在 financial_subjects.py 里。
        """
        cls = S.classify_subject(item_name)
        if cls == S.REVENUE:
            return 'revenue'
        if cls == S.COST:
            return 'cost'
        if cls in S.EXPENSE_CLASSES:
            return 'expense'
        if cls in S.REPORTED_PROFIT_CLASSES:
            return 'profit'
        if cls in (S.INCOME_TAX, S.SURCHARGE):
            return 'tax'
        return 'other'

    def get_months_label(self, start_month: int, end_month: int, year: int) -> str:
        """Generate human-readable period label."""
        if start_month == 1 and end_month == 12:
            return f"{year}年全年"
        elif start_month == end_month:
            return f"{year}年{start_month}月"
        else:
            return f"{year}年{start_month}-{end_month}月"
