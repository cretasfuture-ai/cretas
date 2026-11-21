"""Chart Auto-Insight — Tier 2 backend service (v4 — claims-pinning architecture).

Architecture (v4):
  signature = SHA256(chartType|xDim|yMetric|agg|domain|dataPattern|permissionTier)
              NOTE: factoryId is NOT in the signature (cross-tenant signature sharing).

  get_insight serve flow:
    1. Cross-tenant guard: jwt_factory_id != ctx.factory_id → None
    2. Insufficient comparison data or missing budget tracker → explicit reason
    3. budget.check_budget blocked → BUDGET_EXHAUSTED (outside the opt-in deterministic path)
    4. _call_llm(ctx) → structured dict {claims, finding, implication, suggestion} or None
    5. _validate_claims(llm_obj, ctx) — recompute values and check numeric attribution.
       Missing or rejected model output can use a rule-generated comparison for supported charts.
    6. finance_hidden ¥ serve-gate: refuse validated output with absolute currency amounts.
    7. Return InsightResult(source="llm", tier=2)

  In-database template table (ai_insight_templates) is preserved for offline M4 curation;
  this service no longer reads from or writes to it at serve time.

🔒 RBAC (red-line):
  - factoryId ALWAYS from JWT (jwt_factory_id param), NOT from context.factory_id
  - Cross-tenant (jwt_factory_id != ctx.factory_id) → blocked immediately
  - permission_tier ALWAYS server-derived (from caller_role via API endpoint) — body ignored
  - finance_hidden callers: prompt excludes changeAmt + raw series; ¥ serve-gate hard-blocks
  - budget_tracker=None → fail-closed (no LLM, return SERVICE_UNAVAILABLE + WARN)
  - _validate_claims rejects hallucinated entity swaps and invented numbers (no-fake-data)
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import math
from common.process_secret_harness import with_process_secret_harness
import os
import re
from collections import Counter
from dataclasses import dataclass, field, replace
from enum import Enum
from typing import Any, Dict, List, Optional

from cachetools import TTLCache

from .claim_recompute import recompute_claim, infer_sign_from_prose
from ..distillation_capture import persist_distillation_sample, compute_input_hash

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Finance-sensitive yMetrics (spec §2.6)
FINANCE_METRICS: frozenset[str] = frozenset({
    "revenue", "margin", "profit", "cost",
})

# Roles allowed to see absolute ¥ values in insights (mirrors Java PRICE_VIEW_ROLES)
FINANCE_ROLES: frozenset[str] = frozenset({
    "factory_super_admin",
    "platform_admin",
    "procurement_manager",
    "finance_manager",
    "sales_manager",
    "dispatcher",
    "production_manager",
    "permission_admin",
    "department_admin",
})

# Observation verbs are allowed; causal-prescriptive verbs are BANNED (spec §2.3)
_POISON_VERB_RE = re.compile(r"复制|引流|加大|扩张|推广")

# Regex to detect literal absolute ¥ amounts (e.g. "¥12345", "500万元")
_ABSOLUTE_AMOUNT_RE = re.compile(
    r"¥\s*\d|"           # ¥ followed by digit
    r"\d+\s*[万亿]?\s*元(?!\d)",  # numeric + 元 (not slot suffix)
    re.UNICODE,
)

# Default promote threshold (retained for test import compatibility)
DEFAULT_PROMOTE_THRESHOLD = 3
_ANSWER_CACHE_TTL_SECONDS = 15 * 60
_ANSWER_CACHE_MAX_ENTRIES = 1024


# ---------------------------------------------------------------------------
# Data models
# ---------------------------------------------------------------------------

@dataclass
class ChartInsightContext:
    """All inputs needed to compute a signature and generate/fill an insight."""
    chart_type: str          # e.g. "BAR", "LINE", "PIE"
    x_dim: str               # "time"|"store"|"product"|"channel"|"category"|"other"
    y_metric: str            # "revenue"|"quantity"|"margin"|"cost"|"count"|"pct"|"other"
    aggregation: str         # "sum"|"avg"|"max"|"count"
    domain: str              # "factory"|"finance"
    data_pattern: str        # canonical bucket string e.g. "ranking:top-share:65-80:cat-count:4-8"
    permission_tier: str     # "finance_visible"|"price_hidden"|"finance_hidden"
    factory_id: str          # tenant identifier (from request body — OVERRIDDEN by JWT)
    series_values: List[float] = field(default_factory=list)
    series_labels: List[str] = field(default_factory=list)
    data_version: str = ""   # Optional source snapshot and requested date range


@dataclass
class InsightResult:
    """Structured insight output — mirrors the frontend InsightResult interface."""
    finding: str
    implication: Optional[str] = None
    suggestion: Optional[str] = None
    source: str = "template"   # "template" | "llm" | "cache" | "rules"
    tier: int = 2
    input_hash: Optional[str] = None  # G4: corpus row key — exposed so frontend can send feedback
    cache_hit: bool = False


class InsightUnavailable(str, Enum):
    INSUFFICIENT_DATA = "INSUFFICIENT_DATA"
    BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"
    SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"
    RESULT_UNAVAILABLE = "RESULT_UNAVAILABLE"


# ---------------------------------------------------------------------------
# Signature computation (spec §2.1)
# ---------------------------------------------------------------------------

def _fmt_pct(v: float) -> str:
    return f"{v:.1f}%"


def _fmt_plain_number(v: float) -> str:
    if abs(v) >= 10000:
        return f"{v / 10000:.1f}万"
    if abs(v) >= 1000:
        return f"{v:,.0f}"
    return f"{v:.1f}".rstrip("0").rstrip(".")


def _metric_label(ctx: ChartInsightContext) -> str:
    mapping = {
        "revenue": "营业额",
        "margin": "利润",
        "profit": "利润",
        "cost": "成本",
        "quantity": "数量",
        "count": "数量",
        "pct": "占比",
    }
    return mapping.get((ctx.y_metric or "").lower(), "指标")


def _is_comparison_chart(ctx: ChartInsightContext) -> bool:
    return (
        ctx.domain == "factory"
        and ctx.aggregation == "sum"
        and ctx.y_metric in {"cost", "quantity"}
        and (ctx.chart_type, ctx.x_dim) in {("LINE", "time"), ("BAR", "time"), ("BAR", "product")}
    )


def _comparison_product_names(labels: List[str]) -> List[str]:
    # Product chart inputs use «name · code» so the full labels identify distinct points.
    names = []
    for label in labels:
        name, separator, code = label.rpartition(" · ")
        has_code = separator and name.strip() and re.fullmatch(r"[A-Za-z _.-]*[0-9][A-Za-z0-9 _.-]*", code)
        names.append(name.rstrip() if has_code else label)
    counts = Counter(names)
    return [name if counts[name] == 1 else label for name, label in zip(names, labels)]


def _comparison_suggestion(
    ctx: ChartInsightContext, high_label: str, low_label: str, high: float, low: float,
) -> str:
    if ctx.y_metric == "cost":
        ratio = high / low if low > 0 else None
        if low == 0 or (ratio is not None and ratio > 20):
            baseline = "记录为0" if low == 0 else "很低"
            scope = "当天" if ctx.x_dim == "time" else "该产品"
            return (
                f"{low_label}的成本{baseline}，可能{scope}工单较少或存在缺价。"
                f"可对照{high_label}和{low_label}的工单明细，核对工单数，以及材料费和人工费哪一项相差更多。"
            )
        comparison = f"{high_label}的成本高于{low_label}"
        if ratio is not None and math.isfinite(ratio):
            if round(ratio, 1) > 1:
                comparison = f"{high_label}的成本是{low_label}的{ratio:.1f}倍"
            elif round((ratio - 1) * 100, 1) > 0:
                comparison = f"{high_label}的成本比{low_label}高{_fmt_pct((ratio - 1) * 100)}"
        return f"{comparison}。可对照这两处的工单明细，核对工单数，以及材料费和人工费哪一项相差更多。"

    comparison = f"{low_label}的数量低于{high_label}"
    if 0 <= low < high:
        decrease = (high - low) / high * 100
        if round(decrease, 1) > 0:
            comparison = f"{low_label}的数量比{high_label}少{_fmt_pct(decrease)}"
    return f"{comparison}。可打开{low_label}的报工明细，核对是否有工序漏报，以及两处的计量单位是否一致。"


def _comparison_insight(ctx: ChartInsightContext) -> Optional[InsightResult]:
    """Describe the submitted points using rules that also serve as the first stage."""
    if not _is_comparison_chart(ctx):
        return None
    if ctx.y_metric == "cost" and ctx.permission_tier != "finance_visible":
        return None

    values, labels = ctx.series_values, ctx.series_labels
    if len(values) < 2 or len(values) != len(labels) or len(set(labels)) != len(labels):
        return None
    if not all(math.isfinite(value) for value in values) or not all(label.strip() for label in labels):
        return None

    if ctx.x_dim == "product":
        labels = _comparison_product_names(labels)

    high_idx = max(range(len(values)), key=values.__getitem__)
    low_idx = min(range(len(values)), key=values.__getitem__)
    high, low = values[high_idx], values[low_idx]
    metric = "成本" if ctx.y_metric == "cost" else "数量"
    if high == low:
        finding = f"这张图里，{len(values)}条记录的{metric}相同。"
        suggestion = (
            f"可扩大日期范围，查看其他日期的{metric}是否有变化。"
            if ctx.x_dim == "time"
            else "可对照各产品的明细，核对统计范围是否一致。"
        )
    else:
        high_label, low_label = labels[high_idx], labels[low_idx]
        if ctx.x_dim == "time":
            finding = f"有记录的日期里，{high_label}的{metric}最高，{low_label}最低。"
            first, last = values[0], values[-1]
            if first > 0 and last >= 0:
                change = (last - first) / first * 100
                if math.isfinite(change) and round(abs(change), 1) > 0:
                    direction = "增加" if change > 0 else "减少"
                    finding += f"从首条记录（{labels[0]}）到末条记录（{labels[-1]}），{metric}{direction}{_fmt_pct(abs(change))}。"
        else:
            finding = f"参与比较的产品中，{high_label}的{metric}最高，{low_label}最低。"
            total = sum(values)
            if low >= 0 and math.isfinite(total):
                finding += f"{high_label}占这些产品合计{_fmt_pct(high / total * 100)}。"

        suggestion = _comparison_suggestion(ctx, high_label, low_label, high, low)

    if ctx.permission_tier == "finance_hidden" and _ABSOLUTE_AMOUNT_RE.search(f"{finding} {suggestion}"):
        return None
    return InsightResult(finding=finding, suggestion=suggestion, source="rules")


def build_rule_preview(ctx: ChartInsightContext) -> Optional[InsightResult]:
    result = _comparison_insight(ctx)
    return replace(result, tier=1) if result is not None else None


def _deterministic_insight(ctx: ChartInsightContext) -> Optional[InsightResult]:
    values = [float(v) for v in (ctx.series_values or []) if v is not None]
    labels = [str(v) for v in (ctx.series_labels or [])]
    if len(values) < 2:
        return None

    input_hash = compute_input_hash(_corpus_input_text(ctx))
    metric = _metric_label(ctx)
    chart_type = (ctx.chart_type or "").upper()
    x_dim = (ctx.x_dim or "").lower()
    total = sum(values)

    if x_dim == "time" and len(values) >= 2:
        first = values[0]
        last = values[-1]
        if first == 0:
            change_text = "首尾变化明显，但首期为 0，先看最近几个点是否连续改善"
        else:
            pct = (last - first) / abs(first) * 100
            direction = "升了" if pct > 0 else "降了" if pct < 0 else "基本持平"
            change_text = f"从开头到最后，{metric}{direction}{_fmt_pct(abs(pct))}"
        peak_idx = max(range(len(values)), key=lambda i: values[i])
        low_idx = min(range(len(values)), key=lambda i: values[i])
        peak_label = labels[peak_idx] if peak_idx < len(labels) else f"第{peak_idx + 1}项"
        low_label = labels[low_idx] if low_idx < len(labels) else f"第{low_idx + 1}项"
        return InsightResult(
            finding=f"{change_text}，最高点在{peak_label}，最低点在{low_label}。",
            implication="这说明问题不只看单个点，要看最近几期是不是同方向变化。",
            suggestion="建议先排查最高点和最低点对应的订单、产线或库存动作，再看最近 3 个点是否持续走高或走低。",
            source="template",
            tier=2,
            input_hash=input_hash,
        )

    if chart_type in {"BAR", "RANKING"} or x_dim in {"store", "product", "category"}:
        order = sorted(range(len(values)), key=lambda i: values[i], reverse=True)
        top_i = order[0]
        bot_i = order[-1]
        top = values[top_i]
        bot = values[bot_i]
        top_label = labels[top_i] if top_i < len(labels) else f"第{top_i + 1}项"
        bot_label = labels[bot_i] if bot_i < len(labels) else f"第{bot_i + 1}项"
        top_share = top / total * 100 if total > 0 else 0
        if bot > 0 and top / bot < 1.05:
            return InsightResult(
                finding=f"{top_label}和{bot_label}差距很小，基本可以按同一水平看。",
                implication="这类图不适合硬分第一名和末位，重点应该看两边的产量、出成率、单价和客户结构有没有差异。",
                suggestion="建议不要因为这张图单独做大促，先用同一套备货和排产基准，再去查产量、出成率、毛利和客户结构是否有明显差别。",
                source="template",
                tier=2,
                input_hash=input_hash,
            )
        ratio_text = f"{top / bot:.1f}倍" if bot > 0 else "差距很大"
        return InsightResult(
            finding=f"{top_label}排第一，占整体{_fmt_pct(top_share)}；末位是{bot_label}，头尾大约差{ratio_text}。",
            implication="如果第一名占比过高，经营会更依赖少数客户或少数产品；如果尾部长期偏低，就会拖慢整体效率。",
            suggestion="建议把第一名当作参考样本，但重点排查末位的供给、价格、曝光和执行问题，先做一轮小范围调整再复看排名。",
            source="template",
            tier=2,
            input_hash=input_hash,
        )

    if chart_type == "PIE" or x_dim in {"channel", "platform"}:
        order = sorted(range(len(values)), key=lambda i: values[i], reverse=True)
        top_i = order[0]
        top = values[top_i]
        top_label = labels[top_i] if top_i < len(labels) else f"第{top_i + 1}项"
        top_share = top / total * 100 if total > 0 else 0
        tail_share = 100 - top_share if total > 0 else 0
        return InsightResult(
            finding=f"{top_label}是最大来源，占整体{_fmt_pct(top_share)}，其余部分合计约{_fmt_pct(tail_share)}。",
            implication="这类占比图重点看结构是否过度集中，过度集中会让整体表现更容易被单一渠道波动影响。",
            suggestion="建议保住最大来源的稳定性，同时挑第二、第三来源做提升动作，避免只靠一个渠道或平台。",
            source="template",
            tier=2,
            input_hash=input_hash,
        )

    return InsightResult(
        finding=f"这张图有 {len(values)} 个数据点，最高值是{_fmt_plain_number(max(values))}，最低值是{_fmt_plain_number(min(values))}。",
        implication="高低差已经足够明显，适合先找差异最大的维度。",
        suggestion="建议先按最高和最低两组做对比，确认是产量、价格、库存还是执行动作导致差异。",
        source="template",
        tier=2,
        input_hash=input_hash,
    )


def compute_signature(ctx: ChartInsightContext) -> str:
    """SHA256 of the canonical pipe-delimited feature string.

    U1.8: Format is chartType|xDim|yMetric|aggregation|domain|dataPattern|permissionTier
    factoryId is EXCLUDED — templates are cross-tenant shared.
    Safety: templates contain ONLY {slot} placeholders (zero tenant data).
    RBAC by construction: required_permission + permission_tier (server-derived) double-gate.
    """
    raw = "|".join([
        ctx.chart_type,
        ctx.x_dim,
        ctx.y_metric,
        ctx.aggregation,
        ctx.domain,
        ctx.data_pattern,
        ctx.permission_tier,
    ])
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def _contains_poison(text: Optional[str]) -> bool:
    """Return True iff text contains a causal-prescriptive (banned) verb."""
    if not text:
        return False
    return bool(_POISON_VERB_RE.search(text))


def _extract_json_object(text: str) -> Optional[Dict[str, Any]]:
    """Robustly extract a JSON object from an LLM response.

    LLMs (e.g. glm-5.x) often wrap JSON in ```json fences or prepend reasoning
    text, so a bare json.loads(content) raises 'Expecting value: line 1 column 1'
    and the whole Tier2 distillation silently never captures. Strip markdown
    fences, try direct parse, then fall back to the first balanced {...} block.
    Returns None (honest) when nothing parseable is found.
    """
    if not text or not text.strip():
        return None
    s = text.strip()
    # Strip markdown code fences: ```json ... ``` or ``` ... ```
    if s.startswith("```"):
        s = re.sub(r"^```[a-zA-Z]*\s*", "", s)
        s = re.sub(r"\s*```\s*$", "", s).strip()
    # Direct parse
    try:
        obj = json.loads(s)
        return obj if isinstance(obj, dict) else None
    except json.JSONDecodeError:
        pass
    # Fallback: first balanced {...} block
    start = s.find("{")
    if start == -1:
        return None
    depth = 0
    for i in range(start, len(s)):
        if s[i] == "{":
            depth += 1
        elif s[i] == "}":
            depth -= 1
            if depth == 0:
                try:
                    obj = json.loads(s[start:i + 1])
                    return obj if isinstance(obj, dict) else None
                except json.JSONDecodeError:
                    return None
    return None


def _compute_slot_values(ctx: ChartInsightContext) -> Dict[str, Any]:
    """Derive concrete slot values from ctx.series_values + series_labels.

    Computes:
      topName, botName: highest/lowest-value label
      topShare: top value as % of total
      ratio: top/bottom ratio (1 decimal)
      concLevel: concentration tier label
      growthRate, changeAmt, changeDir: trend-oriented slots
    """
    values = ctx.series_values
    labels = ctx.series_labels
    slots: Dict[str, Any] = {}

    if not values:
        return slots

    total = sum(values)
    if len(values) >= 2 and labels:
        max_idx = values.index(max(values))
        min_idx = values.index(min(values))
        top_val = values[max_idx]
        bot_val = values[min_idx]
        slots["topName"] = labels[max_idx] if max_idx < len(labels) else f"第{max_idx+1}项"
        slots["botName"] = labels[min_idx] if min_idx < len(labels) else f"第{min_idx+1}项"
        slots["topShare"] = f"{round(top_val / total * 100, 1)}" if total > 0 else "0"
        if bot_val and bot_val != 0:
            slots["ratio"] = f"{round(top_val / bot_val, 1)}"
        else:
            slots["ratio"] = "—"
        top_pct = (top_val / total * 100) if total > 0 else 0
        if top_pct >= 80:
            slots["concLevel"] = "极高"
        elif top_pct >= 65:
            slots["concLevel"] = "偏高"
        else:
            slots["concLevel"] = "适中"

    # Trend slots — ONLY meaningful for a time-ordered series. For proportion/ranking charts
    # (x_dim=channel/category/store/...), first→last is not a trend; computing growthRate there
    # produces a meaningless number the LLM then reports (and which coincidentally collides with
    # other entities' shares). Gate on x_dim == "time".
    if ctx.x_dim == "time" and len(values) >= 2:
        first, last = values[0], values[-1]
        if first and first != 0:
            change_pct = round((last - first) / abs(first) * 100, 1)
            slots["growthRate"] = f"{change_pct}"
            slots["changeAmt"] = f"{round(last - first, 1)}"
            slots["changeDir"] = "上升" if last > first else "下降" if last < first else "持平"

    return slots


# ---------------------------------------------------------------------------
# C1.2 — claims-pinning: validate claims + numeric-adjacency gate (MF1)
# ---------------------------------------------------------------------------

# Stat types where the tolerance is applied as a ratio (not percentage-points)
_RATIO_STAT_TYPES: frozenset[str] = frozenset({"ratio"})

# Stat types that use pct-point tolerance (share/derived share types, growth)
_PCT_STAT_TYPES: frozenset[str] = frozenset({
    "value", "share", "top2_share", "complement", "diff", "growth", "count",
})

# Single-entity stats: prose attributes the number to ONE entity ("{entity}占{number}").
# Entity-adjacency is enforced only for these (entity-swap is the clear danger here).
# ratio/diff are relational (two entities); top2_share/growth/count are entity-agnostic.
_SINGLE_ENTITY_STATS: frozenset[str] = frozenset({"value", "share", "complement"})

# Regex to find Arabic numbers (integers and decimals) in prose
_ARABIC_NUMBER_RE = re.compile(r"\d+\.?\d*")


def _coerce_number(v: Any) -> Optional[float]:
    """Coerce an LLM-emitted claim value to float. Handles int/float and strings like
    '62', '62.0', '62%', '1.6倍', '￥38000', '3.8万', '约62'. Returns None if uncoercible."""
    if v is None:
        return None
    if isinstance(v, bool):
        return None
    if isinstance(v, (int, float)):
        return float(v)
    if not isinstance(v, str):
        return None
    s = v.strip().replace(",", "").replace("约", "").replace("近", "").replace("超", "")
    s = s.replace("¥", "").replace("￥", "").replace("%", "").replace("倍", "")
    mult = 1.0
    if s.endswith("万"):
        mult = 10000.0
        s = s[:-1]
    elif s.endswith("亿"):
        mult = 1e8
        s = s[:-1]
    m = re.search(r"-?\d+\.?\d*", s)
    if not m:
        return None
    try:
        return float(m.group()) * mult
    except ValueError:
        return None


def _validate_claims(
    llm_obj: dict,
    ctx: "ChartInsightContext",
    tolerance_pct: float = 1.0,
    tolerance_ratio: float = 0.3,
) -> Optional[dict]:
    """C1.2 — Validate LLM structured claims by server-side recomputation + numeric-adjacency gate.

    Algorithm:
      1. For each claim in llm_obj["claims"], recompute the true value from ctx.series_*.
         If recompute returns None OR abs(claim.value - true) > tolerance → drop the claim.
      2. Numeric-adjacency gate: scan finding+implication+suggestion prose for every Arabic
         number. Each number must:
           a) Match (within tolerance) at least one valid claim's value.
           b) For entity-bearing claims (entity is not None), the nearest entity-label
              occurrence in the prose to that number must equal the claim's entity.
         Any prose number failing either check → return None (reject entire response).
      3. Empty/missing claims → return None.
      4. All checks pass → return {"finding":…, "implication":…, "suggestion":…}.

    Args:
        llm_obj: Parsed LLM response dict with keys "claims", "finding", "implication",
                 "suggestion".
        ctx: ChartInsightContext carrying series_values and series_labels.
        tolerance_pct: Absolute tolerance (in same units as the stat, e.g. percentage points)
                       for non-ratio stats.
        tolerance_ratio: Absolute tolerance for ratio-type stats.

    Returns:
        Dict with finding/implication/suggestion if all claims and prose numbers are valid.
        None otherwise.
    """
    # Collect prose fields (finding is primary; require a non-empty finding).
    finding = llm_obj.get("finding")
    if not finding or not str(finding).strip():
        logger.info("[chart-insight] validate: empty finding (keys=%s)", list(llm_obj.keys()))
        return None
    prose_fields = [str(llm_obj.get(k)) for k in ("finding", "implication", "suggestion") if llm_obj.get(k)]
    full_prose = " ".join(prose_fields)

    # --- Gate: every Arabic number in the prose must anchor to a TRUE recomputed stat ---
    # (Fable MF1: validate against the full arithmetic closure of the series, not just the
    # LLM's emitted claims — the closure is dense enough that checking doesn't throttle the
    # LLM's intelligence; an invented number matches no true stat → reject.)
    numbers = list(_ARABIC_NUMBER_RE.finditer(full_prose))
    if not numbers:
        # A data insight must cite at least one (verifiable) number.
        logger.info("[chart-insight] validate: finding has no numbers to verify → reject")
        return None

    for match in numbers:
        num_str = match.group()
        try:
            num_val = float(num_str)
        except ValueError:
            continue
        char_pos = match.start()

        candidates = _truth_candidates(num_val, ctx, tolerance_pct, tolerance_ratio)
        if not candidates:
            # The number matches no true stat of the series → invented/hallucinated → reject.
            logger.info("[chart-insight] validate: prose number %s matches no true series stat → reject",
                        num_str)
            return None

        # Entity-adjacency — only when ALL candidates are single-entity stats (share/value/
        # complement). Relational (ratio/diff) or agnostic (top2_share/growth/count) candidates
        # mean the number legitimately has no single owner → numeric anchor suffices.
        single_entity = [c for c in candidates if c[1] in _SINGLE_ENTITY_STATS and c[0] is not None]
        other = [c for c in candidates if c[1] not in _SINGLE_ENTITY_STATS]
        if single_entity and not other:
            attributed = _nearest_preceding_label(full_prose, char_pos, ctx.series_labels)
            if attributed is None:
                attributed = _nearest_label_in_prose(full_prose, char_pos, ctx.series_labels)
            if not any(ent == attributed for ent, _ in single_entity):
                logger.info("[chart-insight] validate: number %s attributed=%r ≠ true-stat entities %s → reject",
                            num_str, attributed, [ent for ent, _ in single_entity])
                return None

    # All prose numbers anchored to true stats + correctly attributed.
    return {
        "finding": llm_obj.get("finding"),
        "implication": llm_obj.get("implication"),
        "suggestion": llm_obj.get("suggestion"),
    }


def _truth_candidates(
    num_val: float, ctx: "ChartInsightContext", tol_pct: float, tol_ratio: float
) -> List[tuple]:
    """Return [(entity, stat_type), ...] whose recomputed value ≈ num_val (within tolerance).

    Scans the full recompute closure: single-entity stats over every label, plus
    entity-agnostic stats. This is what makes the LLM's claims array advisory rather than
    load-bearing — any number the LLM uses that is a TRUE stat of the series anchors here.
    """
    cands: List[tuple] = []
    labels = ctx.series_labels or []
    # NOTE: 'complement' is intentionally excluded from the closure. In a 2-element series
    # complement(B) == share(A), so "B占{share(A)}%" (an entity-swap) would falsely anchor to
    # B's complement and bypass swap detection. The LLM states each entity's own share directly,
    # so excluding complement costs nothing while keeping the swap gate sound.
    for stat in ("value", "share", "ratio", "diff"):
        tol = tol_ratio if stat in _RATIO_STAT_TYPES else tol_pct
        for ent in labels:
            tv = recompute_claim(ent, stat, ctx.series_values, labels)
            if tv is not None and abs(num_val - tv) <= tol:
                cands.append((ent, stat))
            # also accept a fraction form (LLM wrote 0.62 for 62%) for pct stats
            elif tv is not None and stat not in _RATIO_STAT_TYPES and abs(num_val * 100 - tv) <= tol:
                cands.append((ent, stat))
    agnostic = ("top2_share", "count")
    if ctx.x_dim == "time":
        agnostic = ("top2_share", "growth", "count")  # growth only meaningful for time series
    for stat in agnostic:
        tol = tol_ratio if stat in _RATIO_STAT_TYPES else tol_pct
        tv = recompute_claim(None, stat, ctx.series_values, labels)
        if tv is not None and abs(num_val - tv) <= tol:
            cands.append((None, stat))
    return cands


# Stat types where sign of the recomputed value carries directional meaning.
# growth: (last-first)/|first|*100 — positive means went up, negative means went down.
# diff:   entity_value - min_value  — always >= 0 (distance above minimum), no direction contrast.
# We only check growth (diff is always non-negative; no prose direction word is expected).
_DIRECTIONAL_STAT_TYPES: frozenset[str] = frozenset({"growth"})

_VERIFY_MARK_DIRECTION = "需核实"  # reuses fact_reconciler sentinel text


def _annotate_direction_mismatch(
    validated: dict,
    llm_obj: dict,
    ctx: "ChartInsightContext",
) -> dict:
    """A6 — Direction-word validation for growth/diff claims.

    After numeric values pass _validate_claims, scan prose direction words near each
    growth-type number. If the LLM's prose says "上涨15%" but the recomputed growth
    value is -15 (fell), the number-side passes (|15| ≈ |-15|) but the direction word
    contradicts reality → annotate finding with '需核实' + lower confidence.

    Mutates a shallow copy of validated and returns it. Never raises (all errors logged).
    """
    try:
        prose_fields = ["finding", "implication", "suggestion"]
        full_prose = " ".join(str(validated.get(k) or "") for k in prose_fields)
        if not full_prose.strip():
            return validated

        claims = llm_obj.get("claims") or []
        directional_claims = [
            c for c in claims
            if isinstance(c, dict) and c.get("stat_type") in _DIRECTIONAL_STAT_TYPES
        ]
        if not directional_claims:
            return validated

        # Recompute true sign for each directional claim
        mismatch_found = False
        for claim in directional_claims:
            entity = claim.get("entity")
            stat_type = claim.get("stat_type")
            true_val = recompute_claim(entity, stat_type, ctx.series_values, ctx.series_labels)
            if true_val is None:
                continue  # cannot verify → skip

            # Find where the absolute value of the claim appears as a number in the prose.
            claim_val_raw = claim.get("value")
            if claim_val_raw is None:
                continue
            try:
                claim_abs = abs(float(claim_val_raw))
            except (TypeError, ValueError):
                continue

            # Scan all numeric occurrences in prose; for each one close to claim_abs, check direction.
            for m in _ARABIC_NUMBER_RE.finditer(full_prose):
                try:
                    prose_num = float(m.group())
                except ValueError:
                    continue
                if abs(prose_num - claim_abs) > 1.0:  # tolerance: 1 pct-point
                    continue
                # Number matches the claim magnitude — check direction word in window
                prose_dir = infer_sign_from_prose(full_prose, m.start())
                if prose_dir is None:
                    continue  # no direction word nearby → not a directional assertion
                true_positive = true_val >= 0
                if prose_dir != true_positive:
                    logger.info(
                        "[chart-insight] direction mismatch: stat=%s true_val=%.2f "
                        "prose_dir=%s (positive=%s) at pos=%d",
                        stat_type, true_val, prose_dir, true_positive, m.start(),
                    )
                    mismatch_found = True
                    break  # one mismatch per claim is enough
            if mismatch_found:
                break

        if mismatch_found:
            result = dict(validated)
            finding = result.get("finding") or ""
            if _VERIFY_MARK_DIRECTION not in finding:
                result["finding"] = f"{finding}（注：趋势方向{_VERIFY_MARK_DIRECTION}）"
            # Lower confidence: add a low confidence hint in metadata (callers may use it)
            result["direction_flag"] = True
            return result

    except Exception as exc:
        logger.warning("[chart-insight] _annotate_direction_mismatch failed: %s", exc)

    return validated


def _nearest_preceding_label(prose: str, target_pos: int, labels: List[str]) -> Optional[str]:
    """Find the series label whose occurrence is nearest to target_pos but at-or-before it.

    Chinese BI prose attributes a number to the entity stated just before it
    ("{entity}占{number}%"). So the entity that owns a number is the closest label
    occurrence starting at index <= target_pos. Returns None if no label precedes.
    """
    best_label: Optional[str] = None
    best_start: int = -1
    for label in labels:
        if not label:
            continue
        start = 0
        while True:
            idx = prose.find(label, start)
            if idx == -1 or idx > target_pos:
                break
            if idx > best_start:  # latest-starting occurrence at-or-before target
                best_start = idx
                best_label = label
            start = idx + 1
    return best_label


def _nearest_label_in_prose(prose: str, target_pos: int, labels: List[str]) -> Optional[str]:
    """Find the series label whose occurrence in the prose is nearest (by char distance) to target_pos.

    For each label, find all occurrences in the prose. Compute the minimum char distance
    from target_pos to any occurrence of that label. Return the label with the smallest
    minimum distance.

    If a label is a substring of another (e.g. "外卖" vs "外卖平台"), we still do simple
    substring search; the shortest-distance match wins regardless of label length.

    Returns None if no label appears in the prose at all.
    """
    best_label: Optional[str] = None
    best_dist: int = len(prose) + 1  # sentinel: larger than any real distance

    for label in labels:
        if not label:
            continue
        start = 0
        while True:
            idx = prose.find(label, start)
            if idx == -1:
                break
            # Distance from target_pos to the start of this occurrence
            # (could also measure to end; start is fine for nearest-mention intent)
            dist = abs(idx - target_pos)
            if dist < best_dist:
                best_dist = dist
                best_label = label
            start = idx + 1  # look for next occurrence

    return best_label


# ---------------------------------------------------------------------------
# C2 — corpus helpers: domain mapping + stable data-rich input_text (MF2/MF4)
# ---------------------------------------------------------------------------

_DOMAIN_TO_BUSINESS_TYPE: dict = {
    "finance": "factory",
    "factory": "factory",
}


def _map_domain(domain: Optional[str]) -> str:
    """Map chart domain to corpus business_type bucket.

    finance    → "factory"   (finance charts are factory-side)
    factory    → "factory"
    anything else / None → "unknown"
    """
    if not domain:
        return "unknown"
    return _DOMAIN_TO_BUSINESS_TYPE.get(domain, "unknown")


def _chart_data(ctx: ChartInsightContext) -> dict:
    return {
        "chart_type": ctx.chart_type,
        "x_dim": ctx.x_dim,
        "y_metric": ctx.y_metric,
        "aggregation": ctx.aggregation,
        "domain": ctx.domain,
        "data_pattern": ctx.data_pattern,
        "series_values": ctx.series_values,
        "series_labels": ctx.series_labels,
        "data_version": ctx.data_version,
    }


def _json_key(value: dict) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _corpus_input_text(ctx: ChartInsightContext) -> str:
    """Canonical, tenant-scoped corpus input shared by capture and read-back."""
    return "chart_insight:v2:" + _json_key(
        {**_chart_data(ctx), "factory_id": ctx.factory_id, "permission_tier": ctx.permission_tier},
    )


# SQL for reading back a today's corpus row by input_hash (MF4).
# smart_bi_distillation_samples has NO RLS (per audit) — plain query is safe.
# The canonical input hash includes tenant and visibility tier.
# G4: also fetch metadata to check feedback_down signal; skip rows already demoted.
_CORPUS_READ_SQL = """
    SELECT teacher_output, input_hash, metadata
    FROM smart_bi_distillation_samples
    WHERE input_hash = $1
      AND source = 'chart_insight'
      AND created_at::date = CURRENT_DATE
      AND NOT COALESCE(metadata ? 'secrecy_recheck', FALSE)
      AND NOT COALESCE(metadata ? 'desensitized', FALSE)
    LIMIT 1
"""

# G4 feedback demote threshold: once a row accumulates this many downvotes, stop serving it.
_FEEDBACK_DOWN_SERVE_THRESHOLD = 2

# System prompt used in _call_llm — duplicated here so corpus persist can store it.
_SYSTEM_PROMPT = (
    "你是一名数据分析师，专门为ERP系统的图表生成结构化洞察。"
    "你的输出必须是严格的JSON格式（不含代码块）。"
    "使用观察动词（关注/排查/分析/了解），禁止因果归因动词（复制/引流/加大/扩张/推广）。"
    "如果数据不足以得出有意义的业务观察，则将claims返回空数组[]，finding返回null，不要编造。"
    "\n\n【语气风格 — 最重要】\n"
    "用大白话写，像店长或仓管在聊天，不要堆砌术语。具体要求：\n"
    "1. 禁止不解释就直接用：环比/同比/GMV/RFM/毛利率/ROI/客单价/坪效/动销率。"
    "要么换成大白话（'环比'→'比上个月'，'客单价'→'每位客人平均花多少'），"
    "要么用了立刻加括号解释（如'毛利率（卖出去的利润占收入的比例）'）。\n"
    "2. 不用英文术语。\n"
    "3. 短句、口语化，像跟人说话一样。\n"
    "4. 直接说该干什么（可落地的建议），不只罗列数据。\n"
    "5. 数字照样要准，只是用人话包起来。"
)


# ---------------------------------------------------------------------------
# Main service class
# ---------------------------------------------------------------------------

class ChartInsightService:
    """Tier 2 insight service: LLM claims-pinning serve (v4).

    🔒 RBAC contract (enforced here, not only at endpoint):
    - jwt_factory_id MUST be passed and used as the authoritative tenant identifier.
    - If jwt_factory_id != ctx.factory_id → blocked immediately (cross-tenant guard).
    - permission_tier ALWAYS server-derived (from caller_role via API endpoint) — body ignored.
    - finance_hidden: prompt excludes changeAmt + raw series; ¥ serve-gate hard-blocks output.
    - budget_tracker=None → fail-closed (return None + WARN, no LLM call).
    - _validate_claims rejects hallucinated entity swaps and invented numbers (no-fake-data).
    """

    def __init__(
        self,
        pool,  # asyncpg Pool (retained for future Task 5 corpus persist)
        budget_tracker,  # AgentBudgetTracker or compatible
        promote_threshold: int = DEFAULT_PROMOTE_THRESHOLD,
    ):
        self._pool = pool
        self._budget_tracker = budget_tracker
        self._promote_threshold = promote_threshold
        self._answers: TTLCache = TTLCache(
            maxsize=_ANSWER_CACHE_MAX_ENTRIES, ttl=_ANSWER_CACHE_TTL_SECONDS,
        )
        self._pending_answers: dict[tuple[str, str, str], asyncio.Task] = {}

    def invalidate_answer(self, factory_id: str, input_hash: str) -> None:
        for key, result in list(self._answers.items()):
            if key[0] == factory_id and result.input_hash == input_hash:
                self._answers.pop(key, None)

    # ------------------------------------------------------------------
    # Public entry point
    # ------------------------------------------------------------------

    async def get_insight(
        self,
        ctx: ChartInsightContext,
        *,
        caller_role: Optional[str] = None,
        jwt_factory_id: Optional[str] = None,
    ) -> InsightResult | InsightUnavailable | None:
        """Return verified or rule-generated text, or a reason when analysis is unavailable.

        Invalid domain and cross-tenant requests stop before the cache, budget or model.

        jwt_factory_id: factory_id from verified JWT; if provided, OVERRIDES ctx.factory_id.
        caller_role: role string from JWT for RBAC permission gating.
        """
        if ctx.domain not in {"factory", "finance"}:
            logger.warning("[chart-insight] unsupported domain=%s", ctx.domain)
            return None

        # 🔒 RBAC: JWT factory_id overrides body factory_id
        effective_factory_id = jwt_factory_id if jwt_factory_id is not None else ctx.factory_id

        # 🔒 Cross-tenant guard
        if jwt_factory_id is not None and ctx.factory_id != jwt_factory_id:
            logger.warning(
                "[chart-insight] cross-tenant blocked: jwt=%s body=%s",
                jwt_factory_id, ctx.factory_id,
            )
            return None

        resolved_ctx = replace(ctx, factory_id=effective_factory_id)

        if _is_comparison_chart(resolved_ctx) and sum(map(math.isfinite, resolved_ctx.series_values)) < 2:
            return InsightUnavailable.INSUFFICIENT_DATA

        # Scope the answer cache with the authenticated tenant and server-derived tier.
        key = (
            effective_factory_id,
            resolved_ctx.permission_tier,
            compute_input_hash(_json_key(_chart_data(resolved_ctx))),
        )
        cached = self._answers.get(key)
        if cached is not None:
            return replace(cached, cache_hit=True)

        pending = self._pending_answers.get(key)
        if pending is not None:
            result = await asyncio.shield(pending)
            return replace(result, cache_hit=True) if isinstance(result, InsightResult) else result

        # A closed browser connection may stop reading SSE while the provider is
        # still working. Finish accounting and caching under the captured tenant context.
        pending = asyncio.create_task(self._generate_and_store(key, resolved_ctx))
        self._pending_answers[key] = pending
        pending.add_done_callback(self._observe_generation_failure)
        return await asyncio.shield(pending)

    async def _generate_and_store(
        self, key: tuple[str, str, str], ctx: ChartInsightContext,
    ) -> InsightResult | InsightUnavailable:
        try:
            result = await self._generate_insight(ctx)
            if isinstance(result, InsightResult):
                self._answers[key] = result
            return result
        finally:
            self._pending_answers.pop(key, None)

    @staticmethod
    def _observe_generation_failure(pending: asyncio.Task) -> None:
        # A disconnected request may no longer await this task.
        if not pending.cancelled() and (error := pending.exception()) is not None:
            logger.error("[chart-insight] generation failed", exc_info=error)

    async def _generate_insight(self, resolved_ctx: ChartInsightContext) -> InsightResult | InsightUnavailable:
        effective_factory_id = resolved_ctx.factory_id
        # Demo/common-chart flywheel: serve accepted corpus rows or deterministic
        # templates before any LLM budget gate. This keeps demo chart narration useful
        # even when teacher-model budget is blocked, and repeat calls become cache hits.
        input_text = _corpus_input_text(resolved_ctx)
        input_hash = compute_input_hash(input_text)
        deterministic_first = os.getenv("CHART_INSIGHT_DETERMINISTIC_FIRST") == "1"
        if deterministic_first:
            cached = await self._serve_corpus_answer(input_hash, resolved_ctx.permission_tier)
            if cached is not None:
                return cached

            deterministic = _deterministic_insight(resolved_ctx)
            if deterministic is not None:
                await persist_distillation_sample(
                    self._pool,
                    source="chart_insight",
                    task_type="insights",
                    input_text=input_text,
                    teacher_output=json.dumps({
                        "finding": deterministic.finding,
                        "implication": deterministic.implication,
                        "suggestion": deterministic.suggestion,
                    }, ensure_ascii=False),
                    business_type=_map_domain(resolved_ctx.domain),
                    factory_id=resolved_ctx.factory_id,
                    system_prompt="deterministic chart insight",
                    teacher_model="deterministic-template",
                    quality=4,
                    metadata={
                        "permission_tier": resolved_ctx.permission_tier,
                        "gate": "deterministic-pre-budget",
                    },
                )
                return deterministic

        # budget_tracker None → fail-closed
        if self._budget_tracker is None:
            logger.warning(
                "[chart-insight] budget tracker unavailable — skipping the model"
            )
            return InsightUnavailable.SERVICE_UNAVAILABLE

        # Budget check
        budget = await self._budget_tracker.check_budget(effective_factory_id)
        if budget.blocked:
            logger.info("[chart-insight] budget blocked for factory=%s", effective_factory_id)
            return InsightUnavailable.BUDGET_EXHAUSTED

        if not deterministic_first:
            cached = await self._serve_corpus_answer(input_hash, resolved_ctx.permission_tier)
            if cached is not None:
                return cached

        # LLM call: returns structured {claims, finding, implication, suggestion} or None
        llm_obj = await self._call_llm(resolved_ctx)

        # C5: count tokens regardless of LLM success/failure — provider charges on attempt,
        # so budget must reflect all LLM calls to prevent undercounting.
        await self._budget_tracker.consume(effective_factory_id, 800)

        # C1.2: claims-pinning validation (recompute + numeric-adjacency gate)
        validated = _validate_claims(llm_obj, resolved_ctx) if llm_obj is not None else None
        if validated is None:
            logger.info(
                "[chart-insight] model result unavailable for factory=%s chart=%s dimension=%s reason=%s",
                effective_factory_id, resolved_ctx.chart_type, resolved_ctx.x_dim,
                "no_response" if llm_obj is None else "claims_rejected",
            )
            return _comparison_insight(resolved_ctx) or InsightUnavailable.RESULT_UNAVAILABLE

        # A6: direction-word check — annotate growth claims where prose direction ≠ recomputed sign
        validated = _annotate_direction_mismatch(validated, llm_obj, resolved_ctx)

        # C1.3 / MF5: finance_hidden ¥ serve-gate — hard-block any ¥ literal in output
        permission_tier = resolved_ctx.permission_tier
        if permission_tier == "finance_hidden":
            combined_text = " ".join(filter(None, [
                validated.get("finding"),
                validated.get("implication"),
                validated.get("suggestion"),
            ]))
            if _ABSOLUTE_AMOUNT_RE.search(combined_text):
                logger.info(
                    "[chart-insight] finance_hidden ¥ serve-gate: rejected absolute ¥ in output"
                )
                return InsightUnavailable.RESULT_UNAVAILABLE

        # C2 MF2: gate-passed → persist accepted output into corpus (fire-and-forget).
        # Only accepted outputs are stored. Rejected paths (validate None / ¥-gate None)
        # return before this point and never reach here.
        # persist_distillation_sample never raises (swallows all exceptions internally),
        # so awaiting it directly is safe and does not risk the user-facing response.
        await persist_distillation_sample(
            self._pool,
            source="chart_insight",
            task_type="insights",
            input_text=input_text,
            teacher_output=json.dumps(validated, ensure_ascii=False),
            business_type=_map_domain(resolved_ctx.domain),
            factory_id=resolved_ctx.factory_id,
            system_prompt=_SYSTEM_PROMPT,
            teacher_model=self._get_teacher_model(),
            quality=5,  # claims-pinning verified: highest quality tier
            metadata={
                "permission_tier": resolved_ctx.permission_tier,
                "gate": "passed",
            },
        )

        return InsightResult(
            finding=validated["finding"],
            implication=validated.get("implication"),
            suggestion=validated.get("suggestion"),
            source="llm",
            tier=2,
            input_hash=input_hash,  # G4: expose for feedback
        )

    # ------------------------------------------------------------------
    # C2 MF4 helpers: corpus read-back cache + teacher model name
    # ------------------------------------------------------------------

    async def _serve_corpus_answer(self, input_hash: str, permission_tier: str) -> Optional[InsightResult]:
        row = await self._read_corpus_cache(input_hash)
        if row is None:
            return None
        teacher_output, row_hash = row
        obj = _extract_json_object(teacher_output)
        if not obj or not isinstance(obj.get("finding"), str) or not obj["finding"].strip():
            return None
        finding, implication, suggestion = (obj.get(field) for field in ("finding", "implication", "suggestion"))
        fields = (finding, implication, suggestion)
        if any(value is not None and not isinstance(value, str) for value in fields):
            return None
        if permission_tier == "finance_hidden" and _ABSOLUTE_AMOUNT_RE.search(" ".join(filter(None, fields))):
            return None
        return InsightResult(
            finding=finding, implication=implication, suggestion=suggestion,
            source="cache", input_hash=row_hash, cache_hit=True,
        )

    async def _read_corpus_cache(self, input_hash: str) -> Optional[tuple]:
        """Read back today's corpus row by input_hash.

        Returns (teacher_output: str, row_input_hash: str) or None.

        Serve-skip (G4): if the row's metadata.feedback_down >= _FEEDBACK_DOWN_SERVE_THRESHOLD,
        the row has been 👎'd enough times to be excluded from serving — returns None so the
        caller falls through to LLM for a fresh answer.

        On any error (pool None, DB error, non-string result, etc.) → returns None and logs.
        Never raises — cache miss gracefully falls through to LLM.
        """
        if self._pool is None:
            return None
        try:
            async with self._pool.acquire() as conn:
                row = await conn.fetchrow(_CORPUS_READ_SQL, input_hash)
                if row is None:
                    return None
                teacher_output = row["teacher_output"]
                row_hash = row["input_hash"]
                if not isinstance(teacher_output, str):
                    # Unexpected type (e.g. mock returning int in tests) — treat as miss
                    return None
                # G4 serve-skip: check feedback_down in metadata
                raw_meta = row["metadata"]
                try:
                    meta_dict = raw_meta if isinstance(raw_meta, dict) else (
                        json.loads(raw_meta) if raw_meta else {}
                    )
                    feedback_down = int(meta_dict.get("feedback_down", 0))
                    feedback_up = int(meta_dict.get("feedback_up", 0))
                    if feedback_down >= _FEEDBACK_DOWN_SERVE_THRESHOLD or feedback_down > feedback_up:
                        if feedback_down >= _FEEDBACK_DOWN_SERVE_THRESHOLD:
                            logger.info(
                                "[chart-insight] serve-skip: row input_hash=%s... has "
                                "feedback_down=%d >= threshold=%d — falling through to LLM",
                                input_hash[:12], feedback_down, _FEEDBACK_DOWN_SERVE_THRESHOLD,
                            )
                            return None
                except Exception as meta_exc:
                    # Metadata parse failure → treat as no-feedback (safe to serve)
                    logger.debug("[chart-insight] metadata parse failed (non-blocking): %s", meta_exc)
                return (teacher_output, row_hash)
        except Exception as exc:
            logger.warning("[chart-insight] corpus cache read failed (non-blocking): %s", exc)
            return None

    def _get_teacher_model(self) -> str:
        """Return the teacher model name for corpus metadata.

        Respects the CHART_INSIGHT_LLM_SLOT env override (e.g. REVIEW → qwen3-max)
        the same way _call_llm does — so the recorded teacher_model reflects which
        model actually generated the output, not always 'chart'.

        Resolution order:
          1. Resolve effective slot: CHART_INSIGHT_LLM_SLOT env → SLOT[name], else SLOT.CHART.
          2. Ask the router for the actual model string for that slot (via get_model_for_slot
             if available).
          3. Fall back to the slot name (lower-case) if model string unavailable.
          4. Fall back to 'unknown' on any import/attribute error.
        """
        try:
            from common.llm_router import SLOT  # noqa: PLC0415
        except Exception:
            return "unknown"

        # Resolve the effective slot (mirrors _call_llm logic exactly)
        effective_slot = SLOT.CHART
        _slot_name_env = os.getenv("CHART_INSIGHT_LLM_SLOT")
        if _slot_name_env:
            try:
                effective_slot = SLOT[_slot_name_env.strip().upper()]
            except (KeyError, TypeError):
                pass  # unknown env value → keep SLOT.CHART

        # Attempt to get the actual resolved model name from the router
        try:
            from common.llm_router import get_model_for_slot  # noqa: PLC0415
            model_name = get_model_for_slot(effective_slot)
            if model_name and isinstance(model_name, str):
                return model_name
        except Exception:
            pass

        # Fall back to the slot enum value (e.g. 'review', 'chart')
        try:
            if hasattr(effective_slot, "value"):
                return str(effective_slot.value)
            return str(effective_slot).lower()
        except Exception:
            return "unknown"

    # ------------------------------------------------------------------
    # LLM call — returns structured claims dict (overridable for testing)
    # ------------------------------------------------------------------

    async def _call_llm(self, ctx: ChartInsightContext) -> Optional[Dict[str, Any]]:
        """Call the LLM and return structured {claims, finding, implication, suggestion}, or None.

        Prompt (built by _build_insight_prompt) instructs the LLM to return a structured
        claims-pinning JSON:
          {"claims":[{"entity":..,"stat_type":..,"value":..}],
           "finding":.., "implication":.., "suggestion":..}

        If data is insufficient for a meaningful observation → LLM should return claims=[] and
        finding=null (no fabrication). Returns None on LLM failure or unparseable response.
        """
        try:
            from common.llm_router import call_chain, SLOT
            from common.llm_metrics import llm_caller_context
        except ImportError:
            logger.warning("[chart-insight] llm_router not available, skipping LLM call")
            return None

        prompt = _build_insight_prompt(ctx, ctx.permission_tier)

        payload = {
            "messages": [
                {
                    "role": "system",
                    "content": with_process_secret_harness(_SYSTEM_PROMPT),
                },
                {"role": "user", "content": prompt},
            ],
            "response_format": {"type": "json_object"},
            "temperature": 0.1,
            "max_tokens": 2000,
        }

        # Distillation teacher override: the seeder/sweeps set CHART_INSIGHT_LLM_SLOT=REVIEW
        # to route corpus generation through a HIGH-QUALITY teacher (qwen3-max) instead of the
        # fast live-serve model (glm-5/SLOT.CHART). Teacher quality = corpus quality = (via
        # serve-from-corpus read-back) user-facing quality. Live serve (no env) keeps SLOT.CHART
        # for low latency. Quota: qwen3-max(REVIEW) healthy across aliyun_b/c.
        _slot = SLOT.CHART
        _slot_name = os.getenv("CHART_INSIGHT_LLM_SLOT")
        if _slot_name:
            try:
                _slot = SLOT[_slot_name.strip().upper()]
            except KeyError:
                logger.warning("[chart-insight] unknown CHART_INSIGHT_LLM_SLOT=%r, using CHART", _slot_name)
        try:
            with llm_caller_context("chart_insight", factory_id=ctx.factory_id):
                resp = await call_chain(_slot, payload)
        except Exception as exc:
            logger.warning("[chart-insight] LLM call failed: %s", exc)
            return None

        if resp is None:
            return None

        try:
            choices = resp.get("choices", [])
            if not choices:
                return None
            content = choices[0].get("message", {}).get("content", "")
            parsed = _extract_json_object(content)
            if parsed is None:
                logger.warning(
                    "[chart-insight] LLM response had no parseable JSON object (len=%d)",
                    len(content or ""),
                )
            return parsed
        except (KeyError, IndexError, TypeError) as exc:
            logger.warning("[chart-insight] LLM response parse failed: %s", exc)
            return None


# ---------------------------------------------------------------------------
# C1.3 — finance_hidden stat whitelist (MF5)
# ---------------------------------------------------------------------------

# Keys that are safe for finance_hidden callers (relative / non-absolute stats only).
# changeAmt = last - first is an absolute ¥ delta; must be excluded for finance_hidden.
# Raw series_values are also absolute — they are never put in the prompt stats dict directly,
# but _stats_for_tier enforces the whitelist at the slot-dict level.
_FINANCE_HIDDEN_STAT_WHITELIST: frozenset[str] = frozenset({
    "topName",
    "botName",
    "topShare",
    "ratio",
    "concLevel",
    "growthRate",
})


def _stats_for_tier(slots: Dict[str, Any], permission_tier: str) -> Dict[str, Any]:
    """Filter computed slot values for the given permission tier (MF5 RBAC).

    finance_hidden callers must NOT be fed absolute ¥ amounts.
    - 'changeAmt' (= last - first in series, an absolute amount) is excluded.
    - 'changeDir' is excluded when changeAmt is (it references the direction of the absolute move).
    - Raw series values are never in 'slots', so no special handling needed there.
    - All other tiers (finance_visible, price_hidden, …) receive the full slot dict unchanged.

    Args:
        slots: dict returned by _compute_slot_values(ctx).
        permission_tier: server-derived permission tier string.

    Returns:
        Filtered dict safe to feed into the LLM prompt for this tier.
    """
    if permission_tier != "finance_hidden":
        return slots  # finance_visible and any other tier: no filtering

    # finance_hidden: return only the whitelisted relative stats
    return {k: v for k, v in slots.items() if k in _FINANCE_HIDDEN_STAT_WHITELIST}


# ---------------------------------------------------------------------------
# LLM prompt builder (C1.3 — structured-claims contract)
# ---------------------------------------------------------------------------

def _build_insight_prompt(ctx: ChartInsightContext, permission_tier: str) -> str:
    """Build the structured-claims prompt for Tier2b LLM call (C1.3).

    Key changes vs the old slot-template prompt:
    - Instructs the LLM to return *structured claims* in a JSON envelope:
        {"claims":[{"entity":..,"stat_type":..,"value":..}],
         "finding":.., "implication":.., "suggestion":..}
      where stat_type ∈ {value,share,top2_share,complement,ratio,diff,growth,count}.
    - Provides the server-computed stats (tier-filtered via _stats_for_tier) as reference
      so the LLM can choose which claims to make — but number values must come from the
      stats dict, not be invented.
    - finance_hidden: raw series_values are NOT embedded; only relative stats are provided
      (MF5 — no absolute ¥ leak to the LLM which would echo them into prose).
    - finance_visible: may receive changeAmt and the full stats dict.

    Args:
        ctx: ChartInsightContext (series_values and series_labels used to compute stats).
        permission_tier: server-derived permission tier (NOT from request body).

    Returns:
        Prompt string ready to pass to the LLM.
    """
    raw_slots = _compute_slot_values(ctx)
    safe_stats = _stats_for_tier(raw_slots, permission_tier)

    # Build a human-readable stats summary from the filtered safe stats
    stats_lines = []
    for k, v in safe_stats.items():
        stats_lines.append(f"  {k}: {v}")
    stats_summary = "\n".join(stats_lines) if stats_lines else "  (无可用统计数据)"

    # For finance_visible, also provide context about value scale — but NOT for finance_hidden
    series_info = ""
    if permission_tier != "finance_hidden" and ctx.series_values:
        series_info = (
            f"\n数据规模参考（仅供理解量级，禁止直接引用原始数值到声明中）：\n"
            f"  数据点数量: {len(ctx.series_values)}\n"
            f"  维度标签: {', '.join(ctx.series_labels[:5]) if ctx.series_labels else '(无)'}\n"
        )

    return f"""请为以下图表生成结构化洞察（JSON格式）。

图表信息：
- 图表类型: {ctx.chart_type}
- X轴维度: {ctx.x_dim}
- Y轴指标: {ctx.y_metric}
- 聚合方式: {ctx.aggregation}
- 业务域: {ctx.domain}
- 数据模式: {ctx.data_pattern}

服务端已计算的统计摘要（你的声明数值必须来自这里，不得编造其他数字）：
{stats_summary}{series_info}

**输出格式**（严格JSON，禁止代码块）：
{{
  "claims": [
    {{"entity": "实体名或null（如"堂食"）", "stat_type": "share", "value": 62.0}},
    {{"entity": null, "stat_type": "top2_share", "value": 80.0}}
  ],
  "finding": "观察发现句（散文，数值必须来自上面 claims，不得引入未声明的数字）",
  "implication": "含义句（可选，可为null）",
  "suggestion": "建议句（可选，可为null；只用观察动词：关注/排查/分析/了解）"
}}

stat_type 取值范围（必须是以下之一）：
  value（绝对值）、share（占比%）、top2_share（前两名合计占比%）、
  complement（1-topShare，尾部占比%）、ratio（倍数）、diff（差值）、
  growth（增长率%）、count（数量）

重要规则：
1. claims 中的每个 value 必须来自上方"服务端已计算的统计摘要"，不得编造。
2. finding/implication/suggestion 中出现的**每个阿拉伯数字**必须在 claims 中有对应条目。
3. 建议只用观察动词（关注/排查/分析/了解），严禁因果归因（复制/引流/加大/扩张/推广）。
4. 如果数据不足以得出有意义的业务观察，claims 返回空数组 []，finding 返回 null。
5. 只返回 JSON，不要任何解释或代码块。

【语气风格 — 最重要，违反即不合格】
用大白话，像店长或仓管在聊天一样说话：
- 禁止不解释就直接用：环比/同比/GMV/毛利率/客单价/坪效/动销率等行话——要么换成大白话，要么加括号解释。
  例：❌"环比下降5.6%" → ✅"比上个月少了5.6%"
  例：❌"客单价承压" → ✅"每位客人平均花得少了"
- 不用英文缩写。
- 短句口语化，直接说该干什么，不只罗列数字。"""
