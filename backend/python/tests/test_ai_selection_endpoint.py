"""POST /api/ai/intent/selection —— 澄清按钮点选回写的闸。"""
import json

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from ai import registry
from ai.api import router


class _Conn:
    def __init__(self, sink):
        self.sink = sink

    async def execute(self, sql, *args):
        self.sink.append((sql, args))


class _Pool:
    def __init__(self):
        self.calls = []

    def acquire(self):
        pool = self

        class _Ctx:
            async def __aenter__(self):
                return _Conn(pool.calls)

            async def __aexit__(self, *a):
                return False
        return _Ctx()


def _app(internal=True):
    app = FastAPI()

    @app.middleware("http")
    async def fake_auth(request: Request, call_next):
        if internal:
            request.state.auth_method = "internal"
            request.state.factory_id = request.headers.get("X-Factory-Id")
        return await call_next(request)

    app.include_router(router)
    app.state.pg_pool = _Pool()
    return app


BODY = {"factoryId": "F006", "userId": "7", "role": "operator", "query": "就是六十箱",
        "chosenIntent": "WORK_PROCESS_TASK_COMPLETE", "candidates": ["WORK_PROCESS_TASK_COMPLETE", "ABACA_WEIGHT_LOG"]}


def test_records_one_row_with_normalized_key():
    registry.set_registry(registry.RegistrySnapshot())
    app = _app()
    r = TestClient(app).post("/api/ai/intent/selection", json=BODY, headers={"X-Factory-Id": "F006"})
    assert r.status_code == 200 and r.json()["success"] is True
    assert len(app.state.pg_pool.calls) == 1
    _, args = app.state.pg_pool.calls[0]
    assert args[0] == "F006" and args[3] == "就是六十箱" and args[5] == "WORK_PROCESS_TASK_COMPLETE"
    assert args[4] == "就是<量>"                      # 与精确表同一套归一化
    assert json.loads(args[6]) == BODY["candidates"]


def test_rejects_without_internal_secret():
    app = _app(internal=False)
    r = TestClient(app).post("/api/ai/intent/selection", json=BODY, headers={"X-Factory-Id": "F006"})
    assert r.status_code == 401 and app.state.pg_pool.calls == []


def test_rejects_smuggled_factory():
    app = _app()
    r = TestClient(app).post("/api/ai/intent/selection", json=BODY, headers={"X-Factory-Id": "F999"})
    assert r.status_code == 403 and app.state.pg_pool.calls == []


def test_requires_query_and_chosen_intent():
    app = _app()
    r = TestClient(app).post("/api/ai/intent/selection", json={**BODY, "chosenIntent": ""},
                             headers={"X-Factory-Id": "F006"})
    assert r.status_code == 400 and app.state.pg_pool.calls == []
