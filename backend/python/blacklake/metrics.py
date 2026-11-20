"""Deterministic metrics at one task grain; never sum project/task/output amounts."""
from decimal import Decimal
from collections import Counter, defaultdict
from datetime import datetime


def task_metrics(row):
    def number(key):
        value = row.get(key)
        return Decimal(value) if value is not None else None

    good, defective, planned = number("good"), number("defective"), number("planned")
    missing = [k for k in ("good", "defective", "planned", "duration_seconds", "labor_cost")
               if row.get(k) is None]
    denominator = good + defective if good is not None and defective is not None else None
    # No cross-unit/process aggregation; zero denominators remain undefined.
    return {
        "good": str(good) if good is not None else None,
        "good_rate": str(good / denominator) if denominator else None,
        "defective_rate": str(defective / denominator) if denominator else None,
        "plan_attainment": str(good / planned) if good is not None and planned else None,
        "duration_seconds": row.get("duration_seconds"),
        "labor_cost": row.get("labor_cost"),
        "approval_status": row.get("approval_status"),
        "unit": row.get("unit"),
        "missing_fields": missing,
        "scope": "single_task_reported_quantities_not_approved_final_output",
    }


def cost_coverage(rows):
    """Coverage counts distinguish missing, zero and signed stock movements."""
    rows = list(rows)
    present = [Decimal(r["material_cost_signed"]) for r in rows
               if r.get("material_cost_signed") is not None]
    return {"records": len(rows), "priced_records": len(present),
            "coverage": str(Decimal(len(present)) / len(rows)) if rows else None,
            "signed_stock_movement_cost": str(sum(present)) if present else None,
            "is_manufacturing_cost": False,
            "requires_verified_order_link_and_consumption_direction": True}


def production_bi(projects, tasks, outputs, stock_orders, start, end):
    """Snapshots and period flows are separate; money is never labelled profit."""
    projects, tasks, outputs, stock_orders = map(list, (projects, tasks, outputs, stock_orders))
    period_projects = [r for r in projects if r.get("event_time") and start <= datetime.fromisoformat(r["event_time"]) < end]
    period_outputs = [r for r in outputs if r.get("event_time") and start <= datetime.fromisoformat(r["event_time"]) < end]
    statuses = Counter(str(r.get("status")) for r in period_projects)
    eligible = [r for r in period_projects if str(r.get("status")) in ("0", "10", "20")]
    known_deadline, late = 0, 0
    for row in eligible:
        if not row.get("planned_end"):
            continue
        actual = row.get("actual_end")
        if str(row.get("status")) == "20" and not actual:
            continue
        known_deadline += 1
        observed = datetime.fromisoformat(actual) if actual else end
        late += observed > datetime.fromisoformat(row["planned_end"])
    quantities = defaultdict(lambda: {"approved_good": Decimal(0), "approved_defective": Decimal(0),
                                       "known_records": 0, "missing_records": 0})
    for row in period_outputs:
        if str(row.get("approval_status")) != "10":
            continue
        # Output is a process-level flow, never a factory final-product amount.
        key = (row.get("product_id"), row.get("process_id"), row.get("unit"))
        if any(value is None or value == "" for value in key):
            key = (None, None, None)
        group = quantities[key]
        if key[0] is None or row.get("good") is None or row.get("defective") is None:
            group["missing_records"] += 1
        else:
            group["approved_good"] += Decimal(row["good"])
            group["approved_defective"] += Decimal(row["defective"])
            group["known_records"] += 1
    quality = []
    for (product, process, unit), group in quantities.items():
        denominator = group["approved_good"] + group["approved_defective"]
        quality.append({"product_id": product, "process_id": process, "unit": unit,
                        **{k: str(v) if isinstance(v, Decimal) else v for k, v in group.items()},
                        "good_rate": str(group["approved_good"] / denominator) if denominator else None})
    material_lines, unlinked, unapproved = [], 0, 0
    for order in stock_orders:
        if not order.get("event_time") or not start <= datetime.fromisoformat(order["event_time"]) < end:
            continue
        if order.get("biz_type") not in (30, 32, "30", "32"):
            continue
        if str(order.get("approval_status")) != "1":
            unapproved += len(order.get("details", []))
            continue
        for detail in order.get("details", []):
            if str(detail.get("associated_order_type")) != "10" or not detail.get("associated_order_code"):
                unlinked += 1
                continue
            material_lines.append(detail)
    plans = defaultdict(lambda: {"planned": Decimal(0), "actual": Decimal(0),
                                 "records": 0, "planned_missing": 0, "actual_missing": 0})
    for row in period_projects:
        key = (row.get("product_id"), row.get("unit"))
        group = plans[key]
        group["records"] += 1
        for field in ("planned", "actual"):
            if row.get(field) is None or not all(key):
                group[field + "_missing"] += 1
            else:
                group[field] += Decimal(row[field])
    process_hours = defaultdict(lambda: {"seconds": Decimal(0), "known_tasks": 0, "missing_tasks": 0})
    for row in tasks:
        group = process_hours[row.get("process_id")]
        if row.get("duration_seconds") is None:
            group["missing_tasks"] += 1
        else:
            group["seconds"] += Decimal(row["duration_seconds"])
            group["known_tasks"] += 1
    return {
        "project_cohort": {"basis": "plan_start_in_period", "count": len(period_projects), "statuses": dict(statuses),
                           "completion_rate": str(Decimal(statuses["20"]) / len(eligible)) if eligible else None,
                           "delay_rate": str(Decimal(late) / known_deadline) if known_deadline else None,
                           "deadline_covered": known_deadline, "deadline_missing": len(eligible) - known_deadline},
        "project_quantities": [
            {"product_id": product, "unit": unit, **{
                k: (None if group[k + "_missing"] == group["records"] else str(v))
                if k in ("planned", "actual") else v for k, v in group.items()}}
            for (product, unit), group in plans.items()],
        "task_snapshot": {"count": len(tasks), "status_counts": dict(Counter(str(r.get("status")) for r in tasks)),
                          "duration_known": sum(r.get("duration_seconds") is not None for r in tasks),
                          "unit_missing": sum(not r.get("unit") for r in tasks),
                          "cumulative_time_by_process": [
                              {"process_id": process, **{
                                  k: (str(v) if group["known_tasks"] else None) if k == "seconds" else v
                                  for k, v in group.items()}}
                              for process, group in process_hours.items()],
                          "scope": "tasks_of_loaded_projects_cumulative_not_period_hours"},
        "report_flow": {"count": len(period_outputs), "approval_counts": dict(Counter(str(r.get("approval_status")) for r in period_outputs)),
                        "quality_by_product_process_unit": quality, "is_final_factory_output": False},
        "material_cost": {**cost_coverage(material_lines), "unlinked_lines": unlinked,
                          "unapproved_lines": unapproved, "consumption_sign": "negative_of_signed_movement",
                          "scope": "approved_biz_types_30_32_with_work_order_link"},
        "complete_manufacturing_cost": None, "gross_profit": None,
        "limitations": ["No inferred deletions", "Task hours and wages are cumulative snapshots",
                        "Process output is not summed across processes", "Unknown data stays missing"]}
