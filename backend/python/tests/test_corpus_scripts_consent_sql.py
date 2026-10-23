"""Corpus scripts that read / label / send distillation rows only touch rows the
consent gate allows (synthetic seed tenants or factories with training consent).

corpus_judge runs weekly in prod (cretas-corpus-refresh) and sends rows to an outside
LLM judge; corpus_eval_freeze labels rows as the eval slice; corpus_g3_sample writes
rows into human review files. Every row-selecting query must carry the predicate.
"""
from __future__ import annotations

import pytest

from smartbi.services.training_consent import consented_rows_sql


def test_predicate_names_only_seed_tenants_and_consenting_scopes():
    sql = consented_rows_sql()
    assert "'SEED_F', 'SEED_R'" in sql
    assert "training_consent" in sql
    assert "'private', 'shared_anon'" in sql
    assert "'none'" not in sql


@pytest.mark.parametrize("module_name, attrs", [
    ("scripts.corpus_judge", ["_FETCH_SQL", "_CROSSFAMILY_FETCH_SQL"]),
    ("scripts.corpus_eval_freeze", ["_FETCH_SQL", "_TOTAL_QUALITY_SQL"]),
    ("scripts.corpus_g3_sample", ["_SAMPLE_SQL"]),
])
def test_row_selecting_queries_carry_the_consent_predicate(module_name, attrs):
    import importlib

    module = importlib.import_module(module_name)
    for attr in attrs:
        assert consented_rows_sql() in getattr(module, attr), f"{module_name}.{attr}"


def test_grouped_review_sample_gates_both_the_buckets_and_the_rows():
    from scripts import corpus_g3_sample

    assert corpus_g3_sample._SAMPLE_GROUPED_SQL.count(consented_rows_sql()) == 2
