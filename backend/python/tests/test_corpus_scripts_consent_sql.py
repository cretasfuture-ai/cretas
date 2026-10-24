"""Corpus scripts that read / label / send distillation rows only touch synthetic
seed rows and rows that passed the 2026-10-24 training rule at capture.

corpus_judge runs weekly in prod (cretas-corpus-refresh) and sends rows to an outside
LLM judge; corpus_eval_freeze labels rows as the eval slice; corpus_g3_sample writes
rows into human review files. Rows captured before the rule (never checked for
process content, never desensitized) must stay out until a backfill re-checks them.
"""
from __future__ import annotations

import importlib

import pytest

from smartbi.services.training_consent import RULE_KEY, usable_rows_sql


def test_predicate_admits_only_seed_tenants_and_rule_checked_rows():
    sql = usable_rows_sql()
    assert "'SEED_F', 'SEED_R'" in sql
    assert f"metadata ? '{RULE_KEY}'" in sql
    assert "training_consent" not in sql, "authorization alone no longer admits an unchecked row"


@pytest.mark.parametrize("module_name, attrs", [
    ("scripts.corpus_judge", ["_FETCH_SQL", "_CROSSFAMILY_FETCH_SQL"]),
    ("scripts.corpus_eval_freeze", ["_FETCH_SQL", "_TOTAL_QUALITY_SQL"]),
    ("scripts.corpus_g3_sample", ["_SAMPLE_SQL"]),
])
def test_row_selecting_queries_carry_the_predicate(module_name, attrs):
    module = importlib.import_module(module_name)
    for attr in attrs:
        assert usable_rows_sql() in getattr(module, attr), f"{module_name}.{attr}"


def test_grouped_review_sample_gates_both_the_buckets_and_the_rows():
    from scripts import corpus_g3_sample

    assert corpus_g3_sample._SAMPLE_GROUPED_SQL.count(usable_rows_sql()) == 2
