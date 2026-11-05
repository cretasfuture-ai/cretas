"""All API-visible production data; source read-only, destination isolated test archive."""
import argparse
import json
import os
import uuid
from datetime import datetime, timezone
from pathlib import Path

import psycopg2

from .contracts import BlacklakeError
from .full_archive import ARCHIVE_VALIDATION_VERSION, Archive, digest
from .full_probe import context
from .secrets import load_local_secret, save_local_secret


def install(directory):
    saved = load_local_secret(directory / "test-database.dpapi")
    from psycopg2.extensions import parse_dsn
    runtime, admin = (parse_dsn(saved[key]) for key in ("runtime_dsn", "admin_dsn"))
    if any(runtime.get(key) != admin.get(key) for key in ("host", "port", "dbname")):
        raise BlacklakeError("ARCHIVE_MIGRATION_DESTINATION_MISMATCH")
    if admin.get("host") != "127.0.0.1" or admin.get("dbname") != "blacklake_poc":
        raise BlacklakeError("TEST_DESTINATION_REQUIRED")
    connection = psycopg2.connect(saved["admin_dsn"])
    try:
        with connection:
            with connection.cursor() as cur:
                cur.execute("SELECT to_regclass('blacklake.full_snapshot')")
                if cur.fetchone()[0] is None:
                    cur.execute((Path(__file__).parent / "migrations/002_full_snapshot.sql").read_text())
                cur.execute("GRANT SELECT,INSERT,UPDATE ON blacklake.full_snapshot TO blacklake_poc_runtime")
    finally:
        connection.close()


def chunks(rows, size):
    for offset in range(0, len(rows), size):
        yield rows[offset:offset + size]


def main():
    parser = argparse.ArgumentParser(description="Full API-visible archive of the approved source factory")
    parser.add_argument("--install", action="store_true", help="Explicitly install the isolated archive table")
    parser.add_argument("--resume", action="store_true", help="Resume current run checkpoints, not a new snapshot")
    args = parser.parse_args()
    directory, client, store, cache = context()
    profile = json.loads((Path(__file__).parent / "full_contract.json").read_text())
    checkpoint = directory / "full-run.dpapi"
    source_fingerprint = digest(store.scope)
    account_fingerprint = digest(client.settings.account_key)
    if args.resume:
        run = load_local_secret(checkpoint)
        if (run["source"] != source_fingerprint or run["contract"] != digest(profile)
                or run.get("account") != account_fingerprint
                or run.get("validation_version") != ARCHIVE_VALIDATION_VERSION):
            raise BlacklakeError("FULL_RESUME_SCOPE_OR_CONTRACT_CHANGED")
    else:
        run = {"run_id": str(uuid.uuid4()), "source": source_fingerprint, "contract": digest(profile),
               "account": account_fingerprint,
               "validation_version": ARCHIVE_VALIDATION_VERSION,
               "started_at": datetime.now(timezone.utc).isoformat(), "run_finished": False}
        save_local_secret(checkpoint, run)
    archive = Archive(client, store, profile, run["run_id"])
    receipts = []

    def pull(resource, body, request_context=(), membership=None):
        receipt = archive.ingest(resource, body, context=request_context, membership=membership)
        receipts.append(receipt)
        if resource not in ("materials", "project_expand", "bom", "stock"):
            print(json.dumps(receipt), flush=True)

    try:
        if args.install:
            install(directory)
        from .readiness import database_ready
        database_ready(load_local_secret(directory / "test-database.dpapi"), require_archive=True)
        with client.coordination.lease():
            for resource in ("product", "process", "routing", "warehouse", "unit"):
                pull(resource, {})
            for status in ("0", "10", "20", "30"):
                pull("project", {"status": status}, membership=lambda row, s=status: str(row["status"]) == s)
            projects = archive.raw_rows("project")
            products = archive.raw_rows("product")
            warehouses = archive.raw_rows("warehouse")
            project_ids = sorted(row["id"] for row in projects)
            for batch in chunks(project_ids, 50):
                allowed = set(batch)
                for resource in ("task", "output"):
                    pull(resource, {"projectIds": batch}, membership=lambda row, ids=allowed: row.get("projectId") in ids)
            for direction in (10, 20):
                pull("stock_order", {"orderType": direction}, (direction,),
                     membership=lambda row, d=direction: row.get("orderType") == d)
            for product in products:
                code = product["productCode"]
                pull("bom", {"lastProductCode": code}, membership=lambda row, c=code: row.get("lastProductCode") == c)
            print(json.dumps({"phase": "bom_complete", "products_checked": len(products)}), flush=True)
            for offset, parent in enumerate(project_ids):
                pull("materials", {"projectId": parent}, (parent,))
                pull("project_expand", {"projectIds": [parent]})
                if (offset + 1) % 20 == 0:
                    print(json.dumps({"phase": "parent_details", "checked": offset + 1, "parents": len(project_ids)}), flush=True)
            pairs = [{"productId": p["id"], "warehouseId": w["simpleVO"]["id"]}
                     for p in products for w in warehouses]
            for batch in chunks(pairs, 100):
                allowed = {(row["productId"], row["warehouseId"]) for row in batch}
                pull("stock", {"stockInfoQryOpenApiCOList": batch}, membership=lambda row, ids=allowed:
                     (row.get("productId"), row.get("warehouseId")) in ids)
            audit = archive.audit()
            source_totals = {}
            for receipt in receipts:
                source_totals[receipt["resource"]] = source_totals.get(receipt["resource"], 0) + receipt["count"]
            if any(audit["counts"].get(key, 0) != total for key, total in source_totals.items()):
                raise BlacklakeError("FULL_SOURCE_ARCHIVE_COUNT_MISMATCH")
            project_set = set(project_ids)
            task_set = {row["id"] for row in archive.raw_rows("task")}
            outputs = archive.raw_rows("output")
            links = {"missing_project": sum(row.get("projectId") not in project_set for row in outputs),
                     "missing_task": sum(row.get("taskId") not in task_set for row in outputs)}
            expands = archive.raw_rows("project_expand")
            unresolved = sum(not row["identity_resolved"] for row in expands)
            stock_pairs = {(row["productId"], row["warehouseId"]) for row in archive.raw_rows("stock")}
            missing_pairs = len(pairs) - len(stock_pairs)
            created = [row["createTime"] for row in projects if row.get("createTime")]
            report = {"run_id": run["run_id"], "started_at": run["started_at"],
                      "finished_at": datetime.now(timezone.utc).isoformat(), "audit": audit,
                      "source_totals": source_totals, "links": links,
                      "project_creation_range": {"earliest": min(created) if created else None,
                                                 "latest": max(created) if created else None},
                      "current_process_unresolved_queries": unresolved,
                      "stock_pairs_requested": len(pairs), "stock_pairs_not_returned": missing_pairs,
                      "production_business_mutations": 0,
                      "coverage": "All API-visible projects in the four documented statuses; all children of these parents; current visible reference data and product/warehouse balance pairs",
                      "deletions_and_orphans_outside_visible_parent_graph": "not_proven"}
            passed = audit["raw_hash_mismatches"] == 0 and not any(links.values())
            report["run_finished"] = True
            report["integrity_passed"] = passed
            report["coverage_complete"] = False
            report["limitations"] = ["Nontransactional source scan; changes during the interval remain possible",
                                     "Deleted records and children outside visible parents are not proven",
                                     "Missing balance pairs must not be interpreted as zero",
                                     "Materials without a source projectId retain unverified parent scope",
                                     "Current-process responses without identity remain unresolved"]
            save_local_secret(directory / "full-report.dpapi", report)
            run["run_finished"] = True
            run["integrity_passed"] = passed
            run["coverage_complete"] = False
            save_local_secret(checkpoint, run)
            print(json.dumps(report), flush=True)
            return 0 if passed else 1
    except Exception as exc:
        code = str(exc) if isinstance(exc, BlacklakeError) else "FULL_ARCHIVE_RUNTIME_ERROR"
        run["last_error"] = code
        save_local_secret(checkpoint, run)
        if client.last_business_error:
            save_local_secret(directory / "full-last-error.dpapi", client.last_business_error)
        print(json.dumps({"success": False, "error": code, "resume_available": True}), flush=True)
        return 1
    finally:
        client.close()
        store.connection.close()
        cache.close()


if __name__ == "__main__":
    raise SystemExit(main())
