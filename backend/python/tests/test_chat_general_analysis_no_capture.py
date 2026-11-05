"""平台授权 AI 分析入口的提示词里有保密产品的成本, 这次问答不能留在 Python 侧。

走真实的 ASGI 链路: JWTAuthMiddleware -> /api/chat/general-analysis。只替换模型调用和样本落库。
守的是: 内部调用带 X-No-Training-Capture 才不留存; 不带时工厂侧默认照旧采集; 拿 JWT 来的请求带这个头不算数。
"""
from __future__ import annotations

import time
from pathlib import Path

import jwt as pyjwt
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import smartbi.api.chat as chat_mod
from auth_middleware import JWTAuthMiddleware
from common.insight_cache import get_insight_cache

SECRET = "internal-secret-for-no-capture-test"
JWT_SECRET = "jwt-secret-for-no-capture-test"
FACTORY = "F-NO-CAPTURE"
ROUTE = "/api/chat/general-analysis"
PROMPT = "【时间范围成本分析】 保密产品 秘制酱牛肉 单位成本 79.3571"
INTERNAL = {"X-Internal-Secret": SECRET, "X-Factory-Id": FACTORY}
NO_CAPTURE = {**INTERNAL, "X-No-Training-Capture": "1"}


class _Spy:
    def __init__(self):
        self.captured = []
        self.llm_calls = 0


@pytest.fixture
def spy(monkeypatch):
    calls = _Spy()

    class _Generator:
        async def generate_text_analysis(self, text):
            calls.llm_calls += 1
            return "分析结果: 成本结构正常"

    async def _persist(_pool, **kwargs):
        calls.captured.append(kwargs)

    async def _no_pool():
        return None

    monkeypatch.setenv("INTERNAL_API_SECRET", SECRET)
    monkeypatch.setattr(chat_mod, "InsightGenerator", _Generator)
    monkeypatch.setattr("smartbi.services.distillation_capture.persist_distillation_sample", _persist)
    monkeypatch.setattr("smartbi.config.get_pg_pool", _no_pool)
    get_insight_cache().clear()
    yield calls
    get_insight_cache().clear()


def _client():
    app = FastAPI()
    app.add_middleware(JWTAuthMiddleware, jwt_secret=JWT_SECRET, enabled=True)
    app.include_router(chat_mod.router, prefix="/api/chat")
    return TestClient(app)


def _post(client, headers, message=PROMPT):
    return client.post(ROUTE, json={"message": message, "allow_tenant_data_fallback": False}, headers=headers)


def _cache_size():
    return len(get_insight_cache()._cache)


def test_signal_present_stores_nothing_and_still_answers(spy):
    response = _post(_client(), NO_CAPTURE)
    assert response.status_code == 200
    assert response.json()["success"] is True
    assert "成本结构正常" in response.json()["aiAnalysis"]
    assert spy.llm_calls == 1, "这次确实走到了模型"
    assert spy.captured == []
    assert _cache_size() == 0


def test_signal_absent_captures_exactly_as_before(spy):
    response = _post(_client(), INTERNAL)
    assert response.status_code == 200
    assert spy.llm_calls == 1
    assert len(spy.captured) == 1
    assert PROMPT in spy.captured[0]["input_text"]
    assert spy.captured[0]["factory_id"] == FACTORY
    assert _cache_size() == 1


def test_signal_never_reads_a_cached_answer(spy):
    client = _client()
    _post(client, INTERNAL)
    assert spy.llm_calls == 1
    _post(client, INTERNAL)
    assert spy.llm_calls == 1, "阳性对照: 不带信号时第二次命中缓存"
    _post(client, NO_CAPTURE)
    assert spy.llm_calls == 2, "带信号时不读缓存"


def test_a_jwt_caller_cannot_switch_capture_off(spy):
    token = pyjwt.encode({"factoryId": FACTORY, "userId": 5, "role": "factory_super_admin",
                          "exp": int(time.time()) + 600}, JWT_SECRET, algorithm="HS256")
    response = _post(_client(), {"Authorization": f"Bearer {token}", "X-No-Training-Capture": "1"})
    assert response.status_code == 200
    assert spy.llm_calls == 1
    assert len(spy.captured) == 1, "这个头只认内部调用"


@pytest.mark.parametrize("value", ["0", "true", ""])
def test_only_the_exact_value_counts(spy, value):
    _post(_client(), {**INTERNAL, "X-No-Training-Capture": value})
    assert len(spy.captured) == 1


def test_the_direct_channel_cannot_store_a_sample(monkeypatch):
    """DashScope 直连通道走 /api/llm/chat: 这条路的代码不碰训练样本, 真调一次也不写。"""
    import llm.api.endpoints as endpoints

    root = Path(endpoints.__file__).resolve().parents[2]
    for source in ("llm/api/endpoints.py", "common/llm_client.py"):
        assert "distillation" not in (root / source).read_text(encoding="utf-8")

    called = []

    async def _chain(_slot, _payload, **_kwargs):
        return {"choices": [{"message": {"content": "ok"}}]}

    async def _persist(_pool, **kwargs):
        called.append(kwargs)

    monkeypatch.setattr(endpoints, "call_chain", _chain)
    monkeypatch.setattr("smartbi.services.distillation_capture.persist_distillation_sample", _persist)
    monkeypatch.setenv("INTERNAL_API_SECRET", SECRET)
    app = FastAPI()
    app.add_middleware(JWTAuthMiddleware, jwt_secret=JWT_SECRET, enabled=True)
    app.include_router(endpoints.router, prefix="/api/llm")
    response = TestClient(app).post("/api/llm/chat", headers=INTERNAL, json={
        "messages": [{"role": "user", "content": PROMPT}], "slot": "chat"})
    assert response.status_code == 200, response.text
    assert called == []
