#!/usr/bin/env python3
"""Seed a deterministic traceability chain for the 2026-10-15 daily review.

This is deliberately a database fixture, rather than an API bootstrap.  The
database is a private loopback PostgreSQL instance and the fixture uses the
same production tables, workflow pin trigger, and lineage closure trigger as
the application.  No DDL, unrelated-row updates, deletes, or user changes are
performed.  The deterministic customer rows are normalized to the real
``CustomerStatus`` enum on rerun so an earlier fixture cannot leave an invalid
``NORMAL`` value behind.

The two factories are seeded independently so the UI/API check can prove that
factory scoping is real:

    raw material (100 kg, consume 60 kg)
      -> production plan + production batch (40 kg)
      -> finished batch (40 kg, ship 12 kg)
      -> sales delivery + legacy shipment record (12 kg)

All fixture identifiers begin with ``REVIEW-TRACE``.  The script is safe to
    re-run: an already-present fixture row is reused after its factory ownership
    is checked; only the fixture customer's own invalid enum value is repaired.
"""

from __future__ import annotations

import json
import os
import sys
from datetime import date, datetime, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any, Iterable

try:
    import psycopg2
    from psycopg2.extras import Json, RealDictCursor
except ImportError as exc:  # pragma: no cover - environment guard
    raise SystemExit("seed_trace.py requires psycopg2 in the local Python environment") from exc


DB_HOST = os.environ.get("TRACE_DB_HOST", "127.0.0.1")
DB_PORT = int(os.environ.get("TRACE_DB_PORT", "55447"))
DB_NAME = os.environ.get("TRACE_DB_NAME", "vision_entry_probe_20261015")
DB_USER = os.environ.get("TRACE_DB_USER", "assembly_executor")
DB_PASSWORD = os.environ.get("TRACE_DB_PASSWORD", "")
EVIDENCE_DIR = Path(
    os.environ.get(
        "TRACE_EVIDENCE_DIR",
        r"C:\Users\Steve\Documents\cretas-connect-20261015\daily-review\trace",
    )
)
SAMPLE_DATE = date(2026, 10, 15)
PREFIX = "REVIEW-TRACE"
FACTORIES = {
    "A": {"id": "VISION-ENTRY-A", "user_id": 92001},
    "B": {"id": "VISION-ENTRY-B", "user_id": 92002},
}

TABLES = (
    "factory_warehouses",
    "raw_material_types",
    "product_types",
    "customers",
    "material_batches",
    "product_process_workflows",
    "product_process_workflow_activations",
    "production_plans",
    "production_batches",
    "material_consumptions",
    "finished_goods_batches",
    "sales_orders",
    "sales_order_items",
    "sales_delivery_records",
    "sales_delivery_items",
    "shipment_records",
    "batch_lineage_edges",
    "batch_lineage_closure",
)


class SeedError(RuntimeError):
    """A fail-closed fixture or scope error."""


def now_utc() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def marker(factory_key: str, suffix: str) -> str:
    return f"{PREFIX}-{factory_key}-{suffix}"


def ensure_loopback_connection() -> Any:
    if DB_HOST != "127.0.0.1" or DB_PORT != 55447:
        raise SeedError(
            "refusing non-loopback/non-assigned DB: "
            f"host={DB_HOST!r} port={DB_PORT!r}; expected 127.0.0.1:55447"
        )
    if DB_NAME != "vision_entry_probe_20261015" or DB_USER != "assembly_executor":
        raise SeedError(
            "refusing non-assigned DB identity: "
            f"dbname={DB_NAME!r} user={DB_USER!r}"
        )
    conn = psycopg2.connect(
        host=DB_HOST,
        port=DB_PORT,
        dbname=DB_NAME,
        user=DB_USER,
        password=DB_PASSWORD,
        connect_timeout=5,
        cursor_factory=RealDictCursor,
    )
    with conn.cursor() as cur:
        cur.execute("SELECT inet_server_addr() AS server_addr, current_database() AS db, current_user AS username")
        identity = cur.fetchone()
    if str(identity["server_addr"]) != "127.0.0.1" or identity["db"] != DB_NAME or identity["username"] != DB_USER:
        conn.close()
        raise SeedError(f"database identity guard failed: {dict(identity)}")
    return conn


def required_schema_guard(cur: Any) -> None:
    required_tables = {
        "factories",
        "users",
        "factory_warehouses",
        "raw_material_types",
        "product_types",
        "customers",
        "material_batches",
        "product_process_workflows",
        "product_process_workflow_activations",
        "production_plans",
        "production_batches",
        "material_consumptions",
        "finished_goods_batches",
        "sales_orders",
        "sales_order_items",
        "sales_delivery_records",
        "sales_delivery_items",
        "shipment_records",
        "batch_lineage_edges",
        "batch_lineage_closure",
    }
    cur.execute(
        """
        SELECT table_name
          FROM information_schema.tables
         WHERE table_schema = 'public'
           AND table_name = ANY(%s)
        """,
        (list(required_tables),),
    )
    actual = {row["table_name"] for row in cur.fetchall()}
    missing = sorted(required_tables - actual)
    if missing:
        raise SeedError(f"required tables missing; refusing partial seed: {missing}")

    required_columns = {
        "production_batches": {"id", "factory_id", "batch_number", "production_plan_id", "product_type_id", "quantity", "actual_quantity", "good_quantity", "status", "unit"},
        "material_consumptions": {"batch_id", "factory_id", "production_batch_id", "production_plan_id", "quantity", "unit_price", "total_cost", "consumption_time", "recorded_by"},
        "batch_lineage_edges": {"id", "factory_id", "edge_type", "source_type", "source_id", "target_type", "target_id", "quantity_used", "unit", "event_time", "meta"},
        "sales_delivery_records": {"id", "factory_id", "delivery_number", "sales_order_id", "customer_id", "delivery_date", "status", "shipped_by"},
    }
    cur.execute(
        """
        SELECT table_name, column_name
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = ANY(%s)
        """,
        (list(required_columns),),
    )
    columns = {(row["table_name"], row["column_name"]) for row in cur.fetchall()}
    missing_columns = {
        table: sorted(wanted - {column for t, column in columns if t == table})
        for table, wanted in required_columns.items()
        if wanted - {column for t, column in columns if t == table}
    }
    if missing_columns:
        raise SeedError(f"required columns missing; refusing partial seed: {missing_columns}")


def factory_and_user_guard(cur: Any) -> None:
    for key, spec in FACTORIES.items():
        cur.execute("SELECT id FROM factories WHERE id = %s", (spec["id"],))
        if cur.fetchone() is None:
            raise SeedError(f"assigned factory missing: {spec['id']}")
        cur.execute(
            "SELECT id, factory_id, is_active FROM users WHERE id = %s",
            (spec["user_id"],),
        )
        user = cur.fetchone()
        if user is None or user["factory_id"] != spec["id"] or not user["is_active"]:
            raise SeedError(f"assigned factory user guard failed for {key}: {user}")


def target_counts(cur: Any) -> dict[str, dict[str, int]]:
    counts: dict[str, dict[str, int]] = {}
    scoped_from_parent = {
        "sales_order_items": "JOIN sales_orders parent ON parent.id = sales_order_items.sales_order_id",
        "sales_delivery_items": "JOIN sales_delivery_records parent ON parent.id = sales_delivery_items.delivery_record_id",
    }
    for key, spec in FACTORIES.items():
        counts[key] = {}
        for table in TABLES:
            join = scoped_from_parent.get(table, "")
            factory_column = "parent.factory_id" if join else "factory_id"
            cur.execute(f"SELECT COUNT(*) AS n FROM {table} {join} WHERE {factory_column} = %s", (spec["id"],))
            counts[key][table] = int(cur.fetchone()["n"])
    return counts


def synthetic_counts(cur: Any) -> dict[str, int]:
    result: dict[str, int] = {}
    for table in TABLES:
        if table == "production_batches":
            predicate, params = "batch_number LIKE %s", (f"{PREFIX}-%",)
        elif table == "production_plans":
            predicate, params = "(id LIKE %s OR plan_number LIKE %s)", (f"{PREFIX}-%", f"{PREFIX}-%")
        elif table in {"product_process_workflows", "product_process_workflow_activations"}:
            predicate, params = "product_type_id LIKE %s", (f"{PREFIX}-%",)
        elif table == "batch_lineage_closure":
            predicate, params = "(ancestor_id LIKE %s OR descendant_id LIKE %s)", (f"{PREFIX}-%", f"{PREFIX}-%")
        elif table == "material_consumptions":
            predicate, params = "notes LIKE %s", (f"%{PREFIX}%",)
        elif table in {"sales_order_items", "sales_delivery_items"}:
            predicate, params = "product_type_id LIKE %s", (f"{PREFIX}-%",)
        else:
            predicate, params = "(id LIKE %s)", (f"{PREFIX}-%",)
        cur.execute(f"SELECT COUNT(*) AS n FROM {table} WHERE {predicate}", params)
        result[table] = int(cur.fetchone()["n"])
    return result


def assert_no_prefix_outside_scope(cur: Any) -> None:
    # All rows carrying the deterministic marker must belong to one of the two
    # assigned factories.  This catches accidental use of another tenant on a
    # previous run before any new write is attempted.
    allowed = tuple(spec["id"] for spec in FACTORIES.values())
    checks = {
        "factory_warehouses": "id LIKE %s",
        "raw_material_types": "id LIKE %s",
        "product_types": "id LIKE %s",
        "customers": "id LIKE %s",
        "material_batches": "id LIKE %s",
        "production_plans": "id LIKE %s OR plan_number LIKE %s",
        "production_batches": "batch_number LIKE %s",
        "finished_goods_batches": "id LIKE %s",
        "sales_orders": "id LIKE %s",
        "sales_delivery_records": "id LIKE %s",
        "shipment_records": "id LIKE %s",
        "batch_lineage_edges": "id LIKE %s",
    }
    for table, predicate in checks.items():
        params = (f"{PREFIX}-%",) * predicate.count("%s")
        cur.execute(
            f"SELECT DISTINCT factory_id FROM {table} WHERE ({predicate}) AND factory_id <> ALL(%s)",
            (*params, list(allowed)),
        )
        bad = [row["factory_id"] for row in cur.fetchall()]
        if bad:
            raise SeedError(f"synthetic rows outside assigned factories in {table}: {bad}")


def one(cur: Any, sql: str, params: Iterable[Any] = ()) -> Any:
    cur.execute(sql, tuple(params))
    return cur.fetchone()


def ensure_row(cur: Any, table: str, row_id: str, factory_id: str) -> bool:
    found = one(cur, f"SELECT factory_id FROM {table} WHERE id = %s", (row_id,))
    if found is None:
        return False
    if found["factory_id"] != factory_id:
        raise SeedError(f"existing {table}.{row_id} belongs to {found['factory_id']}, expected {factory_id}")
    return True


def ensure_factory_rows(cur: Any, key: str, spec: dict[str, Any]) -> dict[str, str | int]:
    fid = spec["id"]
    uid = spec["user_id"]
    ids = {
        "raw_warehouse": marker(key, "WH-RAW"),
        "fg_warehouse": marker(key, "WH-FG"),
        "raw_type": marker(key, "RAW-TYPE"),
        "product": marker(key, "FINISHED-SKU"),
        "customer": marker(key, "CUSTOMER"),
        "material_batch": marker(key, "RAW-BATCH-001"),
        "plan": marker(key, "PLAN-001"),
        "production_batch_number": marker(key, "PROD-BATCH-001"),
        "finished_batch": marker(key, "FG-BATCH-001"),
        "sales_order": marker(key, "ORDER-001"),
        "delivery": marker(key, "DELIVERY-001"),
        "legacy_shipment": marker(key, "SHIPMENT-001"),
        "edge_raw": marker(key, "EDGE-RAW"),
        "edge_finished": marker(key, "EDGE-FINISHED"),
        "edge_shipment": marker(key, "EDGE-SHIPMENT"),
    }
    created = now_utc()

    if not ensure_row(cur, "factory_warehouses", ids["raw_warehouse"], fid):
        cur.execute(
            """INSERT INTO factory_warehouses
               (id, factory_id, code, name, type, is_active, remarks, created_at, updated_at)
               VALUES (%s, %s, %s, %s, %s, TRUE, %s, %s, %s)""",
            (ids["raw_warehouse"], fid, "REVIEW-RAW", "审查样品原料仓", "RAW", "DAILY-TRACE-SEED", created, created),
        )
    if not ensure_row(cur, "factory_warehouses", ids["fg_warehouse"], fid):
        cur.execute(
            """INSERT INTO factory_warehouses
               (id, factory_id, code, name, type, is_active, remarks, created_at, updated_at)
               VALUES (%s, %s, %s, %s, %s, TRUE, %s, %s, %s)""",
            (ids["fg_warehouse"], fid, "REVIEW-FG", "审查样品成品仓", "FINISHED", "DAILY-TRACE-SEED", created, created),
        )

    if not ensure_row(cur, "raw_material_types", ids["raw_type"], fid):
        cur.execute(
            """INSERT INTO raw_material_types
               (id, factory_id, code, name, unit, main_category, category, storage_type,
                is_active, tax_treatment, self_supplied, is_byproduct, created_by,
                created_at, updated_at, actor_type, actor_id)
               VALUES (%s, %s, %s, %s, 'kg', '原料', '粮食', 'DRY', TRUE,
                       'TAXABLE', TRUE, FALSE, %s, %s, %s, 'FACTORY_USER', %s)""",
            (ids["raw_type"], fid, marker(key, "RAW-CODE"), "审查样品原料", uid, created, created, uid),
        )
    if not ensure_row(cur, "product_types", ids["product"], fid):
        cur.execute(
            """INSERT INTO product_types
               (id, factory_id, code, name, category, unit, is_active, product_category,
                shelf_life_days, package_spec, specification, created_by, created_at, updated_at)
               VALUES (%s, %s, %s, %s, '成品', 'kg', TRUE, 'FINISHED_PRODUCT',
                       30, '1kg/袋', '日常追溯审查样品', %s, %s, %s)""",
            (ids["product"], fid, marker(key, "FINISHED-CODE"), "日常追溯审查成品", uid, created, created),
        )
    customer_exists = ensure_row(cur, "customers", ids["customer"], fid)
    if not customer_exists:
        cur.execute(
            """INSERT INTO customers
               (id, factory_id, code, name, customer_code, is_active, customer_status,
                customer_type, created_by, version, created_at, updated_at)
               VALUES (%s, %s, %s, %s, %s, TRUE, 'RECURRING', 'REVIEW', %s, 0, %s, %s)""",
            (ids["customer"], fid, marker(key, "CUSTOMER-CODE"), "追溯审查客户", marker(key, "CUSTOMER-CODE"), uid, created, created),
        )
    else:
        customer_status = one(
            cur,
            "SELECT customer_status FROM customers WHERE id = %s AND factory_id = %s",
            (ids["customer"], fid),
        )["customer_status"]
        if customer_status == "NORMAL":
            cur.execute(
                """UPDATE customers
                   SET customer_status = 'RECURRING', updated_at = %s
                   WHERE id = %s AND factory_id = %s AND customer_status = 'NORMAL'""",
                (created, ids["customer"], fid),
            )
        elif customer_status not in {
            "LEAD", "INITIAL_CONTACT", "SAMPLE_SENT", "QUOTING", "NEGOTIATING",
            "SIGNING", "RECURRING", "INACTIVE", "LOST", "BLACKLIST", "RECOVERED",
        }:
            raise SeedError(
                f"unexpected CustomerStatus for own fixture row {ids['customer']}: {customer_status!r}"
            )
    if not ensure_row(cur, "material_batches", ids["material_batch"], fid):
        cur.execute(
            """INSERT INTO material_batches
               (id, factory_id, batch_number, material_type_id, inbound_date,
                warehouse_id, version, receipt_quantity, quantity_unit, used_quantity,
                reserved_quantity, status, storage_location, notes, created_by,
                created_at, updated_at)
               VALUES (%s, %s, %s, %s, %s, %s, 0, 100, 'kg', 60, 0,
                       'AVAILABLE', 'REVIEW-RAW-BIN', %s, %s, %s, %s)""",
            (ids["material_batch"], fid, marker(key, "RAW-BATCH-001"), ids["raw_type"], SAMPLE_DATE, ids["raw_warehouse"], f"{PREFIX}: 100kg raw -> 60kg production", uid, created, created),
        )

    # A published workflow and plan are required by the live production-batch
    # pin trigger.  The graph records the same raw/product relationship used by
    # the fixture; it does not alter any pre-existing workflow.
    workflow_nodes = [
        {"id": "review-input", "kind": "MATERIAL", "data": {"name": "审查样品原料", "skuId": ids["raw_type"], "materialKind": "RAW", "unit": "kg"}},
        {"id": "review-output", "kind": "FINISHED_GOOD", "data": {"name": "日常追溯审查成品", "skuId": ids["product"], "materialKind": "FINISHED_GOOD", "unit": "kg"}},
    ]
    workflow_edges = [{"id": "review-edge", "source": "review-input", "target": "review-output"}]
    workflow = one(
        cur,
        """SELECT id FROM product_process_workflows
           WHERE factory_id = %s AND product_type_id = %s
             AND status = 'PUBLISHED' AND definition_version = 1
           ORDER BY id LIMIT 1""",
        (fid, ids["product"]),
    )
    if workflow is None:
        cur.execute(
            """INSERT INTO product_process_workflows
               (factory_id, product_type_id, schema_version, status, definition_version,
                nodes_json, edges_json, viewport_json, lock_version, created_at, updated_at,
                unit_review_required)
               VALUES (%s, %s, 1, 'PUBLISHED', 1, %s, %s,
                       '{"x":0,"y":0,"zoom":1}'::jsonb, 0, %s, %s, FALSE)
               RETURNING id""",
            (fid, ids["product"], Json(workflow_nodes), Json(workflow_edges), created, created),
        )
        workflow_id = int(cur.fetchone()["id"])
    else:
        workflow_id = int(workflow["id"])
    ids["workflow"] = workflow_id

    activation = one(
        cur,
        """SELECT id FROM product_process_workflow_activations
           WHERE factory_id = %s AND product_type_id = %s""",
        (fid, ids["product"]),
    )
    if activation is None:
        cur.execute(
            """INSERT INTO product_process_workflow_activations
               (factory_id, product_type_id, active_workflow_id, active_definition_version,
                enabled, activated_by, activated_at, lock_version, created_at, updated_at,
                actor_type, actor_id)
               VALUES (%s, %s, %s, 1, TRUE, %s, %s, 0, %s, %s, 'FACTORY_USER', %s)""",
            (fid, ids["product"], workflow_id, uid, created, created, created, uid),
        )

    plan = one(cur, "SELECT id FROM production_plans WHERE id = %s", (ids["plan"],))
    if plan is None:
        cur.execute(
            """INSERT INTO production_plans
               (id, factory_id, plan_number, product_type_id, planned_quantity, planned_unit,
                actual_quantity, planned_date, status, plan_type, created_by, created_at,
                updated_at, vflag, workflow_selection_mode, selected_workflow_id,
                selected_workflow_version, workflow_output_units_by_product,
                selected_bom_recipe_ids_by_product, selected_bom_versions_by_product,
                processing_mode, material_supply_mode, output_ownership, allocated_quantity,
                is_fully_matched, notes, client_request_id)
               VALUES (%s, %s, %s, %s, 40, 'kg', 40, %s, 'COMPLETED', 'FROM_INVENTORY',
                       %s, %s, %s, 'CREATED', 'WORKFLOW', %s, 1, %s, '{}'::jsonb, '{}'::jsonb,
                       'STANDARD_SALE', 'FACTORY_SUPPLIED', 'COMPANY_OWNED', 60, TRUE, %s, %s)""",
            (ids["plan"], fid, marker(key, "PLAN-001"), ids["product"], SAMPLE_DATE, uid, created, created, workflow_id, Json({ids["product"]: "kg"}), f"{PREFIX}: workflow-governed sample", marker(key, "PLAN-REQUEST")),
        )

    production = one(
        cur,
        "SELECT id FROM production_batches WHERE batch_number = %s",
        (ids["production_batch_number"],),
    )
    if production is None:
        cur.execute(
            """INSERT INTO production_batches
               (factory_id, batch_number, production_plan_id, product_type_id, product_name,
                planned_quantity, quantity, actual_quantity, good_quantity, defect_quantity,
                unit, status, quality_status, start_time, end_time, supervisor_id,
                supervisor_name, created_by, created_at, updated_at, notes)
               VALUES (%s, %s, %s, %s, '日常追溯审查成品', 40, 40, 40, 40, 0,
                       'kg', 'COMPLETED', 'PASSED', %s, %s, %s, '视觉入口管理员', %s, %s, %s, %s)
               RETURNING id""",
            (fid, ids["production_batch_number"], ids["plan"], ids["product"], datetime(2026, 10, 15, 8, 0), datetime(2026, 10, 15, 10, 0), uid, uid, created, created, f"{PREFIX}: 40kg production output"),
        )
        production_id = int(cur.fetchone()["id"])
    else:
        if production["id"] is None:
            raise SeedError(f"production batch lookup returned no id: {ids['production_batch_number']}")
        production_id = int(production["id"])
        owner = one(cur, "SELECT factory_id, product_type_id, production_plan_id FROM production_batches WHERE id = %s", (production_id,))
        if owner["factory_id"] != fid or owner["product_type_id"] != ids["product"] or owner["production_plan_id"] != ids["plan"]:
            raise SeedError(f"existing production batch does not match fixture chain: {owner}")
    ids["production"] = production_id

    consumption = one(
        cur,
        """SELECT id FROM material_consumptions
           WHERE factory_id = %s AND batch_id = %s AND production_batch_id = %s""",
        (fid, ids["material_batch"], production_id),
    )
    if consumption is None:
        cur.execute(
            """INSERT INTO material_consumptions
               (factory_id, production_plan_id, production_batch_id, batch_id, quantity,
                unit_price, total_cost, consumption_time, consumed_at, recorded_by,
                planned_quantity, material_type_id, source_type, notes, created_at, updated_at)
               VALUES (%s, %s, %s, %s, 60, 4.00, 240.00, %s, %s, %s, 60, %s,
                       'MANUAL', %s, %s, %s)
               RETURNING id""",
            (fid, ids["plan"], production_id, ids["material_batch"], datetime(2026, 10, 15, 8, 5), datetime(2026, 10, 15, 8, 5), uid, ids["raw_type"], f"{PREFIX}: source raw batch", created, created),
        )
        ids["consumption"] = int(cur.fetchone()["id"])
    else:
        ids["consumption"] = int(consumption["id"])

    if not ensure_row(cur, "finished_goods_batches", ids["finished_batch"], fid):
        cur.execute(
            """INSERT INTO finished_goods_batches
               (id, factory_id, batch_number, product_type_id, product_name,
                produced_quantity, shipped_quantity, reserved_quantity, unit, unit_price,
                production_date, expire_date, storage_location, production_plan_id,
                warehouse_id, status, created_by, remark, created_at, updated_at)
               VALUES (%s, %s, %s, %s, '日常追溯审查成品', 40, 12, 0, 'kg', 18.00,
                       %s, %s, 'REVIEW-FG-BIN', %s, %s, 'AVAILABLE', %s, %s, %s, %s)""",
            (ids["finished_batch"], fid, marker(key, "FG-BATCH-001"), ids["product"], SAMPLE_DATE, date(2026, 11, 14), ids["plan"], ids["fg_warehouse"], uid, f"{PREFIX}: 40kg finished, 12kg shipped", created, created),
        )

    order = one(cur, "SELECT id FROM sales_orders WHERE id = %s", (ids["sales_order"],))
    if order is None:
        cur.execute(
            """INSERT INTO sales_orders
               (id, factory_id, order_number, customer_id, order_date, required_delivery_date,
                delivery_address, total_amount, status, created_by, confirmed_at,
                vflag, processing_mode, material_supply_mode, created_at, updated_at, remark)
               VALUES (%s, %s, %s, %s, %s, %s, '审查样品收货地址', 216, 'COMPLETED', %s,
                       %s, 'CREATED', 'STANDARD_SALE', 'FACTORY_SUPPLIED', %s, %s, %s)""",
            (ids["sales_order"], fid, marker(key, "ORDER-001"), ids["customer"], SAMPLE_DATE, SAMPLE_DATE, uid, datetime(2026, 10, 15, 10, 5), created, created, f"{PREFIX}: downstream shipment sample"),
        )
    order_item = one(cur, "SELECT id FROM sales_order_items WHERE sales_order_id = %s", (ids["sales_order"],))
    if order_item is None:
        cur.execute(
            """INSERT INTO sales_order_items
               (sales_order_id, product_type_id, product_name, quantity, unit, unit_price,
                delivered_quantity, created_at, updated_at)
               VALUES (%s, %s, '日常追溯审查成品', 12, 'kg', 18.00, 12, %s, %s)
               RETURNING id""",
            (ids["sales_order"], ids["product"], created, created),
        )
        order_item_id = int(cur.fetchone()["id"])
    else:
        order_item_id = int(order_item["id"])
    ids["order_item"] = order_item_id

    delivery = one(cur, "SELECT id FROM sales_delivery_records WHERE id = %s", (ids["delivery"],))
    if delivery is None:
        cur.execute(
            """INSERT INTO sales_delivery_records
               (id, factory_id, delivery_number, sales_order_id, record_role, shipment_sequence,
                customer_id, delivery_date, delivery_address, logistics_company,
                tracking_number, delivery_method, status, shipped_by, total_amount, remark,
                created_at, updated_at)
               VALUES (%s, %s, %s, %s, 'SHIPMENT', 1, %s, %s, '审查样品收货地址',
                       '审查物流', %s, 'LOGISTICS', 'SHIPPED', %s, 216, %s, %s, %s)""",
            (ids["delivery"], fid, marker(key, "DELIVERY-001"), ids["sales_order"], ids["customer"], SAMPLE_DATE, marker(key, "TRACK-001"), uid, f"{PREFIX}: finished batch -> shipment", created, created),
        )
    delivery_item = one(cur, "SELECT id FROM sales_delivery_items WHERE delivery_record_id = %s", (ids["delivery"],))
    if delivery_item is None:
        cur.execute(
            """INSERT INTO sales_delivery_items
               (delivery_record_id, sales_order_item_id, product_type_id, product_name,
                delivered_quantity, unit, unit_price, finished_goods_batch_id, remark,
                created_at, updated_at)
               VALUES (%s, %s, %s, '日常追溯审查成品', 12, 'kg', 18.00, %s, %s, %s, %s)
               RETURNING id""",
            (ids["delivery"], order_item_id, ids["product"], ids["finished_batch"], f"{PREFIX}: 12kg shipped", created, created),
        )
        ids["delivery_item"] = int(cur.fetchone()["id"])
    else:
        ids["delivery_item"] = int(delivery_item["id"])

    if not ensure_row(cur, "shipment_records", ids["legacy_shipment"], fid):
        cur.execute(
            """INSERT INTO shipment_records
               (id, factory_id, shipment_number, customer_id, order_number, batch_number,
                product_name, quantity, unit, unit_price, total_amount, shipment_date,
                delivery_address, logistics_company, tracking_number, status, recorded_by,
                notes, created_at, updated_at)
               VALUES (%s, %s, %s, %s, %s, %s, '日常追溯审查成品', 12, 'kg', 18.00, 216,
                       %s, '审查样品收货地址', '审查物流', %s, 'shipped', %s, %s, %s, %s)""",
            (ids["legacy_shipment"], fid, marker(key, "SHIPMENT-001"), ids["customer"], marker(key, "ORDER-001"), ids["production_batch_number"], SAMPLE_DATE, marker(key, "TRACK-001"), uid, f"{PREFIX}: compatibility shipment read path", created, created),
        )

    # The lineage edge trigger maintains batch_lineage_closure.  Edges point
    # at the sales_delivery_records row because LineageGraphAssembler resolves
    # SHIPMENT_RECORD nodes through that entity.
    edge_rows = [
        (ids["edge_raw"], "RAW_TO_PRODUCTION", "MATERIAL_BATCH", ids["material_batch"], "PRODUCTION_BATCH", str(production_id), Decimal("60"), "kg", {"seed": PREFIX, "meaning": "raw material consumed by production"}),
        (ids["edge_finished"], "PRODUCTION_TO_FINISHED", "PRODUCTION_BATCH", str(production_id), "FINISHED_BATCH", ids["finished_batch"], Decimal("40"), "kg", {"seed": PREFIX, "meaning": "production output stocked as finished goods"}),
        (ids["edge_shipment"], "FINISHED_TO_SHIPMENT", "FINISHED_BATCH", ids["finished_batch"], "SHIPMENT_RECORD", ids["delivery"], Decimal("12"), "kg", {"seed": PREFIX, "meaning": "finished goods shipped to review customer"}),
    ]
    for edge_id, edge_type, source_type, source_id, target_type, target_id, quantity, unit, meta in edge_rows:
        existing = one(cur, "SELECT factory_id, source_type, source_id, target_type, target_id FROM batch_lineage_edges WHERE id = %s", (edge_id,))
        if existing is None:
            cur.execute(
                """INSERT INTO batch_lineage_edges
                   (id, factory_id, edge_type, source_type, source_id, target_type, target_id,
                    quantity_used, unit, event_time, operator_id, meta, created_at, updated_at)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)""",
                (edge_id, fid, edge_type, source_type, source_id, target_type, target_id, quantity, unit, datetime(2026, 10, 15, 8, 0), uid, Json(meta), created, created),
            )
        elif existing["factory_id"] != fid or existing["source_id"] != source_id or existing["target_id"] != target_id:
            raise SeedError(f"existing lineage edge does not match fixture chain: {edge_id} {dict(existing)}")

    ids["delivery"] = str(ids["delivery"])
    ids["finished_batch"] = str(ids["finished_batch"])
    ids["material_batch"] = str(ids["material_batch"])
    ids["plan"] = str(ids["plan"])
    ids["product"] = str(ids["product"])
    ids["raw_type"] = str(ids["raw_type"])
    ids["factory_id"] = fid
    ids["production_batch_number"] = str(ids["production_batch_number"])
    return ids


def readback(cur: Any, seeded: dict[str, dict[str, str | int]]) -> dict[str, Any]:
    result: dict[str, Any] = {"factories": {}, "cross_factory_sentinels": {}}
    for key, ids in seeded.items():
        fid = ids["factory_id"]
        production_id = ids["production"]
        cur.execute(
            """SELECT mb.id AS material_id, mb.factory_id AS material_factory,
                       mb.batch_number AS material_batch, mb.receipt_quantity,
                       mb.used_quantity, mc.quantity AS consumed_quantity,
                       pb.id AS production_id, pb.factory_id AS production_factory,
                       pb.batch_number AS production_batch, pb.status AS production_status,
                       fgb.id AS finished_id, fgb.batch_number AS finished_batch,
                       fgb.produced_quantity, fgb.shipped_quantity,
                       sdr.id AS delivery_id, sdr.delivery_number, sdr.status AS delivery_status,
                       c.name AS customer_name
                  FROM material_batches mb
                  JOIN material_consumptions mc ON mc.batch_id = mb.id
                  JOIN production_batches pb ON pb.id = mc.production_batch_id
                  JOIN finished_goods_batches fgb ON fgb.factory_id = pb.factory_id
                   AND fgb.production_plan_id = pb.production_plan_id
                  JOIN sales_delivery_items sdi ON sdi.finished_goods_batch_id = fgb.id
                  JOIN sales_delivery_records sdr ON sdr.id = sdi.delivery_record_id
                  JOIN customers c ON c.id = sdr.customer_id
                 WHERE mb.id = %s AND mb.factory_id = %s AND pb.id = %s""",
            (ids["material_batch"], fid, production_id),
        )
        chain = cur.fetchone()
        if chain is None:
            raise SeedError(f"chain readback empty for {key}")
        cur.execute(
            """SELECT edge_type, source_type, source_id, target_type, target_id, quantity_used, unit
                 FROM batch_lineage_edges
                WHERE factory_id = %s AND id LIKE %s ORDER BY id""",
            (fid, f"{PREFIX}-{key}-%"),
        )
        edges = [dict(row) for row in cur.fetchall()]
        cur.execute(
            """SELECT ancestor_type, ancestor_id, descendant_type, descendant_id, depth
                 FROM batch_lineage_closure
                WHERE factory_id = %s
                  AND (ancestor_id IN (%s, %s, %s) OR descendant_id IN (%s, %s, %s))
                ORDER BY depth, ancestor_type, descendant_type""",
            (fid, ids["material_batch"], str(production_id), ids["finished_batch"], ids["material_batch"], str(production_id), ids["finished_batch"]),
        )
        closure = [dict(row) for row in cur.fetchall()]
        if len(edges) != 3 or not any(row["target_type"] == "SHIPMENT_RECORD" for row in edges):
            raise SeedError(f"lineage edge readback incomplete for {key}: {edges}")
        if not any(row["ancestor_id"] == ids["material_batch"] and row["descendant_id"] == ids["finished_batch"] for row in closure):
            raise SeedError(f"lineage closure missing raw -> finished path for {key}: {closure}")
        result["factories"][key] = {"chain": dict(chain), "edges": edges, "closure": closure}

    # Negative controls: the fixture IDs may only resolve inside their own
    # factory.  These are SQL readbacks, not application/API assertions.
    for key, ids in seeded.items():
        other = "B" if key == "A" else "A"
        other_fid = seeded[other]["factory_id"]
        cur.execute(
            """SELECT COUNT(*) AS n FROM batch_lineage_edges
                WHERE factory_id = %s AND id LIKE %s""",
            (other_fid, f"{PREFIX}-{key}-%"),
        )
        result["cross_factory_sentinels"][f"{key}_edges_in_{other}"] = int(cur.fetchone()["n"])
        cur.execute(
            """SELECT COUNT(*) AS n FROM production_batches
                WHERE factory_id = %s AND batch_number = %s""",
            (other_fid, ids["production_batch_number"]),
        )
        result["cross_factory_sentinels"][f"{key}_production_in_{other}"] = int(cur.fetchone()["n"])
    if any(value != 0 for value in result["cross_factory_sentinels"].values()):
        raise SeedError(f"cross-factory sentinel failed: {result['cross_factory_sentinels']}")
    return result


def json_safe(value: Any) -> Any:
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, dict):
        return {str(k): json_safe(v) for k, v in value.items()}
    if isinstance(value, list):
        return [json_safe(v) for v in value]
    return value


def write_evidence(payload: dict[str, Any]) -> None:
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
    path = EVIDENCE_DIR / "trace-seed-20261015.json"
    path.write_text(json.dumps(json_safe(payload), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"EVIDENCE={path}")


def main() -> int:
    started = now_utc()
    payload: dict[str, Any] = {
        "task_id": "DAILY-TRACE-SEED",
        "base_sha": "fbe3449f711658ca22cac72995923fc2840d03f0",
        "script": "scripts/e2e/daily-review/seed_trace.py",
        "db_guard": {"host": DB_HOST, "port": DB_PORT, "database": DB_NAME, "user": DB_USER},
        "sample_date": SAMPLE_DATE.isoformat(),
        "started_at": started,
    }
    conn = None
    try:
        conn = ensure_loopback_connection()
        with conn.cursor() as cur:
            required_schema_guard(cur)
            factory_and_user_guard(cur)
            assert_no_prefix_outside_scope(cur)
            payload["before_counts"] = target_counts(cur)
            payload["before_synthetic_counts"] = synthetic_counts(cur)
            # This file is written before the first INSERT so the baseline is
            # durable even if a later constraint blocks the transaction.
            payload["phase"] = "baseline_recorded_before_write"
        write_evidence(payload)

        with conn:
            with conn.cursor() as cur:
                seeded = {key: ensure_factory_rows(cur, key, spec) for key, spec in FACTORIES.items()}
                payload["inserted_ids"] = seeded
                payload["readback"] = readback(cur, seeded)
                payload["after_counts"] = target_counts(cur)
                payload["after_synthetic_counts"] = synthetic_counts(cur)
                payload["phase"] = "committed_readback"
        payload["finished_at"] = now_utc()
        write_evidence(payload)
        print(json.dumps(json_safe({"status": "ok", "inserted_ids": payload["inserted_ids"], "cross_factory_sentinels": payload["readback"]["cross_factory_sentinels"]}), ensure_ascii=False))
        return 0
    except Exception as exc:
        payload["phase"] = "failed_before_commit_or_readback"
        payload["error"] = f"{type(exc).__name__}: {exc}"
        payload["finished_at"] = now_utc()
        try:
            write_evidence(payload)
        except Exception as evidence_exc:  # pragma: no cover - secondary error
            print(f"EVIDENCE_WRITE_ERROR={evidence_exc}", file=sys.stderr)
        print(f"SEED_TRACE_FAILED: {exc}", file=sys.stderr)
        return 1
    finally:
        if conn is not None:
            conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
