"""第②层候选的重排 (2026-10-04): 向量 Top-N → 微调重排模型 → 前 K 个给决策模型。

重排模型是用本仓数据微调过的 bge-reranker-v2-m3 (蒸馏第一步, 评测仓 sim/train_reranker.py),
上线前阶段跑在开发机 RTX 3060 上, 经 FRP STCP 私密通道接到 prod —— 47 只在 127.0.0.1:18190 上看得到它。

2026-10-04 离线 (history 段未命中精确表的 767 条难题, 不在训练集里):
    现行 LLM 粗选 + 向量补足 (~7k token/次): 召回 @10 67.9% / @20 75.7%
    向量(例句+描述) Top-50 → 微调重排 (0 token):  召回 @10 73.5% / @20 79.1%
⇒ 用重排取代 LLM 粗选: 召回更高, 每次请求少约 7k token。

⛔ 失败一律返回 None, 由编排器退回原来的「LLM 粗选 + 向量补足」—— 重排服务在开发机上,
   它不在线是常态之一, 不能让识别跟着它挂。
"""
from __future__ import annotations

import logging
from typing import List, Optional

import httpx

logger = logging.getLogger(__name__)


class RerankClient:
    def __init__(self, url: str, key: str, timeout_s: float = 0.6):
        self.url = url
        self.key = key
        self.timeout_s = timeout_s

    async def scores(self, query: str, documents: List[str]) -> Optional[List[float]]:
        """与 documents 同序的相关度; 任何失败 (超时 / 非 200 / 条数不符) → None。"""
        if not documents:
            return None
        try:
            async with httpx.AsyncClient(timeout=self.timeout_s) as client:
                resp = await client.post(self.url, json={"query": query[:300], "documents": documents},
                                         headers={"X-Rerank-Key": self.key})
            if resp.status_code != 200:
                logger.warning("rerank: HTTP %s", resp.status_code)
                return None
            out = resp.json().get("scores")
            if not isinstance(out, list) or len(out) != len(documents):
                logger.warning("rerank: bad payload (len %s vs %s)", len(out) if isinstance(out, list) else None,
                               len(documents))
                return None
            return [float(x) for x in out]
        except Exception as ex:  # noqa: BLE001
            logger.warning("rerank failed: %s", str(ex)[:200])
            return None


__all__ = ["RerankClient"]
