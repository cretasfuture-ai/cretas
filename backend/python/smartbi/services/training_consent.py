"""Training-consent lookups: the one place that reads ``training_consent``.

Owner rule (Steve): no factory's real data enters any training / fine-tuning /
distillation / labeling set unless that factory explicitly authorized it.
The table ``training_consent`` (migration ``V20261001_01__training_consent.sql``)
is the single source of truth; this module only reads it and states what each
scope allows. Every reader goes through here:

  * ``distillation_capture.persist_distillation_sample`` - capture time
  * ``scripts/export_distillation_dataset.py``           - distillation export
  * ``scripts/finetune/training_consent_gate.py``         - intent classifier
    data (export_training_data / shadow_adjudicate / zpd_label_batch), which
    loads this file by path because those scripts run outside the package.

Keep this module stdlib-only and free of package-relative imports: the finetune
scripts import it with ``importlib`` from its file path.

Scopes (CHECK constraint in the migration):
  none         default; no row means 'none'. Real data stays out of every set.
  private      raw data, only for that factory's own private model.
  shared_anon  anonymized data may enter the cross-tenant shared model.

Synthetic seed corpus (factory ids SEED_R / SEED_F) belongs to no tenant and
is not gated.
"""
from __future__ import annotations

from typing import Any, Dict, Iterable, Mapping, Optional

CONSENT_SCOPES = ("none", "private", "shared_anon")
DEFAULT_SCOPE = "none"

# Sentinel tenants of the synthetic seed corpus (seed_chart_insight_corpus.py).
SYNTHETIC_FACTORY_IDS = frozenset({"SEED_R", "SEED_F"})

# May a real sample be stored in the distillation corpus at all? The corpus
# feeds both the private export (needs 'private') and the shared export (needs
# 'shared_anon'), so either explicit authorization qualifies; 'none' does not.
CORPUS_CAPTURE_SCOPES = frozenset({"private", "shared_anon"})

# May a real sample enter a model shared across tenants? Only 'shared_anon',
# and only after anonymization.
SHARED_MODEL_SCOPES = frozenset({"shared_anon"})

# asyncpg placeholder style; the finetune scripts use SELECT_ALL_SQL only
# (no parameters, so it runs unchanged on psycopg2).
SELECT_SCOPE_SQL = "SELECT consent_scope FROM training_consent WHERE factory_id = $1"
SELECT_ALL_SQL = "SELECT factory_id, consent_scope FROM training_consent"


def normalize_scope(raw: Any) -> str:
    """Map a stored value to a known scope. Anything unexpected is 'none'."""
    if isinstance(raw, str) and raw in CONSENT_SCOPES:
        return raw
    return DEFAULT_SCOPE


def scope_from_map(consent: Mapping[str, str], factory_id: Optional[str]) -> str:
    """Scope of ``factory_id`` in a pre-loaded map; absent factory -> 'none'."""
    if not factory_id:
        return DEFAULT_SCOPE
    return normalize_scope(consent.get(factory_id))


def is_synthetic_factory(factory_id: Optional[str]) -> bool:
    return (factory_id or "") in SYNTHETIC_FACTORY_IDS


def factories_with_scope(consent: Mapping[str, str], scopes: Iterable[str]) -> list:
    """Sorted factory ids whose scope is in ``scopes``."""
    wanted = frozenset(scopes)
    return sorted(f for f, s in consent.items() if f and normalize_scope(s) in wanted)


async def fetch_consent_scope(conn, factory_id: str) -> str:
    """Consent scope of one factory over an asyncpg-style connection.

    No row -> 'none'. Database errors (table missing, permission, connection)
    are NOT swallowed: the caller decides, and capture treats them as "do not
    capture".
    """
    raw = await conn.fetchval(SELECT_SCOPE_SQL, factory_id)
    return normalize_scope(raw)


async def fetch_consent_map(conn) -> Dict[str, str]:
    """``{factory_id: scope}`` for every row. Errors propagate to the caller."""
    rows = await conn.fetch(SELECT_ALL_SQL)
    return {r["factory_id"]: normalize_scope(r["consent_scope"]) for r in rows}
