"""按问题出图：用户问的是哪个维度、哪个指标、哪个月，就用整份数据现算那一张图。

问题 51 (LSM-COSTBI-F, 2026-11-18)：上传数据问答里，「各产品的销售额占比是多少？」
答案文字算对了 (藤椒掌中宝 43.97% …)，图却是「Top 5 省份」柱图 + 前 50 行的「销售单价占比」
饼图；「10月份哪个客户买得最多」配的也是「Top 5 省份」。图和问题对不上，图下的分析
(ChartInsight，按图里的数现算) 也就跟着答非所问。

本模块只做确定性的分组汇总 (pandas，全量行，不抽样)，不调 LLM：
  · 维度：问题里说「产品/客户/区域/省份/门店/分类」→ 数据里对应的名称列 (不选编码列)；
  · 指标：默认金额列 (销售金额/销售额/金额/营收…，不选单价)；问「数量/件数」用数量列，
    问「成本」用成本列；
  · 月份：「10月 / 10月份」→ 按日期列筛那个月 (多年时取有该月数据的最近一年)；
  · 图型：问「占比/份额/构成/比例/分布」→ 饼图；其余 (最多/最高/排名/哪个…) → 降序柱图。
认不出维度或指标就返回 None，调用方保持原来的图，不硬造。
"""
from __future__ import annotations

import re
from typing import Any, Dict, List, Optional, Tuple

# 问题里的说法 → 语义维度
_QUERY_DIM_WORDS: List[Tuple[str, Tuple[str, ...]]] = [
    ("product", ("产品", "商品", "菜品", "品项", "sku", "SKU", "单品")),
    ("customer", ("客户", "买家", "采购方", "经销商")),
    ("category", ("分类", "类别", "品类")),
    ("store", ("门店", "店铺", "分店")),
    ("province", ("省份", "城市", "地区")),
    ("region", ("区域", "大区", "片区")),
    ("supplier", ("供应商",)),
]

# 语义维度 → 数据列名里会出现的词
_COLUMN_DIM_WORDS: Dict[str, Tuple[str, ...]] = {
    "product": ("产品名称", "商品名称", "菜品名称", "品名", "产品", "商品", "菜品", "物料名称"),
    "customer": ("客户名称", "客户", "买家", "采购方", "经销商"),
    "category": ("分类", "类别", "品类"),
    "store": ("门店名称", "门店", "店铺", "分店"),
    "province": ("省份", "城市", "地区"),
    "region": ("区域", "大区", "片区"),
    "supplier": ("供应商名称", "供应商"),
}

DIM_LABELS: Dict[str, str] = {
    "product": "产品", "customer": "客户", "category": "分类", "store": "门店",
    "province": "省份", "region": "区域", "supplier": "供应商",
}

_CODE_COLUMN_WORDS = ("编码", "编号", "代码", "ID", "id", "单号")

_AMOUNT_COLUMN_WORDS = ("销售金额", "销售额", "实收金额", "营业额", "营收", "收入", "金额")
_QTY_COLUMN_WORDS = ("销售数量", "数量", "件数", "销量")
_COST_COLUMN_WORDS = ("销售成本", "成本")
_NOT_MEASURE_WORDS = ("单价", "价格", "单位", "币种", "率", "%")

_SHARE_WORDS = ("占比", "份额", "构成", "比例", "比重", "分布", "结构")
_MONTH_RE = re.compile(r"(?<![\d年-])(1[0-2]|0?[1-9])\s*月(?:份)?(?!\s*\d{1,2}\s*[日号])")
_DATE_COLUMN_WORDS = ("日期", "时间", "date", "Date", "月份")

PIE_MAX_SLICES = 12
BAR_MAX_BARS = 10


def asked_dimension(query: str) -> Optional[str]:
    """问题里点名的语义维度 (第一个命中)。"""
    q = query or ""
    hits = []
    for sem, words in _QUERY_DIM_WORDS:
        for w in words:
            i = q.find(w)
            if i >= 0:
                hits.append((i, sem))
                break
    if not hits:
        return None
    hits.sort()
    return hits[0][1]


def _is_code_column(col: str) -> bool:
    return any(w in col for w in _CODE_COLUMN_WORDS)


def dimension_column(columns: List[str], semantic: str) -> Optional[str]:
    """数据列里对应语义维度的名称列；同一维度有多列时按词表顺序取，⛔ 不取编码列。"""
    for word in _COLUMN_DIM_WORDS.get(semantic, ()):
        for col in columns:
            name = str(col)
            if word in name and not _is_code_column(name):
                return name
    return None


def _measure_column(df, query: str) -> Optional[str]:
    import pandas as pd

    numeric = [c for c in df.columns
               if pd.api.types.is_numeric_dtype(df[c]) and not any(w in str(c) for w in _NOT_MEASURE_WORDS)]
    q = query or ""
    if any(w in q for w in ("数量", "件数", "销量", "多少件", "多少箱")):
        order = _QTY_COLUMN_WORDS + _AMOUNT_COLUMN_WORDS
    elif "成本" in q:
        order = _COST_COLUMN_WORDS + _AMOUNT_COLUMN_WORDS
    else:
        order = _AMOUNT_COLUMN_WORDS
    for word in order:
        for col in numeric:
            if word in str(col) and df[col].notna().any():
                return str(col)
    return None


def asked_month(query: str) -> Optional[int]:
    m = _MONTH_RE.search(query or "")
    return int(m.group(1)) if m else None


def _date_column(df) -> Optional[str]:
    import pandas as pd

    for col in df.columns:
        if any(w in str(col) for w in _DATE_COLUMN_WORDS):
            parsed = pd.to_datetime(df[col], errors="coerce")
            if parsed.notna().sum() >= max(1, len(df) // 2):
                return str(col)
    return None


def _filter_month(df, month: int):
    """返回 (筛后的 df, 年份)；没有日期列或该月没数据返回 (None, None)。"""
    import pandas as pd

    col = _date_column(df)
    if not col:
        return None, None
    dates = pd.to_datetime(df[col], errors="coerce")
    in_month = dates.dt.month == month
    if not in_month.any():
        return None, None
    year = int(dates[in_month].dt.year.max())
    mask = in_month & (dates.dt.year == year)
    return df[mask], year


def _fmt_num(v: float) -> str:
    if abs(v - round(v)) < 1e-9:
        return f"{int(round(v)):,}"
    return f"{v:,.2f}"


def plan_question_chart(query: str, sample_df) -> Optional[Dict[str, Any]]:
    """从问题 + 样本 (只看列名和类型) 定口径；认不出维度/指标返回 None。

    样本只用来认列，⛔ 不用来算数 (流式路径的样本只有前 200 行)。
    """
    if sample_df is None or getattr(sample_df, "empty", True) or not query:
        return None
    semantic = asked_dimension(query)
    if not semantic:
        return None
    dim_col = dimension_column([str(c) for c in sample_df.columns], semantic)
    if not dim_col:
        return None
    measure = _measure_column(sample_df, query)
    if not measure:
        return None
    month = asked_month(query)
    date_col = _date_column(sample_df) if month is not None else None
    if month is not None and not date_col:
        return None
    return {
        "semantic": semantic,
        "dimension": dim_col,
        "measure": measure,
        "month": month,
        "date_column": date_col,
        "wants_share": any(w in query for w in _SHARE_WORDS),
    }


def chart_from_groups(plan: Dict[str, Any], groups: List[Tuple[str, float]],
                      year: Optional[int], rows: int) -> Optional[Dict[str, Any]]:
    """按口径和分组合计出图；少于 2 组 (画不出对比) 返回 None。"""
    pairs = [(str(k), round(float(v), 2)) for k, v in groups if k is not None and v]
    pairs.sort(key=lambda p: p[1], reverse=True)
    if len(pairs) < 2:
        return None
    labels = [p[0] for p in pairs]
    values = [p[1] for p in pairs]
    measure = plan["measure"]
    dim_label = DIM_LABELS.get(plan["semantic"], plan["dimension"])
    period_label = f"{year}年{plan['month']}月 " if plan.get("month") and year else ""

    if plan.get("wants_share") and len(labels) <= PIE_MAX_SLICES:
        title = f"{period_label}各{dim_label}{measure}占比"
        option = {
            "title": {"text": title, "left": "center"},
            "tooltip": {"trigger": "item", "formatter": "{b}: {c} ({d}%)"},
            "legend": {"type": "scroll", "bottom": 0},
            "series": [{
                "name": measure,
                "type": "pie",
                "radius": ["38%", "66%"],
                "center": ["50%", "48%"],
                "label": {"formatter": "{b}\n{d}%"},
                "data": [{"name": n, "value": v} for n, v in zip(labels, values)],
            }],
        }
        chart_type = "pie"
    else:
        labels, values = labels[:BAR_MAX_BARS], values[:BAR_MAX_BARS]
        title = f"{period_label}各{dim_label}{measure}排名"
        option = {
            "title": {"text": title, "left": "center"},
            "tooltip": {"trigger": "axis", "axisPointer": {"type": "shadow"}},
            "grid": {"left": "3%", "right": "4%", "bottom": "3%", "containLabel": True},
            "xAxis": {"type": "category", "data": labels,
                      "axisLabel": {"interval": 0, "rotate": 20 if len(labels) > 5 else 0}},
            "yAxis": {"type": "value", "name": measure},
            "series": [{
                "name": measure,
                "type": "bar",
                "data": values,
                "label": {"show": True, "position": "top"},
            }],
        }
        chart_type = "bar"

    return {
        "type": chart_type,
        "title": title,
        "option": option,
        "question_chart": {
            "dimension": plan["dimension"],
            "measure": measure,
            "month": plan.get("month"),
            "year": year,
            "rows": int(rows),
            "groups": len(pairs),
        },
    }


def build_question_chart(query: str, df) -> Optional[Dict[str, Any]]:
    """df 就是全量数据时用 (非流式接口 / 前端直接传了数据)：pandas 现算。"""
    plan = plan_question_chart(query, df)
    if not plan:
        return None
    scope, year = df, None
    if plan["month"] is not None:
        scope, year = _filter_month(df, plan["month"])
        if scope is None or scope.empty:
            return None
    grouped = (scope.dropna(subset=[plan["dimension"]])
               .groupby(plan["dimension"], sort=False)[plan["measure"]].sum(min_count=1)
               .dropna())
    return chart_from_groups(plan, list(grouped.items()), year, len(scope))


_NUMERIC_TEXT_RE = r"^\s*-?[0-9]+(\.[0-9]+)?\s*$"
_ISO_MONTH_PREFIX_RE = re.compile(r"^\d{4}-\d{2}")


async def build_question_chart_for_upload(conn, upload_id: int, factory_id: str,
                                          query: str, sample_df) -> Optional[Dict[str, Any]]:
    """上传数据在库里：口径看样本，数在 SQL 里对整份上传汇总 (只读，带 factory_id)。"""
    plan = plan_question_chart(query, sample_df)
    if not plan:
        return None
    dim, measure, date_col = plan["dimension"], plan["measure"], plan["date_column"]
    params: List[Any] = [upload_id, factory_id, dim, measure]
    where = "d.upload_id = $1 AND u.factory_id = $2"
    year: Optional[int] = None
    if plan["month"] is not None:
        sample_dates = sample_df[date_col].dropna().astype(str).head(20)
        if sample_dates.empty or not all(_ISO_MONTH_PREFIX_RE.match(v) for v in sample_dates):
            return None  # 不是 YYYY-MM 开头的日期, 不在 SQL 里猜格式
        mm = f"{plan['month']:02d}"
        year_row = await conn.fetchrow(
            """
            SELECT MAX(substr(d.row_data ->> $3, 1, 4)) AS y
            FROM smart_bi_dynamic_data d
            JOIN smart_bi_pg_excel_uploads u ON u.id = d.upload_id
            WHERE d.upload_id = $1 AND u.factory_id = $2
              AND substr(d.row_data ->> $3, 6, 2) = $4
            """,
            upload_id, factory_id, date_col, mm,
        )
        if not year_row or not year_row["y"]:
            return None
        year = int(year_row["y"])
        params += [date_col, f"{year}-{mm}"]
        where += " AND substr(d.row_data ->> $5, 1, 7) = $6"
    rows = await conn.fetch(
        f"""
        SELECT d.row_data ->> $3 AS label,
               SUM(CASE WHEN (d.row_data ->> $4) ~ '{_NUMERIC_TEXT_RE}'
                        THEN (d.row_data ->> $4)::numeric END) AS total,
               COUNT(*) AS n
        FROM smart_bi_dynamic_data d
        JOIN smart_bi_pg_excel_uploads u ON u.id = d.upload_id
        WHERE {where} AND d.row_data ->> $3 IS NOT NULL
        GROUP BY 1
        """,
        *params,
    )
    groups = [(r["label"], float(r["total"])) for r in rows if r["total"] is not None]
    return chart_from_groups(plan, groups, year, sum(int(r["n"]) for r in rows))


def _chart_type_from_option(option: Any) -> Optional[str]:
    if not isinstance(option, dict):
        return None
    series = option.get("series")
    if isinstance(series, list) and series and isinstance(series[0], dict):
        t = series[0].get("type")
        if t in ("pie", "bar", "line"):
            return t
    return None


def normalize_cached_charts(charts: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """缓存模板的图 type 是 "echarts"、title 是 ECharts 的 title 对象 ——
    前端一图一分析 (ChartInsight) 只认 bar/line/pie，于是缓存图下面没有分析。
    按 option.series[0].type 改成真实图型，title 取文字。"""
    out = []
    for c in charts or []:
        if not isinstance(c, dict):
            continue
        item = dict(c)
        real = _chart_type_from_option(item.get("option"))
        if real and item.get("type") not in ("pie", "bar", "line"):
            item["type"] = real
        title = item.get("title")
        if isinstance(title, dict):
            item["title"] = str(title.get("text") or "")
        out.append(item)
    return out


def adapt_category_distribution(payload: Dict[str, Any], query: str) -> Dict[str, Any]:
    """「分类占比」类缓存答案按问题里点名的维度改写。

    缓存的 category_distribution 只按一个主维度 (本例「客户名称」) 算了份额，但
    data.all_dims 里每个维度的份额都算好了。问题点名的维度：
      · 在 all_dims 里且不是主维度 → 用那一维的份额重画饼图、重写一句结论；
      · 数据里根本没有 (如问「分类」，表里没有分类列) → 答案前如实说明按哪一维看的。
    """
    if not isinstance(payload, dict) or payload.get("template_code") != "category_distribution":
        return payload
    raw_data = payload.get("data")
    data: Dict[str, Any] = raw_data if isinstance(raw_data, dict) else {}
    raw_dims = data.get("all_dims")
    all_dims: Dict[str, Any] = raw_dims if isinstance(raw_dims, dict) else {}
    semantic = asked_dimension(query)
    if not semantic or not all_dims:
        return payload
    measure = str(data.get("measure") or "金额")
    target = dimension_column(list(all_dims.keys()), semantic)
    raw_shares = data.get("shares")
    shares: List[Any] = raw_shares if isinstance(raw_shares, list) else []
    primary = _primary_dim_of(data, all_dims, shares)
    asked_label = DIM_LABELS.get(semantic, semantic)

    if not target:
        if primary:
            note = f"这份数据里没有「{asked_label}」这一列，下面按{primary}看{measure}占比。"
            return {**payload, "answer": f"{note}\n{payload.get('answer') or ''}".strip()}
        return payload
    if target == primary:
        return payload

    rows = [r for r in all_dims.get(target) or [] if isinstance(r, dict) and r.get("label") is not None]
    rows = sorted(rows, key=lambda r: float(r.get("total") or 0), reverse=True)
    if len(rows) < 2:
        return payload
    total = sum(float(r.get("total") or 0) for r in rows)
    if total <= 0:
        return payload
    labels = [str(r["label"]) for r in rows][:PIE_MAX_SLICES]
    values = [round(float(r.get("total") or 0), 2) for r in rows][:PIE_MAX_SLICES]
    pct = [v / total * 100 for v in values]
    title = f"各{asked_label}{measure}占比"
    option = {
        "title": {"text": title, "left": "center"},
        "tooltip": {"trigger": "item", "formatter": "{b}: {c} ({d}%)"},
        "legend": {"type": "scroll", "bottom": 0},
        "series": [{
            "name": measure, "type": "pie", "radius": ["38%", "66%"], "center": ["50%", "48%"],
            "label": {"formatter": "{b}\n{d}%"},
            "data": [{"name": n, "value": v} for n, v in zip(labels, values)],
        }],
    }
    answer = (f"按{measure}看，{labels[0]}占 {pct[0]:.1f}%（{_fmt_num(values[0])}），排第一；"
              f"{labels[1]}占 {pct[1]:.1f}%（{_fmt_num(values[1])}）")
    if len(labels) > 2:
        answer += f"；其余 {len(labels) - 2} 个{asked_label}合计占 {sum(pct[2:]):.1f}%"
    answer += "。"
    return {
        **payload,
        "answer": answer,
        "charts": [{"type": "pie", "title": title, "option": option}],
        "data": {**data, "primary_dim": target},
    }


def _primary_dim_of(data: Dict[str, Any], all_dims: Dict[str, Any], shares: List[Any]) -> Optional[str]:
    explicit = data.get("primary_dim") or data.get("dim")
    if isinstance(explicit, str) and explicit in all_dims:
        return explicit
    share_labels = [s.get("label") for s in shares if isinstance(s, dict)]
    for dim, rows in all_dims.items():
        labels = [r.get("label") for r in rows or [] if isinstance(r, dict)]
        if share_labels and labels[:len(share_labels)] == share_labels:
            return dim
    return None
