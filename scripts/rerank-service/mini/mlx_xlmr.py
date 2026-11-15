"""Minimal MLX port of XLMRobertaForSequenceClassification (inference only) for the intent reranker.
Loads the HF safetensors directly. 2026-11-15 parity vs torch fp32 CrossEncoder: max |diff| 0.0013 (sigmoid space).
"""
from __future__ import annotations

import json
from pathlib import Path

import mlx.core as mx
import mlx.nn as nn


class Layer(nn.Module):
    def __init__(self, h, heads, ff, eps):
        super().__init__()
        self.heads = heads
        self.q = nn.Linear(h, h); self.k = nn.Linear(h, h); self.v = nn.Linear(h, h)
        self.o = nn.Linear(h, h); self.ln1 = nn.LayerNorm(h, eps=eps)
        self.fi = nn.Linear(h, ff); self.fo = nn.Linear(ff, h); self.ln2 = nn.LayerNorm(h, eps=eps)

    def __call__(self, x, mask):
        B, L, H = x.shape
        hd = H // self.heads
        sh = lambda t: t.reshape(B, L, self.heads, hd).transpose(0, 2, 1, 3)
        a = mx.fast.scaled_dot_product_attention(sh(self.q(x)), sh(self.k(x)), sh(self.v(x)),
                                                 scale=hd ** -0.5, mask=mask)
        x = self.ln1(x + self.o(a.transpose(0, 2, 1, 3).reshape(B, L, H)))
        return self.ln2(x + self.fo(nn.gelu(self.fi(x))))


class XLMRReranker(nn.Module):
    def __init__(self, cfg):
        super().__init__()
        h, eps = cfg["hidden_size"], cfg["layer_norm_eps"]
        self.pad = cfg["pad_token_id"]
        self.word = nn.Embedding(cfg["vocab_size"], h)
        self.pos = nn.Embedding(cfg["max_position_embeddings"], h)
        self.tt = nn.Embedding(cfg["type_vocab_size"], h)
        self.ln = nn.LayerNorm(h, eps=eps)
        self.layers = [Layer(h, cfg["num_attention_heads"], cfg["intermediate_size"], eps)
                       for _ in range(cfg["num_hidden_layers"])]
        self.dense = nn.Linear(h, h)
        self.out = nn.Linear(h, len(cfg.get("id2label") or {"0": "LABEL_0"}))  # 重排模型: 1 个标签

    def __call__(self, ids, attn):
        m = attn.astype(mx.int32)
        pos = mx.cumsum(m, axis=1) * m + self.pad  # create_position_ids_from_input_ids
        x = self.word(ids) + self.pos(pos) + self.tt(mx.zeros_like(ids))
        x = self.ln(x)
        mask = mx.where(attn[:, None, None, :].astype(mx.bool_), 0.0, -1e9).astype(x.dtype)
        for layer in self.layers:
            x = layer(x, mask)
        c = mx.tanh(self.dense(x[:, 0]))
        return self.out(c)[:, 0]


_MAP = [("roberta.embeddings.word_embeddings", "word"), ("roberta.embeddings.position_embeddings", "pos"),
        ("roberta.embeddings.token_type_embeddings", "tt"), ("roberta.embeddings.LayerNorm", "ln"),
        ("classifier.dense", "dense"), ("classifier.out_proj", "out")]
_LMAP = [("attention.self.query", "q"), ("attention.self.key", "k"), ("attention.self.value", "v"),
         ("attention.output.dense", "o"), ("attention.output.LayerNorm", "ln1"),
         ("intermediate.dense", "fi"), ("output.dense", "fo"), ("output.LayerNorm", "ln2")]


def load(model_dir: str, dtype=mx.float16) -> XLMRReranker:
    d = Path(model_dir)
    cfg = json.loads((d / "config.json").read_text())
    model = XLMRReranker(cfg)
    raw = mx.load(str(d / "model.safetensors"))
    w = {}
    for k, v in raw.items():
        k2 = k
        for a, b in _MAP:
            if k.startswith(a + "."):
                k2 = b + k[len(a):]
        if k.startswith("roberta.encoder.layer."):
            rest = k[len("roberta.encoder.layer."):]
            n, sub = rest.split(".", 1)
            for a, b in _LMAP:
                if sub.startswith(a + "."):
                    k2 = f"layers.{n}.{b}{sub[len(a):]}"
                    break
        if k2 == k:
            continue  # e.g. pooler / position_ids buffers not used by the classifier
        w[k2] = v.astype(dtype)
    model.load_weights(list(w.items()), strict=True)
    mx.eval(model.parameters())
    return model
