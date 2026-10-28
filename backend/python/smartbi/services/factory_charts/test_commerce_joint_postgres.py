"""Opt-in real PostgreSQL/RLS proof for commerce relationship SQL.

These tests execute the frozen source SQL against a disposable schema as a
non-owner role. They do not replace migrated-schema evidence or use fake
connections.
"""
import os
import uuid
from types import SimpleNamespace
from urllib.parse import urlparse

import pytest

from . import commerce_joint, trusted_business_sources


def _request():
    return SimpleNamespace(period_type="all", year=2026, start_month=1, end_month=12)


def _dsn():
    value = os.getenv("COMMERCE_JOINT_TEST_PG_URL")
    if not value:
        pytest.skip("owned disposable PostgreSQL fixture is required")
    target = urlparse(value)
    assert target.hostname in {"127.0.0.1", "localhost"} and target.port == 55439
    return value, pytest.importorskip("asyncpg")


DDL = """
CREATE TABLE canvas_dynamic_field (
    id bigserial PRIMARY KEY, factory_id text NOT NULL, module_code text NOT NULL,
    status text NOT NULL, field_code text NOT NULL, field_type text NOT NULL,
    column_name text NOT NULL, config jsonb NOT NULL
);
CREATE TABLE customers (
    id text PRIMARY KEY, factory_id text NOT NULL, name text NOT NULL,
    source text, deleted_at timestamptz
);
CREATE TABLE product_types (
    id text PRIMARY KEY, factory_id text NOT NULL, name text,
    deleted_at timestamptz
);
CREATE TABLE sales_orders (
    id text PRIMARY KEY, factory_id text NOT NULL, order_date date NOT NULL,
    customer_id text NOT NULL, total_amount numeric, status text NOT NULL,
    deleted_at timestamptz, cf_smartbi_sales_channel text
);
CREATE TABLE sales_order_items (
    id bigint PRIMARY KEY, sales_order_id text NOT NULL, product_type_id text NOT NULL,
    product_name text, unit text NOT NULL, quantity numeric NOT NULL,
    unit_price numeric, discount_rate numeric, deleted_at timestamptz
);
CREATE TABLE sales_delivery_records (
    id text PRIMARY KEY, factory_id text NOT NULL, sales_order_id text NOT NULL,
    customer_id text, delivery_date date NOT NULL, status text NOT NULL,
    deleted_at timestamptz, record_role text
);
CREATE TABLE finished_goods_batches (
    id text PRIMARY KEY, factory_id text NOT NULL, batch_number text,
    product_type_id text NOT NULL, product_name text, unit text NOT NULL,
    produced_quantity numeric NOT NULL, shipped_quantity numeric,
    reserved_quantity numeric, status text NOT NULL, ownership text,
    owner_customer_id text, source_sales_order_id text,
    source_sales_order_item_id text, deleted_at timestamptz
);
CREATE TABLE sales_delivery_items (
    id bigint PRIMARY KEY, delivery_record_id text NOT NULL,
    sales_order_item_id bigint, finished_goods_batch_id text,
    product_type_id text NOT NULL, delivered_quantity numeric NOT NULL,
    unit text NOT NULL, deleted_at timestamptz
);
CREATE TABLE sales_delivery_item_batch_allocations (
    id text PRIMARY KEY, factory_id text NOT NULL, delivery_item_id text NOT NULL,
    finished_goods_batch_id text NOT NULL, allocated_qty numeric NOT NULL,
    unit text, deleted_at timestamptz
);
"""

RLS_TABLES = [
    "canvas_dynamic_field", "customers", "product_types", "sales_orders",
    "sales_delivery_records", "finished_goods_batches",
    "sales_delivery_item_batch_allocations",
]

MONEYLESS_GRANTS = {
    "canvas_dynamic_field": ["factory_id", "module_code", "status", "field_code", "field_type", "column_name", "config"],
    "customers": ["id", "factory_id", "name", "source", "deleted_at"],
    "product_types": ["id", "factory_id", "name", "deleted_at"],
    "sales_orders": ["id", "factory_id", "order_date", "customer_id", "status", "deleted_at", "cf_smartbi_sales_channel"],
    "sales_order_items": ["id", "sales_order_id", "product_type_id", "product_name", "unit", "quantity", "deleted_at"],
    "sales_delivery_records": ["id", "factory_id", "sales_order_id", "customer_id", "delivery_date", "status", "deleted_at", "record_role"],
    "finished_goods_batches": ["id", "factory_id", "batch_number", "product_type_id", "product_name", "unit", "produced_quantity", "shipped_quantity", "reserved_quantity", "status", "ownership", "owner_customer_id", "source_sales_order_id", "source_sales_order_item_id", "deleted_at"],
    "sales_delivery_items": ["id", "delivery_record_id", "sales_order_item_id", "finished_goods_batch_id", "product_type_id", "delivered_quantity", "unit", "deleted_at"],
    "sales_delivery_item_batch_allocations": ["id", "factory_id", "delivery_item_id", "finished_goods_batch_id", "allocated_qty", "unit", "deleted_at"],
}


async def _fixture(dsn, *, moneyless=False):
    asyncpg = pytest.importorskip("asyncpg")
    schema = "commerce_joint_" + uuid.uuid4().hex[:12]
    role = "commerce_r" + uuid.uuid4().hex[:12]
    admin = await asyncpg.connect(dsn)
    try:
        await admin.execute(f"CREATE ROLE {role} NOLOGIN")
        await admin.execute(f"CREATE SCHEMA {schema}")
        await admin.execute(f"SET search_path TO {schema},public")
        await admin.execute(DDL)
        for table in RLS_TABLES:
            await admin.execute(f"ALTER TABLE {table} ENABLE ROW LEVEL SECURITY")
            await admin.execute(
                f"CREATE POLICY tenant_read ON {table} USING (factory_id = current_setting('app.factory_id', true))"
            )
        await admin.execute(f"GRANT USAGE ON SCHEMA {schema} TO {role}")
        await admin.execute(f"GRANT SELECT ON ALL TABLES IN SCHEMA {schema} TO {role}")
        if moneyless:
            await admin.execute(f"REVOKE ALL ON ALL TABLES IN SCHEMA {schema} FROM {role}")
            for table, columns in MONEYLESS_GRANTS.items():
                await admin.execute(f"GRANT SELECT ({', '.join(columns)}) ON {table} TO {role}")
        await admin.execute("""
            INSERT INTO canvas_dynamic_field(factory_id,module_code,status,field_code,field_type,column_name,config)
            VALUES ('F006','sales_order','ACTIVE','smartbi_sales_channel','TEXT','cf_smartbi_sales_channel','{"smartbiSemantic":"sales_channel.v1"}'),
                   ('F007','sales_order','ACTIVE','smartbi_sales_channel','TEXT','cf_smartbi_sales_channel','{"smartbiSemantic":"sales_channel.v1"}');
            INSERT INTO customers VALUES
                ('c6','F006','客户6','WEB',NULL), ('c7','F007','客户7','WEB',NULL);
            INSERT INTO product_types VALUES ('p1','F006','SKU1',NULL), ('p1-7','F007','SKU1',NULL);
            INSERT INTO sales_orders VALUES
                ('o6','F006','2026-10-01','c6',10,'SHIPPED',NULL,'直营'),
                ('o7','F007','2026-10-01','c7',10,'SHIPPED',NULL,'直营');
            INSERT INTO sales_order_items VALUES
                (601,'o6','p1','SKU1','kg',1,10,0,NULL),
                (701,'o7','p1-7','SKU1','kg',1,10,0,NULL);
            INSERT INTO sales_delivery_records VALUES
                ('d6','F006','o6','c6','2026-10-02','SHIPPED',NULL,'LEGACY'),
                ('d7','F007','o7','c7','2026-10-02','SHIPPED',NULL,'LEGACY');
            INSERT INTO finished_goods_batches VALUES
                ('b6','F006','B6','p1','SKU1','kg',10,0,0,'AVAILABLE','OWNED','c6',NULL,NULL,NULL),
                ('b7','F007','B7','p1-7','SKU1','kg',10,0,0,'AVAILABLE','OWNED','c7',NULL,NULL,NULL);
            INSERT INTO sales_delivery_items VALUES
                (606,'d6',601,'b6','p1',1,'kg',NULL),
                (707,'d7',701,'b7','p1-7',1,'kg',NULL);
        """)
        worker = await asyncpg.connect(dsn)
        await worker.execute(f"SET ROLE {role}")
        await worker.execute(f"SET search_path TO {schema},public")
        return SimpleNamespace(admin=admin, worker=worker, schema=schema, role=role)
    except Exception:
        await admin.execute(f"DROP SCHEMA IF EXISTS {schema} CASCADE")
        await admin.execute(f"DROP ROLE IF EXISTS {role}")
        await admin.close()
        raise


async def _close(fixture):
    await fixture.worker.close()
    await fixture.admin.execute(f"DROP SCHEMA IF EXISTS {fixture.schema} CASCADE")
    await fixture.admin.execute(f"DROP ROLE IF EXISTS {fixture.role}")
    await fixture.admin.close()


async def _admin(fixture, sql, *args):
    await fixture.admin.execute(f"SET search_path TO {fixture.schema},public")
    return await fixture.admin.execute(sql, *args)


async def _worker_fetch(fixture, sql, *args, factory_id="F006"):
    async with fixture.worker.transaction():
        await fixture.worker.execute("SELECT set_config('app.factory_id',$1,true)", factory_id)
        return [dict(row) for row in await fixture.worker.fetch(sql, *args)]


async def _facts(fixture, factory_id="F006", *, include_priced=True, include_channel=True):
    async with fixture.worker.transaction():
        await fixture.worker.execute("SELECT set_config('app.factory_id',$1,true)", factory_id)
        base_sales = []
        base_sales_no_price = [dict(row) for row in await fixture.worker.fetch(
            trusted_business_sources.COMMERCE_BASE_SALES_NO_PRICE_SQL, factory_id, None, None)]
        if include_priced:
            base_sales = [dict(row) for row in await fixture.worker.fetch(
                trusted_business_sources.COMMERCE_BASE_SALES_SQL, factory_id, None, None)]
        sales = []
        sales_no_price = []
        source_sales = []
        if include_channel and include_priced:
            sales = [dict(row) for row in await fixture.worker.fetch(
                trusted_business_sources.COMMERCE_JOINT_SALES_SQL, factory_id, None, None)]
        if include_channel:
            sales_no_price = [dict(row) for row in await fixture.worker.fetch(
                trusted_business_sources.COMMERCE_JOINT_SALES_NO_PRICE_SQL, factory_id, None, None)]
        if include_priced:
            source_sales = [dict(row) for row in await fixture.worker.fetch(
                trusted_business_sources.CUSTOMER_SOURCE_SALES_SQL, factory_id, None, None)]
        source_sales_no_price = [dict(row) for row in await fixture.worker.fetch(
            trusted_business_sources.CUSTOMER_SOURCE_SALES_NO_PRICE_SQL, factory_id, None, None)]
        shipments = [dict(row) for row in await fixture.worker.fetch(
            trusted_business_sources.FINISHED_GOODS_SHIPMENT_SQL, factory_id, None, None)]
        inventory = [dict(row) for row in await fixture.worker.fetch(
            trusted_business_sources.FINISHED_GOODS_INVENTORY_SQL, factory_id)]
    return SimpleNamespace(
        base_sales=base_sales, base_sales_no_price=base_sales_no_price,
        sales=sales, sales_no_price=sales_no_price,
        source_sales=source_sales, source_sales_no_price=source_sales_no_price,
        shipments=shipments, inventory=inventory,
    )


def _ids(rows, key="订单行ID"):
    return {str(row[key]) for row in rows if row.get(key) is not None}


def _gap_text(gaps):
    return " ".join(" ".join(str(value) for value in gap.get("missing", [])) for gap in gaps)


def _fact_rows(frame, fact_type):
    return frame[frame["事实类型"].eq(fact_type)]


def _fact_ids(frame, fact_type):
    return {str(value) for value in _fact_rows(frame, fact_type)["订单行ID"].dropna().tolist()}


@pytest.mark.asyncio
async def test_commerce_joint_real_pg_priced_no_price_base_and_channel_sql():
    dsn, _ = _dsn()
    fixture = await _fixture(dsn)
    try:
        channel_ready = await _worker_fetch(
            fixture, trusted_business_sources.COMMERCE_CHANNEL_SCHEMA_SQL, "F006")
        assert channel_ready == [{"channel_ready": True}]
        facts = await _facts(fixture)
        assert _ids(facts.base_sales) == {"601"}
        assert _ids(facts.base_sales_no_price) == {"601"}
        assert _ids(facts.sales) == {"601"}
        assert _ids(facts.sales_no_price) == {"601"}
        assert _ids(facts.source_sales) == {"601"}
        assert _ids(facts.source_sales_no_price) == {"601"}
        assert {"单价", "折扣率", "订单总额"}.issubset(facts.base_sales[0])
        assert not {"单价", "折扣率", "订单总额"}.intersection(facts.sales_no_price[0])
        assert "客户来源" in facts.source_sales[0] and "渠道" not in facts.source_sales[0]
        assert "客户来源" in facts.source_sales_no_price[0]
        assert {row["批次工厂ID"] for row in facts.shipments} == {"F006"}
        assert {row["工厂ID"] for row in facts.inventory} == {"F006"}
        assert not await _worker_fetch(
            fixture, trusted_business_sources.COMMERCE_JOINT_SALES_SQL, "F007", None, None)
        frame, gaps = commerce_joint.prepare(
            facts.sales, facts.shipments, facts.inventory, "F006", _request(), True)
        assert frame is not None and not gaps
        assert _fact_ids(frame, "ORDER_LINE") == {"601"}
        candidates, build_gaps = commerce_joint.build(frame, _request(), True)
        assert not build_gaps
        titles = {candidate["title"] for candidate in candidates}
        assert "销售-发货-当前库存联合" in titles
        assert "客户-显式渠道销售金额联合" in titles
    finally:
        await _close(fixture)


@pytest.mark.asyncio
async def test_commerce_joint_real_pg_master_soft_deleted_cross_tenant_and_customer_owned_guards():
    dsn, _ = _dsn()
    fixture = await _fixture(dsn)
    try:
        await _admin(fixture, """
            INSERT INTO sales_orders VALUES
              ('o8','F006','2026-10-03','c6',10,'SHIPPED',NULL,'直营'),
              ('o9','F006','2026-10-03','c6',10,'SHIPPED',NULL,'直营'),
              ('o10','F006','2026-10-03','c6',10,'SHIPPED',NULL,'直营'),
              ('o11','F006','2026-10-03','c7',10,'SHIPPED',NULL,'直营'),
              ('o12','F006','2026-10-03','c6',10,'SHIPPED',NULL,'直营'),
              ('o13','F006','2026-10-03','c6',10,'SHIPPED','2026-10-04','直营');
            INSERT INTO sales_order_items VALUES
              (608,'o8','p1','SKU1','kg',1,10,0,NULL),
              (609,'o9','p1','SKU1','kg',1,10,0,NULL),
              (610,'o10','p1','SKU1','kg',1,10,0,NULL),
              (611,'o11','p1','SKU1','kg',1,10,0,NULL),
              (612,'o12','p1','SKU1','kg',1,10,0,NULL),
              (613,'o13','p1','SKU1','kg',1,10,0,NULL);
            INSERT INTO sales_delivery_records VALUES
              ('d8','F006','o8','c6','2026-10-03','SHIPPED',NULL,'LEGACY'),
              ('d9','F006','o9','c6','2026-10-03','SHIPPED',NULL,'LEGACY'),
              ('d10','F006','o10','c6','2026-10-03','SHIPPED',NULL,'MASTER'),
              ('d11','F006','o11','c7','2026-10-03','SHIPPED',NULL,'LEGACY'),
              ('d12','F006','o12','c6','2026-10-03','SHIPPED',NULL,'LEGACY'),
              ('d13','F006','o13','c6','2026-10-03','SHIPPED',NULL,'LEGACY');
            INSERT INTO finished_goods_batches VALUES
              ('b_owned_wrong','F006','BO','p1','SKU1','kg',4,0,0,'AVAILABLE','CUSTOMER_OWNED','c7',NULL,NULL,NULL),
              ('b_depleted','F006','BD','p1','SKU1','kg',4,4,0,'AVAILABLE','OWNED','c6',NULL,NULL,NULL);
            INSERT INTO sales_delivery_items VALUES
              (6080,'d8',608,'b_owned_wrong','p1',1,'kg',NULL),
              (6090,'d9',609,'b_depleted','p1',1,'kg',NULL),
              (6100,'d10',610,'b6','p1',1,'kg',NULL),
              (6110,'d11',611,'b6','p1',1,'kg',NULL),
              (6120,'d12',612,'b_depleted','p1',1,'kg',NULL),
              (6130,'d13',613,'b6','p1',1,'kg',NULL);
            UPDATE sales_order_items SET deleted_at='2026-10-05' WHERE id=610;
        """)
        facts = await _facts(fixture)
        assert {"610", "611", "613"}.isdisjoint(_ids(facts.sales))
        assert {"608", "609", "612"}.issubset(_ids(facts.sales))
        assert "610" not in _ids(facts.shipments)
        frame, gaps = commerce_joint.prepare(
            facts.sales, facts.shipments, facts.inventory, "F006", _request(), False)
        assert frame is not None
        frame_ids = _fact_ids(frame, "ORDER_LINE")
        assert "612" in frame_ids, "depleted batch historical sale must remain a relationship fact"
        assert "608" not in frame_ids, "CUSTOMER_OWNED batch for another customer must fail closed"
        assert "611" not in frame_ids, "cross-tenant customer must be excluded by SQL"
        assert "613" not in frame_ids, "soft-deleted order must be excluded by SQL"
        assert "校验" in _gap_text(gaps) or "客户" in _gap_text(gaps)
    finally:
        await _close(fixture)


@pytest.mark.asyncio
async def test_commerce_joint_real_pg_same_batch_two_order_lines_inventory_once():
    dsn, _ = _dsn()
    fixture = await _fixture(dsn)
    try:
        await _admin(fixture, """
            INSERT INTO sales_orders VALUES ('o8','F006','2026-10-03','c6',20,'SHIPPED',NULL,'直营');
            INSERT INTO sales_order_items VALUES (608,'o8','p1','SKU1','kg',2,10,0,NULL);
            INSERT INTO sales_delivery_records VALUES ('d8','F006','o8','c6','2026-10-03','SHIPPED',NULL,'LEGACY');
            INSERT INTO sales_delivery_items VALUES (6080,'d8',608,'b6','p1',2,'kg',NULL);
        """)
        facts = await _facts(fixture)
        assert _ids(facts.sales) == {"601", "608"}
        assert [row["成品批次ID"] for row in facts.shipments if str(row["销售订单行ID"]) in {"601", "608"}] == ["b6", "b6"]
        frame, gaps = commerce_joint.prepare(
            facts.sales, facts.shipments, facts.inventory, "F006", _request(), False)
        assert frame is not None and not gaps
        assert _fact_ids(frame, "ORDER_LINE") == {"601", "608"}
        shipment_rows = _fact_rows(frame, "SHIPMENT_RELATION")
        inventory_rows = _fact_rows(frame, "INVENTORY_BATCH")
        assert len(shipment_rows) == 2
        assert float(shipment_rows["发货数量"].sum()) == 3.0
        assert set(shipment_rows["成品批次ID"]) == {"b6"}
        assert float(inventory_rows["当前可用数量"].sum()) == 10.0
        assert set(inventory_rows["库存事实批次ID"]) == {"b6"}
        candidates, build_gaps = commerce_joint.build(frame, _request(), False)
        assert not build_gaps
        stock_candidates = [candidate for candidate in candidates if candidate["title"] == "销售-发货-当前库存联合"]
        assert len(stock_candidates) == 1
        stock_kpis = [kpi for kpi in stock_candidates[0]["kpis"] if kpi["label"] == "当前可用库存"]
        assert stock_kpis and stock_kpis[0]["value"] == 10.0, "same batch inventory must be emitted once"
    finally:
        await _close(fixture)


@pytest.mark.asyncio
async def test_commerce_joint_real_pg_missing_line_id_and_overdelivery_fail_closed():
    dsn, _ = _dsn()
    fixture = await _fixture(dsn)
    try:
        await _admin(fixture, """
            INSERT INTO sales_orders VALUES
              ('o8','F006','2026-10-03','c6',20,'SHIPPED',NULL,'直营'),
              ('o9','F006','2026-10-03','c6',20,'SHIPPED',NULL,'直营');
            INSERT INTO sales_order_items VALUES
              (608,'o8','p1','SKU1','kg',2,10,0,NULL),
              (609,'o9','p1','SKU1','kg',1,10,0,NULL);
            INSERT INTO sales_delivery_records VALUES
              ('d8','F006','o8','c6','2026-10-03','SHIPPED',NULL,'LEGACY'),
              ('d9','F006','o9','c6','2026-10-03','SHIPPED',NULL,'LEGACY');
            INSERT INTO sales_delivery_items VALUES
              (6080,'d8',NULL,'b6','p1',2,'kg',NULL),
              (6090,'d9',609,'b6','p1',2,'kg',NULL);
        """)
        facts = await _facts(fixture)
        assert any(row["销售订单行ID"] is None for row in facts.shipments)
        frame, gaps = commerce_joint.prepare(
            facts.sales, facts.shipments, facts.inventory, "F006", _request(), False)
        assert frame is not None
        frame_ids = _fact_ids(frame, "ORDER_LINE")
        assert "608" not in frame_ids and "609" not in frame_ids
        gap_text = _gap_text(gaps)
        assert "销售订单行ID" in gap_text
        assert "数量守恒" in gap_text or "校验" in gap_text
        assert "601" in frame_ids
    finally:
        await _close(fixture)


@pytest.mark.asyncio
async def test_commerce_joint_real_pg_channel_schema_duplicate_wrong_and_shadow_guards():
    dsn, _ = _dsn()
    fixture = await _fixture(dsn)
    try:
        shadow = "shadow_" + uuid.uuid4().hex[:8]
        await _admin(fixture, f"CREATE SCHEMA {shadow}")
        await _admin(fixture, f"CREATE TABLE {shadow}.sales_orders (id text, cf_smartbi_sales_channel text)")
        await _admin(fixture, "ALTER TABLE sales_orders DROP COLUMN cf_smartbi_sales_channel")
        await fixture.worker.execute(f"SET search_path TO {fixture.schema},{shadow},public")
        result = [dict(row) for row in await fixture.worker.fetch(
            trusted_business_sources.COMMERCE_CHANNEL_SCHEMA_SQL, "F006")]
        assert result == [{"channel_ready": False}], "shadow column must not satisfy current_schema()"
        facts = await _facts(fixture, include_channel=False)
        assert _ids(facts.base_sales) == {"601"}, "base SQL must remain usable when channel column is absent"
        frame, gaps = commerce_joint.prepare(
            facts.base_sales, facts.shipments, facts.inventory, "F006", _request(), False)
        assert frame is not None
        assert _fact_ids(frame, "ORDER_LINE") == {"601"}
        candidates, _ = commerce_joint.build(frame, _request(), False)
        assert any(candidate["title"] == "销售-发货-当前库存联合" for candidate in candidates)
        await fixture.admin.execute(f"DROP SCHEMA IF EXISTS {shadow} CASCADE")
    finally:
        await _close(fixture)

    fixture = await _fixture(dsn)
    try:
        await _admin(fixture, """
            INSERT INTO canvas_dynamic_field(factory_id,module_code,status,field_code,field_type,column_name,config)
            VALUES ('F006','sales_order','ACTIVE','smartbi_sales_channel','TEXT','cf_smartbi_sales_channel','{"smartbiSemantic":"sales_channel.v1"}');
        """)
        result = await _worker_fetch(
            fixture, trusted_business_sources.COMMERCE_CHANNEL_SCHEMA_SQL, "F006")
        assert result == [{"channel_ready": False}], "duplicate ACTIVE definitions must fail closed"
    finally:
        await _close(fixture)

    fixture = await _fixture(dsn)
    try:
        await _admin(fixture, "UPDATE canvas_dynamic_field SET config=$1::jsonb WHERE factory_id='F006'", '{"smartbiSemantic":"wrong.v1"}')
        result = await _worker_fetch(
            fixture, trusted_business_sources.COMMERCE_CHANNEL_SCHEMA_SQL, "F006")
        assert result == [{"channel_ready": False}], "wrong ACTIVE semantic must fail closed"
    finally:
        await _close(fixture)


@pytest.mark.asyncio
async def test_commerce_joint_real_pg_missing_channel_keeps_sales_stock_and_filters_channel_family():
    dsn, _ = _dsn()
    fixture = await _fixture(dsn)
    try:
        await _admin(fixture, """
            INSERT INTO sales_orders VALUES ('o8','F006','2026-10-03','c6',20,'SHIPPED',NULL,NULL);
            INSERT INTO sales_order_items VALUES (608,'o8','p1','SKU1','kg',2,10,0,NULL);
            INSERT INTO sales_delivery_records VALUES ('d8','F006','o8','c6','2026-10-03','SHIPPED',NULL,'LEGACY');
            INSERT INTO sales_delivery_items VALUES (6080,'d8',608,'b6','p1',2,'kg',NULL);
        """)
        facts = await _facts(fixture, include_channel=False)
        assert _ids(facts.base_sales) == {"601", "608"}
        frame, gaps = commerce_joint.prepare(
            facts.base_sales, facts.shipments, facts.inventory, "F006", _request(), False)
        assert frame is not None, "missing channel must not erase sales-stock facts"
        assert _fact_ids(frame, "ORDER_LINE") == {"601", "608"}
        candidates, _ = commerce_joint.build(frame, _request(), False)
        titles = {candidate["title"] for candidate in candidates}
        assert "销售-发货-当前库存联合" in titles
        channel = [candidate for candidate in candidates if candidate["title"] == "客户-显式渠道销售数量联合"]
        assert not channel, "without a valid dynamic channel column, customer-channel family must be an empty subset"
    finally:
        await _close(fixture)


@pytest.mark.asyncio
async def test_commerce_joint_real_pg_moneyless_sql_does_not_touch_amount_columns():
    dsn, asyncpg = _dsn()
    fixture = await _fixture(dsn, moneyless=True)
    try:
        facts = await _facts(fixture, include_priced=False)
        assert _ids(facts.sales_no_price) == {"601"}
        assert _ids(facts.source_sales_no_price) == {"601"}
        assert not {"单价", "折扣率", "订单总额"}.intersection(facts.sales_no_price[0])
        assert not {"单价", "折扣率", "订单总额"}.intersection(facts.source_sales_no_price[0])
        async with fixture.worker.transaction():
            await fixture.worker.execute("SELECT set_config('app.factory_id',$1,true)", "F006")
            with pytest.raises(asyncpg.exceptions.InsufficientPrivilegeError):
                await fixture.worker.fetch(
                    trusted_business_sources.COMMERCE_JOINT_SALES_SQL, "F006", None, None)
    finally:
        await _close(fixture)
