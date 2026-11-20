"""Loopback protocol checks with a native Ollama stand-in. No remote traffic."""

from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib.util import module_from_spec, spec_from_file_location
from io import StringIO
import json
from pathlib import Path
import socket
import threading
from types import SimpleNamespace

import httpx
import pytest

_PATH = Path(__file__).resolve().parents[3] / "scripts/ops/aurora_chart_gateway.py"
_SPEC = spec_from_file_location("aurora_chart_gateway", _PATH)
gateway = module_from_spec(_SPEC)
_SPEC.loader.exec_module(gateway)
_CHECK_SPEC = spec_from_file_location("aurora_chart_check", _PATH.with_name("aurora_chart_check.py"))
chart_check = module_from_spec(_CHECK_SPEC)
_CHECK_SPEC.loader.exec_module(chart_check)
_BENCHMARK_SPEC = spec_from_file_location(
    "benchmark_aurora_chart", Path(__file__).with_name("benchmark_aurora_chart.py"),
)
benchmark = module_from_spec(_BENCHMARK_SPEC)
_BENCHMARK_SPEC.loader.exec_module(benchmark)

AUTH = {"Authorization": "Bearer unit-test-chart"}
REQUEST = {"model": "qwen3:14b", "messages": [{"role": "user", "content": "JSON"}]}


@contextmanager
def _running(server):
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.02})
    thread.start()
    try:
        yield server.server_address
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


@pytest.fixture
def proxy(monkeypatch, tmp_path):
    requests = []
    f006_connection = threading.Event()
    started = threading.Event()
    cancelled = threading.Event()
    state = tmp_path / "latest.json"
    state.write_text('{"status":"ready","request_active":false,"updated_at":1}', encoding="utf-8")
    monkeypatch.setattr(gateway, "F006_STATE", state)
    monkeypatch.setattr(gateway, "require_memory", lambda _minimum: None)

    def require_idle_f006_socket():
        if f006_connection.is_set():
            raise gateway.Busy("f006_gateway_connected_or_unreadable")

    monkeypatch.setattr(gateway, "require_idle_f006_socket", require_idle_f006_socket)

    class Ollama(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_GET(self):
            assert self.path == "/api/ps"
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"models":[]}')

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            requests.append((self.path, body))
            reason = body["messages"][0]["content"]
            if reason in ("wait for busy", "wait for connection", "wait for disconnect"):
                if reason == "wait for busy":
                    state.write_text('{"status":"running","request_active":true,"updated_at":2}', encoding="utf-8")
                elif reason == "wait for connection":
                    f006_connection.set()
                started.set()
                self.connection.settimeout(2)
                assert self.connection.recv(1) == b"", "watchdog did not cancel Ollama"
                cancelled.set()
                return
            self.send_response(200)
            self.end_headers()
            for chunk in (
                {"message": {"content": '{"selected_ids":'}},
                {"message": {"content": '["chart_approved"]}'}},
                {"done": True, "done_reason": "stop", "prompt_eval_count": 11, "eval_count": 4},
            ):
                self.wfile.write((json.dumps(chunk) + "\n").encode())

    with _running(ThreadingHTTPServer(("127.0.0.1", 0), Ollama)) as upstream:
        monkeypatch.setattr(gateway, "OLLAMA", upstream)
        with _running(gateway.Gateway(("127.0.0.1", 0), "unit-test-chart")) as (host, port):
            with httpx.Client(base_url=f"http://{host}:{port}", trust_env=False) as client:
                yield client, state, requests, port, f006_connection, started, cancelled


def test_chart_gateway_translates_and_forwards_only_the_authenticated_fixed_model(proxy):
    client, _, requests, *_ = proxy
    assert client.get("/health").status_code == 200
    assert client.get("/v1/models").status_code == 401
    assert client.post("/v1/chat/completions", json=REQUEST).status_code == 401
    wrong_model = client.post("/v1/chat/completions", headers=AUTH, json={**REQUEST, "model": "qwen3.8:27b"})
    assert wrong_model.status_code == 400
    assert client.post("/api/chat", headers=AUTH, json=REQUEST).status_code == 404
    assert requests == []

    response = client.post("/v1/chat/completions", headers=AUTH, json=REQUEST)
    assert response.status_code == 200
    content = response.json()
    assert json.loads(content["choices"][0]["message"]["content"]) == {"selected_ids": ["chart_approved"]}
    assert content["usage"] == {"prompt_tokens": 11, "completion_tokens": 4, "total_tokens": 15}
    assert requests == [("/api/chat", {
        **REQUEST, "stream": True, "format": "json", "think": False, "keep_alive": 0,
        "options": {"temperature": 0, "seed": 1234, "num_predict": 900},
    })]


def test_operator_checker_uses_actual_loopback_gateway_protocol(proxy, monkeypatch):
    _, _, requests, port, *_ = proxy
    monkeypatch.setattr(chart_check, "PORT", port)
    token = "unit-test-chart"
    chart_check.check_protocol(token)
    assert requests == []
    with pytest.raises(ValueError, match="chart_model_contract_failed"):
        chart_check.check_protocol("wrong-token")
    assert requests == []


@pytest.mark.parametrize("host", ["127.0.0.1", "0.0.0.0"])
def test_operator_checker_rejects_public_listener(host, monkeypatch):
    monkeypatch.setattr(chart_check.subprocess, "check_output",
                        lambda *_args, **_kwargs: f"LISTEN 0 128 {host}:11440 0.0.0.0:*\n")
    if host == "127.0.0.1":
        chart_check.check_listener()
    else:
        with pytest.raises(ValueError, match="chart_listener_not_exclusively_loopback"):
            chart_check.check_listener()


@pytest.mark.parametrize("contents", [
    '{"status":"running","request_active":true,"updated_at":1}',
    '{"status":"ready","request_active":false}',
    "{unreadable",
])
def test_chart_gateway_declines_active_or_unreadable_f006(proxy, contents):
    client, state, requests, *_ = proxy
    state.write_text(contents, encoding="utf-8")
    assert client.post("/v1/chat/completions", headers=AUTH, json=REQUEST).status_code == 429
    assert requests == []


@pytest.mark.parametrize("signal", ["wait for busy", "wait for connection"])
def test_chart_gateway_cancels_before_ollama_first_byte_if_f006_starts(proxy, signal):
    client, _, requests, *_ = proxy
    payload = {**REQUEST, "messages": [{"role": "user", "content": signal}]}
    assert client.post("/v1/chat/completions", headers=AUTH, json=payload).status_code == 429
    assert len(requests) == 1


def test_chart_gateway_refuses_f006_connection_before_state_changes(proxy):
    client, _, requests, _, f006_connection, *_ = proxy
    f006_connection.set()
    assert client.post("/v1/chat/completions", headers=AUTH, json=REQUEST).status_code == 429
    assert requests == []


@pytest.mark.parametrize(("exit_code", "stdout", "stderr", "idle"), [
    (1, b"", b"", True),
    (0, b"96201\n", b"", False),
    (1, b"", b"lsof error", False),
])
def test_f006_socket_probe_accepts_only_a_readable_empty_result(monkeypatch, exit_code, stdout, stderr, idle):
    def run(cmd, *, capture_output, timeout):
        assert cmd == ["/usr/sbin/lsof", "-nP", "-tiTCP:11438", "-sTCP:ESTABLISHED"]
        assert capture_output and timeout == 2
        return SimpleNamespace(returncode=exit_code, stdout=stdout, stderr=stderr)

    monkeypatch.setattr(gateway.subprocess, "run", run)
    if idle:
        gateway.require_idle_f006_socket()
    else:
        with pytest.raises(gateway.Busy):
            gateway.require_idle_f006_socket()


def test_chart_gateway_rejects_chunked_request_before_ollama(proxy):
    _, _, requests, port, *_ = proxy
    with socket.create_connection(("127.0.0.1", port), timeout=2) as sock:
        sock.sendall(b"POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                     b"Authorization: Bearer unit-test-chart\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")
        assert b" 413 " in sock.recv(1024).split(b"\r\n", 1)[0]
    assert requests == []


def test_chart_gateway_cancels_ollama_when_router_disconnects_before_first_byte(proxy):
    _, _, requests, port, _, started, cancelled = proxy
    payload = json.dumps({**REQUEST, "messages": [{"role": "user", "content": "wait for disconnect"}]}).encode()
    with socket.create_connection(("127.0.0.1", port), timeout=2) as sock:
        headers = ("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                   f"Authorization: {AUTH['Authorization']}\r\nContent-Length: {len(payload)}\r\n\r\n")
        sock.sendall(headers.encode() + payload)
        assert started.wait(1), "upstream inference never started"
    assert cancelled.wait(1)
    assert len(requests) == 1


def test_benchmark_stops_if_f006_connects_before_ollama_first_byte():
    context = {}
    exec(benchmark.AURORA_PROBE, context)
    sent = threading.Event()
    upstream, peer = socket.socketpair()
    with upstream, peer:
        class BlockedOllama:
            def __init__(self, _host, _port, timeout):
                self.sock = upstream

            def request(self, method, _path, *_args):
                if method == "POST":
                    sent.set()

            def connect(self):
                pass

            def getresponse(self):
                if not sent.is_set():
                    return StringIO('{"models":[]}')
                self.sock.settimeout(2)
                assert self.sock.recv(1) == b"", "the benchmark held the Ollama socket open"
                raise ConnectionAbortedError()

            def close(self):
                pass

        def gateway_revision():
            if sent.is_set():
                raise RuntimeError("f006_gateway_connected_or_unreadable")
            return 1

        context["check_state"] = gateway_revision
        context["http"] = SimpleNamespace(client=SimpleNamespace(HTTPConnection=BlockedOllama))
        context["subprocess"] = SimpleNamespace(
            check_output=lambda *_args, **_kwargs: "System-wide memory free percentage: 88%",
            SubprocessError=OSError,
        )
        with pytest.raises(RuntimeError, match="f006_gateway_connected"):
            context["run_probe"](REQUEST)
