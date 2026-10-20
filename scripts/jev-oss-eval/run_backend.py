"""第二段: 对每道题问 3 个问题 (网关 4 选 1 / 是否写操作 / 从 K 个候选里选工具), 各后端同一份输入。

用法: python run_backend.py <backend> [--k 10] [--limit N]
backend: von | laya-en | laya-ml | od | od-ml | sjev (需先起 simple-jev 服务, --model 指定)

输出 results/<backend>[_<tag>].jsonl, 每行一题, 含预测/概率/置信度/耗时/错误。
失败不跳过: 记 error 字段并计数 (规则 C': continue 会丢掉最有信息量的样本)。
"""
from __future__ import annotations

import argparse
import json
import random
import sys
import time
import traceback
from pathlib import Path

from common import HERE, ROUTES, load_testset, load_tools, state_text, tool_text

SRC = Path("D:/jev-eval/src")
GATE_INSTR = "判断用户这句话属于哪一类请求"
WRITE_INSTR = "用户这句话是否要求新增、修改、删除或提交系统里的业务数据（会改动数据）？"
WRITE_CRITERIA = {"true": "是，要改动系统里的数据", "false": "否，只是查看、咨询或闲聊"}
FINE_INSTR = "用户这句话应该交给下面哪个系统功能来处理？"


ROUTES_EN = {
    "read": "Look up or analyse this business's own data (stock, revenue, output, QC results, attendance, orders, reports) without changing anything",
    "write": "Create, modify, delete or submit a business record (stock in/out, work report, price change, delisting a dish, logging waste, clock-in, leave, purchase order, approval); this changes data in the system",
    "consult": "Ask for knowledge, standards, methods or advice (food safety rules, process requirements, how to do better, how to use the system), not a lookup or change of a specific record",
    "oos": "Small talk, greetings, personal questions or requests unrelated to running a food factory or restaurant",
}
CTX_EN = {"factory": "employee of a food processing factory (braised meat products)", "restaurant": "restaurant owner or store manager"}


def build_questions_en(row: dict, cands: list[str], tools: dict, desc_len: int) -> dict:
    return {
        "gate": {"type": "choice", "instructions": "What kind of request is the user's message? (The user writes in Chinese.)", "criteria": dict(ROUTES_EN)},
        "is_write": {"type": "noul", "instructions": "Does the user's message ask to create, modify, delete or submit business data in the system?",
                     "criteria": {"true": "Yes, it changes data", "false": "No, it only looks up, asks, or chats"}},
        "fine": {
            "type": "choice",
            "instructions": "Which system function should handle the user's message? (The user writes in Chinese.)",
            "criteria": {c: c.replace("_", " ") + ": " + tool_text(tools[c], max_len=desc_len) for c in cands},
        },
    }


def state_text_en(row: dict) -> str:
    return f"User role: {CTX_EN[row['ctx']]}\nUser message (Chinese): {row['q']}"


def build_questions(row: dict, cands: list[str], tools: dict, desc_len: int) -> dict:
    return {
        "gate": {"type": "choice", "instructions": GATE_INSTR, "criteria": dict(ROUTES)},
        "is_write": {"type": "noul", "instructions": WRITE_INSTR, "criteria": dict(WRITE_CRITERIA)},
        "fine": {
            "type": "choice",
            "instructions": FINE_INSTR,
            "criteria": {c: tool_text(tools[c], max_len=desc_len) for c in cands},
        },
    }


# ---------------- adapters: evaluate(state, questions) -> {qid: answer_dict} ----------------

class VonAdapter:
    def __init__(self, args):
        sys.path.insert(0, str(SRC / "von/src"))
        from von.engine import VonEngine

        self.eng = VonEngine(device="cuda")

    def evaluate(self, state, questions):
        resp = self.eng.evaluate(state=state, questions=questions)
        return {k: v.model_dump() for k, v in resp.answers.items()}


class LayaAdapter:
    def __init__(self, args):
        sys.path.insert(0, str(SRC / "laya"))
        import laya

        sub = "multilingual" if args.backend == "laya-ml" else None
        self.agent = laya.load("convaiinnovations/laya", device="cuda", subfolder=sub)

    def evaluate(self, state, questions):
        return self.agent.system_one(state, questions)["answers"]


class OpenDecisionAdapter:
    MODELS = {
        "od": "MoritzLaurer/ModernBERT-large-zeroshot-v2.0",
        "od-ml": "MoritzLaurer/mDeBERTa-v3-base-xnli-multilingual-nli-2mil7",
    }

    def __init__(self, args):
        sys.path.insert(0, str(SRC / "OpenDecision/src"))
        from opendecision.engine import OpenDecisionEngine

        self.eng = OpenDecisionEngine(model=self.MODELS[args.backend], device=0)

    def evaluate(self, state, questions):
        out = {}
        for qid, q in questions.items():
            if q["type"] == "choice":
                out[qid] = self.eng.choice(state=state, instructions=q["instructions"], criteria=q["criteria"])
            else:
                out[qid] = self.eng.noul(state=state, instructions=q["instructions"], criteria=q.get("criteria"))
        return out


class SimpleJevAdapter:
    def __init__(self, args):
        import httpx

        self.client = httpx.Client(base_url=args.url, timeout=300)
        self.model = args.model
        r = self.client.get("/health")
        if r.status_code >= 400:
            raise RuntimeError(f"simple-jev server not healthy: {r.status_code} {r.text[:200]}")

    def evaluate(self, state, questions):
        r = self.client.post("/v1/classifier", json={"model": self.model, "state": state, "questions": questions})
        if r.status_code != 200:
            raise RuntimeError(f"HTTP {r.status_code}: {r.text[:300]}")
        return r.json()["answers"]


class BailianAdapter:
    """百炼 OpenAI 兼容端点: 选项编字母 + 前缀续写 '答案：' + 读第一个 token 的 top_logprobs (≤5)。

    - 是非题按「是/否」两选项的 Choice 做 (绕开 simple-jev 那条坏掉的 Noul 路径)
    - 字母变体 (' A' / 全角 'Ａ' / 小写) 归并到同一选项, 概率在返回的字母里归一化;
      没进 top-5 的选项概率记 0 —— 这是 top_logprobs ≤5 的硬限制, 报告里要写明
    - 模型拒绝 partial 时退回不带前缀, 并记进 self.mode_log (不悄悄混用)
    - 一行里的 3 个问题并发发出, 耗时取三者最慢
    """
    BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    ENV = Path("C:/Users/Steve/my-prototype-logistics/backend/python/.env")
    LETTERS = "ABCDEFGHIJKLMNOPQRST"
    SYSTEM = "你是业务系统的请求分类器。根据用户身份和原话，从给定选项中选出最合适的一项，只输出选项字母。"

    def __init__(self, args):
        import httpx
        from concurrent.futures import ThreadPoolExecutor

        key = next(l.split("=", 1)[1].strip().strip('"').strip("'")
                   for l in self.ENV.read_text(encoding="utf-8").splitlines() if l.startswith("LLM_API_KEY="))
        self.client = httpx.Client(base_url=self.BASE, headers={"Authorization": f"Bearer {key}"}, timeout=60)
        self.model = args.model
        self.pool = ThreadPoolExecutor(max_workers=3)
        self.partial_ok: bool | None = None
        self.mode_log: dict[str, int] = {"partial": 0, "no_partial": 0, "letters_missing": 0}

    @staticmethod
    def _norm(tok: str) -> str:
        t = tok.strip().replace("*", "").replace('"', "").replace("(", "").replace(".", "")
        if len(t) == 1 and "Ａ" <= t <= "Ｚ":
            t = chr(ord(t) - ord("Ａ") + ord("A"))
        return t.upper()

    def _messages(self, state: str, instructions: str, keys: list[str], descs: list[str]) -> tuple[str, list[dict]]:
        """百炼与本地 MLX 共用的提示词: 两边结果可直接对比。"""
        letters = self.LETTERS[: len(keys)]
        opts = "\n".join(f"{L}. {k}：{d}" if d and d != k else f"{L}. {d or k}" for L, k, d in zip(letters, keys, descs))
        user = f"{state}\n\n问题：{instructions}\n选项：\n{opts}\n\n只输出一个选项字母。"
        return letters, [{"role": "system", "content": self.SYSTEM}, {"role": "user", "content": user}]

    def _call(self, state: str, instructions: str, keys: list[str], descs: list[str], _retry: int = 0) -> dict:
        import math

        letters, msgs = self._messages(state, instructions, keys, descs)
        body = {"model": self.model, "max_tokens": 1, "temperature": 0, "logprobs": True, "top_logprobs": 5,
                "enable_thinking": False}
        use_partial = self.partial_ok is not False
        if use_partial:
            body["messages"] = msgs + [{"role": "assistant", "content": "答案：", "partial": True}]
        else:
            body["messages"] = msgs
        r = self.client.post("/chat/completions", json=body)
        if r.status_code == 400 and use_partial and "partial" in r.text.lower():
            self.partial_ok = False
            return self._call(state, instructions, keys, descs)
        if r.status_code != 200:
            raise RuntimeError(f"HTTP {r.status_code}: {r.text[:300]}")
        if use_partial:
            self.partial_ok = True
        self.mode_log["partial" if use_partial else "no_partial"] += 1
        ch = r.json()["choices"][0]
        content = ((ch.get("logprobs") or {}).get("content")) or []
        if not content:
            # 实测偶发: 同模型同类请求时有时无, 原因未定位 (已排除隐式缓存)。重试并计数, 不吞
            self.mode_log["no_logprobs"] = self.mode_log.get("no_logprobs", 0) + 1
            if _retry < 2:
                return self._call(state, instructions, keys, descs, _retry + 1)
            raise RuntimeError("no logprobs in response after 2 retries")
        agg: dict[str, float] = {}
        for t in content[0].get("top_logprobs", []):
            L = self._norm(t["token"])
            if len(L) == 1 and L in letters:  # 子串判断会让空串/多字符 token 混进分母
                agg[L] = agg.get(L, 0.0) + math.exp(t["logprob"])
        if not agg:
            self.mode_log["letters_missing"] += 1
            raise RuntimeError(f"top-5 里没有任何选项字母: {[t['token'] for t in content[0].get('top_logprobs', [])]}")
        z = sum(agg.values())
        probs = {k: agg.get(L, 0.0) / z for L, k in zip(letters, keys)}
        best = max(probs, key=lambda k: probs[k])
        return {"type": "choice", "choice": best, "probabilities": probs, "confidence": probs[best]}

    def _one(self, state, q):
        if q["type"] == "choice":
            keys = list(q["criteria"])
            return self._call(state, q["instructions"], keys, [q["criteria"][k] or "" for k in keys])
        crit = q.get("criteria") or {}
        res = self._call(state, q["instructions"], ["true", "false"],
                         [crit.get("true", "是"), crit.get("false", "否")])
        return {"type": "noul", "noul": res["probabilities"]["true"]}

    def evaluate(self, state, questions):
        futs = {qid: self.pool.submit(self._one, state, q) for qid, q in questions.items()}
        return {qid: f.result() for qid, f in futs.items()}


class MLXAdapter(BailianAdapter):
    """Apple Silicon 本地: 与百炼同一份提示词 + 同样的「答案：」前缀, 读下一个 token 在选项字母上的概率。

    与百炼的差别 (报告要写明): 这里拿得到**全部** K 个字母的概率 (完整 softmax),
    百炼只给 top-5。字母的 'A' / ' A' 两种分词变体相加。3 个问题串行 (单设备)。
    """

    def __init__(self, args):
        import mlx.core as mx
        from mlx_lm import load

        self.mx = mx
        self.model, self.tok = load(args.model)
        self.mode_log = {"calls": 0}
        self.partial_ok = None
        self.letter_ids: dict[str, list[int]] = {}
        for L in self.LETTERS:
            ids = set()
            for v in (L, " " + L):
                enc = self.tok.encode(v, add_special_tokens=False)
                if len(enc) == 1:
                    ids.add(enc[0])
            if not ids:
                raise RuntimeError(f"letter {L} has no single-token encoding")
            self.letter_ids[L] = sorted(ids)

    def _call(self, state, instructions, keys, descs, _retry: int = 0):
        mx = self.mx
        letters, msgs = self._messages(state, instructions, keys, descs)
        try:
            prompt = self.tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True, enable_thinking=False)
        except TypeError:
            prompt = self.tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
        ids = self.tok.encode(prompt + "答案：", add_special_tokens=False)
        logits = self.model(mx.array([ids]))[0, -1].astype(mx.float32)
        logp = logits - mx.logsumexp(logits)
        mass = {L: float(mx.sum(mx.exp(logp[mx.array(self.letter_ids[L])])).item()) for L in letters}
        self.mode_log["calls"] += 1
        z = sum(mass.values())
        self.mode_log["letter_mass_sum"] = self.mode_log.get("letter_mass_sum", 0.0) + z  # 字母占总概率多少: 低则提示词没压住
        probs = {k: mass[L] / z for L, k in zip(letters, keys)}
        best = max(probs, key=lambda k: probs[k])
        return {"type": "choice", "choice": best, "probabilities": probs, "confidence": probs[best]}

    def evaluate(self, state, questions):
        return {qid: self._one(state, q) for qid, q in questions.items()}


ADAPTERS = {"von": VonAdapter, "laya-en": LayaAdapter, "laya-ml": LayaAdapter,
            "od": OpenDecisionAdapter, "od-ml": OpenDecisionAdapter, "sjev": SimpleJevAdapter,
            "bailian": BailianAdapter, "mlx": MLXAdapter}


def top3(probs: dict) -> list:
    return sorted(((k, round(float(v), 4)) for k, v in probs.items()), key=lambda x: -x[1])[:3]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("backend", choices=sorted(ADAPTERS))
    ap.add_argument("--k", type=int, default=10)
    ap.add_argument("--desc-len", type=int, default=60)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--tag", default="")
    ap.add_argument("--model", default="")
    ap.add_argument("--url", default="http://127.0.0.1:8000")
    ap.add_argument("--warmup", type=int, default=3)
    ap.add_argument("--lang", choices=["zh", "en"], default="zh", help="问题/选项说明的语言; 用户原话始终是中文")
    args = ap.parse_args()

    tools = load_tools()
    rows = load_testset()
    if args.limit:
        rows = rows[: args.limit]
    cands_all = json.loads((HERE / "candidates.json").read_text(encoding="utf-8"))

    t_load = time.perf_counter()
    adapter = ADAPTERS[args.backend](args)
    load_s = time.perf_counter() - t_load

    name = args.backend + (f"_{args.tag}" if args.tag else "")
    out_dir = HERE / "results"
    out_dir.mkdir(exist_ok=True)
    out_path = out_dir / f"{name}.jsonl"

    def prep(row):
        cands = cands_all[row["id"]][: args.k]
        rng = random.Random(f"{row['id']}-shuffle")  # 同一题所有后端同一顺序
        cands = cands[:]
        rng.shuffle(cands)
        builder = build_questions_en if args.lang == "en" else build_questions
        return cands, builder(row, cands, tools, args.desc_len)

    st_fn = state_text_en if args.lang == "en" else state_text

    # 阳性对照: 送分题答不对 = 仪器没接通, 读数作废 (硬约束 4: rc=2 与「答得差」分开)
    controls = [
        ("The bicycle is red.", "What color is the bicycle?", {"red": "The color red", "blue": "The color blue"}, "red"),
        ("自行车是红色的。", "自行车是什么颜色？", {"red": "红色", "blue": "蓝色"}, "red"),
    ]
    control_log = []
    for st, ins, crit, want in controls:
        a = adapter.evaluate(st, {"c": {"type": "choice", "instructions": ins, "criteria": crit},
                                  "n": {"type": "noul", "instructions": ins.replace("What color is", "Is").replace("是什么颜色", "是红色的吗") }})
        got, p_true = a["c"]["choice"], float(a["n"]["noul"])
        control_log.append({"state": st, "choice": got, "want": want, "noul": round(p_true, 3)})
    print("positive controls:", json.dumps(control_log, ensure_ascii=False))
    if control_log[0]["choice"] != control_log[0]["want"]:
        print("INSTRUMENT DEAD: 英文送分题答错, 本次读数作废")
        return 2

    for row in rows[: args.warmup]:
        _c, q = prep(row)
        adapter.evaluate(st_fn(row), q)

    n_err = 0
    with out_path.open("w", encoding="utf-8") as f:
        for i, row in enumerate(rows):
            cands, qs = prep(row)
            rec = {"id": row["id"], "ctx": row["ctx"], "route": row["route"], "gold": row["gold"],
                   "style": row["style"], "cands": cands, "gold_in_cands": bool(set(row["gold"]) & set(cands))}
            t0 = time.perf_counter()
            try:
                ans = adapter.evaluate(st_fn(row), qs)
                rec["ms"] = round((time.perf_counter() - t0) * 1000, 1)
                g, w, fn = ans["gate"], ans["is_write"], ans["fine"]
                rec["gate_pred"] = g["choice"]
                rec["gate_probs"] = {k: round(float(v), 4) for k, v in g["probabilities"].items()}
                rec["gate_conf"] = float(g.get("confidence", max(g["probabilities"].values())))
                rec["write_p"] = float(w["noul"])
                rec["fine_pred"] = fn["choice"]
                rec["fine_top3"] = top3(fn["probabilities"])
                rec["fine_pmax"] = float(max(fn["probabilities"].values()))
                rec["fine_conf"] = float(fn.get("confidence", rec["fine_pmax"]))
            except Exception as e:  # noqa: BLE001 - 记下来, 不吞
                n_err += 1
                rec["ms"] = round((time.perf_counter() - t0) * 1000, 1)
                rec["error"] = f"{type(e).__name__}: {e}"[:500]
                rec["trace"] = traceback.format_exc()[-1500:]
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            if (i + 1) % 25 == 0:
                print(f"  {i + 1}/{len(rows)} errors={n_err}", flush=True)
    meta = {"backend": name, "args": vars(args), "rows": len(rows), "errors": n_err,
            "load_s": round(load_s, 1), "positive_controls": control_log,
            "adapter_mode": getattr(adapter, "mode_log", None), "partial_ok": getattr(adapter, "partial_ok", None)}
    (out_dir / f"{name}.meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"backend={name} rows={len(rows)} errors={n_err} load_s={load_s:.1f} -> {out_path}")
    return 2 if n_err == len(rows) else (1 if n_err else 0)


if __name__ == "__main__":
    sys.exit(main())
