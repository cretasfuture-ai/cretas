"""Bounded window sync. No deletion inferred from absent query results."""
import hashlib
import json
import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from zoneinfo import ZoneInfo

from .contracts import BlacklakeError, at

NUMBERS = {"planned", "actual", "good", "defective", "approved_good", "duration_seconds",
           "labor_cost", "material_cost_signed", "cost_unit_price", "quantity", "stock_change"}
TEXT_FIELDS = {"project_id", "project_code", "task_id", "product_id", "process_id", "unit", "status",
               "approval_status", "planned_end", "actual_end", "associated_order_code",
               "associated_order_type", "associated_order_id", "deleted", "event_time",
               "order_type", "biz_type", "detail_id"}


def timestamp(value, source_timezone=None):
    if not isinstance(value, str):
        raise BlacklakeError("TIMESTAMP_CONTRACT_UNVERIFIED")
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise BlacklakeError("INVALID_TIMESTAMP") from None
    if result.tzinfo is None:
        if not source_timezone:
            raise BlacklakeError("TIMESTAMP_TIMEZONE_REQUIRED")
        result = result.replace(tzinfo=ZoneInfo(source_timezone))
    return result.astimezone(timezone.utc)


def normalize(row, spec, settings, *, factory_verified=False):
    if spec.get("factory_path"):
        if str(at(row, spec["factory_path"])) != settings.source_factory_id:
            raise BlacklakeError("SOURCE_FACTORY_MISMATCH")
    elif not (factory_verified and spec.get("scope_evidence") == "login"):
        raise BlacklakeError("SOURCE_FACTORY_NOT_PROVEN")
    external_id = at(row, spec["id_path"])
    if isinstance(external_id, bool) or not isinstance(external_id, (str, int)) or str(external_id) == "":
        raise BlacklakeError("EXTERNAL_ID_MISSING")
    updated = timestamp(at(row, spec["updated_path"]), spec.get("source_timezone"))
    result = {}
    for target, path in spec["fields"].items():
        if target not in NUMBERS | TEXT_FIELDS:
            raise BlacklakeError("UNAPPROVED_NORMALIZED_FIELD")
        try:
            value = at(row, path)
        except BlacklakeError:
            value = None
        if target in NUMBERS and value is not None:
            try:
                amount = Decimal(str(value))
                if not amount.is_finite() or (amount < 0 and target not in {"material_cost_signed", "stock_change"}):
                    raise InvalidOperation
                value = str(amount)
            except (InvalidOperation, ValueError):
                raise BlacklakeError("INVALID_NUMERIC_FIELD") from None
        if target in TEXT_FIELDS and isinstance(value, (dict, list)):
            raise BlacklakeError("INVALID_SCALAR_FIELD")
        if target in {"planned_end", "actual_end", "event_time"} and value is not None:
            value = timestamp(value, spec.get("source_timezone")).isoformat() if value != "" else None
        result[target] = value
    if spec.get("details"):
        detail_spec = spec["details"]
        children = at(row, detail_spec["path"])
        if not isinstance(children, list):
            raise BlacklakeError("STOCK_DETAILS_SHAPE_INVALID")
        details, ids = [], set()
        for child in children:
            enriched = dict(child, _parent_updated=updated.isoformat())
            child_spec = dict(detail_spec, factory_path=None, scope_evidence="login",
                              updated_path="_parent_updated")
            detail_id, _, mapped = normalize(enriched, child_spec, settings, factory_verified=True)
            if detail_id in ids:
                raise BlacklakeError("DUPLICATE_STOCK_DETAIL_ID")
            ids.add(detail_id)
            details.append(dict(mapped, detail_id=detail_id))
        result["details"] = details
    return str(external_id), updated, result


class Synchronizer:
    def __init__(self, client, store, contract):
        self.client, self.store, self.contract = client, store, contract
        self.contract_hash = hashlib.sha256(json.dumps(contract, sort_keys=True).encode()).hexdigest()

    def sync_window(self, resource, start, end, *, incremental=False, extra=None):
        if start.tzinfo is None or end.tzinfo is None or start >= end:
            raise BlacklakeError("INVALID_WINDOW")
        spec = self.contract["resources"][resource]
        if spec.get("mapping_verified") is not True or spec.get("window_verified") is not True:
            raise BlacklakeError("MAPPING_OR_WINDOW_NOT_VERIFIED")
        if end - start > timedelta(days=min(spec["max_window_days"], 7)):
            raise BlacklakeError("WINDOW_TOO_LARGE")
        if end > datetime.now(timezone.utc):
            raise BlacklakeError("FUTURE_WINDOW_REJECTED")
        if incremental and spec.get("updated_only_verified") is not True:
            raise BlacklakeError("UPDATED_ONLY_NOT_VERIFIED")
        for alternatives in spec.get("required_any", []):
            query = dict(spec.get("base_body", {}), **(extra or {}))
            if not any(query.get(key) for key in alternatives):
                raise BlacklakeError("REQUIRED_PARENT_FILTER_MISSING")
        if extra and any(k in extra for k in (
                spec["page_key"], spec["size_key"], spec["window_start_key"], spec["window_end_key"])):
            raise BlacklakeError("RESERVED_QUERY_PARAMETER")
        cursor_key = resource
        identity_prefix = resource
        if resource == "stock_order":
            order_type = (extra or {}).get("orderType")
            if order_type not in (10, 20):
                raise BlacklakeError("STOCK_ORDER_TYPE_REQUIRED")
            cursor_key += ":" + str(order_type)
            identity_prefix = cursor_key
        filters = {k: v for k, v in (extra or {}).items() if k != "orderType"}
        if filters:
            cursor_key += ":filter:" + hashlib.sha256(json.dumps(filters, sort_keys=True).encode()).hexdigest()
        previous = self.store.cursor(cursor_key) if spec.get("window_basis") == "updated" else None
        if previous and previous[1] != self.contract_hash:
            raise BlacklakeError("CONTRACT_CHANGED_REBASE_REQUIRED")
        if incremental and previous:
            start = previous[0] - timedelta(minutes=5)
            if end - start > timedelta(days=min(spec["max_window_days"], 7)):
                raise BlacklakeError("CATCH_UP_IN_BOUNDED_WINDOWS")
        if start >= end:
            raise BlacklakeError("WINDOW_NOT_AFTER_CURSOR")
        if incremental and spec.get("window_basis") != "updated":
            raise BlacklakeError("INCREMENTAL_WINDOW_MUST_USE_UPDATED_TIME")
        if previous and start > previous[0]:
            raise BlacklakeError("CURSOR_GAP_REJECTED")
        body = dict(spec.get("base_body", {}))
        body.update(extra or {})
        def wire_time(value):
            if spec.get("time_format") == "local_seconds":
                return value.astimezone(ZoneInfo(spec["source_timezone"])).strftime("%Y-%m-%d %H:%M:%S")
            return value.isoformat()

        if spec.get("window_basis") != "parent_snapshot":
            body[spec["window_start_key"]] = wire_time(start)
            body[spec["window_end_key"]] = wire_time(end)
        run_id, ids = str(uuid.uuid4()), set()
        try:
            with self.store.transaction() as cur:
                for rows in self.client.pages(resource, body):
                    for row in rows:
                        external_id, updated, normalized = normalize(
                            row, spec, self.client.settings,
                            factory_verified=getattr(self.client, "factory_verified", False))
                        if spec.get("window_basis") == "updated" and not start <= updated <= end:
                            raise BlacklakeError("UPSTREAM_UPDATED_FILTER_NOT_APPLIED")
                        if spec.get("window_basis") == "plan" and (
                                not normalized.get("event_time")
                                or not start <= timestamp(normalized["event_time"]) <= end):
                            raise BlacklakeError("UPSTREAM_PLAN_START_FILTER_NOT_APPLIED")
                        if external_id in ids:
                            raise BlacklakeError("PAGINATION_DUPLICATE_ID")
                        ids.add(external_id)
                        # Direction included in storage identity to avoid inlet/outlet collisions.
                        stored_id = identity_prefix + ":" + external_id
                        self.store.upsert(cur, resource, stored_id, updated, row, normalized)
                self.client.coordination.check_lease()
                if spec.get("updated_only_verified") is True and spec.get("window_basis") == "updated":
                    self.store.save_cursor(cur, cursor_key, end, self.contract_hash)
                self.store.run(cur, run_id, cursor_key, start, end, len(ids), "success")
        except Exception as exc:
            code = str(exc) if isinstance(exc, BlacklakeError) else "SYNC_STORAGE_OR_CONTRACT_ERROR"
            with self.store.transaction() as cur:
                self.store.run(cur, run_id, cursor_key, start, end, None, "failed", code)
            raise BlacklakeError(code) from None
        return {"success": True, "data": {"run_id": run_id, "records": len(ids)}, "message": "synced"}

    def reconcile_window(self, resource, start, end, extra=None):
        spec = self.contract["resources"][resource]
        if (spec.get("mapping_verified") is not True or spec.get("window_verified") is not True
                or spec.get("window_basis") != "updated"):
            raise BlacklakeError("RECONCILIATION_UPDATED_WINDOW_NOT_VERIFIED")
        if start.tzinfo is None or end.tzinfo is None or not start < end:
            raise BlacklakeError("INVALID_WINDOW")
        if end - start > timedelta(days=min(spec["max_window_days"], 7)):
            raise BlacklakeError("WINDOW_TOO_LARGE")
        body = dict(spec.get("base_body", {}))
        body.update(extra or {})
        direction = ""
        if resource == "stock_order":
            if body.get("orderType") not in (10, 20):
                raise BlacklakeError("STOCK_ORDER_TYPE_REQUIRED")
            direction = ":" + str(body["orderType"])
        for key, value in ((spec["window_start_key"], start), (spec["window_end_key"], end)):
            body[key] = (value.astimezone(ZoneInfo(spec["source_timezone"])).strftime("%Y-%m-%d %H:%M:%S")
                         if spec.get("time_format") == "local_seconds" else value.isoformat())
        source = {}
        for rows in self.client.pages(resource, body):
            for row in rows:
                external_id, updated, _ = normalize(
                    row, spec, self.client.settings,
                    factory_verified=getattr(self.client, "factory_verified", False))
                if not start <= updated <= end:
                    raise BlacklakeError("UPSTREAM_UPDATED_FILTER_NOT_APPLIED")
                key = resource + direction + ":" + external_id
                if key in source:
                    raise BlacklakeError("PAGINATION_DUPLICATE_ID")
                source[key] = hashlib.sha256(json.dumps(row, sort_keys=True, ensure_ascii=False,
                                                       separators=(',', ':')).encode()).hexdigest()
        local = self.store.snapshot(resource, start, end)
        if direction:
            local = {key: value for key, value in local.items() if key.startswith(resource + direction + ":")}
        common = set(source) & set(local)
        different = sum(source[key] != local[key] for key in common)
        return {"success": True, "data": {
            "source_records": len(source), "local_records": len(local),
            "matching_records": len(common) - different, "changed_records": different,
            "missing_locally": len(set(source) - set(local)),
            "not_returned_by_source": len(set(local) - set(source)),
            "deletion_completeness": "unverified",
        }, "message": "compared bounded update window; no records deleted"}
