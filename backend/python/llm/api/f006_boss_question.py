"""Internal service route for the isolated F006 boss text pilot."""
from __future__ import annotations

import json
import os

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator

from llm.f006_mini_provider import stream_question

router = APIRouter()


class BossQuestion(BaseModel):
    model_config = ConfigDict(extra="forbid")
    question: str = Field(min_length=1, max_length=2000)
    enable_thinking: bool = False

    @field_validator("question")
    @classmethod
    def nonblank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("question must not be blank")
        return value.strip()


@router.post("/f006-boss-question-stream")
async def boss_question(body: BossQuestion, request: Request):
    state = request.state
    user_id = getattr(state, "user_id", None)
    if getattr(state, "auth_method", None) != "internal":
        raise HTTPException(status_code=401, detail="Internal authentication required")
    if (getattr(state, "factory_id", None) != "F006"
            or getattr(state, "role", None) != "factory_super_admin"
            or not isinstance(user_id, str) or not user_id.isascii()
            or not user_id.isdigit() or not 0 < int(user_id) <= 9223372036854775807):
        raise HTTPException(status_code=403, detail="This route is unavailable for this identity")
    if os.environ.get("F006_BOSS_MINI_ENABLED") != "1":
        raise HTTPException(status_code=503, detail="F006 local AI pilot is disabled")

    async def events():
        async for event in stream_question(body.question, body.enable_thinking):
            yield "data: " + json.dumps(event, ensure_ascii=False) + "\n\n"

    return StreamingResponse(events(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
