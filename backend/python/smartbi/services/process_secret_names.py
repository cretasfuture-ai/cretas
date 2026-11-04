"""A factory's lists for training samples (privacy clause 6 + Steve 2026-10-24 /
2026-10-29 option A).

Per factory:

* ``terms``: its process-secret products' full names / codes / product ids
  (``common.process_secret_terms.secret_terms``: pure-numeric names and category
  words are not collected, codes match only on ASCII boundaries);
* ``directory``: its people (``users.full_name``) and customers
  (``customers.name``), matched exactly.

A sample naming any of ``directory`` is not stored / exported / judged, whatever
any setting says. A sample naming one of ``terms`` is not, while the training filter
(item d) of the factory that owns the sample is on. A sample with no
factory is matched against NOTHING — never against the union of every factory's
lists (that union blocked a no-factory row for naming another factory's 牛腱):
capture refuses rows without a factory, and readers drop them
(``row_names_protected_product`` is False for them, ``passes_training_rule`` and
``usable_rows_sql`` exclude them). Content that crosses factories is different: a row OF a
factory is checked against every factory's terms there (:func:`shared_content_lists`), as in
Java ProcessSecretSampleFilter.checkSharedContent.

Item d is read here and nowhere else in the service: :func:`factory_lists` and
:func:`all_factory_lists` stamp it on what they return (``FactoryLists.training_filter_on``),
so capture, export, the corpus scripts and the learners all follow it. It is read on every
call, not cached with the lists, through ``common.factory_secrecy.is_on`` (10 seconds, and a
read error or an unknown factory counts as ON). A factory with no lists at all is not in the
map and reads as ON. What crosses factories does not follow d:
:func:`cannot_be_shared` for the shared export and for every row capture is about to mask (a
masked row goes to the shared set unless consent changes later, and masking would hide what has to
be checked), and the finetune gate (``scripts/finetune/training_consent_gate.py``, one model for every factory)
reads no switch at all. ``FactoryLists.terms`` itself never changes with d, so anything built from
it stays independent of every switch.

The lists live in the Cretas main database; this module reads them through
``get_cretas_pool``. Lookup errors propagate, EXCEPT through
:func:`factory_lists_or_last_good`: capture then judges against the last lists
that loaded successfully for that factory and marks the row held for
re-judgement (``training_consent.RECHECK_KEY``); :func:`rejudge_held_rows`
re-checks held rows against the current lists.
"""
from __future__ import annotations

import asyncio
import json
import time
from dataclasses import dataclass, replace
from typing import Any, Dict, FrozenSet, Iterable, List, Mapping, Optional, Tuple

from common import factory_secrecy
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
    secret_terms_from_policy_rows,
)

_CACHE_SECONDS = 60.0


@dataclass(frozen=True)
class FactoryLists:
    """One factory's lists. ``terms`` and ``directory`` are already folded.

    INVARIANT (Steve 2026-11-04): ``directory`` (person and customer names) and the pay /
    attendance word + value rule (``training_consent.pay_word_with_value``) are PRIVACY, not
    secrecy. They are always filtered from training, learning and distillation samples and NO
    setting controls them. Only ``terms`` (protected products) and the process word + value
    rule follow the training filter (item d, ``training_filter_on``). Never skip a whole check
    because d is off: split it, as Java ProcessSecretSampleFilter.checkTrainingSample does.

    ``terms`` is always the full list; read ``filtered_terms`` for "what this sample's factory
    filters now". ``training_filter_on`` defaults to True: lists built by hand filter everything."""
    terms: FrozenSet[str] = frozenset()
    directory: FrozenSet[str] = frozenset()
    training_filter_on: bool = True

    @property
    def filtered_terms(self) -> FrozenSet[str]:
        return self.terms if self.training_filter_on else frozenset()

    def names_any(self, *texts: Optional[str]) -> bool:
        return mentions_any(self.filtered_terms, *texts) or mentions_any(self.directory, *texts)


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
    name / category / product_category and the policy's ``aliases`` (JSON array);
    directory rows are names."""
    names = [r["name"] if isinstance(r, Mapping) else r for r in directory_rows]
    return FactoryLists(terms=secret_terms_from_policy_rows(product_rows), directory=directory_names(names))


async def _with_training_filter(factory_id: str, lists: FactoryLists) -> FactoryLists:
    """``lists`` stamped with item d as it stands now. The caches keep the unstamped lists."""
    on = await factory_secrecy.is_on(factory_id, factory_secrecy.TRAINING_FILTER)
    return replace(lists, training_filter_on=on)


async def factory_lists(factory_id: str, pool=None) -> FactoryLists:
    """Lists of one factory, with its item d. Lookup errors propagate (never cached)."""
    return await _with_training_filter(factory_id, await _load_factory_lists(factory_id, pool))


async def _load_factory_lists(factory_id: str, pool=None) -> FactoryLists:
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
        return await _with_training_filter(factory_id, last), True


async def all_factory_lists(pool=None) -> Mapping[str, FactoryLists]:
    """``{factory_id: FactoryLists}`` for every factory with protected products, people or
    customers, each with its item d. Lookup errors propagate (never cached)."""
    raw = await _load_all_factory_lists(pool)
    stamped = await asyncio.gather(*(_with_training_filter(f, lists) for f, lists in raw.items()))
    return dict(zip(raw, stamped))


async def _load_all_factory_lists(pool=None) -> Mapping[str, FactoryLists]:
    """Every factory's lists (cached 60 s). Errors propagate (never cached)."""
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
    """Fingerprint of the terms each factory filters now (corpus_judge records it on rows it
    excluded, and re-judges rows excluded under a different list). Switching item d changes it
    only for a factory that has terms: elsewhere rows excluded for a process value stay excluded."""
    return fingerprint(f"{f}\t{t}" for f, lists in (by_factory or {}).items() for t in lists.filtered_terms)


SAMPLE_TEXT_FIELDS: Tuple[str, ...] = ("input_text", "teacher_output")


def row_names_protected_product(row: Mapping[str, Any],
                                by_factory: Optional[Mapping[str, FactoryLists]],
                                fields: Iterable[str] = SAMPLE_TEXT_FIELDS) -> bool:
    """True when a ``smart_bi_distillation_samples`` row (``factory_id`` plus the text
    ``fields``) names one of the process-secret terms its factory filters (item d)."""
    lists = lists_for_row(row.get("factory_id"), by_factory)
    return mentions_any(lists.filtered_terms, *(row.get(f) for f in fields))


def cannot_be_shared(lists: FactoryLists, input_text: Optional[str], answer: Optional[str]) -> bool:
    """What item d may let into the factory's own data but never across factories: one of its
    process-secret terms, or a process word with a value. Reads ``terms``, never
    ``training_filter_on``: no secrecy setting opens this, as in Java
    ProcessSecretSampleFilter.checkSharedContent (a factory with d off, or with everything off,
    still keeps these out of what other factories get). Needs the RAW text: masking turns
    老卤牛肉2号 into 老卤牛肉<数值>号 and 盐放 3 克 into 盐放 <数值> 克, and neither matches afterwards."""
    from smartbi.services.training_consent import qa_process_value

    return mentions_any(lists.terms, input_text, answer) or qa_process_value(input_text, answer)


def shared_content_lists(by_factory: Optional[Mapping[str, FactoryLists]]) -> FactoryLists:
    """Every factory's terms in one list, for what crosses factories (Steve 2026-11-04): a
    co-packer's row can name its customer's protected product, and a same-named product of
    another factory is dropped with it, which only costs a row. No setting is read."""
    return FactoryLists(terms=frozenset(t for lists in (by_factory or {}).values() for t in lists.terms))


def row_holds_secrets_for_shared_content(row: Mapping[str, Any], shared: FactoryLists) -> bool:
    """For the shared export: :func:`cannot_be_shared` on the row, or a term in masked form (a row
    masked before the product was protected: capture could not refuse it then). ``shared`` comes
    from :func:`shared_content_lists`."""
    from smartbi.services.training_consent import masked_terms

    input_text, answer = row.get("input_text"), row.get("teacher_output")
    return cannot_be_shared(shared, input_text, answer) or mentions_any(masked_terms(shared.terms), input_text, answer)


def row_has_process_value(row: Mapping[str, Any],
                          by_factory: Optional[Mapping[str, FactoryLists]],
                          fields: Iterable[str] = SAMPLE_TEXT_FIELDS) -> bool:
    """True when a row holds a process word with a value and its factory's item d is on. Capture
    does not stop this while d is off, so a row kept then can reach a reader after d is turned on;
    every reader checks again. ``teacher_output`` is the answer, the other fields are input.
    Synthetic seed rows are made up: nothing in them is anyone's process."""
    from smartbi.services.training_consent import is_synthetic_factory, qa_process_value

    factory_id = row.get("factory_id")
    if is_synthetic_factory(factory_id) or not lists_for_row(factory_id, by_factory).training_filter_on:
        return False
    return any(qa_process_value(None, row.get(f)) if f == "teacher_output" else qa_process_value(row.get(f), None)
               for f in fields)


def row_has_private_content(row: Mapping[str, Any],
                            by_factory: Optional[Mapping[str, FactoryLists]],
                            fields: Iterable[str] = SAMPLE_TEXT_FIELDS) -> bool:
    """True when a row names a person or customer of its own factory, or has a pay /
    attendance word with a value. Privacy, not secrecy (Steve 2026-11-04): no setting controls
    it (``FactoryLists``). Capture already refuses such rows; this catches what capture could
    not know, such as a person added to the directory afterwards. Synthetic seed rows are made
    up and belong to no one: nothing in them is anyone's pay."""
    from smartbi.services.training_consent import is_synthetic_factory, pay_word_with_value

    factory_id = row.get("factory_id")
    if is_synthetic_factory(factory_id):
        return False
    texts = [row.get(f) for f in fields]
    return mentions_any(lists_for_row(factory_id, by_factory).directory, *texts) or pay_word_with_value(*texts)


def drop_rows_naming_protected_products(
        rows: Iterable[Any],
        by_factory: Optional[Mapping[str, FactoryLists]],
        fields: Iterable[str] = SAMPLE_TEXT_FIELDS) -> List[Any]:
    """Rows that hold nothing private (``row_has_private_content``, always checked) and, while
    their factory's item d is on, name none of its process-secret terms and hold no process word
    with a value (corpus scripts: LLM judge, human review sampling, eval freeze, synthesis
    report). Rows must carry ``factory_id`` and the text ``fields``. A real row with no
    factory is dropped as well: it cannot be checked against anyone's lists
    (``usable_rows_sql`` already excludes it; this is the second lock).
    ``by_factory`` comes from ``all_factory_lists``: a failed lookup must abort
    the caller, never be read as "nothing protected"."""
    fields = tuple(fields)
    return [r for r in rows
            if r.get("factory_id") and not row_names_protected_product(r, by_factory, fields)
            and not row_has_process_value(r, by_factory, fields)
            and not row_has_private_content(r, by_factory, fields)]


# ---- held rows (captured against the last good lists) ----

_HELD_ROWS_SQL = """
    SELECT id, factory_id, input_text, teacher_output, metadata
    FROM smart_bi_distillation_samples
    WHERE metadata ? '{key}'
    ORDER BY id
    LIMIT $1
"""
# released: the hold marker (and the raw text it carried) is dropped. A marker that
# carried raw text means the stored text was desensitized at capture: only now, with the
# raw copy gone, is the row stamped desensitized (factory erasure keeps such rows).
_RELEASE_SQL = """
    UPDATE smart_bi_distillation_samples
    SET metadata = (metadata - '{key}')
        || CASE WHEN jsonb_typeof(metadata -> '{key}') = 'object'
                THEN jsonb_build_object('{desensitized}', true) ELSE '{{}}'::jsonb END
    WHERE id = ANY($1::bigint[])
"""
# rejected (Steve 2026-11-03 Q5, A): flagged and KEPT for manual review, never deleted;
# usable_rows_sql / passes_training_rule exclude it. Only the reason, the list version and
# the time are kept — never the raw text of the hold marker (second review, item 1).
_REJECT_SQL = """
    UPDATE smart_bi_distillation_samples
    SET metadata = (metadata - '{key}') || jsonb_build_object('{rejected}', $2::jsonb)
    WHERE id = $1
"""
# Held rows that could not be judged within the retention are deleted, raw copy and all
# (second review: the Python hold had no expiry). Same retention as the Java hold table
# (cretas.training.secrecy-hold.max-age, P7D); test_process_secret_corpus_scripts pins both.
HOLD_MAX_AGE_DAYS = 7
_EXPIRE_SQL = """
    DELETE FROM smart_bi_distillation_samples
    WHERE metadata ? '{key}' AND created_at < NOW() - make_interval(days => $1)
"""


async def expire_held_rows(conn, max_age_days: int = HOLD_MAX_AGE_DAYS) -> int:
    """Delete held rows older than the retention; returns how many."""
    from smartbi.services.training_consent import RECHECK_KEY

    status = await conn.execute(_EXPIRE_SQL.format(key=RECHECK_KEY), max_age_days)
    try:
        return int(str(status).split()[-1])
    except (ValueError, IndexError):
        return 0


def held_raw_texts(row: Mapping[str, Any]) -> Tuple[Optional[str], Optional[str]]:
    """The texts a held row is re-judged on: the RAW capture text kept in the hold
    marker (2026-11-03 review N3: a desensitized text hides names behind 产品A / <数值>);
    rows stored raw carry ``True`` and are judged on the stored text."""
    from smartbi.services.training_consent import RECHECK_KEY

    marker = metadata_dict(row.get("metadata")).get(RECHECK_KEY)
    if isinstance(marker, dict):
        return marker.get("raw_input"), marker.get("raw_output")
    return row.get("input_text"), row.get("teacher_output")


async def rejudge_held_rows(smartbi_pool, by_factory: Mapping[str, FactoryLists],
                            limit: int = 1000) -> Dict[str, int]:
    """Re-judge rows held at capture (``RECHECK_KEY``) against the CURRENT lists
    (``by_factory`` from :func:`all_factory_lists` — a failed lookup must abort
    the caller first), on the raw capture text. Clean rows are released (marker and
    raw text removed, usable again). Rows that now name a person or customer, hold a pay value
    or, while the factory's item d is on, name a secret term or hold a process value are
    flagged ``REJECTED_KEY`` and kept for manual review, and so are rows whose stored text is
    masked and that fail :func:`cannot_be_shared` whatever d says — never
    deleted (Q5); they stay out of every export / training read, and keep only
    the reason, the list version and the time (no raw text). Held rows past the
    retention are deleted first.
    Returns ``{"released": n, "rejected": n, "expired": n}``."""
    from datetime import datetime, timezone

    from smartbi.services.training_consent import (
        DESENSITIZED_KEY,
        RECHECK_KEY,
        REJECTED_KEY,
        qa_has_blocked_content,
    )

    async with smartbi_pool.acquire() as conn:
        expired = await expire_held_rows(conn)
        rows = await conn.fetch(_HELD_ROWS_SQL.format(key=RECHECK_KEY), limit)
    release, reject = [], []
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    for r in rows:
        lists = lists_for_row(r["factory_id"], by_factory)
        raw_in, raw_out = held_raw_texts(r)
        version = terms_fingerprint({r["factory_id"] or "": lists})
        if not r["factory_id"]:
            reject.append((r["id"], {"reason": "no_factory", "lists": version, "at": now}))
        elif lists.names_any(raw_in, raw_out):
            reject.append((r["id"], {"reason": "names_factory_list", "lists": version, "at": now}))
        elif qa_has_blocked_content(raw_in, raw_out, directory=lists.directory,
                                    training_filter_on=lists.training_filter_on):
            reject.append((r["id"], {"reason": "blocked_content", "lists": version, "at": now}))
        elif (isinstance(metadata_dict(r["metadata"]).get(RECHECK_KEY), dict)
              and cannot_be_shared(lists, raw_in, raw_out)):
            reject.append((r["id"], {"reason": "masked_not_shareable", "lists": version, "at": now}))
        else:
            release.append(r["id"])
    async with smartbi_pool.acquire() as conn:
        if release:
            await conn.execute(_RELEASE_SQL.format(key=RECHECK_KEY, desensitized=DESENSITIZED_KEY), release)
        for row_id, detail in reject:
            await conn.execute(_REJECT_SQL.format(key=RECHECK_KEY, rejected=REJECTED_KEY), row_id,
                               json.dumps(detail, ensure_ascii=False))
    return {"released": len(release), "rejected": len(reject), "expired": expired}


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
