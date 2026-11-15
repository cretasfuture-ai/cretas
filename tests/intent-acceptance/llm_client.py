"""出题 / 盲标用的最小 LLM 客户端: 千问 (DashScope 兼容) 与 OpenAI, 都走 chat/completions, 只用标准库。

每次调用先按上限估算花费再发 (cap 超了就停), 回执含 token 数; 只在内存持凭证。
"""
import json
import threading
import time
import urllib.error
import urllib.request

import creds

# USD / 1M tokens (列表价粗估, 仅用于上限保护; 千问按 1 USD ~ 7.2 CNY 折)
PRICE = {
    "qwen-max": (0.35, 1.4),
    "qwen-plus": (0.11, 0.28),
    "gpt-5.4-mini": (0.25, 2.0),
    "gpt-5.4": (2.5, 15.0),
    "qwen3.5-plus": (0.5, 3.0),  # 保守上限价 (实际约 0.11/0.67 USD)
}


class LLM:
    def __init__(self, provider, model, cap_usd):
        self.provider, self.model, self.cap = provider, model, cap_usd
        self.spent, self.lock = 0.0, threading.Lock()
        self.calls = self.in_tok = self.out_tok = 0
        self.proj = None
        if provider == "aliyun":
            self.key, base = creds.aliyun("A")
            self.url = base.rstrip("/") + "/chat/completions"
        elif provider == "openai":
            self.key, self.proj = creds.openai_eval()
            self.url = "https://api.openai.com/v1/chat/completions"
        else:
            raise ValueError(provider)

    def chat(self, system, user, max_tokens=8000, temperature=0.8, json_mode=True, extra=None):
        pin, pout = PRICE[self.model]
        bound = (len(system) + len(user)) / 1.5 * pin / 1e6 + max_tokens * pout / 1e6
        with self.lock:
            if self.spent + bound > self.cap:
                raise SystemExit("cap %.2f USD reached for %s" % (self.cap, self.model))
        body = {"model": self.model,
                "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}
        if self.provider == "openai":
            body["max_completion_tokens"] = max_tokens
        else:
            body["max_tokens"] = max_tokens
            body["temperature"] = temperature
        if extra:
            body.update(extra)  # 如千问 {"enable_thinking": False}
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        h = {"Authorization": "Bearer " + self.key, "Content-Type": "application/json"}
        if self.proj:
            h["OpenAI-Project"] = self.proj
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        last = None
        for attempt in range(4):
            try:
                req = urllib.request.Request(self.url, data=data, headers=h)
                with urllib.request.urlopen(req, timeout=300) as r:
                    resp = json.loads(r.read().decode("utf-8"))
                u = resp.get("usage") or {}
                it, ot = u.get("prompt_tokens", 0), u.get("completion_tokens", 0)
                with self.lock:
                    self.spent += (it * pin + ot * pout) / 1e6
                    self.calls += 1
                    self.in_tok += it
                    self.out_tok += ot
                ch = resp["choices"][0]
                return ch["message"]["content"], ch.get("finish_reason")
            except urllib.error.HTTPError as e:
                last = "HTTP %s %s" % (e.code, e.read()[:200])
                if e.code in (400, 401, 403, 404):
                    break
            except Exception as e:  # noqa: BLE001 传输层错误重试
                last = repr(e)[:200]
            time.sleep(3 * (attempt + 1))
        raise RuntimeError("LLM call failed: %s" % last)
