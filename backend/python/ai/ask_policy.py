"""先问口径分档 (INTENT-DISTILL-20261111, Steve 2026-11-14 拍板: 按工厂选, 不是全局只留一种)。

Luna 主路径在问 Choice 的同时问一道判断题「这一轮是否必须先问」(decision.ask_probability)。
判断题用哪套规则、概率多少才先问、读写各自的门槛, 合起来叫一个「档」:

  conservative 保守 = 多问。史浩禾口径: 规则 (decision.ASK_PREDICATE_RULES) 与先问门槛 0.3 按他的 300 题答案重校
                (2026-11-19; 此前是 #1711 的 v3, 只按他前 39 题校, 门槛 0.6)。与 Steve 答案有争议的题在金标里单列。
  aggressive   激进 = 少问。按 Steve 自答 300 题校 (decision.ASK_PREDICATE_RULES_AGGRESSIVE), 默认档。

两档都**不放宽写操作**: 写目标只交给 Java, 由 Java 一律先出确认卡, 逐笔确认。

档位从哪来 (优先级从高到低):
  1. 全局开关 AI_DECISION_LUNA_POLICY (AIConfig.decision_luna_policy):
       v1 = 旧口径, 判断题不发 (回滚); v3 / conservative = 全部工厂按保守; v4 / aggressive = 全部工厂按激进;
       factory (缺省) = 按工厂。
  2. 按工厂: Java 从 factory_thresholds 读 ai.intent.ask_policy (工厂行 > 全局 '*' 行), 随识别请求的
     askPolicy 字段传来。
  3. 请求没带 / 认不出 ⇒ 默认档 (激进), 并计数 intent_ask_policy_total{source="default_missing|default_unknown"}
     和打 WARNING (⛔ 不静默)。

门槛来由见 Documents/Cretas意图蒸馏-20261111/steve300-calibrate-20261114.md。
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Optional, Tuple

logger = logging.getLogger(__name__)

CONSERVATIVE = "conservative"
AGGRESSIVE = "aggressive"
DEFAULT_TIER = AGGRESSIVE

# 请求里 / 配置里认得的写法 (Java 传大写枚举名; 中文给人手配时用)
_ALIASES = {
    "conservative": CONSERVATIVE, "保守": CONSERVATIVE,
    "aggressive": AGGRESSIVE, "激进": AGGRESSIVE,
}
# 全局开关取值 -> 强制档位 (None = 按工厂; "v1" 单独处理)
_GLOBAL_FORCED = {"v3": CONSERVATIVE, "conservative": CONSERVATIVE, "v4": AGGRESSIVE, "aggressive": AGGRESSIVE}

try:  # pragma: no cover - 依赖缺失时退化为 no-op
    from prometheus_client import Counter

    ASK_POLICY_TOTAL = Counter(
        "intent_ask_policy_total",
        "Luna ask-policy tier used per decision request, and where it came from",
        ["tier", "source"],
    )
except Exception:  # noqa: BLE001
    ASK_POLICY_TOTAL = None


@dataclass(frozen=True)
class AskTier:
    name: str
    rules: str
    ask_threshold: float
    read_threshold: float
    margin: float
    write_threshold: float


def tier_of(config, name: str) -> AskTier:
    from ai.matcher.decision import ASK_PREDICATE_RULES, ASK_PREDICATE_RULES_AGGRESSIVE

    if name == CONSERVATIVE:
        return AskTier(CONSERVATIVE, ASK_PREDICATE_RULES, config.luna_ask_threshold, config.luna_read_threshold,
                       config.luna_margin, config.luna_write_threshold)
    return AskTier(AGGRESSIVE, ASK_PREDICATE_RULES_AGGRESSIVE, config.luna_ask_threshold_aggressive,
                   config.luna_read_threshold, config.luna_margin, config.luna_write_threshold)


def normalize(raw: Optional[str]) -> Optional[str]:
    return _ALIASES.get((raw or "").strip().lower())


def resolve(config, requested: Optional[str]) -> Tuple[Optional[AskTier], str]:
    """-> (档, 来源)。档为 None = 全局 v1 (判断题不发)。来源: global / factory / default_missing / default_unknown。"""
    policy = (getattr(config, "decision_luna_policy", "v1") or "factory").strip().lower()  # 没这个属性的旧配置对象 = v1
    if policy == "v1":
        _count("v1", "global")
        return None, "global"
    forced = _GLOBAL_FORCED.get(policy)
    if forced is not None:
        _count(forced, "global")
        return tier_of(config, forced), "global"
    name = normalize(requested)
    if name is not None:
        _count(name, "factory")
        return tier_of(config, name), "factory"
    source = "default_missing" if not (requested or "").strip() else "default_unknown"
    logger.warning("ask policy %s (askPolicy=%r) -> default tier %s", source, (requested or "")[:40], DEFAULT_TIER)
    _count(DEFAULT_TIER, source)
    return tier_of(config, DEFAULT_TIER), source


def _count(tier: str, source: str) -> None:
    if ASK_POLICY_TOTAL is None:
        return
    try:
        ASK_POLICY_TOTAL.labels(tier=tier, source=source).inc()
    except Exception:  # noqa: BLE001 - 计数绝不能改变识别结果
        pass
