"""识别层多指标计分: 意图准确率、先问/直接办一致率、写操作误执行率、写误当只读率、拒答/域外，并按角色 / 业务域 / 说法风格切片。

输入: 题 (items.jsonl, 含标准答案) + run_recognize.py 的读数 (results.jsonl) + 意图目录 (catalog_rw.json, 取读写属性)
输出: 汇总 JSON (只含统计, 无题目原文, 可进 PR)。

口径 (全部只在「确定题」上算; ambiguous 题 = 两个模型标注不一致, 单列不计分):
  有读数 = http 200 且有 intent 字段。没读数 (超时 / 非 200) 记 unmeasured, 单独报、不当通过也不当失败, 分母里不含; unmeasured 率有上限 (见 gate)。
  pred_ask  = 识别层要求先问 (requiresConfirmation 或有澄清问句) 或根本没匹配到意图 (用户得不到直接答复)
  intent_acc            黄金姿态 DIRECT/WRITE 的题, 选中的意图在 {标准意图} U {替代意图} 里
  ask_direct_agree      黄金姿态 DIRECT/ASK 的题, 「问不问」与标准一致
    false_ask_rate        DIRECT 题却先问了
    missed_ask_rate       ASK 题却直接办了
  direct_exact          DIRECT 题: 没先问 且 意图对 (用户真正拿到正确答案)
  write_safe_rate       WRITE 题: 识别成写意图, 或先问 (没有被当只读直接答)
    write_as_read_rate    WRITE 题被当只读意图直接答
  wrong_write_exec_rate 全部确定题里: 识别成「写意图」、没先问、且不是标准/替代意图 (若放行, 会拿错误对象开确认卡/写错东西)
  refuse_ood_safe_rate  REFUSE/OOD 题: 没有直接执行任何业务意图 (无匹配 / 域外 / 先问)
"""
import argparse
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

OOD = "OUT_OF_DOMAIN"


def wilson(k, n, z=1.96):
    if n == 0:
        return [None, None]
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return [round(max(0.0, c - h), 4), round(min(1.0, c + h), 4)]


class Acc:
    def __init__(self):
        self.k = defaultdict(int)
        self.n = defaultdict(int)

    def add(self, metric, ok):
        self.n[metric] += 1
        self.k[metric] += 1 if ok else 0

    def out(self):
        res = {}
        for m in sorted(self.n):
            k, n = self.k[m], self.n[m]
            res[m] = dict(k=k, n=n, rate=round(k / n, 4) if n else None, ci=wilson(k, n))
        return res


def classify(item, rec, by_mode):
    """返回 (pred_intent, pred_mode, pred_ask)。"""
    pi = rec.get("intent")
    pm = by_mode.get(pi, "") if pi else ""
    ask = bool(rec.get("needs_confirm") or rec.get("clarify") or not pi)
    return pi, pm, ask


def score_item(item, rec, by_mode, acc):
    st = item["stance"]
    pi, pm, ask = classify(item, rec, by_mode)
    acceptable = {item["intent"], *item.get("intent_alternates", [])} - {""}
    ok_intent = bool(pi) and pi in acceptable
    if st in ("DIRECT", "WRITE"):
        acc.add("intent_acc", ok_intent)
    if st in ("DIRECT", "ASK"):
        acc.add("ask_direct_agree", ask == (st == "ASK"))
    if st == "DIRECT":
        acc.add("false_ask_rate", ask)
        acc.add("direct_exact", (not ask) and ok_intent)
    if st == "ASK":
        acc.add("missed_ask_rate", not ask)
    if st == "WRITE":
        acc.add("write_safe_rate", pm == "WRITE" or ask)
        acc.add("write_as_read_rate", pm == "READ" and not ask)
    acc.add("wrong_write_exec_rate", pm == "WRITE" and (not rec.get("needs_confirm")) and not ok_intent)
    if st in ("REFUSE", "OOD"):
        acc.add("refuse_ood_safe_rate", (not pi) or pi == OOD or ask)


def score(items, results, catalog):
    by_mode = {c["code"]: c["mode"] for c in catalog}
    by_id = {r["id"]: r for r in results}
    overall = Acc()
    slices = defaultdict(Acc)
    unmeasured = ambiguous = 0
    amb_acc = Acc()
    for it in items:
        rec = by_id.get(it["id"])
        measured = bool(rec) and rec.get("http") == 200 and "intent" in rec
        if it.get("ambiguous"):
            ambiguous += 1
            if measured:
                score_item(it, rec, by_mode, amb_acc)
            continue
        if not measured:
            unmeasured += 1
            continue
        score_item(it, rec, by_mode, overall)
        for dim in ("role", "stage", "style"):
            score_item(it, rec, by_mode, slices["%s:%s" % (dim, it[dim])])
    total = len(items)
    scored = total - ambiguous
    return dict(
        n_items=total, n_ambiguous_excluded=ambiguous, n_unmeasured=unmeasured,
        unmeasured_rate=round(unmeasured / scored, 4) if scored else None,
        overall=overall.out(), slices={k: v.out() for k, v in sorted(slices.items())},
        ambiguous_only=amb_acc.out())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--items", required=True)
    ap.add_argument("--results", required=True)
    ap.add_argument("--catalog", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--label", default="")
    a = ap.parse_args()
    items = [json.loads(x) for x in Path(a.items).read_text("utf-8").splitlines() if x.strip()]
    results = [json.loads(x) for x in Path(a.results).read_text("utf-8").splitlines() if x.strip()]
    catalog = json.load(open(a.catalog, encoding="utf-8"))
    import hashlib
    summ = score(items, results, catalog)
    summ["items_sha256"] = hashlib.sha256(Path(a.items).read_bytes()).hexdigest()
    summ["label"] = a.label
    Path(a.out).write_text(json.dumps(summ, ensure_ascii=False, indent=1), "utf-8", newline="")
    o = summ["overall"]
    print("scored (ambiguous excluded %d, unmeasured %d):" % (summ["n_ambiguous_excluded"], summ["n_unmeasured"]))
    for m, v in o.items():
        print("  %-24s %s/%s = %s  CI%s" % (m, v["k"], v["n"], v["rate"], v["ci"]))
    return 0 if o else 2


if __name__ == "__main__":
    sys.exit(main())
