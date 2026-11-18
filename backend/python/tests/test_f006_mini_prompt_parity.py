"""Pin: Python provider _SYSTEM == Aurora gateway SYSTEM (2026-11-19 boss-mini 400 outage)."""
import ast
from pathlib import Path

from llm import f006_mini_provider

GATEWAY = Path(__file__).resolve().parents[3] / "scripts" / "ops" / "f006_aurora_runtime.py"


def _gateway_system() -> str:
    for node in ast.parse(GATEWAY.read_text(encoding="utf-8")).body:
        if isinstance(node, ast.Assign) and any(getattr(t, "id", "") == "SYSTEM" for t in node.targets):
            return ast.literal_eval(node.value)
    raise AssertionError("SYSTEM not found in gateway")


def test_gateway_system_equals_provider_system():
    gw = _gateway_system()
    assert len(gw) > 200
    assert gw == f006_mini_provider._SYSTEM
