"""Unit contracts for bounded shared detail allocation.

The cross-worker PostgreSQL test remains the proof for RLS and concurrent
allocation.  These tests keep the capacity/reuse decisions fast and
deterministic when no disposable cluster is available.
"""
from types import SimpleNamespace

import pandas as pd
import pytest

from . import detail_store, details


class _Acquire:
    def __init__(self, connection):
        self.connection = connection

    async def __aenter__(self):
        return self.connection

    async def __aexit__(self, *_):
        return False


class _Transaction:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return False


class _Connection:
    def __init__(self, *, reusable=None, active=0, used=0):
        self.reusable = reusable
        self.active = active
        self.used = used
        self.executed = []
        self.context_inserts = 0

    def transaction(self):
        return _Transaction()

    async def execute(self, sql, *args):
        self.executed.append((sql, args))
        if "INSERT INTO smart_bi_factory_chart_detail_contexts" in sql:
            self.context_inserts += 1

    async def fetchval(self, sql, *args):
        if "size_bytes FROM smart_bi_factory_chart_detail_snapshots" in sql:
            return 128
        if "count(*) FROM smart_bi_factory_chart_detail_contexts" in sql:
            return self.active
        if "COALESCE(sum(size_bytes)" in sql:
            return self.used
        if "SELECT snapshot_id FROM smart_bi_factory_chart_detail_snapshots" in sql:
            return 9
        return None

    async def fetchrow(self, sql, *args):
        if "c.context_id,c.expires_at" in sql:
            return self.reusable
        if "RETURNING snapshot_id" in sql:
            return {"snapshot_id": 9}
        return None


class _Pool:
    def __init__(self, connection):
        self.connection = connection

    def acquire(self):
        return _Acquire(self.connection)


def _candidate(candidate_id="chart-1"):
    return {
        "id": candidate_id,
        "title": "产量",
        "source": "production · test",
        "unit": "kg",
        "chart": {"chartType": "bar", "echartsOption": {"xAxis": {}}},
    }


def _dataset():
    return [{
        "frame": pd.DataFrame([{"日期": "2026-01-01", "单位": "kg", "产品": "A", "产量": 1}]),
        "source": "production · test",
    }]


def _pool_stub(pool):
    async def get_pool():
        return pool

    return get_pool


@pytest.mark.asyncio
async def test_reuses_same_owner_candidate_and_snapshot(monkeypatch):
    connection = _Connection(reusable={"context_id": "existing", "expires_at": "2030-01-01T00:00:00+00:00"})
    monkeypatch.setattr(detail_store.config, "get_pg_pool", _pool_stub(_Pool(connection)))

    result = await detail_store.register_shared(
        [_candidate()], _dataset(), SimpleNamespace(period_type="all"),
        ("F006", 1309, "factory_super_admin"), True,
    )

    assert result[0]["detailContext"]["id"] == "existing"
    assert connection.context_inserts == 0
    assert not any(" UPDATE " in sql.upper() for sql, _ in connection.executed)


@pytest.mark.asyncio
async def test_capacity_is_fail_closed_without_context(monkeypatch):
    connection = _Connection(active=detail_store.MAX_CONTEXTS)
    monkeypatch.setattr(detail_store.config, "get_pg_pool", _pool_stub(_Pool(connection)))

    result = await detail_store.register_shared(
        [_candidate()], _dataset(), SimpleNamespace(period_type="all"),
        ("F006", 1309, "factory_super_admin"), True,
    )

    assert "detailContext" not in result[0]
    assert result[0]["detailStatus"]["state"] == "capacity"
    assert connection.context_inserts == 0


@pytest.mark.asyncio
async def test_near_expiry_context_is_not_reused(monkeypatch):
    connection = _Connection(reusable=None)
    monkeypatch.setattr(detail_store.config, "get_pg_pool", _pool_stub(_Pool(connection)))

    result = await detail_store.register_shared(
        [_candidate()], _dataset(), SimpleNamespace(period_type="all"),
        ("F006", 1309, "factory_super_admin"), True,
    )

    assert result[0]["detailContext"]["id"]
    assert connection.context_inserts == 1
    assert not any(" UPDATE " in sql.upper() for sql, _ in connection.executed)


def test_hard_capacity_constants_remain_bounded():
    assert detail_store.MAX_CONTEXTS == 256
    assert detail_store.MAX_TOTAL_BYTES == 32 * 1024 * 1024


@pytest.mark.asyncio
async def test_stateless_metadata_matches_registration_fingerprint(monkeypatch):
    owner = ("F006", 1309, "factory_super_admin")
    request = SimpleNamespace(period_type="all")
    candidate = _candidate()
    metadata = detail_store.prepare_shared_metadata([candidate], _dataset(), request, owner, True)[0]
    connection = _Connection()
    monkeypatch.setattr(detail_store.config, "get_pg_pool", _pool_stub(_Pool(connection)))
    registered = await detail_store.register_shared([candidate], _dataset(), request, owner, True)

    assert metadata["detailSnapshotKey"] == registered[0]["detailSnapshotKey"]
    assert metadata["detailStatus"] == {"state": "unavailable", "message": "点击查看时加载详情"}


def test_stateless_metadata_fails_closed_without_hash_for_bad_or_oversized_source(monkeypatch):
    owner = ("F006", 1309, "factory_super_admin")
    request = SimpleNamespace(period_type="all")
    candidate = _candidate()
    mismatched = [{"frame": _dataset()[0]["frame"], "source": "production · another-source"}]
    rejected = detail_store.prepare_shared_metadata([candidate], mismatched, request, owner, True)[0]
    assert "detailSnapshotKey" not in rejected
    assert rejected["detailStatus"]["state"] == "unavailable"

    monkeypatch.setattr(details, "MAX_FRAME_BYTES", 1)
    oversized = detail_store.prepare_shared_metadata([candidate], _dataset(), request, owner, True)[0]
    assert "detailSnapshotKey" not in oversized
    assert oversized["detailStatus"]["state"] == "unavailable"
