"""Background warmup for factory Dashboard insights."""
from __future__ import annotations

import asyncio
import logging

import asyncpg

logger = logging.getLogger(__name__)

WARMUP_QUESTIONS = [
    "本期生产亮点与隐忧是什么？",
    "哪些产品贡献最大产出？",
    "生产趋势有何变化？",
    "哪个生产环节表现突出？需要重点关注哪里？",
    "近期有什么生产或质量异常值得管理层关注？",
]


async def warmup_factory_insights(
    pool: asyncpg.Pool,
    factory_id: str,
    upload_id: int,
    concurrency: int = 4,
) -> dict:
    """Warm the factory Dashboard cache without recipe/restaurant side effects."""
    logger.info(
        f"[warmup] factory={factory_id} upload={upload_id} "
        f"starting {len(WARMUP_QUESTIONS)} questions at concurrency={concurrency}"
    )
    try:
        from smartbi.agent.orchestrator import AgentOrchestrator
        from smartbi.agent.narrative_cache import NarrativeCache
        from smartbi.agent.budget_tracker import AgentBudgetTracker
    except ImportError as e:
        logger.warning(f"[warmup] agent layer not available: {e}")
        return {"started": 0, "error": str(e)}

    cache = NarrativeCache(pool)
    budget = AgentBudgetTracker(pool)
    orchestrator = AgentOrchestrator(pool, cache, budget)
    from datetime import date, timedelta

    date_end = date.today()
    date_start = date_end - timedelta(days=90)
    semaphore = asyncio.Semaphore(concurrency)

    async def _warmup_one(question: str):
        async with semaphore:
            try:
                import time

                t0 = time.time()
                await orchestrator.answer_insight(
                    factory_id=factory_id,
                    question=question,
                    date_range=(date_start, date_end),
                )
                logger.info(
                    f"[warmup] factory={factory_id} question='{question[:20]}...' "
                    f"warmed in {time.time() - t0:.1f}s"
                )
            except Exception as e:
                logger.warning(
                    f"[warmup] factory={factory_id} question='{question[:20]}...' failed: {e}"
                )

    await asyncio.gather(
        *[_warmup_one(q) for q in WARMUP_QUESTIONS],
        return_exceptions=False,
    )
    logger.info(f"[warmup] factory={factory_id} upload={upload_id} all warmups complete")
    return {"started": len(WARMUP_QUESTIONS), "concurrency": concurrency}
