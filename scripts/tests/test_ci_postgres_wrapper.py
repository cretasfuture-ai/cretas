# GUARDS=scripts/ci/run-with-test-postgres.sh
"""Real local PostgreSQL lifecycle and failure-propagation acceptance."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(os.name == "posix" and os.getuid() != 0, "requires an unprivileged Linux/PostgreSQL runner")
class DisposablePostgresTest(unittest.TestCase):
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
