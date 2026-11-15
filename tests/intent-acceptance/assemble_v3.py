"""把多模型出的题装成验收集: 校验 -> 交叉标注一致性 -> 去重 (集内 + 对全部已知语料) -> 拆成「开发半边」和「封存半边」。

输出
  <sealed-dir>/sealed_items.jsonl      封存半边原文 + 答案 (本机, 不进 git)
  <sealed-dir>/MANIFEST.json           sealed_items.jsonl 的 sha256、题量、覆盖表
  <repo>/tests/intent-acceptance/sealed_v3_fingerprints.jsonl   封存半边每题的指纹 + 分组标签 (不含原文)
  <repo>/tests/intent-acceptance/dev/items.jsonl                开发半边 (可用于调参, 也可被训练线使用)
  <repo>/tests/intent-acceptance/COVERAGE.md                    覆盖表 (只统计, 无原文)

拆分规则: 对规范化原话的指纹按「格子」(业务域 x 角色 x 风格) 分层, 格内按指纹排序后交替分配, 两半各约一半且每格都有。
同一指纹只会落在一半。去重在拆分之前做, 所以两半之间没有整句重复; 近似重复 (>=0.85) 也在拆分前剔除, 然后两半之间再跑一遍近似检查并写进 MANIFEST。

用法:
  python -I -X utf8 assemble_v3.py --work <出题工作目录> --catalog <catalog_rw.json> --sealed-dir <本机封存目录> [--corpus-root <目录> ...]
"""
import argparse
import collections
import difflib
import hashlib
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import corpus_guard as cg  # noqa: E402

STANCE_OK = {"DIRECT", "ASK", "WRITE", "REFUSE", "OOD"}


def read_jsonl(p):
    return [json.loads(x) for x in Path(p).read_text("utf-8").splitlines() if x.strip()]


def load_items(work, models):
    items = []
    for m in models:
        rows = json.loads((Path(work) / ("items-%s.json" % m)).read_text("utf-8"))
        labels = {}
        for r in read_jsonl(Path(work) / ("results-label-%s.jsonl" % m)):
            if r.get("ok"):
                labels[r["rid"]] = r
        for it in rows:
            lab = labels.get(it["rid"])
            it["label"] = lab["label"] if lab else None
            it["label_model"] = lab["labeler"] if lab else None
            items.append(it)
    return items


def validate(it, by_code):
    """返回 None (合格) 或剔除原因。"""
    if not isinstance(it.get("input"), str) or not (3 <= len(it["input"].strip()) <= 60):
        return "input_len"
    st, code = it.get("stance"), it.get("intent") or ""
    if st not in STANCE_OK:
        return "bad_stance"
    if st == "OOD":
        return None if code in ("OUT_OF_DOMAIN", "") else "ood_intent"
    if st == "REFUSE":
        return None if code == "" else "refuse_has_intent"
    if code not in by_code:
        return "unknown_intent"
    mode = by_code[code]["mode"]
    if st == "DIRECT" and mode != "READ":
        return "direct_on_write_intent"
    if st == "WRITE" and mode != "WRITE":
        return "write_on_read_intent"
    return None


def agree(it, by_code):
    """交叉标注一致: 姿态相同, 且意图相同或落在任一方列出的替代里 (REFUSE/OOD 只比姿态)。返回 (是否一致, 原因)。"""
    lab = it.get("label")
    if not lab:
        return False, "no_label"
    if lab.get("stance") != it["stance"]:
        return False, "stance:%s->%s" % (it["stance"], lab.get("stance"))
    if it["stance"] in ("REFUSE", "OOD"):
        return True, ""
    mine = {it["intent"], *(it.get("intent_alternates") or [])}
    theirs = {lab.get("intent"), *(lab.get("intent_alternates") or [])}
    if it["intent"] in theirs or lab.get("intent") in mine:
        return True, ""
    return False, "intent"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    ap.add_argument("--catalog", required=True)
    ap.add_argument("--sealed-dir", required=True)
    ap.add_argument("--models", default="qwen-max,gpt-5.4-mini")
    ap.add_argument("--corpus-root", action="append", default=[], help="额外的已知语料根 (如本机蒸馏数据目录), 可多次")
    ap.add_argument("--no-repo-write", action="store_true")
    a = ap.parse_args()

    catalog = json.load(open(a.catalog, encoding="utf-8"))
    by_code = {c["code"]: c for c in catalog}
    items = load_items(a.work, a.models.split(","))
    drops = collections.Counter()
    ok = []
    for it in items:
        why = validate(it, by_code)
        if why:
            drops["invalid:" + why] += 1
            continue
        good, reason = agree(it, by_code)
        it["ambiguous"], it["ambiguity_reason"] = (not good), reason
        it["mode"] = by_code[it["intent"]]["mode"] if it["intent"] in by_code else ""
        it["fp"] = cg.fingerprint(it["input"])
        ok.append(it)

    # ---- 集内去重 (整句 + 近似), 同指纹优先保留一致的、不含歧义的
    ok.sort(key=lambda x: (x["ambiguous"], x["fp"], x["rid"]))
    seen_fp, kept = set(), []
    for it in ok:
        if it["fp"] in seen_fp:
            drops["dup:in_set_exact"] += 1
            continue
        seen_fp.add(it["fp"])
        kept.append(it)
    near_ix = cg.NearIndex([cg.norm(i["input"]) for i in kept])
    final = []
    taken = set()
    for i, it in enumerate(kept):
        q = cg.norm(it["input"])
        # 只和已保留的另一条比 (自己不算)
        dup = False
        qb = cg._bigrams(q)
        for j in {j for g in qb for j in near_ix.inv.get(g, ())}:
            if j == i or j not in taken:
                continue
            t = near_ix.texts[j]
            if difflib.SequenceMatcher(None, q, t).ratio() >= cg.NEAR:
                dup = True
                break
        if dup:
            drops["dup:in_set_near"] += 1
            continue
        taken.add(i)
        final.append(it)

    # ---- 对全部已知语料去重 (训练/开发/旧验收集): 命中即剔除
    repo = HERE.parents[1]
    roots = [repo / "scripts/finetune", repo / "tests/semantic-read", repo / "backend/java/cretas-api/src/test/resources",
             repo / "backend/python/tests/fixtures"] + [Path(r) for r in a.corpus_root]
    seen, n_files = cg.collect(roots)
    ext = cg.NearIndex(seen.keys())
    clean = []
    for it in final:
        hit = ext.hit(cg.norm(it["input"]))
        if hit:
            drops["dup:external_" + hit[0]] += 1
            continue
        clean.append(it)
    ext_info = dict(files_scanned=n_files, corpus_strings=len(seen))

    # ---- 拆分: 按格子分层, 格内按指纹排序交替分配
    cells = collections.defaultdict(list)
    for it in clean:
        cells[(it["stage"], it["role"], it["style"])].append(it)
    dev, sealed = [], []
    for cell in sorted(cells):
        for k, it in enumerate(sorted(cells[cell], key=lambda x: x["fp"])):
            (sealed if k % 2 == 0 else dev).append(it)

    # 两半之间再跑一次近似检查 (写进 MANIFEST)
    sealed_ix = cg.NearIndex([cg.norm(i["input"]) for i in sealed])
    cross = sum(1 for it in dev if sealed_ix.hit(cg.norm(it["input"])))

    def strip(it, set_name, n):
        return dict(id="V3%s-%04d" % (set_name[0].upper(), n), set=set_name, stage=it["stage"], role=it["role"], style=it["style"],
                    input=it["input"].strip(), stance=it["stance"], intent=it["intent"], intent_alternates=it.get("intent_alternates") or [],
                    ask_options=it.get("ask_options") or [], mode=it["mode"], ambiguous=it["ambiguous"],
                    ambiguity_reason=it["ambiguity_reason"], policy_rule=it.get("policy_rule") or 0, gen_model=it["gen_model"],
                    label_model=it["label_model"], scenario=it.get("scenario") or "", fp=it["fp"])

    dev_rows = [strip(it, "dev", n) for n, it in enumerate(dev, 1)]
    sealed_rows = [strip(it, "sealed", n) for n, it in enumerate(sealed, 1)]

    def table(rows):
        out = {}
        for key in ("stage", "role", "style", "stance", "gen_model"):
            out[key] = dict(collections.Counter(r[key] for r in rows))
        out["ambiguous"] = sum(1 for r in rows if r["ambiguous"])
        out["intents"] = len({r["intent"] for r in rows if r["intent"]})
        out["write_items"] = sum(1 for r in rows if r["stance"] == "WRITE")
        return out

    sealed_dir = Path(a.sealed_dir)
    sealed_dir.mkdir(parents=True, exist_ok=True)
    body = "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in sealed_rows)
    (sealed_dir / "sealed_items.jsonl").write_text(body, "utf-8", newline="")
    manifest = dict(version="v3", n_items=len(sealed_rows), sha256=hashlib.sha256(body.encode("utf-8")).hexdigest(),
                    dropped=dict(drops), external_scan=ext_info, cross_half_near_dups=cross, coverage=table(sealed_rows),
                    dev_coverage=table(dev_rows), catalog_intents=len(catalog))
    (sealed_dir / "MANIFEST.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), "utf-8", newline="")
    if not a.no_repo_write:
        fp_lines = "".join(json.dumps(dict(fp=r["fp"], set="sealed-v3", stage=r["stage"], role=r["role"], style=r["style"],
                                           stance=r["stance"], ambiguous=r["ambiguous"]), ensure_ascii=False) + "\n" for r in sealed_rows)
        (HERE / "sealed_v3_fingerprints.jsonl").write_text(fp_lines, "utf-8", newline="")
        (HERE / "dev").mkdir(exist_ok=True)
        (HERE / "dev" / "items.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in dev_rows), "utf-8", newline="")
    print(json.dumps(manifest, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
