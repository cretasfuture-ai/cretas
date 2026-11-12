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


def egress_refusal_message(err: BaseException) -> str:
    """给用户看的拒绝说明 (不含原文、不含工厂): 外发被拦时调用方必须把它报出来, ⛔ 不许当成供应商故障重试,
    也不许返回空串 / 统计兜底冒充 AI 结果 (Steve 2026-10-29 复审 H3)。

    平台账号 (token 里是 PLATFORM) 没有工厂: 有意拒绝 —— 平台读工厂数据走平台读取留痕的规则, 不经这里直接
    把工厂数据发给模型 (复审 M2)。"""
    if "NoEgressFactoryError" in str(err) or isinstance(err, NoEgressFactoryError):
        if _egress_platform_caller.get():
            return "AI 分析没有发出：平台账号不能直接对工厂数据做 AI 分析"
        return "AI 分析没有发出：无法确定这份数据属于哪家工厂，请登录后重试"
    return "AI 分析没有发出：发给模型前的脱敏没有完成，请稍后重试"


import contextvars as _contextvars  # noqa: E402

# 本次请求是平台账号 (token 的 factoryId 为 PLATFORM): 只用来给出准确的拒绝说明, 不放行任何外发 (复审 M2)
_egress_platform_caller: _contextvars.ContextVar[bool] = _contextvars.ContextVar(
    "llm_egress_platform_caller", default=False)


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
            from common.llm_redactor import RedactionMeta, redact_payload_for_egress
            from common.llm_metrics import set_egress_redaction_meta
            # 工厂级保密配置 (按外发工厂判; 工厂不明 / 读不到配置 = 开启, 照常脱敏 / 照常带约束)
            factory_id = _egress_factory()
            if factory_id and not await _process_secret_clause_needed(factory_id):
                from common.process_secret_harness import without_process_secret_clause
                json_payload = without_process_secret_clause(json_payload)
            if factory_id and not await _egress_redaction_on(factory_id):
                # 出外脱敏 (c) 关着、且工厂已知: 原样发出 (不换代号, 也就不用还原)
                logger.info("[egress] passthrough: redaction off for factory %s", factory_id)
                set_egress_redaction_meta(RedactionMeta())
                _egress_tokens.set({})
                return json_payload, {}
            redacted, meta = redact_payload_for_egress(json_payload, await _names_for_this_request())
            set_egress_redaction_meta(meta)
            _egress_tokens.set(meta.egress_tokens)
            return redacted, meta.egress_tokens
        except Exception as e:  # fail-closed: never send the unredacted payload
            logger.critical(
                "[P0-redact] egress redaction FAILED, request BLOCKED (nothing sent): %s",
                type(e).__name__,
            )
            raise EgressRedactionError(type(e).__name__) from e

    async def redact_chat_payload(self, json_payload: Any) -> Any:
        """Redact an OpenAI chat-shaped payload exactly as a ``/chat/completions`` post would.

        For providers whose wire format is NOT ``/chat/completions`` (Anthropic ``/v1/messages``,
        OpenAI ``/v1/decisions``, 2026-11-12): the router redacts the chat-shaped payload here
        FIRST and only then translates it, so the same choke point covers every egress. Posting the
        translated body afterwards goes through ``post`` with a non-chat URL, which is a passthrough.
        Returns ``(payload, tokens)``; sets the same egress-token context as ``post``. Fail-closed.
        """
        return await self._redact("/chat/completions", json_payload)

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


class NoEgressFactoryError(RuntimeError):
    """不知道按哪家工厂脱敏 (没有工厂上下文, 也不是服务端内部调用): 不发 (2026-10-29, 与 Java 一致)。"""


# 服务端内部调用 (Java 带 X-Internal-Secret、没带 X-Factory-Id) / 平台建厂这类「不是某一家工厂的数据」的外发:
# 没有工厂时按**所有工厂**的保密产品名脱敏 (多换代号无害, 原样发出不行); 由 auth_middleware 内部分支 /
# all_factories_egress() 设置。普通请求不设 —— 没有工厂就拒绝。
_egress_all_factories: contextvars.ContextVar[bool] = contextvars.ContextVar(
    "llm_egress_all_factories", default=False)


class all_factories_egress:
    """这一段里没有工厂上下文的外发按所有工厂的保密产品名脱敏 (而不是拒绝)。"""

    def __enter__(self) -> None:
        self._token = _egress_all_factories.set(True)

    def __exit__(self, *_exc) -> None:
        _egress_all_factories.reset(self._token)


def _egress_factory() -> Optional[str]:
    """当前请求的工厂; 空白 / PLATFORM 不算工厂。"""
    try:
        from common.llm_metrics import _llm_factory
        factory_id = _llm_factory.get()
    except Exception:
        factory_id = None
    if not factory_id or not str(factory_id).strip() or str(factory_id).strip().upper() == "PLATFORM":
        return None
    return factory_id


async def _protected_names_for_egress() -> list:
    """当前请求工厂的工艺保密产品名, 每次现查 (不走 60 秒缓存: 刚设为保密的产品名不能再原样发出去)。

    Steve 2026-10-29 (fail-closed, 与 Java ``LlmEgressGuard`` 一致): 没有工厂上下文时不再返回空名单 (那等于
    原名发出) —— 服务端内部调用 / 平台建厂 (``_egress_all_factories``) 按所有工厂的保密产品名脱敏, 其余抛
    :class:`NoEgressFactoryError` → 调用方按 EgressRedactionError 不发。查库出错向上抛 → 不发。"""
    factory_id = _egress_factory()
    if not factory_id and not _egress_all_factories.get():
        raise NoEgressFactoryError("no factory context for egress")
    from smartbi.config import get_cretas_pool
    from common.process_secret_sql import (ALL_FACTORIES_PROTECTED_NAMES_SQL, PROTECTED_NAMES_SQL,
                                           usable_names)
    import asyncio

    async def _read() -> list:
        pool = await get_cretas_pool()
        if pool is None:
            raise RuntimeError("cretas database pool unavailable")
        async with pool.acquire() as conn:
            if factory_id:
                rows = await conn.fetch(PROTECTED_NAMES_SQL, factory_id)
            else:
                rows = await conn.fetch(ALL_FACTORIES_PROTECTED_NAMES_SQL)
        return sorted(usable_names(r["name"] for r in rows))

    # 库卡住时不让外发请求一起卡住: 5 秒没查到按查不了处理 (不发)
    return await asyncio.wait_for(_read(), timeout=_PROTECTED_NAMES_TIMEOUT_S)


_PROTECTED_NAMES_TIMEOUT_S = 5.0


async def _egress_redaction_on(factory_id: str) -> bool:
    """该厂「出外脱敏」(c) 是否开着 (缓存不超过 5 秒; 读不到 = 开)。只在工厂已知时问 —— 工厂不明照旧拒发。"""
    from common import factory_secrecy
    return await factory_secrecy.egress_redaction_on(factory_id)


async def _process_secret_clause_needed(factory_id: str) -> bool:
    """系统约束里「工艺保密产品」那一句要不要带: 该厂 a 或 e 开着就带 (读不到 = 带)。跨厂那一句永远带。"""
    from common import factory_secrecy
    return not await factory_secrecy.process_secret_inert(factory_id)


async def _names_for_this_request() -> list:
    """原话模式 (意图识别) 与 Java ``LlmEgressGuard.utterance`` 一样不查库、不认保密产品名 (识别链路不加库查询);
    其余每次现查; 同一次 call_chain 已预取过就复用 (重试下一家供应商不再查库)。

    已知例外 (有意保留, Steve 2026-10-29 裁定): 意图识别把用户原话连同其中的工艺保密产品名不脱敏发给
    外部模型 (下面原话模式返回空名单); 除意图识别以外的所有外发一律把保密产品名换代号。
    Java 侧同一例外在 LlmEgressGuard.utterance; 说明见 docs/security/process-secret-ai-egress.md。"""
    from common.llm_redactor import _egress_detect_names
    if not _egress_detect_names.get():
        # 已知例外 (Steve 2026-10-29): 意图识别原话模式保留保密产品原名, 故意返回空名单
        return []
    prefetched = _prefetched_names.get()
    if prefetched is not None:
        return prefetched
    return await _protected_names_for_egress()


_prefetched_names: contextvars.ContextVar = contextvars.ContextVar("llm_egress_prefetched_names", default=None)


async def prefetch_names_for_request() -> "contextvars.Token":
    """call_chain 开头调用: 在供应商重试 / 每次尝试的超时之外先查好保密产品名 (库慢不会被当成供应商超时)。"""
    try:
        names = await _names_for_this_request()
    except Exception as e:
        logger.critical("[P0-redact] protected-name lookup FAILED, request BLOCKED (nothing sent): %s",
                        type(e).__name__)
        raise EgressRedactionError(type(e).__name__) from e
    return _prefetched_names.set(names)


def reset_prefetched_names(token: "contextvars.Token") -> None:
    _prefetched_names.reset(token)


def restore_json(node: Any, tokens: dict) -> Any:
    """把本次发出去的代号在一个已解析的 JSON 结构里换回真名 (与 ``_restored_response`` 同一套规则)。

    2026-11-12: Anthropic 适配层把 ``/v1/messages`` 的回答翻译成 OpenAI 形状后用它还原 ——
    那条路不经过 ``post`` 的 ``/chat/completions`` 分支, 必须显式还原。"""
    if not tokens:
        return node
    import json as _json
    from common.llm_egress_text import restore

    def _json_text(real: str) -> str:
        return _json.dumps(real, ensure_ascii=False)[1:-1]

    def _deep(node: Any) -> Any:
        if isinstance(node, dict):
            # 工具调用的 arguments 是 JSON 字符串: 换回的真名要按 JSON 转义 (真名带引号也不弄坏它)
            return {(restore(k, tokens) if isinstance(k, str) else k):
                    (restore(v, tokens, encode=_json_text) if k == "arguments" and isinstance(v, str) else _deep(v))
                    for k, v in node.items()}
        if isinstance(node, list):
            return [_deep(x) for x in node]
        return restore(node, tokens) if isinstance(node, str) else node

    return _deep(node)


def _restored_response(response: httpx.Response, tokens: dict) -> httpx.Response:
    """非流式回答: 把本次发出去的代号换回真名 (去括号 / 全角括号等改写也认)。JSON 先解析再逐个字符串换,
    真名带引号也不会弄坏 JSON; 解析不了按纯文本换。"""
    if not tokens or not isinstance(response, httpx.Response):
        return response
    import json as _json
    from common.llm_egress_text import restore

    try:
        body = _json.dumps(restore_json(response.json(), tokens), ensure_ascii=False).encode("utf-8")
    except ValueError:
        body = (restore(response.text, tokens) or "").encode("utf-8")
    headers = {k: v for k, v in response.headers.items()
               if k.lower() not in ("content-length", "content-encoding", "transfer-encoding", "content-type")}
    content_type = response.headers.get("content-type", "application/json").split(";")[0].strip()
    headers["content-type"] = f"{content_type}; charset=utf-8"  # 重新按 UTF-8 编码了
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
    # 2026-11-12: 阿里云已在路由里整体停用(Steve 裁定) —— 启动预热不再向 DashScope 发 qwen 请求;
    # 预热对新的两家没有意义(经代理、按需建连), 所以这里只在 base_url 不是 DashScope 时才发。
    if api_key and "dashscope" in (base_url or "").lower():
        logger.info("LLM client warmup skipped: DashScope is disabled in the router (2026-11-12)")
    elif api_key:
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


# ── 出境代理 (2026-11-12, LLM-PROVIDER-SWITCH) ──────────────────────────────────────────
# prod 47 在国内: api.anthropic.com 403(地区封锁)、api.openai.com 超时。这两家经加拿大 Mac mini 的反向
# SOCKS 出口走(47 上只监听 127.0.0.1, 见 docs/ops/local-llm-canada-mini.md)。
#   LLM_EGRESS_PROXY  例: socks5h://127.0.0.1:11438   (socks5h = 远端解析域名; 需要 socksio)
# 只给付费两家用(由 llm_router 按账号选客户端); 没配 = 不建代理客户端, 行为与以前完全相同。
# 代理客户端同样包在脱敏层里、挂同一个 metrics hook —— 换的只是出口, ⛔ 不是绕开脱敏。
_proxied_client: Optional[httpx.AsyncClient] = None
_proxied_wrapper: "Optional[_RedactingLLMClient]" = None
_proxied_url: str = ""


def egress_proxy_url() -> str:
    import os
    return (os.getenv("LLM_EGRESS_PROXY") or "").strip()


def get_llm_proxied_http_client() -> "_RedactingLLMClient":
    """经 ``LLM_EGRESS_PROXY`` 出境的共享客户端(同样脱敏)。没配代理时返回普通共享客户端。"""
    global _proxied_client, _proxied_wrapper, _proxied_url
    url = egress_proxy_url()
    if not url:
        return get_llm_http_client()
    if _proxied_client is None or _proxied_url != url:
        from common.llm_metrics import metrics_response_hook
        _proxied_client = httpx.AsyncClient(
            proxy=url,
            limits=httpx.Limits(max_connections=20, max_keepalive_connections=10, keepalive_expiry=30),
            timeout=httpx.Timeout(120.0),
            event_hooks={"response": [metrics_response_hook]},
        )
        _proxied_url = url
        _proxied_wrapper = None
        scheme = url.split("://", 1)[0]
        logger.info("Egress-proxied LLM HTTP client created (scheme=%s)", scheme)
    if _proxied_wrapper is None or _proxied_wrapper._inner is not _proxied_client:
        _proxied_wrapper = _RedactingLLMClient(_proxied_client)
    return _proxied_wrapper


async def close_llm_client() -> None:
    """Close the shared LLM HTTP client. Call during application shutdown."""
    global _client, _wrapper, _proxied_client, _proxied_wrapper, _proxied_url
    if _client is not None:
        await _client.aclose()
        _client = None
        _wrapper = None
        logger.info("Shared LLM HTTP client closed")
    if _proxied_client is not None:
        await _proxied_client.aclose()
        _proxied_client = None
        _proxied_wrapper = None
        _proxied_url = ""
