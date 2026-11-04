"""Shared distillation-corpus capture helper.

Every LLM "teacher pair" (a strong-model answer to a structured/user input) is
training material for the future vertical-model distillation. Originally only
the materialization path (``llm_materializer._persist_distillation_sample``)
wrote into ``smart_bi_distillation_samples``; the high-volume LIVE flows (AI
Q&A answers in ``chat.py`` and 经营驾驶舱 report insights in
``agent/orchestrator.py``) leaked their teacher pairs.

This module extracts the materializer's persistence logic into one reusable,
fire-and-forget helper so every capture site shares IDENTICAL hash / INSERT /
dedup behavior.

HARD RULE -- Fire-and-forget contract (per the original
``_persist_distillation_sample`` docstring): training-data capture is
best-effort. It MUST NEVER raise — any DB / pool / serialization error is
swallowed with a warning so it can never affect the user-facing answer or the
calling flow.

Dedup / hash behavior (faithful extraction of the original — see report):
``input_hash = sha256(input_text)``. The table has a UNIQUE index on
``input_hash`` (``uq_distill_input_hash``); ON CONFLICT refreshes the existing
row's teacher_output / model / template_codes / metadata instead of stacking
near-duplicate samples. This matches the materializer exactly (its
``_get_cached_teacher_output`` content-hash cache looks the row up by
``sha256(prompt)`` where ``prompt == input_text`` — so the hash MUST stay
``sha256(input_text)`` or that cache would silently miss).

Training rule (owner, 2026-10-24 / 2026-10-29 option A; see ``training_consent``):
  * a real sample needs a factory: with none we cannot tell whose lists to
    match it against (the old fallback — every factory's names — blocked rows
    for naming another factory's product), so it is NOT written. Inside a
    request the factory comes from the authenticated tenant; background jobs
    pass the factory of the record they are working on;
  * restaurant / test / backup tenants (``is_training_tenant``) are not written;
  * a sample with per-person pay or one of the factory's people / customers is
    NOT written — authorized or not, whatever any setting says
    (``qa_has_blocked_content``);
  * while the factory's training filter (item d, read with its lists: default off, a read
    error counts as on) is on, a sample with a process word plus a concrete value or
    naming one of its process-secret terms is NOT written either. A sample about to be
    masked is held to that whatever d says: it can only reach the shared set, which no setting
    opens, and after masking neither check can see it (``cannot_be_shared``).
    If the factory's lists cannot be read, the last lists that loaded for it
    are used and the row is written HELD (``metadata.secrecy_recheck``; every
    corpus reader skips it until ``rejudge_held_rows`` re-checks it). No lists
    ever loaded -> not written;
  * otherwise a factory with ``training_consent`` 'private' / 'shared_anon' is
    stored as-is, a withdrawn factory or an unreadable consent row is not
    stored at all, and everyone else (no row, 'none') is stored desensitized
    (``desensitize_for_training``: generic name placeholders, phone / ID / bank
    numbers removed, every number -> <数值>), tagged ``metadata.desensitized``;
  * every real sample written is tagged ``metadata.training_rule`` with the
    rule version; synthetic seed tenants are stored as-is.
Skips are logged once per (reason, factory).
Inside a request the factory is the authenticated one from
``smartbi.tenant_ctx`` (set by auth_middleware from the JWT or the
internal-secret header); a caller-supplied factory_id that disagrees with it
is refused. Background jobs and scripts have no request tenant, so their
factory_id (read from server-side records) is used. The env kill switch
``SMARTBI_DISTILL_CAPTURE=0`` still turns everything off on top of this.

Side effect worth knowing: the materializer / chart-insight / agent-insight
read this table back as a content-hash cache keyed by sha256(input_text). A
desensitized row is stored under the hash of its desensitized text, so it is
never served back as a cached answer (that would show placeholders to a user);
un-authorized factories miss the cache and call the LLM.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import time
from typing import Any, Dict, List, Optional

from smartbi.services.corpus_anonymize import desensitize_for_training
from smartbi.services.process_secret_names import (
    cannot_be_shared,
    expire_held_rows,
    factory_lists_or_last_good,
)
from smartbi.services.training_consent import (
    DESENSITIZED_KEY,
    RECHECK_KEY,
    RULE_KEY,
    RULE_VERSION,
    fetch_consent_scope,
    is_synthetic_factory,
    is_training_tenant,
    is_withdrawn,
    keeps_raw,
    qa_has_blocked_content,
)

logger = logging.getLogger(__name__)

# Same env kill-switch the materializer used — set to "0" to disable all
# distillation capture without a redeploy.
_CAPTURE_ENV = "SMARTBI_DISTILL_CAPTURE"

# Held rows past the retention are swept from the capture path (second review: the
# re-judge only runs from corpus_judge, so without this a held row and its raw copy
# could stay forever). It runs at the top of EVERY capture call — skipped / refused /
# disabled captures included (review round 3, item 3): with capture switched off nothing else
# would ever expire the held rows and their raw text — at most once per EXPIRY_INTERVAL_S per
# process, on its own connection, bounded by EXPIRY_TIMEOUT_S (review round 4, item 3: an
# exhausted pool must not hold a capture call up); a timed-out sweep is retried after
# EXPIRY_RETRY_S. It never blocks for longer or fails a capture.
EXPIRY_INTERVAL_S = 3600.0
EXPIRY_TIMEOUT_S = 2.0
EXPIRY_RETRY_S = 60.0
_LAST_EXPIRY = {"at": None}


async def _expire_on_own_connection(pool) -> int:
    async with pool.acquire() as conn:
        return await expire_held_rows(conn)


async def _maybe_expire_held_rows(pool) -> None:
    now = time.monotonic()
    last = _LAST_EXPIRY["at"]
    if last is not None and now - last < EXPIRY_INTERVAL_S:
        return
    _LAST_EXPIRY["at"] = now
    try:
        n = await asyncio.wait_for(_expire_on_own_connection(pool), timeout=EXPIRY_TIMEOUT_S)
        if n:
            logger.info("[distill] deleted %d held rows past the retention", n)
    except asyncio.TimeoutError:
        # pool busy / slow delete: skip, try again shortly instead of an hour later
        _LAST_EXPIRY["at"] = now - EXPIRY_INTERVAL_S + EXPIRY_RETRY_S
        # a warning, so a sweep that keeps timing out (raw text not being expired) is visible
        logger.warning("[distill] held-row expiry timed out after %.1fs (pool busy / slow delete), "
                       "retried in %.0fs", EXPIRY_TIMEOUT_S, EXPIRY_RETRY_S)
    except Exception as e:  # noqa: BLE001 - housekeeping never blocks a capture
        logger.warning("[distill] held-row expiry failed (retried next interval): %s", e)


# (reason, factory) pairs already logged — "log once" per process so a busy
# un-consented tenant does not flood the log.
_SKIP_LOGGED: set = set()


def _log_skip_once(reason: str, factory_id: Optional[str], detail: str = "") -> None:
    key = (reason, factory_id)
    if key in _SKIP_LOGGED:
        return
    _SKIP_LOGGED.add(key)
    logger.info(
        "[distill] capture skipped: %s (factory=%s)%s — further skips for this "
        "factory/reason are not logged", reason, factory_id,
        f" {detail}" if detail else "",
    )


# Caller metadata keys holding a raw copy of the question (synthesis_engine
# stores metadata.query = the user's question). Desensitized like input_text.
_RAW_QUESTION_METADATA_KEYS = ("query",)


def _desensitize_metadata(metadata: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """Metadata of a desensitized sample must not keep raw text: the question
    copies are desensitized, and the grounding check's violation list (raw
    answer clauses / claimed figures) is reduced to its count."""
    if not metadata:
        return metadata
    out = dict(metadata)
    for key in _RAW_QUESTION_METADATA_KEYS:
        value = out.get(key)
        if isinstance(value, str) and value:
            out[key] = desensitize_for_training(value, "")[0]
    grounding = out.get("grounding")
    if isinstance(grounding, dict) and "violations" in grounding:
        grounding = dict(grounding)
        violations = grounding.pop("violations")
        grounding["violation_count"] = (
            len(violations) if isinstance(violations, (list, tuple)) else int(bool(violations)))
        out["grounding"] = grounding
    return out


def _request_factory_id() -> Optional[str]:
    """Authenticated tenant of the current request, or None outside a request."""
    try:
        from smartbi import tenant_ctx
    except Exception:  # pragma: no cover - module always present in-app
        return None
    fid = tenant_ctx.get_factory_id()
    if not fid or fid == tenant_ctx.INTERNAL_SENTINEL:
        return None
    return fid


def _resolve_capture_factory(factory_id: Optional[str]) -> tuple:
    """``(ok, factory)``: ok=False when the caller names a factory other than
    the authenticated one (refused); factory may be None (no owner)."""
    authenticated = _request_factory_id()
    if authenticated is not None:
        if factory_id and factory_id != authenticated:
            _log_skip_once(
                "factory_id does not match the authenticated tenant",
                factory_id, f"authenticated={authenticated}",
            )
            return False, None
        return True, authenticated
    return True, (factory_id or None)

_INSERT_SQL = """
    INSERT INTO smart_bi_distillation_samples (
        source, business_type, factory_id, task_type, template_codes,
        system_prompt, input_text, teacher_model, teacher_output,
        input_hash, quality, metadata
    ) VALUES (
        $1, $2, $3, $4, $5,
        $6, $7, $8, $9,
        $10, $11, $12::jsonb
    )
    ON CONFLICT (input_hash) DO UPDATE SET
        teacher_output = EXCLUDED.teacher_output,
        teacher_model  = EXCLUDED.teacher_model,
        template_codes = EXCLUDED.template_codes,
        metadata       = EXCLUDED.metadata,
        created_at     = NOW()
    WHERE smart_bi_distillation_samples.factory_id IS NOT DISTINCT FROM EXCLUDED.factory_id
"""
# 2026-11-03 review N7b: the same input text captured for ANOTHER factory must not
# overwrite this row (factory B's answer would be attributed to factory A's row).
# The conditional DO UPDATE then writes nothing ("INSERT 0 0"); counted and logged.
CROSS_FACTORY_CONFLICTS = {"count": 0}


def compute_input_hash(input_text: str) -> str:
    """sha256(input_text) — faithful to the materializer's hash so the
    content-hash teacher-output cache stays consistent. Stable: same input →
    same hash."""
    return hashlib.sha256((input_text or "").encode("utf-8")).hexdigest()


async def persist_distillation_sample(
    pool,
    source: str,
    task_type: str,
    input_text: str,
    teacher_output: str,
    *,
    business_type: str = "unknown",
    factory_id: Optional[str] = None,
    system_prompt: Optional[str] = None,
    teacher_model: Optional[str] = None,
    template_codes: Optional[List[str]] = None,
    quality: Optional[int] = None,
    metadata: Optional[Dict[str, Any]] = None,
) -> None:
    """Write one distillation training sample. Fire-and-forget, NEVER raises.

    Persists the (input_text → teacher_output) pair into
    ``smart_bi_distillation_samples``. Idempotent on ``input_hash`` —
    re-capturing the same input refreshes the row's teacher_output instead of
    stacking near-duplicate samples.

    Args:
        pool: an asyncpg pool (or anything with ``.acquire()``). If ``None``,
            returns quietly (caller could not obtain a pool).
        source: capture origin, e.g. ``"materialization"`` / ``"chat_qa"`` /
            ``"agent_insight"`` (table column ``source``, VARCHAR(32)).
        task_type: ``"insights"`` / ``"qa"`` / ``"analysis"`` … (VARCHAR(32)).
        input_text: the structured / user prompt fed to the teacher (NOT NULL).
        teacher_output: the teacher model's raw response (NOT NULL).
        business_type: 业态分桶 (restaurant/factory/unknown). Defaults
            ``"unknown"``; ``None`` is also normalized to ``"unknown"`` since
            the column is NOT NULL.
        factory_id: tenant the sample belongs to. Inside a request the
            authenticated tenant wins and a different value is refused; a
            sample with no factory is stored desensitized.
        system_prompt: the teacher's system role, if readily available.
        teacher_model: e.g. ``"qwen3-max"``, if readily known.
        template_codes: applicable template codes (materialization); stored as a
            comma-joined string. ``None`` → NULL.
        quality: optional curation score (nullable).
        metadata: optional dict, JSON-encoded into the jsonb column. ``None`` →
            NULL.
    """
    if pool is None:
        return
    await _maybe_expire_held_rows(pool)          # every call, before any early return (hourly)
    if os.getenv(_CAPTURE_ENV, "1") == "0":
        return
    # Guard the NOT NULL columns: never attempt an INSERT we know will fail.
    if not input_text or not teacher_output:
        return
    ok, factory_id = _resolve_capture_factory(factory_id)
    if not ok:
        return
    synthetic = is_synthetic_factory(factory_id)
    held = False
    if not synthetic:
        if not factory_id:
            _log_skip_once("sample has no factory (cannot be matched against any factory's lists)",
                           None, f"source={source}")
            return
        if not is_training_tenant(factory_id) or (business_type or "") == "restaurant":
            _log_skip_once("restaurant / test tenant is never used for training", factory_id)
            return
        # The factory's lists (process-secret terms, people, customers) come from
        # the Cretas main DB, looked up before taking a capture connection. A
        # failed lookup falls back to the last good lists and holds the row.
        try:
            lists, held = await factory_lists_or_last_good(factory_id)
        except Exception as e:
            _log_skip_once("factory lists lookup failed (none loaded before), sample not stored",
                           factory_id, f"({type(e).__name__}: {e})")
            return
        # item d only relaxes the terms and process-value parts: lists.directory and the pay /
        # attendance value rule stay on whatever it says (see FactoryLists)
        if qa_has_blocked_content(input_text, teacher_output, directory=lists.directory,
                                  training_filter_on=lists.training_filter_on):
            _log_skip_once("process value / personnel / named person or customer is not used "
                           "for training", factory_id, f"source={source}")
            return
        if lists.names_any(input_text, teacher_output):
            _log_skip_once("sample names a process-secret product", factory_id)
            return
    try:
        async with pool.acquire() as conn:
            scope = "none"
            if factory_id and not synthetic:
                try:
                    scope = await fetch_consent_scope(conn, factory_id)
                except Exception as e:
                    # unreadable consent could hide a withdrawal -> do not store
                    _log_skip_once("training_consent lookup failed, sample not stored",
                                   factory_id, f"({type(e).__name__}: {e})")
                    return
            if is_withdrawn(scope):
                _log_skip_once("factory withdrew from training", factory_id)
                return
            if not synthetic and not keeps_raw(scope) and cannot_be_shared(lists, input_text, teacher_output):
                _log_skip_once("masked sample would carry a process-secret product or process value "
                               "into the shared set", factory_id, f"source={source}")
                return
            if not synthetic:
                tags: Dict[str, Any] = {RULE_KEY: RULE_VERSION}
                carries_raw = held and not keeps_raw(scope)
                if held:
                    # re-judged later on the RAW text (review N3): a desensitized text
                    # hides names behind 产品A / <数值>. The raw copy lives only in the
                    # hold marker; release drops it, rejection never copies it, the
                    # expiry sweep deletes the row (second review, item 1).
                    tags[RECHECK_KEY] = (True if keeps_raw(scope)
                                         else {"raw_input": input_text, "raw_output": teacher_output})
                if not keeps_raw(scope):
                    input_text, teacher_output = desensitize_for_training(
                        input_text, teacher_output)
                    metadata = _desensitize_metadata(metadata)
                    # a row still carrying raw text is NOT "desensitized": factory erasure
                    # keeps desensitized rows, so the stamp waits for the release
                    # (process_secret_names._RELEASE_SQL adds it when the raw copy goes)
                    if not carries_raw:
                        tags[DESENSITIZED_KEY] = True
                metadata = {**(metadata or {}), **tags}
            input_hash = compute_input_hash(input_text)
            codes_str = ",".join(template_codes) if template_codes else None
            metadata_json = (
                json.dumps(metadata, ensure_ascii=False) if metadata is not None else None
            )
            status = await conn.execute(
                _INSERT_SQL,
                source,
                (business_type or "unknown"),
                factory_id,
                task_type,
                codes_str,
                system_prompt,
                input_text,
                teacher_model,
                teacher_output,
                input_hash,
                quality,
                metadata_json,
            )
            if isinstance(status, str) and status.strip().endswith(" 0"):
                CROSS_FACTORY_CONFLICTS["count"] += 1
                logger.warning(
                    "[distill] same input already captured for another factory; row left as is "
                    "(factory=%s, cross-factory conflicts so far=%d)",
                    factory_id, CROSS_FACTORY_CONFLICTS["count"])
                return
        logger.debug(
            "[distill] captured sample (source=%s, task_type=%s, "
            "business_type=%s, factory=%s)",
            source, task_type, business_type, factory_id,
        )
    except Exception as e:
        # Training-data capture is best-effort. Never propagate.
        logger.warning("[distill] sample capture failed (non-blocking): %s", e)
