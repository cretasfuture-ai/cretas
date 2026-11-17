"""意图候选重排服务 · Mac mini MLX 版 (2026-11-15 起线上主用; Windows RTX 3060 的 ../app.py 留作备份)。

与 ../app.py 接口逐字相同 (编排器 backend/python/ai/matcher/rerank.py 不用改):
  GET  /health                         -> {"ok", "model", "device"}
  POST /rerank  (头 X-Rerank-Key)       {"query": str, "documents": [str, ...] (<=100)}
                                       -> {"scores": [float, ...] 与 documents 同序, "ms": int, "model"}
分数与 sentence-transformers CrossEncoder 同口径 (单标签 → sigmoid), 与 Windows 版可互换。

为什么是 MLX 而不是 torch mps: 2026-11-15 在 M4 Pro 上实测 1×50 候选, torch mps fp16 p95 613ms,
MLX fp16 p95 542ms (同权重, 与 torch fp32 分数最大差 0.0013)。详见 README「Mac mini」一节。

MLX 不是线程安全的、GPU 也只有一块 ⇒ 推理加锁串行; uvicorn 单 worker。
只监听 127.0.0.1。密钥从同目录 .env 的 RERANK_KEY 读, 不对外打印。
"""
from __future__ import annotations

import os
import threading
import time
from pathlib import Path

import mlx.core as mx
import numpy as np
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from transformers import AutoTokenizer

from mlx_xlmr import load

HERE = Path(__file__).resolve().parent


def _env() -> dict:
    out = {}
    p = HERE / ".env"
    if p.exists():
        for line in p.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.strip().startswith("#"):
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


ENV = _env()
KEY = ENV.get("RERANK_KEY") or os.environ.get("RERANK_KEY") or ""
MODEL_DIR = ENV.get("MODEL_DIR") or os.environ.get("MODEL_DIR") or str(HERE / "model")
MODEL_TAG = ENV.get("MODEL_TAG") or "bge-reranker-v2-m3-ft-r2-20261004"
if not KEY:
    raise SystemExit("RERANK_KEY missing in .env — refusing to start without auth")

# MLX 按每种输入 shape 留着 Metal buffer 复用, 不设上限只涨不还: 2026-11-18 实测连续跑 2 天 6 小时
# 进程 footprint 38GB (模型本身 fp16 约 1.1GB), 把同机常驻的 ollama 8b 兜底和黑湖 27B 挤出内存。
# 上限之外的空闲 buffer 会被释放; 上限只影响复用率, 不影响结果。默认 1GB, 可用 .env 的 MLX_CACHE_LIMIT_MB 调。
CACHE_LIMIT_MB = int(ENV.get("MLX_CACHE_LIMIT_MB") or os.environ.get("MLX_CACHE_LIMIT_MB") or 1024)
mx.set_cache_limit(CACHE_LIMIT_MB * 1024 * 1024)

TOK = AutoTokenizer.from_pretrained(MODEL_DIR)
MODEL = load(MODEL_DIR, mx.float16)
LOCK = threading.Lock()


def _scores(query: str, docs: list[str]) -> list[float]:
    enc = TOK([query] * len(docs), docs, padding=True, truncation=True, max_length=128, return_tensors="np")
    with LOCK:
        logits = MODEL(mx.array(enc["input_ids"]), mx.array(enc["attention_mask"]))
        out = np.array(mx.sigmoid(logits.astype(mx.float32)))
    return [float(s) for s in out]


_scores("预热", ["预热"] * 50)  # 首次编译/分配放在启动时

app = FastAPI(title="cretas-intent-rerank-mlx")


class RerankRequest(BaseModel):
    query: str = Field(min_length=1, max_length=300)
    documents: list[str] = Field(min_length=1, max_length=100)


@app.get("/health")
def health():
    mb = lambda b: round(b / 1048576)  # noqa: E731
    return {"ok": True, "model": MODEL_TAG, "device": "mlx-fp16",
            "memoryMb": {"active": mb(mx.get_active_memory()), "cache": mb(mx.get_cache_memory()),
                         "peak": mb(mx.get_peak_memory()), "cacheLimit": CACHE_LIMIT_MB}}


@app.post("/rerank")
def rerank(req: RerankRequest, x_rerank_key: str = Header(default="")):
    if x_rerank_key != KEY:
        raise HTTPException(status_code=401, detail="bad key")
    t0 = time.perf_counter()
    scores = _scores(req.query, [d[:300] for d in req.documents])
    ms = int((time.perf_counter() - t0) * 1000)
    # 每次一行带时间戳 (Windows 版的 uvicorn access log 不带时间, 切换前后的成功计数没法按小时数)
    print(f"{time.strftime('%Y-%m-%d %H:%M:%S')} rerank ok n={len(scores)} ms={ms}", flush=True)
    return {"scores": scores, "ms": ms, "model": MODEL_TAG}
