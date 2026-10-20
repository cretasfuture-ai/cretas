"""蒸馏第一步: 用我们的数据微调开源重排模型 (Steve 2026-10-03 批准「蒸馏可以做」)。

数据 (只用「现实里拿得到」的标签):
  正样本  ① 生成例句 intent_examples.json (按清理动作并入 v3 目录, 标签即生成时的目标意图)
          ② 老师标注的 history 段说法 (registry_seed.json 里 APPROVED 的 sample_text → intent_code)
  负样本  该问句在 bge-base 下的 Top-20 里不可接受的意图 (难负例) 最多 7 个 + 1 个随机
防泄题  训练问句与 future 段 (测试集) 原句完全相同的一律剔除
测试    future 段 1268 次请求, 与 embedding_bench / local_rerank_bench 同口径;
        另报「归一化后从没在训练里出现过的问句」子集, 看泛化而不是背题
用法: python train_reranker.py BASE_NAME [epochs]
"""
from __future__ import annotations

import hashlib
import json
import os
import random
import sys
import time

import numpy as np
import torch
from datasets import Dataset
from sentence_transformers import SentenceTransformer
from sentence_transformers.cross_encoder import CrossEncoder, CrossEncoderTrainer, CrossEncoderTrainingArguments
from sentence_transformers.cross_encoder.losses import BinaryCrossEntropyLoss

from bailian import OUT
from embedding_bench import BGE_PREFIX, data
from retrieval_eval import intent_text

BASES = {"bge-reranker-v2-m3": "BAAI/bge-reranker-v2-m3",
         "qwen3-reranker-0.6b": "tomaarsen/Qwen3-Reranker-0.6B-seq-cls"}
N_HARD = 7
SEED = 20261003


def canon_map():
    actions = json.load(open(OUT / "cleanup_actions.json", encoding="utf-8"))
    return lambda c: (actions.get(c) or "").split(":", 1)[1] if (actions.get(c) or "").startswith("MERGE_INTO:") else c


def suite_half(item_id: str) -> int:
    """考题对半分: 0 = 训练半, 1 = 验收半 (按 id 的 md5, 跨进程稳定)。"""
    return int(hashlib.md5(item_id.encode("utf-8")).hexdigest(), 16) % 2


def build_pairs(v3, codes, ex, fut):
    canon = canon_map()
    test_texts = {r["text"] for r in fut}
    tool_of = {c: v3[c].get("tool") for c in codes}

    def acceptable(c):
        t = tool_of.get(c)
        return {c} | ({x for x in codes if t and tool_of[x] == t})

    pos = [(s, c) for c, xs in ex.items() for s in xs]
    seed = json.loads((OUT / "registry_seed.json").read_text(encoding="utf-8"))
    pos += [(p["sample_text"], canon(p["intent_code"])) for p in seed["phrases"] if p["status"] == "APPROVED"]
    if os.environ.get("RR_SUITE") == "1":
        # 第二轮 (2026-10-04): 理解力考题按 id 哈希对半分, 只用「训练半」; 另一半留给 suite_rerank_eval.py 验收
        suite = [json.loads(l) for l in (OUT / "understanding_suite.jsonl").read_text(encoding="utf-8").splitlines() if l]
        add = [(it["text"], it["expect"]) for it in suite
               if suite_half(it["id"]) == 0 and it["kind"] in ("plain", "implicit", "trap") and it["expect"] in v3]
        print(f"考题训练半加入 {len(add)} 条正样本", flush=True)
        pos += add
        # 第三轮 (2026-10-05) 防泄题: 验收半的原句 (任何题型) 不许以任何来源 (例句/种子/训练半) 出现在训练正样本里
        held = {it["text"] for it in suite if suite_half(it["id"]) == 1}
        assert not ({it["id"] for it in suite if suite_half(it["id"]) == 0} & {it["id"] for it in suite if suite_half(it["id"]) == 1})
        leak = [(s, c) for s, c in pos if s in held]
        print(f"防泄题: 训练正样本里与验收半原句完全相同的 {len(leak)} 条, 剔除", flush=True)
        test_texts = test_texts | held
    pos = [(s, c) for s, c in pos if c in v3 and s not in test_texts]
    pos = list(dict.fromkeys(pos))
    m = SentenceTransformer("BAAI/bge-base-zh-v1.5", device="cuda" if torch.cuda.is_available() else "cpu")
    D = m.encode([intent_text(v3[c]) for c in codes], normalize_embeddings=True, batch_size=64, show_progress_bar=False)
    Q = m.encode([BGE_PREFIX + s for s, _ in pos], normalize_embeddings=True, batch_size=256, show_progress_bar=False)
    top = np.argsort(-(Q @ D.T), axis=1)[:, :20]
    docs = {c: intent_text(v3[c])[:120] for c in codes}
    rnd = random.Random(SEED)
    q, d, y = [], [], []
    for (s, c), row in zip(pos, top):
        ok = acceptable(c)
        q.append(s); d.append(docs[c]); y.append(1.0)
        hard = [codes[j] for j in row if codes[j] not in ok][:N_HARD]
        rand = rnd.choice([x for x in codes if x not in ok])
        for n in hard + [rand]:
            q.append(s); d.append(docs[n]); y.append(0.0)
    print(f"正样本问句 {len(pos)} | 训练对 {len(q)} (正 {int(sum(y))} / 负 {len(y) - int(sum(y))})", flush=True)
    return Dataset.from_dict({"query": q, "doc": d, "label": y}), {s for s, _ in pos}


def evaluate(model, v3, codes, fut, accs, train_texts, K=20):
    texts = sorted({r["text"] for r in fut})
    qi = {t: i for i, t in enumerate(texts)}
    S = np.load(OUT / "bench_scores_bge-base.npy")
    R = np.argsort(-S, axis=1)[:, :K]
    docs = [intent_text(v3[c])[:120] for c in codes]
    sc = model.predict([(texts[i], docs[j]) for i in range(len(texts)) for j in R[i]], batch_size=128,
                       show_progress_bar=False).reshape(len(texts), K)
    sys.path.insert(0, "C:/Users/Steve/cretas-intent-v2/backend/python")
    from ai.registry import build_snapshot, normalize
    seed = json.loads((OUT / "registry_seed.json").read_text(encoding="utf-8"))
    lex = build_snapshot([], seed["lexicon"]).lexicon["F006"]
    train_keys = {normalize(t, lex) for t in train_texts}
    subsets = {"全部": [(r, a) for r, a in zip(fut, accs)],
               "训练里没见过的说法": [(r, a) for r, a in zip(fut, accs) if normalize(r["text"], lex) not in train_keys]}
    for name, rows in subsets.items():
        for label, order in (("向量", lambda i: list(R[i])),
                             ("向量→微调重排", lambda i: [R[i][j] for j in np.argsort(-sc[i])])):
            cells = []
            for k in (1, 3, 5, 10):
                hit = sum(bool(acc & set(order(qi[r["text"]])[:k])) for r, acc in rows)
                cells.append(f"@{k} {hit / max(len(rows), 1) * 100:5.1f}%")
            print(f"  [{name} {len(rows)}] {label:<10} " + "  ".join(cells), flush=True)


def main() -> int:
    base = sys.argv[1]
    epochs = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
    v3, codes, ex, fut, accs = data()
    ds, train_texts = build_pairs(v3, codes, ex, fut)
    ds = ds.shuffle(seed=SEED)
    # 12GB 显存: 每批 32 × 160 会溢出到共享内存, 11 秒一步 (实测) ⇒ 每批 8 × 梯度累积 4, 长度 128
    model = CrossEncoder(BASES[base], num_labels=1, max_length=128)
    out_dir = OUT / (f"reranker_ft_{base}" + os.environ.get("RR_TAG", ""))
    args = CrossEncoderTrainingArguments(
        output_dir=str(out_dir), num_train_epochs=epochs, per_device_train_batch_size=8, gradient_accumulation_steps=4, learning_rate=2e-5,
        warmup_ratio=0.1, fp16=torch.cuda.is_available(), logging_steps=100, save_strategy="no", report_to=[],
        seed=SEED)
    loss = BinaryCrossEntropyLoss(model, pos_weight=torch.tensor(float(N_HARD + 1)))
    t0 = time.time()
    CrossEncoderTrainer(model=model, args=args, train_dataset=ds, loss=loss).train()
    print(f"训练用时 {time.time() - t0:.0f}s", flush=True)
    model.save_pretrained(str(out_dir / "final"))
    evaluate(model, v3, codes, fut, accs, train_texts)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
