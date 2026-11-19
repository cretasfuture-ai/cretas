"""prune_soft_deleted_fact_batches 的真 PostgreSQL 集成测试 (q49, 2026-11-18)。

2026-11-19 补源头硬删（本厂源表已无此 id）：硬删被清 / 活批次不动 / 他厂同 id 不动 /
源头读取失败或一条都没报回时 0 删除 / LSMDEMO 不做硬删清理 / 幂等。

为什么要真库：「活批次不删 / 他厂不动 / agg 只删失去全部事实行的键」是 SQL 语义，
假连接只能看到 SQL 文本，证明不了。这里用：
  * 真迁移文件 V20261002_01（FORCE ROW LEVEL SECURITY + tenant_isolation 策略都是真的）；
  * 非超级用户角色连接（超级用户天然绕过 RLS，会让「忘了设租户」这类错误测不出来）；
  * production_batches 用最小结构建在同一个库里模拟源库。

运行: PG_TEST_DSN="postgresql://postgres:<pw>@localhost:55432/postgres" python -m pytest smartbi/tests/test_factory_etl_prune_pg.py
没设 PG_TEST_DSN 时整文件跳过（和 tests/test_capability_calculator_integration.py 同约定）。
"""
from __future__ import annotations

import os
import sys
import uuid
from datetime import date, datetime
from pathlib import Path

import asyncpg
import pytest

_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _root not in sys.path:
    sys.path.insert(0, _root)

from smartbi.gold.factory_production_etl import (  # noqa: E402
    prune_soft_deleted_fact_batches,
    run_factory_etl,
    materialize_factory_gold,
)

DSN: str = os.environ.get("PG_TEST_DSN") or ""
pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(not DSN, reason="PG_TEST_DSN 未设置: 跳过真库集成测试"),
]

MIGRATION = Path(_root) / "smartbi" / "database" / "migrations" / "V20261002_01__factory_production_gold.sql"
ROLE = "etl_prune_user"
PW = "etl_prune_pw"


def _with_db(dsn: str, db: str, user: str | None = None, pw: str | None = None) -> str:
    from urllib.parse import urlparse, urlunparse
    u = urlparse(dsn)
    netloc = u.netloc if user is None else f"{user}:{pw}@{u.hostname}:{u.port or 5432}"
    return urlunparse(u._replace(path=f"/{db}", netloc=netloc))


@pytest.fixture
async def pool():
    db = "prune_" + uuid.uuid4().hex[:10]
    admin = await asyncpg.connect(DSN)
    try:
        await admin.execute(f'CREATE DATABASE "{db}"')
    finally:
        await admin.close()
    sup = await asyncpg.connect(_with_db(DSN, db))
    try:
        exists = await sup.fetchval("SELECT 1 FROM pg_roles WHERE rolname=$1", ROLE)
        if not exists:
            await sup.execute(f"CREATE ROLE {ROLE} LOGIN PASSWORD '{PW}' NOSUPERUSER NOBYPASSRLS")
        # 迁移里 GRANT ... TO smartbi_user：测试库里补一个同名角色
        if not await sup.fetchval("SELECT 1 FROM pg_roles WHERE rolname='smartbi_user'"):
            await sup.execute("CREATE ROLE smartbi_user NOLOGIN")
        await sup.execute(MIGRATION.read_text(encoding="utf-8"))
        # 模拟源库的 production_batches（最小结构，id 与生产一样是 BIGINT）
        await sup.execute(
            """CREATE TABLE production_batches (
                   id BIGINT NOT NULL, factory_id VARCHAR(50) NOT NULL, deleted_at TIMESTAMP,
                   PRIMARY KEY (factory_id, id))  -- 故意复合主键: 造「他厂同 id」的阴性对照"""
        )
        for col, typ in [
            ("batch_number", "varchar(50)"), ("product_type_id", "varchar(100)"), ("product_name", "varchar(100)"),
            ("planned_quantity", "numeric"), ("actual_quantity", "numeric"), ("good_quantity", "numeric"),
            ("defect_quantity", "numeric"), ("unit", "varchar(20)"), ("status", "varchar(20)"),
            ("start_time", "timestamp"), ("end_time", "timestamp"), ("worker_count", "int"),
            ("work_duration_minutes", "int"), ("material_cost", "numeric"), ("labor_cost", "numeric"),
            ("equipment_cost", "numeric"), ("other_cost", "numeric"), ("total_cost", "numeric"),
            ("unit_cost", "numeric"), ("yield_rate", "numeric"), ("is_trial", "boolean"),
        ]:
            await sup.execute(f"ALTER TABLE production_batches ADD COLUMN {col} {typ}")
        await sup.execute(
            """CREATE TABLE production_reports (id BIGINT PRIMARY KEY, factory_id varchar(50), report_type varchar(20),
               deleted_at timestamp, batch_id bigint, process_order int, process_category varchar(50), report_date date,
               report_kind varchar(20), input_quantity numeric, output_quantity numeric, good_quantity numeric,
               total_work_minutes int, total_workers int, labor_cost numeric, material_cost numeric)""")
        await sup.execute(f"GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO {ROLE}")
        await sup.execute(f"GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO {ROLE}")
    finally:
        await sup.close()
    p = await asyncpg.create_pool(_with_db(DSN, db, ROLE, PW), min_size=1, max_size=3)
    yield p
    await p.close()
    admin = await asyncpg.connect(DSN)
    try:
        await admin.execute(f'DROP DATABASE "{db}" WITH (FORCE)')
    finally:
        await admin.close()


async def _seed_fact(pool, factory, pk, ptype, d, cost=100):
    async with pool.acquire() as c:
        async with c.transaction():
            await c.execute("SELECT set_config('app.factory_id', $1, false)", factory)
            await c.execute(
                """INSERT INTO fact_production_batch (factory_id, source_pk, product_type_id, status, stat_date, total_cost)
                   VALUES ($1,$2,$3,'COMPLETED',$4,$5)""",
                factory, str(pk), ptype, d, cost,
            )


async def _seed_src(pool, factory, pk, deleted):
    async with pool.acquire() as c:
        await c.execute(
            "INSERT INTO production_batches (id, factory_id, deleted_at) VALUES ($1,$2,$3)",
            pk, factory, datetime(2026, 11, 17, 18, 53, 40) if deleted else None,
        )


async def _counts(pool, factory):
    async with pool.acquire() as c:
        async with c.transaction():
            await c.execute("SELECT set_config('app.factory_id', $1, false)", factory)
            f = await c.fetchval("SELECT count(*) FROM fact_production_batch WHERE factory_id=$1", factory)
            a = await c.fetchval("SELECT count(*) FROM agg_factory_batch_daily WHERE factory_id=$1", factory)
            keys = await c.fetch(
                "SELECT stat_date, product_type_id, batch_count FROM agg_factory_batch_daily WHERE factory_id=$1 ORDER BY 1,2 NULLS LAST",
                factory,
            )
    return f, a, [(r["stat_date"], r["product_type_id"], r["batch_count"]) for r in keys]


D1, D2 = date(2026, 10, 27), date(2026, 10, 28)


async def _world(pool):
    """F006: 2 个已软删批次(101,102 → 产品 P_DEAD) + 活批次 103(产品 P_LIVE, 与 101 同日) + 活批次 104(P_LIVE, D2)
       F007: 批次 id 与 F006 的已软删批次相同 (101) 但在 F007 是活的
       agg 由 ETL 的 Gold 物化生成。"""
    # 105: F006 里是活批次，但 F007 有个同 id 的已软删批次 —— 源头查询若漏了 factory_id 就会误删 F006 的 105
    for pk, f, deleted in [(101, "F006", True), (102, "F006", True), (103, "F006", False), (104, "F006", False),
                           (105, "F006", False), (106, "F006", True), (101, "F007", False), (105, "F007", True)]:
        await _seed_src(pool, f, pk, deleted)
    await _seed_fact(pool, "F006", 101, "P_DEAD", D1)
    await _seed_fact(pool, "F006", 102, "P_DEAD", D2)
    await _seed_fact(pool, "F006", 103, "P_LIVE", D1)
    await _seed_fact(pool, "F006", 104, "P_LIVE", D2)
    await _seed_fact(pool, "F006", 105, "P_LIVE2", D2)
    # 106: 已软删，但与活批次 103 同产品同日 —— (D1,P_LIVE) 这行 agg 不能因此被删，只能由 Gold 重算
    await _seed_fact(pool, "F006", 106, "P_LIVE", D1)
    await _seed_fact(pool, "F007", 101, "P_X", D1)
    await materialize_factory_gold(pool, "F006")
    await materialize_factory_gold(pool, "F007")


async def test_prune_removes_only_soft_deleted_batches_of_that_factory(pool):
    await _world(pool)
    before6 = await _counts(pool, "F006")
    before7 = await _counts(pool, "F007")
    # 写前: F006 fact 6 / agg = 产品行 5 (P_DEAD×2日 + P_LIVE×2日 + P_LIVE2×1日) + 日汇总 2 = 7
    assert before6[0] == 6 and before6[1] == 7
    assert before7[0] == 1

    stats = await prune_soft_deleted_fact_batches(pool, pool, "F006")

    after6 = await _counts(pool, "F006")
    after7 = await _counts(pool, "F007")
    assert stats["fact"] == 3
    assert after6[0] == 3, "活批次 103/104/105 的 fact 必须还在（105 在 F007 有同 id 已软删批次，不许连坐）"
    # P_DEAD 两天的产品行没了；P_LIVE 的产品行和两天的日汇总留着（日汇总稍后由 Gold 重算）
    assert stats["agg"] == 2
    assert [k for k in after6[2] if k[1] == "P_DEAD"] == []
    assert any(k[0] == D1 and k[1] == "P_LIVE" for k in after6[2]), "同产品同日仍有活批次的 agg 行不许删"
    assert len([k for k in after6[2] if k[1] == "P_LIVE"]) == 2
    assert len([k for k in after6[2] if k[1] == "P_LIVE2"]) == 1
    # 阴性对照: 别的工厂 id 相同的批次(101)没被碰
    assert after7 == before7


async def test_prune_is_idempotent_and_noop_when_nothing_soft_deleted(pool):
    await _world(pool)
    await prune_soft_deleted_fact_batches(pool, pool, "F006")
    snap = await _counts(pool, "F006")
    again = await prune_soft_deleted_fact_batches(pool, pool, "F006")
    assert again == {"fact": 0, "agg": 0, "fact_hard_deleted": 0, "errors": []}
    assert await _counts(pool, "F006") == snap


async def test_live_batch_in_same_day_keeps_rollup_row_and_gold_recomputes(pool):
    """D1 有已删批次 101(P_DEAD) 和活批次 103(P_LIVE)：D1 日汇总行必须留下，重算后 batch_count=1；D2 剩 104、105 两个活批次。"""
    await _world(pool)
    await prune_soft_deleted_fact_batches(pool, pool, "F006")
    await materialize_factory_gold(pool, "F006")
    _, _, keys = await _counts(pool, "F006")
    rollup = {k[0]: k[2] for k in keys if k[1] is None}
    assert rollup[D1] == 1 and rollup[D2] == 2  # D2: 活批次 104、105


async def test_rollup_row_removed_when_no_fact_left_that_day(pool):
    """某天只有已软删批次 → 清理后这天的日汇总行也不应留着。"""
    d3 = date(2026, 10, 29)
    await _seed_src(pool, "F006", 201, True)
    await _seed_fact(pool, "F006", 201, "P_DEAD", d3)
    await _seed_src(pool, "F006", 202, False)
    await _seed_fact(pool, "F006", 202, "P_LIVE", D1)
    await materialize_factory_gold(pool, "F006")
    stats = await prune_soft_deleted_fact_batches(pool, pool, "F006")
    _, _, keys = await _counts(pool, "F006")
    assert stats["fact"] == 1 and stats["agg"] == 2  # 产品行 + 日汇总行
    assert all(k[0] != d3 for k in keys)


async def test_directly_seeded_agg_rows_are_untouched(pool):
    """seed 脚本绕过 ETL 直接写 agg（演示数据）：这些行的 (日期,产品) 没有被清掉的 fact 行，不许被删。"""
    await _world(pool)
    async with pool.acquire() as c:
        async with c.transaction():
            await c.execute("SELECT set_config('app.factory_id', 'F006', false)")
            await c.execute(
                """INSERT INTO agg_factory_batch_daily (factory_id, stat_date, product_type_id, batch_count)
                   VALUES ('F006', '2026-09-01', 'P_SEEDED', 3), ('F006', '2026-09-01', NULL, 3),
                          ('F006', '2026-10-27', 'P_SEEDED', 4)""")  # 最后一行：与被清 fact 同日、不同产品
    await prune_soft_deleted_fact_batches(pool, pool, "F006")
    _, _, keys = await _counts(pool, "F006")
    assert (date(2026, 9, 1), "P_SEEDED", 3) in keys
    assert (date(2026, 9, 1), None, 3) in keys
    assert (D1, "P_SEEDED", 4) in keys, "与被清 fact 同日但不同产品的 agg 行不许连坐"


async def test_run_factory_etl_prunes_before_gold_and_reports(pool):
    """走真实入口 run_factory_etl：源表要有 ETL 读的全部列，这里补齐后一轮跑完。"""
    async with pool.acquire() as c:
        # 一个活的已完成批次 + 一个已软删的已完成批次(已被早先 ETL 同步进 fact)
        await c.execute(
            """INSERT INTO production_batches (id, factory_id, deleted_at, status, product_type_id, end_time, total_cost)
               VALUES (1,'F006',NULL,'COMPLETED','P_LIVE','2026-10-27 10:00',50),
                      (2,'F006','2026-11-17 18:53:40','COMPLETED','P_DEAD','2026-10-27 11:00',70)""")
    await _seed_fact(pool, "F006", 2, "P_DEAD", D1, 70)
    stats = await run_factory_etl(pool, pool, "F006")
    assert stats.errors == []
    assert stats.silver_pruned == 1
    f, _a, keys = await _counts(pool, "F006")
    assert f == 1  # 只剩活批次（本轮 upsert 进来的 1）
    assert [k for k in keys if k[1] == "P_DEAD"] == []


async def _fact_count_by_day(pool, factory):
    async with pool.acquire() as c:
        async with c.transaction():
            await c.execute("SELECT set_config('app.factory_id', $1, false)", factory)
            rows = await c.fetch(
                "SELECT stat_date, count(*) n FROM fact_production_batch WHERE factory_id=$1 GROUP BY 1", factory)
    return {r["stat_date"]: r["n"] for r in rows}


async def _assert_rollup_equals_fact_count(pool, factory):
    """不变量（q49 补充）：全产品日汇总 batch_count == 该日 fact 行数；没有 fact 的日子不该有汇总行。"""
    _, _, keys = await _counts(pool, factory)
    rollup = {k[0]: k[2] for k in keys if k[1] is None}
    facts = await _fact_count_by_day(pool, factory)
    assert rollup == facts, f"rollup={rollup} facts={facts}"
    assert sum(rollup.values()) == sum(facts.values())


async def test_rollup_batch_count_equals_fact_rows_after_full_cycle(pool):
    await _world(pool)
    await prune_soft_deleted_fact_batches(pool, pool, "F006")
    await materialize_factory_gold(pool, "F006")
    await _assert_rollup_equals_fact_count(pool, "F006")


async def test_stale_rollups_left_by_manual_fact_delete_are_cleaned(pool):
    """prod 的真实现状：fact 行和分产品 agg 行早被人工删了，全产品汇总行没跟着删 → 成本页显示 12/21（应 12/13）。
    D1/D3：没有任何 fact、没有产品行的孤儿汇总（应删）；D2：还有 1 个活 fact，但汇总里仍记着 9（应重算为 1）。"""
    d3 = date(2026, 10, 29)
    await _seed_src(pool, "F006", 301, False)
    await _seed_fact(pool, "F006", 301, "P_LIVE", D2)
    async with pool.acquire() as c:
        async with c.transaction():
            await c.execute("SELECT set_config('app.factory_id', 'F006', false)")
            await c.execute(
                """INSERT INTO agg_factory_batch_daily (factory_id, stat_date, product_type_id, batch_count)
                   VALUES ('F006', $1, NULL, 5), ('F006', $2, NULL, 9), ('F006', $3, NULL, 7)""", D1, D2, d3)
    stats = await prune_soft_deleted_fact_batches(pool, pool, "F006")
    await materialize_factory_gold(pool, "F006")
    assert stats["fact"] == 0 and stats["agg"] == 2  # D1、D3 两条孤儿汇总
    _, _, keys = await _counts(pool, "F006")
    rollup = {k[0]: k[2] for k in keys if k[1] is None}
    assert rollup == {D2: 1}
    await _assert_rollup_equals_fact_count(pool, "F006")


# ── 源头硬删（LSM-F006-HARDDEL-RESIDUE, 2026-11-19）──────────────────────────
# 源表里本厂已没有这个 id = 物理删除。三态：存在 / 确认不存在 / 不知道(查询失败或一条都没报回) → 跳过。

D3 = date(2026, 10, 29)


async def _hard_world(pool):
    """F006 fact: 401(P_HARD,D1,源头无) / 402(P_HARD,D2,F006 源头无但 F007 有同 id 活批次)
                  403(P_LIVE,D1,活) / 404(P_LIVE,D1,源头无: 与活批次同产品同日) / 405(P_SOFT,D3,软删)
       F007 fact: 402(P_X,D2,活) —— 他厂同 id 的阴性对照。"""
    for pk, f, deleted in [(403, "F006", False), (405, "F006", True), (402, "F007", False)]:
        await _seed_src(pool, f, pk, deleted)
    await _seed_fact(pool, "F006", 401, "P_HARD", D1)
    await _seed_fact(pool, "F006", 402, "P_HARD", D2)
    await _seed_fact(pool, "F006", 403, "P_LIVE", D1)
    await _seed_fact(pool, "F006", 404, "P_LIVE", D1)
    await _seed_fact(pool, "F006", 405, "P_SOFT", D3)
    await _seed_fact(pool, "F007", 402, "P_X", D2)
    await materialize_factory_gold(pool, "F006")
    await materialize_factory_gold(pool, "F007")


async def _fact_pks(pool, factory):
    async with pool.acquire() as c:
        async with c.transaction():
            await c.execute("SELECT set_config('app.factory_id', $1, false)", factory)
            rows = await c.fetch("SELECT source_pk FROM fact_production_batch WHERE factory_id=$1", factory)
    return sorted(r["source_pk"] for r in rows)


async def test_hard_deleted_batches_are_pruned_live_and_other_factory_untouched(pool):
    await _hard_world(pool)
    before7 = await _counts(pool, "F007")
    stats = await prune_soft_deleted_fact_batches(pool, pool, "F006")
    assert await _fact_pks(pool, "F006") == ["403"], "只剩活批次 403；401/402/404 源头已硬删、405 已软删"
    assert stats["fact"] == 4 and stats["fact_hard_deleted"] == 3 and stats["errors"] == []
    # 402 在 F007 源头有同 id 活批次：判定只看本厂源表，F006 的 402 仍按硬删清掉；F007 的 402 一行不动
    assert await _counts(pool, "F007") == before7
    await materialize_factory_gold(pool, "F006")
    _, _, keys = await _counts(pool, "F006")
    assert [k for k in keys if k[1] in ("P_HARD", "P_SOFT")] == []
    assert (D1, "P_LIVE", 1) in keys, "与硬删批次同产品同日的活批次：agg 行留下并由 Gold 重算为 1"
    await _assert_rollup_equals_fact_count(pool, "F006")


async def test_hard_delete_prune_is_idempotent(pool):
    await _hard_world(pool)
    await prune_soft_deleted_fact_batches(pool, pool, "F006")
    await materialize_factory_gold(pool, "F006")
    snap6, snap7 = await _counts(pool, "F006"), await _counts(pool, "F007")
    again = await prune_soft_deleted_fact_batches(pool, pool, "F006")
    assert again == {"fact": 0, "agg": 0, "fact_hard_deleted": 0, "errors": []}
    assert await _counts(pool, "F006") == snap6 and await _counts(pool, "F007") == snap7


async def test_source_reports_none_of_the_ids_means_unknown_and_zero_deletes(pool):
    """「不知道」：本厂源表一条都没报回来（库连错/源表被清空/看不到都长这样）→ 不能当成全部已硬删。"""
    await _seed_fact(pool, "F006", 501, "P_A", D1)
    await _seed_fact(pool, "F006", 502, "P_B", D2)
    await _seed_src(pool, "F007", 501, False)  # 他厂有同 id 也不算本厂存在
    await materialize_factory_gold(pool, "F006")
    before = await _counts(pool, "F006")
    stats = await prune_soft_deleted_fact_batches(pool, pool, "F006")
    assert stats["fact"] == 0 and stats["fact_hard_deleted"] == 0
    assert any("hard-delete check skipped" in e for e in stats["errors"]), stats
    assert await _counts(pool, "F006") == before


async def test_source_read_failure_deletes_nothing(pool):
    """「不知道」：源头查询报错 → 异常上抛，fact/agg 一行不删。"""
    await _hard_world(pool)
    before6, before7 = await _counts(pool, "F006"), await _counts(pool, "F007")
    sup = await asyncpg.connect(_with_db(DSN, await _dbname(pool)))
    try:
        await sup.execute("REVOKE SELECT ON production_batches FROM " + ROLE)
        with pytest.raises(asyncpg.PostgresError):
            await prune_soft_deleted_fact_batches(pool, pool, "F006")
    finally:
        await sup.execute("GRANT SELECT ON production_batches TO " + ROLE)
        await sup.close()
    assert await _counts(pool, "F006") == before6 and await _counts(pool, "F007") == before7


async def _dbname(pool):
    async with pool.acquire() as c:
        return await c.fetchval("SELECT current_database()")


async def test_lsmdemo_hard_delete_residue_is_left_alone(pool):
    """LSMDEMO 演示厂不做硬删清理（其残留另议）；软删清理照旧。"""
    await _seed_src(pool, "LSMDEMO", 601, False)
    await _seed_src(pool, "LSMDEMO", 603, True)
    await _seed_fact(pool, "LSMDEMO", 601, "P_LIVE", D1)
    await _seed_fact(pool, "LSMDEMO", 602, "P_HARD", D1)  # 源头无
    await _seed_fact(pool, "LSMDEMO", 603, "P_SOFT", D2)
    stats = await prune_soft_deleted_fact_batches(pool, pool, "LSMDEMO")
    assert await _fact_pks(pool, "LSMDEMO") == ["601", "602"]
    assert stats["fact"] == 1 and stats["fact_hard_deleted"] == 0 and stats["errors"] == []


async def test_run_factory_etl_prunes_hard_deleted_and_reports(pool):
    """真实入口 run_factory_etl：硬删残留被清，job stats 报 silver_pruned_hard_deleted。"""
    async with pool.acquire() as c:
        await c.execute(
            """INSERT INTO production_batches (id, factory_id, deleted_at, status, product_type_id, end_time, total_cost)
               VALUES (1,'F006',NULL,'COMPLETED','P_LIVE','2026-10-27 10:00',50)""")
    await _seed_fact(pool, "F006", 7, "P_HARD", D2, 70)  # 源头已物理删除
    stats = await run_factory_etl(pool, pool, "F006")
    assert stats.errors == []
    assert stats.silver_pruned == 1 and stats.silver_pruned_hard_deleted == 1
    assert await _fact_pks(pool, "F006") == ["1"]
    await _assert_rollup_equals_fact_count(pool, "F006")
