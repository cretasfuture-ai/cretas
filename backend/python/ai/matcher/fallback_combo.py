"""Luna 之后的组合退路 (INTENT-DISTILL-20261111 批次 2, Steve 2026-11-12「那就按这个组合当退路接上去吧」)。

链 (由 orchestrator 在 Luna 没启用 / 失败 / 超时之后调用 ``run_fallback``):

  combo       并行: 阿里云 decision-model-preview 选意图 ‖ qwen 读概率判对话动作, 动作答案里 NEED_CLARIFY 的
              概率 = 「是否先问」; 两边合成后按 v3 口径 (只读题按默认值直接办) 用 ``decide_v3`` 定结论。
  qwen_only   阿里云没配 / 失败 ⇒ 整轮用 qwen: qwen 意图 (读 logprobs, 线上原来的 DECISION 链) + 同一个 qwen 动作。
  intent_ask  有意图分布但没有动作 (qwen 动作失败) ⇒ 保守先问: 一律出按钮, ⛔ 不直接执行。
  buttons     意图一边也拿不到 ⇒ 候选原样做成按钮让用户点 (置信度记 0, ⛔ 不编概率)。

⛔ 不回到旧向量 / 关键词出口: 每一跳的结论都来自决策模型的概率, 或者交给用户点。
写意图在退路上一律不自动执行 (见 orchestrator ``_result_from_fallback``)。

阈值 (与 Luna 的 ``decision_auto_threshold`` 分开存, 环境变量可覆盖, 见 ``FallbackThresholds.from_env``):
  clarify 0.3 / read 0.05 —— 报告 ``Documents/Cretas意图蒸馏-20261111/decision-model-compare-20261112.md`` 第 4 节
  「E 线内部半集校准」表「阿里云意图 + qwen 动作/先问（v3）」一行 (按 case id 分半, 校准 118 轮, 报数 120 轮:
  M 只读判定 28/43、C 50/63、C 该问抓到 21/21、C 误问 9/42)。同表 qwen 单用一行也是 0.3 / 0.05, 两跳共用。
  margin 0.3 / write 0.4 —— ``decide_v3`` 的缺省值, 校准时没有扫 (报告与评测脚本同值)。
"""
from __future__ import annotations

import asyncio
import logging
import os
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from ai.matcher.decision import (
    CLARIFY_KEY, LETTERS, NONE_KEY, SPECIAL_OPTIONS, STRICT_SYSTEM, history_block, intent_text, letter_probs,
    role_line,
)

logger = logging.getLogger(__name__)

# ── 对话动作与「是否先问」的提示词 (E 线评测与这里共用这一份; tests/semantic-read/nogen_pipeline.py 从这里导入) ──
ACTS = ('NEW_TASK', 'CHANGE_PARAM', 'PAGE', 'CANCEL', 'NEED_CLARIFY')
ACT_TEXT = {
    'NEW_TASK': '开始一个新任务或换了要办的事',
    'CHANGE_PARAM': '沿用上一轮任务，只改条件/对象/口径，或回答系统正在追问的字段',
    'PAGE': '对上一轮的结果翻页（下一页/上一页/第几页）',
    'CANCEL': '放弃当前任务（算了/不查了/先别弄）',
    'NEED_CLARIFY': '信息不足以判断要做什么、指代不明、口径含糊或缺了必须先问的字段，系统要先问用户',
}
CLARIFY_RULES = (
    '需要先问的情形：只有含糊动词（弄/处理/整/搞），分不出是查看还是新建、修改、审批，而这个对象既有查询又有改数据的功能；'
    '两个以上含义不同的功能都说得通且上文排除不了；用「这个/那批/这单」指代对象但上文对不上；'
    '系统正在追问某个字段，这句话却没有给出它；命中下面列出的易混口径且没有限定词能分开；'
    '功能已确定但它的必问字段在这句话和上文里都没有。'
    '不需要问：缺的只是有默认值的条件（时间范围、条数、排序、页码）；上文能对上的续句；明确取消。'
)
# Steve 2026-11-12 policy (clarify rubric v3, scripts/finetune/clarify_rubric_v3.json): ask only when the
# request may be a write or the target itself is two-way; a read whose missing conditions have defaults
# that do not mislead is executed and the defaults are stated (Java adds the default notices).
CLARIFY_RULES_V3 = (
    '只有下面几种情形才必须先问：'
    '①只有“弄一下/处理一下/整一下”这类含糊动词，说不出要查看还是要新建、修改、审批；'
    '②查询和录入/修改两种读法都说得通（如“录下……要多少”）；'
    '③接着上一轮只换了对象（如“那原料呢”），新对象有两种以上不同的查法，上文定不下；'
    '④只有“这个/那个”且上文对不上，或者缺了必填、没有合理默认的信息；'
    '⑤系统正在追问某个字段，这句话没回答也没换任务。'
    '下面这些不要问，直接办：只读查询缺的是有合理默认的条件（没说时间按最近7天、结果多先显示20条、过期=已经过期）；'
    '同名的人或物（都列出来）；系统做不到的口径（说明后给最接近的结果）；上文能对上的续句；明确的取消。'
)
META_CONTINUE = {'PAGINATION_PREVIOUS', 'PAGINATION_NEXT', 'CONTEXT_CONTINUE'}

HOPS = ('combo', 'qwen_only', 'intent_ask', 'buttons')


def decide_v3(sig: Dict[str, Any], clarify_t: float, read_t: float, prev_intent: Optional[str] = None,
              modes: Optional[Dict[str, str]] = None, margin_t: float = 0.3, write_t: float = 0.4) -> Dict[str, Any]:
    """Steve 2026-11-12 policy (rubric v3). Differs from ``nogen_pipeline.decide`` in two places only:

    - the clarify signal is the v3 Predicate (asks only for write-or-read / two-way target / unknown referent /
      unanswered pending field / vague verb);
    - a READ target executes (Java states the defaults it used) when Choice's top >= ``read_t`` or leads the
      runner-up by >= ``margin_t``; the "需要先问清楚" option alone no longer forces a question on reads.
      A WRITE target (mode not READ / NO_TOOL) still goes to confirmation, or is asked when weak.
    """
    modes = modes or {}
    act = max(sig['act'], key=sig['act'].get)
    intent = dict(sig['intent'])
    p_none = intent.pop(NONE_KEY, 0.0)
    p_clar = intent.pop(CLARIFY_KEY, 0.0)
    ranked = sorted(intent.items(), key=lambda kv: -kv[1])
    top_codes = [c for c, _ in ranked[:5]]
    top, p_top = (ranked[0] if ranked else (None, 0.0))
    p_second = ranked[1][1] if len(ranked) > 1 else 0.0
    cp = sig.get('clarify_p_v3', sig['clarify_p'])
    if act == 'CANCEL':
        return dict(outcome='NONE', intent=None, top=top_codes, act=act, reason='act_cancel')
    if cp >= clarify_t:
        return dict(outcome='CLARIFY', intent=top, top=top_codes, act=act, reason='clarify_signal')
    if prev_intent and act in ('PAGE', 'CHANGE_PARAM'):
        return dict(outcome='EXECUTE', intent=prev_intent, top=top_codes, act=act, reason='continue_prev')
    if prev_intent and top in META_CONTINUE:
        return dict(outcome='EXECUTE', intent=prev_intent, top=top_codes, act=act, reason='meta_to_prev')
    if p_none >= 0.5 and p_none > p_top:
        return dict(outcome='NONE', intent=None, top=top_codes, act=act, reason='choice_none')
    if top is None:
        return dict(outcome='CLARIFY', intent=None, top=top_codes, act=act, reason='low_choice')
    if modes.get(top, 'READ') in ('READ', 'NO_TOOL'):
        if p_top >= read_t or p_top - p_second >= margin_t:
            return dict(outcome='EXECUTE', intent=top, top=top_codes, act=act, reason='read_with_defaults')
        return dict(outcome='CLARIFY', intent=top, top=top_codes, act=act, reason='two_way_target')
    if p_top >= write_t and p_top >= p_clar:
        return dict(outcome='EXECUTE', intent=top, top=top_codes, act=act, reason='write_to_confirm')
    return dict(outcome='CLARIFY', intent=top, top=top_codes, act=act, reason='low_choice')


@dataclass(frozen=True)
class FallbackThresholds:
    clarify: float = 0.3   # qwen P(NEED_CLARIFY) >= 此值 ⇒ 先问 (报告第 4 节, v3 组合行)
    read: float = 0.05     # 只读目标首选 >= 此值 ⇒ 按默认值直接办 (同上)
    margin: float = 0.3    # 或首选领先第二名 >= 此值 (decide_v3 缺省, 未单独校准)
    write: float = 0.4     # 写目标首选 >= 此值 ⇒ 交写确认, 否则先问 (decide_v3 缺省, 未单独校准)
    aliyun_timeout_s: float = 2.0
    qwen_timeout_s: float = 3.0

    @classmethod
    def from_env(cls) -> "FallbackThresholds":
        def f(name: str, default: float) -> float:
            try:
                return float(os.environ.get(name, str(default)))
            except ValueError:
                return default
        return cls(clarify=f("AI_DECISION_FALLBACK_CLARIFY_THRESHOLD", 0.3),
                   read=f("AI_DECISION_FALLBACK_READ_THRESHOLD", 0.05),
                   margin=f("AI_DECISION_FALLBACK_MARGIN", 0.3),
                   write=f("AI_DECISION_FALLBACK_WRITE_THRESHOLD", 0.4),
                   aliyun_timeout_s=f("AI_DECISION_FALLBACK_ALIYUN_TIMEOUT_S", 2.0),
                   qwen_timeout_s=f("AI_DECISION_FALLBACK_QWEN_TIMEOUT_S", 3.0))


@dataclass
class FallbackDecision:
    hop: str                                   # combo / qwen_only / intent_ask / buttons
    outcome: str                               # EXECUTE / CLARIFY / NONE
    intent: Optional[str]
    top: List[str]
    act: Optional[str] = None
    reason: str = ''
    intent_probs: Dict[str, float] = field(default_factory=dict)   # 按钮排序用, 不含 NONE/CLARIFY
    clarify_p: Optional[float] = None
    failures: Dict[str, str] = field(default_factory=dict)         # 哪一边、为什么失败 (计数用)
    timing: Dict[str, int] = field(default_factory=dict)


def _state_text(query: str, role: Optional[str], history, options: List[Dict[str, Any]], notes: str = '') -> str:
    """与 E 线评测 ``nogen_pipeline._input_text`` 同形 (身份行 + 上文 + 原话 + 候选清单 + 须知)。"""
    opts = '\n'.join(f"{r['intent_code']}：{intent_text(r)[:60]}" for r in options)
    return f"{role_line(role)}\n{history_block(history)}用户说：{query}\n\n可能相关的功能：\n{opts}{notes}"


async def aliyun_intent(matcher, query: str, role: Optional[str], options: List[Dict[str, Any]], history,
                        notes: str = '', timeout: float = 2.0) -> Dict[str, float]:
    """阿里云 decision-model-preview 选意图: 候选 + 「以上都不是」「需要先问清楚」。失败抛异常。"""
    from common.llm_metrics import llm_caller_context  # type: ignore
    from common.llm_redactor import utterance_egress  # type: ignore
    from common.llm_router import call_aliyun_decision_choice  # type: ignore

    setup = matcher._choice_setup(query, options, True)
    if setup is None:
        raise ValueError('no options')
    opts, n_special = setup
    real = opts[:len(opts) - n_special]
    choices = [(str(r['intent_code']), intent_text(r)[:80]) for r in real]
    choices += [(NONE_KEY, SPECIAL_OPTIONS[0]['intent_name'] + '：' + SPECIAL_OPTIONS[0]['description']),
                (CLARIFY_KEY, SPECIAL_OPTIONS[1]['intent_name'] + '：' + SPECIAL_OPTIONS[1]['description'])]
    # 正文里的候选清单按原顺序 (与离线重放同形), 选项字典按固定打乱后的顺序
    listed = options[:len(real)]
    with llm_caller_context("ai_decision"), utterance_egress():
        return await call_aliyun_decision_choice(_state_text(query, role, history, listed, notes), choices,
                                                 name='intent', timeout=timeout)


async def qwen_act(matcher, query: str, role: Optional[str], history, notes: str = '') -> Dict[str, float]:
    """qwen 读首 token logprobs 判对话动作 (与 E 线 ``QwenBackend._act`` 同一提示词)。失败抛异常。"""
    opts = '\n'.join(f'{L}. {a}：{ACT_TEXT[a]}' for L, a in zip(LETTERS, ACTS))
    user = (f"{role_line(role)}\n{history_block(history)}用户说：{query}{notes}\n\n"
            f"问题：这句话在对话中是什么动作？{CLARIFY_RULES}\n选项：\n{opts}\n\n只输出一个选项字母。")
    payload = {'messages': [{'role': 'system', 'content': STRICT_SYSTEM}, {'role': 'user', 'content': user},
                            {'role': 'assistant', 'content': '答案：', 'partial': True}],
               'max_tokens': 1, 'logprobs': True, 'top_logprobs': 5}

    def has(body):
        return None if letter_probs(body, len(ACTS)) else 'no_option_letter_in_top_logprobs'
    body = await matcher._call(payload, body_validator=has)
    probs = letter_probs(body, len(ACTS)) or {}
    return {a: probs.get(L, 0.0) for L, a in zip(LETTERS, ACTS)}


def _reason(ex: BaseException) -> str:
    from ai import legacy_exit_metrics as exit_metrics
    if isinstance(ex, LookupError) and str(ex) == 'aliyun_not_configured':
        return 'not_configured'
    return exit_metrics.classify_exception(ex)


def _real_ranked(dist: Dict[str, float]) -> Dict[str, float]:
    return {k: v for k, v in dist.items() if k not in (NONE_KEY, CLARIFY_KEY)}


async def run_fallback(matcher, query: str, role: Optional[str], options: List[Dict[str, Any]], history,
                       modes: Dict[str, str], thresholds: FallbackThresholds,
                       prev_intent: Optional[str] = None, notes: str = '') -> FallbackDecision:
    """跑完退路链, 一定返回一个结论 (最差是 buttons)。⛔ 从不返回 None 让调用方回落旧出口。

    ``modes``: 意图码 -> READ / WRITE (线上用 ``ai.db._is_write_row``, 执行期写闸仍在 Java)。
    ``prev_intent``: 上一轮执行的意图; 线上 Python 拿不到 (Java 只传 role/content 的上文) ⇒ None,
    续句交给意图选择 + Java 的翻页/续句意图; 评测可传入以对齐离线读数。
    """
    from common.llm_router import aliyun_decision_enabled  # type: ignore

    t0 = time.time()
    failures: Dict[str, str] = {}
    timing: Dict[str, int] = {}

    async def _aliyun():
        if not aliyun_decision_enabled():
            raise LookupError('aliyun_not_configured')
        t = time.time()
        try:
            return await aliyun_intent(matcher, query, role, options, history, notes, thresholds.aliyun_timeout_s)
        finally:
            timing['fallbackAliyunMs'] = int((time.time() - t) * 1000)

    async def _act():
        t = time.time()
        try:
            return await asyncio.wait_for(qwen_act(matcher, query, role, history, notes), thresholds.qwen_timeout_s)
        finally:
            timing['fallbackActMs'] = int((time.time() - t) * 1000)

    async def _qwen_intent():
        t = time.time()
        try:
            return await asyncio.wait_for(matcher.choose_qwen(query, role, options, history, special_options=True),
                                          thresholds.qwen_timeout_s)
        finally:
            timing['fallbackQwenIntentMs'] = int((time.time() - t) * 1000)

    ali, act = await asyncio.gather(_aliyun(), _act(), return_exceptions=True)
    if isinstance(ali, BaseException):
        failures['aliyun_intent'] = _reason(ali)
        if not isinstance(ali, LookupError):
            logger.warning('decision fallback: aliyun intent failed: %s', str(ali)[:200])
        ali = None
    if isinstance(act, BaseException):
        failures['qwen_act'] = _reason(act)
        logger.warning('decision fallback: qwen act failed: %s', str(act)[:200])
        act = None

    intent, source = ali, 'aliyun'
    if intent is None:
        try:
            intent, source = await _qwen_intent(), 'qwen'
        except Exception as ex:  # noqa: BLE001
            failures['qwen_intent'] = _reason(ex)
            logger.warning('decision fallback: qwen intent failed: %s', str(ex)[:200])
            intent = None

    def done(fd: FallbackDecision) -> FallbackDecision:
        fd.failures, fd.timing = failures, timing
        timing['fallbackMs'] = int((time.time() - t0) * 1000)
        return fd

    if intent is None:
        codes = [str(r['intent_code']) for r in options][:5]
        return done(FallbackDecision(hop='buttons', outcome='CLARIFY', intent=codes[0] if codes else None, top=codes,
                                     reason='no_model_signal'))
    real = _real_ranked(intent)
    ranked = sorted(real, key=lambda c: -real[c])
    if act is None:
        # 只有意图没有动作: 不知道这是续句 / 取消 / 要先问 ⇒ 宁可先问, ⛔ 不直接执行
        return done(FallbackDecision(hop='intent_ask', outcome='CLARIFY', intent=ranked[0] if ranked else None,
                                     top=ranked[:5], reason='no_act_signal_' + source, intent_probs=real))
    clarify_p = float(act.get('NEED_CLARIFY', 0.0))
    sig: Dict[str, Any] = dict(intent=intent, act=act, clarify_p=clarify_p)
    d = decide_v3(sig, thresholds.clarify, thresholds.read, prev_intent=prev_intent, modes=modes,
                  margin_t=thresholds.margin, write_t=thresholds.write)
    return done(FallbackDecision(hop='combo' if source == 'aliyun' else 'qwen_only', outcome=d['outcome'],
                                 intent=d['intent'], top=d['top'], act=d['act'], reason=d['reason'],
                                 intent_probs=real, clarify_p=clarify_p))


__all__ = ['ACTS', 'ACT_TEXT', 'CLARIFY_RULES', 'CLARIFY_RULES_V3', 'META_CONTINUE', 'HOPS', 'decide_v3',
           'FallbackThresholds', 'FallbackDecision', 'aliyun_intent', 'qwen_act', 'run_fallback']
