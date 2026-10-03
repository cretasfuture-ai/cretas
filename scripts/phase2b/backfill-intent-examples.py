#!/usr/bin/env python3
"""把意图例句写进 ai_intent_examples, 向量由 cretas-embedding 服务计算 (与线上查询同一个模型)。

输入 JSON: {"INTENT_CODE": ["例句1", "例句2", ...], ...}
  · 只写目录里在用的意图 (deleted_at IS NULL AND is_active), 其余跳过并计数
  · 语料侧**不加** BGE 检索前缀 (查询侧加, 见 ai/embedding.py)
  · 幂等: (intent_code, example_text) 唯一, 已存在的跳过; --refresh 重算已有行的向量
在服务器上运行 (需要能访问 embedding 服务 :9090):
    cd /www/wwwroot/cretas/code/backend/python
    venv-current/bin/python /path/to/backfill-intent-examples.py --db cretas_prod_db --input examples.json --source SIM_GEN
数据库口令从环境变量 FOOD_KB_POSTGRES_PASSWORD 读 (⛔ 不设默认值)。
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
import time

sys.path.insert(0, "/www/wwwroot/cretas/code/backend/python")

import asyncpg  # noqa: E402

from ai.embedding import get_embedding, vec_to_pgvector_text  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("backfill-examples")

ACTIVE_SQL = "SELECT DISTINCT intent_code FROM ai_intent_configs WHERE deleted_at IS NULL AND is_active = true"
EXISTING_SQL = "SELECT intent_code, example_text, embedding IS NULL AS no_emb FROM ai_intent_examples"
INSERT_SQL = """
INSERT INTO ai_intent_examples (intent_code, example_text, embedding, source)
VALUES ($1, $2, $3::vector, $4)
ON CONFLICT (intent_code, example_text) DO UPDATE SET embedding = EXCLUDED.embedding
"""


async def main(args) -> int:
    password = os.environ.get("FOOD_KB_POSTGRES_PASSWORD")
    if not password:
        logger.error("FOOD_KB_POSTGRES_PASSWORD 未设置")
        return 2
    user = os.environ.get("FOOD_KB_POSTGRES_USER", "cretas_user")
    host = os.environ.get("FOOD_KB_POSTGRES_HOST", "localhost")
    data = json.load(open(args.input, encoding="utf-8"))
    pool = await asyncpg.create_pool(user=user, password=password, host=host, database=args.db, min_size=1, max_size=3)
    try:
        async with pool.acquire() as conn:
            active = {r["intent_code"] for r in await conn.fetch(ACTIVE_SQL)}
            existing = {(r["intent_code"], r["example_text"]): r["no_emb"] for r in await conn.fetch(EXISTING_SQL)}
        todo, skipped_inactive = [], 0
        for code, xs in data.items():
            if code not in active:
                skipped_inactive += len(xs) if isinstance(xs, list) else 0
                continue
            for x in xs if isinstance(xs, list) else []:
                x = str(x).strip()[:300]
                key = (code, x)
                if not x or (key in existing and not existing[key] and not args.refresh):
                    continue
                todo.append(key)
        todo = list(dict.fromkeys(todo))
        logger.info("db=%s 在用意图 %d | 待写 %d 条 | 已存在跳过 %d | 意图不在用跳过 %d 条",
                    args.db, len(active), len(todo), len(existing), skipped_inactive)
        ok = fail = 0
        t0 = time.time()
        for i, (code, text) in enumerate(todo, 1):
            vec = await get_embedding(text)
            if vec is None or len(vec) != 768:
                fail += 1
                continue
            async with pool.acquire() as conn:
                await conn.execute(INSERT_SQL, code, text, vec_to_pgvector_text(vec), args.source)
            ok += 1
            if i % 500 == 0:
                logger.info("进度 %d/%d (%.1f 条/s, 失败 %d)", i, len(todo), i / (time.time() - t0), fail)
        async with pool.acquire() as conn:
            total = await conn.fetchval("SELECT count(*) FROM ai_intent_examples WHERE embedding IS NOT NULL")
            n_int = await conn.fetchval("SELECT count(DISTINCT intent_code) FROM ai_intent_examples")
        logger.info("完成: 写入 %d / 失败 %d | 表内有向量的例句 %d 条, 覆盖 %d 个意图 | %.1fs",
                    ok, fail, total, n_int, time.time() - t0)
        return 1 if fail else 0
    finally:
        await pool.close()


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--db", required=True, choices=["cretas_prod_db", "cretas_db"])
    p.add_argument("--input", required=True)
    p.add_argument("--source", default="SIM_GEN")
    p.add_argument("--refresh", action="store_true", help="重算已存在例句的向量")
    raise SystemExit(asyncio.run(main(p.parse_args())))
