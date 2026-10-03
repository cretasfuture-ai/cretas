"""Real OS/process locking, without a model server or ERP database."""
import os
from pathlib import Path
import subprocess
import sys

import pytest

from llm.f006_mini_lease import MiniBusy, acquire_host_lease


def child(directory):
    code = """import sys
from pathlib import Path
from llm.f006_mini_lease import MiniBusy, acquire_host_lease
try:
    lease = acquire_host_lease(Path(sys.argv[1]))
except MiniBusy:
    print('BUSY')
else:
    lease.close()
    print('ACQUIRED')
"""
    env = os.environ.copy()
    env["PYTHONPATH"] = str(Path(__file__).resolve().parents[1])
    return subprocess.run([sys.executable, "-c", code, str(directory)], capture_output=True,
                          text=True, env=env, check=True, timeout=10).stdout.strip()


def test_other_worker_is_rejected_until_owner_releases(tmp_path):
    directory = tmp_path / "lease"
    lease = acquire_host_lease(directory)
    try:
        assert child(directory) == "BUSY"
        with pytest.raises(MiniBusy):
            acquire_host_lease(directory)
    finally:
        lease.close()
    assert child(directory) == "ACQUIRED"


def test_process_exit_releases_lease_without_stale_busy_state(tmp_path):
    directory = tmp_path / "lease"
    code = "from pathlib import Path; from llm.f006_mini_lease import acquire_host_lease; import os,sys; lease=acquire_host_lease(Path(sys.argv[1])); os._exit(0)"
    env = os.environ.copy()
    env["PYTHONPATH"] = str(Path(__file__).resolve().parents[1])
    subprocess.run([sys.executable, "-c", code, str(directory)], env=env, check=True, timeout=10)
    assert child(directory) == "ACQUIRED"


@pytest.mark.skipif(os.name != "posix", reason="POSIX directory ownership/mode guard")
def test_nonprivate_directory_is_refused_before_locking(tmp_path):
    directory = tmp_path / "lease"
    directory.mkdir(mode=0o755)
    directory.chmod(0o755)
    with pytest.raises(OSError):
        acquire_host_lease(directory)


@pytest.mark.skipif(os.name != "posix", reason="POSIX symlink guard")
def test_symlink_lock_file_is_refused(tmp_path):
    directory = tmp_path / "lease"
    directory.mkdir(mode=0o700)
    target = tmp_path / "unrelated"
    target.write_text("keep")
    (directory / "inflight.sqlite").symlink_to(target)
    with pytest.raises(OSError):
        acquire_host_lease(directory)
    assert target.read_text() == "keep"


@pytest.mark.skipif(os.name != "posix", reason="POSIX file mode guard")
def test_existing_nonprivate_lease_file_is_refused(tmp_path):
    directory = tmp_path / "lease"
    directory.mkdir(mode=0o700)
    path = directory / "inflight.sqlite"
    path.touch(mode=0o600)
    path.chmod(0o644)
    with pytest.raises(OSError):
        acquire_host_lease(directory)
