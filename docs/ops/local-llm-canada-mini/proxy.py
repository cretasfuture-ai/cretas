#!/usr/bin/env python3
"""Cretas local-LLM auth proxy (canada-mini). Loopback only, bearer-token, one model.

  client (47, through the reverse tunnel) -> 127.0.0.1:11500 -> ollama 127.0.0.1:11434

Only POST /v1/chat/completions and GET /v1/models are forwarded; only MODEL is accepted.
Every chat request gets reasoning_effort=none (qwen3 otherwise spends the budget on hidden
thinking and returns empty content) and has cloud-only fields dropped. A background thread
keeps the model resident (keep_alive=-1) and re-warms it if ollama restarted.
Token: ~/cretas-llm/token (0600). Nothing here ever prints the token.
"""
import hmac
import http.client
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LISTEN = ("127.0.0.1", 11500)
OLLAMA = ("127.0.0.1", 11434)
MODEL = os.environ.get("CRETAS_LLM_MODEL", "qwen3:8b")
TOKEN = open(os.path.expanduser("~/cretas-llm/token"), encoding="utf-8").read().strip()
MAX_BODY = 512 * 1024
DROP = {"enable_thinking", "thinking_budget", "logprobs", "top_logprobs", "stream_options", "response_format_ext"}


def _warm():
    # Re-assert keep_alive=-1 every cycle: an unset request falls back to ollama's 5-minute
    # default and would unload the model; an empty generate is a no-op once it is resident.
    while True:
        try:
            c = http.client.HTTPConnection(*OLLAMA, timeout=180)
            body = json.dumps({"model": MODEL, "prompt": "", "keep_alive": -1})
            c.request("POST", "/api/generate", body, {"Content-Type": "application/json"})
            c.getresponse().read()
            c.close()
        except Exception:
            pass
        time.sleep(60)


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):  # one line, no headers, no body
        print("%s %s" % (self.command, self.path), flush=True)

    def _send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _authed(self):
        got = self.headers.get("Authorization", "")
        return hmac.compare_digest(got.encode(), ("Bearer " + TOKEN).encode())

    def do_GET(self):
        if not self._authed():
            return self._send(401, {"error": "unauthorized"})
        if self.path == "/v1/models":
            return self._send(200, {"object": "list", "data": [{"id": MODEL, "object": "model"}]})
        if self.path == "/health":
            return self._send(200, {"ok": True, "model": MODEL})
        self._send(404, {"error": "not_found"})

    def do_POST(self):
        if not self._authed():
            return self._send(401, {"error": "unauthorized"})
        if self.path != "/v1/chat/completions":
            return self._send(404, {"error": "not_found"})
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0 or n > MAX_BODY:
            return self._send(413, {"error": "bad_length"})
        try:
            req = json.loads(self.rfile.read(n))
        except Exception:
            return self._send(400, {"error": "bad_json"})
        if req.get("model") != MODEL:
            return self._send(400, {"error": "model_not_served", "served": MODEL})
        for k in DROP:
            req.pop(k, None)
        req.setdefault("reasoning_effort", "none")
        req["stream"] = bool(req.get("stream"))
        up = http.client.HTTPConnection(*OLLAMA, timeout=300)
        try:
            up.request("POST", "/v1/chat/completions", json.dumps(req), {"Content-Type": "application/json"})
            r = up.getresponse()
            self.send_response(r.status)
            self.send_header("Content-Type", r.getheader("Content-Type", "application/json"))
            if req["stream"]:
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()
                while True:
                    chunk = r.read(1024)
                    if not chunk:
                        break
                    self.wfile.write(b"%x\r\n" % len(chunk) + chunk + b"\r\n")
                    self.wfile.flush()
                self.wfile.write(b"0\r\n\r\n")
            else:
                data = r.read()
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
        except Exception:
            try:
                self._send(502, {"error": "upstream_unavailable"})
            except Exception:
                pass
        finally:
            up.close()


if __name__ == "__main__":
    threading.Thread(target=_warm, daemon=True).start()
    ThreadingHTTPServer(LISTEN, H).serve_forever()
