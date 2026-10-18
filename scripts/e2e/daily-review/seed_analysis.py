#!/usr/bin/env python3
"""Seed the private daily-review dish-margin samples.

This script is deliberately a data-only helper.  It never creates or alters
tables, never touches users/passwords, and refuses any database other than the
owned loopback probe.  The calculation itself remains in the existing
``compute_dish_margins`` Gold path; the rows below only provide its inputs.

Use ``--check`` (the default) to inspect the target and dependencies.  Use
``--apply`` only after the required Silver/Gold tables have been provisioned
by their existing migrations.  A missing dependency is reported with the
source migration and exits before any write.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import OrderedDict
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from typing import Any, Iterable

import psycopg
from psycopg import sql


TASK_ID = "DAILY-ANALYSIS-SEED"
BASE_SHA = "fbe3449f711658ca22cac72995923fc2840d03f0"
HOST = "127.0.0.1"
PORT = 55447
DATABASE = "vision_entry_probe_20261015"
USER = "assembly_executor"
PASSWORD = ""
FACTORIES = ("ANALYSIS-REVIEW-A", "ANALYSIS-REVIEW-B")
TRACE_FACTORIES = ("VISION-ENTRY-A", "VISION-ENTRY-B")
FACTORY_TYPE = "RESTAURANT"
EVIDENCE_DIR = Path(
    r"C:\Users\Steve\Documents\cretas-connect-20261015\daily-review\analysis"
)

# These objects are the actual inputs read by the existing Gold function.  The
# two mapping tables are required here because this seed intentionally avoids
# creating product_types rows (which would require a real factory user actor).
REQUIRED_COLUMNS: OrderedDict[str, tuple[str, ...]] = OrderedDict(
    [
        ("dim_store", ("store_id", "factory_id", "name")),
        (
            "dim_product",
            ("product_id", "factory_id", "name", "normalized_name"),
        ),
        (
            "fact_pos_transaction",
            ("id", "factory_id", "source_type", "source_bill_no", "store_id", "date"),
        ),
        (
            "fact_pos_item",
            ("id", "transaction_id", "factory_id", "product_id", "qty", "amount"),
        ),
        (
            "dim_restaurant_cost_product",
            (
                "factory_id",
                "product_source_pk",
                "product_name",
                "normalized_name",
                "is_active",
            ),
        ),
        (
            "agg_restaurant_product_cost",
            ("factory_id", "product_id", "product_source_pk", "food_cost"),
        ),
    ]
)

MIGRATION_HINTS = {
    "dim_store": "backend/python/smartbi/database/migrations/2026_04_28_silver_dimensions.sql",
    "dim_product": "backend/python/smartbi/database/migrations/2026_04_28_silver_dimensions.sql",
    "fact_pos_transaction": "backend/python/smartbi/database/migrations/2026_04_29_silver_facts.sql",
    "fact_pos_item": "backend/python/smartbi/database/migrations/2026_04_29_silver_facts.sql",
    "dim_restaurant_cost_product": "backend/python/smartbi/database/migrations/V20261029_02__restaurant_cost_product_dimension.sql",
    "agg_restaurant_product_cost": (
        "backend/python/smartbi/database/migrations/2026_04_24_gold_restaurant_ops.sql"
        " + 2026_04_24_recipe_product_source_pk.sql"
    ),
}

INDICATORS = {
    "ANALYSIS-REVIEW-A": {
        "id": "analysis-review-a-dish-margin",
        "name": "分析复核 A 菜品毛利率",
        "computation_id": "analysis-review-a-dish-margin-python",
    },
    "ANALYSIS-REVIEW-B": {
        "id": "analysis-review-b-dish-margin",
        "name": "分析复核 B 菜品毛利率",
        "computation_id": "analysis-review-b-dish-margin-python",
    },
}

# These definitions bind to Java's existing JPA_AGGREGATE strategies.  They
# intentionally do not seed versions or values: Java must recompute from the
# trace-owned production_batches rows and write the version evidence itself.
FACTORY_INDICATORS = {
    "VISION-ENTRY-A": (
        {
            "id": "vision-entry-a-factory-yield-rate",
            "code": "FACTORY_LU_YIELD_RATE",
            "name": "视觉入口 A 良品率",
            "unit": "%",
            "display_order": 1,
            "strategy_file": "backend/java/cretas-api/src/main/java/com/cretas/aims/service/indicator/strategy/impl/FactoryLuYieldRateStrategy.java",
        },
        {
            "id": "vision-entry-a-factory-daily-output",
            "code": "FACTORY_LU_DAILY_OUTPUT",
            "name": "视觉入口 A 日均产量",
            "unit": "kg",
            "display_order": 2,
            "strategy_file": "backend/java/cretas-api/src/main/java/com/cretas/aims/service/indicator/strategy/impl/FactoryLuDailyOutputStrategy.java",
        },
    ),
    "VISION-ENTRY-B": (
        {
            "id": "vision-entry-b-factory-yield-rate",
            "code": "FACTORY_LU_YIELD_RATE",
            "name": "视觉入口 B 良品率",
            "unit": "%",
            "display_order": 1,
            "strategy_file": "backend/java/cretas-api/src/main/java/com/cretas/aims/service/indicator/strategy/impl/FactoryLuYieldRateStrategy.java",
        },
        {
            "id": "vision-entry-b-factory-daily-output",
            "code": "FACTORY_LU_DAILY_OUTPUT",
            "name": "视觉入口 B 日均产量",
            "unit": "kg",
            "display_order": 2,
            "strategy_file": "backend/java/cretas-api/src/main/java/com/cretas/aims/service/indicator/strategy/impl/FactoryLuDailyOutputStrategy.java",
        },
    ),
}

# The current month remains a strong AVAILABLE positive for A, so the actual
# analysis page has useful data on first open.  Controls are queried by exact
# dates; they are outside that default window and cannot dilute the positive.
SAMPLES = (
    {
        "factory": "ANALYSIS-REVIEW-A",
        "label": "known_positive",
        "date": "2026-10-01",
        "bill": "AR-A-POSITIVE-20261001",
        "dish": "分析复核正例菜",
        "normalized": "分析复核正例菜",
        "source_pk": "analysis-review-a-positive",
        "qty": Decimal("1"),
        "revenue": Decimal("100"),
        "food_cost": Decimal("25"),
        "expected_status": "AVAILABLE",
        "expected_value": Decimal("75"),
        "expected_reason": None,
    },
    {
        "factory": "ANALYSIS-REVIEW-A",
        "label": "true_zero",
        "date": "2026-09-01",
        "bill": "AR-A-ZERO-20260901",
        "dish": "分析复核零例菜",
        "normalized": "分析复核零例菜",
        "source_pk": "analysis-review-a-zero",
        "qty": Decimal("1"),
        "revenue": Decimal("100"),
        "food_cost": Decimal("100"),
        "expected_status": "AVAILABLE",
        "expected_value": Decimal("0"),
        "expected_reason": None,
    },
    {
        "factory": "ANALYSIS-REVIEW-A",
        "label": "no_data_no_measured_cost",
        "date": "2026-08-01",
        "bill": "AR-A-NODATA-20260801",
        "dish": "分析复核无成本菜",
        "normalized": "分析复核无成本菜",
        "source_pk": None,
        "qty": Decimal("1"),
        "revenue": Decimal("100"),
        "food_cost": None,
        "expected_status": "NO_DATA",
        "expected_value": None,
        "expected_reason": "NO_MEASURED_COST_SAMPLE",
    },
    {
        "factory": "ANALYSIS-REVIEW-B",
        "label": "foreign_true_zero",
        "date": "2026-10-01",
        "bill": "AR-B-ZERO-20261001",
        "dish": "分析复核 B 零例菜",
        "normalized": "分析复核 B 零例菜",
        "source_pk": "analysis-review-b-zero",
        "qty": Decimal("1"),
        "revenue": Decimal("80"),
        "food_cost": Decimal("80"),
        "expected_status": "AVAILABLE",
        "expected_value": Decimal("0"),
        "expected_reason": None,
    },
)


def _json_value(value: Any) -> Any:
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, dict):
        return {str(k): _json_value(v) for k, v in value.items()}
    if isinstance(value, (tuple, list)):
        return [_json_value(v) for v in value]
    return value


def _connect() -> psycopg.Connection:
    env = {
        "host": os.environ.get("ANALYSIS_REVIEW_PG_HOST", HOST),
        "port": int(os.environ.get("ANALYSIS_REVIEW_PG_PORT", str(PORT))),
        "dbname": os.environ.get("ANALYSIS_REVIEW_PG_DATABASE", DATABASE),
        "user": os.environ.get("ANALYSIS_REVIEW_PG_USER", USER),
        "password": os.environ.get("ANALYSIS_REVIEW_PG_PASSWORD", PASSWORD),
    }
    if env != {
        "host": HOST,
        "port": PORT,
        "dbname": DATABASE,
        "user": USER,
        "password": PASSWORD,
    }:
        raise RuntimeError(
            "analysis seed refuses non-owned database; expected "
            f"{HOST}:{PORT}/{DATABASE} user={USER} password=empty"
        )
    conn = psycopg.connect(
        host=HOST,
        port=PORT,
        dbname=DATABASE,
        user=USER,
        password=PASSWORD,
        connect_timeout=5,
    )
    with conn.cursor() as cur:
        cur.execute("SELECT current_database(), current_user")
        db, user = cur.fetchone()
        if db != DATABASE or user != USER:
            conn.close()
            raise RuntimeError(f"database identity guard failed: {db}/{user}")
    return conn


def _table_exists(cur: psycopg.Cursor[Any], table: str) -> bool:
    cur.execute("SELECT to_regclass(%s)", (f"public.{table}",))
    return cur.fetchone()[0] is not None


def _columns(cur: psycopg.Cursor[Any], table: str) -> set[str]:
    cur.execute(
        """
        SELECT column_name
          FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = %s
        """,
        (table,),
    )
    return {row[0] for row in cur.fetchall()}


def _dependency_report(cur: psycopg.Cursor[Any]) -> dict[str, Any]:
    missing: list[dict[str, Any]] = []
    for table, required in REQUIRED_COLUMNS.items():
        if not _table_exists(cur, table):
            missing.append(
                {
                    "table": f"public.{table}",
                    "missing": "table",
                    "migration": MIGRATION_HINTS[table],
                }
            )
            continue
        absent = sorted(set(required) - _columns(cur, table))
        if absent:
            missing.append(
                {
                    "table": f"public.{table}",
                    "missing": absent,
                    "migration": MIGRATION_HINTS[table],
                }
            )
    return {"missing": missing, "ready": not missing}


def _print_dependency_request(report: dict[str, Any]) -> None:
    if report["ready"]:
        return
    print("BLOCKED missing Gold/Silver dependencies; seed made no writes.")
    print("Exact dependency request (root decides and applies existing migrations):")
    for item in report["missing"]:
        missing = item["missing"]
        if isinstance(missing, list):
            missing = "columns " + ", ".join(missing)
        print(
            f"  - CREATE dependency public.{item['table'].split('.', 1)[1]} "
            f"({missing}); source={item['migration']}"
        )
    print(
        "  - Do not substitute hand-written/minimal DDL: preserve the migration "
        "constraints, FORCE RLS, and product_source_pk cost-key migration."
    )


def _set_factory(cur: psycopg.Cursor[Any], factory: str) -> None:
    if factory not in FACTORIES:
        raise RuntimeError(f"factory scope violation: {factory}")
    cur.execute("SELECT set_config('app.factory_id', %s, false)", (factory,))


def _counts(
    cur: psycopg.Cursor[Any], scoped_factories: Iterable[str] = FACTORIES
) -> dict[str, dict[str, int]]:
    scoped_factories = tuple(scoped_factories)
    names = [
        "factories",
        "indicators",
        "indicator_computations",
        "indicator_versions",
        "dim_store",
        "dim_product",
        "fact_pos_transaction",
        "fact_pos_item",
        "dim_restaurant_cost_product",
        "agg_restaurant_product_cost",
    ]
    result: dict[str, dict[str, int]] = {}
    for table in names:
        if not _table_exists(cur, table):
            continue
        result[table] = {}
        cols = _columns(cur, table)
        for factory in scoped_factories:
            if "factory_id" in cols:
                cur.execute(
                    sql.SQL("SELECT count(*) FROM public.{} WHERE factory_id = %s").format(
                        sql.Identifier(table)
                    ),
                    (factory,),
                )
                result[table][factory] = cur.fetchone()[0]
        if table == "factories":
            cur.execute(
                "SELECT count(*) FROM factories WHERE id = ANY(%s)",
                (list(scoped_factories),),
            )
            result[table]["target_ids"] = cur.fetchone()[0]
    return result


def _factory_rows(cur: psycopg.Cursor[Any]) -> None:
    for factory in FACTORIES:
        name = f"分析复核餐饮工厂 {factory[-1]}"
        cur.execute("SELECT id,name,type,is_active,deleted_at FROM factories WHERE id=%s", (factory,))
        row = cur.fetchone()
        if row is None:
            cur.execute(
                """
                INSERT INTO factories
                    (id, created_at, updated_at, ai_weekly_quota, is_active,
                     manually_verified, name, type, level)
                VALUES (%s, now(), now(), 1000, true, true, %s, %s, 0)
                """,
                (factory, name, FACTORY_TYPE),
            )
        elif row != (factory, name, FACTORY_TYPE, True, None):
            raise RuntimeError(f"existing factory {factory} differs; refusing overwrite: {row}")


def _indicator_rows(cur: psycopg.Cursor[Any]) -> None:
    for factory, spec in INDICATORS.items():
        cur.execute(
            """
            INSERT INTO indicators
                (id,factory_id,code,name,description,category,unit,is_active,
                 compute_strategy,cache_ttl_seconds,display_order,created_at,updated_at)
            VALUES (%s,%s,'RESTAURANT_DISH_MARGIN',%s,%s,'RESTAURANT',%s,true,
                    'CACHED',3600,%s,now(),now())
            ON CONFLICT (id) DO NOTHING
            """,
            (
                spec["id"],
                factory,
                spec["name"],
                "daily-review synthetic definition; source is existing Python Gold dish margin",
                "%",
                1 if factory.endswith("A") else 2,
            ),
        )
        cur.execute(
            """
            SELECT factory_id,code,name,category,unit,is_active,compute_strategy
              FROM indicators WHERE id=%s
            """,
            (spec["id"],),
        )
        expected = (factory, "RESTAURANT_DISH_MARGIN", spec["name"], "RESTAURANT", "%", True, "CACHED")
        if cur.fetchone() != expected:
            raise RuntimeError(f"indicator {spec['id']} exists with unexpected fields")
        cur.execute(
            """
            INSERT INTO indicator_computations
                (id,indicator_id,compute_type,compute_source,is_active,priority,created_at,updated_at)
            VALUES (%s,%s,'PYTHON_ENDPOINT','/api/smartbi/indicator/restaurant-dish-margin',true,1,now(),now())
            ON CONFLICT (id) DO NOTHING
            """,
            (spec["computation_id"], spec["id"]),
        )
        cur.execute(
            "SELECT indicator_id,compute_type,compute_source,is_active,priority FROM indicator_computations WHERE id=%s",
            (spec["computation_id"],),
        )
        if cur.fetchone() != (spec["id"], "PYTHON_ENDPOINT", "/api/smartbi/indicator/restaurant-dish-margin", True, 1):
            raise RuntimeError(f"indicator computation {spec['computation_id']} exists with unexpected fields")


def _factory_indicator_rows(cur: psycopg.Cursor[Any]) -> None:
    """Insert only metadata for the existing Java factory strategies."""
    for factory, specs in FACTORY_INDICATORS.items():
        for spec in specs:
            cur.execute(
                """
                INSERT INTO indicators
                    (id,factory_id,code,name,description,category,unit,is_active,
                     compute_strategy,cache_ttl_seconds,display_order,created_at,updated_at)
                VALUES (%s,%s,%s,%s,%s,'FACTORY',%s,true,'CACHED',3600,%s,now(),now())
                ON CONFLICT (id) DO NOTHING
                """,
                (
                    spec["id"],
                    factory,
                    spec["code"],
                    spec["name"],
                    "daily-review trace metadata; Java JPA aggregate strategy; no seeded value",
                    spec["unit"],
                    spec["display_order"],
                ),
            )
            cur.execute(
                """
                SELECT factory_id,code,name,category,unit,is_active,compute_strategy
                  FROM indicators WHERE id=%s
                """,
                (spec["id"],),
            )
            expected = (
                factory,
                spec["code"],
                spec["name"],
                "FACTORY",
                spec["unit"],
                True,
                "CACHED",
            )
            if cur.fetchone() != expected:
                raise RuntimeError(f"factory indicator {spec['id']} exists with unexpected fields")

            computation_id = f"{spec['id']}-jpa"
            cur.execute(
                """
                INSERT INTO indicator_computations
                    (id,indicator_id,compute_type,compute_source,is_active,priority,created_at,updated_at)
                VALUES (%s,%s,'JPA_AGGREGATE','',true,1,now(),now())
                ON CONFLICT (id) DO NOTHING
                """,
                (computation_id, spec["id"]),
            )
            cur.execute(
                "SELECT indicator_id,compute_type,compute_source,is_active,priority "
                "FROM indicator_computations WHERE id=%s",
                (computation_id,),
            )
            if cur.fetchone() != (spec["id"], "JPA_AGGREGATE", "", True, 1):
                raise RuntimeError(f"factory computation {computation_id} exists with unexpected fields")


def _metadata_dependency_report(cur: psycopg.Cursor[Any]) -> dict[str, Any]:
    missing: list[dict[str, Any]] = []
    for table, required in {
        "factories": ("id", "name", "type", "is_active"),
        "indicators": ("id", "factory_id", "code", "name", "category", "unit"),
        "indicator_computations": (
            "id", "indicator_id", "compute_type", "compute_source", "is_active", "priority"
        ),
    }.items():
        if not _table_exists(cur, table):
            missing.append({"table": f"public.{table}", "missing": "table"})
            continue
        absent = sorted(set(required) - _columns(cur, table))
        if absent:
            missing.append({"table": f"public.{table}", "missing": absent})
    return {"missing": missing, "ready": not missing}


def _trace_input_readback(cur: psycopg.Cursor[Any]) -> list[dict[str, Any]]:
    """Read trace-owner rows only; Java remains the value calculator."""
    batch_numbers = [
        "REVIEW-TRACE-A-PROD-BATCH-001",
        "REVIEW-TRACE-B-PROD-BATCH-001",
    ]
    cur.execute(
        """
        SELECT factory_id, count(*)::int, sum(good_quantity), sum(planned_quantity),
               sum(actual_quantity), count(DISTINCT start_time::date)::int
          FROM production_batches
         WHERE factory_id = ANY(%s)
           AND batch_number = ANY(%s)
           AND status = 'COMPLETED'
           AND deleted_at IS NULL
         GROUP BY factory_id ORDER BY factory_id
        """,
        (list(TRACE_FACTORIES), batch_numbers),
    )
    rows = []
    for row in cur.fetchall():
        rows.append(
            {
                "factory_id": row[0],
                "completed_batch_count": row[1],
                "sum_good_quantity": _json_value(row[2]),
                "sum_planned_quantity": _json_value(row[3]),
                "sum_actual_quantity": _json_value(row[4]),
                "production_days": row[5],
                "java_expected_yield_rate_percent": "100",
                "java_expected_daily_output_kg": "40",
            }
        )
    if {r["factory_id"] for r in rows} != set(TRACE_FACTORIES):
        raise RuntimeError(f"trace production readback missing A/B rows: {rows}")
    return rows


def _factory_metadata_readback(cur: psycopg.Cursor[Any]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for factory, specs in FACTORY_INDICATORS.items():
        for spec in specs:
            cur.execute(
                """
                SELECT i.id,i.factory_id,i.code,i.category,i.unit,i.compute_strategy,
                       c.id,c.compute_type,c.compute_source,c.is_active,c.priority
                  FROM indicators i
                  LEFT JOIN indicator_computations c ON c.indicator_id=i.id
                 WHERE i.id=%s ORDER BY c.priority,c.id
                """,
                (spec["id"],),
            )
            for row in cur.fetchall():
                rows.append(
                    {
                        "indicator_id": row[0],
                        "factory_id": row[1],
                        "code": row[2],
                        "category": row[3],
                        "unit": row[4],
                        "compute_strategy": row[5],
                        "computation_id": row[6],
                        "compute_type": row[7],
                        "compute_source": row[8],
                        "is_active": row[9],
                        "priority": row[10],
                        "java_source": spec["strategy_file"],
                        "expected_source_label": f"REAL_BUSINESS:{spec['code']}",
                    }
                )
    return rows


def _ensure_store(cur: psycopg.Cursor[Any], factory: str) -> int:
    _set_factory(cur, factory)
    name = f"分析复核门店 {factory[-1]}"
    cur.execute(
        "INSERT INTO dim_store (factory_id,name,city) VALUES (%s,%s,'上海') ON CONFLICT DO NOTHING",
        (factory, name),
    )
    cur.execute("SELECT store_id FROM dim_store WHERE factory_id=%s AND name=%s", (factory, name))
    row = cur.fetchone()
    if row is None:
        raise RuntimeError(f"failed to read back store for {factory}")
    return int(row[0])


def _ensure_products(cur: psycopg.Cursor[Any]) -> dict[tuple[str, str], int]:
    result: dict[tuple[str, str], int] = {}
    for sample in SAMPLES:
        key = (sample["factory"], sample["normalized"])
        if key in result:
            continue
        _set_factory(cur, sample["factory"])
        cur.execute(
            """
            INSERT INTO dim_product (factory_id,name,normalized_name,category)
            VALUES (%s,%s,%s,'分析复核') ON CONFLICT DO NOTHING
            """,
            (sample["factory"], sample["dish"], sample["normalized"]),
        )
        cur.execute(
            "SELECT product_id FROM dim_product WHERE factory_id=%s AND normalized_name=%s",
            key,
        )
        row = cur.fetchone()
        if row is None:
            raise RuntimeError(f"failed to read back product {key}")
        result[key] = int(row[0])
    return result


def _ensure_cost_rows(cur: psycopg.Cursor[Any], product_ids: dict[tuple[str, str], int]) -> None:
    for sample in SAMPLES:
        source_pk = sample["source_pk"]
        if not source_pk or sample["food_cost"] is None:
            continue
        factory = sample["factory"]
        _set_factory(cur, factory)
        pid = product_ids[(factory, sample["normalized"])]
        cur.execute(
            """
            INSERT INTO dim_restaurant_cost_product
                (factory_id,product_source_pk,product_name,normalized_name,source,is_active)
            VALUES (%s,%s,%s,%s,'daily_review_seed',true)
            ON CONFLICT DO NOTHING
            """,
            (factory, source_pk, sample["dish"], sample["normalized"]),
        )
        cost_columns = ["factory_id", "product_id", "product_source_pk", "food_cost"]
        columns = set(_columns(cur, "agg_restaurant_product_cost"))
        values: list[Any] = [factory, pid, source_pk, sample["food_cost"]]
        if "has_price_data" in columns:
            cost_columns.append("has_price_data")
            values.append(True)
        if "ingredient_count" in columns:
            cost_columns.append("ingredient_count")
            values.append(1)
        placeholders = ",".join(["%s"] * len(values))
        cur.execute(
            sql.SQL("INSERT INTO public.agg_restaurant_product_cost ({}) VALUES ({}) ON CONFLICT DO NOTHING").format(
                sql.SQL(",").join(map(sql.Identifier, cost_columns)),
                sql.SQL(",").join([sql.Placeholder()] * len(values)),
            ),
            values,
        )
        cur.execute(
            "SELECT food_cost FROM agg_restaurant_product_cost WHERE factory_id=%s AND product_source_pk=%s",
            (factory, source_pk),
        )
        row = cur.fetchone()
        if row is None or Decimal(str(row[0])) != sample["food_cost"]:
            raise RuntimeError(f"cost readback mismatch for {factory}/{source_pk}: {row}")


def _ensure_sales_rows(cur: psycopg.Cursor[Any], stores: dict[str, int], product_ids: dict[tuple[str, str], int]) -> dict[str, int]:
    tx_ids: dict[str, int] = {}
    for sample in SAMPLES:
        factory = sample["factory"]
        _set_factory(cur, factory)
        store_id = stores[factory]
        cur.execute(
            """
            INSERT INTO fact_pos_transaction
                (factory_id,source_type,source_bill_no,store_id,date,net_amount,gross_amount,actual_receive,item_count)
            VALUES (%s,'analysis_review',%s,%s,%s,%s,%s,%s,1)
            ON CONFLICT DO NOTHING
            """,
            (factory, sample["bill"], store_id, sample["date"], sample["revenue"], sample["revenue"], sample["revenue"]),
        )
        cur.execute(
            "SELECT id FROM fact_pos_transaction WHERE factory_id=%s AND source_type='analysis_review' AND source_bill_no=%s",
            (factory, sample["bill"]),
        )
        row = cur.fetchone()
        if row is None:
            raise RuntimeError(f"transaction readback failed for {sample['bill']}")
        tx_id = int(row[0])
        tx_ids[sample["bill"]] = tx_id
        product_id = product_ids[(factory, sample["normalized"])]
        item_columns = ["transaction_id", "factory_id", "product_id", "qty", "amount"]
        item_values: list[Any] = [tx_id, factory, product_id, sample["qty"], sample["revenue"]]
        if "unit_price" in _columns(cur, "fact_pos_item"):
            item_columns.append("unit_price")
            item_values.append(sample["revenue"] / sample["qty"])
        if "source_item_raw" in _columns(cur, "fact_pos_item"):
            item_columns.append("source_item_raw")
            item_values.append(sample["dish"])
        where = " AND ".join(
            [
                "transaction_id = %s",
                "factory_id = %s",
                "product_id = %s",
                "qty = %s",
                "amount = %s",
            ]
        )
        cur.execute(
            sql.SQL("INSERT INTO public.fact_pos_item ({}) SELECT {} WHERE NOT EXISTS (SELECT 1 FROM public.fact_pos_item WHERE {})").format(
                sql.SQL(",").join(map(sql.Identifier, item_columns)),
                sql.SQL(",").join([sql.Placeholder()] * len(item_values)),
                sql.SQL(where),
            ),
            item_values + item_values[:5],
        )
    return tx_ids


def _readback(cur: psycopg.Cursor[Any], product_ids: dict[tuple[str, str], int], tx_ids: dict[str, int]) -> dict[str, Any]:
    samples: list[dict[str, Any]] = []
    for sample in SAMPLES:
        factory = sample["factory"]
        _set_factory(cur, factory)
        pid = product_ids[(factory, sample["normalized"])]
        cur.execute(
            """
            SELECT t.id,t.factory_id,t.date,t.net_amount,i.product_id,i.qty,i.amount
              FROM fact_pos_transaction t JOIN fact_pos_item i ON i.transaction_id=t.id
             WHERE t.factory_id=%s AND t.source_bill_no=%s AND i.factory_id=%s
            """,
            (factory, sample["bill"], factory),
        )
        row = cur.fetchone()
        if row is None or int(row[4]) != pid:
            raise RuntimeError(f"sales readback mismatch for {factory}/{sample['bill']}: {row}")
        samples.append(
            {
                "factory": factory,
                "label": sample["label"],
                "date": sample["date"],
                "bill": sample["bill"],
                "transaction_id": int(row[0]),
                "product_id": int(row[4]),
                "revenue": str(row[3]),
                "qty": str(row[5]),
                "amount": str(row[6]),
                "expected_status": sample["expected_status"],
                "expected_value": _json_value(sample["expected_value"]),
                "expected_reason": sample["expected_reason"],
            }
        )
    return {"samples": samples, "tx_ids": tx_ids}


def _write_evidence(payload: dict[str, Any], path: Path | None) -> Path:
    target_dir = path or EVIDENCE_DIR
    target_dir.mkdir(parents=True, exist_ok=True)
    suffix = "factory-only" if payload.get("scope") == "factory-only" else "restaurant-gold"
    output = target_dir / f"seed-analysis-{suffix}.json"
    output.write_text(json.dumps(_json_value(payload), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="read-only dependency/count check (default)")
    mode.add_argument("--apply", action="store_true", help="apply additive deterministic seed rows")
    parser.add_argument(
        "--factory-only",
        action="store_true",
        help="only seed VISION-ENTRY-A/B Java indicator metadata and read trace inputs; skip Gold dependencies",
    )
    parser.add_argument("--evidence-dir", type=Path, default=None)
    args = parser.parse_args()
    apply = bool(args.apply)
    factory_only = bool(args.factory_only)
    scoped_factories = TRACE_FACTORIES if factory_only else FACTORIES
    report: dict[str, Any] = {
        "task_id": TASK_ID,
        "base_sha": BASE_SHA,
        "mode": "apply" if apply else "check",
        "scope": "factory-only" if factory_only else "restaurant-gold",
        "database_guard": {"host": HOST, "port": PORT, "database": DATABASE, "user": USER, "password": "empty"},
        "factories": list(scoped_factories),
        "factory_type": "existing FACTORY rows" if factory_only else FACTORY_TYPE,
        "started_at": datetime.now().isoformat(timespec="seconds"),
    }
    conn: psycopg.Connection[Any] | None = None
    try:
        conn = _connect()
        with conn.cursor() as cur:
            report["before_counts"] = _counts(cur, scoped_factories)
            dependencies = (
                _metadata_dependency_report(cur)
                if factory_only
                else _dependency_report(cur)
            )
            report["dependencies"] = dependencies
            if not dependencies["ready"]:
                _print_dependency_request(dependencies)
                report["status"] = "blocked_missing_dependencies"
                report["after_counts"] = report["before_counts"]
                output = _write_evidence(report, args.evidence_dir)
                print(f"evidence={output}")
                return 3
            if factory_only:
                report["trace_input_readback"] = _trace_input_readback(cur)
                report["metadata_readback"] = _factory_metadata_readback(cur)
            if not apply:
                print("PASS read-only target/dependency check; no writes requested.")
                report["status"] = "ready_no_write"
                report["after_counts"] = report["before_counts"]
                output = _write_evidence(report, args.evidence_dir)
                print(f"evidence={output}")
                return 0
            with conn.transaction():
                if factory_only:
                    _factory_indicator_rows(cur)
                    report["metadata_readback"] = _factory_metadata_readback(cur)
                else:
                    _factory_rows(cur)
                    _indicator_rows(cur)
                    stores = {factory: _ensure_store(cur, factory) for factory in FACTORIES}
                    product_ids = _ensure_products(cur)
                    _ensure_cost_rows(cur, product_ids)
                    tx_ids = _ensure_sales_rows(cur, stores, product_ids)
                    report["readback"] = _readback(cur, product_ids, tx_ids)
            report["status"] = "applied"
            report["after_counts"] = _counts(cur, scoped_factories)
        conn.commit()
    except Exception as exc:  # noqa: BLE001
        if conn is not None:
            conn.rollback()
        report["status"] = "error"
        report["error"] = f"{type(exc).__name__}: {exc}"
        output = _write_evidence(report, args.evidence_dir)
        print(f"ERROR {report['error']}; evidence={output}", file=sys.stderr)
        return 1
    finally:
        if conn is not None:
            conn.close()
    output = _write_evidence(report, args.evidence_dir)
    print(f"PASS status={report['status']} evidence={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
