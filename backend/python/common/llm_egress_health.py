"""出境出口健康探针 + 主备切换 (INTENT-DISTILL-20261111 第二批「Luna 出口」线, 2026-11-12)。

为什么: Luna (OpenAI Decisions) 只能经 ``LLM_EGRESS_PROXY`` 出境, 而这个出口是**单点**:
47 的 127.0.0.1:11438 ← 加拿大 Mac mini 的 SSH 反向隧道 ← mini 上 Clash Verge 7897 ← 代理节点 ← OpenAI。
Clash 停了 / 换了节点 / mini 重启, 线上决策模型就没了, 而此前没有任何读数会喊 (Fable 审阅 11.2 第 4 条)。

三层探针 (每个出口各一套, ⛔ 不花 token):
  tunnel    47 本机能不能连上代理端口 (TCP connect)           ← 断 = SSH 反向隧道没了 (mini 掉线/重启)
  proxy     代理端口后面有没有 SOCKS5 应答 (握手 05 01 00 → 05 00)  ← 断 = 隧道在、mini 上 Clash 没在跑
  upstream  经代理 GET ``LLM_EGRESS_PROBE_URL`` (默认 OpenAI /v1/models, 列模型不计费)
            200 = 通; 没配 key 时 401 也算通; 403 = 节点所在地区被封; 超时/连不上 = 节点坏了
⚠️ SSH ``-R`` 的监听端在 47 上由 sshd 持有: 远端 Clash 死了 TCP 照样能连上, 只有握手那一步才失败
   —— 所以 tunnel 与 proxy 必须分两层量, 合成一层就分不清「mini 掉了」和「Clash 停了」。

主备切换 (``LLM_EGRESS_PROXY_FALLBACK``, 逗号分隔, 可选):
  每轮探所有出口; 当前出口连续 ``LLM_EGRESS_SWITCH_AFTER`` (默认 2) 轮不通、且有别的出口这一轮通 → 切过去;
  主出口连续 ``LLM_EGRESS_FAILBACK_AFTER`` (默认 3) 轮通 → 切回主。``LLM_EGRESS_FORCE=primary|fallback1|…``
  手动钉死(开关切换)。没配备用 = 永远用主出口, 行为与以前完全一样(探针照跑、照告警)。
  ``common.llm_client.egress_proxy_url()`` 读这里的当前出口; 代理客户端按 URL 变化重建。

多 worker: 状态写在一个共享 JSON (``LLM_EGRESS_STATE_DIR``/llm-egress-state.json), 用 flock 选一个 worker
做这一轮探针, 其它 worker 只读状态 ⇒ 所有 worker 用同一个出口, 探针频率不随 worker 数翻倍。

告警 (仓库里没有推送渠道 —— 2026-11-12 核对: alert-digest.sh 骨架未排期、无 webhook):
  · ``/metrics``  llm_egress_* (见下), 每个 worker 一份, 读的是同一份共享状态
  · 状态文件 llm-egress-state.json: ``last_run`` 每轮必更新 (硬约束 9: 它不动 = 探针没在跑)
  · 告警文件 ``llm-egress-alerts.log`` (文件名含 alert ⇒ alert-digest 自动发现): DOWN / RECOVERED / SWITCH /
    INSTRUMENT DEAD 各一行, 只在状态变化时写 (⛔ 不每轮刷屏)
  · journal 固定前缀 ``LLM_EGRESS_ALERT {json}`` (WARNING)
CLI 三态 (硬约束 4): ``python -m common.llm_egress_health [--record]``
  rc=0 当前出口三层全通 / rc=1 有出口不通 (读数有效) / rc=2 没量到 (没配出口 / 状态目录写不了 / 探针自己崩)
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import sys
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urlparse

logger = logging.getLogger(__name__)

ALERT_LOG_PREFIX = "LLM_EGRESS_ALERT"
ALERT_FILE_PREFIX = "LLM EGRESS"
LAYERS = ("tunnel", "proxy", "upstream")
DEFAULT_PROBE_URL = "https://api.openai.com/v1/models"
DEFAULT_STATE_DIR = "/www/wwwroot/cretas/logs"
STATE_FILE = "llm-egress-state.json"
ALERT_FILE = "llm-egress-alerts.log"
LOCK_FILE = ".llm-egress-probe.lock"

try:  # pragma: no cover - 依赖缺失时退化为 no-op
    from prometheus_client import Counter, Gauge

    PROBE_UP = Gauge("llm_egress_probe_up", "Egress probe layer up (1) / down (0), last round",
                     ["egress", "layer"])
    PROBE_LATENCY = Gauge("llm_egress_probe_latency_seconds", "Egress probe layer latency, last round",
                          ["egress", "layer"])
    PROBE_TOTAL = Counter("llm_egress_probe_total", "Egress probe layer outcomes (counted by the probing worker)",
                          ["egress", "layer", "outcome"])
    CONSEC_FAIL = Gauge("llm_egress_consecutive_failures", "Consecutive failed probe rounds per egress",
                        ["egress"])
    ACTIVE = Gauge("llm_egress_active", "1 for the egress paid providers currently use", ["egress"])
    SWITCHES = Counter("llm_egress_switch_total", "Egress switches (counted by the probing worker)",
                       ["from_egress", "to_egress", "reason"])
    LAST_RUN = Gauge("llm_egress_probe_last_run_timestamp_seconds",
                     "Unix time of the last completed probe round (must keep advancing)")
    INSTRUMENT_DEAD = Counter("llm_egress_probe_instrument_dead_total",
                              "Probe rounds that could not measure (state dir / crash)", ["reason"])
except Exception:  # noqa: BLE001
    PROBE_UP = PROBE_LATENCY = PROBE_TOTAL = CONSEC_FAIL = ACTIVE = SWITCHES = LAST_RUN = None
    INSTRUMENT_DEAD = None


# ─────────────────────────────── 配置 ───────────────────────────────

def _env_int(name: str, default: int) -> int:
    try:
        return max(1, int((os.getenv(name) or "").strip() or default))
    except ValueError:
        return default


def _env_float(name: str, default: float) -> float:
    try:
        return max(0.1, float((os.getenv(name) or "").strip() or default))
    except ValueError:
        return default


def configured_egresses() -> List[Tuple[str, str]]:
    """[(名字, URL)]: primary = LLM_EGRESS_PROXY, fallback1.. = LLM_EGRESS_PROXY_FALLBACK (逗号分隔)。"""
    out: List[Tuple[str, str]] = []
    primary = (os.getenv("LLM_EGRESS_PROXY") or "").strip()
    if primary:
        out.append(("primary", primary))
    for i, url in enumerate([u.strip() for u in (os.getenv("LLM_EGRESS_PROXY_FALLBACK") or "").split(",")
                             if u.strip()], start=1):
        out.append((f"fallback{i}", url))
    return out


def state_dir() -> str:
    return (os.getenv("LLM_EGRESS_STATE_DIR") or "").strip() or DEFAULT_STATE_DIR


# ─────────────────────────────── 当前出口 (llm_client 读) ───────────────────────────────

_active_name: str = "primary"


def active_proxy_url() -> str:
    """付费两家此刻该走哪个代理。没配备用 / 当前名字不在配置里 → 主出口 (与以前完全一样)。"""
    egresses = configured_egresses()
    if not egresses:
        return ""
    force = (os.getenv("LLM_EGRESS_FORCE") or "").strip()
    name = force or _active_name
    for n, url in egresses:
        if n == name:
            return url
    return egresses[0][1]


def active_egress_name() -> str:
    egresses = dict(configured_egresses())
    force = (os.getenv("LLM_EGRESS_FORCE") or "").strip()
    name = force or _active_name
    return name if name in egresses else ("primary" if egresses else "")


# ─────────────────────────────── 三层探针 ───────────────────────────────

@dataclass
class LayerResult:
    up: bool
    outcome: str            # ok / refused / timeout / bad_handshake / http_<code> / auth / region_blocked / …
    seconds: float


@dataclass
class EgressReading:
    name: str
    layers: Dict[str, LayerResult] = field(default_factory=dict)

    @property
    def up(self) -> bool:
        return all(self.layers.get(layer) is not None and self.layers[layer].up for layer in LAYERS)

    def first_down(self) -> Optional[str]:
        for layer in LAYERS:
            r = self.layers.get(layer)
            if r is None or not r.up:
                return layer
        return None

    def to_json(self) -> Dict[str, Any]:
        return {"up": self.up, "first_down": self.first_down(),
                "layers": {k: {"up": v.up, "outcome": v.outcome, "s": round(v.seconds, 3)}
                           for k, v in self.layers.items()}}


def _exc_outcome(ex: BaseException) -> str:
    if isinstance(ex, (asyncio.TimeoutError, TimeoutError)):
        return "timeout"
    if isinstance(ex, ConnectionRefusedError):
        return "refused"
    name = type(ex).__name__
    if "Timeout" in name:
        return "timeout"
    if "Connect" in name or isinstance(ex, OSError):
        return "conn_error"
    return name[:40]


async def _probe_tunnel_and_proxy(url: str, timeout: float) -> Tuple[LayerResult, LayerResult]:
    parsed = urlparse(url)
    host, port, scheme = parsed.hostname or "127.0.0.1", parsed.port or 1080, (parsed.scheme or "").lower()
    t0 = time.monotonic()
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout=timeout)
    except Exception as ex:  # noqa: BLE001
        tun = LayerResult(False, _exc_outcome(ex), time.monotonic() - t0)
        return tun, LayerResult(False, "skipped", 0.0)
    tun = LayerResult(True, "ok", time.monotonic() - t0)
    t1 = time.monotonic()
    try:
        if scheme.startswith("socks5"):
            writer.write(b"\x05\x01\x00")
            await writer.drain()
            reply = await asyncio.wait_for(reader.readexactly(2), timeout=timeout)
            ok = reply == b"\x05\x00"
            outcome = "ok" if ok else "bad_handshake"
        elif scheme in ("http", "https"):
            target = urlparse(probe_url())
            hp = f"{target.hostname}:{target.port or 443}"
            writer.write(f"CONNECT {hp} HTTP/1.1\r\nHost: {hp}\r\n\r\n".encode())
            await writer.drain()
            line = await asyncio.wait_for(reader.readline(), timeout=timeout)
            ok = line.split(b" ")[1:2] == [b"200"]
            outcome = "ok" if ok else "bad_handshake"
        else:
            ok, outcome = False, "unsupported_scheme"
        prx = LayerResult(ok, outcome, time.monotonic() - t1)
    except asyncio.IncompleteReadError:
        # SSH -R: sshd 在 47 接了 TCP, 远端那头连不上 (Clash 没在跑) 就直接关 ⇒ 握手读到 EOF
        prx = LayerResult(False, "closed", time.monotonic() - t1)
    except Exception as ex:  # noqa: BLE001
        prx = LayerResult(False, _exc_outcome(ex), time.monotonic() - t1)
    finally:
        try:
            writer.close()
        except Exception:  # noqa: BLE001
            pass
    return tun, prx


def probe_url() -> str:
    return (os.getenv("LLM_EGRESS_PROBE_URL") or "").strip() or DEFAULT_PROBE_URL


async def _probe_upstream(url: str, timeout: float) -> LayerResult:
    import httpx

    key = (os.getenv("LLM_OPENAI_API_KEY") or "").strip()
    target = probe_url()
    headers = {"Authorization": f"Bearer {key}"} if key and "openai.com" in target else {}
    t0 = time.monotonic()
    try:
        # 独立的一次性客户端: ⛔ 不用共享 LLM 客户端 (那会进计费/审计/熔断, 探针不是一次 LLM 调用)
        async with httpx.AsyncClient(proxy=url, timeout=httpx.Timeout(timeout)) as client:
            resp = await client.get(target, headers=headers)
        code = resp.status_code
    except Exception as ex:  # noqa: BLE001
        return LayerResult(False, _exc_outcome(ex), time.monotonic() - t0)
    dt = time.monotonic() - t0
    if 200 <= code < 300:
        return LayerResult(True, "ok", dt)
    if code == 401:
        # 没配 key: 401 证明 OpenAI 应答了, 出口是通的; 配了 key 还 401 = key 坏了, Luna 照样用不了
        return LayerResult(not key, "auth", dt)
    if code == 403:
        return LayerResult(False, "region_blocked", dt)
    return LayerResult(False, f"http_{code}", dt)


async def probe_egress(name: str, url: str, timeout: Optional[float] = None) -> EgressReading:
    t = timeout or _env_float("LLM_EGRESS_PROBE_TIMEOUT_S", 5.0)
    reading = EgressReading(name)
    tun, prx = await _probe_tunnel_and_proxy(url, t)
    reading.layers["tunnel"], reading.layers["proxy"] = tun, prx
    if prx.up:
        reading.layers["upstream"] = await _probe_upstream(url, max(t, 8.0))
    else:
        reading.layers["upstream"] = LayerResult(False, "skipped", 0.0)
    return reading


# ─────────────────────────────── 状态 / 切换 / 告警 ───────────────────────────────

def _read_state(path: str) -> Dict[str, Any]:
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except FileNotFoundError:
        return {}
    except Exception:  # noqa: BLE001 - 坏文件当作空状态, 下一轮重写
        return {}


def _write_state(path: str, state: Dict[str, Any]) -> None:
    tmp = f"{path}.tmp.{os.getpid()}"
    payload = json.dumps(state, ensure_ascii=False, indent=1)  # 先算好再开文件 (硬约束 5/6)
    with open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(payload)
    os.replace(tmp, path)


def _alert(dir_: str, kind: str, detail: Dict[str, Any]) -> None:
    stamp = time.strftime("%Y-%m-%d %H:%M:%S")
    line = f"{ALERT_FILE_PREFIX} {kind} {stamp} {json.dumps(detail, ensure_ascii=False, sort_keys=True)}\n"
    logger.warning("%s %s", ALERT_LOG_PREFIX, json.dumps({"kind": kind, **detail}, ensure_ascii=False))
    # 告警文件写不了就让它抛: 调用方把这一轮记成 INSTRUMENT DEAD (rc=2), ⛔ 不吞
    with open(os.path.join(dir_, ALERT_FILE), "a", encoding="utf-8", newline="") as f:
        f.write(line)


def decide(state: Dict[str, Any], readings: List[EgressReading], now: float) -> Tuple[Dict[str, Any], List[Tuple[str, Dict[str, Any]]]]:
    """纯函数: 旧状态 + 这一轮读数 → 新状态 + 要发的告警 [(kind, detail)]。"""
    switch_after = _env_int("LLM_EGRESS_SWITCH_AFTER", 2)
    failback_after = _env_int("LLM_EGRESS_FAILBACK_AFTER", 3)
    alert_after = _env_int("LLM_EGRESS_ALERT_AFTER", 2)
    names = [r.name for r in readings]
    old_eg: Dict[str, Any] = state.get("egresses") if isinstance(state.get("egresses"), dict) else {}  # type: ignore[assignment]
    new_eg: Dict[str, Any] = {}
    alerts: List[Tuple[str, Dict[str, Any]]] = []
    for r in readings:
        prev: Dict[str, Any] = old_eg.get(r.name) if isinstance(old_eg.get(r.name), dict) else {}  # type: ignore[assignment]
        fails = 0 if r.up else int(prev.get("consecutive_failures") or 0) + 1
        oks = int(prev.get("consecutive_ok") or 0) + 1 if r.up else 0
        alerted = bool(prev.get("alerted"))
        if not r.up and fails >= alert_after and not alerted:
            alerts.append(("DOWN", {"egress": r.name, "consecutive_failures": fails,
                                    "first_down": r.first_down(), "reading": r.to_json()["layers"]}))
            alerted = True
        elif r.up and alerted:
            alerts.append(("RECOVERED", {"egress": r.name, "after_failures": int(prev.get("consecutive_failures") or 0)}))
            alerted = False
        prev_up = (prev.get("last") or {}).get("up") if prev else None
        new_eg[r.name] = {"consecutive_failures": fails, "consecutive_ok": oks, "alerted": alerted,
                          "last": r.to_json(),
                          "last_change": prev.get("last_change") if prev_up is r.up else now}

    active: str = str(state.get("active")) if state.get("active") in names else (names[0] if names else "")
    force = (os.getenv("LLM_EGRESS_FORCE") or "").strip()
    switched: Optional[Tuple[str, str, str]] = None
    if force in names and force != active:
        switched = (active, force, "forced")
    elif not force and active and new_eg[active]["consecutive_failures"] >= switch_after:
        for n in names:  # 按配置顺序 (主优先) 找这一轮通的
            if n != active and new_eg[n]["last"]["up"]:
                switched = (active, n, "active_down")
                break
    elif (not force and active != "primary" and "primary" in new_eg
          and new_eg["primary"]["consecutive_ok"] >= failback_after):
        switched = (active, "primary", "failback")
    if switched:
        frm, to, why = switched
        active = to
        alerts.append(("SWITCH", {"from": frm, "to": to, "reason": why}))
    # 全部不通时不切 (切过去也不通); 每个出口各有自己的 DOWN 告警
    new_state = {"active": active, "egresses": new_eg, "last_run": now,
                 "last_run_iso": time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(now)),
                 "rounds": int(state.get("rounds") or 0) + 1}
    if switched:
        new_state["last_switch"] = {"from": switched[0], "to": switched[1], "reason": switched[2], "at": now}
    elif state.get("last_switch"):
        new_state["last_switch"] = state["last_switch"]
    return new_state, alerts


def apply_state_to_process(state: Dict[str, Any]) -> None:
    """把共享状态同步进本 worker: 当前出口 + /metrics gauges (每个 worker 读同一份)。"""
    global _active_name
    names = [n for n, _ in configured_egresses()]
    active = state.get("active")
    if active in names:
        if active != _active_name:
            logger.warning("[llm-egress] worker %s now uses egress %s (was %s)", os.getpid(), active, _active_name)
        _active_name = active
    if PROBE_UP is None:
        return
    eg: Dict[str, Any] = state.get("egresses") if isinstance(state.get("egresses"), dict) else {}  # type: ignore[assignment]
    for n in names:
        info = eg.get(n) or {}
        layers = (info.get("last") or {}).get("layers") or {}
        for layer in LAYERS:
            li = layers.get(layer)
            if li is not None:
                PROBE_UP.labels(n, layer).set(1 if li.get("up") else 0)
                PROBE_LATENCY.labels(n, layer).set(float(li.get("s") or 0))
        CONSEC_FAIL.labels(n).set(int(info.get("consecutive_failures") or 0))
        ACTIVE.labels(n).set(1 if n == active_egress_name() else 0)
    if state.get("last_run"):
        LAST_RUN.set(float(state["last_run"]))


async def run_round(dir_: Optional[str] = None) -> Tuple[int, Dict[str, Any]]:
    """探一轮、更新共享状态、写告警。返回 (rc, state)。rc: 0 当前出口全通 / 1 有出口不通 / 2 没量到。"""
    d = dir_ or state_dir()
    egresses = configured_egresses()
    if not egresses:
        return 2, {"reason": "no_egress_configured"}
    try:
        if not (os.path.isdir(d) and os.access(d, os.W_OK)):
            raise PermissionError(f"state dir not writable: {d}")
        path = os.path.join(d, STATE_FILE)
        readings = await asyncio.gather(*(probe_egress(n, u) for n, u in egresses))
        old = _read_state(path)
        new, alerts = decide(old, list(readings), time.time())
        _write_state(path, new)
        for kind, detail in alerts:
            _alert(d, kind, detail)
        if PROBE_TOTAL is not None:
            for r in readings:
                for layer, lr in r.layers.items():
                    PROBE_TOTAL.labels(r.name, layer, lr.outcome).inc()
            for kind, detail in alerts:
                if kind == "SWITCH":
                    SWITCHES.labels(detail["from"], detail["to"], detail["reason"]).inc()
        apply_state_to_process(new)
        rc = 0 if all(r.up for r in readings) else 1
        return rc, new
    except Exception as ex:  # noqa: BLE001
        reason = f"{type(ex).__name__}"
        logger.critical("%s %s", ALERT_LOG_PREFIX, json.dumps({"kind": "INSTRUMENT_DEAD", "reason": reason,
                                                               "detail": str(ex)[:200]}, ensure_ascii=False))
        if INSTRUMENT_DEAD is not None:
            INSTRUMENT_DEAD.labels(reason).inc()
        try:  # 能写就在告警文件里留一行 (与 cron_dead 同措辞: 本次读数作废)
            with open(os.path.join(d, ALERT_FILE), "a", encoding="utf-8", newline="") as f:
                f.write(f"{ALERT_FILE_PREFIX} INSTRUMENT DEAD {time.strftime('%Y-%m-%d %H:%M:%S')} "
                        f"reason={reason} — 本次读数作废, ⛔ 不要读成「没问题」\n")
        except Exception:  # noqa: BLE001
            pass
        return 2, {"reason": reason, "detail": str(ex)[:200]}


# ─────────────────────────────── 服务内后台循环 ───────────────────────────────

_task: Optional[asyncio.Task] = None


def _try_lock(path: str):
    """非阻塞 flock; 拿到返回文件对象 (调用方关掉即释放), 拿不到返回 None。Windows 开发机没有 fcntl → 总能拿到。"""
    f = open(path, "a+")
    try:
        import fcntl
        fcntl.flock(f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        return f
    except ImportError:
        return f
    except OSError:
        f.close()
        return None


async def _loop() -> None:
    interval = _env_float("LLM_EGRESS_PROBE_INTERVAL_S", 15.0)
    tick = max(1.0, interval / 3)
    d = state_dir()
    while True:
        try:
            path = os.path.join(d, STATE_FILE)
            st = _read_state(path)
            due = time.time() - float(st.get("last_run") or 0) >= interval
            lock = _try_lock(os.path.join(d, LOCK_FILE)) if due else None
            if lock is not None:
                try:
                    # 拿到锁后再读一次: 另一个 worker 可能刚探完
                    if time.time() - float(_read_state(path).get("last_run") or 0) >= interval:
                        await run_round(d)
                finally:
                    lock.close()
            apply_state_to_process(_read_state(path))
        except asyncio.CancelledError:
            raise
        except Exception as ex:  # noqa: BLE001
            logger.error("[llm-egress] probe loop error: %s", type(ex).__name__)
            if INSTRUMENT_DEAD is not None:
                INSTRUMENT_DEAD.labels(f"loop:{type(ex).__name__}").inc()
        await asyncio.sleep(tick)


def start_background_probe() -> Optional[asyncio.Task]:
    """lifespan 里调用。没配出口 = 不起 (付费两家本来就不发请求); ``LLM_EGRESS_PROBE=0`` 可关。"""
    global _task
    if not configured_egresses() or (os.getenv("LLM_EGRESS_PROBE") or "").strip() == "0":
        logger.info("[llm-egress] probe not started (no egress configured or LLM_EGRESS_PROBE=0)")
        return None
    if _task is None or _task.done():
        d = state_dir()
        if not (os.path.isdir(d) and os.access(d, os.W_OK)):
            logger.critical("%s %s", ALERT_LOG_PREFIX, json.dumps(
                {"kind": "INSTRUMENT_DEAD", "reason": "state_dir_unwritable", "dir": d}))
            if INSTRUMENT_DEAD is not None:
                INSTRUMENT_DEAD.labels("state_dir_unwritable").inc()
            return None
        _task = asyncio.create_task(_loop(), name="llm_egress_probe")
        logger.info("[llm-egress] probe started: egresses=%s dir=%s",
                    [n for n, _ in configured_egresses()], d)
    return _task


async def stop_background_probe() -> None:
    global _task
    if _task is not None:
        _task.cancel()
        try:
            await _task
        except (asyncio.CancelledError, Exception):
            pass
        _task = None


# ─────────────────────────────── CLI ───────────────────────────────

def _main(argv: Optional[List[str]] = None) -> int:
    import argparse

    p = argparse.ArgumentParser(description="LLM egress three-layer probe")
    p.add_argument("--record", action="store_true",
                   help="update shared state + alert file like the service does (default: read-only probe)")
    p.add_argument("--check-state", action="store_true",
                   help="cron: read the service's shared state; rc=2 + alert line if the probe went silent")
    args = p.parse_args(argv)
    if args.check_state:
        return check_state()
    if args.record:
        rc, state = asyncio.run(run_round())
        print(json.dumps({"rc": rc, "state": state}, ensure_ascii=False, indent=1))
        return rc
    egresses = configured_egresses()
    if not egresses:
        print("LLM EGRESS INSTRUMENT DEAD reason=no_egress_configured (LLM_EGRESS_PROXY 为空)")
        return 2
    try:
        readings = asyncio.run(_gather(egresses))
    except Exception as ex:  # noqa: BLE001
        print(f"LLM EGRESS INSTRUMENT DEAD reason={type(ex).__name__}")
        return 2
    for r in readings:
        print(json.dumps({"egress": r.name, **r.to_json()}, ensure_ascii=False))
    return 0 if all(r.up for r in readings) else 1


def check_state(dir_: Optional[str] = None, now: Optional[float] = None) -> int:
    """给 cron 用的「探针还活着吗」(硬约束 9): 服务挂了 / 探针循环死了, 状态文件的 last_run 就不再前进,
    而服务里的告警也就不会再喊 —— 这一步从服务外面看。
    rc=0 当前出口通 / rc=1 当前出口不通 / rc=2 状态文件不在或已陈旧 (探针没在跑), 后者写一行 STALE 告警。"""
    d = dir_ or state_dir()
    t = time.time() if now is None else now
    limit = max(120.0, 5 * _env_float("LLM_EGRESS_PROBE_INTERVAL_S", 15.0))
    st = _read_state(os.path.join(d, STATE_FILE))
    age = t - float(st.get("last_run") or 0)
    if not st or age > limit:
        msg = (f"{ALERT_FILE_PREFIX} STALE {time.strftime('%Y-%m-%d %H:%M:%S')} "
               f"state={'missing' if not st else f'age={int(age)}s'} limit={int(limit)}s "
               f"— 探针没在跑 (cretas-python 挂了或探针循环死了), 出口状况未知, ⛔ 不要读成「没问题」\n")
        print(msg.strip())
        try:
            with open(os.path.join(d, ALERT_FILE), "a", encoding="utf-8", newline="") as f:
                f.write(msg)
        except Exception as ex:  # noqa: BLE001
            print(f"cannot write alert file: {type(ex).__name__}", file=sys.stderr)
        return 2
    active = st.get("active")
    up = bool(((st.get("egresses") or {}).get(active) or {}).get("last", {}).get("up"))
    print(json.dumps({"active": active, "active_up": up, "age_s": int(age), "rounds": st.get("rounds")}))
    return 0 if up else 1


async def _gather(egresses: List[Tuple[str, str]]) -> List[EgressReading]:
    return list(await asyncio.gather(*(probe_egress(n, u) for n, u in egresses)))


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(_main())
