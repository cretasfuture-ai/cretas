"""Shared, fail-closed semantics for inventory batch-count details."""
from __future__ import annotations

from decimal import Decimal, InvalidOperation
import re
from typing import Any, Iterable, Mapping


CONTRACT_VERSION = 1
CONTRACT_KEY = "stockCountContract"
EXPECTED_BUCKET_COLUMNS = {"age": "库存库龄桶", "expiry": "库存效期桶"}
ALLOWED_STOCK_COLUMNS = {"库存数量", "可用数量"}
ALLOWED_VALUE_COLUMNS = {
    "age": {"库龄天数"},
    "expiry": {"距到期天数", "临期天数"},
}


def _number(value: Any) -> Decimal | None:
    try:
        value = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return None
    return value if value.is_finite() else None


def positive_balance(value: Any) -> bool:
    number = _number(value)
    return number is not None and number > 0


def _semantic_column(value: Any) -> str:
    return re.sub(r"[（(].*?[）)]", "", str(value)).strip()


def bucket_label(kind: str, value: Any) -> str | None:
    number = _number(value)
    if number is None:
        return None
    if kind == "age":
        return "不超过30天" if number <= 30 else "31–90天" if number <= 90 else "91–180天" if number <= 180 else "超过180天"
    if kind == "expiry":
        return "已到期" if number <= 0 else "7天内" if number <= 7 else "8–30天" if number <= 30 else "超过30天"
    return None


def contract(kind: str, stock_column: str, value_column: str, bucket_column: str) -> dict[str, Any]:
    if (not all(isinstance(value, str) and value for value in (kind, stock_column, value_column, bucket_column))
            or kind not in EXPECTED_BUCKET_COLUMNS or _semantic_column(stock_column) not in ALLOWED_STOCK_COLUMNS
            or _semantic_column(value_column) not in ALLOWED_VALUE_COLUMNS[kind]
            or bucket_column != EXPECTED_BUCKET_COLUMNS[kind]
            or len({stock_column, value_column, bucket_column}) != 3):
        raise ValueError("unsupported stock bucket")
    return {"version": CONTRACT_VERSION, "kind": "batch_count", "bucketKind": kind,
            "stockColumn": stock_column, "valueColumn": value_column, "bucketColumn": bucket_column}


def prepare_rows(rows: Iterable[Mapping[str, Any]], metadata: Mapping[str, Any]) -> tuple[tuple[dict[str, Any], ...], str | None]:
    if not isinstance(metadata, Mapping) or metadata.get("version") != CONTRACT_VERSION or metadata.get("kind") != "batch_count":
        return (), None
    kind = metadata.get("bucketKind")
    stock_column = metadata.get("stockColumn")
    value_column = metadata.get("valueColumn")
    bucket_column = metadata.get("bucketColumn")
    if not all(isinstance(value, str) and value for value in (kind, stock_column, value_column, bucket_column)):
        return (), None
    if (_semantic_column(stock_column) not in ALLOWED_STOCK_COLUMNS
            or _semantic_column(value_column) not in ALLOWED_VALUE_COLUMNS.get(kind, set())
            or bucket_column != EXPECTED_BUCKET_COLUMNS.get(kind)
            or len({stock_column, value_column, bucket_column}) != 3):
        return (), None
    output: list[dict[str, Any]] = []
    for raw in rows:
        if not isinstance(raw, Mapping) or bucket_column in raw:
            return (), None
        row = dict(raw)
        balance = _number(row.get(stock_column))
        bucket = bucket_label(kind, row.get(value_column))
        if balance is None or balance <= 0 or bucket is None:
            continue
        row[bucket_column] = bucket
        output.append(row)
    return tuple(output), bucket_column


__all__ = ["CONTRACT_KEY", "CONTRACT_VERSION", "EXPECTED_BUCKET_COLUMNS", "bucket_label", "contract", "positive_balance", "prepare_rows"]
