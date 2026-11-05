"""Retired restaurant writers must never be armed by Python service startup."""
import ast
from pathlib import Path

import pytest

import main


def _lifespan_tree(source):
    return next(node for node in ast.parse(source).body if isinstance(node, ast.AsyncFunctionDef) and node.name == "lifespan")


def _assert_no_retired_writers(lifespan):
    for node in ast.walk(lifespan):
        if isinstance(node, ast.Name):
            assert node.id not in {"_reservation_roll_task", "_restaurant_etl_task"}
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            assert node.name not in {"_roll_restaurant_reservations_forever", "_run_restaurant_ops_etl_forever"}
        if isinstance(node, ast.Attribute):
            assert node.attr != "roll_simulated_reservations"
        if isinstance(node, ast.ImportFrom):
            assert not (node.module or "").startswith("smartbi.services.restaurant")


def test_real_lifespan_no_longer_registers_restaurant_background_writers():
    lifespan = _lifespan_tree(Path(main.__file__).read_text(encoding="utf-8"))
    _assert_no_retired_writers(lifespan)
    assert any(isinstance(node, ast.Yield) for node in ast.walk(lifespan))


def test_retirement_guard_rejects_a_reintroduced_writer():
    lifespan = _lifespan_tree(Path(main.__file__).read_text(encoding="utf-8"))
    lifespan.body.extend(ast.parse("_reservation_roll_task = asyncio.create_task(service.roll_simulated_reservations())").body)
    with pytest.raises(AssertionError):
        _assert_no_retired_writers(lifespan)


def test_factory_api_registration_remains_available_by_default():
    routes = {(method, route.path) for route in main.app.routes for method in (getattr(route, "methods", None) or ())}
    assert ("POST", "/api/internal/smartbi/agent/runs/ops/traces/factory") in routes
    assert ("POST", "/api/chat/general-analysis") in routes
    assert ("GET", "/health") in routes
