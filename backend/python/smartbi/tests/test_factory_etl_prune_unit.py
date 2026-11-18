"""prune_soft_deleted_fact_batches 的调用层单测（无需数据库，CI 里会跑）。

SQL 语义本身（活批次不删 / 他厂不动 / agg 只删失去全部事实行的键）由
test_factory_etl_prune_pg.py 在真 PG 上证明；这里守的是「谁调它、带什么参数」：
  - 只对源头 deleted_at IS NOT NULL 的批次动手，参数里带 factory_id；
  - 删除只用源头报回的 id，不会把没报回的活批次 id 也塞进去；
  - 没有 fact 行 / 没有已软删批次时不发任何删除；
  - 编排器里清理失败只记错误，不挡后面的 Gold 重算。
"""
from __future__ import annotations

import asyncio
import os
import sys
from typing import Any, List, Tuple

_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _root not in sys.path:
    sys.path.insert(0, _root)

from smartbi.gold import factory_production_etl as etl  # noqa: E402


class _Conn:
    """按 SQL 关键字返回预设结果，并记录每次调用 (sql, args)。"""

    def __init__(self, script):
        self.script = script  # list[(substring, result)]
        self.calls: List[Tuple[str, tuple]] = []

    def _match(self, sql):
        for key, res in self.script:
            if key in sql:
                return res
        return []

    async def fetch(self, sql, *args):
        self.calls.append((sql, args))
        return self._match(sql)

    async def execute(self, sql, *args):
        self.calls.append((sql, args))
        return "DELETE 0"

    def transaction(self):
        conn = self

        class _Tx:
            async def __aenter__(self_inner):
                return conn

            async def __aexit__(self_inner, *a):
                return False

        return _Tx()


class _Pool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        conn = self.conn

        class _Ctx:
            async def __aenter__(self_inner):
                return conn

            async def __aexit__(self_inner, *a):
                return False

        return _Ctx()


def _run(coro):
    return asyncio.new_event_loop().run_until_complete(coro)


def _fact(*pks):
    return [{"source_pk": p} for p in pks]


def test_no_fact_rows_means_no_source_query_and_no_delete():
    smartbi = _Conn([("SELECT source_pk", [])])
    cretas = _Conn([])
    out = _run(etl.prune_soft_deleted_fact_batches(_Pool(cretas), _Pool(smartbi), "F006"))
    assert out == {"fact": 0, "agg": 0}
    assert cretas.calls == []
    assert not any("DELETE" in s for s, _ in smartbi.calls)


def test_nothing_soft_deleted_at_source_means_no_delete():
    smartbi = _Conn([("SELECT source_pk", _fact("1", "2"))])
    cretas = _Conn([("FROM production_batches", [])])
    out = _run(etl.prune_soft_deleted_fact_batches(_Pool(cretas), _Pool(smartbi), "F006"))
    assert out == {"fact": 0, "agg": 0}
    assert not any("DELETE" in s for s, _ in smartbi.calls)


def test_source_query_is_factory_scoped_and_only_asks_for_soft_deleted():
    smartbi = _Conn([("SELECT source_pk", _fact("1", "2", "3"))])
    cretas = _Conn([("FROM production_batches", [])])
    _run(etl.prune_soft_deleted_fact_batches(_Pool(cretas), _Pool(smartbi), "F006"))
    sql, args = cretas.calls[0]
    assert "factory_id = $1" in sql and "deleted_at IS NOT NULL" in sql
    assert args[0] == "F006" and sorted(args[1]) == ["1", "2", "3"]


def test_delete_uses_only_ids_reported_gone_and_the_same_factory():
    smartbi = _Conn([
        ("SELECT source_pk", _fact("1", "2", "3")),
        ("DELETE FROM fact_production_batch", [{"stat_date": None, "product_type_id": None}]),
    ])
    cretas = _Conn([("FROM production_batches", [{"id": "2"}])])  # 只有 2 已软删，1/3 是活的
    out = _run(etl.prune_soft_deleted_fact_batches(_Pool(cretas), _Pool(smartbi), "F006"))
    assert out["fact"] == 1
    del_sql, del_args = next((s, a) for s, a in smartbi.calls if "DELETE FROM fact_production_batch" in s)
    assert del_args[0] == "F006" and del_args[1] == ["2"], "活批次 1/3 不能进删除列表"
    assert any("set_config" in sql and args == ("F006",) for sql, args in smartbi.calls), "删之前要设租户"


def test_orchestrator_prune_failure_is_reported_but_gold_still_runs(monkeypatch):
    gold_called: List[Any] = []

    async def _silver(*a, **k):
        return 1

    async def _boom(*a, **k):
        raise RuntimeError("source down")

    async def _report(*a, **k):
        return 0

    async def _gold(*a, **k):
        gold_called.append(1)
        return {"product_daily": 1, "all_daily": 1}

    monkeypatch.setattr(etl, "sync_fact_production_batch", _silver)
    monkeypatch.setattr(etl, "prune_soft_deleted_fact_batches", _boom)
    monkeypatch.setattr(etl, "sync_fact_production_report", _report)
    monkeypatch.setattr(etl, "materialize_factory_gold", _gold)
    stats = _run(etl.run_factory_etl(None, None, "F006"))
    assert any(e.startswith("prune:") for e in stats.errors)
    assert gold_called, "清理失败不应挡住 Gold 重算"


def test_orchestrator_prune_runs_before_gold_and_counts_reported(monkeypatch):
    order: List[str] = []

    async def _silver(*a, **k):
        order.append("silver")
        return 1

    async def _prune(*a, **k):
        order.append("prune")
        return {"fact": 3, "agg": 2}

    async def _report(*a, **k):
        return 0

    async def _gold(*a, **k):
        order.append("gold")
        return {"product_daily": 1, "all_daily": 1}

    monkeypatch.setattr(etl, "sync_fact_production_batch", _silver)
    monkeypatch.setattr(etl, "prune_soft_deleted_fact_batches", _prune)
    monkeypatch.setattr(etl, "sync_fact_production_report", _report)
    monkeypatch.setattr(etl, "materialize_factory_gold", _gold)
    stats = _run(etl.run_factory_etl(None, None, "F006"))
    assert order == ["silver", "prune", "gold"]
    assert (stats.silver_pruned, stats.gold_pruned) == (3, 2)
