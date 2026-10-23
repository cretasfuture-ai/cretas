"""Training-consent gate for the intent fine-tuning / labeling scripts.

Owner rule (Steve, 2026-10-22): no factory's real data enters any training,
fine-tuning, distillation or labeling set unless that factory authorized it.
The authority is the ``training_consent`` table in the SmartBI database; the
scope rules live in ``backend/python/smartbi/services/training_consent.py``,
loaded here by file path (these scripts run outside the backend package).

export_training_data / shadow_adjudicate / zpd_label_batch read
``intent_match_records`` (cretas DB, every tenant) and send real user text to
an outside LLM or into a training file. They now only read rows whose
factory_id is in ``consented_factories()``. If the consent table cannot be
read, the scripts stop (fail closed) instead of treating everyone as allowed.

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
from typing import List

logger = logging.getLogger(__name__)

_CONSENT_MODULE_PATH = (
    Path(__file__).resolve().parents[2]
    / "backend" / "python" / "smartbi" / "services" / "training_consent.py"
)

# Appended to an intent_match_records WHERE clause; its parameter is the list
# returned by consented_factories().
CONSENTED_FACTORY_CLAUSE = "AND factory_id = ANY(%s)"


def _load_rules():
    spec = importlib.util.spec_from_file_location("training_consent_rules", _CONSENT_MODULE_PATH)
    if spec is None or spec.loader is None:
        raise SystemExit(f"cannot load consent rules from {_CONSENT_MODULE_PATH}")
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
def consented_factories() -> List[str]:
    """Factories whose consent allows their data in a training / labeling set.

    Raises SystemExit when the consent table cannot be read: an unreadable
    table must never be taken as "everyone agreed".
    """
    rules = _load_rules()
    try:
        conn = _connect()
        try:
            with conn.cursor() as cur:
                cur.execute(rules.SELECT_ALL_SQL)
                consent = {row[0]: row[1] for row in cur.fetchall()}
        finally:
            conn.close()
    except Exception as e:  # noqa: BLE001 - any failure means "not proven"
        raise SystemExit(
            f"training_consent unreadable ({type(e).__name__}: {e}) — refusing to read "
            "factory data. Set CONSENT_DB_* to the SmartBI database."
        ) from e
    allowed = rules.factories_with_scope(consent, rules.CORPUS_CAPTURE_SCOPES)
    logger.info("training consent: %d factor%s authorized %s", len(allowed),
                "y" if len(allowed) == 1 else "ies", allowed)
    return allowed
