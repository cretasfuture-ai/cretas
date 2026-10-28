"""公开前缀上的外发工厂 (Steve 2026-10-29 复审 H3)。

回归: 公开前缀 (/api/insight/*、/api/food-kb/manual-chat* …) 以前完全不看 Bearer, 外发拿不到工厂;
没有工厂就拒绝外发之后, 登录了的工厂用户在这些页面上的 AI 分析全被拒, 而洞察客户端把拒绝当供应商故障
重试、最后返回空串 (没有数据却不说)。现在:
- 带能验过的 Bearer → 按 token 的工厂脱敏 (真实链路: 中间件 → /api/insight/generate → call_chain → 脱敏出口);
- 没登录 → 拒绝, 并明确报「没有发出」, 只调用一次 (不重试);
- 操作手册问答 → 不登录也按所有工厂的保密产品名脱敏后照常发出。
"""
from __future__ import annotations

import asyncio
import json
import time
from types import SimpleNamespace

import httpx
import jwt
import pytest

from common import llm_client as lc
from common import llm_router
from common.llm_metrics import _llm_factory
from common.llm_router import SLOT

JWT_SECRET = "public-prefix-egress-test-secret-0123456789"
SECRET = "秘制酱牛肉"


def _token(factory_id: str) -> str:
    return jwt.encode({"factoryId": factory_id, "userId": 1, "sub": "u", "role": "factory_super_admin",
                       "exp": int(time.time()) + 3600}, JWT_SECRET, algorithm="HS256")


class _Upstream:
    def __init__(self):
        self.bodies = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.bodies.append(json.loads(request.content))
        answer = json.dumps({"insights": [{"type": "summary", "title": "汇总", "text": "销量平稳",
                                           "importance": 5}]}, ensure_ascii=False)
        return httpx.Response(200, json={"choices": [{"message": {"content": answer}}]})

    def sent(self) -> str:
        return json.dumps(self.bodies, ensure_ascii=False)


@pytest.fixture
def upstream(monkeypatch):
    up = _Upstream()
    wrapper = lc._RedactingLLMClient(httpx.AsyncClient(transport=httpx.MockTransport(up.handler)))
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: wrapper)
    monkeypatch.setattr(lc, "get_llm_http_client", lambda: wrapper)
    for slot in SLOT:
        monkeypatch.setitem(llm_router.SLOT_MODELS, slot, [("aliyun_a", "qwen-plus")])
    monkeypatch.setattr(llm_router, "_refuse_reason", lambda a, m: None)
    monkeypatch.setattr(llm_router, "_cb_should_skip", lambda k: False)
    monkeypatch.setattr(llm_router, "_quota_should_skip", lambda k: False)
    monkeypatch.setattr(llm_router, "_provider_config", lambda a: ("https://llm.test/v1", "key"))

    lookups = []

    class _Conn:
        async def fetch(self, sql, *args):
            lookups.append(args[0] if args else "ALL")
            return [{"name": SECRET}]

    class _Acq:
        async def __aenter__(self):
            return _Conn()

        async def __aexit__(self, *exc):
            return False

    class _Pool:
        def acquire(self):
            return _Acq()

    async def pool():
        return _Pool()

    import smartbi.config as cfg
    monkeypatch.setattr(cfg, "get_cretas_pool", pool)
    up.lookups = lookups
    return up


def _insight_app(monkeypatch):
    from fastapi import FastAPI
    from smartbi.api import insight
    from auth_middleware import JWTAuthMiddleware

    monkeypatch.setattr(insight.insight_generator, "settings",
                        SimpleNamespace(llm_api_key="k", llm_insight_model="m"))
    app = FastAPI()
    app.include_router(insight.router, prefix="/api/insight")
    return JWTAuthMiddleware(app, jwt_secret=JWT_SECRET, enabled=True)


def _post(asgi, path, payload, headers=None):
    async def go():
        transport = httpx.ASGITransport(app=asgi)
        async with httpx.AsyncClient(transport=transport, base_url="http://t") as c:
            return await c.post(path, json=payload, headers=headers or {})
    return asyncio.run(go())


PAYLOAD = {"data": [{"产品": SECRET, "销量": 10}, {"产品": "普通牛肉", "销量": 12}], "maxInsights": 3}


def test_logged_in_factory_user_gets_a_masked_llm_call(upstream, monkeypatch):
    resp = _post(_insight_app(monkeypatch), "/api/insight/generate", PAYLOAD,
                 {"Authorization": "Bearer " + _token("F006")})
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True, body
    assert upstream.bodies, "LLM was not called"
    assert SECRET not in upstream.sent()                 # 按 F006 的保密产品名换了代号
    assert "F006" in upstream.lookups


def test_anonymous_call_is_refused_once_with_a_clear_error(upstream, monkeypatch):
    calls = []
    real = llm_router.call_chain

    async def counting(*a, **kw):
        calls.append(1)
        return await real(*a, **kw)

    import sys
    asgi = _insight_app(monkeypatch)
    from smartbi.api import insight
    # 生成器模块按 services.insights.* 导入 (与 smartbi.services.insights.* 是两个模块对象):
    # 打在生成器实际用的那一个 llm_client 上, 否则计数永远是 0 (测量-接线 C″)
    insight_llm = sys.modules[type(insight.insight_generator).__module__].llm
    monkeypatch.setattr(insight_llm, "call_chain", counting)

    resp = _post(asgi, "/api/insight/generate", PAYLOAD)
    body = resp.json()
    assert body["success"] is False
    assert "没有发出" in (body.get("error") or "")
    assert upstream.bodies == []                         # 一个字节都没发
    assert len(calls) == 1                               # 外发被拦不是供应商故障: 不重试


def test_invalid_bearer_on_a_public_prefix_is_not_a_new_401(upstream, monkeypatch):
    resp = _post(_insight_app(monkeypatch), "/api/insight/generate", PAYLOAD,
                 {"Authorization": "Bearer not-a-jwt"})
    assert resp.status_code == 200
    assert resp.json()["success"] is False               # 没有工厂 → 照旧放行, 外发拒绝
    assert upstream.bodies == []


def test_platform_wizard_masks_every_factorys_names_instead_of_refusing(upstream, monkeypatch):
    """复审 M1: 平台需求向导 (平台账号, 没有工厂) 按所有工厂的保密产品名脱敏, 不被拒绝。"""
    from client_requirement import ai_consultant
    seen = {}

    async def fake_chain(slot, payload, timeout=None):
        seen["names"] = await lc._names_for_this_request()
        return {"choices": [{"message": {"content": "ok"}}]}

    monkeypatch.setattr(llm_router, "call_chain", fake_chain)
    token = _llm_factory.set("PLATFORM")
    try:
        assert asyncio.run(ai_consultant._call_llm([{"role": "user", "content": "需求"}])) == "ok"
    finally:
        _llm_factory.reset(token)
    assert seen["names"] == [SECRET] and upstream.lookups == ["ALL"]
    # 阴性对照: 同样没有工厂、但不在向导里 → 拒绝
    with pytest.raises(lc.NoEgressFactoryError):
        asyncio.run(lc._protected_names_for_egress())


def test_manual_chat_prefix_masks_every_factorys_names_without_login(upstream):
    from auth_middleware import JWTAuthMiddleware
    seen = {}

    async def app(scope, receive, send):
        seen["factory"] = _llm_factory.get()
        seen["names"] = await lc._protected_names_for_egress()
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"ok"})

    mw = JWTAuthMiddleware(app, jwt_secret=JWT_SECRET, enabled=True)

    async def go():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=mw), base_url="http://t") as c:
            return await c.post("/api/food-kb/manual-chat", json={"question": "怎么入库"})
    assert asyncio.run(go()).status_code == 200
    assert seen == {"factory": None, "names": [SECRET]}
    assert upstream.lookups == ["ALL"]


def test_platform_token_on_insight_is_refused_with_a_platform_message(upstream, monkeypatch):
    """复审 M2: 平台账号直接对工厂数据做 AI 分析 → 拒绝 (平台读工厂数据走平台读取留痕的规则), 说明讲清楚是平台账号。"""
    resp = _post(_insight_app(monkeypatch), "/api/insight/generate", PAYLOAD,
                 {"Authorization": "Bearer " + _token("PLATFORM")})
    body = resp.json()
    assert body["success"] is False
    assert "平台账号不能直接对工厂数据做 AI 分析" in body["error"]
    assert body["message"] == body["error"]                # /generate 的 message 也带说明 (复审 Low 3)
    assert upstream.bodies == []
    # 阴性对照: 未登录的拒绝说明不提平台账号
    anon = _post(_insight_app(monkeypatch), "/api/insight/generate", PAYLOAD).json()
    assert "请登录" in anon["error"] and "平台账号" not in anon["error"]


def test_analyze_metrics_keeps_the_refusal_message(upstream, monkeypatch):
    """复审 Low 3: /analyze-metrics 以前把生成器给的 error 丢掉, 前端只看到空结果。"""
    from smartbi.api import insight
    asgi = _insight_app(monkeypatch)
    refusal = lc.egress_refusal_message(lc.EgressRedactionError("NoEgressFactoryError"))

    async def refused(**kw):
        return {"success": False, "error": refusal, "insights": []}

    monkeypatch.setattr(insight.insight_generator, "generate_insights", refused)
    resp = _post(asgi, "/api/insight/analyze-metrics", [{"metric": "销量", "value": 10}])
    body = resp.json()
    assert body["success"] is False
    assert "没有发出" in (body.get("error") or "") and "没有发出" in (body.get("message") or "")


def test_chat_stream_failure_message_names_the_egress_refusal():
    from smartbi.api.chat import _stream_failure_message
    assert "没有发出" in _stream_failure_message(lc.EgressRedactionError("NoEgressFactoryError"))
    assert _stream_failure_message(RuntimeError("boom")) == "AI对话处理失败，请稍后重试"
