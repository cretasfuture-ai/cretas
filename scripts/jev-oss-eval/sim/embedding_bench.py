"""换更好的向量模型能好到哪 (2026-10-03, Steve: 「用更好的向量模型能优化到什么地步」)。

与 retrieval_eval.py 同一套题、同一份目录 (intents_v3.json, 清理后 235)、同一套金标重映射,
只把向量模型换掉。每个模型报:
  召回 @1 @5 @10 @20 (正确功能在前 k 个候选里) —— 两种打分: 只比描述 (DESC) / 例句与描述混合 (BLEND 0.35, 线上口径)
  CPU 单条查询编码耗时中位数 (服务器只有 CPU; 评测本身可用 GPU)
用法: python embedding_bench.py [模型名...]   (缺省跑 MODELS 全部)
"""
from __future__ import annotations

import collections
import json
import statistics
import sys
import time

import numpy as np
import torch
from sentence_transformers import SentenceTransformer

from bailian import OUT
from retrieval_eval import intent_text, load_v3, remap
from task_catalog import TASKS

BGE_PREFIX = "为这个句子生成表示以用于检索相关文章："
QWEN_PROMPT = "Instruct: 给定食品加工厂员工说的一句话，检索能处理这句话的系统功能\nQuery:"
# 名字 -> (HF 模型, 查询前缀)。语料侧一律不加前缀 (与线上一致)。
MODELS = {
    "bge-base": ("BAAI/bge-base-zh-v1.5", BGE_PREFIX),
    "bge-large": ("BAAI/bge-large-zh-v1.5", BGE_PREFIX),
    "bge-m3": ("BAAI/bge-m3", ""),
    "qwen3-emb-0.6b": ("Qwen/Qwen3-Embedding-0.6B", QWEN_PROMPT),
    # 百炼 API (A/B/C 免费额度), 在服务器上算, 密钥不出服务器
    "api:qwen3.7-text-embedding": ("qwen3.7-text-embedding", ""),
    "api:qwen3.7-text-embedding-flash": ("qwen3.7-text-embedding-flash", ""),
}
HOST = "root@47.100.235.168"
RDIR = "/tmp/jev_remote"
PY = "/www/wwwroot/cretas/code/backend/python/venv-current/bin/python"


def api_encode(model: str, texts: list[str], tag: str) -> np.ndarray:
    import subprocess
    from pathlib import Path
    here = Path(__file__).resolve().parent
    fin, fout = OUT / f"emb_{tag}_in.json", OUT / f"emb_{tag}_out.npy"
    if fout.exists():
        v = np.load(fout)
        if len(v) == len(texts):
            return v
    fin.write_text(json.dumps(texts, ensure_ascii=False), encoding="utf-8")
    ssh = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=20", HOST]
    subprocess.run(["scp", "-q", "-o", "BatchMode=yes", str(here / "embed_api_server.py"), str(fin), f"{HOST}:{RDIR}/"], check=True)
    r = subprocess.run(ssh + [f"cd {RDIR} && {PY} embed_api_server.py embed {model} {fin.name} {fout.name} > {tag}.log 2>&1; tail -2 {tag}.log"],
                       capture_output=True, text=True, stdin=subprocess.DEVNULL)
    print(tag, r.stdout.strip()[-200:], flush=True)
    subprocess.run(["scp", "-q", "-o", "BatchMode=yes", f"{HOST}:{RDIR}/{fout.name}", str(fout)], check=True)
    v = np.load(fout)
    assert len(v) == len(texts), (len(v), len(texts))
    return v / np.linalg.norm(v, axis=1, keepdims=True)
KS = (1, 5, 10, 20)


def data():
    v3 = load_v3()
    actions = json.load(open(OUT / "cleanup_actions.json", encoding="utf-8"))
    codes = sorted(v3)
    tool_of = {c: v3[c].get("tool") for c in codes}
    alt = {t["id"]: t.get("alt", []) for t in TASKS}

    def acceptable(r):
        base = remap({r["intent"], *alt[r["task"]]}, actions, v3)
        tools = {tool_of[c] for c in base if c in tool_of and tool_of[c]}
        return base | {c for c in codes if tool_of[c] and tool_of[c] in tools}

    raw_ex = json.load(open(OUT / "intent_examples.json", encoding="utf-8"))
    ex = collections.defaultdict(list)
    for c, xs in raw_ex.items():
        if isinstance(xs, list):
            a = actions.get(c) or ""
            tgt = a.split(":", 1)[1] if a.startswith("MERGE_INTO:") else c
            if tgt in v3:
                ex[tgt] += xs
    rows = [json.loads(l) for l in (OUT / "traffic_default.jsonl").read_text(encoding="utf-8").splitlines()]
    fut = [r for r in rows if r["split"] == "future" and r["intent"] != "FOOD_KNOWLEDGE_QUERY"]
    accs = [{codes.index(c) for c in acceptable(r) if c in v3} for r in fut]
    return v3, codes, ex, fut, accs


def bench(name: str, v3, codes, ex, fut, accs) -> dict:
    hf, prefix = MODELS[name]
    is_api = name.startswith("api:")
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    pairs = [(c, s) for c in codes for s in ex.get(c, [])]
    owner = np.array([codes.index(c) for c, _ in pairs])
    texts = sorted({r["text"] for r in fut})
    qi = {t: i for i, t in enumerate(texts)}
    d_txt, e_txt, q_txt = [intent_text(v3[c]) for c in codes], [s for _, s in pairs], [prefix + t for t in texts]
    if is_api:
        allv = api_encode(hf, d_txt + e_txt + q_txt, hf.replace(".", "_"))
        D, E, Q = allv[:len(d_txt)], allv[len(d_txt):len(d_txt) + len(e_txt)], allv[len(d_txt) + len(e_txt):]
    else:
        m = SentenceTransformer(hf, device=dev)
        enc = lambda xs, bs=64: m.encode(xs, normalize_embeddings=True, batch_size=bs, show_progress_bar=False)
        D, E, Q = enc(d_txt), enc(e_txt, 128), enc(q_txt, 128)
    simD = Q @ D.T
    simE_raw = Q @ E.T
    simE = np.full((len(texts), len(codes)), -1.0)
    for j in range(len(codes)):
        cols = np.where(owner == j)[0]
        if len(cols):
            simE[:, j] = simE_raw[:, cols].max(axis=1)
    out = {"model": hf, "device": "api" if is_api else dev}
    for vname, S in (("DESC", simD), ("BLEND0.35", simE * 0.65 + simD * 0.35)):
        R = np.argsort(-S, axis=1)
        for k in KS:
            hit = sum(bool(acc & set(R[qi[r["text"]]][:k])) for r, acc in zip(fut, accs))
            out[f"{vname}@{k}"] = round(hit / len(fut) * 100, 1)
    np.save(OUT / f"bench_scores_{name.replace(':', '_').replace('.', '_')}.npy", simE * 0.65 + simD * 0.35)
    if is_api:
        out["cpu_ms_p50"] = "api"
        return out
    # CPU 单条耗时 (服务器口径)
    mc = SentenceTransformer(hf, device="cpu")
    sample = texts[:40]
    mc.encode([prefix + sample[0]])  # 预热
    ts = []
    for t in sample:
        t0 = time.perf_counter()
        mc.encode([prefix + t], normalize_embeddings=True)
        ts.append((time.perf_counter() - t0) * 1000)
    out["cpu_ms_p50"] = round(statistics.median(ts), 1)
    return out


def main() -> int:
    names = sys.argv[1:] or list(MODELS)
    v3, codes, ex, fut, accs = data()
    print(f"目录 {len(codes)} | 例句意图 {len(ex)} | 题目 {len(fut)} 次请求 (future, 去掉不在目录里的食品问答)", flush=True)
    results = []
    for n in names:
        t0 = time.time()
        r = bench(n, v3, codes, ex, fut, accs)
        r["name"] = n
        r["wall_s"] = round(time.time() - t0)
        results.append(r)
        print(json.dumps(r, ensure_ascii=False), flush=True)
    (OUT / "embedding_bench.json").write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n{'模型':<16}" + "".join(f"{v}@{k:<4}" for v in ("D", "B") for k in KS) + "  CPU ms")
    for r in results:
        print(f"{r['name']:<16}" + "".join(f"{r[f'{v}@{k}']:>7.1f}" for v in ("DESC", "BLEND0.35") for k in KS)
              + f"  {str(r['cpu_ms_p50']):>6}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
