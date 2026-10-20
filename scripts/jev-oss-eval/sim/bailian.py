"""百炼 OpenAI 兼容端点的最小客户端 (生成用)。key 从 backend/python/.env 的 LLM_API_KEY 读, 不打印。"""
from __future__ import annotations

import json
import re
import time
from pathlib import Path

import httpx

ENV = Path("C:/Users/Steve/my-prototype-logistics/backend/python/.env")
BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
SIM = Path(__file__).resolve().parent
OUT = SIM.parent / "results" / "sim"   # 含 F006 主数据, 在 .gitignore 的 results/ 下
OUT.mkdir(parents=True, exist_ok=True)


def _key() -> str:
    for line in ENV.read_text(encoding="utf-8").splitlines():
        if line.startswith("LLM_API_KEY="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    raise SystemExit("LLM_API_KEY not found")


_client: httpx.Client | None = None


def client() -> httpx.Client:
    global _client
    if _client is None:
        _client = httpx.Client(base_url=BASE, headers={"Authorization": f"Bearer {_key()}"}, timeout=180)
    return _client


def chat_json(model: str, system: str, user: str, *, temperature: float = 0.9, retries: int = 3):
    """要求模型只输出 JSON; 解析失败重试。返回 (obj, usage)。"""
    last = None
    for attempt in range(retries):
        r = client().post("/chat/completions", json={
            "model": model, "temperature": temperature, "enable_thinking": False,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        })
        if r.status_code != 200:
            last = f"HTTP {r.status_code}: {r.text[:200]}"
            time.sleep(2 * (attempt + 1))
            continue
        j = r.json()
        txt = j["choices"][0]["message"]["content"].strip()
        txt = re.sub(r"^```(?:json)?\s*|\s*```$", "", txt)
        try:
            return json.loads(txt), j.get("usage", {})
        except json.JSONDecodeError as e:
            last = f"JSON: {e}; head={txt[:120]!r}"
    raise RuntimeError(f"chat_json failed after {retries}: {last}")
