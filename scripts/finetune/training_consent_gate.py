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

    A row is dropped when any of its text fields carries recipe / process-
    parameter content. ``factory_id`` must be selected by the caller.
    """
    r = rules()
    keys = tuple(text_keys)
    kept: List[Dict] = []
    dropped = 0
    for row in rows:
        scope = r.scope_from_map(consent_map(), row.get("factory_id"))
        out = dict(row)
        for key in keys:
            keep, text = r.desensitize_utterance(scope, row.get(key))
            if not keep:
                break
            out[key] = text
        else:
            kept.append(out)
            continue
        dropped += 1
    logger.info("training rule: %d rows kept, %d dropped (process parameters)", len(kept), dropped)
    return kept
