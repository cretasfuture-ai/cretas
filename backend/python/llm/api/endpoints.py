"""
/api/llm/* — FastAPI endpoints that wrap common.llm_router.

All endpoints delegate to call_chain (or call_chain_stream for SSE) so
Java callers get the full 4-provider fallback + circuit-breaker behaviour
without needing their own DashScope client.

Slots:
    chat        — general chat (default)
    insights    — data insights
    chart       — chart recommendation
    mapper      — field mapping
    reasoning   — deep reasoning
    vl          — vision/image (used by /vision endpoint)
    review      — code / content review

Routes:
    POST /chat           — synchronous chat passthrough
    POST /chat-stream    — SSE streaming chat
    POST /tool-call      — function-calling passthrough
    POST /intent-classify — intent classification (temperature forced 0.1)
    POST /vision         — multipart image + prompt → SLOT.VL
"""
from __future__ import annotations

import base64
import json
import logging
from typing import Any, AsyncIterator, Dict, List, Optional, Union

from fastapi import APIRouter, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel

from common.llm_client import EgressRedactionError
from common.llm_router import SLOT, LLMChainExhausted, call_chain, call_chain_stream
from common.llm_metrics import llm_caller_context
from common.responses import ErrorCode, error_response

logger = logging.getLogger(__name__)

router = APIRouter(tags=["LLM Router"])

# This exact child route is internal-only even though legacy /api/llm siblings
# retain their existing auth semantics. It never enters the shared cloud chain.
from llm.api.f006_boss_question import router as f006_boss_question_router
router.include_router(f006_boss_question_router)

# ── Request model ─────────────────────────────────────────────────────────────


class LLMRequest(BaseModel):
    messages: List[Dict[str, Any]]
    slot: str = "chat"
    temperature: Optional[float] = None
    max_tokens: Optional[int] = None
    enable_thinking: Optional[bool] = None
    tools: Optional[List[Dict[str, Any]]] = None
    tool_choice: Optional[Union[str, Dict[str, Any]]] = None
    extra_body: Optional[Dict[str, Any]] = None
    # Upstream (Java/RN) may pass who is calling → attributed in smart_bi_llm_usage.
    # Falls back to the endpoint name so this generic proxy is never "unknown"
    # (was an 8.7M-token/30d attribution black hole — 2026-07-01 rules-gap heatmap).
    caller: Optional[str] = None


# ── Helpers ───────────────────────────────────────────────────────────────────

_VALID_SLOTS = {s.value: s for s in SLOT}


def _parse_slot(slot_str: str) -> SLOT:
    """Convert slot name string to SLOT enum; raise 400 on unknown value."""
    s = _VALID_SLOTS.get(slot_str)
    if s is None:
        valid = sorted(_VALID_SLOTS.keys())
        raise HTTPException(
            status_code=400,
            detail=f"Invalid slot '{slot_str}'. Must be one of: {valid}",
        )
    return s


# 供应商实际支持的 tool_choice 取值。
# 🔴 2026-08-28 生产实测: aistore/DeepSeek-V4-Flash-A 只认 none/auto ——
#   tool_choice="auto"                -> HTTP 200, 正常返回 tool_calls
#   tool_choice="get_inventory_stats" -> 上游 400
#     InvalidParameter: tool_choice is one of the strings that should be ["none","auto"]
#   而 call_chain 兜底链走完之后对外抛出去, FastAPI 兜成
#   `500 {"code":"INTERNAL_ERROR","message":"Internal server error"}` ——
#   调用方完全看不出「是我传错了参数」还是「服务挂了」。
#
# ⛔ 不做降级: 不把具名工具悄悄改写成 "auto"。那会让调用方以为强制生效了,
#    而模型其实可以自由选择别的工具或干脆不调 —— 正是本仓「禁止降级处理」那条。
# ⇒ 在入口显式拒绝, 把「不支持」说清楚。供应商日后支持了, 放开这里即可。
_SUPPORTED_TOOL_CHOICES = {"none", "auto"}


def _validate_tool_choice(tool_choice: Optional[Union[str, Dict[str, Any]]]) -> None:
    """具名 tool_choice 当前供应商不支持 —— 明确 400, 不要让它烂成 500。"""
    if tool_choice is None:
        return
    if isinstance(tool_choice, str) and tool_choice in _SUPPORTED_TOOL_CHOICES:
        return
    if isinstance(tool_choice, str):
        got = repr(tool_choice)
    else:
        got = f"object {json.dumps(tool_choice, ensure_ascii=False)[:120]}"
    raise HTTPException(
        status_code=400,
        detail=(
            f"tool_choice {got} is not supported by the current providers. "
            f"Only {sorted(_SUPPORTED_TOOL_CHOICES)} are accepted. "
            "Forcing a specific tool by name is an OpenAI-style capability that the "
            "configured upstream models reject with 400; use \"auto\" and check the "
            "returned tool_calls instead."
        ),
    )


def _build_payload(req: LLMRequest) -> Dict[str, Any]:
    """Build the payload dict, omitting fields that are None."""
    payload: Dict[str, Any] = {"messages": req.messages}
    if req.temperature is not None:
        payload["temperature"] = req.temperature
    if req.max_tokens is not None:
        payload["max_tokens"] = req.max_tokens
    if req.enable_thinking is not None:
        payload["enable_thinking"] = req.enable_thinking
    if req.tools is not None:
        payload["tools"] = req.tools
    if req.tool_choice is not None:
        payload["tool_choice"] = req.tool_choice
    if req.extra_body is not None:
        payload["extra_body"] = req.extra_body
    return payload


MAX_IMAGE_BYTES = 10 * 1024 * 1024  # 10 MB

# 手持拍照核验的延迟契约(2026-09-15 真机彩排定下来的):
#   · Java 侧 aiServiceHttpClient readTimeout = 30s ⇒ 整条级联必须在 30s 内出结果,
#     否则 Python 这边就算第二个账号答对了, Java 也已经把 502 发给手机了。
#   · qwen3.8-flash 关思考后中位 1.5–4s, 但 DashScope 有随机长尾(同图同 prompt 实测
#     23.5s / 28.2s, 6 次里 2 次), 长尾与账号无关 ⇒ 等它不如换个账号再打一次。
#   ⇒ 单次尝试 12s, 整条级联 27s(留 3s 给 Java 的传输/序列化): 最多两次完整尝试,
#     第三次拿剩余预算(由 _budgeted_attempt_timeout 截断)。
# ⛔ 不要把单次改回 30s: 那等于「第一次撞长尾就必 502」, 而且后面两个账号的重试
#    全部发生在 Java 已经放弃之后, 白烧额度。
VL_ATTEMPT_TIMEOUT_S = 12.0
VL_TOTAL_TIMEOUT_S = 27.0

# ── Endpoints ─────────────────────────────────────────────────────────────────


# Java 侧 (LlmEgressGuard) 已按同一套规则脱敏后才转发到这里: 公司名 / 人名要不要换是 Java 按出口定的 (按原话识别意图的
# 出口故意不换)。这里只再抹一遍号码 (原话模式), 不再认名字、不再查保密产品名 —— 否则会推翻 Java 的选择。
from common.llm_redactor import utterance_egress  # noqa: E402


def _redaction_blocked(exc: EgressRedactionError) -> JSONResponse:
    """脱敏失败 = 请求被拦、什么都没出境。答 503 而不是裸 500, 让 Java 侧知道「没有模型作答」而不是「服务坏了」。"""
    logger.error("[llm_api] egress redaction failed, request blocked: %s", exc)
    return JSONResponse(
        status_code=503,
        content=error_response(
            "AI 请求在发送前脱敏失败，已拦截，未发送任何内容",
            ErrorCode.SERVICE_UNAVAILABLE,
            data={"reason": "EGRESS_REDACTION_FAILED"},
        ),
    )


@router.post("/chat", response_model=None)
async def chat(req: LLMRequest) -> Union[Dict[str, Any], JSONResponse]:
    """Synchronous chat passthrough — returns complete call_chain response."""
    slot = _parse_slot(req.slot)
    _validate_tool_choice(req.tool_choice)
    payload = _build_payload(req)
    with llm_caller_context(req.caller or "llm_api.chat"), utterance_egress():
        try:
            return await call_chain(slot, payload)
        except EgressRedactionError as exc:
            return _redaction_blocked(exc)


@router.post("/chat-stream")
async def chat_stream(req: LLMRequest) -> StreamingResponse:
    """SSE streaming chat. Each event: 'data: <json>\\n\\n'. Ends with 'data: [DONE]\\n\\n'."""
    slot = _parse_slot(req.slot)
    payload = _build_payload(req)
    _caller = req.caller or "llm_api.chat_stream"

    async def _generate() -> AsyncIterator[str]:
        try:
            with llm_caller_context(_caller), utterance_egress():
                async for event in call_chain_stream(slot, payload):
                    yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
        except RuntimeError as e:
            yield f"data: {json.dumps({'type': 'error', 'message': str(e)}, ensure_ascii=False)}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(
        _generate(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",  # disable nginx buffering for SSE
        },
    )


@router.post("/tool-call", response_model=None)
async def tool_call(req: LLMRequest) -> Union[Dict[str, Any], JSONResponse]:
    """Function-calling passthrough — transparently forwards tools / tool_choice.

    Every provider failing/being rejected answers **503** with the project's
    unified error body (``code=SERVICE_UNAVAILABLE``, ``data.reason=
    LLM_PROVIDERS_EXHAUSTED``), not the global handler's bare
    ``500 INTERNAL_ERROR`` — the Java caller (PythonLLMClient) can then tell
    "no LLM answered" from "the service broke" and ask for what it needs
    instead of re-asking the user. 2026-10-06 prod: AI parameter extraction hit
    the bare 500 and the user was asked for a task number already given.
    """
    slot = _parse_slot(req.slot)
    _validate_tool_choice(req.tool_choice)
    payload = _build_payload(req)
    with llm_caller_context(req.caller or "llm_api.tool_call"), utterance_egress():
        try:
            return await call_chain(slot, payload)
        except EgressRedactionError as exc:
            return _redaction_blocked(exc)
        except LLMChainExhausted as exc:
            logger.warning("[llm_api.tool_call] %s", exc)
            return JSONResponse(
                status_code=503,
                content=error_response(
                    str(exc)[:500],
                    ErrorCode.SERVICE_UNAVAILABLE,
                    data={"reason": "LLM_PROVIDERS_EXHAUSTED", "slot": exc.slot},
                ),
            )


@router.post("/intent-classify", response_model=None)
async def intent_classify(req: LLMRequest) -> Union[Dict[str, Any], JSONResponse]:
    """Intent classification — always uses temperature=0.1 for determinism."""
    slot = _parse_slot(req.slot)
    payload = _build_payload(req)
    # Override temperature regardless of what caller sent
    payload["temperature"] = 0.1
    with llm_caller_context(req.caller or "llm_api.intent_classify"), utterance_egress():
        try:
            return await call_chain(slot, payload)
        except EgressRedactionError as exc:
            return _redaction_blocked(exc)


@router.post("/vision")
async def vision(
    image: UploadFile,
    prompt: str = Form(...),
    slot: str = Form(default="vl"),
) -> Dict[str, Any]:
    """Multipart vision endpoint — encodes image to base64, calls SLOT.VL."""
    parsed_slot = _parse_slot(slot)

    # Read image bytes and encode to base64 data-URI
    image_bytes = await image.read(MAX_IMAGE_BYTES + 1)
    if len(image_bytes) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="Image exceeds 10 MB limit")
    mime_type = image.content_type or "image/jpeg"
    b64_data = base64.b64encode(image_bytes).decode("ascii")
    data_uri = f"data:{mime_type};base64,{b64_data}"

    # Build OpenAI-style multimodal message
    messages: List[Dict[str, Any]] = [
        {
            "role": "user",
            "content": [
                {
                    "type": "image_url",
                    "image_url": {"url": data_uri},
                },
                {
                    "type": "text",
                    "text": prompt,
                },
            ],
        }
    ]

    payload: Dict[str, Any] = {"messages": messages}
    if parsed_slot == SLOT.WAREHOUSE_VL:
        # 平面图只需要短 JSON，限制单次输出与异常计费；不影响其它拍照业务。
        payload["max_tokens"] = 2048
    with llm_caller_context("llm_api.vision"):
        return await call_chain(parsed_slot, payload,
                                timeout=VL_ATTEMPT_TIMEOUT_S,
                                total_timeout=VL_TOTAL_TIMEOUT_S)
