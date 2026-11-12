"""Run non-skipped PG/SCRAM + Redis gates in a new isolated synthetic database."""
import json
import os
import subprocess
import sys
import uuid
from pathlib import Path

import psycopg2
from psycopg2 import sql
from psycopg2.extensions import parse_dsn, make_dsn

from .local_environment import cache_url
from .secrets import load_local_secret


def main():
    directory = Path(os.environ["LOCALAPPDATA"]) / "Cretas/Blacklake"
    settings = load_local_secret(directory / "test-database.dpapi")
    admin = psycopg2.connect(settings["admin_dsn"])
    admin.autocommit = True
    name = "blacklake_connector_test_" + uuid.uuid4().hex[:12]
    with admin.cursor() as cur:
        cur.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(name)))
    admin.close()
    destination = dict(parse_dsn(settings["admin_dsn"]), dbname=name)
    environment = dict(os.environ, BLACKLAKE_TEST_ADMIN_DSN=make_dsn(**destination),
                       BLACKLAKE_TEST_REDIS_URL=cache_url())
    root = Path(__file__).parent.parent
    evidence = directory / "validation"
    evidence.mkdir(exist_ok=True)
    log_path = evidence / "pytest-sections-final.log"
    with log_path.open("w", encoding="utf-8") as log:
        completed = subprocess.run([
            sys.executable, "-m", "pytest", "tests/test_blacklake_connector.py", "tests/test_blacklake_storage.py", "tests/test_blacklake_analytics.py", "tests/test_blacklake_intelligence.py", "tests/test_blacklake_blocked_audit.py", "tests/test_blacklake_cost_alerts.py", "tests/test_blacklake_sections.py", "tests/test_smartbi_dataset_alerts.py", "tests/test_smartbi_shared_alerts.py", "tests/test_smartbi_excel_async_auth.py", "tests/test_auth_internal_secret_fail_closed.py", "tests/test_smartbi_analysis_cache_auth.py",
            "tests/test_blacklake_periods.py", "tests/test_blacklake_period_app.py", "tests/test_smartbi_period_windows.py", "tests/test_smartbi_period_reports.py", "tests/test_smartbi_period_schedule.py", "tests/test_blacklake_preview_session.py", "tests/test_smartbi_ai_queue.py", "tests/test_smartbi_local_analyst.py", "tests/test_smartbi_analysis_distillation.py",
            "-q", "-o", "addopts=", "--junitxml=" + str(evidence / "pytest-sections-final.xml")],
            cwd=root, env=environment, stdout=log, stderr=subprocess.STDOUT)
    print(json.dumps({"exit_code": completed.returncode, "database_auth": "SCRAM",
                      "log": str(log_path), "synthetic_database": name}))
    # No automatic database/container destruction; evidence remains inspectable.
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
