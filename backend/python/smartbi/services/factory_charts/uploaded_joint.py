"""Explicit, tenant-scoped joins for uploaded production workbooks.

This module is deliberately independent from the upload loader.  A workbook
id is a user-selected authority; no filename, upload order, or hash is ever
used to discover related workbooks.  The output is a validated, one-row-per
production-batch frame suitable for the existing chart engine.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

import pandas as pd

from .engine import Dataset
from .metrics import number

JOINT_SOURCE = "uploaded-production-inspection-fused"
JOINT_FAMILY = "production.inspection_fused"
REQUIRED_ROLES = ("production", "inspection", "cost")


@dataclass(frozen=True)
class UploadedJoinSelection:
    """The only supported uploaded joint selection."""

    production_upload_id: int
    inspection_upload_id: int
    cost_upload_id: int

    def as_dict(self) -> dict[str, Any]:
        return {
            "uploadId": self.production_upload_id,
            "inspectionUploadId": self.inspection_upload_id,
            "costUploadId": self.cost_upload_id,
            "roles": {
                "production": self.production_upload_id,
                "inspection": self.inspection_upload_id,
                "cost": self.cost_upload_id,
            },
        }


def parse_selection(value: Mapping[str, Any]) -> UploadedJoinSelection:
    """Parse the explicit API object and reject ambiguous/legacy forms."""
    if not isinstance(value, Mapping):
        raise ValueError("uploaded joint selection must be an object")

    def pick(*names: str) -> Any:
        for name in names:
            if name in value:
                return value[name]
        return None

    raw = [pick("uploadId", "upload_id"),
           pick("inspectionUploadId", "inspection_upload_id"),
           pick("costUploadId", "cost_upload_id")]
    if any(isinstance(item, bool) or not isinstance(item, int) or item <= 0 for item in raw):
        raise ValueError("uploadId、inspectionUploadId、costUploadId必须是正整数")
    ids = [int(item) for item in raw]
    if len(set(ids)) != 3:
        raise ValueError("三个上传ID必须互不相同")
    return UploadedJoinSelection(*ids)


def validate_upload_records(records: Sequence[Mapping[str, Any]], *, role: str,
                            factory_id: str, status: str | None = None) -> None:
    """Validate the trusted metadata returned by upload storage.

    Callers must pass storage metadata, not user-provided labels.  This helper
    is intentionally strict so a missing tenant/status cannot be interpreted
    as a valid uploaded source.
    """
    if role not in REQUIRED_ROLES:
        raise ValueError("unsupported uploaded joint role")
    if not records:
        raise ValueError(f"{role}上传不存在或不属于当前工厂")
    for record in records:
        if str(record.get("factory_id", record.get("factoryId", ""))) != str(factory_id):
            raise ValueError(f"{role}上传不属于当前工厂")
        state = str(record.get("status", status or "")).lower()
        if state not in {"completed", "complete", "ready", "succeeded", "success"}:
            raise ValueError(f"{role}上传尚未完成")


def _text(row: Mapping[str, Any], key: str) -> str | None:
    value = row.get(key)
    if value is None or pd.isna(value):
        return None
    value = str(value).strip()
    return value or None


def _production_row(row: Mapping[str, Any]) -> dict[str, Any] | None:
    batch, product, unit = (_text(row, key) for key in ("来源主键", "产品ID", "单位"))
    output, good = (number(row.get(key)) for key in ("产量", "良品数"))
    day = pd.to_datetime(row.get("日期"), errors="coerce")
    if not all((batch, product, unit)) or pd.isna(day) or output is None or output <= 0:
        return None
    if good is None or good < 0 or good > output:
        return None
    return {**dict(row), "来源主键": batch, "产品ID": product, "单位": unit,
            "日期": day.strftime("%Y-%m-%d"), "产量": float(output),
            "良品数": float(good)}


def prepare_uploaded_join(production: Dataset | pd.DataFrame, inspection: Dataset | pd.DataFrame,
                          cost: Dataset | pd.DataFrame) -> tuple[pd.DataFrame | None, list[dict[str, Any]]]:
    """Validate and join explicitly selected uploaded sources.

    Inspection is many-to-one and is aggregated before joining.  Cost must be
    one row per production key; it is never multiplied by inspection rows.
    """
    def frame(item: Dataset | pd.DataFrame) -> pd.DataFrame:
        return item.frame.copy(deep=True) if isinstance(item, Dataset) else pd.DataFrame(item).copy(deep=True)

    p_rows, q_rows, c_rows = [f.to_dict("records") for f in (frame(production), frame(inspection), frame(cost))]
    gaps: list[dict[str, Any]] = []
    def gap(message: str) -> None:
        gaps.append({"id": JOINT_FAMILY, "title": "上传生产-质检-成本联合分析", "missing": [message]})

    invalid_production = Counter()
    p_valid = []
    for raw in p_rows:
        normalized = _production_row(raw)
        if normalized is None:
            invalid_production["生产行缺少完整日期、批次键、产品ID、单位、产量或良品数"] += 1
        else:
            p_valid.append(normalized)
    for reason, count in invalid_production.items():
        gap(f"{count}行生产事实无效：{reason}；未补零")
    production_census = []
    for raw in p_rows:
        key, product, unit = (_text(raw, name) for name in ("来源主键", "产品ID", "单位"))
        day = pd.to_datetime(raw.get("日期"), errors="coerce")
        if key and product and unit and not pd.isna(day):
            production_census.append({"来源主键": key, "产品ID": product, "单位": unit,
                                      "日期": day.strftime("%Y-%m-%d")})
    duplicates = {key for key, count in Counter(_text(row, "来源主键") for row in p_rows).items() if key and count > 1}
    if duplicates:
        gap(f"生产稳定批次键重复（{len(duplicates)}个），拒绝重复累计")

    quality: dict[tuple[str, str], dict[str, Any]] = defaultdict(lambda: {"检验数": 0.0, "合格数": 0.0, "质检不良数": 0.0, "质检记录数": 0})
    bad_quality: set[tuple[str | None, str | None]] = set()
    production_products = defaultdict(set)
    production_units = defaultdict(set)
    for row in p_valid:
        production_products[row["来源主键"]].add(row["产品ID"])
        production_units[row["来源主键"]].add(row["单位"])
    contaminated_quality_batches: set[str] = set()
    orphan_quality_batches: set[str] = set()
    for raw in q_rows:
        batch, product = _text(raw, "生产批次ID"), _text(raw, "产品ID")
        if batch and batch not in production_products:
            orphan_quality_batches.add(batch)
        elif batch and (not product or product not in production_products[batch]):
            contaminated_quality_batches.add(batch)
    if contaminated_quality_batches:
        gap(f"质检已知批次产品ID冲突（{len(contaminated_quality_batches)}个批次），整批拒绝")
    if orphan_quality_batches:
        gap(f"质检存在{len(orphan_quality_batches)}个孤立批次，未关联生产且未混入其他批次")
    quality_id_counts = Counter(_text(raw, "检验ID") for raw in q_rows)
    duplicate_quality_ids = {identifier for identifier, count in quality_id_counts.items() if identifier and count > 1}
    invalid_quality = 0
    for row in q_rows:
        key = (_text(row, "生产批次ID"), _text(row, "产品ID"))
        inspection_id = _text(row, "检验ID")
        if not inspection_id or inspection_id in duplicate_quality_ids:
            bad_quality.add(key)
            invalid_quality += 1
            continue
        sample, passed, failed = (number(row.get(col)) for col in ("检验数", "合格数", "不良数"))
        if not all(key) or sample is None or sample <= 0 or passed is None or not 0 <= passed <= sample or failed is None or failed < 0 or abs(sample - passed - failed) > 1e-6:
            bad_quality.add(key)
            invalid_quality += 1
            continue
        sample_unit = _text(row, "单位")
        inspected_date = pd.to_datetime(row.get("日期"), errors="coerce")
        if sample_unit != "样本" or pd.isna(inspected_date):
            bad_quality.add(key)
            invalid_quality += 1
            continue
        day = inspected_date.strftime("%Y-%m-%d")
        current = quality[key]
        current["首次检验日期"] = min(day, current.get("首次检验日期", day))
        current["末次检验日期"] = max(day, current.get("末次检验日期", day))
        current.setdefault("检验来源ID", []).append(inspection_id)
        quality[key]["检验数"] += float(sample); quality[key]["合格数"] += float(passed); quality[key]["质检不良数"] += float(failed); quality[key]["质检记录数"] += 1
    if invalid_quality:
        gap(f"{invalid_quality}行质检存在缺键、重复身份、无效日期、样本单位或计数；未静默丢弃")

    costs: dict[tuple[str, str, str], dict[str, Any]] = {}
    duplicate_cost: set[tuple[str | None, str | None, str | None]] = set()
    contaminated_cost_batches: set[str] = set()
    orphan_cost_batches: set[str] = set()
    for raw in c_rows:
        batch, product, unit = (_text(raw, col) for col in ("生产批次ID", "产品ID", "单位"))
        if batch and batch not in production_products:
            orphan_cost_batches.add(batch)
        elif batch and (not product or not unit or product not in production_products[batch]
                        or unit not in production_units[batch]):
            contaminated_cost_batches.add(batch)
    if contaminated_cost_batches:
        gap(f"成本已知批次产品ID或单位冲突（{len(contaminated_cost_batches)}个批次），整批拒绝")
    if orphan_cost_batches:
        gap(f"成本存在{len(orphan_cost_batches)}个孤立批次，未关联生产且未混入其他批次")
    components = ("材料成本", "人工成本", "设备成本", "其他成本")
    invalid_cost = 0
    for row in c_rows:
        # Cost exports use the registered production-batch identifier name.
        key = tuple(_text(row, col) for col in ("生产批次ID", "产品ID", "单位"))
        vals = [number(row.get(col)) for col in (*components, "总成本")]
        currency = _text(row, "币种")
        normalized_currency = {"人民币": "CNY", "元": "CNY", "CNY": "CNY"}.get((currency or "").upper())
        if normalized_currency != "CNY":
            duplicate_cost.add(key)
            invalid_cost += 1
            continue
        if not all(key) or any(v is None or v < 0 for v in vals) or abs(sum(vals[:4]) - vals[4]) > 1e-6:
            duplicate_cost.add(key)
            invalid_cost += 1
            continue
        if key in costs:
            duplicate_cost.add(key)
        costs[key] = {**dict(row), **dict(zip(components + ("总成本",), map(float, vals))), "币种": "CNY"}
    if invalid_cost:
        gap(f"{invalid_cost}行成本存在缺键、无效币种、金额或分项对账；未静默丢弃")

    joined: list[dict[str, Any]] = []
    rejected: Counter[str] = Counter()
    for row in p_valid:
        batch, product, unit = row["来源主键"], row["产品ID"], row["单位"]
        key = (batch, product)
        cost_key = (batch, product, unit)
        if batch in duplicates:
            continue
        if batch in contaminated_quality_batches or batch in contaminated_cost_batches:
            rejected["相关上传同批次存在冲突记录"] += 1; continue
        if key in bad_quality:
            rejected["质检存在不完整或不一致的样本"] += 1; continue
        q = quality.get(key)
        c = costs.get(cost_key)
        if q is None:
            rejected["缺少同批次同产品质检"] += 1; continue
        if c is None or cost_key in duplicate_cost:
            rejected["缺少唯一且逐行对账的成本"] += 1; continue
        # Keep production period/product labels authoritative.  Only validated
        # cost numerics and explicit cost provenance cross the join boundary.
        joined.append({**row, **q,
                       **{column: c[column] for column in (*components, "总成本")},
                       "生产批次ID": batch, "成本来源主键": batch, "币种": "CNY"})
    for reason, count in rejected.items():
        gap(f"{count}个生产批次：{reason}；未补零")
    if not joined:
        return None, gaps or [{"id": JOINT_FAMILY, "title": "上传生产-质检-成本联合分析", "missing": ["没有可关联的完整上传批次"]}]
    result = pd.DataFrame(joined)
    result.attrs["joint_currency"] = "CNY"
    result.attrs["joint_production_scope"] = production_census
    totals = Counter((_text(row, "产品ID"), _text(row, "单位")) for row in p_rows if _text(row, "产品ID") and _text(row, "单位"))
    covered = Counter((row["产品ID"], row["单位"]) for row in joined)
    result.attrs["joint_coverage"] = {(p, u): {"totalBatches": n, "coveredBatches": covered[(p, u)]} for (p, u), n in totals.items()}
    return result, gaps


def dataset_for_join(selection: UploadedJoinSelection, frame: pd.DataFrame) -> Dataset:
    """Attach immutable role/id metadata consumed by detail and conversation scopes."""
    if frame.attrs.get("joint_currency") != "CNY":
        raise ValueError("联合成本币种未通过CNY校验")
    dataset = Dataset(frame, JOINT_SOURCE + " · 显式上传生产/质检/成本", financial=True, currency="CNY")
    dataset.metadata = {"sourceKey": JOINT_SOURCE, "roles": selection.as_dict()["roles"], "selection": selection.as_dict()}
    return dataset


__all__ = ["UploadedJoinSelection", "parse_selection", "validate_upload_records", "prepare_uploaded_join", "dataset_for_join", "JOINT_SOURCE", "JOINT_FAMILY"]
