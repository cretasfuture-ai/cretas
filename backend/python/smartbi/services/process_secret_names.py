"""A factory's lists for training samples (privacy clause 6 + Steve 2026-10-24 /
2026-10-29 option A).

Per factory:

* ``terms``: its process-secret products' full names / codes / product ids
  (``common.process_secret_terms.secret_terms``: pure-numeric names and category
  words are not collected, codes match only on ASCII boundaries);
* ``directory``: its people (``users.full_name``) and customers
  (``customers.name``), matched exactly.

A sample naming any of them is not stored / exported / judged. A sample with no
factory is matched against NOTHING — never against the union of every factory's
lists (that union blocked a no-factory row for naming another factory's 牛腱):
capture refuses rows without a factory, and readers drop them
(``row_names_protected_product`` is False for them, ``passes_training_rule`` and
``usable_rows_sql`` exclude them).

The lists live in the Cretas main database; this module reads them through
``get_cretas_pool``. Lookup errors propagate, EXCEPT through
:func:`factory_lists_or_last_good`: capture then judges against the last lists
that loaded successfully for that factory and marks the row held for
re-judgement (``training_consent.RECHECK_KEY``); :func:`rejudge_held_rows`
re-checks held rows against the current lists.
"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass
from typing import Any, Dict, FrozenSet, Iterable, List, Mapping, Optional, Tuple

from common.process_secret_sql import (  # noqa: F401
    ALL_FACTORY_DIRECTORY_SQL,
    ALL_PROTECTED_TERMS_SQL,
    FACTORY_DIRECTORY_SQL,
    MIN_NAME_LENGTH,
    PROTECTED_TERMS_SQL,
)
from common.process_secret_terms import (  # noqa: F401
    directory_names,
    fingerprint,
    mentions_any,
    secret_terms,
)

_CACHE_SECONDS = 60.0


@dataclass(frozen=True)
class FactoryLists:
    """One factory's lists. ``terms`` and ``directory`` are already folded."""
    terms: FrozenSet[str] = frozenset()
    directory: FrozenSet[str] = frozenset()

    def names_any(self, *texts: Optional[str]) -> bool:
        return mentions_any(self.terms, *texts) or mentions_any(self.directory, *texts)


EMPTY_LISTS = FactoryLists()

_cache: Dict[str, Tuple[float, FactoryLists]] = {}
_last_good: Dict[str, FactoryLists] = {}
_ALL = "*"
_all_cache: Dict[str, Tuple[float, Mapping[str, FactoryLists]]] = {}


async def _pool_or_default(pool):
    if pool is None:
        from smartbi.config import get_cretas_pool
        pool = await get_cretas_pool()
    if pool is None:
        raise RuntimeError("cretas database pool unavailable")
    return pool


def lists_from_rows(product_rows: Iterable[Mapping[str, Any]],
                    directory_rows: Iterable[Any]) -> FactoryLists:
    """Pure builder (shared case file runs it): product rows carry id / code /
    name / category / product_category; directory rows are names."""
    names = [r["name"] if isinstance(r, Mapping) else r for r in directory_rows]
    return FactoryLists(terms=secret_terms(product_rows), directory=directory_names(names))


async def factory_lists(factory_id: str, pool=None) -> FactoryLists:
    """Lists of one factory (cached 60 s). Errors propagate (never cached)."""
    now = time.monotonic()
    hit = _cache.get(factory_id)
    if hit and now - hit[0] < _CACHE_SECONDS:
        return hit[1]
    pool = await _pool_or_default(pool)
    async with pool.acquire() as conn:
        products = await conn.fetch(PROTECTED_TERMS_SQL, factory_id)
        people = await conn.fetch(FACTORY_DIRECTORY_SQL, factory_id)
    lists = lists_from_rows([dict(r) for r in products], [dict(r) for r in people])
    _cache[factory_id] = (now, lists)
    _last_good[factory_id] = lists
    return lists


async def factory_lists_or_last_good(factory_id: str, pool=None) -> Tuple[FactoryLists, bool]:
    """``(lists, stale)``: the current lists, or — when the lookup fails — the
    last lists that loaded successfully for this factory (``stale=True``; the
    caller must hold what it judged with them for re-judgement). No last good
    lists -> the lookup error propagates (the caller does not store)."""
    try:
        return await factory_lists(factory_id, pool), False
    except Exception:
        last = _last_good.get(factory_id)
        if last is None:
            raise
        return last, True


async def all_factory_lists(pool=None) -> Mapping[str, FactoryLists]:
    """``{factory_id: FactoryLists}`` for every factory with protected products,
    people or customers (cached 60 s). Errors propagate (never cached)."""
    now = time.monotonic()
    hit = _all_cache.get(_ALL)
    if hit and now - hit[0] < _CACHE_SECONDS:
        return hit[1]
    pool = await _pool_or_default(pool)
    async with pool.acquire() as conn:
        products = await conn.fetch(ALL_PROTECTED_TERMS_SQL)
        people = await conn.fetch(ALL_FACTORY_DIRECTORY_SQL)
    by_products: Dict[str, List[Dict[str, Any]]] = {}
    for r in products:
        by_products.setdefault(r["factory_id"], []).append(dict(r))
    by_people: Dict[str, List[str]] = {}
    for r in people:
        by_people.setdefault(r["factory_id"], []).append(r["name"])
    result = {f: lists_from_rows(by_products.get(f, ()), by_people.get(f, ()))
              for f in set(by_products) | set(by_people)}
    _all_cache[_ALL] = (now, result)
    for f, lists in result.items():
        _last_good[f] = lists
    return result


def lists_for_row(factory_id: Optional[str],
                  by_factory: Optional[Mapping[str, FactoryLists]]) -> FactoryLists:
    """Lists a sample of ``factory_id`` is matched against: its own factory's.
    No factory -> nothing (NOT the union of every factory's lists)."""
    if not factory_id:
        return EMPTY_LISTS
    return (by_factory or {}).get(factory_id, EMPTY_LISTS)


def terms_fingerprint(by_factory: Optional[Mapping[str, FactoryLists]]) -> str:
    """Fingerprint of every factory's secret terms (corpus_judge records it on
    rows it excluded, and re-judges rows excluded under a different list)."""
    return fingerprint(f"{f}\t{t}" for f, lists in (by_factory or {}).items() for t in lists.terms)


SAMPLE_TEXT_FIELDS: Tuple[str, ...] = ("input_text", "teacher_output")


def row_names_protected_product(row: Mapping[str, Any],
                                by_factory: Optional[Mapping[str, FactoryLists]],
                                fields: Iterable[str] = SAMPLE_TEXT_FIELDS) -> bool:
    """True when a ``smart_bi_distillation_samples`` row (``factory_id`` plus
    the text ``fields``) names one of its factory's process-secret terms."""
    lists = lists_for_row(row.get("factory_id"), by_factory)
    return mentions_any(lists.terms, *(row.get(f) for f in fields))


def drop_rows_naming_protected_products(
        rows: Iterable[Any],
        by_factory: Optional[Mapping[str, FactoryLists]],
        fields: Iterable[str] = SAMPLE_TEXT_FIELDS) -> List[Any]:
    """Rows that name none of their factory's process-secret terms (corpus
    scripts: LLM judge, human review sampling, eval freeze, synthesis report).
    Rows must carry ``factory_id`` and the text ``fields``. A real row with no
    factory is dropped as well: it cannot be checked against anyone's lists
    (``usable_rows_sql`` already excludes it; this is the second lock).
    ``by_factory`` comes from ``all_factory_lists``: a failed lookup must abort
    the caller, never be read as "nothing protected"."""
    fields = tuple(fields)
    return [r for r in rows
            if r.get("factory_id") and not row_names_protected_product(r, by_factory, fields)]


# ---- held rows (captured against the last good lists) ----

_HELD_ROWS_SQL = """
    SELECT id, factory_id, input_text, teacher_output, metadata
    FROM smart_bi_distillation_samples
    WHERE metadata ? '{key}'
    ORDER BY id
    LIMIT $1
"""
_RELEASE_SQL = """
    UPDATE smart_bi_distillation_samples SET metadata = metadata - '{key}'
    WHERE id = ANY($1::bigint[])
"""
_DELETE_SQL = "DELETE FROM smart_bi_distillation_samples WHERE id = ANY($1::bigint[])"


async def rejudge_held_rows(smartbi_pool, by_factory: Mapping[str, FactoryLists],
                            limit: int = 1000) -> Dict[str, int]:
    """Re-judge rows held at capture (``RECHECK_KEY``) against the CURRENT lists
    (``by_factory`` from :func:`all_factory_lists` — a failed lookup must abort
    the caller first). Clean rows are released (flag removed, usable again);
    rows that now name a secret term, person or customer are deleted — they
    would not have been captured with good lists. (The content rule does not
    depend on the lists and already ran on the raw text at capture.)
    Returns ``{"released": n, "deleted": n}``."""
    from smartbi.services.training_consent import RECHECK_KEY

    async with smartbi_pool.acquire() as conn:
        rows = await conn.fetch(_HELD_ROWS_SQL.format(key=RECHECK_KEY), limit)
    release, delete = [], []
    for r in rows:
        lists = lists_for_row(r["factory_id"], by_factory)
        bad = not r["factory_id"] or lists.names_any(r["input_text"], r["teacher_output"])
        (delete if bad else release).append(r["id"])
    async with smartbi_pool.acquire() as conn:
        if release:
            await conn.execute(_RELEASE_SQL.format(key=RECHECK_KEY), release)
        if delete:
            await conn.execute(_DELETE_SQL, delete)
    return {"released": len(release), "deleted": len(delete)}


def metadata_dict(raw: Any) -> Dict[str, Any]:
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except (ValueError, TypeError):
            raw = None
    return raw if isinstance(raw, dict) else {}


def clear_cache(*, forget_last_good: bool = True) -> None:
    _cache.clear()
    _all_cache.clear()
    if forget_last_good:
        _last_good.clear()
