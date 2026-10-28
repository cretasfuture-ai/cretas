"""Async keyword learner — extract unseen keywords from positive feedback (β C6).

Reads: ai_training_samples (Java IntentFeedbackService writes; per audit R-IM4 fix)
Writes: ai_intent_configs.keywords (UPDATE — appends new keywords, JSON merge)
Returns: dict[intent_code, set[new_keyword]] for downstream observability

Cron schedule: 5min interval, started by main.py background task (W6).

Tokenizer is cheap: Chinese 2-4 char regex + stopword filter. Sufficient for
keyword extraction; jieba/BERT defer to Phase 3.

Post-W0 fix-pass F1: rewritten with REAL Java schema:
- ai_training_samples (was: training_samples)
- user_input (was: query)
- matched_intent_code (was: intent_code)
- confidence (precision 5,4) + is_correct filter (positive feedback only)
- writes UPDATE to ai_intent_configs.keywords (was: stub returning dict only)
"""
from __future__ import annotations

import json
import logging
import re
from typing import Dict, Iterable, List, Set, Tuple

logger = logging.getLogger(__name__)


# Reads positive feedback rows from ai_training_samples (real table name).
# Aliased to keep loop body unchanged.
SAMPLE_QUERY_SQL = """
SELECT user_input AS query, matched_intent_code AS intent_code,
       factory_id, confidence
FROM ai_training_samples
WHERE created_at > NOW() - INTERVAL '1 hour'
  AND confidence >= $1
  AND is_correct = true
"""

# Persists new keywords back to ai_intent_configs by JSON-merge appending.
# COALESCE handles rows where keywords is NULL. JSONB || preserves duplicates
# being filtered out at write time? No — JSONB || accumulates. We pass a deduped
# set as a JSON array; idempotency is not required here (cron is bounded by
# 1-hour SQL window so duplicate emits over multiple ticks are negligible —
# revisit with a server-side dedup if it grows).
#
# F6 fix-pass (I-NEW-3): WHERE uses `IS NOT DISTINCT FROM` so per-tenant calls
# (factory_id=$3 non-NULL) match ONLY the matching tenant row, and global calls
# ($3=NULL) match ONLY the global (factory_id IS NULL) row. PostgreSQL's
# `IS NOT DISTINCT FROM` treats NULL=NULL as true and NULL=non-NULL as false,
# preventing tenant-specific keywords from leaking into the global config.
#
# 2026-10-29 (Steve, 工艺保密): 只更新原话所属工厂自己的那一行 —— 平台级 (NULL) 行和别厂的行一律不写
# (以前按 intent_code 汇总所有工厂的新词, 再写进「第一条样本」那家的行或全局行)。factory_id 必须非空。
UPDATE_KEYWORDS_SQL = """
UPDATE ai_intent_configs
SET keywords = COALESCE(keywords, '[]'::jsonb) || $1::jsonb,
    updated_at = NOW()
WHERE intent_code = $2 AND factory_id = $3
"""

# 本厂那一行的现有关键词 (复审 L5): 只认 factory_id = $2 的行, 平台级 / 别厂行不算
OWN_KEYWORDS_SQL = """
SELECT keywords FROM ai_intent_configs
WHERE intent_code = $1 AND factory_id = $2 AND deleted_at IS NULL
"""

# 本厂自动学习开关 (Java FactoryConfigService.isAutoLearnEnabled): 没有配置行 = 默认开
AUTO_LEARN_SQL = "SELECT auto_learn_enabled FROM factory_ai_learning_config WHERE factory_id = $1"


async def _auto_learn_enabled(conn, factory_id: str) -> bool:
    """没有配置行 → 默认开 (Java createDefault); 有行但值为空 → 关 (Java Boolean.TRUE.equals(null))。"""
    row = await conn.fetchrow(AUTO_LEARN_SQL, factory_id)
    return True if row is None else row["auto_learn_enabled"] is True


def _keyword_list(raw) -> List[str]:
    if raw is None:
        return []
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except ValueError:
            return []
    return [str(k) for k in raw] if isinstance(raw, list) else []


# Single-char stopwords (filtered separately even though regex below requires 2-4 chars)
STOP_WORDS = {"的", "是", "在", "了", "我", "你", "他", "她", "它", "和", "与", "或", "也"}


_CJK_RUN = re.compile(r"[一-龥]+")


def _ngrams_from_run(run: str) -> List[str]:
    """All 2/3/4-char overlapping n-grams from a contiguous CJK run."""
    out: List[str] = []
    for n in (2, 3, 4):
        for i in range(len(run) - n + 1):
            out.append(run[i: i + n])
    return out


def tokenize(text: str) -> List[str]:
    """Cheap Chinese tokenizer: emit all 2-4 char n-grams from CJK runs.

    Greedy `[一-龥]{2,4}` would only match longest non-overlapping chunks,
    missing meaningful sub-tokens (e.g. "工厂的库存" → "工厂的库"+"存", losing
    "库存"). Instead we extract each CJK-only run and emit overlapping 2/3/4-grams.
    Stopwords (single-char) are filtered, but the n-gram width itself excludes
    single-char particles like 的/我/了.
    """
    tokens: List[str] = []
    for run in _CJK_RUN.findall(text):
        tokens.extend(_ngrams_from_run(run))
    return [t for t in tokens if t not in STOP_WORDS]


def extract_new_keywords(query: str, existing: Iterable[str]) -> Set[str]:
    """Return n-grams from `query` that aren't already covered by `existing` keywords.

    Coverage rule: project each existing keyword onto the query as a position
    mask. An n-gram is "new" only if at least one of its characters lies on an
    uncovered position. This prevents emitting bridging n-grams like "询库"
    when both "查询" (covers 0-1) and "库存" (covers 2-3) are known and the full
    query "查询库存" is fully covered.
    """
    existing_set = set(existing)
    if not query:
        return set()

    covered = [False] * len(query)
    for kw in existing_set:
        if not kw:
            continue
        start = 0
        while True:
            idx = query.find(kw, start)
            if idx < 0:
                break
            for i in range(idx, idx + len(kw)):
                covered[i] = True
            start = idx + 1  # allow overlapping match positions

    new: Set[str] = set()
    for match in _CJK_RUN.finditer(query):
        run = match.group()
        run_start = match.start()
        for n in (2, 3, 4):
            for i in range(len(run) - n + 1):
                tok = run[i: i + n]
                if tok in STOP_WORDS or tok in existing_set:
                    continue
                # Skip if every position inside this n-gram is already covered
                abs_start = run_start + i
                if all(covered[abs_start + j] for j in range(n)):
                    continue
                # Skip if this n-gram strictly contains an existing keyword
                if any(k in tok for k in existing_set if k):
                    continue
                new.add(tok)
    return new


class KeywordLearner:
    def __init__(self, pool, existing_keywords: Dict[str, List[str]], *,
                 consent_scope=None, protected_names=None):
        """existing_keywords: {intent_code: [keyword, ...]} loaded from ai_intent_configs."""
        from ai.learning import learning_rules

        self.pool = pool
        self.existing_keywords = existing_keywords
        self._consent_scope = consent_scope or learning_rules.default_consent_scope
        self._protected_names = protected_names or learning_rules.default_protected_names

    async def run_once(self, min_confidence: float = 0.9) -> Dict[Tuple[str, str], Set[str]]:
        """One pass over recent ai_training_samples. Returns {(factory_id, intent_code): set(new_keywords)}.

        工艺保密 (Steve 2026-10-29): 按 (工厂, 意图) 分组, 只写进那家工厂自己的意图行; 没有工厂的样本不学;
        训练授权撤回 / 查不到、提到本厂工艺保密产品、配方参数 / 客户 / 人名的原话不学。

        Persistence: UPDATE ai_intent_configs.keywords with JSON-merge of new keywords.
        DB read error → returns {} gracefully (degrade not crash).
        DB write error → logged, returned dict still reflects what was learned in-memory.
        """
        from ai.learning.learning_rules import factory_learnable

        learned: Dict[Tuple[str, str], Set[str]] = {}
        rows: List[dict] = []
        try:
            async with self.pool.acquire() as conn:
                rows = await conn.fetch(SAMPLE_QUERY_SQL, min_confidence)
        except Exception:
            logger.exception("KeywordLearner: read ai_training_samples failed")
            return learned

        groups: Dict[Tuple[str, str], List[str]] = {}
        for row in rows:
            factory_id = row["factory_id"]
            if not await factory_learnable(factory_id, row["query"],
                                           consent_scope=self._consent_scope,
                                           protected_names=self._protected_names):
                continue
            groups.setdefault((factory_id, row["intent_code"]), []).append(row["query"])

        # 复审 L5 (2026-10-29): 每组现读「本厂那一行」的关键词 (不用启动时按意图代码取的快照 ——
        # 那份快照不分工厂), 并遵守本厂的自动学习开关 (与 Java KeywordLearningService.learnKeywords 一致)。
        # 本厂没有这一行 / 关了自动学习 / 查不了 → 这一组不学。
        try:
            async with self.pool.acquire() as conn:
                for (factory_id, intent_code), queries in groups.items():
                    try:
                        if not await _auto_learn_enabled(conn, factory_id):
                            continue
                        own = await conn.fetchrow(OWN_KEYWORDS_SQL, intent_code, factory_id)
                    except Exception:
                        logger.warning("KeywordLearner: config lookup failed for %s/%s, skipping",
                                       factory_id, intent_code)
                        continue
                    if own is None:
                        continue
                    existing = _keyword_list(own["keywords"])
                    new: Set[str] = set()
                    for query in queries:
                        new.update(extract_new_keywords(query, existing))
                    if new:
                        learned[(factory_id, intent_code)] = new
        except Exception:
            logger.exception("KeywordLearner: reading own intent rows failed")
            return {}

        # F1 fix-pass: persist learned keywords back to ai_intent_configs.
        # Skip persistence if nothing learned (no UPDATEs to issue).
        if learned:
            try:
                async with self.pool.acquire() as conn:
                    for (factory_id, intent_code), new_kws in learned.items():
                        if not new_kws:
                            continue
                        new_kws_json = json.dumps(sorted(new_kws), ensure_ascii=False)
                        # 只写这家工厂自己的行 (UPDATE_KEYWORDS_SQL: factory_id = $3, 非空);
                        # 本厂没有这一行时 UPDATE 0 行, 不落到平台级或别厂的行上
                        await conn.execute(
                            UPDATE_KEYWORDS_SQL, new_kws_json, intent_code, factory_id,
                        )
            except Exception:
                logger.exception("KeywordLearner: persistence UPDATE failed")

        return learned
