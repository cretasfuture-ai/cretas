#!/usr/bin/env python3
"""上线后自动实测关 (AI 意图两入口 + F006 模拟写入真跑)。

背景: #1706 离线测试全绿, 上线后两个入口都没生效, 是人手实测才发现的。自动优化器以后每周
自动上线, 必须有一道自动的线上实测关 (INTENT-DISTILL-20261111 方案第 7 节一级第 3 条)。

做什么
  1. 只读话术 (cases.json): 普通入口 POST /api/mobile/F006/ai-intents/execute 与流式
     /execute/stream 各跑一遍, 逐轮断言状态 / 意图 / 关键文案。账号 = F006 只读评测号
     (FACTORY_EVAL_USERNAME/PASSWORD, 服务器 .env.prod)。
  2. 模拟写入真跑 (--write, 默认开): 用 F006 QA 写账号走完一条 AI 写操作全流程
     「说话 → 写确认 → /preview 预览 → 服务端一次性令牌走正规 /confirm → 只读回查落库
       → 按业务正规删除路径清理」, 写前写后计数 + 阴性对照 (未确认不落库、假令牌被拒、
       令牌不可重放)。写意图 = WECHAT_CREATE (补录微信记录, 内部备注), 理由见 WRITE_CHOICE。
  3. 版本: 从线上读 (经 ssh): 活动槽端口、运行 jar 的 sha256、Python 最近一次发布提交与启动时刻。

退出码 (三态, measurement-and-wiring 硬约束 4)
  0 = 全过 (EXPECTED_PENDING / PENDING_LIFTED 不算失败)
  1 = 有失败 (含清理失败)
  2 = 没量到 (登录失败 / 租户不是 F006 / 服务不可达 / 0 条执行)

凭证 ⛔ 不打印、不落盘到结果文件、不进 git:
  只读号: AI_PROBE_READ_USERNAME/PASSWORD → FACTORY_EVAL_USERNAME/PASSWORD → (--server) ssh 读服务器 .env.prod
  写入号: AI_PROBE_WRITE_USERNAME/PASSWORD → --env-file (默认 ~/.cretas/ai-live-probe.env)

用法
  python3 scripts/ai-live-probe/ai_live_probe.py                       # 全套 (读 + 写), 结果写 ~/.cretas/ai-live-probe-results/
  python3 scripts/ai-live-probe/ai_live_probe.py --no-write            # 只跑只读话术
  python3 scripts/ai-live-probe/ai_live_probe.py --only inventory      # 只跑 id 含 inventory 的 case
  python3 scripts/ai-live-probe/ai_live_probe.py --base http://127.0.0.1:1   # 构造 rc=2 (服务不可达)
"""
from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from typing import Any, Dict, List, Optional, Tuple

FACTORY_ID = "F006"  # ⛔ 唯一允许的租户; LIUSHANMEN 是真客户
DEFAULT_BASE = "https://admin.cretaceousfuture.com"
DEFAULT_SERVER = "root@47.100.235.168"
PREFIX = "QA-PROBE-"
HERE = os.path.dirname(os.path.abspath(__file__))

WRITE_INTENT = "WECHAT_CREATE"
WRITE_CHOICE = (
    "WECHAT_CREATE (补录微信记录, direction=INTERNAL 内部备注): 只新建一行 wechat_record, "
    "不动库存/金额/状态机/审批, 不发通知 (WechatRecordService.create 仅 save); "
    "正文带 QA-PROBE- 前缀, 挂在 F006 已有的 QA- 客户上; "
    "有正规删除路径 DELETE /api/mobile/F006/customer/{cid}/wechat-record/{id} 与只读回查 GET 列表。"
)

PASS, FAIL, PENDING, LIFTED = "PASS", "FAIL", "EXPECTED_PENDING", "PENDING_LIFTED"


class NotMeasured(Exception):
    """仪器没量到 (rc=2), 与断言失败 (rc=1) 分开。"""


# ───────────────────────────── HTTP ─────────────────────────────

class Http:
    def __init__(self, base: str, timeout: int = 180):
        self.base = base.rstrip("/")
        self.timeout = timeout
        self.calls = 0
        self.retries = 0

    def _req(self, method: str, path: str, body: Any = None, token: Optional[str] = None,
             headers: Optional[Dict[str, str]] = None, retry: bool = True) -> Tuple[int, str]:
        """传输层错误 (TLS EOF / 连接重置) 重试一次 —— 2026-11-14 首跑在网关上实测撞到过一次
        SSLEOFError。⛔ 真正的 /confirm 写调用传 retry=False: 结果不明时由回查判定, 不盲目重发。"""
        h = {"Content-Type": "application/json", "User-Agent": "cretas-ai-live-probe/1"}
        if token:
            h["Authorization"] = "Bearer " + token
        h.update(headers or {})
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        attempts = 2 if retry else 1
        for attempt in range(1, attempts + 1):
            req = urllib.request.Request(self.base + path, data=data, headers=h, method=method)
            self.calls += 1
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    return resp.status, resp.read().decode("utf-8", "replace")
            except urllib.error.HTTPError as exc:
                return exc.code, exc.read().decode("utf-8", "replace")
            except (urllib.error.URLError, ConnectionError, TimeoutError, OSError):
                if attempt == attempts:
                    raise
                self.retries += 1
                time.sleep(2)
        raise AssertionError("unreachable")

    def json(self, method: str, path: str, body: Any = None, token: Optional[str] = None,
             headers: Optional[Dict[str, str]] = None, retry: bool = True) -> Tuple[int, Dict[str, Any]]:
        code, raw = self._req(method, path, body, token, headers, retry)
        try:
            parsed = json.loads(raw) if raw else {}
        except ValueError:
            parsed = {"_unparsed": raw[:300]}
        return code, parsed if isinstance(parsed, dict) else {"_value": parsed}

    def sse(self, path: str, body: Any, token: str) -> Tuple[int, List[Dict[str, Any]]]:
        code, raw = self._req("POST", path, body, token, {"Accept": "text/event-stream"})
        events, cur = [], {}
        for line in raw.splitlines():
            if line.startswith("event:"):
                cur["event"] = line[6:].strip()
            elif line.startswith("data:"):
                payload = line[5:].strip()
                try:
                    cur["data"] = json.loads(payload)
                except ValueError:
                    cur["data"] = {"_raw": payload[:200]}
            elif not line.strip() and cur:
                events.append(cur)
                cur = {}
        if cur:
            events.append(cur)
        return code, events


def login(http: Http, username: str, password: str, label: str) -> Dict[str, Any]:
    try:
        code, env = http.json("POST", "/api/mobile/auth/unified-login",
                              {"username": username, "password": password, "clientType": "web"})
    except Exception as exc:  # noqa: BLE001 — 网络不可达 = 没量到
        raise NotMeasured(f"{label} 登录请求失败 (服务不可达?): {type(exc).__name__}: {exc}")
    data = env.get("data") or {}
    token = data.get("accessToken") or data.get("token") or (data.get("tokens") or {}).get("accessToken")
    if code != 200 or not token:
        raise NotMeasured(f"{label} 登录失败 http={code} (账号={username}) —— 没量到, 不是断言失败")
    factory = data.get("factoryId")
    if factory != FACTORY_ID:
        # 租户证明来自实时登录响应; 不是 F006 就停, 后面一切读数都量错了对象。
        raise NotMeasured(f"{label} 账号 {username} 属于租户 {factory}, 不是 {FACTORY_ID} —— 停止")
    return {"token": token, "factoryId": factory, "userId": data.get("userId"),
            "username": data.get("username"), "role": data.get("role")}


# ───────────────────────────── 凭证 ─────────────────────────────

def read_env_file(path: str) -> Dict[str, str]:
    out: Dict[str, str] = {}
    if not path or not os.path.isfile(path):
        return out
    with open(path, "r", encoding="utf-8", newline="") as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                out[key.strip()] = value.strip()
    return out


def ssh_run(server: str, command: str, timeout: int = 40) -> Optional[str]:
    if not server:
        return None
    try:
        proc = subprocess.run(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", server, command],
                              capture_output=True, timeout=timeout)
    except Exception:  # noqa: BLE001
        return None
    if proc.returncode != 0:
        return None
    return proc.stdout.decode("utf-8", "replace")


def resolve_read_creds(server: str) -> Tuple[Optional[str], Optional[str], str]:
    for u_key, p_key in (("AI_PROBE_READ_USERNAME", "AI_PROBE_READ_PASSWORD"),
                         ("FACTORY_EVAL_USERNAME", "FACTORY_EVAL_PASSWORD")):
        if os.environ.get(u_key) and os.environ.get(p_key):
            return os.environ[u_key], os.environ[p_key], "env:" + u_key
    out = ssh_run(server, "grep -E '^FACTORY_EVAL_(USERNAME|PASSWORD)=' /www/wwwroot/cretas/.env.prod")
    if out:
        kv = dict(line.split("=", 1) for line in out.splitlines() if "=" in line)
        if kv.get("FACTORY_EVAL_USERNAME") and kv.get("FACTORY_EVAL_PASSWORD"):
            return kv["FACTORY_EVAL_USERNAME"], kv["FACTORY_EVAL_PASSWORD"], "ssh:.env.prod"
    return None, None, "missing"


def resolve_write_creds(env_file: str) -> Tuple[Optional[str], Optional[str], str]:
    if os.environ.get("AI_PROBE_WRITE_USERNAME") and os.environ.get("AI_PROBE_WRITE_PASSWORD"):
        return os.environ["AI_PROBE_WRITE_USERNAME"], os.environ["AI_PROBE_WRITE_PASSWORD"], "env"
    kv = read_env_file(env_file)
    if kv.get("AI_PROBE_WRITE_USERNAME") and kv.get("AI_PROBE_WRITE_PASSWORD"):
        return kv["AI_PROBE_WRITE_USERNAME"], kv["AI_PROBE_WRITE_PASSWORD"], "file"
    return None, None, "missing"


# ───────────────────────────── 版本 ─────────────────────────────

VERSION_SCRIPT = r"""
set -u
up=/www/server/panel/vhost/nginx/_upstream_cretas.conf
port=$(grep -oE 'server 127\.0\.0\.1:[0-9]+' "$up" 2>/dev/null | head -1 | sed 's/.*://')
echo "java_active_port=${port:-}"
pid=$(ss -ltnpH "sport = :${port:-0}" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2)
echo "java_pid=${pid:-}"
if [ -n "${pid:-}" ]; then
  jar=$(tr '\0' '\n' </proc/$pid/cmdline | grep -E '\.jar$' | head -1)
  case "$jar" in /*) ;; *) jar="$(readlink /proc/$pid/cwd)/$jar";; esac
  echo "java_jar=$jar"
  echo "java_jar_sha256=$(sha256sum "/proc/$pid/cwd/$(basename "$jar")" 2>/dev/null | cut -c1-64)"
  echo "java_started=$(ps -o lstart= -p $pid 2>/dev/null)"
fi
echo "python_main_pid=$(systemctl show cretas-python -p MainPID --value 2>/dev/null)"
echo "python_started=$(systemctl show cretas-python -p ExecMainStartTimestamp --value 2>/dev/null)"
latest=$(ls -1t /www/wwwroot/cretas/code/.release-systemd 2>/dev/null | head -1)
echo "python_release_commit=${latest:-}"
echo "python_health=$(curl -s -m 8 http://127.0.0.1:8083/health | head -c 200 | tr -d '\n')"
"""


def read_versions(http: Http, server: str) -> Dict[str, Any]:
    versions: Dict[str, Any] = {"source": {}}
    try:
        code, health = http.json("GET", "/api/mobile/health")
        versions["java_public_health"] = {"http": code, "status": health.get("status"),
                                          "appMinVersion": health.get("appMinVersion")}
    except Exception as exc:  # noqa: BLE001
        versions["java_public_health"] = {"error": type(exc).__name__}
    out = ssh_run(server, VERSION_SCRIPT)
    if out is None:
        versions["source"]["server"] = f"unavailable (ssh {server or '-'} 失败或未配置) —— 版本没量到"
        return versions
    versions["source"]["server"] = f"ssh {server}"
    for line in out.splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            versions[key.strip()] = value.strip()
    health = versions.get("python_health") or ""
    match = re.search(r'"version"\s*:\s*"([^"]+)"', health)
    versions["python_service_version"] = match.group(1) if match else None
    versions["python_release_commit_note"] = "最近一次 Python 发布写入的 systemd 包目录名 (按 mtime), 即该次发布的 exact commit"
    versions.pop("python_health", None)
    return versions


# ───────────────────────────── 单轮执行 ─────────────────────────────

def turn_execute(http: Http, token: str, text: str, session_id: Optional[str]) -> Dict[str, Any]:
    body: Dict[str, Any] = {"userInput": text}
    if session_id:
        body["sessionId"] = session_id
    started = time.time()
    code, env = http.json("POST", f"/api/mobile/{FACTORY_ID}/ai-intents/execute", body, token)
    data = env.get("data") if isinstance(env.get("data"), dict) else {}
    meta = data.get("metadata") if isinstance(data.get("metadata"), dict) else {}
    return {"http": code, "envelope_success": env.get("success"), "data": data,
            "session_id": data.get("sessionId") or meta.get("sessionId"),
            "latency_s": round(time.time() - started, 2)}


def turn_stream(http: Http, token: str, text: str, session_id: Optional[str]) -> Dict[str, Any]:
    body: Dict[str, Any] = {"userInput": text}
    if session_id:
        body["sessionId"] = session_id
    started = time.time()
    code, events = http.sse(f"/api/mobile/{FACTORY_ID}/ai-intents/execute/stream", body, token)
    result: Dict[str, Any] = {}
    sid = None
    names = []
    for ev in events:
        names.append(ev.get("event"))
        payload = ev.get("data") if isinstance(ev.get("data"), dict) else {}
        if ev.get("event") == "result":
            result = payload
        meta = payload.get("metadata") if isinstance(payload.get("metadata"), dict) else {}
        sid = payload.get("sessionId") or meta.get("sessionId") or sid
    if result and not result.get("message") and result.get("formattedText"):
        result = dict(result, message=result.get("formattedText"))
    elif result and result.get("formattedText") and result.get("message") == "操作已完成。":
        # 流式 result 的 message 常是固定「操作已完成。」, 真正给用户看的是 formattedText
        result = dict(result, message=result.get("formattedText"))
    return {"http": code, "envelope_success": bool(result), "data": result, "session_id": sid,
            "events": names, "latency_s": round(time.time() - started, 2)}


def choice_intents(data: Dict[str, Any]) -> List[str]:
    out = []
    for choice in data.get("suggestedActions") or []:
        if isinstance(choice, dict) and choice.get("actionCode") == "SELECT_INTENT":
            code = (choice.get("parameters") or {}).get("intentCode")
            if code:
                out.append(code)
    return out


def check_turn(expect: Dict[str, Any], resp: Dict[str, Any], prev: Optional[Dict[str, Any]]) -> List[str]:
    problems: List[str] = []
    data = resp["data"]
    if resp["http"] != 200 or not data:
        return [f"无有效响应 http={resp['http']}"]
    status, intent = data.get("status"), data.get("intentCode")
    message = str(data.get("message") or "")
    if "status_in" in expect and status not in expect["status_in"]:
        problems.append(f"status={status} 不在 {expect['status_in']}")
    if "intent" in expect and intent != expect["intent"]:
        problems.append(f"intent={intent} 应为 {expect['intent']}")
    if "intent_in" in expect and intent not in expect["intent_in"]:
        problems.append(f"intent={intent} 不在 {expect['intent_in']}")
    if intent in expect.get("intent_not", []):
        problems.append(f"intent={intent} 不应出现")
    if "ai_mode" in expect and data.get("aiMode") not in (None, expect["ai_mode"]):
        problems.append(f"aiMode={data.get('aiMode')} 应为 {expect['ai_mode']}")
    for word in expect.get("message_contains", []):
        if word not in message:
            problems.append(f"文案缺「{word}」")
    for word in expect.get("message_not_contains", []):
        if word in message:
            problems.append(f"文案不应含「{word}」")
    if expect.get("min_choices") and len(set(choice_intents(data))) < expect["min_choices"]:
        problems.append(f"澄清候选 {len(set(choice_intents(data)))} 个 < {expect['min_choices']}")
    if expect.get("need_session_id") and not resp.get("session_id"):
        problems.append("澄清轮没回 sessionId (下一句无从对号)")
    if expect.get("intent_from_choice"):
        options = choice_intents((prev or {}).get("data") or {})
        idx = expect["intent_from_choice"] - 1
        want = options[idx] if 0 <= idx < len(options) else None
        if not want or intent != want:
            problems.append(f"回「{expect['intent_from_choice']}」应选中 {want}, 实为 {intent}")
    if expect.get("not_empty_write_card") and status == "WRITE_CONFIRM_REQUIRED":
        meta = data.get("metadata") if isinstance(data.get("metadata"), dict) else {}
        if not data.get("confirmableAction") and not meta.get("previewParams"):
            problems.append("第 1 轮直接出空确认卡 (没列工序、没带参数)")
    return problems


def summarize(resp: Dict[str, Any]) -> Dict[str, Any]:
    data = resp.get("data") or {}
    return {"http": resp.get("http"), "status": data.get("status"), "intent": data.get("intentCode"),
            "aiMode": data.get("aiMode"), "choices": choice_intents(data) or None,
            "message": " ".join(str(data.get("message") or "").split())[:200],
            "latency_s": resp.get("latency_s")}


def run_case(http: Http, token: str, case: Dict[str, Any], entry: str) -> Dict[str, Any]:
    fn = turn_execute if entry == "execute" else turn_stream
    session_id = "ai-live-probe-" + uuid.uuid4().hex[:12]
    turns, problems, prev = [], [], None
    for index, turn in enumerate(case["turns"], 1):
        try:
            resp = fn(http, token, turn["say"], session_id)
        except Exception as exc:  # noqa: BLE001
            resp = {"http": None, "data": {}, "error": f"{type(exc).__name__}: {exc}"}
        if resp.get("session_id"):
            session_id = resp["session_id"]
        issues = check_turn(turn.get("expect") or {}, resp, prev)
        turns.append(dict(summarize(resp), say=turn["say"], problems=issues or None))
        problems += [f"第{index}轮「{turn['say']}」: {p}" for p in issues]
        prev = resp
        if resp.get("http") is None:
            break
    pending = (case.get("pending") or {}).get(entry) or (case.get("pending") or {}).get("*")
    if problems:
        status = PENDING if pending else FAIL
    else:
        status = LIFTED if pending else PASS
    return {"type": "case", "case": case["id"], "title": case.get("title"), "entry": entry,
            "result": status, "pending_reason": pending, "problems": problems or None,
            "final_intent": turns[-1]["intent"] if turns else None, "turns": turns}


# ───────────────────────────── 写入真跑 ─────────────────────────────

def _wechat_list(http: Http, token: str, customer_id: str) -> List[Dict[str, Any]]:
    code, env = http.json("GET", f"/api/mobile/{FACTORY_ID}/customer/{customer_id}/wechat-record?size=100", None, token)
    data = env.get("data") if isinstance(env.get("data"), dict) else None
    if code != 200 or env.get("success") is not True or data is None:
        raise NotMeasured(f"微信记录只读回查失败 http={code}")
    if int(data.get("totalElements") or 0) > 100:
        raise NotMeasured("该客户微信记录 > 100 条, 单页回查会截断 —— 换一个 QA 客户")
    return [row for row in data.get("content") or [] if isinstance(row, dict)]


def _probe_rows(rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    return [r for r in rows if str(r.get("messageContent") or "").startswith(PREFIX)]


def pick_customer(http: Http, token: str) -> Dict[str, Any]:
    code, env = http.json("GET", f"/api/mobile/{FACTORY_ID}/customers?page=1&size=100", None, token)
    data = env.get("data")
    items = data.get("content") if isinstance(data, dict) else data
    if code != 200 or not isinstance(items, list):
        raise NotMeasured(f"读 F006 客户列表失败 http={code}")
    qa = [c for c in items if isinstance(c, dict) and c.get("factoryId") == FACTORY_ID
          and str(c.get("name") or "").startswith(("QA-PROBE-", "QA-"))]
    if not qa:
        raise NotMeasured("F006 没有 QA- 前缀的客户可挂测试备注")
    qa.sort(key=lambda c: (0 if str(c.get("name")).startswith("QA-PROBE-") else 1, str(c.get("name"))))
    return qa[0]


def run_write(http: Http, writer: Dict[str, Any], run_id: str, rec: Dict[str, Any]) -> Dict[str, Any]:
    """rec 由调用方传入: 中途 NotMeasured 时调用方仍能把已做的步骤 (含清理读数) 落进结果文件。"""
    token = writer["token"]
    rec.update({"type": "write", "case": "write_wechat_create_confirm_cleanup", "entry": "stream+preview+confirm",
                           "intent": WRITE_INTENT, "why": WRITE_CHOICE, "account": writer.get("username"),
                           "steps": [], "problems": [], "result": FAIL})
    problems: List[str] = rec["problems"]

    def step(name: str, ok: bool, **kw: Any) -> bool:
        rec["steps"].append(dict(step=name, ok=ok, **kw))
        if not ok:
            problems.append(name + (": " + kw["detail"] if kw.get("detail") else ""))
        return ok

    customer = pick_customer(http, token)
    cid, cname = customer["id"], customer.get("name")
    rec["customer"] = {"id": cid, "name": cname, "factoryId": customer.get("factoryId")}

    # 先收掉以前崩掉的 QA-PROBE- 残留 (只删 QA-PROBE- 开头的, 走正规删除路径)
    leftovers = _probe_rows(_wechat_list(http, token, cid))
    for row in leftovers:
        code, env = http.json("DELETE", f"/api/mobile/{FACTORY_ID}/customer/{cid}/wechat-record/{row['id']}", None, token)
        step("leftover_cleanup", code == 200 and env.get("success") is True, record_id=row["id"], http=code)

    before = len(_wechat_list(http, token, cid))
    rec["count_before"] = before
    marker = f"{PREFIX}{run_id}"
    text = f"补录一条微信内部备注，客户编号{cid}，消息内容：{marker} 实测关写入验证"
    rec["utterance"] = text

    def flow() -> None:
        # 1) 说话 (流式入口, 与 App 同一条)
        stream = turn_stream(http, token, text, "ai-live-probe-w-" + uuid.uuid4().hex[:10])
        sdata = stream["data"]
        params = ((sdata.get("metadata") or {}) if isinstance(sdata.get("metadata"), dict) else {}).get("previewParams") or {}
        ok = (sdata.get("intentCode") == WRITE_INTENT and sdata.get("status") in ("WRITE_CONFIRM_REQUIRED", "PREVIEW"))
        step("say → 写确认", ok, status=sdata.get("status"), intent=sdata.get("intentCode"),
             detail=None if ok else f"status={sdata.get('status')} intent={sdata.get('intentCode')}")
        bound_ok = params.get("customerId") == cid and marker in str(params.get("messageContent") or "")
        step("参数绑定到预期 QA 客户 + 标记", bound_ok, previewParams={k: params.get(k) for k in ("customerId", "direction", "messageContent")},
             detail=None if bound_ok else "抽出的参数不是本次 QA 客户/标记, ⛔ 不确认")
        after_say = len(_wechat_list(http, token, cid))
        step("阴性对照①: 说完未确认不落库", after_say == before, count=after_say)
        if not (ok and bound_ok and after_say == before):
            return

        # 2) 预览 → 一次性确认令牌 (与 App 同: 原话 + 被拦意图 + 会话 + previewParams)
        pbody = {"userInput": text, "intentCode": WRITE_INTENT, "sessionId": stream.get("session_id"), "context": params}
        code, env = http.json("POST", f"/api/mobile/{FACTORY_ID}/ai-intents/preview", pbody, token)
        pdata = env.get("data") if isinstance(env.get("data"), dict) else {}
        action = pdata.get("confirmableAction") if isinstance(pdata.get("confirmableAction"), dict) else {}
        preview = ((action.get("previewData") or {}).get("data") or {}) if isinstance(action.get("previewData"), dict) else {}
        ok = (code == 200 and pdata.get("status") == "PREVIEW" and all(action.get(k) for k in ("confirmToken", "commandDigest", "expiresAt"))
              and preview.get("canDo") is True and preview.get("customerName") == cname)
        step("预览卡 + 一次性令牌", ok, http=code, status=pdata.get("status"), canDo=preview.get("canDo"),
             customerName=preview.get("customerName"), card=preview.get("message"),
             detail=None if ok else "预览没给出可确认、绑定到本 QA 客户的令牌")
        after_preview = len(_wechat_list(http, token, cid))
        step("阴性对照②: 预览后未确认不落库", after_preview == before, count=after_preview)
        if not ok or after_preview != before:
            return

        def confirm(tok: str, retry: bool) -> Tuple[Optional[int], Dict[str, Any]]:
            body = {"commandDigest": action["commandDigest"], "expiresAt": action["expiresAt"],
                    "requestId": "probe-" + uuid.uuid4().hex[:16], "idempotencyKey": "probe-" + uuid.uuid4().hex[:16]}
            try:
                return http.json("POST", f"/api/mobile/{FACTORY_ID}/ai-intents/confirm", body, token,
                                 {"X-Cretas-Confirmation-Token": tok}, retry=retry)
            except Exception as exc:  # noqa: BLE001 — 结果不明: 交给回查判定, ⛔ 不当成「没写」
                return None, {"_transport_error": f"{type(exc).__name__}: {exc}"}

        # 3) 阴性对照③: 假令牌必须被拒, 不落库
        code, env = confirm("probe-forged-" + uuid.uuid4().hex, retry=True)
        # 必须真拿到一个拒绝答复; 传输失败 = 没量到这道对照, 记失败而不是记「被拒」
        forged_rejected = code is not None and not (
            env.get("success") is True and ((env.get("data") or {}).get("status") in ("SUCCESS", "COMPLETED")))
        after_forged = len(_wechat_list(http, token, cid))
        step("阴性对照③: 伪造令牌被拒且不落库", forged_rejected and after_forged == before, http=code,
             message=str(env.get("message") or "")[:120], count=after_forged)

        # 4) 正规确认 → 写入
        created_id = None
        code, env = confirm(action["confirmToken"], retry=False)
        cdata = env.get("data") if isinstance(env.get("data"), dict) else {}
        ok = code == 200 and env.get("success") is True and cdata.get("status") in ("SUCCESS", "COMPLETED")
        step("正规 /confirm 写入", ok, http=code, status=cdata.get("status"),
             message=" ".join(str(cdata.get("message") or env.get("message") or "").split())[:160],
             detail=env.get("_transport_error"))

        # 5) 只读回查 (必然发生的读数: 计数 +1 且能按标记找到那一行)
        rows = _wechat_list(http, token, cid)
        mine = [r for r in rows if marker in str(r.get("messageContent") or "")]
        created_id = mine[0]["id"] if len(mine) == 1 else None
        rec["count_after_confirm"] = len(rows)
        step("回查: 计数 +1 且按标记找到 1 行", len(rows) == before + 1 and created_id is not None,
             count=len(rows), record_id=created_id,
             fields={k: (mine[0].get(k) if mine else None) for k in ("direction", "customerId", "factoryId")})
        if mine:
            row = mine[0]
            fields_ok = (row.get("direction") == "INTERNAL" and row.get("customerId") == cid
                         and row.get("factoryId") in (None, FACTORY_ID))
            step("回查: 落库字段正确 (INTERNAL / 本 QA 客户 / F006)", fields_ok)

        # 6) 令牌不可重放
        # 重放本身就是在验证「不会二次执行」, 传输层失败时重试一次是安全的
        code, env = confirm(action["confirmToken"], retry=True)
        replay_rejected = code is not None and not (
            env.get("success") is True and ((env.get("data") or {}).get("status") in ("SUCCESS", "COMPLETED")))
        after_replay = len(_wechat_list(http, token, cid))
        step("阴性对照④: 同一令牌重放被拒且不重复落库", replay_rejected and after_replay == before + 1,
             http=code, count=after_replay, message=str(env.get("message") or "")[:120],
             detail=env.get("_transport_error"))

    try:
        flow()
    finally:
        # 7) 清理: 正规删除路径; 不论上面哪步失败, 只要按标记找得到就删
        try:
            rows = _wechat_list(http, token, cid)
            targets = [r["id"] for r in rows if marker in str(r.get("messageContent") or "")]
            for rid in targets:
                code, env = http.json("DELETE", f"/api/mobile/{FACTORY_ID}/customer/{cid}/wechat-record/{rid}", None, token)
                step("清理: DELETE 本次 QA-PROBE 记录", code == 200 and env.get("success") is True, record_id=rid, http=code)
            final_rows = _wechat_list(http, token, cid)
            rec["count_after_cleanup"] = len(final_rows)
            gone = not any(marker in str(r.get("messageContent") or "") for r in final_rows)
            step("清理后回查: 计数回到写前且标记行已消失", len(final_rows) == before and gone, count=len(final_rows))
        except NotMeasured as exc:
            step("清理后回查", False, detail=str(exc))
    rec["result"] = FAIL if problems else PASS
    return rec


# ───────────────────────────── 主流程 ─────────────────────────────

def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description="AI 意图上线后自动实测关 (F006)")
    ap.add_argument("--base", default=os.environ.get("AI_PROBE_BASE", DEFAULT_BASE))
    ap.add_argument("--server", default=os.environ.get("AI_PROBE_SERVER", DEFAULT_SERVER),
                    help="读版本与评测号凭证用的 ssh 目标; 传空串则不 ssh")
    ap.add_argument("--cases", default=os.path.join(HERE, "cases.json"))
    ap.add_argument("--only", default="", help="只跑 id 含该子串的 case")
    ap.add_argument("--no-write", action="store_true", help="跳过模拟写入真跑")
    ap.add_argument("--env-file", default=os.environ.get("AI_PROBE_ENV_FILE", os.path.expanduser("~/.cretas/ai-live-probe.env")))
    ap.add_argument("--out-dir", default=os.environ.get("AI_PROBE_OUT_DIR", os.path.expanduser("~/.cretas/ai-live-probe-results")))
    ap.add_argument("--trigger", default=os.environ.get("AI_PROBE_TRIGGER", "manual"), help="谁触发的 (manual / release-cretas / deploy-smartbi-python)")
    ap.add_argument("--expect-python-commit", default="", help="发布脚本传入: 线上 Python 发布提交必须等于它")
    args = ap.parse_args(argv)

    run_id = _dt.datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
    os.makedirs(args.out_dir, exist_ok=True)
    out_path = os.path.join(args.out_dir, f"ai-live-probe-{run_id}.jsonl")
    http = Http(args.base)
    records: List[Dict[str, Any]] = []

    def emit(rec: Dict[str, Any]) -> None:
        records.append(rec)
        with open(out_path, "a", encoding="utf-8", newline="\n") as fh:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")

    with open(args.cases, "r", encoding="utf-8") as fh:
        cases = [c for c in json.load(fh)["cases"] if args.only in c["id"]]
    cases_sha = hashlib.sha256(open(args.cases, "rb").read()).hexdigest()[:12]

    header = {"type": "run", "run_id": run_id, "trigger": args.trigger, "base": args.base, "factory": FACTORY_ID,
              "started_at": _dt.datetime.now().astimezone().isoformat(timespec="seconds"),
              "cases_file_sha256_12": cases_sha, "write": not args.no_write}
    rc_reason = ""
    try:
        header["versions"] = read_versions(http, args.server)
        user, pwd, src = resolve_read_creds(args.server)
        if not user:
            raise NotMeasured("缺只读评测号凭证 (AI_PROBE_READ_* / FACTORY_EVAL_* / ssh .env.prod 都没取到)")
        reader = login(http, user, pwd, "只读评测号")
        header["read_account"] = {"username": reader["username"], "role": reader["role"], "factoryId": reader["factoryId"], "creds_source": src}
        emit(header)

        if args.expect_python_commit:
            got = header["versions"].get("python_release_commit")
            ok = got == args.expect_python_commit
            emit({"type": "case", "case": "version_python_commit", "entry": "server", "result": PASS if ok else FAIL,
                  "problems": None if ok else [f"线上 Python 发布提交 {got} ≠ 期望 {args.expect_python_commit}"]})

        for case in cases:
            per_entry = {}
            for entry in case["entries"]:
                rec = run_case(http, reader["token"], case, entry)
                per_entry[entry] = rec
                emit(rec)
            if case.get("same_intent_across_entries") and len(per_entry) == 2:
                intents = {e: r["final_intent"] for e, r in per_entry.items()}
                ok = len(set(intents.values())) == 1 and None not in intents.values()
                emit({"type": "case", "case": case["id"] + "#same_intent", "entry": "execute=stream",
                      "result": PASS if ok else FAIL, "intents": intents,
                      "problems": None if ok else [f"两个入口意图不一致: {intents}"]})

        if not args.no_write:
            wuser, wpwd, wsrc = resolve_write_creds(args.env_file)
            if not wuser:
                raise NotMeasured(f"缺写入号凭证 (AI_PROBE_WRITE_* 或 {args.env_file})")
            writer = login(http, wuser, wpwd, "写入号")
            wrec: Dict[str, Any] = {}
            try:
                run_write(http, writer, run_id.replace("-", "")[-12:], wrec)
            finally:
                if wrec:
                    emit(wrec)
    except NotMeasured as exc:
        rc_reason = str(exc)
    except Exception as exc:  # noqa: BLE001 — 仪器自己坏了 = 没量到, 但要留下原因
        rc_reason = f"仪器异常 {type(exc).__name__}: {exc}"
    if header not in records:
        records.insert(0, header)
        with open(out_path, "a", encoding="utf-8", newline="\n") as fh:
            fh.write(json.dumps(header, ensure_ascii=False) + "\n")

    executed = [r for r in records if r.get("type") in ("case", "write")]
    counts: Dict[str, int] = {}
    for r in executed:
        counts[r["result"]] = counts.get(r["result"], 0) + 1
    # 有断言失败就是 1 (哪怕后面仪器又坏了 —— 失败的读数是真的); 否则仪器问题 / 0 条执行 = 2
    if counts.get(FAIL):
        rc = 1
    elif rc_reason or not executed:
        rc = 2
        rc_reason = rc_reason or "0 条执行"
    else:
        rc = 0
    summary = {"type": "summary", "rc": rc, "rc_meaning": {0: "全过", 1: "有失败", 2: "没量到"}[rc],
               "reason": rc_reason or None, "counts": counts, "http_calls": http.calls, "transport_retries": http.retries,
               "finished_at": _dt.datetime.now().astimezone().isoformat(timespec="seconds"), "result_file": out_path}
    emit(summary)

    print_human(records)
    print(f"AI_LIVE_PROBE_RESULT={out_path}")
    print(f"AI_LIVE_PROBE_RC={rc}")
    return rc


def print_human(records: List[Dict[str, Any]]) -> None:
    for r in records:
        t = r.get("type")
        if t == "run":
            v = r.get("versions") or {}
            print(f"[run] {r['run_id']} trigger={r['trigger']} base={r['base']} factory={r['factory']} "
                  f"reader={(r.get('read_account') or {}).get('username')}")
            print(f"[versions] java port={v.get('java_active_port')} jar_sha256={(v.get('java_jar_sha256') or '')[:16]} "
                  f"started={v.get('java_started')} | python commit={(v.get('python_release_commit') or '')[:12]} "
                  f"version={v.get('python_service_version')} started={v.get('python_started')} | {v.get('source', {}).get('server')}")
        elif t == "case":
            line = f"[{r['result']:<16}] {r['case']} ({r['entry']})"
            if r.get("turns"):
                line += " → " + " / ".join(f"{x['status']}:{x['intent']}" for x in r["turns"])
            print(line)
            for p in r.get("problems") or []:
                print("      - " + p)
            if r.get("pending_reason") and r["result"] in (PENDING, LIFTED):
                print("      (pending: " + r["pending_reason"] + ")")
        elif t == "write":
            print(f"[{r.get('result', FAIL):<16}] {r['case']} intent={r['intent']} account={r.get('account')} "
                  f"customer={(r.get('customer') or {}).get('name')}")
            print(f"      count before={r.get('count_before')} after_confirm={r.get('count_after_confirm')} "
                  f"after_cleanup={r.get('count_after_cleanup')}")
            for s in r["steps"]:
                print(f"      {'ok ' if s['ok'] else 'BAD'} {s['step']}" + (f" — {s['detail']}" if s.get("detail") else ""))
        elif t == "summary":
            print(f"[summary] rc={r['rc']} ({r['rc_meaning']}) counts={r['counts']} http_calls={r['http_calls']}"
                  + (f" reason={r['reason']}" if r.get("reason") else ""))


if __name__ == "__main__":
    sys.exit(main())
