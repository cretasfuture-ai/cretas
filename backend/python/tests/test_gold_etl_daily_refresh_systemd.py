"""Guard the unit and its Python runtime override against orphaned modules."""
from pathlib import Path
import shlex


def _directives(file: Path, name: str) -> list[str]:
    content = file.read_text(encoding="utf-8").replace("\\\n", " ")
    return [line.split("=", 1)[1].strip() for line in content.splitlines()
            if line.startswith(f"{name}=")]


def test_gold_etl_timer_targets_an_existing_python_module():
    python_root = Path(__file__).resolve().parents[1]
    systemd = python_root.parents[1] / "scripts" / "systemd"
    service_name = "cretas-gold-etl-refresh.service"
    service = systemd / service_name
    override = systemd / f"{service_name}.d" / "python-runtime.conf"
    timer = systemd / "cretas-gold-etl-refresh.timer"

    assert _directives(timer, "Unit") == [service_name]
    assert _directives(timer, "OnCalendar") == ["*-*-* 03:30:00"]
    working_dir, = _directives(service, "WorkingDirectory")
    [base_exec] = _directives(service, "ExecStart")
    reset, override_exec = _directives(override, "ExecStart")
    assert reset == ""
    base_command = shlex.split(base_exec)
    assert shlex.split(override_exec) == base_command
    assert base_command == [f"{working_dir}/venv-current/bin/python", "-m", "scripts.gold_etl_daily_refresh"]
    assert working_dir.endswith("/backend/python")
    module = python_root.joinpath(*base_command[2].split(".")).with_suffix(".py")
    assert module.is_file(), f"systemd schedules a missing Python module: {module}"
