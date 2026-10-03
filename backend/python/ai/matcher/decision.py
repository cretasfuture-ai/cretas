"""第②层: 读概率决策 (decision-model 模式)。

两次快调用, 都走 SLOT.DECISION (qwen3.7-flash 链头):
  1. 粗选: 全目录 (代码｜名称) -> 最多 8 个候选 (短 JSON 生成; 目录是静态前缀, 吃隐式缓存)
  2. 细选: 候选 (粗选 + 向量补足, 最多 20 个) 编成字母 A-T, 前缀续写「答案：」,
     max_tokens=1, 读首 token 的 top_logprobs -> 每个候选一个概率

输出是**概率分布**, 不是一个答案: 置信够高才自动执行, 不够就把前几名交给第③层
(按钮澄清)。⛔ 拿不到概率时返回 None, 由编排器回落旧路径 —— 不猜「默认第一个」。

提示词与 2026-10 离线评测逐字一致 (scripts/jev-oss-eval/sim/mine_eval.py 的 Shortlist /
Chooser), 改这里要同步重跑评测。
"""
from __future__ import annotations

import json
import logging
import math
import random
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

LETTERS = "ABCDEFGHIJKLMNOPQRST"
INSTR = "用户这句话应该交给下面哪个系统功能来处理？"
STRICT_SYSTEM = "你是业务系统的请求分类器。只输出一个大写选项字母，不要换行、不要空格、不要任何其他字符。"

# 角色码 -> (称呼, 说明)。说明进提示词, 帮模型理解「同一句话不同人说意思不同」。
ROLE_LABELS: Dict[str, tuple] = {
    "operator": ("操作工", "车间操作工，文化程度一般，说话口语化、很短，常用语音输入"),
    "warehouse_worker": ("仓管员", "仓管员，年纪偏大，说话直接，常带数量和物料名"),
    "warehouse_manager": ("仓库主管", "仓库主管，关心库存、出入库和盘点"),
    "quality_inspector": ("质检员", "质检员，说话相对规范，会提批次号"),
    "quality_manager": ("质量主管", "质量主管，关心质检结果、不合格和整改"),
    "production_manager": ("生产主管", "生产主管/调度，关心进度、产能、人员、缺料"),
    "dispatcher": ("调度", "生产主管/调度，关心进度、产能、人员、缺料"),
    "workshop_supervisor": ("车间主任", "车间主任，关心本车间进度、人员和工序"),
    "factory_super_admin": ("厂长", "老板或厂长，关心经营数字、订单、发货、毛利"),
    "procurement_manager": ("采购员", "采购员，关心缺什么料、价格、供应商、下单"),
    "sales_manager": ("销售", "销售/发货员，关心订单、发货、客户"),
}


def role_line(role: Optional[str]) -> str:
    label, desc = ROLE_LABELS.get((role or "").strip(), (role or "员工", "工厂员工"))
    return f"用户身份：食品加工厂{label}（{desc}）"


def intent_text(row: Dict[str, Any]) -> str:
    kws = "、".join(_keywords(row)[:8])
    desc = " ".join(str(row.get("description") or "").split())[:80]
    name = row.get("intent_name") or row.get("intent_code")
    return f"{name}：{desc}" + (f" 常见说法：{kws}" if kws else "")


def _keywords(row: Dict[str, Any]) -> List[str]:
    kw = row.get("keywords")
    if isinstance(kw, str):
        try:
            kw = json.loads(kw)
        except json.JSONDecodeError:
            kw = [kw]
    return [str(k) for k in kw] if isinstance(kw, list) else []


def _catalog_line(row: Dict[str, Any], n_keywords: int = 3) -> str:
    # 每行带前 3 个常见说法: 只看名称时「搞起酱鸭腿」找不到「开始工序」。
    # 2026-10-02 history 段离线实测 (未命中精确表的 1,645 次): 候选召回 65.3% -> 74.0%。
    line = f"{row['intent_code']}｜{row.get('intent_name') or ''}"
    kws = _keywords(row)[:n_keywords]
    return line + (f"（{'、'.join(kws)}）" if kws else "")


# 最近对话 (第 3b 步, 2026-10-03): 「好了」「就是六十箱」这种话离开上一句说不清。
# 只为消歧 —— 最多几轮、每轮截断, 与 Java 侧 SseStreamingService.recentTurns 的清洗同口径。
MAX_HISTORY_TURNS = 4
MAX_TURN_CHARS = 200
_SPEAKER = {"user": "用户", "assistant": "系统"}


def history_block(history: Optional[List[Dict[str, Any]]]) -> str:
    """最近对话 -> 提示词片段; 没有可用的轮次时返回空串 (提示词与不带上下文时逐字相同)。"""
    lines: List[str] = []
    for turn in history or []:
        if not isinstance(turn, dict):
            continue
        who = _SPEAKER.get(str(turn.get("role") or ""))
        text = " ".join(str(turn.get("content") or "").split())[:MAX_TURN_CHARS]
        if who and text:
            lines.append(f"{who}：{text}")
    lines = lines[-MAX_HISTORY_TURNS:]
    if not lines:
        return ""
    return "最近对话（只用来理解下面这句话指的是什么）：\n" + "\n".join(lines) + "\n"


def build_shortlist_messages(query: str, role: Optional[str], visible: List[Dict[str, Any]],
                             size: int = 8, history: Optional[List[Dict[str, Any]]] = None) -> List[Dict[str, str]]:
    catalog = "\n".join(
        _catalog_line(r) for r in sorted(visible, key=lambda r: str(r.get("intent_code")))
    )
    system = ("你是食品加工厂业务系统的意图粗筛器。下面是全部意图目录（代码｜名称）：\n" + catalog +
              f'\n\n只输出 JSON：{{"candidates": ["代码1", "代码2", ...]}}，最多 {size} 个，按可能性从高到低；都不像就输出空数组。')
    return [{"role": "system", "content": system},
            {"role": "user", "content": f"{role_line(role)}\n{history_block(history)}用户说：{query}"}]


def parse_shortlist(raw: str, visible_codes: set, size: int = 8) -> Optional[List[str]]:
    txt = (raw or "").strip().strip("`")
    if txt[:4].lower() == "json":
        txt = txt[4:]
    try:
        obj = json.loads(txt.strip())
    except Exception:  # noqa: BLE001
        return None
    codes = obj.get("candidates") if isinstance(obj, dict) else None
    if not isinstance(codes, list):
        return None
    out: List[str] = []
    for c in codes:
        c = str(c)
        if c in visible_codes and c not in out:
            out.append(c)
    return out[:size]


def build_choice_messages(query: str, role: Optional[str], options: List[Dict[str, Any]],
                          history: Optional[List[Dict[str, Any]]] = None) -> List[Dict[str, Any]]:
    letters = LETTERS[: len(options)]
    opts = "\n".join(f"{L}. {r['intent_code']}：{intent_text(r)[:60]}" for L, r in zip(letters, options))
    user = f"{role_line(role)}\n{history_block(history)}用户说：{query}\n\n问题：{INSTR}\n选项：\n{opts}\n\n只输出一个选项字母。"
    return [{"role": "system", "content": STRICT_SYSTEM},
            {"role": "user", "content": user},
            {"role": "assistant", "content": "答案：", "partial": True}]


def letter_probs(body: Dict[str, Any], n_options: int) -> Optional[Dict[str, float]]:
    """首 token 的 top_logprobs -> {字母: 归一化概率}。拿不到任何选项字母 -> None。"""
    letters = LETTERS[:n_options]
    try:
        ch = (body.get("choices") or [{}])[0]
        content = ((ch.get("logprobs") or {}).get("content")) or []
        top = (content[0].get("top_logprobs") or []) if content else []
    except Exception:  # noqa: BLE001
        return None
    agg: Dict[str, float] = {}
    for t in top:
        L = str(t.get("token", "")).strip().upper()
        if len(L) == 1 and L in letters:
            agg[L] = agg.get(L, 0.0) + math.exp(float(t["logprob"]))
    z = sum(agg.values())
    if not agg or z <= 0:
        return None
    return {L: p / z for L, p in agg.items()}


@dataclass
class Decision:
    ranked: List[tuple] = field(default_factory=list)  # [(intent_code, prob)] 降序
    shortlist_ok: bool = False
    n_options: int = 0
    shortlist_ms: int = 0
    choose_ms: int = 0


class DecisionMatcher:
    def __init__(self, timeout_s: float = 6.0, k: int = 20, shortlist_size: int = 8):
        self.timeout_s = timeout_s
        self.k = min(k, len(LETTERS))
        self.shortlist_size = shortlist_size

    async def _call(self, payload: Dict[str, Any], body_validator=None, content_validator=None) -> Dict[str, Any]:
        from common.llm_metrics import llm_caller_context  # type: ignore
        from common.llm_router import SLOT, call_chain  # type: ignore

        with llm_caller_context("ai_decision"):
            return await call_chain(SLOT.DECISION, payload, timeout=self.timeout_s,
                                    total_timeout=self.timeout_s, body_validator=body_validator,
                                    content_validator=content_validator)

    async def shortlist(self, query: str, role: Optional[str], visible: List[Dict[str, Any]],
                        history: Optional[List[Dict[str, Any]]] = None) -> Optional[List[str]]:
        codes = {str(r.get("intent_code")) for r in visible}

        def valid(content: str) -> Optional[str]:
            return None if parse_shortlist(content, codes, self.shortlist_size) is not None else "bad_shortlist_json"

        payload = {"messages": build_shortlist_messages(query, role, visible, self.shortlist_size, history),
                   "max_tokens": 160}
        try:
            body = await self._call(payload, content_validator=valid)
        except Exception as ex:  # noqa: BLE001
            logger.warning("decision shortlist failed: %s", str(ex)[:300])
            return None
        content = ((body.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
        return parse_shortlist(content, codes, self.shortlist_size)

    async def choose(self, query: str, role: Optional[str], options: List[Dict[str, Any]],
                     history: Optional[List[Dict[str, Any]]] = None) -> Optional[Dict[str, float]]:
        """返回 {intent_code: 概率}; 整条链都拿不到概率 -> None。"""
        if not options:
            return None
        opts = options[: self.k]
        if len(opts) == 1:
            return {str(opts[0]["intent_code"]): 1.0}
        opts = opts[:]
        random.Random(query).shuffle(opts)  # 固定打乱, 消除「爱选第一个」(与评测一致)
        n = len(opts)

        def has_probs(body: Dict[str, Any]) -> Optional[str]:
            return None if letter_probs(body, n) else "no_option_letter_in_top_logprobs"

        payload = {"messages": build_choice_messages(query, role, opts, history), "max_tokens": 1,
                   "logprobs": True, "top_logprobs": 5}
        try:
            body = await self._call(payload, body_validator=has_probs)
        except Exception as ex:  # noqa: BLE001
            logger.warning("decision choose failed: %s", str(ex)[:300])
            return None
        probs = letter_probs(body, n) or {}
        return {str(r["intent_code"]): probs.get(L, 0.0) for L, r in zip(LETTERS, opts)}


__all__ = ["DecisionMatcher", "Decision", "letter_probs", "parse_shortlist", "build_choice_messages",
           "build_shortlist_messages", "intent_text", "role_line", "ROLE_LABELS", "history_block"]
