"""意图候选重排服务 (2026-10-04, 上线前阶段跑在本机 RTX 3060 上)。

模型: 用本仓数据微调过的 bge-reranker-v2-m3 (蒸馏第一步, 见评测仓 sim/train_reranker.py)。
调用方: prod 47 上的 Python 识别服务, 经 FRP STCP 私密通道 (47 只在 127.0.0.1:18190 上看得到它)。

接口:
  GET  /health                         -> {"ok", "model", "device"}
  POST /rerank  (头 X-Rerank-Key)       {"query": str, "documents": [str, ...] (<=100)}
                                       -> {"scores": [float, ...] 与 documents 同序, "ms": int}
只监听 127.0.0.1。密钥从同目录 .env 的 RERANK_KEY 读, 不对外打印。
"""
from __future__ import annotations

import os
import time
from pathlib import Path

import torch
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from sentence_transformers.cross_encoder import CrossEncoder

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
MODEL_TAG = ENV.get("MODEL_TAG") or "bge-reranker-v2-m3-ft-20261004"
if not KEY:
    raise SystemExit("RERANK_KEY missing in .env — refusing to start without auth")

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
MODEL = CrossEncoder(MODEL_DIR, device=DEVICE, max_length=128)
if DEVICE == "cuda":
    # 2026-10-04 实测 fp32: 50 个候选 287ms, 3 个并发排队到 850ms, 线上 600ms 超时大面积退回粗选 ⇒ 改半精度
    MODEL.model.half()
MODEL.predict([("预热", "预热")])  # 第一次调用的 CUDA 初始化放在启动时, 不算进请求延迟

app = FastAPI(title="cretas-intent-rerank")


class RerankRequest(BaseModel):
    query: str = Field(min_length=1, max_length=300)
    documents: list[str] = Field(min_length=1, max_length=100)


@app.get("/health")
def health():
    return {"ok": True, "model": MODEL_TAG, "device": DEVICE}


@app.post("/rerank")
def rerank(req: RerankRequest, x_rerank_key: str = Header(default="")):
    if x_rerank_key != KEY:
        raise HTTPException(status_code=401, detail="bad key")
    t0 = time.perf_counter()
    docs = [d[:300] for d in req.documents]
    scores = MODEL.predict([(req.query, d) for d in docs], batch_size=64, show_progress_bar=False)
    return {"scores": [float(s) for s in scores], "ms": int((time.perf_counter() - t0) * 1000),
            "model": MODEL_TAG}
