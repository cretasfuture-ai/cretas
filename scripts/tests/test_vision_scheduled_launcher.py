"""Exercise the Windows scheduled entry with a real local Git remote, no network."""
import os
from pathlib import Path
import subprocess

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "windows/liushanmen-vision-lab.ps1"
pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows Task Scheduler entry")


@pytest.mark.parametrize("state,allowed", [("main", True), ("dirty", False), ("candidate", False), ("unreachable", False)])
def test_scheduler_only_launches_verified_main(tmp_path, state, allowed):
    repo = tmp_path / "checkout"
    remote = tmp_path / "origin.git"
    repo.mkdir()
    def git(*args):
        return subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, text=True)
    git("init", "-b", "main")
    git("config", "user.email", "fixture@example.invalid")
    git("config", "user.name", "Fixture")
    git("config", "core.hooksPath", str(tmp_path / "no-hooks"))
    entry = repo / "tools/vision-lab/vision_lab.py"
    entry.parent.mkdir(parents=True)
    entry.write_text("# fixture only\n", encoding="utf-8")
    git("add", ".")
    git("commit", "-m", "fixture")
    subprocess.run(["git", "init", "--bare", str(remote)], check=True, capture_output=True)
    git("remote", "add", "origin", str(remote))
    git("push", "-u", "origin", "main")
    if state == "dirty":
        entry.write_text("# changed\n", encoding="utf-8")
    elif state == "candidate":
        git("commit", "--allow-empty", "-m", "unmerged candidate")
    elif state == "unreachable":
        git("remote", "set-url", "origin", str(tmp_path / "missing.git"))
    marker = tmp_path / "launched.txt"
    python = tmp_path / "fake-python.ps1"
    python.write_text("Set-Content -LiteralPath '" + str(marker).replace("'", "''") + "' -Value launched\nexit 0\n", encoding="utf-8")
    config = tmp_path / "config.json"
    config.write_text("{}", encoding="utf-8")
    result = subprocess.run([
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(SCRIPT),
        "-Python", str(python), "-Config", str(config), "-RepoRoot", str(repo), "-RequireMergedMain",
    ], capture_output=True, text=True, errors="replace")
    assert (result.returncode == 0) == allowed, result.stdout + result.stderr
    assert marker.exists() == allowed
