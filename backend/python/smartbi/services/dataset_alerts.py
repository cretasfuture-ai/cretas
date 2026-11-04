"""Deterministic, tenant-scoped alerts for uploaded SmartBI datasets.

This module deliberately has no database, network, pandas, or AI dependency.  It
only inspects the rows supplied by the caller.  In particular, a flat cost
summary is never presented as a complete manufacturing-cost ledger.
"""

from collections import Counter, defaultdict
from decimal import Decimal, InvalidOperation
import hashlib
import json
import math
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Tuple


SCHEMA = "smartbi.dataset-alerts/1"
RULE_VERSION = "2026-11-03.1"
MAX_ROWS = 5000
MAX_COLUMNS = 200
PEER_MINIMUM = 3
PEER_SPREAD = Decimal("0.20")

CHECK_OPTIONS = {
    "CHECK_FIELD_MAPPING",
    "CHECK_SOURCE_VALUES",
    "CHECK_DUPLICATES",
    "CHECK_COMPARABILITY",
    "CHECK_HISTORICAL_VALUATION",
    "CHECK_PAYROLL",
    "CHECK_FINAL_GOOD",
    "CHECK_MATERIAL_COVERAGE",
}

_OWNER_ACTIONS = {
    "COST_CAPABILITY_LIMITED": ("数据管理员/财务/生产", "补齐来源字段并确认表级汇总口径；不要补造工单明细"),
    "NUMERIC_FIELD_MISSING": ("数据管理员/业务填报人", "回源核对原始单据；缺失值不补零"),
    "NUMERIC_FIELD_INVALID": ("数据管理员/业务填报人", "修正来源格式并复核单位；非法文本不当作零"),
    "NUMERIC_FIELD_ZERO": ("业务负责人", "核对零值业务含义并保留来源凭证"),
    "NEGATIVE_COST_VALUE": ("财务/仓库/生产", "核对退料、冲销或录入符号；确认前不纳入单位成本"),
    "FINISHED_GOOD_NONPOSITIVE": ("生产/仓库", "核对最终审批良品和成品入库；非正数不纳入单位成本"),
    "COST_ROW_INELIGIBLE": ("数据管理员/业务负责人", "补齐工单、产品、单位及可核算数值后再复核"),
    "WORK_ORDER_GRAIN_AMBIGUOUS": ("数据管理员/生产", "确认工单汇总粒度或明细来源；不自动去重，重复工单不参与单位成本比较"),
    "UNIT_COST_SPREAD_CANDIDATE": ("财务/生产", "核对历史估值、材料覆盖和报工；不直接认定浪费或涨价"),
    "PRODUCT_NAME_CONFLICT": ("主数据/生产", "核实规格和工艺阶段；冲突记录不归并比较"),
    "PEER_IDENTITY_INCOMPLETE": ("主数据/业务负责人", "补齐稳定产品标识后再比较"),
    "FIELD_MAPPING_AMBIGUOUS": ("数据管理员", "确认唯一来源列后再分析"),
    "DUPLICATE_ROWS_CANDIDATE": ("数据管理员/业务负责人", "回源确认是否为合法重复业务记录；不自动删除"),
    "SCAN_TRUNCATED": ("数据管理员", "放宽分页或拆分来源后重新核对"),
    "DATASET_EMPTY": ("数据管理员", "确认导出范围、筛选条件和来源文件"),
}

_ALIASES: Dict[str, Tuple[str, ...]] = {
    "workOrderCode": (
        "workOrderCode", "work_order_code", "工单编码", "工单号", "工单", "生产工单",
    ),
    "finishedGood": (
        "finishedGood", "finished_good", "finishedGoodQty", "finalGood", "良品量", "成品良品量", "最终良品量", "产量",
    ),
    "materialCost": (
        "materialCost", "material_cost", "materialAmount", "材料成本", "材料金额", "原料成本", "原材料成本",
    ),
    "laborCost": (
        "laborCost", "labor_cost", "laborAmount", "人工成本", "人工金额", "工资成本",
    ),
    "productCode": (
        "productCode", "product_code", "sku", "SKU", "productId", "产品代码", "产品编码", "SKU编码",
    ),
    "unit": ("unit", "单位", "成品单位"),
    "productName": ("productName", "product", "产品名称", "品名"),
}

_NUMERIC_FIELDS = {"finishedGood", "materialCost", "laborCost"}
_REQUIRED_COST_FIELDS = (
    "workOrderCode", "finishedGood", "materialCost", "laborCost", "productCode", "unit",
)


def _string(value: Any) -> str:
    return "" if value is None else str(value).strip()


def _is_missing(value: Any) -> bool:
    return value is None or (isinstance(value, str) and not value.strip())


def _number(value: Any) -> Optional[Decimal]:
    if _is_missing(value) or isinstance(value, bool):
        return None
    try:
        result = Decimal(str(value).strip())
    except (InvalidOperation, ValueError, TypeError):
        return None
    return result if result.is_finite() else None


def _json_safe(value: Any) -> Any:
    if isinstance(value, Decimal):
        return str(value) if value.is_finite() else str(value)
    if isinstance(value, float) and not math.isfinite(value):
        # Preserve the fact that the source was non-finite without emitting a
        # NaN/Infinity token that strict JSON encoders reject.
        return str(value)
    if isinstance(value, Mapping):
        return {str(k): _json_safe(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(v) for v in value]
    try:
        json.dumps(value)
        return value
    except (TypeError, ValueError):
        return str(value)


def _stable(value: Any) -> str:
    return json.dumps(_json_safe(value), ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _decimal_for_report(value: Optional[Decimal]) -> Optional[Any]:
    if value is None:
        return None
    # Preserve exact values in evidence while keeping JSON-friendly integers.
    if value == value.to_integral_value():
        return int(value)
    return str(value)


def _normalise_mapping(field_mapping: Optional[Mapping[str, Any]]) -> Dict[str, List[str]]:
    result: Dict[str, List[str]] = {}
    if not isinstance(field_mapping, Mapping):
        return result
    for standard, original in field_mapping.items():
        if isinstance(original, (list, tuple, set)):
            values = [_string(v) for v in original if not _is_missing(v)]
        else:
            values = [_string(original)] if not _is_missing(original) else []
        result[_string(standard)] = values
    return result


def _column_candidates(columns: Optional[Sequence[Mapping[str, Any]]]) -> Dict[str, List[str]]:
    candidates: Dict[str, List[str]] = defaultdict(list)
    if not columns:
        return candidates
    for column in columns[:MAX_COLUMNS]:
        if not isinstance(column, Mapping):
            continue
        original = _string(column.get("originalName") or column.get("original_name") or column.get("name"))
        standard = _string(column.get("standardName") or column.get("standard_name") or original)
        if standard and original and original not in candidates[standard]:
            candidates[standard].append(original)
    return candidates


def _source_type_is_non_work_order(source_type: Any) -> bool:
    value = _string(source_type).lower()
    return any(token in value for token in ("sales", "sale", "finance", "financial", "财务", "销售", "收入", "应收"))


def _field_value(row: Mapping[str, Any], field: str, resolved: Mapping[str, Optional[str]]) -> Any:
    original = resolved.get(field)
    if original is None:
        return None
    return row.get(original)


def _field_label(field: str, resolved: Mapping[str, Optional[str]]) -> str:
    return resolved.get(field) or field


def _make_scope(factory_id: Any, source_type: Any, source_id: Any) -> Dict[str, Any]:
    return {
        "factoryId": _string(factory_id),
        "sourceType": _string(source_type),
        "sourceId": _string(source_id),
    }


def analyze_dataset(
    rows: Iterable[Mapping[str, Any]],
    *,
    factory_id: Any,
    source_type: Any,
    source_id: Any,
    field_mapping: Optional[Mapping[str, Any]] = None,
    columns: Optional[Sequence[Mapping[str, Any]]] = None,
    total_rows: Optional[int] = None,
    dataset_kind: str = "auto",
) -> Dict[str, Any]:
    """Analyze a bounded row set and return a stable public alert report.

    ``field_mapping`` is intentionally standard-name -> original-column-name.
    Rows and column metadata are copied before inspection, so callers can reuse
    their upload objects safely.  ``total_rows`` is the source row count when a
    connector paginates; it is never inferred from the bounded scan.
    """
    factory_text = _string(factory_id)
    source_type_text = _string(source_type)
    source_id_text = _string(source_id)
    if not factory_text or not source_id_text:
        raise ValueError("DATASET_SCOPE_REQUIRED")
    dataset_kind = _string(dataset_kind).lower() or "auto"
    if dataset_kind not in {"auto", "generic", "production_cost"}:
        raise ValueError("DATASET_KIND_INVALID")
    source = {"type": source_type_text, "id": source_id_text}
    scope = _make_scope(factory_text, source_type_text, source_id_text)
    input_rows = list(rows or [])
    declared_rows = max(0, int(total_rows)) if total_rows is not None else len(input_rows)
    declared_rows = max(declared_rows, len(input_rows))
    # A connector may provide only a page while reporting the full source
    # count.  That is still a truncation even when both values are below the
    # hard safety ceiling.
    row_truncated = declared_rows > len(input_rows) or len(input_rows) > MAX_ROWS or declared_rows > MAX_ROWS
    scanned_input = input_rows[:MAX_ROWS]

    all_columns = set()
    for row in scanned_input:
        if isinstance(row, Mapping):
            all_columns.update(str(key) for key in row.keys())
    metadata_columns = []
    if columns:
        for column in columns:
            if isinstance(column, Mapping):
                original = _string(column.get("originalName") or column.get("original_name") or column.get("name"))
                if original:
                    all_columns.add(original)
                metadata_columns.append(dict(column))
    column_names = sorted(all_columns)
    column_truncated = len(column_names) > MAX_COLUMNS or len(metadata_columns) > MAX_COLUMNS
    allowed_columns = set(column_names[:MAX_COLUMNS])
    scanned_rows: List[Dict[str, Any]] = []
    for row in scanned_input:
        if isinstance(row, Mapping):
            scanned_rows.append({str(k): row[k] for k in sorted(row.keys(), key=str) if str(k) in allowed_columns})
        else:
            scanned_rows.append({})
    scanned_columns = sorted({key for row in scanned_rows for key in row})

    mappings = _normalise_mapping(field_mapping)
    metadata_candidates = _column_candidates(metadata_columns)
    mapping_ambiguities: Dict[str, List[str]] = {}
    resolved: Dict[str, Optional[str]] = {}
    for standard in set(_ALIASES) | set(mappings) | set(metadata_candidates):
        explicit = mappings.get(standard, [])
        candidates = []
        if explicit:
            candidates.extend(explicit)
        elif metadata_candidates.get(standard):
            candidates.extend(metadata_candidates[standard])
        else:
            # Without an explicit mapping, two present aliases are ambiguous;
            # choosing the first would make Excel/API results depend on column
            # ordering.  An explicit mapping is the only disambiguator.
            aliases = _ALIASES.get(standard, (standard,))
            candidates.extend(alias for alias in aliases if alias in scanned_columns)
        unique = []
        for candidate in candidates:
            if candidate not in unique:
                unique.append(candidate)
        # A metadata declaration with two columns for one standard is not safe
        # to silently choose, even if one happens to be empty in this page.
        declared_candidates = metadata_candidates.get(standard, [])
        if len(declared_candidates) > 1 or len(explicit) > 1 or (not explicit and not declared_candidates and len(unique) > 1):
            mapping_ambiguities[standard] = list(dict.fromkeys(declared_candidates + explicit + unique))
            resolved[standard] = None
        else:
            # Keep a declared/mapped column even when every scanned row omits
            # it: that distinction is what lets the report say "missing"
            # instead of silently treating the field as unmapped.
            resolved[standard] = unique[0] if unique else None

    source_is_non_work_order = _source_type_is_non_work_order(source_type_text)

    def has_candidate(standard: str) -> bool:
        if resolved.get(standard):
            return True
        if standard in mapping_ambiguities or standard in metadata_candidates:
            return True
        aliases = set(_ALIASES.get(standard, (standard,)))
        if any(key in aliases or any(value in aliases for value in values) for key, values in metadata_candidates.items()):
            return True
        return any(alias in scanned_columns for alias in aliases)

    has_cost_columns = has_candidate("materialCost") or has_candidate("laborCost")
    has_work_order_column = has_candidate("workOrderCode")
    # auto is intentionally conservative: cost rules require an explicit
    # production work-order column. production_cost enables production checks,
    # but never overrides a finance/sales source boundary.
    cost_rules_enabled = (
        dataset_kind == "production_cost" or (dataset_kind == "auto" and has_work_order_column)
    ) and not source_is_non_work_order and dataset_kind != "generic"

    content_for_hash = {
        "rows": scanned_rows,
        "declaredRows": declared_rows,
        "columns": metadata_columns[:MAX_COLUMNS],
        "fieldMapping": mappings,
        "datasetKind": dataset_kind,
        "truncated": {"rows": row_truncated, "columns": column_truncated},
    }
    content_digest = hashlib.sha256(_stable(content_for_hash).encode("utf-8")).hexdigest()
    run_id = "dataset-" + hashlib.sha256(
        _stable({"factoryId": _string(factory_id), "source": source, "content": content_digest}).encode("utf-8")
    ).hexdigest()[:24]

    items: List[Dict[str, Any]] = []

    def emit(
        alert_type: str,
        family: str,
        severity: str,
        title: str,
        message: str,
        *,
        fields: Sequence[str] = (),
        evidence: Optional[Mapping[str, Any]] = None,
        source_refs: Optional[Sequence[Mapping[str, Any]]] = None,
        blocks_unit_cost: bool = False,
        check_options: Sequence[str] = (),
        entity_refs: Optional[Sequence[Mapping[str, Any]]] = None,
        owner: Optional[str] = None,
        action: Optional[str] = None,
    ) -> None:
        options = [option for option in check_options if option in CHECK_OPTIONS]
        refs = [_json_safe(dict(ref)) for ref in (source_refs or [])]
        identity = {
            "factoryId": _string(factory_id),
            "source": source,
            "content": content_digest,
            "type": alert_type,
            "fields": list(fields),
            "refs": refs,
        }
        item_id = hashlib.sha256(_stable(identity).encode("utf-8")).hexdigest()[:24]
        default_owner, default_action = _OWNER_ACTIONS.get(alert_type, ("数据管理员", "先核对来源字段与业务责任人，再决定是否重算"))
        items.append({
            "id": item_id,
            "type": alert_type,
            "family": family,
            "severity": severity,
            "blocksUnitCost": bool(blocks_unit_cost),
            "title": title,
            "message": message,
            "entityRefs": _json_safe(list(entity_refs)) if entity_refs is not None else None,
            "fields": list(fields),
            "evidence": _json_safe(dict(evidence or {})),
            "owner": owner or default_owner,
            "action": action or default_action,
            "status": "needs_review",
            "detectedBy": "rule",
            "ruleVersion": RULE_VERSION,
            "scope": scope,
            "runId": run_id,
            "sourceRefs": refs,
            "checkOptions": options,
        })

    def ref(row_number: int, fields: Sequence[str]) -> Dict[str, Any]:
        return {"sourceType": source["type"], "sourceId": source["id"], "rowNumber": row_number, "fields": list(fields)}

    if not scanned_rows:
        emit(
            "DATASET_EMPTY", "readiness", "info", "数据表为空",
            "来源表没有可扫描的数据行；影响是无法进行质量或成本核对，责任角色：数据管理员；下一步：确认导出范围、筛选条件和来源文件。",
            evidence={"rows": declared_rows, "scannedRows": 0}, check_options=("CHECK_SOURCE_VALUES",),
        )

    for standard, candidates in sorted(mapping_ambiguities.items()):
        labels = "、".join(candidates) or standard
        emit(
            "FIELD_MAPPING_AMBIGUOUS", "data_quality", "blocking", "字段映射存在歧义",
            f"字段“{standard}”对应多个来源列（{labels}）；影响是数值可能被重复或错列读取，责任角色：数据管理员；下一步：确认唯一来源列后再分析。",
            fields=[standard] + candidates,
            evidence={"standardName": standard, "candidateColumns": candidates, "candidateCount": len(candidates)},
            check_options=("CHECK_FIELD_MAPPING",), blocks_unit_cost=cost_rules_enabled,
        )

    if row_truncated or column_truncated:
        emit(
            "SCAN_TRUNCATED", "readiness", "info", "数据扫描被截断",
            f"本次仅扫描 {len(scanned_rows)} 行、{len(scanned_columns)} 列；影响是报告不能代表未扫描部分，责任角色：数据管理员；下一步：放宽分页或拆分来源后重新核对。",
            evidence={
                "rows": declared_rows, "scannedRows": len(scanned_rows), "columns": len(all_columns),
                "scannedColumns": len(scanned_columns), "maxRows": MAX_ROWS, "maxColumns": MAX_COLUMNS,
                "rowsTruncated": row_truncated, "columnsTruncated": column_truncated,
            },
            fields=["rows", "columns"], check_options=("CHECK_SOURCE_VALUES",),
        )

    # Empty/missing/invalid/zero checks are deliberately row-referenced.  A
    # numeric zero is valid data and gets a source reminder, never a missing flag.
    numeric_fields = set(_NUMERIC_FIELDS)
    for column in metadata_columns[:MAX_COLUMNS]:
        standard = _string(column.get("standardName") or column.get("standard_name") or column.get("originalName") or column.get("original_name") or column.get("name"))
        semantic = _string(column.get("semanticType") or column.get("semantic_type")).lower()
        field_type = _string(column.get("fieldType") or column.get("field_type")).lower()
        if column.get("isMeasure") or semantic in {"number", "numeric", "measure", "金额", "数量"} or field_type in {"number", "numeric", "decimal", "float", "int"}:
            if standard:
                numeric_fields.add(standard)
    for field in sorted(numeric_fields):
        original = resolved.get(field)
        if original is None:
            continue
        missing_rows = []
        invalid_rows = []
        zero_rows = []
        for index, row in enumerate(scanned_rows, start=1):
            value = _field_value(row, field, resolved)
            if _is_missing(value):
                missing_rows.append(index)
            elif _number(value) is None:
                invalid_rows.append(index)
            elif _number(value) == 0:
                zero_rows.append(index)
        field_name = _field_label(field, resolved)
        if missing_rows:
            emit(
                "NUMERIC_FIELD_MISSING", "data_quality", "blocking" if cost_rules_enabled and field in _REQUIRED_COST_FIELDS else "warning",
                "数字字段缺失", f"字段“{field_name}”在第 {missing_rows[0]} 行等 {len(missing_rows)} 行为空；影响是相关指标不能可靠计算，责任角色：数据管理员/业务填报人；下一步：回源核对原始单据，缺失值不补零。",
                fields=[field_name], evidence={"field": field, "missingCount": len(missing_rows), "rowCount": len(scanned_rows)},
                source_refs=[ref(row_number, [field_name]) for row_number in missing_rows[:100]], check_options=("CHECK_SOURCE_VALUES",),
                blocks_unit_cost=cost_rules_enabled and field in _REQUIRED_COST_FIELDS,
            )
        if invalid_rows:
            emit(
                "NUMERIC_FIELD_INVALID", "data_quality", "blocking" if cost_rules_enabled and field in _REQUIRED_COST_FIELDS else "warning",
                "数字字段格式非法", f"字段“{field_name}”在第 {invalid_rows[0]} 行等 {len(invalid_rows)} 行不是可识别数字；影响是相关指标不能可靠计算，责任角色：数据管理员/业务填报人；下一步：核对原始格式和单位，不把文本异常当作零。",
                fields=[field_name], evidence={"field": field, "invalidCount": len(invalid_rows), "rowCount": len(scanned_rows)},
                source_refs=[ref(row_number, [field_name]) for row_number in invalid_rows[:100]], check_options=("CHECK_SOURCE_VALUES",),
                blocks_unit_cost=cost_rules_enabled and field in _REQUIRED_COST_FIELDS,
            )
        if cost_rules_enabled and zero_rows and field in {"materialCost", "laborCost", "finishedGood"}:
            check = "CHECK_FINAL_GOOD" if field == "finishedGood" else ("CHECK_MATERIAL_COVERAGE" if field == "materialCost" else "CHECK_PAYROLL")
            emit(
                "NUMERIC_FIELD_ZERO", "data_quality", "warning",
                "数字字段为零，需确认来源口径", f"字段“{field_name}”在第 {zero_rows[0]} 行等 {len(zero_rows)} 行明确为 0；影响是业务含义需要确认但不能视为缺失，责任角色：业务负责人；下一步：核对是否确为零值并保留来源凭证。",
                fields=[field_name], evidence={"field": field, "zeroCount": len(zero_rows), "rowCount": len(scanned_rows)},
                source_refs=[ref(row_number, [field_name]) for row_number in zero_rows[:100]], check_options=(check,),
            )

    # Exact duplicate rows are a candidate only; no row is removed from the
    # scanned population and every candidate retains its source row numbers.
    duplicate_groups: Dict[str, List[int]] = defaultdict(list)
    for index, row in enumerate(scanned_rows, start=1):
        duplicate_groups[_stable(row)].append(index)
    for fingerprint, row_numbers in sorted(duplicate_groups.items()):
        if len(row_numbers) < 2:
            continue
        emit(
            "DUPLICATE_ROWS_CANDIDATE", "data_quality", "warning", "发现完全重复行候选",
            f"第 {row_numbers[0]}、{row_numbers[1]} 行等 {len(row_numbers)} 行内容完全一致；影响是汇总可能重复计数，但系统不会自动删除，责任角色：数据管理员/业务负责人；下一步：回源确认是否为合法重复业务记录。",
            fields=sorted(scanned_rows[row_numbers[0] - 1]), evidence={"duplicateCount": len(row_numbers), "rowCount": len(scanned_rows)},
            source_refs=[ref(row_number, sorted(scanned_rows[row_number - 1])) for row_number in row_numbers[:100]],
            check_options=("CHECK_DUPLICATES",),
        )

    cost_missing_fields = [field for field in _REQUIRED_COST_FIELDS if resolved.get(field) is None]
    work_order_field = resolved.get("workOrderCode")
    work_order_rows: Dict[str, List[int]] = defaultdict(list)
    if work_order_field:
        for row_number, row in enumerate(scanned_rows, start=1):
            code = _string(row.get(work_order_field))
            if code:
                work_order_rows[code].append(row_number)
    ambiguous_work_orders = {code: row_numbers for code, row_numbers in work_order_rows.items() if len(row_numbers) > 1}
    cost_rows: List[Tuple[int, Mapping[str, Any], Dict[str, Any]]] = []
    row_missing_fields: Dict[int, List[str]] = {}
    row_invalid_fields: Dict[int, List[str]] = {}
    row_nonpositive_fields: Dict[int, List[str]] = {}
    row_negative_fields: Dict[int, List[str]] = {}
    row_grain_excluded: Dict[int, List[str]] = {}
    for row_number, row in enumerate(scanned_rows, start=1):
        values = {field: _field_value(row, field, resolved) for field in _REQUIRED_COST_FIELDS}
        missing = [field for field in _REQUIRED_COST_FIELDS if _is_missing(values[field])]
        invalid = [field for field in _NUMERIC_FIELDS if not _is_missing(values[field]) and _number(values[field]) is None]
        if missing:
            row_missing_fields[row_number] = missing
        if invalid:
            row_invalid_fields[row_number] = invalid
        if missing or invalid:
            continue
        quantity = _number(values["finishedGood"])
        material = _number(values["materialCost"])
        labor = _number(values["laborCost"])
        nonpositive = ["finishedGood"] if quantity is not None and quantity <= 0 else []
        negative = [field for field, value in (("materialCost", material), ("laborCost", labor)) if value is not None and value < 0]
        if nonpositive:
            row_nonpositive_fields[row_number] = nonpositive
        if negative:
            row_negative_fields[row_number] = negative
        if nonpositive or negative:
            continue
        if _string(values.get("workOrderCode")) in ambiguous_work_orders:
            row_grain_excluded[row_number] = [work_order_field or "workOrderCode"]
            continue
        cost_rows.append((row_number, row, values))

    # Missing identity is different from an unmapped schema: preserve each
    # offending row so a caller can repair only the affected source records.
    missing_value_fields = sorted({field for fields in row_missing_fields.values() for field in fields})
    if cost_rules_enabled and missing_value_fields:
        emit(
            "COST_ROW_INELIGIBLE", "readiness", "warning", "部分成本行缺少必需标识或数值",
            f"第 {next(iter(row_missing_fields))} 行等 {len(row_missing_fields)} 行缺少工单编码、产品代码、单位或可核算数值；影响是这些行不能进入表级成本核对，责任角色：数据管理员/业务负责人；下一步：补齐来源字段后再复核。",
            fields=missing_value_fields,
            evidence={"ineligibleRows": len(row_missing_fields), "missingFieldCounts": dict(Counter(field for fields in row_missing_fields.values() for field in fields))},
            source_refs=[ref(row_number, fields) for row_number, fields in sorted(row_missing_fields.items())[:100]],
            check_options=("CHECK_SOURCE_VALUES", "CHECK_FIELD_MAPPING"),
            blocks_unit_cost=True,
        )
    if cost_rules_enabled and row_nonpositive_fields:
        emit(
            "FINISHED_GOOD_NONPOSITIVE", "data_quality", "warning", "最终良品量非正",
            f"第 {next(iter(row_nonpositive_fields))} 行等 {len(row_nonpositive_fields)} 行最终良品量为 0 或负数；影响是单位成本分母无效，责任角色：生产/仓库；下一步：核对最终审批良品和成品入库，非正数不纳入单位成本。",
            fields=["finishedGood"], evidence={"rowCount": len(row_nonpositive_fields), "nonpositiveCount": len(row_nonpositive_fields)},
            source_refs=[ref(row_number, fields) for row_number, fields in sorted(row_nonpositive_fields.items())[:100]],
            check_options=("CHECK_FINAL_GOOD",),
            blocks_unit_cost=True,
        )
    if cost_rules_enabled and row_negative_fields:
        emit(
            "NEGATIVE_COST_VALUE", "data_quality", "warning", "成本字段出现负数",
            f"第 {next(iter(row_negative_fields))} 行等 {len(row_negative_fields)} 行材料或人工成本为负；影响可能是退料/冲销或符号录入，责任角色：财务/仓库/生产；下一步：核对来源凭证，确认前不纳入单位成本。",
            fields=sorted({field for fields in row_negative_fields.values() for field in fields}),
            evidence={"rowCount": len(row_negative_fields), "negativeFieldCounts": dict(Counter(field for fields in row_negative_fields.values() for field in fields))},
            source_refs=[ref(row_number, fields) for row_number, fields in sorted(row_negative_fields.items())[:100]],
            check_options=("CHECK_MATERIAL_COVERAGE", "CHECK_PAYROLL"),
            blocks_unit_cost=True,
        )

    invalid_cost_fields = []
    for fields in row_invalid_fields.values():
        invalid_cost_fields.extend(fields)
    invalid_cost_fields = sorted(set(invalid_cost_fields))
    row_ineligible_count = len(set(row_missing_fields) | set(row_invalid_fields) | set(row_nonpositive_fields) | set(row_negative_fields) | set(row_grain_excluded))
    if not cost_rules_enabled:
        cost_status = "not_applicable"
        cost_reason = "当前数据集类型或来源边界不启用生产工单成本规则；保留一般数据质量检查。"
    elif source_is_non_work_order:
        cost_status = "not_applicable"
        cost_reason = "来源类型为销售/财务类表，不能仅凭列名硬解释为生产工单成本。"
    elif not scanned_rows:
        cost_status = "not_applicable"
        cost_reason = "空表没有可核对的工单成本汇总行。"
    elif resolved.get("workOrderCode") is None:
        cost_status = "not_applicable"
        cost_reason = "未识别为生产工单表，保留常规数据质量检查；不要求非生产数据补录工单成本。"
    elif cost_missing_fields or invalid_cost_fields or row_ineligible_count or not cost_rows:
        cost_status = "insufficient"
        cost_reason = "缺少明确工单成本字段、部分行存在缺失/非法/非正值，或扫描行没有一行同时满足表级汇总条件。"
    else:
        cost_status = "summary_only"
        cost_reason = "仅识别为平面表级汇总成本；不代表完整制造成本，不造材料明细、任务或关联单据。"
    if cost_rules_enabled and cost_status != "summary_only":
        emit(
            "COST_CAPABILITY_LIMITED", "readiness", "info", "成本核对能力受限",
            f"{cost_reason} 影响是当前不能可靠进行同产品单位成本比较，责任角色：数据管理员/财务/生产；下一步：补齐来源字段或改用适用的成本明细流程。",
            fields=cost_missing_fields or invalid_cost_fields or missing_value_fields or ["sourceType"], evidence={"status": cost_status, "missingFields": cost_missing_fields, "rowMissingFields": missing_value_fields, "invalidFields": invalid_cost_fields, "ineligibleRows": row_ineligible_count, "reason": cost_reason},
            check_options=("CHECK_FIELD_MAPPING", "CHECK_SOURCE_VALUES"),
            blocks_unit_cost=cost_status == "insufficient",
        )

    # A repeated work-order code may be an imported duplicate or a lower-level
    # material/process grain.  Keep every source row, but exclude that code
    # from peer costing; one row is not chosen implicitly.
    if cost_rules_enabled and ambiguous_work_orders:
        source_refs = [
            ref(row_number, [work_order_field or "workOrderCode"])
            for row_numbers in ambiguous_work_orders.values()
            for row_number in row_numbers[:100]
        ]
        emit(
            "WORK_ORDER_GRAIN_AMBIGUOUS", "readiness", "warning", "同工单编码出现多行",
            f"发现 {len(ambiguous_work_orders)} 个工单编码各有多行（共 {sum(len(rows) for rows in ambiguous_work_orders.values())} 行）；影响是无法确认工单汇总粒度，重复工单不能作为多个独立样本，责任角色：数据管理员/生产；下一步：确认工单汇总或明细来源，不自动去重。",
            fields=[work_order_field or "workOrderCode"],
            evidence={
                "ambiguousWorkOrderCount": len(ambiguous_work_orders),
                "ambiguousRowCount": sum(len(rows) for rows in ambiguous_work_orders.values()),
                "workOrderCodes": sorted(ambiguous_work_orders),
            },
            source_refs=source_refs,
            blocks_unit_cost=True,
            check_options=("CHECK_COMPARABILITY", "CHECK_SOURCE_VALUES"),
        )

    # Peer comparison is restricted to code + unit + name, with valid numeric
    # values and at least three observations.  No cross-unit comparison.
    peer_comparison_enabled = (
        cost_rules_enabled and bool(cost_rows)
        and not cost_missing_fields and not invalid_cost_fields
        and not row_missing_fields and not row_invalid_fields
        and not row_nonpositive_fields and not row_negative_fields
    )
    peer_groups: Dict[Tuple[str, str, str], List[Tuple[int, Dict[str, Any]]]] = defaultdict(list)
    invalid_peer_identity = []
    for row_number, _row, values in cost_rows:
        product_code = _string(values.get("productCode"))
        unit = _string(values.get("unit"))
        product_name = _string(_field_value(_row, "productName", resolved))
        if not product_code or not unit or not product_name:
            invalid_peer_identity.append(row_number)
            continue
        peer_groups[(product_code, unit, product_name)].append((row_number, values))
    if invalid_peer_identity and cost_status == "summary_only":
        emit(
            "PEER_IDENTITY_INCOMPLETE", "readiness", "info", "成本可比标识不完整",
            f"第 {invalid_peer_identity[0]} 行等 {len(invalid_peer_identity)} 行缺少产品代码、单位或名称；影响是不能安全归入同一可比组，责任角色：主数据/业务负责人；下一步：补齐稳定产品标识后再比较。",
            fields=["productCode", "unit", "productName"], evidence={"rowCount": len(invalid_peer_identity)},
            source_refs=[ref(row_number, ["productCode", "unit", "productName"]) for row_number in invalid_peer_identity[:100]],
            check_options=("CHECK_COMPARABILITY",),
        )
    # Detect same product code/unit with conflicting names; do not compare it.
    code_unit_names: Dict[Tuple[str, str], set] = defaultdict(set)
    blocked_peer_keys = set()
    for row_number, _row, values in cost_rows:
        code_unit_names[(_string(values.get("productCode")), _string(values.get("unit")))].add(_string(_field_value(_row, "productName", resolved)))
    for (code, unit), names in sorted(code_unit_names.items()):
        if cost_rules_enabled and code and unit and len(names - {""}) > 1:
            blocked_peer_keys.add((code, unit))
            rows_for_conflict = [row_number for row_number, _row, values in cost_rows if _string(values.get("productCode")) == code and _string(values.get("unit")) == unit]
            emit(
                "PRODUCT_NAME_CONFLICT", "data_quality", "warning", "同产品标识出现名称冲突",
                f"产品代码“{code}”、单位“{unit}”对应多个名称（{'、'.join(sorted(names))}）；影响是不能确认同一可比产品，责任角色：主数据/生产；下一步：核实规格和工艺阶段，不能直接归并成本。",
                fields=["productCode", "unit", "productName"], evidence={"productCode": code, "unit": unit, "names": sorted(names)},
                source_refs=[ref(row_number, ["productCode", "unit", "productName"]) for row_number in rows_for_conflict[:100]],
                check_options=("CHECK_COMPARABILITY",),
            )
    for (code, unit, name), peers in sorted(peer_groups.items()):
        if not peer_comparison_enabled or (code, unit) in blocked_peer_keys:
            continue
        # Peer sample size is measured in distinct, unambiguous work orders.
        # Ambiguous codes remain in the report/source rows but never inflate a
        # comparison group.
        comparable_peers = [
            (row_number, values)
            for row_number, values in peers
            if _string(values.get("workOrderCode")) not in ambiguous_work_orders
        ]
        distinct_work_orders = {_string(values.get("workOrderCode")) for _, values in comparable_peers}
        if len(distinct_work_orders) < PEER_MINIMUM:
            continue
        measured = []
        for row_number, values in comparable_peers:
            quantity = _number(values.get("finishedGood"))
            material = _number(values.get("materialCost"))
            labor = _number(values.get("laborCost"))
            if quantity is None or quantity <= 0 or material is None or labor is None:
                continue
            measured.append((row_number, (material + labor) / quantity))
        if len(measured) < PEER_MINIMUM:
            continue
        low = min(measured, key=lambda item: item[1])
        high = max(measured, key=lambda item: item[1])
        if low[1] > 0 and (high[1] - low[1]) / low[1] >= PEER_SPREAD:
            emit(
                "UNIT_COST_SPREAD_CANDIDATE", "business_signal", "warning", "同产品同单位成本差异候选",
                f"产品代码“{code}”、单位“{unit}”、名称“{name}”的 {len(measured)} 个样本单位成本差异达到 {(high[1] - low[1]) / low[1]:.1%}；影响是需要复核估值与归集口径，责任角色：财务/生产；下一步：核对历史估值、材料覆盖和报工，不直接认定浪费或涨价。",
                fields=["productCode", "unit", "productName", "materialCost", "laborCost", "finishedGood"],
                evidence={"sampleSize": len(measured), "minimumUnitCost": _decimal_for_report(low[1]), "maximumUnitCost": _decimal_for_report(high[1]), "spread": str((high[1] - low[1]) / low[1]), "threshold": str(PEER_SPREAD)},
                source_refs=[ref(row_number, ["productCode", "unit", "materialCost", "laborCost", "finishedGood"]) for row_number, _ in measured[:100]],
                check_options=("CHECK_COMPARABILITY", "CHECK_HISTORICAL_VALUATION"),
            )

    families = Counter(item["family"] for item in items)
    types = Counter(item["type"] for item in items)
    quality_available = bool(scanned_rows) and not any(item["family"] == "data_quality" and item["severity"] == "blocking" for item in items)
    ranking_groups = defaultdict(list)
    if cost_rules_enabled and not source_is_non_work_order:
        for _, row, values in cost_rows:
            name = _string(_field_value(row,"productName",resolved))
            if name:ranking_groups[(_string(values['productCode']),_string(values['unit']),name)].append(values)
    rankings=[]
    for (code,unit,name),group in ranking_groups.items():
        quantity=sum((_number(row['finishedGood']) for row in group),Decimal(0))
        costs=[(_number(row['materialCost'])+_number(row['laborCost']))/_number(row['finishedGood']) for row in group]
        total=sum((_number(row['materialCost'])+_number(row['laborCost']) for row in group),Decimal(0))
        rankings.append({'productCode':code,'unit':unit,'product':name,'projects':len(group),
                         'weightedUnitCost':_decimal_for_report(total/quantity),
                         'minimum':_decimal_for_report(min(costs)),'maximum':_decimal_for_report(max(costs))})
    return {
        "schema": SCHEMA,
        "ruleVersion": RULE_VERSION,
        "datasetKind": dataset_kind,
        "scope": scope,
        "runId": run_id,
        "source": source,
        "summary": {
            "rows": declared_rows,
            "scannedRows": len(scanned_rows),
            "alerts": len(items),
            "byType": dict(sorted(types.items())),
            "byFamily": dict(sorted(families.items())),
            "truncated": bool(row_truncated or column_truncated),
        },
        "capabilities": {
            "quality": {"status": "available" if quality_available else ("empty" if not scanned_rows else "needs_review"), "available": bool(scanned_rows)},
            "cost": {
                "status": cost_status,
                "hasCostColumns": has_cost_columns,
                "available": cost_status == "summary_only",
                "reason": cost_reason,
                "eligibleRows": len(cost_rows) if cost_rules_enabled else 0,
                "scannedRows": len(scanned_rows),
                "eligibleCoverage": (len(cost_rows) / len(scanned_rows)) if scanned_rows and cost_rules_enabled else 0,
                "ambiguousWorkOrderRows": len(row_grain_excluded),
            },
            "costMissingFields": sorted(set(cost_missing_fields + missing_value_fields + invalid_cost_fields)),
        },
        "items": items,
        "analysisMetrics": {"rankings":rankings[:100],"omittedGroups":max(0,len(rankings)-100),
                            "scope":"eligible_rows_only","fullManufacturingCost":None,"grossProfit":None},
        "ai": {"status": "not_run", "message": "规则只返回类型、计数和比例等可审计事实；未上传或生成原始明细解释。"},
    }


__all__ = ["analyze_dataset", "SCHEMA", "RULE_VERSION"]
