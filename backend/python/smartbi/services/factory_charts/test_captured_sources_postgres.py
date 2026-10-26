"""Opt-in real PostgreSQL coverage for the captured factory sources.

Only ``127.0.0.1:55439`` is accepted.  The test creates and drops a unique
schema, so it never writes to a shared or production database.
"""
from __future__ import annotations

import json
import os
import uuid
from pathlib import Path
from urllib.parse import urlparse

import pytest

from smartbi.api.financial_dashboard import FactoryRecommendationRequest
from smartbi.services.factory_charts import captured_sources, engine


def _url() -> str | None:
    value = os.environ.get("FACTORY_SELECTION_TEST_PG_URL")
    if not value:
        return None
    parsed = urlparse(value)
    if parsed.hostname not in {"127.0.0.1", "localhost"} or parsed.port != 55439:
        raise AssertionError("FACTORY_SELECTION_TEST_PG_URL must point to owned localhost:55439")
    return value


def _schema_sql(schema: str) -> str:
    return f"""
CREATE SCHEMA {schema};
SET search_path TO {schema};
CREATE TABLE factories (id TEXT PRIMARY KEY);
CREATE TABLE sales_orders (
  id TEXT PRIMARY KEY, factory_id TEXT NOT NULL, order_date DATE NOT NULL,
  status TEXT NOT NULL, deleted_at TIMESTAMPTZ, total_amount NUMERIC(15,2)
);
CREATE TABLE canvas_dynamic_field (
  id TEXT PRIMARY KEY DEFAULT md5(random()::text || clock_timestamp()::text), factory_id TEXT, module_code TEXT NOT NULL,
  field_code TEXT NOT NULL, field_type TEXT NOT NULL, label TEXT NOT NULL,
  config JSONB, sort_order INT, status TEXT, column_name TEXT,
  UNIQUE(factory_id,module_code,field_code)
);
CREATE TABLE departments (
  id INT PRIMARY KEY, factory_id TEXT NOT NULL, name TEXT NOT NULL,
  is_active BOOLEAN NOT NULL, deleted_at TIMESTAMPTZ
);
CREATE TABLE product_work_processes (
  id BIGSERIAL PRIMARY KEY, factory_id TEXT NOT NULL, product_type_id TEXT NOT NULL,
  work_process_id TEXT NOT NULL, process_order INT NOT NULL, is_active BOOLEAN NOT NULL
);
CREATE TABLE work_processes (
  id TEXT PRIMARY KEY, factory_id TEXT NOT NULL, is_active BOOLEAN NOT NULL,
  deleted_at TIMESTAMPTZ, custom_field_schema JSONB
);
CREATE TABLE factory_equipment (
  id BIGSERIAL PRIMARY KEY, factory_id TEXT NOT NULL, status TEXT NOT NULL,
  deleted_at TIMESTAMPTZ
);
CREATE TABLE production_reports (
  id BIGSERIAL PRIMARY KEY, factory_id TEXT NOT NULL, report_date DATE NOT NULL,
  report_type TEXT NOT NULL, status TEXT NOT NULL, product_type_id TEXT,
  product_name TEXT, process_order INT, output_quantity NUMERIC,
  good_quantity NUMERIC, output_unit TEXT, custom_fields JSONB
);
CREATE TABLE production_plans (
  id TEXT PRIMARY KEY, factory_id TEXT NOT NULL, product_type_id TEXT,
  status TEXT, deleted_at TIMESTAMPTZ
);
CREATE TABLE production_batches (
  id TEXT PRIMARY KEY, factory_id TEXT NOT NULL, production_plan_id TEXT,
  product_type_id TEXT NOT NULL, status TEXT NOT NULL, deleted_at TIMESTAMPTZ
);
CREATE TABLE product_types (
  id TEXT PRIMARY KEY, factory_id TEXT NOT NULL, name TEXT NOT NULL,
  is_active BOOLEAN NOT NULL, deleted_at TIMESTAMPTZ
);
CREATE TABLE process_sheet_rows (
  id BIGSERIAL PRIMARY KEY, factory_id TEXT NOT NULL, plan_id TEXT NOT NULL,
  process_order INT NOT NULL, batch_id TEXT NOT NULL, client_row_id TEXT,
  row_payload JSONB NOT NULL, submission_status TEXT NOT NULL, deleted_at TIMESTAMPTZ
);
"""


@pytest.mark.asyncio
async def test_captured_sources_real_pg_migration_load_engine_and_negative_guards(monkeypatch, tmp_path):
    url = _url()
    if not url:
        pytest.skip("disposable local PostgreSQL not supplied")
    import asyncpg

    conn = await asyncpg.connect(url)
    schema = "captured_pg_" + uuid.uuid4().hex
    pool = None
    reader_role = "reader_" + uuid.uuid4().hex[:12]
    try:
        await conn.execute(_schema_sql(schema))
        await conn.execute(f"INSERT INTO factories VALUES ('F006'),('F001')")
        await conn.execute("INSERT INTO departments VALUES (1,'F006','生产',true,NULL),(2,'F001','外部',true,NULL),(3,'F006','停用',false,NULL),(4,'F006','删除',true,now())")
        await conn.execute("INSERT INTO canvas_dynamic_field(id,factory_id,module_code,field_code,field_type,label,config,sort_order,status,column_name) VALUES ('keep','F006','sales_order','smartbi_sales_channel','TEXT','KEEP','{\"smartbiSemantic\":\"sales_channel.v1\"}',80,'ACTIVE','cf_smartbi_sales_channel')")
        await conn.execute("INSERT INTO sales_orders(id,factory_id,order_date,status,deleted_at,total_amount) VALUES ('before-migration','F006','2026-09-01','CONFIRMED',NULL,7)")
        migration = Path(__file__).resolve().parents[5] / "backend/java/cretas-api/src/main/resources/db/flyway/V20261103_83__smartbi_explicit_order_dimensions.sql"
        migration_sql = migration.read_text(encoding="utf-8")
        await conn.execute(migration_sql)
        await conn.execute(migration_sql)
        assert await conn.fetchval("SELECT count(*) FROM canvas_dynamic_field WHERE factory_id='F006'") == 2
        assert await conn.fetchval("SELECT count(*) FROM canvas_dynamic_field WHERE factory_id='F001'") == 0
        assert await conn.fetchval("SELECT label FROM canvas_dynamic_field WHERE factory_id='F006' AND field_code='smartbi_sales_channel'") == "KEEP"
        assert await conn.fetchval("SELECT is_nullable FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='sales_orders' AND column_name='cf_smartbi_sales_channel'") == "YES"
        assert await conn.fetchval("SELECT total_amount FROM sales_orders WHERE id='before-migration'") == 7
        options = await conn.fetchval("SELECT config->'options' FROM canvas_dynamic_field WHERE factory_id='F006' AND field_code='smartbi_department_id'")
        options = json.loads(options) if isinstance(options, str) else options
        assert options == [{"value": "1", "label": "生产"}]

        assert await conn.fetchval("SELECT label FROM canvas_dynamic_field WHERE id='keep'") == "KEEP"  # existing row is never overwritten
        await conn.execute("INSERT INTO sales_orders VALUES ('o1','F006','2026-10-01','CONFIRMED',NULL,100,'直营','1'),('o2','F001','2026-10-01','CONFIRMED',NULL,999,'外部',NULL)")
        sheet_schema = captured_sources._REQUIRED_SHEET_OEE_SCHEMA
        await conn.execute("INSERT INTO work_processes VALUES ('wp1','F006',true,NULL,$1::jsonb)", json.dumps([{"key": key, "enabled": True, "type": typ, "label": label} for key, typ, label in sheet_schema], ensure_ascii=False))
        await conn.execute("INSERT INTO work_processes VALUES ('wp2','F006',true,NULL,'[]'::jsonb)")
        await conn.execute("INSERT INTO product_work_processes(factory_id,product_type_id,work_process_id,process_order,is_active) VALUES ('F006','p1','wp1',2,true),('F006','p1','wp2',3,true)")
        eq1 = await conn.fetchval("INSERT INTO factory_equipment(factory_id,status) VALUES ('F006','idle') RETURNING id")
        eq3 = await conn.fetchval("INSERT INTO factory_equipment(factory_id,status) VALUES ('F006','idle') RETURNING id")
        eq2 = await conn.fetchval("INSERT INTO factory_equipment(factory_id,status) VALUES ('F001','idle') RETURNING id")
        fields = {"smartbi_oee_planned_minutes": 100, "smartbi_oee_running_minutes": 90, "smartbi_oee_ideal_cycle_seconds": 48, "smartbi_oee_equipment_id": str(eq1)}
        await conn.execute("INSERT INTO production_reports(factory_id,report_date,report_type,status,product_type_id,product_name,process_order,output_quantity,good_quantity,output_unit,custom_fields) VALUES ('F006','2026-10-01','YIELD','SUBMITTED','p1','产品',2,90,85.5,'kg',$1::jsonb)", json.dumps({"clerkCustomFields": fields}))
        zero = dict(fields, smartbi_oee_equipment_id=str(eq3))
        await conn.execute("INSERT INTO production_reports(factory_id,report_date,report_type,status,product_type_id,product_name,process_order,output_quantity,good_quantity,output_unit,custom_fields) VALUES ('F006','2026-10-02','YIELD','SUBMITTED','p1','产品',2,10,0,'kg',$1::jsonb)", json.dumps({"clerkCustomFields": zero}))
        # Negative source rows: legacy PROGRESS, wrong process order/schema, top-level JSON, foreign equipment, and out-of-period date.
        await conn.execute("INSERT INTO production_reports(factory_id,report_date,report_type,status,product_type_id,product_name,process_order,output_quantity,good_quantity,output_unit,custom_fields) VALUES ('F006','2026-10-03','PROGRESS','SUBMITTED','p1','产品',2,10,9,'kg',$1::jsonb)", json.dumps({"clerkCustomFields": fields}))
        await conn.execute("INSERT INTO production_reports(factory_id,report_date,report_type,status,product_type_id,product_name,process_order,output_quantity,good_quantity,output_unit,custom_fields) VALUES ('F006','2026-10-04','YIELD','SUBMITTED','p1','产品',9,10,9,'kg',$1::jsonb)", json.dumps({"clerkCustomFields": fields}))
        await conn.execute("INSERT INTO production_reports(factory_id,report_date,report_type,status,product_type_id,product_name,process_order,output_quantity,good_quantity,output_unit,custom_fields) VALUES ('F006','2026-10-05','YIELD','SUBMITTED','p1','产品',3,10,9,'kg',$1::jsonb)", json.dumps({"clerkCustomFields": fields}))
        await conn.execute("INSERT INTO production_reports(factory_id,report_date,report_type,status,product_type_id,product_name,process_order,output_quantity,good_quantity,output_unit,custom_fields) VALUES ('F006','2026-10-06','YIELD','SUBMITTED','p1','产品',2,10,9,'kg',$1::jsonb)", json.dumps(fields))
        await conn.execute("INSERT INTO production_reports(factory_id,report_date,report_type,status,product_type_id,product_name,process_order,output_quantity,good_quantity,output_unit,custom_fields) VALUES ('F006','2026-10-07','YIELD','SUBMITTED','p1','产品',2,10,9,'kg',$1::jsonb)", json.dumps({"clerkCustomFields": dict(fields, smartbi_oee_equipment_id=str(eq2))}))
        await conn.execute("INSERT INTO production_reports(factory_id,report_date,report_type,status,product_type_id,product_name,process_order,output_quantity,good_quantity,output_unit,custom_fields) VALUES ('F006','2025-01-01','YIELD','SUBMITTED','p1','产品',2,10,9,'kg',$1::jsonb)", json.dumps({"clerkCustomFields": fields}))
        await conn.execute("INSERT INTO product_types VALUES ('p1','F006','产品',true,NULL)")
        await conn.execute("INSERT INTO product_types VALUES ('p2','F006','错误产品',true,NULL)")
        await conn.execute("INSERT INTO production_plans VALUES ('plan-sheet','F006','p1','ACTIVE',NULL)")
        await conn.execute("INSERT INTO production_batches VALUES ('batch-sheet','F006','plan-sheet','p1','ACTIVE',NULL)")
        sheet_payload = {
            "processDate": "2026-10-03", "productTypeId": "p1", "productName": "产品",
            "outputQuantity": 20, "outputUnit": "kg", "customFields": {
                "smartbi_oee_planned_minutes": 30, "smartbi_oee_running_minutes": 20,
                "smartbi_oee_ideal_cycle_seconds": 30, "smartbi_oee_equipment_id": str(eq3),
                "smartbi_oee_good_quantity": 0,
            }
        }
        await conn.execute("INSERT INTO process_sheet_rows(factory_id,plan_id,process_order,batch_id,client_row_id,row_payload,submission_status,deleted_at) VALUES ('F006','plan-sheet',2,'batch-sheet','client-sheet',$1::jsonb,'SUBMITTED',NULL)", json.dumps(sheet_payload))
        bad_sheet = dict(sheet_payload, processDate="not-a-date")
        await conn.execute("INSERT INTO process_sheet_rows(factory_id,plan_id,process_order,batch_id,client_row_id,row_payload,submission_status,deleted_at) VALUES ('F006','plan-sheet',2,'batch-sheet','client-bad',$1::jsonb,'SUBMITTED',NULL)", json.dumps(bad_sheet))
        for plan_id, batch_id, status, deleted_at, batch_product in [
            ("plan-cancel", "batch-cancel", "CANCELLED", "NULL", "p1"),
            ("plan-deleted", "batch-deleted", "ACTIVE", "now()", "p1"),
            ("plan-mismatch", "batch-mismatch", "ACTIVE", "NULL", "p2"),
        ]:
            await conn.execute(f"INSERT INTO production_plans VALUES ('{plan_id}','F006','p1','{status}',{deleted_at})")
            await conn.execute(f"INSERT INTO production_batches VALUES ('{batch_id}','F006','{plan_id}','{batch_product}','ACTIVE',{deleted_at})")
            await conn.execute("INSERT INTO process_sheet_rows(factory_id,plan_id,process_order,batch_id,client_row_id,row_payload,submission_status,deleted_at) VALUES ('F006',$1,2,$2,$3,$4::jsonb,'SUBMITTED',NULL)", plan_id, batch_id, f"negative-{plan_id}", json.dumps(sheet_payload))
        history_payload = dict(sheet_payload, processDate="2025-01-01")
        await conn.executemany("INSERT INTO process_sheet_rows(factory_id,plan_id,process_order,batch_id,client_row_id,row_payload,submission_status,deleted_at) VALUES ('F006','plan-sheet',2,'batch-sheet',$1,$2::jsonb,'SUBMITTED',NULL)", [(f"history-{i}", json.dumps(history_payload)) for i in range(10001)])
        malformed = [dict(sheet_payload, processDate=value) for value in ("2026-02-31", "2026-13-01", "", "NaT")]
        await conn.executemany("INSERT INTO process_sheet_rows(factory_id,plan_id,process_order,batch_id,client_row_id,row_payload,submission_status,deleted_at) VALUES ('F006','plan-sheet',2,'batch-sheet',$1,$2::jsonb,'SUBMITTED',NULL)", [(f"bad-date-{i}", json.dumps(payload)) for i, payload in enumerate(malformed)])
        await conn.executemany("INSERT INTO process_sheet_rows(factory_id,plan_id,process_order,batch_id,client_row_id,row_payload,submission_status,deleted_at) VALUES ('F006','plan-sheet',2,'batch-sheet',$1,$2::jsonb,'SUBMITTED',NULL)", [(f"bad-history-{i}", json.dumps(dict(sheet_payload, processDate="2025-02-31"))) for i in range(10001)])

        await conn.execute(f"CREATE ROLE {reader_role} NOLOGIN")
        await conn.execute(f"GRANT USAGE ON SCHEMA {schema} TO {reader_role}")
        await conn.execute(f"GRANT SELECT ON ALL TABLES IN SCHEMA {schema} TO {reader_role}")
        async def setup_reader(connection):
            await connection.execute(f"SET ROLE {reader_role}")
        pool = await asyncpg.create_pool(url, min_size=1, max_size=1, server_settings={"search_path": schema}, setup=setup_reader)
        async def get_pool(): return pool
        monkeypatch.setattr(captured_sources.config, "get_cretas_pool", get_pool)
        request = FactoryRecommendationRequest(period_type="month_range", year=2026, start_month=10, end_month=10, use_ai=False)
        datasets, errors, gaps = await captured_sources.load("F006", request, True)
        assert errors == []
        assert {d.source.split(" · ", 1)[0] for d in datasets} == {"sales-sales_channel", "sales-department_id", "oee", "oee-sheet"}
        channel = next(d for d in datasets if d.source.startswith("sales-sales_channel"))
        assert channel.frame.iloc[0]["订单ID"] == "o1" and channel.frame.iloc[0]["实际（元）"] == 100
        channel_candidates, channel_gaps = engine.build([channel], request, True)
        assert any(c["id"].endswith("finance.channel_analysis") for c in channel_candidates), channel_gaps
        department = next(d for d in datasets if d.source.startswith("sales-department_id"))
        department_candidates, department_gaps = engine.build([department], request, True)
        assert any(c["id"].endswith("finance.business_unit_analysis") for c in department_candidates), department_gaps
        assert any(d.source.startswith("oee") for d in datasets), ([(d.source, d.frame.to_dict("records")) for d in datasets], errors, gaps)
        native = [d for d in datasets if d.source.startswith("oee ·")]
        assert native and {int(v) for d in native for v in d.frame["报工ID"]} == {1, 2}
        assert any("schema" in str(g).lower() for g in gaps)
        oee = next(d for d in native if len(d.frame) == 1)
        assert oee.frame.iloc[0]["良品数"] == 85.5
        charts, _ = engine.build([oee], request, True)
        assert any(any(k.get("label") == "OEE" and abs(float(k["value"]) - 68.4) < 0.01 for k in c.get("kpis", [])) for c in charts)
        assert any(d.source.startswith("oee") and (d.frame["良品数"] == 0).any() for d in datasets)
        sheet = next(d for d in datasets if d.source.startswith("oee-sheet"))
        assert len(sheet.frame) == 1 and sheet.frame.iloc[0]["报工ID"].endswith(":1")
        sheet_charts, sheet_gaps = engine.build([sheet], request, True)
        assert any(any(k.get("label") == "OEE" for k in c.get("kpis", [])) for c in sheet_charts), sheet_gaps

        denied, denied_errors, denied_gaps = await captured_sources.load("F006", request, False)
        assert denied_errors == [] and not any(d.source.startswith("sales-") for d in denied)
        assert any(g["id"] == "sales.sales_channel.permission" for g in denied_gaps)
        assert await conn.fetchval("SELECT count(*) FROM sales_orders WHERE factory_id='F001'") == 1
    finally:
        if pool is not None:
            await pool.close()
        await conn.execute(f"DROP SCHEMA IF EXISTS {schema} CASCADE")
        await conn.execute(f"DROP ROLE IF EXISTS {reader_role}")
        await conn.close()
