# -*- coding: utf-8 -*-
"""把 300 题真跑记录冻结成 s300_readings.json (test_ai_ask_policy_gold300.py 的输入)。

输入是 calib_run 的逐题记录 (jsonl, 每题一行; 在 47 上用候选源码本地装配、只识别、不碰 Java 执行入口):
  --before-cons  改前源码 (origin/main) + askPolicy=CONSERVATIVE
  --after-cons   改后源码 + askPolicy=CONSERVATIVE
  --before-agg / --after-agg  同上, AskPolicy=AGGRESSIVE
每题只留重放决策函数需要的读数: Choice 概率 (首选若干 + 「以上都不是」「需要先问清楚」) 和 Luna 判断题概率。
⛔ 不放原始回复文本, 不放任何凭证。
用法: python build_readings.py --before-cons a.jsonl --after-cons b.jsonl --before-agg c.jsonl --after-agg d.jsonl \
        --old-rules old.txt --out s300_readings.json
规则哈希取自当前源码 (新) 与 --old-rules 文件 (旧, 即改前 main 的 ASK_PREDICATE_RULES 原文)。
"""
import argparse
import hashlib
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[2]))  # backend/python

from ai.matcher.decision import ASK_PREDICATE_RULES, ASK_PREDICATE_RULES_AGGRESSIVE  # noqa: E402


def sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def act_of(r) -> str:
    if r["kind"] == "EXECUTE":
        return "show" if r.get("write") else "direct"
    if r["kind"] in ("CLARIFY", "NONE"):
        return "ask"
    return "unmeasured"


def side(r) -> dict:
    t = r.get("timing") or {}
    governed = bool(t.get("lunaPolicyV3")) and bool(r.get("probs"))
    out = {"recorded_act": act_of(r), "governed": governed}
    if governed:
        out.update(
            probs=[[round(float(p), 4), bool(w)] for _c, p, w in r["probs"][:5]],
            none=(t.get("decisionNoneP") or 0) / 1000.0,
            clarify=(t.get("decisionClarifyP") or 0) / 1000.0,
            ask=(t.get("lunaAskP") or 0) / 1000.0,
        )
    return out


def load(path):
    rows = {}
    for line in open(path, encoding="utf-8"):
        if line.strip():
            r = json.loads(line)
            rows[r["id"]] = r
    return rows


def main():
    ap = argparse.ArgumentParser()
    for k in ("before-cons", "after-cons", "before-agg", "after-agg", "old-rules", "out"):
        ap.add_argument("--" + k, required=True)
    ap.add_argument("--meta", default="{}")
    a = ap.parse_args()
    runs = {k: load(getattr(a, k.replace("-", "_"))) for k in ("before-cons", "after-cons", "before-agg", "after-agg")}
    ids = sorted(runs["before-cons"])
    for k, v in runs.items():
        assert sorted(v) == ids, k
    rows = {}
    for q in ids:
        rows[q] = {k.replace("-", "_"): side(runs[k][q]) for k in runs}
    meta = json.loads(a.meta)
    meta.update(
        rules_sha256={
            "old_conservative": sha(pathlib.Path(a.old_rules).read_text("utf-8")),
            "new_conservative": sha(ASK_PREDICATE_RULES),
            "aggressive": sha(ASK_PREDICATE_RULES_AGGRESSIVE),
        },
        ask_threshold={"old_conservative": 0.6, "new_conservative": 0.3, "aggressive": 0.4},
    )
    pathlib.Path(a.out).write_text(
        json.dumps({"meta": meta, "rows": rows}, ensure_ascii=False, indent=0, sort_keys=True) + "\n",
        encoding="utf-8", newline="\n")
    print("wrote", a.out, len(rows))


if __name__ == "__main__":
    main()
