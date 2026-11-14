"""Stage 5-8 short-circuit orchestration.

Runs stages in order: SEMANTIC → CLASSIFIER → FUSION → LLM. Short-circuits
on strong signal at each stage per Java pipeline behavior:

- Stage 5 SEMANTIC: if confidence >= AI_SEMANTIC_THRESHOLD (default 0.85),
  skip 6/7/8.
- Stage 6 CLASSIFIER: always runs if stage 5 didn't short-circuit.
- Stage 7 FUSION: combines stage 5 + 6, if confidence >= AI_FUSION_THRESHOLD
  (default 0.70), skip stage 8.
- Stage 8 LLM: only runs if FUSION didn't short-circuit AND `enable_llm=True`.
- All stages empty: return IntentMatchResultDto.empty(userInput=query).

Failure tolerance: each matcher exception is logged and treated as []. One
matcher's failure does not abort the pipeline; orchestration continues to
the next stage.

Builds final IntentMatchResultDto with all 19 top-level fields populated 1:1
with Java IntentMatchResult shape.
"""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Any, Dict, List, Optional

from ai import legacy_exit_metrics as exit_metrics
from ai import registry as registry_module
from ai.config import default_config
from ai.db import row_to_dto
from ai.factory_catalog import canonical_code, filter_runtime_rows, is_retired
from ai.dto import (
    ActionType,
    AIIntentConfigDto,
    CandidateIntentDto,
    IntentMatchResultDto,
    MatchMethod,
    QuestionType,
)
from ai.matcher import fusion as fusion_module
from ai.matcher import semantic as semantic_module
from ai.matcher.candidates import BlendRetriever
from ai.matcher.decision import with_task_state
from ai.db import _is_write_row
from ai.matcher.decision import (ASK_KEY, CLARIFY_KEY, DIALOGUE_ACTS, NONE_KEY, NON_INTENT_KEYS, RequestPurpose,
                                 intent_text)
from ai.matcher.fallback_combo import HOPS, FallbackDecision, FallbackThresholds, run_fallback

# 判「以上都不是」时给用户的话: 不执行, 并告诉他怎么继续 (说「帮助」走 SYSTEM_HELP)。
NONE_REPLY = "这句话我没对上系统里的功能，所以先不做任何操作。可以换个说法，或者说「帮助」看看我能做什么。"
# 合并理解判为取消 (CANCEL): 不执行, 也不追问。
CANCEL_REPLY = "好的，这件事先不办了。需要时再告诉我。"

logger = logging.getLogger(__name__)


class Orchestrator:
    """Composes the 4 stage matchers into a short-circuit pipeline.

    β extension: optionally accepts a SemanticRouter for pre-stage 3-tier routing.
    If router=None, behaves as α (runs all stages 5-8 short-circuit).
    """

    def __init__(self, semantic_matcher, classifier_matcher, llm_matcher,
                 semantic_router=None, llm_tier_selector=None,
                 calibrator=None, scorer=None,
                 rag_retriever=None, rag_evaluator=None,
                 decision_matcher=None, candidate_retriever=None, config=None, rerank_client=None):
        self.semantic_matcher = semantic_matcher
        self.classifier_matcher = classifier_matcher
        self.llm_matcher = llm_matcher
        self.semantic_router = semantic_router  # β: optional pre-stage router
        self.llm_tier_selector = llm_tier_selector  # β C2: optional cheap/expensive picker
        self.calibrator = calibrator  # β C4: optional per-stage confidence calibrator
        self.scorer = scorer  # β C4: optional combined-score reranker
        self.rag_retriever = rag_retriever  # β C5: optional RAG retriever
        self.rag_evaluator = rag_evaluator  # β C5: optional CRAG-style quality evaluator
        # 三层识别 (2026-10): 第②层决策器 + 候选召回器 (向量只提供候选, ⛔ 不拍板)
        self.decision_matcher = decision_matcher
        self.candidate_retriever = candidate_retriever
        self.config = config or default_config
        # 候选重排 (2026-10-04): 配了且 decision_candidates == "rerank" 才用; 失败退回 LLM 粗选
        self.rerank_client = rerank_client

    async def match(
        self,
        query: str,
        factoryId: str,
        businessType: str,
        userId: str,
        role: str,
        visible_intents: List[Dict[str, Any]],
        history: List[Dict[str, Any]],
        min_confidence: float,
        enable_llm: bool = True,
        task_state: Optional[Dict[str, Any]] = None,
    ) -> IntentMatchResultDto:
        """三层识别对开启的业态生效; 其余业态 (以及三层拿不到结果时) 走旧的 5-8 级。

        ``task_state``: 服务端任务状态摘要 (Java TaskStateSummary 白名单), 只给决策层提示词; 旧 5-8 级不用。
        """
        # Matchers/classifiers may return historical head IDs; final matching
        # must still obey the reviewed retirement policy while preserving
        # tenant-owned non-restaurant custom rows.
        visible_intents = filter_runtime_rows(visible_intents)
        if (self.decision_matcher is not None and enable_llm
                and self.config.decision_enabled_for(businessType)):
            # 旧出口计数 (INTENT-DISTILL-20261111): 只计数、只打日志, 不改结果
            exit_metrics.record_decision_site_request()
            exit_metrics.set_decision_none_reason(None)
            exit_metrics.set_luna_failure_reason(None)
            fallback_reason = None
            try:
                result = await self._match_decision(query, factoryId, businessType, role, visible_intents,
                                                    history, task_state=task_state)
            except Exception as ex:
                logger.exception("decision layer failed, falling back to legacy stages")
                fallback_reason = "decision_" + exit_metrics.classify_exception(ex)
                result = None
            if result is not None:
                exit_metrics.observe_decision_answered()
                return result
            legacy = await self._match_legacy(query, factoryId, businessType, userId, role, visible_intents,
                                              history, min_confidence, enable_llm)
            # 回落要留痕: 「三层答的」与「三层没答出来、旧路径答的」在结果上必须分得开
            legacy.timingMs = {**(legacy.timingMs or {}), "decisionFallback": 1}
            exit_metrics.record_legacy_exit(
                legacy, factoryId,
                fallback_reason or exit_metrics.get_decision_none_reason() or "decision_returned_none",
                visible_intents)
            return legacy
        legacy = await self._match_legacy(query, factoryId, businessType, userId, role, visible_intents,
                                          history, min_confidence, enable_llm)
        # 决策层没对这个业态 / 这次请求开 ⇒ 旧四级就是主路径 (配置使然, 不进告警分子)
        exit_metrics.record_legacy_exit(
            legacy, factoryId,
            "llm_disabled" if (self.decision_matcher is not None and not enable_llm) else "decision_not_enabled",
            visible_intents, observe_alarm=False)
        return legacy

    async def _match_decision(
        self,
        query: str,
        factoryId: str,
        businessType: str,
        role: str,
        visible_intents: List[Dict[str, Any]],
        history: Optional[List[Dict[str, Any]]] = None,
        task_state: Optional[Dict[str, Any]] = None,
    ) -> Optional[IntentMatchResultDto]:
        """① 已审核精确表 → ② 读概率决策 → ③ 置信不够则 requiresConfirmation (Java 出按钮)。

        ``history`` (最近几轮对话) 进粗选与决策两个提示词; 精确表与向量召回只看这一句本身。

        返回 None = 这一路没有产出 (无候选 / 决策链拿不到概率), 调用方回落旧路径。
        """
        t0 = time.time()
        timing: Dict[str, int] = {"decisionLayer": 1, "historyTurns": len(history or [])}
        # 最近对话只看真实轮次 (计数、是否跳过重排); 提示词里用接上了任务状态的那份 (白名单清洗在 with_task_state)
        turns = history
        history = with_task_state(turns, task_state)
        if len(history) > len(turns or []):
            timing["taskState"] = 1
        by_code = {str(r.get("intent_code")): r for r in visible_intents}
        if not by_code:
            exit_metrics.set_decision_none_reason("decision_no_visible_intents")
            return None

        # ===== ① 精确表: 归一化后整句相等, 只认 APPROVED =====
        snap = registry_module.get_registry()
        if snap.available:
            t = time.time()
            code, _key = registry_module.lookup(snap, query, factoryId, businessType)
            timing["registryMs"] = int((time.time() - t) * 1000)
            if code and code in by_code:
                row = by_code[code]
                timing["registryHit"] = 1
                timing["totalMs"] = int((time.time() - t0) * 1000)
                cand = CandidateIntentDto(
                    intentCode=code, intentName=row.get("intent_name") or code,
                    intentCategory=row.get("intent_category"), confidence=1.0,
                    matchMethod=MatchMethod.EXACT, description=row.get("description"),
                )
                return self._build_result(query=query, top_candidates=[cand], method=MatchMethod.EXACT,
                                          visible_intents=visible_intents, min_confidence=0.0, timing=timing)

        retriever = self.candidate_retriever or self.semantic_matcher
        if getattr(self.config, "decision_merged", False):
            merged = await self._match_merged(query, factoryId, businessType, role, visible_intents, history,
                                              retriever, by_code, timing, t0)
            if merged is not None:
                return merged
            # 合并调用拿不到合法输出 ⇒ 回落下面原来的分开调用 (留痕, 不猜)
            timing["mergedFallback"] = 1
        # Determine the speech act separately from choosing a related function.
        # This generates a semantic disposition, not confidence or execution permission.
        # organizer 2026-10-09: 与候选召回、决策选择并行跑 (串行时 p50 684→1124ms); 只在用到结论时才 await。
        purpose_task = None
        if bool(getattr(self.config, "decision_special_options", False)):
            purpose_started = time.time()
            purpose_task = asyncio.ensure_future(self.decision_matcher.request_purpose(query, role, history))

        async def purpose_block() -> Optional[IntentMatchResultDto]:
            """purpose != CURRENT ⇒ 返回「先问清楚」的结论; CURRENT 或未启用 ⇒ None (继续原决策)。"""
            if purpose_task is None:
                return None
            purpose = await purpose_task
            timing["requestPurposeMs"] = int((time.time() - purpose_started) * 1000)
            return self._purpose_result(purpose, query, timing, t0)

        # ===== ② 候选 (重排路): 向量 Top-N → 微调重排 → 前 K, 0 token =====
        # 带对话历史的轮次不走这条: 重排只看这一句, LLM 粗选能看到前文。
        codes = None
        if (getattr(self.config, "decision_candidates", "shortlist") == "rerank" and self.rerank_client is not None
                and isinstance(retriever, BlendRetriever)):
            if turns:
                timing["rerankSkippedHistory"] = 1
            else:
                codes = await self._rerank_candidates(query, factoryId, businessType, retriever, by_code, timing)

        # ===== ② 候选: LLM 粗选 ‖ 向量召回 (并行), 合并去重 =====
        t = time.time()
        if codes is None:
            sem, short = await asyncio.gather(
                retriever.match(query, factoryId=factoryId, businessType=businessType),
                self.decision_matcher.shortlist(query, role, visible_intents, history),
                return_exceptions=True,
            )
            timing["retrievalMs"] = int((time.time() - t) * 1000)
            if isinstance(sem, BaseException):
                logger.warning("decision: candidate retrieval failed: %s", sem)
                sem = []
            if isinstance(short, BaseException):
                short = None
            timing["shortlistOk"] = 0 if short is None else 1
            codes = []
            for c in list(short or []) + [s.intentCode for s in sem]:
                if c in by_code and c not in codes:
                    codes.append(c)
                if len(codes) >= self.config.decision_k:
                    break
        if not codes:
            exit_metrics.set_decision_none_reason("decision_no_candidates")
            return await purpose_block()

        # ===== ② 决策: 读概率 =====
        t = time.time()
        special = bool(getattr(self.config, "decision_special_options", False))
        fallback = None
        if getattr(self.config, "decision_fallback", "legacy") == "combo":
            # Luna → 组合退路 (阿里云意图 ‖ qwen 动作/先问 → qwen 单用 → 保守先问 → 按钮), 见 fallback_combo
            options = [by_code[c] for c in codes]
            if len(options) == 1 and not special:
                probs = {codes[0]: 1.0}
            else:
                probs = await self.decision_matcher.choose_luna(
                    query, role, options, history, special_options=special,
                    ask=getattr(self.config, "decision_luna_policy", "v1") == "v3")
            if probs is None:
                luna_reason = exit_metrics.get_luna_failure_reason()  # None = 没启用 Luna
                modes = {c: ("WRITE" if _is_write_row(by_code[c]) else "READ") for c in codes}
                fallback = await run_fallback(self.decision_matcher, query, role, options, history, modes,
                                              FallbackThresholds.from_env())
                exit_metrics.record_fallback(fallback.hop, fallback.outcome, fallback.failures)
                if luna_reason is not None:
                    exit_metrics.record_luna_outcome(luna_reason, "fallback_" + fallback.hop)
        elif special:
            probs = await self.decision_matcher.choose(query, role, [by_code[c] for c in codes], history,
                                                       special_options=True)
        else:
            probs = await self.decision_matcher.choose(query, role, [by_code[c] for c in codes], history)
        timing["decisionMs"] = int((time.time() - t) * 1000)
        # 说话意图不是「现在办」⇒ 不采用刚选出的业务意图 (结论在这里才用到, 前面一直在并行跑)
        purpose_result = await purpose_block()
        if purpose_result is not None:
            return purpose_result
        if fallback is not None:
            return self._result_from_fallback(fallback, query, by_code, visible_intents, timing, t0)
        if not probs:
            exit_metrics.set_decision_none_reason(
                "decision_no_probs_" + (exit_metrics.get_luna_failure_reason() and "after_luna" or "qwen_only"))
            return None
        return self._result_from_probs(probs, special, query, by_code, visible_intents, timing, t0)

    async def _match_merged(self, query, factoryId, businessType, role, visible_intents, history,
                            retriever, by_code, timing, t0) -> Optional[IntentMatchResultDto]:
        """合并理解 (1 次) → 只在需要时 Choice (第 2 次)。返回 None = 合并输出不可用, 调用方走分开调用。

        Choice 触发条件 (INTENT-DISTILL E, 门槛来由见 config / PR 回执):
          - 本轮要先问 (clarify_probability >= decision_clarify_threshold): 按钮要真实概率排序;
          - 没有 target, 或 target 把握 < decision_choice_below。
        「给出了 alternatives」**不**触发: 开发集上它几乎每轮都有 (ORDER_LIST 旁边总挂着 ORDER_STATUS),
        触发后 Choice 的分布很平、过不了 0.8 自动门槛, 72% 的轮次变成多余的澄清。
        调了 Choice 且不是先问: Choice 首选 == 合并 target ⇒ 两个独立判断一致, 执行; 否则按原 Choice 规则
        (以上都不是 / 先问清楚 / 置信不够出按钮)。
        其余轮次直接按合并输出的 target 交给 Java; 权限过滤 / 读写判定 / 参数落地校验仍在 Java 侧照旧执行。
        """
        t = time.time()
        sem_task = asyncio.ensure_future(retriever.match(query, factoryId=factoryId, businessType=businessType))
        u = await self.decision_matcher.understand(query, role, visible_intents, history)
        timing["understandMs"] = int((time.time() - t) * 1000)
        if u is None:
            sem_task.cancel()
            return None
        try:
            sem = await sem_task
        except Exception as ex:  # noqa: BLE001
            logger.warning("decision: candidate retrieval failed: %s", ex)
            sem = []
        timing["merged"] = 1
        timing["mergedAct"] = DIALOGUE_ACTS.index(u.act)
        timing["mergedConfidence"] = int(round(u.confidence * 1000))
        timing["mergedClarifyP"] = int(round(u.clarify_probability * 1000))
        # 「先不查了」类: 模型常同时判 purpose=INFORMATION_ONLY (否定), 先认取消, 否则会被改成追问
        if u.act == "CANCEL":
            timing["mergedCancel"] = 1
            return self._none_result(query, timing, t0, CANCEL_REPLY)
        purpose_result = self._purpose_result(u.purpose, query, timing, t0)
        if purpose_result is not None:
            return purpose_result
        codes: List[str] = []
        for c in list(u.candidates) + list(u.alternatives) + [s.intentCode for s in sem]:
            if c in by_code and c not in codes:
                codes.append(c)
            if len(codes) >= self.config.decision_k:
                break
        if not codes:
            return None
        clarify = u.clarify_probability >= self.config.decision_clarify_threshold
        need_choice = (clarify or u.target is None or u.target not in by_code
                       or u.confidence < self.config.decision_choice_below)
        if need_choice:
            t = time.time()
            probs = await self.decision_matcher.choose(query, role, [by_code[c] for c in codes], history,
                                                       special_options=True)
            timing["decisionMs"] = int((time.time() - t) * 1000)
            timing["choiceCalled"] = 1
            if not probs:
                return None
            if clarify:
                timing["mergedClarify"] = 1
                probs = {k: v for k, v in probs.items() if k not in NON_INTENT_KEYS}
                probs[CLARIFY_KEY] = 1.0  # 先问的结论来自合并输出; Choice 只给按钮排序
                return self._result_from_probs(probs, True, query, by_code, visible_intents, timing, t0)
            real = {k: v for k, v in probs.items() if k not in NON_INTENT_KEYS}
            if not (u.target and real and max(real, key=real.get) == u.target):
                return self._result_from_probs(probs, True, query, by_code, visible_intents, timing, t0)
            timing["choiceAgrees"] = 1
        else:
            timing["choiceSkipped"] = 1
        row = by_code[u.target]
        cand = CandidateIntentDto(
            intentCode=u.target, intentName=row.get("intent_name") or u.target,
            intentCategory=row.get("intent_category"), confidence=round(float(u.confidence), 4),
            matchMethod=MatchMethod.LLM, description=row.get("description"),
        )
        timing["totalMs"] = int((time.time() - t0) * 1000)
        return self._build_result(query=query, top_candidates=[cand], method=MatchMethod.LLM,
                                  visible_intents=visible_intents, min_confidence=0.0, timing=timing)

    @staticmethod
    def _purpose_result(purpose: Optional[RequestPurpose], query: str, timing: Dict[str, int],
                        t0: float) -> Optional[IntentMatchResultDto]:
        """purpose != CURRENT ⇒「先问清楚」的结论; CURRENT ⇒ None。分开调用与合并调用共用这一份。"""
        if purpose == RequestPurpose.CURRENT:
            return None
        result = IntentMatchResultDto.empty(userInput=query)
        result.matchMethod = MatchMethod.LLM
        result.questionType = QuestionType.CONVERSATIONAL
        # Java recognizes this as an explicit no-business-action conclusion,
        # rather than falling back to its legacy keyword/vector guesses.
        timing["decisionNone"] = 1
        if purpose == RequestPurpose.INFORMATION_ONLY:
            timing["requestPurposeInformation"] = 1
            result.requiresConfirmation = True
            result.clarificationQuestion = "您想了解哪个功能的操作步骤，或需要我现在处理什么？请再说明一下。"
        else:
            timing["requestPurposeUnresolved" if purpose is not None else "requestPurposeUnavailable"] = 1
            result.requiresConfirmation = True
            result.clarificationQuestion = ("您是在讨论操作方法，还是希望我现在查询或办理？请再说明一下。"
                                            if purpose is not None else
                                            "系统暂时无法确认您的请求，请稍后重试。当前不会进行业务操作。")
        timing["totalMs"] = int((time.time() - t0) * 1000)
        result.timingMs = timing
        return result

    def _none_result(self, query: str, timing: Dict[str, int], t0: float,
                     message: str = NONE_REPLY) -> IntentMatchResultDto:
        timing["decisionNone"] = 1
        timing["totalMs"] = int((time.time() - t0) * 1000)
        result = IntentMatchResultDto.empty(userInput=query)
        result.matchMethod = MatchMethod.LLM
        result.questionType = QuestionType.CONVERSATIONAL
        result.conversationMessage = message
        result.timingMs = timing
        return result

    def _result_from_probs(self, probs: Dict[str, float], special: bool, query: str, by_code: Dict[str, Any],
                           visible_intents: List[Dict[str, Any]], timing: Dict[str, int],
                           t0: float) -> IntentMatchResultDto:
        """Choice 概率 -> 结论 (以上都不是 / 先问清楚 / 置信不够出按钮 / 执行)。两条路径共用。"""
        probs = dict(probs)
        p_none = float(probs.pop(NONE_KEY, 0.0) or 0.0)
        p_clarify = float(probs.pop(CLARIFY_KEY, 0.0) or 0.0)
        p_ask = probs.pop(ASK_KEY, None)
        ranked = sorted(probs.items(), key=lambda kv: -kv[1])
        top_real = ranked[0][1] if ranked else 0.0
        if special:
            timing["decisionNoneP"] = int(round(p_none * 1000))
            timing["decisionClarifyP"] = int(round(p_clarify * 1000))
        # 「以上都不是」最大且过半 → 明确判无对应功能: 不执行、不回落旧路径猜一个 (Java 认 decisionNone)
        if special and p_none >= 0.5 and p_none > top_real and p_none >= p_clarify:
            return self._none_result(query, timing, t0)
        # 「需要先问清楚」最大 → 一律出按钮 (不管真实第一名的概率是多少)
        force_clarify = special and p_clarify > top_real and p_clarify >= p_none
        auto_threshold = self.config.decision_auto_threshold
        if p_ask is not None and ranked:
            # 第三批 v3 (Luna 判断题在手): 先问 / 按默认直接办 / 写确认卡, 见 AIConfig.decision_luna_policy
            timing["lunaPolicyV3"] = 1
            timing["lunaAskP"] = int(round(float(p_ask) * 1000))
            top_code, p_top = ranked[0]
            p_second = ranked[1][1] if len(ranked) > 1 else 0.0
            if float(p_ask) >= self.config.luna_ask_threshold:
                timing["lunaAsk"] = 1
                force_clarify = True
            elif not force_clarify:
                if _is_write_row(by_code[top_code]):
                    go = p_top >= self.config.luna_write_threshold and p_top >= p_clarify
                    timing["lunaWriteToConfirm" if go else "lunaWriteWeak"] = 1
                else:
                    go = p_top >= self.config.luna_read_threshold or p_top - p_second >= self.config.luna_margin
                    timing["lunaReadWithDefaults" if go else "lunaTwoWay"] = 1
                if go:
                    auto_threshold = 0.0  # 只把首选交给 Java: 读按默认执行并写明默认, 写一律走确认卡
                else:
                    force_clarify = True
        if force_clarify:
            timing["decisionClarify"] = 1
        candidates = []
        for i, (code, p) in enumerate(ranked[:5]):
            if i >= 2 and p < 0.03:  # 澄清按钮至少两个, 其余只留有分量的
                break
            row = by_code[code]
            candidates.append(CandidateIntentDto(
                intentCode=code, intentName=row.get("intent_name") or code,
                intentCategory=row.get("intent_category"), confidence=round(float(p), 4),
                matchMethod=MatchMethod.LLM, description=row.get("description"),
            ))
        timing["totalMs"] = int((time.time() - t0) * 1000)
        # ③ 置信不够 → requiresConfirmation=true, Java 走 buildClarificationResponse 出候选按钮
        return self._build_result(query=query, top_candidates=candidates, method=MatchMethod.LLM,
                                  visible_intents=visible_intents,
                                  min_confidence=1.01 if force_clarify else auto_threshold,
                                  timing=timing)

    def _result_from_fallback(self, fb: FallbackDecision, query: str, by_code: Dict[str, Any],
                              visible_intents: List[Dict[str, Any]], timing: Dict[str, int],
                              t0: float) -> IntentMatchResultDto:
        """退路结论 -> 结果。只有「只读目标 + EXECUTE」交给 Java 直接办; 其余一律按钮 (requiresConfirmation)。

        写目标在退路上**一律不自动执行**: decide_v3 的 write_to_confirm 在这里也转成按钮 (首个按钮就是那个写意图,
        点了之后 Java 照常走写确认)。比 Luna 路径多一次点击, 换来退路上不存在「写意图直通 Java」这条路。
        """
        timing.update(fb.timing)
        timing["fallbackHop"] = HOPS.index(fb.hop) + 1  # 1 combo / 2 qwen_only / 3 intent_ask / 4 buttons
        for side in sorted(fb.failures):
            timing["fallbackFail_" + side] = 1
        if fb.clarify_p is not None:
            timing["fallbackClarifyP"] = int(round(fb.clarify_p * 1000))
        if fb.act in DIALOGUE_ACTS:
            timing["fallbackAct"] = DIALOGUE_ACTS.index(fb.act)
        if fb.outcome == "NONE":
            return self._none_result(query, timing, t0, CANCEL_REPLY if fb.reason == "act_cancel" else NONE_REPLY)
        if fb.outcome == "EXECUTE" and fb.intent in by_code and not _is_write_row(by_code[fb.intent]):
            row = by_code[fb.intent]
            cand = CandidateIntentDto(
                intentCode=fb.intent, intentName=row.get("intent_name") or fb.intent,
                intentCategory=row.get("intent_category"),
                confidence=round(float(fb.intent_probs.get(fb.intent, 0.0)), 4),
                matchMethod=MatchMethod.LLM, description=row.get("description"),
            )
            timing["totalMs"] = int((time.time() - t0) * 1000)
            return self._build_result(query=query, top_candidates=[cand], method=MatchMethod.LLM,
                                      visible_intents=visible_intents, min_confidence=0.0, timing=timing)
        if fb.outcome == "EXECUTE":
            timing["fallbackWriteHeld"] = 1
        if fb.hop == "buttons":
            timing["fallbackNoSignal"] = 1  # 置信度一律 0: 没有任何模型给出概率, ⛔ 不编
        codes: List[str] = []
        for c in ([fb.intent] if fb.intent else []) + list(fb.top):
            if c in by_code and c not in codes:
                codes.append(c)
        candidates = []
        for c in codes[:5]:
            row = by_code[c]
            candidates.append(CandidateIntentDto(
                intentCode=c, intentName=row.get("intent_name") or c, intentCategory=row.get("intent_category"),
                confidence=round(float(fb.intent_probs.get(c, 0.0)), 4),
                matchMethod=MatchMethod.LLM, description=row.get("description"),
            ))
        timing["decisionClarify"] = 1
        timing["totalMs"] = int((time.time() - t0) * 1000)
        return self._build_result(query=query, top_candidates=candidates, method=MatchMethod.LLM,
                                  visible_intents=visible_intents, min_confidence=1.01, timing=timing)

    async def _rerank_candidates(self, query, factoryId, businessType, retriever, by_code, timing):
        """向量 Top-N → 重排 → 前 K 个意图码; 任何一步拿不到结果 → None (调用方走 LLM 粗选)。"""
        t = time.time()
        try:
            pool = await retriever.match(query, factoryId=factoryId, businessType=businessType,
                                         top_k=self.config.rerank_pool_k)
        except Exception as ex:  # noqa: BLE001
            logger.warning("decision: rerank pool retrieval failed: %s", ex)
            pool = []
        pool_codes = [c.intentCode for c in pool if c.intentCode in by_code]
        scores = None
        if pool_codes:
            # 文档格式与训练时逐字一致: intent_text (名称：说明 常见说法) 截到 120 字
            docs = [intent_text(by_code[c])[:120] for c in pool_codes]
            scores = await self.rerank_client.scores(query, docs)
        timing["rerankMs"] = int((time.time() - t) * 1000)
        if not scores:
            timing["rerankFallback"] = 1
            return None
        timing["rerankOk"] = 1
        ranked = [c for c, _ in sorted(zip(pool_codes, scores), key=lambda kv: -kv[1])]
        return ranked[: self.config.rerank_top_k]

    async def _match_legacy(
        self,
        query: str,
        factoryId: str,
        businessType: str,
        userId: str,
        role: str,
        visible_intents: List[Dict[str, Any]],
        history: List[Dict[str, Any]],
        min_confidence: float,
        enable_llm: bool = True,
    ) -> IntentMatchResultDto:
        """Run stages 5-8 short-circuit, return Java-shaped result.

        β: if a semantic_router was provided, runs pre-stage routing first and
        honors its decision (DIRECT_EXECUTE / NEED_RERANKING / NEED_FULL_LLM).
        Without a router, behaves identically to α.
        """
        t0 = time.time()
        timing: Dict[str, int] = {}

        # ===== β Stage 0: SemanticRouter (optional) =====
        skip_stage_8_due_to_reranking = False
        if self.semantic_router is not None:
            t_router = time.time()
            try:
                decision = await self.semantic_router.route(
                    query=query, visible_intents=visible_intents,
                    factoryId=factoryId, businessType=businessType,
                )
            except Exception:
                logger.exception("SemanticRouter failed, falling through to all stages")
                decision = None
            timing["routerMs"] = int((time.time() - t_router) * 1000)

            # β F2 fix (I1): surface OOD signal via timingMs side-channel.
            # Cannot add a 20th top-level field (would break F999/F001 byte-shape).
            # timingMs is Map<String,Long> in Java (Jackson tolerates extras) and
            # the contract test strips timingMs via VOLATILE_KEYS, so adding a
            # key here is golden-safe. Only emit when ood_detected=True so the
            # default-path response stays clean.
            if decision is not None and decision.ood_detected:
                timing["oodDetected"] = 1

            if decision is not None:
                if decision.method == "DIRECT_EXECUTE":
                    logger.info(
                        "SemanticRouter DIRECT_EXECUTE (top conf=%.3f)",
                        decision.candidates[0].confidence if decision.candidates else 0.0,
                    )
                    timing["totalMs"] = int((time.time() - t0) * 1000)
                    candidates = self._apply_calibration(decision.candidates, factoryId)
                    return self._build_result(
                        query=query,
                        top_candidates=candidates,
                        method=MatchMethod.SEMANTIC,
                        visible_intents=visible_intents,
                        min_confidence=min_confidence,
                        timing=timing,
                    )
                elif decision.method == "NEED_RERANKING":
                    skip_stage_8_due_to_reranking = True
                # NEED_FULL_LLM falls through, all stages run

        # ===== Stage 5 SEMANTIC =====
        t_stage_start = time.time()
        try:
            sem_candidates = await self.semantic_matcher.match(
                query, factoryId=factoryId, businessType=businessType
            )
        except Exception:
            logger.exception("Stage 5 SEMANTIC failed, treating as empty")
            sem_candidates = []
        timing["semanticMs"] = int((time.time() - t_stage_start) * 1000)

        if semantic_module.is_strong_signal(sem_candidates):
            logger.info(
                "Stage 5 SEMANTIC short-circuit (confidence=%.3f)",
                sem_candidates[0].confidence,
            )
            timing["totalMs"] = int((time.time() - t0) * 1000)
            sem_candidates = self._apply_calibration(sem_candidates, factoryId)
            return self._build_result(
                query=query,
                top_candidates=sem_candidates,
                method=MatchMethod.SEMANTIC,
                visible_intents=visible_intents,
                min_confidence=min_confidence,
                timing=timing,
            )

        # ===== Stage 6 CLASSIFIER =====
        t_stage_start = time.time()
        try:
            cls_candidates = await self.classifier_matcher.match(
                query, factoryId=factoryId, businessType=businessType
            )
        except Exception:
            logger.exception("Stage 6 CLASSIFIER failed, treating as empty")
            cls_candidates = []
        timing["classifierMs"] = int((time.time() - t_stage_start) * 1000)

        # ===== Stage 7 FUSION =====
        if sem_candidates or cls_candidates:
            fused = fusion_module.fuse(sem_candidates, cls_candidates)
            if fusion_module.is_strong_signal(fused):
                logger.info(
                    "Stage 7 FUSION short-circuit (confidence=%.3f)",
                    fused[0].confidence,
                )
                timing["totalMs"] = int((time.time() - t0) * 1000)
                fused = self._apply_calibration(fused, factoryId)
                return self._build_result(
                    query=query,
                    top_candidates=fused,
                    method=MatchMethod.FUSION,
                    visible_intents=visible_intents,
                    min_confidence=min_confidence,
                    timing=timing,
                )

        # ===== Stage 8 LLM =====
        # β: also skip if NEED_RERANKING decided
        if enable_llm and not skip_stage_8_due_to_reranking:
            # β C2: pick LLM tier (cheap/expensive) before stage 8
            tier = None
            if self.llm_tier_selector is not None:
                try:
                    tier = await self.llm_tier_selector.select(query=query)
                except Exception:
                    logger.exception("LlmTierSelector failed, llm_matcher uses default tier")
                    tier = None

            # β C5: RAG retrieval + evaluation, inject HIGH/MEDIUM cases into LLM prompt
            rag_cases = []
            if self.rag_retriever is not None and self.rag_evaluator is not None:
                try:
                    cases = await self.rag_retriever.retrieve(
                        query=query, factory_id=factoryId, top_k=5,
                    )
                    quality = self.rag_evaluator.evaluate(cases)
                    from ai.rag.evaluator import RAGQuality
                    if quality in (RAGQuality.HIGH, RAGQuality.MEDIUM):
                        rag_cases = cases
                        logger.info("RAG quality=%s, injecting %d cases", quality, len(cases))
                    else:
                        logger.debug("RAG quality=%s (UNRELIABLE), skipping", quality)
                except Exception:
                    logger.exception("RAG failed, continuing without enrichment")

            t_stage_start = time.time()
            try:
                llm_candidates = await self.llm_matcher.match(
                    query, visible_intents=visible_intents, history=history,
                    tier=tier, rag_cases=rag_cases,
                )
            except Exception:
                logger.exception("Stage 8 LLM failed, treating as empty")
                llm_candidates = []
            timing["llmMs"] = int((time.time() - t_stage_start) * 1000)

            if llm_candidates:
                logger.info(
                    "Stage 8 LLM result: %s @ %.3f",
                    llm_candidates[0].intentCode,
                    llm_candidates[0].confidence,
                )
                timing["totalMs"] = int((time.time() - t0) * 1000)
                llm_candidates = self._apply_calibration(llm_candidates, factoryId)
                return self._build_result(
                    query=query,
                    top_candidates=llm_candidates,
                    method=MatchMethod.LLM,
                    visible_intents=visible_intents,
                    min_confidence=min_confidence,
                    timing=timing,
                )

        # ===== No match =====
        logger.info("All stages 5-8 returned empty for query=%r", query[:80])
        timing["totalMs"] = int((time.time() - t0) * 1000)
        result = IntentMatchResultDto.empty(userInput=query)
        result.timingMs = timing
        return result

    def _apply_calibration(self, candidates, factory_id):
        """β: calibrate each candidate's confidence per (matcher, factory_id) coef.
        If calibrator is None, return unchanged. If scorer is set, re-rank by combined score."""
        if self.calibrator is None:
            return candidates
        for c in candidates:
            method_name = c.matchMethod.value if c.matchMethod else "NONE"
            c.confidence = self.calibrator.calibrate(
                raw=c.confidence, matcher=method_name, factory_id=factory_id,
            )
        if self.scorer is not None:
            candidates.sort(
                key=lambda c: -self.scorer.score(
                    calibrated_confidence=c.confidence,
                    matched_keyword_count=len(c.matchedKeywords or []),
                    priority=80,
                    confidence_boost=0.0,
                ),
            )
        return candidates

    def _build_result(
        self,
        query: str,
        top_candidates: List[CandidateIntentDto],
        method: MatchMethod,
        visible_intents: List[Dict[str, Any]],
        min_confidence: float,
        timing: Dict[str, int],
    ) -> IntentMatchResultDto:
        """Build full IntentMatchResultDto from top candidates.

        Populates all 19 top-level fields per Java IntentMatchResult shape.
        Falls back to IntentMatchResultDto.empty() if top_candidates is empty
        (defensive — should not happen on the matched path).
        """
        # I2 fix: filter candidates to visible scope (info-leak prevention).
        # Classifier (stage 6) returns predictions for any trained intent_code,
        # including codes outside this user's factory/business scope. Strip them
        # before they reach topCandidates / bestMatch, otherwise an out-of-scope
        # code can leak via topCandidates[0].intentCode even when bestMatch is
        # None (the downstream meta lookup already filters bestMatch).
        visible_codes = {i.get("intent_code") for i in visible_intents}
        normalized_candidates = []
        for candidate in top_candidates:
            if is_retired(candidate.intentCode):
                continue
            candidate.intentCode = canonical_code(candidate.intentCode)
            if candidate.intentCode in visible_codes:
                normalized_candidates.append(candidate)
        top_candidates = normalized_candidates

        if not top_candidates:
            result = IntentMatchResultDto.empty(userInput=query)
            result.timingMs = timing
            return result

        top1 = top_candidates[0]
        top1_conf = top1.confidence or 0.0

        # Find full config row for top1 → convert via row_to_dto for byte-shape
        # parity with Java AIIntentConfig serialization.
        meta = next(
            (i for i in visible_intents if i.get("intent_code") == top1.intentCode),
            None,
        )
        best_match: Optional[AIIntentConfigDto] = (
            row_to_dto(meta) if meta else None
        )

        # Strong signal: confidence >= 0.7 AND (only 1 candidate OR top1-top2
        # gap > 0.3). Mirrors Java IntentMatchResult.isStrongSignal heuristic.
        is_strong = False
        if top1_conf >= 0.7:
            if len(top_candidates) < 2:
                is_strong = True
            else:
                top2_conf = top_candidates[1].confidence or 0.0
                gap = top1_conf - top2_conf
                is_strong = gap > 0.3

        # requiresConfirmation: top1 confidence below caller's threshold →
        # caller (Java side) should ask user to confirm/disambiguate.
        requires_confirmation = top1_conf < min_confidence

        # questionType: best-effort guess from intent_category. Java side
        # generates more sophisticated classification via IntentKnowledgeBase;
        # here we map a few obvious categories to the corresponding enum.
        # Default OPERATIONAL_COMMAND for action-style intents.
        q_type: QuestionType = QuestionType.OPERATIONAL_COMMAND
        if best_match and best_match.intentCategory:
            cat = best_match.intentCategory
            if cat == "CONVERSATIONAL":
                q_type = QuestionType.CONVERSATIONAL
            elif cat == "GENERAL_QUESTION":
                q_type = QuestionType.GENERAL_QUESTION

        return IntentMatchResultDto(
            bestMatch=best_match,
            topCandidates=top_candidates[:5],  # cap to top-5 for response
            confidence=top1_conf,
            matchMethod=method,
            matchedKeywords=top1.matchedKeywords or [],
            isStrongSignal=is_strong,
            requiresConfirmation=requires_confirmation,
            clarificationQuestion=None,  # Java side generates dynamically
            userInput=query,
            actionType=ActionType.QUERY,  # default; Java may infer better
            questionType=q_type,
            targetEntity=None,
            sessionId=None,
            conversationMessage=None,
            isMultiIntent=False,
            additionalIntents=None,
            executionStrategy=None,
            timingMs=timing,
            preprocessedQuery=None,
        )


__all__ = ["Orchestrator"]
