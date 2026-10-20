"""第 2 步: 本地检索能不能替代 LLM 粗选 (0 token)。只看召回 —— 正确答案在不在候选里。

目录: 清理后的 prod 目录 intents_v3.json (235 个)。金标按 cleanup 动作重映射 (合并的指向目标, 移出/停用的去掉)。
向量: bge-base-zh-v1.5, 查询加 BGE 检索前缀、语料不加 —— 与线上 ai/embedding.py 一致。
例句: intent_examples.json (qwen3.8-flash 生成, 不接触模拟题); 被合并意图的例句并入目标。

变体:
  DESC      查询 vs 意图描述 (≈ 线上 Stage 5 向量召回)
  EX        查询 vs 例句, 按意图取最大相似度
  EX+DESC   两路各取前 k/2 交替合并去重
  BLEND a   max_sim_ex * (1-a) + sim_desc * a
两份样本: future 全量 (按请求加权, 最终验收用) / history 未命中精确表 (第②层真正处理的难样本)。
用法: python retrieval_eval.py
"""
from __future__ import annotations

import collections
import json
import sys

import numpy as np
from sentence_transformers import SentenceTransformer

from bailian import OUT
from task_catalog import TASKS

PREFIX = "为这个句子生成表示以用于检索相关文章："
KS = (5, 8, 10, 15, 20)


def load_v3():
    import os
    xs = json.load(open(OUT / os.environ.get("V3_FILE", "intents_v3.json"), encoding="utf-8"))
    return {x["code"]: x for x in xs}


def intent_text(x):
    kw = x.get("keywords") or []
    if isinstance(kw, str):
        try:
            kw = json.loads(kw)
        except json.JSONDecodeError:
            kw = [kw]
    kws = "、".join(str(k) for k in kw[:8])
    desc = " ".join(str(x.get("desc") or "").split())[:80]
    return f"{x['name']}：{desc}" + (f" 常见说法：{kws}" if kws else "")


def remap(codes, actions, v3):
    out = set()
    for c in codes:
        a = actions.get(c) or ""
        if a.startswith("MERGE_INTO:"):
            c = a.split(":", 1)[1]
        if c in v3 or c == "FOOD_KNOWLEDGE_QUERY":
            out.add(c)
    return out


def main() -> int:
    v3 = load_v3()
    actions = json.load(open(OUT / "cleanup_actions.json", encoding="utf-8"))
    codes = sorted(v3)
    tool_of = {c: v3[c].get("tool") for c in codes}
    alt = {t["id"]: t.get("alt", []) for t in TASKS}

    def acceptable(r):
        base = remap({r["intent"], *alt[r["task"]]}, actions, v3)
        tools = {tool_of[c] for c in base if c in tool_of and tool_of[c]}
        return base | {c for c in codes if tool_of[c] and tool_of[c] in tools}

    # 例句: 合并来源的例句并入目标
    raw_ex = json.load(open(OUT / "intent_examples.json", encoding="utf-8"))
    ex = collections.defaultdict(list)
    for c, xs in raw_ex.items():
        if not isinstance(xs, list):
            continue
        a = actions.get(c) or ""
        tgt = a.split(":", 1)[1] if a.startswith("MERGE_INTO:") else c
        if tgt in v3:
            ex[tgt] += xs
    missing_ex = [c for c in codes if not ex.get(c)]

    m = SentenceTransformer("BAAI/bge-base-zh-v1.5", device="cpu")
    D = m.encode([intent_text(v3[c]) for c in codes], normalize_embeddings=True, batch_size=64, show_progress_bar=False)
    pairs = [(c, s) for c in codes for s in ex.get(c, [])]
    owner = np.array([codes.index(c) for c, _ in pairs])
    E = m.encode([s for _, s in pairs], normalize_embeddings=True, batch_size=128, show_progress_bar=False)

    rows = [json.loads(l) for l in (OUT / "traffic_default.jsonl").read_text(encoding="utf-8").splitlines()]
    sys.path.insert(0, "C:/Users/Steve/cretas-intent-v2/backend/python")
    from ai.registry import build_snapshot, normalize
    seed = json.loads((OUT / "registry_seed.json").read_text(encoding="utf-8"))
    snap = build_snapshot([p for p in seed["phrases"] if p["status"] == "APPROVED"], seed["lexicon"])
    lex, tab = snap.lexicon["F006"], snap.phrases[("F006", "FACTORY")]
    sets = {
        "future 全量": [r for r in rows if r["split"] == "future"],
        "history 未命中精确表": [r for r in rows if r["split"] == "history" and normalize(r["text"], lex) not in tab],
    }
    texts = sorted({r["text"] for rs in sets.values() for r in rs})
    Q = m.encode([PREFIX + t for t in texts], normalize_embeddings=True, batch_size=128, show_progress_bar=False)
    qi = {t: i for i, t in enumerate(texts)}
    simD = Q @ D.T                      # [nq, nintent]
    simE_raw = Q @ E.T                  # [nq, nex]
    simE = np.full((len(texts), len(codes)), -1.0)
    for j in range(len(codes)):
        cols = np.where(owner == j)[0]
        if len(cols):
            simE[:, j] = simE_raw[:, cols].max(axis=1)

    def ranked(scores):
        return np.argsort(-scores, axis=1)

    variants = {"DESC": ranked(simD), "EX": ranked(simE)}
    for a in (0.2, 0.35, 0.5):
        variants[f"BLEND {a}"] = ranked(simE * (1 - a) + simD * a)
    rD, rE = variants["DESC"], variants["EX"]

    def union_topk(i, k):
        out = []
        for a, b in zip(rE[i], rD[i]):
            for c in (a, b):
                if c not in out:
                    out.append(c)
            if len(out) >= k:
                return out[:k]
        return out[:k]

    print(f"目录 {len(codes)} 个意图 | 例句 {len(pairs)} 条 | 没有例句的意图 {len(missing_ex)}: {missing_ex[:12]}")
    for name, rs in sets.items():
        n = len(rs)
        fk = sum(1 for r in rs if r["intent"] == "FOOD_KNOWLEDGE_QUERY")
        print(f"\n== {name}: {n} 次请求 (其中食品知识问答 {fk} 次, 不在目录里, 任何召回都是 0)")
        accs = [{codes.index(c) for c in acceptable(r) if c in v3} for r in rs]
        for vname, R in list(variants.items()) + [("EX∪DESC", None)]:
            cells = []
            for k in KS:
                hit = 0
                for r, acc in zip(rs, accs):
                    i = qi[r["text"]]
                    top = union_topk(i, k) if R is None else list(R[i][:k])
                    hit += bool(acc & set(top))
                cells.append(f"@{k} {hit / n:6.1%}")
            print(f"  {vname:10s} " + "  ".join(cells))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
