"""方案 A 的模拟评测: 从 history 挖高频说法 -> 老师标注 -> 精确匹配表; 在 future 上评测, 并与现有路线对比。

⛔ 挖掘时不看金标: 入表标签只来自老师 (qwen3.8-flash), 金标只在评测时用 —— 现实里我们只有原话。

对比 (同一份 future 流量):
  REG    精确匹配表 (N1/N2/N3 × min_count), 两种审核策略: 全部自动入表 / 老师置信<阈值的留给人工
  PHRASE 现有子串短语表 (IntentKnowledgeBase.doMatchPhrase 移植)
  BGE    bge-base-zh 在意图描述上直接取第一名 (≈ 现在的向量直通)
  LLM    qwen3.7-flash 在 bge top-20 候选意图上选 (≈ 餐饮式「LLM 拍板」)
  REG+LLM 命中表就用表, 没命中交给 LLM
用法: python mine_eval.py [--tag default] [--levels N1,N2,N3] [--min-counts 2,3,5] [--skip-llm-all]
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import random
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace

import numpy as np

from bailian import OUT
from normalize import LEVELS
from task_catalog import ROLES, TASKS

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from phrase_baseline import do_match_phrase, load_maps  # noqa: E402
from run_backend import BailianAdapter  # noqa: E402

TEACHER, LLM = "qwen3.8-flash", "qwen3.7-flash"
K = 20
CONF_AUTO = 0.9
VIRTUAL = {"FOOD_KNOWLEDGE_QUERY": ("食品安全知识问答", "回答食品安全标准、加工工艺要求、法规、卫生规范等知识类问题")}
INSTR = "用户这句话应该交给下面哪个系统功能来处理？"


def load_intents():
    P = json.loads((OUT / "prod_intents.json").read_text(encoding="utf-8"))
    xs = [x for x in P if x["business_type"] in ("FACTORY", "COMMON")]
    by = {x["code"]: x for x in xs}
    for code, (name, desc) in VIRTUAL.items():
        by[code] = {"code": code, "name": name, "desc": desc, "tool": None, "keywords": None}
    return by


def intent_text(x: dict) -> str:
    kw = x.get("keywords")
    if isinstance(kw, str):
        try:
            kw = json.loads(kw)
        except json.JSONDecodeError:
            kw = [kw]
    kws = "、".join(str(k) for k in (kw or [])[:8])
    desc = " ".join(str(x.get("desc") or "").split())[:80]
    return f"{x['name']}：{desc}" + (f" 常见说法：{kws}" if kws else "")


def acceptable(row: dict, intents: dict) -> set[str]:
    base = {row["intent"], *row.get("alt", [])}
    tools = {intents[c]["tool"] for c in base if c in intents and intents[c].get("tool")}
    return base | {c for c, x in intents.items() if x.get("tool") and x["tool"] in tools}


class Retriever:
    def __init__(self, intents: dict):
        from sentence_transformers import SentenceTransformer

        self.codes = sorted(intents)
        self.m = SentenceTransformer("BAAI/bge-base-zh-v1.5", device="cpu")
        self.E = self.m.encode([intent_text(intents[c]) for c in self.codes], normalize_embeddings=True, batch_size=64)
        self._cache: dict[str, list[str]] = {}

    def topk(self, texts: list[str], k: int = K) -> dict[str, list[str]]:
        need = [t for t in dict.fromkeys(texts) if t not in self._cache]
        if need:
            Q = self.m.encode(["为这个句子生成表示以用于检索相关文章：" + t for t in need], normalize_embeddings=True, batch_size=64)
            for t, q in zip(need, Q):
                order = np.argsort(-(self.E @ q))[:k]
                self._cache[t] = [self.codes[i] for i in order]
        return {t: self._cache[t] for t in texts}


class Shortlist:
    """生产 Stage 8 的粗选: LLM 看完整意图目录 (代码+名称) 给出 <=8 个候选。目录前置, 便于前缀缓存。

    纠正第一版的仪器缺陷: 只用 bge top-20 当候选, 召回 59%, 把 LLM 的成绩压到 47% —— 生产并不先做向量召回。
    """

    def __init__(self, model: str, intents: dict):
        from bailian import chat_json

        self.model, self.chat_json = model, chat_json
        self.catalog = "\n".join(f"{c}｜{intents[c]['name']}" for c in sorted(intents))
        self.codes = set(intents)
        self.path = OUT / f"shortlist_cache_{model}.jsonl"
        self.cache = {}
        if self.path.exists():
            for line in self.path.read_text(encoding="utf-8").splitlines():
                r = json.loads(line)
                self.cache[r["key"]] = r["codes"]
        self.lock = threading.Lock()

    def get(self, role: str, text: str) -> list[str]:
        key = hashlib.sha1(f"{self.model}|{role}|{text}".encode()).hexdigest()
        if key in self.cache:
            return self.cache[key]
        system = ("你是食品加工厂业务系统的意图粗筛器。下面是全部意图目录（代码｜名称）：\n" + self.catalog +
                  '\n\n只输出 JSON：{"candidates": ["代码1", "代码2", ...]}，最多 8 个，按可能性从高到低；都不像就输出空数组。')
        user = f"用户身份：食品加工厂{role}（{ROLES[role][1]}）\n用户说：{text}"
        try:
            obj, _ = self.chat_json(self.model, system, user, temperature=0)
            codes = [c for c in (obj.get("candidates") or []) if c in self.codes][:8]
        except Exception:  # noqa: BLE001
            codes = None
        with self.lock:
            self.cache[key] = codes
            with self.path.open("a", encoding="utf-8") as f:
                f.write(json.dumps({"key": key, "codes": codes}, ensure_ascii=False) + "\n")
        return codes

    def batch(self, items: list[tuple[str, str]]) -> list:
        with ThreadPoolExecutor(max_workers=8) as ex:
            return list(ex.map(lambda a: self.get(*a), items))


def merge_cands(short: list[str] | None, bge: list[str], k: int = K) -> list[str]:
    out = []
    for c in (short or []) + bge:
        if c not in out:
            out.append(c)
        if len(out) >= k:
            break
    return out


class Chooser:
    """LLM 在候选意图上选一个 (前缀续写 + logprobs), 带磁盘缓存。"""

    def __init__(self, model: str, intents: dict):
        self.model, self.intents = model, intents
        self.ad = BailianAdapter(SimpleNamespace(model=model))
        self.path = OUT / f"llm_cache_{model}.jsonl"
        self.cache = {}
        if self.path.exists():
            for line in self.path.read_text(encoding="utf-8").splitlines():
                r = json.loads(line)
                self.cache[r["key"]] = r
        self.lock = threading.Lock()

    def choose(self, role: str, text: str, cands: list[str]) -> dict:
        cs = cands[:]
        random.Random(text).shuffle(cs)  # 固定打乱, 消除「爱选第一个」
        key = hashlib.sha1(f"{self.model}|{role}|{text}|{','.join(cs)}".encode()).hexdigest()
        if key in self.cache:
            return self.cache[key]
        state = f"用户身份：食品加工厂{role}（{ROLES[role][1]}）\n用户说：{text}"
        try:
            res = self.ad._call(state, INSTR, cs, [intent_text(self.intents[c])[:60] for c in cs])
            out = {"key": key, "choice": res["choice"], "conf": res["confidence"], "err": None}
        except Exception as e:  # noqa: BLE001 — 计数, 不吞
            out = {"key": key, "choice": None, "conf": 0.0, "err": f"{type(e).__name__}: {e}"[:200]}
        with self.lock:
            self.cache[key] = out
            with self.path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(out, ensure_ascii=False) + "\n")
        return out

    def batch(self, items: list[tuple[str, str, list[str]]]) -> list[dict]:
        with ThreadPoolExecutor(max_workers=8) as ex:
            return list(ex.map(lambda a: self.choose(*a), items))


def pct(a, b):
    return f"{a}/{b}={a / b:.1%}" if b else "0/0"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="default")
    ap.add_argument("--levels", default="N1,N2,N3")
    ap.add_argument("--min-counts", default="2,3,5")
    ap.add_argument("--skip-llm-all", action="store_true")
    ap.add_argument("--teacher", default=TEACHER, help="给精确匹配表打标签的老师模型")
    ap.add_argument("--report-suffix", default="")
    ap.add_argument("--teacher-remote", action="store_true", help="老师走服务器 aliyun B 账号的 qwen3.8-max-0902")
    a = ap.parse_args()

    intents = load_intents()
    rows = [json.loads(l) for l in (OUT / f"traffic_{a.tag}.jsonl").read_text(encoding="utf-8").splitlines()]
    alt_now = {t["id"]: t.get("alt", []) for t in TASKS}  # 等价集合以当前目录为准, 不用模拟时写进流量的旧值
    for r in rows:
        r["alt"] = alt_now[r["task"]]
    hist = [r for r in rows if r["split"] == "history"]
    fut = [r for r in rows if r["split"] == "future"]
    for r in fut:
        r["acc"] = acceptable(r, intents)
    print(f"tag={a.tag} | intents {len(intents)} | history {len(hist)} | future {len(fut)}")

    ret = Retriever(intents)
    teacher, llm = Chooser(a.teacher, intents), Chooser(LLM, intents)
    if a.teacher_remote:
        from remote_teacher import NS, RemoteChooser
        teacher, a.teacher = RemoteChooser(intents), NS
    report = {"tag": a.tag}

    # ---- 基线: BGE 直选 / 子串短语表 ----
    top = ret.topk([r["text"] for r in fut])
    recall = sum(bool(set(top[r["text"]]) & r["acc"]) for r in fut)
    bge_ok = sum(top[r["text"]][0] in r["acc"] for r in fut)
    fac, com, foods, _, _ = load_maps(Path("D:/Temp/IKB.java").read_text(encoding="utf-8"))
    ph_hit = ph_ok = 0
    for r in fut:
        m = do_match_phrase(r["text"], fac, foods) or do_match_phrase(r["text"], com, foods)
        if m:
            ph_hit += 1
            ph_ok += m[0] in r["acc"]
    report["BGE_top1"] = pct(bge_ok, len(fut))
    report["BGE_recall@20"] = pct(recall, len(fut))
    report["PHRASE_coverage"] = pct(ph_hit, len(fut))
    report["PHRASE_precision"] = pct(ph_ok, ph_hit)
    print("BGE top1", report["BGE_top1"], "| recall@20", report["BGE_recall@20"],
          "| PHRASE cover", report["PHRASE_coverage"], "prec", report["PHRASE_precision"])

    # ---- 两段式候选 (照生产 Stage 8): LLM 粗选 + bge 补足 ----
    sl_llm = Shortlist(LLM, intents)
    short = sl_llm.batch([(r["role"], r["text"]) for r in fut])
    cand = {}
    for r, s in zip(fut, short):
        cand[(r["role"], r["text"])] = merge_cands(s, top[r["text"]])
    rec2 = sum(bool(set(cand[(r["role"], r["text"])]) & r["acc"]) for r in fut)
    report["two_stage_recall"] = pct(rec2, len(fut))
    report["shortlist_failures"] = sum(1 for s in short if s is None)
    print("two-stage recall", report["two_stage_recall"], "| shortlist failures", report["shortlist_failures"])

    # ---- LLM 拍板 (全量 future) ----
    llm_res = {}
    if not a.skip_llm_all:
        items = [(r["role"], r["text"], cand[(r["role"], r["text"])]) for r in fut]
        out = llm.batch(items)
        for r, o in zip(fut, out):
            llm_res[id(r)] = o
        errs = sum(1 for o in out if o["choice"] is None)
        ok = sum(1 for r in fut if llm_res[id(r)]["choice"] in r["acc"])
        report["LLM_all"] = pct(ok, len(fut))
        report["LLM_errors"] = errs
        print("LLM(3.7-flash) all", report["LLM_all"], "| errors", errs)

    # ---- 精确匹配表 ----
    report["REG"] = {}
    if a.teacher_remote:
        from remote_teacher import RemoteShortlist
        sl_teacher = RemoteShortlist(intents)
    else:
        sl_teacher = Shortlist(a.teacher, intents)
    report["teacher"] = a.teacher
    for lv in a.levels.split(","):
        norm = LEVELS[lv]
        hk = collections.Counter(norm(r["text"]) for r in hist)
        rep = {}
        for r in hist:  # 每个 key 取最常见的原话作代表给老师看
            rep.setdefault(norm(r["text"]), collections.Counter())[(r["role"], r["text"])] += 1
        for mc in [int(x) for x in a.min_counts.split(",")]:
            keys = [k for k, c in hk.items() if c >= mc]
            reps = [rep[k].most_common(1)[0][0] for k in keys]
            bge_c = ret.topk([t for _, t in reps])
            t_short = sl_teacher.batch(reps)
            lab = teacher.batch([(role, t, merge_cands(s, bge_c[t])) for (role, t), s in zip(reps, t_short)])
            table = {k: o for k, o in zip(keys, lab) if o["choice"]}
            t_err = sum(1 for o in lab if o["choice"] is None)
            for policy in ("auto_all", f"auto_conf>={CONF_AUTO}"):
                reg = table if policy == "auto_all" else {k: o for k, o in table.items() if o["conf"] >= CONF_AUTO}
                pending = len(table) - len(reg)
                hits = [r for r in fut if norm(r["text"]) in reg]
                ok = sum(reg[norm(r["text"])]["choice"] in r["acc"] for r in hits)
                new = [r for r in fut if r["new_user"]]
                new_hits = [r for r in new if norm(r["text"]) in reg]
                by_kind = {}
                for kind in ("write", "read", "consult", "oos"):
                    sub = [r for r in fut if r["kind"] == kind]
                    sh = [r for r in sub if norm(r["text"]) in reg]
                    by_kind[kind] = f"cover {pct(len(sh), len(sub))} prec {pct(sum(reg[norm(r['text'])]['choice'] in r['acc'] for r in sh), len(sh))}"
                res = {"table_size": len(reg), "pending_human": pending, "teacher_errors": t_err,
                       "coverage": pct(len(hits), len(fut)), "precision": pct(ok, len(hits)),
                       "new_user_coverage": pct(len(new_hits), len(new)), "by_kind": by_kind}
                if llm_res:
                    e2e = sum((reg[norm(r["text"])]["choice"] if norm(r["text"]) in reg else llm_res[id(r)]["choice"]) in r["acc"] for r in fut)
                    res["REG+LLM_e2e"] = pct(e2e, len(fut))
                    res["llm_calls_saved"] = pct(len(hits), len(fut))
                report["REG"][f"{lv}_min{mc}_{policy}"] = res
                print(f"{lv} min{mc} {policy:16s} table={len(reg):4d} pending={pending:3d} cover={res['coverage']:>16s} "
                      f"prec={res['precision']:>16s} newuser={res['new_user_coverage']:>14s} e2e={res.get('REG+LLM_e2e', '-')}")
    (OUT / f"report_{a.tag}{a.report_suffix}.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
