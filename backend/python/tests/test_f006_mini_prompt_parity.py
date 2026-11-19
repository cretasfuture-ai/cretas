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


def _gateway_int(name: str) -> int:
    for node in ast.parse(GATEWAY.read_text(encoding="utf-8")).body:
        if isinstance(node, ast.Assign) and any(getattr(t, "id", "") == name for t in node.targets):
            return ast.literal_eval(node.value)
    raise AssertionError(name + " not found in gateway")


def test_provider_timeouts_nest_around_gateway_wall_deadline():
    # 2026-11-19: 网关按次冷启动, 首行要等模型处理完提问; 读超时 28s 会在网关 50s 期限内先掐断。
    # 顺序必须是 网关墙钟 < Python 读超时 < Python 总期限。
    wall = _gateway_int("REQUEST_SECONDS")
    assert f006_mini_provider.GATEWAY_WALL_S == wall == 50
    assert wall < f006_mini_provider.READ_TIMEOUT_S < f006_mini_provider.TOTAL_TIMEOUT_S == 55
