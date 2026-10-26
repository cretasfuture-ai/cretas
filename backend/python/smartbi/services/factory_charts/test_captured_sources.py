from contextlib import asynccontextmanager
from decimal import Decimal
import json
from datetime import date

import pytest

from smartbi.services.factory_charts import captured_sources as sources


class Conn:
    def __init__(self, definitions, sales, department, oee, sheet_oee=None):
        self.definitions, self.sales, self.department, self.oee = definitions, sales, department, oee
        self.sheet_oee = sheet_oee or []
        self.calls = []

    async def execute(self, sql, *params):
        self.calls.append(("execute", sql, params))

    @asynccontextmanager
    async def transaction(self):
        yield self

    async def fetch(self, sql, *params):
        self.calls.append(("fetch", sql, params))
        if "canvas_dynamic_field" in sql:
            return self.definitions
        if "departments d" in sql:
            return self.department
        if "production_reports pr" in sql:
            return self.oee
        if "process_sheet_rows r" in sql:
            return self.sheet_oee
        return self.sales


class Pool:
    def __init__(self, conn):
        self.conn = conn

    @asynccontextmanager
    async def acquire(self):
        yield self.conn


class Request:
    period_type = "all"


def definition(code, column, semantic, field_type):
    return {"field_code": code, "column_name": column, "smartbi_semantic": semantic, "field_type": field_type}


@pytest.mark.asyncio
async def test_load_captures_only_versioned_channel_and_department_and_oee(monkeypatch):
    definitions = [
        definition("smartbi_sales_channel", "cf_smartbi_sales_channel", "sales_channel.v1", "TEXT"),
        definition("smartbi_department_id", "cf_smartbi_department_id", "department_id.v1", "SELECT"),
    ]
    sales = [{"订单ID": "o1", "日期": "2026-10-01", "渠道": "直营", "营业收入（元）": Decimal("12.50")}]
    departments = [{"订单ID": "o2", "日期": "2026-10-01", "部门ID": "d1", "部门": "生产", "营业收入（元）": Decimal("8")}]
    schema = [{"key": key, "enabled": True, "type": typ, "label": label} for key, typ, label in sources._REQUIRED_OEE_SCHEMA]
    oee = [{"报工ID": 1, "日期": "2026-10-01", "产品ID": "p1", "产品": "产品", "总数": 10,
            "良品数": 9, "单位": "kg", "计划运行分钟": "20", "实际运行分钟": "15",
            "理想周期秒": "60", "设备ID": "eq1", "设备主键": 1, "工序字段schema": schema}]
    conn = Conn(definitions, sales, departments, oee)
    async def pool(): return Pool(conn)
    monkeypatch.setattr(sources.config, "get_cretas_pool", pool)

    datasets, errors, gaps = await sources.load("F006", Request(), True)

    assert errors == []
    assert {d.source.split(" · ", 1)[0] for d in datasets} == {"sales-sales_channel", "sales-department_id", "oee"}
    assert all(d.currency == "CNY" for d in datasets if d.source.startswith("sales-"))
    assert list(next(d for d in datasets if d.source.startswith("oee")).frame.columns) == [
        "计划运行分钟", "实际运行分钟", "理想周期秒", "总数", "良品数", "单位", "日期", "产品", "报工ID", "设备ID"
    ]
    assert not gaps
    assert all(call[2][0] == "F006" for call in conn.calls if call[0] in ("execute", "fetch"))


@pytest.mark.asyncio
async def test_missing_or_ambiguous_field_definition_fails_closed(monkeypatch):
    conn = Conn([definition("smartbi_sales_channel", "wrong", "sales_channel.v1", "TEXT"),
                 definition("smartbi_sales_channel", "cf_smartbi_sales_channel", "sales_channel.v1", "TEXT")], [], [], [])
    async def pool(): return Pool(conn)
    monkeypatch.setattr(sources.config, "get_cretas_pool", pool)
    datasets, errors, gaps = await sources.load("F006", Request(), True)
    assert errors == []
    assert not any(d.source.startswith("sales-") for d in datasets)
    assert {g["id"] for g in gaps} >= {"sales.sales_channel", "sales.department_id"}


@pytest.mark.parametrize("row", [
    {"报工ID": 1, "日期": "2026-10-01", "总数": 0, "良品数": 0, "设备ID": "eq1", "设备主键": 1},
    {"报工ID": 1, "日期": "2026-10-01", "总数": 10, "良品数": 11, "设备ID": "eq1", "设备主键": 1},
    {"报工ID": 1, "日期": "2026-10-01", "总数": 10, "良品数": 9, "设备ID": "eq1", "设备主键": None},
])
def test_oee_validation_rejects_invalid_numbers_and_untrusted_equipment(row):
    schema = [{"key": key, "enabled": True, "type": typ, "label": label} for key, typ, label in sources._REQUIRED_OEE_SCHEMA]
    row.update({"计划运行分钟": 10, "实际运行分钟": 11, "理想周期秒": 1, "工序字段schema": schema})
    clean, reasons = sources._valid_oee([row], "F006")
    assert clean == []
    assert reasons


def test_oee_rejects_duplicate_observation_and_wrong_schema():
    base = {"报工ID": 1, "日期": "2026-10-01", "总数": 10, "良品数": 9, "设备ID": "eq1", "设备主键": 1,
            "计划运行分钟": 20, "实际运行分钟": 15, "理想周期秒": 60}
    schema = [{"key": key, "enabled": True, "type": typ, "label": label} for key, typ, label in sources._REQUIRED_OEE_SCHEMA]
    clean, reasons = sources._valid_oee([{**base, "工序字段schema": schema}, {**base, "报工ID": 2, "工序字段schema": schema}], "F006")
    assert clean == [] and any("重复观察" in reason for reason in reasons)
    clean, reasons = sources._valid_oee([{**base, "工序字段schema": []}], "F006")
    assert clean == [] and any("字段schema" in reason for reason in reasons)


def test_real_json_shape_and_yield_sql_contract_and_provenance():
    schema = json.dumps([{"key": key, "enabled": True, "type": typ, "label": label}
                         for key, typ, label in sources._REQUIRED_OEE_SCHEMA], ensure_ascii=False)
    row = {"报工ID": 9, "日期": "2026-10-01", "总数": 10, "良品数": 9, "单位": "kg",
           "设备ID": "eq1", "设备主键": 1, "计划运行分钟": "20", "实际运行分钟": "15",
           "理想周期秒": "60", "工序字段schema": schema}
    clean, gaps = sources._valid_oee([row], "F006")
    assert len(clean) == 1 and gaps == []
    assert "report_type='YIELD'" in sources.OEE_SQL
    assert "clerkCustomFields" in sources.OEE_SQL
    assert "pwp.process_order=pr.process_order" in sources.OEE_SQL


def test_sales_keeps_order_provenance_and_requires_field_type():
    rows, gaps = sources._sales_rows([{"订单ID": "o1", "日期": "2026-10-01", "渠道": "直营", "营业收入（元）": "3.5"}], "sales_channel", True)
    assert rows == [{"日期": "2026-10-01", "订单ID": "o1", "渠道": "直营", "实际（元）": Decimal("3.5")}]
    assert gaps == []


def test_oee_accepts_independent_equipment_windows_but_rejects_schema_key_mutation():
    schema = [{"key": key, "enabled": True, "type": typ, "label": label}
              for key, typ, label in sources._REQUIRED_OEE_SCHEMA]
    base = {"日期": "2026-10-01", "总数": 10, "良品数": 9, "单位": "kg", "设备主键": 1,
            "计划运行分钟": 20, "实际运行分钟": 15, "理想周期秒": 60, "工序字段schema": schema}
    clean, gaps = sources._valid_oee([{**base, "报工ID": 1, "设备ID": "eq1"},
                                      {**base, "报工ID": 2, "设备ID": "eq2"}], "F006")
    assert len(clean) == 2 and gaps == []
    duplicate_schema = schema + [dict(schema[0])]
    clean, gaps = sources._valid_oee([{**base, "报工ID": 3, "设备ID": "eq3", "工序字段schema": duplicate_schema}], "F006")
    assert clean == [] and any("字段schema" in reason for reason in gaps)


def test_oee_allows_zero_good_quantity_as_a_real_zero_result():
    schema = [{"key": key, "enabled": True, "type": typ, "label": label}
              for key, typ, label in sources._REQUIRED_OEE_SCHEMA]
    row = {"报工ID": 10, "日期": "2026-10-01", "总数": 10, "良品数": 0, "单位": "kg",
           "设备ID": "eq1", "设备主键": 1, "计划运行分钟": 20, "实际运行分钟": 15,
           "理想周期秒": 60, "工序字段schema": schema}
    clean, gaps = sources._valid_oee([row], "F006")
    assert len(clean) == 1 and gaps == [] and clean[0]["良品数"] == 0


@pytest.mark.asyncio
async def test_no_price_role_skips_sales_amount_fetch_and_dataset(monkeypatch):
    definitions = [definition("smartbi_sales_channel", "cf_smartbi_sales_channel", "sales_channel.v1", "TEXT")]
    conn = Conn(definitions, [{"订单ID": "forbidden", "营业收入（元）": 99}], [], [])
    async def pool(): return Pool(conn)
    monkeypatch.setattr(sources.config, "get_cretas_pool", pool)
    datasets, errors, gaps = await sources.load("F006", Request(), False)
    assert errors == []
    assert not any(d.source.startswith("sales-") for d in datasets)
    assert any(g["id"] == "sales.sales_channel.permission" for g in gaps)
    assert all("sales_orders so" not in call[1] for call in conn.calls if call[0] == "fetch")


def test_source_expression_mutants_are_pinned_by_contract_assertions():
    sql = sources.OEE_SQL
    assert "custom_fields->'clerkCustomFields'->>'smartbi_oee_planned_minutes'" in sql
    assert "custom_fields->>'smartbi_oee_planned_minutes'" not in sql
    assert "report_type='YIELD'" in sql
    assert "report_type IN ('PROGRESS','HOURS')" not in sql
    assert "pwp.process_order=pr.process_order" in sql


def _sheet_row(schema=None, **overrides):
    row = {
        "电子报工行ID": "row-1", "批次ID": "batch-1", "产品ID": "p1", "产品": "产品1",
        "日期文本": "2026-10-01", "总数文本": "10", "良品数文本": "0", "单位": "kg",
        "计划运行分钟": "20", "实际运行分钟": "15", "理想周期秒": "60", "设备ID": "eq-sheet",
        "设备主键": 8, "工序字段schema": schema or [
            {"key": key, "enabled": True, "type": typ, "label": label}
            for key, typ, label in sources._REQUIRED_SHEET_OEE_SCHEMA
        ],
        "多产出成员": None, "多产出组ID": None,
    }
    row.update(overrides)
    return row


def test_sheet_oee_uses_explicit_good_quantity_and_real_row_provenance():
    clean, gaps = sources._sheet_oee_rows([_sheet_row()], "F006")
    assert gaps == []
    assert len(clean) == 1 and clean[0]["报工ID"] == "sheet-row:row-1"
    assert clean[0]["良品数"] == 0 and clean[0]["总数"] == 10
    assert "process_sheet_rows r" in sources.SHEET_OEE_SQL
    assert "production_batches pb" in sources.SHEET_OEE_SQL
    assert "pb.production_plan_id=r.plan_id" in sources.SHEET_OEE_SQL
    assert "pp.product_type_id=r.row_payload->>'productTypeId'" in sources.SHEET_OEE_SQL
    assert "r.row_payload->'customFields'->>'smartbi_oee_good_quantity'" in sources.SHEET_OEE_SQL


@pytest.mark.parametrize("bad", [
    {"电子报工行ID": None},
    {"批次ID": None},
    {"产品ID": None},
    {"日期文本": "not-a-date"},
    {"多产出成员": "true"},
    {"多产出组ID": "group-1"},
    {"设备主键": None},
])
def test_sheet_oee_quarantines_untrusted_rows(bad):
    clean, gaps = sources._sheet_oee_rows([_sheet_row(**bad)], "F006")
    assert clean == [] and gaps


def test_sheet_oee_requires_fifth_schema_and_period_filter():
    old_schema = [{"key": key, "enabled": True, "type": typ, "label": label}
                  for key, typ, label in sources._REQUIRED_OEE_SCHEMA]
    clean, gaps = sources._sheet_oee_rows([_sheet_row(schema=old_schema)], "F006")
    assert clean == [] and any("schema" in gap for gap in gaps)
    clean, gaps = sources._sheet_oee_rows([_sheet_row()], "F006", date(2026, 10, 2), date(2026, 10, 31))
    assert clean == [] and gaps == []


@pytest.mark.asyncio
async def test_sheet_native_overlap_removes_both_sources(monkeypatch):
    native_schema = [{"key": key, "enabled": True, "type": typ, "label": label}
                     for key, typ, label in sources._REQUIRED_OEE_SCHEMA]
    native = [{"报工ID": 1, "日期": date(2026, 10, 1), "产品ID": "p1", "产品": "产品1", "总数": 10,
               "良品数": 9, "单位": "kg", "计划运行分钟": 20, "实际运行分钟": 15,
               "理想周期秒": 60, "设备ID": "eq-sheet", "设备主键": 1, "工序字段schema": native_schema}]
    conn = Conn([], [], [], native, [_sheet_row()])
    async def pool(): return Pool(conn)
    monkeypatch.setattr(sources.config, "get_cretas_pool", pool)
    datasets, errors, gaps = await sources.load("F006", Request(), True)
    assert errors == [] and not any(d.source.startswith(("oee ·", "oee-sheet")) for d in datasets)
    assert any("重复观察窗口" in str(g) for g in gaps)
