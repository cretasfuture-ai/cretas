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
            assert node.attr not in {"roll_simulated_reservations", "run_full_etl_with_retry"}
        if isinstance(node, ast.ImportFrom):
            assert not (node.module or "").startswith("smartbi.services.restaurant")
            assert not (node.module or "").startswith("smartbi.gold.restaurant")


def test_real_lifespan_no_longer_registers_restaurant_background_writers():
    lifespan = _lifespan_tree(Path(main.__file__).read_text(encoding="utf-8"))
    _assert_no_retired_writers(lifespan)
    assert any(isinstance(node, ast.Yield) for node in ast.walk(lifespan))


@pytest.mark.parametrize("writer", [
    "_reservation_roll_task = asyncio.create_task(service.roll_simulated_reservations())",
    "from smartbi.gold.restaurant.restaurant_ops_etl import run_full_etl_with_retry",
    "renamed_task = asyncio.create_task(service.run_full_etl_with_retry())",
])
def test_retirement_guard_rejects_a_reintroduced_writer(writer):
    lifespan = _lifespan_tree(Path(main.__file__).read_text(encoding="utf-8"))
    lifespan.body.extend(ast.parse(writer).body)
    with pytest.raises(AssertionError):
        _assert_no_retired_writers(lifespan)


def test_factory_api_registration_remains_available_by_default():
    routes = {(method, route.path) for route in main.app.routes for method in (getattr(route, "methods", None) or ())}
    assert ("POST", "/api/internal/smartbi/agent/runs/ops/traces/factory") in routes
    assert ("POST", "/api/chat/general-analysis") in routes
    assert ("GET", "/health") in routes


@pytest.mark.asyncio
async def test_real_lifespan_starts_and_stops_shared_factory_tasks(monkeypatch, tmp_path):
    """Execute production lifespan; replace external I/O, never database/business logic."""
    import asyncio
    import os
    import sys
    from types import SimpleNamespace
    from unittest.mock import AsyncMock, Mock

    tasks = []

    class RecordedTask:
        def __init__(self, coroutine):
            self.name = coroutine.cr_code.co_name
            self.cancelled = False
            coroutine.close()  # no background SQL, network or timers in this test
            tasks.append(self)

        def cancel(self):
            self.cancelled = True

        def __await__(self):
            return AsyncMock(return_value=None)().__await__()

    monkeypatch.setattr(asyncio, "create_task", RecordedTask)
    monkeypatch.setattr(main, "verify_draft_storage", Mock())
    monkeypatch.setattr(main, "_vision_training_runtime", SimpleNamespace(startup_probe=Mock(), close=Mock()))
    monkeypatch.setattr(main, "_ai_module_available", False)
    monkeypatch.setattr(main, "_food_kb_available", True)
    monkeypatch.setattr(main.gc, "collect", Mock())
    monkeypatch.setattr(main.gc, "freeze", Mock())
    for flag in ("SMARTBI_AGENT_LAYER_ENABLED", "PLATFORM_SYNC_ENABLED", "SMARTBI_EXTERNAL_BENCHMARKS_ENABLED"):
        monkeypatch.setenv(flag, "false")
    settings = SimpleNamespace(debug=False, llm_model="test", postgres_enabled=False,
        food_kb_db_url="test-only", llm_api_key="", llm_base_url="test-only",
        food_kb_embedding_model="test", food_kb_embedding_dims=1)
    monkeypatch.setattr(main, "get_settings", lambda: settings)
    retriever = SimpleNamespace(initialize=AsyncMock(), is_ready=lambda: False, close=AsyncMock())
    ingester = SimpleNamespace(initialize=AsyncMock(), close=AsyncMock())
    reranker = SimpleNamespace(configure=Mock(), close=AsyncMock())
    modules = {
        "food_kb.services.embedding": SimpleNamespace(configure=Mock(), get_embedding=AsyncMock(), close=AsyncMock()),
        "food_kb.services.knowledge_retriever": SimpleNamespace(get_knowledge_retriever=lambda: retriever),
        "food_kb.services.document_ingester": SimpleNamespace(get_document_ingester=lambda: ingester),
        "food_kb.services.reranker": SimpleNamespace(get_reranker=lambda: reranker),
        "food_kb.services.food_ner_service": SimpleNamespace(get_food_ner_service=lambda: SimpleNamespace(load_model=Mock())),
        "common.llm_client": SimpleNamespace(init_llm_client=AsyncMock(), close_llm_client=AsyncMock()),
        "common.llm_metrics": SimpleNamespace(enable_llm_metrics=Mock(), disable_llm_metrics=AsyncMock()),
        "common.llm_egress_audit": SimpleNamespace(enable_egress_audit=Mock(), disable_egress_audit=AsyncMock()),
    }
    for name, module in modules.items():
        monkeypatch.setitem(sys.modules, name, module)
    from smartbi import config
    monkeypatch.setattr(config, "get_pg_pool", AsyncMock(return_value=None))
    monkeypatch.setitem(sys.modules, "fcntl", SimpleNamespace(flock=Mock(), LOCK_EX=1, LOCK_NB=2))
    original_open = os.open
    descriptors = []

    def open_test_leader(path, flags, mode=0o777, **kwargs):
        if str(path).startswith("/tmp/cretas-python-leader-"):
            fd = original_open(tmp_path / "leader.lock", flags, mode)
            descriptors.append(fd)
            return fd
        return original_open(path, flags, mode, **kwargs)

    monkeypatch.setattr(os, "open", open_test_leader)
    try:
        async with main.lifespan(main.app):
            names = {task.name for task in tasks}
            assert "_prune_chat_sessions_forever" in names
            assert "_prune_llm_cache_forever" in names
            assert not any("restaurant" in name or "reservation" in name for name in names)
            assert not any(task.cancelled for task in tasks)
        assert all(task.cancelled for task in tasks)
        main._vision_training_runtime.close.assert_called_once()
    finally:
        for fd in descriptors:
            os.close(fd)
