"""Read-only candidate snapshot using the production retriever components (line E).

Run on the Python host from ``backend/python`` with the service's own environment. For each
utterance it records what prod's candidate stage returns, with no LLM call and no write:

  - ``blend``:  BlendRetriever.match(top_k=20)         (vector over examples + descriptions)
  - ``rerank``: BlendRetriever top-50 -> RerankClient   (the prod no-history path, top-10)

Input: JSON list of utterances on stdin. Output: JSON on stdout. Every DB statement is a
SELECT issued by the existing retriever; the session is additionally set read-only.
"""
import asyncio
import json
import sys
import time


async def main():
    from ai.config import default_config as cfg
    from ai.matcher.candidates import BlendRetriever
    from ai.matcher.rerank import RerankClient
    from ai.matcher.decision import intent_text
    from smartbi.config import get_cretas_pool

    utterances = json.load(sys.stdin)
    pool = await get_cretas_pool()
    async with pool.acquire() as conn:
        await conn.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY")
        rows = await conn.fetch("SELECT intent_code, intent_name, description, keywords FROM ai_intent_configs "
                                "WHERE deleted_at IS NULL AND is_active = true")
    by_code = {r["intent_code"]: dict(r) for r in rows}
    retriever = BlendRetriever(pool, top_k=cfg.decision_k, blend=cfg.decision_candidate_blend)
    rerank = RerankClient(cfg.rerank_url, cfg.rerank_key, timeout_s=max(cfg.rerank_timeout_s, 2.0)) \
        if cfg.rerank_url and cfg.rerank_key else None
    out = {}
    for q in utterances:
        rec = {}
        t = time.perf_counter()
        blend = await retriever.match(q, factoryId="F006", businessType="FACTORY")
        rec["blend"] = [[c.intentCode, round(float(c.confidence or 0), 4)] for c in blend]
        rec["blend_ms"] = round((time.perf_counter() - t) * 1000, 1)
        if rerank is not None:
            t = time.perf_counter()
            pool50 = await retriever.match(q, factoryId="F006", businessType="FACTORY", top_k=cfg.rerank_pool_k)
            codes = [c.intentCode for c in pool50 if c.intentCode in by_code]
            scores = await rerank.scores(q, [intent_text(by_code[c])[:120] for c in codes]) if codes else None
            rec["rerank"] = None if not scores else [
                [c, round(float(s), 4)] for c, s in sorted(zip(codes, scores), key=lambda kv: -kv[1])][: cfg.rerank_top_k]
            rec["rerank_ms"] = round((time.perf_counter() - t) * 1000, 1)
        out[q] = rec
    await pool.close()
    json.dump(dict(config=dict(k=cfg.decision_k, blend=cfg.decision_candidate_blend, pool_k=cfg.rerank_pool_k,
                               top_k=cfg.rerank_top_k, rerank=bool(rerank)), results=out), sys.stdout, ensure_ascii=False)


if __name__ == "__main__":
    asyncio.run(main())
