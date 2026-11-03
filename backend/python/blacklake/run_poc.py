"""Run the authorized seven-day PoC into the isolated local test destination."""
import base64
import argparse
import copy
import json
import logging
import os
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import psycopg2
import redis

from .client import BlacklakeConnector
from .contracts import BlacklakeError, Settings, load_contract
from .coordination import RedisCoordination
from .metrics import production_bi
from .secrets import load_local_secret, save_local_secret
from .storage import PostgresStore
from .sync import Synchronizer
from .acceptance import audit_store
from .local_environment import cache_url, apply_source_binding


def main():
    parser = argparse.ArgumentParser(description="Local test-only seven-day PoC")
    parser.add_argument("--checks-only", action="store_true", help="Reuse successful sync evidence; rerun reconciliation only")
    parser.add_argument("--refresh-plan-cohort", action="store_true", help="Refresh plan-start cohort and only newly discovered parent tasks")
    parser.add_argument("--refresh-report-flow", action="store_true", help="Refresh report-end flow and its newly required parents")
    args = parser.parse_args()
    if sum((args.checks_only, args.refresh_plan_cohort, args.refresh_report_flow)) > 1:
        raise BlacklakeError("ONE_REFRESH_MODE_REQUIRED")
    partial_refresh = args.refresh_plan_cohort or args.refresh_report_flow
    for name in ("httpx", "httpcore", "redis"):
        logging.getLogger(name).disabled = True
    directory = Path(os.environ["LOCALAPPDATA"]) / "Cretas/Blacklake"
    db = load_local_secret(directory / "test-database.dpapi")
    if db.get("initialization_complete") is not True:
        raise BlacklakeError("TEST_DATABASE_READINESS_NOT_VERIFIED")
    login = load_local_secret(directory / "login-response.dpapi")
    if login.get("statusCode") != 200:
        raise BlacklakeError("SUCCESSFUL_LOGIN_EVIDENCE_REQUIRED")
    encoded = login["data"].split(".")[1]
    claims = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
    os.environ["BLACKLAKE_TENANT_ID"] = "BLACKLAKE_POC_TEST"
    apply_source_binding()
    settings = Settings.from_env()
    if settings.source_factory_id != str(claims["orgId"]):
        raise BlacklakeError("EXPECTED_SOURCE_FACTORY_MISMATCH")
    contract = load_contract(str(Path(__file__).parent / "verified_contract.json"))
    # This entry point can only connect to the password-protected local PoC database.
    from psycopg2.extensions import parse_dsn
    destination = parse_dsn(db["runtime_dsn"])
    if destination.get("host") != "127.0.0.1" or destination.get("dbname") != "blacklake_poc":
        raise BlacklakeError("POC_DESTINATION_REJECTED")
    cache = redis.Redis.from_url(cache_url())
    coordination = RedisCoordination(cache, settings.account_key)
    client = BlacklakeConnector(settings, contract, coordination)
    connection = psycopg2.connect(db["runtime_dsn"])
    store = PostgresStore(connection, settings)
    end = datetime.now(ZoneInfo("Asia/Shanghai")).replace(hour=0, minute=0, second=0, microsecond=0)
    start = end - timedelta(days=7)
    previous = None
    if args.checks_only or partial_refresh:
        previous = load_local_secret(directory / "poc-report.dpapi")
        if previous.get("period_start") != start.isoformat() or previous.get("period_end") != end.isoformat():
            raise BlacklakeError("CHECKS_WINDOW_MUST_MATCH_SAVED_EVIDENCE")
    receipts = []
    previous_project_ids = {key for key, _ in store.records("project")}

    def run(resource, selected_contract, extra=None):
        if args.checks_only:
            return
        if args.refresh_plan_cohort and (resource not in ("project", "task")
                                        or selected_contract["resources"][resource]["window_basis"] == "updated"):
            return
        if args.refresh_report_flow and not (
                resource in ("project", "task") and selected_contract["resources"][resource]["window_basis"] == "parent_snapshot"
                or resource == "output" and selected_contract["resources"][resource]["window_basis"] == "event"):
            return
        receipt = Synchronizer(client, store, selected_contract).sync_window(resource, start, end, extra=extra)
        receipts.append({"resource": resource, "records": receipt["data"]["records"],
                         "basis": selected_contract["resources"][resource]["window_basis"]})
        print(json.dumps({"success": True, "phase": "sync", **receipts[-1]}), flush=True)

    try:
        with coordination.lease():
            if not coordination.get_token():
                coordination.set_token(login["data"], settings.source_factory_id)
            initial = copy.deepcopy(contract)
            initial["resources"]["project"].update(window_basis="plan", updated_only_verified=False,
                                                     window_start_key="planStartTimeGte", window_end_key="planStartTimeLte")
            initial["resources"]["output"].update(window_basis="event", updated_only_verified=False,
                                                    window_start_key="endtimeofReportGte", window_end_key="endtimeofReportLte")
            run("project", initial)
            run("output", initial)
            run("stock_order", contract, {"orderType": 10})
            run("stock_order", contract, {"orderType": 20})
            # These calls validate every returned source update timestamp, then checkpoint.
            run("project", contract)
            run("output", contract)
            projects = dict(store.records("project"))
            known_codes = {r.get("project_code") for r in projects.values()}
            referenced_codes = {r.get("project_code") for _, r in store.records("output")}
            for _, order in store.records("stock_order"):
                referenced_codes.update(d.get("associated_order_code") for d in order.get("details", [])
                                        if str(d.get("associated_order_type")) == "10")
            missing = sorted(c for c in referenced_codes - known_codes if c)
            closure = copy.deepcopy(contract)
            closure["resources"]["project"].update(window_basis="parent_snapshot", updated_only_verified=False,
                                                     required_any=[["projectCodes"]])
            for offset in range(0, len(missing), 50):
                run("project", closure, {"projectCodes": missing[offset:offset + 50]})
            ids = [int(key.split(":", 1)[1]) for key, _ in store.records("project")
                   if not partial_refresh or key not in previous_project_ids]
            for offset in range(0, len(ids), 50):
                run("task", contract, {"projectIds": ids[offset:offset + 50]})
            project_rows, task_rows = dict(store.records("project")), dict(store.records("task"))
            output_rows, stock_rows = dict(store.records("output")), dict(store.records("stock_order"))
            project_ids = {key.split(":", 1)[1] for key in project_rows}
            task_ids = {key.split(":", 1)[1] for key in task_rows}
            links = {"outputs": len(output_rows),
                     "missing_project": sum(str(r.get("project_id")) not in project_ids for r in output_rows.values()),
                     "missing_task": sum(str(r.get("task_id")) not in task_ids for r in output_rows.values())}
            reconciliations = (previous["reconciliation"] if partial_refresh else {
                resource: Synchronizer(client, store, contract).reconcile_window(resource, start, end)["data"]
                for resource in ("project", "output")})
            bi = production_bi(project_rows.values(), task_rows.values(), output_rows.values(), stock_rows.values(), start, end)
            audit = audit_store(store, contract)
            report = {"period_start": start.isoformat(), "period_end": end.isoformat(), "days": 7,
                      "scope": "BLACKLAKE_POC_TEST isolated local destination; one authenticated source org",
                      "receipts": receipts, "links": links, "reconciliation": reconciliations,
                      "bi": bi, "audit": audit, "production_business_mutations": 0}
            checks_passed = audit["passed"] and not (links["missing_project"] or links["missing_task"])
            checks_passed = checks_passed and all(
                not (r["changed_records"] or r["missing_locally"] or r["not_returned_by_source"])
                for r in reconciliations.values())
            report["checks_passed"] = checks_passed
            save_local_secret(directory / "poc-report.dpapi", report)
            # Only aggregate counts and reconciliation results are suitable for logs.
            summary = {"success": checks_passed, "days": 7, "stored": {
                "projects": len(project_rows), "tasks": len(task_rows),
                "outputs": len(output_rows), "stock_orders": len(stock_rows)},
                "links": links, "reconciliation": reconciliations,
                "audit": audit,
                "project_cohort": bi["project_cohort"], "report_flow_count": bi["report_flow"]["count"],
                "material_cost_coverage": {k: v for k, v in bi["material_cost"].items()
                                           if k not in ("signed_stock_movement_cost",)},
                "production_business_mutations": 0}
            print(json.dumps(summary), flush=True)
            return 0 if checks_passed else 1
    except Exception as exc:
        if client.last_business_error is not None:
            save_local_secret(directory / "last-business-error.dpapi", client.last_business_error)
        print(json.dumps({"success": False, "data": None,
                          "message": str(exc) if isinstance(exc, BlacklakeError) else "POC_ERROR"}), flush=True)
        return 1
    finally:
        client.close()
        cache.close()
        connection.close()


if __name__ == "__main__":
    raise SystemExit(main())
