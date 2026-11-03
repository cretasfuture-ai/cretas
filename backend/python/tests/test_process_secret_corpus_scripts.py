"""Privacy clause 6 in the corpus scripts (review 2026-10-29; rules 2026-10-29 option A).

The LLM judge (corpus_judge), the human review sampler (corpus_g3_sample) and
the eval freeze (corpus_eval_freeze) read sample rows filtered by the training
rule + withdrawal. They must also drop rows naming one of their OWN factory's
process-secret terms; a row with no factory is dropped too (it cannot be
checked — the union of every factory's names is no longer used). A failed
lookup aborts the script. The judge applies the CURRENT list on every run: an
exclusion records the list fingerprint, a row excluded under another list is
re-judged, and rows held at capture are re-judged first.

Each test drives the script's real fetch entry point with a fake pool, so a
script that stops calling the filter goes red here.
"""
from __future__ import annotations

import importlib
import json

import pytest

from smartbi.services import process_secret_names as psn


def _lists(*names):
    return psn.lists_from_rows([{"name": n} for n in names], ())


_LISTS = {"F_A": _lists("秘制牛肉干"), "F_B": _lists("祖传卤汁鸭")}

# (id, factory_id, input_text, teacher_output)
_ROWS = [
    (1, "F_A", "秘制牛肉干本月销量多少", "增长 8%"),     # own factory's product -> out
    (2, None, "本月销量", "祖传卤汁鸭卖得最好"),          # no factory -> out
    (3, "F006", "秘制牛肉干本月销量多少", "增长 8%"),     # not F006's product -> stays
    (4, None, "普通牛肉干本月销量多少", "增长 8%"),       # no factory -> out (cannot be checked)
    (5, "SEED_R", "合成：秘制牛肉干", "合成答案"),        # synthetic sentinel, no names -> stays
    (6, "F_A", "普通牛肉干本月销量多少", "增长 8%"),      # control -> stays
]
_KEPT = [3, 5, 6]


def _db_rows(**extra):
    return [{"id": i, "factory_id": f, "input_text": q, "teacher_output": a,
             "metadata": None, "source": "chat_qa", "business_type": "factory", **extra}
            for i, f, q, a in _ROWS]


class _Conn:
    def __init__(self, by_sql, executed, fetched):
        self._by_sql = by_sql
        self._executed = executed
        self._fetched = fetched

    async def fetch(self, sql, *args):
        self._fetched.append(sql)
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
    """``by_sql`` = {substring of the SQL: rows to return}; first match wins."""

    def __init__(self, by_sql):
        self._by_sql = by_sql
        self.executed: list = []
        self.fetched: list = []

    def acquire(self):
        return _Conn(self._by_sql, self.executed, self.fetched)

    async def close(self):
        pass


def _judge_pool(rows=None, held=()):
    return _Pool({"WHERE metadata ? 'secrecy_recheck'": list(held), "smart_bi_distillation_samples": _db_rows() if rows is None else rows})


async def _names(*_a, **_k):
    return _LISTS


async def _boom(*_a, **_k):
    raise RuntimeError("cretas db unavailable")


# ---- shared helper ----

def test_helper_uses_the_rows_own_factory_and_drops_rows_without_one():
    kept = psn.drop_rows_naming_protected_products(_db_rows(), _LISTS)
    assert [r["id"] for r in kept] == _KEPT
    # nothing protected: only the factory-less rows go
    assert [r["id"] for r in psn.drop_rows_naming_protected_products(_db_rows(), {})] == [1, 3, 5, 6]
    assert psn.lists_for_row("", _LISTS) == psn.EMPTY_LISTS
    assert psn.lists_for_row(None, _LISTS) == psn.EMPTY_LISTS


# ---- corpus_judge: rows sent to the LLM judge ----

async def test_judge_fetch_drops_rows_naming_a_process_secret_product(monkeypatch):
    judge = importlib.import_module("scripts.corpus_judge")
    monkeypatch.setattr(judge, "all_factory_lists", _names)
    assert [r["id"] for r in await judge.fetch_unjudged_rows(_judge_pool(), 50)] == _KEPT
    assert [r["id"] for r in await judge.fetch_crossfamily_rows(_judge_pool(), 50)] == _KEPT


async def test_judge_flags_excluded_rows_with_the_current_list_fingerprint(monkeypatch, caplog):
    judge = importlib.import_module("scripts.corpus_judge")
    monkeypatch.setattr(judge, "all_factory_lists", _names)
    fp = psn.terms_fingerprint(_LISTS)
    for fetch in (judge.fetch_unjudged_rows, judge.fetch_crossfamily_rows):
        pool = _judge_pool()
        caplog.clear()
        with caplog.at_level("WARNING", logger="corpus_judge"):
            await fetch(pool, 50)
        # the fetch skips only rows excluded under THIS list
        sql = [s for s in pool.fetched if "quality" in s][0]
        assert f"IS DISTINCT FROM '{fp}'" in sql and "process_secret_excluded" in sql
        marks = [(s, a) for s, a in pool.executed if "jsonb_build_object('process_secret_excluded'" in s]
        assert len(marks) == 1 and sorted(marks[0][1][0]) == [1, 2, 4] and marks[0][1][1] == fp
        assert any("excluded 3 row(s)" in r.getMessage() for r in caplog.records)

    # control: nothing protected -> only the factory-less rows are excluded, nothing else
    monkeypatch.setattr(judge, "all_factory_lists", lambda *a, **k: _empty())
    pool = _judge_pool()
    assert [r["id"] for r in await judge.fetch_unjudged_rows(pool, 50)] == [1, 3, 5, 6]


async def test_judge_rejudges_rows_excluded_under_an_older_list(monkeypatch):
    """「judge 每次按当前名单重判」: a row flagged under a different list fingerprint is
    fetched again; if it passes the current list its flag is removed."""
    judge = importlib.import_module("scripts.corpus_judge")
    monkeypatch.setattr(judge, "all_factory_lists", _names)
    old = {"process_secret_excluded": True, "process_secret_list": "0000000000000000"}
    rows = [{"id": 7, "factory_id": "F_A", "input_text": "普通牛肉干本月销量", "teacher_output": "增长",
             "metadata": json.dumps(old), "source": "chat_qa"},
            {"id": 8, "factory_id": "F_A", "input_text": "秘制牛肉干本月销量", "teacher_output": "增长",
             "metadata": json.dumps(old), "source": "chat_qa"}]
    pool = _judge_pool(rows=rows)
    assert [r["id"] for r in await judge.fetch_unjudged_rows(pool, 50)] == [7]
    unmark = [a for s, a in pool.executed if "metadata - 'process_secret_excluded'" in s]
    mark = [a for s, a in pool.executed if "jsonb_build_object('process_secret_excluded'" in s]
    assert unmark == [([7],)]                       # passes now -> released
    assert [m[0] for m in mark] == [[8]]            # still names it -> re-flagged with the new list


async def test_judge_rejudges_rows_held_at_capture_first(monkeypatch):
    """Held rows are re-judged on the RAW text kept in the hold marker (review N3); a row that
    still names the list is flagged and KEPT for manual review, never deleted (Q5)."""
    judge = importlib.import_module("scripts.corpus_judge")
    monkeypatch.setattr(judge, "all_factory_lists", _names)
    raw_hold = json.dumps({"secrecy_recheck": {"raw_input": "秘制牛肉干销量", "raw_output": "x"}})
    held = [{"id": 11, "factory_id": "F_A", "input_text": "产品A销量", "teacher_output": "x", "metadata": raw_hold},
            {"id": 12, "factory_id": "F_A", "input_text": "普通牛肉干销量", "teacher_output": "x",
             "metadata": json.dumps({"secrecy_recheck": True})}]
    pool = _judge_pool(held=held)
    await judge.fetch_unjudged_rows(pool, 50)
    assert pool.fetched[0].count("secrecy_recheck") >= 1          # re-judged before the judge fetch
    deletes = [(s, a) for s, a in pool.executed if s.lstrip().startswith("DELETE")]
    # the only DELETE is the retention sweep (by age, not these rows): a judged row is never deleted
    assert all("created_at < NOW() - make_interval(days => $1)" in s and a == (7,) for s, a in deletes), deletes
    released = [a for s, a in pool.executed if s.strip().startswith("UPDATE") and "jsonb_build_object('secrecy_rejected'" not in s
                and "metadata - 'secrecy_recheck'" in s]
    rejected = [a for s, a in pool.executed if "jsonb_build_object('secrecy_rejected'" in s]
    assert released == [([12],)]
    assert [a[0] for a in rejected] == [11]                        # the desensitized text alone would have passed
    detail = json.loads(rejected[0][1])
    assert detail["reason"] == "names_factory_list"
    # second review, item 1: the rejection keeps the reason, the list version and the time — never
    # the raw text of the hold marker
    assert set(detail) == {"reason", "lists", "at"}
    assert "秘制牛肉干" not in rejected[0][1]
    # the release adds the desensitized stamp only now that the raw copy is gone
    release_sql = [s for s, a in pool.executed if a == ([12],)][0]
    assert "jsonb_typeof(metadata -> 'secrecy_recheck') = 'object'" in release_sql
    assert "jsonb_build_object('desensitized', true)" in release_sql


async def _empty():
    return {}


async def test_judge_fetch_aborts_when_the_lookup_fails(monkeypatch):
    judge = importlib.import_module("scripts.corpus_judge")
    monkeypatch.setattr(judge, "all_factory_lists", _boom)
    with pytest.raises(RuntimeError, match="cretas db unavailable"):
        await judge.fetch_unjudged_rows(_judge_pool(), 50)
    with pytest.raises(RuntimeError, match="cretas db unavailable"):
        await judge.fetch_crossfamily_rows(_judge_pool(), 50)


# ---- corpus_g3_sample: human review files ----

async def test_g3_sample_drops_rows_naming_a_process_secret_product(monkeypatch):
    g3 = importlib.import_module("scripts.corpus_g3_sample")
    monkeypatch.setattr(g3, "all_factory_lists", _names)
    pool = _Pool({"smart_bi_distillation_samples": _db_rows()})
    assert [r["id"] for r in await g3.fetch_sample_rows(pool, 10, per_bucket=True)] == _KEPT
    assert [r["id"] for r in await g3.fetch_sample_rows(pool, 10, per_bucket=False)] == _KEPT


async def test_g3_sample_aborts_when_the_lookup_fails(monkeypatch):
    g3 = importlib.import_module("scripts.corpus_g3_sample")
    monkeypatch.setattr(g3, "all_factory_lists", _boom)
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
    monkeypatch.setattr(freeze, "all_factory_lists", _names)
    summary = await freeze.run_freeze(n=30, dry_run=False)
    frozen = [args[0] for sql, args in pool.executed if "eval_frozen" in sql]
    assert frozen == [_KEPT]
    assert summary["n_new"] == len(_KEPT)


async def test_eval_freeze_aborts_when_the_lookup_fails(monkeypatch):
    freeze = importlib.import_module("scripts.corpus_eval_freeze")
    pool = _freeze_pool()
    monkeypatch.setattr(freeze, "_make_pool", _pool_for(pool))
    monkeypatch.setattr(freeze, "all_factory_lists", _boom)
    with pytest.raises(RuntimeError, match="cretas db unavailable"):
        await freeze.run_freeze(n=30, dry_run=False)
    assert pool.executed == []          # nothing frozen


# ---- lists lookup: 60 s cache, failures not cached, last good lists ----

_PRODUCT = {"factory_id": "F_A", "id": "P1", "code": "C-1", "name": "秘制牛肉干", "category": None,
            "product_category": None}


class _ListsPool:
    def __init__(self, fail=0):
        self.fetches = 0
        self._fail = fail

    def acquire(self):
        pool = self

        class _C:
            async def fetch(self, sql, *args):
                pool.fetches += 1
                if pool._fail:
                    pool._fail -= 1
                    raise RuntimeError("boom")
                if "process_secret_policies" in sql:
                    return [_PRODUCT]
                return [{"factory_id": "F_A", "name": "王五"}]

            async def __aenter__(self):
                return self

            async def __aexit__(self, *a):
                return False

        return _C()


async def test_all_factory_lists_are_cached_and_failures_are_not():
    psn.clear_cache()
    try:
        pool = _ListsPool()
        lists = await psn.all_factory_lists(pool)
        assert set(lists) == {"F_A"}
        assert lists["F_A"].terms == frozenset({"秘制牛肉干", "c-1", "p1"})
        assert lists["F_A"].directory == frozenset({"王五"})
        await psn.all_factory_lists(pool)
        assert pool.fetches == 2                     # products + directory, then served from cache
        psn.clear_cache()
        failing = _ListsPool(fail=1)
        with pytest.raises(RuntimeError):
            await psn.all_factory_lists(failing)
        assert set(await psn.all_factory_lists(failing)) == {"F_A"}   # the failure was not cached
    finally:
        psn.clear_cache()


async def test_factory_lists_fall_back_to_the_last_good_lists():
    psn.clear_cache()
    try:
        good = _ListsPool()
        lists, stale = await psn.factory_lists_or_last_good("F_A", good)
        assert stale is False and "秘制牛肉干" in lists.terms
        psn.clear_cache(forget_last_good=False)       # cache expired, last good kept
        again, stale = await psn.factory_lists_or_last_good("F_A", _ListsPool(fail=2))
        assert stale is True and again == lists
        psn.clear_cache()                              # nothing ever loaded -> the error propagates
        with pytest.raises(RuntimeError):
            await psn.factory_lists_or_last_good("F_A", _ListsPool(fail=2))
    finally:
        psn.clear_cache()
