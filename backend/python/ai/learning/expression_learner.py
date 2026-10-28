"""Async expression template learner (β C6).

Reads: intent_match_records (high-confidence rows from Java's match pipeline)
Writes: ai_learned_expressions (full template with embedding_vec)

Different from KeywordLearner: keeps full query as expression template,
preserves linguistic context. Future intent matching can reuse these as
exemplars in stage 5 SEMANTIC or stage 8 LLM RAG context.

Cron schedule: 5min interval (background task in main.py / W6).
DB error → returns 0 gracefully.

Post-W0 fix-pass F1: rewritten with REAL Java schema:
- intent_match_records.user_input → query (SQL alias)
- intent_match_records.matched_intent_code → intent_code (SQL alias)
- intent_match_records.confidence_score (was: confidence)
- ai_learned_expressions.embedding_vec (new pgvector col, V20260501_15)
- expression_hash (SHA256, NOT NULL, ON CONFLICT key) — added per real schema
- source_type = 'SEMANTIC_HIGH' (NOT NULL, matches Java enum LearnedExpression.SourceType)
- id auto-generated via gen_random_uuid()
"""
from __future__ import annotations

import hashlib
import logging

from ai.embedding import get_embedding, vec_to_pgvector_text

logger = logging.getLogger(__name__)


# Reads recent high-confidence matches. SQL aliases preserve original loop body.
READ_SQL = """
SELECT user_input AS query, matched_intent_code AS intent_code, factory_id,
       confidence_score AS confidence
FROM intent_match_records
WHERE confidence_score >= $1
  AND created_at > NOW() - INTERVAL '1 hour'
  AND matched_intent_code IS NOT NULL
"""

# REAL ai_learned_expressions has required NOT NULL columns:
# id (uuid → gen_random_uuid()::text), expression_hash (SHA256 hex), source_type.
# embedding_vec is the new pgvector column added by V20260501_15. Java's BLOB
# column embedding_vector is left untouched by Python writes.
INSERT_SQL = """
INSERT INTO ai_learned_expressions(
    id, intent_code, expression, expression_hash, source_type,
    embedding_vec, factory_id, is_active, created_at, updated_at
)
VALUES(gen_random_uuid()::text, $1, $2, $3, 'SEMANTIC_HIGH',
       $4::vector, $5, $6, NOW(), NOW())
ON CONFLICT (expression_hash, factory_id) DO NOTHING
"""


# Java ExpressionLearningServiceImpl.HIGH_CONF_THRESHOLD: 置信度 >= 0.90 直接激活, 低于的先挂起 (staged)。
HIGH_CONF_THRESHOLD = 0.90


def normalize_expression(expression: str) -> str:
    """与 Java 一致: 存小写、去首尾空白的原话 (Java learnExpression 的 normalized)。"""
    return expression.lower().strip()


def _compute_expression_hash(expression: str) -> str:
    """SHA256 hex digest of the normalized expression —— 与 Java ``LearnedExpression.computeHash`` 相同
    (先 toLowerCase().trim() 再算), 两边写同一句说法时去重键一致 (复审 L6, 2026-10-29)。"""
    return hashlib.sha256(normalize_expression(expression).encode("utf-8")).hexdigest()


class ExpressionLearner:
    """工艺保密 (Steve 2026-10-29): 只学进原话所属工厂自己; 没有工厂的记录不学 (不写全局 —— 以前
    factory_id 为 NULL 的记录会写成 NULL 工厂的 SEMANTIC_HIGH 行, 被每家工厂检索到); 训练授权撤回 /
    查不到、提到本厂工艺保密产品、配方参数 / 客户 / 人名的原话不学 (``learning_rules.factory_learnable``)。"""

    def __init__(self, pool, *, consent_scope=None, protected_names=None):
        from ai.learning import learning_rules

        self.pool = pool
        self._consent_scope = consent_scope or learning_rules.default_consent_scope
        self._protected_names = protected_names or learning_rules.default_protected_names

    async def run_once(self, min_confidence: float = 0.95) -> int:
        """Returns number of new expressions inserted (best-effort, ON CONFLICT skips dup).
        0 on error or empty.
        """
        from ai.learning.learning_rules import factory_learnable

        count = 0
        try:
            async with self.pool.acquire() as conn:
                rows = await conn.fetch(READ_SQL, min_confidence)
                for row in rows:
                    if not await factory_learnable(row["factory_id"], row["query"],
                                                   consent_scope=self._consent_scope,
                                                   protected_names=self._protected_names):
                        continue
                    vec = await get_embedding(row["query"])
                    if vec is None:
                        logger.warning("ExpressionLearner: embedding failed for %r, skipping",
                                       row["query"][:50])
                        continue
                    expression = normalize_expression(row["query"])
                    expr_hash = _compute_expression_hash(row["query"])
                    vec_text = vec_to_pgvector_text(vec)
                    # 激活规则与 Java 分层写入一致: >= 0.90 激活, 否则挂起等重提议 (复审 L6)
                    confidence = row.get("confidence") if hasattr(row, "get") else row["confidence"]
                    active = confidence is not None and float(confidence) >= HIGH_CONF_THRESHOLD
                    await conn.execute(
                        INSERT_SQL,
                        row["intent_code"], expression, expr_hash,
                        vec_text, row["factory_id"], active,
                    )
                    count += 1
        except Exception:
            logger.exception("ExpressionLearner failed")
        return count
