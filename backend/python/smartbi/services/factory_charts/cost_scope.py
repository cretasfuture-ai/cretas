"""Process-secret cost scope for SmartBI cost exits (工艺保密 · 成本合计).

Policy lives in Java (``SmartBICostProductAccessController``): unprotected
products are always allowed; a protected product is allowed only for its
secret owner or an active grant.  This module only turns that answer into the
three states every cost exit needs and the response annotations, so each
exit applies the same rules:

* ``FULL``          - the caller may see every protected product of the
                      factory (``unscopedCostAllowed``).  Factory-wide totals
                      are returned unchanged.
* ``SCOPED``        - some protected products are not allowed.  Per-product
                      cost fields of those products are hidden (``None``),
                      quantities stay.  Factory-wide totals are REBUILT by
                      summing only the allowed products' rows.  ⛔ never
                      ``total - secret``: two published numbers would let
                      anyone back out the secret part.
* ``UNAVAILABLE``   - authorization could not be established (no request,
                      no bearer, Java down, malformed reply).  Fail closed:
                      every cost amount is hidden and totals are ``None`` with
                      a reason, never zero.

Rows that carry no product id (e.g. a material consumption not tied to a
production batch) are not process-secret facts; they count as allowed unless
the scope is ``UNAVAILABLE``.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Iterable, Optional

from . import cost_authorization

FULL = "FULL"
SCOPED = "SCOPED"
UNAVAILABLE = "UNAVAILABLE"

# Mirrors Java ProcessSecretPayloadScrubber.MARKER / TOTALS_ONLY_MESSAGE.
MARKER = "processSecret"
TOTALS_ONLY = "TOTALS_ONLY"
TOTALS_ONLY_MESSAGE = "该产品工艺已设为保密：可以看物料和本单总量，配比、单位用量、标准出成率和成本合计已隐藏"
UNVERIFIED = "COST_UNVERIFIED"
UNVERIFIED_MESSAGE = "成本授权暂时无法确认，成本金额已隐藏"

REASON_NOT_RUN = "成本授权未执行，成本金额已隐藏"
REASON_FAILED = "成本授权失败，成本金额已隐藏"


def _norm(product_id: Any) -> Optional[str]:
    if product_id is None:
        return None
    text = str(product_id).strip()
    return text or None


@dataclass(frozen=True)
class CostScope:
    status: str
    allowed_product_ids: frozenset[str] = field(default_factory=frozenset)
    reason: Optional[str] = None

    @property
    def is_full(self) -> bool:
        return self.status == FULL

    @property
    def is_unavailable(self) -> bool:
        return self.status == UNAVAILABLE

    def allows(self, product_id: Any) -> bool:
        """May this caller see the cost amount of a row of ``product_id``?"""
        if self.status == UNAVAILABLE:
            return False
        pid = _norm(product_id)
        if pid is None or self.status == FULL:
            return True
        return pid in self.allowed_product_ids

    def excluded_product_ids(self, product_ids: Iterable[Any]) -> list[str]:
        """Distinct product ids whose costs this caller may not see."""
        ids = {p for p in (_norm(v) for v in product_ids) if p is not None}
        if self.status == FULL:
            return []
        return sorted(p for p in ids if not self.allows(p))


def unavailable(reason: str = REASON_FAILED) -> CostScope:
    return CostScope(UNAVAILABLE, frozenset(), reason)


class CostScopeResolver:
    """Ask Java once per new product id within one HTTP request.

    Deep call chains (e.g. inventory turnover → health score → KPI cards) ask
    for the same ids repeatedly; decisions are cached per request.  A failure
    is sticky: once authorization failed, every later question is answered
    ``UNAVAILABLE`` for the rest of the request.
    """

    def __init__(self, auth_request: Any, factory_id: str):
        self._request = auth_request
        self._factory_id = factory_id
        self._decided: dict[str, bool] = {}
        self._full: Optional[bool] = None
        self._failure: Optional[str] = None if auth_request is not None else REASON_NOT_RUN

    async def scope_for(self, product_ids: Iterable[Any]) -> CostScope:
        if self._failure:
            return unavailable(self._failure)
        ids = {p for p in (_norm(v) for v in product_ids) if p is not None}
        if self._full:
            return CostScope(FULL, frozenset(ids))
        pending = sorted(ids - self._decided.keys())
        if pending or self._full is None:
            try:
                authority = await cost_authorization.authorize_batches(
                    self._request, self._factory_id, pending)
            except cost_authorization.CostAuthorizationError:
                self._failure = REASON_FAILED
                return unavailable(self._failure)
            self._full = bool(authority.unscoped_cost_allowed)
            for pid in pending:
                self._decided[pid] = pid in authority.allowed_product_ids
            if self._full:
                return CostScope(FULL, frozenset(ids))
        return CostScope(SCOPED, frozenset(p for p in ids if self._decided.get(p)))


async def resolve_cost_scope(auth_request: Any, factory_id: str, product_ids: Iterable[Any]) -> CostScope:
    return await CostScopeResolver(auth_request, factory_id).scope_for(product_ids)


def hidden_marker(hidden_fields: Iterable[str], scope: CostScope) -> dict:
    """Per-row marker, same shape as Java's ``processSecret`` marker."""
    if scope.is_unavailable:
        return {"visibility": UNVERIFIED, "message": scope.reason or UNVERIFIED_MESSAGE,
                "hiddenFields": list(hidden_fields)}
    return {"visibility": TOTALS_ONLY, "message": TOTALS_ONLY_MESSAGE,
            "hiddenFields": list(hidden_fields)}


def excluded_note(count: int) -> str:
    return f"不含 {count} 个保密产品"


def rollup_annotation(scope: CostScope, excluded_count: int) -> Optional[dict]:
    """Annotation for a factory-wide cost total; ``None`` when nothing was left out."""
    if scope.is_unavailable:
        return {"status": UNAVAILABLE, "reason": scope.reason or REASON_FAILED}
    if scope.is_full or excluded_count <= 0:
        return None
    return {"status": "ALLOWED_PRODUCTS_ONLY", "excludedProtectedProductCount": excluded_count,
            "note": excluded_note(excluded_count)}


def rollup_note(scope: CostScope, excluded_count: int) -> Optional[str]:
    annotation = rollup_annotation(scope, excluded_count)
    if annotation is None:
        return None
    return annotation.get("note") or annotation.get("reason")


__all__ = ["CostScope", "CostScopeResolver", "FULL", "SCOPED", "UNAVAILABLE", "MARKER",
           "hidden_marker", "rollup_annotation", "rollup_note", "excluded_note",
           "resolve_cost_scope", "unavailable"]
