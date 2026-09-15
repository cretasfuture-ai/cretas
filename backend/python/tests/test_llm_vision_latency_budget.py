"""拍照核验(/api/llm/vision)的延迟预算必须真的传进 call_chain。

2026-09-15 真机彩排: 关思考后 DashScope 仍有随机长尾(23.5s/28.2s), 而 Java 侧
readTimeout=30s —— 单次 30s 的默认预算等于「撞一次长尾就 502」。这里守的是
「vision 端点把 12s/27s 传给了 call_chain」这条接线, ⛔ 不是常量的字面值。
"""
from __future__ import annotations

import io
from unittest import mock

from fastapi import FastAPI
from fastapi.testclient import TestClient

import llm.api.endpoints as endpoints


def _client() -> TestClient:
    app = FastAPI()
    app.include_router(endpoints.router, prefix="/api/llm")
    return TestClient(app, raise_server_exceptions=False)


def test_vision_endpoint_passes_attempt_and_total_budget_to_call_chain() -> None:
    seen: dict = {}

    async def fake_call_chain(slot, payload, **kw):
        seen["slot"] = slot
        seen["kw"] = kw
        return {"choices": [{"message": {"content": "{}"}}]}

    with mock.patch.object(endpoints, "call_chain", fake_call_chain):
        resp = _client().post(
            "/api/llm/vision",
            files={"image": ("tag.jpg", io.BytesIO(bytes([0xFF, 0xD8, 0xFF, 0xD9])), "image/jpeg")},
            data={"prompt": "读出编号, 返回 JSON"},
        )
    assert resp.status_code == 200, resp.text
    assert seen["kw"].get("timeout") == endpoints.VL_ATTEMPT_TIMEOUT_S, (
        f"单次预算没传进 call_chain: {seen['kw']!r}")
    assert seen["kw"].get("total_timeout") == endpoints.VL_TOTAL_TIMEOUT_S, (
        f"级联总预算没传进 call_chain: {seen['kw']!r}")
    # Java aiServiceHttpClient readTimeout=30s: 级联必须在它之前结束, 单次必须能重试一次。
    assert endpoints.VL_TOTAL_TIMEOUT_S < 30.0
    assert endpoints.VL_ATTEMPT_TIMEOUT_S * 2 <= endpoints.VL_TOTAL_TIMEOUT_S
