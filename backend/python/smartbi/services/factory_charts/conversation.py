"""Authenticated, grounded factory chart conversations.

The conversation layer is intentionally a thin orchestrator around the existing
factory recommendation route.  It never creates a second data loader or a
free-form SQL path: recommendations, detail contexts, price masking and
tenant checks continue to be owned by the existing route.  The LLM only sees
an opaque semantic catalogue and can return existing candidate ids.
"""
from __future__ import annotations

import asyncio
import copy
import hashlib
import json
import logging
import os
import re
import secrets
import time
import unicodedata
from collections import OrderedDict
from dataclasses import dataclass, field
from datetime import date
from typing import Any, Mapping, Optional, Sequence

from fastapi import HTTPException

from .planner_catalog import catalog_limit, rule_selection, shortlist

logger = logging.getLogger(__name__)

CONTEXT_TTL_SECONDS = 300.0
MAX_CONTEXTS = 128
MAX_HISTORY = 8
MAX_SELECTED_CHARTS = 4
MAX_PLANNER_CATALOG = 512
PLANNER_CACHE_TTL_SECONDS = 600.0
MAX_PLANNER_CACHE_ENTRIES = 128
PLANNER_DEADLINE_SECONDS = 20.0
BACKGROUND_PLANNER_DEADLINE_SECONDS = 60.0
_PLAN_CACHE: OrderedDict[tuple[str, str], tuple[float, tuple[str, ...], dict[str, str]]] = OrderedDict()
_planner_monotonic = time.monotonic
_BLACKLAKE_UNIT_COST_GAP = "黑湖数据缺少制造成本，无法计算单位成本"
_cache_monotonic = time.monotonic


class _OwnedHistory(list):
    """Carry the authenticated owner through the route's existing history argument.

    Only turns are serialized to the conversation store or the provider.
    """

    def __init__(self, turns, owner):
        super().__init__(turns)
        self.owner = owner


@dataclass
class ConversationContext:
    context_id: str
    factory_id: str
    user_id: int
    role: str
    source: str
    upload_id: Optional[int]
    scope: dict[str, Any]
    selected_ids: list[str] = field(default_factory=list)
    history: list[dict[str, Any]] = field(default_factory=list)
    unresolved_product: bool = False
    unresolved_period: bool = False
    period_error: Optional[str] = None
    expires_at: float = 0.0
    version: int = 0
    lock: Any = field(default_factory=asyncio.Lock, repr=False)
    lease_token: str = ""
    persisted_version: int = 0

    def __post_init__(self):
        self.history = _OwnedHistory(self.history, (self.user_id, self.role))


_CONTEXTS: dict[str, ConversationContext] = {}


def clear_contexts() -> None:
    """Clear in-process contexts for tests and worker lifecycle hooks."""
    _CONTEXTS.clear()
    _PLAN_CACHE.clear()


def _purge(now: Optional[float] = None) -> None:
    current = time.monotonic() if now is None else now
    for key, context in list(_CONTEXTS.items()):
        if context.expires_at <= current:
            _CONTEXTS.pop(key, None)


def _owner(request: Any) -> tuple[str, int, str]:
    factory_id = getattr(request.state, "factory_id", None)
    raw_user = getattr(request.state, "user_id", None)
    role = getattr(request.state, "role", None)
    try:
        user_id = int(raw_user)
    except (TypeError, ValueError):
        user_id = 0
    if not factory_id:
        raise HTTPException(401, "Authenticated tenant identity required")
    if user_id <= 0:
        raise HTTPException(401, "Authenticated user identity required")
    if not role:
        raise HTTPException(403, "Authenticated role required")
    return str(factory_id), user_id, str(role)


def _new_context(owner: tuple[str, int, str], source: str, upload_id: Optional[int],
                 scope: Mapping[str, Any], subjects: Sequence[Mapping[str, Any]],
                 recommendations: Sequence[Mapping[str, Any]] = (), catalog: Sequence[Mapping[str, Any]] = (),
                 question: str = "", unresolved_product: bool = False) -> ConversationContext:
    _purge()
    # Evict the oldest context.  This bound applies before inserting the new
    # value and prevents an unbounded conversation-memory cache.
    if len(_CONTEXTS) >= MAX_CONTEXTS:
        oldest = min(_CONTEXTS.values(), key=lambda item: item.expires_at)
        _CONTEXTS.pop(oldest.context_id, None)
    context_id = secrets.token_urlsafe(24)
    context = ConversationContext(
        context_id=context_id,
        factory_id=owner[0], user_id=owner[1], role=owner[2],
        source=source, upload_id=upload_id, scope=dict(scope),
        selected_ids=[],
        history=[],
        unresolved_product=bool(unresolved_product),
        expires_at=time.monotonic() + CONTEXT_TTL_SECONDS,
    )
    _CONTEXTS[context_id] = context
    return context


def _context_for(context_id: str, owner: tuple[str, int, str]) -> ConversationContext:
    _purge()
    context = _CONTEXTS.get(context_id)
    if context is None:
        raise HTTPException(410, "对话上下文已过期或不可用")
    if (context.factory_id, context.user_id, context.role) != owner:
        raise HTTPException(403, "无权访问该对话上下文")
    context.expires_at = time.monotonic() + CONTEXT_TTL_SECONDS
    return context


def _canonical_family(candidate: Mapping[str, Any]) -> str:
    analysis = candidate.get("analysis")
    if isinstance(analysis, Mapping) and analysis.get("familyId"):
        return str(analysis["familyId"])
    raw = str(candidate.get("id", ""))
    return raw.split("@", 1)[0].split(":", 1)[0]


def _public_family_label(value: Any, family_id: str, metric: str) -> Optional[str]:
    """Return only server-owned human metadata, never an opaque fallback."""
    if not isinstance(value, str):
        return None
    label = value.strip()
    if not label or label == family_id:
        return None
    # Unknown engine families currently fall back to an id-shaped metric or
    # the final technical segment.  Keep those out of both UI and planner.
    if re.fullmatch(r"[A-Za-z0-9_.:-]+", label) and re.search(r"[._:-]", label):
        return None
    family_suffix = re.sub(r"[_-]+", " ", family_id.rsplit(".", 1)[-1]).strip()
    if family_suffix and label.casefold() == family_suffix.casefold():
        return None
    return label


def semantic_catalog(recommendations: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """Strip values/rows while retaining the complete eligible chart catalogue."""
    from smartbi.services.external_production import SOURCE_KEY

    result: list[dict[str, Any]] = []
    for candidate in recommendations:
        cid = candidate.get("id")
        if not isinstance(cid, str) or not cid:
            continue
        chart = candidate.get("chart") if isinstance(candidate.get("chart"), Mapping) else {}
        analysis = candidate.get("analysis") if isinstance(candidate.get("analysis"), Mapping) else {}
        family_id = str(analysis.get("familyId") or _canonical_family(candidate))
        metric = str(candidate.get("aiMetric") or candidate.get("metric") or "")
        # Candidate titles may include a product value.  The planner does not
        # need them; IDs, family, chart type, domain and unit are sufficient.
        item = {
            "id": cid,
            "familyId": family_id,
            "chartType": str(chart.get("chartType") or ""),
            "domain": str(candidate.get("domain") or ""),
            "metric": metric,
            "unit": str(candidate.get("unit") or ""),
            "priority": str(candidate.get("priority") or ""),
            "sourceKey": str(candidate.get("source") or "").split(" · ", 1)[0],
        }
        # ``familyLabel`` is server-owned semantic metadata.  Keep it public
        # for catalogue rendering, but never derive it from candidate titles
        # (which may contain product values) or put those values in the planner
        # catalogue.
        family_label = _public_family_label(analysis.get("familyLabel"), family_id, metric)
        if family_label:
            item["familyLabel"] = family_label
        if item["sourceKey"] == SOURCE_KEY and analysis.get("sourceQuantityUnit"):
            item["sourceQuantityUnit"] = str(analysis["sourceQuantityUnit"])
        result.append(item)
    return result


def _catalog_by_id(recommendations: Sequence[Mapping[str, Any]]) -> dict[str, Mapping[str, Any]]:
    return {str(item.get("id")): item for item in recommendations if item.get("id")}


def _parse_llm_json(content: Any) -> Mapping[str, Any]:
    if not isinstance(content, str):
        raise ValueError("planner content is not text")
    text = content.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text).strip()
    value = json.loads(text)
    if not isinstance(value, Mapping):
        raise ValueError("planner response is not an object")
    return value


def _sanitized_history(history: Sequence[Mapping[str, Any]] | None,
                       aliases: Optional[Mapping[str, str]] = None) -> list[dict[str, Any]]:
    """Keep dialogue intent, not old values, rows, or numeric answers."""
    result = []
    for item in list(history or [])[-MAX_HISTORY:]:
        if not isinstance(item, Mapping):
            continue
        question = str(item.get("question", ""))[:240]
        question = re.sub(r"\d+(?:\.\d+)?%?", "[number]", question)
        ids = item.get("selectedIds")
        if not isinstance(ids, list):
            ids = []
        result.append({"question": question, "selectedIds": [aliases.get(str(value), "") if aliases else str(value)
                                                               for value in ids[:MAX_SELECTED_CHARTS]]})
    return result


def _plan_cache_key(factory_id, question, catalog, history, subjects, required_families, limit, background):
    owner = history.owner if isinstance(history, _OwnedHistory) else None
    return factory_id, hashlib.sha256(json.dumps({
        "owner": owner,
        "question": question,
        "catalog": catalog,
        "subjects": subjects,
        "requiredFamilies": sorted(required_families),
        "history": _sanitized_history(history),
        "limit": limit,
        "background": background,
    }, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()


async def plan(question: str, catalog: Sequence[Mapping[str, Any]],
               history: Sequence[Mapping[str, Any]] | None = None,
               factory_id: Optional[str] = None,
               authorized_subjects: Sequence[Mapping[str, Any]] = (),
               authorized_recommendations: Sequence[Mapping[str, Any]] = (),
               *, background: bool | None = None) -> tuple[list[str], dict[str, Any]]:
    """Plan with Rex by default, or with Aurora for designated background callers."""
    started = _planner_monotonic()
    if not catalog:
        return [], {"mode": "unavailable", "reason": "当前授权数据没有合格图表"}
    question = unicodedata.normalize("NFC", question.strip())
    authorized_catalog = list(catalog)
    semantic = authorized_catalog
    required_families: set[str] = set()
    if len(semantic) > MAX_PLANNER_CATALOG:
        return [], {"mode": "unavailable", "reason": "合格图表目录超过AI规划安全上限"}
    # The public catalog stays complete. For an explicit joint question, the
    # provider may choose only the already-validated relationship required by
    # that question. Separate charts cannot satisfy a real joint relation.
    # No candidate is invented or appended when that relationship is absent.
    if is_joint_intent(question):
        required_families = joint_family_requirements(question)
        if not required_families:
            return [], {"mode": "unavailable", "reason": "当前问题包含尚未验证的跨域关系，请分别选择已有联合分析"}
        if not required_families.issubset({item.get("familyId") for item in semantic}):
            return [], {"mode": "unavailable", "reason": "当前授权目录缺少本问题所需的已验证联合关系"}
        semantic = [item for item in semantic if item.get("familyId") in required_families]
    limit = max(len(required_families), catalog_limit(MAX_PLANNER_CATALOG))
    if background is None:
        background = os.getenv("SMARTBI_CHART_PLANNER_BACKGROUND") == "1"
    cache_key = None
    if factory_id:
        cache_key = _plan_cache_key(factory_id, question, authorized_catalog, history,
                                    authorized_subjects, required_families, limit, background)
        cached = _PLAN_CACHE.pop(cache_key, None)
        if cached is not None and cached[0] > _cache_monotonic():
            _PLAN_CACHE[cache_key] = cached
            logger.info("Factory conversation planner cache hit")
            return list(cached[1]), {**cached[2], "cache": "hit"}
    rule_catalog = semantic
    semantic = shortlist(question, semantic, required_families, limit)
    logger.info("Factory conversation planner catalogue prefilter: %d -> %d",
                len(authorized_catalog), len(semantic))
    aliases = {str(item.get("id")): "chart_" + hashlib.sha256(str(item.get("id")).encode("utf-8")).hexdigest()[:16]
               for item in semantic if item.get("id")}
    provider_semantic = [{**item, "id": aliases[str(item["id"])]} for item in semantic]
    payload = {
        "messages": [
            {
                "role": "system",
                "content": (
                    "你是工厂经营分析图表规划器。只能从给定目录选择真实id，不能新增、改写或猜测id，"
                    "最多选择4个不同familyId，selected_ids中每一项的familyId必须互异。只返回JSON对象："
                    '{"selected_ids":["目录中的id"],"reason":"简短规划理由"}。'
                    "不要计算数值，不要输出事实、SQL、表名或因果结论。"
                ),
            },
            {"role": "user", "content": json.dumps({
                "question": question,
                "history": _sanitized_history(history, aliases),
                "catalog": provider_semantic,
            }, ensure_ascii=False)},
        ],
        "temperature": 0,
        "max_tokens": 900,
        "enable_thinking": False,
    }
    from common.llm_router import (
        CHART_PLAN_ATTEMPT_TIMEOUT_SECONDS,
        CHART_PLAN_BACKGROUND_ATTEMPT_TIMEOUT_SECONDS,
        CHART_PLAN_BACKGROUND_CHAIN_TIMEOUT_SECONDS,
        CHART_PLAN_CHAIN_TIMEOUT_SECONDS,
        LLMChainExhausted,
        SLOT,
        call_chain,
    )
    if background:
        attempt_timeout = CHART_PLAN_BACKGROUND_ATTEMPT_TIMEOUT_SECONDS
        chain_timeout = CHART_PLAN_BACKGROUND_CHAIN_TIMEOUT_SECONDS
        deadline_seconds = BACKGROUND_PLANNER_DEADLINE_SECONDS
    else:
        attempt_timeout = CHART_PLAN_ATTEMPT_TIMEOUT_SECONDS
        chain_timeout = CHART_PLAN_CHAIN_TIMEOUT_SECONDS
        deadline_seconds = PLANNER_DEADLINE_SECONDS
    deadline = started + deadline_seconds
    unavailable_reason = "当前授权目录没有与问题匹配的图表"
    try:
        if len(json.dumps(payload, ensure_ascii=False).encode("utf-8")) > 50000:
            unavailable_reason = "图表规划请求超过安全上限，当前授权目录没有与问题匹配的图表"
            raise ValueError("planner payload exceeds 50000 bytes")
        from common.llm_metrics import llm_caller_context
        from common.llm_redactor import redaction_scope
        def _sensitive_values() -> dict[str, list[str]]:
            values: dict[str, list[str]] = {"product": [], "customer": [], "phone": [], "sensitive": []}
            for item in authorized_subjects:
                value = item.get("label") if isinstance(item, Mapping) else None
                if isinstance(value, str):
                    values["product"].append(value)
                    values["product"].append(value.split(" · ", 1)[0])
                    sku = item.get("sku")
                    if isinstance(sku, str) and sku:
                        values["product"].append(sku)
            for candidate in authorized_recommendations:
                for field in ("title", "label"):
                    value = candidate.get(field) if isinstance(candidate, Mapping) else None
                    if isinstance(value, str) and value.strip():
                        values["sensitive"].append(value)
                table = candidate.get("tableData") if isinstance(candidate, Mapping) else None
                rows = table.get("rows", []) if isinstance(table, Mapping) else []
                headers = table.get("headers", []) if isinstance(table, Mapping) else []
                for header in headers if isinstance(headers, list) else []:
                    if isinstance(header, str) and header.strip():
                        values["sensitive"].append(header)
                for row in rows if isinstance(rows, list) else []:
                    cells = list(zip(headers, row)) if isinstance(row, list) else list(row.items()) if isinstance(row, Mapping) else []
                    for key, value in cells:
                        if isinstance(value, str) and value.strip():
                            bucket = ("phone" if re.search(r"电话|手机|phone", str(key), re.I)
                                      else "customer" if re.search(r"客户|姓名|联系人|customer|name", str(key), re.I)
                                      else "product" if re.search(r"产品|sku|product", str(key), re.I)
                                      else "sensitive")
                            values[bucket].append(value)
                chart = candidate.get("chart") if isinstance(candidate, Mapping) else None
                def collect_chart_strings(value: Any) -> None:
                    if isinstance(value, str) and value.strip():
                        values["sensitive"].append(value)
                    elif isinstance(value, Mapping):
                        for child in value.values(): collect_chart_strings(child)
                    elif isinstance(value, list):
                        for child in value: collect_chart_strings(child)
                collect_chart_strings(chart)
            return {key: list(dict.fromkeys(items)) for key, items in values.items() if items}
        provider_lookup = {aliases[str(item["id"])]: item for item in semantic}

        def validated_selection(content: Any) -> tuple[list[str], int]:
            parsed = _parse_llm_json(content)
            if set(parsed) - {"selected_ids", "selectedIds", "reason"}:
                raise ValueError("planner envelope contains unknown fields")
            selected_aliases = parsed.get("selected_ids", parsed.get("selectedIds"))
            if not isinstance(selected_aliases, list) or not selected_aliases:
                raise ValueError("planner selection count invalid")

            families: set[str] = set()
            selected: list[str] = []
            for alias in selected_aliases:
                if not isinstance(alias, str) or alias not in provider_lookup:
                    raise ValueError("planner alias outside eligible catalogue")
                item = provider_lookup[alias]
                family = str(item.get("familyId") or alias)
                if family not in families:
                    families.add(family)
                    selected.append(str(item["id"]))

            if not 1 <= len(selected) <= MAX_SELECTED_CHARTS:
                raise ValueError("planner selection count invalid")
            if not required_families.issubset(families):
                raise ValueError("planner omitted requested joint relationship")
            return selected, len(selected_aliases) - len(selected)

        def content_validator(content: str) -> Optional[str]:
            try:
                validated_selection(content)
                return None
            except ValueError as exc:
                return str(exc)

        def _duplicate_family_failure(exc: Exception) -> bool:
            if not isinstance(exc, LLMChainExhausted):
                return False
            errors = getattr(exc, "errors", None)
            if not isinstance(errors, list) or not errors:
                return False
            return any(str(error).endswith(
                ": invalid_planner selected duplicate chart family") for error in errors)

        with redaction_scope() as scope, llm_caller_context("factory_chart_conversation", factory_id=factory_id):
            scope.register_values(_sensitive_values())

            async def _call(request: Mapping[str, Any]) -> Mapping[str, Any]:
                remaining = deadline - _planner_monotonic()
                if remaining <= 0:
                    raise asyncio.TimeoutError()
                return await asyncio.wait_for(
                    call_chain(
                        SLOT.CHART,
                        request,
                        timeout=min(attempt_timeout, remaining),
                        total_timeout=min(chain_timeout, remaining),
                        content_validator=content_validator,
                        chart_background=background,
                    ),
                    timeout=remaining,
                )

            try:
                response = await _call(payload)
            except Exception as first_error:
                # Duplicate-family evidence plus fixed feedback permits one
                # correction. Other errors alone never trigger a second request.
                if not _duplicate_family_failure(first_error):
                    raise
                retry_payload = copy.deepcopy(payload)
                retry_payload["messages"].append({
                    "role": "user",
                    "content": (
                        "固定校验反馈：上次选择包含重复familyId。请仅从目录重新选择，"
                        "selected_ids每项familyId必须互异，最多4项；仍只返回原JSON格式。"
                    ),
                })
                if len(json.dumps(retry_payload, ensure_ascii=False).encode("utf-8")) > 50000:
                    unavailable_reason = "图表规划纠正请求超过安全上限，当前授权目录没有与问题匹配的图表"
                    raise ValueError("planner correction payload exceeds 50000 bytes") from first_error
                response = await _call(retry_payload)
        choices = response.get("choices") if isinstance(response, Mapping) else None
        content = choices[0].get("message", {}).get("content") if choices else None
        selected, duplicate_count = validated_selection(content)
        if duplicate_count:
            logger.info("Factory conversation planner removed %d duplicate chart selections by family", duplicate_count)
        mode = {"mode": "ai", "reason": "AI仅在当前授权目录中规划图表"}
    except Exception as exc:
        logger.warning("Factory conversation AI planner failed (%s): %s", type(exc).__name__, exc)
        selected = rule_selection(question, rule_catalog, required_families, MAX_SELECTED_CHARTS)
        if not selected:
            timed_out = isinstance(exc, asyncio.TimeoutError) or (
                isinstance(exc, LLMChainExhausted) and exc.timed_out
            )
            reason = "AI 规划超时，关键词规则未命中" if timed_out else unavailable_reason
            return [], {"mode": "unavailable", "reason": reason}
        mode = {"mode": "rule", "reason": "按问题关键词从授权图表目录选择"}
    if cache_key is not None:
        _PLAN_CACHE[cache_key] = (_cache_monotonic() + PLANNER_CACHE_TTL_SECONDS, tuple(selected), mode)
        _PLAN_CACHE.move_to_end(cache_key)
        if len(_PLAN_CACHE) > MAX_PLANNER_CACHE_ENTRIES:
            _PLAN_CACHE.popitem(last=False)
    return selected, {**mode, "cache": "miss"}


def facts_for(selected: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """Render facts from trusted candidate KPIs, never from the planner."""
    facts: list[dict[str, Any]] = []
    for candidate in selected:
        candidate_id = str(candidate.get("id", ""))
        source = str(candidate.get("source", ""))
        kpis = candidate.get("kpis") if isinstance(candidate.get("kpis"), list) else []
        for index, kpi in enumerate(kpis):
            if not isinstance(kpi, Mapping) or "value" not in kpi:
                continue
            value = kpi.get("value")
            if isinstance(value, (dict, list, tuple, set)):
                continue
            facts.append({
                # Stable ids let the client reconcile a follow-up response
                # without treating the same trusted KPI as a new fact.
                "id": "fact-" + hashlib.sha256(
                    f"{candidate_id}:{index}:{kpi.get('label', '')}".encode("utf-8")
                ).hexdigest()[:16],
                "candidateId": candidate_id,
                "label": str(kpi.get("label") or candidate.get("metric") or candidate_id),
                "value": value,
                "unit": str(kpi.get("unit") or candidate.get("unit") or ""),
                "source": source,
            })
    return facts[:64]


def limitations_for(selected: Sequence[Mapping[str, Any]], catalog: Sequence[Mapping[str, Any]],
                    question: str = "") -> list[str]:
    from smartbi.services.external_production import SOURCE_KEY

    limitations: list[str] = []
    if re.search(r"单位成本|\bunit cost\b", question, re.I) and selected and all(
        str(candidate.get("source", "")).split(" · ", 1)[0] == SOURCE_KEY for candidate in selected
    ):
        limitations.append(_BLACKLAKE_UNIT_COST_GAP)
    for candidate in selected:
        findings = candidate.get("findings") if isinstance(candidate.get("findings"), Mapping) else {}
        explicit = findings.get("limitations") if isinstance(findings.get("limitations"), list) else []
        limitations.extend(str(item) for item in explicit if item)
    families = {_canonical_family(item) for item in selected}
    available = {str(item.get("familyId")) for item in catalog}
    # Only disclose a missing relationship when the question asks for a
    # combined view.  A stock-only question must not receive an unrelated
    # production/quality/cost warning.
    joint_requested = is_joint_intent(question)
    required_families = joint_family_requirements(question) if joint_requested else set()
    for required_family in sorted(required_families - families):
        relationship = {"commerce-joint.sales.stock": "销售-发货-库存",
                        "commerce-joint.customer.channel": "客户-显式渠道-产品"}.get(required_family, "生产-质量-成本")
        limitations.append(f"当前选择未包含已验证的{relationship}联合关系；分开的图表不能冒充已关联分析" if required_family in available
                           else f"当前授权目录没有已验证的{relationship}联合关系，相关维度将分别披露")
    if len(required_families) > 1:
        limitations.append("各类联合关系分别核验，并不代表已合成一张跨域明细表")
    return list(dict.fromkeys(limitations))[:16]


def render_answer(question: str, selected: Sequence[Mapping[str, Any]], facts: Sequence[Mapping[str, Any]],
                  limitations: Sequence[str]) -> str:
    from smartbi.services.external_production import UNAVAILABLE_MESSAGE

    titles = {str(item.get("id", "")): item.get("title") or item.get("metric") for item in selected}
    readings = []
    for fact in facts:
        value = fact["value"]
        if value is None or value == "":
            continue
        label = fact["label"]
        title = titles.get(fact["candidateId"])
        if title and label in {"合计", "累计口径"}:
            label = f"{title}（{label}）"
        readings.append(f"{label}为{value}{fact['unit']}")
    lines = ["；".join(readings[:8]) + "。" if readings else "暂无可直接汇总的数值，请查看下方图表明细。"]
    if _BLACKLAKE_UNIT_COST_GAP in limitations:
        lines.insert(0, _BLACKLAKE_UNIT_COST_GAP + "。")
    elif UNAVAILABLE_MESSAGE in limitations and re.search(r"出成率|\byield rate\b", question, re.I):
        lines.insert(0, UNAVAILABLE_MESSAGE + "出成率。")
    elif selected and re.search(r"每天|每日|逐日", question):
        from .metrics import display

        first = selected[0]
        rows = first.get("tableData")
        title = first.get("title")
        if title and isinstance(rows, list) and 1 <= len(rows) <= 8 and all(
            isinstance(row, Mapping)
            and re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(row.get("label", "")))
            and isinstance(row.get("value"), (int, float))
            for row in rows
        ):
            unit = first.get("unit") or ""
            daily = "，".join(f"{row['label']}为{display(row['value'])}{unit}" for row in rows)
            lines.insert(0, f"{title}：{daily}。")
    findings = []
    for candidate in selected:
        raw_findings = candidate.get("findings") if isinstance(candidate.get("findings"), Mapping) else {}
        for bucket in ("good", "attention", "suggestions"):
            values = raw_findings.get(bucket) if isinstance(raw_findings.get(bucket), list) else []
            labels = {"good": "做得好", "attention": "需关注", "suggestions": "建议"}
            findings.extend(f"{labels[bucket]}：{value}" for value in values[:2] if value)
    if findings:
        lines.append("分析：" + "；".join(findings[:8]))
    if limitations:
        lines.append("限制：" + "；".join(limitations[:4]))
    return "\n".join(lines)


def _joint_axes(question: str) -> set[str]:
    patterns = {"production": r"生产|产量|\b(?:production|outputs?)\b",
                "quality": r"质量|质检|检验|良品|\b(?:quality|qc|good|yields?|inspections?)\b",
                "cost": r"成本|\bcosts?\b", "sales": r"销售|发货|\b(?:sales|shipments?)\b",
                "inventory": r"库存|\b(?:stocks?|inventory)\b", "purchase": r"采购|供应商收货|采购收货|采购入库|\b(?:purchases?|purchase\s+receipts?|supplier\s+(?:receipts?|inbound))\b",
                "customer": r"客户|\bcustomers?\b",
                "channel": r"渠道|\bchannels?\b"}
    return {axis for axis, pattern in patterns.items() if re.search(pattern, question, re.I)}


def is_joint_intent(question: str) -> bool:
    """Recognize a requested multi-axis relationship, not merely a chart word."""
    if re.search(r"不要(?:关联|联合)|不做(?:关联|联合)|分别(?:看|查看|分析)|do not (?:join|combine)|separately", question, re.I):
        return False
    return bool(re.search(r"关联|联合|综合|多维度|放一起|一起看|同时|compare|together|combined|multi[- ]?dimensional", question, re.I)
                and len(_joint_axes(question)) >= 2)


def joint_family_requirements(question: str) -> set[str]:
    """Return every proven relationship requested; an empty set is unsupported."""
    families: set[str] = set()
    axes = _joint_axes(question)
    covered: set[str] = set()
    if {"customer", "channel"}.issubset(axes):
        families.add("commerce-joint.customer.channel")
        covered.update({"customer", "channel", "sales"})
    if {"sales", "inventory"}.issubset(axes):
        families.add("commerce-joint.sales.stock")
        covered.update({"sales", "inventory"})
    if {"purchase", "inventory"}.issubset(axes):
        families.add("purchase-batch-lineage.purchase.inventory")
        covered.update({"purchase", "inventory"})
    if len(axes & {"production", "quality", "cost"}) >= 2:
        if re.search(r"多表|跨表|跨数据|质检|检验|\bqc\b|inspection|cross[- ]?table|multiple tables|across tables|across sources", question, re.I):
            families.add("production.inspection_fused")
        else:
            families.add("production.fused")
        covered.update({"production", "quality", "cost"})
    return families if axes.issubset(covered) else set()


def joint_family_requirement(question: str) -> str:
    """Compatibility for callers that only handle one relationship."""
    families = joint_family_requirements(question)
    return next(iter(families)) if len(families) == 1 else ""


def resolve_subject(question: str, subjects: Sequence[Mapping[str, Any]], explicit: Optional[str] = None) -> tuple[Optional[str], Optional[str]]:
    """Resolve only an exact/unique authorized product; unknown is clarification."""
    entries = [(str(item.get("id")), str(item.get("label"))) for item in subjects if item.get("id") and item.get("label")]
    if explicit:
        if any(item_id == explicit for item_id, _ in entries):
            return explicit, None
        return None, "所选产品不在当前工厂授权数据目录中"
    exact = [(item_id, label) for item_id, label in entries if label and label in question]
    if len(exact) == 1:
        return exact[0][0], None
    if len(exact) > 1:
        return None, "产品名称对应多个授权产品，请选择明确的产品"
    sku_matches = [
        str(item["id"]) for item in subjects if item.get("id") and item.get("sku")
        and re.search(r"(?<![A-Za-z0-9])" + re.escape(str(item["sku"])) + r"(?![A-Za-z0-9])", question)
    ]
    if len(sku_matches) == 1:
        return sku_matches[0], None
    if sku_matches:
        return None, "问题中包含多个授权产品，请选择明确的产品"
    # A product/SKU marker makes an unknown product explicit.  Generic
    # analysis words are excluded so normal questions keep factory scope.
    generic = re.search(r"(?:按|以|从|看)?产品(?:维度|类别|名称|排名|比较|分析|成本|产量|质量)|哪个产品|哪些产品", question)
    marker = re.search(r"(?:(?<!哪几个)(?<!什么)(?<!多少)产品|SKU|sku)[：:\s-]*([^，。！？?\s]{1,40})", question)
    generic_prefixes = (
        "分析", "趋势", "质量", "成本", "产量", "销售", "采购", "比较", "哪个", "哪些", "高", "低",
        "变化", "结构", "排名", "按", "类别", "名称", "维度", "哪几个", "是哪几个", "有哪些",
    )
    if marker and not generic and not marker.group(1).startswith(generic_prefixes):
        token = marker.group(1)
        base_matches = [(item_id, label) for item_id, label in entries
                        if label.split(" · ", 1)[0] == token or token in label.split(" · ", 1)[0]]
        if len(base_matches) > 1:
            return None, "产品名称对应多个授权产品，请选择明确的产品"
        if len(base_matches) == 1:
            return base_matches[0][0], None
        return None, "问题中的产品不在当前工厂授权数据目录中"
    return None, None


def resolve_period(question: str, scope: Mapping[str, Any]) -> tuple[dict[str, Any], Optional[str]]:
    """Resolve common periods; unsupported period language fails closed."""
    if "季度" in question:
        return dict(scope), "季度表达暂不支持，请明确年份或月份区间"
    years = {int(value) for value in re.findall(r"(?<!\d)(20\d{2})(?:年|年度)", question)}
    months = [int(value) for value in re.findall(r"(?<!\d)(1[0-2]|[1-9])\s*月", question)]
    relative = re.findall(r"今年|去年|前年|本月|上月|上个月|最近(?:[一二两三四五六七八九十\d]+)个月|近(?:[一二两三四五六七八九十\d]+)个月", question)
    if len(years) > 1 or len(relative) > 1:
        return dict(scope), "问题包含多个不明确期间，请明确一个期间"
    rolling = False
    if relative:
        token = relative[0]
        current = date.today()
        if token == "今年":
            years = {current.year}
        elif token == "去年":
            years = {current.year - 1}
        elif token == "前年":
            years = {current.year - 2}
        elif token in {"上月", "上个月"}:
            previous_year, previous_month = current.year, current.month - 1
            if previous_month == 0:
                previous_year, previous_month = current.year - 1, 12
            years, months = {previous_year}, [previous_month]
        else:
            match = re.search(r"(\d+|一|二|两|三|四|五|六|七|八|九|十)", token)
            count = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}.get(match.group(1), int(match.group(1)) if match and match.group(1).isdigit() else 1) if match else 1
            rolling = True
            if current.month - count + 1 < 1:
                return dict(scope), "最近期间跨年度，暂请明确一个年份和月份区间"
            years, months = {current.year}, list(range(current.month - count + 1, current.month + 1))
    if re.search(r"近\s*[一二两三四五六七八九十\d]+年|最近\s*[一二两三四五六七八九十\d]+年|近\s*[一二两三四五六七八九十\d]+天", question):
        return dict(scope), "近年或近天的滚动期间暂不支持，请明确年份或月份区间"
    if not years and not months and re.search(r"季度|期间|月份|年度", question):
        return dict(scope), "期间表达暂不支持，请明确年份或月份区间"
    if len(years) > 1:
        return dict(scope), "问题包含多个年份，请明确一个期间"
    if len(months) > 2 and not rolling:
        return dict(scope), "问题包含多个不明确月份，请明确期间"
    result = dict(scope)
    if years:
        result["year"] = next(iter(years))
        result["period_type"] = "year"
        result["start_month"], result["end_month"] = 1, 12
    if months:
        result["period_type"] = "month_range"
        result["start_month"] = min(months)
        result["end_month"] = max(months)
    return result, None


def apply_explicit_period(scope: Mapping[str, Any], *, year: Optional[int] = None,
                          period_type: Optional[str] = None, start_month: Optional[int] = None,
                          end_month: Optional[int] = None) -> tuple[dict[str, Any], Optional[str], bool]:
    """Apply explicit API period fields, rejecting incomplete/conflicting input."""
    supplied = any(value is not None for value in (year, period_type, start_month, end_month))
    result = dict(scope)
    if not supplied:
        return result, None, False
    if year is not None:
        result["year"] = int(year)
    chosen = period_type or result.get("period_type", "all")
    if chosen not in {"all", "year", "month_range"}:
        return result, "期间类型无效", True
    if chosen == "month_range":
        if start_month is None or end_month is None:
            return result, "月份区间必须同时提供开始和结束月份", True
        if int(start_month) > int(end_month):
            return result, "开始月份不能晚于结束月份", True
        result["start_month"], result["end_month"] = int(start_month), int(end_month)
    elif chosen == "year":
        if (start_month is not None and int(start_month) != 1) or (end_month is not None and int(end_month) != 12):
            return result, "全年期间必须为1至12月", True
        result["start_month"], result["end_month"] = 1, 12
    else:
        # Web/RN send the complete 1..12 defaults even for all-history.  They
        # are a soft baseline, not an explicit month constraint.
        if (start_month is not None and int(start_month) != 1) or (end_month is not None and int(end_month) != 12):
            return result, "全部历史期间不能指定月份", True
        result["start_month"], result["end_month"] = 1, 12
    result["period_type"] = chosen
    return result, None, True


def scope_for_context(context: ConversationContext, question: str, explicit_subject: Optional[str],
                      subjects: Sequence[Mapping[str, Any]] = (),
                      *, year: Optional[int] = None, period_type: Optional[str] = None,
                      start_month: Optional[int] = None, end_month: Optional[int] = None) -> tuple[dict[str, Any], Optional[str], Optional[str]]:
    subject, subject_error = resolve_subject(question, subjects, explicit_subject)
    period_question = question
    if subject:
        for item in subjects:
            if str(item.get("id")) == subject:
                period_question = period_question.replace(str(item.get("label", "")), " ")
                break
    question_scope, period_error = resolve_period(period_question, context.scope)
    scope, explicit_error, explicit_present = apply_explicit_period(
        context.scope, year=year, period_type=period_type,
        start_month=start_month, end_month=end_month)
    if period_error or explicit_error:
        return scope, None, period_error or explicit_error
    hard_explicit = (period_type in {"year", "month_range"} or
                     (period_type is None and any(value is not None for value in (year, start_month, end_month))))
    if hard_explicit and question_scope != context.scope and question_scope != scope:
        return scope, None, "问题中的自然语言期间与显式期间不一致，请只指定一个期间"
    if not explicit_present:
        scope = question_scope
    if subject:
        scope["analysis_subject"] = subject
        scope["guided"] = True
    return scope, subject, period_error


def public_catalog(catalog: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    return [dict(item) for item in catalog]


def is_cancel(question: str) -> bool:
    return bool(re.search(r"(?:取消|放弃|退出|结束)(?:本轮|这次|分析|对话)?", question))


def touch_context(context: ConversationContext, question: str, selected_ids: Sequence[str] = (),
                  *, unresolved_product: Optional[bool] = None,
                  scope: Optional[Mapping[str, Any]] = None,
                  unresolved_period: Optional[bool] = None,
                  period_error: Optional[str] = None) -> None:
    selected = [str(item) for item in selected_ids[:MAX_SELECTED_CHARTS]]
    context.history.append({"question": question[:1000], "selectedIds": selected})
    del context.history[:-MAX_HISTORY]
    context.selected_ids = selected
    if unresolved_product is not None:
        context.unresolved_product = bool(unresolved_product)
    if unresolved_period is not None:
        context.unresolved_period = bool(unresolved_period)
    if period_error is not None:
        context.period_error = period_error
    if scope is not None:
        context.scope = dict(scope)
    context.version += 1
    context.expires_at = time.monotonic() + CONTEXT_TTL_SECONDS


async def create_shared_context(owner, source, upload_id, scope, *, question="", unresolved_product=False):
    """Create production state in PostgreSQL; no in-process fallback."""
    from .conversation_store import get_store
    # Keep explicit uploaded role selection in the durable scope.  It is
    # authority for every follow-up, not a hint from which siblings may be
    # rediscovered.  The API validates values; this canonicalization prevents
    # aliases or omitted role ids being persisted by a caller.
    if isinstance(scope, Mapping) and isinstance(scope.get("uploadedJoinSelection"), Mapping):
        selected = scope["uploadedJoinSelection"]
        keys = ("uploadId", "inspectionUploadId", "costUploadId")
        if all(key in selected for key in keys):
            scope = {**dict(scope), "uploadedJoinSelection": {
                **{key: int(selected[key]) for key in keys},
                "roles": {
                    "production": int(selected["uploadId"]),
                    "inspection": int(selected["inspectionUploadId"]),
                    "cost": int(selected["costUploadId"]),
                },
            }}
    context = await get_store().create(owner, source, upload_id, scope, ConversationContext)
    # The accepted turn is recorded by touch_context after rendering; do not
    # pre-seed the same question here (which would duplicate history).
    return context


async def claim_shared_context(context_id, owner):
    from .conversation_store import get_store
    return await get_store().claim(context_id, owner, ConversationContext)


async def save_shared_context(context):
    from .conversation_store import get_store
    return await get_store().save(context)


async def release_shared_context(context):
    from .conversation_store import get_store
    return await get_store().release(context)


__all__ = [
    "ConversationContext", "CONTEXT_TTL_SECONDS", "MAX_CONTEXTS", "clear_contexts",
    "_new_context", "_context_for", "semantic_catalog", "plan", "facts_for",
    "limitations_for", "render_answer", "resolve_subject", "resolve_period",
    "scope_for_context", "apply_explicit_period", "public_catalog", "is_cancel", "touch_context", "is_joint_intent", "joint_family_requirement", "joint_family_requirements",
    "create_shared_context", "claim_shared_context", "save_shared_context", "release_shared_context",
]
