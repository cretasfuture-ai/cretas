"""v3 开发半边「歧义题」自动裁定: 规则 + 第三方模型投票, 可复跑, 每题留裁定依据, 原标签改名 orig_* 便于回滚。

背景: assemble_v3.py 里两个标注模型 (出题方 gen_model / 盲标方 label_model) 不一致的题标 ambiguous=true, 单列不计分。
本脚本按 Steve 的口径 (README 第 8 节) 给这些题第三票, 多数决裁定; 仍无多数的保持 ambiguous, 进待复核清单 (CSV, 不进仓库)。

只动 dev/items.jsonl 里 ambiguous=true 的行, 确认题一行不改 (确认题里有 43 行 intent_alternates 是字符串的既有问题, 见 PR 说明, 不在本次范围)。
封存半边 / 隔离集 / sealed_v3_fingerprints.jsonl 不读不改。

子命令 (在本目录运行, 千问凭证见 creds.py, 只进内存):
  vote       对全部 ambiguous 行跑第一票 (qwen3.5-plus, 关思考), 再对意图仍无多数的跑第二票 (qwen-plus); 结果写 <work>/votes-*.jsonl, 断点续跑
  calibrate  阳性对照: 抽确认题 (不含歧义) 让同一票模型盲判, 看它与已确认标签的一致率 (只出读数, 不改题)
  apply      读投票缓存 + 规则, 重写 dev/items.jsonl, 输出统计 JSON 与待复核 CSV; 可重复运行 (先还原 orig_* 再裁定, 幂等)
  stats      只读 dev/items.jsonl 出分布

规则编号 (adjudication.rule_id, 判定逻辑在 decide(), 单测里有变异对照):
  N0   字段归一 (只对被裁定的行): intent_alternates / ask_options 的字符串改成列表 (原值保存在 orig_*; 否则 score_recognize 会把字符串按字符展开)
  S1   姿态多数: 出题方 + 第三票 一致 (盲标方为异议)
  S2   姿态多数: 盲标方 + 第三票 一致 (出题方为异议)
  S3   姿态多数: 出题方 + 盲标方 本来姿态一致 (只是意图不一致), 第三票不否决即保持
  S0   三票姿态互不相同: 保持 ambiguous (无多数)
  P1   先问口径收紧: 第三票判 ASK 但没给出三类「真歧义」理由之一 -> 按默认执行 (写意图 WRITE, 读意图 DIRECT)
  P1b  只读题的「同名」不先问 (两个都列出来, README 第 2 节口径), 按 DIRECT
  P3   写操作一律 WRITE: 姿态与意图读写属性冲突且找不到读写一致的意图 -> 保持 ambiguous
  I1   意图多数: 第三票的意图落在出题方意图及其替代里
  I2   意图多数: 第二票的意图落在出题方意图及其替代里
  I3   意图多数: 第三票与第二票意图相同, 出题方为异议 (出题方原意图保留为替代意图)
  I0   意图无多数: 保持 ambiguous
  V0   重装配校验 (assemble_v3.validate) 不过: 保持 ambiguous
"""
import argparse
import collections
import csv
import hashlib
import json
import random
import re
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

STANCES = ("DIRECT", "ASK", "WRITE", "REFUSE", "OOD")
INTENTFUL = ("DIRECT", "ASK", "WRITE")
ASK_REASONS = ("SAME_NAME", "MISSING_REQUIRED", "DIVERGENT_INTENTS")
OOD_CODE = "OUT_OF_DOMAIN"
RULESET = "adjudicate-v3-20261115"
V1_MODEL, V2_MODEL = "qwen3.5-plus", "qwen-plus"
NO_THINK = {"enable_thinking": False}

DEV = HERE / "dev" / "items.jsonl"


# ----------------------------------------------------------------- 纯规则 (可离线单测)
def as_list(v):
    if isinstance(v, list):
        return list(v)
    if isinstance(v, str):
        return [s.strip() for s in re.split(r"[,，、]\s*", v) if s.strip()]
    return []


def base_row(row):
    """还原到裁定前的状态 (幂等重跑用)。确认题原样返回。"""
    if "orig_stance" not in row:
        return dict(row)
    r = dict(row)
    r["stance"], r["intent"] = r.pop("orig_stance"), r.pop("orig_intent")
    r["intent_alternates"], r["ask_options"] = r.pop("orig_intent_alternates"), r.pop("orig_ask_options")
    r["mode"], r["ambiguous"], r["ambiguity_reason"] = r.pop("orig_mode"), r.pop("orig_ambiguous"), r.pop("orig_ambiguity_reason")
    r.pop("adjudication", None)
    return r


def label_stance_of(row):
    """盲标方的姿态: 来自 ambiguity_reason ('stance:ASK->DIRECT' 的右侧; 'intent' = 姿态本来一致)。READ 视为 DIRECT; 其它非法值 (SYSTEM_HELP) = 无票。"""
    reason = row["ambiguity_reason"]
    if reason == "intent":
        return row["stance"]
    m = re.fullmatch(r"stance:([A-Z_]+)->([A-Z_]+)", reason)
    if not m:
        return None
    s = {"READ": "DIRECT"}.get(m.group(2), m.group(2))
    return s if s in STANCES else None


def v1_effective(v, by_code):
    """第三票的有效姿态: 应用 P1 / P1b 收紧。返回 (stance|None, coerced_rule|None)。"""
    if not v:
        return None, None
    st = v.get("stance")
    if st not in STANCES:
        return None, None
    if st == "ASK":
        mode = by_code.get(v.get("intent") or "", {}).get("mode")
        if v.get("ask_reason") not in ASK_REASONS:
            return ("WRITE" if mode == "WRITE" else "DIRECT"), "P1"
        if v.get("ask_reason") == "SAME_NAME" and mode == "READ":
            return "DIRECT", "P1b"
    return st, None


def _pick_intent(stance, cands, by_code):
    """在候选 (有序) 里挑与姿态读写一致的第一个; ASK 不限读写。"""
    for c in cands:
        if c not in by_code:
            continue
        m = by_code[c]["mode"]
        if stance == "ASK" or (stance == "DIRECT" and m == "READ") or (stance == "WRITE" and m == "WRITE"):
            return c
    return None


def decide(row, v1, v2, by_code):
    """row 必须是 base_row 之后、字段已归一的原行。返回 dict(resolved, stance, intent, intent_alternates, ask_options, rule_ids, detail)。"""
    gen_st, lab_st = row["stance"], label_stance_of(row)
    v1_st, coerced = v1_effective(v1, by_code)
    rules = []
    if coerced:
        rules.append(coerced)
    detail = dict(gen=gen_st, label=lab_st, v1=(v1 or {}).get("stance"), v1_eff=v1_st,
                  v1_ask_reason=(v1 or {}).get("ask_reason") or "", v1_intent=(v1 or {}).get("intent") or "",
                  v2_intent=(v2 or {}).get("intent") or "")
    out = dict(resolved=False, stance=gen_st, intent=row["intent"], intent_alternates=row["intent_alternates"],
               ask_options=row["ask_options"], rule_ids=rules, detail=detail, pending_cause="")

    def pend(cause, rule):
        out["rule_ids"] = rules + [rule]
        out["pending_cause"] = cause
        return out

    votes = [s for s in (gen_st, lab_st, v1_st) if s]
    top = collections.Counter(votes).most_common()
    if not top or top[0][1] < 2:
        return pend("姿态无多数: 出题=%s 盲标=%s 第三票=%s" % (gen_st, lab_st, v1_st), "S0")
    stance = top[0][0]
    if gen_st == lab_st:
        rules.append("S3")
    elif v1_st == gen_st:
        rules.append("S1")
    else:
        rules.append("S2")

    if stance in ("REFUSE", "OOD"):
        intent = OOD_CODE if stance == "OOD" else ""
        out.update(resolved=True, stance=stance, intent=intent, intent_alternates=[], ask_options=[], rule_ids=rules)
        return out

    # ---- 意图
    cand_gen = [c for c in ([row["intent"]] + row["intent_alternates"]) if c in by_code]
    i1 = (v1 or {}).get("intent") or ""
    i2 = (v2 or {}).get("intent") or ""
    primary = None
    if i1 in cand_gen:
        rules.append("I1")
        primary = _pick_intent(stance, [i1] + cand_gen, by_code)
    elif i2 in cand_gen:
        rules.append("I2")
        primary = _pick_intent(stance, [i2] + cand_gen, by_code)
    elif i1 and i1 == i2 and i1 in by_code:
        rules.append("I3")
        primary = _pick_intent(stance, [i1] + cand_gen, by_code)
        if primary is not None and primary != i1 and stance != "ASK":
            primary = None
    else:
        return pend("意图无多数: 出题=%s 第三票=%s 第二票=%s" % (row["intent"], i1, i2), "I0")
    if primary is None:
        return pend("姿态 %s 与候选意图读写属性冲突: %s" % (stance, [row["intent"]] + row["intent_alternates"] + [i1, i2]), "P3")
    alts = []
    for c in cand_gen + [i for i in (i1, i2) if i and i in by_code and (i in cand_gen or i1 == i2)]:
        m = by_code[c]["mode"]
        ok = stance == "ASK" or (stance == "DIRECT" and m == "READ") or (stance == "WRITE" and m == "WRITE")
        if ok and c != primary and c not in alts:
            alts.append(c)
    opts = row["ask_options"] if stance == "ASK" else []
    out.update(resolved=True, stance=stance, intent=primary, intent_alternates=alts, ask_options=opts, rule_ids=rules)
    return out


def apply_row(row, v1, v2, by_code):
    """返回裁定后的行 (确认题原样)。"""
    row = base_row(row)
    if not row.get("ambiguous"):
        return row
    norm = dict(row)
    norm["intent_alternates"], norm["ask_options"] = as_list(row["intent_alternates"]), as_list(row["ask_options"])
    d = decide(norm, v1, v2, by_code)
    new = dict(row)
    for k in ("stance", "intent", "intent_alternates", "ask_options", "mode", "ambiguous", "ambiguity_reason"):
        new["orig_" + k] = row[k]
    new["intent_alternates"], new["ask_options"] = norm["intent_alternates"], norm["ask_options"]
    status = "resolved" if d["resolved"] else "pending_review"
    if d["resolved"]:
        new.update(stance=d["stance"], intent=d["intent"], intent_alternates=d["intent_alternates"], ask_options=d["ask_options"],
                   ambiguous=False, ambiguity_reason="")
        new["mode"] = by_code[d["intent"]]["mode"] if d["intent"] in by_code else ""
        import assemble_v3
        bad = assemble_v3.validate(dict(input=new["input"], stance=new["stance"], intent=new["intent"]), by_code)
        if bad:  # 重装配校验不过: 退回原行并保持待复核
            new = dict(row)
            for k in ("stance", "intent", "intent_alternates", "ask_options", "mode", "ambiguous", "ambiguity_reason"):
                new["orig_" + k] = row[k]
            new["intent_alternates"], new["ask_options"] = norm["intent_alternates"], norm["ask_options"]
            d["rule_ids"].append("V0")
            d["pending_cause"] = "重装配校验失败: " + bad
            status = "pending_review"
    new["adjudication"] = dict(ruleset=RULESET, status=status, rule_id="+".join(d["rule_ids"]), votes=d["detail"],
                               pending_cause=d["pending_cause"])
    return new


# ----------------------------------------------------------------- 投票 (调用千问)
def catalog_text(catalog):
    return "\n".join("%s|%s|%s|%s" % (c["code"], "读" if c["mode"] == "READ" else "写", c["name"], c.get("desc", "")) for c in catalog)


def system_prompt(catalog):
    import gen_workflow_set as G
    return ("你是食品工厂 AI 助手的独立裁判。下面是若干句用户原话（角色已标明）。请按产品口径独立判断：识别层应当把它归到目录里哪个意图，以及姿态。"
            "你看不到任何参考答案。\n\n" + G.WORLD + "\n\n" + G.POLICY + "\n\n" + G.STANCE_DOC + "\n\n"
            "补充口径（Steve 偏执行：能按合理默认执行就执行，只有真歧义才 ASK）：\n"
            "A 只读题（DIRECT）缺时间、范围、数量时按默认办，不算歧义；只读题里的同名人/物：两个都列出来，判 DIRECT。\n"
            "B 写操作（WRITE）无论信息齐不齐都是写意图（先展示再确认）；缺的参数有合理默认就仍判 WRITE。\n"
            "C 只有下面三种才判 ASK，并必须在 ask_reason 里填对应代码，否则不要判 ASK：\n"
            "  SAME_NAME：写操作的对象同名且有多个（张三两个车间、牛腱子两个库、东城两个客户），不知道指哪个会写错；\n"
            "  MISSING_REQUIRED：写操作缺必填参数且没有合理默认（哪一批、哪张单、多少数量）；\n"
            "  DIVERGENT_INTENTS：两个意图结果差别大且都说得通（如「放行还是拦住」「查还是录」只差一字）。\n"
            "D REFUSE 只用于超出权限或不该做的事（越权看别人数据、绕审批、改审计记录、造假）；"
            "OOD 只用于与工厂业务无关的闲聊。\n"
            "E intent 只能填下面目录里的代码；REFUSE 填空字符串，OOD 填 OUT_OF_DOMAIN。\n\n"
            "意图目录（代码|读写|名称|说明）：\n" + catalog_text(catalog))


def user_prompt(batch):
    import workflow_spec as W
    lines = ["%d｜%s｜%s" % (i, W.ROLES[b["role"]].split("（")[0], b["input"]) for i, b in enumerate(batch, 1)]
    return ("逐句判断（序号｜角色｜原话）：\n" + "\n".join(lines) +
            "\n\n只输出 JSON：{\"labels\":[{\"n\":1,\"stance\":\"DIRECT|ASK|WRITE|REFUSE|OOD\",\"ask_reason\":\"SAME_NAME|MISSING_REQUIRED|DIVERGENT_INTENTS 或空\","
            "\"intent\":\"目录代码\",\"intent_alternates\":[]}]}，每句都要有，不能漏。")


def read_jsonl(p):
    p = Path(p)
    return [json.loads(x) for x in p.read_text("utf-8").splitlines() if x.strip()] if p.exists() else []


def run_votes(client, rows, catalog, out_path, batch=14, workers=4, extra=None):
    """rows 里每题跑一票, 缓存键 id; 已有结果跳过。返回 {id: vote}。"""
    done = {r["id"]: r for r in read_jsonl(out_path)}
    todo = [r for r in rows if r["id"] not in done]
    sysp = system_prompt(catalog)
    lock = threading.Lock()
    batches = [todo[i:i + batch] for i in range(0, len(todo), batch)]

    def one(b):
        text, _ = client.chat(sysp, user_prompt(b), max_tokens=4000, temperature=0, extra=extra)
        t = text.strip()
        data = json.loads(t[t.find("{"): t.rfind("}") + 1])
        got = {int(x["n"]): x for x in data.get("labels", []) if isinstance(x, dict) and "n" in x}
        with lock, open(out_path, "a", encoding="utf-8", newline="") as f:
            for i, r in enumerate(b, 1):
                x = got.get(i)
                if x:
                    f.write(json.dumps(dict(id=r["id"], model=client.model, stance=x.get("stance"), ask_reason=x.get("ask_reason") or "",
                                            intent=x.get("intent") or "", intent_alternates=as_list(x.get("intent_alternates"))),
                                       ensure_ascii=False) + "\n")

    fails = 0
    with ThreadPoolExecutor(workers) as ex:
        for f in [ex.submit(one, b) for b in batches]:
            try:
                f.result()
            except Exception as e:  # noqa: BLE001 记录并计数, 不静默跳过
                fails += 1
                print("batch failed:", repr(e)[:160], file=sys.stderr)
    res = {r["id"]: r for r in read_jsonl(out_path)}
    print("votes %s: %d/%d rows have a vote, %d batch failures, spent %.3f USD, calls %d, tokens in/out %d/%d" %
          (client.model, sum(1 for r in rows if r["id"] in res), len(rows), fails, client.spent, client.calls, client.in_tok, client.out_tok))
    return res


def load_rows():
    return [json.loads(x) for x in DEV.read_text("utf-8").splitlines() if x.strip()]


def load_catalog():
    import scan_catalog
    return scan_catalog.scan(HERE.parents[1])


def need_v2(row, v1, by_code):
    """第三票的意图不在出题方意图及其替代里 -> 要第二票 (再多数决)。"""
    r = dict(row)
    r["intent_alternates"] = as_list(r["intent_alternates"])
    lab = label_stance_of(r)
    cand = [c for c in ([r["intent"]] + r["intent_alternates"]) if c in by_code]
    if (v1 or {}).get("intent") in cand:
        return False
    return r["stance"] in INTENTFUL or (lab in INTENTFUL)


def cmd_vote(a):
    import llm_client
    work = Path(a.work)
    work.mkdir(parents=True, exist_ok=True)
    catalog = load_catalog()
    by_code = {c["code"]: c for c in catalog}
    rows = [base_row(r) for r in load_rows()]
    amb = [r for r in rows if r.get("ambiguous")]
    c1 = llm_client.LLM("aliyun", V1_MODEL, a.cap1)
    v1 = run_votes(c1, amb, catalog, work / "votes-v1.jsonl", extra=NO_THINK)
    second = [r for r in amb if r["id"] in v1 and need_v2(r, v1[r["id"]], by_code)]
    print("second vote needed for %d rows" % len(second))
    c2 = llm_client.LLM("aliyun", V2_MODEL, a.cap2)
    run_votes(c2, second, catalog, work / "votes-v2.jsonl")
    print("total spent USD %.3f" % (c1.spent + c2.spent))
    return 0


def cmd_calibrate(a):
    import llm_client
    work = Path(a.work)
    work.mkdir(parents=True, exist_ok=True)
    catalog = load_catalog()
    by_code = {c["code"]: c for c in catalog}
    conf = [r for r in load_rows() if not r.get("ambiguous")]
    rnd = random.Random(20261115)
    pick = []
    for st in STANCES:
        pool = sorted([r for r in conf if r["stance"] == st], key=lambda r: r["id"])
        pick += rnd.sample(pool, min(a.per_stance, len(pool)))
    c1 = llm_client.LLM("aliyun", V1_MODEL, a.cap1)
    v1 = run_votes(c1, pick, catalog, work / "calib-v1.jsonl", extra=NO_THINK)
    agree = collections.defaultdict(lambda: [0, 0])
    intent_ok = [0, 0]
    confusion = collections.Counter()
    missing = 0
    for r in pick:
        v = v1.get(r["id"])
        if not v:
            missing += 1
            continue
        eff, _ = v1_effective(v, by_code)
        agree[r["stance"]][1] += 1
        agree[r["stance"]][0] += 1 if eff == r["stance"] else 0
        confusion[(r["stance"], eff)] += 1
        if r["stance"] in INTENTFUL:
            acc = {r["intent"], *as_list(r["intent_alternates"])}
            intent_ok[1] += 1
            intent_ok[0] += 1 if v.get("intent") in acc else 0
    res = dict(n=len(pick), missing=missing, per_stance={k: dict(k=v[0], n=v[1]) for k, v in agree.items()},
               intent=dict(k=intent_ok[0], n=intent_ok[1]), confusion={"%s->%s" % k: n for k, n in sorted(confusion.items(), key=str)},
               spent_usd=round(c1.spent, 4))
    (work / "calibration.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), "utf-8", newline="")
    print(json.dumps(res, ensure_ascii=False, indent=1))
    return 2 if missing > len(pick) * 0.05 else 0


def summarize(rows, orig_rows):
    out = dict(n_items=len(rows), n_confirmed=sum(1 for r in rows if not r["ambiguous"]), n_ambiguous=sum(1 for r in rows if r["ambiguous"]))
    adj = [r for r in rows if "adjudication" in r]
    out["adjudicated_rows"] = len(adj)
    out["resolved"] = sum(1 for r in adj if r["adjudication"]["status"] == "resolved")
    out["pending_review"] = sum(1 for r in adj if r["adjudication"]["status"] == "pending_review")
    out["resolved_by_stance"] = dict(collections.Counter(r["stance"] for r in adj if r["adjudication"]["status"] == "resolved"))
    out["transitions"] = dict(collections.Counter("%s->%s" % (r["orig_stance"], r["stance"]) for r in adj if r["adjudication"]["status"] == "resolved"))
    out["pending_by_cause"] = dict(collections.Counter(r["adjudication"]["rule_id"].split("+")[-1] for r in adj if r["adjudication"]["status"] == "pending_review"))
    out["rule_usage"] = dict(collections.Counter(x for r in adj for x in r["adjudication"]["rule_id"].split("+")))
    out["stance_after_all"] = dict(collections.Counter(r["stance"] for r in rows))
    out["stance_confirmed_after"] = dict(collections.Counter(r["stance"] for r in rows if not r["ambiguous"]))
    out["stance_confirmed_before"] = dict(collections.Counter(r["stance"] for r in orig_rows if not r["ambiguous"]))
    out["intents_confirmed_after"] = len({r["intent"] for r in rows if not r["ambiguous"] and r["intent"]})
    out["intents_confirmed_before"] = len({r["intent"] for r in orig_rows if not r["ambiguous"] and r["intent"]})
    return out


def cmd_apply(a):
    work = Path(a.work)
    catalog = load_catalog()
    by_code = {c["code"]: c for c in catalog}
    cur = load_rows()
    orig = [base_row(r) for r in cur]
    v1 = {r["id"]: r for r in read_jsonl(work / "votes-v1.jsonl")}
    v2 = {r["id"]: r for r in read_jsonl(work / "votes-v2.jsonl")}
    amb_ids = [r["id"] for r in orig if r.get("ambiguous")]
    no_vote = [i for i in amb_ids if i not in v1]
    if no_vote and not a.allow_missing:
        print("%d ambiguous rows have no first vote; run `vote` first (or --allow-missing to leave them pending)" % len(no_vote))
        return 2
    new = [apply_row(r, v1.get(r["id"]), v2.get(r["id"]), by_code) for r in orig]
    summ = summarize(new, orig)
    summ["rows_without_vote"] = len(no_vote)
    if not a.dry_run:
        DEV.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in new), "utf-8", newline="")
    if a.csv:
        Path(a.csv).parent.mkdir(parents=True, exist_ok=True)
        with open(a.csv, "w", encoding="utf-8-sig", newline="") as f:
            w = csv.writer(f)
            w.writerow(["id", "stage", "role", "style", "input", "orig_stance", "orig_intent", "orig_alternates", "label_stance",
                        "v1_stance", "v1_ask_reason", "v1_intent", "v2_intent", "rule_id", "pending_cause", "your_stance", "your_intent"])
            for r in new:
                ad = r.get("adjudication")
                if ad and ad["status"] == "pending_review":
                    v = ad["votes"]
                    w.writerow([r["id"], r["stage"], r["role"], r["style"], r["input"], r["orig_stance"], r["orig_intent"],
                                ";".join(as_list(r["orig_intent_alternates"])), v["label"], v["v1"], v["v1_ask_reason"], v["v1_intent"],
                                v["v2_intent"], ad["rule_id"], ad["pending_cause"], "", ""])
    print(json.dumps(summ, ensure_ascii=False, indent=1))
    if a.summary_out:
        Path(a.summary_out).write_text(json.dumps(summ, ensure_ascii=False, indent=1), "utf-8", newline="")
    return 0


def cmd_stats(a):
    rows = load_rows()
    print(json.dumps(summarize(rows, [base_row(r) for r in rows]), ensure_ascii=False, indent=1))
    return 0


def main():
    ap = argparse.ArgumentParser()
    sp = ap.add_subparsers(dest="cmd", required=True)
    for name in ("vote", "calibrate"):
        p = sp.add_parser(name)
        p.add_argument("--work", required=True)
        p.add_argument("--cap1", type=float, default=2.0, help="第三票模型花费上限 USD")
        p.add_argument("--cap2", type=float, default=0.5, help="第二票模型花费上限 USD")
        if name == "calibrate":
            p.add_argument("--per-stance", type=int, default=40)
    p = sp.add_parser("apply")
    p.add_argument("--work", required=True)
    p.add_argument("--csv", default="")
    p.add_argument("--summary-out", default="")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--allow-missing", action="store_true")
    sp.add_parser("stats")
    a = ap.parse_args()
    return dict(vote=cmd_vote, calibrate=cmd_calibrate, apply=cmd_apply, stats=cmd_stats)[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
