"""Factory-safe routing and rendering for materialized analytics."""
from __future__ import annotations

import logging
import re
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

_PATTERNS: List[Tuple[str, List[List[str]]]] = [
    ("defect_rate_top_n", [["缺陷", "不良", "质检", "合格率"], ["排名", "Top", "最多", "最高", "分析"]]),
    ("pareto_analysis", [["80", "20", "帕累托", "Pareto", "贡献", "头部"], ["分析", "占比", "集中"]]),
    ("anomaly_detection", [["异常", "离群", "偏离", "突变"], ["检测", "发现", "找出", "分析"]]),
    ("monthly_trend", [["趋势", "走势", "变化", "增长"], ["月度", "日", "周", "时间", "趋势"]]),
    ("category_distribution", [["占比", "份额", "结构", "构成", "分布"], ["金额", "营收", "销售", "收入", "成本", "数量"]]),
    ("top_n_by_dim", [["Top", "排名", "头部", "最高", "最多", "前", "第一", "最大", "末位", "差距"], ["门店", "区域", "分类", "类别", "产品", "商品", "供应商", "客户"]]),
]

_TIME_LIMITER_KEYWORDS = ("本月", "本周", "今日", "今天", "昨天", "前天", "上月", "上周", "近一周", "近一月", "近30天", "近7天", "最近一周", "最近一月", "最近30天", "最近7天")
_TIME_LIMITER_RE = re.compile(r"(?:20\d{2}[-年]\d{1,2}|(?:^|\s|、|，|,)\d{1,2}月|\d{1,2}月\d{1,2}日)")
_HARD_MODIFIERS = ("排除", "除了", "不算", "去掉", "过滤", "剔除", "只看", "只要", "为什么", "原因", "解释", "第二", "第三", "对比去年")


def _has_time_limiter(query: str) -> bool:
    return any(k in query for k in _TIME_LIMITER_KEYWORDS) or bool(_TIME_LIMITER_RE.search(query))


def _has_hard_modifier(query: str) -> bool:
    return _has_time_limiter(query) or any(k in query.lower() for k in _HARD_MODIFIERS)


def match_phrase_shortcut(query: str) -> Optional[str]:
    return None


def match_template(query: str) -> Optional[str]:
    if not query or not isinstance(query, str) or _has_hard_modifier(query):
        return None
    q = query.lower()
    for code, groups in _PATTERNS:
        if all(any(keyword.lower() in q for keyword in group) for group in groups):
            return code
    return None


async def match_template_hybrid(query: str, pool) -> Optional[str]:
    """Keyword-first routing with shared template RAG as an optional layer."""
    if not query or not isinstance(query, str) or _has_hard_modifier(query):
        return None
    keyword_code = match_template(query)
    if pool is not None:
        try:
            from smartbi.services.template_rag import hybrid_match
            result = await hybrid_match(pool, query, keyword_code=keyword_code)
            if result is not None:
                from .templates.registry import get_registry, load_all_templates
                load_all_templates()
                if result.template_code in get_registry().codes():
                    return result.template_code
        except Exception as exc:
            logger.warning("factory template RAG failed: %s", exc)
    return keyword_code


_QUERY_DIM_HINTS: Dict[str, List[str]] = {
    "store": ["门店", "店铺", "分店"],
    "channel": ["渠道", "来源"],
    "province": ["省份", "城市", "区域", "地区"],
    "category": ["分类", "类别", "品类"],
    "product": ["产品", "商品", "物料", "SKU"],
    "customer": ["客户", "采购方"],
}
_DIM_NAME_HINTS = _QUERY_DIM_HINTS


def pick_dim_from_query(query: str, available_dims: List[str]) -> Optional[str]:
    if not query or not available_dims:
        return None
    q = query.lower()
    for dim in available_dims:
        if dim and len(dim) >= 2 and dim.lower() in q:
            return dim
    for semantic, hints in _QUERY_DIM_HINTS.items():
        if any(h.lower() in q for h in hints):
            for dim in available_dims:
                if any(h.lower() in dim.lower() for h in _DIM_NAME_HINTS[semantic]):
                    return dim
    return None


def regen_top_n_for_dim(data: Dict, target_dim: str, top_n: int = 10) -> Optional[Dict]:
    all_dims = data.get("all_dims", {}) if isinstance(data, dict) else {}
    rows = all_dims.get(target_dim) if isinstance(all_dims, dict) else None
    if not isinstance(rows, list) or len(rows) < 2:
        return None
    rows = rows[:top_n]
    measure = str(data.get("measure", "总计"))
    totals = [float(r.get("total", 0) or 0) for r in rows if isinstance(r, dict)]
    if not totals:
        return None
    top = rows[0]
    top_label = top.get("label") if isinstance(top, dict) else None
    if top_label is None:
        return None
    top_total = float(top.get("total", 0) or 0)
    total = sum(totals)
    share = top_total / total * 100 if total else 0
    return {
        "insight_text": f"{target_dim} Top {len(rows)}: {top_label} 占比 {share:.1f}%。",
        "kpis": {"top_label": top_label, "top_value": top_total, "top_share_pct": round(share, 2), "dim_count": len(all_dims)},
        "top_rows": rows,
    }


def format_cached_as_sse(template_result: Dict, query: str, intent_signals: Optional[Dict] = None) -> Dict:
    """Render cached factory-safe template data without retired fallbacks."""
    code = str(template_result.get("code", ""))
    data = template_result.get("data") or {}
    kpis = dict(template_result.get("kpis") or {})
    insight = str(template_result.get("insight_text") or "")
    chart = template_result.get("chart_config")
    signals = intent_signals or {}
    requested_n = signals.get("n")
    ranking = data.get("ranking") if isinstance(data, dict) else None
    if isinstance(requested_n, int) and requested_n > 0 and isinstance(ranking, list):
        ranking = ranking[:requested_n]
        data = {**data, "ranking": ranking}
        if isinstance(chart, dict):
            chart = {**chart}
            title = dict(chart.get("title") or {})
            if "text" in title:
                title["text"] = re.sub(r"Top\s*\d+", f"Top {requested_n}", str(title["text"]))
            chart["title"] = title
            series = [dict(s) for s in chart.get("series", [])]
            for series_item in series:
                values = series_item.get("data")
                if isinstance(values, list):
                    series_item["data"] = values[-requested_n:]
            chart["series"] = series
    requested_role = signals.get("role")
    cached_role = data.get("role_type") if isinstance(data, dict) else None
    if requested_role and cached_role:
        requested_label = "收银员" if requested_role == "staff:cashier" else "服务员" if requested_role == "staff:waiter" else str(requested_role)
        if requested_label not in str(cached_role):
            insight = f"提示：你询问的是{requested_label}，缓存结果按{cached_role}统计。\n{insight}"
    if code == "top_n_by_dim" and isinstance(data, dict):
        intended = pick_dim_from_query(query, list((data.get("all_dims") or {}).keys()))
        primary = data.get("primary_dim")
        if intended and intended != primary:
            regen = regen_top_n_for_dim(data, intended, int(signals.get("n") or 10))
            if regen:
                insight, kpis = regen["insight_text"], {**kpis, **regen["kpis"]}
                data = {**data, "primary_dim": intended, "top_rows": regen["top_rows"]}
    return {
        "answer": insight,
        "charts": ([{"type": "echarts", "title": chart.get("title", ""), "option": chart}] if isinstance(chart, dict) else []),
        "kpis": kpis,
        "source": "materialized_cache",
        "template_code": code,
        "title": template_result.get("title", ""),
        "data": data,
    }
