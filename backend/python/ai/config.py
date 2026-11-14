"""AI module config.

Centralized constants and env-driven overrides. Avoids magic numbers in
matcher/orchestrator code.
"""
from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class AIConfig:
    """Phase 2B AI module config.

    All thresholds tunable via env. Defaults match spec §6 / §7 baseline.
    """

    semantic_threshold: float = 0.85
    fusion_threshold: float = 0.70
    min_confidence_default: float = 0.70
    llm_timeout_s: int = 30
    cache_ttl_s: int = 300
    cache_max_size: int = 1000
    config_refresh_s: int = 300
    embedding_grpc_endpoint: str = "localhost:9090"
    embedding_retry_attempts: int = 3
    embedding_retry_delay_s: float = 1.0
    # 三层识别 (2026-10): 第①层精确表 + 第②层读概率决策 + 第③层按钮澄清。
    # 只对列出的业态生效 (逗号分隔, 如 "FACTORY"); 空 = 关闭, 行为与旧版一致。
    decision_business_types: str = ""
    # 第②层: 首选概率 >= 此值才自动执行, 否则 requiresConfirmation=true 交给按钮澄清。
    # 0.80 是 2026-10-02 在模拟流量 history 段 (1,647 句, 未用验收段) 上选的:
    #   τ=0.70 自动 55% / 准 80.2% / 读写方向错 100;  τ=0.80 自动 43% / 准 84.1% / 方向错 30
    decision_auto_threshold: float = 0.80
    decision_timeout_s: float = 6.0
    decision_k: int = 20
    # 候选补足: 例句相似度 × (1-blend) + 描述相似度 × blend (见 ai/matcher/candidates.py 的离线读数)
    decision_candidate_blend: float = 0.35
    # 决策选项里追加「以上都不是 / 需要先问清楚」两个出口 (2026-10-03, 防曲解)。缺省关, 量过再开。
    decision_special_options: bool = False
    # 候选来源 (2026-10-04): "shortlist" = LLM 粗选 + 向量补足 (旧); "rerank" = 向量 Top-N → 微调重排 → 前 K。
    # rerank 失败 / 没配地址 / 本轮带对话历史时一律走 shortlist。
    decision_candidates: str = "shortlist"
    rerank_url: str = ""
    rerank_key: str = ""
    rerank_timeout_s: float = 0.6
    rerank_pool_k: int = 50
    rerank_top_k: int = 10
    # 合并理解 (INTENT-DISTILL E, 2026-11-11): 「目的 + 粗选 (+ 对话动作 / 参数 / 是否先问)」一次结构化调用,
    # Choice (读概率) 只在需要时才调。缺省关, 行为与旧版一致; 门槛的来由见 ai/matcher/decision.py 的 E 线说明。
    decision_merged: bool = False
    # 合并输出的 clarify_probability >= 此值 ⇒ 这一轮先问 (按钮澄清)。
    # 0.5 取自开发集平台 [0.3, 0.7] 的中点: 该区间内两个方向的错误数都不变 (见 PR 回执), 不靠门槛压某一方向。
    decision_clarify_threshold: float = 0.5
    # 合并输出的 target 把握 < 此值, 或给出了同样说得通的 alternatives ⇒ 才调 Choice。
    decision_choice_below: float = 0.8  # 开发集 [0.7,0.8) 档直出只对 1/4, 交给 Choice 复核
    # Luna 没启用 / 失败 / 超时之后的退路 (INTENT-DISTILL 批次 2, Steve 2026-11-12 拍板接上):
    #   "combo"  = 阿里云选意图 ‖ qwen 判动作与是否先问 → qwen 单用 → 保守先问 → 候选按钮 (ai/matcher/fallback_combo.py);
    #   "legacy" = 旧链 Luna → qwen 读 logprobs → _result_from_probs (回滚用)。
    # 退路自己的阈值与 Luna 的 decision_auto_threshold 分开, 见 fallback_combo.FallbackThresholds (AI_DECISION_FALLBACK_*)。
    decision_fallback: str = "combo"
    # Luna 主路径的结论口径 (INTENT-DISTILL-20261111 第三批, 2026-11-14, 按史浩禾 39 题校准):
    #   "v3" = Choice 之外并行问 Luna 一道判断题「这一轮是否必须先问」(decision.ASK_PREDICATE_RULES);
    #          判断题 >= luna_ask_threshold ⇒ 先问; 否则只读目标首选 >= luna_read_threshold 或领先第二名
    #          >= luna_margin 就按默认值直接办 (Java 写明默认), 写目标首选 >= luna_write_threshold 才交写确认卡。
    #          「需要先问清楚」选项比所有真实候选都大时仍然先问 (与 v1 相同)。判断题拿不到 ⇒ 这一轮按 v1。
    #   "v1" = 旧口径 (回滚用): 首选 >= decision_auto_threshold(0.80) 才执行, 否则出按钮。
    # 0.80 在 Luna 上几乎从不达到 (11-14 线上 39 题首选最高 0.74 ⇒ 38/38 先问), 这是「过度追问」的主因。
    # 0.6 / 0.15 / 0.3 / 0.4 的来由见 Documents/Cretas意图蒸馏-20261111/third-batch-calibrate-20261114.md。
    decision_luna_policy: str = "v3"
    luna_ask_threshold: float = 0.6
    luna_read_threshold: float = 0.15
    luna_margin: float = 0.3
    luna_write_threshold: float = 0.4

    def decision_enabled_for(self, business_type: str) -> bool:
        wanted = {b.strip().upper() for b in self.decision_business_types.split(",") if b.strip()}
        return (business_type or "").upper() in wanted

    @classmethod
    def from_env(cls) -> "AIConfig":
        return cls(
            semantic_threshold=float(os.environ.get("AI_SEMANTIC_THRESHOLD", "0.85")),
            fusion_threshold=float(os.environ.get("AI_FUSION_THRESHOLD", "0.70")),
            min_confidence_default=float(os.environ.get("AI_MIN_CONFIDENCE", "0.70")),
            llm_timeout_s=int(os.environ.get("AI_LLM_TIMEOUT_S", "30")),
            cache_ttl_s=int(os.environ.get("AI_CACHE_TTL_S", "300")),
            cache_max_size=int(os.environ.get("AI_CACHE_MAX_SIZE", "1000")),
            config_refresh_s=int(os.environ.get("AI_CONFIG_REFRESH_S", "300")),
            embedding_grpc_endpoint=os.environ.get(
                "AI_EMBEDDING_GRPC_ENDPOINT", "localhost:9090"
            ),
            embedding_retry_attempts=int(os.environ.get("AI_EMBEDDING_RETRY_ATTEMPTS", "3")),
            embedding_retry_delay_s=float(os.environ.get("AI_EMBEDDING_RETRY_DELAY_S", "1.0")),
            decision_business_types=os.environ.get("AI_DECISION_LAYER_BUSINESS_TYPES", ""),
            decision_auto_threshold=float(os.environ.get("AI_DECISION_AUTO_THRESHOLD", "0.80")),
            decision_timeout_s=float(os.environ.get("AI_DECISION_TIMEOUT_S", "6.0")),
            decision_k=int(os.environ.get("AI_DECISION_K", "20")),
            decision_candidate_blend=float(os.environ.get("AI_DECISION_CANDIDATE_BLEND", "0.35")),
            decision_special_options=os.environ.get("AI_DECISION_SPECIAL_OPTIONS", "0").strip().lower()
            in ("1", "true", "yes", "on"),
            decision_candidates=(os.environ.get("AI_DECISION_CANDIDATES", "shortlist").strip().lower() or "shortlist"),
            rerank_url=os.environ.get("AI_RERANK_URL", "").strip(),
            rerank_key=os.environ.get("AI_RERANK_KEY", "").strip(),
            rerank_timeout_s=float(os.environ.get("AI_RERANK_TIMEOUT_MS", "600")) / 1000.0,
            rerank_pool_k=int(os.environ.get("AI_RERANK_POOL_K", "50")),
            rerank_top_k=int(os.environ.get("AI_RERANK_TOP_K", "10")),
            decision_merged=os.environ.get("AI_DECISION_MERGED", "0").strip().lower() in ("1", "true", "yes", "on"),
            decision_clarify_threshold=float(os.environ.get("AI_DECISION_CLARIFY_THRESHOLD", "0.5")),
            decision_choice_below=float(os.environ.get("AI_DECISION_CHOICE_BELOW", "0.8")),
            decision_fallback=(os.environ.get("AI_DECISION_FALLBACK", "combo").strip().lower() or "combo"),
            decision_luna_policy=(os.environ.get("AI_DECISION_LUNA_POLICY", "v3").strip().lower() or "v3"),
            luna_ask_threshold=float(os.environ.get("AI_DECISION_LUNA_ASK_THRESHOLD", "0.6")),
            luna_read_threshold=float(os.environ.get("AI_DECISION_LUNA_READ_THRESHOLD", "0.15")),
            luna_margin=float(os.environ.get("AI_DECISION_LUNA_MARGIN", "0.3")),
            luna_write_threshold=float(os.environ.get("AI_DECISION_LUNA_WRITE_THRESHOLD", "0.4")),
        )


default_config = AIConfig.from_env()
