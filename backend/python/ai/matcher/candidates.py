"""第②层的候选补足: 例句相似度与描述相似度混合 (0 token)。

决策层的候选 = LLM 粗选 (最多 8 个) + 本检索器补足到 k 个。补足用的分数:

    score(intent) = max_sim(query, 该意图的例句) × (1 - blend) + sim(query, 意图描述) × blend

例句来自 `ai_intent_examples` (车间口语例句, 语料侧不加 BGE 前缀); 描述向量来自
`ai_intent_configs.embedding`。查询向量与 Stage 5 共用同一个请求级缓存 (带 BGE 检索前缀)。

2026-10-03 离线 (模拟 history 段未命中精确表的 1,645 次, 清理后目录 235 个, 同一 LLM 粗选):
    补足用描述向量 (旧): 召回 75.6% / 首选对 41.9% / 自动执行判错 9.1%
    补足用 blend 0.35:   召回 82.4% / 首选对 46.9% / 自动执行判错 5.4%
⚠️ 纯检索 (不要 LLM 粗选) 召回相同但首选对只有 35.3% —— 检索只用来**补足**, 不替代粗选。

没有例句的意图 (或例句表还没建): 例句分 = 描述分, 退化成旧行为, 不报错。
"""
from __future__ import annotations

import logging
from typing import Dict, List

from ai.dto import CandidateIntentDto, MatchMethod
from ai.embedding import get_embedding_cached, vec_to_pgvector_text

logger = logging.getLogger(__name__)

DESC_SQL = """
SELECT intent_code, intent_name, intent_category, description,
       1 - (embedding <=> $1::vector) AS similarity
FROM ai_intent_configs
WHERE deleted_at IS NULL
  AND is_active = true
  AND (factory_id IS NULL OR factory_id = $2)
  AND (business_type = 'COMMON' OR business_type = $3)
  AND embedding IS NOT NULL
"""

EXAMPLE_SQL = """
SELECT intent_code, MAX(1 - (embedding <=> $1::vector)) AS similarity
FROM ai_intent_examples
WHERE embedding IS NOT NULL
GROUP BY intent_code
"""

_missing_table_warned = False


def blend_scores(desc: Dict[str, float], ex: Dict[str, float], blend: float) -> Dict[str, float]:
    """只给有描述向量 (= 可见) 的意图打分; 没有例句的意图例句分取描述分。"""
    return {c: ex.get(c, d) * (1 - blend) + d * blend for c, d in desc.items()}


class BlendRetriever:
    """与 SemanticMatcher 同接口 (`match(query, factoryId, businessType)`), 可直接换进编排器。"""

    def __init__(self, pool, top_k: int = 20, blend: float = 0.35):
        self.pool = pool
        self.top_k = top_k
        self.blend = blend

    async def match(self, query: str, factoryId: str, businessType: str) -> List[CandidateIntentDto]:
        global _missing_table_warned
        if self.pool is None:
            return []
        vec = await get_embedding_cached(query)
        if vec is None:
            logger.warning("BlendRetriever: embedding unavailable, no fill candidates")
            return []
        vec_text = vec_to_pgvector_text(vec)
        async with self.pool.acquire() as conn:
            desc_rows = await conn.fetch(DESC_SQL, vec_text, factoryId, businessType)
            try:
                ex_rows = await conn.fetch(EXAMPLE_SQL, vec_text)
            except Exception as ex:  # noqa: BLE001
                if "does not exist" not in str(ex):
                    raise
                if not _missing_table_warned:
                    logger.warning("BlendRetriever: ai_intent_examples 不存在 (迁移未跑?), 只用描述向量")
                    _missing_table_warned = True
                ex_rows = []
        meta = {r["intent_code"]: r for r in desc_rows}
        scores = blend_scores({r["intent_code"]: float(r["similarity"]) for r in desc_rows},
                              {r["intent_code"]: float(r["similarity"]) for r in ex_rows}, self.blend)
        ranked = sorted(scores.items(), key=lambda kv: -kv[1])[: self.top_k]
        return [
            CandidateIntentDto(
                intentCode=c, intentName=meta[c]["intent_name"], intentCategory=meta[c].get("intent_category"),
                confidence=round(s, 4), matchMethod=MatchMethod.SEMANTIC, description=meta[c].get("description"),
            )
            for c, s in ranked
        ]


__all__ = ["BlendRetriever", "blend_scores"]
