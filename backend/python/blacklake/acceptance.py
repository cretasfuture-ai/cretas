"""Independent raw-field comparisons and relation checks; no raw values in receipts."""
from decimal import Decimal

from .sync import NUMBERS


def audit_store(store, contract):
    compared, differences, missing_raw, zero_cost, cost_values = 0, 0, 0, 0, 0
    with store.transaction() as cur:
        cur.execute("SELECT r.resource,r.normalized,raw.payload FROM blacklake.record r "
                    "LEFT JOIN blacklake.raw_record raw ON r.tenant_id=raw.tenant_id "
                    "AND r.source_factory_id=raw.source_factory_id AND r.resource=raw.resource "
                    "AND r.external_id=raw.external_id AND r.content_hash=raw.content_hash "
                    "WHERE r.tenant_id=%s AND r.source_factory_id=%s", store.scope)
        records = cur.fetchall()
    for resource, normalized, raw in records:
        if raw is None:
            missing_raw += 1
            continue
        fields = contract["resources"][resource]["fields"]
        for target, source in fields.items():
            if target not in NUMBERS:
                continue
            # The live verified contract uses direct scalar keys. This comparison
            # deliberately does not reuse the normalizer or its path traversal.
            source_value, actual = raw.get(source), normalized.get(target)
            compared += 1
            if source_value is None:
                differences += actual is not None
            else:
                differences += actual is None or Decimal(str(source_value)) != Decimal(actual)
        if resource == "stock_order":
            source_details = {str(row["id"]): row for row in raw["orderDetails"]}
            for detail in normalized.get("details", []):
                source = source_details[detail["detail_id"]]
                for target, source_key in contract["resources"][resource]["details"]["fields"].items():
                    if target not in NUMBERS:
                        continue
                    expected, actual = source.get(source_key), detail.get(target)
                    compared += 1
                    differences += (actual is not None if expected is None
                                    else actual is None or Decimal(str(expected)) != Decimal(actual))
                if detail.get("material_cost_signed") is not None:
                    cost_values += 1
                    zero_cost += Decimal(detail["material_cost_signed"]) == 0
    projects = {key.split(":", 1)[1]: value for key, value in store.records("project")}
    tasks = {key.split(":", 1)[1]: value for key, value in store.records("task")}
    relations = {"missing_project": 0, "missing_task": 0, "task_project_mismatch": 0,
                 "task_process_mismatch": 0, "task_product_mismatch": 0}
    for _, row in store.records("output"):
        project, task = projects.get(str(row.get("project_id"))), tasks.get(str(row.get("task_id")))
        relations["missing_project"] += project is None
        relations["missing_task"] += task is None
        if task:
            relations["task_project_mismatch"] += str(task.get("project_id")) != str(row.get("project_id"))
            relations["task_process_mismatch"] += str(task.get("process_id")) != str(row.get("process_id"))
            relations["task_product_mismatch"] += str(task.get("product_id")) != str(row.get("product_id"))
    return {"records": len(records), "numeric_fields_compared": compared,
            "numeric_mismatches": differences, "records_missing_raw": missing_raw,
            "relation_checks": relations, "reported_cost_values": cost_values,
            "explicit_source_zero_cost_values": zero_cost,
            "passed": not (differences or missing_raw or any(relations.values()))}
