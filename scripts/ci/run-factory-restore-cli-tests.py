#!/usr/bin/env python3
"""Run the opt-in restore cases on a fresh, owned PostgreSQL 17 cluster."""
from collections import Counter
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "backend/java/cretas-api"
TEST_CLASS = "com.cretas.aims.service.factorydata.FactoryDataPackageRestorePostgresContractTest"
METHODS = (
    "shadowVectorTypeRefusesEvenWithRealExtensionInstalled",
    "javaExportRoundTripsThroughProtectedCliWhenExplicitlyEnabled",
    "forgedZeroReferenceProofRefusesBeforeAnyTargetConnection",
    "legacyMainExportWithInternalForeignKeysRestoresThroughProtectedCli",
    "legacyMainExportWithOnlyPrimaryUniqueAndCheckConstraintsRestores",
    "alteredLegacyExportFingerprintRefusesOfflinePreflight",
    "currentExportDisguisedAsLegacyRefusesOfflinePreflight",
)


def verify_report(path: Path) -> None:
    suite = ET.parse(path).getroot()
    counts = {name: int(suite.attrib[name]) for name in ("tests", "skipped", "failures", "errors")}
    cases = suite.findall("testcase")
    expected = Counter((TEST_CLASS, method) for method in METHODS)
    actual = Counter((case.get("classname"), case.get("name")) for case in cases)
    if (suite.tag != "testsuite" or suite.get("name") != TEST_CLASS
            or counts != dict(tests=len(METHODS), skipped=0, failures=0, errors=0)
            or expected != actual
            or any(case.find(tag) is not None for case in cases for tag in ("skipped", "failure", "error"))):
        raise ValueError(f"{TEST_CLASS}: {counts}, missing={list(expected - actual)}, extra={list(actual - expected)}")
    for method in METHODS:
        print(f"CLI_CASE {TEST_CLASS}#{method} executed=true tests=1 skipped=0 failures=0 errors=0")
    print(f"CLI_SUITE {TEST_CLASS} tests={counts['tests']} skipped=0 failures=0 errors=0")


def run(pg_bin: Path) -> None:
    version = subprocess.check_output([pg_bin / "pg_config", "--version"], text=True).strip()
    if not version.startswith("PostgreSQL 17."):
        raise ValueError(f"PostgreSQL 17 is required: {version}")

    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]

    temp = Path(tempfile.mkdtemp(prefix="cretas-restore-cli-", dir=os.environ.get("RUNNER_TEMP")))
    cluster = temp / "cluster"
    env = os.environ.copy()
    env.pop("PGPASSWORD", None)  # libpq must use the private cluster's passfile.
    try:
        subprocess.run([
            sys.executable, ROOT / "scripts/backup/create-owned-factory-restore-pg.py",
            "--root", cluster, "--pg-bin", pg_bin, "--port", str(port),
            "--task-id", "factory-restore-cli", "--owner-thread-id", os.environ.get("GITHUB_RUN_ID", "local"),
        ], env=env, check=True)
        receipt = cluster / "ownership-receipt.json"
        user = json.loads(receipt.read_text(encoding="utf-8"))["databaseUser"]
        passfile = cluster / "pgpass.private"
        env.update(
            PGPASSFILE=str(passfile),
            CRETAS_WORKFLOW_PG_VERIFY="true",
            CRETAS_WORKFLOW_CLI_VERIFY="true",
            CRETAS_WORKFLOW_PG_URL=f"jdbc:postgresql://127.0.0.1:{port}/postgres",
            CRETAS_WORKFLOW_PG_USER=user,
            CRETAS_WORKFLOW_PG_PASSWORD=passfile.read_text(encoding="utf-8").strip().split(":", 4)[4],
            CRETAS_WORKFLOW_PG_BIN=str(pg_bin),
            CRETAS_WORKFLOW_PG_OWNER_RECEIPT=str(receipt),
            CRETAS_WORKFLOW_PYTHON=sys.executable,
        )
        vector = subprocess.check_output([
            pg_bin / "psql", "-XAt", "-w", "-h", "127.0.0.1", "-p", str(port), "-U", user, "-d", "postgres",
            "-c", "SELECT default_version FROM pg_available_extensions WHERE name = 'vector'",
        ], env=env, text=True).strip()
        if not vector:
            raise ValueError("pgvector is unavailable on the owned PostgreSQL 17 cluster")
        print(f"CLI_POSTGRES {version} pgvector={vector} auth=scram-sha-256", flush=True)

        maven = subprocess.check_output(["bash", ROOT / "scripts/ci/resolve-maven.sh"], cwd=MODULE, text=True).strip()
        report = MODULE / "target/surefire-reports" / f"TEST-{TEST_CLASS}.xml"
        report.unlink(missing_ok=True)
        selector = f"{TEST_CLASS}#{'+'.join(METHODS)}"
        subprocess.run([maven, "-B", "test", f"-Dtest={selector}", "-Dspring.profiles.active=test"],
                       cwd=MODULE, env=env, check=True)
        verify_report(report)
    finally:
        data = cluster / "data"
        if (data / "postmaster.pid").is_file():
            subprocess.run([pg_bin / "pg_ctl", "-D", data, "-m", "fast", "-w", "stop"], check=True)
        shutil.rmtree(temp)


if __name__ == "__main__":
    run(Path(sys.argv[1]).resolve(strict=True))
