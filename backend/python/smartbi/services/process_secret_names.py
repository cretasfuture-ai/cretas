"""Names of a factory's process-secret products (privacy clause 6, 2026-10-24).

Process-secret products are not used for training. Training samples carry no
product id, so the rule is by name — the same rule as the Java
``ProcessSecretSampleFilter``: a sample whose text contains the name of one of
the factory's protected products is not stored / not exported. Names shorter
than 2 characters never match.

The policies live in the Cretas main database (``process_secret_policies``,
Flyway V20261103_72); this module reads them through ``get_cretas_pool``.
Lookup errors propagate: callers treat "unknown" as "do not store".
"""
from __future__ import annotations

import time
from typing import Any, Dict, FrozenSet, Iterable, List, Mapping, Optional, Tuple

from common.process_secret_sql import MIN_NAME_LENGTH, PROTECTED_NAMES_SQL, usable_names  # noqa: F401

_CACHE_SECONDS = 60.0

ALL_PROTECTED_NAMES_SQL = """
    SELECT DISTINCT p.factory_id, pt.name
    FROM process_secret_policies p
    JOIN product_types pt ON pt.id::text = p.product_type_id AND pt.factory_id = p.factory_id
    WHERE pt.name IS NOT NULL
"""

_cache: Dict[str, Tuple[float, FrozenSet[str]]] = {}
_ALL = "*"
_all_cache: Dict[str, Tuple[float, Mapping[str, FrozenSet[str]]]] = {}


def mentions_any(names: Iterable[str], *texts: Optional[str]) -> bool:
    names = tuple(names)
    return any(t and any(n in t for n in names) for t in texts)


async def protected_product_names(factory_id: str, pool=None) -> FrozenSet[str]:
    """Protected product names of one factory (cached 60 s). Errors propagate."""
    now = time.monotonic()
    hit = _cache.get(factory_id)
    if hit and now - hit[0] < _CACHE_SECONDS:
        return hit[1]
    if pool is None:
        from smartbi.config import get_cretas_pool
        pool = await get_cretas_pool()
    if pool is None:
        raise RuntimeError("cretas database pool unavailable")
    async with pool.acquire() as conn:
        rows = await conn.fetch(PROTECTED_NAMES_SQL, factory_id)
    names = usable_names(r["name"] for r in rows)
    _cache[factory_id] = (now, names)
    return names


async def all_protected_product_names(pool=None) -> Mapping[str, FrozenSet[str]]:
    """``{factory_id: names}`` for every factory with protected products
    (cached 60 s: capture of a sample with no factory calls this per sample).
    Errors propagate (a failed lookup is never cached)."""
    now = time.monotonic()
    hit = _all_cache.get(_ALL)
    if hit and now - hit[0] < _CACHE_SECONDS:
        return hit[1]
    if pool is None:
        from smartbi.config import get_cretas_pool
        pool = await get_cretas_pool()
    if pool is None:
        raise RuntimeError("cretas database pool unavailable")
    async with pool.acquire() as conn:
        rows = await conn.fetch(ALL_PROTECTED_NAMES_SQL)
    by_factory: Dict[str, set] = {}
    for r in rows:
        by_factory.setdefault(r["factory_id"], set()).add(r["name"])
    result = {f: usable_names(n) for f, n in by_factory.items()}
    _all_cache[_ALL] = (now, result)
    return result


def any_factory_names(by_factory: Mapping[str, Iterable[str]]) -> FrozenSet[str]:
    """Union of every factory's protected names.

    For samples with no factory: we cannot tell whose product they name, so a
    sample naming a process-secret product of ANY factory is not used."""
    return frozenset(n for names in (by_factory or {}).values() for n in (names or ()))


def names_for_row(factory_id: Optional[str],
                  by_factory: Optional[Mapping[str, Iterable[str]]]) -> FrozenSet[str]:
    """Names a sample of ``factory_id`` must not mention: its own factory's, or,
    for a sample with no factory, those of ANY factory."""
    by_factory = by_factory or {}
    if factory_id:
        return frozenset(by_factory.get(factory_id, ()))
    return any_factory_names(by_factory)


def row_names_protected_product(row: Mapping[str, Any],
                                by_factory: Optional[Mapping[str, Iterable[str]]]) -> bool:
    """True when a ``smart_bi_distillation_samples`` row (``factory_id`` /
    ``input_text`` / ``teacher_output``) names a process-secret product."""
    return mentions_any(names_for_row(row.get("factory_id"), by_factory),
                        row.get("input_text"), row.get("teacher_output"))


def drop_rows_naming_protected_products(
        rows: Iterable[Any],
        by_factory: Optional[Mapping[str, Iterable[str]]]) -> List[Any]:
    """Rows that name no process-secret product (corpus scripts: LLM judge,
    human review sampling, eval freeze). Rows must carry ``factory_id`` /
    ``input_text`` / ``teacher_output``. ``by_factory`` comes from
    ``all_protected_product_names``: a failed lookup must abort the caller,
    never be read as "nothing protected"."""
    return [r for r in rows if not row_names_protected_product(r, by_factory)]


def clear_cache() -> None:
    _cache.clear()
    _all_cache.clear()
