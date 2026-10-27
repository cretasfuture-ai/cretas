"""
Shared LLM HTTP Client

Provides a process-wide httpx.AsyncClient singleton with connection pooling
for all DashScope LLM API calls. Eliminates per-request DNS+TLS handshake
overhead (~200ms savings on subsequent calls).

Usage:
    from common.llm_client import get_llm_http_client
    client = get_llm_http_client()
    resp = await client.post(url, json=payload, timeout=httpx.Timeout(30.0))
"""
from __future__ import annotations

import logging
from typing import Any, Optional

import httpx

logger = logging.getLogger(__name__)

_client: Optional[httpx.AsyncClient] = None
_wrapper: "Optional[_RedactingLLMClient]" = None


class EgressRedactionError(RuntimeError):
    """脱敏失败 → 请求被拦下, 没有任何内容出境。

    这是本地确定性失败, 不是供应商故障: 调用方 (llm_router) 必须直接抛出,
    不得换候选重试, 也不得记入熔断。
    """


class _RedactingLLMClient:
    """P0 数据主权 — 共享 httpx 客户端的脱敏包装层。

    所有 LLM 调用 (insights / Java 意图 / Excel 字段识别/映射/清洗/结构分析/评论分析
    / 跨表聚合 等) 都经 get_llm_http_client() 拿到这个 client。在 post/stream 出境前对
    `json` payload **字典级**脱敏 (无 httpx 内部 hack), 再委托给真实客户端。

    脱敏 scope-aware: insights generator 设了 RedactionScope 时, 客户名/门店名/菜品名
    用 known-values 占位 + 输出还原; 无 scope 时对所有出境施加 PII + factory 兜底。

    Fail-closed: 任何脱敏异常 → CRITICAL 日志 + 抛 EgressRedactionError, 不发送任何内容。
    (曾是 fail-open 发原 payload; 工艺保密改造起禁止: 脱敏失败绝不换账号/模型后发原文。)
    审计在 metrics_response_hook 统一记录 (universal, 含真实 status_code)。
    """

    def __init__(self, inner: httpx.AsyncClient) -> None:
        self._inner = inner

    @staticmethod
    def _is_llm(url: Any, json_payload: Any) -> bool:
        return json_payload is not None and "/chat/completions" in str(url)

    async def _redact(self, url: Any, json_payload: Any) -> Any:
        """返回 (发出去的 payload, 本次代号表)。保密产品名每次现查 (改保密设置立即生效), 查不了就不发。"""
        if not self._is_llm(url, json_payload):
            _egress_tokens.set({})
            return json_payload, {}
        try:
            from common.llm_redactor import redact_payload_for_egress
            from common.llm_metrics import set_egress_redaction_meta
            redacted, meta = redact_payload_for_egress(json_payload, await _protected_names_for_egress())
            set_egress_redaction_meta(meta)
            _egress_tokens.set(meta.egress_tokens)
            return redacted, meta.egress_tokens
        except Exception as e:  # fail-closed: never send the unredacted payload
            logger.critical(
                "[P0-redact] egress redaction FAILED, request BLOCKED (nothing sent): %s",
                type(e).__name__,
            )
            raise EgressRedactionError(type(e).__name__) from e

    async def post(self, url: Any, *, json: Any = None, **kwargs: Any):  # noqa: A002
        json, tokens = await self._redact(url, json)
        response = await self._inner.post(url, json=json, **kwargs)
        return _restored_response(response, tokens)

    def stream(self, method: Any, url: Any, *, json: Any = None, **kwargs: Any):  # noqa: A002
        # 流式: 进入时才脱敏 (要先查保密产品名); 增量里的代号由 llm_router.call_chain_stream 用
        # current_egress_tokens() 边收边换回 (代号可能被拆在两段增量里)
        return _RedactingStream(self, method, url, json, kwargs)

    async def aclose(self):
        return await self._inner.aclose()

    def __getattr__(self, name: str):
        # Delegate everything else (headers, is_closed, build_request, ...) to the inner client.
        return getattr(self._inner, name)


import contextvars  # noqa: E402

_egress_tokens: contextvars.ContextVar[dict] = contextvars.ContextVar("llm_egress_tokens", default={})


def current_egress_tokens() -> dict:
    """最近一次经本客户端发出的请求的代号表 (同一协程内)。"""
    return _egress_tokens.get()


async def _protected_names_for_egress() -> list:
    """当前请求工厂的工艺保密产品名, 每次现查 (不走 60 秒缓存: 刚设为保密的产品名不能再原样发出去)。
    没有工厂上下文 (定时任务等) 时为空, 与 Java 一致; 查库出错向上抛 → 不发。"""
    try:
        from common.llm_metrics import _llm_factory
        factory_id = _llm_factory.get()
    except Exception:
        factory_id = None
    if not factory_id:
        return []
    from smartbi.config import get_cretas_pool
    from smartbi.services.process_secret_names import PROTECTED_NAMES_SQL, usable_names
    import asyncio

    async def _read() -> list:
        pool = await get_cretas_pool()
        if pool is None:
            raise RuntimeError("cretas database pool unavailable")
        async with pool.acquire() as conn:
            rows = await conn.fetch(PROTECTED_NAMES_SQL, factory_id)
        return sorted(usable_names(r["name"] for r in rows))

    # 库卡住时不让外发请求一起卡住: 5 秒没查到按查不了处理 (不发)
    return await asyncio.wait_for(_read(), timeout=_PROTECTED_NAMES_TIMEOUT_S)


_PROTECTED_NAMES_TIMEOUT_S = 5.0


def _restored_response(response: httpx.Response, tokens: dict) -> httpx.Response:
    """非流式回答: 把本次发出去的代号换回真名 (去括号 / 全角括号等改写也认)。JSON 先解析再逐个字符串换,
    真名带引号也不会弄坏 JSON; 解析不了按纯文本换。"""
    if not tokens or not isinstance(response, httpx.Response):
        return response
    import json as _json
    from common.llm_egress_text import restore

    def _deep(node: Any) -> Any:
        if isinstance(node, dict):
            return {(restore(k, tokens) if isinstance(k, str) else k): _deep(v) for k, v in node.items()}
        if isinstance(node, list):
            return [_deep(x) for x in node]
        return restore(node, tokens) if isinstance(node, str) else node

    try:
        body = _json.dumps(_deep(response.json()), ensure_ascii=False).encode("utf-8")
    except ValueError:
        body = (restore(response.text, tokens) or "").encode("utf-8")
    headers = {k: v for k, v in response.headers.items()
               if k.lower() not in ("content-length", "content-encoding", "transfer-encoding")}
    return httpx.Response(response.status_code, headers=headers, content=body, request=response.request)


class _RedactingStream:
    """``async with client.stream(...)``: 进入时脱敏 (异步查保密产品名) 再打开上游流。"""

    def __init__(self, client: "_RedactingLLMClient", method: Any, url: Any, json_payload: Any, kwargs: dict):
        self._client, self._method, self._url, self._json, self._kwargs = client, method, url, json_payload, kwargs
        self._cm = None

    async def __aenter__(self):
        payload, _tokens = await self._client._redact(self._url, self._json)
        self._cm = self._client._inner.stream(self._method, self._url, json=payload, **self._kwargs)
        return await self._cm.__aenter__()

    async def __aexit__(self, *exc):
        return await self._cm.__aexit__(*exc) if self._cm is not None else False


def _ensure_inner_client() -> httpx.AsyncClient:
    global _client
    if _client is None:
        logger.warning("Shared LLM client not initialized, creating fallback")
        _client = httpx.AsyncClient(
            limits=httpx.Limits(
                max_connections=20,
                max_keepalive_connections=10,
                keepalive_expiry=30,
            ),
            timeout=httpx.Timeout(120.0),
        )
    return _client


def get_llm_http_client() -> "_RedactingLLMClient":
    """
    Return the shared LLM HTTP client wrapped in the P0 redaction layer.

    If init_llm_client() hasn't been called yet (e.g. during testing or
    lazy startup), creates a fallback inner client with default pool settings.
    The wrapper redacts every /chat/completions payload before egress.
    """
    global _wrapper
    inner = _ensure_inner_client()
    if _wrapper is None or _wrapper._inner is not inner:
        _wrapper = _RedactingLLMClient(inner)
    return _wrapper


async def init_llm_client(base_url: str, api_key: str) -> None:
    """
    Initialize the shared LLM HTTP client with connection pool.

    Call once during application startup (lifespan).

    Args:
        base_url: DashScope API base URL (e.g. https://dashscope.aliyuncs.com/compatible-mode/v1)
        api_key: DashScope API key
    """
    global _client
    if _client is not None:
        await _client.aclose()

    # Attach metrics hook — captures model/tokens/latency per call into
    # smart_bi_llm_usage (async, non-blocking). Enabled after pool ready
    # via enable_llm_metrics() in the lifespan; hook itself is a no-op
    # until then.
    from common.llm_metrics import metrics_response_hook
    _client = httpx.AsyncClient(
        limits=httpx.Limits(
            max_connections=20,
            max_keepalive_connections=10,
            keepalive_expiry=30,
        ),
        timeout=httpx.Timeout(120.0),
        event_hooks={"response": [metrics_response_hook]},
    )
    logger.info("Shared LLM HTTP client created (pool: 20 max, 10 keepalive, metrics hook)")

    # Warmup: send a minimal request to establish connection + TLS
    if api_key:
        try:
            resp = await _client.post(
                f"{base_url}/chat/completions",
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": "qwen3.5-flash",
                    "messages": [{"role": "user", "content": "hi"}],
                    "max_tokens": 1,
                    "enable_thinking": False,
                },
                timeout=httpx.Timeout(10.0),
            )
            logger.info(f"LLM client warmup request: status={resp.status_code}")
        except Exception as e:
            logger.warning(f"LLM client warmup failed (non-fatal): {e}")


async def close_llm_client() -> None:
    """Close the shared LLM HTTP client. Call during application shutdown."""
    global _client, _wrapper
    if _client is not None:
        await _client.aclose()
        _client = None
        _wrapper = None
        logger.info("Shared LLM HTTP client closed")
