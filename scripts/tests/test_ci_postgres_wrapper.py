# GUARDS=scripts/ci/run-with-test-postgres.sh,scripts/tests/test-fresh-db-baseline-replays.sh,ops/db-baseline/**
"""Real local PostgreSQL lifecycle and failure-propagation acceptance."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(os.name == "posix" and os.getuid() != 0, "requires an unprivileged Linux/PostgreSQL runner")
class DisposablePostgresTest(unittest.TestCase):
    def test_native_baseline_rejects_tampered_index_count(self):
        source = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(prefix="ci-baseline-negative-") as temp:
            fixture = Path(temp)
            for name in ("scripts/ci/run-with-test-postgres.sh", "scripts/tests/test-fresh-db-baseline-replays.sh"):
                target = fixture / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source / name, target)
            shutil.copytree(source / "ops/db-baseline", fixture / "ops/db-baseline")
            baseline, = (fixture / "ops/db-baseline").glob("*.sql")
            content = baseline.read_text(encoding="utf-8")
            count = int(re.search(r"indexes=(\d+)", content).group(1))
            baseline.write_text(content.replace(f"indexes={count}", f"indexes={count + 1}", 1), encoding="utf-8")
            migrations = fixture / "backend/java/cretas-api/src/main/resources/db/flyway"
            migrations.mkdir(parents=True)
            (migrations / "V20261101_41__fixture.sql").touch()
            result = subprocess.run(["bash", str(fixture / "scripts/tests/test-fresh-db-baseline-replays.sh")],
                                    env={**os.environ, "CRETAS_BASELINE_NATIVE_PG": "1"},
                                    text=True, capture_output=True, timeout=120)
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertIn(f"indexes({count}≠{count + 1})", result.stdout + result.stderr)

    def test_isolation_and_cleanup_on_success_and_failure(self):
        wrapper = Path(__file__).resolve().parents[1] / "ci" / "run-with-test-postgres.sh"
        for code in (0, 47):
            with self.subTest(exit_code=code), tempfile.TemporaryDirectory(prefix="ci-pg-accept-") as temp:
                env = {**os.environ, "RUNNER_TEMP": temp, "SMARTBI_PG_DSN": "postgresql://invalid.example/production"}
                child = (
                    "import os,subprocess,sys; "
                    "dsn=os.environ['SMARTBI_PG_DSN']; "
                    "assert '@127.0.0.1:' in dsn and dsn.endswith('/cretas_workflow_verify_test'); "
                    "out=subprocess.check_output(['psql','-X','-At',dsn,'-c',"
                    "'CREATE TABLE acceptance(value integer); INSERT INTO acceptance VALUES (42); SELECT value FROM acceptance'],text=True); "
                    "assert out.strip().endswith('42'); "
                    f"sys.exit({code})"
                )
                result = subprocess.run(["bash", str(wrapper), sys.executable, "-c", child],
                                        env=env, text=True, capture_output=True, timeout=45)
                self.assertEqual(result.returncode, code, result.stdout + result.stderr)
                self.assertIn("CI_POSTGRES=ready", result.stdout)
                self.assertEqual(list(Path(temp).iterdir()), [], "temporary cluster survived command completion")


if __name__ == "__main__":
    unittest.main()
