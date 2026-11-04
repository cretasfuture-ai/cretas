"""Training-data rule for the intent fine-tuning / labeling scripts.

Owner rule (Steve, 2026-10-24): intent mappings (utterance -> intent) are used
by default; an utterance with recipe / process-parameter content is dropped for
every factory, and an un-authorized factory's utterance has its numbers and
phone / ID / bank numbers masked. The rules live in
``backend/python/smartbi/services/training_consent.py`` (``desensitize_utterance``),
loaded here by file path (these scripts run outside the backend package); the
authorizations are the ``training_consent`` table in the SmartBI database.

export_training_data / shadow_adjudicate / zpd_label_batch read
``intent_match_records`` (cretas DB, every tenant) and send user text to an
outside LLM or into a training file; every row goes through
``apply_utterance_rule`` first. Withdrawn factories (clause 10) are dropped.
If the consent table cannot be read the script stops: an unreadable table could
hide a withdrawal.

No secrecy switch is read here, the training filter (item d) included, on purpose:
export_training_data merges every factory's rows into one file for one model (the
records do not even carry factory_id) and the labeling scripts post straight to
DashScope, past the egress redaction. That is content crossing factories, which no
switch opens (Java ProcessSecretSampleFilter.checkSharedContent). d is the default-off
per-factory choice; following it here would put every such factory's protected
products into the model all factories share. To follow d, partition by factory first.

Consent DB connection (SmartBI database, NOT the cretas DB the scripts read):
    CONSENT_DB_HOST / CONSENT_DB_PORT / CONSENT_DB_NAME / CONSENT_DB_USER /
    CONSENT_DB_PASSWORD, falling back to the POSTGRES_* host/port/user/password
    the scripts already use and to database ``smartbi_db``.
"""
from __future__ import annotations

import importlib.util
import logging
import os
from functools import lru_cache
from pathlib import Path
from typing import Dict, Iterable, List

logger = logging.getLogger(__name__)

_CONSENT_MODULE_PATH = (
    Path(__file__).resolve().parents[2]
    / "backend" / "python" / "smartbi" / "services" / "training_consent.py"
)


@lru_cache(maxsize=1)
def rules():
    spec = importlib.util.spec_from_file_location("training_consent_rules", _CONSENT_MODULE_PATH)
    if spec is None or spec.loader is None:
        raise SystemExit(f"cannot load training rules from {_CONSENT_MODULE_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _connect():
    import psycopg2

    return psycopg2.connect(
        host=os.getenv("CONSENT_DB_HOST", os.getenv("POSTGRES_HOST", "localhost")),
        port=int(os.getenv("CONSENT_DB_PORT", os.getenv("POSTGRES_PORT", "5432"))),
        dbname=os.getenv("CONSENT_DB_NAME", "smartbi_db"),
        user=os.getenv("CONSENT_DB_USER", os.getenv("POSTGRES_USER", "cretas_user")),
        password=os.getenv("CONSENT_DB_PASSWORD", os.getenv("POSTGRES_PASSWORD", "")),
        connect_timeout=10,
    )


def _connect_cretas():
    """The cretas main database (the one these scripts read): people / customers."""
    import psycopg2

    return psycopg2.connect(
        host=os.getenv("POSTGRES_HOST", "localhost"),
        port=int(os.getenv("POSTGRES_PORT", "5432")),
        dbname=os.getenv("POSTGRES_DB", "cretas_db"),
        user=os.getenv("POSTGRES_USER", "cretas_user"),
        password=os.getenv("POSTGRES_PASSWORD", ""),
        connect_timeout=10,
    )


def _dict_rows(cur):
    cols = [c[0] for c in cur.description]
    return [dict(zip(cols, row)) for row in cur.fetchall()]


@lru_cache(maxsize=1)
def factory_lists_map() -> Dict[str, tuple]:
    """``{factory_id: (secret_terms, directory)}`` from the cretas database: each factory's
    process-secret terms (full name / code / id / registered aliases) and its people /
    customers / contacts — the same lists capture and export use (2026-11-03 review B2).
    Unreadable -> SystemExit: without them a secret product or a person's name could
    leave in a training file / to an outside LLM. (A batch script has no "last good
    lists" to fall back on, so the run stops instead of holding rows.)"""
    try:
        conn = _connect_cretas()
        try:
            with conn.cursor() as cur:
                cur.execute(rules().ALL_PROTECTED_TERMS_SQL)
                products = _dict_rows(cur)
                cur.execute(rules().ALL_FACTORY_DIRECTORY_SQL)
                people = _dict_rows(cur)
        finally:
            conn.close()
        lists = rules().lists_by_factory(products, people)
    except Exception as e:  # noqa: BLE001
        raise SystemExit(
            f"factory lists (process-secret terms / people / customers) unreadable "
            f"({type(e).__name__}: {e}) — refusing to read factory data. Set POSTGRES_* to the cretas database."
        ) from e
    logger.info("factory lists: %d factories", len(lists))
    return lists


@lru_cache(maxsize=1)
def consent_map() -> Dict[str, str]:
    """``{factory_id: scope}`` (withdrawn factories read as 'withdrawn'); unreadable -> SystemExit."""
    try:
        conn = _connect()
        try:
            with conn.cursor() as cur:
                cur.execute(rules().SELECT_ALL_SQL)
                consent = {row[0]: rules().normalize_scope(row[1]) for row in cur.fetchall()}
        finally:
            conn.close()
    except Exception as e:  # noqa: BLE001 - an unreadable table could hide a withdrawal
        raise SystemExit(
            f"training_consent unreadable ({type(e).__name__}: {e}) — refusing to read "
            "factory data. Set CONSENT_DB_* to the SmartBI database."
        ) from e
    logger.info("training consent rows: %d", len(consent))
    return consent


def apply_utterance_rule(rows: Iterable[Dict], text_keys: Iterable[str]) -> List[Dict]:
    """Rows that may be used, with their text fields desensitized per factory.

    Same rules as capture / export (2026-11-03 review B2). A row is dropped when
    it has no factory, belongs to a restaurant / test / backup tenant, names any
    factory's process-secret terms (one model for every factory: Steve 2026-11-04)
    or its own factory's people or customers, or carries a
    recipe / parameter word with a value. ``factory_id`` must be selected by the
    caller.
    """
    r = rules()
    keys = tuple(text_keys)
    kept: List[Dict] = []
    every_factorys_terms = frozenset(t for terms, _ in factory_lists_map().values() for t in terms)
    dropped = {"no_factory": 0, "tenant": 0, "factory_list": 0, "content": 0}
    for row in rows:
        factory_id = row.get("factory_id") or ""
        if not factory_id:
            dropped["no_factory"] += 1
            continue
        if not r.is_training_tenant(factory_id):
            dropped["tenant"] += 1
            continue
        _, directory = factory_lists_map().get(factory_id, (frozenset(), frozenset()))
        texts = [row.get(k) for k in keys]
        if r.mentions_any(every_factorys_terms, *texts) or r.mentions_any(directory, *texts):
            dropped["factory_list"] += 1
            continue
        scope = r.scope_from_map(consent_map(), factory_id)
        out = dict(row)
        for key in keys:
            keep, text = r.desensitize_utterance(scope, row.get(key), directory=directory)
            if not keep:
                break
            out[key] = text
        else:
            kept.append(out)
            continue
        dropped["content"] += 1
    logger.info("training rule: %d rows kept, dropped %s", len(kept), dropped)
    return kept
