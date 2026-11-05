"""Factory-only contracts for the food knowledge assistant."""
from __future__ import annotations

import json
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

import food_kb.api.manual_chat as manual_chat


class _Doc:
    title = "F006 生产 SOP"
    source = "f006-production-full-chain-sop.md"
    similarity = 0.91
    content = "生产批次必须按当前工厂 SOP 核对投入、产出和仓库确认。"


class _Retriever:
    def is_ready(self):
        return True

    async def retrieve(self, **kwargs):
        assert kwargs["subcategories"] == ["factory"]
        return [_Doc()]


def _client():
    app = FastAPI()
    app.include_router(manual_chat.router, prefix="/api/food-kb")
    return TestClient(app)


def test_manual_chat_has_no_retired_rag_surface():
    source = Path(manual_chat.__file__).read_text(encoding="utf-8")
    assert not any(term in source.lower() for term in ("restaurant", "餐饮", "restaurant_ops"))
    assert "category" not in manual_chat.ManualChatRequest.model_fields
    assert not Path(manual_chat.__file__).with_name("industry_report.py").exists()


def test_factory_route_calls_llm_and_reuses_same_domain_cache(monkeypatch):
    calls = []

    async def call_chain(_slot, payload, **_kwargs):
        calls.append(payload)
        return {"choices": [{"message": {"content": "工厂回答"}}]}

    monkeypatch.setattr(manual_chat, "get_knowledge_retriever", lambda: _Retriever())
    monkeypatch.setattr("common.llm_router.call_chain", call_chain)
    manual_chat._answer_cache.clear()
    client = _client()
    payload = {"question": "生产批次如何确认", "business_line": "stock"}
    first = client.post("/api/food-kb/manual-chat", json=payload)
    second = client.post("/api/food-kb/manual-chat", json=payload)
    assert first.status_code == second.status_code == 200
    assert first.json()["answer"] == second.json()["answer"] == "工厂回答"
    assert len(calls) == 1
    assert "工厂操作助手" in calls[0]["messages"][0]["content"]


def test_legacy_category_payload_is_ignored_and_cannot_route_retired_domain(monkeypatch):
    async def call_chain(_slot, _payload, **_kwargs):
        return {"choices": [{"message": {"content": "工厂通用回答"}}]}

    monkeypatch.setattr(manual_chat, "get_knowledge_retriever", lambda: _Retriever())
    monkeypatch.setattr("common.llm_router.call_chain", call_chain)
    manual_chat._answer_cache.clear()
    response = _client().post(
        "/api/food-kb/manual-chat",
        json={"question": "生产流程怎么走", "category": "restaurant"},
    )
    assert response.status_code == 200
    assert response.json()["answer"] == "工厂通用回答"


def test_stream_route_and_ocr_use_factory_retrieval(monkeypatch):
    async def ocr(_image):
        return "截图中的工厂批次号 F006-001"

    async def call_chain_stream(_slot, payload, **_kwargs):
        assert "F006-001" in payload["messages"][-1]["content"]
        yield {"type": "delta", "text": "工厂"}
        yield {"type": "delta", "text": "流式回答"}

    monkeypatch.setattr(manual_chat, "get_knowledge_retriever", lambda: _Retriever())
    monkeypatch.setattr(manual_chat, "_ocr_extract_text", ocr)
    monkeypatch.setattr("common.llm_router.call_chain_stream", call_chain_stream)
    manual_chat._answer_cache.clear()
    response = _client().post(
        "/api/food-kb/manual-chat/stream",
        json={"question": "批次状态", "image_base64": "aGVsbG8="},
    )
    assert response.status_code == 200
    assert "工厂流式回答" in response.text
    assert '"success": true' in response.text
