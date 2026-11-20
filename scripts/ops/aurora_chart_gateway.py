#!/usr/bin/env python3
"""Loopback chart adapter for Aurora's Ollama, with a dedicated bearer token."""

import argparse
from contextlib import suppress
import hmac
from http.client import HTTPConnection, HTTPException
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
import os
from pathlib import Path
import re
import select
import socket
import subprocess
import threading
import time

LISTEN = ("127.0.0.1", 11501)
OLLAMA = ("127.0.0.1", 11434)
MODEL = "qwen3:14b"
MAX_BODY = 64 * 1024
MAX_TOKENS = 900
INFERENCE_TIMEOUT = 52.0
F006_STATE = Path.home() / ".local/share/cretas-f006-aurora/state/latest.json"
LOGGER = logging.getLogger(__name__)


class Busy(Exception):
    pass


def idle_gateway_revision():
    """Observe the F006 state without acquiring its lease or changing its files."""
    try:
        state = json.loads(F006_STATE.read_text(encoding="utf-8"))
        if (state["request_active"] is False and state["status"] in {"ready", "idle_after_refusal"}
                and type(state["updated_at"]) is int and state["updated_at"] > 0):
            require_idle_f006_socket()
            return state["updated_at"]
    except (OSError, KeyError, TypeError, ValueError):
        pass
    raise Busy("f006_gateway_busy_or_unknown")


def require_idle_f006_socket():
    result = subprocess.run(
        ["/usr/sbin/lsof", "-nP", "-tiTCP:11438", "-sTCP:ESTABLISHED"],
        capture_output=True, timeout=2,
    )
    # lsof returns 1 and emits nothing for no matching socket. Any other
    # outcome is either an incoming F006 request or an unreadable probe.
    if result.returncode != 1 or result.stdout or result.stderr:
        raise Busy("f006_gateway_connected_or_unreadable")


def require_memory(min_free):
    pressure = subprocess.check_output(
        ["sysctl", "-n", "kern.memorystatus_vm_pressure_level"], text=True, timeout=2,
    ).strip()
    memory = subprocess.check_output(["memory_pressure", "-Q"], text=True, timeout=2)
    free = re.search(r"free percentage: (\d+)%", memory)
    if pressure != "1" or free is None or int(free[1]) < min_free:
        raise Busy("memory_headroom")


def require_empty_ollama():
    connection = HTTPConnection(*OLLAMA, timeout=2)
    try:
        connection.request("GET", "/api/ps")
        response = connection.getresponse()
        if response.status != 200 or json.load(response)["models"] != []:
            raise Busy("ollama_busy_or_unknown")
    finally:
        connection.close()


def native_request(body):
    if not isinstance(body, dict) or body.get("model") != MODEL:
        raise ValueError("model_not_served")
    if body.get("stream") or body.get("tools"):
        raise ValueError("only_non_stream_text_supported")
    messages = body.get("messages")
    if not isinstance(messages, list) or not messages:
        raise ValueError("invalid_messages")
    clean = []
    for message in messages:
        if not isinstance(message, dict) or message.get("role") not in ("system", "user", "assistant"):
            raise ValueError("invalid_message_role")
        if not isinstance(message.get("content"), str):
            raise ValueError("invalid_message_content")
        clean.append({"role": message["role"], "content": message["content"]})
    tokens = body.get("max_tokens", MAX_TOKENS)
    if type(tokens) is not int or tokens <= 0:
        raise ValueError("invalid_max_tokens")
    return {
        "model": MODEL,
        "messages": clean,
        "stream": True,
        "format": "json",
        "think": False,
        "keep_alive": 0,
        "options": {"temperature": 0, "seed": 1234, "num_predict": min(tokens, MAX_TOKENS)},
    }


def infer(payload, downstream):
    revision = idle_gateway_revision()
    require_memory(65)
    require_empty_ollama()
    if idle_gateway_revision() != revision:
        raise Busy("f006_gateway_changed")

    upstream = HTTPConnection(*OLLAMA, timeout=INFERENCE_TIMEOUT)
    upstream.connect()
    upstream_socket = upstream.sock
    stop = threading.Event()
    interrupted = []
    deadline = time.monotonic() + INFERENCE_TIMEOUT

    def monitor():
        next_memory_check = time.monotonic() + 1
        while not stop.is_set():
            try:
                if idle_gateway_revision() != revision:
                    raise Busy("f006_gateway_changed")
                now = time.monotonic()
                if now >= deadline:
                    raise TimeoutError("inference_timeout")
                if now >= next_memory_check:
                    require_memory(15)
                    next_memory_check = now + 1
                if select.select([downstream], [], [], 0.2)[0]:
                    raise ConnectionAbortedError("chart_client_disconnected")
            except (Busy, OSError, subprocess.SubprocessError) as error:
                interrupted.append(error)
                with suppress(OSError):
                    upstream_socket.shutdown(socket.SHUT_RDWR)
                return

    watcher = threading.Thread(target=monitor, daemon=True)
    watcher.start()
    try:
        upstream.request("POST", "/api/chat", json.dumps(payload), {"Content-Type": "application/json"})
        response = upstream.getresponse()
        if response.status != 200:
            raise ValueError("ollama_bad_status")
        content = []
        for line in response:
            chunk = json.loads(line)
            if chunk.get("error"):
                raise ValueError("ollama_stream_error")
            part = chunk.get("message", {}).get("content")
            if part:
                content.append(part)
            if chunk.get("done"):
                if chunk.get("done_reason") != "stop" or not content:
                    raise ValueError("ollama_incomplete")
                prompt = chunk["prompt_eval_count"]
                generated = chunk["eval_count"]
                return {
                    "model": MODEL,
                    "choices": [{"index": 0, "message": {"role": "assistant", "content": "".join(content)},
                                 "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": prompt, "completion_tokens": generated,
                              "total_tokens": prompt + generated},
                }
        raise ValueError("ollama_no_terminal_frame")
    finally:
        stop.set()
        watcher.join()
        upstream.close()
        if interrupted:
            raise interrupted[0]
        if idle_gateway_revision() != revision:
            raise Busy("f006_gateway_changed")


class Gateway(ThreadingHTTPServer):
    def __init__(self, address, token):
        self.token = ("Bearer " + token).encode()
        self.inflight = threading.Lock()
        super().__init__(address, Handler)

    def handle_error(self, request, client_address):
        LOGGER.warning("chart gateway connection failed")


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        self.request.settimeout(3)
        super().setup()

    def log_message(self, fmt, *args):
        # BaseHTTPRequestHandler's request line can contain arbitrary input.
        pass

    def reply(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        with suppress(BrokenPipeError, ConnectionResetError):
            self.wfile.write(data)

    def authorized(self):
        return hmac.compare_digest(self.headers.get("Authorization", "").encode(), self.server.token)

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, {"status": "ready"})
        elif self.path == "/v1/models":
            if not self.authorized():
                self.reply(401, {"error": "unauthorized"})
                return
            self.reply(200, {"data": [{"id": MODEL, "object": "model"}], "object": "list"})
        else:
            self.reply(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.reply(404, {"error": "not_found"})
            return
        if not self.authorized():
            self.reply(401, {"error": "unauthorized"})
            return
        lengths = self.headers.get_all("Content-Length", [])
        if (self.headers.get("Transfer-Encoding") or len(lengths) != 1
                or not re.fullmatch(r"[0-9]{1,5}", lengths[0])
                or not 0 < int(lengths[0]) <= MAX_BODY):
            self.reply(413, {"error": "invalid_content_length"})
            return
        try:
            body = json.loads(self.rfile.read(int(lengths[0])))
            payload = native_request(body)
        except (OSError, ValueError):
            self.reply(400, {"error": "invalid_chart_request"})
            return
        if not self.server.inflight.acquire(blocking=False):
            self.reply(429, {"error": "chart_busy"})
            return
        try:
            result = infer(payload, self.connection)
        except Busy:
            self.reply(429, {"error": "aurora_busy"})
        except ConnectionAbortedError:
            return
        except TimeoutError:
            self.reply(504, {"error": "inference_timeout"})
        except (OSError, ValueError, KeyError, TypeError, HTTPException, subprocess.SubprocessError) as error:
            LOGGER.warning("chart inference failed (%s)", type(error).__name__)
            self.reply(502, {"error": "ollama_unavailable"})
        else:
            self.reply(200, result)
        finally:
            self.server.inflight.release()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--token-file", type=Path, required=True)
    args = parser.parse_args()
    info = args.token_file.stat()
    if info.st_uid != os.getuid() or info.st_mode & 0o077 or not args.token_file.is_file():
        parser.error("token file must be private and owned by the gateway user")
    token = args.token_file.read_text(encoding="utf-8").strip()
    if not re.fullmatch(r"[a-fA-F0-9]{64}", token):
        parser.error("token file must contain a 64-character hexadecimal token")
    logging.basicConfig(level=logging.INFO)
    with Gateway(LISTEN, token) as gateway:
        gateway.serve_forever()


if __name__ == "__main__":
    main()
