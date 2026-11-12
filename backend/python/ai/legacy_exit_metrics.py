"""旧出口计数与回落告警 (INTENT-DISTILL-20261111, Fable 审阅建议 6 前半: 先量再关)。

「向量和关键词只给候选、从不授权」在下面这些出口上不成立 —— 它们不经决策模型直接定意图。
这里**只计数、只打日志**, ⛔ 不改任何出口的行为:

  Python 旧四级 (orchestrator._match_legacy), 出口名:
    py_router_vector   SemanticRouter DIRECT_EXECUTE (向量)
    py_semantic_vector Stage 5 向量相似度 >= AI_SEMANTIC_THRESHOLD 直出
    py_fusion          Stage 7 向量 + 分类器融合分 >= AI_FUSION_THRESHOLD 直出
    py_legacy_llm      Stage 8 旧 LLM 匹配 (不是决策层, 没有读概率与门槛)
    py_legacy_none     旧四级也没答出来 (交回 Java, Java 会再走自己的旧管线)
  Luna -> 千问 回落 (decision.DecisionMatcher.choose)。

读数 (同一进程 /metrics, Prometheus 文本格式, 每个 uvicorn worker 各一份):
  intent_legacy_exit_total{exit, factory, rw, reason}
  intent_legacy_exit_site_requests_total{site}         分母: 到达可能回落的那一点的请求数
  intent_decision_luna_fallback_total{reason, qwen_outcome}
  intent_decision_luna_requests_total                  分母: 真的去问了 Luna 的次数

告警: 没有现成的告警渠道 (2026-11-12 核对仓库: 无 webhook / 钉钉 / 企微接线), 所以超阈值时只打一条
固定前缀的 WARNING 结构化日志 ``INTENT_LEGACY_EXIT_ALERT {json}``, 由日志 grep 捕获。

rw 用 Python 侧意图口径 (ai.db._is_write_row, 镜像 Java WriteGuardService 的意图侧启发式); 执行期
写闸的权威判定仍在 Java。计数失败一律吞掉 —— 计数绝不能改变识别结果。
"""
from __future__ import annotations

import contextvars
import json
import logging
import os
import threading
import time
from typing import Any, Dict, Optional

logger = logging.getLogger(__name__)

ALERT_LOG_PREFIX = "INTENT_LEGACY_EXIT_ALERT"

# 不计入告警分子的原因: 配置使然的主路径, 不是故障 (仍然计数, 方便看总量)
NON_FAULT_REASONS = frozenset({"decision_not_enabled", "llm_disabled"})

try:  # pragma: no cover - 依赖缺失时退化为 no-op
    from prometheus_client import Counter

    LEGACY_EXIT = Counter(
        "intent_legacy_exit_total",
        "Intent decided by a legacy exit (vector / fusion / legacy LLM), not the decision model",
        ["exit", "factory", "rw", "reason"],
    )
    LEGACY_SITE_REQUESTS = Counter(
        "intent_legacy_exit_site_requests_total",
        "Requests that reached a point where a legacy exit could fire (rate denominator)",
        ["site"],
    )
    LUNA_FALLBACK = Counter(
        "intent_decision_luna_fallback_total",
        "Luna (OpenAI Decisions) choice failed and the qwen logprobs chain was tried",
        ["reason", "qwen_outcome"],
    )
    LUNA_REQUESTS = Counter(
        "intent_decision_luna_requests_total",
        "Decision choices that actually asked Luna (rate denominator)",
    )
except Exception:  # noqa: BLE001
    LEGACY_EXIT = LEGACY_SITE_REQUESTS = LUNA_FALLBACK = LUNA_REQUESTS = None


# ---------------------------------------------------------------------------
# 原因: 决策层为什么没给出结果 (由 orchestrator 在同一个 asyncio 任务里设置/读取)
# ---------------------------------------------------------------------------
_decision_none_reason: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "intent_decision_none_reason", default=None)
_luna_failure_reason: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "intent_luna_failure_reason", default=None)


def set_decision_none_reason(reason: Optional[str]) -> None:
    _decision_none_reason.set(reason)


def get_decision_none_reason() -> Optional[str]:
    return _decision_none_reason.get()


def set_luna_failure_reason(reason: Optional[str]) -> None:
    _luna_failure_reason.set(reason)


def get_luna_failure_reason() -> Optional[str]:
    return _luna_failure_reason.get()


_KNOWN_CHAIN_REASONS = ("timeout", "cb_open", "quota_skip", "not_enabled", "invalid_choice_distribution")


def classify_exception(ex: BaseException) -> str:
    """把异常归成有限几类 (标签基数要有界)。LLMChainExhausted 读它的 errors 首条。"""
    import asyncio

    if isinstance(ex, asyncio.TimeoutError):
        return "timeout"
    errors = getattr(ex, "errors", None)
    if isinstance(errors, list) and errors:
        tail = str(errors[-1]).split(": ", 1)[-1].strip().lower()
        if tail in _KNOWN_CHAIN_REASONS:
            return tail
        for prefix in ("http ", "quota "):
            if tail.startswith(prefix):
                code = tail[len(prefix):].split()[0] if tail[len(prefix):].split() else ""
                if code.isdigit():
                    return prefix.strip() + "_" + code
        return "chain_other"
    name = type(ex).__name__
    if "Redaction" in name:
        return "redaction_refused"
    return "exception_" + name[:40]


# ---------------------------------------------------------------------------
# 阈值告警: 按时间切的滚动窗 (每进程), 窗内分母够了才判
# ---------------------------------------------------------------------------
def _env_float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, str(default)))
    except ValueError:
        return default


class RateAlarm:
    """窗口内 故障命中 / 请求 > threshold 且 请求 >= min_samples 时打一条告警日志, 每窗最多一条。"""

    def __init__(self, site: str, threshold: float, window_s: float, min_samples: int):
        self.site = site
        self.threshold = threshold
        self.window_s = window_s
        self.min_samples = min_samples
        self._lock = threading.Lock()
        self._reset(time.time())

    def _reset(self, now: float) -> None:
        self._start = now
        self._requests = 0
        self._hits = 0
        self._alerted = False

    def observe(self, hit: bool, now: Optional[float] = None) -> Optional[Dict[str, Any]]:
        now = time.time() if now is None else now
        with self._lock:
            if now - self._start >= self.window_s:
                self._reset(now)
            self._requests += 1
            if hit:
                self._hits += 1
            if self._alerted or self._requests < self.min_samples:
                return None
            rate = self._hits / self._requests
            if rate <= self.threshold:
                return None
            self._alerted = True
            payload = {"site": self.site, "rate": round(rate, 4), "threshold": self.threshold,
                       "hits": self._hits, "requests": self._requests,
                       "window_s": self.window_s, "window_start": int(self._start)}
        logger.warning("%s %s", ALERT_LOG_PREFIX, json.dumps(payload, ensure_ascii=False))
        return payload


# 默认阈值的理由 (可用环境变量覆盖):
#   决策层回落旧四级 5%: 决策层设计上应答住几乎全部请求; 故障类回落 (异常 / 没候选 / 拿不到概率)
#     超过 1/20 就说明决策链在降级, 旧四级正在替它定意图。
#   Luna -> 千问 10%: 独立验收三次跑里出现 2 次 503, 标注 600 条里 1 次 —— 偶发属于正常;
#     一成请求都在回落基本就是加拿大出口 (Clash) 断了。
#   窗口 15 分钟、至少 20 次: 目前没有真实客户, 流量很低; 太小的分母一次失败就是 100%。
def _window_s() -> float:
    return _env_float("AI_LEGACY_EXIT_ALERT_WINDOW_S", 900.0)


def _min_samples() -> int:
    return int(_env_float("AI_LEGACY_EXIT_ALERT_MIN_SAMPLES", 20))


PY_LEGACY_ALARM = RateAlarm("py_decision_fallback", _env_float("AI_LEGACY_EXIT_ALERT_PY_FALLBACK_RATE", 0.05),
                            _window_s(), _min_samples())
LUNA_ALARM = RateAlarm("luna_to_qwen_fallback", _env_float("AI_LEGACY_EXIT_ALERT_LUNA_FALLBACK_RATE", 0.10),
                       _window_s(), _min_samples())


# ---------------------------------------------------------------------------
# 记录入口 (全部吞异常)
# ---------------------------------------------------------------------------
def legacy_exit_name(result: Any) -> str:
    """从旧四级的返回值推出是哪个出口答的 (只读, 不改结果)。"""
    method = getattr(getattr(result, "matchMethod", None), "value", None) or str(
        getattr(result, "matchMethod", "") or "")
    method = str(method).upper()
    timing = getattr(result, "timingMs", None) or {}
    if getattr(result, "bestMatch", None) is None:
        return "py_legacy_none"
    if method.endswith("SEMANTIC"):
        return "py_semantic_vector" if "semanticMs" in timing else "py_router_vector"
    if method.endswith("FUSION"):
        return "py_fusion"
    if method.endswith("LLM"):
        return "py_legacy_llm"
    return "py_legacy_" + (method.lower()[:20] or "unknown")


def _rw_of(result: Any, visible_intents) -> str:
    best = getattr(result, "bestMatch", None)
    if best is None:
        return "none"
    code = getattr(best, "intentCode", None)
    try:
        from ai.db import _is_write_row

        for row in visible_intents or []:
            if str(row.get("intent_code")) == str(code):
                return "write" if _is_write_row(row) else "read"
    except Exception:  # noqa: BLE001
        pass
    return "unknown"


def record_decision_site_request() -> None:
    try:
        if LEGACY_SITE_REQUESTS is not None:
            LEGACY_SITE_REQUESTS.labels(site="py_decision").inc()
    except Exception:  # noqa: BLE001
        logger.debug("legacy exit site counter failed", exc_info=True)


def record_legacy_exit(result: Any, factory_id: Optional[str], reason: str, visible_intents=None,
                       observe_alarm: bool = True) -> None:
    try:
        exit_name = legacy_exit_name(result)
        rw = _rw_of(result, visible_intents)
        if LEGACY_EXIT is not None:
            LEGACY_EXIT.labels(exit=exit_name, factory=str(factory_id or "")[:32], rw=rw, reason=reason).inc()
        logger.info("intent legacy exit: exit=%s factory=%s rw=%s reason=%s", exit_name, factory_id, rw, reason)
    except Exception:  # noqa: BLE001
        logger.debug("legacy exit counter failed", exc_info=True)
    if observe_alarm:
        try:
            PY_LEGACY_ALARM.observe(hit=reason not in NON_FAULT_REASONS)
        except Exception:  # noqa: BLE001
            logger.debug("legacy exit alarm failed", exc_info=True)


def observe_decision_answered() -> None:
    """决策层自己答了 (没回落): 只进告警分母。"""
    try:
        PY_LEGACY_ALARM.observe(hit=False)
    except Exception:  # noqa: BLE001
        logger.debug("legacy exit alarm failed", exc_info=True)


def record_luna_request() -> None:
    try:
        if LUNA_REQUESTS is not None:
            LUNA_REQUESTS.inc()
    except Exception:  # noqa: BLE001
        logger.debug("luna request counter failed", exc_info=True)


def record_luna_outcome(fallback_reason: Optional[str], qwen_outcome: Optional[str]) -> None:
    """fallback_reason=None 表示 Luna 直接给出了概率 (只进分母)。"""
    try:
        if fallback_reason is not None and LUNA_FALLBACK is not None:
            LUNA_FALLBACK.labels(reason=fallback_reason, qwen_outcome=qwen_outcome or "unknown").inc()
            logger.info("intent decision luna fallback: reason=%s qwen_outcome=%s", fallback_reason, qwen_outcome)
    except Exception:  # noqa: BLE001
        logger.debug("luna fallback counter failed", exc_info=True)
    try:
        LUNA_ALARM.observe(hit=fallback_reason is not None)
    except Exception:  # noqa: BLE001
        logger.debug("luna alarm failed", exc_info=True)


__all__ = [
    "ALERT_LOG_PREFIX", "RateAlarm", "classify_exception", "legacy_exit_name",
    "record_legacy_exit", "record_decision_site_request", "observe_decision_answered",
    "record_luna_request", "record_luna_outcome",
    "set_decision_none_reason", "get_decision_none_reason",
    "set_luna_failure_reason", "get_luna_failure_reason",
    "PY_LEGACY_ALARM", "LUNA_ALARM",
]
