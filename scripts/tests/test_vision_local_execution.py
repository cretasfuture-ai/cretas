"""Local execution policy: reject remote compute before side effects."""
import importlib.util
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("vision_local_policy", ROOT / "tools/vision-lab/vision_lab.py")
vision = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(vision)


@pytest.fixture(autouse=True)
def local_windows(monkeypatch):
    monkeypatch.setattr(vision.sys, "platform", "win32")
    monkeypatch.setenv("COMPUTERNAME", "local-training")


@pytest.mark.parametrize("device", [0, "0", "cpu"])
def test_local_compute_keeps_production_collection_and_upload(device):
    vision.assert_local_execution({
        "execution": {"mode": "local", "computer_name": "LOCAL-TRAINING", "remote_enabled": False},
        "training": {"device": device},
        "source": {"ssh_host": "root@47.100.235.168"},
        "deployment": {"ssh_host": "root@47.100.235.168"},
    })


@pytest.mark.parametrize("config", [
    {"execution": {"mode": "remote"}},
    {"execution": {"remote_enabled": True}},
    {"execution": {"host": "canada-mini"}},
    {"execution": {"computer_name": "another-computer"}},
    {"training": {"device": "mps"}},
    {"source": {"ssh_host": "rex@canada-mini"}},
    {"deployment": {"ssh_host": "codex-mac"}},
])
def test_remote_policy_is_rejected(config):
    with pytest.raises(ValueError):
        vision.assert_local_execution(config)


@pytest.mark.parametrize("platform", ["darwin", "linux"])
def test_cli_rejects_nonlocal_platform_before_creating_state(monkeypatch, platform):
    monkeypatch.setattr(vision.sys, "platform", platform)
    monkeypatch.setattr(vision, "load_config", lambda path: {})
    def forbidden(*args, **kwargs):
        pytest.fail("execution policy must run before State or subprocess")
    monkeypatch.setattr(vision, "State", forbidden)
    monkeypatch.setattr(vision.subprocess, "run", forbidden)
    with pytest.raises(ValueError, match="local Windows only"):
        vision.command_main(["--config", "unused.json", "status"])
