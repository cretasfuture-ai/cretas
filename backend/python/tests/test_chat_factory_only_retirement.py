"""Contract tests for the factory-only chat surface after restaurant retirement."""
from __future__ import annotations

import time
from pathlib import Path

import jwt
from fastapi import FastAPI
from fastapi.testclient import TestClient

import smartbi.api.chat as chat
from auth_middleware import JWTAuthMiddleware
from common.insight_cache import get_insight_cache


JWT_SECRET = "chat-factory-only-contract-secret"
FACTORY = "F-CHAT-CONTRACT"


def _client() -> TestClient:
    app = FastAPI()
    app.add_middleware(JWTAuthMiddleware, jwt_secret=JWT_SECRET, enabled=True)
    app.include_router(chat.router, prefix="/api/chat")
    return TestClient(app)


def _token() -> str:
    return jwt.encode(
        {
            "factoryId": FACTORY,
            "userId": 7,
            "role": "factory_super_admin",
            "exp": int(time.time()) + 600,
        },
        JWT_SECRET,
        algorithm="HS256",
    )


def test_chat_source_has_no_retired_business_surface():
    source = Path(chat.__file__).read_text(encoding="utf-8")
    forbidden = (
        "restaurant",
        "RESTAURANT",
        "_try_tiered_restaurant_intent",
        "_trusted_restaurant_session_key",
        "_restaurant_llm_history",
        "gold.restaurant",
        "restaurant_ops",
        "expected_intent",
    )
    assert not [term for term in forbidden if term in source]
    assert "table_type" not in chat.GeneralAnalysisRequest.model_fields


def test_factory_chat_reaches_mocked_llm_and_returns_answer(monkeypatch):
    calls = []

    class _Generator:
        async def generate_text_analysis(self, query):
            calls.append(query)
            return "工厂分析结果"

    async def no_pool():
        return None

    monkeypatch.setattr(chat, "InsightGenerator", _Generator)
    monkeypatch.setattr("smartbi.config.get_pg_pool", no_pool)
    get_insight_cache().clear()
    response = _client().post(
        "/api/chat/general-analysis",
        headers={"Authorization": f"Bearer {_token()}"},
        json={"message": "分析本工厂的产量趋势", "allow_tenant_data_fallback": False},
    )
    assert response.status_code == 200, response.text
    assert response.json()["success"] is True
    assert response.json()["answer"] == "工厂分析结果"
    assert calls == ["分析本工厂的产量趋势"]


def test_legacy_restaurant_table_hint_is_not_a_chat_model_or_route(monkeypatch):
    class _Generator:
        async def generate_text_analysis(self, query):
            return "工厂通用分析"

    async def no_pool():
        return None

    monkeypatch.setattr(chat, "InsightGenerator", _Generator)
    monkeypatch.setattr("smartbi.config.get_pg_pool", no_pool)
    get_insight_cache().clear()
    response = _client().post(
        "/api/chat/general-analysis",
        headers={"Authorization": f"Bearer {_token()}"},
        json={
            "message": "分析产量",
            "table_type": "restaurant_ops",
            "allow_tenant_data_fallback": False,
        },
    )
    assert response.status_code == 200, response.text
    assert response.json()["answer"] == "工厂通用分析"
