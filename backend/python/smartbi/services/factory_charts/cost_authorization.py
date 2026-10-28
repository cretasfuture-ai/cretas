"""Narrow adapter for the Java manufacturing-cost product authority.

This module deliberately contains no policy logic.  The Java ProcessSecret
authority decides which product ids may expose process costs; Python only
validates the authenticated identity and applies the returned intersection to
its SQL reads.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Iterable

import httpx
import re as _re

from .finance_auth import java_base_url

# Java's DTO accepts arbitrary Unicode product identifiers (including internal
# spaces) but rejects empty/control-containing values and bounds them at 191
# characters.  Keep the same wire contract rather than imposing ASCII/\S.
_ID = re.compile(r"^[^\x00-\x1f\x7f]{1,191}$", re.UNICODE)
MAX_PRODUCTS = 512
MAX_ID_LENGTH = 191
PRODUCT_ID_ALIASES = ("productId", "产品ID", "产品编号", "产品编码", "product_id", "productTypeId",
                      "product_type_id", "产品类型id", "SKU", "sku", "sku编码")
_MANUFACTURING_COST_NAMES = {
    "总成本", "材料成本", "原料成本", "人工成本", "设备成本", "其他成本", "单位成本",
    "total_cost", "material_cost", "raw_material_cost", "labor_cost", "equipment_cost",
    "other_cost", "unit_cost", "fg_unit_cost", "finished_goods_unit_cost",
}


def normalized_column_name(value: Any) -> str:
    text = str(value or "").strip()
    while _re.search(r"[（(][^（）()]*[）)]", text):
        text = _re.sub(r"[（(][^（）()]*[）)]", "", text)
    text = text.strip()
    # Match engine._product_columns: bracket decoration is semantic noise,
    # while spaces/hyphens remain meaningful and must not create new aliases.
    return text.lower()


def is_manufacturing_cost_column(value: Any) -> bool:
    return normalized_column_name(value) in {normalized_column_name(item) for item in _MANUFACTURING_COST_NAMES}


def row_product_ids(row: dict[str, Any]) -> tuple[set[str], bool]:
    aliases = {normalized_column_name(alias) for alias in PRODUCT_ID_ALIASES}
    values: set[str] = set()
    for key, value in row.items():
        # Inspect every raw field.  Building a folded dict first would let two
        # differently-spelled aliases overwrite each other before conflicts are
        # detected (e.g. productId=PUBLIC and 产品ID=SECRET).
        if normalized_column_name(key) in aliases and isinstance(value, str) and value.strip():
            values.add(value.strip())
    return values, len(values) > 1


class CostAuthorizationError(RuntimeError):
    """A fail-closed authority/identity response."""


@dataclass(frozen=True)
class CostAuthorization:
    factory_id: str
    user_id: int
    allowed_product_ids: frozenset[str]
    unscoped_cost_allowed: bool

    def allows(self, product_id: Any) -> bool:
        return str(product_id or "").strip() in self.allowed_product_ids


def validate_product_ids(values: Iterable[Any]) -> list[str]:
    if values is None or not isinstance(values, (list, tuple, set, frozenset)):
        raise CostAuthorizationError("productIds is required")
    output: list[str] = []
    seen: set[str] = set()
    for value in values:
        if (not isinstance(value, str) or not value.strip() or len(value) > MAX_ID_LENGTH
                or not _ID.fullmatch(value) or value in seen):
            raise CostAuthorizationError("invalid productIds")
        seen.add(value)
        output.append(value)
    if len(output) > MAX_PRODUCTS:
        raise CostAuthorizationError("too many productIds")
    return output


def _state_identity(request: Any, factory_id: str) -> tuple[int, str]:
    state = getattr(request, "state", None)
    token_factory = getattr(state, "factory_id", None)
    user_id = getattr(state, "user_id", None)
    if token_factory is None or str(token_factory) != str(factory_id):
        raise CostAuthorizationError("authenticated factory mismatch")
    try:
        auth_method = getattr(state, "auth_method", None)
        if auth_method == "internal" and isinstance(user_id, str):
            if not _re.fullmatch(r"[1-9][0-9]{0,18}", user_id):
                raise ValueError
            user_id = int(user_id)
        elif isinstance(user_id, bool) or not isinstance(user_id, int):
            raise ValueError
        user_id = int(user_id)
    except (TypeError, ValueError):
        raise CostAuthorizationError("authenticated user missing") from None
    if user_id <= 0:
        raise CostAuthorizationError("authenticated user missing")
    return user_id, str(token_factory)


async def authorize(request: Any, factory_id: str, product_ids: Iterable[Any], *, unscoped: bool = False) -> CostAuthorization:
    """Ask Java for current grants, rejecting malformed or cross-identity replies."""
    requested = validate_product_ids(product_ids)
    user_id, trusted_factory = _state_identity(request, factory_id)
    token = str(getattr(request, "headers", {}).get("authorization", ""))
    if not token.startswith("Bearer "):
        raise CostAuthorizationError("authenticated bearer missing")
    base = java_base_url()
    if not base:
        raise CostAuthorizationError("java authority target unavailable")
    configured = bool(__import__("os").environ.get("JAVA_API_BASE_URL") or __import__("os").environ.get("BACKEND_BASE_URL"))
    targets = [base]
    if not configured and base.endswith(":10010"):
        targets.append(base[:-6] + ":10020")
    payload = {"productIds": requested}
    response = None
    for index, target in enumerate(targets):
        try:
            async with httpx.AsyncClient(timeout=6, follow_redirects=False) as client:
                response = await client.post(
                    f"{target}/api/mobile/{trusted_factory}/smart-bi/cost-product-access",
                    json=payload, headers={"Authorization": token})
        except httpx.ConnectError:
            if index + 1 < len(targets):
                continue
            raise CostAuthorizationError("java authority unavailable") from None
        except (httpx.TimeoutException, httpx.RequestError):
            raise CostAuthorizationError("java authority unavailable") from None
        break
    if response is None or response.status_code != 200:
        raise CostAuthorizationError("cost authority denied")
    try:
        body = response.json()
        data = body.get("data")
        allowed = validate_product_ids(data.get("allowedProductIds"))
        returned_factory = data.get("factoryId")
        raw_user = data.get("userId")
        if isinstance(raw_user, bool) or not isinstance(raw_user, int):
            raise ValueError
        returned_user = raw_user
        unscoped_allowed = data.get("unscopedCostAllowed") is True
    except (ValueError, TypeError, AttributeError, KeyError, CostAuthorizationError):
        raise CostAuthorizationError("malformed cost authority response") from None
    if body.get("success") is not True or returned_factory != trusted_factory or returned_user != user_id:
        raise CostAuthorizationError("cost authority identity mismatch")
    requested_set = set(requested)
    allowed_set = set(allowed)
    if not allowed_set <= requested_set:
        raise CostAuthorizationError("cost authority returned out-of-scope product")
    if unscoped and not unscoped_allowed:
        # Callers must not turn a denied unscoped dataset into a zero-valued one.
        raise CostAuthorizationError("unscoped manufacturing cost denied")
    return CostAuthorization(trusted_factory, user_id, frozenset(allowed_set), unscoped_allowed)


async def authorize_batches(request: Any, factory_id: str, product_ids: Iterable[Any], *, unscoped: bool = False) -> CostAuthorization:
    """Authorize large row scopes in bounded Java requests."""
    raw = list(product_ids)
    requested = []
    if len(raw) > 20000:
        raise CostAuthorizationError("too many productIds")
    seen = set()
    for value in raw:
        # Validate each item before chunking, but enforce uniqueness globally.
        validated = validate_product_ids([value])[0]
        if validated in seen:
            raise CostAuthorizationError("invalid productIds")
        seen.add(validated)
        requested.append(validated)
    if unscoped and not requested:
        return await authorize(request, factory_id, [], unscoped=True)
    chunks = [requested[i:i + MAX_PRODUCTS] for i in range(0, len(requested), MAX_PRODUCTS)] or [[]]
    results = [await authorize(request, factory_id, chunk, unscoped=False) for chunk in chunks]
    first = results[0]
    return CostAuthorization(first.factory_id, first.user_id,
                             frozenset().union(*(item.allowed_product_ids for item in results)),
                             all(item.unscoped_cost_allowed for item in results))


# Descriptive aliases keep call sites readable and make the adapter stable for
# source loaders that do not need to know the transport details.
authorize_cost_products = authorize
authorize_product_scope = authorize


def sql_product_predicate(column: str, start: int = 4) -> str:
    """Return a parameterized product predicate; cost columns are read after it."""
    return f"{column} = ANY(${start}::text[])"


__all__ = ["CostAuthorization", "CostAuthorizationError", "authorize", "authorize_cost_products",
           "authorize_product_scope", "authorize_batches", "validate_product_ids", "sql_product_predicate"]
