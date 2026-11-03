"""Which distillation rows count as "passed the training rule" (2026-10-29 待办第 7 条).

``usable_rows_sql`` (judge / eval freeze / review sampling / synthesis report) and
``passes_training_rule`` (export) must agree:

* ``metadata.training_rule`` must be a REAL capture rule version — "key present"
  is not enough (prod row 16743, a drill: training_rule = "drill", no factory);
* restaurant tenants (MOCK_REST, DEMO_REST; the restaurant bucket) and test /
  backup tenants (QATEST_*, F006BK) are never training data;
* a real row without a factory is never usable (cannot be matched against any
  factory's lists); a row held at capture (secrecy_recheck) waits for re-judgement.

The SQL predicate runs against a real PostgreSQL (``SMARTBI_PG_DSN``, set by
scripts/ci/run-with-test-postgres.sh in the Python gate); a missing DSN fails on CI.
"""
from __future__ import annotations

import json
import os
import uuid

import pytest

from smartbi.services import training_consent as tc

# (id, factory_id, business_type, metadata) — the same rows go through the SQL
# predicate and the export rule; ``usable`` is the expected verdict for both.
ROWS = [
    (16743, None, "unknown", {"desensitized": "true", "training_rule": "drill"}, False),   # prod drill row
    (2, "F006", "factory", {"training_rule": "drill"}, False),                            # drill tag, factory
    (3, "MOCK_REST", "restaurant", {"training_rule": "2026-10-24", "desensitized": True}, False),
    (4, "MOCK_REST", "factory", {"training_rule": "2026-10-24"}, False),                  # by tenant id alone
    (5, "DEMO_REST", "factory", {"training_rule": "2026-10-24"}, False),
    (6, "F006", "restaurant", {"training_rule": "2026-10-24"}, False),                    # by bucket alone
    (7, "QATEST_1021_001", "factory", {"training_rule": "2026-10-24"}, False),
    (8, "F006BK", "factory", {"training_rule": "2026-10-24"}, False),
    (9, None, "factory", {"training_rule": "2026-10-24"}, False),                         # no factory
    (10, "", "factory", {"training_rule": "2026-10-24"}, False),
    (11, "F006", "factory", {"training_rule": "2026-11-03", "secrecy_recheck": True}, False),  # held
    (12, "F006", "factory", {"desensitized": True}, False),                               # pre-rule row
    (13, "F006", "factory", {"training_rule": "2026-10-24", "desensitized": True}, True),  # control
    (14, "F006", "factory", {"training_rule": "2026-11-03"}, True),                        # control
    (15, "CATTLE_3701_001", None, {"training_rule": "2026-11-03"}, True),                  # control, NULL bucket
]


def test_real_rule_versions_and_tenant_constants():
    assert tc.RULE_VERSION in tc.RULE_VERSIONS and "2026-10-24" in tc.RULE_VERSIONS
    assert "drill" not in tc.RULE_VERSIONS
    assert {"MOCK_REST", "DEMO_REST", "F006BK"} <= tc.NON_TRAINING_FACTORY_IDS
    assert not tc.is_training_tenant("MOCK_REST") and not tc.is_training_tenant("QATEST_X")
    assert not tc.is_training_tenant(None) and not tc.is_training_tenant("")
    assert tc.is_training_tenant("F006") and tc.is_training_tenant("CATTLE_3701_001")


def test_export_rule_agrees_with_the_expected_verdicts():
    from scripts.export_distillation_dataset import passes_training_rule

    got = {i: passes_training_rule({"factory_id": f, "business_type": b, "metadata": m,
                                    "input_text": "本月营收多少", "teacher_output": "增长"}, {})
           for i, f, b, m, _ in ROWS}
    # the held row passes the EXPORT (it re-judges with the current lists itself); every
    # other verdict is the same as the SQL predicate's
    expected = {i: (u or i == 11) for i, _f, _b, _m, u in ROWS}
    assert got == expected


def _dsn():
    dsn = os.getenv("SMARTBI_PG_DSN")
    if not dsn:
        if os.getenv("CI"):
            pytest.fail("SMARTBI_PG_DSN is required on CI (run-with-test-postgres.sh sets it)")
        pytest.skip("no local PostgreSQL (set SMARTBI_PG_DSN to run the SQL predicate check)")
    return dsn


async def test_usable_rows_sql_on_postgres():
    asyncpg = pytest.importorskip("asyncpg")
    conn = await asyncpg.connect(_dsn())
    schema = "training_rule_" + uuid.uuid4().hex[:12]
    try:
        await conn.execute(f"CREATE SCHEMA {schema}")
        await conn.execute(f"SET search_path TO {schema}")
        await conn.execute("""
            CREATE TABLE smart_bi_distillation_samples (
                id BIGINT PRIMARY KEY, factory_id VARCHAR(64), business_type VARCHAR(32),
                metadata JSONB)""")
        await conn.execute("CREATE TABLE training_consent (factory_id VARCHAR(64) PRIMARY KEY, "
                           "consent_scope VARCHAR(32), withdrawn_at TIMESTAMP)")
        for i, f, b, m, _ in ROWS:
            await conn.execute("INSERT INTO smart_bi_distillation_samples VALUES ($1, $2, $3, $4::jsonb)",
                               i, f, b, json.dumps(m))
        await conn.execute("INSERT INTO smart_bi_distillation_samples VALUES (90, 'SEED_F', 'factory', NULL)")
        # a withdrawn factory's row with a real version stays out
        await conn.execute("INSERT INTO smart_bi_distillation_samples VALUES (91, 'F_GONE', 'factory', "
                           "'{\"training_rule\": \"2026-11-03\"}'::jsonb)")
        await conn.execute("INSERT INTO training_consent VALUES ('F_GONE', 'none', now())")
        rows = await conn.fetch(f"SELECT id FROM smart_bi_distillation_samples WHERE {tc.usable_rows_sql()} ORDER BY id")
        assert [r["id"] for r in rows] == sorted([i for i, *_rest, u in ROWS if u] + [90])
        # positive control: without the predicate every row is there (the query reads the table)
        assert await conn.fetchval("SELECT count(*) FROM smart_bi_distillation_samples") == len(ROWS) + 2
    finally:
        await conn.execute(f"DROP SCHEMA IF EXISTS {schema} CASCADE")
        await conn.close()
