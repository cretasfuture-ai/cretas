"""Privacy clause 6 in the corpus scripts (review 2026-10-29).

The LLM judge (corpus_judge), the human review sampler (corpus_g3_sample) and
the eval freeze (corpus_eval_freeze) read sample rows filtered only by the
training-rule tag + withdrawal. They must also drop rows naming a
process-secret product: the row's own factory's names, or ANY factory's names
for a row with no factory. A failed name lookup aborts the script.

Each test drives the script's real fetch entry point with a fake pool, so a
script that stops calling the filter goes red here.
"""
from __future__ import annotations

import importlib

import pytest

from smartbi.services import process_secret_names as psn

_PROTECTED = {"F_A": frozenset({"秘制牛肉干"}), "F_B": frozenset({"祖传卤汁鸭"})}

# (id, factory_id, input_text, teacher_output)
_ROWS = [
    (1, "F_A", "秘制牛肉干本月销量多少", "增长 8%"),     # own factory's product -> out
    (2, None, "本月销量", "祖传卤汁鸭卖得最好"),          # no factory, F_B's product -> out
    (3, "F006", "秘制牛肉干本月销量多少", "增长 8%"),     # not F006's product -> stays
    (4, None, "普通牛肉干本月销量多少", "增长 8%"),       # control -> stays
    (5, "SEED_R", "合成：秘制牛肉干", "合成答案"),        # synthetic sentinel, no names -> stays
]
_KEPT = [3, 4, 5]


def _db_rows(**extra):
    return [{"id": i, "factory_id": f, "input_text": q, "teacher_output": a,
             "metadata": None, "source": "chat_qa", "business_type": "factory", **extra}
            for i, f, q, a in _ROWS]


class _Conn:
    def __init__(self, by_sql, executed):
        self._by_sql = by_sql
        self._executed = executed

    async def fetch(self, sql, *args):
        for key, rows in self._by_sql.items():
            if key in sql:
                return rows
        raise AssertionError(f"unexpected SQL: {sql[:80]}")

    async def execute(self, sql, *args):
        self._executed.append((sql, args))

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


class _Pool:
    """``by_sql`` = {substring of the SQL: rows to return}."""

    def __init__(self, by_sql):
        self._by_sql = by_sql
        self.executed: list = []

    def acquire(self):
        return _Conn(self._by_sql, self.executed)

    async def close(self):
        pass


async def _names(*_a, **_k):
    return _PROTECTED


async def _boom(*_a, **_k):
    raise RuntimeError("cretas db unavailable")


# ---- shared helper ----

def test_helper_uses_own_factory_names_or_any_factorys_for_no_factory():
    kept = psn.drop_rows_naming_protected_products(_db_rows(), _PROTECTED)
    assert [r["id"] for r in kept] == _KEPT
    assert psn.drop_rows_naming_protected_products(_db_rows(), {}) == _db_rows()   # nothing protected
    assert psn.names_for_row("", _PROTECTED) == frozenset({"秘制牛肉干", "祖传卤汁鸭"})


# ---- corpus_judge: rows sent to the LLM judge ----

async def test_judge_fetch_drops_rows_naming_a_process_secret_product(monkeypatch):
    judge = importlib.import_module("scripts.corpus_judge")
    monkeypatch.setattr(judge, "all_protected_product_names", _names)
    pool = _Pool({"smart_bi_distillation_samples": _db_rows()})
    assert [r["id"] for r in await judge.fetch_unjudged_rows(pool, 50)] == _KEPT
    assert [r["id"] for r in await judge.fetch_crossfamily_rows(pool, 50)] == _KEPT


async def test_judge_fetch_aborts_when_the_name_lookup_fails(monkeypatch):
    judge = importlib.import_module("scripts.corpus_judge")
    monkeypatch.setattr(judge, "all_protected_product_names", _boom)
    pool = _Pool({"smart_bi_distillation_samples": _db_rows()})
    with pytest.raises(RuntimeError, match="cretas db unavailable"):
        await judge.fetch_unjudged_rows(pool, 50)
    with pytest.raises(RuntimeError, match="cretas db unavailable"):
        await judge.fetch_crossfamily_rows(pool, 50)


# ---- corpus_g3_sample: human review files ----

async def test_g3_sample_drops_rows_naming_a_process_secret_product(monkeypatch):
    g3 = importlib.import_module("scripts.corpus_g3_sample")
    monkeypatch.setattr(g3, "all_protected_product_names", _names)
    pool = _Pool({"smart_bi_distillation_samples": _db_rows()})
    assert [r["id"] for r in await g3.fetch_sample_rows(pool, 10, per_bucket=True)] == _KEPT
    assert [r["id"] for r in await g3.fetch_sample_rows(pool, 10, per_bucket=False)] == _KEPT


async def test_g3_sample_aborts_when_the_name_lookup_fails(monkeypatch):
    g3 = importlib.import_module("scripts.corpus_g3_sample")
    monkeypatch.setattr(g3, "all_protected_product_names", _boom)
    pool = _Pool({"smart_bi_distillation_samples": _db_rows()})
    with pytest.raises(RuntimeError, match="cretas db unavailable"):
        await g3.fetch_sample_rows(pool, 10)


# ---- corpus_eval_freeze: eval slice ----

def _freeze_pool():
    # first matching key wins: candidates / already-frozen counts / bucket totals
    return _Pool({
        "NOT (metadata ? 'eval_frozen')": _db_rows(),
        "WHERE metadata ? 'eval_frozen'": [],
        "COUNT(*) AS cnt": [{"source": "chat_qa", "business_type": "factory", "cnt": 100}],
    })


def _pool_for(pool):
    async def make(_dsn):
        return pool
    return make


async def test_eval_freeze_never_freezes_rows_naming_a_process_secret_product(monkeypatch):
    freeze = importlib.import_module("scripts.corpus_eval_freeze")
    pool = _freeze_pool()
    monkeypatch.setattr(freeze, "_make_pool", _pool_for(pool))
    monkeypatch.setattr(freeze, "all_protected_product_names", _names)
    summary = await freeze.run_freeze(n=30, dry_run=False)
    frozen = [args[0] for sql, args in pool.executed if "eval_frozen" in sql]
    assert frozen == [_KEPT]
    assert summary["n_new"] == len(_KEPT)


async def test_eval_freeze_aborts_when_the_name_lookup_fails(monkeypatch):
    freeze = importlib.import_module("scripts.corpus_eval_freeze")
    pool = _freeze_pool()
    monkeypatch.setattr(freeze, "_make_pool", _pool_for(pool))
    monkeypatch.setattr(freeze, "all_protected_product_names", _boom)
    with pytest.raises(RuntimeError, match="cretas db unavailable"):
        await freeze.run_freeze(n=30, dry_run=False)
    assert pool.executed == []          # nothing frozen


# ---- all_protected_product_names: 60 s cache, failures not cached ----

class _NamesPool:
    def __init__(self, fail_first=False):
        self.fetches = 0
        self._fail = fail_first

    def acquire(self):
        pool = self

        class _C:
            async def fetch(self, sql, *args):
                pool.fetches += 1
                if pool._fail:
                    pool._fail = False
                    raise RuntimeError("boom")
                return [{"factory_id": "F_A", "name": "秘制牛肉干"}]

            async def __aenter__(self):
                return self

            async def __aexit__(self, *a):
                return False

        return _C()


async def test_all_protected_names_are_cached_and_failures_are_not():
    psn.clear_cache()
    try:
        pool = _NamesPool()
        assert await psn.all_protected_product_names(pool) == {"F_A": frozenset({"秘制牛肉干"})}
        await psn.all_protected_product_names(pool)
        assert pool.fetches == 1                     # second call served from cache
        psn.clear_cache()
        await psn.all_protected_product_names(pool)
        assert pool.fetches == 2                     # clear_cache drops it

        psn.clear_cache()
        failing = _NamesPool(fail_first=True)
        with pytest.raises(RuntimeError):
            await psn.all_protected_product_names(failing)
        assert await psn.all_protected_product_names(failing) == {"F_A": frozenset({"秘制牛肉干"})}
        assert failing.fetches == 2                  # the failure was not cached
    finally:
        psn.clear_cache()
