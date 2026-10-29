from decimal import Decimal
import asyncio
import os
import uuid
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import urlparse

import pandas as pd

from . import detail_store, details, engine, purchase_inventory, trusted_business_sources
from .conversation import joint_family_requirements


def _request(include_optional):
    return SimpleNamespace(include_optional=include_optional, period_type="all", year=2026,
                           start_month=1, end_month=12, guided=False, analysis_subject=None)


def _row(**changes):
    row = {
        "日期": "2026-10-29",
        "收货状态": "CONFIRMED", "收货数量": Decimal("10"), "收货单位": "kg",
        "收货单ID": "PR6", "收货行ID": 1, "物料": "面粉", "物料（单位）": "面粉（kg）",
        "批次工厂ID": "F006", "物料ID": "MT6", "批次物料ID": "MT6", "物料批次ID": "MB6",
        "包装单位快照": "kg", "库存数量快照": Decimal("10"), "库存基础单位快照": "kg",
        "包装换算因子快照": Decimal("1"),
        "批次入库数量": Decimal("10"), "批次已用数量": Decimal("2"),
        "批次预留数量": Decimal("1"), "当前可用数量": Decimal("7"), "批次数量单位": "kg", "数量": Decimal("10"),
        "数量单位": "kg", "事实限定": "已确认收货", "批次来源类型": "PURCHASE_RECEIVE",
        "批次来源单据ID": "PR6",
    }
    row.update(changes)
    return row


def test_optional_gate_and_inventory_conservation():
    frame = pd.DataFrame([_row()])
    source = "purchase-batch-lineage · 已确认收货与物料批次双向来源"
    assert purchase_inventory.build(frame, _request(False), False, source) == ([], [])
    charts, gaps = purchase_inventory.build(frame, _request(True), False, source)
    assert not gaps and charts[0]["priority"] == "optional"
    assert charts[0]["title"] == "采购入库与批次库存（kg）"
    assert {k["label"] for k in charts[0]["kpis"]} == {"已确认入库量", "已用量", "预留量", "当前可用量"}
    assert next(k["value"] for k in charts[0]["kpis"] if k["label"] == "当前可用量") == 7.0
    assert charts[0]["engine"]["pointDrilldown"] is False
    bad, gaps = purchase_inventory.build(frame.assign(**{"批次预留数量": Decimal("9")}), _request(True), False, source)
    assert not bad and gaps
    exhausted = frame.assign(**{"批次已用数量": Decimal("9"), "批次预留数量": Decimal("1"), "当前可用数量": Decimal("0")})
    zero_chart, zero_gaps = purchase_inventory.build(exhausted, _request(True), False, source)
    assert not zero_gaps
    assert next(k["value"] for k in zero_chart[0]["kpis"] if k["label"] == "当前可用量") == 0.0
    assert next(s for s in zero_chart[0]["chart"]["echartsOption"]["series"]
                if s["name"] == "已确认入库")["data"] == [10.0]
    mixed = pd.DataFrame([
        _row(),
        _row(**{"收货单ID": "PR7", "收货行ID": 2, "物料批次ID": "MB7", "物料（单位）": "面粉（箱）",
                 "收货数量": Decimal("3"), "收货单位": "箱", "批次数量单位": "箱", "包装单位快照": "箱",
                 "库存基础单位快照": "箱", "库存数量快照": Decimal("3"), "数量单位": "箱", "数量": Decimal("3"),
                 "批次入库数量": Decimal("3"), "批次已用数量": Decimal("1"),
                 "批次预留数量": Decimal("1"), "当前可用数量": Decimal("1")}),
    ])
    mixed_source = "purchase-batch-lineage · 已确认收货与物料批次双向来源"
    mixed_candidates, mixed_gaps = engine.build([engine.Dataset(mixed, mixed_source)], _request(True), False)
    mixed_candidates = [c for c in mixed_candidates if c.get("analysis", {}).get("familyId") == "purchase-batch-lineage.purchase.inventory"]
    assert not mixed_gaps and {c["unit"] for c in mixed_candidates} == {"kg", "箱"}
    local_registered = details.register(mixed_candidates, [engine.Dataset(mixed, mixed_source)], _request(True),
                                        ("F006", 9982, "factory_super_admin"), False)
    for item in local_registered:
        page = details.page({"context_id": item["detailContext"]["id"], "page": 1, "page_size": 20},
                            ("F006", 9982, "factory_super_admin"), False, True)
        assert {str(row.get("数量单位")) for row in page["data"]["rows"]} == {item["unit"]}
    duplicate_frame = pd.DataFrame([_row(), _row(**{"收货行ID": 2, "收货单ID": "PR7"})])
    duplicate_chart, duplicate_gap = purchase_inventory.build(duplicate_frame, _request(True), False, mixed_source)
    assert not duplicate_chart and duplicate_gap


def test_engine_full_build_and_planner_use_exact_purchase_inventory_family():
    source = "purchase-batch-lineage · 已确认收货与物料批次双向来源"
    dataset = engine.Dataset(pd.DataFrame([_row()]), source)
    charts, gaps = engine.build([dataset], _request(False), False)
    assert charts and not any("finance" in str(g.get("id")) for g in gaps)
    charts, gaps = engine.build([dataset], _request(True), False)
    assert any(item["analysis"]["familyId"].startswith("purchase-batch-lineage") for item in charts)
    assert joint_family_requirements("联合看采购、收货、库存/采购入库和库存一起分析") == {
        "purchase-batch-lineage.purchase.inventory"
    }
    assert "purchase-batch-lineage.purchase.inventory" not in joint_family_requirements("联合看成品入库与库存")
    assert "purchase-batch-lineage.purchase.inventory" not in joint_family_requirements("联合看客户来料入库和库存")
    assert "purchase-batch-lineage.purchase.inventory" not in joint_family_requirements("analyze finished goods inbound and inventory")
    assert "purchase-batch-lineage.purchase.inventory" not in joint_family_requirements("analyze customer supplied inbound and inventory")


def test_real_pg_purchase_lineage_and_inventory_matrix():
    dsn = os.getenv("DETAIL_STORE_TEST_PG_URL") or os.getenv("COMMERCE_JOINT_TEST_PG_URL")
    if not dsn:
        import pytest
        pytest.skip("owned disposable PostgreSQL DSN required")
    target = urlparse(dsn)
    assert target.hostname in {"127.0.0.1", "localhost"} and target.port == 55439
    asyncpg = __import__("asyncpg")

    async def run():
        schema = "purchase_inventory_" + uuid.uuid4().hex[:12]
        conn = await asyncpg.connect(dsn)
        try:
            await conn.execute(f"CREATE SCHEMA {schema}; SET search_path TO {schema},public")
            await conn.execute("""
                CREATE TABLE purchase_receive_records (id text PRIMARY KEY, factory_id text, receive_date date, status text, deleted_at timestamp);
                CREATE TABLE purchase_receive_items (id bigint PRIMARY KEY, receive_record_id text, material_type_id text, material_name text,
                    material_batch_id text, received_quantity numeric, unit text, receive_package_unit_snapshot text,
                    inventory_base_unit_snapshot text, package_to_base_factor_snapshot numeric, inventory_quantity_snapshot numeric, deleted_at timestamp);
                CREATE TABLE material_batches (id text PRIMARY KEY, factory_id text, material_type_id text, source_doc_type text,
                    source_doc_id text, quantity_unit text, receipt_quantity numeric, used_quantity numeric, reserved_quantity numeric, deleted_at timestamp);
                CREATE TABLE raw_material_types (id text PRIMARY KEY, factory_id text, deleted_at timestamp);
            """)
            await conn.execute("""
                INSERT INTO raw_material_types VALUES ('mt6','F006',NULL),('mt7','F007',NULL);
                INSERT INTO purchase_receive_records VALUES ('pr6','F006','2026-10-29','CONFIRMED',NULL),('pr7','F007','2026-10-29','CONFIRMED',NULL),('draft6','F006','2026-10-29','DRAFT',NULL);
                INSERT INTO material_batches VALUES
                  ('mb6','F006','mt6','PURCHASE_RECEIVE','pr6','kg',10,2,1,NULL),
                  ('mb7','F007','mt7','PURCHASE_RECEIVE','pr7','kg',10,2,1,NULL),
                  ('mbdraft','F006','mt6','PURCHASE_RECEIVE','draft6','kg',10,0,0,NULL),
                  ('mbbad','F006','mt6','OTHER','pr6','kg',10,0,0,NULL);
                INSERT INTO purchase_receive_items VALUES
                  (601,'pr6','mt6','面粉','mb6',10,'kg','kg','kg',1,10,NULL),
                  (701,'pr7','mt7','面粉','mb7',10,'kg','kg','kg',1,10,NULL),
                  (602,'draft6','mt6','面粉','mbdraft',10,'kg','kg','kg',1,10,NULL),
                  (603,'pr6','mt6','面粉','mbbad',10,'kg','kg','kg',1,10,NULL);
            """)
            await conn.execute("SELECT set_config('app.factory_id',$1,true)", "F006")
            rows = [dict(row) for row in await conn.fetch(trusted_business_sources.PURCHASE_BATCH_LINEAGE_SQL, "F006", None, None)]
            assert [row["收货行ID"] for row in rows] == [602, 601, 603]
            qualified, gaps = trusted_business_sources.qualify_purchase_batch_rows(rows, "F006")
            assert [row["收货行ID"] for row in qualified] == [601]
            frame = pd.DataFrame(qualified)
            source_name = "purchase-batch-lineage · 已确认收货与物料批次双向来源"
            source_dataset = engine.Dataset(frame, source_name)
            charts, build_gaps = engine.build([source_dataset], _request(True), False)
            purchase_chart = next(c for c in charts
                                  if c.get("analysis", {}).get("familyId") == "purchase-batch-lineage.purchase.inventory")
            assert not build_gaps and next(k["value"] for k in purchase_chart["kpis"]
                                          if k["label"] == "当前可用量") == 7.0

            async def qualified_ids():
                current = [dict(row) for row in await conn.fetch(
                    trusted_business_sources.PURCHASE_BATCH_LINEAGE_SQL, "F006", None, None)]
                clean, _ = trusted_business_sources.qualify_purchase_batch_rows(current, "F006")
                return [row["收货行ID"] for row in clean], clean

            await conn.execute("UPDATE purchase_receive_items SET deleted_at=CURRENT_TIMESTAMP WHERE id=601")
            ids, _ = await qualified_ids()
            assert ids == []
            await conn.execute("UPDATE purchase_receive_items SET deleted_at=NULL WHERE id=601")

            await conn.execute("UPDATE purchase_receive_items SET unit='袋' WHERE id=601")
            ids, _ = await qualified_ids()
            assert ids == []
            await conn.execute("UPDATE purchase_receive_items SET unit='kg' WHERE id=601")

            await conn.execute("UPDATE purchase_receive_items SET inventory_base_unit_snapshot='袋' WHERE id=601")
            ids, _ = await qualified_ids()
            assert ids == []
            await conn.execute("UPDATE purchase_receive_items SET inventory_base_unit_snapshot='kg' WHERE id=601")

            await conn.execute("UPDATE material_batches SET deleted_at=CURRENT_TIMESTAMP WHERE id='mb6'")
            ids, _ = await qualified_ids()
            assert ids == []
            await conn.execute("UPDATE material_batches SET deleted_at=NULL WHERE id='mb6'")

            duplicate_rows = [dict(row) for row in await conn.fetch(
                trusted_business_sources.PURCHASE_BATCH_LINEAGE_SQL, "F006", None, None)]
            duplicate = dict(next(row for row in duplicate_rows if row["收货行ID"] == 601))
            duplicate["收货行ID"] = 604
            duplicate_rows.append(duplicate)
            duplicate_clean, duplicate_gaps = trusted_business_sources.qualify_purchase_batch_rows(
                duplicate_rows, "F006")
            assert duplicate_clean == [] and any("物料批次被多个收货行" in item for item in duplicate_gaps[0]["missing"])

            await conn.execute("UPDATE material_batches SET used_quantity=11 WHERE id='mb6'")
            ids, clean = await qualified_ids()
            assert ids == [601]
            overused, overused_gaps = purchase_inventory.build(
                pd.DataFrame(clean), _request(True), False,
                "purchase-batch-lineage · 已确认收货与物料批次双向来源")
            assert not overused and overused_gaps
            overused_dataset = engine.Dataset(pd.DataFrame(clean), source_name)
            required_only, required_gaps = engine.build([overused_dataset], _request(False), False)
            assert required_only and not any("finance" in str(g.get("id")) for g in required_gaps)
            await conn.execute("UPDATE material_batches SET used_quantity=2 WHERE id='mb6'")

            await conn.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='smartbi_user') THEN CREATE ROLE smartbi_user NOLOGIN; END IF; END $$")
            migration_path = Path(__file__).resolve().parents[3] / "smartbi" / "database" / "migrations" / "V20261101_18__factory_chart_detail_contexts.sql"
            await conn.execute(migration_path.read_text(encoding="utf-8"))
            await conn.execute(f"GRANT USAGE ON SCHEMA {schema} TO smartbi_user")
            async def setup(pool_conn):
                await pool_conn.execute("SET ROLE smartbi_user")
                await pool_conn.execute(f"SET search_path TO {schema},public")
            pool = await asyncpg.create_pool(dsn, min_size=1, max_size=1,
                                              server_settings={"search_path": f"{schema},public"}, setup=setup)
            old_pool = detail_store.config.get_pg_pool
            detail_store.config.get_pg_pool = lambda: pool
            try:
                source = source_dataset
                registered = await detail_store.register_shared([purchase_chart], [source], _request(True),
                                                                 ("F006", 9982, "factory_super_admin"), False)
                page = await detail_store.page_shared(
                    {"context_id": registered[0]["detailContext"]["id"], "page": 1, "page_size": 20},
                    ("F006", 9982, "factory_super_admin"), False, True)
                assert any(str(item.get("收货行ID")) == "601" for item in page["data"]["rows"])
                assert any(str(item.get("物料批次ID")) == "mb6" for item in page["data"]["rows"])
                assert any(str(item.get("批次入库数量")) == "10" and
                           str(item.get("批次已用数量")) == "2" and
                           str(item.get("批次预留数量")) == "1" and
                           str(item.get("当前可用数量")) == "7" for item in page["data"]["rows"])
                assert not any("金额" in key or "价格" in key or "成本" in key
                                for item in page["data"]["rows"] for key in item)
                assert registered[0]["detailContext"]["drilldown"] is False
            finally:
                detail_store.config.get_pg_pool = old_pool
                await pool.close()
            await conn.execute("UPDATE material_batches SET reserved_quantity=9 WHERE id='mb6'")
            bad_rows = [dict(row) for row in await conn.fetch(trusted_business_sources.PURCHASE_BATCH_LINEAGE_SQL, "F006", None, None)]
            qualified, _ = trusted_business_sources.qualify_purchase_batch_rows(bad_rows, "F006")
            bad_frame = pd.DataFrame(qualified)
            no_chart, no_gap = purchase_inventory.build(bad_frame, _request(True), False,
                "purchase-batch-lineage · 已确认收货与物料批次双向来源")
            assert not no_chart and no_gap
        finally:
            await conn.execute(f"DROP SCHEMA IF EXISTS {schema} CASCADE")
            await conn.close()

    asyncio.run(run())

