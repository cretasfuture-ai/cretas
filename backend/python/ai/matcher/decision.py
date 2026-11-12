"""第②层: 读概率决策 (decision-model 模式)。

2026-11-12: 配了加拿大出口(LLM_EGRESS_PROXY)+ OpenAI key 时, 细选先走 OpenAI Decisions
(gpt-6-luna, 原生全选项概率, `call_decision_choice`), 失败再回落下面第 2 步的读 logprobs 链;
没配时与原来完全一样, 只走阿里云 qwen 读 logprobs 链。粗选仍是 SLOT.DECISION 的短 JSON 生成。

两次快调用, 都走 SLOT.DECISION (qwen3.7-flash 链头):
  1. 粗选: 全目录 (代码｜名称) -> 最多 8 个候选 (短 JSON 生成; 目录是静态前缀, 吃隐式缓存)
  2. 细选: 候选 (粗选 + 向量补足, 最多 20 个) 编成字母 A-T, 前缀续写「答案：」,
     max_tokens=1, 读首 token 的 top_logprobs -> 每个候选一个概率

输出是**概率分布**, 不是一个答案: 置信够高才自动执行, 不够就把前几名交给第③层
(按钮澄清)。⛔ 拿不到概率时返回 None, 由编排器回落旧路径 —— 不猜「默认第一个」。

提示词起点是 2026-10-02 的离线评测 (scripts/jev-oss-eval/sim/mine_eval.py 的 Shortlist /
Chooser)。粗选 system 提示至今逐字相同; 之后线上加了最近对话 (#875) 和「以上都不是 /
需要先问清楚」出口 (#880), 评测脚本没有跟进, 两边已不再逐字一致。改这里要同步重跑评测。
"""
from __future__ import annotations

import json
import logging
import math
import random
import re
from enum import Enum
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

LETTERS = "ABCDEFGHIJKLMNOPQRST"

# 两个特殊出口 (2026-10-03): 没有它们时模型只能在候选里硬选一个 ——「先别入库」也得挑个功能,
# 这是曲解的主要来源。它们排在真实候选之后 (不参与打乱), 选项代码用 NONE / CLARIFY,
# 返回给调用方时换成不会与意图码撞名的键。
NONE_KEY = "__NONE__"
CLARIFY_KEY = "__CLARIFY__"
SPECIAL_OPTIONS = [
    {"intent_code": "NONE", "intent_name": "以上都不是",
     "description": "这句话和上面的功能都对不上，或者用户明确说不要做、先别做"},
    {"intent_code": "CLARIFY", "intent_name": "需要先问清楚",
     "description": "上面有两个以上的功能都说得通，或者缺了关键信息必须先问用户"},
]
_SPECIAL_KEYS = {"NONE": NONE_KEY, "CLARIFY": CLARIFY_KEY}
INSTR = "用户这句话应该交给下面哪个系统功能来处理？"
STRICT_SYSTEM = "你是业务系统的请求分类器。只输出一个大写选项字母，不要换行、不要空格、不要任何其他字符。"
REQUEST_PURPOSE_RULES = (
    "先判断用户此刻想得到什么，再选功能；提到某功能不等于要求现在调用它。"
    "询问现有业务的事实、数量、状态、比较，或根据最近对话补充、纠正一个明确请求，"
    "属于当前业务请求；间接表达的真实查询也要理解，不要只因用了条件词就拒绝。"
    "只问界面位置、按钮怎么点、操作教程、应该找谁，或讨论尚未成立的条件、未来愿望、"
    "是否应该采取某个动作，不能改解成现在查询或更改业务数据。"
    "这类话选择 NONE（当前没有要调用的业务功能），意愿或指代仍不清楚则选 CLARIFY；"
    "不要擅自补出用户未表达的操作意愿。"
    "明确询问整个系统有什么功能可以选择使用帮助；它不是具体业务操作教程的替代答案。"
    "条件已经满足且明确要求现在办理，或上下文已确认要办理的请求，应选择对应业务功能。"
    "对当前业务请求，保留用户要处理的具体对象、动作和范围；"
    "不要把具体对象的数据查询扩大成全局总览，也不要把明确的登记或修改改解成查询已有记录。"
)

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


# ===== 任务状态 (INTENT-DISTILL-20261111 第二批) =====
# 字段白名单写死, 与 Java TaskStateSummary.TOP_KEYS / TASK_KEYS 一致; 白名单外的字段一律丢掉, 不进提示词。
TASK_STATE_TOP_KEYS = frozenset({"tasks"})
TASK_STATE_TASK_KEYS = frozenset({"level", "intentCode", "params"})
TASK_STATE_ROLE = "task_state"
MAX_TASK_STATE_TASKS = 4
MAX_TASK_STATE_PARAMS = 8
MAX_TASK_STATE_VALUE_CHARS = 40
_TASK_INTENT = re.compile(r"^[A-Z][A-Z0-9_]{1,79}$")
_TASK_PARAM_KEY = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,39}$")


def sanitize_task_state(state: Any) -> List[Dict[str, Any]]:
    """按白名单重建任务列表; 结构不对的整层丢掉。返回 [] = 不带任务状态。"""
    if not isinstance(state, dict):
        return []
    tasks = state.get("tasks")
    if not isinstance(tasks, list):
        return []
    out: List[Dict[str, Any]] = []
    for task in tasks[:MAX_TASK_STATE_TASKS]:
        if not isinstance(task, dict):
            continue
        level, code, params = task.get("level"), task.get("intentCode"), task.get("params")
        if not isinstance(level, int) or isinstance(level, bool) or level < 0:
            continue
        if not isinstance(code, str) or not _TASK_INTENT.match(code):
            continue
        clean: Dict[str, Any] = {}
        for k, v in (params.items() if isinstance(params, dict) else []):
            if len(clean) >= MAX_TASK_STATE_PARAMS:
                break
            if not isinstance(k, str) or not _TASK_PARAM_KEY.match(k):
                continue
            if isinstance(v, bool):
                clean[k] = "是" if v else "否"
            elif isinstance(v, (int, float, str)):
                clean[k] = " ".join(str(v).split())[:MAX_TASK_STATE_VALUE_CHARS]
        out.append({"level": level, "intentCode": code, "params": clean})
    return out


def task_state_text(state: Any) -> str:
    """清洗后的任务状态 -> 提示词片段 (只有意图码、参数键值、层级); 没有任务时空串。"""
    tasks = sanitize_task_state(state)
    if not tasks:
        return ""
    lines = []
    for t in sorted(tasks, key=lambda x: x["level"]):
        where = "正在做" if t["level"] == 0 else f"被打断挂起（第{t['level']}层）"
        filled = "，".join(f"{k}={v}" for k, v in t["params"].items()) or "无"
        lines.append(f"{where}：{t['intentCode']}，已填 {filled}")
    return "任务状态（服务端记录，只用来判断这句话是不是在接着做某件事）：\n" + "\n".join(lines) + "\n"


def with_task_state(history: Optional[List[Dict[str, Any]]], state: Any) -> List[Dict[str, Any]]:
    """把任务状态作为一条特殊记录接在最近对话后面: 所有提示词都经 history_block, 不用改各处签名。"""
    turns = list(history or [])
    text = task_state_text(state)
    if text:
        turns.append({"role": TASK_STATE_ROLE, "content": text})
    return turns


def history_block(history: Optional[List[Dict[str, Any]]]) -> str:
    """最近对话 -> 提示词片段; 没有可用的轮次时返回空串 (提示词与不带上下文时逐字相同)。"""
    lines: List[str] = []
    state_text = ""
    for turn in history or []:
        if not isinstance(turn, dict):
            continue
        if turn.get("role") == TASK_STATE_ROLE:
            # 已在 with_task_state 里按白名单渲染过; 不占最近对话的轮数
            state_text = str(turn.get("content") or "")
            continue
        who = _SPEAKER.get(str(turn.get("role") or ""))
        text = " ".join(str(turn.get("content") or "").split())[:MAX_TURN_CHARS]
        if who and text:
            lines.append(f"{who}：{text}")
    lines = lines[-MAX_HISTORY_TURNS:]
    if not lines:
        return state_text
    return "最近对话（只用来理解下面这句话指的是什么）：\n" + "\n".join(lines) + "\n" + state_text


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


class RequestPurpose(str, Enum):
    CURRENT = "CURRENT"
    INFORMATION_ONLY = "INFORMATION_ONLY"
    UNRESOLVED = "UNRESOLVED"


def parse_request_purpose(content: str) -> Optional[RequestPurpose]:
    try:
        value = json.loads(content)
        if not isinstance(value, dict) or set(value) != {"purpose"}:
            return None
        return RequestPurpose(value["purpose"])
    except (ValueError, TypeError):
        return None


def build_purpose_messages(query: str, role: Optional[str],
                           history: Optional[List[Dict[str, Any]]] = None) -> List[Dict[str, str]]:
    system = ("判断用户当前的交际目的，不选业务功能，也不判定操作权限。" + REQUEST_PURPOSE_RULES +
              "礼貌、委婉和间接表达的实际办理请求仍是 CURRENT；已经确定办理意愿但缺少业务参数也仍是 CURRENT。"
              "只有对象和事情都清楚，且只是讲述已完成的事或致谢，才不是再次办理。"
              "简短状态回复若没有可用上文、无法判断在说哪件事，属于 UNRESOLVED，不能认定为单纯汇报。"
              "只输出 JSON，且只有 purpose 字段：CURRENT=当前请求业务事实或办理操作；"
              "INFORMATION_ONLY=只咨询教程、讨论未触发条件/建议/愿望、否定或报告已完成操作；"
              "UNRESOLVED=无法判断当前是否要办理或对话指代仍不明。")
    return [{"role":"system", "content":system},
            {"role":"user", "content":f"{role_line(role)}\n{history_block(history)}用户说：{query}"}]


# ===== 合并理解 (INTENT-DISTILL E, 2026-11-11) =====
# 「请求目的 / 候选粗选 / 对话动作 / 参数理解 / 是否要先问」原来是 2-3 次分开的生成调用
# (目的 ‖ 粗选, 之后再选), 合成一次结构化输出。⛔ 输出只是候选: 权限过滤、读写判定、
# 参数落地校验仍在 Java 侧照旧执行; 这里给出的参数只带原话证据, 不替 Java 绑定。
DIALOGUE_ACTS = ("NEW_TASK", "CHANGE_PARAM", "PAGE", "CANCEL", "NEED_CLARIFY")
PARAM_SOURCES = ("explicit_turn", "confirmed", "default")
UNDERSTAND_RULES = (
    "对话动作：NEW_TASK=开始新任务或换了目标；CHANGE_PARAM=沿用上一轮任务，只改条件/口径/对象，"
    "或回答系统正在追问的字段；PAGE=对上一轮结果翻页；CANCEL=放弃当前任务（算了/不查了/先别弄）；"
    "NEED_CLARIFY=必须先问用户才能办。"
    "需要先问的情形：只有含糊动词（弄/处理/整/搞/安排），说不出是查看还是新建、修改、审批，"
    "而这个对象既有查询功能又有改数据的功能；两个以上含义不同的功能都说得通且上文排除不了；"
    "用「这个/那批/这单」指代对象但上文对不上；系统正在追问某个字段，这句话却没有给出它；"
    "说法命中易混口径且没有限定词能分开。"
    "不需要问的情形：功能确定，缺的只是有默认值的条件（时间范围、条数、排序、页码），直接办；"
    "上文能对上的续句，按上一轮任务续接：CHANGE_PARAM 和 PAGE 的 target 就是上一轮在办的那个功能，"
    "翻页、改页码、改每页条数由系统换算，不需要目录里有翻页功能，也不要因此去问。"
    "动词决定读写方向：查询类与新建/修改/删除/确认类差一两个字意图就完全不同，不要替用户补出没说的操作意愿。"
)


def build_understand_messages(query: str, role: Optional[str], visible: List[Dict[str, Any]],
                              size: int = 8, history: Optional[List[Dict[str, Any]]] = None) -> List[Dict[str, str]]:
    """一次调用的提示词。目录放 system 最前面 (与粗选同一份静态前缀, 吃隐式缓存), 规则随后。"""
    catalog = "\n".join(
        _catalog_line(r) for r in sorted(visible, key=lambda r: str(r.get("intent_code")))
    )
    system = (
        "你是食品加工厂业务系统的对话理解器。下面是全部意图目录（代码｜名称）：\n" + catalog + "\n\n"
        + REQUEST_PURPOSE_RULES
        + "purpose：CURRENT=当前请求业务事实或办理操作（礼貌、委婉、间接的真实请求也算；已确定要办但缺参数也算）；"
          "INFORMATION_ONLY=只咨询教程、讨论未触发的条件/建议/愿望、否定或汇报已完成的事；"
          "UNRESOLVED=无法判断当前是否要办理或指代不明。"
        + UNDERSTAND_RULES
        + "只输出一个 JSON 对象，字段："
          '"purpose"；"dialogue_act"（' + "/".join(DIALOGUE_ACTS) + '）；'
          f'"candidates"（目录里的代码，最多 {size} 个，按可能性从高到低，都不像就给空数组）；'
          '"target"（最应该交给的代码，CANCEL 或拿不准时给 null）；'
          '"confidence"（0 到 1，你对 target 就是用户要的功能有多大把握）；'
          '"alternatives"（除 target 外同样说得通、含义不同的代码，没有就给空数组）；'
          '"clarify_probability"（0 到 1，这一轮必须先问用户才能办的可能性）；'
          '"clarify_reason"（一句话，不需要问就给空串）；'
          '"parameters"（数组，每项 {"name":条件名,"value":值,"source":"explicit_turn|confirmed|default",'
          '"evidence":本轮原话里对应的原文片段，source 不是 explicit_turn 时给空串}；'
          "explicit_turn=这句话明确说了，confirmed=沿用上文已确认的值，default=用户没说、按默认值；"
          "只列这句话或上文真的涉及的条件，不要编造）。"
    )
    return [{"role": "system", "content": system},
            {"role": "user", "content": f"{role_line(role)}\n{history_block(history)}用户说：{query}"}]


@dataclass
class Understanding:
    purpose: "RequestPurpose"
    act: str
    candidates: List[str]
    target: Optional[str]
    confidence: float
    alternatives: List[str]
    clarify_probability: float
    clarify_reason: str
    parameters: List[Dict[str, str]]


def _unit(v: Any) -> Optional[float]:
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(float(v)):
        return None
    return float(v) if 0.0 <= float(v) <= 1.0 else None


def parse_understanding(raw: str, visible_codes: set, size: int = 8) -> Optional[Understanding]:
    """严格解析: 字段缺失 / 类型不对 / 概率越界 -> None (调用方按「拿不到」处理, 不猜)。

    目录外的代码直接丢掉 (不让模型凭空造一个意图码); 证据不在原话里的参数由 Java 落地校验处理,
    这里只做形状检查。
    """
    txt = (raw or "").strip().strip("`")
    if txt[:4].lower() == "json":
        txt = txt[4:]
    try:
        obj = json.loads(txt.strip())
    except Exception:  # noqa: BLE001
        return None
    if not isinstance(obj, dict):
        return None
    try:
        purpose = RequestPurpose(obj.get("purpose"))
    except (ValueError, TypeError):
        return None
    act = obj.get("dialogue_act")
    conf, p_clarify = _unit(obj.get("confidence")), _unit(obj.get("clarify_probability"))
    cands, alts, params = obj.get("candidates"), obj.get("alternatives", []), obj.get("parameters", [])
    if act not in DIALOGUE_ACTS or conf is None or p_clarify is None:
        return None
    if not isinstance(cands, list) or not isinstance(alts, list) or not isinstance(params, list):
        return None
    target = obj.get("target")
    target = str(target) if target not in (None, "") else None
    if target is not None and target not in visible_codes:
        target = None
    out: List[str] = []
    for c in ([target] if target else []) + [str(x) for x in cands]:
        if c in visible_codes and c not in out:
            out.append(c)
    clean_params: List[Dict[str, str]] = []
    for p in params:
        if not isinstance(p, dict) or not p.get("name"):
            continue
        src = p.get("source") if p.get("source") in PARAM_SOURCES else None
        if src is None:
            continue
        clean_params.append({"name": str(p["name"])[:40], "value": str(p.get("value", ""))[:80], "source": src,
                             "evidence": str(p.get("evidence") or "")[:80]})
    return Understanding(
        purpose=purpose, act=act, candidates=out[:size], target=target, confidence=conf,
        alternatives=[str(a) for a in alts if str(a) in visible_codes and str(a) != target][:size],
        clarify_probability=p_clarify, clarify_reason=str(obj.get("clarify_reason") or "")[:120],
        parameters=clean_params[:12],
    )


class DecisionMatcher:
    def __init__(self, timeout_s: float = 6.0, k: int = 20, shortlist_size: int = 8):
        self.timeout_s = timeout_s
        self.k = min(k, len(LETTERS))
        self.shortlist_size = shortlist_size

    async def _call(self, payload: Dict[str, Any], body_validator=None, content_validator=None) -> Dict[str, Any]:
        from common.llm_metrics import llm_caller_context  # type: ignore
        from common.llm_redactor import utterance_egress  # type: ignore
        from common.llm_router import SLOT, call_chain  # type: ignore

        # 按原话识别意图: 外发只抹号码, 不认公司名人名 (与 Java LlmEgressGuard.utterance 一致)
        with llm_caller_context("ai_decision"), utterance_egress():
            return await call_chain(SLOT.DECISION, payload, timeout=self.timeout_s,
                                    total_timeout=self.timeout_s, body_validator=body_validator,
                                    content_validator=content_validator)

    async def _choose_native(self, messages: List[Dict[str, Any]], letters: str,
                             opts: List[Dict[str, Any]]) -> Optional[Dict[str, float]]:
        """Decisions API 的原生选项概率 {字母: 概率}; 任何失败 -> None (由调用方回落读 logprobs 链)。"""
        from common.llm_metrics import llm_caller_context  # type: ignore
        from common.llm_redactor import utterance_egress  # type: ignore
        from common.llm_router import call_decision_choice, decision_choice_enabled  # type: ignore

        if not decision_choice_enabled():
            return None  # 没有加拿大出口 / 没有 key: 就是线上原来的阿里云 qwen 读概率链, 不白等一跳

        # 选项说明与 E 线评测同形 (意图码：完整说明); 单次上限留出一半预算给回落链 (Luna 实测中位 0.37s)
        choices = [(L, f"{r['intent_code']}：{intent_text(r)}") for L, r in zip(letters, opts)]
        from ai import legacy_exit_metrics as exit_metrics  # 只计数 (INTENT-DISTILL-20261111)

        exit_metrics.record_luna_request()
        try:
            with llm_caller_context("ai_decision"), utterance_egress():
                dist = await call_decision_choice(messages, choices, name="intent",
                                                  timeout=min(3.0, self.timeout_s / 2))
        except Exception as ex:  # noqa: BLE001
            logger.warning("decision native choice unavailable, falling back to logprobs: %s", str(ex)[:300])
            exit_metrics.set_luna_failure_reason(exit_metrics.classify_exception(ex))
            return None
        if dist is None:
            exit_metrics.set_luna_failure_reason("no_probs")
        return dist

    async def request_purpose(self, query: str, role: Optional[str],
                              history: Optional[List[Dict[str, Any]]] = None) -> Optional[RequestPurpose]:
        """Generated semantic disposition, never an invented probability or permission grant.

        Separate from the function chooser: related function names cannot force a
        tutorial/hypothetical statement into an active request. Missing model evidence
        remains unknown; the orchestrator asks instead of guessing a legacy intent.
        """
        def valid(content):
            return None if parse_request_purpose(content) is not None else "bad_request_purpose_json"
        payload={"messages":build_purpose_messages(query,role,history),"max_tokens":32}
        try:
            body=await self._call(payload,content_validator=valid)
            content=((body.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
            return parse_request_purpose(content)
        except Exception as ex:  # noqa: BLE001
            logger.warning("request purpose unavailable: %s",str(ex)[:200])
            return None

    async def understand(self, query: str, role: Optional[str], visible: List[Dict[str, Any]],
                         history: Optional[List[Dict[str, Any]]] = None) -> Optional[Understanding]:
        """一次结构化调用代替「目的 + 粗选」两次; 拿不到合法输出 -> None (调用方回落旧的分开调用)。"""
        codes = {str(r.get("intent_code")) for r in visible}

        def valid(content: str) -> Optional[str]:
            return None if parse_understanding(content, codes, self.shortlist_size) is not None else "bad_understanding_json"

        payload = {"messages": build_understand_messages(query, role, visible, self.shortlist_size, history),
                   "max_tokens": 600, "response_format": {"type": "json_object"}}
        try:
            body = await self._call(payload, content_validator=valid)
        except Exception as ex:  # noqa: BLE001
            logger.warning("decision understand failed: %s", str(ex)[:300])
            return None
        content = ((body.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
        return parse_understanding(content, codes, self.shortlist_size)

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

    def _choice_setup(self, query: str, options: List[Dict[str, Any]], special_options: bool):
        """固定打乱后的选项表 (出口固定排最后) 与特殊选项个数; 选项为空 -> None。Luna / qwen / 阿里云共用。"""
        if not options:
            return None
        n_special = len(SPECIAL_OPTIONS) if special_options else 0
        opts = options[: self.k - n_special]
        opts = opts[:]
        random.Random(query).shuffle(opts)  # 固定打乱, 消除「爱选第一个」(与评测一致)
        if n_special:
            opts = opts + SPECIAL_OPTIONS  # 出口固定排在最后
        return opts, n_special

    @staticmethod
    def _by_code(probs: Dict[str, float], opts: List[Dict[str, Any]], n_special: int) -> Dict[str, float]:
        """{字母: 概率} -> {意图码 / NONE_KEY / CLARIFY_KEY: 概率}; 没给到的选项记 0 (⛔ 不归一化)。"""
        n = len(opts)
        out: Dict[str, float] = {}
        for i, (L, r) in enumerate(zip(LETTERS, opts)):
            code = str(r["intent_code"])
            if i >= n - n_special:
                code = _SPECIAL_KEYS[code]
            out[code] = probs.get(L, 0.0)
        return out

    async def choose_luna(self, query: str, role: Optional[str], options: List[Dict[str, Any]],
                          history: Optional[List[Dict[str, Any]]] = None,
                          special_options: bool = False) -> Optional[Dict[str, float]]:
        """只问 Luna (OpenAI Decisions)。没启用 / 失败 -> None; 失败原因留在
        ``legacy_exit_metrics.get_luna_failure_reason()`` (没启用时为 None), 由调用方决定下一跳。"""
        from ai import legacy_exit_metrics as exit_metrics  # 只计数 (INTENT-DISTILL-20261111)

        exit_metrics.set_luna_failure_reason(None)
        setup = self._choice_setup(query, options, special_options)
        if setup is None:
            return None
        opts, n_special = setup
        messages = build_choice_messages(query, role, opts, history)
        probs = await self._choose_native(messages, LETTERS[:len(opts)], opts)
        if probs is None:
            return None
        exit_metrics.record_luna_outcome(None, None)  # 只有问了 Luna 且拿到概率才会走到这里
        return self._by_code(probs, opts, n_special)

    async def choose_qwen(self, query: str, role: Optional[str], options: List[Dict[str, Any]],
                          history: Optional[List[Dict[str, Any]]] = None,
                          special_options: bool = False) -> Dict[str, float]:
        """阿里云 qwen 读首 token logprobs (DECISION 链)。拿不到 -> 抛异常 (调用方计原因、走下一跳)。"""
        setup = self._choice_setup(query, options, special_options)
        if setup is None:
            raise ValueError("no options")
        opts, n_special = setup
        n = len(opts)

        def has_probs(body: Dict[str, Any]) -> Optional[str]:
            return None if letter_probs(body, n) else "no_option_letter_in_top_logprobs"

        payload = {"messages": build_choice_messages(query, role, opts, history), "max_tokens": 1,
                   "logprobs": True, "top_logprobs": 5}
        body = await self._call(payload, body_validator=has_probs)
        return self._by_code(letter_probs(body, n) or {}, opts, n_special)

    async def choose(self, query: str, role: Optional[str], options: List[Dict[str, Any]],
                     history: Optional[List[Dict[str, Any]]] = None,
                     special_options: bool = False) -> Optional[Dict[str, float]]:
        """返回 {intent_code: 概率}; 整条链都拿不到概率 -> None。

        special_options=True 时追加「以上都不是 / 需要先问清楚」两个选项, 它们的概率以
        NONE_KEY / CLARIFY_KEY 返回; 真实候选的概率**不重新归一化** —— 模型放在这两个出口上的
        把握会把真实候选的置信度拉低, 让「拿不准」自然落到按钮澄清。

        这是 ``AI_DECISION_FALLBACK=legacy`` (与合并理解路径) 用的旧链: Luna → qwen 读 logprobs。
        组合退路 (缺省) 由 orchestrator 用 ``choose_luna`` + ``ai.matcher.fallback_combo`` 自己串。
        """
        if not options:
            return None
        if len(options[: self.k]) == 1 and not special_options:
            return {str(options[0]["intent_code"]): 1.0}
        # 2026-11-12 (LLM-PROVIDER-SWITCH): 有出口时先问 OpenAI Decisions(gpt-6-luna) —— 原生全选项概率,
        # 一次调用; E 线同一套考题实测意图执行对 73.8%, 读 logprobs 的千问 64.7%。没出口 / 拿不到就走下面的
        # 阿里云 qwen 读 logprobs 链, 都拿不到 -> None。概率**不重新归一化**(与原契约一致)。
        from ai import legacy_exit_metrics as exit_metrics  # 只计数 (INTENT-DISTILL-20261111)

        out = await self.choose_luna(query, role, options, history, special_options)
        if out is not None:
            return out
        luna_reason = exit_metrics.get_luna_failure_reason()  # None = 没问 Luna
        try:
            out = await self.choose_qwen(query, role, options, history, special_options)
        except Exception as ex:  # noqa: BLE001
            logger.warning("decision choose failed: %s", str(ex)[:300])
            if luna_reason is not None:
                exit_metrics.record_luna_outcome(luna_reason, "failed_" + exit_metrics.classify_exception(ex))
            return None
        if luna_reason is not None:
            exit_metrics.record_luna_outcome(luna_reason, "ok" if any(out.values()) else "no_probs")
        return out


__all__ = ["DecisionMatcher", "Decision", "letter_probs", "parse_shortlist", "build_choice_messages",
           "NONE_KEY", "CLARIFY_KEY", "SPECIAL_OPTIONS",
           "build_shortlist_messages", "intent_text", "role_line", "ROLE_LABELS", "history_block",
           "sanitize_task_state", "task_state_text", "with_task_state", "TASK_STATE_TOP_KEYS",
           "TASK_STATE_TASK_KEYS", "TASK_STATE_ROLE"]
