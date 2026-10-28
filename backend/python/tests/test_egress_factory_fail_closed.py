"""Python 外发按哪家工厂脱敏, 没有工厂时 fail-closed (Steve 2026-10-29, 复审 M-C; 与 Java LlmEgressGuard 一致)。

- 有工厂 (JWT / Java 带 X-Factory-Id) → 按那家的保密产品名 (阴性对照: 正常工厂请求照常);
- Java 内部调用没带工厂 / 平台建厂 → 按所有工厂的保密产品名 (不拒绝, 也不原样发);
- 其余没有工厂 (定时任务、平台账号 token 的 PLATFORM) → NoEgressFactoryError, 出口包成 EgressRedactionError 不发;
- 意图识别原话模式 (已知例外) → 不查库、不拒绝。
"""
from __future__ import annotations

import asyncio

import pytest

from common import llm_client as lc
from common.llm_metrics import _llm_factory


class _Pool:
    def __init__(self):
        self.calls = []

    def acquire(self):
        pool = self

        class _Acquire:
            async def __aenter__(self):
                class _Conn:
                    async def fetch(self, sql, *args):
                        pool.calls.append(("ALL" if not args else args[0]))
                        return [{"name": "秘制酱牛肉"}] if args else [{"name": "秘制酱牛肉"}, {"name": "乙厂秘方鸡"}]
                return _Conn()

            async def __aexit__(self, *exc):
                return False
        return _Acquire()


@pytest.fixture
def pool(monkeypatch):
    p = _Pool()

    async def get_pool():
        return p

    import smartbi.config as cfg
    monkeypatch.setattr(cfg, "get_cretas_pool", get_pool)
    return p


async def test_factory_request_uses_that_factorys_names(pool):
    token = _llm_factory.set("F006")
    try:
        assert await lc._protected_names_for_egress() == ["秘制酱牛肉"]
    finally:
        _llm_factory.reset(token)
    assert pool.calls == ["F006"]


@pytest.mark.parametrize("factory", [None, "", "PLATFORM", "platform"])
async def test_no_factory_is_refused(pool, factory):
    token = _llm_factory.set(factory)
    try:
        with pytest.raises(lc.NoEgressFactoryError):
            await lc._protected_names_for_egress()
    finally:
        _llm_factory.reset(token)
    assert pool.calls == []


async def test_internal_call_without_factory_masks_every_factorys_names(pool):
    with lc.all_factories_egress():
        names = await lc._protected_names_for_egress()
    assert names == ["乙厂秘方鸡", "秘制酱牛肉"]
    assert pool.calls == ["ALL"]


async def test_redacting_client_blocks_the_send_without_factory(pool):
    sent = []

    class _Inner:
        async def post(self, url, **kw):
            sent.append(kw)

    client = lc._RedactingLLMClient(_Inner())
    with pytest.raises(lc.EgressRedactionError):
        await client.post("https://x/chat/completions",
                          json={"messages": [{"role": "user", "content": "秘制酱牛肉今天产量"}]})
    assert sent == []


async def test_utterance_mode_is_the_known_exception(pool):
    from common.llm_redactor import utterance_egress
    with utterance_egress():
        assert await lc._names_for_this_request() == []
    assert pool.calls == []


# ------------------------------------------------------------------ auth_middleware 内部分支

def _drive_internal(monkeypatch, headers):
    from auth_middleware import JWTAuthMiddleware
    monkeypatch.setenv("INTERNAL_API_SECRET", "s3cret")
    seen = {}

    async def app(scope, receive, send):
        seen["factory"] = _llm_factory.get()
        seen["all"] = lc._egress_all_factories.get()
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"ok"})

    mw = JWTAuthMiddleware(app, jwt_secret="x", enabled=True)
    scope = {"type": "http", "path": "/api/smartbi/excel/upload",
             "headers": [(k.encode(), v.encode()) for k, v in {"x-internal-secret": "s3cret", **headers}.items()]}

    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(_msg):
        pass

    asyncio.run(mw(scope, receive, send))
    return seen


def test_internal_call_with_factory_header_binds_that_factory(monkeypatch):
    seen = _drive_internal(monkeypatch, {"x-factory-id": "F006"})
    assert seen == {"factory": "F006", "all": False}
    assert _llm_factory.get() is None and lc._egress_all_factories.get() is False  # 结束后还原


def test_internal_call_without_factory_masks_all_factories(monkeypatch):
    seen = _drive_internal(monkeypatch, {})
    assert seen == {"factory": None, "all": True}
    assert lc._egress_all_factories.get() is False
