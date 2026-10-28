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

Training rule (owner, 2026-10-24; see ``training_consent`` for the full text):
  * a sample whose input (question + the data the model was given) touches
    process parameters / structure / problem handling or per-person pay, or
    whose answer names a specific one, is NOT written — for every factory,
    authorized or not (``qa_has_blocked_content``);
  * otherwise a factory with ``training_consent`` 'private' / 'shared_anon' is
    stored as-is, a withdrawn factory or an unreadable consent row is not
    stored at all, and everyone else (no row, 'none', no factory)
    is stored desensitized (``desensitize_for_training``: generic name
    placeholders, phone / ID / bank numbers removed, every number -> <数值>),
    tagged ``metadata.desensitized``;
  * every real sample written is tagged ``metadata.training_rule`` (the corpus
    scripts only read tagged rows); synthetic seed tenants are stored as-is.
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

import hashlib
import json
import logging
import os
from typing import Any, Dict, List, Optional

from smartbi.services.corpus_anonymize import desensitize_for_training
from smartbi.services.process_secret_names import (
    all_protected_product_names,
    any_factory_names,
    mentions_any,
    protected_product_names,
)
from smartbi.services.training_consent import (
    DESENSITIZED_KEY,
    RULE_KEY,
    RULE_VERSION,
    fetch_consent_scope,
    is_synthetic_factory,
    is_withdrawn,
    keeps_raw,
    qa_has_blocked_content,
)

logger = logging.getLogger(__name__)

# Same env kill-switch the materializer used — set to "0" to disable all
# distillation capture without a redeploy.
_CAPTURE_ENV = "SMARTBI_DISTILL_CAPTURE"

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
"""


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
    if os.getenv(_CAPTURE_ENV, "1") == "0":
        return
    if pool is None:
        return
    # Guard the NOT NULL columns: never attempt an INSERT we know will fail.
    if not input_text or not teacher_output:
        return
    ok, factory_id = _resolve_capture_factory(factory_id)
    if not ok:
        return
    synthetic = is_synthetic_factory(factory_id)
    if not synthetic and qa_has_blocked_content(input_text, teacher_output):
        _log_skip_once("process / personnel content is never used for training",
                       factory_id, f"source={source}")
        return
    if not synthetic:
        # privacy clause 6: a sample naming one of the factory's process-secret
        # products is not used for training. With no factory we cannot tell
        # whose product it names, so the process-secret products of ANY factory
        # count. Looked up (Cretas main DB) before taking a capture connection.
        try:
            if factory_id:
                names = await protected_product_names(factory_id)
            else:
                names = any_factory_names(await all_protected_product_names())
        except Exception as e:
            _log_skip_once("process-secret product lookup failed, sample not stored",
                           factory_id, f"({type(e).__name__}: {e})")
            return
        if mentions_any(names, input_text, teacher_output):
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
            if not synthetic:
                tags: Dict[str, Any] = {RULE_KEY: RULE_VERSION}
                if not keeps_raw(scope):
                    input_text, teacher_output = desensitize_for_training(
                        input_text, teacher_output)
                    tags[DESENSITIZED_KEY] = True
                metadata = {**(metadata or {}), **tags}
            input_hash = compute_input_hash(input_text)
            codes_str = ",".join(template_codes) if template_codes else None
            metadata_json = (
                json.dumps(metadata, ensure_ascii=False) if metadata is not None else None
            )
            await conn.execute(
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
        logger.debug(
            "[distill] captured sample (source=%s, task_type=%s, "
            "business_type=%s, factory=%s)",
            source, task_type, business_type, factory_id,
        )
    except Exception as e:
        # Training-data capture is best-effort. Never propagate.
        logger.warning("[distill] sample capture failed (non-blocking): %s", e)
