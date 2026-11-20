"""Run the 12 documented Blacklake chart questions on each installed Aurora model."""

import argparse
import asyncio
import json
import math
from pathlib import Path
import statistics
import subprocess
import sys
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
PYTHON = ROOT / "backend/python"

MODELS = ("qwen3:8b", "qwen3:14b", "qwen3.5:9b", "gemma4:12b", "gemma4:26b", "qwen3.8:27b")
SSH = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=8"]
WARM_KEEP_ALIVE = "15s"

# Sent over SSH on stdin. This code only reads runtime state and sends one
# inference request. A streaming socket is closed if F006 starts or resources
# move outside the observed idle window. No F006 lock is acquired or modified.
AURORA_PROBE = r"""
from contextlib import suppress
import http.client
import json
from pathlib import Path
import re
import socket
import subprocess
import threading
import time

state_path = Path.home() / '.local/share/cretas-f006-aurora/state/latest.json'

def check_state():
    state = json.loads(state_path.read_text())
    if state['request_active'] is not False or state['status'] not in ('ready', 'idle_after_refusal'):
        raise RuntimeError('f006_gateway_busy')
    if subprocess.check_output(['sysctl', '-n', 'kern.memorystatus_vm_pressure_level'], text=True).strip() != '1':
        raise RuntimeError('memory_pressure')
    sockets = subprocess.run(
        ['/usr/sbin/lsof', '-nP', '-tiTCP:11438', '-sTCP:ESTABLISHED'],
        capture_output=True, text=True, timeout=2,
    )
    if sockets.returncode != 1 or sockets.stdout or sockets.stderr:
        raise RuntimeError('f006_gateway_connected_or_unreadable')
    return state['updated_at']

def machine_state():
    connection = http.client.HTTPConnection('127.0.0.1', 11434, timeout=5)
    try:
        connection.request('GET', '/api/ps')
        resident = [model['name'] for model in json.load(connection.getresponse())['models']]
    finally:
        connection.close()
    workers = subprocess.run(['pgrep', '-x', 'Runner.Worker'], capture_output=True, text=True, timeout=2)
    listeners = subprocess.run(['pgrep', '-x', 'Runner.Listener'], capture_output=True, text=True, timeout=2)
    if any(probe.returncode not in (0, 1) or probe.stderr for probe in (workers, listeners)):
        raise RuntimeError('ci_runner_probe_unavailable')
    return dict(uptime=subprocess.check_output(['uptime'], text=True, timeout=2).strip(),
                memory=subprocess.check_output(['memory_pressure', '-Q'], text=True, timeout=2).strip(),
                sysctl=subprocess.check_output(['sysctl', 'hw.memsize', 'vm.swapusage',
                                                'kern.memorystatus_vm_pressure_level'], text=True, timeout=2).strip(),
                workers=len(workers.stdout.splitlines()), listeners=len(listeners.stdout.splitlines()),
                resident_models=resident)

def run_probe(payload, timeout=90, *, warm=False, rex=False):
    connection = http.client.HTTPConnection('127.0.0.1', 11434, timeout=5)
    try:
        connection.request('GET', '/api/ps')
        resident = [model.get('name') for model in json.load(connection.getresponse())['models']]
        if resident and (not warm or resident != [payload['model']]):
            raise RuntimeError('ollama_already_has_active_model')
    finally:
        connection.close()
    def require_memory(minimum):
        memory = subprocess.check_output(['memory_pressure', '-Q'], text=True, timeout=2)
        if int(re.search(r'free percentage: (\d+)%', memory)[1]) < minimum:
            raise RuntimeError('memory_headroom')

    require_memory(50 if resident and not rex else 65)

    def check_inference_safety():
        if not rex:
            return check_state()
        if subprocess.check_output(['sysctl', '-n', 'kern.memorystatus_vm_pressure_level'], text=True).strip() != '1':
            raise RuntimeError('memory_pressure')
        result = subprocess.run(['pgrep', '-x', 'Runner.Worker'], capture_output=True, timeout=2)
        if result.returncode != 1 or result.stdout or result.stderr:
            raise RuntimeError('ci_runner_busy_or_unreadable')

    f006_update = check_inference_safety()
    connection = http.client.HTTPConnection('127.0.0.1', 11434, timeout=timeout)
    connection.connect()
    connected = connection.sock
    stop = threading.Event()
    interrupted = []
    deadline = time.monotonic() + timeout

    def monitor():
        check_memory_at = time.monotonic() + 1
        while not stop.wait(0.25):
            try:
                now = time.monotonic()
                if now >= deadline:
                    raise TimeoutError('benchmark_inference_timeout')
                if check_inference_safety() != f006_update:
                    raise RuntimeError('f006_gateway_changed')
                if not rex and now >= check_memory_at:
                    require_memory(50 if payload.get('keep_alive') else 15)
                    check_memory_at = now + 1
            except (RuntimeError, ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
                interrupted.append(error)
                with suppress(OSError):
                    connected.shutdown(socket.SHUT_RDWR)
                return

    watcher = threading.Thread(target=monitor, daemon=True)
    first = None
    parts = []
    thinking_seen = False
    done = None
    watcher.start()
    start = time.monotonic()
    try:
        connection.request('POST', '/api/chat', json.dumps(payload), {'Content-Type': 'application/json'})
        response = connection.getresponse()
        if response.status != 200:
            raise RuntimeError(f'ollama_http_{response.status}')
        for line in response:
            now = time.monotonic()
            chunk = json.loads(line)
            if chunk.get('error'):
                raise RuntimeError('ollama_stream_error')
            if chunk.get('model') != payload['model']:
                raise RuntimeError('ollama_returned_unexpected_model')
            message = chunk.get('message', {})
            thinking_seen |= bool(message.get('thinking'))
            content = message.get('content')
            if content:
                if first is None:
                    first = round((now - start) * 1000)
                parts.append(content)
            if chunk.get('done'):
                done = chunk
                break
        elapsed = round((time.monotonic() - start) * 1000)
    finally:
        stop.set()
        watcher.join()
        connection.close()
        if interrupted:
            raise interrupted[0]
        if check_inference_safety() != f006_update:
            raise RuntimeError('f006_gateway_changed')
    if done is None:
        raise RuntimeError('ollama_no_terminal_frame')
    return dict(ms=elapsed, ttft_ms=first, content=''.join(parts),
                load_ms=round(done['load_duration'] / 1e6), prompt_tokens=done['prompt_eval_count'],
                output_tokens=done['eval_count'], done_reason=done['done_reason'],
                prefill_ms=round(done.get('prompt_eval_duration', 0) / 1e6),
                decode_ms=round(done.get('eval_duration', 0) / 1e6), thinking_seen=thinking_seen)
"""

LEASE_PROBE = r"""
import json
import os
from pathlib import Path
import subprocess

pid = subprocess.check_output(
    ['systemctl', 'show', '-p', 'MainPID', '--value', 'cretas-python'], universal_newlines=True,
).strip()
if not pid.isdecimal() or int(pid) <= 0:
    raise RuntimeError('python_service_pid_unavailable')
env = dict(item.split(b'=', 1) for item in Path(f'/proc/{pid}/environ').read_bytes().split(b'\0') if b'=' in item)
tmp = next((os.fsdecode(env[key]) for key in (b'TMPDIR', b'TEMP', b'TMP') if env.get(key)), '/tmp')
lease = Path(f'/proc/{pid}/root') / tmp.lstrip('/') / 'cretas-f006-mini-pilot/inflight.sqlite'
stat = lease.stat()
identity = (os.major(stat.st_dev), os.minor(stat.st_dev), stat.st_ino)
locked = False
for row in Path('/proc/locks').read_text().splitlines():
    for field in row.split():
        if field.count(':') == 2 and all(field.split(':')):
            dev_major, dev_minor, inode = field.split(':')
            if (int(dev_major, 16), int(dev_minor, 16), int(inode)) == identity:
                locked = True
connections = subprocess.check_output(
    ['ss', '-Htn', 'state', 'established', 'sport', '=', ':11435'], universal_newlines=True,
)
print(json.dumps({'locked': locked, 'gateway_established': len(connections.splitlines())}))
"""

REX_PROBE = r"""
import http.client
import json
from pathlib import Path
import subprocess
import time

def run_rex(payload=None):
    pid = subprocess.check_output(
        ['systemctl', 'show', '-p', 'MainPID', '--value', 'cretas-python'], universal_newlines=True,
    ).strip()
    if not pid.isdecimal() or int(pid) <= 0:
        raise RuntimeError('python_service_pid_unavailable')
    env = dict(item.split(b'=', 1) for item in Path('/proc/{}/environ'.format(pid)).read_bytes().split(b'\0')
               if b'=' in item)
    token = env.get(b'LLM_LOCAL_CANADA_API_KEY')
    url = env.get(b'LLM_LOCAL_CANADA_BASE_URL', b'http://127.0.0.1:11437/v1').rstrip(b'/')
    if not token or url != b'http://127.0.0.1:11437/v1':
        raise RuntimeError('rex_loopback_provider_unconfigured')
    connection = http.client.HTTPConnection('127.0.0.1', 11437, timeout=90)
    headers = {'Content-Type': 'application/json', 'Authorization': b'Bearer ' + token}
    start = time.monotonic()
    first = None
    parts = []
    terminal = False
    finish_reason = None
    try:
        if payload is None:
            connection.request('GET', '/v1/models', headers=headers)
            response = connection.getresponse()
            if response.status != 200:
                raise RuntimeError('rex_models_http_{}'.format(response.status))
            return [model['id'] for model in json.load(response)['data']]
        connection.request('POST', '/v1/chat/completions', json.dumps(payload), headers)
        response = connection.getresponse()
        if response.status != 200:
            raise RuntimeError('rex_http_{}'.format(response.status))
        for line in response:
            if not line.startswith(b'data:'):
                continue
            data = line[5:].strip()
            if data == b'[DONE]':
                terminal = True
                break
            frame = json.loads(data)
            if frame.get('model') != payload['model']:
                raise RuntimeError('rex_returned_unexpected_model')
            choices = frame.get('choices') or []
            if not choices:
                continue
            choice = choices[0]
            content = choice.get('delta', {}).get('content')
            if content:
                if first is None:
                    first = round((time.monotonic() - start) * 1000)
                parts.append(content)
            finish_reason = choice.get('finish_reason') or finish_reason
    finally:
        connection.close()
    if not terminal:
        raise RuntimeError('rex_no_terminal_frame')
    return dict(ms=round((time.monotonic() - start) * 1000), ttft_ms=first,
                content=''.join(parts), done_reason=finish_reason)
"""


def _ssh(host, python, source, timeout):
    result = subprocess.run(
        [*SSH, host, python, "-"], input=source.encode("utf-8"), capture_output=True, timeout=timeout,
    )
    if result.returncode:
        error = result.stderr.decode("utf-8", errors="replace").splitlines()
        raise RuntimeError(error[-1] if error else f"ssh_exit_{result.returncode}")
    return json.loads(result.stdout)


def _catalog():
    import pandas as pd
    from smartbi.services.factory_charts import conversation, engine
    from smartbi.services.factory_charts.metrics import Series

    rows = [
        (f"2026-11-{day:02}", sku, sku, stage, unit, 10.0 + day, 1.0)
        for day in (1, 2, 3)
        for sku in ("C P2026100002", "fixture-B")
        for stage in ("成品入库", "出料", "包装")
        for unit in ("KG", "盒", "袋", "件")
    ]
    frame = pd.DataFrame(rows, columns=["日期", "产品编码", "产品", "工序", "单位", "良品数", "不良数"])
    request = SimpleNamespace(guided=True, analysis_subject=None)
    charts, _ = engine.build([engine.Dataset(frame, "blacklake-production · benchmark")], request, False)
    blacklake_count = len(charts)
    assert blacklake_count == 28
    # Synthetic native-factory distractors preserve a mixed eligible catalogue
    # without loading tenant data. All 28 Blacklake variants stay present.
    distractors = [
        ("production.output", "生产产量", "生产", "KG", "line"),
        ("production.batch_count", "生产批次数", "生产", "批", "line"),
        ("production.product_mix", "产品结构", "生产", "KG", "bar_horizontal"),
        ("quality.pass_rate", "质检合格率", "质量", "%", "line"),
        ("quality.defect_count", "质检不合格数", "质量", "件", "line"),
        ("quality.inspections", "质检次数", "质量", "次", "line"),
        ("inventory.stock_balance", "库存余额", "库存", "KG", "line"),
        ("inventory.inbound", "采购入库", "库存", "KG", "line"),
        ("inventory.outbound", "库存出库", "库存", "KG", "line"),
        ("sales.quantity", "销售数量", "销售", "件", "line"),
        ("sales.orders", "销售订单数", "销售", "单", "line"),
        ("purchase.orders", "采购订单数", "采购", "单", "line"),
    ]
    for key, label, domain, unit, kind in distractors:
        chart = engine.series_candidate(Series(key, label, domain, unit, [("11-01", 10)], key.split(".")[0]), kind)
        engine._analysis_metadata(chart)
        chart["aiMetric"] = key
        charts.append(chart)
    catalog = conversation.semantic_catalog(charts)
    assert len(catalog) == len({item["id"] for item in catalog}) == 40
    return catalog


async def _planner_input(question, catalog):
    from common import llm_router
    from smartbi.services.factory_charts import conversation

    captured = {}

    async def capture(slot, payload, *, content_validator, **kwargs):
        assert slot == llm_router.SLOT.CHART
        sent = json.loads(payload["messages"][1]["content"])["catalog"]
        assert len(sent) == len(catalog) == 40
        candidate = sent[0]["id"]
        accepted = json.dumps({"selected_ids": [candidate]})
        assert content_validator(accepted) is None
        assert content_validator('{"selected_ids":["outside-catalog"]}') is not None
        captured.update(payload=payload, validate=content_validator)
        return {"choices": [{"message": {"content": accepted}}]}

    with patch.object(llm_router, "call_chain", capture):
        selected, result = await conversation.plan(question, catalog)
    if not selected or result["mode"] != "ai":
        raise RuntimeError(f"planner fixture did not reach chart for {question!r}")
    return captured["payload"], captured["validate"]


def _summary(rows):
    def stats(key):
        values = sorted(row[key] for row in rows if row.get(key) is not None)
        assert values
        return round(statistics.median(values)), values[math.ceil(len(values) * 0.95) - 1]

    p50, p95 = stats("ms")
    ttft_p50, ttft_p95 = stats("ttft_ms")
    valid = [row for row in rows if row["valid"] and row["done_reason"] == "stop" and row["ttft_ms"] is not None]
    return dict(model=rows[0]["model"], n=len(rows), p50_ms=p50, p95_ms=p95,
                ttft_p50_ms=ttft_p50, ttft_p95_ms=ttft_p95,
                validator_pass_rate=sum(row["valid"] for row in rows) / len(rows),
                complete_and_valid=len(valid),
                legal_json=sum(row["output_legal"] for row in rows),
                valid_within_6s=sum(row["ms"] <= 6000 for row in valid),
                valid_within_12s=sum(row["ms"] <= 12000 for row in valid),
                valid_within_60s=sum(row["ms"] <= 60000 for row in valid))


def main():
    sys.path[:0] = [str(PYTHON), str(PYTHON / "smartbi")]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", choices=MODELS, action="append")
    target = parser.add_mutually_exclusive_group()
    target.add_argument("--rex", action="store_true", help="use the existing 47 loopback bridge")
    target.add_argument("--rex-direct", action="store_true", help="use Rex native Ollama loopback")
    target.add_argument("--rex-list", action="store_true", help="read the existing 47 bridge model list")
    parser.add_argument("--check-fixture", action="store_true")
    parser.add_argument("--num-ctx", type=int, help="set the native request context size")
    parser.add_argument("--num-gpu", type=int, help="limit native Ollama GPU layers")
    parser.add_argument("--num-batch", type=int, help="limit the native Ollama prompt batch")
    parser.add_argument("--warm", action="store_true", help="retain only the benchmark model between questions")
    parser.add_argument("--stable-prefix", action="store_true", help="place the unchanged candidate list first")
    parser.add_argument("--num-predict", type=int, default=900)
    args = parser.parse_args()
    if args.num_ctx is not None and (args.rex or args.num_ctx < 4608):
        parser.error("--num-ctx requires native Ollama and at least 4608 tokens")
    if args.num_gpu is not None and (args.rex or args.num_gpu < 0):
        parser.error("--num-gpu requires native Ollama and a nonnegative layer count")
    if args.num_batch is not None and (args.rex or args.num_batch < 1):
        parser.error("--num-batch requires native Ollama and a positive batch size")
    if args.warm and (args.rex or args.rex_direct):
        parser.error("--warm only applies to models loaded by this benchmark on Aurora")
    if not 1 <= args.num_predict <= 900:
        parser.error("--num-predict must be between 1 and 900")
    sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
    bridge = ("root@47.100.235.168", "/usr/bin/python3")
    if args.rex_list:
        print(json.dumps({"proxy_models": _ssh(*bridge, REX_PROBE + "\nprint(json.dumps(run_rex()))", 20)}))
        return
    machine = "rex" if args.rex or args.rex_direct else "aurora"
    mini = ("canada-mini-pomelox" if machine == "rex" else "aurora-mini-pomelox", "/opt/homebrew/bin/python3")
    question_lines = (ROOT / "docs/smartbi/liushanmen-blacklake/questions.md").read_text(encoding="utf-8").splitlines()
    questions = [line.split("|", 2)[1].strip() for line in question_lines if line.startswith("| 11 月")]
    assert len(questions) == len(set(questions)) == 12
    catalog = _catalog()
    cases = [asyncio.run(_planner_input(question, catalog)) for question in questions]
    print(json.dumps({"catalog_size": len(catalog), "questions": len(cases), "think": False,
                      "route": "47:11437" if args.rex else f"{machine}:11434",
                      "num_ctx": args.num_ctx,
                      "num_gpu": args.num_gpu,
                      "num_batch": args.num_batch,
                      "num_predict": args.num_predict, "stable_prefix": args.stable_prefix,
                      "keep_alive": "existing_policy" if machine == "rex" else WARM_KEEP_ALIVE if args.warm else 0}))
    if args.check_fixture:
        return

    def observe(model, stage):
        reading = _ssh(*mini, AURORA_PROBE + "\nprint(json.dumps(machine_state()))", 18)
        print(json.dumps({"machine": machine, "model": model, "stage": stage, **reading}))
        if machine == "rex" and reading["workers"]:
            raise RuntimeError("ci_runner_busy")
        residents = reading["resident_models"]
        if stage.startswith("before") and residents and (machine == "aurora" or residents != [model]):
            raise RuntimeError("other_or_protected_model_resident")
        return residents

    defaults = ("qwen3:8b",) if machine == "rex" else MODELS[:2]
    if args.rex:
        proxy_models = _ssh(*bridge, REX_PROBE + "\nprint(json.dumps(run_rex()))", 20)
        if any(model not in proxy_models for model in args.model or defaults):
            raise RuntimeError(f"rex_proxy_model_not_advertised: {proxy_models}")
    for model in args.model or defaults:
        if machine == "aurora" and model == "qwen3.8:27b":
            parser.error("27B benchmark is Rex-only in round two")
        originally_resident = observe(model, "before")
        reload_options = (args.num_ctx, args.num_gpu, args.num_batch)
        if args.rex_direct and originally_resident and any(value is not None for value in reload_options):
            raise RuntimeError("cannot_reconfigure_protected_resident_model")
        rows = []
        for index, (payload, validator) in enumerate(cases, 1):
            messages = payload["messages"]
            if args.stable_prefix:
                user = json.loads(messages[1]["content"])
                stable = {"catalog": user["catalog"], "history": user["history"], "question": user["question"]}
                messages = [messages[0], {"role": "user", "content": json.dumps(stable, ensure_ascii=False)}]
            if args.rex:
                observe(model, "before_question")
                inference = dict(model=model, messages=messages, stream=True,
                                 response_format={"type": "json_object"}, reasoning_effort="none",
                                 temperature=0, seed=1234, max_tokens=args.num_predict)
                reading = _ssh(*bridge, f"{REX_PROBE}\nprint(json.dumps(run_rex({inference!r})))", 110)
                observe(model, "after_question")
            else:
                if machine == "aurora":
                    lease = _ssh(*bridge, LEASE_PROBE, 15)
                    if lease != {"locked": False, "gateway_established": 0}:
                        raise RuntimeError(f"F006 busy: {lease}")
                options = dict(temperature=0, seed=1234, num_predict=args.num_predict)
                if args.num_ctx is not None:
                    options["num_ctx"] = args.num_ctx
                timeout = 90
                if args.num_gpu is not None:
                    options["num_gpu"] = args.num_gpu
                    timeout = 240
                if args.num_batch is not None:
                    options["num_batch"] = args.num_batch
                    timeout = 240
                inference = dict(model=model, messages=messages, stream=True, format="json", think=False,
                                 options=options)
                if machine == "aurora":
                    inference["keep_alive"] = WARM_KEEP_ALIVE if args.warm and index < len(cases) else 0
                elif not originally_resident:
                    inference["keep_alive"] = 0
                allow_resident = args.warm or args.rex_direct
                reading = _ssh(*mini, f"{AURORA_PROBE}\nprint(json.dumps(run_probe({inference!r}, {timeout}, "
                               f"warm={allow_resident!r}, rex={args.rex_direct!r})))", timeout + 20)
            content = reading.pop("content")
            error = validator(content)
            try:
                parsed = json.loads(content)
                legal = isinstance(parsed, dict) and isinstance(parsed.get("selected_ids"), list)
            except ValueError:
                legal = False
            row = dict(model=model, question=index, valid=error is None, output_legal=legal,
                       validator_error=error, **reading)
            rows.append(row)
            print(json.dumps(row, ensure_ascii=False))
        observe(model, "after")
        print(json.dumps({"summary": {"machine": machine, **_summary(rows)}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
