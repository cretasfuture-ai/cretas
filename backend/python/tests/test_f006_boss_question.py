"""Pilot auth/stream acceptance; all model traffic uses an in-process transport."""
import asyncio
import json

import httpx
import pytest
from fastapi import FastAPI

from auth_middleware import JWTAuthMiddleware
from llm.api.f006_boss_question import router
from llm import f006_mini_provider as provider

PATH = "/api/llm/f006-boss-question-stream"
SECRET = "unit-test-internal-secret-only"


def app():
    api = FastAPI()
    api.include_router(router, prefix="/api/llm")
    api.add_middleware(JWTAuthMiddleware, jwt_secret="unit-test-jwt-only", enabled=False)
    return api


def headers(**overrides):
    result = {"X-Internal-Secret": SECRET, "X-Factory-Id": "F006",
              "X-User-Id": "7", "X-User-Role": "factory_super_admin"}
    result.update(overrides)
    return result


def request(api, auth=None, body=None):
    async def call():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=api), base_url="http://unit") as client:
            return await client.post(PATH, headers=auth or {}, json=body or {"question": "如何检查冷链？"})
    return asyncio.run(call())


@pytest.mark.parametrize("secret", [None, "", "wrong"])
def test_exact_route_never_inherits_public_prefix_or_disabled_auth(monkeypatch, secret):
    if secret is None:
        monkeypatch.delenv("INTERNAL_API_SECRET", raising=False)
    else:
        monkeypatch.setenv("INTERNAL_API_SECRET", secret)
    monkeypatch.setenv("F006_BOSS_MINI_ENABLED", "1")
    assert request(app(), headers()).status_code == 401


@pytest.mark.parametrize("auth", [{}, headers(**{"X-Internal-Secret": ""}),
                                  headers(**{"X-Internal-Secret": "wrong"})])
def test_missing_or_incorrect_service_secret_rejected(monkeypatch, auth):
    monkeypatch.setenv("INTERNAL_API_SECRET", SECRET)
    assert request(app(), auth).status_code == 401


@pytest.mark.parametrize("override", [{"X-Factory-Id": "F007"}, {"X-Factory-Id": ""},
    {"X-User-Role": "finance_manager"}, {"X-User-Role": "platform_admin"},
    {"X-User-Id": ""}, {"X-User-Id": "zero"}, {"X-User-Id": "0"},
    {"X-User-Id": "9223372036854775808"}])
def test_wrong_principal_rejected_before_model(monkeypatch, override):
    monkeypatch.setenv("INTERNAL_API_SECRET", SECRET)
    monkeypatch.setenv("F006_BOSS_MINI_ENABLED", "1")
    assert request(app(), headers(**override)).status_code == 403


def test_feature_is_default_off(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", SECRET)
    monkeypatch.delenv("F006_BOSS_MINI_ENABLED", raising=False)
    assert request(app(), headers()).status_code == 503


@pytest.mark.parametrize("body", [{"question": " "}, {"question": "x" * 2001},
    {"question": "问题", "factory_id": "F007"}, {"question": "问题", "model": "other"},
    {"question": "问题", "tools": []}, {"question": "问题", "images": []},
    {"question": "问题", "url": "https://outside.invalid"}])
def test_request_cannot_override_identity_model_tools_or_egress(monkeypatch, body):
    monkeypatch.setenv("INTERNAL_API_SECRET", SECRET)
    monkeypatch.setenv("F006_BOSS_MINI_ENABLED", "1")
    assert request(app(), headers(), body).status_code == 422


class Lines(httpx.AsyncByteStream):
    def __init__(self, frames, slow=False):
        self.frames = frames
        self.slow = slow

    async def __aiter__(self):
        if self.slow:
            await asyncio.sleep(0.2)
        for frame in self.frames:
            yield (json.dumps(frame, ensure_ascii=False) + "\n").encode()


def frame(text="", **extra):
    result = {"model": provider.MODEL, "message": {"role": "assistant", "content": text},
              "done": False, **extra}
    if result.get("done") is True and "done_reason" not in extra:
        result["done_reason"] = "stop"
    return result


def install_model(monkeypatch, frames, status=200, slow=False):
    real_client = httpx.AsyncClient
    observed = {"requests": [], "metrics": [], "audits": [], "client": []}

    async def handler(req):
        observed["requests"].append(req)
        return httpx.Response(status, stream=Lines(frames, slow), request=req)

    def factory(**kwargs):
        observed["client"].append(kwargs)
        return real_client(transport=httpx.MockTransport(handler), **kwargs)

    monkeypatch.setattr(provider.httpx, "AsyncClient", factory)
    monkeypatch.setattr(provider, "record_stream_usage", lambda *a, **kw: observed["metrics"].append((a, kw)))
    monkeypatch.setattr(provider, "record_egress", lambda record: observed["audits"].append(record))
    return observed


def collect(thinking=False):
    async def run():
        return [event async for event in provider.stream_question("如何检查冷链？", thinking)]
    return asyncio.run(run())


def test_real_deltas_terminal_usage_and_fixed_server_model(monkeypatch):
    observed = install_model(monkeypatch, [frame("先检查"), frame("温度。", done=True,
                            prompt_eval_count=10, eval_count=4, done_reason="stop")])
    events = collect(True)
    assert [event["type"] for event in events] == ["meta", "delta", "delta", "done"]
    assert events[-1]["content"] == "先检查温度。"
    assert events[-1]["usage"] == {"inputTokens": 10, "outputTokens": 4, "totalTokens": 14}
    assert observed["metrics"][0][0] == ("mac-mini", provider.MODEL, 10, 4, 14)
    req = observed["requests"][0]
    payload = json.loads(req.content)
    assert str(req.url) == "http://127.0.0.1:11435/api/chat"
    assert payload["model"] == provider.MODEL and payload["think"] is True
    assert set(payload) == {"model", "messages", "stream", "think", "options"}
    assert observed["client"][0]["trust_env"] is False
    audit = observed["audits"][0]
    assert audit.factory_id == "F006" and audit.status_code == 200
    assert audit.prompt_sha256 and not hasattr(audit, "prompt")


def test_missing_usage_remains_unknown_not_zero(monkeypatch):
    observed = install_model(monkeypatch, [frame("检查温度。", done=True)])
    terminal = collect()[-1]
    assert terminal["type"] == "done" and terminal["usageAvailable"] is False
    assert terminal["usage"] is None and observed["metrics"] == []


@pytest.mark.parametrize("frames,status", [([], 200), ([frame("半个回答")], 200),
    ([frame(done=True)], 200), ([{"error": "sensitive upstream detail"}], 200),
    ([frame("答案", model="other", done=True)], 200), ([], 500),
    ([frame("x", message={"tool_calls": [{}]})], 200),
    ([frame("答案", done=True, prompt_eval_count=-1, eval_count=2)], 200)])
def test_stream_failures_do_not_report_completion_or_cloud_fallback(monkeypatch, frames, status):
    observed = install_model(monkeypatch, frames, status)
    events = collect()
    assert events[-1]["type"] == "error"
    assert not any(event["type"] == "done" for event in events)
    assert observed["audits"][0].status_code == 502
    assert len(observed["requests"]) == 1
    assert observed["metrics"] == []
    assert "sensitive upstream detail" not in str(events)


@pytest.mark.parametrize("reason", ["missing", None, 13, "length", "unexpected"])
def test_incomplete_terminal_after_real_delta_never_reports_done(monkeypatch, reason):
    terminal = frame("", done=True, prompt_eval_count=10, eval_count=4)
    if reason == "missing":
        terminal.pop("done_reason")
    else:
        terminal["done_reason"] = reason
    observed = install_model(monkeypatch, [frame("半个回答"), terminal])
    events = collect()
    assert any(event.get("content") == "半个回答" for event in events if event["type"] == "delta")
    assert events[-1]["type"] == "error" and events[-1]["code"] == "MINI_INCOMPLETE"
    assert not any(event["type"] in ("done", "result", "complete") for event in events)
    assert observed["audits"][0].status_code == 502
    assert len(observed["requests"]) == 1 and observed["metrics"] == []
    assert not provider._inflight.locked()


def test_total_timeout_and_lock_release(monkeypatch):
    observed = install_model(monkeypatch, [frame("慢", done=True)], slow=True)
    monkeypatch.setattr(provider, "TOTAL_TIMEOUT_S", 0.01)
    assert collect()[-1]["code"] == "MINI_TIMEOUT"
    assert not provider._inflight.locked()
    assert observed["audits"][0].status_code == 504


def test_busy_rejects_without_any_outbound_model_request(monkeypatch):
    observed = install_model(monkeypatch, [])

    async def run():
        await provider._inflight.acquire()
        try:
            return [event async for event in provider.stream_question("问题", False)]
        finally:
            provider._inflight.release()

    assert asyncio.run(run())[0]["code"] == "MINI_BUSY"
    assert observed["requests"] == [] and observed["audits"] == []


@pytest.mark.parametrize("usage", [{"prompt_eval_count": 1}, {"eval_count": 1},
    {"prompt_eval_count": True, "eval_count": 2}, {"prompt_eval_count": "10", "eval_count": 2},
    {"prompt_eval_count": None, "eval_count": None}])
def test_present_but_corrupt_usage_is_not_an_unknown_success(monkeypatch, usage):
    observed = install_model(monkeypatch, [frame("答案", done=True, **usage)])
    assert collect()[-1]["type"] == "error"
    assert observed["metrics"] == []


def test_audit_failure_does_not_replace_answer_or_hold_pilot_lock(monkeypatch):
    install_model(monkeypatch, [frame("答案", done=True)])

    def fail(_record):
        raise RuntimeError("private audit failure")

    monkeypatch.setattr(provider, "record_egress", fail)
    events = collect()
    assert events[-1]["type"] == "done"
    assert not provider._inflight.locked()
    assert "private audit failure" not in str(events)


def test_usage_record_failure_preserves_real_usage_and_done(monkeypatch):
    install_model(monkeypatch, [frame("答案", done=True, prompt_eval_count=10, eval_count=3)])

    def fail(*_args, **_kwargs):
        raise RuntimeError("private metrics failure")

    monkeypatch.setattr(provider, "record_stream_usage", fail)
    terminal = collect()[-1]
    assert terminal["type"] == "done" and terminal["usage"]["totalTokens"] == 13
    assert not provider._inflight.locked()


def test_other_process_lease_or_lock_failure_never_calls_model(monkeypatch):
    observed = install_model(monkeypatch, [])

    def busy():
        raise provider.MiniBusy()

    monkeypatch.setattr(provider, "acquire_host_lease", busy)
    assert collect()[-1]["code"] == "MINI_BUSY"
    assert not provider._inflight.locked() and observed["requests"] == []

    def unavailable():
        raise OSError("private lock path")

    monkeypatch.setattr(provider, "acquire_host_lease", unavailable)
    assert collect()[-1]["code"] == "MINI_UNAVAILABLE"
    assert not provider._inflight.locked() and observed["requests"] == []


def test_closing_generator_after_first_event_releases_lock_and_connection(monkeypatch):
    observed = install_model(monkeypatch, [frame("答案", done=True)])

    async def run():
        generator = provider.stream_question("问题", False)
        assert (await anext(generator))["type"] == "meta"
        assert provider._inflight.locked()
        await generator.aclose()

    asyncio.run(run())
    assert not provider._inflight.locked()
    assert len(observed["requests"]) == 1
    assert observed["metrics"] == []
    assert observed["audits"][0].status_code == 502


def test_authenticated_f006_route_streams_without_body_identity(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", SECRET)
    monkeypatch.setenv("F006_BOSS_MINI_ENABLED", "1")
    from llm.api import f006_boss_question as endpoint
    observed = []

    async def fake(question, thinking):
        observed.append((question, thinking))
        yield {"type": "delta", "content": "检查冷链。"}
        yield {"type": "done", "content": "检查冷链。"}

    monkeypatch.setattr(endpoint, "stream_question", fake)
    result = request(app(), headers(), {"question": "  如何检查冷链？  ", "enable_thinking": True})
    assert result.status_code == 200 and "检查冷链。" in result.text
    assert observed == [("如何检查冷链？", True)]


def test_production_parent_router_mounts_exact_authenticated_pilot(monkeypatch):
    from llm.api.endpoints import router as production_router
    from llm.api import f006_boss_question as endpoint

    api = FastAPI()
    api.include_router(production_router, prefix="/api/llm")
    api.add_middleware(JWTAuthMiddleware, jwt_secret="unit-test-jwt-only", enabled=False)
    routes = [route for route in api.routes if getattr(route, "path", None) == PATH]
    assert len(routes) == 1 and routes[0].methods == {"POST"}
    observed = []

    async def fake(question, thinking):
        observed.append((question, thinking))
        yield {"type": "done", "content": "检查冷链。"}

    monkeypatch.setattr(endpoint, "stream_question", fake)
    monkeypatch.setenv("INTERNAL_API_SECRET", SECRET)
    monkeypatch.setenv("F006_BOSS_MINI_ENABLED", "1")
    assert request(api).status_code == 401
    assert request(api, headers(**{"X-Factory-Id": "F007"})).status_code == 403
    assert observed == []
    result = request(api, headers())
    assert result.status_code == 200 and "检查冷链。" in result.text
    assert observed == [("如何检查冷链？", False)]
