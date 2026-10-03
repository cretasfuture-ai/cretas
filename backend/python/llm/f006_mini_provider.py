"""Bounded text-only F006 boss pilot. No cloud fallback or configurable egress URL."""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import time
import uuid
from typing import AsyncIterator
import sqlite3

import httpx

from common.llm_egress_audit import EgressAuditRecord, record_egress
from common.llm_metrics import llm_caller_context, record_stream_usage
from llm.f006_mini_lease import MiniBusy, acquire_host_lease

logger = logging.getLogger(__name__)
MODEL = "qwen3.8:27b"
PROVIDER = "mac-mini"
OLLAMA_URL = "http://127.0.0.1:11435/api/chat"
TOTAL_TIMEOUT_S = 55
_inflight = asyncio.Lock()
_SYSTEM = (
    "你是白垩纪食品工厂助手。仅回答食品安全、生产和质量管理的通用咨询。"
    "用中文给出简洁实用的建议，不超过300字。不能声称读取了工厂实时数据，"
    "不能代用户执行业务操作。具体库存、订单或金额应引导使用系统查询。"
)


def _usage(frame: dict) -> dict | None:
    if "prompt_eval_count" not in frame and "eval_count" not in frame:
        return None
    values = [frame.get("prompt_eval_count"), frame.get("eval_count")]
    if any(type(value) is not int or value < 0 for value in values):
        raise ValueError("invalid terminal usage")
    return {"inputTokens": values[0], "outputTokens": values[1],
            "totalTokens": values[0] + values[1]}


async def stream_question(question: str, enable_thinking: bool) -> AsyncIterator[dict]:
    correlation = uuid.uuid4().hex
    if _inflight.locked():
        yield {"type": "error", "code": "MINI_BUSY", "message": "本地 AI 正忙，请稍后重试"}
        return
    # No await between the locked check and the immediate uncontended acquisition.
    await _inflight.acquire()
    started = time.monotonic()
    status = 502
    attempted = False
    content = ""
    tokens = None
    usage_record_failed = False
    host_lease = None
    messages = [{"role": "system", "content": _SYSTEM},
                {"role": "user", "content": question}]
    payload = {"model": MODEL, "messages": messages, "stream": True,
               "think": enable_thinking,
               "options": {"temperature": 0.4, "num_predict": 768 if enable_thinking else 384}}
    try:
        try:
            host_lease = acquire_host_lease()
        except MiniBusy:
            status = 429
            yield {"type": "error", "code": "MINI_BUSY", "message": "本地 AI 正忙，请稍后重试"}
            return
        except (OSError, sqlite3.Error):
            yield {"type": "error", "code": "MINI_UNAVAILABLE", "message": "本地 AI 通道暂时不可用，请稍后重试"}
            return
        with llm_caller_context("f006_boss_question", "F006"):
            async with asyncio.timeout(TOTAL_TIMEOUT_S):
                async with httpx.AsyncClient(
                    trust_env=False, follow_redirects=False,
                    timeout=httpx.Timeout(28, connect=3, pool=1),
                    limits=httpx.Limits(max_connections=1, max_keepalive_connections=1),
                ) as client:
                    attempted = True
                    async with client.stream("POST", OLLAMA_URL, json=payload) as response:
                        response.raise_for_status()
                        yield {"type": "meta", "provider": PROVIDER, "model": MODEL,
                               "thinking": enable_thinking, "correlationId": correlation}
                        async for line in response.aiter_lines():
                            if not line.strip():
                                continue
                            if len(line) > 131072:
                                raise ValueError("oversized stream frame")
                            frame = json.loads(line)
                            if not isinstance(frame, dict) or frame.get("error"):
                                raise ValueError("invalid stream frame")
                            if frame.get("model") != MODEL:
                                raise ValueError("unexpected model")
                            message = frame.get("message", {})
                            if not isinstance(message, dict) or message.get("tool_calls") or message.get("images"):
                                raise ValueError("unexpected non-text stream")
                            delta = message.get("content", "")
                            if not isinstance(delta, str):
                                raise ValueError("invalid text delta")
                            content += delta
                            if len(content) > 12000:
                                raise ValueError("oversized answer")
                            if delta:
                                yield {"type": "delta", "content": delta}
                            if frame.get("done") is True:
                                if frame.get("done_reason") != "stop":
                                    status = 502
                                    yield {"type": "error", "code": "MINI_INCOMPLETE",
                                           "message": "本地 AI 回答未完整完成，请重试"}
                                    return
                                if not content.strip():
                                    raise ValueError("empty answer")
                                tokens = _usage(frame)
                                if tokens is not None:
                                    try:
                                        record_stream_usage(PROVIDER, MODEL, tokens["inputTokens"],
                                                            tokens["outputTokens"], tokens["totalTokens"],
                                                            latency_ms=int((time.monotonic() - started) * 1000))
                                    except Exception:
                                        usage_record_failed = True
                                status = 200
                                yield {"type": "done", "content": content, "provider": PROVIDER,
                                       "model": MODEL, "usageAvailable": tokens is not None,
                                       "usage": tokens, "finishReason": frame["done_reason"],
                                       "correlationId": correlation}
                                return
                        raise ValueError("missing terminal frame")
    except (TimeoutError, httpx.TimeoutException):
        status = 504
        yield {"type": "error", "code": "MINI_TIMEOUT", "message": "本地 AI 响应超时，请重试"}
    except (httpx.HTTPError, ValueError, TypeError):
        yield {"type": "error", "code": "MINI_UNAVAILABLE", "message": "本地 AI 未能完成回答，请稍后重试"}
    finally:
        try:
            elapsed = int((time.monotonic() - started) * 1000)
            audit_failed = False
            if attempted:
                prompt = _SYSTEM + question
                try:
                    record_egress(EgressAuditRecord(
                        call_site="f006_boss_question", provider=PROVIDER, model=MODEL,
                        factory_id="F006", slot="chat", prompt_chars=len(prompt),
                        prompt_sha256=hashlib.sha256(prompt.encode("utf-8")).hexdigest(),
                        status_code=status,
                    ))
                except Exception:
                    audit_failed = True
            try:
                logger.info("f006_boss_question correlation=%s provider=%s model=%s factory=F006 status=%s latency_ms=%s usage_available=%s audit_failed=%s usage_record_failed=%s",
                            correlation, PROVIDER, MODEL, status, elapsed, tokens is not None, audit_failed, usage_record_failed)
            except Exception:
                # Observability is best-effort and must not replace the stream's
                # terminal result or prevent cancellation cleanup.
                pass
        finally:
            try:
                if host_lease is not None:
                    host_lease.close()
            finally:
                _inflight.release()
