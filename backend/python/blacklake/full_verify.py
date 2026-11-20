"""Revalidate retained full-run evidence offline; never query the source API."""
import json
import os
from dataclasses import dataclass
from pathlib import Path

import psycopg2

from .contracts import BlacklakeError, at
from .full_archive import ARCHIVE_VALIDATION_VERSION, Archive, digest
from .readiness import database_ready
from .secrets import load_local_secret, save_local_secret
from .storage import PostgresStore


@dataclass(frozen=True)
class OfflineScope:
    tenant_id: str
    source_factory_id: str


def offline_context():
    directory = Path(os.environ["LOCALAPPDATA"]) / "Cretas/Blacklake"
    binding = load_local_secret(directory / "source-binding.dpapi")
    if binding.get("owner_confirmed") is not True or not binding.get("expected_org_id"):
        raise BlacklakeError("SOURCE_OWNER_BINDING_REQUIRED")
    saved = load_local_secret(directory / "test-database.dpapi")
    database_ready(saved, require_archive=True)
    connection = psycopg2.connect(saved["runtime_dsn"])
    try:
        store = PostgresStore(connection, OfflineScope("BLACKLAKE_POC_TEST", str(binding["expected_org_id"])))
    except Exception:
        connection.close()
        raise
    return directory, store


def validate_retained_run(run, report, audit):
    if report.get("run_id") != run.get("run_id"):
        raise BlacklakeError("FULL_REPORT_RUN_MISMATCH")
    if not (run.get("run_finished") is True or run.get("complete") is True):
        raise BlacklakeError("FULL_RUN_NOT_FINISHED")
    if not audit["records"] or audit["counts"] != report.get("source_totals"):
        raise BlacklakeError("FULL_RETAINED_COUNTS_MISMATCH")


def main():
    directory, store = offline_context()
    try:
        run = load_local_secret(directory / "full-run.dpapi")
        if run["source"] != digest(store.scope):
            raise BlacklakeError("FULL_SOURCE_BINDING_MISMATCH")
        profile = json.loads((Path(__file__).parent / "full_contract.json").read_text())
        if run["contract"] != digest(profile):
            raise BlacklakeError("FULL_CONTRACT_CHANGED")
        archive = Archive(None, store, profile, run["run_id"])
        report = load_local_secret(directory / "full-report.dpapi")
        audit = archive.audit()
        validate_retained_run(run, report, audit)
        projects = {row["id"] for row in archive.raw_rows("project")}
        tasks = {row["id"] for row in archive.raw_rows("task")}
        outputs = archive.raw_rows("output")
        links = {"missing_project": sum(row.get("projectId") not in projects for row in outputs),
                 "missing_task": sum(row.get("taskId") not in tasks for row in outputs)}
        unresolved = sum(not row["identity_resolved"] for row in archive.raw_rows("project_expand"))
        pairs = len(archive.raw_rows("product")) * len(archive.raw_rows("warehouse"))
        compared, mismatches, materials_unverified = 0, 0, 0
        material_ids = set()
        with store.transaction() as cur:
            cur.execute("SELECT s.resource,s.normalized,r.payload FROM blacklake.full_snapshot s "
                        "JOIN blacklake.raw_record r ON r.tenant_id=s.tenant_id AND r.source_factory_id=s.source_factory_id "
                        "AND r.resource='archive:'||s.resource AND r.external_id=s.external_id AND r.content_hash=s.content_hash "
                        "WHERE s.run_id=%s", (run["run_id"],))
            for resource, normalized, raw in cur.fetchall():
                for target, source in profile[resource].get("fields", {}).items():
                    try:
                        value = at(raw, source)
                    except BlacklakeError:
                        value = None
                    compared += 1
                    mismatches += normalized.get(target) != value
                if resource == "materials":
                    parent = normalized["request_context"][0]
                    if raw["id"] in material_ids:
                        raise BlacklakeError("FULL_MATERIAL_ID_REPEATED_ACROSS_PARENTS")
                    material_ids.add(raw["id"])
                    if raw.get("projectId") is None:
                        materials_unverified += 1
                    elif raw["projectId"] != parent:
                        raise BlacklakeError("FULL_PARENT_SCOPE_MISMATCH")
        report.pop("extraction_passed", None)
        report.update(audit=audit, run_finished=True, links=links,
                      current_process_unresolved_queries=unresolved, stock_pairs_requested=pairs,
                      stock_pairs_not_returned=pairs-len(archive.raw_rows("stock")),
                      integrity_passed=audit["raw_hash_mismatches"] == 0 and mismatches == 0 and not any(links.values()),
                      coverage_complete=False, offline_validation_version=ARCHIVE_VALIDATION_VERSION,
                      normalized_fields_compared=compared, normalized_field_mismatches=mismatches,
                      materials_parent_scope_unverified=materials_unverified,
                      material_ids_unique_across_parent_queries=True,
                      limitations=["Nontransactional source scan; changes within the interval remain possible",
                                   "Deleted records, invisible modules and orphan records are not proven",
                                   "Missing balance pairs are unknown, not zero",
                                   "Materials lack independently returned parent identity",
                                   "Current-process responses lacking identity remain unresolved"],
                      source_api_requests_during_offline_validation=0)
        save_local_secret(directory / "full-report.dpapi", report)
        print(json.dumps(report))
        return 0 if report["integrity_passed"] else 1
    finally:
        store.connection.close()


if __name__ == "__main__":
    raise SystemExit(main())
