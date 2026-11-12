"""出境出口三层探针 / 主备切换 / 告警 / 外发字段记录 (INTENT-DISTILL-20261111 Luna 出口线, 2026-11-12)。"""
from __future__ import annotations

import asyncio
import json
import os
import socket

import httpx
import pytest

from common import llm_egress_health as eh


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    for k in ("LLM_EGRESS_PROXY", "LLM_EGRESS_PROXY_FALLBACK", "LLM_EGRESS_FORCE", "LLM_OPENAI_API_KEY",
              "LLM_EGRESS_SWITCH_AFTER", "LLM_EGRESS_FAILBACK_AFTER", "LLM_EGRESS_ALERT_AFTER",
              "LLM_EGRESS_STATE_DIR", "LLM_EGRESS_PROBE_URL"):
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setattr(eh, "_active_name", "primary")
    yield


def _free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


# ───────────── 三层探针: 真 socket 构造三种断法 ─────────────

def test_tunnel_down_when_nothing_listens():
    port = _free_port()
    r = asyncio.run(eh.probe_egress("primary", f"socks5h://127.0.0.1:{port}", timeout=1.0))
    assert not r.up
    assert r.layers["tunnel"].up is False and r.layers["tunnel"].outcome in ("refused", "conn_error", "timeout")  # Windows 重试 SYN ⇒ timeout
    assert r.layers["proxy"].outcome == "skipped" and r.layers["upstream"].outcome == "skipped"
    assert r.first_down() == "tunnel"


def test_proxy_down_when_listener_accepts_then_closes():
    """SSH -R 的形状: 47 上 sshd 接了 TCP, mini 上 Clash 没在跑 ⇒ 立刻关。tunnel 通、proxy 断。"""
    async def main():
        async def handler(reader, writer):
            writer.close()
        server = await asyncio.start_server(handler, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        async with server:
            return await eh.probe_egress("primary", f"socks5h://127.0.0.1:{port}", timeout=1.0)
    r = asyncio.run(main())
    assert r.layers["tunnel"].up is True
    assert r.layers["proxy"].up is False and r.layers["proxy"].outcome == "closed"
    assert r.layers["upstream"].outcome == "skipped"
    assert r.first_down() == "proxy"


def test_socks_handshake_ok_then_upstream_classified(monkeypatch):
    async def fake_upstream(url, timeout):
        return eh.LayerResult(False, "region_blocked", 0.1)
    monkeypatch.setattr(eh, "_probe_upstream", fake_upstream)

    async def main():
        async def handler(reader, writer):
            assert await reader.readexactly(3) == b"\x05\x01\x00"
            writer.write(b"\x05\x00")
            await writer.drain()
            await asyncio.sleep(0.05)
            writer.close()
        server = await asyncio.start_server(handler, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        async with server:
            return await eh.probe_egress("primary", f"socks5h://127.0.0.1:{port}", timeout=1.0)
    r = asyncio.run(main())
    assert r.layers["tunnel"].up and r.layers["proxy"].up
    assert r.layers["upstream"].outcome == "region_blocked" and not r.up
    assert r.first_down() == "upstream"


@pytest.mark.parametrize("code,key,up,outcome", [
    (200, "sk-x", True, "ok"),
    (401, "", True, "auth"),        # 没配 key: 401 证明 OpenAI 应答了
    (401, "sk-x", False, "auth"),   # 配了 key 还 401: key 坏, Luna 照样用不了
    (403, "", False, "region_blocked"),
    (503, "", False, "http_503"),
])
def test_upstream_classification(monkeypatch, code, key, up, outcome):
    if key:
        monkeypatch.setenv("LLM_OPENAI_API_KEY", key)
    seen = {}

    def handler(request):
        seen["auth"] = request.headers.get("authorization")
        return httpx.Response(code, json={})
    real = httpx.AsyncClient

    def factory(*a, **kw):
        kw.pop("proxy", None)
        return real(transport=httpx.MockTransport(handler), **kw)
    monkeypatch.setattr(httpx, "AsyncClient", factory)
    r = asyncio.run(eh._probe_upstream("socks5h://127.0.0.1:1", 2.0))
    assert (r.up, r.outcome) == (up, outcome)
    assert seen["auth"] == (f"Bearer {key}" if key else None)


def test_upstream_timeout(monkeypatch):
    def handler(request):
        raise httpx.ConnectTimeout("t", request=request)
    real = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient",
                        lambda *a, **kw: real(transport=httpx.MockTransport(handler),
                                              **{k: v for k, v in kw.items() if k != "proxy"}))
    r = asyncio.run(eh._probe_upstream("socks5h://127.0.0.1:1", 2.0))
    assert not r.up and r.outcome == "timeout"


# ───────────── 切换 / 告警 (纯函数) ─────────────

def _reading(name, up, first_down="proxy"):
    r = eh.EgressReading(name)
    for layer in eh.LAYERS:
        ok = up or eh.LAYERS.index(layer) < eh.LAYERS.index(first_down)
        r.layers[layer] = eh.LayerResult(ok, "ok" if ok else "closed", 0.01)
    return r


def test_switch_to_fallback_after_n_failures_and_alert_once(monkeypatch):
    monkeypatch.setenv("LLM_EGRESS_PROXY", "socks5h://127.0.0.1:11438")
    monkeypatch.setenv("LLM_EGRESS_PROXY_FALLBACK", "socks5h://127.0.0.1:11439")
    st, alerts = eh.decide({}, [_reading("primary", False), _reading("fallback1", True)], 1.0)
    assert st["active"] == "primary" and alerts == []          # 1 次失败: 不切、不喊
    st, alerts = eh.decide(st, [_reading("primary", False), _reading("fallback1", True)], 2.0)
    kinds = [k for k, _ in alerts]
    assert st["active"] == "fallback1" and kinds == ["DOWN", "SWITCH"]
    assert dict(alerts)["DOWN"]["first_down"] == "proxy"
    st, alerts = eh.decide(st, [_reading("primary", False), _reading("fallback1", True)], 3.0)
    assert alerts == [] and st["active"] == "fallback1"        # 不刷屏
    # 主出口恢复: 先 RECOVERED, 连续 3 轮通才切回
    st, alerts = eh.decide(st, [_reading("primary", True), _reading("fallback1", True)], 4.0)
    assert [k for k, _ in alerts] == ["RECOVERED"] and st["active"] == "fallback1"
    st, _ = eh.decide(st, [_reading("primary", True), _reading("fallback1", True)], 5.0)
    st, alerts = eh.decide(st, [_reading("primary", True), _reading("fallback1", True)], 6.0)
    assert st["active"] == "primary" and alerts == [("SWITCH", {"from": "fallback1", "to": "primary",
                                                                "reason": "failback"})]


def test_no_switch_when_fallback_also_down(monkeypatch):
    monkeypatch.setenv("LLM_EGRESS_PROXY", "socks5h://a:1")
    monkeypatch.setenv("LLM_EGRESS_PROXY_FALLBACK", "socks5h://b:2")
    st = {}
    for t in range(3):
        st, alerts = eh.decide(st, [_reading("primary", False), _reading("fallback1", False, "tunnel")], t)
    assert st["active"] == "primary"
    assert st["egresses"]["fallback1"]["last"]["first_down"] == "tunnel"


def test_force_switch(monkeypatch):
    monkeypatch.setenv("LLM_EGRESS_PROXY", "socks5h://a:1")
    monkeypatch.setenv("LLM_EGRESS_PROXY_FALLBACK", "socks5h://b:2")
    monkeypatch.setenv("LLM_EGRESS_FORCE", "fallback1")
    st, alerts = eh.decide({}, [_reading("primary", True), _reading("fallback1", True)], 1.0)
    assert st["active"] == "fallback1" and alerts[0][1]["reason"] == "forced"
    assert eh.active_proxy_url() == "socks5h://b:2"


# ───────────── 接线: llm_client 真的读当前出口 (形态 B) ─────────────

def test_llm_client_egress_url_follows_active_egress(monkeypatch):
    from common.llm_client import egress_proxy_url
    monkeypatch.setenv("LLM_EGRESS_PROXY", "socks5h://127.0.0.1:11438")
    assert egress_proxy_url() == "socks5h://127.0.0.1:11438"      # 没配备用: 原值
    eh.apply_state_to_process({"active": "fallback1"})               # 状态里有, 配置里没有 ⇒ 不变
    assert egress_proxy_url() == "socks5h://127.0.0.1:11438"
    monkeypatch.setenv("LLM_EGRESS_PROXY_FALLBACK", "socks5h://127.0.0.1:11439")
    eh.apply_state_to_process({"active": "fallback1"})
    assert egress_proxy_url() == "socks5h://127.0.0.1:11439"
    eh.apply_state_to_process({"active": "primary"})
    assert egress_proxy_url() == "socks5h://127.0.0.1:11438"
    monkeypatch.delenv("LLM_EGRESS_PROXY")
    monkeypatch.delenv("LLM_EGRESS_PROXY_FALLBACK")
    assert egress_proxy_url() == ""                                   # 没配出口: 付费两家不发


def test_main_lifespan_starts_the_probe():
    src = open(os.path.join(os.path.dirname(__file__), "..", "main.py"), encoding="utf-8").read()
    assert "from common.llm_egress_health import start_background_probe" in src
    assert "from common.llm_egress_health import stop_background_probe" in src


# ───────────── run_round: 状态文件 + 告警文件 + rc 三态 ─────────────

def test_run_round_writes_state_and_alert_file(tmp_path, monkeypatch):
    port = _free_port()
    monkeypatch.setenv("LLM_EGRESS_PROXY", f"socks5h://127.0.0.1:{port}")
    monkeypatch.setenv("LLM_EGRESS_PROBE_TIMEOUT_S", "1")
    rc1, st1 = asyncio.run(eh.run_round(str(tmp_path)))
    rc2, st2 = asyncio.run(eh.run_round(str(tmp_path)))
    assert (rc1, rc2) == (1, 1)
    state = json.loads((tmp_path / eh.STATE_FILE).read_text(encoding="utf-8"))
    assert state["rounds"] == 2 and state["last_run"] >= st1["last_run"]   # 「一定会发生」的读数
    lines = (tmp_path / eh.ALERT_FILE).read_text(encoding="utf-8").splitlines()
    assert len(lines) == 1 and lines[0].startswith("LLM EGRESS DOWN ")
    assert '"first_down": "tunnel"' in lines[0]


def test_run_round_rc2_when_not_measurable(tmp_path, monkeypatch):
    rc, st = asyncio.run(eh.run_round(str(tmp_path)))
    assert rc == 2 and st["reason"] == "no_egress_configured"
    monkeypatch.setenv("LLM_EGRESS_PROXY", "socks5h://127.0.0.1:1")
    rc, st = asyncio.run(eh.run_round(str(tmp_path / "missing-dir")))
    assert rc == 2 and st["reason"] == "PermissionError"


# ───────────── 外发字段记录 ─────────────

LUNA_BODY = {"model": "gpt-6-luna", "input": "用户原话 秘密原文ABC",
             "questions": [{"type": "choice", "name": "intent", "instructions": "规则说明",
                            "choices": [{"value": "MATERIAL_QUERY", "description": "查原料"},
                                        {"value": "BATCH_QUERY", "description": "查批次"}]}]}
SYSTEMONE_BODY = {"model": "decision-model-preview", "state": "身份 上文 秘密原文ABC",
                  "questions": {"intent": {"type": "choice",
                                           "criteria": {"MATERIAL_QUERY": "查原料", "BATCH_QUERY": "查批次"}}}}


def test_field_summary_names_and_bytes_never_values():
    from common.llm_metrics import _payload_field_summary
    keys = _payload_field_summary(LUNA_BODY, 321)
    joined = json.dumps(keys, ensure_ascii=False)
    assert "秘密原文" not in joined and "MATERIAL_QUERY" not in joined
    d = dict(k.rsplit(":", 1) for k in keys)
    assert d["input"] == str(len("用户原话 秘密原文ABC".encode("utf-8")))
    assert d["questions[].choices[].description"] == str(len("查原料查批次".encode("utf-8")))
    assert d["__body__"] == "321"
    keys2 = _payload_field_summary(SYSTEMONE_BODY, 99)
    j2 = json.dumps(keys2, ensure_ascii=False)
    assert "MATERIAL_QUERY" not in j2 and "秘密原文" not in j2       # 选项值不当路径
    assert any(k.startswith("questions.*.criteria.*:") for k in keys2)


@pytest.mark.parametrize("url,body", [
    ("https://api.openai.com/v1/decisions", LUNA_BODY),
    ("https://x.dashscope.aliyuncs.com/compatible-mode/v1/systemone", SYSTEMONE_BODY),
])
def test_metrics_hook_records_decision_egress_fields(monkeypatch, url, body):
    """产品真实入口: 共享客户端的 response hook。以前这两种请求体 prompt_chars=0、sent_field_keys 空。"""
    from common import llm_egress_audit, llm_metrics
    from common.llm_redactor import RedactionMeta
    got = []
    monkeypatch.setattr(llm_egress_audit, "record_egress", lambda rec: got.append(rec))
    monkeypatch.setattr(llm_metrics, "_enabled", True)

    async def main():
        llm_metrics._usage_queue = asyncio.Queue()
        llm_metrics.set_egress_redaction_meta(RedactionMeta(sanitized=True, redacted_count=1))
        tok = llm_metrics._llm_factory.set("F006")
        try:
            content = json.dumps(body, ensure_ascii=False).encode("utf-8")
            req = httpx.Request("POST", url, content=content)
            resp = httpx.Response(200, json={"usage": {}}, request=req)
            await llm_metrics.metrics_response_hook(resp)
        finally:
            llm_metrics._llm_factory.reset(tok)
        return len(content)
    n = asyncio.run(main())
    assert len(got) == 1
    rec = got[0]
    assert rec.factory_id == "F006" and rec.sanitized is True and rec.status_code == 200
    assert rec.prompt_chars > 0 and rec.prompt_sha256 != llm_egress_audit.sha256_of("")
    assert rec.sent_field_keys[-1] == f"__body__:{n}"
    assert "秘密原文" not in json.dumps(rec.sent_field_keys, ensure_ascii=False)
