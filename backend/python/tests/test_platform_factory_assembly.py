import json
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock

import jwt
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from factory_assembly import api, generation

SECRET = "platform-assembly-contract-secret-only"
INTERNAL = "platform-assembly-contract-internal-only"


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("CRETAS_PLATFORM_FACTORY_ASSEMBLY_ENABLED", "true")
    monkeypatch.setenv("INTERNAL_API_SECRET", INTERNAL)
    monkeypatch.setattr(api, "get_settings", lambda: SimpleNamespace(jwt_secret=SECRET))
    app = FastAPI()
    app.include_router(api.router)
    return TestClient(app)


def bearer(**updates):
    claims = dict(userId=42, actorType="PLATFORM_ADMIN", factoryId="PLATFORM",
                  role="super_admin", exp=int(time.time()) + 60)
    claims.update(updates)
    return {"Authorization": "Bearer " + jwt.encode(claims, SECRET, algorithm="HS256"),
            "X-Internal-Secret": INTERNAL}


@pytest.mark.parametrize("claims", [
    {"actorType": "FACTORY_USER", "factoryId": "F1"},
    {"actorType": None}, {"factoryId": "F1"}, {"role": "platform_admin"},
    {"userId": 0}, {"userId": True}, {"exp": 1},
])
def test_rejected_namespace_never_generates(client, monkeypatch, claims):
    generate = AsyncMock(return_value={"success": True, "data": {"schemas": [], "suggestedData": {}}})
    monkeypatch.setattr(api, "generate_configuration", generate)
    response = client.post("/generate", headers=bearer(**claims),
                           json={"factory_description": "面包工厂"})
    assert response.status_code in (401, 403)
    generate.assert_not_awaited()


def test_disabled_or_missing_token_never_generates(client, monkeypatch):
    generate = AsyncMock(return_value={"success": True, "data": {"schemas": [], "suggestedData": {}}})
    monkeypatch.setattr(api, "generate_configuration", generate)
    assert client.post("/generate", headers={"X-Internal-Secret": INTERNAL},
                       json={"factory_description": "面包工厂"}).status_code == 401
    monkeypatch.delenv("CRETAS_PLATFORM_FACTORY_ASSEMBLY_ENABLED")
    assert client.post("/generate", headers=bearer(), json={"factory_description": "面包工厂"}).status_code == 404
    generate.assert_not_awaited()


def test_client_jwt_cannot_bypass_live_java_authority(client, monkeypatch):
    generate = AsyncMock(return_value={"success": True})
    monkeypatch.setattr(api, "generate_configuration", generate)
    headers = bearer()
    headers.pop("X-Internal-Secret")
    assert client.post("/generate", headers=headers, json={"factory_description": "面包工厂"}).status_code == 403
    headers["X-Internal-Secret"] = "wrong"
    assert client.post("/generate", headers=headers, json={"factory_description": "面包工厂"}).status_code == 403
    monkeypatch.delenv("INTERNAL_API_SECRET")
    assert client.post("/generate", headers=bearer(), json={"factory_description": "面包工厂"}).status_code == 403
    generate.assert_not_awaited()


def test_explicit_platform_receives_single_structured_envelope(client, monkeypatch):
    expected = {"success": True, "data": {"schemas": [], "suggestedData": {"productTypes": []}}}
    generate = AsyncMock(return_value=expected)
    monkeypatch.setattr(api, "generate_configuration", generate)
    response = client.post("/generate", headers=bearer(), json={"factory_description": "面包工厂"})
    assert response.status_code == 200
    assert response.json() == expected
    generate.assert_awaited_once()


@pytest.mark.asyncio
async def test_generation_preserves_product_unit_and_retries_invalid_json(monkeypatch):
    candidate = {"industryCode": "bakery", "industryName": "烘焙",
        "schemas": [{"entityType": "MATERIAL_BATCH", "entityName": "原料批次", "fields": [{"key": "name", "type": "text"}]}],
        "suggestedData": {
        "productTypes": [{"code": "BREAD", "name": "原味面包", "unit": "个"}],
        "materialTypes": [{"code": "FLOUR", "name": "面粉", "unit": "kg"}],
        "conversionRates": [], "workProcesses": []}}
    llm = AsyncMock(side_effect=["not json", json.dumps(candidate, ensure_ascii=False)])
    monkeypatch.setattr(generation, "_call_llm", llm)
    result = await generation.generate_configuration(generation.FactoryInitRequest(
        factory_description="主要成品：原味面包。单位个。", factory_name="验收工厂"))
    assert result["success"] is True
    assert result["data"]["suggestedData"]["productTypes"][0]["unit"] == "个"
    assert result["data"]["suggestedData"]["provisionDefaultUsers"] is False
    assert result["data"]["factoryName"] == "验收工厂"
    assert llm.await_count == 2


@pytest.mark.asyncio
async def test_invalid_contract_is_not_reported_as_success(monkeypatch):
    monkeypatch.setattr(generation, "_call_llm", AsyncMock(return_value='{"configuration":"text only"}'))
    result = await generation.generate_configuration(generation.FactoryInitRequest(factory_description="面包工厂"))
    assert result["success"] is False
    assert result["data"] is None


@pytest.mark.asyncio
async def test_cache_separates_all_prompt_inputs_and_reuses_only_exact_request(monkeypatch):
    from common import llm_router
    candidate = {"industryCode": "bakery", "industryName": "烘焙",
        "schemas": [{"entityType": "MATERIAL_BATCH", "entityName": "原料批次", "fields": [{"key": "name"}]}],
        "suggestedData": {"productTypes": [], "materialTypes": [], "conversionRates": []}}
    calls = []

    async def stream(slot, payload, **kwargs):
        calls.append(payload)
        yield {"type": "delta", "text": json.dumps(candidate)}

    monkeypatch.setattr(llm_router, "call_chain_stream", stream)
    monkeypatch.setattr(generation, "_ai_proxy_cache", generation.AiProxyCache())
    base = dict(factory_description="同样的描述", factory_name="甲厂", industry_hint="烘焙", include_business_data=True)
    requests = [base, {**base, "factory_name": "乙厂"}, {**base, "industry_hint": "饮料"},
                {**base, "include_business_data": False}, base]
    for request in requests:
        result = await generation.generate_configuration(generation.FactoryInitRequest(**request))
        assert result["success"] is True
        assert result["data"]["factoryName"] == request["factory_name"]
    assert len(calls) == 4
