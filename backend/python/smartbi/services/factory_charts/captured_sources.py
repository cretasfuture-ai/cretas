"""Captured, schema-gated factory sources for SmartBI.

The adapters in this file only expose values that are explicitly configured in
the Java application.  Missing definitions, ambiguous definitions, invalid
values, and unverifiable joins become gaps; no value is guessed or allocated.
"""
from __future__ import annotations

import asyncio
import calendar
import json
from datetime import date
from decimal import Decimal, InvalidOperation
from typing import Any, Iterable

import pandas as pd

import smartbi.config as config
from .engine import Dataset

_EXCLUDED_ORDER_STATUSES = ("DRAFT", "CANCELLED", "FINANCE_REJECTED")
_REQUIRED_OEE_SCHEMA = (
    ("smartbi_oee_planned_minutes", "number", "计划运行分钟"),
    ("smartbi_oee_running_minutes", "number", "实际运行分钟"),
    ("smartbi_oee_ideal_cycle_seconds", "number", "理想周期秒"),
    ("smartbi_oee_equipment_id", "text", "OEE设备ID"),
)
_REQUIRED_SHEET_OEE_SCHEMA = _REQUIRED_OEE_SCHEMA + (("smartbi_oee_good_quantity", "number", "OEE良品数"),)

SALES_FIELD_SQL = """
SELECT field_code, column_name, field_type, config->>'smartbiSemantic' AS smartbi_semantic
FROM canvas_dynamic_field
WHERE factory_id=$1 AND module_code='sales_order' AND status='ACTIVE'
  AND field_code IN ('smartbi_sales_channel','smartbi_department_id')
  AND ((field_code='smartbi_sales_channel' AND field_type='TEXT')
    OR (field_code='smartbi_department_id' AND field_type='SELECT'))
ORDER BY field_code, id
"""

SALES_CAPTURE_SQL = """
SELECT so.id AS "订单ID", so.order_date AS "日期",
       CASE WHEN $4 = 'sales_channel' THEN to_jsonb(so)->>'cf_smartbi_sales_channel'
            ELSE NULL END AS "渠道",
       CASE WHEN $4 = 'department_id' THEN to_jsonb(so)->>'cf_smartbi_department_id'
            ELSE NULL END AS "部门ID",
       so.total_amount AS "营业收入（元）"
FROM sales_orders so
WHERE so.factory_id=$1 AND so.deleted_at IS NULL
  AND so.status NOT IN ('DRAFT','CANCELLED','FINANCE_REJECTED')
  AND ($2::date IS NULL OR so.order_date >= $2::date)
  AND ($3::date IS NULL OR so.order_date < ($3::date + INTERVAL '1 day'))
ORDER BY so.order_date, so.id
LIMIT 10001
"""

SALES_DEPARTMENT_CAPTURE_SQL = """
SELECT so.id AS "订单ID", so.order_date AS "日期",
       to_jsonb(so)->>'cf_smartbi_department_id' AS "部门ID",
       d.name AS "部门", so.total_amount AS "营业收入（元）"
FROM sales_orders so
JOIN departments d ON d.id::text=to_jsonb(so)->>'cf_smartbi_department_id'
 AND d.factory_id=so.factory_id AND d.is_active=true AND d.deleted_at IS NULL
WHERE so.factory_id=$1 AND so.deleted_at IS NULL
  AND so.status NOT IN ('DRAFT','CANCELLED','FINANCE_REJECTED')
  AND ($2::date IS NULL OR so.order_date >= $2::date)
  AND ($3::date IS NULL OR so.order_date < ($3::date + INTERVAL '1 day'))
ORDER BY so.order_date, so.id
LIMIT 10001
"""

OEE_SQL = """
SELECT pr.id AS "报工ID", pr.report_date AS "日期", pr.product_type_id AS "产品ID",
       pr.product_name AS "产品", pr.output_quantity AS "总数", pr.good_quantity AS "良品数",
       pr.output_unit AS "单位", pr.custom_fields->'clerkCustomFields'->>'smartbi_oee_planned_minutes' AS "计划运行分钟",
       pr.custom_fields->'clerkCustomFields'->>'smartbi_oee_running_minutes' AS "实际运行分钟",
       pr.custom_fields->'clerkCustomFields'->>'smartbi_oee_ideal_cycle_seconds' AS "理想周期秒",
       pr.custom_fields->'clerkCustomFields'->>'smartbi_oee_equipment_id' AS "设备ID",
       pwp.process_order AS "工序序", wp.custom_field_schema AS "工序字段schema",
       fe.id AS "设备主键"
FROM production_reports pr
JOIN product_work_processes pwp ON pwp.factory_id=pr.factory_id
 AND pwp.product_type_id=pr.product_type_id AND pwp.process_order=pr.process_order AND pwp.is_active=true
JOIN work_processes wp ON wp.id=pwp.work_process_id AND wp.factory_id=pr.factory_id
 AND wp.is_active=true AND wp.deleted_at IS NULL
JOIN factory_equipment fe ON fe.factory_id=pr.factory_id
 AND fe.id::text=pr.custom_fields->'clerkCustomFields'->>'smartbi_oee_equipment_id'
 AND fe.status <> 'scrapped' AND fe.deleted_at IS NULL
WHERE pr.factory_id=$1 AND pr.report_type='YIELD'
  AND pr.status IN ('SUBMITTED','APPROVED')
  AND pr.report_date >= COALESCE($2::date, '-infinity'::date)
  AND pr.report_date <= COALESCE($3::date, 'infinity'::date)
ORDER BY pr.report_date, pr.id
LIMIT 10001
"""

SHEET_OEE_SQL = """
WITH sheet_rows AS (
    SELECT r.*,
           r.row_payload->>'processDate' AS process_date_text,
           CASE
             WHEN r.row_payload->>'processDate' ~ '^\\d{4}-\\d{2}-\\d{2}$'
              AND substring(r.row_payload->>'processDate' FROM 6 FOR 2) BETWEEN '01' AND '12'
              AND (
                (substring(r.row_payload->>'processDate' FROM 6 FOR 2) IN ('01','03','05','07','08','10','12') AND substring(r.row_payload->>'processDate' FROM 9 FOR 2) BETWEEN '01' AND '31')
                OR (substring(r.row_payload->>'processDate' FROM 6 FOR 2) IN ('04','06','09','11') AND substring(r.row_payload->>'processDate' FROM 9 FOR 2) BETWEEN '01' AND '30')
                OR (substring(r.row_payload->>'processDate' FROM 6 FOR 2)='02' AND substring(r.row_payload->>'processDate' FROM 9 FOR 2) BETWEEN '01' AND '29')
              )
             THEN to_date(r.row_payload->>'processDate','YYYY-MM-DD')
             ELSE NULL
           END AS process_day
    FROM process_sheet_rows r
)
SELECT r.id::text AS "电子报工行ID", r.batch_id AS "批次ID", r.client_row_id AS "客户端行ID",
       r.process_date_text AS "日期文本",
       r.row_payload->>'productTypeId' AS "产品ID",
       pt.name AS "产品",
       r.row_payload->>'outputQuantity' AS "总数文本",
       COALESCE(r.row_payload->>'outputUnit', r.row_payload->>'unit') AS "单位",
       r.row_payload->'customFields'->>'smartbi_oee_good_quantity' AS "良品数文本",
       r.row_payload->'customFields'->>'smartbi_oee_planned_minutes' AS "计划运行分钟",
       r.row_payload->'customFields'->>'smartbi_oee_running_minutes' AS "实际运行分钟",
       r.row_payload->'customFields'->>'smartbi_oee_ideal_cycle_seconds' AS "理想周期秒",
       r.row_payload->'customFields'->>'smartbi_oee_equipment_id' AS "设备ID",
       r.row_payload->'multiOutputMember' AS "多产出成员",
       r.row_payload->>'multiOutputBaseRowId' AS "多产出组ID",
       r.process_order AS "工序序", wp.custom_field_schema AS "工序字段schema",
       fe.id AS "设备主键"
FROM sheet_rows r
JOIN production_plans pp ON pp.id=r.plan_id AND pp.factory_id=r.factory_id
 AND pp.product_type_id=r.row_payload->>'productTypeId'
 AND pp.deleted_at IS NULL AND COALESCE(pp.status,'') NOT IN ('CANCELLED','CANCELED')
 AND pp.workflow_selection_mode='WORKFLOW'
JOIN production_batches pb ON pb.id=r.batch_id AND pb.factory_id=r.factory_id
 AND pb.production_plan_id=r.plan_id AND pb.product_type_id=r.row_payload->>'productTypeId'
 AND pb.deleted_at IS NULL AND COALESCE(pb.status,'') NOT IN ('CANCELLED','CANCELED')
 AND pb.workflow_selection_mode='WORKFLOW'
 AND pb.selected_workflow_id=pp.selected_workflow_id
 AND pb.selected_workflow_version=pp.selected_workflow_version
 AND pb.selected_workflow_revision_id=pp.selected_workflow_revision_id
 AND pb.selected_workflow_revision_hash=pp.selected_workflow_revision_hash
JOIN product_process_workflow_revisions pwr ON pwr.id=pp.selected_workflow_revision_id
 AND pwr.factory_id=pp.factory_id AND pwr.workflow_id=pp.selected_workflow_id
 AND pwr.definition_version=pp.selected_workflow_version
 AND pwr.revision_hash=pp.selected_workflow_revision_hash
 AND pwr.status='PUBLISHED' AND pwr.deleted_at IS NULL
JOIN production_workflow_instances pwi ON pwi.production_batch_id=pb.id
 AND pwi.factory_id=pb.factory_id AND pwi.product_type_id=pb.product_type_id
 AND pwi.workflow_id=pb.selected_workflow_id
 AND pwi.definition_version=pb.selected_workflow_version
 AND pwi.status IN ('ACTIVE','COMPLETED') AND pwi.deleted_at IS NULL
JOIN work_process_tasks wpt ON wpt.workflow_instance_id=pwi.id
 AND wpt.production_batch_id=pb.id
 AND wpt.factory_id=pwi.factory_id AND wpt.product_type_id=pwi.product_type_id
 AND wpt.process_order=r.process_order AND wpt.deleted_at IS NULL
JOIN product_types pt ON pt.id=r.row_payload->>'productTypeId' AND pt.factory_id=r.factory_id
 AND pt.is_active=true AND pt.deleted_at IS NULL
JOIN work_processes wp ON wp.id=wpt.work_process_id AND wp.factory_id=r.factory_id
 AND wp.is_active=true AND wp.deleted_at IS NULL
LEFT JOIN factory_equipment fe ON fe.factory_id=r.factory_id
 AND fe.id::text=r.row_payload->'customFields'->>'smartbi_oee_equipment_id'
 AND fe.status <> 'scrapped' AND fe.deleted_at IS NULL
WHERE r.factory_id=$1 AND r.submission_status='SUBMITTED' AND r.deleted_at IS NULL
  AND r.process_day IS NOT NULL
  AND to_char(r.process_day,'YYYY-MM-DD') = r.process_date_text
  AND ($2::date IS NULL OR r.process_day >= $2::date)
  AND ($3::date IS NULL OR r.process_day <= $3::date)
ORDER BY r.id
LIMIT 10001
"""


def _dates(request: Any) -> tuple[date | None, date | None]:
    if request.period_type == "all":
        return None, None
    return date(request.year, request.start_month, 1), date(
        request.year, request.end_month, calendar.monthrange(request.year, request.end_month)[1]
    )


def _number(value: Any) -> Decimal | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        value = Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError):
        return None
    return value if value.is_finite() else None


def _schema_ok(value: Any, required_schema: tuple[tuple[str, str, str], ...] = _REQUIRED_OEE_SCHEMA) -> bool:
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except (TypeError, ValueError):
            return False
    if not isinstance(value, list):
        return False
    seen: set[str] = set()
    for item in value:
        if not isinstance(item, dict) or item.get("enabled") is not True:
            continue
        key = str(item.get("key"))
        if key in seen:
            return False
        seen.add(key)
    return all(
        any(isinstance(item, dict) and item.get("key") == key
            and item.get("enabled") is True
            and str(item.get("type")) == typ
            and str(item.get("label")) == label for item in value)
        for key, typ, label in required_schema
    )


def _valid_oee(rows: Iterable[dict[str, Any]], factory_id: str,
               required_schema: tuple[tuple[str, str, str], ...] = _REQUIRED_OEE_SCHEMA) -> tuple[list[dict[str, Any]], list[str]]:
    output: list[dict[str, Any]] = []
    reasons: list[str] = []
    materialized = [dict(row) for row in rows]
    report_counts: dict[Any, int] = {}
    window_counts: dict[tuple[Any, Any], int] = {}
    for row in materialized:
        report_counts[row.get("报工ID")] = report_counts.get(row.get("报工ID"), 0) + 1
        key = (row.get("设备ID"), row.get("日期"))
        window_counts[key] = window_counts.get(key, 0) + 1
    seen_report: set[Any] = set()
    for row in materialized:
        report_id, day, equipment = row.get("报工ID"), row.get("日期"), row.get("设备ID")
        if report_id is None or report_counts.get(report_id, 0) != 1:
            reasons.append("报工ID缺失或重复")
            continue
        seen_report.add(report_id)
        if not equipment or str(equipment).strip() == "" or row.get("设备主键") is None:
            reasons.append("设备未能确认属于当前工厂")
            continue
        if window_counts.get((equipment, day), 0) != 1:
            reasons.append("同设备同日存在重复观察窗口")
            continue
        if not _schema_ok(row.get("工序字段schema"), required_schema):
            reasons.append("工序未配置完整OEE字段schema")
            continue
        vals = {key: _number(row.get(label)) for key, _typ, label in _REQUIRED_OEE_SCHEMA[:3]}
        total, good = _number(row.get("总数")), _number(row.get("良品数"))
        if any(v is None or v <= 0 for v in vals.values()) or total is None or good is None or total <= 0:
            reasons.append("OEE数值缺失、非有限或非正")
            continue
        if not row.get("单位") or not str(row.get("单位")).strip():
            reasons.append("产出单位缺失")
            continue
        if good < 0 or good > total or vals["smartbi_oee_running_minutes"] > vals["smartbi_oee_planned_minutes"]:
            reasons.append("OEE数量或运行时间约束失败")
            continue
        if vals["smartbi_oee_ideal_cycle_seconds"] * total > vals["smartbi_oee_running_minutes"] * 60:
            reasons.append("理想周期与产量超过运行时长")
            continue
        output.append({
            "计划运行分钟": float(vals["smartbi_oee_planned_minutes"]),
            "实际运行分钟": float(vals["smartbi_oee_running_minutes"]),
            "理想周期秒": float(vals["smartbi_oee_ideal_cycle_seconds"]),
            "总数": float(total), "良品数": float(good), "单位": row.get("单位"),
            "日期": day, "产品": row.get("产品") or row.get("产品ID"), "报工ID": report_id,
            "设备ID": str(equipment),
        })
    return output, reasons


def _sheet_oee_rows(rows: Iterable[dict[str, Any]], factory_id: str,
                    start: date | None = None, end: date | None = None) -> tuple[list[dict[str, Any]], list[str]]:
    materialized = [dict(row) for row in rows]
    normalized: list[dict[str, Any]] = []
    reasons: list[str] = []
    in_period: list[dict[str, Any]] = []
    for row in materialized:
        if not row.get("电子报工行ID") or row.get("批次ID") is None or not row.get("产品ID") or not row.get("日期文本"):
            reasons.append("电子报工缺少批次、产品或生产日期")
            continue
        try:
            day = date.fromisoformat(str(row["日期文本"]))
        except (TypeError, ValueError):
            reasons.append("电子报工生产日期无效")
            continue
        if start is not None and day < start or end is not None and day > end:
            continue
        row["_strict_day"] = day
        in_period.append(row)
    multi_groups = {
        str(row.get("多产出组ID")) for row in in_period if row.get("多产出组ID")
    }
    for row in in_period:
        is_member = str(row.get("多产出成员", "")).strip().lower() in {"true", "1", "yes"}
        if is_member or (row.get("多产出组ID") and str(row.get("多产出组ID")) in multi_groups):
            reasons.append("多产出报工组不能作为单窗口OEE事实")
            continue
        normalized.append({
            "报工ID": f"sheet-row:{row.get('电子报工行ID')}", "日期": row["_strict_day"],
            "产品ID": row.get("产品ID"), "产品": row.get("产品"),
            "总数": row.get("总数文本"), "良品数": row.get("良品数文本"), "单位": row.get("单位"),
            "计划运行分钟": row.get("计划运行分钟"), "实际运行分钟": row.get("实际运行分钟"),
            "理想周期秒": row.get("理想周期秒"), "设备ID": row.get("设备ID"),
            "设备主键": row.get("设备主键"), "工序字段schema": row.get("工序字段schema"),
        })
    clean, validation = _valid_oee(normalized, factory_id, _REQUIRED_SHEET_OEE_SCHEMA)
    return clean, reasons + validation


def _sales_rows(rows: Iterable[dict[str, Any]], dimension: str, can_price: bool) -> tuple[list[dict[str, Any]], list[str]]:
    if not can_price:
        return [], ["当前角色无金额权限，拒绝构建营业收入数据集"]
    output, reasons, seen = [], [], set()
    for row in rows:
        order_id = row.get("订单ID")
        value = row.get("营业收入（元）")
        label = row.get("渠道") if dimension == "sales_channel" else row.get("部门")
        if order_id is None or order_id in seen:
            reasons.append("订单ID缺失或重复，拒绝重复计数")
            continue
        seen.add(order_id)
        if not label or len(str(label).strip()) > 80:
            reasons.append("渠道/部门值为空或超过80字符")
            continue
        amount = _number(value)
        if amount is None or amount < 0:
            reasons.append("营业收入不是有效财务数值")
            continue
        output.append({"日期": row.get("日期"), "订单ID": order_id,
                       ("渠道" if dimension == "sales_channel" else "部门"): str(label).strip(),
                       "实际（元）": amount})
    return output, reasons


async def load(factory_id: str, request: Any, can_price: bool):
    start, end = _dates(request)
    gaps: list[dict[str, Any]] = []
    errors: list[dict[str, Any]] = []
    datasets: list[Dataset] = []
    try:
        pool = await config.get_cretas_pool()
        if pool is None:
            raise RuntimeError("pool unavailable")
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
                definitions = [dict(r) for r in await conn.fetch(SALES_FIELD_SQL, factory_id)]
                for dimension, code, column, semantic, title in (
                    ("sales_channel", "smartbi_sales_channel", "cf_smartbi_sales_channel", "sales_channel.v1", "渠道"),
                    ("department_id", "smartbi_department_id", "cf_smartbi_department_id", "department_id.v1", "部门"),
                ):
                    if not can_price:
                        gaps.append({"id": f"sales.{dimension}.permission", "title": f"销售{title}收入", "missing": ["当前角色无金额权限，已跳过营业收入读取"]})
                        continue
                    matches = [r for r in definitions if r.get("field_code") == code]
                    expected_type = "TEXT" if dimension == "sales_channel" else "SELECT"
                    if len(matches) != 1 or any((r.get("column_name"), r.get("smartbi_semantic"), str(r.get("field_type", "")).upper()) != (column, semantic, expected_type) for r in matches):
                        gaps.append({"id": f"sales.{dimension}", "title": f"销售{title}收入", "missing": ["缺少唯一且匹配版本的ACTIVE字段定义"]})
                        continue
                    sql = SALES_DEPARTMENT_CAPTURE_SQL if dimension == "department_id" else SALES_CAPTURE_SQL
                    params = (factory_id, start, end, dimension) if dimension == "sales_channel" else (factory_id, start, end)
                    rows = await conn.fetch(sql, *params)
                    if len(rows) > 10000:
                        errors.append({"source": f"sales-{dimension}", "message": "明细超过安全上限，请缩小期间后重试"})
                        continue
                    clean, bad = _sales_rows(rows, dimension, can_price)
                    if bad:
                        gaps.append({"id": f"sales.{dimension}.validation", "title": f"销售{title}收入", "missing": bad})
                    if clean:
                        datasets.append(Dataset(pd.DataFrame(clean), f"sales-{dimension} · 订单总额权威来源", financial=can_price, currency="CNY" if can_price else ""))
                native_clean: list[dict[str, Any]] = []
                native_grouped: dict[tuple[Any, Any, Any, Any], list[dict[str, Any]]] = {}
                rows = await conn.fetch(OEE_SQL, factory_id, start, end)
                if len(rows) > 10000:
                    errors.append({"source": "oee", "message": "明细超过安全上限，请缩小期间后重试"})
                else:
                    native_clean, bad = _valid_oee([dict(r) for r in rows], factory_id)
                    if bad:
                        gaps.append({"id": "production.oee", "title": "设备OEE", "missing": bad})
                    if native_clean:
                        for item in native_clean:
                            key = (item["设备ID"], item["产品"], item["单位"], item["理想周期秒"])
                            native_grouped.setdefault(key, []).append(item)
                sheet_rows = await conn.fetch(SHEET_OEE_SQL, factory_id, start, end)
                if len(sheet_rows) > 10000:
                    errors.append({"source": "oee-sheet", "message": "电子工序表明细超过安全上限，请缩小期间后重试"})
                    for key, items in native_grouped.items():
                        datasets.append(Dataset(pd.DataFrame(items), "oee · 真实生产报工与工序字段 · " + "|".join(map(str, key)), financial=False))
                else:
                    sheet_clean, sheet_bad = _sheet_oee_rows([dict(r) for r in sheet_rows], factory_id, start, end)
                    native_windows = {(item.get("设备ID"), item.get("日期")) for item in native_clean}
                    overlapping = [item for item in sheet_clean if (item["设备ID"], item["日期"]) in native_windows]
                    if overlapping:
                        sheet_bad.append("电子工序表与原生报工存在同设备同日重复观察窗口")
                        overlap_keys = {(item["设备ID"], item["日期"]) for item in overlapping}
                        native_clean = [item for item in native_clean if (item["设备ID"], item["日期"]) not in overlap_keys]
                        gaps.append({"id": "production.oee", "title": "设备OEE", "missing": ["原生报工与电子工序表存在同设备同日重复观察窗口"]})
                        native_grouped = {}
                        for item in native_clean:
                            key = (item["设备ID"], item["产品"], item["单位"], item["理想周期秒"])
                            native_grouped.setdefault(key, []).append(item)
                    sheet_clean = [item for item in sheet_clean if (item["设备ID"], item["日期"]) not in native_windows]
                    for key, items in native_grouped.items():
                        datasets.append(Dataset(pd.DataFrame(items), "oee · 真实生产报工与工序字段 · " + "|".join(map(str, key)), financial=False))
                    if sheet_bad:
                        gaps.append({"id": "production.oee-sheet", "title": "电子工序表OEE", "missing": sheet_bad})
                    if sheet_clean:
                        grouped_sheet: dict[tuple[Any, Any, Any, Any], list[dict[str, Any]]] = {}
                        for item in sheet_clean:
                            key = (item["设备ID"], item["产品"], item["单位"], item["理想周期秒"])
                            grouped_sheet.setdefault(key, []).append(item)
                        for key, items in grouped_sheet.items():
                            datasets.append(Dataset(pd.DataFrame(items), "oee-sheet · 电子工序表提交事实 · " + "|".join(map(str, key)), financial=False))
    except Exception:
        errors.append({"source": "captured", "message": "来源读取失败，请重试或联系管理员核对数据服务"})
    return datasets, errors, gaps
